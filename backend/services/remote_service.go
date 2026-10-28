package services

import (
	"context"
	"errors"
	"fmt"
	"log"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/jkiddo/atvremote/pkg/v2/remote"

	"comfort-player-backend/utils"
)

const (
	remoteClientName = "Comfort Player"

	pairingSessionTTL    = 2 * time.Minute
	pairingStartTimeout  = 10 * time.Second
	pairingFinishTimeout = 15 * time.Second
	connectTimeout       = 5 * time.Second

	// Projectors usually ask "press POWER again to turn off".
	projectorPowerConfirmDelay = 200 * time.Millisecond
	powerKey                   = "KEYCODE_POWER"
)

var (
	// ErrNoPairingSession means pairing wasn't started, expired, or was cancelled on the TV.
	ErrNoPairingSession = errors.New("pairing session expired or cancelled, start pairing again")
	// ErrWrongCode means the pairing code didn't match; the session stays open for a retry.
	ErrWrongCode = errors.New("wrong pairing code")
	// ErrNotPaired means the TV rejected our certificate.
	ErrNotPaired = errors.New("device is not paired")
	// ErrUnreachable means the TV's remote service couldn't be reached.
	ErrUnreachable = errors.New("could not reach the device's remote service")
)

// PowerOffResult describes what PowerOff did.
type PowerOffResult string

const (
	PowerOffSent       PowerOffResult = "sent"
	PowerOffAlreadyOff PowerOffResult = "already_off"
)

type pairingSession struct {
	mu        sync.Mutex // serializes FinishPairing calls
	remote    *remote.AndroidTVRemote
	expiresAt time.Time
}

// RemoteService talks to Android TVs over the Android TV Remote v2 protocol.
type RemoteService struct {
	devices  *DeviceService
	certFile string
	keyFile  string

	certMu sync.Mutex

	mu       sync.Mutex
	sessions map[string]*pairingSession // by device ID

	powerMu sync.Mutex // one power-off at a time, so toggles never overlap
}

// NewRemoteService creates the service. The server's client certificate is
// kept in certDir and shared by all paired devices.
func NewRemoteService(certDir string, devices *DeviceService) *RemoteService {
	s := &RemoteService{
		devices:  devices,
		certFile: filepath.Join(certDir, "cert.pem"),
		keyFile:  filepath.Join(certDir, "key.pem"),
		sessions: map[string]*pairingSession{},
	}
	go s.expireSessionsLoop()
	return s
}

// Identify returns the name and MAC of the Android TV at ip, read from its
// pairing-port certificate. It doesn't need the device to be paired.
func (s *RemoteService) Identify(ctx context.Context, ip string) (string, string, error) {
	r, err := s.newRemote(ip)
	if err != nil {
		return "", "", err
	}
	return r.NameAndMAC(ctx)
}

// StartPairing connects to the device; the TV then shows a pairing code.
func (s *RemoteService) StartPairing(ctx context.Context, deviceID string) error {
	d, err := s.devices.Get(deviceID)
	if err != nil {
		return err
	}
	r, err := s.newRemote(d.IP)
	if err != nil {
		return err
	}

	ctx, cancel := context.WithTimeout(ctx, pairingStartTimeout)
	defer cancel()
	if err := r.StartPairing(ctx); err != nil {
		r.Disconnect()
		log.Printf("RemoteService: start pairing with %s (%s): %v", deviceID, d.IP, err)
		return fmt.Errorf("%w: %v", ErrUnreachable, err)
	}

	s.mu.Lock()
	old := s.sessions[deviceID]
	s.sessions[deviceID] = &pairingSession{remote: r, expiresAt: time.Now().Add(pairingSessionTTL)}
	s.mu.Unlock()
	if old != nil {
		old.remote.Disconnect()
	}
	log.Printf("RemoteService: pairing started with %s (%s)", deviceID, d.IP)
	return nil
}

// FinishPairing submits the code shown on the TV. On a wrong code the
// session stays open so the user can try again.
func (s *RemoteService) FinishPairing(deviceID, code string) error {
	code = strings.ToUpper(strings.TrimSpace(code))

	s.mu.Lock()
	sess := s.sessions[deviceID]
	s.mu.Unlock()
	if sess == nil || time.Now().After(sess.expiresAt) {
		s.dropSession(deviceID, sess)
		return ErrNoPairingSession
	}

	sess.mu.Lock()
	defer sess.mu.Unlock()

	// FinishPairing has no network timeout of its own; disconnecting unblocks it.
	done := make(chan error, 1)
	go func() { done <- sess.remote.FinishPairing(code) }()
	var err error
	select {
	case err = <-done:
	case <-time.After(pairingFinishTimeout):
		s.dropSession(deviceID, sess)
		<-done
		return fmt.Errorf("%w: timed out waiting for the TV", ErrUnreachable)
	}

	var invalidAuth *remote.InvalidAuth
	switch {
	case err == nil:
		s.dropSession(deviceID, sess)
		if _, err := s.devices.SetPaired(deviceID, true); err != nil {
			return err
		}
		log.Printf("RemoteService: paired with %s", deviceID)
		return nil
	case errors.As(err, &invalidAuth):
		log.Printf("RemoteService: wrong pairing code for %s: %v", deviceID, err)
		return ErrWrongCode
	default:
		// Connection closed: cancelled on the TV, or the TV dropped us after a bad secret.
		s.dropSession(deviceID, sess)
		log.Printf("RemoteService: pairing with %s ended: %v", deviceID, err)
		return ErrNoPairingSession
	}
}

// PowerOff turns the device off. It checks the power state first, so calling
// it twice never turns the device back on.
func (s *RemoteService) PowerOff(ctx context.Context, deviceID string) (PowerOffResult, error) {
	d, err := s.devices.Get(deviceID)
	if err != nil {
		return "", err
	}

	s.powerMu.Lock()
	defer s.powerMu.Unlock()

	r, err := s.newRemote(d.IP)
	if err != nil {
		return "", err
	}
	defer r.Disconnect()

	ctx, cancel := context.WithTimeout(ctx, connectTimeout)
	defer cancel()
	if err := r.Connect(ctx); err != nil {
		var invalidAuth *remote.InvalidAuth
		if errors.As(err, &invalidAuth) {
			if d.Paired {
				if _, err := s.devices.SetPaired(deviceID, false); err != nil {
					log.Printf("RemoteService: marking %s unpaired: %v", deviceID, err)
				}
			}
			return "", ErrNotPaired
		}
		return "", fmt.Errorf("%w: %v", ErrUnreachable, err)
	}

	if isOn, ok := r.IsOn(); ok && !isOn {
		log.Printf("RemoteService: %s is already off", deviceID)
		return PowerOffAlreadyOff, nil
	}
	if err := sendPowerOff(r, d.IsProjector, time.Sleep); err != nil {
		return "", fmt.Errorf("%w: %v", ErrUnreachable, err)
	}
	log.Printf("RemoteService: power off sent to %s (projector=%v)", deviceID, d.IsProjector)
	return PowerOffSent, nil
}

type keySender interface {
	SendKeyCommand(keyCode string) error
}

// sendPowerOff presses POWER once for a TV, or twice (with a short pause)
// for a projector, which asks for confirmation.
func sendPowerOff(k keySender, projector bool, sleep func(time.Duration)) error {
	if err := k.SendKeyCommand(powerKey); err != nil {
		return err
	}
	if !projector {
		return nil
	}
	sleep(projectorPowerConfirmDelay)
	return k.SendKeyCommand(powerKey)
}

func (s *RemoteService) newRemote(ip string) (*remote.AndroidTVRemote, error) {
	if err := s.ensureCert(); err != nil {
		return nil, err
	}
	return remote.New(remoteClientName, s.certFile, s.keyFile, ip), nil
}

func (s *RemoteService) ensureCert() error {
	s.certMu.Lock()
	defer s.certMu.Unlock()
	if err := utils.EnsureDir(filepath.Dir(s.certFile)); err != nil {
		return err
	}
	created, err := remote.New(remoteClientName, s.certFile, s.keyFile, "").GenerateCertIfMissing()
	if err != nil {
		return fmt.Errorf("generate remote client certificate: %w", err)
	}
	if created {
		log.Printf("RemoteService: generated client certificate in %s", filepath.Dir(s.certFile))
	}
	return nil
}

// dropSession removes sess (if it is still the current one) and disconnects it.
func (s *RemoteService) dropSession(deviceID string, sess *pairingSession) {
	if sess == nil {
		return
	}
	s.mu.Lock()
	if s.sessions[deviceID] == sess {
		delete(s.sessions, deviceID)
	}
	s.mu.Unlock()
	sess.remote.Disconnect()
}

func (s *RemoteService) expireSessionsLoop() {
	for range time.Tick(30 * time.Second) {
		now := time.Now()
		var expired []*pairingSession
		s.mu.Lock()
		for id, sess := range s.sessions {
			if now.After(sess.expiresAt) {
				expired = append(expired, sess)
				delete(s.sessions, id)
			}
		}
		s.mu.Unlock()
		for _, sess := range expired {
			sess.remote.Disconnect()
		}
	}
}

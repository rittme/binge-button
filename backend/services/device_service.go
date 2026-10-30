package services

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"net"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"

	"comfort-player-backend/store"
)

const (
	devicesNamespace = "devices"

	// How often LastSeen is written to disk for an already-known device.
	lastSeenPersistInterval = time.Minute
	// How often we retry looking up the name/MAC of an IP that has none.
	identifyRetryInterval = 10 * time.Minute
	identifyTimeout       = 3 * time.Second
)

// ErrDeviceNotFound is returned when no device matches the given ID or IP.
var ErrDeviceNotFound = errors.New("device not found")

// Device is a TV/projector that has talked to the server.
type Device struct {
	ID          string `json:"id"` // "mac:<mac>" when known, else "ip:<ip>"
	Name        string `json:"name,omitempty"`
	MAC         string `json:"mac,omitempty"`
	IP          string `json:"ip"`
	UserAgent   string `json:"userAgent,omitempty"`
	FirstSeen   int64  `json:"firstSeen"`
	LastSeen    int64  `json:"lastSeen"`
	Paired      bool   `json:"paired"`
	PairedAt    int64  `json:"pairedAt,omitempty"`
	IsProjector bool   `json:"isProjector"`
}

// IdentifyFunc looks up a device's name and MAC address by IP.
type IdentifyFunc func(ctx context.Context, ip string) (name, mac string, err error)

// DeviceService keeps track of devices in the settings store.
type DeviceService struct {
	store *store.Store
	now   func() time.Time

	mu           sync.Mutex
	identify     IdentifyFunc
	identifyLast map[string]time.Time // per IP
}

// NewDeviceService creates a device service backed by st.
func NewDeviceService(st *store.Store) *DeviceService {
	return &DeviceService{
		store:        st,
		now:          time.Now,
		identifyLast: map[string]time.Time{},
	}
}

// SetIdentifier sets the function used to resolve a device's name and MAC.
func (s *DeviceService) SetIdentifier(fn IdentifyFunc) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.identify = fn
}

// List returns all devices, most recently seen first.
func (s *DeviceService) List() []Device {
	s.mu.Lock()
	defer s.mu.Unlock()
	devices := s.all()
	sort.Slice(devices, func(i, j int) bool { return devices[i].LastSeen > devices[j].LastSeen })
	return devices
}

// Get returns the device with the given ID.
func (s *DeviceService) Get(id string) (Device, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.get(id)
}

// FindByIP returns the most recently seen device using ip.
func (s *DeviceService) FindByIP(ip string) (Device, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	d, ok := s.findByIP(ip)
	if !ok {
		return Device{}, ErrDeviceNotFound
	}
	return d, nil
}

// SetProjector sets whether the device is a projector.
func (s *DeviceService) SetProjector(id string, projector bool) (Device, error) {
	return s.update(id, func(d *Device) { d.IsProjector = projector })
}

// Delete forgets a device.
func (s *DeviceService) Delete(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, err := s.get(id); err != nil {
		return err
	}
	return s.store.Delete(devicesNamespace, id)
}

// SetPaired marks the device as paired or unpaired.
func (s *DeviceService) SetPaired(id string, paired bool) (Device, error) {
	return s.update(id, func(d *Device) {
		d.Paired = paired
		if paired {
			d.PairedAt = s.now().Unix()
		} else {
			d.PairedAt = 0
		}
	})
}

// Seen records that a request came from a device. Unknown IPs get a new
// device, and a background lookup of the device's name/MAC.
func (s *DeviceService) Seen(r *http.Request) {
	ip := ClientIP(r)
	// Skip the server itself (e.g. the Docker healthcheck hitting /api/show/info).
	if parsed := net.ParseIP(ip); parsed == nil || parsed.IsLoopback() {
		return
	}
	ua := r.UserAgent()
	now := s.now()

	s.mu.Lock()
	d, found := s.findByIP(ip)
	if !found {
		d = Device{ID: "ip:" + ip, IP: ip, FirstSeen: now.Unix()}
	}
	changed := !found || d.UserAgent != ua ||
		now.Sub(time.Unix(d.LastSeen, 0)) >= lastSeenPersistInterval
	d.UserAgent = ua
	d.LastSeen = now.Unix()
	if changed {
		if err := s.store.Set(devicesNamespace, d.ID, d); err != nil {
			log.Printf("DeviceService: saving device %s: %v", d.ID, err)
		}
	}

	shouldIdentify := d.MAC == "" && s.identify != nil &&
		now.Sub(s.identifyLast[ip]) >= identifyRetryInterval
	if shouldIdentify {
		s.identifyLast[ip] = now
	}
	identify := s.identify
	s.mu.Unlock()

	if shouldIdentify {
		go s.resolve(identify, ip)
	}
}

// resolve looks up the name/MAC for ip and merges the IP-keyed record into
// a MAC-keyed one, so the device keeps its settings when its IP changes.
func (s *DeviceService) resolve(identify IdentifyFunc, ip string) {
	ctx, cancel := context.WithTimeout(context.Background(), identifyTimeout)
	defer cancel()
	name, mac, err := identify(ctx, ip)
	if err != nil || mac == "" {
		log.Printf("DeviceService: no name/MAC for %s (not an Android TV or remote service unreachable): %v", ip, err)
		return
	}
	if err := s.applyIdentity(ip, name, mac); err != nil {
		log.Printf("DeviceService: saving identity for %s: %v", ip, err)
	}
}

func (s *DeviceService) applyIdentity(ip, name, mac string) error {
	mac = strings.ToLower(mac)
	id := "mac:" + mac

	s.mu.Lock()
	defer s.mu.Unlock()

	merged, err := s.get(id)
	if err != nil {
		merged = Device{ID: id, FirstSeen: s.now().Unix()}
	}
	ipID := "ip:" + ip
	if ipDev, err := s.get(ipID); err == nil {
		merged = mergeDevices(merged, ipDev)
		if err := s.store.Delete(devicesNamespace, ipID); err != nil {
			return err
		}
	}
	merged.ID = id
	merged.MAC = mac
	merged.IP = ip
	if name != "" {
		merged.Name = name
	}
	log.Printf("DeviceService: %s identified as %q (%s)", ip, name, mac)
	return s.store.Set(devicesNamespace, id, merged)
}

// mergeDevices folds the IP-keyed record b into the MAC-keyed record a.
func mergeDevices(a, b Device) Device {
	if a.FirstSeen == 0 || (b.FirstSeen != 0 && b.FirstSeen < a.FirstSeen) {
		a.FirstSeen = b.FirstSeen
	}
	if b.LastSeen > a.LastSeen {
		a.LastSeen = b.LastSeen
		a.UserAgent = b.UserAgent
	}
	if b.Paired && !a.Paired {
		a.Paired, a.PairedAt = true, b.PairedAt
	}
	a.IsProjector = a.IsProjector || b.IsProjector
	return a
}

func (s *DeviceService) update(id string, fn func(*Device)) (Device, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	d, err := s.get(id)
	if err != nil {
		return Device{}, err
	}
	fn(&d)
	return d, s.store.Set(devicesNamespace, id, d)
}

// The helpers below must be called with s.mu held.

func (s *DeviceService) all() []Device {
	raw := s.store.List(devicesNamespace)
	devices := make([]Device, 0, len(raw))
	for id, r := range raw {
		var d Device
		if err := json.Unmarshal(r, &d); err != nil {
			log.Printf("DeviceService: skipping unreadable device %s: %v", id, err)
			continue
		}
		devices = append(devices, d)
	}
	return devices
}

func (s *DeviceService) get(id string) (Device, error) {
	var d Device
	ok, err := s.store.Get(devicesNamespace, id, &d)
	if err != nil {
		return Device{}, err
	}
	if !ok {
		return Device{}, ErrDeviceNotFound
	}
	return d, nil
}

func (s *DeviceService) findByIP(ip string) (Device, bool) {
	var best Device
	found := false
	for _, d := range s.all() {
		if d.IP == ip && (!found || d.LastSeen > best.LastSeen) {
			best, found = d, true
		}
	}
	return best, found
}

// ClientIP returns the IP address of the request's sender. When the request
// comes through a reverse proxy on the local network (e.g. Nginx Proxy
// Manager or Traefik in another container), it uses the client IP the proxy
// forwards in X-Real-IP / X-Forwarded-For instead of the proxy's own IP.
func ClientIP(r *http.Request) string {
	peer, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		peer = r.RemoteAddr
	}
	peerIP := net.ParseIP(peer)
	// Only trust forwarding headers from local proxies, so nobody on the
	// internet can claim to be a device on the LAN.
	if peerIP == nil || !(peerIP.IsPrivate() || peerIP.IsLoopback()) {
		return peer
	}
	if ip := net.ParseIP(strings.TrimSpace(r.Header.Get("X-Real-IP"))); ip != nil {
		return ip.String()
	}
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		// "client, proxy1, proxy2": the first entry is the original client.
		first, _, _ := strings.Cut(xff, ",")
		if ip := net.ParseIP(strings.TrimSpace(first)); ip != nil {
			return ip.String()
		}
	}
	return peer
}

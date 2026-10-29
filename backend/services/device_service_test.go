package services

import (
	"context"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"comfort-player-backend/store"
)

func newTestDeviceService(t *testing.T) (*DeviceService, *time.Time) {
	t.Helper()
	st, err := store.Open(filepath.Join(t.TempDir(), "settings.json"))
	if err != nil {
		t.Fatal(err)
	}
	s := NewDeviceService(st)
	now := time.Unix(1_700_000_000, 0)
	s.now = func() time.Time { return now }
	return s, &now
}

func seen(s *DeviceService, ip string) {
	r := httptest.NewRequest("GET", "/api/show/info", nil)
	r.RemoteAddr = ip + ":51234"
	r.Header.Set("User-Agent", "okhttp/4.11.0")
	s.Seen(r)
}

func TestSeenCreatesIPDevice(t *testing.T) {
	s, _ := newTestDeviceService(t)
	seen(s, "192.168.1.20")

	d, err := s.FindByIP("192.168.1.20")
	if err != nil {
		t.Fatal(err)
	}
	if d.ID != "ip:192.168.1.20" || d.UserAgent != "okhttp/4.11.0" {
		t.Fatalf("unexpected device %+v", d)
	}
}

func TestIdentityMergesIPRecordIntoMACRecord(t *testing.T) {
	s, _ := newTestDeviceService(t)
	seen(s, "192.168.1.20")
	if _, err := s.SetProjector("ip:192.168.1.20", true); err != nil {
		t.Fatal(err)
	}

	if err := s.applyIdentity("192.168.1.20", "Living Room", "AA:BB:CC:DD:EE:FF"); err != nil {
		t.Fatal(err)
	}

	devices := s.List()
	if len(devices) != 1 {
		t.Fatalf("expected 1 device after merge, got %d: %+v", len(devices), devices)
	}
	d := devices[0]
	if d.ID != "mac:aa:bb:cc:dd:ee:ff" || d.Name != "Living Room" || !d.IsProjector || d.IP != "192.168.1.20" {
		t.Fatalf("unexpected merged device %+v", d)
	}
}

func TestKnownMACMovesToNewIP(t *testing.T) {
	s, now := newTestDeviceService(t)
	seen(s, "192.168.1.20")
	if err := s.applyIdentity("192.168.1.20", "TV", "aa:bb:cc:dd:ee:ff"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.SetPaired("mac:aa:bb:cc:dd:ee:ff", true); err != nil {
		t.Fatal(err)
	}

	// Same TV comes back later with a new DHCP lease.
	*now = now.Add(time.Hour)
	seen(s, "192.168.1.42")
	if err := s.applyIdentity("192.168.1.42", "TV", "aa:bb:cc:dd:ee:ff"); err != nil {
		t.Fatal(err)
	}

	devices := s.List()
	if len(devices) != 1 {
		t.Fatalf("expected 1 device, got %d: %+v", len(devices), devices)
	}
	d := devices[0]
	if d.IP != "192.168.1.42" || !d.Paired {
		t.Fatalf("expected paired device at new IP, got %+v", d)
	}
	if found, err := s.FindByIP("192.168.1.42"); err != nil || found.ID != d.ID {
		t.Fatalf("FindByIP: %+v %v", found, err)
	}
}

func TestSeenTriggersIdentifyOnce(t *testing.T) {
	s, _ := newTestDeviceService(t)
	calls := make(chan string, 4)
	s.SetIdentifier(func(ctx context.Context, ip string) (string, string, error) {
		calls <- ip
		return "TV", "11:22:33:44:55:66", nil
	})

	seen(s, "10.0.0.5")
	select {
	case ip := <-calls:
		if ip != "10.0.0.5" {
			t.Fatalf("identify called with %s", ip)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("identify not called")
	}

	// Wait for the merge to land, then a second request must not re-identify.
	deadline := time.Now().Add(2 * time.Second)
	for {
		if d, err := s.FindByIP("10.0.0.5"); err == nil && d.MAC != "" {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("identity never applied")
		}
		time.Sleep(10 * time.Millisecond)
	}
	seen(s, "10.0.0.5")
	select {
	case <-calls:
		t.Fatal("identify called again for a device that already has a MAC")
	case <-time.After(100 * time.Millisecond):
	}
}

func TestSeenIgnoresLoopback(t *testing.T) {
	s, _ := newTestDeviceService(t)
	seen(s, "127.0.0.1")
	if len(s.List()) != 0 {
		t.Fatal("loopback request should not create a device")
	}
}

package services

import (
	"testing"
	"time"
)

type recordingSender struct {
	keys  []string
	slept []time.Duration
}

func (r *recordingSender) SendKeyCommand(k string) error {
	r.keys = append(r.keys, k)
	return nil
}

func TestSendPowerOffTV(t *testing.T) {
	r := &recordingSender{}
	if err := sendPowerOff(r, false, time.Second, func(d time.Duration) { r.slept = append(r.slept, d) }); err != nil {
		t.Fatal(err)
	}
	if len(r.keys) != 1 || r.keys[0] != powerKey || len(r.slept) != 0 {
		t.Fatalf("TV: keys=%v slept=%v", r.keys, r.slept)
	}
}

func TestSendPowerOffProjector(t *testing.T) {
	r := &recordingSender{}
	if err := sendPowerOff(r, true, 1500*time.Millisecond, func(d time.Duration) { r.slept = append(r.slept, d) }); err != nil {
		t.Fatal(err)
	}
	if len(r.keys) != 2 || r.keys[0] != powerKey || r.keys[1] != powerKey {
		t.Fatalf("projector: keys=%v", r.keys)
	}
	if len(r.slept) != 1 || r.slept[0] != 1500*time.Millisecond {
		t.Fatalf("projector: expected one 1.5s pause between presses, got %v", r.slept)
	}
}

func TestPowerDelayDefault(t *testing.T) {
	if got := (Device{}).PowerDelay(); got != DefaultProjectorPowerDelay {
		t.Fatalf("default delay = %v", got)
	}
	if got := (Device{PowerDelayMs: 2500}).PowerDelay(); got != 2500*time.Millisecond {
		t.Fatalf("custom delay = %v", got)
	}
}

func TestPowerOffAndConfirmRetriesProjector(t *testing.T) {
	r := &recordingSender{}
	waits := 0
	// First wait: still on. Second wait (after the extra press): off.
	waitOff := func() bool { waits++; return waits == 2 }
	ok, err := powerOffAndConfirm(r, true, time.Second, func(time.Duration) {}, waitOff)
	if err != nil || !ok {
		t.Fatalf("ok=%v err=%v", ok, err)
	}
	if len(r.keys) != 3 {
		t.Fatalf("expected 2 presses + 1 retry, got %v", r.keys)
	}
}

func TestPowerOffAndConfirmTVNoRetry(t *testing.T) {
	r := &recordingSender{}
	ok, err := powerOffAndConfirm(r, false, time.Second, func(time.Duration) {}, func() bool { return false })
	if err != nil || ok {
		t.Fatalf("ok=%v err=%v", ok, err)
	}
	if len(r.keys) != 1 {
		t.Fatalf("a TV should get a single press, got %v", r.keys)
	}
}

func TestPowerOffAndConfirmImmediate(t *testing.T) {
	r := &recordingSender{}
	ok, _ := powerOffAndConfirm(r, true, time.Second, func(time.Duration) {}, func() bool { return true })
	if !ok || len(r.keys) != 2 {
		t.Fatalf("ok=%v keys=%v", ok, r.keys)
	}
}

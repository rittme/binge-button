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
	if err := sendPowerOff(r, false, func(d time.Duration) { r.slept = append(r.slept, d) }); err != nil {
		t.Fatal(err)
	}
	if len(r.keys) != 1 || r.keys[0] != powerKey || len(r.slept) != 0 {
		t.Fatalf("TV: keys=%v slept=%v", r.keys, r.slept)
	}
}

func TestSendPowerOffProjector(t *testing.T) {
	r := &recordingSender{}
	if err := sendPowerOff(r, true, func(d time.Duration) { r.slept = append(r.slept, d) }); err != nil {
		t.Fatal(err)
	}
	if len(r.keys) != 2 || r.keys[0] != powerKey || r.keys[1] != powerKey {
		t.Fatalf("projector: keys=%v", r.keys)
	}
	if len(r.slept) != 1 || r.slept[0] != 200*time.Millisecond {
		t.Fatalf("projector: expected one 200ms pause between presses, got %v", r.slept)
	}
}

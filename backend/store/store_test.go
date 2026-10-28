package store

import (
	"os"
	"path/filepath"
	"testing"
)

type item struct {
	Name  string `json:"name"`
	Count int    `json:"count"`
}

func TestRoundTripAndReload(t *testing.T) {
	path := filepath.Join(t.TempDir(), "settings.json")
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}

	if err := s.Set("devices", "a", item{Name: "tv", Count: 1}); err != nil {
		t.Fatal(err)
	}
	if err := s.Set("auth", "a", item{Name: "other"}); err != nil {
		t.Fatal(err)
	}

	// Reload from disk.
	s2, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	var got item
	ok, err := s2.Get("devices", "a", &got)
	if err != nil || !ok || got.Name != "tv" || got.Count != 1 {
		t.Fatalf("got %+v ok=%v err=%v", got, ok, err)
	}

	// Namespaces are isolated.
	ok, _ = s2.Get("auth", "a", &got)
	if !ok || got.Name != "other" {
		t.Fatalf("auth namespace: got %+v", got)
	}
	if len(s2.List("devices")) != 1 {
		t.Fatalf("expected 1 device, got %d", len(s2.List("devices")))
	}

	if err := s2.Delete("devices", "a"); err != nil {
		t.Fatal(err)
	}
	if ok, _ := s2.Get("devices", "a", &got); ok {
		t.Fatal("expected key to be deleted")
	}
	if _, err := os.Stat(path + ".tmp"); !os.IsNotExist(err) {
		t.Fatal("temp file left behind")
	}
}

func TestOpenMissingFile(t *testing.T) {
	s, err := Open(filepath.Join(t.TempDir(), "nope", "settings.json"))
	if err != nil {
		t.Fatal(err)
	}
	var got item
	if ok, err := s.Get("x", "y", &got); ok || err != nil {
		t.Fatalf("ok=%v err=%v", ok, err)
	}
}

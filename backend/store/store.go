// Package store is a small namespaced key-value settings store backed by a
// single JSON file. Each feature keeps its data in its own namespace
// (e.g. "devices", later "auth"), so new settings don't need store changes.
package store

import (
	"encoding/json"
	"fmt"
	"sync"

	"comfort-player-backend/utils"
)

// Store holds settings in memory and persists them on every write.
type Store struct {
	path string
	mu   sync.RWMutex
	data map[string]map[string]json.RawMessage
}

// Open loads the store from path, starting empty if the file doesn't exist.
func Open(path string) (*Store, error) {
	s := &Store{path: path, data: map[string]map[string]json.RawMessage{}}
	if utils.FileExists(path) {
		if err := utils.ReadJSON(path, &s.data); err != nil {
			return nil, fmt.Errorf("read settings %s: %w", path, err)
		}
		if s.data == nil {
			s.data = map[string]map[string]json.RawMessage{}
		}
	}
	return s, nil
}

// Get decodes the value at ns/key into out. It reports false if the key is missing.
func (s *Store) Get(ns, key string, out any) (bool, error) {
	s.mu.RLock()
	raw, ok := s.data[ns][key]
	s.mu.RUnlock()
	if !ok {
		return false, nil
	}
	return true, json.Unmarshal(raw, out)
}

// Set stores v at ns/key and saves the file.
func (s *Store) Set(ns, key string, v any) error {
	raw, err := json.Marshal(v)
	if err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.data[ns] == nil {
		s.data[ns] = map[string]json.RawMessage{}
	}
	s.data[ns][key] = raw
	return s.save()
}

// Delete removes ns/key and saves the file. Deleting a missing key is a no-op.
func (s *Store) Delete(ns, key string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.data[ns][key]; !ok {
		return nil
	}
	delete(s.data[ns], key)
	return s.save()
}

// List returns a copy of every key in ns with its raw JSON value.
func (s *Store) List(ns string) map[string]json.RawMessage {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make(map[string]json.RawMessage, len(s.data[ns]))
	for k, v := range s.data[ns] {
		out[k] = v
	}
	return out
}

// save must be called with s.mu held.
func (s *Store) save() error {
	return utils.WriteJSONAtomic(s.path, s.data)
}

package services

import (
	"log"
	"sync"
	"time"

	"comfort-player-backend/models"
	"comfort-player-backend/utils"
)

// StateService handles the current playback state
type StateService struct {
	stateFile string
	state     models.ServerState
	mutex     sync.RWMutex
}

// NewStateService creates a new state service
func NewStateService(stateFile string) *StateService {
	service := &StateService{
		stateFile: stateFile,
		state:     models.ServerState{},
	}

	// Load existing state if it exists
	service.loadState()
	return service
}

// GetState returns the current server state
func (s *StateService) GetState() models.ServerState {
	s.mutex.RLock()
	defer s.mutex.RUnlock()
	s.loadState()
	return s.state
}

// UpdateState updates the server state
func (s *StateService) UpdateState(episodeID string, playbackTimeSeconds int64) error {
	log.Printf("UpdateState: Updating state - EpisodeID: %s, PlaybackTime: %d", episodeID, playbackTimeSeconds)
	
	s.mutex.Lock()
	defer s.mutex.Unlock()

	s.state.CurrentEpisodeID = episodeID
	s.state.PlaybackTimeSeconds = playbackTimeSeconds
	s.state.LastUpdated = time.Now().Unix()
	
	log.Printf("UpdateState: State updated in memory, saving to file: %s", s.stateFile)

	// Save to file
	err := utils.WriteJSON(s.stateFile, s.state)
	if err != nil {
		log.Printf("UpdateState: Error saving state to file: %v", err)
		return err
	}
	
	log.Printf("UpdateState: State saved successfully")
	return nil
}

// loadState loads the state from file
func (s *StateService) loadState() {
	log.Printf("loadState: Loading state from file: %s", s.stateFile)
	
	s.mutex.Lock()
	defer s.mutex.Unlock()

	if utils.FileExists(s.stateFile) {
		err := utils.ReadJSON(s.stateFile, &s.state)
		if err != nil {
			log.Printf("loadState: Error reading state file, initializing with default values: %v", err)
			// If there's an error reading the state file, initialize with default values
			s.state = models.ServerState{}
		} else {
			log.Printf("loadState: State loaded successfully - EpisodeID: %s, PlaybackTime: %d", s.state.CurrentEpisodeID, s.state.PlaybackTimeSeconds)
		}
	} else {
		log.Printf("loadState: State file not found, initializing with default values")
		// Initialize with default values
		s.state = models.ServerState{}
	}
}

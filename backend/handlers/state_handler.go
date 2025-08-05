package handlers

import (
	"encoding/json"
	"log"
	"net/http"

	"comfort-player-backend/models"
	"comfort-player-backend/services"
)

// StateHandler handles playback state related requests
type StateHandler struct {
	stateService *services.StateService
	showService  *services.ShowService
}

// NewStateHandler creates a new state handler
func NewStateHandler(stateService *services.StateService, showService *services.ShowService) *StateHandler {
	return &StateHandler{
		stateService: stateService,
		showService:  showService,
	}
}

// GetShowInfo handles GET /api/show/info
func (h *StateHandler) GetShowInfo(w http.ResponseWriter, r *http.Request) {
	log.Printf("GetShowInfo: Request received")
	
	// Get all episodes
	showInfo, err := h.showService.GetShowInfo()
	if err != nil {
		log.Printf("GetShowInfo: Error getting episodes: %v", err)
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	// Convert relative URLs to full URLs
	host := r.Host
	for i := range showInfo.Episodes {
		// Convert relative video URL to full URL
		if showInfo.Episodes[i].VideoURL != "" && showInfo.Episodes[i].VideoURL[0] == '/' {
			showInfo.Episodes[i].VideoURL = "http://" + host + showInfo.Episodes[i].VideoURL
		}
		// Convert relative subtitle URL to full URL
		if showInfo.Episodes[i].SubtitleURL != "" && showInfo.Episodes[i].SubtitleURL[0] == '/' {
			showInfo.Episodes[i].SubtitleURL = "http://" + host + showInfo.Episodes[i].SubtitleURL
		}
	}

	// Get current state
	state := h.stateService.GetState()
	log.Printf("GetShowInfo: Current state - EpisodeID: %s, PlaybackTime: %d", state.CurrentEpisodeID, state.PlaybackTimeSeconds)

	// Set current episode and playback time in response
	showInfo.CurrentEpisodeID = state.CurrentEpisodeID
	showInfo.PlaybackTimeSeconds = state.PlaybackTimeSeconds

	// If no current episode is set, set the first episode as current
	if showInfo.CurrentEpisodeID == "" && len(showInfo.Episodes) > 0 {
		showInfo.CurrentEpisodeID = showInfo.Episodes[0].ID
		log.Printf("GetShowInfo: Setting first episode as current: %s", showInfo.CurrentEpisodeID)
	}

	log.Printf("GetShowInfo: Returning %d episodes", len(showInfo.Episodes))
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(showInfo)
}

// UpdatePlaybackState handles POST /api/show/state
func (h *StateHandler) UpdatePlaybackState(w http.ResponseWriter, r *http.Request) {
	log.Printf("UpdatePlaybackState: Request received")
	
	var request models.PlaybackStateUpdateRequest

	// Decode request body
	if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
		log.Printf("UpdatePlaybackState: Error decoding JSON: %v", err)
		http.Error(w, "Invalid JSON", http.StatusBadRequest)
		return
	}
	
	log.Printf("UpdatePlaybackState: Received - EpisodeID: %s, PlaybackTime: %d", request.EpisodeID, request.PlaybackTimeSeconds)

	// Validate required fields
	if request.EpisodeID == "" {
		log.Printf("UpdatePlaybackState: Episode ID is required")
		http.Error(w, "Episode ID is required", http.StatusBadRequest)
		return
	}

	// Update state
	if err := h.stateService.UpdateState(request.EpisodeID, request.PlaybackTimeSeconds); err != nil {
		log.Printf("UpdatePlaybackState: Error updating state: %v", err)
		http.Error(w, "Failed to update state", http.StatusInternalServerError)
		return
	}
	
	log.Printf("UpdatePlaybackState: State updated successfully")

	// Return success response
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]bool{"success": true})
}

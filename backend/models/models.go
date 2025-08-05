package models

// EpisodeInfo represents a single episode's information
type EpisodeInfo struct {
	ID          string `json:"id"`          // e.g., "Show_S01E01"
	Title       string `json:"title"`       // Optional: "The First Episode"
	VideoURL    string `json:"videoUrl"`
	SubtitleURL string `json:"subtitleUrl"` // URL for the .srt or .vtt file
}

// ShowInfoResponse represents the overall show information and current state
type ShowInfoResponse struct {
	Episodes             []EpisodeInfo `json:"episodes"`
	CurrentEpisodeID     string        `json:"currentEpisodeId"`
	PlaybackTimeSeconds  int64         `json:"playbackTimeSeconds"`
}

// PlaybackStateUpdateRequest represents the state to be sent to the server
type PlaybackStateUpdateRequest struct {
	EpisodeID           string `json:"episodeId"`
	PlaybackTimeSeconds int64  `json:"playbackTimeSeconds"`
}

// ServerState represents the server's current state
type ServerState struct {
	CurrentEpisodeID    string `json:"currentEpisodeId"`
	PlaybackTimeSeconds int64  `json:"playbackTimeSeconds"`
	LastUpdated         int64  `json:"lastUpdated"` // Unix timestamp
}

package services

import (
	"fmt"
	"log"
	"path/filepath"
	"sort"
	"strings"

	"comfort-player-backend/config"
	"comfort-player-backend/models"
	"comfort-player-backend/utils"
)

// ShowService handles show-related operations
type ShowService struct {
	config *config.Config
}

// NewShowService creates a new show service
func NewShowService(config *config.Config) *ShowService {
	return &ShowService{
		config: config,
	}
}

// GetShowInfo returns the show information including all episodes
func (s *ShowService) GetShowInfo() (*models.ShowInfoResponse, error) {
	log.Printf("GetShowInfo: Getting all episodes")
	
	episodes, err := s.GetAllEpisodes()
	if err != nil {
		log.Printf("GetShowInfo: Error getting episodes: %v", err)
		return nil, fmt.Errorf("failed to get episodes: %w", err)
	}
	
	log.Printf("GetShowInfo: Found %d episodes", len(episodes))

	// Sort episodes by ID to ensure consistent order
	sort.Slice(episodes, func(i, j int) bool {
		return episodes[i].ID < episodes[j].ID
	})

	return &models.ShowInfoResponse{
		Episodes: episodes,
	}, nil
}

// GetAllEpisodes returns all episodes from all seasons by reading JSON files
func (s *ShowService) GetAllEpisodes() ([]models.EpisodeInfo, error) {
	log.Printf("GetAllEpisodes: Searching for episodes in %s", s.config.SeasonsDir)
	
	var allEpisodes []models.EpisodeInfo

	// Check if seasons directory exists
	if !utils.FileExists(s.config.SeasonsDir) {
		log.Printf("GetAllEpisodes: Seasons directory not found: %s", s.config.SeasonsDir)
		return allEpisodes, nil // Return empty list if directory doesn't exist
	}

	// Find all JSON files in the seasons directory
	jsonFiles, err := utils.FindFiles(s.config.SeasonsDir, "*.json")
	if err != nil {
		log.Printf("GetAllEpisodes: Error finding JSON files: %v", err)
		return nil, fmt.Errorf("failed to find JSON files: %w", err)
	}
	
	log.Printf("GetAllEpisodes: Found %d JSON files", len(jsonFiles))

	// Process each JSON file
	for _, jsonFile := range jsonFiles {
		log.Printf("GetAllEpisodes: Processing JSON file: %s", jsonFile)
		
		var episodes []models.EpisodeInfo
		
		// Read JSON file
		if err := utils.ReadJSON(jsonFile, &episodes); err != nil {
			log.Printf("GetAllEpisodes: Error reading JSON file %s: %v", jsonFile, err)
			// Skip files that can't be read or parsed
			continue
		}
		
		log.Printf("GetAllEpisodes: Found %d episodes in %s", len(episodes), jsonFile)
		
		// Add episodes to the list
		allEpisodes = append(allEpisodes, episodes...)
	}
	
	log.Printf("GetAllEpisodes: Total episodes found: %d", len(allEpisodes))

	// Sort episodes by ID to ensure consistent order
	sort.Slice(allEpisodes, func(i, j int) bool {
		return allEpisodes[i].ID < allEpisodes[j].ID
	})

	return allEpisodes, nil
}


// GetNextEpisodeID returns the ID of the next episode in sequence
func (s *ShowService) GetNextEpisodeID(currentEpisodeID string) (string, error) {
	log.Printf("GetNextEpisodeID: Finding next episode after %s", currentEpisodeID)
	
	episodes, err := s.GetAllEpisodes()
	if err != nil {
		log.Printf("GetNextEpisodeID: Error getting episodes: %v", err)
		return "", err
	}

	if len(episodes) == 0 {
		log.Printf("GetNextEpisodeID: No episodes found")
		return "", fmt.Errorf("no episodes found")
	}
	
	log.Printf("GetNextEpisodeID: Found %d episodes", len(episodes))

	// If there's only one episode, return it
	if len(episodes) == 1 {
		log.Printf("GetNextEpisodeID: Only one episode, returning %s", episodes[0].ID)
		return episodes[0].ID, nil
	}

	// Find current episode index
	currentIndex := -1
	for i, episode := range episodes {
		if episode.ID == currentEpisodeID {
			currentIndex = i
			log.Printf("GetNextEpisodeID: Found current episode at index %d", currentIndex)
			break
		}
	}

	// If current episode not found or it's the last episode, loop back to first
	if currentIndex == -1 || currentIndex == len(episodes)-1 {
		log.Printf("GetNextEpisodeID: Current episode not found or is last, returning first episode %s", episodes[0].ID)
		return episodes[0].ID, nil
	}

	// Return next episode
	nextEpisodeID := episodes[currentIndex+1].ID
	log.Printf("GetNextEpisodeID: Returning next episode %s", nextEpisodeID)
	return nextEpisodeID, nil
}

// GetEpisodeVideoPath returns the file path for an episode's video
func (s *ShowService) GetEpisodeVideoPath(episodeID string) (string, error) {
	log.Printf("GetEpisodeVideoPath: Finding video path for episode %s", episodeID)
	
	// This is a simplified implementation
	// In a real implementation, you would have a more sophisticated way to map
	// episode IDs to file paths, possibly using a database or index file
	
	// For now, we'll search for the video file
	path, err := s.findEpisodeVideoFile(episodeID)
	if err != nil {
		log.Printf("GetEpisodeVideoPath: Error finding video path for %s: %v", episodeID, err)
		return "", err
	}
	
	log.Printf("GetEpisodeVideoPath: Found video path %s", path)
	return path, nil
}

// findEpisodeVideoFile searches for the video file matching an episode ID
func (s *ShowService) findEpisodeVideoFile(episodeID string) (string, error) {
	log.Printf("findEpisodeVideoFile: Searching for video file for episode %s", episodeID)
	
	// Remove "Show_" prefix from episode ID
	trimmedID := strings.TrimPrefix(episodeID, "Show_")
	
	// Extract season and episode numbers from format S01E01
	if len(trimmedID) < 6 {
		log.Printf("findEpisodeVideoFile: Invalid episode ID format: %s", episodeID)
		return "", fmt.Errorf("invalid episode ID format")
	}
	
	// Extract season number (S01)
	seasonPart := trimmedID[:3] // e.g., "S01"
	if !strings.HasPrefix(seasonPart, "S") {
		log.Printf("findEpisodeVideoFile: Invalid season format in %s", episodeID)
		return "", fmt.Errorf("invalid season format")
	}
	
	// Extract episode number (E01)
	episodePart := trimmedID[3:] // e.g., "E01"
	if !strings.HasPrefix(episodePart, "E") {
		log.Printf("findEpisodeVideoFile: Invalid episode format in %s", episodeID)
		return "", fmt.Errorf("invalid episode format")
	}
	
	log.Printf("findEpisodeVideoFile: Parsed season %s, episode %s", seasonPart, episodePart)
	
	// Convert to directory format (season-01)
	seasonNum := seasonPart[1:] // e.g., "01"
	seasonDirName := fmt.Sprintf("season-%s", seasonNum)
	
	// Convert to file format (episode-01)
	episodeNum := episodePart[1:] // e.g., "01"
	episodeFileName := fmt.Sprintf("episode-%s", episodeNum)
	
	log.Printf("findEpisodeVideoFile: Looking for season %s, episode %s", seasonDirName, episodeFileName)
	
	// Construct expected file path
	seasonDir := filepath.Join(s.config.SeasonsDir, seasonDirName)
	if !utils.FileExists(seasonDir) {
		log.Printf("findEpisodeVideoFile: Season directory not found: %s", seasonDir)
		return "", fmt.Errorf("season directory not found: %s", seasonDir)
	}
	
	// Try different video extensions
	extensions := []string{".mp4", ".mkv", ".avi", ".mov", ".wmv"}
	for _, ext := range extensions {
		filePath := filepath.Join(seasonDir, episodeFileName+ext)
		if utils.FileExists(filePath) {
			log.Printf("findEpisodeVideoFile: Found exact match: %s", filePath)
			return filePath, nil
		}
	}
	
	log.Printf("findEpisodeVideoFile: No exact match found, searching in directory")
	
	// If not found with exact name, search in the season directory
	videoFiles, err := utils.FindFiles(seasonDir, s.config.VideoFilePattern)
	if err != nil {
		log.Printf("findEpisodeVideoFile: Error finding video files in %s: %v", seasonDir, err)
		return "", err
	}
	
	log.Printf("findEpisodeVideoFile: Found %d video files in directory", len(videoFiles))
	
	// Look for a file that contains the episode name
	for _, videoFile := range videoFiles {
		baseName := strings.TrimSuffix(filepath.Base(videoFile), filepath.Ext(videoFile))
		if strings.Contains(baseName, episodeFileName) || strings.Contains(episodeFileName, baseName) {
			log.Printf("findEpisodeVideoFile: Found partial match: %s", videoFile)
			return videoFile, nil
		}
	}
	
	log.Printf("findEpisodeVideoFile: No video file found for episode: %s", episodeID)
	return "", fmt.Errorf("video file not found for episode: %s", episodeID)
}

// GetEpisodeSubtitlePath returns the file path for an episode's subtitle
func (s *ShowService) GetEpisodeSubtitlePath(episodeID string) (string, error) {
	log.Printf("GetEpisodeSubtitlePath: Finding subtitle path for episode %s", episodeID)
	
	// Remove "Show_" prefix from episode ID
	trimmedID := strings.TrimPrefix(episodeID, "Show_")
	
	// Extract season and episode numbers from format S01E01
	if len(trimmedID) < 6 {
		log.Printf("GetEpisodeSubtitlePath: Invalid episode ID format: %s", episodeID)
		return "", fmt.Errorf("invalid episode ID format")
	}
	
	// Extract season number (S01)
	seasonPart := trimmedID[:3] // e.g., "S01"
	if !strings.HasPrefix(seasonPart, "S") {
		log.Printf("GetEpisodeSubtitlePath: Invalid season format in %s", episodeID)
		return "", fmt.Errorf("invalid season format")
	}
	
	// Extract episode number (E01)
	episodePart := trimmedID[3:] // e.g., "E01"
	if !strings.HasPrefix(episodePart, "E") {
		log.Printf("GetEpisodeSubtitlePath: Invalid episode format in %s", episodeID)
		return "", fmt.Errorf("invalid episode format")
	}
	
	// Convert to directory format (season-01)
	seasonNum := seasonPart[1:] // e.g., "01"
	seasonDirName := fmt.Sprintf("season-%s", seasonNum)
	
	// Convert to file format (episode-01)
	episodeNum := episodePart[1:] // e.g., "01"
	episodeFileName := fmt.Sprintf("episode-%s", episodeNum)
	
	log.Printf("GetEpisodeSubtitlePath: Looking for season %s, episode %s", seasonDirName, episodeFileName)
	
	// Construct subtitle file path in subtitles directory
	subtitlesDir := filepath.Join(filepath.Dir(s.config.SeasonsDir), "subtitles")
	seasonSubtitleDir := filepath.Join(subtitlesDir, seasonDirName)
	
	if !utils.FileExists(seasonSubtitleDir) {
		log.Printf("GetEpisodeSubtitlePath: Subtitle directory not found: %s", seasonSubtitleDir)
		return "", fmt.Errorf("subtitle directory not found: %s", seasonSubtitleDir)
	}
	
	// Try different subtitle extensions
	extensions := []string{".srt", ".vtt"}
	for _, ext := range extensions {
		filePath := filepath.Join(seasonSubtitleDir, episodeFileName+ext)
		if utils.FileExists(filePath) {
			log.Printf("GetEpisodeSubtitlePath: Found exact match: %s", filePath)
			return filePath, nil
		}
	}
	
	log.Printf("GetEpisodeSubtitlePath: No exact match found, searching in directory")
	
	// If not found with exact name, search in the season subtitle directory
	subtitleFiles, err := utils.FindFiles(seasonSubtitleDir, s.config.SubtitleFilePattern)
	if err != nil {
		log.Printf("GetEpisodeSubtitlePath: Error finding subtitle files in %s: %v", seasonSubtitleDir, err)
		return "", err
	}
	
	log.Printf("GetEpisodeSubtitlePath: Found %d subtitle files in directory", len(subtitleFiles))
	
	// Look for a file that contains the episode name
	for _, subtitleFile := range subtitleFiles {
		baseName := strings.TrimSuffix(filepath.Base(subtitleFile), filepath.Ext(subtitleFile))
		if strings.Contains(baseName, episodeFileName) || strings.Contains(episodeFileName, baseName) {
			log.Printf("GetEpisodeSubtitlePath: Found partial match: %s", subtitleFile)
			return subtitleFile, nil
		}
	}
	
	log.Printf("GetEpisodeSubtitlePath: No subtitle file found for episode: %s", episodeID)
	return "", fmt.Errorf("subtitle file not found for episode: %s", episodeID)
}

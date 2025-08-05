package handlers

import (
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"

	"comfort-player-backend/services"
)

// ShowHandler handles show-related requests
type ShowHandler struct {
	showService *services.ShowService
}

// NewShowHandler creates a new show handler
func NewShowHandler(showService *services.ShowService) *ShowHandler {
	return &ShowHandler{
		showService: showService,
	}
}

// ServeEpisodeVideo handles GET /api/episode/{id}/video
func (h *ShowHandler) ServeEpisodeVideo(w http.ResponseWriter, r *http.Request) {
	// Extract episode ID from URL
	episodeID := strings.TrimPrefix(r.URL.Path, "/api/episode/")
	episodeID = strings.TrimSuffix(episodeID, "/video")
	
	log.Printf("ServeEpisodeVideo: Request for episode %s", episodeID)

	// Get video file path
	videoPath, err := h.showService.GetEpisodeVideoPath(episodeID)
	if err != nil {
		log.Printf("ServeEpisodeVideo: Error getting video path for %s: %v", episodeID, err)
		http.Error(w, fmt.Sprintf("Episode not found: %v", err), http.StatusNotFound)
		return
	}
	
	log.Printf("ServeEpisodeVideo: Found video path %s", videoPath)

	// Check if file exists
	if _, err := os.Stat(videoPath); os.IsNotExist(err) {
		log.Printf("ServeEpisodeVideo: Video file not found at %s", videoPath)
		http.Error(w, "Video file not found", http.StatusNotFound)
		return
	}

	// Open video file
	file, err := os.Open(videoPath)
	if err != nil {
		log.Printf("ServeEpisodeVideo: Error opening video file %s: %v", videoPath, err)
		http.Error(w, "Failed to open video file", http.StatusInternalServerError)
		return
	}
	defer file.Close()

	// Get file info
	fileInfo, err := file.Stat()
	if err != nil {
		log.Printf("ServeEpisodeVideo: Error getting file info for %s: %v", videoPath, err)
		http.Error(w, "Failed to get file info", http.StatusInternalServerError)
		return
	}

	// Get file size
	fileSize := fileInfo.Size()
	log.Printf("ServeEpisodeVideo: File size %d bytes", fileSize)

	// Handle range requests for seeking
	rangeHeader := r.Header.Get("Range")
	if rangeHeader != "" {
		log.Printf("ServeEpisodeVideo: Range request: %s", rangeHeader)
		h.servePartialContent(w, r, file, fileSize, rangeHeader)
		return
	}

	// Serve full file
	log.Printf("ServeEpisodeVideo: Serving full file")
	h.serveFullContent(w, r, file, fileSize)
}

// servePartialContent serves a portion of the file for range requests
func (h *ShowHandler) servePartialContent(w http.ResponseWriter, r *http.Request, file *os.File, fileSize int64, rangeHeader string) {
	log.Printf("servePartialContent: File size %d, Range header: %s", fileSize, rangeHeader)
	
	// Parse range header
	rangeParts := strings.Split(rangeHeader, "=")
	if len(rangeParts) != 2 || rangeParts[0] != "bytes" {
		log.Printf("servePartialContent: Invalid range header format")
		http.Error(w, "Invalid range header", http.StatusBadRequest)
		return
	}

	// Parse range values
	rangeValues := strings.Split(rangeParts[1], "-")
	if len(rangeValues) != 2 {
		log.Printf("servePartialContent: Invalid range values format")
		http.Error(w, "Invalid range values", http.StatusBadRequest)
		return
	}

	// Parse start position
	startPos, err := strconv.ParseInt(rangeValues[0], 10, 64)
	if err != nil {
		log.Printf("servePartialContent: Invalid start position: %s", rangeValues[0])
		http.Error(w, "Invalid start position", http.StatusBadRequest)
		return
	}

	// Parse end position or set to end of file
	var endPos int64
	if rangeValues[1] == "" {
		endPos = fileSize - 1
		log.Printf("servePartialContent: End position not specified, using %d", endPos)
	} else {
		endPos, err = strconv.ParseInt(rangeValues[1], 10, 64)
		if err != nil {
			log.Printf("servePartialContent: Invalid end position: %s", rangeValues[1])
			http.Error(w, "Invalid end position", http.StatusBadRequest)
			return
		}
	}

	// Validate range
	if startPos < 0 || endPos >= fileSize || startPos > endPos {
		log.Printf("servePartialContent: Invalid range - Start: %d, End: %d, FileSize: %d", startPos, endPos, fileSize)
		http.Error(w, "Invalid range", http.StatusRequestedRangeNotSatisfiable)
		return
	}

	// Calculate content length
	contentLength := endPos - startPos + 1
	log.Printf("servePartialContent: Serving bytes %d-%d, Content length: %d", startPos, endPos, contentLength)

	// Set response headers for partial content
	w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", startPos, endPos, fileSize))
	w.Header().Set("Accept-Ranges", "bytes")
	w.Header().Set("Content-Length", strconv.FormatInt(contentLength, 10))
	w.Header().Set("Content-Type", "video/mp4")
	w.WriteHeader(http.StatusPartialContent)

	// Seek to start position
	file.Seek(startPos, 0)

	// Copy the requested range to response
	io.CopyN(w, file, contentLength)
}

// serveFullContent serves the entire file
func (h *ShowHandler) serveFullContent(w http.ResponseWriter, r *http.Request, file *os.File, fileSize int64) {
	log.Printf("serveFullContent: Serving full file of size %d bytes", fileSize)
	
	// Set response headers
	w.Header().Set("Content-Length", strconv.FormatInt(fileSize, 10))
	w.Header().Set("Content-Type", "video/mp4")
	w.Header().Set("Accept-Ranges", "bytes")

	// Copy file to response
	io.Copy(w, file)
}

// ServeEpisodeSubtitle handles GET /api/episode/{id}/subtitle
func (h *ShowHandler) ServeEpisodeSubtitle(w http.ResponseWriter, r *http.Request) {
	// Extract episode ID from URL
	episodeID := strings.TrimPrefix(r.URL.Path, "/api/episode/")
	episodeID = strings.TrimSuffix(episodeID, "/subtitle")
	
	log.Printf("ServeEpisodeSubtitle: Request for episode %s", episodeID)

	// Get subtitle file path
	subtitlePath, err := h.showService.GetEpisodeSubtitlePath(episodeID)
	if err != nil {
		log.Printf("ServeEpisodeSubtitle: Error getting subtitle path for %s: %v", episodeID, err)
		http.Error(w, fmt.Sprintf("Subtitle not found: %v", err), http.StatusNotFound)
		return
	}
	
	log.Printf("ServeEpisodeSubtitle: Found subtitle path %s", subtitlePath)

	// Check if file exists
	if _, err := os.Stat(subtitlePath); os.IsNotExist(err) {
		log.Printf("ServeEpisodeSubtitle: Subtitle file not found at %s", subtitlePath)
		http.Error(w, "Subtitle file not found", http.StatusNotFound)
		return
	}

	// Determine content type based on file extension
	contentType := "text/plain"
	if strings.HasSuffix(subtitlePath, ".vtt") {
		contentType = "text/vtt"
	} else if strings.HasSuffix(subtitlePath, ".srt") {
		contentType = "application/x-subrip"
	}
	
	log.Printf("ServeEpisodeSubtitle: Content type %s", contentType)

	// Set response headers
	w.Header().Set("Content-Type", contentType)

	// Serve file
	http.ServeFile(w, r, subtitlePath)
}

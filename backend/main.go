package main

import (
	"fmt"
	"log"
	"net/http"
	"path/filepath"
	"time"

	"github.com/gorilla/mux"
	"github.com/rs/cors"

	"comfort-player-backend/config"
	"comfort-player-backend/handlers"
	"comfort-player-backend/services"
	"comfort-player-backend/utils"
)

func main() {
	// Load configuration
	cfg := config.LoadConfig()

	// Ensure data directory exists for state file
	dataDir := filepath.Dir(cfg.StateFile)
	if err := utils.EnsureDir(dataDir); err != nil {
		log.Fatalf("Failed to create data directory: %v", err)
	}

	// Initialize services
	stateService := services.NewStateService(cfg.StateFile)
	showService := services.NewShowService(cfg)

	// Initialize handlers
	stateHandler := handlers.NewStateHandler(stateService, showService)
	showHandler := handlers.NewShowHandler(showService)

	// Create router
	r := mux.NewRouter()

	// Set up logging middleware
	loggingMiddleware := createLoggingMiddleware()
	r.Use(loggingMiddleware)


	// Set up routes
	// Show info and state routes
	r.HandleFunc("/api/show/info", stateHandler.GetShowInfo).Methods("GET")
	r.HandleFunc("/api/show/state", stateHandler.UpdatePlaybackState).Methods("POST")

	// Episode streaming routes
	r.HandleFunc("/api/episode/{id}/video", showHandler.ServeEpisodeVideo).Methods("GET")
	r.HandleFunc("/api/episode/{id}/subtitle", showHandler.ServeEpisodeSubtitle).Methods("GET")

	// Set up CORS
	corsHandler := cors.New(cors.Options{
		AllowedOrigins: []string{"*"},
		AllowedMethods: []string{"GET", "POST", "OPTIONS"},
		AllowedHeaders: []string{"*"},
	})

	// Create HTTP server
	handler := corsHandler.Handler(r)
	server := &http.Server{
		Addr:    ":" + cfg.Port,
		Handler: handler,
	}

	// Start server
	fmt.Printf("Starting server on port %s\n", cfg.Port)
	fmt.Printf("Media directory: %s\n", cfg.MediaDir)
	fmt.Printf("Seasons directory: %s\n", cfg.SeasonsDir)
	fmt.Printf("State file: %s\n", cfg.StateFile)
	fmt.Printf("API Key: %s\n", cfg.APIKey)

	log.Fatal(server.ListenAndServe())
}

// createAPIKeyMiddleware creates middleware for API key authentication
func createAPIKeyMiddleware(expectedKey string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			// Skip authentication for OPTIONS requests
			if r.Method == "OPTIONS" {
				next.ServeHTTP(w, r)
				return
			}

			// Get API key from Authorization header
			authHeader := r.Header.Get("Authorization")
			if authHeader == "" {
				http.Error(w, "Missing Authorization header", http.StatusUnauthorized)
				return
			}

			// Check if header starts with "Bearer "
			if len(authHeader) < 7 || authHeader[:7] != "Bearer " {
				http.Error(w, "Invalid Authorization header format", http.StatusUnauthorized)
				return
			}

			// Extract API key
			apiKey := authHeader[7:]

			// Validate API key
			if apiKey != expectedKey {
				http.Error(w, "Invalid API key", http.StatusUnauthorized)
				return
			}

			// Continue with request
			next.ServeHTTP(w, r)
		})
	}
}

// createLoggingMiddleware creates middleware for logging requests
func createLoggingMiddleware() func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			// Record start time
			start := time.Now()
			
			// Log incoming request
			log.Printf("REQUEST: %s %s from %s", r.Method, r.URL.Path, r.RemoteAddr)
			
			// Create a response writer wrapper to capture status code
		 wrapped := &responseWriter{ResponseWriter: w, statusCode: http.StatusOK}
			
			// Process request
			next.ServeHTTP(wrapped, r)
			
			// Log response
			duration := time.Since(start)
			log.Printf("RESPONSE: %s %s - Status: %d - Duration: %v", r.Method, r.URL.Path, wrapped.statusCode, duration)
		})
	}
}

// responseWriter wraps http.ResponseWriter to capture status code
type responseWriter struct {
	http.ResponseWriter
	statusCode int
}

func (rw *responseWriter) WriteHeader(code int) {
	rw.statusCode = code
	rw.ResponseWriter.WriteHeader(code)
}

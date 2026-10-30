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
	"comfort-player-backend/store"
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

	settingsStore, err := store.Open(cfg.SettingsFile)
	if err != nil {
		log.Fatalf("Failed to open settings: %v", err)
	}
	deviceService := services.NewDeviceService(settingsStore)
	remoteService := services.NewRemoteService(cfg.RemoteCertDir, deviceService)
	deviceService.SetIdentifier(remoteService.Identify)

	// Initialize handlers
	stateHandler := handlers.NewStateHandler(stateService, showService)
	showHandler := handlers.NewShowHandler(showService)
	settingsHandler := handlers.NewSettingsHandler(deviceService, remoteService)

	// Create router
	r := mux.NewRouter()

	// Set up logging middleware
	loggingMiddleware := createLoggingMiddleware()
	r.Use(loggingMiddleware)


	// Set up routes
	// Show info and state routes. These are the calls the app makes, so they
	// also record which devices use the server.
	showRoutes := r.PathPrefix("/api/show").Subrouter()
	showRoutes.Use(createDeviceTrackingMiddleware(deviceService))
	showRoutes.HandleFunc("/info", stateHandler.GetShowInfo).Methods("GET")
	showRoutes.HandleFunc("/state", stateHandler.UpdatePlaybackState).Methods("POST")

	// Episode streaming routes
	r.HandleFunc("/api/episode/{id}/video", showHandler.ServeEpisodeVideo).Methods("GET")
	r.HandleFunc("/api/episode/{id}/subtitle", showHandler.ServeEpisodeSubtitle).Methods("GET")

	// Settings page and device/remote routes
	r.HandleFunc("/settings", settingsHandler.SettingsPage).Methods("GET")
	r.HandleFunc("/api/settings/devices", settingsHandler.ListDevices).Methods("GET")
	r.HandleFunc("/api/settings/devices/{id}", settingsHandler.UpdateDevice).Methods("POST")
	r.HandleFunc("/api/settings/devices/{id}", settingsHandler.DeleteDevice).Methods("DELETE")
	r.HandleFunc("/api/settings/devices/{id}/pair/start", settingsHandler.StartPairing).Methods("POST")
	r.HandleFunc("/api/settings/devices/{id}/pair/finish", settingsHandler.FinishPairing).Methods("POST")
	r.HandleFunc("/api/settings/devices/{id}/power-off", settingsHandler.PowerOffDevice).Methods("POST")
	r.HandleFunc("/api/remote/power-off", settingsHandler.PowerOffCaller).Methods("POST")

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
	fmt.Printf("Settings file: %s\n", cfg.SettingsFile)
	fmt.Printf("Settings page: http://localhost:%s/settings\n", cfg.Port)
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

// createDeviceTrackingMiddleware records which device made each request.
func createDeviceTrackingMiddleware(devices *services.DeviceService) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			devices.Seen(r)
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

package config

import (
	"log"
	"os"
	"path/filepath"
)

// Config holds the application configuration
type Config struct {
	Port              string
	MediaDir          string
	SeasonsDir        string
	StateFile         string
	APIKey            string
	VideoFilePattern  string
	SubtitleFilePattern string
	SettingsFile      string
	RemoteCertDir     string
}

// LoadConfig loads the configuration from environment variables or defaults
func LoadConfig() *Config {
	// Get the current working directory
	cwd, _ := os.Getwd()
	
	// Default to parent directory's media folder
	defaultMediaDir := filepath.Join(filepath.Dir(cwd), "media")
	defaultSeasonsDir := filepath.Join(defaultMediaDir, "shows")
	defaultStateFile := filepath.Join(cwd, "data", "state.json")
	defaultSettingsFile := filepath.Join(cwd, "data", "settings.json")
	defaultRemoteCertDir := filepath.Join(cwd, "data", "atvremote")
	
	config := &Config{
		Port:              getEnv("PORT", "8080"),
		MediaDir:          getEnv("MEDIA_DIR", defaultMediaDir),
		SeasonsDir:        getEnv("SEASONS_DIR", defaultSeasonsDir),
		StateFile:         getEnv("STATE_FILE", defaultStateFile),
		APIKey:            getEnv("API_KEY", "your-secret-token"),
		VideoFilePattern:  getEnv("VIDEO_FILE_PATTERN", "*.mp4,*.mkv,*.avi"),
		SubtitleFilePattern: getEnv("SUBTITLE_FILE_PATTERN", "*.srt,*.vtt"),
		SettingsFile:      getEnv("SETTINGS_FILE", defaultSettingsFile),
		RemoteCertDir:     getEnv("REMOTE_CERT_DIR", defaultRemoteCertDir),
	}

	log.Printf("%+v\n", config)
	return config
}

// getEnv returns the value of an environment variable or a default value
func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}

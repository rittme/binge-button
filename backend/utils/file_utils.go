package utils

import (
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"strings"
)

// FileExists checks if a file exists
func FileExists(path string) bool {
	_, err := os.Stat(path)
	return !os.IsNotExist(err)
}

// EnsureDir creates a directory if it doesn't exist
func EnsureDir(dir string) error {
	return os.MkdirAll(dir, os.ModePerm)
}

// ReadJSON reads a JSON file and unmarshals it into the provided interface
func ReadJSON(path string, v interface{}) error {
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()

	decoder := json.NewDecoder(file)
	return decoder.Decode(v)
}

// WriteJSON writes data to a JSON file
func WriteJSON(path string, v interface{}) error {
	// Ensure directory exists
	dir := filepath.Dir(path)
	if err := EnsureDir(dir); err != nil {
		return err
	}

	file, err := os.Create(path)
	if err != nil {
		return err
	}
	defer file.Close()

	encoder := json.NewEncoder(file)
	encoder.SetIndent("", "  ")
	return encoder.Encode(v)
}

// FindFiles finds files matching the given pattern in a directory
func FindFiles(dir, pattern string) ([]string, error) {
	var files []string
	
	// Split pattern by comma for multiple extensions
	patterns := strings.Split(pattern, ",")
	
	err := filepath.Walk(dir, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		
		if !info.IsDir() {
			// Check if file matches any of the patterns
			for _, p := range patterns {
				p = strings.TrimSpace(p)
				matched, err := filepath.Match(p, info.Name())
				if err != nil {
					return err
				}
				if matched {
					files = append(files, path)
					break
				}
			}
		}
		
		return nil
	})
	
	return files, err
}

// CopyFile copies a file from src to dst
func CopyFile(src, dst string) error {
	sourceFile, err := os.Open(src)
	if err != nil {
		return err
	}
	defer sourceFile.Close()

	destFile, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer destFile.Close()

	_, err = io.Copy(destFile, sourceFile)
	return err
}

// WriteJSONAtomic writes data to a JSON file via a temp file + rename,
// so a crash mid-write never leaves a truncated file behind.
func WriteJSONAtomic(path string, v interface{}) error {
	if err := EnsureDir(filepath.Dir(path)); err != nil {
		return err
	}

	tmp := path + ".tmp"
	file, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}

	encoder := json.NewEncoder(file)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(v); err != nil {
		file.Close()
		os.Remove(tmp)
		return err
	}
	if err := file.Sync(); err != nil {
		file.Close()
		os.Remove(tmp)
		return err
	}
	if err := file.Close(); err != nil {
		os.Remove(tmp)
		return err
	}
	return os.Rename(tmp, path)
}

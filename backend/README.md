# Comfort Player Backend

This is the Go backend server for the Comfort Player Android application. It serves TV show episodes and manages playback state for a single show that plays in a loop.

## Features

- Serve episode information through REST APIs
- Store episode data in JSON files (one per season)
- Persist current episode and playback time in a JSON file
- Stream video files with HTTP range request support for seeking
- Serve subtitle files (SRT/VTT)
- API key authentication
- CORS support

## API Endpoints

### Get Show Information
```
GET /api/show/info
```
Returns information about all episodes and the current playback state.

Response:
```json
{
  "episodes": [
    {
      "id": "Show_S01E01",
      "title": "The First Episode",
      "videoUrl": "/api/episode/Show_S01E01/video",
      "subtitleUrl": "/api/episode/Show_S01E01/subtitle"
    }
  ],
  "currentEpisodeId": "Show_S01E01",
  "playbackTimeSeconds": 120
}
```

### Update Playback State
```
POST /api/show/state
```
Updates the current episode and playback time.

Request:
```json
{
  "episodeId": "Show_S01E01",
  "playbackTimeSeconds": 120
}
```

### Stream Episode Video
```
GET /api/episode/{id}/video
```
Streams the video file for the specified episode. Supports HTTP range requests for seeking.

### Get Episode Subtitle
```
GET /api/episode/{id}/subtitle
```
Returns the subtitle file for the specified episode.

## Configuration

The server can be configured using environment variables:

- `PORT` - Server port (default: 8080)
- `MEDIA_DIR` - Base media directory (default: ../media)
- `SEASONS_DIR` - Directory containing season folders (default: $MEDIA_DIR/shows)
- `STATE_FILE` - File to store playback state (default: ./data/state.json)
- `API_KEY` - API key for authentication (default: your-secret-token)
- `VIDEO_FILE_PATTERN` - Pattern for video files (default: *.mp4,*.mkv,*.avi)
- `SUBTITLE_FILE_PATTERN` - Pattern for subtitle files (default: *.srt,*.vtt)

## Directory Structure

The server expects the following directory structure for media files:

```
media/
└── shows/
    ├── season-01/
    │   ├── episode-01.mp4
    │   ├── episode-01.srt
    │   ├── episode-02.mp4
    │   └── episode-02.srt
    └── season-02/
        ├── episode-01.mp4
        └── episode-01.srt
```

## Installation

1. Install Go 1.21 or later
2. Install dependencies:
   ```
   go mod tidy
   ```

## Running the Server

1. Set environment variables as needed
2. Run the server:
   ```
   go run main.go
   ```

Or build and run:
```
go build -o comfort-player-backend
./comfort-player-backend
```

## Authentication

All API endpoints require authentication using an API key in the Authorization header:

```
Authorization: Bearer your-secret-token
```

## State Persistence

The server stores the current episode and playback time in a JSON file at `data/state.json`. This file is automatically created and updated as needed.

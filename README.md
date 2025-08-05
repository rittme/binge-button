# Comfort Player

Comfort Player is an Android application that plays a single TV show in a loop, one episode after the other. This repository contains both the Android frontend and the Go backend server.

## Project Structure

```
comfort-player/
├── backend/          # Go backend server
├── frontend/         # Android application (Capacitor.js)
└── media/            # Media files for the server
```

## Backend Server

The backend server is written in Go and provides the following features:

- REST API for episode information and playback state management
- Video streaming with HTTP range request support for seeking
- Subtitle file serving (SRT/VTT)
- JSON-based episode data storage (one file per season)
- Persistent playback state storage
- API key authentication
- CORS support

### API Endpoints

- `GET /api/show/info` - Get show information and current playback state
- `POST /api/show/state` - Update playback state (current episode and time)
- `GET /api/episode/{id}/video` - Stream episode video
- `GET /api/episode/{id}/subtitle` - Get episode subtitle

### Running the Backend Server

1. Ensure you have Go 1.21 or later installed
2. Navigate to the `backend` directory
3. Run the initialization script:
   ```
   ./init.sh
   ```
4. Start the server:
   ```
   ./start_server.sh
   ```
5. To stop the server:
   ```
   ./stop_server.sh
   ```

### Configuration

The server can be configured using environment variables:

- `PORT` - Server port (default: 8080)
- `MEDIA_DIR` - Base media directory (default: ../media)
- `SEASONS_DIR` - Directory containing season folders (default: $MEDIA_DIR/shows)
- `STATE_FILE` - File to store playback state (default: ./data/state.json)
- `API_KEY` - API key for authentication (default: your-secret-token)

## Frontend Application

The frontend is an Android application built with Capacitor.js that connects to the backend server to play episodes in a loop.

## Media Organization

Place your media files in the `media/shows` directory following the structure described in `media/README.md`.

## Development

### Backend Development

1. Install Go 1.21 or later
2. Navigate to the `backend` directory
3. Run `go mod tidy` to install dependencies
4. Run `go run main.go` to start the server

### Frontend Development

1. Install Node.js and npm
2. Navigate to the `frontend` directory
3. Run `npm install` to install dependencies
4. Run `npm run dev` to start the development server

## Testing

You can test the backend API using the provided test script:

```
cd backend
./test_api.sh
```

This will test the main API endpoints and display the results.

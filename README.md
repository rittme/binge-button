# Comfort Player

Comfort Player is an Android TV application that plays a single TV show in a loop, one episode after the other. This repository contains both the Android frontend (TheOfficer) and the Go backend server.

## Project Structure

```
comfort-player/
├── backend/          # Go backend server
├── TheOfficer/       # Android TV application (Kotlin)
└── media/            # Media files for the server
```

## Features

- **Android TV Optimized**: Built specifically for Android TV with remote control support
- **Continuous Playback**: Automatically plays episodes in sequence
- **State Persistence**: Remembers where you left off across app restarts
- **Subtitle Support**: Automatic subtitle loading for episodes
- **Remote Control Navigation**: Full D-pad navigation support
- **Brightness Control**: Adjust screen brightness with remote
- **VLC Integration**: Uses VLC media player for robust video playback
- **RESTful API**: Clean backend API for episode management

## Backend Server

The backend server is written in Go and provides:

- REST API for episode information and playback state management
- Video streaming with HTTP range request support for seeking
- Subtitle file serving (SRT/VTT)
- JSON-based episode data storage
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
   ```bash
   ./init.sh
   ```
4. Start the server:
   ```bash
   ./start_server.sh
   ```
5. To stop the server:
   ```bash
   ./stop_server.sh
   ```

### Backend Configuration

Configure using environment variables:

- `PORT` - Server port (default: 8080)
- `MEDIA_DIR` - Base media directory (default: ../media)
- `SEASONS_DIR` - Directory containing season folders (default: $MEDIA_DIR/shows)
- `STATE_FILE` - File to store playback state (default: ./data/state.json)
- `API_KEY` - API key for authentication (default: your-secret-token)
- `VIDEO_FILE_PATTERN` - Pattern for video files (default: *.mp4,*.mkv,*.avi)
- `SUBTITLE_FILE_PATTERN` - Pattern for subtitle files (default: *.srt,*.vtt)

## Android TV Application (TheOfficer)

The Android app is built with Kotlin and uses:
- **VLC Media Player**: For robust video playback
- **Jetpack Compose**: Modern Android UI toolkit
- **Retrofit**: For HTTP API communication
- **ViewModel**: For lifecycle-aware data management
- **Android TV Support**: Optimized for TV navigation

### Building the Android App

1. Install Android Studio (latest version recommended)
2. Open the `TheOfficer` directory as an Android Studio project
3. Sync project with Gradle files
4. Build and run on an Android TV device or emulator (API 31+)

### Android TV Features

- **Remote Control Navigation**: Full D-pad support for navigation
- **Auto-hide Controls**: UI controls automatically hide during playback
- **Episode Navigation**: Previous/Next episode buttons
- **Brightness Control**: Adjust brightness using D-pad up/down
- **Playback Resume**: Automatically resumes from last position
- **Subtitle Support**: Automatically loads subtitles if available

## Media Organization

Place your media files in the `media/shows` directory following this structure:

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

### JSON Episode Data

Each season requires a corresponding JSON file in `media/shows/`:

```
media/
└── shows/
    ├── season_1.json
    ├── season_2.json
    └── season-01/ (actual media files)
```

Example JSON format:
```json
[
  {
    "id": "Show_S01E01",
    "title": "The First Episode",
    "videoUrl": "/api/episode/Show_S01E01/video",
    "subtitleUrl": "/api/episode/Show_S01E01/subtitle"
  }
]
```

### File Naming Conventions

- Season directories: `season-01`, `season-02`, etc.
- Episode files: `episode-01.mp4`, `episode-02.mp4`, etc.
- Subtitle files: Match episode filename with `.srt` or `.vtt` extension

## Development Setup

### Prerequisites

- **Backend**: Go 1.21+
- **Android App**: Android Studio (latest), Android SDK 31+
- **Media**: Video files and optional subtitle files

### Quick Start

1. **Clone the repository**:
   ```bash
   git clone https://github.com/rittme/comfort-player.git
   cd comfort-player
   ```

2. **Set up the backend**:
   ```bash
   cd backend
   ./init.sh
   ./start_server.sh
   ```

3. **Add your media**:
   - Place video files in `media/shows/season-01/`
   - Create corresponding JSON files for episode metadata
   - Add subtitle files (optional)

4. **Build and run the Android app**:
   - Open `TheOfficer` in Android Studio
   - Configure the API endpoint in `ApiService.kt`
   - Build and deploy to Android TV device/emulator

## Testing

### Backend Testing
Test the backend API using the provided script:
```bash
cd backend
./test_api.sh
```

### Android Testing
- Use Android Studio's built-in emulator for Android TV
- Test with physical Android TV device for best experience
- Verify remote control navigation works correctly

## Configuration Files

### Network Security (Android)
The app includes network security configuration for HTTP connections in:
`TheOfficer/app/src/main/res/xml/network_security_config.xml`

### API Configuration
Update the base URL in `ApiService.kt` to match your server:
```kotlin
private const val BASE_URL = "http://your-server-ip:8080/"
```

## Troubleshooting

### Common Issues

1. **Video not playing**: Check file paths in JSON files and ensure media files exist
2. **Subtitles not loading**: Verify subtitle files match episode filenames
3. **Network errors**: Check server URL configuration and network connectivity
4. **Android TV navigation**: Ensure D-pad navigation is working in emulator/device

### Logs

- **Backend**: Check console output for Go server logs
- **Android**: Use Android Studio's Logcat with tag "PlayerActivity"

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Test thoroughly on both backend and Android app
5. Submit a pull request

## License

This project is open source and available under the MIT License.

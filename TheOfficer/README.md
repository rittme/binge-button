# The Officer (BingeButton) - Android TV Video Player

## Overview
This is an Android TV video player application designed for binge-watching TV shows and movies. It features seamless episode transitions, progress tracking, and TV remote control support.

## Key Features
- **VLC-based Video Playback**: Uses LibVLC for robust video playback support
- **Android TV Remote Control**: Full support for D-pad navigation and TV remote controls
- **Automatic Progress Tracking**: Saves and resumes playback position
- **Episode Navigation**: Easy navigation between episodes with previous/next buttons
- **Subtitle Support**: Built-in support for external subtitle files
- **Brightness Control**: Adjust brightness using up/down D-pad buttons
- **Auto-hide UI**: Controls automatically hide during playback

## Fixed Crash Issues

### 1. Null Pointer Exceptions
- **Issue**: Multiple `!!` operators causing crashes when objects were null
- **Fix**: Replaced with safe calls (`?.`) and proper null checks
- **Files**: `PlayerActivity.kt`, `PlayerViewModel.kt`

### 2. Resource Management
- **Issue**: Improper cleanup of VLC player, media session, and handlers
- **Fix**: Added comprehensive resource cleanup in `onDestroy()`
- **Files**: `PlayerActivity.kt`

### 3. Network Error Handling
- **Issue**: No proper error handling for network failures
- **Fix**: Added try-catch blocks and timeout configurations
- **Files**: `ApiService.kt`, `PlayerViewModel.kt`

### 4. Memory Leaks
- **Issue**: Handler callbacks and background jobs not properly cleaned up
- **Fix**: Added `handler.removeCallbacksAndMessages(null)` and job cancellation
- **Files**: `PlayerActivity.kt`, `PlayerViewModel.kt`

### 5. Thread Safety
- **Issue**: UI updates from background threads without proper synchronization
- **Fix**: Added proper exception handling in coroutines and background tasks
- **Files**: `PlayerViewModel.kt`

## Build Configuration
- **Min SDK**: 21 (Android 5.0)
- **Target SDK**: 35 (Android 15)
- **Kotlin**: 2.0.21
- **VLC Library**: 3.6.0

## Dependencies
- LibVLC Android
- Retrofit 2.9.0
- OkHttp 4.11.0
- Gson 2.9.0
- AndroidX Lifecycle
- AndroidX Media
- Kotlin Coroutines

## ProGuard Configuration
The app includes proper ProGuard rules to prevent crashes due to code obfuscation:
- Keeps Retrofit and Gson classes
- Preserves VLC library classes
- Maintains AndroidX media session classes

## Network Security
- Configured for both HTTP and HTTPS connections
- Proper domain configuration for API endpoints
- Timeout settings for network requests

## Usage
1. Install the APK on an Android TV device
2. The app will automatically connect to the configured API endpoint
3. Use the TV remote D-pad for navigation:
   - **Up/Down**: Adjust brightness
   - **Left/Right**: Navigate between buttons
   - **Center/OK**: Select/press button
   - **Back**: Toggle UI visibility

## Error Handling
- Network errors display user-friendly messages
- Video playback errors show toast notifications
- Player initialization failures are gracefully handled
- All exceptions are logged for debugging purposes

## Testing
The app has been tested for:
- Memory leaks using Android Profiler
- Crash scenarios with forced network failures
- Resource cleanup verification
- Background/foreground lifecycle transitions

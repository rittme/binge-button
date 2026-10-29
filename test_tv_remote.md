# TV Remote Control Test Guide

## Android TV Remote Control Functionality Test

### Overview
The Android app has been enhanced with full TV remote control support. Here's how to test the functionality:

### Remote Control Button Mappings

#### Directional Pad (DPAD)
- **DPAD_UP**: Increase brightness + show controls
- **DPAD_DOWN**: Decrease brightness + show controls  
- **DPAD_LEFT**: Move focus left between buttons + show controls
- **DPAD_RIGHT**: Move focus right between buttons + show controls
- **DPAD_CENTER/ENTER**: Activate focused button + show controls

#### Other Buttons
- **BACK**: Hide controls (if visible) or exit app

### Button Layout (Left to Right)
1. **Previous Episode** (⏮︎) - Plays previous episode
2. **Play/Pause** (⏯︎) - Toggles play/pause state
3. **Next Episode** (⏭︎) - Plays next episode

### Visual Features
- **Large TV-friendly buttons** with 24sp text size
- **High contrast colors** (white text on dark background)
- **Focus indicators** with visual highlighting
- **Auto-hide controls** after 4 seconds of inactivity
- **Brightness adjustment** via DPAD up/down
- **Smooth focus navigation** between buttons

### Testing Steps

1. **Install on Android TV/Emulator**
   ```bash
   # Build and install
   cd TheOfficer
   ./gradlew installDebug
   ```

2. **Launch the app** using TV remote

3. **Test Navigation**
   - Use DPAD left/right to move between buttons
   - Verify focus highlighting works
   - Use DPAD center to activate buttons

4. **Test Brightness Control**
   - Use DPAD up to increase brightness
   - Use DPAD down to decrease brightness
   - Verify brightness toast messages appear

5. **Test Auto-hide**
   - Wait 4 seconds - controls should auto-hide
   - Press any button to show controls again

6. **Test Playback Controls**
   - Test play/pause functionality
   - Test next/previous episode navigation

### Troubleshooting
- If buttons don't respond: Ensure TV has focus on the app
- If brightness doesn't change: Check if video is playing
- If controls don't auto-hide: Ensure video is playing (timer only runs during playback)

### Technical Details
- **Target SDK**: Android TV compatible
- **Leanback support**: Enabled in manifest
- **Touchscreen optional**: Declared in manifest
- **Focus navigation**: Custom implementation with visual feedback

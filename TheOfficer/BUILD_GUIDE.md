# TheOfficer App - Local Build Guide

This guide explains how to build and install the TheOfficer Android app on your devices.

## Prerequisites

1. **Java Development Kit (JDK) 11 or higher**
   - Check: `java -version`
   - The project requires Java 11+ (currently tested with Java 21)

2. **Android Debug Bridge (ADB)** (optional, for device installation)
   - Check: `adb version`
   - Install via Android SDK Platform Tools or Homebrew: `brew install android-platform-tools`

3. **Android Device or Emulator**
   - Enable Developer Options and USB Debugging on your device
   - For Android TV: Enable ADB debugging in Settings → Device Preferences → Developer Options

## Quick Start

### Build Debug APK (Recommended for Testing)

```bash
cd TheOfficer
./build_apk.sh
```

This will:
- Clean previous builds
- Build a debug APK
- Copy the APK to `TheOfficer-debug.apk` in the project root

### Build Release APK

```bash
./build_apk.sh release
```

Note: Release builds require signing configuration. For local testing, debug builds are sufficient.

## Installation Methods

### Method 1: Direct ADB Install (Recommended)

```bash
# Connect your device via USB or network
adb devices

# Install the APK
adb install -r TheOfficer-debug.apk
```

The `-r` flag allows reinstalling and keeping existing data.

### Method 2: Install on Multiple Devices

```bash
# List connected devices
adb devices

# Install on all connected devices
for device in $(adb devices | grep -v List | awk '{print $1}'); do
  adb -s $device install -r TheOfficer-debug.apk
done
```

### Method 3: Network Installation (Android TV)

```bash
# Connect to Android TV over network
adb connect <TV_IP_ADDRESS>:5555

# Install
adb install -r TheOfficer-debug.apk
```

### Method 4: Manual Transfer

```bash
# Push APK to device
adb push TheOfficer-debug.apk /sdcard/Download/

# Then open Files app on your device and install from Downloads
```

### Method 5: Manual Installation (No ADB)

1. Transfer the APK file to your device (email, USB, cloud storage, etc.)
2. Open the APK file on your device
3. Allow installation from unknown sources if prompted
4. Follow the installation prompts

## Build Options

### Clean Build

```bash
cd TheOfficer
./gradlew clean
./build_apk.sh
```

### Build Specific Variant

```bash
# Debug only
./gradlew assembleDebug

# Release only
./gradlew assembleRelease
```

### Build and Install in One Command

```bash
# Build and install debug version
./gradlew installDebug

# Build and install release version
./gradlew installRelease
```

## APK Locations

After building, APKs are located at:

- **Debug**: `app/build/outputs/apk/debug/app-debug.apk`
- **Release**: `app/build/outputs/apk/release/app-release.apk`
- **Convenient Copy**: `TheOfficer-debug.apk` or `TheOfficer-release.apk` (root directory)

## Troubleshooting

### Build Fails

```bash
# Check Java version (needs 11+)
java -version

# Clean and rebuild
./gradlew clean
./build_apk.sh
```

### Installation Fails

```bash
# Uninstall existing version first
adb uninstall com.rittme.theofficer

# Then install fresh
adb install TheOfficer-debug.apk
```

### Device Not Found

```bash
# Check device connection
adb devices

# If empty, check:
# - USB debugging enabled
# - USB cable works for data (not just charging)
# - USB drivers installed (Windows)
# - Device is authorized (check device screen)
```

### Network ADB Connection Issues

```bash
# Reconnect to device
adb disconnect
adb connect <IP_ADDRESS>:5555

# If still failing, enable ADB over network on device:
# Settings → Developer Options → Wireless debugging
```

## App Information

- **Package Name**: `com.rittme.theofficer`
- **Minimum Android Version**: Android 5.0 (API 21)
- **Target Android Version**: Android 15 (API 35)
- **Optimized For**: Android TV devices

## Development Commands

```bash
# Run tests
./gradlew test

# Check for lint issues
./gradlew lint

# View all tasks
./gradlew tasks

# Build both debug and release
./gradlew assemble
```

## Release Build Signing (Future)

For production releases, you'll need to configure signing in `app/build.gradle.kts`:

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("path/to/keystore.jks")
            storePassword = "your-store-password"
            keyAlias = "your-key-alias"
            keyPassword = "your-key-password"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // ... other config
        }
    }
}
```

Never commit signing keys to version control!

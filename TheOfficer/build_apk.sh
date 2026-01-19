#!/bin/bash

# TheOfficer App Build Script
# This script builds the APK for local installation on Android devices

set -e  # Exit on error

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Script directory
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  TheOfficer APK Build Script${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Check for build type argument
BUILD_TYPE=${1:-debug}

if [[ "$BUILD_TYPE" != "debug" && "$BUILD_TYPE" != "release" ]]; then
    echo -e "${RED}Error: Invalid build type '${BUILD_TYPE}'${NC}"
    echo "Usage: $0 [debug|release]"
    echo "  debug   - Build debug APK (default, no signing required)"
    echo "  release - Build release APK (requires signing configuration)"
    exit 1
fi

echo -e "${YELLOW}Build type: ${BUILD_TYPE}${NC}"
echo ""

# Check if gradlew exists
if [ ! -f "./gradlew" ]; then
    echo -e "${RED}Error: gradlew not found in current directory${NC}"
    exit 1
fi

# Make gradlew executable
chmod +x ./gradlew

# Clean previous builds
echo -e "${BLUE}Cleaning previous builds...${NC}"
./gradlew clean

# Build APK
echo ""
echo -e "${BLUE}Building ${BUILD_TYPE} APK...${NC}"
if [ "$BUILD_TYPE" == "debug" ]; then
    ./gradlew assembleDebug
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    OUTPUT_NAME="TheOfficer-debug.apk"
else
    ./gradlew assembleRelease
    APK_PATH="app/build/outputs/apk/release/app-release.apk"
    OUTPUT_NAME="TheOfficer-release.apk"
fi

# Check if build was successful
if [ ! -f "$APK_PATH" ]; then
    echo -e "${RED}Build failed! APK not found at $APK_PATH${NC}"
    exit 1
fi

# Copy APK to root directory for easy access
echo ""
echo -e "${BLUE}Copying APK to project root...${NC}"
cp "$APK_PATH" "./$OUTPUT_NAME"

# Get APK info
APK_SIZE=$(ls -lh "$OUTPUT_NAME" | awk '{print $5}')

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Build Successful!${NC}"
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}APK Location:${NC} $SCRIPT_DIR/$OUTPUT_NAME"
echo -e "${GREEN}APK Size:${NC} $APK_SIZE"
echo ""
echo -e "${YELLOW}Installation Options:${NC}"
echo ""
echo -e "1. ${BLUE}Install via ADB:${NC}"
echo -e "   adb install -r \"$OUTPUT_NAME\""
echo ""
echo -e "2. ${BLUE}Install on multiple devices:${NC}"
echo -e "   for device in \$(adb devices | grep -v List | awk '{print \$1}'); do"
echo -e "     adb -s \$device install -r \"$OUTPUT_NAME\""
echo -e "   done"
echo ""
echo -e "3. ${BLUE}Transfer to device and install manually:${NC}"
echo -e "   adb push \"$OUTPUT_NAME\" /sdcard/Download/"
echo -e "   Then open the file on your device to install"
echo ""
echo -e "${GREEN}Done!${NC}"

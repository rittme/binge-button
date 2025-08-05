#!/bin/bash

# Script to start the Comfort Player Backend server

echo "Starting Comfort Player Backend server..."

# Check if server is already running
if pgrep -f comfort-player-backend > /dev/null; then
    echo "Server is already running."
    exit 1
fi

# Start the server in the background
./comfort-player-backend &

# Wait a moment for the server to start
sleep 2

# Check if server started successfully
if pgrep -f comfort-player-backend > /dev/null; then
    echo "Server started successfully!"
    echo "API endpoints are available at http://localhost:8080"
    echo "Use './stop_server.sh' to stop the server"
else
    echo "Failed to start server."
    exit 1
fi

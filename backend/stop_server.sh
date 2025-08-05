#!/bin/bash

# Script to stop the Comfort Player Backend server

echo "Stopping Comfort Player Backend server..."

# Find and kill the server process
pkill -f comfort-player-backend

if [ $? -eq 0 ]; then
    echo "Server stopped successfully."
else
    echo "No server process found or failed to stop."
fi

#!/bin/bash

# Initialize Go modules and download dependencies
echo "Initializing Go modules..."
go mod tidy

echo "Downloading dependencies..."
go mod download

echo "Building the server..."
go build -o comfort-player-backend

echo "Setup complete! You can now run the server with:"
echo "  ./comfort-player-backend"
echo ""
echo "Or run directly with:"
echo "  go run main.go"

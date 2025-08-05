#!/bin/bash

# Test script for the Comfort Player Backend API

echo "Testing Comfort Player Backend API"
echo "==================================="

# Server URL
BASE_URL="http://localhost:8080"
API_KEY="your-secret-token"

echo "1. Testing GET /api/show/info"
curl -s -H "Authorization: Bearer $API_KEY" "$BASE_URL/api/show/info" | jq '.'

echo -e "\n2. Testing POST /api/show/state"
curl -s -X POST -H "Authorization: Bearer $API_KEY" -H "Content-Type: application/json" -d '{"episodeId": "Show_S01E01", "playbackTimeSeconds": 120}' "$BASE_URL/api/show/state" | jq '.'

echo -e "\n3. Testing GET /api/show/info again (should show updated state)"
curl -s -H "Authorization: Bearer $API_KEY" "$BASE_URL/api/show/info" | jq '.'

echo -e "\nAPI tests completed!"

# Docker Setup Guide for Comfort Player Backend

This guide provides instructions for running the Comfort Player backend using Docker with Alpine Linux for minimal resource usage.

## Quick Start

### Prerequisites
- Docker and Docker Compose installed
- Media files organized in the `media/` directory

### 1. Basic Usage

```bash
# Build and run with Docker Compose
docker-compose up

# Run in detached mode
docker-compose up -d
```

### 2. Docker Build

```bash
# Build the image
docker build -t comfort-player-backend ./backend

# Run the container
docker run -d \
  --name comfort-player-backend \
  -p 8080:8080 \
  -v $(pwd)/media:/app/media:ro \
  -v $(pwd)/data:/app/data \
  -e API_KEY=your-secret-token \
  comfort-player-backend
```

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | 8080 | Server port |
| `API_KEY` | your-secret-token | API authentication key |
| `MEDIA_DIR` | /app/media | Media directory path |
| `SEASONS_DIR` | /app/media/shows | Seasons directory path |
| `STATE_FILE` | /app/data/state.json | State file location |
| `VIDEO_FILE_PATTERN` | *.mp4,*.mkv,*.avi | Video file extensions |
| `SUBTITLE_FILE_PATTERN` | *.srt,*.vtt | Subtitle file extensions |

### Volume Mounts

The following directories are mounted as external volumes:

- `./media:/app/media:ro` - Media files (read-only)
- `./data:/app/data` - Application state and data (read-write)

## Directory Structure

```
comfort-player/
├── backend/
│   ├── Dockerfile
│   └── ... (Go source files)
├── media/
│   └── shows/
│       └── [your video files]
├── data/
│   └── state.json (auto-created)
├── docker-compose.yml
├── .env (optional)
└── ...
```

## Usage Examples

### 1. Development Setup

```bash
# Copy environment template
cp .env.example .env

# Edit configuration
nano .env

# Start services
docker-compose up -d

# Check status
docker-compose ps
```

### 2. Production Setup

```bash
# Create production environment file
cp .env.example .env.production

# Edit with production values
nano .env.production

# Run with production config
docker-compose --env-file .env.production up -d
```

### 3. Updating the Service

```bash
# Pull latest changes
git pull origin main

# Rebuild and restart
docker-compose down
docker-compose up -d --build
```

## Health Checks

The container includes health checks that verify:
- Container is running
- Backend service is responding
- API endpoints are accessible

Check health status:
```bash
docker-compose ps
docker inspect comfort-player-backend | grep -A 10 Health
```

## Troubleshooting

### Common Issues

1. **Permission denied on volume mounts**
   ```bash
   # Fix permissions
   sudo chown -R 1000:1000 ./data ./media
   ```

2. **Container won't start**
   ```bash
   # Check logs
   docker-compose logs comfort-player-backend
   
   # Check configuration
   docker-compose config
   ```

3. **Media files not found**
   ```bash
   # Verify volume mounts
   docker exec comfort-player-backend ls -la /app/media
   ```

### Useful Commands

```bash
# View container logs
docker-compose logs -f comfort-player-backend

# Execute commands in container
docker exec -it comfort-player-backend sh

# Check container resources
docker stats comfort-player-backend

# Clean up
docker-compose down
docker system prune -f
```

## Security Notes

- The container runs as a non-root user (UID 1000)
- Media directory is mounted as read-only
- API key should be changed from default
- Consider using Docker secrets for sensitive data in production

## Performance Optimization

### Image Size
- Uses Alpine Linux 3.19 (~5MB base)
- Multi-stage build reduces final image size
- Only essential packages included

### Resource Limits
Add to docker-compose.yml for production:
```yaml
deploy:
  resources:
    limits:
      cpus: '0.5'
      memory: 256M
    reservations:
      cpus: '0.25'
      memory: 128M
```

## Backup and Restore

### Backup State
```bash
# Backup state file
cp data/state.json data/state.json.backup
```

### Restore State
```bash
# Restore state file
cp data/state.json.backup data/state.json
docker-compose restart

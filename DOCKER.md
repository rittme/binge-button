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
docker build -t comfort-player ./backend

# Run the container
docker run -d \
  --name comfort-player \
  -p 8080:8080 \
  -v $(pwd)/media:/app/media:ro \
  -v $(pwd)/data:/app/data \
  -e API_KEY=your-secret-token \
  comfort-player
```

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | 8080 | HTTP port for the whole server: API, streaming and the settings page |
| `API_KEY` | your-secret-token | API authentication key |
| `MEDIA_DIR` | /app/media | Media directory path |
| `SEASONS_DIR` | /app/media/shows | Seasons directory path |
| `STATE_FILE` | /app/data/state.json | State file location |
| `VIDEO_FILE_PATTERN` | *.mp4,*.mkv,*.avi | Video file extensions |
| `SUBTITLE_FILE_PATTERN` | *.srt,*.vtt | Subtitle file extensions |
| `SETTINGS_FILE` | /app/data/settings.json | Server settings (known devices, pairing state) |
| `REMOTE_CERT_DIR` | /app/data/atvremote | TV-remote client certificate and key |

**Changing the port:** set `PORT`, for example in `.env` (`PORT=9090`) or with `PORT=9090 docker compose up -d`. The port mapping and both healthchecks follow it automatically.

### Host networking (recommended on Linux for TV power-off)

With the default bridge network, some setups (Docker Desktop, OrbStack, rootless Docker, some NAS boxes) make the server see Docker's gateway IP instead of the TV's real IP. Pairing and power-off then fail. Host networking avoids this:

```yaml
services:
  comfort-player:
    network_mode: host   # remove the ports: section, Docker ignores it in this mode
    environment:
      - PORT=9090        # the server listens directly on this host port
```

With host networking, `PORT` is the only port setting. Make sure nothing else on the host is using that port.

### Volume Mounts

The following directories are mounted as external volumes:

- `./media:/app/media:ro` - Media files (read-only)
- `./data:/app/data` - Application state, settings and the TV-remote certificate (read-write)

## Settings Page & TV Power-Off

Open `http://<server-ip>:8080/settings` to pair TVs/projectors and turn them off remotely. See the main README for the full flow.

- **Outbound access:** the container must reach the TVs on TCP ports **6466** and **6467**. The default bridge network allows this.
- **Client IP:** with default Docker port publishing, requests from LAN devices keep their real IP, which the device list relies on.
- **Persistence:** pairing is saved in `./data`. Recreating the container keeps it, as long as the volume is kept.

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
│   ├── state.json (auto-created)
│   ├── settings.json (auto-created)
│   └── atvremote/ (auto-created, client cert + key)
├── docker-compose.yml
├── .env (optional)
└── ...
```

## Usage Examples

### 1. Development Setup

```bash
# Copy environment template
# Start services
docker-compose up -d

# Check status
docker-compose ps
```

### 2. Updating the Service

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
docker inspect comfort-player | grep -A 10 Health
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
   docker-compose logs comfort-player
   
   # Check configuration
   docker-compose config
   ```

3. **Media files not found**
   ```bash
   # Verify volume mounts
   docker exec comfort-player ls -la /app/media
   ```

### Useful Commands

```bash
# View container logs
docker-compose logs -f comfort-player

# Execute commands in container
docker exec -it comfort-player sh

# Check container resources
docker stats comfort-player

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

# Backup settings and the TV-remote certificate (losing the certificate means re-pairing every TV)
cp data/settings.json data/settings.json.backup
cp -r data/atvremote data/atvremote.backup
```

### Restore State
```bash
# Restore state file
cp data/state.json.backup data/state.json
docker-compose restart

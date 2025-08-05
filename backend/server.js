import { scanMediaLibrary } from './media';
// Import required packages
const express = require('express');
const http = require('http');
const fs = require('fs');
const path = require('path');
const { exec } = require('child_process');
const cors = require('cors');
const bodyParser = require('body-parser');
const morgan = require('morgan'); // For logging


// Create Express app
const app = express();
const server = http.createServer(app);

// Configure middleware
app.use(cors()); // Enable CORS for all routes
app.use(bodyParser.json()); // Parse JSON request bodies
app.use(morgan('dev')); // Log HTTP requests

// Define directories
const BASE_DIR = path.join(__dirname, '..', 'media');
const SHOWS_DIR = path.join(BASE_DIR, 'shows');
const HLS_DIR = path.join(BASE_DIR, 'hls');
const SUBTITLES_DIR = path.join(BASE_DIR, 'subtitles');

// Create directories if they don't exist
[BASE_DIR, SHOWS_DIR, HLS_DIR, SUBTITLES_DIR].forEach(dir => {
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }
});

// Simple in-memory database for state tracking
// In a production app, you might want to use a real database
const stateDB = {
  // Format: { deviceId: { showId, seasonId, episodeId, position, timestamp } }
};
const showsDB = {
  // Format: { showId: { title, seasons: { seasonId: { title, episodes: { episodeId: { title, file, duration } } } } } }
};

// Authentication middleware (simple token-based auth)
// For a personal app, this can be very simple
const API_TOKEN = 'your-secret-token'; // Change this to a secure value

function authenticateRequest(req, res, next) {
  const token = req.headers.authorization?.split(' ')[1];
  
  if (token === API_TOKEN) {
    next();
  } else {
    res.status(401).json({ error: 'Unauthorized' });
  }
}

// Initialize the media library
scanMediaLibrary(SHOWS_DIR);

// API Routes

// Get list of shows
app.get('/api/shows', authenticateRequest, (req, res) => {
  const showsList = Object.keys(showsDB).map(showId => ({
    id: showId,
    title: showsDB[showId].title
  }));
  
  res.json(showsList);
});

// Get show details
app.get('/api/shows/:showId', authenticateRequest, (req, res) => {
  const { showId } = req.params;
  
  if (!showsDB[showId]) {
    return res.status(404).json({ error: 'Show not found' });
  }
  
  res.json(showsDB[showId]);
});

// Get episode details
app.get('/api/shows/:showId/seasons/:seasonId/episodes/:episodeId', authenticateRequest, (req, res) => {
  const { showId, seasonId, episodeId } = req.params;
  
  if (!showsDB[showId] || 
      !showsDB[showId].seasons[seasonId] || 
      !showsDB[showId].seasons[seasonId].episodes[episodeId]) {
    return res.status(404).json({ error: 'Episode not found' });
  }
  
  const episode = showsDB[showId].seasons[seasonId].episodes[episodeId];
  res.json({
    ...episode,
    streamUrl: `/api/stream/${showId}/${seasonId}/${episodeId}`,
    hlsUrl: `/api/hls/${showId}/${seasonId}/${episodeId}/playlist.m3u8`,
    subtitles: getSubtitlesForEpisode(showId, seasonId, episodeId)
  });
});

// Get next episode
app.get('/api/shows/:showId/seasons/:seasonId/episodes/:episodeId/next', authenticateRequest, (req, res) => {
  const { showId, seasonId, episodeId } = req.params;
  
  if (!showsDB[showId] || !showsDB[showId].seasons[seasonId]) {
    return res.status(404).json({ error: 'Episode not found' });
  }
  
  const seasons = Object.keys(showsDB[showId].seasons).sort();
  const currentSeasonIndex = seasons.indexOf(seasonId);
  
  if (currentSeasonIndex === -1) {
    return res.status(404).json({ error: 'Season not found' });
  }
  
  // Get episodes in current season
  const currentSeason = showsDB[showId].seasons[seasonId];
  const episodes = Object.keys(currentSeason.episodes).sort();
  const currentEpisodeIndex = episodes.indexOf(episodeId);
  
  if (currentEpisodeIndex === -1) {
    return res.status(404).json({ error: 'Episode not found' });
  }
  
  // Check if there's another episode in current season
  if (currentEpisodeIndex < episodes.length - 1) {
    const nextEpisodeId = episodes[currentEpisodeIndex + 1];
    return res.json({
      showId,
      seasonId,
      episodeId: nextEpisodeId,
      ...currentSeason.episodes[nextEpisodeId],
      streamUrl: `/api/stream/${showId}/${seasonId}/${nextEpisodeId}`,
      hlsUrl: `/api/hls/${showId}/${seasonId}/${nextEpisodeId}/playlist.m3u8`,
      subtitles: getSubtitlesForEpisode(showId, seasonId, nextEpisodeId)
    });
  }
  
  // Check if there's another season
  if (currentSeasonIndex < seasons.length - 1) {
    const nextSeasonId = seasons[currentSeasonIndex + 1];
    const nextSeason = showsDB[showId].seasons[nextSeasonId];
    const firstEpisodeId = Object.keys(nextSeason.episodes).sort()[0];
    
    return res.json({
      showId,
      seasonId: nextSeasonId,
      episodeId: firstEpisodeId,
      ...nextSeason.episodes[firstEpisodeId],
      streamUrl: `/api/stream/${showId}/${nextSeasonId}/${firstEpisodeId}`,
      hlsUrl: `/api/hls/${showId}/${nextSeasonId}/${firstEpisodeId}/playlist.m3u8`,
      subtitles: getSubtitlesForEpisode(showId, nextSeasonId, firstEpisodeId)
    });
  }
  
  // Loop back to first episode of first season
  const firstSeasonId = seasons[0];
  const firstSeason = showsDB[showId].seasons[firstSeasonId];
  const firstEpisodeId = Object.keys(firstSeason.episodes).sort()[0];
  
  return res.json({
    showId,
    seasonId: firstSeasonId,
    episodeId: firstEpisodeId,
    ...firstSeason.episodes[firstEpisodeId],
    streamUrl: `/api/stream/${showId}/${firstSeasonId}/${firstEpisodeId}`,
    hlsUrl: `/api/hls/${showId}/${firstSeasonId}/${firstEpisodeId}/playlist.m3u8`,
    subtitles: getSubtitlesForEpisode(showId, firstSeasonId, firstEpisodeId)
  });
});

// Sync playback state
app.post('/api/state', authenticateRequest, (req, res) => {
  const { deviceId, showId, seasonId, episodeId, position } = req.body;
  
  if (!deviceId || !showId || !seasonId || !episodeId || position === undefined) {
    return res.status(400).json({ error: 'Missing required fields' });
  }
  
  stateDB[deviceId] = {
    showId,
    seasonId,
    episodeId,
    position,
    timestamp: Date.now()
  };
  
  res.json({ success: true });
});

// Get current state for device
app.get('/api/state/:deviceId', authenticateRequest, (req, res) => {
  const { deviceId } = req.params;
  
  if (!stateDB[deviceId]) {
    // If no state exists, return the first episode of the first show
    const firstShowId = Object.keys(showsDB)[0];
    if (!firstShowId) {
      return res.status(404).json({ error: 'No shows available' });
    }
    
    const show = showsDB[firstShowId];
    const firstSeasonId = Object.keys(show.seasons)[0];
    const season = show.seasons[firstSeasonId];
    const firstEpisodeId = Object.keys(season.episodes)[0];
    
    return res.json({
      showId: firstShowId,
      seasonId: firstSeasonId,
      episodeId: firstEpisodeId,
      position: 0,
      streamUrl: `/api/stream/${firstShowId}/${firstSeasonId}/${firstEpisodeId}`,
      hlsUrl: `/api/hls/${firstShowId}/${firstSeasonId}/${firstEpisodeId}/playlist.m3u8`,
      subtitles: getSubtitlesForEpisode(firstShowId, firstSeasonId, firstEpisodeId)
    });
  }
  
  // Return current state with URLs
  const state = stateDB[deviceId];
  res.json({
    ...state,
    streamUrl: `/api/stream/${state.showId}/${state.seasonId}/${state.episodeId}`,
    hlsUrl: `/api/hls/${state.showId}/${state.seasonId}/${state.episodeId}/playlist.m3u8`,
    subtitles: getSubtitlesForEpisode(state.showId, state.seasonId, state.episodeId)
  });
});

// Streaming Routes

// Regular HTTP streaming
app.get('/api/stream/:showId/:seasonId/:episodeId', authenticateRequest, (req, res) => {
  const { showId, seasonId, episodeId } = req.params;
  
  if (!showsDB[showId] || 
      !showsDB[showId].seasons[seasonId] || 
      !showsDB[showId].seasons[seasonId].episodes[episodeId]) {
    return res.status(404).json({ error: 'Episode not found' });
  }
  
  const episode = showsDB[showId].seasons[seasonId].episodes[episodeId];
  const filePath = episode.file;
  
  if (!fs.existsSync(filePath)) {
    return res.status(404).json({ error: 'File not found' });
  }
  
  // Stream the file
  const stat = fs.statSync(filePath);
  const fileSize = stat.size;
  const range = req.headers.range;
  
  if (range) {
    // Handle range requests (seeking)
    const parts = range.replace(/bytes=/, '').split('-');
    const start = parseInt(parts[0], 10);
    const end = parts[1] ? parseInt(parts[1], 10) : fileSize - 1;
    const chunkSize = (end - start) + 1;
    const file = fs.createReadStream(filePath, { start, end });
    
    res.writeHead(206, {
      'Content-Range': `bytes ${start}-${end}/${fileSize}`,
      'Accept-Ranges': 'bytes',
      'Content-Length': chunkSize,
      'Content-Type': 'video/mp4' // Adjust based on file type
    });
    
    file.pipe(res);
  } else {
    // Stream the entire file
    res.writeHead(200, {
      'Content-Length': fileSize,
      'Content-Type': 'video/mp4' // Adjust based on file type
    });
    
    fs.createReadStream(filePath).pipe(res);
  }
});

// HLS streaming
app.get('/api/hls/:showId/:seasonId/:episodeId/playlist.m3u8', authenticateRequest, async (req, res) => {
  const { showId, seasonId, episodeId } = req.params;
  
  if (!showsDB[showId] || 
      !showsDB[showId].seasons[seasonId] || 
      !showsDB[showId].seasons[seasonId].episodes[episodeId]) {
    return res.status(404).json({ error: 'Episode not found' });
  }
  
  const episode = showsDB[showId].seasons[seasonId].episodes[episodeId];
  const sourceFile = episode.file;
  
  // Define HLS output directory
  const hlsOutputDir = path.join(HLS_DIR, showId, seasonId, episodeId);
  const playlistPath = path.join(hlsOutputDir, 'playlist.m3u8');
  
  // Create output directory if it doesn't exist
  if (!fs.existsSync(hlsOutputDir)) {
    fs.mkdirSync(hlsOutputDir, { recursive: true });
  }
  
  // Check if HLS files already exist
  if (!fs.existsSync(playlistPath)) {
    try {
      // Generate HLS files
      await generateHLS(sourceFile, hlsOutputDir);
    } catch (error) {
      console.error('Error generating HLS:', error);
      return res.status(500).json({ error: 'Failed to generate HLS stream' });
    }
  }
  
  // Serve the playlist file
  res.setHeader('Content-Type', 'application/vnd.apple.mpegurl');
  fs.createReadStream(playlistPath).pipe(res);
});

// Serve HLS segments
app.get('/api/hls/:showId/:seasonId/:episodeId/:file', authenticateRequest, (req, res) => {
  const { showId, seasonId, episodeId, file } = req.params;
  const filePath = path.join(HLS_DIR, showId, seasonId, episodeId, file);
  
  if (!fs.existsSync(filePath)) {
    return res.status(404).json({ error: 'Segment not found' });
  }
  
  // Determine content type
  let contentType = 'application/octet-stream';
  if (file.endsWith('.ts')) {
    contentType = 'video/MP2T';
  } else if (file.endsWith('.m3u8')) {
    contentType = 'application/vnd.apple.mpegurl';
  }
  
  res.setHeader('Content-Type', contentType);
  fs.createReadStream(filePath).pipe(res);
});

// Subtitle Routes

// Helper function to get subtitles for an episode
function getSubtitlesForEpisode(showId, seasonId, episodeId) {
  // Check for subtitle files in the subtitles directory
  const subtitleDir = path.join(SUBTITLES_DIR, showId, seasonId);
  
  if (!fs.existsSync(subtitleDir)) {
    return [];
  }
  
  const files = fs.readdirSync(subtitleDir);
  const subtitleFiles = files.filter(file => {
    const nameWithoutExt = path.parse(file).name;
    return (nameWithoutExt === episodeId || nameWithoutExt.startsWith(episodeId + '_')) && 
           (file.endsWith('.vtt') || file.endsWith('.srt'));
  });
  
  return subtitleFiles.map(file => {
    const ext = path.extname(file);
    const nameWithoutExt = path.parse(file).name;
    
    // Determine language from filename (e.g., episode01_en.vtt)
    let language = 'en'; // Default
    if (nameWithoutExt.includes('_')) {
      language = nameWithoutExt.split('_').pop();
    }
    
    return {
      language,
      label: getLanguageLabel(language),
      url: `/api/subtitles/${showId}/${seasonId}/${file}`,
      type: ext === '.vtt' ? 'vtt' : 'srt'
    };
  });
}

// Helper function to get language label
function getLanguageLabel(langCode) {
  const languages = {
    'en': 'English',
    'fr': 'French',
    'es': 'Spanish',
    'de': 'German',
    'it': 'Italian',
    'ja': 'Japanese',
    'ko': 'Korean',
    'zh': 'Chinese',
    // Add more languages as needed
  };
  
  return languages[langCode] || langCode.toUpperCase();
}

// Serve subtitle files
app.get('/api/subtitles/:showId/:seasonId/:file', authenticateRequest, (req, res) => {
  const { showId, seasonId, file } = req.params;
  const filePath = path.join(SUBTITLES_DIR, showId, seasonId, file);
  
  if (!fs.existsSync(filePath)) {
    return res.status(404).json({ error: 'Subtitle file not found' });
  }
  
  // Determine content type
  let contentType = 'text/plain';
  if (file.endsWith('.vtt')) {
    contentType = 'text/vtt';
  } else if (file.endsWith('.srt')) {
    contentType = 'application/x-subrip';
  }
  
  res.setHeader('Content-Type', contentType);
  fs.createReadStream(filePath).pipe(res);
});

// Helper function to convert SRT to VTT
app.get('/api/convert-subtitle/:showId/:seasonId/:file', authenticateRequest, async (req, res) => {
  const { showId, seasonId, file } = req.params;
  
  if (!file.endsWith('.srt')) {
    return res.status(400).json({ error: 'Only SRT files can be converted' });
  }
  
  const srtPath = path.join(SUBTITLES_DIR, showId, seasonId, file);
  const vttPath = path.join(SUBTITLES_DIR, showId, seasonId, file.replace('.srt', '.vtt'));
  
  if (!fs.existsSync(srtPath)) {
    return res.status(404).json({ error: 'Subtitle file not found' });
  }
  
  try {
    await convertSrtToVtt(srtPath, vttPath);
    res.json({ success: true, vttUrl: `/api/subtitles/${showId}/${seasonId}/${file.replace('.srt', '.vtt')}` });
  } catch (error) {
    console.error('Error converting subtitle:', error);
    res.status(500).json({ error: 'Failed to convert subtitle' });
  }
});

// Utility functions

// Generate HLS stream from a source file
function generateHLS(sourceFile, outputDir) {
  return new Promise((resolve, reject) => {
    // Ensure output directory exists
    if (!fs.existsSync(outputDir)) {
      fs.mkdirSync(outputDir, { recursive: true });
    }
    
    // Build FFmpeg command
    const ffmpegCmd = [
      'ffmpeg',
      '-i', `"${sourceFile}"`,
      '-profile:v', 'baseline',
      '-level', '3.0',
      '-start_number', '0',
      '-hls_time', '10',
      '-hls_list_size', '0',
      '-f', 'hls',
      `"${path.join(outputDir, 'playlist.m3u8')}"`
    ].join(' ');
    
    // Execute FFmpeg command
    exec(ffmpegCmd, (error, stdout, stderr) => {
      if (error) {
        console.error('FFmpeg error:', stderr);
        reject(error);
      } else {
        resolve();
      }
    });
  });
}

// Convert SRT to VTT
function convertSrtToVtt(srtPath, vttPath) {
  return new Promise((resolve, reject) => {
    fs.readFile(srtPath, 'utf8', (err, srtContent) => {
      if (err) {
        return reject(err);
      }
      
      // Replace commas with periods in timestamps
      let vttContent = srtContent.replace(/(\d{2}):(\d{2}):(\d{2}),(\d{3})/g, '$1:$2:$3.$4');
      
      // Add VTT header
      vttContent = 'WEBVTT\n\n' + vttContent;
      
      fs.writeFile(vttPath, vttContent, (err) => {
        if (err) {
          reject(err);
        } else {
          resolve();
        }
      });
    });
  });
}

// Start the server
const PORT = process.env.PORT || 3000;
server.listen(PORT, '0.0.0.0', () => {
  console.log(`Server running at http://0.0.0.0:${PORT}`);
  console.log('Media server is ready. Make sure FFmpeg is installed for HLS streaming.');
});
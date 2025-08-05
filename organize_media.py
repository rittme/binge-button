#!/usr/bin/env python3

import os
import json
import re
import shutil
from pathlib import Path

def parse_episode_info(filename):
    """Extract season and episode numbers from filename"""
    # Pattern to match S01E01 format
    pattern = r'[Ss](\d+)[Ee](\d+)'
    match = re.search(pattern, filename)
    
    if match:
        season = int(match.group(1))
        episode = int(match.group(2))
        return season, episode
    return None, None

def get_episode_title(season, episode):
    """Generate a simple episode title"""
    # In a real implementation, you might fetch this from an API like TVDB
    return f"Episode {episode}"

def organize_media():
    """Organize media files and create JSON season files"""
    
    # Source and destination directories
    source_dir = Path("media-to-sort/The Office (US)")
    media_dir = Path("media/shows")
    subtitles_dir = Path("media/subtitles")
    
    # Create directories if they don't exist
    media_dir.mkdir(parents=True, exist_ok=True)
    subtitles_dir.mkdir(parents=True, exist_ok=True)
    
    # Dictionary to store episode information by season
    seasons_data = {}
    
    # Process each season directory
    for season_dir in source_dir.iterdir():
        if season_dir.is_dir() and season_dir.name.startswith("Season "):
            season_num = int(season_dir.name.split()[1])
            print(f"Processing Season {season_num}")
            
            # Initialize season data
            seasons_data[season_num] = []
            
            # Create season media directory
            season_media_dir = media_dir / f"season-{season_num:02d}"
            season_media_dir.mkdir(exist_ok=True)
            
            # Process media files
            for media_file in season_dir.iterdir():
                if media_file.is_file() and media_file.suffix in ['.mp4', '.mkv', '.avi']:
                    # Extract season and episode info
                    season, episode = parse_episode_info(media_file.name)
                    
                    if season and episode:
                        # Create episode ID
                        episode_id = f"Show_S{season:02d}E{episode:02d}"
                        
                        # Copy media file to organized directory
                        new_filename = f"episode-{episode:02d}{media_file.suffix}"
                        dest_path = season_media_dir / new_filename
                        print(f"  Copying {media_file.name} -> {dest_path}")
                        shutil.copy2(media_file, dest_path)
                        
                        # Find corresponding subtitle file
                        subtitle_url = None
                        subs_dir = season_dir / "Subs"
                        if subs_dir.exists():
                            # Look for subtitle folder matching the media file
                            for sub_folder in subs_dir.iterdir():
                                if sub_folder.is_dir() and media_file.stem in sub_folder.name:
                                    # Look for subtitle files in the folder
                                    for sub_file in sub_folder.iterdir():
                                        if sub_file.is_file() and sub_file.suffix in ['.srt', '.vtt']:
                                            # Copy subtitle file
                                            sub_dest_dir = subtitles_dir / f"season-{season:02d}"
                                            sub_dest_dir.mkdir(exist_ok=True)
                                            sub_dest_path = sub_dest_dir / f"episode-{episode:02d}{sub_file.suffix}"
                                            print(f"  Copying subtitle {sub_file} -> {sub_dest_path}")
                                            shutil.copy2(sub_file, sub_dest_path)
                                            subtitle_url = f"/api/episode/{episode_id}/subtitle"
                                            break
                                    if subtitle_url:
                                        break
                        
                        # Create episode info
                        episode_info = {
                            "id": episode_id,
                            "title": get_episode_title(season, episode),
                            "videoUrl": f"/api/episode/{episode_id}/video",
                            "subtitleUrl": subtitle_url
                        }
                        
                        # Add to season data
                        seasons_data[season_num].append(episode_info)
            
            # Sort episodes by episode number
            seasons_data[season_num].sort(key=lambda x: parse_episode_info(x['id'])[1])
    
    # Create JSON files for each season
    for season_num, episodes in seasons_data.items():
        json_filename = f"season_{season_num}.json"
        json_path = media_dir / json_filename
        
        print(f"Creating {json_path} with {len(episodes)} episodes")
        
        with open(json_path, 'w') as f:
            json.dump(episodes, f, indent=2)
    
    print("Media organization complete!")

if __name__ == "__main__":
    organize_media()

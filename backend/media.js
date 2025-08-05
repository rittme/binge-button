// Scan your media library to build showsDB
// This is a simplified version - enhance as needed
export function scanMediaLibrary(show_dir) {
  console.log('Scanning media library...');
  
  // Example structure:
  // /shows/show-name/season-01/episode-01.mp4
  const shows = fs.readdirSync(show_dir);
  
  shows.forEach(showName => {
    const showPath = path.join(show_dir, showName);
    if (fs.statSync(showPath).isDirectory()) {
      const showId = showName.toLowerCase().replace(/\s+/g, '-');
      showsDB[showId] = { 
        title: showName, 
        seasons: {} 
      };
      
      const seasons = fs.readdirSync(showPath);
      seasons.forEach(seasonName => {
        const seasonPath = path.join(showPath, seasonName);
        if (fs.statSync(seasonPath).isDirectory()) {
          const seasonId = seasonName.toLowerCase().replace(/\s+/g, '-');
          showsDB[showId].seasons[seasonId] = {
            title: seasonName,
            episodes: {}
          };
          
          const episodes = fs.readdirSync(seasonPath);
          episodes.forEach(episodeName => {
            if (episodeName.match(/\.(mp4|mkv|avi)$/i)) {
              const episodeId = path.parse(episodeName).name.toLowerCase().replace(/\s+/g, '-');
              const filePath = path.join(seasonPath, episodeName);
              
              showsDB[showId].seasons[seasonId].episodes[episodeId] = {
                title: path.parse(episodeName).name,
                file: filePath,
                duration: 0 // You could use ffprobe to get actual duration
              };
            }
          });
        }
      });
    }
  });
  
  console.log('Media library scan complete.');
}
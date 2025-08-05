I'm building an android app that will play a single TV show in a loop, one episode after the other. This app is for personal use only. It will be watched on multiple devices, but never at the same time. We will stream these episodes from our local network. I need your help building the server for this app.

This is the shape of the data files in the mobile app (Kotlin):

// Represents a single episode's information
data class EpisodeInfo(
    val id: String, // e.g., "Show_S01E01"
    val title: String?, // Optional: "The First Episode"
    val videoUrl: String,
    val subtitleUrl: String? // URL for the .srt or .vtt file
)

// Represents the overall show information and current state
// accessible through API GET: api/show/info
data class ShowInfoResponse(
    val episodes: List<EpisodeInfo>,
    val currentEpisodeId: String?,
    val playbackTimeSeconds: Long?
)

// Represents the state to be sent to the server
// accessible through API POST: api/show/state
data class PlaybackStateUpdateRequest(
    val episodeId: String,
    val playbackTimeSeconds: Long
)

Let's work out a backend server to serve this data. Let's build this server in golang.

The episode info can be stored in a set of json files, one file per season. 

Current episode and playback time will be stored in the server, so we can continue from where we stopped.

Propose an architecture and implementation, and don't hesitate to ask questions to improve it.
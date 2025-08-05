package com.rittme.theofficer.data

// Represents a single episode's information
data class EpisodeInfo(
    val id: String, // e.g., "Show_S01E01"
    val title: String?, // Optional: "The First Episode"
    val videoUrl: String,
    val subtitleUrl: String? // URL for the .srt or .vtt file
)

// Represents the overall show information and current state
data class ShowInfoResponse(
    val episodes: List<EpisodeInfo>,
    val currentEpisodeId: String,
    val playbackTimeSeconds: Long
)

// Represents the state to be sent to the server
data class PlaybackStateUpdateRequest(
    val episodeId: String,
    val playbackTimeSeconds: Long
)

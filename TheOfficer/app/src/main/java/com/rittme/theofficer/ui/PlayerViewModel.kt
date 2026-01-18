package com.rittme.theofficer.ui

import android.util.Log
import androidx.lifecycle.*
import com.rittme.theofficer.data.EpisodeInfo
import com.rittme.theofficer.data.PlaybackStateUpdateRequest
import com.rittme.theofficer.data.ShowInfoResponse
import com.rittme.theofficer.network.ApiService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class PlayerUiState(
    val currentEpisode: EpisodeInfo? = null,
    val startPositionMs: Long = 0L,
    val isLoading: Boolean = true,
    val error: String? = null,
    val allEpisodes: List<EpisodeInfo> = emptyList(),
    val debugInfo: String? = null
)

class PlayerViewModel(private val apiService: ApiService) : ViewModel() {

    private val _uiState = MutableLiveData<PlayerUiState>(PlayerUiState())
    val uiState: LiveData<PlayerUiState> = _uiState

    private var allEpisodes: List<EpisodeInfo> = emptyList()
    private var currentEpisodeIndex = -1
    private var currentEpisodeId: String? = null

    private var progressUpdateJob: Job? = null

    companion object {
        private const val TAG = "PlayerViewModel"
        private const val PROGRESS_UPDATE_INTERVAL_MS = 15_000L // 15 seconds
    }

    init {
        fetchInitialShowInfo()
    }

    fun fetchInitialShowInfo() {
        viewModelScope.launch {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            try {
                _uiState.value = _uiState.value?.copy(isLoading = true, error = null, debugInfo = "[$timestamp] Connecting to server...\nURL: https://bb.13b.xyz/api/show/info")
                val response = apiService.getShowInfo()
                if (response.isSuccessful && response.body() != null) {
                    val showInfo = response.body()!!
                    allEpisodes = showInfo.episodes
                    currentEpisodeId = showInfo.currentEpisodeId

                    if (allEpisodes.isEmpty()) {
                        val debugMsg = buildString {
                            append("[$timestamp] API Response: SUCCESS (200)\n")
                            append("URL: https://bb.13b.xyz/api/show/info\n\n")
                            append("PROBLEM: Server returned 0 episodes\n\n")
                            append("Response Body:\n")
                            append("  currentEpisodeId: ${showInfo.currentEpisodeId}\n")
                            append("  playbackTimeSeconds: ${showInfo.playbackTimeSeconds}\n")
                            append("  episodes: [] (empty)\n\n")
                            append("TROUBLESHOOTING:\n")
                            append("1. Check if backend has episodes in media/shows directory\n")
                            append("2. Check backend logs for episode loading errors\n")
                            append("3. Verify JSON episode files are valid\n")
                        }
                        _uiState.value = _uiState.value?.copy(isLoading = false, error = "No episodes found.", debugInfo = debugMsg)
                        return@launch
                    }

                    currentEpisodeIndex = allEpisodes.indexOfFirst { it.id == currentEpisodeId }
                    if (currentEpisodeIndex == -1) { // Default to first episode if not found or null
                        currentEpisodeIndex = 0
                    }

                    val episodeToPlay = allEpisodes[currentEpisodeIndex]
                    currentEpisodeId = episodeToPlay.id // Update currentEpisodeId

                    val successDebug = "[$timestamp] SUCCESS\n\nLoaded ${allEpisodes.size} episodes\nPlaying: ${episodeToPlay.id}\nPosition: ${showInfo.playbackTimeSeconds}s"

                    _uiState.value = _uiState.value?.copy(
                        currentEpisode = episodeToPlay,
                        startPositionMs = showInfo.playbackTimeSeconds * 1000L,
                        isLoading = false,
                        allEpisodes = allEpisodes,
                        debugInfo = successDebug
                    )
                } else {
                    val errorBody = try {
                        response.errorBody()?.string() ?: "No error body"
                    } catch (e: Exception) {
                        "Error reading body: ${e.message}"
                    }

                    val debugMsg = buildString {
                        append("[$timestamp] API ERROR\n\n")
                        append("URL: https://bb.13b.xyz/api/show/info\n")
                        append("HTTP Status: ${response.code()}\n")
                        append("Message: ${response.message()}\n\n")
                        append("Response Headers:\n")
                        response.headers().forEach { header ->
                            append("  ${header.first}: ${header.second}\n")
                        }
                        append("\nError Body:\n$errorBody\n\n")
                        append("TROUBLESHOOTING:\n")
                        when (response.code()) {
                            401, 403 -> append("- Check API key authentication\n")
                            404 -> append("- Verify backend is running\n- Check endpoint URL\n")
                            500, 502, 503 -> append("- Backend server error\n- Check backend logs\n")
                            else -> append("- Check server connectivity\n")
                        }
                    }

                    _uiState.value = _uiState.value?.copy(
                        isLoading = false,
                        error = "HTTP ${response.code()}: ${response.message()}",
                        debugInfo = debugMsg
                    )
                    Log.e(TAG, "Error fetching show info: ${response.code()} - ${response.message()}")
                }
            } catch (e: Exception) {
                val debugMsg = buildString {
                    append("[$timestamp] NETWORK EXCEPTION\n\n")
                    append("URL: https://bb.13b.xyz/api/show/info\n")
                    append("Exception Type: ${e.javaClass.simpleName}\n")
                    append("Message: ${e.message}\n\n")
                    append("Stack Trace:\n")
                    e.stackTrace.take(10).forEach { frame ->
                        append("  at ${frame}\n")
                    }
                    append("\nCause: ${e.cause?.message ?: "None"}\n\n")
                    append("TROUBLESHOOTING:\n")
                    when (e) {
                        is java.net.UnknownHostException -> {
                            append("- DNS resolution failed\n")
                            append("- Check internet connection\n")
                            append("- Verify server domain: bb.13b.xyz\n")
                            append("- Try pinging server from another device\n")
                        }
                        is java.net.SocketTimeoutException -> {
                            append("- Connection timeout (30s)\n")
                            append("- Server may be down or very slow\n")
                            append("- Check firewall settings\n")
                        }
                        is java.net.ConnectException -> {
                            append("- Cannot connect to server\n")
                            append("- Server may be down\n")
                            append("- Check if server is reachable\n")
                            append("- Verify port and URL\n")
                        }
                        is javax.net.ssl.SSLException -> {
                            append("- SSL/TLS certificate error\n")
                            append("- Check server certificate\n")
                            append("- Try HTTP instead of HTTPS for testing\n")
                        }
                        else -> {
                            append("- Generic network error\n")
                            append("- Check internet connection\n")
                            append("- Verify server is running\n")
                        }
                    }
                }

                _uiState.value = _uiState.value?.copy(
                    isLoading = false,
                    error = "${e.javaClass.simpleName}: ${e.message}",
                    debugInfo = debugMsg
                )
                Log.e(TAG, "Network error: ", e)
            }
        }
    }

    fun playNextEpisode() {
        if (allEpisodes.isEmpty() || currentEpisodeIndex == -1) return
        stopProgressUpdates() // Stop updates for the old episode

        val nextIndex = currentEpisodeIndex + 1
        if (nextIndex < allEpisodes.size) {
            currentEpisodeIndex = nextIndex
            val nextEpisode = allEpisodes[currentEpisodeIndex]
            currentEpisodeId = nextEpisode.id
            _uiState.value = _uiState.value?.copy(
                currentEpisode = nextEpisode,
                startPositionMs = 0L // Start next episode from beginning
            )
            // Optionally, you could fetch the last played position for this *next* episode if it was partially watched.
            // For simplicity, we start from 0. Update server for new episode immediately.
            updatePlaybackState(0L)
        } else {
            // Reached end of show
            _uiState.value = _uiState.value?.copy(error = "You've finished the show!")
             // Optionally loop back or stop
        }
    }

    fun playPreviousEpisode() {
        if (allEpisodes.isEmpty() || currentEpisodeIndex == -1) return
        stopProgressUpdates()

        val prevIndex = currentEpisodeIndex - 1
        if (prevIndex >= 0) {
            currentEpisodeIndex = prevIndex
            val prevEpisode = allEpisodes[currentEpisodeIndex]
            currentEpisodeId = prevEpisode.id
            _uiState.value = _uiState.value?.copy(
                currentEpisode = prevEpisode,
                startPositionMs = 0L // Start previous episode from beginning
            )
            updatePlaybackState(0L)
        } else {
            // At the beginning
             _uiState.value = _uiState.value?.copy(error = "You are at the first episode.")
        }
    }


    fun startProgressUpdates(getCurrentPositionMs: () -> Long) {
        progressUpdateJob?.cancel() // Cancel any existing job
        progressUpdateJob = viewModelScope.launch {
            try {
                while (true) {
                    delay(PROGRESS_UPDATE_INTERVAL_MS)
                    val currentPositionMs = getCurrentPositionMs()
                    updatePlaybackState(currentPositionMs / 1000L) // Convert to seconds
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in progress updates", e)
            }
        }
    }

    fun stopProgressUpdates() {
        progressUpdateJob?.cancel()
        progressUpdateJob = null
    }

    fun updatePlaybackState(currentPositionSeconds: Long) {
        val episodeId = currentEpisodeId ?: return
        viewModelScope.launch {
            try {
                val request = PlaybackStateUpdateRequest(episodeId, currentPositionSeconds)
                val response = apiService.updatePlaybackState(request)
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to update progress: ${response.code()} - ${response.message()}")
                } else {
                    Log.d(TAG, "Progress updated for $episodeId to $currentPositionSeconds s")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating progress: ", e)
            }
        }
    }

    // For providing ViewModel with dependencies (like ApiService)
    class PlayerViewModelFactory(private val apiService: ApiService) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PlayerViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return PlayerViewModel(apiService) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

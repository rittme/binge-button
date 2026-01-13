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
    val allEpisodes: List<EpisodeInfo> = emptyList()
)

class PlayerViewModel(private val apiService: ApiService) : ViewModel() {

    private val _uiState = MutableLiveData<PlayerUiState>(PlayerUiState())
    val uiState: LiveData<PlayerUiState> = _uiState

    private var allEpisodes: List<EpisodeInfo> = emptyList()
    private var currentEpisodeIndex = -1
    private var currentEpisodeId: String? = null

    private var progressUpdateJob: Job? = null
    private var fetchJob: Job? = null
    private var retryCount = 0

    companion object {
        private const val TAG = "PlayerViewModel"
        private const val PROGRESS_UPDATE_INTERVAL_MS = 15_000L // 15 seconds
        private const val MAX_RETRY_ATTEMPTS = 5
        private const val INITIAL_RETRY_DELAY_MS = 2000L // 2 seconds
    }

    init {
        fetchInitialShowInfo()
    }

    fun fetchInitialShowInfo() {
        // Cancel any existing fetch job to prevent duplicate fetches
        fetchJob?.cancel()

        fetchJob = viewModelScope.launch {
            retryCount = 0
            fetchWithRetry()
        }
    }

    private suspend fun fetchWithRetry() {
        var currentDelay = INITIAL_RETRY_DELAY_MS

        while (retryCount < MAX_RETRY_ATTEMPTS) {
            try {
                _uiState.value = _uiState.value?.copy(
                    isLoading = true,
                    error = if (retryCount > 0) "Retrying... (${retryCount}/$MAX_RETRY_ATTEMPTS)" else null
                )

                val response = apiService.getShowInfo()

                if (response.isSuccessful && response.body() != null) {
                    val showInfo = response.body()!!
                    allEpisodes = showInfo.episodes
                    currentEpisodeId = showInfo.currentEpisodeId

                    if (allEpisodes.isEmpty()) {
                        // Empty episodes could be temporary - retry
                        Log.w(TAG, "Received empty episodes list, attempt ${retryCount + 1}/$MAX_RETRY_ATTEMPTS")
                        retryCount++

                        if (retryCount >= MAX_RETRY_ATTEMPTS) {
                            _uiState.value = _uiState.value?.copy(
                                isLoading = false,
                                error = "No episodes found after $MAX_RETRY_ATTEMPTS attempts."
                            )
                            return
                        }

                        // Wait before retry with exponential backoff
                        delay(currentDelay)
                        currentDelay *= 2 // Double delay for next retry
                        continue
                    }

                    // Success! Reset retry count and update UI
                    retryCount = 0
                    currentEpisodeIndex = allEpisodes.indexOfFirst { it.id == currentEpisodeId }
                    if (currentEpisodeIndex == -1) {
                        currentEpisodeIndex = 0
                    }

                    val episodeToPlay = allEpisodes[currentEpisodeIndex]
                    currentEpisodeId = episodeToPlay.id

                    _uiState.value = _uiState.value?.copy(
                        currentEpisode = episodeToPlay,
                        startPositionMs = showInfo.playbackTimeSeconds * 1000L,
                        isLoading = false,
                        error = null,
                        allEpisodes = allEpisodes
                    )

                    Log.d(TAG, "Successfully loaded ${allEpisodes.size} episodes")
                    return // Success - exit retry loop

                } else {
                    // API returned error - retry
                    Log.e(TAG, "API error: ${response.code()} - ${response.message()}, attempt ${retryCount + 1}/$MAX_RETRY_ATTEMPTS")
                    retryCount++

                    if (retryCount >= MAX_RETRY_ATTEMPTS) {
                        _uiState.value = _uiState.value?.copy(
                            isLoading = false,
                            error = "Failed to load episodes: ${response.message()}"
                        )
                        return
                    }

                    delay(currentDelay)
                    currentDelay *= 2
                }

            } catch (e: Exception) {
                // Network error - retry
                Log.e(TAG, "Network error, attempt ${retryCount + 1}/$MAX_RETRY_ATTEMPTS: ", e)
                retryCount++

                if (retryCount >= MAX_RETRY_ATTEMPTS) {
                    _uiState.value = _uiState.value?.copy(
                        isLoading = false,
                        error = "Network error after $MAX_RETRY_ATTEMPTS attempts: ${e.message}"
                    )
                    return
                }

                delay(currentDelay)
                currentDelay *= 2
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

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

    companion object {
        private const val TAG = "PlayerViewModel"
        private const val PROGRESS_UPDATE_INTERVAL_MS = 15_000L // 15 seconds
    }

    init {
        fetchInitialShowInfo()
    }

    fun fetchInitialShowInfo() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value?.copy(isLoading = true, error = null)
                val response = apiService.getShowInfo()
                if (response.isSuccessful && response.body() != null) {
                    val showInfo = response.body()!!
                    allEpisodes = showInfo.episodes
                    currentEpisodeId = showInfo.currentEpisodeId

                    if (allEpisodes.isEmpty()) {
                        _uiState.value = _uiState.value?.copy(isLoading = false, error = "No episodes found.")
                        return@launch
                    }

                    currentEpisodeIndex = allEpisodes.indexOfFirst { it.id == currentEpisodeId }
                    if (currentEpisodeIndex == -1) { // Default to first episode if not found or null
                        currentEpisodeIndex = 0
                    }

                    val episodeToPlay = allEpisodes[currentEpisodeIndex]
                    currentEpisodeId = episodeToPlay.id // Update currentEpisodeId

                    _uiState.value = _uiState.value?.copy(
                        currentEpisode = episodeToPlay,
                        startPositionMs = showInfo.playbackTimeSeconds * 1000L,
                        isLoading = false,
                        allEpisodes = allEpisodes
                    )
                } else {
                    _uiState.value = _uiState.value?.copy(isLoading = false, error = "Failed to load show info: ${response.message()}")
                    Log.e(TAG, "Error fetching show info: ${response.code()} - ${response.message()}")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value?.copy(isLoading = false, error = "Network error: ${e.message}")
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

package com.rittme.theofficer

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rittme.theofficer.network.ApiService
import com.rittme.theofficer.ui.PlayerUiState
import com.rittme.theofficer.ui.PlayerViewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import com.rittme.theofficer.data.EpisodeInfo
import kotlin.math.abs


class PlayerActivity : AppCompatActivity() {
    private val SEEK_TIME_MS: Long = 30000L // 30 seconds

    private lateinit var playerView: PlayerView
    private var exoPlayer: ExoPlayer? = null
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var episodeDescription: TextView
    private var mediaSession: MediaSession? = null
    private var playlistEpisodeIds: List<String> = emptyList()

    private val apiService by lazy { ApiService.create() }
    private val viewModel: PlayerViewModel by viewModels {
        PlayerViewModel.PlayerViewModelFactory(apiService)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val hideEpisodeInfoRunnable = Runnable { episodeDescription.visibility = View.GONE }
    private var isControllerVisible = false
    private var playlistMediaItems: List<MediaItem> = emptyList()

    companion object {
        private const val TAG = "PlayerActivity"
        private const val AUTO_HIDE_DELAY_MS = 4000L
        private const val EPISODE_INFO_HIDE_DELAY_MS = 5000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.player_view)
        loadingIndicator = findViewById(R.id.loading_indicator)
        episodeDescription = findViewById(R.id.episode_description)

        initializePlayer()
        setupControllerVisibilityListener()
        setupViewModelObservers()
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun initializePlayer() {
        try {
            // Initialize ExoPlayer with software decoder fallback enabled
            val renderersFactory = DefaultRenderersFactory(this)
                .forceEnableMediaCodecAsynchronousQueueing()
                .setEnableDecoderFallback(true)

            exoPlayer = ExoPlayer.Builder(this, renderersFactory)
                .setSeekBackIncrementMs(SEEK_TIME_MS)
                .setSeekForwardIncrementMs(SEEK_TIME_MS)
                .build()
                .also { player ->
                    playerView.player = player
                    playerView.useController = true
                    playerView.setControllerShowTimeoutMs(AUTO_HIDE_DELAY_MS.toInt())
                    playerView.setShowNextButton(true)
                    playerView.setShowPreviousButton(true)

                    // Add player event listener
                    player.addListener(playerListener)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ExoPlayer", e)
            Toast.makeText(this, getString(R.string.error_player_init), Toast.LENGTH_LONG).show()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> {
                    Log.d(TAG, "Episode ended.")
                }
                Player.STATE_READY -> {
                    loadingIndicator.visibility = View.GONE
                    if (exoPlayer?.isPlaying == true) {
                        viewModel.startProgressUpdates { exoPlayer?.currentPosition ?: 0L }
                    }
                    val player = exoPlayer
                    if (player != null) {
                        Log.d(
                            TAG,
                            "Player ready. items=${player.mediaItemCount} index=${player.currentMediaItemIndex} " +
                                "hasNext=${player.hasNextMediaItem()} hasPrev=${player.hasPreviousMediaItem()} " +
                                "commands=${player.availableCommands}"
                        )
                    }
                }
                Player.STATE_BUFFERING -> {
                    loadingIndicator.visibility = View.VISIBLE
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                setPreventAmbientMode(true)
                viewModel.startProgressUpdates { exoPlayer?.currentPosition ?: 0L }
            } else {
                setPreventAmbientMode(false)
                viewModel.stopProgressUpdates()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "ExoPlayer Error: ${error.message}", error)
            Toast.makeText(this@PlayerActivity, "Player Error: ${error.message}", Toast.LENGTH_LONG).show()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val index = exoPlayer?.currentMediaItemIndex ?: C.INDEX_UNSET
            val episodes = viewModel.uiState.value?.allEpisodes.orEmpty()
            if (index != C.INDEX_UNSET && index in episodes.indices) {
                viewModel.syncToEpisodeIndex(index)
            }
        }
    }

    private fun initializeMediaSession() {
        val player = exoPlayer
        if (player == null) {
            Log.w(TAG, "MediaSession not created because player is null.")
            return
        }
        mediaSession = MediaSession.Builder(this, player).build()
    }

    private fun updatePlayback() {
        exoPlayer?.currentPosition?.let { currentTime ->
            viewModel.updatePlaybackState(currentTime / 1000L)
        }
    }

    private fun setupViewModelObservers() {
        viewModel.uiState.observe(this) { state ->
            loadingIndicator.visibility = if (state.isLoading) View.VISIBLE else View.GONE

            state.error?.let {
                Log.e(TAG, "UI State Error: $it")
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            }

            state.currentEpisode?.let { episode ->
                Log.d(TAG, "Setting media item: ${episode.videoUrl}, start: ${state.startPositionMs}ms")
                ensurePlaylistAndPlay(state, episode)
                updateEpisodeInfo(episode.id, episode.title)
            } ?: run {
                if (!state.isLoading && state.allEpisodes.isEmpty()) {
                    Toast.makeText(this, getString(R.string.error_no_episodes), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateEpisodeInfo(episodeId: String, title: String?) {
        val displayText = if (title != null) {
            "$episodeId - $title"
        } else {
            episodeId
        }
        episodeDescription.text = displayText
        showEpisodeInfoBriefly()
    }

    private fun ensurePlaylistAndPlay(state: PlayerUiState, episode: EpisodeInfo) {
        val episodes = state.allEpisodes
        if (episodes.isEmpty()) return

        val desiredIndex = episodes.indexOfFirst { it.id == episode.id }.coerceAtLeast(0)
        val newPlaylistIds = episodes.map { it.id }
        val playlistChanged = newPlaylistIds != playlistEpisodeIds

        if (playlistChanged) {
            playlistEpisodeIds = newPlaylistIds
            playlistMediaItems = episodes.map { buildMediaItem(it) }
            exoPlayer?.setMediaItems(playlistMediaItems, desiredIndex, state.startPositionMs)
            exoPlayer?.prepare()
            exoPlayer?.play()
            Log.d(TAG, "Playlist set. items=${playlistMediaItems.size} startIndex=$desiredIndex")
            return
        }

        val currentIndex = exoPlayer?.currentMediaItemIndex ?: C.INDEX_UNSET
        if (currentIndex != desiredIndex) {
            exoPlayer?.seekTo(desiredIndex, state.startPositionMs)
            exoPlayer?.play()
            return
        }

        val currentPosition = exoPlayer?.currentPosition ?: 0L
        if (state.startPositionMs > 0 && abs(currentPosition - state.startPositionMs) > 1000L) {
            exoPlayer?.seekTo(state.startPositionMs)
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun buildMediaItem(episode: EpisodeInfo): MediaItem {
        try {
            // Build MediaItem with subtitle if available
            val mediaItemBuilder = MediaItem.Builder()
                .setUri(Uri.parse(episode.videoUrl))

            val displayTitle = episode.title ?: episode.id
            val mediaMetadata = MediaMetadata.Builder()
                .setTitle(displayTitle)
                .setSubtitle(episode.id)
                .build()
            mediaItemBuilder.setMediaMetadata(mediaMetadata)

            // Add subtitle track if available
            episode.subtitleUrl?.let { subtitleUrl ->
                Log.d(TAG, "Adding subtitle track: $subtitleUrl")
                val subtitle = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP) // Assuming SRT format
                    .setLanguage("en")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
                mediaItemBuilder.setSubtitleConfigurations(listOf(subtitle))
            }

            return mediaItemBuilder.build()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing media: ${episode.videoUrl}", e)
            Toast.makeText(this, "Failed to play media: ${e.message}", Toast.LENGTH_LONG).show()
            return MediaItem.Builder().setUri(Uri.parse(episode.videoUrl)).build()
        }
    }

    private fun seekBackward() {
        exoPlayer?.let { player ->
            player.seekBack()
            Log.d(TAG, "Seeked backward by ${SEEK_TIME_MS}ms")
        }
    }

    private fun seekForward() {
        exoPlayer?.let { player ->
            player.seekForward()
            Log.d(TAG, "Seeked forward by ${SEEK_TIME_MS}ms")
        }
    }
    private fun showEpisodeInfoBriefly() {
        episodeDescription.visibility = View.VISIBLE
        handler.removeCallbacks(hideEpisodeInfoRunnable)
        handler.postDelayed(hideEpisodeInfoRunnable, EPISODE_INFO_HIDE_DELAY_MS)
    }

    private fun setupControllerVisibilityListener() {
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility: Int ->
                if (visibility == View.VISIBLE) {
                    isControllerVisible = true
                    showEpisodeInfoBriefly()
                } else {
                    isControllerVisible = false
                    handler.removeCallbacks(hideEpisodeInfoRunnable)
                    episodeDescription.visibility = View.GONE
                }
            }
        )
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, playerView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun setPreventAmbientMode(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Handle Android TV remote control buttons
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                exoPlayer?.let { player ->
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        player.play()
                    }
                }
                playerView.showController()
                showEpisodeInfoBriefly()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                playerView.showController()
                showEpisodeInfoBriefly()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                seekBackward()
                playerView.showController()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                seekForward()
                playerView.showController()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isControllerVisible) {
                    playerView.hideController()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        if (mediaSession == null) {
            initializeMediaSession()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()

        // If we have an error and no episodes loaded, retry fetching
        viewModel.uiState.value?.let { state ->
            if (state.error != null && state.allEpisodes.isEmpty() && !state.isLoading) {
                Log.d(TAG, "Retrying episode fetch on resume due to error state")
                viewModel.fetchInitialShowInfo()
            }
        }

        exoPlayer?.play()
        if (exoPlayer?.isPlaying == true) {
            viewModel.startProgressUpdates { exoPlayer?.currentPosition ?: 0L }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopProgressUpdates()
        updatePlayback()
        exoPlayer?.pause()
        setPreventAmbientMode(false)
    }

    override fun onStop() {
        super.onStop()
        mediaSession?.release()
        mediaSession = null
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
        mediaSession?.release()
        mediaSession = null
        // Clean up any remaining handlers
        handler.removeCallbacksAndMessages(null)
    }

    private fun releasePlayer() {
        exoPlayer?.let { player ->
            try {
                player.stop()
                player.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing player", e)
            }
        }
        exoPlayer = null
    }
}

package com.rittme.theofficer

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rittme.theofficer.network.ApiService
import com.rittme.theofficer.ui.PlayerViewModel
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia.Slave
import org.videolan.libvlc.util.VLCVideoLayout


class PlayerActivity : AppCompatActivity() {
    private val USE_TEXTURE_VIEW: Boolean = true
    private val ENABLE_SUBTITLES: Boolean = true
    private val SEEK_TIME_MS: Long = 30000L // 30 seconds

    private lateinit var playerView: VLCVideoLayout
    private lateinit var playerContainer: FrameLayout
    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button
    private lateinit var playButton: Button
    private lateinit var seekBackwardButton: Button
    private lateinit var seekForwardButton: Button
    private lateinit var customButtonLayout: LinearLayout
    private lateinit var episodeDescription: TextView
    private lateinit var episodeProgressBar: ProgressBar
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager

    private val apiService by lazy { ApiService.create() }
    private val viewModel: PlayerViewModel by viewModels {
        PlayerViewModel.PlayerViewModelFactory(apiService)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null
    private var isUiVisible = true
    private var focusedButtonIndex = 0
    private val buttons = mutableListOf<Button>()
    private var currentBrightness = 1.0f // 1.0 is normal brightness

    companion object {
        private const val TAG = "PlayerActivity"
        private const val AUTO_HIDE_DELAY_MS = 4000L
        private const val PROGRESS_UPDATE_INTERVAL_MS = 1000L // Update progress every second
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        // Keep screen on during playback to prevent ambient mode on Android TV
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerContainer = findViewById(R.id.player_container)
        playerView = findViewById(R.id.video_layout)
        loadingIndicator = findViewById(R.id.loading_indicator)
        prevButton = findViewById(R.id.button_previous_episode)
        nextButton = findViewById(R.id.button_next_episode)
        playButton = findViewById(R.id.button_play_pause)
        seekBackwardButton = findViewById(R.id.button_seek_backward)
        seekForwardButton = findViewById(R.id.button_seek_forward)
        customButtonLayout = findViewById(R.id.custom_button_layout)
        episodeDescription = findViewById(R.id.episode_description)
        episodeProgressBar = findViewById(R.id.episode_progress_bar)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        initializeVLC()
        initializeMediaSession()
        setupViewModelObservers()
        setupPlayerControls()
        setupAutoHideControls()
    }

    private fun initializeVLC() {
        // Initialize LibVLC
        val options = mutableListOf(
            "--network-caching=5000",
            "--aout=opensles",
            "--audio-time-stretch",
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--rtsp-tcp",
            "--freetype-font=/system/fonts/Roboto.ttf"
        )
        libVLC = LibVLC(this, options)
        mediaPlayer = MediaPlayer(libVLC)
        mediaPlayer?.attachViews(playerView, null, ENABLE_SUBTITLES, USE_TEXTURE_VIEW)
    }

    private fun initializeMediaSession() {
        mediaSession = MediaSessionCompat(this, "PlayerActivity")
        mediaSession.setCallback(mediaSessionCallback)
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
        
        // Set initial playback state
        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO)
        mediaSession.setPlaybackState(stateBuilder.build())
        
        mediaSession.isActive = true
    }

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            mediaPlayer?.play()
            updatePlaybackState()
        }

        override fun onPause() {
            mediaPlayer?.pause()
            updatePlaybackState()
        }

        override fun onSkipToNext() {
            updatePlayback()
            viewModel.playNextEpisode()
        }

        override fun onSkipToPrevious() {
            updatePlayback()
            viewModel.playPreviousEpisode()
        }

        override fun onSeekTo(pos: Long) {
            mediaPlayer?.setTime(pos)
            updateProgressBar()
            updatePlaybackState()
        }
    }

    private fun updatePlaybackState() {
        val state = if (mediaPlayer?.isPlaying == true) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        val position = mediaPlayer?.time ?: 0L

        val stateBuilder = PlaybackStateCompat.Builder()
            .setState(state, position, 1.0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO)
            .setBufferedPosition(0)

        mediaSession.setPlaybackState(stateBuilder.build())
    }

    private fun updateMediaMetadata(episodeId: String, title: String?, duration: Long) {
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title ?: episodeId)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title ?: episodeId)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, episodeId)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)

        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun updatePlayback() {
        mediaPlayer?.time?.let { currentTime ->
            viewModel.updatePlaybackState(currentTime / 1000L)
        }
    }

    private fun setupViewModelObservers() {
        viewModel.uiState.observe(this) { state ->
            loadingIndicator.visibility = if (state.isLoading) View.VISIBLE else View.GONE

            state.error?.let {
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            }

            state.currentEpisode?.let { episode ->
                Log.d(TAG, "Setting media item: ${episode.videoUrl}, start: ${state.startPositionMs}ms")
                playMedia(episode.videoUrl, state.startPositionMs)
                updateEpisodeInfo(episode.id, episode.title)
            } ?: run {
                if (!state.isLoading && state.allEpisodes.isEmpty()) {
                    Toast.makeText(this, "No episodes available to play.", Toast.LENGTH_LONG).show()
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
        episodeDescription.visibility = View.VISIBLE
        
        // Update media metadata
        val duration = mediaPlayer?.length ?: 0L
        updateMediaMetadata(episodeId, title, duration)
        
        // Auto-hide episode info after 5 seconds
        handler.postDelayed({
            if (isUiVisible) { // Only hide if UI is still visible
                episodeDescription.visibility = View.GONE
            }
        }, 5000)
    }

    private fun playMedia(mediaUrl: String, startPosition: Long) {
        mediaPlayer?.stop()

        val media = Media(libVLC, Uri.parse(mediaUrl))
        mediaPlayer?.media = media

        // Add subtitle track if available
        val currentEpisode = viewModel.uiState.value?.currentEpisode
        currentEpisode?.subtitleUrl?.let { subtitleUrl ->
            Log.d(TAG, "Adding subtitle track: $subtitleUrl")
            mediaPlayer?.addSlave(Slave.Type.Subtitle, subtitleUrl.toUri(), true)
            Log.d(TAG, mediaPlayer?.spuTracks.toString())
        }

        media.release()

        Log.d(TAG, "Start Position: $startPosition")

        mediaPlayer?.play()
        mediaPlayer?.setTime(startPosition)
        updatePlaybackState()
    }

    private fun setupPlayerControls() {
        buttons.clear()
        buttons.addAll(listOf(prevButton, seekBackwardButton, playButton, seekForwardButton, nextButton))
        
        prevButton.setOnClickListener {
            updatePlayback()
            viewModel.playPreviousEpisode()
            resetAutoHideTimer()
            updatePlaybackState()
        }
        
        seekBackwardButton.setOnClickListener {
            seekBackward()
            resetAutoHideTimer()
        }
        
        playButton.setOnClickListener {
            if (mediaPlayer!!.isPlaying) {
                mediaPlayer?.pause()
                playButton.text = getString(R.string.play);
            } else {
                mediaPlayer?.play()
                playButton.text = getString(R.string.pause);
            }
            resetAutoHideTimer()
            updatePlaybackState()
        }
        
        seekForwardButton.setOnClickListener {
            seekForward()
            resetAutoHideTimer()
        }
        
        nextButton.setOnClickListener {
            updatePlayback()
            viewModel.playNextEpisode()
            resetAutoHideTimer()
            updatePlaybackState()
        }
    }

    private fun seekBackward() {
        mediaPlayer?.let { player ->
            val currentTime = player.time
            val newTime = (currentTime - SEEK_TIME_MS).coerceAtLeast(0L)
            player.setTime(newTime)
            updateProgressBar()
            updatePlaybackState()
            Log.d(TAG, "Seeked backward to: ${newTime}ms")
        }
    }

    private fun seekForward() {
        mediaPlayer?.let { player ->
            val currentTime = player.time
            val newTime = currentTime + SEEK_TIME_MS
            // Note: VLC will handle seeking beyond the end of the video
            player.setTime(newTime)
            updateProgressBar()
            updatePlaybackState()
            Log.d(TAG, "Seeked forward to: ${newTime}ms")
        }
    }

    private fun updateProgressBar() {
        mediaPlayer?.let { player ->
            val currentTime = player.time
            val duration = player.length
            if (duration > 0) {
                val progress = ((currentTime.toDouble() / duration.toDouble()) * 100).toInt()
                episodeProgressBar.progress = progress
            }
        }
    }

    private fun setupAutoHideControls() {
        // Set click listener on the player container to toggle UI visibility
        playerContainer.setOnClickListener {
            toggleUiVisibility()
        }

        // Start auto-hide timer when video starts playing
        mediaPlayer?.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.EndReached -> {
                    Log.d(TAG, "Episode ended, trying to play next.")
                    viewModel.playNextEpisode()
                    hideUi()
                }
                MediaPlayer.Event.Playing -> {
                    loadingIndicator.visibility = View.GONE
                    viewModel.startProgressUpdates { mediaPlayer?.time ?: 0L }
                    resetAutoHideTimer()
                    startProgressUpdates()
                    updatePlaybackState()
                }
                MediaPlayer.Event.Paused -> {
                    viewModel.stopProgressUpdates()
                    cancelAutoHideTimer()
                    stopProgressUpdates()
                    updatePlaybackState()
                }
                MediaPlayer.Event.Stopped -> {
                    viewModel.stopProgressUpdates()
                    cancelAutoHideTimer()
                    stopProgressUpdates()
                    updatePlaybackState()
                }
                MediaPlayer.Event.EncounteredError -> {
                    Log.e(TAG, "VLC Player Error")
                    Toast.makeText(this, "Player Error", Toast.LENGTH_LONG).show()
                    cancelAutoHideTimer()
                    stopProgressUpdates()
                    updatePlaybackState()
                }
                else -> {
                    // Handle other events if needed
                }
            }
        }
    }

    private var progressUpdateRunnable: Runnable? = null

    private fun startProgressUpdates() {
        stopProgressUpdates() // Stop any existing updates
        progressUpdateRunnable = object : Runnable {
            override fun run() {
                updateProgressBar()
                updatePlaybackState()
                handler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
            }
        }
        handler.post(progressUpdateRunnable!!)
    }

    private fun stopProgressUpdates() {
        progressUpdateRunnable?.let {
            handler.removeCallbacks(it)
            progressUpdateRunnable = null
        }
    }

    private fun toggleUiVisibility() {
        if (isUiVisible) {
            hideUi()
        } else {
            showUi()
        }
    }

    private fun showUi() {
        customButtonLayout.visibility = View.VISIBLE
        episodeDescription.visibility = View.VISIBLE
        episodeProgressBar.visibility = View.VISIBLE
        isUiVisible = true
        resetAutoHideTimer()
    }

    private fun hideUi() {
        customButtonLayout.visibility = View.GONE
        episodeDescription.visibility = View.GONE
        episodeProgressBar.visibility = View.GONE
        isUiVisible = false
        cancelAutoHideTimer()
    }

    private fun resetAutoHideTimer() {
        cancelAutoHideTimer()
        
        // Only start timer if video is playing
        if (mediaPlayer?.isPlaying == true) {
            hideRunnable = Runnable {
                hideUi()
            }
            handler.postDelayed(hideRunnable!!, AUTO_HIDE_DELAY_MS)
        }
    }

    private fun cancelAutoHideTimer() {
        hideRunnable?.let {
            handler.removeCallbacks(it)
            hideRunnable = null
        }
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, playerView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Handle Android TV remote control buttons
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (!isUiVisible) {
                    showUi()
                    return true
                }
                adjustBrightness(0.1f) // Increase brightness
                resetAutoHideTimer()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!isUiVisible) {
                    showUi()
                    return true
                }
                adjustBrightness(-0.1f) // Decrease brightness
                resetAutoHideTimer()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!isUiVisible) {
                    showUi()
                    return true
                }
                cycleButtonFocus(-1) // Move left
                resetAutoHideTimer()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!isUiVisible) {
                    showUi()
                    return true
                }
                cycleButtonFocus(1) // Move right
                resetAutoHideTimer()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (!isUiVisible) {
                    showUi()
                    return true
                }
                // Press the currently focused button
                if (buttons.isNotEmpty() && focusedButtonIndex in buttons.indices) {
                    buttons[focusedButtonIndex].performClick()
                }
                resetAutoHideTimer()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isUiVisible) {
                    hideUi()
                    return true
                }
            }
            else -> {
                // Let MediaSession handle media buttons
                if (mediaSession.controller.dispatchMediaButtonEvent(event)) {
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun adjustBrightness(delta: Float) {
        // Calculate new brightness value (clamped between 0.0 and 1.0)
        currentBrightness = (currentBrightness + delta).coerceIn(0.0f, 1.0f)
        
        // Apply brightness by adjusting the alpha/opacity of the video view
        playerView.alpha = currentBrightness
        
        // Show toast with current brightness level
        val brightnessPercent = (currentBrightness * 100).toInt()
        Toast.makeText(this, "Brightness: $brightnessPercent%", Toast.LENGTH_SHORT).show()
    }

    private fun cycleButtonFocus(direction: Int) {
        if (buttons.isEmpty()) return
        
        // Remove focus from current button
        if (focusedButtonIndex in buttons.indices) {
            buttons[focusedButtonIndex].isFocusable = false
            buttons[focusedButtonIndex].isFocusableInTouchMode = false
            buttons[focusedButtonIndex].clearFocus()
        }
        
        // Calculate new focus index
        focusedButtonIndex = (focusedButtonIndex + direction + buttons.size) % buttons.size
        
        // Set focus to new button
        buttons[focusedButtonIndex].isFocusable = true
        buttons[focusedButtonIndex].isFocusableInTouchMode = true
        buttons[focusedButtonIndex].requestFocus()
        
        // Visual feedback - highlight the focused button
        buttons.forEachIndexed { index, button ->
            button.isSelected = (index == focusedButtonIndex)
        }
    }

    override fun onStart() {
        super.onStart()
        mediaSession.isActive = true
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        mediaPlayer?.play()
        if (mediaPlayer?.isPlaying == true) {
            viewModel.startProgressUpdates { mediaPlayer?.time ?: 0L }
            startProgressUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopProgressUpdates()
        stopProgressUpdates()
        updatePlayback()
        mediaPlayer?.pause()
    }

    override fun onStop() {
        super.onStop()
        mediaSession.isActive = false
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
        mediaSession.release()
        // Remove the keep screen on flag when activity is destroyed
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun releasePlayer() {
        stopProgressUpdates()
        mediaPlayer?.let { player ->
            player.stop()
            player.release()
        }
        libVLC?.release()
        libVLC = null
        mediaPlayer = null
    }
}

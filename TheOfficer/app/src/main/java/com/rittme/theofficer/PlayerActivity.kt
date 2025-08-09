package com.rittme.theofficer

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
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

    private lateinit var playerView: VLCVideoLayout
    private lateinit var playerContainer: FrameLayout
    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button
    private lateinit var playButton: Button
    private lateinit var customButtonLayout: LinearLayout

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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerContainer = findViewById(R.id.player_container)
        playerView = findViewById(R.id.video_layout)
        loadingIndicator = findViewById(R.id.loading_indicator)
        prevButton = findViewById(R.id.button_previous_episode)
        nextButton = findViewById(R.id.button_next_episode)
        playButton = findViewById(R.id.button_play_pause)
        customButtonLayout = findViewById(R.id.custom_button_layout)

        initializeVLC()
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
            } ?: run {
                if (!state.isLoading && state.allEpisodes.isEmpty()) {
                    Toast.makeText(this, "No episodes available to play.", Toast.LENGTH_LONG).show()
                }
            }
        }
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
    }

    private fun setupPlayerControls() {
        buttons.clear()
        buttons.addAll(listOf(prevButton, playButton, nextButton))
        
        prevButton.setOnClickListener {
            updatePlayback()
            viewModel.playPreviousEpisode()
            resetAutoHideTimer()
        }
        nextButton.setOnClickListener {
            updatePlayback()
            viewModel.playNextEpisode()
            resetAutoHideTimer()
        }
        playButton.setOnClickListener {
            if (mediaPlayer!!.isPlaying) {
                mediaPlayer?.pause()
            } else {
                mediaPlayer?.play()
            }
            resetAutoHideTimer()
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
                }
                MediaPlayer.Event.Paused -> {
                    viewModel.stopProgressUpdates()
                    cancelAutoHideTimer()
                }
                MediaPlayer.Event.Stopped -> {
                    viewModel.stopProgressUpdates()
                    cancelAutoHideTimer()
                }
                MediaPlayer.Event.EncounteredError -> {
                    Log.e(TAG, "VLC Player Error")
                    Toast.makeText(this, "Player Error", Toast.LENGTH_LONG).show()
                    cancelAutoHideTimer()
                }
                else -> {
                    // Handle other events if needed
                }
            }
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
        isUiVisible = true
        resetAutoHideTimer()
    }

    private fun hideUi() {
        customButtonLayout.visibility = View.GONE
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
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        mediaPlayer?.play()
        if (mediaPlayer?.isPlaying == true) {
            viewModel.startProgressUpdates { mediaPlayer?.time ?: 0L }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopProgressUpdates()
        updatePlayback()
        mediaPlayer?.pause()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    private fun releasePlayer() {
        mediaPlayer?.let { player ->
            player.stop()
            player.release()
        }
        libVLC?.release()
        libVLC = null
        mediaPlayer = null
    }
}

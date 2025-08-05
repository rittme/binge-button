package com.rittme.theofficer

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
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

    private val apiService by lazy { ApiService.create() }
    private val viewModel: PlayerViewModel by viewModels {
        PlayerViewModel.PlayerViewModelFactory(apiService)
    }

    companion object {
        private const val TAG = "PlayerActivity"
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

        initializeVLC()

        setupViewModelObservers()
        setupPlayerControls()
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
        mediaPlayer?.attachViews(playerView, null, ENABLE_SUBTITLES, USE_TEXTURE_VIEW);

        // Set event listener
        mediaPlayer?.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.EndReached -> {
                    Log.d(TAG, "Episode ended, trying to play next.")
                    viewModel.playNextEpisode()
                }
                MediaPlayer.Event.Playing -> {
                    loadingIndicator.visibility = View.GONE
                    viewModel.startProgressUpdates { mediaPlayer?.time ?: 0L }
                }
                MediaPlayer.Event.Paused -> {
                    viewModel.stopProgressUpdates()
                }
                MediaPlayer.Event.Stopped -> {
                    viewModel.stopProgressUpdates()
                }
                MediaPlayer.Event.EncounteredError -> {
                    Log.e(TAG, "VLC Player Error")
                    Toast.makeText(this, "Player Error", Toast.LENGTH_LONG).show()
                }
                else -> {
                    // Handle other events if needed
                }
            }
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
        prevButton.setOnClickListener {
            mediaPlayer?.time?.let { currentTime ->
                viewModel.updatePlaybackState(currentTime / 1000L)
            }
            viewModel.playPreviousEpisode()
        }
        nextButton.setOnClickListener {
            mediaPlayer?.time?.let { currentTime ->
                viewModel.updatePlaybackState(currentTime / 1000L)
            }
            viewModel.playNextEpisode()
        }
        playButton.setOnClickListener {
            if (mediaPlayer!!.isPlaying) {
                mediaPlayer?.pause();
            } else {
                mediaPlayer?.play();
            }
        }
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, playerView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
        mediaPlayer?.time?.let { currentTime ->
            viewModel.updatePlaybackState(currentTime / 1000L)
        }
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

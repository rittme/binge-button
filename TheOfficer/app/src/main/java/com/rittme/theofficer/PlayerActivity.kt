package com.rittme.theofficer

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.widget.ArrayAdapter
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
    private var dimOverlayView: View? = null
    private var dimAlpha = 0.0f
    private var episodePickerOverlay: View? = null
    private var episodePickerList: ListView? = null
    private var episodePickerTitle: TextView? = null
    private var episodePickerBack: Button? = null
    private var episodePickerClose: Button? = null
    private var episodePickerMode = EpisodePickerMode.SEASONS
    private var episodePickerSeasons: List<SeasonGroup> = emptyList()
    private var currentSeasonGroup: SeasonGroup? = null
    private var episodePickerSelectedIndex = 0

    companion object {
        private const val TAG = "PlayerActivity"
        private const val AUTO_HIDE_DELAY_MS = 4000L
        private const val EPISODE_INFO_HIDE_DELAY_MS = 5000L
        private const val DIM_STEP = 0.1f
        private const val DIM_MAX = 0.9f
    }

    private enum class EpisodePickerMode {
        SEASONS,
        EPISODES
    }

    private data class SeasonGroup(
        val seasonNumber: Int?,
        val label: String,
        val episodes: List<EpisodeInfo>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.player_view)
        episodeDescription = findViewById(R.id.episode_description)

        initializePlayer()
        setupDimOverlay()
        setupOpacityControls()
        setupEpisodePicker()
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
                    playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)

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

    private fun setupControllerVisibilityListener() {
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility: Int ->
                if (visibility == View.VISIBLE) {
                    isControllerVisible = true
                    episodeDescription.visibility = View.VISIBLE
                } else {
                    isControllerVisible = false
                    handler.removeCallbacks(hideEpisodeInfoRunnable)
                    episodeDescription.visibility = View.GONE
                }
            }
        )
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun setupDimOverlay() {
        val overlayFrame = playerView.overlayFrameLayout ?: return
        val overlay = View(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            alpha = dimAlpha
            isClickable = false
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        overlayFrame.addView(overlay, 0)
        dimOverlayView = overlay
    }

    private fun setupOpacityControls() {
        playerView.findViewById<ImageButton?>(R.id.exo_opacity_down)?.setOnClickListener {
            adjustDim(DIM_STEP)
        }
        playerView.findViewById<ImageButton?>(R.id.exo_opacity_up)?.setOnClickListener {
            adjustDim(-DIM_STEP)
        }
    }

    private fun setupEpisodePicker() {
        episodePickerOverlay = findViewById(R.id.episode_picker_overlay)
        episodePickerList = findViewById(R.id.episode_picker_list)
        episodePickerTitle = findViewById(R.id.episode_picker_title)
        episodePickerBack = findViewById(R.id.episode_picker_back)
        episodePickerClose = findViewById(R.id.episode_picker_close)

        findViewById<View?>(R.id.episode_picker_panel)?.setOnClickListener {
            // Consume clicks so only the background dismisses.
        }
        episodePickerOverlay?.setOnClickListener { hideEpisodePicker() }

        episodePickerBack?.setOnClickListener {
            if (episodePickerMode == EpisodePickerMode.EPISODES) {
                showSeasonMenu()
            } else {
                hideEpisodePicker()
            }
        }
        episodePickerClose?.setOnClickListener { hideEpisodePicker() }

        episodePickerList?.setOnItemClickListener { _, _, position, _ ->
            when (episodePickerMode) {
                EpisodePickerMode.SEASONS -> {
                    episodePickerSeasons.getOrNull(position)?.let { season ->
                        showEpisodeMenu(season)
                    }
                }
                EpisodePickerMode.EPISODES -> {
                    val seasonGroup = currentSeasonGroup ?: return@setOnItemClickListener
                    val episode = seasonGroup.episodes.getOrNull(position) ?: return@setOnItemClickListener
                    val allEpisodes = viewModel.uiState.value?.allEpisodes.orEmpty()
                    val index = allEpisodes.indexOfFirst { it.id == episode.id }
                    if (index != -1) {
                        viewModel.syncToEpisodeIndex(index)
                    }
                    hideEpisodePicker()
                }
            }
        }

        playerView.findViewById<ImageButton?>(R.id.exo_episode_picker)?.setOnClickListener {
            showSeasonMenu()
        }
    }

    private fun showSeasonMenu() {
        val episodes = viewModel.uiState.value?.allEpisodes.orEmpty()
        if (episodes.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_no_episodes), Toast.LENGTH_LONG).show()
            return
        }

        episodePickerSeasons = buildSeasonGroups(episodes)
        episodePickerMode = EpisodePickerMode.SEASONS
        currentSeasonGroup = null
        episodePickerTitle?.setText(R.string.episode_picker_season_title)
        episodePickerBack?.visibility = View.INVISIBLE

        val currentSeasonNumber = viewModel.uiState.value?.currentEpisode?.let { parseSeasonNumber(it.id) }
        episodePickerSelectedIndex = episodePickerSeasons.indexOfFirst {
            it.seasonNumber != null && it.seasonNumber == currentSeasonNumber
        }.takeIf { it >= 0 } ?: 0

        val items = episodePickerSeasons.map { formatSeasonDisplay(it) }
        updateEpisodePickerList(items)
        showEpisodePicker()
    }

    private fun showEpisodeMenu(seasonGroup: SeasonGroup) {
        episodePickerMode = EpisodePickerMode.EPISODES
        currentSeasonGroup = seasonGroup
        episodePickerTitle?.setText(R.string.episode_picker_episode_title)
        episodePickerBack?.visibility = View.VISIBLE

        val currentEpisodeId = viewModel.uiState.value?.currentEpisode?.id
        episodePickerSelectedIndex = seasonGroup.episodes.indexOfFirst { it.id == currentEpisodeId }
            .takeIf { it >= 0 } ?: 0

        val items = seasonGroup.episodes.map { formatEpisodeDisplay(it) }
        updateEpisodePickerList(items)
        showEpisodePicker()
    }

    private fun showEpisodePicker() {
        episodePickerOverlay?.visibility = View.VISIBLE
        episodePickerOverlay?.bringToFront()
        episodePickerList?.requestFocus()
        episodePickerList?.setSelection(episodePickerSelectedIndex)
        episodePickerList?.setItemChecked(episodePickerSelectedIndex, true)
    }

    private fun hideEpisodePicker() {
        episodePickerOverlay?.visibility = View.GONE
    }

    private fun updateEpisodePickerList(items: List<String>) {
        episodePickerList?.adapter = ArrayAdapter(
            this,
            R.layout.episode_picker_list_item,
            items
        )
        episodePickerList?.choiceMode = ListView.CHOICE_MODE_SINGLE
        episodePickerList?.setSelection(episodePickerSelectedIndex)
        episodePickerList?.setItemChecked(episodePickerSelectedIndex, true)
    }

    private fun moveEpisodePickerSelection(delta: Int) {
        val list = episodePickerList ?: return
        val count = list.adapter?.count ?: 0
        if (count == 0) return
        episodePickerSelectedIndex = (episodePickerSelectedIndex + delta).coerceIn(0, count - 1)
        list.setSelection(episodePickerSelectedIndex)
        list.setItemChecked(episodePickerSelectedIndex, true)
    }

    private fun activateEpisodePickerSelection() {
        val position = episodePickerSelectedIndex
        when (episodePickerMode) {
            EpisodePickerMode.SEASONS -> {
                episodePickerSeasons.getOrNull(position)?.let { season ->
                    showEpisodeMenu(season)
                }
            }
            EpisodePickerMode.EPISODES -> {
                val seasonGroup = currentSeasonGroup ?: return
                val episode = seasonGroup.episodes.getOrNull(position) ?: return
                val allEpisodes = viewModel.uiState.value?.allEpisodes.orEmpty()
                val index = allEpisodes.indexOfFirst { it.id == episode.id }
                if (index != -1) {
                    viewModel.syncToEpisodeIndex(index)
                }
                hideEpisodePicker()
            }
        }
    }

    private fun buildSeasonGroups(episodes: List<EpisodeInfo>): List<SeasonGroup> {
        val grouped = episodes.groupBy { parseSeasonNumber(it.id) }
        return grouped.entries.map { (seasonNumber, groupEpisodes) ->
            val label = if (seasonNumber != null) "Season $seasonNumber" else "Season ?"
            val sortedEpisodes = groupEpisodes.sortedWith(
                compareBy<EpisodeInfo> { parseEpisodeNumber(it.id) ?: Int.MAX_VALUE }
                    .thenBy { it.id }
            )
            SeasonGroup(seasonNumber, label, sortedEpisodes)
        }.sortedWith(compareBy<SeasonGroup> { it.seasonNumber ?: Int.MAX_VALUE })
    }

    private fun parseSeasonNumber(episodeId: String): Int? {
        val match = Regex("S(\\d{1,2})E(\\d{1,2})", RegexOption.IGNORE_CASE).find(episodeId)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun parseEpisodeNumber(episodeId: String): Int? {
        val match = Regex("S(\\d{1,2})E(\\d{1,2})", RegexOption.IGNORE_CASE).find(episodeId)
        return match?.groupValues?.getOrNull(2)?.toIntOrNull()
    }

    private fun formatSeasonDisplay(seasonGroup: SeasonGroup): String {
        return "${seasonGroup.label} (${seasonGroup.episodes.size})"
    }

    private fun formatEpisodeDisplay(episode: EpisodeInfo): String {
        val episodeNumber = parseEpisodeNumber(episode.id)
        val prefix = if (episodeNumber != null) {
            "E${episodeNumber.toString().padStart(2, '0')}"
        } else {
            episode.id
        }
        return if (!episode.title.isNullOrBlank()) {
            "$prefix - ${episode.title}"
        } else {
            prefix
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val overlayVisible = episodePickerOverlay?.visibility == View.VISIBLE
        if (overlayVisible && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    moveEpisodePickerSelection(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    moveEpisodePickerSelection(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (episodePickerMode == EpisodePickerMode.EPISODES) {
                        showSeasonMenu()
                    } else {
                        hideEpisodePicker()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    activateEpisodePickerSelection()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    hideEpisodePicker()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun adjustDim(delta: Float) {
        dimAlpha = (dimAlpha + delta).coerceIn(0.0f, DIM_MAX)
        dimOverlayView?.alpha = dimAlpha
        Log.d(TAG, "Dim opacity set to $dimAlpha")
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

package com.spacewire.meratune

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.spacewire.meratune.calltheme.RingtoneSetController
import com.spacewire.meratune.data.HomeRepository
import com.spacewire.meratune.data.Tune
import com.spacewire.meratune.ui.PlaybackRingView
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.util.GeneratedTuneTitleHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RingtoneReadyActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var progressJob: Job? = null
    private var previewTune: Tune? = null

    private lateinit var playbackRing: PlaybackRingView
    private lateinit var playPauseButton: ImageButton

    private val ringtoneSetController = RingtoneSetController(
        activity = this,
        analyticsSource = SOURCE_CREATION_FLOW,
        categoryForTune = {
            intent.getStringExtra(EXTRA_CATEGORY).orEmpty().ifBlank { "Devotional" }
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_ringtone_ready)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.readyScroll)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, 0)
            insets
        }

        playbackRing = findViewById(R.id.readyPlaybackRing)
        playPauseButton = findViewById(R.id.playPauseButton)

        val name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Ram" }
        val voice = intent.getStringExtra(EXTRA_VOICE).orEmpty()
        val category = intent.getStringExtra(EXTRA_CATEGORY).orEmpty().ifBlank { "Devotional" }
        val language = intent.getStringExtra(EXTRA_LANGUAGE).orEmpty()
        val generatedTitle = GeneratedTuneTitleHelper.titleFor(name, category)

        mixpanelAnalytics().trackRingtoneCreated(
            voice = voice,
            category = category,
            language = language,
            source = SOURCE_CREATION_FLOW,
        )

        findViewById<TextView>(R.id.readyTitle).text =
            getString(R.string.ready_title, name)
        findViewById<TextView>(R.id.readyTuneName).text = generatedTitle

        setupActions()
        loadPreviewTune(name, category, generatedTitle)
    }

    private fun setupActions() {
        findViewById<ImageView>(R.id.backButton).setOnClickListener { finish() }

        playPauseButton.setOnClickListener { togglePlayback() }
        findViewById<ImageButton>(R.id.rewindButton).setOnClickListener { seekBy(-10_000) }
        findViewById<ImageButton>(R.id.forwardButton).setOnClickListener { seekBy(10_000) }
        findViewById<TextView>(R.id.setRingtoneButton).setOnClickListener {
            previewTune?.let { ringtoneSetController.start(it) }
        }
    }

    private fun loadPreviewTune(name: String, category: String, generatedTitle: String) {
        lifecycleScope.launch {
            val repository = HomeRepository()
            val tune = runCatching { repository.fetchActiveTunes() }
                .getOrNull()
                ?.firstOrNull { it.category?.name.equals(category, ignoreCase = true) }
                ?: runCatching { repository.fetchActiveTunes() }.getOrNull()?.firstOrNull()

            if (tune == null) {
                Toast.makeText(this@RingtoneReadyActivity, R.string.playback_error, Toast.LENGTH_SHORT).show()
                return@launch
            }

            previewTune = tune.copy(name = generatedTitle)
            startPlayback(previewTune!!, autoPlay = true)
        }
    }

    private fun startPlayback(tune: Tune, autoPlay: Boolean) {
        stopPlayback(resetRing = false)

        val playbackUrl = tune.tuneUrl.trim()
        if (playbackUrl.isBlank()) {
            Toast.makeText(this, R.string.playback_error, Toast.LENGTH_SHORT).show()
            return
        }

        player = ExoPlayer.Builder(this).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        updatePlayPauseIcon(isPlaying = false)
                        stopProgressUpdates(resetRing = true)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayPauseIcon(isPlaying)
                    if (isPlaying) {
                        startProgressUpdates()
                    } else {
                        stopProgressUpdates(resetRing = false)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "Preview playback failed", error)
                    Toast.makeText(this@RingtoneReadyActivity, R.string.playback_error, Toast.LENGTH_SHORT).show()
                    stopPlayback()
                }
            })
            setMediaItem(MediaItem.fromUri(Uri.parse(playbackUrl)))
            prepare()
            if (autoPlay) play()
        }
    }

    private fun togglePlayback() {
        val exoPlayer = player
        if (exoPlayer == null) {
            previewTune?.let { startPlayback(it, autoPlay = true) }
            return
        }

        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            exoPlayer.play()
        }
    }

    private fun seekBy(offsetMs: Long) {
        val exoPlayer = player ?: return
        val duration = exoPlayer.duration.takeIf { it > 0 } ?: return
        val target = (exoPlayer.currentPosition + offsetMs).coerceIn(0L, duration)
        exoPlayer.seekTo(target)
        updateRingProgress()
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        playPauseButton.setImageResource(
            if (isPlaying) R.drawable.ic_pause_white else R.drawable.ic_play_white,
        )
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (isActive) {
                updateRingProgress()
                delay(50)
            }
        }
    }

    private fun updateRingProgress() {
        val exoPlayer = player
        val duration = exoPlayer?.duration ?: 0L
        if (exoPlayer != null && duration > 0L) {
            playbackRing.progress = exoPlayer.currentPosition.toFloat() / duration
        }
    }

    private fun stopProgressUpdates(resetRing: Boolean) {
        progressJob?.cancel()
        progressJob = null
        if (resetRing) {
            playbackRing.progress = 0f
        }
    }

    private fun stopPlayback(resetRing: Boolean = true) {
        stopProgressUpdates(resetRing)
        player?.release()
        player = null
        updatePlayPauseIcon(isPlaying = false)
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RingtoneReady"
        private const val SOURCE_CREATION_FLOW = "creation_flow"
        private const val EXTRA_NAME = "extra_name"
        private const val EXTRA_VOICE = "extra_voice"
        private const val EXTRA_CATEGORY = "extra_category"
        private const val EXTRA_LANGUAGE = "extra_language"

        fun intent(
            context: Context,
            name: String,
            voice: String,
            category: String,
            language: String,
        ): Intent {
            return Intent(context, RingtoneReadyActivity::class.java)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_VOICE, voice)
                .putExtra(EXTRA_CATEGORY, category)
                .putExtra(EXTRA_LANGUAGE, language)
        }
    }
}

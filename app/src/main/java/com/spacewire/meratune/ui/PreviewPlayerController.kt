package com.spacewire.meratune.ui

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One-at-a-time preview playback shared by Home, the song picker and the Ready screen.
 *
 * Owns a single [ExoPlayer] (audio focus + pause-on-headphones-unplugged) and the 50 ms progress
 * loop. Every callback carries the `id` the caller passed to [play]/[toggle]. Must be used from the
 * main thread; [scope] should be a main-thread scope such as `lifecycleScope`.
 */
class PreviewPlayerController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {
    interface Listener {
        /** [progress] in `0f..1f`, roughly every 50 ms while playing (and after a seek). */
        fun onProgress(id: String, progress: Float)
        fun onPlayingChanged(id: String, isPlaying: Boolean)
        fun onEnded(id: String)
        fun onError(id: String, error: PlaybackException)
    }

    private var player: ExoPlayer? = null
    private var progressJob: Job? = null

    /** Id passed to the last [play] that has not been released; `null` when idle. */
    var currentId: String? = null
        private set

    val isPlaying: Boolean
        get() = player?.isPlaying == true

    /** Stops whatever is current, builds a fresh player for [url] and starts it. */
    fun play(id: String, url: String) {
        release()
        currentId = id

        val exoPlayer = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player !== exoPlayer) return
                if (playbackState == Player.STATE_ENDED) {
                    stopProgressUpdates()
                    listener.onProgress(id, 1f)
                    listener.onEnded(id)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player !== exoPlayer) return
                if (isPlaying) startProgressUpdates() else stopProgressUpdates()
                listener.onPlayingChanged(id, isPlaying)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (player !== exoPlayer) return
                Log.e(TAG, "Playback failed for $id", error)
                release()
                listener.onError(id, error)
            }
        })

        player = exoPlayer
        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        exoPlayer.prepare()
        exoPlayer.play()
    }

    /**
     * Same [id] as the current item: pause when playing, otherwise resume without re-buffering.
     * A different [id]: [play] it.
     */
    fun toggle(id: String, url: String) {
        if (player != null && currentId == id) {
            if (isPlaying) pause() else resume()
        } else {
            play(id, url)
        }
    }

    fun pause() {
        player?.pause()
    }

    /** Resumes the current item; restarts from the beginning if it had ended, re-prepares after an error. */
    fun resume() {
        val exoPlayer = player ?: return
        when (exoPlayer.playbackState) {
            Player.STATE_ENDED -> exoPlayer.seekTo(0L)
            Player.STATE_IDLE -> exoPlayer.prepare()
            else -> Unit
        }
        exoPlayer.play()
    }

    fun seekBy(offsetMs: Long) {
        val exoPlayer = player ?: return
        val duration = exoPlayer.duration.takeIf { it > 0L } ?: return
        val target = (exoPlayer.currentPosition + offsetMs).coerceIn(0L, duration)
        exoPlayer.seekTo(target)
        publishProgress()
    }

    /** Stops playback and frees the player. Safe to call repeatedly. */
    fun release() {
        stopProgressUpdates()
        player?.release()
        player = null
        currentId = null
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                publishProgress()
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun publishProgress() {
        val exoPlayer = player ?: return
        val id = currentId ?: return
        val duration = exoPlayer.duration
        if (duration > 0L) {
            val progress = (exoPlayer.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
            listener.onProgress(id, progress)
        }
    }

    private companion object {
        const val TAG = "PreviewPlayer"
        const val PROGRESS_INTERVAL_MS = 50L
    }
}

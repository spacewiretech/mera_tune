package com.spacewire.meratune.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.spacewire.meratune.analytics.PlayEndReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * One playback session: from [PreviewPlayerController.play] (or a resume after completion) to
 * completion, release or error. [endReason] is a [PlayEndReason] value; [errorCode] is the
 * lower-cased `PlaybackException.errorCodeName` without its `ERROR_CODE_` prefix.
 */
data class PlaybackSessionStats(
    val id: String,
    val endReason: String,
    val listenedMs: Long,
    val durationMs: Long?,
    val timeToStartMs: Long?,
    val errorCode: String? = null,
)

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

        /**
         * Exactly once per session: at completion (before [onEnded]), on error (before [onError]),
         * or on [release], which [play] also calls for the session it replaces.
         */
        fun onSessionEnded(stats: PlaybackSessionStats) = Unit
    }

    private class Session(val id: String, val startedAtMs: Long) {
        var firstPlayingAtMs: Long? = null
        var playingSinceMs: Long? = null
        var listenedMs: Long = 0L
        var durationMs: Long? = null
    }

    private var player: ExoPlayer? = null
    private var progressJob: Job? = null
    private var session: Session? = null

    /** Id passed to the last [play] that has not been released; `null` when idle. */
    var currentId: String? = null
        private set

    val isPlaying: Boolean
        get() = player?.isPlaying == true

    /** Stops whatever is current, builds a fresh player for [url] and starts it. */
    fun play(id: String, url: String) {
        release()
        currentId = id
        session = Session(id, SystemClock.elapsedRealtime())

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
                if (playbackState == Player.STATE_READY) {
                    session?.durationMs = exoPlayer.duration.takeIf { it > 0L }
                }
                if (playbackState == Player.STATE_ENDED) {
                    stopProgressUpdates()
                    endSession(PlayEndReason.COMPLETED)
                    listener.onProgress(id, 1f)
                    listener.onEnded(id)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player !== exoPlayer) return
                if (isPlaying) startProgressUpdates() else stopProgressUpdates()
                updateListening(isPlaying)
                listener.onPlayingChanged(id, isPlaying)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (player !== exoPlayer) return
                Log.e(TAG, "Playback failed for $id", error)
                endSession(PlayEndReason.ERROR, analyticsErrorCode(error))
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
        val id = currentId
        if (session == null && id != null) {
            session = Session(id, SystemClock.elapsedRealtime())
        }
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
        endSession(PlayEndReason.STOPPED)
        player?.release()
        player = null
        currentId = null
    }

    private fun updateListening(isPlaying: Boolean) {
        val now = SystemClock.elapsedRealtime()
        // A seek on a completed item replays it without resume(): that is a new session too.
        if (isPlaying && session == null) currentId?.let { session = Session(it, now) }
        val current = session ?: return
        if (isPlaying) {
            if (current.firstPlayingAtMs == null) current.firstPlayingAtMs = now
            if (current.playingSinceMs == null) current.playingSinceMs = now
        } else {
            current.playingSinceMs?.let { current.listenedMs += now - it }
            current.playingSinceMs = null
        }
    }

    /** No-op when no session is open, so completion followed by release reports once. */
    private fun endSession(endReason: String, errorCode: String? = null) {
        val ended = session ?: return
        session = null
        val now = SystemClock.elapsedRealtime()
        ended.playingSinceMs?.let { ended.listenedMs += now - it }
        val duration = player?.duration?.takeIf { it > 0L } ?: ended.durationMs
        listener.onSessionEnded(
            PlaybackSessionStats(
                id = ended.id,
                endReason = endReason,
                listenedMs = ended.listenedMs,
                durationMs = duration,
                timeToStartMs = ended.firstPlayingAtMs?.let { it - ended.startedAtMs },
                errorCode = errorCode,
            ),
        )
    }

    private fun analyticsErrorCode(error: PlaybackException): String =
        error.errorCodeName
            .removePrefix("ERROR_CODE_")
            .lowercase(Locale.ROOT)
            .replace(' ', '_')

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

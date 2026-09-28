package com.spacewire.meratune

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import com.spacewire.meratune.analytics.AnalyticsSource
import com.spacewire.meratune.analytics.ReadyAction
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.calltheme.RingtoneSetController
import com.spacewire.meratune.calltheme.SetChoice
import com.spacewire.meratune.data.Tune
import com.spacewire.meratune.ui.CtaButtons
import com.spacewire.meratune.ui.PlaybackRingView
import com.spacewire.meratune.ui.PlaybackSessionStats
import com.spacewire.meratune.ui.PreviewPlayerController
import com.spacewire.meratune.util.ActiveRingtoneStore
import com.spacewire.meratune.util.Haptics
import com.spacewire.meratune.util.enableLightEdgeToEdge

/**
 * Step 4: play the generated ringtone and set it. "Ringtone set karein" applies the set-mode
 * choice made at chuno ([SetChoice], no sheet); without one it runs the full single-shot flow.
 * "Home par jayen" is always available. `ReadyAction.CHANGE_SONG` / `MAKE_ANOTHER` and
 * `CreationEntryPoint.READY_SCREEN` are no longer sent from 1.3.0 (those buttons were removed).
 */
class RingtoneReadyActivity : AppCompatActivity() {

    private lateinit var generatedTune: Tune
    private lateinit var previewId: String
    private var isSet = false
    private var actionTapped = false

    /** The chuno choice; `null` when this screen was opened without one. */
    private var setChoice: SetChoice? = null

    /** The first apply continues the chuno flow; a retry after a terminal failure starts a new one. */
    private var setFlowConsumed = false

    private lateinit var playbackRing: PlaybackRingView
    private lateinit var playPauseButton: ImageButton
    private lateinit var setRingtoneButton: TextView

    private val previewPlayer: PreviewPlayerController by lazy {
        PreviewPlayerController(
            context = this,
            scope = lifecycleScope,
            listener = object : PreviewPlayerController.Listener {
                override fun onProgress(id: String, progress: Float) {
                    playbackRing.progress = progress
                }

                override fun onPlayingChanged(id: String, isPlaying: Boolean) {
                    updatePlayPauseIcon(isPlaying)
                }

                override fun onEnded(id: String) {
                    updatePlayPauseIcon(isPlaying = false)
                    playbackRing.progress = 0f
                }

                override fun onError(id: String, error: PlaybackException) {
                    Log.w(TAG, "Generated ringtone playback failed: ${error.errorCodeName}")
                    updatePlayPauseIcon(isPlaying = false)
                    playbackRing.progress = 0f
                    Toast.makeText(this@RingtoneReadyActivity, R.string.playback_error, Toast.LENGTH_SHORT).show()
                }

                override fun onSessionEnded(stats: PlaybackSessionStats) {
                    // The player id is the generation id; report the base tune id like the other create events.
                    mixpanelAnalytics().trackTunePlayEnded(
                        source = AnalyticsSource.CREATION_FLOW,
                        stats = stats,
                        tuneId = generatedTune.id,
                    )
                }
            },
        )
    }

    private val ringtoneSetController = RingtoneSetController(
        activity = this,
        analyticsSource = AnalyticsSource.CREATION_FLOW,
        categoryForTune = { tune -> tune.category?.name.orEmpty() },
        onSuccess = { tune, uri ->
            // The copy itself (title, file, generation id), so Home marks it Active, not the base tune.
            ActiveRingtoneStore(this).save(tune, uri, personalized = true)
            markSet()
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val name = intent.getStringExtra(EXTRA_NAME).orEmpty().trim()
        val baseTune = Tune.fromIntentJson(intent.getStringExtra(EXTRA_TUNE_JSON))
        val ringtoneUrl = intent.getStringExtra(EXTRA_RINGTONE_URL).orEmpty().trim()
        if (name.isEmpty() || baseTune == null || ringtoneUrl.isEmpty()) {
            finish()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
        val generationId = intent.getStringExtra(EXTRA_GENERATION_ID)?.takeIf { it.isNotBlank() }

        generatedTune = baseTune.copy(
            name = title ?: getString(R.string.ready_default_title, name),
            tuneUrl = ringtoneUrl,
            generationId = generationId,
        )
        previewId = generationId ?: baseTune.id
        isSet = savedInstanceState?.getBoolean(STATE_IS_SET, false) ?: false
        setFlowConsumed = savedInstanceState?.getBoolean(STATE_SET_FLOW_CONSUMED, false) ?: false
        setChoice = SetChoice.from(intent)

        enableLightEdgeToEdge()
        setContentView(R.layout.activity_ringtone_ready)

        setRingtoneButton = findViewById(R.id.setRingtoneButton)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.readyScroll)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        playbackRing = findViewById(R.id.readyPlaybackRing)
        playPauseButton = findViewById(R.id.playPauseButton)

        findViewById<TextView>(R.id.readyTitle).text = getString(R.string.ready_title, name)
        findViewById<TextView>(R.id.readyTuneName).text = generatedTune.name

        setupActions()
        renderSetButton()
        previewPlayer.play(previewId, ringtoneUrl)
    }

    override fun onPause() {
        super.onPause()
        if (::generatedTune.isInitialized) previewPlayer.pause()
    }

    override fun onStop() {
        super.onStop()
        if (::generatedTune.isInitialized) stopPreview()
    }

    override fun onDestroy() {
        if (::generatedTune.isInitialized) previewPlayer.release()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_IS_SET, isSet)
        outState.putBoolean(STATE_SET_FLOW_CONSUMED, setFlowConsumed)
    }

    private fun setupActions() {
        findViewById<ImageView>(R.id.backButton).setOnClickListener {
            onActionTapped(ReadyAction.BACK_BUTTON) { finish() }
        }

        playPauseButton.setOnClickListener {
            previewPlayer.toggle(previewId, generatedTune.tuneUrl)
        }
        findViewById<ImageButton>(R.id.rewindButton).setOnClickListener { previewPlayer.seekBy(-SEEK_STEP_MS) }
        findViewById<ImageButton>(R.id.forwardButton).setOnClickListener { previewPlayer.seekBy(SEEK_STEP_MS) }

        setRingtoneButton.setOnClickListener {
            if (isSet) return@setOnClickListener
            val choice = setChoice
            if (choice != null) {
                if (ringtoneSetController.apply(generatedTune, choice, continuesFlow = !setFlowConsumed)) {
                    setFlowConsumed = true
                }
            } else {
                ringtoneSetController.start(generatedTune)
            }
        }

        findViewById<TextView>(R.id.goHomeButton).setOnClickListener {
            onActionTapped(ReadyAction.GO_HOME) {
                startActivity(
                    Intent(this, Home::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
                finish()
            }
        }
    }

    /** Every Ready action leaves the screen, so only the first tap is tracked and performed. */
    private fun onActionTapped(action: String, perform: () -> Unit) {
        if (actionTapped) return
        actionTapped = true
        mixpanelAnalytics().trackRingtoneReadyActionTapped(
            action = action,
            tuneId = generatedTune.id,
            generationId = generatedTune.generationId,
            isSet = isSet,
        )
        perform()
    }

    private fun markSet() {
        isSet = true
        renderSetButton()
        Haptics.confirm(setRingtoneButton)
    }

    /** After a successful set the primary CTA becomes a disabled "Ringtone set ho gayi". */
    private fun renderSetButton() {
        setRingtoneButton.setText(if (isSet) R.string.ready_set_done else R.string.ready_set_ringtone)
        setRingtoneButton.isEnabled = !isSet
        CtaButtons.setEndIcon(setRingtoneButton, if (isSet) R.drawable.ic_check_white else R.drawable.ic_music_notes_white)
    }

    private fun stopPreview() {
        previewPlayer.release()
        updatePlayPauseIcon(isPlaying = false)
        playbackRing.progress = 0f
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        playPauseButton.setImageResource(if (isPlaying) R.drawable.ic_pause_white else R.drawable.ic_play_white)
    }

    companion object {
        private const val TAG = "RingtoneReady"
        private const val EXTRA_NAME = "extra_name"
        private const val EXTRA_LANGUAGE = "extra_language"
        private const val EXTRA_TUNE_JSON = "extra_tune_json"
        private const val EXTRA_RINGTONE_URL = "extra_ringtone_url"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_GENERATION_ID = "extra_generation_id"
        private const val EXTRA_CACHED = "extra_cached"
        private const val STATE_IS_SET = "state_is_set"
        private const val STATE_SET_FLOW_CONSUMED = "state_set_flow_consumed"

        private const val SEEK_STEP_MS = 10_000L

        fun intent(
            context: Context,
            name: String,
            language: String,
            tune: Tune,
            ringtoneUrl: String,
            title: String?,
            generationId: String,
            cached: Boolean,
            setChoice: SetChoice? = null,
        ): Intent {
            return Intent(context, RingtoneReadyActivity::class.java)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_LANGUAGE, language)
                .putExtra(EXTRA_TUNE_JSON, tune.toIntentJson())
                .putExtra(EXTRA_RINGTONE_URL, ringtoneUrl)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_GENERATION_ID, generationId)
                .putExtra(EXTRA_CACHED, cached)
                .also { intent -> setChoice?.putInto(intent) }
        }
    }
}

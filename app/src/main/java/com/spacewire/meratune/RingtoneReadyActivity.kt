package com.spacewire.meratune

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import com.spacewire.meratune.calltheme.RingtoneSetController
import com.spacewire.meratune.data.Languages
import com.spacewire.meratune.data.Tune
import com.spacewire.meratune.ui.PlaybackRingView
import com.spacewire.meratune.ui.PreviewPlayerController
import com.spacewire.meratune.util.ActiveRingtoneStore
import com.spacewire.meratune.util.Haptics

/** Step 4: play the generated ringtone and set it. */
class RingtoneReadyActivity : AppCompatActivity() {

    private lateinit var generatedTune: Tune
    private lateinit var previewId: String
    private var isSet = false

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
            },
        )
    }

    private val ringtoneSetController = RingtoneSetController(
        activity = this,
        analyticsSource = SOURCE_CREATION_FLOW,
        categoryForTune = { tune -> tune.category?.name.orEmpty() },
        onSuccess = { tune, uri ->
            ActiveRingtoneStore(this).save(tune.id, uri)
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
        val language = intent.getStringExtra(EXTRA_LANGUAGE).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
        val generationId = intent.getStringExtra(EXTRA_GENERATION_ID)?.takeIf { it.isNotBlank() }

        generatedTune = baseTune.copy(
            name = title ?: getString(R.string.ready_default_title, name),
            tuneUrl = ringtoneUrl,
            generationId = generationId,
        )
        previewId = generationId ?: baseTune.id
        isSet = savedInstanceState?.getBoolean(STATE_IS_SET, false) ?: false

        enableEdgeToEdge()
        setContentView(R.layout.activity_ringtone_ready)

        setRingtoneButton = findViewById(R.id.setRingtoneButton)
        val ctaBottomMargin = (setRingtoneButton.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.readyScroll)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, 0)
            setRingtoneButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = ctaBottomMargin + systemBars.bottom
            }
            insets
        }

        playbackRing = findViewById(R.id.readyPlaybackRing)
        playPauseButton = findViewById(R.id.playPauseButton)

        findViewById<TextView>(R.id.readyTitle).text = getString(R.string.ready_title, name)
        findViewById<TextView>(R.id.readyTuneName).text = generatedTune.name
        findViewById<TextView>(R.id.readyBaseSong).text =
            getString(R.string.ready_based_on, baseTune.name, voiceOrLanguageLabel(baseTune, language))

        setupActions()
        renderSetButton()

        if (savedInstanceState == null) {
            playCelebration()
        }
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
    }

    private fun setupActions() {
        findViewById<ImageView>(R.id.backButton).setOnClickListener { finish() }

        playPauseButton.setOnClickListener {
            previewPlayer.toggle(previewId, generatedTune.tuneUrl)
        }
        findViewById<ImageButton>(R.id.rewindButton).setOnClickListener { previewPlayer.seekBy(-SEEK_STEP_MS) }
        findViewById<ImageButton>(R.id.forwardButton).setOnClickListener { previewPlayer.seekBy(SEEK_STEP_MS) }

        setRingtoneButton.setOnClickListener {
            if (isSet) {
                startActivity(
                    Intent(this, Home::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
                finish()
            } else {
                ringtoneSetController.start(generatedTune)
            }
        }

        findViewById<View>(R.id.changeSongButton).setOnClickListener { finish() }
        findViewById<View>(R.id.makeAnotherButton).setOnClickListener {
            startActivity(
                CreateRingtoneActivity.intent(this, "")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
        }
    }

    private fun markSet() {
        isSet = true
        renderSetButton()
        Haptics.confirm(setRingtoneButton)
    }

    private fun renderSetButton() {
        setRingtoneButton.setText(if (isSet) R.string.ready_go_home else R.string.ready_set_ringtone)
    }

    private fun stopPreview() {
        previewPlayer.release()
        updatePlayPauseIcon(isPlaying = false)
        playbackRing.progress = 0f
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        playPauseButton.setImageResource(if (isPlaying) R.drawable.ic_pause_white else R.drawable.ic_play_white)
    }

    /** Overshoot scale-in of the hero, then staggered fades of the copy, with a confirm haptic. */
    private fun playCelebration() {
        val hero = findViewById<View>(R.id.readyHero)
        hero.scaleX = HERO_START_SCALE
        hero.scaleY = HERO_START_SCALE
        hero.alpha = 0f
        hero.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(HERO_DURATION_MS)
            .setInterpolator(OvershootInterpolator(OVERSHOOT_TENSION))
            .withStartAction { Haptics.confirm(hero) }
            .start()

        listOf(R.id.readyTitle, R.id.readyTuneName, R.id.readyBaseSong, R.id.readyControls, R.id.secondaryActionsRow)
            .forEachIndexed { index, viewId ->
                val view = findViewById<View>(viewId)
                view.alpha = 0f
                view.translationY = FADE_OFFSET_DP * resources.displayMetrics.density
                view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(STAGGER_START_MS + index * STAGGER_STEP_MS)
                    .setDuration(FADE_DURATION_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
    }

    private fun voiceOrLanguageLabel(tune: Tune, language: String): String = when (tune.voiceKey) {
        Tune.VOICE_MALE -> getString(R.string.create_form_voice_male)
        Tune.VOICE_FEMALE -> getString(R.string.create_form_voice_female)
        else -> Languages.all.firstOrNull { it.storageValue.equals(language, ignoreCase = true) }
            ?.let { getString(it.nativeLabelRes) }
            ?: language
    }

    companion object {
        private const val TAG = "RingtoneReady"
        private const val SOURCE_CREATION_FLOW = "creation_flow"
        private const val EXTRA_NAME = "extra_name"
        private const val EXTRA_LANGUAGE = "extra_language"
        private const val EXTRA_TUNE_JSON = "extra_tune_json"
        private const val EXTRA_RINGTONE_URL = "extra_ringtone_url"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_GENERATION_ID = "extra_generation_id"
        private const val EXTRA_CACHED = "extra_cached"
        private const val STATE_IS_SET = "state_is_set"

        private const val SEEK_STEP_MS = 10_000L
        private const val HERO_START_SCALE = 0.6f
        private const val HERO_DURATION_MS = 520L
        private const val OVERSHOOT_TENSION = 2.2f
        private const val FADE_OFFSET_DP = 12f
        private const val FADE_DURATION_MS = 320L
        private const val STAGGER_START_MS = 180L
        private const val STAGGER_STEP_MS = 90L

        fun intent(
            context: Context,
            name: String,
            language: String,
            tune: Tune,
            ringtoneUrl: String,
            title: String?,
            generationId: String,
            cached: Boolean,
        ): Intent {
            return Intent(context, RingtoneReadyActivity::class.java)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_LANGUAGE, language)
                .putExtra(EXTRA_TUNE_JSON, tune.toIntentJson())
                .putExtra(EXTRA_RINGTONE_URL, ringtoneUrl)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_GENERATION_ID, generationId)
                .putExtra(EXTRA_CACHED, cached)
        }
    }
}

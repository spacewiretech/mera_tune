package com.spacewire.meratune

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.spacewire.meratune.analytics.AnalyticsSource
import com.spacewire.meratune.analytics.CreationEntryPoint
import com.spacewire.meratune.analytics.GenerationErrorAction
import com.spacewire.meratune.analytics.LogoutReason
import com.spacewire.meratune.analytics.firebaseAnalytics
import com.spacewire.meratune.analytics.metaAnalytics
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.data.GenerationErrorCode
import com.spacewire.meratune.data.Languages
import com.spacewire.meratune.data.Tune
import com.spacewire.meratune.ui.GenerationState
import com.spacewire.meratune.ui.ProcessingStepperController
import com.spacewire.meratune.ui.RingtoneGenerationViewModel
import com.spacewire.meratune.util.AuthStore
import com.spacewire.meratune.util.Haptics
import com.spacewire.meratune.util.enableLightEdgeToEdge
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Step 3: real generation via `generate-ringtone`, with a staged stepper while it runs. */
class RingtoneProcessingActivity : AppCompatActivity() {

    private val viewModel: RingtoneGenerationViewModel by viewModels { RingtoneGenerationViewModel.Factory }

    private lateinit var name: String
    private lateinit var language: String
    private lateinit var tune: Tune

    private lateinit var stepper: ProcessingStepperController
    private lateinit var stepperView: View
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var waveformView: View
    private lateinit var footerView: TextView
    private lateinit var tipView: TextView
    private lateinit var errorContainer: View
    private lateinit var errorMessageView: TextView
    private lateinit var primaryActionButton: TextView
    private lateinit var chooseAnotherButton: TextView

    private val shownAtMs = SystemClock.elapsedRealtime()
    private var generatingUiActive = false
    private var readyAnimationStarted = false
    private var pendingReady: GenerationState.Ready? = null
    private var navigated = false
    private var takingLongerJob: Job? = null
    private var isTakingLonger = false
    private var isWaiting = false
    private var errorActionTaken = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            viewModel.cancel()
            Toast.makeText(this@RingtoneProcessingActivity, R.string.processing_cancel_toast, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val extraName = intent.getStringExtra(EXTRA_NAME).orEmpty().trim()
        val extraLanguage = intent.getStringExtra(EXTRA_LANGUAGE).orEmpty().trim()
        val extraTune = Tune.fromIntentJson(intent.getStringExtra(EXTRA_TUNE_JSON))
        if (extraName.isEmpty() || extraLanguage.isEmpty() || extraTune == null || AuthStore(this).getUserId() <= 0L) {
            finish()
            return
        }
        name = extraName
        language = extraLanguage
        tune = extraTune

        enableLightEdgeToEdge()
        setContentView(R.layout.activity_ringtone_processing)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.processingScroll)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }

        bindViews()
        onBackPressedDispatcher.addCallback(this, backCallback)

        titleView.text = getString(R.string.processing_title, name)
        styleProcessingSubtitle()
        findViewById<TextView>(R.id.processingSongLine).text =
            getString(R.string.processing_song_line, tune.name, voiceOrLanguageLabel())

        val previewedCount = intent.getIntExtra(EXTRA_PREVIEWED_COUNT, -1).takeIf { it >= 0 }
        viewModel.start(tune, name, language, previewedCount)
        observeState()
        rotateTips()
    }

    override fun onResume() {
        super.onResume()
        navigateToReadyIfResumed()
    }

    override fun onDestroy() {
        if (::stepper.isInitialized) stepper.cancel()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    private fun bindViews() {
        stepperView = findViewById(R.id.processingStepper)
        stepper = ProcessingStepperController(stepperView)
        titleView = findViewById(R.id.processingTitle)
        subtitleView = findViewById(R.id.processingSubtitle)
        waveformView = findViewById(R.id.processingWaveform)
        footerView = findViewById(R.id.processingFooter)
        tipView = findViewById(R.id.processingTip)
        errorContainer = findViewById(R.id.processingErrorContainer)
        errorMessageView = findViewById(R.id.errorMessage)
        primaryActionButton = findViewById(R.id.primaryActionButton)
        chooseAnotherButton = findViewById(R.id.chooseAnotherButton)
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: GenerationState) {
        when (state) {
            GenerationState.Idle -> Unit
            is GenerationState.Generating -> showGenerating(waiting = false)
            is GenerationState.Waiting -> showGenerating(waiting = true)
            is GenerationState.Ready -> onReady(state)
            is GenerationState.Failed -> showError(state)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Generating
    // ---------------------------------------------------------------------------------------------

    private fun showGenerating(waiting: Boolean) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        backCallback.isEnabled = true
        isWaiting = waiting

        if (!generatingUiActive) {
            generatingUiActive = true
            errorContainer.visibility = View.GONE
            listOf(titleView, subtitleView, stepperView, waveformView, footerView, tipView)
                .forEach { it.visibility = View.VISIBLE }
            stepper.setProgress(0f)
            stepper.animateTo(INDETERMINATE_TARGET, INDETERMINATE_DURATION_MS, DecelerateInterpolator())
            scheduleTakingLongerFooter()
        }
        renderFooter()
    }

    private fun scheduleTakingLongerFooter() {
        takingLongerJob?.cancel()
        val startedAt = viewModel.currentAttemptStartedAtMs.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
        val remaining = TAKING_LONGER_AFTER_MS - (SystemClock.elapsedRealtime() - startedAt)
        isTakingLonger = remaining <= 0L
        if (isTakingLonger) return
        takingLongerJob = lifecycleScope.launch {
            delay(remaining)
            isTakingLonger = true
            if (generatingUiActive) renderFooter()
        }
    }

    private fun renderFooter() {
        footerView.setText(
            when {
                isWaiting -> R.string.processing_waiting
                isTakingLonger -> R.string.processing_taking_longer
                else -> R.string.processing_footer
            },
        )
    }

    /** Crossfades through the three tips every 3 s while the screen is visible. */
    private fun rotateTips() {
        val tips = listOf(R.string.processing_tip_1, R.string.processing_tip_2, R.string.processing_tip_3)
        tipView.setText(tips.first())
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var index = tips.indexOfFirst { getString(it) == tipView.text.toString() }.coerceAtLeast(0)
                while (true) {
                    delay(TIP_INTERVAL_MS)
                    if (!generatingUiActive) continue
                    index = (index + 1) % tips.size
                    val next = tips[index]
                    tipView.animate().cancel()
                    tipView.animate()
                        .alpha(0f)
                        .setDuration(TIP_FADE_MS)
                        .withEndAction {
                            tipView.setText(next)
                            tipView.animate().alpha(1f).setDuration(TIP_FADE_MS).start()
                        }
                        .start()
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Ready
    // ---------------------------------------------------------------------------------------------

    private fun onReady(state: GenerationState.Ready) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        backCallback.isEnabled = false
        takingLongerJob?.cancel()
        if (readyAnimationStarted) return
        readyAnimationStarted = true
        generatingUiActive = false
        isWaiting = false
        footerView.setText(R.string.processing_footer)

        val visibleMs = SystemClock.elapsedRealtime() - shownAtMs
        val remainingToMinimum = (MIN_VISIBLE_MS - visibleMs).coerceAtLeast(0L)
        stepper.animateTo(1f, READY_SNAP_MS + remainingToMinimum, DecelerateInterpolator()) {
            Haptics.confirm(stepperView)
            lifecycleScope.launch {
                delay(READY_BEAT_MS)
                pendingReady = state
                navigateToReadyIfResumed()
            }
        }
    }

    /** Opens the Ready screen only while this screen is in front; otherwise waits for [onResume]. */
    private fun navigateToReadyIfResumed() {
        val ready = pendingReady ?: return
        if (navigated || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        navigated = true
        pendingReady = null
        val result = ready.result
        startActivity(
            RingtoneReadyActivity.intent(
                context = this,
                name = name,
                language = language,
                tune = tune,
                ringtoneUrl = result.ringtoneUrl,
                title = result.title,
                generationId = result.generationId,
                cached = result.cached,
            ),
        )
        finish()
    }

    // ---------------------------------------------------------------------------------------------
    // Failed
    // ---------------------------------------------------------------------------------------------

    private fun showError(state: GenerationState.Failed) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        backCallback.isEnabled = false
        takingLongerJob?.cancel()
        stepper.cancel()
        generatingUiActive = false
        isWaiting = false
        isTakingLonger = false
        errorActionTaken = false

        listOf(titleView, subtitleView, stepperView, waveformView, footerView, tipView)
            .forEach { it.visibility = View.GONE }
        errorContainer.visibility = View.VISIBLE
        errorMessageView.text = errorMessageFor(state.code)

        val action = primaryActionFor(state)
        primaryActionButton.setText(action.labelRes)
        primaryActionButton.setOnClickListener { onErrorActionTapped(state, action) }
        chooseAnotherButton.setOnClickListener { onErrorActionTapped(state, PrimaryAction.CHOOSE_ANOTHER) }
        chooseAnotherButton.visibility = if (action == PrimaryAction.CHOOSE_ANOTHER) View.GONE else View.VISIBLE
    }

    /** One `generation_error_action_taken` per error screen; a second tap before it changes is ignored. */
    private fun onErrorActionTapped(state: GenerationState.Failed, action: PrimaryAction) {
        if (errorActionTaken) return
        errorActionTaken = true
        mixpanelAnalytics().trackGenerationErrorActionTaken(
            action = action.analyticsValue,
            failureReason = state.code.analyticsValue,
            tuneId = tune.id,
            language = language,
            attempt = viewModel.attempts,
        )
        action.perform()
    }

    private enum class PrimaryAction(val labelRes: Int, val analyticsValue: String) {
        RETRY(R.string.processing_retry, GenerationErrorAction.RETRY),
        LOGIN_AGAIN(R.string.processing_login_again, GenerationErrorAction.LOGIN_AGAIN),
        SUBSCRIBE(R.string.subscription_try_now, GenerationErrorAction.SUBSCRIBE),
        CHANGE_LANGUAGE(R.string.processing_change_language, GenerationErrorAction.CHANGE_LANGUAGE),
        CHOOSE_ANOTHER(R.string.processing_choose_another, GenerationErrorAction.CHOOSE_ANOTHER),
    }

    private fun primaryActionFor(state: GenerationState.Failed): PrimaryAction = when {
        state.code == GenerationErrorCode.UNAUTHORIZED -> PrimaryAction.LOGIN_AGAIN
        state.code == GenerationErrorCode.SUBSCRIPTION_REQUIRED -> PrimaryAction.SUBSCRIBE
        state.code == GenerationErrorCode.UNSUPPORTED_LANGUAGE -> PrimaryAction.CHANGE_LANGUAGE
        state.retryable && viewModel.canRetry -> PrimaryAction.RETRY
        else -> PrimaryAction.CHOOSE_ANOTHER
    }

    private fun PrimaryAction.perform() {
        when (this) {
            PrimaryAction.RETRY -> viewModel.retry()
            PrimaryAction.LOGIN_AGAIN -> loginAgain()
            PrimaryAction.SUBSCRIBE -> {
                startActivity(SubscriptionActivity.intent(this@RingtoneProcessingActivity))
                finish()
            }

            PrimaryAction.CHANGE_LANGUAGE -> {
                Toast.makeText(
                    this@RingtoneProcessingActivity,
                    getString(R.string.processing_error_language_unsupported, languageLabel()),
                    Toast.LENGTH_LONG,
                ).show()
                startActivity(
                    CreateRingtoneActivity.intent(
                        this@RingtoneProcessingActivity,
                        name,
                        CreationEntryPoint.PROCESSING,
                    ).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
                finish()
            }

            PrimaryAction.CHOOSE_ANOTHER -> finish()
        }
    }

    /** Same session teardown as `ProfileActivity`'s logout. */
    private fun loginAgain() {
        mixpanelAnalytics().logout(
            context = this,
            source = AnalyticsSource.RINGTONE_PROCESSING,
            reason = LogoutReason.SESSION_EXPIRED,
        )
        metaAnalytics().clearUserId()
        firebaseAnalytics().clearUserId()
        startActivity(
            PhoneAuthActivity.intent(this).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
        )
        finish()
    }

    private fun errorMessageFor(code: GenerationErrorCode): String = when (code) {
        GenerationErrorCode.NETWORK -> getString(R.string.processing_error_network)
        GenerationErrorCode.TIMEOUT -> getString(R.string.processing_error_timeout)
        GenerationErrorCode.UNSUPPORTED_LANGUAGE ->
            getString(R.string.processing_error_language_unsupported, languageLabel())

        GenerationErrorCode.QUOTA_EXCEEDED -> {
            val quota = getString(R.string.processing_error_quota)
            if (AuthStore(this).getApiToken() == null) {
                quota + "\n" + getString(R.string.processing_error_quota_legacy_hint)
            } else {
                quota
            }
        }

        GenerationErrorCode.TUNE_NOT_FOUND,
        GenerationErrorCode.TUNE_NOT_PERSONALIZABLE,
        -> getString(R.string.processing_error_song_unavailable)

        GenerationErrorCode.NAME_TOO_LONG_FOR_SONG -> getString(R.string.processing_error_name_too_long)
        GenerationErrorCode.NAME_REJECTED,
        GenerationErrorCode.INVALID_NAME,
        -> getString(R.string.processing_error_name_rejected)

        GenerationErrorCode.UNAUTHORIZED -> getString(R.string.processing_error_session)
        GenerationErrorCode.SUBSCRIPTION_REQUIRED -> getString(R.string.processing_error_subscription)
        else -> getString(R.string.processing_error_generic)
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun languageLabel(): String {
        val definition = Languages.all.firstOrNull { it.storageValue.equals(language, ignoreCase = true) }
        return definition?.let { getString(it.nativeLabelRes) } ?: language
    }

    private fun voiceOrLanguageLabel(): String = when (tune.voiceKey) {
        Tune.VOICE_MALE -> getString(R.string.create_form_voice_male)
        Tune.VOICE_FEMALE -> getString(R.string.create_form_voice_female)
        else -> languageLabel()
    }

    private fun styleProcessingSubtitle() {
        val highlight = getString(R.string.processing_subtitle_highlight)
        val fullText = getString(R.string.processing_subtitle, highlight)
        val spannable = SpannableString(fullText)
        val start = fullText.indexOf(highlight)

        if (start >= 0) {
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                start + highlight.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            spannable.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this, R.color.gradient_pink)),
                start,
                start + highlight.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        subtitleView.text = spannable
    }

    companion object {
        private const val EXTRA_NAME = "extra_name"
        private const val EXTRA_LANGUAGE = "extra_language"
        private const val EXTRA_TUNE_JSON = "extra_tune_json"
        private const val EXTRA_PREVIEWED_COUNT = "extra_previewed_count"

        private const val INDETERMINATE_TARGET = 0.9f
        private const val INDETERMINATE_DURATION_MS = 12_000L
        private const val READY_SNAP_MS = 400L
        private const val MIN_VISIBLE_MS = 1_200L
        private const val READY_BEAT_MS = 500L
        private const val TAKING_LONGER_AFTER_MS = 45_000L
        private const val TIP_INTERVAL_MS = 3_000L
        private const val TIP_FADE_MS = 220L

        /**
         * @param name validated display name (never logged or tracked)
         * @param language `Languages.storageValue` the name is spoken in
         * @param tune the picked base song
         * @param previewedCount distinct songs previewed in the picker (analytics only)
         */
        fun intent(
            context: Context,
            name: String,
            language: String,
            tune: Tune,
            previewedCount: Int? = null,
        ): Intent {
            return Intent(context, RingtoneProcessingActivity::class.java)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_LANGUAGE, language)
                .putExtra(EXTRA_TUNE_JSON, tune.toIntentJson())
                .apply { previewedCount?.let { putExtra(EXTRA_PREVIEWED_COUNT, it) } }
        }
    }
}

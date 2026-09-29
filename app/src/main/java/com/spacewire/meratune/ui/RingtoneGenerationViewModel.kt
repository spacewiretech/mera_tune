package com.spacewire.meratune.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.spacewire.meratune.analytics.AnalyticsSource
import com.spacewire.meratune.analytics.AnalyticsTrigger
import com.spacewire.meratune.analytics.FailureReason
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.data.GeneratedRingtone
import com.spacewire.meratune.data.GenerationErrorCode
import com.spacewire.meratune.data.GenerationQuota
import com.spacewire.meratune.data.RingtoneGenerationException
import com.spacewire.meratune.data.RingtoneGenerationRepository
import com.spacewire.meratune.data.Tune
import com.spacewire.meratune.util.AuthStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

sealed class GenerationState {
    data object Idle : GenerationState()

    /** A `generate-ringtone` request for [attempt] (1-based) is in flight. */
    data class Generating(val attempt: Int) : GenerationState()

    /** Server said "busy" (in progress / TTS rate limited); re-posting after [retryAfterMs]. */
    data class Waiting(val retryAfterMs: Long, val attempt: Int) : GenerationState()

    data class Ready(val result: GeneratedRingtone, val clientMs: Long) : GenerationState()

    data class Failed(
        val code: GenerationErrorCode,
        val message: String,
        val retryable: Boolean,
        val clientMs: Long,
        val httpStatus: Int?,
        /** The failure's `quota` (the processing screen's `QUOTA_EXCEEDED` copy); `null` without one. */
        val quota: GenerationQuota? = null,
    ) : GenerationState()
}

/**
 * Drives one personalized-ringtone generation for `RingtoneProcessingActivity`.
 *
 * - [clientRequestId] is created once and kept in [SavedStateHandle] so retries, rotation and process
 *   death all reuse it (the server dedupes per `(user_id, client_request_id)`).
 * - `GENERATION_IN_PROGRESS` / `TTS_RATE_LIMITED` are re-posted automatically with backoff
 *   (min 2 s, x1.5, cap 8 s). `GENERATION_IN_PROGRESS` (another request is rendering it) waits up
 *   to [TOTAL_BUDGET_MS], then fails with `TIMEOUT`; `TTS_RATE_LIMITED` (Gemini refusing, e.g. a key
 *   out of quota) only up to [RATE_LIMIT_BUDGET_MS], then fails with `TTS_RATE_LIMITED` itself.
 * - Analytics: `ringtone_generation_started` once per attempt. `generate-ringtone` owns the outcomes
 *   (`ringtone_created` and every `error_code` it returns); the app sends `ringtone_generation_failed`
 *   only per [GenerationErrorCode.appReportsFailure] or on [cancel], and `creation_limit_reached` on
 *   `QUOTA_EXCEEDED`. The name is never logged or tracked.
 *   `trigger` is `initial`, `retry` (user tap) or `restored` ([start] re-posting after process death);
 *   `total_client_ms` runs from the first attempt and survives process death via [SavedStateHandle].
 */
class RingtoneGenerationViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    private val repository: RingtoneGenerationRepository = RingtoneGenerationRepository(),
) : AndroidViewModel(application) {

    private data class Request(val tune: Tune, val name: String, val language: String, val previewedCount: Int?)

    private val _state = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val state: StateFlow<GenerationState> = _state.asStateFlow()

    private var request: Request? = null
    private var job: Job? = null
    private var attemptStartedAtMs: Long = 0L

    val clientRequestId: String
        get() = savedStateHandle.get<String>(KEY_CLIENT_REQUEST_ID)
            ?: UUID.randomUUID().toString().also { savedStateHandle[KEY_CLIENT_REQUEST_ID] = it }

    /** Attempts posted so far (1 after the first [start]). */
    var attempts: Int
        get() = savedStateHandle.get<Int>(KEY_ATTEMPTS) ?: 0
        private set(value) {
            savedStateHandle[KEY_ATTEMPTS] = value
        }

    /** `SystemClock.elapsedRealtime()` of the first attempt; kept across process death. */
    private var firstAttemptStartedAtMs: Long
        get() = savedStateHandle.get<Long>(KEY_FIRST_ATTEMPT_STARTED_AT_MS) ?: 0L
        set(value) {
            savedStateHandle[KEY_FIRST_ATTEMPT_STARTED_AT_MS] = value
        }

    /** Whether [retry] would post another attempt. */
    val canRetry: Boolean
        get() = attempts < MAX_ATTEMPTS

    val isRunning: Boolean
        get() = job?.isActive == true

    /** `SystemClock.elapsedRealtime()` when the current attempt started; 0 before the first one. */
    val currentAttemptStartedAtMs: Long
        get() = attemptStartedAtMs

    /**
     * Starts the first attempt. Idempotent: a no-op while an attempt is running or once a result or
     * failure exists (rotation re-calls this from `onCreate`).
     *
     * @param previewedCount distinct songs previewed in the picker, for analytics only
     */
    fun start(tune: Tune, name: String, language: String, previewedCount: Int? = null) {
        request = Request(tune, name, language, previewedCount)
        if (isRunning || _state.value !is GenerationState.Idle) return
        // After process death `attempts` survives; the same client_request_id makes this a safe re-post.
        val restored = attempts > 0
        launchAttempt(
            isRetry = restored,
            trigger = if (restored) AnalyticsTrigger.RESTORED else AnalyticsTrigger.INITIAL,
        )
    }

    /** Re-posts after a failure with the same [clientRequestId]. Capped at [MAX_ATTEMPTS]. */
    fun retry() {
        if (isRunning || _state.value !is GenerationState.Failed || !canRetry) return
        launchAttempt(isRetry = true, trigger = AnalyticsTrigger.RETRY)
    }

    /** User backed out mid-generation. */
    fun cancel() {
        val running = isRunning
        job?.cancel()
        job = null
        if (!running) return
        val req = request ?: return
        getApplication<Application>().mixpanelAnalytics().trackRingtoneGenerationFailed(
            tuneId = req.tune.id,
            sampleId = req.tune.id,
            category = req.tune.category?.name.orEmpty(),
            language = req.language,
            voice = req.tune.voiceKey,
            failureReason = FailureReason.USER_CANCELLED,
            httpStatus = null,
            retryable = false,
            clientMs = elapsedSinceAttemptStart(),
            attempt = attempts,
            totalClientMs = elapsedSinceFirstAttempt(),
            clientRequestId = clientRequestId,
        )
        _state.value = GenerationState.Idle
    }

    private fun launchAttempt(isRetry: Boolean, trigger: String) {
        val req = request ?: return
        val attempt = attempts + 1
        attempts = attempt
        attemptStartedAtMs = SystemClock.elapsedRealtime()
        if (firstAttemptStartedAtMs == 0L) firstAttemptStartedAtMs = attemptStartedAtMs
        _state.value = GenerationState.Generating(attempt)

        val analytics = getApplication<Application>().mixpanelAnalytics()
        analytics.trackRingtoneGenerationStarted(
            tuneId = req.tune.id,
            sampleId = req.tune.id,
            category = req.tune.category?.name.orEmpty(),
            language = req.language,
            voice = req.tune.voiceKey,
            nameLength = req.name.length,
            isRetry = isRetry,
            attempt = attempt,
            trigger = trigger,
            clientRequestId = clientRequestId,
            previewedCount = req.previewedCount,
        )

        job = viewModelScope.launch {
            runAttempt(req, attempt)
        }
    }

    private suspend fun runAttempt(req: Request, attempt: Int) {
        val authStore = AuthStore(getApplication())
        val userId = authStore.getUserId()
        if (userId <= 0L) {
            fail(req, GenerationErrorCode.UNAUTHORIZED, "Not logged in", httpStatus = null)
            return
        }

        var backoffMs = MIN_WAIT_MS
        while (true) {
            try {
                val result = repository.generate(
                    userId = userId,
                    userToken = authStore.getApiToken(),
                    tuneId = req.tune.id,
                    name = req.name,
                    language = req.language,
                    clientRequestId = clientRequestId,
                )
                onReady(result)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: RingtoneGenerationException) {
                if (error.code == GenerationErrorCode.GENERATION_IN_PROGRESS ||
                    error.code == GenerationErrorCode.TTS_RATE_LIMITED
                ) {
                    val serverWaitMs = (error.retryAfterSeconds ?: 0).coerceAtLeast(0) * 1_000L
                    val waitMs = maxOf(serverWaitMs, backoffMs).coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)
                    val rateLimited = error.code == GenerationErrorCode.TTS_RATE_LIMITED
                    val budgetMs = if (rateLimited) RATE_LIMIT_BUDGET_MS else TOTAL_BUDGET_MS
                    if (elapsedSinceAttemptStart() + waitMs > budgetMs) {
                        // The server skips busy responses, so this app-side failure is ours to report.
                        fail(
                            req,
                            if (rateLimited) GenerationErrorCode.TTS_RATE_LIMITED else GenerationErrorCode.TIMEOUT,
                            if (rateLimited) "Voice service still rate limited" else "Generation took too long",
                            error.httpStatus,
                            error.quota,
                        )
                        return
                    }
                    Log.d(TAG, "busy code=${error.code.name}, re-posting in ${waitMs}ms")
                    _state.value = GenerationState.Waiting(waitMs, attempt)
                    delay(waitMs)
                    backoffMs = (backoffMs * BACKOFF_FACTOR).toLong().coerceAtMost(MAX_WAIT_MS)
                    _state.value = GenerationState.Generating(attempt)
                    continue
                }
                fail(req, error.code, error.message.orEmpty(), error.httpStatus, error.quota, error.fromServer)
                return
            } catch (error: Exception) {
                Log.e(TAG, "Unexpected generation error: ${error.javaClass.simpleName}")
                fail(req, GenerationErrorCode.UNKNOWN, error.javaClass.simpleName, httpStatus = null)
                return
            }
        }
    }

    private fun onReady(result: GeneratedRingtone) {
        _state.value = GenerationState.Ready(result, elapsedSinceAttemptStart())
    }

    /** [fromServer]: the failure is a server `error_code` (see [GenerationErrorCode.appReportsFailure]). */
    private fun fail(
        req: Request,
        code: GenerationErrorCode,
        message: String,
        httpStatus: Int?,
        quota: GenerationQuota? = null,
        fromServer: Boolean = false,
    ) {
        val clientMs = elapsedSinceAttemptStart()
        Log.w(TAG, "generation failed code=${code.name} status=$httpStatus attempt=$attempts in ${clientMs}ms")
        val analytics = getApplication<Application>().mixpanelAnalytics()
        if (GenerationErrorCode.appReportsFailure(code, fromServer)) {
            analytics.trackRingtoneGenerationFailed(
                tuneId = req.tune.id,
                sampleId = req.tune.id,
                category = req.tune.category?.name.orEmpty(),
                language = req.language,
                voice = req.tune.voiceKey,
                failureReason = code.analyticsValue,
                httpStatus = httpStatus,
                retryable = code.retryable,
                clientMs = clientMs,
                attempt = attempts,
                // Whether the error screen offers Retry (same rule as RingtoneProcessingActivity).
                canRetry = code.retryable && canRetry && !GenerationErrorCode.isServiceDown(code),
                totalClientMs = elapsedSinceFirstAttempt(),
                quotaUsedToday = quota?.usedToday,
                quotaDailyLimit = quota?.dailyLimit,
                clientRequestId = clientRequestId,
            )
        }
        // Once per failed attempt: the processing screen renders this Failed state as the limit screen.
        if (code == GenerationErrorCode.QUOTA_EXCEEDED) {
            analytics.trackCreationLimitReached(
                limitType = CreationLimitPolicy.limitType(quota),
                plan = quota?.plan,
                source = AnalyticsSource.RINGTONE_PROCESSING,
                quotaUsedToday = quota?.usedCount,
                quotaDailyLimit = quota?.limitCount,
            )
        }
        _state.value = GenerationState.Failed(
            code = code,
            message = message,
            retryable = code.retryable,
            clientMs = clientMs,
            httpStatus = httpStatus,
            quota = quota,
        )
    }

    private fun elapsedSinceAttemptStart(): Long =
        if (attemptStartedAtMs == 0L) 0L else SystemClock.elapsedRealtime() - attemptStartedAtMs

    private fun elapsedSinceFirstAttempt(): Long? {
        val startedAt = firstAttemptStartedAtMs.takeIf { it > 0L } ?: return null
        return (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
    }

    companion object {
        private const val TAG = "RingtoneGen"
        private const val KEY_CLIENT_REQUEST_ID = "client_request_id"
        private const val KEY_ATTEMPTS = "attempts"
        private const val KEY_FIRST_ATTEMPT_STARTED_AT_MS = "first_attempt_started_at_ms"

        const val MAX_ATTEMPTS = 3
        private const val MIN_WAIT_MS = 2_000L
        private const val MAX_WAIT_MS = 8_000L
        private const val BACKOFF_FACTOR = 1.5
        private const val TOTAL_BUDGET_MS = 90_000L

        /** A dead or exhausted Gemini key answers 429 every time: stop re-posting after this. */
        private const val RATE_LIMIT_BUDGET_MS = 30_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                RingtoneGenerationViewModel(
                    application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!,
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }
    }
}

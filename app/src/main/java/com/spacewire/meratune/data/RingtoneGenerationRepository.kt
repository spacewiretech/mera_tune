package com.spacewire.meratune.data

import android.util.Log
import com.spacewire.meratune.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.JsonConvertException
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale

/**
 * Error classes for the personalized-ringtone flow. Server codes mirror `generate-ringtone`'s
 * `error_code`; the last five are client-side transport/decoding failures.
 */
enum class GenerationErrorCode(val retryable: Boolean) {
    INVALID_REQUEST(false),
    INVALID_NAME(false),
    NAME_REJECTED(false),
    UNAUTHORIZED(false),
    SUBSCRIPTION_REQUIRED(false),
    TUNE_NOT_FOUND(false),
    GENERATION_IN_PROGRESS(true),
    UNSUPPORTED_LANGUAGE(false),
    TUNE_NOT_PERSONALIZABLE(false),
    NAME_TOO_LONG_FOR_SONG(false),
    QUOTA_EXCEEDED(false),
    SERVICE_BUSY(true),
    TTS_FAILED(true),
    MIX_FAILED(true),
    UPLOAD_FAILED(true),
    TTS_RATE_LIMITED(true),
    SERVICE_UNAVAILABLE(true),
    INTERNAL(true),
    TIMEOUT(true),
    NETWORK(true),
    INVALID_RESPONSE(true),
    UNKNOWN(true),
    ;

    /** Mixpanel `failure_reason` value. */
    val analyticsValue: String = name.lowercase(Locale.ROOT)

    companion object {
        /**
         * Whether the app sends `ringtone_generation_failed` for a terminal failure. `generate-ringtone`
         * tracks every `error_code` it returns (except the busy codes the app re-posts), so the app
         * reports only failures without one ([fromServer] false: transport, timeout, unreadable
         * response, cancel) and [UNAUTHORIZED], where the server could not attribute the user.
         */
        fun appReportsFailure(code: GenerationErrorCode, fromServer: Boolean): Boolean =
            !fromServer || code == UNAUTHORIZED

        /**
         * The ringtone service itself is down or out of capacity, whatever the user does: Gemini
         * still rate-limited after the app's wait (e.g. the API key is out of quota), Gemini
         * failing (bad key, billing, model), generation paused or not configured, or the global
         * daily cap. The processing screen then asks the user to try later and sends them Home.
         */
        fun isServiceDown(code: GenerationErrorCode): Boolean = code in SERVICE_DOWN

        private val SERVICE_DOWN = setOf(TTS_RATE_LIMITED, TTS_FAILED, SERVICE_UNAVAILABLE, SERVICE_BUSY)

        /** Maps a server `error_code` (any case, surrounding whitespace allowed) to a code; unknown or null -> [UNKNOWN]. */
        fun from(raw: String?): GenerationErrorCode {
            val key = raw?.trim()?.uppercase(Locale.ROOT) ?: return UNKNOWN
            if (key.isEmpty()) return UNKNOWN
            return entries.firstOrNull { it.name == key } ?: UNKNOWN
        }
    }
}

/**
 * `quota` of `generate-ringtone` and of `name-ringtones` (mine mode): the caller's fresh renders
 * (a cached ringtone never counts) against their plan's limit.
 *
 * - [plan]: [PLAN_TRIAL] (users.status `trial`, per IST day), [PLAN_MEMBER] (`active`, per IST
 *   calendar month) or [PLAN_DEFAULT] (any other status, per IST day).
 * - [used] / [limit]: this [period]'s numbers ([PERIOD_DAY] or [PERIOD_MONTH]). [usedToday] /
 *   [dailyLimit] carry the same numbers under their pre-plan names (a server before plans sends
 *   only those), so read [usedCount] / [limitCount].
 * - [resetsAt]: ISO-8601 instant the period ends (IST midnight, or 00:00 IST on the 1st).
 * - [memberMonthlyLimit]: the member plan's monthly limit, for the trial copy.
 * - [exceeded] (QUOTA_EXCEEDED errors only): [EXCEEDED_PLAN] = the plan limit above,
 *   [EXCEEDED_ATTEMPTS] = the per-day attempt cap that backs every plan (resets at IST midnight).
 */
@Serializable
data class GenerationQuota(
    @SerialName("used_today") val usedToday: Int? = null,
    @SerialName("daily_limit") val dailyLimit: Int? = null,
    val plan: String? = null,
    val period: String? = null,
    val used: Int? = null,
    val limit: Int? = null,
    @SerialName("resets_at") val resetsAt: String? = null,
    @SerialName("member_monthly_limit") val memberMonthlyLimit: Int? = null,
    val exceeded: String? = null,
) {
    val usedCount: Int?
        get() = used ?: usedToday

    val limitCount: Int?
        get() = limit ?: dailyLimit

    val isMonthly: Boolean
        get() = period == PERIOD_MONTH

    companion object {
        const val PLAN_TRIAL = "trial"
        const val PLAN_MEMBER = "member"
        const val PLAN_DEFAULT = "default"
        const val PERIOD_DAY = "day"
        const val PERIOD_MONTH = "month"
        const val EXCEEDED_PLAN = "plan"
        const val EXCEEDED_ATTEMPTS = "attempts"
    }
}

/** [fromServer]: the response body carried a server `error_code`. */
class RingtoneGenerationException(
    val code: GenerationErrorCode,
    message: String,
    val retryAfterSeconds: Int? = null,
    val quota: GenerationQuota? = null,
    val httpStatus: Int? = null,
    val fromServer: Boolean = false,
) : Exception(message) {
    val retryable: Boolean
        get() = code.retryable
}

data class GeneratedRingtone(
    val generationId: String,
    val renderId: String?,
    val ringtoneUrl: String,
    val title: String?,
    val tuneId: String,
    val tuneName: String?,
    val category: Category?,
    val language: String?,
    val voice: String?,
    val durationMs: Int?,
    val cached: Boolean,
    val quota: GenerationQuota?,
) {
    /** Personalized copy of [base] that `RingtoneHelper`/`RingtoneSetController` can set directly. */
    fun toTune(base: Tune, fallbackTitle: String): Tune = base.copy(
        name = title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
        tuneUrl = ringtoneUrl,
        generationId = generationId,
    )
}

@Serializable
internal data class GenerateRingtoneRequest(
    @SerialName("user_id") val userId: Long,
    @SerialName("user_token") val userToken: String?,
    @SerialName("tune_id") val tuneId: String,
    val name: String,
    val language: String,
    @SerialName("client_request_id") val clientRequestId: String,
    @SerialName("app_version") val appVersion: String,
)

@Serializable
internal data class GenerateRingtoneResponse(
    @SerialName("generation_id") val generationId: String? = null,
    @SerialName("render_id") val renderId: String? = null,
    @SerialName("ringtone_url") val ringtoneUrl: String? = null,
    val title: String? = null,
    @SerialName("tune_id") val tuneId: String? = null,
    @SerialName("tune_name") val tuneName: String? = null,
    val category: Category? = null,
    val language: String? = null,
    val voice: String? = null,
    @SerialName("duration_ms") val durationMs: Int? = null,
    val cached: Boolean? = null,
    val quota: GenerationQuota? = null,
    val error: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Int? = null,
)

/**
 * One `POST /functions/v1/generate-ringtone` per call. No internal polling: the caller handles
 * `GENERATION_IN_PROGRESS` / `TTS_RATE_LIMITED` backoff. Throws [RingtoneGenerationException]
 * for every failure; [CancellationException] propagates untouched.
 */
class RingtoneGenerationRepository {

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
        expectSuccess = false
    }

    private val functionsBaseUrl = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1"

    suspend fun generate(
        userId: Long,
        userToken: String?,
        tuneId: String,
        name: String,
        language: String,
        clientRequestId: String,
    ): GeneratedRingtone {
        val startedAt = System.currentTimeMillis()
        Log.d(TAG, "POST generate-ringtone tune=$tuneId lang=$language nameLen=${name.length} req=$clientRequestId")

        val request = GenerateRingtoneRequest(
            userId = userId,
            userToken = userToken?.takeIf { it.isNotBlank() },
            tuneId = tuneId,
            name = name,
            language = language,
            clientRequestId = clientRequestId,
            appVersion = BuildConfig.VERSION_NAME,
        )

        val response: HttpResponse = try {
            httpClient.post("$functionsBaseUrl/$FUNCTION") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer ${BuildConfig.SUPABASE_KEY}")
                header("apikey", BuildConfig.SUPABASE_KEY)
                setBody(request)
            }
        } catch (error: Throwable) {
            throw mapEdgeTransportError(error, httpStatus = null, tag = TAG, function = FUNCTION)
        }

        val status = response.status.value

        if (status == 408 || status == 504) {
            throw RingtoneGenerationException(
                code = GenerationErrorCode.TIMEOUT,
                message = "Server timed out ($status)",
                httpStatus = status,
            )
        }

        val body: GenerateRingtoneResponse? = try {
            response.body<GenerateRingtoneResponse>()
        } catch (error: JsonConvertException) {
            null
        } catch (error: NoTransformationFoundException) {
            null
        } catch (error: Throwable) {
            throw mapEdgeTransportError(error, httpStatus = status, tag = TAG, function = FUNCTION)
        }

        if (body == null) {
            if (status == 401 || status == 403) {
                throw RingtoneGenerationException(
                    code = GenerationErrorCode.UNAUTHORIZED,
                    message = "Session is not valid ($status)",
                    httpStatus = status,
                )
            }
            throw RingtoneGenerationException(
                code = GenerationErrorCode.INVALID_RESPONSE,
                message = "Unreadable server response ($status)",
                httpStatus = status,
            )
        }

        if (body.error != null || body.errorCode != null) {
            val code = GenerationErrorCode.from(body.errorCode)
            Log.w(TAG, "generate-ringtone failed status=$status code=${code.name} retryAfter=${body.retryAfterSeconds}")
            throw RingtoneGenerationException(
                code = code,
                message = body.error?.takeIf { it.isNotBlank() } ?: "Ringtone generation failed (${code.name})",
                retryAfterSeconds = body.retryAfterSeconds,
                quota = body.quota,
                httpStatus = status,
                fromServer = !body.errorCode.isNullOrBlank(),
            )
        }

        if (!response.status.isSuccess()) {
            val code = if (status == 401 || status == 403) {
                GenerationErrorCode.UNAUTHORIZED
            } else {
                GenerationErrorCode.INVALID_RESPONSE
            }
            throw RingtoneGenerationException(
                code = code,
                message = "Unexpected server response ($status)",
                httpStatus = status,
            )
        }

        val ringtoneUrl = body.ringtoneUrl?.trim().orEmpty()
        val generationId = body.generationId?.trim().orEmpty()
        if (!ringtoneUrl.startsWith("https://") || generationId.isEmpty()) {
            throw RingtoneGenerationException(
                code = GenerationErrorCode.INVALID_RESPONSE,
                message = "Server response is missing a ringtone",
                httpStatus = status,
            )
        }

        val elapsedMs = System.currentTimeMillis() - startedAt
        Log.d(TAG, "ok cached=${body.cached == true} in ${elapsedMs}ms")

        return GeneratedRingtone(
            generationId = generationId,
            renderId = body.renderId?.takeIf { it.isNotBlank() },
            ringtoneUrl = ringtoneUrl,
            title = body.title?.takeIf { it.isNotBlank() },
            tuneId = body.tuneId?.takeIf { it.isNotBlank() } ?: tuneId,
            tuneName = body.tuneName?.takeIf { it.isNotBlank() },
            category = body.category,
            language = body.language?.takeIf { it.isNotBlank() } ?: language,
            voice = body.voice?.takeIf { it.isNotBlank() },
            durationMs = body.durationMs,
            cached = body.cached == true,
            quota = body.quota,
        )
    }

    private companion object {
        const val TAG = "RingtoneGen"
        const val FUNCTION = "generate-ringtone"
        // Past generate-ringtone's worst case (TTS 55 s + mix 60 s + upload 20 s) and the 150 s Edge
        // request limit: by then the server has answered and reported its own outcome, so an app-side
        // TIMEOUT does not double a server ringtone_created / ringtone_generation_failed.
        const val REQUEST_TIMEOUT_MS = 155_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
    }
}

/**
 * Transport or decoding failure of an Edge Function call as a [RingtoneGenerationException]
 * (`TIMEOUT`, `NETWORK`, else `UNKNOWN`). [CancellationException] and an already mapped
 * [RingtoneGenerationException] pass through unchanged. Shared by the `generate-ringtone` and
 * `name-ringtones` repositories.
 */
internal fun mapEdgeTransportError(error: Throwable, httpStatus: Int?, tag: String, function: String): Throwable {
    return when (error) {
        is CancellationException -> error
        is RingtoneGenerationException -> error
        is HttpRequestTimeoutException,
        is ConnectTimeoutException,
        is SocketTimeoutException,
        -> {
            Log.w(tag, "$function timed out: ${error.javaClass.simpleName}")
            RingtoneGenerationException(
                code = GenerationErrorCode.TIMEOUT,
                message = "Request timed out",
                httpStatus = httpStatus,
            )
        }

        is IOException -> {
            Log.w(tag, "$function network error: ${error.javaClass.simpleName}")
            RingtoneGenerationException(
                code = GenerationErrorCode.NETWORK,
                message = "Network error",
                httpStatus = httpStatus,
            )
        }

        else -> {
            Log.e(tag, "$function unexpected error", error)
            RingtoneGenerationException(
                code = GenerationErrorCode.UNKNOWN,
                message = error.message ?: "Unexpected error",
                httpStatus = httpStatus,
            )
        }
    }
}

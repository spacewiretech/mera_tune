package com.spacewire.meratune.data

import android.content.Context
import android.util.Log
import com.spacewire.meratune.BuildConfig
import com.spacewire.meratune.util.AuthStore
import com.spacewire.meratune.util.TuneStatsUtils
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class NameRingtonesRequest(
    @SerialName("user_id") val userId: Long,
    @SerialName("user_token") val userToken: String?,
    val name: String? = null,
    val mine: Boolean? = null,
    val limit: Int? = null,
)

@Serializable
internal data class NameRingtonesResponse(
    val mode: String? = null,
    /** Kept as raw elements so one malformed row is dropped instead of failing the list. */
    val ringtones: List<JsonElement>? = null,
    val error: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
)

/** One `ringtones[]` entry of `name-ringtones`; [tune] is the base tune in the `Tune` shape. */
@Serializable
internal data class NameRingtoneRow(
    val tune: Tune,
    val title: String? = null,
    @SerialName("ringtone_url") val ringtoneUrl: String? = null,
    @SerialName("generation_id") val generationId: String? = null,
)

/** Pure response handling of `name-ringtones`, so it runs in JVM unit tests (no `android.*`). */
internal object NameRingtonesParser {

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * The ringtones of a `name-ringtones` response as ready-to-play [Tune]s. Throws
     * [RingtoneGenerationException] for an error body (server `error_code`, `fromServer` true),
     * a gateway timeout, an unreadable body or a success without `ringtones`.
     */
    fun parse(status: Int, body: String): List<Tune> {
        if (status == 408 || status == 504) {
            throw RingtoneGenerationException(
                code = GenerationErrorCode.TIMEOUT,
                message = "Server timed out ($status)",
                httpStatus = status,
            )
        }

        val response = runCatching { json.decodeFromString(NameRingtonesResponse.serializer(), body) }.getOrNull()
            ?: throw RingtoneGenerationException(
                code = if (status == 401 || status == 403) GenerationErrorCode.UNAUTHORIZED else GenerationErrorCode.INVALID_RESPONSE,
                message = "Unreadable server response ($status)",
                httpStatus = status,
            )

        if (response.error != null || response.errorCode != null) {
            val code = GenerationErrorCode.from(response.errorCode)
            throw RingtoneGenerationException(
                code = code,
                message = response.error?.takeIf { it.isNotBlank() } ?: "Name ringtones lookup failed (${code.name})",
                httpStatus = status,
                fromServer = !response.errorCode.isNullOrBlank(),
            )
        }

        if (status !in 200..299) {
            throw RingtoneGenerationException(
                code = if (status == 401 || status == 403) GenerationErrorCode.UNAUTHORIZED else GenerationErrorCode.INVALID_RESPONSE,
                message = "Unexpected server response ($status)",
                httpStatus = status,
            )
        }

        val rows = response.ringtones ?: throw RingtoneGenerationException(
            code = GenerationErrorCode.INVALID_RESPONSE,
            message = "Server response is missing ringtones",
            httpStatus = status,
        )
        return toTunes(rows)
    }

    /**
     * Personalized copies of each row's base tune: `id` stays the base tune id, `name` is the
     * ringtone title, `tuneUrl` the ringtone mp3 and `generationId` the caller's own generation
     * (null for a ringtone the caller has not made). Rows that do not decode or have no https
     * URL are dropped. Likes/views are Home's stable per-tune numbers.
     */
    fun toTunes(rows: List<JsonElement>): List<Tune> {
        val tunes = rows.mapNotNull { element ->
            val row = runCatching { json.decodeFromJsonElement(NameRingtoneRow.serializer(), element) }.getOrNull()
                ?: return@mapNotNull null
            val url = row.ringtoneUrl?.trim().orEmpty()
            if (!url.startsWith("https://") || row.tune.id.isBlank()) return@mapNotNull null
            row.tune.copy(
                name = row.title?.trim()?.takeIf { it.isNotEmpty() } ?: row.tune.name,
                tuneUrl = url,
                generationId = row.generationId?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
        return TuneStatsUtils.withRandomStats(tunes)
    }
}

/**
 * Read-only `POST /functions/v1/name-ringtones`, authenticated like `generate-ringtone` (the
 * stored `user_id` + api token). Every failure is a [RingtoneGenerationException] (`UNAUTHORIZED`
 * when not logged in, `TIMEOUT` / `NETWORK` for transport, the server `error_code` otherwise);
 * [kotlinx.coroutines.CancellationException] propagates untouched.
 *
 * A [fetchNameRingtones] row the caller has not made yet has `generationId == null`. Posting
 * `generate-ringtone` with its tune id and the same name is a cached hit (no quota) that records
 * it in the caller's own list and returns its generation id.
 */
class NameRingtonesRepository(context: Context) {

    private val authStore = AuthStore(context)

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(NameRingtonesParser.json) }
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
        expectSuccess = false
    }

    private val endpoint = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/$FUNCTION"

    /**
     * Ready ringtones that already sing [name] (at most 20): stock tunes whose sample name is
     * [name], then personalized renders newest first, one per song and voice. Empty when none.
     * An invalid or blocked name fails with `INVALID_NAME` / `NAME_REJECTED`.
     */
    suspend fun fetchNameRingtones(name: String): List<Tune> =
        post(MODE_NAME) { userId, token -> NameRingtonesRequest(userId = userId, userToken = token, name = name) }

    /** The logged-in user's own ready ringtones, newest first, one per song and name (at most 50). */
    suspend fun fetchMyRingtones(): List<Tune> =
        post(MODE_MINE) { userId, token -> NameRingtonesRequest(userId = userId, userToken = token, mine = true) }

    private suspend fun post(mode: String, build: (Long, String?) -> NameRingtonesRequest): List<Tune> {
        val userId = authStore.getUserId()
        if (userId <= 0L) {
            throw RingtoneGenerationException(GenerationErrorCode.UNAUTHORIZED, "Not logged in")
        }
        val request = build(userId, authStore.getApiToken())

        val (status, body) = try {
            val response = httpClient.post(endpoint) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer ${BuildConfig.SUPABASE_KEY}")
                header("apikey", BuildConfig.SUPABASE_KEY)
                setBody(request)
            }
            response.status.value to response.bodyAsText()
        } catch (error: Throwable) {
            throw mapEdgeTransportError(error, httpStatus = null, tag = TAG, function = FUNCTION)
        }

        return try {
            NameRingtonesParser.parse(status, body).also { Log.d(TAG, "$mode ok count=${it.size}") }
        } catch (error: RingtoneGenerationException) {
            Log.w(TAG, "$FUNCTION $mode failed status=$status code=${error.code.name}")
            throw error
        }
    }

    private companion object {
        const val TAG = "NameRingtones"
        const val FUNCTION = "name-ringtones"
        const val MODE_NAME = "name"
        const val MODE_MINE = "mine"
        const val REQUEST_TIMEOUT_MS = 20_000L
        const val CONNECT_TIMEOUT_MS = 10_000L
    }
}

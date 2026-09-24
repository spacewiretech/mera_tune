package com.spacewire.meratune.data

import com.spacewire.meratune.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Locale

@Serializable
data class SendOtpRequest(val phone: String)

@Serializable
data class SendOtpResponse(
    val success: Boolean? = null,
    val phone: String? = null,
    @SerialName("expires_in_minutes") val expiresInMinutes: Int? = null,
    val error: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
)

@Serializable
data class VerifyOtpRequest(
    val phone: String,
    val otp: String,
    @SerialName("app_version") val appVersion: String,
)

@Serializable
data class VerifyOtpResponse(
    val verified: Boolean? = null,
    @SerialName("needs_name") val needsName: Boolean? = null,
    @SerialName("session_token") val sessionToken: String? = null,
    /** Long-lived API token issued for returning users; persisted in `AuthStore`. */
    @SerialName("api_token") val apiToken: String? = null,
    val user: User? = null,
    val error: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
)

@Serializable
data class CompleteSignupRequest(
    @SerialName("session_token") val sessionToken: String,
    val name: String,
    @SerialName("app_version") val appVersion: String,
)

@Serializable
data class CompleteSignupResponse(
    val success: Boolean? = null,
    val user: User? = null,
    /** Long-lived API token issued for the new user; persisted in `AuthStore`. */
    @SerialName("api_token") val apiToken: String? = null,
    val error: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
)

/** Result of a successful signup: the saved user plus the API token (null when the server did not issue one). */
data class CompleteSignupResult(
    val user: User,
    val apiToken: String?,
)

class AuthRepository {

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    private val functionsBaseUrl = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1"

    suspend fun sendOtp(phone: String): Result<String> = catching {
        val response = httpClient.post("$functionsBaseUrl/send-otp") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${BuildConfig.SUPABASE_KEY}")
            header("apikey", BuildConfig.SUPABASE_KEY)
            setBody(SendOtpRequest(phone))
        }.body<SendOtpResponse>()

        response.error?.let { throw AuthException(it, response.errorCode) }
        response.phone ?: throw AuthException("Could not send OTP", AuthFailureReason.BAD_RESPONSE)
    }

    suspend fun verifyOtp(phone: String, otp: String): Result<VerifyOtpResponse> = catching {
        val response = httpClient.post("$functionsBaseUrl/verify-otp") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${BuildConfig.SUPABASE_KEY}")
            header("apikey", BuildConfig.SUPABASE_KEY)
            setBody(VerifyOtpRequest(phone, otp, appVersion = BuildConfig.VERSION_NAME))
        }.body<VerifyOtpResponse>()

        response.error?.let { throw AuthException(it, response.errorCode) }
        if (response.verified != true) throw AuthException("Verification failed", AuthFailureReason.BAD_RESPONSE)
        response
    }

    suspend fun completeSignup(sessionToken: String, name: String): Result<CompleteSignupResult> = catching {
        val response = httpClient.post("$functionsBaseUrl/complete-signup") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${BuildConfig.SUPABASE_KEY}")
            header("apikey", BuildConfig.SUPABASE_KEY)
            setBody(CompleteSignupRequest(sessionToken, name, appVersion = BuildConfig.VERSION_NAME))
        }.body<CompleteSignupResponse>()

        response.error?.let { throw AuthException(it, response.errorCode) }
        val user = response.user ?: throw AuthException("Could not save profile", AuthFailureReason.BAD_RESPONSE)
        CompleteSignupResult(
            user = user,
            apiToken = response.apiToken?.takeIf { it.isNotBlank() },
        )
    }

    /** `runCatching` that lets coroutine cancellation (rotation, finish) propagate instead of failing. */
    private inline fun <T> catching(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
}

/** [code] is the server's `error_code` (or a client [AuthFailureReason]); `null` from older servers. */
class AuthException(message: String, val code: String? = null) : Exception(message)

/** `stage` values for `auth_failed`; OTP verification has its own `otp_verification_failed`. */
object AuthStage {
    const val PHONE_VALIDATION = "phone_validation"
    const val SEND_OTP = "send_otp"
    const val NAME_VALIDATION = "name_validation"
    const val COMPLETE_SIGNUP = "complete_signup"
}

/**
 * Bounded `failure_reason` for `auth_failed` / `otp_verification_failed`: the server `error_code`,
 * or a client value. Never the exception or server text (Fast2SMS errors are forwarded verbatim
 * and may echo the number).
 */
object AuthFailureReason {
    const val NETWORK = "network"
    const val TIMEOUT = "timeout"
    const val BAD_RESPONSE = "bad_response"
    const val INVALID_PHONE_FORMAT = "invalid_phone_format"
    const val NAME_TOO_SHORT = "name_too_short"
    const val UNKNOWN = "unknown"

    private val CODE_PATTERN = Regex("[a-z0-9_]{1,64}")
    private const val MAX_CAUSE_DEPTH = 8

    fun from(error: Throwable): String {
        if (error is AuthException) {
            return error.code?.trim()?.lowercase(Locale.ROOT)?.takeIf { CODE_PATTERN.matches(it) } ?: UNKNOWN
        }
        for (cause in generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH)) {
            when {
                cause is InterruptedIOException || cause.javaClass.simpleName.contains("Timeout") -> return TIMEOUT
                cause is SerializationException ||
                    cause is ContentConvertException ||
                    cause is NoTransformationFoundException -> return BAD_RESPONSE
                cause is IOException -> return NETWORK
            }
        }
        return UNKNOWN
    }
}

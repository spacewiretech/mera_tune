package com.spacewire.meratune.data

import com.spacewire.meratune.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SendOtpRequest(val phone: String)

@Serializable
data class SendOtpResponse(
    val success: Boolean? = null,
    val phone: String? = null,
    @SerialName("expires_in_minutes") val expiresInMinutes: Int? = null,
    val error: String? = null,
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

    suspend fun sendOtp(phone: String): Result<String> = runCatching {
        val response = httpClient.post("$functionsBaseUrl/send-otp") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${BuildConfig.SUPABASE_KEY}")
            header("apikey", BuildConfig.SUPABASE_KEY)
            setBody(SendOtpRequest(phone))
        }.body<SendOtpResponse>()

        response.error?.let { throw AuthException(it) }
        response.phone ?: throw AuthException("Could not send OTP")
    }

    suspend fun verifyOtp(phone: String, otp: String): Result<VerifyOtpResponse> = runCatching {
        val response = httpClient.post("$functionsBaseUrl/verify-otp") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${BuildConfig.SUPABASE_KEY}")
            header("apikey", BuildConfig.SUPABASE_KEY)
            setBody(VerifyOtpRequest(phone, otp, appVersion = BuildConfig.VERSION_NAME))
        }.body<VerifyOtpResponse>()

        response.error?.let { throw AuthException(it) }
        if (response.verified != true) throw AuthException("Verification failed")
        response
    }

    suspend fun completeSignup(sessionToken: String, name: String): Result<CompleteSignupResult> = runCatching {
        val response = httpClient.post("$functionsBaseUrl/complete-signup") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${BuildConfig.SUPABASE_KEY}")
            header("apikey", BuildConfig.SUPABASE_KEY)
            setBody(CompleteSignupRequest(sessionToken, name, appVersion = BuildConfig.VERSION_NAME))
        }.body<CompleteSignupResponse>()

        response.error?.let { throw AuthException(it) }
        val user = response.user ?: throw AuthException("Could not save profile")
        CompleteSignupResult(
            user = user,
            apiToken = response.apiToken?.takeIf { it.isNotBlank() },
        )
    }
}

class AuthException(message: String) : Exception(message)

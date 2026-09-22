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
data class CreateSubscriptionRequest(val user_id: Long)

@Serializable
data class CreateSubscriptionResponse(
    @SerialName("subscription_id") val subscriptionId: String? = null,
    @SerialName("subscription_session_id") val subscriptionSessionId: String? = null,
    @SerialName("cf_subscription_id") val cfSubscriptionId: String? = null,
    val environment: String? = null,
    @SerialName("auth_amount") val authAmount: Double? = null,
    @SerialName("recurring_amount") val recurringAmount: Double? = null,
    val error: String? = null,
)

@Serializable
data class VerifySubscriptionRequest(
    @SerialName("user_id") val userId: Long,
    @SerialName("subscription_id") val subscriptionId: String,
)

@Serializable
data class VerifySubscriptionResponse(
    val active: Boolean? = null,
    val user: User? = null,
    val status: String? = null,
    val error: String? = null,
)

class SubscriptionRepository {

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
    }

    private val functionsBaseUrl = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1"

    suspend fun createSubscription(userId: Long): Result<CreateSubscriptionResponse> = runCatching {
        val response = httpClient.post("$functionsBaseUrl/create-subscription") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${BuildConfig.SUPABASE_KEY}")
            header("apikey", BuildConfig.SUPABASE_KEY)
            setBody(CreateSubscriptionRequest(userId))
        }.body<CreateSubscriptionResponse>()

        response.error?.let { throw AuthException(it) }
        if (response.subscriptionSessionId.isNullOrBlank() || response.subscriptionId.isNullOrBlank()) {
            throw AuthException("Could not start subscription")
        }
        response
    }

    suspend fun verifySubscription(userId: Long, subscriptionId: String): Result<VerifySubscriptionResponse> =
        runCatching {
            val response = httpClient.post("$functionsBaseUrl/verify-subscription") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer ${BuildConfig.SUPABASE_KEY}")
                header("apikey", BuildConfig.SUPABASE_KEY)
                setBody(VerifySubscriptionRequest(userId, subscriptionId))
            }.body<VerifySubscriptionResponse>()

            response.error?.let { throw AuthException(it) }
            response
        }
}

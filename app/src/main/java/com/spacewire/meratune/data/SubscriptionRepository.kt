package com.spacewire.meratune.data

import com.spacewire.meratune.BuildConfig
import com.spacewire.meratune.analytics.FailureReason
import com.spacewire.meratune.util.LoadErrorMapper
import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
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
import java.util.Locale

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

/**
 * A create/verify call that did not succeed. [message] is the server's user-facing `error` text
 * when it sent one: shown in the UI, never sent to analytics.
 */
class SubscriptionApiException(
    val httpStatus: Int,
    message: String?,
    val invalidResponse: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

class SubscriptionRepository {

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
    }

    private val functionsBaseUrl = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1"

    suspend fun createSubscription(userId: Long): Result<CreateSubscriptionResponse> = resultOf {
        val httpResponse = httpClient.post("$functionsBaseUrl/create-subscription") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${BuildConfig.SUPABASE_KEY}")
            header("apikey", BuildConfig.SUPABASE_KEY)
            setBody(CreateSubscriptionRequest(userId))
        }
        val status = httpResponse.status.value
        val response = httpResponse.decode<CreateSubscriptionResponse>()

        response.error?.let { throw SubscriptionApiException(status, it) }
        if (response.subscriptionSessionId.isNullOrBlank() || response.subscriptionId.isNullOrBlank()) {
            throw SubscriptionApiException(
                httpStatus = status,
                message = "Could not start subscription",
                invalidResponse = httpResponse.status.isSuccess(),
            )
        }
        response
    }

    suspend fun verifySubscription(userId: Long, subscriptionId: String): Result<VerifySubscriptionResponse> =
        resultOf {
            val httpResponse = httpClient.post("$functionsBaseUrl/verify-subscription") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer ${BuildConfig.SUPABASE_KEY}")
                header("apikey", BuildConfig.SUPABASE_KEY)
                setBody(VerifySubscriptionRequest(userId, subscriptionId))
            }
            val status = httpResponse.status.value
            val response = httpResponse.decode<VerifySubscriptionResponse>()

            response.error?.let { throw SubscriptionApiException(status, it) }
            if (!httpResponse.status.isSuccess()) throw SubscriptionApiException(status, null)
            response
        }

    /** Unreadable bodies (e.g. a gateway HTML error page) become [SubscriptionApiException]. */
    private suspend inline fun <reified T> HttpResponse.decode(): T = try {
        body<T>()
    } catch (error: JsonConvertException) {
        throw SubscriptionApiException(status.value, null, invalidResponse = true, cause = error)
    } catch (error: NoTransformationFoundException) {
        throw SubscriptionApiException(status.value, null, invalidResponse = true, cause = error)
    }

    /** [runCatching] that lets coroutine cancellation propagate, so a rotation is not a failure. */
    private inline fun <T> resultOf(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
}

/**
 * Bounded `subscription_failed` values per stage. Never built from server, Cashfree or exception
 * text: that is localized prose and can echo customer fields.
 */
object SubscriptionFailureReason {
    const val STAGE_PRECHECK = "precheck"
    const val STAGE_CREATE = "create"
    const val STAGE_CHECKOUT = "checkout"
    const val STAGE_VERIFY = "verify"

    const val PAYMENT_APP_NOT_INSTALLED = "payment_app_not_installed"
    const val NO_PAYMENT_APP_INSTALLED = "no_payment_app_installed"
    const val NOT_LOGGED_IN = "not_logged_in"

    const val MISSING_USER_ID = "missing_user_id"
    const val USER_NOT_FOUND = "user_not_found"
    const val ALREADY_ACTIVE = "already_active"
    const val GATEWAY_ERROR = "gateway_error"
    const val GATEWAY_NOT_CONFIGURED = "gateway_not_configured"
    const val SERVER_ERROR = "server_error"
    const val INVALID_RESPONSE = "invalid_response"

    const val PAYMENT_FAILED = "payment_failed"
    const val SDK_EXCEPTION = "sdk_exception"
    const val OTHER = "other"

    const val PENDING = "pending"
    const val MISSING_USER = "missing_user"
    const val MISSING_SUBSCRIPTION_ID = "missing_subscription_id"

    const val NETWORK = FailureReason.NETWORK
    const val TIMEOUT = FailureReason.TIMEOUT
    const val USER_CANCELLED = FailureReason.USER_CANCELLED
    const val UNKNOWN = FailureReason.UNKNOWN

    fun forCreate(error: Throwable): String = when {
        error is SubscriptionApiException && error.invalidResponse -> INVALID_RESPONSE
        error is SubscriptionApiException -> createReasonForStatus(error.httpStatus)
        else -> transportReason(error, decodeReason = INVALID_RESPONSE)
    }

    fun forVerify(error: Throwable): String =
        if (error is SubscriptionApiException) SERVER_ERROR else transportReason(error, decodeReason = SERVER_ERROR)

    /** [cfErrorCode] is the Cashfree SDK `CFErrorResponse.code`; a back-out is `action_cancelled`. */
    fun forCheckout(cfErrorCode: String?): String = when (cfErrorCode?.trim()?.lowercase(Locale.ROOT)) {
        "action_cancelled" -> USER_CANCELLED
        "payment_failed" -> PAYMENT_FAILED
        else -> OTHER
    }

    fun httpStatus(error: Throwable): Int? = (error as? SubscriptionApiException)?.httpStatus

    /** create-subscription answers each failure with a distinct status. */
    internal fun createReasonForStatus(status: Int): String = when (status) {
        400 -> MISSING_USER_ID
        404 -> USER_NOT_FOUND
        409 -> ALREADY_ACTIVE
        502 -> GATEWAY_ERROR
        503 -> GATEWAY_NOT_CONFIGURED
        else -> SERVER_ERROR
    }

    private fun transportReason(error: Throwable, decodeReason: String): String =
        when (LoadErrorMapper.reason(error)) {
            LoadErrorMapper.NETWORK -> NETWORK
            LoadErrorMapper.TIMEOUT -> TIMEOUT
            LoadErrorMapper.DECODE_ERROR -> decodeReason
            else -> UNKNOWN
        }
}

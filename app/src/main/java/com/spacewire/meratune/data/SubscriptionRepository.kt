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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.JsonConvertException
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * `create-subscription` body. [paidOfferSupported] is always `true` from this build: it can show
 * the monthly plan, so the server may create it for a returning user (older builds get 426).
 * [preview] `true` only asks which offer the user gets and creates nothing; omitted otherwise.
 */
@Serializable
data class CreateSubscriptionRequest(
    @SerialName("user_id") val userId: Long,
    @SerialName("paid_offer_supported") val paidOfferSupported: Boolean,
    val preview: Boolean? = null,
)

/**
 * create-subscription's `offer`: the ₹3 [TRIAL] for a user (and phone) that never authorised a
 * mandate, the monthly [PAID] plan (first month charged at the mandate) for everyone else.
 */
enum class SubscriptionOfferType(val wire: String) {
    TRIAL("trial"),
    PAID("paid"),
    ;

    companion object {
        fun fromWire(value: String?): SubscriptionOfferType? {
            val normalized = value?.trim()?.lowercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.wire == normalized }
        }
    }
}

/** Wire shape of both create-subscription answers (a created mandate, or a `preview`). */
@Serializable
internal data class CreateSubscriptionResponse(
    @SerialName("subscription_id") val subscriptionId: String? = null,
    @SerialName("subscription_session_id") val subscriptionSessionId: String? = null,
    @SerialName("cf_subscription_id") val cfSubscriptionId: String? = null,
    val environment: String? = null,
    @SerialName("auth_amount") val authAmount: Double? = null,
    @SerialName("recurring_amount") val recurringAmount: Double? = null,
    val offer: String? = null,
    @SerialName("first_charge_at") val firstChargeAt: String? = null,
    @SerialName("trial_eligible") val trialEligible: Boolean? = null,
    @SerialName("interval_months") val intervalMonths: Int? = null,
    @SerialName("trial_days") val trialDays: Int? = null,
    val error: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
)

/**
 * A mandate create-subscription made. [authAmount] is what the UPI app charges at authorisation
 * (₹3 for [SubscriptionOfferType.TRIAL], the first month for [SubscriptionOfferType.PAID]);
 * [firstChargeAt] is the first autopay (ISO, `+05:30`), when the server sent it.
 */
data class CreatedSubscription(
    val subscriptionId: String,
    val sessionId: String,
    val cfSubscriptionId: String?,
    val environment: String?,
    val offer: SubscriptionOfferType,
    val authAmount: Double,
    val recurringAmount: Double,
    val firstChargeAt: String?,
)

/** The offer create-subscription would make now (`preview`); nothing was created. */
data class SubscriptionOffer(
    val offer: SubscriptionOfferType,
    val trialEligible: Boolean,
    val authAmount: Double,
    val recurringAmount: Double,
    val intervalMonths: Int,
    val trialDays: Int,
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
 * when it sent one: shown in the UI, never sent to analytics. [errorCode] is the server's
 * `error_code` (create-subscription: [ERROR_ALREADY_ACTIVE], [ERROR_APP_UPDATE_REQUIRED]).
 */
class SubscriptionApiException(
    val httpStatus: Int,
    message: String?,
    val invalidResponse: Boolean = false,
    cause: Throwable? = null,
    val errorCode: String? = null,
) : Exception(message, cause) {

    /** The user already has an active subscription (409; older servers send no `error_code`). */
    val isAlreadyActive: Boolean
        get() = !invalidResponse && (httpStatus == 409 || errorCode == ERROR_ALREADY_ACTIVE)

    /** 426: the user gets an offer this build cannot show. Never for this build, which sends the flag. */
    val isAppUpdateRequired: Boolean
        get() = !invalidResponse && (httpStatus == 426 || errorCode == ERROR_APP_UPDATE_REQUIRED)

    companion object {
        const val ERROR_ALREADY_ACTIVE = "ALREADY_ACTIVE"
        const val ERROR_APP_UPDATE_REQUIRED = "APP_UPDATE_REQUIRED"
    }
}

/** Pure create-subscription response handling, so it runs in JVM unit tests (no `android.*`). */
internal object SubscriptionResponseParser {

    val json = Json { ignoreUnknownKeys = true }

    /**
     * A created mandate. Throws [SubscriptionApiException]: the server's `error` / `error_code`
     * (409 already active, 426 app update), an unreadable body, or a success without a session,
     * its amounts or a known `offer`. A body without `offer` comes from a server from before the
     * paid offer, which only creates trials.
     */
    fun parseCreate(status: Int, body: String): CreatedSubscription {
        val response = decode(status, body, CreateSubscriptionResponse.serializer())
        throwIfError(status, response)
        val subscriptionId = response.subscriptionId?.trim().orEmpty()
        val sessionId = response.subscriptionSessionId?.trim().orEmpty()
        if (subscriptionId.isEmpty() || sessionId.isEmpty()) throw invalid(status)
        val offer = if (response.offer == null) {
            SubscriptionOfferType.TRIAL
        } else {
            SubscriptionOfferType.fromWire(response.offer) ?: throw invalid(status)
        }
        return CreatedSubscription(
            subscriptionId = subscriptionId,
            sessionId = sessionId,
            cfSubscriptionId = response.cfSubscriptionId?.takeIf { it.isNotBlank() },
            environment = response.environment?.takeIf { it.isNotBlank() },
            offer = offer,
            authAmount = amount(status, response.authAmount),
            recurringAmount = amount(status, response.recurringAmount),
            firstChargeAt = response.firstChargeAt?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * A `preview` answer. Throws like [parseCreate]; a body without a known `offer` or its amounts
     * (for example from a server from before the preview, which created a mandate instead) is invalid.
     */
    fun parseOffer(status: Int, body: String): SubscriptionOffer {
        val response = decode(status, body, CreateSubscriptionResponse.serializer())
        throwIfError(status, response)
        val offer = SubscriptionOfferType.fromWire(response.offer) ?: throw invalid(status)
        return SubscriptionOffer(
            offer = offer,
            trialEligible = response.trialEligible ?: (offer == SubscriptionOfferType.TRIAL),
            authAmount = amount(status, response.authAmount),
            recurringAmount = amount(status, response.recurringAmount),
            intervalMonths = response.intervalMonths?.takeIf { it > 0 } ?: 1,
            trialDays = response.trialDays?.takeIf { it >= 0 } ?: 1,
        )
    }

    /** The server's `error` / `error_code`, or a non-2xx status without either. */
    private fun throwIfError(status: Int, response: CreateSubscriptionResponse) {
        val errorCode = response.errorCode?.trim()?.takeIf { it.isNotEmpty() }
        if (response.error != null || errorCode != null) {
            throw SubscriptionApiException(
                httpStatus = status,
                message = response.error?.takeIf { it.isNotBlank() },
                errorCode = errorCode,
            )
        }
        if (status !in 200..299) throw SubscriptionApiException(status, null)
    }

    /** A price the paywall can show: finite and above zero, else the answer is invalid. */
    private fun amount(status: Int, value: Double?): Double =
        value?.takeIf { it.isFinite() && it > 0.0 } ?: throw invalid(status)

    /** A 2xx answer the paywall cannot use; never opens the checkout. */
    private fun invalid(status: Int) = SubscriptionApiException(
        httpStatus = status,
        message = "Could not start subscription",
        invalidResponse = true,
    )

    /** Unreadable bodies (for example a gateway HTML page) become [SubscriptionApiException]. */
    private fun <T> decode(status: Int, body: String, serializer: KSerializer<T>): T = try {
        json.decodeFromString(serializer, body)
    } catch (error: IllegalArgumentException) {
        // kotlinx SerializationException is an IllegalArgumentException.
        throw SubscriptionApiException(status, null, invalidResponse = true, cause = error)
    }
}

class SubscriptionRepository {

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(SubscriptionResponseParser.json) }
    }

    private val functionsBaseUrl = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1"

    /** Creates the mandate the server decides on (trial or paid); fails like [SubscriptionResponseParser.parseCreate]. */
    suspend fun createSubscription(userId: Long): Result<CreatedSubscription> = resultOf {
        val httpResponse = postCreateSubscription(CreateSubscriptionRequest(userId, paidOfferSupported = true))
        SubscriptionResponseParser.parseCreate(httpResponse.status.value, httpResponse.bodyAsText())
    }

    /**
     * The offer [userId] would get now; creates nothing. A 409 ([SubscriptionApiException.isAlreadyActive])
     * means the user is already a member.
     */
    suspend fun fetchOffer(userId: Long): Result<SubscriptionOffer> = resultOf {
        val httpResponse = postCreateSubscription(
            CreateSubscriptionRequest(userId, paidOfferSupported = true, preview = true),
        )
        SubscriptionResponseParser.parseOffer(httpResponse.status.value, httpResponse.bodyAsText())
    }

    private suspend fun postCreateSubscription(request: CreateSubscriptionRequest): HttpResponse =
        httpClient.post("$functionsBaseUrl/create-subscription") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${BuildConfig.SUPABASE_KEY}")
            header("apikey", BuildConfig.SUPABASE_KEY)
            setBody(request)
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
    const val APP_UPDATE_REQUIRED = "app_update_required"

    /** Create answered with another offer or price than the paywall showed, so the checkout did not open. */
    const val OFFER_CHANGED = "offer_changed"
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
        error is SubscriptionApiException && error.isAlreadyActive -> ALREADY_ACTIVE
        error is SubscriptionApiException && error.isAppUpdateRequired -> APP_UPDATE_REQUIRED
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
        426 -> APP_UPDATE_REQUIRED
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

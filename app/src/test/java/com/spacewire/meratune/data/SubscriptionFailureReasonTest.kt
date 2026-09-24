package com.spacewire.meratune.data

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SubscriptionFailureReasonTest {

    private val bounded = Regex("[a-z0-9_]{1,64}")

    @Test
    fun createStatuses() {
        assertEquals(SubscriptionFailureReason.MISSING_USER_ID, SubscriptionFailureReason.createReasonForStatus(400))
        assertEquals(SubscriptionFailureReason.USER_NOT_FOUND, SubscriptionFailureReason.createReasonForStatus(404))
        assertEquals(SubscriptionFailureReason.ALREADY_ACTIVE, SubscriptionFailureReason.createReasonForStatus(409))
        assertEquals(SubscriptionFailureReason.GATEWAY_ERROR, SubscriptionFailureReason.createReasonForStatus(502))
        assertEquals(
            SubscriptionFailureReason.GATEWAY_NOT_CONFIGURED,
            SubscriptionFailureReason.createReasonForStatus(503),
        )
        assertEquals(SubscriptionFailureReason.SERVER_ERROR, SubscriptionFailureReason.createReasonForStatus(500))
        assertEquals(SubscriptionFailureReason.SERVER_ERROR, SubscriptionFailureReason.createReasonForStatus(401))
        assertEquals(SubscriptionFailureReason.SERVER_ERROR, SubscriptionFailureReason.createReasonForStatus(546))
    }

    @Test
    fun createApiErrors() {
        assertEquals(
            SubscriptionFailureReason.ALREADY_ACTIVE,
            SubscriptionFailureReason.forCreate(SubscriptionApiException(409, "Subscription already active")),
        )
        assertEquals(
            SubscriptionFailureReason.GATEWAY_ERROR,
            SubscriptionFailureReason.forCreate(SubscriptionApiException(502, "customer_phone is invalid")),
        )
        assertEquals(
            SubscriptionFailureReason.INVALID_RESPONSE,
            SubscriptionFailureReason.forCreate(
                SubscriptionApiException(200, "Could not start subscription", invalidResponse = true),
            ),
        )
        // A gateway HTML page is an unreadable body, not a Cashfree error.
        assertEquals(
            SubscriptionFailureReason.INVALID_RESPONSE,
            SubscriptionFailureReason.forCreate(SubscriptionApiException(502, null, invalidResponse = true)),
        )
    }

    @Test
    fun createTransportErrors() {
        assertEquals(
            SubscriptionFailureReason.NETWORK,
            SubscriptionFailureReason.forCreate(UnknownHostException("host")),
        )
        assertEquals(SubscriptionFailureReason.NETWORK, SubscriptionFailureReason.forCreate(IOException()))
        assertEquals(SubscriptionFailureReason.TIMEOUT, SubscriptionFailureReason.forCreate(SocketTimeoutException()))
        assertEquals(
            SubscriptionFailureReason.INVALID_RESPONSE,
            SubscriptionFailureReason.forCreate(SerializationException("bad json")),
        )
        assertEquals(SubscriptionFailureReason.UNKNOWN, SubscriptionFailureReason.forCreate(IllegalStateException()))
    }

    @Test
    fun verifyErrors() {
        assertEquals(
            SubscriptionFailureReason.SERVER_ERROR,
            SubscriptionFailureReason.forVerify(SubscriptionApiException(502, "Could not verify subscription status")),
        )
        assertEquals(
            SubscriptionFailureReason.SERVER_ERROR,
            SubscriptionFailureReason.forVerify(SubscriptionApiException(504, null, invalidResponse = true)),
        )
        assertEquals(SubscriptionFailureReason.NETWORK, SubscriptionFailureReason.forVerify(UnknownHostException()))
        assertEquals(SubscriptionFailureReason.TIMEOUT, SubscriptionFailureReason.forVerify(SocketTimeoutException()))
        assertEquals(SubscriptionFailureReason.UNKNOWN, SubscriptionFailureReason.forVerify(RuntimeException()))
    }

    @Test
    fun checkoutCodes() {
        assertEquals(
            SubscriptionFailureReason.USER_CANCELLED,
            SubscriptionFailureReason.forCheckout("action_cancelled"),
        )
        assertEquals(
            SubscriptionFailureReason.USER_CANCELLED,
            SubscriptionFailureReason.forCheckout(" ACTION_CANCELLED "),
        )
        assertEquals(SubscriptionFailureReason.PAYMENT_FAILED, SubscriptionFailureReason.forCheckout("payment_failed"))
        assertEquals(SubscriptionFailureReason.OTHER, SubscriptionFailureReason.forCheckout("no_internet_connection"))
        assertEquals(SubscriptionFailureReason.OTHER, SubscriptionFailureReason.forCheckout(null))
    }

    @Test
    fun httpStatusOnlyFromApiErrors() {
        assertEquals(409, SubscriptionFailureReason.httpStatus(SubscriptionApiException(409, "x")))
        assertNull(SubscriptionFailureReason.httpStatus(IOException("x")))
    }

    @Test
    fun neverEchoesMessageText() {
        val secret = "JSON input: {\"phone\":\"9876543210\",\"name\":\"Asha\"}"
        val errors = listOf(
            SubscriptionApiException(502, secret),
            SubscriptionApiException(200, secret, invalidResponse = true),
            IOException(secret),
            SerializationException(secret),
            IllegalStateException(secret),
        )
        for (error in errors) {
            val reasons = listOf(SubscriptionFailureReason.forCreate(error), SubscriptionFailureReason.forVerify(error))
            for (reason in reasons) {
                assertTrue("$reason is not bounded", bounded.matches(reason))
            }
        }
        assertTrue(bounded.matches(SubscriptionFailureReason.forCheckout(secret)))
    }
}

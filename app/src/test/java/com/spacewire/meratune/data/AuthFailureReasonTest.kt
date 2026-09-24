package com.spacewire.meratune.data

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.serialization.JsonConvertException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class AuthFailureReasonTest {

    @Test
    fun serverErrorCodeIsPassedThrough() {
        assertEquals("otp_invalid", AuthFailureReason.from(AuthException("Invalid verification code", "otp_invalid")))
        assertEquals("session_expired", AuthFailureReason.from(AuthException("Invalid or expired session", "session_expired")))
        assertEquals("otp_expired", AuthFailureReason.from(AuthException("OTP has expired", " OTP_Expired ")))
    }

    @Test
    fun missingOrMalformedCodeIsUnknown() {
        assertEquals(AuthFailureReason.UNKNOWN, AuthFailureReason.from(AuthException("Internal server error")))
        assertEquals(AuthFailureReason.UNKNOWN, AuthFailureReason.from(AuthException("x", "")))
        assertEquals(AuthFailureReason.UNKNOWN, AuthFailureReason.from(AuthException("x", "Number 9876543210 is invalid")))
        assertEquals(AuthFailureReason.UNKNOWN, AuthFailureReason.from(AuthException("x", "a".repeat(65))))
    }

    @Test
    fun clientCodes() {
        assertEquals(
            AuthFailureReason.BAD_RESPONSE,
            AuthFailureReason.from(AuthException("Could not send OTP", AuthFailureReason.BAD_RESPONSE)),
        )
    }

    @Test
    fun transportFailures() {
        assertEquals(AuthFailureReason.NETWORK, AuthFailureReason.from(UnknownHostException("host")))
        assertEquals(AuthFailureReason.NETWORK, AuthFailureReason.from(ConnectException("refused")))
        assertEquals(AuthFailureReason.NETWORK, AuthFailureReason.from(IOException("reset")))
        assertEquals(AuthFailureReason.NETWORK, AuthFailureReason.from(IllegalStateException(UnknownHostException())))
        assertEquals(AuthFailureReason.TIMEOUT, AuthFailureReason.from(SocketTimeoutException("Read timed out")))
        assertEquals(AuthFailureReason.TIMEOUT, AuthFailureReason.from(HttpRequestTimeoutException("https://x", 1_000L)))
    }

    @Test
    fun unreadableBodies() {
        assertEquals(AuthFailureReason.BAD_RESPONSE, AuthFailureReason.from(SerializationException("bad json")))
        assertEquals(AuthFailureReason.BAD_RESPONSE, AuthFailureReason.from(JsonConvertException("bad json")))
    }

    @Test
    fun otherErrorsAreUnknown() {
        assertEquals(AuthFailureReason.UNKNOWN, AuthFailureReason.from(IllegalStateException("boom")))
    }

    @Test
    fun neverReturnsExceptionText() {
        val errors = listOf(
            AuthException("Failed for 9876543210", null),
            AuthException("Failed", "Failed for 9876543210"),
            IllegalArgumentException("9876543210"),
            IOException("9876543210"),
        )
        errors.forEach { error ->
            val reason = AuthFailureReason.from(error)
            assertTrue(reason, Regex("[a-z0-9_]{1,64}").matches(reason))
            assertTrue(reason, !reason.contains("9876543210"))
        }
    }
}

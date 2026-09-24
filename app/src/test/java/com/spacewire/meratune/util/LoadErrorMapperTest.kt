package com.spacewire.meratune.util

import io.github.jan.supabase.exceptions.HttpRequestException
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class LoadErrorMapperTest {

    private val allowed = setOf(
        LoadErrorMapper.NETWORK,
        LoadErrorMapper.TIMEOUT,
        LoadErrorMapper.SERVER_ERROR,
        LoadErrorMapper.CLIENT_ERROR,
        LoadErrorMapper.DECODE_ERROR,
        LoadErrorMapper.UNKNOWN,
    )

    @Test
    fun timeouts() {
        assertEquals(LoadErrorMapper.TIMEOUT, LoadErrorMapper.reason(SocketTimeoutException("Read timed out")))
        assertEquals(LoadErrorMapper.TIMEOUT, LoadErrorMapper.reason(InterruptedIOException("timeout")))
        assertEquals(
            LoadErrorMapper.TIMEOUT,
            LoadErrorMapper.reason(HttpRequestException("Connect timeout has expired", HttpRequestBuilder())),
        )
        assertEquals(LoadErrorMapper.TIMEOUT, LoadErrorMapper.reason(RequestTimeoutException()))
    }

    @Test
    fun networkFailures() {
        assertEquals(LoadErrorMapper.NETWORK, LoadErrorMapper.reason(UnknownHostException("Unable to resolve host")))
        assertEquals(LoadErrorMapper.NETWORK, LoadErrorMapper.reason(ConnectException("Connection refused")))
        assertEquals(LoadErrorMapper.NETWORK, LoadErrorMapper.reason(SSLHandshakeException("handshake")))
        assertEquals(
            LoadErrorMapper.NETWORK,
            LoadErrorMapper.reason(HttpRequestException("Connection reset", HttpRequestBuilder())),
        )
        assertEquals(LoadErrorMapper.NETWORK, LoadErrorMapper.reason(IOException()))
    }

    @Test
    fun decodeErrors() {
        assertEquals(LoadErrorMapper.DECODE_ERROR, LoadErrorMapper.reason(SerializationException("Unexpected JSON")))
        assertEquals(LoadErrorMapper.DECODE_ERROR, LoadErrorMapper.reason(FakeJsonConvertException()))
    }

    @Test
    fun walksCauseChain() {
        val wrapped = IllegalStateException("outer", RuntimeException("mid", UnknownHostException("host")))
        assertEquals(LoadErrorMapper.NETWORK, LoadErrorMapper.reason(wrapped))
        assertEquals(
            LoadErrorMapper.TIMEOUT,
            LoadErrorMapper.reason(RuntimeException(SocketTimeoutException())),
        )
    }

    @Test
    fun unknownFailures() {
        assertEquals(LoadErrorMapper.UNKNOWN, LoadErrorMapper.reason(IllegalStateException("boom")))
        assertEquals(LoadErrorMapper.UNKNOWN, LoadErrorMapper.reason(RuntimeException()))
    }

    @Test
    fun statusCodes() {
        assertEquals(LoadErrorMapper.SERVER_ERROR, LoadErrorMapper.reasonForStatus(500))
        assertEquals(LoadErrorMapper.SERVER_ERROR, LoadErrorMapper.reasonForStatus(503))
        assertEquals(LoadErrorMapper.CLIENT_ERROR, LoadErrorMapper.reasonForStatus(400))
        assertEquals(LoadErrorMapper.CLIENT_ERROR, LoadErrorMapper.reasonForStatus(401))
        assertEquals(LoadErrorMapper.CLIENT_ERROR, LoadErrorMapper.reasonForStatus(404))
        assertEquals(LoadErrorMapper.UNKNOWN, LoadErrorMapper.reasonForStatus(302))
    }

    @Test
    fun noStatusWithoutRestException() {
        assertNull(LoadErrorMapper.httpStatus(IOException("x")))
    }

    @Test
    fun neverEchoesExceptionText() {
        val secret = "https://example.supabase.co/rest/v1/tunes apikey=secret"
        val errors = listOf(
            IOException(secret),
            IllegalStateException(secret),
            SerializationException(secret),
            HttpRequestException(secret, HttpRequestBuilder()),
        )
        for (error in errors) {
            val reason = LoadErrorMapper.reason(error)
            assertTrue("$reason is not bounded", reason in allowed)
        }
    }

    private class RequestTimeoutException : Exception()

    private class FakeJsonConvertException : Exception()
}

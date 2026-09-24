package com.spacewire.meratune.util

import io.github.jan.supabase.exceptions.RestException
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.io.InterruptedIOException

/** Maps a data-load failure to a user-facing message. Shared by Home and the song picker. */
object LoadErrorMapper {
    const val NETWORK = "network"
    const val TIMEOUT = "timeout"
    const val SERVER_ERROR = "server_error"
    const val CLIENT_ERROR = "client_error"
    const val DECODE_ERROR = "decode_error"
    const val UNKNOWN = "unknown"

    fun message(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("timeout", ignoreCase = true) ->
                "Could not reach the server. Check your internet connection and try again."
            message.contains("Unable to resolve host", ignoreCase = true) ||
                message.contains("UnknownHostException", ignoreCase = true) ->
                "No internet connection. Please check your network and try again."
            else -> error.message ?: "Failed to load data"
        }
    }

    /**
     * Bounded analytics `failure_reason`. Exception text is only inspected, never returned:
     * supabase-kt messages embed the request URL and headers.
     */
    fun reason(error: Throwable): String =
        httpStatus(error)?.let(::reasonForStatus) ?: reasonForThrowable(error)

    fun httpStatus(error: Throwable): Int? =
        causes(error).firstNotNullOfOrNull { (it as? RestException)?.statusCode }

    internal fun reasonForStatus(status: Int): String = when (status) {
        in 500..599 -> SERVER_ERROR
        in 400..499 -> CLIENT_ERROR
        else -> UNKNOWN
    }

    internal fun reasonForThrowable(error: Throwable): String {
        for (cause in causes(error)) {
            when {
                isTimeout(cause) -> return TIMEOUT
                isDecodeError(cause) -> return DECODE_ERROR
                // UnknownHost, Connect, SSL and supabase's HttpRequestException are all IOExceptions.
                cause is IOException -> return NETWORK
            }
        }
        return UNKNOWN
    }

    private fun isTimeout(error: Throwable): Boolean {
        if (error is InterruptedIOException) return true
        if (error.javaClass.simpleName.contains("Timeout")) return true
        // supabase-kt rethrows socket/connect timeouts as HttpRequestException, keeping only the text.
        val message = error.message.orEmpty()
        return error is IOException &&
            (message.contains("timeout", ignoreCase = true) || message.contains("timed out", ignoreCase = true))
    }

    private fun isDecodeError(error: Throwable): Boolean =
        error is SerializationException || error.javaClass.simpleName.endsWith("ConvertException")

    private fun causes(error: Throwable): Sequence<Throwable> =
        generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH)

    private const val MAX_CAUSE_DEPTH = 8
}

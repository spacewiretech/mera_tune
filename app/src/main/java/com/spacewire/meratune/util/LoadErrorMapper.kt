package com.spacewire.meratune.util

/** Maps a data-load failure to a user-facing message. Shared by Home and the song picker. */
object LoadErrorMapper {
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
}

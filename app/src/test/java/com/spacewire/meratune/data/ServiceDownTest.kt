package com.spacewire.meratune.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceDownTest {

    @Test
    fun `only failures of the ringtone service itself get the try-later popup`() {
        val down = GenerationErrorCode.entries.filter { GenerationErrorCode.isServiceDown(it) }
        assertEquals(
            setOf(
                GenerationErrorCode.TTS_RATE_LIMITED,
                GenerationErrorCode.TTS_FAILED,
                GenerationErrorCode.SERVICE_UNAVAILABLE,
                GenerationErrorCode.SERVICE_BUSY,
            ),
            down.toSet(),
        )
    }

    @Test
    fun `server codes map onto the popup codes`() {
        listOf("TTS_RATE_LIMITED", "tts_failed", " SERVICE_UNAVAILABLE ", "SERVICE_BUSY").forEach { raw ->
            assertEquals(raw, true, GenerationErrorCode.isServiceDown(GenerationErrorCode.from(raw)))
        }
        // The user's own connection or input keeps its own screen (retry, change language, ...).
        listOf("NETWORK", "TIMEOUT", "QUOTA_EXCEEDED", "UNSUPPORTED_LANGUAGE", "NAME_REJECTED", "UNAUTHORIZED").forEach { raw ->
            assertEquals(raw, false, GenerationErrorCode.isServiceDown(GenerationErrorCode.from(raw)))
        }
    }
}

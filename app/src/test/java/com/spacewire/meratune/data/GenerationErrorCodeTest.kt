package com.spacewire.meratune.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationErrorCodeTest {

    private val nonRetryable = setOf(
        GenerationErrorCode.INVALID_REQUEST,
        GenerationErrorCode.INVALID_NAME,
        GenerationErrorCode.NAME_REJECTED,
        GenerationErrorCode.UNAUTHORIZED,
        GenerationErrorCode.SUBSCRIPTION_REQUIRED,
        GenerationErrorCode.TUNE_NOT_FOUND,
        GenerationErrorCode.UNSUPPORTED_LANGUAGE,
        GenerationErrorCode.TUNE_NOT_PERSONALIZABLE,
        GenerationErrorCode.NAME_TOO_LONG_FOR_SONG,
        GenerationErrorCode.QUOTA_EXCEEDED,
    )

    @Test
    fun retryableTable() {
        for (code in GenerationErrorCode.entries) {
            val expected = code !in nonRetryable
            assertEquals("retryable for ${code.name}", expected, code.retryable)
        }
    }

    @Test
    fun transientCodesAreRetryable() {
        listOf(
            GenerationErrorCode.GENERATION_IN_PROGRESS,
            GenerationErrorCode.SERVICE_BUSY,
            GenerationErrorCode.TTS_FAILED,
            GenerationErrorCode.MIX_FAILED,
            GenerationErrorCode.UPLOAD_FAILED,
            GenerationErrorCode.TTS_RATE_LIMITED,
            GenerationErrorCode.SERVICE_UNAVAILABLE,
            GenerationErrorCode.INTERNAL,
            GenerationErrorCode.TIMEOUT,
            GenerationErrorCode.NETWORK,
            GenerationErrorCode.INVALID_RESPONSE,
            GenerationErrorCode.UNKNOWN,
        ).forEach { assertTrue(it.name, it.retryable) }
    }

    @Test
    fun fromNullOrBlankIsUnknown() {
        assertEquals(GenerationErrorCode.UNKNOWN, GenerationErrorCode.from(null))
        assertEquals(GenerationErrorCode.UNKNOWN, GenerationErrorCode.from(""))
        assertEquals(GenerationErrorCode.UNKNOWN, GenerationErrorCode.from("   "))
    }

    @Test
    fun fromUnknownStringIsUnknown() {
        assertEquals(GenerationErrorCode.UNKNOWN, GenerationErrorCode.from("SOMETHING_NEW"))
        assertEquals(GenerationErrorCode.UNKNOWN, GenerationErrorCode.from("429"))
    }

    @Test
    fun fromMatchesEveryServerCodeExactly() {
        for (code in GenerationErrorCode.entries) {
            assertEquals(code, GenerationErrorCode.from(code.name))
        }
    }

    @Test
    fun fromIsCaseAndWhitespaceInsensitive() {
        assertEquals(GenerationErrorCode.QUOTA_EXCEEDED, GenerationErrorCode.from("quota_exceeded"))
        assertEquals(GenerationErrorCode.TTS_FAILED, GenerationErrorCode.from("  TTS_FAILED \n"))
        assertEquals(GenerationErrorCode.NAME_TOO_LONG_FOR_SONG, GenerationErrorCode.from("name_too_long_for_song"))
    }

    @Test
    fun analyticsValueIsLowerCaseName() {
        for (code in GenerationErrorCode.entries) {
            assertEquals(code.name.lowercase(), code.analyticsValue)
        }
        assertEquals("generation_in_progress", GenerationErrorCode.GENERATION_IN_PROGRESS.analyticsValue)
    }

    @Test
    fun exceptionExposesCodeAndRetryability() {
        val quota = GenerationQuota(usedToday = 5, dailyLimit = 5)
        val error = RingtoneGenerationException(
            code = GenerationErrorCode.QUOTA_EXCEEDED,
            message = "limit reached",
            retryAfterSeconds = 3600,
            quota = quota,
            httpStatus = 429,
        )
        assertEquals(GenerationErrorCode.QUOTA_EXCEEDED, error.code)
        assertFalse(error.retryable)
        assertEquals(3600, error.retryAfterSeconds)
        assertEquals(quota, error.quota)
        assertEquals(429, error.httpStatus)
        assertEquals("limit reached", error.message)

        val transient = RingtoneGenerationException(GenerationErrorCode.TTS_RATE_LIMITED, "busy", retryAfterSeconds = 4)
        assertTrue(transient.retryable)
    }

    @Test
    fun generatedRingtoneToTuneCopiesTitleUrlAndGenerationId() {
        val base = Tune(
            id = "tune-1",
            name = "Ram Bhajan Hook",
            categoryId = "c1",
            gender = "Female",
            language = "Hindi",
            tuneUrl = "https://example.com/base.mp3",
            titleTemplate = "Jai Shri Ram {name} ji..",
        )
        val generated = GeneratedRingtone(
            generationId = "gen-1",
            renderId = "render-1",
            ringtoneUrl = "https://example.com/gen-1.mp3",
            title = "Jai Shri Ram Ayush ji..",
            tuneId = "tune-1",
            tuneName = "Ram Bhajan Hook",
            category = null,
            language = "Hindi",
            voice = "Female",
            durationMs = 28_400,
            cached = false,
            quota = null,
        )
        val tune = generated.toTune(base, fallbackTitle = "Ayush Ki Ringtone")
        assertEquals("Jai Shri Ram Ayush ji..", tune.name)
        assertEquals("https://example.com/gen-1.mp3", tune.tuneUrl)
        assertEquals("gen-1", tune.generationId)
        assertEquals("tune-1", tune.id)
        assertEquals("Jai Shri Ram {name} ji..", tune.titleTemplate)

        val untitled = generated.copy(title = null).toTune(base, fallbackTitle = "Ayush Ki Ringtone")
        assertEquals("Ayush Ki Ringtone", untitled.name)
        val blankTitle = generated.copy(title = "  ").toTune(base, fallbackTitle = "Ayush Ki Ringtone")
        assertEquals("Ayush Ki Ringtone", blankTitle.name)
    }
}

package com.spacewire.meratune.calltheme

import com.spacewire.meratune.analytics.FailureReason
import com.spacewire.meratune.analytics.SetFailureReason
import com.spacewire.meratune.analytics.SetFailureStage
import com.spacewire.meratune.util.RingtoneSetException
import com.spacewire.meratune.util.RingtoneSetStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SetFailureClassifierTest {

    private fun ringtoneError(step: RingtoneSetStep, cause: Throwable) =
        SetFailureClassifier.forRingtoneError(RingtoneSetException(step, cause))

    @Test
    fun downloadFailures() {
        assertEquals(
            SetFailure(SetFailureStage.DOWNLOAD, FailureReason.TIMEOUT, "SocketTimeoutException"),
            ringtoneError(RingtoneSetStep.DOWNLOAD, SocketTimeoutException("Read timed out")),
        )
        assertEquals(
            SetFailure(SetFailureStage.DOWNLOAD, FailureReason.NETWORK, "UnknownHostException"),
            ringtoneError(RingtoneSetStep.DOWNLOAD, UnknownHostException("cdn.example.com")),
        )
        assertEquals(
            SetFailure(SetFailureStage.DOWNLOAD, FailureReason.NETWORK, "FileNotFoundException"),
            ringtoneError(RingtoneSetStep.DOWNLOAD, FileNotFoundException("https://cdn.example.com/a.mp3")),
        )
        assertEquals(
            SetFailure(SetFailureStage.DOWNLOAD, FailureReason.UNKNOWN, "IllegalArgumentException"),
            ringtoneError(RingtoneSetStep.DOWNLOAD, IllegalArgumentException("Empty tune URL")),
        )
    }

    @Test
    fun saveFailures() {
        assertEquals(
            SetFailure(SetFailureStage.SAVE, SetFailureReason.MEDIA_STORE_ERROR, "IOException"),
            ringtoneError(RingtoneSetStep.SAVE, IOException("Could not create ringtone file")),
        )
        assertEquals(
            SetFailure(SetFailureStage.SAVE, SetFailureReason.SECURITY_EXCEPTION, "SecurityException"),
            ringtoneError(RingtoneSetStep.SAVE, SecurityException("no access")),
        )
    }

    @Test
    fun setDefaultFailures() {
        assertEquals(
            SetFailure(SetFailureStage.SET_DEFAULT, SetFailureReason.SECURITY_EXCEPTION, "SecurityException"),
            ringtoneError(RingtoneSetStep.SET_DEFAULT, SecurityException("WRITE_SETTINGS")),
        )
        assertEquals(
            SetFailure(SetFailureStage.SET_DEFAULT, FailureReason.UNKNOWN, "IllegalStateException"),
            ringtoneError(RingtoneSetStep.SET_DEFAULT, IllegalStateException("boom")),
        )
    }

    @Test
    fun unwrappedErrorFallsBackToLastStep() {
        assertEquals(
            SetFailure(SetFailureStage.SET_DEFAULT, FailureReason.UNKNOWN, "IllegalStateException"),
            SetFailureClassifier.forRingtoneError(IllegalStateException("boom")),
        )
    }

    @Test
    fun otherStages() {
        assertEquals(
            SetFailure(SetFailureStage.THEME_SAVE, FailureReason.UNKNOWN, "IllegalArgumentException"),
            SetFailureClassifier.forError(SetFailureStage.THEME_SAVE, IllegalArgumentException("no number")),
        )
        assertEquals(
            SetFailure(SetFailureStage.PHOTO_SHEET, SetFailureReason.SECURITY_EXCEPTION, "SecurityException"),
            SetFailureClassifier.forError(SetFailureStage.PHOTO_SHEET, SecurityException("uri grant")),
        )
        assertEquals(
            SetFailure(SetFailureStage.PHOTO_SHEET, FailureReason.UNKNOWN, "IOException"),
            SetFailureClassifier.forError(SetFailureStage.PHOTO_SHEET, IOException("Could not read selected image")),
        )
    }

    @Test
    fun anonymousExceptionHasNoErrorType() {
        val failure = SetFailureClassifier.forError(SetFailureStage.THEME_SAVE, object : RuntimeException("x") {})
        assertNull(failure.errorType)
    }

    @Test
    fun neverEchoesExceptionText() {
        val secret = "https://example.supabase.co/storage/v1/Ayush 9876543210"
        val errors = listOf(
            RingtoneSetException(RingtoneSetStep.DOWNLOAD, IOException(secret)),
            RingtoneSetException(RingtoneSetStep.SAVE, IllegalStateException(secret)),
            RingtoneSetException(RingtoneSetStep.SET_DEFAULT, SecurityException(secret)),
            IllegalArgumentException(secret),
        )
        val bounded = Regex("[a-z0-9_]{1,64}")
        for (error in errors) {
            val failure = SetFailureClassifier.forRingtoneError(error)
            assertTrue(failure.stage, bounded.matches(failure.stage))
            assertTrue(failure.failureReason, bounded.matches(failure.failureReason))
            assertTrue(failure.errorType.orEmpty(), failure.errorType.orEmpty().matches(Regex("[A-Za-z0-9_]*")))
        }
    }
}

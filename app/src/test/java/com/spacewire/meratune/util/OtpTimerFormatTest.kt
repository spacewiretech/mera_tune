package com.spacewire.meratune.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class OtpTimerFormatTest {

    @Test
    fun formatsMinutesAndSeconds() {
        assertEquals("00:30", OtpTimerFormat.format(30))
        assertEquals("00:28", OtpTimerFormat.format(28))
        assertEquals("00:05", OtpTimerFormat.format(5))
        assertEquals("01:15", OtpTimerFormat.format(75))
    }

    @Test
    fun zeroAndNegativeShowZero() {
        assertEquals("00:00", OtpTimerFormat.format(0))
        assertEquals("00:00", OtpTimerFormat.format(-3))
    }

    @Test
    fun alwaysUsesLatinDigits() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("hi-IN-u-nu-deva"))
            assertEquals("00:28", OtpTimerFormat.format(28))
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("01:15", OtpTimerFormat.format(75))
        } finally {
            Locale.setDefault(previous)
        }
    }
}

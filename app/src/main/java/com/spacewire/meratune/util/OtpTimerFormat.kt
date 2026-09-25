package com.spacewire.meratune.util

import java.util.Locale

/** The OTP resend countdown label. */
object OtpTimerFormat {

    /** `mm:ss` for [seconds] (negative counts as 0), always with Latin digits as designed. */
    fun format(seconds: Int): String {
        val clamped = seconds.coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d", clamped / 60, clamped % 60)
    }
}

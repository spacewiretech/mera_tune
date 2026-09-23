package com.spacewire.meratune.util

import android.view.View
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat

/** Thin wrapper over the compat haptics API; androidx.core picks the right constant per API level. */
object Haptics {
    /** Light tick for choosing an option (song card, chip). */
    fun select(view: View) {
        ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.CONTEXT_CLICK)
    }

    /** Stronger confirmation for a completed action (ringtone ready / set). */
    fun confirm(view: View) {
        ViewCompat.performHapticFeedback(view, HapticFeedbackConstantsCompat.CONFIRM)
    }
}

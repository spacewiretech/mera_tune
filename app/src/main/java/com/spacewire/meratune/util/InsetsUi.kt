package com.spacewire.meratune.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

object InsetsUi {

    /**
     * Pads [view] (a screen root under `enableLightEdgeToEdge`) by the system bars and display
     * cutout, with the bottom raised to the keyboard while it is open, so content laid out inside
     * the padding stays above both. Relies on `adjustResize` for IME insets on API 26-29.
     *
     * [onImeVisibilityChanged] receives the keyboard's visibility on the first insets pass and then
     * whenever it changes, posted to the next frame (outside the insets dispatch). Insets are
     * returned unconsumed.
     */
    fun padForSystemBarsAndIme(view: View, onImeVisibilityChanged: ((Boolean) -> Unit)? = null) {
        var reportedImeVisible: Boolean? = null
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            target.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))

            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (onImeVisibilityChanged != null && imeVisible != reportedImeVisible) {
                reportedImeVisible = imeVisible
                target.post { onImeVisibilityChanged(imeVisible) }
            }
            insets
        }
    }
}

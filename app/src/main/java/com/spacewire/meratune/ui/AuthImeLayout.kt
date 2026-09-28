package com.spacewire.meratune.ui

import kotlin.math.max

/**
 * Keyboard layout maths for the phone, OTP and name screens ([AuthUi.bindImeBehaviour]). Pure, so
 * it is unit-tested.
 *
 * While the keyboard is open the carousel is invisible but keeps a slot sized like when it shows,
 * so the form stays anchored right above the keyboard instead of jumping to the top of the screen.
 */
object AuthImeLayout {

    /**
     * The carousel height that makes the content fill [viewportHeight] exactly, given the
     * [restHeight] of everything else in the scroll content. Clamped to [minHeight]..[maxHeight];
     * while the keyboard is open the minimum is 0, so the invisible slot never pushes the form
     * under the keyboard.
     */
    fun carouselHeight(
        viewportHeight: Int,
        restHeight: Int,
        minHeight: Int,
        maxHeight: Int,
        imeVisible: Boolean,
    ): Int {
        val min = if (imeVisible) 0 else minHeight
        return (viewportHeight - restHeight).coerceIn(min, max(min, maxHeight))
    }

    /**
     * The scroll offset that shows the bottom of the reveal view (the CTA) at [revealBottom] in
     * content coordinates: scrolls down only as far as needed, keeps a larger [currentScrollY]
     * (the focused field is already in view), and clamps to the scroll range, which also pulls a
     * stale offset left from taller content back into range.
     */
    fun revealScrollY(currentScrollY: Int, revealBottom: Int, viewportHeight: Int, contentHeight: Int): Int {
        val maxScroll = max(0, contentHeight - viewportHeight)
        return max(currentScrollY, revealBottom - viewportHeight).coerceIn(0, maxScroll)
    }
}

package com.spacewire.meratune.ui

import android.graphics.Rect
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.view.doOnPreDraw
import com.spacewire.meratune.R
import com.spacewire.meratune.util.GradientTextHelper
import com.spacewire.meratune.util.InsetsUi

/** Shared pieces of the phone, OTP and name screens (carousel + logo + headline + form + terms). */
object AuthUi {

    /** Headline highlight: pink, orange, then magenta over the last few letters (as designed). */
    @ColorRes
    val HEADLINE_COLORS = intArrayOf(R.color.gradient_pink, R.color.gradient_orange, R.color.gradient_magenta)
    val HEADLINE_POSITIONS = floatArrayOf(0f, 0.7f, 1f)

    /** Small accents such as the OTP "Resend OTP" link. */
    @ColorRes
    val ACCENT_COLORS = intArrayOf(R.color.gradient_pink, R.color.gradient_orange)

    /**
     * Sets `getString(format, getString(highlight))` with the gradient on the highlight. A
     * translation that drops the highlight shows plain text instead.
     */
    fun bindHeadline(
        textView: TextView,
        @StringRes format: Int,
        @StringRes highlight: Int,
        @ColorRes colors: IntArray = HEADLINE_COLORS,
        positions: FloatArray? = HEADLINE_POSITIONS,
    ) {
        val context = textView.context
        val highlightText = context.getString(highlight)
        GradientTextHelper.setTextWithGradientHighlight(
            textView,
            context.getString(format, highlightText),
            highlightText,
            colors,
            GradientTextHelper.Direction.HORIZONTAL,
            positions,
        )
    }

    /**
     * Pads [root] for the system bars and keyboard, sizes [carousel] to the room [scroll] leaves,
     * and hides the carousel while the keyboard is open. When the keyboard opens, [reveal] (the CTA
     * container) is scrolled into view once the new layout is ready; the scroll view already keeps
     * the focused field visible on resize.
     */
    fun bindImeBehaviour(root: View, scroll: View, carousel: OnboardingCarouselView, reveal: View) {
        carousel.fitHeightTo(scroll)
        InsetsUi.padForSystemBarsAndIme(root) { imeVisible ->
            carousel.setImeVisible(imeVisible)
            if (imeVisible) {
                reveal.doOnPreDraw { view ->
                    view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
                }
            }
        }
    }
}

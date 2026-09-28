package com.spacewire.meratune.ui

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.widget.NestedScrollView
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
     * and hides the carousel while the keyboard is open (its invisible slot keeps the form right
     * above the keyboard). When the keyboard opens, [reveal] (the CTA container) is scrolled into
     * view once the carousel has refitted; on most screens everything fits and nothing scrolls.
     */
    fun bindImeBehaviour(root: View, scroll: NestedScrollView, carousel: OnboardingCarouselView, reveal: View) {
        carousel.fitHeightTo(scroll)
        InsetsUi.padForSystemBarsAndIme(root) { imeVisible ->
            carousel.setImeVisible(imeVisible)
            if (imeVisible) revealWhenLaidOut(scroll, reveal)
        }
    }

    /**
     * Scrolls [scroll] just enough to show the bottom of [reveal], on the first pre-draw with no
     * layout pending: the carousel refits over a pass or two after the keyboard opens, and a
     * scroll measured against the old, taller content stayed out of range (the form then sat far
     * above the keyboard). Only the scroll view scrolls, never the window.
     */
    private fun revealWhenLaidOut(scroll: NestedScrollView, reveal: View) {
        scroll.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (scroll.isLayoutRequested) return true
                scroll.viewTreeObserver.removeOnPreDrawListener(this)
                val content = scroll.getChildAt(0) ?: return true
                if (!reveal.isAttachedToWindow) return true
                val rect = Rect(0, 0, reveal.width, reveal.height)
                scroll.offsetDescendantRectToMyCoords(reveal, rect)
                val target = AuthImeLayout.revealScrollY(
                    currentScrollY = scroll.scrollY,
                    revealBottom = rect.bottom,
                    viewportHeight = scroll.height - scroll.paddingTop - scroll.paddingBottom,
                    contentHeight = content.height,
                )
                // Also replaces the scroll view's own keep-the-focus-visible animation.
                scroll.smoothScrollTo(0, target)
                return true
            }
        })
    }
}

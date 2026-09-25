package com.spacewire.meratune.util

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Shader
import android.text.Editable
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import com.spacewire.meratune.R
import kotlin.math.max
import kotlin.math.min

/**
 * Gradient text, either across a whole TextView ([applyGradient] and friends) or on a range
 * ([setTextWithGradientHighlight], or [gradientSpan] + [bindSpans] for a caller-built Spannable).
 *
 * Shaders are computed from the TextView's `Layout`, so the gradient covers exactly the drawn text
 * for any gravity, line count or wrap. One binder per TextView (tag [R.id.tag_gradient_binder])
 * recomputes them when the view is laid out and when its text changes. It only mutates existing
 * span objects and the paint shader, never adds or removes spans, so it cannot cause a relayout
 * loop. Note that a TextView with a TextWatcher (the binder is one) stores later `setText` values
 * as an Editable; spans and rendering are unaffected.
 */
object GradientTextHelper {

    enum class Direction {
        /** colours[0] on the left. */
        HORIZONTAL,

        /** colours[0] at the top. */
        VERTICAL,

        /** colours[0] bottom-left, last colour top-right. */
        DIAGONAL_UP,

        /** colours[0] top-left, last colour bottom-right. */
        DIAGONAL_DOWN,
    }

    /** Left-to-right gradient over the whole text. */
    fun applyHorizontalGradient(
        textView: TextView,
        @ColorRes startColorRes: Int,
        @ColorRes endColorRes: Int,
    ) {
        applyGradient(textView, intArrayOf(startColorRes, endColorRes), Direction.HORIZONTAL)
    }

    /** Bottom-left ([startColorRes]) to top-right ([endColorRes]) gradient over the whole text. */
    fun applyDiagonalGradient(
        textView: TextView,
        @ColorRes startColorRes: Int,
        @ColorRes endColorRes: Int,
    ) {
        applyGradient(textView, intArrayOf(startColorRes, endColorRes), Direction.DIAGONAL_UP)
    }

    /**
     * Gradient over the whole text, kept correct across text and size changes until
     * [clearGradient]. Invalid [positions] fall back to evenly spaced stops.
     */
    fun applyGradient(
        textView: TextView,
        @ColorRes colorRes: IntArray,
        direction: Direction = Direction.HORIZONTAL,
        positions: FloatArray? = null,
    ) {
        val colors = resolveColors(textView.context, colorRes)
        val binder = binderFor(textView)
        binder.wholeText = WholeTextGradient(colors, GradientGeometry.validPositions(positions, colors.size), direction)
        binder.refresh()
    }

    /** Removes a whole-text gradient set by [applyGradient]. Range spans are left alone. */
    fun clearGradient(textView: TextView) {
        val binder = textView.getTag(R.id.tag_gradient_binder) as? GradientBinder ?: return
        if (binder.wholeText == null) return
        binder.wholeText = null
        textView.paint.shader = null
        textView.invalidate()
    }

    /**
     * Sets [fullText] with a gradient (plus [extraSpans], for example a `RelativeSizeSpan` or
     * [TypefaceCompatSpan]) on the first occurrence of [highlight]. When [highlight] is missing,
     * for example after a bad translation, sets [fullText] unstyled and returns false.
     */
    fun setTextWithGradientHighlight(
        textView: TextView,
        fullText: CharSequence,
        highlight: CharSequence,
        @ColorRes colorRes: IntArray,
        direction: Direction = Direction.HORIZONTAL,
        positions: FloatArray? = null,
        extraSpans: List<Any> = emptyList(),
    ): Boolean {
        val range = GradientGeometry.highlightRange(fullText, highlight)
        if (range == null) {
            textView.text = fullText
            return false
        }
        val start = range.first
        val end = range.last + 1
        val spannable = SpannableString(fullText)
        extraSpans.forEach { span -> spannable.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        spannable.setSpan(
            gradientSpan(textView.context, colorRes, direction, positions),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        textView.text = spannable
        bindSpans(textView)
        return true
    }

    /** Two-colour form of [setTextWithGradientHighlight]. */
    fun setTextWithGradientHighlight(
        textView: TextView,
        fullText: CharSequence,
        highlight: CharSequence,
        @ColorRes startColorRes: Int,
        @ColorRes endColorRes: Int,
        direction: Direction = Direction.HORIZONTAL,
    ): Boolean = setTextWithGradientHighlight(
        textView,
        fullText,
        highlight,
        intArrayOf(startColorRes, endColorRes),
        direction,
    )

    /** A [GradientSpan] for a caller-built Spannable; call [bindSpans] after setting the text. */
    fun gradientSpan(
        context: Context,
        @ColorRes colorRes: IntArray,
        direction: Direction = Direction.HORIZONTAL,
        positions: FloatArray? = null,
    ): GradientSpan {
        val colors = resolveColors(context, colorRes)
        return GradientSpan(colors, GradientGeometry.validPositions(positions, colors.size), direction)
    }

    /** Keeps the shaders of every [GradientSpan] in [textView]'s text current. Idempotent. */
    fun bindSpans(textView: TextView) {
        binderFor(textView).refresh()
    }

    private fun resolveColors(context: Context, @ColorRes colorRes: IntArray): IntArray {
        require(colorRes.size >= 2) { "A gradient needs at least 2 colours" }
        return IntArray(colorRes.size) { index -> ContextCompat.getColor(context, colorRes[index]) }
    }

    private fun binderFor(textView: TextView): GradientBinder =
        textView.getTag(R.id.tag_gradient_binder) as? GradientBinder
            ?: GradientBinder(textView).also { binder ->
                textView.setTag(R.id.tag_gradient_binder, binder)
                textView.addOnLayoutChangeListener(binder)
                textView.addTextChangedListener(binder)
                textView.addOnAttachStateChangeListener(binder)
            }

    /** [colors] are resolved colour ints. */
    private class WholeTextGradient(
        val colors: IntArray,
        val positions: FloatArray?,
        val direction: Direction,
    )

    private class GradientBinder(private val textView: TextView) :
        View.OnLayoutChangeListener,
        View.OnAttachStateChangeListener,
        TextWatcher {

        var wholeText: WholeTextGradient? = null
        private var refreshScheduled = false

        override fun onLayoutChange(
            view: View,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            oldLeft: Int,
            oldTop: Int,
            oldRight: Int,
            oldBottom: Int,
        ) = refresh()

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

        // The new Layout may only exist after the next measure, so wait for the next frame.
        override fun afterTextChanged(s: Editable?) = scheduleRefresh()

        // Catches up on a text change whose pending pre-draw refresh was dropped by a detach.
        override fun onViewAttachedToWindow(view: View) = scheduleRefresh()

        // doOnPreDraw drops its action (without running it) when the view detaches, so clear the
        // flag here or no later text change would schedule a refresh again.
        override fun onViewDetachedFromWindow(view: View) {
            refreshScheduled = false
        }

        private fun scheduleRefresh() {
            if (refreshScheduled) return
            refreshScheduled = true
            textView.doOnPreDraw {
                refreshScheduled = false
                refresh()
            }
        }

        /** Recomputes every shader from the current Layout; a no-op until the text is laid out. */
        fun refresh() {
            val layout = textView.layout ?: return
            val text = textView.text
            var changed = false

            wholeText?.let { gradient ->
                textView.paint.shader = rangeBounds(layout, 0, layout.text.length)
                    ?.let { bounds -> shader(gradient.colors, gradient.positions, gradient.direction, bounds) }
                changed = true
            }

            if (text is Spanned) {
                for (span in text.getSpans(0, text.length, GradientSpan::class.java)) {
                    val start = text.getSpanStart(span)
                    val end = text.getSpanEnd(span)
                    span.shader = rangeBounds(layout, start, end)
                        ?.let { bounds -> shader(span.colors, span.positions, span.direction, bounds) }
                    changed = true
                }
            }

            if (changed) textView.invalidate()
        }

        private fun shader(
            colors: IntArray,
            positions: FloatArray?,
            direction: Direction,
            bounds: GradientGeometry.Bounds,
        ): Shader? {
            val points = GradientGeometry.endpoints(direction, bounds)
            if (points.isDegenerate) return null
            return LinearGradient(points.x0, points.y0, points.x1, points.y1, colors, positions, Shader.TileMode.CLAMP)
        }

        /**
         * Bounding box of the drawn characters in [start, end), in layout coordinates: per line,
         * from the range's first character to its last visible one (the line's right edge when the
         * range runs into a wrap), and vertically from the first line's ascent to the last line's
         * descent. Null when nothing visible is in range.
         */
        private fun rangeBounds(layout: Layout, start: Int, end: Int): GradientGeometry.Bounds? {
            if (start < 0 || end <= start) return null
            val firstLine = layout.getLineForOffset(start)
            val lastLine = layout.getLineForOffset(end - 1)
            var left = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var topLine = -1
            var bottomLine = -1

            for (line in firstLine..lastLine) {
                val lineEnd = layout.getLineEnd(line)
                val segmentStart = max(start, layout.getLineStart(line))
                val segmentEnd = min(end, layout.getLineVisibleEnd(line))
                if (segmentEnd <= segmentStart) continue

                val x0 = layout.getPrimaryHorizontal(segmentStart)
                // At a wrap, the line-end offset belongs to the next line, so use this line's edge.
                val x1 = if (segmentEnd == lineEnd && line < layout.lineCount - 1) {
                    layout.getLineRight(line)
                } else {
                    layout.getPrimaryHorizontal(segmentEnd)
                }
                left = min(left, min(x0, x1))
                right = max(right, max(x0, x1))
                if (topLine < 0) topLine = line
                bottomLine = line
            }

            if (topLine < 0) return null
            val top = (layout.getLineBaseline(topLine) + layout.getLineAscent(topLine)).toFloat()
            val bottom = (layout.getLineBaseline(bottomLine) + layout.getLineDescent(bottomLine)).toFloat()
            return GradientGeometry.Bounds(left, top, right, bottom)
        }
    }
}

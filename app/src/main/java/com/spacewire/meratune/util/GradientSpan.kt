package com.spacewire.meratune.util

import android.graphics.Color
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.MetricAffectingSpan
import android.text.style.UpdateAppearance

/**
 * Gradient fill for a text range. Create it with [GradientTextHelper.gradientSpan] and bind the
 * TextView with [GradientTextHelper.bindSpans] (or use `setTextWithGradientHighlight`): the
 * binder computes [shader] from the range's position in the laid-out text and keeps it current.
 * Until then the range draws in the first colour.
 */
class GradientSpan internal constructor(
    /** Resolved colour ints (not resources). */
    val colors: IntArray,
    val positions: FloatArray?,
    val direction: GradientTextHelper.Direction,
) : CharacterStyle(), UpdateAppearance {

    init {
        require(colors.size >= 2) { "A gradient needs at least 2 colours" }
    }

    internal var shader: Shader? = null

    override fun updateDrawState(tp: TextPaint) {
        val current = shader
        if (current != null) {
            // Opaque base: the paint colour's alpha modulates the shader.
            tp.color = Color.BLACK
            tp.shader = current
        } else {
            tp.color = colors[0]
        }
    }
}

/**
 * Sets a typeface on a text range. API 26-safe replacement for `TypefaceSpan(Typeface)`, which
 * needs API 28. Use with [com.spacewire.meratune.ui.AppFonts] to pick a Manrope weight.
 */
class TypefaceCompatSpan(private val typeface: Typeface) : MetricAffectingSpan() {

    override fun updateDrawState(tp: TextPaint) {
        tp.typeface = typeface
    }

    override fun updateMeasureState(textPaint: TextPaint) {
        textPaint.typeface = typeface
    }
}

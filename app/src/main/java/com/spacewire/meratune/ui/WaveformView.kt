package com.spacewire.meratune.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.spacewire.meratune.R
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * Full-width animated waveform (Processing): thin bars across the whole view, a repeating tall /
 * short height pattern modulated by a sine, and a vertical gradient (pink tips, orange middle).
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val barWidthPx = BAR_WIDTH_DP * density
    private val barGapPx = BAR_GAP_DP * density

    private var barCount = 0
    private var phases = FloatArray(0)
    private var startX = 0f
    private var animationPhase = 0f

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = barWidthPx
    }

    private var animator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        barCount = floor((w + barGapPx) / (barWidthPx + barGapPx)).toInt().coerceAtLeast(0)
        phases = FloatArray(barCount) { Random.nextFloat() * TWO_PI }
        val totalWidth = barCount * barWidthPx + (barCount - 1).coerceAtLeast(0) * barGapPx
        startX = (w - totalWidth) / 2f + barWidthPx / 2f

        val pink = ContextCompat.getColor(context, R.color.gradient_pink)
        val orange = ContextCompat.getColor(context, R.color.gradient_orange)
        barPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            h.toFloat(),
            intArrayOf(pink, orange, orange, pink),
            floatArrayOf(0f, 0.3f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, TWO_PI).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                animationPhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (barCount == 0) return
        val centerY = height / 2f
        val maxBarHeight = height.toFloat()
        var x = startX
        for (index in 0 until barCount) {
            val wave = (sin(animationPhase + phases[index]) + 1f) / 2f
            val pattern = HEIGHT_PATTERN[index % HEIGHT_PATTERN.size]
            val barHeight = maxBarHeight * pattern * (MIN_SCALE + wave * (1f - MIN_SCALE))
            canvas.drawLine(x, centerY - barHeight / 2f, x, centerY + barHeight / 2f, barPaint)
            x += barWidthPx + barGapPx
        }
    }

    private companion object {
        const val BAR_WIDTH_DP = 1.5f
        const val BAR_GAP_DP = 3f
        const val TWO_PI = 6.2832f
        const val MIN_SCALE = 0.55f
        val HEIGHT_PATTERN = floatArrayOf(1f, 0.45f, 0.7f, 0.3f, 0.85f, 0.4f)
    }
}

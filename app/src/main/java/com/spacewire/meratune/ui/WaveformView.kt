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
import kotlin.math.sin
import kotlin.random.Random

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val barCount = 48
    private val barWidthPx = 4f * resources.displayMetrics.density
    private val barGapPx = 3f * resources.displayMetrics.density
    private val phases = FloatArray(barCount) { Random.nextFloat() * 6.28f }
    private var animationPhase = 0f

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = barWidthPx
    }

    private var animator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, 6.28f).apply {
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

        val totalWidth = barCount * barWidthPx + (barCount - 1) * barGapPx
        var startX = (width - totalWidth) / 2f + barWidthPx / 2f
        val centerY = height / 2f
        val maxBarHeight = height * 0.85f

        barPaint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            0f,
            intArrayOf(
                ContextCompat.getColor(context, R.color.gradient_pink),
                ContextCompat.getColor(context, R.color.gradient_orange),
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )

        repeat(barCount) { index ->
            val wave = sin(animationPhase + phases[index]).toFloat()
            val normalized = (wave + 1f) / 2f
            val barHeight = (0.25f + normalized * 0.75f) * maxBarHeight
            canvas.drawLine(
                startX,
                centerY - barHeight / 2f,
                startX,
                centerY + barHeight / 2f,
                barPaint,
            )
            startX += barWidthPx + barGapPx
        }
    }
}

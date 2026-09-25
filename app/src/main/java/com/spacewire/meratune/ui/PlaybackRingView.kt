package com.spacewire.meratune.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SweepGradient
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.spacewire.meratune.R
import kotlin.math.max
import kotlin.math.min

class PlaybackRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val sweep: Boolean
    private val trackWidthPx: Float
    private val progressWidthPx: Float
    private val glowRadiusPx: Float

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.PlaybackRingView)
        try {
            sweep = a.getInt(R.styleable.PlaybackRingView_ringStyle, STYLE_SOLID) == STYLE_SWEEP
            val stroke = a.getDimension(R.styleable.PlaybackRingView_ringStrokeWidth, DEFAULT_STROKE_DP * density)
            trackWidthPx = a.getDimension(R.styleable.PlaybackRingView_ringTrackWidth, stroke)
            progressWidthPx = a.getDimension(R.styleable.PlaybackRingView_ringProgressWidth, stroke)

            trackPaint.strokeWidth = trackWidthPx
            if (a.hasValue(R.styleable.PlaybackRingView_ringTrackColor)) {
                trackPaint.color = a.getColor(R.styleable.PlaybackRingView_ringTrackColor, Color.TRANSPARENT)
            } else {
                trackPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
                trackPaint.alpha = DEFAULT_TRACK_ALPHA
            }

            progressPaint.strokeWidth = progressWidthPx
            progressPaint.color = a.getColor(
                R.styleable.PlaybackRingView_ringProgressColor,
                ContextCompat.getColor(context, R.color.gradient_pink),
            )

            val hasGlow = a.hasValue(R.styleable.PlaybackRingView_ringGlowColor) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            glowRadiusPx = if (hasGlow) GLOW_RADIUS_DP * density else 0f
            if (hasGlow) {
                progressPaint.setShadowLayer(
                    glowRadiusPx,
                    0f,
                    0f,
                    a.getColor(R.styleable.PlaybackRingView_ringGlowColor, Color.TRANSPARENT),
                )
            }
        } finally {
            a.recycle()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!sweep || w == 0 || h == 0) return
        val cx = w / 2f
        val cy = h / 2f
        val colors = intArrayOf(
            ContextCompat.getColor(context, R.color.gradient_orange),
            ContextCompat.getColor(context, R.color.gradient_pink),
            ContextCompat.getColor(context, R.color.gradient_magenta),
            ContextCompat.getColor(context, R.color.gradient_pink),
            ContextCompat.getColor(context, R.color.gradient_orange),
        )
        // Symmetric stops keep the ring seamless at 12 o'clock under the round cap.
        val shader = SweepGradient(cx, cy, colors, floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f))
        shader.setLocalMatrix(Matrix().apply { setRotate(-90f, cx, cy) })
        progressPaint.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val size = min(width, height).toFloat()
        val halfStroke = max(trackWidthPx, progressWidthPx) / 2f
        val inset = halfStroke + glowRadiusPx + 1f
        val arcSize = size - inset * 2f
        if (arcSize <= 0f) return
        val left = (width - arcSize) / 2f
        val top = (height - arcSize) / 2f

        canvas.drawArc(left, top, left + arcSize, top + arcSize, 0f, 360f, false, trackPaint)
        if (progress > 0f) {
            canvas.drawArc(
                left,
                top,
                left + arcSize,
                top + arcSize,
                -90f,
                360f * progress,
                false,
                progressPaint,
            )
        }
    }

    private companion object {
        const val STYLE_SOLID = 0
        const val STYLE_SWEEP = 1
        const val DEFAULT_STROKE_DP = 3f
        const val DEFAULT_TRACK_ALPHA = 60
        const val GLOW_RADIUS_DP = 8f
    }
}

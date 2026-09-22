package com.spacewire.meratune.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.spacewire.meratune.R
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

    private val strokeWidthPx = 3f * resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = ContextCompat.getColor(context, R.color.text_secondary)
        alpha = 60
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.gradient_pink)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val size = min(width, height).toFloat()
        val halfStroke = strokeWidthPx / 2f
        val inset = halfStroke + 1f
        val arcSize = size - inset * 2f
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
}

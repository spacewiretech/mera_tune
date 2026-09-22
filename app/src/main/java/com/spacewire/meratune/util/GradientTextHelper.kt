package com.spacewire.meratune.util

import android.graphics.LinearGradient
import android.graphics.Shader
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.spacewire.meratune.R

object GradientTextHelper {
    fun applyHorizontalGradient(textView: TextView, startColorRes: Int, endColorRes: Int) {
        textView.post {
            val width = textView.paint.measureText(textView.text.toString())
            if (width <= 0f) return@post

            val startColor = ContextCompat.getColor(textView.context, startColorRes)
            val endColor = ContextCompat.getColor(textView.context, endColorRes)
            textView.paint.shader = LinearGradient(
                0f,
                0f,
                width,
                0f,
                startColor,
                endColor,
                Shader.TileMode.CLAMP,
            )
            textView.invalidate()
        }
    }
}

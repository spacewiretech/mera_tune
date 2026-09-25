package com.spacewire.meratune.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import com.spacewire.meratune.R

/**
 * Three-step stepper (`view_processing_stepper`). Progress `0f..1f`: step one is active from 0,
 * step two from [STEP_TWO_START], and step three ("Ringtone Ready!") only at exactly 1f, so an
 * indeterminate [animateTo] below 1f never claims the ringtone is ready.
 */
class ProcessingStepperController(root: View) {

    private val stepOneCircle = root.findViewById<View>(R.id.stepOneCircle)
    private val stepTwoCircle = root.findViewById<View>(R.id.stepTwoCircle)
    private val stepThreeCircle = root.findViewById<View>(R.id.stepThreeCircle)
    private val connectorOneProgress = root.findViewById<View>(R.id.connectorOneProgress)
    private val connectorTwoProgress = root.findViewById<View>(R.id.connectorTwoProgress)
    private val stepLabels: List<TextView> = listOf(
        root.findViewById(R.id.stepOneLabel),
        root.findViewById(R.id.stepTwoLabel),
        root.findViewById(R.id.stepThreeLabel),
    )

    private val boldTypeface = AppFonts.bold(root.context)
    private val regularTypeface = AppFonts.regular(root.context)

    private var animator: ValueAnimator? = null
    private var activeStep = 0

    /** Last value passed to [setProgress]. */
    var progress: Float = 0f
        private set

    fun setProgress(progress: Float) {
        val normalizedProgress = progress.coerceIn(0f, 1f)
        this.progress = normalizedProgress

        when {
            normalizedProgress < STEP_TWO_START -> {
                setActiveStep(step = 1)
                connectorOneProgress.scaleX = normalizedProgress / STEP_TWO_START
                connectorTwoProgress.scaleX = 0f
            }

            normalizedProgress < 1f -> {
                setActiveStep(step = 2)
                connectorOneProgress.scaleX = 1f
                connectorTwoProgress.scaleX = (normalizedProgress - STEP_TWO_START) / (1f - STEP_TWO_START)
            }

            else -> {
                setActiveStep(step = 3)
                connectorOneProgress.scaleX = 1f
                connectorTwoProgress.scaleX = 1f
            }
        }
    }

    /**
     * Animates from the current [progress] to [target]. Cancels any running animation;
     * [onEnd] runs only when this animation finishes without being cancelled.
     */
    fun animateTo(
        target: Float,
        durationMs: Long,
        interpolator: TimeInterpolator = DecelerateInterpolator(),
        onEnd: (() -> Unit)? = null,
    ) {
        cancel()
        animator = ValueAnimator.ofFloat(progress, target.coerceIn(0f, 1f)).apply {
            duration = durationMs.coerceAtLeast(0L)
            this.interpolator = interpolator
            addUpdateListener { setProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) animator = null
                    if (!cancelled) onEnd?.invoke()
                }
            })
            start()
        }
    }

    fun cancel() {
        val running = animator
        animator = null
        running?.cancel()
    }

    private fun setActiveStep(step: Int) {
        stepOneCircle.setBackgroundResource(
            if (step >= 1) R.drawable.bg_processing_step_active else R.drawable.bg_processing_step_inactive,
        )
        stepTwoCircle.setBackgroundResource(
            if (step >= 2) R.drawable.bg_processing_step_active else R.drawable.bg_processing_step_inactive,
        )
        stepThreeCircle.setBackgroundResource(
            if (step >= 3) R.drawable.bg_processing_step_active else R.drawable.bg_processing_step_inactive,
        )
        if (step != activeStep) {
            activeStep = step
            stepLabels.forEachIndexed { index, label ->
                label.typeface = if (index + 1 == step) boldTypeface else regularTypeface
            }
        }
    }

    private companion object {
        const val STEP_TWO_START = 0.45f
    }
}

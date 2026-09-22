package com.spacewire.meratune.ui

import android.view.View
import com.spacewire.meratune.R

class ProcessingStepperController(root: View) {

    private val stepOneCircle = root.findViewById<View>(R.id.stepOneCircle)
    private val stepTwoCircle = root.findViewById<View>(R.id.stepTwoCircle)
    private val stepThreeCircle = root.findViewById<View>(R.id.stepThreeCircle)
    private val connectorOneProgress = root.findViewById<View>(R.id.connectorOneProgress)
    private val connectorTwoProgress = root.findViewById<View>(R.id.connectorTwoProgress)

    fun setProgress(progress: Float) {
        val normalizedProgress = progress.coerceIn(0f, 1f)

        when {
            normalizedProgress < STEP_TWO_START -> {
                setActiveStep(step = 1)
                connectorOneProgress.scaleX = normalizedProgress / STEP_TWO_START
                connectorTwoProgress.scaleX = 0f
            }

            normalizedProgress < STEP_THREE_START -> {
                setActiveStep(step = 2)
                connectorOneProgress.scaleX = 1f
                connectorTwoProgress.scaleX =
                    (normalizedProgress - STEP_TWO_START) / (STEP_THREE_START - STEP_TWO_START)
            }

            else -> {
                setActiveStep(step = 3)
                connectorOneProgress.scaleX = 1f
                connectorTwoProgress.scaleX = 1f
            }
        }
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
    }

    private companion object {
        const val STEP_TWO_START = 1f / 3f
        const val STEP_THREE_START = 2f / 3f
    }
}

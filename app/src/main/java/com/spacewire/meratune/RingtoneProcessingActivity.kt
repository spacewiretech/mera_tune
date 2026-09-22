package com.spacewire.meratune

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.spacewire.meratune.ui.ProcessingStepperController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RingtoneProcessingActivity : AppCompatActivity() {

    private var stepperAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_ringtone_processing)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.processingScroll)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }

        val name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Ram" }
        findViewById<TextView>(R.id.processingTitle).text =
            getString(R.string.processing_title, name)
        styleProcessingSubtitle()
        startStepperLoading()
        scheduleReadyScreen()
    }

    private fun startStepperLoading() {
        val stepperController = ProcessingStepperController(findViewById(R.id.processingStepper))
        stepperAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PROCESSING_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                stepperController.setProgress(animator.animatedValue as Float)
            }
            start()
        }
    }

    override fun onDestroy() {
        stepperAnimator?.cancel()
        stepperAnimator = null
        super.onDestroy()
    }

    private fun scheduleReadyScreen() {
        lifecycleScope.launch {
            delay(PROCESSING_DURATION_MS)
            if (isFinishing) return@launch

            startActivity(
                RingtoneReadyActivity.intent(
                    context = this@RingtoneProcessingActivity,
                    name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Ram" },
                    voice = intent.getStringExtra(EXTRA_VOICE).orEmpty(),
                    category = intent.getStringExtra(EXTRA_CATEGORY).orEmpty(),
                    language = intent.getStringExtra(EXTRA_LANGUAGE).orEmpty(),
                ),
            )
            finish()
        }
    }

    private fun styleProcessingSubtitle() {
        val subtitleView = findViewById<TextView>(R.id.processingSubtitle)
        val highlight = getString(R.string.processing_subtitle_highlight)
        val fullText = getString(R.string.processing_subtitle, highlight)
        val spannable = SpannableString(fullText)
        val start = fullText.indexOf(highlight)

        if (start >= 0) {
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                start + highlight.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            spannable.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this, R.color.gradient_pink)),
                start,
                start + highlight.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        subtitleView.text = spannable
    }

    companion object {
        private const val EXTRA_NAME = "extra_name"
        private const val EXTRA_VOICE = "extra_voice"
        private const val EXTRA_CATEGORY = "extra_category"
        private const val EXTRA_LANGUAGE = "extra_language"
        private const val PROCESSING_DURATION_MS = 8_000L

        fun intent(
            context: Context,
            name: String,
            voice: String,
            category: String,
            language: String,
        ): Intent {
            return Intent(context, RingtoneProcessingActivity::class.java)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_VOICE, voice)
                .putExtra(EXTRA_CATEGORY, category)
                .putExtra(EXTRA_LANGUAGE, language)
        }
    }
}

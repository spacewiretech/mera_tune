package com.spacewire.meratune

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.spacewire.meratune.data.AuthException
import com.spacewire.meratune.data.AuthFailureReason
import com.spacewire.meratune.data.AuthRepository
import com.spacewire.meratune.data.AuthStage
import com.spacewire.meratune.ui.AuthUi
import com.spacewire.meratune.ui.CtaButtons
import com.spacewire.meratune.ui.OnboardingCarouselView
import com.spacewire.meratune.ui.OtpInputView
import com.spacewire.meratune.analytics.AnalyticsSource
import com.spacewire.meratune.analytics.OtpEntryMethod
import com.spacewire.meratune.analytics.firebaseAnalytics
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.analytics.metaAnalytics
import com.spacewire.meratune.util.AuthNavigator
import com.spacewire.meratune.util.AuthStore
import com.spacewire.meratune.util.AuthTermsHelper
import com.spacewire.meratune.util.GradientTextHelper
import com.spacewire.meratune.util.OtpTimerFormat
import com.spacewire.meratune.util.PhoneUtils
import com.spacewire.meratune.util.ProfileStore
import com.spacewire.meratune.util.SmsOtpFetcher
import com.spacewire.meratune.util.enableLightEdgeToEdge
import kotlinx.coroutines.launch

class OtpVerificationActivity : AppCompatActivity() {

    private val authRepository = AuthRepository()
    private var isVerifying = false
    private var isResending = false
    private var smsOtpFetcher: SmsOtpFetcher? = null
    private var resendCooldownTimer: CountDownTimer? = null
    private var canResend = false

    /** `SystemClock.elapsedRealtime()` when Resend unlocks; saved so rotation keeps the countdown. */
    private var resendDeadlineElapsed = 0L

    /** Set by the SMS fetcher right before it fills the boxes; anything else is a manual entry. */
    private var pendingOtpEntryMethod: String? = null
    private var verifyAttempt = 0
    private var resendCount = 0

    private lateinit var otpInputView: OtpInputView
    private lateinit var verifyButton: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var otpTimerText: TextView
    private lateinit var resendOtpButton: TextView
    private lateinit var resendLoadingIndicator: ProgressBar
    private lateinit var phone: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableLightEdgeToEdge()
        setContentView(R.layout.activity_otp_verification)

        phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
        if (phone.isBlank()) {
            finish()
            return
        }
        verifyAttempt = savedInstanceState?.getInt(STATE_VERIFY_ATTEMPT) ?: 0
        resendCount = savedInstanceState?.getInt(STATE_RESEND_COUNT) ?: 0

        AuthUi.bindImeBehaviour(
            root = findViewById(R.id.otpRoot),
            scroll = findViewById(R.id.otpScroll),
            carousel = findViewById<OnboardingCarouselView>(R.id.onboardingCarousel),
            reveal = findViewById(R.id.verifyButtonContainer),
        )
        AuthUi.bindHeadline(
            findViewById(R.id.authHeadline),
            R.string.auth_otp_headline,
            R.string.auth_otp_headline_highlight,
        )
        AuthTermsHelper.bind(findViewById<TextView>(R.id.authFooter), AnalyticsSource.OTP_ENTRY)

        otpInputView = findViewById(R.id.otpInputView)
        verifyButton = findViewById(R.id.verifyButton)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        otpTimerText = findViewById(R.id.otpTimerText)
        resendOtpButton = findViewById(R.id.resendOtpButton)
        resendLoadingIndicator = findViewById(R.id.resendLoadingIndicator)
        GradientTextHelper.applyGradient(resendOtpButton, AuthUi.ACCENT_COLORS)

        // Auto-verify on the 4th digit and the Verify button share submitOtp's isVerifying guard.
        otpInputView.onCompleteListener = { otp -> submitOtp(otp) }
        otpInputView.onOtpChangedListener = { renderVerifyButton() }
        verifyButton.setOnClickListener { submitOtp(otpInputView.getOtp()) }
        renderVerifyButton()

        resendOtpButton.setOnClickListener {
            if (!isResending && resendOtpButton.isEnabled) {
                resendOtp()
            }
        }

        otpInputView.requestInitialFocus()

        smsOtpFetcher = SmsOtpFetcher(this, otpLength = 4) { otp, source ->
            if (!isVerifying) {
                pendingOtpEntryMethod = source
                otpInputView.setOtp(otp)
            }
        }

        val savedDeadline = savedInstanceState?.getLong(STATE_RESEND_DEADLINE, 0L) ?: 0L
        if (savedDeadline > 0L) {
            // Clamped: elapsedRealtime restarts at boot.
            val remainingMs = (savedDeadline - SystemClock.elapsedRealtime()).coerceAtMost(RESEND_COOLDOWN_MS)
            if (remainingMs > 0L) {
                startResendCooldown(remainingMs)
            } else {
                resendDeadlineElapsed = savedDeadline
                canResend = true
                otpTimerText.isVisible = false
                updateResendButtonEnabled(true)
            }
        } else {
            startResendCooldown(RESEND_COOLDOWN_MS)
        }
    }

    override fun onResume() {
        super.onResume()
        smsOtpFetcher?.start()
    }

    override fun onPause() {
        smsOtpFetcher?.stop()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_VERIFY_ATTEMPT, verifyAttempt)
        outState.putInt(STATE_RESEND_COUNT, resendCount)
        outState.putLong(STATE_RESEND_DEADLINE, resendDeadlineElapsed)
    }

    override fun onDestroy() {
        resendCooldownTimer?.cancel()
        super.onDestroy()
    }

    private fun resendOtp() {
        isResending = true
        resendOtpButton.visibility = View.INVISIBLE
        resendLoadingIndicator.visibility = View.VISIBLE

        lifecycleScope.launch {
            authRepository.sendOtp(phone)
                .onSuccess {
                    resendCount++
                    mixpanelAnalytics().trackOtpSent(isResend = true, resendCount = resendCount)
                    Toast.makeText(
                        this@OtpVerificationActivity,
                        getString(R.string.auth_otp_resent, PhoneUtils.formatDisplayPhone(phone)),
                        Toast.LENGTH_SHORT,
                    ).show()
                    otpInputView.clear()
                    smsOtpFetcher?.restartListening()
                    startResendCooldown(RESEND_COOLDOWN_MS)
                }
                .onFailure { error ->
                    mixpanelAnalytics().trackAuthFailed(
                        stage = AuthStage.SEND_OTP,
                        failureReason = AuthFailureReason.from(error),
                        isResend = true,
                    )
                    val message = (error as? AuthException)?.message ?: getString(R.string.auth_generic_error)
                    Toast.makeText(this@OtpVerificationActivity, message, Toast.LENGTH_LONG).show()
                    updateResendButtonEnabled(true)
                }

            isResending = false
            resendLoadingIndicator.visibility = View.GONE
            resendOtpButton.visibility = View.VISIBLE
        }
    }

    private fun startResendCooldown(durationMs: Long) {
        resendCooldownTimer?.cancel()
        canResend = false
        resendDeadlineElapsed = SystemClock.elapsedRealtime() + durationMs
        updateResendButtonEnabled(false)
        showTimer(durationMs)

        resendCooldownTimer = object : CountDownTimer(durationMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) = showTimer(millisUntilFinished)

            override fun onFinish() {
                canResend = true
                otpTimerText.isVisible = false
                updateResendButtonEnabled(true)
            }
        }.start()
    }

    /** "00:28" next to the dimmed Resend link; rounds up so it never shows 00:00 while locked. */
    private fun showTimer(remainingMs: Long) {
        otpTimerText.isVisible = true
        otpTimerText.text = OtpTimerFormat.format(((remainingMs + 999L) / 1000L).toInt())
    }

    private fun updateResendButtonEnabled(enabled: Boolean) {
        resendOtpButton.isEnabled = enabled
        resendOtpButton.alpha = if (enabled) 1f else 0.5f
    }

    /** The one entry point for a verification, from auto-verify or the Verify button. */
    private fun submitOtp(otp: String) {
        if (isVerifying || otp.length != OtpInputView.DIGIT_COUNT) return
        val entryMethod = pendingOtpEntryMethod ?: OtpEntryMethod.MANUAL
        pendingOtpEntryMethod = null
        verifyOtp(phone, otp, entryMethod)
    }

    /** Grey (disabled) below 4 digits; stays enabled while a verification shows its spinner. */
    private fun renderVerifyButton() {
        verifyButton.isEnabled = isVerifying || otpInputView.getOtp().length == OtpInputView.DIGIT_COUNT
    }

    private fun verifyOtp(
        phone: String,
        otp: String,
        otpEntryMethod: String,
    ) {
        isVerifying = true
        verifyAttempt++
        CtaButtons.setLoading(verifyButton, loadingIndicator, true)
        renderVerifyButton()
        otpInputView.setEnabledState(false)
        updateResendButtonEnabled(false)

        lifecycleScope.launch {
            authRepository.verifyOtp(phone, otp)
                .onSuccess { response ->
                    val user = response.user
                    val sessionToken = response.sessionToken.orEmpty()

                    if (response.needsName == false && user != null) {
                        completeLogin(user, response.apiToken, otpEntryMethod)
                        return@launch
                    }

                    if (sessionToken.isBlank()) {
                        mixpanelAnalytics().trackOtpVerificationFailed(
                            failureReason = AuthFailureReason.BAD_RESPONSE,
                            otpEntryMethod = otpEntryMethod,
                            attempt = verifyAttempt,
                        )
                        Toast.makeText(
                            this@OtpVerificationActivity,
                            R.string.auth_generic_error,
                            Toast.LENGTH_SHORT,
                        ).show()
                        resetOtp()
                        return@launch
                    }

                    startActivity(
                        SignUpNameActivity.intent(this@OtpVerificationActivity, sessionToken, phone, otpEntryMethod),
                    )
                    finish()
                }
                .onFailure { error ->
                    mixpanelAnalytics().trackOtpVerificationFailed(
                        failureReason = AuthFailureReason.from(error),
                        otpEntryMethod = otpEntryMethod,
                        attempt = verifyAttempt,
                    )
                    val message = (error as? AuthException)?.message ?: getString(R.string.auth_generic_error)
                    Toast.makeText(this@OtpVerificationActivity, message, Toast.LENGTH_LONG).show()
                    resetOtp()
                }
        }
    }

    private fun resetOtp() {
        isVerifying = false
        pendingOtpEntryMethod = null
        CtaButtons.setLoading(verifyButton, loadingIndicator, false)
        otpInputView.setEnabledState(true)
        otpInputView.clear()
        renderVerifyButton()
        smsOtpFetcher?.restartListening()
        updateResendButtonEnabled(canResend && !isResending)
    }

    private fun completeLogin(user: com.spacewire.meratune.data.User, apiToken: String?, otpEntryMethod: String) {
        val authStore = AuthStore(this)
        authStore.saveUser(user)
        authStore.replaceApiToken(apiToken)
        ProfileStore(this).saveUser(user.name.orEmpty(), user.phone)
        val analytics = mixpanelAnalytics()
        analytics.identifyUser(user)
        analytics.trackLoginCompleted(
            signInMethod = SIGN_IN_METHOD_PHONE,
            otpEntryMethod = otpEntryMethod,
            attempt = verifyAttempt,
            resendCount = resendCount,
            postAuthDestination = AuthNavigator.postAuthDestination(user),
        )
        metaAnalytics().identifyUser(user)
        firebaseAnalytics().identifyUser(user)
        AuthNavigator.navigateAfterAuth(this, user)
        finish()
    }

    companion object {
        private const val SIGN_IN_METHOD_PHONE = "phone"
        private const val EXTRA_PHONE = "extra_phone"
        private const val RESEND_COOLDOWN_MS = 30_000L
        private const val STATE_VERIFY_ATTEMPT = "state_verify_attempt"
        private const val STATE_RESEND_COUNT = "state_resend_count"
        private const val STATE_RESEND_DEADLINE = "state_resend_deadline"

        fun intent(context: Context, phone: String): Intent =
            Intent(context, OtpVerificationActivity::class.java).putExtra(EXTRA_PHONE, phone)
    }
}

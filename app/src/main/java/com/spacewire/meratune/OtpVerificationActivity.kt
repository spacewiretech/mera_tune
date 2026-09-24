package com.spacewire.meratune

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.spacewire.meratune.data.AuthException
import com.spacewire.meratune.data.AuthFailureReason
import com.spacewire.meratune.data.AuthRepository
import com.spacewire.meratune.data.AuthStage
import com.spacewire.meratune.ui.OtpInputView
import com.spacewire.meratune.analytics.OtpEntryMethod
import com.spacewire.meratune.analytics.firebaseAnalytics
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.analytics.metaAnalytics
import com.spacewire.meratune.util.AuthNavigator
import com.spacewire.meratune.util.AuthStore
import com.spacewire.meratune.util.PhoneUtils
import com.spacewire.meratune.util.ProfileStore
import com.spacewire.meratune.util.SmsOtpFetcher
import kotlinx.coroutines.launch

class OtpVerificationActivity : AppCompatActivity() {

    private val authRepository = AuthRepository()
    private var isVerifying = false
    private var isResending = false
    private var smsOtpFetcher: SmsOtpFetcher? = null
    private var resendCooldownTimer: CountDownTimer? = null
    private var canResend = false

    /** Set by the SMS fetcher right before it fills the boxes; anything else is a manual entry. */
    private var pendingOtpEntryMethod: String? = null
    private var verifyAttempt = 0
    private var resendCount = 0

    private lateinit var otpInputView: OtpInputView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var resendOtpButton: TextView
    private lateinit var resendLoadingIndicator: ProgressBar
    private lateinit var phone: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_otp_verification)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.otpRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom,
            )
            insets
        }

        phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
        if (phone.isBlank()) {
            finish()
            return
        }
        verifyAttempt = savedInstanceState?.getInt(STATE_VERIFY_ATTEMPT) ?: 0
        resendCount = savedInstanceState?.getInt(STATE_RESEND_COUNT) ?: 0

        findViewById<View>(R.id.authHeader).findViewById<View>(R.id.languageButton).setOnClickListener {
            startActivity(LanguageSelectionActivity.intent(this))
        }

        otpInputView = findViewById(R.id.otpInputView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        resendOtpButton = findViewById(R.id.resendOtpButton)
        resendLoadingIndicator = findViewById(R.id.resendLoadingIndicator)

        findViewById<TextView>(R.id.phoneNumberText).text = PhoneUtils.formatDisplayPhone(phone)

        otpInputView.onCompleteListener = { otp ->
            if (!isVerifying) {
                val entryMethod = pendingOtpEntryMethod ?: OtpEntryMethod.MANUAL
                pendingOtpEntryMethod = null
                verifyOtp(phone, otp, entryMethod)
            }
        }

        findViewById<TextView>(R.id.editPhoneButton).setOnClickListener {
            startActivity(PhoneAuthActivity.intent(this, phone))
            finish()
        }

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

        startResendCooldown(RESEND_COOLDOWN_SECONDS)
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
                    startResendCooldown(RESEND_COOLDOWN_SECONDS)
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

    private fun startResendCooldown(seconds: Int) {
        resendCooldownTimer?.cancel()
        canResend = false
        updateResendButtonEnabled(false)

        resendCooldownTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = (millisUntilFinished / 1000L).toInt().coerceAtLeast(1)
                resendOtpButton.text = getString(R.string.auth_otp_resend_in, secondsLeft)
            }

            override fun onFinish() {
                canResend = true
                resendOtpButton.text = getString(R.string.auth_otp_resend)
                updateResendButtonEnabled(true)
            }
        }.start()
    }

    private fun updateResendButtonEnabled(enabled: Boolean) {
        resendOtpButton.isEnabled = enabled
        resendOtpButton.alpha = if (enabled) 1f else 0.5f
    }

    private fun verifyOtp(
        phone: String,
        otp: String,
        otpEntryMethod: String,
    ) {
        isVerifying = true
        verifyAttempt++
        loadingIndicator.visibility = View.VISIBLE
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
        loadingIndicator.visibility = View.GONE
        otpInputView.setEnabledState(true)
        otpInputView.clear()
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
        private const val RESEND_COOLDOWN_SECONDS = 30
        private const val STATE_VERIFY_ATTEMPT = "state_verify_attempt"
        private const val STATE_RESEND_COUNT = "state_resend_count"

        fun intent(context: Context, phone: String): Intent =
            Intent(context, OtpVerificationActivity::class.java).putExtra(EXTRA_PHONE, phone)
    }
}

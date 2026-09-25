package com.spacewire.meratune

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.spacewire.meratune.analytics.AnalyticsSource
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.data.AuthException
import com.spacewire.meratune.data.AuthFailureReason
import com.spacewire.meratune.data.AuthRepository
import com.spacewire.meratune.data.AuthStage
import com.spacewire.meratune.ui.AuthUi
import com.spacewire.meratune.ui.CtaButtons
import com.spacewire.meratune.ui.OnboardingCarouselView
import com.spacewire.meratune.util.AuthTermsHelper
import com.spacewire.meratune.util.PhoneUtils
import com.spacewire.meratune.util.enableLightEdgeToEdge
import kotlinx.coroutines.launch

class PhoneAuthActivity : AppCompatActivity() {

    private val authRepository = AuthRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableLightEdgeToEdge()
        setContentView(R.layout.activity_phone_auth)

        AuthUi.bindImeBehaviour(
            root = findViewById(R.id.phoneAuthRoot),
            scroll = findViewById(R.id.phoneAuthScroll),
            carousel = findViewById<OnboardingCarouselView>(R.id.onboardingCarousel),
            reveal = findViewById(R.id.nextButtonContainer),
        )
        AuthUi.bindHeadline(
            findViewById(R.id.authHeadline),
            R.string.auth_phone_headline,
            R.string.auth_phone_headline_highlight,
        )
        AuthTermsHelper.bind(findViewById<TextView>(R.id.authFooter), AnalyticsSource.PHONE_ENTRY)

        val phoneInput = findViewById<EditText>(R.id.phoneInput)
        val nextButton = findViewById<TextView>(R.id.nextButton)
        val loadingIndicator = findViewById<ProgressBar>(R.id.loadingIndicator)

        // Grey until the number is valid, but still tappable: an invalid tap explains why (toast +
        // auth_failed) in the click handler below.
        fun renderNextButton() {
            CtaButtons.setLooksDisabled(nextButton, PhoneUtils.normalizeIndianPhone(phoneInput.text.toString()) == null)
        }
        renderNextButton()
        phoneInput.doAfterTextChanged { renderNextButton() }

        nextButton.setOnClickListener {
            val normalized = PhoneUtils.normalizeIndianPhone(phoneInput.text.toString())
            if (normalized == null) {
                mixpanelAnalytics().trackAuthFailed(AuthStage.PHONE_VALIDATION, AuthFailureReason.INVALID_PHONE_FORMAT)
                Toast.makeText(this, R.string.auth_invalid_phone, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            setLoading(true, nextButton, loadingIndicator, phoneInput)

            lifecycleScope.launch {
                authRepository.sendOtp(normalized)
                    .onSuccess { verifiedPhone ->
                        mixpanelAnalytics().trackOtpSent(isResend = false)
                        startActivity(OtpVerificationActivity.intent(this@PhoneAuthActivity, verifiedPhone))
                    }
                    .onFailure { error ->
                        mixpanelAnalytics().trackAuthFailed(
                            stage = AuthStage.SEND_OTP,
                            failureReason = AuthFailureReason.from(error),
                            isResend = false,
                        )
                        val message = (error as? AuthException)?.message ?: getString(R.string.auth_generic_error)
                        Toast.makeText(this@PhoneAuthActivity, message, Toast.LENGTH_LONG).show()
                    }

                setLoading(false, nextButton, loadingIndicator, phoneInput)
            }
        }
    }

    private fun setLoading(
        loading: Boolean,
        nextButton: TextView,
        loadingIndicator: ProgressBar,
        phoneInput: EditText,
    ) {
        CtaButtons.setLoading(nextButton, loadingIndicator, loading)
        phoneInput.isEnabled = !loading
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, PhoneAuthActivity::class.java)
    }
}

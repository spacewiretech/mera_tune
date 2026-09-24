package com.spacewire.meratune

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.spacewire.meratune.analytics.AnalyticsSource
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.data.AuthException
import com.spacewire.meratune.data.AuthFailureReason
import com.spacewire.meratune.data.AuthRepository
import com.spacewire.meratune.data.AuthStage
import com.spacewire.meratune.util.AuthTermsHelper
import com.spacewire.meratune.util.PhoneUtils
import kotlinx.coroutines.launch

class PhoneAuthActivity : AppCompatActivity() {

    private val authRepository = AuthRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_phone_auth)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.phoneAuthRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom,
            )
            insets
        }

        AuthTermsHelper.bind(findViewById<TextView>(R.id.authFooter), AnalyticsSource.PHONE_ENTRY)
        findViewById<View>(R.id.authHeader).findViewById<View>(R.id.languageButton).setOnClickListener {
            startActivity(LanguageSelectionActivity.intent(this))
        }

        val phoneInput = findViewById<EditText>(R.id.phoneInput)
        val nextButton = findViewById<ImageView>(R.id.nextButton)
        val loadingIndicator = findViewById<ProgressBar>(R.id.loadingIndicator)

        intent.getStringExtra(EXTRA_PREFILL_PHONE)?.let { prefill ->
            phoneInput.setText(prefill)
            phoneInput.setSelection(prefill.length)
        }

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
        nextButton: ImageView,
        loadingIndicator: ProgressBar,
        phoneInput: EditText,
    ) {
        nextButton.visibility = if (loading) View.INVISIBLE else View.VISIBLE
        loadingIndicator.visibility = if (loading) View.VISIBLE else View.GONE
        phoneInput.isEnabled = !loading
    }

    companion object {
        private const val EXTRA_PREFILL_PHONE = "extra_prefill_phone"

        fun intent(context: Context): Intent = Intent(context, PhoneAuthActivity::class.java)

        fun intent(context: Context, prefillPhone: String): Intent =
            Intent(context, PhoneAuthActivity::class.java)
                .putExtra(EXTRA_PREFILL_PHONE, prefillPhone)
    }
}

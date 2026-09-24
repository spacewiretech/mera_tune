package com.spacewire.meratune

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
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
import com.spacewire.meratune.analytics.firebaseAnalytics
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.analytics.metaAnalytics
import com.spacewire.meratune.util.AuthNavigator
import com.spacewire.meratune.util.AuthStore
import com.spacewire.meratune.util.ProfileStore
import kotlinx.coroutines.launch

class SignUpNameActivity : AppCompatActivity() {

    private val authRepository = AuthRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_sign_up_name)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.signUpRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom,
            )
            insets
        }

        val sessionToken = intent.getStringExtra(EXTRA_SESSION_TOKEN).orEmpty()
        val phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
        val otpEntryMethod = intent.getStringExtra(EXTRA_OTP_ENTRY_METHOD)
        if (sessionToken.isBlank()) {
            finish()
            return
        }

        findViewById<View>(R.id.authHeader).findViewById<View>(R.id.languageButton).setOnClickListener {
            startActivity(LanguageSelectionActivity.intent(this))
        }

        val nameInput = findViewById<EditText>(R.id.nameInput)
        val continueButton = findViewById<TextView>(R.id.continueButton)
        val loadingIndicator = findViewById<ProgressBar>(R.id.loadingIndicator)

        continueButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.length < 2) {
                mixpanelAnalytics().trackAuthFailed(AuthStage.NAME_VALIDATION, AuthFailureReason.NAME_TOO_SHORT)
                Toast.makeText(this, R.string.auth_name_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            setLoading(true, continueButton, loadingIndicator, nameInput)

            lifecycleScope.launch {
                authRepository.completeSignup(sessionToken, name)
                    .onSuccess { result ->
                        val user = result.user
                        val authStore = AuthStore(this@SignUpNameActivity)
                        authStore.saveUser(user)
                        authStore.replaceApiToken(result.apiToken)
                        ProfileStore(this@SignUpNameActivity).saveUser(user.name.orEmpty(), user.phone)

                        val analytics = mixpanelAnalytics()
                        analytics.identifyUser(user)
                        analytics.trackSignUpCompleted(
                            signUpMethod = SIGN_UP_METHOD_PHONE,
                            postAuthDestination = AuthNavigator.postAuthDestination(user),
                            otpEntryMethod = otpEntryMethod,
                        )
                        metaAnalytics().identifyUser(user)
                        metaAnalytics().trackCompleteRegistration(SIGN_UP_METHOD_PHONE)
                        firebaseAnalytics().identifyUser(user)

                        AuthNavigator.navigateAfterAuth(this@SignUpNameActivity, user)
                        finish()
                    }
                    .onFailure { error ->
                        mixpanelAnalytics().trackAuthFailed(AuthStage.COMPLETE_SIGNUP, AuthFailureReason.from(error))
                        val message = (error as? AuthException)?.message ?: getString(R.string.auth_generic_error)
                        Toast.makeText(this@SignUpNameActivity, message, Toast.LENGTH_LONG).show()
                        setLoading(false, continueButton, loadingIndicator, nameInput)
                    }
            }
        }
    }

    private fun setLoading(
        loading: Boolean,
        continueButton: TextView,
        loadingIndicator: ProgressBar,
        nameInput: EditText,
    ) {
        continueButton.isEnabled = !loading
        continueButton.alpha = if (loading) 0.7f else 1f
        loadingIndicator.visibility = if (loading) View.VISIBLE else View.GONE
        nameInput.isEnabled = !loading
    }

    companion object {
        private const val SIGN_UP_METHOD_PHONE = "phone"
        private const val EXTRA_SESSION_TOKEN = "extra_session_token"
        private const val EXTRA_PHONE = "extra_phone"
        private const val EXTRA_OTP_ENTRY_METHOD = "extra_otp_entry_method"

        /** [otpEntryMethod] is an `OtpEntryMethod` value, carried for `sign_up_completed`. */
        fun intent(context: Context, sessionToken: String, phone: String, otpEntryMethod: String? = null): Intent =
            Intent(context, SignUpNameActivity::class.java)
                .putExtra(EXTRA_SESSION_TOKEN, sessionToken)
                .putExtra(EXTRA_PHONE, phone)
                .putExtra(EXTRA_OTP_ENTRY_METHOD, otpEntryMethod)
    }
}

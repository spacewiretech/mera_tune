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
import com.spacewire.meratune.data.AuthRepository
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
                Toast.makeText(this, R.string.auth_name_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            setLoading(true, continueButton, loadingIndicator, nameInput)

            lifecycleScope.launch {
                authRepository.completeSignup(sessionToken, name)
                    .onSuccess { user ->
                        AuthStore(this@SignUpNameActivity).saveUser(user)
                        ProfileStore(this@SignUpNameActivity).saveUser(user.name.orEmpty(), user.phone)

                        val analytics = mixpanelAnalytics()
                        analytics.identifyUser(user)
                        analytics.trackSignUpCompleted(SIGN_UP_METHOD_PHONE)
                        metaAnalytics().identifyUser(user)
                        metaAnalytics().trackCompleteRegistration(SIGN_UP_METHOD_PHONE)
                        firebaseAnalytics().identifyUser(user)

                        AuthNavigator.navigateAfterAuth(this@SignUpNameActivity, user)
                        finish()
                    }
                    .onFailure { error ->
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

        fun intent(context: Context, sessionToken: String, phone: String): Intent =
            Intent(context, SignUpNameActivity::class.java)
                .putExtra(EXTRA_SESSION_TOKEN, sessionToken)
                .putExtra(EXTRA_PHONE, phone)
    }
}

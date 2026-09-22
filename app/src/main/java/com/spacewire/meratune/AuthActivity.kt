package com.spacewire.meratune

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.spacewire.meratune.util.AuthNavigator
import com.spacewire.meratune.util.AuthStore
import com.spacewire.meratune.util.ProfileStore

class AuthActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val profileStore = ProfileStore(this)
        val authStore = AuthStore(this)
        val destination = when {
            !profileStore.hasSelectedLanguage() ->
                LanguageSelectionActivity.intent(this, onboarding = true)
            !authStore.isLoggedIn() -> PhoneAuthActivity.intent(this)
            AuthNavigator.needsSubscription(authStore.getStatus()) -> SubscriptionActivity.intent(this)
            else -> Intent(this, Home::class.java)
        }

        startActivity(destination)
        finish()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, AuthActivity::class.java)
    }
}

package com.spacewire.meratune

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.spacewire.meratune.util.AuthNavigator
import com.spacewire.meratune.util.AuthStore
import com.spacewire.meratune.util.LaunchDestination
import com.spacewire.meratune.util.ProfileStore

class AuthActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Theme.MeraTune.Starting (manifest). No keep-on-screen condition: this activity routes and
        // finishes inside onCreate, so app_opened.entry_screen stays the destination's slug.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        val authStore = AuthStore(this)
        val route = AuthNavigator.launchDestination(
            hasSelectedLanguage = ProfileStore(this).hasSelectedLanguage(),
            isLoggedIn = authStore.isLoggedIn(),
            status = authStore.getStatus(),
            browsingWithoutTrial = authStore.isBrowsingWithoutTrial(),
        )
        val destination = when (route) {
            LaunchDestination.LANGUAGE_SELECTION -> LanguageSelectionActivity.intent(this, onboarding = true)
            LaunchDestination.PHONE_AUTH -> PhoneAuthActivity.intent(this)
            LaunchDestination.SUBSCRIPTION -> SubscriptionActivity.intent(this)
            LaunchDestination.HOME -> Intent(this, Home::class.java)
        }

        startActivity(destination)
        finish()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, AuthActivity::class.java)
    }
}

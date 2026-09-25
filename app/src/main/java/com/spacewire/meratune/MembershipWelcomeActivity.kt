package com.spacewire.meratune

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.spacewire.meratune.analytics.AnalyticsSource
import com.spacewire.meratune.analytics.CreationEntryPoint
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.util.InsetsUi
import com.spacewire.meratune.util.ProfileStore
import com.spacewire.meratune.util.enableLightEdgeToEdge

/**
 * Shown once after the trial payment verifies. The CTA and all three checklist rows open the create
 * form on top of a fresh Home; back goes to that Home.
 */
class MembershipWelcomeActivity : AppCompatActivity() {

    /** Ignores a second tap while the create form is opening; reset when the screen resumes. */
    private var isNavigating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableLightEdgeToEdge()
        setContentView(R.layout.activity_membership_welcome)
        InsetsUi.padForSystemBarsAndIme(findViewById(R.id.membershipRoot))

        val authAmount = intent.getStringExtra(EXTRA_AUTH_AMOUNT)?.takeIf { it.isNotBlank() } ?: DEFAULT_AUTH_AMOUNT
        findViewById<TextView>(R.id.membershipSubtitle).text = getString(R.string.member_subtitle, authAmount)

        listOf(R.id.membershipCta, R.id.memberRowFirstTune, R.id.memberRowPhoto, R.id.memberRowFamily).forEach { id ->
            findViewById<View>(id).setOnClickListener { openCreateForm() }
        }

        onBackPressedDispatcher.addCallback(this) {
            startActivity(homeIntent())
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        isNavigating = false
    }

    private fun openCreateForm() {
        if (isNavigating) return
        isNavigating = true
        mixpanelAnalytics().trackCreateRingtoneCtaTapped(
            source = AnalyticsSource.MEMBERSHIP_WELCOME,
            prefillNameLength = ProfileStore(this).getProfile().name.trim().length,
        )
        startActivities(
            arrayOf(
                homeIntent(),
                CreateRingtoneActivity.intent(this, "", CreationEntryPoint.POST_PURCHASE),
            ),
        )
        finish()
    }

    private fun homeIntent(): Intent =
        Intent(this, Home::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    companion object {
        private const val EXTRA_AUTH_AMOUNT = "extra_auth_amount"
        private const val DEFAULT_AUTH_AMOUNT = "3"

        /** [authAmountLabel] is the trial price as shown on the paywall (for example "3"). */
        fun intent(context: Context, authAmountLabel: String): Intent =
            Intent(context, MembershipWelcomeActivity::class.java).putExtra(EXTRA_AUTH_AMOUNT, authAmountLabel)
    }
}

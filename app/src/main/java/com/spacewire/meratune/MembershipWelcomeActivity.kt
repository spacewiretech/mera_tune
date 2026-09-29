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
import com.spacewire.meratune.data.SubscriptionOfferType
import com.spacewire.meratune.ui.PaywallOfferPolicy
import com.spacewire.meratune.util.InsetsUi
import com.spacewire.meratune.util.ProfileStore
import com.spacewire.meratune.util.enableLightEdgeToEdge

/**
 * Shown once after the mandate (the ₹3 trial or the paid plan) verifies. The CTA and all three
 * checklist rows open the create form on top of a fresh Home; back goes to that Home.
 */
class MembershipWelcomeActivity : AppCompatActivity() {

    /** Ignores a second tap while the create form is opening; reset when the screen resumes. */
    private var isNavigating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableLightEdgeToEdge()
        setContentView(R.layout.activity_membership_welcome)
        InsetsUi.padForSystemBarsAndIme(findViewById(R.id.membershipRoot))

        // Intents from before the paid offer carry no offer: they were all trials.
        val offer = SubscriptionOfferType.fromWire(intent.getStringExtra(EXTRA_OFFER)) ?: SubscriptionOfferType.TRIAL
        val amount = intent.getStringExtra(EXTRA_AMOUNT)?.takeIf { it.isNotBlank() }
            ?: PaywallOfferPolicy.amountLabel(PaywallOfferPolicy.defaults(offer).heroAmount)
        findViewById<TextView>(R.id.membershipSubtitle).text =
            getString(PaywallOfferPolicy.copy(offer).memberSubtitle, amount)

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
        private const val EXTRA_OFFER = "extra_offer"
        private const val EXTRA_AMOUNT = "extra_auth_amount"

        /**
         * [amountLabel] is the paywall's big price for [offer] (for example "3" for the trial,
         * "299" per month for the paid plan).
         */
        fun intent(context: Context, offer: SubscriptionOfferType, amountLabel: String): Intent =
            Intent(context, MembershipWelcomeActivity::class.java)
                .putExtra(EXTRA_OFFER, offer.wire)
                .putExtra(EXTRA_AMOUNT, amountLabel)
    }
}

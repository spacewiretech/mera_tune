package com.spacewire.meratune.ui

import androidx.annotation.StringRes
import com.spacewire.meratune.R
import com.spacewire.meratune.data.GenerationQuota
import com.spacewire.meratune.data.SubscriptionOfferType
import java.util.Locale
import kotlin.math.abs

/**
 * The offer and amounts the paywall shows (and its subscription events send). [authAmount] is
 * charged when the mandate is authorised: the ₹3 trial fee, or the paid plan's first month.
 * [recurringAmount] is the monthly autopay.
 */
data class PaywallPricing(
    val offer: SubscriptionOfferType,
    val authAmount: Double,
    val recurringAmount: Double,
) {
    val isTrial: Boolean get() = offer == SubscriptionOfferType.TRIAL

    /** The big price: today's trial fee, or the paid plan's monthly price. */
    val heroAmount: Double get() = if (isTrial) authAmount else recurringAmount
}

/**
 * The strings of one offer. [renewal] is formatted with the recurring amount (the paid line has no
 * placeholder); [faqQuestion2] and [memberSubtitle] with [PaywallPricing.heroAmount]; [faqAnswer2]
 * and [faqAnswer4] with the auth and then the recurring amount.
 */
data class PaywallCopy(
    @param:StringRes val title: Int,
    @param:StringRes val renewal: Int,
    @param:StringRes val faqQuestion2: Int,
    @param:StringRes val faqAnswer2: Int,
    @param:StringRes val faqAnswer4: Int,
    @param:StringRes val memberSubtitle: Int,
)

/**
 * Pure rules for the paywall's two offers. create-subscription decides the offer (a user or phone
 * that ever authorised a mandate gets the paid plan, never a second ₹3 trial); the app only shows
 * a best guess until the server's preview answers, and never opens a checkout for a price the
 * user did not see.
 */
object PaywallOfferPolicy {

    const val DEFAULT_TRIAL_AUTH_AMOUNT = 3.0
    const val DEFAULT_RECURRING_AMOUNT = 299.0

    /** Stored statuses only an authorised mandate produces, so the server never offers them a trial. */
    private val returningStatuses = setOf("trial", "active", "cancelled", "expired")

    private const val AMOUNT_EPSILON = 0.001

    /**
     * The offer shown before the server answers, from the stored `AuthStore` status: the paid plan
     * for a returning user (`cancelled` / `expired`, and a `trial` / `active` user who is only here
     * because the server no longer counts them as a member), else the trial.
     */
    fun localGuess(status: String?): SubscriptionOfferType =
        if (status?.trim()?.lowercase(Locale.ROOT) in returningStatuses) {
            SubscriptionOfferType.PAID
        } else {
            SubscriptionOfferType.TRIAL
        }

    /** The default amounts of [offer]: trial 3 / 299, paid 299 / 299 (the first month is charged today). */
    fun defaults(offer: SubscriptionOfferType): PaywallPricing = when (offer) {
        SubscriptionOfferType.TRIAL ->
            PaywallPricing(offer, DEFAULT_TRIAL_AUTH_AMOUNT, DEFAULT_RECURRING_AMOUNT)
        SubscriptionOfferType.PAID ->
            PaywallPricing(offer, DEFAULT_RECURRING_AMOUNT, DEFAULT_RECURRING_AMOUNT)
    }

    /**
     * Whether a created mandate must not be checked out: its offer or either amount differs from
     * what the paywall showed when the user tapped. The paywall then shows the server's price and
     * the next tap creates again.
     */
    fun mustStopCheckout(shownAtTap: PaywallPricing, created: PaywallPricing): Boolean =
        shownAtTap.offer != created.offer ||
            !sameAmount(shownAtTap.authAmount, created.authAmount) ||
            !sameAmount(shownAtTap.recurringAmount, created.recurringAmount)

    fun copy(offer: SubscriptionOfferType): PaywallCopy = when (offer) {
        SubscriptionOfferType.TRIAL -> PaywallCopy(
            title = R.string.paywall_title,
            renewal = R.string.paywall_then_price,
            faqQuestion2 = R.string.paywall_faq_q2,
            faqAnswer2 = R.string.paywall_faq_a2,
            faqAnswer4 = R.string.paywall_faq_a4,
            memberSubtitle = R.string.member_subtitle,
        )
        SubscriptionOfferType.PAID -> PaywallCopy(
            title = R.string.paywall_title_paid,
            renewal = R.string.paywall_renewal_paid,
            faqQuestion2 = R.string.paywall_faq_q2_paid,
            faqAnswer2 = R.string.paywall_faq_a2_paid,
            faqAnswer4 = R.string.paywall_faq_a4_paid,
            memberSubtitle = R.string.member_subtitle_paid,
        )
    }

    /**
     * The status to store after create-subscription said "already active" (409), from the server's
     * creation plan (`name-ringtones` quota): `trial` / `active`, else null (keep the stored one).
     */
    fun statusForPlan(plan: String?): String? = when (plan?.trim()?.lowercase(Locale.ROOT)) {
        GenerationQuota.PLAN_TRIAL -> "trial"
        GenerationQuota.PLAN_MEMBER -> "active"
        else -> null
    }

    /** A rupee amount as the paywall prints it after "₹": "3", "299", "2.5". */
    fun amountLabel(amount: Double): String =
        if (amount % 1.0 == 0.0) amount.toInt().toString() else amount.toString()

    private fun sameAmount(a: Double, b: Double): Boolean = abs(a - b) < AMOUNT_EPSILON
}

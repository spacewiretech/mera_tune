package com.spacewire.meratune.ui

import com.spacewire.meratune.analytics.PaywallEntryPoint
import com.spacewire.meratune.data.SubscriptionFailureReason

/** Which of the paywall's three in-activity screens is showing. */
enum class PaywallUiState { PAYWALL, PENDING, FAILED }

/**
 * What started a verify request: the Cashfree verify callback ([CHECKOUT]), the re-run after a
 * recreation ([RESTORED]) or a "Payment Status Dekhein" tap on Pending ([MANUAL]).
 */
enum class VerifyTrigger { CHECKOUT, RESTORED, MANUAL }

/**
 * How the paywall's Home button reaches Home: [FINISH] uncovers the Home that opened the paywall,
 * [CLEAR_TOP_TO_HOME] returns to the Home deeper in the task (the processing screen's Subscribe),
 * and [NEW_TASK_TO_HOME] starts Home when the paywall is the task root (after onboarding or at
 * app launch).
 */
enum class HomeButtonRoute { FINISH, CLEAR_TOP_TO_HOME, NEW_TASK_TO_HOME }

/**
 * Pure UI rules for the paywall's Pending / Failed screens. They only decide what the user sees
 * after outcomes the activity already handles (and tracks); they never trigger a request.
 */
object PaywallUiPolicy {

    /** A UPI cancel returns quietly to the paywall; every other checkout failure shows Failed. */
    fun stateAfterCheckoutFailure(cfErrorCode: String?): PaywallUiState =
        if (SubscriptionFailureReason.forCheckout(cfErrorCode) == SubscriptionFailureReason.USER_CANCELLED) {
            PaywallUiState.PAYWALL
        } else {
            PaywallUiState.FAILED
        }

    /** Failed lists the "bank / funds / details" reasons only for a real payment failure. */
    fun showsFailedReasons(cfErrorCode: String?): Boolean =
        SubscriptionFailureReason.forCheckout(cfErrorCode) == SubscriptionFailureReason.PAYMENT_FAILED

    /**
     * A verify that did not activate the trial (not active yet, no user, or a transport error)
     * opens Pending after a checkout or its re-run. A manual re-check keeps the current screen,
     * so a result that lands after the user went back never re-opens Pending.
     */
    fun stateAfterVerifyNotActive(current: PaywallUiState, trigger: VerifyTrigger): PaywallUiState =
        when (trigger) {
            VerifyTrigger.CHECKOUT, VerifyTrigger.RESTORED -> PaywallUiState.PENDING
            VerifyTrigger.MANUAL -> current
        }

    /**
     * [entryPoint] is the paywall's `entry_point` (kept in saved state): [PaywallEntryPoint.LOCKED_HOME]
     * means Home opened it and sits right below. [isTaskRoot] wins, since nothing is below then.
     */
    fun homeButtonRoute(entryPoint: String?, isTaskRoot: Boolean): HomeButtonRoute = when {
        isTaskRoot -> HomeButtonRoute.NEW_TASK_TO_HOME
        entryPoint == PaywallEntryPoint.LOCKED_HOME -> HomeButtonRoute.FINISH
        else -> HomeButtonRoute.CLEAR_TOP_TO_HOME
    }

    /**
     * The screen to show after a recreation. Unknown names fall back to the paywall, and so does
     * Pending without a subscription id (its re-check would have nothing to verify).
     */
    fun restoredState(saved: String?, hasSubscriptionId: Boolean): PaywallUiState {
        val state = PaywallUiState.entries.firstOrNull { it.name == saved } ?: return PaywallUiState.PAYWALL
        return if (state == PaywallUiState.PENDING && !hasSubscriptionId) PaywallUiState.PAYWALL else state
    }
}

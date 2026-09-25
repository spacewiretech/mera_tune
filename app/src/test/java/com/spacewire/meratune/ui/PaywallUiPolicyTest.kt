package com.spacewire.meratune.ui

import com.spacewire.meratune.analytics.PaywallEntryPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaywallUiPolicyTest {

    @Test
    fun upiCancelReturnsQuietlyToThePaywall() {
        listOf("action_cancelled", " ACTION_CANCELLED ").forEach {
            assertEquals(it, PaywallUiState.PAYWALL, PaywallUiPolicy.stateAfterCheckoutFailure(it))
        }
    }

    @Test
    fun realCheckoutFailuresShowFailed() {
        listOf("payment_failed", "no_internet_connection", "order_expired", "no_upi_app_available", "", null).forEach {
            assertEquals("code=$it", PaywallUiState.FAILED, PaywallUiPolicy.stateAfterCheckoutFailure(it))
        }
    }

    @Test
    fun failedReasonsOnlyForPaymentFailed() {
        assertTrue(PaywallUiPolicy.showsFailedReasons("payment_failed"))
        assertTrue(PaywallUiPolicy.showsFailedReasons(" PAYMENT_FAILED "))
        listOf("action_cancelled", "no_internet_connection", "order_expired", "", null).forEach {
            assertFalse("code=$it", PaywallUiPolicy.showsFailedReasons(it))
        }
    }

    @Test
    fun checkoutAndRestoredVerifyOpenPending() {
        PaywallUiState.entries.forEach { current ->
            assertEquals(PaywallUiState.PENDING, PaywallUiPolicy.stateAfterVerifyNotActive(current, VerifyTrigger.CHECKOUT))
            assertEquals(PaywallUiState.PENDING, PaywallUiPolicy.stateAfterVerifyNotActive(current, VerifyTrigger.RESTORED))
        }
    }

    @Test
    fun manualVerifyKeepsTheCurrentScreen() {
        assertEquals(PaywallUiState.PENDING, PaywallUiPolicy.stateAfterVerifyNotActive(PaywallUiState.PENDING, VerifyTrigger.MANUAL))
        // The user went back to the paywall while the re-check ran: it never re-opens Pending.
        assertEquals(PaywallUiState.PAYWALL, PaywallUiPolicy.stateAfterVerifyNotActive(PaywallUiState.PAYWALL, VerifyTrigger.MANUAL))
    }

    @Test
    fun restoredStateFallsBackToThePaywall() {
        assertEquals(PaywallUiState.PENDING, PaywallUiPolicy.restoredState("PENDING", hasSubscriptionId = true))
        assertEquals(PaywallUiState.PAYWALL, PaywallUiPolicy.restoredState("PENDING", hasSubscriptionId = false))
        assertEquals(PaywallUiState.FAILED, PaywallUiPolicy.restoredState("FAILED", hasSubscriptionId = false))
        assertEquals(PaywallUiState.PAYWALL, PaywallUiPolicy.restoredState("PAYWALL", hasSubscriptionId = true))
        assertEquals(PaywallUiState.PAYWALL, PaywallUiPolicy.restoredState("AUTO_CHECK", hasSubscriptionId = true))
        assertEquals(PaywallUiState.PAYWALL, PaywallUiPolicy.restoredState(null, hasSubscriptionId = true))
    }

    @Test
    fun homeButtonFromHomeUncoversIt() {
        assertEquals(HomeButtonRoute.FINISH, PaywallUiPolicy.homeButtonRoute(PaywallEntryPoint.LOCKED_HOME, isTaskRoot = false))
    }

    @Test
    fun homeButtonDeeperInTheTaskClearsBackToHome() {
        listOf(PaywallEntryPoint.LIMIT_SCREEN, PaywallEntryPoint.ONBOARDING, PaywallEntryPoint.WIN_BACK, null).forEach {
            assertEquals("entry=$it", HomeButtonRoute.CLEAR_TOP_TO_HOME, PaywallUiPolicy.homeButtonRoute(it, isTaskRoot = false))
        }
    }

    @Test
    fun homeButtonAsTaskRootStartsHome() {
        listOf(
            PaywallEntryPoint.ONBOARDING,
            PaywallEntryPoint.WIN_BACK,
            PaywallEntryPoint.LOCKED_HOME,
            PaywallEntryPoint.LIMIT_SCREEN,
            null,
        ).forEach {
            assertEquals("entry=$it", HomeButtonRoute.NEW_TASK_TO_HOME, PaywallUiPolicy.homeButtonRoute(it, isTaskRoot = true))
        }
    }
}

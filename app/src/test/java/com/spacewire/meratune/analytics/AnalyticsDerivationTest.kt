package com.spacewire.meratune.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsDerivationTest {

    @Test
    fun userStateMapsKnownStatuses() {
        assertEquals(UserState.TRIAL, UserState.fromStatus("trial"))
        assertEquals(UserState.ACTIVE, UserState.fromStatus("ACTIVE"))
        assertEquals(UserState.CANCELLED, UserState.fromStatus(" cancelled "))
        assertEquals(UserState.EXPIRED, UserState.fromStatus("expired"))
    }

    @Test
    fun userStateNoneBlankAndUnknownAreLocked() {
        listOf("none", "", "   ", null, "pending").forEach {
            assertEquals("status=$it", UserState.LOCKED, UserState.fromStatus(it))
        }
    }

    @Test
    fun paywallFromProcessingIsLimitScreen() {
        listOf("none", "trial", "cancelled", null).forEach {
            assertEquals(PaywallEntryPoint.LIMIT_SCREEN, PaywallEntryPoint.derive(AnalyticsScreen.RINGTONE_PROCESSING, it))
        }
    }

    @Test
    fun paywallFromHomeIsLockedHome() {
        assertEquals(PaywallEntryPoint.LOCKED_HOME, PaywallEntryPoint.derive(AnalyticsScreen.HOME, "none"))
    }

    @Test
    fun paywallAtAppOpenDependsOnStatus() {
        assertEquals(PaywallEntryPoint.ONBOARDING, PaywallEntryPoint.derive(null, "none"))
        assertEquals(PaywallEntryPoint.ONBOARDING, PaywallEntryPoint.derive(null, ""))
        assertEquals(PaywallEntryPoint.ONBOARDING, PaywallEntryPoint.derive(null, "trial"))
        assertEquals(PaywallEntryPoint.WIN_BACK, PaywallEntryPoint.derive(null, "cancelled"))
        assertEquals(PaywallEntryPoint.WIN_BACK, PaywallEntryPoint.derive(null, "Expired"))
        assertNull(PaywallEntryPoint.derive(null, "active"))
    }

    @Test
    fun paywallAfterOnboardingScreens() {
        listOf(
            AnalyticsScreen.LANGUAGE_SELECTION,
            AnalyticsScreen.PHONE_ENTRY,
            AnalyticsScreen.OTP_ENTRY,
            AnalyticsScreen.NAME_ENTRY,
        ).forEach { screen ->
            assertEquals(screen, PaywallEntryPoint.ONBOARDING, PaywallEntryPoint.derive(screen, "none"))
            assertEquals(screen, PaywallEntryPoint.WIN_BACK, PaywallEntryPoint.derive(screen, "cancelled"))
        }
    }

    @Test
    fun paywallFromMembershipWelcomeIsOmitted() {
        assertNull(PaywallEntryPoint.derive(AnalyticsScreen.MEMBERSHIP_WELCOME, "trial"))
        assertTrue(AnalyticsScreen.MEMBERSHIP_WELCOME in AnalyticsScreen.ALL)
        assertEquals("membership_welcome", AnalyticsSource.MEMBERSHIP_WELCOME)
    }

    @Test
    fun paywallFromOtherScreensIsOmitted() {
        assertNull(PaywallEntryPoint.derive(AnalyticsScreen.PROFILE, "none"))
        assertNull(PaywallEntryPoint.derive(AnalyticsScreen.RINGTONE_READY, "cancelled"))
    }
}

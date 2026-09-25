package com.spacewire.meratune.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthNavigatorTest {

    private fun route(
        hasSelectedLanguage: Boolean = true,
        isLoggedIn: Boolean = true,
        status: String = "none",
        browsingWithoutTrial: Boolean = false,
    ) = AuthNavigator.launchDestination(hasSelectedLanguage, isLoggedIn, status, browsingWithoutTrial)

    @Test
    fun onboardingComesFirst() {
        assertEquals(LaunchDestination.LANGUAGE_SELECTION, route(hasSelectedLanguage = false, isLoggedIn = false))
        assertEquals(LaunchDestination.LANGUAGE_SELECTION, route(hasSelectedLanguage = false, browsingWithoutTrial = true))
        assertEquals(LaunchDestination.PHONE_AUTH, route(isLoggedIn = false))
        assertEquals(LaunchDestination.PHONE_AUTH, route(isLoggedIn = false, browsingWithoutTrial = true))
    }

    @Test
    fun noTrialOpensThePaywall() {
        listOf("none", "", "expired", "cancelled").forEach {
            assertEquals("status=$it", LaunchDestination.SUBSCRIPTION, route(status = it))
        }
    }

    @Test
    fun homeButtonChoiceOpensHomeWithoutTrial() {
        listOf("none", "", "expired", "cancelled").forEach {
            assertEquals("status=$it", LaunchDestination.HOME, route(status = it, browsingWithoutTrial = true))
        }
    }

    @Test
    fun membersOpenHome() {
        listOf("trial", "active").forEach {
            assertEquals("status=$it", LaunchDestination.HOME, route(status = it))
            assertEquals("status=$it", LaunchDestination.HOME, route(status = it, browsingWithoutTrial = true))
        }
    }
}

package com.spacewire.meratune.util

import android.content.Context
import android.content.Intent
import com.spacewire.meratune.Home
import com.spacewire.meratune.SubscriptionActivity
import com.spacewire.meratune.analytics.PostAuthDestination
import com.spacewire.meratune.data.User

/** Where the launcher ([com.spacewire.meratune.AuthActivity]) sends the user. */
enum class LaunchDestination { LANGUAGE_SELECTION, PHONE_AUTH, SUBSCRIPTION, HOME }

object AuthNavigator {

    private val statusesNeedingSubscription = setOf("none", "expired", "cancelled")

    fun needsSubscription(status: String): Boolean =
        status.isBlank() || status in statusesNeedingSubscription

    fun needsSubscription(user: User): Boolean = needsSubscription(user.status)

    /**
     * The launcher's route. A user who still needs a subscription sees the paywall, unless they
     * already left it with its Home button in this session ([browsingWithoutTrial]); Home then
     * gates creating and setting behind the paywall.
     */
    fun launchDestination(
        hasSelectedLanguage: Boolean,
        isLoggedIn: Boolean,
        status: String,
        browsingWithoutTrial: Boolean,
    ): LaunchDestination = when {
        !hasSelectedLanguage -> LaunchDestination.LANGUAGE_SELECTION
        !isLoggedIn -> LaunchDestination.PHONE_AUTH
        needsSubscription(status) && !browsingWithoutTrial -> LaunchDestination.SUBSCRIPTION
        else -> LaunchDestination.HOME
    }

    /** The stored session status; Home reads it at tap time (never cached) to gate create and set. */
    fun needsSubscription(context: Context): Boolean = needsSubscription(AuthStore(context).getStatus())

    /** Where [navigateAfterAuth] sends [user]: a [PostAuthDestination] value. */
    fun postAuthDestination(user: User): String =
        if (needsSubscription(user)) PostAuthDestination.SUBSCRIPTION else PostAuthDestination.HOME

    fun navigateAfterAuth(context: Context, user: User) {
        val destination = if (needsSubscription(user)) {
            SubscriptionActivity.intent(context)
        } else {
            Intent(context, Home::class.java)
        }.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(destination)
    }
}

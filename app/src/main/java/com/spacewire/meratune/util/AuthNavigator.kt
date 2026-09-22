package com.spacewire.meratune.util

import android.content.Context
import android.content.Intent
import com.spacewire.meratune.Home
import com.spacewire.meratune.SubscriptionActivity
import com.spacewire.meratune.data.User

object AuthNavigator {

    private val statusesNeedingSubscription = setOf("none", "expired", "cancelled")

    fun needsSubscription(status: String): Boolean =
        status.isBlank() || status in statusesNeedingSubscription

    fun needsSubscription(user: User): Boolean = needsSubscription(user.status)

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

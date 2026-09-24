package com.spacewire.meratune.calltheme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.spacewire.meratune.analytics.CallSurface
import com.spacewire.meratune.analytics.IncomingCallAction
import com.spacewire.meratune.analytics.mixpanelAnalytics

class IncomingCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = when (intent?.action) {
            ACTION_ANSWER -> IncomingCallAction.ANSWER
            ACTION_DECLINE -> IncomingCallAction.DECLINE
            else -> null
        }
        if (action != null) {
            val succeeded = if (action == IncomingCallAction.ANSWER) {
                CallActions.accept(context)
            } else {
                CallActions.decline(context)
            }
            context.mixpanelAnalytics().trackIncomingCallActionTapped(action, CallSurface.NOTIFICATION, succeeded)
        }
        IncomingCallNotifier.dismiss(context)
    }

    companion object {
        const val ACTION_ANSWER = "com.spacewire.meratune.calltheme.ACTION_ANSWER"
        const val ACTION_DECLINE = "com.spacewire.meratune.calltheme.ACTION_DECLINE"
    }
}

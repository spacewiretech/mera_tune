package com.spacewire.meratune.calltheme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class IncomingCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_ANSWER -> CallActions.accept(context)
            ACTION_DECLINE -> CallActions.decline(context)
        }
        IncomingCallNotifier.dismiss(context)
    }

    companion object {
        const val ACTION_ANSWER = "com.spacewire.meratune.calltheme.ACTION_ANSWER"
        const val ACTION_DECLINE = "com.spacewire.meratune.calltheme.ACTION_DECLINE"
    }
}

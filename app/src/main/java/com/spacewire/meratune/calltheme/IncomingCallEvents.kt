package com.spacewire.meratune.calltheme

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import java.io.File

/**
 * Shows a heads-up incoming-call banner for every ringing call.
 * Uses a saved photo when one exists, otherwise a branded fallback.
 *
 * Caller identity is only used when the OS provides a number (no Call Log access).
 * Contact-specific photos still appear in the system Phone app via Contacts.
 */
object IncomingCallEvents {
    @Volatile
    private var isShowing = false
    @Volatile
    private var lastNumber: String? = null

    fun onPhoneState(context: Context, state: String, incomingNumber: String?) {
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING,
            -> showIncoming(context, incomingNumber)

            TelephonyManager.EXTRA_STATE_IDLE,
            TelephonyManager.EXTRA_STATE_OFFHOOK,
            -> dismissIncoming(context)
        }
    }

    fun onCallState(context: Context, state: Int, incomingNumber: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> showIncoming(context, incomingNumber)
            TelephonyManager.CALL_STATE_IDLE,
            TelephonyManager.CALL_STATE_OFFHOOK,
            -> dismissIncoming(context)
        }
    }

    private fun showIncoming(context: Context, incomingNumber: String?) {
        val number = incomingNumber?.trim()?.takeIf { it.isNotEmpty() }
        if (isShowing && (number == null || number == lastNumber)) return
        isShowing = true
        lastNumber = number

        val store = CallThemeStore(context)
        val theme = store.resolveForIncomingNumber(number)
            ?: store.latestWithImage()?.takeIf {
                number != null || it.scope == CallThemeScope.EVERYONE
            }
        val imagePath = theme?.imagePath?.takeIf { path -> File(path).exists() }
        val contactName = theme?.contactName?.takeIf { it.isNotBlank() }
            ?: ContactLookupHelper.findNameByNumber(context, number)

        Log.d(TAG, "Showing incoming UI number=${PhoneMatch.normalizeKey(number)} hasPhoto=${imagePath != null}")
        IncomingCallNotifier.show(
            context = context,
            phoneNumber = number,
            contactName = contactName,
            imagePath = imagePath,
            tuneName = theme?.tuneName,
        )
    }

    private fun dismissIncoming(context: Context) {
        isShowing = false
        lastNumber = null
        IncomingCallNotifier.dismiss(context)
    }

    private const val TAG = "IncomingCallEvents"
}

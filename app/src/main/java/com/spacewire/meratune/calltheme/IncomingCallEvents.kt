package com.spacewire.meratune.calltheme

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import com.spacewire.meratune.analytics.mixpanelAnalytics
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
        // Monitor and receiver both deliver RINGING; a later delivery may only add the number.
        val firstShow = !isShowing
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
        // Read before show(), which wakes the screen.
        val screenLocked = IncomingCallNotifier.isLockedOrScreenOff(context)
        val overlayRequested = IncomingCallNotifier.show(
            context = context,
            phoneNumber = number,
            contactName = contactName,
            imagePath = imagePath,
            tuneName = theme?.tuneName,
        )

        if (firstShow && theme != null) {
            context.mixpanelAnalytics().trackCallThemeDisplayed(
                themeScope = when (theme.scope) {
                    CallThemeScope.EVERYONE -> THEME_SCOPE_EVERYONE
                    CallThemeScope.CONTACT -> THEME_SCOPE_CONTACT
                },
                displayMode = if (overlayRequested) DISPLAY_MODE_OVERLAY else DISPLAY_MODE_NOTIFICATION,
                hasImage = imagePath != null,
                screenLocked = screenLocked,
                numberAvailable = number != null,
            )
        }
    }

    private fun dismissIncoming(context: Context) {
        isShowing = false
        lastNumber = null
        IncomingCallNotifier.dismiss(context)
    }

    private const val TAG = "IncomingCallEvents"
    private const val THEME_SCOPE_EVERYONE = "everyone"
    private const val THEME_SCOPE_CONTACT = "contact"

    /** The overlay start was attempted; whether it appeared is `incoming_call_overlay_displayed`. */
    private const val DISPLAY_MODE_OVERLAY = "overlay_requested"
    private const val DISPLAY_MODE_NOTIFICATION = "heads_up_notification"
}

package com.spacewire.meratune.analytics

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.spacewire.meratune.calltheme.CallActions
import com.spacewire.meratune.model.PaymentApp
import com.spacewire.meratune.util.RingtoneHelper
import java.time.LocalDate
import java.util.Locale

/** `screen_name` / `previous_screen` / `entry_screen` slugs. Only these screens are tracked. */
object AnalyticsScreen {
    const val LANGUAGE_SELECTION = "language_selection"
    const val PHONE_ENTRY = "phone_entry"
    const val OTP_ENTRY = "otp_entry"
    const val NAME_ENTRY = "name_entry"
    const val SUBSCRIPTION = "subscription"
    const val HOME = "home"
    const val PROFILE = "profile"
    const val CREATE_FORM = "create_form"
    const val SONG_PICKER = "song_picker"
    const val RINGTONE_PROCESSING = "ringtone_processing"
    const val RINGTONE_READY = "ringtone_ready"

    /** The member screen shown once after the trial payment verifies. */
    const val MEMBERSHIP_WELCOME = "membership_welcome"

    /** Create flow: ringtones that already sing the entered name (between the form and the picker). */
    const val NAME_RINGTONES = "name_ringtones"

    val ALL = setOf(
        LANGUAGE_SELECTION, PHONE_ENTRY, OTP_ENTRY, NAME_ENTRY, SUBSCRIPTION, HOME,
        PROFILE, CREATE_FORM, SONG_PICKER, RINGTONE_PROCESSING, RINGTONE_READY,
        MEMBERSHIP_WELCOME, NAME_RINGTONES,
    )
}

/** `source` = where the action happened. Screen slugs are valid sources too. */
object AnalyticsSource {
    const val HOME = AnalyticsScreen.HOME
    const val SONG_PICKER = AnalyticsScreen.SONG_PICKER
    const val PROFILE = AnalyticsScreen.PROFILE
    const val PHONE_ENTRY = AnalyticsScreen.PHONE_ENTRY
    const val OTP_ENTRY = AnalyticsScreen.OTP_ENTRY
    const val NAME_ENTRY = AnalyticsScreen.NAME_ENTRY
    const val SUBSCRIPTION = AnalyticsScreen.SUBSCRIPTION
    const val RINGTONE_PROCESSING = AnalyticsScreen.RINGTONE_PROCESSING
    const val MEMBERSHIP_WELCOME = AnalyticsScreen.MEMBERSHIP_WELCOME

    /** The existing name ringtones step: row previews and its Set flow. */
    const val NAME_RINGTONES = AnalyticsScreen.NAME_RINGTONES

    /** The Ready screen (legacy name kept for `ringtone_set` and the server `ringtone_created`). */
    const val CREATION_FLOW = "creation_flow"

    /** The Home search empty-state Create CTA (`create_ringtone_cta_tapped`). */
    const val SEARCH_BAR = "search_bar"

    /** The same empty-state CTA when the "{name} Tunes" chip (no search query) found no tune. */
    const val MY_NAME_CHIP = "my_name_chip"

    /** `tune_played` on Home while a search query is active. */
    const val SEARCH_RESULTS = "search_results"
    const val UNKNOWN = "unknown"
}

/** `ringtone_creation_started.entry_point`: how the create form was reached. */
object CreationEntryPoint {
    const val SEARCH_BAR = "search_bar"

    /** The Home empty-state CTA under the "{name} Tunes" chip, without a search query. */
    const val MY_NAME_CHIP = "my_name_chip"
    const val READY_SCREEN = "ready_screen"
    const val PROCESSING = "processing"

    /** The member screen's CTA and checklist rows, right after the trial payment. */
    const val POST_PURCHASE = "post_purchase"
}

/** `subscription_screen_viewed` / `paywall_dismissed` `entry_point`. */
object PaywallEntryPoint {
    const val ONBOARDING = "onboarding"
    const val LIMIT_SCREEN = "limit_screen"
    const val WIN_BACK = "win_back"
    const val LOCKED_HOME = "locked_home"

    private val ONBOARDING_SCREENS = setOf(
        AnalyticsScreen.LANGUAGE_SELECTION,
        AnalyticsScreen.PHONE_ENTRY,
        AnalyticsScreen.OTP_ENTRY,
        AnalyticsScreen.NAME_ENTRY,
    )

    /**
     * [previousScreen] `null` is the session's entry screen (app open). [status] is the AuthStore
     * status. `null` when nothing matches (omitted).
     */
    fun derive(previousScreen: String?, status: String?): String? {
        val normalized = status?.trim()?.lowercase(Locale.ROOT).orEmpty().ifEmpty { "none" }
        return when {
            previousScreen == AnalyticsScreen.RINGTONE_PROCESSING -> LIMIT_SCREEN
            previousScreen == AnalyticsScreen.HOME -> LOCKED_HOME
            previousScreen != null && previousScreen !in ONBOARDING_SCREENS -> null
            normalized == "cancelled" || normalized == "expired" -> WIN_BACK
            normalized == "none" || normalized == "trial" -> ONBOARDING
            else -> null
        }
    }
}

object PaywallDismissMethod {
    const val SYSTEM_BACK = "system_back"

    /** The Home button on the paywall's video card (it replaced the header close X before release). */
    const val HOME_BUTTON = "home_button"
}

/** `payment_app` / `previous_payment_app` on the paywall events. */
object PaymentAppSlug {
    const val PHONEPE = "phonepe"
    const val GOOGLE_PAY = "google_pay"
    const val PAYTM = "paytm"
    const val BHIM = "bhim"

    /** Cashfree's hosted checkout, where the user enters a UPI ID (no UPI app intent). */
    const val UPI_ID = "upi_id"

    fun of(app: PaymentApp): String = when (app) {
        PaymentApp.PHONEPE -> PHONEPE
        PaymentApp.GOOGLE_PAY -> GOOGLE_PAY
        PaymentApp.PAYTM -> PAYTM
        PaymentApp.BHIM -> BHIM
        PaymentApp.UPI_ID -> UPI_ID
    }
}

/** `user_state` super property, from the AuthStore status. */
object UserState {
    const val LOCKED = "locked"
    const val TRIAL = "trial"
    const val ACTIVE = "active"
    const val CANCELLED = "cancelled"
    const val EXPIRED = "expired"

    /** `none`, blank and unknown statuses are [LOCKED]. */
    fun fromStatus(status: String?): String = when (status?.trim()?.lowercase(Locale.ROOT)) {
        TRIAL -> TRIAL
        ACTIVE -> ACTIVE
        CANCELLED -> CANCELLED
        EXPIRED -> EXPIRED
        else -> LOCKED
    }
}

/** `creation_limit_reached.limit_type`; trial / cycle limits do not exist yet. */
object CreationLimitType {
    const val DAILY = "daily"
}

object StartType {
    const val COLD = "cold"
    const val WARM = "warm"
}

object LogoutReason {
    const val USER_INITIATED = "user_initiated"
    const val SESSION_EXPIRED = "session_expired"
}

/** Shared `failure_reason` values; units add their own bounded values on top. */
object FailureReason {
    const val NETWORK = "network"
    const val TIMEOUT = "timeout"
    const val USER_CANCELLED = "user_cancelled"
    const val PERMISSION_DENIED = "permission_denied"
    const val UNKNOWN = "unknown"
}

/** `trigger` for loads and attempts. */
object AnalyticsTrigger {
    const val INITIAL = "initial"
    const val RETRY = "retry"
    const val RESTORED = "restored"
    const val CATEGORY_CHANGE = "category_change"
    const val RESET = "reset"
    const val HINDI_FALLBACK = "hindi_fallback"
}

/** `permission` values for `permission_prompt_answered`. */
object AnalyticsPermissionKey {
    const val READ_PHONE_STATE = "read_phone_state"
    const val ANSWER_PHONE_CALLS = "answer_phone_calls"
    const val READ_CONTACTS = "read_contacts"
    const val WRITE_CONTACTS = "write_contacts"
    const val POST_NOTIFICATIONS = "post_notifications"
    const val WRITE_EXTERNAL_STORAGE = "write_external_storage"
    const val WRITE_SETTINGS = "write_settings"

    private val byManifest = mapOf(
        Manifest.permission.READ_PHONE_STATE to READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS to ANSWER_PHONE_CALLS,
        Manifest.permission.READ_CONTACTS to READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS to WRITE_CONTACTS,
        Manifest.permission.POST_NOTIFICATIONS to POST_NOTIFICATIONS,
        Manifest.permission.WRITE_EXTERNAL_STORAGE to WRITE_EXTERNAL_STORAGE,
    )

    /** `null` for permissions the app does not track. */
    fun fromManifest(manifestPermission: String): String? = byManifest[manifestPermission]
}

object PromptContext {
    const val STARTUP = "startup"
    const val SET_RINGTONE = "set_ringtone"
}

object ExternalLink {
    const val TERMS = "terms"
    const val PRIVACY_POLICY = "privacy_policy"
    const val HELP_SUPPORT = "help_support"
    const val DELETE_ACCOUNT = "delete_account"
}

object OtpEntryMethod {
    const val MANUAL = "manual"
    const val SMS_RETRIEVER = "sms_retriever"
    const val SMS_CONSENT = "sms_consent"
}

object PostAuthDestination {
    const val HOME = "home"
    const val SUBSCRIPTION = "subscription"
}

/** `selection` for `category_filtered`. */
object FilterSelection {
    const val SELECTED = "selected"
    const val DESELECTED = "deselected"
    const val ALL = "all"
}

/** `end_reason` for `tune_play_ended`. */
object PlayEndReason {
    const val COMPLETED = "completed"
    const val STOPPED = "stopped"
    const val ERROR = "error"
}

/** `action` for `generation_error_action_taken`. */
object GenerationErrorAction {
    const val RETRY = "retry"
    const val LOGIN_AGAIN = "login_again"
    const val SUBSCRIBE = "subscribe"
    const val CHANGE_LANGUAGE = "change_language"
    const val CHOOSE_ANOTHER = "choose_another"
}

/** `action` for `ringtone_ready_action_tapped`. */
object ReadyAction {
    const val CHANGE_SONG = "change_song"
    const val MAKE_ANOTHER = "make_another"
    const val GO_HOME = "go_home"
    const val BACK_BUTTON = "back_button"
}

/** `stage` for `ringtone_set_failed`. */
object SetFailureStage {
    const val MODE_SHEET = "mode_sheet"
    const val STORAGE_PERMISSION = "storage_permission"
    const val WRITE_SETTINGS_PERMISSION = "write_settings_permission"
    const val CONTACTS_PERMISSION = "contacts_permission"
    const val PHONE_PERMISSION = "phone_permission"
    const val CONTACT_PICKER = "contact_picker"
    const val PHOTO_SHEET = "photo_sheet"
    const val DOWNLOAD = "download"
    const val SAVE = "save"
    const val SET_DEFAULT = "set_default"
    const val THEME_SAVE = "theme_save"
}

/** `failure_reason` for `ringtone_set_failed`, on top of [FailureReason]. */
object SetFailureReason {
    const val NO_VALID_PHONE_NUMBER = "no_valid_phone_number"
    const val MEDIA_STORE_ERROR = "media_store_error"
    const val SECURITY_EXCEPTION = "security_exception"
    const val STATE_LOST = "state_lost"
}

object IncomingCallAction {
    const val ANSWER = "answer"
    const val DECLINE = "decline"
}

object CallSurface {
    const val OVERLAY = "overlay"
    const val NOTIFICATION = "notification"
}

object LaunchTrigger {
    const val AUTO = "auto"
    const val NOTIFICATION_TAP = "notification_tap"
}

data class PermissionSnapshot(
    val phoneStateGranted: Boolean,
    val contactsGranted: Boolean,
    val notificationsEnabled: Boolean,
    val writeSettingsGranted: Boolean,
    val callControlGranted: Boolean,
)

object AnalyticsPermissions {
    fun snapshot(context: Context): PermissionSnapshot {
        val appContext = context.applicationContext
        return PermissionSnapshot(
            phoneStateGranted = isGranted(appContext, Manifest.permission.READ_PHONE_STATE),
            contactsGranted = isGranted(appContext, Manifest.permission.READ_CONTACTS) &&
                isGranted(appContext, Manifest.permission.WRITE_CONTACTS),
            notificationsEnabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled(),
            writeSettingsGranted = !RingtoneHelper.needsWriteSettingsPermission(appContext),
            callControlGranted = CallActions.canControlCalls(appContext),
        )
    }

    /** Only meaningful in a permission-result callback, after the dialog was shown. */
    fun isPermanentlyDenied(activity: Activity, manifestPermission: String): Boolean =
        !isGranted(activity, manifestPermission) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, manifestPermission)

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/** Analytics bookkeeping in `analytics_state.xml`. */
class AnalyticsStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var installReferrerDone: Boolean
        get() = prefs.getBoolean(KEY_INSTALL_REFERRER_DONE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_INSTALL_REFERRER_DONE, value).apply()
        }

    /** Wall clock of the last move to the background; null while in the foreground. */
    var backgroundedAtWallMs: Long?
        get() = prefs.getLong(KEY_BACKGROUNDED_AT_WALL_MS, -1L).takeIf { it >= 0L }
        set(value) {
            val editor = prefs.edit()
            if (value == null) {
                editor.remove(KEY_BACKGROUNDED_AT_WALL_MS)
            } else {
                editor.putLong(KEY_BACKGROUNDED_AT_WALL_MS, value)
            }
            editor.apply()
        }

    /**
     * True the first time [permissionKey] is reported and whenever [granted] differs from the last
     * reported value; records [granted] when it returns true.
     */
    fun shouldReportStartupPermission(permissionKey: String, granted: Boolean): Boolean {
        val key = KEY_STARTUP_PERMISSION_PREFIX + permissionKey
        if (prefs.contains(key) && prefs.getBoolean(key, false) == granted) return false
        prefs.edit().putBoolean(key, granted).apply()
        return true
    }

    companion object {
        const val PREFS_NAME = "analytics_state"
        private const val KEY_INSTALL_REFERRER_DONE = "install_referrer_done"
        private const val KEY_BACKGROUNDED_AT_WALL_MS = "backgrounded_at_wall_ms"
        private const val KEY_STARTUP_PERMISSION_PREFIX = "startup_permission_"
    }
}

/** Pure daily-cap bookkeeping: one counter per user per day, reset when either changes. */
internal object DailyCapPolicy {
    data class State(val day: String, val userId: Long, val count: Int)

    data class Decision(val allowed: Boolean, val state: State)

    fun acquire(state: State?, today: String, userId: Long, limit: Int): Decision {
        val current = state?.takeIf { it.day == today && it.userId == userId } ?: State(today, userId, 0)
        if (current.count >= limit) return Decision(allowed = false, state = current)
        return Decision(allowed = true, state = current.copy(count = current.count + 1))
    }
}

/**
 * Shared cap for background incoming-call events (device-local day). Committed synchronously
 * because a receiver-started process can die right after tracking.
 */
class AnalyticsDailyCap(context: Context, private val limit: Int = DEFAULT_LIMIT) {
    private val prefs = context.applicationContext
        .getSharedPreferences(AnalyticsStateStore.PREFS_NAME, Context.MODE_PRIVATE)

    fun tryAcquire(userId: Long): Boolean = synchronized(LOCK) {
        val stored = prefs.getString(KEY_DAY, null)?.let { day ->
            DailyCapPolicy.State(day, prefs.getLong(KEY_USER_ID, -1L), prefs.getInt(KEY_COUNT, 0))
        }
        val decision = DailyCapPolicy.acquire(stored, LocalDate.now().toString(), userId, limit)
        if (decision.allowed) {
            prefs.edit()
                .putString(KEY_DAY, decision.state.day)
                .putLong(KEY_USER_ID, decision.state.userId)
                .putInt(KEY_COUNT, decision.state.count)
                .commit()
        }
        decision.allowed
    }

    companion object {
        const val DEFAULT_LIMIT = 10
        private val LOCK = Any()
        private const val KEY_DAY = "call_event_cap_day"
        private const val KEY_USER_ID = "call_event_cap_user_id"
        private const val KEY_COUNT = "call_event_cap_count"
    }
}

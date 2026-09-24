package com.spacewire.meratune.analytics

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import com.spacewire.meratune.ChooseSongActivity
import com.spacewire.meratune.CreateRingtoneActivity
import com.spacewire.meratune.Home
import com.spacewire.meratune.LanguageSelectionActivity
import com.spacewire.meratune.OtpVerificationActivity
import com.spacewire.meratune.PhoneAuthActivity
import com.spacewire.meratune.ProfileActivity
import com.spacewire.meratune.RingtoneProcessingActivity
import com.spacewire.meratune.RingtoneReadyActivity
import com.spacewire.meratune.SignUpNameActivity
import com.spacewire.meratune.SubscriptionActivity
import com.spacewire.meratune.calltheme.IncomingCallThemeActivity

/**
 * App-shell analytics: `app_opened`, `screen_viewed` and the `previous_screen` bookkeeping.
 * The incoming-call overlay is ignored entirely, so ringing calls never count as a foreground.
 * Lifecycle callbacks run on the main thread.
 */
class AnalyticsLifecycleCallbacks(
    private val analytics: MixpanelAnalytics,
    private val stateStore: AnalyticsStateStore,
) : Application.ActivityLifecycleCallbacks {

    private var startedCount = 0
    private var hasForegrounded = false
    private var backgroundedAtMs: Long? = null

    /** Whether the process's first activity was restored from saved state (process death). */
    private var restoredProcess: Boolean? = null

    /** The coming foreground was already evaluated for `app_opened` in [onActivityCreated]. */
    private var openDecided = false

    /** Created and not yet destroyed activities (router and third-party ones included). */
    private var liveCount = 0

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is IncomingCallThemeActivity) return
        if (restoredProcess == null) restoredProcess = savedInstanceState != null
        // A fresh activity in an emptied task (backed out, relaunched within 5 min): no app_opened,
        // but the old session's last screen is gone, so it is nobody's previous_screen.
        // Config-change recreations carry saved state and are skipped.
        if (liveCount == 0 && savedInstanceState == null) analytics.clearPreviousScreen()
        liveCount++
        val screenName = screenName(activity) ?: return
        // Decided inside super.onCreate(), so the entry screen's own onCreate events follow app_opened.
        if (startedCount == 0 && !openDecided) {
            openDecided = true
            trackAppOpenedIfDue(activity)
        }
        if (savedInstanceState != null) return
        analytics.trackScreenViewed(screenName)
    }

    override fun onActivityStarted(activity: Activity) {
        if (activity is IncomingCallThemeActivity) return
        startedCount++
        if (startedCount != 1) return
        if (!openDecided) trackAppOpenedIfDue(activity)
        openDecided = false
    }

    override fun onActivityResumed(activity: Activity) {
        screenName(activity)?.let(analytics::onScreenResumed)
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) {
        if (activity is IncomingCallThemeActivity || startedCount == 0) return
        startedCount--
        if (startedCount == 0 && !activity.isChangingConfigurations) {
            backgroundedAtMs = SystemClock.elapsedRealtime()
            // Persisted for a return that lands in a new process (killed during a UPI or camera trip).
            stateStore.backgroundedAtWallMs = System.currentTimeMillis()
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is IncomingCallThemeActivity || liveCount == 0) return
        liveCount--
    }

    /**
     * First foreground in the process (cold), or back after at least [MIN_BACKGROUND_MS] (warm).
     * Shorter trips (UPI app, Settings, contact picker, camera) and config changes are not opens,
     * including when the process died meanwhile and the activity is restored in a new one.
     */
    private fun trackAppOpenedIfDue(activity: Activity) {
        val backgroundedAt = backgroundedAtMs
        backgroundedAtMs = null
        val startType = when {
            hasForegrounded ->
                if (backgroundedAt != null && SystemClock.elapsedRealtime() - backgroundedAt >= MIN_BACKGROUND_MS) {
                    StartType.WARM
                } else {
                    null
                }
            restoredProcess == true -> if (isRecent(stateStore.backgroundedAtWallMs)) null else StartType.WARM
            else -> StartType.COLD
        }
        if (stateStore.backgroundedAtWallMs != null) stateStore.backgroundedAtWallMs = null
        if (startType != null) analytics.trackAppOpened(startType, screenName(activity))
        if (!hasForegrounded) {
            hasForegrounded = true
            // Not from Application.onCreate: the PHONE_STATE receiver cold-starts the process in the background.
            InstallReferrerTracker.fetchOnce(activity)
        }
    }

    private fun isRecent(wallMs: Long?): Boolean =
        wallMs != null && System.currentTimeMillis() - wallMs in 0 until MIN_BACKGROUND_MS

    /** Exact class match, so the router, dead and third-party activities stay untracked. */
    private fun screenName(activity: Activity): String? = SCREENS[activity.javaClass]

    private companion object {
        const val MIN_BACKGROUND_MS = 5 * 60 * 1000L

        val SCREENS: Map<Class<out Activity>, String> = mapOf(
            LanguageSelectionActivity::class.java to AnalyticsScreen.LANGUAGE_SELECTION,
            PhoneAuthActivity::class.java to AnalyticsScreen.PHONE_ENTRY,
            OtpVerificationActivity::class.java to AnalyticsScreen.OTP_ENTRY,
            SignUpNameActivity::class.java to AnalyticsScreen.NAME_ENTRY,
            SubscriptionActivity::class.java to AnalyticsScreen.SUBSCRIPTION,
            Home::class.java to AnalyticsScreen.HOME,
            ProfileActivity::class.java to AnalyticsScreen.PROFILE,
            CreateRingtoneActivity::class.java to AnalyticsScreen.CREATE_FORM,
            ChooseSongActivity::class.java to AnalyticsScreen.SONG_PICKER,
            RingtoneProcessingActivity::class.java to AnalyticsScreen.RINGTONE_PROCESSING,
            RingtoneReadyActivity::class.java to AnalyticsScreen.RINGTONE_READY,
        )
    }
}

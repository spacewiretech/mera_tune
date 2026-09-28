package com.spacewire.meratune.analytics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import com.android.installreferrer.api.InstallReferrerStateListener

/**
 * Reads the Play install referrer once per fresh install, saves its [InstallAttribution] (so every
 * later event and a later identify carry it) and sends `install_attributed`. Only the `utm_*` keys
 * leave the device; `gclid` / `fbclid` only decide the source. Main thread only.
 */
object InstallReferrerTracker {

    private const val MAX_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 10_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var inFlight = false

    fun fetchOnce(context: Context) {
        val appContext = context.applicationContext
        val stateStore = AnalyticsStateStore(appContext)
        if (inFlight || stateStore.installReferrerDone) return
        // Fresh installs only (lastUpdateTime == firstInstallTime): an updated package was installed
        // by an older build, or already had its chance, so its referrer is not an install now.
        if (wasUpdatedSinceInstall(appContext)) {
            stateStore.installReferrerDone = true
            return
        }
        inFlight = true
        connect(appContext, attempt = 1)
    }

    private fun connect(context: Context, attempt: Int) {
        val client = InstallReferrerClient.newBuilder(context).build()
        var finished = false
        val listener = object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                if (finished) return
                finished = true
                when (responseCode) {
                    InstallReferrerResponse.OK -> {
                        // RemoteException (Play died mid-call) is transient: retry.
                        val referrer = try {
                            client.installReferrer?.installReferrer.orEmpty()
                        } catch (_: Exception) {
                            null
                        }
                        end(client)
                        if (referrer == null) retryLater(context, attempt) else report(context, referrer)
                    }
                    InstallReferrerResponse.SERVICE_UNAVAILABLE,
                    InstallReferrerResponse.SERVICE_DISCONNECTED -> {
                        end(client)
                        retryLater(context, attempt)
                    }
                    // FEATURE_NOT_SUPPORTED (no Play Store), DEVELOPER_ERROR, PERMISSION_ERROR: permanent.
                    else -> {
                        end(client)
                        AnalyticsStateStore(context).installReferrerDone = true
                        inFlight = false
                    }
                }
            }

            override fun onInstallReferrerServiceDisconnected() {
                if (finished) return
                finished = true
                end(client)
                retryLater(context, attempt)
            }
        }
        try {
            client.startConnection(listener)
        } catch (_: RuntimeException) {
            finished = true
            end(client)
            inFlight = false
        }
    }

    private fun report(context: Context, referrer: String) {
        val attribution = InstallAttribution.fromReferrer(referrer)
        val stateStore = AnalyticsStateStore(context)
        // Saved before sending: the super properties and the identify-time set_once read it.
        stateStore.installAttribution = attribution
        stateStore.installReferrerDone = true
        inFlight = false
        MixpanelAnalytics.getInstance(context).trackInstallAttributed(attribution)
    }

    private fun wasUpdatedSinceInstall(context: Context): Boolean = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.lastUpdateTime > info.firstInstallTime
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Gives up for this process after [MAX_ATTEMPTS]; the flag stays unset, so the next process retries. */
    private fun retryLater(context: Context, attempt: Int) {
        if (attempt >= MAX_ATTEMPTS) {
            inFlight = false
            return
        }
        mainHandler.postDelayed({ connect(context, attempt + 1) }, RETRY_DELAY_MS * attempt)
    }

    private fun end(client: InstallReferrerClient) {
        runCatching { client.endConnection() }
    }
}

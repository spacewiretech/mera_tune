package com.spacewire.meratune.analytics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import com.android.installreferrer.api.InstallReferrerStateListener
import java.net.URLDecoder
import java.util.Locale

/**
 * Reads the Play install referrer once per fresh install and sends `install_attributed`. Only the
 * whitelisted `utm_*` keys leave the device; `gclid` is reduced to `has_gclid`. Main thread only.
 */
object InstallReferrerTracker {

    private const val MAX_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 10_000L
    private const val UTM_SOURCE = "utm_source"
    private const val UTM_MEDIUM = "utm_medium"
    private const val UTM_CAMPAIGN = "utm_campaign"
    private const val GCLID = "gclid"
    private val KEPT_KEYS = setOf(UTM_SOURCE, UTM_MEDIUM, UTM_CAMPAIGN, GCLID)

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
        AnalyticsStateStore(context).installReferrerDone = true
        inFlight = false
        val params = parseReferrer(referrer)
        MixpanelAnalytics.getInstance(context).trackInstallAttributed(
            utmSource = params[UTM_SOURCE],
            utmMedium = params[UTM_MEDIUM],
            utmCampaign = params[UTM_CAMPAIGN],
            hasGclid = params.containsKey(GCLID),
        )
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

    /** Whitelisted, non-blank keys only. Some referrers arrive URL-encoded as a whole. */
    private fun parseReferrer(referrer: String): Map<String, String> {
        val query = if ('=' !in referrer && referrer.contains("%3D", ignoreCase = true)) decode(referrer) else referrer
        return query.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=', "").trim().lowercase(Locale.ROOT)
            if (key !in KEPT_KEYS) return@mapNotNull null
            val value = decode(pair.substringAfter('=')).trim()
            if (value.isEmpty()) null else key to value
        }.toMap()
    }

    private fun decode(value: String): String =
        try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: IllegalArgumentException) {
            value
        }
}

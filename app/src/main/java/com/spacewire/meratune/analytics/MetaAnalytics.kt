package com.spacewire.meratune.analytics

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.facebook.FacebookSdk
import com.facebook.LoggingBehavior
import com.facebook.appevents.AppEventsConstants
import com.facebook.appevents.AppEventsLogger
import com.spacewire.meratune.BuildConfig
import com.spacewire.meratune.data.User
import com.spacewire.meratune.util.AuthStore

class MetaAnalytics private constructor(context: Context) {

    // The SDK auto-initializes from the manifest app ID before Application.onCreate().
    // When facebook.app_id is missing from local.properties the ID is blank, the SDK
    // skips initialization, and every AppEventsLogger call would throw. Degrade to
    // no-ops in that case so local builds still run.
    private val logger: AppEventsLogger? =
        if (FacebookSdk.isInitialized()) AppEventsLogger.newLogger(context.applicationContext) else null

    init {
        if (logger == null) {
            Log.w(TAG, "Facebook SDK not initialized (missing facebook.app_id?); Meta events disabled")
        } else {
            if (BuildConfig.DEBUG) {
                FacebookSdk.setIsDebugEnabled(true)
                FacebookSdk.addLoggingBehavior(LoggingBehavior.APP_EVENTS)
            }
            restoreIdentity(context)
        }
    }

    fun identifyUser(user: User) {
        logger ?: return
        AppEventsLogger.setUserID(user.id.toString())
    }

    fun clearUserId() {
        logger ?: return
        AppEventsLogger.clearUserID()
    }

    fun trackCompleteRegistration(registrationMethod: String) {
        val logger = logger ?: return
        val params = Bundle()
        params.putString(AppEventsConstants.EVENT_PARAM_REGISTRATION_METHOD, registrationMethod)
        logger.logEvent(AppEventsConstants.EVENT_NAME_COMPLETED_REGISTRATION, params)
    }

    fun trackSubscriptionScreenViewed() {
        val logger = logger ?: return
        val params = Bundle()
        params.putString(AppEventsConstants.EVENT_PARAM_CONTENT_TYPE, "subscription")
        logger.logEvent(AppEventsConstants.EVENT_NAME_VIEWED_CONTENT, params)
    }

    fun trackSubscriptionStarted(authAmount: Double?, recurringAmount: Double?) {
        val logger = logger ?: return
        val params = Bundle()
        params.putString(AppEventsConstants.EVENT_PARAM_CURRENCY, CURRENCY)
        params.putString(AppEventsConstants.EVENT_PARAM_CONTENT_TYPE, "subscription")
        recurringAmount?.let { params.putDouble("recurring_amount", it) }
        logger.logEvent(
            AppEventsConstants.EVENT_NAME_INITIATED_CHECKOUT,
            authAmount ?: 0.0,
            params,
        )
    }

    fun trackTrialPaymentCompleted(amount: Double, subscriptionId: String) {
        val logger = logger ?: return
        val params = Bundle()
        params.putString(AppEventsConstants.EVENT_PARAM_CURRENCY, CURRENCY)
        params.putString(AppEventsConstants.EVENT_PARAM_CONTENT_TYPE, "subscription")
        params.putString("subscription_id", subscriptionId)
        logger.logEvent(AppEventsConstants.EVENT_NAME_PURCHASED, amount, params)
    }

    fun flush() {
        logger?.flush()
    }

    private fun restoreIdentity(context: Context) {
        val authStore = AuthStore(context)
        if (!authStore.isLoggedIn()) return
        AppEventsLogger.setUserID(authStore.getUserId().toString())
    }

    companion object {
        private const val TAG = "MetaAnalytics"
        private const val CURRENCY = "INR"

        @Volatile
        private var instance: MetaAnalytics? = null

        fun init(context: Context): MetaAnalytics {
            return instance ?: synchronized(this) {
                instance ?: MetaAnalytics(context.applicationContext).also { instance = it }
            }
        }

        fun getInstance(context: Context): MetaAnalytics {
            return instance ?: init(context)
        }
    }
}

fun Context.metaAnalytics(): MetaAnalytics = MetaAnalytics.getInstance(this)

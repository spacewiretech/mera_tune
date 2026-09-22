package com.spacewire.meratune.analytics

import android.content.Context
import android.os.Bundle
import com.facebook.FacebookSdk
import com.facebook.LoggingBehavior
import com.facebook.appevents.AppEventsConstants
import com.facebook.appevents.AppEventsLogger
import com.spacewire.meratune.BuildConfig
import com.spacewire.meratune.data.User
import com.spacewire.meratune.util.AuthStore

class MetaAnalytics private constructor(context: Context) {

    private val logger: AppEventsLogger = AppEventsLogger.newLogger(context.applicationContext)

    init {
        if (BuildConfig.DEBUG) {
            FacebookSdk.setIsDebugEnabled(true)
            FacebookSdk.addLoggingBehavior(LoggingBehavior.APP_EVENTS)
        }
        restoreIdentity(context)
    }

    fun identifyUser(user: User) {
        AppEventsLogger.setUserID(user.id.toString())
    }

    fun clearUserId() {
        AppEventsLogger.clearUserID()
    }

    fun trackCompleteRegistration(registrationMethod: String) {
        val params = Bundle()
        params.putString(AppEventsConstants.EVENT_PARAM_REGISTRATION_METHOD, registrationMethod)
        logger.logEvent(AppEventsConstants.EVENT_NAME_COMPLETED_REGISTRATION, params)
    }

    fun trackSubscriptionScreenViewed() {
        val params = Bundle()
        params.putString(AppEventsConstants.EVENT_PARAM_CONTENT_TYPE, "subscription")
        logger.logEvent(AppEventsConstants.EVENT_NAME_VIEWED_CONTENT, params)
    }

    fun trackSubscriptionStarted(authAmount: Double?, recurringAmount: Double?) {
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
        val params = Bundle()
        params.putString(AppEventsConstants.EVENT_PARAM_CURRENCY, CURRENCY)
        params.putString(AppEventsConstants.EVENT_PARAM_CONTENT_TYPE, "subscription")
        params.putString("subscription_id", subscriptionId)
        logger.logEvent(AppEventsConstants.EVENT_NAME_PURCHASED, amount, params)
    }

    fun flush() {
        logger.flush()
    }

    private fun restoreIdentity(context: Context) {
        val authStore = AuthStore(context)
        if (!authStore.isLoggedIn()) return
        AppEventsLogger.setUserID(authStore.getUserId().toString())
    }

    companion object {
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

package com.spacewire.meratune.analytics

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.spacewire.meratune.BuildConfig
import com.spacewire.meratune.data.User
import com.spacewire.meratune.util.AuthStore

class FirebasePurchaseAnalytics private constructor(context: Context) {

    private val analytics: FirebaseAnalytics =
        FirebaseAnalytics.getInstance(context.applicationContext)

    init {
        restoreIdentity(context)
    }

    fun identifyUser(user: User) {
        analytics.setUserId(user.id.toString())
    }

    fun clearUserId() {
        analytics.setUserId(null)
    }

    fun trackTrialPaymentCompleted(amount: Double, subscriptionId: String) {
        val item = Bundle().apply {
            putString(FirebaseAnalytics.Param.ITEM_ID, subscriptionId)
            putString(FirebaseAnalytics.Param.ITEM_NAME, "subscription")
            putString(FirebaseAnalytics.Param.ITEM_CATEGORY, "subscription")
            putDouble(FirebaseAnalytics.Param.PRICE, amount)
            putLong(FirebaseAnalytics.Param.QUANTITY, 1)
        }
        val params = Bundle().apply {
            putDouble(FirebaseAnalytics.Param.VALUE, amount)
            putString(FirebaseAnalytics.Param.CURRENCY, CURRENCY)
            putString(FirebaseAnalytics.Param.TRANSACTION_ID, subscriptionId)
            putParcelableArrayList(FirebaseAnalytics.Param.ITEMS, arrayListOf(item))
        }
        analytics.logEvent(FirebaseAnalytics.Event.PURCHASE, params)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "purchase value=$amount currency=$CURRENCY transaction_id=$subscriptionId")
        }
    }

    private fun restoreIdentity(context: Context) {
        val authStore = AuthStore(context)
        if (!authStore.isLoggedIn()) return
        analytics.setUserId(authStore.getUserId().toString())
    }

    companion object {
        private const val CURRENCY = "INR"
        private const val TAG = "FirebasePurchase"

        @Volatile
        private var instance: FirebasePurchaseAnalytics? = null

        fun init(context: Context): FirebasePurchaseAnalytics {
            return instance ?: synchronized(this) {
                instance ?: FirebasePurchaseAnalytics(context.applicationContext).also { instance = it }
            }
        }

        fun getInstance(context: Context): FirebasePurchaseAnalytics {
            return instance ?: init(context)
        }
    }
}

fun Context.firebaseAnalytics(): FirebasePurchaseAnalytics = FirebasePurchaseAnalytics.getInstance(this)

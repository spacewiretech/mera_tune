package com.spacewire.meratune.calltheme

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

object IncomingCallMonitor {
    @Volatile
    private var started = false
    private var telephonyCallback: TelephonyCallback? = null
    private var phoneStateListener: PhoneStateListener? = null

    fun start(context: Context) {
        if (started) return
        val appContext = context.applicationContext
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = appContext.getSystemService(TelephonyManager::class.java) ?: return
        started = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    IncomingCallEvents.onCallState(appContext, state, null)
                }
            }
            telephonyCallback = callback
            runCatching {
                manager.registerTelephonyCallback(appContext.mainExecutor, callback)
            }.onFailure { error ->
                started = false
                Log.e(TAG, "Could not register telephony callback", error)
            }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    IncomingCallEvents.onCallState(appContext, state, phoneNumber)
                }
            }
            phoneStateListener = listener
            @Suppress("DEPRECATION")
            manager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }
        Log.d(TAG, "Incoming call monitor started")
    }

    private const val TAG = "IncomingCallMonitor"
}

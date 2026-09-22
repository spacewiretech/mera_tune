package com.spacewire.meratune.calltheme

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat

object CallActions {
    private const val TAG = "CallActions"

    fun canControlCalls(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun accept(context: Context): Boolean {
        if (!canControlCalls(context)) return false
        return runCatching {
            val telecom = context.getSystemService(TelecomManager::class.java) ?: return@runCatching false
            @Suppress("DEPRECATION")
            telecom.acceptRingingCall()
            true
        }.onFailure { error ->
            Log.e(TAG, "Failed to accept call", error)
        }.getOrDefault(false)
    }

    fun decline(context: Context): Boolean {
        if (!canControlCalls(context)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return runCatching {
            val telecom = context.getSystemService(TelecomManager::class.java) ?: return@runCatching false
            @Suppress("DEPRECATION")
            telecom.endCall()
        }.onFailure { error ->
            Log.e(TAG, "Failed to decline call", error)
        }.getOrDefault(false)
    }
}

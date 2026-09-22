package com.spacewire.meratune.util

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status

class SmsOtpFetcher(
    private val activity: ComponentActivity,
    private val otpLength: Int = 4,
    private val onOtpReceived: (String) -> Unit,
) {
    private val smsClient = SmsRetriever.getClient(activity)
    private var receiverRegistered = false

    private val consentLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val message = result.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE).orEmpty()
        extractOtp(message)?.let(onOtpReceived)
    }

    private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SmsRetriever.SMS_RETRIEVED_ACTION) return

            val extras = intent.extras ?: return
            val status = extras.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return

            when (status.statusCode) {
                CommonStatusCodes.SUCCESS -> {
                    val message = extras.getString(SmsRetriever.EXTRA_SMS_MESSAGE)
                    if (!message.isNullOrBlank()) {
                        extractOtp(message)?.let(onOtpReceived)
                        return
                    }

                    val consentIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        extras.getParcelable(SmsRetriever.EXTRA_CONSENT_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        extras.getParcelable(SmsRetriever.EXTRA_CONSENT_INTENT)
                    }
                    consentIntent?.let { consentLauncher.launch(it) }
                }
            }
        }
    }

    fun start() {
        if (receiverRegistered) return

        val filter = IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(smsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(smsReceiver, filter)
        }
        receiverRegistered = true

        smsClient.startSmsRetriever()
        smsClient.startSmsUserConsent(null)
    }

    fun restartListening() {
        smsClient.startSmsRetriever()
        smsClient.startSmsUserConsent(null)
    }

    fun stop() {
        if (!receiverRegistered) return
        activity.unregisterReceiver(smsReceiver)
        receiverRegistered = false
    }

    private fun extractOtp(message: String): String? {
        val pattern = Regex("\\b(\\d{$otpLength})\\b")
        return pattern.find(message)?.groupValues?.get(1)
    }
}

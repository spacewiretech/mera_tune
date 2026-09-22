package com.spacewire.meratune.util

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.spacewire.meratune.R
import com.spacewire.meratune.calltheme.IncomingCallMonitor
import com.spacewire.meratune.calltheme.IncomingCallNotifier

/**
 * Asks for Set-ringtone / call-theme permissions as soon as Home opens,
 * so later Set taps are not interrupted by a chain of system dialogs.
 */
class StartupPermissionRequester(private val activity: ComponentActivity) {

    private val runtimeLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        requestWriteSettingsIfNeeded()
    }

    private val writeSettingsLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        finishPermissionSetup()
    }

    fun requestIfNeeded() {
        val missing = missingRuntimePermissions()
        if (missing.isNotEmpty()) {
            runtimeLauncher.launch(missing)
        } else {
            requestWriteSettingsIfNeeded()
        }
    }

    private fun requestWriteSettingsIfNeeded() {
        if (!RingtoneHelper.needsWriteSettingsPermission(activity)) {
            finishPermissionSetup()
            return
        }
        Toast.makeText(activity, R.string.ringtone_permission_required, Toast.LENGTH_LONG).show()
        writeSettingsLauncher.launch(
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
            },
        )
    }

    private fun finishPermissionSetup() {
        IncomingCallNotifier.ensureChannel(activity)
        IncomingCallMonitor.start(activity)
    }

    private fun missingRuntimePermissions(): Array<String> {
        return RUNTIME_PERMISSIONS.filter { permission ->
            if (permission == Manifest.permission.POST_NOTIFICATIONS &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            ) {
                return@filter false
            }
            if (permission == Manifest.permission.WRITE_EXTERNAL_STORAGE &&
                Build.VERSION.SDK_INT > Build.VERSION_CODES.P
            ) {
                return@filter false
            }
            ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    private companion object {
        val RUNTIME_PERMISSIONS = listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
    }
}

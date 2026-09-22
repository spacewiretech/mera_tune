package com.spacewire.meratune.calltheme

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.spacewire.meratune.R
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.data.Tune
import com.spacewire.meratune.ui.SetRingtoneBottomSheet
import com.spacewire.meratune.ui.UploadPhotoBottomSheet
import com.spacewire.meratune.util.RingtoneHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shared Set flow for Home and RingtoneReady:
 * mode sheet → photo sheet (if needed) → permissions → contact picker → set ringtone + save call theme.
 */
class RingtoneSetController(
    private val activity: ComponentActivity,
    private val analyticsSource: String,
    private val categoryForTune: (Tune) -> String,
    private val onSuccess: (Tune, Uri) -> Unit = { _, _ -> },
) {
    // Must be lazy: controller is constructed during Activity init, before Context is attached.
    private val themeStore by lazy { CallThemeStore(activity) }

    private var pendingTune: Tune? = null
    private var pendingMode: RingtoneSetMode = RingtoneSetMode.AUDIO_ONLY
    private var pendingContact: ContactDetails? = null
    private var pendingImageUri: Uri? = null
    private var pendingImagePath: String? = null
    private var awaitingWriteSettings = false
    private var awaitingImagePick = false
    private var permissionStep: PermissionStep = PermissionStep.NONE
    private var photoSheet: UploadPhotoBottomSheet? = null
    private var cameraOutputUri: Uri? = null

    private val storagePermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val tune = pendingTune
        if (granted && tune != null) {
            beginMode(tune, pendingMode)
        } else if (tune != null) {
            Toast.makeText(activity, R.string.ringtone_set_error, Toast.LENGTH_SHORT).show()
            clearPending()
        }
    }

    private val writeSettingsLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val tune = pendingTune
        awaitingWriteSettings = false
        if (tune != null && RingtoneHelper.needsWriteSettingsPermission(activity)) {
            Toast.makeText(activity, R.string.ringtone_permission_required, Toast.LENGTH_LONG).show()
            clearPending()
        } else if (tune != null) {
            continueAfterWriteSettings(tune)
        }
    }

    private val runtimePermissionsLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val tune = pendingTune ?: return@registerForActivityResult
        val denied = result.filterValues { !it }.keys
        when (permissionStep) {
            PermissionStep.CONTACTS -> {
                val missingContacts = Manifest.permission.READ_CONTACTS in denied ||
                    Manifest.permission.WRITE_CONTACTS in denied
                if (missingContacts) {
                    Toast.makeText(activity, R.string.call_theme_contacts_permission_required, Toast.LENGTH_LONG).show()
                    clearPending()
                } else {
                    launchContactPicker()
                }
            }

            PermissionStep.CALL_DISPLAY -> {
                val criticalDenied = Manifest.permission.READ_PHONE_STATE in denied
                if (criticalDenied) {
                    Toast.makeText(activity, R.string.call_theme_phone_permission_required, Toast.LENGTH_LONG).show()
                    clearPending()
                } else {
                    afterCallPermissionsGranted(tune)
                }
            }

            PermissionStep.NONE -> Unit
        }
        permissionStep = PermissionStep.NONE
    }

    private val contactPickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.PickContact(),
    ) { uri ->
        val tune = pendingTune
        if (uri == null || tune == null) {
            clearPending()
            return@registerForActivityResult
        }
        val details = ContactLookupHelper.loadDetails(activity, uri)
        if (details == null || details.phoneNumbers.isEmpty()) {
            Toast.makeText(activity, R.string.call_theme_contact_no_phone, Toast.LENGTH_LONG).show()
            clearPending()
            return@registerForActivityResult
        }
        pendingContact = details
        requestCallDisplayPermissions(tune)
    }

    private val imagePickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        awaitingImagePick = false
        if (uri != null) {
            showPreviewOnPhotoSheet(uri)
        } else {
            ensurePhotoSheetVisible()
        }
    }

    private val takePictureLauncher = activity.registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        awaitingImagePick = false
        val uri = cameraOutputUri
        if (success && uri != null) {
            showPreviewOnPhotoSheet(uri)
        } else {
            ensurePhotoSheetVisible()
        }
    }

    fun start(tune: Tune) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingTune = tune
            pendingMode = RingtoneSetMode.AUDIO_ONLY
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        SetRingtoneBottomSheet(activity) { mode ->
            beginMode(tune, mode)
        }.show()
    }

    private fun beginMode(tune: Tune, mode: RingtoneSetMode) {
        pendingTune = tune
        pendingMode = mode
        pendingContact = null
        pendingImageUri = null
        pendingImagePath = null

        when (mode) {
            RingtoneSetMode.AUDIO_ONLY -> setRingtoneOnly(tune)
            RingtoneSetMode.WITH_IMAGE_EVERYONE,
            RingtoneSetMode.WITH_IMAGE_CONTACT,
            -> showPhotoSheet()
        }
    }

    private fun showPhotoSheet() {
        val sheet = UploadPhotoBottomSheet(
            context = activity,
            onCameraClick = { launchCamera() },
            onGalleryClick = { launchImagePicker() },
            onContinue = { uri ->
                pendingImageUri = uri
                photoSheet = null
                val tune = pendingTune ?: return@UploadPhotoBottomSheet
                when (pendingMode) {
                    RingtoneSetMode.AUDIO_ONLY -> setRingtoneOnly(tune)
                    RingtoneSetMode.WITH_IMAGE_EVERYONE -> requestCallDisplayPermissions(tune)
                    RingtoneSetMode.WITH_IMAGE_CONTACT -> requestContactsPermission()
                }
            },
            onDismissed = {
                photoSheet = null
                if (!awaitingImagePick) {
                    clearPending()
                }
            },
        )
        photoSheet = sheet
        sheet.show()
        pendingImageUri?.let(sheet::setPreview)
    }

    private fun showPreviewOnPhotoSheet(uri: Uri) {
        pendingImageUri = uri
        if (photoSheet?.isShowing() == true) {
            photoSheet?.setPreview(uri)
        } else {
            showPhotoSheet()
        }
    }

    private fun ensurePhotoSheetVisible() {
        if (pendingTune == null) return
        if (photoSheet?.isShowing() != true) {
            showPhotoSheet()
        }
    }

    private fun launchCamera() {
        val uri = createCameraOutputUri()
        if (uri == null) {
            Toast.makeText(activity, R.string.upload_photo_camera_error, Toast.LENGTH_SHORT).show()
            return
        }
        awaitingImagePick = true
        cameraOutputUri = uri
        takePictureLauncher.launch(uri)
    }

    private fun requestContactsPermission() {
        val needRead = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.READ_CONTACTS,
        ) != PackageManager.PERMISSION_GRANTED
        val needWrite = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.WRITE_CONTACTS,
        ) != PackageManager.PERMISSION_GRANTED
        if (!needRead && !needWrite) {
            launchContactPicker()
            return
        }
        permissionStep = PermissionStep.CONTACTS
        runtimePermissionsLauncher.launch(
            arrayOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS,
            ),
        )
    }

    private fun launchContactPicker() {
        contactPickerLauncher.launch(null)
    }

    private fun launchImagePicker() {
        awaitingImagePick = true
        imagePickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    private fun createCameraOutputUri(): Uri? {
        return runCatching {
            val directory = File(activity.cacheDir, "camera").apply { mkdirs() }
            val file = File(directory, "set_ringtone_${System.currentTimeMillis()}.jpg").apply {
                createNewFile()
            }
            FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                file,
            )
        }.getOrElse { error ->
            Log.e(TAG, "Could not create camera output file", error)
            null
        }
    }

    private fun requestCallDisplayPermissions(tune: Tune) {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.READ_PHONE_STATE,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.READ_PHONE_STATE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.ANSWER_PHONE_CALLS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.ANSWER_PHONE_CALLS)
            }
        }

        if (needed.isEmpty()) {
            afterCallPermissionsGranted(tune)
            return
        }

        permissionStep = PermissionStep.CALL_DISPLAY
        runtimePermissionsLauncher.launch(needed.toTypedArray())
    }

    private fun afterCallPermissionsGranted(tune: Tune) {
        setRingtoneWithTheme(tune)
    }

    private fun setRingtoneOnly(tune: Tune) {
        Toast.makeText(activity, R.string.ringtone_setting, Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val result = RingtoneHelper.setRingtone(activity, tune)
            result.onSuccess { uri ->
                finishSuccess(tune, uri, saveTheme = false)
            }.onFailure { error ->
                handleSetFailure(tune, error, retryWithTheme = false)
            }
        }
    }

    private fun setRingtoneWithTheme(tune: Tune) {
        val imageUri = pendingImageUri
        if (imageUri == null && pendingImagePath == null) {
            Toast.makeText(activity, R.string.call_theme_image_required, Toast.LENGTH_SHORT).show()
            clearPending()
            return
        }

        Toast.makeText(activity, R.string.ringtone_setting, Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val imagePath = pendingImagePath ?: runCatching {
                CallThemeImageHelper.persistImage(activity, imageUri!!)
            }.getOrElse { error ->
                Log.e(TAG, "Failed to persist call theme image", error)
                Toast.makeText(activity, R.string.call_theme_image_save_error, Toast.LENGTH_SHORT).show()
                clearPending()
                return@launch
            }
            pendingImagePath = imagePath

            val result = RingtoneHelper.setRingtone(activity, tune)
            result.onSuccess { uri ->
                val photoSaved = saveTheme(tune, imagePath, uri)
                finishSuccess(
                    tune = tune,
                    uri = uri,
                    saveTheme = true,
                    contactPhotoSaved = photoSaved,
                )
            }.onFailure { error ->
                handleSetFailure(tune, error, retryWithTheme = true)
            }
        }
    }

    private fun continueAfterWriteSettings(tune: Tune) {
        when (pendingMode) {
            RingtoneSetMode.AUDIO_ONLY -> setRingtoneOnly(tune)
            RingtoneSetMode.WITH_IMAGE_EVERYONE,
            RingtoneSetMode.WITH_IMAGE_CONTACT,
            -> setRingtoneWithTheme(tune)
        }
    }

    /**
     * @return false only when contact-photo mode was requested but the Contacts photo write failed
     */
    private suspend fun saveTheme(tune: Tune, imagePath: String, ringtoneUri: Uri): Boolean {
        return when (pendingMode) {
            RingtoneSetMode.AUDIO_ONLY -> true
            RingtoneSetMode.WITH_IMAGE_EVERYONE -> {
                themeStore.saveEveryone(
                    imagePath = imagePath,
                    tuneId = tune.id,
                    tuneName = tune.name,
                )
                IncomingCallNotifier.ensureChannel(activity)
                true
            }

            RingtoneSetMode.WITH_IMAGE_CONTACT -> {
                val contact = pendingContact ?: return false
                themeStore.saveContact(
                    contactName = contact.displayName,
                    contactUri = contact.contactUri.toString(),
                    phoneKeys = contact.phoneNumbers,
                    imagePath = imagePath,
                    tuneId = tune.id,
                    tuneName = tune.name,
                )
                IncomingCallNotifier.ensureChannel(activity)

                val canWriteContacts = ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.WRITE_CONTACTS,
                ) == PackageManager.PERMISSION_GRANTED
                if (!canWriteContacts) {
                    Toast.makeText(
                        activity,
                        R.string.call_theme_contacts_write_permission_required,
                        Toast.LENGTH_LONG,
                    ).show()
                    return false
                }

                withContext(Dispatchers.IO) {
                    ContactRingtoneHelper.setCustomRingtone(
                        activity,
                        contact.contactUri,
                        ringtoneUri,
                    )
                    val photoSaved = ContactPhotoHelper.setContactPhoto(
                        activity,
                        contact.contactUri,
                        imagePath,
                    )
                    if (!photoSaved) {
                        Log.w(TAG, "Failed to write contact photo for ${contact.displayName}")
                    }
                    photoSaved
                }
            }
        }
    }

    private fun finishSuccess(
        tune: Tune,
        uri: Uri,
        saveTheme: Boolean,
        contactPhotoSaved: Boolean = true,
    ) {
        onSuccess(tune, uri)
        activity.mixpanelAnalytics().trackRingtoneSet(
            source = analyticsSource,
            category = categoryForTune(tune),
            tuneId = tune.id,
            tuneName = tune.name,
            setMode = pendingMode.analyticsValue,
        )
        val message = when {
            !saveTheme -> activity.getString(R.string.ringtone_set_success, tune.name)
            pendingMode == RingtoneSetMode.WITH_IMAGE_CONTACT && !contactPhotoSaved ->
                activity.getString(R.string.call_theme_contact_photo_error)
            pendingMode == RingtoneSetMode.WITH_IMAGE_CONTACT -> {
                val name = pendingContact?.displayName
                    ?: activity.getString(R.string.call_theme_selected_contact)
                activity.getString(R.string.call_theme_set_contact_success, tune.name, name)
            }
            else -> activity.getString(R.string.call_theme_set_everyone_success, tune.name)
        }
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        clearPending()
    }

    private fun handleSetFailure(tune: Tune, error: Throwable, retryWithTheme: Boolean) {
        Log.e(TAG, "Failed to set ringtone for ${tune.name}", error)
        if (error is SecurityException && RingtoneHelper.needsWriteSettingsPermission(activity)) {
            awaitingWriteSettings = true
            pendingTune = tune
            if (!retryWithTheme) {
                pendingMode = RingtoneSetMode.AUDIO_ONLY
            }
            writeSettingsLauncher.launch(RingtoneHelper.writeSettingsIntent(activity))
        } else {
            Toast.makeText(activity, R.string.ringtone_set_error, Toast.LENGTH_SHORT).show()
            clearPending()
        }
    }

    private fun clearPending() {
        if (awaitingWriteSettings) return
        pendingTune = null
        pendingContact = null
        pendingImageUri = null
        pendingImagePath = null
        pendingMode = RingtoneSetMode.AUDIO_ONLY
        permissionStep = PermissionStep.NONE
        awaitingImagePick = false
        cameraOutputUri = null
        photoSheet?.dismiss()
        photoSheet = null
    }

    private enum class PermissionStep {
        NONE,
        CONTACTS,
        CALL_DISPLAY,
    }

    private companion object {
        const val TAG = "RingtoneSetController"
    }
}

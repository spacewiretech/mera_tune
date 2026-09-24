package com.spacewire.meratune.calltheme

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.spacewire.meratune.R
import com.spacewire.meratune.analytics.AnalyticsPermissionKey
import com.spacewire.meratune.analytics.AnalyticsPermissions
import com.spacewire.meratune.analytics.AnalyticsSource
import com.spacewire.meratune.analytics.FailureReason
import com.spacewire.meratune.analytics.PromptContext
import com.spacewire.meratune.analytics.SetFailureReason
import com.spacewire.meratune.analytics.SetFailureStage
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.data.Tune
import com.spacewire.meratune.ui.SetRingtoneBottomSheet
import com.spacewire.meratune.ui.UploadPhotoBottomSheet
import com.spacewire.meratune.util.LoadErrorMapper
import com.spacewire.meratune.util.RingtoneHelper
import com.spacewire.meratune.util.RingtoneSetException
import com.spacewire.meratune.util.RingtoneSetStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shared Set flow for Home and RingtoneReady:
 * mode sheet → photo sheet (if needed) → permissions → contact picker → set ringtone + save call theme.
 *
 * One flow at a time: it runs from [start] until success or [failFlow], and `pendingTune` is
 * non-null for exactly that span.
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
    private var pendingPhotoSource: String? = null
    private var flowStartedAtMs = 0L
    private var awaitingWriteSettings = false
    private var awaitingImagePick = false
    private var permissionStep: PermissionStep = PermissionStep.NONE
    private var photoSheet: UploadPhotoBottomSheet? = null
    private var cameraOutputUri: Uri? = null

    private val storagePermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        trackPermissionAnswered(Manifest.permission.WRITE_EXTERNAL_STORAGE, granted)
        val tune = pendingTune
        if (tune == null) {
            reportStateLost(SetFailureStage.STORAGE_PERMISSION)
        } else if (granted) {
            beginMode(tune, pendingMode)
        } else {
            Toast.makeText(activity, R.string.ringtone_set_error, Toast.LENGTH_SHORT).show()
            failFlow(SetFailureStage.STORAGE_PERMISSION, FailureReason.PERMISSION_DENIED)
        }
    }

    private val writeSettingsLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val tune = pendingTune
        awaitingWriteSettings = false
        val granted = !RingtoneHelper.needsWriteSettingsPermission(activity)
        activity.mixpanelAnalytics().trackPermissionPromptAnswered(
            permission = AnalyticsPermissionKey.WRITE_SETTINGS,
            granted = granted,
            promptContext = PromptContext.SET_RINGTONE,
        )
        if (tune == null) {
            reportStateLost(SetFailureStage.WRITE_SETTINGS_PERMISSION)
        } else if (!granted) {
            Toast.makeText(activity, R.string.ringtone_permission_required, Toast.LENGTH_LONG).show()
            failFlow(SetFailureStage.WRITE_SETTINGS_PERMISSION, FailureReason.PERMISSION_DENIED)
        } else {
            continueAfterWriteSettings(tune)
        }
    }

    private val runtimePermissionsLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        result.forEach { (permission, granted) -> trackPermissionAnswered(permission, granted) }
        val step = permissionStep.takeUnless { it == PermissionStep.NONE }
            ?: PermissionStep.forRequest(result.keys)
        permissionStep = PermissionStep.NONE
        val tune = pendingTune
        if (tune == null) {
            step.failureStage?.let(::reportStateLost)
            return@registerForActivityResult
        }
        val denied = result.filterValues { !it }.keys
        when (step) {
            PermissionStep.CONTACTS -> {
                val missingContacts = Manifest.permission.READ_CONTACTS in denied ||
                    Manifest.permission.WRITE_CONTACTS in denied
                if (missingContacts) {
                    Toast.makeText(activity, R.string.call_theme_contacts_permission_required, Toast.LENGTH_LONG).show()
                    failFlow(SetFailureStage.CONTACTS_PERMISSION, FailureReason.PERMISSION_DENIED)
                } else {
                    launchContactPicker()
                }
            }

            PermissionStep.CALL_DISPLAY -> {
                val criticalDenied = Manifest.permission.READ_PHONE_STATE in denied
                if (criticalDenied) {
                    Toast.makeText(activity, R.string.call_theme_phone_permission_required, Toast.LENGTH_LONG).show()
                    failFlow(SetFailureStage.PHONE_PERMISSION, FailureReason.PERMISSION_DENIED)
                } else {
                    afterCallPermissionsGranted(tune)
                }
            }

            // Empty result with no step on record: nothing to continue, so do not leave the flow open.
            PermissionStep.NONE -> clearPending()
        }
    }

    private val contactPickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.PickContact(),
    ) { uri ->
        val tune = pendingTune
        if (tune == null) {
            reportStateLost(SetFailureStage.CONTACT_PICKER)
            return@registerForActivityResult
        }
        if (uri == null) {
            failFlow(SetFailureStage.CONTACT_PICKER, FailureReason.USER_CANCELLED)
            return@registerForActivityResult
        }
        val details = ContactLookupHelper.loadDetails(activity, uri)
        // CallThemeStore.saveContact needs at least one 10+ digit number to match callers.
        if (details == null || details.phoneKeys.isEmpty()) {
            Toast.makeText(activity, R.string.call_theme_contact_no_phone, Toast.LENGTH_LONG).show()
            failFlow(
                SetFailureStage.CONTACT_PICKER,
                if (details == null) FailureReason.UNKNOWN else SetFailureReason.NO_VALID_PHONE_NUMBER,
            )
            return@registerForActivityResult
        }
        pendingContact = details
        requestCallDisplayPermissions(tune)
    }

    private val imagePickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        awaitingImagePick = false
        if (pendingTune == null) {
            reportStateLost(SetFailureStage.PHOTO_SHEET)
        } else if (uri != null) {
            showPreviewOnPhotoSheet(uri, PHOTO_SOURCE_GALLERY)
        } else {
            ensurePhotoSheetVisible()
        }
    }

    private val takePictureLauncher = activity.registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        awaitingImagePick = false
        val uri = cameraOutputUri
        if (pendingTune == null) {
            reportStateLost(SetFailureStage.PHOTO_SHEET)
        } else if (success && uri != null) {
            showPreviewOnPhotoSheet(uri, PHOTO_SOURCE_CAMERA)
        } else {
            ensurePhotoSheetVisible()
        }
    }

    /** Ignored while another flow is in progress (double tap, or a second row tapped mid-download). */
    fun start(tune: Tune, entry: SetEntryContext = SetEntryContext()) {
        if (pendingTune != null) return
        pendingTune = tune
        pendingMode = RingtoneSetMode.AUDIO_ONLY
        flowStartedAtMs = SystemClock.elapsedRealtime()
        activity.mixpanelAnalytics().trackRingtoneSetStarted(
            source = analyticsSource,
            tuneId = tune.id,
            category = categoryForTune(tune),
            personalized = tune.isPersonalized,
            generationId = tune.generationId,
            rank = entry.rank,
            wasPreviewed = entry.wasPreviewed,
        )

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        SetRingtoneBottomSheet(
            context = activity,
            onContinue = { mode ->
                activity.mixpanelAnalytics().trackSetModeSelected(
                    setMode = mode.analyticsValue,
                    source = analyticsSource,
                    tuneId = tune.id,
                    personalized = tune.isPersonalized,
                )
                beginMode(tune, mode)
            },
            onDismissed = { failFlow(SetFailureStage.MODE_SHEET, FailureReason.USER_CANCELLED) },
        ).show()
    }

    private fun beginMode(tune: Tune, mode: RingtoneSetMode) {
        pendingTune = tune
        pendingMode = mode
        pendingContact = null
        pendingImageUri = null
        pendingImagePath = null
        pendingPhotoSource = null

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
                    failFlow(SetFailureStage.PHOTO_SHEET, FailureReason.USER_CANCELLED)
                }
            },
        )
        photoSheet = sheet
        sheet.show()
        pendingImageUri?.let(sheet::setPreview)
    }

    private fun showPreviewOnPhotoSheet(uri: Uri, photoSource: String) {
        pendingImageUri = uri
        pendingPhotoSource = photoSource
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
                finishSuccess(tune, uri, RingtoneSetMode.AUDIO_ONLY)
            }.onFailure { error ->
                handleSetFailure(tune, RingtoneSetMode.AUDIO_ONLY, error)
            }
        }
    }

    private fun setRingtoneWithTheme(tune: Tune) {
        val mode = pendingMode
        val imageUri = pendingImageUri
        if (imageUri == null && pendingImagePath == null) {
            Toast.makeText(activity, R.string.call_theme_image_required, Toast.LENGTH_SHORT).show()
            failFlow(SetFailureStage.PHOTO_SHEET, FailureReason.UNKNOWN)
            return
        }

        Toast.makeText(activity, R.string.ringtone_setting, Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val imagePath = pendingImagePath ?: runCatching {
                CallThemeImageHelper.persistImage(activity, imageUri!!)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                Log.e(TAG, "Failed to persist call theme image", error)
                Toast.makeText(activity, R.string.call_theme_image_save_error, Toast.LENGTH_SHORT).show()
                failFlow(SetFailureClassifier.forError(SetFailureStage.PHOTO_SHEET, error))
                return@launch
            }
            pendingImagePath = imagePath

            val uri = RingtoneHelper.setRingtone(activity, tune).getOrElse { error ->
                handleSetFailure(tune, mode, error)
                return@launch
            }
            val themeResult = runCatching { saveTheme(tune, mode, imagePath, uri) }.getOrElse { error ->
                if (error is CancellationException) throw error
                Log.e(TAG, "Failed to save call theme", error)
                // The system ringtone already changed; only the photo part failed.
                onSuccess(tune, uri)
                Toast.makeText(activity, R.string.call_theme_image_save_error, Toast.LENGTH_LONG).show()
                failFlow(SetFailureClassifier.forError(SetFailureStage.THEME_SAVE, error))
                return@launch
            }
            finishSuccess(tune, uri, mode, themeResult)
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

    private suspend fun saveTheme(
        tune: Tune,
        mode: RingtoneSetMode,
        imagePath: String,
        ringtoneUri: Uri,
    ): ThemeSaveResult {
        return when (mode) {
            RingtoneSetMode.AUDIO_ONLY -> ThemeSaveResult()
            RingtoneSetMode.WITH_IMAGE_EVERYONE -> {
                themeStore.saveEveryone(
                    imagePath = imagePath,
                    tuneId = tune.id,
                    tuneName = tune.name,
                )
                IncomingCallNotifier.ensureChannel(activity)
                ThemeSaveResult()
            }

            RingtoneSetMode.WITH_IMAGE_CONTACT -> {
                val contact = pendingContact
                    ?: return ThemeSaveResult(contactPhotoSaved = false, contactRingtoneSaved = false)
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
                    return ThemeSaveResult(contactPhotoSaved = false, contactRingtoneSaved = false)
                }

                withContext(Dispatchers.IO) {
                    val ringtoneSaved = ContactRingtoneHelper.setCustomRingtone(
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
                    ThemeSaveResult(contactPhotoSaved = photoSaved, contactRingtoneSaved = ringtoneSaved)
                }
            }
        }
    }

    /** [theme] is `null` for audio-only. */
    private fun finishSuccess(
        tune: Tune,
        uri: Uri,
        mode: RingtoneSetMode,
        theme: ThemeSaveResult? = null,
    ) {
        onSuccess(tune, uri)
        val personalized = tune.isPersonalized
        activity.mixpanelAnalytics().trackRingtoneSet(
            source = analyticsSource,
            category = categoryForTune(tune),
            tuneId = tune.id,
            tuneName = analyticsTuneName(tune, personalized),
            setMode = mode.analyticsValue,
            generationId = tune.generationId,
            personalized = personalized,
            photoSource = pendingPhotoSource.takeIf { theme != null },
            contactPhotoSaved = theme?.contactPhotoSaved,
            contactRingtoneSaved = theme?.contactRingtoneSaved,
            flowDurationMs = SystemClock.elapsedRealtime() - flowStartedAtMs,
            hasCallTheme = themeStore.hasAnyTheme(),
        )
        val message = when {
            theme == null -> activity.getString(R.string.ringtone_set_success, tune.name)
            mode == RingtoneSetMode.WITH_IMAGE_CONTACT && theme.contactPhotoSaved == false ->
                activity.getString(R.string.call_theme_contact_photo_error)
            mode == RingtoneSetMode.WITH_IMAGE_CONTACT -> {
                val name = pendingContact?.displayName
                    ?: activity.getString(R.string.call_theme_selected_contact)
                activity.getString(R.string.call_theme_set_contact_success, tune.name, name)
            }
            else -> activity.getString(R.string.call_theme_set_everyone_success, tune.name)
        }
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        clearPending()
    }

    /**
     * A personalized tune's `name` is the generated title and contains the user's name, which
     * must never reach analytics. Fall back to the base song as authored (`title_template` with
     * `sample_name`), else send nothing (blank props are omitted by `MixpanelAnalytics`).
     */
    private fun analyticsTuneName(tune: Tune, personalized: Boolean): String {
        if (!personalized) return tune.name
        val template = tune.titleTemplate?.trim().orEmpty()
        if (template.isEmpty()) return ""
        val sampleName = tune.sampleName?.trim().orEmpty()
        return if (sampleName.isNotEmpty()) template.replace("{name}", sampleName) else template
    }

    private fun handleSetFailure(tune: Tune, mode: RingtoneSetMode, error: Throwable) {
        Log.e(TAG, "Failed to set ringtone for ${tune.name}", error)
        val cause = (error as? RingtoneSetException)?.cause ?: error
        if (cause is SecurityException && RingtoneHelper.needsWriteSettingsPermission(activity)) {
            awaitingWriteSettings = true
            pendingTune = tune
            pendingMode = mode
            writeSettingsLauncher.launch(RingtoneHelper.writeSettingsIntent(activity))
        } else {
            Toast.makeText(activity, R.string.ringtone_set_error, Toast.LENGTH_SHORT).show()
            failFlow(SetFailureClassifier.forRingtoneError(error))
        }
    }

    /** Terminal non-success exit: `ringtone_set_failed` (once per flow), then the flow is cleared. */
    private fun failFlow(stage: String, failureReason: String, errorType: String? = null) {
        val tune = pendingTune
        if (tune != null && !awaitingWriteSettings) {
            activity.mixpanelAnalytics().trackRingtoneSetFailed(
                stage = stage,
                failureReason = failureReason,
                source = analyticsSource,
                tuneId = tune.id,
                personalized = tune.isPersonalized,
                setMode = pendingMode.analyticsValue.takeUnless { stage == SetFailureStage.MODE_SHEET },
                errorType = errorType,
            )
        }
        clearPending()
    }

    private fun failFlow(failure: SetFailure) {
        failFlow(failure.stage, failure.failureReason, failure.errorType)
    }

    /**
     * A launcher result reached a controller with no flow: the Activity was recreated or the
     * process died while a system screen was on top. The tune is gone; only the Ready screen
     * sets personalized tunes.
     */
    private fun reportStateLost(stage: String) {
        activity.mixpanelAnalytics().trackRingtoneSetFailed(
            stage = stage,
            failureReason = SetFailureReason.STATE_LOST,
            source = analyticsSource,
            tuneId = "",
            personalized = analyticsSource == AnalyticsSource.CREATION_FLOW,
        )
    }

    private fun trackPermissionAnswered(manifestPermission: String, granted: Boolean) {
        val permission = AnalyticsPermissionKey.fromManifest(manifestPermission) ?: return
        activity.mixpanelAnalytics().trackPermissionPromptAnswered(
            permission = permission,
            granted = granted,
            promptContext = PromptContext.SET_RINGTONE,
            permanentlyDenied = if (granted) {
                null
            } else {
                AnalyticsPermissions.isPermanentlyDenied(activity, manifestPermission)
            },
        )
    }

    private fun clearPending() {
        if (awaitingWriteSettings) return
        pendingTune = null
        pendingContact = null
        pendingImageUri = null
        pendingImagePath = null
        pendingPhotoSource = null
        pendingMode = RingtoneSetMode.AUDIO_ONLY
        permissionStep = PermissionStep.NONE
        awaitingImagePick = false
        cameraOutputUri = null
        photoSheet?.dismiss()
        photoSheet = null
    }

    private val Tune.isPersonalized: Boolean
        get() = generationId != null

    /** Contact results are `null` outside contact mode. */
    private data class ThemeSaveResult(
        val contactPhotoSaved: Boolean? = null,
        val contactRingtoneSaved: Boolean? = null,
    )

    private enum class PermissionStep(val failureStage: String?) {
        NONE(null),
        CONTACTS(SetFailureStage.CONTACTS_PERMISSION),
        CALL_DISPLAY(SetFailureStage.PHONE_PERMISSION),
        ;

        companion object {
            /** Recovers the step from the requested permissions when the field was lost. */
            fun forRequest(requested: Set<String>): PermissionStep = when {
                Manifest.permission.READ_CONTACTS in requested ||
                    Manifest.permission.WRITE_CONTACTS in requested -> CONTACTS
                requested.isNotEmpty() -> CALL_DISPLAY
                else -> NONE
            }
        }
    }

    private companion object {
        const val TAG = "RingtoneSetController"
        const val PHOTO_SOURCE_CAMERA = "camera"
        const val PHOTO_SOURCE_GALLERY = "gallery"
    }
}

/** Bounded `ringtone_set_failed` values. */
internal data class SetFailure(val stage: String, val failureReason: String, val errorType: String?)

/** Maps Set-flow exceptions to bounded analytics values. Exception messages are never read. */
internal object SetFailureClassifier {

    /** A [RingtoneHelper.setRingtone] failure; the stage comes from its [RingtoneSetException]. */
    fun forRingtoneError(error: Throwable): SetFailure {
        val stepError = error as? RingtoneSetException
        val stage = when (stepError?.step) {
            RingtoneSetStep.DOWNLOAD -> SetFailureStage.DOWNLOAD
            RingtoneSetStep.SAVE -> SetFailureStage.SAVE
            RingtoneSetStep.SET_DEFAULT, null -> SetFailureStage.SET_DEFAULT
        }
        return forError(stage, stepError?.cause ?: error)
    }

    fun forError(stage: String, error: Throwable): SetFailure =
        SetFailure(stage, failureReason(stage, error), error.javaClass.simpleName.takeIf { it.isNotEmpty() })

    private fun failureReason(stage: String, error: Throwable): String = when {
        error is SecurityException -> SetFailureReason.SECURITY_EXCEPTION
        stage == SetFailureStage.DOWNLOAD -> when (LoadErrorMapper.reason(error)) {
            LoadErrorMapper.TIMEOUT -> FailureReason.TIMEOUT
            LoadErrorMapper.NETWORK -> FailureReason.NETWORK
            else -> FailureReason.UNKNOWN
        }
        stage == SetFailureStage.SAVE -> SetFailureReason.MEDIA_STORE_ERROR
        else -> FailureReason.UNKNOWN
    }
}

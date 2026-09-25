package com.spacewire.meratune.calltheme

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
 * Shared Set flow. One class, three entry points (all launchers are registered at construction):
 *
 * - [start] (Home, and Ready without a saved choice), single shot: storage permission (API <= 28)
 *   → mode sheet → photo sheet (if needed) → permissions → contact picker → set ringtone + save
 *   call theme.
 * - [choose] (song picker "chuno"): mode sheet → photo sheet → the photo is staged. No permission,
 *   no download, no ringtone change; [onChosen] receives the [SetChoice].
 * - [apply] (Ready "Ringtone set karein"): the saved [SetChoice] without a sheet: storage permission
 *   (API <= 28) → WRITE_SETTINGS pre-flight (before any download) → per-mode permissions / contact
 *   picker → set ringtone → promote the staged photo → save the call theme. A missing staged photo
 *   falls back to the photo sheet.
 *
 * One flow at a time: it runs from an entry point until success, a choice, or [failFlow], and
 * `pendingTune` is non-null for exactly that span ([isIdle]). While a system screen is on top, the
 * flow is kept in the activity's SavedStateRegistry, so a camera / gallery / contact picker round
 * trip survives process death.
 */
class RingtoneSetController(
    private val activity: ComponentActivity,
    private val analyticsSource: String,
    private val categoryForTune: (Tune) -> String,
    private val onSuccess: (Tune, Uri) -> Unit = { _, _ -> },
    /** [choose] finished: the base tune and the choice to apply on the final screen. */
    private val onChosen: (Tune, SetChoice) -> Unit = { _, _ -> },
) {
    // Must be lazy: controller is constructed during Activity init, before Context is attached.
    private val themeStore by lazy { CallThemeStore(activity) }

    private enum class Phase { SINGLE_SHOT, CHOOSE, APPLY }

    private var phase = Phase.SINGLE_SHOT

    /** `personalized` for this flow's analytics: the create path is always personalized. */
    private var flowPersonalized = false
    private var writeSettingsPreflight = false
    private var cameraOutputFile: File? = null

    /** A launcher is out (system screen on top); only then is the flow worth restoring. */
    private var awaitingLaunchResult = false

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

    /** No flow is open: a new [start], [choose] or [apply] would be accepted. */
    val isIdle: Boolean
        get() = pendingTune == null

    init {
        activity.savedStateRegistry.registerSavedStateProvider(SAVED_STATE_KEY) { saveFlowState() }
        activity.lifecycle.addObserver(
            object : LifecycleEventObserver {
                override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
                    if (event == Lifecycle.Event.ON_CREATE) {
                        activity.lifecycle.removeObserver(this)
                        restoreFlowState(activity.savedStateRegistry.consumeRestoredStateForKey(SAVED_STATE_KEY))
                    }
                }
            },
        )
    }

    private val storagePermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        awaitingLaunchResult = false
        trackPermissionAnswered(Manifest.permission.WRITE_EXTERNAL_STORAGE, granted)
        val tune = pendingTune
        if (tune == null) {
            reportStateLost(SetFailureStage.STORAGE_PERMISSION)
        } else if (granted) {
            // APPLY continues with the saved mode; Home now shows the mode sheet (it used to set
            // audio-only silently on Android 8/9).
            if (phase == Phase.APPLY) applyAfterStorage(tune) else showModeSheet(tune)
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
        awaitingLaunchResult = false
        val preflight = writeSettingsPreflight
        writeSettingsPreflight = false
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
        } else if (preflight) {
            proceedApply(tune)
        } else {
            continueAfterWriteSettings(tune)
        }
    }

    private val runtimePermissionsLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        awaitingLaunchResult = false
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
        awaitingLaunchResult = false
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
        awaitingLaunchResult = false
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
        awaitingLaunchResult = false
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
        phase = Phase.SINGLE_SHOT
        pendingTune = tune
        pendingMode = RingtoneSetMode.AUDIO_ONLY
        flowPersonalized = tune.isPersonalized
        flowStartedAtMs = SystemClock.elapsedRealtime()
        activity.mixpanelAnalytics().trackRingtoneSetStarted(
            source = analyticsSource,
            tuneId = tune.id,
            category = categoryForTune(tune),
            personalized = flowPersonalized,
            generationId = tune.generationId,
            rank = entry.rank,
            wasPreviewed = entry.wasPreviewed,
        )

        if (needsStoragePermission()) {
            launchStoragePermission()
            return
        }
        showModeSheet(tune)
    }

    /**
     * Song picker "chuno": mode sheet, then the photo sheet for photo modes, then the photo is
     * staged and [onChosen] receives the choice. Returns false (and does nothing) while another flow
     * is open.
     */
    fun choose(baseTune: Tune, entry: SetEntryContext = SetEntryContext()): Boolean {
        if (pendingTune != null) return false
        phase = Phase.CHOOSE
        pendingTune = baseTune
        pendingMode = RingtoneSetMode.AUDIO_ONLY
        flowPersonalized = true
        flowStartedAtMs = SystemClock.elapsedRealtime()
        activity.mixpanelAnalytics().trackRingtoneSetStarted(
            source = analyticsSource,
            tuneId = baseTune.id,
            category = categoryForTune(baseTune),
            personalized = true,
            generationId = null,
            rank = entry.rank,
            wasPreviewed = entry.wasPreviewed,
        )
        showModeSheet(baseTune)
        return true
    }

    /**
     * Final screen: sets [tune] with the [choice] made at chuno, without a sheet. [continuesFlow]
     * is true for the first apply of that choice (the flow started at chuno, so no second
     * `ringtone_set_started`); a retry after a terminal failure starts a new flow. Returns false
     * while another flow is open.
     */
    fun apply(tune: Tune, choice: SetChoice, continuesFlow: Boolean): Boolean {
        if (pendingTune != null) return false
        phase = Phase.APPLY
        pendingTune = tune
        pendingMode = choice.mode
        pendingContact = null
        pendingImageUri = null
        pendingImagePath = choice.imagePath
        pendingPhotoSource = choice.photoSource
        flowPersonalized = true
        val now = SystemClock.elapsedRealtime()
        if (continuesFlow) {
            flowStartedAtMs = now - choice.chooseDurationMs
        } else {
            flowStartedAtMs = now
            activity.mixpanelAnalytics().trackRingtoneSetStarted(
                source = analyticsSource,
                tuneId = tune.id,
                category = categoryForTune(tune),
                personalized = true,
                generationId = tune.generationId,
            )
        }

        if (needsStoragePermission()) {
            launchStoragePermission()
        } else {
            applyAfterStorage(tune)
        }
        return true
    }

    private fun needsStoragePermission(): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED

    private fun launchStoragePermission() {
        awaitingLaunchResult = true
        storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    /** APPLY: asks WRITE_SETTINGS before anything is downloaded, so a grant never re-downloads. */
    private fun applyAfterStorage(tune: Tune) {
        if (RingtoneHelper.needsWriteSettingsPermission(activity)) {
            awaitingWriteSettings = true
            writeSettingsPreflight = true
            awaitingLaunchResult = true
            writeSettingsLauncher.launch(RingtoneHelper.writeSettingsIntent(activity))
            return
        }
        proceedApply(tune)
    }

    /** APPLY, per mode. A photo mode whose staged file is gone falls back to the photo sheet. */
    private fun proceedApply(tune: Tune) {
        when (pendingMode) {
            RingtoneSetMode.AUDIO_ONLY -> setRingtoneOnly(tune)
            RingtoneSetMode.WITH_IMAGE_EVERYONE,
            RingtoneSetMode.WITH_IMAGE_CONTACT,
            -> if (CallThemeImageHelper.isUsableImage(pendingImagePath)) {
                if (pendingMode == RingtoneSetMode.WITH_IMAGE_EVERYONE) {
                    requestCallDisplayPermissions(tune)
                } else {
                    requestContactsPermission()
                }
            } else {
                pendingImagePath = null
                pendingPhotoSource = null
                showPhotoSheet()
            }
        }
    }

    private fun showModeSheet(tune: Tune) {
        SetRingtoneBottomSheet(
            context = activity,
            onContinue = { mode ->
                activity.mixpanelAnalytics().trackSetModeSelected(
                    setMode = mode.analyticsValue,
                    source = analyticsSource,
                    tuneId = tune.id,
                    personalized = flowPersonalized,
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
            RingtoneSetMode.AUDIO_ONLY ->
                if (phase == Phase.CHOOSE) completeChoice(tune, mode, null, null) else setRingtoneOnly(tune)
            RingtoneSetMode.WITH_IMAGE_EVERYONE,
            RingtoneSetMode.WITH_IMAGE_CONTACT,
            -> showPhotoSheet()
        }
    }

    /** CHOOSE: copies the picked photo into staging (it must outlive this activity), then completes. */
    private fun stageAndComplete(tune: Tune, uri: Uri) {
        activity.lifecycleScope.launch {
            val path = runCatching { CallThemeImageHelper.stageImage(activity, uri) }.getOrElse { error ->
                if (error is CancellationException) throw error
                Log.e(TAG, "Failed to stage call theme image", error)
                Toast.makeText(activity, R.string.call_theme_image_save_error, Toast.LENGTH_SHORT).show()
                failFlow(SetFailureClassifier.forError(SetFailureStage.PHOTO_SHEET, error))
                return@launch
            }
            if (pendingPhotoSource == PHOTO_SOURCE_CAMERA) {
                cameraOutputFile?.delete()
            }
            completeChoice(tune, pendingMode, path, pendingPhotoSource)
        }
    }

    /** CHOOSE finished: the flow closes here (no analytics) and resumes on the final screen. */
    private fun completeChoice(tune: Tune, mode: RingtoneSetMode, imagePath: String?, photoSource: String?) {
        val choice = SetChoice(
            mode = mode,
            imagePath = imagePath,
            photoSource = photoSource,
            chooseDurationMs = (SystemClock.elapsedRealtime() - flowStartedAtMs).coerceAtLeast(0L),
        )
        clearPending()
        onChosen(tune, choice)
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
                if (phase == Phase.CHOOSE) {
                    stageAndComplete(tune, uri)
                    return@UploadPhotoBottomSheet
                }
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
        awaitingLaunchResult = true
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
        awaitingLaunchResult = true
        runtimePermissionsLauncher.launch(
            arrayOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS,
            ),
        )
    }

    private fun launchContactPicker() {
        awaitingLaunchResult = true
        contactPickerLauncher.launch(null)
    }

    private fun launchImagePicker() {
        awaitingImagePick = true
        awaitingLaunchResult = true
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
            cameraOutputFile = file
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
        awaitingLaunchResult = true
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
            val themeResult = runCatching {
                // A staged (create path) photo moves into call_themes/ only now that the ringtone
                // is set, so a failed set keeps it for a retry.
                val themePath = CallThemeImageHelper.promoteStaged(activity, imagePath)
                pendingImagePath = themePath
                saveTheme(tune, mode, themePath, uri)
            }.getOrElse { error ->
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
        val personalized = flowPersonalized
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
            awaitingLaunchResult = true
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
                personalized = flowPersonalized,
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
        cameraOutputFile = null
        phase = Phase.SINGLE_SHOT
        flowPersonalized = false
        writeSettingsPreflight = false
        awaitingLaunchResult = false
        photoSheet?.dismiss()
        photoSheet = null
    }

    /**
     * Saved only while a system screen (permission dialog, Settings, camera, gallery, contact
     * picker) is on top: its result then continues this flow after process death. A flow with only
     * a sheet showing is not saved, the same as before (the sheet is lost with the activity).
     */
    private fun saveFlowState(): Bundle {
        val tune = pendingTune
        if (tune == null || !awaitingLaunchResult) return Bundle()
        return Bundle().apply {
            putString(STATE_PHASE, phase.name)
            putString(STATE_TUNE_JSON, tune.toIntentJson())
            putString(STATE_MODE, pendingMode.name)
            putString(STATE_IMAGE_PATH, pendingImagePath)
            putString(STATE_IMAGE_URI, pendingImageUri?.toString())
            putString(STATE_PHOTO_SOURCE, pendingPhotoSource)
            putString(STATE_CAMERA_URI, cameraOutputUri?.toString())
            putString(STATE_CAMERA_FILE, cameraOutputFile?.absolutePath)
            putString(STATE_CONTACT_URI, pendingContact?.contactUri?.toString())
            putLong(STATE_FLOW_STARTED_AT_MS, flowStartedAtMs)
            putBoolean(STATE_AWAITING_IMAGE_PICK, awaitingImagePick)
            putBoolean(STATE_AWAITING_WRITE_SETTINGS, awaitingWriteSettings)
            putBoolean(STATE_WRITE_SETTINGS_PREFLIGHT, writeSettingsPreflight)
            putBoolean(STATE_FLOW_PERSONALIZED, flowPersonalized)
            putString(STATE_PERMISSION_STEP, permissionStep.name)
        }
    }

    private fun restoreFlowState(state: Bundle?) {
        if (state == null || pendingTune != null) return
        val tune = Tune.fromIntentJson(state.getString(STATE_TUNE_JSON)) ?: return
        phase = Phase.entries.firstOrNull { it.name == state.getString(STATE_PHASE) } ?: return
        pendingTune = tune
        pendingMode = RingtoneSetMode.entries.firstOrNull { it.name == state.getString(STATE_MODE) }
            ?: RingtoneSetMode.AUDIO_ONLY
        pendingImagePath = state.getString(STATE_IMAGE_PATH)
        pendingImageUri = state.getString(STATE_IMAGE_URI)?.let(Uri::parse)
        pendingPhotoSource = state.getString(STATE_PHOTO_SOURCE)
        cameraOutputUri = state.getString(STATE_CAMERA_URI)?.let(Uri::parse)
        cameraOutputFile = state.getString(STATE_CAMERA_FILE)?.let(::File)
        pendingContact = state.getString(STATE_CONTACT_URI)?.let { raw ->
            runCatching { ContactLookupHelper.loadDetails(activity, Uri.parse(raw)) }.getOrNull()
        }
        flowStartedAtMs = state.getLong(STATE_FLOW_STARTED_AT_MS, SystemClock.elapsedRealtime())
        awaitingImagePick = state.getBoolean(STATE_AWAITING_IMAGE_PICK, false)
        awaitingWriteSettings = state.getBoolean(STATE_AWAITING_WRITE_SETTINGS, false)
        writeSettingsPreflight = state.getBoolean(STATE_WRITE_SETTINGS_PREFLIGHT, false)
        flowPersonalized = state.getBoolean(STATE_FLOW_PERSONALIZED, false)
        permissionStep = PermissionStep.entries.firstOrNull { it.name == state.getString(STATE_PERMISSION_STEP) }
            ?: PermissionStep.NONE
        awaitingLaunchResult = true
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
        const val PHOTO_SOURCE_CAMERA = SetChoice.PHOTO_SOURCE_CAMERA
        const val PHOTO_SOURCE_GALLERY = SetChoice.PHOTO_SOURCE_GALLERY

        const val SAVED_STATE_KEY = "com.spacewire.meratune.calltheme.RingtoneSetController"
        const val STATE_PHASE = "phase"
        const val STATE_TUNE_JSON = "tune_json"
        const val STATE_MODE = "mode"
        const val STATE_IMAGE_PATH = "image_path"
        const val STATE_IMAGE_URI = "image_uri"
        const val STATE_PHOTO_SOURCE = "photo_source"
        const val STATE_CAMERA_URI = "camera_uri"
        const val STATE_CAMERA_FILE = "camera_file"
        const val STATE_CONTACT_URI = "contact_uri"
        const val STATE_FLOW_STARTED_AT_MS = "flow_started_at_ms"
        const val STATE_AWAITING_IMAGE_PICK = "awaiting_image_pick"
        const val STATE_AWAITING_WRITE_SETTINGS = "awaiting_write_settings"
        const val STATE_WRITE_SETTINGS_PREFLIGHT = "write_settings_preflight"
        const val STATE_FLOW_PERSONALIZED = "flow_personalized"
        const val STATE_PERMISSION_STEP = "permission_step"
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

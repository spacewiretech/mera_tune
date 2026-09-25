package com.spacewire.meratune.calltheme

import android.content.Intent

/**
 * The set-mode choice made on the song picker ("chuno"), applied on the final screen by
 * [RingtoneSetController.apply]. It travels as intent extras ChooseSong -> Processing -> Ready,
 * so it survives process death; a photo mode's [imagePath] is a staged file under `filesDir`.
 *
 * @param chooseDurationMs time spent on the sheets at chuno, added to `ringtone_set.flow_duration_ms`
 */
data class SetChoice(
    val mode: RingtoneSetMode,
    val imagePath: String?,
    val photoSource: String?,
    val chooseDurationMs: Long,
) {

    fun putInto(intent: Intent): Intent = intent
        .putExtra(EXTRA_SET_MODE, mode.name)
        .putExtra(EXTRA_SET_IMAGE_PATH, imagePath)
        .putExtra(EXTRA_SET_PHOTO_SOURCE, photoSource)
        .putExtra(EXTRA_SET_CHOOSE_MS, chooseDurationMs)

    companion object {
        const val PHOTO_SOURCE_CAMERA = "camera"
        const val PHOTO_SOURCE_GALLERY = "gallery"

        private const val EXTRA_SET_MODE = "extra_set_mode"
        private const val EXTRA_SET_IMAGE_PATH = "extra_set_image_path"
        private const val EXTRA_SET_PHOTO_SOURCE = "extra_set_photo_source"
        private const val EXTRA_SET_CHOOSE_MS = "extra_set_choose_ms"

        /** `null` when [intent] carries no (valid) choice. */
        fun from(intent: Intent?): SetChoice? {
            if (intent == null) return null
            return parse(
                modeName = intent.getStringExtra(EXTRA_SET_MODE),
                imagePath = intent.getStringExtra(EXTRA_SET_IMAGE_PATH),
                photoSource = intent.getStringExtra(EXTRA_SET_PHOTO_SOURCE),
                chooseMs = intent.getLongExtra(EXTRA_SET_CHOOSE_MS, 0L),
            )
        }

        /**
         * Pure extras parser: an unknown or blank mode gives `null`; audio-only never carries an
         * image path; an unknown photo source is dropped; a negative duration becomes 0.
         */
        internal fun parse(
            modeName: String?,
            imagePath: String?,
            photoSource: String?,
            chooseMs: Long,
        ): SetChoice? {
            val mode = RingtoneSetMode.entries.firstOrNull { it.name == modeName?.trim() } ?: return null
            val isPhotoMode = mode != RingtoneSetMode.AUDIO_ONLY
            return SetChoice(
                mode = mode,
                imagePath = imagePath?.takeIf { isPhotoMode && it.isNotBlank() },
                photoSource = photoSource?.takeIf { isPhotoMode && (it == PHOTO_SOURCE_CAMERA || it == PHOTO_SOURCE_GALLERY) },
                chooseDurationMs = chooseMs.coerceAtLeast(0L),
            )
        }
    }
}

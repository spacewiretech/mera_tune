package com.spacewire.meratune.calltheme

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.spacewire.meratune.R

enum class RingtoneSetMode(
    @param:DrawableRes val illustrationRes: Int,
    @param:StringRes val titleRes: Int,
    val analyticsValue: String,
) {
    AUDIO_ONLY(
        illustrationRes = R.drawable.ic_set_mode_audio,
        titleRes = R.string.set_ringtone_option_audio_title,
        analyticsValue = "audio_only",
    ),
    WITH_IMAGE_EVERYONE(
        illustrationRes = R.drawable.ic_set_mode_photo,
        titleRes = R.string.set_ringtone_option_everyone_title,
        analyticsValue = "with_image_everyone",
    ),
    WITH_IMAGE_CONTACT(
        illustrationRes = R.drawable.ic_set_mode_contacts,
        titleRes = R.string.set_ringtone_option_contact_title,
        analyticsValue = "with_image_contact",
    ),
}

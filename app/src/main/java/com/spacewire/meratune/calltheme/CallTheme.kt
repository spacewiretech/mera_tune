package com.spacewire.meratune.calltheme

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CallThemeScope {
    @SerialName("everyone")
    EVERYONE,

    @SerialName("contact")
    CONTACT,
}

@Serializable
data class CallTheme(
    val id: String,
    val scope: CallThemeScope,
    @SerialName("contact_name") val contactName: String? = null,
    @SerialName("contact_uri") val contactUri: String? = null,
    @SerialName("phone_keys") val phoneKeys: List<String> = emptyList(),
    @SerialName("image_path") val imagePath: String,
    @SerialName("tune_id") val tuneId: String,
    @SerialName("tune_name") val tuneName: String,
    @SerialName("updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

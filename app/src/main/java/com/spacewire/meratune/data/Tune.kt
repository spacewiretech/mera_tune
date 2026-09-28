package com.spacewire.meratune.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Category(
    val id: String,
    val name: String,
    @SerialName("image_url") val imageUrl: String = "",
    @SerialName("is_active") val isActive: Boolean = true,
) {
    companion object {
        const val ALL_CATEGORY_ID = "__all__"

        /**
         * The synthetic Home "{name} Tunes" chip (after All Tunes): the user's own created
         * ringtones, then every active tune whose title contains the profile's first name. Never a
         * DB id.
         */
        const val MY_NAME_CATEGORY_ID = "__my_name__"

        /** [MY_NAME_CATEGORY_ID]'s `category_name` / `category_filter` in analytics; never the user's name. */
        const val MY_NAME_CATEGORY_NAME = "my_name"
    }
}

@Serializable
data class Tune(
    val id: String,
    val name: String,
    @SerialName("category_id") val categoryId: String,
    val gender: String,
    val language: String,
    @SerialName("tune_url") val tuneUrl: String,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("likes_count") val likesCount: Int = 0,
    @SerialName("views_count") val viewsCount: Int = 0,
    val category: Category? = null,
    @SerialName("is_personalizable") val isPersonalizable: Boolean = false,
    @SerialName("featured_rank") val featuredRank: Int? = null,
    @SerialName("sample_name") val sampleName: String? = null,
    @SerialName("title_template") val titleTemplate: String? = null,
    @SerialName("assets_version") val assetsVersion: Int = 1,
    /** Client-only: set on a personalized copy of a tune after generation. Never returned by Postgrest. */
    @SerialName("generation_id") val generationId: String? = null,
) {
    /** `"male"`, `"female"` or `""` when the gender column holds anything else. Case-insensitive. */
    val voiceKey: String
        get() {
            val normalized = gender.trim().lowercase()
            return when {
                normalized.contains("female") -> VOICE_FEMALE
                normalized.contains("male") -> VOICE_MALE
                else -> ""
            }
        }

    /**
     * Home list / player / Active key. A personalized copy shares its base tune's [id] (and so do
     * several copies of one song for different names), so it is keyed by its [generationId]; a
     * catalog tune by its [id]. Not serialized (no backing field).
     */
    val rowKey: String
        get() = generationId?.let { GENERATION_ROW_KEY_PREFIX + it } ?: id

    /** Serializes this tune for an `Intent` extra (the project has no Parcelize plugin). */
    fun toIntentJson(): String = intentJson.encodeToString(serializer(), this)

    companion object {
        const val VOICE_MALE = "male"
        const val VOICE_FEMALE = "female"
        private const val GENERATION_ROW_KEY_PREFIX = "generation:"

        private val intentJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Inverse of [toIntentJson]; returns `null` for a missing, blank or malformed payload. */
        fun fromIntentJson(raw: String?): Tune? {
            if (raw.isNullOrBlank()) return null
            return runCatching { intentJson.decodeFromString(serializer(), raw) }.getOrNull()
        }
    }
}

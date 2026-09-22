package com.spacewire.meratune.data

import android.content.Context
import com.spacewire.meratune.network.SupabaseProvider
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SubscriptionVideo(
    @SerialName("locale_code") val localeCode: String,
    @SerialName("video_url") val videoUrl: String,
)

class SubscriptionVideoRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun cachedVideoUrl(localeCode: String): String? =
        prefs.getString(cacheKey(localeCode), null)?.takeIf { it.isNotBlank() }

    fun cacheVideoUrl(localeCode: String, url: String) {
        if (url.isBlank()) return
        prefs.edit().putString(cacheKey(localeCode), url).apply()
    }

    suspend fun fetchVideoUrl(localeCode: String): String? {
        val preferred = fetchActiveUrl(localeCode)
        if (!preferred.isNullOrBlank()) return preferred
        if (!localeCode.equals(FALLBACK_LOCALE, ignoreCase = true)) {
            return fetchActiveUrl(FALLBACK_LOCALE)
        }
        return null
    }

    private suspend fun fetchActiveUrl(localeCode: String): String? {
        return SupabaseProvider.client
            .from("subscription_videos")
            .select {
                filter {
                    eq("locale_code", localeCode)
                    eq("is_active", true)
                }
            }
            .decodeList<SubscriptionVideo>()
            .firstOrNull()
            ?.videoUrl
            ?.takeIf { it.isNotBlank() }
    }

    private fun cacheKey(localeCode: String) = "$KEY_URL_PREFIX${localeCode.lowercase()}"

    companion object {
        const val FALLBACK_LOCALE = "en"
        private const val PREFS_NAME = "subscription_videos"
        private const val KEY_URL_PREFIX = "url_"
    }
}

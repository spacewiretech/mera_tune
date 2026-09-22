package com.spacewire.meratune.util

import android.content.Context
import com.spacewire.meratune.data.Languages

data class UserProfile(
    val name: String,
    val phone: String,
    val selectedLanguage: String,
)

class ProfileStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val authStore = AuthStore(appContext)

    fun getProfile(): UserProfile {
        val authPrefs = appContext.getSharedPreferences(AuthStore.PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_NAME, null)
            ?: authPrefs.getString("name", "").orEmpty()
        val phoneRaw = prefs.getString(KEY_PHONE, null)
            ?: authPrefs.getString("phone", "").orEmpty()

        return UserProfile(
            name = name,
            phone = if (phoneRaw.isNotBlank()) PhoneUtils.formatDisplayPhone(phoneRaw) else "",
            selectedLanguage = prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE)
                .orEmpty()
                .ifBlank { DEFAULT_LANGUAGE },
        )
    }

    fun saveUser(name: String, phone: String) {
        prefs.edit()
            .putString(KEY_NAME, name)
            .putString(KEY_PHONE, phone)
            .apply()
    }

    fun getLocaleCode(): String {
        val stored = prefs.getString(KEY_LOCALE, null)
        if (!stored.isNullOrBlank()) return stored
        return Languages.localeCodeForStorageValue(getProfile().selectedLanguage)
    }

    fun saveSelectedLanguage(language: String, localeCode: String) {
        prefs.edit()
            .putString(KEY_LANGUAGE, language)
            .putString(KEY_LOCALE, localeCode)
            .putBoolean(KEY_LANGUAGE_SELECTED, true)
            .apply()
    }

    fun hasSelectedLanguage(): Boolean =
        prefs.getBoolean(KEY_LANGUAGE_SELECTED, false)

    fun markLanguageSelected() {
        prefs.edit().putBoolean(KEY_LANGUAGE_SELECTED, true).apply()
    }

    fun clearSession() {
        authStore.clearSession()
        prefs.edit()
            .remove(KEY_NAME)
            .remove(KEY_PHONE)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "user_profile"
        const val KEY_NAME = "name"
        const val KEY_PHONE = "phone"
        const val KEY_LANGUAGE = "language"
        const val KEY_LOCALE = "locale"
        const val KEY_LANGUAGE_SELECTED = "language_selected"
        const val DEFAULT_LANGUAGE = "English"
        const val DEFAULT_LOCALE = "en"
    }
}

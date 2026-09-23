package com.spacewire.meratune.util

import android.content.Context
import com.spacewire.meratune.data.User

class AuthStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isLoggedIn(): Boolean = prefs.getLong(KEY_USER_ID, -1L) > 0L

    fun getUserId(): Long = prefs.getLong(KEY_USER_ID, -1L)

    fun getStatus(): String = prefs.getString(KEY_STATUS, "none").orEmpty().ifBlank { "none" }

    /** Long-lived API token issued by `verify-otp` / `complete-signup`; `null` for legacy sessions. */
    fun getApiToken(): String? = prefs.getString(KEY_API_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun saveApiToken(token: String) {
        prefs.edit().putString(KEY_API_TOKEN, token).apply()
    }

    /**
     * Stores the token from a fresh login, or clears any previous one when the server issued none,
     * so a revoked or other-account token never outlives the login that replaced it.
     */
    fun replaceApiToken(token: String?) {
        val value = token?.trim().orEmpty()
        prefs.edit().apply {
            if (value.isEmpty()) remove(KEY_API_TOKEN) else putString(KEY_API_TOKEN, value)
        }.apply()
    }

    /** Updates the profile fields only; the API token is left untouched. */
    fun saveUser(user: User) {
        prefs.edit()
            .putLong(KEY_USER_ID, user.id)
            .putString(KEY_PHONE, user.phone)
            .putString(KEY_NAME, user.name.orEmpty())
            .putString(KEY_STATUS, user.status)
            .apply()
    }

    /** Logout: drops the profile and the API token. */
    fun clearSession() {
        prefs.edit().clear().apply()
    }

    companion object {
        /** Backed by `auth_session.xml`; excluded from Auto Backup in `res/xml/backup_rules.xml` and `data_extraction_rules.xml`. */
        const val PREFS_NAME = "auth_session"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_PHONE = "phone"
        private const val KEY_NAME = "name"
        private const val KEY_STATUS = "status"
        private const val KEY_API_TOKEN = "api_token"
    }
}

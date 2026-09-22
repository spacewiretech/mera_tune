package com.spacewire.meratune.util

import android.content.Context
import com.spacewire.meratune.data.User

class AuthStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isLoggedIn(): Boolean = prefs.getLong(KEY_USER_ID, -1L) > 0L

    fun getUserId(): Long = prefs.getLong(KEY_USER_ID, -1L)

    fun getStatus(): String = prefs.getString(KEY_STATUS, "none").orEmpty().ifBlank { "none" }

    fun saveUser(user: User) {
        prefs.edit()
            .putLong(KEY_USER_ID, user.id)
            .putString(KEY_PHONE, user.phone)
            .putString(KEY_NAME, user.name.orEmpty())
            .putString(KEY_STATUS, user.status)
            .apply()
    }

    fun clearSession() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val PREFS_NAME = "auth_session"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_PHONE = "phone"
        private const val KEY_NAME = "name"
        private const val KEY_STATUS = "status"
    }
}

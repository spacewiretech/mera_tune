package com.spacewire.meratune.calltheme

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class CallThemeStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun getAll(): List<CallTheme> {
        val raw = prefs.getString(KEY_THEMES, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(CallTheme.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    fun resolveForIncomingNumber(incomingNumber: String?): CallTheme? {
        val themes = getAll()
        val phoneKey = PhoneMatch.normalizeKey(incomingNumber)
        if (phoneKey != null) {
            themes.firstOrNull { theme ->
                theme.scope == CallThemeScope.CONTACT && phoneKey in theme.phoneKeys
            }?.let { return it }
        }
        return themes.firstOrNull { it.scope == CallThemeScope.EVERYONE }
    }

    fun latestWithImage(): CallTheme? {
        return getAll()
            .filter { theme -> File(theme.imagePath).exists() }
            .maxByOrNull { it.updatedAt }
    }

    fun hasAnyTheme(): Boolean = getAll().isNotEmpty()

    fun saveEveryone(
        imagePath: String,
        tuneId: String,
        tuneName: String,
    ): CallTheme {
        val existing = getAll().filterNot { it.scope == CallThemeScope.EVERYONE }
        val theme = CallTheme(
            id = UUID.randomUUID().toString(),
            scope = CallThemeScope.EVERYONE,
            imagePath = imagePath,
            tuneId = tuneId,
            tuneName = tuneName,
        )
        persist(existing + theme)
        return theme
    }

    fun saveContact(
        contactName: String?,
        contactUri: String?,
        phoneKeys: List<String>,
        imagePath: String,
        tuneId: String,
        tuneName: String,
    ): CallTheme {
        val keys = phoneKeys.mapNotNull(PhoneMatch::normalizeKey).distinct()
        require(keys.isNotEmpty()) { "Contact has no usable phone number" }

        val remaining = getAll().filterNot { theme ->
            theme.scope == CallThemeScope.CONTACT &&
                theme.phoneKeys.any { it in keys }
        }
        val theme = CallTheme(
            id = UUID.randomUUID().toString(),
            scope = CallThemeScope.CONTACT,
            contactName = contactName,
            contactUri = contactUri,
            phoneKeys = keys,
            imagePath = imagePath,
            tuneId = tuneId,
            tuneName = tuneName,
        )
        persist(remaining + theme)
        return theme
    }

    fun clearEveryone() {
        persist(getAll().filterNot { it.scope == CallThemeScope.EVERYONE })
    }

    fun delete(themeId: String) {
        val theme = getAll().firstOrNull { it.id == themeId }
        persist(getAll().filterNot { it.id == themeId })
        theme?.imagePath?.let { path ->
            runCatching { File(path).takeIf { it.exists() }?.delete() }
        }
    }

    private fun persist(themes: List<CallTheme>) {
        prefs.edit()
            .putString(
                KEY_THEMES,
                json.encodeToString(ListSerializer(CallTheme.serializer()), themes),
            )
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "call_themes"
        const val KEY_THEMES = "themes_json"
    }
}

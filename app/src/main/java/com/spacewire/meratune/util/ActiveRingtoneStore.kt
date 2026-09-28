package com.spacewire.meratune.util

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri

/** The saved MeraTune ringtone stopped being the system default. */
data class ReplacedRingtone(
    val tuneId: String,
    /** `null` for ringtones saved before this was recorded. */
    val personalized: Boolean?,
    val daysSinceSet: Int?,
)

class ActiveRingtoneStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(tuneId: String, uri: Uri, personalized: Boolean = false) {
        prefs.edit()
            .putString(KEY_TUNE_ID, tuneId)
            .putString(KEY_RINGTONE_URI, uri.toString())
            .putLong(KEY_SET_AT, System.currentTimeMillis())
            .putBoolean(KEY_PERSONALIZED, personalized)
            .remove(KEY_REPLACED_REPORTED)
            .remove(KEY_LEGACY_BASELINE)
            .apply()
    }

    /** [tune] as set: for a personalized copy ([personalized]), its base tune id is recorded. */
    fun save(tune: com.spacewire.meratune.data.Tune, uri: Uri, personalized: Boolean = tune.generationId != null) {
        save(tune.id, uri, personalized)
    }

    fun resolveActiveTuneId(context: Context, tunes: List<com.spacewire.meratune.data.Tune>): String? {
        if (tunes.isEmpty()) return null

        val systemUri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: return null

        val savedTuneId = prefs.getString(KEY_TUNE_ID, null)
        val savedUri = prefs.getString(KEY_RINGTONE_URI, null)?.let(Uri::parse)

        if (savedTuneId != null && savedUri != null && urisMatch(systemUri, savedUri)) {
            return savedTuneId.takeIf { id -> tunes.any { it.id == id } }
        }

        val ringtoneTitle = RingtoneManager.getRingtone(context, systemUri)
            ?.getTitle(context)
            ?.toString()
            ?.trim()
            .orEmpty()

        if (ringtoneTitle.isNotEmpty()) {
            tunes.find { it.name.equals(ringtoneTitle, ignoreCase = true) }?.id?.let { return it }
        }

        return null
    }

    /**
     * Returns the saved ringtone once, the first time the system default no longer matches it
     * (including a silent default); `null` otherwise. Independent of the loaded tune list.
     * A save from before 1.3.0 (no `set_at`) is baselined on the first check: a replacement that
     * already happened is marked reported without an event, so the update does not backfill them.
     */
    fun consumeReplacedRingtone(context: Context): ReplacedRingtone? {
        if (prefs.getBoolean(KEY_REPLACED_REPORTED, false)) return null
        val savedTuneId = prefs.getString(KEY_TUNE_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val savedUri = prefs.getString(KEY_RINGTONE_URI, null)?.let(Uri::parse) ?: return null

        val systemUri = runCatching {
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
        }.getOrElse { return null }
        val stillActive = systemUri != null && urisMatch(systemUri, savedUri)

        if (!prefs.contains(KEY_SET_AT) && !prefs.getBoolean(KEY_LEGACY_BASELINE, false)) {
            val key = if (stillActive) KEY_LEGACY_BASELINE else KEY_REPLACED_REPORTED
            prefs.edit().putBoolean(key, true).apply()
            return null
        }
        if (stillActive) return null

        prefs.edit().putBoolean(KEY_REPLACED_REPORTED, true).apply()
        val setAt = prefs.getLong(KEY_SET_AT, 0L).takeIf { it > 0L }
        return ReplacedRingtone(
            tuneId = savedTuneId,
            personalized = if (prefs.contains(KEY_PERSONALIZED)) prefs.getBoolean(KEY_PERSONALIZED, false) else null,
            daysSinceSet = setAt?.let { ((System.currentTimeMillis() - it).coerceAtLeast(0L) / DAY_MS).toInt() },
        )
    }

    private fun urisMatch(first: Uri, second: Uri): Boolean {
        if (first == second) return true
        if (first.toString() == second.toString()) return true

        val firstId = first.lastPathSegment
        val secondId = second.lastPathSegment
        return firstId != null && firstId == secondId
    }

    private companion object {
        const val PREFS_NAME = "active_ringtone"
        const val KEY_TUNE_ID = "tune_id"
        const val KEY_RINGTONE_URI = "ringtone_uri"
        const val KEY_SET_AT = "set_at"
        const val KEY_PERSONALIZED = "personalized"
        const val KEY_REPLACED_REPORTED = "replaced_reported"

        /** A pre-1.3.0 save that was still the default when first checked after the update. */
        const val KEY_LEGACY_BASELINE = "legacy_baseline"
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}

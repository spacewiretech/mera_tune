package com.spacewire.meratune.util

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri

class ActiveRingtoneStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(tuneId: String, uri: Uri) {
        prefs.edit()
            .putString(KEY_TUNE_ID, tuneId)
            .putString(KEY_RINGTONE_URI, uri.toString())
            .apply()
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
    }
}

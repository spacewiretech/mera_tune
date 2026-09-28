package com.spacewire.meratune.util

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import com.spacewire.meratune.data.Tune

/** The saved MeraTune ringtone stopped being the system default. */
data class ReplacedRingtone(
    val tuneId: String,
    /** `null` for ringtones saved before this was recorded. */
    val personalized: Boolean?,
    val daysSinceSet: Int?,
)

/** The ringtone [ActiveRingtoneStore] last recorded as set by MeraTune. */
data class SavedActiveRingtone(
    /** The set tune's id: the BASE tune id for a personalized ringtone. */
    val tuneId: String,
    /** `null` for ringtones saved before this was recorded. */
    val personalized: Boolean?,
    /**
     * The personalized copy as it was set (title, ringtone url, generation id, category); `null`
     * for a catalog tune and for personalized saves from before the copy was recorded.
     */
    val personalizedTune: Tune? = null,
)

class ActiveRingtoneStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Records [tune] as the ringtone MeraTune just set as the default. A [personalized] tune is the
     * create path's copy of a base tune (same [Tune.id]); the copy itself is kept too, so Home shows
     * the user's own ringtone as Active, never the base catalog tune.
     */
    fun save(tune: Tune, uri: Uri, personalized: Boolean = tune.generationId != null) {
        val editor = prefs.edit()
            .putString(KEY_TUNE_ID, tune.id)
            .putString(KEY_RINGTONE_URI, uri.toString())
            .putLong(KEY_SET_AT, System.currentTimeMillis())
            .putBoolean(KEY_PERSONALIZED, personalized)
            .remove(KEY_REPLACED_REPORTED)
            .remove(KEY_LEGACY_BASELINE)
        if (personalized) {
            editor.putString(KEY_PERSONALIZED_TUNE, tune.toIntentJson())
        } else {
            editor.remove(KEY_PERSONALIZED_TUNE)
        }
        editor.apply()
    }

    fun savedRingtone(): SavedActiveRingtone? {
        val tuneId = prefs.getString(KEY_TUNE_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val personalized = if (prefs.contains(KEY_PERSONALIZED)) prefs.getBoolean(KEY_PERSONALIZED, false) else null
        return SavedActiveRingtone(
            tuneId = tuneId,
            personalized = personalized,
            personalizedTune = if (personalized == true) Tune.fromIntentJson(prefs.getString(KEY_PERSONALIZED_TUNE, null)) else null,
        )
    }

    /**
     * The saved personalized ringtone when it can be listed on its own (a generation id and a
     * ringtone url), whether or not it is still the default; `null` otherwise.
     */
    fun savedPersonalizedTune(): Tune? =
        savedRingtone()?.personalizedTune?.takeIf { !it.generationId.isNullOrBlank() && it.tuneUrl.isNotBlank() }

    /**
     * The [Tune.rowKey] of the row that is the live system ringtone among [catalog] and the user's
     * own ringtones [mine], see [ActiveRingtoneResolver.resolve]; `null` when there is none.
     */
    fun resolveActiveKey(context: Context, catalog: List<Tune>, mine: List<Tune>): String? {
        val systemUri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: return null
        val savedUri = prefs.getString(KEY_RINGTONE_URI, null)?.let(Uri::parse)

        return ActiveRingtoneResolver.resolve(
            saved = savedRingtone(),
            savedIsSystemDefault = savedUri != null && urisMatch(systemUri, savedUri),
            systemTitle = {
                RingtoneManager.getRingtone(context, systemUri)
                    ?.getTitle(context)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
            },
            catalog = catalog,
            mine = mine,
        )
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

        /** [Tune.toIntentJson] of a personalized ringtone as set; absent for catalog tunes and older saves. */
        const val KEY_PERSONALIZED_TUNE = "personalized_tune"
        const val KEY_REPLACED_REPORTED = "replaced_reported"

        /** A pre-1.3.0 save that was still the default when first checked after the update. */
        const val KEY_LEGACY_BASELINE = "legacy_baseline"
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}

/**
 * Pure rules for which Home row is the live system ringtone, keyed by [Tune.rowKey]. A personalized
 * ringtone is never its base catalog tune: the base tune shares its id but sings another name.
 */
internal object ActiveRingtoneResolver {

    /** Never a row key: a personalized ringtone is the default, but no listed row is it. */
    const val UNLISTED_PERSONALIZED_KEY = "personalized:unlisted"

    /**
     * [saved] is the store's record, [savedIsSystemDefault] whether its uri is the system default,
     * [systemTitle] the system ringtone's title (read only when needed; `""` when unknown).
     * - The saved catalog tune (or a save from before `personalized` was recorded): its id when it
     *   is in [catalog], as before.
     * - The saved personalized ringtone: the matching row of [mine] (same generation, or same base
     *   tune and ringtone url), else the saved copy's own key (Home lists that copy when [mine]
     *   lacks it). A save without the copy is told apart among the user's copies of that base tune
     *   by the system title (the set wrote the ringtone title), or is the only copy; else it is
     *   [UNLISTED_PERSONALIZED_KEY], so the base catalog tune is not marked.
     * - Anything else: the catalog tune, then the user's own ringtone, titled like the default.
     */
    fun resolve(
        saved: SavedActiveRingtone?,
        savedIsSystemDefault: Boolean,
        systemTitle: () -> String,
        catalog: List<Tune>,
        mine: List<Tune>,
    ): String? {
        if (saved != null && savedIsSystemDefault) {
            if (saved.personalized != false) {
                personalizedKey(saved, systemTitle, mine)?.let { return it }
                if (saved.personalized == true) return UNLISTED_PERSONALIZED_KEY
            }
            return saved.tuneId.takeIf { id -> catalog.any { it.id == id } }
        }

        val title = systemTitle().trim()
        if (title.isEmpty()) return null
        catalog.firstOrNull { it.name.equals(title, ignoreCase = true) }?.let { return it.id }
        return mine.firstOrNull { it.generationId != null && it.name.equals(title, ignoreCase = true) }?.rowKey
    }

    /**
     * Two personalized copies are one ringtone: the same generation, or the same base tune playing
     * the same file (the server lists one row per song and name, with its newest generation).
     */
    fun sameRingtone(first: Tune, second: Tune): Boolean {
        if (first.generationId != null && first.generationId == second.generationId) return true
        return first.id == second.id && first.tuneUrl.isNotBlank() && first.tuneUrl.trim() == second.tuneUrl.trim()
    }

    private fun personalizedKey(saved: SavedActiveRingtone, systemTitle: () -> String, mine: List<Tune>): String? {
        val copy = saved.personalizedTune?.takeIf { !it.generationId.isNullOrBlank() }
        if (copy != null) {
            return (mine.firstOrNull { it.generationId != null && sameRingtone(it, copy) } ?: copy).rowKey
        }

        val copies = mine.filter { it.generationId != null && it.id == saved.tuneId }
        if (copies.isEmpty()) return null
        val title = systemTitle().trim()
        if (title.isNotEmpty()) {
            copies.firstOrNull { it.name.equals(title, ignoreCase = true) }?.let { return it.rowKey }
        }
        return copies.singleOrNull()?.takeIf { saved.personalized == true }?.rowKey
    }
}

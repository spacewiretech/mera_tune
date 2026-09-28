package com.spacewire.meratune.ui

import androidx.annotation.StringRes
import com.spacewire.meratune.R
import com.spacewire.meratune.data.Tune

/**
 * Pure rules of the create flow's "existing name ringtones" step (`NameRingtonesActivity`), kept
 * free of `android.*` so they run in JVM unit tests.
 */
internal object NameRingtonesPolicy {

    /**
     * The `fetchNameRingtones` rows the screen lists, in server order: a non-blank tune id and an
     * https ringtone URL, first row per tune id (the screen keys rows, previews and Set by tune id).
     * Empty means the form continues straight to the song picker.
     */
    fun rowsToShow(tunes: List<Tune>): List<Tune> =
        tunes
            .filter { it.id.isNotBlank() && it.tuneUrl.trim().startsWith("https://") }
            .distinctBy { it.id }

    /** A row the user has not made yet: Set records it in their own list first (`claim`). */
    fun needsClaim(tune: Tune): Boolean = tune.generationId.isNullOrBlank()

    /** [rows] with the row of [claimed]'s tune id replaced by [claimed]. */
    fun replaceRow(rows: List<Tune>, claimed: Tune): List<Tune> =
        rows.map { if (it.id == claimed.id) claimed else it }

    /** The "Male voice" / "Female voice" badge for [Tune.voiceKey]; `null` hides it. */
    @StringRes
    fun voiceLabelRes(voiceKey: String): Int? = when (voiceKey) {
        Tune.VOICE_MALE -> R.string.create_form_voice_male
        Tune.VOICE_FEMALE -> R.string.create_form_voice_female
        else -> null
    }

    /** Intent / saved-state form of [rows] (the project has no Parcelize plugin). */
    fun encode(rows: List<Tune>): ArrayList<String> = rows.mapTo(ArrayList(rows.size)) { it.toIntentJson() }

    /** Inverse of [encode]; malformed entries are dropped. */
    fun decode(raw: List<String>?): List<Tune> = raw.orEmpty().mapNotNull(Tune::fromIntentJson)
}

package com.spacewire.meratune.ui

import androidx.annotation.StringRes
import com.spacewire.meratune.R
import com.spacewire.meratune.analytics.CreationEntryPoint
import com.spacewire.meratune.data.Tune
import com.spacewire.meratune.util.NameMatch

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

    /**
     * The form opens the song picker without the step when it was launched from Home (the
     * "{name} Tunes" CTA or the search CTA, [entryPoint] of the launch): Home already lists the
     * name's tunes. The member screen after the trial (and any other entry) looks up the name.
     */
    fun skipsLookup(entryPoint: String?): Boolean =
        entryPoint == CreationEntryPoint.MY_NAME_CHIP || entryPoint == CreationEntryPoint.SEARCH_BAR

    /**
     * Stock catalog tunes named for [name] (whole-word title match, [NameMatch], like Home's name
     * chip): listed after the server's personalized rows. Personalizable tunes are left out: their
     * stock recording sings the sample name, and the server already lists the one that sings [name].
     */
    fun catalogNameTunes(catalog: List<Tune>, name: String): List<Tune> =
        catalog.filter { !it.isPersonalizable && NameMatch.titleHasName(it.name, name) }

    /**
     * A personalized row (a render or sample of a personalizable tune) the user has not made yet:
     * Set records it in their own list first (`claim`). A stock catalog tune is set as it is.
     */
    fun needsClaim(tune: Tune): Boolean = tune.generationId.isNullOrBlank() && tune.isPersonalizable

    /** Sings the user's name (their own ringtone, a render or a sample); `false` for a stock catalog tune. */
    fun isPersonalized(tune: Tune): Boolean = !tune.generationId.isNullOrBlank() || tune.isPersonalizable

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

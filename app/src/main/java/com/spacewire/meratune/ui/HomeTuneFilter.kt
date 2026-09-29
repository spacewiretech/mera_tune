package com.spacewire.meratune.ui

import com.spacewire.meratune.data.Category
import com.spacewire.meratune.data.Tune
import com.spacewire.meratune.util.NameMatch
import com.spacewire.meratune.util.ActiveRingtoneResolver

/**
 * Pure Home list rules: the search box and the synthetic "{name} Tunes" chip
 * ([com.spacewire.meratune.data.Category.MY_NAME_CATEGORY_ID]). Both match a tune title the same
 * way (case-insensitive substring), and both apply together (name AND query).
 */
object HomeTuneFilter {

    private val WHITESPACE = Regex("\\s+")

    /** The profile's first word ("Ram Kumar" -> "Ram"); blank when the profile has no name. */
    fun firstName(profileName: String?): String =
        profileName.orEmpty().trim().split(WHITESPACE).firstOrNull().orEmpty()

    /** The search match (unchanged from before the chip): the raw query inside the title. */
    fun matches(tune: Tune, text: String): Boolean = tune.name.contains(text, ignoreCase = true)

    /**
     * [nameFilter] is the chip's first name while the chip is selected, else `null`: a tune whose
     * title has that name as a whole word ([NameMatch], so "Ram" is not "Ramesh"). A blank name
     * matches nothing, so the chip then shows the empty state. A blank [query] keeps every tune.
     */
    fun filter(tunes: List<Tune>, query: String, nameFilter: String?): List<Tune> {
        val byName = when {
            nameFilter == null -> tunes
            nameFilter.isBlank() -> emptyList()
            else -> tunes.filter { NameMatch.titleHasName(it.name, nameFilter) }
        }
        if (query.isBlank()) return byName
        return byName.filter { matches(it, query) }
    }

    /**
     * The "{name} Tunes" list: the user's own ringtones first, then the [catalog] tunes whose title
     * has [firstName]; [query] applies to both, and the [activeKey] row is pinned first.
     * Own ringtones are [mine] as the server orders them (newest first), or nothing while [mine] is
     * unavailable (`null`: not loaded or failed); [savedPersonalized] (the locally saved
     * personalized ringtone) is added first when [mine] does not have it, so it always appears.
     */
    fun nameTab(
        catalog: List<Tune>,
        mine: List<Tune>?,
        savedPersonalized: Tune?,
        firstName: String,
        query: String,
        activeKey: String?,
    ): List<Tune> {
        val own = mine.orEmpty().filter { it.generationId != null }
        val saved = savedPersonalized?.takeIf { copy ->
            copy.generationId != null && own.none { ActiveRingtoneResolver.sameRingtone(it, copy) }
        }
        val ownRows = listOfNotNull(saved) + own
        val listed = filter(ownRows, query, nameFilter = null) + filter(catalog, query, firstName)
        return withActiveFirst(listed.distinctBy { it.rowKey }, activeKey)
    }

    /**
     * A search searches every active tune: typing the first character of a [query] while a chip
     * other than All Tunes is selected ([selectedCategoryId]) moves Home to All Tunes. A chip
     * picked after that still narrows the results (the query was not blank then).
     */
    fun searchMovesToAllTunes(previousQuery: String, query: String, selectedCategoryId: String?): Boolean =
        previousQuery.isBlank() &&
            query.isNotBlank() &&
            (selectedCategoryId ?: Category.ALL_CATEGORY_ID) != Category.ALL_CATEGORY_ID

    /** [tunes] with the [activeKey] row ([Tune.rowKey]) moved to the top; unchanged without one. */
    fun withActiveFirst(tunes: List<Tune>, activeKey: String?): List<Tune> {
        val active = activeKey?.let { key -> tunes.find { it.rowKey == key } } ?: return tunes
        return listOf(active) + tunes.filter { it.rowKey != activeKey }
    }

    /**
     * The name in the empty state's message and "Make %1$s tune" CTA (and the create-form prefill):
     * the trimmed search query when there is one, else the chip's first name; blank otherwise.
     */
    fun emptyStateName(query: String, myNameSelected: Boolean, firstName: String): String =
        query.trim().ifEmpty { if (myNameSelected) firstName.trim() else "" }
}

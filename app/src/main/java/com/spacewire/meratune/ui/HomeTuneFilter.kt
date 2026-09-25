package com.spacewire.meratune.ui

import com.spacewire.meratune.data.Tune

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
     * [nameFilter] is the chip's first name while the chip is selected, else `null`. A blank name
     * matches nothing, so the chip then shows the empty state. A blank [query] keeps every tune.
     */
    fun filter(tunes: List<Tune>, query: String, nameFilter: String?): List<Tune> {
        val byName = when {
            nameFilter == null -> tunes
            nameFilter.isBlank() -> emptyList()
            else -> tunes.filter { matches(it, nameFilter.trim()) }
        }
        if (query.isBlank()) return byName
        return byName.filter { matches(it, query) }
    }

    /**
     * The name in the empty state's message and "Make %1$s tune" CTA (and the create-form prefill):
     * the trimmed search query when there is one, else the chip's first name; blank otherwise.
     */
    fun emptyStateName(query: String, myNameSelected: Boolean, firstName: String): String =
        query.trim().ifEmpty { if (myNameSelected) firstName.trim() else "" }
}

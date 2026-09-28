package com.spacewire.meratune.ui

import com.spacewire.meratune.data.Category
import com.spacewire.meratune.data.Tune
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNameTabTest {

    private fun tune(id: String, name: String, url: String = "https://cdn.example/stock/$id.mp3", generationId: String? = null) =
        Tune(
            id = id,
            name = name,
            categoryId = "c1",
            gender = "Male",
            language = "Hindi",
            tuneUrl = url,
            generationId = generationId,
        )

    private val shyam = tune("base", "Jai Shri Shyam")
    private val lakshyaStock = tune("stock-lakshya", "Happy Birthday Lakshya")
    private val radhe = tune("radhe", "Radhe Radhe")
    private val catalog = listOf(shyam, lakshyaStock, radhe)

    /** Own ringtones, newest first as the server returns them. */
    private val ownNew = tune("base", "Jai Shri Lakshya", url = "https://cdn.example/r/lakshya.mp3", generationId = "g2")
    private val ownOld = tune("radhe", "Radhe Priya", url = "https://cdn.example/r/priya.mp3", generationId = "g1")
    private val mine = listOf(ownNew, ownOld)

    private fun keys(result: List<Tune>) = result.map { it.rowKey }

    private fun nameTab(
        mine: List<Tune>? = this.mine,
        saved: Tune? = null,
        query: String = "",
        activeKey: String? = null,
        firstName: String = "Lakshya",
    ) = HomeTuneFilter.nameTab(catalog, mine, saved, firstName, query, activeKey)

    @Test
    fun `own ringtones come first, newest first, then catalog tunes with the name`() {
        assertEquals(listOf(ownNew.rowKey, ownOld.rowKey, "stock-lakshya"), keys(nameTab()))
    }

    @Test
    fun `the base catalog tune is not listed or marked for an own copy`() {
        val result = nameTab(activeKey = ownNew.rowKey)
        assertFalse(result.any { it.generationId == null && it.id == "base" })
        assertEquals(ownNew.rowKey, result.first().rowKey)
    }

    @Test
    fun `active row is pinned first`() {
        assertEquals(listOf(ownOld.rowKey, ownNew.rowKey, "stock-lakshya"), keys(nameTab(activeKey = ownOld.rowKey)))
        assertEquals(listOf("stock-lakshya", ownNew.rowKey, ownOld.rowKey), keys(nameTab(activeKey = "stock-lakshya")))
        // An active tune not in this list (another category's) changes nothing.
        assertEquals(keys(nameTab()), keys(nameTab(activeKey = "radhe")))
    }

    @Test
    fun `own ringtones unavailable falls back to the saved personalized ringtone`() {
        val saved = ownNew
        assertEquals(listOf(saved.rowKey, "stock-lakshya"), keys(nameTab(mine = null, saved = saved)))
        assertEquals(listOf("stock-lakshya"), keys(nameTab(mine = null)))
    }

    @Test
    fun `saved personalized ringtone is added once when the list lacks it`() {
        // Same generation, or the same song and file under a newer generation: already listed.
        assertEquals(keys(nameTab()), keys(nameTab(saved = ownNew)))
        assertEquals(keys(nameTab()), keys(nameTab(saved = ownNew.copy(generationId = "g-first"))))

        val justMade = tune("base", "Jai Shri Lakshya ji", url = "https://cdn.example/r/new.mp3", generationId = "g3")
        assertEquals(
            listOf(justMade.rowKey, ownNew.rowKey, ownOld.rowKey, "stock-lakshya"),
            keys(nameTab(saved = justMade)),
        )
    }

    @Test
    fun `search applies to own ringtones and catalog tunes`() {
        assertEquals(listOf(ownOld.rowKey), keys(nameTab(query = "priya")))
        assertEquals(listOf(ownNew.rowKey, "stock-lakshya"), keys(nameTab(query = "lakshya")))
    }

    @Test
    fun `own ringtones are listed even without a profile name`() {
        assertEquals(listOf(ownNew.rowKey, ownOld.rowKey), keys(nameTab(firstName = "")))
        assertTrue(nameTab(mine = emptyList(), firstName = "").isEmpty())
    }

    @Test
    fun `withActiveFirst keeps catalog order when the key is a tune id`() {
        assertEquals(listOf("radhe", "base", "stock-lakshya"), keys(HomeTuneFilter.withActiveFirst(catalog, "radhe")))
        assertEquals(keys(catalog), keys(HomeTuneFilter.withActiveFirst(catalog, null)))
        assertEquals(keys(catalog), keys(HomeTuneFilter.withActiveFirst(catalog, ownNew.rowKey)))
    }

    @Test
    fun `name chip empty state waits for the first own-ringtones load`() {
        val waiting = HomeUiState(
            isLoadingCategories = false,
            selectedCategoryId = Category.MY_NAME_CATEGORY_ID,
            profileFirstName = "Lakshya",
            isLoadingMyRingtones = true,
        )
        assertTrue(waiting.isAwaitingMyRingtones)
        assertFalse(waiting.showSearchEmptyState)
        assertTrue(waiting.showLoadingIndicator)

        // A refresh with a list already loaded keeps showing it (and the empty state when empty).
        val refreshing = waiting.copy(myRingtones = emptyList())
        assertFalse(refreshing.isAwaitingMyRingtones)
        assertTrue(refreshing.showSearchEmptyState)
        assertFalse(refreshing.showLoadingIndicator)

        val failed = waiting.copy(isLoadingMyRingtones = false)
        assertTrue(failed.showSearchEmptyState)
        assertFalse(waiting.copy(filteredTunes = listOf(ownNew)).showLoadingIndicator)
        assertFalse(waiting.copy(selectedCategoryId = Category.ALL_CATEGORY_ID).showLoadingIndicator)
    }

    @Test
    fun `rank is by row key`() {
        val state = HomeUiState(filteredTunes = listOf(ownNew, shyam))
        assertEquals(1, state.rankOf(ownNew.rowKey))
        assertEquals(2, state.rankOf("base"))
        assertEquals(null, state.rankOf("missing"))
    }
}

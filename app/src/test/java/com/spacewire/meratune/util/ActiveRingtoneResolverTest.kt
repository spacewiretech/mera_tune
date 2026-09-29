package com.spacewire.meratune.util

import com.spacewire.meratune.data.Tune
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveRingtoneResolverTest {

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

    /** The catalog tune "Jai Shri Shyam" (sample name Shyam) and another catalog tune. */
    private val shyam = tune("base", "Jai Shri Shyam")
    private val other = tune("other", "Radhe Radhe")
    private val catalog = listOf(shyam, other)

    /** The user's own copies of the base tune: Lakshya (newest) and Priya. */
    private val lakshya = tune("base", "Jai Shri Lakshya", url = "https://cdn.example/r/lakshya.mp3", generationId = "g-lakshya")
    private val priya = tune("base", "Jai Shri Priya", url = "https://cdn.example/r/priya.mp3", generationId = "g-priya")
    private val mine = listOf(lakshya, priya)

    private fun resolve(
        saved: SavedActiveRingtone?,
        savedIsSystemDefault: Boolean = true,
        systemTitle: String = "",
        catalog: List<Tune> = this.catalog,
        mine: List<Tune> = this.mine,
    ): String? = ActiveRingtoneResolver.resolve(saved, savedIsSystemDefault, { systemTitle }, catalog, mine)

    @Test
    fun `row key is the generation for a personalized copy and the id for a catalog tune`() {
        assertEquals("base", shyam.rowKey)
        assertNotEquals(shyam.rowKey, lakshya.rowKey)
        assertNotEquals(lakshya.rowKey, priya.rowKey)
        assertFalse(lakshya.toIntentJson().contains("rowKey"))
    }

    @Test
    fun `saved catalog tune is active while it is the default and listed`() {
        val saved = SavedActiveRingtone(tuneId = "base", personalized = false)
        assertEquals("base", resolve(saved))
        assertNull(resolve(saved, catalog = listOf(other)))
    }

    @Test
    fun `save from before the personalized flag keeps the catalog behaviour`() {
        val saved = SavedActiveRingtone(tuneId = "base", personalized = null)
        assertEquals("base", resolve(saved, mine = emptyList()))
        assertEquals("base", resolve(saved, systemTitle = "Jai Shri Shyam"))
        // Unless the default is titled like one of the user's own copies of that tune.
        assertEquals(lakshya.rowKey, resolve(saved, systemTitle = "jai shri lakshya"))
    }

    @Test
    fun `personalized save marks the user's own ringtone, never the base catalog tune`() {
        val saved = SavedActiveRingtone(tuneId = "base", personalized = true, personalizedTune = lakshya)
        assertEquals(lakshya.rowKey, resolve(saved))
        // Own ringtones not loaded (or failed): still the saved copy, which Home lists on its own.
        assertEquals(lakshya.rowKey, resolve(saved, mine = emptyList()))
        assertNotEquals("base", resolve(saved, mine = emptyList()))
    }

    @Test
    fun `personalized save matches the listed row with a newer generation of the same file`() {
        val olderGeneration = lakshya.copy(generationId = "g-lakshya-first")
        val saved = SavedActiveRingtone(tuneId = "base", personalized = true, personalizedTune = olderGeneration)
        assertEquals(lakshya.rowKey, resolve(saved))
    }

    @Test
    fun `personalized save without the recorded copy uses the system title among own copies`() {
        val saved = SavedActiveRingtone(tuneId = "base", personalized = true)
        assertEquals(priya.rowKey, resolve(saved, systemTitle = "Jai Shri Priya"))
        // The only copy of that base tune.
        assertEquals(lakshya.rowKey, resolve(saved, mine = listOf(lakshya, tune("other", "X", generationId = "g-x"))))
        // Several copies and no title match, or no own ringtones yet: active, but no row is marked.
        assertEquals(ActiveRingtoneResolver.UNLISTED_PERSONALIZED_KEY, resolve(saved))
        assertEquals(ActiveRingtoneResolver.UNLISTED_PERSONALIZED_KEY, resolve(saved, mine = emptyList()))
    }

    @Test
    fun `name ringtones set of an unclaimed row never marks the base catalog tune`() {
        // NameRingtonesActivity sets a row as listed when its claim fails: personalized, no generation id.
        val unclaimed = tune("base", "Jai Shri Lakshya", url = "https://cdn.example/r/lakshya.mp3")
        val saved = SavedActiveRingtone(tuneId = "base", personalized = true, personalizedTune = unclaimed)
        assertEquals(
            ActiveRingtoneResolver.UNLISTED_PERSONALIZED_KEY,
            resolve(saved, systemTitle = "Jai Shri Lakshya", mine = emptyList()),
        )
        // Once the user's own list has that ringtone (same base tune and file), it is that row.
        assertEquals(lakshya.rowKey, resolve(saved, systemTitle = "Jai Shri Lakshya"))
        assertEquals(lakshya.rowKey, resolve(saved))
    }

    @Test
    fun `unclaimed name ringtone never marks another own copy of the same base tune`() {
        val unclaimed = tune("base", "Jai Shri Lakshya", url = "https://cdn.example/r/lakshya.mp3")
        val saved = SavedActiveRingtone(tuneId = "base", personalized = true, personalizedTune = unclaimed)
        val ayush = tune("base", "Jai Shri Ayush", url = "https://cdn.example/r/ayush.mp3", generationId = "g-ayush")
        // The only own copy of that base tune sings another name: not the one that was set.
        assertEquals(ActiveRingtoneResolver.UNLISTED_PERSONALIZED_KEY, resolve(saved, mine = listOf(ayush)))
        assertEquals(
            ActiveRingtoneResolver.UNLISTED_PERSONALIZED_KEY,
            resolve(saved, systemTitle = "Jai Shri Ayush", mine = listOf(ayush)),
        )
        assertEquals(ActiveRingtoneResolver.UNLISTED_PERSONALIZED_KEY, resolve(saved, mine = listOf(ayush, priya)))
    }

    @Test
    fun `replaced default falls back to the system title, catalog first`() {
        val saved = SavedActiveRingtone(tuneId = "base", personalized = true, personalizedTune = lakshya)
        assertNull(resolve(saved, savedIsSystemDefault = false))
        assertEquals("other", resolve(saved, savedIsSystemDefault = false, systemTitle = "radhe radhe"))
        assertEquals(priya.rowKey, resolve(saved, savedIsSystemDefault = false, systemTitle = "Jai Shri Priya"))
        assertEquals("base", resolve(null, savedIsSystemDefault = false, systemTitle = "Jai Shri Shyam"))
        assertNull(resolve(null, savedIsSystemDefault = false, systemTitle = "Some phone tone"))
    }

    @Test
    fun `system title is read only when needed`() {
        var reads = 0
        val saved = SavedActiveRingtone(tuneId = "base", personalized = true, personalizedTune = lakshya)
        ActiveRingtoneResolver.resolve(saved, savedIsSystemDefault = true, systemTitle = { reads++; "" }, catalog, mine)
        ActiveRingtoneResolver.resolve(
            SavedActiveRingtone(tuneId = "base", personalized = false),
            savedIsSystemDefault = true,
            systemTitle = { reads++; "" },
            catalog,
            mine,
        )
        assertEquals(0, reads)
    }

    @Test
    fun `same ringtone is the same generation or the same base tune and file`() {
        assertTrue(ActiveRingtoneResolver.sameRingtone(lakshya, lakshya.copy(name = "Renamed")))
        assertTrue(ActiveRingtoneResolver.sameRingtone(lakshya, lakshya.copy(generationId = "g-new")))
        assertFalse(ActiveRingtoneResolver.sameRingtone(lakshya, priya))
        assertFalse(ActiveRingtoneResolver.sameRingtone(lakshya, lakshya.copy(id = "other", generationId = "g-new")))
    }
}

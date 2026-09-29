package com.spacewire.meratune.ui

import com.spacewire.meratune.R
import com.spacewire.meratune.analytics.CreationEntryPoint
import com.spacewire.meratune.data.Category
import com.spacewire.meratune.data.Tune
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NameRingtonesPolicyTest {

    private fun tune(
        id: String = "t1",
        name: String = "Jai Shri Ram Ram ji..",
        url: String = "https://cdn.example/generated/$id.mp3",
        gender: String = "Male",
        generationId: String? = null,
    ) = Tune(
        id = id,
        name = name,
        categoryId = "c1",
        gender = gender,
        language = "Hindi",
        tuneUrl = url,
        viewsCount = 39_000,
        category = Category("c1", "Bhakti"),
        titleTemplate = "Jai Shri Ram {name} ji..",
        sampleName = "Shyam",
        generationId = generationId,
    )

    @Test
    fun `rowsToShow keeps server order and drops blank ids, non-https URLs and repeated tune ids`() {
        val rows = NameRingtonesPolicy.rowsToShow(
            listOf(
                tune("t2"),
                tune(" "),
                tune("t3", url = "http://cdn.example/t3.mp3"),
                tune("t4", url = "  "),
                tune("t1"),
                tune("t2", name = "second copy"),
            ),
        )
        assertEquals(listOf("t2", "t1"), rows.map { it.id })
        assertEquals("Jai Shri Ram Ram ji..", rows.first().name)
    }

    @Test
    fun `rowsToShow of nothing is empty, so the form goes to the song picker`() {
        assertTrue(NameRingtonesPolicy.rowsToShow(emptyList()).isEmpty())
        assertTrue(NameRingtonesPolicy.rowsToShow(listOf(tune(url = "ftp://x"))).isEmpty())
    }

    @Test
    fun `only rows without the caller's generation id need a claim`() {
        assertTrue(NameRingtonesPolicy.needsClaim(tune(generationId = null)))
        assertTrue(NameRingtonesPolicy.needsClaim(tune(generationId = " ")))
        assertFalse(NameRingtonesPolicy.needsClaim(tune(generationId = "g1")))
    }

    @Test
    fun `replaceRow swaps the claimed row in place and leaves the others`() {
        val rows = listOf(tune("t1"), tune("t2"), tune("t3"))
        val claimed = tune("t2", generationId = "g2")
        val updated = NameRingtonesPolicy.replaceRow(rows, claimed)
        assertEquals(listOf("t1", "t2", "t3"), updated.map { it.id })
        assertEquals("g2", updated[1].generationId)
        assertNull(updated[0].generationId)
        assertNull(updated[2].generationId)
    }

    @Test
    fun `voice badge follows voiceKey and hides for an unknown gender`() {
        assertEquals(R.string.create_form_voice_male, NameRingtonesPolicy.voiceLabelRes(tune(gender = "Male").voiceKey))
        assertEquals(R.string.create_form_voice_female, NameRingtonesPolicy.voiceLabelRes(tune(gender = " FEMALE").voiceKey))
        assertNull(NameRingtonesPolicy.voiceLabelRes(tune(gender = "duet").voiceKey))
        assertNull(NameRingtonesPolicy.voiceLabelRes(""))
    }

    @Test
    fun `Home's name section CTA skips the lookup while its name is kept`() {
        val chip = CreationEntryPoint.MY_NAME_CHIP
        assertTrue(NameRingtonesPolicy.skipsLookup(chip, "Ram", "Ram"))
        assertTrue(NameRingtonesPolicy.skipsLookup(chip, "ram ", "Ram"))
        // Another name typed on the form: Home never listed its ringtones.
        assertFalse(NameRingtonesPolicy.skipsLookup(chip, "Rahul", "Ram"))
    }

    @Test
    fun `every other entry point looks up the name`() {
        listOf(
            CreationEntryPoint.POST_PURCHASE,
            CreationEntryPoint.SEARCH_BAR,
            CreationEntryPoint.PROCESSING,
            null,
        ).forEach { entry ->
            assertFalse(entry.toString(), NameRingtonesPolicy.skipsLookup(entry, "Ram", "Ram"))
        }
    }

    @Test
    fun `encode and decode round-trip the rows and drop malformed entries`() {
        val rows = listOf(tune("t1", generationId = "g1"), tune("t2", gender = "Female"))
        val encoded = NameRingtonesPolicy.encode(rows)
        assertEquals(rows, NameRingtonesPolicy.decode(encoded))
        assertEquals(rows, NameRingtonesPolicy.decode(listOf("{not json", "") + encoded))
        assertTrue(NameRingtonesPolicy.decode(null).isEmpty())
    }
}

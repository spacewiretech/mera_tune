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
        personalizable: Boolean = true,
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
        isPersonalizable = personalizable,
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
    fun `only personalized rows without the caller's generation id need a claim`() {
        assertTrue(NameRingtonesPolicy.needsClaim(tune(generationId = null)))
        assertTrue(NameRingtonesPolicy.needsClaim(tune(generationId = " ")))
        assertFalse(NameRingtonesPolicy.needsClaim(tune(generationId = "g1")))
        // A stock catalog tune is set as it is: no generate-ringtone call.
        assertFalse(NameRingtonesPolicy.needsClaim(tune(generationId = null, personalizable = false)))
    }

    @Test
    fun `stock catalog tunes are not personalized, the rest are`() {
        assertFalse(NameRingtonesPolicy.isPersonalized(tune(personalizable = false)))
        assertTrue(NameRingtonesPolicy.isPersonalized(tune()))
        assertTrue(NameRingtonesPolicy.isPersonalized(tune(generationId = "g1", personalizable = false)))
    }

    @Test
    fun `catalog name tunes are stock tunes whose title has the name as a word`() {
        val catalog = listOf(
            tune("jai", name = "Jai Shri Ram Rahul ji", personalizable = false),
            tune("shree", name = "Jai Shree Ram", personalizable = false),
            tune("ramram", name = "Ram-Ram", personalizable = false),
            tune("ramesh", name = "Ramesh", personalizable = false),
            tune("param", name = "Param", personalizable = false),
            tune("balaram", name = "Balaram", personalizable = false),
            // Personalizable: its stock recording sings the sample name, the server lists the right one.
            tune("template", name = "Ram ki dhun", personalizable = true),
        )
        assertEquals(listOf("jai", "shree", "ramram"), NameRingtonesPolicy.catalogNameTunes(catalog, "Ram").map { it.id })
        assertEquals(listOf("jai", "shree", "ramram"), NameRingtonesPolicy.catalogNameTunes(catalog, " ram ").map { it.id })
        assertEquals(listOf("ramesh"), NameRingtonesPolicy.catalogNameTunes(catalog, "RAMESH").map { it.id })
        assertTrue(NameRingtonesPolicy.catalogNameTunes(catalog, "Ayushss").isEmpty())
        assertTrue(NameRingtonesPolicy.catalogNameTunes(catalog, " ").isEmpty())
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
    fun `a form launched from Home skips the step`() {
        assertTrue(NameRingtonesPolicy.skipsLookup(CreationEntryPoint.MY_NAME_CHIP))
        assertTrue(NameRingtonesPolicy.skipsLookup(CreationEntryPoint.SEARCH_BAR))
    }

    @Test
    fun `the trial user's form after the member screen looks up the name`() {
        listOf(CreationEntryPoint.POST_PURCHASE, CreationEntryPoint.PROCESSING, null).forEach { entry ->
            assertFalse(entry.toString(), NameRingtonesPolicy.skipsLookup(entry))
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

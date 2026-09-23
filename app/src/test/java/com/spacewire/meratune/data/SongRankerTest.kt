package com.spacewire.meratune.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongRankerTest {

    private val devotional = Category(id = "c-dev", name = "Devotional")
    private val romantic = Category(id = "c-rom", name = "Romantic")
    private val family = Category(id = "c-fam", name = "Family")

    private fun tune(
        id: String,
        name: String = "Song $id",
        language: String = "Hindi",
        gender: String = "Female",
        featuredRank: Int? = null,
        category: Category? = devotional,
    ) = Tune(
        id = id,
        name = name,
        categoryId = category?.id ?: "none",
        gender = gender,
        language = language,
        tuneUrl = "https://example.com/$id.mp3",
        featuredRank = featuredRank,
        category = category,
        isPersonalizable = true,
    )

    private fun ids(result: RankedSongs) = result.songs.map { it.tune.id }

    @Test
    fun exactLanguageTierIsUsedWithNoFallback() {
        val all = listOf(
            tune("h1", language = "Hindi"),
            tune("t1", language = "Telugu"),
            tune("t2", language = "Telugu"),
        )
        val result = SongRanker.rank(all, "Telugu")
        assertEquals(listOf("t1", "t2"), ids(result))
        assertEquals(FallbackLevel.NONE, result.fallbackLevel)
        assertEquals("Telugu", result.effectiveLanguage)
    }

    @Test
    fun languageMatchIgnoresCaseAndSurroundingSpaces() {
        val all = listOf(tune("h1", language = " hindi "), tune("e1", language = "English"))
        val result = SongRanker.rank(all, "HINDI ")
        assertEquals(listOf("h1"), ids(result))
        assertEquals(FallbackLevel.NONE, result.fallbackLevel)
        assertEquals("HINDI", result.effectiveLanguage)
    }

    @Test
    fun fallsBackToHindiWhenLanguageHasNoSongs() {
        val all = listOf(
            tune("h1", language = "Hindi"),
            tune("h2", language = "hindi"),
            tune("e1", language = "English"),
        )
        val result = SongRanker.rank(all, "Odia")
        assertEquals(setOf("h1", "h2"), ids(result).toSet())
        assertEquals(FallbackLevel.HINDI, result.fallbackLevel)
        assertEquals("Hindi", result.effectiveLanguage)
    }

    @Test
    fun fallsBackToAnySongWhenNeitherLanguageNorHindiExist() {
        val all = listOf(tune("e1", language = "English"), tune("t1", language = "Tamil"))
        val result = SongRanker.rank(all, "Odia")
        assertEquals(setOf("e1", "t1"), ids(result).toSet())
        assertEquals(FallbackLevel.ANY, result.fallbackLevel)
        assertEquals("Odia", result.effectiveLanguage)
    }

    @Test
    fun emptyCatalogGivesEmptyResult() {
        val result = SongRanker.rank(emptyList(), "Hindi")
        assertTrue(result.songs.isEmpty())
        assertTrue(result.categories.isEmpty())
        assertEquals(FallbackLevel.ANY, result.fallbackLevel)
    }

    @Test
    fun ordersByFeaturedRankThenNameWithNullsLast() {
        val all = listOf(
            tune("n-b", name = "Bhajan", featuredRank = null),
            tune("r3", name = "Zeta", featuredRank = 3),
            tune("n-a", name = "aarti", featuredRank = null),
            tune("r1", name = "Yankee", featuredRank = 1),
            tune("r2", name = "Xray", featuredRank = 2),
        )
        val result = SongRanker.rank(all, "Hindi")
        assertEquals(listOf("r1", "r2", "r3", "n-a", "n-b"), ids(result))
        assertEquals(listOf(1, 2, 3, 4, 5), result.songs.map { it.rank })
    }

    @Test
    fun nameTieBreakIsCaseInsensitive() {
        val all = listOf(
            tune("b", name = "banjo"),
            tune("a", name = "Aarti"),
            tune("c", name = "Chalisa"),
        )
        assertEquals(listOf("a", "b", "c"), ids(SongRanker.rank(all, "Hindi")))
    }

    @Test
    fun rankIsPositionInUnfilteredTierEvenWhenVoiceFiltered() {
        val all = listOf(
            tune("m1", gender = "Male", featuredRank = 1),
            tune("f1", gender = "Female", featuredRank = 2),
            tune("m2", gender = "Male", featuredRank = 3),
            tune("f2", gender = "female", featuredRank = 4),
        )
        val result = SongRanker.rank(all, "Hindi", VoiceFilter.FEMALE)
        assertEquals(listOf("f1", "f2"), ids(result))
        assertEquals(listOf(2, 4), result.songs.map { it.rank })
    }

    @Test
    fun maleFilterDropsFemaleAndUnknownVoices() {
        val all = listOf(
            tune("m1", gender = "MALE"),
            tune("f1", gender = "Female"),
            tune("u1", gender = ""),
            tune("m2", gender = "male voice"),
        )
        val result = SongRanker.rank(all, "Hindi", VoiceFilter.MALE)
        assertEquals(setOf("m1", "m2"), ids(result).toSet())
    }

    @Test
    fun allFilterKeepsEveryVoice() {
        val all = listOf(tune("m1", gender = "Male"), tune("f1", gender = "Female"), tune("u1", gender = "?"))
        assertEquals(3, SongRanker.rank(all, "Hindi", VoiceFilter.ALL).songs.size)
    }

    @Test
    fun categoriesAreDistinctInOrderOfFirstAppearanceAndSkipNulls() {
        val all = listOf(
            tune("1", featuredRank = 1, category = romantic),
            tune("2", featuredRank = 2, category = null),
            tune("3", featuredRank = 3, category = devotional),
            tune("4", featuredRank = 4, category = romantic),
            tune("5", featuredRank = 5, category = family),
        )
        val result = SongRanker.rank(all, "Hindi")
        assertEquals(listOf("c-rom", "c-dev", "c-fam"), result.categories.map { it.id })
    }

    @Test
    fun categoriesOnlyReflectReturnedSongs() {
        val all = listOf(
            tune("m1", gender = "Male", category = romantic, featuredRank = 1),
            tune("f1", gender = "Female", category = devotional, featuredRank = 2),
        )
        val result = SongRanker.rank(all, "Hindi", VoiceFilter.FEMALE)
        assertEquals(listOf("c-dev"), result.categories.map { it.id })
    }

    @Test
    fun defaultLimitIsTen() {
        val all = (1..12).map { tune("s$it", featuredRank = it) }
        val result = SongRanker.rank(all, "Hindi")
        assertEquals(10, result.songs.size)
        assertEquals((1..10).map { "s$it" }, ids(result))
    }

    @Test
    fun explicitLimitIsHonoured() {
        val all = (1..12).map { tune("s$it", featuredRank = it) }
        assertEquals(listOf("s1", "s2", "s3"), ids(SongRanker.rank(all, "Hindi", limit = 3)))
        assertTrue(SongRanker.rank(all, "Hindi", limit = 0).songs.isEmpty())
    }

    @Test
    fun limitAppliesAfterVoiceFilter() {
        val all = (1..12).map { tune("s$it", gender = if (it % 2 == 0) "Female" else "Male", featuredRank = it) }
        val result = SongRanker.rank(all, "Hindi", VoiceFilter.FEMALE)
        assertEquals(6, result.songs.size)
        assertEquals(listOf(2, 4, 6, 8, 10, 12), result.songs.map { it.rank })
    }

    @Test
    fun limitDoesNotChangeFallbackDecision() {
        val all = (1..15).map { tune("h$it", language = "Hindi", featuredRank = it) }
        val result = SongRanker.rank(all, "Bengali")
        assertEquals(FallbackLevel.HINDI, result.fallbackLevel)
        assertEquals(10, result.songs.size)
    }

    @Test
    fun analyticsValues() {
        assertEquals("none", FallbackLevel.NONE.analyticsValue)
        assertEquals("hindi", FallbackLevel.HINDI.analyticsValue)
        assertEquals("any", FallbackLevel.ANY.analyticsValue)
        assertNull(VoiceFilter.ALL.analyticsValue)
        assertEquals("male", VoiceFilter.MALE.analyticsValue)
        assertEquals("female", VoiceFilter.FEMALE.analyticsValue)
    }

    @Test
    fun tuneVoiceKey() {
        assertEquals("male", tune("a", gender = "Male").voiceKey)
        assertEquals("female", tune("a", gender = " FEMALE ").voiceKey)
        assertEquals("female", tune("a", gender = "Female voice").voiceKey)
        assertEquals("", tune("a", gender = "").voiceKey)
        assertEquals("", tune("a", gender = "Duet").voiceKey)
    }

    @Test
    fun tuneIntentJsonRoundTrip() {
        val original = tune("x1", gender = "Male", featuredRank = 4, category = family).copy(
            sampleName = "Ram",
            titleTemplate = "Jai Shri Ram {name} ji..",
            assetsVersion = 2,
            generationId = "gen-123",
        )
        val restored = Tune.fromIntentJson(original.toIntentJson())
        assertEquals(original, restored)
        assertNull(Tune.fromIntentJson(null))
        assertNull(Tune.fromIntentJson(""))
        assertNull(Tune.fromIntentJson("not json"))
    }
}

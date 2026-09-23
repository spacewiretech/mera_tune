package com.spacewire.meratune.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RingtoneFileNameTest {

    private val safeName = Regex("^[A-Za-z0-9._-]+$")

    @Test
    fun nonAsciiTitleFallsBackToMeratuneWithSuffix() {
        assertEquals(
            "meratune_01234567.mp3",
            RingtoneHelper.fileNameFor("जय श्री राम", "0123456789abcdef", "mp3"),
        )
    }

    @Test
    fun differentUnicodeTitlesDoNotCollideThanksToSuffix() {
        val a = RingtoneHelper.fileNameFor("जय श्री राम", "aaaaaaaa-1111", "mp3")
        val b = RingtoneHelper.fileNameFor("प्यार का पैगाम", "bbbbbbbb-2222", "mp3")
        assertTrue(a != b)
    }

    @Test
    fun asciiTitleIsSanitized() {
        assertEquals(
            "Jai_Shri_Ram_Ayush_ji_abcd1234.mp3",
            RingtoneHelper.fileNameFor("Jai Shri Ram Ayush ji..", "abcd1234-5678", "mp3"),
        )
    }

    @Test
    fun mixedTitleKeepsAsciiPart() {
        assertEquals(
            "Ayush_Ringtone_gen12345.mp3",
            RingtoneHelper.fileNameFor("Ayush की Ringtone", "gen12345", "mp3"),
        )
    }

    @Test
    fun suffixIsTruncatedToEightCharacters() {
        assertEquals(
            "Ayush_3f2504e0.mp3",
            RingtoneHelper.fileNameFor("Ayush", "3f2504e0-4f89-11d3-9a0c-0305e82c3301", "mp3"),
        )
        assertEquals("Ayush_ab.mp3", RingtoneHelper.fileNameFor("Ayush", "ab", "mp3"))
    }

    @Test
    fun blankSuffixOmitsSeparator() {
        assertEquals("Ayush.mp3", RingtoneHelper.fileNameFor("Ayush", "", "mp3"))
        assertEquals("Ayush.mp3", RingtoneHelper.fileNameFor("Ayush", "---", "mp3"))
    }

    @Test
    fun extensionIsNormalised() {
        assertEquals("Ayush_abc.mp3", RingtoneHelper.fileNameFor("Ayush", "abc", "MP3"))
        assertEquals("Ayush_abc.wav", RingtoneHelper.fileNameFor("Ayush", "abc", ".wav"))
        assertEquals("Ayush_abc.mp3", RingtoneHelper.fileNameFor("Ayush", "abc", ""))
    }

    @Test
    fun longTitleIsTruncated() {
        val title = "A".repeat(100)
        val name = RingtoneHelper.fileNameFor(title, "12345678", "mp3")
        assertEquals("A".repeat(48) + "_12345678.mp3", name)
    }

    @Test
    fun resultOnlyContainsSafeCharacters() {
        val titles = listOf("Ayush/../etc", "  spaced   out  ", "emoji 😀 title", "..hidden", "___", "!!!")
        for (title in titles) {
            val name = RingtoneHelper.fileNameFor(title, "3f2504e0", "mp3")
            assertTrue("unsafe file name <$name> for <$title>", safeName.matches(name))
            assertTrue("must not start with a dot: <$name>", !name.startsWith("."))
        }
    }

    @Test
    fun punctuationOnlyTitleFallsBackToMeratune() {
        assertEquals("meratune_3f2504e0.mp3", RingtoneHelper.fileNameFor("...", "3f2504e0", "mp3"))
        assertEquals("meratune_3f2504e0.mp3", RingtoneHelper.fileNameFor("", "3f2504e0", "mp3"))
    }
}

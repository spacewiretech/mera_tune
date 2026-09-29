package com.spacewire.meratune.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameMatchTest {

    @Test
    fun `the name as a whole word, any case and spacing`() {
        assertTrue(NameMatch.titleHasName("Jai Shri Ram Rahul ji", "Ram"))
        assertTrue(NameMatch.titleHasName("Jai Shree RAM", "ram"))
        assertTrue(NameMatch.titleHasName("Ram-Ram", "Ram"))
        assertTrue(NameMatch.titleHasName("Ram's tune", " Ram "))
        assertTrue(NameMatch.titleHasName("Ram", "ram"))
        assertTrue(NameMatch.titleHasName("Happy  Birthday   Rahul Kumar!", "rahul  kumar"))
    }

    @Test
    fun `not inside another name`() {
        listOf("Ramesh", "Param", "Balaram", "Kavalaram", "Vidyaram", "Rameshwar", "Ram2").forEach { title ->
            assertFalse(title, NameMatch.titleHasName(title, "Ram"))
        }
    }

    @Test
    fun `a later whole-word occurrence still counts`() {
        assertTrue(NameMatch.titleHasName("Ramesh and Ram", "Ram"))
    }

    @Test
    fun `Indic scripts keep their vowel signs inside the word`() {
        assertTrue(NameMatch.titleHasName("जय श्री राम", "राम"))
        assertFalse(NameMatch.titleHasName("रामेश", "राम"))
        assertFalse(NameMatch.titleHasName("रामु", "राम"))
    }

    @Test
    fun `composed and decomposed forms match`() {
        val decomposed = "José"
        assertTrue(NameMatch.titleHasName("Happy birthday José", decomposed))
    }

    @Test
    fun `a blank name matches nothing`() {
        assertFalse(NameMatch.titleHasName("Ram", ""))
        assertFalse(NameMatch.titleHasName("Ram", "   "))
    }
}

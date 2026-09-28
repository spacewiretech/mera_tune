package com.spacewire.meratune.ui

import com.spacewire.meratune.data.Category
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSearchScopeTest {

    @Test
    fun `starting a search on a category chip moves to All Tunes`() {
        assertTrue(HomeTuneFilter.searchMovesToAllTunes("", "R", "romantic-id"))
        assertTrue(HomeTuneFilter.searchMovesToAllTunes("   ", "Ram", "romantic-id"))
    }

    @Test
    fun `starting a search on the name chip moves to All Tunes`() {
        assertTrue(HomeTuneFilter.searchMovesToAllTunes("", "R", Category.MY_NAME_CATEGORY_ID))
    }

    @Test
    fun `a search on All Tunes stays there`() {
        assertFalse(HomeTuneFilter.searchMovesToAllTunes("", "R", Category.ALL_CATEGORY_ID))
        assertFalse(HomeTuneFilter.searchMovesToAllTunes("", "R", null))
    }

    @Test
    fun `a chip picked while searching keeps narrowing the results`() {
        // The query was already typed when the chip was picked: typing on does not move again.
        assertFalse(HomeTuneFilter.searchMovesToAllTunes("R", "Ra", "romantic-id"))
        assertFalse(HomeTuneFilter.searchMovesToAllTunes("Ram", "", "romantic-id"))
    }

    @Test
    fun `whitespace alone is not a search`() {
        assertFalse(HomeTuneFilter.searchMovesToAllTunes("", "  ", "romantic-id"))
    }
}

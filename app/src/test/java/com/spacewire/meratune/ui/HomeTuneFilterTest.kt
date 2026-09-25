package com.spacewire.meratune.ui

import com.spacewire.meratune.R
import com.spacewire.meratune.data.Category
import com.spacewire.meratune.data.Tune
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTuneFilterTest {

    private fun tune(id: String, name: String) = Tune(
        id = id,
        name = name,
        categoryId = "c-$id",
        gender = "Male",
        language = "Hindi",
        tuneUrl = "https://example.com/$id.mp3",
    )

    private val ramBhajan = tune("1", "Ram Bhajan")
    private val shriRam = tune("2", "Jai Shri RAM")
    private val priya = tune("3", "Priya Love Song")
    private val ramPriya = tune("4", "Ram and Priya Duet")
    private val tunes = listOf(ramBhajan, shriRam, priya, ramPriya)

    private fun ids(result: List<Tune>) = result.map { it.id }

    @Test
    fun `firstName takes the first word and trims`() {
        assertEquals("Ram", HomeTuneFilter.firstName("  Ram Kumar Sharma "))
        assertEquals("Priya", HomeTuneFilter.firstName("Priya"))
        assertEquals("", HomeTuneFilter.firstName("   "))
        assertEquals("", HomeTuneFilter.firstName(null))
    }

    @Test
    fun `no name filter and no query keeps every tune`() {
        assertEquals(tunes, HomeTuneFilter.filter(tunes, query = "", nameFilter = null))
        assertEquals(tunes, HomeTuneFilter.filter(tunes, query = "   ", nameFilter = null))
    }

    @Test
    fun `name chip matches the title case-insensitively like search`() {
        assertEquals(listOf("1", "2", "4"), ids(HomeTuneFilter.filter(tunes, query = "", nameFilter = "ram")))
        assertEquals(
            ids(HomeTuneFilter.filter(tunes, query = "Ram", nameFilter = null)),
            ids(HomeTuneFilter.filter(tunes, query = "", nameFilter = "Ram")),
        )
    }

    @Test
    fun `name chip and search apply together`() {
        assertEquals(listOf("4"), ids(HomeTuneFilter.filter(tunes, query = "priya", nameFilter = "Ram")))
        assertEquals(listOf("2"), ids(HomeTuneFilter.filter(tunes, query = "shri", nameFilter = "Ram")))
        assertTrue(HomeTuneFilter.filter(tunes, query = "zzz", nameFilter = "Ram").isEmpty())
    }

    @Test
    fun `no matching tune or a blank name gives an empty list`() {
        assertTrue(HomeTuneFilter.filter(tunes, query = "", nameFilter = "Ayush").isEmpty())
        assertTrue(HomeTuneFilter.filter(tunes, query = "", nameFilter = "").isEmpty())
        assertTrue(HomeTuneFilter.filter(tunes, query = "Ram", nameFilter = "  ").isEmpty())
    }

    @Test
    fun `empty-state name prefers the search query, then the chip name`() {
        assertEquals("Ravi", HomeTuneFilter.emptyStateName(" Ravi ", myNameSelected = false, firstName = "Ram"))
        assertEquals("Ravi", HomeTuneFilter.emptyStateName("Ravi", myNameSelected = true, firstName = "Ram"))
        assertEquals("Ram", HomeTuneFilter.emptyStateName("  ", myNameSelected = true, firstName = "Ram"))
        assertEquals("", HomeTuneFilter.emptyStateName("", myNameSelected = true, firstName = ""))
        assertEquals("", HomeTuneFilter.emptyStateName("", myNameSelected = false, firstName = "Ram"))
    }

    @Test
    fun `ui state shows the empty state for the name chip without a query`() {
        val chip = HomeUiState(
            categories = emptyList(),
            isLoadingCategories = false,
            selectedCategoryId = Category.MY_NAME_CATEGORY_ID,
            profileFirstName = "Ram",
        )
        assertTrue(chip.isMyNameSelected)
        assertTrue(chip.showSearchEmptyState)
        assertEquals("Ram", chip.emptyStateName)

        val all = chip.copy(selectedCategoryId = Category.ALL_CATEGORY_ID)
        assertFalse(all.showSearchEmptyState)
        assertFalse(chip.copy(filteredTunes = listOf(ramBhajan)).showSearchEmptyState)
        assertFalse(chip.copy(isLoadingTunes = true).showSearchEmptyState)
        assertFalse(chip.copy(errorMessage = "offline").showSearchEmptyState)
    }

    @Test
    fun `selected name chip reports my_name, never the user's name`() {
        val state = HomeUiState(
            categories = listOf(
                Category(Category.ALL_CATEGORY_ID, "All"),
                Category(Category.MY_NAME_CATEGORY_ID, Category.MY_NAME_CATEGORY_NAME),
            ),
            selectedCategoryId = Category.MY_NAME_CATEGORY_ID,
            profileFirstName = "Ram",
        )
        assertEquals("my_name", state.selectedCategoryName)
    }

    @Test
    fun `name chip uses its bundled art and label kind`() {
        val chip = Category(Category.MY_NAME_CATEGORY_ID, Category.MY_NAME_CATEGORY_NAME)
        assertEquals(CategoryArt.LabelKind.MY_NAME, CategoryArt.labelKind(chip))
        val style = CategoryUiHelper.styleFor(chip)
        assertEquals(CategoryUiHelper.Kind.ILLUSTRATION, style.kind)
        assertEquals(R.drawable.ic_category_my_name, style.iconRes)
        assertEquals(R.drawable.bg_category_art_circle, style.circleBg)
    }
}

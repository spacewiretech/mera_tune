package com.spacewire.meratune.ui

import com.spacewire.meratune.R
import com.spacewire.meratune.data.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryUiHelperTest {

    @Test
    fun `slugFor lower-cases, trims and collapses non-alphanumerics`() {
        assertEquals("name", CategoryArt.slugFor("Name"))
        assertEquals("love_romance", CategoryArt.slugFor("  Love & Romance "))
        assertEquals("bollywood", CategoryArt.slugFor("Bollywood"))
    }

    @Test
    fun `labelKind is keyed by id and DB name, not display label`() {
        assertEquals(CategoryArt.LabelKind.ALL_TUNES, CategoryArt.labelKind(Category(Category.ALL_CATEGORY_ID, "All")))
        assertEquals(CategoryArt.LabelKind.YOUR_NAME, CategoryArt.labelKind(Category("c1", "Name")))
        assertEquals(CategoryArt.LabelKind.YOUR_NAME, CategoryArt.labelKind(Category("c1", "name ")))
        assertEquals(CategoryArt.LabelKind.DB_NAME, CategoryArt.labelKind(Category("c2", "Romantic")))
        assertEquals(CategoryArt.LabelKind.DB_NAME, CategoryArt.labelKind(Category("c3", "All")))
    }

    @Test
    fun `illustrationFor uses the slug and returns null when not bundled`() {
        val catalog = mapOf("love_romance" to 42)
        assertEquals(42, CategoryArt.illustrationFor(" Love & Romance", catalog))
        assertNull(CategoryArt.illustrationFor("Family", catalog))
    }

    @Test
    fun `All category by id is the brand mark`() {
        val style = CategoryUiHelper.styleFor(Category(Category.ALL_CATEGORY_ID, "All"))
        assertEquals(CategoryUiHelper.Kind.BRAND_MARK, style.kind)
        assertEquals(R.drawable.logo, style.iconRes)
        assertEquals(R.drawable.bg_category_art_circle, style.circleBg)
    }

    @Test
    fun `null category falls back to the default glyph`() {
        assertEquals(CategoryUiHelper.styleFor(""), CategoryUiHelper.styleFor(null as Category?))
        assertEquals(CategoryUiHelper.Kind.GLYPH, CategoryUiHelper.styleFor(null as Category?).kind)
    }

    @Test
    fun `unbundled DB category keeps its flat glyph`() {
        val style = CategoryUiHelper.styleFor(Category("x", "Romantic"))
        assertEquals(CategoryUiHelper.Kind.GLYPH, style.kind)
        assertEquals(R.drawable.ic_category_romantic, style.iconRes)
        assertEquals(CategoryUiHelper.styleFor("Romantic"), style)
    }

    @Test
    fun `name-keyed styleFor keeps the pre-refresh mapping`() {
        assertEquals(R.drawable.ic_category_all, CategoryUiHelper.styleFor("All").iconRes)
        assertEquals(R.drawable.ic_category_devotional, CategoryUiHelper.styleFor("Bhakti").iconRes)
    }
}

package com.spacewire.meratune.ui

import com.spacewire.meratune.data.Category
import java.util.Locale

/**
 * Pure mapping from DB categories to bundled art and display-label kinds (plan P3).
 * Keyed by category id and lower-cased DB name, never by the localized display label.
 */
object CategoryArt {

    /**
     * Bundled full-bleed illustrations by slug. Starts empty: each designer asset adds one line,
     * e.g. `"devotional" to R.drawable.art_category_devotional`
     * (asset convention: res/drawable-xxhdpi/art_category_<slug>.webp, 180x180px = 60dp at 3x).
     */
    internal val BUNDLED: Map<String, Int> = emptyMap()

    enum class LabelKind { ALL_TUNES, YOUR_NAME, DB_NAME }

    private val NON_SLUG = Regex("[^a-z0-9]+")

    fun slugFor(name: String): String =
        name.trim().lowercase(Locale.ROOT).replace(NON_SLUG, "_").trim('_')

    fun illustrationFor(name: String, catalog: Map<String, Int> = BUNDLED): Int? = catalog[slugFor(name)]

    fun labelKind(category: Category): LabelKind = when {
        category.id == Category.ALL_CATEGORY_ID -> LabelKind.ALL_TUNES
        slugFor(category.name) == YOUR_NAME_SLUG -> LabelKind.YOUR_NAME
        else -> LabelKind.DB_NAME
    }

    private const val YOUR_NAME_SLUG = "name"
}

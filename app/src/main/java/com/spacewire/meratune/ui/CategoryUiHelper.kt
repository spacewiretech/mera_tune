package com.spacewire.meratune.ui

import android.content.Context
import android.view.View
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import coil.dispose
import coil.load
import com.spacewire.meratune.R
import com.spacewire.meratune.data.Category

object CategoryUiHelper {
    enum class Kind { GLYPH, ILLUSTRATION, BRAND_MARK }

    data class Style(
        @param:DrawableRes val circleBg: Int,
        @param:DrawableRes val iconRes: Int,
        val kind: Kind = Kind.GLYPH,
    )

    /** Name-keyed flat icon fallback (pre-refresh look). */
    fun styleFor(categoryName: String): Style {
        return when (categoryName.lowercase()) {
            "all" -> Style(R.drawable.bg_category_all, R.drawable.ic_category_all)
            "romantic" -> Style(R.drawable.bg_category_romantic, R.drawable.ic_category_romantic)
            "family" -> Style(R.drawable.bg_category_family, R.drawable.ic_category_family)
            "cinematic" -> Style(R.drawable.bg_category_cinematic, R.drawable.ic_category_cinematic)
            else -> Style(R.drawable.bg_category_devotional, R.drawable.ic_category_devotional)
        }
    }

    /**
     * Full-bleed art style: All (by id) is the brand mark, a bundled illustration wins next,
     * otherwise the flat glyph fallback. Never keyed by the display label.
     */
    fun styleFor(category: Category?): Style {
        if (category == null) return styleFor("")
        if (category.id == Category.ALL_CATEGORY_ID) {
            return Style(R.drawable.bg_category_art_circle, R.drawable.logo, Kind.BRAND_MARK)
        }
        val art = CategoryArt.illustrationFor(category.name)
        if (art != null) return Style(R.drawable.bg_category_art_circle, art, Kind.ILLUSTRATION)
        return styleFor(category.name)
    }

    /** Localized chip/row label. Analytics keep [Category.name]. */
    fun displayName(context: Context, category: Category): String = when (CategoryArt.labelKind(category)) {
        CategoryArt.LabelKind.ALL_TUNES -> context.getString(R.string.category_all_tunes)
        CategoryArt.LabelKind.YOUR_NAME -> context.getString(R.string.category_your_name)
        CategoryArt.LabelKind.DB_NAME -> category.name
    }

    /** Pre-refresh binding (flat circle + icon). Kept for existing callers. */
    fun bindIcon(container: View, icon: ImageView, category: Category) {
        val fallback = styleFor(category.name)
        if (category.imageUrl.isNotBlank()) {
            container.background = ContextCompat.getDrawable(container.context, fallback.circleBg)
            icon.load(category.imageUrl) {
                crossfade(true)
                placeholder(fallback.iconRes)
                error(fallback.iconRes)
            }
        } else {
            container.background = ContextCompat.getDrawable(container.context, fallback.circleBg)
            icon.setImageResource(fallback.iconRes)
        }
    }

    /**
     * Full-bleed art binding for the refreshed rows/chips. The container is clipped to its
     * circular background; a non-blank [Category.imageUrl] (Coil) is still preferred over the
     * flat glyph, and falls back to it on error.
     */
    fun bindArt(container: View, icon: ImageView, category: Category?) {
        icon.dispose()
        val style = styleFor(category)
        container.background = ContextCompat.getDrawable(container.context, style.circleBg)
        container.clipToOutline = true
        val url = category?.imageUrl.orEmpty()
        when {
            style.kind == Kind.ILLUSTRATION -> {
                icon.scaleType = ImageView.ScaleType.CENTER_CROP
                icon.setPadding(0, 0, 0, 0)
                icon.setImageResource(style.iconRes)
            }
            style.kind == Kind.BRAND_MARK -> {
                val pad = (BRAND_MARK_PADDING_DP * icon.resources.displayMetrics.density).toInt()
                icon.scaleType = ImageView.ScaleType.FIT_CENTER
                icon.setPadding(pad, pad, pad, pad)
                icon.setImageResource(style.iconRes)
            }
            url.isNotBlank() -> {
                icon.scaleType = ImageView.ScaleType.CENTER_CROP
                icon.setPadding(0, 0, 0, 0)
                icon.setImageDrawable(null)
                icon.load(url) {
                    crossfade(true)
                    listener(onError = { _, _ -> applyGlyph(icon, style) })
                }
            }
            else -> applyGlyph(icon, style)
        }
    }

    private fun applyGlyph(icon: ImageView, style: Style) {
        icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        icon.setPadding(0, 0, 0, 0)
        icon.setImageResource(style.iconRes)
    }

    private const val BRAND_MARK_PADDING_DP = 9f
}

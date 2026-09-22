package com.spacewire.meratune.ui

import android.view.View
import android.widget.ImageView
import androidx.core.content.ContextCompat
import coil.load
import com.spacewire.meratune.data.Category

object CategoryUiHelper {
    data class Style(val circleBg: Int, val iconRes: Int)

    fun styleFor(categoryName: String): Style {
        return when (categoryName.lowercase()) {
            "all" -> Style(com.spacewire.meratune.R.drawable.bg_category_all, com.spacewire.meratune.R.drawable.ic_category_all)
            "romantic" -> Style(com.spacewire.meratune.R.drawable.bg_category_romantic, com.spacewire.meratune.R.drawable.ic_category_romantic)
            "family" -> Style(com.spacewire.meratune.R.drawable.bg_category_family, com.spacewire.meratune.R.drawable.ic_category_family)
            "cinematic" -> Style(com.spacewire.meratune.R.drawable.bg_category_cinematic, com.spacewire.meratune.R.drawable.ic_category_cinematic)
            else -> Style(com.spacewire.meratune.R.drawable.bg_category_devotional, com.spacewire.meratune.R.drawable.ic_category_devotional)
        }
    }

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
}

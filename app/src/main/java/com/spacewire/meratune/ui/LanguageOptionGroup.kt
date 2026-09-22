package com.spacewire.meratune.ui

import android.view.View
import com.spacewire.meratune.R

class LanguageOptionGroup(
    private val optionViews: List<View>,
    initialSelectedIndex: Int = 0,
    private val onSelectionChanged: ((Int) -> Unit)? = null,
) {
    private var selectedIndex = initialSelectedIndex

    init {
        optionViews.forEachIndexed { index, view ->
            view.setOnClickListener { select(index) }
        }
        select(initialSelectedIndex, notify = false)
    }

    fun select(index: Int, notify: Boolean = true) {
        if (index !in optionViews.indices) return

        selectedIndex = index
        optionViews.forEachIndexed { optionIndex, view ->
            view.setBackgroundResource(
                if (optionIndex == index) {
                    R.drawable.bg_language_option_selected
                } else {
                    R.drawable.bg_language_option_unselected
                },
            )
        }

        if (notify) {
            onSelectionChanged?.invoke(index)
        }
    }

    fun selectedIndex(): Int = selectedIndex
}

package com.spacewire.meratune.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.spacewire.meratune.R

class FormOptionGroup(
    private val optionViews: List<View>,
    private val onSelectionChanged: ((String) -> Unit)? = null,
) {
    private val values: List<String> = optionViews.map { view ->
        view.findViewById<TextView>(R.id.optionLabel).text.toString()
    }

    private var selectedIndex: Int = 0

    init {
        optionViews.forEachIndexed { index, view ->
            view.setOnClickListener { select(index) }
        }
        select(0, notify = false)
    }

    fun select(index: Int, notify: Boolean = true) {
        if (index !in optionViews.indices) return

        selectedIndex = index
        optionViews.forEachIndexed { optionIndex, view ->
            val isSelected = optionIndex == index
            view.findViewById<View>(R.id.optionIndicator).setBackgroundResource(
                if (isSelected) R.drawable.bg_form_option_selected else R.drawable.bg_form_option_unselected,
            )
            view.findViewById<ImageView>(R.id.optionCheck).visibility =
                if (isSelected) View.VISIBLE else View.GONE
        }

        if (notify) {
            onSelectionChanged?.invoke(values[index])
        }
    }

    fun selectedValue(): String = values[selectedIndex]
}

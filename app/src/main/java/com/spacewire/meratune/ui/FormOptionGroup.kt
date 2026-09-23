package com.spacewire.meratune.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.spacewire.meratune.R

/**
 * Single-select radio group over `item_form_option` rows.
 *
 * @param keys stable, locale-independent key per option (e.g. `LanguageDefinition.storageValue`).
 *   Defaults to each row's label text when omitted. [onSelectionChanged] receives the key.
 */
class FormOptionGroup(
    private val optionViews: List<View>,
    keys: List<String>? = null,
    private val onSelectionChanged: ((String) -> Unit)? = null,
) {
    private val keys: List<String> = keys ?: optionViews.map { view ->
        view.findViewById<TextView>(R.id.optionLabel).text.toString()
    }

    private val enabledStates: MutableList<Boolean> = MutableList(optionViews.size) { true }

    private var selectedIndex: Int = 0

    init {
        require(this.keys.size == optionViews.size) {
            "keys (${this.keys.size}) must match optionViews (${optionViews.size})"
        }
        optionViews.forEachIndexed { index, view ->
            view.setOnClickListener {
                if (enabledStates[index]) select(index)
            }
        }
        if (optionViews.isNotEmpty()) {
            select(0, notify = false)
        }
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
            onSelectionChanged?.invoke(keys[index])
        }
    }

    fun selectedKey(): String = keys[selectedIndex]

    /** Selects the option with [key]; unknown keys are ignored. Silent unless [notify]. */
    fun selectKey(key: String, notify: Boolean = false) {
        val index = keys.indexOf(key)
        if (index >= 0) select(index, notify)
    }

    /**
     * Enables or disables one option. A disabled option ignores taps and is dimmed; the current
     * selection is left unchanged so the caller decides where to move it.
     */
    fun setEnabled(key: String, enabled: Boolean) {
        val index = keys.indexOf(key)
        if (index < 0) return
        enabledStates[index] = enabled
        val view = optionViews[index]
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    fun isEnabled(key: String): Boolean {
        val index = keys.indexOf(key)
        return index >= 0 && enabledStates[index]
    }

    private companion object {
        const val DISABLED_ALPHA = 0.45f
    }
}

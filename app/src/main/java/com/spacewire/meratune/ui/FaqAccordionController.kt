package com.spacewire.meratune.ui

import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.spacewire.meratune.R

/**
 * The paywall FAQ: one `item_paywall_faq` row per question, at most one open at a time. The open
 * row's index is [expandedIndex] (-1 when all are closed), for the activity's saved state.
 */
class FaqAccordionController(private val container: LinearLayout) {

    data class Item(val question: CharSequence, val answer: CharSequence)

    var expandedIndex: Int = NONE
        private set

    private val rows = mutableListOf<View>()

    /** Inflates the rows once; later calls only update the text (see [rebind]). */
    fun bind(items: List<Item>, initiallyExpanded: Int = NONE) {
        if (rows.isEmpty()) {
            val inflater = LayoutInflater.from(container.context)
            items.indices.forEach { index ->
                val row = inflater.inflate(R.layout.item_paywall_faq, container, false)
                row.findViewById<View>(R.id.faqHeader).setOnClickListener { toggle(index) }
                container.addView(row)
                rows += row
            }
        }
        rebind(items)
        expandedIndex = if (initiallyExpanded in rows.indices) initiallyExpanded else NONE
        rows.indices.forEach { render(it, animate = false) }
    }

    /** Updates the questions and answers (for example after the prices change). */
    fun rebind(items: List<Item>) {
        rows.forEachIndexed { index, row ->
            val item = items.getOrNull(index) ?: return@forEachIndexed
            row.findViewById<TextView>(R.id.faqQuestion).text = item.question
            row.findViewById<TextView>(R.id.faqAnswer).text = item.answer
        }
    }

    private fun toggle(index: Int) {
        val previous = expandedIndex
        expandedIndex = if (previous == index) NONE else index
        // The scroll content, so the rows below the toggled one slide too.
        TransitionManager.beginDelayedTransition(
            container.parent as? ViewGroup ?: container,
            AutoTransition().setDuration(TOGGLE_DURATION_MS),
        )
        if (previous != NONE && previous != index) render(previous, animate = true)
        render(index, animate = true)
    }

    private fun render(index: Int, animate: Boolean) {
        val row = rows[index]
        val expanded = index == expandedIndex
        row.findViewById<View>(R.id.faqAnswer).visibility = if (expanded) View.VISIBLE else View.GONE
        val chevron = row.findViewById<ImageView>(R.id.faqChevron)
        val rotation = if (expanded) 180f else 0f
        if (animate) chevron.animate().rotation(rotation).setDuration(TOGGLE_DURATION_MS).start() else chevron.rotation = rotation
        val header = row.findViewById<View>(R.id.faqHeader)
        ViewCompat.setStateDescription(
            header,
            header.context.getString(
                if (expanded) R.string.paywall_faq_state_expanded else R.string.paywall_faq_state_collapsed,
            ),
        )
    }

    companion object {
        const val NONE = -1
        private const val TOGGLE_DURATION_MS = 200L
    }
}

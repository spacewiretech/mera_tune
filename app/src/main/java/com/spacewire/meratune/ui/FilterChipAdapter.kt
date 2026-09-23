package com.spacewire.meratune.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.spacewire.meratune.R

/** One pill in a single-select filter row. [key] is stable and locale-independent. */
data class FilterChip(
    val key: String,
    val label: String,
)

/**
 * Horizontal single-select pill row (`item_filter_chip`). Used by the song picker for both the
 * voice toggle and the category chips. Selection is owned by the caller: [onChipClick] reports a
 * tap and the caller re-submits with the new [submit] `selectedKey`.
 */
class FilterChipAdapter(
    private val onChipClick: (FilterChip) -> Unit,
) : RecyclerView.Adapter<FilterChipAdapter.ChipViewHolder>() {

    private var chips: List<FilterChip> = emptyList()
    private var selectedKey: String? = null

    fun submit(chips: List<FilterChip>, selectedKey: String?) {
        this.chips = chips
        this.selectedKey = selectedKey
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_filter_chip, parent, false)
        return ChipViewHolder(view as TextView)
    }

    override fun onBindViewHolder(holder: ChipViewHolder, position: Int) {
        holder.bind(chips[position])
    }

    override fun getItemCount(): Int = chips.size

    inner class ChipViewHolder(private val chipView: TextView) : RecyclerView.ViewHolder(chipView) {
        fun bind(chip: FilterChip) {
            val isSelected = chip.key == selectedKey
            chipView.text = chip.label
            chipView.isSelected = isSelected
            chipView.setBackgroundResource(
                if (isSelected) R.drawable.bg_language_option_selected else R.drawable.bg_language_option_unselected,
            )
            chipView.setTextColor(
                ContextCompat.getColor(chipView.context, if (isSelected) R.color.navy else R.color.text_secondary),
            )
            chipView.typeface = if (isSelected) {
                Typeface.create("sans-serif-medium", Typeface.BOLD)
            } else {
                Typeface.create("sans-serif", Typeface.NORMAL)
            }
            ViewCompat.setStateDescription(
                chipView,
                if (isSelected) chipView.context.getString(R.string.song_choice_selected_a11y, chip.label) else null,
            )
            chipView.setOnClickListener { onChipClick(chip) }
        }
    }
}

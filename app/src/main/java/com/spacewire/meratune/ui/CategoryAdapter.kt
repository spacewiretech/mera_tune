package com.spacewire.meratune.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.spacewire.meratune.R
import com.spacewire.meratune.data.Category

class CategoryAdapter(
    private val onCategoryClick: (String) -> Unit,
) : RecyclerView.Adapter<CategoryAdapter.CategoryViewHolder>() {

    private var categories: List<Category> = emptyList()
    private var selectedCategoryId: String? = null

    fun submitList(categories: List<Category>, selectedCategoryId: String?) {
        this.categories = categories
        this.selectedCategoryId = selectedCategoryId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category, parent, false)
        return CategoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.bind(categories[position])
    }

    override fun getItemCount(): Int = categories.size

    inner class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val selectionRing: View = itemView.findViewById(R.id.categorySelectionRing)
        private val iconContainer: View = itemView.findViewById(R.id.categoryIconContainer)
        private val icon: ImageView = itemView.findViewById(R.id.categoryIcon)
        private val label: TextView = itemView.findViewById(R.id.categoryLabel)

        fun bind(category: Category) {
            // Localized label only; the click and analytics keep the DB id/name.
            label.text = CategoryUiHelper.displayName(itemView.context, category)
            CategoryUiHelper.bindArt(iconContainer, icon, category)

            val isSelected = when {
                category.id == Category.ALL_CATEGORY_ID ->
                    selectedCategoryId == null || selectedCategoryId == Category.ALL_CATEGORY_ID
                else -> selectedCategoryId == category.id
            }

            selectionRing.visibility = if (isSelected) View.VISIBLE else View.GONE
            // TalkBack announces "selected"; no dimming or weight swap in the refreshed chips.
            itemView.isSelected = isSelected

            itemView.setOnClickListener {
                onCategoryClick(category.id)
            }
        }
    }
}

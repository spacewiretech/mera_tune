package com.spacewire.meratune.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.spacewire.meratune.R

/**
 * Thin divider drawn inside each row's bottom padding, inset from start/end (mirrored in RTL),
 * with no line after the last item. Adds no item offsets.
 */
class InsetDividerDecoration(
    context: Context,
    @ColorRes colorRes: Int = R.color.divider,
    insetStartDp: Float = 0f,
    insetEndDp: Float = 0f,
    heightDp: Float = 1f,
) : RecyclerView.ItemDecoration() {

    private val density = context.resources.displayMetrics.density
    private val insetStartPx = insetStartDp * density
    private val insetEndPx = insetEndDp * density
    private val heightPx = heightDp * density
    private val paint = Paint().apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, colorRes)
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val itemCount = parent.adapter?.itemCount ?: return
        val rtl = parent.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val leftInset = if (rtl) insetEndPx else insetStartPx
        val rightInset = if (rtl) insetStartPx else insetEndPx
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION || position >= itemCount - 1) continue
            val bottom = child.bottom + child.translationY
            val left = child.left + child.translationX + leftInset
            val right = child.right + child.translationX - rightInset
            if (right <= left) continue
            canvas.drawRect(left, bottom - heightPx, right, bottom, paint)
        }
    }
}

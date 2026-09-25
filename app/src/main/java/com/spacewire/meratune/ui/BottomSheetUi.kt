package com.spacewire.meratune.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.R as MaterialR

/** Scrim for the refreshed sheets: the screen behind stays readable, as in the mocks. */
const val LIGHT_SCRIM_DIM = 0.08f

/**
 * Shows [content] fully expanded on a transparent sheet container (the content draws its own
 * rounded background). [dimAmount] overrides the theme scrim when set.
 *
 * Navigation bar: when the dialog is edge-to-edge, the bottom inset is added to [content]'s own
 * bottom padding so its white background runs under the bar. Any bottom padding the M3 sheet
 * behaviour already put on the transparent container is moved onto [content] (never counted
 * twice), so no see-through strip is left below the sheet on 3-button or gesture navigation.
 */
fun BottomSheetDialog.present(content: View, dimAmount: Float? = null) {
    setContentView(content)
    val basePaddingBottom = content.paddingBottom
    val sheet = findViewById<View>(MaterialR.id.design_bottom_sheet)
    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
        val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        if (sheet != null && sheet.paddingBottom != 0) sheet.updatePadding(bottom = 0)
        view.updatePadding(bottom = basePaddingBottom + navBottom)
        insets
    }
    setOnShowListener {
        sheet?.background = ColorDrawable(Color.TRANSPARENT)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }
    dimAmount?.let { window?.setDimAmount(it) }
    show()
}

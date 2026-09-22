package com.spacewire.meratune.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.R as MaterialR

fun BottomSheetDialog.present(content: View) {
    setContentView(content)
    setOnShowListener {
        findViewById<View>(MaterialR.id.design_bottom_sheet)?.background = ColorDrawable(Color.TRANSPARENT)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }
    show()
}

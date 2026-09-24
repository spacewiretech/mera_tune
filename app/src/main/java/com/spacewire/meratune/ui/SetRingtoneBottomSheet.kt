package com.spacewire.meratune.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.spacewire.meratune.R
import com.spacewire.meratune.calltheme.RingtoneSetMode

class SetRingtoneBottomSheet(
    private val context: Context,
    private val onContinue: (RingtoneSetMode) -> Unit,
    /** Dismissed without Continue (back, outside tap or swipe). */
    private val onDismissed: () -> Unit,
) {

    fun show() {
        val dialog = BottomSheetDialog(context)
        val sheetView = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_set_ringtone, null)
        val container = sheetView.findViewById<LinearLayout>(R.id.setRingtoneOptionsContainer)
        val optionViews = mutableMapOf<RingtoneSetMode, View>()
        var selectedMode = RingtoneSetMode.AUDIO_ONLY
        var continued = false

        fun bindSelection() {
            optionViews.forEach { (mode, view) ->
                val selected = mode == selectedMode
                view.findViewById<View>(R.id.setRingtoneOptionIndicator).setBackgroundResource(
                    if (selected) R.drawable.bg_radio_selected else R.drawable.bg_form_option_unselected,
                )
                view.findViewById<ImageView>(R.id.setRingtoneOptionCheck).visibility =
                    if (selected) View.VISIBLE else View.GONE
            }
        }

        RingtoneSetMode.entries.forEach { mode ->
            val optionView = LayoutInflater.from(context)
                .inflate(R.layout.item_set_ringtone_option, container, false)

            optionView.findViewById<ImageView>(R.id.setRingtoneOptionIllustration)
                .setImageResource(mode.illustrationRes)
            optionView.findViewById<TextView>(R.id.setRingtoneOptionTitle).setText(mode.titleRes)
            optionView.setOnClickListener {
                selectedMode = mode
                bindSelection()
            }
            optionViews[mode] = optionView
            container.addView(optionView)
        }

        bindSelection()

        sheetView.findViewById<TextView>(R.id.setRingtoneContinueButton).setOnClickListener {
            if (continued) return@setOnClickListener
            continued = true
            onContinue(selectedMode)
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            if (!continued) onDismissed()
        }
        dialog.present(sheetView)
    }
}

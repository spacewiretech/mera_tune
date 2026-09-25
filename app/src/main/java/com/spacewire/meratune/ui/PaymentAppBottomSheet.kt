package com.spacewire.meratune.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.spacewire.meratune.R
import com.spacewire.meratune.model.PaymentApp

/**
 * The paywall's payment-app sheet. [availableApps] is [PaymentApp.sheetOptions]: the installed UPI
 * apps, then the [PaymentApp.UPI_ID] row ("other UPI app / UPI ID", or "pay with UPI ID" alone).
 */
class PaymentAppBottomSheet(
    private val context: Context,
    private val selectedApp: PaymentApp,
    private val availableApps: List<PaymentApp>,
    private val onAppSelected: (PaymentApp) -> Unit,
) {

    fun show() {
        if (availableApps.isEmpty()) return

        val dialog = BottomSheetDialog(context)
        val sheetView = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_payment_apps, null)
        val container = sheetView.findViewById<LinearLayout>(R.id.paymentAppOptionsContainer)
        sheetView.findViewById<TextView>(R.id.paymentAppSheetTitle)
            .setText(PaymentApp.sheetTitleRes(availableApps))
        // Two quick taps on different rows can both land before dismiss() takes effect.
        var handled = false
        val upiIdLabel = context.getString(PaymentApp.upiIdSheetLabelRes(availableApps))

        availableApps.forEach { app ->
            val optionView = LayoutInflater.from(context)
                .inflate(R.layout.item_payment_app_option, container, false)

            PaymentAppBadge.bind(optionView.findViewById(R.id.paymentAppOptionIcon), app)
            optionView.findViewById<TextView>(R.id.paymentAppOptionName).text =
                if (app == PaymentApp.UPI_ID) upiIdLabel else app.label(context)
            bindSelection(optionView, app == selectedApp)

            optionView.setOnClickListener {
                if (handled) return@setOnClickListener
                handled = true
                onAppSelected(app)
                dialog.dismiss()
            }
            container.addView(optionView)
        }

        dialog.present(sheetView)
    }

    private fun bindSelection(optionView: View, selected: Boolean) {
        optionView.findViewById<View>(R.id.paymentAppOptionIndicator).setBackgroundResource(
            if (selected) R.drawable.bg_form_option_selected else R.drawable.bg_form_option_unselected,
        )
        optionView.findViewById<ImageView>(R.id.paymentAppOptionCheck).visibility =
            if (selected) View.VISIBLE else View.GONE
    }
}

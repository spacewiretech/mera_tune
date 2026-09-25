package com.spacewire.meratune.ui

import android.widget.TextView
import com.spacewire.meratune.model.PaymentApp

/** The round payment-app badge on the paywall pill and in the payment-app sheet. */
object PaymentAppBadge {

    /** A brand logo when [PaymentApp.logoRes] is set, else the brand-colour oval with its label. */
    fun bind(badge: TextView, app: PaymentApp) {
        val logo = app.logoRes
        if (logo != null) {
            badge.setBackgroundResource(logo)
            badge.text = null
        } else {
            badge.setBackgroundResource(app.iconBackgroundRes)
            badge.text = app.iconLabel
        }
    }
}

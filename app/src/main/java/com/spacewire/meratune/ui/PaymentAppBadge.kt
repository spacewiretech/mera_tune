package com.spacewire.meratune.ui

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.TypedValue
import android.widget.TextView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.core.graphics.drawable.toBitmap
import com.spacewire.meratune.model.PaymentApp

/** The round payment-app badge on the paywall pill and in the payment-app sheet. */
object PaymentAppBadge {

    private const val TAG = "PaymentAppBadge"
    private const val FALLBACK_SIZE_DP = 40f

    /**
     * The installed app's own launcher icon, cropped to a circle (the manifest `<queries>` makes
     * the four UPI packages visible). Falls back to [PaymentApp.logoRes] when set, else the
     * brand-colour oval with its label, e.g. when the app is not installed.
     */
    fun bind(badge: TextView, app: PaymentApp) {
        val icon = launcherIcon(badge, app)
        val logo = app.logoRes
        when {
            icon != null -> {
                badge.background = icon
                badge.text = null
            }
            logo != null -> {
                badge.setBackgroundResource(logo)
                badge.text = null
            }
            else -> {
                badge.setBackgroundResource(app.iconBackgroundRes)
                badge.text = app.iconLabel
            }
        }
    }

    private fun launcherIcon(badge: TextView, app: PaymentApp): Drawable? {
        val sizePx = badge.layoutParams?.width?.takeIf { it > 0 }
            ?: TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                FALLBACK_SIZE_DP,
                badge.resources.displayMetrics,
            ).toInt()
        return try {
            val icon = badge.context.packageManager.getApplicationIcon(app.packageName)
            // Adaptive icons draw inside the launcher mask; every mask shape contains its inscribed
            // circle, so the circular crop always shows a full round logo.
            val bitmap = icon.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            RoundedBitmapDrawableFactory.create(badge.resources, bitmap).apply {
                isCircular = true
                setAntiAlias(true)
            }
        } catch (e: Exception) {
            // Not installed (NameNotFoundException) or the icon failed to draw: the text badge.
            Log.d(TAG, "No launcher icon for ${app.packageName}: ${e.javaClass.simpleName}")
            null
        }
    }
}

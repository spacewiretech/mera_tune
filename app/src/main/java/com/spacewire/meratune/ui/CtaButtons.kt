package com.spacewire.meratune.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.spacewire.meratune.R

/**
 * Shared behaviour for `Widget.MeraTune.Cta` buttons. They are MaterialButtons, but every helper
 * takes a [TextView] so callers keep their existing `findViewById<TextView>` casts, and legacy
 * plain-TextView CTAs keep working.
 *
 * The disabled look is plain `isEnabled = false` (the `bg_create_ringtone_button` selector turns it
 * grey); never use alpha.
 */
object CtaButtons {

    /**
     * Shows or hides the loading state. While loading, the label and end icon turn transparent (so
     * the button keeps its width), taps are ignored and TalkBack announces [R.string.cta_loading].
     * [progress] is the sibling spinner drawn over the button (it sits in the same FrameLayout; the
     * CTA style's 0dp elevation keeps it on top). `false` restores the saved colours. Repeated
     * calls with the same value are safe.
     */
    fun setLoading(button: TextView, progress: View?, loading: Boolean) {
        if (loading) {
            if (button.getTag(R.id.tag_cta_saved_text_colors) == null) {
                button.setTag(R.id.tag_cta_saved_text_colors, button.textColors)
                if (button is MaterialButton) {
                    button.setTag(R.id.tag_cta_saved_icon_tint, button.iconTint)
                }
            }
            button.setTextColor(Color.TRANSPARENT)
            if (button is MaterialButton) {
                button.iconTint = ColorStateList.valueOf(Color.TRANSPARENT)
            }
            button.isClickable = false
            ViewCompat.setStateDescription(button, button.context.getString(R.string.cta_loading))
            progress?.visibility = View.VISIBLE
        } else {
            val savedTextColors = button.getTag(R.id.tag_cta_saved_text_colors) as? ColorStateList
            if (savedTextColors != null) {
                button.setTextColor(savedTextColors)
                if (button is MaterialButton) {
                    button.iconTint = button.getTag(R.id.tag_cta_saved_icon_tint) as? ColorStateList
                }
                button.setTag(R.id.tag_cta_saved_text_colors, null)
                button.setTag(R.id.tag_cta_saved_icon_tint, null)
            }
            button.isClickable = true
            ViewCompat.setStateDescription(button, null)
            progress?.visibility = View.GONE
        }
    }

    /**
     * Greys a primary CTA while leaving it enabled and tappable (for example, the phone CTA stays
     * tappable while the number is invalid, so its click handler can explain why). Only for
     * `Widget.MeraTune.Cta`, whose normal background is `bg_create_ringtone_button`.
     */
    fun setLooksDisabled(button: TextView, looksDisabled: Boolean) {
        if (button.getTag(R.id.tag_cta_looks_disabled) == looksDisabled) return
        button.setTag(R.id.tag_cta_looks_disabled, looksDisabled)
        button.setBackgroundResource(
            if (looksDisabled) R.drawable.bg_cta_disabled else R.drawable.bg_create_ringtone_button,
        )
    }

    /**
     * Sets or clears ([res] = null) the end icon. On a MaterialButton the icon stays next to the
     * label (`iconGravity=textEnd`); a plain TextView falls back to an end compound drawable.
     */
    fun setEndIcon(button: TextView, @DrawableRes res: Int?) {
        if (button is MaterialButton) {
            if (res == null) button.icon = null else button.setIconResource(res)
        } else {
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, res ?: 0, 0)
        }
    }
}

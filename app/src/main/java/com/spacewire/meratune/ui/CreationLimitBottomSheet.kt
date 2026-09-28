package com.spacewire.meratune.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.os.ConfigurationCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.spacewire.meratune.R
import com.spacewire.meratune.data.GenerationQuota
import com.spacewire.meratune.data.Tune
import com.spacewire.meratune.util.FormatUtils
import com.spacewire.meratune.util.GradientTextHelper
import java.util.Locale

/** Scrim for the limit sheet: darker than [LIGHT_SCRIM_DIM], as in the mock. */
private const val LIMIT_SCRIM_DIM = 0.17f

/**
 * Home's "limit reached" sheet, shown instead of the create form when a member's [quota] is used
 * up ([CreationLimitPolicy.isExhausted]). The copy follows [CreationLimitPolicy.variant]; the card
 * shows [latestRingtone] (the user's newest own ringtone), and is hidden without one.
 */
class CreationLimitBottomSheet(
    private val context: Context,
    private val quota: GenerationQuota,
    private val latestRingtone: Tune?,
    /** "Explore More Tunes", after the sheet is dismissed. */
    private val onExploreTunes: () -> Unit,
) {

    /**
     * Shows the sheet; the caller dismisses the returned dialog when its screen is destroyed. The
     * layout is inflated without a parent (a dialog has none), like the other sheets.
     */
    @SuppressLint("InflateParams")
    fun show(): BottomSheetDialog {
        val dialog = BottomSheetDialog(context)
        val sheetView = LayoutInflater.from(context).inflate(R.layout.sheet_creation_limit, null)
        val variant = CreationLimitPolicy.variant(quota)
        val limit = quota.limitCount ?: quota.usedCount ?: 0

        bindTitle(sheetView.findViewById(R.id.limitTitle), variant)
        sheetView.findViewById<TextView>(R.id.limitSubtitle).text = countText(
            when (variant) {
                CreationLimitPolicy.Variant.TRIAL -> R.string.home_limit_subtitle_trial
                CreationLimitPolicy.Variant.MEMBER -> R.string.home_limit_subtitle_member
                CreationLimitPolicy.Variant.DAILY -> R.string.home_limit_subtitle_daily
            },
            limit,
        )
        bindRingtoneCard(sheetView)
        bindInfoBox(sheetView, variant, limit)

        sheetView.findViewById<View>(R.id.limitExploreButton).setOnClickListener {
            dialog.dismiss()
            onExploreTunes()
        }
        sheetView.findViewById<View>(R.id.limitCloseButton).setOnClickListener { dialog.dismiss() }

        dialog.present(sheetView, LIMIT_SCRIM_DIM)
        return dialog
    }

    /** "Your {Trial Limit} Reached!" with the pink-to-orange gradient on the limit's name. */
    private fun bindTitle(titleView: TextView, variant: CreationLimitPolicy.Variant) {
        val highlight = context.getString(
            when (variant) {
                CreationLimitPolicy.Variant.TRIAL -> R.string.home_limit_name_trial
                CreationLimitPolicy.Variant.MEMBER -> R.string.home_limit_name_member
                CreationLimitPolicy.Variant.DAILY -> R.string.home_limit_name_daily
            },
        )
        GradientTextHelper.setTextWithGradientHighlight(
            titleView,
            context.getString(R.string.home_limit_title, highlight),
            highlight,
            R.color.gradient_pink,
            R.color.gradient_orange,
        )
    }

    /** The newest own ringtone, bound like a Home row (art, title, likes / views). */
    private fun bindRingtoneCard(sheetView: View) {
        val card = sheetView.findViewById<View>(R.id.limitRingtoneCard)
        val tune = latestRingtone
        card.isVisible = tune != null
        if (tune == null) return
        CategoryUiHelper.bindArt(
            sheetView.findViewById(R.id.limitRingtoneArt),
            sheetView.findViewById<ImageView>(R.id.limitRingtoneArtIcon),
            tune.category,
        )
        sheetView.findViewById<TextView>(R.id.limitRingtoneTitle).text = tune.name
        sheetView.findViewById<TextView>(R.id.limitRingtoneLikes).text = FormatUtils.formatCount(tune.likesCount)
        sheetView.findViewById<TextView>(R.id.limitRingtoneViews).text = FormatUtils.formatCount(tune.viewsCount)
    }

    /**
     * When creating opens up again: a trial becomes the monthly plan tomorrow, a member's month
     * resets on its date ("1 October", IST), a daily limit at midnight. A member quota without a
     * readable reset date hides the box.
     */
    private fun bindInfoBox(sheetView: View, variant: CreationLimitPolicy.Variant, limit: Int) {
        val titleView = sheetView.findViewById<TextView>(R.id.limitInfoTitle)
        val bodyView = sheetView.findViewById<TextView>(R.id.limitInfoBody)
        when (variant) {
            CreationLimitPolicy.Variant.TRIAL -> {
                titleView.setText(R.string.home_limit_info_trial_title)
                val monthlyLimit = quota.memberMonthlyLimit?.takeIf { it > 0 }
                bodyView.text = if (monthlyLimit != null) {
                    countText(R.string.home_limit_info_trial_body, monthlyLimit)
                } else {
                    context.getString(R.string.home_limit_info_trial_body_generic)
                }
            }

            CreationLimitPolicy.Variant.MEMBER -> {
                val resetDate = CreationLimitPolicy.formatResetDate(quota, appLocale())
                if (resetDate == null) {
                    sheetView.findViewById<View>(R.id.limitInfoBox).isVisible = false
                    return
                }
                titleView.text = context.getString(R.string.home_limit_info_member_title, resetDate)
                bodyView.text = countText(R.string.home_limit_info_member_body, limit)
            }

            CreationLimitPolicy.Variant.DAILY -> {
                titleView.setText(R.string.home_limit_info_daily_title)
                bodyView.setText(R.string.home_limit_info_daily_body)
            }
        }
    }

    /**
     * [resId] with its `%1$d` count in Latin digits, like every other number in the app
     * (`getString` would use the locale's digits, e.g. Devanagari in Marathi).
     */
    private fun countText(resId: Int, count: Int): String =
        String.format(Locale.ROOT, context.getString(resId), count)

    /** The app language (month names follow it). */
    private fun appLocale(): Locale =
        ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.getDefault()
}

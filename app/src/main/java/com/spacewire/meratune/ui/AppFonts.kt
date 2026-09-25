package com.spacewire.meratune.ui

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.spacewire.meratune.R

/**
 * Manrope faces for code that sets a typeface at runtime (XML uses `@font/manrope_*` or the
 * `Text.MeraTune.*` styles). ResourcesCompat caches loaded fonts, so these are cheap to call from
 * a bind. Falls back to the system default if a font cannot be loaded.
 */
object AppFonts {
    fun regular(context: Context): Typeface = load(context, R.font.manrope_regular)

    fun medium(context: Context): Typeface = load(context, R.font.manrope_medium)

    fun semibold(context: Context): Typeface = load(context, R.font.manrope_semibold)

    fun bold(context: Context): Typeface = load(context, R.font.manrope_bold)

    fun extrabold(context: Context): Typeface = load(context, R.font.manrope_extrabold)

    private fun load(context: Context, fontRes: Int): Typeface =
        ResourcesCompat.getFont(context, fontRes) ?: Typeface.DEFAULT
}

package com.spacewire.meratune.util

import java.util.Locale
import kotlin.math.roundToInt

object FormatUtils {
    fun formatCount(count: Int): String {
        return when {
            count >= 1_000_000 -> {
                val value = count / 1_000_000.0
                String.format(Locale.US, "%.1fm", value).removeSuffix(".0m") + "m"
            }
            count >= 1_000 -> {
                val value = count / 1_000.0
                val formatted = String.format(Locale.US, "%.1f", value)
                if (formatted.endsWith(".0")) {
                    "${formatted.dropLast(2)}k"
                } else {
                    "${formatted}k"
                }
            }
            else -> count.toString()
        }
    }
}

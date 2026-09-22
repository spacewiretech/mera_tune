package com.spacewire.meratune.util

object GeneratedTuneTitleHelper {
    fun titleFor(name: String, category: String): String {
        return when (category.lowercase()) {
            "devotional" -> "Jai Shri Ram $name ji.."
            "romantic" -> "Pyar Ka Paigaam $name ji.."
            "family" -> "Hamari Family $name ji.."
            "cinematic" -> "Cinematic Vibes $name ji.."
            else -> "$name ki Ringtone"
        }
    }
}

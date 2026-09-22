package com.spacewire.meratune.calltheme

/**
 * Normalizes phone numbers for caller matching.
 * Uses the last 10 digits so +91 / 0 / local formats resolve to the same key.
 */
object PhoneMatch {
    fun normalizeKey(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val digits = raw.filter { it.isDigit() }
        if (digits.length < 10) return null
        return digits.takeLast(10)
    }
}

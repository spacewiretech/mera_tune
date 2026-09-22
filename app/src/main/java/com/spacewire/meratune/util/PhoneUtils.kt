package com.spacewire.meratune.util

object PhoneUtils {
    fun normalizeIndianPhone(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.length == 10 -> digits
            digits.length == 12 && digits.startsWith("91") -> digits.drop(2)
            digits.length == 11 && digits.startsWith("0") -> digits.drop(1)
            else -> null
        }
    }

    fun formatDisplayPhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        val local = when {
            digits.length == 10 -> digits
            digits.length == 12 && digits.startsWith("91") -> digits.drop(2)
            else -> digits
        }
        return "+91 $local"
    }
}

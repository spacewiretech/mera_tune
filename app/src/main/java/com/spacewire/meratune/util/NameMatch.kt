package com.spacewire.meratune.util

import java.text.Normalizer
import java.util.Locale

/**
 * Whole-word name match in a tune title, for "ringtones of this name" (Home's "{name} Tunes" chip
 * and the create flow's existing name ringtones step): "Ram" matches "Jai Shri Ram", "Ram-Ram"
 * and "RAM bhajan" but not "Ramesh", "Param" or "Balaram". Case-insensitive, Unicode NFC, trimmed,
 * inner spacing collapsed; Indic vowel signs count as part of a word, so "राम" does not match
 * inside "रामेश". Search keeps its plain substring match (HomeTuneFilter.matches).
 *
 * Pure (no `android.*`), unit tested in NameMatchTest.
 */
object NameMatch {

    private val WHITESPACE = Regex("\\s+")

    fun titleHasName(title: String, name: String): Boolean {
        val needle = normalize(name)
        if (needle.isEmpty()) return false
        val haystack = normalize(title)
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return false
            val end = at + needle.length
            val startsWord = at == 0 || !isWordChar(haystack[at - 1])
            val endsWord = end == haystack.length || !isWordChar(haystack[end])
            if (startsWord && endsWord) return true
            from = at + 1
        }
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFC).trim().replace(WHITESPACE, " ").lowercase(Locale.ROOT)

    /** Letters, digits, combining marks (Indic vowel signs, viramas) and the zero-width joiners. */
    private fun isWordChar(c: Char): Boolean {
        if (Character.isLetterOrDigit(c) || c == '‌' || c == '‍') return true
        return when (Character.getType(c)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            -> true
            else -> false
        }
    }
}

package com.spacewire.meratune.util

import java.text.Normalizer
import java.util.Locale

enum class NameInvalidReason {
    EMPTY,
    TOO_LONG,
    TOO_MANY_WORDS,
    INVALID_CHARS,
    MIXED_SCRIPT,
}

sealed class NameValidation {
    /** @property display what is shown and sent to the server; @property normalized cache key form. */
    data class Valid(val display: String, val normalized: String) : NameValidation()
    data class Invalid(val reason: NameInvalidReason) : NameValidation()
}

/**
 * Mirrors `supabase/functions/generate-ringtone/names.ts`. Pure Kotlin: no `android.*` imports so
 * it runs in JVM unit tests (`NameNormalizerTest` + `name_normalization_cases.json`).
 *
 * Pipeline: NFC -> drop emoji / pictographs / variation selectors / ZWSP -> drop other control and
 * format characters (ZWJ and ZWNJ survive only between letters or marks) -> collapse whitespace -> trim.
 * Rejects empty, > [MAX_CODE_POINTS], > [MAX_WORDS] words, characters outside letters / marks / space /
 * apostrophes / period / hyphen (or no letter at all), and text that mixes two scripts (e.g. Latin
 * with Devanagari). The backend fixture `supabase/functions/tests/fixtures/name_normalization_cases.json`
 * is run against this class too, so both sides stay in step.
 */
object NameNormalizer {
    const val MAX_CODE_POINTS = 30
    const val MAX_WORDS = 3

    private const val SPACE = ' '.code
    private const val ZWSP = 0x200B
    private const val ZWNJ = 0x200C
    private const val ZWJ = 0x200D
    private const val COMBINING_ENCLOSING_KEYCAP = 0x20E3
    private const val RIGHT_SINGLE_QUOTATION_MARK = 0x2019

    /** Extended_Pictographic code points that are not General_Category `So` (plus the big emoji planes). */
    private val PICTOGRAPHIC_RANGES: List<IntRange> = listOf(
        0x00A9..0x00A9, 0x00AE..0x00AE, 0x203C..0x203C, 0x2049..0x2049, 0x2122..0x2122, 0x2139..0x2139,
        0x2194..0x2199, 0x21A9..0x21AA, 0x231A..0x231B, 0x2328..0x2328, 0x23CF..0x23CF, 0x23E9..0x23F3,
        0x23F8..0x23FA, 0x24C2..0x24C2, 0x25AA..0x25AB, 0x25B6..0x25B6, 0x25C0..0x25C0, 0x25FB..0x25FE,
        0x2600..0x27BF, 0x2934..0x2935, 0x2B05..0x2B07, 0x2B1B..0x2B1C, 0x2B50..0x2B50, 0x2B55..0x2B55,
        0x3030..0x3030, 0x303D..0x303D, 0x3297..0x3297, 0x3299..0x3299,
        0x1F000..0x1FAFF, 0x1FC00..0x1FFFD,
    )

    private val VARIATION_SELECTOR_RANGES: List<IntRange> = listOf(
        0xFE00..0xFE0F,
        0xE0100..0xE01EF,
    )

    fun validate(raw: String): NameValidation {
        val display = display(raw)
        if (display.isEmpty()) return NameValidation.Invalid(NameInvalidReason.EMPTY)
        if (display.codePointCount(0, display.length) > MAX_CODE_POINTS) {
            return NameValidation.Invalid(NameInvalidReason.TOO_LONG)
        }
        if (display.split(' ').size > MAX_WORDS) {
            return NameValidation.Invalid(NameInvalidReason.TOO_MANY_WORDS)
        }

        val scripts = HashSet<Character.UnicodeScript>()
        var hasLetter = false
        var index = 0
        while (index < display.length) {
            val codePoint = display.codePointAt(index)
            index += Character.charCount(codePoint)

            if (!isAllowedCodePoint(codePoint)) {
                return NameValidation.Invalid(NameInvalidReason.INVALID_CHARS)
            }
            if (Character.isLetter(codePoint)) hasLetter = true
            if (isLetterOrMark(codePoint)) {
                val script = Character.UnicodeScript.of(codePoint)
                if (script != Character.UnicodeScript.COMMON &&
                    script != Character.UnicodeScript.INHERITED &&
                    script != Character.UnicodeScript.UNKNOWN
                ) {
                    scripts.add(script)
                }
            }
        }
        // Punctuation-only input ("...", "-") is not a name; the server rejects it the same way.
        if (!hasLetter) return NameValidation.Invalid(NameInvalidReason.INVALID_CHARS)
        if (scripts.size > 1) return NameValidation.Invalid(NameInvalidReason.MIXED_SCRIPT)

        return NameValidation.Valid(display = display, normalized = display.lowercase(Locale.ROOT))
    }

    /** Cleaned form of [raw] (may be empty). Idempotent. */
    fun display(raw: String): String {
        val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC)

        val kept = ArrayList<Int>(nfc.length)
        var index = 0
        while (index < nfc.length) {
            val codePoint = nfc.codePointAt(index)
            index += Character.charCount(codePoint)
            when {
                codePoint == ZWJ || codePoint == ZWNJ -> kept.add(codePoint)
                isWhitespace(codePoint) -> kept.add(SPACE)
                isStrippedSymbol(codePoint) -> Unit
                isControlOrFormat(codePoint) -> Unit
                else -> kept.add(codePoint)
            }
        }

        val builder = StringBuilder(kept.size)
        var pendingSpace = false
        var hasContent = false
        for (position in kept.indices) {
            val codePoint = kept[position]
            when {
                codePoint == SPACE -> pendingSpace = hasContent

                codePoint == ZWJ || codePoint == ZWNJ -> {
                    val previous = neighbourNonJoiner(kept, position, step = -1)
                    val next = neighbourNonJoiner(kept, position, step = 1)
                    if (previous != null && isLetterOrMark(previous) && next != null && isLetterOrMark(next)) {
                        builder.appendCodePoint(codePoint)
                    }
                }

                else -> {
                    if (pendingSpace) builder.append(' ')
                    pendingSpace = false
                    hasContent = true
                    builder.appendCodePoint(codePoint)
                }
            }
        }
        return builder.toString()
    }

    /** Cache-key form: [display] lower-cased with the root locale. */
    fun normalizeForKey(raw: String): String = display(raw).lowercase(Locale.ROOT)

    private fun neighbourNonJoiner(codePoints: List<Int>, from: Int, step: Int): Int? {
        var position = from + step
        while (position in codePoints.indices) {
            val candidate = codePoints[position]
            if (candidate != ZWJ && candidate != ZWNJ) return candidate
            position += step
        }
        return null
    }

    private fun isAllowedCodePoint(codePoint: Int): Boolean {
        return isLetterOrMark(codePoint) ||
            codePoint == SPACE ||
            codePoint == '\''.code ||
            codePoint == RIGHT_SINGLE_QUOTATION_MARK ||
            codePoint == '.'.code ||
            codePoint == '-'.code ||
            codePoint == ZWJ ||
            codePoint == ZWNJ
    }

    private fun isLetterOrMark(codePoint: Int): Boolean = Character.isLetter(codePoint) || isMark(codePoint)

    private fun isMark(codePoint: Int): Boolean {
        return when (Character.getType(codePoint).toByte()) {
            Character.NON_SPACING_MARK,
            Character.ENCLOSING_MARK,
            Character.COMBINING_SPACING_MARK,
            -> true

            else -> false
        }
    }

    private fun isWhitespace(codePoint: Int): Boolean =
        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)

    private fun isControlOrFormat(codePoint: Int): Boolean {
        val type = Character.getType(codePoint).toByte()
        return type == Character.CONTROL || type == Character.FORMAT
    }

    private fun isStrippedSymbol(codePoint: Int): Boolean {
        if (codePoint == ZWSP || codePoint == COMBINING_ENCLOSING_KEYCAP) return true
        if (Character.getType(codePoint).toByte() == Character.OTHER_SYMBOL) return true
        if (VARIATION_SELECTOR_RANGES.any { codePoint in it }) return true
        return PICTOGRAPHIC_RANGES.any { codePoint in it }
    }
}

package com.spacewire.meratune.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Cases in `name_normalization_cases.json` are shared with the Deno tests for
 * `supabase/functions/generate-ringtone/names.ts`; keep both implementations green on it.
 */
class NameNormalizerTest {

    @Serializable
    private data class NameCase(
        val name: String,
        val input: String,
        val valid: Boolean,
        val display: String? = null,
        val normalized: String? = null,
        val reason: String? = null,
    )

    /** Shape of the backend fixture (`{"cases": [{"id", "input", "valid", ...}]}`). */
    @Serializable
    private data class BackendCase(
        val id: String,
        val input: String,
        val valid: Boolean,
        val display: String? = null,
        val normalized: String? = null,
        val reason: String? = null,
    )

    @Serializable
    private data class BackendFixture(val cases: List<BackendCase>)

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadCases(): List<NameCase> {
        val stream = javaClass.classLoader!!.getResourceAsStream(FIXTURE)
            ?: error("Missing test resource $FIXTURE")
        return stream.bufferedReader(Charsets.UTF_8).use { json.decodeFromString(it.readText()) }
    }

    /** The Deno tests' fixture, resolved relative to the module or repo root; null when this checkout lacks it. */
    private fun loadBackendCases(): List<NameCase>? {
        val file = listOf(
            File(BACKEND_FIXTURE),
            File("..", BACKEND_FIXTURE),
            File(System.getProperty("user.dir"), BACKEND_FIXTURE),
            File(System.getProperty("user.dir"), "../$BACKEND_FIXTURE"),
        ).firstOrNull { it.isFile } ?: return null
        val fixture: BackendFixture = json.decodeFromString(file.readText(Charsets.UTF_8))
        return fixture.cases.map { NameCase(it.id, it.input, it.valid, it.display, it.normalized, it.reason) }
    }

    @Test
    fun fixtureHasEnoughCoverage() {
        assertTrue("expected at least 20 shared cases", loadCases().size >= 20)
    }

    @Test
    fun sharedFixtureCases() {
        runCases(loadCases())
    }

    /** Runs the server-side fixture against the Kotlin mirror so the two implementations cannot drift. */
    @Test
    fun backendFixtureCasesAgreeWithKotlinMirror() {
        val cases = loadBackendCases()
        Assume.assumeTrue("backend fixture $BACKEND_FIXTURE not present in this checkout", cases != null)
        assertTrue("backend fixture is empty", cases!!.isNotEmpty())
        runCases(cases)
    }

    private fun runCases(cases: List<NameCase>) {
        val failures = mutableListOf<String>()
        for (case in cases) {
            val result = NameNormalizer.validate(case.input)
            when {
                case.valid && result is NameValidation.Valid -> {
                    case.display?.let { expected ->
                        if (expected != result.display) {
                            failures += "${case.name}: display expected <$expected> got <${result.display}>"
                        }
                    }
                    case.normalized?.let { expected ->
                        if (expected != result.normalized) {
                            failures += "${case.name}: normalized expected <$expected> got <${result.normalized}>"
                        }
                    }
                }

                case.valid -> failures += "${case.name}: expected valid, got $result"

                result is NameValidation.Invalid -> {
                    if (case.reason != null && case.reason != result.reason.name) {
                        failures += "${case.name}: reason expected ${case.reason} got ${result.reason}"
                    }
                }

                else -> failures += "${case.name}: expected invalid (${case.reason}), got $result"
            }
        }
        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n"))
        }
    }

    @Test
    fun maxCodePointsIsThirty() {
        assertEquals(30, NameNormalizer.MAX_CODE_POINTS)
    }

    @Test
    fun loneZeroWidthJoinerIsEmpty() {
        assertEquals(NameValidation.Invalid(NameInvalidReason.EMPTY), NameNormalizer.validate("‍"))
        assertEquals(NameValidation.Invalid(NameInvalidReason.EMPTY), NameNormalizer.validate("‌‍"))
    }

    @Test
    fun joinersLeftOverFromEmojiSequencesAreDropped() {
        // family emoji = man ZWJ woman ZWJ girl
        val input = "Ayush 👨‍👩‍👧"
        assertEquals(NameValidation.Valid("Ayush", "ayush"), NameNormalizer.validate(input))
    }

    @Test
    fun joinerNextToSpaceIsDropped() {
        assertEquals("Ayush Kumar", NameNormalizer.display("Ayush‍ Kumar"))
        assertEquals("Ayush Kumar", NameNormalizer.display("Ayush ‌Kumar"))
    }

    @Test
    fun tabsAndNewlinesBecomeSingleSpaces() {
        assertEquals("Ayush Kumar", NameNormalizer.display("Ayush\tKumar"))
        assertEquals("Ayush Kumar", NameNormalizer.display("Ayush \n  Kumar"))
        assertEquals("Ayush Kumar", NameNormalizer.display("Ayush Kumar"))
    }

    @Test
    fun displayIsIdempotent() {
        val inputs = listOf("  Ayush  😀 Kumar ", "क्‍ष", "Ayúsh", "❤️ राम")
        for (input in inputs) {
            val once = NameNormalizer.display(input)
            assertEquals("display(display(x)) for <$input>", once, NameNormalizer.display(once))
        }
    }

    @Test
    fun normalizeForKeyMatchesValidNormalized() {
        val inputs = listOf("Ayush", "  D’Souza ", "आयुष", "JOSÉ")
        for (input in inputs) {
            val result = NameNormalizer.validate(input) as NameValidation.Valid
            assertEquals(result.normalized, NameNormalizer.normalizeForKey(input))
        }
    }

    @Test
    fun exactlyThirtyCodePointsWithCombiningMarksIsValid() {
        val thirty = "कि".repeat(15) // 30 code points, no spaces
        assertTrue(NameNormalizer.validate(thirty) is NameValidation.Valid)
        assertEquals(
            NameValidation.Invalid(NameInvalidReason.TOO_LONG),
            NameNormalizer.validate(thirty + "क"),
        )
    }

    @Test
    fun lengthIsCheckedAfterCleaning() {
        // 30 letters plus emoji and padding: emoji and spaces do not count
        val input = "  " + "a".repeat(30) + " 😀 "
        assertTrue(NameNormalizer.validate(input) is NameValidation.Valid)
    }

    @Test
    fun wordCountIsCheckedAfterCollapsingSpaces() {
        assertTrue(NameNormalizer.validate("Ayush    Kumar    Singh") is NameValidation.Valid)
        assertEquals(
            NameValidation.Invalid(NameInvalidReason.TOO_MANY_WORDS),
            NameNormalizer.validate("a b c d"),
        )
    }

    @Test
    fun rejectionOrderLengthBeforeWordsBeforeChars() {
        // 31 code points and 4 words and digits -> TOO_LONG wins
        assertEquals(
            NameValidation.Invalid(NameInvalidReason.TOO_LONG),
            NameNormalizer.validate("1234567 1234567 1234567 12345678"),
        )
        // 4 words with digits -> TOO_MANY_WORDS wins over INVALID_CHARS
        assertEquals(
            NameValidation.Invalid(NameInvalidReason.TOO_MANY_WORDS),
            NameNormalizer.validate("1 2 3 4"),
        )
        // invalid char and mixed script -> INVALID_CHARS wins
        assertEquals(
            NameValidation.Invalid(NameInvalidReason.INVALID_CHARS),
            NameNormalizer.validate("Ayush राम!"),
        )
    }

    @Test
    fun punctuationDoesNotCountAsScript() {
        assertTrue(NameNormalizer.validate("राम-लक्ष्मण") is NameValidation.Valid)
        assertTrue(NameNormalizer.validate("श्री. राम") is NameValidation.Valid)
    }

    @Test
    fun nameMustContainAtLeastOneLetter() {
        for (input in listOf("...", "-", "'", "’", ". - '", "́")) {
            assertEquals(
                "<$input>",
                NameValidation.Invalid(NameInvalidReason.INVALID_CHARS),
                NameNormalizer.validate(input),
            )
        }
    }

    @Test
    fun combiningMarkAloneIsNotMixedScript() {
        // Devanagari letters plus an inherited combining mark stay a single script
        assertTrue(NameNormalizer.validate("राम́") is NameValidation.Valid)
    }

    private companion object {
        const val FIXTURE = "name_normalization_cases.json"
        const val BACKEND_FIXTURE = "supabase/functions/tests/fixtures/name_normalization_cases.json"
    }
}

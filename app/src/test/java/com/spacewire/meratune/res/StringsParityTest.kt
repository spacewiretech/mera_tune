package com.spacewire.meratune.res

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the create-ringtone flow strings across all 9 `values*` folders.
 *
 * Expected to FAIL until the UI work for the personalized-ringtone flow (plan section B7) lands:
 * every `create_form_*`, `song_choice_*`, `processing_*` and `ready_*` key must exist in the default
 * `values/strings.xml` and in each locale folder with matching `%1$s` / `%2$s` placeholders.
 */
class StringsParityTest {

    private val prefixes = listOf("create_form_", "song_choice_", "processing_", "ready_")
    private val locales = listOf("bn", "hi", "kn", "ml", "mr", "or", "ta", "te")
    private val placeholder = Regex("%(\\d+)\\$[sd]")

    /** New keys from plan B7 that the default file must define. */
    private val requiredDefaultKeys = listOf(
        "create_form_title_generic", "create_form_subtitle", "create_form_name_too_long",
        "create_form_name_invalid", "create_form_language_hint", "create_form_language_coming_soon",
        "song_choice_title", "song_choice_subtitle", "song_choice_filter_all", "song_choice_sample",
        "song_choice_replaces", "song_choice_cta", "song_choice_cta_disabled", "song_choice_empty_title",
        "song_choice_empty_message", "song_choice_empty_hindi_cta", "song_choice_empty_change_language",
        "song_choice_fallback_banner", "song_choice_selected_a11y",
        "processing_subtitle", "processing_subtitle_highlight", "processing_step_preparing",
        "processing_step_composing", "processing_song_line", "processing_tip_1", "processing_tip_2",
        "processing_tip_3", "processing_taking_longer", "processing_waiting", "processing_error_title",
        "processing_error_generic", "processing_error_network", "processing_error_timeout",
        "processing_error_language_unsupported", "processing_error_quota", "processing_error_quota_legacy_hint",
        "processing_error_song_unavailable", "processing_error_name_too_long", "processing_error_name_rejected",
        "processing_error_session", "processing_error_subscription", "processing_retry", "processing_login_again",
        "processing_change_language", "processing_choose_another", "processing_cancel_toast",
        "ready_based_on", "ready_default_title", "ready_change_song", "ready_make_another", "ready_go_home",
    )

    /** Keys plan B7 removes from every folder (voice/category sections are gone from the form). */
    private val removedKeys = listOf(
        "create_form_choose_voice",
        "create_form_choose_category",
        "create_form_category_romantic",
        "create_form_category_devotional",
        "create_form_category_family",
        "create_form_category_cinematic",
        "create_form_continue_toast",
    )

    private val resDir: File by lazy {
        val candidates = listOf(
            File("src/main/res"),
            File("app/src/main/res"),
            File(System.getProperty("user.dir"), "src/main/res"),
            File(System.getProperty("user.dir"), "app/src/main/res"),
        )
        candidates.firstOrNull { File(it, "values/strings.xml").isFile }
            ?: error("Could not locate app/src/main/res from ${System.getProperty("user.dir")}")
    }

    private fun readStrings(folder: String): Map<String, String> {
        val file = File(resDir, "$folder/strings.xml")
        assertTrue("missing $file", file.isFile)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        val result = LinkedHashMap<String, String>()
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as Element
            result[element.getAttribute("name")] = element.textContent
        }
        return result
    }

    private fun flowKeys(strings: Map<String, String>): List<String> =
        strings.keys.filter { key -> prefixes.any { key.startsWith(it) } }

    private fun placeholders(value: String): List<String> =
        placeholder.findAll(value).map { it.value }.sorted().toList()

    @Test
    fun everyFlowKeyExistsInAllLocales() {
        val defaults = readStrings("values")
        val keys = flowKeys(defaults)
        assertTrue("no create-flow keys found in values/strings.xml", keys.isNotEmpty())

        val failures = mutableListOf<String>()
        for (locale in locales) {
            val localized = readStrings("values-$locale")
            val missing = keys.filter { it !in localized }
            if (missing.isNotEmpty()) {
                failures += "values-$locale/strings.xml is missing ${missing.size} key(s): $missing"
            }
        }
        if (failures.isNotEmpty()) {
            fail(
                "Create-flow strings are not in every locale yet (plan B7 adds each new key to all 9 folders):\n" +
                    failures.joinToString("\n"),
            )
        }
    }

    @Test
    fun placeholderCountsMatchDefault() {
        val defaults = readStrings("values")
        val failures = mutableListOf<String>()
        for (locale in locales) {
            val localized = readStrings("values-$locale")
            for (key in flowKeys(defaults)) {
                val translated = localized[key] ?: continue
                val expected = placeholders(defaults.getValue(key))
                val actual = placeholders(translated)
                if (expected != actual) {
                    failures += "values-$locale/$key: expected placeholders $expected but found $actual"
                }
            }
        }
        if (failures.isNotEmpty()) {
            fail("Placeholder mismatch (%1\$s / %2\$s must match the default string):\n" + failures.joinToString("\n"))
        }
    }

    @Test
    fun requiredCreateFlowKeysExistInDefault() {
        val defaults = readStrings("values")
        val missing = requiredDefaultKeys.filter { it !in defaults }
        if (missing.isNotEmpty()) {
            fail(
                "values/strings.xml is missing ${missing.size} key(s) required by the personalized-ringtone flow " +
                    "(plan B7, added by the UI work): $missing",
            )
        }
    }

    @Test
    fun removedFormKeysAreGoneEverywhere() {
        val failures = mutableListOf<String>()
        for (folder in listOf("values") + locales.map { "values-$it" }) {
            val present = removedKeys.filter { it in readStrings(folder) }
            if (present.isNotEmpty()) {
                failures += "$folder/strings.xml still defines $present"
            }
        }
        if (failures.isNotEmpty()) {
            fail("Voice/category form strings should be removed in all 9 folders (plan B7):\n" + failures.joinToString("\n"))
        }
    }
}

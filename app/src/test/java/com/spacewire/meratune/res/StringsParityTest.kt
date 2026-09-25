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
 *
 * UI refresh: the shared `cta_*` keys (P1) and the onboarding `language_*` / `auth_*` keys (P2) are
 * guarded the same way. Keys marked `translatable="false"` in the default file (the English
 * `language_subtitle_*` names) are skipped by the parity checks and must not appear in any locale.
 *
 * UI refresh P6/P7: the paywall / payment-state `paywall_*` keys and the member-screen `member_*`
 * keys are guarded too.
 */
class StringsParityTest {

    private val prefixes = listOf(
        "create_form_", "song_choice_", "processing_", "ready_", "cta_", "language_", "auth_",
        "paywall_", "member_",
        // UI refresh P9 (lane C): Home and Profile.
        "home_", "empty_search_", "profile_",
    )
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
        "cta_loading",
        // UI refresh P2: language screen and onboarding auth.
        "language_continue", "auth_phone_headline", "auth_phone_headline_highlight", "auth_phone_helper",
        "auth_phone_cta", "auth_country_india", "auth_otp_headline", "auth_otp_headline_highlight",
        "auth_otp_verify", "auth_name_headline", "auth_name_headline_highlight",
        // UI refresh P9 (lane C): Home empty-search title and row play button.
        "empty_search_title", "empty_search_title_highlight", "home_pause_tune",
    )

    /**
     * Keys removed from every folder: plan B7's voice/category form sections, and (UI refresh P2) the
     * old auth titles/subtitles, the emoji country code, the OTP edit link and countdown label, and
     * the language-screen `logo_tune` wordmark. P9 (lane C) adds the Home/Profile prefixes.
     */
    private val removedKeys = listOf(
        "create_form_choose_voice",
        "create_form_choose_category",
        "create_form_category_romantic",
        "create_form_category_devotional",
        "create_form_category_family",
        "create_form_category_cinematic",
        "create_form_continue_toast",
        "auth_phone_title", "auth_phone_subtitle", "auth_country_code", "auth_next",
        "auth_otp_title", "auth_otp_subtitle", "auth_otp_edit_phone", "auth_otp_resend_in",
        "auth_name_title", "auth_name_subtitle",
        "logo_tune",
        // UI refresh P6: the old paywall header, rating, FREE badge, feature rows and pending toast.
        "auth_speaker", "auth_language",
        "subscription_title_prefix", "subscription_free_badge", "subscription_rating",
        "subscription_feature_auth", "subscription_feature_trial", "subscription_feature_autopay",
        "subscription_pending",
        // UI refresh P9 (lane C): two-line empty-search title and the Home header's old wordmark text.
        "empty_search_title_line1", "empty_search_title_line2", "logo_mera",
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

    private fun stringElements(folder: String): List<Element> {
        val file = File(resDir, "$folder/strings.xml")
        assertTrue("missing $file", file.isFile)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun readStrings(folder: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (element in stringElements(folder)) {
            result[element.getAttribute("name")] = element.textContent
        }
        return result
    }

    /** Default keys marked `translatable="false"`: they must exist only in `values/`. */
    private val nonTranslatableKeys: Set<String> by lazy {
        stringElements("values")
            .filter { it.getAttribute("translatable") == "false" }
            .map { it.getAttribute("name") }
            .toSet()
    }

    private fun flowKeys(strings: Map<String, String>): List<String> =
        strings.keys.filter { key -> key !in nonTranslatableKeys && prefixes.any { key.startsWith(it) } }

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
            fail("Removed strings (plan B7, UI refresh) must be gone from all 9 folders:\n" + failures.joinToString("\n"))
        }
    }

    @Test
    fun nonTranslatableKeysAreNotTranslated() {
        assertTrue(
            "expected the language_subtitle_* keys to be translatable=\"false\" in values/strings.xml",
            nonTranslatableKeys.any { it.startsWith("language_subtitle_") },
        )
        val failures = mutableListOf<String>()
        for (locale in locales) {
            val present = nonTranslatableKeys.filter { it in readStrings("values-$locale") }
            if (present.isNotEmpty()) {
                failures += "values-$locale/strings.xml defines non-translatable key(s) $present"
            }
        }
        if (failures.isNotEmpty()) {
            fail(
                "Keys marked translatable=\"false\" in values/strings.xml must not be redefined in a locale " +
                    "(for example language_subtitle_* stay English in every app language):\n" + failures.joinToString("\n"),
            )
        }
    }
}

package com.spacewire.meratune.data

import com.spacewire.meratune.R

data class LanguageDefinition(
    val nativeLabelRes: Int,
    val englishLabelRes: Int,
    val storageValue: String,
    val localeCode: String,
)

object Languages {
    val all = listOf(
        LanguageDefinition(
            nativeLabelRes = R.string.create_form_language_english,
            englishLabelRes = R.string.language_subtitle_english,
            storageValue = "English",
            localeCode = "en",
        ),
        LanguageDefinition(
            nativeLabelRes = R.string.create_form_language_hindi,
            englishLabelRes = R.string.language_subtitle_hindi,
            storageValue = "Hindi",
            localeCode = "hi",
        ),
        LanguageDefinition(
            nativeLabelRes = R.string.create_form_language_telugu,
            englishLabelRes = R.string.language_subtitle_telugu,
            storageValue = "Telugu",
            localeCode = "te",
        ),
        LanguageDefinition(
            nativeLabelRes = R.string.create_form_language_tamil,
            englishLabelRes = R.string.language_subtitle_tamil,
            storageValue = "Tamil",
            localeCode = "ta",
        ),
        LanguageDefinition(
            nativeLabelRes = R.string.create_form_language_kannada,
            englishLabelRes = R.string.language_subtitle_kannada,
            storageValue = "Kannada",
            localeCode = "kn",
        ),
        LanguageDefinition(
            nativeLabelRes = R.string.create_form_language_malayalam,
            englishLabelRes = R.string.language_subtitle_malayalam,
            storageValue = "Malayalam",
            localeCode = "ml",
        ),
        LanguageDefinition(
            nativeLabelRes = R.string.create_form_language_marathi,
            englishLabelRes = R.string.language_subtitle_marathi,
            storageValue = "Marathi",
            localeCode = "mr",
        ),
        LanguageDefinition(
            nativeLabelRes = R.string.create_form_language_odia,
            englishLabelRes = R.string.language_subtitle_odia,
            storageValue = "Odia",
            localeCode = "or",
        ),
        LanguageDefinition(
            nativeLabelRes = R.string.create_form_language_bengali,
            englishLabelRes = R.string.language_subtitle_bengali,
            storageValue = "Bengali",
            localeCode = "bn",
        ),
    )

    fun localeCodeForStorageValue(storageValue: String): String =
        all.firstOrNull { it.storageValue.equals(storageValue.trim(), ignoreCase = true) }?.localeCode ?: "en"

    fun indexForLocaleCode(localeCode: String): Int =
        all.indexOfFirst { it.localeCode == localeCode }.takeIf { it >= 0 } ?: 0
}

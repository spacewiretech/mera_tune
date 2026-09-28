package com.spacewire.meratune

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.spacewire.meratune.analytics.AnalyticsScreen
import com.spacewire.meratune.analytics.CreationEntryPoint
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.data.HomeRepository
import com.spacewire.meratune.data.LanguageDefinition
import com.spacewire.meratune.data.Languages
import com.spacewire.meratune.ui.FormOptionGroup
import com.spacewire.meratune.util.GradientTextHelper
import com.spacewire.meratune.util.InsetsUi
import com.spacewire.meratune.util.NameInvalidReason
import com.spacewire.meratune.util.NameNormalizer
import com.spacewire.meratune.util.NameValidation
import com.spacewire.meratune.util.ProfileStore
import com.spacewire.meratune.util.enableLightEdgeToEdge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Step 1 of the personalized-ringtone flow: name + language. */
class CreateRingtoneActivity : AppCompatActivity() {

    private lateinit var languageGroup: FormOptionGroup
    private lateinit var languageDefinitions: List<LanguageDefinition>
    private lateinit var nameInput: EditText
    private lateinit var nameError: TextView
    private lateinit var formTitle: TextView
    private lateinit var continueButton: TextView
    private lateinit var comingSoonNote: TextView

    /** Storage language value -> TTS enabled. `null` until loaded (or on failure = all enabled). */
    private var languageAvailability: Map<String, Boolean>? = null
    private var userPickedLanguage = false
    private var availabilityJob: Job? = null

    // Analytics for one form visit (onCreate, or a CLEAR_TOP re-entry through onNewIntent).
    private var formShownAtMs = 0L
    private var prefillSource = PREFILL_NONE
    private var prefillDisplay = ""
    private var entryPoint: String? = null
    private val reportedUnavailableLanguages = mutableSetOf<String>()
    private var isNavigating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreAnalyticsState(savedInstanceState)
        enableLightEdgeToEdge()
        setContentView(R.layout.activity_create_ringtone)

        nameInput = findViewById(R.id.nameInput)
        nameError = findViewById(R.id.nameError)
        formTitle = findViewById(R.id.formTitle)
        continueButton = findViewById(R.id.continueButton)
        comingSoonNote = findViewById(R.id.languageComingSoonNote)

        // The CTA lives in the scroll content; the keyboard raises the bottom padding so it stays reachable.
        InsetsUi.padForSystemBarsAndIme(findViewById(R.id.formScroll))

        applyGradientLabels()
        setupLanguageGroup()
        setupNameInput(savedInstanceState)
        setupActions()

        if (savedInstanceState != null) {
            userPickedLanguage = savedInstanceState.getBoolean(STATE_USER_PICKED_LANGUAGE, false)
            savedInstanceState.getString(STATE_LANGUAGE)?.let { languageGroup.selectKey(it) }
        } else {
            languageGroup.selectKey(defaultLanguageKey())
        }

        loadLanguageAvailability()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // CLEAR_TOP re-entry skips onCreate, so the lifecycle tracker does not see this view.
        mixpanelAnalytics().trackScreenViewed(AnalyticsScreen.CREATE_FORM)
        val retained = NameNormalizer.display(nameInput.text?.toString().orEmpty())
        startFormVisit(if (retained.isEmpty()) PREFILL_NONE else PREFILL_RETAINED, retained, intent)
        // Returning from later steps (e.g. UNSUPPORTED_LANGUAGE): the server gate may have changed.
        loadLanguageAvailability()
    }

    override fun onResume() {
        super.onResume()
        isNavigating = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_LANGUAGE, languageGroup.selectedKey())
        outState.putBoolean(STATE_USER_PICKED_LANGUAGE, userPickedLanguage)
        outState.putLong(STATE_FORM_SHOWN_AT_MS, formShownAtMs)
        outState.putString(STATE_PREFILL_SOURCE, prefillSource)
        outState.putString(STATE_PREFILL_DISPLAY, prefillDisplay)
        outState.putString(STATE_ENTRY_POINT, entryPoint)
        outState.putStringArrayList(STATE_REPORTED_UNAVAILABLE, ArrayList(reportedUnavailableLanguages))
    }

    private fun restoreAnalyticsState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        formShownAtMs = savedInstanceState.getLong(STATE_FORM_SHOWN_AT_MS, SystemClock.elapsedRealtime())
        prefillSource = savedInstanceState.getString(STATE_PREFILL_SOURCE) ?: PREFILL_NONE
        prefillDisplay = savedInstanceState.getString(STATE_PREFILL_DISPLAY).orEmpty()
        entryPoint = savedInstanceState.getString(STATE_ENTRY_POINT)
        savedInstanceState.getStringArrayList(STATE_REPORTED_UNAVAILABLE)?.let(reportedUnavailableLanguages::addAll)
    }

    private fun startFormVisit(source: String, prefill: String, launchIntent: Intent) {
        formShownAtMs = SystemClock.elapsedRealtime()
        prefillSource = source
        prefillDisplay = prefill
        entryPoint = launchIntent.getStringExtra(EXTRA_ENTRY_POINT)
        reportedUnavailableLanguages.clear()
    }

    private fun applyGradientLabels() {
        listOf(R.id.nameSectionLabel, R.id.languageSectionLabel).forEach { labelId ->
            GradientTextHelper.applyHorizontalGradient(
                findViewById(labelId),
                R.color.gradient_pink,
                R.color.gradient_orange,
            )
        }
    }

    private fun setupLanguageGroup() {
        languageDefinitions = Languages.all.filter { it.storageValue in LANGUAGE_OPTION_IDS }
        val optionViews = languageDefinitions.map { definition ->
            findViewById<View>(LANGUAGE_OPTION_IDS.getValue(definition.storageValue)).also { option ->
                option.findViewById<TextView>(R.id.optionLabel).setText(definition.nativeLabelRes)
            }
        }
        languageGroup = FormOptionGroup(
            optionViews = optionViews,
            keys = languageDefinitions.map { it.storageValue },
            onSelectionChanged = { userPickedLanguage = true },
            onDisabledOptionClick = ::onUnavailableLanguageTapped,
        )
    }

    private fun onUnavailableLanguageTapped(language: String) {
        if (reportedUnavailableLanguages.add(language)) {
            mixpanelAnalytics().trackUnavailableLanguageTapped(language)
        }
    }

    private fun setupNameInput(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            val extraName = intent.getStringExtra(EXTRA_NAME).orEmpty().trim()
            val prefill = extraName.ifBlank { ProfileStore(this).getProfile().name.trim() }
            nameInput.setText(prefill)
            nameInput.setSelection(nameInput.text.length)
            // The Home "{name} Tunes" chip passes the profile first name, not a typed query.
            val fromNameChip = intent.getStringExtra(EXTRA_ENTRY_POINT) == CreationEntryPoint.MY_NAME_CHIP
            val source = when {
                extraName.isNotEmpty() && fromNameChip -> PREFILL_PROFILE_NAME
                extraName.isNotEmpty() -> PREFILL_SEARCH_QUERY
                prefill.isNotEmpty() -> PREFILL_PROFILE_NAME
                else -> PREFILL_NONE
            }
            startFormVisit(source, NameNormalizer.display(prefill), intent)
        }
        nameInput.doAfterTextChanged { renderName() }
        renderName()
    }

    private fun setupActions() {
        findViewById<ImageView>(R.id.backButton).setOnClickListener { finish() }
        continueButton.setOnClickListener { onContinue() }
    }

    private fun renderName() {
        val raw = nameInput.text?.toString().orEmpty()
        val validation = NameNormalizer.validate(raw)
        val display = NameNormalizer.display(raw)

        formTitle.text = if (display.isBlank()) {
            getString(R.string.create_form_title_generic)
        } else {
            getString(R.string.create_form_title, display)
        }

        val errorRes = when (validation) {
            is NameValidation.Valid -> null
            is NameValidation.Invalid -> when (validation.reason) {
                NameInvalidReason.EMPTY -> null
                NameInvalidReason.TOO_LONG -> R.string.create_form_name_too_long
                NameInvalidReason.TOO_MANY_WORDS,
                NameInvalidReason.INVALID_CHARS,
                NameInvalidReason.MIXED_SCRIPT,
                -> R.string.create_form_name_invalid
            }
        }
        if (errorRes != null) {
            nameError.setText(errorRes)
            nameError.visibility = View.VISIBLE
        } else {
            nameError.visibility = View.GONE
        }

        val canContinue = validation is NameValidation.Valid
        continueButton.isEnabled = canContinue
    }

    private fun onContinue() {
        if (isNavigating) return
        val validation = NameNormalizer.validate(nameInput.text?.toString().orEmpty())
        if (validation !is NameValidation.Valid) {
            renderName()
            return
        }
        val languageKey = languageGroup.selectedKey()
        if (!languageGroup.isEnabled(languageKey)) return

        isNavigating = true
        mixpanelAnalytics().trackRingtoneCreationStarted(
            language = languageKey,
            nameLength = validation.display.length,
            languageSource = languageSource(languageKey),
            prefillSource = prefillSource,
            nameEdited = validation.display != prefillDisplay,
            timeOnFormMs = (SystemClock.elapsedRealtime() - formShownAtMs).coerceAtLeast(0L),
            entryPoint = entryPoint,
        )
        startActivity(ChooseSongActivity.intent(this, validation.display, languageKey))
    }

    /** Which [defaultLanguageKey] branch produced [selected], unless the user tapped a language. */
    private fun languageSource(selected: String): String {
        if (userPickedLanguage) return LANGUAGE_USER_PICKED
        val profileLanguage = ProfileStore(this).getProfile().selectedLanguage.trim()
        return when {
            selected.equals(profileLanguage, ignoreCase = true) -> LANGUAGE_PROFILE_DEFAULT
            selected == HINDI -> LANGUAGE_HINDI_DEFAULT
            else -> LANGUAGE_FIRST_ENABLED
        }
    }

    private fun loadLanguageAvailability() {
        availabilityJob?.cancel()
        availabilityJob = lifecycleScope.launch {
            val availability = try {
                HomeRepository().fetchGenerationLanguages()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // The server enforces the same table; a failed read must not block the form.
                Log.w(TAG, "generation_languages unavailable: ${error.javaClass.simpleName}")
                null
            }
            applyLanguageAvailability(availability)
        }
    }

    private fun applyLanguageAvailability(availability: Map<String, Boolean>?) {
        val normalized = availability
            ?.takeIf { it.isNotEmpty() }
            ?.mapKeys { (language, _) -> language.trim().lowercase() }
        languageAvailability = normalized

        languageDefinitions.forEach { definition ->
            languageGroup.setEnabled(definition.storageValue, isLanguageEnabled(definition.storageValue))
        }

        val selected = languageGroup.selectedKey()
        if (!userPickedLanguage || !languageGroup.isEnabled(selected)) {
            val fallback = defaultLanguageKey()
            if (fallback != selected) languageGroup.selectKey(fallback)
        }
        renderComingSoonNote()
    }

    private fun isLanguageEnabled(storageValue: String): Boolean {
        val availability = languageAvailability ?: return true
        return availability[storageValue.lowercase()] == true
    }

    /** Profile language when TTS supports it, else Hindi, else the first enabled language. */
    private fun defaultLanguageKey(): String {
        val profileLanguage = ProfileStore(this).getProfile().selectedLanguage
        val keys = languageDefinitions.map { it.storageValue }
        val profileKey = keys.firstOrNull { it.equals(profileLanguage.trim(), ignoreCase = true) }
        return when {
            profileKey != null && isLanguageEnabled(profileKey) -> profileKey
            isLanguageEnabled(HINDI) -> HINDI
            else -> keys.firstOrNull { isLanguageEnabled(it) } ?: HINDI
        }
    }

    private fun renderComingSoonNote() {
        val profileLanguage = ProfileStore(this).getProfile().selectedLanguage.trim()
        val disabled = languageDefinitions
            .filterNot { isLanguageEnabled(it.storageValue) }
            .sortedByDescending { it.storageValue.equals(profileLanguage, ignoreCase = true) }
        if (disabled.isEmpty()) {
            comingSoonNote.visibility = View.GONE
            return
        }
        val labels = disabled.joinToString(", ") { getString(it.nativeLabelRes) }
        comingSoonNote.text = getString(R.string.create_form_language_coming_soon, labels)
        comingSoonNote.visibility = View.VISIBLE
    }

    companion object {
        private const val TAG = "CreateRingtone"
        private const val EXTRA_NAME = "extra_name"
        private const val EXTRA_ENTRY_POINT = "extra_entry_point"
        private const val STATE_LANGUAGE = "state_language"
        private const val STATE_USER_PICKED_LANGUAGE = "state_user_picked_language"
        private const val STATE_FORM_SHOWN_AT_MS = "state_form_shown_at_ms"
        private const val STATE_PREFILL_SOURCE = "state_prefill_source"
        private const val STATE_PREFILL_DISPLAY = "state_prefill_display"
        private const val STATE_ENTRY_POINT = "state_entry_point"
        private const val STATE_REPORTED_UNAVAILABLE = "state_reported_unavailable_languages"
        private const val HINDI = "Hindi"

        // `prefill_source`: where the name field's starting text came from.
        private const val PREFILL_SEARCH_QUERY = "search_query"
        private const val PREFILL_PROFILE_NAME = "profile_name"
        private const val PREFILL_RETAINED = "retained"
        private const val PREFILL_NONE = "none"

        // `language_source`
        private const val LANGUAGE_USER_PICKED = "user_picked"
        private const val LANGUAGE_PROFILE_DEFAULT = "profile_default"
        private const val LANGUAGE_HINDI_DEFAULT = "hindi_default"
        private const val LANGUAGE_FIRST_ENABLED = "first_enabled"

        private val LANGUAGE_OPTION_IDS = mapOf(
            "English" to R.id.languageEnglishOption,
            "Hindi" to R.id.languageHindiOption,
            "Telugu" to R.id.languageTeluguOption,
            "Tamil" to R.id.languageTamilOption,
            "Kannada" to R.id.languageKannadaOption,
            "Malayalam" to R.id.languageMalayalamOption,
            "Marathi" to R.id.languageMarathiOption,
            "Odia" to R.id.languageOdiaOption,
            "Bengali" to R.id.languageBengaliOption,
        )

        /** @param entryPoint a `CreationEntryPoint` value for `ringtone_creation_started`. */
        fun intent(context: Context, name: String, entryPoint: String? = null): Intent {
            return Intent(context, CreateRingtoneActivity::class.java)
                .putExtra(EXTRA_NAME, name)
                .apply { entryPoint?.let { putExtra(EXTRA_ENTRY_POINT, it) } }
        }
    }
}

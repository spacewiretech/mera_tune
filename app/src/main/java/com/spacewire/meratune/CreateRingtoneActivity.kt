package com.spacewire.meratune

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.data.HomeRepository
import com.spacewire.meratune.data.LanguageDefinition
import com.spacewire.meratune.data.Languages
import com.spacewire.meratune.ui.FormOptionGroup
import com.spacewire.meratune.util.GradientTextHelper
import com.spacewire.meratune.util.NameInvalidReason
import com.spacewire.meratune.util.NameNormalizer
import com.spacewire.meratune.util.NameValidation
import com.spacewire.meratune.util.ProfileStore
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_create_ringtone)

        nameInput = findViewById(R.id.nameInput)
        nameError = findViewById(R.id.nameError)
        formTitle = findViewById(R.id.formTitle)
        continueButton = findViewById(R.id.continueButton)
        comingSoonNote = findViewById(R.id.languageComingSoonNote)

        val ctaBottomMargin = (continueButton.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.formScroll)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, 0)
            continueButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = ctaBottomMargin + systemBars.bottom
            }
            insets
        }

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
        // Returning from later steps (e.g. UNSUPPORTED_LANGUAGE): the server gate may have changed.
        loadLanguageAvailability()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_LANGUAGE, languageGroup.selectedKey())
        outState.putBoolean(STATE_USER_PICKED_LANGUAGE, userPickedLanguage)
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
        )
    }

    private fun setupNameInput(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            val prefill = intent.getStringExtra(EXTRA_NAME).orEmpty().trim()
                .ifBlank { ProfileStore(this).getProfile().name.trim() }
            nameInput.setText(prefill)
            nameInput.setSelection(nameInput.text.length)
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
        continueButton.alpha = if (canContinue) 1f else DISABLED_ALPHA
    }

    private fun onContinue() {
        val validation = NameNormalizer.validate(nameInput.text?.toString().orEmpty())
        if (validation !is NameValidation.Valid) {
            renderName()
            return
        }
        val languageKey = languageGroup.selectedKey()
        if (!languageGroup.isEnabled(languageKey)) return

        mixpanelAnalytics().trackRingtoneCreationStarted(
            language = languageKey,
            nameLength = validation.display.length,
        )
        startActivity(ChooseSongActivity.intent(this, validation.display, languageKey))
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
        private const val STATE_LANGUAGE = "state_language"
        private const val STATE_USER_PICKED_LANGUAGE = "state_user_picked_language"
        private const val HINDI = "Hindi"
        private const val DISABLED_ALPHA = 0.45f

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

        fun intent(context: Context, name: String): Intent {
            return Intent(context, CreateRingtoneActivity::class.java)
                .putExtra(EXTRA_NAME, name)
        }
    }
}

package com.spacewire.meratune

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.ui.FormOptionGroup
import com.spacewire.meratune.util.GradientTextHelper

class CreateRingtoneActivity : AppCompatActivity() {

    private lateinit var voiceGroup: FormOptionGroup
    private lateinit var categoryGroup: FormOptionGroup
    private lateinit var languageGroup: FormOptionGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_create_ringtone)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.formScroll)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, 0)
            insets
        }

        val name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Ram" }
        findViewById<TextView>(R.id.formTitle).text =
            getString(R.string.create_form_title, name)
        findViewById<EditText>(R.id.nameInput).setText(name)

        applyGradientLabels()
        setupOptionGroups()
        setupActions()
    }

    private fun applyGradientLabels() {
        listOf(
            R.id.nameSectionLabel,
            R.id.voiceSectionLabel,
            R.id.categorySectionLabel,
            R.id.languageSectionLabel,
        ).forEach { labelId ->
            GradientTextHelper.applyHorizontalGradient(
                findViewById(labelId),
                R.color.gradient_pink,
                R.color.gradient_orange,
            )
        }
    }

    private fun setupOptionGroups() {
        setOptionLabel(R.id.voiceMaleOption, getString(R.string.create_form_voice_male))
        setOptionLabel(R.id.voiceFemaleOption, getString(R.string.create_form_voice_female))

        setOptionLabel(R.id.categoryRomanticOption, getString(R.string.create_form_category_romantic))
        setOptionLabel(R.id.categoryDevotionalOption, getString(R.string.create_form_category_devotional))
        setOptionLabel(R.id.categoryFamilyOption, getString(R.string.create_form_category_family))
        setOptionLabel(R.id.categoryCinematicOption, getString(R.string.create_form_category_cinematic))

        setOptionLabel(R.id.languageEnglishOption, getString(R.string.create_form_language_english))
        setOptionLabel(R.id.languageHindiOption, getString(R.string.create_form_language_hindi))
        setOptionLabel(R.id.languageTeluguOption, getString(R.string.create_form_language_telugu))
        setOptionLabel(R.id.languageTamilOption, getString(R.string.create_form_language_tamil))
        setOptionLabel(R.id.languageKannadaOption, getString(R.string.create_form_language_kannada))
        setOptionLabel(R.id.languageMalayalamOption, getString(R.string.create_form_language_malayalam))
        setOptionLabel(R.id.languageMarathiOption, getString(R.string.create_form_language_marathi))
        setOptionLabel(R.id.languageOdiaOption, getString(R.string.create_form_language_odia))
        setOptionLabel(R.id.languageBengaliOption, getString(R.string.create_form_language_bengali))

        voiceGroup = FormOptionGroup(
            listOf(
                findViewById(R.id.voiceMaleOption),
                findViewById(R.id.voiceFemaleOption),
            ),
        )

        categoryGroup = FormOptionGroup(
            listOf(
                findViewById(R.id.categoryRomanticOption),
                findViewById(R.id.categoryDevotionalOption),
                findViewById(R.id.categoryFamilyOption),
                findViewById(R.id.categoryCinematicOption),
            ),
        )

        languageGroup = FormOptionGroup(
            listOf(
                findViewById(R.id.languageEnglishOption),
                findViewById(R.id.languageHindiOption),
                findViewById(R.id.languageTeluguOption),
                findViewById(R.id.languageTamilOption),
                findViewById(R.id.languageKannadaOption),
                findViewById(R.id.languageMalayalamOption),
                findViewById(R.id.languageMarathiOption),
                findViewById(R.id.languageOdiaOption),
                findViewById(R.id.languageBengaliOption),
            ),
        )
    }

    private fun setupActions() {
        findViewById<ImageView>(R.id.backButton).setOnClickListener { finish() }

        findViewById<TextView>(R.id.continueButton).setOnClickListener {
            val enteredName = findViewById<EditText>(R.id.nameInput).text.toString().trim()
            if (enteredName.isBlank()) {
                Toast.makeText(this, R.string.create_form_name_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val voice = voiceGroup.selectedValue()
            val category = categoryGroup.selectedValue()
            val language = languageGroup.selectedValue()

            mixpanelAnalytics().trackRingtoneCreationStarted(
                voice = voice,
                category = category,
                language = language,
            )

            startActivity(
                RingtoneProcessingActivity.intent(
                    context = this,
                    name = enteredName,
                    voice = voice,
                    category = category,
                    language = language,
                ),
            )
            finish()
        }
    }

    private fun setOptionLabel(optionRootId: Int, label: String) {
        findViewById<View>(optionRootId).findViewById<TextView>(R.id.optionLabel).text = label
    }

    companion object {
        private const val EXTRA_NAME = "extra_name"

        fun intent(context: Context, name: String): Intent {
            return Intent(context, CreateRingtoneActivity::class.java)
                .putExtra(EXTRA_NAME, name)
        }
    }
}

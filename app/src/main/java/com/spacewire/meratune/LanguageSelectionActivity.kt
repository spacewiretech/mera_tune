package com.spacewire.meratune

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.data.Languages
import com.spacewire.meratune.ui.LanguageOptionGroup
import com.spacewire.meratune.util.LocaleHelper
import com.spacewire.meratune.util.ProfileStore
import com.spacewire.meratune.util.enableLightEdgeToEdge

class LanguageSelectionActivity : AppCompatActivity() {

    private lateinit var languageGroup: LanguageOptionGroup

    /** applyLocale recreates asynchronously, so a second tap could otherwise run the flow twice. */
    private var continueHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableLightEdgeToEdge()
        setContentView(R.layout.activity_language_selection)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.languageSelectionRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom,
            )
            insets
        }

        setupLanguageOptions(savedInstanceState?.getInt(STATE_SELECTED_INDEX, -1) ?: -1)
        setupContinueAction()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED_INDEX, languageGroup.selectedIndex())
    }

    /** [restoredIndex] is the card picked before a configuration change, or -1. */
    private fun setupLanguageOptions(restoredIndex: Int) {
        val listContainer = findViewById<LinearLayout>(R.id.languageListContainer)
        val profileStore = ProfileStore(this)

        val optionViews = Languages.all.map { language ->
            val optionView = layoutInflater.inflate(
                R.layout.item_language_option,
                listContainer,
                false,
            )
            optionView.findViewById<TextView>(R.id.nativeLabel).text =
                getString(language.nativeLabelRes)
            optionView.findViewById<TextView>(R.id.englishLabel).text =
                getString(language.englishLabelRes)

            val layoutParams = optionView.layoutParams as ViewGroup.MarginLayoutParams
            layoutParams.bottomMargin = resources.getDimensionPixelSize(R.dimen.language_option_spacing)
            optionView.layoutParams = layoutParams

            listContainer.addView(optionView)
            optionView
        }

        val initialIndex = restoredIndex.takeIf { it in Languages.all.indices }
            ?: Languages.indexForLocaleCode(profileStore.getLocaleCode())
        languageGroup = LanguageOptionGroup(optionViews, initialIndex)
    }

    private fun setupContinueAction() {
        findViewById<TextView>(R.id.continueButton).setOnClickListener {
            if (continueHandled) return@setOnClickListener
            continueHandled = true
            val selectedLanguage = Languages.all[languageGroup.selectedIndex()]
            val profileStore = ProfileStore(this)
            // Before the first choice getLocaleCode() is the "English" default, not a user choice.
            val hadChoice = profileStore.hasSelectedLanguage()
            val previousLocale = profileStore.getLocaleCode()
            val localeChanged = previousLocale != selectedLanguage.localeCode
            val previousLanguage = if (hadChoice) {
                Languages.all.firstOrNull { it.localeCode == previousLocale }?.storageValue
            } else {
                null
            }
            val isOnboarding = intent.getBooleanExtra(EXTRA_ONBOARDING, false)

            profileStore.saveSelectedLanguage(
                selectedLanguage.storageValue,
                selectedLanguage.localeCode,
            )
            LocaleHelper.applyLocale(this, selectedLanguage.localeCode)

            mixpanelAnalytics().trackLanguageSelected(
                language = selectedLanguage.storageValue,
                locale = selectedLanguage.localeCode,
                context = if (isOnboarding) "onboarding" else "settings",
                previousLanguage = previousLanguage,
                languageChanged = if (hadChoice) localeChanged else null,
            )

            setResult(RESULT_OK)

            when {
                isOnboarding -> {
                    startActivity(PhoneAuthActivity.intent(this))
                    finish()
                }
                localeChanged -> LocaleHelper.restartApp(this)
                else -> finish()
            }
        }
    }

    companion object {
        private const val EXTRA_ONBOARDING = "extra_onboarding"
        private const val STATE_SELECTED_INDEX = "state_selected_index"

        fun intent(context: Context, onboarding: Boolean = false): Intent =
            Intent(context, LanguageSelectionActivity::class.java)
                .putExtra(EXTRA_ONBOARDING, onboarding)
    }
}

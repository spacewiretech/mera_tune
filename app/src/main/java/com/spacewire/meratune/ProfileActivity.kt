package com.spacewire.meratune

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.spacewire.meratune.data.Languages
import com.spacewire.meratune.analytics.AnalyticsSource
import com.spacewire.meratune.analytics.ExternalLink
import com.spacewire.meratune.analytics.LogoutReason
import com.spacewire.meratune.analytics.firebaseAnalytics
import com.spacewire.meratune.analytics.mixpanelAnalytics
import com.spacewire.meratune.analytics.metaAnalytics
import com.spacewire.meratune.util.GradientTextHelper
import com.spacewire.meratune.util.ProfileStore
import com.spacewire.meratune.util.enableLightEdgeToEdge

class ProfileActivity : AppCompatActivity() {

    /** Double-tap guards: one logout, and one browser launch until the screen resumes again. */
    private var logoutHandled = false
    private var isNavigating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableLightEdgeToEdge()
        setContentView(R.layout.activity_profile)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.profileRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom,
            )
            insets
        }

        bindProfile()
        setupMenuItems()
        setupActions()
    }

    override fun onResume() {
        super.onResume()
        isNavigating = false
        refreshLanguageSubtitle()
    }

    private fun refreshLanguageSubtitle() {
        val profileStore = ProfileStore(this)
        val language = Languages.all.firstOrNull {
            it.storageValue.equals(profileStore.getProfile().selectedLanguage, ignoreCase = true)
        }
        val subtitle = language?.let { getString(it.nativeLabelRes) }
            ?: profileStore.getProfile().selectedLanguage
        findViewById<View>(R.id.languageMenuItem)
            .findViewById<TextView>(R.id.menuSubtitle)
            .text = subtitle
    }

    private fun bindProfile() {
        val profile = ProfileStore(this).getProfile()

        findViewById<TextView>(R.id.profileName).apply {
            text = profile.name
            visibility = if (profile.name.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.profilePhone).apply {
            text = profile.phone
            visibility = if (profile.phone.isBlank()) View.GONE else View.VISIBLE
        }
        val versionText = findViewById<TextView>(R.id.versionText)
        versionText.text = getString(R.string.profile_version, BuildConfig.VERSION_NAME)
        GradientTextHelper.applyHorizontalGradient(versionText, R.color.gradient_pink, R.color.gradient_orange)
    }

    private fun setupMenuItems() {
        bindMenuItem(
            rootId = R.id.languageMenuItem,
            iconRes = R.drawable.ic_profile_language,
            title = getString(R.string.profile_selected_language),
            subtitle = "",
        )
        refreshLanguageSubtitle()
        bindMenuItem(
            rootId = R.id.helpMenuItem,
            iconRes = R.drawable.ic_profile_help,
            title = getString(R.string.profile_help_support),
            subtitle = getString(R.string.profile_help_support_subtitle),
        )
        bindMenuItem(
            rootId = R.id.privacyMenuItem,
            iconRes = R.drawable.ic_profile_privacy,
            title = getString(R.string.profile_privacy_policy),
            subtitle = getString(R.string.profile_privacy_policy_subtitle),
        )
        bindMenuItem(
            rootId = R.id.deleteAccountMenuItem,
            iconRes = R.drawable.ic_profile_delete_account,
            title = getString(R.string.profile_delete_account),
            subtitle = getString(R.string.profile_delete_account_subtitle),
        )
    }

    private fun setupActions() {
        findViewById<ImageView>(R.id.backButton).setOnClickListener { finish() }

        findViewById<View>(R.id.logoutButton).setOnClickListener {
            if (logoutHandled) return@setOnClickListener
            logoutHandled = true
            mixpanelAnalytics().logout(this, AnalyticsSource.PROFILE, LogoutReason.USER_INITIATED)
            metaAnalytics().clearUserId()
            firebaseAnalytics().clearUserId()
            Toast.makeText(this, R.string.profile_logout_toast, Toast.LENGTH_SHORT).show()
            startActivity(
                PhoneAuthActivity.intent(this).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                },
            )
            finish()
        }

        findViewById<View>(R.id.languageMenuItem).setOnClickListener {
            startActivity(LanguageSelectionActivity.intent(this))
        }
        findViewById<View>(R.id.helpMenuItem).setOnClickListener {
            openUrl(HELP_SUPPORT_URL, ExternalLink.HELP_SUPPORT)
        }
        findViewById<View>(R.id.privacyMenuItem).setOnClickListener {
            openUrl(PRIVACY_POLICY_URL, ExternalLink.PRIVACY_POLICY)
        }
        findViewById<View>(R.id.deleteAccountMenuItem).setOnClickListener {
            openUrl(DELETE_ACCOUNT_URL, ExternalLink.DELETE_ACCOUNT)
        }
    }

    /** [link] is an [ExternalLink] value, tracked only once a browser actually opened. */
    private fun openUrl(url: String, link: String) {
        if (isNavigating) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            return
        }
        isNavigating = true
        mixpanelAnalytics().trackExternalLinkOpened(link, AnalyticsSource.PROFILE)
    }

    private fun bindMenuItem(rootId: Int, iconRes: Int, title: String, subtitle: String) {
        val root = findViewById<View>(rootId)
        root.findViewById<ImageView>(R.id.menuIcon).setImageResource(iconRes)
        root.findViewById<TextView>(R.id.menuTitle).text = title
        root.findViewById<TextView>(R.id.menuSubtitle).text = subtitle
    }

    companion object {
        private const val HELP_SUPPORT_URL = "https://meratune.app/contact/"
        private const val PRIVACY_POLICY_URL = "https://meratune.app/privacy/"
        private const val DELETE_ACCOUNT_URL = "https://meratune.app/delete-account/"

        fun intent(context: Context): Intent = Intent(context, ProfileActivity::class.java)
    }
}

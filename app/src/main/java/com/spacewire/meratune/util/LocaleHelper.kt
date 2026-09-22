package com.spacewire.meratune.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.spacewire.meratune.AuthActivity

object LocaleHelper {

    fun applyStoredLocale(context: Context) {
        applyLocale(context, ProfileStore(context).getLocaleCode())
    }

    fun applyLocale(context: Context, localeCode: String) {
        val locales = LocaleListCompat.forLanguageTags(localeCode)
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != locales.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }

    fun restartApp(context: Context) {
        val intent = AuthActivity.intent(context).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
        if (context is Activity) {
            context.finish()
        }
    }
}

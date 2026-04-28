package com.moyue.app.util

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

object LocaleHelper {

    private const val PREFS_NAME = "moreader_prefs"
    private const val KEY_LANG = "selected_language"

    // null = follow system; "zh" = Chinese; "en" = English
    fun getSelectedLanguage(context: Context): String? {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lang = prefs.getString(KEY_LANG, null)
        return if (lang.isNullOrBlank()) null else lang
    }

    fun setSelectedLanguage(context: Context, lang: String?) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANG, lang).apply()
    }

    /**
     * Wrap the base context with our locale. Called from attachBaseContext.
     * This is the official Android pattern for per-app language support.
     */
    fun wrap(context: Context): Context {
        val lang = getSelectedLanguage(context) ?: return context
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}

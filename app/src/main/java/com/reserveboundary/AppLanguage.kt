package com.reserveboundary

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object AppLanguage {
    const val ENGLISH = "en"
    const val HEBREW = "iw"
    private const val HEBREW_MODERN = "he"

    fun currentLanguageTag(): String {
        val current = AppCompatDelegate.getApplicationLocales()[0]?.language
        return if (isHebrew(current)) HEBREW else ENGLISH
    }

    fun isHebrew(): Boolean = currentLanguageTag() == HEBREW

    fun setLanguage(languageTag: String): Boolean {
        val normalized = if (isHebrew(languageTag)) HEBREW else ENGLISH
        val currentRaw = AppCompatDelegate.getApplicationLocales()[0]?.language
        if ((normalized == ENGLISH && currentRaw == ENGLISH) ||
            (normalized == HEBREW && currentRaw == HEBREW)) {
            return false
        }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(normalized))
        return true
    }

    private fun isHebrew(languageTag: String?): Boolean =
        languageTag == HEBREW || languageTag == HEBREW_MODERN
}

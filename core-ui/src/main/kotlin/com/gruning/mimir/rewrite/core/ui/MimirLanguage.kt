package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * The language the player chose for Mimir's own text (Settings > Appearance > Language).
 *
 * [AUTOMATIC] is "no override": [languageTag] and [locale] are null and the device's language wins, exactly as if the
 * setting did not exist. The other two name their locale. Spanish is Latin American (`es-419`);
 * its resource folders are `values-b+es+419`, with `values-es` as a copy for a device set to plain `es`.
 *
 * [storageKey] is what the app persists, so it is stable and never renamed; [nativeName] is the name a player reads in
 * the row, always in the language itself, so a player who landed in the wrong one can still find their own. Automatic has
 * none: its label is a translated string resource.
 */
enum class MimirLanguage(
    val storageKey: String,
    val languageTag: String?,
    val nativeName: String?,
) {
    AUTOMATIC(storageKey = "automatic", languageTag = null, nativeName = null),
    ENGLISH(storageKey = "en", languageTag = "en", nativeName = "English"),
    SPANISH_LATAM(storageKey = "es-419", languageTag = "es-419", nativeName = "Español (Latinoamérica)"),
    ;

    /** The locale to apply, or null for [AUTOMATIC]: nothing is overridden. */
    val locale: Locale?
        get() = languageTag?.let { Locale.forLanguageTag(it) }

    /** The next choice in Settings' row order, wrapping from the last back to [AUTOMATIC]. */
    fun next(): MimirLanguage = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT: MimirLanguage = AUTOMATIC

        /** An unknown or absent stored key (a first run, or a choice a later build renamed) is [DEFAULT]. */
        fun fromStorageKey(key: String?): MimirLanguage =
            entries.firstOrNull { it.storageKey == key } ?: DEFAULT

        /**
         * The choice an app-level locale stands for, for reading back what Android holds (API 33+ `LocaleManager`): a null
         * locale is [AUTOMATIC], any Spanish is [SPANISH_LATAM], any English is [ENGLISH], and a language Mimir does not
         * ship is [AUTOMATIC] rather than a guess.
         */
        fun fromLocale(locale: Locale?): MimirLanguage = when (locale?.language) {
            "es" -> SPANISH_LATAM
            "en" -> ENGLISH
            else -> AUTOMATIC
        }
    }
}

/** Process-wide snapshot state shared by Settings and the Activities. Persistence and the platform locale are owned by app. */
object MimirLanguageSettings {
    var language by mutableStateOf(MimirLanguage.DEFAULT)
}

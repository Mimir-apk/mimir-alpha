package com.gruning.mimir.rewrite

import android.content.Context
import android.os.Build
import androidx.compose.runtime.snapshots.Snapshot
import com.gruning.mimir.rewrite.core.ui.MimirLanguage
import com.gruning.mimir.rewrite.core.ui.MimirLanguageSettings

/**
 * The persisted language choice (Settings > Appearance > Language): Automatic, English or Español (Latinoamérica).
 *
 * One small SharedPreferences entry in the `mimir_rewrite_appearance` file the theme, backdrop and layout choices already
 * share (so MimirPrefsPrefetch has it parsed before the first read), keyed by [MimirLanguage.storageKey]. The live value is
 * [MimirLanguageSettings.language], the snapshot state Settings reads, the same shape as the Layout choice's
 * `MimirViewportSettings.mode`.
 *
 * Who is the truth depends on the API level, because Android only owns the app's language from API 33:
 *  - API 30-32 (Android 11-12L): this store. [MimirLocale.attach] reads it for every Activity.
 *  - API 33+: Android's per-app language (`LocaleManager`). [set] writes it there too, and [load] reads it back, so a language
 *    changed in Android's own settings is what Mimir's row shows. The stored key is kept in step so the file never disagrees.
 *
 * Automatic is the absence of an override: no locale is applied anywhere and the device language wins.
 */
internal object MimirLanguageStore {
    private const val PREFS = "mimir_rewrite_appearance"
    internal const val KEY_LANGUAGE = "languagePreference"

    /**
     * The stored choice, read straight from disk-backed preferences with no side effects. Safe from `attachBaseContext`, where an
     * Activity has no application context of its own yet: it opens the same file through the context it is given.
     */
    fun read(context: Context): MimirLanguage =
        MimirLanguage.fromStorageKey(prefs(context).getString(KEY_LANGUAGE, null))

    /** Load the choice into [MimirLanguageSettings] once at process start (`MimirApplication.onCreate`). */
    fun load(context: Context) {
        val stored = read(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val held = MimirLocale.platformLanguage(context) ?: stored
            MimirLanguageSettings.language = held
            if (held != stored) writeKey(context, held)
        } else {
            MimirLanguageSettings.language = stored
        }
    }

    /**
     * Persist and apply a new choice. Updates [MimirLanguageSettings] first so Settings shows it at once. On API 33+ Android applies it
     * to every Mimir window and recreates them; before that, MainActivity recreates itself when it sees the value change
     * ([MimirLocale.recreateWhenLanguageChanges]) and every other Activity reads the new choice when it is next created.
     */
    fun set(context: Context, language: MimirLanguage) {
        writeKey(context, language)
        MimirLanguageSettings.language = language
        // Tell snapshotFlow collectors (MainActivity's recreate watcher) now, not at the next frame: a write to global snapshot state is only
        // announced on a Compose frame, and the watcher must not depend on one being scheduled.
        Snapshot.sendApplyNotifications()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            MimirLocale.applyPlatformLanguage(context, language)
        }
    }

    private fun writeKey(context: Context, language: MimirLanguage) {
        prefs(context).edit().putString(KEY_LANGUAGE, language.storageKey).apply()
    }

    private fun prefs(context: Context) =
        (context.applicationContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

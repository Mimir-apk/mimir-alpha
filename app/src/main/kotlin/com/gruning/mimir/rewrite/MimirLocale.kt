package com.gruning.mimir.rewrite

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.lifecycleScope
import com.gruning.mimir.rewrite.core.ui.MimirLanguage
import com.gruning.mimir.rewrite.core.ui.MimirLanguageSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Applies the player's language choice ([MimirLanguageStore]) to Mimir's windows. The one helper every Activity uses; there is no
 * AppCompat here to do it.
 *
 * - **API 30-32.** Android has no per-app language, so [attach] gives the Activity an override configuration carrying the chosen
 *   locale (`ContextThemeWrapper.applyOverrideConfiguration`, the same call AppCompat makes). The Activity keeps its own base context,
 *   so its display, window and system services are untouched; only its resources change. Compose's `stringResource` reads the
 *   Activity's resources, so it follows. Automatic applies nothing: no override, the device locale wins.
 * - **API 33+.** Android owns it. [applyPlatformLanguage] hands the choice to `LocaleManager.setApplicationLocales` (an empty list for
 *   Automatic), which applies to every Mimir window in the process, the second screen's included, and recreates them.
 *   [platformLanguage] reads it back. [attach] does nothing there.
 *
 * What the override does not reach, on API 30-32: the Application context (a toast or notification built from it is in the device
 * language) and `Locale.getDefault()` (a bare `String.format` or `DateFormat`). Strings extracted in later steps read through the
 * Activity's resources, or a `Resources` they are handed; whoever extracts a service or notification string must hand it one.
 */
internal object MimirLocale {
    private const val TAG = "MimirLocale"

    /**
     * Call from every Activity's `attachBaseContext`, straight after `super.attachBaseContext(newBase)` and before anything touches
     * `resources`. Returns the language applied (Automatic when nothing was overridden), or null from API 33, where the system
     * applies the app's language and recreates the Activity itself.
     */
    fun attach(activity: ComponentActivity): MimirLanguage? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return null
        val language = MimirLanguageStore.read(activity.baseContext)
        overrideConfigurationFor(language)?.let { activity.applyOverrideConfiguration(it) }
        return language
    }

    /**
     * The resources for text that is drawn outside any Activity (the ᛗ button's menu is an overlay window the process owns). From API 33
     * the system applies the app's language to every context, so [context]'s own are right. Below it they are in the device language,
     * so the player's choice ([MimirLanguageSettings], loaded at process start) is applied the way [attach] applies it to an Activity.
     * Automatic, or an unresolved choice, leaves [context]'s own.
     */
    fun resourcesFor(context: Context): Resources {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context.resources
        val override = overrideConfigurationFor(MimirLanguageSettings.language) ?: return context.resources
        return context.createConfigurationContext(override).resources
    }

    /**
     * The override [attach] hands an Activity for [language]: only the locale (and the layout direction it implies) is set, every
     * other field is left unset so the Activity keeps its own size, density and night mode. Null for Automatic.
     */
    fun overrideConfigurationFor(language: MimirLanguage): Configuration? {
        val locale = language.locale ?: return null
        return Configuration().apply { setLocales(LocaleList(locale)) }
    }

    /**
     * Recreate [activity] once the player picks a language other than [applied] (what [attach] returned). For an Activity that draws
     * language-dependent UI and lives long enough to be on screen when Settings changes it: MainActivity. The short-lived return and
     * transfer Activities and the display-4 floor follow the stored choice when they are created.
     *
     * A no-op when [applied] is null (API 33+): the system recreates the Activity itself when `LocaleManager` changes the app's locale.
     */
    fun recreateWhenLanguageChanges(activity: ComponentActivity, applied: MimirLanguage?) {
        if (applied == null) return
        activity.lifecycleScope.launch {
            snapshotFlow { MimirLanguageSettings.language }.first { it != applied }
            activity.recreate()
        }
    }

    /**
     * The language Android holds for this app: Automatic when it holds no override. Null when it cannot be asked, so the caller keeps
     * the stored choice rather than guessing (the launcher must outlive a platform service that refuses).
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun platformLanguage(context: Context): MimirLanguage? {
        val manager = context.getSystemService(LocaleManager::class.java) ?: return null
        return try {
            val locales = manager.applicationLocales
            if (locales.isEmpty) MimirLanguage.AUTOMATIC else MimirLanguage.fromLocale(locales[0])
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not read the app language back from LocaleManager", e)
            null
        }
    }

    /** Hand [language] to Android as this app's locale; Automatic clears the override. Does nothing when it already holds it. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun applyPlatformLanguage(context: Context, language: MimirLanguage) {
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        try {
            val wanted = platformLocales(language)
            if (manager.applicationLocales != wanted) manager.applicationLocales = wanted
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not hand the app language to LocaleManager", e)
        }
    }

    /** The `LocaleList` Android should hold for [language]: empty for Automatic, so the device locale wins. */
    fun platformLocales(language: MimirLanguage): LocaleList =
        language.languageTag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
}

/**
 * The base of every Mimir Activity: puts the player's language choice under it ([MimirLocale.attach]) and, unless an Activity opts out,
 * recreates it when the choice changes ([MimirLocale.recreateWhenLanguageChanges]). MainActivity, the display-4 floor, the return host
 * and the primary transfer host all extend it, so none of them can read the device language by mistake once its strings are extracted.
 *
 * Public only because the Activities that extend it are; it holds nothing but those two calls.
 */
abstract class MimirLocaleActivity : ComponentActivity() {

    /** The language [MimirLocale.attach] applied to this instance's resources; null from API 33, where the system applies it. */
    private var appliedLanguage: MimirLanguage? = null

    /**
     * True when this Activity is recreated, below API 33, as soon as the choice changes. False for a short-lived host or a window
     * that has no language-dependent UI and must not be relaunched mid-handoff; it still follows the stored choice when created.
     */
    protected open val recreatesWhenLanguageChanges: Boolean = true

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        appliedLanguage = MimirLocale.attach(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (recreatesWhenLanguageChanges) MimirLocale.recreateWhenLanguageChanges(this, appliedLanguage)
    }
}

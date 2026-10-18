package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The persisted appearance choice — which Norse [MimirThemeId] identity the app wears.
 *
 * Process-scoped single source of truth. Backed by a small [android.content.SharedPreferences] entry
 * keyed by the theme's STABLE [MimirThemeId.storageKey] (the same key the shipping product persisted, so
 * a future migration can read it). The value is exposed as a [StateFlow] so the app root re-skins LIVE
 * the instant the Appearance picker changes it — no restart, no recomposition plumbing at the call site.
 *
 * Local-only: this is a UI preference, never network. Reads are cheap and synchronous (a single string).
 * [load] runs at process start (`seedCompanionThemeAtProcessStart`, so the Bottom's first payload wears the saved theme
 * before any MainActivity exists) and again from [MainActivity.onCreate] before first composition, so the app opens
 * already wearing the saved theme (no default→saved flash).
 */
object ThemeStore {
    private const val PREFS = "mimir_rewrite_appearance"
    private const val KEY_THEME = "themePreference"

    private val _themeId = MutableStateFlow(MimirThemeId.DEFAULT)

    /** The active theme identity. Collect in composition (`collectAsState`) for a live re-skin. */
    val themeId: StateFlow<MimirThemeId> = _themeId.asStateFlow()

    private val _explicitChanges = MutableStateFlow(0)

    /**
     * How many times [setTheme] has changed the theme in this process. [load] never counts: a stored theme is what a
     * process starts wearing, not a change.
     *
     * The motion policy wakes the ambient motion gate on "an explicit theme change", and this count is what that means. Main's first composition used to pulse the gate for the theme it merely started with, which
     * made it the last wake of every restart. See [claimExplicitChangeWake].
     */
    val explicitChanges: StateFlow<Int> = _explicitChanges.asStateFlow()

    private var explicitChangesClaimed = 0

    /** Load the persisted choice once at startup (falls back to DEFAULT for a first run / unknown key). */
    fun load(context: Context) {
        val key = prefs(context).getString(KEY_THEME, null)
        _themeId.value = MimirThemeId.fromStorageKey(key)
    }

    /**
     * Persist + apply a new theme. Updates the flow synchronously so the UI re-skins immediately.
     *
     * The app's explicit theme change: the Appearance picker, the `mimir.setTheme` dev hook and the shipping migration
     * all come through here. A change of value counts in [explicitChanges]; re-selecting the current theme persists it
     * again and counts nothing, as the flow emits nothing for it either.
     */
    fun setTheme(context: Context, id: MimirThemeId) {
        prefs(context).edit().putString(KEY_THEME, id.storageKey).apply()
        if (_themeId.value == id) return
        _themeId.value = id
        _explicitChanges.update { it + 1 }
    }

    /**
     * True once for each [explicitChanges] count nothing has claimed yet; false otherwise.
     *
     * Claimed by the motion gate's theme wake (`ThemeChangeMotionWake`). The claim lives here, for the process, rather than
     * in a composition: a change made before Main first composes (the dev hook runs in `onCreate`) still wakes once, and a
     * recreated composition does not wake again for a change an earlier one claimed.
     */
    @Synchronized
    fun claimExplicitChangeWake(): Boolean {
        val changes = _explicitChanges.value
        if (changes == explicitChangesClaimed) return false
        explicitChangesClaimed = changes
        return true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

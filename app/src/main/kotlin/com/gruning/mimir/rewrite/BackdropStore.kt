package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.feature.library.GamelistBackdropMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The persisted Bifröst choice (reduced to two states 2026-07-19).
 *
 * Mirrors [ThemeStore] 1:1 (same appearance prefs file, a StateFlow read live in composition, loaded once at
 * startup before first composition so the gamelist opens already wearing the saved backdrop). This replaces
 * the old hidden compile-time constant `GamelistBackdropCurrent` with a real, user-selectable Settings choice.
 *
 * **exactly two user-facing states, and Settings is their sole authority.**
 *  - `Cover only`   → [GamelistBackdropMode.OFF]     — Bifröst off. Cover art still renders.
 *  - `Cover + Hero` → [GamelistBackdropMode.BIFROST] — Bifröst on: the chosen/eligible hero behind the cover.
 *
 * `Backdrop only` ([GamelistBackdropMode.BIFROST_BACKDROP]) is retired from the UI: it was the one state that
 * made cover art disappear, which the ruling forbids. [GamelistBackdropMode.AURORA] remains an internal
 * gamelist eval engine and was never settable here. Both migrate to `Cover + Hero` on read — see
 * [bifrostModeFromStored]. The enum itself is untouched; it is the gamelist engine's vocabulary, not this
 * setting's.
 *
 * Local-only: a UI preference, never network.
 */
object BackdropStore {
    private const val PREFS = "mimir_rewrite_appearance"
    private const val KEY_BACKDROP = "gamelistBackdrop"

    /** Default for a fresh install: the full look (falls back to the cover when a game has no clip). */
    internal val DEFAULT = GamelistBackdropMode.BIFROST

    private val _mode = MutableStateFlow(DEFAULT)

    /** The active gamelist backdrop. Collect in composition (`collectAsState`) for a live change. */
    val mode: StateFlow<GamelistBackdropMode> = _mode.asStateFlow()

    /** Load the persisted choice once at startup, migrating any retired value to a live two-state one. */
    fun load(context: Context) {
        _mode.value = bifrostModeFromStored(prefs(context).getString(KEY_BACKDROP, null))
    }

    /** Persist + apply a new mode. Updates the flow synchronously so the gamelist backdrop changes immediately. */
    fun setMode(context: Context, mode: GamelistBackdropMode) {
        prefs(context).edit().putString(KEY_BACKDROP, mode.name).apply()
        _mode.value = mode
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * The conservative two-state migration used here:
 *  - stored `OFF`  → `Cover only`   (the user had Bifröst off; keep it off)
 *  - anything else → `Cover + Hero` (every Bifröst-enabled state collapses to the one remaining on-state)
 *
 * "Anything else" deliberately also absorbs the retired `BIFROST_BACKDROP`, the never-settable internal
 * `AURORA`, unparseable junk, and a first run with nothing stored — all of which land on the fresh-install
 * default anyway. Pure, so the migration is JVM-testable without SharedPreferences.
 */
internal fun bifrostModeFromStored(saved: String?): GamelistBackdropMode =
    if (saved == GamelistBackdropMode.OFF.name) GamelistBackdropMode.OFF else BackdropStore.DEFAULT

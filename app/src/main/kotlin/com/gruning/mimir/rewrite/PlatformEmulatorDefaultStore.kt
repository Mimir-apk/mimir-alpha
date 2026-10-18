package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.PlatformId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The per-PLATFORM default emulator — which installed emulator opens each system (e.g. "PS1 → DuckStation").
 * A convenience layer above the per-GAME Open-With override: at launch the engine resolves emulator in the
 * order per-game override → **this platform default** → Auto (first installed).
 *
 * Process-scoped single source of truth, mirroring [ThemeStore] / [ProfileNameStore]: a small
 * [android.content.SharedPreferences] map keyed `default_<platformId>` = the [EmulatorId] value. Exposed as a
 * [StateFlow] so the Emulators pane reflects a change live. Local-only, never network. Cleared entry = Auto.
 * [load] runs once at startup before the launch engine reads a default.
 */
object PlatformEmulatorDefaultStore {
    private const val PREFS = "mimir_rewrite_platform_defaults"
    private const val KEY_PREFIX = "default_"

    private val _defaults = MutableStateFlow<Map<PlatformId, EmulatorId>>(emptyMap())

    /** platformId → the user's chosen default emulator. Absent = Auto. Collect for a live pane. */
    val defaults: StateFlow<Map<PlatformId, EmulatorId>> = _defaults.asStateFlow()

    /** The chosen default for [platformId], or null when the platform is on Auto. Read by the launch engine. */
    fun defaultFor(platformId: PlatformId): EmulatorId? = _defaults.value[platformId]

    /** Load all persisted per-platform defaults once at startup. */
    fun load(context: Context) {
        val prefs = prefs(context)
        _defaults.value = prefs.all.entries
            .filter { it.key.startsWith(KEY_PREFIX) && it.value is String }
            .associate { PlatformId(it.key.removePrefix(KEY_PREFIX)) to EmulatorId(it.value as String) }
    }

    /**
     * Set (or clear, when [emulatorId] is null → Auto) the default emulator for [platformId]. Persists + updates
     * the flow synchronously so the pane re-reads it immediately; the next launch of that platform uses it.
     */
    fun setDefault(context: Context, platformId: PlatformId, emulatorId: EmulatorId?) {
        val editor = prefs(context).edit()
        if (emulatorId == null) editor.remove(KEY_PREFIX + platformId.value)
        else editor.putString(KEY_PREFIX + platformId.value, emulatorId.value)
        editor.apply()
        _defaults.value = _defaults.value.toMutableMap().apply {
            if (emulatorId == null) remove(platformId) else put(platformId, emulatorId)
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

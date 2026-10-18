package com.gruning.mimir.rewrite

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The persisted local profile display name — the optional friendly name the user gives their device.
 *
 * Process-scoped single source of truth, mirroring [ThemeStore]: a small [android.content.SharedPreferences]
 * string exposed as a [StateFlow] so the Profile row re-reads it LIVE the instant the name-edit saves — no
 * restart. Local-only: this is a UI preference, never network, never synced. The stable profile key
 * stays `LOCAL_PROFILE_ID`; this is only the display name.
 * A blank value means "no name set" — a name is always optional. [load] runs once from
 * [MainActivity.onCreate] before first composition so the app opens already showing the saved name.
 *
 */
object ProfileNameStore {
    private const val PREFS = "mimir_rewrite_profile"
    private const val KEY_NAME = "displayName"

    private val _displayName = MutableStateFlow("")

    /** The local profile display name ("" = none set). Collect in composition for a live re-read. */
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    /** Load the persisted name once at startup ("" for a first run). */
    fun load(context: Context) {
        _displayName.value = prefs(context).getString(KEY_NAME, "").orEmpty()
    }

    /** Persist + apply a new name (trimmed). Updates the flow synchronously so the Profile row re-reads it. */
    fun setName(context: Context, name: String) {
        val trimmed = name.trim()
        prefs(context).edit().putString(KEY_NAME, trimmed).apply()
        _displayName.value = trimmed
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

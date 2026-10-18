package com.gruning.mimir.rewrite

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class QuickLaunchLaunchOrigin(val recordsGeneralMru: Boolean) {
    PINNED_SLOT(false),
    GENERAL_PRIMARY(true),
    GENERAL_COMPANION(true),
    GENERAL_M_BUTTON(true),
}

/** The destination remembered for a secondary-display quick launch package. */
internal enum class QuickLaunchDisplayRole(val storageValue: String) {
    PRIMARY("primary"),
    SECONDARY("secondary"),
}

internal fun quickLaunchDisplayRoleFromStorage(value: String?): QuickLaunchDisplayRole? = when {
    value.equals(QuickLaunchDisplayRole.PRIMARY.storageValue, ignoreCase = true) ->
        QuickLaunchDisplayRole.PRIMARY
    value.equals(QuickLaunchDisplayRole.SECONDARY.storageValue, ignoreCase = true) ->
        QuickLaunchDisplayRole.SECONDARY
    else -> null
}

internal const val QuickLaunchDisplayPreferencePrefix = "launch_display/"

/** Restore only package-keyed display preferences; numeric slot ids are intentionally not accepted. */
internal fun restoreQuickLaunchDisplayPreferences(
    values: Map<String, *>,
    keyPrefix: String = QuickLaunchDisplayPreferencePrefix,
): Map<String, QuickLaunchDisplayRole> = values.asSequence()
    .filter { (key, value) ->
        key.startsWith(keyPrefix) &&
            key.removePrefix(keyPrefix).isNotBlank() &&
            key.removePrefix(keyPrefix).any { !it.isDigit() } &&
            value is String
    }
    .mapNotNull { (key, value) ->
        quickLaunchDisplayRoleFromStorage(value as String)?.let { role ->
            key.removePrefix(keyPrefix) to role
        }
    }
    .toMap()

internal fun shouldRecordQuickLaunchMru(
    origin: QuickLaunchLaunchOrigin,
    dispatchSucceeded: Boolean,
): Boolean = origin.recordsGeneralMru && dispatchSucceeded

/** One cold-host ordering seam: persistence is loaded before any process flow is read. */
internal inline fun <T> quickLaunchColdRead(load: () -> Unit, read: () -> T): T {
    load()
    return read()
}

internal fun restoreQuickLaunchSequences(
    values: Map<String, *>,
    keyPrefix: String = "launch_sequence/",
): Map<String, Long> = values.asSequence()
    .filter { (key, value) -> key.startsWith(keyPrefix) && value is Long && value > 0L }
    .associate { (key, value) -> key.removePrefix(keyPrefix) to value as Long }

/**
 * UX01 — the companion Now-Playing quick-launch slots (Q1–Q5): which Android app each pinned slot opens
 * on screen 2, so the player can jump to another app without leaving the game. "General" is never pinned
 * (it always opens the full picker).
 *
 * Process-scoped, mirroring [PlatformEmulatorDefaultStore]: a small SharedPreferences map (`slot_0..4` =
 * packageName) exposed as a [StateFlow] so the Now-Playing footer reflects a re-pin live. Local-only, never
 * network. A cleared slot = empty ("+"). [load] runs once at startup.
 */
object QuickLaunchStore {
    /**
     * **Five since PR173 S2-01.** The fifth pin occupies the former collection-switch seat. Five 56 dp seats
     * and four 10 dp gaps fit the rail without shrinking any seat or changing Q1–Q4 positions: 320 dp of
     * the 506 dp the 538 dp panel leaves inside its padding, so nothing shrinks and
     * no seat approaches §4's 48 dp floor. The same ruling REFUSED the rail's other half: it stays off
     * `HOME`, because putting it there permanently costs 86 dp of a 468 dp panel and drops each
     * lattice tile to 38.7 dp — under that same floor, on a surface with no controller.
     *
     * Read as a count, never assumed: the companion row iterates `slots.indices`.
     */
    const val SLOT_COUNT = 5
    private const val PREFS = "mimir_rewrite_quick_launch"
    private const val KEY_PREFIX = "slot_"
    private const val KEY_GENERAL_FOCUS_PACKAGE = "general_focus_package"
    private const val KEY_LAUNCH_SEQUENCE = "launch_sequence"
    private const val KEY_LAUNCH_SEQUENCE_PREFIX = "launch_sequence/"

    private val _slots = MutableStateFlow<List<String?>>(List(SLOT_COUNT) { null })
    private val _launchSequences = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val _displayPreferences = MutableStateFlow<Map<String, QuickLaunchDisplayRole>>(emptyMap())

    /** index 0..4 → the pinned package, or null when the slot is empty. Collect for a live footer. */
    val slots: StateFlow<List<String?>> = _slots.asStateFlow()
    /** Successful Mimir dispatch order for General only; zero/absent means never launched. */
    val launchSequences: StateFlow<Map<String, Long>> = _launchSequences.asStateFlow()
    /** Package-keyed destination choices. The absence of a key means ask on first secondary launch. */
    internal val displayPreferences: StateFlow<Map<String, QuickLaunchDisplayRole>> = _displayPreferences.asStateFlow()

    /** Load the persisted pins once at startup, before the Now-Playing footer first reads them. */
    fun load(context: Context) {
        val prefs = prefs(context)
        _slots.value = List(SLOT_COUNT) { i ->
            prefs.getString(KEY_PREFIX + i, null)?.takeIf { it.isNotBlank() }
        }
        _launchSequences.value = restoreQuickLaunchSequences(prefs.all, KEY_LAUNCH_SEQUENCE_PREFIX)
        _displayPreferences.value = restoreQuickLaunchDisplayPreferences(prefs.all)
    }

    /** Pin [packageName] to slot [index] (or clear, when null/blank); persists + updates the flow synchronously. */
    fun setSlot(context: Context, index: Int, packageName: String?) {
        if (index !in 0 until SLOT_COUNT) return
        val clean = packageName?.trim()?.takeIf { it.isNotBlank() }
        prefs(context).edit().apply {
            if (clean == null) remove(KEY_PREFIX + index) else putString(KEY_PREFIX + index, clean)
        }.apply()
        _slots.value = _slots.value.toMutableList().also { it[index] = clean }
    }

    internal fun displayPreference(packageName: String): QuickLaunchDisplayRole? {
        val clean = packageName.trim().takeIf { it.isNotBlank() } ?: return null
        return _displayPreferences.value[clean]
    }

    internal fun setDisplayPreference(
        context: Context,
        packageName: String,
        role: QuickLaunchDisplayRole,
    ) {
        val clean = packageName.trim().takeIf { it.isNotBlank() } ?: return
        prefs(context).edit().putString(QuickLaunchDisplayPreferencePrefix + clean, role.storageValue).apply()
        _displayPreferences.value = _displayPreferences.value + (clean to role)
    }

    internal fun clearDisplayPreference(context: Context, packageName: String) {
        val clean = packageName.trim().takeIf { it.isNotBlank() } ?: return
        prefs(context).edit().remove(QuickLaunchDisplayPreferencePrefix + clean).apply()
        _displayPreferences.value = _displayPreferences.value - clean
    }

    /**
     * Last app focused in General. Unlike Q1–Q5 this is navigation memory, not a pin: reopening
     * General lands on the app the user was last inspecting instead of jumping back to catalog item 0.
     */
    fun lastGeneralPackageName(context: Context): String? =
        prefs(context).getString(KEY_GENERAL_FOCUS_PACKAGE, null)?.takeIf { it.isNotBlank() }

    fun setLastGeneralPackageName(context: Context, packageName: String) {
        packageName.trim().takeIf { it.isNotBlank() }?.let { clean ->
            prefs(context).edit().putString(KEY_GENERAL_FOCUS_PACKAGE, clean).apply()
        }
    }

    /** Settle every route at one policy seam; pinned and failed launches can never mutate General MRU. */
    internal fun settleLaunch(
        context: Context,
        origin: QuickLaunchLaunchOrigin,
        dispatchSucceeded: Boolean,
        packageName: String,
    ): Boolean {
        if (!shouldRecordQuickLaunchMru(origin, dispatchSucceeded)) return false
        val clean = packageName.trim().takeIf { it.isNotBlank() } ?: return false
        val preferences = prefs(context)
        val previous = preferences.getLong(KEY_LAUNCH_SEQUENCE, 0L)
        val next = if (previous == Long.MAX_VALUE) Long.MAX_VALUE else previous + 1L
        preferences.edit()
            .putLong(KEY_LAUNCH_SEQUENCE, next)
            .putLong(KEY_LAUNCH_SEQUENCE_PREFIX + clean, next)
            .apply()
        _launchSequences.value = _launchSequences.value + (clean to next)
        return true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

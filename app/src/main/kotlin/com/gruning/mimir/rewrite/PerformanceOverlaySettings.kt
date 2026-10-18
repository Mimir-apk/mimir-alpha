package com.gruning.mimir.rewrite

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * GAMES_ONLY and ALWAYS both keep [PerformanceOverlayService] running; the difference is whether the
 * floating card is allowed to draw. See [performanceOverlayGamesOnlyVisible] for what "a game" means for
 * GAMES_ONLY: an app Mimir's own launch lifecycle dispatched from its library, never a platform
 * classification of whatever is foreground.
 *
 * **Sampling cadence — the intent below was OVERRIDDEN on 2026-08-21; do not restore it.**
 *
 * This doc used to say that both scopes "keep sampling telemetry in the background", and the reason given
 * for it was that the card should find warm graph history waiting when it finally appears. That background-sampling behavior was removed: a warm graph is not worth 10% of a core standing. RC-44 measured the price on hardware
 * (build `05cd02a0…92615`, A/B/A, service ON = {1213, 1199} vs OFF = {608, 608} jiffies/60s) —
 * **598 jiffies per 60s, ~9.97% of one core, continuously, in the Tweaker daemon process, while drawing
 * nothing.** GAMES_ONLY sitting in Mimir's own UI with no dispatched game paid that in full, forever.
 *
 * **The rationale was also simply wrong on its own terms, which is the part worth remembering.** Background
 * sampling never produced a warm graph for GAMES_ONLY, because the graph is not service state — it lives in
 * `PerformanceOverlayPresenter.fpsHistory`, it is appended to only inside `show()`, and `hide()` clears it
 * to `emptyList()`. A hidden GAMES_ONLY tick calls `hide()`, so every hidden sample wiped exactly the
 * history it was supposedly accumulating. The card has always opened on an empty graph and still does; the
 * 9.97% bought no part of what the comment claimed for it.
 *
 * **The contract now:** the service still runs on both scopes, but the transport is read at
 * [PerformanceTelemetryPollMs] (1s) only while the card is actually being drawn, and at
 * [PerformanceTelemetryHiddenPollMs] (5s) while the screen is on and the card is not — GAMES_ONLY with
 * nothing dispatched, or a foreground that disagrees with the dispatched game. The loop's own beat stays at
 * 1s so the card still appears within one beat of a game being dispatched; only the Binder read into the
 * daemon is rationed. What a hidden stretch genuinely does keep warm is the battery-runtime estimator and
 * transport liveness — not the graph.
 *
 * The other two ways the card stops being drawn are not slow-polled, because they never reach a read at
 * all: with the screen off the loop skips the transport entirely and only re-checks that it is still off,
 * and if the draw grant is revoked the service calls `stopSelf()` rather than continuing at any cadence.
 */
internal enum class PerformanceOverlayVisibility(
    val enabled: Boolean,
    val displayLabel: String,
) {
    OFF(false, "OFF"),
    GAMES_ONLY(true, "GAMES ONLY"),
    ALWAYS(true, "ALWAYS ON"),
    ;

    fun step(direction: Int): PerformanceOverlayVisibility {
        if (direction == 0) return this
        val next = ordinal + if (direction < 0) -1 else 1
        return entries[next.coerceIn(0, entries.lastIndex)]
    }
}

/** Legacy persisted value from the OFF/ON-only era. A stored "ON" must keep meaning always-on. */
private const val LegacyAlwaysVisibilityStoredValue = "ON"

internal fun performanceOverlayVisibilityFromStoredValue(stored: String?): PerformanceOverlayVisibility =
    PerformanceOverlayVisibility.entries.firstOrNull { it.name == stored }
        ?: if (stored == LegacyAlwaysVisibilityStoredValue) {
            PerformanceOverlayVisibility.ALWAYS
        } else {
            PerformanceOverlayVisibility.OFF
        }

internal enum class PerformanceOverlayMode {
    MINIMAL,
    CLASSIC,
    DETAILED,
    ;

    fun next(): PerformanceOverlayMode = entries[(ordinal + 1) % entries.size]

    val displayLabel: String
        get() = when (this) {
            MINIMAL -> "COMPACT"
            CLASSIC -> "CLASSIC"
            DETAILED -> "EXTENDED"
        }
}

internal fun performanceOverlayModeFromStoredValue(stored: String?): PerformanceOverlayMode =
    PerformanceOverlayMode.entries.firstOrNull { it.name == stored }
        ?: PerformanceOverlayMode.CLASSIC

internal enum class PerformanceOverlayRowValue(val label: String) {
    OFF("OFF"),
    ON("ON"),
    ACCESS("ACCESS"),
    UNAVAILABLE("UNAVAILABLE"),
}

/**
 * [canDrawOverlays] is [android.provider.Settings.canDrawOverlays] read fresh by the caller, never a stored
 * preference. This row used to compute ON/OFF from [visibility] and [access] alone, so revoking "Display over
 * other apps" in Android Settings left it reading ON forever after — the persisted [visibility] never changes
 * on its own, and nothing was checking the one thing that actually decides whether the overlay can draw
 * ([performanceOverlayShouldRun] in `PerformanceOverlayService.kt`, and the identical live check at
 * `setPerformanceOverlayVisibility`'s `NEEDS_DRAW_ACCESS` path, both already gate on it). A missing draw
 * grant is reported through the same [PerformanceOverlayRowValue.ACCESS] value already used for a missing
 * Tweaker usage access grant — both are "the player must go grant something before this can run", the row
 * stays terse, and stepping/activating it already routes through [setPerformanceOverlayVisibility], which
 * knows how to open the right Settings screen and say the specific reason.
 */
internal fun performanceOverlayRowValue(
    visibility: PerformanceOverlayVisibility,
    access: TweakerUsageAccessState,
    canDrawOverlays: Boolean,
): PerformanceOverlayRowValue = when {
    access == TweakerUsageAccessState.SET_UP -> PerformanceOverlayRowValue.ACCESS
    access != TweakerUsageAccessState.READY -> PerformanceOverlayRowValue.UNAVAILABLE
    !visibility.enabled -> PerformanceOverlayRowValue.OFF
    !canDrawOverlays -> PerformanceOverlayRowValue.ACCESS
    else -> PerformanceOverlayRowValue.ON
}

internal data class PerformanceOverlayControlState(
    val visibility: PerformanceOverlayVisibility,
    val mode: PerformanceOverlayMode,
)

internal fun performanceOverlayControlLabel(
    visibility: PerformanceOverlayVisibility,
    mode: PerformanceOverlayMode,
    availability: PerformanceOverlayRowValue,
): String = when (availability) {
    PerformanceOverlayRowValue.ACCESS -> "ACCESS"
    PerformanceOverlayRowValue.UNAVAILABLE -> "UNAVAILABLE"
    PerformanceOverlayRowValue.OFF -> "OFF"
    // The row now settles WHEN the overlay appears and nothing else. Naming the view here too would
    // claim this control changes it, and it no longer does — that moved onto the overlay itself.
    PerformanceOverlayRowValue.ON -> visibility.displayLabel
}

/**
 * Steps WHEN the overlay is shown. It never touches the mode.
 *
 * The row used to walk a seven-entry product of scope and mode, so reaching "always on" meant
 * pressing through three view modes and the row's value read as two settings at once. This row controls scope alone — off, only games, always on — with the view cycled by touching
 * the overlay, where the effect of the change is actually visible. [mode] is carried through
 * untouched so a scope change never silently rewrites the player's chosen view.
 */
internal fun stepPerformanceOverlayControl(
    visibility: PerformanceOverlayVisibility,
    mode: PerformanceOverlayMode,
    direction: Int,
): PerformanceOverlayControlState {
    val scopes = listOf(
        PerformanceOverlayVisibility.OFF,
        PerformanceOverlayVisibility.GAMES_ONLY,
        PerformanceOverlayVisibility.ALWAYS,
    )
    val currentIndex = scopes.indexOf(visibility).coerceAtLeast(0)
    val delta = if (direction < 0) -1 else 1
    return PerformanceOverlayControlState(
        visibility = scopes[(currentIndex + delta).coerceIn(0, scopes.lastIndex)],
        mode = mode,
    )
}

internal data class PerformanceOverlayPlacement(
    val horizontalFraction: Float,
    val verticalFraction: Float,
) {
    fun sanitized(): PerformanceOverlayPlacement = PerformanceOverlayPlacement(
        horizontalFraction = horizontalFraction.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f,
        verticalFraction = verticalFraction.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f,
    )
}

internal object PerformanceOverlaySettingsStore {
    private const val PreferencesName = "mimir_performance_overlay"
    private const val VisibilityKey = "visibility"
    private const val ModeKey = "mode"
    private const val HorizontalPlacementKey = "horizontal_placement"
    private const val VerticalPlacementKey = "vertical_placement"
    private val visibilityMutable = MutableStateFlow(PerformanceOverlayVisibility.OFF)
    private val modeMutable = MutableStateFlow(PerformanceOverlayMode.CLASSIC)
    private var initialized = false

    val visibility: StateFlow<PerformanceOverlayVisibility> = visibilityMutable.asStateFlow()
    val selectedMode: StateFlow<PerformanceOverlayMode> = modeMutable.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val preferences = context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        visibilityMutable.value = performanceOverlayVisibilityFromStoredValue(
            preferences.getString(VisibilityKey, null),
        )
        modeMutable.value = performanceOverlayModeFromStoredValue(preferences.getString(ModeKey, null))
        initialized = true
    }

    @Synchronized
    fun setVisibility(context: Context, visibility: PerformanceOverlayVisibility): Boolean {
        initialize(context)
        val previous = visibilityMutable.value
        if (previous == visibility) return true
        context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(VisibilityKey, visibility.name)
            .apply()
        visibilityMutable.value = visibility
        // P9: a setting change while no game runs was otherwise invisible (§1.14 "SILENT for the
        // setting change") — the service only logs on a card-wanted flip, not on the setting itself.
        Log.i("MimirPerformanceOverlay", "visibility $previous -> $visibility")
        return true
    }

    fun mode(context: Context): PerformanceOverlayMode {
        initialize(context)
        return modeMutable.value
    }

    @Synchronized
    fun setMode(context: Context, mode: PerformanceOverlayMode): Boolean {
        initialize(context)
        if (modeMutable.value == mode) return true
        context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(ModeKey, mode.name)
            .apply()
        modeMutable.value = mode
        return true
    }

    fun placement(context: Context): PerformanceOverlayPlacement? {
        val preferences = context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        if (!preferences.contains(HorizontalPlacementKey) || !preferences.contains(VerticalPlacementKey)) {
            return null
        }
        return PerformanceOverlayPlacement(
            horizontalFraction = preferences.getFloat(HorizontalPlacementKey, 0f),
            verticalFraction = preferences.getFloat(VerticalPlacementKey, 0f),
        ).sanitized()
    }

    fun setPlacement(context: Context, placement: PerformanceOverlayPlacement): Boolean {
        val sanitized = placement.sanitized()
        context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putFloat(HorizontalPlacementKey, sanitized.horizontalFraction)
            .putFloat(VerticalPlacementKey, sanitized.verticalFraction)
            .apply()
        return true
    }
}

package com.gruning.mimir.rewrite

import android.content.Context
import android.util.Log
import com.gruning.mimir.rewrite.core.model.MimirMarkPlacement
import com.gruning.mimir.rewrite.core.ui.MimirAccentStop
import com.gruning.mimir.rewrite.core.ui.MimirAccentTemp
import com.gruning.mimir.rewrite.core.ui.MimirAppearanceOverrides
import com.gruning.mimir.rewrite.core.ui.MimirButtonMark
import com.gruning.mimir.rewrite.core.ui.MimirCalmShineSettings
import com.gruning.mimir.rewrite.core.ui.MimirRingStop
import com.gruning.mimir.rewrite.core.ui.MimirSceneGround
import com.gruning.mimir.rewrite.core.ui.MimirSceneGroundImages
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirViewportMode
import com.gruning.mimir.rewrite.core.ui.MimirViewportSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * AP01 — the persistence bridge for [MimirAppearanceOverrides]: the Default-theme accent stop (PERSISTS across restarts) and the per-theme focus-ring stops (durable by design — an
 * accessibility/legibility preference). Same `mimir_rewrite_appearance` prefs file as ThemeStore /
 * BackdropStore (already prefetched), same load-once-at-startup + synchronous-write shape.
 *
 * [revision] ticks on every committed change and on every [load], so non-compose consumers can re-apply
 * without polling: today the native ᛗ overlay's theme push (`startCompanionOverlay`). The joycon LED
 * collector it was added for was removed in CF01. A live picker preview does not tick it. Unknown persisted
 * names (a stop renamed/removed in a later build) fall back to the defaults — never a crash.
 *
 * Calm shine (the Utility switch that tones the focus shine down) lives here too: one boolean,
 * loaded with the rest so every window has it from its first frame. It never ticks [revision].
 *
 * The player's backgrounds and Mimir-button mark (concept A) follow the ring stops' shape:
 * `appearanceGround.<THEME> = sha|DIM|BLUR` per theme and `appearanceButtonMark = sha|STRUCK`, decoded through
 * [decodeAppearanceGroundValue] / [decodeAppearanceMarkValue] against the files `AppearanceImageStore` holds, and
 * the default DELETES the key. Both load here, at process start before the companion runtime, so neither screen
 * flashes the default; the committed grounds are prefetched into [MimirSceneGroundImages] off the main thread at
 * the same moment. A mark change ticks [revision], because the ᛗ overlay re-reads its theme on that tick alone; a
 * ground change does not, because the overlay has no ground and a push would re-add its window. The two saved
 * lists ([savedBackgrounds], [savedMarks]) are the store's directories, re-read after every import or removal.
 */
internal object AppearanceOverrideStore {
    private const val TAG = "MimirAppearance"
    private const val PREFS = "mimir_rewrite_appearance"
    private const val KEY_ACCENT = "appearanceAccentStop"
    private const val KEY_RING_PREFIX = "appearanceFocusRing." // + MimirThemeId.name
    private const val KEY_TEMP_PREFIX = "appearanceAccentTemp." // + MimirThemeId.name (CF01, named themes)
    private const val KEY_VIEWPORT_MODE = "layoutViewportMode"
    // A plain boolean: an absent key is the shipped shine, so calm shine is off until the player turns it on.
    private const val KEY_CALM_SHINE = "calmShine"
    internal const val KEY_GROUND_PREFIX = "appearanceGround." // + MimirThemeId.name (concept A, per theme: R1)
    internal const val KEY_BUTTON_MARK = "appearanceButtonMark"

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val _savedBackgrounds = MutableStateFlow<List<AppearanceSavedImage>>(emptyList())

    /** The saved backgrounds, oldest first: the Background picker's tiles between Atmosphere and Add image. */
    val savedBackgrounds: StateFlow<List<AppearanceSavedImage>> = _savedBackgrounds.asStateFlow()

    private val _savedMarks = MutableStateFlow<List<AppearanceSavedImage>>(emptyList())

    /** The saved marks, oldest first: the Mimir-button picker's tiles between the rune and Add image. */
    val savedMarks: StateFlow<List<AppearanceSavedImage>> = _savedMarks.asStateFlow()

    fun load(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Cheap by construction: one directory listing each, empty until the player imports something.
        refreshSavedLists(context)
        MimirAppearanceOverrides.grounds = buildMap {
            MimirThemeId.entries.forEach { themeId ->
                val key = KEY_GROUND_PREFIX + themeId.name
                val decoded = decodeAppearanceGroundValue(prefs.getString(key, null)) ?: return@forEach
                val saved = AppearanceImageStore.find(context, AppearanceImageKind.BACKGROUND, decoded.id)
                if (saved == null) {
                    // The copy and its sidecar are gone (a cleared cache never reaches filesDir; a hand-deleted file
                    // can): the choice cannot be shown or removed from a tile, so it clears itself. Logged, no path.
                    Log.w(TAG, "ground for ${themeId.storageKey} id=${decoded.id} is missing; cleared")
                    prefs.edit().remove(key).apply()
                    return@forEach
                }
                put(themeId, saved.toGround(decoded.dim, decoded.blur))
            }
        }
        MimirSceneGroundImages.prefetch(MimirAppearanceOverrides.grounds.values)
        MimirAppearanceOverrides.buttonMark = decodeAppearanceMarkValue(prefs.getString(KEY_BUTTON_MARK, null))
            ?.let { decoded -> AppearanceImageStore.find(context, AppearanceImageKind.MARK, decoded.id)?.toMark() }
        MimirAppearanceOverrides.accentStop = prefs.getString(KEY_ACCENT, null)
            ?.let { name -> MimirAccentStop.entries.firstOrNull { it.name == name } }
            ?: MimirAccentStop.CLASSIC_GOLD
        MimirAppearanceOverrides.ringStops = buildMap {
            MimirThemeId.entries.forEach { themeId ->
                prefs.getString(KEY_RING_PREFIX + themeId.name, null)
                    ?.let { name -> MimirRingStop.entries.firstOrNull { it.name == name } }
                    ?.takeIf { it != MimirRingStop.THEME_DEFAULT }
                    ?.let { put(themeId, it) }
            }
        }
        MimirAppearanceOverrides.accentTemps = buildMap {
            MimirThemeId.entries.forEach { themeId ->
                prefs.getString(KEY_TEMP_PREFIX + themeId.name, null)
                    ?.let { name -> MimirAccentTemp.entries.firstOrNull { it.name == name } }
                    ?.takeIf { it != MimirAccentTemp.ORIGINAL }
                    ?.let { put(themeId, it) }
            }
        }
        MimirViewportSettings.mode = prefs.getString(KEY_VIEWPORT_MODE, null)
            ?.let { name -> MimirViewportMode.entries.firstOrNull { it.name == name } }
            ?: MimirViewportMode.AUTOMATIC
        MimirCalmShineSettings.enabled = prefs.getBoolean(KEY_CALM_SHINE, false)
        _revision.value += 1
    }

    fun setViewportMode(context: Context, mode: MimirViewportMode) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_VIEWPORT_MODE, mode.name).apply()
        MimirViewportSettings.mode = mode
        _revision.value += 1
    }

    /**
     * Calm shine, Utility's switch.
     * Durable like the ring stops, being the same kind of preference, and in the same file. It does not tick
     * [revision]: only Compose draws the shine, so the companion overlay has nothing to re-apply.
     */
    fun setCalmShine(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CALM_SHINE, enabled).apply()
        MimirCalmShineSettings.enabled = enabled
    }

    fun setAccent(context: Context, stop: MimirAccentStop) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACCENT, stop.name).apply()
        MimirAppearanceOverrides.accentStop = stop
        _revision.value += 1
    }

    /** CF01: persist a NAMED theme's accent temperature; [MimirAccentTemp.ORIGINAL] clears the key. */
    fun setAccentTemp(context: Context, themeId: MimirThemeId, temp: MimirAccentTemp) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (temp == MimirAccentTemp.ORIGINAL) {
            prefs.edit().remove(KEY_TEMP_PREFIX + themeId.name).apply()
            MimirAppearanceOverrides.accentTemps = MimirAppearanceOverrides.accentTemps - themeId
        } else {
            prefs.edit().putString(KEY_TEMP_PREFIX + themeId.name, temp.name).apply()
            MimirAppearanceOverrides.accentTemps =
                MimirAppearanceOverrides.accentTemps + (themeId to temp)
        }
        _revision.value += 1
    }

    fun setRing(context: Context, themeId: MimirThemeId, stop: MimirRingStop) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // "Theme default" IS the reset detent — committing it clears the override, so an absent key
        // always means "authored look" and the map never accumulates no-op entries.
        if (stop == MimirRingStop.THEME_DEFAULT) {
            prefs.edit().remove(KEY_RING_PREFIX + themeId.name).apply()
            MimirAppearanceOverrides.ringStops = MimirAppearanceOverrides.ringStops - themeId
        } else {
            prefs.edit().putString(KEY_RING_PREFIX + themeId.name, stop.name).apply()
            MimirAppearanceOverrides.ringStops = MimirAppearanceOverrides.ringStops + (themeId to stop)
        }
        _revision.value += 1
    }

    /**
     * Commit [themeId]'s background (concept A): null is Atmosphere and DELETES the key. The committed ground is
     * prefetched off the main thread at once, so the shelf's re-bake finds it resident. No [revision] tick: the ᛗ
     * overlay has no ground, and a push would re-add its window for nothing.
     */
    fun setGround(context: Context, themeId: MimirThemeId, ground: MimirSceneGround?) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = KEY_GROUND_PREFIX + themeId.name
        if (ground == null) {
            prefs.edit().remove(key).apply()
            MimirAppearanceOverrides.grounds = MimirAppearanceOverrides.grounds - themeId
        } else {
            prefs.edit().putString(key, encodeAppearanceGroundValue(ground.id, ground.dim, ground.blur)).apply()
            MimirAppearanceOverrides.grounds = MimirAppearanceOverrides.grounds + (themeId to ground)
            MimirSceneGroundImages.prefetch(listOf(ground))
        }
        Log.i(TAG, "ground ${themeId.storageKey}=${ground?.id ?: "atmosphere"} dim=${ground?.dim} blur=${ground?.blur}")
    }

    /** Commit the Mimir-button mark: null is the rune and DELETES the key. Ticks [revision] so the overlay re-reads. */
    fun setButtonMark(context: Context, mark: MimirButtonMark?) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (mark == null) {
            prefs.edit().remove(KEY_BUTTON_MARK).apply()
        } else {
            prefs.edit().putString(KEY_BUTTON_MARK, encodeAppearanceMarkValue(mark.id, mark.style)).apply()
        }
        MimirAppearanceOverrides.buttonMark = mark
        Log.i(TAG, "mark=${mark?.id ?: "rune"}")
        // Atomic: this tick may be taken from any thread, and a lost one would leave the live ᛗ on the mark it had (audit 2026-10-14, W1A #4).
        _revision.update { it + 1 }
    }

    /**
     * Save where the player put the mark [id] and make it the one in use. In the picker's adjust mode A saves the placement AND
     * commits the image, as A on its tile does. The placement goes into that image's sidecar first, the saved list is re-read so its tile
     * brings the new look, and the mark is then committed through [setButtonMark], which ticks [revision] once so the live ᛗ repaints at once.
     *
     * Returns the committed mark (carrying the placement as stored), or null when nothing was saved or committed: the sidecar could not be
     * written, or the image is missing or has no whole copy left. A failed save leaves the committed mark as it was.
     */
    fun saveMarkPlacementAndUse(context: Context, id: String, placement: MimirMarkPlacement): MimirButtonMark? {
        val mark = saveMarkPlacement(context, id, placement) ?: return null
        setButtonMark(context, mark)
        return mark
    }

    /**
     * The file half of [saveMarkPlacementAndUse], for a caller that keeps the commit on the main thread (audit 2026-10-14, W1A #4: the picker takes the file work off the key
     * press, and commits the mark in the same block that writes its own state, so the live ᛗ and the picker's `current` change together). Writes the sidecar, re-reads the saved
     * list, and returns the mark as stored, or null as [saveMarkPlacementAndUse] does. Commits nothing.
     */
    fun saveMarkPlacement(context: Context, id: String, placement: MimirMarkPlacement): MimirButtonMark? {
        if (!AppearanceImageStore.updateMarkPlacement(context, id, placement)) return null
        refreshSavedLists(context)
        return AppearanceImageStore.find(context, AppearanceImageKind.MARK, id)?.takeIf { it.available }?.toMark()
    }

    /** Re-read both saved lists from the store's directories (after an import or a removal, and at load). */
    fun refreshSavedLists(context: Context) {
        _savedBackgrounds.value = AppearanceImageStore.list(context, AppearanceImageKind.BACKGROUND)
        _savedMarks.value = AppearanceImageStore.list(context, AppearanceImageKind.MARK)
    }

    /**
     * Remove a saved background nobody uses: refused (false) while any theme's committed ground names it, so the
     * picker's "Hel uses this image" rule can never be bypassed by a stale list. Drops the decoded copy too.
     */
    fun removeSavedBackground(context: Context, id: String): Boolean {
        if (MimirAppearanceOverrides.grounds.values.any { it.id == id }) return false
        val removed = AppearanceImageStore.remove(context, AppearanceImageKind.BACKGROUND, id)
        MimirSceneGroundImages.forget(id)
        refreshSavedLists(context)
        return removed
    }

    /** Remove a saved mark that is not the committed one; refused (false) while it is. */
    fun removeSavedMark(context: Context, id: String): Boolean {
        if (MimirAppearanceOverrides.buttonMark?.id == id) return false
        val removed = AppearanceImageStore.remove(context, AppearanceImageKind.MARK, id)
        refreshSavedLists(context)
        return removed
    }

    /** The themes whose committed background is [id] -- the picker's "Hel uses this image. Switch Hel first." */
    fun themesUsingBackground(id: String): List<MimirThemeId> =
        MimirAppearanceOverrides.grounds.entries.filter { it.value.id == id }.map { it.key }
}

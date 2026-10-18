package com.gruning.mimir.rewrite

import android.content.Context
import android.os.SystemClock
import android.view.Display
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat

/*
 * F12 (Thor re-check on combined16, 2026-10-08): the floor is recreated when a reclaim finds it gone.
 *
 * After `adb install -r` the floor task (`CompanionSecondaryHomeActivity`) is gone and nothing starts it again: the Bottom shows the
 * last foreign app (Chrome), and the edge-back reclaim logged
 * `floor front requested display=4 reason=overlay_edge_back ... result=failed:floor_task_absent` and drew
 * the Presentation over a live foreign app, with no floor under it to hand that display's keys to Main. A HOME aimed at display 4
 * recreated the floor, so the platform's own SECONDARY_HOME start does work there.
 *
 * [floorFrontPrecheckFailure] still refuses an absent floor with `floor_task_absent`, because that is what the check observed. This
 * file decides what the reclaim does about it: start the floor the way the platform starts a SECONDARY_HOME, the same component-less
 * `MAIN` + `SECONDARY_HOME` intent aimed at the reclaimed display ([companionFloorFrontIntent]), which creates the task when none exists
 * instead of fronting one that does. Every other refusal (the default display, a display that is not the selected valid one, an
 * intent that does not resolve to the floor) still refuses first and never reaches the recreate: with no valid display to aim at, a
 * start has nowhere safe to land.
 *
 * Fail closed. One attempt per reclaim, and none at all within [FloorRecreateWindowMillis] of the last one on that display, so a floor
 * that does not survive its own start can never turn the reclaim into a start loop. A start that throws is logged on the same
 * `floor front requested` line (`result=failed:<exception>`) and the reclaim goes on to present exactly as it does for any other
 * refusal. Nothing here retries.
 *
 * Two more guards keep the recreate off a display where the floor was never meant to run (P2-3, code review 2026-10-08). A recreate needs the floor
 * to have been seen on that display before ([CompanionFloorDisplays], persisted, so it survives the very `install -r` that makes the recreate
 * necessary), because on a presentation display where the platform never places a secondary home the same start could land the floor on display 0, over
 * Main. And the floor itself ends at once when it is created on the default display ([CompanionFloorDisplayGuard], called from its `onCreate`), so
 * even a misrouted start leaves nothing behind.
 */

/** What [floorFrontPrecheckFailure] answers for a floor task that is not running. */
internal const val FloorTaskAbsentRefusal = "floor_task_absent"

/** The refusal for an absent floor whose recreate was already attempted on this display a moment ago. */
internal const val FloorRecreateThrottledRefusal = "recreate_throttled"

/** The refusal for an absent floor on a display where no floor has ever been seen: no evidence the platform places one there. */
internal const val FloorNeverSeenRefusal = "floor_never_seen"

/** How long after one recreate attempt on a display another is refused. A user's reclaims are further apart than this. */
internal const val FloorRecreateWindowMillis = 10_000L

/** What [requestFloorFront][CompanionDisplayController] does for one reclaim: refuse for [refusal], or start (recreating when [recreate]). */
internal data class FloorFrontPlan(val refusal: String?, val recreate: Boolean) {
    /** The `result=` the `floor front requested` line states once the start was accepted. */
    val started: String get() = if (recreate) "started:recreated" else "started"
}

/** The attempts already made, per display. Pure of the clock: the caller supplies the time. */
internal class FloorRecreateThrottle(private val windowMillis: Long = FloorRecreateWindowMillis) {
    private val lastAttemptMillis = HashMap<Int, Long>()

    /** True and records the attempt when none was made on [displayId] within the window before [nowMillis]. */
    @Synchronized
    fun admit(displayId: Int, nowMillis: Long): Boolean {
        val last = lastAttemptMillis[displayId]
        if (last != null && nowMillis - last in 0L until windowMillis) return false
        lastAttemptMillis[displayId] = nowMillis
        return true
    }
}

/** The process's attempts: the floor outlives any one Activity, so does the memory of having tried. */
private val ProcessFloorRecreateThrottle = FloorRecreateThrottle()

/**
 * The plan for one reclaim from the precheck's answer. [precheck] null is a floor that exists: the ordinary start. [FloorTaskAbsentRefusal]
 * alone can become a recreate, and only when [floorSeenBefore] says the floor has run on [displayId] and [throttle] admits it. Any other refusal is
 * returned untouched, and a refusal for a floor never seen spends none of the throttle's budget.
 */
internal fun planFloorFront(
    precheck: String?,
    displayId: Int,
    floorSeenBefore: Boolean,
    throttle: FloorRecreateThrottle = ProcessFloorRecreateThrottle,
    nowMillis: Long = SystemClock.elapsedRealtime(),
): FloorFrontPlan = when {
    precheck != FloorTaskAbsentRefusal -> FloorFrontPlan(precheck, recreate = false)
    !floorSeenBefore -> FloorFrontPlan(FloorNeverSeenRefusal, recreate = false)
    throttle.admit(displayId, nowMillis) -> FloorFrontPlan(null, recreate = true)
    else -> FloorFrontPlan(FloorRecreateThrottledRefusal, recreate = false)
}

/**
 * The floor refuses the default display. A single place for the rule so the activity's `onCreate` is one call; [refuseDefaultDisplay] is the host
 * tests' seam, because Robolectric hosts the floor on its only display, which is the default one.
 */
internal object CompanionFloorDisplayGuard {
    @Volatile
    internal var refuseDefaultDisplay: Boolean = true

    /** True when a floor created on [displayId] must end at once: the default display, never a secondary one and never an unknown one. */
    fun refuses(displayId: Int): Boolean = refuseDefaultDisplay && displayId == Display.DEFAULT_DISPLAY
}

/**
 * The displays a floor has run on, kept in preferences so the memory survives `adb install -r`, which keeps app data. Recorded by the floor
 * itself from `onCreate`, so a display is in the set only because the platform (or a reclaim that worked) really put a floor there.
 * A build that first carries this has nothing recorded: the first update onto it cannot recreate, the second can, once the floor has been
 * created once under it.
 */
internal object CompanionFloorDisplays {
    private const val PreferencesName = "mimir-floor-displays"
    private const val SeenKey = "seen"

    fun seen(context: Context, displayId: Int): Boolean =
        displayId.toString() in preferences(context).getStringSet(SeenKey, emptySet()).orEmpty()

    /** Remembers [displayId]. The default and unknown displays are never recorded: the floor does not belong on them. */
    fun record(context: Context, displayId: Int) {
        if (displayId == Display.DEFAULT_DISPLAY || displayId == BottomUiReceiptFormat.NO_DISPLAY) return
        val preferences = preferences(context)
        val seen = preferences.getStringSet(SeenKey, emptySet()).orEmpty()
        if (displayId.toString() in seen) return
        preferences.edit().putStringSet(SeenKey, seen + displayId.toString()).apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
}

package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.launch.LaunchCoverRule
import com.gruning.mimir.rewrite.core.ui.MimirMotion

/*
 * The launch cover tracks the game's screen becoming visible rather than merely intent dispatch.
 *
 * Dolphin and ARMSX2 dispatch into a startup handler / a boot splash and show the game a moment later. The in-activity
 * intro leaves when Mimir stops, which is when that first screen opens, so the emulator's own loading is visible between
 * the two. The cover is a window that is up BEFORE the intent fires, shows the intro's frame, survives Mimir stopping, and
 * leaves when the game's activity has been on screen for [GameLaunchCoverGameHoldMs].
 *
 * Everything here is pure: a state, the events that can happen to it, the clock as an argument, and a step that says what
 * to do and when to look again. The window, the audio, the scheduler and UsageStats are [GameLaunchCoverController]'s.
 */

/** After the game's activity resumes the cover holds for 0.2 s, then glides out. Same clock as the intro's yield hold. */
internal const val GameLaunchCoverGameHoldMs: Long = GameOpeningIntroYieldHoldMs

/**
 * The ceiling, measured from the YIELD edge (Mimir stopped): the intro's own handoff cap, `GameDispatchPrimaryGraceMs`
 * plus `GameDispatchRecoveryDeadlineMs`, 3 000 + 2 500 + 500 = 6 000 ms. 6 s is the cap
 * the intro already uses, and ARMSX2's boot splash alone ran about 3.4 s from the yield on the walk-32g sample, so 5 s
 * would have left 1.6 s of margin on that one sample and 6 s leaves 2.6 s.
 */
internal const val GameLaunchCoverCeilingMs: Long = GameOpeningIntroHandoffCapMs

/**
 * The ceiling for a cover whose launch never yields, measured from when the window was added. The intro underneath is the
 * same frame, so removing the cover before the yield shows it and nothing else. Without this a launch that throws between
 * the mount and its finish step would leave an opaque window over Mimir with nothing to end it.
 */
internal const val GameLaunchCoverUnyieldedCeilingMs: Long = GameOpeningIntroHandoffCapMs

/**
 * How long the picture takes to leave: the intro's own entrance clock, [GameOpeningIntroEnterMs] (600 ms), so the exit
 * mirrors the way in, blending while gliding. One constant, one source: changing the intro's entrance changes
 * this. The exit is ONE eased clock ([MimirMotion.Easing], as the entrance runs on) that both fades the picture and drifts
 * it. Zero under reduced motion (see [GameLaunchCover]).
 *
 * It replaced a 160 ms page glide at full opacity and, before that, a
 * 180 ms dissolve on [MimirMotion.OverlayMs].
 */
internal const val GameLaunchCoverGlideMs: Long = GameOpeningIntroEnterMs.toLong()

/**
 * How far the picture drifts while it fades, as a fraction of the screen's width (height, for an up or down exit): a
 * quarter. A blend, not a curtain: the picture is gone (alpha 0) before it could leave the screen, and the game shows
 * through as it goes. The distance and clock remain subject to visual tuning on device; it is the one place the distance is named.
 */
internal const val GameLaunchCoverGlideDriftFraction: Float = 0.25f

/** The edge the picture drifts toward. */
internal enum class GameLaunchCoverGlideEdge(private val signX: Float, private val signY: Float) {
    LEFT(-1f, 0f),
    RIGHT(1f, 0f),
    UP(0f, -1f),
    DOWN(0f, 1f),
    ;

    /** The direction of the drift as signs on x and y: LEFT is toward negative x. */
    fun offsetSigns(): Pair<Float, Float> = signX to signY
}

/**
 * THE ONE PLACE to change where the cover leaves to. The cover glides off to the left.
 * To change its exit direction, change this line: the presenter reads nothing else.
 */
internal val GameLaunchCoverGlideExit: GameLaunchCoverGlideEdge = GameLaunchCoverGlideEdge.LEFT

/**
 * Where the picture is, in pixels from its resting place, when the exit is [progress] (0 at rest, 1 at the end, already
 * eased) of the way through: [fraction] of the window's width along x (or of its height along y) toward [edge].
 */
internal fun gameLaunchCoverGlideOffsetPx(
    edge: GameLaunchCoverGlideEdge,
    progress: Float,
    widthPx: Float,
    heightPx: Float,
    fraction: Float = GameLaunchCoverGlideDriftFraction,
): Pair<Float, Float> {
    val (signX, signY) = edge.offsetSigns()
    return (signX * widthPx * fraction * progress) to (signY * heightPx * fraction * progress)
}

/**
 * The picture's opacity when the exit is [progress] (already eased) of the way through: 1 at rest and exactly 0 at the
 * end, on the same clock as [gameLaunchCoverGlideOffsetPx], so nothing of it is left to show when the window is removed.
 */
internal fun gameLaunchCoverGlideAlpha(progress: Float): Float = (1f - progress).coerceIn(0f, 1f)

/**
 * The longest the dispatch waits for the cover's first visible frame. The intent is not held for a cover that cannot
 * draw: past this the launch goes, and the cover reveals itself when it can.
 */
internal const val GameLaunchCoverRevealWaitMs: Long = 250L

/** Frames the cover's own composition runs transparent before it shows itself, so its logo painter has landed. */
internal const val GameLaunchCoverSettleFrames: Int = 2

internal data class GameLaunchCoverTiming(
    val gameHoldMs: Long = GameLaunchCoverGameHoldMs,
    val ceilingMs: Long = GameLaunchCoverCeilingMs,
    val unyieldedCeilingMs: Long = GameLaunchCoverUnyieldedCeilingMs,
    val glideMs: Long = GameLaunchCoverGlideMs,
)

/** What ended the cover; [logName] is the `via=` of the `cover end` line. */
internal enum class GameLaunchCoverEnd(val logName: String) {
    /** (a) Mimir started again: the player came back. Removed synchronously, no glide, so it never tails over Mimir. */
    ON_START("onStart"),

    /** (b) An activity outside the profile's dispatch/game pair resumed: an error dialog, the emulator's menu, another app. */
    FOREIGN("foreign"),

    /** (c) The game's activity resumed and [GameLaunchCoverTiming.gameHoldMs] passed; the cover glided out. */
    GAME("game"),

    /** (d) The ceiling: nothing ended it in time. Glided out after the yield's ceiling, cut before a yield. */
    CEILING("ceiling"),

    /** The launch never took Mimir's screen: the dispatch failed, the watchdog spoke, or the intro's own cap passed. */
    SETTLED("settled"),

    /** The window was torn down by its owner. */
    TEARDOWN("teardown"),
}

internal sealed interface GameLaunchCoverEvent {
    /** `MainActivity.onStart`. */
    data object HostStarted : GameLaunchCoverEvent

    /** `MainActivity.onStop`, not for a configuration change: Mimir ceded the screen. */
    data object Yielded : GameLaunchCoverEvent

    /** UsageStats saw [packageName]/[className] resume at [eventAtMs] (wall clock). */
    data class ActivityResumed(
        val packageName: String,
        val className: String,
        val eventAtMs: Long,
    ) : GameLaunchCoverEvent

    /** The launch did not take Mimir's screen. */
    data object Settled : GameLaunchCoverEvent

    data object Teardown : GameLaunchCoverEvent

    /** Time passing: a scheduled wake-up. */
    data object Tick : GameLaunchCoverEvent
}

internal data class GameLaunchCoverGlide(val end: GameLaunchCoverEnd, val startedAtMs: Long)

/**
 * @property armedAtMs when the window was added (monotonic).
 * @property armedAtEpochMs the same moment on the wall clock, which is UsageStats' clock: an observation older than
 *   this is from a previous launch and is ignored.
 * @property yieldedAtMs when Mimir stopped, the start of the ceiling. Null until then.
 * @property gameSeenAtMs when the game's activity was first observed, the start of the hold. The observation's own time,
 *   so the lag UsageStats adds is inside the hold and not in front of it.
 */
internal data class GameLaunchCoverState(
    val rule: LaunchCoverRule,
    val armedAtMs: Long,
    val armedAtEpochMs: Long,
    val yieldedAtMs: Long? = null,
    val gameSeenAtMs: Long? = null,
    val glide: GameLaunchCoverGlide? = null,
)

internal sealed interface GameLaunchCoverAction {
    data object None : GameLaunchCoverAction

    /** Start the exit (the drift and the fade are one clock) and the audio's fade, and remove when it ends. */
    data class BeginGlide(val end: GameLaunchCoverEnd) : GameLaunchCoverAction

    /** Take the window away now, and release the audio. */
    data class Remove(val end: GameLaunchCoverEnd) : GameLaunchCoverAction
}

/** [wakeAtMs] is the next monotonic moment the machine wants a [GameLaunchCoverEvent.Tick], or null if it wants none. */
internal data class GameLaunchCoverStep(
    val state: GameLaunchCoverState,
    val action: GameLaunchCoverAction = GameLaunchCoverAction.None,
    val wakeAtMs: Long? = null,
)

internal object GameLaunchCoverMachine {
    fun arm(rule: LaunchCoverRule, nowMs: Long, nowEpochMs: Long): GameLaunchCoverState =
        GameLaunchCoverState(rule = rule, armedAtMs = nowMs, armedAtEpochMs = nowEpochMs)

    /**
     * The one transition. Highest priority first, and every interrupt ends the cover whatever it was doing, a glide
     * included:
     *
     *  (a) [GameLaunchCoverEvent.HostStarted]: removed at once.
     *  (b) a resume outside the profile's {dispatch, game} pair: removed at once.
     *  (c) the game's activity resumed: hold [GameLaunchCoverTiming.gameHoldMs], then glide out, then remove.
     *  (d) the ceiling: [GameLaunchCoverTiming.ceilingMs] after the yield, then glide out and remove.
     *
     * [GameLaunchCoverEvent.Settled] and [GameLaunchCoverEvent.Teardown] are the owner's own ends and rank with (a) and
     * (b): nothing they interrupt is worth finishing. (c) outranks (d) when both are due in the same tick.
     */
    fun reduce(
        state: GameLaunchCoverState,
        event: GameLaunchCoverEvent,
        nowMs: Long,
        timing: GameLaunchCoverTiming = GameLaunchCoverTiming(),
    ): GameLaunchCoverStep {
        when (event) {
            GameLaunchCoverEvent.HostStarted -> return remove(state, GameLaunchCoverEnd.ON_START)
            is GameLaunchCoverEvent.ActivityResumed ->
                if (!isStale(state, event) && isForeign(state.rule, event)) {
                    return remove(state, GameLaunchCoverEnd.FOREIGN)
                }
            GameLaunchCoverEvent.Settled -> return remove(state, GameLaunchCoverEnd.SETTLED)
            GameLaunchCoverEvent.Teardown -> return remove(state, GameLaunchCoverEnd.TEARDOWN)
            GameLaunchCoverEvent.Yielded,
            GameLaunchCoverEvent.Tick,
            -> Unit
        }
        var next = state
        when (event) {
            GameLaunchCoverEvent.Yielded ->
                if (next.yieldedAtMs == null) next = next.copy(yieldedAtMs = nowMs)
            is GameLaunchCoverEvent.ActivityResumed ->
                if (!isStale(state, event) &&
                    event.className == state.rule.gameActivity &&
                    next.gameSeenAtMs == null
                ) {
                    next = next.copy(gameSeenAtMs = nowMs)
                }
            else -> Unit
        }
        return settle(next, nowMs, timing)
    }

    /** What is due at [nowMs], and when to look again. Also the answer for a state that nothing has happened to. */
    fun settle(state: GameLaunchCoverState, nowMs: Long, timing: GameLaunchCoverTiming = GameLaunchCoverTiming()): GameLaunchCoverStep {
        state.glide?.let { glide ->
            val glideEndsAtMs = glide.startedAtMs + timing.glideMs
            return if (nowMs >= glideEndsAtMs) {
                remove(state, glide.end)
            } else {
                GameLaunchCoverStep(state, wakeAtMs = glideEndsAtMs)
            }
        }
        val holdEndsAtMs = state.gameSeenAtMs?.let { it + timing.gameHoldMs }
        val ceilingAtMs = state.yieldedAtMs?.let { it + timing.ceilingMs }
        val unyieldedCeilingAtMs = if (state.yieldedAtMs == null) state.armedAtMs + timing.unyieldedCeilingMs else null
        if (holdEndsAtMs != null && nowMs >= holdEndsAtMs) return beginGlide(state, GameLaunchCoverEnd.GAME, nowMs, timing)
        if (ceilingAtMs != null && nowMs >= ceilingAtMs) return beginGlide(state, GameLaunchCoverEnd.CEILING, nowMs, timing)
        if (unyieldedCeilingAtMs != null && nowMs >= unyieldedCeilingAtMs) {
            // No yield, so Mimir is still on screen and the intro under the cover is the same frame: cut, do not glide.
            return remove(state, GameLaunchCoverEnd.CEILING)
        }
        return GameLaunchCoverStep(state, wakeAtMs = listOfNotNull(holdEndsAtMs, ceilingAtMs, unyieldedCeilingAtMs).minOrNull())
    }

    private fun beginGlide(
        state: GameLaunchCoverState,
        end: GameLaunchCoverEnd,
        nowMs: Long,
        timing: GameLaunchCoverTiming,
    ): GameLaunchCoverStep {
        if (timing.glideMs <= 0L) return remove(state, end)
        return GameLaunchCoverStep(
            state = state.copy(glide = GameLaunchCoverGlide(end, nowMs)),
            action = GameLaunchCoverAction.BeginGlide(end),
            wakeAtMs = nowMs + timing.glideMs,
        )
    }

    private fun remove(state: GameLaunchCoverState, end: GameLaunchCoverEnd) =
        GameLaunchCoverStep(state, GameLaunchCoverAction.Remove(end))

    /** UsageStats' replay of a resume from before this cover began (a previous launch's) says nothing about this one. */
    private fun isStale(state: GameLaunchCoverState, event: GameLaunchCoverEvent.ActivityResumed): Boolean =
        event.eventAtMs < state.armedAtEpochMs

    /** Only the profile's own dispatch activity and game activity are expected; anything else is someone else's screen. */
    private fun isForeign(rule: LaunchCoverRule, event: GameLaunchCoverEvent.ActivityResumed): Boolean =
        !(event.packageName == rule.packageName &&
            (event.className == rule.dispatchActivity || event.className == rule.gameActivity))
}

/** Why a launch that has a cover rule gets no cover; [logName] is the `reason=` of the `cover skip` line. */
internal enum class GameLaunchCoverSkip(val logName: String) {
    /** The profile names no game activity: every emulator but Dolphin and ARMSX2. Not logged, there is nothing to say. */
    NO_RULE("no-rule"),
    SECONDARY_DISPLAY("secondary-display"),
    INTRO_NOT_RUNNING("intro-not-running"),
    NO_OVERLAY_PERMISSION("no-overlay-permission"),
    NO_USAGE_ACCESS("no-usage-access"),
    HERO_NOT_LOADED("hero-not-loaded"),
}

/**
 * The cover is added only when the launch has a rule AND the intro is actually running (so the secondary-display skip the
 * intro has carries over untouched: [gameOpeningLaunchTransitionAllowed]) AND Mimir may draw over other apps AND Usage
 * Access is granted (without it nothing could end the cover on the game) AND the intro's hero is loaded (the cover draws
 * exactly that bitmap; a cover drawn without it would be a different frame from the one under it). Anything missing is
 * today's behaviour, unchanged.
 */
internal fun gameLaunchCoverSkipReason(
    hasRule: Boolean,
    secondaryDisplayId: Int?,
    introRunning: Boolean,
    canDrawOverlays: Boolean,
    usageAccessGranted: Boolean,
    heroLoaded: Boolean,
): GameLaunchCoverSkip? = when {
    !hasRule -> GameLaunchCoverSkip.NO_RULE
    !gameOpeningLaunchTransitionAllowed(secondaryDisplayId) -> GameLaunchCoverSkip.SECONDARY_DISPLAY
    !introRunning -> GameLaunchCoverSkip.INTRO_NOT_RUNNING
    !canDrawOverlays -> GameLaunchCoverSkip.NO_OVERLAY_PERMISSION
    !usageAccessGranted -> GameLaunchCoverSkip.NO_USAGE_ACCESS
    !heroLoaded -> GameLaunchCoverSkip.HERO_NOT_LOADED
    else -> null
}

package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.launch.LaunchCoverRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The launch cover's state machine. Pure: a state, the events that happen to it, a clock passed in. Every end condition and its priority is here;
 * the window, the audio and UsageStats are [GameLaunchCoverControllerTest]'s.
 *
 * Times are monotonic milliseconds from an arbitrary zero; event stamps are wall-clock milliseconds, as UsageStats' are.
 */
class GameLaunchCoverMachineTest {
    private val rule = LaunchCoverRule(
        packageName = "com.armsx2",
        dispatchActivity = "com.armsx2.BootSplashActivity",
        gameActivity = "com.armsx2.Main",
    )
    private val timing = GameLaunchCoverTiming()

    private val splash = GameLaunchCoverEvent.ActivityResumed("com.armsx2", "com.armsx2.BootSplashActivity", eventAtMs = EPOCH + 10)

    private fun game(eventAtMs: Long = EPOCH + 10) =
        GameLaunchCoverEvent.ActivityResumed("com.armsx2", "com.armsx2.Main", eventAtMs)

    // ---- the named constants are the documented timing bounds

    @Test
    fun `the hold is the intro's own 0_2 s, the ceiling its 6 s cap, the blend the intro's entrance clock`() {
        assertEquals(200L, GameLaunchCoverGameHoldMs)
        assertEquals(GameOpeningIntroYieldHoldMs, GameLaunchCoverGameHoldMs)
        assertEquals("the owner was told about 5 s; 6 s is the intro's cap: 3 000 + 2 500 + 500", 6_000L, GameLaunchCoverCeilingMs)
        assertEquals(GameOpeningIntroHandoffCapMs, GameLaunchCoverCeilingMs)
        assertEquals(GameOpeningIntroHandoffCapMs, GameLaunchCoverUnyieldedCeilingMs)
        assertEquals(GameOpeningIntroEnterMs.toLong(), GameLaunchCoverGlideMs)
    }

    // ---- the exit is a blend: one eased clock fades the picture and drifts it, toward one named edge

    @Test
    fun `the blend runs on the intro's own entrance clock and drifts a quarter of the window`() {
        assertEquals(GameOpeningIntroEnterMs.toLong(), GameLaunchCoverGlideMs)
        assertEquals("the owner was told 600 ms, the intro's entrance", 600L, GameLaunchCoverGlideMs)
        assertEquals(0.25f, GameLaunchCoverGlideDriftFraction, 0f)
    }

    @Test
    fun `the blend drifts to the left, and that is one named constant`() {
        assertEquals(GameLaunchCoverGlideEdge.LEFT, GameLaunchCoverGlideExit)
        assertEquals(-1f to 0f, GameLaunchCoverGlideEdge.LEFT.offsetSigns())
        assertEquals(1f to 0f, GameLaunchCoverGlideEdge.RIGHT.offsetSigns())
        assertEquals(0f to -1f, GameLaunchCoverGlideEdge.UP.offsetSigns())
        assertEquals(0f to 1f, GameLaunchCoverGlideEdge.DOWN.offsetSigns())
    }

    @Test
    fun `the drift is the fraction times the window's width or height along the named edge, scaled by the progress`() {
        val width = 1_920f
        val height = 1_080f
        val fraction = GameLaunchCoverGlideDriftFraction

        fun offset(edge: GameLaunchCoverGlideEdge, progress: Float) =
            gameLaunchCoverGlideOffsetPx(edge, progress, width, height)

        // Compared with a zero delta, which treats -0 and 0 as equal: a sign times zero progress is a negative zero.
        fun assertOffset(message: String, x: Float, y: Float, actual: Pair<Float, Float>) {
            assertEquals("$message (x)", x, actual.first, 0f)
            assertEquals("$message (y)", y, actual.second, 0f)
        }

        assertOffset("at rest", 0f, 0f, offset(GameLaunchCoverGlideEdge.LEFT, 0f))
        assertOffset("half way", -(width * fraction * 0.5f), 0f, offset(GameLaunchCoverGlideEdge.LEFT, 0.5f))
        assertOffset("at the end it is the fraction of the width, and no more", -(width * fraction), 0f, offset(GameLaunchCoverGlideEdge.LEFT, 1f))
        assertOffset("a quarter of 1 920 is 480", -480f, 0f, offset(GameLaunchCoverGlideEdge.LEFT, 1f))
        assertOffset("up moves along y by the fraction of the height", 0f, -(height * fraction * 0.5f), offset(GameLaunchCoverGlideEdge.UP, 0.5f))
        assertOffset("right", width * fraction, 0f, offset(GameLaunchCoverGlideEdge.RIGHT, 1f))
        assertOffset("down", 0f, height * fraction, offset(GameLaunchCoverGlideEdge.DOWN, 1f))
        assertOffset(
            "the fraction is the parameter the owner tunes",
            -(width * 0.5f),
            0f,
            gameLaunchCoverGlideOffsetPx(GameLaunchCoverGlideEdge.LEFT, 1f, width, height, fraction = 0.5f),
        )
    }

    @Test
    fun `the picture's alpha is 1 at rest and exactly 0 at the end, on the same progress as the drift`() {
        assertEquals(1f, gameLaunchCoverGlideAlpha(0f), 0f)
        assertEquals(0.5f, gameLaunchCoverGlideAlpha(0.5f), 0f)
        assertEquals("exactly 0 at the end, so nothing of it is left when the window goes", 0f, gameLaunchCoverGlideAlpha(1f), 0f)
        assertEquals("an easing that overshot cannot take it past 0", 0f, gameLaunchCoverGlideAlpha(1.2f), 0f)
        assertEquals("nor past 1", 1f, gameLaunchCoverGlideAlpha(-0.1f), 0f)

        val end = gameLaunchCoverGlideOffsetPx(GameLaunchCoverGlideExit, 1f, 1_000f, 500f)
        assertEquals("at the end it has drifted its whole fraction", -250f, end.first, 0f)
        assertEquals("and is invisible", 0f, gameLaunchCoverGlideAlpha(1f), 0f)
    }

    @Test
    fun `the blend's step names the glide and wakes exactly when it ends`() {
        val armed = GameLaunchCoverMachine.arm(rule, nowMs = 1_000L, nowEpochMs = EPOCH)
        val yielded = GameLaunchCoverMachine.reduce(armed, GameLaunchCoverEvent.Yielded, 1_000L, timing).state
        val seen = GameLaunchCoverMachine.reduce(yielded, game(), 1_100L, timing).state

        val step = GameLaunchCoverMachine.settle(seen, 1_100L + GameLaunchCoverGameHoldMs, timing)

        assertEquals(GameLaunchCoverAction.BeginGlide(GameLaunchCoverEnd.GAME), step.action)
        assertEquals(1_100L + GameLaunchCoverGameHoldMs + GameLaunchCoverGlideMs, step.wakeAtMs)
    }

    @Test
    fun `the window is removed exactly when the blend ends, not before and with no tail after it`() {
        val sim = Sim()
        sim.send(GameLaunchCoverEvent.Yielded)
        sim.send(game())
        sim.advanceTo(1_000L + GameLaunchCoverGameHoldMs)
        assertEquals(GameLaunchCoverEnd.GAME, sim.glideEnd)

        sim.advanceTo(1_000L + GameLaunchCoverGameHoldMs + GameLaunchCoverGlideMs - 1L)
        assertNull("one millisecond short: still blending", sim.ended)

        sim.advanceTo(1_000L + GameLaunchCoverGameHoldMs + GameLaunchCoverGlideMs)
        assertEquals("the picture is at alpha 0 by now", GameLaunchCoverEnd.GAME, sim.ended)
    }

    @Test
    fun `the ceiling blends out the same way`() {
        val sim = Sim()
        sim.send(GameLaunchCoverEvent.Yielded)
        sim.advanceTo(1_000L + GameLaunchCoverCeilingMs)
        assertEquals(GameLaunchCoverEnd.CEILING, sim.glideEnd)

        sim.advanceTo(1_000L + GameLaunchCoverCeilingMs + GameLaunchCoverGlideMs - 1L)
        assertNull(sim.ended)
        sim.advanceTo(1_000L + GameLaunchCoverCeilingMs + GameLaunchCoverGlideMs)
        assertEquals(GameLaunchCoverEnd.CEILING, sim.ended)
    }

    @Test
    fun `onStart and a foreign activity never glide, they remove at once`() {
        val hostStarted = Sim().also { it.send(GameLaunchCoverEvent.HostStarted) }
        assertEquals(GameLaunchCoverEnd.ON_START, hostStarted.ended)
        assertNull(hostStarted.glideEnd)

        val foreign = Sim().also {
            it.send(GameLaunchCoverEvent.ActivityResumed("com.someone.else", "com.someone.else.Main", eventAtMs = EPOCH + 10))
        }
        assertEquals(GameLaunchCoverEnd.FOREIGN, foreign.ended)
        assertNull(foreign.glideEnd)
    }

    // ---- (c) the game's activity resumed

    @Test
    fun `the game resuming holds 0_2 s, then glides out, then removes`() {
        val sim = Sim()
        sim.send(GameLaunchCoverEvent.Yielded)
        sim.advanceTo(1_500L)

        sim.send(game())
        assertNull(sim.ended)
        sim.advanceTo(1_500L + GameLaunchCoverGameHoldMs - 1L)
        assertNull("still holding one millisecond short", sim.glideStartedAt)

        sim.advanceTo(1_500L + GameLaunchCoverGameHoldMs)
        assertEquals(GameLaunchCoverEnd.GAME, sim.glideEnd)
        assertEquals(1_500L + GameLaunchCoverGameHoldMs, sim.glideStartedAt)
        assertNull("gliding, not yet removed", sim.ended)

        sim.advanceTo(1_500L + GameLaunchCoverGameHoldMs + GameLaunchCoverGlideMs - 1L)
        assertNull(sim.ended)
        sim.advanceTo(1_500L + GameLaunchCoverGameHoldMs + GameLaunchCoverGlideMs)
        assertEquals(GameLaunchCoverEnd.GAME, sim.ended)
    }

    @Test
    fun `the hold runs from when the game was observed, so UsageStats' lag is inside it and not in front of it`() {
        val sim = Sim()
        sim.send(GameLaunchCoverEvent.Yielded)
        sim.advanceTo(2_000L)

        // The resume was stamped 300 ms before the poll that saw it: monotonic 2 000 is wall clock EPOCH + 1 000, so 700.
        sim.send(game(eventAtMs = EPOCH + 700L))
        sim.advanceTo(2_000L + GameLaunchCoverGameHoldMs)

        assertEquals("glide begins 0.2 s after the observation, not 0.2 s after the resume", 2_200L, sim.glideStartedAt)
    }

    @Test
    fun `a game seen before the yield still holds and glides out, as Dolphin's does`() {
        // Dolphin's EmulationActivity is displayed a moment BEFORE Mimir's onStop (walk-32g: 0.28 s).
        val sim = Sim()
        sim.advanceTo(1_800L)
        sim.send(game())
        sim.advanceTo(2_000L)
        assertEquals("the hold does not wait for the yield", GameLaunchCoverEnd.GAME, sim.glideEnd)
        assertEquals(2_000L, sim.glideStartedAt)

        sim.send(GameLaunchCoverEvent.Yielded) // Mimir stops mid-glide: nothing changes
        sim.advanceTo(2_000L + GameLaunchCoverGlideMs)
        assertEquals(GameLaunchCoverEnd.GAME, sim.ended)
    }

    @Test
    fun `the game resuming again does not move the hold`() {
        val sim = Sim()
        sim.send(game())
        sim.advanceTo(1_100L)
        sim.send(game(eventAtMs = EPOCH + 100L))
        sim.advanceTo(1_000L + GameLaunchCoverGameHoldMs)
        assertEquals(1_200L, sim.glideStartedAt)
    }

    @Test
    fun `the dispatch activity resuming is expected, ends nothing and starts no hold`() {
        val sim = Sim()
        sim.send(splash)
        sim.send(GameLaunchCoverEvent.Yielded)
        sim.advanceTo(3_000L)

        assertNull(sim.ended)
        assertNull(sim.glideStartedAt)
    }

    // ---- (d) the ceiling

    @Test
    fun `the ceiling is 6 s from the yield edge, then it glides out and removes`() {
        val sim = Sim()
        sim.advanceTo(2_000L)
        sim.send(GameLaunchCoverEvent.Yielded)

        sim.advanceTo(2_000L + GameLaunchCoverCeilingMs - 1L)
        assertNull(sim.glideStartedAt)

        sim.advanceTo(2_000L + GameLaunchCoverCeilingMs)
        assertEquals(GameLaunchCoverEnd.CEILING, sim.glideEnd)
        assertNull(sim.ended)

        sim.advanceTo(2_000L + GameLaunchCoverCeilingMs + GameLaunchCoverGlideMs)
        assertEquals(GameLaunchCoverEnd.CEILING, sim.ended)
    }

    @Test
    fun `the ceiling is measured from the yield and not from the mount, and the first yield wins`() {
        val sim = Sim()
        sim.advanceTo(4_000L)
        sim.send(GameLaunchCoverEvent.Yielded)
        sim.advanceTo(5_000L)
        sim.send(GameLaunchCoverEvent.Yielded) // a second edge (onStop and the intro's finish step both report)

        sim.advanceTo(1_000L + GameLaunchCoverCeilingMs + 500L)
        assertNull("the mount's own 6 s has passed but the yield's has not", sim.glideStartedAt)

        sim.advanceTo(4_000L + GameLaunchCoverCeilingMs)
        assertEquals(4_000L + GameLaunchCoverCeilingMs, sim.glideStartedAt)
    }

    @Test
    fun `the game outranks the ceiling when both are due in the same tick`() {
        val sim = Sim()
        sim.send(GameLaunchCoverEvent.Yielded) // at 1 000; ceiling due at 7 000
        sim.advanceTo(1_000L + GameLaunchCoverCeilingMs - GameLaunchCoverGameHoldMs)
        sim.send(game()) // hold ends exactly at the ceiling
        sim.advanceTo(1_000L + GameLaunchCoverCeilingMs)

        assertEquals(GameLaunchCoverEnd.GAME, sim.glideEnd)
    }

    @Test
    fun `a launch that never yields is cut at the mount's own ceiling, not glided, because Mimir's intro is under it`() {
        val sim = Sim()
        sim.advanceTo(1_000L + GameLaunchCoverUnyieldedCeilingMs - 1L)
        assertNull(sim.ended)

        sim.advanceTo(1_000L + GameLaunchCoverUnyieldedCeilingMs)

        assertEquals(GameLaunchCoverEnd.CEILING, sim.ended)
        assertNull("no glide: the intro under the cover is the same frame", sim.glideStartedAt)
    }

    // ---- (b) anything outside the pair

    @Test
    fun `an activity outside the pair ends the cover at once, in every state`() {
        val strangers = listOf(
            GameLaunchCoverEvent.ActivityResumed("com.armsx2", "com.armsx2.SettingsActivity", EPOCH + 50L),
            GameLaunchCoverEvent.ActivityResumed("org.dolphinemu.dolphinemu", "com.armsx2.Main", EPOCH + 50L),
            GameLaunchCoverEvent.ActivityResumed("com.android.settings", "com.android.settings.Settings", EPOCH + 50L),
        )
        for (stranger in strangers) {
            for (setup in states()) {
                val sim = Sim()
                setup.second(sim)
                sim.send(stranger)
                assertEquals("${setup.first} / $stranger", GameLaunchCoverEnd.FOREIGN, sim.ended)
            }
        }
    }

    @Test
    fun `a foreign screen during the 0_2 s hold ends the cover at once`() {
        val sim = Sim()
        sim.send(game())
        sim.advanceTo(1_100L) // mid-hold
        sim.send(GameLaunchCoverEvent.ActivityResumed("com.armsx2", "com.armsx2.ErrorDialog", EPOCH + 100L))

        assertEquals(GameLaunchCoverEnd.FOREIGN, sim.ended)
        assertNull("it was cut in the hold, never glided", sim.glideStartedAt)
    }

    @Test
    fun `a stale observation from a previous launch neither ends nor holds this one`() {
        val sim = Sim()
        sim.send(GameLaunchCoverEvent.Yielded)
        // UsageStats' replay of the last launch: a stranger and this very game, both stamped before the mount.
        sim.send(GameLaunchCoverEvent.ActivityResumed("com.android.settings", "x.Y", eventAtMs = EPOCH - 1L))
        sim.send(game(eventAtMs = EPOCH - 1L))
        sim.advanceTo(1_000L + GameLaunchCoverGameHoldMs + 10L)

        assertNull(sim.ended)
        assertNull("a stale game resume starts no hold", sim.glideStartedAt)
    }

    // ---- (a) the player came back

    @Test
    fun `Mimir starting ends the cover at once, in every state, whatever else is going on`() {
        for (setup in states()) {
            val sim = Sim()
            setup.second(sim)
            sim.send(GameLaunchCoverEvent.HostStarted)
            assertEquals(setup.first, GameLaunchCoverEnd.ON_START, sim.ended)
        }
    }

    @Test
    fun `Mimir starting mid-glide removes at once and does not wait out the glide`() {
        val sim = Sim()
        sim.send(GameLaunchCoverEvent.Yielded)
        sim.send(game())
        sim.advanceTo(1_000L + GameLaunchCoverGameHoldMs + 10L) // the glide has begun
        assertEquals(GameLaunchCoverEnd.GAME, sim.glideEnd)
        assertNull(sim.ended)

        sim.send(GameLaunchCoverEvent.HostStarted)

        assertEquals(GameLaunchCoverEnd.ON_START, sim.ended)
    }

    @Test
    fun `an interrupt beats a deadline that is already due but whose tick has not run`() {
        // The hold ended at 1 200 and the wake-up is late; the player's return (a) and a stranger (b) must each still end
        // the cover as themselves, not let it glide as if the game had finished it.
        val returning = Sim()
        returning.send(GameLaunchCoverEvent.Yielded)
        returning.send(game())
        returning.now = 1_300L
        returning.send(GameLaunchCoverEvent.HostStarted)
        assertEquals(GameLaunchCoverEnd.ON_START, returning.ended)
        assertNull(returning.glideStartedAt)

        val stranger = Sim()
        stranger.send(GameLaunchCoverEvent.Yielded)
        stranger.send(game())
        stranger.now = 1_300L
        stranger.send(GameLaunchCoverEvent.ActivityResumed("com.android.settings", "x.Y", EPOCH + 300L))
        assertEquals(GameLaunchCoverEnd.FOREIGN, stranger.ended)
        assertNull(stranger.glideStartedAt)
    }

    @Test
    fun `a cover that has ended takes no more events`() {
        val sim = Sim()
        sim.send(GameLaunchCoverEvent.HostStarted)
        assertEquals(GameLaunchCoverEnd.ON_START, sim.ended)
        assertNull(sim.state)

        sim.send(GameLaunchCoverEvent.ActivityResumed("com.android.settings", "x.Y", EPOCH + 300L))
        assertEquals("the first end stands", GameLaunchCoverEnd.ON_START, sim.ended)
    }

    // ---- the owner's own ends

    @Test
    fun `a launch that never took Mimir's screen ends the cover at once, mid-glide included`() {
        for (setup in states()) {
            val sim = Sim()
            setup.second(sim)
            sim.send(GameLaunchCoverEvent.Settled)
            assertEquals(setup.first, GameLaunchCoverEnd.SETTLED, sim.ended)
        }
    }

    @Test
    fun `a teardown ends the cover at once`() {
        val sim = Sim()
        sim.send(GameLaunchCoverEvent.Yielded)
        sim.send(GameLaunchCoverEvent.Teardown)
        assertEquals(GameLaunchCoverEnd.TEARDOWN, sim.ended)
    }

    // ---- reduced motion

    @Test
    fun `with no glide, reduced motion, the game's hold ends in a removal and not a glide`() {
        val sim = Sim(GameLaunchCoverTiming(glideMs = 0L))
        sim.send(GameLaunchCoverEvent.Yielded)
        sim.send(game())
        sim.advanceTo(1_000L + GameLaunchCoverGameHoldMs)

        assertEquals(GameLaunchCoverEnd.GAME, sim.ended)
        assertNull(sim.glideStartedAt)
    }

    @Test
    fun `with no glide, the ceiling removes too`() {
        val sim = Sim(GameLaunchCoverTiming(glideMs = 0L))
        sim.send(GameLaunchCoverEvent.Yielded)
        sim.advanceTo(1_000L + GameLaunchCoverCeilingMs)

        assertEquals(GameLaunchCoverEnd.CEILING, sim.ended)
        assertNull(sim.glideStartedAt)
    }

    // ---- the gate

    @Test
    fun `the cover is added only when every condition holds, and the first missing one is the reason`() {
        fun reason(
            hasRule: Boolean = true,
            secondaryDisplayId: Int? = null,
            introRunning: Boolean = true,
            canDrawOverlays: Boolean = true,
            usageAccessGranted: Boolean = true,
            heroLoaded: Boolean = true,
        ) = gameLaunchCoverSkipReason(hasRule, secondaryDisplayId, introRunning, canDrawOverlays, usageAccessGranted, heroLoaded)

        assertNull("everything holds", reason())
        assertEquals(GameLaunchCoverSkip.NO_RULE, reason(hasRule = false))
        assertEquals(
            "the Thor's secondary-display skip carries over untouched",
            GameLaunchCoverSkip.SECONDARY_DISPLAY,
            reason(secondaryDisplayId = 4),
        )
        assertEquals(GameLaunchCoverSkip.INTRO_NOT_RUNNING, reason(introRunning = false))
        assertEquals(GameLaunchCoverSkip.NO_OVERLAY_PERMISSION, reason(canDrawOverlays = false))
        assertEquals(GameLaunchCoverSkip.NO_USAGE_ACCESS, reason(usageAccessGranted = false))
        assertEquals(GameLaunchCoverSkip.HERO_NOT_LOADED, reason(heroLoaded = false))
        // A rule with nothing else is still no cover: the rule is necessary, not sufficient.
        assertEquals(
            GameLaunchCoverSkip.NO_RULE,
            reason(hasRule = false, secondaryDisplayId = 4, introRunning = false, canDrawOverlays = false),
        )
    }

    @Test
    fun `the gate's secondary-display answer is the intro's own`() {
        // The one predicate, not a copy: a change to when the intro skips is a change to when the cover does.
        for (displayId in listOf<Int?>(null, 0, 1, 4)) {
            val skipped = gameLaunchCoverSkipReason(true, displayId, true, true, true, true) ==
                GameLaunchCoverSkip.SECONDARY_DISPLAY
            assertEquals(!gameOpeningLaunchTransitionAllowed(displayId), skipped)
        }
    }

    // ---- fixtures

    /** The states an interrupt can find the cover in, each set up on a fresh [Sim]. */
    private fun states(): List<Pair<String, (Sim) -> Unit>> = listOf(
        "armed" to { _: Sim -> },
        "yielded" to { sim: Sim -> sim.send(GameLaunchCoverEvent.Yielded) },
        "holding" to { sim: Sim ->
            sim.send(GameLaunchCoverEvent.Yielded)
            sim.send(game())
            sim.advanceTo(1_050L)
        },
        "gliding (game)" to { sim: Sim ->
            sim.send(GameLaunchCoverEvent.Yielded)
            sim.send(game())
            sim.advanceTo(1_000L + GameLaunchCoverGameHoldMs + 10L)
        },
        "gliding (ceiling)" to { sim: Sim ->
            sim.send(GameLaunchCoverEvent.Yielded)
            sim.advanceTo(1_000L + GameLaunchCoverCeilingMs + 10L)
        },
    )

    /**
     * Drives the machine the way the controller does: events at the current time, and a Tick at each wake-up the machine
     * asked for. [now] starts at the mount, 1 000 ms.
     */
    private inner class Sim(private val simTiming: GameLaunchCoverTiming = timing) {
        var now = 1_000L
        var state: GameLaunchCoverState? = GameLaunchCoverMachine.arm(rule, now, EPOCH)
        var ended: GameLaunchCoverEnd? = null
        var glideEnd: GameLaunchCoverEnd? = null
        var glideStartedAt: Long? = null
        private var wakeAt: Long? = state?.let { GameLaunchCoverMachine.settle(it, now, simTiming).wakeAtMs }

        fun send(event: GameLaunchCoverEvent) {
            val current = state ?: return
            apply(GameLaunchCoverMachine.reduce(current, event, now, simTiming))
        }

        fun advanceTo(target: Long) {
            while (true) {
                val due = wakeAt
                if (state == null || due == null || due > target) break
                now = due
                apply(GameLaunchCoverMachine.reduce(state!!, GameLaunchCoverEvent.Tick, now, simTiming))
            }
            now = target
        }

        private fun apply(step: GameLaunchCoverStep) {
            when (val action = step.action) {
                GameLaunchCoverAction.None -> Unit
                is GameLaunchCoverAction.BeginGlide -> {
                    glideEnd = action.end
                    glideStartedAt = now
                }
                is GameLaunchCoverAction.Remove -> {
                    ended = action.end
                    state = null
                    wakeAt = null
                    return
                }
            }
            state = step.state
            wakeAt = step.wakeAtMs
        }
    }

    private companion object {
        /** The wall-clock moment the cover is mounted at; event stamps are relative to it. */
        const val EPOCH = 5_000_000L
    }
}

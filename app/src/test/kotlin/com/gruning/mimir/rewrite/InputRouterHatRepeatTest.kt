package com.gruning.mimir.rewrite

import android.view.MotionEvent
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultInputAuthority
import com.gruning.mimir.rewrite.core.input.MimirDirectionalPace
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * BM01 — held-HAT repeat synthesis, pinned. On device a 2.5s physical hold of the HAT d-pad produced
 * exactly ONE move (both tested devices report the d-pad as HAT axes, which emit no samples while
 * held, so the DB01 two-phase repeat never ran there). The router now re-feeds the held state on a
 * ticker; these pins prove the AUTHORITY'S gates stay the only pacing decision: first repeat waits
 * the 260ms deliberate delay, later ones the 120ms cruise, and the release sample stops everything.
 */
class InputRouterHatRepeatTest {

    private var clock = 1_000L
    private val ticks = ArrayDeque<Pair<Long, () -> Unit>>()
    private var scheduled = 0
    private lateinit var router: InputRouter
    private val moves = mutableListOf<Long>()

    @Before fun setUp() {
        clock = 1_000L
        MimirDirectionalPace.reset()
        MimirDirectionalPace.clock = { clock }
        router = InputRouter(
            interactionRoot = InteractionRoot.PRIMARY,
            inputAuthority = DefaultInputAuthority(clock = { clock }),
            hatRepeatTicker = { delayMs, tick ->
                scheduled++
                val entry = (clock + delayMs) to tick
                ticks += entry
                ({ ticks.remove(entry) })
            },
            hatRepeatClock = { clock },
        ).also { r ->
            r.handler = { action ->
                if (action == ControllerAction.NAV_RIGHT) moves += clock
                true
            }
        }
    }

    @After fun tearDown() {
        MimirDirectionalPace.reset()
        MimirDirectionalPace.clock = { System.nanoTime() / 1_000_000 }
    }

    /** Advance fake time, firing due ticks in order (each tick may schedule its successor). */
    private fun advanceTo(untilMs: Long) {
        while (true) {
            val due = ticks.filter { it.first <= untilMs }.minByOrNull { it.first } ?: break
            ticks.remove(due)
            clock = due.first
            due.second.invoke()
        }
        clock = untilMs
    }

    private fun hat(x: Float) {
        router.onRawMotionSample(
            axes = mapOf(MotionEvent.AXIS_HAT_X to x, MotionEvent.AXIS_HAT_Y to 0f),
            eventTimeMs = clock,
        )
        router.updateHatHoldForTest(mapOf(MotionEvent.AXIS_HAT_X to x, MotionEvent.AXIS_HAT_Y to 0f))
    }

    @Test
    fun `a held HAT direction repeats on the authority's two-phase cadence`() {
        hat(1f) // press → immediate move
        assertEquals(listOf(1_000L), moves)

        advanceTo(2_000L)
        // The 40ms tick grid quantizes the 260ms deliberate delay to 1280; the 120ms cruise then
        // lands EXACTLY on-grid (40 divides 120): 1400, 1520, 1640, 1760, 1880, 2000.
        assertEquals(
            listOf(1_000L, 1_280L, 1_400L, 1_520L, 1_640L, 1_760L, 1_880L, 2_000L),
            moves,
        )
    }

    @Test
    fun `the release sample stops the synthesized repeats`() {
        hat(1f)
        advanceTo(1_300L) // one repeat has fired
        assertEquals(2, moves.size)

        hat(0f) // release
        advanceTo(3_000L)
        assertEquals("no repeats after release", 2, moves.size)
    }

    @Test
    fun `reset stops the repeater`() {
        hat(1f)
        router.reset()
        advanceTo(3_000L)
        assertEquals(1, moves.size)
    }

    @Test
    fun `no ticker means no synthesis - the pre-BM01 test behavior`() {
        val bare = InputRouter(interactionRoot = InteractionRoot.PRIMARY, inputAuthority = DefaultInputAuthority(clock = { clock }))
        var count = 0
        bare.handler = { count++; true }
        bare.onRawMotionSample(
            axes = mapOf(MotionEvent.AXIS_HAT_X to 1f, MotionEvent.AXIS_HAT_Y to 0f),
            eventTimeMs = clock,
        )
        assertEquals(1, count)
    }
}

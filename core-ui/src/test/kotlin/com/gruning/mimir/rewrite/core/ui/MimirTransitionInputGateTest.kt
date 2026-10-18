package com.gruning.mimir.rewrite.core.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Presses during a page change : held by one owner at a time, and never forever. */
class MimirTransitionInputGateTest {

    private var now = 1_000L

    @Before
    fun clock() {
        MimirTransitionInputGate.reset()
        MimirTransitionInputGate.clock = { now }
    }

    @After
    fun reset() = MimirTransitionInputGate.reset()

    @Test
    fun `a hold keeps presses out until its owner releases it`() {
        val page = Any()
        MimirTransitionInputGate.hold(page)
        assertTrue(MimirTransitionInputGate.holding)
        MimirTransitionInputGate.release(page)
        assertFalse(MimirTransitionInputGate.holding)
    }

    @Test
    fun `an older owner cannot release a newer hold`() {
        val first = Any()
        val second = Any()
        MimirTransitionInputGate.hold(first)
        MimirTransitionInputGate.hold(second)
        MimirTransitionInputGate.release(first)
        assertTrue(MimirTransitionInputGate.holding)
    }

    @Test
    fun `a hold nobody releases expires, so the pad is never left deaf`() {
        MimirTransitionInputGate.hold(Any())
        now += MimirPageMotion.InputGateMaxMs - 1L
        assertTrue(MimirTransitionInputGate.holding)
        now += 1L
        assertFalse(MimirTransitionInputGate.holding)
    }

    @Test
    fun `an owner that rearms its hold gets the new deadline, and a rearm never shortens one`() {
        val page = Any()
        MimirTransitionInputGate.hold(page, maxMs = 100)
        now += 90
        MimirTransitionInputGate.rearm(page, maxMs = 100)
        now += 90
        assertTrue("past the first deadline, inside the second", MimirTransitionInputGate.holding)
        now += 10
        assertFalse("and it lapses on the second", MimirTransitionInputGate.holding)

        MimirTransitionInputGate.hold(page, maxMs = 500)
        MimirTransitionInputGate.rearm(page, maxMs = 100)
        now += 499
        assertTrue("a shorter rearm leaves the longer deadline alone", MimirTransitionInputGate.holding)
        now += 1
        assertFalse(MimirTransitionInputGate.holding)
    }

    @Test
    fun `a rearm by anyone but the owner changes nothing, so it cannot steal a newer hold`() {
        val older = Any()
        val newer = Any()
        MimirTransitionInputGate.hold(older, maxMs = 100)
        MimirTransitionInputGate.hold(newer, maxMs = 100)
        now += 90
        MimirTransitionInputGate.rearm(older, maxMs = 1_000)
        now += 10
        assertFalse("the newer hold lapses on its own deadline, not the older one's rearm", MimirTransitionInputGate.holding)

        MimirTransitionInputGate.hold(older, maxMs = 100)
        MimirTransitionInputGate.release(older)
        MimirTransitionInputGate.rearm(older, maxMs = 1_000)
        assertFalse("a released hold is not revived", MimirTransitionInputGate.holding)
    }

    @Test
    fun `an owner that never released can rearm a hold that already lapsed`() {
        // A wait that ended after the deadline: nobody released it, and the page it holds for is still hidden.
        val page = Any()
        MimirTransitionInputGate.hold(page, maxMs = 100)
        now += 150
        assertFalse(MimirTransitionInputGate.holding)
        MimirTransitionInputGate.rearm(page, maxMs = 100)
        assertTrue(MimirTransitionInputGate.holding)
        MimirTransitionInputGate.release(page)
        assertFalse(MimirTransitionInputGate.holding)
    }

    @Test
    fun `the page tier's clocks and travel signs`() {
        assertEquals(180, MimirPageMotion.ExitMs)
        assertEquals(130, MimirPageMotion.LeadMs)
        assertEquals(540, MimirPageMotion.ArriveMs)
        assertEquals(160, MimirPageMotion.ReturnMs)
        assertEquals(400, MimirPageMotion.SettleCapMs)
        assertEquals(22f, MimirPageMotion.arrivalTravelPx(1, 22f), 0f)
        assertEquals(-22f, MimirPageMotion.exitTravelPx(1, 22f), 0f)
        assertEquals(-22f, MimirPageMotion.arrivalTravelPx(-1, 22f), 0f)
        assertEquals(22f, MimirPageMotion.returnTravelPx(1, 22f), 0f)
    }
}

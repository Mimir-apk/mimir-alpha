package com.gruning.mimir.rewrite.core.input

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L2/R2 anti-misfire coverage for duplicate analog/key edges during a single physical pull.
 *
 * **The mechanism, traced rather than guessed.** L2/R2 are analog axes that many pads ALSO report as
 * `KEYCODE_BUTTON_L2/R2` — the dual-source hazard [ControllerAction.TRIGGER_LEFT]'s own KDoc warns about.
 * The axis edge fires when the pull crosses `triggerActivate`; the keycode twin fires at whatever depth
 * the pad's firmware chose. On a brisk pull the two land inside `dualSourceCoalesceMs` (50 ms) and the
 * existing cross-source dedupe catches the second. **On a soft or slow pull they do not, and one squeeze
 * jumps two alphabet buckets intermittently**.
 *
 * **What is NOT paced, and why that is the interesting half.** The axis path already fires exactly once
 * per physical pull: firing clears the armed latch, and only a reading back under `triggerRelease`
 * re-arms it. Pacing it as well was tried and reverted — it swallows a deliberate second pull, and
 * `DefaultInputAuthorityHumanMappingTest.heldAnalogTriggersFireOnceAndRearmAtTheirReleaseThreshold` fails
 * when it is added. That failure is the evidence the axis path was never the defect.
 *
 * **What a passing run here does NOT prove:** that this is the reported misfire on the Odin. It
 * proves the twin path is closed and a deliberate re-pull survives. The device discriminates: the pace
 * emits its own `trigger_pace_*` swallow reason, so misfires that persist WITHOUT that reason in logcat
 * are axis-band oscillation instead, and the fix for that is a wider band, not a timer.
 */
class TriggerMisfirePaceTest {

    private fun authority(
        tuning: InputTuning = InputTuning(),
        clock: () -> Long = { 0L },
    ) = DefaultInputAuthority(tuning = tuning, clock = clock)

    private fun axisPull(value: Float = 0.9f) = RawInputEvent(
        kind = RawInputEvent.Kind.MOTION,
        axisValues = mapOf(MotionEvent.AXIS_LTRIGGER to value),
    )

    private fun keyTwin(down: Boolean = true) = RawInputEvent(
        kind = RawInputEvent.Kind.KEY,
        keyCode = KeyEvent.KEYCODE_BUTTON_L2,
        down = down,
    )

    private fun reason(decision: InputDecision): String? = (decision as? InputDecision.Swallowed)?.reason

    /**
     * The union latch closes the duplicate-trigger defect before the pace is consulted.
     *
     * The behaviour asserted here is unchanged: an axis pull's keycode twin at 120 ms must not actuate a
     * second time. What changed is the reason. The union latch in [TriggerUnionLatchTest] now catches this
     * before the pace is ever consulted, and it does so at ANY separation rather than only inside 280 ms —
     * which matters, because the defect was reported again after the pace shipped. See
     * `TriggerUnionLatchTest.an axis pull whose keycode twin arrives past the pace window …` for the
     * ordering that proved the pace alone was not enough.
     */
    @Test
    fun `a slow pull's keycode twin cannot actuate a second time`() {
        var now = 0L
        val authority = authority(clock = { now })

        assertTrue(
            "precondition: the axis edge is the real actuation and must fire",
            authority.interpret(axisPull()) is InputDecision.Normalized,
        )

        // 120 ms later -- well past dualSourceCoalesceMs (50), which is exactly why this defect survived
        // the existing cross-source dedupe.
        now = 120L
        val twin = authority.interpret(keyTwin())
        assertEquals(
            "the keycode twin of an analog pull must not actuate a second time. At 120ms it is outside " +
                "dualSourceCoalesceMs (50ms), so the cross-source dedupe cannot catch it -- and since " +
                "TM-2 the union latch, not the pace, is what closes it.",
            "trigger_latched_${ControllerAction.TRIGGER_LEFT}",
            reason(twin),
        )
    }

    /**
     * The other half: the pace must not become a tax on deliberate input.
     *
     * TM-2 added the key UP. A second DOWN with no intervening UP was never a second pull — it is one
     * continuous hold, and the union latch now (correctly) swallows it. Asserting that such a sequence
     * actuates twice was asserting that a held trigger re-fires.
     */
    @Test
    fun `a genuinely separate pull still actuates once the pace window has passed`() {
        var now = 0L
        val authority = authority(clock = { now })
        authority.interpret(keyTwin())
        authority.interpret(keyTwin(down = false))

        now = 300L
        assertTrue(
            "a deliberate second pull must survive. This held under the retired 280ms pace because 300 " +
                "cleared it; it holds now for a stronger reason -- there is no window left to clear, and " +
                "the union latch re-armed on the UP.",
            authority.interpret(keyTwin()) is InputDecision.Normalized,
        )
    }

    @Test
    fun `the two triggers are paced independently`() {
        var now = 0L
        val authority = authority(clock = { now })
        authority.interpret(keyTwin())

        now = 60L
        val r2 = authority.interpret(
            RawInputEvent(kind = RawInputEvent.Kind.KEY, keyCode = KeyEvent.KEYCODE_BUTTON_R2, down = true),
        )
        assertTrue(
            "L2 must never pace R2. Correcting an over-jump by immediately pulling the OTHER trigger is " +
                "the most likely next thing a player does, and a shared stamp -- the d-pad pace's global " +
                "one, for instance -- would eat exactly that.",
            r2 is InputDecision.Normalized,
        )
    }

    @Test
    fun `the pace never touches a face button`() {
        var now = 0L
        val authority = authority(clock = { now })
        val a = RawInputEvent(kind = RawInputEvent.Kind.KEY, keyCode = KeyEvent.KEYCODE_BUTTON_A, down = true)
        assertTrue(authority.interpret(a) is InputDecision.Normalized)

        // 60ms, not 30: a same-keycode repress inside dualSourceCoalesceMs (50ms) is swallowed by the
        // PRE-EXISTING dual-source dedupe, which has nothing to do with this pace. Asserting at 30ms
        // passed for the wrong reason and would have gone on passing with the pace deleted.
        now = 60L
        assertTrue(
            "CONFIRM is not a trigger and must keep its own cadence -- a pace leaking onto the A button " +
                "would make the whole UI feel broken while looking like an input bug. 60ms is inside " +
                "the retired 280ms pace but outside the keycode dedupe -- when this was written that " +
                "made the pace the ONLY thing that could swallow it, which is why it is kept.",
            authority.interpret(a) is InputDecision.Normalized,
        )
    }

    /**
     * **INVERTED by policy 2026-08-21.** This used to assert that a fully-released re-press at 200ms
     * was swallowed as `trigger_pace_…`, and then that a rejection had not stretched the stamp. Both
     * halves described the retired pace, and the superseded rationale was: *"the pace's own remaining live
     * behaviour, and the one case that still reaches it."* That case is precisely the important distinction — it was never a misfire, it was a deliberate fast double-pull.
     *
     * Inverted rather than deleted: the sequence is unchanged, only the verdict is. **If a trigger timer
     * is ever reintroduced, this is the test that fails.**
     *
     * The key UPs matter and are not decoration — without them the union latch swallows the re-press
     * first, and the test would pass while proving nothing about what replaced the pace.
     */
    @Test
    fun `a fast deliberate re-press after a full release is no longer swallowed`() {
        var now = 0L
        val authority = authority(clock = { now })
        authority.interpret(keyTwin())
        authority.interpret(keyTwin(down = false))

        now = 200L
        val second = authority.interpret(keyTwin())
        assertEquals(
            "200ms after a FULL release is a deliberate double-pull, not a misfire -- the union latch " +
                "re-armed on the UP, so nothing legitimate is left to swallow it. Under the retired pace " +
                "this returned trigger_pace_" + ControllerAction.TRIGGER_LEFT + ".",
            null,
            reason(second),
        )
        assertTrue(second is InputDecision.Normalized)
    }

    @Test
    fun `reset leaves no trigger state that could throttle a fresh session`() {
        var now = 0L
        val authority = authority(clock = { now })
        authority.interpret(keyTwin())
        authority.reset()

        now = 10L
        assertTrue(
            "reset() is the teardown seam between input sessions. With the pace retired there is no " +
                "stamp left to survive it, but the latch's own state must clear too -- a trigger left " +
                "engaged across a reset would swallow the first press of the next session.",
            authority.interpret(keyTwin()) is InputDecision.Normalized,
        )
    }
}

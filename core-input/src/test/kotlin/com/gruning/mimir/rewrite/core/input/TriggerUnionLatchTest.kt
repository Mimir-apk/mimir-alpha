package com.gruning.mimir.rewrite.core.input

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the L2/R2 union latch.  Reported AFTER the 2026-08-14 `triggerPaceMs` timer shipped, which is the point: the timer
 * closed one of the three dual-source orderings and left two open.
 *
 * ### Device grounding (both certified pads, `getevent -lp`, 2026-08-21)
 * Retroid Pocket Nova (`Retroid Pocket Controller`, `Generic.kl`) and Odin 3 (`Odin Controller`,
 * `Vendor_2020_Product_0111.kl`) BOTH expose L2/R2 twice over: as `BTN_TL2`/`BTN_TR2` (→
 * `KEYCODE_BUTTON_L2`/`_R2`) **and** as `ABS_BRAKE`/`ABS_GAS` (→ `AXIS_BRAKE`/`AXIS_GAS`, the alias pair
 * [DefaultInputAuthority] folds into l2/r2). Both report `flat 0` on those axes — no hardware deadzone,
 * so the shallow rising samples this test feeds are real, not invented.
 *
 * ### The three orderings, and which were open
 * Two edges per pull (keycode twin, axis crossing) can land in either order at any separation:
 *  1. **Either order, < 50 ms apart** — closed since before this change by `dualSourceCoalesceMs`.
 *  2. **Axis first, keycode > 280 ms later** — the `triggerPaceMs` timer accepts it. **Was open.**
 *  3. **Keycode first, axis > 50 ms later** — the pace deliberately never runs on the axis path, and the
 *     key path never touched `axisArmed`. **Was open, and is the likelier of the two**: the pad's digital
 *     threshold sits far below [InputTuning.triggerActivate] (0.55), so on a soft squeeze the keycode
 *     always leads and the axis can take well over 50 ms to arrive.
 *
 * Both open orderings are slow-pull shapes — consistent with an intermittent report.
 *
 * The fix is an edge-latch, not a second timer: a trigger is ENGAGED while *either* source says so and
 * re-arms only when *both* release. [TriggerMisfirePaceTest] covers what remains of the timer.
 */
class TriggerUnionLatchTest {

    private var now = 0L

    private fun authority(tuning: InputTuning = InputTuning()) =
        DefaultInputAuthority(tuning = tuning, clock = { now })

    /** `AXIS_BRAKE` is the real L2 axis on BOTH certified pads — not `AXIS_LTRIGGER`. */
    private fun brake(value: Float) = RawInputEvent(
        kind = RawInputEvent.Kind.MOTION,
        axisValues = mapOf(MotionEvent.AXIS_BRAKE to value),
    )

    private fun l2Key(down: Boolean) = RawInputEvent(
        kind = RawInputEvent.Kind.KEY,
        keyCode = KeyEvent.KEYCODE_BUTTON_L2,
        down = down,
    )

    private fun actuations(vararg decisions: InputDecision): Int =
        decisions.count { it is InputDecision.Normalized && it.action == ControllerAction.TRIGGER_LEFT }

    /**
     * ORDERING 3 — THE DEFECT, and the one the 2026-08-14 timer could not see.
     *
     * A soft pull: the pad's digital edge fires shallow, the axis creeps up through the release band and
     * only crosses `triggerActivate` 120 ms later — outside `dualSourceCoalesceMs` (50 ms), and on the
     * axis path, where the pace deliberately never checks. One squeeze, two alphabet-bucket jumps.
     */
    @Test
    fun `a soft pull whose keycode leads its axis actuates exactly once`() {
        now = 0L
        val authority = authority()

        val keyEdge = authority.interpret(l2Key(down = true))
        now = 40L
        val rising1 = authority.interpret(brake(0.18f))
        now = 80L
        val rising2 = authority.interpret(brake(0.22f))
        now = 120L
        val axisEdge = authority.interpret(brake(0.90f))

        assertTrue(
            "precondition: the keycode twin leads on a soft pull and is the real actuation",
            keyEdge is InputDecision.Normalized,
        )
        assertEquals(
            "ONE physical pull must produce ONE actuation. The axis crossing at 120ms is the same " +
                "squeeze as the keycode at 0ms: too late for dualSourceCoalesceMs (50ms), and on the " +
                "axis path, which never consults triggerPaceMs. Before the union latch this returned 2 " +
                "-- the reported intermittent double actuation.",
            1,
            actuations(keyEdge, rising1, rising2, axisEdge),
        )
    }

    /**
     * The half of ORDERING 3 that a wrong re-arm rule silently breaks.
     *
     * The rising samples at 0.18/0.22 sit UNDER `triggerRelease` (0.25). An axis-only re-arm rule reads
     * them as "released" and re-opens the latch before the pull ever reaches 0.55 — the fix compiles,
     * reads correctly, and does nothing. Asserting the swallow reason pins the latch as the mechanism.
     */
    @Test
    fun `rising samples under the release threshold cannot re-arm a trigger whose key is still held`() {
        now = 0L
        val authority = authority()
        authority.interpret(l2Key(down = true))

        now = 40L
        authority.interpret(brake(0.18f))
        now = 80L
        val axisEdge = authority.interpret(brake(0.90f))

        assertEquals(
            "a rising sample below triggerRelease is a pull in progress, not a release. If it re-arms " +
                "the latch the axis edge that follows actuates a second time and TM-2 is a no-op.",
            InputDecision.Swallowed("axis_deadzone_or_not_rearmed"),
            axisEdge,
        )
    }

    /**
     * ORDERING 2 — the second open ordering, found while tracing rather than reported.
     *
     * A very slow squeeze on a pad whose digital threshold sits near full pull: the axis crosses first,
     * the keycode arrives beyond `triggerPaceMs` (280 ms) and the timer waves it through.
     */
    @Test
    fun `an axis pull whose keycode twin arrives past the pace window actuates exactly once`() {
        now = 0L
        val authority = authority()

        val axisEdge = authority.interpret(brake(0.90f))
        now = 300L
        val lateTwin = authority.interpret(l2Key(down = true))

        assertTrue("precondition: the axis edge fires", axisEdge is InputDecision.Normalized)
        assertEquals(
            "at 300ms the keycode twin clears triggerPaceMs (280ms) and the timer accepts it as a fresh " +
                "press. The latch is closed until a real release, so separation cannot matter.",
            InputDecision.Swallowed("trigger_latched_${ControllerAction.TRIGGER_LEFT}"),
            lateTwin,
        )
    }

    /**
     * The counterweight. A latch that never re-opens is a dead button, which is a worse defect than the
     * misfire. A FULL disengage — axis under `triggerRelease` **and** keycode up — must restore the trigger.
     */
    @Test
    fun `a genuine second pull after a full release still actuates`() {
        now = 0L
        val authority = authority()

        val first = authority.interpret(l2Key(down = true))
        now = 20L
        authority.interpret(brake(0.90f))
        now = 200L
        authority.interpret(brake(0.10f))
        now = 220L
        authority.interpret(l2Key(down = false))
        now = 400L
        val second = authority.interpret(l2Key(down = true))

        assertEquals(
            "two deliberate pulls separated by a real release are two actuations. Trading the misfire " +
                "for a trigger that only ever fires once would not be a fix.",
            2,
            actuations(first, second),
        )
    }

    /**
     * The axis half of the disengage must not re-arm on its own while the finger is still on the button.
     * Otherwise a pad that drops its axis to 0 before releasing the keycode double-fires on the next rise.
     */
    @Test
    fun `an axis returning to rest does not re-arm while the keycode is still held`() {
        now = 0L
        val authority = authority()
        authority.interpret(l2Key(down = true))

        now = 100L
        authority.interpret(brake(0.0f))
        now = 200L
        val rePull = authority.interpret(brake(0.90f))

        assertEquals(
            "the keycode is still down, so the trigger has not physically disengaged. Re-arming on the " +
                "axis alone would let one continuous hold actuate repeatedly.",
            InputDecision.Swallowed("axis_deadzone_or_not_rearmed"),
            rePull,
        )
    }

    /**
     * A pad that reports L2/R2 ONLY as keycodes (no trigger axis at all) must keep working. Its
     * `lastTriggerAxis` is never written, so the disengage predicate has to read absent-as-rest or the
     * second press of the session would be swallowed forever.
     */
    @Test
    fun `a digital-only pad that never sends a trigger axis never gets a dead trigger`() {
        now = 0L
        val authority = authority()

        val first = authority.interpret(l2Key(down = true))
        now = 50L
        authority.interpret(l2Key(down = false))
        now = 400L
        val second = authority.interpret(l2Key(down = true))

        assertEquals(
            "with no axis history the key UP alone is a full disengage. Reading absent axis state as " +
                "'still pulled' would wedge the trigger shut on every pad without an analog L2/R2.",
            2,
            actuations(first, second),
        )
    }

    /** The latch is per physical trigger: L2 engaged must never hold R2 shut. */
    @Test
    fun `the two triggers latch independently`() {
        now = 0L
        val authority = authority()
        authority.interpret(l2Key(down = true))
        authority.interpret(brake(0.90f))

        now = 60L
        val r2 = authority.interpret(
            RawInputEvent(
                kind = RawInputEvent.Kind.MOTION,
                axisValues = mapOf(MotionEvent.AXIS_GAS to 0.90f),
            ),
        )

        assertTrue(
            "correcting an over-jump by pulling the OTHER trigger is the most likely next input; a " +
                "shared latch would eat exactly that.",
            r2 is InputDecision.Normalized && r2.action == ControllerAction.TRIGGER_RIGHT,
        )
    }

    /** Holding a trigger must not freeze navigation — every motion sample carries the full axis set. */
    @Test
    fun `a latched trigger does not swallow stick input in the same motion sample`() {
        now = 0L
        val authority = authority()
        authority.interpret(l2Key(down = true))

        now = 40L
        val withStick = authority.interpret(
            RawInputEvent(
                kind = RawInputEvent.Kind.MOTION,
                axisValues = mapOf(
                    MotionEvent.AXIS_BRAKE to 0.90f,
                    MotionEvent.AXIS_HAT_X to 1f,
                ),
            ),
        )

        assertTrue(
            "InputRouter packs BRAKE/GAS and HAT/stick into every motion sample, so a latched trigger " +
                "returning early would kill directional navigation for the whole hold.",
            withStick is InputDecision.Normalized && withStick.action == ControllerAction.NAV_RIGHT,
        )
    }

    /**
     * holding a direction while a trigger is pulled must not re-arm the trigger.
     *
     * `InputRouter`'s synthesized HAT-repeat tick sends `AXIS_HAT_X/Y` and nothing else, so the trigger
     * axes read 0f from it. Treating that absence as "released" re-arms a trigger the user is still
     * holding, and the next real sample actuates again — one pull, two jumps, triggered by the ordinary
     * act of holding a direction. This is an axis-only pull (no keycode held), so `heldTriggerKeys` cannot
     * cover it; only presence-gating can.
     */
    @Test
    fun `a hat-only repeat sample does not re-arm a trigger that is still pulled`() {
        now = 0L
        val authority = authority()

        assertTrue(
            "precondition: the axis pull actuates",
            authority.interpret(brake(0.90f)) is InputDecision.Normalized,
        )

        now = 40L
        // The synthesized tick, verbatim in shape: hat axes only, no trigger axes at all.
        authority.interpret(
            RawInputEvent(
                kind = RawInputEvent.Kind.MOTION,
                axisValues = mapOf(MotionEvent.AXIS_HAT_X to 1f, MotionEvent.AXIS_HAT_Y to 0f),
            ),
        )

        now = 80L
        assertEquals(
            "a sample that carries no trigger axis is not evidence the trigger was released. Reading it " +
                "as 0f re-arms the latch mid-pull and the still-held trigger fires a second time.",
            InputDecision.Swallowed("axis_deadzone_or_not_rearmed"),
            authority.interpret(brake(0.90f)),
        )
    }

    /**
     * The union must not leak when a dedupe returns first.
     *
     * An axis-first pull whose keycode twin lands inside `dualSourceCoalesceMs` has that DOWN swallowed by
     * the cross-source dedupe. The key is still physically held, so if the dedupe's early return skipped
     * registering the key half, an axis dip mid-hold would read as a full disengage and re-arm — the
     * trigger then re-fires without the user ever releasing it.
     */
    @Test
    fun `a keycode twin swallowed by the dedupe still registers as physically held`() {
        now = 0L
        val authority = authority()

        authority.interpret(brake(0.90f))
        now = 30L
        authority.interpret(l2Key(down = true)) // inside the 50ms dedupe -- swallowed, but DOWN

        now = 100L
        authority.interpret(brake(0.10f)) // axis dips while the keycode is still held
        now = 160L
        assertEquals(
            "the keycode is still down, so this is one continuous hold however the twin was swallowed. " +
                "A dedupe's early return must not lose the key half of the union.",
            InputDecision.Swallowed("axis_deadzone_or_not_rearmed"),
            authority.interpret(brake(0.90f)),
        )
    }

    /**
     * The authority-swap seam. `InputRouter` picks between the main and mapping-overlay authorities per
     * event (`InputRouter.kt`, `controllerMappingOverlayActive()`), with no reset on the transition — so a
     * trigger's DOWN can land on one authority and its UP on the other, orphaning the key half.
     *
     * This pins the degradation as SELF-HEALING rather than a dead trigger: `releaseTriggerKey` runs
     * unconditionally at the top of every key UP, ahead of every other branch, so the first UP the main
     * authority does see clears the orphan. Worst case is one swallowed press, not a button that stays
     * dead until `reset()`.
     */
    @Test
    fun `an orphaned trigger hold costs at most one press and then self-heals`() {
        now = 0L
        val authority = authority()

        authority.interpret(l2Key(down = true)) // DOWN seen here; UP goes to the overlay authority

        now = 500L
        val swallowed = authority.interpret(l2Key(down = true))
        assertEquals(
            "precondition: the orphaned hold latches the trigger",
            InputDecision.Swallowed("trigger_latched_${ControllerAction.TRIGGER_LEFT}"),
            swallowed,
        )

        now = 520L
        authority.interpret(l2Key(down = false))
        now = 900L
        assertTrue(
            "a trigger must never stay dead waiting on reset(). The unconditional releaseTriggerKey on " +
                "the UP edge is what bounds this seam at a single lost press.",
            authority.interpret(l2Key(down = true)) is InputDecision.Normalized,
        )
    }

    /** reset() is the focus-loss/resume seam; a latch surviving it would eat the next session's first pull. */
    @Test
    fun `reset re-arms both triggers`() {
        now = 0L
        val authority = authority()
        authority.interpret(l2Key(down = true))
        authority.interpret(brake(0.90f))
        authority.reset()

        now = 10L
        assertTrue(
            "a trigger held across a window focus loss must not come back latched shut",
            authority.interpret(brake(0.90f)) is InputDecision.Normalized,
        )
    }
}

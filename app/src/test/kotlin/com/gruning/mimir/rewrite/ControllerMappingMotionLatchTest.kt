package com.gruning.mimir.rewrite

import android.view.MotionEvent
import com.gruning.mimir.rewrite.core.input.PhysicalControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture gate needs the same two-level latch its sibling already uses, and it needs to stop reading
 * an axis it was never told about as proof of release.
 *
 * `DefaultInputAuthority` arms at `InputTuning.axisActivate` (0.55) and only re-arms once the axis falls
 * back under `axisRelease` (0.35) — triggers at 0.55/0.25 — precisely so a stick easing back through the
 * activation point cannot immediately re-fire. The capture gate was written with ONE threshold used in
 * both directions: activate above 0.55, "neutral" at 0.55 or below. Those are complementary, so there is
 * no latch at all — a stick resting anywhere below the activation point reads as fully released, and a
 * noisy axis hovering at the boundary alternates between captured and neutral sample by sample.
 *
 * Second half: neutrality was judged with `axes[axis] ?: 0f`, so an axis MISSING from a sample counted as
 * a perfectly centred one. Absence is not release — it is the absence of evidence either way, and it must
 * not satisfy the wait.
 */
class ControllerMappingMotionLatchTest {

    private fun axes(vararg pairs: Pair<Int, Float>): Map<Int, Float> = buildMap {
        listOf(
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_BRAKE,
            MotionEvent.AXIS_GAS,
        ).forEach { put(it, 0f) }
        pairs.forEach { (axis, value) -> put(axis, value) }
    }

    private fun captureOnce(gate: ControllerMappingMotionInputGate): PhysicalControl? {
        var seen: PhysicalControl? = null
        gate.handle(
            samples = listOf(axes(MotionEvent.AXIS_X to 0.9f)),
            captureActive = true,
            capture = { control -> seen = control; true },
        )
        return seen
    }

    @Test
    fun `an axis easing back below the activation point is not yet a release`() {
        val gate = ControllerMappingMotionInputGate()
        assertEquals(PhysicalControl.DPAD_RIGHT, captureOnce(gate))

        // 0.45 is under the 0.55 activation point but well above the sibling's 0.35 re-arm point. The
        // player has not let go; they are on the way back.
        gate.handle(
            samples = listOf(axes(MotionEvent.AXIS_X to 0.45f)),
            captureActive = true,
            capture = { throw AssertionError("capture must stay latched until the axis truly releases") },
        )

        var recaptured = false
        gate.handle(
            samples = listOf(axes(MotionEvent.AXIS_X to 0.9f)),
            captureActive = true,
            capture = { recaptured = true; true },
        )
        assertTrue(
            "the gate re-armed at 0.45, so pushing the same stick again bound a second control the " +
                "player never meant to press",
            !recaptured,
        )
    }

    @Test
    fun `a genuine return to centre does re-arm the gate`() {
        val gate = ControllerMappingMotionInputGate()
        assertEquals(PhysicalControl.DPAD_RIGHT, captureOnce(gate))

        gate.handle(samples = listOf(axes()), captureActive = true, capture = { true })

        var recaptured = false
        gate.handle(
            samples = listOf(axes(MotionEvent.AXIS_X to 0.9f)),
            captureActive = true,
            capture = { recaptured = true; true },
        )
        assertTrue("a real release must let the next control be bound", recaptured)
    }

    @Test
    fun `an axis missing from the sample is not evidence that it released`() {
        val gate = ControllerMappingMotionInputGate()
        assertEquals(PhysicalControl.DPAD_RIGHT, captureOnce(gate))

        // A sample that simply does not mention AXIS_X. It says nothing about where the stick is.
        gate.handle(
            samples = listOf(mapOf(MotionEvent.AXIS_HAT_X to 0f, MotionEvent.AXIS_HAT_Y to 0f)),
            captureActive = true,
            capture = { throw AssertionError("an unreported axis must not satisfy the neutral wait") },
        )

        var recaptured = false
        gate.handle(
            samples = listOf(axes(MotionEvent.AXIS_X to 0.9f)),
            captureActive = true,
            capture = { recaptured = true; true },
        )
        assertTrue("absence of an axis was read as release", !recaptured)
    }

    // The shared predicate itself, which BOTH the mapping capture gate and the shortcut capture
    // quarantine now use. The shortcut path had its own verbatim copy with the same two defects.

    @Test
    fun `the rest test uses the sibling's two-level latch for sticks and triggers alike`() {
        assertTrue("a centred pad is at rest", controllerMotionIsAtRest(axes()))
        assertTrue(
            "0.30 is under the 0.35 directional re-arm point",
            controllerMotionIsAtRest(axes(MotionEvent.AXIS_X to 0.30f)),
        )
        assertTrue(
            "a stick on the way back at 0.45 has not released",
            !controllerMotionIsAtRest(axes(MotionEvent.AXIS_X to 0.45f)),
        )
        assertTrue(
            "the latch is symmetric — a stick pushed the other way must latch identically",
            !controllerMotionIsAtRest(axes(MotionEvent.AXIS_X to -0.45f)),
        )
        assertTrue(
            "triggers rest lower than sticks: 0.30 is above the 0.25 trigger re-arm point",
            !controllerMotionIsAtRest(axes(MotionEvent.AXIS_LTRIGGER to 0.30f)),
        )
        assertTrue(
            "0.20 is under the trigger re-arm point",
            controllerMotionIsAtRest(axes(MotionEvent.AXIS_LTRIGGER to 0.20f)),
        )
    }

    @Test
    fun `the rest test refuses to guess about an axis it was not given`() {
        assertTrue(
            "an axis absent from the sample is not evidence it centred",
            !controllerMotionIsAtRest(mapOf(MotionEvent.AXIS_HAT_X to 0f)),
        )
    }
}

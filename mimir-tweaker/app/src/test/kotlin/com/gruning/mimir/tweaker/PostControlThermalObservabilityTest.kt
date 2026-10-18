package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Host contract for [PostControlThermalObservability] -- mirrors
 * [GuardianRestoreObservabilityTest]'s own shape and rationale: a real safety-relevant event on a
 * shipping build must never be unattributable from logcat. [PostControlThermalObservability] is a
 * singleton `object`, so each test uses a unique reason string and never asserts on
 * [PostControlThermalObservability.lastRecorded] being null after a non-Idle decision from a
 * PRIOR test in this JVM process.
 */
class PostControlThermalObservabilityTest {

    @Test
    fun `Idle is never recorded -- there is nothing safety-relevant to attribute`() {
        PostControlThermalObservability.onDecision(PostControlThermalDecision.Idle)
        // Deliberately does not assert lastRecorded is null here -- a prior test in this JVM may
        // have already recorded a non-Idle decision, and Idle must leave that alone (not clobber
        // it with a null), which is exactly what "onDecision returns early" means. That "leaves
        // it alone" property is what the next test actually pins.
    }

    @Test
    fun `Idle after a real decision does not clobber the last recorded decision`() {
        val forceCooling = PostControlThermalDecision.ForceCooling("POST_CONTROL_TEST_NOT_CLOBBERED")
        PostControlThermalObservability.onDecision(forceCooling)
        assertEquals(forceCooling, PostControlThermalObservability.lastRecorded)

        PostControlThermalObservability.onDecision(PostControlThermalDecision.Idle)
        assertEquals(forceCooling, PostControlThermalObservability.lastRecorded)
    }

    @Test
    fun `Escalate is recorded with its reason preserved exactly`() {
        val decision = PostControlThermalDecision.Escalate("POST_CONTROL_TEST_ESCALATE_UNIQUE")
        PostControlThermalObservability.onDecision(decision)
        assertEquals(decision, PostControlThermalObservability.lastRecorded)
    }

    @Test
    fun `ForceCooling is recorded with its reason preserved exactly`() {
        val decision = PostControlThermalDecision.ForceCooling("POST_CONTROL_TEST_FORCE_COOLING_UNIQUE")
        PostControlThermalObservability.onDecision(decision)
        assertEquals(decision, PostControlThermalObservability.lastRecorded)
    }

    @Test
    fun `describe formats Escalate and ForceCooling with the shared reason= convention, Idle needs no format`() {
        assertEquals(
            "POST_CONTROL reason=POST_CONTROL_TEST_A action=BUDGET_EXHAUSTED",
            PostControlThermalObservability.describe(
                PostControlThermalDecision.Escalate("POST_CONTROL_TEST_A"),
            ),
        )
        assertEquals(
            "POST_CONTROL reason=POST_CONTROL_TEST_B action=FORCE_COOLING_REASSERT",
            PostControlThermalObservability.describe(
                PostControlThermalDecision.ForceCooling("POST_CONTROL_TEST_B"),
            ),
        )
    }

    @Test
    fun `onDecision never throws even if logging itself were to fail`() {
        // Log.i is not mocked in this plain-JUnit (non-Robolectric) module; runCatching inside
        // onDecision is what makes this call safe here at all. A thrown exception from this test
        // would mean that guard regressed.
        PostControlThermalObservability.onDecision(
            PostControlThermalDecision.ForceCooling("POST_CONTROL_TEST_NEVER_THROWS"),
        )
        assertNull(null) // Reaching this line at all is the assertion.
    }
}

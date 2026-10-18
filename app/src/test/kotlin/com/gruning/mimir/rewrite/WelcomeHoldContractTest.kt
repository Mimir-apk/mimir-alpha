package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PERFE-01 — the welcome hold's deadline contract, in a test rather than in prose.
 *
 * Two comments used to assert this invariant and neither enforced it, so it shipped broken:
 * `WelcomeBackLane` said *"MainActivity's input backstop is 9s — this cap must stay under it"* (comparing
 * the cap alone, when the cap starts only AFTER the beat), and `MainActivity` said the backstop covered
 * *"the 3.2s minimum beat plus the ~5s library-ready linger"* (right form, but the linger cap was 8s, not
 * ~5s). Actual worst case was 3.2 + 8 = 11.2s against a 9s backstop, so the input gate opened up to 2.2s
 * before the splash could come down and input reached a shell the user could not see.
 *
 * These are pure constants, so this costs nothing to run and fails the moment either one moves.
 */
class WelcomeHoldContractTest {

    @Test
    fun `the hold budget is the beat plus the ready cap, not either alone`() {
        assertEquals(
            WelcomeBackColdStartMs + WelcomeBackReadyCapMs + com.gruning.mimir.rewrite.core.ui.MimirMotion.OverlayMs,
            WelcomeHoldMaxMs,
        )
    }

    @Test
    fun `the input-gate backstop outlives the splash's worst case`() {
        // The regression that shipped: comparing the backstop against the cap alone passes (8_000 < 9_000)
        // while the real hold (11_200) already exceeded it. Assert against the SUM.
        assertTrue(
            "input backstop ($WelcomeHoldBackstopMs ms) must exceed the splash's max " +
                "hold ($WelcomeHoldMaxMs ms), or the gate opens under the splash",
            WelcomeHoldBackstopMs > WelcomeHoldMaxMs,
        )
    }

    @Test
    fun `the minimum hold beat is intact`() {
        // The 3.2s beat is an policy (shipping WELCOME_BACK_COLD_START_MS). PERFE-01 changed how the
        // budget is expressed, never how long the user waits — this pins that.
        assertEquals(3_200L, WelcomeBackColdStartMs)
    }
}

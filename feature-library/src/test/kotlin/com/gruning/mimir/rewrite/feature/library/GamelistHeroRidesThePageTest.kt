package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.ui.MimirMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy 2026-08-26, *"backdrop rides the page"* — the settlement of a strobe the regression report described twice.
 *
 * Measured on the Odin before the fix: the page glide ran 160 ms gated on rows while the hero backdrop
 * sat on a 300 ms floor, and the screen moved AGAIN 180/189/213 ms after it had already settled. One
 * press, two arrivals.
 */
class GamelistHeroRidesThePageTest {

    /**
     * Policy 2026-08-28, correcting the arithmetic of the one above rather than the intent.
     *
     * "They land together" was implemented as `floor == glide`, and the floor is a delay BEFORE the
     * 180 ms reveal tween, not the tween itself — so equalising them lands the hero at `160 + 180 =
     * 340 ms` against a page that settled at 160 ms. Same two arrivals, closer together.
     *
     * Device-measured, correlating each frame against the settled frame of the incoming hero:
     * **floor 160 ms → 213/220/217 ms; floor 0 ms → −50/80/97 ms.** At zero the reveal runs concurrently
     * with the glide, which is what the concurrent-reveal contract requires.
     */
    @Test
    fun `the hero's reveal runs WITH the page glide rather than starting after it`() {
        assertEquals(
            "the floor is a WAIT and there is nothing left to wait for",
            0L,
            LibraryGamelistHeroRevealFloorMs,
        )
        assertTrue(
            "restoring floor == glide is the 2026-08-26 arithmetic error: it delays the 180ms reveal " +
                "until the page has already settled, which causes a second arrival",
            LibraryGamelistHeroRevealFloorMs < MimirMotion.ModeTransitionMs,
        )
    }

    /**
     * The de-coupling pin, and the reason this is a new constant instead of an edit to the old one.
     *
     * `BifrostMediaSettleMs` is the VIDEO player's debounce — it stops a player being spun up per game
     * while scrolling. The static hero borrowed it and inherited a cost that was never its own. If these
     * two are ever collapsed back into one number, either the still hero pays the debounce again or the
     * video player loses it.
     */
    @Test
    fun `the still hero's floor is NOT the video player's debounce`() {
        assertNotEquals(
            "two different duties must not share one number again",
            BifrostMediaSettleMs,
            LibraryGamelistHeroRevealFloorMs,
        )
        assertTrue(
            "and the still hero must be the FASTER of the two -- the debounce exists to delay a player, " +
                "not to make a decoded still image sit there",
            LibraryGamelistHeroRevealFloorMs < BifrostMediaSettleMs,
        )
    }

    @Test
    fun `the video player keeps its debounce untouched`() {
        assertEquals(
            "lowering the still hero's floor must not have moved the player's, or scrolling a list " +
                "starts spinning up a player per game",
            300L,
            BifrostMediaSettleMs,
        )
    }
}

package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.ui.MimirMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F6 and N3 of the alpha audit (2026-09-30).
 *
 * F6: B from a Library game card or from search held the scene's blur for `MimirMotion.OverlayMs` (180 ms) on a coroutine
 * `delay()`, where the host holds it for `OverlayDissolveCoverMs` (130 ms), the frame the card is at alpha 0.044. For
 * about 50 ms the list stayed blurred under a card that was nearly gone, and the return finished at 376 ms where Home's
 * is 310.
 *
 * N3: a `delay()` does not scale with the animator setting. Under a slowed animator the hold ended while the fade it
 * covered was still running. The holds are `mimirHold` now (core-ui's MimirHoldTest drives it on a paused clock).
 *
 * Each pin is scoped to the effect it is about: the file's other `delay()` calls are not holds on a fade and stay.
 */
class LibraryHoldsRideTheAnimatorScaleTest {

    private fun source(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText() ?: error("$name not found from ${File(".").absolutePath}")
    }

    @Test
    fun `B from a card or from search holds the blur for the host's 130 ms, on the animation clock`() {
        val screen = source("LibraryScreen.kt")
        val card = screen.substringAfter("LaunchedEffect(renderedDetailState) {")
            .substringBefore("LaunchedEffect(transitionCancellationEpoch) {")
        val search = screen.substringAfter("LaunchedEffect(searchChromeRequested) {")
            .substringBefore("LaunchedEffect(transitionCancellationEpoch) {")
        listOf("the card" to card, "search" to search).forEach { (name, effect) ->
            assertTrue("$name's chrome hold is the host's", effect.contains("mimirHold(MimirMotion.OverlayDissolveCoverMs)"))
            assertFalse("$name's chrome hold is no coroutine delay", effect.contains("delay("))
            assertTrue("$name still clears its chrome after the hold", effect.contains("ChromeDismissStartEpoch == startEpoch"))
        }
    }

    @Test
    fun `the hold is the host's own constant, shorter than the dissolve it covers`() {
        assertEquals(130, MimirMotion.OverlayDissolveCoverMs)
        assertTrue(MimirMotion.OverlayDissolveCoverMs < MimirMotion.OverlayMs)
    }

    @Test
    fun `the coverflow's seam wait rides the animator scale, and its pre-roll deadline stays what it was`() {
        val motion = source("CoverflowMotion.kt")
        val tail = motion.substringAfter("// Let the per-identity alpha animation reach zero at the final antipode.")
            .substringBefore("motion.evenSeamPrepared = false")
        assertTrue(tail.contains("mimirHold(CoverflowRingSeamFadeMs)"))
        assertFalse(tail.contains("delay("))
        // The pre-roll waits out a deadline measured on the frame clock, which a hold cannot express; it is unchanged.
        assertTrue(motion.contains("delay((remainingNanos + 999_999L) / 1_000_000L)"))
    }
}

package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Library surfaces on the page lead: Search, a game's detail and the modals,
 * and the Carousel/List swap (which shares its surface with L2/R2) hold the page's input gate until they first paint.
 */
class LibraryOverlayArrivalInputGateTest {

    private fun source(): String = listOf(
        File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
    ).first(File::isFile).readText()

    @Test
    fun `the overlay host holds a cold arrival until it paints`() {
        val arrival = source()
            .substringAfter("internal fun <T> LibraryOverlayPixelFadeTransition(")
            .substringAfter("LaunchedEffect(targetState, frame.epoch) {")
            .substringBefore("LaunchedEffect(targetState, frame.epoch, frame.outgoingLayerIndex) {")
        val hold = "if (incomingAlpha.value <= 0f) holdMimirInputUntilShown(incomingAlpha) { incomingAlpha.value > 0f }"
        assertTrue(arrival.contains(hold))
        assertTrue("held before the frame beat", arrival.indexOf(hold) < arrival.indexOf("withFrameNanos { }"))
    }

    @Test
    fun `the Carousel-List swap holds presses like L2-R2 does`() {
        val swap = source()
            .substringAfter("fun GamelistViewModeLandingTransition(")
            .substringBefore("MimirPageHandoff(")
        val hold = "if (progress.value <= 0f) holdMimirInputUntilShown(progress) { progress.value > 0f }"
        assertTrue(swap.contains(hold))
        assertTrue("held before the frame beat", swap.indexOf(hold) < swap.indexOf("withFrameNanos { }"))
        assertFalse("only a cold swap holds", swap.contains("holdMimirInputUntilShown(progress) { true }"))
    }
}

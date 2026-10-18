package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Motion audit 2026-09-28, batch F: the Library's own overlays (Search, a game's detail, the open-with,
 * launch-choice and native-add modals) arrive on the page clock, ShineMs from the 130 ms lead, and leave on OverlayMs.
 */
class LibraryOverlayArrivalPageClockTest {

    private fun source(): String = listOf(
        File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
    ).first(File::isFile).readText()

    @Test
    fun `the overlay host's arrival is ShineMs from the lead and its exit stays OverlayMs`() {
        val host = source()
            .substringAfter("internal fun <T> LibraryOverlayPixelFadeTransition(")
            .substringBefore("Box(modifier.fillMaxSize()) {")
        val arrival = host
            .substringAfter("LaunchedEffect(targetState, frame.epoch) {")
            .substringBefore("LaunchedEffect(targetState, frame.epoch, frame.outgoingLayerIndex) {")
        assertTrue(arrival.contains("MimirMotion.ShineMs"))
        assertTrue(
            "only a cold arrival takes the lead",
            arrival.contains("delayMillis = if (incomingAlpha.value <= 0f) MimirMotion.OverlayDissolveCoverMs else 0"),
        )
        assertTrue("reduced motion snaps", arrival.contains("snap()"))
        val exit = host
            .substringAfter("LaunchedEffect(targetState, frame.epoch, frame.outgoingLayerIndex) {")
            .substringBefore("LaunchedEffect(transitionCancellationEpoch, frame.epoch) {")
        assertTrue(exit.contains("tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)"))
    }
}

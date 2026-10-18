package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * a segment change is the swap tier, not a dissolve, and each
 * segment is its own tree, so Android Games <-> Android Apps no longer cuts and scroll does not carry across.
 */
class FavoritesSegmentSwapTest {

    private val source = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
        File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
    ).first(File::isFile).readText()

    @Test
    fun `favourites swaps its segments instead of dissolving them`() {
        val view = source.substringAfter("private fun FavoritesSegmentedView(")
            .substringBefore("@Composable\nprivate fun FavoritesSegmentStrip")
        assertTrue(view.contains("FavoritesSegmentSwap(\n        segment = activeSegment,"))
        assertFalse("no dissolve", view.contains("ImmediateLibrarySnapshotTransition("))
    }

    @Test
    fun `each segment is its own tree, arriving on the swap clock from its chip's side`() {
        val swap = source.substringAfter("private fun FavoritesSegmentSwap(")
        assertTrue(swap.contains("key(segment) {\n                content(segment)"))
        assertTrue(swap.contains("progress.animateTo(targetValue = 1f, animationSpec = mimirSwapEnterSpec(reducedMotion))"))
        assertTrue(swap.contains("segment.ordinal.compareTo(previous.ordinal).coerceIn(-1, 1)"))
        assertTrue("presses wait until it shows", swap.contains("holdMimirInputUntilShown(progress) { progress.value > 0f }"))
    }

    /**
     * N1 (alpha audit 2026-09-30). The centred card's gleam waits for `LocalMimirPageArrived`, which only the Library's
     * page entry provided, so a Favourites segment's gleam started behind the held list. The segment provides it now:
     * false from the change until the arrival has run, and true after -- including when a press finishes the arrival,
     * because that snap interrupts the arrival's own animateTo and nothing after it runs.
     */
    @Test
    fun `the gleam waits for the segment to arrive, and a press that finishes the arrival releases it`() {
        val swap = source.substringAfter("private fun FavoritesSegmentSwap(").substringBefore("/** The segment shown last")
        assertTrue(swap.contains("var arrived by remember(segment) { mutableStateOf(!animates) }"))
        assertTrue(
            "provided around the content, and combined with an outer page's own flag",
            swap.contains("CompositionLocalProvider(LocalMimirPageArrived provides (outerArrived && arrived)) {\n            key(segment) {"),
        )
        assertTrue(swap.contains("val outerArrived = LocalMimirPageArrived.current"))
        val arrival = swap.substringAfter("LaunchedEffect(segment) {").substringBefore("LaunchedEffect(cancellationEpoch) {")
        assertTrue(
            "set when the arrival ends",
            arrival.contains("progress.animateTo(targetValue = 1f, animationSpec = mimirSwapEnterSpec(reducedMotion))\n        arrived = true"),
        )
        val press = swap.substringAfter("LaunchedEffect(cancellationEpoch) {").substringBefore("val travelPx")
        assertTrue(
            "and where a press finishes it",
            press.indexOf("progress.snapTo(1f)") in 0 until press.indexOf("arrived = true"),
        )
        // The flag is written when the arrival ends; progress is never read in composition (a per-frame read there
        // would recompose the whole segment on every frame of its arrival).
        val composition = swap.substringBefore("LaunchedEffect(segment) {")
        assertFalse(composition.contains("progress.value"))
        assertFalse(swap.contains("provides (progress"))
    }
}

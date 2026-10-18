package com.gruning.mimir.rewrite.feature.files

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *
 *
 * The answer: `rememberLazyListState()` is unkeyed, so on a directory change the parent listing is composed
 * and **drawn** at the scroll position inherited from the child. The corrective `SeekTo` is unanimated and
 * correct, but a `LaunchedEffect` body launches `CoroutineStart.DEFAULT` and is drained by
 * `AndroidUiDispatcher`'s posted `Handler` message — so it lands after `doFrame` has measured, laid out and
 * drawn. One painted frame at the wrong offset, then a snap.
 *
 * Measured on the Odin at the panel's native 120 Hz, returning into a scrolled parent:
 *
 * | | first content frame's vertical offset |
 * |---|---|
 * | before | **+214 px**, then a snap |
 * | seeding the state's initial index (tried, reverted) | −236 px, then a snap |
 * | **this gate** | **0 px — already seated** |
 *
 * Cost, measured on a content→content return: **exactly one blanked frame**, 8.3 ms at 120 Hz.
 *
 * No Compose harness exists in this module, so this pins the wiring as source text — the same precedent as
 * `HomeLaunchCardTest` and `UtilityPrimaryActionFocusAffordanceTest`. The `finally` is the load-bearing
 * half: the settlement effect has two early returns and is cancelled whenever a new arrival re-keys it, and
 * a `seatPending` left true on any of those paths is a permanently invisible listing.
 */
class FileBrowserSeatGateTest {

    private fun surfaceSource(): String = listOf(
        File("feature-files/src/main/kotlin/com/gruning/mimir/rewrite/feature/files/FileBrowserSurface.kt"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/files/FileBrowserSurface.kt"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("FileBrowserSurface.kt not reachable from ${File(".").absolutePath}")

    @Test
    fun `the content lane is withheld until the listing has been re-seated`() {
        val src = surfaceSource()
        assertTrue(
            "seatPending must start true for any arrival that animates (R6-P2-04 widened this from " +
                "DIRECTORY-only to the glide's own predicate, since a SCREEN return paints the same " +
                "inherited-offset frame a directory change does) -- that is the frame that would " +
                "otherwise paint at the offset inherited from the child",
            src.contains("val seatPending = remember(arrival) { mutableStateOf(fileBrowserArrivalAnimates(arrival)) }"),
        )
        assertTrue(
            "and it must gate the content lane's alpha",
            src.contains("alpha = if (seatPending.value) 0f else 1f"),
        )
    }

    @Test
    fun `the gate is on the LISTING, never on the shared modifier the empty branch uses`() {
        val src = surfaceSource()
        assertTrue(
            "contentModifier is shared with the empty-folder branch, which returns BEFORE the " +
                "settlement effect that clears seatPending -- so gating it there made \"This folder is " +
                "empty.\" permanently invisible. Device observation confirmed the missing hint.",
            src.contains("modifier = contentModifier.graphicsLayer { alpha = if (seatPending.value) 0f else 1f },"),
        )
        val chain = src.substringAfter("val contentModifier = modifier").substringBefore("val ringKey")
        assertTrue(
            "and contentModifier itself must carry no seat gate",
            !chain.contains("seatPending"),
        )
    }

    @Test
    fun `and it is released on EVERY path out of the settlement effect`() {
        val src = surfaceSource()
        assertTrue(
            "released in a finally, not at the end of the body: the settlement effect has two early " +
                "returns and is cancelled on every re-key. A seatPending left true is an invisible " +
                "listing -- a far worse failure than the snap it exists to hide. R6-P2-02 added " +
                "travelPending beside it, same discipline, same window.",
            src.contains(
                "} finally {\n            seatPending.value = false\n            travelPending.value = false\n        }",
            ),
        )
    }
}

package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 5 (5b): the overlay-hosted Tabs drawer made its "Launch unavailable. Try again." toasts from `appContext`, the
 * process context, which Android places on the default display. A failed launch the player tapped on the Bottom was
 * therefore reported on Main. The drawer's window is lent by `attachSecondaryPanel`, which mounts it on the Bottom, so
 * each toast is now made from the display of that window, read from the composition's own view.
 *
 * Each of those taps now makes its one toast through the shared tap helpers ([routeSecondaryQuickLaunchTap],
 * [settleSecondaryQuickLaunchChoiceTap]), which show Main's route's reason when it refused, so the drawer hands them that
 * window's context and keeps its own copy for everything else.
 *
 * This is a source pin. The taps are lambdas inside a private composable that only a mounted overlay window reaches, and
 * standing up that window, the Quick launch catalog and a refused Main route under Robolectric would be out of proportion
 * for one argument at three call sites. The shared context choice is behaviour-tested in [CompanionFeedbackContextTest], and
 * the helpers' one toast in [SecondaryQuickLaunchTapFeedbackTest].
 */
class CompanionRecentsOverlayToastDisplayTest {

    private val source: List<String> =
        File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionRecentsOverlayHost.kt").readLines()

    @Test
    fun `every Launch unavailable toast in the drawer is made from the drawer window's display`() {
        val copyLines = source.indices.filter { source[it].trim() == "unavailableCopy = \"Launch unavailable. Try again.\"," }
        assertEquals(
            "the drawer host has three taps that can report: quick launch, pinned quick launch and the display choice",
            3,
            copyLines.size,
        )
        copyLines.forEach { index ->
            assertEquals(
                "the tap whose copy is on line ${index + 1} makes its toast from the display of the drawer's own window",
                "feedbackContext = { companionFeedbackContext(appContext, drawerView.display?.displayId) },",
                source[index - 1].trim(),
            )
        }
        assertTrue(
            "the drawer makes no toast of its own: its taps' one toast is the shared helpers'",
            source.none { it.contains("Toast.makeText(") },
        )
    }

    @Test
    fun `the drawer's view is the composition's own view`() {
        assertTrue(
            "OverlayRecentsContent reads the view of the drawer's window as `val drawerView = LocalView.current`",
            source.any { it.trim() == "val drawerView = LocalView.current" },
        )
    }
}

package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round-5 triage 4a item 2, Thor build 10524eb4 with the route on gbc: an edge-swipe reclaim and a SWAP each logged
 * `destinationChanged=true cause=compose` during the reclaim hold, then `shelfEnter finished … entry=ramp`. The shelf
 * diagnostic reads `cause=compose` only when its remembered snapshot is empty, which is a fresh composition of the whole
 * Presentation window (the snapshot sits outside every gate of the list). The list came back in a new window, and an
 * entry history kept in `remember` started empty there.
 *
 * These cases drive production's own history ([companionShelfEntryHistory]) through that path: one window's composition
 * commits, then a second composition takes a history of its own, as a new window does.
 */
class CompanionShelfEntryHistoryTest {

    private val gbc = CompanionLibraryRoute.Destination.Platform("gbc", "Game Boy Color")
    private val threeDs = CompanionLibraryRoute.Destination.Platform("3ds", "Nintendo 3DS")
    private val lattice = CompanionLibraryRoute.Destination.Lattice

    @Before
    fun forgetEarlierWindows() {
        resetCompanionShelfEntryMemoryForTest()
    }

    @Test
    fun `a list composed again in a fresh window after a reclaim snaps`() {
        val first = companionShelfEntryHistory()
        compose(first, lattice, admitted = null)
        assertFalse("the player's first open ramps", compose(first, gbc))
        // The reclaim's hold recomposes the first window with the list still admitted.
        assertFalse(first.entering("gbc"))
        compose(first, gbc)

        // The reclaim composes the list in a new window; the first window's composition is gone.
        val second = companionShelfEntryHistory()
        assertTrue("the new window's list is entering", second.entering("gbc"))
        assertTrue("and snaps, because the route never moved", compose(second, gbc))
        assertTrue("the decision holds for the rest of that entry", compose(second, gbc))
    }

    @Test
    fun `a route write between the windows still ramps`() {
        val first = companionShelfEntryHistory()
        compose(first, gbc)
        compose(first, lattice, admitted = null)
        val second = companionShelfEntryHistory()
        compose(second, lattice, admitted = null)
        assertFalse("a lattice tap is a navigation", compose(second, gbc))
    }

    @Test
    fun `a fresh window onto another platform ramps`() {
        val first = companionShelfEntryHistory()
        compose(first, threeDs)
        val second = companionShelfEntryHistory()
        assertFalse(compose(second, gbc))
    }

    @Test
    fun `inside one window a mode round trip snaps and Back then a tap ramps`() {
        val window = companionShelfEntryHistory()
        compose(window, gbc)
        // NOW_PLAYING refuses the list; the route stays on gbc.
        compose(window, gbc, admitted = null)
        assertTrue("the round trip snaps", compose(window, gbc))
        compose(window, lattice, admitted = null)
        assertFalse("Back then a lattice tap ramps", compose(window, gbc))
    }

    @Test
    fun `the Presentation builds its history through the shared memory`() {
        val presentation = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt").readText()
        assertTrue(
            "the composition must take its history from companionShelfEntryHistory()",
            presentation.contains("val shelfEntryHistory = remember { companionShelfEntryHistory() }"),
        )
        assertTrue(
            "and read and commit the decision through it",
            presentation.contains("shelfEntryHistory.snaps(libraryDestination, platformDestination?.platformIdValue)") &&
                presentation.contains(
                    "shelfEntryHistory.commit(libraryDestination, platformDestination?.platformIdValue, shelfEntrySnaps)",
                ),
        )
    }

    /** One committed composition: the decision read in composition, then the SideEffect commit. */
    private fun compose(
        history: CompanionShelfEntryHistory,
        route: CompanionLibraryRoute.Destination,
        admitted: String? = (route as? CompanionLibraryRoute.Destination.Platform)?.platformIdValue,
    ): Boolean {
        val snaps = history.snaps(route, admitted)
        history.commit(route, admitted, snaps)
        return snaps
    }
}

package com.gruning.mimir.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Library dock's half of core-input's two-channel focus law — the twin of the Home dock pin in
 * `feature-library`'s `HomeShelfHeroTileConservesFocusThroughScopePushTest`.
 *
 * Both docks must read the LIT channel, and they must read it the same way. One dock behaving two ways
 * depending on which shelf you arrived from is the drift the one-language rule forbids, and this pair of
 * rails has drifted before: the focus-conservation lane was fenced out of `AppShelfQuickRail.kt` while the
 * dock lane held it, so for a build the Library rail and the Home rail disagreed about their own ring.
 *
 * A device report on 2026-08-15 found a stale tile ring after focus moved to the dock. A dock is persistent visible chrome sitting next to the tiles, never a covered
 * surface — it has no ring to conserve, only one to light while it owns input.
 *
 * Source-text pins because `app` has no Compose harness (same precedent as `UtilityFilesRouteTest`).
 */
class DockSeatDiesWithItsScopeTest {

    private fun appSource(fileName: String): String = listOf(
        java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        java.io.File("app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        java.io.File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
    ).firstOrNull { it.isFile }?.readText()
        ?: throw AssertionError("$fileName not reachable from the test working directory")

    @Test
    fun `the Library dock's lit seat reads the live scope, never the remembered one`() {
        val source = appSource("AppShelfQuickRail.kt")

        assertTrue(
            "LibraryShelfQuickRail's focusedAction must route through FocusAuthority.litFocusIn so the " +
                "seat goes dark the moment focus returns to the shelf tiles",
            source.contains("authority.litFocusIn(LibraryQuickRailScope, focus)"),
        )
        assertFalse(
            "the conserved read that shipped for one build must be gone -- it leaves the last visited " +
                "seat lit indefinitely, a second ring with no input behind it",
            source.contains("authority.renderedFocusIn(LibraryQuickRailScope, focus)"),
        )
    }

    @Test
    fun `the quick-launch panel's slot ring reads the live scope too`() {
        val source = appSource("HomeQuickLaunchOverlay.kt")

        assertTrue(
            "the QL panel stays composed and VISIBLE under its own Edit picker, so a conserved ring there " +
                "is the same artifact as the dock's: a lit row the picker has taken input away from",
            source.contains("authority.isLitFocusIn("),
        )
        assertFalse(
            "no conserved ring read may remain in this file",
            source.contains("isRenderedFocusIn("),
        )
    }

    /** RC-50: the shelf cannot see a full-surface cover on its own. If this argument is ever dropped,
     *  `tomeFolioActive` silently defaults to false, feature-library's pins all stay green, and the
     *  chrome goes back to unmounting on the tome's first commit frame. */
    @Test
    fun `the host still tells the Home shelf when a Folio covers it`() {
        assertTrue(
            "MimirAppHost must keep supplying tomeFolioActive to HomeShelfScreen -- it is the shelf's " +
                "only signal that a full-surface cover owns input (homeShelfLiveChromeLit)",
            appSource("MimirAppHost.kt").contains("tomeFolioActive = folio != null,"),
        )
    }
}

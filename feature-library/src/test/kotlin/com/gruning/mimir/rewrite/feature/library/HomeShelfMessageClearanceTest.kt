package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Main's message rail stands above the dock on Home and on the Library's platform shelf. The app host asks [homeShelfQuickRailClearanceDp] for
 * where that dock begins, so this pins that one number to the dock each screen actually solves, on every viewport the screens are certified at:
 * Home's own quick rail band, and the Library shelf's dock lane (placed from the same bottom-gap helper, standard and compact alike).
 */
class HomeShelfMessageClearanceTest {
    private val viewports = listOf(
        832f to 468f, // The Thor's Main.
        853f to 480f,
        700f to 350f, // The certified small viewport.
        640f to 480f,
        720f to 720f, // The square handheld.
    )

    @Test
    fun `the clearance is where Home's quick rail begins, measured from the bottom edge`() {
        viewports.forEach { (w, h) ->
            listOf(false, true).forEach { compact ->
                val quickRail = solveHomeShelfGeometry(w, h, compact).quickRailBand
                assertEquals("$w x $h compact=$compact", h - quickRail.top, homeShelfQuickRailClearanceDp(h), 0.01f)
            }
        }
    }

    @Test
    fun `it is also where the Library shelf's dock begins`() {
        viewports.forEach { (w, h) ->
            val shelf = solveLibraryShelfGeometry(w, h)
            val dockTop = h - shelf.bottomPaddingDp - ShelfQuickRailLaneHeightDp
            assertEquals("$w x $h", h - dockTop, homeShelfQuickRailClearanceDp(h), 1.0f)
        }
    }

    @Test
    fun `the dock stands inside the screen, so the rail's inset is a real distance above the bottom edge`() {
        viewports.forEach { (_, h) ->
            val clearance = homeShelfQuickRailClearanceDp(h)
            assertTrue("$h: $clearance", clearance > 0f && clearance < h / 2f)
        }
    }
}

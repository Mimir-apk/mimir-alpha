package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ONE law, two surfaces:
 *
 * *The collapse badge identifies disc/version count once per game on each surface*
 *
 * Badge invariant: **the collapse badge is stated
 * exactly once per game per surface, on the element that IS the game in that surface's navigation
 * model.** List → the row. Shelf (the CAROUSEL view, since 2026-10-10 the Home shelf) → the tile.
 *
 * These are source-contract pins because both halves are Compose placement — a `remember` key set and an
 * `align` anchor are not reachable from a JVM unit test. Every anchor is presence-checked first, so a
 * rename fails loudly here instead of turning the assertion into a vacuous pass.
 */
class CollapseBadgeSurfaceLawTest {

    /**
     * R-1. The panel mirrors the focused row; the row already prints the badge for that same game, and
     * for every other visible row. Two votes on one fact was the report.
     */
    @Test
    fun `the list preview panel does not restate what its own row already says`() {
        val components = sourceFile("LibraryComponents.kt")
        val panel = sliceBetween(components, "internal fun GamePreviewPanel(", "\n): Unit\n\n")
            ?: sliceBetween(components, "internal fun GamePreviewPanel(", "\n) {")
            ?: error("GamePreviewPanel signature not found")
        assertFalse(
            "the panel must not take a collapse badge again: $panel",
            panel.contains("badge: String?"),
        )

        val screen = sourceFile("LibraryScreen.kt")
        assertTrue(
            "collapseBadge must still exist — this pin is about WHERE, not whether",
            screen.contains("internal fun collapseBadge("),
        )
        // The ROW keeps it. Exactly one collapseBadge() call site survives in the list view.
        assertEquals(
            "the list states the badge once, on the row",
            1,
            Regex("""collapseBadge\(""").findAll(withoutLineComments(screen)).count()
                - 1, // minus the declaration itself
        )
    }

    /**
     * R-2. Before this, the shelf could not say a game was a group AT ALL — device-checked, not
     * argued .
     *
     * Anchored against the favourite marker rather than placed freely: both are identity-owned art
     * chrome seated in the same art box ([GameShelf]'s `GameShelfMarkSeat`, handed to the painters as their overlay), so they
     * inherit one alpha and one scale, and sit on the corners of the PAINTED art whatever its shape. If a later change moves
     * the badge out of that box it stops riding the art, and this fails.
     */
    @Test
    fun `the shelf tile states it, anchored opposite the favourite in the same art box`() {
        val shelf = withoutLineComments(sourceFile("GameShelf.kt"))
        assertTrue(
            "the favourite marker is the anchor this pin is written against",
            shelf.contains("LibraryFavoriteMarker("),
        )
        val chrome = sliceBetween(shelf, "LibraryFavoriteMarker(", "private val GameShelfMarkInset")
            ?: error("art-chrome block not found")
        assertTrue("the shelf must state the collapse badge: $chrome", chrome.contains("CollapseBadge("))
        // On the art the badge stands on the etched plate the favourite rune has (a tinted pill vanishes on a bright cover), and the list
        // row keeps its own pill: the shelf must not call the row's.
        assertTrue("the shelf's badge is its own etched plate: $chrome", chrome.contains("GameShelfCollapseBadge("))
        assertFalse("not the list row's tinted pill: $chrome", Regex("""(?<![A-Za-z])CollapseBadge\(""").containsMatchIn(chrome))
        assertTrue("badge anchors TopEnd: $chrome", chrome.contains("Alignment.TopEnd"))
        assertTrue(
            "both marks share the tile's corner inset, so they are one law: $chrome",
            chrome.contains("GameShelfMarkInset") && chrome.indexOf("GameShelfMarkInset") < chrome.indexOf("CollapseBadge("),
        )
        // The badge appears on every tile, matching the star — so it must not be gated on the hero.
        //
        // Written as "no `selected`/`focused` anywhere between the favourite mark and the badge" rather than as a
        // pattern for one shape of `if`. The first draft of this pin matched `if (centered)` on the line
        // directly above `CollapseBadge` and I proved it useless the same minute: reverting the mechanism
        // put the guard four lines up, the regex missed it, and the pin went green on hero-only code.
        val guardWindow = sliceBetween(chrome, "LibraryFavoriteMarker(", "CollapseBadge(")
            ?: error("badge must follow the favourite mark in the same block")
        assertFalse(
            "the badge is not hero-only; it appears on every tile: $guardWindow",
            guardWindow.contains("selected") || guardWindow.contains("focused"),
        )
        // Both painters seat them in their art box, not the envelope: a portrait cover letterboxes inside a square seat.
        assertEquals(
            "the marks reach both painters as the overlay in the art box",
            2,
            Regex("""overlay = markSeat""").findAll(shelf).count(),
        )
        // And a tile whose art is not looked up yet seats the same marks on its own square, so a favourite does not lose them for a beat.
        val pending = sliceBetween(shelf, "item.artPending ->", "squareModel != null ->")
            ?: error("the pending arm of the tile's art was not found")
        assertTrue("the pending arm seats the marks: $pending", pending.contains("markSeat"))
    }

    private fun sliceBetween(source: String, start: String, end: String): String? {
        val from = source.indexOf(start)
        if (from < 0) return null
        val to = source.indexOf(end, from + start.length)
        if (to < 0) return null
        return source.substring(from, to)
    }

    private fun withoutLineComments(source: String): String =
        source.lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}

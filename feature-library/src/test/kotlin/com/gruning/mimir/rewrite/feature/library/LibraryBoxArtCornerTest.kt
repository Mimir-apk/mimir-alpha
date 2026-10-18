package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Box art uses the Home shelf corner style, with the focus ring following its full silhouette.
 *
 * **A `dp` literal in a corner position is the defect.** Three independent mechanisms let inconsistent corner geometry survive, each involving a literal:
 *
 *  - the focus ring was fixed twice — 2026-08-19 for its width, RC-36 on 2026-08-21 for its rect — and
 *    both tickets passed `cornerRadius` straight through, so the CURVE never moved;
 *  - an `8.dp` outlived the very commit that reverted its rationale (`36b81314`, 2026-07-08, titled
 *    *"rounder corners"*);
 *  - the 2026-08-09 corner-token change landed on one of two duplicated book components, and the Library
 *    uses the other.
 *
 * So this test does not check a NUMBER. Checking 10 would pass the moment someone wrote a fresh `10.dp`
 * literal at a new site, which is exactly how the census reached three different radii. It checks that
 * every box-art corner is the same SYMBOL — the only property under which those three failures cannot
 * recur, and one no future value change can quietly break.
 *
 * Its limits, stated rather than implied: this is a SOURCE gate. It cannot see what a pixel does, so it
 * cannot tell you the ring hugs the art. The closeable check for that is the paired capture — same game,
 * carousel, one L/R press to list, cropped to the corner at equal zoom — and no source diff can fake it.
 */
class LibraryBoxArtCornerTest {

    private fun featureSource(name: String): String = read(
        "feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name",
        "src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name",
    )

    private fun coreUiSource(name: String): String = read(
        "core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name",
        "../core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name",
    )

    private fun read(vararg paths: String): String =
        paths.map(::File).firstOrNull(File::isFile)?.readText()
            ?: error("none of ${paths.toList()} found from ${File(".").absolutePath}")

    /** Every surface that paints a game's box art, and the file its corner is declared in. */
    private val boxArtSurfaces = listOf(
        "LibraryComponents.kt" to "the gamelist LIST preview panel — the OTHER answer, one L/R press away",
        "HomeLaunchCard.kt" to "the launch card's free art",
        "HomeTomeFace.kt" to "the Tome face plate",
        "HomeTomeFolio.kt" to "the Tome member well",
    )

    @Test
    fun `the bespoke gamelist corner is gone, not merely re-valued`() {
        listOf("LibraryGameShelf.kt", "GameShelf.kt", "GamelistMedia.kt").forEach { file ->
            assertTrue(
                "GamelistCoverCorner was a private literal beside a shared token, invisible to anyone " +
                    "fixing 'the corner' from the token side. Re-valuing it would leave that invisibility " +
                    "intact — it has to be GONE ($file).",
                !featureSource(file).contains("GamelistCoverCorner"),
            )
        }
    }

    @Test
    fun `every box art surface takes the one corner symbol`() {
        boxArtSurfaces.forEach { (file, what) ->
            assertTrue(
                "$what ($file) must take MimirBoxArtCorner",
                featureSource(file).contains("MimirBoxArtCorner"),
            )
        }
    }

    /**
     * The census, as a test. One object had three different corner curves; this is the
     * assertion that cannot be satisfied by fixing only one surface.
     */
    @Test
    fun `the shelf and the list view no longer disagree about the same game`() {
        val shelf = featureSource("GameShelf.kt")
        val listPanel = featureSource("LibraryComponents.kt")
        // The shelf tile is the ledge painter, which clips its art and rings it with MimirLedgeCoverCorner (the last test here
        // pins that to BE the box-art corner). The hide's flash sits inside that same corner.
        assertTrue(
            "the shelf tile must take the ledge cover corner, which is the box-art symbol, for its hide flash",
            shelf.contains("MimirLedgeCoverCorner"),
        )
        assertTrue(
            "the list preview panel painted box art at MimirSpacing.radiusCard — a CARD radius on a BOX " +
                "ART, which is why one game wore two curves one press apart",
            listPanel.contains("RoundedCornerShape(MimirBoxArtCorner)"),
        )
    }

    // R-4.3 stood here: it pinned the carousel's BOOK-fallback ring to the book's proportional corner law. The Library's shelf is the
    // Home shelf now (2026-10-10), whose no-art tile is the ledge painter's glyph inside the same ring as any cover, so that surface -- and
    // the hug box the pin solved against -- are gone with the cards.

    /**
     * The Home shelf is the reference style, so it must be provably unchanged rather than re-verified by
     * eye. Defining its name as the new token, instead of as a second 10, is what makes that provable.
     */
    @Test
    fun `the home shelf corner is defined AS the box art corner, not as a second ten`() {
        val frame = coreUiSource("MediaArtFrame.kt")
        assertTrue(
            "MimirLedgeCoverCorner must be defined as MimirBoxArtCorner so Home cannot drift from it, " +
                "promoting the token must preserve the reference surface",
            frame.contains("val MimirLedgeCoverCorner = MimirBoxArtCorner"),
        )
        assertEquals(
            "exactly one declaration may carry the box-art corner's VALUE",
            1,
            Regex("""val MimirBoxArtCorner = \d+\.dp""").findAll(frame).count(),
        )
    }
}

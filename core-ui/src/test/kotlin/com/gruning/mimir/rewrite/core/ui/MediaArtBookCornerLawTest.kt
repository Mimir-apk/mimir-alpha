package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shared corner-token coverage in core-ui.
 *
 * `LibraryBoxArtCornerTest` (feature-library) closed R-1 for the five surfaces that paint game box art.
 * Two classes of corner literal survived it, in this module, and both are here because they are the shapes
 * that gate could not see:
 *
 *  - **[MediaArtBookCover]'s inline duplicate of [mimirNorseBookCoverCorner].** Not a `dp` literal at all
 *    -- bare `Float` arithmetic, `(wDp * 0.05f).coerceIn(4f, 12f)`, character-for-character the function's
 *    own body. This duplicate is the documented reason the earlier corner-token fix missed this component:
 *    it landed on [MimirNorseBookCover] and the Library paints this one.
 *  - **[MimirLedgeFocusRingShell]'s animated radius**, which carried its pair as bare `Float`s feeding
 *    `animateFloatAsState`. This site needs a separate assertion, precisely because a builder applying a "no `dp` literal" rule finds it does not fit here
 *    and then either weakens the gate or stalls.
 *
 * **What this test does not prove, stated rather than implied:** it reads source text and pure functions.
 * It cannot see a painted pixel, cannot measure a corner arc, and would pass a build in which every symbol
 * is right and the art still renders wrong. The evidence for that is the paired capture (§4.1) and D5 of
 * the device matrix (§4.2) -- the book fallback, all four corners, spine side included.
 */
class MediaArtBookCornerLawTest {

    private fun coreUiSource(name: String): String {
        val candidates = listOf(
            File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    private fun bookCoverBody(): String {
        val body = coreUiSource("MediaArtFrame.kt")
            .substringAfter("fun MediaArtBookCover(")
            .substringBefore("\n@Composable")
        assertTrue(
            "search window missed MediaArtBookCover's body -- every assertion below would pass vacuously",
            body.contains("aspectRatio(3f / 4f") && body.contains("bookShape") && body.length > 800,
        )
        return body
    }

    /** R-4.1. The duplicate is the defect, not the value it happens to compute. */
    @Test
    fun `the book cover calls the shared corner law instead of inlining a copy of it`() {
        val body = bookCoverBody()
        assertTrue(
            "MediaArtBookCover must call mimirNorseBookCoverCorner. Recomputing the same arithmetic locally " +
                "is what made this component invisible to a fix aimed at 'the book corner' -- the 2026-08-09 " +
                "corner rule changed in MimirNorseBookCover while this kept a separate copy",
            body.contains("mimirNorseBookCoverCorner(wDp)"),
        )
        assertFalse(
            "the inline copy of the corner law may not come back",
            body.contains("* 0.05f).coerceIn(4f, 12f)"),
        )
    }

    /**
     * R-4.2, ported from [MimirNorseBookCover]. The spine corners were a separate, much squarer 2-5dp while
     * the ring stayed uniform at the fore corner, so on the spine side a near-square cover corner sat
     * OUTSIDE the ring's curve and could escape the focus ring. The book still reads as a book: spine shading and the fore-edge page stack draw that
     * silhouette. Only the corner radius is uniform.
     */
    @Test
    fun `the book cover's corner is uniform on all four corners, spine side included`() {
        val body = bookCoverBody()
        assertTrue(
            "the shape must be built from one radius",
            body.contains("val bookShape = RoundedCornerShape(bookCorner)"),
        )
        assertFalse(
            "per-corner asymmetry is what put the spine outside the ring; it may not return",
            body.contains("topStart =") || body.contains("bottomStart =") ||
                body.contains("topEnd =") || body.contains("bottomEnd ="),
        )
        assertFalse(
            "the squarer spine radius is gone, not merely unused",
            body.contains("cornerSpine") || body.contains("* 0.02f).coerceIn(2f, 5f)"),
        )
    }

    /**
     * The law itself, as arithmetic rather than as text -- so a future edit to
     * [mimirNorseBookCoverCorner] that silently re-values the book corner reddens here too. These are the
     * widths the gamelist actually hands it: the book's hug box is 3:4 inside a platform cell, so at the
     * carousel's real geometry the width lands in the ~177-225dp band, which this law maps to ~8.8-11.3dp.
     */
    @Test
    fun `the book corner law is proportional and bounded, and clamps at both ends`() {
        assertEquals("narrow end clamps to the floor", 4.dp, mimirNorseBookCoverCorner(20f))
        assertEquals("wide end clamps to the ceiling", 12.dp, mimirNorseBookCoverCorner(400f))
        assertEquals("PSP's ~177dp book", 8.85f.dp, mimirNorseBookCoverCorner(177f))
        assertEquals("GameCube's ~216dp book", 10.8f.dp, mimirNorseBookCoverCorner(216f))
        assertTrue(
            "a degenerate width must not produce a negative or zero radius",
            mimirNorseBookCoverCorner(0f) >= 4.dp && mimirNorseBookCoverCorner(-99f) >= 4.dp,
        )
    }

    /**
     * Separate assertion because a literal-grep cannot reach it. Promoting these
     * did not move a pixel -- 16f and 10f were already [MimirSwitchTileCorner] and [MimirBoxArtCorner] --
     * which is exactly what makes the change safe to assert rather than something to re-verify by eye.
     */
    @Test
    fun `the ledge ring's animated radius takes symbols, not bare floats`() {
        val frame = coreUiSource("MediaArtFrame.kt")
        assertTrue(
            "MimirLedgeFocusRingShell's radius must animate between the two named corners",
            frame.contains(
                "targetValue = if (square) MimirSwitchTileCorner.value else MimirBoxArtCorner.value,",
            ),
        )
        assertFalse(
            "the bare-float pair may not return -- it is the one corner literal a dp-grep cannot see",
            frame.contains("if (square) 16f else 10f"),
        )
        assertEquals("the switch tile's corner keeps the value it shipped with", 16.dp, MimirSwitchTileCorner)
        assertEquals(
            "and it is its OWN symbol, not a second name for the box-art corner: a square app tile is not " +
                "box art, so future box-art corner changes must not affect it",
            false,
            MimirSwitchTileCorner == MimirBoxArtCorner,
        )
    }

    /** The switch tile's own clip and ring, which carried the same literal twice. */
    @Test
    fun `the switch tile clips and rings at one symbol`() {
        val frame = coreUiSource("MediaArtFrame.kt")
        assertTrue(
            "the switch tile's clip must take the symbol",
            frame.contains("val shape = RoundedCornerShape(MimirSwitchTileCorner)"),
        )
        assertTrue(
            "and its ring must take the same one -- two literals carrying the same digits is exactly what " +
                "R-1 forbids, because nothing keeps them equal",
            frame.contains("cornerRadius = MimirSwitchTileCorner"),
        )
        assertFalse(
            "no bare 16.dp may remain in a corner position in this file",
            frame.contains("RoundedCornerShape(16.dp)") || frame.contains("cornerRadius = 16.dp"),
        )
    }
}

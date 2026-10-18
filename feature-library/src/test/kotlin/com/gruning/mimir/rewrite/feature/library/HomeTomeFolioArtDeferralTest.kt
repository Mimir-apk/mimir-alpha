package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The folio defers each cell's Coil painter by one frame so the opening frame does not build 21 of
 * them inside its measure pass (12.2 ms of a ~45 ms phase, measured on the Thor 2026-09-04).
 *
 * The risk that makes this worth pinning is not the timing — it is what a deferred cell LOOKS like.
 * Implemented by nulling `artUri`, the deferral would read as "this game has no art" and flash the
 * placeholder across the whole grid for a frame, which is a far worse trade than the 12 ms it buys.
 * These tests pin the predicate's semantics and that appearance contract. They do NOT see the call
 * site: a future edit that deferred by nulling `artUri` in `HomeTomeFolio` would still pass every
 * assertion here. What catches that is the device A/B and the eye. Stated so nobody reads green here
 * as proof the wiring is right.
 */
class HomeTomeFolioArtDeferralTest {

    private val artUri = "content://media/external/images/media/4242"

    @Test
    fun `art is deferred only until the folio's first frame has been built`() {
        assertTrue(homeTomeFolioDefersArtPainter(folioArtAttached = false, artUri = artUri))
        assertFalse(homeTomeFolioDefersArtPainter(folioArtAttached = true, artUri = artUri))
    }

    @Test
    fun `a cell with no art at all is never deferred, so the two states can never be confused`() {
        assertFalse(homeTomeFolioDefersArtPainter(folioArtAttached = false, artUri = null))
        assertFalse(homeTomeFolioDefersArtPainter(folioArtAttached = true, artUri = null))
    }

    /**
     * THE APPEARANCE GUARANTEE. A deferred cell must paint the quiet backdrop that any cover paints
     * while it loads — never the no-art placeholder. If this goes red, the folio opens with a grid
     * of "no art" tiles for a frame.
     */
    @Test
    fun `a deferred cell reads as art-is-coming, not as no-art`() {
        val deferred = homeTomeFolioDefersArtPainter(folioArtAttached = false, artUri = artUri)
        assertTrue(deferred)
        // A deferred cell has no painter, so it is not ready — but it still knows its art URI.
        val artReadyWhileDeferred = false
        assertFalse(
            "a deferred cell must NOT show the no-art placeholder",
            homeTomeFolioArtShowsPlaceholder(artUri, artReadyWhileDeferred),
        )
        assertTrue(
            "a deferred cell must show the same quiet backdrop a loading cover shows",
            homeTomeFolioArtShowsQuietBackdrop(artUri, artReadyWhileDeferred),
        )
    }

    @Test
    fun `once attached, a ready cover paints neither the placeholder nor the backdrop`() {
        assertFalse(homeTomeFolioArtShowsPlaceholder(artUri, true))
        assertFalse(homeTomeFolioArtShowsQuietBackdrop(artUri, true))
    }
}

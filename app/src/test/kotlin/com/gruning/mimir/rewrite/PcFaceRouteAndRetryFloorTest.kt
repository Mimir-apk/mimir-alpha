package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two device-reported defects, one root each.
 *
 *
 */
class PcFaceRouteAndRetryFloorTest {

    // ---- the face kind a SteamGridDB grid records ----

    @Test
    fun `a square grid is a square, a portrait grid is the box`() {
        assertEquals(
            "a square pick is a square tile (Icon art takes any shape and the tile follows " +
                "the pick). It used to be the physical article, which a PC title does not have",
            WiredFaceKind.SQUARE,
            wiredFaceKindOf(SteamGridDbSquareGridType),
        )
        assertEquals(
            "the cover lane cannot honour a SQUARE record (its file is not in sgdb2/), so a square grid saved " +
                "there before the merge keeps the PHYSICAL record it always had",
            WiredFaceKind.PHYSICAL,
            coverLaneFaceKind(wiredFaceKindOf(SteamGridDbSquareGridType)),
        )
        assertEquals(WiredFaceKind.COVER, coverLaneFaceKind(WiredFaceKind.COVER))
        assertEquals(WiredFaceKind.PHYSICAL, coverLaneFaceKind(WiredFaceKind.PHYSICAL))
        assertNull(coverLaneFaceKind(null))
        assertEquals(
            "and the portrait grid is the box, so it lands in the portrait cover lane",
            WiredFaceKind.COVER,
            wiredFaceKindOf(SteamGridDbPortraitGridType),
        )
    }

    /**
     * The shape is read off the ASSET, never off the request. A request for portrait can come back with
     * whatever the provider actually has, and recording the request would put a claim in his saved catalog
     * that the file does not support — which paints a square asset in a portrait lane.
     */
    @Test
    fun `the recorded shape comes from the asset's own dimensions`() {
        assertEquals(SteamGridDbPortraitGridType, steamGridDbGridMediaType(600, 900))
        assertEquals(SteamGridDbSquareGridType, steamGridDbGridMediaType(512, 512))
        assertEquals(
            "wider than tall is still not a portrait box",
            SteamGridDbSquareGridType,
            steamGridDbGridMediaType(920, 430),
        )
        assertNull(
            "no size from the provider means no claim about the shape",
            steamGridDbGridMediaType(0, 0),
        )
    }

    /**
     * ScreenScraper's families stay prefix-matched and unchanged: the provider grows them over time, and a
     * new `support-…` should be a disc on the day it appears.
     */
    @Test
    fun `ScreenScraper's vocabulary is untouched`() {
        assertEquals(WiredFaceKind.PHYSICAL, wiredFaceKindOf("support-2d"))
        assertEquals(WiredFaceKind.COVER, wiredFaceKindOf("box-3d"))
        assertNull(wiredFaceKindOf("clearlogo"))
    }

    // ---- the retry floor ----

    @Test
    fun `the first search is always allowed`() {
        assertTrue(
            "nothing has come back barren yet -- and the subtraction would overflow if this were not " +
                "special-cased",
            homeCustomEnrichRetryAllowed(nowMillis = 0L, lastEmptyOrFailedAt = Long.MIN_VALUE),
        )
    }

    @Test
    fun `spamming after an empty result is refused until the floor passes`() {
        assertFalse(
            "this is the probing bug: every press fired a live transaction, so holding the button on a " +
                "platform the provider cannot answer for spawned unbounded concurrent probes",
            homeCustomEnrichRetryAllowed(
                nowMillis = 1_000L,
                lastEmptyOrFailedAt = 1_000L - (HomeCustomEnrichRetryFloorMs - 1L),
            ),
        )
        assertTrue(
            "and once the floor has passed a retry is his to make",
            homeCustomEnrichRetryAllowed(
                nowMillis = 1_000L,
                lastEmptyOrFailedAt = 1_000L - HomeCustomEnrichRetryFloorMs,
            ),
        )
    }
}

package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FUX-01 §3.4 (Cover-Art Law): the natural-aspect resolver behind the frameless ledge cover. Real covers
 * pass through untouched; unknown/degenerate sources resolve to null (the caller reserves neutral bounds
 * and only ever fades art in — the shelf never reflows).
 */
class MimirLedgeCoverArtTest {

    @Test
    fun `real cover proportions pass through untouched`() {
        // PS1 jewel case ~ 0.86, GBA box ~ 1.0, N64 landscape box ~ 1.39.
        assertEquals(0.86f, mimirLedgeCoverAspect(860f, 1000f))
        assertEquals(1.0f, mimirLedgeCoverAspect(500f, 500f))
        assertEquals(1.39f, mimirLedgeCoverAspect(1390f, 1000f))
    }

    @Test
    fun `degenerate proportions clamp instead of shattering the shelf`() {
        assertEquals(MimirLedgeCoverMinAspect, mimirLedgeCoverAspect(100f, 1000f))
        assertEquals(MimirLedgeCoverMaxAspect, mimirLedgeCoverAspect(4000f, 1000f))
    }

    @Test
    fun `unknown sizes resolve to null so art only ever fades in`() {
        assertNull(mimirLedgeCoverAspect(Float.NaN, 100f))
        assertNull(mimirLedgeCoverAspect(100f, Float.NaN))
        assertNull(mimirLedgeCoverAspect(Float.POSITIVE_INFINITY, 100f))
        assertNull(mimirLedgeCoverAspect(0f, 100f))
        assertNull(mimirLedgeCoverAspect(100f, 0f))
        assertNull(mimirLedgeCoverAspect(-100f, 100f))
    }

    // ── FUX-G P0 (cold-Home crash): the intrinsic seam gates Size.Unspecified BEFORE any read ──────

    @Test
    fun `unspecified intrinsic size resolves to unknown aspects without throwing`() {
        // Pre-guard, reading width/height off the Unspecified sentinel threw
        // "IllegalStateException: Size is unspecified" and killed cold Home.
        val aspects = mimirLedgeIntrinsicAspects(Size.Unspecified)
        assertNull(aspects.raw)
        assertNull(aspects.clamped)
    }

    @Test
    fun `specified intrinsic size resolves both aspects`() {
        val aspects = mimirLedgeIntrinsicAspects(Size(860f, 1000f))
        assertEquals(0.86f, aspects.raw)
        assertEquals(0.86f, aspects.clamped)
    }

    @Test
    fun `degenerate specified intrinsic size resolves null without throwing`() {
        assertNull(mimirLedgeIntrinsicAspects(Size.Zero).clamped)
        assertNull(mimirLedgeIntrinsicAspects(Size(100f, 0f)).raw)
        assertNull(mimirLedgeIntrinsicAspects(Size(0f, 100f)).clamped)
    }

    @Test
    fun `the fallback is for no art wired and nothing else`() {
        // R8 : wired art shows NOTHING while it loads and NOTHING when it fails. One argument, so no caller
        // can bring the fallback back for a decode failure.
        assertFalse(mimirArtFallbackVisible(hasArtSource = true))
        assertTrue(mimirArtFallbackVisible(hasArtSource = false))
    }

    // ── a custom pick whose FILE NO LONGER EXISTS is unwired; only a file that exists and fails to decode is "failed" ──────

    @Test
    fun `a missing local file is unwired and takes the no-art fallback`() {
        val gone = { _: String -> false }
        assertFalse(mimirArtSourceWired("/storage/emulated/0/Mimir/custom-cover-art/gone.png", gone))
        assertFalse(mimirArtSourceWired("file:///storage/emulated/0/Mimir/custom-cover-art/gone.png", gone))
        assertFalse(mimirArtSourceWired(java.io.File("/storage/emulated/0/Mimir/custom-cover-art/gone.png"), gone))
        assertTrue(mimirArtFallbackVisible(hasArtSource = mimirArtSourceWired("/x/gone.png", gone)))
    }

    @Test
    fun `an existing local file is wired, so a decode failure on it draws nothing`() {
        val present = { _: String -> true }
        assertTrue(mimirArtSourceWired("/storage/emulated/0/Mimir/custom-cover-art/corrupt.png", present))
        assertTrue(mimirArtSourceWired("file:///storage/emulated/0/Mimir/custom-cover-art/corrupt.png", present))
        assertTrue(mimirArtSourceWired(java.io.File("/x/corrupt.png"), present))
        assertFalse(mimirArtFallbackVisible(hasArtSource = mimirArtSourceWired("/x/corrupt.png", present)))
    }

    @Test
    fun `the existence check is asked the path the source names`() {
        val asked = mutableListOf<String>()
        val record = { path: String -> asked += path; true }
        mimirArtSourceWired("/a/b.png", record)
        mimirArtSourceWired("file:///c/d%20e.png", record)
        mimirArtSourceWired(java.io.File("/f/g.png"), record)
        // A File reports its own platform separators, so the expectation is built the same way.
        assertEquals(listOf("/a/b.png", "/c/d e.png", java.io.File("/f/g.png").path), asked)
    }

    @Test
    fun `a remote or provider source cannot be checked and counts as wired`() {
        val neverAsked = { _: String -> error("a remote source must not be stat-ed") }
        assertTrue(mimirArtSourceWired("https://cdn.example.org/art.png", neverAsked))
        assertTrue(mimirArtSourceWired("content://media/external/images/1", neverAsked))
        assertTrue(mimirArtSourceWired(12345, neverAsked))
    }

    @Test
    fun `null and blank are never wired`() {
        val neverAsked = { _: String -> error("nothing to stat") }
        assertFalse(mimirArtSourceWired(null, neverAsked))
        assertFalse(mimirArtSourceWired("", neverAsked))
        assertFalse(mimirArtSourceWired("   ", neverAsked))
    }
}

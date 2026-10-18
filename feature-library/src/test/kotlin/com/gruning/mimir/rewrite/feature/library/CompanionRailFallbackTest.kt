package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA-23, the companion rail's coverless games show the Mimir book, not a letter.
 *
 * This site previously read *"a quiet themed panel carrying the initial. Deliberately not the book fallback
 * -- that is a rail by another name."* Device observation located the inconsistent letter placeholder on this surface.
 *
 * It does NOT reopen the 2026-09-01 *"no box rail for companion shelf art or platform art"* policy. That
 * one removed `MediaArtFrame`, which draws a plate and border AROUND art that already has its own edges.
 * The book is not a frame around anything -- it IS the content, drawn only when there is no art to
 * double-frame. Both assertions below exist so the two policies stay legible as compatible rather than one
 * looking like a silent reversal of the other.
 */
class CompanionRailFallbackTest {

    private fun shelfSource(): String {
        val candidates = listOf(
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/CompanionPlatformShelf.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/CompanionPlatformShelf.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("CompanionPlatformShelf.kt not found from ${File(".").absolutePath}")
    }

    @Test
    fun `a coverless game on the rail falls back to the Mimir book`() {
        val source = shelfSource()

        assertTrue(
            "search window missed the file -- the assertions below would pass vacuously",
            source.contains("artRef") && source.length > 2000,
        )
        assertTrue(
            "the coverless branch must render the shared book fallback",
            source.contains("MediaArtFallbackGlyph("),
        )
        assertTrue(
            "and it must carry the platform so the per-platform leather treatment applies -- the letter " +
                "panel could not express that at all",
            source.contains("platformIdValue = game.platformId.value"),
        )
    }

    @Test
    fun `the single-letter placeholder is gone from the rail`() {
        val source = shelfSource()
        assertFalse(
            "the initial-letter stand-in is what QA-23 reported; it must not come back here",
            source.contains("game.title.take(1).uppercase()"),
        )
    }

    @Test
    fun `real art is drawn bare without a box rail`() {
        val source = shelfSource()

        assertFalse(
            "MediaArtFrame is the plate-and-border this shelf had removed; it must not return",
            source.contains("MediaArtFrame("),
        )
        assertTrue(
            "present art is still FIT at its own aspect rather than cropped or framed",
            source.contains("contentScale = ContentScale.Fit"),
        )
    }
}

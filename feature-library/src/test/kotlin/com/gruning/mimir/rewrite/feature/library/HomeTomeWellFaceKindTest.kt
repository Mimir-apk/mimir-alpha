package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Tome well, brought onto the same footing as the Home shelf and the launch card (2026-08-25).
 *
 * It carried two faults of the same family — a kind worked out by comparing URIs instead of being told:
 *
 *  1. `wellArtUri == physicalMediaUri` answered NO for every provider render, so a well showing an
 *     enrichment-downloaded disc reported itself a cover.
 *  2. The wired tier read only the cartridge store, so a hand-picked COVER never reached a well at all —
 *     and once it could, that same comparison would have called it a disc.
 */
class HomeTomeWellFaceKindTest {

    private fun face(
        wired: String? = null,
        wiredPhysical: Boolean = true,
        artwork: String? = null,
    ) = homeTomeWellFace(
        physicalMediaUri = wired,
        wiredFaceShape = if (wiredPhysical) ShelfFaceShape.PHYSICAL else ShelfFaceShape.LEDGE_COVER,
        artworkUri = artwork,
    )

    private fun source(name: String): String {
        val candidates = listOf(
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    /**
     * §5.4 — the ABSENCE needs its own gate, or it is only absent until someone types it back.
     *
     * Two tests stood here. One pinned that a downloaded disc no longer outranks the cover; the other
     * that a lone disc is not promoted into a missing cover's place. Both passed a `provider` argument
     * that the shelf-face family no longer HAS — removed the Cartridge Hero
     * promotion, and that switch was the only thing which could ever select the tier — so neither can be
     * written as a call. What they guarded is a property of the signature now, and that is what this reads.
     *
     * Reading the source rather than asserting a behaviour is deliberate, and is the only way to catch
     * the regression left: passing a provider uri is a compile error today, so the failure mode is
     * someone RE-ADDING the parameter. The Cartridge Hero SCRAPE is untouched by all of this and is
     * expected to stay reachable — through `launchCardWiredFace`'s L2 and `resolveTomeScopedArtwork`,
     * both in `:app`, neither of them this file's subject.
     */
    @Test
    fun `the shelf face family has no provider tier left to promote`() {
        val src = source("HomeTomeFolioModels.kt")
        assertTrue("search window missed the file -- the assertion below would pass vacuously", src.length > 2000)
        assertTrue("the family must still be here to be pinned", src.contains("fun mimirShelfFace("))
        assertFalse(
            "a provider/support uri may not re-enter the Shelf face ladder; it belongs to the launch " +
                "card's L2 and to the Library list row, which have their own laws",
            src.contains("providerPhysicalMediaUri"),
        )
    }

    @Test
    fun `a hand-picked cover reaches the well and is not called a disc`() {
        val f = face(wired = "file:///picks/box-3D.png", wiredPhysical = false, artwork = "file:///cover.png")
        assertEquals("his pick outranks the cover, C4", "file:///picks/box-3D.png", f.uri)
        assertFalse(
            "and it is a COVER -- the old comparison would have said disc, because the pick is what it " +
                "compared against",
            homeTomeFolioMemberIsPhysical(f),
        )
    }

    @Test
    fun `a hand-picked cartridge is still a cartridge`() {
        val f = face(wired = "file:///picks/disc.png", wiredPhysical = true, artwork = "file:///cover.png")
        assertEquals("file:///picks/disc.png", f.uri)
        assertTrue(homeTomeFolioMemberIsPhysical(f))
    }

    @Test
    fun `a plain cover is a cover, and no art is neither`() {
        assertFalse(homeTomeFolioMemberIsPhysical(face(artwork = "file:///cover.png")))
        val empty = face()
        assertEquals(null, empty.uri)
        assertFalse("no face at all cannot be physical", homeTomeFolioMemberIsPhysical(empty))
    }

    /** The uri-only accessor the warm-cache callers use must keep agreeing with the full resolution. */
    @Test
    fun `the uri-only accessor agrees with the face it is a projection of`() {
        for (wired in listOf(null, "file:///pick.png")) {
            for (art in listOf(null, "file:///cover.png")) {
                assertEquals(
                    homeTomeWellFace(wired, ShelfFaceShape.PHYSICAL, art).uri,
                    homeTomeWellArtUri(wired, art),
                )
            }
        }
    }
}

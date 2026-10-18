package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *
 *
 * **This file previously asserted the exact opposite** and it is worth saying why, because the reversal is
 * the point. The earlier analysis attributed a Home/Tome hard cut to `VideoBackdrop`'s opaque themed floor
 * and removed it. The corrected requirement hides the Home scene completely.
 *
 * The floor is also the ONLY thing that takes the shelf to zero. Two scrims at `ScrimAlphaBlurred = 0.52`
 * still transmit roughly 5% mid-band, ~12% before the art resolves, and about double that on the metallic
 * themes; blur lowers spatial frequency, not transmission. So "glass" cannot be relied on to hide anything.
 *
 * What this pins now: no glass overlay composed over Home may suppress the floor.
 */
class GlassOverlayFloorTest {

    private fun read(path: String): String {
        val f = File(path)
        assertTrue("not found: ${f.absolutePath}", f.isFile)
        return f.readText()
    }

    @Test
    fun `a surface that is a GROUND paints the floor`() {
        // The Tome Library is full-screen and stands on this for its whole life. Suppressing the floor
        // lets the shelf bleed through -- two scrims at 0.52 transmit ~23% on their own.
        val text = read("src/main/kotlin/com/gruning/mimir/rewrite/HomeTomeLibraryOverlay.kt")
        assertTrue(
            "the Tome Library no longer mounts a backdrop — check whether this rule still applies",
            text.contains("VideoBackdrop("),
        )
        assertEquals(
            "the Tome Library is a ground and must not suppress the floor",
            0,
            Regex("""paintFloor""").findAll(text).count(),
        )
    }

    @Test
    fun `a surface that is a PREVIEW does not`() {
        // The discriminator is ground vs preview, NOT overlay vs destination -- the line I got wrong twice.
        // The tile menu's backdrop mounts only in HomeTileMenuPhase.ClipPick, as content inside a menu
        // that is already open. A floor there is a full-screen opaque wipe in and out mid-life, twice,
        // with no transition on either edge, while the player has gone nowhere.
        val text = read("src/main/kotlin/com/gruning/mimir/rewrite/HomeTileMenuOverlay.kt")
        assertTrue("the tile menu no longer mounts a backdrop", text.contains("VideoBackdrop("))
        assertTrue(
            "the preview backdrop must keep its opt-out",
            text.contains("paintFloor = false,"),
        )
        assertTrue(
            "and it must still be gated on the ClipPick phase, or it is no longer a preview",
            text.contains("HomeTileMenuPhase.ClipPick"),
        )
    }

    @Test
    fun `the floor is on by default, so a new caller has to argue its way out`() {
        val text = read(
            "../feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/VideoBackdrop.kt",
        )
        assertTrue(
            "the themed floor must still be painted at all — otherwise this test passes vacuously",
            text.contains("background(palette.signatureBottom)"),
        )
        assertTrue(
            "the opt-out must stay DEFAULTED ON, so a new caller gets the floor unless it argues otherwise",
            text.contains("paintFloor: Boolean = true,"),
        )
    }
}

package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for Shelves / Launch card previews that were too large to show the complete
 * design and lacked a SELECT preview action.
 */
class CoverLanePreviewTest {

    /**
     * A cover tile CONTAINS its art now. It used to crop at 0.72 with zero padding, so a box-3D render —
     * which C6 made the enrichment default, and which sits on a squarer canvas — lost its edges. Two
     * different crops of the same game's art are indistinguishable, which defeats the one job a chooser
     * has.
     */
    @Test
    fun `a cover tile contains its art rather than cropping it`() {
        val source = java.io.File("app/src/main/kotlin/com/gruning/mimir/rewrite/HomeCustomEnrichPicker.kt")
            .takeIf { it.isFile }
            ?: java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/HomeCustomEnrichPicker.kt")
        val text = source.readText()
        val decision = text.substringAfter("val squarePreview = state.kind == EnrichMediaKind.ICON")
            .substringBefore("var previewFailed")
        assertTrue(
            "COVER must join the contain list beside ICON and TOME_PHYSICAL; it reads:\n$decision",
            decision.contains("state.kind == EnrichMediaKind.COVER"),
        )
    }

    /**
     * The legend only offers SELECT when SELECT actually does something — it is derived from the same
     * predicate the dispatcher consults, so the two cannot disagree.
     */
    @Test
    fun `the legend offers the preview only when one can be opened`() {
        assertEquals("A USE · SELECT PREVIEW · B BACK", MimirCopy.HintMediaPreviewable)
        assertEquals("A USE · B BACK", MimirCopy.HintMedia)
        assertTrue(
            "the removable variant must keep X REMOVE as well as the preview",
            MimirCopy.HintMediaRemovablePreviewable.contains("X REMOVE") &&
                MimirCopy.HintMediaRemovablePreviewable.contains("SELECT PREVIEW"),
        )
        assertFalse(
            "and the plain legend must not promise a key that does nothing",
            MimirCopy.HintMedia.contains("SELECT"),
        )
    }

    /**
     * The half that would have shipped broken: `canExpand` saying yes is worthless if the surface it
     * opens has nothing to paint, the wrong shape, and the wrong name. A cover result carries no saved
     * asset and no prepared file, so without the previewPath tier SELECT opened an empty panel.
     */
    @Test
    fun `the expanded surface can actually show a cover`() {
        assertEquals("Cover art preview", homeCustomEnrichExpandedPreviewTitle(EnrichMediaKind.COVER))
        assertEquals("Hero art preview", homeCustomEnrichExpandedPreviewTitle(EnrichMediaKind.HERO))
        assertEquals(
            "a portrait cover must not be staged at 16:9 -- Crop through that is a horizontal slice",
            0.72f,
            homeCustomEnrichPreviewStageAspect(EnrichMediaKind.COVER),
            0.0001f,
        )
    }
}

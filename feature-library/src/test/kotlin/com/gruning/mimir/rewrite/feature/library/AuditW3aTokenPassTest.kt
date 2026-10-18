package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.ui.MimirMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit 2026-10-14, wave 3 (W3a): the shared motion-clock constraint (FocusMs/OverlayMs on MimirMotion.Easing) and the P3 draw pass, for this module's
 * files. Clocks are pinned by value; the "not read in composition" fixes by source, which is the proof the lane brief names for them (a recomposition-count test needs a harness these
 * surfaces do not have).
 */
class AuditW3aTokenPassTest {

    private fun source(name: String): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
    ).first(File::isFile).readText()

    @Test
    fun `the coverflow title acknowledges on the focus clock and the house curve`() {
        assertEquals("was 150", MimirMotion.FocusMs, CoverflowTitleDurationMs)
        assertEquals("the tile emphasis beside it", CoverflowMotionDurationMs, CoverflowTitleDurationMs)
        listOf("ShelfCoverflow.kt", "NativeShelfCoverflow.kt").forEach { file ->
            val text = source(file)
            assertTrue("$file: the title's tween rides MimirMotion.Easing", text.contains("tween(CoverflowTitleDurationMs, easing = MimirMotion.Easing)"))
            assertFalse("$file: and no longer the file's own copy of the curve", text.contains("tween(CoverflowTitleDurationMs, easing = CoverflowMotionEasing)"))
        }
    }

    @Test
    fun `the tome preview crossfade is the overlay clock`() {
        assertEquals("was 240", MimirMotion.OverlayMs, HomeTomePreviewFadeMs)
    }

    @Test
    fun `the tome folio's dead spring knob is gone, the pan being a tween on the overlay clock`() {
        assertFalse(source("HomeTomeFolioVector.kt").contains("HomeTomeFolioGlideStiffness"))
        assertFalse(source("HomeTomeFolioVector.kt").contains("SettleThresholdSeats"))
        assertTrue(source("HomeTomeFolio.kt").contains("animationSpec = tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)"))
    }

    @Test
    fun `a coverflow tile reads the window-edge fade in the layer, and composition sees only the discrete gate`() {
        listOf("ShelfCoverflow.kt", "NativeShelfCoverflow.kt").forEach { file ->
            val text = source(file)
            assertFalse("$file: no composition-phase copy of the animated factor", text.contains("val edgeAlpha = viewportAlpha"))
            assertTrue("$file: the gate is derived", text.contains("val tileInteractive by remember { derivedStateOf { viewportAlpha > 0.01f } }"))
            assertTrue("$file: and it is what the tile is told", text.contains("interactive = tileInteractive || isCentered,"))
        }
        val native = source("NativeShelfCoverflow.kt")
        assertTrue("the painted alpha is read in the layer", native.contains("alpha = viewportAlpha\n"))
        assertTrue("and the semantic in its own lambda", native.contains("coverflowEdgeAlpha = viewportAlpha"))
    }
}

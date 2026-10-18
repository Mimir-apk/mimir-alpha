package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.ui.select.MimirSelectOverlay
import com.gruning.mimir.rewrite.core.ui.select.MimirSelectOptionsTestTag

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The select overlay's option rows wear the one ring, which paints MimirFocusTileRingReach past the row. The options
 * Column is a vertical scroll host, and a scroll host clips exactly at its top and bottom edges, so the ring's reach
 * must sit inside the scroll content and in the bring-into-view rect. Source pins, the module's idiom: whether a ring
 * clips is device-only.
 */
class SelectOverlayRingClearanceTest {

    @Test
    fun `the options scroll host holds the ring's reach inside and reveals it`() {
        val overlay = source("MimirSelectOverlay.kt")
        val options = overlay.substringAfter(".testTag(MimirSelectOptionsTestTag)").substringBefore("MimirControlHintText(")
        val scroll = options.indexOf(".verticalScroll(rememberScrollState())")
        val pad = options.indexOf(".padding(vertical = MimirFocusTileRingReach)")
        assertTrue("the options Column scrolls", scroll >= 0)
        assertTrue("the ring's reach is padding INSIDE the scroll host", pad > scroll)
        assertTrue(
            "ringed siblings 8 dp apart (spec 3.3)",
            options.contains("verticalArrangement = Arrangement.spacedBy(if (centeredPlainRows) MimirSpacing.xs else MimirSpacing.sm),"),
        )
        assertFalse("the old 4 dp gap is gone", options.contains("else 4.dp)"))
        assertTrue(
            "a row scrolled in is revealed with its ring",
            options.contains("revealOutset = MimirFocusTileRingReach,"),
        )
        assertFalse(
            "no picker opts out of the ring's reach",
            options.contains("if (centeredPlainRows) 0.dp"),
        )
    }

    @Test
    fun `the centred-plain row of the Accent picker wears the one ring and no private glow`() {
        val overlay = source("MimirSelectOverlay.kt")
        val row = overlay.substringAfter("private fun SelectRow(")
        val plain = row.substringAfter("if (centeredPlain) {").substringBefore("val shape = RoundedCornerShape(MimirSpacing.radiusCard)")
        assertTrue("the centred-plain branch is where the anchor says", plain.contains("heightIn(min = MimirSelectRowMinHeight)"))
        assertTrue("the centred-plain row wears the shared ring", plain.contains(".mimirSelectionRing("))
        assertTrue(
            "on the same row shape as the ringed branch",
            plain.contains("shape = MimirRingShape.Row(MimirSpacing.radiusCard),"),
        )
        assertTrue("ring before the tap target", plain.indexOf(".mimirSelectionRing(") < plain.indexOf(".clickable("))
        assertFalse("the private radial glow is gone", plain.contains("radialGradient"))
        assertFalse("no second focus colour", plain.contains("accent.copy(alpha"))
        assertFalse("no focus scale", plain.contains("scale("))
        assertFalse("FocusAuthority only", plain.contains(".focusable(") || plain.contains("focusTarget("))
        val picker = source("AccentPicker.kt")
        assertTrue("the Accent picker is a centred-plain caller", picker.contains("centeredPlainRows = true,"))
    }

    @Test
    fun `the ringed row still wears the one ring before its clip`() {
        val overlay = source("MimirSelectOverlay.kt")
        val row = overlay.substringAfter("private fun SelectRow(")
        val ringed = row.substringAfter("val shape = RoundedCornerShape(MimirSpacing.radiusCard)")
        assertTrue(ringed.contains(".mimirSelectionRing("))
        assertTrue("ring before clip", ringed.indexOf(".mimirSelectionRing(") < ringed.indexOf(".clip(shape)"))
    }

    private fun source(name: String): String = listOf(
        File("../core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/select/$name"),
        File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/select/$name"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/$name"),
        File("feature-settings/src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/$name"),
    ).first(File::isFile).readText()
}

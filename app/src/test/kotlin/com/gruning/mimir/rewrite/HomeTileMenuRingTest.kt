package com.gruning.mimir.rewrite

import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The game menu's focus after the ring migration : the one selection ring on a capsule around
 * the focused action's label, the radial glow retired from this menu, and every full-width row and strip inside the
 * glass panel pulled in by what the panel's padding lacks of the ring's reach. Source pins: no Compose harness runs
 * here, and whether a ring clips on a device is device-only.
 */
class HomeTileMenuRingTest {

    @Test
    fun `a row inside the glass panel pulls in by what the panel's padding lacks of the ring's reach`() {
        assertEquals(MimirFocusTileRingReach - MimirSpacing.md, homeTileMenuRowRingInset(MimirSpacing.md))
        assertEquals(MimirFocusTileRingReach - MimirSpacing.sm, homeTileMenuRowRingInset(MimirSpacing.sm))
        assertEquals(MimirFocusTileRingReach - MimirSpacing.xs, homeTileMenuRowRingInset(MimirSpacing.xs))
        assertEquals(0.dp, homeTileMenuRowRingInset(MimirFocusTileRingReach))
        assertEquals(0.dp, homeTileMenuRowRingInset(MimirSpacing.xl))
    }

    @Test
    fun `the menu's rows ring on a label capsule, and the glow is gone from this menu`() {
        val source = overlaySource()
        val menu = source.substringAfter("internal fun TileArtMenuOverlay(").substringBefore("private fun glassTextInset(")
        assertFalse("the menu must not draw a radial focus glow", menu.contains(".plainMenuFocus("))
        assertTrue(menu.contains("GlassMenuCapsule(focused = focused, identity = label)"))
        assertTrue(menu.contains("GlassMenuCapsule(focused = focused, identity = entry.name)"))
        // The capsule moved to core-ui (V2, so the Bottom can row a menu the same way); the tile menu imports it.
        assertTrue(source.contains("import com.gruning.mimir.rewrite.core.ui.GlassMenuCapsule"))
        assertFalse(source.contains("fun GlassMenuCapsule("))
        val parts = glassMenuPartsSource()
        assertTrue("the slice below must find the capsule, or its assertions prove nothing", parts.contains("fun GlassMenuCapsule("))
        val capsule = parts.substringAfter("fun GlassMenuCapsule(")
        assertTrue(capsule.contains("shape = MimirRingShape.Pill"))
        assertTrue("the capsule clips after its ring", capsule.indexOf(".mimirSelectionRing(") < capsule.indexOf(".clip("))
    }

    @Test
    fun `the strips key their rings on the choice, never the index, and clear their own scroll axis`() {
        val source = overlaySource()
        val strip = source.substringAfter("private fun GlassPickStrip(").substringBefore("private fun GlassPreviewTile(")
        assertTrue(strip.contains("identity = identityOf(index)"))
        assertFalse(strip.contains("identity = index"))
        assertTrue(strip.contains(".padding(horizontal = MimirFocusTileRingReach, vertical = clearance)"))
        assertTrue(strip.contains("shape = MimirRingShape.Card(GlassPickTileCorner)"))
        assertTrue(strip.contains(".clip(RoundedCornerShape(GlassPickTileCorner))"))
        assertEquals(3, Regex("""identityOf = \{ phase\.choices\[it\] \}""").findAll(source).count())
    }

    @Test
    fun `the footer is a row ring pulled in from the clip, and the confirm answers with two pill rings on etched chips`() {
        val source = overlaySource()
        val footer = source.substringAfter("private fun GlassUploadFooter(").substringBefore("private const val HomeTileMenuUploadFooterRingIdentity")
        assertTrue(footer.contains(".padding(ringInset)"))
        assertTrue(footer.indexOf(".padding(ringInset)") < footer.indexOf(".mimirSelectionRing("))
        assertTrue(footer.indexOf(".mimirSelectionRing(") < footer.indexOf(".clip(shape)"))
        assertTrue(footer.contains("outlineColor = palette.glassRestingOutline()"))
        // The confirm (the 10-09 sweep): the question and two etched chips, the lit one ringed once on its plate (Pill, no row ring, no inset).
        val chip = source.substringAfter("private fun HomeTileMenuConfirmChip(").substringBefore("/** The confirm-remove row's ring identity")
        assertTrue(chip.contains("shape = MimirRingShape.Pill"))
        assertTrue(chip.contains("MimirEtchedChipPlate("))
        assertFalse(source.substringAfter("is HomeTileMenuPhase.ConfirmRemove -> {").substringBefore("is HomeTileMenuPhase.CollectionPick -> {").contains(".mimirSelectionRing("))
        assertFalse(source.contains(".mimirGlassRow("))
    }

    private fun glassMenuPartsSource(): String = listOf(
        File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirGlassMenuParts.kt"),
        File("../core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirGlassMenuParts.kt"),
    ).first(File::isFile).readText()

    private fun overlaySource(): String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/HomeTileMenuOverlay.kt"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/HomeTileMenuOverlay.kt"),
    ).first(File::isFile).readText()
}

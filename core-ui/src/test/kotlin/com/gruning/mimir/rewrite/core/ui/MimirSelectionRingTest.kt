package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one selection ring (FOCUS-RING-SPEC, 2026-09-28). No Compose harness runs here: these pin the pure laws the
 * modifier draws from -- the gap rule, the corner rule, the wash, the plate, the outline, the dense reach and the
 * reach tokens layout reserves. Whether a ring clips on a device is device-only.
 */
class MimirSelectionRingTest {

    @Test
    fun `a filled pill, row or card stands off its ring, gold on gold (run-10)`() {
        assertEquals(MimirFilledControlRingGap, mimirSelectionRingGap(MimirRingShape.Pill, filled = true))
        assertEquals(0.dp, mimirSelectionRingGap(MimirRingShape.Pill, filled = false))
        // Utility's Allow, the empty shelf's Open Library and the file queue's lead action are filled rows.
        assertEquals(MimirFilledControlRingGap, mimirSelectionRingGap(MimirRingShape.Row(MimirSpacing.radiusCard), filled = true))
        assertEquals(0.dp, mimirSelectionRingGap(MimirRingShape.Row(MimirSpacing.radiusCard), filled = false))
        assertEquals(MimirFilledControlRingGap, mimirSelectionRingGap(MimirRingShape.Card(MimirBoxArtCorner), filled = true))
        assertEquals(0.dp, mimirSelectionRingGap(MimirRingShape.Card(MimirBoxArtCorner), filled = false))
        assertEquals(0.dp, mimirSelectionRingGap(MimirRingShape.Key(4.dp), filled = true))
        assertEquals(0.dp, mimirSelectionRingGap(MimirRingShape.Circle, filled = true))
    }

    @Test
    fun `a filled row's bright inner stroke starts the gap off its edge, inside the filled reach`() {
        val density = Density(1f)
        val gap = mimirSelectionRingGap(MimirRingShape.Row(14.dp), filled = true)
        val paint = with(density) { mimirFocusTileRingPaint(Color.Red, 14.dp, Size(100f, 48f), gap = gap) }
        // Innermost stroke: 2 dp + gap outset, 3 dp wide, so its inner edge sits 0.5 dp + the 3 dp gap off the fill.
        assertEquals(3.5f, paint.outsets[2] - paint.strokes[2].width / 2f, 1e-4f)
        val reach = paint.outsets[0] + paint.strokes[0].width / 2f
        assertEquals(16.5f, reach, 1e-4f)
        assertTrue(reach <= with(density) { MimirFilledControlRingReach.toPx() })
    }

    @Test
    fun `the ring takes the element's own corner, and a pill or a circle measures its own`() {
        with(Density(2f)) {
            assertEquals(28f, mimirSelectionRingCornerPx(MimirRingShape.Row(14.dp), Size(200f, 48f)), 1e-4f)
            assertEquals(20f, mimirSelectionRingCornerPx(MimirRingShape.Card(MimirBoxArtCorner), Size(200f, 48f)), 1e-4f)
            assertEquals(8f, mimirSelectionRingCornerPx(MimirRingShape.Key(4.dp), Size(40f, 40f)), 1e-4f)
            assertEquals(24f, mimirSelectionRingCornerPx(MimirRingShape.Pill, Size(200f, 48f)), 1e-4f)
            assertEquals(26f, mimirSelectionRingCornerPx(MimirRingShape.Circle, Size(52f, 60f)), 1e-4f)
        }
    }

    @Test
    fun `the wash is the ghost pill's law, on unfilled pills and rows only`() {
        assertEquals(0f, mimirSelectionWashAlpha(washed = true, progress = 0f, flare = 0f, plate = 0f), 1e-6f)
        assertEquals(MimirFocusWashAlpha, mimirSelectionWashAlpha(washed = true, progress = 1f, flare = 0f, plate = 0f), 1e-6f)
        assertEquals(MimirFocusWashAlpha * 2f, mimirSelectionWashAlpha(washed = true, progress = 0f, flare = 1f, plate = 0f), 1e-6f)
        assertEquals(0f, mimirSelectionWashAlpha(washed = false, progress = 1f, flare = 1f, plate = 0f), 1e-6f)
        assertTrue(mimirSelectionRingWashed(MimirRingShape.Pill, filled = false))
        assertTrue(mimirSelectionRingWashed(MimirRingShape.Row(14.dp), filled = false))
        assertFalse("gold on gold takes no wash", mimirSelectionRingWashed(MimirRingShape.Pill, filled = true))
        assertFalse(mimirSelectionRingWashed(MimirRingShape.Card(10.dp), filled = false))
        assertFalse(mimirSelectionRingWashed(MimirRingShape.Key(4.dp), filled = false))
        assertFalse(mimirSelectionRingWashed(MimirRingShape.Circle, filled = false))
    }

    @Test
    fun `active is a plate that yields to the ring, never a second ring`() {
        assertEquals(MimirSelectionPlateFillAlpha, mimirSelectionWashAlpha(washed = false, progress = 0f, flare = 0f, plate = 1f), 1e-6f)
        // Focused AND active: the wash wins over the plate's fill, so the two never stack to 0.30 of accent.
        assertEquals(MimirFocusWashAlpha, mimirSelectionWashAlpha(washed = true, progress = 1f, flare = 0f, plate = 1f), 1e-6f)
        assertEquals(MimirSelectionPlateStrokeAlpha, mimirSelectionPlateStrokeAlpha(plate = 1f, progress = 0f), 1e-6f)
        assertEquals(0f, mimirSelectionPlateStrokeAlpha(plate = 1f, progress = 1f), 1e-6f)
        assertEquals(0f, mimirSelectionPlateStrokeAlpha(plate = 0f, progress = 0f), 1e-6f)
        assertEquals(0.14f, MimirSelectionPlateFillAlpha, 1e-6f)
        assertEquals(0.55f, MimirSelectionPlateStrokeAlpha, 1e-6f)
    }

    @Test
    fun `the resting outline fades out in step with the ring`() {
        assertEquals(1f, mimirSelectionOutlineAlpha(restingOutline = true, progress = 0f), 1e-6f)
        assertEquals(0.25f, mimirSelectionOutlineAlpha(restingOutline = true, progress = 0.75f), 1e-6f)
        assertEquals(0f, mimirSelectionOutlineAlpha(restingOutline = true, progress = 1f), 1e-6f)
        assertEquals(0f, mimirSelectionOutlineAlpha(restingOutline = false, progress = 0f), 1e-6f)
    }

    @Test
    fun `a dense key ring is the bright stroke alone, inside its four dp reach`() {
        val density = Density(1f)
        val dense = with(density) { mimirFocusTileRingPaint(Color.Red, 4.dp, Size(40f, 40f), dense = true) }
        assertTrue(dense.dense)
        val reach = dense.outsets[MimirFocusRingDenseStroke] + dense.strokes[MimirFocusRingDenseStroke].width / 2f
        assertEquals(3.5f, reach, 1e-4f)
        assertTrue(reach <= with(density) { MimirDenseKeyRingReach.toPx() })
        assertFalse(with(density) { mimirFocusTileRingPaint(Color.Red, 4.dp, Size(40f, 40f)) }.dense)
    }

    @Test
    fun `the plain ring's reach is the token layout reserves`() {
        val density = Density(1f)
        val paint = with(density) { mimirFocusTileRingPaint(Color.Red, 14.dp, Size(100f, 48f)) }
        val reach = paint.outsets[0] + paint.strokes[0].width / 2f
        assertEquals(13.5f, reach, 1e-4f)
        assertTrue(reach <= with(density) { MimirFocusTileRingReach.toPx() })
        assertEquals(14.dp, MimirFocusTileRingReach)
        assertEquals(4.dp, MimirDenseKeyRingReach)
        // The gapped reach the filled pills already reserve is the plain one plus the gap.
        assertEquals(MimirFocusTileRingReach + MimirFilledControlRingGap, MimirFilledControlRingReach)
    }

    @Test
    fun `pills and rows fix their gleam's reach, art keeps the tile rule`() {
        assertEquals(MimirSpacing.xl, mimirSelectionGlintReach(MimirRingShape.Pill))
        assertEquals(MimirSpacing.xl, mimirSelectionGlintReach(MimirRingShape.Row(14.dp)))
        assertFalse(mimirSelectionGlintReach(MimirRingShape.Card(10.dp)).isSpecified)
        assertFalse(mimirSelectionGlintReach(MimirRingShape.Circle).isSpecified)
    }

    @Test
    fun `the rest dim is the one every peer list carried`() {
        assertEquals(0.88f, MimirFocusRestingAlpha, 1e-6f)
    }
}

package com.gruning.mimir.rewrite.feature.companion

import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lattice cell's solve (policy R1, build A1): a two-line name band under a square cursor ring, in the
 * 3 x 3 page's cell (about 151 x 111 dp on the Thor, rendered: CompanionLatticeHeaderRenderTest; it was 151 x 116 until the lattice header of V3 took 15 dp) and the open book's (104 x 122 dp), at the
 * default and the certified 1.3 font scale. The four points are the ones the build brief names.
 *
 * The solver is pure, so the ring / label / bottom-clear promises are checked as arithmetic, not by a render.
 */
class CompanionLatticeCellTest {

    private data class Point(val width: Float, val height: Float, val fontScale: Float)

    private val points = listOf(
        Point(151f, 111f, 1.0f),
        Point(151f, 111f, 1.3f),
        Point(104f, 122f, 1.0f),
        Point(104f, 122f, 1.3f),
        // Twice the font scale: the face shrinks to 25 and 36 dp, but the band and the clear are still paid for.
        Point(151f, 111f, 2.0f),
        Point(104f, 122f, 2.0f),
    )

    private val gap = MimirSpacing.xs.value
    private val clear = MimirSpacing.xs.value

    /** The rail's corner brackets sit this far in from the rail edge (10 dp bracket inset). */
    private val bracketLineDp = 10f

    @Test
    fun `the ring never crosses the label and the label never leaves the cell`() {
        points.forEach { p ->
            val cell = solveCompanionLatticeCell(p.width, p.height, p.fontScale)
            val at = "at ${p.width} x ${p.height} fontScale ${p.fontScale}: $cell"
            assertTrue("ring bottom + gap must reach the label top, $at", cell.ringEdgeDp + gap <= cell.labelTopDp + EPS)
            assertTrue("label bottom + clear must fit the cell, $at", cell.labelTopDp + cell.bandDp + clear <= p.height + EPS)
            assertTrue("the ring must fit the cell width, $at", cell.ringEdgeDp <= p.width + EPS)
            assertTrue("the face must have an area, $at", cell.faceEdgeDp > 0f)
            assertEquals(
                "the face is the ring less the one ring's 8 dp sibling clearance, $at",
                cell.ringEdgeDp - CompanionCursorRingClearanceDp,
                cell.faceEdgeDp,
                EPS,
            )
            assertTrue("the label keeps off the rail's corner brackets, $at", cell.labelInsetDp > 0f)
        }
    }


    @Test
    fun `the one ring's 8 dp rule holds against the name below and the row above`() {
        assertEquals("the clearance is Main's sibling clearance, MimirSpacing.sm", MimirSpacing.sm.value, CompanionCursorRingClearanceDp, EPS)
        points.forEach { p ->
            val cell = solveCompanionLatticeCell(p.width, p.height, p.fontScale)
            val at = "at ${p.width} x ${p.height} fontScale ${p.fontScale}: $cell"
            val inset = (cell.ringEdgeDp - cell.faceEdgeDp) / 2f
            assertEquals("the face stands 4 dp inside its square on every side, $at", CompanionCursorRingClearanceDp / 2f, inset, EPS)
            // Face bottom to the first name line: the square's inset plus the gap.
            val faceBottom = inset + cell.faceEdgeDp
            assertTrue("the name starts at least 8 dp under the face, $at", cell.labelTopDp - faceBottom >= CompanionCursorRingClearanceDp - EPS)
            // Name band bottom to the next row's face top: the clear plus that row's inset.
            val nextFaceTop = p.height + inset
            assertTrue(
                "the next row's face is at least 8 dp under this band, $at",
                nextFaceTop - (cell.labelTopDp + cell.bandDp) >= CompanionCursorRingClearanceDp - EPS,
            )
        }
    }

    @Test
    fun `the merge hotspot is the face, 4 dp inside the square the ring is solved from`() {
        val cell = solveCompanionLatticeCell(151f, 111f, 1.0f)
        val slot = DragRect(0f, 0f, 151f * 2f, 111f * 2f)
        val rect = cell.faceRectIn(slot, density = 2f)
        assertEquals((151f - cell.ringEdgeDp) / 2f * 2f + 4f * 2f, rect.left, EPS)
        assertEquals(4f * 2f, rect.top, EPS)
        assertEquals(cell.faceEdgeDp * 2f, rect.width, EPS)
        assertEquals(cell.faceEdgeDp * 2f, rect.height, EPS)
    }
    @Test
    fun `the last row's name sits clear inside the rail's brackets`() {
        points.forEach { p ->
            val cell = solveCompanionLatticeCell(p.width, p.height, p.fontScale)
            // The last row's cell bottom is the rail's bottom less its inset; the label bottom is cell bottom - clear.
            val labelBottomFromRailEdge = CompanionRailInsetDp + (p.height - (cell.labelTopDp + cell.bandDp))
            assertTrue(
                "label bottom must sit at least 4 dp inside the 10 dp bracket line (${labelBottomFromRailEdge - bracketLineDp} dp), " +
                    "cell ${p.width} x ${p.height} fontScale ${p.fontScale}",
                labelBottomFromRailEdge - bracketLineDp >= 4f - EPS,
            )
        }
    }

    @Test
    fun `the solve at the four briefed points`() {
        // (width, height, fontScale) -> band, ring, face. Band = 2 x 17 x fontScale + 2; ring = min(width, height - 4 - band - 4).
        // The face is the ring less 8 dp (Main's one ring costs 8 dp of sibling clearance, where the 1.4 dp ring cost 4).
        assertCell(151f, 111f, 1.0f, band = 36f, ring = 67f, face = 59f)
        assertCell(151f, 111f, 1.3f, band = 46.2f, ring = 56.8f, face = 48.8f)
        assertCell(104f, 122f, 1.0f, band = 36f, ring = 78f, face = 70f)
        assertCell(104f, 122f, 1.3f, band = 46.2f, ring = 67.8f, face = 59.8f)
    }

    @Test
    fun `a wide short cell is limited by its height and a narrow tall one by its width`() {
        val wide = solveCompanionLatticeCell(400f, 116f, 1.0f)
        assertEquals("height-limited", 116f - gap - wide.bandDp - clear, wide.ringEdgeDp, EPS)
        val narrow = solveCompanionLatticeCell(60f, 300f, 1.0f)
        assertEquals("width-limited", 60f, narrow.ringEdgeDp, EPS)
        assertEquals("the label still sits under the ring", 60f + gap, narrow.labelTopDp, EPS)
    }

    @Test
    fun `a larger font scale only ever shrinks the face`() {
        val scales = listOf(1.0f, 1.15f, 1.3f, 1.5f)
        val faces = scales.map { solveCompanionLatticeCell(151f, 111f, it).faceEdgeDp }
        assertTrue("faces $faces must not grow with the font scale", faces.zipWithNext().all { (a, b) -> b <= a + EPS })
    }

    @Test
    fun `a degenerate cell solves to an empty face rather than a crash`() {
        listOf(
            Triple(0f, 0f, 1.0f),
            Triple(-5f, 116f, 1.0f),
            Triple(151f, 20f, 1.3f),
            Triple(Float.NaN, Float.NaN, Float.NaN),
            Triple(151f, 116f, 0f),
        ).forEach { (w, h, f) ->
            val cell = solveCompanionLatticeCell(w, h, f)
            assertTrue("no negative edge for $w x $h at $f: $cell", cell.ringEdgeDp >= 0f && cell.faceEdgeDp >= 0f)
            assertTrue("finite for $w x $h at $f: $cell", cell.labelTopDp.isFinite() && cell.bandDp.isFinite())
            // The label never leaves the cell, however small it is: its top is inside, and its band ends inside.
            val height = if (h.isFinite()) h.coerceAtLeast(0f) else 0f
            assertTrue("label top inside the cell for $w x $h at $f: $cell", cell.labelTopDp in 0f..(height + EPS))
            assertTrue("label bottom inside the cell for $w x $h at $f: $cell", cell.labelTopDp + cell.bandDp <= height + EPS)
            assertTrue("a line count the band can hold for $w x $h at $f: $cell", cell.labelLines in 0..CompanionLatticeLabelLines)
            assertEquals("no lines means no band for $w x $h at $f: $cell", cell.labelLines == 0, cell.bandDp == 0f)
        }
        assertEquals(0f, solveCompanionLatticeCell(151f, 20f, 1.3f).faceEdgeDp, EPS)
    }

    @Test
    fun `a cell too short for the band hides the name rather than overrun the cell`() {
        // The finding: 151 x 20 at fontScale 1.3 used to return label bottom 50.2 in a 20 dp cell.
        val tiny = solveCompanionLatticeCell(151f, 20f, 1.3f)
        assertEquals(0f, tiny.ringEdgeDp, EPS)
        assertEquals(0, tiny.labelLines)
        assertEquals(0f, tiny.bandDp, EPS)
        assertTrue("label bottom ${tiny.labelTopDp + tiny.bandDp} inside the cell", tiny.labelTopDp + tiny.bandDp <= 20f + EPS)
    }

    @Test
    fun `a cell with room for one line gets a one-line band and no ring`() {
        // 151 x 40 at 1.0: room under the gap and above the clear is 32 dp; one line + slack is 19, two is 36.
        val short = solveCompanionLatticeCell(151f, 40f, 1.0f)
        assertEquals(0f, short.ringEdgeDp, EPS)
        assertEquals(1, short.labelLines)
        assertEquals(CompanionLatticeLabelLineHeightSp + 2f, short.bandDp, EPS)
        assertTrue(
            "label bottom + clear ${short.labelTopDp + short.bandDp + clear} inside the cell",
            short.labelTopDp + short.bandDp + clear <= 40f + EPS,
        )
    }

    @Test
    fun `every cell that can pay for the whole band keeps both lines`() {
        points.forEach { p ->
            assertEquals(
                "two lines at ${p.width} x ${p.height} fontScale ${p.fontScale}",
                CompanionLatticeLabelLines,
                solveCompanionLatticeCell(p.width, p.height, p.fontScale).labelLines,
            )
        }
        // The boundary: exactly gap + band + clear, no ring left over, is still two lines.
        val boundary = solveCompanionLatticeCell(151f, gap + 36f + clear, 1.0f)
        assertEquals(CompanionLatticeLabelLines, boundary.labelLines)
        assertEquals(36f, boundary.bandDp, EPS)
    }

    private fun assertCell(width: Float, height: Float, fontScale: Float, band: Float, ring: Float, face: Float) {
        val cell = solveCompanionLatticeCell(width, height, fontScale)
        val at = "$width x $height fontScale $fontScale"
        assertEquals("band at $at", band, cell.bandDp, EPS)
        assertEquals("ring at $at", ring, cell.ringEdgeDp, EPS)
        assertEquals("face at $at", face, cell.faceEdgeDp, EPS)
        assertEquals("label top at $at", ring + gap, cell.labelTopDp, EPS)
    }

    private companion object {
        const val EPS = 0.01f
    }
}

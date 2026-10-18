package com.gruning.mimir.rewrite.feature.companion

import android.app.Application
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.CompanionHomeLane
import com.gruning.mimir.rewrite.core.model.CompanionHomeTile
import com.gruning.mimir.rewrite.core.model.CompanionHomeTileKind
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * V6 and B6 of the Thor Bottom revamp, on the REAL [CompanionSurface] at the Thor Bottom's size (1240 x 1080 px at 369 dpi, 537.7 x 468.3 dp: the Presentation draws the full 1080 px, ledger D18/D19), at font
 * scales 1.0 and the certified 1.3, with the Activity swipe hint shown and retired: what the footer costs, what the lattice cell and its face are, and that
 * every name fits its band. Every number the report quotes is printed with the tag `V6-FOOTPRINT`.
 *
 * Geometry only, from the semantics tree: the real surface cannot be rasterised under Robolectric (its scene transition draws explicit graphics layers,
 * which the software canvas refuses, and `captureToImage` is never delivered a redraw by the paused looper). The pixels of the ring are
 * [CompanionCursorRingRenderTest], on the real tile and the real Tome leaves, in the cell sizes this class pins from the real surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionLatticeFootprintRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val widthDp = 1240f / Density369
    private val heightDp = 1080f / Density369

    private val names = listOf(
        "Nintendo Entertainment System",
        "Super Nintendo Entertainment System",
        "Game Boy Advance",
        "Sega Genesis",
        "PlayStation Portable",
        "Nintendo 64",
        "Dreamcast",
        "Neo Geo Pocket Color",
        "Atari 2600",
        "TurboGrafx-16",
        "Famicom Disk System",
    )
    private val tiles = names.mapIndexed { i, name -> CompanionHomeTile("p${i + 1}", CompanionHomeTileKind.PLATFORM, name) } +
        (12..19).map { CompanionHomeTile("p$it", CompanionHomeTileKind.PLATFORM, "Platform number $it") }
    private fun payloadOf(count: Int) = CompanionPayload(
        mode = CompanionMode.HOME,
        statsLabel = "19 platforms · 1,234 games",
        homeLanes = listOf(CompanionHomeLane(id = "emulation", title = "Emulation", hint = "", tiles = tiles.take(count))),
    )

    private var density = 0f

    private fun mount(fontScale: Float, hintShown: Boolean, swipeCallback: Boolean = true, cursor: String = "p12", tileCount: Int = 19) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(Density369, fontScale)) {
                MimirTheme {
                    density = LocalDensity.current.density
                    CompanionSurface(
                        payload = payloadOf(tileCount),
                        cursorId = cursor,
                        // The hint is drawn only where the host offers the swipe: a surface with no workspace callback never shows it.
                        onWorkspaceDrag = if (swipeCallback) ({ _: Float -> 0f }) else null,
                        showActivitySwipeHint = hintShown,
                        modifier = Modifier.requiredSize(widthDp.dp, heightDp.dp),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000L)
        compose.waitForIdle()
    }

    private fun boundsDp(tag: String): FloatArray {
        val b = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        return floatArrayOf(b.left / density, b.top / density, b.width / density, b.height / density)
    }

    /**
     * A node's own position and size, NOT clipped to its parent: `boundsInRoot` is intersected with every ancestor's layout bounds, and the page diamonds overhang the frame (so
     * the body) by half their height with the hint retired, which `boundsInRoot` would cut to 3.9 dp of a 7 dp row. Layout geometry only: whether anything clips the DRAWING is the
     * structure the source pin holds (the diamonds are a sibling of the clipping drag root, not inside it).
     */
    private fun rawBoundsDp(tag: String): FloatArray {
        val n = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        return floatArrayOf(n.positionInRoot.x / density, n.positionInRoot.y / density, n.size.width / density, n.size.height / density)
    }

    /** What one scenario measures. */
    private class Footprint(val footerDp: Float, val cellW: Float, val cellH: Float, val ring: Float, val face: Float, val band: Float, val hintNodes: Int)

    private fun assertScenario(fontScale: Float, hintShown: Boolean, swipeCallback: Boolean = true, cursor: String = "p12"): Footprint {
        mount(fontScale, hintShown, swipeCallback, cursor)
        // The footer row is the swipe hint's alone and exists only while the hint does (the diamonds moved onto the frame): retired, there is no row.
        val footers = compose.onAllNodes(androidx.compose.ui.test.hasTestTag(CompanionFooterTag), useUnmergedTree = true).fetchSemanticsNodes().size
        val footerDp = if (footers > 0) boundsDp(CompanionFooterTag)[3] else 0f
        assertEquals("the footer row exists exactly while the hint is not retired", if (hintShown) 1 else 0, footers)
        val runes = rawBoundsDp(CompanionPageRunesTag)
        // Slot 0, as the V3 pin reads it: the three rows share the pixel remainder, so row 0 differs from rows 1 and 2 by a pixel (118.37 against 118.80 dp at 1.0 with the hint shown, 125.31 against 124.88 retired).
        val slot = boundsDp(companionSlotTag(0))
        val solved = solveCompanionLatticeCell(slot[2], slot[3], fontScale)
        val hintNodes = compose.onAllNodes(hasText("Swipe right for Activity →") or hasContentDescription("Swipe right for Activity →"), useUnmergedTree = true).fetchSemanticsNodes().size
        // The frame: the pager's pages are inset by the rail's 12 dp inside it, so its bottom edge is the last row's bottom plus that inset and its right edge the last column's
        // right plus it. The page diamonds stand on its bottom line, centred on it (the 1 dp border's centre is half a dp up), and at the trailing end.
        val lastRow = boundsDp(companionSlotTag(6))
        val lastCol = boundsDp(companionSlotTag(2))
        val frameBottom = lastRow[1] + lastRow[3] + CompanionRailInsetDp
        val frameRight = lastCol[0] + lastCol[2] + CompanionRailInsetDp
        // The row is 7 dp (6.94 at 369 dpi), measured whole.
        val runesCentreY = runes[1] + runes[3] / 2f
        assertTrue("the diamonds row is measured whole, 7 dp: ${runes[3]}", runes[3] >= 6.5f)
        val runesRightGap = frameRight - (runes[0] + runes[2])
        // The overhang stays inside the scene: the scrim's bottom is under the frame by half its height, inside the shell's 10 dp clearance.
        val scrimBottom = runesCentreY + CompanionPageRunesScrimHeightDp / 2f
        assertTrue("the scrim ends inside the scene: $scrimBottom vs $heightDp", scrimBottom <= heightDp - 3f)
        println(
            "V6-FOOTPRINT lattice fontScale=$fontScale hint=${if (hintShown) "shown" else "retired"} hintNodes=$hintNodes footerRow=${footerDp}dp runes=${runes[2]}x${runes[3]}dp " +
                "runesCentreY=$runesCentreY frameBottom=$frameBottom runesRightGapToFrame=$runesRightGap scrimBottom=$scrimBottom scene=$heightDp cell=${slot[2]}x${slot[3]}dp ring=${solved.ringEdgeDp} " +
                "face=${solved.faceEdgeDp} band=${solved.bandDp} lines=${solved.labelLines}",
        )
        assertEquals("the hint is on the page exactly while it is not retired", if (hintShown) 1 else 0, hintNodes)
        // The diamonds are ON the frame line: their centre is the line's (half a dp inside the bottom edge), whatever the hint does.
        assertEquals("the diamonds are centred on the frame's bottom line", frameBottom - 0.5f, runesCentreY, 1.0f)
        // At the trailing end, clear of the bottom-right bracket (its arm ends 26 dp in) with the scrim's own padding to spare, and of the centred 96 dp dock handle.
        val scrimRight = runes[0] + runes[2] + CompanionPageRunesScrimPadDp
        assertTrue("the diamonds' scrim ends clear of the bottom-right bracket's arm: ${frameRight - scrimRight} dp from the frame", frameRight - scrimRight >= 26f + 2f)
        val surfaceMid = widthDp / 2f
        assertTrue("the diamonds clear the dock handle's 96 dp: left edge ${runes[0] - CompanionPageRunesScrimPadDp} vs handle right ${surfaceMid + 48f}", runes[0] - CompanionPageRunesScrimPadDp >= surfaceMid + 48f + 8f)
        assertEquals("the face is the ring less the one ring's 8 dp of clearance", solved.ringEdgeDp - 8f, solved.faceEdgeDp, 0.01f)

        // Every name on the page fits its band: at most the solver's lines, inside its cell, above the 4 dp clear; a long name ends in an ellipsis and never overflows.
        for (slotIndex in 0..8) {
            val label = tiles[slotIndex].label
            val node = compose.onNode(hasText(label), useUnmergedTree = true).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            val action = checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action) { "$label has no layout action" }
            check(action(layouts)) { "$label has no text layout yet" }
            val layout = layouts.single()
            val cellB = compose.onNodeWithTag(companionSlotTag(slotIndex), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val geometry = solveCompanionLatticeCell(cellB.width / density, cellB.height / density, fontScale)
            assertTrue("'$label' shows at most ${geometry.labelLines} lines, was ${layout.lineCount}", layout.lineCount <= geometry.labelLines)
            val b = node.boundsInRoot
            assertTrue("'$label' stays inside its cell: $b in $cellB", b.left >= cellB.left - 1f && b.right <= cellB.right + 1f && b.top >= cellB.top - 1f)
            assertTrue(
                "'$label' keeps the ${geometry.clearDp} dp clear under it: ${(cellB.bottom - b.bottom) / density}",
                (cellB.bottom - b.bottom) / density >= geometry.clearDp - 0.6f,
            )
            assertTrue("'$label' is no taller than its band: ${b.height / density} vs ${geometry.bandDp}", b.height / density <= geometry.bandDp + 0.6f)
            // The name starts at least 8 dp under the face (4 dp inside the ring square, plus the 4 dp gap): the ring's strong strokes clear it.
            val faceBottom = cellB.top / density + (solved.ringEdgeDp - solved.faceEdgeDp) / 2f + solved.faceEdgeDp
            assertTrue("'$label' stands 8 dp under the face: ${b.top / density - faceBottom}", b.top / density - faceBottom >= 8f - 1.2f)
        }
        return Footprint(footerDp, slot[2], slot[3], solved.ringEdgeDp, solved.faceEdgeDp, solved.bandDp, hintNodes)
    }

    private fun assertCell(expected: ThorBottomCell, actual: Footprint) {
        assertEquals("cell width", expected.width, actual.cellW, 0.05f)
        assertEquals("cell height (row 0)", expected.height, actual.cellH, 0.05f)
        assertEquals("ring square", expected.height - 4f - 4f - actual.band, actual.ring, 0.05f)
        assertEquals("face", expected.height - 4f - 4f - actual.band - 8f, actual.face, 0.05f)
    }

    /** The default font scale: the footer's 13 dp minimum is taller than the 9 sp hint, so the hint's row costs the grid 19 dp (6 dp spacer + 13 dp row) while it shows. */
    @Test
    fun `lattice at font scale 1_0 with the hint shown`() {
        val shown = assertScenario(1.0f, hintShown = true)
        assertEquals("the footer row is its 13 dp minimum with the hint shown", 13f, shown.footerDp, 0.1f)
        // On the real 468.3 dp Bottom with the hint shown: A1's cell is 151 x 118.37 dp, the ring square 74.37 dp and, since the one ring, the face 66.37 (111.00, 67.00 and 59.00 on the 444.4 dp host).
        assertCell(ThorBottomCells.Scale10, shown)
        assertEquals(66.373985f, shown.face, 0.01f)
    }

    /** The policy: once the first Activity swipe has retired the hint, the row and its spacer are gone, the diamonds stand on the frame, and the face is about 73 dp on the real 468.3 dp Bottom (65 on the 444.4 dp host). */
    @Test
    fun `lattice at font scale 1_0 with the hint retired`() {
        val retired = assertScenario(1.0f, hintShown = false)
        assertEquals("there is no footer row once the hint is retired", 0f, retired.footerDp, 0.01f)
        assertCell(ThorBottomCells.Scale10Retired, retired)
        assertEquals("the whole 19 dp footer went back to the grid: the face is 73.31 dp (66.37 with the hint)", 73.31165f, retired.face, 0.01f)
        assertTrue("about 73 dp", abs(retired.face - 73.3f) <= 0.5f)
    }

    /** The certified font scale: the 9 sp hint is 1.3 times taller and out-grows the 13 dp minimum on page 0. */
    @Test
    fun `lattice at font scale 1_3 with the hint shown`() {
        val shown = assertScenario(1.3f, hintShown = true)
        assertEquals("the hint is taller than the 13 dp minimum at 1.3, and it is the footer's height", 15.18f, shown.footerDp, 0.1f)
        assertCell(ThorBottomCells.Scale13Shown, shown)
    }

    @Test
    fun `lattice at font scale 1_3 with the hint retired`() {
        val retired = assertScenario(1.3f, hintShown = false)
        assertEquals("there is no footer row", 0f, retired.footerDp, 0.01f)
        assertCell(ThorBottomCells.Scale13Retired, retired)
        assertTrue(
            "the grid took the whole footer back (6 dp + 15.18 dp over three rows): the cell is taller than with the hint by more than 6 dp",
            retired.cellH > ThorBottomCells.Scale13Shown.height + 6f,
        )
    }

    /**
     * A lattice of one page (nine tiles or fewer) has no page runes, so the swipe hint is the footer's only content: with the hint shown the footer is the hint's row,
     * and once it is retired there is no footer at all and its 6 dp spacer and 13 dp row go back to the grid. This is the one place the hint's retirement frees height at the
     * default font scale; the Thor, with 19 platforms and three pages, does not show it.
     */
    private fun assertOnePage(hintShown: Boolean): FloatArray {
        mount(1.0f, hintShown, tileCount = 6)
        val footers = compose.onAllNodes(androidx.compose.ui.test.hasTestTag(CompanionFooterTag), useUnmergedTree = true).fetchSemanticsNodes().size
        val runes = compose.onAllNodes(androidx.compose.ui.test.hasTestTag(CompanionPageRunesTag), useUnmergedTree = true).fetchSemanticsNodes().size
        val slot = boundsDp(companionSlotTag(0))
        val solved = solveCompanionLatticeCell(slot[2], slot[3], 1.0f)
        println(
            "V6-FOOTPRINT onePage hint=${if (hintShown) "shown" else "retired"} footerNodes=$footers runeNodes=$runes cell=${slot[2]}x${slot[3]}dp " +
                "ring=${solved.ringEdgeDp} face=${solved.faceEdgeDp}",
        )
        assertEquals("no page runes on a one-page lattice", 0, runes)
        assertEquals("the footer exists only for the hint", if (hintShown) 1 else 0, footers)
        return slot
    }

    @Test
    fun `a one-page lattice with the hint shown keeps a footer for it`() {
        val slot = assertOnePage(hintShown = true)
        assertEquals("the hint's row is the footer: the page's cell is the 118.37 dp one", 118.373984f, slot[3], 0.5f)
    }

    @Test
    fun `a one-page lattice with the hint retired gives the whole footer back to the grid`() {
        val slot = assertOnePage(hintShown = false)
        // 6 dp spacer + 13 dp row = 19 dp over three rows: about 6.3 dp a cell, and 6.3 dp more face.
        assertTrue("the grid took the footer back: cell ${slot[3]} dp", slot[3] > 118.373984f + 5.5f)
    }

    private companion object {
        /** The Thor Bottom's density: 369 dpi / 160. */
        const val Density369 = 369f / 160f
    }
}

/** One row-0 lattice cell at the Thor Bottom's real size (537.7 x 468.3 dp, ledger D19), in dp, as the real surface solves it (the pixel test composes the real tile at exactly these sizes). */
internal class ThorBottomCell(val width: Float, val height: Float)

/** The row-0 cell of the real surface at the Thor Bottom size, by font scale and hint state. Pinned by [CompanionLatticeFootprintRenderTest]; read by the pixel test. */
internal object ThorBottomCells {
    val Scale10 = ThorBottomCell(150.8943f, 118.373984f)
    val Scale13Shown = ThorBottomCell(150.8943f, 117.940379f)
    val Scale10Retired = ThorBottomCell(150.8943f, 125.31165f)
    val Scale13Retired = ThorBottomCell(150.8943f, 125.31165f)
}

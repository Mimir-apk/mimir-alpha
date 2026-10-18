package com.gruning.mimir.rewrite.feature.companion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Create Tome cell at the Thor's 151 x 111 dp lattice cell (116 before the lattice header of V3), rendered with real text shaping (NATIVE graphics).
 *
 * Review finding (A1, P2): the "+" and the word "TOME" used to sit stacked inside the dashed face, which the cell
 * solve shrinks and clips as the font scale grows (34 dp high at 2.0), so the word could be squeezed out and the "+"
 * cut. Now the "+" stays in the face, sized to it, and the word is in the label band under it, as every other tile
 * labels. "Fully inside" is read two ways: each node's bounds sit inside the part it belongs to, and the text Compose
 * shaped for it fits that node (one line, no ellipsis, a natural height no taller than the node).
 *
 * The "+" is read through its own text semantics. The word is `MimirChromeText`, which clears its text semantics for a
 * spoken description, so its shaping is read by measuring the same string with the same style at the width the band
 * gives it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionCreateTomeTileLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `at the default font scale the plus is in the face and the word in the band`() = assertAffordanceFits(1.0f)

    @Test
    fun `at the certified font scale the plus is in the face and the word in the band`() = assertAffordanceFits(1.3f)

    @Test
    fun `at twice the font scale the plus is in the face and the word in the band`() = assertAffordanceFits(2.0f)

    private fun assertAffordanceFits(fontScale: Float) {
        val solved = solveCompanionLatticeCell(CellWidth, CellHeight, fontScale)
        var wordShaped: TextLayoutResult? = null
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MimirTheme {
                    val measurer = rememberTextMeasurer()
                    val density = LocalDensity.current
                    wordShaped = measurer.measure(
                        text = AnnotatedString("TOME"),
                        style = CompanionCreateTomeWordStyle,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = solved.labelLines.coerceAtLeast(1),
                        constraints = Constraints(maxWidth = ((CellWidth - 2f * solved.labelInsetDp) * density.density).toInt()),
                    )
                    Box(Modifier.size(CellWidth.dp, CellHeight.dp).testTag(CellTag)) {
                        CompanionCreateTomeTile(onCreate = {})
                    }
                }
            }
        }
        compose.waitForIdle()

        val at = "at fontScale $fontScale ($solved)"
        val cell = compose.onNodeWithTag(CellTag).getUnclippedBoundsInRoot()
        val face = compose.onNodeWithTag(CompanionCreateTomeFaceTag).getUnclippedBoundsInRoot()
        val plus = compose.onNodeWithTag(CompanionCreateTomePlusTag).getUnclippedBoundsInRoot()
        val word = compose.onNodeWithTag(CompanionCreateTomeWordTag, useUnmergedTree = true).getUnclippedBoundsInRoot()

        // The dashed seat is the face rect the solver names, inside the cell and above the band.
        assertEquals("face edge $at", solved.faceEdgeDp, face.width.value, 0.6f)
        assertEquals("face is square $at", face.width.value, face.height.value, 0.6f)
        assertInside("face inside the cell $at", face, cell)
        assertEquals("face sits half the ring's 8 dp clearance (4 dp) from the top $at", CompanionCursorRingClearanceDp / 2f, face.top.value - cell.top.value, 0.6f)

        // The "+" is in the face, and its text is drawn whole inside its own node.
        assertInside("plus inside the face $at", plus, face)
        assertPlusWhole("plus $at")

        // The word is in the label band: under the face, inside the cell and above the bottom clear, drawn whole.
        assertTrue("word below the face $at: $word vs $face", word.top.value >= face.bottom.value - 0.6f)
        assertInside("word inside the cell $at", word, cell)
        assertTrue(
            "word keeps the bottom clear $at: ${word.bottom.value} vs ${cell.bottom.value - solved.clearDp}",
            word.bottom.value <= cell.bottom.value - solved.clearDp + 0.6f,
        )
        assertEquals("word band height $at", solved.bandDp, word.height.value, 0.6f)
        val shaped = wordShaped!!
        assertEquals("word is one line $at", 1, shaped.lineCount)
        assertFalse("word is not ellipsized $at", shaped.isLineEllipsized(0))
        assertTrue(
            "word natural height ${shaped.multiParagraph.height} fits the band ${word.height.value} $at",
            shaped.multiParagraph.height <= word.height.value + 1f,
        )
        assertTrue(
            "word natural width ${shaped.multiParagraph.maxIntrinsicWidth} fits the band ${word.width.value - 2f * solved.labelInsetDp} $at",
            shaped.multiParagraph.maxIntrinsicWidth <= word.width.value - 2f * solved.labelInsetDp + 1f,
        )
    }

    private fun assertInside(message: String, inner: DpRect, outer: DpRect) {
        val slack = 0.6f
        assertTrue(
            "$message: $inner not inside $outer",
            inner.left.value >= outer.left.value - slack &&
                inner.top.value >= outer.top.value - slack &&
                inner.right.value <= outer.right.value + slack &&
                inner.bottom.value <= outer.bottom.value + slack,
        )
    }

    /** The "+" Compose laid out is whole: one line, nothing cut, and its natural size fits the node. */
    private fun assertPlusWhole(message: String) {
        val node = compose.onNodeWithTag(CompanionCreateTomePlusTag, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        assertTrue("$message: the node exposes its text layout", node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
        val layout = results.single()
        assertEquals("$message: line count", 1, layout.lineCount)
        // (`hasVisualOverflow` is not read: a one-line text in a loose box is laid out at the box width and shrinks
        // to its intrinsic width, which that flag reports as a width overflow though nothing is cut.)
        assertFalse("$message: height overflow", layout.didOverflowHeight)
        assertTrue(
            "$message: natural height ${layout.multiParagraph.height} fits the node ${node.size.height}",
            layout.multiParagraph.height <= node.size.height + 1f,
        )
        assertTrue(
            "$message: natural width ${layout.multiParagraph.maxIntrinsicWidth} fits the node ${node.size.width}",
            layout.multiParagraph.maxIntrinsicWidth <= node.size.width + 1f,
        )
    }

    private companion object {
        const val CellWidth = 151f
        const val CellHeight = 111f
        const val CellTag = "create-tome-test-cell"
    }
}

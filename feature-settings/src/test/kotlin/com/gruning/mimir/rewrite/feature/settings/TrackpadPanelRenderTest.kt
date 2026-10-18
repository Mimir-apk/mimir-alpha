package com.gruning.mimir.rewrite.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerColor
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerShape
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerSize
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerStyle
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
 * Settings > Controls > Trackpad with the pointer's three rows : Pointer size, Pointer shape and Pointer color
 * render under the speed row with the defaults (Small, Slim arrow, White), a press on each raises its own row id, and the value
 * on the row and the preview's description follow the state the press changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w640dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrackpadPanelRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val paneWidthDp = 560f
    private val activations = mutableListOf<String>()
    private var state by mutableStateOf(TrackpadSettingsState(serviceEnabled = true, naturalScroll = true, speed = 3))

    /** What the app's coordinator does for each row: cycle that one choice. */
    private fun pressed(id: String) {
        activations += id
        val pointer = state.pointer
        state = state.copy(
            pointer = when (id) {
                TrackpadPointerSizeRowId -> pointer.copy(size = pointer.size.next())
                TrackpadPointerShapeRowId -> pointer.copy(shape = pointer.shape.next())
                TrackpadPointerColorRowId -> pointer.copy(color = pointer.color.next())
                else -> pointer
            },
        )
    }

    private fun show() {
        compose.setContent {
            MimirTheme {
                Box(Modifier.width(paneWidthDp.dp).verticalScroll(rememberScrollState())) {
                    TrackpadPanel(authority = remember { DefaultFocusAuthority() }, onRowActivate = ::pressed, trackpad = state)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun named(text: String) = hasContentDescription(text) or hasText(text)

    private fun node(text: String): SemanticsNodeInteraction = compose.onAllNodes(named(text), useUnmergedTree = true)[0]

    private fun exists(text: String): Boolean = compose.onAllNodes(named(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun top(text: String): Float = node(text).getUnclippedBoundsInRoot().top.value

    private fun assertPreview(expected: String) {
        compose.onNodeWithTag(TrackpadPointerPreviewTestTag, useUnmergedTree = true).assertContentDescriptionEquals(expected)
    }

    @Test
    fun theThreePointerRowsRenderUnderTheSpeedRowWithTheDefaults() {
        show()
        listOf("Keyboard trackpad", "Natural scroll", "Pointer speed", "Pointer size", "Pointer shape", "Pointer color").forEach {
            assertTrue("$it is on the card", exists(it))
        }
        val tops = listOf("Pointer speed", "Pointer size", "Pointer shape", "Pointer color").map(::top)
        assertEquals("they read down the card in order", tops.sorted(), tops)
        assertEquals("on separate lines", tops.size, tops.toSet().size)
        // The values an install that never chose reads.
        listOf("Small", "Slim arrow", "White").forEach { assertTrue("$it is the default value", exists(it)) }
        assertFalse("and nothing else is chosen", exists("Medium") || exists("Large") || exists("Dot") || exists("Theme metal") || exists("Accent"))
        assertPreview("Pointer preview: Small, Slim arrow, White")
    }

    @Test
    fun everyRowStaysInsideThePane() {
        show()
        listOf("Pointer size", "Pointer shape", "Pointer color").forEach { title ->
            val bounds = node(title).getUnclippedBoundsInRoot()
            assertTrue("$title starts inside the pane", bounds.left.value >= -1.5f)
            assertTrue("$title ends inside the pane", bounds.right.value <= paneWidthDp + 1.5f)
        }
    }

    @Test
    fun aPressOnEachRowRaisesItsOwnIdAndTheValueAndThePreviewFollow() {
        show()
        node("Pointer size").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(TrackpadPointerSizeRowId, activations.last())
        assertTrue("Medium is now the size", exists("Medium"))
        assertPreview("Pointer preview: Medium, Slim arrow, White")

        node("Pointer shape").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(TrackpadPointerShapeRowId, activations.last())
        assertTrue("Dot is now the shape", exists("Dot"))
        assertFalse("and the slim arrow is no longer shown", exists("Slim arrow"))
        assertPreview("Pointer preview: Medium, Dot, White")

        node("Pointer color").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(TrackpadPointerColorRowId, activations.last())
        assertTrue("Theme metal is now the color", exists("Theme metal"))
        assertPreview("Pointer preview: Medium, Dot, Theme metal")

        node("Pointer color").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue("Accent follows Theme metal", exists("Accent"))
        assertEquals(
            listOf(TrackpadPointerSizeRowId, TrackpadPointerShapeRowId, TrackpadPointerColorRowId, TrackpadPointerColorRowId),
            activations.toList(),
        )
        assertEquals(
            TrackpadPointerStyle(TrackpadPointerSize.MEDIUM, TrackpadPointerShape.DOT, TrackpadPointerColor.ACCENT),
            state.pointer,
        )
    }

    @Test
    fun theOtherRowsKeepTheirIdsAndTheNewIdsAreDistinct() {
        val ids = listOf(
            TrackpadAccessRowId, TrackpadScrollRowId, TrackpadSpeedRowId,
            TrackpadPointerSizeRowId, TrackpadPointerShapeRowId, TrackpadPointerColorRowId,
        )
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("controls/trackpad/") })
        show()
        node("Pointer speed").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(TrackpadSpeedRowId, activations.last())
    }

    @Test
    fun aStateBuiltWithoutAPointerReadsTheDefault() {
        assertEquals(TrackpadPointerStyle.Default, TrackpadSettingsState(serviceEnabled = false, naturalScroll = true).pointer)
        assertEquals(TrackpadPointerStyle.Default, TrackpadSettingsState(serviceEnabled = false, naturalScroll = true, speed = 4).pointer)
    }
}

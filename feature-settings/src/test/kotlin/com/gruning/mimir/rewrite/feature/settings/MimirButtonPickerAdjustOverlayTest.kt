package com.gruning.mimir.rewrite.feature.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.center
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.MimirMarkPlacement
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Mimir-button picker's ADJUST mode on the real overlay and the real [DefaultFocusAuthority], in the shape production has: the host
 * pushes the scope, Y on a saved image opens the large coin and the chips, the pad nudges through [dispatchMimirButtonPicker], and touch
 * drags the coin, pinches it and taps the chips. The coin is the Compose twin ([MarkCoin]) here; `MimirButtonPickerAdjustStageTest` in `:app`
 * hosts the native view. The pixels are `MimirButtonAdjustRenderTest`'s.
 *
 * What it pins: focus stays on the image's tile for the whole of adjust mode (the tiles are hidden and silent, not unmounted) and is on it
 * again after B; the chips are tap-only and do what the pad keys do; a drag moves the image by the dp it travelled on the reference coin
 * and a pinch sizes it; nothing is saved until A.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w832dp-h468dp-xhdpi")
class MimirButtonPickerAdjustOverlayTest {

    @get:Rule
    val compose = createComposeRule()

    private val starPlacement = MimirMarkPlacement(scale = 1.2f, offsetX = 3f, offsetY = -1.5f)
    private val star = MimirButtonPickerMark("aaaaaaaa", "Your mark 1", "/none/aaaaaaaa.png", starPlacement)
    private val bolt = MimirButtonPickerMark("bbbbbbbb", "Your mark 2", "/none/bbbbbbbb.png")

    private val handed = mutableListOf<MimirButtonPickerTransition>()

    private inner class Host {
        val authority = DefaultFocusAuthority()
        var state by mutableStateOf(mimirButtonPickerInitialState(null, listOf(star, bolt)))

        fun content(fontScale: Float = 1f): @androidx.compose.runtime.Composable () -> Unit = {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MimirTheme {
                    MimirButtonPickerOverlay(
                        authority = authority,
                        state = state,
                        onFocus = { key -> state = mimirButtonPickerOnFocus(state, key) },
                        onTransition = { transition ->
                            handed += transition
                            state = transition.state
                        },
                        presenceManagedExternally = true,
                        stage = { _ -> },
                    )
                }
            }
        }

        val focused get() = authority.state.value.focused

        fun press(action: ControllerAction) = compose.runOnIdle {
            dispatchMimirButtonPicker(authority, state, action) { transition ->
                handed += transition
                state = transition.state
            }
        }
    }

    private fun frames(count: Int = 8) {
        repeat(count) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
        }
        compose.waitForIdle()
    }

    private fun open(fontScale: Float = 1f): Host {
        val host = Host()
        host.authority.pushScope(MimirButtonPickerScope)
        compose.setContent(host.content(fontScale))
        frames()
        compose.runOnIdle { host.authority.requestFocus(mimirButtonTileKey(star.id)) }
        frames(3)
        assertEquals(mimirButtonTileKey(star.id), host.focused)
        return host
    }

    @Test
    fun `Y opens adjust with the large coin and the four chips, and the tiles are hidden and silent but still hold focus`() {
        val host = open()
        compose.onNodeWithText("Your mark 1").assertOnScreen()
        host.press(ControllerAction.TERTIARY)
        frames()

        assertNotNull(host.state.adjust)
        compose.onNodeWithTag(MimirAdjustCoinTag).assertOnScreen()
        for (label in listOf("Smaller", "Larger", "Reset", "Done")) compose.onNodeWithTag(mimirAdjustChipTag(label)).assertOnScreen()
        compose.onNode(hasContentDescription(MimirButtonControlHintAdjusting)).assertOnScreen()
        assertEquals("the pad's keys are named in the footer alone, once", 0, compose.onAllNodesWithTextCount("X resets"))
        assertEquals("the tiles are silent while the chips stand where they do", 0, compose.onAllNodesWithTextCount("Your mark 1"))
        assertEquals("focus never left the image's tile", mimirButtonTileKey(star.id), host.focused)
    }

    @Test
    fun `the pad nudges and sizes the image while focus stays put, and B lands back on the tile`() {
        val host = open()
        host.press(ControllerAction.TERTIARY)
        frames()
        repeat(4) { host.press(ControllerAction.NAV_RIGHT) }
        host.press(ControllerAction.PAGE_RIGHT)
        frames()
        assertEquals(starPlacement.offsetX + 2f, host.state.adjust!!.working.offsetX, 0f)
        assertEquals(1.25f, host.state.adjust!!.working.scale, 0.00001f)
        assertEquals(mimirButtonTileKey(star.id), host.focused)
        assertEquals("nothing is saved until A", starPlacement, host.state.saved.first { it.id == star.id }.placement)

        host.press(ControllerAction.CANCEL)
        frames()
        assertNull(host.state.adjust)
        assertEquals(mimirButtonTileKey(star.id), host.focused)
        assertEquals(starPlacement, host.state.saved.first { it.id == star.id }.placement)
        compose.onNodeWithText("Your mark 1").assertOnScreen()
        assertEquals(MimirButtonPickerCommand.LeaveAdjust(star.id), handed.last().command)
    }

    @Test
    fun `the chips do what the pad keys do`() {
        val host = open()
        host.press(ControllerAction.TERTIARY)
        frames()
        compose.onNodeWithTag(mimirAdjustChipTag("Larger")).performClick()
        frames(3)
        assertEquals(1.25f, host.state.adjust!!.working.scale, 0.00001f)
        compose.onNodeWithTag(mimirAdjustChipTag("Smaller")).performClick()
        compose.onNodeWithTag(mimirAdjustChipTag("Smaller")).performClick()
        frames(3)
        assertEquals(1.15f, host.state.adjust!!.working.scale, 0.00001f)
        compose.onNodeWithTag(mimirAdjustChipTag("Reset")).performClick()
        frames(3)
        assertEquals(MimirMarkPlacement.Default, host.state.adjust!!.working)
        compose.onNodeWithTag(mimirAdjustChipTag("Done")).performClick()
        frames(3)
        assertNull(host.state.adjust)
        assertEquals(MimirButtonPickerCommand.SaveAdjusted(mimirButtonMarkOf(star.id, star.path!!, MimirMarkPlacement.Default)), handed.last().command)
        assertEquals("the image is the one in use", star.id, host.state.current?.id)
        assertEquals(mimirButtonTileKey(star.id), host.focused)
    }

    @Test
    fun `a drag on the large coin moves the image by the dp it travelled on the reference coin, and a pinch sizes it`() {
        val host = open()
        host.press(ControllerAction.TERTIARY)
        frames()
        val before = host.state.adjust!!.working

        compose.onNodeWithTag(MimirAdjustCoinTag).performTouchInput {
            down(center)
            moveBy(Offset(60f, 0f))
            moveBy(Offset(60f, 0f))
            up()
        }
        frames(4)
        val dragged = host.state.adjust!!.working
        assertTrue("moved right: ${before.offsetX} to ${dragged.offsetX}", dragged.offsetX > before.offsetX + 1f)
        assertEquals("and not down", before.offsetY, dragged.offsetY, 0.01f)
        assertEquals("a drag does not size", before.scale, dragged.scale, 0.0001f)

        val rightPx = 400f
        compose.onNodeWithTag(MimirAdjustCoinTag).performTouchInput {
            pinch(
                start0 = Offset(center.x - 20f, center.y), end0 = Offset(center.x - 60f, center.y),
                start1 = Offset(center.x + 20f, center.y), end1 = Offset(center.x + 60f, center.y),
            )
        }
        frames(4)
        val pinched = host.state.adjust!!.working
        assertTrue("a spread grows it: ${dragged.scale} to ${pinched.scale} (px $rightPx)", pinched.scale > dragged.scale + 0.05f)
        assertTrue("and it stays in range", abs(pinched.offsetX) <= 12f && pinched.scale <= 1.6f)
        assertEquals("nothing saved by touching", starPlacement, host.state.saved.first { it.id == star.id }.placement)
    }

    @Test
    fun `the footer names Y ADJUST on an image tile and not on the rune`() {
        val host = open()
        compose.onNode(hasContentDescription("Y ADJUST", substring = true)).assertOnScreen()
        compose.runOnIdle { host.authority.requestFocus(MimirButtonRuneKey) }
        frames(3)
        assertEquals(0, compose.onAllNodes(hasContentDescription("Y ADJUST", substring = true)).fetchSemanticsNodes().size)
        // X removes nothing on the rune, so its footer offers no X.
        compose.onNode(hasContentDescription(AppearancePickerControlHintNoRemove)).assertOnScreen()
    }

    @Test
    fun `the footer on the Upload tile says A UPLOAD, because A there opens the file picker and uses nothing`() {
        val host = open()
        compose.runOnIdle { host.authority.requestFocus(MimirButtonAddKey) }
        frames(3)
        assertEquals(MimirButtonAddKey, host.focused)
        compose.onNode(hasContentDescription(AppearancePickerControlHintUpload)).assertOnScreen()
        assertEquals("A UPLOAD · B BACK", AppearancePickerControlHintUpload)
        // And only that footer: neither the saved-image legend nor the rune's "A USE" is left on screen beside it.
        assertEquals(0, compose.onAllNodes(hasContentDescription(AppearancePickerControlHintNoRemove)).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasContentDescription("Y ADJUST", substring = true)).fetchSemanticsNodes().size)
        // A saved image keeps its own legend.
        compose.runOnIdle { host.authority.requestFocus(mimirButtonTileKey(star.id)) }
        frames(3)
        compose.onNode(hasContentDescription(MimirButtonControlHintAdjustable)).assertOnScreen()
        assertEquals(0, compose.onAllNodes(hasContentDescription(AppearancePickerControlHintUpload)).fetchSemanticsNodes().size)
    }

    @Test
    fun `adjust mode's header and footer speak in the picker's voice`() {
        val host = open()
        host.press(ControllerAction.TERTIARY)
        frames()
        compose.onNode(hasContentDescription(MimirButtonAdjustTitle)).assertOnScreen()
        compose.onNodeWithText(MimirButtonAdjustSubtitle).assertOnScreen()
        compose.onNode(hasContentDescription(MimirButtonControlHintAdjusting)).assertOnScreen()
    }

    /**
     * The node exists, has a size, and lies inside the root. (`assertIsDisplayed` is unreliable for text here: the unit-test classpath has no Plex
     * face, so a text node measures a few pixels wide; where a thing is and that it fits is the render test's, with the real fonts.)
     */
    private fun SemanticsNodeInteraction.assertOnScreen() {
        val node = fetchSemanticsNode()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val b = node.boundsInRoot
        assertTrue("size ${b.width} x ${b.height}", b.width > 0f && b.height > 0f)
        assertTrue("$b inside $root", b.left >= root.left && b.top >= root.top && b.right <= root.right && b.bottom <= root.bottom)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String, substring: Boolean = false): Int =
        onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().size
}

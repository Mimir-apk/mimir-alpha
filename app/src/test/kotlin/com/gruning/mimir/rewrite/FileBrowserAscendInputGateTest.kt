package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirTransitionInputGate
import com.gruning.mimir.rewrite.feature.files.FileBrowserController
import com.gruning.mimir.rewrite.feature.files.FileBrowserMode
import com.gruning.mimir.rewrite.feature.files.FileBrowserSurface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * On the real [FileBrowserSurface], going up a
 * folder takes presses at once. The ascent used to hold [MimirTransitionInputGate] (which the input router honours) while
 * the parent waited hidden for the child to clear, ~66 ms after 106. The gate is now watched frame by frame across a whole
 * ascent and a whole descent. Going INTO a folder never held a press, and is asserted to still hold none.
 *
 * The child's last frame departs as recorded pixels (core-ui's `MimirPageHandoff`), so nothing of it is in the semantics
 * tree or takes a press from the first frame, and the parent's focus graph is the only one: a press in the same frame goes
 * back into the folder the player came out of. The router itself is not stood up, so "a press is not dropped" is proved as
 * "the gate is never held", and "a press acts on the parent" as the controller's own dispatch after the ascent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w640dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileBrowserAscendInputGateTest {

    @get:Rule
    val compose = createComposeRule()

    private val tree = mapOf(
        "/root" to listOf(
            FileItem(name = "Games", path = "/root/Games", isDirectory = true),
            FileItem(name = "pack.zip", path = "/root/pack.zip", extension = "zip"),
            FileItem(name = "readme.txt", path = "/root/readme.txt", extension = "txt"),
        ),
        "/root/Games" to listOf(FileItem(name = "sonicadventure.gba", path = "/root/Games/sonicadventure.gba", extension = "gba")),
    )

    private val authority = DefaultFocusAuthority()

    private val controller = FileBrowserController(
        authority = authority,
        rootPath = "/root",
        mode = FileBrowserMode.MANAGE,
        listDirectory = { tree[it].orEmpty() },
        onChoose = {},
        onDismiss = {},
    )

    @Before
    fun gateClock() {
        MimirTransitionInputGate.reset()
        MimirTransitionInputGate.clock = { compose.mainClock.currentTime }
    }

    @After
    fun resetGate() = MimirTransitionInputGate.reset()

    private fun surface() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.fillMaxSize()) {
                    FileBrowserSurface(controller = controller, authority = authority)
                }
            }
        }
        settle()
    }

    private fun press(action: ControllerAction): Boolean {
        var handled = false
        compose.runOnUiThread { handled = controller.dispatch(action) }
        compose.mainClock.advanceTimeByFrame()
        return handled
    }

    private fun settle() = compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs * 6L)

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** Runs frames for [ms] and returns every frame's gate reading, so a single held frame anywhere is seen. */
    private fun holdingAcross(ms: Long): List<Boolean> {
        val readings = mutableListOf<Boolean>()
        val start = compose.mainClock.currentTime
        while (compose.mainClock.currentTime - start < ms) {
            readings += MimirTransitionInputGate.holding
            compose.mainClock.advanceTimeByFrame()
        }
        return readings
    }

    @Test
    fun `going up a folder holds no press, on any frame of the ascent`() {
        surface()
        assertTrue("the root lists its folder", shown("Games"))
        assertTrue(press(ControllerAction.CONFIRM))
        assertEquals("/root/Games", controller.currentPath)
        settle()
        assertTrue("standing in the child", shown("sonicadventure"))

        assertTrue(press(ControllerAction.CANCEL))
        assertEquals("/root", controller.currentPath)
        val readings = holdingAcross(400L)

        assertFalse("the gate was held on a frame of the ascent: $readings", readings.any { it })
        assertFalse(MimirTransitionInputGate.holding)
    }

    @Test
    fun `going into a folder holds no press either, as it never did`() {
        surface()
        assertTrue(press(ControllerAction.CONFIRM))
        val readings = holdingAcross(400L)
        assertFalse("the gate was held on a frame of the descent: $readings", readings.any { it })
    }

    @Test
    fun `the frame after going up the child is not a tree, and a press acts on the parent's graph`() {
        surface()
        press(ControllerAction.CONFIRM)
        settle()
        assertTrue(shown("sonicadventure"))

        press(ControllerAction.CANCEL)

        assertFalse("the child's tiles are recorded pixels: nothing of them is in the semantics tree", shown("sonicadventure"))
        assertTrue("the parent is the live page", shown("Games"))
        assertFalse("and no press is held", MimirTransitionInputGate.holding)
        assertTrue("a press in that same frame is taken", press(ControllerAction.CONFIRM))
        assertEquals("and it acted on the parent's focus: A on the folder went back into it", "/root/Games", controller.currentPath)
    }
}

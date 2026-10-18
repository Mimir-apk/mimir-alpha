package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
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
 * F4 (alpha audit 2026-09-30): the real [MimirKeyboard] in the shape both sheet-tier keyboards give it, the Library's filter
 * and the Files search: on a [MimirSheetHost] whose `visible` is the host's own flag, handed a
 * controller and a key decorator while it is open and neither once it is not.
 *
 * This verifies that the outgoing Files keyboard leaves no second live focus graph: the sheet keeps the keyboard
 * composed and drawing through its 180 ms exit, but from the FRAME `visible` turns false the keyboard has popped its scope
 * (`onExit`) and none of its keys is registered any more, so there is one focus graph, the screen's, while it slides away.
 * And nothing on it takes a touch. The two hosts' own wiring (that they pass exactly this) is pinned where they are,
 * `LibraryKeyboardSheetHostTest` and `FileBrowserSearchKeyboardSheetTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirSheetHostKeyboardTest {

    @get:Rule
    val compose = createComposeRule()

    private val open = mutableStateOf(false)

    /** Scope pushes and pops the host was told of, and the keys that are registered with the authority right now. */
    private var enters = 0
    private var exits = 0
    private val liveKeys = mutableSetOf<String>()
    private val typed = mutableListOf<String>()

    private val controller = MimirKeyboardController(
        onEnter = { enters++ },
        onExit = { exits++ },
        onEnterAt = { enters++ },
    )

    /** A registering decorator, as the real one is: a key that is decorated is a key in the authority. */
    private val decorator: MimirKeyboardKeyDecorator = { id, _ ->
        DisposableEffect(id.value) {
            liveKeys += id.value
            onDispose { liveKeys -= id.value }
        }
        MimirKeyboardKeyDecoration(Modifier, false)
    }

    private fun host() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.fillMaxSize()) {
                    MimirSheetHost(
                        visible = open.value,
                        reducedMotion = false,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.94f),
                    ) {
                        MimirKeyboard(
                            value = "",
                            onValueChange = { typed += it },
                            onDone = {},
                            controller = controller.takeIf { open.value },
                            keyDecorator = decorator.takeIf { open.value },
                            targetLabel = "Search files",
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun setOpen(value: Boolean) {
        Snapshot.withMutableSnapshot { open.value = value }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun settle() = compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs * 3L)

    private fun keyCount() = compose.onAllNodesWithContentDescription("Q", useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun `opening pushes the scope once and registers every key`() {
        host()
        assertEquals(0, keyCount())
        setOpen(true)
        settle()
        assertEquals("the scope was pushed once", 1, enters)
        assertTrue("every key is a focus target while it is open: ${liveKeys.size}", liveKeys.size > 25)
        compose.onNodeWithContentDescription("Q").performTouchInput { click() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("and a touch types", 1, typed.size)
    }

    @Test
    fun `the frame it is closed the scope is popped and no key is registered, while the sheet is still on screen`() {
        host()
        setOpen(true)
        settle()
        assertEquals(0, exits)

        setOpen(false)

        assertEquals("popped in the frame `visible` turned false, not at the end of the fade", 1, exits)
        assertTrue("no leaving key is a focus target: ${liveKeys.size}", liveKeys.isEmpty())
        assertEquals("but it is still composed and drawing: the exit is a real one", 1, keyCount())

        compose.mainClock.advanceTimeBy(60L)
        assertEquals("and mid-fade it is inert to touch", 1, keyCount())
        val before = typed.size
        compose.onAllNodesWithContentDescription("Q", useUnmergedTree = true)[0].performTouchInput { click() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("a key tapped while it leaves types nothing", before, typed.size)

        settle()
        assertEquals("gone once it has left", 0, keyCount())
        assertEquals("and the scope was popped once, not twice", 1, exits)
        assertEquals(1, enters)
    }

    @Test
    fun `a reopen inside the exit pushes the scope again and the keys answer again`() {
        host()
        setOpen(true)
        settle()
        setOpen(false)
        compose.mainClock.advanceTimeBy(40L)
        assertTrue(liveKeys.isEmpty())

        setOpen(true)
        settle()

        assertEquals("entered again", 2, enters)
        assertTrue("and every key is registered again: ${liveKeys.size}", liveKeys.size > 25)
        val before = typed.size
        compose.onNodeWithContentDescription("Q").performTouchInput { click() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(before + 1, typed.size)
        assertFalse(liveKeys.isEmpty())
    }
}

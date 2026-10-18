package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sheet exit touch safety: a sheet on the sheet tier that
 * cannot be touched while it leaves. The keyboards stayed composed through their exit with the pad kept off them by a null
 * controller, but a touch on a key in those 180 ms typed into a field whose keyboard was closed.
 *
 * The content is two stand-in keys, the two kinds of key a keyboard has: one on `clickable`, which reads a consumed pointer
 * as not its own, and one on `awaitFirstDown(requireUnconsumed = false)`, the way Backspace and Space do, which does not.
 * The second is why the shield is a sibling over the content and not a modifier on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MimirSheetHostTest {

    @get:Rule
    val compose = createComposeRule()

    private val visible = mutableStateOf(false)
    private val taps = mutableListOf<String>()

    private fun host(reducedMotion: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                MimirSheetHost(
                    visible = visible.value,
                    reducedMotion = reducedMotion,
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    Box(Modifier.size(width = 200.dp, height = 100.dp)) {
                        Box(Modifier.size(80.dp).testTag("key").clickable { taps += "key" })
                        Box(
                            Modifier
                                .size(80.dp)
                                .align(Alignment.CenterEnd)
                                .testTag("space")
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            awaitFirstDown(requireUnconsumed = false)
                                            taps += "space"
                                            waitForUpOrCancellation()
                                        }
                                    }
                                },
                        )
                    }
                }
            }
        }
        frame()
    }

    private fun frame() = compose.mainClock.advanceTimeByFrame()

    private fun setVisible(value: Boolean) {
        visible.value = value
        Snapshot.sendApplyNotifications()
        frame()
    }

    private fun settle() = compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs * 3L)

    private fun tap(tag: String): List<String> {
        val before = taps.size
        compose.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput { click() }
        frame()
        return taps.drop(before)
    }

    private fun composed(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun answers(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a hidden sheet composes nothing, and an open one takes touches`() {
        host()
        assertFalse("nothing is composed while hidden", composed("key"))
        setVisible(true)
        settle()
        assertTrue(composed("key"))
        assertEquals(listOf("key"), tap("key"))
        assertEquals(listOf("space"), tap("space"))
    }

    @Test
    fun `a sheet that is leaving takes no touch on either kind of key, and clears its semantics`() {
        host()
        setVisible(true)
        settle()
        assertTrue("open, its keys answer accessibility", answers("key"))

        setVisible(false)
        compose.mainClock.advanceTimeBy(60L)
        assertTrue("still composed and mid-fade: the exit is a real one", composed("key"))
        assertFalse("but its semantics are cleared, so accessibility does not offer a key that does nothing", answers("key"))
        assertEquals("a clickable key takes nothing", emptyList<String>(), tap("key"))
        assertEquals(
            "and neither does a key that ignores a consumed pointer, which is why the shield is a sibling",
            emptyList<String>(),
            tap("space"),
        )

        settle()
        assertFalse("and once it has left it is gone", composed("key"))
    }

    @Test
    fun `a sheet asked to come back while it is leaving is a sheet again at once`() {
        host()
        setVisible(true)
        settle()
        setVisible(false)
        compose.mainClock.advanceTimeBy(40L)
        setVisible(true)
        compose.mainClock.advanceTimeBy(40L)

        assertTrue("its semantics come back", answers("key"))
        assertEquals(listOf("key"), tap("key"))
        assertEquals(listOf("space"), tap("space"))
    }

    @Test
    fun `reduced motion snaps the sheet in and out`() {
        host(reducedMotion = true)
        setVisible(true)
        frame()
        frame()
        assertTrue("there at once", composed("key"))
        assertEquals(listOf("key"), tap("key"))

        setVisible(false)
        frame()
        frame()
        assertFalse("and gone at once", composed("key"))
    }
}

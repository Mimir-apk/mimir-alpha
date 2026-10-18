package com.gruning.mimir.rewrite.feature.settings

import android.animation.ValueAnimator
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Music's transitions on a paused clock (a missing transition is a defect). A write to state
 * under `autoAdvance = false` needs [Snapshot.sendApplyNotifications], or nothing recomposes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w640dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicPaneTransitionTest {
    @get:Rule
    val compose = createComposeRule()

    private fun anchorTop(): Dp = compose.onNodeWithText("anchor").getUnclippedBoundsInRoot().top

    /** Animator scale (Developer options): 0 is reduced motion. The setter is hidden, so by reflection. */
    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun write(change: () -> Unit) {
        change()
        Snapshot.sendApplyNotifications()
    }

    @Test
    fun `a row that comes and goes grows in and out on the overlay clock and is not composed once gone`() {
        var visible by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Column {
                    SettingsRowReveal(visible) { Text("probe") }
                    Text("anchor")
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("probe").assertDoesNotExist()
        val before = anchorTop()

        write { visible = true }
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithText("probe").assertExists()
        val mid = anchorTop()
        compose.mainClock.advanceTimeBy(600)
        val settled = anchorTop()
        assertTrue("it has grown by the settled frame ($before -> $settled)", settled > before)
        assertTrue("mid-growth is strictly between ($before < $mid < $settled)", mid > before && mid < settled)

        write { visible = false }
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithText("probe").assertExists()
        val leaving = anchorTop()
        assertTrue("mid-shrink is strictly between ($before < $leaving < $settled)", leaving > before && leaving < settled)
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("probe").assertDoesNotExist()
        assertEquals("no gap is left behind", before.value, anchorTop().value, 0.5f)
    }

    @Test
    fun `reduced motion jumps a row in and out`() {
        setAnimatorScale(0f)
        try {
            var visible by mutableStateOf(false)
            compose.mainClock.autoAdvance = false
            compose.setContent {
                MimirTheme {
                    Column {
                        SettingsRowReveal(visible) { Text("probe") }
                        Text("anchor")
                    }
                }
            }
            compose.mainClock.advanceTimeByFrame()
            val before = anchorTop()
            write { visible = true }
            compose.mainClock.advanceTimeBy(48)
            compose.onNodeWithText("probe").assertExists()
            val settled = anchorTop()
            assertTrue(settled > before)
            write { visible = false }
            compose.mainClock.advanceTimeBy(48)
            compose.onNodeWithText("probe").assertDoesNotExist()
            assertEquals(before.value, anchorTop().value, 0.5f)
        } finally {
            setAnimatorScale(1f)
        }
    }

    @Test
    fun `a supporting line that changes in place crossfades, and only the new one is left once settled`() {
        var line by mutableStateOf("Song · Music on this device")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                SettingsRow(
                    key = FocusKey("test/row"),
                    scope = FocusScope("test"),
                    authority = DefaultFocusAuthority(),
                    title = "Now playing",
                    supporting = line,
                    swapSupporting = true,
                )
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Song · Music on this device").assertExists()

        write { line = "Other · Music on this device\nFLAC · 24/96" }
        compose.mainClock.advanceTimeBy(60)
        compose.onNodeWithText("Song · Music on this device").assertExists()
        compose.onNodeWithText("Other · Music on this device\nFLAC · 24/96").assertExists()

        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Song · Music on this device").assertDoesNotExist()
        compose.onNodeWithText("Other · Music on this device\nFLAC · 24/96").assertExists()
    }
}

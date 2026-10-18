package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A line of animated chrome text that changes length resizes on the fade's own clock, unclipped (including seven Customize Media callers and the platform chip). The bare `togetherWith` carries a DEFAULT size transform, a clipped spring that
 * settles long after the 140 ms fade has finished and cuts a grown line at its old size while it fades in. The frames below are the
 * container's height (the text fills the width it is given, so a longer string is more rows): it must reach the new line's height by
 * the time the fade is whole.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirAnimatedChromeTextResizeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun source(): String = listOf(
        File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirTextIntent.kt"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirTextIntent.kt"),
    ).first(File::exists).readText()

    @Test
    fun `a line that grows a row reaches its height inside the fade, on the same clock`() {
        var text by mutableStateOf("OK")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            // The text fills the width it is given, so a longer string shows as more rows: the container's HEIGHT is what grows.
            Box(Modifier.width(120.dp)) {
                MimirAnimatedChromeText(text = text, modifier = Modifier.testTag("line"))
            }
        }
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val small = compose.onNodeWithTag("line").fetchSemanticsNode().size.height

        Snapshot.withMutableSnapshot { text = "A MUCH LONGER STATUS LINE THAN BEFORE, WHICH WRAPS ONTO SEVERAL ROWS" }
        val heights = mutableListOf<Int>()
        repeat(20) {
            compose.mainClock.advanceTimeBy(16)
            Snapshot.sendApplyNotifications()
            compose.waitForIdle()
            heights += compose.onNodeWithTag("line").fetchSemanticsNode().size.height
        }
        val grown = heights.last()
        assertTrue("the line is taller afterwards: $small -> $grown", grown > small)
        assertTrue("it moves through intermediate heights, not a cut: $heights", heights.any { it in (small + 1) until grown })

        // 16 ms frames: the fade (FocusMs) is whole by frame 9; the container is at its new height by then (a frame or two of slack for the
        // frame the change is first seen on) and does not creep after.
        val frameWhenWhole = (MimirMotion.FocusMs + 15) / 16
        val settled = heights.drop(frameWhenWhole + 2)
        assertTrue("the height is final once the fade is whole: $heights", settled.all { it == grown })
    }

    @Test
    fun `the resize rides the fade's clock, unclipped, in the source`() {
        val text = source().substringAfter("fun MimirAnimatedChromeText(\n    intent: MimirTextIntent,")
        val spec = text.substringAfter("transitionSpec = {").substringBefore("contentAlignment")
        val clock = "tween(MimirMotion.FocusMs, easing = MimirMotion.Easing)"
        assertEquals("fade in, fade out and the resize: one clock three times", 3, Regex(Regex.escape(clock)).findAll(spec).count())
        assertTrue(spec.contains(".using("))
        assertTrue("never clipped", spec.contains("SizeTransform(clip = false)"))
    }
}

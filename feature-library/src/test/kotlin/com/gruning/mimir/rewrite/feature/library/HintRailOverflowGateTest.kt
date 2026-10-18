package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
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
 * Audit 2026-10-14, W3a, the hint legend's edge fade: the offscreen layer and the edge mask are on only while the legend overflows its pill.
 *
 * The fade's gate ([hintRailLegendOverflows]) and the marquee's ([hintRailMarqueePassMs]) read the same two measured widths, so the layer is on exactly when the row scrolls. A first
 * version of this change decided the gate against the room the pill could have, on the premise that the pill's own width is short while the legend expands; and it counted the
 * pill's 12 dp padding twice (`contentWidthPx` is measured outside it), which gave a legend within 24 dp of the cap a layer and a mask and never a scroll. The premise is false
 * (the last test: `expandHorizontally` hands its child one size for the whole expand), so the original comparison stands and the change was reverted to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h560dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HintRailOverflowGateTest {

    @get:Rule
    val compose = createComposeRule()

    private val spacingPx = 48
    private val velocity = 160f

    /** What `onSizeChanged` reports of the pill: the row's width, held at the room the pill can have (the marquee measures its child unbounded and the pill takes what fits). */
    private fun pillWidth(contentWidthPx: Int, roomPx: Int) = minOf(contentWidthPx, roomPx)

    private fun scrolls(contentWidthPx: Int, windowWidthPx: Int): Boolean =
        hintRailMarqueePassMs(contentWidthPx, windowWidthPx, spacingPx, velocity) > 0L

    @Test
    fun `a legend just under the cap gets no layer and does not scroll, one over it gets both`() {
        val room = 720
        // The case the double count got wrong: a row 24 px (the padding, both sides) under the cap fitted, and was given the layer and the mask without ever scrolling.
        listOf(room - 24, room - 12, room - 1, room).forEach { content ->
            val window = pillWidth(content, room)
            assertFalse("a $content px row in $room px fits: no layer", hintRailLegendOverflows(content, window))
            assertFalse("and it does not scroll", scrolls(content, window))
        }
        listOf(room + 1, room + 12, room + 24, room * 2).forEach { content ->
            val window = pillWidth(content, room)
            assertTrue("a $content px row in $room px overflows: the layer and the mask", hintRailLegendOverflows(content, window))
            assertTrue("and it scrolls", scrolls(content, window))
        }
    }

    @Test
    fun `the fade is on exactly when the marquee moves, for every pair of widths`() {
        for (window in 0..400 step 5) {
            for (content in 0..400 step 3) {
                assertEquals("window $window, content $content px", scrolls(content, window), hintRailLegendOverflows(content, window))
            }
        }
        assertFalse("nothing measured, no layer", hintRailLegendOverflows(0, 0))
        assertFalse("a row measured before its pill is: no layer yet", hintRailLegendOverflows(500, 0))
    }

    /**
     * Whether the pill is narrowed while the legend expands: the premise the first version of this change was written on, and it does not hold. `expandHorizontally` measures its
     * child with the constraints it was given and animates only the size it reports to its parent, so the child sees one size for the whole expand. `onSizeChanged` fires on a
     * change, so a single report across twenty frames of the expand is the child's size never moving.
     */
    @Test
    fun `expandHorizontally hands its child one size for the whole expand`() {
        val visible = mutableStateOf(false)
        val seen = mutableListOf<Int>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                AnimatedVisibility(visible = visible.value, enter = expandHorizontally(tween(180), expandFrom = Alignment.End)) {
                    Box(Modifier.width(300.dp).onSizeChanged { seen += it.width })
                }
            }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { Snapshot.withMutableSnapshot { visible.value = true } }
        Snapshot.sendApplyNotifications()
        repeat(20) { compose.mainClock.advanceTimeBy(16) }
        assertTrue("the child was measured", seen.isNotEmpty())
        assertEquals("one size the whole way: $seen", listOf(300), seen)
    }
}

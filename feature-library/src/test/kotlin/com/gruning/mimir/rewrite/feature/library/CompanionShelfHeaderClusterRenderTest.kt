package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.BottomTrackpadPlateTag
import com.gruning.mimir.rewrite.core.ui.LocalBottomTrackpadOpen
import com.gruning.mimir.rewrite.core.ui.MimirBottomMarkDefaultTopDp
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderRowTag
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderTag
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shelf's header, with the Bottom's coin free to go anywhere (policy 2026-10-09, which retired the 2026-10-08 corner slot): the trailing cluster (the count, the
 * density chip, the Trackpad plate) ends at the page's 16 dp end inset with no empty band at the top right, and nothing is reserved for the coin. Rendered at the Thor Bottom's
 * 1240 x 1080 px at 369 dpi, at font scale 1.0 and 1.3, in the page's own 10 dp top inset and 16 dp gutters.
 *
 * Held: the plate is the last control and ends exactly at the row's end; the density chip and the plate are no further apart than the normal spacing; the count and the density
 * readout are one line each; the header ends above the coin's default seat ([MimirBottomMarkDefaultTopDp]). Pixels are the JVM's; the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionShelfHeaderClusterRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val density = 369f / 160f
    private val widthDp = 1240f / density

    @Before
    fun setUp() {
        PlatformTileArtStore.art.value = emptyMap()
        PlatformTileArtStore.hydrated.value = true
    }

    @After
    fun tearDown() {
        PlatformTileArtStore.art.value = emptyMap()
        PlatformTileArtStore.hydrated.value = false
    }

    private fun mount(fontScale: Float) {
        compose.mainClock.autoAdvance = false
        val openTrackpad: () -> Unit = {}
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density, fontScale),
                LocalBottomTrackpadOpen provides openTrackpad,
            ) {
                MimirTheme {
                    // The page: 10 dp from the top, 16 dp a side (CompanionPlatformShelf).
                    Box(Modifier.requiredSize(widthDp.dp, 120.dp).testTag(HostTag).padding(start = MimirSpacing.lg, end = MimirSpacing.lg, top = CompanionShelfTopInsetDp.dp)) {
                        CompanionShelfHeader(
                            platformIdValue = "genesis",
                            title = "Genesis",
                            gameCount = 43,
                            density = CompanionShelfDensity.STANDARD,
                            active = true,
                            onBack = {},
                            onDensityMenu = {},
                        )
                    }
                }
            }
        }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    private class Box4(val x: Float, val y: Float, val w: Float, val h: Float) {
        val right get() = x + w
        val bottom get() = y + h
        override fun toString() = "%.1fx%.1f@(%.1f,%.1f)".format(w, h, x, y)
    }

    private fun SemanticsNode.dp(): Box4 {
        val o = compose.onNodeWithTag(HostTag).fetchSemanticsNode().positionInRoot
        val p = positionInRoot
        return Box4((p.x - o.x) / density, (p.y - o.y) / density, size.width / density, size.height / density)
    }

    private fun bounds(tag: String): Box4 = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().dp()

    private fun lines(tag: String): Int {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        check(node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true) { "$tag has no text layout" }
        return layouts.first().lineCount
    }

    private fun assertClusterAtHeaderEnd(fontScale: Float) {
        mount(fontScale)
        val row = bounds(MimirCompactHeaderRowTag)
        val header = bounds(MimirCompactHeaderTag)
        val plate = bounds(BottomTrackpadPlateTag)
        val chip = bounds(CompanionShelfDensityChipTag)
        val count = bounds(CompanionShelfCountTag)
        println("HDR-SHELF font=$fontScale row=$row header=$header plate=$plate chip=$chip count=$count")
        assertEquals("the row ends at the page's 16 dp end inset", widthDp - 16f, row.right, 1f)
        assertEquals("the Trackpad plate is the last control, at the row's end (no blank band at the top right)", row.right, plate.right, 1f)
        assertTrue("the density chip is no further from the plate than the normal spacing: ${plate.x - chip.right}", plate.x - chip.right <= MimirSpacing.md.value + 0.6f)
        assertTrue("and the count no further from the chip: ${chip.x - count.right}", chip.x - count.right <= MimirSpacing.md.value + 0.6f)
        assertEquals("the count is one line", 1, lines(CompanionShelfCountTag))
        assertEquals("and so is the density readout", 1, lines(CompanionShelfDensityChipTextTag))
        assertTrue("the header (to ${header.bottom} dp) ends above the coin's default seat at $MimirBottomMarkDefaultTopDp", header.bottom <= MimirBottomMarkDefaultTopDp - 0.5f)
    }

    @Test
    fun `the shelf header's trailing cluster ends at the page's end inset at font scale 1 point 0`() = assertClusterAtHeaderEnd(1.0f)

    @Test
    fun `the shelf header's trailing cluster ends at the page's end inset at font scale 1 point 3`() = assertClusterAtHeaderEnd(1.3f)

    private companion object {
        const val HostTag = "hdr-shelf-host"
    }
}

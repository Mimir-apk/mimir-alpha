package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.BottomTrackpadPlateTag
import com.gruning.mimir.rewrite.core.ui.LocalBottomTrackpadOpen
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderRowTag
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderTitleTag
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
 * Pad4 (the Trackpad plate is on every Bottom header): the platform shelf's header beside its count
 * and density chip, at the Thor Bottom's width (537.7 dp, so the page is 505.7 dp) and font scales 1.0 and 1.3, with a long
 * platform name and a four-digit game count (the crowded case). Geometry is printed with the tag `PAD4-SHELF`. Pixel values are
 * the JVM's; the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionShelfTrackpadPlateTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f
    private val pageWidthDp = 1240f / density369 - 32f
    private var density = 0f
    private var opened = 0

    private fun mount(fontScale: Float, offered: Boolean = true, active: Boolean = true) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, fontScale)) {
                MimirTheme {
                    density = LocalDensity.current.density
                    CompositionLocalProvider(LocalBottomTrackpadOpen provides if (offered) ({ opened++ }) else null) {
                        Box(Modifier.requiredSize(pageWidthDp.dp, 96.dp).testTag("host")) {
                            CompanionShelfHeader(
                                platformIdValue = "psp",
                                title = "PlayStation Portable",
                                gameCount = 1234,
                                density = CompanionShelfDensity.STANDARD,
                                active = active,
                                onBack = {},
                                onDensityMenu = {},
                            )
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    private fun boundsDp(tag: String): FloatArray {
        val b = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val o = compose.onNodeWithTag("host").fetchSemanticsNode().boundsInRoot
        return floatArrayOf((b.left - o.left) / density, (b.top - o.top) / density, b.width / density, b.height / density)
    }

    private fun right(b: FloatArray) = b[0] + b[2]

    /** Mounts [fontScale] and reports the header's geometry. */
    private fun measure(fontScale: Float): Map<String, FloatArray> {
        mount(fontScale)
        val geometry = mapOf(
            "row" to boundsDp(MimirCompactHeaderRowTag),
            "pill" to boundsDp(CompanionShelfBackPillTag),
            "title" to boundsDp(MimirCompactHeaderTitleTag),
            "count" to boundsDp(CompanionShelfCountTag),
            "chip" to boundsDp(CompanionShelfDensityChipTag),
            "plate" to boundsDp(BottomTrackpadPlateTag),
        )
        println("PAD6-SHELF scale=$fontScale " + geometry.entries.joinToString(" ") { (k, v) -> "$k=${v.toList()}" })
        return geometry
    }

    private fun assertOrderedAndInside(g: Map<String, FloatArray>) {
        val plate = g.getValue("plate")
        val chip = g.getValue("chip")
        val count = g.getValue("count")
        val row = g.getValue("row")
        assertTrue("the plate is at least a 48 dp target: ${plate.toList()}", plate[2] >= 47.9f && plate[3] >= 47.9f)
        assertTrue("the plate sits inside the header row: ${right(plate)} <= ${right(row)}", right(plate) <= right(row) + 0.5f)
        assertTrue("the count ends before the chip, the chip before the plate", right(count) <= chip[0] + 0.5f && right(chip) <= plate[0] + 0.5f)
        assertTrue("the title ends before the count", right(g.getValue("title")) <= count[0] + 0.5f)
    }

    private fun assertIconPlate(scale: Float) {
        val g = measure(scale)
        assertOrderedAndInside(g)
        val plate = g.getValue("plate")
        assertEquals("an icon: the 48 dp square at every font scale", 48f, plate[2], 0.6f)
        assertTrue("the title keeps a seat: ${g.getValue("title").toList()}", g.getValue("title")[2] >= 80f)
        compose.onAllNodesWithText("Trackpad", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag(BottomTrackpadPlateTag, useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Trackpad", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the plate is a 48 dp icon beside the count and chip at the system font size`() = assertIconPlate(1.0f)

    @Test
    fun `the plate is the same icon at the certified larger font scale`() = assertIconPlate(1.3f)

    @Test
    fun `a tap on the plate opens the pad through the gateway`() {
        mount(1.0f)
        compose.onNodeWithTag(BottomTrackpadPlateTag, useUnmergedTree = true).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `an inactive shelf does not open the pad`() {
        mount(1.0f, active = false)
        compose.onNodeWithTag(BottomTrackpadPlateTag, useUnmergedTree = true).performClick()
        assertEquals(0, opened)
    }

    @Test
    fun `with no trackpad offered the header has no plate`() {
        mount(1.0f, offered = false)
        compose.onAllNodesWithTag(BottomTrackpadPlateTag, useUnmergedTree = true).assertCountEquals(0)
    }
}

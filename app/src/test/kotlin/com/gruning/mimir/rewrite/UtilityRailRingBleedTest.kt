package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
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
 * Utility's first row keeps 6 dp under the divider while the rail still clears the ring's
 * reach (ring migration, 2026-09-28): the scroll viewport bleeds up behind the divider instead of pushing the row down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UtilityRailRingBleedTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the bleed is the ring reach less the divider inset`() {
        assertEquals(6.dp, UtilityRailFirstRowInset)
        assertEquals(8.dp, UtilityRailRingBleed)
        assertEquals(MimirFocusTileRingReach, UtilityRailFirstRowInset + UtilityRailRingBleed)
    }

    @Test
    fun `the first row sits 6 dp under the divider and the ring's reach lies over it`() {
        rail(panelHeight = 300.dp, fill = false, rows = 1)
        val divider = bounds("divider")
        val viewport = bounds("viewport")
        val row = bounds("row0")
        val footer = bounds("footer")
        near(UtilityRailFirstRowInset, row.top - divider.bottom)
        near(divider.bottom - UtilityRailRingBleed, viewport.top)
        // the whole reach above the row is inside the scroll viewport, so the clip cannot cut it
        assertTrue(row.top - viewport.top >= MimirFocusTileRingReach - 0.5.dp)
        // nothing after the rail moves: the footer meets the viewport's bottom with no gap
        near(viewport.bottom, footer.top)
    }

    @Test
    fun `a filled overflowing rail still takes exactly its weighted slot`() {
        rail(panelHeight = 200.dp, fill = true, rows = 6)
        val divider = bounds("divider")
        val viewport = bounds("viewport")
        val footer = bounds("footer")
        near(divider.bottom - UtilityRailRingBleed, viewport.top)
        near(viewport.bottom, footer.top)
        near(200.dp - 1.dp, footer.top)
    }

    @Test
    fun `a zero slot holds zero`() {
        rail(panelHeight = 2.dp, fill = true, rows = 1)
        val divider = bounds("divider")
        val footer = bounds("footer")
        near(divider.bottom, footer.top)
    }

    @Test
    fun `the rail viewport bleeds after its visible-viewport report`() {
        val source = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"),
        ).first(File::isFile).readText()
        val box = source.substringAfter(".weight(1f, fill = presentedSurface == UtilitySurface.TWEAKER)")
            .substringBefore(".verticalScroll(presentedRailScroll)")
        val report = box.indexOf(".onGloballyPositioned")
        val bleed = box.indexOf(".utilityRailBleedUp(UtilityRailRingBleed)")
        assertTrue(report >= 0 && bleed > report)
        assertEquals(1, Regex("""\.utilityRailBleedUp\(UtilityRailRingBleed\)""").findAll(source).count())
    }

    private fun rail(panelHeight: Dp, fill: Boolean, rows: Int) {
        compose.setContent {
            Column(Modifier.size(320.dp, panelHeight)) {
                Box(Modifier.fillMaxWidth().height(1.dp).testTag("divider"))
                Box(
                    Modifier
                        .weight(1f, fill = fill)
                        .utilityRailBleedUp(UtilityRailRingBleed)
                        .testTag("viewport"),
                ) {
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(top = MimirFocusTileRingReach, bottom = MimirFocusTileRingReach),
                    ) {
                        repeat(rows) { Box(Modifier.fillMaxWidth().height(58.dp).testTag("row$it")) }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).testTag("footer"))
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun near(expected: Dp, actual: Dp) {
        assertEquals(expected.value, actual.value, 0.75f)
    }
}

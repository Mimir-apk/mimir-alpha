package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirMannazPin
import com.gruning.mimir.rewrite.core.ui.MimirMannazPinCornerDp
import com.gruning.mimir.rewrite.core.ui.MimirMannazPinHeightDp
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirMannazPinWidth
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
 * The two marks on one shelf tile are one size of plate (two plate sizes on one tile was the Library render finding, 2026-10-11). The favourite rune stands on
 * [MimirMannazPinHeightDp] and a [MimirMannazPinCornerDp] corner; the disc badge beside it was about 17 dp tall with 6 dp corners. It is now
 * at least the rune's height, with the rune's corner. The shelf's pin has no compact height (the 16 dp one is the Tome overlay's own), so the
 * badge has none either: at the compact density and at both font scales it stands the pin's height.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w600dp-h600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameShelfCollapseBadgePlateTest {

    @get:Rule
    val compose = createComposeRule()

    private fun mount(fontScale: Float) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MimirTheme {
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(8.dp)) {
                        MimirMannazPin(present = true, modifier = Modifier.testTag("pin"))
                        GameShelfCollapseBadge("2 discs")
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertBadgeIsThePinsPlate(fontScale: Float) {
        mount(fontScale)
        val pin = compose.onNodeWithTag("pin").fetchSemanticsNode().boundsInRoot
        val badge = compose.onNodeWithTag(GameShelfCollapseBadgeTestTag).fetchSemanticsNode().boundsInRoot
        assertEquals("the pin stands its authored height", MimirMannazPinHeightDp.toFloat(), pin.height, 0.5f)
        assertEquals("the pin keeps its authored width", mimirMannazPinWidth(MimirMannazPinHeightDp.dp).value, pin.width, 0.5f)
        assertTrue("the badge's plate (${badge.height}) is at least the pin's (${pin.height}) at font scale $fontScale", badge.height >= pin.height - 0.5f)
        assertEquals("and they line up at the top, so the two marks are one row of plates", pin.top, badge.top, 0.5f)
        println("PLATE font $fontScale: pin ${pin.height} dp, badge ${badge.height} dp")
    }

    @Test
    fun `the badge plate is the rune plate's height at font scale 1_0`() = assertBadgeIsThePinsPlate(1f)

    @Test
    fun `the badge plate is the rune plate's height at font scale 1_3`() = assertBadgeIsThePinsPlate(1.3f)

    @Test
    fun `the badge's corner is the rune plate's constant, not a literal of its own`() {
        val shelf = shelfSource()
        val badge = shelf.substringAfter("internal fun GameShelfCollapseBadge(").substringBefore("internal const val GameShelfCollapseBadgeTestTag")
        assertTrue("the plate takes the pin's corner: $badge", badge.contains("RoundedCornerShape(MimirMannazPinCornerDp.dp)"))
        assertTrue("and the pin's height as its minimum: $badge", badge.contains(".heightIn(min = MimirMannazPinHeightDp.dp)"))
        assertTrue("no corner literal of the badge's own is left", !shelf.contains("GameShelfBadgeCorner"))
    }

    private fun shelfSource(): String {
        val name = "GameShelf.kt"
        val candidates = listOf(
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText() ?: error("$name not found from ${File(".").absolutePath}")
    }
}

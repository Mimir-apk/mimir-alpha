package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import com.gruning.mimir.rewrite.core.ui.MimirAchievementChip
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
 * Ledger D17: the launch card's count slot, with no count, is [HomeLaunchCardChipSeat]: the chip's room and nothing else. It has to be the room the chip takes, to the pixel,
 * at every font scale (the card lays everything out around it, and a count that arrives must move nothing), and it has to leave no semantics node, empty or not, for a screen
 * reader to land on (the invisible chip it replaces left one).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w832dp-h468dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeLaunchCardChipSeatTest {

    @get:Rule
    val compose = createComposeRule()

    private fun assertSeatMatchesChip(density: Float, fontScale: Float) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MimirTheme {
                    Column {
                        Box(Modifier.testTag(SeatHost)) { HomeLaunchCardChipSeat() }
                        Box(Modifier.testTag(ChipHost)) { MimirAchievementChip(label = HomeLaunchCardChipPlaceholder) }
                    }
                }
            }
        }
        compose.waitForIdle()
        val seat = compose.onNodeWithTag(SeatHost, useUnmergedTree = true).fetchSemanticsNode().size
        val chip = compose.onNodeWithTag(ChipHost, useUnmergedTree = true).fetchSemanticsNode().size
        println("D17-SEAT density=$density font=$fontScale seat=$seat chip=$chip")
        assertEquals("density $density font $fontScale: the seat is as wide as the chip", chip.width, seat.width)
        assertEquals("density $density font $fontScale: and as tall", chip.height, seat.height)
        assertTrue("sanity: a real size ${seat.width} x ${seat.height}", seat.width > 20 && seat.height > 10)
        val inSeat = compose.onAllNodes(hasAnyAncestor(hasTestTag(SeatHost)), useUnmergedTree = true).fetchSemanticsNodes().size
        val inChip = compose.onAllNodes(hasAnyAncestor(hasTestTag(ChipHost)), useUnmergedTree = true).fetchSemanticsNodes().size
        assertEquals("density $density font $fontScale: the seat has no semantics node of any kind", 0, inSeat)
        assertTrue("sanity: the chip does have nodes ($inChip), so the count above means something", inChip > 0)
    }

    @Test
    fun `the seat is the chip's size at font 1_0 on Thor Main`() = assertSeatMatchesChip(2.625f, 1.0f)

    @Test
    fun `the seat is the chip's size at font 1_3`() = assertSeatMatchesChip(2.625f, 1.3f)

    @Test
    fun `the seat is the chip's size at font 2_0`() = assertSeatMatchesChip(2.625f, 2.0f)

    @Test
    fun `the seat is the chip's size at density 1 and font 1_5, where the card's own tests run`() = assertSeatMatchesChip(1f, 1.5f)

    private companion object {
        const val SeatHost = "chip-seat-host"
        const val ChipHost = "chip-seat-chip-host"
    }
}

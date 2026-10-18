package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Library legend previously self-revealed once per process on a cold-launch shelf and collapsed 2.6s later. The real HintRailButton, on a paused clock, at the Nova's viewport: the
 * first composition is collapsed, it stays collapsed through the old reveal-and-collapse window, and only a tap on
 * the Guidance rune opens it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HintRailNeverAutoOpensTest {

    @get:Rule
    val compose = createComposeRule()

    private fun host() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                MimirTheme {
                    // Robolectric's window is shorter than 480 dp, so requiredHeight centres this box and its top edge
                    // (where the button sits) falls above the root: the button is placed, not displayed. Bounds are read
                    // relative to this box instead.
                    Box(Modifier.requiredWidth(640.dp).requiredHeight(480.dp).testTag("hint-rail-host")) {
                        HintRailButton(
                            hints = shelfFooterHints(RuntimeEnvironment.getApplication().resources, nativeMode = false),
                            contextKey = "shelf:EMULATION",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the legend stays shut on a cold first composition and through the old reveal window`() {
        host()
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        val host = compose.onNodeWithTag("hint-rail-host").getUnclippedBoundsInRoot()
        val button = compose.onNodeWithTag(HintRailGuidanceButtonTestTag).assertExists().getUnclippedBoundsInRoot()
        assertTrue("button " + button + " inside host " + host, button.top >= host.top && button.bottom <= host.bottom)
        assertTrue("button has size " + button, button.height > 0.dp && button.width > 0.dp)
        compose.onNodeWithTag(HintRailLegendTestTag).assertDoesNotExist()

        compose.mainClock.advanceTimeBy(OldRevealWindowMs)
        compose.onNodeWithTag(HintRailLegendTestTag).assertDoesNotExist()
    }

    @Test
    fun `only the Guidance rune opens the legend`() {
        host()
        compose.mainClock.advanceTimeBy(OldRevealWindowMs)
        compose.onNodeWithTag(HintRailLegendTestTag).assertDoesNotExist()

        compose.onNodeWithTag(HintRailGuidanceButtonTestTag).performClick()
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(RevealSettleMs)
        compose.onNodeWithTag(HintRailLegendTestTag).assertIsDisplayed()
    }

    private companion object {
        /** The retired one-shot's whole life: 260 ms reveal, 2 600 ms hold, 220 ms collapse, with slack. */
        const val OldRevealWindowMs = 3_500L

        /** Past the 260 ms reveal, well inside the 2 600 ms hold. */
        const val RevealSettleMs = 1_000L
    }
}

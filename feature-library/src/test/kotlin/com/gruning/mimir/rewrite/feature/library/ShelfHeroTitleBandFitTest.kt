package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cert 2026-10-08 (A3, e7-07): "1 game" showed with its baseline cut under the platform name. The review read that as the
 * fixed [CoverflowTitleBandHeightDp] (48 dp) being too short for the title plus the count line. It is not: the two lines are
 * 18 sp + 13 sp tall, so they sit inside the nominal band with room to spare, at the standard font scale and at the raised one
 * (1.3, the scale the instrumented shelf tests use). What cut the line on the 411 dp emulator is the vertical lane: the
 * weighted coverflow box is 411 - 8 - 4.6 (dock gap) - 45 - 10 - 38 - 86 (dock lane) = ~219 dp against the 238 dp hero band,
 * ~19 dp short, so the title band is squeezed from 48 to ~29 dp and the box clips where the dock lane starts (y=842 px, 320.8 dp,
 * in e7-07-ui.txt). The Thor's 468 dp leaves ~270 dp, 32 dp to spare. That is the solver's budget, not the line heights, and
 * these pins hold the line heights honest so a future clip is not blamed on them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShelfHeroTitleBandFitTest {

    @get:Rule
    val compose = createComposeRule()

    private fun assertBothLinesInsideTheBand(fontScale: Float) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 2.625f, fontScale = fontScale)) {
                MimirTheme {
                    Box(Modifier.fillMaxWidth().height(CoverflowTitleBandHeightDp.dp)) {
                        ShelfHeroTitle(title = "Game Boy", subtitle = "1 game")
                    }
                }
            }
        }
        compose.waitForIdle()

        val title = compose.onNodeWithText("Game Boy").getUnclippedBoundsInRoot()
        val subtitle = compose.onNodeWithText("1 game").getUnclippedBoundsInRoot()
        val band = CoverflowTitleBandHeightDp.toFloat()
        val tolerance = 0.5f
        assertTrue("title starts inside the band at scale $fontScale: ${title.top}", title.top.value >= -tolerance)
        assertTrue("count line follows the title at scale $fontScale", subtitle.top.value >= title.bottom.value - tolerance)
        assertTrue(
            "count line ends inside the 48 dp band at scale $fontScale: ${subtitle.bottom}",
            subtitle.bottom.value <= band + tolerance,
        )
    }

    @Test
    fun `title and count line sit inside the 48 dp band at the standard font scale`() = assertBothLinesInsideTheBand(1.0f)

    @Test
    fun `title and count line sit inside the 48 dp band at the raised font scale`() = assertBothLinesInsideTheBand(1.3f)
}

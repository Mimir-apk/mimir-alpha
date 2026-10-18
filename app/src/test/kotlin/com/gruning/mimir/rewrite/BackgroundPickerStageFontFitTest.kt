package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
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
 * Sweep 10-15, F7 (font 1.3): the Background picker's mini Library preview cut its "1 game" caption at the stage's bottom edge (`f13-30-bgpicker.png`; fine at 1.0). The title band under the tiles was
 * 38 dp whatever the font, and its two lines are sp (18 + 13 of line height): 31 dp of lines at 1.0, 40.3 dp at 1.3, centred in a band that was 2.3 dp short of them, so the count hung out of the bottom.
 * The band is now the larger of the shelf's rule and its two lines at the font scale, and the tiles take the rest.
 *
 * Renders the real [BackgroundPickerLiveStageChrome] at fonts 1.0 and 1.3 in the stages the picker is given (the Nova's, a 16:9 handheld's, a Main-wide one) and asserts both lines stand whole inside the band
 * and the band inside the stage. Pixel values are the JVM's; the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackgroundPickerStageFontFitTest {

    @get:Rule
    val compose = createComposeRule()

    private val shelf = listOf(
        PlatformRow(PlatformId("snes"), "Super Nintendo", 12),
        PlatformRow(PlatformId("gb"), "Game Boy", 1),
        PlatformRow(PlatformId("gba"), "Game Boy Advance", 7),
    )

    private var fontScale by mutableStateOf(1f)

    private fun mount(stageWidthDp: Int, stageHeightDp: Int) {
        val authority = DefaultFocusAuthority()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 2f, fontScale = fontScale)) {
                MimirTheme {
                    Box(Modifier.requiredSize(stageWidthDp.dp, stageHeightDp.dp).testTag(StageTag)) {
                        BackgroundPickerLiveStageChrome(authority = authority, platforms = shelf, gameCount = 20)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun fits(stageWidthDp: Int, stageHeightDp: Int) {
        mount(stageWidthDp, stageHeightDp)
        listOf(1.0f, 1.3f).forEach { font ->
            fontScale = font
            compose.waitForIdle()
            val stage = compose.onNodeWithTag(StageTag).fetchSemanticsNode().boundsInRoot
            val band = compose.onNodeWithTag(BackgroundStageTitleTestTag).fetchSemanticsNode().boundsInRoot
            val label = compose.onNodeWithText("Game Boy").fetchSemanticsNode().boundsInRoot
            val count = compose.onNodeWithText("1 game").fetchSemanticsNode().boundsInRoot
            println("STAGE-FIT ${stageWidthDp}x$stageHeightDp font=$font: stage bottom ${stage.bottom}, band ${band.top}..${band.bottom}, label ${label.top}..${label.bottom}, count ${count.top}..${count.bottom}")
            assertTrue("$stageWidthDp x $stageHeightDp font=$font: the label starts inside its band (${label.top} vs ${band.top})", label.top >= band.top - 0.5f)
            assertTrue("$stageWidthDp x $stageHeightDp font=$font: the count ends inside its band (${count.bottom} vs ${band.bottom})", count.bottom <= band.bottom + 0.5f)
            assertTrue("$stageWidthDp x $stageHeightDp font=$font: and the band inside the stage (${band.bottom} vs ${stage.bottom})", band.bottom <= stage.bottom + 0.5f)
            assertTrue("$stageWidthDp x $stageHeightDp font=$font: the two lines do not overlap", label.bottom <= count.top + 0.5f)
        }
    }

    @Test
    fun `the caption stands whole in the Nova's stage at fonts 1 and 1_3`() = fits(608, 208)

    @Test
    fun `the caption stands whole in a 16 to 9 handheld's stage at fonts 1 and 1_3`() = fits(699, 160)

    @Test
    fun `the caption stands whole in a Main-wide stage at fonts 1 and 1_3`() = fits(780, 230)

    @Test
    fun `the band is the shelf's rule at font 1 and its own two lines at font 1_3, and the tiles keep the rest`() {
        val rule = solveBackgroundStageGeometry(608f, 208f, 640f)
        assertEquals("font 1.0 is exactly the specified 38 dp band, unchanged", 38f, rule.titleBandDp, 0f)
        val big = solveBackgroundStageGeometry(608f, 208f, 640f, fontScale = 1.3f)
        assertEquals("its two lines at 1.3: 31 sp of line height scaled", 31f * 1.3f, big.titleBandDp, 0.01f)
        assertTrue("the band is no shorter than its lines", big.titleBandDp >= 31f * 1.3f)
        assertEquals("the tiles give up exactly what the band took", rule.artBandDp - (big.titleBandDp - rule.titleBandDp), big.artBandDp, 0.01f)
        assertTrue("and the seat is still a tile, never a speck", big.seatHeightDp >= 32f)
        // A font below 1 never shrinks the ruled band.
        assertEquals(38f, solveBackgroundStageGeometry(608f, 208f, 640f, fontScale = 0.85f).titleBandDp, 0f)
    }

    private companion object {
        const val StageTag = "stage-fit-stage"
    }
}

package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirLogoStage
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * R6-P2-09 red proof: at the base, the launch transition sizes its logo to a FRACTION of the Main
 * viewport (`widthIn(max = maxWidth * 0.44f)` / `heightIn(max = maxHeight * 0.30f)`,
 * `GameOpeningIntro.kt:487-488` before this ticket) rather than the one fixed stage the Now Playing
 * card already draws on (`MimirLogoStage`, 430 x 215 dp -- R6-P2-10 re-ruled it taller, 2:1; it was
 * 430 x 143.33 dp, 3:1, at R6-P2-09). See spec-R6-P2-09.md §8.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameOpeningIntroLogoStageTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() = GameOpeningIntroState.hide()

    @Test
    fun `the launch transition draws the logo on the one fixed stage, not on a fraction of the viewport`() {
        //  The URIs resolve to nothing on purpose: the box is the layout whether or not a
        // bitmap arrives, and no decode is needed to measure it.
        val plan = GameOpeningIntroPlan(
            gameIdValue = "stage-game",
            title = "Stage Game",
            heroUri = "file:///no-such-hero.png",
            logoUri = "content://mimir/no-such-logo.webp",
            audioUri = null,
        )
        GameOpeningIntroState.show(plan)
        compose.setContent {
            // Thor/Odin Main post-inset bounds (ShelfViewportEvidenceTest.kt:11): 832.5 x 444.4 dp.
            Box(Modifier.requiredSize(832.5.dp, 444.4.dp)) {
                MimirTheme { GameOpeningIntroOverlay() }
            }
        }
        compose.waitForIdle()
        val logo = compose.onNodeWithContentDescription("Stage Game")
        // Re-pinned against the token itself (policy 2026-09-15, R6-P2-10: a taller 2:1 stage,
        // 430 x 215 dp) rather than re-deriving the old 3:1 law -- MimirFlatLogoArtDefaults.AspectRatio
        // is the clear-logo FILE format's own ratio, a different law from the STAGE's shape, and this
        // test must not silently agree with a wrong stage just because it re-derives from the same
        // constant the production box used to draw from.
        logo.assertWidthIsEqualTo(MimirLogoStage.width)
        logo.assertHeightIsEqualTo(MimirLogoStage.height)
    }
}

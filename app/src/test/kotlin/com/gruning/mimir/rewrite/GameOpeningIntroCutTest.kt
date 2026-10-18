package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * walk 32g: a game that has taken the screen removes the launch intro with NO exit animation. Mimir is stopped then,
 * and Compose's frame clock is paused with it, so a `visible = false` flip would run the overlay's 400 ms fade-out when
 * Mimir RESUMES and the player would see the intro's tail on the way back. The overlay drops its whole layer from
 * composition on a cut ([GameOpeningIntroState.cutEpoch]); a plain hide still fades, as it always has.
 *
 * The clock is held (`autoAdvance = false`) so these read the frame right after the change and not the settled one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameOpeningIntroCutTest {
    @get:Rule
    val compose = createComposeRule()

    private val plan = GameOpeningIntroPlan(
        gameIdValue = "cut-game",
        title = "Cut Game",
        // Nothing decodes: the layer is what these read, not the picture.
        heroUri = "file:///no-such-hero.png",
        logoUri = null,
        audioUri = null,
    )

    @Before
    fun setUp() = GameOpeningIntroState.hide()

    @After
    fun tearDown() = GameOpeningIntroState.hide()

    private fun showIntroAndSettle() {
        compose.mainClock.autoAdvance = false
        compose.setContent { MimirTheme { GameOpeningIntroOverlay() } }
        GameOpeningIntroState.show(plan)
        publish()
        compose.mainClock.advanceTimeBy(100L)
        compose.onNodeWithTag(IntroTag).assertExists()
    }

    /** A state write made outside a frame reaches a held clock only once it is applied. */
    private fun publish() {
        Snapshot.sendApplyNotifications()
    }

    @Test
    fun `a removal without an exit takes the intro out of the very next frame`() {
        showIntroAndSettle()

        GameOpeningIntroState.hideIfCurrentWithoutExit(plan.gameIdValue)
        publish()
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithTag(IntroTag).assertDoesNotExist()
    }

    @Test
    fun `a plain hide still fades the intro out over its exit`() {
        showIntroAndSettle()

        GameOpeningIntroState.hide()
        publish()
        compose.mainClock.advanceTimeBy(GameOpeningIntroExitMs / 2L)
        compose.onNodeWithTag(IntroTag).assertExists()

        compose.mainClock.advanceTimeBy(GameOpeningIntroExitMs.toLong() + 100L)
        compose.onNodeWithTag(IntroTag).assertDoesNotExist()
    }

    @Test
    fun `a state written while the clock is held is drawn as gone at the first frame after it resumes`() {
        showIntroAndSettle()

        // Mimir stops: no frame runs. The cut is made while the clock is held, and only then does a frame arrive, which
        // is the first frame after the return.
        GameOpeningIntroState.hideIfCurrentWithoutExit(plan.gameIdValue)
        publish()
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithTag(IntroTag).assertDoesNotExist()
        // ...and it does not come back as the clock runs on.
        compose.mainClock.advanceTimeBy(GameOpeningIntroExitMs.toLong() + 100L)
        compose.onNodeWithTag(IntroTag).assertDoesNotExist()
    }

    @Test
    fun `the next intro after a cut shows normally`() {
        showIntroAndSettle()
        GameOpeningIntroState.hideIfCurrentWithoutExit(plan.gameIdValue)
        publish()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(IntroTag).assertDoesNotExist()

        GameOpeningIntroState.show(plan.copy(gameIdValue = "next-game"))
        publish()
        compose.mainClock.advanceTimeBy(100L)

        compose.onNodeWithTag(IntroTag).assertExists()
    }

    private companion object {
        const val IntroTag = "game-opening-intro"
    }
}

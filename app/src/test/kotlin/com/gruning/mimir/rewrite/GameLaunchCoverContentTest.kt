package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirLogoStage
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The launch cover's content. The cover is a second window drawing the intro's frame from a snapshot; if
 * its picture differed from the one under it, the handover would be a visible jump. It draws through the intro's own
 * [GameOpeningIntroFrame], so this pins the two places a difference could still come from: the geometry the frame lands on,
 * and the reveal, which must not happen on the first frame (the logo's painter lands a frame later).
 *
 * The URIs resolve to nothing on purpose: the layout is the same whether or not a bitmap arrives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameLaunchCoverContentTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() = GameOpeningIntroState.hide()

    private fun snapshot(logo: String? = "content://mimir/no-such-logo.webp") = GameLaunchCoverSnapshot(
        gameIdValue = "stage-game",
        title = "Stage Game",
        heroUri = "file:///no-such-hero.png",
        hero = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap(),
        logoUri = logo,
        frame = gameOpeningIntroFrameAt(3_000L),
    )

    @Test
    fun `the cover draws its logo on the one fixed stage, in the same place the intro draws it`() {
        val plan = GameOpeningIntroPlan(
            gameIdValue = "stage-game",
            title = "Stage Game",
            heroUri = "file:///no-such-hero.png",
            logoUri = "content://mimir/no-such-logo.webp",
            audioUri = null,
        )
        GameOpeningIntroState.show(plan)
        compose.setContent {
            // Thor/Odin Main post-inset bounds (ShelfViewportEvidenceTest.kt:11), twice: the intro, then the cover.
            Column {
                Box(Modifier.requiredSize(832.5.dp, 444.4.dp).testTag("intro-box")) {
                    MimirTheme { GameOpeningIntroOverlay() }
                }
                Box(Modifier.requiredSize(832.5.dp, 444.4.dp).testTag("cover-box")) {
                    MimirTheme {
                        GameLaunchCoverContent(snapshot(), gliding = false, glideMs = GameLaunchCoverGlideMs.toInt(), onRevealed = {})
                    }
                }
            }
        }
        compose.waitForIdle()

        val logos = compose.onAllNodesWithContentDescription("Stage Game")
        val bounds = logos.fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals("the intro's logo and the cover's", 2, bounds.size)

        val introBox = compose.onNodeWithTag("intro-box").fetchSemanticsNode().boundsInRoot
        val coverBox = compose.onNodeWithTag("cover-box").fetchSemanticsNode().boundsInRoot
        fun relative(logo: Rect, box: Rect) = Rect(logo.left - box.left, logo.top - box.top, logo.right - box.left, logo.bottom - box.top)
        assertEquals(
            "the cover's logo sits where the intro's does, relative to its own window",
            relative(bounds[0], introBox),
            relative(bounds[1], coverBox),
        )
        logos[1].assertWidthIsEqualTo(MimirLogoStage.width)
        logos[1].assertHeightIsEqualTo(MimirLogoStage.height)
    }

    @Test
    fun `with no logo the cover draws the title, as the intro does, over the whole window`() {
        compose.setContent {
            Box(Modifier.requiredSize(832.5.dp, 444.4.dp)) {
                MimirTheme { GameLaunchCoverContent(snapshot(logo = null), gliding = false, glideMs = GameLaunchCoverGlideMs.toInt(), onRevealed = {}) }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Stage Game").assertExists()
        compose.onNodeWithTag("game-launch-cover").assertWidthIsEqualTo(832.5.dp)
        compose.onNodeWithTag("game-launch-cover").assertHeightIsEqualTo(444.4.dp)
    }

    @Test
    fun `the cover does not reveal on its first frame, only once it has settled, and reveals once`() {
        var revealed = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.requiredSize(832.5.dp, 444.4.dp)) {
                MimirTheme { GameLaunchCoverContent(snapshot(), gliding = false, glideMs = GameLaunchCoverGlideMs.toInt(), onRevealed = { revealed += 1 }) }
            }
        }

        assertEquals("not at composition: the logo's painter has not landed", 0, revealed)
        compose.mainClock.advanceTimeByFrame()
        assertEquals("nor one frame in", 0, revealed)

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertEquals("settled, revealed, exactly once", 1, revealed)
    }

    // ---- the exit: the whole picture blends out, fading and drifting on the content's own clock

    private var gliding by mutableStateOf(false)

    /** The title is inside the moving layer, so its bounds in the root carry the layer's translation. */
    private fun coverLeftTop(): Pair<Float, Float> =
        compose.onNodeWithText("Stage Game").fetchSemanticsNode().boundsInRoot.let { it.left to it.top }

    /** The cover's own size in pixels, so the distances asked of the blend do not assume a density. */
    private fun coverSizePx(): Pair<Float, Float> =
        compose.onNodeWithTag("game-launch-cover").fetchSemanticsNode().size.let { it.width.toFloat() to it.height.toFloat() }

    private fun setGlidingCover(edge: GameLaunchCoverGlideEdge) {
        gliding = false
        compose.setContent {
            Box(Modifier.requiredSize(800.dp, 400.dp)) {
                MimirTheme {
                    GameLaunchCoverContent(
                        snapshot(logo = null),
                        gliding = gliding,
                        glideMs = GameLaunchCoverGlideMs.toInt(),
                        onRevealed = {},
                        edge = edge,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the cover drifts the named fraction of the window's width to the left, and only that far`() {
        setGlidingCover(GameLaunchCoverGlideExit)
        val (restLeft, restTop) = coverLeftTop()

        compose.runOnIdle { gliding = true }
        compose.waitForIdle()

        val (endLeft, endTop) = coverLeftTop()
        assertEquals(
            "a quarter of the window's width off to the left, not the whole of it",
            restLeft - coverSizePx().first * GameLaunchCoverGlideDriftFraction,
            endLeft,
            0.5f,
        )
        assertEquals("and not at all vertically", restTop, endTop, 0.5f)
    }

    @Test
    fun `mid-blend the picture is between rest and its drift's end, all of it moving as one`() {
        setGlidingCover(GameLaunchCoverGlideExit)
        val (restLeft, _) = coverLeftTop()
        val driftPx = coverSizePx().first * GameLaunchCoverGlideDriftFraction
        compose.mainClock.autoAdvance = false

        compose.runOnUiThread { gliding = true }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(GameLaunchCoverGlideMs / 2)

        val (midLeft, _) = coverLeftTop()
        assertTrue("it has left its place: $midLeft", midLeft < restLeft - 1f)
        assertTrue("and has not finished its drift: $midLeft", midLeft > restLeft - driftPx)
        // The whole frame is the one layer that moves: the cover node itself keeps its size.
        compose.onNodeWithTag("game-launch-cover").assertWidthIsEqualTo(800.dp)
    }

    @Test
    fun `the direction is the one constant's, so up drifts the picture along y by the fraction of the height and not along x`() {
        setGlidingCover(GameLaunchCoverGlideEdge.UP)
        val (restLeft, restTop) = coverLeftTop()

        compose.runOnIdle { gliding = true }
        compose.waitForIdle()

        val (endLeft, endTop) = coverLeftTop()
        assertEquals(restLeft, endLeft, 0.5f)
        assertEquals(restTop - coverSizePx().second * GameLaunchCoverGlideDriftFraction, endTop, 0.5f)
    }

    @Test
    fun `the settle is the two frames the KDoc promises`() {
        // A cover that revealed on frame zero would show the intro under it losing its logo for a frame.
        assertEquals(2, GameLaunchCoverSettleFrames)
    }
}

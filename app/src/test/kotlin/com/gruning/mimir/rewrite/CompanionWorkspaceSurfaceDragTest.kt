package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
 * Thor Bottom revamp, B3/B4 fix round: the Activity page's own drag seam, on the REAL [CompanionWorkspaceSurface] with real touch events (paused clock), and a takeover
 * control's claim on its own horizontal drag.
 *
 * A lift decides; a system cancel and a second finger do not. The cancel and a second finger settle the host back to where the drag began (its cancel callback) and
 * never reach its release, so the strip can never commit a page, or write a control receipt, for a gesture that was taken away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionWorkspaceSurfaceDragTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369

    private val deltas = mutableListOf<Float>()
    private var ends = 0
    private var endVelocity = 0f
    private var cancels = 0

    private fun mountActivity() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, 1f)) {
                MimirTheme {
                    Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp)) {
                        CompanionWorkspaceSurface(
                            page = CompanionWorkspacePage.ACTIVITY,
                            navigationEnabled = true,
                            recentApp = null,
                            game = null,
                            media = CompanionMediaUiState.NoActiveMedia,
                            onRecentApp = {},
                            onReturnToGame = null,
                            onMediaAccess = {},
                            onMediaAction = { _, _, _ -> },
                            bento = EmptyActivityBentoData,
                            onWorkspaceDrag = { d -> deltas += d; 0f },
                            onWorkspaceDragEnd = { v -> ends += 1; endVelocity = v; true },
                            onWorkspaceDragCancel = { cancels += 1 },
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    private fun frames(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    /** A left drag across most of the width, in the page's empty middle, then one more nudge so the last moves are delivered. */
    private fun androidx.compose.ui.test.SemanticsNodeInteraction.dragLeft() {
        performTouchInput {
            down(Offset(width * 0.85f, height * 0.5f))
            repeat(10) { moveBy(Offset(-width * 0.06f, 0f), delayMillis = 60) }
        }
        // Delivered as the paused clock reaches the moves' times (10 x 60 ms).
        frames(800)
        performTouchInput { moveBy(Offset(-1f, 0f), delayMillis = 16) }
        frames(100)
    }

    @Test
    fun `a left drag reaches the seam and a lift asks the host to settle`() {
        mountActivity()
        val root = compose.onRoot()
        root.dragLeft()
        assertTrue("the host was offered the finger leftward: ${deltas.sum()}", deltas.sum() < -hostWidthDp * density369 * 0.4f)
        assertTrue(deltas.all { it < 0f })
        root.performTouchInput { up() }
        frames(200)
        assertEquals("a lift is the release", 1, ends)
        assertEquals(0, cancels)
    }

    @Test
    fun `a system cancel past half settles the host back and never asks it to switch`() {
        mountActivity()
        val root = compose.onRoot()
        root.dragLeft()
        assertTrue("fixture: past half", deltas.sum() < -hostWidthDp * density369 * 0.5f)
        root.performTouchInput { cancel() }
        frames(300)
        assertEquals("the host was told to settle back", 1, cancels)
        assertEquals("and was never asked to switch", 0, ends)
    }

    @Test
    fun `a second finger mid-drag cancels the host at once and the rest of the gesture is ignored`() {
        mountActivity()
        val root = compose.onRoot()
        root.dragLeft()
        val before = deltas.size
        root.performTouchInput { down(1, Offset(width * 0.2f, height * 0.3f)) }
        frames(100)
        assertEquals("cancelled at the second finger's touch", 1, cancels)
        root.performTouchInput { moveBy(0, Offset(-width * 0.1f, 0f), delayMillis = 60) }
        frames(100)
        assertEquals("nothing more is offered", before, deltas.size)
        root.performTouchInput { up(0); up(1) }
        frames(200)
        assertEquals("and the lift asks nothing", 0, ends)
        assertEquals("one cancel, no more", 1, cancels)
    }

    // ---------------------------------------------------------------------------------------------------------------------------------
    // A takeover control's claim on its own horizontal drag (item 5 of the fix round).
    // ---------------------------------------------------------------------------------------------------------------------------------

    private var parentDeltas = 0f
    private var taps = 0

    private fun mountClaim(claim: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val state = rememberDraggableState { parentDeltas += it }
            Box(Modifier.requiredSize(400.dp, 300.dp).draggable(state, Orientation.Horizontal)) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(120.dp, 48.dp)
                        .testTag("pill")
                        .then(if (claim) Modifier.companionOwnsHorizontalDrag() else Modifier)
                        .clickable { taps += 1 },
                )
            }
        }
        compose.mainClock.advanceTimeBy(300L)
    }

    private fun dragFrom(tag: String?, dx: Float) {
        val node = if (tag != null) compose.onNodeWithTag(tag) else compose.onRoot()
        node.performTouchInput {
            down(if (tag != null) center else Offset(20f, 20f))
            repeat(6) { moveBy(Offset(dx / 6f, 0f), delayMillis = 30) }
            up()
        }
        frames(100)
    }

    @Test
    fun `a horizontal drag that starts on a takeover control does not move the workspace, and it is not a tap`() {
        mountClaim(claim = true)
        dragFrom("pill", -250f)
        assertEquals("the strip behind it never saw the drag", 0f, parentDeltas, 0f)
        assertEquals("and the control was not activated by it", 0, taps)
    }

    @Test
    fun `without the claim the same drag pans the workspace (the red case)`() {
        mountClaim(claim = false)
        dragFrom("pill", -250f)
        assertTrue("the parent took the drag: $parentDeltas", parentDeltas < -100f)
    }

    @Test
    fun `a drag that starts beside the control still moves the workspace, and a tap on the control still activates it`() {
        mountClaim(claim = true)
        dragFrom(null, 250f)
        assertTrue("the workspace followed a drag that began elsewhere: $parentDeltas", parentDeltas > 100f)
        compose.onNodeWithTag("pill").performTouchInput { click() }
        frames(100)
        assertEquals("a tap on the control activates it", 1, taps)
    }

    private val scrollState = androidx.compose.foundation.ScrollState(0)

    private fun mountClaimInScroll() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val state = rememberDraggableState { parentDeltas += it }
            Box(Modifier.requiredSize(400.dp, 300.dp).verticalScroll(scrollState).draggable(state, Orientation.Horizontal)) {
                androidx.compose.foundation.layout.Column(Modifier.size(400.dp, 1200.dp)) {
                    androidx.compose.foundation.layout.Spacer(Modifier.size(400.dp, 100.dp))
                    Box(
                        Modifier
                            .size(120.dp, 48.dp)
                            .testTag("pill")
                            .companionOwnsHorizontalDrag()
                            .clickable { taps += 1 },
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(300L)
    }

    /**
     * Drag-claim boundary: the claim is decided once, at touch slop. A drag that starts on the control and goes VERTICAL first is the page's scroll; a later sideways
     * drift must not take it over and cancel the scroll under way.
     */
    @Test
    fun `a drag from a control that goes vertical first and then drifts sideways keeps scrolling the page`() {
        mountClaimInScroll()
        // Injected through the root with the pill's start position taken once: the injection keeps the finger in the tagged node's own coordinates, and this node scrolls.
        val node = compose.onRoot()
        val start = compose.onNodeWithTag("pill").fetchSemanticsNode().boundsInRoot.center
        node.performTouchInput {
            down(start)
            repeat(4) { moveBy(Offset(0f, -60f), delayMillis = 30) }
        }
        frames(400)
        val afterVertical = scrollState.value
        assertTrue("fixture: the page scrolled under the vertical drag: $afterVertical", afterVertical > 50)
        node.performTouchInput {
            // Drifts sideways while still going on up the page.
            repeat(6) { moveBy(Offset(40f, -40f), delayMillis = 30) }
            up()
        }
        frames(600)
        assertTrue("the scroll was not cancelled by the drift: $afterVertical then ${scrollState.value}", scrollState.value > afterVertical + 100)
        assertEquals("and the workspace never moved", 0f, parentDeltas, 0f)
        assertEquals("and it was no tap", 0, taps)
    }

    @Test
    fun `a horizontal-first drag from a control still never pages the workspace, even when it then drifts vertically`() {
        mountClaimInScroll()
        val start = compose.onNodeWithTag("pill").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput {
            down(start)
            repeat(5) { moveBy(Offset(-50f, 0f), delayMillis = 30) }
            repeat(4) { moveBy(Offset(-10f, -60f), delayMillis = 30) }
            up()
        }
        frames(800)
        assertEquals("the workspace behind it never saw the drag", 0f, parentDeltas, 0f)
        assertEquals("the page did not scroll under a claimed drag", 0, scrollState.value)
        assertEquals(0, taps)
    }

    @Test
    fun `a tap on a control inside a scrolling page still activates it`() {
        mountClaimInScroll()
        compose.onNodeWithTag("pill").performTouchInput { click() }
        frames(200)
        assertEquals(1, taps)
    }

    @Test
    fun `every takeover control wears the claim`() {
        val source = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionActivityGameDetail.kt").readText()
        assertEquals("the back pill, the warm action pill (Return, a confirming Quit), the etched action chip (Quit) and the achievements chip", 4, Regex("[ ]+[.]companionOwnsHorizontalDrag[(][)]").findAll(source).count())
    }
}

package com.gruning.mimir.rewrite.feature.companion

import android.app.Application
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.CompanionHomeLane
import com.gruning.mimir.rewrite.core.model.CompanionHomeTile
import com.gruning.mimir.rewrite.core.model.CompanionHomeTileKind
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat
import com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * Thor Bottom revamp B3: the lattice's pages follow the finger, on the REAL [CompanionSurface] at the Thor Bottom's real size (537.7 x 468.3 dp: the Presentation draws the full 1080 px, not the 445 dp window; ledger D18/D19), on a
 * paused clock (autoAdvance off) with real touch events.
 *
 * The pager's position is read from where its own slots are (`companionSlotTag`), and the page from the diamonds' description. The settle rule is
 * core-ui's `mimirPagerSettlePage` (its table is `MimirPagerSettleTest`); this is that rule on the real pager: a slow drag follows the finger and
 * lands back on the start page, a fling turns exactly one page, the last page stops at its edge (no wrap), and at page 0's left edge the finger's
 * remainder goes to the workspace seam while the pager stays pinned.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionLatticePagerGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private val widthDp = 1240f / Density369
    private val heightDp = 1080f / Density369
    private val tiles = (1..19).map { CompanionHomeTile("p$it", CompanionHomeTileKind.PLATFORM, "Platform number $it") }
    private val payload = CompanionPayload(
        mode = CompanionMode.HOME,
        statsLabel = "19 platforms",
        homeLanes = listOf(CompanionHomeLane(id = "emulation", title = "Emulation", hint = "", tiles = tiles)),
    )

    /** What the workspace seam was offered, in finger px (positive rightward), and how the release was answered. */
    private val offered = mutableListOf<Float>()
    private var ends = 0
    private var cancels = 0
    private var density = 0f
    private var covered by mutableStateOf(false)

    private fun mount(restoredPage: Int, seam: Boolean = true, hint: Boolean = false) {
        ShadowLog.clear()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(Density369, 1f), LocalBottomUiDisplay provides DISPLAY) {
                MimirTheme {
                    density = LocalDensity.current.density
                    CompanionSurface(
                        payload = payload,
                        cursorId = "p1",
                        restoredPage = restoredPage,
                        // The seam takes everything it is offered, as a host with a strip would, so the pager pins at page 0.
                        onWorkspaceDrag = if (seam) ({ delta: Float -> offered += delta; 0f }) else null,
                        onWorkspaceDragEnd = if (seam) ({ _: Float -> ends += 1; offered.any { it > 0f } }) else null,
                        onWorkspaceDragCancel = if (seam) ({ cancels += 1 }) else null,
                        showActivitySwipeHint = hint,
                        hostCovered = covered,
                        // The lift's long press reads this clock; it is the paused compose clock, so a held finger lifts when the test says.
                        monotonicUptimeMillis = { compose.mainClock.currentTime },
                        modifier = Modifier.requiredSize(widthDp.dp, heightDp.dp),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000L)
        compose.waitForIdle()
    }

    private fun page(): String =
        compose.onNodeWithTag(CompanionPageRunesTag, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription].single()

    /** Where page [p]'s first slot is, left edge in px from the root (its position, not its bounds, which are clipped to the pager once it is off-screen). */
    private fun slotLeft(p: Int): Float =
        compose.onNodeWithTag(companionSlotTag(p * 9), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x

    private fun width(): Float = widthDp * density

    private fun frames(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun receiptLines(name: String): List<String> =
        ShadowLog.getLogsForTag(BottomUiReceiptFormat.TAG).map { it.msg }.filter { "display=$DISPLAY" in it && "name=$name " in it + " " }

    private fun centre(): Offset = Offset(width() / 2f, heightDp * density / 2f)

    @Test
    fun `a slow drag of 40 percent follows the finger, and lands back on the start page`() {
        mount(restoredPage = 1)
        assertEquals("Page 2 of 3", page())
        val rest = slotLeft(1)
        val drag = width() * 0.4f
        val root = compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true)
        root.performTouchInput {
            down(centre())
            // A slow, steady move: well under the fling threshold at the release.
            repeat(5) { moveBy(Offset(drag / 10f, 0f), delayMillis = 60) }
        }
        // Held: the pages are where the finger put them, mid-drag (the pager lays out on frames, so the clock moves between the touches).
        frames(100)
        val half = slotLeft(1) - rest
        root.performTouchInput { repeat(5) { moveBy(Offset(drag / 10f, 0f), delayMillis = 60) } }
        frames(100)
        val full = slotLeft(1) - rest
        // The touch slop is the finger's first few px before a drag starts, so the pager is that far behind it; every px after is 1:1.
        assertEquals("the second half of the finger's travel moved the page by the same: $half then $full", drag / 2f, full - half, 2f)
        assertTrue("the page is under the finger, a touch slop behind at most: moved ${full}px of $drag", full in drag * 0.85f..drag)
        root.performTouchInput { up() }
        frames(400)
        assertEquals("a slow 40% drag settles on the page it began on", "Page 2 of 3", page())
        assertEquals("seated", rest, slotLeft(1), 1f)
        assertTrue("one settle receipt, begun and ended at rest: ${receiptLines("lattice.page")}", receiptLines("lattice.page").let { l ->
            l.any { it.startsWith("ev=transition.begin") } && l.any { it.startsWith("ev=transition.end") && it.endsWith("result=done") }
        })
    }

    @Test
    fun `a fling turns exactly one page, in its direction`() {
        mount(restoredPage = 1)
        compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true).performTouchInput {
            // A long, fast left swipe: more than a page of travel, which still turns one.
            swipeWithVelocity(Offset(width * 0.9f, centerY), Offset(width * 0.05f, centerY), endVelocity = 4_000f, durationMillis = 120)
        }
        frames(500)
        assertEquals("a left fling brings the next page, and only one", "Page 3 of 3", page())

        compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true).performTouchInput {
            swipeWithVelocity(Offset(width * 0.05f, centerY), Offset(width * 0.95f, centerY), endVelocity = 4_000f, durationMillis = 120)
        }
        frames(500)
        assertEquals("a right fling goes back one", "Page 2 of 3", page())
    }

    @Test
    fun `a left drag on the last page stops at its edge and does not wrap`() {
        mount(restoredPage = 2)
        assertEquals("Page 3 of 3", page())
        val rest = slotLeft(2)
        compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true).performTouchInput {
            swipeWithVelocity(Offset(width * 0.9f, centerY), Offset(width * 0.05f, centerY), endVelocity = 4_000f, durationMillis = 120)
        }
        frames(500)
        assertEquals("the last page is still the last page", "Page 3 of 3", page())
        assertEquals("and has not moved", rest, slotLeft(2), 1f)
        assertTrue("nothing was offered to the workspace: $offered", offered.isEmpty())
    }

    @Test
    fun `a right drag at page 0 offers its remainder to the workspace seam and the pager stays pinned`() {
        mount(restoredPage = 0)
        val rest = slotLeft(0)
        val drag = width() * 0.3f
        val root = compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true)
        root.performTouchInput {
            down(centre())
            repeat(10) { moveBy(Offset(drag / 10f, 0f), delayMillis = 60) }
        }
        frames(100)
        assertEquals("the pager did not move: page 0 stays at its seat", rest, slotLeft(0), 1f)
        root.performTouchInput { up() }
        frames(400)
        val total = offered.sum()
        assertTrue("the seam was offered the finger's travel, about $drag px: $total", total in drag * 0.8f..drag * 1.05f)
        assertTrue("every offer was rightward", offered.all { it > 0f })
        assertEquals("the release asked the seam", 1, ends)
        assertEquals("page 0 stayed page 0", "Page 1 of 3", page())
        assertEquals(rest, slotLeft(0), 1f)
        assertFalse("the seam owned the release, so the pager made no settle of its own: ${receiptLines("lattice.page")}", receiptLines("lattice.page").isNotEmpty())
    }

    @Test
    fun `a lifted tile blocks the pager, the finger carries the tile and the page does not turn`() {
        mount(restoredPage = 1)
        val rest = slotLeft(1)
        val root = compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true)
        // Press a tile of page 1 and hold it past the long press: it lifts.
        val slot = compose.onNodeWithTag(companionSlotTag(9 + 4), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        root.performTouchInput { down(slot) }
        frames(900)
        assertEquals("fixture: a tile is in the air", 1, compose.onAllNodesWithTag(companionLiftTag("p14"), useUnmergedTree = true).fetchSemanticsNodes().size)
        // Carry it a page's width to the left, away from every edge dwell: the pager still does not follow the finger.
        root.performTouchInput { repeat(6) { moveBy(Offset(-width * 0.12f, 0f), delayMillis = 40) } }
        frames(100)
        assertEquals("the page has not moved under the lifted tile's finger", rest, slotLeft(1), 1f)
        root.performTouchInput { up() }
        frames(600)
        assertEquals("and no page turned from the release", "Page 2 of 3", page())
        assertTrue("no pager settle was made: ${receiptLines("lattice.page")}", receiptLines("lattice.page").isEmpty())
    }

    @Test
    fun `with no workspace seam, a right drag at page 0 goes nowhere`() {
        mount(restoredPage = 0, seam = false)
        val rest = slotLeft(0)
        compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true).performTouchInput {
            swipeWithVelocity(Offset(width * 0.05f, centerY), Offset(width * 0.95f, centerY), endVelocity = 4_000f, durationMillis = 120)
        }
        frames(500)
        assertEquals("Page 1 of 3", page())
        assertEquals(rest, slotLeft(0), 1f)
    }

    /** The lattice's own cancel semantics: a gesture the system took away, or a second finger touched, decides nothing. */
    @Test
    fun `a system cancel past half lands back on the start page and writes no receipt`() {
        mount(restoredPage = 1)
        val rest = slotLeft(1)
        val root = compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true)
        // 60% of the width to the left: past half, so a LIFT here would turn to the next page.
        root.performTouchInput {
            down(centre())
            repeat(10) { moveBy(Offset(-width * 0.06f, 0f), delayMillis = 60) }
        }
        // The injected moves are delivered as the paused clock reaches their times (10 x 60 ms), so the clock is run past them before anything is read.
        frames(800)
        root.performTouchInput { moveBy(Offset(-1f, 0f), delayMillis = 16) }
        frames(100)
        assertTrue("fixture: the page followed the finger well past half: ${rest - slotLeft(1)} of ${width()}", rest - slotLeft(1) > width() * 0.5f)
        root.performTouchInput { cancel() }
        frames(600)
        assertEquals("a cancel past half lands on the page it began on", "Page 2 of 3", page())
        assertEquals("seated", rest, slotLeft(1), 1f)
        assertTrue("and no settle receipt was written: ${receiptLines("lattice.page")}", receiptLines("lattice.page").isEmpty())
    }

    @Test
    fun `a second finger mid-drag rolls the pager back to the start page, and the rest of the gesture is ignored`() {
        mount(restoredPage = 1)
        val rest = slotLeft(1)
        val root = compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true)
        root.performTouchInput {
            down(centre())
            repeat(10) { moveBy(Offset(-width * 0.06f, 0f), delayMillis = 60) }
        }
        // The injected moves are delivered as the paused clock reaches their times (10 x 60 ms), so the clock is run past them before anything is read.
        frames(800)
        root.performTouchInput { moveBy(Offset(-1f, 0f), delayMillis = 16) }
        frames(100)
        assertTrue("fixture: the page followed the finger: ${rest - slotLeft(1)}", rest - slotLeft(1) > width() * 0.3f)
        root.performTouchInput { down(1, Offset(width * 0.2f, centre().y)) }
        frames(600)
        assertEquals("the second finger took the gesture: back on the start page", rest, slotLeft(1), 1f)
        // The first finger keeps moving and lifts: nothing follows it and nothing turns.
        root.performTouchInput { moveBy(0, Offset(-width * 0.2f, 0f), delayMillis = 60) }
        frames(100)
        assertEquals("what is left of the gesture is ignored", rest, slotLeft(1), 1f)
        root.performTouchInput { up(0); up(1) }
        frames(600)
        assertEquals("Page 2 of 3", page())
        assertEquals(rest, slotLeft(1), 1f)
        assertTrue("no receipt: ${receiptLines("lattice.page")}", receiptLines("lattice.page").isEmpty())
    }

    /** The footer is outside the pager's own hit path: a second finger there is seen only by the surface's root watch. */
    @Test
    fun `a second finger on the footer mid-drag rolls the pager back and no receipt is written`() {
        mount(restoredPage = 1, hint = true)
        val rest = slotLeft(1)
        val root = compose.onRoot(useUnmergedTree = true)
        root.performTouchInput {
            down(centre())
            repeat(10) { moveBy(Offset(-width * 0.06f, 0f), delayMillis = 60) }
        }
        frames(800)
        root.performTouchInput { moveBy(Offset(-1f, 0f), delayMillis = 16) }
        frames(100)
        assertTrue("fixture: the page followed the finger: ${rest - slotLeft(1)}", rest - slotLeft(1) > width() * 0.3f)
        // On the swipe hint's footer row, which sits under the pager and outside its hit path.
        val footer = compose.onNodeWithTag(CompanionFooterTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        root.performTouchInput { down(1, footer) }
        frames(600)
        assertEquals("back on the start page", rest, slotLeft(1), 1f)
        root.performTouchInput { up(0); up(1) }
        frames(600)
        assertEquals("Page 2 of 3", page())
        assertTrue("no receipt: ${receiptLines("lattice.page")}", receiptLines("lattice.page").isEmpty())
    }

    @Test
    fun `a second finger on the footer while the host holds the finger cancels the host`() {
        mount(restoredPage = 0, hint = true)
        val root = compose.onRoot(useUnmergedTree = true)
        root.performTouchInput {
            down(centre())
            repeat(10) { moveBy(Offset(width * 0.06f, 0f), delayMillis = 60) }
        }
        frames(800)
        root.performTouchInput { moveBy(Offset(1f, 0f), delayMillis = 16) }
        frames(100)
        assertTrue("fixture: the host holds the finger", offered.sum() > width() * 0.4f)
        val footer = compose.onNodeWithTag(CompanionFooterTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        root.performTouchInput { down(1, footer) }
        frames(100)
        assertTrue("cancelled by a finger the pager never saw: $cancels", cancels >= 1)
        root.performTouchInput { up(0); up(1) }
        frames(300)
        assertEquals("and the lift asks nothing", 0, ends)
    }

    @Test
    fun `a system cancel while the host holds the finger cancels the host and never asks it to switch`() {
        mount(restoredPage = 0)
        val root = compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true)
        root.performTouchInput {
            down(centre())
            repeat(10) { moveBy(Offset(width * 0.06f, 0f), delayMillis = 60) }
        }
        frames(800)
        root.performTouchInput { moveBy(Offset(1f, 0f), delayMillis = 16) }
        frames(100)
        assertTrue("fixture: the host was offered well past half: ${offered.sum()} of ${width()}", offered.sum() > width() * 0.4f)
        root.performTouchInput { cancel() }
        frames(600)
        assertEquals("the host was told to settle back", 1, cancels)
        assertEquals("and was never asked to switch", 0, ends)
        assertEquals("Page 1 of 3", page())
    }

    @Test
    fun `a second finger while the host holds the finger cancels the host at once`() {
        mount(restoredPage = 0)
        val root = compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true)
        root.performTouchInput {
            down(centre())
            repeat(10) { moveBy(Offset(width * 0.06f, 0f), delayMillis = 60) }
        }
        frames(800)
        root.performTouchInput { moveBy(Offset(1f, 0f), delayMillis = 16) }
        frames(100)
        assertTrue("fixture: the host holds the finger: ${offered.sum()}", offered.sum() > width() * 0.4f)
        root.performTouchInput { down(1, Offset(width * 0.2f, centre().y)) }
        frames(100)
        assertEquals("cancelled at the second finger's touch", 1, cancels)
        val offeredAfter = offered.size
        root.performTouchInput { moveBy(0, Offset(width * 0.1f, 0f), delayMillis = 60) }
        frames(100)
        assertEquals("nothing more is offered to the host in that gesture", offeredAfter, offered.size)
        root.performTouchInput { up(0); up(1) }
        frames(300)
        assertEquals("and the release asks nothing", 0, ends)
        assertEquals("one cancel, no more", 1, cancels)
    }

    @Test
    fun `a cover that opens over a held swipe cancels the host and the lift that follows switches nothing`() {
        mount(restoredPage = 0)
        val root = compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true)
        root.performTouchInput {
            down(centre())
            repeat(10) { moveBy(Offset(width * 0.06f, 0f), delayMillis = 60) }
        }
        frames(800)
        root.performTouchInput { moveBy(Offset(1f, 0f), delayMillis = 16) }
        frames(100)
        assertTrue("fixture: the host holds the finger", offered.sum() > width() * 0.4f)
        covered = true
        Snapshot.sendApplyNotifications()
        frames(300)
        assertTrue("the shelf opening took the strip back: $cancels cancels", cancels >= 1)
        root.performTouchInput { up() }
        frames(300)
        assertEquals("and the release did not commit", 0, ends)
    }

    private companion object {
        const val Density369 = 369f / 160f
        const val DISPLAY = 4
    }
}

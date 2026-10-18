package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Ledger D27: a settle that lands on Activity re-reads EVERY cover that is up, not only the platform shelf.
 *
 * Before, only the shelf was read at the landing ([companionLivePlatformShelfDestination]); the other covers (the recents drawer, a chooser, the picker,
 * the Tweaker, the Bottom Quick launch window) relied on the cancel their opening fires. That cancel reaches a drag that is still held. After the finger
 * has lifted the settle runs on its own for a while, and a cover can open in that time, or without touching the page at all (a chooser a launch raises),
 * and fires no cancel the strip would hear. So the landing asks: [companionLiveWorkspaceCoverUp], the same function the page's touch gate is built from.
 *
 * The composed tests run the REAL runner and the REAL strip: the finger drags to Activity and lets go, the settle is on its way, a cover opens WITHOUT
 * `cancel()` or `abandon()` being called (the cover's own state is all that changes), and the settle runs out. Nothing may commit, and the strip rests
 * on the lattice. A run with no cover still commits, so it is the cover that stopped the other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionWorkspaceCoverLandingTest {

    @get:Rule
    val compose = createComposeRule()

    private val width = 1000f
    private val fling = 800f
    private val strip = CompanionWorkspaceStrip().also { it.widthPx = width }
    private val commits = mutableListOf<CompanionWorkspaceSettle>()
    private val renderId = UUID.randomUUID()
    private var pickerOpen = false
    private var bottomQuickLaunchCovering = false

    /** The covers are process state, shared by every test in this JVM: start and end with none up. */
    @Before
    @After
    fun closeEveryCover() {
        CompanionGameDisplayChoiceState.pending.value?.let(CompanionGameDisplayChoiceState::dismiss)
        CompanionQuickLaunchChoiceState.clear()
        CompanionLibraryRoute.backToLattice()
    }

    /** The Presentation's shape: the runner, handed the live cover read the Presentation hands it. */
    @Composable
    private fun Root() {
        CompanionWorkspaceSettleRunner(
            strip = strip,
            receiptDisplayId = BottomUiReceiptFormat.NO_DISPLAY,
            coverUp = { companionLiveWorkspaceCoverUp(renderId, pickerOpen, bottomQuickLaunchCovering) },
            commit = { commits += it },
        )
    }

    private fun frames(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    /**
     * A swipe to Activity past half the page, let go, and a few frames into the settle; then [openCover] runs (it changes only the cover's own state; null
     * opens none), and the settle runs out. The host then shows the page the settle left it on: the lattice when a cover stopped the commit, else Activity.
     */
    private fun swipeToActivityWithCoverOpenedMidSettle(openCover: (() -> Unit)?) {
        compose.mainClock.autoAdvance = false
        compose.setContent { Root() }
        compose.mainClock.advanceTimeBy(100L)
        compose.waitForIdle()

        strip.drag(CompanionWorkspacePage.SHELF, width * 0.6f)
        Snapshot.sendApplyNotifications()
        frames(16)
        assertTrue("fixture: the strip is off its rest", strip.active)
        val settle = strip.release(0f, fling)
        assertNotNull("fixture: the release begins a settle", settle)
        assertEquals("fixture: bound for Activity", CompanionWorkspacePage.ACTIVITY, settle!!.target)
        Snapshot.sendApplyNotifications()
        frames(16)
        frames(16)
        assertTrue("fixture: the settle is on its way, not landed", strip.settling)
        assertEquals("fixture: nothing has committed yet", 0, commits.size)
        assertFalse("fixture: no cover is up as the settle begins", companionLiveWorkspaceCoverUp(renderId, pickerOpen, bottomQuickLaunchCovering))

        if (openCover != null) {
            openCover()
            assertTrue("the cover is up, as the landing will read it", companionLiveWorkspaceCoverUp(renderId, pickerOpen, bottomQuickLaunchCovering))
            assertTrue("and the settle was not cancelled or abandoned: it is still on its way", strip.settling)
        }

        repeat(30) { frames(16) }
        strip.reconcile(if (openCover != null) CompanionWorkspacePage.SHELF else CompanionWorkspacePage.ACTIVITY)
        Snapshot.sendApplyNotifications()
        frames(100)
    }

    private fun assertSettledBackWithNoSwitch() {
        assertEquals("the settle landed over a cover: nothing commits", 0, commits.size)
        assertFalse("and the strip is at rest on the lattice it began on", strip.active)
        assertFalse(strip.carried)
        assertFalse(strip.settling)
    }

    @Test
    fun `a game-display chooser raised during the settle, with no cancel, stops the landing committing`() {
        swipeToActivityWithCoverOpenedMidSettle { CompanionGameDisplayChoiceState.request(GameId("g1"), "Sonic the Hedgehog 2", editing = false) }
        assertSettledBackWithNoSwitch()
    }

    @Test
    fun `a quick-launch choice raised during the settle, with no cancel, stops the landing committing`() {
        swipeToActivityWithCoverOpenedMidSettle { assertTrue(CompanionQuickLaunchChoiceState.request("example.player", "Player")) }
        assertSettledBackWithNoSwitch()
    }

    @Test
    fun `the picker opened during the settle, with no cancel, stops the landing committing`() {
        swipeToActivityWithCoverOpenedMidSettle { pickerOpen = true }
        assertSettledBackWithNoSwitch()
    }

    @Test
    fun `the Bottom Quick launch window covering during the settle, with no cancel, stops the landing committing`() {
        swipeToActivityWithCoverOpenedMidSettle { bottomQuickLaunchCovering = true }
        assertSettledBackWithNoSwitch()
    }

    @Test
    fun `with no cover opened the same swipe commits to Activity once`() {
        swipeToActivityWithCoverOpenedMidSettle(null)
        assertEquals("the settle landed on Activity and committed once", 1, commits.size)
        assertEquals(CompanionWorkspacePage.ACTIVITY, commits.single().target)
    }

    @Test
    fun `each cover alone is a cover, and none is none`() {
        assertFalse(companionWorkspaceCoverUp(false, false, false, false, false, false))
        assertTrue("the drawer on this window", companionWorkspaceCoverUp(true, false, false, false, false, false))
        assertTrue("the Bottom Quick launch window", companionWorkspaceCoverUp(false, true, false, false, false, false))
        assertTrue("the Tweaker on the Bottom", companionWorkspaceCoverUp(false, false, true, false, false, false))
        assertTrue("the picker", companionWorkspaceCoverUp(false, false, false, true, false, false))
        assertTrue("a quick-launch choice", companionWorkspaceCoverUp(false, false, false, false, true, false))
        assertTrue("a game-display choice", companionWorkspaceCoverUp(false, false, false, false, false, true))
    }

    @Test
    fun `the live read follows the process state each cover is drawn from, and lets go when it closes`() {
        assertFalse(companionLiveWorkspaceCoverUp(renderId, quickPickerOpen = false, bottomQuickLaunchCovering = false))

        CompanionGameDisplayChoiceState.request(GameId("g1"), "Sonic the Hedgehog 2", editing = false)
        assertTrue(companionLiveWorkspaceCoverUp(renderId, quickPickerOpen = false, bottomQuickLaunchCovering = false))
        CompanionGameDisplayChoiceState.dismiss(CompanionGameDisplayChoiceState.pending.value!!)
        assertFalse("a dismissed chooser is no cover", companionLiveWorkspaceCoverUp(renderId, quickPickerOpen = false, bottomQuickLaunchCovering = false))

        assertTrue(CompanionQuickLaunchChoiceState.request("example.player", "Player"))
        assertTrue(companionLiveWorkspaceCoverUp(renderId, quickPickerOpen = false, bottomQuickLaunchCovering = false))
        CompanionQuickLaunchChoiceState.clear()
        assertFalse(companionLiveWorkspaceCoverUp(renderId, quickPickerOpen = false, bottomQuickLaunchCovering = false))

        assertTrue(companionLiveWorkspaceCoverUp(renderId, quickPickerOpen = true, bottomQuickLaunchCovering = false))
        assertTrue(companionLiveWorkspaceCoverUp(renderId, quickPickerOpen = false, bottomQuickLaunchCovering = true))
    }

    /**
     * The Presentation cannot be composed on the JVM, so its wiring is pinned as source: the runner is handed the live read, the touch gate is built from the
     * same cover function, and the live read takes the drawer, the Tweaker and both choices off their process state with `.value`, not off what was composed.
     */
    @Test
    fun `the Presentation hands the runner the live cover read, and the touch gate is built from the same function`() {
        val presentation = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt").readText()
        assertTrue(
            "the landing reads every cover live",
            presentation.contains(
                "coverUp = { companionLiveWorkspaceCoverUp(renderOperation.value, quickPickerOpen, " +
                    "CompanionProcessRuntime.services(context).bottomQuickLaunchPanel.covering.value) },",
            ),
        )
        assertEquals("the shelf alone is no longer the whole read", 0, Regex("coverUp = \\{ companionLivePlatformShelfDestination\\(\\) != null \\}").findAll(presentation).count())
        assertTrue(
            "the page takes no touch under the covers of the one function",
            presentation.contains("val workspaceNavigationBase = !companionWorkspaceCoverUp("),
        )
        assertEquals(
            "the picker's state is declared before the runner reads it",
            true,
            presentation.indexOf("var quickPickerOpen by remember") in 0 until presentation.indexOf("CompanionWorkspaceSettleRunner("),
        )
        assertEquals("and once", 1, Regex("var quickPickerOpen by remember").findAll(presentation).count())

        val runner = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionWorkspaceSettleRunner.kt").readText()
        val live = runner.substringAfter("internal fun companionLiveWorkspaceCoverUp(")
        listOf(
            "companionLivePlatformShelfDestination() != null",
            "CompanionRecentsState.ui.value.isVisibleOnPresentation(renderOperationId)",
            "CompanionProcessRuntime.sharedBrightness.value",
            "CompanionQuickLaunchChoiceState.pending.value != null",
            "CompanionGameDisplayChoiceState.pending.value != null",
        ).forEach { read -> assertTrue("the live read has $read", live.contains(read)) }
    }
}

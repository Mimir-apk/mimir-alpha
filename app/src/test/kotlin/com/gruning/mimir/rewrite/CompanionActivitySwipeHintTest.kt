package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * B6 of the Thor Bottom revamp: the lattice footer's "Swipe right for Activity" hint retires for good at the first committed lattice -> Activity switch.
 *
 * The store's half is a round trip through its own prefs file. The Presentation's half is the retire site: it is in the commit the workspace strip's settle runs
 * (B4) when it lands on the other page, after the receipt line and the switch, and only when the workspace actually landed on Activity. A cancelled swipe never reaches it, which is pinned on the
 * detector's source (a real drag cannot be driven through the Presentation's window on the JVM, so the source is the instrument, as [CompanionWorkspacePolicyTest]'s are).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CompanionActivitySwipeHintTest {

    private lateinit var app: Application

    private fun prefs() = app.getSharedPreferences("mimir_companion_shelf", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        prefs().edit().clear().commit()
        CompanionOrderStore.load(app)
    }

    @Test
    fun `a fresh install shows the hint, and retiring it is written to the prefs`() {
        assertFalse("nothing stored means the hint shows", CompanionOrderStore.activitySwipeHintRetired.value)
        CompanionOrderStore.retireActivitySwipeHint(app)
        assertTrue(CompanionOrderStore.activitySwipeHintRetired.value)
        assertTrue("the flag is in the same prefs as the shelf layout", prefs().getBoolean("companionActivitySwipeHintRetired.v1", false))
    }

    @Test
    fun `the flag survives a restart, and a data wipe brings the hint back`() {
        CompanionOrderStore.retireActivitySwipeHint(app)
        // A restart: the process starts with nothing in memory and `load` reads the file. Put the in-memory flow back to false from the file, then to true from it.
        prefs().edit().putBoolean("companionActivitySwipeHintRetired.v1", false).commit()
        CompanionOrderStore.load(app)
        assertFalse("load reads the file, not the previous process's memory", CompanionOrderStore.activitySwipeHintRetired.value)
        prefs().edit().putBoolean("companionActivitySwipeHintRetired.v1", true).commit()
        CompanionOrderStore.load(app)
        assertTrue("a cold start after the swipe: still retired", CompanionOrderStore.activitySwipeHintRetired.value)

        // A data wipe takes the prefs file with it; the next start shows the hint again.
        prefs().edit().clear().commit()
        CompanionOrderStore.load(app)
        assertFalse(CompanionOrderStore.activitySwipeHintRetired.value)
    }

    @Test
    fun `retiring the hint is idempotent and touches nothing else the store keeps`() {
        CompanionOrderStore.setCursor(app, "gba")
        CompanionOrderStore.setPage(app, 2)
        CompanionOrderStore.retireActivitySwipeHint(app)
        CompanionOrderStore.retireActivitySwipeHint(app)
        assertTrue(CompanionOrderStore.activitySwipeHintRetired.value)
        assertEquals("gba", CompanionOrderStore.cursor.value)
        assertEquals(2, CompanionOrderStore.page.value)
    }

    @Test
    fun `only a switch that landed on Activity retires the hint`() {
        assertTrue(companionActivitySwipeLanded(CompanionWorkspacePage.ACTIVITY))
        assertFalse("a switch that left the workspace on the shelf teaches nothing the player has done", companionActivitySwipeLanded(CompanionWorkspacePage.SHELF))
    }

    @Test
    fun `the Presentation retires the hint at the committed switch, after the receipt and the switch, gated on the landing`() {
        val source = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt").readText()
        // B4: the strip's settle is what commits; the Presentation's commit is where the receipt, the switch and the retire live, and it runs only when a
        // settle landed on the other page (CompanionWorkspaceStripTest), so a drag that settles back or is cancelled never reaches it.
        val site = source.substringAfter("val commitWorkspacePage").substringBefore("CompanionWorkspaceSettleRunner(")
        val receipt = site.indexOf("BottomUiReceipts.log.control(")
        val show = site.indexOf("CompanionWorkspaceState.show(request.target)")
        val gate = site.indexOf("if (request.target == CompanionWorkspacePage.ACTIVITY && companionActivitySwipeLanded(CompanionWorkspaceState.page.value))")
        val retire = site.indexOf("CompanionOrderStore.retireActivitySwipeHint(context)")
        assertTrue("the control receipt is first", receipt in 0 until show)
        assertTrue("the switch comes before the retire", show in 0 until gate)
        assertTrue("the retire is inside the gate on the landed page, and only for the Activity direction", gate in 0 until retire)
        assertEquals("one retire site", 1, Regex("retireActivitySwipeHint").findAll(source).count())
        assertTrue(
            "the footer's hint follows the flag",
            source.contains("showActivitySwipeHint = !activitySwipeHintRetired"),
        )
        assertTrue(
            "the flag is collected from the store the layout lives in",
            source.contains("val activitySwipeHintRetired by CompanionOrderStore.activitySwipeHintRetired.collectAsState()"),
        )
    }

    @Test
    fun `a swipe the player cancels never reaches the switch (the behaviour is pinned on the real pager and the real strip)`() {
        // B3/B4 fix round: this was a read of the surface's source text. The behaviour is now driven for real: CompanionLatticePagerGestureTest injects a system
        // cancel past half and a second finger and sees the page land back with no receipt and the host cancelled and never asked to switch; CompanionWorkspaceStripTest
        // shows a cancel settle back with no control receipt and that only a settle landing on the other page names one; CompanionWorkspaceSurfaceDragTest does
        // the same through the Activity page's own drag. All that is left to pin here is that the retire is reachable from the commit alone.
        val source = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt").readText()
        // D37: the settle runs in CompanionWorkspaceSettleRunner, which is the one caller of the Presentation's commit.
        val runner = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionWorkspaceSettleRunner.kt").readText()
        assertEquals("one commit site", 1, Regex("latestCommit[(]").findAll(runner).count())
        assertEquals("the Presentation hands it over and never calls it", 0, Regex("commitWorkspacePage[(]").findAll(source).count())
    }
}

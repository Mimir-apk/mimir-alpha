package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import android.app.Application
import android.view.Display
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.gruning.mimir.rewrite.core.model.CompanionHomeLane
import com.gruning.mimir.rewrite.core.model.CompanionHomeTile
import com.gruning.mimir.rewrite.core.model.CompanionHomeTileKind
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.ui.BottomSurface
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat
import com.gruning.mimir.rewrite.core.ui.BottomUiReceipts
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper

/**
 * The reclaim handoff covers, through the REAL [MimirCompanionPresentation] (Q7a, X-06; the fresh-Presentation reclaim fix of 2026-10-15):
 *
 *  - A FRESH window created for a reclaim (`reclaimHandoff`, the controller's `companionFreshPresentationReclaims`) wears the mount cover on its
 *    first frame, holds it through its intro and fades it on `OverlayMs`, receipted as `reclaim-cover.fade`. A fresh window that is not a reclaim
 *    wears nothing, and reduced motion cuts.
 *  - A PARKED window revealed again keeps its decor hidden until the composition that carries the reveal cover has been applied, and shows it from
 *    that composition's SideEffect: the first drawn frame of the reveal wears the cover, never the lattice bare. A re-token of a window already
 *    showing wears nothing and shows at once.
 *  - One cover at a time, one cover per reveal: a reveal while the mount cover is still up is covered by it and spent, so no second cover ever
 *    comes for it (F2). A park that lands inside the reveal's fallback window keeps the decor hidden (F1), and a second render in the frame of a
 *    pending reveal goes through that reveal (P3).
 *  - Reuse is OFF (lead 2026-10-15): the controller still reads `isShowing`, so a reclaim after a park recreates the window; `isReusable` is the
 *    Presentation's own liveness that the park and the reveal gate on, and the switch for a later device-tested change.
 *
 * Pixels on the Thor's Bottom are device-owed; this proves the JVM composition and the decor's visibility against it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionReclaimCoverTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scope: CoroutineScope
    private var displayId = Display.INVALID_DISPLAY
    private var presentation: MimirCompanionPresentation? = null

    @Before
    fun setUp() {
        displayId = ShadowDisplayManager.addDisplay("+land", 2)
        ShadowLooper.idleMainLooper()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        ShadowLog.clear()
        setAnimatorScale(1f)
        CompanionState.set(
            CompanionPayload(
                mode = CompanionMode.HOME,
                homeLanes = listOf(
                    CompanionHomeLane(
                        id = "emulation",
                        title = "Emulation",
                        hint = "",
                        tiles = listOf(CompanionHomeTile("gba", CompanionHomeTileKind.PLATFORM, "Game Boy Advance")),
                    ),
                ),
            ),
        )
        // No floor has armed the cold-start handoff: every window here is handed `coldStartHandoff = false`, as a reclaim's is.
        CompanionColdStartHandoff.reset()
        BottomUiReceipts.log.surface(BottomSurface.COLD_START, displayId, visible = false)
    }

    @After
    fun tearDown() {
        presentation?.dismiss()
        CompanionColdStartHandoff.reset()
        presentation = null
        BottomUiReceipts.log.surface(BottomSurface.COLD_START, displayId, visible = false)
        scope.cancel()
        setAnimatorScale(1f)
        CompanionState.set(CompanionPayload.idle())
        Snapshot.sendApplyNotifications()
        ShadowDisplayManager.removeDisplay(displayId)
        ShadowLooper.idleMainLooper()
    }

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun coverCount(): Int =
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "companion-cold-cover")).fetchSemanticsNodes().size

    private fun decorVisibility(window: MimirCompanionPresentation): Int = window.window!!.decorView.visibility

    private fun receipts(): List<String> = ShadowLog.getLogsForTag(BottomUiReceiptFormat.TAG).map { it.msg }

    /** What the window is at the moment a reveal is asked of it, for the message of the assertion that it was revealed. */
    private fun probe(window: MimirCompanionPresentation): String =
        "showing=${window.isShowing} decorAttached=${window.window?.decorView?.isAttachedToWindow} presentation receipts=${receipts().filter { "ev=presentation" in it }}"

    /** The display controller's one construction site, with the reclaim flag it passes for a fresh reclaim window. */
    private fun showLikeTheController(reclaimHandoff: Boolean): MimirCompanionPresentation {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val display = activity.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(displayId)
        val window = MimirCompanionPresentation(
            outerContext = activity,
            display = display,
            lifecycleOwner = activity,
            viewModelStoreOwner = activity,
            savedStateRegistryOwner = activity,
            initialRenderOperationId = UUID.randomUUID(),
            interactionMotionGate = DefaultInteractionMotionGate(scope = scope),
            coldStartHandoff = CompanionColdStartHandoff.forNewPresentation(displayId),
            reclaimHandoff = reclaimHandoff,
        )
        presentation = window
        compose.mainClock.autoAdvance = false
        window.show()
        compose.mainClock.advanceTimeBy(16L)
        return window
    }

    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(16L, left)
            compose.mainClock.advanceTimeBy(step)
            ShadowLooper.idleMainLooper()
            left -= step
        }
    }

    /** Frames until the cover has left the tree, or -1 within [budgetMs]. */
    private fun msUntilCoverGone(budgetMs: Long): Long {
        var elapsed = 0L
        while (elapsed < budgetMs) {
            advance(16L)
            elapsed += 16L
            if (coverCount() == 0) return elapsed
        }
        return -1L
    }

    // ---------------------------------------------------------------------------------------------------------------------------------
    // The fresh reclaim window: the mount cover.
    // ---------------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a fresh reclaim window wears the cover on its first frame, holds it through the intro, then fades it on the overlay clock`() {
        showLikeTheController(reclaimHandoff = true)

        assertEquals("the first frame wears the handoff cover", 1, coverCount())

        var elapsed = 0L
        var introEndedAt = -1L
        var goneAt = -1L
        while (elapsed < 4_000L && goneAt < 0L) {
            advance(16L)
            elapsed += 16L
            if (introEndedAt < 0L && receipts().any { "transition.end" in it && "presentation.intro" in it }) introEndedAt = elapsed
            if (coverCount() == 0) goneAt = elapsed
        }
        println("RECLAIM-MOUNT-COVER introEndedAt=$introEndedAt goneAt=$goneAt")
        assertTrue("the intro ran", introEndedAt > 0L)
        assertTrue("the cover left the tree", goneAt > 0L)
        assertTrue("held until the intro has run: intro end $introEndedAt, gone $goneAt", goneAt > introEndedAt)
        val fade = goneAt - introEndedAt
        assertTrue("fades on the overlay clock (${MimirMotion.OverlayMs} ms), took $fade ms", fade in (MimirMotion.OverlayMs - 40L)..(MimirMotion.OverlayMs + 60L))
        advance(64L)
        assertEquals("one receipted exit begins", 1, receipts().count { "transition.begin" in it && CompanionReclaimCoverFade in it })
        assertEquals("and ends done", 1, receipts().count { "transition.end" in it && CompanionReclaimCoverFade in it && "result=done" in it })
    }

    @Test
    fun `a fresh window that is not a reclaim wears nothing, as before`() {
        showLikeTheController(reclaimHandoff = false)
        assertEquals(0, coverCount())
        advance(600L)
        assertTrue("and writes no cover receipt", receipts().none { CompanionReclaimCoverFade in it })
    }

    @Test
    fun `reduced motion cuts a fresh reclaim window in with no cover`() {
        setAnimatorScale(0f)
        showLikeTheController(reclaimHandoff = true)
        assertEquals(0, coverCount())
    }

    // ---------------------------------------------------------------------------------------------------------------------------------
    // The parked window revealed again: the reveal cover, composed before the decor's first draw.
    // ---------------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a parked window revealed again keeps its decor hidden until the cover is composed, and its first drawn frame wears the cover`() {
        val window = showLikeTheController(reclaimHandoff = false)
        advance(1_200L)
        assertEquals("fixture: settled, no cover", 0, coverCount())
        assertEquals(View.VISIBLE, decorVisibility(window))

        assertTrue(window.parkBehindSecondaryHost())
        assertEquals("fixture: parked, the decor is hidden", View.INVISIBLE, decorVisibility(window))
        // The finding this test made: `Dialog.isShowing()` reads the decor's visibility on this SDK, so a parked window reports not showing. The
        // park, the reveal and the controller's reuse check gate on the window's own liveness instead, or no reveal could ever happen.
        assertFalse("a parked window's decor is hidden, and isShowing reads that", window.isShowing)
        assertTrue("but it is alive for the reveal", window.isReusable)

        // Every traversal of the decor from here: what it would draw (its visibility) against what is composed (covers in the tree).
        val samples = mutableListOf<Pair<Int, Int>>()
        val decor = window.window!!.decorView
        val listener = ViewTreeObserver.OnPreDrawListener {
            samples += decorVisibility(window) to coverCount()
            true
        }
        decor.viewTreeObserver.addOnPreDrawListener(listener)
        try {
            assertTrue(probe(window), window.rebindRenderOperation(UUID.randomUUID()))
            // Synchronously after the reveal: the decor still waits for the composition that carries the cover (the old wiring showed it here).
            assertEquals("the decor is not shown by the rebind itself", View.INVISIBLE, decorVisibility(window))
            advance(16L)
            assertEquals("one frame on, the cover is composed", 1, coverCount())
            assertEquals("and the decor was shown by that composition's SideEffect", View.VISIBLE, decorVisibility(window))

            val goneAt = msUntilCoverGone(1_500L)
            println("RECLAIM-REVEAL-COVER goneAt=$goneAt samples=$samples")
            assertTrue("the cover leaves after its hold and OverlayMs fade (gone at $goneAt)", goneAt in MimirMotion.OverlayMs.toLong()..(MimirMotion.OverlayMs + 160L))
            // No traversal before the cover was composed found the decor visible: the lattice was never drawn bare.
            val beforeCover = samples.takeWhile { (_, covers) -> covers == 0 }
            assertTrue("no traversal could draw the lattice bare: $samples", beforeCover.none { (visibility, _) -> visibility == View.VISIBLE })
            advance(64L)
            assertTrue("the composition came in time: no fallback reveal", receipts().none { "reveal_fallback" in it })
            assertEquals(1, receipts().count { "transition.begin" in it && CompanionReclaimCoverFade in it })
            assertEquals(1, receipts().count { "transition.end" in it && CompanionReclaimCoverFade in it && "result=done" in it })
        } finally {
            decor.viewTreeObserver.removeOnPreDrawListener(listener)
        }
    }

    @Test
    fun `a re-token of a window already showing wears nothing and shows at once`() {
        val window = showLikeTheController(reclaimHandoff = false)
        advance(1_200L)
        assertTrue(window.rebindRenderOperation(UUID.randomUUID()))
        assertEquals("not a reveal: the decor is shown by the rebind", View.VISIBLE, decorVisibility(window))
        advance(64L)
        assertEquals("and no cover is worn", 0, coverCount())
    }

    @Test
    fun `reduced motion reveals a parked window with no cover, from the composition all the same`() {
        setAnimatorScale(0f)
        val window = showLikeTheController(reclaimHandoff = false)
        advance(600L)
        assertTrue(window.parkBehindSecondaryHost())
        assertTrue(probe(window), window.rebindRenderOperation(UUID.randomUUID()))
        advance(32L)
        assertEquals(View.VISIBLE, decorVisibility(window))
        assertEquals(0, coverCount())
    }

    // ---------------------------------------------------------------------------------------------------------------------------------
    // One cover at a time.
    // ---------------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a reveal while a fresh reclaim window's mount cover is still up adds no second cover`() {
        val window = showLikeTheController(reclaimHandoff = true)
        advance(32L)
        assertEquals("fixture: the mount cover is up", 1, coverCount())

        assertTrue(window.parkBehindSecondaryHost())
        assertTrue(probe(window), window.rebindRenderOperation(UUID.randomUUID()))
        var most = 0
        var elapsed = 0L
        var goneAt = -1L
        var coversAfterGone = 0
        // Past the mount cover's leaving by a full second: a reveal it covered is spent, so no second cover ever comes for it (F2).
        while (elapsed < 5_000L && (goneAt < 0L || elapsed < goneAt + 1_000L)) {
            advance(16L)
            elapsed += 16L
            val covers = coverCount()
            most = maxOf(most, covers)
            if (goneAt < 0L && covers == 0) goneAt = elapsed
            if (goneAt > 0L && covers > 0) coversAfterGone += 1
        }
        assertEquals("never two covers", 1, most)
        assertTrue("and the one cover does leave", goneAt > 0L)
        assertEquals("no second cover after the mount cover has left (frames with one: $coversAfterGone)", 0, coversAfterGone)
        assertEquals(View.VISIBLE, decorVisibility(window))
        assertEquals("one receipted exit", 1, receipts().count { "transition.begin" in it && CompanionReclaimCoverFade in it })
    }

    @Test
    fun `a park inside the reveal's fallback window keeps the decor hidden over the app it is parked for (F1)`() {
        val window = showLikeTheController(reclaimHandoff = false)
        advance(1_200L)
        assertTrue(window.parkBehindSecondaryHost())
        assertTrue(probe(window), window.rebindRenderOperation(UUID.randomUUID()))
        assertEquals("fixture: the reveal is pending", View.INVISIBLE, decorVisibility(window))
        // Parked again before a frame has run: the pending reveal is withdrawn.
        assertTrue(window.parkBehindSecondaryHost())
        advance(CompanionDecorRevealFallbackMs + 200L)
        assertEquals("neither the composition nor the fallback shows a parked window", View.INVISIBLE, decorVisibility(window))
        assertTrue("and no fallback fired", receipts().none { "reveal_fallback" in it })
    }

    @Test
    fun `a second render in the frame of a pending reveal goes through that reveal (P3)`() {
        val window = showLikeTheController(reclaimHandoff = false)
        advance(1_200L)
        assertTrue(window.parkBehindSecondaryHost())
        assertTrue(probe(window), window.rebindRenderOperation(UUID.randomUUID()))
        assertTrue(probe(window), window.rebindRenderOperation(UUID.randomUUID()))
        assertEquals("the second render does not show the decor ahead of the cover", View.INVISIBLE, decorVisibility(window))
        advance(16L)
        assertEquals("the cover is composed", 1, coverCount())
        assertEquals("and the decor shown with it", View.VISIBLE, decorVisibility(window))
    }

    // ---------------------------------------------------------------------------------------------------------------------------------
    // The rule and the sites.
    // ---------------------------------------------------------------------------------------------------------------------------------

    private fun main(name: String): String =
        File("src/main/kotlin/com/gruning/mimir/rewrite/$name").readText()

    @Test
    fun `the controller's one construction site passes the reclaim flag from the disposition, and the fallback is bounded`() {
        val controller = main("CompanionDisplayController.kt")
        assertTrue(controller.contains("reclaimHandoff = companionFreshPresentationReclaims(runtimeSnapshot.disposition),"))
        assertTrue(
            controller.contains(
                "internal fun companionFreshPresentationReclaims(disposition: SecondaryDisplayDisposition): Boolean = disposition is SecondaryDisplayDisposition.ReclaimPending",
            ),
        )
        assertTrue(
            "reuse OFF: the reuse and the park-retain decisions still read isShowing, and isReusable stays as the switch",
            controller.contains("canReuseCompanionPresentation(current?.display?.displayId, current?.isShowing == true, target.displayId)") &&
                controller.contains("currentShowing = current?.isShowing == true,") &&
                !controller.contains("isReusable == true"),
        )
        val presentation = main("MimirCompanionPresentation.kt")
        assertTrue("the fresh reclaim window arms the mount cover", presentation.contains("val coldCoverArmed = remember { (coldStartHandoff || reclaimHandoff) && !animatorsOff }"))
        assertTrue(
            "the park and the reveal gate on the window's own liveness",
            presentation.contains("internal val isReusable: Boolean get() = windowShown && composeView != null") &&
                presentation.contains("        if (!windowShown) return false\n        val view = composeView ?: return false") &&
                presentation.contains("        if (!isReusable) return false\n        BottomUiReceipts.log.presentation(\"park\""),
        )
        assertTrue("the reveal's decor waits with a bounded fallback", presentation.contains("view.postDelayed({ revealDecorIfPending(pending, fallback = true) }, CompanionDecorRevealFallbackMs)"))
        assertEquals(100L, CompanionDecorRevealFallbackMs)
        assertFalse("the one-cover rule", companionReclaimCoverWorn(reveals = 1, reducedMotion = false, mountCoverUp = true, spent = 0))
        assertFalse("one cover per reveal", companionReclaimCoverWorn(reveals = 1, reducedMotion = false, mountCoverUp = false, spent = 1))
        assertTrue(companionReclaimCoverWorn(reveals = 2, reducedMotion = false, mountCoverUp = false, spent = 1))
        assertTrue("F1: the park withdraws a pending reveal, and the reveal refuses a parked window", presentation.contains("        decorRevealPendingFor = 0\n        parkedBehindSecondaryHost.value = true\n        return true") && presentation.contains("        if (parkedBehindSecondaryHost.value) return\n        decorRevealPendingFor = 0"))
    }
}

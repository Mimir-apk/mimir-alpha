package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.view.Display
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers

/**
 * The cold-start handoff fade (policy 6, Thor Bottom revamp), through the REAL [MimirCompanionPresentation] and the production handoff state the
 * display controller asks ([CompanionColdStartHandoff], armed by the REAL floor activity's onCreate in the production-path tests), not a caller written for the test.
 *
 * The floor beneath the Presentation wears Main's return cover for good and is replaced by the Presentation's window, so a fade painted in the
 * floor would paint nothing the player sees. The fade lives in the Presentation: on a cold start its first frame wears the same cover, and the cover
 * leaves on `OverlayMs` once the lattice is in. A Presentation that comes back over a yielded app wears none; reduced motion cuts.
 *
 * Pixels on the Thor's Bottom are device-owed; this proves the JVM composition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionPresentationColdCoverTest {

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
        // No floor has armed the handoff and no window is attached, whatever an earlier test left in the process-wide state and log.
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

    /** Exactly what the display controller does at its one construction site: ask the handoff state, hand the answer to the window. */
    private fun showLikeTheController(onDisplay: Int = displayId): MimirCompanionPresentation {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val display = activity.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(onDisplay)
        val window = MimirCompanionPresentation(
            outerContext = activity,
            display = display,
            lifecycleOwner = activity,
            viewModelStoreOwner = activity,
            savedStateRegistryOwner = activity,
            initialRenderOperationId = UUID.randomUUID(),
            interactionMotionGate = DefaultInteractionMotionGate(scope = scope),
            coldStartHandoff = CompanionColdStartHandoff.forNewPresentation(onDisplay),
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

    @Test
    fun `a cold start wears the cover on the first frame and the cover is gone after the overlay clock`() {
        // The floor is up first (its onCreate armed the latch), then the controller builds the window.
        assertTrue("the floor arms the handoff", CompanionColdStartHandoff.floorCreated(displayId))
        BottomUiReceipts.log.surface(BottomSurface.COLD_START, displayId, visible = true)
        showLikeTheController()

        assertEquals("the Presentation's first frame wears the floor's cover", 1, coverCount())

        // Held while the lattice comes in; dropped when the intro (the receipt log's own presentation.intro end) has run, and gone one overlay clock later.
        var elapsed = 0L
        var introEndedAt = -1L
        var goneAt = -1L
        while (elapsed < 4_000L && goneAt < 0L) {
            advance(16L)
            elapsed += 16L
            if (introEndedAt < 0L && ShadowLog.getLogsForTag(BottomUiReceiptFormat.TAG).any { "transition.end" in it.msg && "presentation.intro" in it.msg }) {
                introEndedAt = elapsed
            }
            if (coverCount() == 0) goneAt = elapsed
        }
        println("COLD-COVER introEndedAt=$introEndedAt goneAt=$goneAt")
        assertTrue("the intro ran (it is what the cover waits for)", introEndedAt > 0L)
        assertTrue("the cover left the tree", goneAt > 0L)
        assertTrue("the cover is held until the intro has run: intro end $introEndedAt, gone $goneAt", goneAt > introEndedAt)
        val fade = goneAt - introEndedAt
        assertTrue("the cover fades on the overlay clock (${MimirMotion.OverlayMs} ms), took $fade ms", fade in (MimirMotion.OverlayMs - 40L)..(MimirMotion.OverlayMs + 60L))

        // The receipt stream is the floor's and the host's alone: this window's cover wrote nothing, so the one cold-start line is the floor's enter this test made.
        val coldStartLines = ShadowLog.getLogsForTag(BottomUiReceiptFormat.TAG).map { it.msg }.filter { "surface=cold-start" in it }
        assertEquals("only the floor's own enter: $coldStartLines", 1, coldStartLines.size)
        assertTrue(ShadowLog.getLogsForTag(BottomUiReceiptFormat.TAG).none { "cold-cover" in it.msg })
    }

    @Test
    fun `reduced motion cuts, with no cover at all`() {
        setAnimatorScale(0f)
        CompanionColdStartHandoff.floorCreated(displayId)
        BottomUiReceipts.log.surface(BottomSurface.COLD_START, displayId, visible = true)
        showLikeTheController()

        assertEquals("nothing fades, so nothing is worn", 0, coverCount())
    }

    @Test
    fun `a Presentation that does not follow the floor's cover wears none`() {
        // The first frame on this display has already committed (the cold start is over), so a window that comes back over a yielded app is handed no latch.
        CompanionColdStartHandoff.floorCreated(displayId)
        CompanionColdStartHandoff.firstPresentationFrame(displayId)
        showLikeTheController()

        assertEquals("no badge flashes over a lattice that returns over a yielded app", 0, coverCount())
    }

    // ---------------------------------------------------------------------------------------------------------------------------------
    // The handoff state (V6 fix round, the v3b deferred fix): explicit, ordered, and disarmed by the first frame.
    // ---------------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `floor first, the window takes the latch and follows the cover`() {
        assertTrue("the floor arms with no window attached", CompanionColdStartHandoff.floorCreated(displayId))
        assertTrue(CompanionColdStartHandoff.isArmed(displayId))
        assertTrue("the window built after it follows the cover", CompanionColdStartHandoff.forNewPresentation(displayId))
        assertTrue("and is attached", CompanionColdStartHandoff.isAttached(displayId))
        assertTrue("still armed until a frame: a window recreated before the first frame follows the cover too", CompanionColdStartHandoff.forNewPresentation(displayId))
    }

    @Test
    fun `window first, the floor does not arm and the window wears nothing`() {
        assertFalse("nothing is armed yet, so the window is not a handoff", CompanionColdStartHandoff.forNewPresentation(displayId))
        assertFalse("a floor that comes up under an attached window does not arm", CompanionColdStartHandoff.floorCreated(displayId))
        assertFalse(CompanionColdStartHandoff.isArmed(displayId))
        // The next window on this display (a reclaim recreate) is no handoff either, and the cover count says so through a real window.
        showLikeTheController()
        assertEquals("no cover over a window that was there before the floor", 0, coverCount())
    }

    @Test
    fun `the first window frame disarms the latch unconditionally, so a later reclaim never shows the cover`() {
        CompanionColdStartHandoff.floorCreated(displayId)
        assertTrue(CompanionColdStartHandoff.forNewPresentation(displayId))
        CompanionColdStartHandoff.firstPresentationFrame(displayId)
        assertFalse("disarmed", CompanionColdStartHandoff.isArmed(displayId))
        // The window leaves (a yield), comes back (a reclaim), with the floor never recreated: no cover, however many times.
        CompanionColdStartHandoff.presentationGone(displayId)
        assertFalse(CompanionColdStartHandoff.forNewPresentation(displayId))
        CompanionColdStartHandoff.presentationGone(displayId)
        assertFalse(CompanionColdStartHandoff.forNewPresentation(displayId))
        showLikeTheController()
        assertEquals("a reclaim over a yielded app wears no cover", 0, coverCount())
    }

    @Test
    fun `a frame disarms even a window that never took the latch`() {
        // Window first, then a floor that (wrongly) armed in the log's old world: the first frame still ends the cold start.
        CompanionColdStartHandoff.floorCreated(displayId)
        CompanionColdStartHandoff.firstPresentationFrame(displayId)
        assertFalse(CompanionColdStartHandoff.isArmed(displayId))
        assertFalse("another display is untouched by this one's frame", CompanionColdStartHandoff.isArmed(displayId + 1))
    }

    @Test
    fun `a floor that is gone has no cover up, and displays are independent`() {
        CompanionColdStartHandoff.floorCreated(displayId)
        CompanionColdStartHandoff.floorCreated(displayId + 1)
        CompanionColdStartHandoff.floorDestroyed(displayId)
        assertFalse(CompanionColdStartHandoff.forNewPresentation(displayId))
        assertTrue("the other display's floor is still up", CompanionColdStartHandoff.forNewPresentation(displayId + 1))
        assertFalse("an unknown display never arms", CompanionColdStartHandoff.floorCreated(BottomUiReceiptFormat.NO_DISPLAY))
    }

    /**
     * The production path, not a seeded log: the REAL floor activity's onCreate arms the latch (Robolectric attaches every Activity to a non-UI base context, so the harness marks
     * it an ACTIVITY context for the floor's lifetime, as [CompanionSecondaryHomeThemeTest] does), and the same call the controller makes at its construction site builds a
     * REAL window that wears the cover. Then the window's first frame disarms it, and the next window over a yielded app wears none.
     */
    @Test
    fun `the real floor arms the latch in onCreate and the controller's seam builds a window that follows its cover`() {
        withFloor { floor ->
            val floorDisplay = floor.display?.displayId ?: error("the floor has no display")
            assertTrue("the real floor's onCreate armed the latch before any composition", CompanionColdStartHandoff.isArmed(floorDisplay))
            // The floor's own cover is a cover node too (it stays up for good beneath the window): count what the WINDOW adds.
            val floorsOwn = coverCount()
            assertEquals("the floor wears its own cover", 1, floorsOwn)
            showLikeTheController(onDisplay = floorDisplay)
            assertEquals("a window built over the real floor wears the cover on its first frame", floorsOwn + 1, coverCount())
            CompanionColdStartHandoff.firstPresentationFrame(floorDisplay)
            assertFalse("the first frame disarms it", CompanionColdStartHandoff.isArmed(floorDisplay))
            presentation?.dismiss()
            CompanionColdStartHandoff.presentationGone(floorDisplay)
            presentation = null
            showLikeTheController(onDisplay = floorDisplay)
            assertEquals("the reclaim window over the same real floor wears none", floorsOwn, coverCount())
        }
    }

    @Test
    fun `a real floor that comes up under an attached window does not arm`() {
        // The floor on the default display: attach a window there first, as the controller would.
        CompanionColdStartHandoff.forNewPresentation(0)
        withFloor { floor ->
            val floorDisplay = floor.display?.displayId ?: error("the floor has no display")
            assertFalse("a window is already attached on the floor's display, so the floor did not arm", CompanionColdStartHandoff.isArmed(floorDisplay))
        }
    }

    private fun withFloor(block: (Activity) -> Unit) {
        val base = RuntimeEnvironment.getApplication().baseContext
        val originalType = ReflectionHelpers.getField<Int>(base, "mContextType")
        ReflectionHelpers.setField(base, "mContextType", ReflectionHelpers.getStaticField<Int>(base.javaClass, "CONTEXT_TYPE_ACTIVITY"))
        try {
            // P2-3: the floor ends itself on the default display, which is the only display Robolectric has.
            CompanionFloorDisplayGuard.refuseDefaultDisplay = false
            val controller = Robolectric.buildActivity(CompanionSecondaryHomeActivity::class.java).setup()
            try {
                block(controller.get())
            } finally {
                controller.pause().stop().destroy()
            }
        } finally {
            ReflectionHelpers.setField(base, "mContextType", originalType)
            CompanionFloorDisplayGuard.refuseDefaultDisplay = true
        }
    }

    private fun main(name: String): String =
        java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/$name").readText()

    private fun squash(text: String): String = text.split(Regex("""\s+""")).joinToString(" ")

    private fun count(text: String, needle: String): Int = text.split(needle).size - 1

    @Test
    fun `the controller's one construction site hands the window the latch, the first frame disarms it before any stale check, and the floor's cover is never hidden`() {
        val controller = squash(main("CompanionDisplayController.kt"))
        assertEquals(
            "the one Presentation construction site asks the handoff state",
            1,
            count(controller, "coldStartHandoff = CompanionColdStartHandoff.forNewPresentation(target.displayId),"),
        )
        assertEquals("and there is only that one construction site", 1, count(controller, "MimirCompanionPresentation("))
        assertFalse("the receipt log is no longer read for the decision", controller.contains("companionPresentationFollowsColdCover") || controller.contains("isSurfaceVisible"))
        val commit = controller.substringAfter("onFirstFrameCommitted = frameCommitted@{ committedId -> runOnMain commit@{").substringBefore("onWindowRevealed")
        assertTrue(
            "the first frame disarms the latch before the stale-window check",
            commit.indexOf("CompanionColdStartHandoff.firstPresentationFrame(target.displayId)") in 0 until commit.indexOf("if (!started || presentation !== candidate) return@commit"),
        )
        assertTrue(
            "the window leaving is recorded wherever it is dismissed, replaced or the host stops",
            controller.contains("if (left != null && left !== value) CompanionColdStartHandoff.presentationGone(left.display.displayId)"),
        )
        assertEquals("the receipt edge is unchanged: one exit per frame commit", 1, count(controller, "coldStartCoverGone("))

        val floor = squash(main("CompanionSecondaryHomeActivity.kt"))
        assertEquals("the floor's cover call is the bare, always-visible one", 1, count(floor, "CompanionMannazCover()"))
        assertFalse("the floor never passes visible", floor.contains("CompanionMannazCover(visible"))
        assertTrue(
            "the floor arms synchronously in onCreate, before setContent",
            floor.indexOf("CompanionColdStartHandoff.floorCreated(floorDisplay)") in 0 until floor.indexOf("setContent {"),
        )
        assertTrue("and disarms when it is destroyed", floor.contains("CompanionColdStartHandoff.floorDestroyed(floorDisplay)"))

        val presentation = squash(main("MimirCompanionPresentation.kt"))
        assertTrue("the Presentation wears the same composable over its content", presentation.contains("CompanionMannazCover(visible = coldCoverHeld, onGone = { coldCoverLeft = true })"))
        assertEquals("it is dropped in one place, after the intro", 1, count(presentation, "coldCoverHeld = false"))
        assertFalse("the receipt-log read is gone from the Presentation", presentation.contains("companionPresentationFollowsColdCover"))
        // The ring's hold releases when the cover has FULLY left (its exit fade finished), not when the fade starts.
        assertTrue("the ring waits for the intro and for the cover to be gone", presentation.contains("{ enterAlpha.value >= 1f && coldCoverLeft }"))
        assertFalse("not for the start of the fade", presentation.contains("enterAlpha.value >= 1f && !coldCoverHeld"))
    }

    @Test
    fun `the receipt log's cold-start exit says what the Presentation's own fade is`() {
        val log = java.io.File("../core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/BottomUiReceiptLog.kt").readText()
        assertFalse("the stale 'no fade of its own to name' sentence is gone", log.contains("with no fade of its own to name"))
        assertTrue("it names the Presentation's own handoff fade", log.contains("MimirCompanionPresentation.coldStartHandoff"))
    }
}

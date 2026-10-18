package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.model.CompanionHomeLane
import com.gruning.mimir.rewrite.core.model.CompanionHomeTile
import com.gruning.mimir.rewrite.core.model.CompanionHomeTileKind
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.ui.AmbientMotionPhase
import com.gruning.mimir.rewrite.feature.companion.CompanionSurface
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Thor Bottom ring after retiring the breath animation, end to end on the JVM: the gate the Presentation uses, its ambient provider and the
 * real companion surface, run under one virtual clock. This replaces the O18 test that counted the breath's steps until the first touch: there is no breath now. A cursor that has landed wears Main's ring, its one gleam plays, and the frame is STILL, whatever the ambient
 * phase says and whether the player touched the Bottom or not.
 *
 * The test counts writes to snapshot state (any kind): a ticker would write one per step, and on device each is one layer invalidation and so one
 * Presentation frame. The gate's clock and the composition's clock advance together, one 16 ms frame at a time. How many frames the Thor draws stays
 * device-owed; the pixel proof is `CompanionCursorRingRenderTest` in :feature-companion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CompanionRingStillnessTest {

    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val clock = ManualDelay()
    private val gate = DefaultInteractionMotionGate(scope = scope, clock = clock, delayMillis = clock::delay)
    private var stateWrites = 0
    private val writeObserver = Snapshot.registerGlobalWriteObserver { stateWrites++ }
    private var covered by mutableStateOf(false)

    private val home = CompanionPayload(
        mode = CompanionMode.HOME,
        homeLanes = listOf(
            CompanionHomeLane(
                id = "emulation",
                title = "Emulation",
                hint = "",
                tiles = listOf(
                    CompanionHomeTile("gba", CompanionHomeTileKind.PLATFORM, "Game Boy Advance"),
                    CompanionHomeTile("ds", CompanionHomeTileKind.PLATFORM, "Nintendo DS"),
                ),
            ),
        ),
    )

    @After
    fun tearDown() {
        writeObserver.dispose()
        scope.cancel()
    }

    private class Roots(val native: RootLease, val primary: RootLease, val companion: RootLease)

    /**
     * A process start's registrations in its order, with no input: the ᛗ's native root mounted on the Bottom, MainActivity's
     * shown in `onStart`, then the Presentation's. The last wake is at 1,000 ms, so the window ends at 13,000 ms.
     */
    private fun restart(): Roots {
        val native = gate.registerRoot(InteractionRoot.NATIVE_WINDOW, initiallyVisible = true)
        clock.advanceBy(600)
        val primary = gate.registerRoot(InteractionRoot.PRIMARY, initiallyVisible = false)
        primary.setVisible(true)
        clock.advanceBy(400)
        val companion = gate.registerRoot(InteractionRoot.COMPANION, initiallyVisible = true)
        return Roots(native, primary, companion)
    }

    /** The Presentation's provider over the surface, resting with its cursor on a tile. */
    private fun showTheShelf() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompanionAmbientMotionProvider(interactionMotionGate = gate) {
                CompanionSurface(
                    payload = home,
                    cursorId = "gba",
                    panelCovered = covered,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        advance(160)
    }

    private fun advance(millis: Long) {
        require(millis % FrameMillis == 0L) { "advance whole frames so both clocks stay together" }
        repeat((millis / FrameMillis).toInt()) {
            clock.advanceBy(FrameMillis)
            compose.mainClock.advanceTimeBy(FrameMillis)
        }
    }

    private fun advanceTo(atMillis: Long) = advance(atMillis - clock.nowMillis())

    private fun writesOver(millis: Long): Int {
        val before = stateWrites
        advance(millis)
        return stateWrites - before
    }

    @Test
    fun `after a restart with no input the settled ring writes nothing, in RECENT and after IDLE`() {
        restart()
        showTheShelf()
        assertEquals(1_160L, clock.nowMillis())

        // The ring (420 ms) and its one gleam (160 ms settle + 540 ms sweep) are over by 2 s. The old breath wrote about 4 steps a second here.
        advance(2_000)
        assertEquals(AmbientMotionPhase.RECENT, gate.phase.value)
        assertEquals("a settled ring in the RECENT window writes nothing: no breath, no ticker", 0, writesOver(8_000))

        advanceTo(13_000)
        assertEquals("12,000 ms after the last window appeared", AmbientMotionPhase.IDLE, gate.phase.value)
        // The phase reaches the composition on the next frame.
        advance(64)
        assertEquals("IDLE leaves nothing ticking either", 0, writesOver(4_000))
    }

    @Test
    fun `a Bottom touch, a cover and the next wake change nothing at rest`() {
        val roots = restart()
        showTheShelf()
        advance(2_000)
        assertEquals("before any touch on the Bottom", 0, writesOver(2_000))

        // MimirCompanionPresentation.dispatchTouchEvent: one lease on the companion root from DOWN to UP.
        val touch = gate.hold(roots.companion, InteractionSource.TOUCH)
        advance(96)
        touch.close()
        // The ambient root the touch names reaches the composition once; the full-rate ring it used to buy no longer exists.
        advance(160)
        assertEquals("after a touch on the Bottom the ring is no busier: the full-rate breath is gone", 0, writesOver(2_000))

        // `covered` is written from outside composition: send the write now. A cover stands the gleam down; it never starts a clock.
        covered = true
        Snapshot.sendApplyNotifications()
        advance(480)
        assertEquals("a covered ring writes nothing", 0, writesOver(1_200))
        covered = false
        Snapshot.sendApplyNotifications()
        advance(480)
        assertEquals("uncovered, the ring is still", 0, writesOver(1_200))

        // The Presentation parked behind a host and shown again, with no touch: a new wake, and still nothing ticking.
        roots.companion.setVisible(false)
        roots.companion.setVisible(true)
        assertEquals(AmbientMotionPhase.RECENT, gate.phase.value)
        advance(160)
        assertEquals("a window appearing after that window closed", 0, writesOver(2_000))
    }

    /** A monotonic clock whose delays resume only when the test advances it. */
    private class ManualDelay : MonotonicClock {
        private class Waiter(val deadlineMillis: Long, val continuation: CancellableContinuation<Unit>)

        private val waiters = mutableListOf<Waiter>()
        private var nowMillis = 0L

        override fun nowMillis(): Long = nowMillis

        suspend fun delay(durationMillis: Long) {
            if (durationMillis <= 0L) return
            suspendCancellableCoroutine { continuation ->
                val waiter = Waiter(nowMillis + durationMillis, continuation)
                waiters += waiter
                continuation.invokeOnCancellation { waiters.remove(waiter) }
            }
        }

        fun advanceBy(durationMillis: Long) {
            nowMillis += durationMillis
            while (true) {
                val due = waiters.filter { it.deadlineMillis <= nowMillis }
                if (due.isEmpty()) return
                waiters.removeAll(due.toSet())
                due.forEach { waiter -> if (waiter.continuation.isActive) waiter.continuation.resume(Unit) }
            }
        }
    }

    private companion object {
        const val FrameMillis = 16L
    }
}

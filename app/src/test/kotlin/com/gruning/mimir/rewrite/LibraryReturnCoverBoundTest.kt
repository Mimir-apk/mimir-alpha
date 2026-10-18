package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The bound on the Library return cover (combined45 emulator sweep, N1: "Welcome back" for 24-26 s behind a restore that was done at
 * 600 ms, waiting for frames a reclaim cycle starved). Before this the Library arm's hold was bounded in frames alone
 * (MimirNavigationCoordinator.kt's eight focus attempts and two drawn frames) and restarted whole on every key change; nothing in time
 * lifted it. Now the cover holds at most [LibraryLaunchReturnCoverGraceMs] of resumed time from the first run of a real return, and the
 * restore goes on behind the lifted cover. On the base the bound, its clock and its wiring do not exist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryReturnCoverBoundTest {

    @get:Rule
    val compose = createComposeRule()

    private var deadline by mutableStateOf<Long?>(null)
    private var resumed by mutableStateOf(true)
    private var fakeNowMs = 10_000L
    private val lifts = mutableListOf<Long>()

    @Before
    fun setUp() {
        compose.mainClock.autoAdvance = false
    }

    private fun mount() {
        compose.setContent {
            MimirLibraryLaunchReturnCoverBound(
                deadlineMs = deadline,
                resumed = resumed,
                onLift = { lifts += fakeNowMs },
                nowMs = { fakeNowMs },
            )
        }
    }

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        fakeNowMs += ms
        compose.waitForIdle()
    }

    /** A state write, then the one frame that recomposes on it and starts (or cancels) the bound's effect; the wait is counted from there. */
    private fun write(block: () -> Unit) {
        compose.runOnUiThread(block)
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    @Test
    fun `the cover lifts at the bound, once, and not a frame before`() {
        mount()
        write { deadline = fakeNowMs + LibraryLaunchReturnCoverGraceMs }
        advance(LibraryLaunchReturnCoverGraceMs - 16L)
        assertTrue("held until the bound", lifts.isEmpty())
        advance(16L)
        assertEquals("lifted at the bound", 1, lifts.size)
        advance(5_000L)
        assertEquals("and only once", 1, lifts.size)
    }

    @Test
    fun `no deadline, or a stopped host, lifts nothing, and the resume takes up the same deadline`() {
        mount()
        advance(2 * LibraryLaunchReturnCoverGraceMs)
        assertTrue("no return is pending: nothing to lift", lifts.isEmpty())

        write {
            deadline = fakeNowMs + LibraryLaunchReturnCoverGraceMs
            resumed = false
        }
        advance(2 * LibraryLaunchReturnCoverGraceMs)
        assertTrue("behind a stopped window nothing is in front of the player: no clock runs", lifts.isEmpty())

        // Main resumes with the deadline already past (the guard held it stopped): the cover lifts on the next frame.
        write { resumed = true }
        advance(16L)
        assertEquals(1, lifts.size)
    }

    @Test
    fun `a reset mid-wait cancels the lift, and a new deadline starts its own wait`() {
        mount()
        write { deadline = fakeNowMs + LibraryLaunchReturnCoverGraceMs }
        advance(LibraryLaunchReturnCoverGraceMs / 2)
        write { deadline = null }
        advance(2 * LibraryLaunchReturnCoverGraceMs)
        assertTrue("the restore lifted the cover itself: the bound has nothing to do", lifts.isEmpty())

        write { deadline = fakeNowMs + LibraryLaunchReturnCoverGraceMs }
        advance(LibraryLaunchReturnCoverGraceMs - 16L)
        assertTrue("the new deadline waits its own grace out", lifts.isEmpty())
        advance(16L)
        assertEquals("and lifts once at it", 1, lifts.size)
    }

    @Test
    fun `the clock mints its deadline once, holds until it, and never extends it`() {
        val clock = LibraryLaunchReturnCoverClock(graceMs = 4_000L)
        assertNull("nothing minted before a real return runs", clock.deadlineMs)
        assertTrue(clock.holds(1_000L))
        assertEquals(5_000L, clock.deadlineMs)
        assertTrue("a restart of the effect reads the same deadline", clock.holds(4_999L))
        assertEquals(5_000L, clock.deadlineMs)
        assertFalse(clock.holds(5_000L))
        assertFalse("absolute: asking again past the deadline never extends it", clock.holds(6_000L))
        assertEquals(5_000L, clock.deadlineMs)
        clock.reset()
        assertNull(clock.deadlineMs)
        assertTrue("the next return mints its own", clock.holds(9_000L))
        assertEquals(13_000L, clock.deadlineMs)
    }

    @Test
    fun `the budget is the cover's one, the restore raises the cover only while the clock holds, and the bound is mounted`() {
        assertEquals("one patience budget for the return cover, expressed once", HomeLaunchReturnRenderableGraceMs, LibraryLaunchReturnCoverGraceMs)
        assertEquals(
            "return-wait bound lifted the cover graceMs=4000 cause=library-return: the restore goes on behind the Library",
            libraryLaunchReturnCoverBoundLogLine(4_000L),
        )

        val source = appSource("MimirNavigationCoordinator.kt").readText()
        val effect = source.substringAfter("private fun MimirLibraryLaunchReturnEffect(").substringBefore("internal const val LibraryLaunchReturnDrawnFrames")
        assertTrue(
            "a real return raises the cover only while its clock holds, and the first run mints the deadline",
            effect.contains("navigation.libraryLaunchReturnRestoring = navigation.libraryLaunchReturnCoverClock.holds(SystemClock.elapsedRealtime())"),
        )
        assertFalse("never an unconditional raise inside the restore", effect.contains("navigation.libraryLaunchReturnRestoring = true"))
        assertTrue("the deadline is minted after the return is known to be real, not at the launch", effect.indexOf("libraryLaunchReturnMayRestore(") < effect.indexOf("libraryLaunchReturnCoverClock.holds("))
        assertEquals("both completions reset the clock with the return", 2, Regex(Regex.escape("navigation.libraryLaunchReturnCoverClock.reset()")).findAll(effect).count())

        val effects = source.substringAfter("internal fun MimirNavigationEffects(").substringBefore("@Composable")
        assertTrue("the bound is mounted beside the restore effect", effects.contains("MimirLibraryLaunchReturnCoverBound("))
        assertTrue(effects.contains("deadlineMs = navigation.libraryLaunchReturnCoverClock.deadlineMs,"))
        assertTrue(effects.contains("resumed = navigation.libraryHostResumed,"))
        assertTrue(effects.contains("onLift = navigation::liftLibraryLaunchReturnCover,"))

        val coordinator = source.substringAfter("internal class MimirNavigationCoordinator internal constructor(").substringBefore("internal fun unwindHomeQuickLaunchFocus(")
        assertTrue("arming a return starts a fresh clock", coordinator.substringAfter("fun armLibraryLaunchReturn() {").substringBefore("}").contains("libraryLaunchReturnCoverClock.reset()"))
        assertTrue("disarming it clears the clock", coordinator.substringAfter("fun disarmLibraryLaunchReturn() {").substringBefore("}").contains("libraryLaunchReturnCoverClock.reset()"))
        val lift = coordinator.substringAfter("fun liftLibraryLaunchReturnCover() {").substringBefore("}")
        assertTrue("the lift drops the cover's flag and says so", lift.contains("libraryLaunchReturnRestoring = false") && lift.contains("logLibraryLaunchReturnCoverLifted()"))
        assertFalse("and touches nothing of the restore: the armed flag and the pending target stay", lift.contains("libraryLaunchReturnArmed") || lift.contains("clearPendingLaunchReturnTarget"))
    }

    private fun appSource(fileName: String): File = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
    ).firstOrNull { it.isFile }
        ?: throw AssertionError("$fileName not reachable from the test working directory")
}

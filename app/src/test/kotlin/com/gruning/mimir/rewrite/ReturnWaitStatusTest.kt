package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.gruning.mimir.rewrite.core.ui.LocalMimirMotionLive
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * The return cover's words, on the real [ReturnWaitStatus] under Robolectric (AYN Thor, 2026-10-07: after about
 * seventy minutes in Azahar, Main sat on the bare Mannaz rune for about five seconds and read as a hang).
 *
 * Compose runs on its virtual clock here, so every time below is the composition's own; [fakeNowMs] is advanced beside it so
 * the logged `waitedMs` can be checked against what the test spent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReturnWaitStatusTest {

    @get:Rule
    val compose = createComposeRule()

    private var waiting by mutableStateOf(true)
    private var subject by mutableStateOf(ReturnWaitSubject.None)
    private var mounted by mutableStateOf(true)
    private var resumed by mutableStateOf(true)
    private var fakeNowMs = 10_000L

    @Before
    fun setUp() {
        ShadowLog.clear()
        compose.mainClock.autoAdvance = false
    }

    private fun mount(
        reducedMotion: Boolean = false,
        live: Boolean = true,
        cause: ReturnWaitCause = ReturnWaitCause.HOME_RETURN,
    ) {
        val liveState = mutableStateOf(live)
        compose.setContent {
            MimirTheme {
                CompositionLocalProvider(LocalMimirMotionLive provides liveState) {
                    Box(Modifier.fillMaxSize()) {
                        if (mounted) {
                            ReturnWaitStatus(
                                waiting = waiting,
                                subject = subject,
                                cause = cause,
                                reducedMotion = reducedMotion,
                                logDetail = { "scope=home/shelf splash=false" },
                                nowMs = { fakeNowMs },
                                resumed = resumed,
                            )
                        }
                    }
                }
            }
        }
    }

    /** The composition's clock first, then the fake wall clock: what the effects read during these frames is the start of the span. */
    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        fakeNowMs += ms
    }

    private fun write(block: () -> Unit) {
        compose.runOnUiThread(block)
        Snapshot.sendApplyNotifications()
    }

    private fun statusNodes() = compose.onAllNodesWithTag(ReturnWaitStatusTag, useUnmergedTree = true)

    private fun logLines(): List<String> = ShadowLog.getLogsForTag(ReturnWaitLogTag).map { it.msg }

    @Test
    fun `nothing is drawn before the delay and the status is there once it has run`() {
        mount()
        advance(ReturnWaitStatusDelayMs - 100)
        statusNodes().assertCountEquals(0)
        advance(ReturnWaitStatusDelayMs)
        compose.mainClock.advanceTimeByFrame()
        statusNodes().assertCountEquals(1)
    }

    @Test
    fun `the delay is 600 ms`() {
        assertEquals(600L, ReturnWaitStatusDelayMs)
    }

    @Test
    fun `it names the game when the ledger knows it, and greets plainly when it does not`() {
        write { subject = ReturnWaitSubject(title = "Metroid: Samus Returns", packageName = "io.github.lime3ds.android") }
        mount()
        advance(ReturnWaitStatusDelayMs * 2)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(ReturnWaitStatusTag)
            .assertContentDescriptionEquals("Back from Metroid: Samus Returns — restoring your library…")
        // What TalkBack is told is the line on screen, word for word.
        compose.onNodeWithText("Back from Metroid: Samus Returns — restoring your library…", useUnmergedTree = true).assertExists()

        write { subject = ReturnWaitSubject.None }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(ReturnWaitStatusTag)
            .assertContentDescriptionEquals("Welcome back — restoring your library…")
        compose.onNodeWithText("Welcome back — restoring your library…", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `it fades out and goes when the restore is ready, and writes the one log line`() {
        write { subject = ReturnWaitSubject(title = "Metroid: Samus Returns", packageName = "io.github.lime3ds.android") }
        mount()
        advance(4_400L)
        compose.mainClock.advanceTimeByFrame()
        statusNodes().assertCountEquals(1)
        assertTrue("nothing is logged while the wait is still going", logLines().isEmpty())

        write { waiting = false }
        advance(400L)
        compose.mainClock.advanceTimeByFrame()
        statusNodes().assertCountEquals(0)

        // The cover then leaves composition too: the wait is already over, so it must not be written twice.
        write { mounted = false }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(
            listOf(
                "return-wait shown game=io.github.lime3ds.android waitedMs=4400 cause=home-return " +
                    "scope=home/shelf splash=false",
            ),
            logLines(),
        )
    }

    @Test
    fun `a return that settles inside the delay shows nothing and logs nothing`() {
        mount()
        advance(300L)
        write { waiting = false }
        advance(ReturnWaitStatusDelayMs * 3)
        compose.mainClock.advanceTimeByFrame()
        statusNodes().assertCountEquals(0)
        write { mounted = false }
        compose.mainClock.advanceTimeByFrame()
        assertTrue("a status nobody saw is not reported as shown", logLines().isEmpty())
    }

    @Test
    fun `a cover that leaves composition mid-wait ends the wait and logs it once`() {
        mount(cause = ReturnWaitCause.LIBRARY_RETURN)
        advance(1_500L)
        compose.mainClock.advanceTimeByFrame()
        statusNodes().assertCountEquals(1)
        write { mounted = false }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1, logLines().size)
        assertTrue(logLines().single(), logLines().single().startsWith("return-wait shown game=none waitedMs=1500 cause=library-return"))
    }

    @Test
    fun `it is one polite live region saying the whole sentence`() {
        write { subject = ReturnWaitSubject(title = "Zelda", packageName = "com.example.emu") }
        mount()
        advance(ReturnWaitStatusDelayMs * 2)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(ReturnWaitStatusTag)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assertContentDescriptionEquals("Back from Zelda — restoring your library…")
        // The sweep is decoration: it adds no node of its own to what a screen reader walks.
        compose.onAllNodesWithTag(ReturnWaitSweepTag).assertCountEquals(0)
    }

    @Test
    fun `with motion on the indicator sweeps`() {
        mount(reducedMotion = false)
        advance(ReturnWaitStatusDelayMs * 2)
        compose.mainClock.advanceTimeByFrame()
        compose.onAllNodesWithTag(ReturnWaitSweepTag, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithTag(ReturnWaitStillTag, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `reduced motion shows a still bar, never a sweep`() {
        mount(reducedMotion = true)
        advance(ReturnWaitStatusDelayMs * 2)
        compose.mainClock.advanceTimeByFrame()
        statusNodes().assertCountEquals(1)
        compose.onAllNodesWithTag(ReturnWaitStillTag, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithTag(ReturnWaitSweepTag, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `a parked pane (D24) shows the still bar and runs no sweep`() {
        mount(reducedMotion = false, live = false)
        advance(ReturnWaitStatusDelayMs * 2)
        compose.mainClock.advanceTimeByFrame()
        compose.onAllNodesWithTag(ReturnWaitStillTag, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithTag(ReturnWaitSweepTag, useUnmergedTree = true).assertCountEquals(0)
    }

    // --- The wait runs while the player can SEE the cover ---

    @Test
    fun `a cover requested behind a stopped host starts no wait and shows nothing`() {
        write { resumed = false }
        mount()
        // A launch raised the cover and a game took the screen: minutes pass, none of them in front of the player.
        advance(120_000L)
        compose.mainClock.advanceTimeByFrame()
        statusNodes().assertCountEquals(0)
        assertTrue("nothing was shown, nothing is logged", logLines().isEmpty())
    }

    @Test
    fun `the 600 ms starts at resume - the first resumed frames find no sentence`() {
        write { resumed = false }
        mount()
        advance(120_000L)
        write { resumed = true }
        // The first resumed frames: under the old arming the sentence was already due here and faded in, to be cut moments later.
        advance(100L)
        compose.mainClock.advanceTimeByFrame()
        statusNodes().assertCountEquals(0)
        advance(ReturnWaitStatusDelayMs - 200L)
        compose.mainClock.advanceTimeByFrame()
        statusNodes().assertCountEquals(0)
        advance(300L)
        compose.mainClock.advanceTimeByFrame()
        statusNodes().assertCountEquals(1)
    }

    @Test
    fun `the log measures what the player saw - waitedMs runs from the resume, not from the launch`() {
        write { subject = ReturnWaitSubject(title = "Metroid", packageName = "dev.eden.eden_emulator") }
        write { resumed = false }
        mount()
        advance(90_000L)
        write { resumed = true }
        advance(1_500L)
        compose.mainClock.advanceTimeByFrame()
        statusNodes().assertCountEquals(1)
        write { waiting = false }
        advance(400L)
        compose.mainClock.advanceTimeByFrame()
        write { mounted = false }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(
            listOf("return-wait shown game=dev.eden.eden_emulator waitedMs=1500 cause=home-return scope=home/shelf splash=false"),
            logLines(),
        )
    }

    @Test
    fun `the game taking the screen again ends the wait and the next resume starts a new one`() {
        mount()
        advance(1_000L)
        compose.mainClock.advanceTimeByFrame()
        statusNodes().assertCountEquals(1)
        write { resumed = false }
        advance(60_000L)
        compose.mainClock.advanceTimeByFrame()
        statusNodes().assertCountEquals(0)
        assertEquals("the stretch it was shown for is written once, at the pause", 1, logLines().size)
        assertTrue(logLines().single(), logLines().single().contains("waitedMs=1000"))
        write { resumed = true }
        advance(300L)
        compose.mainClock.advanceTimeByFrame()
        statusNodes().assertCountEquals(0)
        advance(ReturnWaitStatusDelayMs)
        compose.mainClock.advanceTimeByFrame()
        statusNodes().assertCountEquals(1)
    }

    @Test
    fun `the wait runs only for a requested cover on a resumed host`() {
        assertTrue(returnWaitIsRunning(waiting = true, resumed = true))
        assertTrue(!returnWaitIsRunning(waiting = true, resumed = false))
        assertTrue(!returnWaitIsRunning(waiting = false, resumed = true))
        assertTrue(!returnWaitIsRunning(waiting = false, resumed = false))
    }
}

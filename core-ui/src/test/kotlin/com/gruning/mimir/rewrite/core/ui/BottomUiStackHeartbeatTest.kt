package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A5 fold-in 4: the stack heartbeat lives and dies with the Presentation's composition. While the window is composed it restates
 * the surface stack on its interval; once the window is dismissed (the composition leaves) its timer is cancelled and nothing more
 * is written. The clock is injected, so the cadence is a paused one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BottomUiStackHeartbeatTest {
    @get:Rule
    val compose = createComposeRule()

    private val lines = ArrayList<String>()
    private val log = BottomUiReceiptLog(
        sink = { line, _ -> lines += line },
        nowMs = { 0L },
        timer = BottomUiReceiptTimer { _, _ -> {} },
        stackSnapshots = true,
    )

    private fun heartbeats(): List<String> = lines.filter { it.contains("reason=heartbeat") }

    @Test
    fun theHeartbeatTicksWhileThePresentationIsShownAndNeverAfterItIsDismissed() {
        log.surface(BottomSurface.LATTICE, 4, true)
        lines.clear()
        val waits = ArrayList<Long>()
        val gate = Channel<Unit>(Channel.UNLIMITED)
        var cancelled = false
        var shown by mutableStateOf(true)
        compose.setContent {
            if (shown) {
                BottomUiStackHeartbeat(
                    display = 4,
                    log = log,
                    wait = { ms ->
                        waits += ms
                        try {
                            gate.receive()
                        } catch (e: CancellationException) {
                            cancelled = true
                            throw e
                        }
                    },
                )
            }
        }
        compose.waitForIdle()
        assertEquals("the timer is armed on the five-minute interval and nothing is written yet", listOf(300_000L), waits)
        assertTrue(lines.toString(), heartbeats().isEmpty())

        gate.trySend(Unit)
        compose.waitForIdle()
        assertEquals(listOf("ev=surface.state display=4 stack=lattice reason=heartbeat"), heartbeats())

        gate.trySend(Unit)
        compose.waitForIdle()
        assertEquals(2, heartbeats().size)
        assertEquals("a fresh interval each time", List(3) { 300_000L }, waits)

        // The Presentation is dismissed: the composition leaves, and its timer with it.
        shown = false
        compose.waitForIdle()
        assertTrue("the pending wait was cancelled with the window", cancelled)
        val written = lines.size
        gate.trySend(Unit)
        compose.waitForIdle()
        assertEquals("no heartbeat after the window is gone: ${lines.drop(written)}", written, lines.size)
        assertEquals(2, heartbeats().size)
    }

    @Test
    fun aCompositionNotHostedOnABottomArmsNoTimerAtAll() {
        val waits = ArrayList<Long>()
        compose.setContent {
            BottomUiStackHeartbeat(display = BottomUiReceiptFormat.NO_DISPLAY, log = log, wait = { waits += it })
        }
        compose.waitForIdle()
        assertTrue("Main and previews write nothing and run no timer", waits.isEmpty() && lines.isEmpty())
        assertFalse(lines.any { it.startsWith("ev=surface.state") })
    }
}

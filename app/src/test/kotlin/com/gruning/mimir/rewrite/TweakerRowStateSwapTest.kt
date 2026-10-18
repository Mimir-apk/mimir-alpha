package com.gruning.mimir.rewrite

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.gruning.mimir.rewrite.core.ui.MimirHintSwap
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Stepping profiles with D-pad left/right jittered on Nova. The profile row's value
 * had been wrapped in a swap keyed on its words, so every step between names started a crossfade and the next press
 * restarted it. Stepper values snap and tick; only the daemon's state changing (CHECKING... <-> a name) dissolves.
 *
 * A running swap is an Animatable on the frame clock, so the app is not idle until it settles; a snap is idle after its own
 * frame. The test measures how much test-clock time a state write keeps the app busy for, which needs no pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TweakerRowStateSwapTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setOnUiThread(write: () -> Unit) = compose.runOnUiThread {
        write()
        Snapshot.sendApplyNotifications()
    }

    private var label by mutableStateOf("BALANCED")
    private var pending by mutableStateOf(false)

    private fun show() {
        compose.setContent {
            TweakerRowStateSwap(answerPending = pending) {
                Text(label)
            }
        }
        compose.waitForIdle()
    }

    /**
     * How much of the test clock a state write keeps the app busy for. A running swap is an Animatable on the frame clock, so
     * the app is not idle until it settles; a snap is idle after the recomposition's own frame.
     */
    private fun clockSpentBy(write: () -> Unit): Long {
        val before = compose.mainClock.currentTime
        setOnUiThread(write)
        compose.waitForIdle()
        return compose.mainClock.currentTime - before
    }

    @Test
    fun `two quick steps between profile names leave no intermediate frames`() {
        show()

        val first = clockSpentBy { label = "PERFORMANCE" }
        val second = clockSpentBy { label = "QUIET" }

        assertTrue("a step snaps in the frame it lands: $first ms", first < MimirMotion.OverlayMs / 2)
        assertTrue("and so does the next, with nothing to restart: $second ms", second < MimirMotion.OverlayMs / 2)
        compose.onNodeWithText("QUIET").assertExists()
    }

    @Test
    fun `the daemon's state changing between CHECKING and a name does dissolve`() {
        show()

        val toChecking = clockSpentBy { pending = true; label = "CHECKING" }
        val toName = clockSpentBy { pending = false; label = "QUIET" }

        assertTrue("CHECKING arrives on the overlay clock, not in one frame: $toChecking ms", toChecking >= MimirMotion.OverlayMs / 2)
        assertTrue("and a name returns the same way: $toName ms", toName >= MimirMotion.OverlayMs / 2)
        compose.onNodeWithText("QUIET").assertExists()
    }

    /** The control: the swap this replaced, keyed on the words, does keep the clock busy on a plain step. */
    @Test
    fun `the words-keyed swap that caused the jitter does run on a step`() {
        compose.setContent { MimirHintSwap(key = label) { Text(label) } }
        compose.waitForIdle()

        val step = clockSpentBy { label = "PERFORMANCE" }

        assertTrue("the measurement sees a crossfade when there is one: $step ms", step >= MimirMotion.OverlayMs / 2)
    }

    @Test
    fun `a step while the answer is pending does not start a dissolve`() {
        show()
        clockSpentBy { pending = true; label = "CHECKING" }

        val step = clockSpentBy { label = "CHECKING TWO" } // a step preview while unanswered: same state, new words

        assertTrue("the state did not change, so there is no clock: $step ms", step < MimirMotion.OverlayMs / 2)
    }
}

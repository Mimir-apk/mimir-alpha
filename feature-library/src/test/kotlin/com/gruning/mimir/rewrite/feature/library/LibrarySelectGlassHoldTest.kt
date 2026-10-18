package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.rememberMimirGlassHold
import com.gruning.mimir.rewrite.core.ui.rememberMimirGlassProgress
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Observed on Thor: launch-option changes blinked. The Library card's glass
 * under the Open-with and disc/version pickers followed the SCOPE, so it began clearing on the frame the commit popped the scope,
 * while the picker was still half-visible: the card un-blurred under a fading picker (two full-screen blurs at once, 7-8.5 ms a
 * frame), and the dissolve-then-clear ordering was broken.
 *
 * **The hold is tested the way a press drives it.** Every Library action bumps `transitionCancellationEpoch` BEFORE it is handled
 * (`MimirAppInputDispatch`), so the press that commits a pick moves the hold's cancel key in the same frame it pops the picker's scope.
 * The first cut released the hold on "the key changed while holding", and the press that began the hold released it (review
 * 2026-10-14: the glass at .56 under a picker at .10, picker over sharp text). These compose the real primitives the way
 * `LibraryScreen` wires them and move the scope and the epoch in ONE snapshot write.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibrarySelectGlassHoldTest {

    @get:Rule
    val compose = createComposeRule()

    private var pickerScope by mutableStateOf(false)
    private var epoch by mutableLongStateOf(0L)
    private var glass: () -> Float = { 0f }
    private var heldNow = false

    @Before
    fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val held = rememberMimirGlassHold(requested = pickerScope, cancelKey = epoch)
            heldNow = held
            glass = rememberMimirGlassProgress(visible = held)
        }
        press { pickerScope = true }
        step(MimirMotion.OverlayMs * 2L)
        assertEquals("settled behind the open picker", 1f, glass(), 0.001f)
    }

    private fun press(block: () -> Unit) {
        Snapshot.withMutableSnapshot(block)
        Snapshot.sendApplyNotifications()
    }

    private fun step(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
    }

    @Test
    fun `the press that commits a pick moves the epoch in the frame it pops the scope, and the glass still holds through the dissolve`() {
        press {
            pickerScope = false
            epoch += 1L // the epoch bump that precedes the handling of every Library press
        }
        var t = 0L
        while (t < MimirMotion.OverlayDissolveCoverMs - 20L) {
            step(16L)
            t += 16L
            assertEquals("t=$t ms: the glass holds while the picker dissolves", 1f, glass(), 0.001f)
        }
        step(MimirMotion.OverlayMs * 3L)
        assertEquals("then it clears", 0f, glass(), 0.001f)
    }

    @Test
    fun `a press that arrives DURING the hold still cancels it`() {
        press {
            pickerScope = false
            epoch += 1L
        }
        step(48L)
        assertEquals(1f, glass(), 0.001f)
        press { epoch += 1L } // a real later action: the card chrome hold's rule, copied
        val trace = StringBuilder()
        var releasedAt = -1L
        for (t in 1..8) {
            step(16L)
            trace.append("held=$heldNow glass=%.3f; ".format(glass()))
            if (!heldNow && releasedAt < 0L) releasedAt = t * 16L
        }
        println("TTX hold-cancel trace: $trace")
        // An uncancelled hold would stand until its own 130 ms ran out (about 146 ms after the first press, 98 ms after this one).
        assertTrue("the second press released the hold within a few frames, not at the end of its 130 ms (released after $releasedAt ms): $trace", releasedAt in 1L..64L)
        step(MimirMotion.OverlayMs * 2L)
        assertEquals("and the glass is gone", 0f, glass(), 0.001f)
    }

    private fun screenSource(): String {
        val file = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
        ).firstOrNull(File::exists) ?: throw AssertionError("LibraryScreen.kt not reachable from ${File(".").absolutePath}")
        return file.readText()
    }

    @Test
    fun `the picker glass follows the held request, never the raw scope`() {
        val source = screenSource()
        val site = source.substringAfter("val selectGlassHeld = rememberMimirGlassHold(").substringBefore("initialArrivalFade =")
        assertTrue(
            "the request is the picker scope, the hold is the host's own and a later cancellation releases it",
            site.contains("requested = focusState.scope == OpenWithScope || focusState.scope == LaunchChoiceScope,") &&
                site.contains("cancelKey = transitionCancellationEpoch,"),
        )
        assertTrue(site.contains("val selectOverlayGlass = rememberMimirGlassProgress(visible = selectGlassHeld)"))
        assertFalse(
            "the glass must not be asked to clear on the raw scope: it would clear under the picker's fade",
            source.contains("rememberMimirGlassProgress(\n        visible = focusState.scope == OpenWithScope"),
        )
        assertTrue("the card is blurred by that one progress, held as a snapshot while the picker is up (Q5)", site.contains("progress = selectOverlayGlass,") && site.contains("glass = pickerCardGlass,"))
    }

    @Test
    fun `a card opening for a launch return is already the finished card`() {
        val source = screenSource()
        assertTrue(source.contains("initialArrivalFade = !libraryDetailArrivalSettled(viewModel.restoringLaunchReturnTarget()),"))
        assertTrue(source.contains("settleOpen = { libraryDetailArrivalSettled(viewModel.restoringLaunchReturnTarget()) },"))
    }

    @Test
    fun `only a pending return that restores the card settles the arrival`() {
        val restoring = LibraryLaunchReturnTarget(
            surface = LibraryLaunchReturnTarget.Surface.GAMELIST,
            shelfMode = LibraryShelfMode.EMULATION,
            openPlatformId = PlatformId.PS1,
            nativePackageName = "com.example.game",
            restoreLaunchCard = true,
        )
        assertTrue(libraryDetailArrivalSettled(restoring))
        assertFalse(libraryDetailArrivalSettled(restoring.copy(restoreLaunchCard = false)))
        assertFalse("an ordinary open has no pending return", libraryDetailArrivalSettled(null))
        assertEquals(false, libraryDetailArrivalSettled(null))
    }
}

package com.gruning.mimir.rewrite

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirPageMotion
import com.gruning.mimir.rewrite.core.ui.MimirSwapPageGate
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirTransitionInputGate
import com.gruning.mimir.rewrite.core.ui.mimirRingSafeFadeAlpha
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Alpha audit F1a (2026-09-30), the 15%-visibility input threshold for Quick Launch: the dock's first pinned slot is focused
 * the frame it mounts, and on a fade with no lead and no gate a second A launched an app nobody had seen (the dock stood at
 * 6% at 40 ms and 26% at 100 ms). The dock now arrives on the page tier, and the pad and the touch wait until it is above
 * [MimirPageMotion.ShownAlpha], about 200 ms after the press.
 *
 * Composes the real [HomeQuickLaunchOverlay] in the shape `MimirAppHost`'s `HomeQuickLaunchOverlayHost` gives it: the same
 * two spec functions on `fadeIn` and `fadeOut`, the panel keyed on the open generation, [MimirSwapPageGate] on a box around
 * it. (The host itself takes the app's bindings, which the JVM cannot build; OverlayArrivalInputGateTest pins that it
 * holds this exact shape.) The router is not stood up, so "the second A does nothing" is proved as the router does it: the
 * press is dropped while [MimirTransitionInputGate] is held, and reaches the dock's own reducer once it is not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w640dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeQuickLaunchArrivalGateTest {

    @get:Rule
    val compose = createComposeRule()

    private val open = mutableStateOf(false)
    private val authority = DefaultFocusAuthority()

    /** What the dock launched, in order. */
    private val launched = mutableListOf<String>()

    private val slots = listOf(
        HomeQuickLaunchSlotUi(index = 0, packageName = "pkg.zephyr", label = "Zephyr"),
        HomeQuickLaunchSlotUi(index = 1, packageName = null, label = null),
        HomeQuickLaunchSlotUi(index = 2, packageName = null, label = null),
        HomeQuickLaunchSlotUi(index = 3, packageName = null, label = "All apps", general = true),
    )

    @Before
    fun gateClock() {
        MimirTransitionInputGate.reset()
        MimirTransitionInputGate.clock = { compose.mainClock.currentTime }
    }

    @After
    fun resetGate() = MimirTransitionInputGate.reset()

    @Test
    fun `a second A during the arrival is dropped, and launches once the dock is above 15 percent`() {
        composeDock()
        assertFalse("a dock that is not open holds nothing", MimirTransitionInputGate.holding)
        setOpen(true)
        advance(100L - FrameMs)
        assertTrue("the second A at 100 ms is dropped: the gate is held", MimirTransitionInputGate.holding)
        pressA()
        assertEquals("nothing was launched from a dock nobody had seen", emptyList<String>(), launched)

        // 130 ms lead, then 15% of the 540 ms fade is about 71 ms in.
        advance(140L)
        assertFalse("the pad is free once the dock is above 15%", MimirTransitionInputGate.holding)
        pressA()
        assertEquals("and the same A now launches the focused first pinned slot", listOf("pkg.zephyr"), launched)
    }

    @Test
    fun `B during the arrival frees the pad at once`() {
        composeDock()
        setOpen(true)
        advance(60L)
        assertTrue(MimirTransitionInputGate.holding)
        setOpen(false)
        compose.mainClock.advanceTimeByFrame()
        assertFalse("Back always works and a dock that is going holds nothing", MimirTransitionInputGate.holding)
    }

    @Test
    fun `a tap on the slot takes nothing until the dock is above 15 percent, and launches after`() {
        composeDock()
        setOpen(true)
        advance(100L)
        assertEquals("the dock is at alpha 0 through the lead: a tap reaches nothing", emptyList<String>(), tapSlot())
        // About 180 ms in: 50 ms into the 540 ms fade, painted but at about 10%, short of the 15% that is 71 ms in.
        advance(50L)
        assertEquals("and a trace is not a dock", emptyList<String>(), tapSlot())
        settle()
        assertEquals(listOf("pkg.zephyr"), tapSlot())
    }

    @Test
    fun `a dock that is leaving takes no tap`() {
        composeDock()
        setOpen(true)
        settle()
        setOpen(false)
        advance(40L)
        assertEquals(emptyList<String>(), tapSlot(unmerged = true))
    }

    private fun composeDock() {
        val enterSpec = homeQuickLaunchEnterSpec(reducedMotion = false)
        val exitSpec = homeQuickLaunchExitSpec()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                AnimatedVisibility(visible = open.value, enter = fadeIn(enterSpec), exit = fadeOut(exitSpec)) {
                    key(0) {
                        val pageInput = MimirSwapPageGate(mimirRingSafeFadeAlpha(enterSpec, exitSpec), MimirPageMotion.ShownAlpha)
                        Box(Modifier.fillMaxSize().then(pageInput)) {
                            HomeQuickLaunchOverlay(
                                authority = authority,
                                slots = slots,
                                onLaunch = { launched += it },
                                onOpenGeneral = {},
                                onEdit = {},
                                onDismiss = {},
                            )
                        }
                    }
                }
            }
        }
        settle()
    }

    private fun setOpen(value: Boolean) {
        // A plain write from the test thread is not seen by a paused-clock recomposer until something idles.
        Snapshot.withMutableSnapshot { open.value = value }
        compose.mainClock.advanceTimeByFrame()
    }

    /** The router's rule: a press waits while the page change holds the gate, and otherwise reaches the dock's reducer. */
    private fun pressA() {
        if (MimirTransitionInputGate.holding) return
        dispatchHomeQuickLaunch(authority, slots, ControllerAction.CONFIRM, onLaunch = { launched += it }, onDismiss = {})
    }

    private fun tapSlot(unmerged: Boolean = false): List<String> {
        val before = launched.size
        compose.onNodeWithContentDescription("Zephyr", useUnmergedTree = unmerged).performTouchInput { click() }
        compose.mainClock.advanceTimeByFrame()
        return launched.drop(before)
    }

    private fun advance(ms: Long) = compose.mainClock.advanceTimeBy(ms)

    private fun settle() = advance(MimirMotion.ShineMs * 3L)

    private companion object {
        const val FrameMs = 16L
    }
}

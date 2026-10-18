package com.gruning.mimir.rewrite

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.down
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.up
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.performTouchInput
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityAvailability
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirTransitionInputGate
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
 * Utility panel input isolation: the Utility panel's surface swap keeps a copy of the outgoing surface while it
 * fades, and that copy clears its semantics and consumes every pointer event at the Initial pass (`BrightnessOverlay.kt`,
 * the `outgoingInput` of `brightness-surface-transition`, since alpha audit F1c core-ui's `MimirSwapPageGate`, which does
 * the same for the departing copy and also gates the arriving one). The suspicion was that such a copy could outlive its fade,
 * or hand its consume-all block to the live panel, and leave the live panel painting but deaf.
 *
 * These tests compose the real [MimirBrightnessOverlay] as the Bottom host does (no controller hints, no BackHandler)
 * under Robolectric with native graphics, drive the surface on a paused clock, and inject down/up pairs through the
 * root at the footer's measured centre. Semantics clicks would skip the hit test, which is the thing in question.
 *
 * What they prove, on the JVM with Compose 1.7.3: a copy fading out above the live surface takes no tap; the live
 * surface answers once that copy is gone; a settled swap leaves no copy behind; and interrupting the swap in either
 * direction, with taps landing on the outgoing copy, leaves the live footer answering. They do not reach the
 * Presentation's own panel-kind AnimatedContent, which is inline in `MimirCompanionPresentation.onCreate`, and they say
 * nothing about the Thor.
 *
 * Motion audit batch A (2026-09-28) made the swap sequential: the old surface fades out on 180 ms and the new one fades
 * in from the 130 ms lead ([MimirSwapLeadMs] below). So a copy is only ever "fading out above the live surface" once the
 * incoming surface has started to show; the interrupted swaps here reverse after that point. A reversal inside the
 * lead drops the never-shown copy at once (Compose interrupts an exit with a spring from the painted alpha, which is
 * 0), and the live surface, which is on screen, answers the tap: pinned by its own test below.
 *
 * Back to the Utility is a return (review of 89d626d38): the Utility is at rest at once, under the Tweaker copy that
 * dissolves over it on MimirPageMotion.ReturnMs. That copy takes no tap either.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w640dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UtilitySurfaceTransitionTouchTest {

    @get:Rule
    val compose = createComposeRule()

    private var surface by mutableStateOf(UtilitySurface.UTILITY)

    @Before
    fun gateClock() {
        MimirTransitionInputGate.reset()
        MimirTransitionInputGate.clock = { compose.mainClock.currentTime }
    }

    @After
    fun resetGate() = MimirTransitionInputGate.reset()

    /** What each footer tap reached, in order: "back" or "close". */
    private val reached = mutableListOf<String>()

    private fun composePanel(initial: UtilitySurface, beneath: Boolean = false) {
        surface = initial
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                if (beneath) {
                    // The shelf under an in-app Utility: a sibling the scrim must keep from being touched.
                    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { reached += "beneath" }) })
                }
                MimirBrightnessOverlay(
                    state = utilityPanelState(),
                    focusedControl = null,
                    onSetFraction = { _, _ -> },
                    onSetThemeAwareJoystickLight = {},
                    onRequestWriteAccess = {},
                    onRequestPrivilegedAccess = {},
                    onOpenTweaker = {},
                    onOpenFiles = {},
                    filesRouteAvailable = true,
                    allFilesAccessGranted = true,
                    tweakerRow = DefaultTweakerUtilityRow.copy(availability = TweakerUtilityAvailability.READY),
                    surface = surface,
                    onBack = { reached += "back" },
                    onClose = { reached += "close" },
                    // As the coordinator words it for a Tweaker opened from the Utility (backActionLabel).
                    backActionLabel = if (surface == UtilitySurface.TWEAKER) "Back" else "Close",
                    controllerHintsAvailable = false,
                    enableBackHandler = false,
                )
            }
        }
        settle()
    }

    @Test
    fun `a surface fading out above the live one takes no tap, and the live surface answers once it is gone`() {
        composePanel(UtilitySurface.UTILITY)
        val close = footerCentre("Close")

        // Utility -> Tweaker, then back once the Tweaker has started to show: the Tweaker copy is now the one fading
        // out, and it stays above the live Utility because an interrupted AnimatedContent does not reorder its children.
        show(UtilitySurface.TWEAKER)
        advance(MimirSwapLeadMs + 60)
        show(UtilitySurface.UTILITY)
        advance(40)

        assertEquals("a tap on a surface that is fading out must reach nothing", emptyList<String>(), tapAt(close))

        settle()
        assertEquals(
            "once that copy is gone the live Utility's Close must answer",
            listOf("close"),
            tapAt(close),
        )
    }

    @Test
    fun `a settled swap leaves no copy, and after interrupted swaps with taps on the outgoing copy the live footer answers`() {
        composePanel(UtilitySurface.TWEAKER)
        val back = footerCentre("Back")
        show(UtilitySurface.UTILITY)
        settle()
        val close = footerCentre("Close")

        // Utility live: Tweaker in, Utility back, a tap lands on the Tweaker copy fading out on top, then Tweaker again.
        // The Tweaker copy is only on top if the settled swap above left nothing behind: a Tweaker copy still composed
        // from it would be revived underneath the Utility, and this tap would land on the live Utility instead.
        show(UtilitySurface.TWEAKER)
        advance(MimirSwapLeadMs + 60)
        show(UtilitySurface.UTILITY)
        advance(20)
        val onOutgoingTweaker = tapAt(back)
        show(UtilitySurface.TWEAKER)
        settle()
        assertEquals(
            "the tap on the outgoing Tweaker copy must reach nothing (a copy left by the settled swap reorders them)",
            emptyList<String>(),
            onOutgoingTweaker,
        )
        assertEquals("the live Tweaker's Back must answer after the interrupted swaps", listOf("back"), tapAt(back))

        // And back to the Utility, now a return: the Utility at rest at once, under the Tweaker copy dissolving over it
        // on the return clock. A tap on that copy reaches nothing; the Utility answers once it is gone.
        show(UtilitySurface.UTILITY)
        advance(20)
        val onReturningTweaker = tapAt(close)
        settle()
        assertEquals(
            "the tap on the Tweaker copy dissolving over the Utility must reach nothing",
            emptyList<String>(),
            onReturningTweaker,
        )
        assertEquals("the live Utility's Close must answer after the interrupted swaps", listOf("close"), tapAt(close))
    }

    @Test
    fun `a swap reversed before the new surface shows drops that copy at once, and the live surface answers`() {
        composePanel(UtilitySurface.UTILITY)

        // Inside the 130 ms lead the Tweaker has painted nothing; reversing leaves only the Utility on screen.
        show(UtilitySurface.TWEAKER)
        advance(40)
        show(UtilitySurface.UTILITY)
        advance(40)

        // The Utility glides back into place on a return, so its footer is read where it is now.
        val close = footerCentre("Close")
        assertEquals("the Utility on screen answers its own Close", listOf("close"), tapAt(close))
    }

    /**
     * During the Utility -> Tweaker swap, the Tweaker stands at alpha 0 through the
     * 130 ms lead with its CONFIRM already live, so an A inside it applied a pending draft nobody had seen. The pad's half
     * is [MimirTransitionInputGate] (the router is not stood up here, so "CONFIRM at 100 ms does nothing" is proved as
     * "the gate is held at 100 ms"); touch bypasses the router, so its half is a real hit-tested tap.
     */
    @Test
    fun `the arriving Tweaker holds the pad and takes no tap until it paints, and answers once it has`() {
        composePanel(UtilitySurface.UTILITY)
        assertFalse("the surface the panel opens on is not a page change", MimirTransitionInputGate.holding)
        show(UtilitySurface.TWEAKER)
        advance(100L - 16L)
        assertTrue("an A at 100 ms must not reach the Tweaker", MimirTransitionInputGate.holding)
        // The arriving Tweaker keeps its semantics (the departing Utility copy clears its own), so its footer is found.
        val back = footerCentre("Back")
        assertEquals("a tap on the Tweaker while it is at alpha 0 reaches nothing", emptyList<String>(), tapAt(back))

        advance(MimirSwapLeadMs + 60L)
        assertFalse("the pad is free once the Tweaker shows", MimirTransitionInputGate.holding)
        settle()
        // The panels differ in height and the swap resizes on its clock, so the footer is read where it settled.
        assertEquals("and the Tweaker answers", listOf("back"), tapAt(footerCentre("Back")))
    }

    @Test
    fun `Back inside the lead frees the pad at once and the Utility answers`() {
        composePanel(UtilitySurface.UTILITY)
        show(UtilitySurface.TWEAKER)
        advance(50L)
        assertTrue(MimirTransitionInputGate.holding)
        show(UtilitySurface.UTILITY)
        compose.mainClock.advanceTimeByFrame()
        assertFalse("a reversed swap must not leave the pad deaf", MimirTransitionInputGate.holding)
        settle()
        assertEquals(listOf("close"), tapAt(footerCentre("Close")))
    }

    @Test
    fun `Back from a settled Tweaker shows the Utility at once and frees the pad within a few frames`() {
        composePanel(UtilitySurface.UTILITY)
        show(UtilitySurface.TWEAKER)
        settle()
        show(UtilitySurface.UTILITY)
        advance(3 * 16L)
        assertFalse(
            "a return paints its page at once (review of 89d626d38), so the pad must not wait out the lead",
            MimirTransitionInputGate.holding,
        )
        settle()
        assertEquals(listOf("close"), tapAt(footerCentre("Close")))
    }

    /** A finger drifts a few pixels between down and up; that is still a tap (the catch-all used to cancel it). */
    private fun tapWithDriftAt(point: Offset): List<String> {
        val before = reached.size
        compose.onRoot().performTouchInput {
            down(point)
            moveBy(Offset(2f, 1f))
            moveBy(Offset(1f, 2f))
            up()
        }
        advance(50)
        return reached.drop(before)
    }

    @Test
    fun `a tap that drifts a few pixels still activates the footer`() {
        composePanel(UtilitySurface.UTILITY)
        settle()
        assertEquals(listOf("close"), tapWithDriftAt(footerCentre("Close")))
    }

    @Test
    fun `a tap on the scrim, motionless or drifting, closes the panel and never reaches the surface beneath`() {
        composePanel(UtilitySurface.UTILITY, beneath = true)
        settle()
        val corner = Offset(4f, 4f)
        assertEquals(listOf("back"), tapAt(corner))
        assertEquals(listOf("back"), tapWithDriftAt(corner))
    }

    private fun show(target: UtilitySurface) {
        // A plain write from the test thread is not seen by a paused-clock recomposer until something idles.
        Snapshot.withMutableSnapshot { surface = target }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun advance(ms: Long) = compose.mainClock.advanceTimeBy(ms)

    private fun settle() = advance(MimirMotion.OverlayMs * 8L)

    private companion object {
        /** When the incoming surface starts to show: the swap tier's lead. */
        val MimirSwapLeadMs = MimirMotion.OverlayDissolveCoverMs.toLong()
    }

    /** The live footer's centre. A copy that is fading out clears its semantics; the live surface must not. */
    private fun footerCentre(label: String): Offset {
        val matches = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { node ->
            val words = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            words.any { it.trim().equals(label, ignoreCase = true) }
        }
        assertEquals(
            "the live surface must keep exactly one footer labelled $label in the semantics tree; " +
                "only a copy that is fading out clears its own",
            1,
            matches.size,
        )
        return matches.single().boundsInRoot.center
    }

    private fun tapAt(point: Offset): List<String> {
        val before = reached.size
        compose.onRoot().performTouchInput {
            down(point)
            up()
        }
        advance(50)
        return reached.drop(before)
    }

    /** UtilityChoiceRowTouchTest's fixture, with privileged access READY. */
    private fun utilityPanelState(): BrightnessState = BrightnessState(
        capabilities = BrightnessCapabilities(
            canWriteSystemSettings = true,
            mainSystemBrightnessActive = true,
            secondaryWindowAvailable = false,
            secondarySupport = SecondaryBrightnessSupport.UNAVAILABLE,
            joystickLightSupported = false,
            joystickLightColorSupported = false,
            mimirHapticsSupported = false,
            mimirHapticAmplitudeControl = false,
            mediaVolumeSupported = false,
            mediaVolumeFixed = false,
            mainWriteAccessUseful = true,
            privilegedUtilityPosture = PrivilegedUtilityPosture(PrivilegedUtilityStatus.READY),
            navSoundSupported = false,
            ambientSoundSupported = false,
        ),
        primaryFraction = 0.5f,
        primaryWindowFallback = null,
        secondaryFraction = null,
        secondaryWindowFallback = null,
        joystickLightFraction = null,
        joystickColorFraction = null,
        joystickColorArgb = null,
        themeAwareJoystickLight = false,
        mimirHapticFraction = null,
        mediaVolumeFraction = null,
        themeAwareJoystickLightRequested = false,
        themeAwareJoystickLightRestorePending = false,
        ambientSoundFraction = null,
    )
}

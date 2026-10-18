package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.center
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** D34b: the trackpad's pure parts (acceleration, gesture machine, clamp, capability, layout) and its keyboard wiring. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirTrackpadTest {

    @get:Rule
    val compose = createComposeRule()

    /** Records every call the machine makes on its host, in order, as readable strings. */
    private class FakeHost(override var serviceConnected: Boolean = true) : MimirTrackpadHost {
        val calls = mutableListOf<String>()
        var begun = 0
        var ended = 0
        var settingsOpened = 0
        var padOpened = 0
        val scrollDys = mutableListOf<Float>()
        val moveDxs = mutableListOf<Float>()
        var backs = 0
        var live = true
        var speed = TrackpadSpeed.Default
        override val sessionLive: Boolean get() = live
        override val speedStep: Int get() = speed
        override fun setSpeed(step: Int): Int {
            speed = TrackpadSpeed.clamp(step)
            return speed
        }
        override fun back() { backs++ }
        override fun begin(): MimirTrackpadHost {
            begun++
            return this
        }
        override fun pointerMove(dx: Float, dy: Float) { calls += "move"; moveDxs += dx }
        override fun click() { calls += "click" }
        override fun doubleClick() { calls += "double" }
        override fun scrollBy(dx: Float, dy: Float) { calls += "scroll"; scrollDys += dy }
        override fun scrollEnd() { calls += "scrollEnd" }
        override fun scrollCancel() { calls += "scrollCancel" }
        override fun holdBegin() { calls += "holdBegin" }
        override fun holdMove(dx: Float, dy: Float) { calls += "holdMove" }
        override fun holdEnd() { calls += "holdEnd" }
        override fun openAccessibilitySettings() { settingsOpened++ }
        override fun end() { ended++ }
        override fun openPad() { padOpened++ }
    }

    private fun machine(host: FakeHost = FakeHost()) = TrackpadGestureMachine(host, slopPx = 12f) to host

    // ---- acceleration -------------------------------------------------------------------------------------------

    @Test
    fun accelerationRunsFromOnePointTwoXToFourXMonotonicallyAndIsCapped() {
        assertEquals(1.2f, PointerAcceleration.gain(0f), 0.0001f)
        assertEquals(4f, PointerAcceleration.gain(1_000f), 0.0001f)
        var last = 0f
        var speed = 0f
        while (speed <= 3f) {
            val gain = PointerAcceleration.gain(speed)
            assertTrue("monotonic at $speed", gain >= last)
            assertTrue("within 1.2..4 at $speed: $gain", gain in 1.2f..4f)
            last = gain
            speed += 0.01f
        }
        assertEquals("negative and NaN speeds are slow", 1.2f, PointerAcceleration.gain(-3f), 0.0001f)
        assertEquals(1.2f, PointerAcceleration.gain(Float.NaN), 0.0001f)
    }

    // ---- the gesture machine ------------------------------------------------------------------------------------

    @Test
    fun aQuickUnmovedTouchIsAClickAndAFastSecondIsADoubleClick() {
        val (m, host) = machine()
        m.down(1, 100f, 100f, 0)
        m.up(1, 100)
        assertEquals(listOf("click"), host.calls)
        m.down(1, 100f, 100f, 250)
        m.up(1, 330)
        assertEquals(listOf("click", "double"), host.calls)
        // A third quick tap starts over: it is a plain click, not another double.
        m.down(1, 100f, 100f, 400)
        m.up(1, 460)
        assertEquals(listOf("click", "double", "click"), host.calls)
    }

    @Test
    fun aSecondTapTooLateIsAnotherPlainClick() {
        val (m, host) = machine()
        m.down(1, 0f, 0f, 0); m.up(1, 50)
        m.down(1, 0f, 0f, 500); m.up(1, 550)
        assertEquals(listOf("click", "click"), host.calls)
    }

    @Test
    fun aTouchHeldPastTheTapWindowButUnmovedIsNeitherClickNorMove() {
        val (m, host) = machine()
        m.down(1, 0f, 0f, 0)
        m.up(1, 300)
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun movementUnderTheSlopIsStillATapAndOverItMovesThePointer() {
        val (m, host) = machine()
        m.down(1, 100f, 100f, 0)
        m.move(1, 106f, 104f, 20)
        m.up(1, 60)
        assertEquals("a wobble under the slop is a click", listOf("click"), host.calls)

        val (m2, host2) = machine()
        m2.down(1, 100f, 100f, 0)
        m2.move(1, 130f, 100f, 16)
        m2.move(1, 160f, 100f, 32)
        m2.up(1, 50)
        assertEquals(listOf("move", "move"), host2.calls)
    }

    @Test
    fun aStillFingerHoldsAfterTheLongPressThenDragsThenReleases() {
        val (m, host) = machine()
        m.down(1, 100f, 100f, 0)
        m.tick(300)
        assertTrue("not yet", host.calls.isEmpty())
        m.tick(451)
        assertEquals(listOf("holdBegin"), host.calls)
        m.move(1, 140f, 100f, 470)
        m.up(1, 500)
        assertEquals(listOf("holdBegin", "holdMove", "holdEnd"), host.calls)
    }

    @Test
    fun aHoldReleasedUnmovedIsALongPressAndMovementBeforeTheDelayCancelsIt() {
        val (m, host) = machine()
        m.down(1, 0f, 0f, 0)
        m.tick(460)
        m.up(1, 700)
        assertEquals(listOf("holdBegin", "holdEnd"), host.calls)

        val (m2, host2) = machine()
        m2.down(1, 0f, 0f, 0)
        m2.move(1, 50f, 0f, 100)
        m2.tick(600)
        assertEquals("a finger that moved never becomes a hold", listOf("move"), host2.calls)
        assertFalse(m2.longPressPending)
    }

    @Test
    fun twoFingersDragAScrollSentOnceOnLift() {
        val (m, host) = machine()
        m.down(1, 100f, 100f, 0)
        m.down(2, 160f, 100f, 5)
        m.move(1, 100f, 140f, 20)
        m.move(2, 160f, 140f, 22)
        m.move(1, 100f, 180f, 40)
        m.up(2, 60)
        m.up(1, 70)
        assertEquals(listOf("scroll", "scroll", "scroll", "scrollEnd"), host.calls)
        assertFalse("a scroll is never also a click", "click" in host.calls)
    }

    @Test
    fun twoFingersThatBarelyMoveAreNotAScrollAndNotAClick() {
        val (m, host) = machine()
        m.down(1, 0f, 0f, 0)
        m.down(2, 50f, 0f, 3)
        m.move(1, 0f, 3f, 20)
        m.up(2, 40)
        m.up(1, 50)
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun aStrayFingerNeverChangesAHoldOrAScroll() {
        val (m, host) = machine()
        m.down(1, 100f, 100f, 0)
        m.tick(460)
        m.down(2, 300f, 300f, 480)
        m.move(2, 340f, 300f, 500)
        m.up(2, 520)
        assertEquals("the stray finger moves nothing", listOf("holdBegin"), host.calls)
        m.up(1, 600)
        assertEquals(listOf("holdBegin", "holdEnd"), host.calls)

        val (m2, host2) = machine()
        m2.down(1, 0f, 0f, 0)
        m2.down(2, 50f, 0f, 3)
        m2.down(3, 90f, 0f, 6)
        m2.move(3, 90f, 80f, 20)
        assertTrue("a third finger is ignored", host2.calls.isEmpty())
    }

    @Test
    fun afterAScrollTheRemainingFingerDoesNothingUntilAllAreUp() {
        val (m, host) = machine()
        m.down(1, 0f, 0f, 0); m.down(2, 50f, 0f, 2)
        m.move(1, 0f, 60f, 10)
        m.up(2, 20)
        m.move(1, 40f, 60f, 30)
        m.up(1, 40)
        assertEquals(listOf("scroll", "scrollEnd"), host.calls)
        m.down(1, 0f, 0f, 1000); m.up(1, 1040)
        assertEquals("the next touch works normally", "click", host.calls.last())
    }

    @Test
    fun cancelReleasesAHeldPressAndDropsAScroll() {
        val (m, host) = machine()
        m.down(1, 0f, 0f, 0)
        m.tick(470)
        m.cancel()
        assertEquals(listOf("holdBegin", "holdEnd"), host.calls)

        val (m2, host2) = machine()
        m2.down(1, 0f, 0f, 0); m2.down(2, 50f, 0f, 2)
        m2.move(1, 0f, 80f, 20)
        m2.cancel()
        assertEquals(listOf("scroll", "scrollCancel"), host2.calls)
        m2.up(1, 100)
        assertEquals("a late lift after cancel is ignored", listOf("scroll", "scrollCancel"), host2.calls)
    }

    // ---- the pointer clamp --------------------------------------------------------------------------------------

    @Test
    fun thePointerStaysInsideTheDisplayAndIsReclampedOnRotation() {
        val centre = MimirPointerPosition.centre(1920, 1080)
        assertEquals(MimirPointerPosition(960f, 540f), centre)
        assertEquals(MimirPointerPosition(1919f, 1079f), centre.moved(99_999f, 99_999f, 1920, 1080))
        assertEquals(MimirPointerPosition(0f, 0f), centre.moved(-99_999f, -99_999f, 1920, 1080))
        // Rotated to portrait: a pointer in the landscape's far corner is pulled back inside.
        val corner = MimirPointerPosition(1919f, 1079f)
        assertEquals(MimirPointerPosition(1079f, 1079f), corner.reclamped(1080, 1920))
        assertEquals(MimirPointerPosition(0f, 0f), corner.reclamped(0, 0))
    }

    // ---- capability and layout ----------------------------------------------------------------------------------

    @Test
    fun theCapabilityNeedsApi32AndAPresentationDisplayAndThePosture() {
        for (sdk in listOf(30, 31, 32, 33, 35)) {
            for (display in listOf(false, true)) {
                for (soc in listOf(false, true)) {
                    assertEquals(
                        "sdk=$sdk display=$display soc=$soc",
                        sdk >= 32 && display && soc,
                        mimirTrackpadCapability(sdk, display, soc),
                    )
                }
            }
        }
    }

    @Test
    fun theLayoutIsUnchangedWithoutATrackpadAndGainsOneKeyBesidePasteWithOne() {
        for (set in MimirKeyboardCharacterSet.entries) {
            val plain = keyboardRows(shiftOn = false, characterSet = set)
            assertEquals(plain, keyboardRows(shiftOn = false, characterSet = set, trackpad = false))
            assertTrue(plain.flatten().none { it.action == KeyboardAction.Trackpad })

            val withPad = keyboardRows(shiftOn = false, characterSet = set, trackpad = true)
            assertEquals(1, withPad.flatten().count { it.action == KeyboardAction.Trackpad })
            val bottom = withPad.last().map { it.action }
            val paste = bottom.indexOf(KeyboardAction.Paste)
            assertEquals("beside Paste", KeyboardAction.Trackpad, bottom[paste + 1])
            assertEquals(plain.size, withPad.size)
        }
        assertEquals(
            keyboardRows(shiftOn = false, characterSet = MimirKeyboardCharacterSet.ALPHA).flatten().size + 1,
            keyboardRows(shiftOn = false, characterSet = MimirKeyboardCharacterSet.ALPHA, trackpad = true).flatten().size,
        )
        assertEquals(
            "the controller's row layout carries the key too",
            true,
            mimirKeyboardLayoutRows(MimirKeyboardCharacterSet.ALPHA, trackpad = true).flatten().contains("action/trackpad"),
        )
    }

    @Test
    fun noSecretPathOffersTheTrackpad() {
        val dir = listOf(File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui"), File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui"))
            .first(File::isDirectory)
        fun code(name: String) = File(dir, name).readText()
            .replace(Regex("/[*].*?[*]/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//.*"), "")
        val secret = code("MimirSecretKeyboard.kt")
        assertFalse("the secret keyboard takes no trackpad", secret.contains("MimirTrackpadHost"))
        assertFalse(secret.contains("trackpad ="))
        val keyboard = code("MimirKeyboard.kt")
        assertTrue(keyboard.contains("val trackpadOffered = trackpad != null && !secret && secretEdit == null"))
        assertFalse("the public overloads have no secret flag to branch on", keyboard.contains("trackpad = if (secret)"))
        assertTrue("and pass the trackpad through to an ordinary surface", keyboard.contains("trackpad = trackpad,"))
    }

    @Test
    fun theConsentCopyIsTheOwnerApprovedTextVerbatim() {
        assertEquals("Turn on the trackpad", MimirTrackpadCopy.Title)
        assertEquals(
            "The trackpad needs Android's Accessibility permission so Mimir can tap and swipe on your main screen " +
                "for you. Mimir uses it only to place taps and swipes where your pointer is, and only while the " +
                "trackpad is on. It does not read your screen, your typing or your passwords, and nothing leaves the device.",
            MimirTrackpadCopy.Body,
        )
        assertEquals("Open Accessibility settings", MimirTrackpadCopy.OpenSettings)
        assertEquals("Not now", MimirTrackpadCopy.NotNow)
        assertEquals(
            "If the switch is greyed out, open Mimir's App info, tap the three dots, choose Allow restricted " +
                "settings, then try again.",
            MimirTrackpadCopy.GreyedOutHint,
        )
    }

    // ---- the keyboard wiring ------------------------------------------------------------------------------------

    @Test
    fun noKeyShowsWithoutAHost() {
        compose.setContent { MimirTheme { MimirKeyboard(value = "", onValueChange = {}, onDone = {}) } }
        compose.onAllNodesWithText("PAD", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun aKeyTapWithTheServiceOffShowsTheConsentCard() {
        val off = FakeHost(serviceConnected = false)
        compose.setContent { MimirTheme { MimirKeyboard(value = "", onValueChange = {}, onDone = {}, trackpad = off) } }
        compose.onNodeWithContentDescription("Trackpad").performClick()
        compose.onNodeWithContentDescription(MimirTrackpadCopy.Title, useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription(MimirTrackpadCopy.OpenSettings).performClick()
        assertEquals(1, off.settingsOpened)
        assertEquals("no pointer for a service that is off", 0, off.begun)
    }

    @Test
    fun withTheServiceOnThePadKeyOpensTheWindowAndTheKeyboardStaysAsItWas() {
        val host = FakeHost(serviceConnected = true)
        compose.setContent { MimirTheme { MimirKeyboard(value = "", onValueChange = {}, onDone = {}, trackpad = host) } }
        compose.onNodeWithContentDescription("Trackpad").performClick()
        assertEquals("the PAD key asks the host to open the full-screen window", 1, host.padOpened)
        assertEquals("no activation is begun by the keyboard itself", 0, host.begun)
        compose.onAllNodesWithText(MimirTrackpadCopy.KeysStrip, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("Trackpad").assertExists()
    }

    // ---- the full-screen pad ------------------------------------------------------------------------------------

    private fun showPad(
        host: FakeHost,
        waiting: Boolean = false,
        onKeyboard: () -> Unit = {},
        onClose: () -> Unit = {},
        probe: (() -> Unit)? = null,
    ) {
        compose.setContent {
            MimirTheme {
                MimirTrackpadPad(
                    session = host,
                    keyboardWaiting = waiting,
                    onKeyboard = onKeyboard,
                    onClose = onClose,
                    recompositionProbe = probe,
                )
            }
        }
        compose.onNodeWithTag(MimirTrackpadPadFieldTag, useUnmergedTree = true).assertExists()
    }

    @Test
    // pad8: the header now carries the Slow-to-Fast gauge, which needs the Bottom's own width (the pad window is the 537 dp Thor screen); a 320 dp window squeezes the last plate away.
    @Config(qualifiers = "w600dp-h500dp")
    fun theHeaderCarriesTheTitleAndTheKeyboardAndClosePlates() {
        var closed = 0
        var keyboard = 0
        showPad(FakeHost(), onKeyboard = { keyboard++ }, onClose = { closed++ })
        compose.onNodeWithContentDescription(MimirTrackpadCopy.KeyboardPlate).assertExists().performClick()
        compose.onNodeWithContentDescription(MimirTrackpadCopy.CloseDescription).assertExists().performClick()
        assertEquals(1, keyboard)
        assertEquals(1, closed)
        // pad6: Back on the left and the speed beside the Keyboard plate (pad8: the Slow-to-Fast gauge, level 3 of 5 by default).
        compose.onNodeWithContentDescription(MimirTrackpadCopy.BackDescription).assertExists()
        compose.onNodeWithTag(MimirTrackpadSpeedGaugeTag, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "3 of 5"))
        compose.onAllNodes(hasText(MimirTrackpadCopy.Legend, ignoreCase = true) or hasContentDescription(MimirTrackpadCopy.Legend), useUnmergedTree = true)
            .assertCountEquals(1)
    }

    @Test
    fun theKeyboardPlateLightsWhenAFieldIsWaitingAndIsIdleOtherwise() {
        showPad(FakeHost(), waiting = true)
        compose.onNodeWithContentDescription(MimirTrackpadCopy.KeyboardPlate)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "ready"))
    }

    @Test
    fun theKeyboardPlateIsIdleWithNoFieldWaiting() {
        showPad(FakeHost(), waiting = false)
        compose.onNodeWithContentDescription(MimirTrackpadCopy.KeyboardPlate)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "idle"))
    }

    @Test
    fun aRealTapOnTheFieldClicks() {
        val host = FakeHost()
        showPad(host)
        compose.onNodeWithTag(MimirTrackpadPadFieldTag, useUnmergedTree = true).performTouchInput { down(center); up() }
        compose.waitForIdle()
        assertEquals(listOf("click"), host.calls)
    }

    @Test
    fun aCancelledTouchIsNeitherAClickNorALift() {
        val host = FakeHost()
        showPad(host)
        compose.onNodeWithTag(MimirTrackpadPadFieldTag, useUnmergedTree = true).performTouchInput { down(center); cancel() }
        compose.waitForIdle()
        assertFalse("a cancelled touch must not click: ${host.calls}", host.calls.contains("click"))
        assertFalse(host.calls.contains("double"))
    }

    @Test
    fun aCancelledTwoFingerDragSendsNoSwipe() {
        val host = FakeHost()
        showPad(host)
        compose.onNodeWithTag(MimirTrackpadPadFieldTag, useUnmergedTree = true).performTouchInput {
            down(0, center)
            down(1, center + Offset(40f, 0f))
            moveBy(0, Offset(0f, 120f))
            cancel()
        }
        compose.waitForIdle()
        assertTrue("the drag was seen: ${host.calls}", host.calls.contains("scroll"))
        assertFalse("a cancelled drag must not send its swipe: ${host.calls}", host.calls.contains("scrollEnd"))
    }

    @Test
    fun theMillScrollsWithOneFingerAndSendsTheStrokeOnLift() {
        val host = FakeHost()
        showPad(host)
        compose.onNodeWithTag(MimirTrackpadPadDrumTag, useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(Offset(0f, -60f))
            moveBy(Offset(0f, 30f))
            // A pause before the lift: this is a drag that stopped, not a flick.
            advanceEventTime(300)
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("scroll", "scroll", "scrollEnd"), host.calls)
        assertTrue(
            "an upward finger sends a negative dy, a downward one a positive: ${host.scrollDys}",
            host.scrollDys[0] < 0f && host.scrollDys[1] > 0f,
        )
        assertTrue("the mill never moves the pointer", host.calls.none { it == "move" })
    }

    @Test
    fun aCancelledMillTouchSendsNoStroke() {
        val host = FakeHost()
        showPad(host)
        compose.onNodeWithTag(MimirTrackpadPadDrumTag, useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(Offset(0f, 40f))
            cancel()
        }
        compose.waitForIdle()
        assertFalse(host.calls.contains("scrollEnd"))
        assertTrue(host.calls.contains("scrollCancel"))
    }

    @Test
    fun leavingCompositionDuringAHoldReleasesItButNeverEndsTheActivation() {
        val host = FakeHost()
        var shown by mutableStateOf(true)
        compose.setContent {
            MimirTheme {
                if (shown) MimirTrackpadPad(session = host, keyboardWaiting = false, onKeyboard = {}, onClose = {})
            }
        }
        compose.onNodeWithTag(MimirTrackpadPadFieldTag, useUnmergedTree = true).performTouchInput { down(center) }
        // The long-press clock reads android.os.SystemClock; Robolectric's only moves when told to.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(700))
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertTrue("the long press began a hold: ${host.calls}", host.calls.contains("holdBegin"))
        shown = false
        compose.waitForIdle()
        assertTrue("the held press was released: ${host.calls}", host.calls.contains("holdEnd"))
        assertEquals("the window, not the composable, ends the activation", 0, host.ended)
    }

    @Test
    fun aFiftyMoveDragRecomposesAtMostTwice() {
        val host = FakeHost()
        var recompositions = 0
        showPad(host, probe = { recompositions++ })
        compose.waitForIdle()
        val before = recompositions
        compose.onNodeWithTag(MimirTrackpadPadFieldTag, useUnmergedTree = true).performTouchInput {
            down(center)
            repeat(50) { moveBy(Offset(3f, 2f)) }
            up()
        }
        compose.waitForIdle()
        val during = recompositions - before
        assertTrue("a 50-move drag recomposed $during times (budget 2)", during <= 2)
        assertTrue("the moves reached the host", host.calls.count { it == "move" } >= 40)
    }

    // ---- the keyboard fills the Bottom (K2: normal keys plus a tool row) ---------------------------------------------

    private fun mountFilled(width: Int = 537, height: Int = 468, fill: Boolean = true, onValue: (TextFieldValue) -> Unit = {}, value: TextFieldValue = TextFieldValue(""), onHide: (() -> Unit)? = null, onDone: () -> Unit = {}) {
        compose.setContent {
            MimirTheme {
                Box(Modifier.size(width.dp, height.dp)) {
                    MimirKeyboard(
                        value = value,
                        onValueChange = onValue,
                        onDone = onDone,
                        trackpad = FakeHost(),
                        fill = fill,
                        onHide = onHide,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    private fun dp(px: Float) = px / compose.density.density

    @Test
    @Config(qualifiers = "w600dp-h500dp")
    fun onA537By468BottomTheKeysAreNormalNeverStretchedAndAtLeastTheTouchMinimum() {
        mountFilled()
        // The hit Box of a key is the drawn key plus the gap (4 dp in this compact layout); read it off the "Q" key.
        val q = compose.onNodeWithContentDescription("Q").fetchSemanticsNode()
        val gap = 4f
        val keyHeight = dp(q.size.height.toFloat()) - gap
        val keyWidth = dp(q.size.width.toFloat()) - gap
        println("K2-RENDER 537x468 fill: key ${"%.2f".format(keyWidth)} x ${"%.2f".format(keyHeight)} dp, aspect ${"%.3f".format(keyHeight / keyWidth)}")
        assertTrue("key height $keyHeight must be at least the 48 dp touch minimum", keyHeight >= 47.9f)
        assertTrue("key height $keyHeight must be at most 60 dp", keyHeight <= 60.1f)
        assertTrue("aspect ${keyHeight / keyWidth} must be at most 1.2", keyHeight / keyWidth <= 1.2f)
    }

    @Test
    @Config(qualifiers = "w600dp-h500dp")
    fun theSameBoxWithoutFillKeepsTheShortDisplayKeysAndHasNoToolRow() {
        mountFilled(fill = false)
        val q = compose.onNodeWithContentDescription("Q").fetchSemanticsNode()
        assertTrue("Main/phone keys are at most the 48 dp target: ${dp(q.size.height.toFloat())}", dp(q.size.height.toFloat()) <= 48.5f)
        compose.onAllNodesWithTag(MimirKeyboardToolRowTag, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("Trackpad").assertExists()
        compose.onNodeWithContentDescription("Paste").assertExists()
    }

    @Test
    @Config(qualifiers = "w600dp-h500dp")
    fun theHitAreasOfAKeyRowTileItWithNoDeadZone() {
        mountFilled()
        val q = compose.onNodeWithContentDescription("Q").fetchSemanticsNode()
        val rowHeight = q.size.height
        val inRow = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .filter { it.size.height == rowHeight && it.boundsInRoot.top == q.boundsInRoot.top }
            .sortedBy { it.boundsInRoot.left }
        assertTrue("the letter row has ten keys, found ${inRow.size}", inRow.size == 10)
        inRow.zipWithNext { a, b ->
            assertEquals("no gap between two hit areas", a.boundsInRoot.right, b.boundsInRoot.left, 0.51f)
        }
        // And the rows tile the key block vertically: the next row begins where this one ends.
        val tops = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .filter { it.size.height == rowHeight }.map { it.boundsInRoot.top }.distinct().sorted()
        tops.zipWithNext { a, b -> assertEquals("no gap between two rows' hit areas", rowHeight.toFloat(), b - a, 0.51f) }
    }

    @Test
    @Config(qualifiers = "w600dp-h500dp")
    fun theToolRowIsBetweenThePreviewAndTheKeysWithCursorSelectAllPasteAndHide() {
        mountFilled(onHide = {})
        compose.onNodeWithTag(MimirKeyboardToolRowTag, useUnmergedTree = true).assertExists()
        listOf("Cursor left", "Cursor right", "Select all", "Paste", "Trackpad", "Hide keyboard").forEach {
            compose.onNodeWithContentDescription(it).assertExists()
        }
        // Paste and PAD moved out of the key rows into the tool row: one of each, not two.
        compose.onAllNodesWithContentDescription("Paste").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Trackpad").assertCountEquals(1)
        val tool = compose.onNodeWithTag(MimirKeyboardToolRowTag, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("the tool row is 44 dp tall", 44f, dp(tool.size.height.toFloat()), 0.6f)
        val q = compose.onNodeWithContentDescription("Q").fetchSemanticsNode()
        assertTrue("the tool row sits above the keys", tool.boundsInRoot.bottom <= q.boundsInRoot.top + 0.6f)
    }

    @Test
    @Config(qualifiers = "w600dp-h500dp")
    fun theToolRowsButtonsActOnTheField() {
        var latest = TextFieldValue("abc", TextRange(0))
        var hidden = 0
        var done = 0
        mountFilled(value = latest, onValue = { latest = it }, onHide = { hidden++ }, onDone = { done++ })
        compose.onNodeWithContentDescription("Cursor right").performClick()
        assertEquals("one place right", TextRange(1), latest.selection)
        compose.onNodeWithContentDescription("Select all").performClick()
        assertEquals(TextRange(0, 3), latest.selection)
        compose.onNodeWithContentDescription("Hide keyboard").performClick()
        assertEquals(1, hidden)
        assertEquals("Hide is not Done: no editor action", 0, done)
    }

    @Test
    @Config(qualifiers = "w600dp-h500dp")
    fun withNoHideHandlerTheToolRowShowsNoHideAndDoneIsNeverItsStandIn() {
        var done = 0
        mountFilled(onDone = { done++ })
        // Pre-fix, Hide fell back to onDone: a caller whose Done saves (the Tome rename) committed on Hide.
        compose.onAllNodesWithContentDescription("Hide keyboard").assertCountEquals(0)
        listOf("Cursor left", "Cursor right", "Select all", "Paste", "Trackpad").forEach {
            compose.onNodeWithContentDescription(it).assertExists()
        }
        assertEquals(0, done)
    }

    @Test
    @Config(qualifiers = "w600dp-h500dp")
    fun aSecretKeyboardWithNoHideHandlerShowsNoHideEither() {
        compose.setContent {
            MimirTheme {
                Box(Modifier.size(537.dp, 468.dp)) {
                    MimirSecretKeyboard(
                        display = MimirSecretDisplay(count = 3, caret = 1),
                        onEdit = {},
                        onDone = {},
                        fill = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.onAllNodesWithContentDescription("Hide keyboard").assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "w600dp-h500dp")
    fun aHostThatOwnsTheFieldRoutesTheCaretAndSelectAllToTheFieldAndEditsNothingLocally() {
        val calls = mutableListOf<String>()
        val values = mutableListOf<String>()
        compose.setContent {
            MimirTheme {
                Box(Modifier.size(537.dp, 468.dp)) {
                    MimirKeyboard(
                        value = "abc",
                        onValueChange = { values += it },
                        onDone = {},
                        fill = true,
                        onHide = {},
                        fieldOps = MimirKeyboardFieldOps(
                            caretLeft = { calls += "left" },
                            caretRight = { calls += "right" },
                            selectAll = { calls += "all" },
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Cursor right").performClick()
        compose.onNodeWithContentDescription("Cursor left").performClick()
        compose.onNodeWithContentDescription("Select all").performClick()
        assertEquals(listOf("right", "left", "all"), calls)
        assertTrue("no local text edit was made", values.isEmpty())
    }

    private fun mountOwnedField(value: String, selectionActive: () -> Boolean, deletes: () -> Unit, values: MutableList<String>) {
        compose.setContent {
            MimirTheme {
                Box(Modifier.size(537.dp, 468.dp)) {
                    MimirKeyboard(
                        value = value,
                        onValueChange = { values += it },
                        onDone = {},
                        fill = true,
                        onHide = {},
                        fieldOps = MimirKeyboardFieldOps(
                            caretLeft = {},
                            caretRight = {},
                            selectAll = {},
                            selectionActive = selectionActive,
                            deleteSelection = deletes,
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w600dp-h500dp")
    fun overASelectionTheFieldReportsBackspaceDeletesItInTheFieldAndSpaceReplacesItWhateverThePreviewHolds() {
        var deletes = 0
        val values = mutableListOf<String>()
        // After Select all the preview is empty, so its own Backspace would have had nothing to delete and Space was blocked.
        mountOwnedField("", selectionActive = { true }, deletes = { deletes++ }, values = values)
        compose.onNodeWithContentDescription("Backspace").performClick()
        assertEquals("Backspace asked the host to delete the field's selection", 1, deletes)
        assertTrue("and edited nothing locally", values.isEmpty())
        compose.onNodeWithContentDescription("Space").performClick()
        assertEquals("Space replaces the selection even though the preview is empty", listOf(" "), values)
    }

    @Test
    @Config(qualifiers = "w600dp-h500dp")
    fun withNoSelectionInTheFieldBackspaceAndSpaceKeepTheirOrdinaryBehaviour() {
        var deletes = 0
        val values = mutableListOf<String>()
        mountOwnedField("ab", selectionActive = { false }, deletes = { deletes++ }, values = values)
        compose.onNodeWithContentDescription("Backspace").performClick()
        assertEquals("no host delete", 0, deletes)
        assertEquals("the ordinary local backspace", listOf("a"), values)
    }

    @Test
    @Config(qualifiers = "w600dp-h500dp")
    fun anEmptyPreviewWithNoSelectionStillIgnoresSpace() {
        val values = mutableListOf<String>()
        mountOwnedField("", selectionActive = { false }, deletes = {}, values = values)
        compose.onNodeWithContentDescription("Space").performClick()
        assertTrue(values.isEmpty())
    }

    @Test
    @Config(qualifiers = "w600dp-h500dp")
    fun theBottomTrackpadPlateIsAnIconWithNoTextNamedTrackpadThatOpensThroughItsCallback() {
        var opened = 0
        compose.setContent {
            MimirTheme {
                BottomTrackpadPlate(onOpen = { opened++ })
            }
        }
        compose.onNodeWithTag(BottomTrackpadPlateTag).performClick()
        assertEquals(1, opened)
        compose.onNodeWithContentDescription("Trackpad").assertExists()
        compose.onAllNodesWithText("Trackpad").assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).assertCountEquals(0)
        val box = compose.onNodeWithTag(BottomTrackpadPlateTag).fetchSemanticsNode()
        assertTrue("the plate keeps the 48 dp target: ${dp(box.size.width.toFloat())} x ${dp(box.size.height.toFloat())}", dp(box.size.width.toFloat()) >= 47.9f && dp(box.size.height.toFloat()) >= 47.9f)
        assertTrue("and is a square, not a label's width: ${dp(box.size.width.toFloat())}", dp(box.size.width.toFloat()) <= 48.6f)
    }

    @Test
    @Config(qualifiers = "w600dp-h500dp")
    fun aSecretKeyboardsToolRowHasTheCaretPasteAndHideOnly() {
        val edits = mutableListOf<MimirSecretEdit>()
        compose.setContent {
            MimirTheme {
                Box(Modifier.size(537.dp, 468.dp)) {
                    MimirSecretKeyboard(
                        display = MimirSecretDisplay(count = 3, caret = 1),
                        onEdit = { edits += it },
                        onDone = {},
                        fill = true,
                        onHide = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.onNodeWithTag(MimirKeyboardToolRowTag, useUnmergedTree = true).assertExists()
        listOf("Cursor left", "Cursor right", "Paste", "Hide keyboard").forEach {
            compose.onNodeWithContentDescription(it).assertExists()
        }
        compose.onAllNodesWithContentDescription("Select all").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Trackpad").assertCountEquals(0)
        compose.onNodeWithContentDescription("Cursor left").performClick()
        compose.onNodeWithContentDescription("Cursor right").performClick()
        assertEquals("the caret goes to the field as forwarded ops, nothing computed from text", listOf(MimirSecretEdit.CaretLeft, MimirSecretEdit.CaretRight), edits)
    }

    @Test
    fun theKeyRowsDropPasteAndPadOnlyInFillMode() {
        val plain = keyboardRows(shiftOn = false, characterSet = MimirKeyboardCharacterSet.ALPHA, trackpad = true)
        val filled = keyboardRows(shiftOn = false, characterSet = MimirKeyboardCharacterSet.ALPHA, trackpad = true, toolRow = true)
        assertTrue(plain.flatten().any { it.action == KeyboardAction.Paste })
        assertTrue(plain.flatten().any { it.action == KeyboardAction.Trackpad })
        assertTrue(filled.flatten().none { it.action == KeyboardAction.Paste || it.action == KeyboardAction.Trackpad })
        assertEquals("the same number of rows", plain.size, filled.size)
    }

    @Test
    fun theFillGeometryOnA537By468BottomIsNormalKeysAnchoredAtTheBottomWithTheRestForThePreview() {
        // 537 x 468 dp, compact (gap 4): side padding 12, 5 rows, widest row weight 10, tool row 44, overhead 4 + 8 + 2 x 4.
        val g = mimirKeyboardFillGeometry(537.dp, 468.dp, rows = 5, widestRowWeight = 10f, sidePadding = 12.dp, gap = 4.dp, toolRowHeight = 44.dp, verticalOverhead = 20.dp)
        println("K2-GEOMETRY 537x468: key ${g.keyWidth.value} x ${g.keyHeight.value}, row ${g.rowHeight.value}, preview ${g.previewHeight.value}")
        assertEquals("cell 51.3 less the gap", 47.3f, g.keyWidth.value, 0.01f)
        assertEquals("1.1 times the width", 52.03f, g.keyHeight.value, 0.01f)
        assertEquals(56.03f, g.rowHeight.value, 0.01f)
        assertEquals("468 - 5 x 56.03 - 44 - 20", 123.85f, g.previewHeight.value, 0.05f)
        assertTrue(g.keyHeight.value / g.keyWidth.value <= 1.2f)
    }

    @Test
    fun theFillGeometryClampsTheKeyHeightAndShrinksTheKeysWhenThePreviewWouldVanish() {
        val wide = mimirKeyboardFillGeometry(1100.dp, 600.dp, 5, 10f, 12.dp, 4.dp, 44.dp, 20.dp)
        assertEquals("never taller than 60 dp, however wide", 60f, wide.keyHeight.value, 0.01f)
        val narrow = mimirKeyboardFillGeometry(300.dp, 600.dp, 5, 10f, 12.dp, 4.dp, 44.dp, 20.dp)
        assertEquals("never shorter than the 48 dp touch minimum", 48f, narrow.keyHeight.value, 0.01f)
        val short = mimirKeyboardFillGeometry(537.dp, 330.dp, 5, 10f, 12.dp, 4.dp, 44.dp, 20.dp)
        assertTrue("a short display shrinks the keys to leave the 56 dp preview: ${short.keyHeight}", short.keyHeight.value < 52f)
        assertTrue(short.previewHeight.value >= 55.9f)
        val unbounded = mimirKeyboardFillGeometry(537.dp, Dp.Infinity, 5, 10f, 12.dp, 4.dp, 44.dp, 20.dp)
        assertEquals("an unbounded host keeps the touch target", 48f, unbounded.keyHeight.value, 0.01f)
    }

    // ---- scrolling feedback (pad3) -----------------------------------------------------------------------------------

    @Test
    fun aFiftyMoveMillTurnAndATwoFingerScrollEachRecomposeAtMostTwice() {
        val host = FakeHost()
        var recompositions = 0
        showPad(host, probe = { recompositions++ })
        compose.waitForIdle()
        val before = recompositions
        compose.onNodeWithTag(MimirTrackpadPadDrumTag, useUnmergedTree = true).performTouchInput {
            down(center)
            repeat(50) { moveBy(Offset(0f, 3f)) }
            advanceEventTime(300)
            up()
        }
        compose.waitForIdle()
        val rail = recompositions - before
        compose.onNodeWithTag(MimirTrackpadPadFieldTag, useUnmergedTree = true).performTouchInput {
            down(0, center)
            down(1, center + Offset(40f, 0f))
            repeat(50) { moveBy(0, Offset(0f, 3f)); moveBy(1, Offset(0f, 3f)) }
            up(0)
            up(1)
        }
        compose.waitForIdle()
        val twoFinger = recompositions - before - rail
        assertTrue("a 50-move mill turn recomposed $rail times (budget 2)", rail <= 2)
        assertTrue("a 50-move two-finger scroll recomposed $twoFinger times (budget 2)", twoFinger <= 2)
        assertTrue("the scrolls reached the host: ${host.calls.count { it == "scroll" }}", host.calls.count { it == "scroll" } >= 50)
    }

    private fun panelLeftDp(fill: Boolean): Float {
        compose.setContent {
            MimirTheme {
                Box(Modifier.size(1000.dp, 468.dp)) {
                    MimirKeyboard(value = "", onValueChange = {}, onDone = {}, fill = fill, modifier = Modifier.fillMaxSize())
                }
            }
        }
        val lefts = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().map { it.boundsInRoot.left }
        return lefts.min() / compose.density.density
    }

    @Test
    @Config(qualifiers = "w1100dp-h500dp")
    fun theFillingPanelIsNotCappedAt720Dp() {
        assertEquals("the filling panel starts at the left edge", 0f, panelLeftDp(fill = true), 1f)
    }

    @Test
    @Config(qualifiers = "w1100dp-h500dp")
    fun theCapStillAppliesWithoutFill() {
        val left = panelLeftDp(fill = false)
        assertTrue("without fill the panel is the 720 dp cap, centred: left=$left", left > 100f)
    }

    // ---- pad6: speed, hold feedback, rune wall, mill, Back -----------------------------------------------------------

    @Test
    fun theSpeedStepsCycleOneToFiveAndBackAndTheDefaultIsTheOldSpeed() {
        assertEquals(1, TrackpadSpeed.next(5))
        assertEquals(listOf(2, 3, 4, 5, 1), (1..5).map { TrackpadSpeed.next(it) })
        assertEquals("default is step 3", 3, TrackpadSpeed.Default)
        assertEquals("step 3 is today's gain exactly", 1.0f, TrackpadSpeed.factor(3), 0f)
        assertEquals(listOf(0.6f, 0.8f, 1.0f, 1.3f, 1.7f), (1..5).map { TrackpadSpeed.factor(it) })
        assertEquals("out of range is clamped", 0.6f, TrackpadSpeed.factor(-4), 0f)
        assertEquals(1.7f, TrackpadSpeed.factor(99), 0f)
    }

    @Test
    fun theSpeedFactorScalesThePointerGainOfTheSameSwipe() {
        fun movedFor(scale: Float): Float {
            val host = FakeHost()
            val machine = TrackpadGestureMachine(host, slopPx = 12f, gainScale = { scale })
            machine.down(0, 0f, 0f, 0L)
            machine.move(0, 30f, 0f, 10L)
            machine.move(0, 60f, 0f, 20L)
            return host.moveDxs.sum()
        }
        val base = movedFor(TrackpadSpeed.factor(3))
        assertTrue("the swipe moved the pointer: $base", base > 0f)
        assertEquals("step 1 is 0.6 of step 3", 0.6f, movedFor(TrackpadSpeed.factor(1)) / base, 0.001f)
        assertEquals("step 5 is 1.7 of step 3", 1.7f, movedFor(TrackpadSpeed.factor(5)) / base, 0.001f)
    }

    @Test
    fun theGaugeInTheHeaderSetsTheHostsSpeedDirectlyAndShowsTheLevel() {
        val host = FakeHost()
        showPad(host)
        // pad8: the dial that cycled 3 to 4 is a gauge: a tap on the bar's fourth fifth is 4, and its last is 5, with no wrap.
        compose.tapGaugeStep(4)
        assertEquals("the host's step is 4", 4, host.speed)
        compose.onNodeWithTag(MimirTrackpadSpeedGaugeTag, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "4 of 5"))
        compose.tapGaugeStep(5)
        assertEquals("5, and tapping it again stays 5", 5, host.speed)
        compose.tapGaugeStep(5)
        assertEquals(5, host.speed)
    }

    @Test
    fun theBackPlateCallsTheHostsBack() {
        val host = FakeHost()
        showPad(host)
        compose.onNodeWithContentDescription(MimirTrackpadCopy.BackDescription).performClick()
        assertEquals(1, host.backs)
    }

    @Test
    fun aTapNeverShowsHoldProgressAndAHoldShowsItOnlyAfterTheVisualDelay() {
        val delay = TrackpadGestureMachine.HoldVisualDelayMs
        assertTrue("the delay is about 60 percent of the long press: $delay", delay in 250L..290L)
        assertTrue("a tap ends before the ring could show", TrackpadGestureMachine.TapMs < delay)
        val host = FakeHost()
        val machine = TrackpadGestureMachine(host)
        // A tap: down, a lift 100 ms later. At no moment is there progress.
        machine.down(0, 10f, 10f, 0L)
        for (t in listOf(0L, 40L, 80L, 100L)) {
            assertEquals("tap at $t ms", 0f, trackpadHoldProgress(machine.holdPendingElapsedMs(t), machine.longPressPending, machine.isHolding), 0f)
        }
        machine.up(0, 100L)
        assertEquals("after the tap nothing is pending", 0f, trackpadHoldProgress(machine.holdPendingElapsedMs(120L), machine.longPressPending, machine.isHolding), 0f)
        assertEquals(listOf("click"), host.calls)
        // A hold: nothing before the delay, filling after it, full once the machine holds.
        val held = FakeHost()
        val holdMachine = TrackpadGestureMachine(held)
        holdMachine.down(0, 10f, 10f, 1_000L)
        fun progress(at: Long) = trackpadHoldProgress(holdMachine.holdPendingElapsedMs(at), holdMachine.longPressPending, holdMachine.isHolding)
        assertEquals(0f, progress(1_000L + 200L), 0f)
        assertEquals("at the delay itself nothing yet", 0f, progress(1_000L + delay), 0f)
        assertTrue("just past it the ring has begun", progress(1_000L + delay + 30L) > 0f)
        assertTrue("and keeps filling", progress(1_000L + 400L) > progress(1_000L + delay + 30L))
        holdMachine.tick(1_000L + TrackpadGestureMachine.LongPressMs)
        assertTrue(holdMachine.isHolding)
        assertEquals("full once the press holds", 1f, progress(1_000L + 460L), 0f)
        // Moving past the slop cancels the pending state: no ring for a drag that was never a hold.
        val moved = FakeHost()
        val moveMachine = TrackpadGestureMachine(moved, slopPx = 12f)
        moveMachine.down(0, 0f, 0f, 0L)
        moveMachine.move(0, 40f, 0f, 300L)
        assertEquals(0f, trackpadHoldProgress(moveMachine.holdPendingElapsedMs(320L), moveMachine.longPressPending, moveMachine.isHolding), 0f)
    }

    @Test
    fun twoFingersShowNoHoldProgressEither() {
        val machine = TrackpadGestureMachine(FakeHost())
        machine.down(0, 0f, 0f, 0L)
        machine.down(1, 50f, 0f, 20L)
        assertEquals(0f, trackpadHoldProgress(machine.holdPendingElapsedMs(400L), machine.longPressPending, machine.isHolding), 0f)
    }

    @Test
    fun aCancelClearsEveryContactHoldGlowAndFlash() {
        val feedback = TrackpadPadFeedback()
        feedback.down(0, 10f, 10f, 1_000L)
        feedback.move(0, 40f, 10f, 1_020L)
        feedback.holdPending = true
        feedback.holding = true
        feedback.scrolling = true
        feedback.noteTap(1_030L)
        assertTrue("a fresh trail and flash keep it ticking", feedback.alive(1_040L))
        feedback.clear()
        assertFalse("no contact is left", feedback.contacts.any { it.active })
        assertFalse(feedback.holdPending)
        assertFalse(feedback.holding)
        assertFalse(feedback.scrolling)
        assertFalse("no trail or flash keeps the pad ticking", feedback.alive(1_050L))
    }

    @Test
    fun theTrailGlowsForAboutThreeHundredMillisecondsAndNoLonger() {
        val trail = TrackpadTrail()
        trail.add(0f, 0f, 1_000L)
        trail.add(2f, 0f, 1_010L) // under the minimum step: not a new sample
        trail.add(30f, 0f, 1_020L)
        assertTrue(trail.alive(1_100L))
        assertEquals(1f, trackpadFade(1_000L, 1_000L), 0f)
        assertEquals(0.5f, trackpadFade(1_000L, 1_150L), 0.001f)
        assertEquals("gone after the glow fade", 0f, trackpadFade(1_000L, 1_000L + TrackpadVisuals.GlowFadeMs), 0f)
        assertEquals(300L, TrackpadVisuals.GlowFadeMs)
        assertFalse(trail.alive(1_020L + TrackpadVisuals.GlowFadeMs))
        assertTrue("glow falls off to nothing at its reach", trackpadGlow(70f, 70f, 1f) == 0f)
        assertEquals("a finger on a rune lights it fully", 1f, trackpadGlow(0f, 70f, 1f), 0f)
    }

    @Test
    fun theRuneWallIsAStaggeredGridOfElderFutharkRunes() {
        val wall = RuneWallLayout(width = 400f, height = 300f, cell = 40f)
        assertTrue("cells were laid out: ${wall.count}", wall.count > 40)
        assertEquals("the elder futhark has 24 runes", 24, TrackpadVisuals.Runes.size)
        assertTrue("every glyph is one of them", wall.glyphs.all { it in 0 until 24 })
        val rows = wall.ys.toSet().sorted()
        val first = wall.xs.filterIndexed { i, _ -> wall.ys[i] == rows[0] }.minOrNull()!!
        val second = wall.xs.filterIndexed { i, _ -> wall.ys[i] == rows[1] }.minOrNull()!!
        assertEquals("every other row is shifted half a cell", 20f, kotlin.math.abs(second - first), 0.01f)
        // pad8: every row is laid out (the wall drifts and wraps); the ones below the field are simply not drawn, so an undrifted wall shows what it always did.
        assertTrue("every cell is inside the field's width", wall.xs.all { it in 0f..400f })
        assertTrue("and inside one period", wall.ys.all { it in 0f..wall.period })
        assertTrue("with no drift the visible cells are the old wall's", wall.ys.count { it <= 300f } > 40)
    }

    // ---- the mill wheel (pure) ---------------------------------------------------------------------------------------

    private fun spinFor(host: FakeHost, radius: Float = 50f, friction: Float = MillPhysics.FrictionPerMs) = MillSpin(host, radiusPx = { radius }, friction = friction)

    private fun flick(spin: MillSpin, host: FakeHost): Boolean {
        spin.touchDown(500f, 0L)
        for (k in 1..5) spin.drag(500f - k * 10f, k * 10L)
        return spin.release(60L, cancelled = false)
    }

    @Test
    fun draggingTheMillTurnsItOneForOneWithTheFingerAndScrollsInStep() {
        val host = FakeHost()
        val spin = spinFor(host, radius = 50f)
        spin.touchDown(300f, 0L)
        spin.drag(280f, 50L)
        spin.drag(310f, 100L)
        assertEquals("the scroll steps are the finger's: ${host.scrollDys}", listOf(-20f, 30f), host.scrollDys)
        assertEquals("the rim moved as far as the finger: 10 px at radius 50", 10f / 50f, spin.angle, 0.0001f)
        assertEquals(listOf("scroll", "scroll"), host.calls)
    }

    @Test
    fun aSlowLiftSendsTheScrollOnceAndDoesNotSpin() {
        val host = FakeHost()
        val spin = spinFor(host)
        spin.touchDown(300f, 0L)
        spin.drag(290f, 100L)
        spin.drag(280f, 200L)
        assertFalse(spin.release(220L, cancelled = false))
        assertFalse(spin.spinning)
        assertEquals(listOf("scroll", "scroll", "scrollEnd"), host.calls)
    }

    @Test
    fun aFlickKeepsItSpinningWithDecayingScrollUntilItStops() {
        val host = FakeHost()
        val spin = spinFor(host)
        assertTrue("a fast lift starts a spin", flick(spin, host))
        assertTrue(spin.spinning)
        val before = host.scrollDys.size
        val steps = mutableListOf<Float>()
        var now = 60L
        var guard = 0
        while (guard++ < 5_000) {
            val count = host.scrollDys.size
            now += 16L
            if (!spin.step(16f, now)) break
            // A step turns the wheel once; a settling spin waiting out the burst spacing turns nothing.
            assertTrue("a step scrolls at most once", host.scrollDys.size - count in 0..1)
            if (host.scrollDys.size > count) steps += host.scrollDys.last()
        }
        assertFalse("friction stopped it", spin.spinning)
        assertTrue("it kept scrolling as it turned: ${steps.size} steps", steps.size > 10)
        assertTrue("the scroll is moving the way the finger flicked (up is negative)", steps.all { it < 0f })
        assertTrue("and decays: first ${steps.first()} last ${steps.last()}", kotlin.math.abs(steps.last()) < kotlin.math.abs(steps.first()))
        assertTrue("its strokes went out in bursts and the last scroll was flushed", host.calls.count { it == "scrollEnd" } >= 1)
        assertTrue("nothing is left pending: the last call is a send or a drop, ${host.calls.last()}", host.calls.last() in listOf("scrollEnd", "scrollCancel"))
        assertFalse("it turned the wheel past the drag", spin.angle == 0f)
    }

    @Test
    fun aBurstIsSentOnlyOnceEnoughHasScrolledAndNeverCloserThanTheStrokeLength() {
        val host = FakeHost()
        val spin = spinFor(host)
        assertTrue(flick(spin, host))
        var now = 60L
        val sentAt = mutableListOf<Long>()
        var seenEnds = host.calls.count { it == "scrollEnd" }
        while (true) {
            now += 16L
            val more = spin.step(16f, now)
            val ends = host.calls.count { it == "scrollEnd" }
            if (ends > seenEnds && more) sentAt += now
            seenEnds = ends
            if (!more) break
        }
        assertTrue("bursts mid-spin are at least ${MillPhysics.BurstMs} ms apart: $sentAt", sentAt.zipWithNext().all { (a, b) -> b - a >= MillPhysics.BurstMs })
    }

    @Test
    fun touchingTheMillAgainStopsTheSpin() {
        val host = FakeHost()
        val spin = spinFor(host)
        assertTrue(flick(spin, host))
        spin.step(16f, 80L)
        spin.step(16f, 96L)
        assertTrue(spin.spinning)
        spin.touchDown(250f, 120L)
        assertFalse("the touch stopped the spin", spin.spinning)
        assertEquals(0f, spin.velocity, 0f)
        val scrolled = host.scrollDys.size
        assertFalse("and a step after it does nothing", spin.step(16f, 140L))
        assertEquals(scrolled, host.scrollDys.size)
    }

    @Test
    fun aLockMidFlingStopsTheSpinAndSendsNothingMore() {
        val host = FakeHost()
        val spin = spinFor(host)
        assertTrue(flick(spin, host))
        var now = 60L
        repeat(5) { now += 16L; assertTrue(spin.step(16f, now)) }
        val callsBefore = host.calls.size
        host.live = false
        now += 16L
        assertFalse("the dead session ends the spin", spin.step(16f, now))
        assertFalse(spin.spinning)
        val after = host.calls.drop(callsBefore)
        assertTrue("no scroll or send after the lock, only the cancel: $after", after.none { it == "scroll" || it == "scrollEnd" })
        now += 16L
        assertFalse(spin.step(16f, now))
        assertEquals("and it stays silent", after.size, host.calls.size - callsBefore)
    }

    @Test
    fun closingTheWheelMidSpinSendsNothingMore() {
        val host = FakeHost()
        val spin = spinFor(host)
        assertTrue(flick(spin, host))
        spin.step(16f, 80L)
        spin.dispose()
        assertFalse(spin.spinning)
        val calls = host.calls.size
        spin.step(16f, 100L)
        assertEquals(calls, host.calls.size)
        assertEquals("scrollCancel", host.calls.last())
    }

    @Test
    fun aCancelledTouchOrAStalledFingerIsNotAFlick() {
        val host = FakeHost()
        val spin = spinFor(host)
        spin.touchDown(500f, 0L)
        for (k in 1..5) spin.drag(500f - k * 10f, k * 10L)
        assertFalse(spin.release(60L, cancelled = true))
        assertEquals("scrollCancel", host.calls.last())
        assertFalse(host.calls.contains("scrollEnd"))
        val stalled = FakeHost()
        val still = spinFor(stalled)
        still.touchDown(500f, 0L)
        for (k in 1..5) still.drag(500f - k * 10f, k * 10L)
        assertFalse("the finger paused 400 ms before it lifted", still.release(450L, cancelled = false))
        assertEquals("scrollEnd", stalled.calls.last())
    }

    @Test
    fun theWheelsPhysicsDecayToNothingAndTheDistanceIsTheirIntegral() {
        var v = 2f
        var travelled = 0f
        repeat(2_000) {
            travelled += MillPhysics.distance(v, 16f)
            v = MillPhysics.decay(v, 16f)
        }
        assertTrue("it stopped: $v", v < MillPhysics.StopSpeedPxPerMs)
        assertEquals("the closed form: v0 / k", 2f / MillPhysics.FrictionPerMs, travelled, 6f)
        assertTrue(MillPhysics.MinBurstPx >= 20f / 2.2f)
    }

    // ---- pad7: the mill's terminal flush spacing, the field yielding, the plate tap, the caps -----------------------------------

    /** Steps a spin from [startMs] in 16 ms frames; returns the times at which a swipe went out (a new scrollEnd) and the end time. */
    private fun runSpin(host: FakeHost, spin: MillSpin, startMs: Long): Pair<List<Long>, Long> {
        val times = mutableListOf<Long>()
        var seen = host.calls.count { it == "scrollEnd" }
        var now = startMs
        var guard = 0
        while (guard++ < 10_000) {
            now += 16L
            val more = spin.step(16f, now)
            val ends = host.calls.count { it == "scrollEnd" }
            repeat(ends - seen) { times += now }
            seen = ends
            if (!more) break
        }
        return times to now
    }

    @Test
    fun noSwipeEverGoesOutWithinTheBurstSpacingOfAnotherTheTerminalFlushIncluded() {
        // Several lift speeds: some stop before the first burst, some between bursts, some after several.
        for ((stepPx, friction) in listOf(5f to 0.003f, 6f to 0.01f, 8f to 0.02f, 12f to 0.003f, 25f to 0.006f, 60f to 0.003f, 6f to 0.003f)) {
            val host = FakeHost()
            val spin = spinFor(host, friction = friction)
            spin.touchDown(2_000f, 0L)
            for (k in 1..5) spin.drag(2_000f - k * stepPx, k * 10L)
            if (!spin.release(60L, cancelled = false)) continue
            val (times, end) = runSpin(host, spin, 60L)
            val all = listOf(60L) + times // the lift is the spacing baseline
            assertTrue("step $stepPx: swipes at $times (end $end) are at least ${MillPhysics.BurstMs} ms apart", all.zipWithNext().all { (a, b) -> b - a >= MillPhysics.BurstMs })
            assertFalse(spin.spinning)
        }
    }

    @Test
    fun aSpinThatSettlesInsideTheSpacingWaitsForItBeforeItsLastScrollGoes() {
        val host = FakeHost()
        // A heavily damped wheel and a slow flick: it is over in well under one spacing, with enough scroll owed to be a swipe.
        val spin = spinFor(host, friction = 0.01f)
        spin.touchDown(500f, 0L)
        for (k in 1..5) spin.drag(500f - k * 5f, k * 10L)
        assertTrue(spin.release(60L, cancelled = false))
        var now = 60L
        var firstEnd = -1L
        while (spin.step(16f, now + 16L).also { now += 16L }) {
            if (firstEnd < 0 && host.calls.contains("scrollEnd")) firstEnd = now
        }
        assertTrue("its one swipe went out after the spacing, not before: ended at $now, first $firstEnd", now - 60L >= MillPhysics.BurstMs)
        assertEquals(1, host.calls.count { it == "scrollEnd" })
    }

    @Test
    fun touchingTheWheelDropsThePendingTailAndSendsNoSwipe() {
        val host = FakeHost()
        val spin = spinFor(host)
        assertTrue(flick(spin, host))
        var now = 60L
        repeat(10) { now += 16L; assertTrue(spin.step(16f, now)) }
        assertFalse("nothing was due yet", host.calls.contains("scrollEnd"))
        spin.touchDown(300f, now + 5L)
        assertFalse(spin.spinning)
        assertFalse("the tail was dropped, never sent", host.calls.contains("scrollEnd"))
        assertEquals("scrollCancel", host.calls.last())
    }

    @Test
    fun aSlowLiftRightAfterASwipeWaitsOutTheSpacingInsteadOfCancellingIt() {
        val host = FakeHost()
        val spin = spinFor(host)
        assertTrue(flick(spin, host))
        var now = 60L
        while (host.calls.count { it == "scrollEnd" } == 0) { now += 16L; assertTrue(spin.step(16f, now)) }
        val sentAt = now
        // A new slow drag begins at once after that swipe and lifts inside its spacing.
        spin.touchDown(400f, sentAt + 20L)
        spin.drag(380f, sentAt + 120L)
        spin.drag(360f, sentAt + 220L)
        val before = host.calls.count { it == "scrollEnd" }
        assertTrue("the lift must wait, so the caller keeps stepping", spin.release(sentAt + 240L, cancelled = false))
        assertEquals("nothing went out inside the spacing", before, host.calls.count { it == "scrollEnd" })
        var t = sentAt + 240L
        while (spin.step(16f, t + 16L).also { t += 16L }) Unit
        assertTrue("it went out once the spacing had passed: $t vs ${sentAt + MillPhysics.BurstMs}", t >= sentAt + MillPhysics.BurstMs)
        assertEquals(before + 1, host.calls.count { it == "scrollEnd" })
    }

    @Test
    fun aFlingThenAHoldOnTheFieldSendsNoBurstAfterTheDownAndTheHoldChainIsIntact() {
        val host = FakeHost()
        val spin = spinFor(host)
        val machine = TrackpadGestureMachine(host)
        assertTrue(flick(spin, host))
        var now = 60L
        repeat(10) { now += 16L; spin.step(16f, now) }
        // The pad's order: the wheel yields first, then the field's gesture is admitted.
        spin.yieldToField()
        machine.down(0, 100f, 100f, now)
        val mark = host.calls.size
        // The hold starts, the field keeps its press for a long time, and the wheel is stepped on every frame meanwhile.
        machine.tick(now + TrackpadGestureMachine.LongPressMs)
        repeat(200) { now += 16L; assertFalse(spin.step(16f, now)) }
        machine.up(0, now)
        val after = host.calls.drop(mark)
        assertEquals("only the hold's own calls follow the down: $after", listOf("holdBegin", "holdEnd"), after)
        assertFalse(spin.spinning)
    }

    @Test
    fun theFieldYieldingDropsAFingerStillTurningTheWheelAndItsScroll() {
        val host = FakeHost()
        val spin = spinFor(host)
        spin.touchDown(300f, 0L)
        spin.drag(280f, 20L)
        spin.yieldToField()
        assertFalse(spin.touching)
        assertEquals("scrollCancel", host.calls.last())
        val calls = host.calls.size
        spin.drag(250f, 40L)
        assertFalse("a drag after the yield turns nothing", spin.release(60L, cancelled = false))
        assertEquals(calls, host.calls.size)
    }

    @Test
    fun thePadAdmitsAFieldDownOnlyAfterTheWheelYields() {
        val pad = java.io.File(
            listOf(
                "core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirTrackpadPad.kt",
                "src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirTrackpadPad.kt",
            ).first { java.io.File(it).isFile },
        ).readText()
        val yielded = pad.indexOf("feedback.mill?.yieldToField()")
        val admit = pad.indexOf("machine.down(id, change.position.x")
        assertTrue("yield at $yielded comes before the machine down at $admit", yielded in 0 until admit)
    }

    @Test
    fun aFlickIsCappedInSpeedAndAFlingInWallClockTime() {
        val host = FakeHost()
        val spin = spinFor(host)
        spin.touchDown(5_000f, 0L)
        for (k in 1..5) spin.drag(5_000f - k * 400f, k * 10L)
        assertTrue(spin.release(60L, cancelled = false))
        assertEquals("the speed is capped", MillPhysics.MaxFlingSpeedPxPerMs, kotlin.math.abs(spin.velocity), 0f)
        // A frictionless-ish wheel would spin for many seconds: the wall-clock cap settles it.
        val slick = FakeHost()
        val long = MillSpin(slick, radiusPx = { 50f }, friction = 0.00005f)
        long.touchDown(5_000f, 0L)
        for (k in 1..5) long.drag(5_000f - k * 40f, k * 10L)
        assertTrue(long.release(60L, cancelled = false))
        var now = 60L
        var turnedAtCap = -1
        var guard = 0
        while (guard++ < 10_000) {
            now += 16L
            val more = long.step(16f, now)
            if (turnedAtCap < 0 && now - 60L >= MillPhysics.FlingMaxMs) turnedAtCap = slick.scrollDys.size
            if (!more) break
        }
        assertFalse(long.spinning)
        assertTrue("it reached the cap: $turnedAtCap", turnedAtCap > 0)
        assertEquals("nothing turned after the cap", turnedAtCap, slick.scrollDys.size)
        assertTrue("and it was over by the cap plus one spacing: ${now - 60L}", now - 60L <= MillPhysics.FlingMaxMs + MillPhysics.BurstMs + 32L)
    }

    private fun dragOutAndTapPlate(description: String, fired: () -> Int) {
        // Press the plate, drag out of it past the slop, release elsewhere: nothing.
        compose.onNodeWithContentDescription(description).performTouchInput {
            down(center)
            moveBy(Offset(0f, 200f))
            up()
        }
        compose.waitForIdle()
        assertEquals("$description: a drag-out release fires nothing", 0, fired())
        // A wiggle inside the slop and a release inside the plate: a tap.
        compose.onNodeWithContentDescription(description).performTouchInput {
            down(center)
            moveBy(Offset(2f, 1f))
            up()
        }
        compose.waitForIdle()
        assertEquals("$description: a tap fires once", 1, fired())
    }

    @Test
    // pad8: the header now carries the Slow-to-Fast gauge, which needs the Bottom's own width (the pad window is the 537 dp Thor screen); a 320 dp window squeezes the last plate away.
    @Config(qualifiers = "w600dp-h500dp")
    fun everyHeaderPlateFiresOnlyOnAReleaseInsideItThatStayedWithinSlop() {
        val host = FakeHost()
        var keyboard = 0
        var closed = 0
        showPad(host, onKeyboard = { keyboard++ }, onClose = { closed++ })
        dragOutAndTapPlate(MimirTrackpadCopy.BackDescription) { host.backs }
        dragOutAndTapPlate(MimirTrackpadCopy.KeyboardPlate) { keyboard }
        dragOutAndTapPlate(MimirTrackpadCopy.CloseDescription) { closed }
        val start = host.speed
        val spot = compose.gaugeSpot()
        compose.onNodeWithTag(MimirTrackpadSpeedGaugeTag, useUnmergedTree = true).performTouchInput {
            down(Offset(spot.stepX(5), spot.midY))
            moveBy(Offset(0f, 200f))
            up()
        }
        compose.waitForIdle()
        assertEquals("the gauge did not move on a drag-out release", start, host.speed)
        compose.tapGaugeStep(5)
        assertEquals("a tap sets that fifth's level", 5, host.speed)
    }

    @Test
    fun aCancelledPressOnAPlateFiresNothing() {
        val host = FakeHost()
        showPad(host)
        compose.onNodeWithContentDescription(MimirTrackpadCopy.BackDescription).performTouchInput { down(center); cancel() }
        compose.waitForIdle()
        assertEquals(0, host.backs)
    }

    @Test
    fun theBottomHeaderPlateUsesATapGestureThatAlsoIgnoresADragOutRelease() {
        var opened = 0
        compose.setContent { MimirTheme { BottomTrackpadPlate(onOpen = { opened++ }) } }
        compose.onNodeWithTag(BottomTrackpadPlateTag).performTouchInput { down(center); moveBy(Offset(0f, 300f)); up() }
        compose.waitForIdle()
        assertEquals("a drag-out release does not open the pad", 0, opened)
        compose.onNodeWithTag(BottomTrackpadPlateTag).performTouchInput { down(center); up() }
        compose.waitForIdle()
        assertEquals(1, opened)
    }
}

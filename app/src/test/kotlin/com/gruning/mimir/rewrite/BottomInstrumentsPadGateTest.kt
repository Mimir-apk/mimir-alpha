package com.gruning.mimir.rewrite

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.tweaker.TweakerFanMode
import com.gruning.mimir.rewrite.core.tweaker.TweakerManualFanCapability
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityAvailability
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirTransitionInputGate
import com.gruning.mimir.rewrite.core.ui.mimirPageTouchShield
import java.io.File
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
 * Bottom instruments input isolation.
 *
 * P2-1: the instruments (Utility and Tweaker) on the BOTTOM must never set the process-wide pad gate: through the panel's own arrival
 * (`rememberMimirGlassPanelProgress`) and through the swap to the Tweaker (`MimirSwapPageGate`) they used to, which could mute Main's
 * controller for the lead. Both now take `holdPad`; Main's hosts keep today's `true`, which the controls below prove.
 *
 * P2-3: the fan-curve row steps past an ancestor's consume (`requireUnconsumed = false`), so the page gate's lead shield did not stop a
 * tap in the Tweaker's lead from changing the Manual track's draft. It now stands down for a consumed press.
 *
 * P2-2: the platform shelf's touch shield (`companionShelfTouchShielded`), on the modifier shape the Presentation gives the shelf layer.
 *
 * The panel is composed as the Bottom host composes it (no controller hints, no BackHandler), on a paused clock under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w640dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomInstrumentsPadGateTest {

    @get:Rule
    val compose = createComposeRule()

    private var surface by mutableStateOf(UtilitySurface.UTILITY)
    private val reached = mutableListOf<String>()
    private val previews = mutableListOf<Int>()

    @Before
    fun gateClock() {
        MimirTransitionInputGate.reset()
        MimirTransitionInputGate.clock = { compose.mainClock.currentTime }
    }

    @After
    fun resetGate() = MimirTransitionInputGate.reset()

    private val manualFan = TweakerManualFanCapability(
        modes = setOf(TweakerFanMode.AUTO, TweakerFanMode.MANUAL),
        curveBiasRange = -3..3,
        curveBiasStep = 1,
        curveSensorType = "package",
        currentMode = TweakerFanMode.MANUAL,
        currentCurveBias = 0,
        ownerRequestId = "test-owner",
        generation = 1L,
        transactionId = "test-transaction",
        profileId = "balanced",
    )

    private fun mount(initial: UtilitySurface, holdPad: Boolean?) {
        surface = initial
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                if (holdPad == null) {
                    panel()
                } else {
                    panel(holdPad)
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun panel() = MimirBrightnessOverlay(
        state = panelState(),
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
        tweakerManualFan = manualFan,
        onPreviewTweakerFanCurve = { previews += it },
        surface = surface,
        onBack = { reached += "back" },
        onClose = { reached += "close" },
        backActionLabel = if (surface == UtilitySurface.TWEAKER) "Back" else "Close",
        controllerHintsAvailable = false,
        enableBackHandler = false,
    )

    @androidx.compose.runtime.Composable
    private fun panel(holdPad: Boolean) = MimirBrightnessOverlay(
        state = panelState(),
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
        tweakerManualFan = manualFan,
        onPreviewTweakerFanCurve = { previews += it },
        surface = surface,
        onBack = { reached += "back" },
        onClose = { reached += "close" },
        backActionLabel = if (surface == UtilitySurface.TWEAKER) "Back" else "Close",
        controllerHintsAvailable = false,
        enableBackHandler = false,
        holdPad = holdPad,
    )

    private fun show(target: UtilitySurface) {
        Snapshot.withMutableSnapshot { surface = target }
        compose.mainClock.advanceTimeByFrame()
    }

    /** Frames of 16 ms, reading the gate on every one of them. */
    private fun advanceWatching(ms: Long, expectHolding: Boolean?, what: String) {
        var left = ms
        var sawHolding = false
        while (left > 0) {
            compose.mainClock.advanceTimeBy(16L)
            left -= 16L
            if (MimirTransitionInputGate.holding) sawHolding = true
            if (expectHolding == false) assertFalse("$what: the Bottom must never hold the process-wide pad gate", MimirTransitionInputGate.holding)
        }
        if (expectHolding == true) assertTrue("$what: Main's panel holds the pad gate through the lead, as it always did", sawHolding)
    }

    private fun settle() = compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs * 8L)

    private fun tapAt(point: Offset) {
        compose.onRoot().performTouchInput {
            down(point)
            up()
        }
        compose.mainClock.advanceTimeBy(50L)
    }

    private fun footerCentre(label: String): Offset {
        val matches = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { node ->
            val words = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            words.any { it.trim().equals(label, ignoreCase = true) }
        }
        assertEquals("one live footer labelled $label", 1, matches.size)
        return matches.single().boundsInRoot.center
    }

    @Test
    fun `opening the Bottom's Utility, then switching to the Tweaker, never sets the pad gate`() {
        mount(UtilitySurface.UTILITY, holdPad = false)
        advanceWatching(MimirMotion.OverlayDissolveCoverMs + MimirMotion.ShineMs + 100L, expectHolding = false, what = "the panel's own arrival")
        show(UtilitySurface.TWEAKER)
        advanceWatching(MimirMotion.OverlayDissolveCoverMs + MimirMotion.OverlayMs + 100L, expectHolding = false, what = "the swap to the Tweaker")
        show(UtilitySurface.UTILITY)
        advanceWatching(MimirMotion.OverlayMs * 3L, expectHolding = false, what = "back to Utility")
    }

    @Test
    fun `the Bottom's arriving Tweaker still takes no touch through its lead, and answers once shown`() {
        mount(UtilitySurface.UTILITY, holdPad = false)
        settle()
        show(UtilitySurface.TWEAKER)
        compose.mainClock.advanceTimeBy(48L)
        assertFalse(MimirTransitionInputGate.holding)
        tapAt(footerCentre("Back"))
        assertEquals("the touch half of the gate still shields the arriving page: $reached", emptyList<String>(), reached)
        settle()
        tapAt(footerCentre("Back"))
        assertEquals(listOf("back"), reached)
    }

    @Test
    fun `control - Main's panel (the default) holds the pad gate through its arrival and through the swap to the Tweaker`() {
        mount(UtilitySurface.UTILITY, holdPad = null)
        compose.mainClock.advanceTimeByFrame()
        advanceWatching(MimirMotion.OverlayDissolveCoverMs.toLong(), expectHolding = true, what = "the panel's own arrival")
        settle()
        assertFalse("settled: the gate is free", MimirTransitionInputGate.holding)
        show(UtilitySurface.TWEAKER)
        advanceWatching(MimirMotion.OverlayDissolveCoverMs.toLong(), expectHolding = true, what = "the swap to the Tweaker")
    }
    /** The fan-response TRACK: of the two nodes named "Fan response" (the row's label and the track) only the track reports a range. */
    private fun fanTrackCentre(): Offset {
        val tracks = compose.onAllNodesWithContentDescription("Fan response").fetchSemanticsNodes()
            .filter { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }
        assertEquals("one fan-response track in the Manual-fan Tweaker", 1, tracks.size)
        return tracks.single().boundsInRoot.center
    }

    @Test
    fun `a tap in the Tweaker's lead cannot move the fan-curve draft, and one after it can`() {
        mount(UtilitySurface.UTILITY, holdPad = false)
        settle()
        show(UtilitySurface.TWEAKER)
        compose.mainClock.advanceTimeBy(48L)
        tapAt(fanTrackCentre())
        assertEquals("the lead shield must stop the fan-curve handler too: $previews", emptyList<Int>(), previews)
        settle()
        tapAt(fanTrackCentre())
        assertTrue("a shown fan-curve row takes its tap: $previews", previews.isNotEmpty())
    }
    @Test
    fun `the Bottom host passes false and Main hosts keep true, in source`() {
        val root = repoRoot()
        val coordinator = File(root, "app/src/main/kotlin/com/gruning/mimir/rewrite/AppBrightnessCoordinator.kt").readText()
        assertTrue("the coordinator host holds the pad for the primary target only", coordinator.contains("holdPad = target == AppBrightnessPresentationTarget.PRIMARY,"))
        val presentation = File(root, "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt").readText()
        assertTrue("the Bottom's instruments host is the SECONDARY target", presentation.contains("target = AppBrightnessPresentationTarget.SECONDARY,"))
        val overlay = File(root, "app/src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt").readText()
        assertTrue(overlay.contains("if (holdPad && !settledOnReturn) {\n        LaunchedEffect(Unit) { holdMimirInputUntilShown(panelEntry) { panelEntry() > 0f } }\n    }"))
        assertTrue(overlay.contains("MimirSwapPageGate(mimirSwapFadeAlpha(swapReducedMotion, returning = swapReturning), holdPad = holdPad)"))
        assertTrue("the fan-curve handler stands down for a consumed press", overlay.contains("if (down.isConsumed) return@awaitEachGesture"))
        assertTrue("the default is today's behaviour for Main", overlay.contains("holdPad: Boolean = true,"))
        val host = File(root, "app/src/main/kotlin/com/gruning/mimir/rewrite/AppBrightnessHost.kt").readText()
        assertTrue(host.contains("holdPad: Boolean = true,") && host.contains("holdPad = holdPad,"))
    }

    // ---- P2-2: the platform shelf's lead shield ------------------------------------------------------------------------

    @Test
    fun `the shelf is shielded from the ask until it has painted, never once leaving, and the rule is the Presentation's`() {
        assertTrue("asked for, nothing painted: shielded", companionShelfTouchShielded(platformOpen = true, shelfEnter = 0f))
        assertTrue("through the lead the value is still zero", companionShelfTouchShielded(platformOpen = true, shelfEnter = 0.0005f))
        assertFalse("its first painted frame lifts it", companionShelfTouchShielded(platformOpen = true, shelfEnter = 0.01f))
        assertFalse("settled", companionShelfTouchShielded(platformOpen = true, shelfEnter = 1f))
        assertFalse("a shelf that is leaving is not shielded", companionShelfTouchShielded(platformOpen = false, shelfEnter = 0f))
        val presentation = File(repoRoot(), "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt").readText()
        val shelfLayer = presentation.substringAfter(".zIndex(shelfZ)").substringBefore("traceShelfExit")
        assertTrue("the shelf layer wears the shield", shelfLayer.contains(".then(if (shelfTouchShielded) Modifier.mimirPageTouchShield() else Modifier)"))
        assertTrue(
            "the shield is derived from the drawn value, not read in composition (audit 2026-10-14, B-35)",
            presentation.contains("derivedStateOf { companionShelfTouchShielded(platformDestination != null, shelfEnter()) }"),
        )
        assertTrue("and passes no pad hold", !presentation.contains("MimirTransitionInputGate"))
    }

    @Test
    fun `on the shelf layer's modifier shape neither the invisible shelf nor the lattice under it takes a tap until it paints`() {
        val taps = mutableListOf<String>()
        var shelfEnter by mutableStateOf(0f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.size(200.dp)) {
                // The lattice: still on screen under the arriving shelf.
                Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { taps += "lattice" }) })
                // The shelf layer as the Presentation composes it: alpha from the enter value, a tile that takes taps, and the shield.
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = shelfEnter }
                        .then(if (companionShelfTouchShielded(true, shelfEnter)) Modifier.mimirPageTouchShield() else Modifier),
                ) {
                    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { taps += "shelf tile" }) })
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        tapAt(Offset(100f, 100f))
        assertEquals("through the lead nothing is tapped: $taps", emptyList<String>(), taps)
        Snapshot.withMutableSnapshot { shelfEnter = 0.2f }
        compose.mainClock.advanceTimeByFrame()
        tapAt(Offset(100f, 100f))
        assertEquals("once the shelf has painted its tile answers, and the lattice does not: $taps", listOf("shelf tile"), taps)
    }

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "core-ui").isDirectory) return dir
            dir = dir.parentFile
        }
        error("repository root not found from ${File(".").absolutePath}")
    }

    private fun panelState(): BrightnessState = BrightnessState(
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

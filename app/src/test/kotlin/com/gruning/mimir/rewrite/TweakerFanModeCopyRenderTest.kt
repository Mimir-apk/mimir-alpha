package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.gruning.mimir.rewrite.core.tweaker.TweakerFanMode
import com.gruning.mimir.rewrite.core.tweaker.TweakerManualFanCapability
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityAvailability
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirTextLayoutObserver
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.liquidGlassContentPalette
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Fan mode row's width in the Thor Bottom's rail (538 x 468 dp at 369 dpi), as the real panel lays it out: what [TweakerFanModeRowRenderTest] draws its rows at. */
private const val FanRowWidthDp = 465.69f

/**
 * The Tweaker dialog's FAN MODE row in every state it can show (device frames 2026-10-13: AUTO read "the device's own fan controller" while the Balanced profile held
 * `fan_mode=6`, Mimir's own duty loop). The real [MimirBrightnessOverlay] on the TWEAKER surface at the Thor Bottom's geometry (538 x 468 dp at 369 dpi), at font 1.0 and 1.3, with
 * the state changed under it the way the daemon's attestation changes it.
 *
 * What it asserts is what a render cannot show: the row's supporting sentence is the one [tweakerFanModeSupportingText] says for that state and is whole (not ellipsized, not cut,
 * on at most the two caps lines it has always had), the row's label and value stay on one line each, the spoken state is [tweakerFanModeStateDescription], and the row is inside
 * the screen. The pixels are [TweakerFanModeRowRenderTest]'s: the panel's own face stands in recorded layers a JVM canvas cannot draw, so that picture is of the row itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TweakerFanModeCopyRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var fan by mutableStateOf(capability(TweakerFanMode.AUTO, owned = true))
    private var fontScale by mutableStateOf(1f)

    /** The last layout each chrome line was laid out as, by the words it speaks. */
    private val heard = mutableMapOf<String, TextLayoutResult>()

    private fun capability(mode: TweakerFanMode, owned: Boolean?) = TweakerManualFanCapability(
        modes = setOf(TweakerFanMode.AUTO, TweakerFanMode.MANUAL),
        curveBiasRange = -100..100,
        curveBiasStep = 10,
        curveSensorType = "GPU",
        currentMode = mode,
        currentCurveBias = 0,
        ownerRequestId = "mimir.system.1",
        generation = 1L,
        transactionId = "tx-1",
        profileId = "balanced",
        hardwareDutyOwned = owned,
    )

    /** The four states the row shows, by the name the PNG carries. */
    private val states = listOf(
        "auto-mimir" to capability(TweakerFanMode.AUTO, owned = true),
        "auto-device" to capability(TweakerFanMode.AUTO, owned = false),
        "auto-unattested" to capability(TweakerFanMode.AUTO, owned = null),
        "manual" to capability(TweakerFanMode.MANUAL, owned = true),
    )

    /** The panel's rows reveal over time (the rail reveals by index), so the clock is walked on far enough that every row is drawn whole, as it is on a handheld. */
    private fun settle(ms: Long) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                // The chrome text hides its layout from semantics (clearAndSetSemantics); this is the hook core-ui gives a test to hear it.
                LocalMimirTextLayoutObserver provides { spoken, layout -> heard[spoken] = layout },
            ) {
                MimirTheme {
                    MimirBrightnessOverlay(
                        state = tweakerPanelState(),
                        focusedControl = BrightnessControlKey.TWEAKER_FAN_MODE,
                        onSetFraction = { _, _ -> },
                        onSetThemeAwareJoystickLight = {},
                        onRequestWriteAccess = {},
                        onRequestPrivilegedAccess = {},
                        onBack = {},
                        onClose = {},
                        surface = UtilitySurface.TWEAKER,
                        tweakerRow = DefaultTweakerUtilityRow.copy(availability = TweakerUtilityAvailability.READY),
                        tweakerManualFan = fan,
                        tweakerUsageAccessState = TweakerUsageAccessState.SET_UP,
                    )
                }
            }
        }
        settle(2_000L)
    }

    private fun layoutOf(spoken: String): TextLayoutResult =
        checkNotNull(heard[spoken]) { "\"$spoken\" was never laid out" }

    private fun shoot(scale: Float) {
        fontScale = scale
        fan = states.first().second
        mount()
        val density = compose.activity.resources.displayMetrics.density
        for ((name, capability) in states) {
            fan = capability
            settle(600L)
            val where = "$name at font $scale"
            val rows = compose.onAllNodesWithContentDescription("Fan mode").fetchSemanticsNodes(atLeastOneRootRequired = false)
            assertEquals("$where: exactly one Fan mode row", 1, rows.size)
            compose.onAllNodesWithContentDescription("Fan mode").onFirst().performScrollTo()
            settle(600L)

            // The row speaks the state it shows: its value, and its mode and sentence as one spoken line.
            val row = compose.onAllNodesWithContentDescription("Fan mode").fetchSemanticsNodes().single()
            assertEquals(
                "$where: the row's spoken state",
                tweakerFanModeStateDescription(capability),
                row.config.getOrNull(SemanticsProperties.StateDescription),
            )

            // The sentence is on screen, whole.
            val sentence = tweakerFanModeSupportingText(capability)
            val text = compose.onAllNodes(hasContentDescription(sentence), useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals("$where: the sentence \"$sentence\" is drawn once", 1, text.size)
            val layout = layoutOf(sentence)
            assertTrue("$where: ${layout.lineCount} lines, at most the two caps lines the row has always had", layout.lineCount <= 2)
            assertTrue(
                "$where: ellipsized or clipped (overflowHeight ${layout.didOverflowHeight}, ellipsized ${(0 until layout.lineCount).map { layout.isLineEllipsized(it) }})",
                !layout.didOverflowHeight && (0 until layout.lineCount).none { layout.isLineEllipsized(it) },
            )
            for (line in 0 until layout.lineCount - 1) {
                val end = layout.getLineEnd(line, visibleEnd = false)
                assertTrue("$where: line ${line + 1} ends inside a word", sentence[end - 1].isWhitespace() || sentence[end].isWhitespace())
            }
            val root = compose.activity.window.decorView
            val box = text.single().boundsInRoot
            assertTrue(
                "$where: the sentence ($box) lies inside the ${root.width / density} x ${root.height / density} dp screen",
                box.left >= 0f && box.right <= root.width.toFloat() && box.top >= 0f && box.bottom <= root.height.toFloat(),
            )
            // The row's label and its value are one line each: AUTO / MANUAL stand whole beside the name.
            val valueLabel = tweakerFanModeValueLabel(capability.currentMode)
            val value = compose.onAllNodes(hasContentDescription(valueLabel), useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("$where: the value $valueLabel is drawn", value.isNotEmpty())
            assertEquals("$where: and it is one line", 1, layoutOf(valueLabel).lineCount)
            val label = layoutOf("Fan mode")
            assertEquals("$where: and so is the row's name", 1, label.lineCount)
            assertEquals("$where: the row is as wide as the one the pixel render draws", FanRowWidthDp, row.boundsInRoot.width / density, 1f)
        }
        val cut = compose.onAllNodes(hasText("…", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("text cut with an ellipsis: ${cut.map { it.config }}", cut.isEmpty())
    }

    @Test
    fun everyStateAtFont1() = shoot(1.0f)

    @Test
    fun everyStateAtFont13() = shoot(1.3f)

    private fun tweakerPanelState(): BrightnessState = BrightnessState(
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
            privilegedUtilityPosture = PrivilegedUtilityPosture.Unsupported,
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

/**
 * The Fan mode row as pixels, the four states stacked (AUTO under Mimir's loop, AUTO under the vendor's SMART mode, AUTO from a Tweaker that cannot attest, MANUAL), at the row's real
 * width in the Bottom's rail and font 1.0 and 1.3, in the real [UtilityChoiceRow] with the real fonts. The whole panel is not drawn: its rail and footer stand in recorded layers a JVM
 * canvas cannot draw (`BottomUtilityWindowRenderTest` loses the same), and what changed is this row's sentence. [TweakerFanModeCopyRenderTest] asserts the same four states inside the
 * real panel.
 *
 * What it asserts is what a render cannot show: each sentence is whole (not ellipsized, no line ending inside a word, at most the two caps lines the row has always had) and the
 * row's name and value are one line each. The PNGs (`fan-mode-states-font<scale>.png`) land in `build/test-captures/tlog` (or `MIMIR_RENDER_DIR`) and the look is read off them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TweakerFanModeRowRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val heard = mutableMapOf<String, TextLayoutResult>()

    private fun capability(mode: TweakerFanMode, owned: Boolean?) = TweakerManualFanCapability(
        modes = setOf(TweakerFanMode.AUTO, TweakerFanMode.MANUAL),
        curveBiasRange = -100..100,
        curveBiasStep = 10,
        curveSensorType = "GPU",
        currentMode = mode,
        currentCurveBias = 0,
        ownerRequestId = "mimir.system.1",
        generation = 1L,
        transactionId = "tx-1",
        profileId = "balanced",
        hardwareDutyOwned = owned,
    )

    private val states = listOf(
        "AUTO, MIMIR HOLDS THE DUTY (fan_mode 6, attested)" to capability(TweakerFanMode.AUTO, owned = true),
        "AUTO, THE VENDOR SMART MODE (attested, not owned)" to capability(TweakerFanMode.AUTO, owned = false),
        "AUTO, AN OLDER TWEAKER (no attestation)" to capability(TweakerFanMode.AUTO, owned = null),
        "MANUAL" to capability(TweakerFanMode.MANUAL, owned = true),
    )

    private fun shoot(scale: Float) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, scale),
                LocalMimirTextLayoutObserver provides { spoken, layout -> heard[spoken] = layout },
            ) {
                MimirTheme {
                    val palette = LocalMimirPalette.current.liquidGlassContentPalette()
                    Column(Modifier.fillMaxSize().background(Color(0xFF151823)).padding(start = 36.dp, top = 8.dp)) {
                        Column(Modifier.width(FanRowWidthDp.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            for ((caption, fan) in states) {
                                BasicText(caption, style = TextStyle(color = Color(0xFF8A93A8), fontSize = 9.sp))
                                UtilityChoiceRow(
                                    label = "Fan mode",
                                    supportingText = tweakerFanModeSupportingText(fan),
                                    valueLabel = tweakerFanModeValueLabel(fan.currentMode),
                                    stateDescription = tweakerFanModeStateDescription(fan),
                                    focused = false,
                                    palette = palette,
                                    modifier = Modifier,
                                    onRequestFocus = {},
                                    onStep = {},
                                    canStepLeft = tweakerFanModeCanStepLeft(fan),
                                    canStepRight = tweakerFanModeCanStepRight(fan),
                                    onActivate = {},
                                    enabled = true,
                                    showStepAffordances = true,
                                    compactSupporting = true,
                                )
                            }
                        }
                    }
                }
            }
        }
        repeat(8) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
        }
        compose.waitForIdle()

        for ((caption, fan) in states) {
            val where = "${caption.substringBefore(" (")} at font $scale"
            val sentence = tweakerFanModeSupportingText(fan)
            val layout = checkNotNull(heard[sentence]) { "$where: \"$sentence\" was never laid out" }
            assertTrue("$where: ${layout.lineCount} lines, at most the two caps lines the row has always had", layout.lineCount <= 2)
            assertTrue(
                "$where: ellipsized or clipped (overflowHeight ${layout.didOverflowHeight}, ellipsized ${(0 until layout.lineCount).map { layout.isLineEllipsized(it) }})",
                !layout.didOverflowHeight && (0 until layout.lineCount).none { layout.isLineEllipsized(it) },
            )
            for (line in 0 until layout.lineCount - 1) {
                val end = layout.getLineEnd(line, visibleEnd = false)
                assertTrue("$where: line ${line + 1} ends inside a word", sentence[end - 1].isWhitespace() || sentence[end].isWhitespace())
            }
        }
        assertEquals("the row's name is one line", 1, checkNotNull(heard["Fan mode"]).lineCount)
        for (value in listOf("AUTO", "MANUAL")) assertEquals("the value $value is one line", 1, checkNotNull(heard[value]).lineCount)
        val cut = compose.onAllNodes(hasText("\u2026", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("text cut with an ellipsis: ${cut.map { it.config }}", cut.isEmpty())

        val decor = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        val folder = File(System.getenv("MIMIR_RENDER_DIR") ?: "build/test-captures/tlog").also { it.mkdirs() }
        val file = File(folder, "fan-mode-states-font$scale.png")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue("the render landed: ${file.absolutePath}", file.isFile && file.length() > 1_000L)
        println("TLOG-RENDER ${file.absolutePath}")
    }

    @Test
    fun everyStateAtFont1() = shoot(1.0f)

    @Test
    fun everyStateAtFont13() = shoot(1.3f)
}

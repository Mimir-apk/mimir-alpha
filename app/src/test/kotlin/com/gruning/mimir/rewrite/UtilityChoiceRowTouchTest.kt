package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityAvailability
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * On the Thor's Bottom, taps on the OPEN label of Utility's TWEAKER row
 * produced an ACTION_DOWN receipt and nothing else, while a tap on the row name opened the Tweaker.
 *
 * The mechanism: [UtilityChoiceRow] composed its ‹ › chevrons even when they could not step (transparent while the
 * affordances are hidden, muted at a bound), each with `clickable(enabled = false)`. In Compose foundation 1.7.3 a
 * disabled clickable still runs its tap detector, which consumes the down and the up. Only the click callback checks
 * `enabled`. Each chevron is narrower than the minimum touch target, so touch hit-testing stretches it over the value
 * label beside it, and the row's own clickable never saw an unconsumed down. The fix takes the clickable off hidden
 * chevrons only; a shown chevron keeps it even when it cannot step, which the second test pins.
 *
 * These tests compose the real row, and the real Utility panel, under Robolectric with native graphics so text
 * measures. They inject down/up pairs through the root at measured points, not semantics clicks, because a semantics
 * click would bypass the hit test that is the defect. This proves on the JVM which callback a touch reaches; what the
 * Thor does is device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w640dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UtilityChoiceRowTouchTest {

    @get:Rule
    val compose = createComposeRule()

    /** What each touch reached, in order: "focus", "activate", "step:<direction>". */
    private val events = mutableListOf<String>()

    private var minimumTouchTargetWidth: Dp = Dp.Unspecified

    private fun composeRow(canStepLeft: Boolean, canStepRight: Boolean, showStepAffordances: Boolean) {
        compose.setContent {
            minimumTouchTargetWidth = LocalViewConfiguration.current.minimumTouchTargetSize.width
            MimirTheme {
                // MimirBrightnessOverlay caps the panel at 560 dp.
                Box(Modifier.width(560.dp)) {
                    UtilityChoiceRow(
                        label = "Tweaker",
                        supportingText = tweakerRouteSupportingText(TweakerRouteDecision.OPEN),
                        valueLabel = tweakerRouteValueLabel(TweakerRouteDecision.OPEN),
                        stateDescription = tweakerRouteValueLabel(TweakerRouteDecision.OPEN),
                        focused = false,
                        palette = LocalMimirPalette.current,
                        modifier = Modifier,
                        onRequestFocus = { events += "focus" },
                        onStep = { events += "step:$it" },
                        canStepLeft = canStepLeft,
                        canStepRight = canStepRight,
                        onActivate = { events += "activate" },
                        showStepAffordances = showStepAffordances,
                        compactSupporting = true,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `with no step to take, a tap anywhere on the value cluster activates the row`() {
        composeRow(canStepLeft = false, canStepRight = false, showStepAffordances = false)
        val label = valueLabelBounds()
        val row = rowBounds("Tweaker")
        val geometry = geometry(label)
        val y = label.center.y
        val taps = linkedMapOf(
            "label left edge + 2dp" to Offset(label.left + 2.dp.px(), y),
            "label centre" to label.center,
            "label right edge - 2dp" to Offset(label.right - 2.dp.px(), y),
            // The cluster ends where the row's 12 dp end padding starts, so this is 6 dp past it.
            "6dp right of the cluster" to Offset(row.right - 6.dp.px(), y),
        )
        taps.forEach { (name, point) -> assertTrue("$name must land inside the row; $geometry", row.contains(point)) }

        val outcomes = taps.mapValues { (_, point) -> reached(point) }

        assertEquals(
            "every tap on the cluster must reach the row, not a chevron that cannot step. $geometry",
            taps.mapValues { listOf("focus", "activate") },
            outcomes,
        )
        assertEquals(4, events.count { it == "activate" })
        assertEquals(
            "a chevron that cannot step is decoration, not an accessibility node (as TweakerFpsStepAction)",
            emptyList<String>(),
            listOf("‹", "›").filter { glyphBounds(it) != null },
        )
    }

    /**
     * Deliberate, and unchanged by the B5 fix: with the affordances shown, a chevron keeps its clickable even when it
     * cannot step, as a shown, disabled control absorbs its own tap. The live ‹ steps; the muted ›'s stretched hit
     * area still takes a tap on the right half of the label beside it, and that tap reaches nothing. That is the same
     * stretched-hit-area loss as B5, on the visible stepping rows at a bound. It was not reported and is left for an
     * design decision; this test pins today's behaviour so any change to it is made on purpose.
     */
    @Test
    fun `with affordances shown, a live chevron steps and a muted chevron still absorbs the label beside it`() {
        composeRow(canStepLeft = true, canStepRight = false, showStepAffordances = true)
        val label = valueLabelBounds()
        val row = rowBounds("Tweaker")
        val left = checkNotNull(glyphBounds("‹")) { "a shown ‹ that can step must stay a node" }
        val right = checkNotNull(glyphBounds("›")) { "a shown › that cannot step must stay a node" }
        val geometry = geometry(label)
        val rightHalf = Offset(label.right - 2.dp.px(), label.center.y)
        // Each chevron is narrower than the minimum touch target, so its hit area reaches past its own edges. The
        // point must sit beyond the live ‹'s reach and within the muted ›'s, so what it reaches is the muted ›'s doing.
        val leftReach = ((minimumTouchTargetWidth.px() - left.width) / 2f).coerceAtLeast(0f)
        val rightReach = ((minimumTouchTargetWidth.px() - right.width) / 2f).coerceAtLeast(0f)
        assertTrue("the point must sit in the label's right half; $geometry", rightHalf.x > label.center.x)
        assertTrue("the point must land inside the row; $geometry", row.contains(rightHalf))
        assertTrue(
            "the point must sit beyond the live ‹'s stretched hit area; reach=${dp(leftReach)}dp, $geometry",
            rightHalf.x - left.right > leftReach,
        )
        assertTrue(
            "the point must sit level with the muted › and within its stretched hit area; " +
                "reach=${dp(rightReach)}dp, $geometry",
            rightHalf.y in right.top..right.bottom && right.left - rightHalf.x < rightReach,
        )

        assertEquals(
            "a tap on ‹ steps back and does not also activate",
            listOf("focus", "step:-1"),
            reached(left.center),
        )
        assertEquals(
            "a shown › that cannot step still absorbs a tap on the label's right half, so it reaches nothing. " +
                geometry,
            emptyList<String>(),
            reached(rightHalf),
        )
    }

    /**
     * The same taps through the whole Utility panel as the Bottom composes it (no controller hints), so a pass also
     * covers every layer above the row: the scrim, the panel's own pointer handler, the outside-tap sibling and the
     * rail scroll.
     *
     * Three points per label, not the centre alone. Under this harness OPEN measured 28 dp wide and each chevron
     * 12.5 dp, so each stretched chevron reached 13.75 dp into the label and left a strip about 0.5 dp wide at its
     * centre uncovered: a centre-only version of this test passed against the unfixed row.
     */
    @Test
    fun `OPEN on the Utility panel's Tweaker and Files rows opens each route`() {
        var openedTweaker = 0
        var openedFiles = 0
        compose.setContent {
            MimirTheme {
                MimirBrightnessOverlay(
                    state = utilityPanelState(),
                    focusedControl = null,
                    onSetFraction = { _, _ -> },
                    onSetThemeAwareJoystickLight = {},
                    onRequestWriteAccess = {},
                    onRequestPrivilegedAccess = {},
                    onOpenTweaker = { openedTweaker++ },
                    onOpenFiles = { openedFiles++ },
                    filesRouteAvailable = true,
                    allFilesAccessGranted = true,
                    tweakerRow = DefaultTweakerUtilityRow.copy(availability = TweakerUtilityAvailability.READY),
                    surface = UtilitySurface.UTILITY,
                    onBack = {},
                    onClose = {},
                    controllerHintsAvailable = false,
                )
            }
        }
        compose.waitForIdle()

        val oncePerTap = mapOf("left edge + 2dp" to 1, "centre" to 1, "right edge - 2dp" to 1)
        assertEquals(
            "each tap on the Tweaker row's OPEN must open the Tweaker once",
            oncePerTap,
            opensPerTap(openLabelIn("Tweaker")) { openedTweaker },
        )
        assertEquals(
            "each tap on the Files row's OPEN must open Files once",
            oncePerTap,
            opensPerTap(openLabelIn("Files")) { openedFiles },
        )
    }

    /** The OPEN value label inside the row named [rowLabel]. */
    private fun openLabelIn(rowLabel: String): Rect {
        val row = rowBounds(rowLabel)
        return compose.onAllNodesWithContentDescription("OPEN", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .single { row.contains(it.center) }
    }

    /** Taps [label] at its left edge + 2 dp, centre and right edge - 2 dp; returns how many opens each tap made. */
    private fun opensPerTap(label: Rect, opened: () -> Int): Map<String, Int> =
        linkedMapOf(
            "left edge + 2dp" to Offset(label.left + 2.dp.px(), label.center.y),
            "centre" to label.center,
            "right edge - 2dp" to Offset(label.right - 2.dp.px(), label.center.y),
        ).mapValues { (_, point) ->
            val before = opened()
            tapAt(point)
            opened() - before
        }

    private fun tapAt(point: Offset) {
        compose.onRoot().performTouchInput {
            down(point)
            up()
        }
        compose.waitForIdle()
    }

    /** Taps [point] and returns what that one touch reached. */
    private fun reached(point: Offset): List<String> {
        val before = events.size
        tapAt(point)
        return events.drop(before)
    }

    private fun rowBounds(label: String): Rect =
        compose.onNode(hasContentDescription(label) and hasClickAction(), useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

    private fun valueLabelBounds(): Rect =
        compose.onAllNodesWithContentDescription("OPEN", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .single()
            .boundsInRoot

    private fun glyphBounds(glyph: String): Rect? =
        compose.onAllNodesWithText(glyph, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .singleOrNull()
            ?.boundsInRoot

    private fun geometry(label: Rect): String = buildString {
        append("label ").append(dp(label.width)).append("dp wide")
        glyphBounds("‹")?.let { append(", ‹ node ").append(dp(it.width)).append("dp") }
        glyphBounds("›")?.let { append(", › node ").append(dp(it.width)).append("dp") }
        append(", minimum touch target ").append(minimumTouchTargetWidth)
    }

    private fun Dp.px(): Float = with(compose.density) { toPx() }

    private fun dp(px: Float): String = "%.1f".format(px / compose.density.density)

    /** TweakerRailRenderOrderTest's fixture, with privileged access READY so the Tweaker route reads OPEN. */
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

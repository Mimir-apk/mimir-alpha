package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.center
import androidx.compose.ui.test.down
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Rune Pad Forge: the bound slab's wall (drift, edge fade, shimmer), the rune drum's geometry,
 * the Slow-to-Fast gauge that a tap sets directly, the pointer-on-a-pad icon, the ember plates, and the rules every new effect obeys
 * (theme tokens only, no clock while parked or under reduced motion). The effects are checked on EVERY theme palette, not only Default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w600dp-h500dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirPad8Test {

    @get:Rule
    val compose = createComposeRule()

    private class Host : MimirTrackpadHost {
        override val serviceConnected: Boolean = true
        var speed = TrackpadSpeed.Default
        var settings = 0
        val speedCalls = mutableListOf<Int>()
        val calls = mutableListOf<String>()
        override val speedStep: Int get() = speed
        override fun setSpeed(step: Int): Int {
            settings++
            speedCalls += step
            speed = TrackpadSpeed.clamp(step)
            return speed
        }
        override fun begin(): MimirTrackpadHost = this
        override fun pointerMove(dx: Float, dy: Float) { calls += "move" }
        override fun click() { calls += "click" }
        override fun doubleClick() { calls += "double" }
        override fun scrollBy(dx: Float, dy: Float) { calls += "scroll" }
        override fun scrollEnd() { calls += "scrollEnd" }
        override fun scrollCancel() { calls += "scrollCancel" }
        override fun holdBegin() { calls += "holdBegin" }
        override fun holdMove(dx: Float, dy: Float) { calls += "holdMove" }
        override fun holdEnd() { calls += "holdEnd" }
        override fun openAccessibilitySettings() = Unit
        override fun end() = Unit
    }

    private val palettes = listOf(
        "Default" to MimirThemePalette.Default,
        "Freyr" to MimirThemePalette.Freyr,
        "Surtr" to MimirThemePalette.Surtr,
        "Mani" to MimirThemePalette.Mani,
        "Hel" to MimirThemePalette.Hel,
        "Niflheim" to MimirThemePalette.Niflheim,
        "Yggdrasil" to MimirThemePalette.Yggdrasil,
        "Folkvangr" to MimirThemePalette.Folkvangr,
        "Baldr" to MimirThemePalette.Baldr,
    )

    private fun dp(px: Int) = px / compose.density.density

    /** The touch slop the running view configuration uses, in pixels: what a finger must travel before a drag begins. */
    private var slopPx = 0f

    private fun showPad(host: Host, palette: MimirThemePalette = MimirThemePalette.Default) {
        compose.setContent {
            slopPx = LocalViewConfiguration.current.touchSlop
            MimirTheme {
                CompositionLocalProvider(LocalMimirPalette provides palette) {
                    MimirTrackpadPad(session = host, keyboardWaiting = false, onKeyboard = {}, onClose = {}, palette = palette)
                }
            }
        }
    }

    private fun render(width: Int, height: Int, block: DrawScope.() -> Unit): PixelMap {
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat()), block)
        return bitmap.toPixelMap()
    }

    private fun near(expected: Color, actual: Color, tolerance: Int = 3): Boolean {
        val e = expected.toArgb()
        val a = actual.toArgb()
        return intArrayOf(0, 8, 16).all { shift -> abs(((e shr shift) and 0xFF) - ((a shr shift) and 0xFF)) <= tolerance }
    }

    // ---- item 2: the drum -------------------------------------------------------------------------------------------

    @Test
    fun theDrumIsA56DpRibbedStripAndTheMillIsGone() {
        showPad(Host())
        val drum = compose.onNodeWithTag(MimirTrackpadPadDrumTag, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("the drum is 56 dp wide: ${dp(drum.size.width)}", 56f, dp(drum.size.width), 0.6f)
        assertTrue("and as tall as the field: ${dp(drum.size.height)}", drum.size.height > 0)
        compose.onNodeWithContentDescription(MimirTrackpadCopy.DrumDescription, useUnmergedTree = true).assertExists()
        assertEquals("Rune drum", MimirTrackpadCopy.DrumDescription)
        val main = File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirTrackpadMill.kt").readText()
        assertFalse("no mill wheel left: blades, hub or rim", main.contains("MillBladeCount") || main.contains("Mill wheel"))
    }

    @Test
    fun aRibMovesExactlyAsFarAsTheThumbAtTheMiddleAndRibsCrowdTowardTheEnds() {
        val radius = 200f
        val pitch = DrumGeometry.RibPitchDp / radius
        // The thumb moves 6 px: the drum turns 6 / radius (MillSpin's own rule) and the middle rib shows 6 px lower (a hair under: sin).
        val turn = 6f / radius
        val before = drumRibY(0f, radius, drumRibAngle(0, pitch, 0f))
        val after = drumRibY(0f, radius, drumRibAngle(0, pitch, turn))
        assertEquals("one for one at the middle", 6f, after - before, 0.01f)
        // Spacing between two neighbouring ribs shrinks away from the middle.
        fun gap(rib: Int) = drumRibY(0f, radius, drumRibAngle(rib + 1, pitch, 0f)) - drumRibY(0f, radius, drumRibAngle(rib, pitch, 0f))
        val middleGap = gap(0)
        val nearEnd = gap(drumLastRib(pitch, 0f) - 3)
        assertTrue("the ribs crowd at the ends: middle $middleGap, near the end $nearEnd", nearEnd < middleGap * 0.5f)
        // And they darken: a rib faces the player fully at the middle and not at all at the rim.
        assertEquals(1f, drumRibDepth(0f), 0f)
        assertEquals(0f, drumRibDepth((PI / 2).toFloat()), 0.0001f)
        assertTrue(drumRibDepth(1.2f) < drumRibDepth(0.4f))
    }

    @Test
    fun everyShownRibIsOnTheNearHalfOfTheCylinderForAnyTurn() {
        val pitch = DrumGeometry.RibPitchDp / 180f
        for (turn in listOf(0f, 0.3f, 1.7f, -2.4f, 31.4f)) {
            val first = drumFirstRib(pitch, turn)
            val last = drumLastRib(pitch, turn)
            assertTrue("ribs exist for turn $turn", last > first)
            for (rib in first..last) {
                val theta = drumRibAngle(rib, pitch, turn)
                assertTrue("rib $rib at turn $turn is within a quarter turn: $theta", abs(theta) <= (PI / 2).toFloat() + 0.0001f)
            }
            // Neither neighbour past the ends shows.
            assertTrue(abs(drumRibAngle(first - 1, pitch, turn)) > (PI / 2).toFloat() - 0.0001f)
            assertTrue(abs(drumRibAngle(last + 1, pitch, turn)) > (PI / 2).toFloat() - 0.0001f)
        }
    }

    @Test
    fun theDrumsGlowBandFollowsTheThumbAndFadesAfterTheLift() {
        assertEquals(1f, drumGlowStrength(touching = true, spinning = false, fade = 0f), 0f)
        assertTrue("a coast glows less than a touch", drumGlowStrength(false, true, 0f) < 1f)
        assertEquals("then it fades with the lift", 0f, drumGlowStrength(false, false, 0f), 0f)
        assertTrue(drumGlowStrength(false, false, 1f) > 0f)
        val host = Host()
        var told = 0f
        val mill = MillSpin(host, radiusPx = { 100f }, onTurn = { told += it })
        mill.touchDown(40f, 0L)
        assertEquals(40f, mill.touchY, 0f)
        mill.drag(75f, 16L)
        assertEquals("the band sits where the thumb is", 75f, mill.touchY, 0f)
        assertEquals("every scroll the drum sends is told to the wall", 35f, told, 0.001f)
    }

    // ---- item 2: the wall's scroll feedback -------------------------------------------------------------------------

    @Test
    fun theWallDriftsWithAScrollAndFadesAtTheEdgesOnlyWhileScrolling() {
        val feedback = TrackpadPadFeedback()
        assertEquals("at rest nothing drifts", 0f, feedback.driftY, 0f)
        assertEquals(0f, feedback.scrollStrength(1_000L), 0f)
        feedback.noteScroll(40f, 1_000L)
        assertEquals("the wall drifts in the scroll direction, a little under the finger", 40f * TrackpadVisuals.DriftGain, feedback.driftY, 0.001f)
        feedback.noteScroll(-100f, 1_050L)
        assertTrue("the other way too: ${feedback.driftY}", feedback.driftY < 40f * TrackpadVisuals.DriftGain)
        assertEquals(1f, feedback.scrollStrength(1_050L), 0f)
        assertTrue("the feedback keeps the draw clock ticking while it fades", feedback.alive(1_100L))
        assertEquals("gone after the fade", 0f, feedback.scrollStrength(1_050L + TrackpadVisuals.ScrollFadeMs), 0f)
        assertFalse("and then the pad draws no more frames", feedback.alive(1_050L + TrackpadVisuals.ScrollFadeMs + 1))
        feedback.noteScroll(10f, 5_000L)
        feedback.clear()
        assertEquals(0f, feedback.scrollStrength(5_001L), 0f)
    }

    @Test
    fun underReducedMotionNothingDriftsOrFades() {
        val feedback = TrackpadPadFeedback()
        feedback.reducedMotion = true
        feedback.noteScroll(500f, 1_000L)
        assertEquals(0f, feedback.driftY, 0f)
        assertEquals(0f, feedback.scrollStrength(1_001L), 0f)
        assertFalse(feedback.alive(1_001L))
    }

    @Test
    fun aDriftedWallWrapsAndFadesAtItsEdgesAndAnUndriftedOneIsExactlyAsItWas() {
        val wall = RuneWallLayout(width = 400f, height = 300f, cell = 40f)
        assertTrue("the period covers the field: ${wall.period}", wall.period >= 300f)
        assertEquals("no drift: the cell is where it was", 123f, runeDriftedY(123f, 0f, wall.period), 0f)
        for (drift in listOf(5f, 99f, -37f, wall.period * 3f + 1f, -wall.period * 2f - 3f)) {
            val y = runeDriftedY(150f, drift, wall.period)
            assertTrue("wrapped into one period for drift $drift: $y", y >= 0f && y < wall.period)
        }
        assertEquals("fully faded at the very edge", 0f, runeEdgeFade(0f, 300f), 0.0001f)
        assertEquals(0f, runeEdgeFade(300f, 300f), 0.0001f)
        assertEquals("untouched in the middle", 1f, runeEdgeFade(150f, 300f), 0.0001f)
        assertEquals("not scrolling: no fade anywhere", 1f, runeScrollAlphaScale(0f, 2f, 300f), 0f)
        assertTrue("scrolling: the edge runes fade", runeScrollAlphaScale(1f, 2f, 300f) < 0.1f)
        assertEquals("scrolling: the middle is as it was", 1f, runeScrollAlphaScale(1f, 150f, 300f), 0.0001f)
    }

    @Test
    fun theRestingShimmerIsVeryLowAndOffMeansExactlyTheRestAlpha() {
        assertEquals(TrackpadVisuals.RestAlpha, runeRestAlpha(0.37f, 5, 0f), 0f)
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in 0 until 40) for (p in 0 until 20) {
            val a = runeRestAlpha(p / 20f, i, TrackpadVisuals.ShimmerAmplitude)
            lo = minOf(lo, a)
            hi = maxOf(hi, a)
        }
        assertTrue("the swing is within the amplitude: $lo..$hi", lo >= TrackpadVisuals.RestAlpha - TrackpadVisuals.ShimmerAmplitude - 0.0001f && hi <= TrackpadVisuals.RestAlpha + TrackpadVisuals.ShimmerAmplitude + 0.0001f)
        assertTrue("and it is very low: ${TrackpadVisuals.ShimmerAmplitude}", TrackpadVisuals.ShimmerAmplitude <= 0.03f)
        assertTrue("it does swing", hi - lo > TrackpadVisuals.ShimmerAmplitude)
    }

    // ---- item 3: plates with life ----------------------------------------------------------------------------------

    @Test
    fun aPlateBreathesAtRestAndGlowsBrighterWhenLitOrPressed() {
        val low = emberPlateStrength(emberWave(0.75f), lit = false, pressed = false)
        val high = emberPlateStrength(emberWave(0.25f), lit = false, pressed = false)
        assertTrue("the breath is low amplitude: $low..$high", high > low && high - low < 0.5f)
        assertEquals("static phase is the middle of the wave", 0.5f, emberWave(EmberStaticPhase), 0.0001f)
        assertTrue(emberPlateStrength(0.5f, lit = true, pressed = false) > emberPlateStrength(0.5f, lit = false, pressed = false))
        assertTrue(emberPlateStrength(0.5f, lit = false, pressed = true) > emberPlateStrength(0.5f, lit = false, pressed = false))
        assertTrue(emberPlateStrength(1f, lit = true, pressed = true) <= 1f)
        assertEquals("about five seconds a breath", 5_000L, EmberBreathPeriodMs)
        assertTrue(emberPhaseAt(2_500L, EmberBreathPeriodMs) in 0f..1f)
    }

    @Test
    fun theEmberClockRunsOnlyWhenMotionIsAllowedAndThePaneIsSeen() {
        assertTrue(emberClockRuns(reducedMotion = false, live = true))
        assertFalse("reduced motion: the static rendering", emberClockRuns(reducedMotion = true, live = true))
        assertFalse("parked (D24): no clock", emberClockRuns(reducedMotion = false, live = false))
        assertFalse(emberClockRuns(reducedMotion = true, live = false))
    }

    @Test
    fun everyThemesPlateDrawsItsOwnEmberInsideAndOut() {
        val centres = mutableSetOf<Int>()
        for ((name, palette) in palettes) {
            val rest = render(120, 84) {
                inset(12f) { drawEmberPlate(palette, platformConsoleIconBrush(palette), 8.dp, strength = 0.5f, lit = false) }
            }
            val lit = render(120, 84) {
                inset(12f) { drawEmberPlate(palette, platformConsoleIconBrush(palette), 8.dp, strength = 1f, lit = true) }
            }
            assertTrue("$name: the plate body is drawn", rest[60, 42].alpha > 0f)
            assertTrue("$name: the ember glow reaches past the plate's edge", rest[10, 42].alpha > 0f)
            assertTrue("$name: lit glows brighter outside: ${rest[10, 42].alpha} vs ${lit[10, 42].alpha}", lit[10, 42].alpha > rest[10, 42].alpha)
            assertTrue("$name: and the lit body is whole: ${lit[60, 42].alpha}", lit[60, 42].alpha >= rest[60, 42].alpha)
            assertEquals("$name: nothing is drawn far outside the glow", 0f, rest[1, 1].alpha, 0f)
            centres += rest[60, 42].toArgb()
        }
        assertTrue("the themes do not all draw one colour: $centres", centres.size >= 2)
    }

    // ---- item 4: Back and the gauge ---------------------------------------------------------------------------------

    @Test
    fun theBackPlateReadsBackThenTheReturnSign() {
        assertEquals("Back ↩", MimirTrackpadCopy.BackPlate)
        showPad(Host())
        compose.onAllNodes(hasText("Back ↩") or hasContentDescription("Back ↩"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun theHeaderPlatesAndTheGaugeAreAll48DpTall() {
        showPad(Host())
        for (description in listOf(MimirTrackpadCopy.BackDescription, MimirTrackpadCopy.KeyboardPlate, MimirTrackpadCopy.CloseDescription)) {
            val node = compose.onNodeWithContentDescription(description).fetchSemanticsNode()
            assertEquals("$description is 48 dp tall: ${dp(node.size.height)}", 48f, dp(node.size.height), 0.6f)
        }
        val gauge = compose.onNodeWithTag(MimirTrackpadSpeedGaugeTag, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("the gauge is 48 dp tall in all: ${dp(gauge.size.height)}", 48f, dp(gauge.size.height), 0.6f)
        // the bar is one slim 4 dp line, and its touch target is the gauge's whole 48 dp row, not the line.
        val bar = compose.onNodeWithTag(MimirTrackpadSpeedBarTag, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("the bar is a slim line: ${dp(bar.size.height)}", 4f, dp(bar.size.height), 0.6f)
        assertTrue("and its touch target is the gauge row, ${dp(gauge.size.height)} dp tall", dp(gauge.size.height) >= 47.9f)
    }

    @Test
    fun theGaugeSaysSlowAndFastAroundOneSlimBarAndTapsSetTheLevelDirectly() {
        val host = Host()
        showPad(host)
        compose.onAllNodes(hasText(MimirTrackpadCopy.SlowLabel) or hasContentDescription(MimirTrackpadCopy.SlowLabel), useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodes(hasText(MimirTrackpadCopy.FastLabel) or hasContentDescription(MimirTrackpadCopy.FastLabel), useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, MimirTrackpadSpeedBarTag), useUnmergedTree = true).assertCountEquals(1)
        val gauge = compose.onNodeWithTag(MimirTrackpadSpeedGaugeTag, useUnmergedTree = true)
        val bar = compose.onNodeWithTag(MimirTrackpadSpeedBarTag, useUnmergedTree = true)
        gauge.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "3 of 5"))
        bar.assert(SemanticsMatcher.expectValue(MimirTrackpadSpeedFillSemantics, 0.6f))
        // From 3, a tap at the bar's last fifth is 5 (the old dial would have gone to 4), then its first fifth is 1: no cycling.
        compose.tapGaugeStep(5)
        assertEquals(5, host.speed)
        gauge.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "5 of 5"))
        bar.assert(SemanticsMatcher.expectValue(MimirTrackpadSpeedFillSemantics, 1f))
        compose.tapGaugeStep(1)
        assertEquals(1, host.speed)
        bar.assert(SemanticsMatcher.expectValue(MimirTrackpadSpeedFillSemantics, 0.2f))
        // The fill snaps to the step: a tap just inside a fifth's edges lands on that fifth.
        val spot = compose.gaugeSpot()
        gauge.performTouchInput { down(Offset(spot.left + spot.stepPx * 3.05f, spot.midY)); up() }
        compose.waitForIdle()
        assertEquals("the fourth fifth starts a hair past 3 steps", 4, host.speed)
        bar.assert(SemanticsMatcher.expectValue(MimirTrackpadSpeedFillSemantics, 0.8f))
    }

    @Test
    fun aTapOnTheLabelsOrOutsideTheBarSetsNothingButTheBarsWholeColumnTakesTheTap() {
        val host = Host()
        showPad(host)
        val spot = compose.gaugeSpot()
        val gauge = compose.onNodeWithTag(MimirTrackpadSpeedGaugeTag, useUnmergedTree = true)
        gauge.performTouchInput { down(Offset(2f, spot.midY)); up() }
        compose.waitForIdle()
        assertEquals("the Slow label is not a button", 0, host.settings)
        // The bar is 4 dp tall; a tap well above and below it, in the same column, still sets it: the target is the whole row.
        gauge.performTouchInput { down(Offset(spot.stepX(5), 2f)); up() }
        compose.waitForIdle()
        gauge.performTouchInput { down(Offset(spot.stepX(2), spot.height - 2f)); up() }
        compose.waitForIdle()
        assertEquals(listOf(5, 2), host.speedCalls)
    }

    @Test
    fun aBarTapsOnlyOnAReleaseInsideItThatStayedWithinSlop() {
        val host = Host()
        showPad(host)
        val spot = compose.gaugeSpot()
        compose.onNodeWithTag(MimirTrackpadSpeedGaugeTag, useUnmergedTree = true).performTouchInput {
            down(Offset(spot.stepX(5), spot.midY))
            moveBy(Offset(0f, 300f))
            up()
        }
        compose.waitForIdle()
        assertEquals("a drag-out release set nothing", 0, host.settings)
        assertEquals(TrackpadSpeed.Default, host.speed)
    }

    // ---- item 5: the pointer-on-a-pad icon ---------------------------------------------------------------------------

    @Test
    fun theBottomPlateIsThePointerOnAPadOnEveryThemeAndStillNamedTrackpad() {
        for ((name, palette) in palettes) {
            // 34 units at 2 px: the design's pad outline, divider and arrow, drawn from the palette.
            val map = render(68, 68) { drawPointerOnPad(palette) }
            assertTrue("$name: the arrow's body is struck metal, whole: ${map[27, 30].alpha}", map[27, 30].alpha > 0.99f)
            assertTrue("$name: the pad's outline is drawn: ${map[6, 34].alpha}", map[6, 34].alpha > 0f)
            assertTrue("$name: and its centre divider", map[34, 48].alpha > 0f)
            assertEquals("$name: the pad's inside away from the arrow is empty", 0f, map[52, 24].alpha, 0f)
            assertEquals("$name: and so is the outside", 0f, map[2, 2].alpha, 0f)
        }
        compose.setContent {
            MimirTheme {
                CompositionLocalProvider(LocalMimirPalette provides MimirThemePalette.Surtr) { BottomTrackpadPlate(onOpen = {}) }
            }
        }
        compose.onNodeWithContentDescription("Trackpad").assertExists()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).assertCountEquals(0)
        val plate = compose.onNodeWithTag(BottomTrackpadPlateTag).fetchSemanticsNode()
        assertTrue("a 48 dp target: ${dp(plate.size.width)} x ${dp(plate.size.height)}", dp(plate.size.width) >= 47.9f && dp(plate.size.height) >= 47.9f)
    }

    @Test
    fun theOldRoundedRectWithADotIsGone() {
        val source = File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirBottomTrackpadPlate.kt").readText()
        assertFalse("no pointer dot", source.contains("GlyphDotDp") || source.contains("drawCircle"))
        assertTrue("the arrow is a Canvas path", source.contains("Path()") && source.contains("drawPath(arrow"))
    }

    // ---- two themes through the whole pad -----------------------------------------------------------------------------

    @Test
    fun theWholePadMountsOnDefaultAndAFieldDragMovesThePointer() {
        val host = Host()
        showPad(host, MimirThemePalette.Default)
        compose.onNodeWithTag(MimirTrackpadPadFieldTag, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(MimirTrackpadPadDrumTag, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(MimirTrackpadSpeedGaugeTag, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(MimirTrackpadPadFieldTag, useUnmergedTree = true).performTouchInput { down(center); moveBy(Offset(30f, 10f)); up() }
        compose.waitForIdle()
        assertTrue("a drag moved the pointer: ${host.calls}", host.calls.contains("move"))
    }

    @Test
    fun theWholePadMountsOnASecondThemeToo() {
        val host = Host()
        showPad(host, MimirThemePalette.Surtr)
        compose.onNodeWithTag(MimirTrackpadPadFieldTag, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(MimirTrackpadPadDrumTag, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(MimirTrackpadPadDrumTag, useUnmergedTree = true).performTouchInput { down(center); moveBy(Offset(0f, 40f)); up() }
        compose.waitForIdle()
        assertTrue("the drum scrolls on Surtr too: ${host.calls}", host.calls.contains("scroll"))
    }

    // ---- the rules the new code lives by ------------------------------------------------------------------------------

    private fun source(name: String) = File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name").readText()

    @Test
    fun theNewEffectsUseThemeTokensOnlyAndNoHexLiteral() {
        for (name in listOf("MimirEmberLife.kt", "MimirMetalPlate.kt", "MimirTrackpadSlab.kt", "MimirTrackpadMill.kt", "MimirTrackpadPad.kt", "MimirTrackpadRuneWall.kt", "MimirTrackpadTooledWall.kt", "MimirBottomTrackpadPlate.kt")) {
            val text = source(name)
            assertFalse("$name has a hex colour literal", Regex("""Color\(\s*0x""").containsMatchIn(text))
            assertFalse("$name has a Color.parseColor / argb literal", text.contains("parseColor") || Regex("""Color\(\s*\d""").containsMatchIn(text))
        }
    }

    @Test
    fun theEmberClockIsAParkedAwareReducedMotionAwareFrameLoopAndNothingElseRunsForever() {
        val ember = source("MimirEmberLife.kt")
        assertTrue("the clock follows the pane's liveness (D24) and the host's", ember.contains("snapshotFlow { (live.value && host.value) to life.trigger.intValue }"))
        assertTrue("and reduced motion", ember.contains("mimirReducedMotion()") && ember.contains("if (reduced)"))
        assertTrue("an infinite animation frame loop, which a test host cancels", ember.contains("withInfiniteAnimationFrameNanos"))
        for (name in listOf("MimirEmberLife.kt", "MimirTrackpadSlab.kt", "MimirTrackpadMill.kt", "MimirTrackpadPad.kt", "MimirTrackpadRuneWall.kt", "MimirBottomTrackpadPlate.kt")) {
            val text = source(name)
            assertFalse("$name runs an endless transition outside the parked-aware clock", text.contains("rememberInfiniteTransition") || text.contains("infiniteRepeatable"))
        }
    }

    @Test
    fun noDialAndNoCycleSurvives() {
        val pad = source("MimirTrackpadPad.kt")
        assertFalse(pad.contains("PadSpeedDial") || pad.contains("cycleSpeed"))
        assertFalse(source("MimirTrackpad.kt").contains("fun cycleSpeed"))
        assertTrue(pad.contains("PadSpeedGauge"))
    }

    @Test
    fun theHeaderHeldPlatesAre48Dp() {
        assertEquals(48.dp, PadHeaderHeight)
        assertEquals(56.dp, PadDrumWidth)
    }

    // ---- pad9: the ember life is BOUNDED ------------------------------------------------------------------

    /** Frame times 16 ms apart, in ns. [onFrame] runs with the frame's index, to let a test touch the surface mid-window. */
    private suspend fun EmberLife.runCounting(onFrame: (Int) -> Unit = {}): Int {
        var frames = 0
        var t = 0L
        runWindow {
            frames += 1
            onFrame(frames)
            t += 16_000_000L
            t
        }
        return frames
    }

    @Test
    fun theWindowEndsAndThenAsksForNoMoreFrames() = runBlocking {
        val life = EmberLife()
        val frames = life.runCounting()
        val expected = ((EmberLifeWindowMs + EmberEaseMs) / 16L).toInt()
        assertTrue("about the window and its ease, then it stops: $frames frames for $expected", frames in (expected - 3)..(expected + 3))
        assertFalse("nothing is running", life.running)
        assertEquals("the level is at rest", 0f, life.level.floatValue, 0f)
        assertEquals("a second of rest later it would ask for nothing: the loop has returned", 0f, emberWindowLevel(EmberLifeWindowMs + EmberEaseMs), 0f)
    }

    @Test
    fun theWindowIsAboutSixSecondsAndEasesOutNotCuts() {
        assertEquals(6_000L, EmberLifeWindowMs)
        assertEquals(1f, emberWindowLevel(0L), 0f)
        assertEquals(1f, emberWindowLevel(EmberLifeWindowMs - 1), 0f)
        val mid = emberWindowLevel(EmberLifeWindowMs + EmberEaseMs / 2)
        assertTrue("it eases: $mid", mid > 0.4f && mid < 0.6f)
        assertEquals(0f, emberWindowLevel(EmberLifeWindowMs + EmberEaseMs), 0f)
        assertEquals(0f, emberWindowLevel(60_000L), 0f)
    }

    @Test
    fun aTouchReArmsTheWindowAndAnIdleLifeIsWokenByOne() = runBlocking {
        val still = EmberLife().runCounting()
        val life = EmberLife()
        val touched = life.runCounting { frame -> if (frame == 100) life.arm() }
        assertTrue("a touch 100 frames in restarts the window: $still without, $touched with", touched > still + 80)
        assertFalse(life.running)
        val before = life.trigger.intValue
        life.arm()
        assertEquals("an idle life is woken by a touch", before + 1, life.trigger.intValue)
    }

    @Test
    fun theStaticRestingLookIsTheMidPhaseNotDark() {
        val life = EmberLife()
        assertEquals("a life that has not run, or has finished, sits at the middle of its wave", 0.5f, life.wave(), 0f)
        assertEquals(0.5f, emberEasedWave(emberWave(0.25f), 0f), 0f)
        assertEquals(0.5f, emberEasedWave(emberWave(0.75f), 0f), 0f)
        assertEquals(emberWave(0.25f), emberEasedWave(emberWave(0.25f), 1f), 0.0001f)
        val rest = emberPlateStrength(life.wave(), lit = false, pressed = false)
        assertEquals("the same glow as the wave's own middle", emberPlateStrength(emberWave(EmberStaticPhase), false, false), rest, 0f)
        assertTrue("and not dark: $rest", rest > 0.5f)
    }

    @Test
    fun nothingRunsWhileParkedOffScreenOrReducedAndItWakesWhenLiveAgain() = runBlocking {
        suspend fun frames(life: EmberLife, reduced: Boolean, live: Boolean, host: Boolean): Triple<Int, Int, Int> {
            val liveState = mutableStateOf(live)
            val hostState = mutableStateOf(host)
            var count = 0
            var t = 0L
            val job = launch {
                driveEmberLife(life, reduced, liveState, hostState) {
                    count += 1
                    yield()
                    t += 16_000_000L
                    t
                }
            }
            repeat(30) { yield() }
            val first = count
            // The pane parks (or the screen goes off): the frames stop at once.
            liveState.value = false
            Snapshot.sendApplyNotifications()
            repeat(30) { yield() }
            val parked = count
            repeat(30) { yield() }
            val after = count
            job.cancelAndJoin()
            return Triple(first, parked, after)
        }
        val (parkedFirst, _, parkedAfter) = frames(EmberLife(), reduced = false, live = false, host = true)
        assertEquals("a parked pane asks for no frame", 0, parkedFirst)
        assertEquals(0, parkedAfter)
        val (offFirst, _, _) = frames(EmberLife(), reduced = false, live = true, host = false)
        assertEquals("a screen that is off (or a host that is stopped) asks for none", 0, offFirst)
        val (reducedFirst, _, reducedAfter) = frames(EmberLife(), reduced = true, live = true, host = true)
        assertEquals("reduced motion asks for none", 0, reducedFirst)
        assertEquals(0, reducedAfter)
        val (liveFirst, parkedNow, parkedLater) = frames(EmberLife(), reduced = false, live = true, host = true)
        assertTrue("a live pane animates: $liveFirst", liveFirst > 5)
        assertEquals("and parking it stops the frames at once: $parkedNow then $parkedLater", parkedNow, parkedLater)
    }

    @Test
    fun aLivePaneThatHasRestedAsksForNothingUntilATouchReArmsIt() = runBlocking {
        val life = EmberLife()
        val live = mutableStateOf(true)
        val host = mutableStateOf(true)
        var count = 0
        var t = 0L
        val job = launch {
            driveEmberLife(life, false, live, host) {
                count += 1
                yield()
                t += 16_000_000L
                t
            }
        }
        repeat(2_000) { yield() }
        val rested = count
        assertTrue("the window ran, then ended: $rested frames", rested in 300..600)
        repeat(200) { yield() }
        assertEquals("a rested life asks for no frame while nothing touches it", rested, count)
        life.arm()
        Snapshot.sendApplyNotifications()
        repeat(2_000) { yield() }
        assertTrue("a touch re-armed it: $count frames now", count > rested + 300)
        job.cancelAndJoin()
    }

    @Test
    fun theLifeTakesItsLivenessExplicitlyAndTheHostIsTheScreenAndTheLifecycle() {
        val ember = source("MimirEmberLife.kt")
        assertTrue("no default for the pane's liveness: reuse cannot make an always-on clock", ember.contains("fun rememberEmberLife(live: State<Boolean>): EmberLife {"))
        assertFalse(ember.contains("fun rememberEmberLife(live: State<Boolean> ="))
        assertTrue("the host is the lifecycle and the screen", ember.contains("PowerManager") && ember.contains("currentStateAsState") && ember.contains("ACTION_SCREEN_OFF"))
        assertTrue(emberHostRuns(lifecycleStarted = true, screenInteractive = true))
        assertFalse("screen off", emberHostRuns(lifecycleStarted = true, screenInteractive = false))
        assertFalse("host stopped", emberHostRuns(lifecycleStarted = false, screenInteractive = true))
        assertTrue("the pad passes its own liveness", source("MimirTrackpadPad.kt").contains("rememberEmberLife(padLive)"))
        assertTrue("the Bottom plate passes the pane's", source("MimirBottomTrackpadPlate.kt").contains("rememberEmberLife(LocalMimirMotionLive.current)"))
        for (name in listOf("MimirTrackpadPad.kt", "MimirTrackpadRuneWall.kt", "MimirTrackpadSlab.kt", "MimirBottomTrackpadPlate.kt")) {
            assertFalse("$name has no frame loop of its own", source(name).contains("withInfiniteAnimationFrameNanos"))
        }
    }

    @Test
    fun aTouchOnThePadOrAPlateReArmsTheLife() {
        val pad = source("MimirTrackpadPad.kt")
        assertTrue("the field", pad.contains("life.arm() // a touch on the pad re-opens the ember window"))
        assertTrue("the plates and the gauge bars", pad.contains("onPress = life::arm") && pad.indexOf("onPress = life::arm") != pad.lastIndexOf("onPress = life::arm"))
        assertTrue("the drum", source("MimirTrackpadMill.kt").contains("life.arm() // a touch on the drum re-opens the ember window"))
        assertTrue("the Bottom plate", source("MimirBottomTrackpadPlate.kt").contains("life.arm(); mimirReportPress(source, it)"))
        assertTrue("the shimmer is the life's and rests with it", pad.contains("TrackpadVisuals.ShimmerAmplitude * life.level.floatValue"))
    }

    // ---- pad10: screws, the etched rune, the sliding gauge ----------------------------------------------------------------------

    @Test
    fun theSlabHasFourCornerScrewsAndNoBracketsOrRivets() {
        val screws = slabScrews(400f, 300f, 13f)
        assertEquals(4, screws.size)
        val corners = setOf(13f to 13f, 387f to 13f, 387f to 287f, 13f to 287f)
        assertEquals("one in each corner and nothing at the mid-edges", corners, screws.map { it.center.x to it.center.y }.toSet())
        assertEquals("each slot is cut at its own angle", 4, screws.map { it.angleDeg }.toSet().size)
        val slab = source("MimirTrackpadSlab.kt")
        assertFalse("the corner brackets and the mid-edge rivets are gone", slab.contains("Bracket") || slab.contains("bracket(") || slab.contains("Rivet") || slab.contains("rivet"))
        assertTrue(slab.contains("drawSlabScrew") && slab.contains("slabScrews("))
        assertFalse("no hex literal", Regex("""Color\(\s*0x""").containsMatchIn(slab))
    }

    @Test
    fun aScrewIsARoundMetalHeadWithAnInsetRingAndASlotAtItsOwnAngleOnEveryTheme() {
        for ((name, palette) in palettes) {
            val flat = render(80, 80) { drawSlabScrew(palette, Offset(40f, 40f), 12f, 0f) }
            val turned = render(80, 80) { drawSlabScrew(palette, Offset(40f, 40f), 12f, 90f) }
            assertTrue("$name: the head is drawn", flat[40, 32].alpha > 0.99f)
            assertTrue("$name: the slot is darker than the metal beside it: ${flat[40, 40].luminance()} vs ${flat[40, 32].luminance()}", flat[40, 40].luminance() < flat[40, 32].luminance())
            assertTrue("$name: and the slot turns with the angle: a horizontal slot leaves (40,32) metal, a vertical one cuts it", turned[40, 32].luminance() < flat[40, 32].luminance())
            assertTrue("$name: the inset shadow ring sits just outside the head: ${flat[53, 40].alpha}", flat[53, 40].alpha > 0f)
            assertEquals("$name: nothing beyond the ring", 0f, flat[62, 40].alpha, 0f)
            assertTrue("$name: the head is metal-lit up and to the left, falling off down and to the right: ${flat[36, 36].luminance()} vs ${flat[46, 46].luminance()}", flat[36, 34].luminance() > flat[46, 47].luminance())
        }
    }

    @Test
    fun theDrumsRuneIsEtchedInTheHeaderPlatesMetalAndOnlyCatchesEmberWhileTurning() {
        val mill = source("MimirTrackpadMill.kt")
        assertTrue("the face is the console-icon metal brush the header plates use", mill.contains("val metal = platformConsoleIconBrush(palette)") && mill.contains("drawText(rune, brush = metal, topLeft = topLeft)"))
        assertTrue("with a dark groove above and a light lip below", mill.contains("topLeft - Offset(0f, lip * 0.8f)") && mill.contains("topLeft + Offset(0f, lip)"))
        assertTrue("the plates use the same brush", source("MimirEmberLife.kt").contains("platformConsoleIconBrush(palette)"))
        assertFalse("no glowing ember text at rest", mill.contains("DrumRuneRest") || mill.contains("DrumRuneLit"))
        assertEquals("at rest it is metal: no ember", 0f, drumRuneEmber(0f), 0f)
        assertTrue("turning, it catches a little", drumRuneEmber(1f) > 0f && drumRuneEmber(1f) <= 0.35f)
        assertTrue("and eases with the turn", drumRuneEmber(0.5f) < drumRuneEmber(1f))
    }

    @Test
    fun theGaugeStepIsTheBarColumnUnderTheFingerClampedAtBothEnds() {
        assertEquals(1, gaugeStepAt(9f, 10f, 85f, 5))
        assertEquals(1, gaugeStepAt(10f, 10f, 85f, 5))
        assertEquals(1, gaugeStepAt(26.9f, 10f, 85f, 5))
        assertEquals(2, gaugeStepAt(27f, 10f, 85f, 5))
        assertEquals(5, gaugeStepAt(94.9f, 10f, 85f, 5))
        assertEquals(5, gaugeStepAt(400f, 10f, 85f, 5))
        assertEquals("unmeasured", 1, gaugeStepAt(50f, 0f, 0f, 5))
        assertTrue(gaugeOnBars(50f, 10f, 85f))
        assertFalse(gaugeOnBars(5f, 10f, 85f))
    }

    @Test
    fun aSwipeMovesTheLevelFromWhereItWasOneStepForEachFifthOfTheBarTheFingerTravelsAndPartialStepsCountForNothing() {
        // A 50 px bar: ten px a step.
        assertEquals(3, gaugeSlideStep(3, 0f, 50f, 5))
        assertEquals("not yet a whole step", 3, gaugeSlideStep(3, 9.9f, 50f, 5))
        assertEquals("and not yet a whole step the other way", 3, gaugeSlideStep(3, -9.9f, 50f, 5))
        assertEquals("right is faster", 4, gaugeSlideStep(3, 10f, 50f, 5))
        assertEquals("left is slower", 2, gaugeSlideStep(3, -10f, 50f, 5))
        assertEquals(5, gaugeSlideStep(3, 20f, 50f, 5))
        assertEquals(1, gaugeSlideStep(3, -20f, 50f, 5))
        assertEquals("the ends are clamped", 5, gaugeSlideStep(3, 900f, 50f, 5))
        assertEquals(1, gaugeSlideStep(3, -900f, 50f, 5))
        assertEquals("from the end, further right stays at the end", 5, gaugeSlideStep(5, 30f, 50f, 5))
        assertEquals("an unmeasured bar leaves the level where it was", 3, gaugeSlideStep(3, 400f, 0f, 5))
    }

    @Test
    fun aSwipeAlongTheGaugeMovesTheLevelStepByStepLeftThenRightAndNeverReachesThePadField() {
        val host = Host().also { it.speed = 3 }
        showPad(host)
        compose.waitForIdle()
        val spot = compose.gaugeSpot()
        val gauge = compose.onNodeWithTag(MimirTrackpadSpeedGaugeTag, useUnmergedTree = true)
        // Left, from the middle step: a whole step of travel is 3 -> 2, a second one 2 -> 1.
        compose.swipeGauge(spot.stepX(3), -2, slopPx)
        assertEquals("left is slower, one change per step", listOf(2, 1), host.speedCalls)
        assertEquals(1, host.speed)
        gauge.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1 of 5"))
        compose.onNodeWithTag(MimirTrackpadSpeedBarTag, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(MimirTrackpadSpeedFillSemantics, 0.2f))
        // Right, from the first step: each step of travel is one faster, up to the end.
        compose.swipeGauge(spot.stepX(1), 4, slopPx)
        assertEquals("right is faster, one change per step", listOf(2, 1, 2, 3, 4, 5), host.speedCalls)
        assertEquals("and 5 is what the host holds (the store Settings reads)", 5, host.speed)
        gauge.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "5 of 5"))
        compose.onNodeWithTag(MimirTrackpadSpeedBarTag, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(MimirTrackpadSpeedFillSemantics, 1f))
        assertTrue("the swipe never reached the pad field: ${host.calls}", host.calls.none { it == "move" || it == "click" || it == "scroll" })
    }

    @Test
    fun aSwipeCanStartOnALabelAndAWobbleInsideTheSlopIsOnlyATap() {
        val host = Host().also { it.speed = 3 }
        showPad(host)
        compose.waitForIdle()
        val spot = compose.gaugeSpot()
        val gauge = compose.onNodeWithTag(MimirTrackpadSpeedGaugeTag, useUnmergedTree = true)
        // A wobble inside the slop is not a swipe: it is the tap it began as, on the step it began on.
        gauge.performTouchInput {
            down(Offset(spot.stepX(4), spot.midY))
            moveBy(Offset(1f, 0f))
            up()
        }
        compose.waitForIdle()
        assertEquals("a tap on the fourth fifth", listOf(4), host.speedCalls)
        // Started on the Slow label, left of the bar, swiping right two steps from 4: 5, and the end holds at 5.
        compose.swipeGauge(2f, 3, slopPx)
        assertEquals("further right than the end changes nothing more", listOf(4, 5), host.speedCalls)
        assertEquals(5, host.speed)
    }

    @Test
    fun theGaugeIsASliderToAScreenReaderWithSetProgressAcrossOneToFive() {
        val host = Host()
        showPad(host)
        val gauge = compose.onNodeWithTag(MimirTrackpadSpeedGaugeTag, useUnmergedTree = true)
        val info = gauge.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(3f, info.current, 0f)
        assertEquals(1f..5f, info.range)
        gauge.performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }
        compose.waitForIdle()
        assertEquals(5, host.speed)
        assertEquals("the reader sees the new level", 5f, gauge.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
        gauge.performSemanticsAction(SemanticsActions.SetProgress) { it(2.4f) }
        compose.waitForIdle()
        assertEquals("a fraction snaps to the nearest step", 2, host.speed)
        gauge.performSemanticsAction(SemanticsActions.SetProgress) { it(99f) }
        compose.waitForIdle()
        assertEquals("and the ends are clamped", 5, host.speed)
    }

    @Test
    fun theSlideKeepsItsFingerAcrossRecompositionsAndConsumesWhatItTakes() {
        val tap = source("MimirTrackpadTap.kt")
        val slide = tap.substringAfter("internal fun Modifier.mimirGaugeSlide(")
        assertTrue("keyed on nothing, so a recomposition mid-slide cannot drop the finger", slide.contains("pointerInput(Unit)"))
        assertTrue("a slide's changes are consumed", slide.contains("change.consume()"))
        assertTrue("the gauge reads its latest setter through a state", source("MimirTrackpadPad.kt").contains("rememberUpdatedState(onSet)"))
    }

    // ---- pad11: the signature struck-metal plate ----------------------------------------------------------------------------------

    @Test
    fun everyHeaderPlateDrawsThroughTheSharedMetalPlateNotThePaleEmberOne() {
        val pad = source("MimirTrackpadPad.kt")
        assertEquals("the plates (Back, Keys, Close) and the gauge's housing", 2, Regex("""\.mimirMetalPlate\(""").findAll(pad).count())
        assertTrue(pad.contains(".mimirMetalPlate(palette, PadPlateRadius, lit = lit, life = life, pressed = pressed, raised = true)"))
        assertTrue("the gauge housing", pad.contains(".mimirMetalPlate(palette, PadPlateRadius, lit = false, life = life, pressed = pressed, raised = true)"))
        assertFalse("no pale ember plate left in the header", pad.contains(".emberPlate("))
        val plate = source("MimirBottomTrackpadPlate.kt")
        assertTrue("the Bottom header's Trackpad plate", plate.contains(".mimirMetalPlate(palette, PlateCorner, lit = false, life = life, pressed = pressed)"))
        assertFalse(plate.contains(".emberPlate("))
        val metal = source("MimirMetalPlate.kt")
        assertTrue("the shared plate is the transport's: leather ground at 27 and 75 percent, a metal rim, a press warms the top",
            metal.contains("MimirMetalGroundTopStop = 0.27f") && metal.contains("MimirMetalGroundBottomStop = 0.75f") && metal.contains("hero.coverTop") && metal.contains("counterAccent"))
        assertEquals("the rim is the transport's 1.25 dp", 1.25.dp, MimirMetalRimDp)
        assertTrue("and a header plate is in the card corner", pad.contains("private val PadPlateRadius = MimirSpacing.radiusCard") && plate.contains("private val PlateCorner = MimirSpacing.radiusCard"))
    }

    @Test
    fun labelsAndIconsAreInkedInTheMetalBrushNotPaletteTextColours() {
        val pad = source("MimirTrackpadPad.kt")
        assertEquals("the plate label is a brush-filled pill", 1, Regex("""MimirTypeScale\.Pill\.copy\(brush = metal, alpha = MimirMetalRestAlpha\)""").findAll(pad).count())
        assertEquals("and Slow and Fast, one type step smaller, share one brush-filled style", 1, Regex("""MimirTypeScale\.MicroStatus\.copy\(brush = metal, alpha = MimirMetalRestAlpha\)""").findAll(pad).count())
        assertFalse("no palette text colour on a header label", pad.contains("color = palette.onAccent"))
        val plate = source("MimirBottomTrackpadPlate.kt")
        assertTrue("the glyph", plate.contains("val metal = platformConsoleIconBrush(palette)") && plate.contains("drawPath(arrow, brush = metal)"))
        assertFalse("no palette ink in the glyph", plate.contains("palette.textSecondary") || plate.contains("palette.accentBright"))
    }

    @Test
    fun theHeaderPlateIsLeatherAndMetalOnEveryThemeAndRestsAsPureMetal() {
        for ((name, palette) in palettes) {
            val metal = platformConsoleIconBrush(palette)
            val rest = render(120, 84) { inset(12f) { drawMetalPlateWithEmber(palette, metal, 14f, warmed = false, ember = 0f) } }
            val warmed = render(120, 84) { inset(12f) { drawMetalPlateWithEmber(palette, metal, 14f, warmed = true, ember = 0f) } }
            val glowing = render(120, 84) { inset(12f) { drawMetalPlateWithEmber(palette, metal, 14f, warmed = false, ember = 1f) } }
            assertTrue("$name: a leather ground, whole: ${rest[60, 42].alpha}", rest[60, 42].alpha > 0.99f)
            assertTrue("$name: a metal rim struck round it: ${rest[12, 42].alpha}", rest[12, 42].alpha > 0f && rest[12, 42].toArgb() != rest[60, 42].toArgb())
            assertEquals("$name: at rest nothing is drawn past the metal", 0f, rest[10, 42].alpha, 0f)
            assertTrue("$name: a lit or pressed plate warms the top of its ground: ${rest[60, 16].toArgb()} vs ${warmed[60, 16].toArgb()}", rest[60, 16].toArgb() != warmed[60, 16].toArgb())
            assertTrue("$name: the ember life is an add-on: a faint halo past the rim while it is open", glowing[10, 42].alpha > 0f)
            assertEquals("$name: and the metal under it is the same plate", rest[60, 50].toArgb(), glowing[60, 50].toArgb())
        }
    }

    @Test
    fun theEmberAddOnIsNothingAtRestAndOnlyTheWindowsBreathOtherwise() {
        assertEquals(0f, mimirMetalEmber(null), 0f)
        val life = EmberLife()
        assertEquals("a life at rest adds nothing", 0f, mimirMetalEmber(life), 0f)
        life.levelState.floatValue = 1f
        life.breathState.floatValue = 0.25f
        val high = mimirMetalEmber(life)
        life.breathState.floatValue = 0.75f
        val low = mimirMetalEmber(life)
        assertTrue("in its window it breathes, faintly: $low..$high", low > 0f && high > low && high <= 1f)
        life.levelState.floatValue = 0.5f
        assertTrue("and eases out with the level", mimirMetalEmber(life) < high)
    }
}

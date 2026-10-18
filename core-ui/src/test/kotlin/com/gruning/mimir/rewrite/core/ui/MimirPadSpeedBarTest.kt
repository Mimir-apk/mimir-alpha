package com.gruning.mimir.rewrite.core.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.toArgb
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pad header's speed gauge: not five rising ember columns but "Slow", ONE slim progress bar (4 dp,
 * rounded ends, the level in the accent over the muted track) and "Fast", the two words one type step smaller. Rendered at font scale 1.0 and 1.3: the words
 * stay on one line, nothing is cut, the bar is visible and sits between them inside the gauge. The swipe and the tap are in [MimirPad8Test].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w600dp-h500dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirPadSpeedBarTest {

    @get:Rule
    val compose = createComposeRule()

    private class Host : MimirTrackpadHost {
        override val serviceConnected: Boolean = true
        var speed = TrackpadSpeed.Default
        override val speedStep: Int get() = speed
        override fun setSpeed(step: Int): Int {
            speed = TrackpadSpeed.clamp(step)
            return speed
        }
        override fun begin(): MimirTrackpadHost = this
        override fun pointerMove(dx: Float, dy: Float) = Unit
        override fun click() = Unit
        override fun doubleClick() = Unit
        override fun scrollBy(dx: Float, dy: Float) = Unit
        override fun scrollEnd() = Unit
        override fun scrollCancel() = Unit
        override fun holdBegin() = Unit
        override fun holdMove(dx: Float, dy: Float) = Unit
        override fun holdEnd() = Unit
        override fun openAccessibilitySettings() = Unit
        override fun end() = Unit
    }

    private var hostView: android.view.View? = null

    /** Each chrome word's real layout, by its spoken text: the text's own semantics are cleared, so the layout observer is the only way to ask a line how it laid out. */
    private val observed = mutableMapOf<String, TextLayoutResult>()

    private fun showPad(fontScale: Float) {
        compose.setContent {
            hostView = LocalView.current
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalMimirTextLayoutObserver provides { spoken, layout -> observed[spoken] = layout },
            ) {
                MimirTheme {
                    MimirTrackpadPad(session = Host(), keyboardWaiting = false, onKeyboard = {}, onClose = {}, palette = MimirThemePalette.Default)
                }
            }
        }
        compose.waitForIdle()
    }

    private class Label(val text: String, val left: Float, val right: Float, val layout: TextLayoutResult)

    /** The word [text]: where its node sits and how its line laid out. */
    private fun label(text: String): Label? {
        val layout = observed[text] ?: return null
        val node = compose.onNode(hasContentDescription(text), useUnmergedTree = true).fetchSemanticsNode()
        return Label(text, node.boundsInRoot.left, node.boundsInRoot.right, layout)
    }

    private fun assertGauge(fontScale: Float) {
        showPad(fontScale)
        val density = compose.density.density
        fun dp(px: Float) = px / density
        val gauge = compose.onNodeWithTag(MimirTrackpadSpeedGaugeTag, useUnmergedTree = true).fetchSemanticsNode()
        val bar = compose.onNodeWithTag(MimirTrackpadSpeedBarTag, useUnmergedTree = true).fetchSemanticsNode()
        val tag = "fontScale=$fontScale"

        // One slim bar: 4 dp tall, shorter than the five columns it replaces (5 x 17 dp), inside the 48 dp gauge row.
        assertEquals("$tag the bar is 4 dp tall", 4f, dp(bar.size.height.toFloat()), 0.6f)
        assertTrue("$tag the bar is shorter than the old columns: ${dp(bar.size.width.toFloat())}", dp(bar.size.width.toFloat()) in 20f..85f)
        assertTrue("$tag the bar is visible: ${bar.boundsInRoot}", bar.size.width > 0 && bar.size.height > 0)
        assertEquals("$tag the gauge row is 48 dp tall", 48f, dp(gauge.size.height.toFloat()), 0.6f)
        assertTrue("$tag the bar is inside the gauge", bar.boundsInRoot.left >= gauge.boundsInRoot.left - 0.5f && bar.boundsInRoot.right <= gauge.boundsInRoot.right + 0.5f)
        assertTrue("$tag and level 3 of 5 fills three fifths", abs(bar.config[MimirTrackpadSpeedFillSemantics] - 0.6f) < 0.001f)

        // The words: one line each, nothing cut, one type step below the pill size, Slow before the bar and Fast after it.
        val slow = assertNotNull_(label(MimirTrackpadCopy.SlowLabel), "$tag Slow is on screen")
        val fast = assertNotNull_(label(MimirTrackpadCopy.FastLabel), "$tag Fast is on screen")
        for (label in listOf(slow, fast)) {
            assertEquals("$tag ${label.text} stays on one line", 1, label.layout.lineCount)
            assertFalse("$tag ${label.text} is not cut", label.layout.didOverflowWidth || label.layout.isLineEllipsized(0))
            val size = label.layout.layoutInput.style.fontSize
            assertEquals("$tag ${label.text} is set at the micro step", MimirTypeStep.Micro.value, size.value, 0.001f)
            assertTrue("$tag and that is smaller than the pill's ${MimirTypeStep.Label.value}", size.value < MimirTypeStep.Label.value)
        }
        assertTrue("$tag Slow ends before the bar starts", slow.right <= bar.boundsInRoot.left + 0.5f)
        assertTrue("$tag Fast starts after the bar ends", fast.left >= bar.boundsInRoot.right - 0.5f)
        assertTrue("$tag both words are inside the gauge", slow.left >= gauge.boundsInRoot.left - 0.5f && fast.right <= gauge.boundsInRoot.right + 0.5f)

        // The header still holds: the gauge and the plates around it are on screen.
        val rootWidth = compose.onRoot().fetchSemanticsNode().size.width.toFloat()
        assertTrue("$tag the gauge is on screen", gauge.boundsInRoot.right <= rootWidth + 0.5f)
        val keyboard = compose.onNodeWithContentDescription(MimirTrackpadCopy.KeyboardPlate).fetchSemanticsNode()
        assertTrue("$tag the Keyboard plate is on screen", keyboard.boundsInRoot.right <= rootWidth + 0.5f)
        assertTrue("$tag the gauge does not run into the Keyboard plate", gauge.boundsInRoot.right <= keyboard.boundsInRoot.left + 0.5f)
    }

    private fun <T : Any> assertNotNull_(value: T?, message: String): T {
        assertNotNull(message, value)
        return value!!
    }

    @Test
    fun theGaugeIsOneSlimBarBetweenTwoSmallerSingleLineWordsAtFontScale1() = assertGauge(1.0f)

    @Test
    fun theGaugeIsOneSlimBarBetweenTwoSmallerSingleLineWordsAtFontScale13() = assertGauge(1.3f)

    @Test
    fun theBarIsTheAccentOverTheMutedTrackWithNoColumnsNoEmberGradientAndNoGlow() {
        val pad = File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirTrackpadPad.kt").readText()
        val gauge = pad.substringAfter("private fun PadSpeedGauge(").substringBefore("/** How much of the bar the level fills")
        assertTrue("the level is the theme's accent", gauge.contains("val fillColor = palette.accent"))
        assertTrue("the track is the seek bar's muted line", gauge.contains("palette.textMuted.copy(alpha = PadSpeedTrackAlpha)"))
        assertTrue("one rounded bar", gauge.contains("CornerRadius(size.height / 2f)"))
        assertFalse("no columns", gauge.contains("for (n in 1..") || gauge.contains("PadBarColumnWidth") || gauge.contains("PadBarHeights"))
        assertFalse("no ember gradient", gauge.contains("verticalGradient") || gauge.contains("horizontalGradient") || gauge.contains("accentBright"))
        assertFalse("no glow", gauge.contains("tileGlow") || gauge.contains("PadBarGlow"))
        assertEquals("the track is the seek bar's 0.28", "0.28f", Regex("""PadSpeedTrackAlpha = ([0-9.]+f)""").find(pad)!!.groupValues[1])
    }

    private fun near(a: Int, b: Int, tolerance: Int = 8): Boolean =
        intArrayOf(0, 8, 16).all { shift -> abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)) <= tolerance }

    /** The pad header at font scale 1.0 on a 2x screen, drawn the way this repo rasterises (the view hierarchy into a bitmap), written to build/test-captures for visual review. */
    @Test
    @Config(qualifiers = "w600dp-h500dp-xhdpi")
    fun thePadHeaderAtFontScale1IsDrawnWithTheAccentFillOverTheMutedTrack() {
        showPad(1.0f)
        // The pad's entrance has played out before it is drawn.
        compose.mainClock.advanceTimeBy(2_000L)
        compose.waitForIdle()
        val root = hostView!!.rootView
        val full = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(full))

        val gauge = compose.onNodeWithTag(MimirTrackpadSpeedGaugeTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag(MimirTrackpadSpeedBarTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        // The pad's own view may sit below something in the window: bounds are in its root, the bitmap is the window's.
        val origin = IntArray(2).also { hostView!!.getLocationInWindow(it) }
        val y = origin[1] + ((bar.top + bar.bottom) / 2f).toInt()
        val accent = MimirThemePalette.Default.accent.toArgb()
        val filled = full.getPixel(origin[0] + (bar.left + bar.width * 0.3f).toInt(), y)
        val empty = full.getPixel(origin[0] + (bar.left + bar.width * 0.85f).toInt(), y)
        assertTrue("three fifths of the bar is the accent: ${Integer.toHexString(filled)} vs ${Integer.toHexString(accent)}", near(filled, accent))
        assertFalse("and the rest is the muted track, not the accent: ${Integer.toHexString(empty)}", near(empty, accent, 24))

        val top = (origin[1] + gauge.top - 6f * density).toInt().coerceAtLeast(0)
        val height = (gauge.height + 12f * density).toInt().coerceAtMost(full.height - top)
        val band = Bitmap.createBitmap(full, 0, top, full.width, height)
        var differing = 0
        val ground = band.getPixel(2, band.height - 2)
        for (py in 0 until band.height step 2) for (px in 0 until band.width step 2) if (band.getPixel(px, py) != ground) differing += 1
        assertTrue("the header drew something: $differing sampled pixels differ from the ground", differing > 300)

        val outDir = File("build/test-captures").takeIf { it.isDirectory } ?: File(System.getProperty("java.io.tmpdir"))
        val out = File(outDir, "pad-speed-1.0.png")
        out.outputStream().use { band.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("PAD-SPEED wrote ${out.path} (${out.length()} bytes)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }
}

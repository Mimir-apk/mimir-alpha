package com.gruning.mimir.rewrite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.WindowManager
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerColor
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerGeometry
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerShape
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerSize
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pointer as it is drawn, and where its ink lands against the click it places (Small 18 dp and Large 34 dp,
 * both shapes). The dot's ink is centered on the click point once the window is placed by [mimirTrackpadPointerOrigin]; the
 * arrow's tip is at the window's corner, one pixel down-right of the click, within the width of its dark edge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirTrackpadPointerDrawTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val density = 2.75f
    private val click = 640f to 360f

    private class Drawn(val windowPx: Int, val pixels: IntArray) {
        fun alpha(x: Int, y: Int) = Color.alpha(pixels[y * windowPx + x])
        fun at(x: Int, y: Int) = pixels[y * windowPx + x]

        /** Pixels the player would call ink, not the faintest anti-aliasing. */
        fun inked(x: Int, y: Int) = alpha(x, y) >= 96
    }

    private fun draw(
        shape: TrackpadPointerShape,
        size: TrackpadPointerSize,
        color: TrackpadPointerColor = TrackpadPointerColor.WHITE,
    ): Drawn {
        val windowPx = TrackpadPointerGeometry.windowPx(shape, size, density)
        val view = MimirPointerView(context)
        view.applyStyle(shape, windowPx, mimirTrackpadPointerFill(color, MimirThemeId.DEFAULT))
        val bitmap = Bitmap.createBitmap(windowPx, windowPx, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val pixels = IntArray(windowPx * windowPx)
        bitmap.getPixels(pixels, 0, windowPx, 0, 0, windowPx, windowPx)
        return Drawn(windowPx, pixels)
    }

    private fun luma(argb: Int) = 0.299 * Color.red(argb) + 0.587 * Color.green(argb) + 0.114 * Color.blue(argb)

    // ---- the dot ------------------------------------------------------------------------------------------------------

    @Test
    fun theDotsInkIsCenteredOnTheClickPointAtEighteenAndThirtyFourDp() {
        for (size in listOf(TrackpadPointerSize.SMALL, TrackpadPointerSize.LARGE)) {
            val drawn = draw(TrackpadPointerShape.DOT, size)
            val n = drawn.windowPx
            var weight = 0.0
            var sumX = 0.0
            var sumY = 0.0
            for (y in 0 until n) {
                for (x in 0 until n) {
                    val a = drawn.alpha(x, y).toDouble()
                    weight += a
                    sumX += a * (x + 0.5)
                    sumY += a * (y + 0.5)
                }
            }
            assertTrue("${size.dp} dp: something is drawn", weight > 0)
            // Centered in its window, to well under a pixel...
            assertEquals("${size.dp} dp: ink centroid x", n / 2.0, sumX / weight, 0.35)
            assertEquals("${size.dp} dp: ink centroid y", n / 2.0, sumY / weight, 0.35)
            // ...and the window is placed with its center on the click, so the ink is centered on the click.
            val (originX, originY) = mimirTrackpadPointerOrigin(TrackpadPointerShape.DOT, click.first, click.second, n, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            assertEquals("${size.dp} dp: ink centroid on screen x", click.first.toDouble(), originX + sumX / weight, 0.5)
            assertEquals("${size.dp} dp: ink centroid on screen y", click.second.toDouble(), originY + sumY / weight, 0.5)
        }
    }

    @Test
    fun theDotIsAWhiteDiscInADarkEdgeAndFillsItsWindow() {
        for (size in listOf(TrackpadPointerSize.SMALL, TrackpadPointerSize.LARGE)) {
            val drawn = draw(TrackpadPointerShape.DOT, size)
            val n = drawn.windowPx
            val mid = n / 2
            assertEquals("${size.dp} dp: the center is white", Color.WHITE, drawn.at(mid, mid))
            // Walking left from the center along the middle row: white, then the dark edge, then outside the window.
            val firstInked = (0 until n).first { drawn.inked(it, mid) }
            assertTrue("${size.dp} dp: the dot spans its window (starts at $firstInked)", firstInked <= 1)
            assertTrue("${size.dp} dp: the outermost ink is the dark edge, not the fill", luma(drawn.at(firstInked + 1, mid)) < 90)
            val outlinePx = TrackpadPointerGeometry.outlinePx(TrackpadPointerShape.DOT, n)
            assertTrue("${size.dp} dp: the edge is as wide as its rule says", luma(drawn.at(firstInked + ceil(outlinePx).toInt() + 2, mid)) > 200)
            // Nothing outside the circle: the corners of the window are clear.
            assertEquals(0, drawn.alpha(0, 0))
            assertEquals(0, drawn.alpha(n - 1, n - 1))
        }
    }

    @Test
    fun theDotIsSmallerThanTheArrowsWindowAtTheSameSize() {
        for (size in TrackpadPointerSize.entries) {
            assertTrue(
                TrackpadPointerGeometry.windowPx(TrackpadPointerShape.DOT, size, density) <
                    TrackpadPointerGeometry.windowPx(TrackpadPointerShape.SLIM_ARROW, size, density),
            )
        }
    }

    // ---- the slim arrow -----------------------------------------------------------------------------------------------

    @Test
    fun theArrowsTipIsAtTheWindowsCornerAtEighteenAndThirtyFourDp() {
        for (size in listOf(TrackpadPointerSize.SMALL, TrackpadPointerSize.LARGE)) {
            val drawn = draw(TrackpadPointerShape.SLIM_ARROW, size)
            val n = drawn.windowPx
            val edge = ceil(TrackpadPointerGeometry.outlinePx(TrackpadPointerShape.SLIM_ARROW, n)).toInt()
            val inked = (0 until n).flatMap { y -> (0 until n).filter { x -> drawn.inked(x, y) }.map { x -> x to y } }
            assertTrue("${size.dp} dp: something is drawn", inked.isNotEmpty())
            val minX = inked.minOf { it.first }
            val minY = inked.minOf { it.second }
            assertTrue("${size.dp} dp: the ink starts at the window's left within the edge ($minX)", minX <= edge)
            assertTrue("${size.dp} dp: and at its top within the edge ($minY)", minY <= edge)
            // The tip pixel: the ink nearest the corner.
            val tip = inked.minBy { hypot(it.first.toDouble(), it.second.toDouble()) }
            assertTrue("${size.dp} dp: the tip pixel $tip is within the edge of the corner", tip.first <= edge && tip.second <= edge)
            // The window is one pixel down-right of the click, so the tip pixel is a few pixels from the click and never behind it.
            val (originX, originY) = mimirTrackpadPointerOrigin(TrackpadPointerShape.SLIM_ARROW, click.first, click.second, n, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            val tipX = originX + tip.first
            val tipY = originY + tip.second
            assertTrue("${size.dp} dp: the tip is down-right of the click", tipX > click.first && tipY > click.second)
            assertTrue("${size.dp} dp: and within ${2 * edge + 2} px of it", tipX - click.first <= 2 * edge + 2 && tipY - click.second <= 2 * edge + 2)
        }
    }

    @Test
    fun theArrowIsThinAndWhiteInsideADarkEdge() {
        for (size in listOf(TrackpadPointerSize.SMALL, TrackpadPointerSize.LARGE)) {
            val drawn = draw(TrackpadPointerShape.SLIM_ARROW, size)
            val n = drawn.windowPx
            val edge = TrackpadPointerGeometry.outlinePx(TrackpadPointerShape.SLIM_ARROW, n)
            val inner = n - 2f * edge
            // Inside the head, a third of the way down and a little in from the left edge: the fill.
            val head = drawn.at((edge + 0.10f * inner).toInt(), (edge + 0.35f * inner).toInt())
            assertEquals("${size.dp} dp: the head is white", Color.WHITE, head)
            // The window's far corner is clear: the arrow is a strip, not a square.
            assertEquals(0, drawn.alpha(n - 1, n - 1))
            val width = (0 until n).count { x -> (0 until n).any { y -> drawn.inked(x, y) } }
            assertTrue("${size.dp} dp: the arrow is under six tenths as wide as it is tall ($width of $n)", width <= 0.6f * n)
            // The dark edge is there: walking left to right along a row inside the head, the first ink is dark.
            val row = (edge + 0.35f * inner).toInt()
            val first = (0 until n).first { drawn.inked(it, row) }
            assertTrue("${size.dp} dp: the arrow's edge is dark", luma(drawn.at(first, row)) < 90)
        }
    }

    // ---- the fills ----------------------------------------------------------------------------------------------------

    @Test
    fun accentAndThemeMetalFillTheBodyWithTheThemesOwnColors() {
        val palette = MimirThemePalette.forId(MimirThemeId.DEFAULT)
        for (shape in TrackpadPointerShape.entries) {
            val accent = draw(shape, TrackpadPointerSize.LARGE, TrackpadPointerColor.ACCENT)
            val mid = accent.windowPx / 2
            val probe = if (shape == TrackpadPointerShape.DOT) mid to mid else {
                val edge = TrackpadPointerGeometry.outlinePx(shape, accent.windowPx)
                val inner = accent.windowPx - 2f * edge
                (edge + 0.10f * inner).toInt() to (edge + 0.35f * inner).toInt()
            }
            assertEquals("$shape accent", palette.accentBright.toArgb(), accent.at(probe.first, probe.second))
            val metal = draw(shape, TrackpadPointerSize.LARGE, TrackpadPointerColor.THEME_METAL)
            val ramp = listOf(palette.iconGradLight, palette.iconGradHot, palette.iconGradMid).map { it.toArgb() }
            val got = metal.at(probe.first, probe.second)
            val nearest = ramp.minOf { stop -> abs(Color.red(stop) - Color.red(got)) + abs(Color.green(stop) - Color.green(got)) + abs(Color.blue(stop) - Color.blue(got)) }
            assertTrue("$shape metal is on the coin's ramp (off by $nearest)", nearest <= 60)
        }
    }
}

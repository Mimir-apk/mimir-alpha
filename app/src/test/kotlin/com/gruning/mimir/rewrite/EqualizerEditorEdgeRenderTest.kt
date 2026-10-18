package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.audio.EqualizerSettings
import com.gruning.mimir.rewrite.core.audio.OutputFormat
import com.gruning.mimir.rewrite.core.audio.SystemEqStatus
import com.gruning.mimir.rewrite.core.audio.SystemEqualizerState
import com.gruning.mimir.rewrite.core.audio.TrackFormat
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sweep 10-09 (F8), the equalizer editor on Main (832.8 x 468.3 dp, 369 dpi) with the Equalizer on and its switch focused, at font 1.0 and 1.3:
 *  - at 1.0x the Bass slider, cut by the controls' window under the sheet, showed only the tip of its thumb at the bottom edge, a stray mark;
 *  - at 1.3x the focused row settled on the centre and left the curve half under the header, its 0 dB line directly under the "OUTPUT" line.
 * The window now dissolves what scrolls under its edges, and a focus settlement never leaves the curve cut: the curve is in view whole or scrolled past whole.
 * PNGs land in `build/test-captures/sweep28b/`; the pixels are the JVM's, a handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w833dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EqualizerEditorEdgeRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val viewportWidthDp = 832.8f
    private val viewportHeightDp = 468.3f
    private val rootDensity = 369f / 160f

    private val format = TrackFormat(
        codec = "MP3", lossless = false, bitDepth = null, sampleRateHz = 8_000, bitrateKbps = 8,
        output = OutputFormat(bitDepth = 16, sampleRateHz = 8_000),
    )

    private fun mount(fontScale: Float, focused: EqualizerEditorControl) {
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(rootDensity, fontScale)) {
                MimirTheme {
                    Box(Modifier.requiredSize(viewportWidthDp.dp, viewportHeightDp.dp)) {
                        EqualizerEditorOverlay(
                            settings = EqualizerSettings(enabled = true),
                            // Mimir's own track's two data lines, as the header carries them under its entry.
                            infoLines = equalizerHeaderLines(format).map { EqualizerInfoLine(it) },
                            focused = focused,
                            authority = null,
                            onToggle = {},
                            onPresetStep = {},
                            onGainRequested = { _, _ -> },
                            onRequestFocus = {},
                            onDismiss = {},
                            systemEq = SystemEqualizerState(wanted = true, status = SystemEqStatus.ON),
                        )
                    }
                }
            }
        }
        settle()
    }

    private fun settle(ms: Long = 1_500L) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun bitmap(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun save(bitmap: Bitmap, name: String) {
        val dir = File("build/test-captures/sweep28b").apply { if (!isDirectory) mkdirs() }
        val out = File(dir, name)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("EQEDGE wrote ${out.absolutePath}")
        assertTrue(out.isFile && out.length() > 1_000L)
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    /** The brightest channel (0..255) of any pixel in the last [dp] of the controls' window, across the middle [widthDp]: what a lone thumb tip or half a word at the edge would show. */
    private fun edgeBrightness(shot: Bitmap, viewportBottomDp: Float, dp: Float, widthDp: Float): Int {
        val bottomPx = (viewportBottomDp * rootDensity).toInt()
        val topPx = ((viewportBottomDp - dp) * rootDensity).toInt()
        val centre = shot.width / 2
        val half = (widthDp * rootDensity / 2).toInt()
        var best = 0
        for (y in topPx until bottomPx) for (x in centre - half until centre + half) {
            val c = shot.getPixel(x, y)
            best = maxOf(best, (c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        }
        return best
    }

    private fun report(fontScale: Float, name: String): Triple<Bitmap, Bounds, Bounds> {
        val viewport = bounds(EqualizerEditorViewportTestTag)
        val curve = bounds(EqualizerEditorCurveTestTag)
        println("EQEDGE font $fontScale: viewport ${viewport.top.value}..${viewport.bottom.value} dp, curve ${curve.top.value}..${curve.bottom.value} dp")
        val shot = bitmap()
        save(shot, name)
        return Triple(shot, Bounds(viewport.top.value, viewport.bottom.value), Bounds(curve.top.value, curve.bottom.value))
    }

    private data class Bounds(val top: Float, val bottom: Float)

    @Test
    fun `font 1 with the switch focused - no lone thumb tip at the window's bottom edge`() {
        mount(1.0f, EqualizerEditorControl.TOGGLE)
        val (shot, viewport, curve) = report(1.0f, "f8-eq-1.0.png")
        // The curve stands whole at the top, as it did.
        assertTrue("the curve is whole at 1x (${curve.top} vs the window's ${viewport.top})", curve.top >= viewport.top - 0.5f)
        // The Bass thumb's tip was a gold mark of about 190/255 in the last 6 dp; the fade carries it to the card's own dark (a trace of 35 was left by a first, linear mask).
        // Which control the window cuts depends on the sheet's height (a header or a row more or less moves it), so the pin is the mask's own arithmetic: the last fifth of the
        // fade (3.2 dp) is clear, and at the 6 dp line the mask lets through 35%, so no pixel there can be brighter than 0.35 x 255 = 89 however bright the control is.
        val brightness = edgeBrightness(shot, viewport.bottom, 6f, 60f)
        val lastBit = edgeBrightness(shot, viewport.bottom, 3f, 60f)
        println("EQEDGE font 1.0: brightest pixel in the window's last 6 dp, middle 60 dp: $brightness; in the last 3 dp: $lastBit (the card behind is about 20)")
        assertTrue("no lone mark, not even a faint speck, in the clear end of the fade (brightest $lastBit; the card behind is about 20)", lastBit <= 30)
        assertTrue("the fade is on: nothing in the last 6 dp is brighter than the mask lets through (brightest $brightness)", brightness <= 90)
    }

    @Test
    fun `font 1_3 with the switch focused - the curve is whole under the header, not cut onto the OUTPUT line`() {
        mount(1.3f, EqualizerEditorControl.TOGGLE)
        val (shot, viewport, curve) = report(1.3f, "f8-eq-1.3.png")
        assertTrue("the curve is whole at 1.3x (its top ${curve.top} is not above the window's ${viewport.top})", curve.top >= viewport.top - 0.5f)
        assertTrue("the curve is whole (its bottom ${curve.bottom} is inside the window)", curve.bottom <= viewport.bottom)
        val brightness = edgeBrightness(shot, viewport.bottom, 6f, 120f)
        println("EQEDGE font 1.3: brightest pixel in the window's last 6 dp, middle 120 dp: $brightness")
    }

    @Test
    fun `a settlement inside the curve's block goes to the top when the row still stands whole there, else past the block`() {
        val row = UtilityRailItemGeometry(topPx = 300, heightPx = 100)
        // A target at the top, or past the block, is as asked.
        assertEquals(0, equalizerSettleOffCurve(0, 200, row, 700, 32, 900))
        assertEquals(250, equalizerSettleOffCurve(250, 200, row, 700, 32, 900))
        // Inside the block: the row (300..400 + ring 32 = 432) stands whole at the top of a 700 px window, so the settlement is the top.
        assertEquals(0, equalizerSettleOffCurve(120, 200, row, 700, 32, 900))
        // The row would fall out of a 420 px window at the top, so the settlement goes past the block.
        assertEquals(200, equalizerSettleOffCurve(120, 200, row, 420, 32, 900))
        // Never beyond the scroll's own end.
        assertEquals(150, equalizerSettleOffCurve(120, 200, row, 420, 32, 150))
    }
}

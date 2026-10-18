package com.gruning.mimir.rewrite

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.gruning.mimir.rewrite.core.ui.MimirMotion
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
 * Utility opens on the SWAP tier, like Settings: one fade on
 * [MimirMotion.OverlayMs]. It used to arrive in two: the scrim darkened on the 180 ms glass ramp, then the panel brightened on the
 * page clock (a 130 ms lead and 540 ms), so on a dark scene the region under the panel dimmed and then rose (device: luminance 19.9
 * then 27.7, an open of 337-484 ms) and the panel was still arriving a third of a second after the scrim had finished.
 *
 * Frame by frame on the paused clock, 16 ms a step, over a dark field (the case that showed the dip: the panel is brighter than the
 * scrimmed scene). The region under the panel and a corner outside it each move one way and are settled when the overlay clock has
 * run. The class runs as API 30, the level at which the platform cannot blur (a software canvas cannot draw the RenderNode layers a
 * blur records); the blur and the host's pixel fade are the sweep's, on the emulator at animator scale 5.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class, qualifiers = "w832dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UtilityOpenSwapTierFrameTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.fillMaxSize().background(Color(0.05f, 0.05f, 0.05f))) {
                    MimirBrightnessOverlay(
                        state = calmShineTestBrightnessState(),
                        focusedControl = null,
                        onSetFraction = { _, _ -> },
                        onSetThemeAwareJoystickLight = {},
                        onRequestWriteAccess = {},
                        onRequestPrivilegedAccess = {},
                        filesRouteAvailable = true,
                        surface = UtilitySurface.UTILITY,
                        onBack = {},
                        onClose = {},
                        controllerHintsAvailable = false,
                    )
                }
            }
        }
    }

    private class Frame(val atMs: Long, val centre: Float, val corner: Float)

    private fun brightness(frame: Bitmap, x: Int, y: Int): Float {
        var sum = 0f
        var n = 0
        for (dx in -3..3) for (dy in -3..3) {
            val pixel = frame.getPixel((x + dx).coerceIn(0, frame.width - 1), (y + dy).coerceIn(0, frame.height - 1))
            sum += ((pixel shr 16 and 0xFF) + (pixel shr 8 and 0xFF) + (pixel and 0xFF)) / (3f * 255f)
            n++
        }
        return sum / n
    }

    /** One frame per 16 ms from the open to [untilMs]: the region under the panel and a corner outside it. */
    private fun record(untilMs: Long): List<Frame> {
        mount()
        val frames = mutableListOf<Frame>()
        var at = 0L
        while (at <= untilMs) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(16L)
            Snapshot.sendApplyNotifications()
            compose.waitForIdle()
            at += 16L
            val decor = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            frames += Frame(at, brightness(bitmap, decor.width / 2, decor.height / 2), brightness(bitmap, 6, 6))
        }
        return frames
    }

    /** True when [values] never turn back on a move of more than [tolerance]: one direction from the first frame to the last. */
    private fun movesOneWay(values: List<Float>, tolerance: Float): Boolean {
        var up = false
        var down = false
        values.zipWithNext().forEach { (a, b) ->
            if (b - a > tolerance) up = true
            if (a - b > tolerance) down = true
        }
        return !(up && down)
    }

    @Test
    fun `Utility opens in one fade on the overlay clock, one way, with no dip`() {
        val frames = record(untilMs = 480L)
        val centre = frames.map { it.centre }
        val corner = frames.map { it.corner }
        val summary = frames.joinToString { "${it.atMs}:${"%.3f".format(it.centre)}/${"%.3f".format(it.corner)}" }

        assertTrue("the region under the panel moves one way, no dim then rise: $summary", movesOneWay(centre, 0.004f))
        assertTrue("the scrim beside it moves one way too: $summary", movesOneWay(corner, 0.004f))

        val settleBy = frames.first { it.atMs >= MimirMotion.OverlayMs + 48L }
        val last = frames.last()
        assertEquals(
            "the panel is at rest once the overlay clock has run (+3 frames), not still arriving: $summary",
            last.centre,
            settleBy.centre,
            0.01f,
        )
        assertEquals("and so is the scrim: $summary", last.corner, settleBy.corner, 0.01f)
        assertTrue("it did arrive: $summary", kotlin.math.abs(last.centre - frames.first().centre) > 0.02f)
    }
}

package com.gruning.mimir.rewrite.feature.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Audit 2026-10-15, F5 (combined43 sweep, `r6-music-sub-s10`, `r6-forget-s10`): a Settings sub-page's forward swap dipped through dark (the old page out, then the new one in, 310 ms) and its back swap
 * dissolved the page left over the page returned to for three frames of two pages' text printed over each other, 160 ms: two behaviours for one gesture and its reverse.
 *
 * [SettingsSubPageSwap] is now ONE swap both ways, on the clock the Library's Carousel/List swap took for the same complaint (RC-10, `libraryViewCrossfadeSpec`): one [com.gruning.mimir.rewrite.core.ui.MimirMotion.OverlayMs]
 * on the house ease, no lead, the two pages complementary at every frame, each rising or sinking [com.gruning.mimir.rewrite.core.ui.MimirMotion.SwapTravelDp].
 *
 * Frame by frame on a paused clock, off the composed output: the two pages are a solid red and a solid blue over black, so one pixel of the pane tells how much of each is showing (red is how much of the
 * red page reaches the eye, blue the blue one), whichever is on top. Coverage is the two together (1 is a pane full of page, the lower the emptier); the overprint is a frame on which both are
 * above the readable 15% ([com.gruning.mimir.rewrite.core.ui.MimirPageMotion.ShownAlpha]). Two tiers of translucent page cannot have both a pane that is never emptier and no frame with both
 * showing; what the swap holds is a floor under the first and a ceiling over the second, the same in both directions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w640dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsSubPageSwapFrameTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density = 2f
    private var page by mutableStateOf(0)

    private val red = Color(255, 0, 0)
    private val blue = Color(0, 0, 255)

    private fun mount() {
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            Box(Modifier.requiredSize(200.dp, 200.dp).background(Color.Black)) {
                SettingsSubPageSwap(target = page, returning = page == 0, label = "frame-test") { shown ->
                    Box(Modifier.fillMaxWidth().height(120.dp).background(if (shown == 0) red else blue))
                }
            }
        }
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    private fun bitmap(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private class Frame(val ms: Int, val red: Float, val blue: Float, val redEdgeDp: Float, val blueEdgeDp: Float) {
        val coverage: Float get() = red + blue
        val bothShown: Boolean get() = red > ShownAlpha && blue > ShownAlpha
    }

    /** The lowest row, in dp, whose [channel] (shift 16 red, 0 blue) is above a faint threshold: the bottom edge of that page, which moves with its travel. */
    private fun lowestRowDp(bmp: Bitmap, x: Int, shift: Int): Float {
        var row = -1
        // The pane only: the window's own bars below it are not the page's.
        for (y in 0 until minOf(bmp.height, (200 * density).toInt())) if (((bmp.getPixel(x, y) shr shift) and 0xFF) > 12) row = y
        return if (row < 0) Float.NaN else (row + 1) / density
    }

    /** One swap on a paused clock, 16 ms a frame for [frames] frames: what each page shows at the middle of the pane, and where each page's bottom edge stands. */
    private fun run(change: () -> Unit, frames: Int = 30): List<Frame> {
        compose.mainClock.autoAdvance = false
        Snapshot.withMutableSnapshot { change() }
        Snapshot.sendApplyNotifications()
        val out = ArrayList<Frame>()
        for (frame in 1..frames) {
            compose.mainClock.advanceTimeBy(16)
            val bmp = bitmap()
            val x = (100 * density).toInt()
            val p = bmp.getPixel(x, (60 * density).toInt())
            out += Frame(
                ms = frame * 16,
                red = ((p shr 16) and 0xFF) / 255f,
                blue = (p and 0xFF) / 255f,
                redEdgeDp = lowestRowDp(bmp, x, 16),
                blueEdgeDp = lowestRowDp(bmp, x, 0),
            )
        }
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        return out
    }

    private fun report(name: String, frames: List<Frame>) {
        println(
            "SWAPFRAME $name coverage min ${frames.minOf { it.coverage }}; both shown on ${frames.count { it.bothShown }} frames; " +
                "red " + frames.joinToString(" ") { "%.2f".format(it.red) } + " | blue " + frames.joinToString(" ") { "%.2f".format(it.blue) },
        )
    }

    /** The first frame from which the page that arrives is whole (above 98%) and stays so. */
    private fun settledAt(frames: List<Frame>, arriving: (Frame) -> Float): Int {
        val lastShort = frames.indexOfLast { arriving(it) < 0.98f }
        return if (lastShort < 0) 0 else frames[lastShort].ms + 16
    }

    @Test
    fun `a sub-page swap is one symmetric swap, never a dip and never a held double exposure`() {
        mount()
        val forward = run({ page = 1 })
        report("forward red->blue", forward)
        val back = run({ page = 0 })
        report("back blue->red", back)

        assertTrue("the harness reads the pages: the red page was there before, the blue after", forward.first().red > 0f && forward.last().blue > 0.98f)

        listOf("forward" to forward, "back" to back).forEach { (name, frames) ->
            // The dip (F5, forward): old out, then new in left the pane near-empty for about six frames. The cross-fade's own floor is 0.75, at the midpoint.
            assertTrue("$name: the pane is never emptier than the cross-fade's own overlap (min ${frames.minOf { it.coverage }})", frames.minOf { it.coverage } >= 0.70f)
            // The held overprint (F5, back): the page left over the page returned to showed both above 15% for three-plus frames.
            val both = frames.count { it.bothShown }
            assertTrue("$name: both pages are readable together on at most three frames (was $both)", both <= 3)
        }

        val forwardBoth = forward.count { it.bothShown }
        val backBoth = back.count { it.bothShown }
        assertTrue("forward and back overprint within a frame of each other ($forwardBoth vs $backBoth)", abs(forwardBoth - backBoth) <= 1)
        val forwardFloor = forward.minOf { it.coverage }
        val backFloor = back.minOf { it.coverage }
        assertTrue("and share one floor ($forwardFloor vs $backFloor)", abs(forwardFloor - backFloor) <= 0.06f)

        // One clock, both ways: the page arriving is whole by OverlayMs (180 ms) plus a frame, not by 310 ms (a 130 ms lead and a fade).
        val forwardDone = settledAt(forward) { it.blue }
        val backDone = settledAt(back) { it.red }
        println("SWAPFRAME settled: forward ${forwardDone} ms, back ${backDone} ms")
        assertTrue("forward is whole by the swap tier's one clock ($forwardDone ms)", forwardDone <= 180 + 32)
        assertTrue("back is whole by the same ($backDone ms)", backDone <= 180 + 32)
        assertTrue("and the two agree to a frame ($forwardDone vs $backDone)", abs(forwardDone - backDone) <= 16)
    }

    @Test
    fun `the page arriving travels the house swap distance, from below on the way in and from above on the way back`() {
        mount()
        val forward = run({ page = 1 })
        val back = run({ page = 0 })
        // Page A is 120 dp tall at the top of the pane: its bottom edge stands at 120 dp, and a travel of 8 dp moves it to 128 (rising from below) or 112 (coming down from above).
        val forwardArriving = forward.first { it.blue > 0.2f }.blueEdgeDp
        val backArriving = back.first { it.red > 0.2f }.redEdgeDp
        println("SWAPFRAME travel: forward arriving edge $forwardArriving dp, back arriving edge $backArriving dp")
        assertTrue("forward the page arriving comes up from below ($forwardArriving dp)", forwardArriving in 121f..128.5f)
        assertTrue("back the page arriving comes down from above ($backArriving dp)", backArriving in 111.5f..119f)
        assertEquals("settled forward at its place", 120f, forward.last().blueEdgeDp, 0.6f)
        assertEquals("settled back at its place", 120f, back.last().redEdgeDp, 0.6f)
    }

    private companion object {
        /** [com.gruning.mimir.rewrite.core.ui.MimirPageMotion.ShownAlpha]: the readable 15%. */
        const val ShownAlpha = 0.15f
    }
}

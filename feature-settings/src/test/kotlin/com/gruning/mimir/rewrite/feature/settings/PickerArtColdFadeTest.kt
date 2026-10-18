package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.ui.select.pickerThumbnailKey

import com.gruning.mimir.rewrite.core.ui.select.PickerArt
import com.gruning.mimir.rewrite.core.ui.select.rememberPickerArt
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cold picker art fades in from nothing with no placeholder: first-show cold loads fade on
 * FocusMs with the house curve, while warm paths stay instant. NATIVE pixels: the window drawn in software, the centre pixel read against
 * the same window before any art exists. Frame 0 of a cold load draws nothing; the decode lands, the art rises through blends, and is
 * whole within FocusMs of its first visible frame; a second show of the same file is whole on its first frame. The pixel values are
 * the JVM's; the handheld's are device-owed.
 *
 * Both of the Appearance pickers' painters are covered: the saved-background tile ([AppearanceThumbnailImage], also the Settings row's
 * photo) and the saved-mark coin ([MarkCoin], struck through an explicit layer whose paint alpha carries the fade).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PickerArtColdFadeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("coldfade", "dir").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun write(name: String, color: Int): String {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return file.absolutePath
    }

    private fun step(ms: Long) {
        if (ms > 0L) compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    /** The window's centre pixel, drawn in software. */
    private fun centre(): Int {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap.getPixel(view.width / 2, view.height / 2)
    }

    private fun distance(a: Int, b: Int): Int =
        abs(Color.alpha(a) - Color.alpha(b)) + abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    /** Steps 16 ms frames until the centre has settled (two equal frames beyond the ground), or the decode thread's five seconds are up. */
    private fun framesUntilSettled(ground: Int): List<Int> {
        val frames = mutableListOf<Int>()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            step(16L)
            frames += centre()
            val n = frames.size
            if (n >= 3 && frames[n - 1] == frames[n - 2] && frames[n - 2] == frames[n - 3] && distance(frames.last(), ground) > 0) return frames
            Thread.sleep(10)
        }
        return frames
    }

    /** What the proof asks of a cold fade, whatever painted it. [ground] is the window before the art; [whole] its full look. */
    private fun assertColdFade(ground: Int, frames: List<Int>, whole: Int?) {
        val total = distance(frames.last(), ground)
        assertTrue("the art arrived", total > 0)
        if (whole != null) assertEquals("whole at the end", whole, frames.last())
        val progress = frames.map { distance(it, ground).toFloat() / total }
        val first = progress.indexOfFirst { it > 0f }
        assertTrue("some frame wore the art: $progress", first >= 0)
        assertTrue("it starts from nothing, not at full strength: $progress", progress[first] < 0.6f)
        assertTrue("it never steps back: $progress", progress.drop(first).zipWithNext().all { (a, b) -> b >= a - 0.02f })
        assertTrue("through blends, not a cut: $progress", progress.count { it > 0.05f && it < 0.95f } >= 2)
        val wholeAt = progress.indexOfFirst { it >= 0.999f }
        assertTrue("and whole: $progress", wholeAt >= 0)
        assertTrue(
            "whole within FocusMs (${MimirMotion.FocusMs} ms) of the first visible frame: ${(wholeAt - first) * 16} ms, $progress",
            (wholeAt - first) * 16 <= MimirMotion.FocusMs + 16,
        )
    }

    @Test
    fun `a cold background tile draws nothing on frame 0 and fades in from nothing, whole within FocusMs`() {
        val red = Color.RED
        val path = write("tile", red)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme { Box(Modifier.fillMaxSize()) { AppearanceThumbnailImage(path, Modifier.fillMaxSize()) } }
        }
        step(0L)
        val ground = centre()
        assertTrue("frame 0 of a cold tile shows no art: ${Integer.toHexString(ground)}", distance(ground, red) > 0)

        // The decode is real file work on another thread: it may land within a frame or two, so frame 0 is the frame it is measured from.
        val frames = listOf(ground) + framesUntilSettled(ground)
        assertColdFade(ground, frames, whole = red)
    }

    @Test
    fun `a warm background tile is whole on its first frame and never fades`() {
        val red = Color.RED
        val path = write("warm", red)
        var show by mutableStateOf(false)
        var warmed: PickerArt? = null
        compose.setContent {
            if (show) {
                MimirTheme { Box(Modifier.fillMaxSize()) { AppearanceThumbnailImage(path, Modifier.fillMaxSize()) } }
            } else {
                warmed = rememberPickerArt(pickerThumbnailKey(path, AppearanceThumbnailWidthPx)) {
                    decodeSampledThumbnail(path, AppearanceThumbnailWidthPx)!!.asImageBitmap()
                }
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) { warmed?.bitmap != null }
        compose.mainClock.autoAdvance = false
        Snapshot.withMutableSnapshot { show = true }
        compose.mainClock.advanceTimeByFrame()
        step(0L)
        assertEquals("frame 0 wears the art, whole", red, centre())
        repeat(10) {
            step(16L)
            assertEquals("and every frame after it", red, centre())
        }
    }

    @Test
    fun `a cold saved mark fades in through its struck layer, and a kept one is whole at once`() {
        val white = write("mark", Color.WHITE)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MarkCoin(path = white, diameter = 120.dp, modifier = Modifier.size(120.dp)) }
            }
        }
        step(0L)
        val ground = centre()
        val frames = listOf(ground) + framesUntilSettled(ground)
        assertColdFade(ground, frames, whole = null)
    }
}

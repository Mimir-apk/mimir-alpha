package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.mimirGlassBackdropProgress
import com.gruning.mimir.rewrite.core.ui.mimirHold
import com.gruning.mimir.rewrite.core.ui.rememberMimirGlassProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Library's launch-card OPEN, drawn frame by frame: the sequence Home's card is held to (Thor video showed Home popping in at full alpha over the sharp shelf). The same scene, card and probe as `HomeLaunchCardOpenSequenceTest` (app), the same block between the markers; that test reads
 * this file and holds the two blocks equal, so the two doors cannot drift apart without one of the two failing.
 *
 * The Library's card host is [LibraryOverlayPixelFadeTransition] as `LibraryScreen` calls it for a game's detail (`initialArrivalFade = true`, no glass of its own here), over
 * the scene's glass wired as `rememberLibraryScreenScene` and the detail chrome do: up the moment there is a card, held for the overlay hold after it goes.
 *
 * Pixels are the JVM's; handheld pixels need device review.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w834dp-h520dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryCardOpenSequenceTest {

    /**
     * The outermost rule, applied before the compose rule launches anything. On the Linux CI runners these PixelCopy frame tests never
     * finish: run 38796079835 hung 60 min, and in run 38807575626 a class-level assumption skipped only the first test before the next
     * one was killed. They run on the Windows host gate, where they pass, and are skipped elsewhere.
     */
    @get:Rule(order = Int.MIN_VALUE)
    val windowsHostOnly = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"))
                base.evaluate()
            }
        }
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density369 = 369f / 160f
    private val widthDp = 1920f / density369
    private val heightDp = 1080f / density369
    private var target by mutableStateOf<String?>(null)
    private var glass: () -> Float = { 0f }

    private fun press(block: () -> Unit) {
        Snapshot.withMutableSnapshot(block)
        Snapshot.sendApplyNotifications()
    }

    private fun step(ms: Long = 16L) {
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
    }

    /** One drawn frame: the window as PixelCopy gives it, with an idled main looper (the paused clock never delivers a redraw). */
    private fun capture(): Bitmap {
        val view = compose.activity.window.decorView
        val frame = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        var copied = -1
        PixelCopy.request(compose.activity.window, frame, { copied = it }, Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()
        check(copied == PixelCopy.SUCCESS) { "PixelCopy result $copied" }
        return frame
    }

    private data class Painted(val alpha: Float, val glass: Float)

    /** The card is flat red over the scene's blue: its alpha is read from a flat pixel in the middle. */
    private fun paint(): Painted {
        val frame = capture()
        val p = frame.getPixel(frame.width / 2, frame.height / 2)
        return Painted(((p shr 16) and 0xFF) / 255f, glass())
    }

    private fun mount() {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = density369, fontScale = 1f),
                LocalMimirViewportProfile provides MimirViewportProfile(MimirViewportPosture.STANDARD, widthDp, heightDp),
            ) {
                MimirTheme {
                    Box(Modifier.requiredWidth(widthDp.dp).requiredHeight(heightDp.dp)) {
                        // The detail chrome (LibraryScreenFoundation): up with the card, held for the overlay hold after it goes.
                        var chrome by remember { mutableStateOf(false) }
                        LaunchedEffect(target) {
                            if (target != null) {
                                chrome = true
                            } else {
                                mimirHold(MimirMotion.OverlayDissolveCoverMs)
                                chrome = false
                            }
                        }
                        val sceneGlass = rememberMimirGlassProgress(visible = chrome)
                        glass = sceneGlass
                        Box(Modifier.fillMaxSize().mimirGlassBackdropProgress(sceneGlass)) {
                            Box(Modifier.fillMaxSize().background(Color(0xFF0000FF)))
                            Canvas(Modifier.fillMaxSize()) {
                                var x = 0f
                                var i = 0
                                while (x < size.width) {
                                    drawRect(if (i % 2 == 0) Color.Black else Color.White, Offset(x, 0f), Size(16f, size.height / 10f))
                                    x += 16f
                                    i += 1
                                }
                            }
                        }
                        LibraryOverlayPixelFadeTransition(targetState = target, initialArrivalFade = true) {
                            Box(Modifier.fillMaxSize().background(Color(0xFFFF0000)))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun frames(count: Int): List<Painted> = (0 until count).map {
        step()
        paint()
    }

    private fun alphas(list: List<Painted>) = list.joinToString(" ") { "%.2f".format(it.alpha) }

    @Test
    fun `the open, from the press, is the sequence both doors share and never paints the card beyond the glass`() {
        mount()
        val opens = (0 until 4).map {
            press { target = "xenogears" }
            val open = frames(OPEN_FRAMES)
            frames(20)
            press { target = null }
            frames(CLOSE_FRAMES + 40)
            open
        }
        opens.forEachIndexed { n, open ->
            open.forEachIndexed { i, f ->
                assertTrue("frame $i of open #$n paints the card (${f.alpha}) beyond the glass (${f.glass})\n${alphas(open)}", f.alpha <= f.glass + 0.02f)
            }
            assertEquals("open #$n, from the press: alpha per 16 ms frame", OPEN_ALPHAS, alphas(open))
        }
    }

    companion object {
        const val OPEN_FRAMES = 46
        const val CLOSE_FRAMES = 24

        // OPEN-SEQUENCE-BEGIN
        // The card's alpha on each 16 ms frame after the press that opens it (the lead is 130 ms, then the page clock).
        val OPEN_ALPHAS: String = "0.00 0.00 0.00 0.00 0.00 0.00 0.00 0.00 0.00 0.00 0.00 0.01 0.04 0.07 0.12 0.17 0.24 0.30 0.37 0.44 0.50 0.55 0.61 0.65 0.70 0.73 0.77 0.80 0.83 0.85 0.87 0.89 0.91 0.93 0.94 0.95 0.96 0.97 0.98 0.98 0.99 0.99 1.00 1.00 1.00 1.00"
        // OPEN-SEQUENCE-END
    }
}

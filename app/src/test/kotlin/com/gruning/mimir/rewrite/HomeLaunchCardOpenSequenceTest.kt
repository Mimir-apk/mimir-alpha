package com.gruning.mimir.rewrite

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.launch.LaunchController
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.mimirGlassBackdropProgress
import com.gruning.mimir.rewrite.core.ui.rememberMimirGlassProgress
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Home's launch card opens the way Library's does. Thor video showed card content appearing at full alpha
 * over a sharp shelf before blur arrived, duplicating text and titles during the transition.
 *
 * Cause, found by drawing it: [HomeLaunchCardLayers] records its card into one of two reusable GraphicsLayers by hand. A layer recorded that way holds the RenderNodes of the
 * children that have a layer of their own (the glass node did), not their pixels. That node stood for every card AND for the empty frames between, so the layer painted at alpha
 * 1 while no card was up (the host's "null" layer) referenced the node the next card is drawn into. On the next open that layer is the outgoing one, still at alpha 1: the new
 * card, whole, for two frames over the shelf the glass had not softened yet, then dissolving over [MimirMotion.OverlayMs], ahead of the card's own 130 ms lead and 540 ms arrival.
 * (The first open of a session was right: the layer's alpha started at 0.) The same shared node is why a CLOSING card dissolved to nothing: by the time its layer was drawn the node
 * was empty. A node of its own per card, composed only while there is a card, is what every other pixel-fade host here does and what the Library's does.
 *
 * Second cause, found by comparing the frames with the Library's: the glass and the under-sheet hold were scoped to a card by an inline `key(target, frame.epoch)` in the host's own
 * group, and a card change cancelled the arrival effect declared after them once more at the next recomposition (about 220 ms in) and ran it again from the alpha it had: a stall
 * of two frames and an ease-in from the middle, five frames behind the Library (the video's "slowly sharpens"). The `key`s now sit inside functions of their own.
 *
 * What these hold the REAL host ([HomeLaunchCardLayers], the one `MimirAppHost` mounts, with the real coordinator) to, drawn frame by frame under a paused clock over the scene's glass:
 * 1. **Nothing of the card before the glass.** On no frame of an open is the card painted beyond what the scene's glass has reached, and for the 130 ms lead it is not painted at all
 *    (every open of a session, not only the first); it never fades out and in again.
 * 2. **A close dissolves the card that was there.** Its own pixels, from where it stood, to nothing on [MimirMotion.OverlayMs].
 * 3. **A card handing to the next dissolves the first card, not the second.**
 * 4. **The card is never inside what the glass blurs**: the scene is the glass's, the card is its sibling; wherever the JVM draws the blur at all, the card's edges stay as sharp as
 *    they were drawn.
 * 5. **The same open as the Library's**, with no stall: the per-frame alpha of an open, from the press, is the block between the markers below, which `LibraryCardOpenSequenceTest`
 *    (feature-library) holds the Library's card host to as well.
 *
 * Pixels are the JVM's (a flat colour per card, columns for the sharpness probe); handheld pixels need device review.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w834dp-h520dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeLaunchCardOpenSequenceTest {

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
    private val xenogears = GameRow(gameId = GameId("ps1/xenogears"), title = "Xenogears", platformId = PlatformId.PS1, memberCount = 1, duplicateKind = null)
    private val chronoCross = GameRow(gameId = GameId("ps1/chrono-cross"), title = "Chrono Cross", platformId = PlatformId.PS1, memberCount = 1, duplicateKind = null)
    private val authority = DefaultFocusAuthority()
    private lateinit var card: HomeLaunchCardCoordinator
    private var glass: () -> Float = { 0f }

    /** The coordinator only holds its launch controller, and these paths never reach it; an instance without a constructor call is enough. */
    private fun untouchedLaunchController(): LaunchController {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        @Suppress("UNCHECKED_CAST")
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, LaunchController::class.java) as LaunchController
    }

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

    /** What one frame shows: how much of the card's colour is painted over the scene's blue (0 to 1), which card it is, the scene's glass, and each band's sharpness. */
    private data class Painted(val alpha: Float, val card: Char, val glass: Float, val cardEdge: Float, val sceneEdge: Float)

    /**
     * The scene is flat blue except a band of black and white columns along the top (what the glass blurs). A card is flat red (Xenogears) or green (Chrono Cross) over all of it
     * except a band of colour and black columns along the bottom (its text, as far as sharpness goes). Alpha is read from a flat pixel; each band's edge contrast is read across one
     * column boundary: the card's, as a fraction of what a sharp card of that alpha shows, and the scene's, as a fraction of black against white.
     */
    private fun paint(): Painted {
        val frame = capture()
        val w = frame.width
        val h = frame.height
        val p = frame.getPixel(w / 2, h / 2)
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val alpha = maxOf(r, g) / 255f
        val who = when {
            alpha < 0.005f -> '-'
            r > g -> 'x'
            else -> 'c'
        }
        // The scene is 1920 x 1080 px from the window's top-left corner: columns of 16 px from its left edge, its bands on the top and bottom tenths.
        val ox = 0
        val oy = 0
        val boundary = ox + 960
        val bottom = oy + 1080 - 54
        val a = frame.getPixel(boundary - 1, bottom)
        val b = frame.getPixel(boundary, bottom)
        val cardContrast = kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) + kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val cardEdge = if (alpha < 0.1f) 1f else (cardContrast / (255f * alpha)).coerceAtMost(1f)
        val s = frame.getPixel(boundary - 1, oy + 54)
        val t = frame.getPixel(boundary, oy + 54)
        // The card is flat over the scene, so what shows of the scene is its (1 - alpha); past half there is too little of it to read.
        val sceneEdge = if (alpha >= 0.5f) Float.NaN else kotlin.math.abs(((s shr 8) and 0xFF) - ((t shr 8) and 0xFF)) / (255f * (1f - alpha))
        return Painted(alpha, who, glass(), cardEdge, sceneEdge)
    }

    private fun mount() {
        compose.setContent {
            val scope = rememberCoroutineScope()
            card = remember { HomeLaunchCardCoordinator(authority, untouchedLaunchController(), scope) }
            CompositionLocalProvider(
                LocalDensity provides Density(density = density369, fontScale = 1f),
                LocalMimirViewportProfile provides MimirViewportProfile(MimirViewportPosture.STANDARD, widthDp, heightDp),
            ) {
                MimirTheme {
                    Box(Modifier.requiredWidth(widthDp.dp).requiredHeight(heightDp.dp)) {
                        // The scene, under the glass the app gives it while the card is the top overlay (MimirAppHost: `rememberMimirGlassProgress(visible = mimirOverlayUsesGlass(glassChrome))`
                        // on a Box that holds only the destination, with the overlay host its sibling).
                        val sceneGlass = rememberMimirGlassProgress(visible = card.session != null && !card.closing)
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
                        HomeLaunchCardLayers(card = card, visible = true) { session ->
                            val color = if (session.game?.gameId == xenogears.gameId) Color(0xFFFF0000) else Color(0xFF00FF00)
                            Box(Modifier.fillMaxSize().background(color))
                            Canvas(Modifier.fillMaxSize()) {
                                var x = 0f
                                var i = 0
                                while (x < size.width) {
                                    drawRect(if (i % 2 == 0) color else Color.Black, Offset(x, size.height - size.height / 10f), Size(16f, size.height / 10f))
                                    x += 16f
                                    i += 1
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private val rowX = libraryHeroPickerRow(xenogears)
    private val rowC = libraryHeroPickerRow(chronoCross)

    private fun focusHome() {
        authority.register(rowX.focusKey, HomeShelfScope, FocusBounds(0f, 0f, 10f, 10f))
        authority.register(rowC.focusKey, HomeShelfScope, FocusBounds(20f, 0f, 30f, 10f))
        authority.pushScope(HomeShelfScope)
        authority.requestFocus(rowX.focusKey)
    }

    private fun openX() = press { check(card.open(rowX, xenogears, HomeShelfScope, rowX.focusKey)) { "the card did not open" } }

    private fun frames(count: Int): List<Painted> = (0 until count).map {
        step()
        paint()
    }

    private fun alphas(list: List<Painted>) = list.joinToString(" ") { "%.2f".format(it.alpha) }

    /** Four opens and closes of one game, matching the reported video: the frames after each press. */
    private fun cycles(): List<Pair<List<Painted>, List<Painted>>> {
        mount()
        focusHome()
        return (0 until 4).map {
            openX()
            val open = frames(OPEN_FRAMES)
            frames(20)
            press { card.close() }
            val close = frames(CLOSE_FRAMES)
            frames(40)
            press { authority.requestFocus(rowX.focusKey) }
            open to close
        }
    }

    @Test
    fun `every open paints nothing of the card for its lead and never beyond what the glass has reached, and never fades out and in`() {
        val cycles = cycles()
        cycles.forEachIndexed { n, (open, _) ->
            val why = "open #$n alphas: ${alphas(open)}\nglass: ${open.joinToString(" ") { "%.2f".format(it.glass) }}"
            val lead = (MimirMotion.OverlayDissolveCoverMs / 16) - 1
            open.take(lead).forEachIndexed { i, f -> assertTrue("frame $i of open #$n paints the card (${f.alpha}) inside its ${MimirMotion.OverlayDissolveCoverMs} ms lead\n$why", f.alpha <= 0.02f) }
            open.forEachIndexed { i, f ->
                assertTrue("frame $i of open #$n paints the card (${f.alpha}) beyond the glass (${f.glass})\n$why", f.alpha <= f.glass + 0.02f)
            }
            open.zipWithNext().forEachIndexed { i, (a, b) ->
                assertTrue("open #$n fades out between frames $i and ${i + 1} (${a.alpha} to ${b.alpha})\n$why", b.alpha >= a.alpha - 0.01f)
                // A recomposition must not restart the arrival: it stalls the card for frames and eases in again from the alpha it had (the Thor video's "slowly sharpens").
                if (a.alpha in 0.05f..0.9f) {
                    assertTrue("open #$n stalls between frames $i and ${i + 1} (${a.alpha} to ${b.alpha})\n$why", b.alpha >= a.alpha + 0.015f)
                }
            }
            assertTrue("open #$n arrives whole by the end\n$why", open.last().alpha >= 0.95f)
        }
    }

    @Test
    fun `a close dissolves the card that was there, from where it stood to nothing on the overlay clock`() {
        val cycles = cycles()
        cycles.forEachIndexed { n, (_, close) ->
            val why = "close #$n alphas: ${alphas(close)}"
            assertTrue("the card is still there on the first frame of close #$n (${close[0].alpha}): it dissolves, it is not cut\n$why", close[0].alpha >= 0.9f)
            assertEquals("and it is Xenogears's own pixels\n$why", 'x', close[0].card)
            close.zipWithNext().forEachIndexed { i, (a, b) -> assertTrue("close #$n rises between frames $i and ${i + 1}\n$why", b.alpha <= a.alpha + 0.01f) }
            val gone = MimirMotion.OverlayMs / 16 + 2
            assertTrue("it is gone by $gone frames\n$why", close[gone].alpha <= 0.02f)
        }
    }

    @Test
    fun `a card handing to the next dissolves the first card and not the second`() {
        mount()
        focusHome()
        openX()
        frames(50)
        // Chrono Cross's card opens from its own tile while Xenogears's is up (the swap the host calls a hand-over; the pad is put back on the shelf for the coordinator's origin check).
        press {
            authority.pushScope(HomeShelfScope)
            authority.requestFocus(rowC.focusKey)
            check(card.open(rowC, chronoCross, HomeShelfScope, rowC.focusKey)) { "the second card did not open" }
        }
        val out = frames(6)
        val why = out.joinToString(" ") { "%.2f%c".format(it.alpha, it.card) }
        assertEquals("the departing card is Xenogears's: $why", 'x', out[0].card)
        assertTrue("and it is still there, dissolving: $why", out[0].alpha >= 0.8f)
        out.forEach { assertFalse("no frame of the hand-over paints the arriving card at the departing one's alpha: $why", it.card == 'c' && it.alpha > 0.5f) }
    }

    @Test
    fun `the card is never inside what the glass blurs`() {
        val cycles = cycles()
        val sceneAtPeak = cycles.flatMap { it.first }.filter { it.glass >= 1f && !it.sceneEdge.isNaN() }.map { it.sceneEdge }
        cycles.forEachIndexed { n, (open, close) ->
            (open + close).forEachIndexed { i, f ->
                assertTrue("frame $i of cycle #$n: the card's edge has softened (${f.cardEdge} of a sharp card's) at alpha ${f.alpha}, glass ${f.glass}", f.cardEdge >= 0.9f)
            }
        }
        assertTrue("the probe is live: the JVM blurs the scene at the glass's peak (edge contrast ${sceneAtPeak.distinct()}), so a card inside it would show", sceneAtPeak.isNotEmpty() && sceneAtPeak.all { it < 0.5f })
    }

    @Test
    fun `the open is the sequence the Library's card host is held to`() {
        val cycles = cycles()
        val reopened = cycles.drop(1).map { alphas(it.first) }.distinct()
        assertEquals("every re-open is the same frames as every other", 1, reopened.size)
        assertEquals("and the first open of a session is the same frames as a re-open", alphas(cycles[0].first), reopened.single())
        assertEquals("the open, from the press: alpha per 16 ms frame", OPEN_ALPHAS, alphas(cycles[0].first))

        val here = sourceOf("app/src/test/kotlin/com/gruning/mimir/rewrite/HomeLaunchCardOpenSequenceTest.kt", "src/test/kotlin/com/gruning/mimir/rewrite/HomeLaunchCardOpenSequenceTest.kt")
        val library = sourceOf(
            "feature-library/src/test/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryCardOpenSequenceTest.kt",
            "../feature-library/src/test/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryCardOpenSequenceTest.kt",
        )
        assertTrue("the Library test carries the block", block(library).contains("val OPEN_ALPHAS"))
        assertEquals("one sequence for both doors", block(library), block(here))
    }

    private fun sourceOf(vararg paths: String): String =
        paths.map(::File).firstOrNull(File::isFile)?.readText() ?: error("${paths.first()} not found from ${File(".").absolutePath}")

    private fun block(text: String) = text.substringAfter("// OPEN-SEQUENCE-" + "BEGIN").substringBefore("// OPEN-SEQUENCE-" + "END").trim()

    companion object {
        const val OPEN_FRAMES = 46
        const val CLOSE_FRAMES = 24

        // OPEN-SEQUENCE-BEGIN
        // The card's alpha on each 16 ms frame after the press that opens it (the lead is 130 ms, then the page clock).
        val OPEN_ALPHAS: String = "0.00 0.00 0.00 0.00 0.00 0.00 0.00 0.00 0.00 0.00 0.00 0.01 0.04 0.07 0.12 0.17 0.24 0.30 0.37 0.44 0.50 0.55 0.61 0.65 0.70 0.73 0.77 0.80 0.83 0.85 0.87 0.89 0.91 0.93 0.94 0.95 0.96 0.97 0.98 0.98 0.99 0.99 1.00 1.00 1.00 1.00"
        // OPEN-SEQUENCE-END
    }
}

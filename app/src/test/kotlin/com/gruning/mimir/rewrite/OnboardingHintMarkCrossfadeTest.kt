package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirMannazPinWidth
import java.io.File
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * One ᛗ per book in the onboarding hint's MARK scene, replacing the first cut's 0.5 threshold: the marked book's embossed Mannaz and its pin cross over on the
 * demo's own progress. The pin arrives over the demo's second half (alpha `p`); the emboss is `1 - p` over the same half, so one mark stands at every instant: the emboss alone at
 * the half (the ring has landed, the pin has not), the pin alone at the end, and between them the two at complementary strength, never neither and never both at full.
 *
 * Read from the pixels the scene drew, in the box of the emboss's four tips and in the box of the pin's plate. Frames at the half, at three quarters and at the end are written to
 * `build/test-captures/sweepfix32/round2/` (the scene, three times its size).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingHintMarkCrossfadeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density = 2f
    private val sceneWidthDp = 300f
    private val sceneHeightDp = 140f
    private val demo = mutableFloatStateOf(0.5f)

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                MimirTheme {
                    val palette = LocalMimirPalette.current
                    Box(Modifier.testTag(SceneTag).width(sceneWidthDp.dp)) {
                        OnboardingHintSceneFrame(sceneHeight = sceneHeightDp.dp, palette = palette) {
                            OnboardingHintSceneView(
                                card = OnboardingHintCard(OnboardingHintScene.MARK, key = null, action = "Mark"),
                                palette = palette,
                                sceneHeight = sceneHeightDp.dp,
                                demo = { demo.floatValue },
                            )
                        }
                    }
                }
            }
        }
        settle()
    }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(200L)
        compose.waitForIdle()
    }

    private fun at(progress: Float): Bitmap {
        Snapshot.withMutableSnapshot { demo.floatValue = progress }
        settle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    /** The marked book (the third of four, each [BookFaceDp] tall, 3:4, a small gap apart, the row centred in the scene), in window px. */
    private fun markedBook(): Rect {
        val scene = compose.onNodeWithTag(SceneTag).fetchSemanticsNode().boundsInWindow
        val face = sceneHeightDp * 0.62f * 0.9f * density
        val width = face * 0.75f
        val gap = 8f * density
        val rowLeft = scene.center.x - (4 * width + 3 * gap) / 2f
        val left = rowLeft + 2 * (width + gap)
        return Rect(left, scene.center.y - face / 2f, left + width, scene.center.y + face / 2f)
    }

    private fun embossBox(book: Rect): Rect {
        val spine = maxOf(book.width * 0.075f, 3f * density)
        val foreEdge = maxOf(book.width * 0.055f, 2f * density)
        val leaf = book.width - foreEdge - spine
        val width = leaf * 0.18f
        val height = width * 1.06f
        val cx = book.left + spine + leaf / 2f
        val cy = book.top + book.height * 0.84f
        return Rect(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f)
    }

    /** The pin's plate at full size: 14 dp tall, top-trailing, an `xs` (4 dp) in from the book's corner. */
    private fun pinBox(book: Rect): Rect {
        val inset = 4f * density
        val height = 14f * density
        val width = mimirMannazPinWidth(14.dp).value * density
        return Rect(book.right - inset - width, book.top + inset, book.right - inset, book.top + inset + height)
    }

    private fun spread(bitmap: Bitmap, box: Rect): Float {
        val left = box.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = box.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = box.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = box.bottom.toInt().coerceIn(top + 1, bitmap.height)
        var sum = 0.0
        var squares = 0.0
        var count = 0
        for (y in top until bottom) for (x in left until right) {
            val pixel = bitmap.getPixel(x, y)
            val luma = 0.299 * ((pixel shr 16) and 0xFF) + 0.587 * ((pixel shr 8) and 0xFF) + 0.114 * (pixel and 0xFF)
            sum += luma
            squares += luma * luma
            count++
        }
        val mean = sum / count
        return sqrt((squares / count - mean * mean).coerceAtLeast(0.0)).toFloat()
    }

    private fun save(bitmap: Bitmap, name: String) {
        val scene = compose.onNodeWithTag(SceneTag).fetchSemanticsNode().boundsInWindow
        val crop = Bitmap.createBitmap(
            bitmap,
            scene.left.toInt().coerceAtLeast(0),
            scene.top.toInt().coerceAtLeast(0),
            scene.width.toInt().coerceAtMost(bitmap.width - scene.left.toInt()),
            scene.height.toInt().coerceAtMost(bitmap.height - scene.top.toInt()),
        )
        val big = Bitmap.createScaledBitmap(crop, crop.width * 3, crop.height * 3, false)
        val file = File(File("build/test-captures/sweepfix32/round2").apply { mkdirs() }, name)
        file.outputStream().use { big.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("HINTMARK wrote ${file.absolutePath} (${big.width}x${big.height} px)")
        assertTrue("the PNG was written: $file", file.isFile && file.length() > 1_000L)
    }

    @Test
    fun `one mark stands at every instant of the pin's arrival, the emboss giving way as the pin comes in`() {
        mount()
        val book = markedBook()
        val steps = (0..10).map { 0.5f + it * 0.05f }
        val rows = steps.map { progress ->
            val frame = at(progress)
            val emboss = spread(frame, embossBox(book))
            val pin = spread(frame, pinBox(book))
            println("HINTMARK demo=$progress emboss=$emboss pin=$pin")
            if (progress == 0.5f) save(frame, "hint-mark-0.50-emboss-alone.png")
            if (progress == 0.75f) save(frame, "hint-mark-0.75-mid-crossfade.png")
            if (progress == 1.0f) save(frame, "hint-mark-1.00-pin-alone.png")
            Triple(progress, emboss, pin)
        }
        val embossFull = rows.first().second
        val pinFull = rows.last().third
        assertTrue("the emboss is drawn at the half, before the pin (spread $embossFull)", embossFull > Drawn)
        assertTrue("the pin is not there yet at the half (spread ${rows.first().third})", rows.first().third < pinFull * 0.35f)
        assertTrue("the pin is drawn at the end (spread $pinFull)", pinFull > Drawn)
        assertTrue("the emboss is gone at the end (spread ${rows.last().second})", rows.last().second < Bare)
        rows.forEach { (progress, emboss, pin) ->
            val e = (emboss / embossFull).coerceIn(0f, 1.2f)
            val p = (pin / pinFull).coerceIn(0f, 1.2f)
            assertTrue("demo $progress: neither mark is there (emboss $e, pin $p of full)", e + p > 0.6f)
            assertTrue("demo $progress: both marks stand at full strength (emboss $e, pin $p of full)", !(e > 0.85f && p > 0.85f))
        }
    }

    private companion object {
        const val SceneTag = "hint-scene"

        /** Brightness spread above which a box holds a drawn mark, and below which it is bare leather. */
        const val Drawn = 15f
        const val Bare = 5f
    }
}

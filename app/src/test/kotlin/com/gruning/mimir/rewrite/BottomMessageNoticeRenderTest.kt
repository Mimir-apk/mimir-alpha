package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirTextLayoutObserver
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Observed on Thor: a tap on the ᛗ coin, with Android app switching not ready, raises "Android app switching couldn't open. Review access in Settings > Controls." on the
 * Bottom's message rail, and at font 1.3 its last line and its rune were cut off by the pill. The Bottom's host was the one rail that kept a fixed band, and at 0.42 of the Bottom's
 * 538 dp it was 226 dp wide (Main's is 349). It now grows to the card, as Main's does , on a rail as wide in dp as Main's, so the copy is read whole at font 1.0 and 1.3; a
 * copy that still outgrows the card's three lines (font 2.0) ends in an ellipsis rather than being dropped.
 *
 * The real host composable ([MimirDisplayMessageHost], registered for the Bottom's display) at the Bottom's 538 x 468 dp at 369 dpi, at font 1.0 and 1.3. The PNGs are written to
 * `build/test-captures/sweep28a/`. Pixel values are the JVM's; a handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w538dp-h520dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomMessageNoticeRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density369 = 369f / 160f
    private val observed = mutableMapOf<String, TextLayoutResult>()

    @Before
    fun setUp() {
        MimirDisplayMessages.resetForTest()
        setAnimatorScale(0f)
    }

    @After
    fun tearDown() {
        setAnimatorScale(1f)
        MimirDisplayMessages.resetForTest()
    }

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun advance(ms: Long) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms / 2L)
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms - ms / 2L)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun scene(): Bitmap {
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        val bounds = compose.onNodeWithTag(SceneTag).fetchSemanticsNode().boundsInWindow
        val left = bounds.left.toInt().coerceAtLeast(0)
        val top = bounds.top.toInt().coerceAtLeast(0)
        return Bitmap.createBitmap(full, left, top, minOf(bounds.width.toInt(), full.width - left), minOf(bounds.height.toInt(), full.height - top))
    }

    private fun savePng(bitmap: Bitmap, name: String) {
        val dir = File("build/test-captures/sweep28a").apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The Bottom's host as the Presentation mounts it: last in the window's root, at the bottom centre, the dock-clear inset up. */
    private fun mount(fontScale: Float) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density369, fontScale),
                LocalMimirTextLayoutObserver provides { spoken, layout -> observed[spoken] = layout },
            ) {
                MimirTheme {
                    val liveness = remember { MimirHostLiveness.of(MutableStateFlow(true)) }
                    Box(
                        Modifier.requiredSize(538.dp, 468.dp).background(LocalMimirPalette.current.surfaceBase).testTag(SceneTag),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        MimirDisplayMessageHost(
                            displayId = BottomDisplay,
                            liveness = liveness,
                            bottomInset = MimirBottomMessageRailBottomInset,
                            growsToFit = true,
                            railWidthFraction = MimirBottomMessageRailWidthFraction,
                        )
                    }
                }
            }
        }
        advance(300L)
    }

    private fun checkNotice(fontScale: Float, png: String, outgrowsItsLines: Boolean = false) {
        mount(fontScale)
        val text = systemRecentsFailureCopy()
        assertTrue(MimirDisplayMessages.post(BottomDisplay, text))
        advance(1_000L)
        val frame = scene()
        savePng(frame, png)

        val node = compose.onAllNodes(hasContentDescription("Warning. $text"), useUnmergedTree = true).fetchSemanticsNodes().single()
        val scene = compose.onNodeWithTag(SceneTag).fetchSemanticsNode().boundsInWindow
        val band = node.boundsInWindow
        val layout = observed[text] ?: throw AssertionError("the copy's layout was never heard: ${observed.keys}")
        println(
            "SWEEP28A-F2 font=$fontScale band=${(band.left - scene.left) / density369},${(band.top - scene.top) / density369} " +
                "${band.width / density369}x${band.height / density369}dp lines=${layout.lineCount} visualOverflow=${layout.hasVisualOverflow} " +
                "lineEnd=${layout.getLineEnd(layout.lineCount - 1)} of ${text.length}",
        )
        if (outgrowsItsLines) {
            // Last resort: wrapped at words as far as the card's lines go, and then the last line says it was cut.
            assertEquals("the card takes its three lines at font $fontScale", 3, layout.lineCount)
            assertTrue("the last line ends in an ellipsis at font $fontScale, it is not dropped silently", layout.isLineEllipsized(layout.lineCount - 1))
        } else {
            assertFalse("the copy was cut at font $fontScale (visual overflow): $text", layout.hasVisualOverflow)
            assertEquals("every character of the copy is on a line at font $fontScale", text.length, layout.getLineEnd(layout.lineCount - 1))
        }
        val copy = compose.onAllNodes(hasContentDescription(text), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        assertTrue("the copy stands inside the pill: $copy vs $band", copy.top >= band.top - 1f && copy.bottom <= band.bottom + 1f && copy.left >= band.left - 1f && copy.right <= band.right + 1f)
        val rune = compose.onAllNodes(hasText("ᚦ"), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInWindow
            ?: throw AssertionError("the rune is not drawn")
        assertTrue("the rune stands wholly inside the pill: $rune vs $band", rune.top >= band.top - 1f && rune.bottom <= band.bottom + 1f && rune.left >= band.left - 1f)
        val seated = (4f + 2f + 6f) * density369
        assertTrue("and seated inside the pill's curve: ${rune.left - band.left} px in, wanted $seated", rune.left - band.left >= seated - 1f)
        assertTrue("the pill stands inside the Bottom: $band vs $scene", band.left >= scene.left && band.right <= scene.right && band.bottom <= scene.bottom - MimirBottomMessageRailBottomInset.value * density369 + 1f)
    }

    @Test
    fun `the app-switching notice shows whole in a pill that grows to it at font 1_0`() = checkNotice(1.0f, "notice-f10.png")

    @Test
    fun `the app-switching notice shows whole in a pill that grows to it at font 1_3`() = checkNotice(1.3f, "notice-f13.png")

    @Test
    fun `a copy that outgrows the card's three lines at font 2_0 ends in an ellipsis and the pill still holds its rune`() =
        checkNotice(2.0f, "notice-f20.png", outgrowsItsLines = true)

    private companion object {
        const val SceneTag = "sweep28a-notice-scene"
        const val BottomDisplay = 4
    }
}

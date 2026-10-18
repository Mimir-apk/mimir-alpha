package com.gruning.mimir.rewrite

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * RC-33 / L-28 (audit 2026-10-14): the achievement card's ledger frame ("checking") was replaced by the rich page in ONE frame the moment
 * the lane answered, because the host chose between them with an `if`. [AchievementCardPageSwap] makes it one swap on the swap tier.
 *
 * Frame by frame on the paused clock, 16 ms a step, over a dark field: the centre of the screen goes from the ledger's grey to the rich
 * page's bright one. The class runs as API 30 (a software canvas draws the layers a fade records; no blur is involved). The harness is
 * proven against the OLD shape too: the same pages chosen by an `if` jump in a single frame, which is what this asserts the swap does not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class, qualifiers = "w832dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AchievementCardPageSwapTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var loaded by mutableStateOf<Int?>(null)

    private val ledgerGrey = 0.40f
    private val richBright = 0.90f

    @Composable
    private fun Ledger() = Box(Modifier.fillMaxSize().background(Color(ledgerGrey, ledgerGrey, ledgerGrey)))

    @Composable
    private fun Rich(@Suppress("UNUSED_PARAMETER") set: Int) = Box(Modifier.fillMaxSize().background(Color(richBright, richBright, richBright)))

    private fun mount(swap: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color(0.05f, 0.05f, 0.05f)), contentAlignment = Alignment.Center) {
                if (swap) {
                    AchievementCardPageSwap(loaded = loaded, reducedMotion = false, ledger = { Ledger() }, rich = { Rich(it) })
                } else {
                    // The shape the host had: one page or the other, chosen by an `if`.
                    val set = loaded
                    if (set != null) Rich(set) else Ledger()
                }
            }
        }
    }

    private fun centre(): Float {
        val decor = compose.activity.window.decorView
        val frame = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(frame))
        val pixel = frame.getPixel(decor.width / 2, decor.height / 2)
        return ((pixel shr 16 and 0xFF) + (pixel shr 8 and 0xFF) + (pixel and 0xFF)) / (3f * 255f)
    }

    /** The centre brightness once per 16 ms from the lane answering until [untilMs]. */
    private fun answerAndRecord(swap: Boolean, untilMs: Long): List<Float> {
        mount(swap)
        repeat(6) { compose.mainClock.advanceTimeBy(16L) }
        compose.waitForIdle()
        val before = centre()
        assertEquals("fixture: the ledger frame is up", ledgerGrey, before, 0.02f)
        Snapshot.withMutableSnapshot { loaded = 7 }
        Snapshot.sendApplyNotifications()
        val frames = mutableListOf(before)
        var at = 0L
        while (at < untilMs) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(16L)
            Snapshot.sendApplyNotifications()
            compose.waitForIdle()
            at += 16L
            frames += centre()
        }
        return frames
    }

    private fun largestStep(frames: List<Float>): Float = frames.zipWithNext().maxOf { (a, b) -> kotlin.math.abs(b - a) }

    @Test
    fun `the ledger frame hands over to the rich page across frames, not in one`() {
        val frames = answerAndRecord(swap = true, untilMs = 720L)
        val excursion = richBright - ledgerGrey
        val summary = frames.joinToString { "%.2f".format(it) }
        assertTrue("the page did arrive: $summary", frames.last() > richBright - 0.03f)
        assertTrue(
            "no single frame carries the change (step ${largestStep(frames)} of $excursion): $summary",
            largestStep(frames) < excursion * 0.5f,
        )
        assertTrue(
            "the departing page is not cut: the first frame after the answer is still mostly the ledger: $summary",
            frames[1] > ledgerGrey - 0.2f,
        )
    }

    @Test
    fun `the harness sees the old if as a one-frame cut`() {
        val frames = answerAndRecord(swap = false, untilMs = 96L)
        val excursion = richBright - ledgerGrey
        assertTrue(
            "the old shape jumps nearly the whole excursion in a single frame: ${frames.joinToString { "%.2f".format(it) }}",
            largestStep(frames) > excursion * 0.9f,
        )
    }

    @Test
    fun `the host swaps through the page composable and no longer picks a page with an if`() {
        val host = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
        ).first(File::isFile).readText()
        val overlay = host.substringAfter("private fun MimirAchievementCardOverlay(").substringBefore("internal fun <T : Any> AchievementCardPageSwap(")
        assertTrue(overlay.contains("AchievementCardPageSwap("))
        assertTrue(overlay.contains("loaded = achievements.cardProgress(),"))
        assertFalse("no if between the two pages", overlay.contains("if (loaded != null)"))
        val swap = host.substringAfter("internal fun <T : Any> AchievementCardPageSwap(").substringBefore("\n}\n")
        assertTrue("one keyed AnimatedContent", swap.contains("contentKey = { it != null },"))
        assertTrue("on the swap tier", swap.contains("transitionSpec = { mimirSwapTransform(reducedMotion) },"))
    }
}

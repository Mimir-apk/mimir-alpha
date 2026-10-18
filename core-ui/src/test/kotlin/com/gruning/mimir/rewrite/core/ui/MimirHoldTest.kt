package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.junit4.createComposeRule
import java.io.File
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A hold written as a coroutine `delay()` does not scale with the animator
 * setting, so under a slowed animator it ends while the fade it covers is still running, and under a stopped one it
 * still waits its whole time. [mimirHold] is a wait on the animation clock: it obeys the scale as a tween does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MimirHoldTest {

    @get:Rule
    val compose = createComposeRule()

    private var done = false

    private class Scale(override val scaleFactor: Float) : MotionDurationScale

    private fun host(ms: Int, scale: Float? = null) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LaunchedEffect(Unit) {
                if (scale == null) mimirHold(ms) else withContext(Scale(scale)) { mimirHold(ms) }
                done = true
            }
        }
    }

    /**
     * How long after the first frame the hold is done, on frames of the test clock; -1 if it is not by [limitMs].
     * The hold starts on the first frame it sees, so the clock is counted from there.
     */
    private fun doneAfter(limitMs: Long): Long {
        compose.mainClock.advanceTimeByFrame()
        if (done) return 0L
        val start = compose.mainClock.currentTime
        while (compose.mainClock.currentTime - start <= limitMs) {
            compose.mainClock.advanceTimeByFrame()
            if (done) return compose.mainClock.currentTime - start
        }
        return -1L
    }

    @Test
    fun `a hold of the swap lead is done on the first frame at or after 130 ms, never before`() {
        host(MimirMotion.OverlayDissolveCoverMs)
        val at = doneAfter(400)
        assertTrue("not before its time, and done at $at", at >= MimirMotion.OverlayDissolveCoverMs)
        assertTrue("and within a couple of frames after it: $at", at <= MimirMotion.OverlayDissolveCoverMs + 32)
    }

    @Test
    fun `under a slowed animator the hold runs slower with the fade it covers`() {
        host(MimirMotion.OverlayDissolveCoverMs, scale = 5f)
        val at = doneAfter(1_500)
        assertTrue("five times the lead, and done at $at", at >= 5 * MimirMotion.OverlayDissolveCoverMs)
        assertTrue("and within a couple of frames after it: $at", at <= 5 * MimirMotion.OverlayDissolveCoverMs + 32)
    }

    @Test
    fun `under a stopped animator a hold returns at once, however long it was`() {
        host(10_000, scale = 0f)
        val at = doneAfter(400)
        assertTrue("done at $at ms, not after its 10 s", at in 0L..16L)
        assertTrue(done)
    }

    @Test
    fun `a hold of nothing waits for nothing`() {
        host(0)
        val at = doneAfter(400)
        assertTrue("done at $at", at in 0L..16L)
    }

    @Test
    fun `a hold cancels with its coroutine and never finishes on its own`() {
        val show = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            if (show.value) {
                LaunchedEffect(Unit) {
                    mimirHold(300)
                    done = true
                }
            }
        }
        repeat(5) { compose.mainClock.advanceTimeByFrame() }
        show.value = false
        Snapshot.sendApplyNotifications()
        repeat(40) { compose.mainClock.advanceTimeByFrame() }
        assertFalse("its time passed, but its coroutine was gone", done)
    }

    private fun code(name: String): String {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name")
        assertTrue("$name not found at ${file.absolutePath}", file.isFile)
        // Comments stripped: the KDocs name the retired `delay()` to explain it, and prose is not a hold.
        return file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
    }

    @Test
    fun `the glass return's hold is the tween's own delay, so it scales with the dissolve it covers`() {
        val glass = code("MimirGlassTransition.kt")
        val ret = glass.substringAfter("fun rememberMimirGlassReturnProgress(").substringBefore("internal data class MimirGlassBlurPlan")
        assertTrue(ret.contains("tween(clearMillis, delayMillis = holdMillis.coerceAtLeast(0), easing = MimirMotion.Easing)"))
        assertFalse("no coroutine delay in the return's effect", ret.contains("delay("))
        assertFalse("nor the import that would bring it back", glass.contains("import kotlinx.coroutines.delay"))
    }

    @Test
    fun `the flare's mount-quiet window is a hold on the animation clock`() {
        val flare = code("MediaArtFrame.kt").substringAfter("val mountQuiet = remember { booleanArrayOf(true) }")
            .substringBefore("LaunchedEffect(settledOn, reducedMotion) {")
        assertTrue(flare.contains("mimirHold(MimirMotion.OverlayMs)"))
        assertFalse(flare.contains("delay("))
    }

    @Test
    fun `the hold is a snap after a delay, not a zero-length tween the literal pin would read as a clock`() {
        val hold = code("MimirPageMotion.kt").substringAfter("suspend fun mimirHold(ms: Int) {").substringBefore("\n}\n")
        assertTrue(hold.contains("Animatable(0f).animateTo(1f, snap(delayMillis = ms))"))
        assertFalse("MimirMotionLiteralPinTest reads tween(0 as a raw clock", hold.contains("tween("))
        assertTrue("a hold of nothing never waits for a frame", hold.contains("if (ms <= 0) return"))
    }
}

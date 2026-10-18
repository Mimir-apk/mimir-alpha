package com.gruning.mimir.rewrite.feature.library

import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit 2026-10-14, RC-18 (L-15 / X-60a): "the shelf's Hints circle overprints B BACK during the launch-card close". The gamelist's Hints button was mounted by
 * `if (visible)`: it left when the detail took the scope and came back at full alpha under the card that was still dissolving. It now FADES: it leaves on
 * OverlayMs at once, and returns after the lead (where the page it returns under is at alpha .044) on OverlayMs, composed for as long as it has any alpha.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LibraryHintRailPresenceTest {

    @get:Rule
    val compose = createComposeRule()

    private val wanted = mutableStateOf(true)
    private var presence: LibraryHintRailPresence? = null

    /** The animator scale is hidden API in the stub jar: the module reaches it by reflection (CarouselTouchTestSupport). */
    private fun durationScale(scale: Float) {
        android.animation.ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    @After
    fun restoreAnimators() {
        durationScale(1f)
    }

    private fun host() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            presence = rememberLibraryHintRailPresence(wanted.value)
        }
        frame()
    }

    private fun frame() = compose.mainClock.advanceTimeByFrame()

    private fun run(ms: Long, each: (Long) -> Unit = {}) {
        val start = compose.mainClock.currentTime
        while (compose.mainClock.currentTime - start < ms) {
            frame()
            each(compose.mainClock.currentTime - start)
        }
    }

    private fun flip(to: Boolean) {
        Snapshot.withMutableSnapshot { wanted.value = to }
        Snapshot.sendApplyNotifications()
        frame()
    }

    private val alpha get() = presence!!.alpha
    private fun composed() = presence!!.composed(wanted.value)

    private fun eased(ms: Long): Float = tween<Float>(MimirMotion.OverlayMs, easing = MimirMotion.Easing)
        .vectorize(Float.VectorConverter)
        .getValueFromNanos(ms * 1_000_000L, AnimationVector1D(0f), AnimationVector1D(1f), AnimationVector1D(0f)).value

    @Test
    fun `the button that is wanted at first is simply there, not arriving`() {
        host()
        assertEquals(1f, alpha, 0f)
        assertTrue(composed())
    }

    @Test
    fun `when a modal takes the scope the button fades out on OverlayMs and stays composed until it is gone`() {
        host()
        flip(false)
        assertTrue("it is still composed on the frame the scope goes (it used to unmount)", composed())
        assertTrue("and still has alpha: $alpha", alpha > 0.5f)
        var lastWhileComposed = 0L
        var goneAt = -1L
        run(400) { t ->
            if (composed()) lastWhileComposed = t
            if (goneAt < 0 && alpha == 0f) goneAt = t
        }
        assertTrue("it is gone in about one OverlayMs: $goneAt", goneAt in (MimirMotion.OverlayMs - 16L)..(MimirMotion.OverlayMs + 48L))
        assertFalse("and then it is not composed, so it takes no touches", composed())
        assertTrue("composed through the fade: $lastWhileComposed", lastWhileComposed >= MimirMotion.OverlayMs - 16L)
    }

    @Test
    fun `when the scope comes back the button waits out the lead and then fades in, never over the card's last frames`() {
        host()
        flip(false)
        run(400)
        assertFalse(composed())
        flip(true)
        assertTrue("it is composed at once, so it can arrive", composed())
        val lead = MimirMotion.OverlayDissolveCoverMs.toLong()
        var firstVisible = -1L
        var overprint = 0
        run(lead + MimirMotion.OverlayMs + 64) { t ->
            val hint = alpha
            if (firstVisible < 0 && hint > 0f) firstVisible = t
            // The card the scope came back from dissolves on OverlayMs from the same instant ("B BACK" is part of it).
            val card = 1f - eased(t)
            if (hint > 0.15f && card > 0.15f) overprint++
        }
        assertTrue("it stays invisible through the lead: $firstVisible", firstVisible >= lead - 16L)
        assertEquals("no frame has the button and the card's B BACK both above alpha .15", 0, overprint)
        assertEquals(1f, alpha, 0f)
    }

    @Test
    fun `a scope that returns mid-fade carries on from where the fade is, without a second lead`() {
        host()
        flip(false)
        run(60)
        val midway = alpha
        assertTrue("part-way out: $midway", midway in 0.05f..0.95f)
        flip(true)
        var rose = -1L
        run(120) { t -> if (rose < 0 && alpha > midway + 0.02f) rose = t }
        assertTrue("it climbs back at once rather than waiting out the 130 ms lead: $rose", rose in 0L..(MimirMotion.OverlayDissolveCoverMs - 16L))
    }

    @Test
    fun `under reduced motion it is a cut that still leaves the composition`() {
        durationScale(0f)
        host()
        flip(false)
        run(32)
        assertEquals(0f, alpha, 0f)
        assertFalse(composed())
        flip(true)
        run(32)
        assertEquals(1f, alpha, 0f)
        assertTrue(composed())
    }

    private fun screenCode(): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
        File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
    ).first(File::isFile).readText()

    @Test
    fun `the gamelist's Hints button is no longer mounted by a bare if, and fades in the draw phase`() {
        val screen = screenCode()
        val site = screen.substringAfter("val hintRailWanted =").substringBefore("// The in-app keyboard floats")
        assertTrue(site.contains("val hintRailPresence = rememberLibraryHintRailPresence(hintRailWanted)"))
        assertTrue(site.contains("if (hintPlatform != null && hintRailPresence.composed(hintRailWanted)) {"))
        assertTrue("alpha is read in the draw phase", site.contains(".graphicsLayer { alpha = hintRailPresence.alpha }"))
        assertFalse(
            "the old bare mount is gone",
            screen.contains("if (hintPlatform != null && libraryNavigationHintRailVisible(navigationHintsEnabled, keyboardOpen, hintScope)) {"),
        )
    }
}

package com.gruning.mimir.rewrite

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
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
 * The Bottom's "Achievement earned" toast arrives and leaves on the house overlay clock (audit B-49, RC-35). The plate used to be composed only while a toast was held, so it cut
 * in and cut out on one frame. Here it stands on a white ground, and each stepped frame is rasterised: the pixel inside the plate's left padding says how much of the plate
 * shows (1 = whole, 0 = gone). The frame right after the toast is cleared still has the plate; it falls monotonically; it is gone, and no longer composed, by
 * [MimirMotion.OverlayMs]; and on the way in it rises from nothing the same way. Pixels are the JVM's; the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AchievementToastFadeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369

    private val toast = AchievementToast(key = 7, raGameId = 1_234, gameTitle = "Chrono Cross", count = 1, title = "First Blood", points = 10, badgePath = null)
    private var shown by mutableStateOf<AchievementToast?>(null)
    private var plate: Rect = Rect.Zero

    @Before
    fun clear() {
        AchievementToasts.resetForTest()
        AchievementToastBands.resetForTest()
        write { shown = null }
    }

    @After
    fun clearAfter() {
        AchievementToasts.resetForTest()
        AchievementToastBands.resetForTest()
    }

    private fun write(block: () -> Unit) = Snapshot.withMutableSnapshot(block)

    private fun step(ms: Long) {
        if (ms > 0L) compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, 1f)) {
                MimirTheme {
                    Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp).background(Color.White)) {
                        AchievementToastLayer(
                            toast = shown,
                            badgeSource = NoActivityBadgeArt,
                            onDone = {},
                            band = AchievementToastBand(
                                Rect(100f * density369, 10f * density369, 440f * density369, 58f * density369),
                                AchievementToastVertical.Center,
                            ),
                        )
                    }
                }
            }
        }
        step(0L)
    }

    private fun exists() = compose.onAllNodesWithTag(AchievementToastTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Where the plate stands (window pixels), remembered while it exists so the pixel can still be read while it leaves. */
    private fun probe(): Pair<Int, Int> {
        val nodes = compose.onAllNodesWithTag(AchievementToastTag, useUnmergedTree = true).fetchSemanticsNodes()
        if (nodes.isNotEmpty()) plate = nodes.first().boundsInWindow
        return (plate.left + 3f * density369).toInt() to plate.center.y.toInt()
    }

    private fun gray(): Float {
        val (x, y) = probe()
        return compose.activity.shootWindow().gray(x, y)
    }

    @Test
    fun `the plate fades out over the overlay clock and the frame after the toast is cleared still has it`() {
        mount()
        write { shown = toast }
        step(16L)
        step(300L)
        val whole = gray()
        assertTrue("the plate is darker than the white ground it stands on (whole=$whole)", 255f - whole > 40f)
        assertTrue(exists())

        write { shown = null }
        val series = ArrayList<Float>()
        val composed = ArrayList<Boolean>()
        var t = 0L
        while (t <= MimirMotion.OverlayMs + 64L) {
            step(16L)
            t += 16L
            composed += exists()
            series += presenceOverWhite(gray(), whole)
        }
        println("TOAST-FADE-OUT presence per 16 ms frame: ${series.joinToString { "%.2f".format(it) }}")
        assertTrue("the first frame after the toast is cleared still has the plate: ${series.first()}", series.first() > 0.85f)
        assertTrue("it falls and never rises: $series", series.zipWithNext().all { (a, b) -> b <= a + 0.03f })
        assertTrue("through a real fade, not a cut: $series", series.count { it in 0.1f..0.9f } >= 3)
        val gone = series.indexOfFirst { it < 0.03f }
        assertTrue("it is gone: $series", gone >= 0)
        assertTrue("by the overlay clock (frame $gone of ${series.size})", (gone + 1) * 16L <= MimirMotion.OverlayMs + 32L)
        assertTrue("composed through the fade", composed.take(gone - 1).all { it })
        assertFalse("and no longer composed once it has gone", composed.last())
    }

    @Test
    fun `the plate fades in from nothing instead of cutting in`() {
        mount()
        write { shown = toast }
        step(16L)
        val first = probe()
        assertTrue("the plate is composed on the first frame", exists())
        step(16L)
        // Whole, for scale.
        step(400L)
        val whole = gray()
        assertTrue(255f - whole > 40f)

        write { shown = null }
        step(400L)
        assertFalse("gone", exists())

        write { shown = toast }
        val series = ArrayList<Float>()
        var t = 0L
        while (t <= MimirMotion.OverlayMs + 64L) {
            step(16L)
            t += 16L
            series += presenceOverWhite(gray(), whole)
        }
        println("TOAST-FADE-IN presence per 16 ms frame (first probe at $first): ${series.joinToString { "%.2f".format(it) }}")
        assertTrue("the first frame is not the whole plate: ${series.first()}", series.first() < 0.5f)
        assertTrue("it rises and never falls: $series", series.zipWithNext().all { (a, b) -> b >= a - 0.03f })
        assertTrue("through a real fade: $series", series.count { it in 0.1f..0.9f } >= 3)
        assertEquals("and is whole at the end", 1f, series.last(), 0.05f)
    }
}

package com.gruning.mimir.rewrite

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
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
 * The step boundary's host ([OnboardingStepCrossfade]) with a red step and a blue step on white, so both alphas read off one
 * pixel: the blue (arriving, on top) step's gap is the red channel, and the green channel is what is left of the white ground.
 * Each page draws the alpha the scaffold's layers draw ([onboardingStepAlpha] of the locals the host provides). * an even cross-fade -- complementary alphas, the pane never emptier than the overlap, one clock for both, and the dispatch
 * gate open once it is done. (The real flow's own boundary is proved in [OnboardingCrossFadeTest].)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w832dp-h468dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingStepCrossfadeHostTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var navigation by mutableStateOf(OnboardingStep.PROFILE)
    private val glide = OnboardingStepGlideState(OnboardingStep.PROFILE)

    private fun step(ms: Long) {
        if (ms > 0L) compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                MimirTheme {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        OnboardingStepCrossfade(step = navigation, stepGlide = glide) { page, _ ->
                            val arrival = LocalOnboardingStepArrival.current
                            val handover = LocalOnboardingStepHandover.current
                            Box(
                                Modifier.fillMaxSize()
                                    .graphicsLayer { alpha = onboardingStepAlpha(arrival.value, handover.departure.value) }
                                    .background(if (page == OnboardingStep.PROFILE) Color.Red else Color.Blue),
                            )
                        }
                    }
                }
            }
        }
        step(0L)
        step(600L)
    }

    @Test
    fun `the step host is an even cross-fade - complementary, never emptier than the overlap, done by the swap clock`() {
        mount()
        val rest = compose.activity.shootWindow().getPixel(416, 234)
        assertTrue("the first step is whole before the move", android.graphics.Color.red(rest) > 250)
        assertEquals(OnboardingStep.PROFILE, glide.displayedStep)

        Snapshot.withMutableSnapshot { navigation = OnboardingStep.ACCESS }
        step(0L)
        val coverage = ArrayList<Float>()
        val arriving = ArrayList<Float>()
        val leaving = ArrayList<Float>()
        var t = 0L
        var gateOpenedAt = -1L
        var settledAt = -1L
        while (t < 400L) {
            step(16L)
            t += 16L
            val p = compose.activity.shootWindow().getPixel(416, 234)
            val r = android.graphics.Color.red(p) / 255f
            val g = android.graphics.Color.green(p) / 255f
            val top = 1f - r
            coverage += 1f - g
            arriving += top
            leaving += if (top < 0.9f) 1f - g / (1f - top) else Float.NaN
            if (gateOpenedAt < 0 && glide.displayedStep == OnboardingStep.ACCESS) gateOpenedAt = t
            if (settledAt < 0 && top > 0.99f && g < 0.02f) settledAt = t
            if (glide.displayedStep != OnboardingStep.ACCESS) {
                assertTrue("a press is dropped while both are on screen", glide.departing(OnboardingStep.ACCESS))
            }
        }
        println("ONB-HOST coverage: ${coverage.joinToString { "%.2f".format(it) }}")
        println("ONB-HOST arriving: ${arriving.joinToString { "%.2f".format(it) }}")
        println("ONB-HOST leaving: ${leaving.joinToString { "%.2f".format(it) }}")
        assertTrue("the pane never empties: min coverage ${coverage.min()}", coverage.min() >= 0.72f)
        assertTrue("the step left is whole on the first frame, not cut: ${leaving.first()}", leaving.first() > 0.7f)
        arriving.indices.filter { !leaving[it].isNaN() }.forEach {
            assertEquals("complementary at frame $it", 1f, arriving[it] + leaving[it], 0.06f)
        }
        assertTrue(
            "a brief overlap",
            arriving.indices.count { !leaving[it].isNaN() && arriving[it] > 0.15f && leaving[it] > 0.15f } >= 2,
        )
        assertTrue("settled by the swap clock: $settledAt", settledAt in 1L..(MimirMotion.OverlayMs + 32L))
        // The gate opens the frame after the clock ends: the coroutine resumes once the animation has reported done.
        assertTrue("the dispatch gate opens with it: $gateOpenedAt", gateOpenedAt in 1L..(MimirMotion.OverlayMs + 48L))
        assertFalse(glide.departing(OnboardingStep.ACCESS))
    }
}

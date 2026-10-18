package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.TweenSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The swap's one-clock form, the Library view swap's clock as an `AnimatedContent` transform: ONE [MimirMotion.OverlayMs] on the house easing, no lead, the page left and the
 * page arriving complementary at every frame, the same in both directions. [MimirSwapMotionTest] holds the swap tier proper (a lead forward, a dissolve-over return) that this is the symmetric alternative to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MimirCrossSwapMotionTest {

    @get:Rule
    val compose = createComposeRule()

    private val page = mutableStateOf("a")
    private val alphas = mutableMapOf<String, State<Float>>()

    private fun host(reducedMotion: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val returning = page.value == "a"
            AnimatedContent(
                targetState = page.value,
                transitionSpec = { mimirCrossSwapTransform(reducedMotion, fade = false, returning = returning, travelPx = 8) },
                label = "crossSwapTest",
            ) { shown ->
                val alpha = mimirCrossSwapFadeAlpha(reducedMotion)
                alphas[shown] = alpha
                Box(Modifier.size(40.dp).mimirRingSafeFade(alpha))
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun swapTo(next: String) {
        page.value = next
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
    }

    /** The (page left, page arriving) alphas on each frame of a swap to [next], 16 ms apart, until both have settled. */
    private fun frames(next: String, left: String): List<Pair<Float, Float>> {
        swapTo(next)
        val out = ArrayList<Pair<Float, Float>>()
        repeat(30) {
            out += (alphas[left]?.value ?: 0f) to (alphas[next]?.value ?: 0f)
            compose.mainClock.advanceTimeByFrame()
        }
        return out
    }

    @Test
    fun `the two pages are complementary at every frame, the same going and coming back`() {
        host()
        val forward = frames(next = "b", left = "a")
        val back = frames(next = "a", left = "b")
        listOf("forward" to forward, "back" to back).forEach { (name, run) ->
            run.forEachIndexed { index, (left, arriving) ->
                // Once the page left is gone from the tree its alpha reads 0 and so does its pair: complementary only while it is still there.
                if (left > 0f) assertTrue("$name frame $index: left $left + arriving $arriving is a cross-fade", abs(left + arriving - 1f) <= 0.03f)
            }
            assertEquals("$name: the page arriving is whole", 1f, run.last().second, 0f)
        }
        assertEquals("one swap both ways: the same alphas frame for frame", forward.map { it.second }, back.map { it.second })
    }

    @Test
    fun `there is no lead and no wait, the page arriving shows within two frames and the swap is whole on the overlay clock`() {
        host()
        swapTo("b")
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertTrue("no 130 ms lead: the page arriving has begun by 32 ms (${alphas.getValue("b").value})", alphas.getValue("b").value > 0.01f)
        val start = compose.mainClock.currentTime
        while (alphas.getValue("b").value < 1f && compose.mainClock.currentTime - start < 1_000L) compose.mainClock.advanceTimeByFrame()
        val settled = compose.mainClock.currentTime - start + 32L
        assertTrue("whole by OverlayMs plus a frame or two ($settled ms)", settled <= MimirMotion.OverlayMs + 32L)
    }

    @Test
    fun `under reduced motion the swap is a cut both ways`() {
        host(reducedMotion = true)
        swapTo("b")
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1f, alphas.getValue("b").value, 0f)
        swapTo("a")
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1f, alphas.getValue("a").value, 0f)
    }

    @Test
    fun `the clock is the house one, for the pages and the resize alike`() {
        val spec = mimirCrossSwapSpec<Float>(reducedMotion = false) as TweenSpec
        assertEquals(MimirMotion.OverlayMs, spec.durationMillis)
        assertEquals("no lead", 0, spec.delay)
        assertEquals(MimirMotion.Easing, spec.easing)
        assertEquals(0, (mimirCrossSwapSpec<Float>(reducedMotion = true) as TweenSpec).durationMillis)
    }

    @Test
    fun `neither direction stacks the page arriving under the page left, so neither is a return`() {
        assertEquals(0f, mimirCrossSwapTransform(reducedMotion = false).targetContentZIndex, 0f)
        assertEquals(0f, mimirCrossSwapTransform(reducedMotion = false, returning = true).targetContentZIndex, 0f)
    }
}

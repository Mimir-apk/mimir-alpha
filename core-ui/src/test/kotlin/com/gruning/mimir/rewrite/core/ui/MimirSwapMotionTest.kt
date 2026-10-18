package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The swap tier (motion audit 2026-09-28, batch A): the old page out on 180 ms, the new one in from the 130 ms lead on
 * 180 ms. On no frame are both pages above alpha 0.15 (double-exposure constraint).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MimirSwapMotionTest {

    @get:Rule
    val compose = createComposeRule()

    private val page = mutableStateOf("a")
    private val alphas = mutableMapOf<String, State<Float>>()

    /** [home]: the page a move back to is a return, decided from the host's target as the tile menu decides it. */
    private fun host(reducedMotion: Boolean = false, home: String? = null) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val returning = home != null && page.value == home
            AnimatedContent(
                targetState = page.value,
                transitionSpec = { mimirSwapTransform(reducedMotion, fade = false, returning = returning) },
                label = "swapTest",
            ) { shown ->
                val alpha = mimirSwapFadeAlpha(reducedMotion, returning = returning)
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

    @Test
    fun `the old page leaves before the new one arrives, and they never show together`() {
        host()
        assertEquals(1f, alphas.getValue("a").value, 0f)
        swapTo("b")
        val start = compose.mainClock.currentTime
        var t = 0L
        var bothVisible = 0
        while (t < 400L) {
            val old = alphas["a"]?.value ?: 0f
            val new = alphas["b"]?.value ?: 0f
            if (old > 0.15f && new > 0.15f) bothVisible++
            if (t < MimirMotion.OverlayDissolveCoverMs - 16L) assertEquals("the new page waits at $t ms", 0f, new, 0.001f)
            if (t in 130L..146L) assertTrue("the old page is nearly gone at $t ms: $old", old <= 0.06f)
            compose.mainClock.advanceTimeByFrame()
            t = compose.mainClock.currentTime - start
        }
        assertEquals("frames with both pages above 0.15", 0, bothVisible)
        assertEquals(1f, alphas.getValue("b").value, 0f)
    }

    @Test
    fun `the swap settles about 310 ms after the change`() {
        host()
        swapTo("b")
        val start = compose.mainClock.currentTime
        while (alphas["b"]?.value != 1f) compose.mainClock.advanceTimeByFrame()
        val settled = compose.mainClock.currentTime - start
        assertTrue("settled at $settled ms", settled in 300L..340L)
    }

    @Test
    fun `under reduced motion the swap is a cut`() {
        host(reducedMotion = true)
        swapTo("b")
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1f, alphas.getValue("b").value, 0f)
    }

    @Test
    fun `the clocks are the house ones`() {
        val exit = mimirSwapExitSpec<Float>(reducedMotion = false) as androidx.compose.animation.core.TweenSpec
        val enter = mimirSwapEnterSpec<Float>(reducedMotion = false) as androidx.compose.animation.core.TweenSpec
        assertEquals(MimirMotion.OverlayMs, exit.durationMillis)
        assertEquals(0, exit.delay)
        assertEquals(MimirMotion.OverlayMs, enter.durationMillis)
        assertEquals(MimirMotion.OverlayDissolveCoverMs, enter.delay)
        assertEquals(MimirMotion.Easing, enter.easing)
        val returnExit = mimirSwapExitSpec<Float>(reducedMotion = false, returning = true)
            as androidx.compose.animation.core.TweenSpec
        val returnEnter = mimirSwapEnterSpec<Float>(reducedMotion = false, returning = true)
            as androidx.compose.animation.core.TweenSpec
        assertEquals(MimirPageMotion.ReturnMs, returnExit.durationMillis)
        assertEquals(0, returnEnter.durationMillis)
        assertEquals(0, returnEnter.delay)
    }

    /**
     * Review of 89d626d38: a return held its page at alpha 0 for the 130 ms lead, so B landed on a blank panel. A return
     * is the page tier's (without the forward-entry lead): the page returned to at rest at once, the page left
     * dissolving over it on ReturnMs. A move deeper still waits the lead.
     */
    @Test
    fun `a return shows its page at once and the page left dissolves over it on the return clock`() {
        host(home = "a")
        swapTo("b")
        compose.mainClock.advanceTimeBy(100L)
        assertEquals("a move deeper still waits the lead", 0f, alphas.getValue("b").value, 0.001f)
        compose.mainClock.advanceTimeBy(400L)
        assertEquals(1f, alphas.getValue("b").value, 0f)

        swapTo("a")
        compose.mainClock.advanceTimeByFrame()
        assertEquals("the page returned to is at rest from the first frame", 1f, alphas.getValue("a").value, 0f)
        val left = alphas.getValue("b")
        assertTrue("the page left dissolves rather than cutting: ${left.value}", left.value > 0.15f)
        val start = compose.mainClock.currentTime
        while (left.value > 0f && compose.mainClock.currentTime - start < 1_000L) compose.mainClock.advanceTimeByFrame()
        val gone = compose.mainClock.currentTime - start
        assertTrue("the page left is gone at $gone ms", gone <= MimirPageMotion.ReturnMs + 16L)
    }

    @Test
    fun `under reduced motion a return is a cut`() {
        host(reducedMotion = true, home = "a")
        swapTo("b")
        compose.mainClock.advanceTimeByFrame()
        swapTo("a")
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1f, alphas.getValue("a").value, 0f)
        assertEquals(0f, alphas.getValue("b").value, 0f)
    }

    @Test
    fun `a return draws the page left over the page returned to`() {
        assertEquals(-1f, mimirSwapTransform(reducedMotion = false, returning = true).targetContentZIndex, 0f)
        assertEquals(0f, mimirSwapTransform(reducedMotion = false).targetContentZIndex, 0f)
    }
}

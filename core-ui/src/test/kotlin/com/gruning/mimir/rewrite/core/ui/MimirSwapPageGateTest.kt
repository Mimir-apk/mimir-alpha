package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
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

/**
 * S1 and S2 (alpha audit 2026-09-30): the swap page's input gate, and the 15%-visible input boundary for Quick
 * Launch. Presses during a page change wait for the page they would act on : the gate is held
 * from the arriving page's first frame until a probe on its own fade is above `minAlpha`, and a reversal frees it. Touch
 * bypasses the router, so the same modifier shields the page: a page that is leaving takes no touch and clears its
 * semantics, an arriving one takes none until it shows.
 *
 * Two hosts, the two shapes callers have: an `AnimatedContent` swap (the tile menu, the Tome editor, Utility), and an
 * `AnimatedVisibility` whose `fadeIn` and `fadeOut` run the specs the probe is given (Quick Launch). The gate is
 * process-wide, so each test runs on a virtual clock and resets it (HomeTileMenuSwapInputGateTest's arrangement).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MimirSwapPageGateTest {

    @get:Rule
    val compose = createComposeRule()

    private val page = mutableStateOf("a")
    private val open = mutableStateOf(false)
    private val epoch = mutableIntStateOf(0)
    private val taps = mutableListOf<String>()
    private val alphas = mutableMapOf<String, State<Float>>()
    private var dockAlpha: State<Float>? = null

    @Before
    fun gateClock() {
        MimirTransitionInputGate.reset()
        MimirTransitionInputGate.clock = { compose.mainClock.currentTime }
    }

    @After
    fun resetGate() = MimirTransitionInputGate.reset()

    /** The tile menu's shape: pages on `mimirSwapTransform(fade = false)` that draw their own probe. */
    private fun swapHost(minAlpha: Float = 0f, home: String? = null, reducedMotion: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val returning = home != null && page.value == home
            AnimatedContent(
                targetState = page.value,
                transitionSpec = { mimirSwapTransform(reducedMotion, fade = false, returning = returning) },
                label = "swapGateTest",
            ) { shown ->
                val alpha = mimirSwapFadeAlpha(reducedMotion, returning = returning)
                alphas[shown] = alpha
                val gate = MimirSwapPageGate(alpha, minAlpha)
                Box(Modifier.size(80.dp).mimirRingSafeFade(alpha).then(gate)) {
                    Box(Modifier.size(80.dp).testTag("page-$shown").clickable { taps += shown })
                }
            }
        }
        frame()
    }

    private val dockEnter = tween<Float>(
        durationMillis = MimirPageMotion.ArriveMs,
        delayMillis = MimirPageMotion.LeadMs,
        easing = MimirMotion.Easing,
    )
    private val dockExit = tween<Float>(durationMillis = MimirMotion.OverlayMs, easing = MimirMotion.Easing)

    /**
     * Quick Launch's shape: the page tier's lead and slow arrival on `fadeIn`, the sheet clock on `fadeOut`, the panel
     * keyed on the open generation, and a probe on the same two specs gating at [MimirPageMotion.ShownAlpha].
     */
    private fun dockHost(holdPad: Boolean = true) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AnimatedVisibility(visible = open.value, enter = fadeIn(dockEnter), exit = fadeOut(dockExit)) {
                key(epoch.intValue) {
                    val alpha = mimirRingSafeFadeAlpha(dockEnter, dockExit)
                    dockAlpha = alpha
                    val gate = MimirSwapPageGate(alpha, MimirPageMotion.ShownAlpha, holdPad)
                    Box(Modifier.size(80.dp).then(gate)) {
                        Box(Modifier.size(80.dp).testTag("dock").clickable { taps += "dock" })
                    }
                }
            }
        }
        frame()
    }

    private fun frame() = compose.mainClock.advanceTimeByFrame()

    private fun now() = compose.mainClock.currentTime

    private fun swapTo(next: String) {
        page.value = next
        Snapshot.sendApplyNotifications()
        frame()
    }

    private fun setOpen(value: Boolean) {
        open.value = value
        Snapshot.sendApplyNotifications()
        frame()
    }

    private fun settle() = compose.mainClock.advanceTimeBy(MimirMotion.ShineMs * 3L)

    private fun tap(tag: String): List<String> {
        val before = taps.size
        compose.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput { click() }
        frame()
        return taps.drop(before)
    }

    /** One row per frame from the change: (ms since it, the probe's alpha, whether the pad is held). */
    private fun watch(ms: Long, alpha: () -> Float): List<Triple<Long, Float, Boolean>> {
        val start = now()
        val rows = mutableListOf<Triple<Long, Float, Boolean>>()
        while (now() - start < ms) {
            rows += Triple(now() - start, alpha(), MimirTransitionInputGate.holding)
            frame()
        }
        return rows
    }

    @Test
    fun `S1 the shown figure is 15 percent, and on the house curve that is about 71 ms into the 540 ms arrival`() {
        assertEquals(0.15f, MimirPageMotion.ShownAlpha, 0f)
        fun msTo(alpha: Float, over: Int): Float {
            var lo = 0f
            var hi = 1f
            repeat(40) { if (MimirMotion.Easing.transform((lo + hi) / 2f) < alpha) lo = (lo + hi) / 2f else hi = (lo + hi) / 2f }
            return lo * over
        }
        assertEquals("1% at 11 ms", 11f, msTo(0.01f, MimirPageMotion.ArriveMs), 1f)
        assertEquals("15% at 71 ms", 71f, msTo(MimirPageMotion.ShownAlpha, MimirPageMotion.ArriveMs), 1f)
        assertEquals("25% at 97 ms", 97f, msTo(0.25f, MimirPageMotion.ArriveMs), 1f)
    }

    @Test
    fun `the page a host opens on holds nothing`() {
        swapHost(minAlpha = MimirPageMotion.ShownAlpha)
        assertFalse("an open page is not a page change", MimirTransitionInputGate.holding)
        assertEquals(listOf("a"), tap("page-a"))
    }

    @Test
    fun `at 15 percent the pad is held from the first frame until the arriving page is above it, then freed`() {
        swapHost(minAlpha = MimirPageMotion.ShownAlpha)
        swapTo("b")
        val rows = watch(400L) { alphas.getValue("b").value }
        rows.forEach { (t, alpha, held) ->
            if (alpha <= MimirPageMotion.ShownAlpha) assertTrue("held at $t ms while the page is at $alpha", held)
        }
        val shownAt = rows.first { it.second > MimirPageMotion.ShownAlpha }.first
        val freedAt = rows.first { !it.third }.first
        // The lead is 130 ms and the arrival's own fade is 180 ms: 15% of it is about 24 ms in.
        assertTrue("the arriving page is above 15% at $shownAt ms", shownAt in 140L..180L)
        assertTrue("the pad is freed within two frames of that: $shownAt -> $freedAt", freedAt in shownAt..shownAt + 32L)
    }

    @Test
    fun `at 0 the pad is freed on the arriving page's first painted frame, as the tile menu's is`() {
        swapHost()
        swapTo("b")
        val rows = watch(400L) { alphas.getValue("b").value }
        rows.forEach { (t, alpha, held) -> if (alpha <= 0f) assertTrue("held at $t ms while the page is unpainted", held) }
        val paintedAt = rows.first { it.second > 0f }.first
        val freedAt = rows.first { !it.third }.first
        assertTrue("painted after the 130 ms lead: $paintedAt", paintedAt >= MimirMotion.OverlayDissolveCoverMs.toLong())
        assertTrue("freed within two frames of that: $paintedAt -> $freedAt", freedAt in paintedAt..paintedAt + 32L)
    }

    @Test
    fun `a reversal inside the lead frees the pad at once, and the page it returns to answers`() {
        swapHost(minAlpha = MimirPageMotion.ShownAlpha)
        swapTo("b")
        compose.mainClock.advanceTimeBy(50L)
        assertTrue(MimirTransitionInputGate.holding)
        swapTo("a")
        frame()
        assertFalse("a reversed swap must not leave the pad deaf", MimirTransitionInputGate.holding)
        settle()
        assertEquals(listOf("a"), tap("page-a"))
    }

    @Test
    fun `a return holds for a frame or two at most, since the page returned to is at rest at once`() {
        swapHost(minAlpha = MimirPageMotion.ShownAlpha, home = "a")
        swapTo("b")
        settle()
        swapTo("a")
        compose.mainClock.advanceTimeBy(3 * 16L)
        assertFalse("a return paints its page at once, so the pad must not wait out a lead", MimirTransitionInputGate.holding)
    }

    @Test
    fun `an arriving page takes no touch until it is shown, and answers once it is`() {
        swapHost(minAlpha = MimirPageMotion.ShownAlpha)
        swapTo("b")
        compose.mainClock.advanceTimeBy(60L)
        assertEquals("the arriving page is at alpha 0: a tap reaches nothing", emptyList<String>(), tap("page-b"))
        while (alphas.getValue("b").value <= 0f) frame()
        val trace = alphas.getValue("b").value
        assertTrue("painted, but still a trace: $trace", trace > 0f && trace <= MimirPageMotion.ShownAlpha)
        assertEquals("first paint is not enough at 15%", emptyList<String>(), tap("page-b"))
        settle()
        assertEquals(listOf("b"), tap("page-b"))
    }

    @Test
    fun `a page that is leaving takes no touch and clears its semantics, and the page returned to answers once it is gone`() {
        swapHost(home = "a")
        swapTo("b")
        settle()
        swapTo("a")
        compose.mainClock.advanceTimeBy(20L)
        val leaving = alphas.getValue("b").value
        assertTrue("the page left is still dissolving over the page returned to: $leaving", leaving > 0.15f)
        // It is drawn above the page returned to on a return, so the tap lands on it, and reaches nothing.
        assertEquals(emptyList<String>(), tap("page-a"))
        compose.onNodeWithTag("page-a").assertExists()
        assertTrue(
            "the departing page clears its semantics: only the page returned to keeps its node",
            compose.onAllNodesWithTag("page-b").fetchSemanticsNodes().isEmpty(),
        )
        settle()
        assertEquals(listOf("a"), tap("page-a"))
    }

    @Test
    fun `under reduced motion the gate lets go at once`() {
        swapHost(minAlpha = MimirPageMotion.ShownAlpha, reducedMotion = true)
        swapTo("b")
        compose.mainClock.advanceTimeBy(3 * 16L)
        assertFalse(MimirTransitionInputGate.holding)
        assertEquals(listOf("b"), tap("page-b"))
    }

    /**
     * The Utility panel's chain is the gate, then a bounds catcher that is also `pointerInput(Unit)`. Compose matches two
     * `pointerInput` elements with equal keys as one node whatever their lambdas, so a shield keyed on `Unit` that dropped
     * out of that chain left the survivor running its consume-everything loop: the Tweaker painted and answered nothing
     * after a tap during its lead. The shield has its own key.
     */
    @Test
    fun `a pointerInput after the gate in the same chain still answers once the page shows`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AnimatedContent(
                targetState = page.value,
                transitionSpec = { mimirSwapTransform(reducedMotion = false, fade = false) },
                label = "swapGateChainTest",
            ) { shown ->
                val alpha = mimirSwapFadeAlpha(reducedMotion = false)
                val gate = MimirSwapPageGate(alpha)
                Box(
                    Modifier
                        .size(80.dp)
                        .mimirRingSafeFade(alpha)
                        .then(gate)
                        .pointerInput(Unit) { detectTapGestures { taps += shown } }
                        .testTag("page-$shown"),
                )
            }
        }
        frame()
        swapTo("b")
        compose.mainClock.advanceTimeBy(60L)
        assertEquals("the arriving page is shielded", emptyList<String>(), tap("page-b"))
        settle()
        assertEquals("and the pointerInput behind the shield answers once it is gone", listOf("b"), tap("page-b"))
    }

    // --- Quick Launch's shape.

    @Test
    fun `a dock opened cold holds the pad until its fade is at 15 percent, about 200 ms after the press`() {
        dockHost()
        assertFalse(MimirTransitionInputGate.holding)
        setOpen(true)
        val rows = watch(500L) { dockAlpha?.value ?: 0f }
        rows.forEach { (t, alpha, held) ->
            if (alpha <= MimirPageMotion.ShownAlpha) assertTrue("held at $t ms while the dock is at $alpha", held)
        }
        val shownAt = rows.first { it.second > MimirPageMotion.ShownAlpha }.first
        val freedAt = rows.first { !it.third }.first
        // 130 ms lead + 71 ms into the 540 ms fade.
        assertTrue("above 15% at $shownAt ms", shownAt in 190L..225L)
        assertTrue("freed within two frames of that: $shownAt -> $freedAt", freedAt in shownAt..shownAt + 32L)
        val first = rows.first { it.second > 0f }.first
        assertTrue("first paint is far sooner, at $first ms: first paint is what the second A used to reach", first < shownAt - 50L)
    }

    @Test
    fun `B during the hold closes the dock and frees the pad`() {
        dockHost()
        setOpen(true)
        compose.mainClock.advanceTimeBy(80L)
        assertTrue(MimirTransitionInputGate.holding)
        setOpen(false)
        frame()
        assertFalse("Back always works, and a dock that is going holds nothing", MimirTransitionInputGate.holding)
    }

    @Test
    fun `a dock reopened inside its own dismiss fade holds nothing`() {
        dockHost()
        setOpen(true)
        settle()
        setOpen(false)
        compose.mainClock.advanceTimeBy(60L)
        epoch.intValue += 1
        setOpen(true)
        frame()
        assertFalse("the house behaviour: a reopen inside a dismiss fade holds nothing", MimirTransitionInputGate.holding)
        assertEquals("and the dock is on screen, so it takes a touch", listOf("dock"), tap("dock"))
    }

    @Test
    fun `a dock takes no touch until it is at 15 percent and none while it leaves`() {
        dockHost()
        setOpen(true)
        compose.mainClock.advanceTimeBy(100L)
        assertEquals("before the lead is out the dock is not there", emptyList<String>(), tap("dock"))
        compose.mainClock.advanceTimeBy(60L)
        assertTrue("the fade has started, at ${dockAlpha?.value}", (dockAlpha?.value ?: 0f) > 0f)
        assertEquals("a trace is not a dock", emptyList<String>(), tap("dock"))
        settle()
        assertEquals(listOf("dock"), tap("dock"))
        setOpen(false)
        compose.mainClock.advanceTimeBy(40L)
        assertEquals("a dock that is leaving is inert", emptyList<String>(), tap("dock"))
    }

    // --- The Bottom's dock (A4): the same gate with the pad half dropped.

    @Test
    fun `a dock with holdPad false never holds the pad, and still shields touch until it is above 15 percent`() {
        dockHost(holdPad = false)
        setOpen(true)
        val rows = watch(500L) { dockAlpha?.value ?: 0f }
        rows.forEach { (t, _, held) ->
            assertFalse("the Bottom must not deafen Main's controller: held at $t ms", held)
        }
        assertFalse(MimirTransitionInputGate.holding)
        // The touch half is the gate's and is unchanged: nothing reaches the dock until it passes the shown threshold.
        setOpen(false)
        settle()
        setOpen(true)
        compose.mainClock.advanceTimeBy(100L)
        assertEquals("inside the lead the dock is not there", emptyList<String>(), tap("dock"))
        compose.mainClock.advanceTimeBy(60L)
        assertTrue("painted, but still a trace: ${dockAlpha?.value}", (dockAlpha?.value ?: 0f) > 0f)
        assertTrue((dockAlpha?.value ?: 1f) <= MimirPageMotion.ShownAlpha)
        assertEquals("a trace takes no touch", emptyList<String>(), tap("dock"))
        assertFalse(MimirTransitionInputGate.holding)
        settle()
        assertEquals(listOf("dock"), tap("dock"))
    }

    @Test
    fun `a dock with holdPad false is inert while it leaves, as the holding gate's is`() {
        dockHost(holdPad = false)
        setOpen(true)
        settle()
        setOpen(false)
        compose.mainClock.advanceTimeBy(40L)
        assertFalse(MimirTransitionInputGate.holding)
        assertEquals(emptyList<String>(), tap("dock"))
    }

    @Test
    fun `the overlay arrive spec is the page tier, 540 ms after a 130 ms lead on the house easing, and snaps under reduced motion`() {
        val arrive = mimirOverlayArriveSpec(reducedMotion = false) as TweenSpec<Float>
        assertEquals(540, arrive.durationMillis)
        assertEquals(130, arrive.delay)
        assertEquals(MimirMotion.Easing, arrive.easing)
        val reduced = mimirOverlayArriveSpec(reducedMotion = true) as TweenSpec<Float>
        assertEquals(0, reduced.durationMillis)
        assertEquals(0, reduced.delay)
        val leave = mimirOverlayLeaveSpec() as TweenSpec<Float>
        assertEquals(180, leave.durationMillis)
        assertEquals(0, leave.delay)
        assertEquals(MimirMotion.Easing, leave.easing)
    }
}

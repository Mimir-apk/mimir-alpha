package com.gruning.mimir.rewrite.feature.library

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTransitionInputGate
import com.gruning.mimir.rewrite.core.ui.mimirSwapEnterSpec
import kotlinx.coroutines.CompletableDeferred
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
 * Audit 2026-10-14, RC-10 (L-12 / L-13): "shelf to list (R1) dips through near-black: the shelf fades to dim, then the list fades in". The Carousel/List
 * swap ran on the swap tier's lead: the old view left on OverlayMs, the new one waited 130 ms (where the old is at alpha .044) and arrived over [MimirMotion.ShineMs],
 * so from about 130 ms the body was the bare backdrop. It is now a cross-fade of the two views on ONE clock ([libraryViewCrossfadeSpec]): the new view arrives from the first
 * frame while the old one leaves, complementary at every frame. And the Normal/Favorites-only swap (X, RC-18 / L-10: "hard-cuts with no reflow") rides the same.
 *
 * Luminance is computed from the two alphas the handoff actually draws (the departing page on top of the arriving one), because the handoff records its pages in
 * hand-recorded `GraphicsLayer`s, which a JVM software canvas cannot draw: the probe reads the alphas the draw block applies on the paused clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GamelistViewSwapCrossfadeTest {

    @get:Rule
    val compose = createComposeRule()

    private val viewMode = mutableStateOf(GamelistViewMode.CAROUSEL)
    private val favoritesOnly = mutableStateOf(false)
    private val platform = mutableStateOf(PlatformId.PS1)
    private val reduced = mutableStateOf(false)
    private val probe = LibraryPageProbe()
    private var settle: (suspend () -> Unit)? = null
    private var uptime = 0L

    @Before
    fun clock() {
        MimirTransitionInputGate.reset()
        MimirTransitionInputGate.clock = { uptime }
    }

    @After
    fun resetGate() = MimirTransitionInputGate.reset()

    private fun host() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                GamelistViewModeLandingTransition(
                    mode = viewMode.value,
                    modifier = Modifier.size(100.dp),
                    reducedMotion = reduced.value,
                    settle = settle,
                    favoritesOnly = favoritesOnly.value,
                    platformId = platform.value,
                    probe = probe,
                ) {
                    Box(Modifier.size(100.dp))
                }
            }
        }
        frame()
    }

    private fun frame() {
        compose.mainClock.advanceTimeByFrame()
        uptime += 16
    }

    private fun run(ms: Long, each: (Long) -> Unit = {}) {
        val start = compose.mainClock.currentTime
        while (compose.mainClock.currentTime - start < ms) {
            frame()
            each(compose.mainClock.currentTime - start)
        }
    }

    private fun swap(block: () -> Unit) {
        Snapshot.withMutableSnapshot(block)
        Snapshot.sendApplyNotifications()
        frame()
    }

    /** What the handoff draws: the departing page ON TOP of the arriving one, over the backdrop. Pages that cover the whole body. */
    private fun composite(outgoingAlpha: Float, incomingAlpha: Float, outgoingLuma: Float, incomingLuma: Float, backdropLuma: Float): Float {
        val under = incomingAlpha * incomingLuma + (1f - incomingAlpha) * backdropLuma
        return outgoingAlpha * outgoingLuma + (1f - outgoingAlpha) * under
    }

    private fun sample(spec: FiniteAnimationSpec<Float>, ms: Long): Float = spec.vectorize(Float.VectorConverter)
        .getValueFromNanos(ms * 1_000_000L, AnimationVector1D(0f), AnimationVector1D(1f), AnimationVector1D(0f)).value

    private val shelfLuma = 0.55f
    private val listLuma = 0.45f
    private val backdropLuma = 0.05f

    @Test
    fun `the view swap brings the new view in from the first frame while the old one leaves, complementary on one clock`() {
        host()
        swap { viewMode.value = GamelistViewMode.LIST }
        var firstIncoming = -1L
        var worstSum = 0f
        val series = mutableListOf<String>()
        var bothGoneAt = -1L
        run(400) { t ->
            val incoming = probe.incomingAlpha()
            val outgoing = probe.outgoingAlpha()
            series += "$t:${"%.2f".format(incoming)}/${"%.2f".format(outgoing)}"
            if (firstIncoming < 0 && incoming > 0f) firstIncoming = t
            if (t < MimirMotion.OverlayMs - 16) worstSum = maxOf(worstSum, kotlin.math.abs(incoming + outgoing - 1f))
            if (bothGoneAt < 0 && incoming == 1f && outgoing == 0f) bothGoneAt = t
        }
        println("GamelistViewSwapCrossfade incoming/outgoing per frame: ${series.take(16).joinToString(" ")}")
        assertTrue("the new view shows from the first frames, not after the 130 ms lead: $firstIncoming", firstIncoming in 0L..64L)
        assertTrue("the two alphas are complementary through the swap, within a frame of each other (worst |in + out - 1| = $worstSum)", worstSum < 0.2f)
        assertTrue("one OverlayMs clock, not a lead and a 540 ms arrival: settled at $bothGoneAt", bothGoneAt in MimirMotion.OverlayMs.toLong()..(MimirMotion.OverlayMs + 64L))
        assertEquals(1f, probe.incomingAlpha(), 0f)
        assertEquals(0f, probe.outgoingAlpha(), 0f)
    }

    @Test
    fun `the swap never empties the body toward the backdrop, where the lead did`() {
        host()
        swap { viewMode.value = GamelistViewMode.LIST }
        var lowest = Float.MAX_VALUE
        run(400) {
            lowest = minOf(lowest, composite(probe.outgoingAlpha(), probe.incomingAlpha(), shelfLuma, listLuma, backdropLuma))
        }
        // The old swap, from its own specs: the departing view on OverlayMs, the arriving one after the 130 ms lead (mimirSwapEnterSpec), same pages, same backdrop.
        val exit = tween<Float>(MimirMotion.OverlayMs, easing = MimirMotion.Easing)
        val oldEnter = mimirSwapEnterSpec<Float>(reducedMotion = false)
        var oldLowest = Float.MAX_VALUE
        for (t in 0L..700L step 8L) {
            val out = 1f - sample(exit, t)
            val incoming = sample(oldEnter, t)
            oldLowest = minOf(oldLowest, composite(out, incoming, shelfLuma, listLuma, backdropLuma))
        }
        println("GamelistViewSwapCrossfade: lowest body luminance, cross-fade $lowest, lead swap $oldLowest (endpoints $shelfLuma / $listLuma, backdrop $backdropLuma)")
        assertTrue("the harness sees the lead's near-black frame: $oldLowest", oldLowest < backdropLuma + 0.05f)
        // A cross-fade of two pages that cover each other keeps the overlap term only: at most a quarter of the gap to the backdrop below the dimmer page.
        val floor = listLuma - 0.25f * (listLuma - backdropLuma)
        assertTrue("the cross-fade stays above the overlap floor $floor (lowest $lowest)", lowest >= floor)
        assertTrue("and well clear of the backdrop (lowest $lowest)", lowest > backdropLuma + 0.25f)
    }

    @Test
    fun `X in the List swaps the Normal and Favorites lists on the same cross-fade, and waits for no covers`() {
        viewMode.value = GamelistViewMode.LIST
        settle = { CompletableDeferred<Unit>().await() }
        host()
        swap { favoritesOnly.value = true }
        var firstIncoming = -1L
        run(300) { t -> if (firstIncoming < 0 && probe.incomingAlpha() > 0f) firstIncoming = t }
        assertTrue("the List filter swap starts at once, with no settle to wait for: $firstIncoming", firstIncoming in 0L..64L)
        assertEquals(1f, probe.incomingAlpha(), 0f)
        assertFalse("and the pad is free", MimirTransitionInputGate.holding)
    }

    @Test
    fun `X in the Carousel holds the old list for the new one's covers, then cross-fades`() {
        val covers = CompletableDeferred<Unit>()
        settle = { covers.await() }
        host()
        swap { favoritesOnly.value = true }
        run(240) { t ->
            assertEquals("the new list waits behind the old at $t ms", 0f, probe.incomingAlpha(), 0f)
            assertEquals("and the old one stays whole at $t ms", 1f, probe.outgoingAlpha(), 0f)
        }
        covers.complete(Unit)
        var firstIncoming = -1L
        run(300) { t -> if (firstIncoming < 0 && probe.incomingAlpha() > 0f) firstIncoming = t }
        assertTrue("the cross-fade runs as soon as the covers are in: $firstIncoming", firstIncoming in 0L..96L)
        assertEquals(1f, probe.incomingAlpha(), 0f)
        assertEquals(0f, probe.outgoingAlpha(), 0f)
        assertFalse("and the pad is free", MimirTransitionInputGate.holding)
    }

    @Test
    fun `a view change waits for its covers, and past the cap lets go`() {
        settle = { CompletableDeferred<Unit>().await() }
        host()
        swap { viewMode.value = GamelistViewMode.LIST }
        run(200) { t -> assertEquals("the new view waits behind the old at $t ms", 0f, probe.incomingAlpha(), 0f) }
        run(700) { }
        assertEquals("the cap let the view go", 1f, probe.incomingAlpha(), 0f)
    }

    @Test
    fun `a Favorites flip that comes with a platform switch is the switch's to fade, not a second X`() {
        favoritesOnly.value = true
        host()
        run(64)
        // L2/R2 resets the Favorites-only flag inside the platform switch (LibraryViewModel.resetPerGamelistFilters).
        swap {
            platform.value = PlatformId.SNES
            favoritesOnly.value = false
        }
        assertEquals("the body is at once the new platform's, whole", 1f, probe.incomingAlpha(), 0f)
        assertEquals("with no second list left fading under the switch's own", 0f, probe.outgoingAlpha(), 0f)
        run(240) { t ->
            assertEquals("still whole at $t ms", 1f, probe.incomingAlpha(), 0f)
            assertEquals("and nothing departs at $t ms", 0f, probe.outgoingAlpha(), 0f)
        }
        // On the new platform an X is an X again.
        swap { favoritesOnly.value = true }
        var firstIncoming = -1L
        run(300) { t -> if (firstIncoming < 0 && probe.incomingAlpha() > 0f) firstIncoming = t }
        assertTrue("the next flip on the same platform cross-fades: $firstIncoming", firstIncoming in 0L..64L)
    }

    @Test
    fun `the epoch rules - a platform change is never an X, and the Carousel holds for an X where the List does not`() {
        val normal = GamelistViewEpoch(GamelistViewMode.CAROUSEL, favoritesOnly = false)
        val favorites = GamelistViewEpoch(GamelistViewMode.CAROUSEL, favoritesOnly = true)
        val list = GamelistViewEpoch(GamelistViewMode.LIST, favoritesOnly = false)
        assertTrue(libraryGamelistViewEpochLandingApplies(normal, favorites, samePlatform = true))
        assertFalse(libraryGamelistViewEpochLandingApplies(normal, favorites, samePlatform = false))
        assertFalse("nothing changed, nothing to animate", libraryGamelistViewEpochLandingApplies(normal, normal, samePlatform = true))
        assertTrue(libraryGamelistViewSwapHoldsForCovers(normal, favorites))
        assertTrue("a view change always holds", libraryGamelistViewSwapHoldsForCovers(normal, list))
        assertTrue(libraryGamelistViewSwapHoldsForCovers(list, normal))
        assertFalse("the List has one preview cover and nothing to wait for", libraryGamelistViewSwapHoldsForCovers(list, GamelistViewEpoch(GamelistViewMode.LIST, favoritesOnly = true)))
    }

    @Test
    fun `under reduced motion both swaps are a cut and the gleam is released at once`() {
        reduced.value = true
        host()
        swap { viewMode.value = GamelistViewMode.LIST }
        assertEquals(1f, probe.incomingAlpha(), 0f)
        assertTrue(probe.arrived())
        swap { favoritesOnly.value = true }
        assertEquals(1f, probe.incomingAlpha(), 0f)
        assertEquals(0f, probe.outgoingAlpha(), 0f)
        assertTrue(probe.arrived())
    }

    @Test
    fun `the swap's clock is the shared cross-fade spec, and it takes no lead`() {
        val spec = libraryViewCrossfadeSpec<Float>(reducedMotion = false)
        assertEquals(0f, sample(spec, 0), 0f)
        assertTrue("a trace of the new view at 16 ms", sample(spec, 16) > 0.02f)
        assertEquals(1f, sample(spec, MimirMotion.OverlayMs.toLong()), 0.001f)
        assertEquals("reduced motion is a snap", 1f, sample(libraryViewCrossfadeSpec<Float>(reducedMotion = true), 1), 0f)
    }
}

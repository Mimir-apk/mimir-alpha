package com.gruning.mimir.rewrite

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.rememberMimirGlassProgress
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Customize media's glass and scope (audit 2026-10-14: RC-26 H-09a / L-08a, RC-50 H-09).
 *
 *  - The page behind a dropdown stays glass while the dropdown dissolves and clears only after [MimirMotion.OverlayDissolveCoverMs]
 *   , on the shared glass clock; the progress is read in DRAW, so the page does not recompose per
 *    frame. Robolectric cannot draw the blur layer: the pixel proof is the sweep's (emulator, animator scale 5).
 *  - The picker lets go of its focus scope on the commit that takes its page away, once, and never discards the scope a re-entered page owns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnrichPickerMotionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun source(name: String): String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
    ).firstOrNull(File::exists)?.readText() ?: throw AssertionError("$name not reachable from the test working directory")

    /** One frame at a time, 16 ms each, with the clock held. */
    private fun frame() {
        compose.mainClock.advanceTimeBy(16L)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    @Test
    fun `the glass holds through the dropdown's dissolve and then clears on the glass clock`() {
        var requested by mutableStateOf(true)
        val progress = mutableListOf<Pair<Long, Float>>()
        var recompositions = 0
        var glassNow: () -> Float = { error("not composed") }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            recompositions++
            val held = rememberHomeCustomEnrichGlassHold(requested = requested)
            val glass = rememberMimirGlassProgress(visible = held, identity = "page")
            // The progress lambda is the thing a draw block reads; the test reads it as each frame lands (a draw is not delivered on a paused looper).
            glassNow = glass
            Box(Modifier.fillMaxSize())
        }
        fun frame() {
            this.frame()
            progress += compose.mainClock.currentTime to glassNow()
        }
        frame()
        repeat(16) { frame() } // the open ramp settles
        assertEquals("the glass is whole behind the open dropdown", 1f, progress.last().second, 0.001f)

        val closedAt = compose.mainClock.currentTime
        val recompositionsBefore = recompositions
        progress.clear()
        Snapshot.withMutableSnapshot { requested = false }
        repeat(40) { frame() }

        val held = progress.filter { it.first - closedAt < MimirMotion.OverlayDissolveCoverMs - 16 }
        assertTrue("there are frames in the dissolve window", held.size >= 5)
        assertTrue("the glass does not start to clear while the dropdown dissolves: $held", held.all { it.second == 1f })

        val timeline = progress.map { (it.first - closedAt) to it.second }
        val gone = progress.firstOrNull { it.second == 0f }
        assertTrue("and it is gone: $timeline", gone != null)
        val goneAfter = gone!!.first - closedAt
        assertTrue("not before the hold and one clear ($goneAfter ms): $timeline", goneAfter >= MimirMotion.OverlayDissolveCoverMs + MimirMotion.OverlayMs)
        // The request lands on a frame, the hold starts on the next, the clear's frame beat follows the hold: seven frames of beats around the two clocks.
        assertTrue("and soon after them ($goneAfter ms): $timeline", goneAfter <= MimirMotion.OverlayDissolveCoverMs + MimirMotion.OverlayMs + 7 * 16)
        assertTrue("and it stays gone: $timeline", progress.filter { it.first >= gone.first }.all { it.second == 0f })

        val clearing = progress.filter { it.second in 0.01f..0.99f }
        assertTrue("it clears through intermediate frames, not a cut", clearing.isNotEmpty())
        assertTrue("on the glass clock, falling", clearing.map { it.second }.zipWithNext().all { (a, b) -> b <= a })

        // The page never reads the progress in composition: closing the dropdown recomposes it for the request and the hold, not per frame.
        val recomposed = recompositions - recompositionsBefore
        assertTrue("the root recomposed $recomposed times over 40 frames", recomposed <= 4)
    }

    @Test
    fun `a dropdown reopened inside the hold keeps the glass whole`() {
        var requested by mutableStateOf(true)
        val progress = mutableListOf<Float>()
        var glassNow: () -> Float = { error("not composed") }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val held = rememberHomeCustomEnrichGlassHold(requested = requested)
            val glass = rememberMimirGlassProgress(visible = held, identity = "page")
            glassNow = glass
            Box(Modifier.fillMaxSize())
        }
        fun frame() {
            this.frame()
            progress += glassNow()
        }
        repeat(18) { frame() }
        Snapshot.withMutableSnapshot { requested = false }
        repeat(3) { frame() } // 48 ms into a 130 ms hold
        Snapshot.withMutableSnapshot { requested = true }
        progress.clear()
        repeat(30) { frame() }
        assertTrue("the glass never dipped: ${progress.min()}", progress.all { it == 1f })
    }

    @Test
    fun `the page reads its glass from the hold and draws it as a progress, with no raw blur and no animated radius`() {
        val picker = source("HomeCustomEnrichPicker.kt")
        val overlay = picker.substringAfter("internal fun HomeCustomEnrichPickerOverlay(").substringBefore("\n}\n")
        assertTrue(overlay.contains("val filterGlassHeld = rememberHomeCustomEnrichGlassHold(requested = filterDropdownOpen)"))
        assertTrue(overlay.contains("val filterGlass = rememberMimirGlassProgress(visible = filterGlassHeld)"))
        assertFalse("the glass is not keyed on the session: a type change builds a new one, and the frost would be reborn at 0 on the pick", overlay.contains("rememberMimirGlassProgress(visible = filterGlassHeld, identity"))
        assertTrue("and the body swaps behind it while it is held", overlay.contains("behindGlass = filterGlassHeld,"))
        assertTrue(overlay.contains(".mimirGlassBackdropProgress { filterGlass() }"))
        assertFalse("no animated radius recomposing the page per frame", picker.contains("animateDpAsState"))
        assertFalse("no raw blur", picker.contains(".blur("))
        assertFalse("and no layer toggled on the radius", overlay.contains("filterBlurRadius"))
        val hold = source("EnrichPickerArt.kt").substringAfter("internal fun rememberHomeCustomEnrichGlassHold(").substringBefore("\n}\n")
        assertTrue("the hold is on the animation clock", hold.contains("mimirHold(holdMs)"))
        assertTrue("and is the specified 130 ms", hold.contains("holdMs: Int = MimirMotion.OverlayDissolveCoverMs"))
    }

    @Test
    fun `the strips follow focus on the focus clock, not the default spring`() {
        val picker = source("HomeCustomEnrichPicker.kt")
        val menu = source("HomeTileMenuOverlay.kt")
        val tween = "tween(MimirMotion.FocusMs, easing = MimirMotion.Easing)"
        val calls = Regex("""animateScrollTo\(""").findAll(picker).map { it.range.first }.toList()
        assertEquals("the chip strip and the gallery strip", 2, calls.size)
        calls.forEach { at ->
            assertTrue("every follow in the picker is on FocusMs", picker.substring(at, minOf(picker.length, at + 900)).contains(tween))
        }
        assertTrue(picker.contains("scroll.animateScrollTo(targetPx, $tween)"))
        assertTrue(menu.contains("scroll.animateScrollTo(target, $tween)"))
    }

    @Test
    fun `the painters fade on the focus clock and the house curve and nothing else`() {
        val art = source("EnrichPickerArt.kt")
        // The product's ONE cold-art rule (Q13, unified in the art-fade pass): the painter takes its spec, its first-show alpha and its warm test from core-ui, and names no clock of its own.
        assertFalse("no clock of the painter's own", art.contains("EnrichArtColdFadeInMs") || art.contains("coldFadeMs"))
        assertTrue("a cold first show and a swap fade on the one spec (FocusMs, the house curve)", art.contains("layer.alpha.animateTo(1f, MimirColdArtRevealSpec)"))
        assertTrue("and so does the art that leaves", art.contains("layer.alpha.animateTo(0f, MimirColdArtRevealSpec)"))
        assertFalse("no raw clock in the painter", Regex("""tween\(\s*\d""").containsMatchIn(art))
        assertFalse("and no private tween", art.contains("tween("))
        assertTrue("a first show is born by the core rule", art.contains("mimirColdArtStartAlpha(warm, reduced)"))
        assertTrue("a warm first show is asked of the shared cache and not faded", art.contains("mimirMemoryCacheWarm(imageLoader, mimirCoverCacheKey(model))"))
        assertTrue("a state is the model's only when its request was for it", art.contains("mimirArtLanded(state, request)"))
    }

    // ---- RC-50: the scope --------------------------------------------------------------------------------------------------------

    @Test
    fun `the picker pushes its scope once and lets go once`() {
        val authority = DefaultFocusAuthority()
        authority.pushScope(HomeShelfScope)
        val hold = HomeCustomEnrichScopeHold(authority)

        hold.acquire()
        assertEquals(HomeCustomEnrichScope, authority.state.value.scope)

        hold.release() // the commit that takes the page away
        assertEquals("the shelf has its scope back on the commit, not when the fade ends", HomeShelfScope, authority.state.value.scope)

        hold.release() // the page is disposed when the dissolve ends
        assertEquals("a second release changes nothing", HomeShelfScope, authority.state.value.scope)
    }

    @Test
    fun `a page that let go does not discard the scope a re-entered page owns`() {
        val authority = DefaultFocusAuthority()
        authority.pushScope(HomeShelfScope)
        val old = HomeCustomEnrichScopeHold(authority)
        old.acquire()
        old.release() // committed away, still dissolving

        val reentered = HomeCustomEnrichScopeHold(authority)
        reentered.acquire() // the player opens the page again

        old.release() // the first page is finally disposed
        assertEquals("the new page keeps its scope", HomeCustomEnrichScope, authority.state.value.scope)

        reentered.release()
        assertEquals(HomeShelfScope, authority.state.value.scope)
    }

    @Test
    fun `a page that let go and then stayed takes its scope back, and an ordinary page does nothing extra`() {
        val authority = DefaultFocusAuthority()
        authority.pushScope(HomeShelfScope)
        val hold = HomeCustomEnrichScopeHold(authority)

        hold.reacquire() // nothing was released: no second push
        assertEquals(HomeShelfScope, authority.state.value.scope)

        hold.acquire()
        hold.reacquire()
        assertEquals("still one copy", HomeCustomEnrichScope, authority.state.value.scope)
        hold.release()
        assertEquals(HomeShelfScope, authority.state.value.scope)

        hold.reacquire() // released, then the dissolve was reversed
        assertEquals(HomeCustomEnrichScope, authority.state.value.scope)
        hold.release()
        assertEquals("one pop undoes it", HomeShelfScope, authority.state.value.scope)
        assertNotEquals(HomeCustomEnrichScope, authority.state.value.scope)
    }

    @Test
    fun `the page is told it is leaving by its own content transition, and the scope follows`() {
        val menu = source("HomeTileMenuOverlay.kt")
        assertTrue(menu.contains("leaving = transition.targetState != EnterExitState.Visible,"))
        val picker = source("HomeCustomEnrichPicker.kt")
        assertTrue(picker.contains("    leaving: Boolean = false,\n) {"))
        assertTrue(picker.contains("if (leaving) scopeHold.release() else scopeHold.reacquire()"))
        assertTrue("the dispose goes through the same hold", picker.contains("scopeHold.release()\n            registered.forEach(authority::unregisterVirtual)"))
    }
}

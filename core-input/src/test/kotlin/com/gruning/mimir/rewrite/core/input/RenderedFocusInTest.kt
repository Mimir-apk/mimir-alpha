package com.gruning.mimir.rewrite.core.input

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [FocusAuthority.renderedFocusIn] / [FocusAuthority.isRenderedFocusIn] :
 * a covered surface must keep painting its OWN scope's last live key, not go ringless, the instant
 * another scope becomes active. Differential-verified against the naive `takeIf { it.scope == scope }
 * ?.focused ?: rememberedFocusIn(scope)` shape, which passes every case below except
 * [activeScopeWithNoFocusReturnsNullNotAStaleRememberedKey] -- see that test for why the branch form is
 * required instead.
 */
class RenderedFocusInTest {

    private fun box(l: Float, t: Float): FocusBounds = FocusBounds(l, t, l + 100f, t + 100f)

    /** Bare-bones double exercising only the [FocusAuthority] interface's DEFAULT members -- in
     * particular the default `rememberedFocusIn` that always returns null, matching any test double
     * or future implementation that never overrides it (FocusAuthority.kt:142's contract). */
    private class NullMemoryFocusAuthority(private val focused: FocusKey?, private val scope: FocusScope) :
        FocusAuthority {
        override val state: StateFlow<FocusState> = MutableStateFlow(FocusState(scope, focused))
        override fun register(key: FocusKey, scope: FocusScope, bounds: FocusBounds) = Unit
        override fun unregister(key: FocusKey) = Unit
        override fun requestFocus(key: FocusKey) = Unit
        override fun moveFocus(direction: ControllerAction): Boolean = false
        override fun pushScope(scope: FocusScope) = Unit
        override fun popScope() = Unit
        // rememberedFocusIn deliberately NOT overridden -- exercises the interface default (null).
    }

    @Test
    fun activeScopeReturnsTheLiveFocusedKey() {
        val fa = DefaultFocusAuthority()
        val shelf = FocusScope("shelf")
        val tile = FocusKey("shelf/tile")
        fa.pushScope(shelf)
        fa.register(tile, shelf, box(0f, 0f))
        fa.requestFocus(tile)

        assertEquals(tile, fa.renderedFocusIn(shelf, fa.state.value))
        assertTrue(fa.isRenderedFocusIn(shelf, tile, fa.state.value))
    }

    @Test
    fun activeScopeWithNoFocusReturnsNullNotAStaleRememberedKey() {
        // The bug the takeIf/elvis shape would reintroduce: a scope can be ACTIVE with nothing focused
        // (post-clearFocus, mid ring-swap) while its OWN focusMemory entry still holds an older key
        // (DefaultFocusAuthority.setFocus only updates focusMemory on a non-null key, and clearFocus
        // parks a sentinel rather than erasing it). The active scope's TRUTH is "nothing is focused" --
        // resurrecting the remembered key here would paint a ring around a tile the authority just
        // disowned.
        val fa = DefaultFocusAuthority()
        val shelf = FocusScope("shelf")
        val tile = FocusKey("shelf/tile")
        fa.pushScope(shelf)
        fa.register(tile, shelf, box(0f, 0f))
        fa.requestFocus(tile)
        assertEquals(tile, fa.rememberedFocusIn(shelf)) // memory still holds it...
        fa.clearFocus()

        assertNull("active+null must render as null, not fall back to memory", fa.renderedFocusIn(shelf, fa.state.value))
        assertFalse(fa.isRenderedFocusIn(shelf, tile, fa.state.value))
    }

    @Test
    fun inactiveScopeReturnsItsRememberedKey() {
        val fa = DefaultFocusAuthority()
        val shelf = FocusScope("shelf")
        val overlay = FocusScope("utility/brightness")
        val tile = FocusKey("shelf/tile")
        fa.pushScope(shelf)
        fa.register(tile, shelf, box(0f, 0f))
        fa.requestFocus(tile)

        fa.pushScope(overlay) // Utility opens over the shelf; shelf stays composed underneath.

        assertEquals("shelf scope inactive now", overlay, fa.state.value.scope)
        assertEquals(tile, fa.renderedFocusIn(shelf, fa.state.value))
        assertTrue(fa.isRenderedFocusIn(shelf, tile, fa.state.value))
    }

    @Test
    fun inactiveScopeWithNoMemoryReturnsNull() {
        val fa = DefaultFocusAuthority()
        val neverVisited = FocusScope("never/visited")
        fa.pushScope(FocusScope("root/other"))

        assertNull(fa.renderedFocusIn(neverVisited, fa.state.value))
    }

    @Test
    fun pushThenPopRoundTripLeavesTheConservedKeyUnchangedThroughout() {
        // The exact reported flow: focus a shelf tile, push Utility, assert the shelf's OWN render
        // truth never moved, pop back, assert it is STILL the same key (self-healing on return too).
        val fa = DefaultFocusAuthority()
        val shelf = FocusScope("shelf")
        val utility = FocusScope("utility/brightness")
        val tileA = FocusKey("shelf/a")
        val tileB = FocusKey("shelf/b")
        fa.pushScope(shelf)
        fa.register(tileA, shelf, box(0f, 0f))
        fa.register(tileB, shelf, box(200f, 0f))
        fa.requestFocus(tileA)

        assertTrue(fa.isRenderedFocusIn(shelf, tileA, fa.state.value))

        fa.pushScope(utility)
        assertTrue("no animation trigger: the shelf's rendered key must be byte-identical across the push",
            fa.isRenderedFocusIn(shelf, tileA, fa.state.value))
        assertFalse(fa.isRenderedFocusIn(shelf, tileB, fa.state.value))

        fa.popScope()
        assertEquals(shelf, fa.state.value.scope)
        assertTrue(fa.isRenderedFocusIn(shelf, tileA, fa.state.value))
    }

    @Test
    fun clearedFocusSentinelNeverMatchesARealKeyOnceCovered() {
        // clearFocus() parks an internal sentinel as the scope's remembered key (DefaultFocusAuthority,
        // "no card matches the sentinel"). A surface covered right after a clear must show NO ring, not
        // crash and not accidentally match a real key.
        val fa = DefaultFocusAuthority()
        val shelf = FocusScope("shelf")
        val tile = FocusKey("shelf/tile")
        fa.pushScope(shelf)
        fa.register(tile, shelf, box(0f, 0f))
        fa.requestFocus(tile)
        fa.clearFocus()

        fa.pushScope(FocusScope("utility/brightness"))

        assertFalse(fa.isRenderedFocusIn(shelf, tile, fa.state.value))
    }

    @Test
    fun nullReturningAuthorityDegradesToNoRingRatherThanCrashing() {
        // FocusAuthority.kt:142's interface default (rememberedFocusIn -> null) must be safe to build on
        // directly -- a test double or a future implementation that never overrides it must not crash.
        val scope = FocusScope("shelf")
        val other = FocusScope("utility/brightness")
        val fake = NullMemoryFocusAuthority(focused = FocusKey("shelf/tile"), scope = other)

        assertNull(fake.renderedFocusIn(scope, fake.state.value))
        assertFalse(fake.isRenderedFocusIn(scope, FocusKey("shelf/tile"), fake.state.value))
    }
}

package com.gruning.mimir.rewrite.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [FocusAuthority.litFocusIn] / [FocusAuthority.isLitFocusIn] — the RING channel of the two-channel
 * focus law documented on [FocusAuthority.renderedFocusIn].
 *
 * Focus has two channels: the ring goes dark until return, while the focused tile and remembered
 * authority state remain stable. One predicate formerly drove both channels, keeping the ring
 * visible when moving from tiles to the dock and producing a ghost trail on device.
 *
 * So the contract these tests defend is the OPPOSITE of [RenderedFocusInTest]'s, on purpose, and the two
 * files are meant to be read as a pair. The load-bearing pin is
 * [inactiveScopeIsDarkEvenWhenItsRememberedKeyExists]: it is the one that goes red the moment somebody
 * "fixes" this helper into the conserved form while chasing the snap bug, which is exactly how the ghost
 * trail would come back.
 */
class LitFocusInTest {

    private fun box(l: Float, t: Float): FocusBounds = FocusBounds(l, t, l + 100f, t + 100f)

    @Test
    fun activeScopeLightsTheLiveFocusedKey() {
        val fa = DefaultFocusAuthority()
        val shelf = FocusScope("shelf")
        val tile = FocusKey("shelf/tile")
        fa.pushScope(shelf)
        fa.register(tile, shelf, box(0f, 0f))
        fa.requestFocus(tile)

        assertEquals(tile, fa.litFocusIn(shelf, fa.state.value))
        assertTrue(fa.isLitFocusIn(shelf, tile, fa.state.value))
    }

    @Test
    fun inactiveScopeIsDarkEvenWhenItsRememberedKeyExists() {
        val fa = DefaultFocusAuthority()
        val shelf = FocusScope("shelf")
        val dock = FocusScope("shelf/dock")
        val tile = FocusKey("shelf/tile")
        val seat = FocusKey("shelf/dock/files")
        fa.pushScope(shelf)
        fa.register(tile, shelf, box(0f, 0f))
        fa.requestFocus(tile)
        // Focus drops into the dock, exactly as NAV_DOWN off a shelf tile does.
        fa.pushScope(dock)
        fa.register(seat, dock, box(0f, 200f))
        fa.requestFocus(seat)

        // The authority still remembers the shelf's tile -- that is the whole point of focus memory, and
        // the conserved channel reads it. The RING channel must not.
        assertEquals(tile, fa.rememberedFocusIn(shelf))
        assertEquals(tile, fa.renderedFocusIn(shelf, fa.state.value))
        assertNull(
            "a scope that does not own input must paint no ring, remembered key or not -- this is the " +
                "ghost trail caused by the shelf tile keeping its ring in the dock",
            fa.litFocusIn(shelf, fa.state.value),
        )
        assertFalse(fa.isLitFocusIn(shelf, tile, fa.state.value))
        // ...and the dock, which DOES own input, is lit.
        assertTrue(fa.isLitFocusIn(dock, seat, fa.state.value))
    }

    @Test
    fun theOtherDirection_theDockGoesDarkOnceFocusIsBackOnTheShelf() {
        val fa = DefaultFocusAuthority()
        val shelf = FocusScope("shelf")
        val dock = FocusScope("shelf/dock")
        val tile = FocusKey("shelf/tile")
        val seat = FocusKey("shelf/dock/files")
        fa.pushScope(shelf)
        fa.register(tile, shelf, box(0f, 0f))
        fa.requestFocus(tile)
        fa.pushScope(dock)
        fa.register(seat, dock, box(0f, 200f))
        fa.requestFocus(seat)
        fa.popScope()

        assertEquals(shelf, fa.state.value.scope)
        assertEquals(tile, fa.litFocusIn(shelf, fa.state.value))
        assertNull(
            "the dock remembers its seat, but visible chrome that no longer owns input must not stay lit " +
                "-- \"focus ring now doesn't go on his own when moving from tiles to dock\", in reverse",
            fa.litFocusIn(dock, fa.state.value),
        )
        assertEquals(
            "focus memory itself is untouched by the ring channel -- the dock still knows where to land",
            seat,
            fa.rememberedFocusIn(dock),
        )
    }

    @Test
    fun activeScopeWithNoFocusIsDark() {
        val fa = DefaultFocusAuthority()
        val shelf = FocusScope("shelf")
        fa.pushScope(shelf)

        assertNull(fa.litFocusIn(shelf, fa.state.value))
        assertFalse(fa.isLitFocusIn(shelf, FocusKey("shelf/tile"), fa.state.value))
    }

    @Test
    fun isLitFocusInMatchesOnlyTheOneLiveKey() {
        val fa = DefaultFocusAuthority()
        val shelf = FocusScope("shelf")
        val first = FocusKey("shelf/one")
        val second = FocusKey("shelf/two")
        fa.pushScope(shelf)
        fa.register(first, shelf, box(0f, 0f))
        fa.register(second, shelf, box(200f, 0f))
        fa.requestFocus(second)

        assertTrue(fa.isLitFocusIn(shelf, second, fa.state.value))
        assertFalse(fa.isLitFocusIn(shelf, first, fa.state.value))
    }

    @Test
    fun bothChannelsAgreeWhileTheScopeIsActiveAndDivergeOnlyUnderAPush() {
        val fa = DefaultFocusAuthority()
        val shelf = FocusScope("shelf")
        val overlay = FocusScope("utility")
        val tile = FocusKey("shelf/tile")
        val row = FocusKey("utility/brightness")
        fa.pushScope(shelf)
        fa.register(tile, shelf, box(0f, 0f))
        fa.requestFocus(tile)

        // Active: one truth, two names.
        assertEquals(fa.renderedFocusIn(shelf, fa.state.value), fa.litFocusIn(shelf, fa.state.value))

        // Covered by Utility: the tile keeps its pose (conserved) and loses its ring (lit). This single
        // assertion pair IS the policy -- both clauses of it, at once.
        fa.pushScope(overlay)
        fa.register(row, overlay, box(0f, 400f))
        fa.requestFocus(row)
        assertEquals(tile, fa.renderedFocusIn(shelf, fa.state.value))
        assertNull(fa.litFocusIn(shelf, fa.state.value))

        // And on return both agree again, on the same tile -- "until return".
        fa.popScope()
        assertEquals(tile, fa.renderedFocusIn(shelf, fa.state.value))
        assertEquals(tile, fa.litFocusIn(shelf, fa.state.value))
    }
}

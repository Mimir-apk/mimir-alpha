package com.gruning.mimir.rewrite

import androidx.compose.runtime.MonotonicFrameClock
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [landFocus] gives up after eight frames, and when it does, the target it was chasing goes with it.
 *
 * Run 9's trace (2026-09-18), in order: UP from the top row of the Tome asked for the search field. The field
 * was zero wide and had never registered, so the authority refused and the ring stayed on the game. The
 * target stayed pending through all eight landing frames and after them. [onboardingLogicalFocus] prefers a
 * pending target over the authority's focus, so from then on the dispatch acted from a key the ring never
 * showed, and A opened the search keyboard instead of marking the ringed game.
 *
 * The layout fix removed that particular unregistered key. These tests pin the mechanism for any key that
 * never arrives, on any surface that lands through [landFocus]. They also pin the two cases the fix must
 * not break: a target that arrives within the budget, and a newer request made while the old one was being
 * chased.
 */
class OnboardingLandFocusGiveUpTest {

    /** Hands out frames immediately, calling [onFrameNumber] with 1, 2, 3... before each one. */
    private class CountingFrameClock(private val onFrameNumber: (Int) -> Unit = {}) : MonotonicFrameClock {
        var frames = 0
            private set

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            frames += 1
            onFrameNumber(frames)
            return onFrame(frames * 16_666_667L)
        }
    }

    private val surface = OnboardingFocusSurface.FAVORITES
    private val game = onboardingFavoriteGameKey("a")
    private val search = OnboardingFavoritesSearchKey
    private val filter = OnboardingFavoritesPlatformFilterKey
    private val keys = listOf(search, filter, game)

    /** An authority holding only the game, focused on it: run 9's state, with the head keys unregistered. */
    private fun onTheGame() = DefaultFocusAuthority().apply {
        pushScope(OnboardingScope)
        register(game, OnboardingScope, FocusBounds(0f, 60f, 75f, 164f))
        requestFocus(game)
    }

    @Test
    fun `a target that never registers is dropped when the landing gives up`() {
        val authority = onTheGame()
        val focusIntent = OnboardingFocusIntentState()
        requestOnboardingFocus(authority, focusIntent, surface, search)
        assertEquals("precondition: the authority refused the unregistered key", game, authority.state.value.focused)
        assertEquals("precondition: and the target stayed pending", search, focusIntent.pendingTarget(surface, keys))

        val clock = CountingFrameClock()
        runBlocking(clock) { authority.landFocus(focusIntent, surface, entryFocus = game, validKeys = keys) }

        assertEquals("the whole budget was spent trying", 8, clock.frames)
        assertNull("nothing is pending once the landing gives up", focusIntent.pendingTarget(surface, keys))
        assertEquals(
            "so the dispatch acts from the key the ring is on",
            game,
            onboardingLogicalFocus(
                keys = keys,
                authorityFocus = authority.state.value.focused,
                entryFocus = game,
                pendingFocus = focusIntent.pendingTarget(surface, keys),
            ),
        )
    }

    @Test
    fun `a target that registers within the budget still lands`() {
        val authority = onTheGame()
        val focusIntent = OnboardingFocusIntentState()
        requestOnboardingFocus(authority, focusIntent, surface, search)

        // The ordinary late case: the arriving element registers its bounds a few frames on.
        val clock = CountingFrameClock { frame ->
            if (frame == 3) authority.register(search, OnboardingScope, FocusBounds(0f, 0f, 160f, 48f))
        }
        runBlocking(clock) { authority.landFocus(focusIntent, surface, entryFocus = game, validKeys = keys) }

        assertEquals("it landed on the frame the field arrived, not after the budget", 3, clock.frames)
        assertEquals(search, authority.state.value.focused)
        assertNull("and the target was consumed by landing, not dropped", focusIntent.pendingTarget(surface, keys))
    }

    @Test
    fun `a newer request made during the chase is not dropped with the old one`() {
        val authority = onTheGame()
        val focusIntent = OnboardingFocusIntentState()
        requestOnboardingFocus(authority, focusIntent, surface, search)

        // On the last frame the player presses again, and asks for a key that has not registered yet either.
        // That bump restarts the caller's effect, so the new target is the NEXT run's to chase.
        val clock = CountingFrameClock { frame ->
            if (frame == 8) focusIntent.deferTarget(surface, filter)
        }
        runBlocking(clock) { authority.landFocus(focusIntent, surface, entryFocus = game, validKeys = keys) }

        assertEquals(
            "the request made during the chase survives the give-up",
            filter,
            focusIntent.pendingTarget(surface, keys),
        )
    }
}

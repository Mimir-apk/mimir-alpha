package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *
 *
 * Measured on the Odin the same day (APK sha
 * 73df9d10): the carousel paints the CORRECT remembered tile at +42-65ms, holds it for 7-23ms, and is then
 * retargeted by a SECOND focus event nobody asked for. Departed lane on tile 1 -> 11 of 17 switches landed
 * wrong; departed lane on tile 0 -> 0 of 4. The glide that follows matched its own constants to within 2ms,
 * so `CoverflowMotion.kt` was never the defect -- it was faithfully animating a wrong target.
 *
 * The second event is [com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority]'s geometric fallback.
 * [virtualFocusRowSlots] derives bounds from the index alone, so tile N of two different platforms occupies
 * identical bounds and the fallback re-homes focus INDEX-PRESERVING into a different platform's games.
 * [resolvePendingGamelistLanding] then reads that silent move as a deliberate one and drops its correction.
 */
class GamelistRingSwapFocusTest {

    private val playstation = PlatformId("playstation")
    /** Real pixel bounds, like the composed media chip -- deliberately NOT the synthetic index geometry. */
    private val pillBounds = FocusBounds(left = 860f, top = 980f, right = 1060f, bottom = 1040f)
    private val aKeys = List(8) { FocusKey("game/gamecube/$it") }
    private val bKeys = List(3) { FocusKey("game/playstation/$it") }

    private fun authorityFocusedOn(keys: List<FocusKey>, index: Int): Pair<FocusAuthority, VirtualFocusRegistrationTracker> {
        val authority = DefaultFocusAuthority()
        authority.pushScope(GamelistScope)
        val tracker = VirtualFocusRegistrationTracker()
        tracker.update(authority, GamelistScope, virtualFocusRowSlots(keys))
        authority.requestFocus(keys[index])
        assertEquals(keys[index], authority.state.value.focused)
        return authority to tracker
    }

    // ── The predicate ──

    @Test
    fun `a platform switch replaces identity, a moving window does not`() {
        assertTrue(virtualFocusReplacesIdentity(aKeys.toSet(), bKeys.toSet()))
        // A window that slides still overlaps what it replaces -- that overlap is what makes
        // register-before-unregister safe, and it must NOT be treated as a swap.
        assertFalse(virtualFocusReplacesIdentity(aKeys.take(6).toSet(), aKeys.drop(2).toSet()))
        // One shared key is enough to be a window, not a swap.
        assertFalse(virtualFocusReplacesIdentity(setOf(aKeys[0]), setOf(aKeys[0], bKeys[0])))
        // First registration, and teardown, are neither.
        assertFalse(virtualFocusReplacesIdentity(emptySet(), bKeys.toSet()))
        assertFalse(virtualFocusReplacesIdentity(aKeys.toSet(), emptySet()))
    }

    // ── The defect, through the real authority ──

    @Test
    fun `without the opt-in, a ring swap re-homes focus onto the same index in the new platform`() {
        val (authority, tracker) = authorityFocusedOn(aKeys, index = 1)

        tracker.update(authority, GamelistScope, virtualFocusRowSlots(bKeys))

        // Bounds are index-derived, so PlayStation's tile 1 sits at distance 0 from GameCube's tile 1.
        assertEquals(
            "characterizes the defect: focus silently moves to the same INDEX in a different platform",
            bKeys[1],
            authority.state.value.focused,
        )
    }

    @Test
    fun `the same wrong key then suppresses the landing correction - except at index zero`() {
        val remembered = bKeys[2]
        val pending = PendingGamelistLanding(platformId = playstation, rememberedFocus = remembered)

        // Focus re-homed to index 1 (not `first`) reads as a deliberate user landing, so the correction
        // is dropped and the carousel is left pointing at the wrong game. This is the 11-of-17 case.
        val suppressed = resolvePendingGamelistLanding(
            pending = pending,
            openPlatform = playstation,
            gamesLoadedForPlatform = playstation,
            gameKeys = bKeys,
            focused = bKeys[1],
        )
        assertNull("the defect: a silent re-home is indistinguishable from a user move", suppressed.target)

        // Re-homed to index 0 it EQUALS `first`, so the correction survives -- which is exactly why the
        // device tally showed 0 of 4 stale when the departed lane sat on tile 0.
        val survivesAtZero = resolvePendingGamelistLanding(
            pending = pending,
            openPlatform = playstation,
            gamesLoadedForPlatform = playstation,
            gameKeys = bKeys,
            focused = bKeys[0],
        )
        assertEquals(remembered, survivesAtZero.target)

        // Cleared focus is the path that already measured correct with zero tile travel.
        val cleared = resolvePendingGamelistLanding(
            pending = pending,
            openPlatform = playstation,
            gamesLoadedForPlatform = playstation,
            gameKeys = bKeys,
            focused = null,
        )
        assertEquals(remembered, cleared.target)
    }

    // ── The fix ──

    @Test
    fun `with the opt-in, a ring swap leaves focus for the landing effect to place`() {
        val (authority, tracker) = authorityFocusedOn(aKeys, index = 1)

        tracker.update(
            authority = authority,
            scope = GamelistScope,
            slots = virtualFocusRowSlots(bKeys),
            clearsFocusOnIdentityReplacement = true,
        )

        assertNull(
            "no key from the departed platform may hand focus to a key of the arriving one",
            authority.state.value.focused,
        )
    }

    @Test
    fun `the cleared ring does not let a re-registering card grab focus`() {
        val (authority, tracker) = authorityFocusedOn(aKeys, index = 4)
        tracker.update(authority, GamelistScope, virtualFocusRowSlots(bKeys), clearsFocusOnIdentityReplacement = true)
        assertNull(authority.state.value.focused)

        // A later window update inside the SAME platform must not auto-seed tile 0 behind the landing effect.
        tracker.update(authority, GamelistScope, virtualFocusRowSlots(bKeys), clearsFocusOnIdentityReplacement = true)
        assertNull(authority.state.value.focused)
    }

    @Test
    fun `a swap taken from the media pill also clears, so the auto-seed cannot land first`() {
        val authority = DefaultFocusAuthority()
        authority.pushScope(GamelistScope)
        val tracker = VirtualFocusRegistrationTracker()
        tracker.update(authority, GamelistScope, virtualFocusRowSlots(aKeys))
        // Stand on a ring tile first: `setFocus` stamps focusMemory, which is ONE slot for the whole scope.
        authority.requestFocus(aKeys[1])
        // Then move to the platform-invariant media pill and switch from there.
        authority.registerVirtual(GamelistGetArtKey, GamelistScope, pillBounds)
        authority.requestFocus(GamelistGetArtKey)
        assertEquals(GamelistGetArtKey, authority.state.value.focused)

        tracker.update(
            authority = authority,
            scope = GamelistScope,
            slots = virtualFocusRowSlots(bKeys),
            clearsFocusOnIdentityReplacement = true,
        )

        // The pill is never in the tracker's registered set, so a `focused in registeredKeys` test misses it.
        assertNull(
            "focus stale across the swap must be cleared even when it is not a ring key",
            authority.state.value.focused,
        )
        // And the cleared sentinel must survive re-registration, or `maybeSeedFocusOnRegister` hands focus
        // to the scope's remembered key the moment it comes back -- which is the defect, not the fix.
        tracker.update(
            authority = authority,
            scope = GamelistScope,
            slots = virtualFocusRowSlots(aKeys),
            clearsFocusOnIdentityReplacement = true,
        )
        assertNull(authority.state.value.focused)
    }

    // ── No regression: the fallback that IS correct must survive ──

    @Test
    fun `a sliding window keeps its focused key`() {
        val (authority, tracker) = authorityFocusedOn(aKeys, index = 3)

        tracker.update(
            authority = authority,
            scope = GamelistScope,
            slots = virtualFocusRowSlots(aKeys.drop(2)),
            clearsFocusOnIdentityReplacement = true,
        )

        assertEquals(aKeys[3], authority.state.value.focused)
    }

    @Test
    fun `a single live removal still gets its geometric fallback`() {
        val (authority, tracker) = authorityFocusedOn(aKeys, index = 3)

        // One game removed from an otherwise-stable ring: focus must land on a sibling, never null.
        val survivors = aKeys.filterIndexed { index, _ -> index != 3 }
        tracker.update(
            authority = authority,
            scope = GamelistScope,
            slots = virtualFocusRowSlots(survivors),
            clearsFocusOnIdentityReplacement = true,
        )

        val landed = authority.state.value.focused
        assertTrue("a removed tile must not strand focus: $landed", landed != null && landed in survivors)
    }
}

/**
 * The second door into the same travel, found by a code trace and left open by the
 * ring-swap fix: a switch taken while the Enrich/Hero pill holds focus.
 *
 * `GamelistGetArtKey` is platform-INVARIANT, so it survives L2/R2 as a key while its meaning does not --
 * `switchOpenPlatform` nulls the `detailGame` it was acting on (`LibraryViewModel.kt`), and
 * `switchGamelistPlatform` deliberately leaves authority focus on it (`LibraryDispatch.kt`). The tracker's
 * identity-replacement guard cannot help here: the pill is never in its registered key set, so no geometric
 * fallback fires and nothing is cleared. The damage is in what PAINTS -- centring the pill centres nothing,
 * `GamelistCarousel` falls to `focusedIndex = 0`, and the coverflow glides from tile 0 to the real landing.
 */
class GamelistPillSwitchCentreTest {

    private val keys = List(8) { FocusKey("game/gamecube/$it") }
    private val remembered = keys[4]

    private fun rendered(platformLandingPending: Boolean, rememberedFocus: FocusKey?) =
        gamelistRenderedFocus(
            gameKeys = keys,
            focused = GamelistGetArtKey,
            scopeOwnsGamelist = true,
            rememberedFocus = rememberedFocus,
            pendingRememberedFocus = remembered,
            retainedGameFocus = null,
            platformLandingPending = platformLandingPending,
        )

    @Test
    fun `a switch taken from the pill paints the destination's remembered tile, not the pill`() {
        // The scope's remembered key can BE the pill: setFocus stamps focusMemory on every focus change.
        assertEquals(remembered, rendered(platformLandingPending = true, rememberedFocus = GamelistGetArtKey))
        assertEquals(remembered, rendered(platformLandingPending = true, rememberedFocus = null))
    }

    @Test
    fun `outside a landing the pill still owns the centre`() {
        // Standing on the pill inside one platform must keep its game centred -- without this the carousel
        // would snap to index 0 the instant the artwork action is reached.
        assertEquals(GamelistGetArtKey, rendered(platformLandingPending = false, rememberedFocus = null))
        assertEquals(
            GamelistGetArtKey,
            rendered(platformLandingPending = false, rememberedFocus = GamelistGetArtKey),
        )
    }

    @Test
    fun `a pill switch into a never-visited platform still lands on tile one`() {
        val landing = gamelistRenderedFocus(
            gameKeys = keys,
            focused = GamelistGetArtKey,
            scopeOwnsGamelist = true,
            rememberedFocus = GamelistGetArtKey,
            pendingRememberedFocus = null,
            retainedGameFocus = null,
            platformLandingPending = true,
        )
        assertEquals(keys.first(), landing)
    }
}

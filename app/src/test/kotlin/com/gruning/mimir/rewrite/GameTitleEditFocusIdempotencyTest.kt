package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusBinding
import com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusScope
import com.gruning.mimir.rewrite.core.input.MimirKeyboardInitialKeyId
import com.gruning.mimir.rewrite.core.input.createMimirKeyboardFocusBindingForTests
import com.gruning.mimir.rewrite.core.input.mimirKeyboardFocusKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Piece 4. Opening the overlay always
 * lands on the same element; closing and reopening returns to the same place; a direction with nowhere to
 * go leaves focus exactly where it was; re-entering after save/cancel/reset never lands somewhere
 * different from a fresh open.
 *
 * `rememberMimirKeyboardHost` (`MimirKeyboardHost.kt`) creates a BRAND NEW `MimirKeyboardFocusBinding` via
 * `remember(authority, scope, initialKeyId)` scoped to the SAME `if (active == GAME_TITLE_EDIT) { ... }`
 * block that composes the whole overlay — closing the overlay (`gameTitleEdit = null`) unmounts that
 * block, tearing the remembered binding down; the next open remounts it fresh with `entered = false`.
 * These tests model that exact shape: a NEW `MimirKeyboardFocusBinding` instance per "open", sharing the
 * SAME long-lived `FocusAuthority` a real app session would (the authority persists across opens; only
 * the binding is fresh) — never a fake/mock authority, so the transitions are proven against the real
 * production geometry/seeding engine.
 */
class GameTitleEditFocusIdempotencyTest {

    private fun freshBinding(authority: DefaultFocusAuthority): MimirKeyboardFocusBinding =
        createMimirKeyboardFocusBindingForTests(authority)

    private fun registerKeyboardGridAndReset(authority: DefaultFocusAuthority, registerResetFirst: Boolean) {
        val register = {
            authority.register(GameTitleEditResetKey, MimirKeyboardFocusScope, FocusBounds(140f, 200f, 220f, 230f))
        }
        if (registerResetFirst) register()
        authority.register(
            mimirKeyboardFocusKey(MimirKeyboardInitialKeyId),
            MimirKeyboardFocusScope,
            FocusBounds(300f, 450f, 340f, 490f),
        )
        if (!registerResetFirst) register()
    }

    /**
     * Mirrors what `Modifier.mimirFocusable`'s own `DisposableEffect(key, scope) { onDispose {
     * authority.unregister(key) } }` does for EVERY registered node when Compose actually tears the
     * overlay's composition down on close — never left implicit, since a test that forgot this step would
     * pass by accident (stale entries from the first open coincidentally still being registered) instead
     * of proving the real "fresh remount, fresh registration" shape.
     */
    private fun unregisterKeyboardGridAndReset(authority: DefaultFocusAuthority, hadReset: Boolean) {
        authority.unregister(mimirKeyboardFocusKey(MimirKeyboardInitialKeyId))
        if (hadReset) authority.unregister(GameTitleEditResetKey)
    }

    // ── Transition 1: a fresh open always lands on the same element (Done) ──────────────────────────

    @Test
    fun freshOpenLandsOnDoneEvenWhenResetRegistersBeforeTheDoneKeyDoes() {
        val authority = DefaultFocusAuthority()
        val binding = freshBinding(authority)

        binding.enter()
        registerKeyboardGridAndReset(authority, registerResetFirst = true)
        binding.reconcileFocus(setOf(MimirKeyboardInitialKeyId))

        assertEquals(mimirKeyboardFocusKey(MimirKeyboardInitialKeyId), authority.state.value.focused)
    }

    @Test
    fun freshOpenLandsOnDoneWhenTheDoneKeyRegistersFirst() {
        val authority = DefaultFocusAuthority()
        val binding = freshBinding(authority)

        binding.enter()
        registerKeyboardGridAndReset(authority, registerResetFirst = false)
        binding.reconcileFocus(setOf(MimirKeyboardInitialKeyId))

        assertEquals(mimirKeyboardFocusKey(MimirKeyboardInitialKeyId), authority.state.value.focused)
    }

    // ── Transitions 2-4: closing (via cancel / save / reset) and reopening returns to the same place ──

    @Test
    fun reopeningAfterCancelLandsOnDoneAgainNotWhereFocusWasLeft() {
        val authority = DefaultFocusAuthority()
        val firstOpen = freshBinding(authority)
        firstOpen.enter()
        registerKeyboardGridAndReset(authority, registerResetFirst = false)
        firstOpen.reconcileFocus(setOf(MimirKeyboardInitialKeyId))
        authority.requestFocus(GameTitleEditResetKey) // player navigated to Reset before cancelling
        firstOpen.exit() // Back/scrim -> onCancel -> closeGameTitleEdit() unmounts the overlay
        unregisterKeyboardGridAndReset(authority, hadReset = true)

        // Reopen: a NEW binding instance with a FRESH registration, matching a real remount.
        val secondOpen = freshBinding(authority)
        secondOpen.enter()
        registerKeyboardGridAndReset(authority, registerResetFirst = false)
        secondOpen.reconcileFocus(setOf(MimirKeyboardInitialKeyId))

        assertEquals(mimirKeyboardFocusKey(MimirKeyboardInitialKeyId), authority.state.value.focused)
    }

    @Test
    fun reopeningAfterSaveLandsOnDoneAgainNotWhereFocusWasLeft() {
        val authority = DefaultFocusAuthority()
        val firstOpen = freshBinding(authority)
        firstOpen.enter()
        registerKeyboardGridAndReset(authority, registerResetFirst = false)
        firstOpen.reconcileFocus(setOf(MimirKeyboardInitialKeyId))
        // Done itself was focused when Save fired -- the common case, still exercised explicitly.
        authority.requestFocus(mimirKeyboardFocusKey(MimirKeyboardInitialKeyId))
        firstOpen.exit() // onSave -> closeGameTitleEdit() unmounts the overlay
        unregisterKeyboardGridAndReset(authority, hadReset = true)

        val secondOpen = freshBinding(authority)
        secondOpen.enter()
        registerKeyboardGridAndReset(authority, registerResetFirst = false)
        secondOpen.reconcileFocus(setOf(MimirKeyboardInitialKeyId))

        assertEquals(mimirKeyboardFocusKey(MimirKeyboardInitialKeyId), authority.state.value.focused)
    }

    @Test
    fun reopeningAfterResetLandsOnDoneAgainEvenThoughResetKeyWasFocusedWhenItFired() {
        val authority = DefaultFocusAuthority()
        val firstOpen = freshBinding(authority)
        firstOpen.enter()
        registerKeyboardGridAndReset(authority, registerResetFirst = false)
        firstOpen.reconcileFocus(setOf(MimirKeyboardInitialKeyId))
        authority.requestFocus(GameTitleEditResetKey) // Reset itself was focused when CONFIRM fired it
        firstOpen.exit() // onReset -> resetGameTitleOverride() -> closeGameTitleEdit() unmounts the overlay
        unregisterKeyboardGridAndReset(authority, hadReset = true)

        // Reopen on the SAME game (hasOverride is now false post-reset, so Reset never registers this time).
        val secondOpen = freshBinding(authority)
        secondOpen.enter()
        authority.register(
            mimirKeyboardFocusKey(MimirKeyboardInitialKeyId),
            MimirKeyboardFocusScope,
            FocusBounds(300f, 450f, 340f, 490f),
        )
        secondOpen.reconcileFocus(setOf(MimirKeyboardInitialKeyId))

        assertEquals(mimirKeyboardFocusKey(MimirKeyboardInitialKeyId), authority.state.value.focused)
        assertFalse(authority.registeredKeysInScope(MimirKeyboardFocusScope).contains(GameTitleEditResetKey))
    }

    // ── Transition 5: re-entering the SAME binding without exiting is a no-op, not a second seed ──────

    @Test
    fun callingEnterTwiceWithoutExitingIsIdempotent() {
        val authority = DefaultFocusAuthority()
        val binding = freshBinding(authority)
        binding.enter()
        registerKeyboardGridAndReset(authority, registerResetFirst = false)
        binding.reconcileFocus(setOf(MimirKeyboardInitialKeyId))
        authority.requestFocus(GameTitleEditResetKey)

        binding.enter() // guarded by MimirKeyboardFocusBinding's own `if (entered) return`

        assertEquals(GameTitleEditResetKey, authority.state.value.focused)
    }

    // ── Transition 6: a direction with nowhere to go leaves focus exactly where it was ────────────────

    @Test
    fun repeatedNavUpFromResetStaysExactlyOnResetAcrossMultiplePresses() {
        val authority = DefaultFocusAuthority()
        val binding = freshBinding(authority)
        binding.enter()
        registerKeyboardGridAndReset(authority, registerResetFirst = false)
        binding.reconcileFocus(setOf(MimirKeyboardInitialKeyId))
        authority.requestFocus(GameTitleEditResetKey)

        // Not just one press: repeated presses into a genuine dead end must not accumulate any drift.
        repeat(3) {
            val moved = authority.moveFocus(ControllerAction.NAV_UP)
            assertFalse(moved)
            assertEquals(GameTitleEditResetKey, authority.state.value.focused)
        }
    }

    /**
     * Discovered while writing this test, worth stating plainly rather than silently asserting a "dead
     * end" that isn't real: [DefaultFocusAuthority.inDirection] scores LEFT/RIGHT purely on horizontal
     * projection, with no vertical gate — so with the FULL number row registered (unlike the two-key
     * setup the other transitions in this file use), LEFT/RIGHT from Reset are NOT dead ends, because the
     * row has keys on both sides of Reset's centerX. They resolve deterministically to the nearest row-1
     * key on that side, via the identical closest-neighbor scoring proven in
     * `GameTitleEditResetFocusGraphTest`. Idempotent (repeatable, provable) is not the same claim as
     * "never moves" — this pins the actual behavior rather than an assumption about it.
     */
    @Test
    fun horizontalPressesFromResetAreDeterministicNotStranded() {
        val authority = DefaultFocusAuthority()
        val binding = freshBinding(authority)
        binding.enter()
        val numRowKeys = (0..9).map { com.gruning.mimir.rewrite.core.input.FocusKey("keyboard/char/$it") }
        numRowKeys.forEachIndexed { index, key ->
            val left = index * 36f
            authority.register(key, MimirKeyboardFocusScope, FocusBounds(left, 250f, left + 32f, 290f))
        }
        authority.register(GameTitleEditResetKey, MimirKeyboardFocusScope, FocusBounds(140f, 200f, 220f, 230f))
        authority.requestFocus(GameTitleEditResetKey)

        val movedLeft = authority.moveFocus(ControllerAction.NAV_LEFT)
        assertTrue(movedLeft)
        assertEquals(numRowKeys[4], authority.state.value.focused)

        authority.requestFocus(GameTitleEditResetKey)
        val movedRight = authority.moveFocus(ControllerAction.NAV_RIGHT)
        assertTrue(movedRight)
        assertEquals(numRowKeys[5], authority.state.value.focused)
    }
}

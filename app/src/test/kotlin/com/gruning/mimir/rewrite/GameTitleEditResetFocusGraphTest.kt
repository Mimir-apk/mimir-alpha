package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Piece 3/4. Proves the ONE new edge [GameTitleEditResetKey] adds to
 * [MimirKeyboardFocusScope]'s existing graph, using [DefaultFocusAuthority] — the REAL production
 * geometry engine, not a fake — with a schematic five-row keyboard layout that mirrors the actual Compose
 * tree order (Reset composes ABOVE `MimirKeyboard` in the same parent `Column`, so its window Y is
 * ALWAYS less than every key's, by construction, not by measured coincidence).
 *
 * What this pins vs. what it does NOT claim: the mechanism (Reset is the sole node above the panel, so
 * any top-row key's NAV_UP reaches it; NAV_DOWN resolves through the SAME closest-neighbor scoring every
 * other key already uses) is proven exactly. The bounds below are representative/schematic, not measured
 * real device pixels — which top-row key NAV_DOWN lands on in the shipping app is a device-layout fact,
 * owed to hardware, not claimed here.
 */
class GameTitleEditResetFocusGraphTest {

    private val numRowKeys = (0..9).map { FocusKey("keyboard/char/$it") }
    private val doneKey = FocusKey("keyboard/action/done")

    /** Schematic five-row keyboard + Reset above it, matching the real Compose vertical order. */
    private fun seedKeyboardWithReset(authority: DefaultFocusAuthority, includeReset: Boolean) {
        authority.pushScope(MimirKeyboardFocusScope)
        if (includeReset) {
            authority.register(GameTitleEditResetKey, MimirKeyboardFocusScope, FocusBounds(140f, 200f, 220f, 230f))
        }
        // Row 1 (numbers) — the keyboard's own topmost row, ten equal-weight keys.
        numRowKeys.forEachIndexed { index, key ->
            val left = index * 36f
            authority.register(key, MimirKeyboardFocusScope, FocusBounds(left, 250f, left + 32f, 290f))
        }
        // Rows 2-5 (qwerty / asdf / shift+back / symbols+paste+space+done) — only their Y matters here;
        // the NEW edge under test is entirely about row 1 <-> Reset, not these rows' own internal nav
        // (pre-existing, unmodified, already shipped for every MimirKeyboard host).
        authority.register(FocusKey("keyboard/char/q"), MimirKeyboardFocusScope, FocusBounds(0f, 300f, 32f, 340f))
        authority.register(FocusKey("keyboard/char/a"), MimirKeyboardFocusScope, FocusBounds(0f, 350f, 32f, 390f))
        authority.register(FocusKey("keyboard/action/shift"), MimirKeyboardFocusScope, FocusBounds(0f, 400f, 32f, 440f))
        authority.register(doneKey, MimirKeyboardFocusScope, FocusBounds(300f, 450f, 340f, 490f))
    }

    // ── WITH an override (Reset present) ──────────────────────────────────────────────────────────

    @Test
    fun everyTopRowKeyReachesResetWithOneNavUp() {
        listOf(numRowKeys.first(), numRowKeys[5], numRowKeys.last()).forEach { startKey ->
            val authority = DefaultFocusAuthority()
            seedKeyboardWithReset(authority, includeReset = true)
            authority.requestFocus(startKey)

            val moved = authority.moveFocus(ControllerAction.NAV_UP)

            assertTrue("NAV_UP from ${startKey.value} should reach Reset", moved)
            assertEquals(GameTitleEditResetKey, authority.state.value.focused)
        }
    }

    @Test
    fun navUpFromResetIsADeadEndAndLeavesFocusExactlyWhereItWas() {
        val authority = DefaultFocusAuthority()
        seedKeyboardWithReset(authority, includeReset = true)
        authority.requestFocus(GameTitleEditResetKey)

        val moved = authority.moveFocus(ControllerAction.NAV_UP)

        assertFalse(moved)
        assertEquals(GameTitleEditResetKey, authority.state.value.focused)
    }

    @Test
    fun navDownFromResetAlwaysResolvesToARealRegisteredKeyboardKeyNeverADeadEnd() {
        val authority = DefaultFocusAuthority()
        seedKeyboardWithReset(authority, includeReset = true)
        authority.requestFocus(GameTitleEditResetKey)

        val moved = authority.moveFocus(ControllerAction.NAV_DOWN)

        assertTrue(moved)
        val landed = authority.state.value.focused
        assertTrue(
            "Reset's NAV_DOWN must land on a real key in scope, was $landed",
            landed != null && authority.registeredKeysInScope(MimirKeyboardFocusScope).contains(landed),
        )
        // Deterministic for THIS schematic geometry (Reset centered over the row, row 1 is the nearest
        // row below it): key index 5 has the smallest horizontal offset from Reset's own centerX among
        // every registered key, so it wins the same closest-neighbor score every other key already uses.
        // Confirms the mechanism, not a device-measured claim about the real shipping layout.
        assertEquals(numRowKeys[5], landed)
    }

    // ── WITHOUT an override (Reset absent) — absence must not strand focus or invent a dead end ─────

    @Test
    fun withNoOverrideTopRowNavUpIsTheSameDeadEndAsBeforePiece3() {
        val authority = DefaultFocusAuthority()
        seedKeyboardWithReset(authority, includeReset = false)
        authority.requestFocus(numRowKeys[5])

        val moved = authority.moveFocus(ControllerAction.NAV_UP)

        assertFalse(moved)
        assertEquals(numRowKeys[5], authority.state.value.focused)
        assertFalse(authority.registeredKeysInScope(MimirKeyboardFocusScope).contains(GameTitleEditResetKey))
    }

    @Test
    fun withNoOverrideTheResetKeyNeverAppearsInTheSceneGraph() {
        val authority = DefaultFocusAuthority()
        seedKeyboardWithReset(authority, includeReset = false)

        assertFalse(authority.registeredKeysInScope(MimirKeyboardFocusScope).contains(GameTitleEditResetKey))
    }
}

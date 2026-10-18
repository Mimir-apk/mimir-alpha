package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Returning from Settings via START then Back could land without focus. The mechanism has
 * three silent failure paths:
 *  - `pushScope` lands **null** focus when the destination's ring has not registered yet
 *  - `requestFocus` is a **silent no-op** while its key is unregistered
 *  - `maybeSeedFocusOnRegister` only ever seeds the scope's *remembered* key — the very key that
 *    never registered
 *
 * `moveFocus` then returns `false` on its first line, so the D-pad is dead on a screen that looks
 * ready. The rune cover drops regardless, because the return request clears without checking that
 * anything actually got focus.
 *
 * **What these prove:** the fallback policy is correct under `DefaultFocusAuthority`'s real
 * semantics, and the null-focus window is genuinely reachable. **What they do not prove:** that the
 * reported START→Back route hits this on the reference device, or that the rune stops flashing. Neither is
 * certifiable off-device.
 */
class SettingsReturnAuthorityLandingTest {

    private val scope = FocusScope("library-shelf")
    private fun key(name: String) = FocusKey(name)
    private fun bounds(top: Float) = FocusBounds(left = 0f, top = top, right = 100f, bottom = top + 50f)

    // ── the pure policy ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a return that already landed is never overridden`() {
        assertNull(
            "a fallback able to override a correct landing would reintroduce UI-02's 'moves to the " +
                "first tile and then cancels' jitter -- the same defect class this lane just removed",
            settingsReturnFallbackFocusKey(
                focused = key("tile-3"),
                registeredInScope = listOf(key("tile-1"), key("tile-3")),
                remembered = key("tile-1"),
            ),
        )
    }

    @Test
    fun `a registered remembered key wins, so the player lands where they were`() {
        assertEquals(
            key("tile-7"),
            settingsReturnFallbackFocusKey(
                focused = null,
                registeredInScope = listOf(key("tile-1"), key("tile-7")),
                remembered = key("tile-7"),
            ),
        )
    }

    @Test
    fun `an unregistered remembered key falls back to a real element -- the defect case`() {
        assertEquals(
            "this is BUG#01 itself: the remembered target never registered (hidden, filtered or " +
                "removed while Settings owned the screen), so focus must land on something real " +
                "rather than stay null and leave the D-pad dead",
            key("tile-1"),
            settingsReturnFallbackFocusKey(
                focused = null,
                registeredInScope = listOf(key("tile-1"), key("tile-2")),
                remembered = key("gone"),
            ),
        )
    }

    @Test
    fun `an empty scope yields null rather than a lie`() {
        assertNull(
            settingsReturnFallbackFocusKey(focused = null, registeredInScope = emptyList(), remembered = null),
        )
    }

    // ── the real authority, reproducing the window ───────────────────────────────────────────────

    @Test
    fun `the null-focus window is real, and the fallback closes it`() {
        val authority = DefaultFocusAuthority()

        // A scope pushed before its ring has laid out registers nothing.
        authority.pushScope(scope)
        assertNull("pushScope lands null focus when nothing is registered", authority.state.value.focused)
        assertFalse(
            "moveFocus returns false on its first line when nothing is focused -- THIS is the dead " +
                "reported inert D-pad",
            authority.moveFocus(ControllerAction.NAV_DOWN),
        )

        // The ring lays out a frame later.
        authority.register(key("tile-1"), scope, bounds(0f))
        authority.register(key("tile-2"), scope, bounds(60f))

        authority.landSettingsReturnFocusFallback()

        assertEquals(key("tile-1"), authority.state.value.focused)
        assertTrue(
            "with focus landed, the D-pad works again",
            authority.moveFocus(ControllerAction.NAV_DOWN),
        )
    }

    @Test
    fun `a remembered key that never registers still lands the player somewhere real`() {
        val authority = DefaultFocusAuthority()
        authority.register(key("tile-1"), scope, bounds(0f))
        authority.register(key("tile-2"), scope, bounds(60f))
        authority.pushScope(scope)
        authority.requestFocus(key("tile-2"))
        authority.rememberFocusForScope(scope, key("tile-2"))

        // The player leaves for Settings; tile-2 is filtered out while they are away.
        authority.unregister(key("tile-2"))
        authority.clearFocus()

        authority.landSettingsReturnFocusFallback()

        assertEquals(
            "the remembered key is gone, so the fallback must reach for a registered one",
            key("tile-1"),
            authority.state.value.focused,
        )
    }
}

/**
 * The CALL SITE, not the policy.
 *
 * The behavioural tests above pin `settingsReturnFallbackFocusKey` and
 * `landSettingsReturnFocusFallback`, and neutering either fails them loudly. But deleting the single
 * line that *invokes* the fallback in `MimirNavigationCoordinator` failed nothing — verified by
 * differential. That is the exact "a test still passes when its subject is removed" hole this tree has
 * hit three times today, so the invocation gets its own pin.
 *
 * A source-text pin rather than a behavioural one because `app` has no Compose or Robolectric harness:
 * the effect it lives in cannot be driven from a JVM test. This is the module's established fallback
 * idiom (see `MimirSettingsReturnPathRaceTest`). It proves the call exists and sits AFTER the retry
 * loop and BEFORE the request is cleared — it proves nothing about how the screen looks.
 */
class SettingsReturnFallbackWiringTest {

    private val source = java.io.File(
        "src/main/kotlin/com/gruning/mimir/rewrite/MimirNavigationCoordinator.kt",
    ).readText()

    @Test
    fun `the settings return invokes the focus fallback before it clears the request`() {
        val call = source.indexOf("authority.landSettingsReturnFocusFallback()")
        assertTrue(
            "MimirNavigationCoordinator must invoke landSettingsReturnFocusFallback(). Without it the " +
                "return can finish with the active scope owning nothing, the rune cover drops, and the " +
                "D-pad is inert on a screen that looks ready -- reported on device, 2026-08-14.",
            call >= 0,
        )
        val finish = source.indexOf("navigation.finishSettingsLibraryReturn()", call)
        assertTrue(
            "the fallback must run BEFORE finishSettingsLibraryReturn(): clearing that request is what " +
                "drops the rune cover and hands the screen to the player, so landing focus afterwards " +
                "would be too late",
            finish > call,
        )
    }
}

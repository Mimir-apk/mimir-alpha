package com.gruning.mimir.rewrite.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [FocusAuthority.clearFocus] parks a private sentinel as the scope's remembered focus so that
 * `maybeSeedFocusOnRegister` cannot hand focus to a re-registering card. That sentinel is an internal
 * marker — its own KDoc says it *"matches no real element"* — and it was being handed straight back out
 * through [FocusAuthority.rememberedFocusIn].
 *
 * Most callers survived that by accident: they guard with `takeIf { it in someKeys }`, and the sentinel is
 * in no key set. The one that did not is `LibraryScreen`'s `rememberedGamelistFocusOnShelf`, which SNAPSHOTS
 * the value on the way out to the shelf and carries it into the next gamelist open. A sentinel snapshot
 * degrades that landing to tile one, silently.
 *
 * The window is real and got wider on 2026-08-27: the gamelist's registration tracker now calls
 * `clearFocus` on every platform switch, and focus stays null until the landing effect resolves — which
 * waits on the destination's Room query. Leaving to the shelf inside that beat takes the sentinel with it.
 */
class ClearedFocusSentinelLeakTest {

    private val scope = FocusScope("test/scope")
    private val a = FocusKey("a")
    private val b = FocusKey("b")
    private val bounds = FocusBounds(left = 0f, top = 0f, right = 100f, bottom = 100f)

    private fun authorityWithFocusOnA(): DefaultFocusAuthority {
        val authority = DefaultFocusAuthority()
        authority.pushScope(scope)
        authority.registerVirtual(a, scope, bounds)
        authority.registerVirtual(b, scope, bounds)
        authority.requestFocus(a)
        assertEquals(a, authority.state.value.focused)
        return authority
    }

    @Test
    fun `a cleared scope remembers NOTHING, not a sentinel`() {
        val authority = authorityWithFocusOnA()
        assertEquals(a, authority.rememberedFocusIn(scope))

        authority.clearFocus()

        assertNull("focus itself is cleared", authority.state.value.focused)
        assertNull(
            "a caller that SNAPSHOTS this value must not carry an internal marker forward",
            authority.rememberedFocusIn(scope),
        )
    }

    @Test
    fun `clearing still suppresses the auto-seed, which is what the sentinel is for`() {
        val authority = authorityWithFocusOnA()
        authority.clearFocus()
        assertNull(authority.state.value.focused)

        // The ring re-registers, exactly as it does on the far side of a platform switch. Nothing may
        // grab focus here -- that auto-seed is the defect the gamelist ring-swap fix closed, and filtering
        // the PUBLIC accessor must not weaken the PRIVATE marker that blocks it.
        authority.registerVirtual(a, scope, bounds)
        authority.registerVirtual(b, scope, bounds)
        assertNull("re-registration must not seed focus into a deliberately cleared scope", authority.state.value.focused)
    }

    @Test
    fun `a real focus after clearing is remembered again`() {
        val authority = authorityWithFocusOnA()
        authority.clearFocus()
        authority.requestFocus(b)
        assertEquals(b, authority.state.value.focused)
        assertEquals("the sentinel must not outlive the next real landing", b, authority.rememberedFocusIn(scope))
    }
}

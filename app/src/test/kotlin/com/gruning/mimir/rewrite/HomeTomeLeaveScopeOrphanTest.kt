package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaving a Home Tome scope must remove it, not merely decline to.
 *
 * `leaveHomeTomeScope` used to be `if (authority.state.value.scope != scope) return false` followed by a
 * pop — the guarded-pop shape that was retired for `MimirKeyboardFocus` on 2026-09-06 for exactly this
 * reason. When the Folio scope was NOT the active one, nothing was popped and the scope stayed on the
 * stack forever.
 *
 * That orphan is not hypothetical bookkeeping: two of the four call sites
 * (`HomeShelfCoordinator.kt:2494` and `:2705`) discard the return value entirely, so a failed leave was
 * invisible and the close continued. The orphaned Folio scope is then the anomalous scope that makes a
 * later `enterHomeTomeScope` return false — the origin of the return-path defect the PR 171 review
 * found at `MimirAppInputDispatch.kt:205`, where the rollback was the symptom and this is the cause.
 *
 * The authority now offers `discardScope`, which removes a buried scope with no state emission, so the
 * function can be total.
 */
class HomeTomeLeaveScopeOrphanTest {

    private val covering = FocusScope("test/covering-overlay")

    @Test
    fun `leaving the Folio scope removes it even when another scope covers it`() {
        val authority = DefaultFocusAuthority()
        authority.pushScope(HomeShelfScope)
        authority.pushScope(HomeTomeFolioScope)
        // A surface opened over the Folio — Utility, Brightness, a launch card. Its scope is now active.
        authority.pushScope(covering)
        assertEquals(covering, authority.state.value.scope)

        leaveHomeTomeScope(authority, HomeTomeFolioScope)

        // The covering surface is untouched: leaving the Folio must not disturb whatever is on top.
        assertEquals(covering, authority.state.value.scope)
        // And when that surface closes, focus must land on the shelf — NOT back in a Folio scope whose
        // surface is long gone.
        authority.popScope()
        assertEquals(
            "the Folio scope was orphaned on the stack; a later enterHomeTomeScope will refuse",
            HomeShelfScope,
            authority.state.value.scope,
        )
    }

    @Test
    fun `leaving the active Folio scope still reports landing on the shelf`() {
        val authority = DefaultFocusAuthority()
        authority.pushScope(HomeShelfScope)
        authority.pushScope(HomeTomeFolioScope)

        assertTrue(leaveHomeTomeScope(authority, HomeTomeFolioScope))
        assertEquals(HomeShelfScope, authority.state.value.scope)
    }

    @Test
    fun `leaving a scope that is not on the stack changes nothing and says so`() {
        val authority = DefaultFocusAuthority()
        authority.pushScope(HomeShelfScope)

        // The contract is "the scope is off the stack and focus is on the shelf", so a scope that was
        // never there satisfies it vacuously. What matters at this call is the second assertion: a
        // leave for an absent scope must not pop anything in its name.
        assertTrue(leaveHomeTomeScope(authority, HomeTomeFolioScope))
        assertEquals(
            "nothing may be popped in the name of a scope that was never pushed",
            HomeShelfScope,
            authority.state.value.scope,
        )
    }
}

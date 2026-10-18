package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.feature.library.GamelistScope
import com.gruning.mimir.rewrite.feature.library.LibraryShelfScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupOriginScopeTest {
    @Test
    fun waitsForLibraryShelfBeforeRestoringGamelistScope() {
        val authority = DefaultFocusAuthority()

        assertFalse(restoreStartupGamelistScopeIfReady(authority))
        assertEquals("root", authority.state.value.scope.value)

        authority.pushScope(LibraryShelfScope)
        assertTrue(restoreStartupGamelistScopeIfReady(authority))
        assertEquals(GamelistScope, authority.state.value.scope)
    }

    @Test
    fun alreadyRestoredGamelistScopeIsIdempotent() {
        val authority = DefaultFocusAuthority()
        authority.pushScope(LibraryShelfScope)
        authority.pushScope(GamelistScope)

        assertTrue(restoreStartupGamelistScopeIfReady(authority))
        assertEquals(GamelistScope, authority.state.value.scope)
    }
}

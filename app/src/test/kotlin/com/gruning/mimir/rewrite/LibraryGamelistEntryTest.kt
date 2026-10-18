package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.feature.library.GameDetailScope
import com.gruning.mimir.rewrite.feature.library.GamelistScope
import com.gruning.mimir.rewrite.feature.library.LibraryShelfScope
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryGamelistEntryTest {
    @Test
    fun `mounted shelf enters game scope so a visible card can acquire focus`() {
        val authority = DefaultFocusAuthority()
        authority.pushScope(LibraryShelfScope)
        enterMountedLibraryGamelist(authority, MimirDestination.LIBRARY)
        val card = FocusKey("game/sonic")
        authority.registerVirtual(card, GamelistScope, FocusBounds(0f, 0f, 100f, 100f))
        authority.requestFocus(card)
        assertEquals(GamelistScope, authority.state.value.scope)
        assertEquals(card, authority.state.value.focused)
        authority.popScope()
        assertEquals(LibraryShelfScope, authority.state.value.scope)
    }

    @Test
    fun `repeated mounted entry does not duplicate the game scope`() {
        val authority = DefaultFocusAuthority()
        authority.pushScope(LibraryShelfScope)
        repeat(3) { enterMountedLibraryGamelist(authority, MimirDestination.LIBRARY) }
        assertEquals(GamelistScope, authority.state.value.scope)
        authority.popScope()
        assertEquals(LibraryShelfScope, authority.state.value.scope)
        authority.popScope()
        assertEquals(FocusScope.Root, authority.state.value.scope)
    }

    @Test
    fun `cross destination entry leaves focus to the incoming Library mount`() {
        val authority = DefaultFocusAuthority()
        val outgoing = FocusScope("browse")
        authority.pushScope(outgoing)
        enterMountedLibraryGamelist(authority, MimirDestination.BROWSE)
        assertEquals(outgoing, authority.state.value.scope)
        authority.popScope()
        assertEquals(FocusScope.Root, authority.state.value.scope)
    }

    @Test
    fun `a retained shelf scope alone does not authorize changing another destination`() {
        val authority = DefaultFocusAuthority()
        authority.pushScope(LibraryShelfScope)
        enterMountedLibraryGamelist(authority, MimirDestination.SETTINGS)
        assertEquals(LibraryShelfScope, authority.state.value.scope)
    }

    @Test
    fun `an active detail keeps its scope and return path`() {
        val authority = DefaultFocusAuthority()
        authority.pushScope(LibraryShelfScope)
        authority.pushScope(GamelistScope)
        authority.pushScope(GameDetailScope)
        enterMountedLibraryGamelist(authority, MimirDestination.LIBRARY)
        assertEquals(GameDetailScope, authority.state.value.scope)
        authority.popScope()
        assertEquals(GamelistScope, authority.state.value.scope)
    }
}

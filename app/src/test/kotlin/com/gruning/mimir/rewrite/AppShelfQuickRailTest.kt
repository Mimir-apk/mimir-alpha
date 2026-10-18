package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailAction
import com.gruning.mimir.rewrite.feature.library.LibraryShelfScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShelfQuickRailTest {
    @Test
    fun `down enters rail and up restores exact library hero`() {
        val authority = authorityOnLibraryHero()

        assertTrue(dispatchLibraryQuickRail(authority, ControllerAction.NAV_DOWN) {})
        assertEquals(LibraryQuickRailScope, authority.state.value.scope)
        assertEquals(
            libraryQuickRailKey(MimirQuickRailAction.LIBRARY),
            authority.state.value.focused,
        )

        assertTrue(dispatchLibraryQuickRail(authority, ControllerAction.NAV_RIGHT) {})
        assertEquals(
            libraryQuickRailKey(MimirQuickRailAction.BRIGHTNESS),
            authority.state.value.focused,
        )
        assertTrue(dispatchLibraryQuickRail(authority, ControllerAction.NAV_UP) {})
        assertEquals(LibraryShelfScope, authority.state.value.scope)
        assertEquals(HeroKey, authority.state.value.focused)
    }

    @Test
    fun `rapid reversal is synchronous and confirm invokes one action`() {
        val authority = authorityOnLibraryHero()
        dispatchFullRail(authority, ControllerAction.NAV_DOWN)
        repeat(40) {
            dispatchFullRail(authority, ControllerAction.NAV_RIGHT)
            dispatchFullRail(authority, ControllerAction.NAV_LEFT)
        }
        assertEquals(
            libraryQuickRailKey(MimirQuickRailAction.LIBRARY),
            authority.state.value.focused,
        )

        dispatchFullRail(authority, ControllerAction.NAV_LEFT)
        var activated: MimirQuickRailAction? = null
        assertTrue(
            dispatchFullRail(authority, ControllerAction.CONFIRM) { activated = it },
        )
        assertEquals(MimirQuickRailAction.HOME, activated)
    }

    @Test
    fun `down from the add pill does not enter the rail so the reducer can return to the tiles`() {
        val authority = DefaultFocusAuthority().apply {
            pushScope(LibraryShelfScope)
            // A tile exists: the ring is not empty, so the pill-to-tile rule below still applies.
            registerVirtual(HeroKey, LibraryShelfScope, Bounds.copy(top = 200f, bottom = 264f))
            registerVirtual(AddKey, LibraryShelfScope, Bounds)
            requestFocus(AddKey)
            registerRail()
        }

        // Item 6 : the Add pill sits ABOVE the tiles, so DOWN from it must fall to the
        // centred tile (the Library reducer's job) — never jump to the dock. The rail hand-off no longer
        // claims the Add key; it returns false and leaves focus untouched so `dispatchToLibrary` takes over.
        assertFalse(dispatchLibraryQuickRail(authority, ControllerAction.NAV_DOWN) {})
        assertEquals(LibraryShelfScope, authority.state.value.scope)
        assertEquals(AddKey, authority.state.value.focused)
    }

    @Test
    fun `down from the add pill over an empty ring enters the rail on LIBRARY, and up or back return to the pill`() {
        // Cert 2026-10-08 (A2, e4-03..e4-09): with no platform tile the pill was a dead end -- DOWN found no peer
        // and focus stayed put. The shelf scope registers nothing but the pill, so the dock is the only thing below.
        listOf(ControllerAction.NAV_UP, ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK).forEach { back ->
            val authority = authorityOnEmptyRing()

            assertTrue(dispatchLibraryQuickRail(authority, ControllerAction.NAV_DOWN) {})
            assertEquals(LibraryQuickRailScope, authority.state.value.scope)
            assertEquals(libraryQuickRailKey(MimirQuickRailAction.LIBRARY), authority.state.value.focused)

            assertTrue(dispatchLibraryQuickRail(authority, back) {})
            assertEquals("$back pops to the shelf", LibraryShelfScope, authority.state.value.scope)
            assertEquals("$back returns to the pill", AddKey, authority.state.value.focused)
        }
    }

    @Test
    fun `down from a native-ring pill with a tile still falls to the tile, only a pill alone hands off to the rail`() {
        val native = FocusKey("library/native/com.example.alpha")
        val authority = DefaultFocusAuthority().apply {
            pushScope(LibraryShelfScope)
            registerVirtual(native, LibraryShelfScope, Bounds.copy(top = 200f, bottom = 264f))
            registerVirtual(AddKey, LibraryShelfScope, Bounds)
            requestFocus(AddKey)
            registerRail()
        }

        assertFalse(dispatchLibraryQuickRail(authority, ControllerAction.NAV_DOWN) {})
        assertEquals(AddKey, authority.state.value.focused)

        // The ring empties (the tile unregisters): the very same press now reaches the dock instead of dead-ending.
        authority.unregisterVirtual(native)
        assertTrue(dispatchLibraryQuickRail(authority, ControllerAction.NAV_DOWN) {})
        assertEquals(libraryQuickRailKey(MimirQuickRailAction.LIBRARY), authority.state.value.focused)
    }

    @Test
    fun `left and right on the empty-ring pill stay no-ops for the rail hand-off`() {
        val authority = authorityOnEmptyRing()

        assertFalse(dispatchLibraryQuickRail(authority, ControllerAction.NAV_LEFT) {})
        assertFalse(dispatchLibraryQuickRail(authority, ControllerAction.NAV_RIGHT) {})
        assertEquals(LibraryShelfScope, authority.state.value.scope)
        assertEquals(AddKey, authority.state.value.focused)
    }

    @Test
    fun `library rail carries quick launch to match the home dock`() {
        // Library and Home include the same Quick Launch button. Keep this list explicit so dock ordering
        // changes are deliberate and the two shelves stay aligned.
        assertEquals(
            listOf(
                MimirQuickRailAction.HOME,
                MimirQuickRailAction.LIBRARY,
                MimirQuickRailAction.BRIGHTNESS,
                MimirQuickRailAction.SETTINGS,
                MimirQuickRailAction.QUICK_LAUNCH,
                MimirQuickRailAction.FILES,
            ),
            libraryQuickRailActions(homeEnabled = true),
        )
        assertTrue(libraryQuickRailActions(homeEnabled = true).contains(MimirQuickRailAction.QUICK_LAUNCH))
        assertTrue(libraryQuickRailActions(homeEnabled = false).contains(MimirQuickRailAction.QUICK_LAUNCH))
    }

    @Test
    fun `library rail carries the FILES end-cap too, terminal position, both home states`() {
        // D-1: appended LAST so no existing action's
        // index moves; opted into the Library rail explicitly (never entries.filter -- see the comment
        // on libraryQuickRailActions above).
        assertEquals(
            MimirQuickRailAction.FILES,
            libraryQuickRailActions(homeEnabled = true).last(),
        )
        assertEquals(
            MimirQuickRailAction.FILES,
            libraryQuickRailActions(homeEnabled = false).last(),
        )
    }

    @Test
    fun `release rail omits home from rendering and controller traversal`() {
        assertEquals(
            listOf(
                MimirQuickRailAction.LIBRARY,
                MimirQuickRailAction.BRIGHTNESS,
                MimirQuickRailAction.SETTINGS,
                MimirQuickRailAction.QUICK_LAUNCH,
                MimirQuickRailAction.FILES,
            ),
            libraryQuickRailActions(homeEnabled = false),
        )

        val authority = authorityOnLibraryHero()
        dispatchLibraryQuickRail(
            authority = authority,
            action = ControllerAction.NAV_DOWN,
            homeEnabled = false,
            onAction = {},
        )
        // DOWN lands on LIBRARY (index 0); one LEFT wraps to the last entry — now FILES (D-1 appended it
        // after QUICK_LAUNCH on the release rail too), where it used to be QUICK_LAUNCH before FILES existed.
        dispatchLibraryQuickRail(
            authority = authority,
            action = ControllerAction.NAV_LEFT,
            homeEnabled = false,
            onAction = {},
        )
        var activated: MimirQuickRailAction? = null
        dispatchLibraryQuickRail(
            authority = authority,
            action = ControllerAction.CONFIRM,
            homeEnabled = false,
            onAction = { activated = it },
        )

        assertEquals(MimirQuickRailAction.FILES, activated)
        assertFalse(libraryQuickRailActions(homeEnabled = false).contains(MimirQuickRailAction.HOME))
    }

    // --- libraryQuickRailActivate: the LIBRARY toggle must never push-then-pop -----------------------
    //
    // Why these assert on the CALL SEQUENCE and not just end state: DefaultFocusAuthority's push/popScope
    // round-trip through `focusMemory`, keyed per scope (DefaultFocusAuthority.kt:161/171). A synchronous
    // push(A) immediately followed by pop() restores EXACTLY the scope and focused key that were active
    // before the push -- so a buggy "push then pop" and a correct "never push" are OBSERVATIONALLY
    // IDENTICAL in `authority.state.value` afterward. An end-state-only assertion would be VACUOUS against
    // exactly the naive-collapse defect this piece exists to kill. RecordingFocusAuthority wraps a real
    // DefaultFocusAuthority and logs every pushScope/popScope/requestFocus call, so the pin is
    // timing-independent and does not depend on StateFlow emission/collection order.

    @Test
    fun `activating LIBRARY while not in rail scope calls onAction only, never touches the rail scope`() {
        val real = authorityOnLibraryHero()
        assertEquals(LibraryShelfScope, real.state.value.scope) // sanity: confirms the cold precondition
        val recorder = RecordingFocusAuthority(real)
        var activated: MimirQuickRailAction? = null

        libraryQuickRailActivate(recorder, MimirQuickRailAction.LIBRARY) { activated = it }

        assertEquals(MimirQuickRailAction.LIBRARY, activated)
        assertEquals(LibraryShelfScope, real.state.value.scope)
        assertTrue(
            "a cold LIBRARY activation must never push or pop the rail scope -- recorded calls: " +
                "${recorder.calls}",
            recorder.calls.none { it.startsWith("pushScope") || it.startsWith("popScope") },
        )
    }

    @Test
    fun `activating LIBRARY while already in rail scope pops exactly once and never calls onAction`() {
        val real = authorityOnLibraryHero().apply { pushScope(LibraryQuickRailScope) }
        assertEquals(LibraryQuickRailScope, real.state.value.scope) // sanity: confirms the precondition
        val recorder = RecordingFocusAuthority(real)
        var activated: MimirQuickRailAction? = null

        libraryQuickRailActivate(recorder, MimirQuickRailAction.LIBRARY) { activated = it }

        assertEquals("must never fall through to onAction while closing the rail", null, activated)
        assertEquals(LibraryShelfScope, real.state.value.scope)
        assertEquals(
            "must pop exactly once and never push -- recorded calls were: ${recorder.calls}",
            listOf("popScope()"),
            recorder.calls,
        )
    }

    @Test
    fun `activating a non-LIBRARY action always defers to onAction regardless of scope`() {
        val real = authorityOnLibraryHero().apply { pushScope(LibraryQuickRailScope) }
        val recorder = RecordingFocusAuthority(real)
        var activated: MimirQuickRailAction? = null

        libraryQuickRailActivate(recorder, MimirQuickRailAction.SETTINGS) { activated = it }

        assertEquals(MimirQuickRailAction.SETTINGS, activated)
        assertTrue(
            "a non-LIBRARY activation must not itself push or pop the rail scope -- recorded calls: " +
                "${recorder.calls}",
            recorder.calls.none { it.startsWith("pushScope") || it.startsWith("popScope") },
        )
    }

    /** Delegates everything to [delegate] but logs every scope/focus mutation for timing-independent pins. */
    private class RecordingFocusAuthority(
        private val delegate: FocusAuthority,
    ) : FocusAuthority by delegate {
        val calls = mutableListOf<String>()

        override fun pushScope(scope: FocusScope) {
            calls += "pushScope($scope)"
            delegate.pushScope(scope)
        }

        override fun popScope() {
            calls += "popScope()"
            delegate.popScope()
        }

        override fun requestFocus(key: FocusKey) {
            calls += "requestFocus($key)"
            delegate.requestFocus(key)
        }
    }

    private fun authorityOnLibraryHero() = DefaultFocusAuthority().apply {
        pushScope(LibraryShelfScope)
        registerVirtual(HeroKey, LibraryShelfScope, Bounds)
        requestFocus(HeroKey)
        registerRail()
    }

    /** The empty-ring Library: the shelf scope registers the Add pill and nothing else, and the pill holds focus. */
    private fun authorityOnEmptyRing() = DefaultFocusAuthority().apply {
        pushScope(LibraryShelfScope)
        registerVirtual(AddKey, LibraryShelfScope, Bounds)
        requestFocus(AddKey)
        registerRail()
    }

    private fun DefaultFocusAuthority.registerRail() {
        MimirQuickRailAction.entries.forEachIndexed { index, action ->
            registerVirtual(
                libraryQuickRailKey(action),
                LibraryQuickRailScope,
                Bounds.copy(left = index * 80f, right = index * 80f + 64f),
            )
        }
    }

    private fun dispatchFullRail(
        authority: DefaultFocusAuthority,
        action: ControllerAction,
        onAction: (MimirQuickRailAction) -> Unit = {},
    ): Boolean = dispatchLibraryQuickRail(
        authority = authority,
        action = action,
        homeEnabled = true,
        onAction = onAction,
    )

    private companion object {
        val HeroKey = FocusKey("library/shelf/snes")
        val AddKey = FocusKey("library/shelf/add")
        val Bounds = FocusBounds(0f, 0f, 64f, 64f)
    }
}

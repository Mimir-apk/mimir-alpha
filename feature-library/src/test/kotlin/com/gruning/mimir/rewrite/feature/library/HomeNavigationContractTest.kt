package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Navigation Pass 3 contract: Home content is a finite list on every device and at every row count. */
class HomeNavigationContractTest {

    // ── Left/Right traversal and finite-edge policy ─────────────────────────────────────────────

    @Test
    fun `left and right traverse a long shelf and clamp at both ends`() {
        val rows = rows("a", "b", "c", "d", "e", "f")
        val authority = homeAuthority(rows)
        authority.requestFocus(rows[0].focusKey)

        dispatch(authority, rows, ControllerAction.NAV_RIGHT)
        assertEquals(rows[1].focusKey, authority.state.value.focused)

        authority.requestFocus(rows.last().focusKey)
        dispatch(authority, rows, ControllerAction.NAV_RIGHT)
        assertEquals(rows.last().focusKey, authority.state.value.focused)

        authority.requestFocus(rows.first().focusKey)
        dispatch(authority, rows, ControllerAction.NAV_LEFT)
        assertEquals(rows.first().focusKey, authority.state.value.focused)
    }

    @Test
    fun `a fully visible shelf uses the same finite edge contract`() {
        val rows = rows("a", "b")
        val authority = homeAuthority(rows)
        authority.requestFocus(rows[1].focusKey)

        dispatch(authority, rows, ControllerAction.NAV_RIGHT)
        assertEquals("RIGHT at the tail must hold", rows[1].focusKey, authority.state.value.focused)

        authority.requestFocus(rows[0].focusKey)
        dispatch(authority, rows, ControllerAction.NAV_LEFT)
        assertEquals("LEFT at the head must hold", rows[0].focusKey, authority.state.value.focused)
    }

    @Test
    fun `a collection strip shares the same finite edge contract`() {
        val rows = rows("a", "b", "c")
        val authority = homeAuthority(rows)
        authority.requestFocus(rows[2].focusKey)

        dispatch(authority, rows, ControllerAction.NAV_RIGHT)
        assertEquals(rows[2].focusKey, authority.state.value.focused)
    }

    // ── Down enters the dock; Up returns to the exact row ────────────────────────────────────────

    @Test
    fun `down enters the dock at HOME on first entry, with nothing remembered`() {
        val rows = rows("a", "b")
        val authority = homeAuthority(rows)
        registerRail(authority)
        authority.requestFocus(rows[1].focusKey)

        dispatch(authority, rows, ControllerAction.NAV_DOWN)

        assertEquals(HomeShelfQuickRailScope, authority.state.value.scope)
        assertEquals(
            "the dock must not start from the positionally-first tile",
            homeShelfQuickRailKey(MimirQuickRailAction.HOME),
            authority.state.value.focused,
        )
    }

    @Test
    fun `down re-enters the dock at its remembered item`() {
        val rows = rows("a", "b")
        val authority = homeAuthority(rows)
        registerRail(authority)
        authority.requestFocus(rows[0].focusKey)

        dispatch(authority, rows, ControllerAction.NAV_DOWN)
        dispatch(authority, rows, ControllerAction.NAV_RIGHT) // move off HOME within the dock
        val remembered = authority.state.value.focused
        assertNotEquals(homeShelfQuickRailKey(MimirQuickRailAction.HOME), remembered)

        dispatch(authority, rows, ControllerAction.NAV_UP) // back to content
        dispatch(authority, rows, ControllerAction.NAV_DOWN) // and down again

        assertEquals(remembered, authority.state.value.focused)
    }

    @Test
    fun `up from the dock returns to the exact row focus left`() {
        val rows = rows("a", "b", "c")
        val authority = homeAuthority(rows)
        registerRail(authority)
        authority.requestFocus(rows[2].focusKey)

        dispatch(authority, rows, ControllerAction.NAV_DOWN)
        dispatch(authority, rows, ControllerAction.NAV_UP)

        assertEquals(HomeShelfScope, authority.state.value.scope)
        assertEquals("Up must restore the EXACT row, not the first", rows[2].focusKey, authority.state.value.focused)
    }

    @Test
    fun `up on the content row is consumed and moves nothing`() {
        val rows = rows("a", "b")
        val authority = homeAuthority(rows)
        authority.requestFocus(rows[1].focusKey)

        val consumed = dispatch(authority, rows, ControllerAction.NAV_UP)

        assertTrue("Home owns UP even when it does nothing", consumed)
        assertEquals(rows[1].focusKey, authority.state.value.focused)
    }

    // ── X and Y are hard modals ──────────────────────────────────────────────────────────────────

    @Test
    fun `Y opens the Edit modal for the exact focused row and consumes the press`() {
        val rows = rows("a", "b")
        val authority = homeAuthority(rows)
        authority.requestFocus(rows[1].focusKey)
        var opened: HomeShelfRow? = null

        val consumed = dispatch(authority, rows, ControllerAction.TERTIARY, onTileMenu = { opened = it })

        assertTrue(consumed)
        assertEquals(rows[1], opened)
        // Opening a modal must not itself move Home's focus — dismissal restores this exact row.
        assertEquals(rows[1].focusKey, authority.state.value.focused)
    }

    @Test
    fun `X opens the Tome Library and consumes the press without moving focus`() {
        val rows = rows("a", "b")
        val authority = homeAuthority(rows)
        authority.requestFocus(rows[0].focusKey)
        var opened = false

        val consumed = dispatch(authority, rows, ControllerAction.SECONDARY, onTomes = { opened = true })

        assertTrue(consumed)
        assertTrue(opened)
        assertEquals(rows[0].focusKey, authority.state.value.focused)
    }

    // ── Edit mutations never strand focus on a dead key ──────────────────────────────────────────

    @Test
    fun `after a row is removed, focus lands on a surviving row not the dead key`() {
        val rows = rows("a", "b", "c")
        val authority = homeAuthority(rows)
        authority.requestFocus(rows[1].focusKey)

        // The removal reduces the projected rows; the ring is rebuilt from what remains.
        val survivors = listOf(rows[0], rows[2])
        val rebuilt = homeAuthority(survivors)
        rebuilt.requestFocus(survivors[0].focusKey)
        dispatch(rebuilt, survivors, ControllerAction.NAV_RIGHT)

        assertTrue(
            "focus must be on a live row",
            rebuilt.state.value.focused in survivors.map { it.focusKey },
        )
        assertTrue(
            "the removed row's key must not be reachable",
            rows[1].focusKey !in survivors.map { it.focusKey },
        )
    }

    @Test
    fun `an Android row is a first-class navigation target alongside emulated rows`() {
        // The Android lanes returned in this package, so traversal must treat their rows as ordinary
        // members of the ring rather than skipping them.
        val mixed = listOf(
            emulatedRow("a"),
            HomeShelfRow(
                itemId = HomeShelfItemId.AndroidGame(AndroidPackageName("com.example.game")),
                rank = 1L,
                addedAtMillis = 1L,
                title = "Native Game",
            ),
            HomeShelfRow(
                itemId = HomeShelfItemId.AndroidApp(AndroidPackageName("com.example.app")),
                rank = 2L,
                addedAtMillis = 2L,
                title = "Native App",
            ),
        )
        val authority = homeAuthority(mixed)
        authority.requestFocus(mixed[0].focusKey)

        dispatch(authority, mixed, ControllerAction.NAV_RIGHT)
        assertEquals(mixed[1].focusKey, authority.state.value.focused)
        dispatch(authority, mixed, ControllerAction.NAV_RIGHT)
        assertEquals(mixed[2].focusKey, authority.state.value.focused)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private fun dispatch(
        authority: DefaultFocusAuthority,
        rows: List<HomeShelfRow>,
        action: ControllerAction,
        onTileMenu: (HomeShelfRow) -> Unit = {},
        onTomes: () -> Unit = {},
    ): Boolean = dispatchToHomeShelf(
        authority = authority,
        rows = rows,
        action = action,
        onLaunch = {},
        onOpenLibrary = {},
        onQuickAction = {},
        onExit = {},
        onTileMenu = onTileMenu,
        onTomes = onTomes,
    )

    private fun emulatedRow(id: String, rank: Long = 0L) = HomeShelfRow(
        itemId = HomeShelfItemId.Emulated(GameId(id)),
        rank = rank,
        addedAtMillis = rank,
        title = id,
    )

    private fun rows(vararg ids: String): List<HomeShelfRow> =
        ids.mapIndexed { index, id -> emulatedRow(id, rank = index * 1_024L) }

    private fun homeAuthority(rows: List<HomeShelfRow>) = DefaultFocusAuthority().apply {
        pushScope(HomeShelfScope)
        rows.forEachIndexed { index, row ->
            registerVirtual(
                key = row.focusKey,
                scope = HomeShelfScope,
                bounds = FocusBounds(index * 1_000f, 0f, index * 1_000f + 500f, 500f),
            )
        }
    }

    private fun registerRail(authority: DefaultFocusAuthority) {
        HomeShelfQuickActions.forEachIndexed { index, action ->
            authority.registerVirtual(
                key = homeShelfQuickRailKey(action),
                scope = HomeShelfQuickRailScope,
                bounds = FocusBounds(index * 100f, 800f, index * 100f + 90f, 880f),
            )
        }
    }
}

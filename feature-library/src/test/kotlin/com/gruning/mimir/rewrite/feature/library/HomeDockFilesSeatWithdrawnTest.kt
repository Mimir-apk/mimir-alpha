package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailAction
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy R2 (2026-10-09): while a game runs the Files seat is withdrawn, from the rail, the focus order and the press alike, and it
 * comes back when the game ends. The Files entry is also offered on Home. This module cannot see
 * the session ledger, so the app passes one answer to the screen (`HomeShelfScreen.filesSeatOffered`) and to [dispatchToHomeShelf]; these
 * pin that the two agree, that only the end-cap goes, and that the drawn list is byte-for-byte the pinned [HomeShelfQuickActions] when offered.
 *
 * Lead (reclaim fix round, 2026-10-15): a withdrawn seat that stayed REGISTERED kept a dead focus (nothing lit, A dead, and a remembered
 * Files restored onto it on the next dock entry). The screen registers the seat only while it is offered, so a lit Files falls to its
 * neighbour by the authority's own unregistration, a remembered Files lands on its neighbour ([homeShelfQuickRailLanding]), and LEFT/RIGHT
 * from a seat the rail does not know go to the neighbour in the pressed direction, as on the Library dock. The benches below register what
 * the screen registers: never the withdrawn seat.
 */
class HomeDockFilesSeatWithdrawnTest {

    @Test
    fun `only the end-cap goes, and the offered rail is the pinned one`() {
        assertEquals(HomeShelfQuickActions, homeShelfQuickActions(filesSeatOffered = true))
        assertEquals(HomeShelfQuickActions.dropLast(1), homeShelfQuickActions(filesSeatOffered = false))
        assertFalse(MimirQuickRailAction.FILES in homeShelfQuickActions(filesSeatOffered = false))
        assertEquals("the five navigation seats keep their order", MimirQuickRailAction.QUICK_LAUNCH, homeShelfQuickActions(filesSeatOffered = false).last())
    }

    @Test
    fun `the D-pad travels the rail that is drawn, so a withdrawn seat is not in the ring`() {
        val rows = listOf(row("a"))
        val authority = registeredHomeAuthority(rows, filesSeatOffered = false)
        val activated = mutableListOf<MimirQuickRailAction>()

        dispatch(authority, rows, ControllerAction.NAV_DOWN, activated, filesSeatOffered = false)
        assertEquals(homeShelfQuickRailKey(MimirQuickRailAction.HOME), authority.state.value.focused)
        dispatch(authority, rows, ControllerAction.NAV_LEFT, activated, filesSeatOffered = false)
        assertEquals("wrap from HOME lands on QUICK LAUNCH while the end-cap is withdrawn", homeShelfQuickRailKey(MimirQuickRailAction.QUICK_LAUNCH), authority.state.value.focused)
        dispatch(authority, rows, ControllerAction.NAV_RIGHT, activated, filesSeatOffered = false)
        assertEquals(homeShelfQuickRailKey(MimirQuickRailAction.HOME), authority.state.value.focused)

        // The game ends: the screen registers the end-cap again, and the same wrap reaches it (HomeShelfModelsTest pins this ring with the seat offered).
        registerSeat(authority, MimirQuickRailAction.FILES)
        dispatch(authority, rows, ControllerAction.NAV_LEFT, activated, filesSeatOffered = true)
        assertEquals(homeShelfQuickRailKey(MimirQuickRailAction.FILES), authority.state.value.focused)
        assertTrue(activated.isEmpty())
    }

    @Test
    fun `a seat withdrawn under the focus is unregistered, and the focus falls to its neighbour, lit and pressable`() {
        val rows = listOf(row("a"))
        val authority = registeredHomeAuthority(rows, filesSeatOffered = true)
        val activated = mutableListOf<MimirQuickRailAction>()
        dispatch(authority, rows, ControllerAction.NAV_DOWN, activated, filesSeatOffered = true)
        dispatch(authority, rows, ControllerAction.NAV_LEFT, activated, filesSeatOffered = true)
        assertEquals("the end-cap holds the focus", homeShelfQuickRailKey(MimirQuickRailAction.FILES), authority.state.value.focused)

        // HOME parks the game while the end-cap is focused: the screen's Files modifier is a bare Modifier now and its registration is disposed.
        authority.unregister(homeShelfQuickRailKey(MimirQuickRailAction.FILES))
        assertEquals("the focus falls to the neighbour", homeShelfQuickRailKey(MimirQuickRailAction.QUICK_LAUNCH), authority.state.value.focused)
        assertFalse(homeShelfQuickRailKey(MimirQuickRailAction.FILES) in authority.registeredKeysInScope(HomeShelfQuickRailScope))

        assertTrue(dispatch(authority, rows, ControllerAction.CONFIRM, activated, filesSeatOffered = false))
        assertEquals("A works where the focus landed", listOf(MimirQuickRailAction.QUICK_LAUNCH), activated)
        dispatch(authority, rows, ControllerAction.NAV_RIGHT, activated, filesSeatOffered = false)
        assertEquals("the ring is the drawn rail", homeShelfQuickRailKey(MimirQuickRailAction.HOME), authority.state.value.focused)
    }

    @Test
    fun `a remembered Files lands on its neighbour when the dock is re-entered while the seat is withdrawn`() {
        val rows = listOf(row("a"))
        val authority = registeredHomeAuthority(rows, filesSeatOffered = true)
        val activated = mutableListOf<MimirQuickRailAction>()
        dispatch(authority, rows, ControllerAction.NAV_DOWN, activated, filesSeatOffered = true)
        dispatch(authority, rows, ControllerAction.NAV_LEFT, activated, filesSeatOffered = true)
        dispatch(authority, rows, ControllerAction.NAV_UP, activated, filesSeatOffered = true)
        assertEquals("the dock remembers the end-cap", homeShelfQuickRailKey(MimirQuickRailAction.FILES), authority.rememberedFocusIn(HomeShelfQuickRailScope))
        assertEquals(rows.single().focusKey, authority.state.value.focused)

        // A game starts: the seat is withdrawn and unregistered; DOWN lands on the neighbour, not on HOME and not on a dead seat.
        authority.unregister(homeShelfQuickRailKey(MimirQuickRailAction.FILES))
        dispatch(authority, rows, ControllerAction.NAV_DOWN, activated, filesSeatOffered = false)
        assertEquals(homeShelfQuickRailKey(MimirQuickRailAction.QUICK_LAUNCH), authority.state.value.focused)
        assertEquals(HomeShelfQuickRailScope, authority.state.value.scope)

        // Control: the same memory with the seat offered lands on the end-cap itself.
        dispatch(authority, rows, ControllerAction.NAV_LEFT, activated, filesSeatOffered = false)
        dispatch(authority, rows, ControllerAction.NAV_UP, activated, filesSeatOffered = false)
        registerSeat(authority, MimirQuickRailAction.FILES)
        dispatch(authority, rows, ControllerAction.NAV_DOWN, activated, filesSeatOffered = true)
        assertEquals("the memory is where the focus last was", homeShelfQuickRailKey(MimirQuickRailAction.SETTINGS), authority.state.value.focused)
        assertTrue(activated.isEmpty())
    }

    @Test
    fun `the landing is HOME with no memory, the memory when offered, and the nearest offered seat when not`() {
        val offered = homeShelfQuickActions(filesSeatOffered = false)
        assertEquals(MimirQuickRailAction.HOME, homeShelfQuickRailLanding(remembered = null, actions = offered))
        assertNull("the scope push restored the remembered seat already", homeShelfQuickRailLanding(remembered = MimirQuickRailAction.FILES, actions = HomeShelfQuickActions))
        assertNull(homeShelfQuickRailLanding(remembered = MimirQuickRailAction.SETTINGS, actions = offered))
        assertEquals(MimirQuickRailAction.QUICK_LAUNCH, homeShelfQuickRailLanding(remembered = MimirQuickRailAction.FILES, actions = offered))
        assertEquals(MimirQuickRailAction.QUICK_LAUNCH, homeShelfQuickRailNeighbour(MimirQuickRailAction.FILES, offered))
        assertEquals("the nearest in the dock's own order, on either side", MimirQuickRailAction.LIBRARY, homeShelfQuickRailNeighbour(MimirQuickRailAction.HOME, listOf(MimirQuickRailAction.LIBRARY, MimirQuickRailAction.FILES)))
        assertNull(homeShelfQuickRailNeighbour(MimirQuickRailAction.FILES, emptyList()))
    }

    @Test
    fun `LEFT and RIGHT from a seat the rail does not know go to the neighbour in the pressed direction, as on the Library dock`() {
        val rows = listOf(row("a"))
        val authority = registeredHomeAuthority(rows, filesSeatOffered = false)
        val activated = mutableListOf<MimirQuickRailAction>()
        dispatch(authority, rows, ControllerAction.NAV_DOWN, activated, filesSeatOffered = false)
        val ghost = FocusKey("home/rail/ghost")
        authority.registerVirtual(ghost, HomeShelfQuickRailScope, FocusBounds(900f, 1_000f, 950f, 1_050f))

        authority.requestFocus(ghost)
        dispatch(authority, rows, ControllerAction.NAV_LEFT, activated, filesSeatOffered = false)
        assertEquals(homeShelfQuickRailKey(MimirQuickRailAction.QUICK_LAUNCH), authority.state.value.focused)
        authority.requestFocus(ghost)
        dispatch(authority, rows, ControllerAction.NAV_RIGHT, activated, filesSeatOffered = false)
        assertEquals(homeShelfQuickRailKey(MimirQuickRailAction.LIBRARY), authority.state.value.focused)
    }

    @Test
    fun `the screen registers the seat only while it is offered, and the dock entry lands through the one landing rule`() {
        val screen = source("HomeShelfScreen.kt")
        val filesRailModifier = screen.substringAfter("val filesRailModifier = ").substringBefore("MimirRunicQuickRail(")
        assertTrue("registered only while offered", filesRailModifier.startsWith("if (filesSeatOffered) {"))
        assertTrue(filesRailModifier.substringBefore("} else {").contains("Modifier.mimirFocusable("))
        assertTrue(filesRailModifier.substringBefore("} else {").contains("key = homeShelfQuickRailKey(MimirQuickRailAction.FILES),"))
        assertTrue(filesRailModifier.substringAfter("} else {").trim().startsWith("Modifier\n"))
        assertEquals("one Files registration on the Home dock", 1, Regex(Regex.escape("homeShelfQuickRailKey(MimirQuickRailAction.FILES),")).findAll(screen).count())

        val models = source("HomeShelfModels.kt")
        val down = models.substringAfter("ControllerAction.NAV_DOWN -> {").substringBefore("ControllerAction.CONFIRM")
        assertTrue(down.contains("homeShelfQuickRailLanding(remembered, actions)?.let { authority.requestFocus(homeShelfQuickRailKey(it)) }"))
        assertFalse("no unconditional HOME landing is left", down.contains("if (!hadRemembered)"))
    }

    private fun dispatch(
        authority: DefaultFocusAuthority,
        rows: List<HomeShelfRow>,
        action: ControllerAction,
        activated: MutableList<MimirQuickRailAction>,
        filesSeatOffered: Boolean,
    ): Boolean = dispatchToHomeShelf(
        authority = authority,
        rows = rows,
        action = action,
        onLaunch = {},
        onOpenLibrary = {},
        onQuickAction = activated::add,
        onExit = {},
        filesSeatOffered = filesSeatOffered,
    )

    /** What the screen registers: the rows, and the OFFERED seats of the dock, each in its own slot. */
    private fun registeredHomeAuthority(rows: List<HomeShelfRow>, filesSeatOffered: Boolean) = DefaultFocusAuthority().apply {
        pushScope(HomeShelfScope)
        rows.forEachIndexed { index, row ->
            registerVirtual(
                key = row.focusKey,
                scope = HomeShelfScope,
                bounds = FocusBounds(index * 1_000f, 0f, index * 1_000f + 500f, 500f),
            )
        }
        homeShelfQuickActions(filesSeatOffered).forEach { action -> registerSeat(this, action) }
    }

    private fun registerSeat(authority: DefaultFocusAuthority, action: MimirQuickRailAction) {
        val index = HomeShelfQuickActions.indexOf(action)
        authority.register(
            key = homeShelfQuickRailKey(action),
            scope = HomeShelfQuickRailScope,
            bounds = FocusBounds(index * 100f, 1_000f, index * 100f + 50f, 1_050f),
        )
    }

    private fun source(fileName: String): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$fileName"),
        File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$fileName"),
    ).first(File::exists).readText()

    private fun row(id: String) = HomeShelfRow(
        itemId = HomeShelfItemId.Emulated(GameId(id)),
        rank = 0L,
        addedAtMillis = 0L,
        title = id,
    )
}

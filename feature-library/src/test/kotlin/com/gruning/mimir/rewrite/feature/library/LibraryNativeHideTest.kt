package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Walk-32h, the native hide ("every hide shatters the card"): the foundation the shatter of an Android game or app stands
 * on, driven through the real dispatch, a real [LibraryViewModel] and a real authority. Same shape as walk-32g's removal of
 * an emulated game: the card is broken while it is still listed and focused, the focus is handed to a card that stays, and
 * only then is the ONE write made, in the same turn, so the moment the shelf drops the package the authority already names
 * a survivor.
 */
class LibraryNativeHideTest {

    private fun row(packageName: String, label: String = packageName.substringAfterLast('.'), isGame: Boolean = true) =
        NativeShelfRow(
            packageName = packageName,
            label = label,
            launchActivity = null,
            isGame = isGame,
            isFavorite = true,
        )

    private fun viewModel() = LibraryViewModel(repository = FakeLibraryRepository(), mainDispatcher = Dispatchers.Unconfined)

    /** A surface with [rows] registered in [scope], the player on [focusOn], and the hide prompt open on top of it. */
    private fun promptOver(scope: FocusScope, rows: List<NativeShelfRow>, focusOn: String) = DefaultFocusAuthority().apply {
        pushScope(scope)
        rows.forEachIndexed { index, row ->
            val left = index * 110f
            register(nativeShelfKey(row.packageName), scope, FocusBounds(left, 0f, left + 100f, 100f))
        }
        requestFocus(nativeShelfKey(focusOn))
        pushScope(NativeHideConfirmScope)
        register(NativeHideCancelKey, NativeHideConfirmScope, FocusBounds(0f, 200f, 100f, 300f))
        register(NativeHideConfirmKey, NativeHideConfirmScope, FocusBounds(110f, 200f, 210f, 300f))
        requestFocus(NativeHideConfirmKey)
    }

    private fun confirm(
        authority: DefaultFocusAuthority,
        viewModel: LibraryViewModel,
        shelfRows: List<NativeShelfRow> = emptyList(),
        favouriteGames: List<NativeShelfRow> = emptyList(),
        favouriteApps: List<NativeShelfRow> = emptyList(),
        write: () -> Unit,
    ) = dispatchToLibrary(
        authority = authority,
        viewModel = viewModel,
        action = ControllerAction.CONFIRM,
        onExit = {},
        onRescanFolder = {},
        onConfirmHideNativePackage = write,
        nativeGameRows = shelfRows,
        nativeFavoriteGameRows = favouriteGames,
        nativeFavoriteAppRows = favouriteApps,
    )

    // ================================================================================================== the survivor

    @Test
    fun `the card that stays is the next one along, else the one before, else none`() {
        val rows = listOf(row("com.a"), row("com.b"), row("com.c"))
        assertEquals("com.b", survivingNativeRowAfterRemoval(rows, "com.a")?.packageName)
        assertEquals("com.c", survivingNativeRowAfterRemoval(rows, "com.b")?.packageName)
        assertEquals("the last card hands over to the one before it", "com.b", survivingNativeRowAfterRemoval(rows, "com.c")?.packageName)
        assertNull("a shelf of one has nothing to hand to", survivingNativeRowAfterRemoval(listOf(row("com.a")), "com.a"))
        assertNull("a package that is not on the surface has no survivor", survivingNativeRowAfterRemoval(rows, "com.zzz"))
    }

    @Test
    fun `the surface a hide happens in names the rows the player sees, and only those`() {
        val viewModel = viewModel()
        val shelf = listOf(row("com.a"), row("com.b"))
        val favouriteGames = listOf(row("com.x.zedone", isGame = false), row("com.x.other", isGame = false), row("com.x.zedtwo", isGame = false))
        val favouriteApps = listOf(row("com.y.app", isGame = false))

        assertEquals(
            "the shelf's ring is the Android games it draws",
            shelf,
            nativeRowsShownForHide(LibraryShelfScope, viewModel, shelf, favouriteGames, favouriteApps),
        )

        viewModel.openFavorites(FavoriteSegment.ANDROID_GAMES)
        assertEquals(
            "a Favorites segment is its own favourites",
            favouriteGames,
            nativeRowsShownForHide(GamelistScope, viewModel, shelf, favouriteGames, favouriteApps),
        )
        viewModel.setSearchQuery("zed")
        assertEquals(
            "narrowed by the search the list is showing: a hidden-by-search row is not a survivor",
            listOf("com.x.zedone", "com.x.zedtwo"),
            nativeRowsShownForHide(GamelistScope, viewModel, shelf, favouriteGames, favouriteApps).map { it.packageName },
        )
        viewModel.setSearchQuery("")
        viewModel.setFavoriteSegment(FavoriteSegment.ANDROID_APPS)
        assertEquals(favouriteApps, nativeRowsShownForHide(GamelistScope, viewModel, shelf, favouriteGames, favouriteApps))

        viewModel.setFavoriteSegment(FavoriteSegment.GAMES)
        assertTrue(
            "the emulated games segment is not a native surface",
            nativeRowsShownForHide(GamelistScope, viewModel, shelf, favouriteGames, favouriteApps).isEmpty(),
        )
        assertTrue(nativeRowsShownForHide(GameDetailScope, viewModel, shelf, favouriteGames, favouriteApps).isEmpty())
    }

    // ================================================================================================== the order

    @Test
    fun `the shelf tile is broken first, then focus is handed on, then the one write is made in the same turn`() {
        val viewModel = viewModel()
        val rows = listOf(row("com.a"), row("com.b"), row("com.c"))
        val authority = promptOver(LibraryShelfScope, rows, focusOn = "com.b")
        val events = mutableListOf<String>()
        val slotGate = CompletableDeferred<Unit>()
        viewModel.nativeDepartureSlot = { packageName ->
            events += "slot:$packageName@${authority.state.value.focused?.value}"
            slotGate.await()
            events += "slot-done"
        }

        assertTrue(
            confirm(authority, viewModel, shelfRows = rows) {
                events += "write@${authority.state.value.focused?.value}"
            },
        )

        assertEquals(
            "the slot runs while the card is still listed and focused, and nothing is written while it plays",
            listOf("slot:com.b@library/native/com.b"),
            events,
        )
        assertEquals(LibraryShelfScope, authority.state.value.scope)
        assertNull("the slot posts the receipt when it has one; the view model does not say it twice", viewModel.parkedRefusalToast.value)

        slotGate.complete(Unit)

        assertEquals(
            "at the write, focus already names the card that stays",
            listOf("slot:com.b@library/native/com.b", "slot-done", "write@library/native/com.c"),
            events,
        )
        assertEquals(nativeShelfKey("com.c"), authority.state.value.focused)
        assertEquals("and the scope remembers it", nativeShelfKey("com.c"), authority.rememberedFocusIn(LibraryShelfScope))
    }

    @Test
    fun `the last card hands over to the one before it, and a Favorites row is handed on inside its own scope`() {
        val viewModel = viewModel()
        val rows = listOf(row("com.x.one", isGame = false), row("com.x.two", isGame = false))
        viewModel.openFavorites(FavoriteSegment.ANDROID_APPS)
        val authority = promptOver(GamelistScope, rows, focusOn = "com.x.two")
        var focusAtWrite: String? = null

        confirm(authority, viewModel, favouriteApps = rows) { focusAtWrite = authority.state.value.focused?.value }

        assertEquals("library/native/com.x.one", focusAtWrite)
        assertEquals(GamelistScope, authority.state.value.scope)
        assertEquals(nativeShelfKey("com.x.one"), authority.rememberedFocusIn(GamelistScope))
    }

    @Test
    fun `a Favorites row's survivor is a row the search still shows`() {
        val viewModel = viewModel()
        val rows = listOf(row("com.x.zedone", isGame = false), row("com.x.other", isGame = false), row("com.x.zedtwo", isGame = false))
        viewModel.openFavorites(FavoriteSegment.ANDROID_APPS)
        viewModel.setSearchQuery("zed")
        val authority = promptOver(GamelistScope, rows, focusOn = "com.x.zedone")
        var focusAtWrite: String? = null

        confirm(authority, viewModel, favouriteApps = rows) { focusAtWrite = authority.state.value.focused?.value }

        assertEquals(
            "the row after it in the LIST THE PLAYER SEES, not the unfiltered one (which would refocus row one)",
            "library/native/com.x.zedtwo",
            focusAtWrite,
        )
    }

    @Test
    fun `a shelf of one has nothing to hand to, so focus stays and the write is still made`() {
        val viewModel = viewModel()
        val rows = listOf(row("com.only"))
        val authority = promptOver(LibraryShelfScope, rows, focusOn = "com.only")
        var written = 0

        confirm(authority, viewModel, shelfRows = rows) { written++ }

        assertEquals(1, written)
        assertEquals(nativeShelfKey("com.only"), authority.state.value.focused)
    }

    // ============================================================================================ the receipt and the slot

    @Test
    fun `with no slot set the hide still says where it went, at once, once`() {
        val viewModel = viewModel()
        val rows = listOf(row("com.a"), row("com.b"))
        val authority = promptOver(LibraryShelfScope, rows, focusOn = "com.a")
        var written = 0

        confirm(authority, viewModel, shelfRows = rows) { written++ }

        assertEquals(1, written)
        val toast = viewModel.parkedRefusalToast.value
        assertNotNull(toast)
        assertEquals(LibraryHideCopy.NativeReceipt, toast!!.text)
        assertEquals(nativeShelfKey("com.b"), authority.state.value.focused)
    }

    @Test
    fun `a slot that ends the hide leaves the package on its shelf and writes nothing`() {
        val viewModel = viewModel()
        val rows = listOf(row("com.a"), row("com.b"))
        val authority = promptOver(LibraryShelfScope, rows, focusOn = "com.a")
        var written = 0
        viewModel.nativeDepartureSlot = { throw CancellationException("the screen left") }

        confirm(authority, viewModel, shelfRows = rows) { written++ }

        assertEquals("nothing was written", 0, written)
        assertEquals("and focus never left the card", nativeShelfKey("com.a"), authority.state.value.focused)
    }

    @Test
    fun `cancelling the prompt breaks nothing and writes nothing`() {
        val viewModel = viewModel()
        val rows = listOf(row("com.a"), row("com.b"))
        val authority = promptOver(LibraryShelfScope, rows, focusOn = "com.a")
        var slotCalls = 0
        viewModel.nativeDepartureSlot = { slotCalls++ }

        assertTrue(
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = ControllerAction.CANCEL,
                onExit = {},
                onRescanFolder = {},
                nativeGameRows = rows,
            ),
        )

        assertEquals(0, slotCalls)
        assertEquals(LibraryShelfScope, authority.state.value.scope)
        assertEquals(nativeShelfKey("com.a"), authority.state.value.focused)
    }
}

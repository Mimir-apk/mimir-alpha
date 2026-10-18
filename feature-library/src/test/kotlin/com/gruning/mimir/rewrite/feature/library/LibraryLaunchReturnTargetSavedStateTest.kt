package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The flat encoding a launch's pending return target rides in the host's saved instance state (a process kill during an emulator load). */
class LibraryLaunchReturnTargetSavedStateTest {

    private fun roundTrip(target: LibraryLaunchReturnTarget) = LibraryLaunchReturnTarget.fromSavedState(target.toSavedState())

    @Test
    fun `a ROM card target round-trips with its query, filters and the card flag`() {
        val target = LibraryLaunchReturnTarget.gamelistRom(
            openPlatformId = PlatformId.PS1,
            romPlatformId = PlatformId.PS1,
            gameId = GameId("ps1/chrono-cross"),
            shelfMode = LibraryShelfMode.EMULATION,
            favoriteSegment = FavoriteSegment.GAMES,
            searchQuery = "chrono cross: \"radical\" | dreamers",
            favoritesOnly = true,
            restoreLaunchCard = true,
            gamelistViewMode = GamelistViewMode.entries.last(),
        )
        assertEquals(target, roundTrip(target))
    }

    @Test
    fun `a native shelf target and a native gamelist target round-trip`() {
        val shelf = LibraryLaunchReturnTarget.shelf(LibraryShelfMode.ANDROID_GAMES, "com.example.game", restoreLaunchCard = true)
        assertEquals(shelf, roundTrip(shelf))
        val list = LibraryLaunchReturnTarget.gamelistNative(
            openPlatformId = PlatformId.PS1,
            nativePackageName = "com.example.game",
            shelfMode = LibraryShelfMode.EMULATION,
            favoriteSegment = FavoriteSegment.ANDROID_GAMES,
            searchQuery = "",
        )
        assertEquals(list, roundTrip(list))
    }

    @Test
    fun `the dedicated search snapshot does not survive a kill, the query does`() {
        val withSnapshot = LibraryLaunchReturnTarget.gamelistRom(
            openPlatformId = PlatformId.PS1,
            romPlatformId = PlatformId.PS1,
            gameId = GameId("ps1/x"),
            shelfMode = LibraryShelfMode.EMULATION,
            favoriteSegment = FavoriteSegment.GAMES,
            searchQuery = "x",
            favoritesOnly = false,
            searchSnapshot = LibrarySearchReturnSnapshot(query = "x"),
        )
        val restored = requireNotNull(roundTrip(withSnapshot))
        assertNull(restored.searchSnapshot)
        assertEquals("x", restored.searchQuery)
    }

    @Test
    fun `anything this build cannot read is no target at all`() {
        assertNull(LibraryLaunchReturnTarget.fromSavedState(null))
        assertNull(LibraryLaunchReturnTarget.fromSavedState(emptyList()))
        val good = { LibraryLaunchReturnTarget.shelf(LibraryShelfMode.EMULATION, "ps1").toSavedState() }
        assertNull("a changed layout", LibraryLaunchReturnTarget.fromSavedState(good() + "extra"))
        assertNull("another version", LibraryLaunchReturnTarget.fromSavedState(good().apply { this[0] = "ret0" }))
        assertNull("a removed enum value", LibraryLaunchReturnTarget.fromSavedState(good().apply { this[2] = "NO_SUCH_MODE" }))
        assertNull("a shelf target with no key violates the model", LibraryLaunchReturnTarget.fromSavedState(good().apply { this[3] = "" }))
    }

    /**
     * Launch return: the return lands on the launch card, smooth, including after a low-memory kill. A target put back from the saved state restores like
     * any other: it is the restore in flight, the card arrives as the finished card (no glide into it) and the page change is the settled one.
     */
    @Test
    fun `a target restored after a kill lands on the launch card, settled, with no glide`() {
        val target = LibraryLaunchReturnTarget.gamelistRom(
            openPlatformId = PlatformId.PS1,
            romPlatformId = PlatformId.PS1,
            gameId = GameId("ps1/chrono-cross"),
            shelfMode = LibraryShelfMode.EMULATION,
            favoriteSegment = FavoriteSegment.GAMES,
            searchQuery = "chrono",
            favoritesOnly = true,
            restoreLaunchCard = true,
        )
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        assertTrue("fixture: the new process had no launch of its own", viewModel.restorePendingLaunchReturnTargetAfterKill(roundTrip(target)!!))
        val pending = requireNotNull(viewModel.pendingLaunchReturnTarget.value)
        assertTrue("the restored target is known as one", viewModel.isLaunchReturnRestoredAfterKill(pending))
        assertNull("nothing is in flight until the restore begins", viewModel.restoringLaunchReturnTarget())

        viewModel.restoreLaunchReturnTargetState(pending)
        val restoring = viewModel.restoringLaunchReturnTarget()
        assertTrue("the card arrives as the finished card", libraryDetailArrivalSettled(restoring))
        assertTrue("and the page change is the settled one", libraryPageRestoreSettled(restoring))
        assertEquals(
            LibraryPageMotion.Settled,
            libraryPrimaryPageMotion(
                LibraryPrimarySurfaceKey.Shelf,
                LibraryPrimarySurfaceKey.Gamelist,
                settleGamelistOpen = null,
                settled = libraryPageRestoreSettled(restoring),
            ),
        )
        assertEquals(PlatformId.PS1, viewModel.openPlatform.value)

        viewModel.clearPendingLaunchReturnTarget() // the cover lifted
        assertNull(viewModel.pendingLaunchReturnTarget.value)
        assertNull("and nothing reads as a restore afterwards", viewModel.restoringLaunchReturnTarget())
        assertTrue("(a later page change glides again)", !libraryPageRestoreSettled(viewModel.restoringLaunchReturnTarget()))
    }
}

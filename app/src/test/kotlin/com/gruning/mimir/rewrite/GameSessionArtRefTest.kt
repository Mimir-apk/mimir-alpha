package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import com.gruning.mimir.rewrite.core.model.library.WiredFace
import com.gruning.mimir.rewrite.feature.library.FavoriteSegment
import com.gruning.mimir.rewrite.feature.library.LibraryGameLaunchOperation
import com.gruning.mimir.rewrite.feature.library.LibraryGameSessionEvent
import com.gruning.mimir.rewrite.feature.library.LibraryLaunchReturnTarget
import com.gruning.mimir.rewrite.feature.library.LibraryShelfMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy 2026-10-10 (one shelf face) moved a `pc_games` game's automatic SteamGridDB portrait out of
 * `GameRow.artUri` (where the Library projection rewrote it) into `GameRow.wiredFace`. A Library-launched PC game with
 * only that portrait carried no art into its session, so the Bottom's game tab and the Now Playing card had none.
 */
class GameSessionArtRefTest {

    private val portrait = "file:///files/sgdb2/pc-portrait.png#v=2"
    private val square = "file:///files/sgdb2/pc.png#v=1"
    private val cover = "file:///games/media_art/Game.png"

    private fun pcGame(artUri: String? = null, wired: WiredFace? = null) = GameRow(
        gameId = GameId("pc_games/hades"),
        title = "Hades",
        platformId = PlatformId.PC_GAMES,
        memberCount = 1,
        duplicateKind = null,
        artUri = artUri,
        wiredFace = wired,
    )

    @Test
    fun `a pc game with only its portrait carries the portrait`() {
        assertEquals(portrait, gameSessionArtRef(pcGame(wired = WiredFace(portrait, ShelfFaceShape.LEDGE_COVER))))
    }

    @Test
    fun `a game with a cover carries it, and one he picked a cover for carries the pick`() {
        assertEquals(cover, gameSessionArtRef(pcGame(artUri = cover)))
        assertEquals(
            portrait,
            gameSessionArtRef(pcGame(artUri = cover, wired = WiredFace(portrait, ShelfFaceShape.LEDGE_COVER))),
        )
    }

    @Test
    fun `a square is the Icon-art tile, so it is session art only when nothing else is`() {
        // A PC game with only its square used to carry it through artUri; it keeps carrying it.
        assertEquals(square, gameSessionArtRef(pcGame(wired = WiredFace(square, ShelfFaceShape.SQUARE))))
        assertEquals(cover, gameSessionArtRef(pcGame(artUri = cover, wired = WiredFace(square, ShelfFaceShape.SQUARE))))
        assertNull(gameSessionArtRef(pcGame()))
        assertNull(gameSessionArtRef(pcGame(artUri = "  ")))
    }

    /** The whole path: a Library launch for the Bottom, the dispatch hook, and what the Bottom's tab is told. */
    @Test
    fun `a library launched pc game with only a portrait gets that art in noteGameTab`() {
        val game = pcGame(wired = WiredFace(portrait, ShelfFaceShape.LEDGE_COVER))
        val event = LibraryGameSessionEvent.Preparing(
            operation = LibraryGameLaunchOperation(7L),
            game = game,
            returnTarget = LibraryLaunchReturnTarget.gamelistRom(
                openPlatformId = game.platformId,
                romPlatformId = game.platformId,
                gameId = game.gameId,
                shelfMode = LibraryShelfMode.EMULATION,
                favoriteSegment = FavoriteSegment.GAMES,
                searchQuery = "",
                favoritesOnly = false,
            ),
            startedAtEpochMs = 1_007L,
        )
        val coordinator = MimirSessionCoordinator()
        assertTrue(
            coordinator.gamePreparing(
                operation = libraryGameSessionOperation(event.operation),
                game = event.game,
                returnAnchor = MimirGameReturnAnchor.Library(event.returnTarget),
                libraryOperation = event.operation,
                startedAtEpochMs = event.startedAtEpochMs,
                placement = MimirGamePlacement.BOTTOM,
            ),
        )

        val named = mutableListOf<List<String?>>()
        val previous = MimirBottomGameTabs.noteGameTab
        try {
            MimirBottomGameTabs.noteGameTab = { pkg, title, gameId, artRef -> named += listOf(pkg, title, gameId, artRef) }
            noteBottomGameTab(coordinator.state.value.gameSession, "org.example.emulator")
        } finally {
            MimirBottomGameTabs.noteGameTab = previous
        }

        assertEquals(listOf(listOf("org.example.emulator", "Hades", "pc_games/hades", portrait)), named)
    }

    @Test
    fun `a main game is not named on the Bottom`() {
        val game = pcGame(artUri = cover)
        val coordinator = MimirSessionCoordinator()
        requireNotNull(
            coordinator.prepareHomeGame(
                game = game,
                returnTarget = com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget(
                    com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId.Emulated(game.gameId),
                    rank = 0L,
                ),
            ),
        )
        val named = mutableListOf<String>()
        val previous = MimirBottomGameTabs.noteGameTab
        try {
            MimirBottomGameTabs.noteGameTab = { pkg, _, _, _ -> named += pkg }
            noteBottomGameTab(coordinator.state.value.gameSession, "org.example.emulator")
        } finally {
            MimirBottomGameTabs.noteGameTab = previous
        }
        assertTrue(named.isEmpty())
    }
}

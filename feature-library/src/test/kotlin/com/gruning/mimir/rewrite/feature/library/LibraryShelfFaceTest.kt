package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.DuplicateGroupKind
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.ShelfFace
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import com.gruning.mimir.rewrite.core.model.library.WiredFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Library's shelf is the Home shelf , so one game must wear the face Home would give it and the
 * on-tile marks the Library has always stated. These pin [shelfFaceOf] (the picture and its shape) and [libraryGameShelfItem] (the
 * item the shelf is handed) from a [GameRow], the way the repository delivers one.
 */
class LibraryShelfFaceTest {

    private fun game(
        id: String = "g",
        artUri: String? = null,
        wiredFace: WiredFace? = null,
        physicalMediaUri: String? = null,
        flatArtUri: String? = null,
        isFavorite: Boolean = false,
        memberCount: Int = 1,
        duplicateKind: DuplicateGroupKind? = null,
        discCount: Int = 0,
        artResolutionSettled: Boolean = true,
        platform: PlatformId = PlatformId.GBA,
    ) = GameRow(
        gameId = GameId(id),
        title = "Game $id",
        platformId = platform,
        memberCount = memberCount,
        duplicateKind = duplicateKind,
        discCount = discCount,
        isFavorite = isFavorite,
        artUri = artUri,
        wiredFace = wiredFace,
        physicalMediaUri = physicalMediaUri,
        flatArtUri = flatArtUri,
        artResolutionSettled = artResolutionSettled,
    )

    @Test
    fun `a pick wired by hand wins, in the shape it was wired`() {
        ShelfFaceShape.values().filter { it != ShelfFaceShape.NONE }.forEach { shape ->
            val face = shelfFaceOf(game(artUri = "file:///cover.png", wiredFace = WiredFace("file:///picked.png", shape)))
            assertEquals("a wired $shape face is the one painted", ShelfFace("file:///picked.png", shape), face)
        }
    }

    @Test
    fun `a wired icon is a SQUARE tile, not a box`() {
        val face = shelfFaceOf(game(wiredFace = WiredFace("file:///icon.png", ShelfFaceShape.SQUARE)))
        assertEquals(ShelfFaceShape.SQUARE, face.shape)
    }

    @Test
    fun `the automatic cover is a ledge box`() {
        assertEquals(ShelfFace("file:///cover.png", ShelfFaceShape.LEDGE_COVER), shelfFaceOf(game(artUri = "file:///cover.png")))
    }

    @Test
    fun `a wired face with a blank uri falls through to the cover rather than painting nothing`() {
        val face = shelfFaceOf(game(artUri = "file:///cover.png", wiredFace = WiredFace("   ", ShelfFaceShape.PHYSICAL)))
        assertEquals(ShelfFace("file:///cover.png", ShelfFaceShape.LEDGE_COVER), face)
    }

    @Test
    fun `a uri is trimmed before it is asked for`() {
        assertEquals("file:///a.png", shelfFaceOf(game(wiredFace = WiredFace("  file:///a.png ", ShelfFaceShape.SQUARE))).uri)
        assertEquals("file:///b.png", shelfFaceOf(game(artUri = "  file:///b.png ")).uri)
    }

    @Test
    fun `a game with no cover wears no face, so the shelf paints the runed book`() {
        assertEquals(ShelfFace.None, shelfFaceOf(game()))
        assertEquals(ShelfFace.None, shelfFaceOf(game(artUri = "")))
        assertEquals(ShelfFace.None, shelfFaceOf(game(artUri = "   ")))
    }

    @Test
    fun `the provider's support disc and an emulated game's flat art never answer a shelf with a cover missing`() {
        // The 2026-09-17 shelf face law: a per-game fall-through to a disc makes the shelf look assembled from two libraries.
        val face = shelfFaceOf(game(physicalMediaUri = "file:///x-support.png", flatArtUri = "file:///flat.png"))
        assertEquals(ShelfFace.None, face)
        assertFalse(face.shape == ShelfFaceShape.PHYSICAL)
    }

    /**
     * a `pc_games` game with no cover and no tile but a clear logo shows the LOGO on both doors, as Home's STAND_IN
     * tier does (a ledge cover); the book stays for a game with no art at all. The answer is the one function Home reads.
     */
    @Test
    fun `a pc game with no cover shows its clear logo, as Home does, and the book is for no art at all`() {
        val logo = shelfFaceOf(game(platform = PlatformId.PC_GAMES, flatArtUri = "file:///logo.png"))
        assertEquals(ShelfFace("file:///logo.png", ShelfFaceShape.LEDGE_COVER), logo)
        assertEquals(
            "the same answer Home's cover function gives",
            homeShelfCoverUri(PlatformId.PC_GAMES, null, "file:///logo.png"),
            logo.uri,
        )
        assertEquals("no art at all is the book", ShelfFace.None, shelfFaceOf(game(platform = PlatformId.PC_GAMES)))
        assertEquals("a blank cover does not hide the logo", "file:///logo.png", shelfFaceOf(game(platform = PlatformId.PC_GAMES, artUri = "  ", flatArtUri = "file:///logo.png")).uri)
        assertEquals("a blank logo is no logo", ShelfFace.None, shelfFaceOf(game(platform = PlatformId.PC_GAMES, flatArtUri = " ")))
    }

    @Test
    fun `a pc game's cover and a wired face both outrank its logo`() {
        assertEquals(
            ShelfFace("file:///cover.png", ShelfFaceShape.LEDGE_COVER),
            shelfFaceOf(game(platform = PlatformId.PC_GAMES, artUri = "file:///cover.png", flatArtUri = "file:///logo.png")),
        )
        assertEquals(
            ShelfFace("file:///tile.png", ShelfFaceShape.SQUARE),
            shelfFaceOf(
                game(
                    platform = PlatformId.PC_GAMES,
                    flatArtUri = "file:///logo.png",
                    wiredFace = WiredFace("file:///tile.png", ShelfFaceShape.SQUARE),
                ),
            ),
        )
    }

    @Test
    fun `the shelf item carries the face, the two marks, the game's own id and no plate`() {
        val row = game(
            id = "ff7",
            artUri = "file:///cover.png",
            isFavorite = true,
            memberCount = 2,
            duplicateKind = DuplicateGroupKind.MULTI_DISC,
            discCount = 2,
        )
        val item = libraryGameShelfItem(row)
        assertEquals(gameKey(row), item.focusKey)
        assertEquals("ff7", item.stableId)
        assertEquals("Game ff7", item.title)
        assertEquals(ShelfFace("file:///cover.png", ShelfFaceShape.LEDGE_COVER), item.face)
        assertTrue("the favourite rune stays on the tile", item.marks.favorite)
        assertEquals("the disc badge stays on the tile", "2 discs", item.marks.collapseBadge)
        assertEquals("a gamelist is one platform and the header names it: no plate", "", item.platformIdValue)
        assertFalse(item.artPending)
    }

    @Test
    fun `an unmarked single game carries no marks`() {
        assertEquals(GameShelfMarks.None, libraryGameShelfItem(game(artUri = "file:///cover.png")).marks)
    }

    @Test
    fun `a game whose art is not looked up yet paints nothing instead of claiming it has none`() {
        val pending = libraryGameShelfItem(game(artResolutionSettled = false))
        assertTrue("an unanswered question is not the runed book", pending.artPending)
        val settled = libraryGameShelfItem(game(artResolutionSettled = true))
        assertFalse("art known to be absent is the runed book", settled.artPending)
        // A face that is known never waits, settled or not.
        assertFalse(libraryGameShelfItem(game(artUri = "file:///c.png", artResolutionSettled = false)).artPending)
    }
}

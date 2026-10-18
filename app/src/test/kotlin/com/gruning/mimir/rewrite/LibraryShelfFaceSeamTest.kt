package com.gruning.mimir.rewrite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import com.gruning.mimir.rewrite.core.model.library.WiredFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * the Library screen must show the SteamGridDB tile ENRICH just wrote. Policy
 * 2026-10-10: for every platform, from the same resolver Home uses.
 *
 * This replaces `LibraryPcTileProjectionTest`, which pinned `withPcTileArtOverlay` and `PcTileLibraryRepository`
 * (both deleted: the face is projected inside the repository's own row mapping now, through
 * `CoverOverrideSource.shelfFaceOverrideFor`, so there is nothing to overlay afterwards). Its shapes survive, re-pointed
 * at the general seam:
 *
 *  - the `pc_games` portrait sibling is preferred to the square, and the square is the fallback;
 *  - a cover the owner placed himself is never displaced by an automatic tile (the seam is told `hasAutomaticCover`);
 *  - a platform that is NOT `pc_games` used to be unaffected; it now gets the same face (the ruling), which is the one
 *    expectation that reversed, and the cover-wins differential below is what keeps that from being a regression;
 *
 * A tile write for a game with no art must still reach an already-open Library flow (PM gate DEFECT 1). That is now the
 * repository's own `refreshShelfFaces()`, published once by the fetcher; it is covered where the flows live, in
 * `LibraryRepositoryShelfFaceTest` (core-resolver), and the fetcher's publish is pinned in `LibraryPcTileWiringTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class LibraryShelfFaceSeamTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun writeSquare(key: String) =
        SteamGridDbTileFetcher.canonicalTileIconFile(context, key).also {
            it.parentFile?.mkdirs()
            it.writeBytes(byteArrayOf(1))
        }

    private fun writePortrait(key: String) =
        SteamGridDbTileFetcher.canonicalPortraitTileIconFile(context, key).also {
            it.parentFile?.mkdirs()
            it.writeBytes(byteArrayOf(2))
        }

    // -- the pinned shapes, through the general seam ----------------------------------------------------

    @Test
    fun `a game with both a portrait and a square tile gets the portrait as a box`() {
        val key = "pc_games/seam-both"
        writeSquare(key)
        val portrait = writePortrait(key)

        val face = shelfFaceOverride(context, key, hasAutomaticCover = false)

        assertEquals(WiredFace(versionedFileUri(portrait), ShelfFaceShape.LEDGE_COVER), face)
    }

    @Test
    fun `a game with only a square tile falls back to the square`() {
        val key = "pc_games/seam-square-only"
        val square = writeSquare(key)

        assertEquals(
            WiredFace(versionedFileUri(square), ShelfFaceShape.SQUARE),
            shelfFaceOverride(context, key, hasAutomaticCover = false),
        )
    }

    @Test
    fun `a game with no tile says nothing`() {
        assertNull(shelfFaceOverride(context, "pc_games/seam-no-tile", hasAutomaticCover = false))
    }

    /** The differential for the reversed expectation: the tile exists, so "no effect" cannot pass by accident. */
    @Test
    fun `a game with its own cover keeps it, though both tiles exist at the same key`() {
        val key = "pc_games/seam-own-cover"
        assertTrue(writeSquare(key).isFile)
        assertTrue(writePortrait(key).isFile)

        assertNull(shelfFaceOverride(context, key, hasAutomaticCover = true))
    }

    @Test
    fun `a platform that is not pc_games gets the same face now, and a blank key reads nothing`() {
        val key = "snes/seam-other-platform"
        val square = writeSquare(key)

        assertEquals(
            WiredFace(versionedFileUri(square), ShelfFaceShape.SQUARE),
            shelfFaceOverride(context, key, hasAutomaticCover = false),
        )
        assertNull(shelfFaceOverride(context, "", hasAutomaticCover = false))
    }
}

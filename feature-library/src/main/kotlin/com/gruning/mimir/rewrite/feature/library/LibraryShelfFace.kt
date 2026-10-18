package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.ShelfFace

/**
 * The face the game shelf paints for one Library game, picture and shape together. The Library's shelf is the Home shelf, so this is the SAME answer Home's resolver gives for the same game, read from the row the repository handed
 * over: `GameRow.wiredFace` carries the resolver's pick tier and its automatic-tile tier for every platform, with the shape of
 * each; the automatic cover is `artUri`; the stand-in is the row's own art when it is not a real cover (a `pc_games` game's clear
 * logo, [homeShelfCoverUri], the function Home reads, painted as a ledge cover as Home's STAND_IN tier does; * the logo shows on both doors); and a game with none of them wears the runed book ([ShelfFace.None], which the ledge painter
 * paints as its own no-art state).
 *
 * Deliberately not [libraryListPreviewMedia]. The list row keeps DL-4, where real art of either kind outranks the book, so it
 * falls through to the provider's `-support` disc. A shelf holds many games side by side, and a per-game fall-through to a disc
 * makes it look assembled from two libraries (the 2026-09-17 shelf face law, 2026-09-22: the automatic shelf tier is the cover
 * only). So the `-support` render, [GameRow.physicalMediaUri], never answers here.
 *
 * Public because the app builds shelf rows from Library games (the compact browser) and must not restate the ladder.
 */
fun shelfFaceOf(game: GameRow): ShelfFace {
    game.wiredFace?.let { wired ->
        val uri = wired.uri.trim()
        if (uri.isNotEmpty()) return ShelfFace(uri, wired.shape)
    }
    // A blank cover is no cover, so a blank `artUri` must not hide the stand-in behind it (`artUri ?: flat` would keep the blank).
    val cover = game.artUri?.trim()?.takeIf(String::isNotEmpty)
    return ledgeCoverFaceOf(homeShelfCoverUri(game.platformId, cover, game.flatArtUri?.trim()?.takeIf(String::isNotEmpty)))
}

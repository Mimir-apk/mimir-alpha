package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow

/**
 * Why a game is leaving the list the player is looking at, before the database has said so.
 *
 * The reported gamelist removal had no preceding animation; un-favouriting first removed the
 * favorite mark, then the game, producing visible jitter. The list only
 * learned of a removal when Room re-emitted, a hop through the write, the invalidation and (on a platform's list) a
 * 1.5 s throttle later, so nothing could dissolve a card the list still held and the card went in the same frame the
 * focus fell through to game one. The ViewModel now lets the game go the moment the player confirms, and keeps this
 * record until the database's own list agrees.
 */
internal enum class LibraryRemovalKind {
    /** Hidden from the library: the game leaves every list until the player restores it. */
    HIDDEN,

    /** Un-starred inside a favourites view: the game leaves THAT view and stays everywhere else. */
    UNFAVORITED,
}

/** One game held back from [scope]'s list. [scope] is the open platform when it was sent out, null when none was open. */
internal data class PendingLibraryRemoval(
    val scope: PlatformId?,
    val kind: LibraryRemovalKind,
)

/**
 * The games sent out of a list whose row has not yet left the database's own list.
 *
 * Applied where the visible list is reduced ([LibraryViewModel.games]) and again, in the same frame, by the screen that
 * draws it, and dropped ([reconciledWith]) as soon as a live emission for the list it left no longer carries the game.
 * Every function is pure so the rules are checkable without a ViewModel, a clock or a repository.
 */
internal data class PendingLibraryRemovals(
    private val byGame: Map<GameId, PendingLibraryRemoval> = emptyMap(),
) {
    val isEmpty: Boolean get() = byGame.isEmpty()

    val ids: Set<GameId> get() = byGame.keys

    operator fun contains(gameId: GameId): Boolean = gameId in byGame

    fun with(gameId: GameId, removal: PendingLibraryRemoval): PendingLibraryRemovals =
        copy(byGame = byGame + (gameId to removal))

    fun without(gameId: GameId): PendingLibraryRemovals =
        if (gameId in byGame) copy(byGame = byGame - gameId) else this

    /**
     * Whether [row] is held back from the list [openScope] shows. A hidden game leaves every list. An un-starred one
     * leaves only the favourites view it was un-starred in, and only while that view is still what the list is: with the
     * filter off, the same game is an ordinary row again.
     */
    fun hides(row: GameRow, openScope: PlatformId?, favoritesOnly: Boolean): Boolean {
        val removal = byGame[row.gameId] ?: return false
        return when (removal.kind) {
            LibraryRemovalKind.HIDDEN -> true
            LibraryRemovalKind.UNFAVORITED ->
                removal.scope == openScope &&
                    (openScope == LibraryViewModel.FAVORITES_PLATFORM_ID || favoritesOnly)
        }
    }

    /** [rows] without what is held back. The SAME list when nothing in it is, so an unchanged list stays unchanged. */
    fun applyTo(rows: List<GameRow>, openScope: PlatformId?, favoritesOnly: Boolean): List<GameRow> {
        if (byGame.isEmpty() || rows.none { hides(it, openScope, favoritesOnly) }) return rows
        return rows.filterNot { hides(it, openScope, favoritesOnly) }
    }

    /**
     * What is still pending once a LIVE emission [raw] for [scope]'s list has arrived: an entry is done when the
     * database's list no longer carries the game as it was (gone, for a hidden game; gone or no longer starred, for an
     * un-starred one). An emission for another list says nothing about it, and an emission computed before the write
     * still carries the game, so the entry stays until one computed after it does not.
     *
     * The same instance when nothing changed, so the StateFlow it lives in does not re-emit for nothing.
     */
    fun reconciledWith(scope: PlatformId?, raw: List<GameRow>): PendingLibraryRemovals {
        if (byGame.isEmpty()) return this
        val rawById = raw.associateBy { it.gameId }
        val kept = byGame.filterNot { (gameId, removal) ->
            (removal.scope == null || removal.scope == scope) && when (removal.kind) {
                LibraryRemovalKind.HIDDEN -> gameId !in rawById
                LibraryRemovalKind.UNFAVORITED -> rawById[gameId]?.isFavorite != true
            }
        }
        return if (kept.size == byGame.size) this else copy(byGame = kept)
    }
}

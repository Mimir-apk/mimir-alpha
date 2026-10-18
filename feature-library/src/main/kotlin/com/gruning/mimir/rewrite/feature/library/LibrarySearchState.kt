package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import java.util.Locale

/** Immutable identity of the game projection that opened a Library Search session. */
internal sealed interface LibrarySearchOrigin {
    data class PlatformGames(val platformId: PlatformId) : LibrarySearchOrigin
    data object FavoriteGames : LibrarySearchOrigin
}

/** Stable result identity shared by focus, scroll anchoring, and the later Tome Search surface. */
internal sealed interface LibrarySearchResultIdentity {
    data class Game(val gameId: GameId) : LibrarySearchResultIdentity

    data class NativePackage(val packageName: String) : LibrarySearchResultIdentity {
        init {
            require(packageName.isNotBlank()) { "A native Search result requires a package name" }
        }
    }
}

/** Where Search returns when it closes without activating a result. */
internal sealed interface LibrarySearchOriginFocusIdentity {
    data object SearchLauncher : LibrarySearchOriginFocusIdentity
    data class Result(val identity: LibrarySearchResultIdentity) : LibrarySearchOriginFocusIdentity
}

/** Focus identities owned by the later Search scope; none of these create another focus engine. */
internal sealed interface LibrarySearchFocusIdentity {
    data object Query : LibrarySearchFocusIdentity
    data object Genre : LibrarySearchFocusIdentity
    data object Platform : LibrarySearchFocusIdentity
    data object Clear : LibrarySearchFocusIdentity
    data class Result(val identity: LibrarySearchResultIdentity) : LibrarySearchFocusIdentity
}

@JvmInline
internal value class LibrarySearchGenreKey private constructor(
    val value: String,
) {
    companion object {
        fun from(raw: String?): LibrarySearchGenreKey? =
            raw?.let(::normalizeLibrarySearchGenre)
                ?.takeIf(String::isNotBlank)
                ?.let(::LibrarySearchGenreKey)
    }
}

internal data class LibrarySearchGenreOption(
    val key: LibrarySearchGenreKey,
    val label: String,
) {
    init {
        require(label.isNotBlank()) { "A Genre option requires a visible label" }
    }
}

internal sealed interface LibrarySearchGenreFilter {
    data object All : LibrarySearchGenreFilter
    data class Exact(val key: LibrarySearchGenreKey) : LibrarySearchGenreFilter

    companion object {
        fun exact(raw: String?): LibrarySearchGenreFilter =
            LibrarySearchGenreKey.from(raw)?.let(::Exact) ?: All
    }
}

internal sealed interface LibrarySearchPlatformFilter {
    data object All : LibrarySearchPlatformFilter
    data class Exact(val platformId: PlatformId) : LibrarySearchPlatformFilter
}

/** User-authored criteria. Query casing/spacing is retained for the input field; matching trims it. */
internal data class LibrarySearchCriteria(
    val query: String = "",
    val genre: LibrarySearchGenreFilter = LibrarySearchGenreFilter.All,
    val platform: LibrarySearchPlatformFilter = LibrarySearchPlatformFilter.All,
)

/**
 * Portable, provider-free Search state carried by Library launch/origin return targets.
 *
 * The DTO deliberately uses stable primitive/model identities rather than internal UI types so app-owned
 * recovery payloads can round-trip it without exposing the Search reducer. Values are sanitized against the
 * live lane only when the user explicitly opens Search again.
 */
data class LibrarySearchReturnSnapshot(
    val query: String = "",
    val genreKey: String? = null,
    val platformId: PlatformId? = null,
    val lastFocused: LibrarySearchReturnFocus = LibrarySearchReturnFocus.QUERY,
    val lastFocusedResultGameId: GameId? = null,
    val lastTopFocused: LibrarySearchReturnFocus = LibrarySearchReturnFocus.QUERY,
    val resultAnchorGameId: GameId? = null,
)

enum class LibrarySearchReturnFocus {
    QUERY,
    GENRE,
    PLATFORM,
    CLEAR,
    RESULT,
}

/** Validated internal state used only when an explicit Search open consumes a dormant snapshot. */
internal data class RestoredLibrarySearchState(
    val criteria: LibrarySearchCriteria,
    val lastFocused: LibrarySearchFocusIdentity,
    val lastTopFocused: LibrarySearchFocusIdentity,
    val resultScrollAnchor: LibrarySearchResultIdentity?,
)

/**
 * In-memory Search session for the upcoming Tome surface. Launch/process persistence remains a separate,
 * explicitly owed lane; this type only prevents stringly-typed origin/focus/scroll identities.
 */
internal data class LibrarySearchSession(
    val origin: LibrarySearchOrigin,
    val originFocus: LibrarySearchOriginFocusIdentity,
    val criteria: LibrarySearchCriteria = LibrarySearchCriteria(),
    val lastFocused: LibrarySearchFocusIdentity = LibrarySearchFocusIdentity.Query,
    val resultScrollAnchor: LibrarySearchResultIdentity? = null,
)

internal data class LibrarySearchFacets(
    val genres: List<LibrarySearchGenreOption>,
    val platforms: List<PlatformId>,
) {
    companion object {
        val Empty = LibrarySearchFacets(emptyList(), emptyList())
    }
}

internal fun captureLibrarySearchReturnSnapshot(
    criteria: LibrarySearchCriteria,
    lastFocused: LibrarySearchFocusIdentity,
    lastTopFocused: LibrarySearchFocusIdentity,
    resultScrollAnchor: LibrarySearchResultIdentity?,
): LibrarySearchReturnSnapshot = LibrarySearchReturnSnapshot(
    query = criteria.query,
    genreKey = (criteria.genre as? LibrarySearchGenreFilter.Exact)?.key?.value,
    platformId = (criteria.platform as? LibrarySearchPlatformFilter.Exact)?.platformId,
    lastFocused = lastFocused.toReturnFocus(),
    lastFocusedResultGameId = lastFocused.gameIdOrNull(),
    lastTopFocused = lastTopFocused.toReturnTopFocus(),
    resultAnchorGameId = (resultScrollAnchor as? LibrarySearchResultIdentity.Game)?.gameId,
)

internal fun LibrarySearchReturnSnapshot.toLibrarySearchCriteria(
    origin: LibrarySearchOrigin,
): LibrarySearchCriteria = LibrarySearchCriteria(
    query = query,
    genre = LibrarySearchGenreFilter.exact(genreKey),
    platform = platformId
        ?.takeIf { origin == LibrarySearchOrigin.FavoriteGames }
        ?.let(LibrarySearchPlatformFilter::Exact)
        ?: LibrarySearchPlatformFilter.All,
)

/**
 * Restores only live-valid state. This never opens Search: callers keep the result dormant until the next
 * explicit user open. Legacy payloads pass a null snapshot and retain their query-only behavior.
 */
internal fun restoreLibrarySearchReturnSnapshot(
    snapshot: LibrarySearchReturnSnapshot?,
    legacyQuery: String,
    origin: LibrarySearchOrigin,
    facets: LibrarySearchFacets,
    games: List<GameRow>,
): RestoredLibrarySearchState {
    val effective = snapshot ?: LibrarySearchReturnSnapshot(query = legacyQuery)
    val unvalidated = effective.toLibrarySearchCriteria(origin)
    val genre = (unvalidated.genre as? LibrarySearchGenreFilter.Exact)
        ?.key
        ?.takeIf { key -> facets.genres.any { it.key == key } }
        ?.let { key -> LibrarySearchGenreFilter.Exact(key) }
        ?: LibrarySearchGenreFilter.All
    val platform = (unvalidated.platform as? LibrarySearchPlatformFilter.Exact)
        ?.platformId
        ?.takeIf(facets.platforms::contains)
        ?.let(LibrarySearchPlatformFilter::Exact)
        ?: LibrarySearchPlatformFilter.All
    val validGameIds = games.asSequence().map(GameRow::gameId).toHashSet()
    val lastTop = effective.lastTopFocused.toTopFocus(origin)
    val criteria = LibrarySearchCriteria(
        query = effective.query,
        genre = genre,
        platform = platform,
    )
    val lastFocused = when (effective.lastFocused) {
        LibrarySearchReturnFocus.QUERY -> LibrarySearchFocusIdentity.Query
        LibrarySearchReturnFocus.GENRE -> LibrarySearchFocusIdentity.Genre
        LibrarySearchReturnFocus.PLATFORM -> if (origin == LibrarySearchOrigin.FavoriteGames) {
            LibrarySearchFocusIdentity.Platform
        } else {
            lastTop
        }
        LibrarySearchReturnFocus.CLEAR -> if (criteria != LibrarySearchCriteria()) {
            LibrarySearchFocusIdentity.Clear
        } else {
            lastTop
        }
        LibrarySearchReturnFocus.RESULT -> effective.lastFocusedResultGameId
            ?.takeIf(validGameIds::contains)
            ?.let { gameId ->
                LibrarySearchFocusIdentity.Result(
                    LibrarySearchResultIdentity.Game(gameId),
                )
            }
            ?: lastTop
    }
    val anchor = effective.resultAnchorGameId
        ?.takeIf(validGameIds::contains)
        ?.let { gameId -> LibrarySearchResultIdentity.Game(gameId) }
    return RestoredLibrarySearchState(
        criteria = criteria,
        lastFocused = lastFocused,
        lastTopFocused = lastTop,
        resultScrollAnchor = anchor,
    )
}

/**
 * The sole ordered game reducer for Library Search. It never sorts: repository order remains product truth.
 * Platform criteria are deliberately ignored in a fixed-platform origin and are applied only to cross-platform
 * Favorites/Games.
 */
internal fun reduceVisibleLibraryGames(
    orderedRows: List<GameRow>,
    origin: LibrarySearchOrigin,
    criteria: LibrarySearchCriteria,
    favoritesOnly: Boolean = false,
): List<GameRow> {
    val eligible = librarySearchBaseRows(orderedRows, origin, favoritesOnly)
    val query = criteria.query.trim()
    return eligible.filter { row ->
        (query.isEmpty() || row.title.contains(query, ignoreCase = true)) &&
            criteria.genre.matches(row.genre) &&
            criteria.platform.matches(row.platformId, origin)
    }
}

/**
 * Facets derive from the eligible lane before the active query/filter is applied, so choosing one facet never
 * destroys the other available choices. Ordering and display-label selection are input-order independent.
 */
internal fun deriveLibrarySearchFacets(
    orderedRows: List<GameRow>,
    origin: LibrarySearchOrigin,
    favoritesOnly: Boolean = false,
): LibrarySearchFacets {
    val eligible = librarySearchBaseRows(orderedRows, origin, favoritesOnly)
    val genres = eligible
        .mapNotNull { row ->
            val label = row.genre?.trim()?.replace(LIBRARY_SEARCH_WHITESPACE, " ")
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            LibrarySearchGenreKey.from(label)?.let { key -> key to label }
        }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .map { (key, labels) ->
            LibrarySearchGenreOption(
                key = key,
                label = labels.minWithOrNull(LIBRARY_SEARCH_LABEL_ORDER).orEmpty(),
            )
        }
        .sortedBy { it.key.value }
    val platforms = if (origin == LibrarySearchOrigin.FavoriteGames) {
        eligible.map(GameRow::platformId)
            .distinct()
            .sortedWith(LIBRARY_SEARCH_PLATFORM_ORDER)
    } else {
        emptyList()
    }
    return LibrarySearchFacets(genres = genres, platforms = platforms)
}

private fun librarySearchBaseRows(
    orderedRows: List<GameRow>,
    origin: LibrarySearchOrigin,
    favoritesOnly: Boolean,
): List<GameRow> = when (origin) {
    is LibrarySearchOrigin.PlatformGames -> orderedRows.filter { row ->
        row.platformId == origin.platformId && (!favoritesOnly || row.isFavorite)
    }
    LibrarySearchOrigin.FavoriteGames -> orderedRows.filter(GameRow::isFavorite)
}

private fun LibrarySearchGenreFilter.matches(rawGenre: String?): Boolean = when (this) {
    LibrarySearchGenreFilter.All -> true
    is LibrarySearchGenreFilter.Exact -> LibrarySearchGenreKey.from(rawGenre) == key
}

private fun LibrarySearchPlatformFilter.matches(
    platformId: PlatformId,
    origin: LibrarySearchOrigin,
): Boolean = when {
    origin is LibrarySearchOrigin.PlatformGames -> true
    this == LibrarySearchPlatformFilter.All -> true
    this is LibrarySearchPlatformFilter.Exact -> this.platformId == platformId
    else -> false
}

private fun LibrarySearchFocusIdentity.toReturnFocus(): LibrarySearchReturnFocus = when (this) {
    LibrarySearchFocusIdentity.Query -> LibrarySearchReturnFocus.QUERY
    LibrarySearchFocusIdentity.Genre -> LibrarySearchReturnFocus.GENRE
    LibrarySearchFocusIdentity.Platform -> LibrarySearchReturnFocus.PLATFORM
    LibrarySearchFocusIdentity.Clear -> LibrarySearchReturnFocus.CLEAR
    is LibrarySearchFocusIdentity.Result -> LibrarySearchReturnFocus.RESULT
}

private fun LibrarySearchFocusIdentity.gameIdOrNull(): GameId? =
    ((this as? LibrarySearchFocusIdentity.Result)?.identity as? LibrarySearchResultIdentity.Game)?.gameId

private fun LibrarySearchFocusIdentity.toReturnTopFocus(): LibrarySearchReturnFocus = when (this) {
    LibrarySearchFocusIdentity.Genre -> LibrarySearchReturnFocus.GENRE
    LibrarySearchFocusIdentity.Platform -> LibrarySearchReturnFocus.PLATFORM
    else -> LibrarySearchReturnFocus.QUERY
}

private fun LibrarySearchReturnFocus.toTopFocus(
    origin: LibrarySearchOrigin,
): LibrarySearchFocusIdentity = when (this) {
    LibrarySearchReturnFocus.GENRE -> LibrarySearchFocusIdentity.Genre
    LibrarySearchReturnFocus.PLATFORM -> if (origin == LibrarySearchOrigin.FavoriteGames) {
        LibrarySearchFocusIdentity.Platform
    } else {
        LibrarySearchFocusIdentity.Query
    }
    else -> LibrarySearchFocusIdentity.Query
}

private fun normalizeLibrarySearchGenre(value: String): String =
    value.trim()
        .replace(LIBRARY_SEARCH_WHITESPACE, " ")
        .lowercase(Locale.ROOT)

private val LIBRARY_SEARCH_WHITESPACE = Regex("\\s+")
private val LIBRARY_SEARCH_LABEL_ORDER =
    compareBy<String> { it.lowercase(Locale.ROOT) }.thenBy { it }
private val LIBRARY_SEARCH_PLATFORM_ORDER =
    compareBy<PlatformId> { it.value.lowercase(Locale.ROOT) }.thenBy { it.value }

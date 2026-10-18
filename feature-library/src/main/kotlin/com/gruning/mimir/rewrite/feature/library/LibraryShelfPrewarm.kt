package com.gruning.mimir.rewrite.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirFocusTraversal
import com.gruning.mimir.rewrite.core.ui.mimirArtWarmInMemory
import com.gruning.mimir.rewrite.core.ui.mimirCoverRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/*
 * Library landing prewarm. The settle gate
 * ([awaitLibraryGamelistSettled]) holds a platform open for up to MimirPageMotion.SettleCapMs while the landing card
 * and two either side decode. These two passes decode that same window BEFORE the press, through the same request
 * ([mimirCoverRequest]) and so under the same cache key, so the gate finds the covers in memory and the page moves at
 * once.
 *
 * Decode budget (desk numbers, not device-measured): an INEXACT cover of about 500x700 decodes in roughly 20-30 ms on
 * the Unisoc T618's A75 cores and 5-10 ms on a Snapdragon 8 Gen 2, and holds about 1.4 MB of the 30% memory cache.
 *  - Dwell: at most [LibraryDwellPrewarmCovers] decodes (the gate's own window) for the platform the shelf rests on for
 *    [LibraryDwellPrewarmMs] with no traverse streaming; one at a time, one platform in flight, cancelled by the next
 *    move. About 100-150 ms of one worker on the T618.
 *  - Idle: once per Library visit, after the cold-start ledger settles and [LibraryIdleWarmQuietMs] of quiet: landing
 *    +-[LibraryIdleWarmReach] for the platforms within [LibraryIdleWarmPlatformReach] of the centred one, nearest first,
 *    at most [LibraryIdleWarmCoverCap] decodes, and never once the memory cache is [LibraryIdleWarmCacheFillLimit]
 *    full, so it cannot evict the warm set Home and the landing rely on. It holds while a traverse streams or a
 *    gamelist is open.
 * Rows come only from the view model's idle row cache: a platform whose rows have not landed is skipped, never queried.
 */

internal const val LibraryDwellPrewarmMs = 250L
internal const val LibraryDwellPrewarmCovers = LibraryGamelistSettleReach * 2 + 1
internal const val LibraryIdleWarmQuietMs = 1_500L
internal const val LibraryIdleWarmReach = 1
internal const val LibraryIdleWarmPlatformReach = 2
internal const val LibraryIdleWarmCoverCap = 15
internal const val LibraryIdleWarmCacheFillLimit = 0.5
private const val LibraryPrewarmRecheckMs = 100L
private const val LibraryShelfKeyPrefix = "library/shelf/"

@Composable
internal fun LibraryShelfPrewarm(viewModel: LibraryViewModel, authority: FocusAuthority) {
    val context = LocalContext.current.applicationContext
    // Both passes read focus inside their effects, never in composition: a D-pad move recomposes nothing here (PERF-G).
    // Dwell. collectLatest cancels the previous platform's pass on every move: one platform in flight.
    LaunchedEffect(viewModel, authority) {
        combine(authority.state, viewModel.openPlatform) { focus, open ->
            libraryShelfDwellPlatform(focus.scope == LibraryShelfScope, focus.focused?.value, open)
        }
            .distinctUntilChanged()
            .collectLatest { platform ->
                if (platform == null) return@collectLatest
                delay(LibraryDwellPrewarmMs)
                // A traverse that has just stopped still reads as streaming; wait it out rather than warm in passing.
                while (MimirFocusTraversal.streaming) delay(LibraryPrewarmRecheckMs)
                val favoritesOnly = viewModel.favoritesOnly.value
                val uris = libraryPrewarmCoverUris(
                    cachedRows = viewModel.cachedPlatformRows(platform),
                    platform = platform,
                    favoritesOnly = favoritesOnly,
                    landing = viewModel.rememberedGamelistFocusFor(platform, favoritesOnly),
                    listView = viewModel.gamelistViewMode.value == GamelistViewMode.LIST,
                )
                val loader = context.imageLoader
                for (uri in uris) {
                    if (!mimirArtWarmInMemory(context, uri)) loader.execute(mimirCoverRequest(context, uri))
                }
            }
    }
    // Idle. Once per Library visit, after the cold-start ledger settles.
    LaunchedEffect(viewModel, authority) {
        WarmupLedger.settled.first { it }
        delay(LibraryIdleWarmQuietMs)
        val loader = context.imageLoader
        val platforms = viewModel.platforms.first { it.isNotEmpty() }.map { it.platformId }
        val centre = authority.state.value.let { focus ->
            libraryShelfDwellPlatform(focus.scope == LibraryShelfScope, focus.focused?.value, openPlatform = null)
        }
        var decoded = 0
        for (platform in libraryIdleWarmPlatformOrder(platforms, centre)) {
            val favoritesOnly = viewModel.favoritesOnly.value
            val uris = libraryPrewarmCoverUris(
                cachedRows = viewModel.cachedPlatformRows(platform),
                platform = platform,
                favoritesOnly = favoritesOnly,
                landing = viewModel.rememberedGamelistFocusFor(platform, favoritesOnly),
                reach = LibraryIdleWarmReach,
                listView = viewModel.gamelistViewMode.value == GamelistViewMode.LIST,
            )
            for (uri in uris) {
                val cache = loader.memoryCache
                if (!libraryIdleWarmHasBudget(decoded, cache?.size, cache?.maxSize)) return@LaunchedEffect
                // Never compete with the player's own decodes: hold while a traverse streams or a gamelist is open.
                while (MimirFocusTraversal.streaming || viewModel.openPlatform.value != null) delay(LibraryIdleWarmQuietMs)
                if (mimirArtWarmInMemory(context, uri)) continue
                loader.execute(mimirCoverRequest(context, uri))
                decoded++
            }
        }
    }
}

/** The platform the shelf rests on, or null when the gamelist, the add pill or anything off the platform shelf has focus. */
internal fun libraryShelfDwellPlatform(onShelf: Boolean, focusedKey: String?, openPlatform: PlatformId?): PlatformId? {
    if (!onShelf || openPlatform != null || focusedKey == null) return null
    if (!focusedKey.startsWith(LibraryShelfKeyPrefix)) return null
    val id = focusedKey.removePrefix(LibraryShelfKeyPrefix)
    return if (id.isBlank() || id == "add") null else PlatformId(id)
}

/** The landing card and [reach] either side, as the gamelist will order them. At the gate's reach this IS its window. */
internal fun libraryPrewarmWindow(rows: List<GameRow>, landing: GameId?, reach: Int): List<GameRow> {
    if (rows.isEmpty()) return emptyList()
    val centre = rows.indexOfFirst { it.gameId == landing }.takeIf { it >= 0 } ?: 0
    return rows.subList((centre - reach).coerceAtLeast(0), (centre + reach + 1).coerceAtMost(rows.size))
}

/**
 * The cover uris the settle gate will await when [platform] opens: its cached rows reduced exactly as the opened
 * gamelist reduces them (search criteria reset on open, the current favourites lane), windowed on the remembered
 * landing, mapped through [libraryGamelistSettleCoverUri] for the view it opens in, so what is warmed is what is shown:
 * the face the game shelf paints for each ([shelfFaceOf], which never paints a disc for a missing cover) in CAROUSEL, and in LIST
 * ([listView]) the preview panel's one cover by the list row's own ladder ([libraryListPreviewMedia]), which does.
 */
internal fun libraryPrewarmCoverUris(
    cachedRows: List<GameRow>?,
    platform: PlatformId,
    favoritesOnly: Boolean,
    landing: GameId?,
    reach: Int = LibraryGamelistSettleReach,
    listView: Boolean = false,
): List<String> {
    if (cachedRows.isNullOrEmpty()) return emptyList()
    val visible = reduceVisibleLibraryGames(
        orderedRows = cachedRows,
        origin = LibrarySearchOrigin.PlatformGames(platform),
        criteria = LibrarySearchCriteria(),
        favoritesOnly = favoritesOnly,
    )
    // The list paints one cover, the landing game's, in its preview panel; the shelf paints a window of them.
    return libraryPrewarmWindow(visible, landing, if (listView) 0 else reach).mapNotNull { libraryGamelistSettleCoverUri(it, listView) }
}

/** The idle pass's order: the platforms within [reach] of [centre], nearest first, right before left, wrapping. */
internal fun libraryIdleWarmPlatformOrder(
    platforms: List<PlatformId>,
    centre: PlatformId?,
    reach: Int = LibraryIdleWarmPlatformReach,
): List<PlatformId> {
    if (platforms.isEmpty()) return emptyList()
    val size = platforms.size
    val c = platforms.indexOf(centre).takeIf { it >= 0 } ?: 0
    val order = LinkedHashSet<PlatformId>()
    for (step in 1..reach) {
        order += platforms[Math.floorMod(c + step, size)]
        order += platforms[Math.floorMod(c - step, size)]
    }
    order -= platforms[c]
    return order.toList()
}

/** Whether the idle pass may decode one more cover: under its cap, and the memory cache under its fill limit. */
internal fun libraryIdleWarmHasBudget(decoded: Int, cacheSize: Int?, cacheMaxSize: Int?): Boolean {
    if (decoded >= LibraryIdleWarmCoverCap) return false
    if (cacheSize == null || cacheMaxSize == null || cacheMaxSize <= 0) return true
    return cacheSize < cacheMaxSize * LibraryIdleWarmCacheFillLimit
}

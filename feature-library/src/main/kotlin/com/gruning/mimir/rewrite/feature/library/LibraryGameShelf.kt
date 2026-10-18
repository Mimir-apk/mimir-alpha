package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.SuccessResult
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusState
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import com.gruning.mimir.rewrite.core.ui.LocalMimirPageArrived
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirIntentText
import com.gruning.mimir.rewrite.core.ui.MimirTextFragment
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.mimirCoverRequest
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion

/**
 * The Library gamelist's CAROUSEL view: the Home shelf ([GameShelf]) over the platform's games, with Home's title block above it. The card style code it replaced is
 * gone; what is left here is what only the Library has: its games and their focus keys, its loader and empty copy, the departure and
 * the hide's shatter, and the body it sits in.
 *
 * The title block carries what the old destination lane did, in Home's one pattern: the game's title, its launch status chip and its
 * meta line (genre, launcher, last played) as the eyebrow. There is no label under a tile and no lane under the strip; Y opens the
 * media menu on the focused game (Customize media, Add to Tome and Achievements, the Edit menu's own list) and SELECT marks it a
 * favourite. Each tile carries two on-tile marks (the favourite rune and the disc badge) and no
 * platform plate: a gamelist is one platform and the header names it (07-18).
 *
 * ### Focus + input (single authority)
 * The whole ordered game row is registered virtually ([RegisterVirtualFocusRow]) under [GamelistScope], so LEFT/RIGHT can request any
 * game without coupling focus identity to the mounted window, and the keys are the ones the LIST view registers (`library/gamelist/<id>`),
 * so toggling views keeps focus. There is NO Compose `focusable()`; a tap requests the tile's key and opens the game, as before.
 * [centeredKey] and [getArtFocused] decide the SELECTED seat exactly as they decided the old centre ([gamelistCentredIndex], which the
 * departure tracker reads as well): while the media action holds focus the game it acts on stays the selection.
 *
 * @param windowMemoryKey which state of the Library's shelf memory this is ([libraryShelfWindowMemoryKey]: the platform, the favourites filter
 *   and whether a search is typed), so L2/R2 between platforms puts each shelf back in the seat it was left in
 *   ([LibraryGameShelfWindowCapacity] states are remembered).
 * @param departureFrame the departure the screen has seated for a game the player just sent away, and the shatter stage for
 *   a hide; both null for every other caller.
 */
@Composable
internal fun LibraryGameShelf(
    games: List<GameRow>,
    emptyMessage: String,
    isLoading: Boolean,
    searchQuery: String,
    authority: FocusAuthority,
    focusState: FocusState,
    centeredKey: FocusKey?,
    getArtFocused: Boolean,
    detailGame: GameRow?,
    metaForGame: (GameRow) -> String?,
    statusForGame: (GameRow) -> GameStatusChip?,
    onOpenGame: (GameRow) -> Unit,
    /** Localized readable chrome when a light palette is laid directly over arbitrary Hero media. */
    mediaContrastRail: Boolean,
    /** Depth of the bottom-right control lane (M button, L1/R1 hint): the strip stands above it. */
    bottomControlInset: Dp,
    windowMemoryKey: Any,
    departureFrame: GamelistDepartureFrame?,
    shatter: LibraryShatterStage?,
    modifier: Modifier = Modifier,
    /**
     * The whole Search the list is under: the query, the genre and the platform. The strip is started afresh by any of them (see the `key` below), so a narrowed list seats itself where
     * its one remembered Search state says instead of gliding from where the wider list left the strip. Defaulted for the hosts that have no Search.
     */
    searchCriteria: LibrarySearchCriteria = LibrarySearchCriteria(),
    /**
     * The keys of [games], made once per list (audit 2026-10-14, RC-45: no per-step `games.map(::gameKey)` or `firstOrNull`). The screen hands
     * over the index it already remembers; a host that has none gets one remembered on the list here.
     */
    gameKeys: GamelistKeyIndex = rememberGamelistKeyIndex(games),
) {
    val palette = LocalMimirPalette.current

    if (games.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // while the platform's games are still loading (the ~1s Room-query beat after a
            // switch/open), HOLD a quiet loader instead of flashing "No games…"/stale covers. The empty message
            // reads only once the list has truly landed empty (isLoading = gamesLoadedForPlatform != openPlatform).
            if (isLoading) {
                CircularProgressIndicator(
                    color = palette.accentBright,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(30.dp),
                )
            } else {
                MimirIntentText(
                    intent = if (searchQuery.isNotBlank()) {
                        libraryAroundAuthored(libraryResources(), R.string.library_gamelist_empty_search, searchQuery.trim())
                    } else {
                        MimirTextIntent.StaticChrome(emptyMessage)
                    },
                    style = MimirTypeScale.MicroStatus,
                    color = palette.textMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }

    // Register the whole list virtually so requestFocus can land on an offscreen game while the mounted
    // window remains bounded. Focus identity and visual mounting therefore remain independent.
    val focusKeys = gameKeys.keys
    RegisterVirtualFocusRow(
        authority = authority,
        scope = GamelistScope,
        keys = focusKeys,
        // Incoming identities register in authoritative centre-first order. This prevents FocusAuthority
        // from temporarily adopting game one before the remembered key exists on a warm return/mutation.
        preferredRegistrationKey = centeredKey?.takeIf { it in gameKeys },
        // A platform switch replaces every key in this row while it stays mounted. Without this, the
        // departed key's geometric fallback re-homed focus onto the same INDEX in the new platform's games
        // and the landing correction was then suppressed as a "user landing". See the tracker's own note.
        clearsFocusOnIdentityReplacement = true,
    )

    // Which game is selected. While the artwork action is focused its detail game stays the selection; on a platform switch / first
    // open the focus briefly isn't a game key yet and index 0 is the intended landing. The departure tracker asks the very same
    // question (gamelistCentredIndex), so the two can never disagree about which game was leaving.
    val selectedIndex = gamelistCentredIndex(gameKeys, centeredKey, getArtFocused, detailGame)
    val destinationGame = games[selectedIndex]
    val shelfItems = remember(games) { games.map(::libraryGameShelfItem) }
    val departure = departureFrame?.departure
    val shelfDeparture = remember(departure) { departure?.toGameShelfDeparture() }
    val reducedMotion = mimirReducedMotion()
    val pageArrived = LocalMimirPageArrived.current
    val rootProfile = LocalMimirViewportProfile.current
    val compact = rootProfile.widthDp > 0f && rootProfile.isCompact
    MeasurePlatformCoverAspect(destinationGame)

    // Laid out as wide as the screen, past the Column's padding: the seats then stand where Home's do, and the bleed is inside this
    // node's bounds, so a touch there is this shelf's.
    Box(modifier.fillMaxSize().gamelistPageBleed(horizontal = LibraryGamelistBodyPaddingDp)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val bodyHeight = maxHeight.value
            val screenHeight = rootProfile.heightDp.takeIf { it > 0f } ?: (bodyHeight + LibraryShelfHeaderAllowanceDp)
            val geometry = remember(maxWidth.value, bodyHeight, screenHeight, bottomControlInset.value, compact) {
                solveLibraryGameShelfGeometry(
                    widthDp = maxWidth.value,
                    heightDp = bodyHeight,
                    screenHeightDp = screenHeight,
                    bottomLaneDp = bottomControlInset.value,
                    titleLeftDp = LibraryGamelistBodyPaddingDp.value,
                    compact = compact,
                )
            }
            // A search is ONE remembered state however long the query grows ([libraryShelfWindowMemoryKey]), so the memory cannot tell one typed prefix from the next; the strip
            // is started afresh by the whole Search instead (the query's text, the genre, the platform: what the memory key calls "searching" is any of them), and seats itself from
            // that one state's memory, so narrowing a search never glides the strip from where the wider list left it.
            val shelfState = key(searchCriteria) {
                rememberGameShelfState(
                    items = shelfItems,
                    selectedKey = focusKeys[selectedIndex],
                    geometry = geometry,
                    windowMemory = LibraryGameShelfWindowMemory.memory,
                    windowMemoryKey = windowMemoryKey,
                    // One epoch: the Library's states are told apart by [windowMemoryKey], and a tile is keyed on its game.
                    surfaceEpoch = 0,
                    reducedMotion = reducedMotion,
                    ids = LibraryGameShelfIds,
                    departure = shelfDeparture,
                )
            }
            // Home's title block, as Home draws it: eyebrow, title and status chip in the one rail above the strip.
            HomeShelfTitle(
                title = destinationGame.title,
                eyebrow = metaForGame(destinationGame),
                geometry = geometry,
                mediaBackdropAvailable = mediaContrastRail,
                statusChip = statusForGame(destinationGame),
                stackStatus = compact,
            )
            GameShelf(
                state = shelfState,
                scope = GamelistScope,
                authority = authority,
                focusState = focusState,
                onRequestFocus = { key -> authority.requestFocus(key) },
                onActivate = { item -> gameKeys.gameFor(item.focusKey)?.let(onOpenGame) },
                emptyContent = { },
                // The plate is Home's: a gamelist is one platform and its header names it .
                showShelfmark = false,
                // A game that is leaving is mid-dissolve: the strip is not the player's to move yet.
                touchEnabled = { shelfDeparture == null },
                // The gleam waits for the page to arrive (motion audit batch C), so its sweeps are seen, not spent behind the
                // departing view.
                ringGleamArmed = { pageArrived },
                paintMarks = true,
                shatter = shatter,
            )
            // The destroyed game, over the tiles and under what must stay above them.
            LibraryShatterHost(shatter)
        }
    }
}

/**
 * Teaches a platform nobody curated its cover proportion, from the first automatic cover that decodes (GL-RP5 rev B): the store is
 * first-write-wins and persisted, and the LIST view's preview panel, the launch card and the Bottom's platform shelf size their
 * covers by it. The old cards measured every cover they painted; the shelf paints a square seat and knows no proportion, so the
 * selected game's cover is asked for here (the tile's own request, so a memory-cache hit), and only while the platform is unsettled
 * and the cover is the provider's automatic one ([gamelistArtMayMeasurePlatform]: never a face the player wired by hand).
 */
@Composable
private fun MeasurePlatformCoverAspect(game: GameRow) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { MeasuredCoverAspects.ensureLoaded(context) }
    val media = libraryListPreviewMedia(game)
    val uri = media.artUri
    val measurable = uri != null && gamelistArtMayMeasurePlatform(game, media) && !platformCoverAspectIsSettled(game.platformId.value)
    LaunchedEffect(uri, measurable) {
        if (!measurable || uri == null) return@LaunchedEffect
        val drawable = (context.imageLoader.execute(mimirCoverRequest(context, uri)) as? SuccessResult)?.drawable
            ?: return@LaunchedEffect
        MeasuredCoverAspects.record(
            context,
            game.platformId.value,
            drawable.intrinsicWidth.toFloat(),
            drawable.intrinsicHeight.toFloat(),
        )
    }
}

/** The headroom the Library keeps above the shelf's body when the screen's own height is not provided: the header and the Column's spacing. */
private const val LibraryShelfHeaderAllowanceDp = 96f

/**
 * How many shelf states the Library remembers: a platform, the favourites filter or a search each is one, and L2/R2 between platforms
 * must put each back in the seat it was left in. A search is ONE state however long the query grows ([libraryShelfWindowMemoryKey]), so
 * typing never pushes a browsed platform out.
 */
internal const val LibraryGameShelfWindowCapacity = 32

/**
 * Which state of the Library's shelf memory the screen is in: the platform, the favourites filter, and whether a search is active ([searching]: a query typed,
 * or a genre or platform filter chosen in Search). The query's TEXT is not part of it: a state per typed prefix (seven letters, seven entries) crowded the
 * platforms out of the memory. The screen says it as `criteria.query.isNotBlank() || criteria != LibrarySearchCriteria()`.
 */
internal fun libraryShelfWindowMemoryKey(openPlatform: PlatformId, favoritesOnly: Boolean, searching: Boolean): Any =
    Triple(openPlatform, favoritesOnly, searching)

/** The Library's own shelf-window memory (never Home's, so a Library visit cannot displace Home's seat), one state per [windowMemoryKey]. */
internal object LibraryGameShelfWindowMemory {
    val memory = GameShelfWindowMemory(capacity = LibraryGameShelfWindowCapacity)
}

/** The tags, semantics keys and animation labels the Library's shelf wears. Built once: each build mints its own semantics keys. */
internal val LibraryGameShelfIds = GameShelfIds("library")

/** The tag of [game]'s tile on the Library's shelf; a game that has just left the list is found under [libraryShelfLeavingTileTestTag]. */
internal fun libraryShelfTileTestTag(game: GameRow): String = LibraryGameShelfIds.tilePrefix + gameKey(game).value

internal fun libraryShelfLeavingTileTestTag(game: GameRow): String = LibraryGameShelfIds.leavingTilePrefix + gameKey(game).value

/**
 * One Library game as a shelf item: its face (the same one Home would paint, [shelfFaceOf]), the two on-tile marks, and no platform
 * plate. A game whose cover is not known to be absent yet paints nothing rather than the book ([GameShelfItem.Game.artPending]).
 */
internal fun libraryGameShelfItem(game: GameRow): GameShelfItem.Game {
    val face = shelfFaceOf(game)
    return GameShelfItem.Game(
        focusKey = gameKey(game),
        title = game.title,
        face = face,
        platformIdValue = "",
        marks = GameShelfMarks(favorite = game.isFavorite, collapseBadge = collapseBadge(game)),
        stableId = game.gameId.value,
        artPending = face.shape == ShelfFaceShape.NONE && !game.artResolutionSettled,
    )
}

/**
 * The departure the screen seated, told to the shelf in seats. A dissolve paints the leaving tile at its old seat on the
 * departure's progress and the gap is the same number; a shatter paints no tile (the overlay breaks the picture of it) and
 * holds the ring back until the gap has shut. The gap and the presence are read in layout and draw lambdas only.
 */
internal fun GamelistDeparture.toGameShelfDeparture(): GameShelfDeparture {
    val departure = this
    val dissolve = kind == GamelistDepartureKind.Dissolve
    return GameShelfDeparture(
        item = libraryGameShelfItem(game),
        index = index,
        paintsLeavingTile = dissolve,
        gap = { departure.gap() },
        presence = if (dissolve) {
            { 1f - departure.progress.value }
        } else {
            { 0f }
        },
        ringReveal = if (dissolve) null else { { 1f - departure.gap() } },
    )
}

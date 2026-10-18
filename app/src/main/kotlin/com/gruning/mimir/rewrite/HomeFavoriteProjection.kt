package com.gruning.mimir.rewrite

import android.content.res.Resources
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import com.gruning.mimir.rewrite.core.resolver.library.PlatformLabels
import com.gruning.mimir.rewrite.feature.library.HomeShelfEntry
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.HomeShelfTome
import com.gruning.mimir.rewrite.feature.library.HomeTomePreview
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioPageSize
import com.gruning.mimir.rewrite.feature.library.NativeShelfRow
import com.gruning.mimir.rewrite.feature.library.TomeId
import com.gruning.mimir.rewrite.feature.library.homeShelfCoverUri
import java.util.Locale
import java.text.Normalizer

/**
 * The Home shelf's own Tome, in English, for the two projections below that must name every [TomeId] to be exhaustive. Neither can
 * produce it (the Home shelf's Tome is never a member of a catalogue: [rememberHomeShelfProjection] builds it from the live shelf and
 * titles it `app_tome_home_shelf_title` in the player's language), so this value is never drawn. Sentence case, like every title.
 */
internal const val HomeShelfTomeFallbackTitle = "Home shelf"

/** The Home shelf's own Tome: [title] in the player's language (`app_tome_home_shelf_title`), the first four members' previews, and their count. */
internal fun homeOverviewTome(
    title: String,
    memberIds: List<FavoriteMemberId>,
    rowsById: Map<HomeShelfItemId, HomeShelfRow>,
): HomeShelfTome = HomeShelfTome(
    tomeId = TomeId.HomeOverview,
    title = title,
    previews = memberIds.mapNotNull { rowsById[it.toHomeShelfItemId()]?.toHomeTomePreviewOrNull() }.take(4),
    memberCount = memberIds.size,
)

/**
 * A closed platform Tome is titled with the platform's full label in authored case, as a custom Tome is. The
 * acronym table that used to compact it ("GCN", "DC", upper-cased) is gone with policy R1: Main and the
 * Bottom name a platform the same way. The non-compact book cover wraps the title to two lines.
 */
@Suppress("UNUSED_PARAMETER")
internal fun platformTomeTitle(platformId: PlatformId, label: String): String = label

data class FavoriteHomeGame(
    val id: FavoriteMemberId,
    val row: HomeShelfRow,
    val platformId: PlatformId? = null,
    /**
     * Launch recency belongs to the loose Editor's Choice projection only. Tome ordering remains governed
     * by its explicit/manual document rules, so sorting the root shelf cannot reshuffle a user's book.
     * Both emulated and Android-game rows receive the same durable successful-dispatch clock.
     */
    val lastPlayedMillis: Long? = null,
)

/** One atomic, render-ready Home transaction plus the normalized durable Tome state it came from. */
data class FavoriteHomeProjection(
    val document: HomeTomeDocument,
    val games: List<FavoriteHomeGame>,
    val entries: List<HomeShelfEntry>,
    val tomes: Map<TomeId, HomeShelfTome>,
    val eligibleMembersByTome: Map<TomeId, List<FavoriteMemberId>>,
    val eligibleMemberSlotsByTome: Map<TomeId, List<FavoriteMemberId?>>,
) {
    val rows: List<HomeShelfRow> = games.map(FavoriteHomeGame::row)
    val eligibleIds: Set<FavoriteMemberId> = games.mapTo(linkedSetOf(), FavoriteHomeGame::id)

    fun launchableRow(memberId: FavoriteMemberId): HomeShelfRow? =
        games.firstOrNull { it.id == memberId }?.row
}

/**
 * Root projection for the loose Home shelf.  A custom Tome is an alternate Home placement, not a
 * Favorite mutation: its members stay launchable in Library and may appear in any number of Tomes.
 * With copy-like membership they also stay on this loose lane: adding a game
 * to a Tome copies it there, like a playlist, instead of taking it off Editor's Choice.
 */
internal fun favoriteEditorChoiceEntries(projection: FavoriteHomeProjection): List<HomeShelfEntry> {
    return orderedLooseHomeGames(projection).map { HomeShelfEntry.Game(it.row.itemId) }
}

internal fun orderedLooseHomeGames(projection: FavoriteHomeProjection): List<FavoriteHomeGame> {
    // Every Favorite is loose (OR-1): Tome membership no longer takes a game off this lane. The latest played
    // title leads, then A-Z, exactly as before.
    val recent = looseHomeRecentGame(projection.games)
    return buildList {
        recent?.let(::add)
        projection.games.asSequence().filterNot { it === recent }
            .map(::LooseHomeSortKey)
            .sortedWith(looseHomeTitleComparator)
            .mapTo(this) { it.game }
    }
}

/**
 * The Tome membership mark: the words beside a focused Editor's Choice game
 * that also lives in custom Tomes. "In {Tome}" for one, "In {n} Tomes" for several, null for none. The name is the
 * player's own, as typed. Platform Tomes never count: every game has one.
 */
internal fun homeTomeMarkLabel(res: Resources, document: HomeTomeDocument, member: FavoriteMemberId?): String? {
    if (member == null) return null
    val tomes = document.customTomes.filter { member in it.members }
    return when (tomes.size) {
        0 -> null
        1 -> res.getString(R.string.app_home_tome_mark_one, tomes.single().label)
        else -> res.getQuantityString(R.plurals.app_home_tome_mark_count, tomes.size, tomes.size)
    }
}

internal fun looseHomeRecentGame(games: List<FavoriteHomeGame>): FavoriteHomeGame? = games
    .filter { it.lastPlayedMillis != null && it.lastPlayedMillis > 0L }
    .maxWithOrNull(compareBy<FavoriteHomeGame> { it.lastPlayedMillis }.thenBy { it.row.title })

/** Case/diacritic-insensitive Home alphabet bucket; digits and symbols share the # bucket. */
internal fun looseHomeAlphabetBucket(title: String): String {
    val normalized = Normalizer.normalize(title.trim(), Normalizer.Form.NFD)
        .replace(CombiningMarks, "")
    val first = normalized.firstOrNull { !it.isWhitespace() } ?: return "#"
    val folded = first.uppercaseChar()
    return if (folded in 'A'..'Z') folded.toString() else "#"
}

/**
 * Travels the occupied alphabet buckets as a ring around the optional recent tile. The recent tile
 * is a real terminal stop: final bucket -> recent -> first bucket (and the reverse for L2).
 */
internal fun looseHomeAlphabetJumpIndex(
    games: List<FavoriteHomeGame>,
    currentIndex: Int,
    forward: Boolean,
    recentItemId: FavoriteMemberId? = null,
): Int? {
    val buckets = games.map { game ->
        if (game.id == recentItemId) null else looseHomeAlphabetBucket(game.row.title)
    }
    val currentBucket = buckets.getOrNull(currentIndex)
    val occupied = buckets.filterNotNull().distinct().sorted()
    if (occupied.isEmpty()) return null
    val recentIndex = recentItemId?.let { id -> games.indexOfFirst { it.id == id } }
        ?.takeIf { it >= 0 }
    val target = when {
        currentBucket == null && forward -> buckets.indexOf(occupied.first())
        currentBucket == null -> buckets.indexOf(occupied.last())
        forward -> occupied.firstOrNull { it > currentBucket }?.let(buckets::indexOf) ?: recentIndex
        else -> occupied.lastOrNull { it < currentBucket }?.let(buckets::indexOf) ?: recentIndex
    }
    return target?.takeIf { it >= 0 && it != currentIndex }
}

/**
 * The combining marks a title's diacritics decompose into. Compiled ONCE: the sort below folds every title with it, and the
 * alphabet jump folds one per game per press. The comparator this replaced compiled it twice per comparison (about 1,300
 * compiles for 100 favourites), on the UI thread, at every Home open and every favourites change (RC-48, audit 2026-10-14).
 */
private val CombiningMarks = Regex("\\p{M}+")

private fun foldedLooseTitle(title: String): String =
    Normalizer.normalize(title, Normalizer.Form.NFD).replace(CombiningMarks, "").lowercase(Locale.ROOT)

/** A game with its three sort keys worked out ONCE, so the sort compares strings and folds nothing. */
private class LooseHomeSortKey(val game: FavoriteHomeGame) {
    val folded: String = foldedLooseTitle(game.row.title)
    val lowered: String = game.row.title.lowercase(Locale.ROOT)
    val itemKey: String = game.row.itemId.toString()
}

private val looseHomeTitleComparator = compareBy<LooseHomeSortKey>({ it.folded }, { it.lowered }, { it.itemKey })

/** Every Tome the browser may expose, independent of whether it currently occupies a Home seat. */
data class FavoriteHomeTomeCatalog(
    val tomes: Map<TomeId, HomeShelfTome>,
    val eligibleMembersByTome: Map<TomeId, List<FavoriteMemberId>>,
    val eligibleMemberSlotsByTome: Map<TomeId, List<FavoriteMemberId?>>,
)

fun favoriteHomeTomeCatalog(
    games: List<FavoriteHomeGame>,
    document: HomeTomeDocument,
    platformLabels: Map<PlatformId, String> = emptyMap(),
): FavoriteHomeTomeCatalog {
    val canonicalGames = games.distinctBy(FavoriteHomeGame::id)
    val eligibleById = canonicalGames.associateBy(FavoriteHomeGame::id)
    val overlays = document.platformOrderOverlays.associateBy(PlatformTomeOrderOverlay::platformId)
    val customById = document.customTomes.associateBy(CustomHomeTome::id)
    val memberSlots = linkedMapOf<TomeId, List<FavoriteMemberId?>>()
    val platformGroups = canonicalGames.filter { it.platformId != null }
        .groupBy { TomeId.Platform(requireNotNull(it.platformId)) }
    val orderedPlatformIds = platformLabels.keys.map { platformId -> TomeId.Platform(platformId) } +
        platformGroups.keys.filterNot { it.platformId in platformLabels }
    orderedPlatformIds.distinct().forEach { tomeId ->
        platformGroups[tomeId]?.let { platformGames ->
            val overlay = overlays[tomeId.platformId]
            val ordered = orderPlatformTomeMembers(
                platformGames,
                overlay,
            ).map(FavoriteHomeGame::id)
            val storedSlots = overlay?.gameSlots.orEmpty().map { gameId ->
                gameId?.let { FavoriteMemberId.Emulated(it) }
            }
            memberSlots[tomeId] = projectSparseTomeSlots(ordered, storedSlots)
        }
    }
    document.customTomes.forEach { tome ->
        memberSlots[tome.id] = projectSparseTomeSlots(
            orderCustomTomeMembers(canonicalGames, tome),
            tome.memberSlots,
        )
    }
    val members = memberSlots.mapValues { (_, slots) -> slots.filterNotNull() }
    return FavoriteHomeTomeCatalog(
        tomes = members.mapValues { (tomeId, memberIds) ->
            val rows = memberIds.mapNotNull(eligibleById::get)
            HomeShelfTome(
                tomeId = tomeId,
                title = when (tomeId) {
                    is TomeId.Platform -> platformTomeTitle(
                        platformId = tomeId.platformId,
                        label = platformLabels[tomeId.platformId]
                            ?: PlatformLabels.labelFor(tomeId.platformId),
                    )
                    is TomeId.Custom -> customById.getValue(tomeId).label
                    TomeId.HomeOverview -> HomeShelfTomeFallbackTitle
                },
                previews = rows.mapNotNull { it.row.toHomeTomePreviewOrNull() }.take(4),
                memberCount = rows.size,
            )
        },
        eligibleMembersByTome = members,
        eligibleMemberSlotsByTome = memberSlots,
    )
}

fun favoriteHomeGames(
    emulatedFavorites: List<GameRow>,
    nativeGames: List<NativeShelfRow>,
    nativeLastPlayedMillis: Map<AndroidPackageName, Long> = emptyMap(),
): List<FavoriteHomeGame> = buildList {
    // observeFavorites is the Favorite order authority. Index is the primary stable tie-breaker; the
    // identity key makes duplicate/canonical promotion deterministic without changing valid source order.
    emulatedFavorites.withIndex()
        .sortedWith(compareBy<IndexedValue<GameRow>> { it.index }.thenBy { it.value.gameId.value })
        .distinctBy { it.value.gameId }
        .forEachIndexed { index, indexed ->
            val game = indexed.value
            add(
                FavoriteHomeGame(
                    id = FavoriteMemberId.Emulated(game.gameId),
                    platformId = game.platformId,
                    row = game.toFavoriteHomeShelfRow(index.toLong() * HomeFavoriteRankStep),
                    lastPlayedMillis = game.lastPlayedMillis,
                ),
            )
        }
    val nativeOffset = size
    nativeGames.asSequence()
        .filter { it.isGame && it.isFavorite }
        .distinctBy(NativeShelfRow::packageName)
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, NativeShelfRow::label).thenBy(NativeShelfRow::packageName))
        .forEachIndexed { index, native ->
            val id = FavoriteMemberId.AndroidGame(AndroidPackageName(native.packageName))
            add(
                FavoriteHomeGame(
                    id = id,
                    lastPlayedMillis = nativeLastPlayedMillis[id.packageName],
                    row = HomeShelfRow(
                        itemId = id.toHomeShelfItemId(),
                        rank = (nativeOffset + index).toLong() * HomeFavoriteRankStep,
                        addedAtMillis = 0L,
                        title = native.label,
                        artworkUri = native.iconPath,
                        supportingLabel = native.rowKindLabel ?: "Android game",
                    ),
                ),
            )
        }
}

fun projectFavoriteHome(
    games: List<FavoriteHomeGame>,
    document: HomeTomeDocument,
    platformLabels: Map<PlatformId, String> = emptyMap(),
): FavoriteHomeProjection {
    val canonicalGames = games.distinctBy(FavoriteHomeGame::id)
    val eligibleById = canonicalGames.associateBy(FavoriteHomeGame::id)
    val platformMembers = canonicalGames
        .filter { it.platformId != null }
        .groupBy { TomeId.Platform(requireNotNull(it.platformId)) }

    // A smart Tome with no Favorite is removed from pin authority before entries are derived. The
    // renderer therefore receives one coherent transaction, never an empty pinned frame followed by cleanup.
    val normalized = normalizeHomeTomeDocument(
        document.copy(
            pinOrder = document.pinOrder.filterNot { id -> id is TomeId.Platform && platformMembers[id].isNullOrEmpty() },
        ),
    )
    val customById = normalized.customTomes.associateBy(CustomHomeTome::id)
    val overlays = normalized.platformOrderOverlays.associateBy(PlatformTomeOrderOverlay::platformId)
    val eligibleMembersByTome = linkedMapOf<TomeId, List<FavoriteMemberId>>()
    val eligibleMemberSlotsByTome = linkedMapOf<TomeId, List<FavoriteMemberId?>>()

    normalized.pinOrder.forEach { tomeId ->
        val slots = when (tomeId) {
            is TomeId.Platform -> {
                val overlay = overlays[tomeId.platformId]
                val ordered = orderPlatformTomeMembers(
                    members = platformMembers[tomeId].orEmpty(),
                    overlay = overlay,
                ).map(FavoriteHomeGame::id)
                projectSparseTomeSlots(
                    eligibleOrder = ordered,
                    storedSlots = overlay?.gameSlots.orEmpty().map { gameId ->
                        gameId?.let { FavoriteMemberId.Emulated(it) }
                    },
                )
            }
            is TomeId.Custom -> customById[tomeId]?.let { tome ->
                projectSparseTomeSlots(
                    eligibleOrder = orderCustomTomeMembers(canonicalGames, tome),
                    storedSlots = tome.memberSlots,
                )
            }.orEmpty()
            TomeId.HomeOverview -> emptyList()
        }
        val members = slots.filterNotNull()
        if (members.isNotEmpty() || tomeId is TomeId.Custom) eligibleMembersByTome[tomeId] = members
        if (members.isNotEmpty() || tomeId is TomeId.Custom) eligibleMemberSlotsByTome[tomeId] = slots
    }

    val tomes = eligibleMembersByTome.mapValues { (tomeId, members) ->
        val memberRows = members.mapNotNull(eligibleById::get)
        HomeShelfTome(
            tomeId = tomeId,
            title = when (tomeId) {
                is TomeId.Platform -> platformTomeTitle(
                    platformId = tomeId.platformId,
                    label = platformLabels[tomeId.platformId]
                        ?: PlatformLabels.labelFor(tomeId.platformId),
                )
                is TomeId.Custom -> customById.getValue(tomeId).label
                TomeId.HomeOverview -> HomeShelfTomeFallbackTitle
            },
            previews = memberRows.mapNotNull { it.row.toHomeTomePreviewOrNull() }.take(4),
            memberCount = memberRows.size,
        )
    }
    val captured = eligibleMembersByTome.values.flatten().toHashSet()
    val entries = buildList {
        eligibleMembersByTome.keys.mapTo(this, HomeShelfEntry::Tome)
        canonicalGames.filterNot { it.id in captured }.mapTo(this) { HomeShelfEntry.Game(it.row.itemId) }
    }
    return FavoriteHomeProjection(
        normalized,
        canonicalGames,
        entries,
        tomes,
        eligibleMembersByTome,
        eligibleMemberSlotsByTome,
    )
}

/** Reconciles an optional sparse overlay with current Favorite truth and pads one complete 6x2 leaf. */
internal fun <T> projectSparseTomeSlots(
    eligibleOrder: List<T>,
    storedSlots: List<T?>,
): List<T?> {
    if (eligibleOrder.isEmpty()) return emptyList()
    val eligible = eligibleOrder.toSet()
    val seen = linkedSetOf<T>()
    val slots = storedSlots.mapTo(mutableListOf()) { identity ->
        identity?.takeIf { it in eligible && seen.add(it) }
    }
    eligibleOrder.forEach { identity ->
        if (seen.add(identity)) {
            val empty = slots.indexOfFirst { it == null }
            if (empty >= 0) slots[empty] = identity else slots += identity
        }
    }
    val used = maxOf(eligibleOrder.size, slots.indexOfLast { it != null } + 1)
    val paddedSize = ((used - 1) / HomeTomeFolioPageSize + 1) * HomeTomeFolioPageSize
    if (slots.size > paddedSize) return slots.take(paddedSize)
    repeat(paddedSize - slots.size) { slots += null }
    return slots
}

private fun orderPlatformTomeMembers(
    members: List<FavoriteHomeGame>,
    overlay: PlatformTomeOrderOverlay?,
): List<FavoriteHomeGame> {
    if (overlay == null) return members
    val byGameId = members.associateBy { (it.id as FavoriteMemberId.Emulated).gameId }
    val known = overlay.gameOrder.mapNotNull(byGameId::get)
    val knownIds = known.mapTo(hashSetOf(), FavoriteHomeGame::id)
    return known + members.filterNot { it.id in knownIds }
}

/** Favorite order owns an untouched Tome; a committed Arrange owns known members and appends later additions. */
private fun orderCustomTomeMembers(
    canonicalGames: List<FavoriteHomeGame>,
    tome: CustomHomeTome,
): List<FavoriteMemberId> {
    val eligibleMembership = tome.members.toHashSet()
    val favoriteOrder = canonicalGames.map(FavoriteHomeGame::id).filter(eligibleMembership::contains)
    if (!tome.manualOrderCommitted) return favoriteOrder
    val eligibleIds = favoriteOrder.toHashSet()
    val manual = tome.members.filter(eligibleIds::contains).distinct()
    return manual + favoriteOrder.filterNot(manual.toHashSet()::contains)
}

private fun GameRow.toFavoriteHomeShelfRow(rank: Long): HomeShelfRow = HomeShelfRow(
    itemId = HomeShelfItemId.Emulated(gameId),
    rank = rank,
    addedAtMillis = 0L,
    title = title,
    artworkUri = homeShelfCoverUri(platformId, artUri, flatArtUri),
    supportingLabel = platformId.value,
    platformId = platformId,
    flatArtUri = flatArtUri,
    physicalMediaUri = physicalMediaUri,
    previewVideoUri = previewVideoUri,
    heroArtUri = heroArtUri,
    genre = genre,
    description = description,
    descriptionSource = descriptionSource,
)

/**
 * The HOME SHELF overview's Tome previews.
 *
 * This used to hardwire `selectedCartridgeUri = null, visibility = OFF`, on the reasoning that *"provider
 * support media is metadata, not an implicit presentation override"* -- so the four faces on a Tome's
 * overview card contradicted the faces inside that same Tome, on the same screen, for the same games.
 *
 * C7 overturns that reasoning for the Shelf state: enrichment alone must produce a populated library. And
 * [HomeShelfRow.faceUri] now carries the already-resolved Shelf face -- his pick, else the cover --
 * resolved once by the coordinator, where the pick stores are read. Reading it here
 * is what makes the overview agree with the Tome BY CONSTRUCTION rather than by two ladders staying in
 * step.
 */
internal fun HomeShelfRow.toHomeTomePreviewOrNull(): HomeTomePreview? {
    // The row's face, picture and shape as the resolver chose them: what Home paints. The older fields are only the fallback for a row that
    // carries no face of its own (an empty one), exactly the uri this read before the face existed.
    val shelfFace = face
    val uri = shelfFace.uri ?: tileIconUri ?: faceUri ?: artworkUri ?: return null
    val typedPlatform = platformId.takeIf { itemId is HomeShelfItemId.Emulated }
    return HomeTomePreview(
        artUri = uri,
        platformId = typedPlatform,
        // The shape comes from the resolver, which wrote it beside the uri. This used to be `faceUri != artworkUri`, which called a hand-picked COVER (or
        // the automatic `pc_games` portrait) physical media merely because it was not the row's own art: the uri-without-its-shape re-derivation the
        // face types exist to remove.
        shape = shelfFace.shape.takeIf { shelfFace.uri != null && it != ShelfFaceShape.NONE } ?: ShelfFaceShape.LEDGE_COVER,
        heroArtUri = tomeHeroArtUri(),
    )
}

internal fun HomeShelfRow.toHomeTomePreviewOrNull(
    selectedCartridgeUri: String?,
    providerPhysicalMediaUri: String? = physicalMediaUri,
): HomeTomePreview? {
    val standardArtworkUri = tileIconUri ?: artworkUri
    // The row's RESOLVED shelf face (one resolver for every shelf, 2026-10-10): a square rides `tileIconUri`, and a
    // face that is not a square -- his cover or article pick, the automatic `pc_games` portrait -- rides `faceUri`
    // with its kind. A caller that has not resolved faces (`faceUri` null) gets the row's own art exactly as before.
    val resolvedFaceUri = if (tileIconUri == null) faceUri?.takeIf(String::isNotBlank) else null
    val art = resolveTomeScopedArtwork(
        standardArtworkUri = tileIconUri ?: resolvedFaceUri ?: artworkUri,
        selectedCartridgeUri = selectedCartridgeUri,
        // FL-7: the downloaded render is a real tier here now, not something a Tome can only reach
        // through a hand re-pick. Defaulted from the row so every existing caller gains it.
        providerPhysicalMediaUri = providerPhysicalMediaUri,
    )
    val uri = art.uri ?: return null
    val typedPlatform = platformId.takeIf { itemId is HomeShelfItemId.Emulated }
    return HomeTomePreview(
        artUri = uri,
        platformId = typedPlatform,
        // `art` knows a hand-picked cartridge and the provider render are discs; it cannot know that the RESOLVED face
        // is one (his article pick rides `faceUri`), so the row's own face is read for that case, and for a square tile.
        shape = when {
            art.isPhysicalMedia || (resolvedFaceUri != null && art.uri == resolvedFaceUri && faceIsPhysicalMedia) ->
                ShelfFaceShape.PHYSICAL
            art.uri != null && art.uri == face.uri && face.shape == ShelfFaceShape.SQUARE -> ShelfFaceShape.SQUARE
            else -> ShelfFaceShape.LEDGE_COVER
        },
        itemId = itemId,
        // The Tome preview uses Hero art rather than the game cover. The chosen
        // hero, then the downloaded one, the rung Home's own ladder gained on 2026-09-17; the cover only without either.
        backdropArtUri = chosenHeroUri ?: heroArtUri ?: standardArtworkUri,
        heroArtUri = tomeHeroArtUri(),
    )
}

/**
 * The hero SLOT only, excluding the Home shelf cover and icon: the hero picked in Customize media, else the downloaded one, the same two rungs Home's own backdrop reads
 * ([HomeShelfRow.chosenHeroUri], [HomeShelfRow.heroArtUri]). No cover or icon rung, so a game with no hero is null and
 * the Tome Library shows its theme backdrop.
 */
internal fun HomeShelfRow.tomeHeroArtUri(): String? =
    chosenHeroUri?.takeIf { it.isNotBlank() } ?: heroArtUri?.takeIf { it.isNotBlank() }

private const val HomeFavoriteRankStep = 1_024L

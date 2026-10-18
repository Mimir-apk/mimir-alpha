package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.ShelfFace
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import java.io.File

/*
 * "Icon art": "Shelves" and "Icon art" are ONE Customize-media type for the shelf. It accepts
 * anything -- a square, a box, the physical article, an upload -- and the tile's shape follows the pick.
 *
 * Nothing about WHERE a pick lives changes. The three app-private stores, the three Saved catalogs and their frozen wire
 * names (`icon`, `cover`, `tome-physical`) stay; a pick made before this build is read where it already is, with no file
 * moved, renamed or rewritten. What this file adds is the ROUTING: which lane a found result or an upload belongs in, how
 * the three catalogs read as one Saved gallery, and how "Default" clears every lane. `media_art/` and every
 * `Mimir-media-art-backup-*` folder are never on any path in here: all three stores are app-private.
 */

/** The Type filter's values on the merged picker, in the order the filter lists them. */
internal object IconArtType {
    const val All = "All"
    const val Square = "Square"
    const val Front = "Front"
    const val ThreeD = "3D"
    const val Article = "Article"
    const val Texture = "Texture"
    const val Back = "Back"
}

/** The filter key of the merged picker's Type filter. */
internal const val IconArtTypeFilterKey = "face"

/**
 * The lane a shelf face is stored in. One lane per SHAPE, and the lane names the store ([kind]) and the
 * [WiredFaceKind] the apply records for it:
 *
 *  - [SQUARE]: `filesDir/sgdb2/`, the Switch tile. Record SQUARE.
 *  - [BOX]: `custom-cover-art/`, the box on the ledge. Record COVER.
 *  - [ARTICLE]: `tome-cartridge-art/`, the game's physical article. Record PHYSICAL.
 *
 * The declaration order is the legacy tie-break's precedence (an icon he picked, then a cover, then the article), so it is
 * also how two lanes holding the same picture are told apart.
 */
internal enum class IconArtLane(val kind: EnrichMediaKind) {
    SQUARE(EnrichMediaKind.ICON),
    BOX(EnrichMediaKind.COVER),
    ARTICLE(EnrichMediaKind.TOME_PHYSICAL),
}

internal fun iconArtLaneOfKind(kind: EnrichMediaKind): IconArtLane? = IconArtLane.entries.firstOrNull { it.kind == kind }

/**
 * The lane a FOUND result belongs in, by what it IS: its provider media type first (`box-*` and the SteamGridDB portrait
 * grid are boxes, `support-*` is the article, the SteamGridDB square grid is a square), then its source. A SteamGridDB asset
 * with no type is an icon, or a grid whose size the provider did not say: both are squares. Never decided from a staged file,
 * which is canvas-normalised before anyone could ask.
 */
internal fun iconArtLaneOf(providerMediaType: String?, fromSteamGridDb: Boolean): IconArtLane =
    when (wiredFaceKindOf(providerMediaType)) {
        WiredFaceKind.SQUARE -> IconArtLane.SQUARE
        WiredFaceKind.COVER -> IconArtLane.BOX
        WiredFaceKind.PHYSICAL -> IconArtLane.ARTICLE
        null -> if (fromSteamGridDb) IconArtLane.SQUARE else IconArtLane.BOX
    }

/** How far from 1:1 a picture may be and still be a square tile: 8 percent either way. */
internal const val IconArtSquareTolerance = 0.08f

/**
 * The lane an UPLOAD belongs in, by its own shape: a picture within [IconArtSquareTolerance] of 1:1 is a square tile, every
 * other shape is a box that keeps its shape. An unreadable shape is a box -- the lane that never crops.
 */
internal fun iconArtLaneOfAspect(aspect: Float?): IconArtLane =
    if (aspect != null && aspect.isFinite() && aspect > 0f && kotlin.math.abs(aspect - 1f) <= IconArtSquareTolerance) {
        IconArtLane.SQUARE
    } else {
        IconArtLane.BOX
    }

/**
 * The aspect an asset's tile takes when its size is not known: a square tile for the square and the article, the portrait box
 * (null: the cover law's own default) for a box.
 */
internal fun iconArtDefaultAspect(lane: IconArtLane): Float? = when (lane) {
    IconArtLane.SQUARE, IconArtLane.ARTICLE -> 1f
    IconArtLane.BOX -> null
}

/**
 * The Type filter's values for a game: every shape a provider can answer for it. ScreenScraper answers for an emulated game
 * (not PC), SteamGridDB for any game (a PC game's portrait grid is its Front). An Android row has only SteamGridDB squares.
 */
internal fun iconArtTypeValues(platformId: PlatformId?, emulated: Boolean): List<String> = when {
    !emulated -> listOf(IconArtType.All, IconArtType.Square)
    platformId == PlatformId.PC_GAMES -> listOf(IconArtType.All, IconArtType.Square, IconArtType.Front)
    else -> listOf(
        IconArtType.All,
        IconArtType.Square,
        IconArtType.Front,
        IconArtType.ThreeD,
        IconArtType.Article,
        IconArtType.Texture,
        IconArtType.Back,
    )
}

/**
 * Whether a found result with this provider media type is of the chosen Type. Exact matches except the article, which is the
 * whole `support-` family (the provider grows it); a `box-` type the picker does not name is shown under All only.
 */
internal fun iconArtTypeMatches(type: String, providerMediaType: String?): Boolean {
    val media = providerMediaType?.lowercase()?.trim().orEmpty()
    return when (type) {
        IconArtType.Square -> iconArtLaneOf(providerMediaType, fromSteamGridDb = true) == IconArtLane.SQUARE
        IconArtType.Front -> media == "box-2d" || media == "box-scan" || media == SteamGridDbPortraitGridType
        IconArtType.ThreeD -> media == "box-3d"
        IconArtType.Article -> media.startsWith("support-")
        IconArtType.Texture -> media == "box-texture"
        IconArtType.Back -> media == "box-2d-back"
        else -> true
    }
}

/** Whether the search asks SteamGridDB at all for this Type: squares and icons, and a PC game's portrait grids. */
internal fun iconArtTypeUsesSteamGridDb(type: String, pcGame: Boolean): Boolean = when (type) {
    IconArtType.All, IconArtType.Square -> true
    IconArtType.Front -> pcGame
    else -> false
}

/** Whether the search asks ScreenScraper at all for this Type: every box and the article, never the square. */
internal fun iconArtTypeUsesScreenScraper(type: String): Boolean = type != IconArtType.Square

/**
 * The three Saved catalogs of one game read as ONE gallery. [saved] is the one in use first, then the rest newest first; a
 * picture saved in two lanes appears once, under the lane that shows it (or the first in lane order). [lanes] says which
 * catalog each asset lives in, so activating or removing it goes to the right store. [activeAssetId] is the asset the shelf
 * paints, [activeLane] its lane.
 */
internal data class IconArtCatalog(
    val saved: List<EnrichSavedAsset>,
    val lanes: Map<String, IconArtLane>,
    val activeAssetId: String?,
    val activeLane: IconArtLane?,
) {
    companion object {
        val Empty = IconArtCatalog(emptyList(), emptyMap(), null, null)
    }
}

/**
 * The lane [face], the face the SHELF resolved, was painted from, or null for a face that is not a pick of his (the automatic
 * cover, the stand-in). The square is the square lane; a box or an article is told apart by the file it painted, which is how
 * [pickedShelfFace] chose it (a kind with no file of its own falls to the other store, so the kind alone cannot say).
 */
internal fun iconArtLaneOfShelfFace(face: ShelfFace, inputs: ShelfFaceInputs): IconArtLane? = when {
    face.shape == ShelfFaceShape.SQUARE && face.uri != null && face.uri == inputs.squareTileUri -> IconArtLane.SQUARE
    face.uri != null && face.uri == inputs.coverPickUri -> IconArtLane.BOX
    face.uri != null && face.uri == inputs.physicalPickUri -> IconArtLane.ARTICLE
    else -> null
}

/**
 * The lane a saved choice's SHAPE belongs in. A choice saved in the box lane before the lanes merged may be a square grid or a disc: the
 * old Shelves picker saved boxes, the article and (for a PC game) the square grid all through the cover lane. Its provider type says
 * which, and activating it there recorded PHYSICAL (see [coverLaneFaceKind]), a kind whose file the shelf looks up in the article store
 * FIRST, so an older article pick painted instead of the choice he had just made. A box, an upload and a legacy file with no provider
 * type stay where they are; every other lane's choices are already in their own lane.
 */
internal fun iconArtLaneOfSavedEntry(holding: IconArtLane, entry: EnrichSavedAsset): IconArtLane {
    if (holding != IconArtLane.BOX) return holding
    return when (wiredFaceKindOf(entry.providerMediaType)) {
        WiredFaceKind.SQUARE -> IconArtLane.SQUARE
        WiredFaceKind.PHYSICAL -> IconArtLane.ARTICLE
        WiredFaceKind.COVER, null -> IconArtLane.BOX
    }
}

/**
 * The three catalogs as one gallery; see [IconArtCatalog]. [winner] is the lane the shelf paints from ([IconArtStore.winningLane]):
 * it is never decided here, from the catalogs, because the shelf does not read them. Pure.
 */
internal fun mergeIconArtCatalogs(
    catalogs: Map<IconArtLane, EnrichSavedCatalog>,
    winner: IconArtLane?,
): IconArtCatalog {
    val activeId = winner?.let { catalogs.getValue(it).activeAssetId }
    val lanes = LinkedHashMap<String, IconArtLane>()
    val saved = ArrayList<EnrichSavedAsset>()
    // The winning lane first, so a picture held by two lanes reads as the one in use; then the lane order.
    val order = listOfNotNull(winner) + IconArtLane.entries.filter { it != winner }
    for (lane in order) {
        for (entry in catalogs[lane]?.entries.orEmpty()) {
            if (lanes.containsKey(entry.assetId)) continue
            lanes[entry.assetId] = lane
            saved += entry
        }
    }
    val ordered = saved.sortedWith(
        compareByDescending<EnrichSavedAsset> { it.assetId == activeId }
            .thenByDescending(EnrichSavedAsset::addedAt),
    )
    return IconArtCatalog(ordered, lanes, activeId, winner)
}

/** What one lane contributes to the picker before its Saved catalog exists: the file a pick already left there, if any. */
internal data class IconArtLaneInput(
    val lane: IconArtLane,
    val durable: EnrichSavedCatalog,
    val display: EnrichSavedCatalog,
    val legacyActive: File?,
    val retainedUpload: File?,
    val activeWasUpload: Boolean,
) {
    /** Whether this lane still has a pick to adopt into its catalog (the picker shows it at once and migrates behind it). */
    val needsMigration: Boolean get() = display != durable
}

/** The pick the shelf paints for a game: its lane, and when he made it (its selection marker's time; null when unknown). Read together, from one reading of the shelf's inputs. */
internal data class IconArtShelfWinner(val lane: IconArtLane?, val pickedAtMillis: Long?)

/** The app-private I/O of the merged picker. Each lane's own transactions are [EnrichSavedStore]'s, unchanged. */
internal object IconArtStore {
    /** The three durable catalogs of [cacheKey]. */
    fun loadLanes(context: Context, cacheKey: String): Map<IconArtLane, EnrichSavedCatalog> =
        IconArtLane.entries.associateWith { EnrichSavedStore.load(context, it.kind, cacheKey) }

    fun load(context: Context, cacheKey: String): IconArtCatalog =
        mergeIconArtCatalogs(loadLanes(context, cacheKey), winningLane(context, cacheKey))

    /** [load] on the IO dispatcher, for a caller on the main thread: it reads three catalogs, the face record, the selection markers and the stores' files. */
    suspend fun loadOffMain(context: Context, cacheKey: String): IconArtCatalog =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { load(context, cacheKey) }

    /**
     * The lane the shelf paints [cacheKey]'s face from, asked of the shelf itself: [readShelfFaceInputs] and [pickedShelfFace] are
     * the one reading Home, the Library and the Bottom all take (his record, which of his picks is NEWEST by their selection
     * markers' times, the legacy tie-break), so "in use" here can never differ from what is on the shelf. Deciding it from the
     * catalogs did: a box with a COVER record and a newer square picked through the old Icon art (no record) showed the square on
     * the shelf and marked the box in use, and the square carried a remove mark. Null when the shelf paints no pick of his.
     */
    fun winningLane(context: Context, cacheKey: String): IconArtLane? = shelfWinner(context, cacheKey).lane

    /**
     * [winningLane] and [winningPickedAtMillis] from ONE reading of the shelf's inputs. It stats the three stores' files and reads the
     * face record and the selection markers, so a caller on the main thread asks it through `withContext(Dispatchers.IO)`.
     */
    fun shelfWinner(context: Context, cacheKey: String): IconArtShelfWinner {
        val inputs = readShelfFaceInputs(context, cacheKey, ShelfFaceInputs())
        val face = pickedShelfFace(inputs, allowSquare = true) ?: return IconArtShelfWinner(null, null)
        val lane = iconArtLaneOfShelfFace(face, inputs) ?: return IconArtShelfWinner(null, null)
        val shelfKey = mediaStorageKey(cacheKey, MediaState.SHELF)
        val pickedAt = when (lane) {
            IconArtLane.SQUARE -> pickedAt(context, EnrichMediaKind.ICON, cacheKey)
            IconArtLane.BOX -> pickedAt(context, EnrichMediaKind.COVER, shelfKey)
            IconArtLane.ARTICLE -> pickedAt(context, EnrichMediaKind.TOME_PHYSICAL, shelfKey)
        }
        return IconArtShelfWinner(lane, pickedAt)
    }

    /**
     * When the pick the shelf paints was made (its selection marker's time), or null when that pick carries no time or there is
     * none. Adoption is never allowed to stamp a pick later than this ([EnrichSavedStore.migrateLegacy]'s `notAfterMillis`), so
     * opening the picker cannot make an old pick look newer than the one on the shelf.
     */
    fun winningPickedAtMillis(context: Context, cacheKey: String): Long? = shelfWinner(context, cacheKey).pickedAtMillis

    /**
     * Adopts what [input]'s lane already holds into its Saved catalog, stamped so that adopting changes nothing on the shelf:
     * [notAfterMillis] is [winningPickedAtMillis], read once BEFORE any lane is adopted.
     */
    suspend fun adopt(context: Context, cacheKey: String, input: IconArtLaneInput, notAfterMillis: Long?): EnrichLegacyMigrationOutcome =
        EnrichSavedStore.migrateLegacy(
            context = context,
            kind = input.lane.kind,
            cacheKey = cacheKey,
            legacyActive = input.legacyActive,
            retainedUpload = input.retainedUpload,
            activeWasUpload = input.activeWasUpload,
            notAfterMillis = notAfterMillis,
        )

    /**
     * What the picker opens with, lane by lane. The ICON lane adopts the tile in `sgdb2/` only when he picked it
     * ([homeIconOwnerPicked]): that folder also holds the AUTOMATIC tile, which is not his pick and must not become one just
     * because he opened Customize media (it would outrank the cover the game already has). The box and the article lanes
     * hold picks and nothing else, so what they hold is adopted.
     */
    fun laneInputs(context: Context, cacheKey: String): List<IconArtLaneInput> = IconArtLane.entries.map { lane ->
        val durable = EnrichSavedStore.load(context, lane.kind, cacheKey)
        val legacy: File?
        val retained: File?
        val activeWasUpload: Boolean
        when (lane) {
            IconArtLane.SQUARE -> {
                legacy = SteamGridDbTileFetcher.tileIconFile(context, cacheKey)
                    .takeIf { it.isFile && it.length() > 0L && homeIconOwnerPicked(context, cacheKey) }
                retained = CustomImageImportStore.retainedIconFile(context, cacheKey)
                    .takeIf { it.isFile && it.length() > 0L }
                activeWasUpload = SteamGridDbTileFetcher.uploadedPickActive(context, cacheKey)
            }
            IconArtLane.BOX -> {
                legacy = CustomCoverStore.activeFile(context, cacheKey).takeIf { it.isFile && it.length() > 0L }
                retained = null
                activeWasUpload = false
            }
            IconArtLane.ARTICLE -> {
                legacy = TomeCartridgeArtStore.activeFile(context, cacheKey).takeIf { it.isFile && it.length() > 0L }
                retained = null
                activeWasUpload = false
            }
        }
        IconArtLaneInput(
            lane = lane,
            durable = durable,
            display = enrichCatalogWithProvisionalLocal(
                catalog = durable,
                kind = lane.kind,
                legacyActive = legacy,
                retainedUpload = retained,
                activeWasUpload = activeWasUpload,
            ),
            legacyActive = legacy,
            retainedUpload = retained,
            activeWasUpload = activeWasUpload,
        )
    }

    /** Which lane [assetId] is stored in, in lane order, or null when no catalog holds it. */
    fun laneOf(context: Context, cacheKey: String, assetId: String): IconArtLane? =
        loadLanes(context, cacheKey).entries.firstOrNull { (_, catalog) ->
            catalog.entries.any { it.assetId == assetId }
        }?.key

    /**
     * Activates [assetId] in the lane that holds it, which records the lane's face kind in the same transaction. A picture two
     * lanes hold is listed once, under the lane [IconArtCatalog.lanes] names, and that is the lane the page passes as [lane]: the
     * choice he made is the one he gets, not the first lane in the fixed order. A [lane] that does not hold it, or none, falls
     * back to [laneOf].
     */
    suspend fun activate(context: Context, cacheKey: String, assetId: String, lane: IconArtLane? = null): Boolean {
        val holding = lane?.takeIf { candidate ->
            EnrichSavedStore.load(context, candidate.kind, cacheKey).entries.any { it.assetId == assetId }
        } ?: laneOf(context, cacheKey, assetId) ?: return false
        val entry = EnrichSavedStore.load(context, holding.kind, cacheKey).entries.firstOrNull { it.assetId == assetId } ?: return false
        val target = iconArtLaneOfSavedEntry(holding, entry)
        if (target == holding) return EnrichSavedStore.activate(context, holding.kind, cacheKey, assetId)
        // A square grid or a disc saved in the box lane before the lanes merged is activated by its real shape: the same picture is saved
        // into its own lane (the asset id is the picture's, so it is the same choice) and activated THERE, which writes the right record
        // and puts the file where the shelf looks first. The box lane keeps its entry; the gallery lists the choice once, under the lane
        // that shows it.
        val full = File(entry.localPath)
        val preview = entry.previewPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile } ?: full
        val saved = EnrichSavedStore.save(
            context = context,
            kind = target.kind,
            cacheKey = cacheKey,
            source = entry.source,
            label = entry.label,
            fullAsset = full,
            previewAsset = preview,
            width = entry.width,
            height = entry.height,
            providerMediaType = entry.providerMediaType,
            activate = false,
        )
        val rehomed = (saved as? EnrichSaveOutcome.Saved)?.asset ?: return false
        return EnrichSavedStore.activate(context, target.kind, cacheKey, rehomed.assetId)
    }

    /**
     * Removes [assetId] from every catalog that holds it. A lane that still names it as ITS active pick without being the one the
     * shelf paints (he picked a box after this square) is put back to Automatic first, or the store would refuse the remove: the
     * pick it holds is not showing, and the face record does not name it.
     */
    suspend fun remove(context: Context, cacheKey: String, assetId: String): Boolean {
        val lanes = loadLanes(context, cacheKey)
        val merged = mergeIconArtCatalogs(lanes, winningLane(context, cacheKey))
        if (merged.activeAssetId == assetId) return false
        var removed = false
        for ((lane, catalog) in lanes) {
            if (catalog.entries.none { it.assetId == assetId }) continue
            if (catalog.activeAssetId == assetId && !EnrichSavedStore.activateAutomatic(context, lane.kind, cacheKey)) {
                return false
            }
            if (EnrichSavedStore.remove(context, lane.kind, cacheKey, assetId)) removed = true
        }
        return removed
    }

    /**
     * "Default": his shelf pick is cleared in EVERY lane, so automatic art shows. Each lane is its own rollback-safe transaction;
     * the face record goes with them. A lane is cleared only when it HOLDS something of his: the box and the article lanes when
     * their store or catalog does, the icon lane when he picked its tile ([homeIconOwnerPicked]) or its catalog names an active
     * asset. `sgdb2/` also holds the AUTOMATIC square, which is not his pick and which the game falls back to when it has no
     * cover: clearing the icon lane unconditionally deleted it (a no-cover game that had a box picked, then Default, lost its
     * automatic square along with the box). The record is cleared whenever the icon lane was, whatever the other lanes did, so a
     * partial failure cannot leave a SQUARE record that would make the next automatic tile count as his; when no lane failed it
     * is cleared in any case. Files are cleared in the three app-private stores only: nothing under `media_art/` is touched and
     * no `Mimir-media-art-backup-*` folder is on the path.
     */
    suspend fun activateAutomatic(context: Context, cacheKey: String): Boolean {
        var allCleared = true
        var squareCleared = false
        for (lane in IconArtLane.entries) {
            val holds = when (lane) {
                IconArtLane.SQUARE -> homeIconOwnerPicked(context, cacheKey) ||
                    EnrichSavedStore.load(context, lane.kind, cacheKey).activeAssetId != null
                IconArtLane.BOX -> CustomCoverStore.hasCanonicalOverride(context, cacheKey) ||
                    EnrichSavedStore.load(context, lane.kind, cacheKey).activeAssetId != null
                IconArtLane.ARTICLE -> TomeCartridgeArtStore.hasCanonicalOverride(context, cacheKey) ||
                    EnrichSavedStore.load(context, lane.kind, cacheKey).activeAssetId != null
            }
            if (!holds) continue
            if (EnrichSavedStore.activateAutomatic(context, lane.kind, cacheKey)) {
                if (lane == IconArtLane.SQUARE) squareCleared = true
            } else {
                allCleared = false
            }
        }
        if (allCleared || squareCleared) WiredFaceStore.clear(context, cacheKey, MediaState.SHELF)
        return allCleared
    }
}

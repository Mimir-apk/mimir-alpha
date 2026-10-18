package com.gruning.mimir.rewrite

import android.content.Context
import android.net.Uri
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.ShelfFace
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import com.gruning.mimir.rewrite.core.model.library.WiredFace
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import java.io.File

/*
 * One shelf face: Icon art is the shelf art type, accepts any image, and lets the tile follow its shape.
 * Library and Home use the same shelf face resolver. This replaces three conflicting projections:
 * - Home's automatic square tile could outrank a picked cover because its guard read automatic art only.
 * - The pick-only GameRow.wiredFace projection omitted square art except for pc_games.
 * - LibraryPcTileProjection rewrote pc_games covers into GameRow.artUri only.
 *
 * Zero writes: custom-cover-art/, tome-cartridge-art/, filesDir/sgdb2/ and both preferences files
 * are read in place. Nothing under media_art/ is touched, so existing picks need no migration.
 */

/** Which rung of the shelf-face ladder decided. A reader that must tell his choice from a default asks this. */
internal enum class ShelfFaceTier {
    /** L1: a face he picked himself (a cover, the article, or a square). */
    PICK,

    /** L2: the automatic cover, `media_art/<base>.<ext>` (`GameRow.artUri`). */
    AUTOMATIC_COVER,

    /** L3: the automatic SteamGridDB art -- the `pc_games` portrait, else the square -- and only without a cover. */
    AUTOMATIC_TILE,

    /** The row's own art when it is not a real cover: a `pc_games` clear logo, an Android launcher icon. */
    STAND_IN,

    /** L4: the provider's `-support` render of the cartridge or disc. */
    SUPPORT,

    /** L5: nothing to paint. */
    NONE,
}

/** A resolved face and the rung that chose it. */
internal data class ResolvedShelfFace(val face: ShelfFace, val tier: ShelfFaceTier)

/**
 * What the shelf paints ([shelf]) and what a launch card may inherit ([card]) for one game, from one read of the
 * stores.
 *
 * [card] is the same ladder with squares left out: "a square never becomes a launch-card face" (design section B).
 * It is NOT "the shelf face unless it is square" -- a game whose shelf shows his square icon and who also holds a
 * cover pick still opens a card wearing that cover.
 */
internal data class ResolvedShelfFaces(val shelf: ResolvedShelfFace, val card: ResolvedShelfFace)

/**
 * The facts [shelfFaceTiers] decides from, with the storage reads lifted out so the ladder is provable without a
 * device (the shape [wiredFaceKind] already has). Every field defaults to "absent".
 */
internal data class ShelfFaceInputs(
    /** The recorded [WiredFaceKind] for the Shelf state, or null when this build never recorded one. */
    val recorded: WiredFaceKind? = null,
    /** His cover pick, `custom-cover-art/`. */
    val coverPickUri: String? = null,
    /** His article pick, `tome-cartridge-art/`. */
    val physicalPickUri: String? = null,
    /** The tile in `filesDir/sgdb2/` (`SteamGridDbTileFetcher.tileIconFile`), when its file exists. */
    val squareTileUri: String? = null,
    /** Whether [squareTileUri] is one he chose ([homeIconOwnerPicked]) rather than the automatic fetch's. */
    val squareTileIsOwnersPick: Boolean = false,
    /** When he picked that icon (its selection marker's `changedAtMillis`), or null when the marker carries no time. */
    val squarePickedAtMillis: Long? = null,
    /** When he made the NEWER of his cover / article picks that exist, or null when none carries a time. The coarse input: read only when neither exact time below is given. */
    val facePickedAtMillis: Long? = null,
    /** When he picked his cover (its selection marker's `changedAtMillis`), or null when the marker carries no time. */
    val coverPickedAtMillis: Long? = null,
    /** When he picked his article, as [coverPickedAtMillis]. The square is compared with the time of the pick that would otherwise WIN, never the newer of the two. */
    val physicalPickedAtMillis: Long? = null,
    /** The portrait sibling of the tile (`pc_games` only, in practice), when its file exists. */
    val portraitTileUri: String? = null,
    /** Whether the game holds a REAL cover. Gates the automatic tiles: they stand in for a cover, never over one. */
    val hasAutomaticCover: Boolean = false,
    /** The row's own art. A real cover when [hasAutomaticCover]; otherwise a stand-in (or absent). */
    val automaticCoverUri: String? = null,
    /** The provider's `-support` render. Null from a caller that does not paint L4 on its surface. */
    val supportUri: String? = null,
)

/**
 * The shelf-face ladder, pure. Design section D:
 *
 *  - **L1 his pick, by recorded kind.** With no record the tie-break is today's Home precedence: an icon he picked
 *    -> SQUARE, else a cover -> COVER, else the article -> PHYSICAL ([wiredFaceKind]). A pick whose kind has no file
 *    falls through honestly, but never past another pick he still holds.
 *  - **L2** the automatic cover.
 *  - **L3** the automatic SteamGridDB art, only without a cover: the portrait sibling when one exists (a `pc_games`
 *    box), else the square.
 *  - the row's own art when it is not a real cover ([ShelfFaceTier.STAND_IN]).
 *  - **L4** the provider's `-support` render, when the caller supplies one.
 *  - **L5** nothing.
 *
 * [allowSquare] false is the launch card's reading: no square tier anywhere.
 */
internal fun shelfFaceTiers(inputs: ShelfFaceInputs, allowSquare: Boolean = true): ResolvedShelfFace {
    pickedShelfFace(inputs, allowSquare)?.let { return ResolvedShelfFace(it, ShelfFaceTier.PICK) }
    if (inputs.hasAutomaticCover) {
        // A real cover is final: the automatic tiles below stand in for one, they do not outrank it. A null uri
        // here is the seam's call (it supplies none) and is reported as the tier, not as a face.
        val cover = inputs.automaticCoverUri.nonBlank()?.let { ShelfFace(it, ShelfFaceShape.LEDGE_COVER) }
        return ResolvedShelfFace(cover ?: ShelfFace.None, ShelfFaceTier.AUTOMATIC_COVER)
    }
    inputs.portraitTileUri.nonBlank()?.let {
        return ResolvedShelfFace(ShelfFace(it, ShelfFaceShape.LEDGE_COVER), ShelfFaceTier.AUTOMATIC_TILE)
    }
    if (allowSquare) {
        inputs.squareTileUri.nonBlank()?.let {
            return ResolvedShelfFace(ShelfFace(it, ShelfFaceShape.SQUARE), ShelfFaceTier.AUTOMATIC_TILE)
        }
    }
    inputs.automaticCoverUri.nonBlank()?.let {
        return ResolvedShelfFace(ShelfFace(it, ShelfFaceShape.LEDGE_COVER), ShelfFaceTier.STAND_IN)
    }
    inputs.supportUri.nonBlank()?.let {
        return ResolvedShelfFace(ShelfFace(it, ShelfFaceShape.PHYSICAL), ShelfFaceTier.SUPPORT)
    }
    return ResolvedShelfFace(ShelfFace.None, ShelfFaceTier.NONE)
}

internal fun pickedShelfFace(inputs: ShelfFaceInputs, allowSquare: Boolean): ShelfFace? {
    val cover = inputs.coverPickUri.nonBlank()
    val physical = inputs.physicalPickUri.nonBlank()
    val tile = inputs.squareTileUri.nonBlank()
    // The card never wears a square, and a game whose LAST choice was a square has no cover/physical pick for it:
    // the older one was replaced (C4), so it is not resurrected here. Only while the square is really there: a
    // recorded square whose tile is gone falls through to the older picks, exactly as the shelf's own reading does,
    // so the card and the tile cannot disagree about which pick is left.
    if (!allowSquare && inputs.recorded == WiredFaceKind.SQUARE && tile != null) return null
    // The tile is a pick when the explicit selection marker says so, or when the record names it.
    val squarePick = tile?.takeIf { inputs.squareTileIsOwnersPick || inputs.recorded == WiredFaceKind.SQUARE }
        .takeIf { allowSquare }

    fun faceOf(kind: WiredFaceKind?): ShelfFace? = when (kind) {
        null -> null
        WiredFaceKind.SQUARE -> squarePick?.let { ShelfFace(it, ShelfFaceShape.SQUARE) }
        // The kind decides the SHAPE only, and the file is looked up where it actually is: the named store first,
        // then the other. The face picker saves boxes and the article through one lane, so a disc he picked there
        // is a PHYSICAL record whose file is in the cover store (see `resolveWiredFace`).
        WiredFaceKind.COVER -> (cover ?: physical)?.let { ShelfFace(it, ShelfFaceShape.LEDGE_COVER) }
        WiredFaceKind.PHYSICAL -> (physical ?: cover)?.let { ShelfFace(it, ShelfFaceShape.PHYSICAL) }
    }

    // Which of his picks is NEWEST decides, not the order the stores are read in: a cover recorded long ago must not
    // hide an icon he picked since, and an icon must not hide a cover picked after it. A pick whose time is unknown
    // loses to the icon, as it always did on Home.
    faceOf(
        wiredFaceKind(
            recorded = inputs.recorded,
            state = MediaState.SHELF,
            hasCoverPick = cover != null,
            hasPhysicalPick = physical != null,
            hasSquarePick = squarePick != null,
            squarePickedAtMillis = inputs.squarePickedAtMillis,
            facePickedAtMillis = inputs.facePickedAtMillis,
            coverPickedAtMillis = inputs.coverPickedAtMillis,
            physicalPickedAtMillis = inputs.physicalPickedAtMillis,
        ),
    )?.let { return it }
    // His recorded kind has no file behind it. Fall through to the tie-break as though nothing were recorded rather
    // than past a pick he still holds ("a kind with no file falls through honestly", never silently drops a pick).
    if (inputs.recorded == null) return null
    val ownersSquare = squarePick?.takeIf { inputs.squareTileIsOwnersPick }
    return faceOf(
        wiredFaceKind(
            recorded = null,
            state = MediaState.SHELF,
            hasCoverPick = cover != null,
            hasPhysicalPick = physical != null,
            hasSquarePick = ownersSquare != null,
            squarePickedAtMillis = inputs.squarePickedAtMillis,
            facePickedAtMillis = inputs.facePickedAtMillis,
            coverPickedAtMillis = inputs.coverPickedAtMillis,
            physicalPickedAtMillis = inputs.physicalPickedAtMillis,
        ),
    )
}

/**
 * [shelfFaceTiers] with the storage read in. Each store is asked once for [cacheKey].
 *
 * [cacheKey] is the Home cache key: `SteamGridDbTileFetcher.emulatedKey` for a game, `androidKey` for a package; the
 * Shelf state's storage key is the bare key ([mediaStorageKey]). [automaticCoverUri] is the row's own art and
 * [hasAutomaticCover] says whether it is a real cover; [physicalMediaUri] is the provider's `-support` render, which
 * a surface supplies only if it paints L4.
 *
 * Reads files and one prefs file per call and fetches nothing: call it off the main thread, or from a memo.
 */
internal fun resolveShelfFaces(
    context: Context,
    cacheKey: String,
    hasAutomaticCover: Boolean,
    automaticCoverUri: String?,
    physicalMediaUri: String? = null,
): ResolvedShelfFaces {
    val base = ShelfFaceInputs(
        hasAutomaticCover = hasAutomaticCover,
        automaticCoverUri = automaticCoverUri,
        supportUri = physicalMediaUri,
    )
    val inputs = if (cacheKey.isBlank()) base else readShelfFaceInputs(context, cacheKey, base)
    return ResolvedShelfFaces(
        shelf = shelfFaceTiers(inputs, allowSquare = true),
        // The card inherits no provider article here: `launchCardWiredFace` and the Home card read the article
        // as their own tier before they ever reach the shelf's face.
        card = shelfFaceTiers(inputs.copy(supportUri = null), allowSquare = false),
    )
}

/** The shelf's face for one game; see [resolveShelfFaces] for the arguments and for the card's reading. */
internal fun resolveShelfFace(
    context: Context,
    cacheKey: String,
    hasAutomaticCover: Boolean,
    automaticCoverUri: String? = null,
    physicalMediaUri: String? = null,
): ShelfFace =
    resolveShelfFaces(context, cacheKey, hasAutomaticCover, automaticCoverUri, physicalMediaUri).shelf.face

/**
 * Gives the row the face from one resolution, and keeps the three older face fields in step for the readers that have not moved
 * to [HomeShelfRow.face].
 *
 * [HomeShelfRow.face] is what the shelf paints, shape included: the resolved shelf face, except that a launcher icon an Android
 * row was built with ([HomeShelfRow.tileIconUri]) stays the square it always was unless the resolver found a tile of its own to
 * replace it. The older fields: a square rides `tileIconUri`, everything else rides `faceUri` with its kind in
 * `faceIsPhysicalMedia`. `faceUri` is the CARD's reading ([ResolvedShelfFaces.card], no square), because the launch card and the
 * Tome wells inherit it and "a square never becomes a launch-card face".
 */
internal fun HomeShelfRow.withShelfFaces(faces: ResolvedShelfFaces): HomeShelfRow {
    val shelf = faces.shelf.face
    // `faceUri` is the shelf's own face whenever that is not a square, so the tile never differs from the resolver;
    // only a square sends the card's reading instead.
    val cardFace = if (shelf.shape == ShelfFaceShape.SQUARE) faces.card.face else shelf
    val tileIcon = if (shelf.shape == ShelfFaceShape.SQUARE) shelf.uri else tileIconUri
    return copy(
        faceUri = cardFace.uri,
        faceIsPhysicalMedia = cardFace.shape == ShelfFaceShape.PHYSICAL,
        tileIconUri = tileIcon,
        // The preset launcher icon survives a non-square resolution (the tile is painted ahead of the face, as before).
        face = if (shelf.shape != ShelfFaceShape.SQUARE && tileIcon != null) ShelfFace(tileIcon, ShelfFaceShape.SQUARE) else shelf,
    )
}

/**
 * The face a Tome well paints as its "wired" tier: the row's shelf face when it is a PICTURE of its own -- a face he picked,
 * or the automatic SteamGridDB box (the `pc_games` portrait) -- and not a square.
 *
 * A square travels on the row as `tileIconUri`, and a cover that is merely the automatic one is already the well's
 * `artworkUri`, so both answer null here and the well paints the same face the shelf does. Read from the resolution the
 * row was already given, never a second resolver call: every call is file stats on the UI thread.
 */
internal fun ResolvedShelfFaces.folioWiredFace(): MimirWiredFace? =
    shelf
        .takeIf { it.tier == ShelfFaceTier.PICK || it.tier == ShelfFaceTier.AUTOMATIC_TILE }
        ?.face
        ?.takeIf { it.shape != ShelfFaceShape.SQUARE }
        ?.let { face -> face.uri?.let { MimirWiredFace(it, physical = face.shape == ShelfFaceShape.PHYSICAL) } }

/**
 * The answer behind `CoverOverrideSource.shelfFaceOverrideFor`, so `GameRow.wiredFace` carries it for EVERY platform.
 *
 * Only the tiers the resolver cannot supply itself come back: his pick (L1) and the automatic tile (L3). The
 * automatic cover (L2) is `GameRow.artUri`, the `-support` render is `GameRow.physicalMediaUri`, and both stay where
 * they are. No uri is passed in, so L2 and the stand-in can never be reported as a "wired" face.
 */
internal fun shelfFaceOverride(context: Context, gameId: String, hasAutomaticCover: Boolean): WiredFace? {
    val shelf = resolveShelfFaces(
        context = context,
        cacheKey = gameId,
        hasAutomaticCover = hasAutomaticCover,
        automaticCoverUri = null,
    ).shelf
    if (shelf.tier != ShelfFaceTier.PICK && shelf.tier != ShelfFaceTier.AUTOMATIC_TILE) return null
    val uri = shelf.face.uri ?: return null
    return WiredFace(uri, shelf.face.shape)
}

internal fun readShelfFaceInputs(context: Context, cacheKey: String, base: ShelfFaceInputs): ShelfFaceInputs {
    val shelfKey = mediaStorageKey(cacheKey, MediaState.SHELF)
    // `tileIconFile`, not `canonicalTileIconFile` / `hasTile`: it honours EnrichSavedStore's Automatic sentinel and
    // an in-flight rollback, which the canonical path bypasses.
    val tileFile = SteamGridDbTileFetcher.tileIconFile(context, cacheKey)
    val tile = tileFile.takeIf(File::isFile)?.let(::versionedFileUri)
    val coverPick = CustomCoverStore.activeUri(context, shelfKey)
    val physicalPick = TomeCartridgeArtStore.activeUri(context, shelfKey)
    // Only worth a prefs read when there is a tile to describe.
    val iconRecord = tile?.let { GameIdentityMediaSelectionStore.record(context, EnrichMediaKind.ICON, cacheKey) }
    val squareIsOwnersPick = iconRecord?.selection == GameIdentityMediaSelection.SELECTED
    // And the time of each of his face picks only when there is an icon pick to be older or newer than. Each on its own:
    // [wiredFaceKind] compares the icon with the time of the pick that would WIN, not with the newer of the two, so a stale
    // article marker (an old pick, or one an adoption stamped) cannot make an older cover look newer than an icon picked after it.
    val coverPickedAt = if (squareIsOwnersPick) coverPick?.let { pickedAt(context, EnrichMediaKind.COVER, shelfKey) } else null
    val physicalPickedAt = if (squareIsOwnersPick) physicalPick?.let { pickedAt(context, EnrichMediaKind.TOME_PHYSICAL, shelfKey) } else null
    return base.copy(
        recorded = WiredFaceStore.recorded(context, cacheKey, MediaState.SHELF),
        coverPickUri = coverPick,
        physicalPickUri = physicalPick,
        squareTileUri = tile,
        squareTileIsOwnersPick = squareIsOwnersPick,
        squarePickedAtMillis = iconRecord?.changedAtMillis?.takeIf { squareIsOwnersPick && it > 0L },
        coverPickedAtMillis = coverPickedAt,
        physicalPickedAtMillis = physicalPickedAt,
        // The portrait sibling only ever stands in for a missing cover, so it is not read under one.
        portraitTileUri = if (base.hasAutomaticCover) {
            null
        } else {
            SteamGridDbTileFetcher.canonicalPortraitTileIconFile(context, cacheKey)
                .takeIf(File::isFile)
                ?.let(::versionedFileUri)
        },
    )
}

/** When he picked [kind] for [storageKey]: its selection marker's time while it says SELECTED, else null (unknown). */
internal fun pickedAt(context: Context, kind: EnrichMediaKind, storageKey: String): Long? =
    GameIdentityMediaSelectionStore.record(context, kind, storageKey)
        .takeIf { it.selection == GameIdentityMediaSelection.SELECTED && it.changedAtMillis > 0L }
        ?.changedAtMillis

/**
 * `file:///...#v=<mtime>`. Coil keys its caches on the model string, so a same-path overwrite (an art re-pick) would
 * otherwise serve the stale bitmap forever (FUX-10). `File.toURI()` yields a single-slash `file:/...` that Coil's file
 * fetcher does not recognise (R1 fix, 2026-07-16), hence `Uri.fromFile`; the fragment is decoder-ignored but is part of
 * the cache key.
 */
internal fun versionedFileUri(file: File): String = Uri.fromFile(file).toString() + "#v=" + file.lastModified()

private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }

/**
 * Whether the owner explicitly chose the icon sitting in this row's tile slot.
 *
 * **Corrected 2026-09-17, after the previous answer shipped and never once fired.** That answer was
 * "a `.idx` sidecar exists beside the tile", on the authority of a comment in `HomeShelfCoordinator.kt` which stated
 * that `activateLocalPick` writes one and "the automatic path writes none". The second half is false.
 * `SteamGridDbTileFetcher.fetchMissing` -- the automatic batch -- ends every successful download with
 * `idxFile.writeText(pick.toString())`, because FUX-09's reroll needs to remember which candidate it took. It builds
 * the path by hand rather than calling `pickIndexFile`, which is why a search for that function finds only the
 * explicit writer and appears to confirm the comment.
 *
 * So the escape hatch was open on EVERY automatically fetched tile, the cover guard never applied to a single one,
 * and the reported defect -- scraped boxes still wearing square icons on Home -- was unchanged by the fix that claimed
 * to address it. The unit tests passed because they wrote a tile and no sidecar, a state the automatic path never
 * actually leaves behind; `an automatic tile writes a sidecar too` in `HomeShelfAutomaticTileCoverPrecedenceTest`
 * now pins the real one.
 *
 * The authority is the explicit selection marker, which is the same one [automaticTileFetchMayInstall] consults to
 * decide whether the automatic fetch may write here at all. Guard and install gate therefore agree by construction,
 * raw cache key and all -- neither resolves aliases. Only owner actions write it: `applyPick`,
 * `CustomImageImportStore`'s two import paths, and `EnrichSavedStore`'s Saved-asset activation.
 * `clearPickAsAutomatic` writes AUTOMATIC, which correctly reads as not-picked here.
 *
 * Deliberately NOT [effectiveGameIdentityMediaSelectionRecord], which would be the obvious call and is the third wrong
 * answer: it reports SELECTED whenever the renderer file merely EXISTS, by design, so that picks predating the marker
 * keep working. Every automatic tile exists. It would reopen this hole in a new shape.
 *
 * Accepted cost of that choice: on an install old enough to hold an icon picked before the marker existed, and only
 * there, that icon is read as an automatic tile (so a cover outranks it).
 */
internal fun homeIconOwnerPicked(context: Context, cacheKey: String): Boolean =
    GameIdentityMediaSelectionStore.state(context, EnrichMediaKind.ICON, cacheKey) ==
        GameIdentityMediaSelection.SELECTED

/**
 * Whether this row already holds a REAL automatic cover -- the one fact that gates the automatic tiles.
 *
 * Policy 2026-09-17, extending the consistency ruling of the same day to Home: a game's face is its box. An
 * emulated game that had just been given a box-3D still wore a SteamGridDB square on Home because the tile outranked
 * the cover at every reader. Keyed on the ITEM KIND, not the platform: an Android row carries its launcher icon in
 * `artworkUri` (`content://launcher/icon`), which is not a cover and must not suppress its tile -- a package's art is
 * its icon, and Home is where the owner picks a nicer one.
 *
 * **`pc_games` stand-in (2026-10-10).** `homeShelfCoverUri` hands a `pc_games` row its clear logo when it has no cover
 * ("for a PC row the logo IS the face"). That logo is not a cover and must not block the tile, or Home would keep the
 * logo while the Library -- whose gate is `GameRow.artUri` alone -- showed the box: the two shelves disagreeing is the
 * thing this ruling closes. It is told apart by being the row's own [HomeShelfRow.flatArtUri].
 */
internal fun HomeShelfRow.hasOwnCoverArt(): Boolean {
    if (itemId !is HomeShelfItemId.Emulated) return false
    val art = artworkUri?.trim()?.takeIf(String::isNotEmpty) ?: return false
    val standInLogo = platformId == PlatformId.PC_GAMES && art == flatArtUri?.trim()
    return !standInLogo
}

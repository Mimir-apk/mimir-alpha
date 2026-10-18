package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape

/**
 * Which KIND of picture is wired as a game's face in one state.
 *
 * [SQUARE] (one shelf, one "Icon art") is the square tile -- a SteamGridDB grid or icon he
 * picked, which lives in `filesDir/sgdb2/` rather than in either of the other two stores. It is a SHELF kind only:
 * the launch card never wears a square ([resolveWiredFace] answers null for it). The Icon art apply
 * ([EnrichSavedStore.activate] on the icon lane) records it; a pick made before this build has no record and is reached
 * by the absent-record tie-break in [wiredFaceKind], which is exactly how it keeps showing.
 */
internal enum class WiredFaceKind {
    COVER,
    PHYSICAL,
    SQUARE,
}

/**
 * Which kind of picture the player wired as a game's face, per state (design ruling C5-F).
 *
 * ## The trap this exists to close
 *
 * Policy C4: a pick **replaces** the state's media. But the two candidate kinds live in **different
 * directories** — `custom-cover-art/` and `tome-cartridge-art/`. Pick a 3D box for the Shelf, then pick a
 * disc for the Shelf, and *both files exist*, at different paths, with nothing on disk saying which one he
 * meant last. Without this record, "replace" silently becomes "add", and which face he gets depends on
 * whichever tier the ladder happens to reach first.
 *
 * ## It is a NEW prefs file, and that is deliberate
 *
 * It touches no frozen identity, needs no migration pass, and is inert until his first pick under this
 * build. Nothing already on the reference device is re-addressed — see [mediaStorageKey]'s zero-write property.
 */
internal object WiredFaceStore {
    private const val PreferencesName = "mimir_wired_face_kind"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    /** The RECORDED kind, or null when this (game, state) has never been wired under this build. */
    fun recorded(context: Context, cacheKey: String, state: MediaState): WiredFaceKind? =
        prefs(context).getString(mediaStorageKey(cacheKey, state), null)
            ?.let { name -> WiredFaceKind.entries.firstOrNull { it.name == name } }

    /** Written by the same transaction that promotes the renderer, so the two cannot disagree. */
    fun record(context: Context, cacheKey: String, state: MediaState, kind: WiredFaceKind) {
        if (cacheKey.isBlank()) return
        prefs(context).edit().putString(mediaStorageKey(cacheKey, state), kind.name).commit()
    }

    /** Back to automatic for this state. The picked FILES are deliberately left alone — see [wiredFaceKind]. */
    fun clear(context: Context, cacheKey: String, state: MediaState) {
        prefs(context).edit().remove(mediaStorageKey(cacheKey, state)).commit()
    }
}

/**
 * The wired face kind for one (game, state), or null when the state is not wired at all: the decision, with the storage reads left to
 * the callers ([resolveWiredFace] and [resolveShelfFaces], which read the stores once and hand the answers in).
 *
 * Pure so C5-F2's asymmetry and C5-F3's tie-break can be pinned exhaustively. Both are rules about first
 * boot on a device that already holds picks, which is the one situation a device test cannot be
 * re-run for once it has happened — so it has to be provable here instead.
 *
 * **C5-F2 — absence is ASYMMETRIC, and the asymmetry IS the migration.**
 *
 * This record is new, so it is absent for every pick already on the reference device. Reading absent as *"never
 * wired"* in both states would drop every existing cartridge pick out of the top tier on first boot,
 * and his Tome wells would paint the provider's downloaded render instead of the image he chose by
 * hand. So:
 *
 *  - **absent + [MediaState.SHELF]** → consult the stores. A valid file at the Shelf key **is** a
 *    wiring. Every legacy pick keeps working, with nothing re-made.
 *  - **absent + [MediaState.LAUNCH_CARD]** → never wired, inherit the Shelf. Correct by construction:
 *    no launch-card file can exist, because the key that would address one has never been written.
 *
 * **C5-F3 — the legacy tie-break, for a game with BOTH a cover pick and a cartridge pick.**
 *
 * Such a game has two candidates and no record to arbitrate them, so it resolves to `COVER`.
 *
 * **This does not violate C4.** C4 forbids a default overruling a pick in favour of a *non-pick*. Here
 * both candidates are his own picks and neither is being discarded — one of them has to be shown
 * first, and there is nothing on record saying which. Until 2026-09-22 the Cartridge Hero switch
 * arbitrated it; `COVER` is the answer that switch already gave in its shipped OFF position, so
 * removing it changed no device. It is also self-healing: the first pick under this build writes the
 * record, and the tie-break is never consulted for that game again.
 */
internal fun wiredFaceKind(
    recorded: WiredFaceKind?,
    state: MediaState,
    hasCoverPick: Boolean,
    hasPhysicalPick: Boolean,
    // Default false so every caller that reads only the cover and disc stores keeps its
    // answer. True only from [resolveShelfFaces], which is the one reader that also looks at `sgdb2/`.
    hasSquarePick: Boolean = false,
    // When each kind of pick was made (the selection markers' `changedAtMillis`), or null when unknown. Only the
    // square is compared against the other two, and only to say which of his picks is NEWER; see below.
    squarePickedAtMillis: Long? = null,
    // The NEWER of his cover / disc times: the old, coarse input. Used only when neither of the two exact times below
    // is given, so a caller that has not learned them yet answers as it always did.
    facePickedAtMillis: Long? = null,
    // The exact times of the cover pick and of the disc pick. The square is compared with the time of the pick that
    // would otherwise WIN, never with the newer of the two: a stale disc marker (an old pick, or a marker an identity
    // replay wrote) must not make an older cover look newer than an icon he picked after it.
    coverPickedAtMillis: Long? = null,
    physicalPickedAtMillis: Long? = null,
): WiredFaceKind? {
    // The face that would win if there were no square at all, and the time of the FILE that paints it. The record (or,
    // with none, the tie-break below) names the kind, and the file is looked up where it really is -- the named store
    // first, then the other ([pickedShelfFace]) -- so the time is that file's.
    val faceKind = when {
        recorded == WiredFaceKind.COVER || recorded == WiredFaceKind.PHYSICAL -> recorded
        hasCoverPick && hasPhysicalPick -> WiredFaceKind.COVER
        hasPhysicalPick -> WiredFaceKind.PHYSICAL
        hasCoverPick -> WiredFaceKind.COVER
        else -> null
    }
    val exactTimesGiven = coverPickedAtMillis != null || physicalPickedAtMillis != null
    val faceTime = when {
        !exactTimesGiven -> facePickedAtMillis
        faceKind == WiredFaceKind.COVER -> if (hasCoverPick) coverPickedAtMillis else physicalPickedAtMillis
        faceKind == WiredFaceKind.PHYSICAL -> if (hasPhysicalPick) physicalPickedAtMillis else coverPickedAtMillis
        else -> null
    }
    // A record naming a box or a disc says only which FACE pick was last, not that no icon came after it. An icon
    // picked later has to win over it, or the record would hold a stale answer for good; the newer pick is his last
    // choice (C4). (The icon lane writes a SQUARE record of its own, so for a pick made under this build this is the
    // second line of defence; for one made before it, or through the older tile menu, it is the first.)
    val squareIsNewest = state == MediaState.SHELF && hasSquarePick &&
        (!hasCoverPick && !hasPhysicalPick || squareNotOlder(squarePickedAtMillis, faceTime))
    // A record naming the square is his last choice outright (a later cover or disc pick overwrites it).
    if (recorded == WiredFaceKind.SQUARE) return recorded
    // An explicit record wins, in either state, over everything but a newer icon. This is C4: his last choice.
    if (recorded != null) return if (squareIsNewest) WiredFaceKind.SQUARE else recorded
    // C5-F2: absence means "never wired" for the launch card, because no file can exist under a key that
    // has never been written. It does NOT mean that for the Shelf, where legacy picks live.
    if (state == MediaState.LAUNCH_CARD) return null
    return when {
        // 2026-10-10: an icon he picked himself (the explicit selection marker -- see [homeIconOwnerPicked]) is
        // shown when nothing records which he chose last, unless a cover or disc was picked AFTER it. That is
        // today's Home precedence (the stamped tile outranked every face), so a pick made before this build keeps
        // showing as it does now; the first pick under a build that records SQUARE makes it unreachable.
        squareIsNewest -> WiredFaceKind.SQUARE
        // C5-F3: two of HIS OWN picks and no record to arbitrate. The Cartridge Hero switch used to
        // arbitrate here -- which was never the switch vetoing a pick, and so never a C4 breach. With
        // the switch removed  the tie resolves to COVER, which is the answer
        // it already gave in the switch's off position. C4 itself is untouched: an explicit record
        // returns above this block and never reaches the tie at all.
        hasCoverPick && hasPhysicalPick -> WiredFaceKind.COVER
        hasPhysicalPick -> WiredFaceKind.PHYSICAL
        hasCoverPick -> WiredFaceKind.COVER
        else -> null
    }
}

/**
 * Whether the icon is at least as new as his face picks. A missing time on either side means the order cannot be
 * told, and the icon wins -- today's Home behaviour (the stamped tile outranked every face), so nothing he picked
 * before the markers carried times changes what it shows. A tie goes to the icon for the same reason.
 */
internal fun squareNotOlder(squarePickedAtMillis: Long?, facePickedAtMillis: Long?): Boolean =
    squarePickedAtMillis == null || facePickedAtMillis == null || squarePickedAtMillis >= facePickedAtMillis

/**
 * Which face kind a provider media type IS, or null when it says nothing useful.
 *
 * `support-*` is the physical article; every `box-*` is a cover. Null for an upload or a legacy file
 * adopted from disk — those genuinely have no provider type, and inventing one would be worse than
 * falling back to [wiredFaceKind]'s store-consult.
 *
 * A prefix test rather than a list, deliberately: the provider adds media types over time, and a new
 * `support-…` appearing should be a disc on the day it appears rather than on the day someone
 * remembers to extend a set.
 */
internal fun wiredFaceKindOf(providerMediaType: String?): WiredFaceKind? {
    val type = providerMediaType?.lowercase()?.trim().orEmpty()
    return when {
        type.startsWith("support-") -> WiredFaceKind.PHYSICAL
        type.startsWith("box-") -> WiredFaceKind.COVER
        // SteamGridDB's vocabulary, for the platforms ScreenScraper cannot answer for at all. The portrait grid is
        // the box. The SQUARE grid used to stand in for a physical article (a PC title has none); since the Icon art shape rule
        // (the tile follows the selected asset's shape) a square is a
        // square tile, which is what [WiredFaceKind.SQUARE] is. Exact matches, not prefixes: these two strings are
        // ours and finite, unlike the provider-grown `support-`/`box-` families above.
        type == SteamGridDbSquareGridType -> WiredFaceKind.SQUARE
        type == SteamGridDbPortraitGridType -> WiredFaceKind.COVER
        else -> null
    }
}

/**
 * The kind the COVER lane records for a pick, given what [wiredFaceKindOf] said of its provider type.
 *
 * The cover lane's file lives in `custom-cover-art/`, and a SQUARE record is only ever honoured for a file in
 * `sgdb2/` ([shelfFaceTiers] looks for the tile there). So a square grid saved through the cover lane -- a choice he
 * made in the PC Shelves or Launch card picker before "Icon art" was one type, and the Saved gallery of those
 * pickers still lists it -- keeps the record it always had, PHYSICAL (the square-ish article shape), rather than a
 * SQUARE record whose file the resolver would never find. Everything else passes through.
 */
internal fun coverLaneFaceKind(kind: WiredFaceKind?): WiredFaceKind? =
    if (kind == WiredFaceKind.SQUARE) WiredFaceKind.PHYSICAL else kind

/**
 * A resolved face and what KIND of thing it is, together, from one read.
 *
 * **Why these two facts may never be derived separately.** Before C1, "is this face a disc" was readable
 * off "is the cartridge URI non-null", because that field was populated only when a disc was meant. FL-2
 * then made the same field carry the face the Shelf resolved -- usually a COVER -- and the encoding
 * silently inverted: a 2D box arriving by the inherit path was classified physical, and
 * `homeLaunchCardRenderedArtSize` paints physical media at aspect 1f. A tall box squared off, with no
 * null to warn anyone.
 *
 * So the kind travels WITH the uri rather than being re-derived at each consumer. The launch card's own
 * KDoc already refuses to let its two geometry computations take separate inputs; this is the same rule
 * one layer up.
 */
internal data class MimirWiredFace(val uri: String, val physical: Boolean)

/**
 * The one app-side wired-face read, shared by the Shelf ([HomeShelfCoordinator]) and the Launch Card
 * ([MimirAppHost]).
 *
 * Two things it does that its callers used to get wrong on their own:
 *
 *  - **It binds the store to the kind it just resolved.** Fetching "the cover pick, else the cartridge
 *    pick" independently of the kind can render a cover under kind PHYSICAL -- a cover squared off at
 *    disc aspect, which is the defect above wearing a different hat.
 *  - **A kind with no file falls through honestly**, returning null so the caller drops to its next tier,
 *    rather than reporting a wiring it cannot paint.
 *
 * The Shelf half also closes a C4 gap that predates this: the Home shelf read only [TomeCartridgeArtStore],
 * so a hand-picked COVER never reached a Home tile at all.
 */
/**
 * The LAUNCH CARD state's face and kind, for a caller that has a `GameRow` rather than a `HomeShelfRow`.
 *
 * **Why this exists: the Library side was resolving the same surface by a different, older law.**
 * `MimirAppHost`'s Home-side card runs FL-2's ladder — his launch-card pick, then the article, then the
 * Shelf's resolved face — while the Library destination handed `GameDetailScreen` a bare `String?` from
 * `launchCardCartridgeArtUri`, which is a pre-C1 resolver with three separate problems:
 *
 *  1. **It is gated on Cartridge Hero** (`if (!cartridgeArtEnabled) return null`), so an OFF switch
 *     VETOED a cartridge he had picked by hand. That is exactly what C4 forbids, and the Home-side card
 *     is *"deliberately NOT gated"* on the same setting.
 *  2. **It is state-blind.** It reads `TomeCartridgeArtStore` at the BARE cache key, so a `LAUNCH_CARD`
 *     pick — which lives under `mediaStorageKey(cacheKey, LAUNCH_CARD)` — was invisible to it.
 *  3. **It is kind-blind.** A bare `String?` cannot say whether the picture is a disc or a cover, so
 *     `libraryLaunchCardUiState` defaulted `cartridgeArtIsPhysical = true`. That default was accidentally
 *     correct only because the old ladder's two tiers were both cartridges; its own KDoc says it is a
 *     parameter *"so that a caller which one day hands over a wired COVER can say so, instead of having
 *     its box squared off at the disc aspect."* L3 below is that caller.
 *
 * Same tiers as the Home-side card, so the two cannot drift: **L1** his launch-card pick, **L2** the
 * article — his shelf pick of one, else the provider's downloaded render — and **L3** the Shelf's
 * resolved face, which is where a COVER can arrive.
 */
internal fun launchCardWiredFace(
    context: Context,
    cacheKey: String,
    providerPhysicalMediaUri: String?,
    shelfArtworkUri: String?,
): MimirWiredFace? {
    if (cacheKey.isBlank()) return null
    // L1 -- his LAUNCH CARD pick, absolute (C4).
    resolveWiredFace(context, cacheKey, MediaState.LAUNCH_CARD)?.let { return it }
    // L2 -- the article: his SHELF pick of one, else the provider's downloaded render. This is where
    // the Cartridge Hero SCRAPE still reaches a surface, and it is deliberately ungated -- the promotion
    // switch that used to sit over it ruled the shelves, never this card (kept
    // the scrape and removed only the promotion).
    val article = TomeCartridgeArtStore.activeUri(context, mediaStorageKey(cacheKey, MediaState.SHELF))
        ?: providerPhysicalMediaUri
    article?.takeIf(String::isNotBlank)?.let { return MimirWiredFace(it, physical = true) }
    // L3 -- no article, so inherit the face the SHELF resolved, kind included. Going through the shelf's
    // resolved face rather than straight to the raw cover is what keeps a COVER he picked by hand from
    // being quietly replaced by the provider's (FL-4's reasoning, which survives at this tier).
    //
    // `providerPhysicalMediaUri` is deliberately NOT passed on: the shelf ladder has no provider tier any
    // more , and it could not have been reached from here regardless -- arriving
    // at L3 means L2 found no article, which means that uri is absent.
    //
    // 2026-10-10 (one shelf face): that face now comes from [resolveShelfFaces], the shelf's ONE resolver, asked
    // for the CARD's reading of it -- the same ladder with squares left out. "A square never becomes a launch-card
    // face": a game whose shelf shows his square icon still opens a card wearing its box or its disc, and the
    // automatic SteamGridDB portrait (a pc_games box, which used to ride `shelfArtworkUri` through the Library
    // projection) arrives here as the same tier it is on the shelf.
    val card = resolveShelfFaces(
        context = context,
        cacheKey = cacheKey,
        hasAutomaticCover = !shelfArtworkUri.isNullOrBlank(),
        automaticCoverUri = shelfArtworkUri,
    ).card.face
    return card.uri?.takeIf(String::isNotBlank)?.let { MimirWiredFace(it, card.shape == ShelfFaceShape.PHYSICAL) }
}

internal fun resolveWiredFace(
    context: Context,
    cacheKey: String,
    state: MediaState,
): MimirWiredFace? {
    // Each store is asked ONCE per key.
    //
    // `hasOverride(k)` is *defined* as `activeUri(k) != null` in both stores, and the kind decision needs
    // nothing from them but those two booleans. Before this, the store's own `resolve` (since deleted) asked both stores
    // for the shelf key, and then the lookup below asked both stores again — and on the Shelf path
    // `storageKey == shelfKey`, so every game on screen paid the whole chain (prefs read, SHA-256,
    // `stat`, a `BitmapFactory` bounds decode, `stat`) exactly twice. Measured with a UI-thread sampler
    // on the Thor 2026-09-04: **7.7 ms of a folio open's 31 ms of composition**, inside
    // `rememberHomeShelfCoordinator`, per open.
    //
    // Resolving first and deriving the booleans is the same question asked half as many times. It is a
    // deduplication and nothing else: no store, no key, no order of preference changes, and the
    // misfiled-pick recovery this file exists for is pinned by its own tests either way.
    val shelfKey = mediaStorageKey(cacheKey, MediaState.SHELF)
    val storageKey = mediaStorageKey(cacheKey, state)
    val shelfCover = CustomCoverStore.activeUri(context, shelfKey)
    val shelfPhysical = TomeCartridgeArtStore.activeUri(context, shelfKey)
    val kind = wiredFaceKind(
        recorded = WiredFaceStore.recorded(context, cacheKey, state),
        state = state,
        hasCoverPick = shelfCover != null,
        hasPhysicalPick = shelfPhysical != null,
    ) ?: return null
    // This is the cover / physical LANE of the wiring, and the launch card reads it. A square is neither, and
    // "a square never becomes a launch-card face" (2026-10-10), so a game whose last choice was a square has no
    // face here; the shelf's own reader is [resolveShelfFaces]. Answered null rather than falling back to an
    // older cover pick: that pick was replaced (C4).
    if (kind == WiredFaceKind.SQUARE) return null
    // [WiredFaceKind] carries TWO facts, and reading it as one lost a pick on the reference device.
    //
    // At the WRITE site it records what the picture IS -- a disc or a cover -- derived from the provider's
    // media type, so a disc gets the square lane instead of the portrait one. But the face picker offers
    // boxes and the physical article **in one grid and saves both through `EnrichMediaKind.COVER`**, which
    // writes to [CustomCoverStore]. So a disc picked from that grid is a `PHYSICAL` record whose FILE is in
    // the COVER store, and a read that used the kind to choose the store looked in the wrong one, found
    // nothing, and fell through as though he had never picked. Measured 2026-08-26 on the Odin: of 21
    // records, exactly one -- the LAUNCH CARD pick for Tears of the Kingdom -- was in that state, and it was
    // being silently ignored. `EnrichSavedStore` predicted this failure in prose ("SILENT DATA LOSS for the
    // Launch card") for the no-provider-type case; this is the same loss by the other route.
    //
    // So the kind now decides the SHAPE only, and the file is looked up where it actually is. The named
    // store is still tried FIRST, because both stores commonly hold a file at the same key -- 17 of the 21 records do -- and there the kind is exactly what disambiguates which one is the face.
    // Reused verbatim when this state IS the shelf (`mediaStorageKey(k, SHELF)` returns `k`), which is
    // the hot path; any other state addresses different files and must ask again.
    val cover = if (storageKey == shelfKey) shelfCover else CustomCoverStore.activeUri(context, storageKey)
    val physical =
        if (storageKey == shelfKey) shelfPhysical else TomeCartridgeArtStore.activeUri(context, storageKey)
    val named = if (kind == WiredFaceKind.PHYSICAL) physical else cover
    val other = if (kind == WiredFaceKind.PHYSICAL) cover else physical
    val uri = named?.takeIf(String::isNotBlank) ?: other?.takeIf(String::isNotBlank)
    return uri?.let { MimirWiredFace(it, kind == WiredFaceKind.PHYSICAL) }
}

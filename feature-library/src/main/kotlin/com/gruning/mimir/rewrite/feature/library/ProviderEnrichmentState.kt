package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.EnrichAttemptOutcome
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape

/**
 * One provider disposition shared by Home and Library.
 *
 * A decorative override does not change it, with one exception to the media policy: a cover (or article) the player picked by hand
 * counts as a cover for the "No cover" gap pill and for [VERIFIED_COMPLETE] ([shelfCoverArtUri] reads it from `GameRow.wiredFace`,
 * lead decision 2026-10-10). A picked square (the Icon-art tile) is not a cover and never counts.
 */
enum class ProviderEnrichmentState {
    /** No verified provider record has ever been stored for this game, and no Enrich has finished for it. */
    NEVER_REQUESTED,

    /**
     * No verified provider record was stored, but an Enrich FINISHED for this game: the provider had nothing acceptable, or there was
     * nothing to ask with . Asking again changes nothing, so no Enrich is offered anywhere; Customize media is
     * the re-fetch. A FAILED attempt never lands here, it leaves the game [NEVER_REQUESTED].
     */
    ATTEMPTED,

    /**
     * The provider verified the game, but one or more optional media/text fields were unavailable. A record is proof a run finished, so
     * Enrich is offered here only after a FAILED attempt (policy 2026-10-15, which supersedes 2026-08-19 for the button).
     */
    VERIFIED_PARTIAL,

    /** The provider verified the game and supplied the complete cover/logo/synopsis package. */
    VERIFIED_COMPLETE,
    ;

    /**
     * Gates the per-game seat's OWN direct-tap action — true only for [NEVER_REQUESTED], whose seat
     * performs Enrich immediately with no menu. It does NOT mean "Enrich is offered nowhere else":
     * restored Enrich
     * for [VERIFIED_PARTIAL] games, presented INSIDE the existing Settings/Customize-media menu that seat already opens, alongside
     * Customize media, never as a second focus seat. narrowed that to a game whose last Enrich FAILED (the menu's
     * own gate is `tileMenuOffersEnrich` in `:app`); a finished attempt hides both. See [providerScrapeGapPills] for the paired honest-tag
     * vocabulary derived from the same four inputs, so the pill line and this gate can never drift apart on the facts
     * (they were never required to imply each other one-for-one — see that function's kdoc for the one
     * known corner case).
     */
    val shouldOfferEnrich: Boolean get() = this == NEVER_REQUESTED
}

/**
 * [enrichAttempt] is how the last Enrich ended (`GameRow.enrichAttempt`). Only a FINISHED one changes the answer, and only for a game with no
 * verified record: a game with a record is [ProviderEnrichmentState.VERIFIED_PARTIAL] or COMPLETE whatever the attempt says, and its Y-menu
 * Enrich row is the one the failed attempt keeps (`tileMenuOffersEnrich` in `:app`).
 */
fun providerEnrichmentState(
    description: String?,
    descriptionSource: String?,
    coverArtUri: String?,
    flatArtUri: String?,
    enrichAttempt: EnrichAttemptOutcome? = null,
): ProviderEnrichmentState {
    if (descriptionSource.isNullOrBlank()) {
        return if (enrichAttempt?.finished == true) {
            ProviderEnrichmentState.ATTEMPTED
        } else {
            ProviderEnrichmentState.NEVER_REQUESTED
        }
    }
    val complete = !description.isNullOrBlank() &&
        !coverArtUri.isNullOrBlank() &&
        !flatArtUri.isNullOrBlank() &&
        coverArtUri != flatArtUri
    return if (complete) ProviderEnrichmentState.VERIFIED_COMPLETE else {
        ProviderEnrichmentState.VERIFIED_PARTIAL
    }
}

fun GameRow.providerEnrichmentState(): ProviderEnrichmentState = providerEnrichmentState(
    description = description,
    descriptionSource = descriptionSource,
    coverArtUri = shelfCoverArtUri(),
    flatArtUri = flatArtUri,
    enrichAttempt = enrichAttempt,
)

/**
 * The cover this game HAS, for every question of the form "does it have one": its automatic cover, else the cover- or
 * disc-shaped face the resolver carries in [GameRow.wiredFace].
 *
 * moved a `pc_games` game's automatic SteamGridDB portrait out of [GameRow.artUri] (where the
 * Library projection used to rewrite it) into `wiredFace`, because one resolver now feeds every shelf. A reader that
 * still asked `artUri` alone saw "no cover": the "No cover" pill came back, Enrich was offered again and the detail art
 * went blank. A SQUARE is not a cover (it is the Icon-art tile) and never answers here.
 */
fun GameRow.shelfCoverArtUri(): String? = artUri?.takeIf { it.isNotBlank() }
    ?: wiredFace
        ?.takeIf { it.shape == ShelfFaceShape.LEDGE_COVER || it.shape == ShelfFaceShape.PHYSICAL }
        ?.uri
        ?.takeIf { it.isNotBlank() }

/**
 * The cover the Library's launch card and detail art read, by the SAME function Home's card reads through
 * (`homeShelfCoverUri` over the game's cover): a `pc_games` game with no cover shows its clear logo there, so it must here.
 */
fun GameRow.libraryCoverArtUri(): String? = homeShelfCoverUri(platformId, shelfCoverArtUri(), flatArtUri)

/**
 * The face a Shelf row carries into [mimirShelfFace]'s cover tier — a COVER, except where a cover cannot
 * exist.
 *
 * This read `artUri ?: flatArtUri` for every platform. `flatArtUri` is the LOGO slot, so a game with no
 * cover handed its clear logo to the tier that paints boxes: the same cross-kind mixing the 2026-09-17
 * media-consistency policy removed from the ladder itself, arriving one layer above it and surviving that fix.
 *
 * `pc_games` is the exception and the reason the fallback existed. It is absent from
 * `ScreenScraperSystemMap`, so no cover is ever fetched for it; its art is a SteamGridDB tile or a file
 * the player selected, and for a PC row the logo IS the face rather than a substitute for one.
 * Dropping the fallback outright would blank every PC game, so it is scoped instead of deleted.
 */
fun homeShelfCoverUri(
    platformId: PlatformId?,
    artUri: String?,
    flatArtUri: String?,
): String? = artUri ?: flatArtUri.takeIf { platformId == PlatformId.PC_GAMES }

/**
 *  One pill per missing piece, shared wording — no other wording. Fires ONLY once a scrape
 * actually happened (`descriptionSource` non-blank): a [ProviderEnrichmentState.NEVER_REQUESTED] game shows
 * no pills, since nothing is missing from a scrape that never ran. Shares these exact four inputs with
 * [providerEnrichmentState] so the two can never disagree about what the record actually holds.
 *
 * Known corner case, deliberately out of scope per the blank-means-absent policy:
 * [providerEnrichmentState] also requires `coverArtUri != flatArtUri` for COMPLETE, so a record with all
 * three fields non-blank but an identical cover/flat pair is [ProviderEnrichmentState.VERIFIED_PARTIAL]
 * (Enrich stays offered in the menu) while showing zero pills here (nothing is blank). The policy maps pills
 * to blankness only, not to that distinctness rule.
 */
fun providerScrapeGapPills(
    description: String?,
    descriptionSource: String?,
    coverArtUri: String?,
    flatArtUri: String?,
): List<String> {
    if (descriptionSource.isNullOrBlank()) return emptyList()
    return buildList {
        if (description.isNullOrBlank()) add("No synopsis")
        if (coverArtUri.isNullOrBlank()) add("No cover")
        if (flatArtUri.isNullOrBlank()) add("No logo")
    }
}

fun GameRow.providerScrapeGapPills(): List<String> = providerScrapeGapPills(
    description = description,
    descriptionSource = descriptionSource,
    coverArtUri = shelfCoverArtUri(),
    flatArtUri = flatArtUri,
)

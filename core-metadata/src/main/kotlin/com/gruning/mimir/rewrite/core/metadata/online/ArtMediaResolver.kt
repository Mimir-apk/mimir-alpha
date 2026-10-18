package com.gruning.mimir.rewrite.core.metadata.online

/**
 * Picks the best artwork URL from a game's `medias[]`, by type then region. Pure + deterministic.
 *
 * Box and physical-support media are deliberately separate. A `support-*` cartridge/disc/card render
 * must never be persisted into the cover slot merely because a title lacks `box-*` art — [pickBoxArtUrl]
 * only ever reads [BOX_TYPE_PRIORITY], never [PHYSICAL_MEDIA_TYPE_PRIORITY].
 *
 * NO EXCEPTION for this AUTOMATIC path — and the history matters, because it was widened once already.
 *
 *  Ground truth then: probing ScreenScraper for Resident Evil 5 (Xbox 360) returned 21 media types
 * and zero `support-*` entries, but did carry `box-texture`. Commit 5b9ef36d admitted `box-texture` at
 * three sites, including this one.
 * NARROWED to the explicit Customize Media picker ONLY, and removed from here.
 * In that picker, `box-texture` is a visible, labelled "Box texture (fallback)" candidate the player
 * chooses. This list feeds no picker: its one production
 * caller is [ScreenScraperMetadataSource]'s `lookupOutcome`, the automatic enrichment record, which
 * downloads the winner and installs it as `<base>-support.png` with no label and no choice. Device
 * evidence 2026-08-22: that silent substitution wrote a flattened Xbox 360 box wrapper (1024x680) into
 * Resident Evil 5's disc slot — the ONLY wrong file among 21 physical-media files on the sampled SD card,
 * every other one a genuine 600x600 disc render or Switch cartridge — and the regression report described it as missing disc
 * art. ScreenScraper still has no `support-*` for that title (re-verified live 2026-08-22: game id 15896,
 * systeme 33, 34 medias, 20 types, zero `support-*`), so the honest automatic outcome is no physical media
 * at all. Downstream that is not a blank slot, and since 2026-08-25 it is even less of one: the DEFAULT
 * order in `LibraryComponents.libraryListPreviewMedia` is now COVER first and the disc is the fallback
 * with the inverse reached only when the
 * Cartridge Hero setting is on. `homeTomeWellArtUri` likewise falls back to the cover when there is no
 * disc. So a title with no `support-*` shows its cover; the silhouette appears only when it has no cover
 * either. Absence here is a truthful "no disc scan exists", not a hole in the shelf.
 *
 * The sentence this replaces described HEAD on 2026-08-22 and said these two surfaces "fall back to the
 * COVER when `physicalMediaUri` is null" — true then, but it described a disc-FIRST ladder that no longer
 * exists, and this paragraph is load-bearing for the 08-22 narrowing rather than decoration. Corrected in
 * the same commit that inverted the ladder so the neighboring documentation describes the current order.
 *
 * Do not re-add `box-texture` here to "fix" a title with no disc scan — that is the exact change 08-22
 * reversed. The picker is where the fallback lives: [ScreenScraperMetadataSource]'s `PHYSICAL_MEDIA_TYPES`
 * gates its fetch and `app/CustomHeroStore.kt`'s `CustomPhysicalScreenScraperTypes` ranks and labels it,
 * both deliberately still admitting `box-texture`. The Hero/backdrop dup-cover ban (CustomHeroStore's
 * `CustomHeroScreenScraperTypes` doc) is likewise untouched.
 *
 * COVER SLOT,  [BOX_TYPE_PRIORITY] now ends in `box-texture`. This does NOT reopen anything 08-22
 * closed, and the distinction is the whole point of that ruling: 08-22 was about a box wrapper being
 * filed as a DISC scan -- a lie about what the image is -- whereas in the cover slot a box texture is
 * box art being used as box art. It is ranked last, so it is reached only by a title with no `box-2D`
 * and no `box-3D`, i.e. one that would otherwise show no cover at all. The sentence above about
 * `pickBoxArtUrl`'s cover slot being untouched described HEAD on 2026-08-22 and is corrected here
 * rather than left standing beside the change that overturned it.
 *
 * Returns null when nothing matches → the caller keeps the honest silhouette.
 */
internal object ArtMediaResolver {

    /**
     * The automatic cover ladder: **3D box first**.
     *
     *  The two leaders swapped; `box-texture` keeps its place at the end.
     *
     * Automatic enrichment should populate a usable library without requiring per-game picks;
     * explicit per-game overrides remain available. Probed live 2026-08-25: every GameCube and Wii title checked publishes BOTH `box-2D` and
     * `box-3D`, so before this swap the 3D box was unreachable automatically across the sampled corpus and only
     * a picker could reach one. Now it is the default and `box-2D` is the fallback.
     *
     * **It does not rewrite a cover already on disk.** `ArtworkFetchController` writes the cover only when
     * the slot is empty, or when the game is unattributed — so an existing library keeps its 2D boxes and
     * new enrichment installs 3D.
     *
     * **Region note, verified rather than assumed.** `box-3D` publishes no `wor` region at all (Metroid
     * Prime's six are `de, eu, fr, jp, uk, us`), while [DEFAULT_REGIONS] leads with `wor`. That is fine and
     * was checked: [pick] walks TYPE first and only then region, so `box-3D` wins the type round, `wor`
     * misses, and `us` takes it. A title whose 3D boxes are only `de/fr/uk` reaches pick's "first of the
     * winning type" clause and still gets a 3D box rather than falling back to 2D. The ladder was shaped
     * for `box-2D`'s `wor`-first corpus and is now leading with a type that has a different region shape —
     * which works, and is written down here so the next person does not have to re-derive it.
     *
     * `box-texture` stays LAST: the cover-slot fallback policy is untouched, and the
     * 2026-08-22 narrowing that removed it from [PHYSICAL_MEDIA_TYPE_PRIORITY] — the DISC slot, where a box
     * wrapper was masquerading as a disc scan — is likewise untouched. In the cover slot a box texture is
     * not masquerading as anything: it IS box art.
     */
    private val BOX_TYPE_PRIORITY = listOf("box-3D", "box-2D", "box-texture")
    // Real provider scans of the physical article ONLY. `box-texture` was admitted here on 2026-08-12 and
    // removed again on 2026-08-22 when it silently filed a box wrapper as Resident Evil 5's disc art —
    // see the file doc before adding any box-* type back.
    private val PHYSICAL_MEDIA_TYPE_PRIORITY = listOf(
        "support-2D",
        "support-3D",
        "support-label",
        "support-texture",
    )
    // Enrich saves only clear logos for the launch animation. Fan art, title screens and screenshots formerly filled this slot for a game
    // with no wheel, so the launch animation drew a screenshot where the game's name belongs. Without a logo the
    // animation writes the title instead, which is honest; the backdrop is the hero slot's job.
    private val LOGO_TYPE_PRIORITY = listOf("wheel-hd", "wheel")
    // Enrichment and the manual picker prefer fanart as the purpose-built backdrop.
    // Earlier title-screen-first selection showed title screens where a backdrop was expected.
    // sstitle/ss remain screenshot fallbacks when fanart is absent. Clear-logo media is excluded
    // so a backdrop cannot become a logo on a field.
    private val HERO_TYPE_PRIORITY = listOf("fanart", "sstitle", "ss")
    // Prefer the provider's original encode before the smaller normalized sibling.
    private val VIDEO_TYPE_PRIORITY = listOf("video", "video-normalized")
    // The launch sound wants the small clip first (see pickSoundClipUrl); the original is only the fallback.
    private val SOUND_CLIP_TYPE_PRIORITY = listOf("video-normalized", "video")
    private val DEFAULT_REGIONS = listOf("wor", "us", "eu", "jp")

    /**
     * The game's COVER, in [BOX_TYPE_PRIORITY] order: the 3D render first, then `box-2D`, then the
     * flattened `box-texture` wrapper as a last resort (policy 2026-08-23, see the file doc).
     *
     * This sentence used to read "`box-2D`, then `box-3D`", which was the order before 3D was promoted and
     * had been contradicting the list above it ever since. Corrected 2026-09-17 while auditing why one
     * library paints both 3D renders and flat scans: the ladder falling through TYPES is that reason, and
     * a doc claiming the opposite order is how someone re-derives the wrong cause.
     *
     * Never reads
     * [PHYSICAL_MEDIA_TYPE_PRIORITY] -- a disc/cartridge scan is not a cover. Null when the provider
     * carries no box art of any kind.
     */
    fun pickBoxArtUrl(
        medias: List<ScreenScraperApi.Media>,
        regionPriority: List<String> = DEFAULT_REGIONS,
    ): String? = pick(medias, BOX_TYPE_PRIORITY, regionPriority)

    /**
     * The provider's real cartridge/disc/card/support artwork, independent from cover art. Null when the
     * provider has no `support-*` scan — box art is NOT substituted here (narrowing
     * 2026-08-12's `box-texture` fallback to the explicit picker; see the file doc). A null keeps the
     * honest silhouette, which is the correct answer for a title with no disc scan in the provider's data.
     */
    fun pickPhysicalMediaUrl(
        medias: List<ScreenScraperApi.Media>,
        regionPriority: List<String> = DEFAULT_REGIONS,
    ): String? = pick(medias, PHYSICAL_MEDIA_TYPE_PRIORITY, regionPriority)

    /**
     * The game's clear logo (`wheel-hd`, then `wheel`), or null when the provider has none: never fan art or a
     * screenshot standing in for one. Type and region matching are case-insensitive.
     */
    fun pickLogoUrl(
        medias: List<ScreenScraperApi.Media>,
        regionPriority: List<String> = DEFAULT_REGIONS,
    ): String? = pick(medias, LOGO_TYPE_PRIORITY, regionPriority)

    /**
     * Type then region, per [HERO_TYPE_PRIORITY]. No size/fit or popularity tie-break within a type:
     * [ScreenScraperApi.Media] carries neither pixel dimensions nor a vote/rating/popularity field
     * (verified 2026-08-11, see the [ScreenScraperApi.Media] doc) — ranking on either would mean inventing
     * data the provider does not return, so the first match of the winning type/region is taken as-is.
     *
     * Popularity, specifically : ScreenScraper's `jeu` object does carry a
     * community rating (`note`), a staff-pick flag (`topstaff`), and a supported-player-count field
     * (`joueurs` — player count, not a popularity metric despite the name). All three are PER-GAME, not
     * per-media — they cannot distinguish "this fanart is more popular than that fanart" for the same
     * title, only rate/flag the game as a whole. [ScreenScraperMetadataSource] and [ScreenScraperApi] parse
     * none of the three today. There is no per-asset popularity/vote signal on `jeu.medias[]` to rank hero
     * candidates by.
     */
    fun pickHeroArtUrl(
        medias: List<ScreenScraperApi.Media>,
        regionPriority: List<String> = DEFAULT_REGIONS,
    ): String? = pick(medias, HERO_TYPE_PRIORITY, regionPriority)

    /**
     * The best PREVIEW VIDEO url (short gameplay/trailer), or null. CP05 video mode — a SEPARATE opt-in from
     * covers; the caller gates + downloads it to a LOCAL sibling and only ever plays the local file. Prefers
     * the original provider encode before the smaller normalized sibling.
     */
    fun pickVideoUrl(
        medias: List<ScreenScraperApi.Media>,
        regionPriority: List<String> = DEFAULT_REGIONS,
    ): String? = pick(medias, VIDEO_TYPE_PRIORITY, regionPriority)

    /**
     * The clip the launch SOUND is cut from: `video-normalized` first, then the original `video`, same region logic as
     * every other picker. The normalized sibling is small (about 1.7 MB measured, 2026-07-09) and the original is larger,
     * and an original over the download cap would record a 14-day "too large" negative for a game whose normalized clip
     * fits, leaving it soundless. The first-enrich sound cost is also measured, so it must not download
     * more than a sound needs. [pickVideoUrl] is unchanged.
     */
    fun pickSoundClipUrl(
        medias: List<ScreenScraperApi.Media>,
        regionPriority: List<String> = DEFAULT_REGIONS,
    ): String? = pick(medias, SOUND_CLIP_TYPE_PRIORITY, regionPriority)

    private fun pick(
        medias: List<ScreenScraperApi.Media>,
        typePriority: List<String>,
        regionPriority: List<String>,
    ): String? {
        for (type in typePriority) {
            val ofType = medias.filter { it.type.equals(type, ignoreCase = true) }
            if (ofType.isEmpty()) continue
            for (region in regionPriority) {
                ofType.firstOrNull { it.region?.equals(region, ignoreCase = true) == true }
                    ?.let { return it.url }
            }
            // No region match for this type → take the first (region-less / any) rather than skip the type.
            return ofType.first().url
        }
        return null
    }
}

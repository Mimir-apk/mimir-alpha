package com.gruning.mimir.rewrite.core.model.library

/**
 * OFFLINE, LOCAL-ONLY image resolution seam. Given where a game's primary file lives — its SAF
 * `sourceDocumentUri` (a granted tree) OR its `managedPath` (a plain-file source) — returns LOCAL image
 * URIs (a `content://` document or a `file://` path) for sibling art files that ALREADY EXIST on the
 * device, or null when there is none.
 *
 * NEVER a network URL (local-only, legal line): the launcher shows art the
 * user (or the future offline enricher) has placed locally, beside the ROM. A miss falls back to the honest
 * glyph in [com.gruning.mimir.rewrite.core.ui MediaArtFrame] — never a broken image.
 *
 * A `fun interface` so the default is a trivial no-op: previews/tests build without an Android context, and
 * the real `LocalArtSource` (core-resolver) is injected at the app. Sibling of [GenreSource].
 */
fun interface ArtSource {
    /** A LOCAL cover-art URI for the game whose primary file is [sourceDocumentUri] / [managedPath], or null. */
    fun artUriFor(sourceDocumentUri: String?, managedPath: String?): String?

    /**
     * A distinct LOCAL clear-logo URI (`<base>-logo.<image-ext>`) for the same game, or null. Default null
     * keeps [ArtSource] a SAM/fun interface and preserves existing preview and test lambdas.
     */
    fun logoUriFor(sourceDocumentUri: String?, managedPath: String?): String? = null

    /** A distinct LOCAL title-screen/fan-art Hero (`<base>-hero.<image-ext>`), or null. */
    fun heroUriFor(sourceDocumentUri: String?, managedPath: String?): String? = null

    /** A distinct LOCAL cartridge/disc/card render (`<base>-support.<image-ext>`), or null. */
    fun physicalMediaUriFor(sourceDocumentUri: String?, managedPath: String?): String? = null

    /**
     * A LOCAL `<base>-audio.m4a`: the game's own sound, kept from its provider clip by Enrich and played under the
     * launch animation , or null. It is a SOUND and only a sound: it is never a video,
     * so it cannot become a backdrop, and it is not [PreviewVideoSource]'s answer to anything.
     */
    fun audioUriFor(sourceDocumentUri: String?, managedPath: String?): String? = null

    /**
     * Whether a null from the probes above means *"there is no art"* or only *"we could not look"*.
     *
     * Every method here answers `String?`, and null has always carried both meanings at once. That is
     * fine for a caller that just wants a URI, and wrong for one that draws a **claim** — the runed book
     * says *this game has no art*, and saying it because a directory would not enumerate is the product
     * asserting something it never established.
     *
     * Default true: a lambda answering from a literal map, or a plain-file probe, cannot be inconclusive.
     */
    fun artResolutionSettled(sourceDocumentUri: String?, managedPath: String?): Boolean = true

    /**
     * Evict memoized existence-probes for ONE ROM locator so the next cover/logo/hero/physical-media probe re-hits
     * storage — call after a per-game rescrape writes new local art so the tile can repaint. Default no-op
     * (the SAM / preview form has nothing to evict); the real [LocalArtSource] overrides it.
     */
    fun invalidate(sourceDocumentUri: String?, managedPath: String?) {}

    /**
     * Evict EVERY memoized existence-probe (all ROM locators, all media kinds) so the next probes re-hit
     * storage — call after a BATCH enrichment writes new local media for an unknown set of games (the
     * per-locator [invalidate] would need the whole written list). The probes memoize MISSES too, so
     * without this a freshly downloaded file keeps resolving as "known-absent" until process death.
     * Default no-op; the real [LocalArtSource] overrides it.
     */
    fun invalidateAll() {}
}

/**
 * The shelf face the player chose by hand, or the automatic square that stands in when no cover exists, for a
 * game; null when neither applies.
 *
 * Policy 2026-08-25 (R5), widened by the ruling of 2026-10-10 (one shelf, one Icon art). A `fun interface`
 * with a no-op default for the same reason [ArtSource] is one: previews and tests build without it.
 *
 * **Why a seam and not a read inside the resolver.** The pick lives in app-private storage, deliberately,
 * so that `ArtworkFetchController`'s automatic cover write cannot reach it — `replaceCover` fires for any
 * game with no provider attribution, which a hand-picked cover is by definition, so a pick written into
 * the ROM's own cover slot would be silently overwritten by the next enrichment pass. Private storage
 * removes that by construction rather than by a flag the automatic side must remember to honour.
 *
 * Keying by GAME ID rather than by file locator is what forces the seam: [ArtSource] answers from a
 * `sourceDocumentUri`/`managedPath` and has no game id to look a pick up with. `:app` owns the stores and
 * hands the answer down, exactly as it already does for cartridge art.
 */
fun interface CoverOverrideSource {
    /**
     * The face the Shelf paints ahead of the automatic cover, for [gameId], or null when the automatic cover
     * (or the provider's `-support` render, or nothing) is the answer.
     *
     * [hasAutomaticCover] says whether the resolver already holds a cover for this game (`GameRow.artUri`). It is
     * what lets the app answer the ONE tier that depends on it: the automatic SteamGridDB square stands in only
     * when there is no cover, and never outranks one (the automatic square fallback). An explicit pick ignores it.
     * Every platform is asked, not only `pc_games`.
     */
    fun shelfFaceOverrideFor(gameId: String, hasAutomaticCover: Boolean): WiredFace?
}

/**
 * A face the player wired by hand — or the automatic stand-in square — with the picture AND its shape.
 *
 * The shape is not decoration. The lane follows the WINNER, not the surface
 * — a disc gets the square lane, a cover the platform aspect. Under C4 a pick can now be a disc while
 * Cartridge Hero is OFF, which is a lane the off branch has never had to paint before, so a face that
 * arrived without its kind would be drawn in the wrong shape. The 2026-10-10 ruling adds a third shape, a
 * square tile ([ShelfFaceShape.SQUARE]); the shape travels with the uri and is never re-derived from which
 * field happened to be set.
 *
 * It also has to be carried SEPARATELY from the automatic cover rather than folded into it. Folding it in
 * is what made the first Cover picker violate C4: with the switch on, the resolution order reached the
 * provider's disc render before it reached `artUri`, so a manually chosen cover silently lost to art
 * the player never picked.
 *
 * [shape] is never [ShelfFaceShape.NONE]: a face that paints nothing is `null`, not a `WiredFace`.
 */
data class WiredFace(val uri: String, val shape: ShelfFaceShape) {
    init {
        require(shape != ShelfFaceShape.NONE) { "A wired face has a shape; no face is null, not NONE" }
    }

    /** The pre-shape constructor: a disc ([physical] = true) or a cover. Kept so existing callers compile. */
    constructor(uri: String, physical: Boolean) :
        this(uri, if (physical) ShelfFaceShape.PHYSICAL else ShelfFaceShape.LEDGE_COVER)

    /**
     * Whether a reader that has not yet learned the shape should paint this in the SQUARE lane (aspect 1) rather
     * than the platform's cover lane: true for a disc and for a square tile, false for a cover.
     *
     * Derived from [shape], never stored. A reader that needs to tell a disc from a square tile reads [shape].
     */
    val physical: Boolean
        get() = shape == ShelfFaceShape.PHYSICAL || shape == ShelfFaceShape.SQUARE
}

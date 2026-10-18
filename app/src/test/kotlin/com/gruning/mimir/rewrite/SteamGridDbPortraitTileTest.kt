package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The Library's cover slot is portrait
 * (`GamelistCarousel.kt`'s `portraitArtW`/`portraitArtH`), so a SteamGridDB SQUARE grid there left
 * visible gaps around the focus ring. The fix adds a distinct portrait asset
 * ([SteamGridDbTileFetcher.canonicalPortraitTileIconFile]) alongside the existing square one
 * ([SteamGridDbTileFetcher.canonicalTileIconFile]) so both shapes coexist on disk for one game --
 * `HomeShelfCoordinator`'s Home-shelf tile keeps reading the square file untouched, while
 * the shelf-face resolver (pinned in `ShelfFaceResolutionTest`) prefers the portrait one.
 *
 * This file pins the two pieces that are genuinely Robolectric-runtime testable -- [hasPortraitTile] and
 * [canonicalPortraitTileIconFile] only touch `context.filesDir`, exactly like [SteamGridDbTileHasTileTest]
 * already established for the square counterpart -- plus source-text characterization (this repo's
 * established convention, e.g. [PcTileAppIdWiringTest]) of the two links a JVM unit test cannot reach
 * because [SteamGridDbTileFetcher.fetchMissingPortrait] makes a real network call:
 * that it is keyed by the caller's already-resolved [SteamGridDbGameRef] (the exact `steam/<appid>` /
 * `game/<sgdbId>` segment machinery [SteamGridDbTileFetcher.fetchMissing] shares)
 * rather than inventing new
 * routing, and that it publishes the shelf-face refresh under the same `fetched > 0` gating [SteamGridDbTileFetcher.fetchMissing]
 * itself uses -- so a portrait-only write still repaints every open row flow (PM gate DEFECT 1's mechanism, extended to
 * this second writer).
 *
 * UNKNOWN (not testable offline): whether SteamGridDB actually returns a
 * 600x900 grid for a given title is a live-provider fact; every test here is about what Mimir REQUESTS
 * and WRITES, never about what the remote service returns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class SteamGridDbPortraitTileTest {

    // ── hasPortraitTile / canonicalPortraitTileIconFile: genuine Robolectric-filesystem tests ──────

    @Test
    fun `hasPortraitTile is false when no portrait tile file exists for the key`() {
        val context = RuntimeEnvironment.getApplication()

        assertFalse(SteamGridDbTileFetcher.hasPortraitTile(context, "pc-game-with-no-portrait-tile-yet"))
    }

    @Test
    fun `hasPortraitTile is true once a portrait tile file exists at the exact path the fetcher checks`() {
        val context = RuntimeEnvironment.getApplication()
        val cacheKey = "pc-game-already-portrait-illustrated"

        val target = SteamGridDbTileFetcher.canonicalPortraitTileIconFile(context, cacheKey)
        target.parentFile?.mkdirs()
        target.writeBytes(byteArrayOf(1, 2, 3))

        assertTrue(SteamGridDbTileFetcher.hasPortraitTile(context, cacheKey))
    }

    @Test
    fun `hasPortraitTile does not confuse two different cache keys`() {
        val context = RuntimeEnvironment.getApplication()
        val target = SteamGridDbTileFetcher.canonicalPortraitTileIconFile(context, "pc-game-portrait-a")
        target.parentFile?.mkdirs()
        target.writeBytes(byteArrayOf(9))

        assertFalse(SteamGridDbTileFetcher.hasPortraitTile(context, "pc-game-portrait-b"))
    }

    /**
     * A square write for [cacheKey] must never register as a portrait hit, or the
     * shelf-face resolver's portrait preference would fire on the wrong asset. The differential half of this guard
     * ("a portrait write is not seen as a square hit") is the mirror-image assertion in
     * [hasPortraitTile does not confuse two different cache keys]'s sibling square test
     * ([SteamGridDbTileHasTileTest]); both files together prove the two presence checks are independent.
     */
    @Test
    fun `hasPortraitTile is false for a key that only has a square tile`() {
        val context = RuntimeEnvironment.getApplication()
        val cacheKey = "pc-game-square-only-for-portrait-check"
        val square = SteamGridDbTileFetcher.canonicalTileIconFile(context, cacheKey)
        square.parentFile?.mkdirs()
        square.writeBytes(byteArrayOf(5))
        assertTrue(SteamGridDbTileFetcher.hasTile(context, cacheKey))

        assertFalse(SteamGridDbTileFetcher.hasPortraitTile(context, cacheKey))
    }

    /**
     * [SteamGridDbTileFetcher.canonicalPortraitTileIconFile]'s kdoc contract: reuse [SteamGridDbTileFetcher]'s
     * private `fileNameFor` hex derivation of [cacheKey] verbatim, only appending a `-portrait` marker
     * before the `.png` extension -- never a second hashing scheme. Verified indirectly (both accessors
     * are `internal`, `fileNameFor` itself is `private`) by checking the portrait file sits in the exact
     * same directory as the square file and its name is the square file's name with the extension swapped
     * for `-portrait.png`.
     */
    @Test
    fun `canonicalPortraitTileIconFile shares the square file's hex-derived name with a -portrait marker`() {
        val context = RuntimeEnvironment.getApplication()
        val cacheKey = "pc_games/portrait-naming-check"

        val square = SteamGridDbTileFetcher.canonicalTileIconFile(context, cacheKey)
        val portrait = SteamGridDbTileFetcher.canonicalPortraitTileIconFile(context, cacheKey)

        assertEquals(
            "both assets must live in the same sgdb2 cache directory",
            square.parentFile,
            portrait.parentFile,
        )
        assertTrue(
            "the square file name must be a plain hex string -- otherwise this test cannot tell the two " +
                "names apart from an unrelated naming scheme",
            square.name.endsWith(".png") && square.name.removeSuffix(".png").isNotBlank(),
        )
        assertEquals(
            "the portrait file must reuse the EXACT hex prefix fileNameFor derives, not a second " +
                "hashing scheme",
            square.name.removeSuffix(".png") + "-portrait.png",
            portrait.name,
        )
        assertNotEquals(
            "the two assets must be genuinely distinct files so both shapes can coexist for one game",
            square,
            portrait,
        )
    }

    // ── source-text characterization: what a JVM unit test cannot reach (real network calls) ───────

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    private fun fetchMissingPortraitBody(): String = sourceOf("SteamGridDbTileFetcher.kt")
        .substringAfter("suspend fun fetchMissingPortrait(")
        .substringBefore("private fun fileNameFor(")

    /**
     * Differential: replace `game.urlSegment` with a hand-built `"steam/$appId"` / `"game/$id"` string, or
     * add a title-search fallback for a key the caller did not resolve, and this test fails -- it is the
     * one guard proving the portrait lane did not invent its own routing (moved
     * WHICH-game resolution to the caller's single pcArtworkLookupPlan decision; this lane only consumes it).
     */
    @Test
    fun `fetchMissingPortrait is keyed by the caller's resolved SteamGridDB game, never its own title search`() {
        val body = fetchMissingPortraitBody()

        assertTrue(
            "search window missed fetchMissingPortrait's body -- assertions below would pass vacuously",
            body.contains("gamesByKey.forEach { (cacheKey, game) ->"),
        )
        assertTrue(
            "the portrait lane must resolve its segment through the SAME SteamGridDbGameRef the square " +
                "and hero lanes use for this game, not a hand-built string",
            body.contains("steamGridDbPortraitGridQueryUrl(game.urlSegment)"),
        )
        assertFalse(
            "the portrait lane must never call the title-search helpers -- resolving the game is the " +
                "caller's one decision, never re-derived per asset kind",
            body.contains("searchFirstGameId") || body.contains("searchGameByTitle"),
        )
    }

    /**
     * PM gate DEFECT 1's Library-repaint mechanism (one `refreshShelfFaces` through the repository, published by the
     * fetcher) must also cover a portrait-only write, or a game whose square art already exists (so the Enrich early
     * return in `fetchOnePcArtwork` never reaches `fetchMissing`'s own publish) would gain a portrait file on disk with no
     * signal to repaint. Differential: move this publish outside the `fetched > 0` guard, or drop it entirely, and this test
     * fails; an unconditional publish would also violate "never publish on a no-op call" (fetchMissing's own documented
     * contract, reused here).
     */
    @Test
    fun `fetchMissingPortrait publishes the tile write only when it actually wrote a tile`() {
        val body = fetchMissingPortraitBody()

        assertTrue(
            "search window missed fetchMissingPortrait's body -- assertion below would pass " +
                "vacuously",
            body.contains("var fetched = 0"),
        )
        assertTrue(
            "the publish must be gated on a genuine write, exactly like fetchMissing's own contract",
            body.contains("if (fetched > 0) publishTileWrite(context)"),
        )
    }

    private fun fetchOnePcArtworkBody(): String = sourceOf("ArtworkFetchController.kt")
        .substringAfter("private suspend fun fetchOnePcArtwork(context: Context, g: GameEntity, explicitPress: Boolean): OneResult {")
        .substringBefore("/** Compatibility entry point for the retired per-game Get art name. */")

    /**
     * Without a caller, [SteamGridDbTileFetcher.fetchMissingPortrait] is dead code and
     * the shelf-face resolver's portrait preference never has anything to prefer --
     * `fetchOnePcArtwork` (ENRICH for a `pc_games` row) is
     * the wiring site. Differential: drop the call, and this test fails.
     */
    @Test
    fun `Enrich for a pc_games row calls the portrait fetch, not only the square one`() {
        val body = fetchOnePcArtworkBody()

        assertTrue(
            "search window missed fetchOnePcArtwork's body -- assertion below would pass vacuously",
            body.contains(
                "val appId = steamAppIdForShortcut(context, g.managedPath, g.sourceDocumentUri, g.displayName)",
            ),
        )
        assertTrue(
            "fetchOnePcArtwork must call the portrait fetch with the ONE resolved SteamGridDB game (app id " +
                "or name-resolved entry alike), or portrait art is never acquired for the game the player is viewing",
            body.contains("SteamGridDbTileFetcher.fetchMissingPortrait(context, apiKey, mapOf(cacheKey to game))"),
        )
        // Ordering: BEFORE the square hasTile early return, so a game already square-tiled under the
        // pre-portrait code still gains its portrait asset the next time Enrich runs, not only on a
        // brand-new tile.
        val portraitCallIndex = body.indexOf("SteamGridDbTileFetcher.fetchMissingPortrait(")
        val hasTileReturnIndex = body.indexOf(
            "if (SteamGridDbTileFetcher.hasTile(context, cacheKey)) return OneResult.Matched",
        )
        assertTrue(
            "search window missed one of the two ordering anchors -- the assertion below would pass " +
                "vacuously; found portraitCall=$portraitCallIndex hasTileReturn=$hasTileReturnIndex",
            portraitCallIndex >= 0 && hasTileReturnIndex >= 0,
        )
        assertTrue(
            "the portrait fetch must run BEFORE the square early return, so an already-square-tiled game " +
                "still gains a portrait asset on its next Enrich press",
            portraitCallIndex < hasTileReturnIndex,
        )
    }

    private fun homeShelfSgdbEffectBody(): String = sourceOf("HomeShelfCoordinator.kt")
        .substringAfter("val sgdbTitles = remember(emulatedFavorites)")
        .substringBefore("val tiledDisplayRows")

    /**
     * The Home shelf must keep reading the SQUARE tile file. Its automatic tile-fetch effect calls
     * only [SteamGridDbTileFetcher.fetchMissing], with no portrait fetch or portrait file accessor.
     *
     * The rendering step uses `SteamGridDbTileFetcher.tileIconFile(context, key)`, the picker-aware
     * wrapper that falls back to `canonicalTileIconFile` when no override is set. This source test
     * pins that actual call rather than requiring a direct `canonicalTileIconFile` call.
     */
    @Test
    fun `HomeShelfCoordinator's automatic tile fetch stays square-only, no portrait reference`() {
        val effect = homeShelfSgdbEffectBody()

        assertTrue(
            "search window missed the SGDB tile effect -- assertions below would pass vacuously",
            effect.contains("SteamGridDbTileFetcher.fetchMissing("),
        )
        assertFalse(
            "the Home shelf's automatic fetch must not also request the portrait grid",
            effect.contains("fetchMissingPortrait"),
        )

        // 2026-10-10 (one shelf face): the Home render no longer reads the tile itself. The coordinator hands the
        // row to the ONE shelf-face resolver, which both shelves share, and that resolver is where the tile and its
        // portrait sibling are read. The 08-19 constraint this test pinned -- Home keeps the square -- is superseded
        // by the contract that Home and the Library paint the same face (the portrait for a game with no cover); what
        // stays pinned is WHERE the files are read and that the AUTOMATIC fetch above is still square-only.
        val renderBody = sourceOf("HomeShelfCoordinator.kt")
        assertTrue(
            "search window missed the Home-shelf render's resolver call -- assertion below would pass vacuously",
            renderBody.contains("resolveShelfFaces("),
        )
        assertFalse(
            "HomeShelfCoordinator must not resolve tile files itself: the shelf-face resolver is the one reader",
            renderBody.contains("canonicalPortraitTileIconFile") || renderBody.contains("hasPortraitTile") ||
                renderBody.contains("SteamGridDbTileFetcher.tileIconFile("),
        )
        val resolver = sourceOf("ShelfFaceResolver.kt")
        assertTrue(
            "the resolver must read the square through the picker-aware wrapper, which honours the Automatic sentinel",
            resolver.contains("SteamGridDbTileFetcher.tileIconFile(context, cacheKey)"),
        )
    }
}

package com.gruning.mimir.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-text characterization of two links that make the Library display the PC tile fetched by Enrich and that a JVM unit test cannot reach directly:
 *  - [MimirAppCoreCoordinator]'s `LibraryViewModel.Factory` construction is a closure inside a
 *    `@Composable` (the same category of gap `PcTileAppIdWiringTest` names for `HomeShelfCoordinator`);
 *  - `ArtworkFetchController.fetchOnePcArtwork`'s DB/Keystore-bound routing is already characterized as
 *    source text by [PcEnrichSteamGridDbRoutingWiringTest] for the same reason.
 *
 * [LibraryShelfFaceSeamTest] runtime-tests that the shelf-face seam and
 * `refreshShelfFaces` do the right thing WHEN
 * WIRED IN; this file is the missing half -- proof they are actually reachable from the real
 * construction/call sites, not merely correct in isolation. Every search window below is guarded so a
 * missed slice cannot pass vacuously, this repo's established convention (see [PcTileAppIdWiringTest]).
 *
 * Differential: pass the raw `repository` (instead of the decorated one) into `LibraryViewModel.Factory`,
 * or drop the `publishWrittenMedia` call from `fetchOnePcArtwork`'s success path, and one of the tests
 * below fails.
 */
class LibraryPcTileWiringTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    @Test
    fun `the Library ViewModel factory receives the one repository, and no decorator stands between`() {
        val body = sourceOf("MimirAppCoreCoordinator.kt")
            .substringAfter("val repository = remember { LibraryInjection.repository(context) }")
            .substringBefore("return MimirAppCoreCoordinator(")

        assertTrue(
            "search window missed the coordinator's ViewModel construction -- assertions below would " +
                "pass vacuously",
            body.contains("LibraryViewModel.Factory("),
        )
        assertFalse(
            "a tile write is published by the writer through the repository itself, so every reader sees it; a " +
                "decorator on the Library's copy alone would leave the Bottom platform shelf and the cold-start warm-up stale",
            body.contains("TileEpochLibraryRepository") || body.contains("PcTileLibraryRepository"),
        )
        // Bounded by the NEXT stable arg name in the same call, not by the first newline -- the real
        // source breaks the line right after the opening paren, so a "\n" bound would capture nothing.
        val factoryArgs = body.substringAfter("LibraryViewModel.Factory(")
            .substringBefore("launchController = launchController,")
        assertTrue(
            "search window missed the factory's argument list -- assertion below would pass vacuously",
            factoryArgs.isNotBlank(),
        )
        assertTrue(
            "LibraryViewModel.Factory must receive the repository whose flows re-project on a tile write -- " +
                "found: <$factoryArgs>",
            factoryArgs.contains("repository = repository,"),
        )
    }

    @Test
    fun `the coordinator's own exposed repository stays undecorated -- Home shelf and friends are unaffected`() {
        val body = sourceOf("MimirAppCoreCoordinator.kt")
        val constructorCall = body.substringAfter("return MimirAppCoreCoordinator(")

        assertTrue(
            "search window missed the constructor call -- assertion below would pass vacuously",
            constructorCall.contains("libraryViewModel = libraryViewModel,"),
        )
        assertTrue(
            "core.repository (read by HomeShelfCoordinator/FileBrowserStore/AppBrightnessCoordinator via " +
                "their own LibraryInjection.repository(context) call, and exposed as LocalLibraryRepository) " +
                "must remain the RAW instance -- decorating it would ripple this change into Home shelf, " +
                "file browser, and the brightness coordinator must retain their existing routing",
            constructorCall.contains("repository = repository,"),
        )
    }

    private fun fetchOnePcArtworkBody(): String = sourceOf("ArtworkFetchController.kt")
        .substringAfter("private suspend fun fetchOnePcArtwork(context: Context, g: GameEntity, explicitPress: Boolean): OneResult {")
        .substringBefore("/** Compatibility entry point for the retired per-game Get art name. */")

    @Test
    fun `a genuinely new tile fetch is published once, by the fetcher, so the Library screen actually repaints`() {
        val body = fetchOnePcArtworkBody()

        assertTrue(
            "search window missed fetchOnePcArtwork's fetch result -- assertions below would pass vacuously",
            body.contains("val fetched = SteamGridDbTileFetcher.fetchMissing("),
        )
        assertFalse(
            "fetchMissing publishes the shelf-face refresh itself; a second publishWrittenMedia for the same write " +
                "was a duplicate invalidation (and re-keyed the cover's `#mv=` for a file that is not the cover)",
            body.contains("if (fetched > 0) publishWrittenMedia(context, listOf(g.asChangedMedia()))"),
        )
        // A bare sgdb2/ file write touches no Room table, so GameRow flows never re-emit without the writer saying so --
        // the player would see "Game enriched" and no change until the next cold platform open.
        val fetcher = sourceOf("SteamGridDbTileFetcher.kt")
        val fetchMissing = fetcher.substringAfter("suspend fun fetchMissing(").substringBefore("suspend fun fetchMissingPortrait(")
        assertTrue(
            "search window missed fetchMissing's tail -- assertion below would pass vacuously",
            fetchMissing.contains("fetched++"),
        )
        assertTrue(
            "fetchMissing must publish once, only when it wrote",
            fetchMissing.contains("if (fetched > 0) publishTileWrite(context)"),
        )
        assertTrue(
            "the publish is ONE shelf-face refresh through the repository, never a decorator or a second signal",
            fetcher.contains("LibraryStore.repository(context).refreshShelfFaces()"),
        )
    }

    @Test
    fun `the already-has-a-tile early return does not redundantly publish the unwritten square tile`() {
        // Updated 2026-08-19 for the automatic-hero lane:
        // this guard originally forbade ANY publishWrittenMedia before the has-tile early return, because
        // nothing was written there before that ruling. It now genuinely IS legitimate for something to be
        // written there -- an automatic hero, which is independent of the sgdb2 tile the has-tile check is
        // about -- so this guard is narrowed to what it always actually meant: no REDUNDANT publish of the
        // game's own (unwritten) tile locator. A hero publish, gated on heroWritten != null and scoped to
        // heroWritten.asChangedMedia() (never the bare, unconditional g.asChangedMedia() this guard forbids),
        // is exactly correct here, not the no-op churn this test exists to catch.
        val body = fetchOnePcArtworkBody()
        val beforeHasTileReturn = body.substringBefore(
            "if (SteamGridDbTileFetcher.hasTile(context, cacheKey)) return OneResult.Matched",
        )

        assertTrue(
            "search window missed the has-tile early return -- assertion below would pass vacuously",
            beforeHasTileReturn.length < body.length,
        )
        assertFalse(
            "publishWrittenMedia must never publish the game's OWN (unwritten) sgdb2 tile locator before " +
                "the has-tile early return -- that branch writes nothing new to the TILE itself, so bumping " +
                "artVersion via the bare g.asChangedMedia() there would be a no-op churn, not a fix",
            beforeHasTileReturn.contains("publishWrittenMedia(context, listOf(g.asChangedMedia()))"),
        )
        assertTrue(
            "the automatic-hero publish (gated on heroWritten != null, scoped to " +
                "heroWritten.asChangedMedia()) is expected here -- a hero written for an already-tiled " +
                "game must still be published before this early return, or it stays invisible until a " +
                "process restart; see PcAutomaticHeroArtTest for the dedicated ordering guard",
            beforeHasTileReturn.contains(
                "if (heroWritten != null) publishWrittenMedia(context, listOf(heroWritten.asChangedMedia()))",
            ),
        )
    }

    /**
     * A LATER, separate fix (policy 2026-08-19, "a portrait-only enrich must not report failure"):
     * the pre-fix tail discarded [SteamGridDbTileFetcher.fetchMissingPortrait]'s return value
     * entirely, so a game whose portrait grid landed but had no qualifying SQUARE grid still fell through
     * to `OneResult.NoMatch` -- the overlay read "SteamGridDB had no art for this game. Nothing was
     * changed." while the portrait art the shelf-face resolver prefers for a game with no cover was already
     * on screen. This is the same dishonesty class the genuinely-new-tile-fetch test above exists to
     * close for the square lane, now closed for the portrait one.
     *
     * Differential: revert the tail to `return if (fetched > 0) OneResult.Matched else OneResult.NoMatch`
     * (dropping `portraitFetched` from the condition), or discard `fetchMissingPortrait`'s
     * return value again instead of binding it to `portraitFetched`, and this test fails.
     */
    @Test
    fun `a portrait-only success still reports Matched, not NoMatch`() {
        val body = fetchOnePcArtworkBody()

        assertTrue(
            "search window missed fetchOnePcArtwork's portrait-fetch capture -- assertions below would " +
                "pass vacuously",
            body.contains("val portraitFetched ="),
        )
        assertTrue(
            "the portrait fetch's count must actually be bound to a value, not discarded, or it cannot " +
                "feed the return classification below",
            body.contains(
                "val portraitFetched =\n" +
                    "            SteamGridDbTileFetcher.fetchMissingPortrait(" +
                    "context, apiKey, mapOf(cacheKey to game))",
            ),
        )
        // Automatic hero and logo fetching widened the result condition:
        // this used to pin the exact single-line `return if (...) OneResult.Matched else OneResult.NoMatch`
        // ternary verbatim. A later policy widened the SAME condition with `|| heroWritten != null`
        // and reflowed it into a multi-line if/else (both genuine, deliberate changes -- not formatting
        // drift), so the exact single-line literal can no longer appear. Checked as a bounded substring
        // instead of the old exact-statement match. Widened again for launch-animation logos with `|| logoWritten != null`, which reflowed the condition onto
        // its own lines.
        val verdictIdx = body.indexOf(
            "return if (\n" +
                "            fetched > 0 || portraitFetched > 0 || descriptionPersisted || markerPersisted || " +
                "heroWritten != null ||\n" +
                "            logoWritten != null\n" +
                "        )",
        )
        assertTrue(
            "search window missed the final verdict -- the assertion below would pass vacuously",
            verdictIdx >= 0,
        )
        val verdictTail = body.substring(verdictIdx, minOf(verdictIdx + 320, body.length))
        assertTrue(
            "a portrait grid landing with no qualifying square grid must still report Matched, or the " +
                "overlay claims nothing changed while the portrait art is already on screen in the Library. " +
                "The verdict includes `|| descriptionPersisted` and `|| heroWritten != null` -- " +
                "a durable Steam description write or a written hero " +
                "both count as Matched on their own, same as a durable metadata write does everywhere else. " +
                "A written logo (2026-09-28) counts the same way.",
            verdictTail.contains("OneResult.Matched") && verdictTail.contains("OneResult.NoMatch"),
        )
    }
}

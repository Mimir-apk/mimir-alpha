package com.gruning.mimir.rewrite

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-text characterization of the two links that decide whether a favourited PC game's automatic
 * tile is identified by its Steam app id or by a fuzzy title search (policy 2026-08-19; R-23
 * declined title matching for `.steam` because sequels and editions are where it goes wrong).
 *
 * Both links are otherwise unpinned, and the executor said so rather than letting three green tests
 * imply full coverage:
 *  - `steamGridDbUrlSegmentFromAppId` is unit-tested in isolation, but nothing proved `fetchMissing`
 *    actually calls it as the `?:` left operand. Dropping that call leaves those tests green.
 *  - `HomeShelfCoordinator` supplying the map at all is a closure inside a `@Composable`.
 *
 * The same gap shipped once already this week on `MimirAppCoordinator` and was caught by review, not
 * by the suite — see [ExactAppIdSizePassthroughCoordinatorWiringTest], which this follows, along with
 * the five older coordinator wiring tests that pin captured closures the same way.
 *
 * Differential: drop `steamGridDbUrlSegmentFromAppId(...)` from `fetchMissing`'s routing, or stop
 * passing the map at the call site, and this test fails.
 */
class PcTileAppIdWiringTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    @Test
    fun fetchMissingPrefersTheExactAppIdOverTheTitleSearch() {
        val body = sourceOf("SteamGridDbTileFetcher.kt")
            .substringAfter("suspend fun fetchMissing(")
            .substringBefore("private fun searchFirstGameId(")

        assertTrue(
            "search window missed fetchMissing's body -- every assertion below would pass vacuously",
            body.contains("titlesByKey.forEach") && body.contains("steamAppIdsByKey"),
        )

        val routing = body.substringAfter("val urlSegment = ").substringBefore("\n")
        assertTrue(
            "the app-id lookup must be the FIRST operand so `?:` short-circuits and the fuzzy title " +
                "search is never evaluated for a PC game that carries an exact key; found: <$routing>",
            routing.startsWith("steamGridDbUrlSegmentFromAppId(cacheKey, steamAppIdsByKey)"),
        )
        assertTrue(
            "the title search must remain the fallback for keys with no app id -- R-25 keeps " +
                "filename/title matching for .epic/.gog/.amazon/.pcgame",
            body.contains("searchFirstGameId(apiKey, title)"),
        )
    }

    @Test
    fun theShelfEffectSuppliesAppIdsForPcGamesOnly() {
        val effect = sourceOf("HomeShelfCoordinator.kt")
            .substringAfter("val sgdbTitles = remember(emulatedFavorites)")
            .substringBefore("val tiledDisplayRows")

        assertTrue(
            "search window missed the SGDB tile effect -- assertions below would pass vacuously",
            effect.contains("SteamGridDbTileFetcher.fetchMissing("),
        )
        assertTrue(
            "the resolved app ids must actually reach the fetcher, or the exact-id route is dead code",
            effect.contains("steamAppIds)") || effect.contains("steamAppIdsByKey = steamAppIds"),
        )
        assertTrue(
            "resolve must be filtered to pc_games first -- pcGameSteamAppId self-guards, but it costs " +
                "a DAO read plus a file parse per favourite inside a Compose effect",
            effect.contains("PlatformId.PC_GAMES"),
        )
    }
}

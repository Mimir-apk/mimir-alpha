package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automatic PC hero art (policy 2026-08-19):
 * an automatically-fetched SteamGridDB hero for a `pc_games` row is stored at the same ROM-sibling
 * `media_art/<base>-hero.png` slot ScreenScraper already writes for every other platform -- never
 * `filesDir/hero` (the distinct chosen-hero store) and never a new app-internal namespace.
 *
 * Two test shapes, following [PcEnrichSteamGridDbRoutingWiringTest]'s own precedent and its documented
 * reason for the split: [ArtworkFetchController.attemptPcHeroWrite] is a pure, injectable-seam decision
 * function (create-only gate + fetch/stage/install sequencing) with no Context/Room/network dependency, so
 * it is runtime-tested directly with fakes below. The three network/DB-bound steps it is wired to from
 * [ArtworkFetchController]'s private `fetchOnePcArtwork` -- which candidate route is used, how the download
 * is staged, which slot it installs to, and critically WHEN the write is published -- are NOT reachable
 * from a JVM unit test the same way (no Robolectric+Room precedent, `SteamGridDbKeyStore.resolvedKey` is
 * device-test-only), so those are pinned as source text, exactly as the existing routing test does for the
 * rest of this function.
 */
class PcAutomaticHeroArtTest {

    // ── attemptPcHeroWrite: real runtime tests, no fakes needed beyond simple lambdas ──────────────

    private fun fakeCandidate(): CustomHeroCandidate = CustomHeroCandidate(
        source = CustomHeroSource.STEAMGRIDDB,
        mediaType = CustomHeroMediaType.STATIC,
        remoteUrl = "https://cdn.steamgriddb.com/heroes/example.png",
    )

    private fun fakeGameEntity(): GameEntity = GameEntity(
        entryKey = "pc-1-primary",
        gameId = "pc-1",
        platformId = "pc_games",
        contentKind = "GAME",
        displayName = "Some Game.steam",
        title = "Some Game",
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "FILENAME",
        locationKind = "SAF",
        availability = "AVAILABLE",
        sizeBytes = 1,
        contentHash = null,
        sourceRootUri = "content://root",
        sourceDocumentUri = "content://root/pc-1.steam",
        managedPath = null,
        relativePath = "Some Game.steam",
        discNumber = 0,
        duplicateGroupId = "pc-1",
        isPrimaryMember = true,
        duplicateGroupKind = null,
    )

    @Test
    fun `create-only -- an existing hero uri skips fetch, stage, and install entirely`() = runBlocking {
        var fetchCalls = 0
        var stageCalls = 0
        var installCalls = 0

        val result = ArtworkFetchController.attemptPcHeroWrite(
            existingHeroUri = "file:///roms/media_art/Some Game-hero.png",
            fetchCandidate = {
                fetchCalls++
                fakeCandidate()
            },
            stage = {
                stageCalls++
                true
            },
            install = {
                installCalls++
                fakeGameEntity()
            },
        )

        assertNull(result)
        assertEquals(0, fetchCalls)
        assertEquals(0, stageCalls)
        assertEquals(0, installCalls)
    }

    @Test
    fun `no existing hero -- a staged candidate is installed and its result returned`() = runBlocking {
        val candidate = fakeCandidate()
        val installed = fakeGameEntity()
        var stagedCandidate: CustomHeroCandidate? = null
        var installCalls = 0

        val result = ArtworkFetchController.attemptPcHeroWrite(
            existingHeroUri = null,
            fetchCandidate = { candidate },
            stage = { staged ->
                stagedCandidate = staged
                true
            },
            install = {
                installCalls++
                installed
            },
        )

        assertSame(installed, result)
        assertSame(candidate, stagedCandidate)
        assertEquals(1, installCalls)
    }

    @Test
    fun `no candidate available -- install never runs and the result is null`() = runBlocking {
        var installCalls = 0

        val result = ArtworkFetchController.attemptPcHeroWrite(
            existingHeroUri = null,
            fetchCandidate = { null },
            stage = { true },
            install = {
                installCalls++
                fakeGameEntity()
            },
        )

        assertNull(result)
        assertEquals(0, installCalls)
    }

    @Test
    fun `staging failure -- install never runs and the result is null`() = runBlocking {
        var installCalls = 0

        val result = ArtworkFetchController.attemptPcHeroWrite(
            existingHeroUri = null,
            fetchCandidate = { fakeCandidate() },
            stage = { false },
            install = {
                installCalls++
                fakeGameEntity()
            },
        )

        assertNull(result)
        assertEquals(0, installCalls)
    }

    // ── fetchOnePcArtwork wiring: source-text characterization, same idiom and same reason as
    // PcEnrichSteamGridDbRoutingWiringTest (class kdoc above explains why a real run is unreachable here) ──

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    private fun fetchOnePcArtworkBody(): String = sourceOf("ArtworkFetchController.kt")
        .substringAfter("private suspend fun fetchOnePcArtwork(context: Context, g: GameEntity, explicitPress: Boolean): OneResult {")
        .substringBefore("/** Compatibility entry point for the retired per-game Get art name. */")

    @Test
    fun `the create-only gate is fed from LocalArtSource's real hero probe`() {
        val body = fetchOnePcArtworkBody()

        assertTrue(
            "search window missed the attemptPcHeroWrite call site -- assertions below would pass vacuously",
            body.contains("attemptPcHeroWrite("),
        )
        assertTrue(
            "the create-only gate must be fed the SAME local-file probe LocalArtSource.heroUriFor exposes " +
                "to the Library screen, not a separately-tracked flag that could drift from disk truth",
            body.contains(
                "existingHeroUri = LocalArtSource(context).heroUriFor(g.sourceDocumentUri, g.managedPath),",
            ),
        )
    }

    @Test
    fun `the hero candidate is keyed by the ONE resolved SteamGridDB game, never a second title search`() {
        val body = fetchOnePcArtworkBody()

        assertTrue(
            "R-23: the hero fetch must use the exact id-type-keyed route for the game fetchOnePcArtwork " +
                "already resolved (steam/<appid> from a `.steam` shortcut, or game/<sgdbId> from the one " +
                "name search), never re-derive the game per asset kind",
            body.contains("CustomHeroFetcher.steamGridDbHeroesForGame(apiKey, game)"),
        )
        assertFalse(
            "no separate title search for the hero route: the picker's candidatesFor, the null-swallowing " +
                "searchFirstGameId and the title-keyed steamGridDbHeroes(apiKey, ...) overloads are all " +
                "off-limits here",
            body.contains("searchFirstGameId") ||
                body.contains("candidatesFor(") ||
                body.contains("steamGridDbHeroes(apiKey"),
        )
    }

    @Test
    fun `the hero download is staged through the SteamGridDB-allowlisted path, never downloadGated`() {
        val body = fetchOnePcArtworkBody()

        assertTrue(
            "the hero must reuse CustomHeroFetcher.stageCandidate -- the manual Custom Hero apply path -- " +
                "which is byte-bounded, decode-proof and quality-floored, and already SGDB-CDN-allowlisted",
            body.contains("CustomHeroFetcher.stageCandidate(context,"),
        )
        assertFalse(
            "downloadGated enforces connectedArtMediaUrlAllowed -> ScreenScraper-host-only and cannot " +
                "reach a SteamGridDB CDN URL at all -- it must never be reused for the hero fetch",
            body.contains("downloadGated"),
        )
    }

    @Test
    fun `the hero installs at the HERO slot under the shared HeroSuffix, default create-only overwrite`() {
        val body = fetchOnePcArtworkBody()

        assertTrue(
            "search window missed the install wiring -- assertions below would pass vacuously",
            body.contains("installDownloadedMediaAtCurrentGame("),
        )
        assertTrue(
            "the hero must land in the same slot/suffix ScreenScraper's own hero write uses, so " +
                "LocalArtSource.heroUriFor and the Library's single-hero-slot rule see it identically",
            body.contains("slot = ManagedMediaSlot.HERO,") && body.contains("nameSuffix = HeroSuffix,"),
        )
        assertFalse(
            "overwriteRequested must stay at its default (false) -- an automatic hero never replaces an " +
                "existing one; only the chosen-hero picker or the legacy-repair ladder may overwrite",
            body.contains("overwriteRequested = true"),
        )
    }

    @Test
    fun `a hero written while the game already has its tile is still published, before the hasTile return`() {
        // This is the differential guard for the "only visible after a process restart" defect class: a
        // bare media_art/ write touches no Room table, and the hasTile early return two lines below can
        // exit the function before the LATER shared publish call (guarding the square-art fetch) ever
        // runs. Without an immediate publish here, a hero-only success on an already-tiled game would
        // render only on the next cold platform open / process restart.
        val body = fetchOnePcArtworkBody()

        val publishIdx = body.indexOf(
            "if (heroWritten != null) publishWrittenMedia(context, listOf(heroWritten.asChangedMedia()))",
        )
        val hasTileIdx = body.indexOf(
            "if (SteamGridDbTileFetcher.hasTile(context, cacheKey)) return OneResult.Matched",
        )

        assertTrue(
            "search window missed one of the two anchors -- the ordering assertion would pass vacuously; " +
                "found publish=$publishIdx hasTile=$hasTileIdx",
            publishIdx >= 0 && hasTileIdx >= 0,
        )
        assertTrue(
            "the hero publish must run BEFORE the hasTile early return, or a hero written for an " +
                "already-tiled game is never published and stays invisible until a process restart",
            publishIdx < hasTileIdx,
        )
    }

    @Test
    fun `a hero-only success is folded into the final Matched verdict`() {
        val body = fetchOnePcArtworkBody()

        assertTrue(
            "search window missed the final verdict line -- the assertion below would pass vacuously",
            body.contains("fetched > 0 || portraitFetched > 0 || descriptionPersisted"),
        )
        assertTrue(
            "a hero landing on disk with no qualifying square/portrait grid and no description change " +
                "must still report Matched, not NoMatch -- the same dishonesty class the portrait fix " +
                "and the description fold both already close",
            body.contains(
                "fetched > 0 || portraitFetched > 0 || descriptionPersisted || markerPersisted || " +
                    "heroWritten != null",
            ),
        )
    }

    @Test
    fun `the automatic hero fetch is confined to fetchOnePcArtwork -- a non-pc_games row never reaches it`() {
        // Differential guard for "a non-pc_games row is completely unaffected": the hero fetch call is
        // reachable ONLY from inside fetchOnePcArtwork's own window (its body plus the shared
        // fetchAndPersistSteamGridDbGame persist body declared right after it, which only it calls).
        // Every non-pc_games platform is routed
        // by the UNCHANGED `PlatformId.PC_GAMES` branch in fetchOneArtwork (pinned by
        // PcEnrichSteamGridDbRoutingWiringTest) to fall through to the original ScreenScraper path instead
        // -- so if the hero call ever leaked outside fetchOnePcArtwork's body, this test would catch it.
        val fullSource = sourceOf("ArtworkFetchController.kt")
        val beforePcFn = fullSource.substringBefore(
            "private suspend fun fetchOnePcArtwork(context: Context, g: GameEntity, explicitPress: Boolean): OneResult {",
        )
        val afterPcFnBody = fullSource
            .substringAfter("private suspend fun fetchOnePcArtwork(context: Context, g: GameEntity, explicitPress: Boolean): OneResult {")
            .substringAfter("/** Compatibility entry point for the retired per-game Get art name. */")

        assertFalse(
            "the hero fetch call must not exist anywhere before fetchOnePcArtwork's own declaration",
            beforePcFn.contains("steamGridDbHeroesForGame"),
        )
        assertFalse(
            "the hero fetch call must not leak into any function declared after fetchOnePcArtwork either",
            afterPcFnBody.contains("steamGridDbHeroesForGame"),
        )
    }
}

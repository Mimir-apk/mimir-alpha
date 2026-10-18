package com.gruning.mimir.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-text characterization of ENRICH for `pc_games`: pressing ENRICH on a
 * `pc_games` row used to run a ScreenScraper lookup that can never succeed (R-24: ScreenScraper has no
 * `pc_games` system id), then tell the player it "couldn't verify this game on ScreenScraper" — naming a
 * provider that structurally cannot identify that platform. The fix routes `pc_games` to SteamGridDB by
 * Steam app id BEFORE the ScreenScraper gate, and the overlay names whichever provider actually ran.
 *
 * `ArtworkFetchController.fetchOneArtwork`/`fetchOnePcArtwork` resolve a Room row and (on the SteamGridDB
 * route) a Keystore-backed API key (`SteamGridDbKeyStore.resolvedKey`) — a genuine JVM-unit-test run of
 * the routing itself is unreachable from this module: a sweep of `app/src/test` before writing this test
 * found no precedent combining Robolectric with a real `MimirDatabaseFactory`/Room database, and
 * `SteamGridDbKeyStore`/`resolvedKey` is exercised only by an instrumented device test elsewhere in this
 * repo, never a JVM unit test. So the routing claims here are pinned as source text, this repo's
 * established convention for exactly this gap (see [PcTileAppIdWiringTest],
 * [ExactAppIdSizePassthroughCoordinatorWiringTest]) — every window below is guarded so a missed slice
 * cannot pass vacuously.
 *
 * NOT everything about this fix needs that escape hatch, and it is not used where a real test is
 * reachable: [SteamGridDbTileHasTileTest] runtime-tests the tile-presence check under Robolectric,
 * `MimirArtworkStateFetchOneAutoDismissTest` in `MimirArtworkCoordinatorTest.kt` runtime-tests provider
 * selection reaching the overlay's state, and `GetArtOverlayProviderCopyTest` renders [GetArtOverlay]
 * itself under Robolectric Compose (this repo's own precedent for exactly that, e.g.
 * `SettingsLibraryReadFailureRenderTest`) and asserts on the actual rendered text — a plain composable
 * with no coordinator/ViewModel dependency, unlike the DB/Keystore-bound controller functions below.
 *
 * Differential: reorder the `pc_games` branch back after the ScreenScraper gate, or drop the SteamGridDB
 * gate's own check, or short-circuit an app-id-less row to NoMatch before the name search runs (the
 * reported 2026-09-11 defect), and one of the tests below fails.
 */
class PcEnrichSteamGridDbRoutingWiringTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    private fun fetchOneArtworkBody(): String = sourceOf("ArtworkFetchController.kt")
        .substringAfter("suspend fun fetchOneArtwork(")
        .substringBefore("private suspend fun fetchOnePcArtwork(")

    /**
     * R6-P3-01 review fix: R6-P2-03 lever F's bulk serial PC pass, isolated so a guard can pin exactly
     * how it calls [fetchOneArtwork] -- BULK must never look like a user's own Enrich press.
     */
    private fun fetchAllArtworkBody(): String = sourceOf("ArtworkFetchController.kt")
        .substringAfter("suspend fun fetchAllArtwork(context: Context, onProgress: (Progress) -> Unit = {}): FetchResult =")
        .substringBefore("Compatibility entry point retained for existing callers")

    private fun fetchOnePcArtworkBody(): String = sourceOf("ArtworkFetchController.kt")
        .substringAfter("private suspend fun fetchOnePcArtwork(context: Context, g: GameEntity, explicitPress: Boolean): OneResult {")
        .substringBefore("/** Compatibility entry point for the retired per-game Get art name. */")

    /**
     * T1 item 4 (gate review): [fetchOnePcArtwork] calls [fetchAndPersistSteamDescription] one call-hop
     * out rather than inlining the Steam-description persist, so a literal-text scan of
     * [fetchOnePcArtworkBody] alone cannot see what that helper itself does. Isolated the same way
     * [fetchOnePcArtworkBody] is, so the guard below can restore its reach into the helper's own body.
     */
    private fun fetchAndPersistSteamDescriptionBody(): String = sourceOf("ArtworkFetchController.kt")
        .substringAfter("private suspend fun fetchAndPersistSteamDescription(")
        .substringBefore("private fun fetchSteamStoreDescription(")

    /**
     * R6-P3-01: [fetchAndPersistIgdbDescription] is the same one-call-hop-out shape as
     * [fetchAndPersistSteamDescriptionBody] above, for the exact same poisoning reason -- isolated so the
     * three-store guard below can see into it.
     */
    private fun fetchAndPersistIgdbDescriptionBody(): String = sourceOf("ArtworkFetchController.kt")
        .substringAfter("private suspend fun fetchAndPersistIgdbDescription(")
        .substringBefore("private fun igdbOutcomeLogName(")

    @Test
    fun `a pc_games row is routed BEFORE the ScreenScraper-specific gate can refuse it`() {
        val body = fetchOneArtworkBody()

        val rowResolvedIndex = body.indexOf("membersOfGame(currentGameId).firstOrNull()")
        val pcBranchIndex = body.indexOf("fetchOnePcArtwork(context, g, explicitPress)")
        val screenScraperOnlyGateIndex =
            body.indexOf("if (!MimirConnectedArtGate.isOpen) return@withContext OneResult.GateClosed")

        assertTrue(
            "search window missed one of the three anchors -- every ordering assertion below would " +
                "pass vacuously; found row=$rowResolvedIndex pcBranch=$pcBranchIndex " +
                "screenScraperOnlyGate=$screenScraperOnlyGateIndex",
            rowResolvedIndex >= 0 && pcBranchIndex >= 0 && screenScraperOnlyGateIndex >= 0,
        )
        assertTrue(
            "the row must be resolved before the pc_games branch can decide routing",
            rowResolvedIndex < pcBranchIndex,
        )
        assertTrue(
            "a pc_games row must reach its own branch BEFORE the ScreenScraper-only gate can refuse it " +
                "-- this is the exact device defect: 'Mimir couldn't verify this game on ScreenScraper' " +
                "for a platform ScreenScraper can never identify (R-24)",
            pcBranchIndex < screenScraperOnlyGateIndex,
        )
    }

    @Test
    fun `the outer short-circuit requires BOTH provider gates closed, never ScreenScraper alone`() {
        val body = fetchOneArtworkBody().substringBefore("val database = MimirDatabaseFactory.get(context)")

        assertTrue(
            "search window missed the outer gate slice -- assertion below would pass vacuously",
            body.contains("OneResult.GateClosed"),
        )
        assertTrue(
            "a closed ScreenScraper gate alone must not refuse a pc_games row whose own SteamGridDB " +
                "gate is open -- found: <$body>",
            body.contains("if (!MimirConnectedArtGate.isOpen && !MimirSteamGridDbGate.isOpen)"),
        )
    }

    @Test
    fun `the non-pc_games path keeps the original ScreenScraper gate and attach calls, unchanged`() {
        // Differential guard: every platform other than pc_games must be completely unaffected --
        // the ORIGINAL gate check and attach sequence must still run, immediately after the pc_games
        // branch, for the fall-through (non-pc_games) case.
        val body = fetchOneArtworkBody()

        assertTrue(
            "search window missed the non-pc_games fall-through -- assertions below would pass vacuously",
            body.contains("fetchOnePcArtwork(context, g, explicitPress)") && body.contains("ArtworkConnectController.attach(context)"),
        )
        val afterPcBranch = body.substringAfter("fetchOnePcArtwork(context, g, explicitPress)")
        val gateIndex = afterPcBranch.indexOf("if (!MimirConnectedArtGate.isOpen) return@withContext OneResult.GateClosed")
        val attachIndex = afterPcBranch.indexOf("ArtworkConnectController.attach(context)")
        val forceLookupIndex = afterPcBranch.indexOf("forceLookup = true")

        assertTrue(
            "the non-pc_games gate check and attach sequence must all still be present after the " +
                "pc_games branch; found gate=$gateIndex attach=$attachIndex forceLookup=$forceLookupIndex",
            gateIndex >= 0 && attachIndex >= 0 && forceLookupIndex >= 0,
        )
        assertTrue(
            "the ScreenScraper gate must still run before attach()/the lookup for every other platform, " +
                "exactly as it did before this change",
            gateIndex < attachIndex && attachIndex < forceLookupIndex,
        )
    }

    @Test
    fun `fetchOnePcArtwork never consults the ScreenScraper gate or writes ScreenScraper-attributed state`() {
        val body = fetchOnePcArtworkBody()

        assertTrue(
            "search window missed fetchOnePcArtwork's body -- every assertion below would pass vacuously",
            body.contains("SteamGridDbKeyStore.resolvedKey(context)") && body.contains("fetchMissing("),
        )
        assertFalse(
            "the SteamGridDB route must never reference the ScreenScraper connection gate; found: <$body>",
            body.contains("MimirConnectedArtGate"),
        )
        assertTrue(
            "the SteamGridDB route's own gate must check both the resolved key and MimirSteamGridDbGate",
            body.contains("apiKey.isBlank() || !MimirSteamGridDbGate.isOpen"),
        )
        assertFalse(
            "the SteamGridDB route must never touch ArtResolutionStore, ArtNoMatchStore, or " +
                "ScreenScraperMetadataCache -- an SGDB id fed back as a providerGameId would poison a " +
                "later ScreenScraper query and disable DB01 legacy repair",
            body.contains("ArtResolutionStore.") ||
                body.contains("ArtNoMatchStore.") ||
                body.contains("ScreenScraperMetadataCache."),
        )
        // T1 item 4 (gate review): the Steam description persist sits one call-hop out, in
        // fetchAndPersistSteamDescription -- a literal-text scan of fetchOnePcArtwork's OWN body (above)
        // cannot see what that helper does, so it could no longer catch a ScreenScraper-attributed write
        // placed inside the helper. Repeat the exact same three-store guard against the helper's body so
        // this test's reach covers the call it was blind to.
        val descriptionBody = fetchAndPersistSteamDescriptionBody()
        assertTrue(
            "search window missed fetchAndPersistSteamDescription's body -- the assertion below would " +
                "pass vacuously",
            descriptionBody.contains("VerifiedMetadataCache.persistVerifiedRecord("),
        )
        assertFalse(
            "the Steam description helper -- one call-hop out of fetchOnePcArtwork -- must never touch " +
                "ArtResolutionStore, ArtNoMatchStore, or ScreenScraperMetadataCache either, for the exact " +
                "same poisoning reason as fetchOnePcArtwork's own body above; found: <$descriptionBody>",
            descriptionBody.contains("ArtResolutionStore.") ||
                descriptionBody.contains("ArtNoMatchStore.") ||
                descriptionBody.contains("ScreenScraperMetadataCache."),
        )
        // R6-P3-01: the IGDB last-resort description helper is a second call-hop out (from BOTH the
        // SteamApp and name-resolved arms), so the same three-store guard is repeated against ITS body.
        val igdbDescriptionBody = fetchAndPersistIgdbDescriptionBody()
        assertTrue(
            "search window missed fetchAndPersistIgdbDescription's body -- the assertion below would " +
                "pass vacuously",
            igdbDescriptionBody.contains("VerifiedMetadataCache.persistVerifiedRecord("),
        )
        assertFalse(
            "the IGDB description helper must never touch ArtResolutionStore, ArtNoMatchStore, or " +
                "ScreenScraperMetadataCache either, for the exact same poisoning reason; " +
                "found: <$igdbDescriptionBody>",
            igdbDescriptionBody.contains("ArtResolutionStore.") ||
                igdbDescriptionBody.contains("ArtNoMatchStore.") ||
                igdbDescriptionBody.contains("ScreenScraperMetadataCache."),
        )
    }

    /**
     * Reported provider mismatch, 2026-09-11: on a Nova test device the row `title:pc_games:blasphemous 2` has an EMPTY managedPath, so
     * [steamAppIdForManagedPath] is null and this route used to `?: return OneResult.NoMatch` before
     * SteamGridDB was asked anything -- then the card said "SteamGridDB had no art for this game." The Home
     * shelf's picker found the same game by NAME through the same autocomplete route. The lookup contract uses `.steam` app IDs and filenames for the other four shortcut types: the name half was never wired into ENRICH.
     *
     * Differential: reintroduce `steamAppIdForManagedPath(g.managedPath) ?: return OneResult.NoMatch`, or
     * route the plan's NoTitle/NoMatch/Failed arms to any other OneResult, or resolve the name through
     * the Home picker's preview-downloading `candidatesFor(` or the null-swallowing `searchFirstGameId`
     * (which cannot tell a transport failure from a miss), and this test fails.
     */
    @Test
    fun `an unresolvable app id falls back to a name search, and only a blank title ends without asking`() {
        val body = fetchOnePcArtworkBody()

        assertTrue(
            "search window missed the app-id resolution line -- assertions below would pass vacuously",
            body.contains(
                "val appId = steamAppIdForShortcut(context, g.managedPath, g.sourceDocumentUri, g.displayName)",
            ),
        )
        assertFalse(
            "the pre-fix short-circuit must be gone: a null app id is a NameSearch plan, never an " +
                "immediate NoMatch",
            body.contains(
                "steamAppIdForShortcut(context, g.managedPath, g.sourceDocumentUri, g.displayName) ?: " +
                    "return OneResult.NoMatch",
            ),
        )
        assertTrue(
            "the identification decision must be the pure pcArtworkLookupPlan, so the JVM test of that " +
                "function (ArtworkFetchControllerTest) actually pins what this route does",
            body.contains("val plan = pcArtworkLookupPlan(appId, title)"),
        )
        assertTrue(
            "the NameSearch plan must query SteamGridDB with its own title + candidates through " +
                "searchGameByTitle -- the shared sgdbTitleMatches-validated resolver the Home picker uses",
            body.contains("SteamGridDbTileFetcher.searchGameByTitle(apiKey, plan.title, plan.candidates)"),
        )
        assertTrue(
            "a name-search miss is an honest NoMatch (SteamGridDB WAS asked), a failure is Failed, and a " +
                "blank title is NoTitle -- three distinct verdicts, never collapsed into one",
            body.contains("SteamGridDbTileFetcher.TitleSearch.NoMatch -> PcGameLookup.Ended(OneResult.NoMatch)") &&
                body.contains("SteamGridDbTileFetcher.TitleSearch.Failed -> PcGameLookup.Ended(OneResult.Failed)") &&
                body.contains("PcArtworkLookupPlan.NoTitle -> PcGameLookup.Ended(OneResult.NoTitle)"),
        )
        assertTrue(
            "both plans must run the SAME persist body once a game is resolved, so the app-id and name " +
                "routes cannot drift in what they save",
            body.contains(
                "fetchAndPersistSteamGridDbGame(context, g, apiKey, title, lookup.game, explicitPress)",
            ),
        )
        assertFalse(
            "the name path must not reuse the Home picker's candidatesFor (it downloads preview thumbs) " +
                "or the null-swallowing searchFirstGameId (a transport failure would read as 'no art')",
            body.contains("searchFirstGameId") || body.contains("candidatesFor("),
        )
    }

    @Test
    fun `the identification line is logged once per press, after the verdict`() {
        val body = fetchOnePcArtworkBody()

        val logIndex = body.indexOf("Log.i(\n            PcLookupLogTag,")
        val verdictIndex = body.indexOf("val result = when (lookup) {")
        assertTrue(
            "search window missed the log line or the verdict -- the ordering assertion would pass " +
                "vacuously; found log=$logIndex verdict=$verdictIndex",
            logIndex >= 0 && verdictIndex >= 0,
        )
        assertTrue(
            "the line must carry the plan, the resolved SteamGridDB game and the verdict",
            body.contains("plan=\${pcArtworkLookupPlanLogName(plan)}") &&
                body.contains("sgdbGame=") &&
                body.contains("result=\${oneResultLogName(result)}"),
        )
        assertTrue(
            "logged once, after the verdict is known -- never per candidate or per asset",
            verdictIndex < logIndex,
        )
    }

    @Test
    fun `an existing tile is reported Matched before fetchMissing is ever called`() {
        // fetchMissing itself only silently SKIPS an existing tile file -- a bare `count > 0` reading of
        // its return value would misreport NoMatch for a game that already has art.
        val body = fetchOnePcArtworkBody()

        val hasTileIndex = body.indexOf("SteamGridDbTileFetcher.hasTile(context, cacheKey)")
        val fetchMissingIndex = body.indexOf("SteamGridDbTileFetcher.fetchMissing(")

        assertTrue(
            "search window missed one of the two anchors -- the ordering assertion would pass vacuously",
            hasTileIndex >= 0 && fetchMissingIndex >= 0,
        )
        assertTrue(
            "presence must be checked, and must short-circuit to Matched, before fetchMissing runs",
            hasTileIndex < fetchMissingIndex,
        )
        assertTrue(
            "an existing tile must report Matched, not silently fall through to a live fetch",
            body.substringAfter("SteamGridDbTileFetcher.hasTile(context, cacheKey))")
                .substringBefore("\n")
                .contains("return OneResult.Matched"),
        )
    }

    @Test
    fun `the Steam description hook runs only for a Steam-app-id game, and before the existing-tile short-circuit`() {
        // the
        // description fetch inherits the SAME identity precondition the exact-id art fetch enforces -- no
        // Steam app id, no storefront request leaves the device -- and must not be skipped by the
        // already-has-a-tile early return, or a returning player's description would never get filled in.
        // A name-resolved SteamGridDB entry  carries no Steam identity, so that
        // plan has no description step at all rather than a guessed one.
        // R6-P1-04: the settle marker inherits the same ordering requirement -- a game already tiled
        // under the pre-fix code (Nova's Blasphemous 2) must still settle its marker on a later Enrich
        // press, so the marker write must also run before the hasTile early return, not after it.
        val body = fetchOnePcArtworkBody()

        val appIdIndex = body.indexOf(
            "val appId = steamAppIdForShortcut(context, g.managedPath, g.sourceDocumentUri, g.displayName)",
        )
        val descriptionCallIndex = body.indexOf(
            "is SteamGridDbGameRef.SteamApp -> fetchAndPersistSteamDescription(context, g, game.appId)",
        )
        // R6-P3-01 re-anchor: a name-resolved game no longer skips the description step outright -- it
        // now runs IGDB's own name search (the PC synopsis now uses IGDB).
        val nameResolvedSkipIndex = body.indexOf(
            "explicitPress && fetchAndPersistIgdbDescription(context, g, listOf(title) + sgdbSearchCandidates(title))",
        )
        val markerIndex = body.indexOf("persistSteamGridDbVerifiedMarker(context, g)")
        val hasTileIndex = body.indexOf("SteamGridDbTileFetcher.hasTile(context, cacheKey)")

        assertTrue(
            "search window missed one of the five anchors -- every ordering assertion below would " +
                "pass vacuously; found appId=$appIdIndex description=$descriptionCallIndex " +
                "nameResolvedSkip=$nameResolvedSkipIndex marker=$markerIndex hasTile=$hasTileIndex",
            appIdIndex >= 0 && descriptionCallIndex >= 0 && nameResolvedSkipIndex >= 0 &&
                markerIndex >= 0 && hasTileIndex >= 0,
        )
        assertTrue(
            "no resolved app id, no description request -- the storefront call is keyed by the exact " +
                "SteamApp reference only, never a name-resolved game",
            appIdIndex < descriptionCallIndex,
        )
        assertTrue(
            "the description fetch must run BEFORE the already-has-a-tile early return, or a game that " +
                "already has its SteamGridDB tile would never get its description filled in on a later " +
                "Enrich press",
            descriptionCallIndex < hasTileIndex,
        )
        assertTrue(
            "the settle marker must run AFTER the description step (it only fires when the description " +
                "step didn't persist one) and BEFORE the already-has-a-tile early return, or an " +
                "already-tiled row (Nova's Blasphemous 2) would silently stop settling on a later Enrich " +
                "press while every other test here stays green (R6-P1-04)",
            descriptionCallIndex < markerIndex && markerIndex < hasTileIndex,
        )
    }

    @Test
    fun `a durable Steam description write is folded into the final Matched verdict`() {
        val body = fetchOnePcArtworkBody()

        assertTrue(
            "search window missed the final verdict line -- the assertion below would pass vacuously",
            body.contains("fetched > 0 || portraitFetched > 0 || descriptionPersisted"),
        )
    }

    /**
     * R6-P3-01 review fix (blocking finding 1): the policy is IGDB on an EXPLICIT Enrich press
     * only. Before this test existed, R6-P2-03 lever F's bulk serial PC pass called
     * `fetchOneArtwork(context, gameId)` with no way to tell it apart from a real Enrich press, so a
     * bulk "Fetch artwork" run silently reached the IGDB rung for every `pc_games` row it walked.
     * `explicitPress` closes that: the bulk pass must pass `false`, and both description call sites in
     * [fetchOnePcArtworkBody] must gate their IGDB call on it.
     */
    @Test
    fun `the bulk lever-F PC pass cannot reach IGDB -- only an explicit press can`() {
        val bulkBody = fetchAllArtworkBody()
        val bulkCallIndex = bulkBody.indexOf(
            "fetchOne = { gameId -> fetchOneArtwork(context, gameId, explicitPress = false) },",
        )
        assertTrue(
            "search window missed lever F's own fetchOneArtwork call -- the assertion below would " +
                "pass vacuously; found bulkCall=$bulkCallIndex",
            bulkCallIndex >= 0,
        )
        assertFalse(
            "the bulk pass must never call fetchOneArtwork with its default explicitPress=true (that " +
                "reads exactly like a user's own Enrich press to everything downstream); found: <$bulkBody>",
            bulkBody.contains("fetchOneArtwork(context, gameId)"),
        )

        val pcBody = fetchOnePcArtworkBody()
        assertTrue(
            "the SteamApp description arm must gate its IGDB fallback on explicitPress",
            pcBody.contains(
                "(explicitPress && fetchAndPersistIgdbDescription(context, g, listOf(title) + " +
                    "sgdbSearchCandidates(title)))",
            ),
        )
        val gameArmIndex = pcBody.indexOf("is SteamGridDbGameRef.Game ->")
        // startIndex = gameArmIndex: the SteamApp arm above ALSO contains this exact substring (inside
        // its own parenthesized `(explicitPress && ...)`), so an unscoped indexOf would find that
        // earlier occurrence instead of the Game arm's own call.
        val gameArmCallIndex = pcBody.indexOf(
            "explicitPress && fetchAndPersistIgdbDescription(context, g, listOf(title) + sgdbSearchCandidates(title))",
            startIndex = gameArmIndex,
        )
        assertTrue(
            "the name-resolved (Game) description arm must gate its ONLY IGDB call on explicitPress " +
                "-- found gameArm=$gameArmIndex call=$gameArmCallIndex",
            gameArmIndex in 0 until gameArmCallIndex,
        )
    }

    // The overlay's rendered copy is NOT characterized here -- see [GetArtOverlayProviderCopyTest], a
    // real Robolectric Compose render, which this repo's own precedent (`SettingsLibraryReadFailureRenderTest`)
    // shows is reachable for a plain, coordinator-free composable like GetArtOverlay.
}

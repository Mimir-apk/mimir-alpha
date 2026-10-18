package com.gruning.mimir.rewrite

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.resolve
import com.gruning.mimir.rewrite.core.ui.MimirCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class HomeCustomEnrichPickerTest {
    @Test
    fun lateExpandedPreviewCannotOverwriteOrResurrectTheCurrentRequest() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("expanded-preview-race").toFile()
        try {
            val first = homeCustomEnrichExpandedPreviewRequest(
                cacheDir = root,
                sessionId = "session",
                generation = 1,
                resultId = "first",
                nonce = 1u,
            )
            val second = homeCustomEnrichExpandedPreviewRequest(
                cacheDir = root,
                sessionId = "session",
                generation = 1,
                resultId = "second",
                nonce = 2u,
            )
            assertNotEquals(first.target, second.target)

            var currentToken: String? = first.token
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val lateFirst = async {
                stageHomeCustomEnrichExpandedPreview(
                    request = first,
                    isCurrent = { it == currentToken },
                    stage = { target ->
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                        target.parentFile?.mkdirs()
                        target.writeText("first")
                        CustomHeroStageOutcome.READY
                    },
                )
            }
            firstEntered.await()

            currentToken = second.token
            val currentSecond = async {
                stageHomeCustomEnrichExpandedPreview(
                    request = second,
                    isCurrent = { it == currentToken },
                    stage = { target ->
                        target.parentFile?.mkdirs()
                        target.writeText("second")
                        CustomHeroStageOutcome.READY
                    },
                )
            }.await()
            releaseFirst.complete(Unit)

            assertEquals(HomeCustomEnrichExpandedPreviewSettlement.Stale, lateFirst.await())
            assertTrue(currentSecond is HomeCustomEnrichExpandedPreviewSettlement.Current)
            assertFalse(first.target.exists())
            assertEquals("second", second.target.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun completedPreviewIsCarriedIntoTheNextPrepareAndRemovedWithinItsSession() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("expanded-preview-ready-next").toFile()
        try {
            val firstRequest = homeCustomEnrichExpandedPreviewRequest(
                cacheDir = root,
                sessionId = "session",
                generation = 4,
                resultId = "first",
                nonce = 1u,
            )
            firstRequest.target.parentFile?.mkdirs()
            firstRequest.target.writeText("first")
            val firstResult = HomeCustomEnrichResult.Hero(
                heroCandidate(1_920, 1_080).copy(
                    remoteUrl = "https://cdn2.steamgriddb.com/hero/first.png",
                    providerAssetId = "first",
                ),
            )
            val secondResult = HomeCustomEnrichResult.Hero(
                heroCandidate(1_920, 1_080).copy(
                    remoteUrl = "https://cdn2.steamgriddb.com/hero/second.png",
                    providerAssetId = "second",
                ),
            )
            val readyState = state(kind = EnrichMediaKind.HERO).copy(
                generation = 4,
                results = listOf(firstResult, secondResult),
                expandedPreview = HomeCustomEnrichExpandedPreview.Ready(
                    resultId = firstResult.stableId,
                    localPath = firstRequest.target.absolutePath,
                ),
            )
            val secondAsset = HomeCustomEnrichAsset.Result(secondResult)
            val authority = authorityFor(readyState, homeCustomEnrichAssetKey(readyState, secondAsset))

            val transition = reduceHomeCustomEnrich(
                authority,
                readyState,
                ControllerAction.SYSTEM_SELECT,
            )
            val command = transition.command as HomeCustomEnrichCommand.PrepareExpandedPreview
            assertEquals(firstRequest.target.absolutePath, command.priorLocalPath)
            assertTrue(
                deleteHomeCustomEnrichPriorExpandedPreview(
                    cacheDir = root,
                    sessionId = readyState.sessionId,
                    localPath = command.priorLocalPath,
                ),
            )
            assertFalse(firstRequest.target.exists())

            val secondRequest = homeCustomEnrichExpandedPreviewRequest(
                cacheDir = root,
                sessionId = readyState.sessionId,
                generation = readyState.generation,
                resultId = command.resultId,
                nonce = 2u,
            )
            secondRequest.target.parentFile?.mkdirs()
            secondRequest.target.writeText("second")
            assertEquals("second", secondRequest.target.readText())
        } finally {
            root.deleteRecursively()
        }
    }


    @Test
    fun androidHeroSearchRoutesToSteamGridDbWithoutPretendingItHasAnEmulatedGame() {
        assertEquals(
            HomeCustomEnrichHeroProviderRoute(steamGridDb = true, screenScraper = false),
            homeCustomEnrichHeroProviderRoute(source = "Both", hasEmulatedGame = false),
        )
        assertEquals(
            HomeCustomEnrichHeroProviderRoute(steamGridDb = true, screenScraper = false),
            homeCustomEnrichHeroProviderRoute(source = "SteamGridDB", hasEmulatedGame = false),
        )
        assertEquals(
            HomeCustomEnrichHeroProviderRoute(steamGridDb = false, screenScraper = false),
            homeCustomEnrichHeroProviderRoute(source = "ScreenScraper", hasEmulatedGame = false),
        )
        assertEquals(
            HomeCustomEnrichHeroProviderRoute(steamGridDb = true, screenScraper = true),
            homeCustomEnrichHeroProviderRoute(source = "Both", hasEmulatedGame = true),
        )
    }

    // ── R-24 pc_games ownership  ────────────────────────

    @Test
    fun `pc games hero search excludes screenscraper even though the game IS emulated`() {
        // Unlike the Android case above, hasEmulatedGame is TRUE here -- a pc_games title has a real
        // GameRow and launches through GameNative. It is excluded anyway: ScreenScraper matches ROM
        // files by hash/name and structurally cannot identify a GameNative shortcut.
        assertEquals(
            HomeCustomEnrichHeroProviderRoute(steamGridDb = true, screenScraper = false),
            homeCustomEnrichHeroProviderRoute(
                source = "Both",
                hasEmulatedGame = true,
                platformId = PlatformId.PC_GAMES,
            ),
        )
        assertEquals(
            HomeCustomEnrichHeroProviderRoute(steamGridDb = true, screenScraper = false),
            homeCustomEnrichHeroProviderRoute(
                source = "SteamGridDB",
                hasEmulatedGame = true,
                platformId = PlatformId.PC_GAMES,
            ),
        )
        // Explicitly asking for ScreenScraper on a pc_games title is the same honest dead-end the
        // Android case already accepts above (both providers false) -- not a silent SteamGridDB swap.
        assertEquals(
            HomeCustomEnrichHeroProviderRoute(steamGridDb = false, screenScraper = false),
            homeCustomEnrichHeroProviderRoute(
                source = "ScreenScraper",
                hasEmulatedGame = true,
                platformId = PlatformId.PC_GAMES,
            ),
        )
    }

    @Test
    fun `a real console platform keeps screenscraper eligible when the game is emulated`() {
        // Differential guard: only pc_games is excluded, not "any platformId at all".
        assertEquals(
            HomeCustomEnrichHeroProviderRoute(steamGridDb = true, screenScraper = true),
            homeCustomEnrichHeroProviderRoute(
                source = "Both",
                hasEmulatedGame = true,
                platformId = PlatformId.PS2,
            ),
        )
    }

    // ── pc_games HERO looks itself up by Steam app id while untouched ─────

    @Test
    fun `pc games hero search uses the exact steam app id when the query is untouched`() {
        // The passing case every guard below flips exactly one variable away from.
        assertTrue(
            homeCustomEnrichPcGameUsesExactAppId(
                platformId = PlatformId.PC_GAMES,
                seededTitle = "Soul Hackers",
                submittedQuery = "Soul Hackers",
                page = 0,
            ),
        )
    }

    @Test
    fun `pc games hero search drops the exact app id route the moment he types`() {
        // Differential guard: a genuinely different title (sequel, not an edition suffix
        // ProviderTitleMatcher would still call the same identity) must win over the seeded app id.
        assertFalse(
            homeCustomEnrichPcGameUsesExactAppId(
                platformId = PlatformId.PC_GAMES,
                seededTitle = "Soul Hackers",
                submittedQuery = "Soul Hackers 2",
                page = 0,
            ),
        )
    }

    @Test
    fun `a real console platform never uses the exact app id route even with an untouched query`() {
        // Differential guard: only pc_games has an app-id route; a real platform never takes it.
        assertFalse(
            homeCustomEnrichPcGameUsesExactAppId(
                platformId = PlatformId.PS2,
                seededTitle = "Soul Hackers",
                submittedQuery = "Soul Hackers",
                page = 0,
            ),
        )
    }

    @Test
    fun `pc games hero search only uses the exact app id route on the first page`() {
        // Differential guard: the exact route returns a single non-paged set, so paging past it
        // must fall back to title search rather than re-asking for a page that does not exist.
        assertFalse(
            homeCustomEnrichPcGameUsesExactAppId(
                platformId = PlatformId.PC_GAMES,
                seededTitle = "Soul Hackers",
                submittedQuery = "Soul Hackers",
                page = 1,
            ),
        )
    }

    // CLIP eligibility follows HERO's route model. HERO's pc_games exclusion never touched needsAccess.
    // CLIP reused needsAccess=true for exclusion, which outranked the empty state and asked a PC game
    // with both providers connected to connect them again.

    @Test
    fun `pc games clip exclusion is structural, never a needs-access claim`() {
        // Both providers connected, real emulated game, and it STILL must not claim needs-access:
        // ScreenScraper cannot answer for this platform at all, which is not something he can fix.
        assertEquals(
            HomeCustomEnrichClipScreenScraperUse(attempt = false, needsAccess = false),
            homeCustomEnrichClipScreenScraperUse(
                source = "Both",
                format = "All",
                page = 0,
                hasScreenScraper = true,
                hasEmulatedGame = true,
                platformId = PlatformId.PC_GAMES,
            ),
        )
    }

    @Test
    fun `a console platform still reports needs-access when screenscraper is not connected`() {
        // Differential guard: the exclusion is pc_games-only. A real platform that is merely
        // unconnected keeps the access prompt, because that one he CAN act on.
        assertEquals(
            HomeCustomEnrichClipScreenScraperUse(attempt = false, needsAccess = true),
            homeCustomEnrichClipScreenScraperUse(
                source = "Both",
                format = "All",
                page = 0,
                hasScreenScraper = false,
                hasEmulatedGame = true,
                platformId = PlatformId.PS2,
            ),
        )
        assertEquals(
            HomeCustomEnrichClipScreenScraperUse(attempt = true, needsAccess = false),
            homeCustomEnrichClipScreenScraperUse(
                source = "Both",
                format = "All",
                page = 0,
                hasScreenScraper = true,
                hasEmulatedGame = true,
                platformId = PlatformId.PS2,
            ),
        )
    }

    @Test
    fun `a pc game with both providers connected and no motion says no matches, not connect them`() {
        // The exact reported regression, end to end. needsAccess is DERIVED from the route rather than
        // hard-coded, because hard-coding it false is what makes this test vacuous: the pre-fix code's
        // whole defect was that the route raised needsAccess here, and a test that assumes the answer
        // cannot see it. (Verified by differential: with the route mutated back, this now fails.)
        val use = homeCustomEnrichClipScreenScraperUse(
            source = "Both",
            format = "All",
            page = 0,
            hasScreenScraper = true,
            hasEmulatedGame = true,
            platformId = PlatformId.PC_GAMES,
        )
        val status = homeCustomEnrichClipSearchStatus(
            resultCount = 0,
            // SteamGridDB was attempted and found nothing; ScreenScraper was structurally skipped.
            attempted = true,
            needsAccess = use.needsAccess,
            unreachable = false,
            providerFailed = false,
            source = "Both",
            platformId = PlatformId.PC_GAMES,
        )
        assertEquals(HomeCustomEnrichSearchStatus.Complete(0), status)
    }

    @Test
    fun `asking a pc game for screenscraper explicitly says why it cannot answer`() {
        // Same sentence HERO already shows for this fact -- one wording, whichever picker he opened.
        assertEquals(
            HomeCustomEnrichSearchStatus.Failed(
                "ScreenScraper can’t identify PC games. Use SteamGridDB instead.",
            ),
            homeCustomEnrichClipSearchStatus(
                resultCount = 0,
                attempted = false,
                needsAccess = false,
                unreachable = false,
                providerFailed = false,
                source = "ScreenScraper",
                platformId = PlatformId.PC_GAMES,
            ),
        )
    }

    @Test
    fun `clip settlement is unchanged for every platform that is not pc games`() {
        // Differential guard on the settle helper: the four inherited clip sentences must still fire.
        assertEquals(
            HomeCustomEnrichSearchStatus.NeedsAccess(
                "Connect ScreenScraper or add a SteamGridDB key in Settings.",
            ),
            homeCustomEnrichClipSearchStatus(
                resultCount = 0,
                attempted = false,
                needsAccess = true,
                unreachable = false,
                providerFailed = false,
                source = "Both",
                platformId = PlatformId.PS2,
            ),
        )
        assertEquals(
            HomeCustomEnrichSearchStatus.Unreachable(
                "Couldn’t reach media providers. Saved motion is still available.",
            ),
            homeCustomEnrichClipSearchStatus(
                resultCount = 0,
                attempted = true,
                needsAccess = false,
                unreachable = true,
                providerFailed = false,
                source = "Both",
                platformId = PlatformId.PS2,
            ),
        )
        // Explicit ScreenScraper on a NON-pc platform must not borrow the pc_games sentence.
        assertEquals(
            HomeCustomEnrichSearchStatus.Complete(0),
            homeCustomEnrichClipSearchStatus(
                resultCount = 0,
                attempted = true,
                needsAccess = false,
                unreachable = false,
                providerFailed = false,
                source = "ScreenScraper",
                platformId = PlatformId.PS2,
            ),
        )
    }

    @Test
    fun `default filters put pc games source on SteamGridDB, everything else on Both`() {
        assertEquals("Both", homeCustomEnrichDefaultFilters(EnrichMediaKind.HERO).first { it.key == "source" }.selected)
        assertEquals(
            "SteamGridDB",
            homeCustomEnrichDefaultFilters(EnrichMediaKind.HERO, preferSteamGridDb = true)
                .first { it.key == "source" }.selected,
        )
        assertEquals(
            "SteamGridDB",
            homeCustomEnrichDefaultFilters(EnrichMediaKind.CLIP, preferSteamGridDb = true)
                .first { it.key == "source" }.selected,
        )
        // Values stay in their normal order -- cycle() must step Both -> SteamGridDB -> ScreenScraper
        // regardless of which one starts selected.
        assertEquals(
            listOf("Both", "SteamGridDB", "ScreenScraper"),
            homeCustomEnrichDefaultFilters(EnrichMediaKind.HERO, preferSteamGridDb = true)
                .first { it.key == "source" }.values,
        )
        // ICON has no Source filter at all (already SteamGridDB-only) -- the flag is a no-op for it.
        assertTrue(
            homeCustomEnrichDefaultFilters(EnrichMediaKind.ICON, preferSteamGridDb = true)
                .none { it.key == "source" },
        )
    }

    @Test
    fun `a new enrich state for pc games opens with source already on SteamGridDB`() {
        val state = newHomeCustomEnrichState(
            sessionId = "s",
            kind = EnrichMediaKind.HERO,
            cacheKey = "cache",
            gameTitle = "Blasphemous 2",
            catalog = EnrichSavedCatalog(activeAssetId = null, entries = emptyList()),
            preferSteamGridDb = true,
        )
        assertEquals("SteamGridDB", state.filterValue("source"))
    }

    @Test
    fun HeroArtAndHeroMotionWithARealPresentationCanExpand() {
        val hero = state(
            saved = listOf(saved("hero", 1L)),
            kind = EnrichMediaKind.HERO,
        )
        assertTrue(
            homeCustomEnrichPreviewCanExpand(
                hero,
                HomeCustomEnrichAsset.Saved(hero.saved.single()),
            ),
        )
        assertTrue(
            homeCustomEnrichPreviewCanExpand(
                hero.copy(defaultArt = HomeCustomEnrichDefaultArt.Automatic("/local/default-hero")),
                HomeCustomEnrichAsset.Automatic,
            ),
        )
        assertFalse(
            homeCustomEnrichPreviewCanExpand(
                state(saved = hero.saved, kind = EnrichMediaKind.ICON),
                HomeCustomEnrichAsset.Saved(hero.saved.single()),
            ),
        )
        assertFalse(homeCustomEnrichPreviewCanExpand(hero, HomeCustomEnrichAsset.Automatic))

        val clipResult = HomeCustomEnrichResult.Clip(
            heroCandidate(1_920, 1_080).copy(
                mediaType = CustomHeroMediaType.VIDEO,
                previewPath = null,
            ),
        )
        val clip = state(kind = EnrichMediaKind.CLIP).copy(
            catalogReady = true,
            results = listOf(clipResult),
        )
        assertTrue(
            homeCustomEnrichPreviewCanExpand(
                clip,
                HomeCustomEnrichAsset.Result(clipResult),
            ),
        )
        assertEquals(
            clipResult.stableId,
            homeCustomEnrichExpandedPreviewNeedsPrepare(HomeCustomEnrichAsset.Result(clipResult)),
        )
    }

    @Test
    fun selectStagesFullHeroMotionForPreviewEvenWhenTheProviderHasNoPoster() {
        val result = HomeCustomEnrichResult.Clip(
            heroCandidate(1_920, 1_080).copy(
                mediaType = CustomHeroMediaType.VIDEO,
                previewPath = null,
            ),
        )
        val clip = state(kind = EnrichMediaKind.CLIP).copy(
            catalogReady = true,
            results = listOf(result),
        )
        val asset = HomeCustomEnrichAsset.Result(result)
        val authority = authorityFor(clip, homeCustomEnrichAssetKey(clip, asset))

        val opened = reduceHomeCustomEnrich(authority, clip, ControllerAction.SYSTEM_SELECT)

        assertTrue(opened.state.previewExpanded)
        assertEquals(
            HomeCustomEnrichCommand.PrepareExpandedPreview(result.stableId),
            opened.command,
        )
        assertEquals(
            HomeCustomEnrichExpandedPreview.Loading(result.stableId),
            opened.state.expandedPreview,
        )
    }

    @Test
    fun selectOpensAndCancelClosesHeroPreviewWithoutLeavingThePicker() {
        val hero = state(
            saved = listOf(saved("hero", 1L)),
            kind = EnrichMediaKind.HERO,
        )
        val savedAsset = HomeCustomEnrichAsset.Saved(hero.saved.single())
        val authority = authorityFor(hero, homeCustomEnrichAssetKey(hero, savedAsset))

        val opened = reduceHomeCustomEnrich(authority, hero, ControllerAction.SYSTEM_SELECT)
        assertTrue(opened.state.previewExpanded)
        assertEquals(HomeCustomEnrichCommand.None, opened.command)

        val closed = reduceHomeCustomEnrich(authority, opened.state, ControllerAction.CANCEL)
        assertFalse(closed.state.previewExpanded)
        assertEquals(HomeCustomEnrichCommand.None, closed.command)
    }

    @Test
    fun selectStagesFullHeroForUnsavedResultAndNeverExpandsItsPickerThumbnail() {
        val result = HomeCustomEnrichResult.Hero(
            heroCandidate(1_920, 1_080).copy(previewPath = "/cache/640x360-picker.jpg"),
        )
        val hero = state(kind = EnrichMediaKind.HERO).copy(
            catalogReady = true,
            results = listOf(result),
        )
        val asset = HomeCustomEnrichAsset.Result(result)
        val authority = authorityFor(hero, homeCustomEnrichAssetKey(hero, asset))

        val opened = reduceHomeCustomEnrich(authority, hero, ControllerAction.SYSTEM_SELECT)
        assertTrue(opened.state.previewExpanded)
        assertEquals(
            HomeCustomEnrichCommand.PrepareExpandedPreview(result.stableId),
            opened.command,
        )
        assertEquals(
            HomeCustomEnrichExpandedPreview.Loading(result.stableId),
            opened.state.expandedPreview,
        )
        assertNull(homeCustomEnrichExpandedPreviewPath(opened.state, asset))

        val ready = opened.state.copy(
            expandedPreview = HomeCustomEnrichExpandedPreview.Ready(
                result.stableId,
                "/cache/full-1920x1080.img",
            ),
        )
        assertEquals(
            "/cache/full-1920x1080.img",
            homeCustomEnrichExpandedPreviewPath(ready, asset),
        )
    }

    @Test
    fun closingAFullHeroStillBeingPreparedCancelsTheTransientInspectJob() {
        val result = HomeCustomEnrichResult.Hero(
            heroCandidate(1_920, 1_080).copy(previewPath = "/cache/640x360-picker.jpg"),
        )
        val loading = state(kind = EnrichMediaKind.HERO).copy(
            previewExpanded = true,
            expandedPreview = HomeCustomEnrichExpandedPreview.Loading(result.stableId),
        )

        val closed = reduceHomeCustomEnrich(
            authorityFor(loading, homeCustomEnrichInitialKey(loading)),
            loading,
            ControllerAction.CANCEL,
        )

        assertFalse(closed.state.previewExpanded)
        assertEquals(HomeCustomEnrichExpandedPreview.None, closed.state.expandedPreview)
        assertEquals(HomeCustomEnrichCommand.CancelExpandedPreview, closed.command)
    }

    @Test
    fun metallicThemeGlassControlsUseTheirAuthoredDarkRaisedSurface() {
        listOf(MimirThemePalette.Folkvangr, MimirThemePalette.Baldr).forEach { palette ->
            assertFalse(palette.usesBrightField)
            assertEquals(
                palette.surfaceRaised.copy(alpha = 0.62f),
                homeCustomEnrichGlassControlFill(palette, lightAlpha = 0.72f, darkAlpha = 0.62f),
            )
            assertNotEquals(
                palette.materials.hero.coverMiddle.copy(alpha = 0.72f),
                homeCustomEnrichGlassControlFill(palette, lightAlpha = 0.72f, darkAlpha = 0.62f),
            )
        }
    }

    @Test
    fun mediaPreparationFailuresAreSpecificAndNeverBlamePosterExtraction() {
        assertEquals(
            "Download failed. Check the connection and try again.",
            homeCustomEnrichStageFailureMessage(CustomHeroStageOutcome.DOWNLOAD_FAILED),
        )
        assertTrue(
            homeCustomEnrichStageFailureMessage(CustomHeroStageOutcome.UNSUPPORTED_MEDIA)
                .startsWith("Unsupported media format."),
        )
        assertTrue(
            homeCustomEnrichStageFailureMessage(CustomHeroStageOutcome.BELOW_QUALITY_FLOOR)
                .contains("quality floor"),
        )
    }

    @Test
    fun downloadedChoicesStateUsesTheOwnerRuledCompactSentence() {
        val resolved = homeCustomEnrichDownloadedChoicesIntent().resolve()
        assertEquals("DOWNLOADED CHOICES READY", resolved.visualText)
        assertEquals("Downloaded choices ready", resolved.spokenText)
        assertEquals(12, HomeCustomEnrichStatusFontSizeSp)
    }

    @Test
    fun statusLaneKeepsOneGeometryAcrossFailureRetryAndSearchStates() {
        assertEquals(17, homeCustomEnrichStatusLaneHeightDp(compactSquare = false, fontScale = 1f))
        assertEquals(34, homeCustomEnrichStatusLaneHeightDp(compactSquare = true, fontScale = 1f))
        assertTrue(
            homeCustomEnrichStatusLaneHeightDp(compactSquare = true, fontScale = 1.3f) >
                homeCustomEnrichStatusLaneHeightDp(compactSquare = true, fontScale = 1f),
        )
    }

    @Test
    fun everyMediaKindHasTruthfulSearchFilters() {
        val iconFilters = homeCustomEnrichDefaultFilters(EnrichMediaKind.ICON)
        assertEquals(listOf("kind", "style", "size"), iconFilters.map { it.key })
        assertEquals("Type", iconFilters.first { it.key == "kind" }.label)
        assertEquals(listOf("source", "size", "fit"), homeCustomEnrichDefaultFilters(EnrichMediaKind.HERO).map { it.key })
        val motionFilters = homeCustomEnrichDefaultFilters(EnrichMediaKind.CLIP)
        assertEquals(listOf("source", "encode"), motionFilters.map { it.key })
        assertTrue("SteamGridDB" in motionFilters.first { it.key == "source" }.values)
        assertTrue("Motion Art" in motionFilters.first { it.key == "encode" }.values)
        assertTrue(homeCustomEnrichDefaultFilters(EnrichMediaKind.TOME_PHYSICAL).isEmpty())
    }

    @Test
    fun iconFilterVocabularyIsContextualAndPhysicalLivesOnlyInTomePhysical() {
        val all = state()
        val icons = all.copy(filters = all.filters.map {
            if (it.key == "kind") it.copy(selectedIndex = 2) else it
        })

        assertEquals(listOf("kind"), all.visibleFilters().map { it.key })
        val grids = all.copy(filters = all.filters.map {
            if (it.key == "kind") it.copy(selectedIndex = 1) else it
        })
        assertEquals(listOf("kind", "style", "size"), grids.visibleFilters().map { it.key })
        assertEquals(listOf("kind", "size"), icons.visibleFilters().map { it.key })
        // Physical results exist only in the TOME_PHYSICAL lane — never in ICON's Kind vocabulary.
        assertEquals(listOf("All", "Grids", "Icons"), all.filters.first().values)
        assertEquals("All", all.effectiveFilterValue("style", hiddenDefault = "All"))
        // Style opens on its all value too , so Grids opens unconstrained; a picked style is what narrows it.
        assertEquals("All", grids.effectiveFilterValue("style", hiddenDefault = "All"))
        val clean = grids.copy(filters = grids.filters.map {
            if (it.key == "style") it.copy(selectedIndex = it.values.indexOf("Clean")) else it
        })
        assertEquals("Clean", clean.effectiveFilterValue("style", hiddenDefault = "All"))
    }

    @Test
    fun explicitPagingHasAHighDefensiveCeilingInsteadOfAFirstPageCeiling() {
        var current = state().copy(
            generation = 9,
            searchStatus = HomeCustomEnrichSearchStatus.Searching("SteamGridDB", 0),
        )
        repeat(260) { index ->
            current = current.appendSearchResult(
                expectedGeneration = 9,
                result = HomeCustomEnrichResult.Icon(
                    SteamGridDbTileFetcher.Candidate(
                        url = "https://cdn2.steamgriddb.com/$index.png",
                        thumbUrl = "https://cdn2.steamgriddb.com/$index-thumb.png",
                    ),
                ),
                provider = "SteamGridDB",
            )
        }

        assertEquals(240, current.results.size)
        assertEquals(120, homeCustomEnrichResultCap(EnrichMediaKind.HERO))
        assertEquals(18, homeCustomEnrichResultCap(EnrichMediaKind.CLIP))
    }

    @Test
    fun compactAndRp5CompositionBudgetsLeaveFullRingClearedGalleryHeight() {
        assertTrue(homeCustomEnrichRequiredHeightDp(EnrichMediaKind.ICON, compactSquare = true) <= 325)
        assertTrue(homeCustomEnrichRequiredHeightDp(EnrichMediaKind.CLIP, compactSquare = true) <= 325)
        assertTrue(homeCustomEnrichRequiredHeightDp(EnrichMediaKind.ICON, compactSquare = false) <= 312)
        assertEquals(148, homeCustomEnrichGalleryMinHeightDp(EnrichMediaKind.ICON, compactSquare = true))
        assertEquals(124, homeCustomEnrichGalleryMinHeightDp(EnrichMediaKind.ICON, compactSquare = false))
    }

    @Test
    fun providerPageMetadataDrivesExplicitMoreResultsWithoutGuessingPastExhaustion() {
        assertTrue(steamGridDbPageHasMore(total = 30, page = 0, limit = 12, loaded = 12))
        assertTrue(steamGridDbPageHasMore(total = 30, page = 1, limit = 12, loaded = 12))
        assertFalse(steamGridDbPageHasMore(total = 30, page = 2, limit = 12, loaded = 6))
        assertTrue(steamGridDbPageHasMore(total = null, page = 0, limit = 12, loaded = 12))
        assertFalse(steamGridDbPageHasMore(total = null, page = 1, limit = 12, loaded = 4))
    }

    @Test
    fun heroBothKeepsTheSameProviderLimitWhenAvailabilityChangesBetweenPages() {
        assertEquals(6, homeCustomEnrichHeroProviderBudget("Both"))
        assertEquals(12, homeCustomEnrichHeroProviderBudget("SteamGridDB"))
        assertEquals(12, homeCustomEnrichHeroProviderBudget("ScreenScraper"))
    }

    @Test
    fun heroBothSettlesCompleteWhenScreenScraperProducesResultsDespiteSteamGridGate() {
        val status = homeCustomEnrichHeroSearchStatus(
            resultCount = 4,
            attempted = true,
            needsAccess = true,
            unreachable = false,
            providerFailed = false,
        )
        assertEquals(HomeCustomEnrichSearchStatus.Complete(4), status)
    }

    @Test
    fun heroBothNeedsAccessOnlyWhenNoUsableProviderResultsExist() {
        assertEquals(
            HomeCustomEnrichSearchStatus.NeedsAccess(
                "Add a SteamGridDB key: Settings › Appearance › Enrichment & artwork.",
            ),
            homeCustomEnrichHeroSearchStatus(
                resultCount = 0,
                attempted = true,
                needsAccess = true,
                unreachable = false,
                providerFailed = false,
                needsAccessMessage =
                    "Add a SteamGridDB key: Settings › Appearance › Enrichment & artwork.",
            ),
        )
        assertEquals(
            HomeCustomEnrichSearchStatus.NeedsAccess(
                "Connect artwork: Settings › Appearance › Enrichment & artwork.",
            ),
            homeCustomEnrichHeroSearchStatus(
                resultCount = 0,
                attempted = false,
                needsAccess = false,
                unreachable = false,
                providerFailed = false,
            ),
        )
        assertEquals(
            HomeCustomEnrichSearchStatus.NeedsAccess(
                "Add a SteamGridDB key: Settings › Appearance › Enrichment & artwork.",
            ),
            homeCustomEnrichHeroSearchStatus(
                resultCount = 0,
                attempted = false,
                needsAccess = true,
                unreachable = false,
                providerFailed = false,
                needsAccessMessage =
                    "Add a SteamGridDB key: Settings › Appearance › Enrichment & artwork.",
            ),
        )
    }

    @Test
    fun providerSearchStatusKeepsUnreachableProviderFailedAndEmptyDistinct() {
        assertEquals(
            HomeCustomEnrichSearchStatus.Unreachable("offline"),
            homeCustomEnrichProviderSearchStatus(
                resultCount = 0,
                attempted = true,
                needsAccess = false,
                unreachable = true,
                providerFailed = false,
                needsAccessMessage = "needs",
                notAttemptedMessage = "not attempted",
                unreachableMessage = "offline",
                providerFailedMessage = "provider failed",
            ),
        )
        assertEquals(
            HomeCustomEnrichSearchStatus.ProviderFailed("provider failed"),
            homeCustomEnrichProviderSearchStatus(
                resultCount = 0,
                attempted = true,
                needsAccess = false,
                unreachable = false,
                providerFailed = true,
                needsAccessMessage = "needs",
                notAttemptedMessage = "not attempted",
                unreachableMessage = "offline",
                providerFailedMessage = "provider failed",
            ),
        )
        assertEquals(
            HomeCustomEnrichSearchStatus.Complete(0),
            homeCustomEnrichProviderSearchStatus(
                resultCount = 0,
                attempted = true,
                needsAccess = false,
                unreachable = false,
                providerFailed = false,
                needsAccessMessage = "needs",
                notAttemptedMessage = "not attempted",
                unreachableMessage = "offline",
                providerFailedMessage = "provider failed",
            ),
        )
    }

    @Test
    fun providerGroupsAreInterleavedWithoutDuplicatesOrFirstMatchMonopoly() {
        fun candidate(name: String) = SteamGridDbTileFetcher.Candidate(
            url = "https://cdn2.steamgriddb.com/$name.png",
            thumbUrl = "https://cdn2.steamgriddb.com/$name-thumb.png",
        )
        val a = candidate("a")
        val b = candidate("b")
        val c = candidate("c")

        assertEquals(
            listOf(a.url, c.url, b.url),
            roundRobinDistinctCandidates(listOf(listOf(a, b), listOf(c, a)), limit = 3).map { it.url },
        )
    }

    @Test
    fun resolutionAndFitFiltersOnlyConstrainVerifiedDimensions() {
        val unknown = heroCandidate(width = 0, height = 0)
        val hd = heroCandidate(width = 2_160, height = 720)
        val providerBanner = heroCandidate(width = 1_920, height = 620)
        val twoK = heroCandidate(width = 3_840, height = 1_080)
        val portrait = heroCandidate(width = 720, height = 2_160)

        // Unknown / unverified dimensions are admitted; verified dimensions keep the existing rules.
        assertTrue(customEnrichHeroMatchesFilters(unknown, size = "Any", fit = "Mimir"))
        assertTrue(customEnrichHeroMatchesFilters(unknown, size = "Any", fit = "Any"))
        assertTrue(customEnrichHeroMatchesFilters(unknown, size = "HD", fit = "Mimir"))
        assertTrue(customEnrichHeroMatchesFilters(hd, size = "HD", fit = "Wide"))
        assertTrue(customEnrichHeroMatchesFilters(providerBanner, size = "HD", fit = "Wide"))
        assertFalse(customEnrichHeroMatchesFilters(hd, size = "2K+", fit = "Any"))
        assertTrue(customEnrichHeroMatchesFilters(twoK, size = "2K+", fit = "Wide"))
        assertFalse(customEnrichHeroMatchesFilters(portrait, size = "HD", fit = "Any"))
        assertTrue(customEnrichHeroMatchesFilters(providerBanner, size = "Any", fit = "Any"))
    }

    @Test
    fun `search result without preview stays actionable instead of unavailable`() {
        val result = HomeCustomEnrichResult.Hero(
            CustomHeroCandidate(
                source = CustomHeroSource.STEAMGRIDDB,
                mediaType = CustomHeroMediaType.STATIC,
                remoteUrl = "https://cdn2.steamgriddb.com/hero.png",
            ),
        )
        val base = state().copy(results = listOf(result))
        val resultAsset = HomeCustomEnrichAsset.Result(result)

        assertEquals("SELECT TO PREPARE", homeCustomEnrichPreviewStatus(base, resultAsset))
        assertEquals("SELECT TO PREPARE", homeCustomEnrichPreviewActionLabel(resultAsset))
        assertEquals("SELECT TO PREPARE", homeCustomEnrichPreviewActionLabel(resultAsset, previewUnavailable = true))
        assertEquals(
            "PREVIEW UNAVAILABLE",
            homeCustomEnrichPreviewStatus(
                base,
                HomeCustomEnrichAsset.Saved(saved("one", 1L).copy(previewPath = "")),
            ),
        )
        assertEquals(
            "PREVIEW UNAVAILABLE",
            homeCustomEnrichPreviewActionLabel(
                HomeCustomEnrichAsset.Saved(saved("one", 1L).copy(previewPath = "")),
                previewUnavailable = true,
            ),
        )
    }

    @Test
    fun `hero preview namespaces include generation media kind provider and page`() {
        assertEquals(
            "manual-4-hero-sgdb-p1",
            homeCustomEnrichHeroPreviewNamespace(4, EnrichMediaKind.HERO, "SteamGridDB", 1),
        )
        assertEquals(
            "manual-4-clip-ss-p0",
            homeCustomEnrichHeroPreviewNamespace(4, EnrichMediaKind.CLIP, "ScreenScraper", 0),
        )
    }

    @Test
    fun `verified screen scraper identity is read without persistence for seeded title`() {
        assertEquals(true to false, homeCustomEnrichScreenScraperIdentityUse("Sonic Adventure", "Sonic Adventure"))
        assertEquals(false to false, homeCustomEnrichScreenScraperIdentityUse("Sonic Adventure", "Sonic"))
    }

    @Test
    fun `screen scraper candidate names stay query first with seeded title fallback`() {
        assertEquals(
            listOf("Chrono Trigger", "Chrono Trigger (USA)"),
            homeCustomEnrichScreenScraperCandidateNames("Chrono Trigger (USA)", "Chrono Trigger"),
        )
        assertEquals(
            listOf("Exact"),
            homeCustomEnrichScreenScraperCandidateNames("Exact", "Exact"),
        )
        assertEquals(
            listOf("Sonic", "Sonic Adventure"),
            homeCustomEnrichScreenScraperCandidateNames("Sonic Adventure", "Sonic"),
        )
    }

    @Test
    fun galleryLeftRightIsFiniteAndNeverWraps() {
        val state = state(saved = listOf(saved("one", 1L), saved("two", 2L)))
        val authority = authorityFor(state, homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Automatic))

        reduceHomeCustomEnrich(authority, state, ControllerAction.NAV_LEFT)
        assertEquals(homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Automatic), authority.state.value.focused)

        authority.requestFocus(homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Saved(state.saved.last())))
        reduceHomeCustomEnrich(authority, state, ControllerAction.NAV_RIGHT)
        assertEquals(
            homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Saved(state.saved.last())),
            authority.state.value.focused,
        )
    }

    @Test
    fun verticalMovementRestoresLastStableKeyPerLane() {
        var state = state(saved = listOf(saved("one", 1L)), active = "one").let { base ->
            base.copy(filters = base.filters.map { filter ->
                if (filter.key == "kind") filter.copy(selectedIndex = 1) else filter
            })
        }
        val lastVisibleFilter = state.visibleFilters().last()
        val authority = authorityFor(state, homeCustomEnrichFilterKey(state, lastVisibleFilter))

        var transition = reduceHomeCustomEnrich(authority, state, ControllerAction.NAV_DOWN)
        state = transition.state
        assertEquals(
            homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Saved(state.saved.first())),
            authority.state.value.focused,
        )

        transition = reduceHomeCustomEnrich(authority, state, ControllerAction.NAV_UP)
        state = transition.state
        assertEquals(homeCustomEnrichFilterKey(state, lastVisibleFilter), authority.state.value.focused)
    }

    @Test
    fun appendingResultsNeverMovesAuthorityFocus() {
        val initial = state(saved = listOf(saved("one", 1L))).copy(
            generation = 4,
            searchStatus = HomeCustomEnrichSearchStatus.Searching("SteamGridDB", 0),
        )
        val focused = homeCustomEnrichAssetKey(initial, HomeCustomEnrichAsset.Saved(initial.saved.first()))
        val authority = authorityFor(initial, focused)

        val appended = initial.appendSearchResult(
            expectedGeneration = 4,
            result = HomeCustomEnrichResult.Icon(
                SteamGridDbTileFetcher.Candidate(
                    url = "https://cdn2.steamgriddb.com/a.png",
                    thumbUrl = "https://cdn2.steamgriddb.com/a-thumb.png",
                    providerAssetId = "44",
                    width = 1024,
                    height = 1024,
                ),
            ),
            provider = "SteamGridDB",
        )

        assertEquals(1, appended.results.size)
        assertEquals(focused, authority.state.value.focused)
    }

    @Test
    fun staleProviderResultsCannotAppend() {
        val remote = "https://cdn2.steamgriddb.com/a.png"
        val initial = state().copy(
            generation = 4,
            searchStatus = HomeCustomEnrichSearchStatus.Searching("SteamGridDB", 0),
        )
        val result = HomeCustomEnrichResult.Icon(
            SteamGridDbTileFetcher.Candidate(url = remote, thumbUrl = "$remote?thumb=1"),
        )

        assertTrue(initial.appendSearchResult(3, result, "SteamGridDB").results.isEmpty())
        assertEquals(1, initial.appendSearchResult(4, result, "SteamGridDB").results.size)
    }

    /**
     * The live RP5 regression, under the policy: Saved is durable history, Results is this
     * response, and the same asset may honestly stand in both. An explicit rerun asks the provider and
     * every current match LANDS — bound to its Saved record so selecting it activates locally, and
     * painted from the Saved file so the rerun costs no second transfer.
     *
     * Fails under the previous suppression logic, which returned the state untouched and settled the
     * identical round as Complete(0) — the "No matches" reported on RP5.
     */
    @Test
    fun anAlreadySavedProviderMatchStillLandsInResultsBoundToItsSavedAsset() {
        val remote = "https://www.screenscraper.fr/image.php?media=support-2d"
        val savedAsset = saved("ss-cartridge", 1L, source = EnrichSavedSource.SCREENSCRAPER)
            .copy(providerRefHash = enrichProviderReferenceHash(remote))
        val initial = state(
            kind = EnrichMediaKind.TOME_PHYSICAL,
            saved = listOf(savedAsset),
        ).copy(
            generation = 4,
            searchStatus = HomeCustomEnrichSearchStatus.Searching("ScreenScraper", 0),
        )
        val result = HomeCustomEnrichResult.Icon(
            SteamGridDbTileFetcher.Candidate(url = remote, thumbUrl = remote, providerAssetId = "support-2d-1"),
        )

        val appended = initial.appendSearchResult(4, result, "ScreenScraper")

        // The provider's answer lands, and the count grows with it.
        assertEquals(1, appended.results.size)
        assertEquals(0, initial.results.size)
        // Bound to the Saved record: selection activates the local asset, never a fresh download.
        assertEquals(savedAsset, appended.results.single().saved)
        // Saved itself is untouched history — no reorder, no eviction, no activation change.
        assertEquals(listOf(savedAsset), appended.saved)
        assertEquals(initial.activeAssetId, appended.activeAssetId)

        val settled = homeCustomEnrichProviderSearchStatus(
            resultCount = appended.results.size,
            attempted = true,
            needsAccess = false,
            unreachable = false,
            providerFailed = false,
            needsAccessMessage = "needs access",
            notAttemptedMessage = "not attempted",
            unreachableMessage = "unreachable",
            providerFailedMessage = "provider failed",
        )
        assertEquals(HomeCustomEnrichSearchStatus.Complete(1), settled)
        assertNotEquals(HomeCustomEnrichSearchStatus.Complete(0), settled)
    }

    @Test
    fun aSavedMatchPaintsFromItsLocalFileInsteadOfHydratingAgain() {
        val remote = "https://www.screenscraper.fr/image.php?media=support-2d"
        val cachedFile = File.createTempFile("mimir-saved-preview", ".png").apply {
            deleteOnExit()
            writeBytes(ByteArray(4_096) { 7 })
        }
        val savedAsset = saved("ss-cartridge", 1L, source = EnrichSavedSource.SCREENSCRAPER).copy(
            providerRefHash = enrichProviderReferenceHash(remote),
            localPath = cachedFile.absolutePath,
            previewPath = cachedFile.absolutePath,
        )
        val candidate = CustomHeroCandidate(
            source = CustomHeroSource.SCREENSCRAPER,
            mediaType = CustomHeroMediaType.STATIC,
            remoteUrl = remote,
            thumbUrl = remote,
            providerAssetId = "support-2d-1",
        )

        // The lookup the search actually performs: Saved index → unique response-scoped reference.
        val index = homeCustomEnrichSavedPreviewIndex(listOf(savedAsset))
        val reference = homeCustomEnrichProviderReference(EnrichMediaKind.TOME_PHYSICAL, candidate)
        val unique = homeCustomEnrichUniqueProviderRefHashes(listOf(reference))
        val lookedUp = homeCustomEnrichCachedPreviewPath(index, reference, unique)
        assertEquals(cachedFile.absolutePath, lookedUp)

        // The injected seam withLocalPreviews consults before it opens a connection.
        val reusable = customHeroReusableCachedPreview(candidate, lookedUp)
        assertEquals(cachedFile.absolutePath, reusable?.absolutePath)
        val reused = customHeroWithCachedPreview(candidate, reusable!!, 1_920, 1_080)
        assertEquals(cachedFile.absolutePath, reused.pickerArtPath)
        // Preview-file decode is unverified: keep provider-unknown dimensions for filter parity.
        assertEquals(0, reused.width)
        // Saved bytes are borrowed, never consumed.
        assertTrue(cachedFile.isFile)

        // Missing or unusable cache bytes must fall back to remote hydration, never to a blank tile.
        assertNull(customHeroReusableCachedPreview(candidate, null))
        assertNull(customHeroReusableCachedPreview(candidate, "  "))
        assertNull(customHeroReusableCachedPreview(candidate, "/does/not/exist.png"))
        val empty = File.createTempFile("mimir-empty-preview", ".png").apply { deleteOnExit() }
        assertNull(customHeroReusableCachedPreview(candidate, empty.absolutePath))
    }

    @Test
    fun collidingScreenScraperTomeHashesBindNoneAndSkipCacheReuse() {
        val base = "https://www.screenscraper.fr/image.php"
        val urls = listOf(
            "$base?media=support-2d&region=us&ssid=a",
            "$base?media=support-2d&region=eu&ssid=b",
            "$base?media=support-2d&region=jp&ssid=c",
            "$base?media=support-2d&region=wor&ssid=d",
        )
        // Legacy Saved identity is path-only after query strip — same hash for every URL above.
        val savedAsset = saved("ss-cartridge", 1L, source = EnrichSavedSource.SCREENSCRAPER).copy(
            providerRefHash = enrichProviderReferenceHash(urls.first()),
            previewPath = "/local/ss-cartridge.png",
        )
        assertEquals(1, urls.map(::enrichProviderReferenceHash).distinct().size)

        val candidates = urls.mapIndexed { index, url ->
            CustomHeroCandidate(
                source = CustomHeroSource.SCREENSCRAPER,
                mediaType = CustomHeroMediaType.STATIC,
                remoteUrl = url,
                thumbUrl = url,
                providerAssetId = "support-2d-${index + 1}",
            )
        }
        val unique = homeCustomEnrichUniqueProviderRefHashes(EnrichMediaKind.TOME_PHYSICAL, candidates)
        assertTrue(unique.isEmpty())

        val savedPreviews = homeCustomEnrichSavedPreviewIndex(listOf(savedAsset))
        var state = state(
            kind = EnrichMediaKind.TOME_PHYSICAL,
            saved = listOf(savedAsset),
        ).copy(
            generation = 4,
            searchStatus = HomeCustomEnrichSearchStatus.Searching("ScreenScraper", 0),
        )
        urls.forEachIndexed { ordinal, url ->
            state = state.appendSearchResult(
                expectedGeneration = 4,
                result = HomeCustomEnrichResult.Icon(
                    SteamGridDbTileFetcher.Candidate(
                        url = url,
                        thumbUrl = url,
                        providerAssetId = "support-2d-${ordinal + 1}",
                    ),
                ),
                provider = "ScreenScraper",
                uniqueProviderRefHashes = unique,
            )
            assertNull(
                homeCustomEnrichCachedPreviewPath(
                    savedPreviews = savedPreviews,
                    providerReference = url,
                    uniqueProviderRefHashes = unique,
                ),
            )
        }

        assertEquals(4, state.results.size)
        assertTrue(state.results.all { it.saved == null })
    }

    @Test
    fun uniqueScreenScraperCandidateStillBindsSavedAndReusesLocalBytes() {
        val remote = "https://www.screenscraper.fr/image.php?media=support-2d&region=us"
        val cachedFile = File.createTempFile("mimir-unique-saved", ".png").apply {
            deleteOnExit()
            writeBytes(ByteArray(2_048) { 9 })
        }
        val savedAsset = saved("ss-unique", 1L, source = EnrichSavedSource.SCREENSCRAPER).copy(
            providerRefHash = enrichProviderReferenceHash(remote),
            localPath = cachedFile.absolutePath,
            previewPath = cachedFile.absolutePath,
        )
        val candidate = CustomHeroCandidate(
            source = CustomHeroSource.SCREENSCRAPER,
            mediaType = CustomHeroMediaType.STATIC,
            remoteUrl = remote,
            thumbUrl = remote,
            providerAssetId = "support-2d-1",
        )
        val unique = homeCustomEnrichUniqueProviderRefHashes(
            EnrichMediaKind.TOME_PHYSICAL,
            listOf(candidate),
        )
        val initial = state(
            kind = EnrichMediaKind.TOME_PHYSICAL,
            saved = listOf(savedAsset),
        ).copy(
            generation = 5,
            searchStatus = HomeCustomEnrichSearchStatus.Searching("ScreenScraper", 0),
        )
        val appended = initial.appendSearchResult(
            expectedGeneration = 5,
            result = HomeCustomEnrichResult.Icon(
                SteamGridDbTileFetcher.Candidate(url = remote, thumbUrl = remote),
            ),
            provider = "ScreenScraper",
            uniqueProviderRefHashes = unique,
        )
        assertEquals(savedAsset, appended.results.single().saved)
        val lookedUp = homeCustomEnrichCachedPreviewPath(
            savedPreviews = homeCustomEnrichSavedPreviewIndex(listOf(savedAsset)),
            providerReference = homeCustomEnrichProviderReference(EnrichMediaKind.TOME_PHYSICAL, candidate),
            uniqueProviderRefHashes = unique,
        )
        assertEquals(cachedFile.absolutePath, lookedUp)
        assertEquals(cachedFile.absolutePath, customHeroReusableCachedPreview(candidate, lookedUp)?.absolutePath)
    }

    @Test
    fun steamGridDbDistinctAssetIdsReuseOnlyTheMatchingSavedRecord() {
        val savedRemote = "https://cdn2.steamgriddb.com/hero/alpha.png"
        val otherRemote = "https://cdn2.steamgriddb.com/hero/beta.png"
        val cachedFile = File.createTempFile("mimir-sgdb-saved", ".png").apply {
            deleteOnExit()
            writeBytes(ByteArray(1_024) { 5 })
        }
        val savedAsset = saved("sgdb-alpha", 1L).copy(
            providerRefHash = enrichProviderReferenceHash("alpha"),
            previewPath = cachedFile.absolutePath,
        )
        val savedCandidate = CustomHeroCandidate(
            source = CustomHeroSource.STEAMGRIDDB,
            mediaType = CustomHeroMediaType.STATIC,
            remoteUrl = savedRemote,
            providerAssetId = "alpha",
            width = 1_920,
            height = 620,
        )
        val otherCandidate = CustomHeroCandidate(
            source = CustomHeroSource.STEAMGRIDDB,
            mediaType = CustomHeroMediaType.STATIC,
            remoteUrl = otherRemote,
            providerAssetId = "beta",
            width = 1_920,
            height = 620,
        )
        val unique = homeCustomEnrichUniqueProviderRefHashes(
            EnrichMediaKind.HERO,
            listOf(savedCandidate, otherCandidate),
        )
        assertEquals(2, unique.size)

        val index = homeCustomEnrichSavedPreviewIndex(listOf(savedAsset))
        assertEquals(
            cachedFile.absolutePath,
            homeCustomEnrichCachedPreviewPath(
                index,
                homeCustomEnrichProviderReference(EnrichMediaKind.HERO, savedCandidate),
                unique,
            ),
        )
        assertNull(
            homeCustomEnrichCachedPreviewPath(
                index,
                homeCustomEnrichProviderReference(EnrichMediaKind.HERO, otherCandidate),
                unique,
            ),
        )

        var state = state(kind = EnrichMediaKind.HERO, saved = listOf(savedAsset)).copy(
            generation = 6,
            searchStatus = HomeCustomEnrichSearchStatus.Searching("SteamGridDB", 0),
        )
        state = state.appendSearchResult(
            6,
            HomeCustomEnrichResult.Hero(savedCandidate),
            "SteamGridDB",
            unique,
        )
        state = state.appendSearchResult(
            6,
            HomeCustomEnrichResult.Hero(otherCandidate),
            "SteamGridDB",
            unique,
        )
        assertEquals(savedAsset, state.results[0].saved)
        assertNull(state.results[1].saved)
    }

    @Test
    fun unverifiedPreviewDimensionsAreAdmittedWhileVerifiedDimensionsKeepRules() {
        val unknown = CustomHeroCandidate(
            source = CustomHeroSource.SCREENSCRAPER,
            mediaType = CustomHeroMediaType.STATIC,
            remoteUrl = "https://www.screenscraper.fr/image.php?id=1",
        )
        val previewProxy = customHeroWithHydratedDimensions(unknown, 640, 360, verified = false)
        assertEquals(0, previewProxy.width)
        assertTrue(customEnrichHeroMatchesFilters(previewProxy, size = "HD", fit = "Mimir"))
        assertTrue(customEnrichHeroMatchesFilters(previewProxy, size = "Any", fit = "Any"))

        val verifiedSmall = customHeroWithHydratedDimensions(unknown, 640, 360, verified = true)
        assertEquals(640, verifiedSmall.width)
        assertFalse(customEnrichHeroMatchesFilters(verifiedSmall, size = "Any", fit = "Any"))
        assertFalse(customEnrichHeroMatchesFilters(verifiedSmall, size = "HD", fit = "Mimir"))

        val verifiedBanner = heroCandidate(width = 1_920, height = 620)
        assertTrue(customEnrichHeroMatchesFilters(verifiedBanner, size = "HD", fit = "Wide"))
        assertFalse(customEnrichHeroMatchesFilters(verifiedBanner, size = "2K+", fit = "Any"))
    }

    @Test
    fun savedAndUnsavedRepresentationsShareFilterOutcomeForPreviewDerivedDimensions() {
        val remote = "https://www.screenscraper.fr/image.php?media=fanart&id=9"
        val unsaved = CustomHeroCandidate(
            source = CustomHeroSource.SCREENSCRAPER,
            mediaType = CustomHeroMediaType.STATIC,
            remoteUrl = remote,
        )
        val cachedFile = File.createTempFile("mimir-filter-parity", ".jpg").apply {
            deleteOnExit()
            writeBytes(ByteArray(1_024) { 2 })
        }
        val savedHydrated = customHeroWithCachedPreview(unsaved, cachedFile, 640, 360)
        val remoteHydrated = customHeroWithHydratedDimensions(unsaved, 640, 360, verified = false)

        assertEquals(remoteHydrated.width, savedHydrated.width)
        assertEquals(remoteHydrated.height, savedHydrated.height)
        listOf("Any" to "Any", "Any" to "Mimir", "HD" to "Wide", "2K+" to "Mimir").forEach { (size, fit) ->
            assertEquals(
                customEnrichHeroMatchesFilters(remoteHydrated, size, fit),
                customEnrichHeroMatchesFilters(savedHydrated, size, fit),
            )
        }
    }

    @Test
    fun aReusedSavedFileIsPickerArtAndNeverStandsInForASelectedOriginal() {
        val cachedFile = File.createTempFile("mimir-saved-poster", ".jpg").apply {
            deleteOnExit()
            writeBytes(ByteArray(2_048) { 3 })
        }
        fun candidate(mediaType: CustomHeroMediaType) = CustomHeroCandidate(
            source = CustomHeroSource.SCREENSCRAPER,
            mediaType = mediaType,
            remoteUrl = "https://www.screenscraper.fr/clip.mp4",
        )

        // A still may serve as both picker art and preview — its previewPath is never installed as-is.
        val still = customHeroWithCachedPreview(candidate(CustomHeroMediaType.STATIC), cachedFile, 1_280, 720)
        assertEquals(cachedFile.absolutePath, still.previewPath)
        assertFalse(customHeroPreviewReusableAsInstalledAsset(CustomHeroMediaType.STATIC))

        // A clip poster and an animated first frame are art only: previewPath stays empty, so the
        // installer's reuse path cannot copy them in place of the selected original.
        listOf(CustomHeroMediaType.VIDEO, CustomHeroMediaType.ANIMATED_IMAGE).forEach { mediaType ->
            val reused = customHeroWithCachedPreview(candidate(mediaType), cachedFile, 1_920, 620)
            assertNull(reused.previewPath)
            assertEquals(cachedFile.absolutePath, reused.posterPath)
        }
    }

    @Test
    fun explicitSearchClearsOnlyTransientResultsAndAdvancesGeneration() {
        val prior = state(saved = listOf(saved("one", 1L))).copy(
            generation = 2,
            results = listOf(
                HomeCustomEnrichResult.Icon(
                    SteamGridDbTileFetcher.Candidate(
                        url = "https://cdn2.steamgriddb.com/old.png",
                        thumbUrl = "https://cdn2.steamgriddb.com/old-thumb.png",
                    ),
                ),
            ),
        )
        val authority = authorityFor(prior, homeCustomEnrichSearchKey(prior))

        val transition = reduceHomeCustomEnrich(authority, prior, ControllerAction.TERTIARY)

        assertEquals(HomeCustomEnrichCommand.Search(3, "Game"), transition.command)
        assertEquals(3, transition.state.generation)
        assertTrue(transition.state.results.isEmpty())
        assertEquals(prior.saved, transition.state.saved)
    }

    @Test
    fun failedSearchAndActiveSearchShareOneStableFooterControlSeat() {
        val failed = state().copy(
            generation = 2,
            searchStatus = HomeCustomEnrichSearchStatus.Unreachable("Artwork services are unreachable."),
        )
        val searchKey = homeCustomEnrichSearchKey(failed)
        val controlKey = homeCustomEnrichRetryKey(failed)
        val authority = authorityFor(failed, searchKey)

        assertEquals(HomeCustomEnrichSearchControl.RETRY, failed.searchControl())
        assertTrue(controlKey in homeCustomEnrichLaneKeys(failed).getValue(HomeCustomEnrichLane.FOOTER))

        val searching = reduceHomeCustomEnrich(authority, failed, ControllerAction.TERTIARY)

        assertEquals(HomeCustomEnrichCommand.Search(3, "Game"), searching.command)
        assertEquals(HomeCustomEnrichSearchControl.STOP, searching.state.searchControl())
        assertTrue(controlKey in homeCustomEnrichLaneKeys(searching.state).getValue(HomeCustomEnrichLane.FOOTER))
        assertEquals(searchKey, authority.state.value.focused)
    }

    @Test
    fun focusedRetryTurnsIntoFocusedStopWithoutChangingLanes() {
        val failed = state().copy(searchStatus = HomeCustomEnrichSearchStatus.ProviderFailed("Provider failed."))
        val controlKey = homeCustomEnrichRetryKey(failed)
        val authority = authorityFor(failed, controlKey)

        val searching = reduceHomeCustomEnrich(authority, failed, ControllerAction.CONFIRM)
        assertTrue(searching.command is HomeCustomEnrichCommand.Search)
        assertEquals(controlKey, authority.state.value.focused)
        assertEquals(HomeCustomEnrichSearchControl.STOP, searching.state.searchControl())

        val stopped = reduceHomeCustomEnrich(authority, searching.state, ControllerAction.CONFIRM)
        assertEquals(HomeCustomEnrichCommand.CancelSearch, stopped.command)
        assertEquals(
            HomeCustomEnrichSearchStatus.Cancelled(0),
            stopped.state.searchStatus,
        )
        assertEquals(HomeCustomEnrichSearchControl.RETRY, stopped.state.searchControl())
        assertTrue(
            homeCustomEnrichShouldShowSearchControl(
                state = stopped.state,
                narrowWidth = true,
                retryFocused = true,
            ),
        )
    }

    @Test
    fun stopRemainsPaintedOnNarrowScreensRegardlessOfCurrentFooterFocus() {
        val searching = state().copy(
            searchStatus = HomeCustomEnrichSearchStatus.Searching("Community media", 4),
        )

        assertTrue(
            homeCustomEnrichShouldShowSearchControl(
                state = searching,
                narrowWidth = true,
                retryFocused = false,
            ),
        )
    }

    @Test
    fun selectingAFilterOptionInvalidatesStaleResultsButPreservesDurableSavedMedia() {
        val durable = saved("kept", 1L)
        val result = HomeCustomEnrichResult.Icon(
            SteamGridDbTileFetcher.Candidate(
                url = "https://cdn2.steamgriddb.com/kept.png",
                thumbUrl = "https://cdn2.steamgriddb.com/kept-thumb.png",
            ),
            saved = durable,
        )
        val initial = state().copy(
            generation = 3,
            submittedQuery = "Game",
            results = listOf(result),
            hasMore = true,
            searchStatus = HomeCustomEnrichSearchStatus.Complete(1),
        )
        val kind = initial.filters.first { it.key == "kind" }
        val authority = authorityFor(initial, homeCustomEnrichFilterKey(initial, kind))

        val opened = reduceHomeCustomEnrich(authority, initial, ControllerAction.CONFIRM)

        assertEquals("kind", opened.state.openFilterKey)
        assertEquals("All", opened.state.filterValue("kind"))
        val option = homeCustomEnrichFilterOptionKey(opened.state, kind, optionIndex = 1)
        authority.registerVirtual(
            option,
            HomeCustomEnrichScope,
            FocusBounds(0f, 0f, 80f, 80f),
        )
        authority.requestFocus(option)
        val transition = reduceHomeCustomEnrich(authority, opened.state, ControllerAction.CONFIRM)

        assertEquals("Grids", transition.state.filterValue("kind"))
        assertTrue(transition.state.results.isEmpty())
        assertEquals(listOf(durable), transition.state.saved)
        assertFalse(transition.state.hasMore)
        assertEquals(4, transition.state.generation)
    }

    @Test
    fun cancellingAFilterDropdownRestoresChipFocusWithoutChangingValue() {
        val initial = state().copy(
            filters = state().filters.map { filter ->
                if (filter.key == "kind") filter.copy(selectedIndex = 1) else filter
            },
        )
        val kind = initial.filters.first { it.key == "kind" }
        val authority = authorityFor(initial, homeCustomEnrichFilterKey(initial, kind))

        val opened = reduceHomeCustomEnrich(authority, initial, ControllerAction.CONFIRM)
        val option = homeCustomEnrichFilterOptionKey(opened.state, kind, optionIndex = 2)
        authority.registerVirtual(option, HomeCustomEnrichScope, FocusBounds(0f, 0f, 80f, 80f))
        authority.requestFocus(option)
        val cancelled = reduceHomeCustomEnrich(authority, opened.state, ControllerAction.CANCEL)

        assertNull(cancelled.state.openFilterKey)
        assertEquals("Grids", cancelled.state.filterValue("kind"))
        assertEquals(homeCustomEnrichFilterKey(initial, kind), authority.state.value.focused)
    }

    @Test
    fun heroMotionFilterMatrixAlwaysRoutesEveryVisibleCombinationTruthfully() {
        listOf("All", "Motion Art", "Video").forEach { format ->
            val steam = homeCustomEnrichClipUsesSteamGridDb("Both", format)
            val screenScraper = homeCustomEnrichClipUsesScreenScraper("Both", format, page = 0)
            assertTrue("Both + $format must route at least one provider", steam || screenScraper)
            if (format == "Motion Art") assertFalse(screenScraper)
            if (format == "Video") assertFalse(steam)
        }
        val steamOnly = state(kind = EnrichMediaKind.CLIP).let { clip ->
            clip.copy(filters = clip.filters.map { filter ->
                if (filter.key == "source") filter.copy(selectedIndex = 1) else filter
            })
        }
        val screenScraperOnly = state(kind = EnrichMediaKind.CLIP).let { clip ->
            clip.copy(filters = clip.filters.map { filter ->
                if (filter.key == "source") filter.copy(selectedIndex = 2) else filter
            })
        }
        assertEquals(listOf("source"), steamOnly.visibleFilters().map(HomeCustomEnrichFilter::key))
        assertEquals("All", steamOnly.effectiveFilterValue("encode", hiddenDefault = "All"))
        assertEquals(listOf("source"), screenScraperOnly.visibleFilters().map(HomeCustomEnrichFilter::key))
        assertEquals("All", screenScraperOnly.effectiveFilterValue("encode", hiddenDefault = "All"))
        assertTrue(homeCustomEnrichClipUsesSteamGridDb("SteamGridDB", "All"))
        assertTrue(homeCustomEnrichClipUsesScreenScraper("ScreenScraper", "All", page = 0))
        assertFalse(homeCustomEnrichClipUsesScreenScraper("Both", "All", page = 1))
    }

    @Test
    fun bothHeroMotionProvidersReceiveReservedVisibleCapacity() {
        assertEquals(
            HomeCustomEnrichClipProviderBudgets(steamGridDb = 9, screenScraper = 9),
            homeCustomEnrichClipProviderBudgets(source = "Both", format = "All"),
        )
        assertEquals(
            HomeCustomEnrichClipProviderBudgets(steamGridDb = 18, screenScraper = 0),
            homeCustomEnrichClipProviderBudgets(source = "Both", format = "Motion Art"),
        )
        assertEquals(
            HomeCustomEnrichClipProviderBudgets(steamGridDb = 0, screenScraper = 18),
            homeCustomEnrichClipProviderBudgets(source = "Both", format = "Video"),
        )
    }

    @Test
    fun savedMotionOriginalIsNeverIndexedAsGalleryPreview() {
        val original = saved("motion", 1L).copy(
            localPath = "/saved/full-motion.webp",
            previewPath = "/saved/full-motion.webp",
            providerRefHash = "motion-ref",
            mediaType = CustomHeroMediaType.ANIMATED_IMAGE,
        )
        val poster = original.copy(previewPath = "/saved/poster.jpg")

        assertTrue(homeCustomEnrichSavedPreviewIndex(listOf(original)).isEmpty())
        assertNull(HomeCustomEnrichAsset.Saved(original).previewPath)
        assertEquals(
            mapOf("motion-ref" to "/saved/poster.jpg"),
            homeCustomEnrichSavedPreviewIndex(listOf(poster)),
        )
    }

    @Test
    fun moreResultsRequestsTheNextPageAndKeepsFocusOnTheLastSettledResult() {
        val result = HomeCustomEnrichResult.Icon(
            SteamGridDbTileFetcher.Candidate(
                url = "https://cdn2.steamgriddb.com/one.png",
                thumbUrl = "https://cdn2.steamgriddb.com/one-thumb.png",
            ),
        )
        val initial = state().copy(
            submittedQuery = "Game",
            results = listOf(result),
            hasMore = true,
            searchStatus = HomeCustomEnrichSearchStatus.Complete(1),
        )
        val authority = authorityFor(initial, homeCustomEnrichAssetKey(initial, HomeCustomEnrichAsset.More))

        val transition = reduceHomeCustomEnrich(authority, initial, ControllerAction.CONFIRM)

        assertEquals(HomeCustomEnrichCommand.Search(0, "Game", page = 1, append = true), transition.command)
        assertEquals(0, transition.state.page)
        assertEquals(initial.results, transition.state.results)
        assertEquals(
            homeCustomEnrichAssetKey(initial, HomeCustomEnrichAsset.Result(result)),
            authority.state.value.focused,
        )
    }

    @Test
    fun physicalIconApplyKeepsScreenScraperProvenance() {
        val result = HomeCustomEnrichResult.Icon(
            SteamGridDbTileFetcher.Candidate(
                url = "https://www.screenscraper.fr/image.php?id=1",
                thumbUrl = "https://www.screenscraper.fr/image.php?id=1",
                savedSource = EnrichSavedSource.SCREENSCRAPER,
            ),
        )

        assertEquals(EnrichSavedSource.SCREENSCRAPER, homeCustomEnrichSavedSource(result))
    }

    /**
     *
     *
     * Both halves are pinned, because only pinning the new key would let the old one keep working in
     * silence — X submitting a search it no longer advertises is the same class of defect as Y not
     * submitting one it does. The gallery half is asserted from the SAME state object so this cannot pass
     * by the search field simply being unreachable.
     */
    @Test
    fun searchAnswersOnYAndTheOldXBindingIsGoneWhileXStillRemoves() {
        val record = saved("one", 1L)
        val base = state(saved = listOf(record))
        val onSearch = authorityFor(base, homeCustomEnrichSearchKey(base))

        val onY = reduceHomeCustomEnrich(onSearch, base, ControllerAction.TERTIARY)
        assertTrue(onY.command is HomeCustomEnrichCommand.Search)

        val onX = reduceHomeCustomEnrich(onSearch, base, ControllerAction.SECONDARY)
        assertEquals(HomeCustomEnrichCommand.None, onX.command)
        assertEquals(base.generation, onX.state.generation)

        // X keeps its one remaining job on this surface, so the ruling narrowed the key rather than
        // emptying it.
        val savedKey = homeCustomEnrichAssetKey(base, HomeCustomEnrichAsset.Saved(record))
        val onAsset = authorityFor(base, savedKey)
        val removal = reduceHomeCustomEnrich(onAsset, base, ControllerAction.SECONDARY)
        assertEquals(HomeCustomEnrichCommand.RemoveSaved(record.assetId), removal.command)

        // ...and Y does NOT inherit remove on the way past. An advertised key doing a second, unlegended
        // thing one lane down is exactly the collision this ruling closed.
        val yOnAsset = reduceHomeCustomEnrich(onAsset, base, ControllerAction.TERTIARY)
        assertEquals(HomeCustomEnrichCommand.None, yOnAsset.command)
    }

    /**
     * The legend and the dispatcher have to be readable as one statement. This asserts the SEARCH lane's
     * rendered legend names the key the reducer actually answers, rather than asserting a literal string
     * twice — a literal pin passes happily while the binding underneath it moves.
     */
    @Test
    fun theSearchLaneLegendNamesTheKeyTheReducerAnswers() {
        val base = state()
        val legend = homeCustomEnrichHint(base, HomeCustomEnrichLane.SEARCH)
        assertTrue(legend, legend.contains("Y SEARCH"))
        assertFalse(legend, legend.contains("X SEARCH"))

        val onSearch = authorityFor(base, homeCustomEnrichSearchKey(base))
        assertTrue(
            reduceHomeCustomEnrich(onSearch, base, ControllerAction.TERTIARY).command
                is HomeCustomEnrichCommand.Search,
        )
    }

    @Test
    fun confirmOnSearchOpensTouchKeyboardWhileTertiarySubmitsSeededTitle() {
        val state = state()
        val authority = authorityFor(state, homeCustomEnrichSearchKey(state))

        val edit = reduceHomeCustomEnrich(authority, state, ControllerAction.CONFIRM)
        assertTrue(edit.state.keyboardOpen)
        assertEquals(HomeCustomEnrichCommand.OpenKeyboard, edit.command)

        val submit = reduceHomeCustomEnrich(authority, state, ControllerAction.TERTIARY)
        assertEquals(HomeCustomEnrichCommand.Search(1, "Game"), submit.command)
        assertFalse(submit.state.keyboardOpen)
    }

    @Test
    fun keyboardQuarantinesControllerAndBackRestoresSearch() {
        val state = state().copy(keyboardOpen = true)
        val authority = authorityFor(state, homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Automatic))

        val swallowed = reduceHomeCustomEnrich(authority, state, ControllerAction.NAV_RIGHT)
        assertTrue(swallowed.state.keyboardOpen)
        assertEquals(HomeCustomEnrichCommand.None, swallowed.command)

        val closed = reduceHomeCustomEnrich(authority, state, ControllerAction.CANCEL)
        assertFalse(closed.state.keyboardOpen)
        assertEquals(homeCustomEnrichSearchKey(state), authority.state.value.focused)
    }

    @Test
    fun publishedKeyboardHostBackClosesExactlyOnce() {
        val state = state().copy(keyboardOpen = true)
        val authority = authorityFor(state, homeCustomEnrichSearchKey(state))
        val host = MimirKeyboardHost(
            controller = com.gruning.mimir.rewrite.core.ui.MimirKeyboardController(
                onEnter = {},
                onExit = {},
            ),
            keyDecorator = { _, _ ->
                com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyDecoration(
                    focusModifier = androidx.compose.ui.Modifier,
                    focused = false,
                )
            },
            binding = com.gruning.mimir.rewrite.core.input.createMimirKeyboardFocusBindingForTests(authority),
        )
        host.binding.enter()
        HomeCustomEnrichKeyboardHostHolder.publish(state.sessionId, host)

        val closed = reduceHomeCustomEnrich(authority, state, ControllerAction.SYSTEM_BACK)
        host.binding.exit()
        assertFalse(closed.state.keyboardOpen)
        assertEquals(homeCustomEnrichSearchKey(state), authority.state.value.focused)

        HomeCustomEnrichKeyboardHostHolder.clear(state.sessionId)
    }

    // device evidence: CONFIRM on a focused Customize Media keyboard key navigated fine
    // (37 NavFocusMove sounds in the repro window) but never typed. `reconcileCustomEnrichPickerForWrite`
    // (HomeCustomEnrichPicker.kt) is the fix — see its doc comment for the full mechanism. These pin it.

    @Test
    fun confirmOnFocusedCharacterKeySurvivesTheCoordinatorWriteBack() {
        val preDispatchPicker = state().copy(keyboardOpen = true, queryDraft = "")
        val authority = com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority()
        val binding = com.gruning.mimir.rewrite.core.input.createMimirKeyboardFocusBindingForTests(authority)
        binding.enter()
        val nKey = com.gruning.mimir.rewrite.core.input.mimirKeyboardFocusKey("char/n")
        authority.register(
            nKey,
            com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusScope,
            FocusBounds(0f, 0f, 40f, 40f),
        )

        // Stands in for decorateKey's real activator: production calls onValueChange -> onQueryChange,
        // which in MimirAppHost.kt (onCustomEnrichQueryChange) writes DIRECTLY into the live picker
        // storage, SYNCHRONOUSLY, from inside this very activateFocused() call — i.e. before
        // reduceHomeCustomEnrich below has even returned.
        var live = preDispatchPicker
        binding.registerActivator("char/n") { live = live.copy(queryDraft = live.queryDraft + "n") }
        authority.requestFocus(nKey)

        val host = MimirKeyboardHost(
            controller = com.gruning.mimir.rewrite.core.ui.MimirKeyboardController(onEnter = {}, onExit = {}),
            keyDecorator = { _, _ ->
                com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyDecoration(
                    focusModifier = androidx.compose.ui.Modifier,
                    focused = false,
                )
            },
            binding = binding,
        )
        HomeCustomEnrichKeyboardHostHolder.publish(preDispatchPicker.sessionId, host)

        val transition = reduceHomeCustomEnrich(authority, preDispatchPicker, ControllerAction.CONFIRM)
        val written = reconcileCustomEnrichPickerForWrite(preDispatchPicker, transition, live)

        HomeCustomEnrichKeyboardHostHolder.clear(preDispatchPicker.sessionId)

        // Pre-fix (`reconcileCustomEnrichPickerForWrite` forced to `= transition.state`
        // unconditionally), this recorded `org.junit.ComparisonFailure: expected:<[n]> but was:<[]>`.
        assertEquals("n", written.queryDraft)
    }

    @Test
    fun reconcilePrefersLiveWhenReducerMadeNoChange() {
        val pre = state()
        val transition = HomeCustomEnrichTransition(pre) // same reference: reducer's own no-op shape.
        val live = pre.copy(queryDraft = "n")
        assertEquals(live, reconcileCustomEnrichPickerForWrite(pre, transition, live))
    }

    @Test
    fun reconcileNeverOverridesAReducerAuthoredChange() {
        val pre = state()
        val authored = pre.copy(note = "Enter a search first.")
        val transition = HomeCustomEnrichTransition(authored)
        val staleLive = pre.copy(queryDraft = "stale")
        assertEquals(authored, reconcileCustomEnrichPickerForWrite(pre, transition, staleLive))
    }

    @Test
    fun reconcileFallsBackToTransitionStateWhenNothingIsLive() {
        val pre = state()
        val transition = HomeCustomEnrichTransition(pre)
        assertEquals(pre, reconcileCustomEnrichPickerForWrite(pre, transition, null))
    }

    @Test
    fun staleKeyboardHostSessionDoesNotCloseCurrentPicker() {
        val state = state().copy(keyboardOpen = true)
        val authority = authorityFor(state, homeCustomEnrichSearchKey(state))
        val host = MimirKeyboardHost(
            controller = com.gruning.mimir.rewrite.core.ui.MimirKeyboardController(
                onEnter = {},
                onExit = {},
            ),
            keyDecorator = { _, _ ->
                com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyDecoration(
                    focusModifier = androidx.compose.ui.Modifier,
                    focused = false,
                )
            },
            binding = com.gruning.mimir.rewrite.core.input.createMimirKeyboardFocusBindingForTests(authority),
        )
        HomeCustomEnrichKeyboardHostHolder.publish("stale-session", host)
        assertNull(HomeCustomEnrichKeyboardHostHolder.hostFor(state.sessionId))

        val closed = reduceHomeCustomEnrich(authority, state, ControllerAction.SYSTEM_BACK)
        assertFalse(closed.state.keyboardOpen)
        assertEquals(homeCustomEnrichSearchKey(state), authority.state.value.focused)

        HomeCustomEnrichKeyboardHostHolder.clear("stale-session")
    }

    @Test
    fun handPickedApplyIsNonDismissibleDuringFetchAndCommit() {
        val base = state()
        val authority = authorityFor(base, homeCustomEnrichAssetKey(base, HomeCustomEnrichAsset.Automatic))

        HomeCustomEnrichOperation.entries
            .filterNot { it == HomeCustomEnrichOperation.IDLE }
            .forEach { operation ->
                listOf(ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK).forEach { action ->
                    val transition = reduceHomeCustomEnrich(
                        authority,
                        base.copy(operation = operation),
                        action,
                    )
                    assertEquals(operation, transition.state.operation)
                    assertEquals(HomeCustomEnrichCommand.None, transition.command)
                }
            }
    }

    @Test
    fun handPickedTransactionSurvivesParentCancellationUntilTerminal() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var completed = false
        val job = launch {
            runHomeCustomEnrichCommittedPick {
                entered.complete(Unit)
                release.await()
                completed = true
            }
        }

        entered.await()
        job.cancel()
        assertFalse(job.isCompleted)
        release.complete(Unit)
        job.join()

        assertTrue(completed)
    }

    @Test
    fun fetchingQuarantinesEveryPhysicalAction() {
        val base = state().copy(operation = HomeCustomEnrichOperation.APPLY_FETCHING)
        val authority = authorityFor(base, homeCustomEnrichSearchKey(base))

        listOf(
            ControllerAction.CONFIRM,
            ControllerAction.SECONDARY,
            ControllerAction.TERTIARY,
            ControllerAction.NAV_DOWN,
            ControllerAction.NAV_RIGHT,
            ControllerAction.CANCEL,
            ControllerAction.SYSTEM_BACK,
        ).forEach { action ->
            val transition = reduceHomeCustomEnrich(authority, base, action)
            assertEquals(HomeCustomEnrichOperation.APPLY_FETCHING, transition.state.operation)
            assertEquals(HomeCustomEnrichCommand.None, transition.command)
        }
    }

    @Test
    fun savedResultReactivatesOfflineAndCanBeRemovedWithoutRedownload() {
        val record = saved("one", 1L)
        val result = HomeCustomEnrichResult.Icon(
            SteamGridDbTileFetcher.Candidate(
                url = "https://cdn2.steamgriddb.com/a.png",
                thumbUrl = "https://cdn2.steamgriddb.com/a-thumb.png",
            ),
            saved = record,
        )
        val state = state().copy(results = listOf(result))
        val resultAsset = HomeCustomEnrichAsset.Result(result)
        val authority = authorityFor(state, homeCustomEnrichAssetKey(state, resultAsset))

        val activate = reduceHomeCustomEnrich(authority, state, ControllerAction.CONFIRM)
        assertEquals(HomeCustomEnrichCommand.ActivateSaved(record.assetId), activate.command)
        assertEquals(HomeCustomEnrichOperation.APPLY_COMMITTING, activate.state.operation)

        val remove = reduceHomeCustomEnrich(authority, state, ControllerAction.SECONDARY)
        assertEquals(HomeCustomEnrichCommand.RemoveSaved(record.assetId), remove.command)
        assertEquals(1, state.durableSavedCount)
    }

    @Test
    fun activeSearchKeepsTheSubmittedFilterSnapshotVisible() {
        val state = state().copy(searchStatus = HomeCustomEnrichSearchStatus.Searching("SteamGridDB", 0))
        val authority = authorityFor(state, homeCustomEnrichFilterKey(state, state.filters.first()))

        val transition = reduceHomeCustomEnrich(authority, state, ControllerAction.CONFIRM)

        assertEquals(state.filters, transition.state.filters)
        assertEquals(HomeCustomEnrichCommand.None, transition.command)

        authority.requestFocus(homeCustomEnrichSearchKey(state))
        val editAttempt = reduceHomeCustomEnrich(authority, state, ControllerAction.CONFIRM)
        assertFalse(editAttempt.state.keyboardOpen)
        assertEquals(HomeCustomEnrichCommand.None, editAttempt.command)

        val duplicateSearch = reduceHomeCustomEnrich(authority, state, ControllerAction.TERTIARY)
        assertEquals(HomeCustomEnrichCommand.None, duplicateSearch.command)
        assertEquals(state.generation, duplicateSearch.state.generation)
        assertEquals(
            "B STOP",
            homeCustomEnrichHint(duplicateSearch.state, HomeCustomEnrichLane.SEARCH),
        )
    }

    @Test
    fun evictionNeverSelectsActiveOrUpload() {
        val upload = saved("upload", 1L, EnrichSavedSource.UPLOAD)
        val oldestProvider = saved("old", 2L)
        val active = saved("active", 3L)

        assertEquals(
            oldestProvider,
            enrichSavedEvictionCandidate(listOf(upload, oldestProvider, active), active.assetId),
        )
        assertNull(enrichSavedEvictionCandidate(listOf(upload, active), active.assetId))
    }

    @Test
    fun catalogSerializationCannotContainCredentialUrl() {
        val raw = encodeCatalog(
            EnrichSavedCatalog(
                activeAssetId = "ss-1",
                entries = listOf(
                    saved("ss-1", 1L).copy(
                        source = EnrichSavedSource.SCREENSCRAPER,
                        providerRefHash = "b91cafe12",
                    ),
                ),
            ),
        )

        assertFalse(raw.contains("https://"))
        assertFalse(raw.contains("sspassword"))
        assertTrue(raw.contains("b91cafe12"))
    }

    @Test
    fun providerIdentityIgnoresCredentialBearingQueryParameters() {
        val first = enrichProviderReferenceHash("https://media.screenscraper.fr/video.mp4?ssid=user&sspassword=one")
        val rotated = enrichProviderReferenceHash("https://media.screenscraper.fr/video.mp4?ssid=user&sspassword=two")

        assertEquals(first, rotated)
    }

    @Test
    fun provisionalLocalCatalogKeepsKnownArtVisibleWithoutTreatingVideoAsAStill() {
        val root = kotlin.io.path.createTempDirectory("enrich-provisional").toFile()
        try {
            val hero = File(root, "hero.img").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val clip = File(root, "clip.mp4").apply { writeBytes(byteArrayOf(4, 5, 6)) }

            val heroCatalog = enrichCatalogWithProvisionalLocal(
                EnrichSavedCatalog(null, emptyList()),
                EnrichMediaKind.HERO,
                hero,
                retainedUpload = null,
                activeWasUpload = false,
            )
            val clipCatalog = enrichCatalogWithProvisionalLocal(
                EnrichSavedCatalog(null, emptyList()),
                EnrichMediaKind.CLIP,
                clip,
                retainedUpload = null,
                activeWasUpload = false,
            )

            assertEquals(hero.absolutePath, heroCatalog.entries.single().previewPath)
            assertTrue(clipCatalog.entries.single().previewPath.isBlank())
            assertEquals(heroCatalog.entries.single().assetId, heroCatalog.activeAssetId)
            assertEquals(clipCatalog.entries.single().assetId, clipCatalog.activeAssetId)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun provisionalBridgeDoesNotDuplicateDurableActiveOrUploadedChoices() {
        val root = kotlin.io.path.createTempDirectory("enrich-provisional-dedupe").toFile()
        try {
            val legacy = File(root, "active.img").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val retainedUpload = File(root, "upload.img").apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val durableActive = saved("active", 2L)
            val durableUpload = saved("upload", 1L, EnrichSavedSource.UPLOAD)
            val catalog = EnrichSavedCatalog(
                activeAssetId = durableActive.assetId,
                entries = listOf(durableActive, durableUpload),
            )

            val bridged = enrichCatalogWithProvisionalLocal(
                catalog = catalog,
                kind = EnrichMediaKind.HERO,
                legacyActive = legacy,
                retainedUpload = retainedUpload,
                activeWasUpload = false,
            )

            assertEquals(catalog, bridged)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun provisionalCatalogAllowsNavigationAndBackButBlocksMutation() {
        val base = state(saved = listOf(saved("provisional", 1L))).copy(catalogReady = false)
        val key = homeCustomEnrichAssetKey(base, HomeCustomEnrichAsset.Saved(base.saved.single()))
        val authority = authorityFor(base, key)

        val apply = reduceHomeCustomEnrich(authority, base, ControllerAction.CONFIRM)
        assertEquals(HomeCustomEnrichCommand.None, apply.command)
        assertEquals(HomeCustomEnrichOperation.IDLE, apply.state.operation)

        val remove = reduceHomeCustomEnrich(authority, base, ControllerAction.SECONDARY)
        assertEquals(HomeCustomEnrichCommand.None, remove.command)

        val back = reduceHomeCustomEnrich(authority, base, ControllerAction.CANCEL)
        assertEquals(HomeCustomEnrichCommand.Back, back.command)
    }

    @Test
    fun catalogSerializationPreservesIntentionalBlankClipPoster() {
        val clip = saved("clip", 1L).copy(previewPath = "")
        val decoded = decodeCatalog(encodeCatalog(EnrichSavedCatalog(clip.assetId, listOf(clip))))

        assertEquals("", decoded.entries.single().previewPath)
        assertEquals(clip.assetId, decoded.activeAssetId)
    }

    @Test
    fun touchRemoveIsOfferedOnlyForInactiveDurableChoices() {
        val inactive = saved("inactive", 1L)
        val active = saved("active", 2L)
        val state = state(saved = listOf(inactive, active), active = active.assetId)

        assertTrue(state.canRemove(HomeCustomEnrichAsset.Saved(inactive)))
        assertFalse(state.canRemove(HomeCustomEnrichAsset.Saved(active)))
        assertFalse(state.canRemove(HomeCustomEnrichAsset.Automatic))
        assertFalse(state.canRemove(HomeCustomEnrichAsset.More))
        assertFalse(state.copy(catalogReady = false).canRemove(HomeCustomEnrichAsset.Saved(inactive)))
    }

    @Test
    fun leftRightCrossTheSearchFilterRowNaturallyAndStayFinite() {
        val base = state().let { b ->
            b.copy(filters = b.filters.map { f -> if (f.key == "kind") f.copy(selectedIndex = 1) else f })
        }
        val searchKey = homeCustomEnrichSearchKey(base)
        val firstFilterKey = homeCustomEnrichFilterKey(base, base.visibleFilters().first())
        val lastFilterKey = homeCustomEnrichFilterKey(base, base.visibleFilters().last())

        val fromSearch = authorityFor(base, searchKey)
        reduceHomeCustomEnrich(fromSearch, base, ControllerAction.NAV_RIGHT)
        assertEquals(firstFilterKey, fromSearch.state.value.focused)
        reduceHomeCustomEnrich(fromSearch, base, ControllerAction.NAV_LEFT)
        assertEquals(searchKey, fromSearch.state.value.focused)
        reduceHomeCustomEnrich(fromSearch, base, ControllerAction.NAV_LEFT)
        assertEquals(searchKey, fromSearch.state.value.focused)

        val fromLast = authorityFor(base, lastFilterKey)
        reduceHomeCustomEnrich(fromLast, base, ControllerAction.NAV_RIGHT)
        assertEquals(lastFilterKey, fromLast.state.value.focused)
    }

    @Test
    fun searchAndFiltersAreOneLogicalRowForVerticalMovement() {
        val base = state().let { b ->
            b.copy(filters = b.filters.map { f -> if (f.key == "kind") f.copy(selectedIndex = 1) else f })
        }
        val searchKey = homeCustomEnrichSearchKey(base)
        val filterKey = homeCustomEnrichFilterKey(base, base.visibleFilters().first())

        // Up from a filter is the top of the graph — it must NOT be forced through Search.
        val fromFilter = authorityFor(base, filterKey)
        reduceHomeCustomEnrich(fromFilter, base, ControllerAction.NAV_UP)
        assertEquals(filterKey, fromFilter.state.value.focused)

        // Down from either top-row member lands in the gallery.
        var transition = reduceHomeCustomEnrich(fromFilter, base, ControllerAction.NAV_DOWN)
        assertEquals(
            homeCustomEnrichAssetKey(base, HomeCustomEnrichAsset.Automatic),
            fromFilter.state.value.focused,
        )
        // Up from the gallery restores the row's last stable key — the filter, not Search.
        reduceHomeCustomEnrich(fromFilter, transition.state, ControllerAction.NAV_UP)
        assertEquals(filterKey, fromFilter.state.value.focused)

        val fromSearch = authorityFor(base, searchKey)
        reduceHomeCustomEnrich(fromSearch, base, ControllerAction.NAV_UP)
        assertEquals(searchKey, fromSearch.state.value.focused)
    }

    @Test
    fun tertiarySubmitsOnlyFromSearchAndSecondaryIsConsumedOnAnUnsavedResult() {
        val unsavedResult = HomeCustomEnrichResult.Icon(
            SteamGridDbTileFetcher.Candidate(
                url = "https://cdn2.steamgriddb.com/x.png",
                thumbUrl = "https://cdn2.steamgriddb.com/x-thumb.png",
            ),
        )
        val base = state().copy(
            queryDraft = "zelda",
            results = listOf(unsavedResult),
            searchStatus = HomeCustomEnrichSearchStatus.Complete(1),
        )
        val resultKey = homeCustomEnrichAssetKey(base, HomeCustomEnrichAsset.Result(unsavedResult))
        val onResult = authorityFor(base, resultKey)
        val transition = reduceHomeCustomEnrich(onResult, base, ControllerAction.SECONDARY)

        // X on an unsaved result is consumed: no removal, no media transfer, no state change.
        assertEquals(HomeCustomEnrichCommand.None, transition.command)
        assertEquals(HomeCustomEnrichOperation.IDLE, transition.state.operation)
        assertEquals(base.generation, transition.state.generation)

        val onSearch = authorityFor(base, homeCustomEnrichSearchKey(base))
        val submitted = reduceHomeCustomEnrich(onSearch, base, ControllerAction.TERTIARY)
        assertTrue(submitted.command is HomeCustomEnrichCommand.Search)
    }

    @Test
    fun galleryFollowScrollLawKeepsThePreviousTilePeekedOnPlainTilesAtOneGap() {
        assertEquals(0f, homeCustomEnrichGalleryScrollOffsetDp(0, 112, 16), 0f)
        assertEquals(0f, homeCustomEnrichGalleryScrollOffsetDp(1, 112, 16), 0f)
        // Past the first slot the previous tile stays peeked: offset = x(i) - tile - gap - clearance.
        assertEquals(128f, homeCustomEnrichGalleryScrollOffsetDp(2, 112, 16), 0f)
        // no "Results" label sits between the saved tiles and the results, so crossing from one to the other adds no lane:
        // every step is one tile and one gap.
        assertEquals(
            homeCustomEnrichGalleryScrollOffsetDp(3, 112, 16) + 112f + 16f,
            homeCustomEnrichGalleryScrollOffsetDp(4, 112, 16),
            0f,
        )
    }

    @Test
    fun previewUsesOnlyMeasuredGallerySlackAndHidesInsteadOfCompressing() {
        // Hardware-measured gallery bands: RP5 269/257dp and Thor 257/245dp at fs 1.0/1.3.
        assertEquals(117, homeCustomEnrichPreviewHeightDp(269, EnrichMediaKind.ICON, false))
        assertEquals(148, homeCustomEnrichPreviewHeightDp(269, EnrichMediaKind.HERO, false))
        assertEquals(137, homeCustomEnrichPreviewHeightDp(269, EnrichMediaKind.CLIP, false))
        assertEquals(117, homeCustomEnrichPreviewHeightDp(269, EnrichMediaKind.TOME_PHYSICAL, false))
        assertEquals(105, homeCustomEnrichPreviewHeightDp(257, EnrichMediaKind.ICON, false))
        assertEquals(136, homeCustomEnrichPreviewHeightDp(257, EnrichMediaKind.HERO, false))
        assertEquals(125, homeCustomEnrichPreviewHeightDp(257, EnrichMediaKind.CLIP, false))
        // Thor icon at fs 1.3 has only 93dp: below the honest 96dp media floor.
        assertEquals(0, homeCustomEnrichPreviewHeightDp(245, EnrichMediaKind.ICON, false))
        assertEquals(124, homeCustomEnrichPreviewHeightDp(245, EnrichMediaKind.HERO, false))
        assertEquals(113, homeCustomEnrichPreviewHeightDp(245, EnrichMediaKind.CLIP, false))
        // Exact contract edges: 95dp hides, 96dp shows, and roomy landscape never exceeds 200dp.
        assertEquals(0, homeCustomEnrichPreviewHeightDp(247, EnrichMediaKind.ICON, false))
        assertEquals(96, homeCustomEnrichPreviewHeightDp(248, EnrichMediaKind.ICON, false))
        assertEquals(200, homeCustomEnrichPreviewHeightDp(503, EnrichMediaKind.ICON, false))

        EnrichMediaKind.entries.forEach { kind ->
            assertEquals("700x350 kind=$kind", 0, homeCustomEnrichPreviewHeightDp(144, kind, false))
            assertEquals("360x360 kind=$kind", 0, homeCustomEnrichPreviewHeightDp(154, kind, true))
            assertEquals("RG square cap kind=$kind", 240, homeCustomEnrichPreviewHeightDp(503, kind, true))
        }
    }

    @Test
    fun previewIdentityFollowsGalleryMemoryAndMoreRetainsTheLastRealResult() {
        val current = saved("current", 1)
        val result = HomeCustomEnrichResult.Icon(
            SteamGridDbTileFetcher.Candidate(
                url = "https://cdn2.steamgriddb.com/result.png",
                thumbUrl = "https://cdn2.steamgriddb.com/result-thumb.png",
                previewPath = "/local/result.png",
            ),
        )
        val base = state(saved = listOf(current), active = current.assetId).copy(
            results = listOf(result),
            hasMore = true,
        )
        val resultAsset = HomeCustomEnrichAsset.Result(result)
        val resultKey = homeCustomEnrichAssetKey(base, resultAsset)
        val moreKey = homeCustomEnrichAssetKey(base, HomeCustomEnrichAsset.More)

        assertEquals(resultAsset, homeCustomEnrichPreviewAsset(base, resultKey))
        assertEquals(resultAsset, homeCustomEnrichPreviewAsset(base, moreKey))

        val remembered = base.copy(
            laneMemory = mapOf(HomeCustomEnrichLane.GALLERY to resultKey.value),
        )
        assertEquals(
            resultAsset,
            homeCustomEnrichPreviewAsset(remembered, homeCustomEnrichSearchKey(remembered)),
        )

        val activeAsset = HomeCustomEnrichAsset.Saved(current)
        assertEquals(
            activeAsset,
            homeCustomEnrichPreviewAsset(base, homeCustomEnrichSearchKey(base)),
        )
        assertEquals("CURRENT", homeCustomEnrichPreviewStatus(base, activeAsset))
        assertEquals("FOUND", homeCustomEnrichPreviewStatus(base, resultAsset))
        assertEquals(
            "SEARCHING",
            homeCustomEnrichPreviewStatus(
                base.copy(searchStatus = HomeCustomEnrichSearchStatus.Searching("SteamGridDB", 2)),
                resultAsset,
            ),
        )
        assertEquals(
            "SEARCH UNAVAILABLE",
            homeCustomEnrichPreviewStatus(
                base.copy(searchStatus = HomeCustomEnrichSearchStatus.Failed("Provider offline")),
                resultAsset,
            ),
        )
        assertEquals(
            "PREVIEW UNAVAILABLE",
            homeCustomEnrichPreviewStatus(base, HomeCustomEnrichAsset.Saved(current.copy(previewPath = ""))),
        )
    }

    @Test
    fun certifiedViewportBudgetsHoldAtFontScaleOneAndOnePointThree() {
        // Certified fixtures: 700x350 landscape (panel 0.96 - md padding) and 360x360 square
        // (panel 0.98 - sm padding). Compact reserves two support lines for provider/setup failures.
        // bounds, not string-length guesses.
        val landscapePanel = (350f * 0.96f).toInt() - 24
        val squarePanel = (360f * 0.98f).toInt() - 16
        listOf(1f, 1.3f).forEach { fontScale ->
            EnrichMediaKind.entries.forEach { kind ->
                assertTrue(
                    "kind=$kind fs=$fontScale landscape",
                    homeCustomEnrichRequiredHeightDp(kind, false, fontScale) <= landscapePanel,
                )
                assertTrue(
                    "kind=$kind fs=$fontScale square",
                    homeCustomEnrichRequiredHeightDp(kind, true, fontScale) <= squarePanel,
                )
            }
        }
        // The scaled lanes really scale: 1.3 grows the header/status model, never the clip guard.
        assertTrue(
            homeCustomEnrichRequiredHeightDp(EnrichMediaKind.ICON, true, 1.3f) >
                homeCustomEnrichRequiredHeightDp(EnrichMediaKind.ICON, true, 1f),
        )
        assertEquals(28, homeCustomEnrichTextLaneDp(15, 1.3f))
        assertEquals(24, homeCustomEnrichTextLaneDp(13, 1.3f))
    }

    @Test
    fun tomePhysicalLaneGetsFullPickerTreatment() {
        assertEquals(
            homeCustomEnrichTileWidthDp(EnrichMediaKind.ICON, false),
            homeCustomEnrichTileWidthDp(EnrichMediaKind.TOME_PHYSICAL, false),
        )
        assertEquals(
            homeCustomEnrichGalleryMinHeightDp(EnrichMediaKind.ICON, true),
            homeCustomEnrichGalleryMinHeightDp(EnrichMediaKind.TOME_PHYSICAL, true),
        )
        assertEquals(8, homeCustomEnrichResultCap(EnrichMediaKind.TOME_PHYSICAL))
    }

    @Test
    fun topBandFooterAndChipsKeepFullTargetsWithUncompressedRings() {
        // Both action bands are floors, never fixed squeezes: 48dp targets + measured ring air.
        assertEquals(56, homeCustomEnrichTopBandMinHeightDp())
        assertEquals(56, homeCustomEnrichActionRowMinHeightDp())
        assertTrue(homeCustomEnrichTopBandMinHeightDp() >= 48 + 2 * EnrichTargetRingAirDp)
        assertTrue(homeCustomEnrichActionRowMinHeightDp() >= 48 + 2 * EnrichTargetRingAirDp)
        // Measured chip seats settle only as far as needed to expose the complete ring-cleared seat.
        assertEquals(0, homeCustomEnrichChipScrollTargetPx(0, 200, 4, 50, 12))
        assertEquals(52, homeCustomEnrichChipScrollTargetPx(0, 200, 180, 60, 12))
        assertEquals(28, homeCustomEnrichChipScrollTargetPx(80, 200, 40, 60, 12))
        assertEquals(44, homeCustomEnrichChipScrollTargetPx(44, 200, 80, 40, 12))
        assertEquals(88, homeCustomEnrichChipScrollTargetPx(0, 180, 100, 200, 12))
    }

    @Test
    fun compactTopBandGivesDynamicFiltersTheGovernedOneLineSeat() {
        val compact = homeCustomEnrichTopBandWeights(narrowWidth = true)
        val landscape = homeCustomEnrichTopBandWeights(narrowWidth = false)
        assertEquals(0.31f, compact.search, 0f)
        assertEquals(0.69f, compact.filters, 0f)
        assertEquals(0.38f, landscape.search, 0f)
        assertEquals(0.62f, landscape.filters, 0f)

        val viewport = homeCustomEnrichCompactFilterViewportWidthDp()
        val filterSeats = listOf(EnrichMediaKind.ICON, EnrichMediaKind.HERO, EnrichMediaKind.CLIP)
            .flatMap(::homeCustomEnrichDefaultFilters)
            .flatMap { filter ->
                filter.values.indices.map { index ->
                    filter.copy(selectedIndex = index).let { "${it.label} · ${it.selectedDisplay}" }
                }
            }
        filterSeats.forEach { text ->
            assertTrue(
                "$text exceeded compact filter viewport $viewport",
                homeCustomEnrichFilterSeatWidthDp(text) <= viewport,
            )
        }
    }

    @Test
    fun compactSearchKeepsFullSpeechWhilePaintingOneGovernedWord() {
        val blank = state().copy(queryDraft = "")
        val compact = homeCustomEnrichSearchIntent(blank, compactSquare = true).resolve()
        assertEquals("SEARCH", compact.visualText)
        assertEquals("Search icon art", compact.spokenText)

        val landscape = homeCustomEnrichSearchIntent(blank, compactSquare = false).resolve()
        assertEquals("SEARCH ICON ART", landscape.visualText)
        assertEquals("Search icon art", landscape.spokenText)

        val query = homeCustomEnrichSearchIntent(blank.copy(queryDraft = "Pokémon Emerald"), true).resolve()
        assertEquals("Pokémon Emerald", query.visualText)
        assertEquals("Pokémon Emerald", query.spokenText)
    }

    @Test
    fun controlGeometryNormalizesRp5OdinAndCommonAspectRatios() {
        assertEquals(
            HomeCustomEnrichControlGeometry(compactTypography = true, narrowWidth = false),
            homeCustomEnrichControlGeometry(853f, 480f, 1f),
        )
        assertEquals(
            HomeCustomEnrichControlGeometry(compactTypography = true, narrowWidth = false),
            homeCustomEnrichControlGeometry(833f, 468f, 1f),
        )
        listOf(
            360f to 360f, // 1:1
            540f to 360f, // 3:2
            480f to 360f, // 4:3
            360f to 640f, // vertical
        ).forEach { (width, height) ->
            assertEquals(
                "$width x $height",
                HomeCustomEnrichControlGeometry(compactTypography = true, narrowWidth = true),
                homeCustomEnrichControlGeometry(width, height, 1f),
            )
        }
        assertTrue(homeCustomEnrichControlGeometry(853f, 480f, 1.3f).compactTypography)
        assertFalse(homeCustomEnrichControlGeometry(853f, 480f, 1.3f).narrowWidth)
        assertEquals(8, homeCustomEnrichFilterHorizontalPaddingDp(compactTypography = true))
        assertEquals(12, homeCustomEnrichFilterHorizontalPaddingDp(compactTypography = false))
    }

    @Test
    fun searchAndFiltersShareOneSyntheticRowBecauseVirtualBoundsWin() {
        val base = state().let { b ->
            b.copy(filters = b.filters.map { f -> if (f.key == "kind") f.copy(selectedIndex = 1) else f })
        }
        val graph = homeCustomEnrichLaneKeys(base)
        val searchBounds = syntheticEnrichBounds(homeCustomEnrichSearchKey(base), graph, 0)
        val filterBounds = base.visibleFilters().map { filter ->
            syntheticEnrichBounds(homeCustomEnrichFilterKey(base, filter), graph, 0)
        }
        filterBounds.forEach { bounds ->
            assertEquals(searchBounds.top, bounds.top, 0f)
            assertTrue(bounds.left > searchBounds.left)
        }
        val galleryBounds = syntheticEnrichBounds(
            homeCustomEnrichAssetKey(base, HomeCustomEnrichAsset.Automatic),
            graph,
            0,
        )
        assertTrue(galleryBounds.top > searchBounds.top)
    }

    @Test
    fun topRowMemoryRestoresTheLastKeyAcrossTheRealSequence() {
        var state = state().let { b ->
            b.copy(filters = b.filters.map { f -> if (f.key == "kind") f.copy(selectedIndex = 1) else f })
        }
        val searchKey = homeCustomEnrichSearchKey(state)
        val firstFilter = homeCustomEnrichFilterKey(state, state.visibleFilters().first())
        val authority = authorityFor(state, searchKey)

        // Search -> Right (first filter) -> Down (gallery) -> Up must restore THAT FILTER.
        state = reduceHomeCustomEnrich(authority, state, ControllerAction.NAV_RIGHT).state
        assertEquals(firstFilter, authority.state.value.focused)
        state = reduceHomeCustomEnrich(authority, state, ControllerAction.NAV_DOWN).state
        state = reduceHomeCustomEnrich(authority, state, ControllerAction.NAV_UP).state
        assertEquals(firstFilter, authority.state.value.focused)

        // Reverse: back to Search, then Down/Up restores Search — one identity, either member.
        state = reduceHomeCustomEnrich(authority, state, ControllerAction.NAV_LEFT).state
        assertEquals(searchKey, authority.state.value.focused)
        state = reduceHomeCustomEnrich(authority, state, ControllerAction.NAV_DOWN).state
        state = reduceHomeCustomEnrich(authority, state, ControllerAction.NAV_UP).state
        assertEquals(searchKey, authority.state.value.focused)

        // Finite edges hold inside the same run.
        reduceHomeCustomEnrich(authority, state, ControllerAction.NAV_LEFT)
        assertEquals(searchKey, authority.state.value.focused)
        reduceHomeCustomEnrich(authority, state, ControllerAction.NAV_UP)
        assertEquals(searchKey, authority.state.value.focused)
    }

    @Test
    fun heroChooserTilesStayCompactAndShrinkOnlyForPathologicalWidths() {
        // Hero chooser cards remain subordinate to the larger inline inspection stage.
        assertEquals(
            HomeCustomEnrichHeroSelectionWidthDp,
            homeCustomEnrichEffectiveTileWidthDp(EnrichMediaKind.HERO, true, 278),
        )
        // Roomy viewports keep that authored chooser width; square kinds are untouched everywhere.
        assertEquals(
            HomeCustomEnrichHeroSelectionWidthDp,
            homeCustomEnrichEffectiveTileWidthDp(EnrichMediaKind.HERO, true, 640),
        )
        assertEquals(
            112,
            homeCustomEnrichEffectiveTileWidthDp(EnrichMediaKind.ICON, false, 278),
        )
        // The floor keeps a legible tile even in a pathological viewport.
        assertEquals(
            96,
            homeCustomEnrichEffectiveTileWidthDp(EnrichMediaKind.HERO, true, 100),
        )
        // An unmeasured viewport falls back to the authored width instead of guessing.
        assertEquals(
            HomeCustomEnrichHeroSelectionWidthDp,
            homeCustomEnrichEffectiveTileWidthDp(EnrichMediaKind.HERO, true, 0),
        )
    }

    @Test
    fun kindsWithoutFiltersGiveSearchTheWholeBand() {
        assertTrue(state().copy(kind = EnrichMediaKind.TOME_PHYSICAL, filters = emptyList()).searchOwnsFullBand())
        assertFalse(state().searchOwnsFullBand())
    }

    @Test
    fun releaseAlphaMediaCopyUsesKindSpecificSearchAndCappedControllerLegends() {
        assertEquals("Search icon art", homeCustomEnrichSearchLabel(EnrichMediaKind.ICON))
        assertEquals("Search hero art", homeCustomEnrichSearchLabel(EnrichMediaKind.HERO))
        assertEquals("Search Hero Motion", homeCustomEnrichSearchLabel(EnrichMediaKind.CLIP))
        assertEquals("Search Cartridge Hero", homeCustomEnrichSearchLabel(EnrichMediaKind.TOME_PHYSICAL))
        assertEquals("Default", HomeCustomEnrichAsset.Automatic.label)

        val icon = state()
        assertEquals("A EDIT · Y SEARCH · B BACK", homeCustomEnrichHint(icon, HomeCustomEnrichLane.SEARCH))
        // 0aa81c38 turned the filter chips into dropdowns: A OPENS the list (see the CONFIRM arm that
        // reads `openFilter`/`focusedOptionIndex`), so the legend says OPEN, not the pre-dropdown CYCLE.
        assertEquals(MimirCopy.HintOpen, homeCustomEnrichHint(icon, HomeCustomEnrichLane.FILTERS))
        assertEquals("A OPEN · B BACK", MimirCopy.HintOpen)
        assertEquals("A CHOOSE · B BACK", homeCustomEnrichHint(icon, HomeCustomEnrichLane.FOOTER))
        assertEquals(
            "A RETRY · B BACK",
            homeCustomEnrichHint(icon, HomeCustomEnrichLane.FOOTER, retryFocused = true),
        )
        assertEquals("A USE · B BACK", homeCustomEnrichHint(icon, HomeCustomEnrichLane.GALLERY))
        assertEquals(
            "A USE · X REMOVE · B BACK",
            homeCustomEnrichHint(icon, HomeCustomEnrichLane.GALLERY, removableFocused = true),
        )
        assertEquals("Any", HomeCustomEnrichFilter("encode", "Format", listOf("Both")).selectedDisplay)
        assertEquals(
            "Optimized",
            HomeCustomEnrichFilter("encode", "Format", listOf("Normalized")).selectedDisplay,
        )
    }

    @Test
    fun settledPreviewOwnerEvolutionRejectsStaleDecodeWhilePreservingSamePathLoading() {
        val pathA = "/local/a"
        val pathB = "/local/b"
        val painterA = ColorPainter(Color.Red)
        val painterB = ColorPainter(Color.Blue)
        var owner: SettledPreviewOwner? = null

        owner = settledPreviewOwnerAfterDecode(
            owner,
            pathA,
            SettledPreviewDecodePhase.Success(pathA),
            painterA,
        )
        assertEquals(pathA, owner?.ownerPath)

        owner = settledPreviewOwnerAfterDecode(owner, pathB, SettledPreviewDecodePhase.Loading)
        assertNull(owner)

        owner = settledPreviewOwnerAfterDecode(
            owner,
            pathB,
            SettledPreviewDecodePhase.Success(pathA),
            painterA,
        )
        assertNull(owner)

        owner = settledPreviewOwnerAfterDecode(
            owner,
            pathB,
            SettledPreviewDecodePhase.Success(pathB),
            painterB,
        )
        assertEquals(pathB, owner?.ownerPath)

        owner = settledPreviewOwnerAfterDecode(owner, pathB, SettledPreviewDecodePhase.Loading)
        assertEquals(pathB, owner?.ownerPath)

        owner = settledPreviewOwnerAfterDecode(owner, pathB, SettledPreviewDecodePhase.Error)
        assertNull(owner)

        assertEquals(painterA, settledPreviewPainterForPath(pathA, pathA, painterA))
        assertNull(settledPreviewPainterForPath(pathA, pathB, painterA))
        assertNull(settledPreviewPainterForPath(pathA, null, painterA))
    }

    @Test
    fun previewStageHoldsOneSquareAspectForIconAndPhysicalAndWideForHeroAndClip() {
        assertEquals(1f, homeCustomEnrichPreviewStageAspect(EnrichMediaKind.ICON), 0f)
        assertEquals(1f, homeCustomEnrichPreviewStageAspect(EnrichMediaKind.TOME_PHYSICAL), 0f)
        assertEquals(16f / 9f, homeCustomEnrichPreviewStageAspect(EnrichMediaKind.HERO), 0.001f)
        assertEquals(16f / 9f, homeCustomEnrichPreviewStageAspect(EnrichMediaKind.CLIP), 0.001f)
    }

    @Test
    fun theExpandedHeroKeepsItsSixteenByNineFrame() {
        assertEquals(16f / 9f, HomeCustomEnrichExpandedHeroAspect, 0.001f)
    }

    @Test
    fun inlineHeroPreviewIsLargerThanItsSelectionTile() {
        val tileWidth = homeCustomEnrichTileWidthDp(EnrichMediaKind.HERO, compactSquare = false)
        val tileHeight = tileWidth / homeCustomEnrichPreviewStageAspect(EnrichMediaKind.HERO)
        val inlineHeight = homeCustomEnrichPreviewHeightDp(
            measuredGalleryHeightDp = 217,
            kind = EnrichMediaKind.HERO,
            compactSquare = false,
        )

        assertEquals(HomeCustomEnrichHeroSelectionWidthDp, tileWidth)
        assertTrue(inlineHeight > tileHeight)
        assertTrue(inlineHeight * homeCustomEnrichPreviewStageAspect(EnrichMediaKind.HERO) > tileWidth)
    }

    @Test
    fun clipFooterCarriesNoProviderProvenanceCopy() {
        val retiredClipFooterProvenancePhrases = listOf(
            "ScreenScraper",
            "Clips come from ",
            " · no uploads",
        )
        val clipState = state().copy(kind = EnrichMediaKind.CLIP)
        val footerLanes = listOf(
            HomeCustomEnrichLane.SEARCH,
            HomeCustomEnrichLane.FILTERS,
            HomeCustomEnrichLane.GALLERY,
            HomeCustomEnrichLane.FOOTER,
        )
        footerLanes.forEach { lane ->
            val copy = homeCustomEnrichFooterCopy(clipState, lane)
            val hint = homeCustomEnrichHint(clipState, lane)
            listOfNotNull(copy.legend, copy.status, hint).forEach { text ->
                retiredClipFooterProvenancePhrases.forEach { phrase ->
                    assertFalse("$phrase in $text", text.contains(phrase))
                }
            }
        }
        retiredClipFooterProvenancePhrases.forEach { phrase ->
            assertFalse(phrase, homeCustomEnrichSearchLabel(EnrichMediaKind.CLIP).contains(phrase))
        }
    }

    @Test
    fun automaticClipReadyIsCenteredStageCopyNotACornerStatus() {
        assertEquals("MOTION READY", HomeCustomEnrichAutomaticMotionReadyVisual)
        assertEquals("DEFAULT", homeCustomEnrichPreviewStatus(state(), HomeCustomEnrichAsset.Automatic))
        EnrichMediaKind.entries.forEach { kind ->
            val clipAutomatic = state(kind = kind).copy(
                defaultArt = HomeCustomEnrichDefaultArt.ClipAutomatic(posterUri = null),
            )
            assertNotEquals(
                HomeCustomEnrichAutomaticMotionReadyVisual,
                homeCustomEnrichPreviewStatus(clipAutomatic, HomeCustomEnrichAsset.Automatic),
            )
        }
    }

    @Test
    fun selectExpandHintSurvivesZeroInlinePreviewHeightOnRp5AndSquare() {
        val hero = state(kind = EnrichMediaKind.HERO).copy(
            catalogReady = true,
            saved = listOf(saved("hero", 1L)),
        )
        val focused = homeCustomEnrichAssetKey(hero, HomeCustomEnrichAsset.Saved(hero.saved.first()))
        assertEquals(0, homeCustomEnrichPreviewHeightDp(144, EnrichMediaKind.HERO, false))
        assertTrue(homeCustomEnrichShowsExpandHint(hero, focused))
    }

    @Test
    fun selectExpandIsSaidOnceAndByTheFooterForEveryKindThatExpands() {
        // the cue stood in the preview's caption AND in the footer. It is the footer's alone now.
        val source = java.io.File("app/src/main/kotlin/com/gruning/mimir/rewrite/HomeCustomEnrichPicker.kt")
            .takeIf { it.isFile }
            ?: java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/HomeCustomEnrichPicker.kt")
        val text = source.readText()
        assertFalse("the preview caption no longer says it", text.contains("visualOverride = \"SELECT · EXPAND\""))
        assertFalse(text.contains("text = \"Inspect preview\""))
        val footerCue = "text = \"SELECT \u00b7 Expand\""
        assertEquals("the footer says it once (${text.length} chars read from ${source.absolutePath})", 1, text.split(footerCue).size - 1)
        // The footer carries the cue for Hero art and Hero Motion only: a cover's SELECT is in its legend, an icon has none.
        EnrichMediaKind.entries.forEach { kind ->
            val withArt = state(kind = kind).copy(saved = listOf(saved("art", 1L)))
            val focused = homeCustomEnrichAssetKey(withArt, HomeCustomEnrichAsset.Saved(withArt.saved.first()))
            val wanted = kind == EnrichMediaKind.HERO || kind == EnrichMediaKind.CLIP
            assertEquals("$kind: the footer cue is said for Hero art and Hero Motion alone", wanted, homeCustomEnrichShowsExpandHint(withArt, focused))
        }
    }

    /** Reported duplicate cues: "SELECT · EXPAND" stood over "A USE · SELECT PREVIEW". One hint for that action: the legend's where it names the keys, the cue where it does not. */
    @Test
    fun theFooterSaysSelectOnceNeverTheCueOverALegendThatAlreadySaysIt() {
        val hero = state(kind = EnrichMediaKind.HERO).copy(catalogReady = true, saved = listOf(saved("hero", 1L)))
        val asset = HomeCustomEnrichAsset.Saved(hero.saved.first())
        val focused = homeCustomEnrichAssetKey(hero, asset)
        // On the gallery the legend says SELECT PREVIEW: the cue is not stacked above it.
        val galleryLegend = homeCustomEnrichFooterCopy(hero, HomeCustomEnrichLane.GALLERY, previewExpandable = true).legend
        assertTrue(galleryLegend!!.contains(HomeCustomEnrichSelectPreviewWords))
        assertFalse(homeCustomEnrichFooterSaysExpand(hero, focused, galleryLegend))
        val removableLegend = homeCustomEnrichFooterCopy(hero, HomeCustomEnrichLane.GALLERY, removableFocused = true, previewExpandable = true).legend
        assertFalse(homeCustomEnrichFooterSaysExpand(hero, focused, removableLegend))
        // Where the legend is another lane's (it names no SELECT), the cue is the one place the action is said.
        val uploadLegend = homeCustomEnrichFooterCopy(hero, HomeCustomEnrichLane.FOOTER).legend
        assertFalse(uploadLegend!!.contains("SELECT"))
        assertTrue(homeCustomEnrichFooterSaysExpand(hero, focused, uploadLegend))
        assertTrue(homeCustomEnrichFooterSaysExpand(hero, focused, null))
        // Whatever the lane, the footer never says SELECT twice.
        listOf(galleryLegend, removableLegend, uploadLegend, null).forEach { legend ->
            val said = (if (legend?.contains("SELECT") == true) 1 else 0) + (if (homeCustomEnrichFooterSaysExpand(hero, focused, legend)) 1 else 0)
            assertEquals("legend $legend", 1, said)
        }
    }

    @Test
    fun expandedHeroPreviewIsMateriallyLargerThanInlineOn360Square() {
        val ratio = homeCustomEnrichExpandedToInlineLinearRatio(
            viewportHeightDp = 360,
            kind = EnrichMediaKind.HERO,
            compactSquare = true,
            measuredGalleryHeightDp = 154,
        )
        assertTrue(ratio >= 1.25f)
    }

    @Test
    fun expandedHeroPreviewUsesCertifiedFractionsAndCardRadius() {
        assertEquals(0.96f, HomeCustomEnrichExpandedWidthFraction, 0f)
        assertEquals(0.94f, HomeCustomEnrichExpandedHeightFraction, 0f)
        assertEquals(8, HomeCustomEnrichExpandedContentPaddingDp)
        assertEquals(16f / 9f, HomeCustomEnrichExpandedHeroAspect, 0.001f)
        assertEquals(MimirSpacing.radiusCard.value, 14f, 0.01f)
    }

    @Test
    fun compactFooterFitsOneFocusedActionAndTruthfulLegend() {
        val available = homeCustomEnrichCompactFooterAvailableWidthDp()
        assertTrue(
            homeCustomEnrichCompactFooterRequiredWidthDp("＋ Upload", "A CHOOSE · B BACK") <= available,
        )
        assertTrue(
            homeCustomEnrichCompactFooterRequiredWidthDp("↻ Retry", "A RETRY · B BACK") <= available,
        )
    }

    @Test
    fun transientFooterSeparatesControllerLegendFromStatusCopy() {
        val copy = homeCustomEnrichFooterCopy(
            state().copy(operation = HomeCustomEnrichOperation.APPLY_FETCHING),
            HomeCustomEnrichLane.GALLERY,
        )
        assertNull(copy.legend)
        assertEquals("Preparing selection…", copy.status)
    }

    private fun state(
        saved: List<EnrichSavedAsset> = emptyList(),
        active: String? = null,
        kind: EnrichMediaKind = EnrichMediaKind.ICON,
    ) = HomeCustomEnrichState(
        sessionId = "session",
        kind = kind,
        cacheKey = "game-id",
        gameTitle = "Game",
        queryDraft = "Game",
        saved = saved,
        activeAssetId = active,
    )

    private fun saved(
        id: String,
        addedAt: Long,
        source: EnrichSavedSource = EnrichSavedSource.STEAMGRIDDB,
    ) = EnrichSavedAsset(
        assetId = id,
        source = source,
        label = id,
        localPath = "/local/$id",
        previewPath = "/local/$id",
        width = 1024,
        height = 1024,
        bytes = 10,
        addedAt = addedAt,
    )

    private fun heroCandidate(width: Int, height: Int) = CustomHeroCandidate(
        source = CustomHeroSource.STEAMGRIDDB,
        mediaType = CustomHeroMediaType.STATIC,
        remoteUrl = "https://cdn2.steamgriddb.com/hero.png",
        width = width,
        height = height,
    )

    private fun authorityFor(state: HomeCustomEnrichState, initial: FocusKey): DefaultFocusAuthority =
        DefaultFocusAuthority().also { authority ->
            homeCustomEnrichLaneKeys(state).values.flatten().forEachIndexed { index, key ->
                authority.registerVirtual(
                    key,
                    HomeCustomEnrichScope,
                    FocusBounds(index * 100f, 0f, index * 100f + 80f, 80f),
                )
            }
            authority.pushScope(HomeCustomEnrichScope)
            authority.requestFocus(initial)
        }
}

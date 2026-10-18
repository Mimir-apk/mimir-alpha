package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.ScreenScraperMetadataSource
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *  The order is SteamGridDB's
 * `logos` first (covered by `PcAutomaticLogoArtTest`), then ScreenScraper's `wheel` for a game SteamGridDB
 * has no logo for.
 *
 * Every provider here is a fake the test hands [ArtworkFetchController.stagePcLogo]: no request leaves the JVM. The two URL
 * lambdas and the two stage lambdas are the only places the production code reaches a provider, and each test counts them.
 */
class PcLogoFallbackTest {

    private val sgdbUrl = "https://cdn2.steamgriddb.com/logo/official.png"
    private val ssUrl = "https://www.screenscraper.fr/medias/wheel.png?devid=D&devpassword=SECRET&ssid=ME&sspassword=SECRET2"

    private class Calls {
        val asked = mutableListOf<String>()
        val staged = mutableListOf<String>()
        val log = mutableListOf<String>()
    }

    private fun run(
        calls: Calls,
        steamGridDb: String? = null,
        steamGridDbStages: Boolean = true,
        screenScraper: String? = null,
        screenScraperStages: Boolean = true,
    ): ArtworkFetchController.PcLogoSource? = runBlocking {
        ArtworkFetchController.stagePcLogo(
            steamGridDbLogoUrl = { calls.asked += "sgdb"; steamGridDb },
            stageSteamGridDb = { url -> calls.staged += "sgdb:$url"; steamGridDbStages },
            screenScraperLogoUrl = { calls.asked += "ss"; screenScraper },
            stageScreenScraper = { url -> calls.staged += "ss:$url"; screenScraperStages },
            log = { calls.log += it },
        )
    }

    @Test
    fun `a PC game with a SteamGridDB logo picks it, and ScreenScraper is never asked`() {
        val calls = Calls()

        val source = run(calls, steamGridDb = sgdbUrl, screenScraper = ssUrl)

        assertEquals(ArtworkFetchController.PcLogoSource.STEAMGRIDDB, source)
        assertEquals(listOf("sgdb"), calls.asked)
        assertEquals(listOf("sgdb:$sgdbUrl"), calls.staged)
    }

    @Test
    fun `with no SteamGridDB logo it falls back to the ScreenScraper wheel`() {
        val calls = Calls()

        val source = run(calls, steamGridDb = null, screenScraper = ssUrl)

        assertEquals(ArtworkFetchController.PcLogoSource.SCREENSCRAPER, source)
        assertEquals("SteamGridDB first, then ScreenScraper", listOf("sgdb", "ss"), calls.asked)
        assertEquals(listOf("ss:$ssUrl"), calls.staged)
    }

    @Test
    fun `a SteamGridDB logo whose bytes will not stage falls on to ScreenScraper like a miss`() {
        val calls = Calls()

        val source = run(calls, steamGridDb = sgdbUrl, steamGridDbStages = false, screenScraper = ssUrl)

        assertEquals(ArtworkFetchController.PcLogoSource.SCREENSCRAPER, source)
        assertEquals(listOf("sgdb:$sgdbUrl", "ss:$ssUrl"), calls.staged)
    }

    @Test
    fun `no logo from either provider stages nothing and the game keeps its fallback`() {
        val calls = Calls()

        assertNull(run(calls, steamGridDb = null, screenScraper = null))
        assertTrue("nothing was staged", calls.staged.isEmpty())

        val unusable = Calls()
        assertNull(run(unusable, steamGridDb = sgdbUrl, steamGridDbStages = false, screenScraper = ssUrl, screenScraperStages = false))
    }

    @Test
    fun `with no key for either provider there is no request, because each URL step answers null before it builds one`() = runBlocking {
        // SteamGridDB: a blank key returns before the gate or the network is touched.
        assertNull(CustomHeroFetcher.steamGridDbLogoUrlForGame("", SteamGridDbGameRef.SteamApp(1)))
        assertNull(CustomHeroFetcher.steamGridDbLogoUrlForGame("   ", SteamGridDbGameRef.Game(7L)))

        // ScreenScraper: the app-side step closes on the connection gate before it attaches or builds a query, and the source
        // itself answers null with no registration (`ScreenScraperPcLogoTest`). Both are pinned where they are written.
        val body = source().substringAfter("private suspend fun pcScreenScraperLogoOutcome(").substringBefore("/** Downloads ScreenScraper's logo")
        assertTrue(body.indexOf("if (!MimirConnectedArtGate.isOpen) return ScreenScraperMetadataSource.PcLogoOutcome.Unavailable") in 0 until body.indexOf("ArtworkConnectController.attach(context)"))
        assertTrue(body.indexOf("ArtworkConnectController.attach(context)") < body.indexOf(".pcLogoOutcome("))
    }

    @Test
    fun `the log lines are fixed, naming the source and the outcome and never a URL or a credential`() {
        val calls = Calls()
        run(calls, steamGridDb = sgdbUrl, steamGridDbStages = false, screenScraper = ssUrl)
        run(calls, steamGridDb = null, screenScraper = ssUrl)

        assertEquals(
            listOf(
                "pc logo source=steamgriddb outcome=unusable",
                "pc logo source=screenscraper outcome=staged",
                "pc logo source=screenscraper outcome=staged",
            ),
            calls.log,
        )
        calls.log.forEach { line ->
            assertFalse(line, line.contains("http", ignoreCase = true))
            assertFalse(line, line.contains("SECRET"))
            assertFalse(line, line.contains("devid", ignoreCase = true))
            assertFalse(line, line.contains("ssid", ignoreCase = true))
        }
    }

    @Test
    fun `source text -- no log call on the PC logo lane can name a URL`() {
        val src = source()
        val lane = src.substringAfter("internal enum class PcLogoSource").substringBefore("/** Explicit Enrich always refreshes")
        val logoStart = src.indexOf("val logoStageFile = ")
        assertTrue("PC logo staging declaration must exist", logoStart >= 0)
        val logoEnd = src.indexOf("install = {", startIndex = logoStart)
        assertTrue("PC logo install block must follow staging", logoEnd > logoStart)
        val logoBlock = src.substring(logoStart, logoEnd)
        assertTrue("the slices are the lane, not the whole file", lane.length in 500..16_000 && logoBlock.length in 200..3_000)

        val logLines = (lane + logoBlock).lines().map(String::trim).filter { it.startsWith("log(") || it.contains("Log.") }
        assertTrue("the lane does log its outcomes", logLines.size >= 5)
        logLines.forEach { line ->
            val fixedLiteral = Regex("""log\("pc logo source=[a-z]+ outcome=[a-z]+"\)""").matches(line)
            val theOneSink = line == "log = { line -> Log.i(PcLookupLogTag, line) },"
            assertTrue("a log call on this lane is a fixed line or the one sink, never a URL: $line", fixedLiteral || theOneSink)
        }
    }

    @Test
    fun `source text -- SteamGridDB leads, ScreenScraper is the explicit-press fallback, into the logo slot`() {
        val src = source()
        val block = src.substringAfter("private suspend fun fetchAndPersistSteamGridDbGame(")
            .substringBefore("if (SteamGridDbTileFetcher.hasTile(context, cacheKey)) return OneResult.Matched")
        val sgdb = block.indexOf("steamGridDbLogoUrl = { CustomHeroFetcher.steamGridDbLogoUrlForGame(apiKey, game) }")
        val ss = block.indexOf("screenScraperLogoUrl = { if (explicitPress) pcScreenScraperLogoUrl(context, g, title) else null }")
        assertTrue("SteamGridDB first", sgdb in 0 until ss)
        assertTrue(block.contains("slot = ManagedMediaSlot.LOGO,"))
        assertTrue(block.contains("nameSuffix = LogoSuffix,"))
        // Create-only, still: an existing logo skips the whole lane.
        assertTrue(block.contains("existingUri = LocalArtSource(context).logoUriFor(g.sourceDocumentUri, g.managedPath)"))
        // The ScreenScraper lane reads its URL only from the source's PC logo seam, never building one here.
        assertFalse(src.contains("screenscraper.fr/api2"))
    }

    // ---- fix round 2026-10-06 -------------------------------------------------------------------------------------------------

    @Test
    fun `only the per-game Enrich press is an explicit press -- the new-games batch asks ScreenScraper for no PC logo`() {
        val controller = source()
        assertTrue(
            "fetchOneArtwork defaults to NOT an explicit press",
            controller.substringAfter("suspend fun fetchOneArtwork(").substringBefore("): OneResult {").contains("explicitPress: Boolean = false,"),
        )
        assertFalse("no default-true left on any fetch entry point", controller.contains("explicitPress: Boolean = true"))

        val coordinator = coordinatorSource()
        // The new-games batch passes false, in so many words.
        assertTrue(
            coordinator.substringAfter("// The new-games batch is not a press").contains("ArtworkFetchController.fetchOneArtwork(context, gameId, explicitPress = false)"),
        )
        assertFalse(coordinator.contains("ArtworkFetchController.fetchOneArtwork(context, gameId)"))
        // The per-game press passes true, and nothing else in this file does.
        assertTrue(coordinator.contains("ArtworkFetchController.fetchOneArtwork(ctx, id, names, explicitPress = true)"))
        assertEquals(1, Regex("explicitPress = true").findAll(coordinator).count())

        // The two doors the ScreenScraper lane has, both closed to a caller that is not an explicit press.
        assertTrue(controller.contains("screenScraperLogoUrl = { if (explicitPress) pcScreenScraperLogoUrl(context, g, title) else null }"))
        val only = controller.substringAfter("private suspend fun screenScraperOnlyPcLogo(").substringBefore("private suspend fun pcScreenScraperLogoUrl(")
        assertTrue(
            "ScreenScraper alone is asked only after the press and the connection are checked, before any request",
            only.indexOf("if (!explicitPress || !MimirConnectedArtGate.isOpen) return nothingWritten") in 0 until only.indexOf("pcScreenScraperLogoOutcome(context, g, title)"),
        )
        // The bulk PC pass is unchanged: explicitPress = false.
        assertTrue(controller.contains("fetchOne = { gameId -> fetchOneArtwork(context, gameId, explicitPress = false) },"))
    }

    @Test
    fun `ScreenScraper is reachable on an Enrich press when SteamGridDB has no key or gate, knows no such game, or has no logo`() {
        val controller = source()
        val pc = controller.substringAfter("private suspend fun fetchOnePcArtwork(").substringBefore("/** How [fetchOnePcArtwork]'s identification step ended")

        // (a) no SteamGridDB key or gate: the answer used to be GateClosed before anything was asked.
        val unavailable = pc.indexOf("val steamGridDbUnavailable = apiKey.isBlank() || !MimirSteamGridDbGate.isOpen")
        val firstSteamGridDbCall = pc.indexOf("steamAppIdForShortcut(")
        assertTrue(unavailable in 0 until firstSteamGridDbCall)
        assertTrue(pc.contains("screenScraperOnlyPcLogo(context, g, title, explicitPress, nothingWritten = OneResult.GateClosed)"))
        assertTrue(
            "the early return no longer ends at GateClosed with no ScreenScraper question",
            pc.substringAfter("if (steamGridDbUnavailable) {").substringBefore("}").contains("screenScraperOnlyPcLogo("),
        )

        // (b) SteamGridDB knows no such game (NoMatch, and only NoMatch: a transport failure is not a miss).
        assertTrue(pc.contains("if (lookup.result == OneResult.NoMatch) {"))
        assertTrue(pc.contains("screenScraperOnlyPcLogo(context, g, title, explicitPress, nothingWritten = OneResult.NoMatch)"))

        // (c) SteamGridDB resolved the game but has no logo: the fallback inside the same logo step (behaviour: stagePcLogo above).
        val persist = controller.substringAfter("private suspend fun fetchAndPersistSteamGridDbGame(").substringBefore("if (SteamGridDbTileFetcher.hasTile(")
        assertTrue(persist.contains("stagePcLogo("))

        // Create-only, same slot, same publish, for the ScreenScraper-only door too.
        val only = controller.substringAfter("private suspend fun screenScraperOnlyPcLogo(").substringBefore("private suspend fun pcScreenScraperLogoUrl(")
        assertTrue(only.contains("LocalArtSource(context).logoUriFor(g.sourceDocumentUri, g.managedPath)"))
        assertTrue(only.contains("slot = ManagedMediaSlot.LOGO,") && only.contains("nameSuffix = LogoSuffix,"))
        assertTrue(only.contains("publishWrittenMedia(context, listOf(written.asChangedMedia()))"))
        // It writes the logo and nothing else: no tile, hero or description step is reachable from it. The one record it adds is the settle-only
        // marker, once the logo has landed (a keyless PC game kept its Enrich seat after the logo was on disk).
        listOf("fetchMissing", "attemptPcHeroWrite", "fetchAndPersistSteamDescription", "ArtResolutionStore", "ArtNoMatchStore")
            .forEach { assertFalse("the ScreenScraper-only logo door must not call $it", only.contains(it)) }
        assertTrue(
            "the logo that landed settles the game like the SteamGridDB door does",
            only.contains("settlePcLogoOnlyPress(logoLanded = written != null) { persistSteamGridDbVerifiedMarker(context, g) }"),
        )
    }

    @Test
    fun `a ScreenScraper-only press that landed the logo writes the settle-only marker, and one that did not writes none`() {
        var written = 0
        val landed = runBlocking { ArtworkFetchController.settlePcLogoOnlyPress(logoLanded = true) { written++; true } }
        assertTrue("the marker persisted", landed)
        assertEquals("exactly one marker write for a logo that landed", 1, written)

        val missed = runBlocking { ArtworkFetchController.settlePcLogoOnlyPress(logoLanded = false) { written++; true } }
        assertFalse("no logo, no marker", missed)
        assertEquals("a press that landed nothing never reaches the marker write", 1, written)
    }

    @Test
    fun `an Enrich press with no SteamGridDB provider stages ScreenScraper's wheel and nothing else is asked`() {
        val calls = Calls()

        // The ScreenScraper-only door's staging call, as written: SteamGridDB answers nothing and stages nothing.
        val staged = runBlocking {
            ArtworkFetchController.stagePcLogo(
                steamGridDbLogoUrl = { null },
                stageSteamGridDb = { calls.staged += "sgdb"; false },
                screenScraperLogoUrl = { calls.asked += "ss"; ssUrl },
                stageScreenScraper = { url -> calls.staged += "ss:$url"; true },
                log = { calls.log += it },
            )
        }

        assertEquals(ArtworkFetchController.PcLogoSource.SCREENSCRAPER, staged)
        assertEquals(listOf("ss"), calls.asked)
        assertEquals(listOf("ss:$ssUrl"), calls.staged)
        assertEquals(listOf("pc logo source=screenscraper outcome=staged"), calls.log)
    }

    // ---- fix round ux3 -------------------------------------------------------------------------------------------------------

    @Test
    fun `a failed ScreenScraper lookup keeps the failure card, and only a verified miss is NoMatch`() {
        val closed = ArtworkFetchController.OneResult.GateClosed
        val miss = ArtworkFetchController.OneResult.NoMatch
        val failed = ArtworkFetchController.OneResult.Failed
        fun verdict(before: ArtworkFetchController.OneResult, o: ScreenScraperMetadataSource.PcLogoOutcome?) =
            ArtworkFetchController.pcLogoOnlyVerdict(before, o)

        // A transport failure, or an auth/quota/server rejection, reaches here as Failed: never "no art".
        assertEquals(failed, verdict(closed, ScreenScraperMetadataSource.PcLogoOutcome.Failed))
        assertEquals(failed, verdict(miss, ScreenScraperMetadataSource.PcLogoOutcome.Failed))
        // A logo that was offered but could not be downloaded, decoded or installed is a failure too.
        assertEquals(failed, verdict(closed, ScreenScraperMetadataSource.PcLogoOutcome.Found("https://www.screenscraper.fr/wheel.png")))
        assertEquals(failed, verdict(miss, ScreenScraperMetadataSource.PcLogoOutcome.Found("https://www.screenscraper.fr/wheel.png")))
        // Answered every PC system and had none: the one real miss, whichever door it came through.
        assertEquals(miss, verdict(closed, ScreenScraperMetadataSource.PcLogoOutcome.NoMatch))
        assertEquals(miss, verdict(miss, ScreenScraperMetadataSource.PcLogoOutcome.NoMatch))
        // Nothing was asked (connection closed or no registration): the caller's own verdict stands.
        assertEquals(closed, verdict(closed, null))
        assertEquals(closed, verdict(closed, ScreenScraperMetadataSource.PcLogoOutcome.Unavailable))
        assertEquals(miss, verdict(miss, ScreenScraperMetadataSource.PcLogoOutcome.Unavailable))
    }

    @Test
    fun `source text -- the ScreenScraper-only door ends in the verdict, not in a guess that it asked`() {
        val only = source().substringAfter("private suspend fun screenScraperOnlyPcLogo(").substringBefore("private suspend fun pcScreenScraperLogoUrl(")
        assertTrue(only.contains("return pcLogoOnlyVerdict(nothingWritten, outcome)"))
        assertFalse("no 'asked means NoMatch' shortcut", only.contains("var asked"))
        assertTrue(only.contains("pcScreenScraperLogoOutcome(context, g, title)"))
        // The lookup is reached through the source's outcome, which keeps a failure apart from a miss.
        assertTrue(source().contains("ArtworkConnectController.source.pcLogoOutcome("))
    }

    @Test
    fun `source text -- a ScreenScraper-only press labels its card ScreenScraper, and both closed still names SteamGridDB`() {
        val coordinator = coordinatorSource()
        val label = coordinator.substringAfter("val isPc = row.platformId == PlatformId.PC_GAMES").substringBefore("val gateOpen =")
        assertTrue(label.contains("if (isPc && (MimirSteamGridDbGate.isOpen || !MimirConnectedArtGate.isOpen)) {"))
        assertTrue(label.contains("GetArtProvider.STEAMGRIDDB") && label.contains("GetArtProvider.SCREENSCRAPER"))
    }

    private fun coordinatorSource(): String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirArtworkCoordinator.kt"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/MimirArtworkCoordinator.kt"),
    ).first(File::isFile).readText()

    private fun source(): String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/ArtworkFetchController.kt"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/ArtworkFetchController.kt"),
    ).first(File::isFile).readText()
}

package com.gruning.mimir.rewrite

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC Enrich downloads both hero and real logo assets needed by the launch-card animation. It asks
 * SteamGridDB for the game's logo, as it already asks for its hero.
 */
class PcAutomaticLogoArtTest {

    @Test
    fun `the official logo wins, then white, then any other, and black comes last on Mimir's dark backdrop`() {
        val entries = listOf(
            SteamGridDbLogoEntry("https://cdn2.steamgriddb.com/logo/black.png", "black", score = 90),
            SteamGridDbLogoEntry("https://cdn2.steamgriddb.com/logo/custom.png", "custom", score = 50),
            SteamGridDbLogoEntry("https://cdn2.steamgriddb.com/logo/white.png", "white", score = 10),
            SteamGridDbLogoEntry("https://cdn2.steamgriddb.com/logo/official.png", "official", score = 1),
        )
        assertEquals("official", steamGridDbBestLogo(entries)?.style)
        assertEquals("white", steamGridDbBestLogo(entries.filter { it.style != "official" })?.style)
        assertEquals("custom", steamGridDbBestLogo(entries.filter { it.style == "custom" || it.style == "black" })?.style)
        assertEquals("black", steamGridDbBestLogo(entries.filter { it.style == "black" })?.style)
    }

    @Test
    fun `within a style the community's score decides, and a blank url is never a logo`() {
        val entries = listOf(
            SteamGridDbLogoEntry("https://cdn2.steamgriddb.com/logo/low.png", "official", score = 3),
            SteamGridDbLogoEntry("https://cdn2.steamgriddb.com/logo/high.png", "official", score = 7),
            SteamGridDbLogoEntry("", "official", score = 99),
        )
        assertEquals("https://cdn2.steamgriddb.com/logo/high.png", steamGridDbBestLogo(entries)?.url)
        assertNull(steamGridDbBestLogo(emptyList()))
    }

    @Test
    fun `create-only -- an existing logo skips fetch, stage and install`() = runBlocking {
        var calls = 0
        val result = ArtworkFetchController.attemptPcMediaWrite<String>(
            existingUri = "file:///roms/media_art/Some Game-logo.png",
            fetchCandidate = { calls++; "https://cdn2.steamgriddb.com/logo/x.png" },
            stage = { calls++; true },
            install = { calls++; null },
        )
        assertNull(result)
        assertEquals(0, calls)
    }

    @Test
    fun `every PC enrich asks for the logo, into the logo slot, before the tile early return`() {
        val source = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/ArtworkFetchController.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/ArtworkFetchController.kt"),
        ).first(File::isFile).readText()
        val body = source.substringAfter("private suspend fun fetchAndPersistSteamGridDbGame(")
            .substringBefore("if (SteamGridDbTileFetcher.hasTile(context, cacheKey)) return OneResult.Matched")
        assertTrue(body.contains("CustomHeroFetcher.steamGridDbLogoUrlForGame(apiKey, game)"))
        assertTrue(body.contains("LocalArtSource(context).logoUriFor(g.sourceDocumentUri, g.managedPath)"))
        assertTrue(body.contains("slot = ManagedMediaSlot.LOGO,"))
        assertTrue(body.contains("nameSuffix = LogoSuffix,"))
        assertTrue(body.contains("if (logoWritten != null) publishWrittenMedia("))
    }
}

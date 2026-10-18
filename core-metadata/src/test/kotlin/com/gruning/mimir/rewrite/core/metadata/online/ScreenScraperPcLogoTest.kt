package com.gruning.mimir.rewrite.core.metadata.online

import com.gruning.mimir.rewrite.core.metadata.ContentHash
import com.gruning.mimir.rewrite.core.metadata.MetadataQuery
import com.gruning.mimir.rewrite.core.model.PlatformId
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *  SteamGridDB is asked first
 * (the app, `PcAutomaticLogoArtTest` and `PcLogoFallbackTest`); this is ScreenScraper's half, the fallback.
 *
 * Every response here is a FAKE the test hands the source: no request leaves the JVM. The requests the source built are
 * recorded and read back, which is how "no key, no request" and "never a hash or a provider id" are proved.
 */
class ScreenScraperPcLogoTest {

    private val dev = DevAuth(devId = "D", devPassword = "P", softName = "MimirApk")
    private val query = MetadataQuery(platformId = PlatformId.PC_GAMES, candidateNames = listOf("Blasphemous 2"))

    private fun source(
        gateOpen: Boolean = true,
        devAuth: DevAuth? = dev,
        responder: (urls: List<String>) -> String?,
    ): Pair<ScreenScraperMetadataSource, MutableList<String>> {
        val urls = mutableListOf<String>()
        val src = ScreenScraperMetadataSource(
            gateOpen = { gateOpen },
            devAuth = { devAuth },
            httpGet = { url -> urls += url; responder(urls) },
        )
        return src to urls
    }

    @Test
    fun `a PC game's wheel is picked from a PC system, by name`() = runBlocking {
        val (src, urls) = source { game(system = 138, medias = WHEEL_WOR) }

        val logo = src.pcLogoUrl(query)

        assertEquals("https://www.screenscraper.fr/wheel-wor.png", logo)
        assertEquals("one request answered it", 1, urls.size)
        assertTrue("asked about the Windows system", urls[0].contains("systemeid=138"))
        assertTrue("by the game's name", urls[0].contains("romnom=Blasphemous"))
    }

    @Test
    fun `wheel-hd leads wheel, and a screenshot or fan art is never a logo`() = runBlocking {
        val both = source { game(system = 138, medias = WHEEL_WOR + "," + WHEEL_HD_WOR) }.first
        assertEquals("https://www.screenscraper.fr/wheel-hd-wor.png", both.pcLogoUrl(query))

        val artOnly = source { game(system = 138, medias = FAN_ART + "," + SCREENSHOT) }.first
        assertNull("art that is not a logo is no logo", artOnly.pcLogoUrl(query))
    }

    @Test
    fun `a game ScreenScraper keeps on the DOS system is found there when Windows has none`() = runBlocking {
        val (src, urls) = source { calls ->
            if (calls.size == 1) NOT_FOUND else game(system = 135, medias = WHEEL_WOR)
        }

        assertEquals("https://www.screenscraper.fr/wheel-wor.png", src.pcLogoUrl(query))
        assertTrue(urls[0].contains("systemeid=138"))
        assertTrue(urls[1].contains("systemeid=135"))
        assertEquals("never more than one request per PC system", 2, urls.size)
    }

    @Test
    fun `no logo anywhere is null, after one request per PC system and no more`() = runBlocking {
        val (src, urls) = source { NOT_FOUND }

        assertNull(src.pcLogoUrl(query))
        assertEquals(ScreenScraperMetadataSource.PC_LOGO_SYSTEME_IDS.size, urls.size)
    }

    @Test
    fun `with the connection closed or no registration there is no request at all`() = runBlocking {
        val (closed, closedUrls) = source(gateOpen = false) { game(system = 138, medias = WHEEL_WOR) }
        assertNull(closed.pcLogoUrl(query))
        assertTrue("a closed gate never builds a request", closedUrls.isEmpty())

        val (unregistered, unregisteredUrls) = source(devAuth = null) { game(system = 138, medias = WHEEL_WOR) }
        assertNull(unregistered.pcLogoUrl(query))
        assertTrue("no software registration never builds a request", unregisteredUrls.isEmpty())
    }

    @Test
    fun `a match on another system is rejected, as every ScreenScraper lookup rejects it`() = runBlocking {
        val (src, _) = source { game(system = 12, medias = WHEEL_WOR) }

        assertNull(src.pcLogoUrl(query))
    }

    @Test
    fun `a different game that merely shares the system is rejected by name`() = runBlocking {
        val (src, _) = source { game(system = 138, name = "Totally Unrelated Title", medias = WHEEL_WOR) }

        assertNull(src.pcLogoUrl(query))
    }

    @Test
    fun `a PC game never sends a hash or a provider id, so a SteamGridDB id cannot ride into ScreenScraper`() = runBlocking {
        val (src, urls) = source { NOT_FOUND }
        val withEvidence = query.copy(
            contentHash = ContentHash(crc32 = "AAAA", md5 = "bbbb", sha1 = "cccc", sizeBytes = 99),
            providerGameId = "sgdb-777",
        )

        src.pcLogoUrl(withEvidence)

        assertTrue(urls.isNotEmpty())
        urls.forEach { url ->
            assertFalse(url, url.contains("crc="))
            assertFalse(url, url.contains("md5="))
            assertFalse(url, url.contains("sha1="))
            assertFalse(url, url.contains("gameid="))
        }
    }

    @Test
    fun `pc_games stays unmapped, so no PC game joins the ScreenScraper cover and hero batch`() = runBlocking {
        assertNull(ScreenScraperSystemMap.systemeIdFor(PlatformId.PC_GAMES))
        val (src, urls) = source { game(system = 138, medias = WHEEL_WOR) }

        assertEquals(ScreenScraperMetadataSource.LookupOutcome.PlatformUnsupported, src.lookupOutcome(query))
        assertTrue("the ordinary lookup still makes no request for a PC game", urls.isEmpty())
    }

    @Test
    fun `the lane logs nothing, so a credentialed URL cannot reach the log`() {
        val source = listOf(
            File("core-metadata/src/main/kotlin/com/gruning/mimir/rewrite/core/metadata/online/ScreenScraperMetadataSource.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/core/metadata/online/ScreenScraperMetadataSource.kt"),
        ).first(File::isFile).readText()
        val lane = source.substringAfter("sealed interface PcLogoOutcome").substringBefore("/** Distinguishes a verified media miss")
        assertTrue("the slice is the lane, not the whole file", lane.length in 400..6_000)
        assertFalse(lane.contains("Log."))
        assertFalse(lane.contains("println"))
        assertFalse(lane.contains("Timber"))
    }

    @Test
    fun `the right id is not enough, the response must NAME a PC system`() = runBlocking {
        // Fix round 2026-10-06: 138 and 135 are unverified against a live account, so an id that is really another system must
        // never file that system's wheel as a PC game's logo.
        val other = source { game(system = 138, systemName = "Game Boy Advance", medias = WHEEL_WOR) }.first
        assertNull("a non-PC system name is rejected even on the id asked", other.pcLogoUrl(query))

        val unnamed = source { game(system = 138, systemName = null, medias = WHEEL_WOR) }.first
        assertNull("a response that names no system is rejected too", unnamed.pcLogoUrl(query))

        val dos = source { game(system = 135, systemName = "PC Dos", medias = WHEEL_WOR) }.first
        assertEquals("a Windows or DOS name on its own id is accepted", "https://www.screenscraper.fr/wheel-wor.png", dos.pcLogoUrl(query))
    }

    @Test
    fun `two games that share a title but not a year are not one game`() = runBlocking {
        val doom2016 = source { game(system = 138, name = "Doom (2016)", medias = WHEEL_WOR) }.first
        assertNull("Doom (1993) must not take Doom (2016)'s wheel", doom2016.pcLogoUrl(query.copy(candidateNames = listOf("Doom (1993)"))))
        assertNull("a year in the provider's title only is a different game too", doom2016.pcLogoUrl(query.copy(candidateNames = listOf("Doom"))))

        val doom1993 = source { game(system = 135, name = "Doom (1993)", medias = WHEEL_WOR) }.first
        assertNull("and the other way round", doom1993.pcLogoUrl(query.copy(candidateNames = listOf("Doom (2016)"))))
        assertEquals(
            "the same year is the same game",
            "https://www.screenscraper.fr/wheel-wor.png",
            source { game(system = 138, name = "DOOM (2016)", medias = WHEEL_WOR) }.first.pcLogoUrl(query.copy(candidateNames = listOf("Doom (2016)"))),
        )

        val classic = source { game(system = 138, name = "Doom (Eternal)", medias = WHEEL_WOR) }.first
        assertNull("a parenthesised word is part of the title here", classic.pcLogoUrl(query.copy(candidateNames = listOf("Doom (Classic)"))))
    }


    @Test
    fun `a transport failure is a failure, never a miss`() = runTest {
        val (src, urls) = source { null }

        assertEquals(ScreenScraperMetadataSource.PcLogoOutcome.Failed, src.pcLogoOutcome(query))
        assertTrue("it did try", urls.isNotEmpty())
        assertNull(src.pcLogoUrl(query))
    }

    @Test
    fun `an auth or quota rejection is a failure, never a miss`() = runTest {
        for (rejection in listOf("API fermé pour les non membres", "Erreur : Votre quota de scrape est dépassé !", "Vérifier les identifiants utilisateurs")) {
            val (src, _) = source { rejection }
            assertEquals(rejection, ScreenScraperMetadataSource.PcLogoOutcome.Failed, src.pcLogoOutcome(query))
        }
    }

    @Test
    fun `a real no-match, every PC system answering with no such game, is the only miss`() = runTest {
        val (src, urls) = source { NOT_FOUND }

        assertEquals(ScreenScraperMetadataSource.PcLogoOutcome.NoMatch, src.pcLogoOutcome(query))
        assertEquals(ScreenScraperMetadataSource.PC_LOGO_SYSTEME_IDS.size, urls.size)

        // A game ScreenScraper holds with no logo in it is a miss too: it answered and has nothing.
        val (artOnly, _) = source { game(system = 138, medias = FAN_ART) }
        assertEquals(ScreenScraperMetadataSource.PcLogoOutcome.NoMatch, artOnly.pcLogoOutcome(query))
    }

    @Test
    fun `a miss on one system and a failure on the other is a failure, because nothing complete was learned`() = runTest {
        val (src, _) = source { calls -> if (calls.size == 1) NOT_FOUND else null }

        assertEquals(ScreenScraperMetadataSource.PcLogoOutcome.Failed, src.pcLogoOutcome(query))
    }

    @Test
    fun `a logo is Found, and a closed connection is Unavailable with no request`() = runTest {
        val (found, _) = source { game(system = 138, medias = WHEEL_WOR) }
        assertEquals(
            ScreenScraperMetadataSource.PcLogoOutcome.Found("https://www.screenscraper.fr/wheel-wor.png"),
            found.pcLogoOutcome(query),
        )

        val (closed, urls) = source(gateOpen = false) { game(system = 138, medias = WHEEL_WOR) }
        assertEquals(ScreenScraperMetadataSource.PcLogoOutcome.Unavailable, closed.pcLogoOutcome(query))
        assertTrue(urls.isEmpty())
    }

    /** A jeuInfos answer. [systemName] defaults to the real label of the PC system asked, and null leaves the field out. */
    private fun game(
        system: Int,
        name: String = "Blasphemous 2",
        medias: String,
        systemName: String? = when (system) {
            138 -> "PC Windows"
            135 -> "PC Dos"
            else -> "Game Boy Advance"
        },
    ): String {
        val label = systemName?.let { "\",\"text\":\"$it" } ?: ""
        return """{"response":{"jeu":{"id":"4242","nom":"$name","systeme":{"id":"$system$label"},"medias":[$medias]}}}"""
    }

    private companion object {
        const val WHEEL_WOR = """{"type":"wheel","region":"wor","format":"png","url":"https://www.screenscraper.fr/wheel-wor.png"}"""
        const val WHEEL_HD_WOR = """{"type":"wheel-hd","region":"wor","format":"png","url":"https://www.screenscraper.fr/wheel-hd-wor.png"}"""
        const val FAN_ART = """{"type":"fanart","region":"wor","format":"png","url":"https://www.screenscraper.fr/fan.png"}"""
        const val SCREENSHOT = """{"type":"ss","region":"wor","format":"png","url":"https://www.screenscraper.fr/shot.png"}"""
        const val NOT_FOUND = "Erreur : Jeu non trouvée !"
    }
}

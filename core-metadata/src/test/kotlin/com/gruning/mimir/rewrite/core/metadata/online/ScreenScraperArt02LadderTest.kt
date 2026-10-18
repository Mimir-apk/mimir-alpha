package com.gruning.mimir.rewrite.core.metadata.online

import com.gruning.mimir.rewrite.core.metadata.ContentHash
import com.gruning.mimir.rewrite.core.metadata.MetadataQuery
import com.gruning.mimir.rewrite.core.model.PlatformId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-system artwork regression: GB requests returned GBA art. jeuInfos' name search fuzzy-matches
 * ACROSS systems and the old lookup accepted whatever came back. These tests pin the fixed contract:
 *
 *  1. STRICT system verification — a response whose `jeu.systeme.id` differs from the REQUESTED system is
 *     rejected (never a wrong cover), and the ladder falls through to the next candidate.
 *  2. Candidate ladder ORDER — verified providerGameId first (one precise call), then contentHash + the best
 *     name, then remaining candidate names name-only, capped.
 *  3. The verified `jeu.id` rides back on the record so the fetch layer can persist the mapping.
 */
class ScreenScraperArt02LadderTest {

    private val dev = DevAuth(devId = "D", devPassword = "P", softName = "MimirApk")

    /** dreamcast → systemeid 23 in the committed map; all fixtures below declare their OWN system. */
    private val platform = PlatformId("dreamcast")

    /** [responder] receives the 1-based call number; the returned list records every url sent. */
    private fun source(responder: (Int) -> String?): Pair<ScreenScraperMetadataSource, MutableList<String>> {
        val urls = mutableListOf<String>()
        val src = ScreenScraperMetadataSource(
            gateOpen = { true },
            devAuth = { dev },
            httpGet = { url -> urls += url; responder(urls.size) },
        )
        return src to urls
    }

    // ── 1. Strict cross-system rejection ──

    @Test fun crossSystem_match_is_rejected_and_ladder_falls_to_next_candidate() = runBlocking {
        // First call answers with a VERIFIED-WRONG system (12 ≠ 23) carrying perfectly good box art — the
        // exact wrong-art failure. Second call answers with the right system.
        val (src, urls) = source { call ->
            if (call == 1) WRONG_SYSTEM_GAME else RIGHT_SYSTEM_GAME.replace("\"Sonic\"", "\"Scan Title\"")
        }
        val rec = src.lookup(
            MetadataQuery(
                platformId = platform,
                contentHash = ContentHash(crc32 = "deadbeef", sizeBytes = 42L),
                rawBaseName = "raw-file-name",
                candidateNames = listOf("Canonical Name", "Scan Title"),
            ),
        )
        assertEquals(2, urls.size)
        assertEquals("https://www.screenscraper.fr/right-box.png", rec?.boxArtUri)
        // The accepted rung was the SECOND candidate — its name becomes the canonical title.
        assertEquals("Scan Title", rec?.canonicalTitle)
    }

    @Test fun crossSystem_only_responses_yield_null_never_a_wrong_cover() = runBlocking {
        val (src, _) = source { WRONG_SYSTEM_GAME }
        val rec = src.lookup(MetadataQuery(platformId = platform, rawBaseName = "Sonic"))
        assertNull("a verified-wrong-system response must NEVER produce a record", rec)
    }

    @Test fun unparseable_system_is_rejected_not_trusted() = runBlocking {
        val (src, _) = source { NO_SYSTEM_GAME }
        assertNull(src.lookup(MetadataQuery(platformId = platform, rawBaseName = "Sonic")))
    }

    // ── 2. Ladder order + shape ──

    @Test fun providerGameId_is_tried_first_and_alone() = runBlocking {
        val (src, urls) = source { RIGHT_SYSTEM_GAME }
        val rec = src.lookup(
            MetadataQuery(
                platformId = platform,
                contentHash = ContentHash(crc32 = "deadbeef"),
                rawBaseName = "raw-file-name",
                candidateNames = listOf("Canonical Name"),
                providerGameId = "777",
            ),
        )
        assertEquals(1, urls.size) // resolved precisely in ONE call
        assertTrue("first rung must be the gameid lookup", urls[0].contains("gameid=777"))
        assertTrue("the gameid rung must not send a name", !urls[0].contains("romnom="))
        assertEquals("https://www.screenscraper.fr/right-box.png", rec?.boxArtUri)
    }

    @Test fun hash_rides_the_first_name_rung_then_names_go_alone() = runBlocking {
        val (src, urls) = source { WRONG_SYSTEM_GAME } // force full ladder walk
        src.lookup(
            MetadataQuery(
                platformId = platform,
                contentHash = ContentHash(crc32 = "deadbeef", sizeBytes = 42L),
                rawBaseName = "raw-file-name",
                candidateNames = listOf("Canonical Name", "Scan Title"),
            ),
        )
        assertEquals(3, urls.size) // hash+canonical, scan-title, raw name
        assertTrue(urls[0].contains("crc=deadbeef") && urls[0].contains("romnom=Canonical+Name"))
        assertTrue(!urls[1].contains("crc=") && urls[1].contains("romnom=Scan+Title"))
        assertTrue(!urls[2].contains("crc=") && urls[2].contains("romnom=raw-file-name"))
    }

    @Test fun name_rungs_are_capped_so_quota_spend_stays_bounded() = runBlocking {
        val (src, urls) = source { WRONG_SYSTEM_GAME }
        src.lookup(
            MetadataQuery(
                platformId = platform,
                rawBaseName = "raw",
                candidateNames = listOf("A", "B", "C", "D", "E"),
            ),
        )
        // 1 hash+primary rung + MAX_NAME_CANDIDATES(2) name-only rungs = 3 calls, never more.
        assertEquals(3, urls.size)
    }

    @Test fun duplicate_and_blank_candidates_collapse() = runBlocking {
        val (src, urls) = source { WRONG_SYSTEM_GAME }
        src.lookup(
            MetadataQuery(
                platformId = platform,
                rawBaseName = "Sonic",
                candidateNames = listOf("Sonic", "  ", "Sonic "),
            ),
        )
        assertEquals(1, urls.size) // everything deduped to the one real name
    }

    @Test fun verified_but_artless_response_continues_the_ladder() = runBlocking {
        val (src, urls) = source { call ->
            if (call == 1) ARTLESS_RIGHT_SYSTEM_GAME
            else RIGHT_SYSTEM_GAME.replace("\"Sonic\"", "\"raw-file-name\"")
        }
        val rec = src.lookup(
            MetadataQuery(
                platformId = platform,
                rawBaseName = "raw-file-name",
                candidateNames = listOf("Canonical Name"),
            ),
        )
        assertEquals(2, urls.size)
        assertEquals("https://www.screenscraper.fr/right-box.png", rec?.boxArtUri)
    }

    // ── 3. The verified mapping rides back for persistence ──

    @Test fun record_carries_the_verified_provider_game_id() = runBlocking {
        val (src, _) = source { RIGHT_SYSTEM_GAME }
        val rec = src.lookup(MetadataQuery(platformId = platform, rawBaseName = "Sonic"))
        assertEquals("4242", rec?.providerGameId)
    }

    private companion object {
        /** Verified 23 (dreamcast) with box art — the honest match. */
        const val RIGHT_SYSTEM_GAME = """
        {"response":{"jeu":{"id":"4242","nom":"Sonic","systeme":{"id":"23"},"medias":[
          {"type":"box-2D","region":"wor","format":"png","url":"https://www.screenscraper.fr/right-box.png"}
        ]}}}
        """

        /** Verified 12 (gba) with box art — the wrong-art class; must be rejected despite good media. */
        const val WRONG_SYSTEM_GAME = """
        {"response":{"jeu":{"id":"666","nom":"Wrong Game","systeme":{"id":"12"},"medias":[
          {"type":"box-2D","region":"wor","format":"png","url":"https://www.screenscraper.fr/wrong-box.png"}
        ]}}}
        """

        /** A jeu block with NO parseable systeme — strict verification must reject it too. */
        const val NO_SYSTEM_GAME = """
        {"response":{"jeu":{"id":"9","nom":"Sonic","medias":[
          {"type":"box-2D","region":"wor","format":"png","url":"https://www.screenscraper.fr/mystery-box.png"}
        ]}}}
        """

        /** Right system, but nothing the art lanes use → keep walking the ladder. */
        const val ARTLESS_RIGHT_SYSTEM_GAME = """
        {"response":{"jeu":{"id":"4242","nom":"Canonical Name","systeme":{"id":"23"},"medias":[
          {"type":"manuel","region":"wor","format":"pdf","url":"https://www.screenscraper.fr/manual.pdf"}
        ]}}}
        """
    }
}

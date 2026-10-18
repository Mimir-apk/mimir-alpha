package com.gruning.mimir.rewrite.core.metadata.online

import com.gruning.mimir.rewrite.core.metadata.MetadataQuery
import com.gruning.mimir.rewrite.core.model.PlatformId
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Custom Hero: the explicit hero-media seam.
 *
 * It exists so `app` never builds a credentialed ScreenScraper URL of its own. These pin that it inherits
 * every guard `lookup` already had — the gate, the software registration, the system mapping, strict cross-system rejection, the bounded ladder — and that it is bounded and https-only.
 */
class ScreenScraperHeroMediaTest {

    private val dev = DevAuth(devId = "D", devPassword = "P", softName = "MimirApk")

    /** dreamcast → systemeid 23 in the committed map. */
    private val platform = PlatformId("dreamcast")

    private fun source(
        gateOpen: Boolean = true,
        devAuth: DevAuth? = dev,
        responder: (Int) -> String?,
    ): Pair<ScreenScraperMetadataSource, MutableList<String>> {
        val urls = mutableListOf<String>()
        val src = ScreenScraperMetadataSource(
            gateOpen = { gateOpen },
            devAuth = { devAuth },
            httpGet = { url -> urls += url; responder(urls.size) },
        )
        return src to urls
    }

    @Test
    fun `a closed gate never builds a request`() = runBlocking {
        val (src, urls) = source(gateOpen = false) { HERO_GAME }

        assertNull(src.heroMedia(MetadataQuery(platformId = platform, rawBaseName = "Sonic")))
        assertTrue("a closed gate must not touch the network", urls.isEmpty())
    }

    @Test
    fun `no software registration never builds a request`() = runBlocking {
        val (src, urls) = source(devAuth = null) { HERO_GAME }

        assertNull(src.heroMedia(MetadataQuery(platformId = platform, rawBaseName = "Sonic")))
        assertTrue(urls.isEmpty())
    }

    @Test
    fun `a cross-system match is rejected exactly as the art lane rejects it`() = runBlocking {
        val (src, _) = source { WRONG_SYSTEM_HERO }

        assertNull(
            "a verified-wrong-system response must never yield hero media",
            src.heroMedia(MetadataQuery(platformId = platform, rawBaseName = "Sonic")),
        )
    }

    @Test
    fun `a verified game returns its media in provider order with the verified id`() = runBlocking {
        val (src, urls) = source { HERO_GAME }

        val result = src.heroMedia(MetadataQuery(platformId = platform, rawBaseName = "Sonic"))

        assertEquals("4242", result?.providerGameId)
        assertEquals("Sonic", result?.canonicalTitle)
        assertEquals(
            listOf(
                "fanart" to "https://www.screenscraper.fr/fan.png",
                "ss" to "https://www.screenscraper.fr/shot.png",
            ),
            result?.medias,
        )
        assertEquals(1, urls.size)
    }

    @Test
    fun `a verified provider id is sent alone and first, never filename-guessed over`() = runBlocking {
        val (src, urls) = source { HERO_GAME }

        src.heroMedia(
            MetadataQuery(
                platformId = platform,
                rawBaseName = "some-scan-file",
                candidateNames = listOf("Some Scan Title"),
                providerGameId = "4242",
            ),
        )

        assertEquals("one precise call, not a name ladder", 1, urls.size)
        assertTrue("must query by verified id", urls[0].contains("gameid=4242"))
        assertTrue("must not send a name alongside the verified id", !urls[0].contains("romnom="))
    }

    @Test
    fun `a verified provider id failure is terminal and never falls through to a filename guess`() = runTest {
        // R6-P2-03 lever B: a transport null is now retried at the source (3 attempts, 2 real backoffs).
        // call <= 3 models a PERSISTENT transport failure on the exact rung (not a one-shot blip), so
        // this still proves the rung is terminal and never falls through to a guess; runTest's virtual
        // clock skips the ~2s of real backoff a persistent failure now costs under runBlocking.
        val (src, urls) = source { call -> if (call <= 3) null else HERO_GAME }

        assertNull(
            src.heroMedia(
                MetadataQuery(
                    platformId = platform,
                    candidateNames = listOf("A dangerous fuzzy fallback"),
                    providerGameId = "4242",
                ),
            ),
        )

        assertEquals(3, urls.size)
        assertTrue(urls.all { it.contains("gameid=4242") })
    }

    @Test
    fun `a wrong-system response for a verified provider id is terminal`() = runBlocking {
        val (src, urls) = source { call -> if (call == 1) WRONG_SYSTEM_HERO else HERO_GAME }

        assertNull(
            src.heroMedia(
                MetadataQuery(
                    platformId = platform,
                    rawBaseName = "fuzzy-name",
                    providerGameId = "4242",
                ),
            ),
        )

        assertEquals(1, urls.size)
    }

    /**
     * The verified id stays FIRST, but "this identity carries none of the media kind you asked for" is
     * not the same fact as "this identity is wrong". Presentation searches walk on to the bounded
     * seeded-title rungs; only a rung that actually verifies may contribute media, so the wrong-art
     * class ART02 exists to prevent is still closed (see the unverifiable-name case below).
     */
    @Test
    fun `a media-less verified provider id falls through to the bounded seeded-title ladder`() = runBlocking {
        val (src, urls) = source { call -> if (call == 1) MEDIA_LESS_GAME else HERO_GAME }

        val outcome = src.heroMediaOutcome(
            MetadataQuery(
                platformId = platform,
                candidateNames = listOf("Sonic"),
                providerGameId = "4242",
            ),
        ) as ScreenScraperMetadataSource.HeroMediaOutcome.Loaded

        assertEquals(2, urls.size)
        assertTrue("the verified id is still the first rung", urls[0].contains("gameid=4242"))
        assertTrue("the fallback rung is a name search", urls[1].contains("romnom=Sonic"))
        assertTrue("the fallback rung must not resend the exhausted id", !urls[1].contains("gameid="))
        assertTrue(outcome.result.medias.any { it.first == "fanart" })
    }

    @Test
    fun `a media-less verified id still refuses an unverifiable name rung`() = runBlocking {
        val (src, urls) = source { call -> if (call == 1) MEDIA_LESS_GAME else HERO_GAME }

        assertTrue(
            src.heroMediaOutcome(
                MetadataQuery(
                    platformId = platform,
                    candidateNames = listOf("A dangerous fuzzy fallback"),
                    providerGameId = "4242",
                ),
            ) is ScreenScraperMetadataSource.HeroMediaOutcome.Empty,
        )

        assertEquals(2, urls.size)
    }

    @Test
    fun `the verified-id fallback stays inside the bounded name budget`() = runBlocking {
        val (src, urls) = source { MEDIA_LESS_GAME }

        src.heroMediaOutcome(
            MetadataQuery(
                platformId = platform,
                candidateNames = listOf("One", "Two", "Three", "Four", "Five"),
                providerGameId = "4242",
            ),
        )

        // One exact id rung + the primary name + MAX_NAME_CANDIDATES, exactly as the art ladder budgets.
        assertEquals(4, urls.size)
    }

    @Test
    fun `cartridge art and Hero Motion reach a second record when the verified id has neither`() = runBlocking {
        val cartridge = """
            {"response":{"jeu":{"id":"77","nom":"Sonic","systeme":{"id":"23"},"medias":[
              {"type":"support-2d","url":"https://www.screenscraper.fr/front.png"}
            ]}}}
        """.trimIndent()
        // HERO_GAME is the live shape that broke both lanes: a verified identity rich in stills and
        // video, but with no cartridge scan at all.
        val (physicalSource, physicalUrls) = source { call -> if (call == 1) HERO_GAME else cartridge }
        val physical = physicalSource.physicalMediaOutcome(
            MetadataQuery(
                platformId = platform,
                candidateNames = listOf("Sonic"),
                providerGameId = "4242",
            ),
        ) as ScreenScraperMetadataSource.HeroMediaOutcome.Loaded

        assertEquals(2, physicalUrls.size)
        assertEquals(listOf("support-2d"), physical.result.medias.map { it.first })

        val (motionSource, motionUrls) = source { call -> if (call == 1) cartridge else HERO_GAME }
        val motion = motionSource.videoMediaOutcome(
            MetadataQuery(
                platformId = platform,
                candidateNames = listOf("Sonic"),
                providerGameId = "4242",
            ),
        ) as ScreenScraperMetadataSource.HeroMediaOutcome.Loaded

        assertEquals(2, motionUrls.size)
        assertTrue(motion.result.medias.any { it.first == "video" })
    }

    @Test
    fun `the automatic art lookup ladder keeps its single exact call`() = runBlocking {
        val (src, urls) = source { call -> if (call == 1) MEDIA_LESS_GAME else HERO_GAME }

        assertTrue(
            src.lookupOutcome(
                MetadataQuery(
                    platformId = platform,
                    candidateNames = listOf("Sonic"),
                    providerGameId = "4242",
                ),
            ) is ScreenScraperMetadataSource.LookupOutcome.Empty,
        )

        assertEquals("the art lane's quota spend is unchanged by the picker fallback", 1, urls.size)
    }

    @Test
    fun `explicit hero outcome reports gate closed before any network attempt`() = runBlocking {
        val (closed, urls) = source(gateOpen = false) { HERO_GAME }
        assertTrue(
            closed.heroMediaOutcome(
                MetadataQuery(platformId = platform, candidateNames = listOf("Sonic")),
            ) is ScreenScraperMetadataSource.HeroMediaOutcome.GateClosed,
        )
        assertTrue(urls.isEmpty())
    }

    @Test
    fun `an unmapped platform is a verified empty capability not a transport failure`() = runBlocking {
        val (src, urls) = source { HERO_GAME }

        assertTrue(
            src.heroMediaOutcome(
                MetadataQuery(platformId = PlatformId("not-mapped"), candidateNames = listOf("Game")),
            ) is ScreenScraperMetadataSource.HeroMediaOutcome.Empty,
        )
        assertTrue(urls.isEmpty())
    }

    @Test
    fun `explicit hero outcome distinguishes provider silence from a verified empty answer`() = runBlocking {
        val (unreachable, _) = source { null }
        assertTrue(
            unreachable.heroMediaOutcome(
                MetadataQuery(platformId = platform, candidateNames = listOf("Sonic")),
            ) is ScreenScraperMetadataSource.HeroMediaOutcome.Unreachable,
        )

        val (empty, _) = source { MEDIA_LESS_GAME }
        assertTrue(
            empty.heroMediaOutcome(
                MetadataQuery(platformId = platform, candidateNames = listOf("Sonic")),
            ) is ScreenScraperMetadataSource.HeroMediaOutcome.Empty,
        )
    }

    @Test
    fun `explicit hero outcome keeps provider failure distinct from verified empty`() = runBlocking {
        val (failed, _) = source { "API fermé pour les non membres" }

        assertTrue(
            failed.heroMediaOutcome(
                MetadataQuery(platformId = platform, providerGameId = "4242"),
            ) is ScreenScraperMetadataSource.HeroMediaOutcome.Failed,
        )
    }

    @Test
    fun `a verified but media-less game keeps walking the ladder`() = runBlocking {
        val (src, urls) = source { call ->
            if (call == 1) MEDIA_LESS_GAME.replace("\"Sonic\"", "\"First\"")
            else HERO_GAME.replace("\"Sonic\"", "\"Second\"")
        }

        val result = src.heroMedia(
            MetadataQuery(
                platformId = platform,
                rawBaseName = "raw",
                candidateNames = listOf("First", "Second"),
            ),
        )

        assertEquals(2, urls.size)
        assertEquals(2, result?.medias?.size)
    }

    @Test
    fun `non-https media are dropped rather than handed to the downloader`() = runBlocking {
        val (src, _) = source { MIXED_SCHEME_GAME }

        val result = src.heroMedia(MetadataQuery(platformId = platform, rawBaseName = "Sonic"))

        assertEquals(
            listOf("fanart" to "https://www.screenscraper.fr/safe.png"),
            result?.medias,
        )
    }

    @Test
    fun `https media on a lookalike host are dropped at the guarded seam`() = runBlocking {
        val (src, _) = source { LOOKALIKE_HOST_GAME }

        assertNull(src.heroMedia(MetadataQuery(platformId = platform, rawBaseName = "Sonic")))
    }

    @Test
    fun `physical media filters before its cap so screenshots cannot starve cartridge art`() = runBlocking {
        val medias = buildList {
            repeat(45) { index ->
                add("""{"type":"ss","url":"https://www.screenscraper.fr/shot-$index.png"}""")
            }
            add("""{"type":"support-3d","url":"https://www.screenscraper.fr/case.png"}""")
            add("""{"type":"support-2d","url":"https://www.screenscraper.fr/front.png"}""")
        }.joinToString(",")
        val body = """{"response":{"jeu":{"id":"4242","nom":"Sonic","systeme":{"id":"23"},"medias":[$medias]}}}"""
        val (src, _) = source { body }

        val outcome = src.physicalMediaOutcome(
            MetadataQuery(platformId = platform, rawBaseName = "Sonic"),
        ) as ScreenScraperMetadataSource.HeroMediaOutcome.Loaded

        assertEquals(
            listOf("support-3d", "support-2d"),
            outcome.result.medias.map { it.first },
        )
    }

    //  Ground truth: probing ScreenScraper for Resident Evil 5 (Xbox 360) returned 21 media types
    // and zero support-* entries, but did carry box-texture -- this is the accepted-types filter
    // (PHYSICAL_MEDIA_TYPES) that gates what even reaches the explicit physical-media picker's fetch;
    // without box-texture admitted here, ranking downstream never sees it regardless of priority order.
    @Test
    fun `physical media outcome admits box-texture when the provider has no support scan at all`() = runBlocking {
        val body = """
            {"response":{"jeu":{"id":"4242","nom":"Sonic","systeme":{"id":"23"},"medias":[
              {"type":"box-texture","url":"https://www.screenscraper.fr/boxtex.png"},
              {"type":"fanart","url":"https://www.screenscraper.fr/fan.png"}
            ]}}}
        """.trimIndent()
        val (src, _) = source { body }

        val outcome = src.physicalMediaOutcome(
            MetadataQuery(platformId = platform, rawBaseName = "Sonic"),
        ) as ScreenScraperMetadataSource.HeroMediaOutcome.Loaded

        assertEquals(listOf("box-texture"), outcome.result.medias.map { it.first })
    }

    /**
     * The explicit Hero picker admits box-texture and 2D/3D box candidates for player selection.
     *
     * [STATIC_HERO_MEDIA_TYPES] is the accepted-types filter that gates what even reaches the Hero
     * picker's fetch. Without the box family admitted HERE, `CustomHeroScreenScraperTypes` can rank them
     * all it likes and the picker will still never see one -- the same lesson the box-texture test
     * directly above records for the physical picker earlier.
     */
    /**
     * The Cover picker offers the box family, and `box-2D-side` is the one
     * deliberate exclusion.
     *
     * The spine is not a claim problem — it is a PROPORTION problem. A spine is a long thin strip and
     * cannot fill a portrait cover lane without being stretched or letterboxed into nonsense. Pinned here
     * so the exclusion reads as a decision rather than an oversight, which is exactly how the three types
     * that reach nothing in this product got there in the first place.
     */
    @Test
    fun `cover media outcome admits the box family and excludes the spine`() = runBlocking {
        val body = """
            {"response":{"jeu":{"id":"4242","nom":"Sonic","systeme":{"id":"23"},"medias":[
              {"type":"box-2D","url":"https://www.screenscraper.fr/b2.png"},
              {"type":"box-3D","url":"https://www.screenscraper.fr/b3.png"},
              {"type":"box-texture","url":"https://www.screenscraper.fr/btex.png"},
              {"type":"box-2D-back","url":"https://www.screenscraper.fr/bback.png"},
              {"type":"box-scan","url":"https://www.screenscraper.fr/bscan.png"},
              {"type":"box-2D-side","url":"https://www.screenscraper.fr/bside.png"},
              {"type":"support-2D","url":"https://www.screenscraper.fr/disc.png"}
            ]}}}
        """.trimIndent()
        val (src, _) = source { body }

        val outcome = src.coverArtOutcome(
            MetadataQuery(platformId = platform, rawBaseName = "Sonic"),
        ) as ScreenScraperMetadataSource.HeroMediaOutcome.Loaded

        val types = outcome.result.medias.map { it.first }
        // Widened to include physical-media scans. This asserted "a disc scan is not a cover" and excluded support-*.
        // That was right while "Cover art" and "Cartridge Hero" were two separate rows. They were replaced
        // with two STATE rows, so this picker became the only route to EITHER vocabulary -- withholding
        // the physical article here would have silently removed the ability to choose cartridge art.
        assertEquals(
            listOf("box-2D", "box-3D", "box-texture", "box-2D-back", "box-scan", "support-2D"),
            types,
        )
        assertTrue("the spine is still excluded, on geometry", "box-2D-side" !in types)
    }

    @Test
    fun `hero media outcome admits the box family so the picker can offer it`() = runBlocking {
        val body = """
            {"response":{"jeu":{"id":"4242","nom":"Sonic","systeme":{"id":"23"},"medias":[
              {"type":"box-texture","url":"https://www.screenscraper.fr/boxtex.png"},
              {"type":"box-3D","url":"https://www.screenscraper.fr/box3d.png"},
              {"type":"box-2D","url":"https://www.screenscraper.fr/box2d.png"},
              {"type":"manuel","url":"https://www.screenscraper.fr/manual.pdf"}
            ]}}}
        """.trimIndent()
        val (src, _) = source { body }

        val outcome = src.heroMediaOutcome(
            MetadataQuery(platformId = platform, rawBaseName = "Sonic"),
        ) as ScreenScraperMetadataSource.HeroMediaOutcome.Loaded

        assertEquals(
            listOf("box-texture", "box-3D", "box-2D"),
            outcome.result.medias.map { it.first },
        )
    }

    @Test
    fun `video media filters before its cap so screenshots cannot starve Hero Motion`() = runBlocking {
        val medias = buildList {
            repeat(45) { index ->
                add("""{"type":"ss","url":"https://www.screenscraper.fr/shot-$index.png"}""")
            }
            add("""{"type":"video-normalized","url":"https://www.screenscraper.fr/clip-small.mp4"}""")
            add("""{"type":"video","url":"https://www.screenscraper.fr/clip.mp4"}""")
        }.joinToString(",")
        val body = """{"response":{"jeu":{"id":"4242","nom":"Sonic","systeme":{"id":"23"},"medias":[$medias]}}}"""
        val (src, _) = source { body }

        val outcome = src.videoMediaOutcome(
            MetadataQuery(platformId = platform, rawBaseName = "Sonic"),
        ) as ScreenScraperMetadataSource.HeroMediaOutcome.Loaded

        assertEquals(
            listOf("video-normalized", "video", "ss"),
            outcome.result.medias.map { it.first },
        )
    }

    @Test
    fun `a poster without video is an empty Hero Motion result`() = runBlocking {
        val body = """
            {"response":{"jeu":{"id":"4242","nom":"Sonic","systeme":{"id":"23"},"medias":[
              {"type":"ss","url":"https://www.screenscraper.fr/shot.png"}
            ]}}}
        """.trimIndent()
        val (src, _) = source { body }

        assertTrue(
            src.videoMediaOutcome(
                MetadataQuery(platformId = platform, rawBaseName = "Sonic"),
            ) is ScreenScraperMetadataSource.HeroMediaOutcome.Empty,
        )
    }

    @Test
    fun `hero media filters before cap so fanart survives forty non hero entries`() = runBlocking {
        val medias = buildList {
            repeat(45) { index ->
                add("""{"type":"box-2d","url":"https://www.screenscraper.fr/box-$index.png"}""")
            }
            add("""{"type":"fanart","url":"https://www.screenscraper.fr/fan.png"}""")
        }.joinToString(",")
        val body = """{"response":{"jeu":{"id":"4242","nom":"Sonic","systeme":{"id":"23"},"medias":[$medias]}}}"""
        val (src, _) = source { body }

        val outcome = src.heroMediaOutcome(
            MetadataQuery(platformId = platform, rawBaseName = "Sonic"),
        ) as ScreenScraperMetadataSource.HeroMediaOutcome.Loaded

        // Before 2026-08-23 box-2D was not an accepted hero type at all, so "survives" meant "is the only
        // survivor". It is accepted now, as a demoted picker fallback, so the invariant is restated in the
        // only form that still means anything: the one real backdrop LEADS, and it is present at all.
        //
        // This is the pin that caught the defect in the widening. Admitting the box family without
        // demoting it before the cap put all 45 box scans in the bounded window and dropped the fanart
        // entirely -- `assertEquals("fanart", ...first().first)` failed with "box-2d". The demotion in
        // ScreenScraperMetadataSource.presentationMediaOutcome is what makes this green.
        assertEquals("fanart", outcome.result.medias.first().first)
        assertEquals(listOf("fanart", "box-2d"), outcome.result.medias.map { it.first }.distinct())
        assertEquals(40, outcome.result.medias.size)
    }

    @Test
    fun `hero ladder continues when first identity has no usable hero media`() = runBlocking {
        val noHero = MEDIA_LESS_GAME.replace("\"Sonic\"", "\"First\"")
        val withHero = HERO_GAME.replace("\"Sonic\"", "\"Second\"")
        val (src, urls) = source { call ->
            if (call == 1) noHero else withHero
        }

        val outcome = src.heroMediaOutcome(
            MetadataQuery(
                platformId = platform,
                rawBaseName = "raw",
                candidateNames = listOf("First", "Second"),
            ),
        ) as ScreenScraperMetadataSource.HeroMediaOutcome.Loaded

        assertEquals(2, urls.size)
        assertEquals("Second", outcome.result.canonicalTitle)
        assertTrue(outcome.result.medias.any { it.first == "fanart" })
    }

    @Test
    fun `bounded body reader rejects an oversized response`() {
        assertEquals(
            "small",
            readScreenScraperUtf8Bounded(ByteArrayInputStream("small".toByteArray()), 5),
        )
        assertNull(readScreenScraperUtf8Bounded(ByteArrayInputStream("too-large".toByteArray()), 5))
    }

    private companion object {
        const val HERO_GAME = """
        {"response":{"jeu":{"id":"4242","nom":"Sonic","systeme":{"id":"23"},"medias":[
          {"type":"fanart","region":"wor","format":"png","url":"https://www.screenscraper.fr/fan.png"},
          {"type":"ss","region":"wor","format":"png","url":"https://www.screenscraper.fr/shot.png"},
          {"type":"video","region":"wor","format":"mp4","url":"https://www.screenscraper.fr/clip.mp4"}
        ]}}}
        """

        const val WRONG_SYSTEM_HERO = """
        {"response":{"jeu":{"id":"666","nom":"Wrong Game","systeme":{"id":"12"},"medias":[
          {"type":"fanart","region":"wor","format":"png","url":"https://www.screenscraper.fr/wrong-fan.png"}
        ]}}}
        """

        const val MEDIA_LESS_GAME = """
        {"response":{"jeu":{"id":"4242","nom":"Sonic","systeme":{"id":"23"},"medias":[]}}}
        """

        const val MIXED_SCHEME_GAME = """
        {"response":{"jeu":{"id":"4242","nom":"Sonic","systeme":{"id":"23"},"medias":[
          {"type":"fanart","region":"wor","format":"png","url":"https://www.screenscraper.fr/safe.png"},
          {"type":"ss","region":"wor","format":"png","url":"http://www.screenscraper.fr/insecure.png"}
        ]}}}
        """

        const val LOOKALIKE_HOST_GAME = """
        {"response":{"jeu":{"id":"4242","nom":"Sonic","systeme":{"id":"23"},"medias":[
          {"type":"fanart","region":"wor","format":"png","url":"https://evil-screenscraper.fr/fan.png"}
        ]}}}
        """
    }
}

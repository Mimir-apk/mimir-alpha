package com.gruning.mimir.rewrite.core.metadata.online

import com.gruning.mimir.rewrite.core.metadata.ContentHash
import com.gruning.mimir.rewrite.core.metadata.MetadataQuery
import com.gruning.mimir.rewrite.core.model.PlatformId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * a transport failure (timeout/IO — a `null` body) is retried at the source with a
 * short backoff; a body the provider actually returned is never re-sent. `runTest`'s virtual clock skips
 * the backoff `delay()` so this test is instant despite pinning the exact backoff spent.
 */
class ScreenScraperTransientRetryTest {
    private val dev = DevAuth(devId = "D", devPassword = "P", softName = "MimirApk")

    /** Same shape as ScreenScraperArt02LadderTest.RIGHT_SYSTEM_GAME, whose companion is private. */
    private val rightSystemGame = """
        {"response":{"jeu":{"id":"4242","nom":"Sonic","systeme":{"id":"23"},"medias":[
          {"type":"box-2D","region":"wor","format":"png","url":"https://www.screenscraper.fr/right-box.png"}
        ]}}}
    """

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a transport failure on the only rung is retried after a backoff and the record lands`() = runTest {
        val urls = mutableListOf<String>()
        val source = ScreenScraperMetadataSource(
            gateOpen = { true },
            devAuth = { dev },
            httpGet = { url -> urls += url; if (urls.size == 1) null else rightSystemGame },
        )
        val outcome = source.lookupOutcome(
            MetadataQuery(
                platformId = PlatformId("dreamcast"), // systemeid 23, matches the fixture
                contentHash = ContentHash(crc32 = "deadbeef", sizeBytes = 42L),
                rawBaseName = "Sonic", // one rung only: no candidateNames
            ),
        )
        // Base (0ad25ff1): the null body ends the ladder (no retry) -> Unreachable. FAILS HERE.
        assertTrue(outcome is ScreenScraperMetadataSource.LookupOutcome.Loaded)
        assertEquals(listOf(urls[0], urls[0]), urls) // same url twice: a retry, not a ladder step
        assertEquals(500L, currentTime) // the fake clock advanced by exactly the first backoff
    }
}

package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Embedded art : the sizes it is decoded to, the cache that keeps it, and the track id that reaches the panel so it knows which art to ask for.
 * The decode itself is Android's (BitmapFactory, MediaMetadataRetriever) and is exercised in the app's Robolectric tests.
 */
class MusicEmbeddedArtTest {
    // ── the size it is decoded to ────────────────────────────────────────────────────────────────────

    @Test
    fun `a drawn size is covered by the smallest bucket that is at least as big, and the largest covers the rest`() {
        assertEquals(128, MusicArtSize.bucket(1))
        assertEquals(128, MusicArtSize.bucket(128))
        assertEquals(192, MusicArtSize.bucket(129))
        assertEquals(256, MusicArtSize.bucket(221)) // Main's 96 dp at the Thor's density
        assertEquals(640, MusicArtSize.bucket(609)) // the Bottom player's 264 dp
        assertEquals(640, MusicArtSize.bucket(5_000))
    }

    @Test
    fun `a huge cover is sampled down by a power of two that keeps it at least as big as wanted`() {
        // 4000 x 4000 is 64 MB decoded whole; sampled by 8 it is 500 x 500, still above 256.
        assertEquals(8, MusicArtSize.sampleSize(4_000, 4_000, 256))
        assertEquals(2, MusicArtSize.sampleSize(4_000, 1_000, 256))
        assertEquals("a small cover is not sampled", 1, MusicArtSize.sampleSize(500, 500, 256))
        assertEquals("exactly twice the target halves once", 2, MusicArtSize.sampleSize(512, 512, 256))
        assertEquals(1, MusicArtSize.sampleSize(511, 511, 256))
        assertEquals("no size means no sampling", 1, MusicArtSize.sampleSize(0, 0, 256))
        assertEquals(1, MusicArtSize.sampleSize(100, 100, 0))
        // Whatever the sample, the shorter side never falls below the target.
        for (side in listOf(257, 300, 511, 512, 1_000, 3_999, 4_000)) {
            val sample = MusicArtSize.sampleSize(side, side, 256)
            assertTrue("$side sampled by $sample", side / sample >= 256 || sample == 1)
        }
    }

    @Test
    fun `a decoded cover is scaled so its shorter side is the bucket, and never enlarged`() {
        assertEquals(256 to 256, MusicArtSize.scaledSize(500, 500, 256))
        assertEquals("the aspect is kept", 1_024 to 256, MusicArtSize.scaledSize(2_000, 500, 256))
        assertNull("already the size", MusicArtSize.scaledSize(256, 256, 256))
        assertNull("smaller than the bucket stays as it is", MusicArtSize.scaledSize(100, 80, 256))
        assertNull(MusicArtSize.scaledSize(0, 0, 256))
    }

    // ── the cache ────────────────────────────────────────────────────────────────────────────────────

    private fun lru(budget: Long) = BudgetLru<String, ByteArray>(budget) { it.size.toLong() }

    @Test
    fun `the cache keeps the most recently used pictures within its budget`() {
        val cache = lru(100)
        cache.put("a", ByteArray(40))
        cache.put("b", ByteArray(40))
        assertEquals(80, cache.usedBytes)
        cache["a"] // a is now the more recent
        cache.put("c", ByteArray(40))
        assertFalse("b was the least recently used", "b" in cache)
        assertTrue("a" in cache && "c" in cache)
        assertEquals(80, cache.usedBytes)
    }

    @Test
    fun `replacing a picture does not count it twice, and a picture over the whole budget still stands alone`() {
        val cache = lru(100)
        cache.put("a", ByteArray(60))
        cache.put("a", ByteArray(30))
        assertEquals(30, cache.usedBytes)
        cache.put("huge", ByteArray(500))
        assertEquals(1, cache.size)
        assertTrue("huge" in cache)
        cache.clear()
        assertEquals(0, cache.size)
        assertEquals(0, cache.usedBytes)
    }

    // ── the track id that reaches the panel ──────────────────────────────────────────────────────────

    @Test
    fun `the folder track on air carries its id to the panel, the bed carries none`() {
        val tracks = listOf(MusicTrack("", "a.mp3", "id-a"), MusicTrack("", "b.mp3", "id-b"))
        assertEquals("id-b", FrontendMusicPlan.Folder("tree", 1L, tracks).nowPlayingAt(1)?.trackId)
        assertNull(FrontendMusicPlan.Bed(MimirFrontendBed.Ambient).nowPlayingAt(0)?.trackId)

        val input = MusicControlInputs(
            yourMusicActive = true,
            yourMusicSounding = true,
            title = "B",
            pause = null,
            musicVolume = 0.5f,
            trackId = "id-b",
        )
        assertEquals("id-b", deriveMusicControlState(input).yours?.trackId)
        assertNull(deriveMusicControlState(input.copy(trackId = null)).yours?.trackId)
    }
}

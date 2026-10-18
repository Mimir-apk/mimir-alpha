package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.core.metadata.online.ScreenScraperMetadataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A cached "no art for this game" must not outlive the reason it was recorded.
 *
 * The concrete failure this pins: before 2026-08-10 no xbox/xbox360/ps3 platform had a ScreenScraper
 * system id, so every lookup for those titles returned without a request being made -- and the artwork
 * batch cached that as a per-game miss with a fourteen-day cooldown. Adding the ids made those games
 * fetchable, but the stale negative would have gone on suppressing the very lookup that now works,
 * for up to two weeks, on a real library.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class ArtNoMatchCoverageEpochTest {
    private val prefs
        get() = RuntimeEnvironment.getApplication()
            .getSharedPreferences("mimir_rewrite_art_no_matches", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        ArtNoMatchStore.detachForTest()
        prefs.edit().clear().commit()
        ArtNoMatchStore.currentEpochMillis = { 1_000_000L }
        ArtNoMatchStore.attach(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `a negative recorded under the current coverage survives attach`() {
        ArtNoMatchStore.record("game-a")
        assertTrue(ArtNoMatchStore.isCoolingDown("game-a"))

        // Re-attaching on a later launch must not throw away work that is still trustworthy;
        // otherwise every launch re-lookups the whole library and burns provider quota.
        ArtNoMatchStore.attach(RuntimeEnvironment.getApplication())

        assertTrue(ArtNoMatchStore.isCoolingDown("game-a"))
    }

    @Test
    fun `a coverage change drops negatives so newly scrapable platforms are retried`() {
        ArtNoMatchStore.record("game-a")
        ArtNoMatchStore.record("physical-media:game-b")
        assertTrue(ArtNoMatchStore.isCoolingDown("game-a"))
        assertTrue(ArtNoMatchStore.isCoolingDown("physical-media:game-b"))

        // Simulate the app booting with a build whose platform coverage differs from the one that
        // recorded these misses -- exactly what adding xbox/xbox360/ps3 did.
        prefs.edit().putString(ArtNoMatchStore.CoverageEpochKey, "stale-coverage").commit()
        ArtNoMatchStore.attach(RuntimeEnvironment.getApplication())

        assertFalse("game-level negative must not survive a coverage change", ArtNoMatchStore.isCoolingDown("game-a"))
        assertFalse(
            "support-art negative must not survive a coverage change",
            ArtNoMatchStore.isCoolingDown("physical-media:game-b"),
        )
    }

    // ── the launch sound's negative : "this game's clip gives no usable sound" ──────

    @Test
    fun `the sound's negative cools on its own key and does not cool the game or its images`() {
        ArtNoMatchStore.record("audio:game-c")

        assertTrue(ArtNoMatchStore.isCoolingDown("audio:game-c"))
        assertFalse("not the game-level negative", ArtNoMatchStore.isCoolingDown("game-c"))
        assertFalse(ArtNoMatchStore.isCoolingDown("cover:game-c"))
        assertFalse(ArtNoMatchStore.isCoolingDown("physical-media:game-c"))
        assertFalse(ArtNoMatchStore.isCoolingDown("clip:game-c"))

        ArtNoMatchStore.currentEpochMillis = { 1_000_000L + ArtNoMatchStore.CooldownMillis + 1L }
        assertFalse("and it expires after the same fourteen days as every other slot", ArtNoMatchStore.isCoolingDown("audio:game-c"))

        ArtNoMatchStore.currentEpochMillis = { 1_000_000L }
        ArtNoMatchStore.record("audio:game-c")
        ArtNoMatchStore.clear("audio:game-c")
        assertFalse("a written sound clears it", ArtNoMatchStore.isCoolingDown("audio:game-c"))
    }

    @Test
    fun `a coverage change drops the sound's negative like every other slot's`() {
        ArtNoMatchStore.record("audio:game-d")
        assertTrue(ArtNoMatchStore.isCoolingDown("audio:game-d"))

        prefs.edit().putString(ArtNoMatchStore.CoverageEpochKey, "stale-coverage").commit()
        ArtNoMatchStore.attach(RuntimeEnvironment.getApplication())

        assertFalse(ArtNoMatchStore.isCoolingDown("audio:game-d"))
    }

    /**
     * A rekeyed identity (two scans that turn out to be one game) carries every slot's negative across. The sound's prefix
     * has to be in the store's own list for that to hold, or a merged game would forget its silent clip and download it again.
     */
    @Test
    fun `a rekeyed identity carries the sound's negative to its new id`() {
        ArtNoMatchStore.record("audio:old-id")
        assertFalse(ArtNoMatchStore.isCoolingDown("audio:new-id"))

        assertTrue(ArtNoMatchStore.remapGameId(RuntimeEnvironment.getApplication(), "old-id", "new-id"))

        assertTrue(ArtNoMatchStore.isCoolingDown("audio:new-id"))
    }

    // ── the sound's own epoch (walk 32k): "not AAC" negatives recorded before the transcode are stale ────────────

    @Test
    fun `a stale audio epoch drops the sound's negatives on attach and nothing else`() {
        ArtNoMatchStore.record("audio:game-e")
        ArtNoMatchStore.record("audio:game-f")
        ArtNoMatchStore.clear("audio:game-g")
        ArtNoMatchStore.record("cover:game-e")
        ArtNoMatchStore.record("logo:game-e")
        ArtNoMatchStore.record("hero:game-e")
        ArtNoMatchStore.record("physical-media:game-e")
        ArtNoMatchStore.record("clip:game-e")
        ArtNoMatchStore.record("game-e")
        // An install that recorded them under the first build, which copied AAC and recorded "not AAC" as a miss.
        prefs.edit().putInt(ArtNoMatchStore.AudioEpochKey, 1).commit()

        ArtNoMatchStore.attach(RuntimeEnvironment.getApplication())

        assertFalse("the sound's negative is gone", ArtNoMatchStore.isCoolingDown("audio:game-e"))
        assertFalse(ArtNoMatchStore.isCoolingDown("audio:game-f"))
        assertTrue(
            "and so is its clear marker: no key of the sound is left behind",
            prefs.all.keys.none { it.startsWith("audio:") || it.startsWith("@cleared/audio:") },
        )
        for (survivor in listOf("cover:game-e", "logo:game-e", "hero:game-e", "physical-media:game-e", "clip:game-e", "game-e")) {
            assertTrue("$survivor is not the sound's and must survive", ArtNoMatchStore.isCoolingDown(survivor))
        }
        assertEquals(ArtNoMatchStore.AudioNegativeEpoch, prefs.getInt(ArtNoMatchStore.AudioEpochKey, -1))
    }

    @Test
    fun `a store that never stamped an audio epoch is the first build's and is dropped once`() {
        ArtNoMatchStore.record("audio:game-h")
        prefs.edit().remove(ArtNoMatchStore.AudioEpochKey).commit()

        ArtNoMatchStore.attach(RuntimeEnvironment.getApplication())

        assertFalse(ArtNoMatchStore.isCoolingDown("audio:game-h"))
        assertEquals(ArtNoMatchStore.AudioNegativeEpoch, prefs.getInt(ArtNoMatchStore.AudioEpochKey, -1))
    }

    @Test
    fun `a sound negative recorded under the current audio epoch survives every later attach`() {
        assertEquals(
            "a fresh store is stamped with the current epoch",
            ArtNoMatchStore.AudioNegativeEpoch,
            prefs.getInt(ArtNoMatchStore.AudioEpochKey, -1),
        )
        ArtNoMatchStore.record("audio:game-i")

        repeat(3) { ArtNoMatchStore.attach(RuntimeEnvironment.getApplication()) }

        assertTrue("the bump happens once, not on every launch", ArtNoMatchStore.isCoolingDown("audio:game-i"))
    }

    @Test
    fun `the audio epoch is a value of its own and the coverage epoch check is unchanged`() {
        assertEquals("the second meaning of the sound's negatives: any decodable track is transcoded", 2, ArtNoMatchStore.AudioNegativeEpoch)
        assertFalse(
            "it is its own key, so bumping it never touches the coverage fingerprint",
            ArtNoMatchStore.AudioEpochKey == ArtNoMatchStore.CoverageEpochKey,
        )
        assertEquals(
            ScreenScraperMetadataSource.coverageEpoch(),
            prefs.getString(ArtNoMatchStore.CoverageEpochKey, null),
        )
    }

    @Test
    fun `the epoch is stamped so the next launch knows which coverage recorded its misses`() {
        assertEquals(
            ScreenScraperMetadataSource.coverageEpoch(),
            prefs.getString(ArtNoMatchStore.CoverageEpochKey, null),
        )
    }

    @Test
    fun `the coverage epoch is stable across calls`() {
        // A fingerprint that changed on every read would clear the cache on every launch.
        assertEquals(ScreenScraperMetadataSource.coverageEpoch(), ScreenScraperMetadataSource.coverageEpoch())
        assertTrue(ScreenScraperMetadataSource.coverageEpoch().isNotBlank())
    }
}

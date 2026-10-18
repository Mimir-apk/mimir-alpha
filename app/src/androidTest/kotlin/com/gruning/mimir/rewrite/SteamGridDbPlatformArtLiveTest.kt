package com.gruning.mimir.rewrite

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gruning.mimir.rewrite.core.model.PlatformId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit connected proof for the three platform-name angles and their downloadable art. */
@RunWith(AndroidJUnit4::class)
class SteamGridDbPlatformArtLiveTest {
    @Test
    fun arcadeMameAnd3doCanonicalAnglesResolveLiveProviderEntries() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = SteamGridDbKeyStore.resolvedKey(context)
        assertTrue("SteamGridDB key is required for live platform proof", key.isNotBlank())

        listOf(
            Triple("arcade", "Arcade", "Arcade"),
            Triple("mame", "MAME", "MAME"),
            Triple("3do", "3DO", "3DO"),
        ).forEach { (platformId, label, proofName) ->
            val matches = SteamGridDbTileFetcher.platformSearchMatchCount(key, platformId, label)
            assertTrue("$proofName should resolve at least one SteamGridDB platform entry", matches > 0)
            var candidates = SteamGridDbTileFetcher.platformCandidatesFor(context, key, platformId, label)
            if (candidates.isEmpty()) {
                ConnectedArtStore.restore(context)
                ArtworkConnectController.attach(context)
                candidates = SteamGridDbTileFetcher.screenScraperPlatformCandidatesFor(
                    context,
                    ArtworkConnectController.source.systemImageInputs(PlatformId(platformId)),
                )
            }
            assertTrue("$proofName should return downloadable platform art", candidates.isNotEmpty())
            assertTrue(
                "$proofName should retain at least one local preview",
                candidates.any { candidate ->
                    candidate.previewPath?.let { path ->
                        java.io.File(path).let { it.isFile && it.length() > 0L }
                    } == true
                },
            )
            if (platformId == "3do") {
                val fallback = candidates.firstOrNull { it.normalizeSquare }
                assertTrue("3DO should use normalized ScreenScraper system media", fallback != null)
                val proofKey = "device-proof/platform-3do"
                try {
                    assertTrue(SteamGridDbTileFetcher.applyPick(context, proofKey, fallback!!, index = 0))
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(
                        SteamGridDbTileFetcher.tileIconFile(context, proofKey).absolutePath,
                        bounds,
                    )
                    assertEquals(1_024, bounds.outWidth)
                    assertEquals(1_024, bounds.outHeight)
                } finally {
                    SteamGridDbTileFetcher.clearPick(context, proofKey)
                }
            }
        }
    }
}

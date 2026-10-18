package com.gruning.mimir.rewrite

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Build
import com.gruning.mimir.rewrite.core.metadata.MetadataQuery
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.posture.MimirConnectedArtGate
import java.io.File
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Optional device proof. It runs only after the owner has explicitly connected ScreenScraper in Mimir.
 * Thor is deliberately excluded to protect player data; that exclusion is not a device-compatibility limit.
 */
@RunWith(AndroidJUnit4::class)
class ScreenScraperFlatArtLiveTest {

    @Test
    fun connectedMemberWheelArtFetchesCachesAndReadsBackThroughTheExistingGate() = runBlocking {
        assumeTrue("Thor is excluded from connected-art live tests for data protection", !isThor())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ConnectedArtStore.restore(context)
        ArtworkConnectController.attach(context)

        assumeTrue("Requires the owner's existing connected-art opt-in", ConnectedArtStore.optedIn.value)
        assumeTrue(
            "Requires a healthy encrypted member credential on this device",
            ArtworkCredentialStore.hasHealthyCredentials(context),
        )
        assumeTrue("Requires a configured build and the single connected-art gate", MimirConnectedArtGate.isOpen)

        val record = ArtworkConnectController.source.lookup(
            MetadataQuery(platformId = PlatformId("ds"), rawBaseName = "Final Fantasy IV"),
        )
        val flatArt = record?.flatArtUri

        assumeTrue("The authenticated response did not expose clear-logo art", flatArt != null)
        val flatArtUrl = requireNotNull(flatArt)
        val parsed = URL(flatArtUrl)
        assertTrue(parsed.protocol == "https")
        assertTrue(MimirConnectedArtGate.allowsRequestTo(parsed.host))

        // ScreenScraper's media URL needs to identify the selected clear-logo type before this can claim a
        // wheel proof. Opaque/fallback URLs are skipped rather than mislabeled as a clear logo.
        assumeTrue("Requires a response that identifies wheel or wheel-hd media", identifiesWheelMedia(flatArtUrl))

        val bytes = requireNotNull(
            ArtworkFetchController.downloadGated(flatArtUrl, ArtworkFetchController.MaxImageBytes),
        ) { "The selected wheel artwork should download within the image limit" }
        val fixture = File(context.cacheDir, "connected-art-live-${System.nanoTime()}")
        try {
            assertTrue(fixture.mkdirs())
            val rom = File(fixture, "Final Fantasy IV.nds")
            assertTrue(rom.createNewFile())
            assertTrue(
                ArtworkFetchController.writeManagedArt(
                    context = context,
                    sourceDocumentUri = null,
                    managedPath = rom.path,
                    romDisplayName = rom.name,
                    bytes = bytes,
                    nameSuffix = "-logo",
                ),
            )
            val cached = File(fixture, "media_art/Final Fantasy IV-logo.png")
            assertTrue("The managed clear-logo cache file should exist", cached.isFile)
            assertArrayEquals(bytes, cached.readBytes())
        } finally {
            fixture.deleteRecursively()
        }
    }

    private fun isThor(): Boolean =
        Build.DEVICE.equals("thor", ignoreCase = true) ||
            Build.MODEL.equals("thor", ignoreCase = true) ||
            Build.PRODUCT.equals("thor", ignoreCase = true)

    private fun identifiesWheelMedia(url: String): Boolean {
        val lower = url.lowercase()
        return Regex("""(?:[?&](?:media|type)=|/)(wheel(?:-hd)?)(?:[/?&#.]|$)""").containsMatchIn(lower)
    }
}

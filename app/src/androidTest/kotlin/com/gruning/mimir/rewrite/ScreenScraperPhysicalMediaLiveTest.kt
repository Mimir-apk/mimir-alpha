package com.gruning.mimir.rewrite

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gruning.mimir.rewrite.core.metadata.MetadataQuery
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.posture.MimirConnectedArtGate
import com.gruning.mimir.rewrite.core.resolver.library.LocalArtSource
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Focused RP5 proof that ScreenScraper support art is downloaded and read back as its own local slot. */
@RunWith(AndroidJUnit4::class)
class ScreenScraperPhysicalMediaLiveTest {
    @Test
    fun gbaSupportArtDownloadsToTheDistinctPhysicalMediaSlot() = runBlocking {
        assumeTrue("Thor is excluded from connected-art live tests", !isThor())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ConnectedArtStore.restore(context)
        ArtworkConnectController.attach(context)

        assumeTrue("Requires the owner's existing connected-art opt-in", ConnectedArtStore.optedIn.value)
        assumeTrue("Requires a healthy encrypted member credential", ArtworkCredentialStore.hasHealthyCredentials(context))
        assumeTrue("Requires the single connected-art gate", MimirConnectedArtGate.isOpen)

        val record = ArtworkConnectController.source.lookup(
            MetadataQuery(
                platformId = PlatformId.GBA,
                rawBaseName = "The Legend of Zelda - The Minish Cap",
                candidateNames = listOf("The Legend of Zelda : The Minish Cap"),
            ),
        )
        val remoteSupport = requireNotNull(record?.physicalMediaUri) {
            "The verified GBA response should expose support/cartridge art"
        }
        val bytes = requireNotNull(
            ArtworkFetchController.downloadGated(remoteSupport, ArtworkFetchController.MaxImageBytes),
        ) { "The selected support art should download within the image limit" }

        val fixture = File(context.cacheDir, "physical-media-live-${System.nanoTime()}")
        try {
            assertTrue(fixture.mkdirs())
            val rom = File(fixture, "The Legend of Zelda - The Minish Cap.gba")
            assertTrue(rom.createNewFile())
            assertTrue(
                ArtworkFetchController.writeManagedArt(
                    context = context,
                    sourceDocumentUri = null,
                    managedPath = rom.path,
                    romDisplayName = rom.name,
                    bytes = bytes,
                    nameSuffix = "-support",
                ),
            )
            val local = LocalArtSource(context).physicalMediaUriFor(null, rom.path)
            assertEquals(
                Uri.fromFile(File(fixture, "media_art/The Legend of Zelda - The Minish Cap-support.png")).toString(),
                local,
            )
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(Uri.parse(local).path, bounds)
            assertTrue("Support art must decode as a real image", bounds.outWidth > 0 && bounds.outHeight > 0)
        } finally {
            fixture.deleteRecursively()
        }
    }

    private fun isThor(): Boolean =
        Build.DEVICE.equals("thor", ignoreCase = true) ||
            Build.MODEL.equals("thor", ignoreCase = true) ||
            Build.PRODUCT.equals("thor", ignoreCase = true)
}

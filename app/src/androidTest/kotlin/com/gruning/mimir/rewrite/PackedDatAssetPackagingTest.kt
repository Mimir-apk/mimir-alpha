package com.gruning.mimir.rewrite

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gruning.mimir.rewrite.core.metadata.BundledDatManifest
import com.gruning.mimir.rewrite.core.metadata.BundledIdManifest
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device/API proof that AGP stored `.midx` assets in the form required by AssetManager.openFd(). */
@RunWith(AndroidJUnit4::class)
class PackedDatAssetPackagingTest {

    @Test
    fun everyBundledPackedDatIsStoredForFdMappingAndLegacyXmlBlobsAreAbsent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = AssetDatProvider(context)
        val packedAssets = BundledDatManifest.assets
        assertTrue("Packed DAT manifest must not be empty", packedAssets.isNotEmpty())

        packedAssets.forEach { packed ->
            assertTrue("Physical DAT asset must use .midx: ${packed.assetName}", packed.assetName.endsWith(".midx"))
            context.assets.openFd("dat/${packed.assetName}").use { descriptor ->
                assertEquals(
                    "Packed byte-length lock drifted for ${packed.assetName}",
                    packed.packedSizeBytes,
                    descriptor.length,
                )
            }

            val mapped = provider.openPacked(packed.assetName)
            assertNotNull("Provider could not map ${packed.assetName}", mapped)
            requireNotNull(mapped)
            assertTrue("Mapping must be read-only for ${packed.assetName}", mapped.isReadOnly)
            assertTrue("Mapping must remain direct for ${packed.assetName}", mapped.isDirect)
            assertEquals(0, mapped.position())
            assertTrue("Mapping must be non-empty for ${packed.assetName}", mapped.remaining() > 0)
        }

        val packagedNames = context.assets.list("dat").orEmpty().toList()
        val legacyXmlAssets = packagedNames.filter { it.endsWith(".datgz") }
        assertFalse("Legacy Logiqx blobs must not ship beside packed indexes: $legacyXmlAssets", legacyXmlAssets.isNotEmpty())
    }

    @Test
    fun compressedIdCatalogueStillUsesTheRawStreamContract() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val idCatalogue = BundledIdManifest.assets.first()

        AssetDatProvider(context).openRaw(idCatalogue.assetName).use { raw ->
            assertNotNull(raw)
            requireNotNull(raw)
            assertEquals(0x1f, raw.read())
            assertEquals(0x8b, raw.read())
        }
    }

    @Test
    fun filesystemOverrideIsMappedThroughTheSamePackedContract() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = byteArrayOf(1, 3, 3, 7)
        val override = File(AssetDatProvider.datDir(context), "android-test-${System.nanoTime()}.midx")
        try {
            override.writeBytes(expected)

            val mapped = AssetDatProvider(context).openPacked(override.name)

            assertNotNull(mapped)
            requireNotNull(mapped)
            val actual = ByteArray(mapped.remaining())
            mapped.duplicate().get(actual)
            assertArrayEquals(expected, actual)
            assertTrue(mapped.isReadOnly)
            assertTrue(mapped.isDirect)
        } finally {
            override.delete()
        }
    }
}

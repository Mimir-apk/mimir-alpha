package com.gruning.mimir.rewrite

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gruning.mimir.rewrite.core.metadata.BundledIdManifest
import com.gruning.mimir.rewrite.core.metadata.BundledIdSource
import com.gruning.mimir.rewrite.core.metadata.MameDependencyCatalog
import com.gruning.mimir.rewrite.core.metadata.MameDependencyManifest
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Android packaging/runtime proof for the exact keyed catalogs consumed by Athena's current adapters. */
@RunWith(AndroidJUnit4::class)
class AthenaA1PackagedCatalogDeviceTest {

    @Test
    fun packagedMameDependencyGraphPassesEveryRuntimeManifestLock() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val started = SystemClock.elapsedRealtimeNanos()
        val catalog = MameDependencyCatalog(AssetDatProvider(context))

        assertTrue(catalog.isAvailable)
        val payload =
            "{\"schema\":\"mimir.athena.a1d.mame-dependency.v1\"," +
                "\"release\":\"${MameDependencyManifest.MAME_RELEASE}\"," +
                "\"machines\":${MameDependencyManifest.MACHINES}," +
                "\"romRequirements\":${MameDependencyManifest.ROM_REQUIREMENTS}," +
                "\"diskRequirements\":${MameDependencyManifest.DISK_REQUIREMENTS}," +
                "\"elapsedNanos\":${SystemClock.elapsedRealtimeNanos() - started}}"
        Log.i("AthenaA1D", "ATHENA_A1D_MAME_DEPENDENCY_JSON=$payload")
        println("ATHENA_A1D_MAME_DEPENDENCY_JSON=$payload")
    }

    @Test
    fun everyPackagedKeyedCatalogPassesItsRuntimeLatchAndKnownLookup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = BundledIdSource(AssetDatProvider(context))
        val started = SystemClock.elapsedRealtimeNanos()

        BundledIdManifest.assets.forEach { asset ->
            assertEquals(
                "${asset.platformId.value} runtime catalog count",
                asset.expectedEntryCount,
                source.entryCount(asset.platformId.value),
            )
        }

        val arcade = source.resolve("arcade", "robotbwl.zip", null, 0)
        arcade as com.gruning.mimir.rewrite.core.metadata.BundledIdResolution.Identified
        assertEquals("Robot Bowl", arcade.match.record.canonicalTitle)
        assertEquals(
            com.gruning.mimir.rewrite.core.metadata.BundledIdBinding.FILENAME,
            arcade.match.binding,
        )
        val switch = source.resolve("switch", "Game [01007EF00011E000].nsp", null, 0)
        switch as com.gruning.mimir.rewrite.core.metadata.BundledIdResolution.Identified
        assertEquals("The Legend of Zelda: Breath of the Wild", switch.match.record.canonicalTitle)
        assertEquals(
            com.gruning.mimir.rewrite.core.metadata.BundledIdBinding.FILENAME,
            switch.match.binding,
        )
        val gameCubeHead = ByteArray(64).also {
            "GZLE01".toByteArray(Charsets.US_ASCII).copyInto(it)
        }
        val gameCube = source.resolve("gamecube", null, gameCubeHead, gameCubeHead.size)
        gameCube as com.gruning.mimir.rewrite.core.metadata.BundledIdResolution.Identified
        assertEquals("The Legend of Zelda: The Wind Waker", gameCube.match.record.canonicalTitle)
        assertEquals(
            com.gruning.mimir.rewrite.core.metadata.BundledIdBinding.EMBEDDED_HEADER,
            gameCube.match.binding,
        )
        val wiiHead = ByteArray(64).also {
            "RMCE01".toByteArray(Charsets.US_ASCII).copyInto(it)
        }
        val wii = source.resolve("wii", null, wiiHead, wiiHead.size)
        wii as com.gruning.mimir.rewrite.core.metadata.BundledIdResolution.Identified
        assertEquals("Mario Kart Wii", wii.match.record.canonicalTitle)
        assertEquals(
            com.gruning.mimir.rewrite.core.metadata.BundledIdBinding.EMBEDDED_HEADER,
            wii.match.binding,
        )
        val ps3 = source.resolve("ps3", "Game [BLUS30443].iso", null, 0)
        ps3 as com.gruning.mimir.rewrite.core.metadata.BundledIdResolution.Identified
        assertEquals("Demon's Souls", ps3.match.record.canonicalTitle)
        assertEquals(
            com.gruning.mimir.rewrite.core.metadata.BundledIdBinding.FILENAME,
            ps3.match.binding,
        )

        val elapsedNanos = SystemClock.elapsedRealtimeNanos() - started
        val declaredCompressedBytes = BundledIdManifest.assets.sumOf { it.compressedSizeBytes }
        val payload =
            "{\"schema\":\"mimir.athena.a1.packaged-catalog.v1\"," +
                "\"platformLoads\":${BundledIdManifest.assets.size}," +
                "\"declaredCompressedBytes\":$declaredCompressedBytes," +
                "\"elapsedNanos\":$elapsedNanos}"
        Log.i("AthenaA1", "ATHENA_A1_PACKAGED_CATALOG_JSON=$payload")
        println("ATHENA_A1_PACKAGED_CATALOG_JSON=$payload")
    }
}

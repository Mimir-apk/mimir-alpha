package com.gruning.mimir.rewrite

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnrichSavedColdRecoveryDeviceTest {
    @Test
    fun pendingJournalRestoresRendererSelectionAndCatalogBeforeReadsProceed() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cacheKey = "cold-recovery-${System.nanoTime()}"
        val source = File(context.cacheDir, "$cacheKey.png")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
        try {
            val saved = EnrichSavedStore.save(
                context = context,
                kind = EnrichMediaKind.TOME_PHYSICAL,
                cacheKey = cacheKey,
                source = EnrichSavedSource.UPLOAD,
                label = "Recovery fixture",
                fullAsset = source,
                activate = false,
            ) as EnrichSaveOutcome.Saved
            assertTrue(
                EnrichSavedStore.activate(
                    context,
                    EnrichMediaKind.TOME_PHYSICAL,
                    cacheKey,
                    saved.asset.assetId,
                ),
            )
            val selectedCatalog = EnrichSavedStore.load(
                context,
                EnrichMediaKind.TOME_PHYSICAL,
                cacheKey,
            )
            val selectedAuthority = GameIdentityMediaSelectionStore.canonicalRecord(
                context,
                EnrichMediaKind.TOME_PHYSICAL,
                cacheKey,
            )
            assertEquals(GameIdentityMediaSelection.SELECTED, selectedAuthority.selection)
            assertEquals(selectedAuthority.changedAtMillis, selectedCatalog.selectionChangedAtMillis)
            assertTrue(TomeCartridgeArtStore.activeFile(context, cacheKey).isFile)

            assertTrue(
                EnrichSavedStore.seedInterruptedRendererMutationForTest(
                    context,
                    EnrichMediaKind.TOME_PHYSICAL,
                    cacheKey,
                ),
            )
            assertTrue(
                EnrichSavedStore.recoverInterruptedRendererMutation(context),
            )

            val restored = EnrichSavedStore.load(context, EnrichMediaKind.TOME_PHYSICAL, cacheKey)
            assertEquals(saved.asset.assetId, restored.activeAssetId)
            assertEquals(GameIdentityMediaSelection.SELECTED, restored.selection)
            assertEquals(
                GameIdentityMediaSelection.SELECTED,
                GameIdentityMediaSelectionStore.state(context, EnrichMediaKind.TOME_PHYSICAL, cacheKey),
            )
            assertTrue(TomeCartridgeArtStore.activeFile(context, cacheKey).isFile)
            assertNotNull(TomeCartridgeArtStore.activeUri(context, cacheKey))

            assertTrue(
                EnrichSavedStore.activateAutomatic(
                    context,
                    EnrichMediaKind.TOME_PHYSICAL,
                    cacheKey,
                ),
            )
            val automaticCatalog = EnrichSavedStore.load(
                context,
                EnrichMediaKind.TOME_PHYSICAL,
                cacheKey,
            )
            val automaticAuthority = GameIdentityMediaSelectionStore.canonicalRecord(
                context,
                EnrichMediaKind.TOME_PHYSICAL,
                cacheKey,
            )
            assertEquals(GameIdentityMediaSelection.AUTOMATIC, automaticAuthority.selection)
            assertEquals(automaticAuthority.changedAtMillis, automaticCatalog.selectionChangedAtMillis)
            assertTrue(
                EnrichSavedStore.remove(
                    context,
                    EnrichMediaKind.TOME_PHYSICAL,
                    cacheKey,
                    saved.asset.assetId,
                ),
            )
        } finally {
            source.delete()
        }
    }
}

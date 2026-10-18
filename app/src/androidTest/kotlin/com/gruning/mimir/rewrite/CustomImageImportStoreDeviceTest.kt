package com.gruning.mimir.rewrite

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gruning.mimir.rewrite.core.model.GameId
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Connected proof that Android-decodable uploads are normalized, retained, and made active. */
@RunWith(AndroidJUnit4::class)
class CustomImageImportStoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cacheKey = "device-proof/icon"
    private val gameId = GameId("device-proof-hero")
    private val source = File(context.cacheDir, "custom-image-import-proof.webp")

    @After
    fun cleanUp() {
        source.delete()
        SteamGridDbTileFetcher.clearPick(context, cacheKey)
        CustomImageImportStore.retainedIconFile(context, cacheKey).delete()
        CustomHeroStore.clear(context, gameId)
        CustomImageImportStore.retainedHeroFile(context, gameId).delete()
    }

    @Test
    fun decodableWebpBecomesRetainedAndActiveIconAndHero() = runBlocking {
        val bitmap = Bitmap.createBitmap(2_048, 1_200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(32, 96, 160))
        }
        source.outputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, output))
        }
        bitmap.recycle()

        assertTrue(CustomImageImportStore.importIcon(context, Uri.fromFile(source), cacheKey))
        assertTrue(SteamGridDbTileFetcher.uploadedPickActive(context, cacheKey))
        assertTrue(CustomImageImportStore.retainedIconFile(context, cacheKey).isFile)
        BitmapFactory.decodeFile(SteamGridDbTileFetcher.tileIconFile(context, cacheKey).absolutePath).useBitmap {
            assertEquals(1_024, it.width)
            assertEquals(1_024, it.height)
        }

        assertTrue(CustomImageImportStore.importHero(context, Uri.fromFile(source), gameId))
        assertTrue(CustomHeroStore.uploadedHeroActive(context, gameId))
        assertTrue(CustomImageImportStore.retainedHeroFile(context, gameId).isFile)
        BitmapFactory.decodeFile(CustomHeroStore.assetFile(context, gameId, video = false).absolutePath).useBitmap {
            assertEquals(1_843, it.width)
            assertEquals(1_080, it.height)
        }
    }
}

private inline fun Bitmap?.useBitmap(block: (Bitmap) -> Unit) {
    checkNotNull(this).also(block).recycle()
}

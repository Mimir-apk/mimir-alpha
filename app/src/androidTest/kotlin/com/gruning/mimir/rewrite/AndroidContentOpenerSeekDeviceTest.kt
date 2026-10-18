package com.gruning.mimir.rewrite

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
import java.io.FileInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidContentOpenerSeekDeviceTest {

    @Test
    fun contentProviderDescriptorSupportsRandomAndNativeSeekWithoutCopying() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val bytes = ByteArray(64) { index -> (index * 7 + 3).toByte() }
        val uri = resolver.insert(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "mimir-a1c-seek-${System.nanoTime()}.bin")
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/MimirA1CProof")
                put(MediaStore.Downloads.IS_PENDING, 1)
            },
        )
        assertNotNull(uri)
        try {
            resolver.openOutputStream(requireNotNull(uri), "w")!!.use { it.write(bytes) }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            val opener = AndroidContentOpener(context)
            opener.openRandomAccess(uri.toString(), null)!!.use { content ->
                val observed = ByteArray(11)
                assertArrayEquals(
                    bytes.copyOfRange(17, 28),
                    observed.also { content.readAt(17, it) },
                )
            }
            opener.openNativeSeekablePath(uri.toString(), null)!!.use { nativePath ->
                val observed = FileInputStream(nativePath.path).use { stream ->
                    stream.skip(29)
                    stream.readNBytes(9)
                }
                assertArrayEquals(bytes.copyOfRange(29, 38), observed)
            }
        } finally {
            uri?.let { resolver.delete(it, null, null) }
        }
    }

    @Test
    fun existingGrantedLibraryDocumentSupportsSeekWhenPhysicalFixtureExists() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val game = MimirDatabaseFactory.get(context).gameDao().smallestHashablePrimary()
        assumeTrue("physical library fixture is not present on this device", game != null)
        val opener = AndroidContentOpener(context)
        val random = opener.openRandomAccess(game!!.sourceDocumentUri, game.managedPath)
        assertNotNull("existing library resident must expose random access", random)
        random!!.use { content ->
            val one = ByteArray(1)
            assumeTrue("existing library resident is empty", content.length > 0L)
            assertEquals(1, content.readAt(0, one))
        }
        val native = opener.openNativeSeekablePath(game.sourceDocumentUri, game.managedPath)
        assertNotNull("existing library resident must expose a native seek path", native)
        native!!.close()
    }
}

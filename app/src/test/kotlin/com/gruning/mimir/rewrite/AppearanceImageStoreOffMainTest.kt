package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.core.model.MimirMarkPlacement
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Appearance store's file work off the main thread (audit 2026-10-14, RC-29: S-24, S-25). A picker opens from the saved lists the stores
 * already hold, and every saved image used to cost it two stats (`available`) as it composed; the stats are taken where the list is READ, so
 * the picker's open, its tiles and the A / X handlers read a field. The suspend variants give a caller on the main thread the whole read, the
 * removal and the sidecar rewrite on IO.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class AppearanceImageStoreOffMainTest {

    private lateinit var context: Context
    private val id = "0123456789abcdef0123456789abcdef"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "appearance").deleteRecursively()
    }

    @After
    fun tearDown() {
        File(context.filesDir, "appearance").deleteRecursively()
    }

    private fun plant(kind: AppearanceImageKind, imageId: String = id, length: Int = 512): File {
        val root = AppearanceImageStore.root(context, kind).apply { mkdirs() }
        val file = File(root, "$imageId.${kind.fileExtension}").also { it.writeBytes(ByteArray(length) { 1 }) }
        File(root, "$imageId.json").writeText(
            JSONObject().put("width", 256).put("height", 256).put("p90", 0.3).put("addedAt", 7L).put("bytes", length.toLong()).toString(),
        )
        return file
    }

    @Test
    fun `a listed image answers available from the read, with no stat after it`() {
        val file = plant(AppearanceImageKind.MARK)
        val listed = AppearanceImageStore.list(context, AppearanceImageKind.MARK).single()
        assertEquals("the stats were taken where the list was read", true, listed.availableAtRead)
        assertTrue(listed.available)

        // The copy goes away AFTER the list was read: the picker's field does not touch the disk, so it still answers what the read saw.
        file.delete()
        assertTrue("reading `available` costs no stat", listed.available)
        assertFalse("while a stat taken now says the copy is gone", listed.wholeOnDisk())
        assertFalse("and the next read of the list says so", AppearanceImageStore.list(context, AppearanceImageKind.MARK).single().available)
    }

    @Test
    fun `a truncated copy is still told from a whole one at the read`() {
        val file = plant(AppearanceImageKind.BACKGROUND, length = 4_096)
        file.writeBytes(ByteArray(100) { 1 })
        val listed = AppearanceImageStore.find(context, AppearanceImageKind.BACKGROUND, id)!!
        assertEquals(false, listed.availableAtRead)
        assertFalse(listed.available)
    }

    @Test
    fun `an image built without a read still stats the file`() {
        val file = plant(AppearanceImageKind.MARK)
        val built = AppearanceSavedImage(AppearanceImageKind.MARK, id, file, 256, 256, 0.3f, 7L, bytes = 512L)
        assertNull(built.availableAtRead)
        assertTrue(built.available)
        file.delete()
        assertFalse("no read, so no remembered answer", built.available)
    }

    @Test
    fun `the suspend variants answer what the blocking ones do`() = runBlocking {
        plant(AppearanceImageKind.MARK)
        assertEquals(
            AppearanceImageStore.list(context, AppearanceImageKind.MARK),
            AppearanceImageStore.listOffMain(context, AppearanceImageKind.MARK),
        )

        assertTrue(AppearanceImageStore.updateMarkPlacementOffMain(context, id, MimirMarkPlacement(scale = 1.25f, offsetX = 2f, offsetY = -1f)))
        val moved = AppearanceImageStore.find(context, AppearanceImageKind.MARK, id)
        assertNotNull(moved)
        assertEquals(1.25f, moved!!.placement.scale, 0.0001f)

        assertTrue(AppearanceImageStore.removeOffMain(context, AppearanceImageKind.MARK, id))
        assertTrue(AppearanceImageStore.listOffMain(context, AppearanceImageKind.MARK).isEmpty())
    }

    @Test
    fun `the store reads on IO through the same four calls the pickers use`() {
        val source = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/AppearanceImageStore.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/AppearanceImageStore.kt"),
        ).first(File::exists).readText()
        for (name in listOf("listOffMain", "removeOffMain", "updateMarkPlacementOffMain")) {
            assertTrue("$name runs on the IO dispatcher", source.substringAfter("suspend fun $name(").substringBefore("\n\n").contains("withContext(Dispatchers.IO)"))
        }
        assertTrue(source.contains(").let { read -> read.copy(availableAtRead = read.wholeOnDisk()) }"))
    }
}

package com.gruning.mimir.rewrite.core.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import java.io.FileOutputStream
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * A first bake never reads the file under the synchronous-bake policy.
 *
 * Every stage `Crossfade` child, every theme-gallery card and every destination mount gets a FRESH swap, and a fresh swap
 * bakes synchronously on the UI thread -- which used to decode the player's 1-4 MB image right there whenever it was not
 * resident, at each D-pad move across the picker's tiles. The plan a UI-thread bake takes is [planSynchronousBake]: the
 * ground when its decoded copy is resident (or its raster already cached), otherwise the theme's Atmosphere with the image
 * pending, baked off the UI thread next.
 *
 * Robolectric's test thread IS the main thread, so "the calling thread" is the UI thread here: a decode the plan made
 * would be logged `MimirSceneGround: sync decode id=<id>` by [MimirSceneGroundImages.getOrDecode], and none is. The image
 * is then decoded where the app decodes it -- [MimirSceneGroundImages.prefetch]'s IO thread -- and becomes resident. That
 * the draw takes this plan is pinned as source in `MimirShelfBackgroundBakeWiringTest`; what a sweep looks like on a
 * device is the emulator's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirSceneGroundSyncBakeTest {

    private lateinit var dir: File
    private val ids = mutableListOf<String>()

    @Before
    fun setUp() {
        dir = File.createTempFile("ground", "dir").apply { delete(); mkdirs() }
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        ids.forEach(MimirSceneGroundImages::forget)
        dir.deleteRecursively()
    }

    /** A real, decodable JPEG on disk and the ground that names it, under an id no other test uses. */
    private fun ground(name: String): MimirSceneGround {
        val id = "sync-$name-${System.nanoTime()}".also(ids::add)
        val file = File(dir, "$id.jpg")
        val bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 40, 40)) }
        FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        return MimirSceneGround(id = id, path = file.absolutePath, width = 64, height = 36, p90 = 0.2f)
    }

    private fun key(ground: MimirSceneGround?) = MimirShelfBackgroundCacheKey(
        size = Size(64f, 36f),
        density = 1f,
        layoutDirection = LayoutDirection.Ltr,
        palette = MimirThemePalette.forId(MimirThemeId.DEFAULT),
        showAtmosphericClouds = true,
        ground = ground,
    )

    private fun syncDecodeLines(): List<String> =
        ShadowLog.getLogsForTag("MimirSceneGround").map { it.msg }.filter { "sync decode" in it }

    private fun awaitResident(id: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (MimirSceneGroundImages.get(id) == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertNotNull("the prefetch decoded $id off the calling thread", MimirSceneGroundImages.get(id))
    }

    @Test
    fun `a first bake whose image is not resident paints the Atmosphere and does not decode on the calling thread`() {
        val ground = ground("cold")
        assertNull("the copy is not resident", MimirSceneGroundImages.get(ground.id))

        val plan = planSynchronousBake(key(ground), rasterCached = { false })

        assertTrue("the image is pending", plan.groundPending)
        assertEquals("the raster is baked and cached under the Atmosphere's own key, never under the ground's", key(null), plan.key)
        assertNull(plan.groundImage)
        assertNull("nothing was decoded to make the plan", MimirSceneGroundImages.get(ground.id))
        assertEquals("and the calling thread never read the file", emptyList<String>(), syncDecodeLines())
    }

    @Test
    fun `the pending image decodes off the calling thread, then the next first bake paints it`() {
        val ground = ground("warm")
        assertTrue(planSynchronousBake(key(ground), rasterCached = { false }).groundPending)

        MimirSceneGroundImages.prefetch(listOf(ground))
        awaitResident(ground.id)

        val plan = planSynchronousBake(key(ground), rasterCached = { false })
        assertFalse(plan.groundPending)
        assertEquals("the requested key: the raster is the image's", key(ground), plan.key)
        assertNotNull("painted from the resident copy", plan.groundImage)
        assertEquals("the decode ran on the IO thread, not the main thread", emptyList<String>(), syncDecodeLines())
    }

    @Test
    fun `a raster that is already cached is painted as asked whether or not the copy is resident`() {
        val ground = ground("cached")
        val plan = planSynchronousBake(key(ground), rasterCached = { it == key(ground) })
        assertFalse("nothing to bake, so nothing to wait for", plan.groundPending)
        assertEquals(key(ground), plan.key)
        assertNull("no copy was needed, and none was decoded", MimirSceneGroundImages.get(ground.id))
        assertEquals(emptyList<String>(), syncDecodeLines())
    }

    @Test
    fun `a key without a ground is exactly the key of today`() {
        val plan = planSynchronousBake(key(null), rasterCached = { false })
        assertFalse(plan.groundPending)
        assertEquals(key(null), plan.key)
        assertNull(plan.groundImage)
    }

    @Test
    fun `a decode that does happen on the main thread is logged with an id and never a path`() {
        val ground = ground("loud")
        val decoded = MimirSceneGroundImages.getOrDecode(ground)
        assertNotNull("it still decodes: no frame is ever blank", decoded)
        val lines = syncDecodeLines()
        assertEquals(listOf("sync decode id=${ground.id}"), lines)
        ShadowLog.getLogs().forEach { item ->
            assertFalse("a log line carries an id, never a path: ${item.msg}", ground.path in item.msg || dir.absolutePath in item.msg)
        }
        assertEquals("the copy is resident afterwards", decoded, MimirSceneGroundImages.get(ground.id))
        MimirSceneGroundImages.getOrDecode(ground)
        assertEquals("a resident copy never logs again", 1, syncDecodeLines().size)
    }

    @Test
    fun `the decode cache holds every saved background, so the picker's own prefetch does not evict itself`() {
        assertTrue("six saved backgrounds, so six resident", MimirSceneGroundImages.MaxEntries >= 6)
        assertTrue("and never the raster budget's worth of RGB_565 copies", MimirSceneGroundImages.MaxEntries <= 7)
        val six = (1..6).map { ground("six$it") }
        MimirSceneGroundImages.prefetch(six)
        six.forEach { awaitResident(it.id) }
        six.forEach { assertNotNull("${it.id} is still resident", MimirSceneGroundImages.get(it.id)) }
        assertEquals(emptyList<String>(), syncDecodeLines())
    }
}

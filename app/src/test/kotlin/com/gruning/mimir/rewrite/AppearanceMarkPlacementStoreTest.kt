package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.core.companionnative.CompanionButtonMark
import com.gruning.mimir.rewrite.core.model.MimirMarkPlacement
import com.gruning.mimir.rewrite.core.ui.MimirAppearanceOverrides
import com.gruning.mimir.rewrite.core.ui.MimirButtonMark
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import java.io.File
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
 * Where the player put their own image on the Mimir button , kept per image in its sidecar beside width, height, p90,
 * addedAt and bytes: three optional fields `scale`, `offsetX` and `offsetY`. A sidecar without them (every image saved before this) is the
 * image centred at 100%, so nothing migrates; a bad or out-of-range value is held in range on read and never costs the image its place in the
 * saved list; saving writes the placement and commits the image through the one `setButtonMark` the tile's A uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class AppearanceMarkPlacementStoreTest {

    private lateinit var context: Context
    private val id = "0123456789abcdef0123456789abcdef"
    private val other = "fedcba9876543210fedcba9876543210"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("mimir_rewrite_appearance", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "appearance").deleteRecursively()
        MimirAppearanceOverrides.buttonMark = null
    }

    @After
    fun tearDown() {
        MimirAppearanceOverrides.buttonMark = null
        File(context.filesDir, "appearance").deleteRecursively()
    }

    private fun root(): File = AppearanceImageStore.root(context, AppearanceImageKind.MARK).apply { mkdirs() }

    /** A saved mark exactly as an import wrote it before placement existed: no scale, no offsets. */
    private fun plantOld(imageId: String, addedAt: Long = 1L): AppearanceSavedImage {
        File(root(), "$imageId.png").writeBytes(ByteArray(512) { 1 })
        File(root(), "$imageId.json").writeText(
            JSONObject().put("width", 256).put("height", 256).put("p90", 0.3).put("addedAt", addedAt).put("bytes", 512L).toString(),
        )
        return AppearanceImageStore.find(context, AppearanceImageKind.MARK, imageId)!!
    }

    private fun sidecar(imageId: String = id): JSONObject = JSONObject(File(root(), "$imageId.json").readText())

    private fun read(imageId: String = id): AppearanceSavedImage = AppearanceImageStore.find(context, AppearanceImageKind.MARK, imageId)!!

    // ---- reading ----------------------------------------------------------------------------------------------------------

    @Test
    fun `an old sidecar with none of the fields is centred at 100 percent and its mark says so`() {
        val saved = plantOld(id)
        assertEquals(MimirMarkPlacement.Default, saved.placement)
        assertEquals(MimirButtonMark(id = id, path = saved.path, scale = 1f, offsetX = 0f, offsetY = 0f), saved.toMark())
        assertEquals("an old sidecar's mark is the mark every build before this one made", MimirButtonMark(id, saved.path), saved.toMark())
        assertEquals("it is still in the saved list", listOf(id), AppearanceImageStore.list(context, AppearanceImageKind.MARK).map { it.id })
    }

    @Test
    fun `a placement round trips through the sidecar and the other facts are left as they were`() {
        plantOld(id, addedAt = 77L)
        val placement = MimirMarkPlacement(scale = 1.25f, offsetX = -3.5f, offsetY = 8f)

        assertTrue(AppearanceImageStore.updateMarkPlacement(context, id, placement))

        val read = read()
        assertEquals(placement, read.placement)
        assertEquals(256, read.width)
        assertEquals(256, read.height)
        assertEquals(0.3f, read.p90, 0.0001f)
        assertEquals(77L, read.addedAtMillis)
        assertEquals(512L, read.bytes)
        assertTrue("the copy still reads whole", read.available)
        assertEquals(placement, AppearanceImageStore.list(context, AppearanceImageKind.MARK).single().placement)
        // The mark the app commits carries the three numbers.
        assertEquals(MimirButtonMark(id, read.path, scale = 1.25f, offsetX = -3.5f, offsetY = 8f), read.toMark())
        // Written aside and moved over: nothing is left beside the sidecar.
        assertEquals(setOf("$id.png", "$id.json"), root().list()!!.toSet())
    }

    @Test
    fun `the sidecar reads as plain numbers, and a placement that sanitizes to the default removes them`() {
        plantOld(id)
        AppearanceImageStore.updateMarkPlacement(context, id, MimirMarkPlacement(1.05f, 0.5f, -0.5f))
        val written = sidecar()
        assertEquals(1.05, written.getDouble("scale"), 0.0)
        assertEquals(0.5, written.getDouble("offsetX"), 0.0)
        assertEquals(-0.5, written.getDouble("offsetY"), 0.0)

        // Reset: back to what an import wrote.
        assertTrue(AppearanceImageStore.updateMarkPlacement(context, id, MimirMarkPlacement.Default))
        val reset = sidecar()
        assertFalse(reset.has("scale") || reset.has("offsetX") || reset.has("offsetY"))
        assertEquals(MimirMarkPlacement.Default, read().placement)
        // A placement of nothing but bad numbers is the default too.
        assertTrue(AppearanceImageStore.updateMarkPlacement(context, id, MimirMarkPlacement(Float.NaN, Float.NaN, Float.NaN)))
        assertFalse(sidecar().has("scale"))
    }

    @Test
    fun `a bad or out of range value is held in range on read and the image stays in the saved list`() {
        plantOld(id)
        fun withSidecar(edit: JSONObject.() -> Unit): AppearanceSavedImage {
            val json = sidecar()
            json.edit()
            File(root(), "$id.json").writeText(json.toString())
            return AppearanceImageStore.list(context, AppearanceImageKind.MARK).single { it.id == id }
        }
        // Out of range: clamped to the edge, never rejected, never a crash.
        assertEquals(MimirMarkPlacement(1.6f, 12f, -12f), withSidecar { put("scale", 9.0).put("offsetX", 1.0e30).put("offsetY", -99.0) }.placement)
        assertEquals(MimirMarkPlacement(0.6f, -12f, 12f), withSidecar { put("scale", -4.0).put("offsetX", -1.0e300).put("offsetY", 50) }.placement)
        // Not a number: the field's default, the others kept.
        assertEquals(MimirMarkPlacement(1f, 2f, 0f), withSidecar { put("scale", "abc").put("offsetX", 2.0).put("offsetY", "NaN") }.placement)
        assertEquals(MimirMarkPlacement(1f, 0f, 3f), withSidecar { put("scale", JSONObject.NULL).put("offsetX", true).put("offsetY", 3.0) }.placement)
        assertEquals(MimirMarkPlacement(1.3f, 0f, 0f), withSidecar { put("scale", "1.3").remove("offsetX"); remove("offsetY") }.placement)
        // A sidecar that records nothing it understands is still the image, centred.
        assertEquals(MimirMarkPlacement.Default, withSidecar { put("scale", JSONObject()).put("offsetX", org.json.JSONArray()).put("offsetY", "") }.placement)
        assertNotNull(AppearanceImageStore.find(context, AppearanceImageKind.MARK, id))
    }

    @Test
    fun `writing clamps what it is given, and refuses an id that is not an image`() {
        plantOld(id)
        assertTrue(AppearanceImageStore.updateMarkPlacement(context, id, MimirMarkPlacement(5f, 99f, -99f)))
        assertEquals(MimirMarkPlacement(1.6f, 12f, -12f), read().placement)

        assertFalse("not a hash", AppearanceImageStore.updateMarkPlacement(context, "../escape", MimirMarkPlacement(1.2f, 1f, 1f)))
        assertFalse("no sidecar", AppearanceImageStore.updateMarkPlacement(context, other, MimirMarkPlacement(1.2f, 1f, 1f)))
        assertFalse(File(root(), "$other.json").exists())
        File(root(), "$id.json").writeText("not json")
        assertFalse("a sidecar that does not read is not rewritten", AppearanceImageStore.updateMarkPlacement(context, id, MimirMarkPlacement(1.2f, 1f, 1f)))
        assertEquals("not json", File(root(), "$id.json").readText())
    }

    @Test
    fun `removing an image takes a sidecar aside file a dead process left behind, and nothing else`() {
        plantOld(id)
        plantOld(other)
        val strayPart = File(root(), "$id.json.part").apply { writeText("{") }
        val otherPart = File(root(), "$other.json.part").apply { writeText("{") }

        assertTrue("nothing of the removed image remains", AppearanceImageStore.remove(context, AppearanceImageKind.MARK, id))
        assertFalse(File(root(), "$id.png").exists())
        assertFalse(File(root(), "$id.json").exists())
        assertFalse("the half-written sidecar went with it", strayPart.exists())
        assertTrue("another image's aside file is its own", otherPart.exists())
        assertTrue(File(root(), "$other.json").isFile)
    }

    @Test
    fun `a placement write leaves no aside file behind`() {
        plantOld(id)
        assertTrue(AppearanceImageStore.updateMarkPlacement(context, id, MimirMarkPlacement(1.2f, 1f, 0f)))
        assertFalse(File(root(), "$id.json.part").exists())
    }

    @Test
    fun `each image keeps its own placement`() {
        plantOld(id, addedAt = 1L)
        plantOld(other, addedAt = 2L)
        AppearanceImageStore.updateMarkPlacement(context, id, MimirMarkPlacement(1.4f, 6f, 0f))
        AppearanceImageStore.updateMarkPlacement(context, other, MimirMarkPlacement(0.8f, 0f, -4.5f))

        val listed = AppearanceImageStore.list(context, AppearanceImageKind.MARK)
        assertEquals(listOf(id, other), listed.map { it.id })
        assertEquals(MimirMarkPlacement(1.4f, 6f, 0f), listed[0].placement)
        assertEquals(MimirMarkPlacement(0.8f, 0f, -4.5f), listed[1].placement)
    }

    // ---- saving and using ----------------------------------------------------------------------------------------------------

    @Test
    fun `saving writes the placement and commits the image the way A on its tile does, one revision tick`() {
        val saved = plantOld(id)
        val before = AppearanceOverrideStore.revision.value
        val placement = MimirMarkPlacement(1.15f, -2f, 4.5f)

        val committed = AppearanceOverrideStore.saveMarkPlacementAndUse(context, id, placement)

        assertEquals(MimirButtonMark(id, saved.path, scale = 1.15f, offsetX = -2f, offsetY = 4.5f), committed)
        assertEquals(committed, MimirAppearanceOverrides.buttonMark)
        assertEquals("one tick, so the live ᛗ repaints once", before + 1, AppearanceOverrideStore.revision.value)
        assertEquals("the preference is the same key and value A on a tile writes", "$id|STRUCK", context.getSharedPreferences("mimir_rewrite_appearance", Context.MODE_PRIVATE).getString("appearanceButtonMark", null))
        assertEquals(placement, read().placement)
        assertEquals("the saved list was re-read, so the tile brings the new look", placement, AppearanceOverrideStore.savedMarks.value.single().placement)

        // A restart: load reads the choice and its placement back from the key and the sidecar.
        MimirAppearanceOverrides.buttonMark = null
        AppearanceOverrideStore.load(context)
        assertEquals(committed, MimirAppearanceOverrides.buttonMark)
    }

    @Test
    fun `a save that cannot be written commits nothing and ticks nothing`() {
        plantOld(id)
        val before = AppearanceOverrideStore.revision.value
        assertNull(AppearanceOverrideStore.saveMarkPlacementAndUse(context, other, MimirMarkPlacement(1.2f, 1f, 1f)))
        assertNull(MimirAppearanceOverrides.buttonMark)
        assertEquals(before, AppearanceOverrideStore.revision.value)
        assertFalse(context.getSharedPreferences("mimir_rewrite_appearance", Context.MODE_PRIVATE).contains("appearanceButtonMark"))
    }

    // ---- the overlay's mark ----------------------------------------------------------------------------------------------------

    @Test
    fun `the overlay's mark carries the placement, sanitized, and a changed placement alone is a changed mark`() {
        val plain = MimirButtonMark(id, "/m/a.png")
        val placed = plain.copy(scale = 1.25f, offsetX = 3f, offsetY = -2f)
        assertEquals(CompanionButtonMark.Image(id, "/m/a.png"), plain.toCompanionButtonMark())
        assertEquals(CompanionButtonMark.Image(id, "/m/a.png", MimirMarkPlacement(1.25f, 3f, -2f)), placed.toCompanionButtonMark())
        assertTrue("a placement change alone rebuilds an attached button", plain.toCompanionButtonMark() != placed.toCompanionButtonMark())
        // Whatever reaches the adapter leaves it a finite number in range.
        val wild = plain.copy(scale = Float.NaN, offsetX = 1_000f, offsetY = Float.NEGATIVE_INFINITY)
        assertEquals(MimirMarkPlacement(1f, 12f, 0f), (wild.toCompanionButtonMark() as CompanionButtonMark.Image).placement)
        // And it rides in the one theme the overlay is pushed.
        assertEquals(placed.toCompanionButtonMark(), companionOverlayTheme(MimirThemeId.DEFAULT, buttonMark = placed).mark)
        assertEquals(CompanionButtonMark.Rune, companionOverlayTheme(MimirThemeId.DEFAULT, buttonMark = null).mark)
    }
}

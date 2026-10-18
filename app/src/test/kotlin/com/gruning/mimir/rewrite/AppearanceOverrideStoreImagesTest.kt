package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.core.ui.MimirAppearanceOverrides
import com.gruning.mimir.rewrite.core.ui.MimirButtonMark
import com.gruning.mimir.rewrite.core.ui.MimirSceneGroundBlur
import com.gruning.mimir.rewrite.core.ui.MimirSceneGroundDim
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The store's half of concept A against a real preference file and a real `filesDir`:
 *  - committing a background writes `appearanceGround.<THEME> = sha|DIM|BLUR` and Atmosphere DELETES the key;
 *  - committing a mark writes `appearanceButtonMark = sha|STRUCK`, the rune deletes it, and only the mark ticks
 *    the revision the ᛗ overlay re-reads on;
 *  - load restores both from the key and the sidecar, and a key whose copy is gone clears itself;
 *  - a saved background another theme uses cannot be removed, and the saved lists are the directories.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class AppearanceOverrideStoreImagesTest {

    private lateinit var context: Context
    private val id = "0123456789abcdef0123456789abcdef"
    private val other = "fedcba9876543210fedcba9876543210"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("mimir_rewrite_appearance", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "appearance").deleteRecursively()
        MimirAppearanceOverrides.grounds = emptyMap()
        MimirAppearanceOverrides.buttonMark = null
    }

    @After
    fun tearDown() {
        MimirAppearanceOverrides.grounds = emptyMap()
        MimirAppearanceOverrides.buttonMark = null
        File(context.filesDir, "appearance").deleteRecursively()
    }

    private fun prefs() = context.getSharedPreferences("mimir_rewrite_appearance", Context.MODE_PRIVATE)

    /** A saved image on disk: a non-empty file and its sidecar, the way the store writes them. */
    private fun plant(kind: AppearanceImageKind, imageId: String, p90: Float = 0.2f, addedAt: Long = 1L): AppearanceSavedImage {
        val root = AppearanceImageStore.root(context, kind).apply { mkdirs() }
        File(root, "$imageId.${kind.fileExtension}").writeBytes(ByteArray(16) { 1 })
        File(root, "$imageId.json").writeText(
            JSONObject().put("width", 1920).put("height", 1080).put("p90", p90.toDouble()).put("addedAt", addedAt).toString(),
        )
        return AppearanceImageStore.find(context, kind, imageId)!!
    }

    /** Like [plant], but the sidecar also records the copy's length, the way an import writes it. */
    private fun plantRecorded(kind: AppearanceImageKind, imageId: String, length: Int = 4_096): AppearanceSavedImage {
        val root = AppearanceImageStore.root(context, kind).apply { mkdirs() }
        File(root, "$imageId.${kind.fileExtension}").writeBytes(ByteArray(length) { 1 })
        File(root, "$imageId.json").writeText(
            JSONObject().put("width", 1920).put("height", 1080).put("p90", 0.2).put("addedAt", 1L).put("bytes", length.toLong()).toString(),
        )
        return AppearanceImageStore.find(context, kind, imageId)!!
    }

    @Test
    fun `a whole copy reads available and a truncated one does not, though it is not empty`() {
        val whole = plantRecorded(AppearanceImageKind.BACKGROUND, id, length = 4_096)
        assertEquals(4_096L, whole.bytes)
        assertTrue(whole.available)

        // The copy is cut short (a full disk, a killed write): non-empty, so the old check called it SAVED.
        whole.file.writeBytes(ByteArray(1_000) { 1 })
        val truncated = AppearanceImageStore.find(context, AppearanceImageKind.BACKGROUND, id)!!
        assertTrue("the file is not empty", truncated.file.length() > 0L)
        assertFalse("but it is not the copy the import wrote: PREVIEW UNAVAILABLE", truncated.available)

        // Longer than recorded is just as wrong (a different file under the same name).
        whole.file.writeBytes(ByteArray(8_192) { 1 })
        assertFalse(AppearanceImageStore.find(context, AppearanceImageKind.BACKGROUND, id)!!.available)

        // The same holds for a mark, and a missing or empty copy is still unavailable.
        val mark = plantRecorded(AppearanceImageKind.MARK, other, length = 512)
        assertTrue(mark.available)
        mark.file.writeBytes(ByteArray(0))
        assertFalse(AppearanceImageStore.find(context, AppearanceImageKind.MARK, other)!!.available)
        mark.file.delete()
        assertFalse(AppearanceImageStore.find(context, AppearanceImageKind.MARK, other)!!.available)
    }

    @Test
    fun `a sidecar written before the length was recorded counts as available when the copy is not empty`() {
        val legacy = plant(AppearanceImageKind.BACKGROUND, id)
        assertNull("no recorded length", legacy.bytes)
        assertTrue(legacy.available)
        legacy.file.writeBytes(ByteArray(3) { 1 })
        assertTrue("any non-empty size is fine without a recorded length", AppearanceImageStore.find(context, AppearanceImageKind.BACKGROUND, id)!!.available)
        legacy.file.writeBytes(ByteArray(0))
        assertFalse(AppearanceImageStore.find(context, AppearanceImageKind.BACKGROUND, id)!!.available)
    }

    @Test
    fun `committing a background writes the key and Atmosphere deletes it`() {
        val saved = plant(AppearanceImageKind.BACKGROUND, id)
        val ground = saved.toGround(MimirSceneGroundDim.STRONG, MimirSceneGroundBlur.SOFT)
        val before = AppearanceOverrideStore.revision.value

        AppearanceOverrideStore.setGround(context, MimirThemeId.HEL, ground)
        assertEquals("$id|STRONG|SOFT", prefs().getString("appearanceGround.HEL", null))
        assertEquals(ground, MimirAppearanceOverrides.grounds[MimirThemeId.HEL])
        assertNull(MimirAppearanceOverrides.grounds[MimirThemeId.DEFAULT])
        assertEquals("a ground change does not tick the overlay's revision", before, AppearanceOverrideStore.revision.value)

        AppearanceOverrideStore.setGround(context, MimirThemeId.HEL, null)
        assertFalse(prefs().contains("appearanceGround.HEL"))
        assertTrue(MimirAppearanceOverrides.grounds.isEmpty())
    }

    @Test
    fun `committing a mark writes the key, the rune deletes it, and only the mark ticks the revision`() {
        val saved = plant(AppearanceImageKind.MARK, id)
        val before = AppearanceOverrideStore.revision.value
        AppearanceOverrideStore.setButtonMark(context, saved.toMark())
        assertEquals("$id|STRUCK", prefs().getString("appearanceButtonMark", null))
        assertEquals(MimirButtonMark(id, saved.path), MimirAppearanceOverrides.buttonMark)
        assertEquals(before + 1, AppearanceOverrideStore.revision.value)

        AppearanceOverrideStore.setButtonMark(context, null)
        assertFalse(prefs().contains("appearanceButtonMark"))
        assertNull(MimirAppearanceOverrides.buttonMark)
        assertEquals(before + 2, AppearanceOverrideStore.revision.value)
    }

    @Test
    fun `load restores both choices from the keys and the sidecars`() {
        val ground = plant(AppearanceImageKind.BACKGROUND, id, p90 = 0.42f)
        val mark = plant(AppearanceImageKind.MARK, other)
        prefs().edit()
            .putString("appearanceGround.MANI", "$id|DEEP|STRONG")
            .putString("appearanceButtonMark", "$other|STRUCK")
            .commit()

        AppearanceOverrideStore.load(context)

        val restored = MimirAppearanceOverrides.grounds[MimirThemeId.MANI]!!
        assertEquals(id, restored.id)
        assertEquals(ground.path, restored.path)
        assertEquals(0.42f, restored.p90, 1e-6f)
        assertEquals(MimirSceneGroundDim.DEEP, restored.dim)
        assertEquals(MimirSceneGroundBlur.STRONG, restored.blur)
        assertEquals(1920, restored.width)
        assertEquals(mark.toMark(), MimirAppearanceOverrides.buttonMark)
        assertEquals(listOf(id), AppearanceOverrideStore.savedBackgrounds.value.map { it.id })
        assertEquals(listOf(other), AppearanceOverrideStore.savedMarks.value.map { it.id })
    }

    @Test
    fun `a key whose copy is gone clears itself at load`() {
        prefs().edit().putString("appearanceGround.SURTR", "$id|MEDIUM|OFF").commit()
        AppearanceOverrideStore.load(context)
        assertNull(MimirAppearanceOverrides.grounds[MimirThemeId.SURTR])
        assertFalse("the stale key is removed so it cannot outlive the file", prefs().contains("appearanceGround.SURTR"))
    }

    @Test
    fun `a saved background another theme uses cannot be removed, an unused one can`() {
        val used = plant(AppearanceImageKind.BACKGROUND, id, addedAt = 1L)
        plant(AppearanceImageKind.BACKGROUND, other, addedAt = 2L)
        AppearanceOverrideStore.refreshSavedLists(context)
        assertEquals(listOf(id, other), AppearanceOverrideStore.savedBackgrounds.value.map { it.id })
        AppearanceOverrideStore.setGround(context, MimirThemeId.HEL, used.toGround(MimirSceneGroundDim.MEDIUM, MimirSceneGroundBlur.OFF))

        assertEquals(listOf(MimirThemeId.HEL), AppearanceOverrideStore.themesUsingBackground(id))
        assertFalse(AppearanceOverrideStore.removeSavedBackground(context, id))
        assertTrue(AppearanceImageStore.imageFile(context, AppearanceImageKind.BACKGROUND, id).isFile)

        assertTrue(AppearanceOverrideStore.removeSavedBackground(context, other))
        assertFalse(AppearanceImageStore.imageFile(context, AppearanceImageKind.BACKGROUND, other).exists())
        assertEquals(listOf(id), AppearanceOverrideStore.savedBackgrounds.value.map { it.id })
    }

    @Test
    fun `the current mark cannot be removed and a path-shaped id is refused`() {
        val mark = plant(AppearanceImageKind.MARK, id)
        AppearanceOverrideStore.setButtonMark(context, mark.toMark())
        assertFalse(AppearanceOverrideStore.removeSavedMark(context, id))
        assertFalse(AppearanceImageStore.remove(context, AppearanceImageKind.MARK, "../../shared_prefs/x"))
        AppearanceOverrideStore.setButtonMark(context, null)
        assertTrue(AppearanceOverrideStore.removeSavedMark(context, id))
        assertTrue(AppearanceOverrideStore.savedMarks.value.isEmpty())
    }
}

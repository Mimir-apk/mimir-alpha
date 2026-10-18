package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player's background -- the pure parts, pinned:
 *  - there is no readability floor: the stored dim is what draws ;
 *  - the crop-to-fill rectangle and the decode sample bound;
 *  - the cache key: a null ground is exactly today's key, a ground is a different raster, and a ground-only change
 *    takes the off-thread path (`differsOnlyInPaletteFrom`) while a ground + geometry change keeps the synchronous bake;
 *  - the bake log line names the ground;
 *  - `customizes()` counts a background and a button mark, so the Appearance chip never reads Default over one.
 */
class MimirSceneGroundTest {

    @After fun reset() {
        MimirAppearanceOverrides.grounds = emptyMap()
        MimirAppearanceOverrides.buttonMark = null
    }

    private fun ground(
        id: String = "abc123",
        p90: Float = 0.2f,
        dim: MimirSceneGroundDim = MimirSceneGroundDim.MEDIUM,
        blur: MimirSceneGroundBlur = MimirSceneGroundBlur.OFF,
    ) = MimirSceneGround(id = id, path = "/data/appearance/backgrounds/$id.jpg", width = 1920, height = 1080, p90 = p90, dim = dim, blur = blur)

    // ── no readability floor () ─────────────────────

    @Test fun `the stored dim is what draws, however bright the image`() {
        // The model carries the choice as is: nothing raises a bright image's dim. What the veil DRAWS is not read off the source here: the baked pixels are
        // checked by MimirSceneGroundVeilRenderTest (a separate class, so this one stays a plain JVM test that touches no sandbox).
        val bright = ground(p90 = 1f, dim = MimirSceneGroundDim.LIGHT)
        assertEquals(MimirSceneGroundDim.LIGHT, bright.dim)
        val atmosphere = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirAtmosphere.kt").readText()
        val model = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirSceneGround.kt").readText()
        listOf("mimirGroundDimFloor", "clearsTextOn", "effectiveDim", "mimirGroundLuminanceAfterDim", "MimirGroundTextContrastFloor").forEach { gone ->
            assertFalse("$gone is gone", model.contains(gone) || atmosphere.contains(gone))
        }
    }

    @Test fun `dim steps strengthen in ordinal order`() {
        MimirSceneGroundDim.entries.zipWithNext().forEach { (a, b) ->
            assertTrue("$a < $b", a.alpha < b.alpha)
        }
    }

    // ── geometry ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `crop to fill cuts the overhanging axis equally from both sides`() {
        // A 4:3 image on a 16:9 surface: full width, a centred 1920x1080 band.
        assertEquals(
            MimirGroundCrop(IntOffset(0, 180), IntSize(1920, 1080)),
            mimirGroundCropRect(1920, 1440, 1920f, 1080f),
        )
        // A 16:9 image on a square surface: full height, a centred square.
        assertEquals(
            MimirGroundCrop(IntOffset(420, 0), IntSize(1080, 1080)),
            mimirGroundCropRect(1920, 1080, 720f, 720f),
        )
        // A matching aspect is the whole image.
        assertEquals(MimirGroundCrop(IntOffset.Zero, IntSize(1920, 1080)), mimirGroundCropRect(1920, 1080, 640f, 360f))
    }

    @Test fun `a degenerate crop answers the whole image`() {
        assertEquals(MimirGroundCrop(IntOffset.Zero, IntSize(10, 10)), mimirGroundCropRect(10, 10, 0f, 5f))
        assertEquals(MimirGroundCrop(IntOffset.Zero, IntSize(0, 0)), mimirGroundCropRect(0, 0, 5f, 5f))
    }

    @Test fun `the decode sample stays 1 for a stored copy and halves a stray larger file`() {
        assertEquals(1, mimirGroundInSampleSize(1920, 1080, 1920))
        assertEquals(1, mimirGroundInSampleSize(720, 720, 1920))
        assertEquals(2, mimirGroundInSampleSize(3840, 2160, 1920))
        assertEquals(4, mimirGroundInSampleSize(8000, 6000, 1920))
        assertEquals(1, mimirGroundInSampleSize(0, 0, 1920))
    }

    // ── the cache key ───────────────────────────────────────────────────────────────────────────────────────────

    private fun key(
        palette: MimirThemePalette = MimirThemePalette.forId(MimirThemeId.DEFAULT),
        ground: MimirSceneGround? = null,
        size: Size = Size(1920f, 1080f),
    ) = MimirShelfBackgroundCacheKey(
        size = size,
        density = 2f,
        layoutDirection = LayoutDirection.Ltr,
        palette = palette,
        showAtmosphericClouds = false,
        ground = ground,
    )

    @Test fun `a key without a ground is exactly the key of today`() {
        val today = MimirShelfBackgroundCacheKey(
            size = Size(1920f, 1080f),
            density = 2f,
            layoutDirection = LayoutDirection.Ltr,
            palette = MimirThemePalette.forId(MimirThemeId.DEFAULT),
            showAtmosphericClouds = false,
        )
        assertEquals(today, key())
        assertEquals(today.hashCode(), key().hashCode())
    }

    @Test fun `a ground and each of its settings make a different raster`() {
        assertNotEquals(key(), key(ground = ground()))
        assertNotEquals(key(ground = ground()), key(ground = ground(id = "other")))
        assertNotEquals(key(ground = ground()), key(ground = ground(dim = MimirSceneGroundDim.DEEP)))
        assertNotEquals(key(ground = ground()), key(ground = ground(blur = MimirSceneGroundBlur.SOFT)))
        assertEquals(key(ground = ground()), key(ground = ground()))
    }

    @Test fun `a ground-only change takes the off-thread path like a palette change`() {
        val mani = MimirThemePalette.forId(MimirThemeId.MANI)
        assertTrue(key().differsOnlyInPaletteFrom(key(palette = mani)))
        assertTrue(key().differsOnlyInPaletteFrom(key(ground = ground())))
        assertTrue(key(ground = ground()).differsOnlyInPaletteFrom(key()))
        assertTrue(key(ground = ground()).differsOnlyInPaletteFrom(key(ground = ground(dim = MimirSceneGroundDim.DEEP))))
        assertTrue(key().differsOnlyInPaletteFrom(key(palette = mani, ground = ground())))
    }

    @Test fun `a ground change with a geometry change keeps the synchronous bake`() {
        assertFalse(key().differsOnlyInPaletteFrom(key(ground = ground(), size = Size(640f, 480f))))
        assertFalse(key().differsOnlyInPaletteFrom(key()))
        assertFalse(key(ground = ground()).differsOnlyInPaletteFrom(key(ground = ground())))
    }

    @Test fun `the bake log line names the ground`() {
        val line = mimirShelfBakeLogMessage(key(ground = ground(id = "feedbeef")), MimirSceneBakeOutcome.INSERTED, 12L)
        assertTrue(line, line.endsWith("outcome=inserted ground=feedbeef"))
        assertTrue(mimirShelfBakeLogMessage(key(), MimirSceneBakeOutcome.STALE, null).endsWith("ground=none"))
    }

    // ── customizes() ────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a background counts as customizing its own theme only`() {
        MimirAppearanceOverrides.grounds = mapOf(MimirThemeId.HEL to ground())
        assertTrue(MimirAppearanceOverrides.customizes(MimirThemeId.HEL))
        assertFalse(MimirAppearanceOverrides.customizes(MimirThemeId.DEFAULT))
        assertEquals(ground(), MimirAppearanceOverrides.effectiveGround(MimirThemeId.HEL))
        assertEquals(null, MimirAppearanceOverrides.effectiveGround(MimirThemeId.DEFAULT))
    }

    @Test fun `a button mark counts as customizing every theme`() {
        MimirAppearanceOverrides.buttonMark = MimirButtonMark(id = "m1", path = "/data/appearance/marks/m1.png")
        MimirThemeId.entries.forEach { assertTrue(it.name, MimirAppearanceOverrides.customizes(it)) }
        assertEquals(MimirButtonMarkStyle.STRUCK, MimirAppearanceOverrides.buttonMark?.style)
    }

    @Test fun `neither set leaves the chip honest`() {
        MimirThemeId.entries.forEach { assertFalse(it.name, MimirAppearanceOverrides.customizes(it)) }
    }
}

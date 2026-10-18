package com.gruning.mimir.rewrite.core.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reduced motion is a CUT on the Bottom screen's and the compact miniature's ground swap with every change cut and nothing moving at rest.
 *
 * `mimirThemeSceneBackdrop` built its swap with the default [MimirMotion.SceneMs] crossfade whatever the animator scale
 * said, so a theme or background change still faded there while every other surface cut. The swap is now built from
 * [mimirSceneBackdropCrossfadeMillis], and a zero-length crossfade is the same cutting swap the Main shelf runs.
 *
 * Pinned: the duration function; that a swap of that duration shows a cached or landed raster at once with nothing fading
 * out (and that the normal one still crossfades); and, as source, that the composable actually builds its swap from it.
 * Whether the device's animator scale reaches `mimirReducedMotion()` is the emulator's (`animator_duration_scale 0`).
 */
class MimirSceneBackdropReducedMotionTest {

    private data class Key(val geometry: Int, val palette: String)

    private val paletteOnly: (Key, Key) -> Boolean = { from, to -> from.geometry == to.geometry && from.palette != to.palette }
    private val default = Key(geometry = 1, palette = "default")
    private val mani = Key(geometry = 1, palette = "mani")

    private fun swapFor(reducedMotion: Boolean): MimirSceneBackdropSwap<Key, String> =
        MimirSceneBackdropSwap<Key, String>(crossfadeMillis = mimirSceneBackdropCrossfadeMillis(reducedMotion)).also {
            it.request(default, paletteOnly) { null }
            it.showNow(default, "default-raster")
        }

    @Test
    fun `reduced motion is no crossfade and otherwise the scene clock`() {
        assertEquals(0, mimirSceneBackdropCrossfadeMillis(reducedMotion = true))
        assertEquals(MimirMotion.SceneMs, mimirSceneBackdropCrossfadeMillis(reducedMotion = false))
    }

    @Test
    fun `under reduced motion a cached raster is shown at once and nothing fades`() {
        val swap = swapFor(reducedMotion = true)
        assertEquals(MimirSceneBackdropStep.DRAW, swap.request(mani, paletteOnly) { "mani-raster" })
        assertEquals("mani-raster", swap.shown)
        assertNull("a cut leaves nothing fading out", swap.outgoing)
        assertEquals(1f, swap.incomingAlpha(0L), 0f)
    }

    @Test
    fun `under reduced motion a landed bake is shown at once and nothing fades`() {
        val swap = swapFor(reducedMotion = true)
        assertEquals(MimirSceneBackdropStep.BAKE_OFF_THREAD, swap.request(mani, paletteOnly) { null })
        assertTrue(swap.landed(mani, "mani-raster"))
        assertEquals("mani-raster", swap.shown)
        assertNull(swap.outgoing)
        assertEquals(1f, swap.incomingAlpha(0L), 0f)
    }

    @Test
    fun `without reduced motion the same changes still crossfade over the scene clock`() {
        val cached = swapFor(reducedMotion = false)
        assertEquals(MimirSceneBackdropStep.CROSSFADE, cached.request(mani, paletteOnly) { "mani-raster" })
        assertEquals("default-raster", cached.outgoing)
        assertEquals("the incoming raster has not started to fade in", 0f, cached.incomingAlpha(0L), 0f)

        val baked = swapFor(reducedMotion = false)
        baked.request(mani, paletteOnly) { null }
        assertTrue(baked.landed(mani, "mani-raster"))
        assertEquals("default-raster", baked.outgoing)
    }

    /** `mimirThemeSceneBackdrop`'s body, comments stripped and whitespace collapsed. */
    private fun themeSceneBackdropBody(): String {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirAtmosphere.kt")
        assertTrue("MimirAtmosphere.kt not found at ${file.absolutePath} -- move this pin with it", file.isFile)
        val code = file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
        val start = code.indexOf("fun Modifier.mimirThemeSceneBackdrop(")
        assertTrue("fun Modifier.mimirThemeSceneBackdrop( is gone from MimirAtmosphere.kt", start >= 0)
        val end = Regex("""\n(?:private |internal )?(?:suspend )?fun """).find(code, start + 1)?.range?.first ?: code.length
        return code.substring(start, end).replace(Regex("""\s+"""), " ")
    }

    @Test
    fun `the miniature builds its swap from the reduced-motion clock`() {
        val body = themeSceneBackdropBody()
        assertTrue("the composable must read the system's reduced-motion setting", "val reducedMotion = mimirReducedMotion()" in body)
        assertTrue(
            "the swap's crossfade comes from the one duration function, keyed on the setting",
            "remember(reducedMotion) { MimirSceneBackdropSwap<MimirShelfBackgroundCacheKey, ImageBitmap>( " +
                "crossfadeMillis = mimirSceneBackdropCrossfadeMillis(reducedMotion), ) }" in body,
        )
        assertFalse(
            "a swap with the default crossfade fades under reduced motion",
            "remember { MimirSceneBackdropSwap<MimirShelfBackgroundCacheKey, ImageBitmap>() }" in body,
        )
    }
}

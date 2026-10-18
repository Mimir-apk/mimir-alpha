package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A stale off-thread shelf bake can never replace a newer key -- not on screen, not in the cache -- and is released
 * (Main shelf).
 *
 * Runs the production pieces together: the real [MimirShelfBackgroundBitmapCache] at its production bounds (two entries,
 * 16 MiB), a cutting [MimirSceneBackdropSwap], [landOffThreadBake] with the same `publish` the draw passes
 * (`getOrBuild(key, estimatedBytes) { result }`) and [releaseUnpublishedShelfRaster]. The ORDER inside the landing is the
 * point: the cache holds two rasters, so a stale result that is published first and checked second -- the Bottom
 * backdrop's round-4 order -- takes a slot, and two of them push the newer key's raster out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirShelfBackgroundStaleBakeTest {

    private val cache = MimirShelfBackgroundBitmapCache()
    private val default = key(MimirThemePalette.Default)
    private val mani = key(MimirThemePalette.Mani)
    private val freyr = key(MimirThemePalette.Freyr)
    private val hel = key(MimirThemePalette.Hel)

    @Test
    fun `stale bakes landing after the newer key replace neither its raster on screen nor its cache entry`() {
        val swap = shownSwap()
        assertEquals(MimirSceneBackdropStep.BAKE_OFF_THREAD, swap.requestFor(mani))
        assertEquals(MimirSceneBackdropStep.BAKE_OFF_THREAD, swap.requestFor(freyr))
        assertEquals(MimirSceneBackdropStep.BAKE_OFF_THREAD, swap.requestFor(hel))

        val helBake = bake()
        assertEquals(MimirSceneBakeOutcome.INSERTED, swap.land(hel, helBake))
        val maniBake = bake()
        val freyrBake = bake()
        assertEquals(MimirSceneBakeOutcome.STALE, swap.land(mani, maniBake))
        assertEquals(MimirSceneBakeOutcome.STALE, swap.land(freyr, freyrBake))

        assertEquals(hel, swap.shownKey)
        assertSame(helBake.bitmap, swap.shown)
        assertSame("the newer key's raster must keep its cache entry", helBake.bitmap, cache.get(hel))
        assertNull("a stale raster must never enter the cache", cache.get(mani))
        assertNull("a stale raster must never enter the cache", cache.get(freyr))
        assertTrue("a stale raster is released", maniBake.bitmap.asAndroidBitmap().isRecycled)
        assertTrue("a stale raster is released", freyrBake.bitmap.asAndroidBitmap().isRecycled)
        assertFalse(helBake.bitmap.asAndroidBitmap().isRecycled)
    }

    @Test
    fun `a stale bake landing before the newer key is released without ever showing`() {
        val swap = shownSwap()
        val previousRaster = swap.shown
        swap.requestFor(mani)
        swap.requestFor(hel)

        val maniBake = bake()
        assertEquals(MimirSceneBakeOutcome.STALE, swap.land(mani, maniBake))
        assertSame("the previous raster keeps drawing", previousRaster, swap.shown)
        assertNull("a stale raster must never enter the cache", cache.get(mani))
        assertTrue("a stale raster is released", maniBake.bitmap.asAndroidBitmap().isRecycled)

        val helBake = bake()
        assertEquals(MimirSceneBakeOutcome.INSERTED, swap.land(hel, helBake))
        assertSame(helBake.bitmap, swap.shown)
        assertSame(helBake.bitmap, cache.get(hel))
    }

    @Test
    fun `a bake for a key another surface already cached adopts that raster and releases its own`() {
        val first = shownSwap()
        val second = shownSwap()
        first.requestFor(mani)
        second.requestFor(mani)

        val firstBake = bake()
        assertEquals(MimirSceneBakeOutcome.INSERTED, first.land(mani, firstBake))
        val secondBake = bake()
        assertEquals(MimirSceneBakeOutcome.ALREADY_CACHED, second.land(mani, secondBake))

        assertSame(firstBake.bitmap, second.shown)
        assertTrue("the duplicate raster is released", secondBake.bitmap.asAndroidBitmap().isRecycled)
        assertFalse(firstBake.bitmap.asAndroidBitmap().isRecycled)
    }

    private fun shownSwap(): MimirSceneBackdropSwap<MimirShelfBackgroundCacheKey, ImageBitmap> =
        MimirSceneBackdropSwap<MimirShelfBackgroundCacheKey, ImageBitmap>(crossfadeMillis = 0).also { swap ->
            assertEquals(MimirSceneBackdropStep.BAKE_NOW, swap.requestFor(default))
            swap.showNow(default, cache.getOrBuild(default, EstimatedBytes) { bake() })
        }

    private fun MimirSceneBackdropSwap<MimirShelfBackgroundCacheKey, ImageBitmap>.requestFor(
        key: MimirShelfBackgroundCacheKey,
    ): MimirSceneBackdropStep = request(key, MimirShelfBackgroundCacheKey::differsOnlyInPaletteFrom, cache::get)

    /** The landing the draw runs on the UI thread, with the draw's own `publish` and `release`. */
    private fun MimirSceneBackdropSwap<MimirShelfBackgroundCacheKey, ImageBitmap>.land(
        key: MimirShelfBackgroundCacheKey,
        built: MimirShelfBackgroundCacheBuild,
    ): MimirSceneBakeOutcome = landOffThreadBake(
        key = key,
        result = built,
        rasterOf = MimirShelfBackgroundCacheBuild::bitmap,
        publish = { cacheKey, result -> cache.getOrBuild(cacheKey, EstimatedBytes) { result } },
        release = ::releaseUnpublishedShelfRaster,
    )

    private fun bake(): MimirShelfBackgroundCacheBuild = MimirShelfBackgroundCacheBuild(ImageBitmap(1, 1), EstimatedBytes)

    private fun key(palette: MimirThemePalette): MimirShelfBackgroundCacheKey = MimirShelfBackgroundCacheKey(
        size = Size(1f, 1f),
        density = 1f,
        layoutDirection = LayoutDirection.Ltr,
        palette = palette,
        showAtmosphericClouds = false,
    )

    private companion object {
        const val EstimatedBytes = 4L
    }
}

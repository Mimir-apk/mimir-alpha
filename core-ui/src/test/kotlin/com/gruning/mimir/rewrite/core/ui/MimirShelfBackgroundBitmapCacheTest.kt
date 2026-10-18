package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirShelfBackgroundBitmapCacheTest {
    @Test
    fun equalKeysAcrossCallersBuildOnceAndReuseBitmapIdentity() {
        val cache = MimirShelfBackgroundBitmapCache()
        val key = key()
        var builds = 0

        val first = cache.getOrBuild(key, estimatedBytes = 4L) {
            builds++
            build(bytes = 4L)
        }
        val second = cache.getOrBuild(key, estimatedBytes = 4L) {
            builds++
            build(bytes = 4L)
        }

        assertSame(first, second)
        assertEquals(1, builds)
        assertEquals(1, cache.entryCount)
        assertEquals(4L, cache.byteCount)
    }

    @Test
    fun everyPixelAffectingKeyChangeMisses() {
        val variants = listOf(
            key(size = Size(100.25f, 200f)),
            key(size = Size(100f, 200.25f)),
            key(density = 2f),
            key(layoutDirection = LayoutDirection.Rtl),
            key(palette = MimirThemePalette.Default.copy(accent = Color(0xFF123456))),
            key(showAtmosphericClouds = true),
        )

        variants.forEach { variant ->
            val cache = MimirShelfBackgroundBitmapCache()
            val first = cache.getOrBuild(key(), estimatedBytes = 4L) { build(bytes = 4L) }
            val second = cache.getOrBuild(variant, estimatedBytes = 4L) { build(bytes = 4L) }
            assertNotSame("changed cache key must rebuild: $variant", first, second)
            assertEquals(2, cache.entryCount)
        }
    }

    @Test
    fun lruEvictsByCountAndRetainsRecentlyUsedEntry() {
        val cache = MimirShelfBackgroundBitmapCache(maxEntries = 2, maxBytes = 100L)
        val a = key(size = Size(1f, 1f))
        val b = key(size = Size(2f, 2f))
        val c = key(size = Size(3f, 3f))
        var builds = 0

        val firstA = cache.getOrBuild(a, estimatedBytes = 30L) { builds++; build(bytes = 30L) }
        cache.getOrBuild(b, estimatedBytes = 30L) { builds++; build(bytes = 30L) }
        assertSame(firstA, cache.getOrBuild(a, estimatedBytes = 30L) { builds++; build(bytes = 30L) })
        cache.getOrBuild(c, estimatedBytes = 30L) { builds++; build(bytes = 30L) }

        assertEquals(3, builds)
        assertEquals(2, cache.entryCount)
        assertEquals(60L, cache.byteCount)
        cache.getOrBuild(b, estimatedBytes = 30L) { builds++; build(bytes = 30L) }
        assertEquals(4, builds)
        assertEquals(2, cache.entryCount)
        assertEquals(60L, cache.byteCount)
    }

    @Test
    fun byteBoundEvictsEvenWhenEntryCountStillFits() {
        val cache = MimirShelfBackgroundBitmapCache(maxEntries = 2, maxBytes = 100L)
        val a = key(size = Size(1f, 1f))
        val b = key(size = Size(2f, 2f))
        var builds = 0

        cache.getOrBuild(a, estimatedBytes = 60L) { builds++; build(bytes = 60L) }
        cache.getOrBuild(b, estimatedBytes = 60L) { builds++; build(bytes = 60L) }

        assertEquals(2, builds)
        assertEquals(1, cache.entryCount)
        assertEquals(60L, cache.byteCount)
        cache.getOrBuild(a, estimatedBytes = 60L) { builds++; build(bytes = 60L) }
        assertEquals(3, builds)
    }

    @Test
    fun oversizeAndActualOverBudgetBuildsAreNeverInserted() {
        val cache = MimirShelfBackgroundBitmapCache(maxEntries = 2, maxBytes = 100L)
        var builds = 0

        cache.getOrBuild(key(), estimatedBytes = 101L) { builds++; build(bytes = 4L) }
        cache.getOrBuild(key(), estimatedBytes = 101L) { builds++; build(bytes = 4L) }
        assertEquals(2, builds)
        assertEquals(0, cache.entryCount)

        cache.getOrBuild(key(size = Size(2f, 2f)), estimatedBytes = 4L) {
            builds++
            build(bytes = 101L)
        }
        assertEquals(3, builds)
        assertEquals(0, cache.entryCount)
    }

    @Test
    fun failedBuildLeavesNoPartialEntry() {
        val cache = MimirShelfBackgroundBitmapCache()
        val key = key()

        runCatching {
            cache.getOrBuild(key, estimatedBytes = 4L) {
                error("synthetic incomplete bake")
            }
        }

        assertEquals(0, cache.entryCount)
        assertEquals(0L, cache.byteCount)
    }

    @Test
    fun getReturnsOnlyACompletedEntryAndNeverBuilds() {
        val cache = MimirShelfBackgroundBitmapCache()
        assertNull(cache.get(key()))
        assertEquals(0, cache.entryCount)

        val built = cache.getOrBuild(key(), estimatedBytes = 4L) { build(bytes = 4L) }

        assertSame(built, cache.get(key()))
        assertNull(cache.get(key(size = Size(2f, 2f))))
        assertEquals(1, cache.entryCount)
    }

    @Test
    fun nominalPreflightIsOverflowSafe() {
        assertEquals(0L, MimirShelfBackgroundBitmapCache.nominalAllocationBytes(0, 1))
        assertEquals(0L, MimirShelfBackgroundBitmapCache.nominalAllocationBytes(1, 0))
        assertEquals(4L, MimirShelfBackgroundBitmapCache.nominalAllocationBytes(1, 1))
        assertEquals(8_294_400L, MimirShelfBackgroundBitmapCache.nominalAllocationBytes(1920, 1080))
        assertEquals(null, MimirShelfBackgroundBitmapCache.nominalAllocationBytes(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    private fun key(
        size: Size = Size(100f, 200f),
        density: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        palette: MimirThemePalette = MimirThemePalette.Default,
        showAtmosphericClouds: Boolean = false,
    ): MimirShelfBackgroundCacheKey = MimirShelfBackgroundCacheKey(
        size = size,
        density = density,
        layoutDirection = layoutDirection,
        palette = palette,
        showAtmosphericClouds = showAtmosphericClouds,
    )

    private fun build(bytes: Long): MimirShelfBackgroundCacheBuild =
        MimirShelfBackgroundCacheBuild(ImageBitmap(1, 1), bytes)
}

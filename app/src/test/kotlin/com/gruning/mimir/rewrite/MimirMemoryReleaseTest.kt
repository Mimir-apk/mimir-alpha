package com.gruning.mimir.rewrite

import android.app.Application
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.core.app.ApplicationProvider
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

/** Lane mem (2026-10-14): which system trim levels release the art caches, and what a release leaves mounted art. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MimirMemoryReleaseTest {
    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        HomeQuickLaunchPickerIconCache.evictAll()
    }

    @After
    fun tearDown() {
        HomeQuickLaunchPickerIconCache.evictAll()
    }

    @Test
    fun `running-low, running-critical, background and every level above it release`() {
        listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW + 9,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        ).forEach { level ->
            assertTrue("level $level releases", MimirMemoryRelease.releasesAtTrimLevel(level))
        }
    }

    @Test
    fun `ui-hidden keeps every cache, as do running-moderate and an unknown low level`() {
        // Fix round (2026-10-14): UI_HIDDEN is what a game launch sends on a single-screen device; a release there
        // would be a release on every launch, and the return would re-decode its art in view.
        assertFalse(MimirMemoryRelease.releasesAtTrimLevel(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertFalse(MimirMemoryRelease.releasesAtTrimLevel(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND - 1))
        assertFalse(MimirMemoryRelease.releasesAtTrimLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertFalse(MimirMemoryRelease.releasesAtTrimLevel(0))
    }

    private fun icon() = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).asImageBitmap()

    /** 70 icons of 192 px ARGB is about 10 MB, past half of the picker cache's 16 MB budget. */
    private fun fillPickerCache(count: Int = 70) {
        repeat(count) { HomeQuickLaunchPickerIconCache.put("icon-$it", icon()) }
    }

    @Test
    fun `art a mounted consumer reads every paint is still found after a release, only the least recently used goes`() {
        fillPickerCache()
        // The mounted dock reads its icons every paint, which makes them the most recently used.
        repeat(10) { assertNotNull(HomeQuickLaunchPickerIconCache.get("icon-$it")) }
        val before = HomeQuickLaunchPickerIconCache.sizeBytes()

        MimirMemoryRelease.onTrimMemory(context, ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)

        val after = HomeQuickLaunchPickerIconCache.sizeBytes()
        assertTrue("the cache was trimmed ($before -> $after)", after < before)
        assertTrue("to half its budget or less", after <= 8 * 1024 * 1024)
        repeat(10) { assertNotNull("mounted icon $it is still found, so nothing re-decodes in view", HomeQuickLaunchPickerIconCache.get("icon-$it")) }
        assertNull("the least recently used icon is what went", HomeQuickLaunchPickerIconCache.get("icon-10"))
    }

    @Test
    fun `ui-hidden leaves the caches untouched`() {
        fillPickerCache()
        val before = HomeQuickLaunchPickerIconCache.sizeBytes()

        MimirMemoryRelease.onTrimMemory(context, ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)

        assertEquals(before, HomeQuickLaunchPickerIconCache.sizeBytes())
        repeat(70) { assertNotNull(HomeQuickLaunchPickerIconCache.get("icon-$it")) }
    }
}

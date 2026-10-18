package com.gruning.mimir.rewrite.core.ui.select

import com.gruning.mimir.rewrite.core.ui.select.PickerBitmapCache
import com.gruning.mimir.rewrite.core.ui.select.PickerArt
import com.gruning.mimir.rewrite.core.ui.select.rememberPickerArt
import com.gruning.mimir.rewrite.core.ui.select.pickerThumbnailKey
import com.gruning.mimir.rewrite.core.ui.select.pickerMarkKey
import com.gruning.mimir.rewrite.core.ui.select.pickerAppIconKey

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Appearance pickers' small images (audit 2026-10-14, RC-28: S-07, S-24, S-25, S-27) are decoded once and then kept: a tile that was
 * empty-then-popped on every open paints on the composition's FIRST frame the second time, and nothing decodes again. A cold first decode
 * arrives into an empty tile and fades in from nothing (Q13; its pixels are [PickerArtColdFadeTest]'s); this proves the
 * warm path (whole at once, never faded), the single decode, the fade's alpha, and that a new key never inherits the picture of the one
 * before it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PickerBitmapCacheTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val first: ImageBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImageBitmap()
    private val second: ImageBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImageBitmap()

    @Before
    fun clear() = PickerBitmapCache.clear()

    @After
    fun clearAfter() = PickerBitmapCache.clear()

    /** A key already in the cache is the composition's answer on its very first frame, whole, with no decode at all. */
    @Test
    fun `a cached key paints on the first frame, whole, and never decodes`() {
        PickerBitmapCache.put("k", first)
        val decodes = AtomicInteger()
        val frames = mutableListOf<PickerArt>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            frames += rememberPickerArt("k") { decodes.incrementAndGet(); second }
        }
        compose.waitForIdle()
        assertSame("frame 0 already wears the kept image", first, frames.first().bitmap)
        assertTrue("and every frame after it", frames.all { it.bitmap === first })
        assertEquals("whole at once: a warm show is never faded", 1f, frames.first().alpha, 0f)
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        assertTrue("and stays whole", frames.all { it.alpha == 1f })
        assertEquals("nothing was decoded for a key the cache holds", 0, decodes.get())
    }

    /** A cold key decodes once, lands, fades in from nothing, is kept, and the next mount of it is warm and whole. */
    @Test
    fun `a cold key decodes once and the next mount is warm`() {
        val decodes = AtomicInteger()
        val mounts = AtomicInteger()
        val perMount = mutableMapOf<Int, MutableList<PickerArt>>()
        var open by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            if (open) {
                val mount = remember { mounts.incrementAndGet() }
                perMount.getOrPut(mount) { mutableListOf() } += rememberPickerArt("cold") { decodes.incrementAndGet(); first }
            }
        }
        compose.waitForIdle()
        assertNull("the cold decode arrives into an empty tile", perMount.getValue(1).first().bitmap)
        assertEquals("drawing nothing", 0f, perMount.getValue(1).first().alpha, 0f)

        val deadline = System.currentTimeMillis() + 5_000
        while (perMount.getValue(1).last().bitmap == null && System.currentTimeMillis() < deadline) {
            compose.mainClock.advanceTimeBy(16)
            Snapshot.sendApplyNotifications()
            compose.waitForIdle()
            Thread.sleep(10)
        }
        assertSame(first, perMount.getValue(1).last().bitmap)
        assertEquals(1, decodes.get())
        assertSame("and the cache keeps it", first, PickerBitmapCache.peek("cold"))

        // It lands at nothing and fades to whole.
        val art = perMount.getValue(1).last()
        val alphas = mutableListOf(art.alpha)
        repeat(12) {
            compose.mainClock.advanceTimeBy(16)
            Snapshot.sendApplyNotifications()
            compose.waitForIdle()
            alphas += art.alpha
        }
        assertEquals("landed at nothing: $alphas", 0f, alphas.first(), 0.001f)
        assertTrue("through the middle: $alphas", alphas.any { it > 0.05f && it < 0.95f })
        assertEquals("whole by FocusMs and a frame: $alphas", 1f, alphas.last(), 0f)
        assertEquals("never steps back: $alphas", alphas, alphas.sorted())

        // The picker closes and opens again: a new mount, warm on its frame 0, whole, with no second decode.
        Snapshot.withMutableSnapshot { open = false }
        compose.mainClock.advanceTimeBy(16)
        compose.waitForIdle()
        Snapshot.withMutableSnapshot { open = true }
        compose.mainClock.advanceTimeBy(16)
        compose.waitForIdle()
        assertEquals(2, mounts.get())
        assertSame("the second open paints on frame 0", first, perMount.getValue(2).first().bitmap)
        assertEquals("whole on it", 1f, perMount.getValue(2).first().alpha, 0f)
        assertEquals("and decodes nothing", 1, decodes.get())
    }

    /** A file that fails to decode is not cached, so a later open tries again rather than keeping a hole. */
    @Test
    fun `a decode that answers nothing is not kept`() {
        val decodes = AtomicInteger()
        var seen: PickerArt? = null
        compose.setContent {
            seen = rememberPickerArt("missing") { decodes.incrementAndGet(); null }
        }
        compose.waitUntil(timeoutMillis = 5_000) { decodes.get() == 1 }
        compose.waitForIdle()
        assertNull(seen!!.bitmap)
        assertNull("no hole is kept", PickerBitmapCache.peek("missing"))
    }

    /** No key, no art. */
    @Test
    fun `no key draws nothing`() {
        var seen: PickerArt? = null
        compose.setContent { seen = rememberPickerArt(null) { error("nothing to decode") } }
        compose.waitForIdle()
        assertNull(seen!!.bitmap)
    }

    /** A key that changes starts from nothing, never from the image of the key before it. */
    @Test
    fun `a new key never wears the picture of the one before it`() {
        PickerBitmapCache.put("a", first)
        var key by mutableStateOf("a")
        val frames = mutableListOf<PickerArt>()
        compose.setContent {
            frames += rememberPickerArt(key) { second }
        }
        compose.waitForIdle()
        assertSame(first, frames.last().bitmap)

        val before = frames.size
        Snapshot.withMutableSnapshot { key = "b" }
        compose.waitForIdle()
        assertNull("the first frame of the new key has nothing of 'a' in it", frames[before].bitmap)
        compose.waitUntil(timeoutMillis = 5_000) { frames.last().bitmap != null }
        assertSame(second, frames.last().bitmap)
    }

    /** The cache is bounded by bytes, least recently used first. */
    @Test
    fun `the cache is bounded and drops the least recently used`() {
        val big = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888).asImageBitmap() // 4 MiB
        PickerBitmapCache.put("one", big)
        assertNotNull(PickerBitmapCache.peek("one"))
        PickerBitmapCache.put("two", big) // 8 MiB against a 6 MiB budget: "one" goes.
        assertNull(PickerBitmapCache.peek("one"))
        assertNotNull(PickerBitmapCache.peek("two"))
        assertEquals(1, PickerBitmapCache.size)
    }

    @Test
    fun `the keys name what produced the image`() {
        assertEquals("thumb:256:/a/b.jpg", pickerThumbnailKey("/a/b.jpg", 256))
        assertEquals("mark:256:/a/b.png", pickerMarkKey("/a/b.png", 256))
        assertEquals("icon:96:com.example", pickerAppIconKey("com.example", 96))
    }
}

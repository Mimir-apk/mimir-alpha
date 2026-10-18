package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the Main shelf's theme-switch fix (no crossfade).
 *
 * The AYN Thor profile of a cold switch (round-4 interim build, 2026-09-11) put 55.3% of the main thread's samples in
 * `MimirShelfBackgroundBitmapCache.getOrBuild` -> `bakeShelfScene`, called from `MimirShelfBackground`'s draw. The fix
 * keeps drawing the previous raster while the new palette's raster bakes on `Dispatchers.Default`, then CUTS to it.
 * Pinned here:
 *
 *  1. the three-way decision a cutting [MimirSceneBackdropSwap] (`crossfadeMillis = 0`) makes -- bake synchronously,
 *     draw the previous raster and bake off the UI thread, or draw a cached raster at once -- and that the Bottom's
 *     default swap still crossfades;
 *  2. [runOffThreadShelfBake]: a finished raster lands on the calling (UI) thread with its measured duration, and a bake
 *     cancelled mid-raster is released and never lands;
 *  3. the `MimirShelfBake` log line the device pass reads.
 *
 * Plain JUnit, strings standing in for rasters. What a stale bake may displace in the real cache is
 * `MimirShelfBackgroundStaleBakeTest`; that the draw is wired to all of this is `MimirShelfBackgroundBakeWiringTest`.
 * What a switch looks like on the Thor is device-only.
 */
class MimirShelfBackgroundPaletteBakeTest {

    private data class Key(val geometry: Int, val palette: String)

    private val paletteOnly: (Key, Key) -> Boolean = { from, to ->
        from.geometry == to.geometry && from.palette != to.palette
    }
    private val nothingCached: (Key) -> String? = { null }

    private val default = Key(geometry = 1, palette = "default")
    private val mani = Key(geometry = 1, palette = "mani")
    private val resizedMani = Key(geometry = 2, palette = "mani")

    // ── the decision: bake now, bake off-thread behind the previous raster, or draw a cached one ───────────────────

    @Test
    fun `a surface's first bake stays synchronous so a cold start never draws an empty frame`() {
        val swap = MimirSceneBackdropSwap<Key, String>(crossfadeMillis = 0)
        assertEquals(MimirSceneBackdropStep.BAKE_NOW, swap.request(default, paletteOnly, nothingCached))
        swap.showNow(default, "default-raster")

        assertEquals(MimirSceneBackdropStep.DRAW, swap.request(default, paletteOnly, nothingCached))
        assertEquals("default-raster", swap.shown)
    }

    @Test
    fun `a palette-only miss keeps drawing the previous raster and bakes off the UI thread exactly once`() {
        val swap = cuttingSwap()
        assertEquals(MimirSceneBackdropStep.BAKE_OFF_THREAD, swap.request(mani, paletteOnly, nothingCached))
        // Any redraw re-runs the cache block before the bake returns: no second bake, still the previous raster alone.
        assertEquals(MimirSceneBackdropStep.DRAW, swap.request(mani, paletteOnly, nothingCached))
        assertEquals("default-raster", swap.shown)
        assertNull(swap.outgoing)
        assertEquals(mani, swap.pendingKey)
    }

    @Test
    fun `a palette-only hit cuts straight to the cached raster with no crossfade`() {
        val swap = cuttingSwap()
        assertEquals(
            "a cached palette on Main is a one-frame cut, never a crossfade",
            MimirSceneBackdropStep.DRAW,
            swap.request(mani, paletteOnly) { key -> if (key == mani) "mani-raster" else null },
        )
        assertEquals("mani-raster", swap.shown)
        assertEquals(mani, swap.shownKey)
        assertNull("a cut leaves nothing fading out", swap.outgoing)
        assertNull(swap.pendingKey)
        assertEquals(1f, swap.incomingAlpha(0L), 0f)
    }

    @Test
    fun `a landed bake cuts in at full weight with no crossfade`() {
        val swap = cuttingSwap()
        swap.request(mani, paletteOnly, nothingCached)

        assertTrue(swap.landed(mani, "mani-raster"))
        assertEquals("mani-raster", swap.shown)
        assertNull("the previous raster is dropped at once, not faded out", swap.outgoing)
        assertEquals(1f, swap.incomingAlpha(0L), 0f)
        assertEquals(MimirSceneBackdropStep.DRAW, swap.request(mani, paletteOnly, nothingCached))
    }

    @Test
    fun `anything but a palette change still bakes synchronously and orphans the pending bake`() {
        val swap = cuttingSwap()
        swap.request(mani, paletteOnly, nothingCached)

        assertEquals(MimirSceneBackdropStep.BAKE_NOW, swap.request(resizedMani, paletteOnly, nothingCached))
        assertNull(swap.pendingKey)
        swap.showNow(resizedMani, "resized-mani-raster")
        assertFalse(swap.landed(mani, "mani-raster"))
        assertEquals("resized-mani-raster", swap.shown)
    }

    @Test
    fun `the Bottom's default swap still crossfades`() {
        val swap = MimirSceneBackdropSwap<Key, String>().also {
            it.request(default, paletteOnly, nothingCached)
            it.showNow(default, "default-raster")
        }
        assertEquals(MimirSceneBackdropStep.CROSSFADE, swap.request(mani, paletteOnly) { "mani-raster" })
        assertEquals("default-raster", swap.outgoing)
    }

    // ── runOffThreadShelfBake ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a finished bake lands on the calling thread with its measured duration`() {
        val bakedOn = CopyOnWriteArrayList<Thread>()
        val landedOn = CopyOnWriteArrayList<Thread>()
        val reports = CopyOnWriteArrayList<Pair<MimirSceneBakeOutcome, Long?>>()
        lateinit var callingThread: Thread
        runBlocking {
            callingThread = Thread.currentThread()
            runOffThreadShelfBake(
                bakeContext = Dispatchers.Default,
                bake = {
                    bakedOn += Thread.currentThread()
                    "mani-raster"
                },
                land = { raster ->
                    assertEquals("mani-raster", raster)
                    landedOn += Thread.currentThread()
                    MimirSceneBakeOutcome.INSERTED
                },
                release = { raster -> error("a landed raster must not be released: $raster") },
                report = { outcome, bakeMillis -> reports += outcome to bakeMillis },
            )
        }
        assertEquals("the cache insert happens back on the calling thread", listOf(callingThread), landedOn.toList())
        assertFalse("the raster must not bake on the calling thread", bakedOn.single() === callingThread)
        val (outcome, bakeMillis) = reports.single()
        assertEquals(MimirSceneBakeOutcome.INSERTED, outcome)
        assertNotNull("the log line names the bake's duration", bakeMillis)
        assertTrue(bakeMillis!! >= 0L)
    }

    @Test
    fun `a bake cancelled mid-raster is released and never lands`() {
        val bakeStarted = CountDownLatch(1)
        val finishBake = CountDownLatch(1)
        val released = CopyOnWriteArrayList<String>()
        val landed = CopyOnWriteArrayList<String>()
        val reports = CopyOnWriteArrayList<MimirSceneBakeOutcome>()
        runBlocking {
            // UNDISPATCHED runs up to the hop onto Default now, so blocking this thread below cannot starve the start.
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                runOffThreadShelfBake(
                    bakeContext = Dispatchers.Default,
                    bake = {
                        bakeStarted.countDown()
                        finishBake.await(5, TimeUnit.SECONDS)
                        "mani-raster"
                    },
                    land = { raster ->
                        landed += raster
                        MimirSceneBakeOutcome.INSERTED
                    },
                    release = { raster -> released += raster },
                    report = { outcome, _ -> reports += outcome },
                )
            }
            assertTrue("the bake never started", bakeStarted.await(5, TimeUnit.SECONDS))
            // The surface leaves composition while the raster is still painting; the painter cannot be interrupted.
            job.cancel()
            finishBake.countDown()
            job.join()
        }
        assertTrue("a cancelled bake must never publish", landed.isEmpty())
        assertEquals("the finished raster is released, not left for the GC", listOf("mani-raster"), released.toList())
        assertEquals(listOf(MimirSceneBakeOutcome.CANCELLED), reports.toList())
    }

    // ── the device-pass log line ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the log line names the palette the duration and the outcome`() {
        val key = shelfKey(MimirThemePalette.Mani)
        assertEquals(
            "off-thread bake palette=midnight size=1920x1080 clouds=false bakeMs=183 outcome=inserted ground=none",
            mimirShelfBakeLogMessage(key, MimirSceneBakeOutcome.INSERTED, bakeMillis = 183L),
        )
        assertEquals(
            "off-thread bake palette=midnight size=1920x1080 clouds=false bakeMs=- outcome=cancelled ground=none",
            mimirShelfBakeLogMessage(key, MimirSceneBakeOutcome.CANCELLED, bakeMillis = null),
        )
        assertEquals("stale", MimirSceneBakeOutcome.STALE.logName)
    }

    private fun cuttingSwap(): MimirSceneBackdropSwap<Key, String> =
        MimirSceneBackdropSwap<Key, String>(crossfadeMillis = 0).also { swap ->
            swap.request(default, paletteOnly, nothingCached)
            swap.showNow(default, "default-raster")
        }

    private fun shelfKey(palette: MimirThemePalette): MimirShelfBackgroundCacheKey = MimirShelfBackgroundCacheKey(
        size = Size(1920f, 1080f),
        density = 1.5f,
        layoutDirection = LayoutDirection.Ltr,
        palette = palette,
        showAtmosphericClouds = false,
    )
}

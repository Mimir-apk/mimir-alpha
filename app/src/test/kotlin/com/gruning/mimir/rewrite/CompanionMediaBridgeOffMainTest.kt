package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The media bridge's two main-looper costs at each other-player track change (audit B-63a, M-14, M-17a, RC-44): scaling a session's artwork (`Bitmap.createScaledBitmap` of an album
 * cover) and reading the player's app label (two binder calls into the package manager). The scale now runs on the default dispatcher and the track is published from there once
 * the art is ready (title and art together), and each package's label is read once.
 *
 * The bridge itself needs a media session server that Robolectric does not have, so the seams are tested: [CompanionArtworkScaler] (what the bridge's `publish` waits for) and
 * [CompanionAppLabelCache], plus a source pin that the bridge uses them and the main-looper scale is gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CompanionMediaBridgeOffMainTest {

    private val mainThread: Thread get() = Looper.getMainLooper().thread

    private fun cover(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    @Test
    fun `the scale and the publish that follows it run off the main looper, and the caller is not held`() {
        val big = cover(1024, 768)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val scaleThread = AtomicReference<Thread>()
        val deliverThread = AtomicReference<Thread>()
        val result = AtomicReference<Bitmap?>()
        val scaler = CompanionArtworkScaler(
            scope = CoroutineScope(Dispatchers.Default),
            scale = { source, maxPx ->
                scaleThread.set(Thread.currentThread())
                // Held until the test lets go: a request that scaled on the caller's thread could never return before this.
                release.await(5, TimeUnit.SECONDS)
                scaleCompanionArtwork(source, maxPx)
            },
            deliver = { _, scaled ->
                deliverThread.set(Thread.currentThread())
                result.set(scaled)
                delivered.countDown()
            },
        )
        assertTrue("1024 px is over the 512 px bound", scaler.needsScaling(big))
        scaler.request(big)
        // Back on the caller's thread with the scale still held: the main looper was not asked to do it.
        assertEquals("nothing delivered while the scale is held", 1L, delivered.count)
        release.countDown()
        assertTrue("delivered", delivered.await(5, TimeUnit.SECONDS))
        assertNotSame("the scale ran off the main thread", mainThread, scaleThread.get())
        assertNotSame("and so did the publish that follows it", mainThread, deliverThread.get())
        val scaled = result.get()
        assertNotNull(scaled)
        assertEquals("the long edge is the bound", 512, maxOf(scaled!!.width, scaled.height))
        assertEquals("with its proportions", 384, minOf(scaled.width, scaled.height))
    }

    @Test
    fun `art within the bound is not scaled at all, and a scale that fails delivers none`() {
        val scaler = CompanionArtworkScaler(scope = CoroutineScope(Dispatchers.Default), deliver = { _, _ -> })
        assertFalse(scaler.needsScaling(cover(512, 300)))
        assertTrue(scaler.needsScaling(cover(513, 10)))
        val small = cover(100, 100)
        assertSame("a bitmap already within the bound comes back as itself", small, scaleCompanionArtwork(small, 512))

        val delivered = CountDownLatch(1)
        val result = AtomicReference<Bitmap?>(cover(1, 1))
        CompanionArtworkScaler(
            scope = CoroutineScope(Dispatchers.Default),
            scale = { _, _ -> error("out of memory, say") },
            deliver = { _, scaled ->
                result.set(scaled)
                delivered.countDown()
            },
        ).request(cover(2000, 1000))
        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        assertNull("a failed scale is a missing cover, not a crash", result.get())
    }

    @Test
    fun `a package's label is read once, and a failed read is asked again`() {
        val reads = AtomicInteger()
        val installed = mutableSetOf<String>()
        val cache = CompanionAppLabelCache { pkg -> reads.incrementAndGet(); if (pkg in installed) "Label of $pkg" else null }
        assertEquals("a package that cannot be read falls back to its name", "org.example.player", cache.labelFor("org.example.player"))
        assertEquals("and is asked again next time", "org.example.player", cache.labelFor("org.example.player"))
        assertEquals(2, reads.get())
        installed += "org.example.player"
        assertEquals("Label of org.example.player", cache.labelFor("org.example.player"))
        repeat(5) { assertEquals("Label of org.example.player", cache.labelFor("org.example.player")) }
        assertEquals("one read for the label that was found, however many track changes follow", 3, reads.get())
        cache.clear()
        cache.labelFor("org.example.player")
        assertEquals("a cleared cache reads again", 4, reads.get())
    }

    /**
     * The bridge's publish as the binder hands it art: `getMetadata()` gives a NEW Bitmap on every call (no client cache), so each publish reads a distinct, equal-size bitmap.
     * [publish] is `CompanionArtworkState.resolve` then "return while the scale is pending, else publish the value"; the scaler's answer calls `scaled` and, when accepted,
     * publishes again, exactly as `onArtworkScaled` does. [cap] ends a loop that would never end.
     */
    private class PublishLoop(sameArt: (Bitmap, String?, Bitmap, String?) -> Boolean, private val cap: Int = 1_000) {
        private val lock = Any()
        val scales = AtomicInteger()
        val published = AtomicInteger()
        val dropped = AtomicInteger()
        val done = CountDownLatch(1)
        @Volatile var art: Bitmap? = null
        private val key = "session" to "Artist - Title"
        private val state: CompanionArtworkState
        private val scaler: CompanionArtworkScaler

        init {
            scaler = CompanionArtworkScaler(
                scope = CoroutineScope(Dispatchers.Default),
                scale = { source, px -> scales.incrementAndGet(); scaleCompanionArtwork(source, px) },
                deliver = { source, scaledBitmap ->
                    synchronized(lock) {
                        if (state.scaled(source, scaledBitmap)) {
                            if (scales.get() >= cap) done.countDown() else publish()
                        } else {
                            dropped.incrementAndGet()
                            done.countDown()
                        }
                    }
                },
            )
            state = CompanionArtworkState(needsScaling = scaler::needsScaling, requestScale = scaler::request, sameArt = sameArt)
        }

        val pending: Boolean get() = synchronized(lock) { state.pending }

        /** One publish: a freshly read bitmap of [w] x [h] (with [uri] when the session names the art). */
        fun publish(w: Int = 1024, h: Int = 768, uri: String? = null, trackKey: Any = key) {
            synchronized(lock) {
                state.resolve(trackKey, Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888), uri)
                if (state.pending) return
                art = state.value
                published.incrementAndGet()
                if (art != null) done.countDown()
            }
        }
    }

    private fun longEdge(b: Bitmap) = maxOf(b.width, b.height)

    @Test
    fun `two distinct equal-size bitmaps for the same track give exactly one scale and a published track with its art`() {
        val loop = PublishLoop(::companionMediaArtworkIsSameArt)
        loop.publish()
        assertTrue("the track waits for its art", loop.pending)
        assertEquals("and is not published yet", 0, loop.published.get())
        assertTrue("the scale lands and the track is published", loop.done.await(5, TimeUnit.SECONDS))
        assertEquals("the re-read bitmap is a second distinct object of the same size: it is the same art, so ONE scale", 1, loop.scales.get())
        assertEquals("the answer was accepted, not dropped", 0, loop.dropped.get())
        assertEquals("and the track was published once", 1, loop.published.get())
        val art = loop.art
        assertNotNull("with its art", art)
        assertEquals("scaled to the bound", 512, longEdge(art!!))
        // A play or pause after the track reads another new bitmap: still no scale, and the same art is published again.
        loop.publish()
        assertEquals(1, loop.scales.get())
        assertEquals(2, loop.published.get())
        assertSame(art, loop.art)
    }

    @Test
    fun `control - by reference, as before, every read is new art, so the scale never ends and the track is never published`() {
        val loop = PublishLoop(sameArt = { held, _, candidate, _ -> held === candidate }, cap = 4)
        loop.publish()
        assertTrue(loop.done.await(5, TimeUnit.SECONDS))
        assertTrue("it keeps asking for a new scale: ${loop.scales.get()}", loop.scales.get() >= 4)
        assertEquals("and the track is never published", 0, loop.published.get())
    }

    @Test
    fun `a match leaves the source pointing at the bitmap handed to the scaler, so its answer is accepted`() {
        val asked = ArrayList<Bitmap>()
        val state = CompanionArtworkState(needsScaling = { true }, requestScale = { asked += it })
        val first = cover(1024, 768)
        val second = cover(1024, 768)
        state.resolve("t", first, null)
        state.resolve("t", second, null)
        assertEquals("the second read is the same art: no second request", 1, asked.size)
        assertSame(first, asked[0])
        val scaled = cover(512, 384)
        assertFalse("an answer for a bitmap nobody handed to the scaler is dropped", state.scaled(second, scaled))
        assertTrue("the answer for the one that was handed over is accepted", state.scaled(first, scaled))
        assertSame(scaled, state.value)
        assertFalse(state.pending)
        state.resolve("t", cover(1024, 768), null)
        assertSame("and later reads keep the scaled copy", scaled, state.value)
        assertEquals(1, asked.size)
    }

    @Test
    fun `the art's URI names it when the session gives one, else the same size does, and a new track starts again`() {
        val asked = ArrayList<Bitmap>()
        val state = CompanionArtworkState(needsScaling = { true }, requestScale = { asked += it })
        state.resolve("t", cover(1024, 768), "content://art/1")
        state.resolve("t", cover(900, 900), "content://art/1")
        assertEquals("the same URI is the same art, whatever size the read gives", 1, asked.size)
        state.resolve("t", cover(1024, 768), "content://art/2")
        assertEquals("another URI is other art, even at the same size", 2, asked.size)
        state.resolve("t", cover(1024, 768), null)
        assertEquals("no URI on one side: the same size is the same art", 2, asked.size)
        state.resolve("t", cover(800, 800), null)
        assertEquals("another size is other art", 3, asked.size)
        state.resolve("other track", cover(800, 800), null)
        assertEquals("another track starts again", 4, asked.size)
        state.resolve("other track", null, null)
        assertNull("a track with no bitmap art holds none", state.value)
        assertFalse(state.pending)
    }

    @Test
    fun `small art is published as it is and is not churned by a re-read`() {
        val state = CompanionArtworkState(needsScaling = { false }, requestScale = { error("never asked") })
        val first = cover(300, 300)
        state.resolve("t", first, null)
        assertSame(first, state.value)
        assertFalse(state.pending)
        state.resolve("t", cover(300, 300), null)
        assertSame("the same art keeps its first bitmap", first, state.value)
        state.reset()
        assertNull(state.value)
    }

    @Test
    fun `the bridge waits for the off-main scale and no longer scales on the looper`() {
        val dir = listOf(File("app/src/main/kotlin/com/gruning/mimir/rewrite"), File("src/main/kotlin/com/gruning/mimir/rewrite")).first(File::isDirectory)
        val source = File(dir, "CompanionMediaSessionBridge.kt").readText()
        assertFalse("the main-looper scale is gone", source.contains("boundArtwork"))
        assertFalse("and so are the inline label lookups", source.contains("getApplicationLabel(\n                appContext?.packageManager?.getApplicationInfo(controller.packageName"))
        val publish = source.substringAfter("private fun publish(controller: MediaController) {")
        assertTrue("a track whose art is being scaled is published when it lands", publish.contains("if (artwork.pending) return"))
        val landed = source.substringAfter("private fun onArtworkScaled(").substringBefore("private fun publish(")
        assertTrue("the landing publishes the selected player", landed.contains("selected?.let(::publish)"))
        assertTrue("and drops an answer for art it no longer shows", landed.contains("if (!artwork.scaled(source, scaled)) return"))
        assertTrue("a re-read bitmap is matched to the art by its URI or its size", source.contains("artwork.resolve(key, bitmap,"))
        assertFalse("never by the reference of a bitmap the binder makes anew on every read", source.contains("artworkSource !== bitmap"))
    }
}

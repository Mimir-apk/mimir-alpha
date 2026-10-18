package com.gruning.mimir.tweaker

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTelemetrySnapshotCacheTest {
    private fun frameCountingSampler(
        clock: () -> Long,
        frameReads: AtomicLong,
        cpuUsage: CpuUsageSampler = CpuUsageSampler(
            readProcStat = { "cpu 1 0 1 7 1 0 0 0" },
        ),
    ) = LivePerformanceTelemetrySampler(
        foregroundPackage = { "org.ppsspp.ppsspp" },
        frameTelemetry = object : FrameTelemetryPort {
            override fun read(packageName: String): FrameTelemetryReading {
                frameReads.incrementAndGet()
                return FrameTelemetryReading(FrameTelemetryStatus.AVAILABLE)
            }
        },
        cpuUsage = cpuUsage,
        elapsedRealtimeMs = clock,
    )

    private fun snapshot() = TelemetrySnapshot(
        cpuCurrentKhz = emptyMap(),
        cpuMinKhz = emptyMap(),
        cpuMaxKhz = emptyMap(),
        gpuClockHz = null,
        gpuBusyPercent = null,
        gpuMinPowerLevel = null,
        gpuMaxPowerLevel = null,
        fanDuty = null,
        fanPeriod = null,
        fanRpm = null,
        cpuTempC = null,
        gpuTempC = null,
        batteryLevelPercent = null,
        batteryCurrentUa = null,
        batteryVoltageMv = null,
        batteryPowerMw = null,
        batteryTempDeciC = null,
        batteryStatus = null,
        thermalStatus = null,
    )

    @Test
    fun `fresh sample is served from cache without re-running the frame pipeline`() {
        val clock = AtomicLong(10_000L)
        val frameReads = AtomicLong(0L)
        val sampler = frameCountingSampler(clock::get, frameReads)
        val cache = LiveTelemetrySnapshotCache(
            compose = { ComposedLiveTelemetry(snapshot(), sampler.sample()) },
            elapsedRealtimeMs = clock::get,
        )

        val first = cache.sample()
        assertEquals(1L, frameReads.get())
        assertEquals(10_000L, first.live.sampledAtElapsedRealtimeMs)

        // 700 ms later: within the 750 ms freshness window. Served from cache, pipeline not
        // re-run, and the ORIGINAL stamp is preserved so the consumer can see the true age.
        clock.set(10_700L)
        val cached = cache.sample()
        assertEquals(1L, frameReads.get())
        assertSame(first, cached)
        assertEquals(10_000L, cached.live.sampledAtElapsedRealtimeMs)

        // 760 ms after composition: stale, so the next caller composes a fresh sweep.
        clock.set(10_760L)
        val refreshed = cache.sample()
        assertEquals(2L, frameReads.get())
        assertEquals(10_760L, refreshed.live.sampledAtElapsedRealtimeMs)
    }

    @Test
    fun `interleaved second consumer does not corrupt the first consumer's cpu delta window`() {
        // Synthetic load: 100% busy during 0..400 ms, 0% busy during 400..1000 ms. The truthful
        // full-window CPU% for a 0..1000 ms delta is therefore 40%. Before the cache, a second
        // surface polling at 400 ms consumed the shared sampler's delta and reset the window, so
        // the 1000 ms caller measured only 400..1000 ms and displayed 0%.
        val clock = AtomicLong(0L)
        val frameReads = AtomicLong(0L)
        val procStat = { now: Long ->
            when (now) {
                0L -> "cpu 100 0 100 700 100 0 0 0"
                400L -> "cpu 400 0 200 700 100 0 0 0"
                1000L -> "cpu 500 0 100 1300 100 0 0 0"
                else -> error("unexpected /proc/stat read at $now")
            }
        }
        val sampler = frameCountingSampler(
            clock = clock::get,
            frameReads = frameReads,
            cpuUsage = CpuUsageSampler(
                readProcStat = { procStat(clock.get()) },
                elapsedRealtimeMs = clock::get,
            ),
        )
        val cache = LiveTelemetrySnapshotCache(
            compose = { ComposedLiveTelemetry(snapshot(), sampler.sample()) },
            elapsedRealtimeMs = clock::get,
        )

        // Overlay's first poll warms the delta.
        assertNull(cache.sample().live.cpuBusyPercent)

        // The rail opens and polls 400 ms later: served from cache; the sampler is NOT re-read,
        // so the overlay's measurement window stays intact.
        clock.set(400L)
        cache.sample()

        // Overlay's next poll at 1000 ms: the delta window is the full 0..1000 ms.
        clock.set(1000L)
        assertEquals(40, cache.sample().live.cpuBusyPercent)
    }

    @Test
    fun `refresh is single-flight so a concurrent caller shares the sweep instead of adding one`() {
        val clock = AtomicLong(5_000L)
        val composeCalls = AtomicLong(0L)
        val composeEntered = CountDownLatch(1)
        val releaseCompose = CountDownLatch(1)
        val frameReads = AtomicLong(0L)
        val sampler = frameCountingSampler(clock::get, frameReads)
        val cache = LiveTelemetrySnapshotCache(
            compose = {
                composeCalls.incrementAndGet()
                composeEntered.countDown()
                assertTrue(releaseCompose.await(2, TimeUnit.SECONDS))
                ComposedLiveTelemetry(snapshot(), sampler.sample())
            },
            elapsedRealtimeMs = clock::get,
        )

        val firstResult = AtomicReference<ComposedLiveTelemetry>()
        val secondResult = AtomicReference<ComposedLiveTelemetry>()
        val firstCaller = thread(name = "caller-1") { firstResult.set(cache.sample()) }
        assertTrue(composeEntered.await(2, TimeUnit.SECONDS))
        val secondCaller = thread(name = "caller-2") { secondResult.set(cache.sample()) }
        // The second caller is provably inside sample() while the first composes; release and
        // require that exactly one sweep ran and both callers received its result.
        releaseCompose.countDown()
        firstCaller.join(2_000L)
        secondCaller.join(2_000L)

        assertEquals(1L, composeCalls.get())
        assertSame(firstResult.get(), secondResult.get())
        assertEquals(5_000L, firstResult.get().live.sampledAtElapsedRealtimeMs)
    }
}

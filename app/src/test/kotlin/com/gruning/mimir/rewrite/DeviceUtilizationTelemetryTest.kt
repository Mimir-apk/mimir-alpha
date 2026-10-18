package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceUtilizationTelemetryTest {
    @Test
    fun `aggregate CPU warms then excludes idle iowait without subtracting guest twice`() {
        val reads = ArrayDeque(
            listOf(
                "cpu 100 20 30 400 50 10 5 5 20 4",
                "cpu 160 30 50 500 70 20 10 10 30 6",
            ),
        )
        var now = 1_000L
        val sampler = DeviceUtilizationSampler(
            readProcStat = { reads.removeFirstOrNull() },
            readGpu = { null },
            elapsedRealtimeMs = { now },
        )

        assertNull(sampler.sample().cpuBusyPercent)
        now += 1_000L
        // Raw delta = 230; idle+iowait = 120, so busy = 110 / 230 = 47.8%, rounded to 48.
        // The optional guest delta is already included in user/nice and must not be subtracted.
        assertEquals(48, sampler.sample().cpuBusyPercent)
        assertEquals(
            parseDeviceCpuCounters("cpu 160 30 50 500 70 20 10 10"),
            parseDeviceCpuCounters("cpu 160 30 50 500 70 20 10 10 30 6"),
        )
    }

    @Test
    fun `CPU malformed input clears baseline and a later pair rewarms`() {
        val reads = ArrayDeque(
            listOf(
                "cpu 100 0 100 700 100 0 0 0 0 0",
                "not cpu",
                "cpu 1 0 1 7 1 0 0 0 0 0",
                "cpu 11 0 1 17 1 0 0 0 0 0",
            ),
        )
        var now = 0L
        val sampler = DeviceUtilizationSampler(
            readProcStat = { reads.removeFirstOrNull() },
            readGpu = { null },
            elapsedRealtimeMs = { now },
        )

        assertNull(sampler.sample().cpuBusyPercent)
        now += 1_000L
        assertNull(sampler.sample().cpuBusyPercent)
        now += 1_000L
        assertNull(sampler.sample().cpuBusyPercent)
        now += 1_000L
        assertEquals(50, sampler.sample().cpuBusyPercent)
    }

    @Test
    fun `CPU counter regression reseeds even when aggregate total increases`() {
        val reads = ArrayDeque(
            listOf(
                "cpu 100 0 100 700 100 0 0 0",
                // Aggregate total increases, but the system counter regresses from 100 to 90.
                "cpu 101 0 90 800 100 0 0 0",
                "cpu 111 0 100 810 100 0 0 0",
            ),
        )
        var now = 0L
        val sampler = DeviceUtilizationSampler(
            readProcStat = { reads.removeFirstOrNull() },
            readGpu = { null },
            elapsedRealtimeMs = { now },
        )

        assertNull(sampler.sample().cpuBusyPercent)
        now += 1_000L
        assertNull(sampler.sample().cpuBusyPercent)
        now += 1_000L
        assertEquals(67, sampler.sample().cpuBusyPercent)
    }

    @Test
    fun `CPU reader exception clears baseline`() {
        var calls = 0
        var now = 0L
        val sampler = DeviceUtilizationSampler(
            readProcStat = {
                calls += 1
                when (calls) {
                    1 -> "cpu 100 0 100 700 100 0 0 0"
                    2 -> error("reader failed")
                    else -> "cpu 111 0 100 810 100 0 0 0"
                }
            },
            readGpu = { null },
            elapsedRealtimeMs = { now },
        )

        assertNull(sampler.sample().cpuBusyPercent)
        now += 1_000L
        assertNull(sampler.sample().cpuBusyPercent)
        now += 1_000L
        assertNull(sampler.sample().cpuBusyPercent)
    }

    @Test
    fun `CPU gap beyond five seconds is unavailable and next sample rewarms`() {
        val reads = ArrayDeque(
            listOf(
                "cpu 100 0 100 700 100 0 0 0",
                "cpu 101 0 101 704 100 0 0 0",
                "cpu 111 0 101 714 100 0 0 0",
            ),
        )
        var now = 0L
        val sampler = DeviceUtilizationSampler(
            readProcStat = { reads.removeFirstOrNull() },
            readGpu = { null },
            elapsedRealtimeMs = { now },
        )

        assertNull(sampler.sample().cpuBusyPercent)
        now = 5_001L
        assertNull(sampler.sample().cpuBusyPercent)
        now = 6_001L
        assertEquals(50, sampler.sample().cpuBusyPercent)
    }

    @Test
    fun `CPU nonpositive clock interval is unavailable and later work recovers`() {
        val reads = ArrayDeque(
            listOf(
                "cpu 100 0 100 700 100 0 0 0",
                "cpu 110 0 100 710 100 0 0 0",
                "cpu 120 0 100 720 100 0 0 0",
            ),
        )
        var now = 1_000L
        val sampler = DeviceUtilizationSampler(
            readProcStat = { reads.removeFirstOrNull() },
            readGpu = { null },
            elapsedRealtimeMs = { now },
        )

        assertNull(sampler.sample().cpuBusyPercent)
        now = 1_000L
        assertNull(sampler.sample().cpuBusyPercent)
        now = 2_000L
        assertEquals(50, sampler.sample().cpuBusyPercent)
    }

    @Test
    fun `GPU source priority stops after first valid source`() {
        val paths = mutableListOf<String>()
        val sample = DeviceUtilizationSampler(
            readProcStat = { null },
            readGpu = { path ->
                paths += path
                when (path) {
                    DeviceGpuPaths.BUSY_PERCENTAGE -> " 42 %"
                    else -> error("lower-priority source must not be read")
                }
            },
            elapsedRealtimeMs = { 123L },
        ).sample()

        assertEquals(42, sample.gpuBusyPercent)
        assertEquals(listOf(DeviceGpuPaths.BUSY_PERCENTAGE), paths)
    }

    @Test
    fun `GPU falls back from malformed preferred percent to averaged load then interval`() {
        val paths = mutableListOf<String>()
        val values = mapOf(
            DeviceGpuPaths.BUSY_PERCENTAGE to "garbage",
            DeviceGpuPaths.LOAD to "61%",
            DeviceGpuPaths.BUSY_INTERVAL to "9 10",
        )
        val gpu = readGpuBusyPercent(read = { path ->
            paths += path
            values[path]
        })

        assertEquals(61, gpu)
        assertEquals(listOf(DeviceGpuPaths.BUSY_PERCENTAGE, DeviceGpuPaths.LOAD), paths)
        assertEquals(38, parseGpuBusyInterval("3 8"))

        paths.clear()
        assertEquals(
            38,
            readGpuBusyPercent(read = { path ->
                paths += path
                mapOf(
                    DeviceGpuPaths.BUSY_PERCENTAGE to "bad",
                    DeviceGpuPaths.LOAD to "bad",
                    DeviceGpuPaths.BUSY_INTERVAL to "3 8",
                )[path]
            }),
        )
        assertEquals(
            listOf(DeviceGpuPaths.BUSY_PERCENTAGE, DeviceGpuPaths.LOAD, DeviceGpuPaths.BUSY_INTERVAL),
            paths,
        )
    }

    @Test
    fun `GPU rejects malformed and unsafe interval arithmetic`() {
        assertNull(parseGpuPercent("101"))
        assertNull(parseGpuPercent("-1%"))
        assertNull(parseGpuBusyInterval("0 0"))
        assertNull(parseGpuBusyInterval("9 8"))
        assertNull(parseGpuBusyInterval("-1 8"))
        assertEquals(100, parseGpuBusyInterval("922337203685477580 922337203685477581"))
        assertTrue(parseGpuBusyInterval("1 3") in 0..100)
        assertNull(parseDeviceCpuCounters("cpu ${Long.MAX_VALUE} 1 0 0 0 0 0 0"))
    }
}

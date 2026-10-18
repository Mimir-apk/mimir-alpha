package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LivePerformanceTelemetryTest {
    @Test
    fun `cpu sampler warms then returns bounded aggregate usage`() {
        val reads = ArrayDeque(
            listOf(
                "cpu  100 0 100 700 100 0 0 0 0 0",
                "cpu  140 0 120 760 100 0 0 0 0 0",
            ),
        )
        var now = 0L
        val sampler = CpuUsageSampler(
            readProcStat = { reads.removeFirstOrNull() },
            elapsedRealtimeMs = { now },
        )

        assertNull(sampler.samplePercent())
        now += 1_000L
        assertEquals(50, sampler.samplePercent())
        val withGuest = parseCpuTimeSample("cpu 140 0 120 760 100 0 0 0 40 8")
        val withoutGuest = parseCpuTimeSample("cpu 140 0 120 760 100 0 0 0")
        assertEquals(withoutGuest?.total to withoutGuest?.idle, withGuest?.total to withGuest?.idle)
    }

    @Test
    fun `cpu sampler rejects a counter reset instead of fabricating zero`() {
        val reads = ArrayDeque(
            listOf(
                "cpu  100 0 100 700 100 0 0 0 0 0",
                "cpu  1 0 1 7 1 0 0 0 0 0",
            ),
        )
        var now = 0L
        val sampler = CpuUsageSampler(
            readProcStat = { reads.removeFirstOrNull() },
            elapsedRealtimeMs = { now },
        )

        assertNull(sampler.samplePercent())
        now += 1_000L
        assertNull(sampler.samplePercent())
    }

    @Test
    fun `cpu sampler reseeds after one counter regresses even when total rises`() {
        val reads = ArrayDeque(
            listOf(
                "cpu 100 0 100 700 100 0 0 0",
                "cpu 101 0 90 800 100 0 0 0",
                "cpu 111 0 100 810 100 0 0 0",
            ),
        )
        var now = 0L
        val sampler = CpuUsageSampler(
            readProcStat = { reads.removeFirstOrNull() },
            elapsedRealtimeMs = { now },
        )

        assertNull(sampler.samplePercent())
        now += 1_000L
        assertNull(sampler.samplePercent())
        now += 1_000L
        assertEquals(67, sampler.samplePercent())
    }

    @Test
    fun `cpu sampler rejects stale and nonpositive intervals then recovers`() {
        val reads = ArrayDeque(
            listOf(
                "cpu 100 0 100 700 100 0 0 0",
                "cpu 110 0 100 710 100 0 0 0",
                "cpu 120 0 100 720 100 0 0 0",
                "cpu 130 0 100 730 100 0 0 0",
            ),
        )
        var now = 0L
        val sampler = CpuUsageSampler(
            readProcStat = { reads.removeFirstOrNull() },
            elapsedRealtimeMs = { now },
        )

        assertNull(sampler.samplePercent())
        now = 5_001L
        assertNull(sampler.samplePercent())
        now = 5_001L
        assertNull(sampler.samplePercent())
        now = 6_001L
        assertEquals(50, sampler.samplePercent())
    }

    @Test
    fun `cpu sampler swallows reader failure and rejects overflowing counters`() {
        var call = 0
        var now = 0L
        val sampler = CpuUsageSampler(
            readProcStat = {
                call += 1
                when (call) {
                    1 -> "cpu 100 0 100 700 100 0 0 0"
                    2 -> error("reader failed")
                    else -> "cpu ${Long.MAX_VALUE} 0 0 0 0 0 0 0"
                }
            },
            elapsedRealtimeMs = { now },
        )

        assertNull(sampler.samplePercent())
        now += 1_000L
        assertNull(sampler.samplePercent())
        now += 1_000L
        assertNull(sampler.samplePercent())
        assertNull(parseCpuTimeSample("cpu ${Long.MAX_VALUE} ${Long.MAX_VALUE} 0 0 0 0 0 0"))
    }

    @Test
    fun `thermal selection uses hottest classified cpu and gpu sensors only`() {
        val result = classifiedThermalTemperatures(
            listOf(
                ClassifiedThermalZone("cpu-0-0-usr", 35_900),
                ClassifiedThermalZone("cpu-1-0-usr", 42_400),
                ClassifiedThermalZone("gpu-usr", 39_800),
                ClassifiedThermalZone("battery", 88_000),
            ),
        )

        assertEquals(42, result.first)
        assertEquals(39, result.second)
    }

    @Test
    fun `package temperature averages cpu and cpuss zones without weakening hottest safety`() {
        val zones = listOf(
            ClassifiedThermalZone("cpu-0-0-usr", 40_000),
            ClassifiedThermalZone("cpu-1-0-usr", 80_000),
            ClassifiedThermalZone("cpuss-0-usr", 60_000),
            ClassifiedThermalZone("gpu-usr", 95_000),
            ClassifiedThermalZone("battery", 30_000),
        )

        assertEquals(60, classifiedCpuPackageTemperature(zones))
        assertEquals(80, classifiedThermalTemperatures(zones).first)
    }

    @Test
    fun `package temperature uses first available preferred sensor family`() {
        val zones = listOf(
            ClassifiedThermalZone("cpu-0-0-0", 92_000),
            ClassifiedThermalZone("cpu-1-0-0", 61_000),
            ClassifiedThermalZone("cpuss-0-0", 58_000),
            ClassifiedThermalZone("cpuss-0-1", 60_000),
            ClassifiedThermalZone("gpuss-0", 57_000),
        )

        assertEquals(
            60,
            classifiedCpuPackageTemperature(
                zones = zones,
                preferredTypePrefixes = listOf("cpuss-0-1", "cpuss-0"),
            ),
        )
        assertEquals(92, classifiedThermalTemperatures(zones).first)
    }

    @Test
    fun `package temperature falls through ordered sensor prefixes`() {
        val zones = listOf(
            ClassifiedThermalZone("cpu-0-0-0", 92_000),
            ClassifiedThermalZone("cpuss-0-0", 61_000),
            ClassifiedThermalZone("cpuss-0-2", 63_000),
        )

        assertEquals(
            62,
            classifiedCpuPackageTemperature(
                zones = zones,
                preferredTypePrefixes = listOf("cpuss-0-1", "cpuss-0"),
            ),
        )
    }

    @Test
    fun `Odin package authority follows vendor cpuss zero one while hottest CPU stays a safety witness`() {
        val zones = listOf(
            ClassifiedThermalZone("cpuss-0-0", 41_000),
            ClassifiedThermalZone("cpuss-0-1", 43_000),
            ClassifiedThermalZone("cpuss-1-0", 72_000),
            ClassifiedThermalZone("cpu-1-0-0", 46_000),
        )

        assertEquals(
            43,
            classifiedCpuPackageTemperature(
                zones = zones,
                preferredTypePrefixes = listOf("cpuss-0-1", "cpuss-0"),
            ),
        )
        assertEquals(72, classifiedThermalTemperatures(zones).first)
    }

    @Test
    fun `strict Odin fan authority accepts one exact path-backed package sensor`() {
        val winner = strictOdinPackageThermalReading(
            listOf(
                ClassifiedThermalZone("cpu-1-0-0", 94_000, "/sys/class/thermal/thermal_zone1"),
                ClassifiedThermalZone("cpuss-0-1", 58_400, "/sys/class/thermal/thermal_zone23"),
                ClassifiedThermalZone("gpuss-0", 57_000, "/sys/class/thermal/thermal_zone24"),
            ),
        )

        assertEquals(ODIN_PACKAGE_THERMAL_TYPE, winner?.type)
        assertEquals(58_400L, winner?.rawTemperature)
        assertEquals(58, winner?.celsius)
        assertEquals("/sys/class/thermal/thermal_zone23", winner?.path)
    }

    @Test
    fun `strict Odin fan authority rejects fallback duplicate pathless and invalid sensors`() {
        val exact = ClassifiedThermalZone(
            ODIN_PACKAGE_THERMAL_TYPE,
            58_000,
            "/sys/class/thermal/thermal_zone23",
        )

        assertNull(
            strictOdinPackageThermalReading(
                listOf(ClassifiedThermalZone("cpuss-0-0", 58_000, exact.path)),
            ),
        )
        assertNull(strictOdinPackageThermalReading(listOf(exact, exact.copy(path = "zone99"))))
        assertNull(strictOdinPackageThermalReading(listOf(exact.copy(path = ""))))
        assertNull(strictOdinPackageThermalReading(listOf(exact.copy(rawTemperature = 300_000))))
    }

    @Test
    fun `package temperature rejects invalid cpu readings instead of diluting the aggregate`() {
        val zones = listOf(
            ClassifiedThermalZone("cpu-0-0-usr", null),
            ClassifiedThermalZone("cpu-0-1-usr", -40_000),
            ClassifiedThermalZone("cpu-1-0-usr", 300_000),
        )

        assertNull(classifiedCpuPackageTemperature(zones))
    }

    @Test
    fun `thermal winners retain exact zone identity and raw kernel value`() {
        val winners = classifiedThermalZoneWinners(
            listOf(
                ClassifiedThermalZone("cpu-0-0-usr", 39_800),
                ClassifiedThermalZone("cpu-1-0-usr", 42_400),
                ClassifiedThermalZone("kgsl-usr", 40_100),
            ),
        )

        assertEquals("cpu-1-0-usr", winners.first?.type)
        assertEquals(42_400L, winners.first?.rawTemperature)
        assertEquals(42, winners.first?.celsius)
        assertEquals("kgsl-usr", winners.second?.type)
        assertEquals(40_100L, winners.second?.rawTemperature)
    }

    @Test
    fun `freshness stamp is taken after the foreground attestation and before the frame read`() {
        // Each stage advances the fake clock, so the captured stamp proves the exact ordering:
        // package read finishes at 200, the frame pipeline runs 200..900, the CPU read 900..950.
        var clock = 100L
        val sampler = LivePerformanceTelemetrySampler(
            foregroundPackage = {
                clock += 100
                "org.ppsspp.ppsspp"
            },
            frameTelemetry = object : FrameTelemetryPort {
                override fun read(packageName: String): FrameTelemetryReading {
                    clock += 700
                    return FrameTelemetryReading(FrameTelemetryStatus.AVAILABLE)
                }
            },
            cpuUsage = CpuUsageSampler(
                readProcStat = {
                    clock += 50
                    "cpu 1 0 1 7 1 0 0 0"
                },
            ),
            elapsedRealtimeMs = { clock },
        )

        val result = sampler.sample()

        // 200 exactly: after the package attestation — a stamp of 100 would let the FPS-cap
        // mutation guard (attestedLiveFpsForegroundPackage) treat the foreground package as
        // fresher than the attestation really is, loosening a window that must only ever
        // over-reject — and before the frame read, so the shell pipeline's cost (here 700 ms)
        // counts against freshness gates instead of hiding behind a bundle-assembly stamp.
        assertEquals(200L, result.sampledAtElapsedRealtimeMs)
        assertEquals("org.ppsspp.ppsspp", result.foregroundPackageName)
        assertEquals(FrameTelemetryStatus.AVAILABLE, result.frame.status)
    }

    @Test
    fun `live sample does not ask frame port without foreground truth`() {
        var frameReads = 0
        val sampler = LivePerformanceTelemetrySampler(
            foregroundPackage = { null },
            frameTelemetry = object : FrameTelemetryPort {
                override fun read(packageName: String): FrameTelemetryReading {
                    frameReads += 1
                    return FrameTelemetryReading(FrameTelemetryStatus.AVAILABLE)
                }
            },
            cpuUsage = CpuUsageSampler(readProcStat = { "cpu 1 0 1 7 1 0 0 0" }),
            elapsedRealtimeMs = { 123L },
        )

        val result = sampler.sample()

        assertEquals(FrameTelemetryStatus.WORKLOAD_NOT_FOREGROUND, result.frame.status)
        assertEquals(0, frameReads)
        assertEquals(123L, result.sampledAtElapsedRealtimeMs)
    }
}

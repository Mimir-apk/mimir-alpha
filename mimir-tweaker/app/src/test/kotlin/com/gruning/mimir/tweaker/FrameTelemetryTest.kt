package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameTelemetryTest {
    private val packageName = "dev.legacy.eden_emulator"
    private val layerName =
        "SurfaceView[$packageName/org.yuzu.yuzu_emu.activities.EmulationActivity](BLAST)#3989"

    @Test
    fun `resolver selects the unique package SurfaceView BLAST layer`() {
        val output = """
            Background for SurfaceView[$packageName/org.yuzu.yuzu_emu.activities.EmulationActivity]#10
            $packageName/org.yuzu.yuzu_emu.activities.EmulationActivity#11
            $layerName
            SurfaceView[other.game/.MainActivity](BLAST)#99
        """.trimIndent()

        val result = SurfaceFlingerFrameParser.resolveLayer(output, packageName)

        assertEquals(FrameTelemetryStatus.AVAILABLE, result.status)
        assertEquals(layerName, result.layerName)
    }

    @Test
    fun `resolver unwraps Android 15 requested layer state`() {
        val output =
            "RequestedLayerState{SurfaceView[$packageName/" +
                "org.yuzu.yuzu_emu.activities.EmulationActivity](BLAST)#11927 parentId=11926}"

        val result = SurfaceFlingerFrameParser.resolveLayer(output, packageName)

        assertEquals(FrameTelemetryStatus.AVAILABLE, result.status)
        assertEquals(layerName.replace("#3989", "#11927"), result.layerName)
    }

    @Test
    fun `resolver ignores Android 15 container layers and accepts direct package render layer`() {
        val direct = "$packageName/org.yuzu.yuzu_emu.activities.EmulationActivity#12030"
        val output = listOf(
            "RequestedLayerState{ActivityRecord{25c51e9 u0 $packageName/.MainActivity#11780 parentId=11779}",
            "RequestedLayerState{975da70 ActivityRecordInputSink $packageName/.MainActivity#11787 parentId=11780}",
            "RequestedLayerState{3e23c94 $direct parentId=11780}",
        ).joinToString("\n")

        val result = SurfaceFlingerFrameParser.resolveLayer(output, packageName)

        assertEquals(FrameTelemetryStatus.AVAILABLE, result.status)
        assertEquals(direct, result.layerName)
    }

    @Test
    fun `resolver rejects two equally ranked package layers`() {
        val output = "$layerName\n${layerName.replace("#3989", "#3990")}"

        val result = SurfaceFlingerFrameParser.resolveLayer(output, packageName)

        assertEquals(FrameTelemetryStatus.AMBIGUOUS_PACKAGE_LAYER, result.status)
    }

    @Test
    fun `latency parser measures a fresh thirty fps presentation ring`() {
        val firstNs = 9_000_000_000L
        val output = latencyOutput(firstNs, 33_333_333L, 12)
        val lastNs = firstNs + 11 * 33_333_333L

        val result = SurfaceFlingerFrameParser.parseLatency(output, layerName, lastNs + 100_000_000L)

        assertEquals(FrameTelemetryStatus.AVAILABLE, result.status)
        assertEquals(11, result.frameCount)
        assertEquals(30_000, result.averageFpsMilli)
        assertEquals(33_333, result.p50FrameTimeUs)
        assertEquals(33_333, result.p95FrameTimeUs)
        assertEquals(0, result.slow20FpsPercent)
        assertEquals(0, result.slow30FpsPercent)
    }

    @Test
    fun `latency parser discloses the age of the newest frame behind the average`() {
        val firstNs = 9_000_000_000L
        val output = latencyOutput(firstNs, 33_333_333L, 12)
        val lastNs = firstNs + 11 * 33_333_333L

        // SurfaceFlinger tolerates retained history up to 2000 ms old; a reading parsed 1500 ms
        // after the last present must SAY so, or no freshness gate above can see the staleness.
        val result = SurfaceFlingerFrameParser.parseLatency(output, layerName, lastNs + 1_500_000_000L)

        assertEquals(FrameTelemetryStatus.AVAILABLE, result.status)
        assertEquals(1_500, result.latestPresentAgeMs)
        // 11 intervals of 33_333_333 ns = 366_666_663 ns -> 366 ms.
        assertEquals(366, result.windowDurationMs)
    }

    @Test
    fun `latency parser rejects stale presentation timestamps`() {
        val output = latencyOutput(1_000_000_000L, 16_666_666L, 10)

        val result = SurfaceFlingerFrameParser.parseLatency(output, layerName, 5_000_000_000L)

        assertEquals(FrameTelemetryStatus.STALE_LATENCY_DATA, result.status)
    }

    @Test
    fun `latency parser rejects a short malformed ring`() {
        val output = latencyOutput(1_000_000_000L, 16_666_666L, 3)

        val result = SurfaceFlingerFrameParser.parseLatency(output, layerName, 1_100_000_000L)

        assertEquals(FrameTelemetryStatus.MALFORMED_LATENCY_DATA, result.status)
    }

    @Test
    fun `port caches a resolved layer and issues only read commands`() {
        val commands = mutableListOf<String>()
        val firstNs = 9_000_000_000L
        val latency = latencyOutput(firstNs, 33_333_333L, 12)
        val executor = FrameCommandExecutor { command ->
            commands += command
            Result.success(
                when {
                    "--list" in command -> layerName
                    // RP5 PServer exposes only the last physical stdout line. The
                    // production command joins the ring into one semicolon-delimited line.
                    else -> latency.trimEnd().replace('\n', ';')
                },
            )
        }
        val port = PServerFrameTelemetryPort(
            executor = executor,
            pServerAvailable = { true },
            nowNs = { firstNs + 11 * 33_333_333L + 100_000_000L },
        )

        assertEquals(FrameTelemetryStatus.AVAILABLE, port.read(packageName).status)
        assertEquals(FrameTelemetryStatus.AVAILABLE, port.read(packageName).status)

        assertEquals(1, commands.count { "--list" in it })
        assertEquals(2, commands.count { "--latency" in it })
        assertTrue(commands.none { "echo " in it || "chmod " in it || "settings put" in it })
    }

    @Test
    fun `port selects the only fresh layer after emulator activity recreation`() {
        val staleLayer = layerName.replace("#3989", "#3988")
        val firstNs = 9_000_000_000L
        val latency = latencyOutput(firstNs, 16_666_666L, 12).trimEnd().replace('\n', ';')
        val stale = buildString {
            append("16666666")
            repeat(12) { append(";0 0 0") }
        }
        val executor = FrameCommandExecutor { command ->
            Result.success(
                when {
                    "--list" in command -> "$staleLayer;$layerName"
                    staleLayer in command -> stale
                    else -> latency
                },
            )
        }
        val port = PServerFrameTelemetryPort(
            executor = executor,
            pServerAvailable = { true },
            nowNs = { firstNs + 11 * 16_666_666L + 100_000_000L },
        )

        val result = port.read(packageName)

        assertEquals(FrameTelemetryStatus.AVAILABLE, result.status)
        assertEquals(layerName, result.layerName)
    }

    @Test
    fun `port remains ambiguous when two layers both have fresh frames`() {
        val secondLayer = layerName.replace("#3989", "#3990")
        val firstNs = 9_000_000_000L
        val latency = latencyOutput(firstNs, 16_666_666L, 12).trimEnd().replace('\n', ';')
        val executor = FrameCommandExecutor { command ->
            Result.success(if ("--list" in command) "$layerName;$secondLayer" else latency)
        }
        val port = PServerFrameTelemetryPort(
            executor = executor,
            pServerAvailable = { true },
            nowNs = { firstNs + 11 * 16_666_666L + 100_000_000L },
        )

        assertEquals(FrameTelemetryStatus.AMBIGUOUS_PACKAGE_LAYER, port.read(packageName).status)
    }

    private fun latencyOutput(firstNs: Long, intervalNs: Long, timestampCount: Int): String = buildString {
        appendLine("16666666")
        repeat(timestampCount) { index ->
            val actualNs = firstNs + index * intervalNs
            appendLine("${actualNs - 1_000_000L}\t$actualNs\t${actualNs + 1_000_000L}")
        }
    }
}

package com.gruning.mimir.rewrite

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class VendorHudTelemetryTest {
    @Test
    fun `protocol keeps CPU when GameNative has no session`() {
        val raw = parseVendorHudRawSnapshot(
            """$VENDOR_HUD_PROTOCOL_MARKER
cpu 100 20 30 400 50 10 5 5
FRAME NONE
END
""".trimStart('+'),
        )

        assertNotNull(raw)
        assertEquals("cpu 100 20 30 400 50 10 5 5", raw?.procStatLine)
        assertNull(raw?.gameNativeSessionFileName)
        assertNull(raw?.gameNativeTail)
    }

    @Test
    fun `protocol rejects truncated or extra output`() {
        val complete = """$VENDOR_HUD_PROTOCOL_MARKER
cpu 100 20 30 400 50 10 5 5
FRAME NONE
END
""".trimStart('+')

        assertNull(parseVendorHudRawSnapshot(complete.removeSuffix("END\n")))
        assertNull(parseVendorHudRawSnapshot(complete + "garbage"))
    }

    @Test
    fun `vendor wire decodes one captured line before strict protocol parsing`() {
        val protocol = """$VENDOR_HUD_PROTOCOL_MARKER
cpu 100 20 30 400 50 10 5 5
FRAME NONE
END
""".trimStart('+')
        val capturedLine = Base64.getEncoder().encodeToString(protocol.toByteArray()) + "\n"

        val decoded = requireNotNull(decodeVendorHudWire(capturedLine))
        assertEquals(protocol, decoded)
        assertNotNull(parseVendorHudRawSnapshot(decoded))
        assertEquals(protocol, decodeVendorHudWire(capturedLine.removeSuffix("\n")))
    }

    @Test
    fun `vendor wire rejects truncated whitespace extra newline and oversize replies`() {
        val protocol = """$VENDOR_HUD_PROTOCOL_MARKER
cpu 100 20 30 400 50 10 5 5
FRAME NONE
END
""".trimStart('+')
        val encoded = Base64.getEncoder().encodeToString(protocol.toByteArray())

        assertNull(decodeVendorHudWire(encoded.dropLast(1)))
        assertNull(decodeVendorHudWire(encoded.take(4) + "\n" + encoded.drop(4)))
        assertNull(decodeVendorHudWire(encoded + "\n\n"))
        assertNull(decodeVendorHudWire(Base64.getEncoder().encodeToString(ByteArray(721))))
    }

    @Test
    fun `protocol preserves bounded tail and shared FPS parser selects newest complete row`() {
        val session = 1_700_000_000_000L
        val row = """{"timestampMs":${session + 500},"fps":144.5,"totalFrameCount":20}"""
        val output = """$VENDOR_HUD_PROTOCOL_MARKER
cpu 100 20 30 400 50 10 5 5
FRAME metrics-$session.jsonl 2048
ROW_BEGIN
${row}
ROW_END
END
""".trimStart('+')

        val raw = requireNotNull(parseVendorHudRawSnapshot(output))
        assertEquals("\n$row\n", raw.gameNativeTail)
        val fps = parseGameNativeFrameRateTail(
            sessionStartMs = session,
            fileName = requireNotNull(raw.gameNativeSessionFileName),
            fileLength = requireNotNull(raw.gameNativeSessionFileSizeBytes),
            tail = requireNotNull(raw.gameNativeTail),
            nowEpochMs = session + 700,
            deadlineElapsedRealtimeMs = 2_000L,
            nowElapsedRealtimeMs = 1_000L,
        )
        assertEquals(144_500, fps)
    }

    @Test
    fun `CPU sampler warms then resets on failed snapshot and rejects counter regression`() {
        val sampler = VendorHudCpuSampler()
        assertNull(sampler.sample("cpu 100 0 100 700 100 0 0 0", 1_000L))
        assertEquals(50, sampler.sample("cpu 110 0 100 710 100 0 0 0", 2_000L))
        assertNull(sampler.sample(null, 3_000L))
        assertNull(sampler.sample("cpu 120 0 100 720 100 0 0 0", 4_000L))
        assertNull(sampler.sample("cpu 121 0 90 730 100 0 0 0", 5_000L))
        assertEquals(67, sampler.sample("cpu 131 0 100 740 100 0 0 0", 6_000L))
    }

    @Test
    fun `cache shares one snapshot for adjacent CPU and FPS reads then samples next beat`() {
        var elapsed = 1_000L
        var epoch = 1_700_000_000_000L
        var calls = 0
        val session = epoch - 1_000L
        val snapshots = ArrayDeque(
            listOf(
                vendorSnapshot(session, "cpu 100 0 100 700 100 0 0 0", 60.0),
                vendorSnapshot(session, "cpu 110 0 100 710 100 0 0 0", 72.0),
            ),
        )
        val cache = VendorHudTelemetryCache(
            nowElapsedRealtimeMs = { elapsed },
            nowEpochMs = { epoch },
            readSnapshot = {
                calls += 1
                snapshots.removeFirstOrNull()
            },
        )

        assertNull(cache.readDeviceUtilization(elapsed + 1_700L)?.cpuBusyPercent)
        assertEquals(60_000, cache.readForegroundFrameRateMilli(GAME_NATIVE_FRAME_RATE_PACKAGE, elapsed + 1_700L))
        assertEquals(1, calls)

        elapsed += 251L
        assertEquals(50, cache.readDeviceUtilization(elapsed + 1_700L)?.cpuBusyPercent)
        assertEquals(72_000, cache.readForegroundFrameRateMilli(GAME_NATIVE_FRAME_RATE_PACKAGE, elapsed + 1_700L))
        assertEquals(2, calls)

        epoch += GAME_NATIVE_MAX_SAMPLE_AGE_MS + 1L
        assertNull(cache.readForegroundFrameRateMilli(GAME_NATIVE_FRAME_RATE_PACKAGE, elapsed + 1_700L))
        assertEquals(2, calls)
    }

    @Test
    fun `cache failure returns unavailable and does not bridge CPU baseline`() {
        var elapsed = 1_000L
        var calls = 0
        val snapshots = ArrayDeque<VendorHudRawSnapshot?>(
            listOf(
                vendorSnapshot(1_700_000_000_000L, "cpu 100 0 100 700 100 0 0 0", 60.0),
                null,
                vendorSnapshot(1_700_000_000_000L, "cpu 120 0 100 720 100 0 0 0", 60.0),
            ),
        )
        val cache = VendorHudTelemetryCache(
            nowElapsedRealtimeMs = { elapsed },
            nowEpochMs = { 1_700_000_000_500L },
            readSnapshot = {
                calls += 1
                snapshots.removeFirstOrNull()
            },
        )
        assertNull(cache.readDeviceUtilization(elapsed + 1_700L)?.cpuBusyPercent)
        elapsed += 251L
        assertNull(cache.readDeviceUtilization(elapsed + 1_700L))
        elapsed += 251L
        assertNull(cache.readDeviceUtilization(elapsed + 1_700L)?.cpuBusyPercent)
        assertEquals(3, calls)
        assertTrue(calls <= 3)
    }

    @Test
    fun `fixed script command stays inside vendor envelope and has no caller arguments`() {
        val command = requireNotNull(vendorHudScriptCommand(java.io.File("/data/user/0/com.gruning.mimir/files/mimir-vendor-hud/read_hud.sh")))
        assertTrue(command.toByteArray(Charsets.UTF_8).size <= 198)
        assertTrue(command.contains("timeout -k .2 1.5"))
        assertTrue(command.endsWith("read_hud.sh"))
        assertTrue(!command.contains("$VENDOR_HUD_PROTOCOL_MARKER"))
        assertTrue(VENDOR_HUD_SCRIPT_SOURCE.contains("wire=${'$'}(printf '%s'"))
        assertTrue(VENDOR_HUD_SCRIPT_SOURCE.contains("[ \"${'$'}{#wire}\" -le 960 ]"))
    }

    @Test
    fun `wire decoder accepts exactly 960 ASCII bytes and rejects 964`() {
        val accepted = Base64.getEncoder().encodeToString(ByteArray(720))
        val rejected = Base64.getEncoder().encodeToString(ByteArray(723))

        assertEquals(960, accepted.length)
        assertNotNull(decodeVendorHudWire(accepted))
        assertEquals(964, rejected.length)
        assertNull(decodeVendorHudWire(rejected))
    }

    @Test
    fun `fixed script selects latest complete row and ignores a partial append`() {
        val session = 1_700_000_000_000L
        val first = "{\"timestampMs\":${session + 400},\"fps\":60.0,\"totalFrameCount\":20}"
        val partial = "{\"timestampMs\":${session + 500},\"fps\":72.0,\"totalFrameCount\":21}"
        val wire = runFixedHudScript(
            cpuLine = "cpu 100 20 30 400 50 10 5 5",
            sessions = mapOf("metrics-$session.jsonl" to "$first\n$partial"),
        )

        val decoded = requireNotNull(decodeVendorHudWire(wire))
        val raw = requireNotNull(parseVendorHudRawSnapshot(decoded))
        assertEquals("\n$first\n", raw.gameNativeTail)
        assertEquals(60_000, parseGameNativeFrameRateTail(
            sessionStartMs = session,
            fileName = requireNotNull(raw.gameNativeSessionFileName),
            fileLength = requireNotNull(raw.gameNativeSessionFileSizeBytes),
            tail = requireNotNull(raw.gameNativeTail),
            nowEpochMs = session + 700,
            deadlineElapsedRealtimeMs = 2_000L,
            nowElapsedRealtimeMs = 1_000L,
        ))

        val large = runFixedHudScript(
            cpuLine = "cpu 100 20 30 400 50 10 5 5",
            sessions = mapOf(
                "metrics-$session.jsonl" to
                    "${"x".repeat(4_200)}\n$first\n$partial\n",
            ),
        )
        val largeRaw = requireNotNull(parseVendorHudRawSnapshot(
            requireNotNull(decodeVendorHudWire(large)),
        ))
        assertEquals("\n$partial\n", largeRaw.gameNativeTail)
        assertEquals(72_000, parseGameNativeFrameRateTail(
            sessionStartMs = session,
            fileName = requireNotNull(largeRaw.gameNativeSessionFileName),
            fileLength = requireNotNull(largeRaw.gameNativeSessionFileSizeBytes),
            tail = requireNotNull(largeRaw.gameNativeTail),
            nowEpochMs = session + 700,
            deadlineElapsedRealtimeMs = 2_000L,
            nowElapsedRealtimeMs = 1_000L,
        ))

        val tornPrefix = "x".repeat(5_000)
        val tornPaddingLength = 4_096 - first.toByteArray(StandardCharsets.UTF_8).size - 1
        assertTrue(tornPaddingLength > 0)
        val loneTruncatedFile = tornPrefix + first + "\n" + "p".repeat(tornPaddingLength)
        assertTrue(loneTruncatedFile.toByteArray(StandardCharsets.UTF_8).size > 4_096)
        assertEquals(
            4_096,
            (first + "\n" + "p".repeat(tornPaddingLength))
                .toByteArray(StandardCharsets.UTF_8)
                .size,
        )
        val loneTruncated = parseVendorHudRawSnapshot(
            requireNotNull(decodeVendorHudWire(runFixedHudScript(
                cpuLine = "cpu 100 20 30 400 50 10 5 5",
                sessions = mapOf("metrics-$session.jsonl" to loneTruncatedFile),
            ))),
        )
        assertNotNull(loneTruncated)
        assertNull(loneTruncated?.gameNativeSessionFileName)
    }

    @Test
    fun `fixed script bounds newest row and never falls back to older frame`() {
        val session = 1_700_000_000_000L
        val valid = "{\"timestampMs\":${session + 400},\"fps\":60.0,\"totalFrameCount\":20}"
        val malformed = "{\"timestampMs\":${session + 500},\"fps\":oops,\"totalFrameCount\":21}"

        val malformedWire = runFixedHudScript(
            cpuLine = "cpu 100 20 30 400 50 10 5 5",
            sessions = mapOf("metrics-$session.jsonl" to "$valid\n$malformed\n"),
        )
        val malformedRaw = requireNotNull(parseVendorHudRawSnapshot(
            requireNotNull(decodeVendorHudWire(malformedWire)),
        ))
        assertEquals("\n$malformed\n", malformedRaw.gameNativeTail)
        assertNull(parseGameNativeFrameRateTail(
            sessionStartMs = session,
            fileName = requireNotNull(malformedRaw.gameNativeSessionFileName),
            fileLength = requireNotNull(malformedRaw.gameNativeSessionFileSizeBytes),
            tail = requireNotNull(malformedRaw.gameNativeTail),
            nowEpochMs = session + 700,
            deadlineElapsedRealtimeMs = 2_000L,
            nowElapsedRealtimeMs = 1_000L,
        ))

        val blankWire = runFixedHudScript(
            cpuLine = "cpu 100 20 30 400 50 10 5 5",
            sessions = mapOf("metrics-$session.jsonl" to "$valid\n\n"),
        )
        assertNull(requireNotNull(parseVendorHudRawSnapshot(
            requireNotNull(decodeVendorHudWire(blankWire)),
        )).gameNativeSessionFileName)

        val oversized = "{" + "x".repeat(512) + "}"
        val oversizedWire = runFixedHudScript(
            cpuLine = "cpu 100 20 30 400 50 10 5 5",
            sessions = mapOf("metrics-$session.jsonl" to "$valid\n$oversized\n"),
        )
        assertNull(requireNotNull(parseVendorHudRawSnapshot(
            requireNotNull(decodeVendorHudWire(oversizedWire)),
        )).gameNativeSessionFileName)
    }

    @Test
    fun `fixed script rejects CPU overflow, excessive sessions, and dangling newest files`() {
        val session = 1_700_000_000_000L
        val row = "{\"timestampMs\":${session + 400},\"fps\":60.0,\"totalFrameCount\":20}"
        val sessions = (0 until 6).associate { index ->
            "metrics-${session + index}.jsonl" to "$row\n"
        }

        val overflow = runFixedHudScript(
            cpuLine = "cpu 99999999999999999999 20 30 400 50 10 5 5",
            sessions = mapOf("metrics-$session.jsonl" to "$row\n"),
        )
        assertTrue(overflow.isEmpty())

        val excessive = parseVendorHudRawSnapshot(
            requireNotNull(decodeVendorHudWire(runFixedHudScript(
                cpuLine = "cpu 100 20 30 400 50 10 5 5",
                sessions = sessions,
            ))),
        )
        assertNotNull(excessive)
        assertNull(excessive?.gameNativeSessionFileName)

        val dangling = parseVendorHudRawSnapshot(
            requireNotNull(decodeVendorHudWire(runFixedHudScript(
                cpuLine = "cpu 100 20 30 400 50 10 5 5",
                sessions = mapOf("metrics-$session.jsonl" to "$row\n"),
                danglingSession = "metrics-${session + 1}.jsonl",
            ))),
        )
        assertNotNull(dangling)
        assertNull(dangling?.gameNativeSessionFileName)

        val longCpu = "cpu 100 20 30 400 50 10 5 5" + " 1".repeat(120)
        val longRow = "{\"timestampMs\":${session + 400},\"fps\":60.0," +
            "\"totalFrameCount\":20,\"padding\":\"${"x".repeat(430)}\"}"
        val budgetFallback = parseVendorHudRawSnapshot(
            requireNotNull(decodeVendorHudWire(runFixedHudScript(
                cpuLine = longCpu,
                sessions = mapOf("metrics-$session.jsonl" to "$longRow\n"),
            ))),
        )
        assertEquals(longCpu, budgetFallback?.procStatLine)
        assertNull(budgetFallback?.gameNativeSessionFileName)
    }

    private fun runFixedHudScript(
        cpuLine: String,
        sessions: Map<String, String>,
        danglingSession: String? = null,
    ): String {
        val root = Files.createTempDirectory("mimir-vendor-hud-script")
        val tools = Files.createDirectories(root.resolve("tools"))
        val procStat = root.resolve("proc-stat")
        val directory = Files.createDirectories(root.resolve("powercontrol"))
        Files.write(procStat, "$cpuLine\n".toByteArray(StandardCharsets.UTF_8))
        sessions.forEach { (name, contents) ->
            Files.write(directory.resolve(name), contents.toByteArray(StandardCharsets.UTF_8))
        }
        danglingSession?.let {
            Files.createSymbolicLink(directory.resolve(it), root.resolve("missing-target"))
        }

        val base64 = writeHostTool(tools.resolve("base64"), """
            #!/bin/sh
            /usr/bin/base64 | tr -d '\n'
        """.trimIndent())
        val stat = writeHostTool(tools.resolve("stat"), """
            #!/bin/sh
            [ "${'$'}1" = -c ] && [ "${'$'}2" = %s ] || exit 1
            wc -c < "${'$'}3" | tr -d '[:space:]'
        """.trimIndent())
        val tail = writeHostTool(tools.resolve("tail"), """
            #!/bin/sh
            exec /usr/bin/tail "${'$'}@"
        """.trimIndent())
        val script = root.resolve("read_hud.sh")
        val source = VENDOR_HUD_SCRIPT_SOURCE
            .replace("/proc/stat", procStat.toString())
            .replace("/sdcard/Android/data/app.gamenative/files/powercontrol", directory.toString())
            .replace("/system/bin/base64", base64.toString())
            .replace("/system/bin/stat", stat.toString())
            .replace("/system/bin/tail", tail.toString())
        Files.write(script, source.toByteArray(StandardCharsets.UTF_8))

        return try {
            val process = ProcessBuilder("/bin/sh", script.toString())
                .redirectErrorStream(false)
                .start()
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
            }
            assertTrue("fixed script did not exit", finished)
            assertEquals(0, process.exitValue())
            val output = process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
            assertTrue(output.length <= 960)
            assertTrue(output.none { it == '\n' || it == '\r' })
            assertTrue(output.all { it.code < 128 })
            output
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder<Path>()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun writeHostTool(path: Path, source: String): String {
        Files.write(path, source.toByteArray(StandardCharsets.UTF_8))
        path.toFile().setExecutable(true)
        return path.toString()
    }

    private fun vendorSnapshot(session: Long, cpu: String, fps: Double): VendorHudRawSnapshot =
        VendorHudRawSnapshot(
            procStatLine = cpu,
            gameNativeSessionFileName = "metrics-$session.jsonl",
            gameNativeSessionFileSizeBytes = 2048L,
            gameNativeTail = "{\"timestampMs\":${session + 500},\"fps\":$fps,\"totalFrameCount\":20}\n",
        )
}

package com.gruning.mimir.rewrite

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameFrameRateTelemetryTest {
    @Test
    fun `shared tail parser selects newest complete row from fixed vendor snapshot`() {
        val tail = row(9_700L) + row(9_900L)

        assertEquals(
            60_000,
            parseGameNativeFrameRateTail(
                sessionStartMs = 9_000L,
                fileName = "metrics-9000.jsonl",
                fileLength = tail.toByteArray().size.toLong(),
                tail = tail,
                nowEpochMs = 10_000L,
                deadlineElapsedRealtimeMs = 200L,
                nowElapsedRealtimeMs = 100L,
            ),
        )
    }

    @Test
    fun `shared tail parser rejects a prefix cut and mismatched session identity`() {
        assertNull(
            parseGameNativeFrameRateTail(
                sessionStartMs = 9_000L,
                fileName = "metrics-9000.jsonl",
                fileLength = GAME_NATIVE_MAX_TAIL_BYTES + 1L,
                tail = "partial-prefix" + row(9_900L),
                nowEpochMs = 10_000L,
                deadlineElapsedRealtimeMs = 200L,
                nowElapsedRealtimeMs = 100L,
            ),
        )
        assertNull(
            parseGameNativeFrameRateTail(
                sessionStartMs = 9_000L,
                fileName = "metrics-9001.jsonl",
                fileLength = row(9_900L).toByteArray().size.toLong(),
                tail = row(9_900L),
                nowEpochMs = 10_000L,
                deadlineElapsedRealtimeMs = 200L,
                nowElapsedRealtimeMs = 100L,
            ),
        )
    }

    @Test
    fun `shared tail parser enforces caller deadline after fixed snapshot`() {
        assertNull(
            parseGameNativeFrameRateTail(
                sessionStartMs = 9_000L,
                fileName = "metrics-9000.jsonl",
                fileLength = row(9_900L).toByteArray().size.toLong(),
                tail = row(9_900L),
                nowEpochMs = 10_000L,
                deadlineElapsedRealtimeMs = 100L,
                nowElapsedRealtimeMs = 100L,
            ),
        )
    }

    @Test
    fun `reads newest complete GameNative renderer row`() {
        withDirectory { directory ->
            directory.resolve("metrics-9000.jsonl").writeText(
                "{\"timestampMs\":9900,\"fps\":59.5,\"totalFrameCount\":120}\n",
            )
            val source = source(directory, nowEpoch = 10_000L)

            assertEquals(
                59_500,
                source.read("app.gamenative", deadlineElapsedRealtimeMs = 200L),
            )
            assertEquals(60, framesPerSecond(59_500))
        }
    }

    @Test
    fun `package and monotonic deadline gates refuse unrelated or expired reads`() {
        withDirectory { directory ->
            directory.resolve("metrics-9000.jsonl").writeText(row(9_900L))
            val source = source(directory, nowEpoch = 10_000L, nowElapsed = 100L)

            assertNull(
                source.read("com.example.game", deadlineElapsedRealtimeMs = 200L),
            )
            assertNull(
                source.read("app.gamenative", deadlineElapsedRealtimeMs = 100L),
            )
        }
    }

    @Test
    fun `freshness and session identity reject future old and pre-session rows`() {
        withDirectory { directory ->
            // After the session start, but older than the 1.5 s freshness window.
            directory.resolve("metrics-7000.jsonl").writeText(row(8_400L))
            val source = source(directory, nowEpoch = 10_000L)
            assertNull(
                source.read("app.gamenative", deadlineElapsedRealtimeMs = 200L),
            )

            // Fresh enough by wall clock, but impossible for this session because it predates its filename.
            Files.delete(directory.resolve("metrics-7000.jsonl"))
            directory.resolve("metrics-9500.jsonl").writeText(row(9_400L))
            assertNull(
                source.read("app.gamenative", deadlineElapsedRealtimeMs = 200L),
            )

            Files.delete(directory.resolve("metrics-9500.jsonl"))
            directory.resolve("metrics-9000.jsonl").writeText(row(10_001L))
            assertNull(
                source.read("app.gamenative", deadlineElapsedRealtimeMs = 200L),
            )
        }
    }

    @Test
    fun `newest malformed session never falls back to an older session`() {
        withDirectory { directory ->
            directory.resolve("metrics-8000.jsonl").writeText(row(9_900L))
            directory.resolve("metrics-9000.jsonl").writeText("not-json\n")

            assertNull(
                source(directory, nowEpoch = 10_000L).read(
                    "app.gamenative",
                    deadlineElapsedRealtimeMs = 200L,
                ),
            )
        }
    }

    @Test
    fun `incomplete tail prefix is not accepted as a complete row`() {
        withDirectory { directory ->
            directory.resolve("metrics-9000.jsonl").writeText(
                "x".repeat(5_000) + row(9_900L),
            )

            assertNull(
                source(directory, nowEpoch = 10_000L).read(
                    "app.gamenative",
                    deadlineElapsedRealtimeMs = 200L,
                ),
            )
        }
    }

    @Test
    fun `fractional timestamps and oversized frame counts are rejected`() {
        withDirectory { directory ->
            directory.resolve("metrics-9000.jsonl").writeText(
                "{\"timestampMs\":9900.5,\"fps\":60,\"totalFrameCount\":120}\n",
            )
            val source = source(directory, nowEpoch = 10_000L)
            assertNull(
                source.read("app.gamenative", deadlineElapsedRealtimeMs = 200L),
            )

            directory.resolve("metrics-9000.jsonl").writeText(
                "{\"timestampMs\":9900,\"fps\":60,\"totalFrameCount\":4097}\n",
            )
            assertNull(
                source.read("app.gamenative", deadlineElapsedRealtimeMs = 200L),
            )
        }
    }

    @Test
    fun `monotonic deadline is rechecked after the file read`() {
        withDirectory { directory ->
            directory.resolve("metrics-9000.jsonl").writeText(row(9_900L))
            var elapsed = 100L
            val source = GameNativeFrameRateSource(
                directory = directory.toFile(),
                nowEpochMs = { 10_000L },
                nowElapsedRealtimeMs = { elapsed.also { elapsed = 300L } },
            )

            assertNull(source.read("app.gamenative", deadlineElapsedRealtimeMs = 200L))
        }
    }

    @Test
    fun `symlinked session is refused`() {
        withDirectory { directory ->
            val target = directory.resolve("outside.jsonl")
            target.writeText(row(9_900L))
            val link = directory.resolve("metrics-9000.jsonl")
            runCatching { Files.createSymbolicLink(link, target) }
                .onFailure { return@withDirectory }

            assertNull(
                source(directory, nowEpoch = 10_000L).read(
                    "app.gamenative",
                    deadlineElapsedRealtimeMs = 200L,
                ),
            )
        }
    }

    private fun source(directory: Path, nowEpoch: Long, nowElapsed: Long = 100L) =
        GameNativeFrameRateSource(
            directory = directory.toFile(),
            nowEpochMs = { nowEpoch },
            nowElapsedRealtimeMs = { nowElapsed },
        )

    private fun row(timestamp: Long) =
        "{\"timestampMs\":$timestamp,\"fps\":60.0,\"totalFrameCount\":120}\n"

    private fun withDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("mimir-gamenative")
        try {
            directory.createDirectories()
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

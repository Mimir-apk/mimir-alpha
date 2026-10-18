package com.gruning.mimir.rewrite

import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlin.math.roundToInt

/** Convert one validated milli-FPS value to the integer rendered by the HUD. */
internal fun framesPerSecond(milli: Int?): Int? =
    milli?.takeIf { it > 0 }?.let { (it + 500) / 1000 }

internal const val GAME_NATIVE_FRAME_RATE_PACKAGE = "app.gamenative"
internal const val GAME_NATIVE_MAX_TAIL_BYTES = 4 * 1024L
internal const val GAME_NATIVE_MAX_LOG_BYTES = 20L * 1024L * 1024L
// GameNative checks the 20 MiB limit before appending a row, so permit one bounded tail beyond it.
internal const val GAME_NATIVE_MAX_STORED_BYTES =
    GAME_NATIVE_MAX_LOG_BYTES + GAME_NATIVE_MAX_TAIL_BYTES
internal const val GAME_NATIVE_MAX_SAMPLE_AGE_MS = 1_500L
internal const val GAME_NATIVE_MAX_FRAME_COUNT = 4_096L

private val GameNativeSessionName = Regex("^metrics-([0-9]{1,19})\\.jsonl$")

/**
 * Validates one bounded GameNative renderer-log snapshot. The privileged producer supplies the already
 * selected session identity, file length, and bounded tail; it must check the request deadline before
 * starting the read and this seam checks it again before accepting the result.
 */
internal fun parseGameNativeFrameRateTail(
    sessionStartMs: Long,
    fileName: String,
    fileLength: Long,
    tail: String,
    nowEpochMs: Long,
    deadlineElapsedRealtimeMs: Long,
    nowElapsedRealtimeMs: Long,
): Int? {
    val fileSessionStartMs = GameNativeSessionName.matchEntire(fileName)
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()
        ?: return null
    if (fileSessionStartMs != sessionStartMs || sessionStartMs <= 0L) return null
    if (fileLength <= 0L || fileLength > GAME_NATIVE_MAX_STORED_BYTES) return null
    if (tail.toByteArray(StandardCharsets.UTF_8).size > GAME_NATIVE_MAX_TAIL_BYTES) return null

    val lastNewline = tail.lastIndexOf('\n')
    if (lastNewline < 0) return null
    val completeTail = tail.substring(0, lastNewline).trimEnd('\r')
    val lineStart = completeTail.lastIndexOf('\n') + 1
    // A bounded window may begin in the middle of the newest row. Its suffix is not a complete JSONL
    // record; accepting it would turn a torn prefix into a valid-looking sample.
    if (fileLength > GAME_NATIVE_MAX_TAIL_BYTES && lineStart == 0) return null
    val line = completeTail.substring(lineStart).trim().takeIf(String::isNotEmpty) ?: return null
    val sample = parseGameNativeFrameRow(line) ?: return null
    if (sample.timestampMs < sessionStartMs ||
        sample.timestampMs > nowEpochMs ||
        nowEpochMs - sample.timestampMs !in 0L..GAME_NATIVE_MAX_SAMPLE_AGE_MS
    ) {
        return null
    }
    if (deadlineElapsedRealtimeMs <= nowElapsedRealtimeMs) return null
    return sample.fpsMilli
}

private data class GameNativeFrameRow(
    val timestampMs: Long,
    val fpsMilli: Int,
)

private fun parseGameNativeFrameRow(line: String): GameNativeFrameRow? = runCatching {
    val json = JSONObject(line)
    if (!json.has("timestampMs") || !json.has("fps") || !json.has("totalFrameCount")) {
        return@runCatching null
    }
    val timestamp = json.strictLong("timestampMs") ?: return@runCatching null
    val fps = json.opt("fps") as? Number ?: return@runCatching null
    val frameCount = json.strictLong("totalFrameCount") ?: return@runCatching null
    val fpsValue = fps.toDouble()
    if (timestamp <= 0L ||
        !fpsValue.isFinite() || fpsValue <= 0.0 || fpsValue > 1_000.0 ||
        frameCount !in 1L..GAME_NATIVE_MAX_FRAME_COUNT
    ) {
        return@runCatching null
    }
    val fpsMilli = (fpsValue * 1_000.0).roundToInt()
    if (fpsMilli <= 0) return@runCatching null
    GameNativeFrameRow(timestampMs = timestamp, fpsMilli = fpsMilli)
}.getOrNull()

private fun JSONObject.strictLong(name: String): Long? = when (val value = opt(name)) {
    is Int -> value.toLong()
    is Long -> value
    else -> null
}

/**
 * Reads GameNative's renderer-fed frame log. This is the only live HUD FPS producer: it is a per-game
 * value written by GameNative's PerformanceMetricsCollector, not a display/compositor rate. The fixed
 * directory, package gate, bounded tail and freshness checks are intentional; no caller-supplied path or
 * process command crosses the privileged boundary.
 */
internal class GameNativeFrameRateSource(
    private val directory: File = File(DEFAULT_DIRECTORY),
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val nowElapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val readTail: (File) -> String? = ::readBoundedTail,
) {
    /** Returns FPS x1000, or null when the current GameNative stream cannot prove a value. */
    fun read(
        packageName: String,
        deadlineElapsedRealtimeMs: Long,
    ): Int? {
        if (packageName != GAME_NATIVE_FRAME_RATE_PACKAGE) return null
        if (deadlineElapsedRealtimeMs <= nowElapsedRealtimeMs()) return null
        val session = newestSession(nowEpochMs()) ?: return null
        val tail = readTail(session.file) ?: return null
        val fileLengthAfterRead = session.file.length()
        val afterReadNow = nowEpochMs()
        return parseGameNativeFrameRateTail(
            sessionStartMs = session.sessionStartMs,
            fileName = session.file.name,
            fileLength = fileLengthAfterRead,
            tail = tail,
            nowEpochMs = afterReadNow,
            deadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
            nowElapsedRealtimeMs = nowElapsedRealtimeMs(),
        )
    }

    private fun newestSession(nowEpochMs: Long): SessionFile? {
        if (Files.isSymbolicLink(directory.toPath()) ||
            !Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)
        ) return null
        val candidates = directory.listFiles()
            ?.filter { GameNativeSessionName.matches(it.name) }
            ?: return null
        if (candidates.isEmpty() || candidates.size > MAX_SESSION_FILES) return null
        val sessions = candidates.map { file ->
            if (Files.isSymbolicLink(file.toPath()) ||
                !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) ||
                file.length() > GAME_NATIVE_MAX_STORED_BYTES
            ) {
                return null
            }
            val timestamp = GameNativeSessionName.matchEntire(file.name)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
                ?.takeIf { it > 0L && it <= nowEpochMs }
                ?: return null
            SessionFile(file, timestamp)
        }
        return sessions.maxByOrNull(SessionFile::sessionStartMs)
    }

    private data class SessionFile(
        val file: File,
        val sessionStartMs: Long,
    )

    private companion object {
        const val DEFAULT_DIRECTORY =
            "/sdcard/Android/data/app.gamenative/files/powercontrol"
        const val MAX_SESSION_FILES = 5

        fun readBoundedTail(file: File): String? = runCatching {
            val length = file.length()
            if (length <= 0L) return@runCatching null
            if (length > GAME_NATIVE_MAX_STORED_BYTES) return@runCatching null
            val start = (length - GAME_NATIVE_MAX_TAIL_BYTES).coerceAtLeast(0L)
            val bytes = ByteArray((length - start).toInt())
            RandomAccessFile(file, "r").use { input ->
                input.seek(start)
                input.readFully(bytes)
            }
            String(bytes, StandardCharsets.UTF_8)
        }.getOrNull()
    }
}

package com.gruning.mimir.rewrite

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/** The bounded, typed payload emitted by the fixed vendor HUD snapshot script. */
internal data class VendorHudRawSnapshot(
    val procStatLine: String,
    val gameNativeSessionFileName: String?,
    val gameNativeSessionFileSizeBytes: Long?,
    val gameNativeTail: String?,
)

/**
 * Pure delta sampler for the aggregate `/proc/stat` line supplied by the vendor snapshot.
 * The first eight counters already include guest time in user/nice, so guest is validated but not
 * subtracted a second time.
 */
internal class VendorHudCpuSampler(
    private val maxIntervalMs: Long = MAX_INTERVAL_MS,
) {
    private var procStatLine: String? = null
    private var sampledAtMs: Long = 0L
    private var delegate = newSampler()

    fun sample(procStatLine: String?, sampledAtElapsedRealtimeMs: Long): Int? {
        this.procStatLine = procStatLine
        this.sampledAtMs = sampledAtElapsedRealtimeMs
        return delegate.sample().cpuBusyPercent
    }

    fun reset() {
        delegate = newSampler()
        procStatLine = null
        sampledAtMs = 0L
    }

    private fun newSampler() = DeviceUtilizationSampler(
        { procStatLine },
        { null },
        { sampledAtMs },
        maxIntervalMs,
    )

    private companion object {
        internal const val MAX_INTERVAL_MS = 5_000L
    }
}

/**
 * Parse the fixed script protocol. The CPU line is deliberately the first payload line, and the
 * optional frame section carries only the fixed GameNative filename, size and one proved row.
 */
internal fun parseVendorHudRawSnapshot(output: String?): VendorHudRawSnapshot? {
    if (output == null || output.toByteArray(StandardCharsets.UTF_8).size > MAX_PROTOCOL_BYTES) return null
    val marker = "$VENDOR_HUD_PROTOCOL_MARKER\n"
    if (!output.startsWith(marker)) return null
    val payload = output.removePrefix(marker)
    val procEnd = payload.indexOf('\n')
    if (procEnd <= 0) return null
    val procStatLine = payload.substring(0, procEnd)
    if (procStatLine.toByteArray(StandardCharsets.UTF_8).size > MAX_PROC_STAT_BYTES ||
        parseDeviceCpuCounters(procStatLine) == null
    ) return null
    val framePayload = payload.substring(procEnd + 1)
    if (framePayload.startsWith("FRAME NONE\nEND\n")) {
        if (framePayload.substring("FRAME NONE\nEND\n".length).isNotEmpty()) return null
        return VendorHudRawSnapshot(procStatLine, null, null, null)
    }
    val frameEnd = framePayload.indexOf('\n')
    if (frameEnd <= 0) return null
    val frameHeader = framePayload.substring(0, frameEnd)
    val fields = frameHeader.split(' ')
    if (fields.size != 3 || fields[0] != "FRAME") return null
    val fileName = fields[1].takeIf { GAME_NATIVE_SESSION_NAME.matches(it) } ?: return null
    val fileLength = fields[2].toLongOrNull()
        ?.takeIf { it > 0L && it <= GAME_NATIVE_MAX_STORED_BYTES }
        ?: return null
    val rowPrefix = "ROW_BEGIN\n"
    val rest = framePayload.substring(frameEnd + 1)
    if (!rest.startsWith(rowPrefix)) return null
    val rowAndEnd = rest.removePrefix(rowPrefix)
    val rowEnd = rowAndEnd.indexOf("\nROW_END\nEND\n")
    if (rowEnd <= 0) return null
    val row = rowAndEnd.substring(0, rowEnd)
    if (row.contains('\n') ||
        row.toByteArray(StandardCharsets.UTF_8).size > MAX_GAME_NATIVE_ROW_BYTES
    ) return null
    if (rowAndEnd.substring(rowEnd + "\nROW_END\nEND\n".length).isNotEmpty()) return null
    // The script proved this row as a complete record. Keep explicit newlines around it so the
    // shared parser retains the boundary proof even when the backing file is larger than 4 KiB.
    val tail = "\n$row\n"
    return VendorHudRawSnapshot(procStatLine, fileName, fileLength, tail)
}

private val GAME_NATIVE_SESSION_NAME = Regex("^metrics-([0-9]{1,19})\\.jsonl$")
private const val MAX_PROTOCOL_BYTES = 720
private const val MAX_WIRE_BASE64_CHARS = 960
private const val MAX_GAME_NATIVE_ROW_BYTES = 512
private const val MAX_PROC_STAT_BYTES = 512
internal const val VENDOR_HUD_PROTOCOL_MARKER = "__MIMIR_VENDOR_HUD_V2__"

/** Decode the one-line reply captured by PServer before passing it to the strict raw parser. */
internal fun decodeVendorHudWire(output: String?): String? {
    if (output == null || output.isEmpty()) return null
    val wire = if (output.endsWith('\n')) output.dropLast(1) else output
    if (wire.isEmpty() || wire.length > MAX_WIRE_BASE64_CHARS || wire.any { it == '\n' || it == '\r' }) {
        return null
    }
    if (wire.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '+' && it != '/' && it != '=' }) {
        return null
    }
    if (wire.length % 4 != 0) return null
    val decoded = runCatching { Base64.getDecoder().decode(wire) }.getOrNull() ?: return null
    if (decoded.size > MAX_PROTOCOL_BYTES) return null
    return String(decoded, StandardCharsets.UTF_8)
}

/**
 * Shares one bounded remote snapshot between adjacent CPU/FPS calls. A failed read is cached as an
 * unavailable entry briefly so the two consumers do not dispatch duplicate Binder work. CPU is
 * sampled only when the snapshot is fetched; a cache hit re-parses FPS so its freshness deadline is
 * evaluated at the actual read time.
 */
internal class VendorHudTelemetryCache(
    private val nowElapsedRealtimeMs: () -> Long,
    private val nowEpochMs: () -> Long,
    private val readSnapshot: (Long) -> VendorHudRawSnapshot?,
    private val cacheWindowMs: Long = CACHE_WINDOW_MS,
    private val cpuSampler: VendorHudCpuSampler = VendorHudCpuSampler(),
) {
    private val lock = Any()
    private var cached: Cached? = null

    fun readDeviceUtilization(deadlineElapsedRealtimeMs: Long): DeviceUtilizationSample? =
        synchronized(lock) {
            val entry = entry(deadlineElapsedRealtimeMs) ?: return@synchronized null
            if (entry.raw == null) return@synchronized null
            DeviceUtilizationSample(entry.sampledAtElapsedRealtimeMs, entry.cpuBusyPercent, null)
        }

    fun readForegroundFrameRateMilli(
        packageName: String,
        deadlineElapsedRealtimeMs: Long,
    ): Int? = synchronized(lock) {
        if (packageName != GAME_NATIVE_FRAME_RATE_PACKAGE) return@synchronized null
        val entry = entry(deadlineElapsedRealtimeMs) ?: return@synchronized null
        val raw = entry.raw ?: return@synchronized null
        val fileName = raw.gameNativeSessionFileName ?: return@synchronized null
        val fileLength = raw.gameNativeSessionFileSizeBytes ?: return@synchronized null
        val tail = raw.gameNativeTail ?: return@synchronized null
        parseGameNativeFrameRateTail(
            sessionStartMs = GAME_NATIVE_SESSION_NAME.matchEntire(fileName)
                ?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return@synchronized null,
            fileName = fileName,
            fileLength = fileLength,
            tail = tail,
            nowEpochMs = nowEpochMs(),
            deadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
            nowElapsedRealtimeMs = nowElapsedRealtimeMs(),
        )
    }

    fun invalidate() = synchronized(lock) {
        cached = null
        cpuSampler.reset()
    }

    private fun entry(deadlineElapsedRealtimeMs: Long): Cached? {
        val now = nowElapsedRealtimeMs()
        if (deadlineElapsedRealtimeMs <= now) {
            cached = null
            return null
        }
        val current = cached
        if (current != null && now >= current.sampledAtElapsedRealtimeMs &&
            now - current.sampledAtElapsedRealtimeMs <= cacheWindowMs
        ) {
            return current
        }
        val raw = runCatching { readSnapshot(deadlineElapsedRealtimeMs) }.getOrNull()
        val sampledAt = nowElapsedRealtimeMs()
        if (raw == null || sampledAt >= deadlineElapsedRealtimeMs) {
            cpuSampler.reset()
            cached = Cached(sampledAt, null, null)
            return null
        }
        val cpu = cpuSampler.sample(raw.procStatLine, sampledAt)
        return Cached(sampledAt, raw, cpu).also { cached = it }
    }

    private data class Cached(
        val sampledAtElapsedRealtimeMs: Long,
        val raw: VendorHudRawSnapshot?,
        val cpuBusyPercent: Int?,
    )

    private companion object {
        const val CACHE_WINDOW_MS = 250L
    }
}

/** Fixed source staged once into the app's private files directory. */
internal class VendorHudScriptStager(
    private val filesDir: File,
    private val source: String = VENDOR_HUD_SCRIPT_SOURCE,
) {
    private val staged = AtomicReference<File?>(null)

    fun stage(): File? {
        staged.get()?.let { return it }
        val result = synchronized(this) {
            staged.get()?.let { return@synchronized it }
            val target = runCatching {
                val root = filesDir.canonicalFile
                val directory = File(root, VENDOR_HUD_STAGING_DIRECTORY)
                if (!validPrivateDirectory(root, directory) &&
                    (!directory.mkdirs() || !validPrivateDirectory(root, directory))
                ) return@runCatching null
                val destination = File(directory, VENDOR_HUD_SCRIPT_NAME)
                if (validStagedFile(destination)) return@runCatching destination
                val temporary = File.createTempFile("hud-", ".tmp", directory)
                try {
                    FileOutputStream(temporary).use { stream ->
                        stream.write(source.toByteArray(StandardCharsets.UTF_8))
                        stream.fd.sync()
                    }
                    if (!temporary.renameTo(destination)) return@runCatching null
                } finally {
                    temporary.delete()
                }
                destination.takeIf(::validStagedFile)
            }.getOrNull()
            staged.set(target)
            target
        }
        return result
    }

    private fun validPrivateDirectory(root: File, directory: File): Boolean = runCatching {
        val rootPath = root.toPath()
        val path = directory.toPath()
        !Files.isSymbolicLink(filesDir.toPath()) &&
            !Files.isSymbolicLink(path) &&
            Files.isDirectory(path, NOFOLLOW_LINKS) &&
            directory.canonicalFile.toPath().startsWith(rootPath)
    }.getOrDefault(false)

    private fun validStagedFile(file: File): Boolean = runCatching {
        val root = filesDir.canonicalFile.toPath()
        val path = file.toPath()
        Files.isRegularFile(path, NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(path) &&
            file.canonicalFile.toPath().startsWith(root) &&
            file.readBytes().contentEquals(source.toByteArray(StandardCharsets.UTF_8))
    }.getOrDefault(false)

    private companion object {
        const val VENDOR_HUD_STAGING_DIRECTORY = "mimir-vendor-hud"
        const val VENDOR_HUD_SCRIPT_NAME = "read_hud.sh"
    }
}

internal fun vendorHudScriptCommand(script: File): String? {
    val path = runCatching { script.canonicalPath }.getOrNull() ?: return null
    if (!path.matches(Regex("^[A-Za-z0-9_./-]+$"))) return null
    return "/system/bin/timeout -k .2 1.5 sh $path"
        .takeIf { it.toByteArray(StandardCharsets.UTF_8).size <= 198 }
}

/**
 * The only command staged by the vendor telemetry route. It has no arguments and reads only the
 * fixed GameNative app-private directory. A proved whole row and CPU counters are encoded inside
 * the timeout, with a 960-byte limit before stdout reaches PServer's 1,024-byte allocation. Invalid
 * frame data preserves bounded CPU telemetry and never falls back to an older session or row.
 */
internal val VENDOR_HUD_SCRIPT_SOURCE: String = """
#!/system/bin/sh
exec 2>/dev/null
LC_ALL=C
export LC_ALL

marker='$VENDOR_HUD_PROTOCOL_MARKER'
emit_payload() {
  payload=${'$'}(printf '%s\n%s\n%s' "${'$'}marker" "${'$'}cpu" "${'$'}frame"; printf x) || exit 0
  payload=${'$'}{payload%x}
  if [ "${'$'}{#payload}" -gt 720 ]; then
    frame=${'$'}(printf 'FRAME NONE\nEND\n'; printf x) || exit 0
    frame=${'$'}{frame%x}
    payload=${'$'}(printf '%s\n%s\n%s' "${'$'}marker" "${'$'}cpu" "${'$'}frame"; printf x) || exit 0
    payload=${'$'}{payload%x}
  fi
  [ "${'$'}{#payload}" -le 720 ] || exit 0
  wire=${'$'}(printf '%s' "${'$'}payload" | /system/bin/base64 -w 0) || exit 0
  [ "${'$'}{#wire}" -le 960 ] || exit 0
  case "${'$'}wire" in *[!A-Za-z0-9+/=]*) exit 0 ;; esac
  printf '%s' "${'$'}wire"
  exit 0
}
emit_cpu() {
  frame=${'$'}(printf 'FRAME NONE\nEND\n'; printf x) || exit 0
  frame=${'$'}{frame%x}
  emit_payload
}

IFS= read -r cpu < /proc/stat || exit 0
case "${'$'}cpu" in cpu\ *) ;; *) exit 0 ;; esac
[ "${'$'}{#cpu}" -le 512 ] || exit 0
set -f
set -- ${'$'}cpu
[ "${'$'}#" -ge 9 ] || exit 0
[ "${'$'}1" = cpu ] || exit 0
shift
counter_count=0
for value in "${'$'}@"; do
  counter_count=${'$'}((counter_count + 1))
  [ "${'$'}counter_count" -le 10 ] || break
  case "${'$'}value" in ''|*[!0-9]*) exit 0 ;; esac
  normalized=${'$'}value
  while [ "${'$'}{#normalized}" -gt 1 ] && [ "${'$'}{normalized#0}" != "${'$'}normalized" ]; do
    normalized=${'$'}{normalized#0}
  done
  [ "${'$'}{#normalized}" -le 19 ] || exit 0
  if [ "${'$'}{#normalized}" -eq 19 ] &&
      [ "${'$'}normalized" \> 9223372036854775807 ]; then
    exit 0
  fi
done
[ "${'$'}counter_count" -ge 8 ] || exit 0
set +f

dir=/sdcard/Android/data/app.gamenative/files/powercontrol
[ -L "${'$'}dir" ] && emit_cpu
[ -d "${'$'}dir" ] || emit_cpu
best=
best_name=
best_num=0
best_size=0
count=0
for file in "${'$'}dir"/metrics-*.jsonl; do
  name=${'$'}{file##*/}
  number=${'$'}{name#metrics-}
  number=${'$'}{number%.jsonl}
  case "${'$'}number" in ''|*[!0-9]*) continue ;; esac
  [ "${'$'}{#number}" -le 19 ] || continue
  if [ ! -e "${'$'}file" ]; then
    [ -L "${'$'}file" ] && emit_cpu
    continue
  fi
  candidate_num=${'$'}number
  while [ "${'$'}{#candidate_num}" -gt 1 ] &&
      [ "${'$'}{candidate_num#0}" != "${'$'}candidate_num" ]; do
    candidate_num=${'$'}{candidate_num#0}
  done
  count=${'$'}((count + 1))
  [ "${'$'}count" -le 5 ] || emit_cpu
  [ -f "${'$'}file" ] || emit_cpu
  [ ! -L "${'$'}file" ] || emit_cpu
  size=${'$'}(/system/bin/stat -c '%s' "${'$'}file") || emit_cpu
  case "${'$'}size" in ''|*[!0-9]*) emit_cpu ;; esac
  [ "${'$'}size" -le 20975616 ] || emit_cpu
  if [ -z "${'$'}best" ] ||
      [ "${'$'}{#candidate_num}" -gt "${'$'}{#best_num}" ] ||
      { [ "${'$'}{#candidate_num}" -eq "${'$'}{#best_num}" ] && [ "${'$'}candidate_num" \> "${'$'}best_num" ]; }; then
    best="${'$'}file"
    best_name="${'$'}name"
    best_num="${'$'}candidate_num"
    best_size="${'$'}size"
  fi
done
[ -n "${'$'}best" ] || emit_cpu
[ -f "${'$'}best" ] || emit_cpu
[ ! -L "${'$'}best" ] || emit_cpu
selection=${'$'}(
  /system/bin/tail -c 4096 "${'$'}best" |
  (
    line_count=0
    last_row=
    while IFS= read -r line; do
      line_count=${'$'}((line_count + 1))
      last_row="${'$'}line"
    done
    printf '%s\037%s\037%s\037' '__MIMIR_ROW_RESULT__' "${'$'}line_count" "${'$'}last_row"
  )
) || emit_cpu
separator=${'$'}(printf '\037')
result_body=${'$'}{selection#*"${'$'}separator"}
line_count=${'$'}{result_body%%"${'$'}separator"*}
row_with_end=${'$'}{result_body#*"${'$'}separator"}
row=${'$'}{row_with_end%"${'$'}separator"}
after_size=${'$'}(/system/bin/stat -c '%s' "${'$'}best") || emit_cpu
case "${'$'}after_size" in ''|*[!0-9]*) emit_cpu ;; esac
[ "${'$'}after_size" -le 20975616 ] || emit_cpu
[ "${'$'}after_size" -ge "${'$'}best_size" ] || emit_cpu
[ "${'$'}line_count" -gt 0 ] || emit_cpu
[ "${'$'}line_count" -ne 1 ] || [ "${'$'}after_size" -le 4096 ] || emit_cpu
[ -n "${'$'}row" ] || emit_cpu
[ "${'$'}{#row}" -le 512 ] || emit_cpu
frame=${'$'}(printf 'FRAME %s %s\nROW_BEGIN\n%s\nROW_END\nEND\n' "${'$'}best_name" "${'$'}after_size" "${'$'}row"; printf x) || emit_cpu
frame=${'$'}{frame%x}
[ "${'$'}{#frame}" -le 720 ] || emit_cpu
emit_payload
""".trimIndent() + "\n"

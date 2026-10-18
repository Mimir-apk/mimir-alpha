package com.gruning.mimir.rewrite

import java.util.UUID

/** The bounded native helper request/response envelope used by the emulator HUD route. */
internal const val EMULATOR_RENDER_FPS_REQUEST_HEADER = "MIMIR_RENDER_FPS_REQUEST_V1"
internal const val EMULATOR_RENDER_FPS_RECORD_HEADER = "MIMIR_RENDER_FPS_RECORD_V1"
internal const val EMULATOR_RENDER_FPS_MAX_PROTOCOL_BYTES = 4_096
internal const val EMULATOR_RENDER_FPS_MAX_RECORD_BYTES = 512
/** Base64 expansion of the 512-byte record, before the transport's trailing newline. */
internal const val EMULATOR_RENDER_FPS_MAX_WIRE_BASE64_CHARS = 684
internal const val EMULATOR_RENDER_FPS_MAX_LEASE_MS = 10_000L
internal const val EMULATOR_RENDER_FPS_SESSION_LEASE_MS = 3_500L
internal const val EMULATOR_RENDER_FPS_NO_PROGRESS_TIMEOUT_MS = 3_500L
internal const val EMULATOR_RENDER_FPS_UNSUPPORTED_BACKOFF_MS = 10_000L
internal const val EMULATOR_RENDER_FPS_MISSING_RECORD_SENTINEL =
    "__MIMIR_RENDER_FPS_RECORD_MISSING__"

internal sealed interface EmulatorRenderFpsRecordRead {
    data class Encoded(val value: String) : EmulatorRenderFpsRecordRead
    data object Missing : EmulatorRenderFpsRecordRead
    data object Failed : EmulatorRenderFpsRecordRead
}
internal const val EMULATOR_RENDER_FPS_INTERVAL_MS = 1_000L

/** A process identity obtained by the privileged adapter, never supplied by a HUD caller. */
internal data class EmulatorRenderFpsTargetIdentity(
    val packageName: String,
    val uid: Long,
    val pid: Int,
    val startTicks: Long,
) {
    fun isUsable(): Boolean =
        isRenderFpsPackageName(packageName) &&
            uid in 0L..RENDER_FPS_MAX_UID &&
            pid in 1..Int.MAX_VALUE &&
            startTicks > 0L
}

internal data class EmulatorRenderFpsRequest(
    val token: String,
    val packageName: String,
    val uid: Long,
    val pid: Int,
    val startTicks: Long,
    val generation: Long,
    val leaseBoottimeMs: Long,
    val intervalMs: Long = EMULATOR_RENDER_FPS_INTERVAL_MS,
)

internal data class EmulatorRenderFpsRecord(
    val token: String,
    val packageName: String,
    val uid: Long,
    val pid: Int,
    val startTicks: Long,
    val generation: Long,
    val sampleBoottimeMs: Long,
    val intervalMs: Long,
    val count: Long,
    val status: String,
)

/**
 * Parses the exact line protocol emitted by the native helper. Keeping this parser independent from
 * process launching lets the privileged adapter reject malformed, oversized, or cross-session output
 * before it reaches the HUD.
 */
internal fun parseEmulatorRenderFpsRequest(text: String): EmulatorRenderFpsRequest? {
    val fields = parseRenderFpsFields(
        text = text,
        header = EMULATOR_RENDER_FPS_REQUEST_HEADER,
        expected = setOf(
            "token",
            "package",
            "uid",
            "pid",
            "start",
            "generation",
            "lease_boottime_ms",
            "interval_ms",
        ),
        expectedCount = 8,
    ) ?: return null

    val token = fields["token"]?.takeIf(::isRenderFpsToken) ?: return null
    val packageName = fields["package"]?.takeIf(::isRenderFpsPackageName) ?: return null
    val uid = fields["uid"]?.parseUnsigned()?.takeIf { it <= RENDER_FPS_MAX_UID } ?: return null
    val pid = fields["pid"]?.parseUnsigned()
        ?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
        ?: return null
    val start = fields["start"]?.parseUnsigned() ?: return null
    val generation = fields["generation"]?.parseUnsigned() ?: return null
    val lease = fields["lease_boottime_ms"]?.parseUnsigned() ?: return null
    val interval = fields["interval_ms"]?.parseUnsigned()
        ?.takeIf { it == EMULATOR_RENDER_FPS_INTERVAL_MS }
        ?: return null
    return EmulatorRenderFpsRequest(
        token = token,
        packageName = packageName,
        uid = uid,
        pid = pid,
        startTicks = start,
        generation = generation,
        leaseBoottimeMs = lease,
        intervalMs = interval,
    )
}

internal fun parseEmulatorRenderFpsRecord(text: String): EmulatorRenderFpsRecord? {
    val fields = parseRenderFpsFields(
        text = text,
        header = EMULATOR_RENDER_FPS_RECORD_HEADER,
        expected = setOf(
            "token",
            "package",
            "uid",
            "pid",
            "start",
            "generation",
            "boot",
            "interval",
            "count",
            "status",
        ),
        expectedCount = 10,
        maxBytes = EMULATOR_RENDER_FPS_MAX_RECORD_BYTES,
    ) ?: return null

    val token = fields["token"]?.takeIf(::isRenderFpsToken) ?: return null
    val packageName = fields["package"]?.takeIf(::isRenderFpsPackageName) ?: return null
    val uid = fields["uid"]?.parseUnsigned()?.takeIf { it <= RENDER_FPS_MAX_UID } ?: return null
    val pid = fields["pid"]?.parseUnsigned()
        ?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
        ?: return null
    val start = fields["start"]?.parseUnsigned() ?: return null
    val generation = fields["generation"]?.parseUnsigned() ?: return null
    val boot = fields["boot"]?.parseUnsigned() ?: return null
    val status = fields["status"]?.takeIf(::isRenderFpsStatus) ?: return null
    val interval = fields["interval"]?.parseUnsigned()
        ?.takeIf { if (status == "ok") it in 1L..5_000L else it <= 5_000L }
        ?: return null
    val count = fields["count"]?.parseUnsigned()?.takeIf { it <= 1_000_000L } ?: return null
    if (status != "ok" && count != 0L) return null
    return EmulatorRenderFpsRecord(
        token = token,
        packageName = packageName,
        uid = uid,
        pid = pid,
        startTicks = start,
        generation = generation,
        sampleBoottimeMs = boot,
        intervalMs = interval,
        count = count,
        status = status,
    )
}

/** Formats a request only when all fields satisfy the same contract as the native parser. */
internal fun formatEmulatorRenderFpsRequest(request: EmulatorRenderFpsRequest): String? {
    if (!request.isStructurallyValid()) return null
    return buildString {
        append(EMULATOR_RENDER_FPS_REQUEST_HEADER).append('\n')
        append("token=").append(request.token).append('\n')
        append("package=").append(request.packageName).append('\n')
        append("uid=").append(request.uid).append('\n')
        append("pid=").append(request.pid).append('\n')
        append("start=").append(request.startTicks).append('\n')
        append("generation=").append(request.generation).append('\n')
        append("lease_boottime_ms=").append(request.leaseBoottimeMs).append('\n')
        append("interval_ms=").append(request.intervalMs).append('\n')
    }.takeIf { it.utf8Size() <= EMULATOR_RENDER_FPS_MAX_PROTOCOL_BYTES }
}

/** Formats a record for a bounded transport or returns null instead of producing an invalid wire row. */
internal fun formatEmulatorRenderFpsRecord(record: EmulatorRenderFpsRecord): String? {
    if (!record.isStructurallyValid()) return null
    return buildString {
        append(EMULATOR_RENDER_FPS_RECORD_HEADER).append('\n')
        append("token=").append(record.token).append('\n')
        append("package=").append(record.packageName).append('\n')
        append("uid=").append(record.uid).append('\n')
        append("pid=").append(record.pid).append('\n')
        append("start=").append(record.startTicks).append('\n')
        append("generation=").append(record.generation).append('\n')
        append("boot=").append(record.sampleBoottimeMs).append('\n')
        append("interval=").append(record.intervalMs).append('\n')
        append("count=").append(record.count).append('\n')
        append("status=").append(record.status).append('\n')
    }.takeIf { it.utf8Size() <= EMULATOR_RENDER_FPS_MAX_RECORD_BYTES }
}

internal fun EmulatorRenderFpsRecord.matchesIdentity(request: EmulatorRenderFpsRequest): Boolean =
    token == request.token &&
        packageName == request.packageName &&
        uid == request.uid &&
        pid == request.pid &&
        startTicks == request.startTicks &&
        generation == request.generation

internal fun EmulatorRenderFpsRequest.matchesTarget(target: EmulatorRenderFpsTargetIdentity): Boolean =
    packageName == target.packageName &&
        uid == target.uid &&
        pid == target.pid &&
        startTicks == target.startTicks

internal fun isEmulatorRenderFpsPackage(packageName: String): Boolean =
    MimirLaunchTargetStopPolicy.isAllowed(packageName)

/**
 * Accepts only a successful, non-empty sample from the live lease and exact process/session identity.
 * BOOTTIME is used for both lease and sample age so suspend cannot make a dead lease look current.
 */
internal fun EmulatorRenderFpsRecord.isAcceptableFor(
    request: EmulatorRenderFpsRequest,
    nowBoottimeMs: Long,
    maximumAgeMs: Long = PerformanceTelemetryStaleMs,
): Boolean {
    if (!request.isStructurallyValid() || !isStructurallyValid()) return false
    if (request.startTicks <= 0L || startTicks <= 0L) return false
    if (!matchesIdentity(request) || status != "ok" || count <= 0L) return false
    if (nowBoottimeMs < 0L || maximumAgeMs < 0L) return false
    if (request.leaseBoottimeMs <= nowBoottimeMs ||
        request.leaseBoottimeMs - nowBoottimeMs > EMULATOR_RENDER_FPS_MAX_LEASE_MS
    ) return false
    if (sampleBoottimeMs > nowBoottimeMs) return false
    return nowBoottimeMs - sampleBoottimeMs <= maximumAgeMs
}

/**
 * Owns one bounded native helper lease. A mismatched identity invalidates the lease immediately; a
 * same-identity unavailable/warming status leaves the lease retryable until it expires.
 */
internal class EmulatorRenderFpsSession(
    private val approvedPackages: Set<String>,
    private val nowBoottimeMs: () -> Long,
    private val tokenFactory: () -> String = { UUID.randomUUID().toString().replace("-", "") },
    private val leaseMs: Long = EMULATOR_RENDER_FPS_SESSION_LEASE_MS,
) {
    private var generation = 0L
    private var activeRequest: EmulatorRenderFpsRequest? = null
    private var lastProgressBoottimeMs: Long? = null
    private var unsupportedBackoffUntilBoottimeMs: Long? = null

    val request: EmulatorRenderFpsRequest?
        get() = activeRequest

    fun begin(target: EmulatorRenderFpsTargetIdentity): EmulatorRenderFpsRequest? {
        if (target.packageName !in approvedPackages || !target.isUsable()) {
            activeRequest = null
            return null
        }
        val now = nowBoottimeMs()
        if (unsupportedBackoffUntilBoottimeMs?.let { now < it } == true) return null
        if (now < 0L || leaseMs <= 0L || leaseMs > EMULATOR_RENDER_FPS_MAX_LEASE_MS ||
            now > Long.MAX_VALUE - leaseMs
        ) {
            activeRequest = null
            return null
        }
        val token = tokenFactory().takeIf(::isRenderFpsToken) ?: run {
            activeRequest = null
            return null
        }
        generation = if (generation == Long.MAX_VALUE) 1L else generation + 1L
        return EmulatorRenderFpsRequest(
            token = token,
            packageName = target.packageName,
            uid = target.uid,
            pid = target.pid,
            startTicks = target.startTicks,
            generation = generation,
            leaseBoottimeMs = now + leaseMs,
        ).also {
            activeRequest = it
            lastProgressBoottimeMs = now
            unsupportedBackoffUntilBoottimeMs = null
        }
    }

    /** Renews the same validated process/session lease without restarting its native collector. */
    fun renew(target: EmulatorRenderFpsTargetIdentity): EmulatorRenderFpsRequest? {
        val current = activeRequest
        if (current == null || !current.matchesTarget(target)) return begin(target)
        val now = nowBoottimeMs()
        if (current.leaseBoottimeMs <= now) return begin(target)
        if (now < 0L || leaseMs <= 0L || leaseMs > EMULATOR_RENDER_FPS_MAX_LEASE_MS ||
            now > Long.MAX_VALUE - leaseMs
        ) {
            activeRequest = null
            return null
        }
        return current.copy(leaseBoottimeMs = now + leaseMs)
            .also { activeRequest = it }
    }

    fun invalidate() {
        activeRequest = null
        lastProgressBoottimeMs = null
    }

    fun backoffUnsupported() {
        val now = nowBoottimeMs()
        unsupportedBackoffUntilBoottimeMs =
            now.takeIf { it >= 0L && it <= Long.MAX_VALUE - EMULATOR_RENDER_FPS_UNSUPPORTED_BACKOFF_MS }
                ?.plus(EMULATOR_RENDER_FPS_UNSUPPORTED_BACKOFF_MS)
        invalidate()
    }

    fun noProgressExpired(): Boolean {
        val now = nowBoottimeMs()
        val started = lastProgressBoottimeMs ?: return true
        return now < started || now - started > EMULATOR_RENDER_FPS_NO_PROGRESS_TIMEOUT_MS
    }

    fun observeRecord(record: EmulatorRenderFpsRecord): Boolean {
        val request = activeRequest ?: return false
        val now = nowBoottimeMs()
        if (!record.isFreshPublicationFor(request, now)) return false
        lastProgressBoottimeMs = now
        return true
    }

    fun acceptRecord(record: EmulatorRenderFpsRecord): EmulatorRenderFpsRecord? {
        val request = activeRequest ?: return null
        val now = nowBoottimeMs()
        if (request.leaseBoottimeMs <= now) {
            activeRequest = null
            return null
        }
        if (!record.matchesIdentity(request)) {
            activeRequest = null
            return null
        }
        if (!observeRecord(record)) return null
        if (record.status != "ok" || record.count <= 0L) return null
        return record
    }

    fun accept(record: EmulatorRenderFpsRecord): Long? = acceptRecord(record)?.count
}

private const val RENDER_FPS_MAX_UID = 4_294_967_295L

private fun EmulatorRenderFpsRequest.isStructurallyValid(): Boolean =
    isRenderFpsToken(token) &&
        isRenderFpsPackageName(packageName) &&
        uid in 0L..RENDER_FPS_MAX_UID &&
        pid in 1..Int.MAX_VALUE &&
        startTicks >= 0L &&
        generation >= 0L &&
        leaseBoottimeMs >= 0L &&
        intervalMs == EMULATOR_RENDER_FPS_INTERVAL_MS

private fun EmulatorRenderFpsRecord.isStructurallyValid(): Boolean =
    isRenderFpsToken(token) &&
        isRenderFpsPackageName(packageName) &&
        uid in 0L..RENDER_FPS_MAX_UID &&
        pid in 1..Int.MAX_VALUE &&
        startTicks >= 0L &&
        generation >= 0L &&
        sampleBoottimeMs >= 0L &&
        (if (status == "ok") intervalMs in 1L..5_000L else intervalMs <= 5_000L) &&
        count in 0L..1_000_000L &&
        (status == "ok" || count == 0L) &&
        isRenderFpsStatus(status)

private fun EmulatorRenderFpsRecord.isFreshPublicationFor(
    request: EmulatorRenderFpsRequest,
    nowBoottimeMs: Long,
    maximumAgeMs: Long = PerformanceTelemetryStaleMs,
): Boolean {
    if (!request.isStructurallyValid() || !isStructurallyValid()) return false
    if (!matchesIdentity(request) || nowBoottimeMs < 0L || maximumAgeMs < 0L) return false
    if (request.leaseBoottimeMs <= nowBoottimeMs ||
        request.leaseBoottimeMs - nowBoottimeMs > EMULATOR_RENDER_FPS_MAX_LEASE_MS
    ) return false
    if (sampleBoottimeMs > nowBoottimeMs) return false
    return nowBoottimeMs - sampleBoottimeMs <= maximumAgeMs
}

private fun parseRenderFpsFields(
    text: String,
    header: String,
    expected: Set<String>,
    expectedCount: Int,
    maxBytes: Int = EMULATOR_RENDER_FPS_MAX_PROTOCOL_BYTES,
): Map<String, String>? {
    if (text.isEmpty() || text.utf8Size() > maxBytes || !text.all { it.code in 0..127 }) return null
    if (!text.endsWith('\n')) return null
    val lines = text.removeSuffix("\n").split('\n')
    if (lines.size < 2 || lines.first() != header) return null
    val fields = LinkedHashMap<String, String>(expectedCount)
    for (line in lines.drop(1)) {
        val equals = line.indexOf('=')
        if (equals <= 0) return null
        val key = line.substring(0, equals)
        if (key !in expected || fields.put(key, line.substring(equals + 1)) != null) return null
    }
    return fields.takeIf { it.size == expectedCount }
}

private fun String.parseUnsigned(): Long? =
    takeIf { isNotEmpty() && all { it.isDigit() } }?.toLongOrNull()

private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

private fun isRenderFpsToken(value: String): Boolean =
    value.length in 8..64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

private fun isRenderFpsPackageName(value: String): Boolean {
    if (value.isEmpty() || value.length > 128) return false
    var component = false
    for (char in value) {
        if (char == '.') {
            if (!component) return false
            component = false
        } else if ((char in 'a'..'z') || (char in 'A'..'Z') ||
            (char in '0'..'9') || char == '_'
        ) {
            component = true
        } else {
            return false
        }
    }
    return component
}

private fun isRenderFpsStatus(value: String): Boolean = when (value) {
    "ok", "warming", "unavailable", "ambiguous", "lost", "unsupported", "stale" -> true
    else -> false
}

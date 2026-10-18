package com.gruning.mimir.tweaker

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Pure RCT-1 transport vocabulary. Its root is the frozen post-unlock topology.
 *
 * CORRECTED 2026-09-06 (guardian map §4.2): this used to say "deliberately not constructed by
 * production code... this does not authorize a live construction site." That is no longer true --
 * `MimirTweakerService.runSafeRct1ChannelProbe` constructs this class in production. What the
 * probe does NOT do is authorize a live *decode*: it passes `decode = { null }`, so the port built
 * from this topology can never reach `Received`. The class is live; the decode boundary is still
 * the fail-closed one.
 */
internal class GuardianReceiptChannelTopology(taskNonce: String) {
    val sessionDirectory: String
    val directory: String
    /** Fixed, nonce-bound marker component; never caller supplied after topology construction. */
    val markerNonce: String

    init {
        require(TASK_NONCE.matches(taskNonce)) { "invalid task nonce" }
        val digest = sha256(taskNonce)
        sessionDirectory = digest.take(16)
        markerNonce = digest.drop(16).take(16)
        check(SESSION_DIRECTORY.matches(sessionDirectory))
        check(MARKER_NONCE.matches(markerNonce))
        directory = "${GuardianReceiptChannelPostUnlockTopology.ROOT}/$sessionDirectory"
    }

    fun record(record: GuardianReceiptChannelRecord): String = "$directory/${record.fileName}"
    fun stage(record: GuardianReceiptChannelRecord): String = "$directory/.${record.fileName}"

    companion object {
        private val TASK_NONCE = Regex("[A-Za-z0-9_-]{8,128}")
        private val SESSION_DIRECTORY = Regex("[0-9a-f]{16}")
        private val MARKER_NONCE = Regex("[0-9a-f]{16}")
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Exact Probe-41R-backed post-unlock receipt topology and READY metadata expectation.
 * This is policy data only: it neither creates a directory nor constructs a live receipt port.
 */
internal object GuardianReceiptChannelPostUnlockTopology {
    const val ROOT = "/data/local/tmp/mimir-rct1"
    const val OWNER_UID = 0
    const val OWNER_GID = 0
    const val DIRECTORY_MODE_OCTAL = "700"
    const val RECORD_MODE_OCTAL = "600"
    const val RECORD_SELINUX_LABEL = "u:object_r:shell_data_file:s0"
    const val RECEIPT_CHUNK_BYTES = 128
    val READY_EXPECTATION = GuardianReadyChannelExpectation(
        OWNER_UID,
        OWNER_GID,
        RECORD_MODE_OCTAL,
        RECORD_SELINUX_LABEL,
        exactBytesConfirmed = true,
        forbiddenWriterUids = setOf(2000, 1000),
    )
}

internal enum class GuardianReceiptChannelRecord(val fileName: String) { READY("R"), TERMINAL("T"), EXIT("X") }

/** Fixed statements only; no caller-controlled path or command crosses this seam. */
internal object GuardianReceiptChannelRenderer {
    const val MAX_WRAPPED_STATEMENT_BYTES = 198
    /** Device-proven receipt read unit; it is not a general PServer transport-cap policy. */
    const val RECEIPT_CHUNK_BYTES = GuardianReceiptChannelPostUnlockTopology.RECEIPT_CHUNK_BYTES
    /** Derived from the parser's safety bound, not evidence of a larger device transport capacity. */
    val MAX_RECEIPT_CHUNKS: Int =
        (GuardianReceiptChannelParser.MAX_FRAMED_RECORD_BYTES + RECEIPT_CHUNK_BYTES - 1) / RECEIPT_CHUNK_BYTES

    fun stat(topology: GuardianReceiptChannelTopology, record: GuardianReceiptChannelRecord): String? =
        render(topology, "stat -c '%u:%g:%a:%C:%s' '${topology.record(record)}'")

    fun digest(topology: GuardianReceiptChannelTopology, record: GuardianReceiptChannelRecord): String? =
        render(topology, "sha256sum '${topology.record(record)}'|cut -d' ' -f1")

    /**
     * A fixed indexed read of one fixture-sized chunk.  The index is derived from the attested
     * stat size, never supplied by a caller.  Whole-record base64 is deliberately forbidden.
     */
    fun chunk(
        topology: GuardianReceiptChannelTopology,
        record: GuardianReceiptChannelRecord,
        index: Int,
    ): String? {
        if (index !in 0 until MAX_RECEIPT_CHUNKS) return null
        return render(
            topology,
            "dd if='${topology.record(record)}' bs=$RECEIPT_CHUNK_BYTES skip=$index count=1 2>/dev/null|base64 -w0",
        )
    }

    fun preArmSweep(topology: GuardianReceiptChannelTopology): List<String>? = fixed(topology) { path -> "rm -f '$path'" }

    fun publication(topology: GuardianReceiptChannelTopology, record: GuardianReceiptChannelRecord): String? =
        render(topology, "mv -f '${topology.stage(record)}' '${topology.record(record)}'")

    fun cleanup(topology: GuardianReceiptChannelTopology): List<String>? {
        val files = fixed(topology) { path -> "rm -f '$path'" } ?: return null
        val removeDirectory = render(topology, "rmdir '${topology.directory}'") ?: return null
        return files + removeDirectory
    }

    private fun fixed(topology: GuardianReceiptChannelTopology, command: (String) -> String): List<String>? {
        val paths = GuardianReceiptChannelRecord.entries.flatMap { listOf(topology.stage(it), topology.record(it)) }
        return paths.map { path -> render(topology, command(path)) }.takeIf { statements -> statements.all { it != null } }
            ?.filterNotNull()
    }

    /**
     * One checked, anchored line binds every fixed lifecycle statement to this topology's nonce.
     * The body length is authoritative, so PServer's observed removal of this final LF cannot
     * make a body/suffix boundary ambiguous.
     */
    private fun render(topology: GuardianReceiptChannelTopology, statement: String): String? =
        "b=\$($statement 2>/dev/null);s=\$?;printf '${GuardianReceiptChannelParser.marker(topology.markerNonce)}:%s:%s:%s\\n' \"\$s\" \"\${#b}\" \"\$b\""
            .takeIf(::valid)

    private fun valid(statement: String): Boolean =
        statement.toByteArray(Charsets.UTF_8).size <= MAX_WRAPPED_STATEMENT_BYTES &&
            !statement.contains('\n') && !statement.contains('\u0000')
}

internal data class GuardianReceiptChannelStat(
    val ownerUid: Int,
    val ownerGid: Int,
    val modeOctal: String,
    val seLinuxLabel: String,
    val byteCount: Int,
) {
    init {
        require(ownerUid >= 0 && ownerGid >= 0 && Regex("[0-7]{3,4}").matches(modeOctal))
        require(seLinuxLabel.isNotBlank() && !seLinuxLabel.any(Char::isWhitespace))
        require(byteCount in 8..GuardianReceiptChannelParser.MAX_FRAMED_RECORD_BYTES)
    }
}

/**
 * Lossless model of PServer's public reply boundary: one UTF-8 String after its `trim()` and
 * `"null"` sentinel handling. Consumers never receive a pre-split byte channel.
 */
internal class GuardianReceiptChannelRawReply private constructor(
    val text: String?,
    /** A caller that still has pre-PServer bytes must preserve trim loss as ambiguity. */
    val trimAmbiguity: Boolean,
) {
    companion object {
        fun fromPServerUtf8(bytes: ByteArray?): GuardianReceiptChannelRawReply {
            val decoded = bytes?.toString(StandardCharsets.UTF_8)
            val trimmed = decoded?.trim()
            // The checked renderer emits precisely one final LF. PServer's trim removes it at
            // the Binder boundary; that one loss is safe because the declared ASCII body length
            // is parsed below. Any other trim loss remains ambiguous and fail-closed.
            val onlyTerminalLf = decoded != null && trimmed != null && decoded == "$trimmed\n"
            return GuardianReceiptChannelRawReply(trimmed?.takeUnless { it == "null" }, decoded != trimmed && !onlyTerminalLf)
        }

        fun fromPServerOutput(output: String?): GuardianReceiptChannelRawReply =
            // PServerClient already exposes the post-trim UTF-8 String. Do not trim again.
            GuardianReceiptChannelRawReply(output?.takeUnless { it == "null" }, false)
    }
}

internal object GuardianReceiptChannelParser {
    const val MAX_RECEIPT_BYTES = 8_192
    const val MAX_FRAMED_RECORD_BYTES = MAX_RECEIPT_BYTES + 8
    private const val MAGIC = 0x4d345231 // M4R1
    private const val MARKER_PREFIX = "M4R"
    private val BASE64 = Regex("[A-Za-z0-9+/]*={0,2}")
    private val SHA256 = Regex("[0-9a-f]{64}")

    fun marker(nonce: String): String {
        require(Regex("[0-9a-f]{16}").matches(nonce)) { "invalid marker nonce" }
        return "$MARKER_PREFIX:$nonce"
    }

    /** Exact `%u:%g:%a:%C:%s`, in a one-line checked body. */
    fun stat(reply: GuardianReceiptChannelRawReply, markerNonce: String): GuardianReceiptChannelStat? {
        val body = successfulBody(reply, markerNonce, allowEmpty = false) ?: return null
        val match = STAT.matchEntire(body) ?: return null
        val uid = match.groupValues[1].toIntOrNull() ?: return null
        val gid = match.groupValues[2].toIntOrNull() ?: return null
        val bytes = match.groupValues[5].toIntOrNull() ?: return null
        return runCatching { GuardianReceiptChannelStat(uid, gid, match.groupValues[3], match.groupValues[4], bytes) }.getOrNull()
    }

    /** Strict ASCII-base64 chunk body. The caller enforces index, count and whole-record digest. */
    fun frame(reply: GuardianReceiptChannelRawReply, markerNonce: String): ByteArray? {
        val encoded = successfulBody(reply, markerNonce, allowEmpty = false) ?: return null
        if (!BASE64.matches(encoded) || encoded.length > MAX_CHUNK_BASE64_CHARS) return null
        val framed = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
        if (framed.size !in 1..GuardianReceiptChannelRenderer.RECEIPT_CHUNK_BYTES) return null
        // Reject padding aliases and transport text that decoded but was not strict canonical base64.
        if (Base64.getEncoder().encodeToString(framed) != encoded) return null
        return framed
    }

    fun digest(reply: GuardianReceiptChannelRawReply, markerNonce: String): String? =
        successfulBody(reply, markerNonce, allowEmpty = false)?.takeIf(SHA256::matches)

    /** Exact framed byte grammar: M4R1 + unsigned big-endian length + exactly that many bytes. */
    fun decodeFrame(raw: ByteArray?): ByteArray? {
        if (raw == null || raw.size !in 8..MAX_FRAMED_RECORD_BYTES) return null
        val buffer = ByteBuffer.wrap(raw)
        if (buffer.int != MAGIC) return null
        val length = buffer.int
        if (length !in 1..MAX_RECEIPT_BYTES || raw.size != length + 8) return null
        return ByteArray(length).also(buffer::get)
    }

    fun encodeFrame(payload: ByteArray): ByteArray? {
        if (payload.size !in 1..MAX_RECEIPT_BYTES) return null
        return ByteBuffer.allocate(payload.size + 8).putInt(MAGIC).putInt(payload.size).put(payload).array()
    }

    /** A lifecycle command has no body after PServer's trim; only one unambiguous successful marker is accepted. */
    fun acknowledgement(reply: GuardianReceiptChannelRawReply, markerNonce: String): Boolean {
        return successfulBody(reply, markerNonce, allowEmpty = true) == ""
    }

    private fun successfulBody(reply: GuardianReceiptChannelRawReply, markerNonce: String, allowEmpty: Boolean): String? {
        val raw = reply.text ?: return null
        if (reply.trimAmbiguity || raw == "null" || raw.any { it.code !in 0x20..0x7e }) return null
        val prefix = "${marker(markerNonce)}:"
        if (!raw.startsWith(prefix)) return null
        val exitEnd = raw.indexOf(':', prefix.length)
        if (exitEnd < 0) return null
        val lengthEnd = raw.indexOf(':', exitEnd + 1)
        if (lengthEnd < 0) return null
        val exit = decimal(raw.substring(prefix.length, exitEnd)) ?: return null
        val declaredLength = decimal(raw.substring(exitEnd + 1, lengthEnd)) ?: return null
        if (exit != 0L || declaredLength > MAX_REPLY_BODY_CHARS) return null
        val body = raw.substring(lengthEnd + 1)
        if (body.length.toLong() != declaredLength || (!allowEmpty && body.isEmpty())) return null
        return body
    }

    private fun decimal(value: String): Long? =
        value.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }?.toLongOrNull()

    private val STAT = Regex("(\\d+):(\\d+):([0-7]{3,4}):([^\\s\\u0000]+):(\\d+)")
    private const val MAX_REPLY_BODY_CHARS = ((MAX_FRAMED_RECORD_BYTES + 2) / 3) * 4
    private const val MAX_CHUNK_BASE64_CHARS = ((GuardianReceiptChannelRenderer.RECEIPT_CHUNK_BYTES + 2) / 3) * 4
}

internal sealed interface GuardianReceiptChannelExecution {
    data class Reply(val raw: GuardianReceiptChannelRawReply) : GuardianReceiptChannelExecution
    data object Absent : GuardianReceiptChannelExecution
    data class Transport(val detail: String) : GuardianReceiptChannelExecution
}

internal fun interface GuardianReceiptChannelExecutor { fun execute(statement: String): GuardianReceiptChannelExecution }

/**
 * Adapts exactly one raw PServer transaction to the receipt channel without changing the
 * renderer statement or replaying an ambiguous result. Live construction remains separate.
 */
internal class GuardianReceiptChannelPServerExecutor(
    private val executeRaw: (String) -> Result<PServerRawReply>,
) : GuardianReceiptChannelExecutor {
    override fun execute(statement: String): GuardianReceiptChannelExecution {
        val result = try {
            executeRaw(statement)
        } catch (error: Throwable) {
            return GuardianReceiptChannelExecution.Transport(error.message ?: "PServer transport failure")
        }
        return result.fold(
            onSuccess = { reply ->
                when {
                    !reply.accepted -> GuardianReceiptChannelExecution.Transport("transact rejected")
                    reply.bytes == null -> GuardianReceiptChannelExecution.Absent
                    else -> GuardianReceiptChannelExecution.Reply(
                        GuardianReceiptChannelRawReply.fromPServerUtf8(reply.bytes),
                    )
                }
            },
            onFailure = { error ->
                GuardianReceiptChannelExecution.Transport(error.message ?: "PServer transport failure")
            },
        )
    }
}

internal sealed interface GuardianReceiptChannelRead {
    data class Stable(val payload: ByteArray, val stat: GuardianReceiptChannelStat) : GuardianReceiptChannelRead
    data object Absent : GuardianReceiptChannelRead
    data object Malformed : GuardianReceiptChannelRead
    data object Transport : GuardianReceiptChannelRead
    data object TimedOut : GuardianReceiptChannelRead
}

/**
 * A bounded adapter for the existing READY port. It performs no sleeps/retries.
 *
 * CORRECTED 2026-09-06 (guardian map §4.2): "has no live construction site" is no longer true --
 * `MimirTweakerService.runSafeRct1ChannelProbe` constructs one, with `decode = { null }`, so it can
 * observe transport-level presence/absence but can never decode a receipt into `Received`.
 */
internal class GuardianReceiptChannelReadyPort(
    private val topology: GuardianReceiptChannelTopology,
    private val executor: GuardianReceiptChannelExecutor,
    private val expected: GuardianReadyChannelExpectation,
    private val runtimeAppUid: Int,
    private val decode: (ByteArray) -> SignedRestoreGuardianReadyReceipt?,
    private val nowElapsedRealtimeMs: () -> Long,
) : GuardianReadyReceiptPort {
    init { require(runtimeAppUid >= 0) }

    override fun loadUntil(deadlineElapsedRealtimeMs: Long): GuardianReadyLoad {
        if (nowElapsedRealtimeMs() > deadlineElapsedRealtimeMs) return GuardianReadyLoad.TimedOut
        return when (val read = readStable(deadlineElapsedRealtimeMs)) {
            GuardianReceiptChannelRead.Absent -> GuardianReadyLoad.Unavailable
            GuardianReceiptChannelRead.TimedOut -> GuardianReadyLoad.TimedOut
            GuardianReceiptChannelRead.Malformed, GuardianReceiptChannelRead.Transport -> GuardianReadyLoad.Ambiguous
            is GuardianReceiptChannelRead.Stable -> {
                if (nowElapsedRealtimeMs() > deadlineElapsedRealtimeMs) return GuardianReadyLoad.TimedOut
                if (read.stat.ownerUid == runtimeAppUid || read.stat.ownerUid in expected.forbiddenWriterUids) return GuardianReadyLoad.Ambiguous
                val evidence = runCatching {
                    GuardianReadyChannelEvidence(read.stat.ownerUid, read.stat.ownerGid, read.stat.modeOctal, read.stat.seLinuxLabel, exactBytesConfirmed = true)
                }.getOrNull() ?: return GuardianReadyLoad.Ambiguous
                if (!GuardianReadyChannelPolicy.matches(evidence, expected)) GuardianReadyLoad.Ambiguous
                else {
                    val signed = decode(read.payload) ?: return GuardianReadyLoad.Ambiguous
                    if (nowElapsedRealtimeMs() > deadlineElapsedRealtimeMs) GuardianReadyLoad.TimedOut
                    else GuardianReadyLoad.Received(signed, evidence)
                }
            }
        }
    }

    private fun readStable(deadline: Long): GuardianReceiptChannelRead {
        fun expired() = nowElapsedRealtimeMs() > deadline
        data class Sample(
            val stat: GuardianReceiptChannelStat,
            val digest: String,
            val framed: ByteArray,
        )
        fun sample(): Pair<GuardianReceiptChannelRead?, Sample?> {
            if (expired()) return GuardianReceiptChannelRead.TimedOut to null
            val statStatement = GuardianReceiptChannelRenderer.stat(topology, GuardianReceiptChannelRecord.READY)
                ?: return GuardianReceiptChannelRead.Malformed to null
            val statResult = executor.execute(statStatement)
            if (expired()) return GuardianReceiptChannelRead.TimedOut to null
            val stat = when (statResult) {
                GuardianReceiptChannelExecution.Absent -> return GuardianReceiptChannelRead.Absent to null
                is GuardianReceiptChannelExecution.Transport -> return GuardianReceiptChannelRead.Transport to null
                is GuardianReceiptChannelExecution.Reply -> GuardianReceiptChannelParser.stat(statResult.raw, topology.markerNonce) ?: return GuardianReceiptChannelRead.Malformed to null
            }
            val digestStatement = GuardianReceiptChannelRenderer.digest(topology, GuardianReceiptChannelRecord.READY)
                ?: return GuardianReceiptChannelRead.Malformed to null
            val digestResult = executor.execute(digestStatement)
            if (expired()) return GuardianReceiptChannelRead.TimedOut to null
            val digest = when (digestResult) {
                GuardianReceiptChannelExecution.Absent -> return GuardianReceiptChannelRead.Absent to null
                is GuardianReceiptChannelExecution.Transport -> return GuardianReceiptChannelRead.Transport to null
                is GuardianReceiptChannelExecution.Reply -> GuardianReceiptChannelParser.digest(digestResult.raw, topology.markerNonce) ?: return GuardianReceiptChannelRead.Malformed to null
            }
            val chunkCount = (stat.byteCount + GuardianReceiptChannelRenderer.RECEIPT_CHUNK_BYTES - 1) /
                GuardianReceiptChannelRenderer.RECEIPT_CHUNK_BYTES
            if (chunkCount !in 1..GuardianReceiptChannelRenderer.MAX_RECEIPT_CHUNKS) return GuardianReceiptChannelRead.Malformed to null
            val assembled = ArrayList<ByteArray>(chunkCount)
            repeat(chunkCount) { index ->
                val chunkStatement = GuardianReceiptChannelRenderer.chunk(topology, GuardianReceiptChannelRecord.READY, index)
                    ?: return GuardianReceiptChannelRead.Malformed to null
                val chunkResult = executor.execute(chunkStatement)
                if (expired()) return GuardianReceiptChannelRead.TimedOut to null
                val chunk = when (chunkResult) {
                    GuardianReceiptChannelExecution.Absent -> return GuardianReceiptChannelRead.Absent to null
                    is GuardianReceiptChannelExecution.Transport -> return GuardianReceiptChannelRead.Transport to null
                    is GuardianReceiptChannelExecution.Reply -> GuardianReceiptChannelParser.frame(chunkResult.raw, topology.markerNonce)
                        ?: return GuardianReceiptChannelRead.Malformed to null
                }
                val expectedBytes = minOf(
                    GuardianReceiptChannelRenderer.RECEIPT_CHUNK_BYTES,
                    stat.byteCount - (index * GuardianReceiptChannelRenderer.RECEIPT_CHUNK_BYTES),
                )
                if (chunk.size != expectedBytes) return GuardianReceiptChannelRead.Malformed to null
                assembled += chunk
            }
            val framed = ByteArray(stat.byteCount)
            var offset = 0
            assembled.forEach { chunk -> chunk.copyInto(framed, destinationOffset = offset).also { offset += chunk.size } }
            if (offset != stat.byteCount || sha256Hex(framed) != digest || GuardianReceiptChannelParser.decodeFrame(framed) == null) {
                return GuardianReceiptChannelRead.Malformed to null
            }
            return null to Sample(stat, digest, framed)
        }
        // First explicit stat/digest/chunks and second sample: all metadata and bytes must settle exactly.
        val first = sample(); if (first.first != null) return first.first!!
        val second = sample(); if (second.first != null) return second.first!!
        val firstSample = first.second ?: return GuardianReceiptChannelRead.Malformed
        val secondSample = second.second ?: return GuardianReceiptChannelRead.Malformed
        if (expired()) return GuardianReceiptChannelRead.TimedOut
        return if (
            firstSample.stat == secondSample.stat &&
            firstSample.digest == secondSample.digest &&
            firstSample.framed.contentEquals(secondSample.framed)
        ) {
            GuardianReceiptChannelParser.decodeFrame(firstSample.framed)
                ?.let { GuardianReceiptChannelRead.Stable(it, firstSample.stat) }
                ?: GuardianReceiptChannelRead.Malformed
        } else GuardianReceiptChannelRead.Malformed
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

}

/** Pure fixed-path lifecycle planning. No action is recursive and no production factory exists. */
internal object GuardianReceiptChannelLifecycle {
    fun preArmSweep(topology: GuardianReceiptChannelTopology): GuardianReceiptChannelLifecyclePlan =
        GuardianReceiptChannelRenderer.preArmSweep(topology)?.let(GuardianReceiptChannelLifecyclePlan::Statements)
            ?: GuardianReceiptChannelLifecyclePlan.Ambiguous

    fun publish(topology: GuardianReceiptChannelTopology, record: GuardianReceiptChannelRecord): GuardianReceiptChannelLifecyclePlan =
        GuardianReceiptChannelRenderer.publication(topology, record)?.let { GuardianReceiptChannelLifecyclePlan.Statements(listOf(it)) }
            ?: GuardianReceiptChannelLifecyclePlan.Ambiguous

    fun cleanup(topology: GuardianReceiptChannelTopology): GuardianReceiptChannelLifecyclePlan =
        GuardianReceiptChannelRenderer.cleanup(topology)?.let(GuardianReceiptChannelLifecyclePlan::Statements)
            ?: GuardianReceiptChannelLifecyclePlan.Ambiguous

    /** Reducer input is a single directory listing, never a recursive filesystem walk. */
    fun reduce(entries: Set<String>, event: GuardianReceiptChannelLifecycleEvent): GuardianReceiptChannelLifecycleResult {
        val permitted = GuardianReceiptChannelRecord.entries.flatMap { listOf(it.fileName, ".${it.fileName}") }.toSet()
        if (entries.any { it !in permitted || it.contains('/') || it.isBlank() }) return GuardianReceiptChannelLifecycleResult.ForeignResidue
        return when (event) {
            GuardianReceiptChannelLifecycleEvent.PreArmSweep -> GuardianReceiptChannelLifecycleResult.SweepRequired(entries)
            is GuardianReceiptChannelLifecycleEvent.Publication -> if (".${event.record.fileName}" in entries) GuardianReceiptChannelLifecycleResult.PublishRequired(event.record) else GuardianReceiptChannelLifecycleResult.StageMissing
            GuardianReceiptChannelLifecycleEvent.Cleanup -> if (entries.isEmpty()) GuardianReceiptChannelLifecycleResult.Clean else GuardianReceiptChannelLifecycleResult.CleanupRequired(entries)
        }
    }
}

/** Renderer failure is an explicit no-command outcome; callers must not recover by throwing. */
internal sealed interface GuardianReceiptChannelLifecyclePlan {
    data class Statements(val values: List<String>) : GuardianReceiptChannelLifecyclePlan
    data object Ambiguous : GuardianReceiptChannelLifecyclePlan
}

internal sealed interface GuardianReceiptChannelLifecycleEvent {
    data object PreArmSweep : GuardianReceiptChannelLifecycleEvent
    data class Publication(val record: GuardianReceiptChannelRecord) : GuardianReceiptChannelLifecycleEvent
    data object Cleanup : GuardianReceiptChannelLifecycleEvent
}

internal sealed interface GuardianReceiptChannelLifecycleResult {
    data class SweepRequired(val staleEntries: Set<String>) : GuardianReceiptChannelLifecycleResult
    data class PublishRequired(val record: GuardianReceiptChannelRecord) : GuardianReceiptChannelLifecycleResult
    data object StageMissing : GuardianReceiptChannelLifecycleResult
    data class CleanupRequired(val residue: Set<String>) : GuardianReceiptChannelLifecycleResult
    data object Clean : GuardianReceiptChannelLifecycleResult
    data object ForeignResidue : GuardianReceiptChannelLifecycleResult
}

/**
 * Pure root/session-directory attestation vocabulary. This observes a single `lstat` result
 * supplied by a future fixed runner; it does not access a filesystem or render a shell command.
 * In particular, recovery is an explicit one-shot decision, never a recursive deletion policy.
 */
internal enum class GuardianReceiptChannelNodeKind { ABSENT, DIRECTORY, SYMLINK, OTHER }

internal data class GuardianReceiptChannelLstatObservation(
    val kind: GuardianReceiptChannelNodeKind,
    val ownerUid: Int? = null,
    val ownerGid: Int? = null,
    val modeOctal: String? = null,
    /** Null, blank, or whitespace-bearing label means the raw observation is not trustworthy. */
    val seLinuxLabel: String? = null,
)

/**
 * A non-recursive listing of the nominated candidate root.  The expected child is not a
 * convenience name: it is the exact nonce-derived 16-hex session directory.  Records belong
 * below that directory and are deliberately foreign at this scope.
 */
internal data class GuardianReceiptChannelCandidateRootObservation(
    val root: GuardianReceiptChannelLstatObservation,
    val expectedSessionDirectory: String,
    /** Non-recursive immediate children only. */
    val childNames: Set<String>,
) {
    init {
        require(expectedSessionDirectory.matches(Regex("[0-9a-f]{16}"))) {
            "expected session directory must be the derived 16-hex child"
        }
    }
}

/**
 * A separate non-recursive listing of the exact derived session directory.  A sibling session
 * directory is never a known record here, and a record is never a known child at the root.
 */
internal data class GuardianReceiptChannelSessionDirectoryObservation(
    val session: GuardianReceiptChannelLstatObservation,
    val childNames: Set<String>,
)

internal sealed interface GuardianReceiptChannelRootAttestationState {
    data object Initial : GuardianReceiptChannelRootAttestationState
    /** A `mkdir` without `-p` was requested; a second lstat is mandatory. */
    data object AwaitingCreateLstat : GuardianReceiptChannelRootAttestationState
    /** One empty wrong-owner root was removed and recreated; a second recovery is forbidden. */
    data object AwaitingRecoveryLstat : GuardianReceiptChannelRootAttestationState
}

internal enum class GuardianReceiptChannelRootStopReason {
    Symlink,
    NonDirectory,
    WrongOwnedNonEmpty,
    ForeignChild,
    MetadataAmbiguous,
    CreateDidNotMaterialize,
    ResquatAfterRecovery,
}

internal sealed interface GuardianReceiptChannelRootAttestationDecision {
    /** Model only: caller may issue one `mkdir <root>` and must not add `-p`. */
    data object CreateWithoutParents : GuardianReceiptChannelRootAttestationDecision
    /** Model only: caller may issue exactly `rmdir <root>` then one `mkdir <root>` without `-p`. */
    data object RecoverWrongOwnedEmpty : GuardianReceiptChannelRootAttestationDecision
    /** Candidate root is exact and contains only its exact derived session child. */
    data object CandidateRootReady : GuardianReceiptChannelRootAttestationDecision
    data class StopForOperator(val reason: GuardianReceiptChannelRootStopReason) : GuardianReceiptChannelRootAttestationDecision
}

internal data class GuardianReceiptChannelRootAttestationResult(
    val state: GuardianReceiptChannelRootAttestationState,
    val decision: GuardianReceiptChannelRootAttestationDecision,
)

internal sealed interface GuardianReceiptChannelSessionAttestationDecision {
    /** Model only: caller may issue one `mkdir <session>` and must not add `-p`. */
    data object CreateWithoutParents : GuardianReceiptChannelSessionAttestationDecision
    /** Model only: caller may issue exactly `rmdir <session>` then one `mkdir <session>` without `-p`. */
    data object RecoverWrongOwnedEmpty : GuardianReceiptChannelSessionAttestationDecision
    /** Session directory is exact and contains only fixed receipt record names. */
    data object SessionDirectoryReady : GuardianReceiptChannelSessionAttestationDecision
    data class StopForOperator(val reason: GuardianReceiptChannelRootStopReason) : GuardianReceiptChannelSessionAttestationDecision
}

internal data class GuardianReceiptChannelSessionAttestationResult(
    val state: GuardianReceiptChannelRootAttestationState,
    val decision: GuardianReceiptChannelSessionAttestationDecision,
)

/** Pure arm gate: a candidate root is never sufficient without an independently exact session. */
internal object GuardianReceiptChannelArmReadiness {
    fun isReady(
        candidateRoot: GuardianReceiptChannelRootAttestationResult,
        sessionDirectory: GuardianReceiptChannelSessionAttestationResult,
    ): Boolean =
        candidateRoot.decision == GuardianReceiptChannelRootAttestationDecision.CandidateRootReady &&
            sessionDirectory.decision == GuardianReceiptChannelSessionAttestationDecision.SessionDirectoryReady
}

/**
 * Attests exactly one root lstat/listing snapshot. The label is deliberately validated only for
 * observability (nonblank, single token); exact READY-record metadata is frozen separately above.
 */
internal object GuardianReceiptChannelRootAttestationReducer {
    /**
     * Candidate-root policy intentionally has no stale-session sweep.  A second session, a
     * record, or any other child is an operator stop until a separately adjudicated policy says
     * otherwise.
     */
    fun reduce(
        state: GuardianReceiptChannelRootAttestationState,
        observed: GuardianReceiptChannelCandidateRootObservation,
    ): GuardianReceiptChannelRootAttestationResult = reduceDirectory(
        state = state,
        directory = observed.root,
        childNames = observed.childNames,
        allowedChildren = setOf(observed.expectedSessionDirectory),
        create = GuardianReceiptChannelRootAttestationDecision.CreateWithoutParents,
        recover = GuardianReceiptChannelRootAttestationDecision.RecoverWrongOwnedEmpty,
        ready = GuardianReceiptChannelRootAttestationDecision.CandidateRootReady,
        result = ::GuardianReceiptChannelRootAttestationResult,
        stop = { current, reason ->
            GuardianReceiptChannelRootAttestationResult(
                current,
                GuardianReceiptChannelRootAttestationDecision.StopForOperator(reason),
            )
        },
    )

    private val recordNames = GuardianReceiptChannelRecord.entries
        .flatMap { listOf(it.fileName, ".${it.fileName}") }
        .toSet()

    /** The session scope is intentionally distinct from the candidate-root scope. */
    fun reduceSession(
        state: GuardianReceiptChannelRootAttestationState,
        observed: GuardianReceiptChannelSessionDirectoryObservation,
    ): GuardianReceiptChannelSessionAttestationResult = reduceDirectory(
        state = state,
        directory = observed.session,
        childNames = observed.childNames,
        allowedChildren = recordNames,
        create = GuardianReceiptChannelSessionAttestationDecision.CreateWithoutParents,
        recover = GuardianReceiptChannelSessionAttestationDecision.RecoverWrongOwnedEmpty,
        ready = GuardianReceiptChannelSessionAttestationDecision.SessionDirectoryReady,
        result = ::GuardianReceiptChannelSessionAttestationResult,
        stop = { current, reason ->
            GuardianReceiptChannelSessionAttestationResult(
                current,
                GuardianReceiptChannelSessionAttestationDecision.StopForOperator(reason),
            )
        },
    )

    private fun <D, R> reduceDirectory(
        state: GuardianReceiptChannelRootAttestationState,
        directory: GuardianReceiptChannelLstatObservation,
        childNames: Set<String>,
        allowedChildren: Set<String>,
        create: D,
        recover: D,
        ready: D,
        result: (GuardianReceiptChannelRootAttestationState, D) -> R,
        stop: (GuardianReceiptChannelRootAttestationState, GuardianReceiptChannelRootStopReason) -> R,
    ): R {
        if (directory.kind == GuardianReceiptChannelNodeKind.SYMLINK) return stop(state, GuardianReceiptChannelRootStopReason.Symlink)
        if (directory.kind != GuardianReceiptChannelNodeKind.ABSENT && directory.kind != GuardianReceiptChannelNodeKind.DIRECTORY) {
            return stop(state, GuardianReceiptChannelRootStopReason.NonDirectory)
        }
        if (state == GuardianReceiptChannelRootAttestationState.AwaitingRecoveryLstat && directory.kind != GuardianReceiptChannelNodeKind.DIRECTORY) {
            return stop(state, GuardianReceiptChannelRootStopReason.ResquatAfterRecovery)
        }
        if (directory.kind == GuardianReceiptChannelNodeKind.ABSENT) {
            return when (state) {
                GuardianReceiptChannelRootAttestationState.Initial -> result(
                    GuardianReceiptChannelRootAttestationState.AwaitingCreateLstat,
                    create,
                )
                GuardianReceiptChannelRootAttestationState.AwaitingCreateLstat -> stop(state, GuardianReceiptChannelRootStopReason.CreateDidNotMaterialize)
                GuardianReceiptChannelRootAttestationState.AwaitingRecoveryLstat -> stop(state, GuardianReceiptChannelRootStopReason.ResquatAfterRecovery)
            }
        }
        if (!metadataIsExact(directory)) return stop(state, GuardianReceiptChannelRootStopReason.MetadataAmbiguous)
        if (childNames.any { it !in allowedChildren || it.isBlank() || it.contains('/') }) {
            return stop(state, GuardianReceiptChannelRootStopReason.ForeignChild)
        }
        val ownedByRoot = directory.ownerUid == 0 && directory.ownerGid == 0
        if (!ownedByRoot) {
            if (childNames.isNotEmpty()) return stop(state, GuardianReceiptChannelRootStopReason.WrongOwnedNonEmpty)
            return when (state) {
                GuardianReceiptChannelRootAttestationState.Initial -> result(
                    GuardianReceiptChannelRootAttestationState.AwaitingRecoveryLstat,
                    recover,
                )
                GuardianReceiptChannelRootAttestationState.AwaitingCreateLstat -> stop(state, GuardianReceiptChannelRootStopReason.CreateDidNotMaterialize)
                GuardianReceiptChannelRootAttestationState.AwaitingRecoveryLstat -> stop(state, GuardianReceiptChannelRootStopReason.ResquatAfterRecovery)
            }
        }
        return result(state, ready)
    }

    private fun metadataIsExact(directory: GuardianReceiptChannelLstatObservation): Boolean =
        directory.ownerUid != null && directory.modeOctal == "700" && directory.ownerGid == 0 &&
            directory.seLinuxLabel?.let { it.isNotBlank() && it.none(Char::isWhitespace) } == true
}

/**
 * CORRECTED 2026-09-06 (guardian map §4.2): this was meant as THE fail-closed live boundary for
 * this channel, but it is not the one production actually goes through --
 * `MimirTweakerService.runSafeRct1ChannelProbe` constructs [GuardianReceiptChannelTopology] and
 * [GuardianReceiptChannelReadyPort] directly and never calls [unavailable]. This object has no
 * caller anywhere in `app/src/main`; its only reference is its own test. Left in place rather than
 * deleted pending a removal decision, but a reader must
 * not treat it as an enforced gate.
 */
internal object GuardianReceiptChannelProductionFreeze {
    fun unavailable(): Nothing = error("receipt channel factory unavailable until live construction is adjudicated")
}

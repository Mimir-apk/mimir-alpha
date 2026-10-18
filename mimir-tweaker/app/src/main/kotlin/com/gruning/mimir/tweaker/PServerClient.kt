package com.gruning.mimir.tweaker

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Parcel
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class PServerTransactResult(
    val accepted: Boolean,
    val output: String? = null,
    /**
     * Exact Binder reply bytes for consumers that must distinguish absent bytes from
     * post-decoding or post-trim string output. Test transports must provide this
     * explicitly; `output` is never synthesized into raw bytes.
     */
    val bytes: ByteArray? = null,
    val error: Throwable? = null,
)

/**
 * One PServer transaction's raw reply boundary.
 *
 * `bytes == null` means Binder returned no byte array. In particular, it is not the
 * string literal `"null"`, an empty array, decoded UTF-8, or a trimmed representation.
 */
internal data class PServerRawReply(
    val accepted: Boolean,
    val bytes: ByteArray?,
)

/** Injectable Binder/transport seam for checked PServer execution proofs. */
internal fun interface PServerBinderTransport {
    fun transact(command: String, captureOutput: Boolean): PServerTransactResult
}

/**
 * Resolves a live PServer Binder. Called when the cache is absent or dead.
 * Never auto-replays an ambiguous command after a failed attempt.
 */
internal fun interface PServerBinderResolver {
    fun resolve(): IBinder?
}

/** Side-effect-free identity proof required before a resolved service may become mutation transport. */
internal fun interface PServerBinderAttestor {
    fun attest(binder: IBinder): Boolean
}

internal data class PServerServiceAttestation(
    val protocolMarker: String?,
    val uid: String?,
    val gid: String?,
    val selinuxDomain: String?,
    val executablePath: String?,
    val executableSha256: String?,
)

/**
 * Structural trust for the vendor `pservice`, with no firmware allowlist behind it.
 *
 *  What stood here
 * was a three-entry `buildFingerprint -> expected digest` table whose lookup elvis-returned `false`
 * on any unlisted firmware, so an unseen handheld with a perfectly genuine `pservice` failed
 * attestation before a single axis was compared. That table is an unwanted firmware allowlist, and it
 * is gone; the entry added for the Nova for the same table went with it.
 *
 * **Why dropping the pinned digest costs nothing that was actually being paid for.** Every value
 * below is *self-reported by the service being attested* -- the probes run through that same binder.
 * A hostile responder can return any string it likes, and the expected digest was never a secret
 * (anyone holding the device or the OTA image can compute it), so pinning it stopped no attacker who
 * was not already stopped. Against an honest responder the digest only detected firmware drift, and
 * it did so for exactly the three builds a release happened to enumerate.
 *
 * What survives is what was doing the work:
 * - [DefaultPServerBinderAttestor.PROTOCOL_MARKER] must be echoed back. This is the only axis a
 *   *wrong* service fails by construction, and it is what stops a blind `transact(0, ...)` against
 *   an ABI we do not understand.
 * - root uid/gid, the `pservice` SELinux domain, and the `/system/bin/pservice` executable path.
 * - the digest, still measured and still required to be **well-formed** -- proving a `sha256sum`
 *   probe ran and answered in digest shape, which is all a self-reported digest can ever prove.
 *
 * This is still fail-closed: any missing or mismatched axis refuses, and a null digest refuses.
 * SoC-family policy is deliberately *not* duplicated here -- it already lives in
 * [TweakerHardwareFamilies], which classifies every device including the ones this build has never
 * seen. This predicate gates the channel; the family table gates what may be done over it.
 */
internal fun PServerServiceAttestation.isTrusted(): Boolean =
    protocolMarker == DefaultPServerBinderAttestor.PROTOCOL_MARKER &&
        uid == "0" &&
        gid == "0" &&
        selinuxDomain == "u:r:pservice:s0" &&
        executablePath == "/system/bin/pservice" &&
        isWellFormedSha256(executableSha256)

internal fun isWellFormedSha256(digest: String?): Boolean {
    val normalized = digest?.lowercase() ?: return false
    return normalized.length == 64 && normalized.all { it in '0'..'9' || it in 'a'..'f' }
}

internal object DefaultPServerBinderAttestor : PServerBinderAttestor {
    internal const val PROTOCOL_MARKER = "__MIMIR_VENDOR_PSERVER_V1__"

    /** One attestation probe is a `printf`/`id`/`cat`; a service that cannot answer one in this long is not attested. */
    internal const val PROBE_DEADLINE_MS = 3_000L

    private val probeBound = PServerCallBound(defaultDeadlineMs = PROBE_DEADLINE_MS)

    /**
     * Probes stop at the first one that answers nothing: attestation is all-or-nothing, so the remaining probes could
     * only repeat the same wait against a service that is not answering. The verdict is the same as running all six.
     */
    override fun attest(binder: IBinder): Boolean {
        val protocolMarker = probe(binder, "/system/bin/printf $PROTOCOL_MARKER") ?: return false
        val uid = probe(binder, "/system/bin/id -u") ?: return false
        val gid = probe(binder, "/system/bin/id -g") ?: return false
        val selinuxDomain = probe(binder, "/system/bin/cat /proc/self/attr/current") ?: return false
        val executablePath = probe(binder, "/system/bin/readlink /proc/\$PPID/exe") ?: return false
        val executableSha256 = probe(binder, "/system/bin/sha256sum /proc/\$PPID/exe")
            ?.substringBefore(' ') ?: return false
        return PServerServiceAttestation(
            protocolMarker = protocolMarker,
            uid = uid,
            gid = gid,
            selinuxDomain = selinuxDomain,
            executablePath = executablePath,
            executableSha256 = executableSha256,
        ).isTrusted()
    }

    /** Bounded: the Parcels live and die inside the call, so a probe that outlives its deadline cannot touch a recycled one. */
    private fun probe(binder: IBinder, command: String): String? {
        val outcome = probeBound.run {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeStringArray(arrayOf(command, "1"))
                if (!binder.transact(0, data, reply, 0)) {
                    null
                } else {
                    reply.createByteArray()
                        ?.toString(StandardCharsets.UTF_8)
                        ?.trimEnd('\u0000', '\n', '\r', ' ', '\t')
                }
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
        return (outcome as? PServerCallOutcome.Done)?.value
    }
}

/**
 * Minimal client for the vendor PServerBinder found in Retroid/AYN firmware.
 * Technique credit: AurelioB/ClusterTune and keiretrogaming/PULSE. The binder call pattern derives from
 * langerhans/OdinTools (MIT); see NOTICE.md.
 *
 * Dead/unavailable Binders are invalidated and reacquired on later attempts.
 * A single call never automatically replays the same ambiguous command.
 *
 * **Reads have a deadline; writes do not.** A read ([execute] with output, [executeRaw]) runs under a deadline
 * ([PServerCallBound]): a Binder `transact` cannot be cancelled, so a wedged `pservice` used to hold its caller --
 * usually the one transaction worker -- forever, and a read that lapses is simply unreadable (never an observed value).
 * A write ([executeChecked], [execute] without output) has NO deadline and runs inline on its caller until `pservice`
 * answers. A write abandoned at a deadline would keep running on a helper thread and could land after the daemon had
 * read the node back, restored it, or released the Guardian's mutation fence, so nothing read afterwards would be
 * authoritative. What keeps the daemon live while a write is slow is the transaction worker's own bound (a Binder caller
 * gets SERVICE_BUSY, ticks skip and log), not a timeout on the write.
 */
/** What one bounded checked read answered. Anything but [Body] is "no value": a caller never guesses one. */
internal sealed interface CheckedReadResult {
    /** Exit 0 and a well-formed frame: the command's own output. */
    data class Body(val text: String) : CheckedReadResult

    /** Never sent (no route, blank or oversize command) or ran and exited nonzero. */
    data class Failed(val detail: String) : CheckedReadResult

    /** Sent, but the answer cannot be read: an exception, a rejected transaction, or a malformed frame. */
    data class Unreadable(val detail: String) : CheckedReadResult

    /** The deadline lapsed, the wait was interrupted, or earlier reads are wedged. The read may still be running. */
    data class Busy(val detail: String) : CheckedReadResult
}

@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
internal open class PServerClient(
    private val transport: PServerBinderTransport? = null,
    private val binderResolver: PServerBinderResolver? = if (transport == null) {
        DefaultPServerBinderResolver
    } else {
        null
    },
    private val binderAlive: (IBinder) -> Boolean = { it.isBinderAlive },
    private val binderAttestor: PServerBinderAttestor = DefaultPServerBinderAttestor,
    private val callBound: PServerCallBound = PServerCallBound(),
    private val readDeadlineMs: Long = PServerCallBound.READ_DEADLINE_MS,
) : FrameCommandExecutor {
    private val cachedBinder = AtomicReference<IBinder?>(null)
    private val binderLock = Any()
    private var cachedDeathRecipient: IBinder.DeathRecipient? = null

    val isAvailable: Boolean
        get() = transport != null || acquireBinder() != null

    override fun execute(command: String): Result<String?> = execute(command, captureOutput = true)

    /**
     * Executes exactly one output-capturing PServer transaction and returns the Binder
     * byte-array reply without decoding, trimming, or null-sentinel normalization.
     *
     * This is intentionally separate from [execute] and [executeChecked]: callers that
     * need byte-accurate framing must opt into this boundary rather than inherit either
     * String-oriented path. A failed Binder transaction is represented by `accepted =
     * false`; exceptions invalidate the cached Binder and are never replayed. A call that
     * passes [PServerCallBound.READ_DEADLINE_MS] fails with [PServerTransactTimeoutException]
     * (it may still run; the Binder is kept, because a slow service is not a dead one).
     */
    open fun executeRaw(command: String): Result<PServerRawReply> {
        val outcome = transactBounded(
            command,
            captureOutput = true,
            deadlineMs = readDeadlineMs,
            readReplyEvenIfRejected = true,
        ) ?: return Result.failure(IllegalStateException("PServerBinder unavailable"))
        return when (outcome) {
            is PServerCallOutcome.Done -> {
                val result = outcome.value
                if (result.error != null) {
                    Result.failure(result.error)
                } else {
                    // Match the existing output-capturing path's failed-transaction cache
                    // invalidation while still exposing raw acceptance to the caller.
                    if (transport == null && !result.accepted) invalidateBinder()
                    Result.success(PServerRawReply(result.accepted, result.bytes?.copyOf()))
                }
            }
            is PServerCallOutcome.Threw -> {
                if (transport == null) invalidateBinder()
                Result.failure(outcome.error)
            }
            else -> Result.failure(leftBehind(outcome))
        }
    }

    /**
     * An output-capturing call is a READ and runs under [readDeadlineMs]. A call without output is a WRITE: it has no
     * deadline and waits until the service answers (see the class comment).
     */
    open fun execute(command: String, captureOutput: Boolean): Result<String?> {
        val outcome = if (captureOutput) {
            transactBounded(command, captureOutput = true, deadlineMs = readDeadlineMs)
        } else {
            transactInline(command, captureOutput = false)
        } ?: return Result.failure(IllegalStateException("PServerBinder unavailable"))
        return when (outcome) {
            is PServerCallOutcome.Done -> {
                val result = outcome.value
                when {
                    result.error != null -> Result.failure(result.error)
                    captureOutput && !result.accepted -> {
                        if (transport == null) invalidateBinder()
                        Result.failure(IllegalStateException("PServerBinder declined the command"))
                    }
                    else -> Result.success(result.output?.takeUnless { it == "null" })
                }
            }
            is PServerCallOutcome.Threw -> {
                if (transport == null) invalidateBinder()
                Result.failure(outcome.error)
            }
            else -> Result.failure(leftBehind(outcome))
        }
    }

    /**
     * Checked PServer execution: Binder acceptance alone is never confirmation.
     * Requires transact acceptance, captured output, and a unique nonce-bound exit marker.
     * Failures invalidate a dead Binder cache but never auto-replay this command.
     *
     * **A write has no deadline.** It runs inline on its caller and returns only when `pservice` answers (or the Binder
     * throws), so when this returns the command is no longer in flight and a read-back afterwards is authoritative. Any
     * exception, rejected transaction or unparseable reply is AMBIGUOUS, not FAILED: the command may have run, so the
     * write is UNCONFIRMED and every caller must read it back and repair, never take it for "failed, so still Stock".
     * FAILED means the command was never sent (blank or oversize command, no route) or ran and reported a nonzero exit.
     */
    open fun executeChecked(command: String, nonce: String = nextNonce()): CheckedCommandResult {
        if (command.isBlank()) {
            return CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "Blank command rejected")
        }
        if (!EXIT_NONCE_PATTERN.matches(nonce)) {
            return CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "Invalid nonce rejected")
        }
        val wrapped = wrapChecked(command, nonce)
        if (wrapped.toByteArray(StandardCharsets.UTF_8).size > MAX_CHECKED_COMMAND_BYTES) {
            return CheckedCommandResult(
                CheckedCommandStatus.FAILED,
                detail = "Checked command exceeds $MAX_CHECKED_COMMAND_BYTES-byte PServer envelope",
            )
        }
        val outcome = transactInline(wrapped, captureOutput = true)
            ?: return CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "PServerBinder unavailable")
        return when (outcome) {
            is PServerCallOutcome.Done -> {
                val result = outcome.value
                when {
                    result.error != null -> CheckedCommandResult(
                        CheckedCommandStatus.AMBIGUOUS,
                        detail = "PServer exception: ${result.error.message}",
                    )
                    !result.accepted -> {
                        if (transport == null) invalidateBinder()
                        CheckedCommandResult(
                            CheckedCommandStatus.AMBIGUOUS,
                            detail = "PServer transact returned false",
                        )
                    }
                    else -> parseExitMarker(result.output?.takeUnless { it == "null" }, nonce)
                }
            }
            is PServerCallOutcome.Threw -> {
                if (transport == null) invalidateBinder()
                CheckedCommandResult(
                    CheckedCommandStatus.AMBIGUOUS,
                    detail = "PServer exception: ${outcome.error.message}",
                )
            }
            // transactInline only ever answers Done or Threw: a write has no deadline, so it is never left behind.
            else -> CheckedCommandResult(
                CheckedCommandStatus.AMBIGUOUS,
                detail = "PServer write did not complete; it is UNCONFIRMED and must be read back",
            )
        }
    }

    /**
     * A checked READ: the same nonce-anchored exit frame as [executeChecked], but carried by a transaction that runs under
     * [readDeadlineMs], and answering the frame's decoded PAYLOAD rather than the raw frame. For a readback that must
     * not be able to occupy the shared worker forever: a lapsed deadline is [CheckedReadResult.Busy], never a value.
     * Reads only. A write never goes through here, because a write abandoned at a deadline keeps running and nothing read
     * afterwards would be authoritative (see the class comment).
     */
    open fun executeCheckedRead(command: String, nonce: String = nextNonce()): CheckedReadResult {
        if (command.isBlank()) return CheckedReadResult.Failed("Blank command rejected")
        if (!EXIT_NONCE_PATTERN.matches(nonce)) return CheckedReadResult.Failed("Invalid nonce rejected")
        val wrapped = wrapChecked(command, nonce)
        if (wrapped.toByteArray(StandardCharsets.UTF_8).size > MAX_CHECKED_COMMAND_BYTES) {
            return CheckedReadResult.Failed("Checked command exceeds $MAX_CHECKED_COMMAND_BYTES-byte PServer envelope")
        }
        val outcome = transactBounded(wrapped, captureOutput = true, deadlineMs = readDeadlineMs)
            ?: return CheckedReadResult.Failed("PServerBinder unavailable")
        return when (outcome) {
            is PServerCallOutcome.Done -> {
                val result = outcome.value
                when {
                    result.error != null -> CheckedReadResult.Unreadable("PServer exception: ${result.error.message}")
                    !result.accepted -> {
                        if (transport == null) invalidateBinder()
                        CheckedReadResult.Unreadable("PServer transact returned false")
                    }
                    else -> {
                        val frame = result.output?.takeUnless { it == "null" }
                        val parsed = parseExitMarker(frame, nonce, allowLineFeedsInPayload = true)
                        when (parsed.status) {
                            CheckedCommandStatus.CONFIRMED ->
                                decodeCheckedBody(frame, nonce, allowLineFeedsInPayload = true)?.let(CheckedReadResult::Body)
                                    ?: CheckedReadResult.Unreadable("Checked frame carried no decodable payload")
                            CheckedCommandStatus.FAILED -> CheckedReadResult.Failed(parsed.detail)
                            CheckedCommandStatus.AMBIGUOUS -> CheckedReadResult.Unreadable(parsed.detail)
                        }
                    }
                }
            }
            is PServerCallOutcome.Threw -> {
                if (transport == null) invalidateBinder()
                CheckedReadResult.Unreadable("PServer exception: ${outcome.error.message}")
            }
            else -> CheckedReadResult.Busy(leftBehind(outcome).message.orEmpty())
        }
    }

    /**
     * One READ transaction on whichever route this client has, under a deadline. Null means there is no route at all
     * (no injected transport and no live attested Binder). Every Parcel lives and dies inside the bounded call, because
     * the caller may have left while the service still holds the transaction.
     */
    private fun transactBounded(
        command: String,
        captureOutput: Boolean,
        deadlineMs: Long,
        readReplyEvenIfRejected: Boolean = false,
    ): PServerCallOutcome<PServerTransactResult>? {
        if (transport != null) {
            return callBound.run(deadlineMs) { transport.transact(command, captureOutput) }
        }
        val service = acquireBinder() ?: return null
        return callBound.run(deadlineMs) {
            binderTransact(service, command, captureOutput, readReplyEvenIfRejected)
        }
    }

    /**
     * One WRITE transaction on whichever route this client has, with NO deadline: it runs on the calling thread and
     * returns when the service answers. Answers [PServerCallOutcome.Done] or [PServerCallOutcome.Threw] only. Null means
     * there is no route at all. Nothing is left running behind the caller, so what it reads next is authoritative and
     * a Guardian fence held around this call is held until the write has really returned.
     *
     * `acquireBinder()` takes `binderLock` only to resolve and cache the Binder and releases it before the transaction,
     * so a slow write never holds that lock and a read on another thread is never queued behind it.
     */
    private fun transactInline(command: String, captureOutput: Boolean): PServerCallOutcome<PServerTransactResult>? {
        if (transport != null) {
            return callDirect { transport.transact(command, captureOutput) }
        }
        val service = acquireBinder() ?: return null
        return callDirect { binderTransact(service, command, captureOutput, readReplyEvenIfRejected = false) }
    }

    private inline fun <T> callDirect(call: () -> T): PServerCallOutcome<T> = try {
        PServerCallOutcome.Done(call())
    } catch (error: Throwable) {
        PServerCallOutcome.Threw(error)
    }

    private fun leftBehind(outcome: PServerCallOutcome<*>): Throwable = when (outcome) {
        is PServerCallOutcome.TimedOut -> PServerTransactTimeoutException(outcome.deadlineMs)
        PServerCallOutcome.Interrupted -> IllegalStateException("PServer wait interrupted; the command may still run")
        is PServerCallOutcome.Refused -> PServerCallsWedgedException(outcome.abandoned)
        else -> IllegalStateException("PServer call did not complete")
    }

    /**
     * Runs [body] with a fresh request/response Parcel pair and hands both back to the pool when it
     * finishes, however it finishes.
     */
    private inline fun <R> withParcelPair(body: (request: Parcel, response: Parcel) -> R): R {
        val request = Parcel.obtain()
        try {
            val response = Parcel.obtain()
            try {
                return body(request, response)
            } finally {
                response.recycle()
            }
        } finally {
            request.recycle()
        }
    }

    private fun binderTransact(
        service: IBinder,
        command: String,
        captureOutput: Boolean,
        readReplyEvenIfRejected: Boolean,
    ): PServerTransactResult = withParcelPair { request, response ->
        request.writeStringArray(arrayOf(command, if (captureOutput) "1" else "0"))
        val accepted = service.transact(0, request, response, 0)
        val wantsReply = captureOutput && (accepted || readReplyEvenIfRejected)
        val replyBytes = if (wantsReply) response.createByteArray() else null
        PServerTransactResult(
            accepted = accepted,
            output = replyBytes?.toString(StandardCharsets.UTF_8)?.trim(),
            bytes = replyBytes,
        )
    }

    /** Test/host seam: resolve/check live Binder when cache absent or dead. */
    internal fun acquireBinderForTest(): IBinder? = acquireBinder()

    internal fun invalidateBinderForTest() = invalidateBinder()

    private fun acquireBinder(): IBinder? {
        val resolver = binderResolver ?: return null
        return synchronized(binderLock) {
            val resolved = resolver.resolve()
            val cached = cachedBinder.get()
            if (
                cached != null &&
                resolved != null &&
                sameBinder(cached, resolved) &&
                binderAlive(cached) &&
                binderAlive(resolved)
            ) {
                return@synchronized cached
            }
            clearCachedBinderLocked(unlink = true)
            if (resolved == null || !binderAlive(resolved) || !binderAttestor.attest(resolved)) {
                return@synchronized null
            }
            val deathRecipient = IBinder.DeathRecipient {
                synchronized(binderLock) {
                    if (sameBinder(cachedBinder.get(), resolved)) {
                        clearCachedBinderLocked(unlink = false)
                    }
                }
            }
            val linked = runCatching {
                resolved.linkToDeath(deathRecipient, 0)
                true
            }.getOrDefault(false)
            if (!linked || !binderAlive(resolved)) {
                if (linked) runCatching { resolved.unlinkToDeath(deathRecipient, 0) }
                return@synchronized null
            }
            cachedDeathRecipient = deathRecipient
            cachedBinder.set(resolved)
            resolved
        }
    }

    private fun invalidateBinder() {
        synchronized(binderLock) {
            clearCachedBinderLocked(unlink = true)
        }
    }

    private fun clearCachedBinderLocked(unlink: Boolean) {
        val binder = cachedBinder.getAndSet(null)
        val recipient = cachedDeathRecipient
        cachedDeathRecipient = null
        if (unlink && binder != null && recipient != null) {
            runCatching { binder.unlinkToDeath(recipient, 0) }
        }
    }

    private fun sameBinder(first: IBinder?, second: IBinder?): Boolean =
        first != null && second != null && (first === second || first == second)

    companion object {
        const val EXIT_MARKER_PREFIX = "MIMIR_TX_EXIT:"
        /** Largest fixed PServer statement observed on RP5; oversize commands are never sent unchecked. */
        const val MAX_CHECKED_COMMAND_BYTES = 198
        /** Bounded checked reply body; this is a transport cap, not a topology or READY policy. */
        const val MAX_CHECKED_OUTPUT_BYTES = 8_192
        private val EXIT_NONCE_PATTERN = Regex("[A-Za-z0-9_-]{8,64}")
        private val nonceCounter = AtomicLong(0)

        fun nextNonce(): String = "n${nonceCounter.incrementAndGet()}_${UUID.randomUUID().toString().take(8)}"

        /** The one spelling of the checked envelope, shared by the write and the bounded read. */
        internal fun wrapChecked(command: String, nonce: String): String =
            "b=\$( ( $command ) 2>/dev/null);s=\$?;printf '${EXIT_MARKER_PREFIX}$nonce:%s:%s:%s\\n' \"\$s\" \"\${#b}\" \"\$b\""

        /**
         * The payload of a frame [parseExitMarker] CONFIRMED, or null for anything it did not confirm (a missing,
         * foreign-nonce, truncated, oversize, nonprintable or nonzero-exit frame). The payload is the command's own
         * output with its trailing newline already stripped by the shell's command substitution.
         */
        fun decodeCheckedBody(output: String?, nonce: String, allowLineFeedsInPayload: Boolean = false): String? {
            if (parseExitMarker(output, nonce, allowLineFeedsInPayload).status != CheckedCommandStatus.CONFIRMED) return null
            val frame = output?.removeSuffix("\n") ?: return null
            val prefix = "$EXIT_MARKER_PREFIX$nonce:"
            val exitEnd = frame.indexOf(':', prefix.length)
            if (exitEnd < 0) return null
            val lengthEnd = frame.indexOf(':', exitEnd + 1)
            if (lengthEnd < 0) return null
            return frame.substring(lengthEnd + 1)
        }

        fun parseExitMarker(
            output: String?,
            nonce: String,
            allowLineFeedsInPayload: Boolean = false,
        ): CheckedCommandResult {
            if (output.isNullOrBlank()) {
                return CheckedCommandResult(
                    CheckedCommandStatus.AMBIGUOUS,
                    output = output,
                    detail = "Missing PServer output for nonce $nonce",
                )
            }
            val frame = output.removeSuffix("\n")
            if (frame.toByteArray(StandardCharsets.UTF_8).size > MAX_CHECKED_OUTPUT_BYTES ||
                frame.any { it.code !in 0x20..0x7e && !(allowLineFeedsInPayload && it == '\n') }
            ) {
                return CheckedCommandResult(
                    CheckedCommandStatus.AMBIGUOUS,
                    output = output,
                    detail = "Malformed checked PServer frame for nonce $nonce",
                )
            }
            val prefix = "$EXIT_MARKER_PREFIX$nonce:"
            if (!frame.startsWith(prefix)) {
                return CheckedCommandResult(
                    CheckedCommandStatus.AMBIGUOUS,
                    output = output,
                    detail = "Missing anchored exit marker for nonce $nonce",
                )
            }
            val exitEnd = frame.indexOf(':', prefix.length)
            val lengthEnd = exitEnd.takeIf { it >= 0 }?.let { frame.indexOf(':', it + 1) } ?: -1
            if (exitEnd < 0 || lengthEnd < 0) {
                return CheckedCommandResult(
                    CheckedCommandStatus.AMBIGUOUS,
                    output = output,
                    detail = "Malformed checked PServer frame for nonce $nonce",
                )
            }
            val exitCode = frame.substring(prefix.length, exitEnd).decimalOrNull()
                ?: return malformedFrame(output, nonce)
            val declaredLength = frame.substring(exitEnd + 1, lengthEnd).decimalOrNull()
                ?: return malformedFrame(output, nonce)
            val body = frame.substring(lengthEnd + 1)
            if (exitCode > Int.MAX_VALUE ||
                declaredLength > MAX_CHECKED_OUTPUT_BYTES ||
                body.toByteArray(StandardCharsets.US_ASCII).size.toLong() != declaredLength
            ) {
                return malformedFrame(output, nonce)
            }
            return if (exitCode == 0L) {
                CheckedCommandResult(
                    CheckedCommandStatus.CONFIRMED,
                    output = output,
                    exitCode = exitCode.toInt(),
                    detail = "Exit 0 confirmed",
                )
            } else {
                CheckedCommandResult(
                    CheckedCommandStatus.FAILED,
                    output = output,
                    exitCode = exitCode.toInt(),
                    detail = "Nonzero exit $exitCode",
                )
            }
        }

        private fun String.decimalOrNull(): Long? =
            takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }?.toLongOrNull()

        private fun malformedFrame(output: String, nonce: String): CheckedCommandResult =
            CheckedCommandResult(
                CheckedCommandStatus.AMBIGUOUS,
                output = output,
                detail = "Malformed checked PServer frame for nonce $nonce",
            )
    }
}

@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
internal object DefaultPServerBinderResolver : PServerBinderResolver {
    override fun resolve(): IBinder? = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
            .apply { isAccessible = true }
        getService.invoke(null, "PServerBinder") as? IBinder
    }.getOrNull()
}

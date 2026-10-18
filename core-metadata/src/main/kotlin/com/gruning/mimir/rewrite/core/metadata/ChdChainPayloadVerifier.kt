package com.gruning.mimir.rewrite.core.metadata

import java.io.Closeable
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ChdVerificationNode(
    val path: String,
    val header: ChdHeaderEvidence,
)

internal data class ChdChainNodeInfo(
    val version: Int,
    val logicalBytes: Long,
    val hunkBytes: Int,
    val hunkCount: Int,
    val rawSha1: String,
    val combinedSha1: String,
    val parentSha1: String?,
    val compressionTags: List<Int>,
)

internal interface ChdChainReadSession : Closeable {
    /** Borrowed native nodes in root-to-target order. */
    val nodes: List<ChdChainNodeInfo>

    /** Non-zero only for the native libchdr chain owner; fake/JVM sessions cannot be borrowed by rcheevos. */
    val nativeHandle: Long get() = 0L

    fun readHunk(nodeIndex: Int, hunkIndex: Int, destination: ByteArray): ChdHunkRead
    fun readMetadata(nodeIndex: Int, metadataIndex: Int, maxBytes: Int): ChdMetadataRead
}

internal sealed interface ChdChainOpen {
    data class Opened(val session: ChdChainReadSession) : ChdChainOpen

    /**
     * A typed open refusal. [failingNodeIndex] is the 0-based root-first node libchdr rejected, or null
     * when the failure is not attributable to one node. An INVALID_PARENT rejection surfaces at the CHILD
     * index whose header claim its predecessor's actual digest refuted.
     */
    data class Failed(
        val result: ContainerIdentityResult<Nothing>,
        val failingNodeIndex: Int?,
    ) : ChdChainOpen
}

internal fun interface ChdChainVerifierBackend {
    fun openChain(nodes: List<ChdVerificationNode>): ChdChainOpen
}

/** [ChdChainPayloadVerifier.verify] plus the open-failure index a discovery caller prunes branches with. */
internal data class ChdChainVerification(
    val result: ContainerIdentityResult<VerifiedChdIdentity>,
    val openFailureNodeIndex: Int? = null,
    val budgetExhausted: Boolean = false,
)

internal data class BorrowedVerifiedChdChain(
    val identity: VerifiedChdIdentity,
    val targetPath: String,
    val nativeHandle: Long,
)

private class RetainedChdChainVerification(
    val verification: ChdChainVerification,
    private var session: ChdChainReadSession?,
) : Closeable {
    fun takeSession(): ChdChainReadSession? = session.also { session = null }

    override fun close() {
        session?.close()
        session = null
    }
}

/**
 * Verifies every node of a digest-bound CHD parent chain. A child is never accepted merely because
 * libchdr can decode it through a parent: the parent and every intermediary earn their own raw and
 * checksum-metadata digest first.
 */
internal object ChdChainPayloadVerifier {
    const val PARSER_VERSION = 1
    const val POLICY_STAMP = "libchdr-parent-chain-v1"
    const val MAX_CHAIN_DEPTH = 8

    private const val MAX_LOGICAL_BYTES_PER_NODE = 16L * 1024 * 1024 * 1024
    private const val MAX_AGGREGATE_LOGICAL_BYTES = 64L * 1024 * 1024 * 1024
    private const val MAX_HUNK_BYTES = 8 * 1024 * 1024
    private const val MAX_HUNKS = 1_000_000
    private const val MAX_METADATA_ENTRIES = 4_096
    private const val MAX_METADATA_ENTRY_BYTES = 1024 * 1024
    private const val MAX_METADATA_TOTAL_BYTES = 8 * 1024 * 1024

    private const val SHA1_BYTES = 20

    suspend fun verify(
        chain: List<ChdVerificationNode>,
        backend: ChdChainVerifierBackend = NativeChdChainVerifierBackend,
        budget: ChdVerificationBudget = ChdVerificationBudget.forResolve(),
    ): ContainerIdentityResult<VerifiedChdIdentity> = verifyChain(chain, backend, budget).result

    suspend fun verifyChain(
        chain: List<ChdVerificationNode>,
        backend: ChdChainVerifierBackend = NativeChdChainVerifierBackend,
        budget: ChdVerificationBudget = ChdVerificationBudget.forResolve(),
    ): ChdChainVerification = verifyChainRetainingSession(chain, backend, budget).use {
        it.verification
    }

    /**
     * Verifies [chain], keeps the exact native session live for [block], then closes it exactly once.
     * A successful fake/JVM backend still cannot cross the native borrow boundary.
     */
    suspend fun <T> withVerifiedNativeChain(
        chain: List<ChdVerificationNode>,
        expectedIdentity: VerifiedChdIdentity? = null,
        backend: ChdChainVerifierBackend = NativeChdChainVerifierBackend,
        budget: ChdVerificationBudget = ChdVerificationBudget.forResolve(),
        block: suspend (BorrowedVerifiedChdChain) -> T,
    ): ContainerIdentityResult<T> {
        val retained = verifyChainRetainingSession(chain, backend, budget)
        val verification = retained.verification
        val identified =
            verification.result as? ContainerIdentityResult.Identified<VerifiedChdIdentity>
            ?: return retained.use {
                when (val failure = verification.result) {
                    is ContainerIdentityResult.PolicyAbstain -> failure
                    is ContainerIdentityResult.NeedsReview -> failure
                    is ContainerIdentityResult.RetryableFailure -> failure
                    is ContainerIdentityResult.Identified ->
                        ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
                }
            }
        if (expectedIdentity != null && identified.value != expectedIdentity) {
            return retained.use {
                ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
            }
        }
        val session = retained.takeSession()
            ?: return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.SEEK_UNAVAILABLE)
        return session.use { opened ->
            val handle = opened.nativeHandle
            if (handle == 0L) {
                ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.SEEK_UNAVAILABLE)
            } else {
                ContainerIdentityResult.Identified(
                    block(
                        BorrowedVerifiedChdChain(
                            identity = identified.value,
                            targetPath = chain.last().path,
                            nativeHandle = handle,
                        ),
                    ),
                )
            }
        }
    }

    private suspend fun verifyChainRetainingSession(
        chain: List<ChdVerificationNode>,
        backend: ChdChainVerifierBackend,
        budget: ChdVerificationBudget,
    ): RetainedChdChainVerification {
        currentCoroutineContext().ensureActive()
        if (!budget.hasAdmissionFloor()) {
            return RetainedChdChainVerification(
                ChdChainVerification(
                    result = resourceRefused(),
                    budgetExhausted = true,
                ),
                null,
            )
        }
        preflight(chain)?.let {
            return RetainedChdChainVerification(ChdChainVerification(it), null)
        }
        val opened = backend.openChain(chain)
        if (budget.isExhausted()) {
            if (opened is ChdChainOpen.Opened) {
                opened.session.use { }
            }
            return RetainedChdChainVerification(
                ChdChainVerification(
                    result = resourceRefused(),
                    budgetExhausted = true,
                ),
                null,
            )
        }
        val session = when (opened) {
            is ChdChainOpen.Opened -> opened.session
            is ChdChainOpen.Failed -> {
                return RetainedChdChainVerification(
                    ChdChainVerification(
                        result = opened.result,
                        openFailureNodeIndex = opened.failingNodeIndex?.takeIf { it in chain.indices },
                        budgetExhausted = budgetExpiredFor(opened.result, budget),
                    ),
                    null,
                )
            }
        }
        val result = try {
            run {
                val opened = session
                if (opened.nodes.size != chain.size) {
                    return@run timedVerifiedResult(malformed(), budget)
                }
                var aggregateLogicalBytes = 0L
                var previousOverall: String? = null
                chain.indices.forEach { nodeIndex ->
                    currentCoroutineContext().ensureActive()
                    if (budget.isExhausted()) return@run timedVerifiedResult(budgetRefused(), budget, budgetExhausted = true)
                    val expected = chain[nodeIndex].header
                    val info = opened.nodes[nodeIndex]
                    if (
                        info.version != expected.version ||
                        info.logicalBytes != expected.logicalBytes ||
                        info.hunkBytes != expected.hunkBytes ||
                        info.rawSha1 != expected.rawSha1 ||
                        info.combinedSha1 != expected.combinedSha1 ||
                        info.parentSha1 != expected.parentSha1 ||
                        (
                            expected.compressionTags.isNotEmpty() &&
                                info.compressionTags != expected.compressionTags
                            )
                    ) {
                        return@run timedVerifiedResult(malformed(), budget)
                    }
                    if (nodeIndex == 0) {
                        if (info.parentSha1 != null) return@run timedVerifiedResult(malformed(), budget)
                    } else if (info.parentSha1 != previousOverall) {
                        return@run timedVerifiedResult(
                            ContainerIdentityResult.NeedsReview(
                                ContainerIdentityReason.AMBIGUOUS,
                            ),
                            budget,
                        )
                    }
                    if (
                        info.logicalBytes !in 1..MAX_LOGICAL_BYTES_PER_NODE ||
                        info.hunkBytes !in 1..MAX_HUNK_BYTES ||
                        info.hunkCount !in 1..MAX_HUNKS
                    ) {
                        return@run timedVerifiedResult(resourceRefused(), budget)
                    }
                    aggregateLogicalBytes += info.logicalBytes
                    if (
                        aggregateLogicalBytes <= 0L ||
                        aggregateLogicalBytes > MAX_AGGREGATE_LOGICAL_BYTES
                    ) {
                        return@run timedVerifiedResult(resourceRefused(), budget)
                    }
                    val expectedHunks =
                        (info.logicalBytes + info.hunkBytes - 1L) / info.hunkBytes
                    if (expectedHunks != info.hunkCount.toLong()) return@run timedVerifiedResult(malformed(), budget)

                    val raw = recomputeRaw(opened, nodeIndex, info, budget)
                    val recomputedRaw = when (val rawResult = raw.result) {
                        is ContainerIdentityResult.Identified -> rawResult.value
                        is ContainerIdentityResult.PolicyAbstain -> return@run timedVerifiedResult(rawResult, budget, raw.budgetExhausted)
                        is ContainerIdentityResult.NeedsReview -> return@run timedVerifiedResult(rawResult, budget, raw.budgetExhausted)
                        is ContainerIdentityResult.RetryableFailure -> return@run timedVerifiedResult(rawResult, budget, raw.budgetExhausted)
                    }
                    if (recomputedRaw.toHex() != info.rawSha1) return@run timedVerifiedResult(malformed(), budget)
                    val overall = recomputeOverall(
                        session = opened,
                        nodeIndex = nodeIndex,
                        version = info.version,
                        recomputedRaw = recomputedRaw,
                        budget = budget,
                    )
                    val recomputedOverall = when (val overallResult = overall.result) {
                        is ContainerIdentityResult.Identified -> overallResult.value
                        is ContainerIdentityResult.PolicyAbstain -> return@run timedVerifiedResult(overallResult, budget, overall.budgetExhausted)
                        is ContainerIdentityResult.NeedsReview -> return@run timedVerifiedResult(overallResult, budget, overall.budgetExhausted)
                        is ContainerIdentityResult.RetryableFailure -> return@run timedVerifiedResult(overallResult, budget, overall.budgetExhausted)
                    }
                    if (recomputedOverall != info.combinedSha1) return@run timedVerifiedResult(malformed(), budget)
                    previousOverall = recomputedOverall
                }
                if (budget.isExhausted()) {
                    return@run timedVerifiedResult(budgetRefused(), budget, budgetExhausted = true)
                }
                val target = opened.nodes.last()
                timedVerifiedResult(
                    ContainerIdentityResult.Identified(
                        VerifiedChdIdentity(
                            version = target.version,
                            logicalBytes = target.logicalBytes,
                            rawSha1 = target.rawSha1,
                            combinedSha1 = target.combinedSha1,
                            chainDepth = opened.nodes.size,
                            compressionTags = target.compressionTags,
                        ),
                    ),
                    budget,
                )
            }
        } catch (cancelled: CancellationException) {
            session.close()
            throw cancelled
        } catch (failure: Throwable) {
            session.close()
            throw failure
        }
        return if (result.result is ContainerIdentityResult.Identified) {
            RetainedChdChainVerification(result, session)
        } else {
            session.close()
            RetainedChdChainVerification(result, null)
        }
    }

    private data class TimedStep<T>(
        val result: ContainerIdentityResult<T>,
        val budgetExhausted: Boolean = false,
    )

    private fun timedVerifiedResult(
        result: ContainerIdentityResult<VerifiedChdIdentity>,
        budget: ChdVerificationBudget,
        budgetExhausted: Boolean = budgetExpiredFor(result, budget),
    ): ChdChainVerification = ChdChainVerification(
        result = result,
        budgetExhausted = budgetExhausted,
    )

    private fun <T> budgetExpiredFor(
        result: ContainerIdentityResult<T>,
        budget: ChdVerificationBudget,
    ): Boolean = result is ContainerIdentityResult.RetryableFailure &&
        result.reason == ContainerIdentityReason.RESOURCE_REFUSED &&
        budget.isExhausted()

    private fun preflight(
        chain: List<ChdVerificationNode>,
    ): ContainerIdentityResult<VerifiedChdIdentity>? {
        if (chain.isEmpty() || chain.size > MAX_CHAIN_DEPTH) return resourceRefused()
        if (chain.any { it.path.isBlank() }) {
            return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.SEEK_UNAVAILABLE)
        }
        chain.first().header.parentSha1?.let { parent ->
            return if (parent == chain.first().header.combinedSha1) {
                ContainerIdentityResult.NeedsReview(ContainerIdentityReason.MALFORMED)
            } else {
                ContainerIdentityResult.RetryableFailure(
                    ContainerIdentityReason.PARENT_REQUIRED,
                )
            }
        }
        val seen = HashSet<String>(chain.size)
        var aggregate = 0L
        chain.forEachIndexed { index, node ->
            val header = node.header
            if (!seen.add(header.combinedSha1)) {
                return ContainerIdentityResult.NeedsReview(ContainerIdentityReason.AMBIGUOUS)
            }
            aggregate += header.logicalBytes
            if (
                aggregate <= 0L ||
                aggregate > MAX_AGGREGATE_LOGICAL_BYTES ||
                header.logicalBytes !in 1..MAX_LOGICAL_BYTES_PER_NODE ||
                header.hunkBytes !in 1..MAX_HUNK_BYTES
            ) {
                return resourceRefused()
            }
            val derivedHunkCount = ((header.logicalBytes - 1L) / header.hunkBytes) + 1L
            val declaredHunkCount = header.declaredHunkCount ?: derivedHunkCount
            if (declaredHunkCount !in 1..MAX_HUNKS.toLong()) return resourceRefused()
            if (declaredHunkCount != derivedHunkCount) return malformed()
            if (
                index > 0 &&
                header.parentSha1 != chain[index - 1].header.combinedSha1
            ) {
                return ContainerIdentityResult.NeedsReview(ContainerIdentityReason.MALFORMED)
            }
        }
        return null
    }

    private suspend fun recomputeRaw(
        session: ChdChainReadSession,
        nodeIndex: Int,
        info: ChdChainNodeInfo,
        budget: ChdVerificationBudget,
    ): TimedStep<ByteArray> {
        val digest = MessageDigest.getInstance("SHA-1")
        val hunk = ByteArray(info.hunkBytes)
        var remaining = info.logicalBytes
        for (hunkIndex in 0 until info.hunkCount) {
            currentCoroutineContext().ensureActive()
            if (budget.isExhausted()) return TimedStep(budgetRefused(), budgetExhausted = true)
            val hunkRead = session.readHunk(nodeIndex, hunkIndex, hunk)
            if (budget.isExhausted()) return TimedStep(budgetRefused(), budgetExhausted = true)
            when (hunkRead) {
                ChdHunkRead.OK -> Unit
                ChdHunkRead.IO_RETRYABLE ->
                    return TimedStep(
                        ContainerIdentityResult.RetryableFailure(
                            ContainerIdentityReason.IO_RETRYABLE,
                        ),
                    )
                ChdHunkRead.RESOURCE_REFUSED -> return TimedStep(resourceRefused())
                ChdHunkRead.UNSUPPORTED ->
                    return TimedStep(
                        ContainerIdentityResult.PolicyAbstain(
                            ContainerIdentityReason.UNSUPPORTED_CONTAINER,
                        ),
                    )
                ChdHunkRead.MALFORMED -> return TimedStep(malformed())
            }
                    val bytes = minOf(remaining, info.hunkBytes.toLong()).toInt()
                    if (bytes <= 0) return TimedStep(malformed())
                    digest.update(hunk, 0, bytes)
                    remaining -= bytes
                    if (budget.isExhausted()) return TimedStep(budgetRefused(), budgetExhausted = true)
                }
        if (remaining != 0L) return TimedStep(malformed())
        return TimedStep(ContainerIdentityResult.Identified(digest.digest()))
    }

    private suspend fun recomputeOverall(
        session: ChdChainReadSession,
        nodeIndex: Int,
        version: Int,
        recomputedRaw: ByteArray,
        budget: ChdVerificationBudget,
    ): TimedStep<String> {
        if (version < 4) return TimedStep(ContainerIdentityResult.Identified(recomputedRaw.toHex()))
        val records = ArrayList<ByteArray>()
        var metadataBytes = 0L
        var reachedEnd = false
        for (metadataIndex in 0 until MAX_METADATA_ENTRIES) {
            currentCoroutineContext().ensureActive()
            if (budget.isExhausted()) return TimedStep(budgetRefused(), budgetExhausted = true)
            val metadata = session.readMetadata(
                nodeIndex,
                metadataIndex,
                MAX_METADATA_ENTRY_BYTES,
            )
            if (budget.isExhausted()) return TimedStep(budgetRefused(), budgetExhausted = true)
            when (metadata) {
                is ChdMetadataRead.Checksummed -> {
                    if (metadata.bytes.size > MAX_METADATA_ENTRY_BYTES) {
                        return TimedStep(resourceRefused())
                    }
                    metadataBytes += metadata.bytes.size.toLong()
                    if (metadataBytes > MAX_METADATA_TOTAL_BYTES) return TimedStep(resourceRefused())
                    val record = ByteArray(4 + SHA1_BYTES)
                    record.putBigEndian32(0, metadata.tag)
                    MessageDigest.getInstance("SHA-1")
                        .digest(metadata.bytes)
                        .copyInto(record, destinationOffset = 4)
                    records += record
                }
                ChdMetadataRead.NotChecksummed -> Unit
                ChdMetadataRead.End -> {
                    reachedEnd = true
                    break
                }
                ChdMetadataRead.IoRetryable ->
                    return TimedStep(
                        ContainerIdentityResult.RetryableFailure(
                            ContainerIdentityReason.IO_RETRYABLE,
                        ),
                    )
                ChdMetadataRead.ResourceRefused -> return TimedStep(resourceRefused())
                ChdMetadataRead.Unsupported ->
                    return TimedStep(
                        ContainerIdentityResult.PolicyAbstain(
                            ContainerIdentityReason.UNSUPPORTED_CONTAINER,
                        ),
                    )
                ChdMetadataRead.Malformed -> return TimedStep(malformed())
            }
        }
        if (!reachedEnd) return TimedStep(resourceRefused())
        records.sortWith(::compareUnsigned)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(recomputedRaw)
        records.forEach(digest::update)
        return TimedStep(ContainerIdentityResult.Identified(digest.digest().toHex()))
    }

    private fun <T> malformed(): ContainerIdentityResult<T> =
        ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)

    private fun <T> resourceRefused(): ContainerIdentityResult<T> =
        ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED)

    private fun <T> budgetRefused(): ContainerIdentityResult<T> =
        ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED)

    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val compared = (left[index].toInt() and 0xff) - (right[index].toInt() and 0xff)
            if (compared != 0) return compared
        }
        return left.size - right.size
    }

    private fun ByteArray.putBigEndian32(at: Int, value: Int) {
        for (index in 0 until 4) {
            this[at + index] = (value ushr ((3 - index) * 8)).toByte()
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") {
        "%02x".format(Locale.ROOT, it.toInt() and 0xff)
    }
}

private object NativeChdChainVerifierBackend : ChdChainVerifierBackend {
    override fun openChain(nodes: List<ChdVerificationNode>): ChdChainOpen {
        if (!ChdChainNativeBridge.isAvailable()) {
            return ChdChainOpen.Failed(
                result = ContainerIdentityResult.RetryableFailure(
                    ContainerIdentityReason.SEEK_UNAVAILABLE,
                ),
                failingNodeIndex = null,
            )
        }
        val opened = ChdChainNativeBridge.open(nodes.map { it.path }.toTypedArray())
        if (opened.handle == 0L) {
            val result = when (opened.status) {
                ChdChainNativeOpenStatus.PARENT_REQUIRED ->
                    ContainerIdentityResult.RetryableFailure(
                        ContainerIdentityReason.PARENT_REQUIRED,
                    )
                ChdChainNativeOpenStatus.RESOURCE_REFUSED ->
                    ContainerIdentityResult.RetryableFailure(
                        ContainerIdentityReason.RESOURCE_REFUSED,
                    )
                ChdChainNativeOpenStatus.IO_RETRYABLE ->
                    ContainerIdentityResult.RetryableFailure(
                        ContainerIdentityReason.IO_RETRYABLE,
                    )
                ChdChainNativeOpenStatus.UNSUPPORTED ->
                    ContainerIdentityResult.PolicyAbstain(
                        ContainerIdentityReason.UNSUPPORTED_CONTAINER,
                    )
                ChdChainNativeOpenStatus.MALFORMED,
                ChdChainNativeOpenStatus.OK,
                -> ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED)
            }
            return ChdChainOpen.Failed(
                result = result,
                failingNodeIndex = opened.failingNodeIndex.takeIf { it in nodes.indices },
            )
        }
        return try {
            ChdChainOpen.Opened(NativeChdChainReadSession(opened.handle, nodes.size))
        } catch (_: Exception) {
            ChdChainNativeBridge.close(opened.handle)
            ChdChainOpen.Failed(
                result = ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED),
                failingNodeIndex = null,
            )
        }
    }
}

private enum class ChdChainNativeOpenStatus(val wire: Long) {
    OK(0),
    IO_RETRYABLE(1),
    PARENT_REQUIRED(2),
    UNSUPPORTED(3),
    RESOURCE_REFUSED(4),
    MALFORMED(5),
    ;

    companion object {
        fun fromWire(value: Long): ChdChainNativeOpenStatus =
            entries.firstOrNull { it.wire == value } ?: MALFORMED
    }
}

private data class ChdChainNativeOpenResult(
    val status: ChdChainNativeOpenStatus,
    val handle: Long,
    val failingNodeIndex: Int,
)

private class NativeChdChainReadSession(
    private var handle: Long,
    nodeCount: Int,
) : ChdChainReadSession {
    override val nativeHandle: Long get() = handle

    override val nodes: List<ChdChainNodeInfo> = List(nodeCount) { nodeIndex ->
        val info = ChdChainNativeBridge.info(handle, nodeIndex)
            ?.takeIf { it.size == INFO_BYTES }
            ?: throw IllegalStateException("libchdr returned malformed chain info")
        ChdChainNodeInfo(
            version = info.beU32(0),
            logicalBytes = info.beU64(4),
            hunkBytes = info.beU32(12),
            hunkCount = info.beU32(16),
            rawSha1 = info.copyOfRange(20, 40).toHex(),
            combinedSha1 = info.copyOfRange(40, 60).toHex(),
            parentSha1 = info.copyOfRange(60, 80).toHex().takeUnless { value ->
                value.all { it == '0' }
            },
            compressionTags = List(4) { tagIndex ->
                info.beU32(80 + tagIndex * 4)
            },
        )
    }

    override fun readHunk(
        nodeIndex: Int,
        hunkIndex: Int,
        destination: ByteArray,
    ): ChdHunkRead = if (handle == 0L) {
        ChdHunkRead.MALFORMED
    } else {
        ChdChainNativeBridge.readHunk(handle, nodeIndex, hunkIndex, destination)
    }

    override fun readMetadata(
        nodeIndex: Int,
        metadataIndex: Int,
        maxBytes: Int,
    ): ChdMetadataRead = if (handle == 0L) {
        ChdMetadataRead.Malformed
    } else {
        ChdChainNativeBridge.metadata(handle, nodeIndex, metadataIndex, maxBytes)
            ?.toChdMetadataRead()
            ?: ChdMetadataRead.Malformed
    }

    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) ChdChainNativeBridge.close(current)
    }

    private fun ByteArray.beU32(at: Int): Int {
        check(at >= 0 && at + 4 <= size)
        return ((this[at].toInt() and 0xff) shl 24) or
            ((this[at + 1].toInt() and 0xff) shl 16) or
            ((this[at + 2].toInt() and 0xff) shl 8) or
            (this[at + 3].toInt() and 0xff)
    }

    private fun ByteArray.beU64(at: Int): Long {
        check(at >= 0 && at + 8 <= size && (this[at].toInt() and 0x80) == 0)
        var value = 0L
        for (index in at until at + 8) {
            value = (value shl 8) or (this[index].toLong() and 0xff)
        }
        return value
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") {
        "%02x".format(Locale.ROOT, it.toInt() and 0xff)
    }

    private companion object {
        const val INFO_BYTES = 96
    }
}

private fun ByteArray.toChdMetadataRead(): ChdMetadataRead {
    if (isEmpty()) return ChdMetadataRead.Malformed
    return when (this[0].toInt() and 0xff) {
        0 -> ChdMetadataRead.End
        1 -> ChdMetadataRead.NotChecksummed
        2 -> {
            if (size < 9) return ChdMetadataRead.Malformed
            val tag = bigU32(1)
            val length = bigU32(5).takeIf { it >= 0 } ?: return ChdMetadataRead.ResourceRefused
            if (size != 9 + length) return ChdMetadataRead.Malformed
            ChdMetadataRead.Checksummed(tag, copyOfRange(9, size))
        }
        3 -> ChdMetadataRead.ResourceRefused
        5 -> ChdMetadataRead.IoRetryable
        6 -> ChdMetadataRead.Unsupported
        else -> ChdMetadataRead.Malformed
    }
}

private fun ByteArray.bigU32(at: Int): Int {
    require(at >= 0 && at + 4 <= size)
    val value = ((this[at].toLong() and 0xff) shl 24) or
        ((this[at + 1].toLong() and 0xff) shl 16) or
        ((this[at + 2].toLong() and 0xff) shl 8) or
        (this[at + 3].toLong() and 0xff)
    return value.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: -1
}

private object ChdChainNativeBridge {
    private val loaded = try {
        System.loadLibrary("mimir_ra_hash")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun isAvailable(): Boolean = loaded

    fun open(paths: Array<String>): ChdChainNativeOpenResult {
        if (!loaded) {
            return ChdChainNativeOpenResult(ChdChainNativeOpenStatus.UNSUPPORTED, 0L, -1)
        }
        val result = try {
            nativeOpenChain(paths)
        } catch (_: Exception) {
            null
        } ?: return ChdChainNativeOpenResult(ChdChainNativeOpenStatus.MALFORMED, 0L, -1)
        if (result.size != 3) {
            return ChdChainNativeOpenResult(ChdChainNativeOpenStatus.MALFORMED, 0L, -1)
        }
        return ChdChainNativeOpenResult(
            status = ChdChainNativeOpenStatus.fromWire(result[0]),
            handle = result[1],
            failingNodeIndex = result[2].toInt(),
        )
    }

    fun info(handle: Long, nodeIndex: Int): ByteArray? =
        if (loaded && handle != 0L) {
            try {
                nativeChainInfo(handle, nodeIndex)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

    fun readHunk(
        handle: Long,
        nodeIndex: Int,
        hunkIndex: Int,
        destination: ByteArray,
    ): ChdHunkRead {
        if (!loaded || handle == 0L) return ChdHunkRead.UNSUPPORTED
        val wire = try {
            nativeChainReadHunk(handle, nodeIndex, hunkIndex, destination)
        } catch (_: Exception) {
            return ChdHunkRead.IO_RETRYABLE
        }
        return ChdHunkRead.entries.getOrNull(wire) ?: ChdHunkRead.MALFORMED
    }

    fun metadata(
        handle: Long,
        nodeIndex: Int,
        metadataIndex: Int,
        maxBytes: Int,
    ): ByteArray? = if (loaded && handle != 0L) {
        try {
            nativeChainMetadata(handle, nodeIndex, metadataIndex, maxBytes)
        } catch (_: Exception) {
            null
        }
    } else {
        null
    }

    fun close(handle: Long) {
        if (!loaded || handle == 0L) return
        try {
            nativeChainClose(handle)
        } catch (_: Exception) {
            // Native close is idempotent at the Kotlin ownership boundary.
        }
    }

    private external fun nativeOpenChain(paths: Array<String>): LongArray
    private external fun nativeChainInfo(handle: Long, nodeIndex: Int): ByteArray?
    private external fun nativeChainReadHunk(
        handle: Long,
        nodeIndex: Int,
        hunkIndex: Int,
        destination: ByteArray,
    ): Int
    private external fun nativeChainMetadata(
        handle: Long,
        nodeIndex: Int,
        metadataIndex: Int,
        maxBytes: Int,
    ): ByteArray?
    private external fun nativeChainClose(handle: Long)
}

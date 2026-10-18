package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.model.PlatformId
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class ArchivePayloadRefusalReason {
    UNSUPPORTED_PLATFORM,
    NO_ALLOWLISTED_PAYLOAD,
    MULTIPLE_ALLOWLISTED_PAYLOADS,
    NESTED_PAYLOAD_OVER_DEPTH,
    RESOURCE_REFUSED,
    STREAM_DESCRIPTOR_SIZE_MISMATCH,
    MALFORMED_ARCHIVE,
    INNER_MALFORMED,
    NO_CATALOG_MATCH,
    AMBIGUOUS_CATALOG_MATCH,
    IO_UNAVAILABLE,
    ARCHIVE_CHANGED,
    OTHER,
}

/** Evaluation evidence only; never title or grouping authority. */
internal data class ArchivePayloadReach(
    val deepestCandidateDepthReached: Int? = null,
)

internal data class ArchivePayloadIdentity(
    val fingerprint: RomFingerprint,
    val canonicalTitle: String,
    val collapseKey: String?,
    val origin: MetadataOrigin,
    val descentDepth: Int,
)

internal sealed interface ArchivePayloadSelectionResult {
    val reach: ArchivePayloadReach

    data class Identified(
        val identity: ArchivePayloadIdentity,
        override val reach: ArchivePayloadReach = ArchivePayloadReach(),
    ) : ArchivePayloadSelectionResult

    data class TerminalAbstain(
        val reason: ArchivePayloadRefusalReason,
        override val reach: ArchivePayloadReach = ArchivePayloadReach(),
    ) : ArchivePayloadSelectionResult

    data class Retryable(
        val reason: ArchivePayloadRefusalReason,
        override val reach: ArchivePayloadReach = ArchivePayloadReach(),
    ) : ArchivePayloadSelectionResult
}

internal data class ArchivePayloadOracleMatch(
    val canonicalTitle: String,
    val collapseKey: String?,
    val origin: MetadataOrigin,
)

internal object ArchivePayloadPolicy {
    const val REFUSAL_VOCABULARY_VERSION = 1
}

internal data class ArchivePayloadLimits(
    val maxTotalExpandedBytes: Long = 256L * 1024 * 1024,
    val maxWallNanos: Long = 45L * 1_000_000_000L,
)

internal fun archiveExpansionRatioExceeded(
    expandedBytes: Long,
    compressedBytes: Long,
    maxRatio: Long = 200L,
): Boolean {
    if (expandedBytes < 0L || compressedBytes <= 0L || maxRatio <= 0L) return false
    val allowed = if (compressedBytes > Long.MAX_VALUE / maxRatio) {
        Long.MAX_VALUE
    } else {
        compressedBytes * maxRatio
    }
    return expandedBytes > allowed
}

/**
 * Bounded DEFAULT-route ZIP payload selection.
 *
 * The first complete walk inventories shallow evidence and hashes Tier A only. Tier B and nested entries are
 * read only after the shallow preference requires them. Every subsequent walk is compared to the first
 * walk's complete raw SHA-256 generation stamp before its findings are admitted. Repeated work draws from the
 * same deadline and expansion budget. No entry is extracted, fully buffered, or written to disk.
 */
internal class ArchivePayloadSelector(
    private val platformId: String,
    private val residentSizeBytes: Long,
    private val openResident: () -> InputStream?,
    private val oracle: suspend (PlatformId, RomFingerprint) -> ArchivePayloadOracleMatch?,
    private val nanoTime: () -> Long = System::nanoTime,
    private val limits: ArchivePayloadLimits = ArchivePayloadLimits(),
    private val onCandidateReached: (Int) -> Unit = {},
) {
    suspend fun select(sharedBudget: ChdVerificationBudget): ArchivePayloadSelectionResult {
        val extensions = RomFingerprinter.archiveExtensionsFor(platformId)
            ?: return ArchivePayloadSelectionResult.TerminalAbstain(
                ArchivePayloadRefusalReason.UNSUPPORTED_PLATFORM,
            )
        val budget = ArchiveBudget(
            residentSizeBytes = residentSizeBytes,
            sharedBudget = sharedBudget,
            nanoTime = nanoTime,
            coroutineContext = currentCoroutineContext(),
            limits = limits,
            onCandidateReached = onCandidateReached,
        )
        val result = try {
            selectWithinBudget(extensions, budget)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RetryableArchiveFailure) {
            ArchivePayloadSelectionResult.Retryable(failure.reason)
        }
        return result.withReach(budget.reach())
    }

    private suspend fun selectWithinBudget(
        extensions: Set<String>,
        budget: ArchiveBudget,
    ): ArchivePayloadSelectionResult {
        val first = withResidentArchive(budget) { zip ->
            inventoryOuter(zip, extensions, budget)
        }
        val generation = first.generationSha256
        val outer = first.value

        if (outer.tierA.size == 1) {
            when (val resolution = resolveEvaluated(outer.tierA, descentDepth = 0, budget = budget)) {
                is FingerprintResolution.Identified ->
                    return ArchivePayloadSelectionResult.Identified(resolution.identity)
                FingerprintResolution.Conflict -> error("one Tier-A candidate cannot conflict")
                FingerprintResolution.NoMatch -> Unit
            }
        }

        if (outer.admissibleCount > MAX_CANDIDATES_HASHED) {
            return ArchivePayloadSelectionResult.TerminalAbstain(
                ArchivePayloadRefusalReason.RESOURCE_REFUSED,
            )
        }

        val tierBHashed = ArrayList<HashedCandidate>(outer.tierB.size)
        if (outer.tierB.isNotEmpty()) {
            val second = withResidentArchive(budget) { zip ->
                hashOuterTargets(zip, outer.tierB, extensions, budget)
            }
            requireSameGeneration(generation, second.generationSha256)
            tierBHashed += second.value
        }
        // A lone Tier-A miss was already evaluated above. Re-querying it here would both waste oracle work
        // and make the answer depend on a mutable oracle between passes. Multiple Tier-A candidates have not
        // yet been evaluated and therefore remain in the arbitration set.
        val outerCandidatesToEvaluate = if (outer.tierA.size == 1) {
            tierBHashed
        } else {
            outer.tierA + tierBHashed
        }
        when (
            val resolution = resolveEvaluated(
                outerCandidatesToEvaluate,
                descentDepth = 0,
                budget = budget,
            )
        ) {
            is FingerprintResolution.Identified ->
                return ArchivePayloadSelectionResult.Identified(resolution.identity)
            FingerprintResolution.Conflict ->
                return ArchivePayloadSelectionResult.TerminalAbstain(
                    ArchivePayloadRefusalReason.AMBIGUOUS_CATALOG_MATCH,
                )
            FingerprintResolution.NoMatch -> Unit
        }

        if (outer.nested.isEmpty()) {
            return ArchivePayloadSelectionResult.TerminalAbstain(
                if (outer.admissibleCount == 0) {
                    ArchivePayloadRefusalReason.NO_ALLOWLISTED_PAYLOAD
                } else {
                    ArchivePayloadRefusalReason.NO_CATALOG_MATCH
                },
            )
        }

        val numeric = outer.nested.filter(EntryObservation::numericBundleSignature)
        val remaining = outer.nested.filterNot(EntryObservation::numericBundleSignature)
        val nestedInventories = ArrayList<NestedInventory>()
        var innerMalformed = false
        var candidateOverflow = false
        listOf(numeric, remaining).filter(List<EntryObservation>::isNotEmpty).forEach { targets ->
            val pass = withResidentArchive(budget) { zip ->
                walkNestedTargets(zip, targets, extensions, budget)
            }
            requireSameGeneration(generation, pass.generationSha256)
            nestedInventories += pass.value.inventories
            innerMalformed = innerMalformed || pass.value.innerMalformed
            candidateOverflow = candidateOverflow || pass.value.candidateOverflow
        }
        if (candidateOverflow) {
            return ArchivePayloadSelectionResult.TerminalAbstain(
                ArchivePayloadRefusalReason.RESOURCE_REFUSED,
            )
        }
        if (innerMalformed) {
            return ArchivePayloadSelectionResult.TerminalAbstain(
                ArchivePayloadRefusalReason.INNER_MALFORMED,
            )
        }

        val identities = ArrayList<ArchivePayloadIdentity>()
        val tierBPending = ArrayList<NestedInventory>()
        var sawOverDepth = false
        for (inventory in nestedInventories) {
            val canResolveWithoutTierB = inventory.tierB.isEmpty() || inventory.tierA.size == 1
            if (!canResolveWithoutTierB) {
                tierBPending += inventory
                continue
            }
            when (
                val resolution = resolveEvaluated(
                    inventory.tierA,
                    descentDepth = 1,
                    budget = budget,
                )
            ) {
                is FingerprintResolution.Identified -> identities += resolution.identity
                FingerprintResolution.Conflict ->
                    return ArchivePayloadSelectionResult.TerminalAbstain(
                        ArchivePayloadRefusalReason.AMBIGUOUS_CATALOG_MATCH,
                    )
                FingerprintResolution.NoMatch -> {
                    if (inventory.tierB.isNotEmpty()) {
                        // A lone Tier-A candidate is the depth-one fast path. Only its miss admits Tier B.
                        tierBPending += inventory
                    } else if (inventory.overDepth) {
                        sawOverDepth = true
                    }
                }
            }
        }

        val tierBResults = HashMap<Int, NestedInventory>()
        val numericPending = tierBPending.filter { it.outer.numericBundleSignature() }
        val remainingPending = tierBPending.filterNot { it.outer.numericBundleSignature() }
        for (targets in listOf(numericPending, remainingPending).filter(List<NestedInventory>::isNotEmpty)) {
            val pass = withResidentArchive(budget) { zip ->
                walkNestedTierBTargets(zip, targets, extensions, budget)
            }
            requireSameGeneration(generation, pass.generationSha256)
            innerMalformed = innerMalformed || pass.value.innerMalformed
            candidateOverflow = candidateOverflow || pass.value.candidateOverflow
            pass.value.inventories.forEach { inventory ->
                tierBResults[inventory.outer.ordinal] = inventory
            }
        }
        if (candidateOverflow) {
            return ArchivePayloadSelectionResult.TerminalAbstain(
                ArchivePayloadRefusalReason.RESOURCE_REFUSED,
            )
        }
        if (innerMalformed) {
            return ArchivePayloadSelectionResult.TerminalAbstain(
                ArchivePayloadRefusalReason.INNER_MALFORMED,
            )
        }
        for (inventory in tierBPending) {
            val hashed = tierBResults[inventory.outer.ordinal]
                ?: throw RetryableArchiveFailure(ArchivePayloadRefusalReason.ARCHIVE_CHANGED)
            val candidates = if (inventory.tierA.size == 1) {
                hashed.hashedTierB
            } else {
                inventory.tierA + hashed.hashedTierB
            }
            when (
                val resolution = resolveEvaluated(
                    candidates,
                    descentDepth = 1,
                    budget = budget,
                )
            ) {
                is FingerprintResolution.Identified -> identities += resolution.identity
                FingerprintResolution.Conflict ->
                    return ArchivePayloadSelectionResult.TerminalAbstain(
                        ArchivePayloadRefusalReason.AMBIGUOUS_CATALOG_MATCH,
                    )
                FingerprintResolution.NoMatch -> if (inventory.overDepth) sawOverDepth = true
            }
        }
        if (identities.isNotEmpty()) {
            val authorities = identities.map { identity ->
                identity.canonicalTitle to identity.collapseKey
            }.distinct()
            return if (authorities.size == 1) {
                ArchivePayloadSelectionResult.Identified(identities.first())
            } else {
                ArchivePayloadSelectionResult.TerminalAbstain(
                    ArchivePayloadRefusalReason.AMBIGUOUS_CATALOG_MATCH,
                )
            }
        }
        return ArchivePayloadSelectionResult.TerminalAbstain(
            when {
                sawOverDepth -> ArchivePayloadRefusalReason.NESTED_PAYLOAD_OVER_DEPTH
                outer.admissibleCount > 0 ||
                    nestedInventories.any { it.tierA.isNotEmpty() || it.tierB.isNotEmpty() } ->
                    ArchivePayloadRefusalReason.NO_CATALOG_MATCH
                else -> ArchivePayloadRefusalReason.NO_ALLOWLISTED_PAYLOAD
            },
        )
    }

    private fun inventoryOuter(
        zip: ZipInputStream,
        extensions: Set<String>,
        budget: ArchiveBudget,
    ): ArchiveWalk<OuterInventory> {
        val tierA = ArrayList<HashedCandidate>()
        val tierB = ArrayList<EntryObservation>()
        val nested = ArrayList<EntryObservation>()
        val localEntries = ArrayList<LocalEntryProof>()
        var admissibleCount = 0
        var ordinal = 0
        while (true) {
            budget.beforeEntry()
            val entry = nextEntry(zip) ?: break
            val currentOrdinal = ordinal++
            budget.observeEntry(EntryKey(0, null, currentOrdinal), entry)
            val tier = classifyName(entry, extensions)
            val bounded = ArchiveEntryInputStream(zip, entry, budget)
            when (tier) {
                EntryTier.A -> {
                    val admitted = CandidateAdmissionInputStream(bounded) {
                        budget.recordCandidateHash(depth = 0)
                    }
                    val fingerprint = if (budget.candidateSlotsRemaining() > 0) {
                        fingerprint(admitted, budget)
                    } else {
                        drain(admitted)
                        null
                    }
                    drain(admitted)
                    finishEntry(zip, entry, bounded, budget)
                    if (admitted.admitted) {
                        admissibleCount++
                        if (fingerprint != null) tierA += HashedCandidate(EntryTier.A, fingerprint)
                    }
                }
                EntryTier.B -> {
                    drain(bounded)
                    finishEntry(zip, entry, bounded, budget)
                    if (bounded.bytesRead >= MIN_PAYLOAD_BYTES) {
                        budget.recordCandidateReach(depth = 0)
                        admissibleCount++
                        tierB += EntryObservation(currentOrdinal, entry.name, EntryTier.B)
                    }
                }
                EntryTier.C -> {
                    drain(bounded)
                    finishEntry(zip, entry, bounded, budget)
                    if (bounded.bytesRead >= MIN_PAYLOAD_BYTES) {
                        nested += EntryObservation(currentOrdinal, entry.name, EntryTier.C)
                    }
                }
                EntryTier.EXCLUDED -> {
                    drain(bounded)
                    finishEntry(zip, entry, bounded, budget)
                }
            }
            localEntries += bounded.proof(entry)
        }
        return ArchiveWalk(
            OuterInventory(tierA, tierB, nested, admissibleCount),
            localEntries = localEntries,
        )
    }

    private fun hashOuterTargets(
        zip: ZipInputStream,
        targets: List<EntryObservation>,
        extensions: Set<String>,
        budget: ArchiveBudget,
    ): ArchiveWalk<List<HashedCandidate>> {
        val byOrdinal = targets.associateBy(EntryObservation::ordinal)
        val found = HashSet<Int>()
        val fingerprints = ArrayList<HashedCandidate>(targets.size)
        val localEntries = ArrayList<LocalEntryProof>()
        var ordinal = 0
        while (true) {
            budget.beforeEntry()
            val entry = nextEntry(zip) ?: break
            val currentOrdinal = ordinal++
            budget.observeEntry(EntryKey(0, null, currentOrdinal), entry)
            val expected = byOrdinal[currentOrdinal]
            val bounded = ArchiveEntryInputStream(zip, entry, budget)
            if (expected != null) {
                if (expected.name != entry.name || classifyName(entry, extensions) != EntryTier.B) {
                    throw RetryableArchiveFailure(ArchivePayloadRefusalReason.ARCHIVE_CHANGED)
                }
                val admitted = CandidateAdmissionInputStream(bounded) {
                    budget.recordCandidateHash(depth = 0)
                }
                val fingerprint = fingerprint(admitted, budget)
                drain(admitted)
                finishEntry(zip, entry, bounded, budget)
                if (!admitted.admitted) {
                    throw RetryableArchiveFailure(ArchivePayloadRefusalReason.ARCHIVE_CHANGED)
                }
                fingerprints += HashedCandidate(EntryTier.B, fingerprint)
                found += currentOrdinal
            } else {
                drain(bounded)
                finishEntry(zip, entry, bounded, budget)
            }
            localEntries += bounded.proof(entry)
        }
        if (found.size != targets.size) {
            throw RetryableArchiveFailure(ArchivePayloadRefusalReason.ARCHIVE_CHANGED)
        }
        return ArchiveWalk(fingerprints, localEntries)
    }

    private fun walkNestedTargets(
        zip: ZipInputStream,
        targets: List<EntryObservation>,
        extensions: Set<String>,
        budget: ArchiveBudget,
    ): ArchiveWalk<NestedPass> {
        val byOrdinal = targets.associateBy(EntryObservation::ordinal)
        val found = HashSet<Int>()
        val inventories = ArrayList<NestedInventory>()
        var innerMalformed = false
        var candidateOverflow = false
        val localEntries = ArrayList<LocalEntryProof>()
        var ordinal = 0
        while (true) {
            budget.beforeEntry()
            val entry = nextEntry(zip) ?: break
            val currentOrdinal = ordinal++
            budget.observeEntry(EntryKey(0, null, currentOrdinal), entry)
            val target = byOrdinal[currentOrdinal]
            val bounded = ArchiveEntryInputStream(zip, entry, budget)
            if (target == null) {
                drain(bounded)
                finishEntry(zip, entry, bounded, budget)
                localEntries += bounded.proof(entry)
                continue
            }
            found += currentOrdinal
            if (target.name != entry.name || classifyName(entry, extensions) != EntryTier.C) {
                throw RetryableArchiveFailure(ArchivePayloadRefusalReason.ARCHIVE_CHANGED)
            }
            if (!budget.tryRecordNestedDescent()) {
                candidateOverflow = true
                drain(bounded)
                finishEntry(zip, entry, bounded, budget)
                localEntries += bounded.proof(entry)
                continue
            }
            when (
                val outcome = readNestedEntry(
                    outerEntry = entry,
                    outerBounded = bounded,
                    outerZip = zip,
                    outerObservation = target,
                    extensions = extensions,
                    budget = budget,
                )
            ) {
                is NestedReadOutcome.Inventory -> {
                    inventories += outcome.inventory
                    candidateOverflow = candidateOverflow || outcome.candidateOverflow
                }
                NestedReadOutcome.Malformed -> innerMalformed = true
            }
            localEntries += bounded.proof(entry)
        }
        if (found.size != targets.size) {
            throw RetryableArchiveFailure(ArchivePayloadRefusalReason.ARCHIVE_CHANGED)
        }
        return ArchiveWalk(
            NestedPass(inventories, innerMalformed, candidateOverflow),
            localEntries = localEntries,
        )
    }

    private fun walkNestedTierBTargets(
        zip: ZipInputStream,
        targets: List<NestedInventory>,
        extensions: Set<String>,
        budget: ArchiveBudget,
    ): ArchiveWalk<NestedPass> {
        val byOrdinal = targets.associateBy { it.outer.ordinal }
        val found = HashSet<Int>()
        val inventories = ArrayList<NestedInventory>()
        val localEntries = ArrayList<LocalEntryProof>()
        var innerMalformed = false
        var candidateOverflow = false
        var ordinal = 0
        while (true) {
            budget.beforeEntry()
            val entry = nextEntry(zip) ?: break
            val currentOrdinal = ordinal++
            budget.observeEntry(EntryKey(0, null, currentOrdinal), entry)
            val target = byOrdinal[currentOrdinal]
            val bounded = ArchiveEntryInputStream(zip, entry, budget)
            if (target == null) {
                drain(bounded)
                finishEntry(zip, entry, bounded, budget)
                localEntries += bounded.proof(entry)
                continue
            }
            found += currentOrdinal
            if (
                target.outer.name != entry.name ||
                classifyName(entry, extensions) != EntryTier.C
            ) {
                throw RetryableArchiveFailure(ArchivePayloadRefusalReason.ARCHIVE_CHANGED)
            }
            when (
                val outcome = readNestedEntry(
                    outerEntry = entry,
                    outerBounded = bounded,
                    outerZip = zip,
                    outerObservation = target.outer,
                    extensions = extensions,
                    budget = budget,
                    tierBTargets = target.tierB,
                )
            ) {
                is NestedReadOutcome.Inventory -> {
                    inventories += outcome.inventory
                    candidateOverflow = candidateOverflow || outcome.candidateOverflow
                }
                NestedReadOutcome.Malformed -> innerMalformed = true
            }
            localEntries += bounded.proof(entry)
        }
        if (found.size != targets.size) {
            throw RetryableArchiveFailure(ArchivePayloadRefusalReason.ARCHIVE_CHANGED)
        }
        return ArchiveWalk(
            NestedPass(inventories, innerMalformed, candidateOverflow),
            localEntries,
        )
    }

    private fun readNestedEntry(
        outerEntry: ZipEntry,
        outerBounded: ArchiveEntryInputStream,
        outerZip: ZipInputStream,
        outerObservation: EntryObservation,
        extensions: Set<String>,
        budget: ArchiveBudget,
        tierBTargets: List<EntryObservation>? = null,
    ): NestedReadOutcome {
        val tail = TailCaptureInputStream(NonClosingInputStream(outerBounded))
        val guarded = PushbackInputStream(tail, ZIP_SIGNATURE_BYTES)
        var walk: ArchiveWalk<NestedInventory>? = null
        var structuralFailure: Throwable? = null
        val inner = ZipInputStream(guarded)
        try {
            if (!hasZipSignature(guarded)) {
                structuralFailure = ZipException("nested entry is not a ZIP stream")
            } else {
                walk = walkInner(
                    zip = inner,
                    outerObservation = outerObservation,
                    extensions = extensions,
                    budget = budget,
                    tierBTargets = tierBTargets,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RetryableArchiveFailure) {
            throw failure
        } catch (failure: ZipException) {
            structuralFailure = failure
        } catch (failure: EOFException) {
            structuralFailure = failure
        } catch (failure: IllegalArgumentException) {
            structuralFailure = failure
        } catch (failure: IOException) {
            throw RetryableArchiveFailure(ArchivePayloadRefusalReason.IO_UNAVAILABLE, failure)
        } finally {
            try {
                inner.close()
            } catch (failure: ZipException) {
                structuralFailure = structuralFailure ?: failure
            } catch (failure: IllegalArgumentException) {
                structuralFailure = structuralFailure ?: failure
            } catch (failure: IOException) {
                throw RetryableArchiveFailure(ArchivePayloadRefusalReason.IO_UNAVAILABLE, failure)
            }
        }
        try {
            drain(tail)
        } catch (known: RetryableArchiveFailure) {
            throw known
        } catch (failure: ZipException) {
            structuralFailure = structuralFailure ?: failure
        } catch (failure: EOFException) {
            structuralFailure = structuralFailure ?: failure
        } catch (failure: IOException) {
            throw RetryableArchiveFailure(ArchivePayloadRefusalReason.IO_UNAVAILABLE, failure)
        }
        if (structuralFailure == null) {
            val completed = requireNotNull(walk)
            when (tail.proveCentralDirectory(completed.localEntries)) {
                CentralDirectoryProof.COMPLETE -> Unit
                CentralDirectoryProof.MALFORMED ->
                    structuralFailure = ZipException("nested ZIP has no proved central directory")
                CentralDirectoryProof.RESOURCE_REFUSED ->
                    throw RetryableArchiveFailure(ArchivePayloadRefusalReason.RESOURCE_REFUSED)
            }
        }
        try {
            drain(outerBounded)
            finishEntry(outerZip, outerEntry, outerBounded, budget)
        } catch (failure: RetryableArchiveFailure) {
            if (failure.reason == ArchivePayloadRefusalReason.STREAM_DESCRIPTOR_SIZE_MISMATCH) {
                throw RetryableArchiveFailure(ArchivePayloadRefusalReason.MALFORMED_ARCHIVE, failure)
            }
            throw failure
        } catch (failure: ZipException) {
            throw RetryableArchiveFailure(ArchivePayloadRefusalReason.MALFORMED_ARCHIVE, failure)
        } catch (failure: EOFException) {
            throw RetryableArchiveFailure(ArchivePayloadRefusalReason.MALFORMED_ARCHIVE, failure)
        } catch (failure: IOException) {
            throw RetryableArchiveFailure(ArchivePayloadRefusalReason.IO_UNAVAILABLE, failure)
        }
        return if (structuralFailure != null) {
            NestedReadOutcome.Malformed
        } else {
            NestedReadOutcome.Inventory(requireNotNull(walk).value, budget.candidateOverflow)
        }
    }

    private fun walkInner(
        zip: ZipInputStream,
        outerObservation: EntryObservation,
        extensions: Set<String>,
        budget: ArchiveBudget,
        tierBTargets: List<EntryObservation>?,
    ): ArchiveWalk<NestedInventory> {
        val tierA = ArrayList<HashedCandidate>()
        val tierB = ArrayList<EntryObservation>()
        val hashedTierB = ArrayList<HashedCandidate>()
        val targetsByOrdinal = tierBTargets?.associateBy(EntryObservation::ordinal).orEmpty()
        val foundTargets = HashSet<Int>()
        val localEntries = ArrayList<LocalEntryProof>()
        var overDepth = false
        var ordinal = 0
        while (true) {
            budget.beforeEntry()
            val entry = nextEntry(zip) ?: break
            val currentOrdinal = ordinal++
            budget.observeEntry(EntryKey(1, outerObservation.ordinal, currentOrdinal), entry)
            val tier = classifyName(entry, extensions)
            val bounded = ArchiveEntryInputStream(zip, entry, budget)
            when (tier) {
                EntryTier.A -> {
                    if (tierBTargets != null) {
                        drain(bounded)
                        finishEntry(zip, entry, bounded, budget)
                    } else {
                        val admitted = CandidateAdmissionInputStream(bounded) {
                            budget.recordCandidateHash(depth = 1)
                        }
                        val fingerprint = if (budget.candidateSlotsRemaining() > 0) {
                            fingerprint(admitted, budget)
                        } else {
                            drain(admitted)
                            null
                        }
                        drain(admitted)
                        finishEntry(zip, entry, bounded, budget)
                        if (admitted.admitted && fingerprint != null) {
                            tierA += HashedCandidate(EntryTier.A, fingerprint)
                        }
                    }
                }
                EntryTier.B -> {
                    val target = targetsByOrdinal[currentOrdinal]
                    if (tierBTargets == null) {
                        drain(bounded)
                        finishEntry(zip, entry, bounded, budget)
                        if (bounded.bytesRead >= MIN_PAYLOAD_BYTES) {
                            budget.recordCandidateReach(depth = 1)
                            tierB += EntryObservation(currentOrdinal, entry.name, EntryTier.B)
                        }
                    } else if (target == null) {
                        drain(bounded)
                        finishEntry(zip, entry, bounded, budget)
                    } else {
                        if (target.name != entry.name) {
                            throw RetryableArchiveFailure(ArchivePayloadRefusalReason.ARCHIVE_CHANGED)
                        }
                        foundTargets += currentOrdinal
                        if (budget.candidateSlotsRemaining() == 0) {
                            drain(bounded)
                            finishEntry(zip, entry, bounded, budget)
                            if (bounded.bytesRead >= MIN_PAYLOAD_BYTES) {
                                budget.recordCandidateReach(depth = 1)
                                budget.markCandidateOverflow()
                            }
                        } else {
                            val admitted = CandidateAdmissionInputStream(bounded) {
                                budget.recordCandidateHash(depth = 1)
                            }
                            val fingerprint = fingerprint(admitted, budget)
                            drain(admitted)
                            finishEntry(zip, entry, bounded, budget)
                            if (!admitted.admitted) {
                                throw RetryableArchiveFailure(
                                    ArchivePayloadRefusalReason.ARCHIVE_CHANGED,
                                )
                            }
                            hashedTierB += HashedCandidate(EntryTier.B, fingerprint)
                        }
                    }
                }
                EntryTier.C -> {
                    drain(bounded)
                    finishEntry(zip, entry, bounded, budget)
                    if (bounded.bytesRead >= MIN_PAYLOAD_BYTES) {
                        overDepth = true
                    }
                }
                EntryTier.EXCLUDED -> {
                    drain(bounded)
                    finishEntry(zip, entry, bounded, budget)
                }
            }
            localEntries += bounded.proof(entry)
        }
        if (tierBTargets != null && foundTargets.size != tierBTargets.size) {
            throw RetryableArchiveFailure(ArchivePayloadRefusalReason.ARCHIVE_CHANGED)
        }
        return ArchiveWalk(
            NestedInventory(
                outer = outerObservation,
                tierA = tierA,
                tierB = tierB,
                hashedTierB = hashedTierB,
                overDepth = overDepth,
            ),
            localEntries,
        )
    }

    private fun fingerprint(
        input: InputStream,
        budget: ArchiveBudget,
    ): RomFingerprint = RomFingerprinter.fingerprintArchiveStream(
        platformId = platformId,
        input = input,
        checkpoint = budget::checkpoint,
    )

    private suspend fun resolveEvaluated(
        candidates: List<HashedCandidate>,
        descentDepth: Int,
        budget: ArchiveBudget,
    ): FingerprintResolution {
        val hits = ArrayList<Pair<RomFingerprint, ArchivePayloadOracleMatch>>()
        for (candidate in candidates) {
            budget.checkpoint()
            val match = oracle(PlatformId(platformId), candidate.fingerprint)
            budget.checkpoint()
            if (match != null) {
                hits += candidate.fingerprint to match
            }
        }
        budget.checkpoint()
        if (hits.isEmpty()) return FingerprintResolution.NoMatch
        val authorities = hits.map { (_, match) ->
            match.canonicalTitle to match.collapseKey
        }.distinct()
        budget.checkpoint()
        if (authorities.size != 1) return FingerprintResolution.Conflict
        val (fingerprint, match) = hits.first()
        budget.checkpoint()
        return FingerprintResolution.Identified(
            ArchivePayloadIdentity(
                fingerprint = fingerprint,
                canonicalTitle = match.canonicalTitle,
                collapseKey = match.collapseKey,
                origin = match.origin,
                descentDepth = descentDepth,
            ),
        )
    }

    private suspend fun <T> withResidentArchive(
        budget: ArchiveBudget,
        block: suspend (ZipInputStream) -> ArchiveWalk<T>,
    ): ResidentPass<T> {
        budget.checkpoint()
        val input = try {
            openResident()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            throw RetryableArchiveFailure(ArchivePayloadRefusalReason.IO_UNAVAILABLE, failure)
        } catch (_: SecurityException) {
            null
        } ?: throw RetryableArchiveFailure(ArchivePayloadRefusalReason.IO_UNAVAILABLE)
        val tail = TailCaptureInputStream(input) { totalBytes ->
            budget.accountRootRawRead(totalBytes)
        }
        val guarded = PushbackInputStream(tail, ZIP_SIGNATURE_BYTES)
        val zip = ZipInputStream(guarded)
        try {
            if (!hasZipSignature(guarded)) {
                throw RetryableArchiveFailure(ArchivePayloadRefusalReason.MALFORMED_ARCHIVE)
            }
            val walk = block(zip)
            drain(tail)
            when (tail.proveCentralDirectory(walk.localEntries)) {
                CentralDirectoryProof.COMPLETE -> Unit
                CentralDirectoryProof.MALFORMED ->
                    throw RetryableArchiveFailure(ArchivePayloadRefusalReason.MALFORMED_ARCHIVE)
                CentralDirectoryProof.RESOURCE_REFUSED ->
                    throw RetryableArchiveFailure(ArchivePayloadRefusalReason.RESOURCE_REFUSED)
            }
            return ResidentPass(walk.value, tail.sha256())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (known: RetryableArchiveFailure) {
            throw known
        } catch (failure: ZipException) {
            throw RetryableArchiveFailure(ArchivePayloadRefusalReason.MALFORMED_ARCHIVE, failure)
        } catch (failure: EOFException) {
            throw RetryableArchiveFailure(ArchivePayloadRefusalReason.MALFORMED_ARCHIVE, failure)
        } catch (failure: IllegalArgumentException) {
            throw RetryableArchiveFailure(ArchivePayloadRefusalReason.MALFORMED_ARCHIVE, failure)
        } catch (failure: SecurityException) {
            throw RetryableArchiveFailure(ArchivePayloadRefusalReason.IO_UNAVAILABLE, failure)
        } catch (failure: IOException) {
            throw RetryableArchiveFailure(ArchivePayloadRefusalReason.IO_UNAVAILABLE, failure)
        } finally {
            runCatching { zip.close() }
        }
    }

    private fun requireSameGeneration(expected: String, actual: String) {
        if (expected != actual) {
            throw RetryableArchiveFailure(ArchivePayloadRefusalReason.ARCHIVE_CHANGED)
        }
    }

    private fun nextEntry(zip: ZipInputStream): ZipEntry? = try {
        zip.nextEntry
    } catch (known: RetryableArchiveFailure) {
        throw known
    } catch (failure: ZipException) {
        throw failure
    } catch (failure: EOFException) {
        throw failure
    } catch (failure: IOException) {
        throw RetryableArchiveFailure(ArchivePayloadRefusalReason.IO_UNAVAILABLE, failure)
    }

    private fun classifyName(entry: ZipEntry, extensions: Set<String>): EntryTier {
        if (entry.isDirectory) return EntryTier.EXCLUDED
        val name = entry.name
        val base = name.substringAfterLast('/')
        val extension = extensionOf(name)
        if (isAppleDouble(name, base)) return EntryTier.EXCLUDED
        if (extension in UNSUPPORTED_CONTAINERS) return EntryTier.EXCLUDED
        if (isMalformedName(name)) return EntryTier.EXCLUDED
        if (extension in extensions) return EntryTier.A
        if (extension == "zip") return EntryTier.C
        if (extension in JUNK_EXTENSIONS || base.lowercase() in JUNK_BASENAMES) {
            return EntryTier.EXCLUDED
        }
        return EntryTier.B
    }

    private fun finishEntry(
        zip: ZipInputStream,
        entry: ZipEntry,
        bounded: ArchiveEntryInputStream,
        budget: ArchiveBudget,
    ) {
        zip.closeEntry()
        val declared = entry.size
        if (declared >= 0L && bounded.bytesRead != declared) {
            throw RetryableArchiveFailure(
                ArchivePayloadRefusalReason.STREAM_DESCRIPTOR_SIZE_MISMATCH,
            )
        }
        val compressed = entry.compressedSize
        if (compressed == 0L && bounded.bytesRead > 0L) {
            throw RetryableArchiveFailure(ArchivePayloadRefusalReason.MALFORMED_ARCHIVE)
        }
        if (
            bounded.bytesRead >= MIN_RATIO_CHECK_BYTES &&
            compressed > 0L &&
            archiveExpansionRatioExceeded(bounded.bytesRead, compressed, MAX_RATIO)
        ) {
            throw RetryableArchiveFailure(ArchivePayloadRefusalReason.RESOURCE_REFUSED)
        }
        budget.checkpoint()
    }

    private fun drain(input: InputStream) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (input.read(buffer) >= 0) {
            // Accounted by the owning bounded stream.
        }
    }

    private fun isAppleDouble(name: String, base: String): Boolean =
        name.split('/').any { it.equals("__MACOSX", ignoreCase = true) } || base.startsWith("._")

    private fun isMalformedName(name: String): Boolean {
        if (name.isBlank() || name.startsWith('/') || name.startsWith('\\')) return true
        if ('\\' in name || '\u0000' in name) return true
        return name.removeSuffix("/").split('/').any { segment ->
            segment.isBlank() || segment == "." || segment == ".."
        }
    }

    private fun extensionOf(name: String): String =
        name.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    private fun hasZipSignature(input: PushbackInputStream): Boolean {
        val signature = ByteArray(ZIP_SIGNATURE_BYTES)
        var length = 0
        while (length < signature.size) {
            val read = input.read(signature, length, signature.size - length)
            if (read < 0) break
            length += read
        }
        if (length > 0) input.unread(signature, 0, length)
        if (length != signature.size) return false
        return leU32(signature, 0) in setOf(
            ZIP_LOCAL_HEADER,
            ZIP_EMPTY_ARCHIVE,
            ZIP_SPANNED_ARCHIVE,
        )
    }

    private fun ArchivePayloadSelectionResult.withReach(
        reach: ArchivePayloadReach,
    ): ArchivePayloadSelectionResult = when (this) {
        is ArchivePayloadSelectionResult.Identified -> copy(reach = reach)
        is ArchivePayloadSelectionResult.TerminalAbstain -> copy(reach = reach)
        is ArchivePayloadSelectionResult.Retryable -> copy(reach = reach)
    }

    private data class ResidentPass<T>(
        val value: T,
        val generationSha256: String,
    )

    private data class ArchiveWalk<T>(
        val value: T,
        val localEntries: List<LocalEntryProof>,
    )

    private data class OuterInventory(
        val tierA: List<HashedCandidate>,
        val tierB: List<EntryObservation>,
        val nested: List<EntryObservation>,
        val admissibleCount: Int,
    )

    private data class EntryObservation(
        val ordinal: Int,
        val name: String,
        val tier: EntryTier,
    ) {
        fun numericBundleSignature(): Boolean {
            val base = name.substringAfterLast('/').substringBeforeLast('.')
            val parent = name.substringBeforeLast('/', "").substringAfterLast('/')
            return base.isNotEmpty() && parent.isNotEmpty() &&
                base.all(Char::isDigit) && parent.all(Char::isDigit)
        }
    }

    private data class NestedInventory(
        val outer: EntryObservation,
        val tierA: List<HashedCandidate>,
        val tierB: List<EntryObservation>,
        val hashedTierB: List<HashedCandidate>,
        val overDepth: Boolean,
    )

    private data class LocalEntryProof(
        val name: String,
        val method: Int,
        val crc32: Long,
        val compressedSize: Long?,
        val uncompressedSize: Long,
    )

    private data class RawLocalHeaderProof(
        val offset: Long,
        val flags: Int,
        val method: Int,
        val crc32: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val extraLength: Int,
        val name: ByteArray,
    )

    private data class CentralEntryRecord(
        val flags: Int,
        val method: Int,
        val crc32: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        val name: ByteArray,
    )

    private data class NestedPass(
        val inventories: List<NestedInventory>,
        val innerMalformed: Boolean,
        val candidateOverflow: Boolean,
    )

    private sealed interface NestedReadOutcome {
        data class Inventory(
            val inventory: NestedInventory,
            val candidateOverflow: Boolean,
        ) : NestedReadOutcome

        data object Malformed : NestedReadOutcome
    }

    private data class HashedCandidate(
        val tier: EntryTier,
        val fingerprint: RomFingerprint,
    )

    private enum class EntryTier { A, B, C, EXCLUDED }

    private sealed interface FingerprintResolution {
        data class Identified(val identity: ArchivePayloadIdentity) : FingerprintResolution
        data object Conflict : FingerprintResolution
        data object NoMatch : FingerprintResolution
    }

    private enum class CentralDirectoryProof {
        COMPLETE,
        MALFORMED,
        RESOURCE_REFUSED,
    }

    private class RetryableArchiveFailure(
        val reason: ArchivePayloadRefusalReason,
        cause: Throwable? = null,
    ) : IOException(cause)

    private data class EntryKey(
        val depth: Int,
        val parentOrdinal: Int?,
        val ordinal: Int,
    )

    private class ArchiveBudget(
        residentSizeBytes: Long,
        private val sharedBudget: ChdVerificationBudget,
        private val nanoTime: () -> Long,
        private val coroutineContext: CoroutineContext,
        private val limits: ArchivePayloadLimits,
        private val onCandidateReached: (Int) -> Unit,
    ) {
        private val deadlineNanos: Long
        private val aggregateExpandedLimit: Long
        private val rootRawLimit: Long
        private val entries = HashSet<EntryKey>()
        private var expandedBytes = 0L
        private var candidateWork = 0
        private var deepestCandidateDepthReached: Int? = null
        var candidateOverflow: Boolean = false
            private set

        init {
            val now = nanoTime()
            deadlineNanos = minOf(
                MonotonicDeadline.deadlineAfter(now, limits.maxWallNanos),
                MonotonicDeadline.deadlineAfter(now, sharedBudget.remainingNanos()),
            )
            aggregateExpandedLimit = saturatingMultiply(
                residentSizeBytes.coerceAtLeast(1L),
                MAX_AGGREGATE_RATIO,
            )
            rootRawLimit = residentSizeBytes.takeIf { it > 0L } ?: limits.maxTotalExpandedBytes
        }

        fun checkpoint() {
            coroutineContext.ensureActive()
            if (
                sharedBudget.isExhausted() ||
                MonotonicDeadline.remainingUntil(deadlineNanos, nanoTime()) == 0L
            ) {
                throw RetryableArchiveFailure(ArchivePayloadRefusalReason.RESOURCE_REFUSED)
            }
        }

        fun beforeEntry() = checkpoint()

        fun observeEntry(key: EntryKey, entry: ZipEntry) {
            checkpoint()
            if (entry.name.toByteArray(Charsets.UTF_8).size > MAX_ENTRY_NAME_BYTES) {
                throw RetryableArchiveFailure(ArchivePayloadRefusalReason.RESOURCE_REFUSED)
            }
            if (entry.size > MAX_ENTRY_BYTES) {
                throw RetryableArchiveFailure(ArchivePayloadRefusalReason.RESOURCE_REFUSED)
            }
            if (entries.add(key) && entries.size > MAX_ENTRIES_EXAMINED) {
                throw RetryableArchiveFailure(ArchivePayloadRefusalReason.RESOURCE_REFUSED)
            }
        }

        fun accountRead(entryBytes: Long, read: Long) {
            checkpoint()
            if (entryBytes > MAX_ENTRY_BYTES || read > Long.MAX_VALUE - expandedBytes) {
                throw RetryableArchiveFailure(ArchivePayloadRefusalReason.RESOURCE_REFUSED)
            }
            expandedBytes += read
            if (
                expandedBytes > limits.maxTotalExpandedBytes ||
                expandedBytes > aggregateExpandedLimit
            ) {
                throw RetryableArchiveFailure(ArchivePayloadRefusalReason.RESOURCE_REFUSED)
            }
        }

        /**
         * Bounds and checkpoints the complete raw resident walk, including the central directory and EOCD
         * bytes that ZipInputStream leaves for the final tail drain.
         */
        fun accountRootRawRead(totalBytes: Long) {
            checkpoint()
            if (totalBytes > rootRawLimit) {
                throw RetryableArchiveFailure(ArchivePayloadRefusalReason.RESOURCE_REFUSED)
            }
        }

        fun candidateSlotsRemaining(): Int = MAX_CANDIDATES_HASHED - candidateWork

        fun recordCandidateHash(depth: Int) {
            recordCandidateReach(depth)
            if (candidateWork >= MAX_CANDIDATES_HASHED) {
                candidateOverflow = true
                return
            }
            candidateWork++
        }

        fun recordCandidateReach(depth: Int) {
            val current = deepestCandidateDepthReached
            if (current == null || depth > current) {
                deepestCandidateDepthReached = depth
            }
            onCandidateReached(depth)
        }

        fun tryRecordNestedDescent(): Boolean {
            if (candidateWork >= MAX_CANDIDATES_HASHED) {
                candidateOverflow = true
                return false
            }
            candidateWork++
            return true
        }

        fun markCandidateOverflow() {
            candidateOverflow = true
        }

        fun reach() = ArchivePayloadReach(deepestCandidateDepthReached)
    }

    private class ArchiveEntryInputStream(
        private val delegate: InputStream,
        private val entry: ZipEntry,
        private val budget: ArchiveBudget,
    ) : InputStream() {
        private val crc32 = CRC32()
        var bytesRead: Long = 0L
            private set

        override fun read(): Int {
            budget.checkpoint()
            val value = delegate.read()
            if (value >= 0) {
                crc32.update(value)
                account(1L)
            }
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            budget.checkpoint()
            val read = delegate.read(b, off, len)
            if (read > 0) {
                updateCrc(b, off, read)
                account(read.toLong())
            }
            return read
        }

        private fun account(read: Long) {
            bytesRead += read
            budget.accountRead(bytesRead, read)
            if (entry.size >= 0L && bytesRead > entry.size) {
                throw RetryableArchiveFailure(
                    ArchivePayloadRefusalReason.STREAM_DESCRIPTOR_SIZE_MISMATCH,
                )
            }
        }

        fun proof(entry: ZipEntry): LocalEntryProof = LocalEntryProof(
            name = entry.name,
            method = entry.method,
            crc32 = crc32.value,
            compressedSize = entry.compressedSize.takeIf { it >= 0L },
            uncompressedSize = bytesRead,
        )

        private fun updateCrc(bytes: ByteArray, offset: Int, length: Int) {
            crc32.update(bytes, offset, length)
        }
    }

    private class CandidateAdmissionInputStream(
        private val delegate: InputStream,
        private val onAdmit: () -> Unit,
    ) : InputStream() {
        var admitted: Boolean = false
            private set
        private var bytesRead = 0L

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) account(1L)
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = delegate.read(b, off, len)
            if (read > 0) account(read.toLong())
            return read
        }

        private fun account(read: Long) {
            bytesRead += read
            if (!admitted && bytesRead >= MIN_PAYLOAD_BYTES) {
                onAdmit()
                admitted = true
            }
        }
    }

    /**
     * Fixed-memory raw local-header index. Central records are not trusted until their offsets and decoding
     * flags point back to one of these byte-observed headers.
     */
    private class RawLocalHeaderScanner {
        private val active = ArrayList<HeaderCapture>()
        private val completed = ArrayList<RawLocalHeaderProof>()
        private var signatureWindow = 0
        private var startedCandidates = 0

        fun accept(value: Byte, offset: Long) {
            val iterator = active.iterator()
            while (iterator.hasNext()) {
                val capture = iterator.next()
                when (capture.accept(value)) {
                    CaptureState.ACTIVE -> Unit
                    CaptureState.DISCARD -> iterator.remove()
                    CaptureState.COMPLETE -> {
                        completed += capture.proof()
                        iterator.remove()
                    }
                }
            }
            signatureWindow = (signatureWindow ushr 8) or ((value.toInt() and 0xff) shl 24)
            if (signatureWindow == ZIP_LOCAL_HEADER) {
                startedCandidates++
                if (startedCandidates > MAX_RAW_LOCAL_HEADER_CANDIDATES) {
                    throw RetryableArchiveFailure(ArchivePayloadRefusalReason.RESOURCE_REFUSED)
                }
                active += HeaderCapture(offset - 3L)
            }
        }

        fun proofs(): List<RawLocalHeaderProof> = completed

        private class HeaderCapture(
            private val offset: Long,
        ) {
            private val bytes = ByteArray(LOCAL_FIXED_BYTES + MAX_ENTRY_NAME_BYTES)
            private var count = ZIP_SIGNATURE_BYTES
            private var target = LOCAL_FIXED_BYTES

            init {
                bytes[0] = 0x50.toByte()
                bytes[1] = 0x4b.toByte()
                bytes[2] = 0x03.toByte()
                bytes[3] = 0x04.toByte()
            }

            fun accept(value: Byte): CaptureState {
                if (count >= bytes.size) return CaptureState.DISCARD
                bytes[count++] = value
                if (count == LOCAL_FIXED_BYTES) {
                    val nameBytes = leU16(bytes, 26)
                    if (nameBytes < 0 || nameBytes > MAX_ENTRY_NAME_BYTES) {
                        return CaptureState.DISCARD
                    }
                    target = LOCAL_FIXED_BYTES + nameBytes
                }
                return if (count >= target) CaptureState.COMPLETE else CaptureState.ACTIVE
            }

            fun proof(): RawLocalHeaderProof = RawLocalHeaderProof(
                offset = offset,
                flags = leU16(bytes, 6),
                method = leU16(bytes, 8),
                crc32 = leU32(bytes, 14).toLong() and 0xffff_ffffL,
                compressedSize = leU32(bytes, 18).toLong() and 0xffff_ffffL,
                uncompressedSize = leU32(bytes, 22).toLong() and 0xffff_ffffL,
                extraLength = leU16(bytes, 28),
                name = bytes.copyOfRange(LOCAL_FIXED_BYTES, target),
            )
        }

        private enum class CaptureState {
            ACTIVE,
            COMPLETE,
            DISCARD,
        }
    }

    /** Fixed-memory raw tail + generation digest. */
    private class TailCaptureInputStream(
        private val delegate: InputStream,
        private val onRawRead: (totalBytes: Long) -> Unit = {},
    ) : InputStream() {
        private val ring = ByteArray(MAX_ARCHIVE_PROOF_TAIL_BYTES)
        private val localHeaderScanner = RawLocalHeaderScanner()
        private val digest = MessageDigest.getInstance("SHA-256")
        private var start = 0
        private var size = 0
        private var total = 0L

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) {
                capture(value.toByte())
                onRawRead(total)
            }
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = delegate.read(b, off, len)
            if (read > 0) {
                digest.update(b, off, read)
                for (index in off until off + read) {
                    localHeaderScanner.accept(b[index], total + index - off)
                    captureTail(b[index])
                }
                total += read
                onRawRead(total)
            }
            return read
        }

        fun proveCentralDirectory(localEntries: List<LocalEntryProof>): CentralDirectoryProof {
            val tail = snapshot()
            val firstEocdCandidate = (tail.size - MAX_EOCD_TAIL_BYTES).coerceAtLeast(0)
            for (at in tail.size - EOCD_MIN_BYTES downTo firstEocdCandidate) {
                if (leU32(tail, at) != ZIP_EMPTY_ARCHIVE) continue
                val commentBytes = leU16(tail, at + 20)
                if (at + EOCD_MIN_BYTES + commentBytes != tail.size) continue
                val disk = leU16(tail, at + 4)
                val centralDisk = leU16(tail, at + 6)
                val entriesOnDisk = leU16(tail, at + 8)
                val totalEntries = leU16(tail, at + 10)
                if (disk != 0 || centralDisk != 0 || entriesOnDisk != totalEntries) continue
                if (totalEntries == ZIP64_U16) {
                    return CentralDirectoryProof.RESOURCE_REFUSED
                }
                if (totalEntries != localEntries.size) continue
                val centralBytes = leU32(tail, at + 12).toLong() and 0xffff_ffffL
                val centralOffset = leU32(tail, at + 16).toLong() and 0xffff_ffffL
                if (centralBytes == ZIP64_U32 || centralOffset == ZIP64_U32) {
                    return CentralDirectoryProof.RESOURCE_REFUSED
                }
                val eocdOffset = total - tail.size + at
                if (centralOffset > Long.MAX_VALUE - centralBytes) continue
                if (centralOffset + centralBytes != eocdOffset) continue
                if (totalEntries == 0) {
                    if (centralBytes == 0L && centralOffset == eocdOffset) {
                        return CentralDirectoryProof.COMPLETE
                    }
                    continue
                }
                if (centralBytes == 0L) continue
                if (centralBytes > MAX_CENTRAL_DIRECTORY_BYTES) {
                    return CentralDirectoryProof.RESOURCE_REFUSED
                }
                val tailOffset = total - tail.size
                if (centralOffset < tailOffset) {
                    return CentralDirectoryProof.RESOURCE_REFUSED
                }
                val centralAt = (centralOffset - tailOffset).toInt()
                if (centralAt < 0 || centralAt > at) continue
                val centralRecords = ArrayList<CentralEntryRecord>(totalEntries)
                var cursor = centralAt
                var parsedEntries = 0
                while (cursor < at && parsedEntries < totalEntries) {
                    if (leU32(tail, cursor) != ZIP_CENTRAL_HEADER) break
                    if (cursor + CEN_FIXED_BYTES > at) break
                    val nameBytes = leU16(tail, cursor + 28)
                    val extraBytes = leU16(tail, cursor + 30)
                    val entryCommentBytes = leU16(tail, cursor + 32)
                    val diskStart = leU16(tail, cursor + 34)
                    val flags = leU16(tail, cursor + 8)
                    val method = leU16(tail, cursor + 10)
                    val crc32 = leU32(tail, cursor + 16).toLong() and 0xffff_ffffL
                    val compressedSize = leU32(tail, cursor + 20).toLong() and 0xffff_ffffL
                    val uncompressedSize = leU32(tail, cursor + 24).toLong() and 0xffff_ffffL
                    val localHeaderOffset = leU32(tail, cursor + 42).toLong() and 0xffff_ffffL
                    if (
                        compressedSize == ZIP64_U32 ||
                        uncompressedSize == ZIP64_U32 ||
                        localHeaderOffset == ZIP64_U32 ||
                        diskStart == ZIP64_U16
                    ) {
                        return CentralDirectoryProof.RESOURCE_REFUSED
                    }
                    if (
                        nameBytes < 0 || nameBytes > MAX_ENTRY_NAME_BYTES ||
                        extraBytes < 0 || entryCommentBytes < 0 || diskStart != 0 ||
                        localHeaderOffset >= centralOffset
                    ) {
                        break
                    }
                    val recordBytes = CEN_FIXED_BYTES.toLong() +
                        nameBytes.toLong() + extraBytes.toLong() + entryCommentBytes.toLong()
                    if (recordBytes > Int.MAX_VALUE || cursor + recordBytes.toInt() > at) break
                    val nameAt = cursor + CEN_FIXED_BYTES
                    val centralName = tail.copyOfRange(nameAt, nameAt + nameBytes)
                    centralRecords += CentralEntryRecord(
                        flags = flags,
                        method = method,
                        crc32 = crc32,
                        compressedSize = compressedSize,
                        uncompressedSize = uncompressedSize,
                        localHeaderOffset = localHeaderOffset,
                        name = centralName,
                    )
                    cursor += recordBytes.toInt()
                    parsedEntries++
                }
                if (
                    parsedEntries == totalEntries &&
                    cursor == at &&
                    proveLocalEntryChain(
                        centralRecords = centralRecords,
                        localEntries = localEntries,
                        rawHeaders = localHeaderScanner.proofs(),
                        centralOffset = centralOffset,
                    )
                ) {
                    return CentralDirectoryProof.COMPLETE
                }
            }
            return CentralDirectoryProof.MALFORMED
        }

        private fun proveLocalEntryChain(
            centralRecords: List<CentralEntryRecord>,
            localEntries: List<LocalEntryProof>,
            rawHeaders: List<RawLocalHeaderProof>,
            centralOffset: Long,
        ): Boolean {
            if (centralRecords.size != localEntries.size) return false
            val physical = centralRecords.sortedBy(CentralEntryRecord::localHeaderOffset)
            if (physical.firstOrNull()?.localHeaderOffset != 0L) return false
            physical.forEachIndexed { index, central ->
                val local = localEntries[index]
                val raw = rawHeaders.firstOrNull { it.offset == central.localHeaderOffset }
                    ?: return false
                val expectedName = local.name.toByteArray(Charsets.UTF_8)
                if (
                    !central.name.contentEquals(expectedName) ||
                    local.method != central.method ||
                    local.crc32 != central.crc32 ||
                    local.compressedSize != central.compressedSize ||
                    local.uncompressedSize != central.uncompressedSize ||
                    raw.flags != central.flags ||
                    raw.method != central.method ||
                    !raw.name.contentEquals(central.name) ||
                    raw.extraLength < 0
                ) {
                    return false
                }
                val usesDescriptor = raw.flags and DATA_DESCRIPTOR_FLAG != 0
                if (
                    !usesDescriptor &&
                    (
                        raw.crc32 != central.crc32 ||
                            raw.compressedSize != central.compressedSize ||
                            raw.uncompressedSize != central.uncompressedSize
                        )
                ) {
                    return false
                }
                val dataStart = raw.offset +
                    LOCAL_FIXED_BYTES + raw.name.size.toLong() + raw.extraLength.toLong()
                if (dataStart < raw.offset || dataStart > Long.MAX_VALUE - central.compressedSize) {
                    return false
                }
                val payloadEnd = dataStart + central.compressedSize
                val nextBoundary = physical.getOrNull(index + 1)?.localHeaderOffset ?: centralOffset
                if (nextBoundary < payloadEnd) return false
                val trailerBytes = nextBoundary - payloadEnd
                if (
                    (usesDescriptor && trailerBytes !in setOf(12L, 16L)) ||
                    (!usesDescriptor && trailerBytes != 0L)
                ) {
                    return false
                }
            }
            return true
        }

        fun sha256(): String = digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

        override fun close() = delegate.close()

        private fun capture(value: Byte) {
            digest.update(value)
            localHeaderScanner.accept(value, total)
            captureTail(value)
            total++
        }

        private fun captureTail(value: Byte) {
            if (size < ring.size) {
                ring[(start + size) % ring.size] = value
                size++
            } else {
                ring[start] = value
                start = (start + 1) % ring.size
            }
        }

        private fun snapshot(): ByteArray = ByteArray(size) { index ->
            ring[(start + index) % ring.size]
        }

    }

    private companion object {
        const val MAX_ENTRIES_EXAMINED = 512
        const val MAX_ENTRY_NAME_BYTES = 512
        const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
        const val MAX_RATIO = 200L
        const val MIN_RATIO_CHECK_BYTES = 1024L * 1024
        const val MAX_CANDIDATES_HASHED = 8
        const val MIN_PAYLOAD_BYTES = 1024L
        const val MAX_AGGREGATE_RATIO = 200L
        const val BUFFER_BYTES = 32 * 1024
        const val ZIP_SIGNATURE_BYTES = 4
        const val EOCD_MIN_BYTES = 22
        const val MAX_EOCD_TAIL_BYTES = EOCD_MIN_BYTES + 65_535
        const val MAX_CENTRAL_DIRECTORY_BYTES = 4 * 1024 * 1024
        const val MAX_ARCHIVE_PROOF_TAIL_BYTES =
            MAX_CENTRAL_DIRECTORY_BYTES + MAX_EOCD_TAIL_BYTES
        const val MAX_RAW_LOCAL_HEADER_CANDIDATES = 2_048
        const val LOCAL_FIXED_BYTES = 30
        const val CEN_FIXED_BYTES = 46
        const val DATA_DESCRIPTOR_FLAG = 1 shl 3
        const val ZIP_LOCAL_HEADER = 0x04034b50
        const val ZIP_CENTRAL_HEADER = 0x02014b50
        const val ZIP_EMPTY_ARCHIVE = 0x06054b50
        const val ZIP_SPANNED_ARCHIVE = 0x08074b50
        const val ZIP64_U16 = 0xffff
        const val ZIP64_U32 = 0xffff_ffffL

        val UNSUPPORTED_CONTAINERS = setOf("7z", "rar", "tar", "gz", "bz2", "xz")
        val JUNK_EXTENSIONS = setOf(
            "txt", "nfo", "diz", "url", "sfv", "md5", "sha1", "crc", "log",
            "htm", "html", "jpg", "jpeg", "png", "gif", "bmp", "ico", "ini",
            "cfg", "bat", "cmd", "exe", "dll", "ds_store", "db",
        )
        val JUNK_BASENAMES = setOf("thumbs.db", "desktop.ini")

        fun saturatingMultiply(left: Long, right: Long): Long =
            if (left <= 0L || right <= 0L) 0L
            else if (left > Long.MAX_VALUE / right) Long.MAX_VALUE
            else left * right

        fun leU16(bytes: ByteArray, at: Int): Int {
            if (at < 0 || at + 2 > bytes.size) return -1
            return (bytes[at].toInt() and 0xff) or
                ((bytes[at + 1].toInt() and 0xff) shl 8)
        }

        fun leU32(bytes: ByteArray, at: Int): Int {
            if (at < 0 || at + 4 > bytes.size) return -1
            return (bytes[at].toInt() and 0xff) or
                ((bytes[at + 1].toInt() and 0xff) shl 8) or
                ((bytes[at + 2].toInt() and 0xff) shl 16) or
                ((bytes[at + 3].toInt() and 0xff) shl 24)
        }
    }
}

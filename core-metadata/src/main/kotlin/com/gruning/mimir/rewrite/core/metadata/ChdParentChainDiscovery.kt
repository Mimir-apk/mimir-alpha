package com.gruning.mimir.rewrite.core.metadata

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Locates one CHD-shaped resident's bytes. Names and paths locate bytes; they are never identity. */
internal data class ChdChainResident(
    val entryKey: String,
    val sourceRootUri: String,
    val relativePath: String,
    val documentUri: String?,
    val managedPath: String?,
)

internal sealed interface ChdResidentHeaderRead {
    /** The resident carries a parseable CHD header claim (still unverified until recomputed). */
    data class Parsed(val header: ChdHeaderEvidence) : ChdResidentHeaderRead

    /** Readable bytes that are not a CHD header: never a candidate, never a blocker. */
    data object NotChd : ChdResidentHeaderRead

    /** The bytes could not be read, so the resident's claim is unknown until a later retry. */
    data object Unreadable : ChdResidentHeaderRead
}

internal data class VerifiedChdChainNode(
    val resident: ChdChainResident,
    val header: ChdHeaderEvidence,
)

/** One fully settled structure. Its resident locations stay process-local and are never durable identity. */
internal data class VerifiedChdChainSelection(
    val identity: VerifiedChdIdentity,
    val nodesRootFirst: List<VerifiedChdChainNode>,
)

/**
 * Digest-only parent discovery for differential CHDs. Candidate parents are selected exclusively by the
 * child header's parent overall SHA-1 against every AVAILABLE CHD-shaped resident's own header claim —
 * cross-root, primary and non-primary alike. Every complete root-to-target path is then independently
 * recomputed by [ChdChainPayloadVerifier]; a header claim alone never identifies.
 *
 * Convergence: fully verified chains with the same per-node digest structure are one identity, however
 * many duplicate-equivalent copies exist. Two verified chains with different structures are ambiguous
 * evidence and fail closed as [ContainerIdentityReason.AMBIGUOUS]. Branches that can never produce a
 * policy-valid verified chain (cycles, over-depth tails, open-refuted candidates) kill only themselves,
 * and an unreadable resident unrelated to a verified chain never invalidates it.
 *
 * Budgets are enforced before unbounded work: at most [MAX_RESIDENTS] residents enter one sweep catalog
 * (the inventory is fetched with limit+1 so overflow is detected, not truncated silently), at most
 * [MAX_CANDIDATES_PER_DIGEST] candidates may claim a digest the target actually needs, at most
 * [MAX_BRANCHES] ancestry branches are enumerated, and chains stay within
 * [ChdChainPayloadVerifier.MAX_CHAIN_DEPTH]. Exceeding any budget refuses retryably; it never mints or
 * denies identity durably.
 *
 * One [ChdVerificationBudget] spans every catalog header read, enumeration step, and verifier branch for
 * a single [resolve] call. A deadline-refused or partial catalog is never cached.
 *
 * Native descriptor lifetime: every node's [NativeSeekablePath] holder stays open until the native chain
 * session has closed; holders then close in reverse acquisition order on success, failure, timeout and
 * cancellation alike.
 */
internal class ChdParentChainDiscovery(
    private val inventory: suspend () -> List<ChdChainResident>,
    private val headerReader: suspend (ChdChainResident) -> ChdResidentHeaderRead,
    private val holderOpener: (ChdChainResident) -> NativeSeekablePath?,
    private val verifier: suspend (List<ChdVerificationNode>, ChdVerificationBudget) -> ChdChainVerification = {
            nodes,
            budget,
        ->
        ChdChainPayloadVerifier.verifyChain(nodes, budget = budget)
    },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val catalogMutex = Mutex()
    private var builtCatalog: Catalog? = null

    suspend fun resolve(
        target: ChdChainResident,
        targetHeader: ChdHeaderEvidence,
        budget: ChdVerificationBudget = ChdVerificationBudget.forResolve(nanoTime = nanoTime),
    ): ContainerIdentityResult<VerifiedChdIdentity> =
        when (val selected = resolveVerifiedChain(target, targetHeader, budget)) {
            is ContainerIdentityResult.Identified ->
                ContainerIdentityResult.Identified(selected.value.identity)
            is ContainerIdentityResult.PolicyAbstain -> selected
            is ContainerIdentityResult.NeedsReview -> selected
            is ContainerIdentityResult.RetryableFailure -> selected
        }

    /**
     * Settles the unique verified structure but does not retain native paths. An RA caller must reopen and
     * reverify [VerifiedChdChainSelection.nodesRootFirst] before borrowing it for hashing.
     */
    suspend fun resolveVerifiedChain(
        target: ChdChainResident,
        targetHeader: ChdHeaderEvidence,
        budget: ChdVerificationBudget = ChdVerificationBudget.forResolve(nanoTime = nanoTime),
    ): ContainerIdentityResult<VerifiedChdChainSelection> {
        val requiredDigest = requireNotNull(targetHeader.parentSha1) {
            "standalone CHDs verify without discovery"
        }
        currentCoroutineContext().ensureActive()
        if (!budget.hasAdmissionFloor()) {
            return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED)
        }
        val catalog = when (val built = catalog(budget)) {
            Catalog.OverResidentBudget ->
                return ContainerIdentityResult.RetryableFailure(
                    ContainerIdentityReason.RESOURCE_REFUSED,
                )
            Catalog.BudgetRefused ->
                return ContainerIdentityResult.RetryableFailure(
                    ContainerIdentityReason.RESOURCE_REFUSED,
                )
            is Catalog.Ready -> built
        }
        val enumeration = Enumeration(catalog, target, budget)
        enumeration.descend(
            pathRootFirst = emptyList(),
            requiredDigest = requiredDigest.lowercase(),
            digestsOnPath = setOf(targetHeader.combinedSha1.lowercase()),
        )
        if (enumeration.overBudget) {
            return ContainerIdentityResult.RetryableFailure(
                ContainerIdentityReason.RESOURCE_REFUSED,
            )
        }
        if (enumeration.completePaths.isEmpty()) {
            return when {
                catalog.unreadableCount > 0 ->
                    ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.IO_RETRYABLE)
                enumeration.missingParent ->
                    ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.PARENT_REQUIRED)
                enumeration.overDepth ->
                    ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED)
                else -> ContainerIdentityResult.NeedsReview(ContainerIdentityReason.MALFORMED)
            }
        }
        val outcome = verifyEnumeratedPaths(enumeration.completePaths, target, targetHeader, budget)
        // An unread resident may hold the payload a policy refusal was judged without; stay retryable.
        return if (outcome is ContainerIdentityResult.PolicyAbstain && catalog.unreadableCount > 0) {
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.IO_RETRYABLE)
        } else {
            outcome
        }
    }

    /**
     * Verifies structure groups until exactly one structure survives. Groups and member paths iterate in
     * a deterministic order so equal libraries resolve equally on every device.
     */
    private suspend fun verifyEnumeratedPaths(
        completePaths: List<List<CatalogEntry>>,
        target: ChdChainResident,
        targetHeader: ChdHeaderEvidence,
        budget: ChdVerificationBudget,
    ): ContainerIdentityResult<VerifiedChdChainSelection> {
        val groups = completePaths
            .groupBy { path -> path.joinToString(separator = "|") { it.header.combinedSha1.lowercase() } }
            .entries
            .sortedWith(compareBy({ it.value.first().size }, { it.key }))
        val deadFiles = HashSet<String>()
        val deadLinks = HashSet<Pair<String, String>>()
        val failures = ArrayList<ContainerIdentityResult<Nothing>>()
        var verified: VerifiedChdChainSelection? = null
        groups.forEachIndexed { groupIndex, (_, paths) ->
            if (!budget.hasAdmissionFloor()) {
                if (verified != null) {
                    return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED)
                }
                return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED)
            }
            var groupSelection: VerifiedChdChainSelection? = null
            for (path in paths.sortedWith(::compareCatalogPaths)) {
                currentCoroutineContext().ensureActive()
                if (!budget.hasAdmissionFloor()) {
                    if (verified != null || groupSelection != null) {
                        return ContainerIdentityResult.RetryableFailure(
                            ContainerIdentityReason.RESOURCE_REFUSED,
                        )
                    }
                    return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED)
                }
                val nodeKeys = path.map(CatalogEntry::key) + target.entryKey
                if (nodeKeys.any(deadFiles::contains)) continue
                if (nodeKeys.zipWithNext().any(deadLinks::contains)) continue
                val attempt = verifyPath(path, target, targetHeader, budget)
                if (attempt.budgetExhausted) {
                    if (verified != null || groupSelection != null) {
                        return ContainerIdentityResult.RetryableFailure(
                            ContainerIdentityReason.RESOURCE_REFUSED,
                        )
                    }
                    return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED)
                }
                val failure: ContainerIdentityResult<Nothing>? = when (val result = attempt.result) {
                    is ContainerIdentityResult.Identified -> {
                        groupSelection = VerifiedChdChainSelection(
                            identity = result.value,
                            nodesRootFirst = path.map {
                                VerifiedChdChainNode(it.resident, it.header)
                            } + VerifiedChdChainNode(target, targetHeader),
                        )
                        null
                    }
                    is ContainerIdentityResult.PolicyAbstain -> result
                    is ContainerIdentityResult.NeedsReview -> result
                    is ContainerIdentityResult.RetryableFailure -> result
                }
                if (failure == null) break
                failures += failure
                pruneRefutedNodes(
                    result = failure,
                    openFailureNodeIndex = attempt.openFailureNodeIndex,
                    nodeKeys = nodeKeys,
                    deadFiles = deadFiles,
                    deadLinks = deadLinks,
                )
            }
            if (groupSelection != null) {
                if (verified != null) {
                    return ContainerIdentityResult.NeedsReview(ContainerIdentityReason.AMBIGUOUS)
                }
                verified = groupSelection
                if (!budget.hasAdmissionFloor() && groupIndex < groups.lastIndex) {
                    return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED)
                }
            }
        }
        verified?.let { return ContainerIdentityResult.Identified(it) }
        return collapseVerificationFailures(failures)
    }

    /**
     * The open-failure index prunes work other branches would repeat. A refusal at the first node, or a
     * non-malformed refusal anywhere, condemns that file for this resolution; a MALFORMED refusal at a
     * deeper node may be libchdr refuting the predecessor pairing rather than the file itself, so only
     * the exact parent→child link dies.
     */
    private fun pruneRefutedNodes(
        result: ContainerIdentityResult<Nothing>,
        openFailureNodeIndex: Int?,
        nodeKeys: List<String>,
        deadFiles: MutableSet<String>,
        deadLinks: MutableSet<Pair<String, String>>,
    ) {
        val failedAt = openFailureNodeIndex?.takeIf { it in nodeKeys.indices } ?: return
        val malformed = when (result) {
            is ContainerIdentityResult.RetryableFailure -> result.reason == ContainerIdentityReason.MALFORMED
            is ContainerIdentityResult.NeedsReview -> result.reason == ContainerIdentityReason.MALFORMED
            is ContainerIdentityResult.PolicyAbstain -> false
            is ContainerIdentityResult.Identified -> false
        }
        if (malformed && failedAt > 0) {
            deadLinks += nodeKeys[failedAt - 1] to nodeKeys[failedAt]
        } else {
            deadFiles += nodeKeys[failedAt]
        }
    }

    /** No structure verified: keep the most retryable evidence, and abstain only when policy refused every path. */
    private fun collapseVerificationFailures(
        failures: List<ContainerIdentityResult<Nothing>>,
    ): ContainerIdentityResult<VerifiedChdChainSelection> {
        check(failures.isNotEmpty()) { "an enumerated path must verify or fail" }
        if (failures.all { it is ContainerIdentityResult.PolicyAbstain }) {
            return ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.UNSUPPORTED_CONTAINER)
        }
        val reasons = failures.mapNotNull { (it as? ContainerIdentityResult.RetryableFailure)?.reason }
        val reason = listOf(
            ContainerIdentityReason.IO_RETRYABLE,
            ContainerIdentityReason.SEEK_UNAVAILABLE,
            ContainerIdentityReason.PARENT_REQUIRED,
            ContainerIdentityReason.RESOURCE_REFUSED,
        ).firstOrNull(reasons::contains) ?: ContainerIdentityReason.MALFORMED
        return ContainerIdentityResult.RetryableFailure(reason)
    }

    /**
     * One bounded native attempt. Holders open root-to-target and every one of them outlives the native
     * session ([verifier] closes native state before returning or throwing); the reversed `finally` walk
     * then releases descriptors on every completion path, including cancellation.
     */
    private suspend fun verifyPath(
        path: List<CatalogEntry>,
        target: ChdChainResident,
        targetHeader: ChdHeaderEvidence,
        budget: ChdVerificationBudget,
    ): ChdChainVerification {
        if (!budget.hasAdmissionFloor()) {
            return ChdChainVerification(
                result = ContainerIdentityResult.RetryableFailure(
                    ContainerIdentityReason.RESOURCE_REFUSED,
                ),
                budgetExhausted = true,
            )
        }
        val orderedNodes = path.map { it.resident to it.header } + (target to targetHeader)
        val holders = ArrayList<NativeSeekablePath>(orderedNodes.size)
        try {
            val verificationNodes = ArrayList<ChdVerificationNode>(orderedNodes.size)
            orderedNodes.forEach { (resident, header) ->
                currentCoroutineContext().ensureActive()
                if (!budget.hasAdmissionFloor()) {
                    return ChdChainVerification(
                        result = ContainerIdentityResult.RetryableFailure(
                            ContainerIdentityReason.RESOURCE_REFUSED,
                        ),
                        openFailureNodeIndex = holders.size,
                        budgetExhausted = true,
                    )
                }
                val holder = holderOpener(resident)
                if (budget.isExhausted()) {
                    holder?.let { runCatching { it.close() } }
                    return ChdChainVerification(
                        result = ContainerIdentityResult.RetryableFailure(
                            ContainerIdentityReason.RESOURCE_REFUSED,
                        ),
                        budgetExhausted = true,
                    )
                }
                if (holder == null) {
                    return ChdChainVerification(
                        result = ContainerIdentityResult.RetryableFailure(
                            ContainerIdentityReason.IO_RETRYABLE,
                        ),
                        openFailureNodeIndex = holders.size,
                    )
                }
                holders += holder
                verificationNodes += ChdVerificationNode(holder.path, header)
            }
            return verifier(verificationNodes, budget)
        } finally {
            holders.indices.reversed().forEach { index ->
                runCatching { holders[index].close() }
            }
        }
    }

    private suspend fun catalog(budget: ChdVerificationBudget): Catalog = catalogMutex.withLock {
        builtCatalog?.let { return@withLock it }
        if (!budget.hasAdmissionFloor()) {
            return@withLock Catalog.BudgetRefused
        }
        val residents = inventory()
        if (!budget.hasAdmissionFloor()) {
            return@withLock Catalog.BudgetRefused
        }
        if (residents.size > MAX_RESIDENTS) {
            val over = Catalog.OverResidentBudget
            builtCatalog = over
            return@withLock over
        }
        val entries = ArrayList<CatalogEntry>(residents.size)
        var unreadable = 0
        for (resident in residents) {
            currentCoroutineContext().ensureActive()
            if (!budget.hasAdmissionFloor()) {
                return@withLock Catalog.BudgetRefused
            }
            when (val read = headerReader(resident)) {
                is ChdResidentHeaderRead.Parsed -> entries += CatalogEntry(resident, read.header)
                ChdResidentHeaderRead.NotChd -> Unit
                ChdResidentHeaderRead.Unreadable -> unreadable++
            }
            if (!budget.hasAdmissionFloor()) {
                return@withLock Catalog.BudgetRefused
            }
        }
        if (!budget.hasAdmissionFloor()) {
            return@withLock Catalog.BudgetRefused
        }
        val ready = Catalog.Ready(
            byDigest = entries.groupBy { it.header.combinedSha1.lowercase() },
            unreadableCount = unreadable,
        )
        builtCatalog = ready
        ready
    }

    private class Enumeration(
        private val catalog: Catalog.Ready,
        private val target: ChdChainResident,
        private val budget: ChdVerificationBudget,
    ) {
        val completePaths = ArrayList<List<CatalogEntry>>()
        var missingParent = false
        var overDepth = false
        var overBudget = false
        private var branches = 0

        /** Walks ancestry child-side up; [pathRootFirst] accumulates candidates nearest-root first. */
        fun descend(
            pathRootFirst: List<CatalogEntry>,
            requiredDigest: String,
            digestsOnPath: Set<String>,
        ) {
            if (overBudget) return
            if (!budget.hasAdmissionFloor()) {
                overBudget = true
                return
            }
            if (requiredDigest in digestsOnPath) {
                // Every candidate for an already-visited digest revisits it: the whole level is a cycle.
                endBranch()
                return
            }
            val candidates = catalog.byDigest[requiredDigest].orEmpty()
                .filter { it.resident.entryKey != target.entryKey }
            if (candidates.size > MAX_CANDIDATES_PER_DIGEST) {
                overBudget = true
                return
            }
            if (candidates.isEmpty()) {
                missingParent = true
                endBranch()
                return
            }
            candidates.sortedWith(CATALOG_ENTRY_COMPARATOR).forEach { candidate ->
                if (overBudget) return
                if (!budget.hasAdmissionFloor()) {
                    overBudget = true
                    return
                }
                val parentDigest = candidate.header.parentSha1?.lowercase()
                when {
                    parentDigest == null -> {
                        completePaths.add(listOf(candidate) + pathRootFirst)
                        endBranch()
                    }
                    // The target itself is one chain node, so ancestors stop one short of the cap.
                    pathRootFirst.size + 1 >= ChdChainPayloadVerifier.MAX_CHAIN_DEPTH - 1 -> {
                        overDepth = true
                        endBranch()
                    }
                    else -> descend(
                        pathRootFirst = listOf(candidate) + pathRootFirst,
                        requiredDigest = parentDigest,
                        digestsOnPath = digestsOnPath + candidate.header.combinedSha1.lowercase(),
                    )
                }
            }
        }

        private fun endBranch() {
            branches++
            if (branches > MAX_BRANCHES) overBudget = true
        }
    }

    private sealed interface Catalog {
        data object OverResidentBudget : Catalog

        /** Budget expired before every resident header was read — never cached. */
        data object BudgetRefused : Catalog

        data class Ready(
            val byDigest: Map<String, List<CatalogEntry>>,
            val unreadableCount: Int,
        ) : Catalog
    }

    private data class CatalogEntry(
        val resident: ChdChainResident,
        val header: ChdHeaderEvidence,
    ) {
        val key: String get() = resident.entryKey
    }

    companion object {
        private val CATALOG_ENTRY_COMPARATOR =
            compareBy<CatalogEntry>(
                { it.resident.sourceRootUri },
                { it.resident.relativePath },
                CatalogEntry::key,
            )

        private fun compareCatalogPaths(
            left: List<CatalogEntry>,
            right: List<CatalogEntry>,
        ): Int {
            val commonSize = minOf(left.size, right.size)
            for (index in 0 until commonSize) {
                val compared = CATALOG_ENTRY_COMPARATOR.compare(left[index], right[index])
                if (compared != 0) return compared
            }
            return left.size.compareTo(right.size)
        }

        const val MAX_RESIDENTS = 4_096
        const val MAX_CANDIDATES_PER_DIGEST = 8
        const val MAX_BRANCHES = 16
    }
}

package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.storage.ProcessSourceRootMutations
import com.gruning.mimir.rewrite.core.storage.SourceMutationGeneration
import com.gruning.mimir.rewrite.core.storage.SourceRootMutationCoordinator
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Counts-only coverage safe to expose without turning missing atom digests into an acquisition list. */
data class MameAuditCoverage(
    val nodesEvaluated: Int,
    val requiredRomAtoms: Int,
    val requiredDiskAtoms: Int,
    val declaredRomRequirements: Int,
    val declaredDiskRequirements: Int,
    val baddumpRequirements: Int,
    val missingRomAtoms: Int,
    val missingDiskAtoms: Int,
)

/** Stable public mirror of the internal dependency evaluator's refusal vocabulary. */
enum class MameAuditReason {
    CATALOG_UNAVAILABLE,
    CATALOG_IO_RETRYABLE,
    CATALOG_MALFORMED,
    CATALOG_RESOURCE_REFUSED,
    CATALOG_BLOCKED,
    INVENTORY_INCOMPLETE,
    MALFORMED_CONTAINER,
    AMBIGUOUS_CONTAINER,
    RESOURCE_REFUSED,
    ROOT_MISMATCH,
    MACHINE_UNRESOLVED,
    NON_RUNNABLE_TARGET,
    BIOS_SELECTION_REQUIRED,
    UNKNOWN_BIOS_SELECTION,
    CHD_PARENT_REQUIRED,
    DISK_UNVERIFIED,
    UNSUPPORTED_MEMBER_SIZE,
    WRITABLE_DISK_UNSUPPORTED,
    DEPENDENCY_CYCLE,
    DEPENDENCY_BUDGET,
}

/**
 * A MAME dependency audit is metadata evidence only. Proven never means launchable; NotProven never means
 * the supplied files are wrong or missing.
 */
sealed interface MameDependencyAuditResult {
    data class Proven(val coverage: MameAuditCoverage) : MameDependencyAuditResult
    data class NotProven(val coverage: MameAuditCoverage) : MameDependencyAuditResult
    data class NeedsReview(val reason: MameAuditReason) : MameDependencyAuditResult
    data class Retryable(val reason: MameAuditReason) : MameDependencyAuditResult
    data class Blocked(val reason: MameAuditReason) : MameDependencyAuditResult
    data object Stale : MameDependencyAuditResult
}

/**
 * Production entry point for one exact selected MAME resident and one exact current source root.
 *
 * Machine authority is re-derived from the selected resident on every call. A caller cannot inject a
 * machine name or source root, and no result is persisted or connected to launch readiness.
 */
class MameRootDependencyAudit(
    private val dao: GameDao,
    private val opener: ContentOpener,
    payloadProvider: DatAssetProvider,
    private val sourceMutations: SourceRootMutationCoordinator =
        ProcessSourceRootMutations.coordinator,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val payloadCatalog = MamePayloadCatalog(payloadProvider)
    private val dependencyCatalog = MameDependencyCatalog(payloadProvider)

    suspend fun audit(
        targetEntryKey: String,
        selectedBios: Map<String, String> = emptyMap(),
        absoluteDeadlineNanos: Long? = null,
    ): MameDependencyAuditResult = withContext(Dispatchers.IO) {
        if (targetEntryKey.isBlank()) {
            return@withContext MameDependencyAuditResult.NeedsReview(
                MameAuditReason.MACHINE_UNRESOLVED,
            )
        }
        val selected = sourceMutations.snapshotSourceMutation {
            val target = dao.byEntryKey(targetEntryKey)
            if (
                target == null ||
                target.availability != AVAILABLE ||
                target.platformId != ARCADE ||
                target.sourceRootUri.isBlank()
            ) {
                null
            } else {
                MameAuditSnapshot(
                    target = target,
                    residents = dao.availableArcadeResidentsForSource(
                        rootUri = target.sourceRootUri,
                        limit = MAX_INVENTORY_RESIDENTS + 1,
                    ),
                )
            }
        }
        val snapshot = selected.value
        if (sourceMutations.currentSourceMutationGeneration() != selected.generation) {
            return@withContext MameDependencyAuditResult.Stale
        }
        if (snapshot == null) {
            return@withContext MameDependencyAuditResult.NeedsReview(
                MameAuditReason.MACHINE_UNRESOLVED,
            )
        }
        val deadline = auditDeadline(absoluteDeadlineNanos)
        withContentCancellation { scope ->
            val boundOpener = opener.boundTo(scope)
            val builder = MameRootInventoryBuilder(
                opener = boundOpener,
                setLookup = payloadCatalog,
                memberLookup = payloadCatalog,
                diskLookup = payloadCatalog,
                dependencyCatalog = dependencyCatalog,
                nanoTime = nanoTime,
            )
            val built = builder.build(
                snapshot = snapshot,
                deadlineNanos = deadline,
                preflight = { machineName ->
                    evaluateMamePreflightIfCurrent(
                        sourceMutations = sourceMutations,
                        expectedGeneration = selected.generation,
                    ) {
                        builder.preflight(
                            machineName = machineName,
                            sourceRootUri = snapshot.target.sourceRootUri,
                            selectedBios = selectedBios,
                        )
                    }
                },
            )
            when (built) {
                is MameInventoryBuildResult.Terminal -> {
                    if (sourceMutations.currentSourceMutationGeneration() != selected.generation) {
                        MameDependencyAuditResult.Stale
                    } else {
                        built.result
                    }
                }
                is MameInventoryBuildResult.Ready -> {
                    evaluateMameInventoryIfCurrent(
                        sourceMutations = sourceMutations,
                        expectedGeneration = selected.generation,
                    ) {
                        builder.evaluate(built.observation, selectedBios)
                    }
                }
            }
        }
    }

    private fun auditDeadline(requested: Long?): Long {
        val now = nanoTime()
        val aggregate = MonotonicDeadline.deadlineAfter(now, MAX_AUDIT_NANOS)
        return when {
            requested == null -> aggregate
            !MonotonicDeadline.hasTimeRemaining(requested, now) -> now
            else -> minOf(requested, aggregate)
        }
    }

    companion object {
        const val MAX_INVENTORY_RESIDENTS = 4_096
        const val MAX_CHD_VERIFICATIONS = 8
        const val MAX_AGGREGATE_ROM_BYTES = 8L * 1024 * 1024 * 1024
        const val MAX_AUDIT_NANOS = ChdVerificationBudget.DEFAULT_AGGREGATE_NANOS
        private const val AVAILABLE = "AVAILABLE"
        private const val ARCADE = "arcade"
    }
}

internal suspend fun evaluateMameInventoryIfCurrent(
    sourceMutations: SourceRootMutationCoordinator,
    expectedGeneration: SourceMutationGeneration,
    evaluation: () -> MameDependencyAuditResult,
): MameDependencyAuditResult {
    var evaluated: MameDependencyAuditResult? = null
    val current = sourceMutations.commitIfSourceUnchanged(expectedGeneration) {
        evaluated = evaluation()
    }
    return if (current) requireNotNull(evaluated) else MameDependencyAuditResult.Stale
}

internal suspend fun evaluateMamePreflightIfCurrent(
    sourceMutations: SourceRootMutationCoordinator,
    expectedGeneration: SourceMutationGeneration,
    evaluation: () -> MameDependencyAuditResult?,
): MameDependencyAuditResult? {
    var evaluated: MameDependencyAuditResult? = null
    val current = sourceMutations.commitIfSourceUnchanged(expectedGeneration) {
        evaluated = evaluation()
    }
    return if (current) evaluated else MameDependencyAuditResult.Stale
}

internal data class MameAuditSnapshot(
    val target: GameEntity,
    val residents: List<GameEntity>,
)

internal data class MameUnevaluatedInventory(
    val sourceRootUri: String,
    val machineName: String,
    val inventory: MameRootInventory,
)

internal sealed interface MameInventoryBuildResult {
    data class Ready(
        val observation: MameUnevaluatedInventory,
    ) : MameInventoryBuildResult

    data class Terminal(
        val result: MameDependencyAuditResult,
    ) : MameInventoryBuildResult
}

internal class MameRootInventoryBuilder(
    private val opener: ContentOpener,
    private val setLookup: MameSetLookup,
    private val memberLookup: MameMemberLookup,
    private val diskLookup: MameDiskLookup,
    private val dependencyCatalog: MameDependencyCatalog,
    private val nanoTime: () -> Long,
    private val standaloneChdVerifier: suspend (
        path: String,
        header: ChdHeaderEvidence,
        budget: ChdVerificationBudget,
    ) -> ContainerIdentityResult<VerifiedChdIdentity> = { path, header, budget ->
        ChdPayloadVerifier.verify(path, header, budget = budget)
    },
) {
    private val zipObservations = HashMap<String, MameZipObservation>()
    private val chdHeaders = HashMap<String, ChdResidentHeaderRead>()
    private val chdObservations = HashMap<String, ChdAuditObservation>()
    private var aggregateRomBytes = 0L
    private var chdVerifications = 0

    suspend fun build(
        snapshot: MameAuditSnapshot,
        deadlineNanos: Long,
        preflight: suspend (machineName: String) -> MameDependencyAuditResult? = { null },
    ): MameInventoryBuildResult {
        currentCoroutineContext().ensureActive()
        if (
            snapshot.residents.size > MameRootDependencyAudit.MAX_INVENTORY_RESIDENTS ||
            !MonotonicDeadline.hasTimeRemaining(deadlineNanos, nanoTime())
        ) {
            return MameInventoryBuildResult.Terminal(
                MameDependencyAuditResult.Blocked(MameAuditReason.RESOURCE_REFUSED),
            )
        }
        if (
            snapshot.residents.none { it.entryKey == snapshot.target.entryKey } ||
            snapshot.residents.any {
                it.sourceRootUri != snapshot.target.sourceRootUri ||
                    it.availability != AVAILABLE ||
                    it.platformId != ARCADE
            }
        ) {
            return MameInventoryBuildResult.Terminal(
                MameDependencyAuditResult.Blocked(MameAuditReason.ROOT_MISMATCH),
            )
        }

        val budget = ChdVerificationBudget.forResolve(
            nanoTime = nanoTime,
            absoluteDeadlineNanos = deadlineNanos,
        )
        val chdResidents = snapshot.residents
            .filter { route(it) == ContainerRoutingPolicy.Route.CHD }
            .map(GameEntity::toChdResident)
        val chainDiscovery = ChdParentChainDiscovery(
            inventory = { chdResidents },
            headerReader = { resident -> readChdHeader(resident.toGame(snapshot.residents)) },
            holderOpener = { resident ->
                openNative(resident.documentUri, resident.managedPath)
            },
            verifier = { nodes, sharedBudget ->
                ChdChainPayloadVerifier.verifyChain(nodes, budget = sharedBudget)
            },
            nanoTime = nanoTime,
        )

        val machineName = when (route(snapshot.target)) {
            ContainerRoutingPolicy.Route.MAME_ZIP -> {
                when (val observed = observeZip(snapshot.target, deadlineNanos)) {
                    is ContainerIdentityResult.Identified ->
                        observed.value.exactSetIdentity?.record?.machineName
                            ?: return MameInventoryBuildResult.Terminal(
                                MameDependencyAuditResult.NeedsReview(
                                    MameAuditReason.MACHINE_UNRESOLVED,
                                ),
                            )
                    is ContainerIdentityResult.PolicyAbstain ->
                        return MameInventoryBuildResult.Terminal(targetFailure(observed.reason))
                    is ContainerIdentityResult.NeedsReview ->
                        return MameInventoryBuildResult.Terminal(targetFailure(observed.reason))
                    is ContainerIdentityResult.RetryableFailure ->
                        return MameInventoryBuildResult.Terminal(targetFailure(observed.reason))
                }
            }
            ContainerRoutingPolicy.Route.CHD -> {
                val observation = observeChd(
                    game = snapshot.target,
                    requiredDiskAtoms = null,
                    chainDiscovery = chainDiscovery,
                    budget = budget,
                )
                chdObservations[snapshot.target.entryKey] = observation
                val verified = observation.verified
                    ?: return MameInventoryBuildResult.Terminal(observation.targetFailure())
                when (val classified = diskLookup.classifyDisk(verified.combinedSha1)) {
                    is ContainerIdentityResult.Identified -> classified.value.machineName
                    is ContainerIdentityResult.PolicyAbstain ->
                        return MameInventoryBuildResult.Terminal(targetFailure(classified.reason))
                    is ContainerIdentityResult.NeedsReview ->
                        return MameInventoryBuildResult.Terminal(targetFailure(classified.reason))
                    is ContainerIdentityResult.RetryableFailure ->
                        return MameInventoryBuildResult.Terminal(targetFailure(classified.reason))
                }
            }
            else -> return MameInventoryBuildResult.Terminal(
                MameDependencyAuditResult.NeedsReview(MameAuditReason.MACHINE_UNRESOLVED),
            )
        }

        val machine = dependencyCatalog.machine(machineName)
        preflight(machineName)?.let {
            return MameInventoryBuildResult.Terminal(it)
        }
        val requiredDiskAtoms = machine?.let {
            (listOf(it) + it.deviceDependencies())
                .flatMap { node -> node.diskRequirements() }
                .mapTo(LinkedHashSet()) { requirement -> requirement.atom }
        }

        val romAtoms = LinkedHashSet<MameRomAtom>()
        val diskAtoms = LinkedHashMap<MameDiskAtom, MameObservedDiskState>()
        val failures = LinkedHashSet<MameInventoryFailure>()
        for (resident in snapshot.residents) {
            currentCoroutineContext().ensureActive()
            if (!MonotonicDeadline.hasTimeRemaining(deadlineNanos, nanoTime())) {
                failures += MameInventoryFailure.RESOURCE_REFUSED
                break
            }
            when (route(resident)) {
                ContainerRoutingPolicy.Route.MAME_ZIP -> {
                    when (val observed = observeZip(resident, deadlineNanos)) {
                        is ContainerIdentityResult.Identified -> {
                            if (!admitRomBytes(observed.value.expandedBytes)) {
                                failures += MameInventoryFailure.RESOURCE_REFUSED
                                break
                            }
                            romAtoms += observed.value.romAtoms
                        }
                        is ContainerIdentityResult.PolicyAbstain ->
                            failures += observed.reason.toInventoryFailure()
                        is ContainerIdentityResult.NeedsReview ->
                            failures += observed.reason.toInventoryFailure()
                        is ContainerIdentityResult.RetryableFailure ->
                            failures += observed.reason.toInventoryFailure()
                    }
                }
                ContainerRoutingPolicy.Route.MAME_LOOSE -> {
                    when (val observed = observeLoose(resident, deadlineNanos)) {
                        is ContainerIdentityResult.Identified -> {
                            val value = observed.value
                            if (!admitRomBytes(value.atom.sizeBytes)) {
                                failures += MameInventoryFailure.RESOURCE_REFUSED
                                break
                            }
                            if (value.inCatalog && value.atom.sizeBytes > 0L) {
                                romAtoms += value.atom
                            }
                        }
                        is ContainerIdentityResult.PolicyAbstain ->
                            failures += observed.reason.toInventoryFailure()
                        is ContainerIdentityResult.NeedsReview ->
                            failures += observed.reason.toInventoryFailure()
                        is ContainerIdentityResult.RetryableFailure ->
                            failures += observed.reason.toInventoryFailure()
                    }
                }
                ContainerRoutingPolicy.Route.CHD -> {
                    val observed = chdObservations[resident.entryKey] ?: observeChd(
                        game = resident,
                        requiredDiskAtoms = requiredDiskAtoms,
                        chainDiscovery = chainDiscovery,
                        budget = budget,
                    ).also { chdObservations[resident.entryKey] = it }
                    observed.failure?.let(failures::add)
                    val atom = observed.atom
                    val state = observed.state
                    if (atom != null && state != null) {
                        diskAtoms[atom] = mergeMameDiskState(diskAtoms[atom], state)
                    }
                }
                else -> Unit
            }
        }
        val inventory = MameRootInventory(
            sourceRootKey = snapshot.target.sourceRootUri,
            complete = failures.isEmpty(),
            romAtoms = romAtoms,
            diskAtoms = diskAtoms,
            failures = failures,
        )
        return MameInventoryBuildResult.Ready(
            MameUnevaluatedInventory(
                sourceRootUri = snapshot.target.sourceRootUri,
                machineName = machineName,
                inventory = inventory,
            ),
        )
    }

    internal fun evaluate(
        observation: MameUnevaluatedInventory,
        selectedBios: Map<String, String>,
    ): MameDependencyAuditResult {
        val evaluator = MameDependencyClosureEvaluator(dependencyCatalog)
        val coverage = completeCoverage(
            evaluator = evaluator,
            machineName = observation.machineName,
            sourceRootUri = observation.sourceRootUri,
            selectedBios = selectedBios,
        )
        return when (
            val result = evaluator.evaluate(
                MameClosureRequest(
                    sourceRootKey = observation.sourceRootUri,
                    machineName = observation.machineName,
                    selectedBios = selectedBios,
                    inventory = observation.inventory,
                ),
            )
        ) {
            is MameClosureResult.Satisfied ->
                MameDependencyAuditResult.Proven(result.report.toCoverage())
            is MameClosureResult.Incomplete -> {
                if (coverage == null) {
                    return MameDependencyAuditResult.NeedsReview(
                        MameAuditReason.MACHINE_UNRESOLVED,
                    )
                }
                val missingRom = result.missing.count { it.kind == MameRequirementKind.ROM }
                val missingDisk = result.missing.count { it.kind == MameRequirementKind.DISK }
                MameDependencyAuditResult.NotProven(
                    coverage.copy(
                        missingRomAtoms = missingRom,
                        missingDiskAtoms = missingDisk,
                    ),
                )
            }
            is MameClosureResult.NeedsReview -> result.toPublic()
            is MameClosureResult.Retryable -> result.toPublic()
            is MameClosureResult.PolicyBlocked -> result.toPublic()
        }
    }

    internal fun preflight(
        machineName: String,
        sourceRootUri: String,
        selectedBios: Map<String, String>,
    ): MameDependencyAuditResult? {
        val evaluator = MameDependencyClosureEvaluator(dependencyCatalog)
        val result = evaluator.evaluate(
            MameClosureRequest(
                sourceRootKey = sourceRootUri,
                machineName = machineName,
                selectedBios = selectedBios,
                inventory = MameRootInventory(
                    sourceRootKey = sourceRootUri,
                    complete = true,
                    romAtoms = emptySet(),
                    diskAtoms = emptyMap(),
                ),
            ),
        )
        return when (result) {
            is MameClosureResult.Satisfied,
            is MameClosureResult.Incomplete,
            -> null
            is MameClosureResult.NeedsReview -> result.toPublic()
            is MameClosureResult.Retryable -> result.toPublic()
            is MameClosureResult.PolicyBlocked -> result.toPublic()
        }
    }

    private suspend fun observeZip(
        game: GameEntity,
        deadlineNanos: Long,
    ): ContainerIdentityResult<MameZipObservation> {
        zipObservations[game.entryKey]?.let {
            return ContainerIdentityResult.Identified(it)
        }
        val stream = openStream(game)
            ?: return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.IO_RETRYABLE)
        val result = MameZipIdentityReader.observe(
            input = stream,
            catalog = setLookup,
            nanoTime = nanoTime,
            absoluteDeadlineNanos = deadlineNanos,
        )
        if (result is ContainerIdentityResult.Identified) {
            zipObservations[game.entryKey] = result.value
        }
        return result
    }

    private suspend fun observeLoose(
        game: GameEntity,
        deadlineNanos: Long,
    ): ContainerIdentityResult<MameLooseMemberObservation> {
        val stream = openStream(game)
            ?: return ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.IO_RETRYABLE)
        return MameLooseMemberReader.observe(
            input = stream,
            catalog = memberLookup,
            nanoTime = nanoTime,
            absoluteDeadlineNanos = deadlineNanos,
        )
    }

    private suspend fun observeChd(
        game: GameEntity,
        requiredDiskAtoms: Set<MameDiskAtom>?,
        chainDiscovery: ChdParentChainDiscovery,
        budget: ChdVerificationBudget,
    ): ChdAuditObservation {
        val headerRead = readChdHeader(game)
        val header = when (headerRead) {
            is ChdResidentHeaderRead.Parsed -> headerRead.header
            ChdResidentHeaderRead.NotChd ->
                return ChdAuditObservation(failure = MameInventoryFailure.MALFORMED_CONTAINER)
            ChdResidentHeaderRead.Unreadable ->
                return ChdAuditObservation(failure = MameInventoryFailure.IO_RETRYABLE)
        }
        val atom = MameDiskAtom(header.combinedSha1.lowercase())
        if (requiredDiskAtoms != null && atom !in requiredDiskAtoms) {
            return ChdAuditObservation()
        }
        if (chdVerifications >= MameRootDependencyAudit.MAX_CHD_VERIFICATIONS) {
            return ChdAuditObservation(
                atom = atom,
                state = MameObservedDiskState.UNVERIFIED,
                failure = MameInventoryFailure.RESOURCE_REFUSED,
            )
        }
        chdVerifications++
        val verified = if (header.parentSha1 != null) {
            chainDiscovery.resolve(
                target = game.toChdResident(),
                targetHeader = header,
                budget = budget,
            )
        } else {
            val native = openNative(game.sourceDocumentUri, game.managedPath)
                ?: return ChdAuditObservation(
                    atom = atom,
                    state = MameObservedDiskState.UNVERIFIED,
                    failure = MameInventoryFailure.SEEK_UNAVAILABLE,
                )
            native.use { standaloneChdVerifier(it.path, header, budget) }
        }
        return when (verified) {
            is ContainerIdentityResult.Identified -> ChdAuditObservation(
                atom = MameDiskAtom(verified.value.combinedSha1.lowercase()),
                state = MameObservedDiskState.VERIFIED,
                verified = verified.value,
            )
            is ContainerIdentityResult.PolicyAbstain -> when (verified.reason) {
                ContainerIdentityReason.PARENT_REQUIRED -> ChdAuditObservation(
                    atom = atom,
                    state = MameObservedDiskState.PARENT_REQUIRED,
                )
                else -> ChdAuditObservation(
                    atom = atom,
                    state = MameObservedDiskState.UNVERIFIED,
                )
            }
            is ContainerIdentityResult.NeedsReview -> ChdAuditObservation(
                atom = atom,
                state = MameObservedDiskState.UNVERIFIED,
                failure = verified.reason.toInventoryFailure(),
            )
            is ContainerIdentityResult.RetryableFailure -> when (verified.reason) {
                ContainerIdentityReason.PARENT_REQUIRED -> ChdAuditObservation(
                    atom = atom,
                    state = MameObservedDiskState.PARENT_REQUIRED,
                )
                else -> ChdAuditObservation(
                    atom = atom,
                    state = MameObservedDiskState.UNVERIFIED,
                    failure = verified.reason.toInventoryFailure(),
                )
            }
        }
    }

    private suspend fun readChdHeader(game: GameEntity): ChdResidentHeaderRead {
        chdHeaders[game.entryKey]?.let { return it }
        val random = try {
            opener.openRandomAccess(game.sourceDocumentUri, game.managedPath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return ChdResidentHeaderRead.Unreadable.also { chdHeaders[game.entryKey] = it }
        val result = random.use {
            if (it.length < CHD_HEADER_BYTES) {
                ChdResidentHeaderRead.NotChd
            } else {
                val bytes = it.readExact(0L, CHD_HEADER_BYTES)
                    ?: return@use ChdResidentHeaderRead.Unreadable
                RomFingerprinter.chdHeaderEvidence(bytes, bytes.size)
                    ?.let(ChdResidentHeaderRead::Parsed)
                    ?: ChdResidentHeaderRead.NotChd
            }
        }
        chdHeaders[game.entryKey] = result
        return result
    }

    private fun openStream(game: GameEntity) = try {
        opener.open(game.sourceDocumentUri, game.managedPath)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun openNative(documentUri: String?, managedPath: String?) = try {
        opener.openNativeSeekablePath(documentUri, managedPath)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun admitRomBytes(bytes: Long): Boolean {
        if (
            bytes !in 0L..MameRootDependencyAudit.MAX_AGGREGATE_ROM_BYTES ||
            aggregateRomBytes > MameRootDependencyAudit.MAX_AGGREGATE_ROM_BYTES - bytes
        ) {
            return false
        }
        aggregateRomBytes += bytes
        return true
    }

    private fun completeCoverage(
        evaluator: MameDependencyClosureEvaluator,
        machineName: String,
        sourceRootUri: String,
        selectedBios: Map<String, String>,
    ): MameAuditCoverage? {
        val target = dependencyCatalog.machine(machineName) ?: return null
        val nodes = listOf(target) + target.deviceDependencies()
        val everyRom = nodes.flatMap { it.romRequirements() }.mapTo(LinkedHashSet()) { it.atom }
        val everyDisk = nodes.flatMap { it.diskRequirements() }.associate {
            it.atom to MameObservedDiskState.VERIFIED
        }
        val result = evaluator.evaluate(
            MameClosureRequest(
                sourceRootKey = sourceRootUri,
                machineName = machineName,
                selectedBios = selectedBios,
                inventory = MameRootInventory(
                    sourceRootKey = sourceRootUri,
                    complete = true,
                    romAtoms = everyRom,
                    diskAtoms = everyDisk,
                ),
            ),
        )
        return (result as? MameClosureResult.Satisfied)?.report?.toCoverage()
    }

    private companion object {
        const val CHD_HEADER_BYTES = 132
        const val AVAILABLE = "AVAILABLE"
        const val ARCADE = "arcade"
    }
}

private data class ChdAuditObservation(
    val atom: MameDiskAtom? = null,
    val state: MameObservedDiskState? = null,
    val verified: VerifiedChdIdentity? = null,
    val failure: MameInventoryFailure? = null,
) {
    fun targetFailure(): MameDependencyAuditResult = when {
        state == MameObservedDiskState.PARENT_REQUIRED ->
            MameDependencyAuditResult.Retryable(MameAuditReason.CHD_PARENT_REQUIRED)
        failure == MameInventoryFailure.IO_RETRYABLE ||
            failure == MameInventoryFailure.SEEK_UNAVAILABLE ->
            MameDependencyAuditResult.Retryable(MameAuditReason.INVENTORY_INCOMPLETE)
        failure == MameInventoryFailure.RESOURCE_REFUSED ->
            MameDependencyAuditResult.Blocked(MameAuditReason.RESOURCE_REFUSED)
        failure == MameInventoryFailure.AMBIGUOUS_CONTAINER ->
            MameDependencyAuditResult.NeedsReview(MameAuditReason.AMBIGUOUS_CONTAINER)
        failure == MameInventoryFailure.MALFORMED_CONTAINER ->
            MameDependencyAuditResult.NeedsReview(MameAuditReason.MALFORMED_CONTAINER)
        state == MameObservedDiskState.UNVERIFIED ->
            MameDependencyAuditResult.NeedsReview(MameAuditReason.DISK_UNVERIFIED)
        else -> MameDependencyAuditResult.NeedsReview(MameAuditReason.MACHINE_UNRESOLVED)
    }
}

private fun GameEntity.toChdResident() = ChdChainResident(
    entryKey = entryKey,
    sourceRootUri = sourceRootUri,
    relativePath = relativePath,
    documentUri = sourceDocumentUri,
    managedPath = managedPath,
)

private fun ChdChainResident.toGame(residents: List<GameEntity>): GameEntity =
    requireNotNull(residents.firstOrNull { it.entryKey == entryKey }) {
        "root-scoped CHD resident missing from its immutable audit snapshot"
    }

private fun route(game: GameEntity): ContainerRoutingPolicy.Route =
    ContainerRoutingPolicy.route(
        platformId = game.platformId,
        fileName = ContainerRoutingPolicy.fileName(
            relativePath = game.relativePath,
            displayName = game.displayName,
            legacyLocation = game.sourceDocumentUri,
        ),
    )

internal fun mergeMameDiskState(
    current: MameObservedDiskState?,
    observed: MameObservedDiskState,
): MameObservedDiskState = when {
    current == MameObservedDiskState.VERIFIED || observed == MameObservedDiskState.VERIFIED ->
        MameObservedDiskState.VERIFIED
    current == MameObservedDiskState.PARENT_REQUIRED ||
        observed == MameObservedDiskState.PARENT_REQUIRED ->
        MameObservedDiskState.PARENT_REQUIRED
    else -> MameObservedDiskState.UNVERIFIED
}

private fun ContainerIdentityReason.toInventoryFailure(): MameInventoryFailure = when (this) {
    ContainerIdentityReason.AMBIGUOUS -> MameInventoryFailure.AMBIGUOUS_CONTAINER
    ContainerIdentityReason.RESOURCE_REFUSED -> MameInventoryFailure.RESOURCE_REFUSED
    ContainerIdentityReason.SEEK_UNAVAILABLE -> MameInventoryFailure.SEEK_UNAVAILABLE
    ContainerIdentityReason.IO_RETRYABLE -> MameInventoryFailure.IO_RETRYABLE
    ContainerIdentityReason.MALFORMED,
    ContainerIdentityReason.UNSUPPORTED_CONTAINER,
    ContainerIdentityReason.PARENT_REQUIRED,
    ContainerIdentityReason.NO_CATALOG_MATCH,
    ContainerIdentityReason.MEMBER_ONLY,
    ContainerIdentityReason.DLC_ROLE_NOT_ADMITTED,
    ContainerIdentityReason.ZIPPED_OPTICAL_UNSUPPORTED,
    -> MameInventoryFailure.MALFORMED_CONTAINER
}

private fun targetFailure(reason: ContainerIdentityReason): MameDependencyAuditResult = when (reason) {
    ContainerIdentityReason.RESOURCE_REFUSED ->
        MameDependencyAuditResult.Blocked(MameAuditReason.RESOURCE_REFUSED)
    ContainerIdentityReason.IO_RETRYABLE,
    ContainerIdentityReason.SEEK_UNAVAILABLE,
    ->
        MameDependencyAuditResult.Retryable(MameAuditReason.INVENTORY_INCOMPLETE)
    ContainerIdentityReason.PARENT_REQUIRED ->
        MameDependencyAuditResult.Retryable(MameAuditReason.CHD_PARENT_REQUIRED)
    ContainerIdentityReason.AMBIGUOUS ->
        MameDependencyAuditResult.NeedsReview(MameAuditReason.AMBIGUOUS_CONTAINER)
    ContainerIdentityReason.MALFORMED,
    ContainerIdentityReason.UNSUPPORTED_CONTAINER,
    ->
        MameDependencyAuditResult.NeedsReview(MameAuditReason.MALFORMED_CONTAINER)
    ContainerIdentityReason.NO_CATALOG_MATCH,
    ContainerIdentityReason.MEMBER_ONLY,
    ContainerIdentityReason.DLC_ROLE_NOT_ADMITTED,
    ContainerIdentityReason.ZIPPED_OPTICAL_UNSUPPORTED,
    ->
        MameDependencyAuditResult.NeedsReview(MameAuditReason.MACHINE_UNRESOLVED)
}

private fun MameClosureResult.NeedsReview.toPublic() =
    MameDependencyAuditResult.NeedsReview(reason.toPublic())

private fun MameClosureResult.Retryable.toPublic() =
    MameDependencyAuditResult.Retryable(reason.toPublic())

private fun MameClosureResult.PolicyBlocked.toPublic() =
    MameDependencyAuditResult.Blocked(reason.toPublic())

private fun MameClosureReason.toPublic(): MameAuditReason = when (this) {
    MameClosureReason.CATALOG_UNAVAILABLE -> MameAuditReason.CATALOG_UNAVAILABLE
    MameClosureReason.CATALOG_IO_RETRYABLE -> MameAuditReason.CATALOG_IO_RETRYABLE
    MameClosureReason.CATALOG_MALFORMED -> MameAuditReason.CATALOG_MALFORMED
    MameClosureReason.CATALOG_RESOURCE_REFUSED -> MameAuditReason.CATALOG_RESOURCE_REFUSED
    MameClosureReason.CATALOG_BLOCKED -> MameAuditReason.CATALOG_BLOCKED
    MameClosureReason.INVENTORY_INCOMPLETE -> MameAuditReason.INVENTORY_INCOMPLETE
    MameClosureReason.MALFORMED_CONTAINER -> MameAuditReason.MALFORMED_CONTAINER
    MameClosureReason.AMBIGUOUS_CONTAINER -> MameAuditReason.AMBIGUOUS_CONTAINER
    MameClosureReason.RESOURCE_REFUSED -> MameAuditReason.RESOURCE_REFUSED
    MameClosureReason.ROOT_MISMATCH -> MameAuditReason.ROOT_MISMATCH
    MameClosureReason.MACHINE_UNRESOLVED -> MameAuditReason.MACHINE_UNRESOLVED
    MameClosureReason.NON_RUNNABLE_TARGET -> MameAuditReason.NON_RUNNABLE_TARGET
    MameClosureReason.BIOS_SELECTION_REQUIRED -> MameAuditReason.BIOS_SELECTION_REQUIRED
    MameClosureReason.UNKNOWN_BIOS_SELECTION -> MameAuditReason.UNKNOWN_BIOS_SELECTION
    MameClosureReason.CHD_PARENT_REQUIRED -> MameAuditReason.CHD_PARENT_REQUIRED
    MameClosureReason.DISK_UNVERIFIED -> MameAuditReason.DISK_UNVERIFIED
    MameClosureReason.UNSUPPORTED_MEMBER_SIZE -> MameAuditReason.UNSUPPORTED_MEMBER_SIZE
    MameClosureReason.WRITABLE_DISK_UNSUPPORTED -> MameAuditReason.WRITABLE_DISK_UNSUPPORTED
    MameClosureReason.DEPENDENCY_CYCLE -> MameAuditReason.DEPENDENCY_CYCLE
    MameClosureReason.DEPENDENCY_BUDGET -> MameAuditReason.DEPENDENCY_BUDGET
}

private fun MameCoverageReport.toCoverage() = MameAuditCoverage(
    nodesEvaluated = nodesEvaluated,
    requiredRomAtoms = requiredRomAtoms,
    requiredDiskAtoms = requiredDiskAtoms,
    declaredRomRequirements = declaredRomRequirements,
    declaredDiskRequirements = declaredDiskRequirements,
    baddumpRequirements = baddumpRequirements,
    missingRomAtoms = 0,
    missingDiskAtoms = 0,
)

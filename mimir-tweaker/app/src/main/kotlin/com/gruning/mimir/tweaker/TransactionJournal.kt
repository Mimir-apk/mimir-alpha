package com.gruning.mimir.tweaker

/** Authoritative TW04-B journal phases. Never ACTIVE or RECOVERY_REQUIRED. */
internal enum class TransactionPhase {
    PREPARED,
    /** Schema-v5: immutable Guardian binding is durable, but no mutation authority exists. */
    ARMING,
    /** Schema-v5: the exact Guardian READY/session evidence is durable. */
    GUARDIAN_READY,
    APPLYING,
    APPLIED,
    RESTORING,
    RESTORE_FAILED,
    /** Schema-v5: terminal Guardian receipt is durable; cross-store cleanup is still owed. */
    GUARDIAN_SETTLED,
}

/**
 * Journal-side immutable identity of the separately authenticated Guardian record.
 *
 * This is deliberately evidence only: no command, profile, or execution capability is represented.
 * The journal signature authenticates it, while [GuardianTransactionReconciler] requires an exact
 * independently authenticated arm-store counterpart before selecting any recovery posture.
 */
internal data class GuardianJournalBinding(
    val envelopeSha256: String,
    val taskNonce: String,
    val helperBuildIdentity: String,
    val tweakerCertificateSha256: String,
    val hardDeadlineElapsedRealtimeMs: Long,
    val readySessionSha256: String? = null,
    val terminalReceiptSha256: String? = null,
) {
    init {
        GuardianEnvelopeValidation.requireSha256(envelopeSha256, "guardian envelope")
        GuardianEnvelopeValidation.requireNonce(taskNonce)
        GuardianEnvelopeValidation.requireToken(helperBuildIdentity, "helper build")
        GuardianEnvelopeValidation.requireSha256(tweakerCertificateSha256, "certificate")
        require(hardDeadlineElapsedRealtimeMs > 0) { "hard deadline must be positive" }
        readySessionSha256?.let { GuardianEnvelopeValidation.requireSha256(it, "READY session") }
        terminalReceiptSha256?.let { GuardianEnvelopeValidation.requireSha256(it, "terminal receipt") }
    }
}

/**
 * Durable active-session journal record. Every field is required for a valid encoding;
 * [lastFailure] may be null when no failure has been recorded.
 */
internal data class TransactionJournalRecord(
    val schemaVersion: Int = TRANSACTION_JOURNAL_SCHEMA,
    val generation: Long,
    val requestId: String,
    val transactionId: String,
    val hardwareFingerprint: String,
    val deviceProfile: String,
    val planHash: String,
    val profileId: String,
    val targetPackage: String,
    val phase: TransactionPhase,
    val creationTime: Long,
    val requestDeadline: Long,
    val leaseDeadline: Long,
    val bootIdentity: String,
    val snapshot: HardwareSnapshot,
    /** SHA-256 of the exact ordered planned topology identities at PREPARED. */
    val expectedTopologySha256: String,
    /** SHA-256 of the full captured snapshot (schema/fingerprint/boot + every node field). */
    val snapshotSha256: String,
    val lastCompletedMutationBoundary: Int,
    val lastCompletedRestoreBoundary: Int = 0,
    val lastFailure: String? = null,
    /**
     * Schema-v2+: full historical plan identity persisted before PREPARED.
     * Schema-v1 recovery leaves this null and never rewrites the journal.
     */
    val appliedPlan: PersistedTuningPlan? = null,
    /**
     * Schema-v2+: exact complete apply-order logical IDs at PREPARED.
     * Restore is the literal reverse of this list.
     */
    val applyOrderLogicalIds: List<String> = emptyList(),
    /**
     * Schema-v4: exact ordered renderer output captured before PREPARED. The MTJA
     * signature covers this list; later phase and consume writes must preserve its
     * canonical bytes.
     */
    val appliedMutationProjection: List<PersistedAppliedMutation> = emptyList(),
    /**
     * Schema-v3+: SHA-256 of the one exact MGDE envelope already admitted for this
     * transaction. Null is explicit unconsumed truth; schema-v3+ encoding must carry
     * the field even when null.
     */
    val consumedMgdeEnvelopeSha256: String? = null,
    /** Schema-v5 immutable cross-store Guardian identity; legacy records deliberately have none. */
    val guardianBinding: GuardianJournalBinding? = null,
)

/**
 * Schema-v5 phase/evidence contract. READY and terminal evidence are appended atomically with the
 * phase that first requires them; earlier phases may never carry future evidence.
 */
internal object GuardianJournalBindingValidator {
    fun validate(record: TransactionJournalRecord): List<String> {
        if (record.schemaVersion != TRANSACTION_JOURNAL_SCHEMA_V5) {
            return buildList {
                if (record.guardianBinding != null) add("legacy journal carries Guardian binding")
                if (
                    record.phase == TransactionPhase.ARMING ||
                    record.phase == TransactionPhase.GUARDIAN_READY ||
                    record.phase == TransactionPhase.GUARDIAN_SETTLED
                ) {
                    add("Guardian phase requires schema-v5")
                }
            }
        }
        val binding = record.guardianBinding
            ?: return listOf("schema-v5 missing immutable Guardian binding")
        return when (record.phase) {
            TransactionPhase.PREPARED,
            TransactionPhase.ARMING,
            -> buildList {
                if (binding.readySessionSha256 != null) add("${record.phase} carries premature READY evidence")
                if (binding.terminalReceiptSha256 != null) add("${record.phase} carries premature terminal evidence")
            }
            TransactionPhase.GUARDIAN_READY,
            TransactionPhase.APPLYING,
            TransactionPhase.APPLIED,
            TransactionPhase.RESTORING,
            TransactionPhase.RESTORE_FAILED,
            -> buildList {
                if (binding.readySessionSha256 == null) add("${record.phase} missing READY session evidence")
                if (binding.terminalReceiptSha256 != null) add("${record.phase} carries premature terminal evidence")
            }
            TransactionPhase.GUARDIAN_SETTLED -> buildList {
                if (binding.readySessionSha256 == null) add("GUARDIAN_SETTLED missing READY session evidence")
                if (binding.terminalReceiptSha256 == null) add("GUARDIAN_SETTLED missing terminal receipt evidence")
            }
        }
    }
}

/** Durable journal load truth: empty, valid, or corrupt. Corrupt is never empty. */
internal sealed class JournalLoadResult {
    data object Empty : JournalLoadResult()
    data class Loaded(val record: TransactionJournalRecord) : JournalLoadResult()
    data class Corrupt(val reason: String) : JournalLoadResult()
}

/** Monotonic generation allocation; fail-closed on corrupt/unreadable state. */
internal sealed class GenerationResult {
    data class Ok(val value: Long) : GenerationResult()
    data class Failed(val reason: String) : GenerationResult()
}

/**
 * Device-protected atomic journal for crash-restorable Tweaker sessions.
 * Persist must fsync before returning success. Generation is monotonic across sessions.
 */
internal interface TransactionJournal {
    fun load(): JournalLoadResult
    fun nextGeneration(): GenerationResult

    /**
     * Write a record whose applied projection is UNCHANGED. Phase advances, Guardian digest
     * merges and MGDE consume transitions all round-trip through here, and the durable
     * implementation refuses any candidate that rewrites what is applied to hardware.
     */
    fun persist(record: TransactionJournalRecord): Boolean

    /**
     * Amend the APPLIED record of a live transaction with a strictly slower applied projection
     * -- the WAVE2 thermal-derate rung path. Fan ownership uses [amendFanOwnership] instead.
     * Separate from [persist] so the immutability guard there stays frozen: no
     * amount of drift in this lane can loosen the ordinary write path.
     *
     * The default delegates to [persist] for in-memory test doubles. The durable
     * implementation overrides it with the identity, phase and monotonic-slower rules.
     */
    fun amendApplied(record: TransactionJournalRecord): Boolean = persist(record)

    /** Fan-only 4/6 amendment. The coordinator must also verify the signed envelope's targets. */
    fun amendFanOwnership(record: TransactionJournalRecord, envelope: RestoreGuardianEnvelope): Boolean = false

    /** Clear only when the durable record matches [expectedTransactionId]. */
    fun clear(expectedTransactionId: String): Boolean
}

/** Deterministic death/fault injection points for JVM proof. */
internal enum class TransactionBarrierPoint {
    AFTER_CAPTURE_BEFORE_JOURNAL,
    AFTER_PREPARED,
    AFTER_GUARDIAN_ARMING,
    AFTER_GUARDIAN_READY,
    AFTER_APPLYING_PHASE,
    BEFORE_MUTATION,
    AFTER_APPLY_COMMAND_BEFORE_READBACK,
    AFTER_MUTATION,
    BEFORE_APPLY_VERIFY,
    AFTER_APPLY_VERIFIED_BEFORE_APPLIED,
    AFTER_APPLIED,
    AFTER_RESTORING_PHASE,
    BEFORE_RESTORE_MUTATION,
    AFTER_RESTORE_COMMAND_BEFORE_READBACK,
    AFTER_RESTORE_MUTATION,
    BEFORE_RESTORE_VERIFY,
    AFTER_RESTORE_VERIFIED_BEFORE_CLEAR,
}

internal fun interface TransactionBarrier {
    fun check(point: TransactionBarrierPoint, mutationIndex: Int)
}

internal object NoopTransactionBarrier : TransactionBarrier {
    override fun check(point: TransactionBarrierPoint, mutationIndex: Int) = Unit
}

internal class TransactionDeathException(
    val point: TransactionBarrierPoint,
    val mutationIndex: Int = -1,
) : RuntimeException("Injected transaction death at $point index=$mutationIndex")

/**
 * Coordinator-side defense-in-depth: every CPU/GPU node in a captured snapshot must carry
 * value, mode, UID, and GID before PREPARED. Fakes cannot bypass this by omitting fields.
 */
internal object SnapshotCompleteness {
    fun validate(snapshot: HardwareSnapshot): List<String> {
        val failures = mutableListOf<String>()
        if (snapshot.hardwareFingerprint.isBlank()) failures += "hardware fingerprint missing"
        if (snapshot.bootIdentity.isBlank()) failures += "boot identity missing"
        if (snapshot.nodes.isEmpty()) failures += "snapshot has no topology nodes"
        snapshot.nodes.forEach { node ->
            when (node.axis) {
                TuningAxis.FAN -> {
                    if (node.canonicalPath != HardwareSnapshot.FAN_SETTINGS_PATH) {
                        failures += "${node.logicalId} noncanonical fan path"
                    }
                    if (node.originalMode != null || node.originalUid != null || node.originalGid != null) {
                        failures += "${node.logicalId} fan must have null mode/UID/GID"
                    }
                    if (node.present && node.originalValue == null) {
                        failures += "${node.logicalId} present without integer value"
                    }
                    if (!node.present && node.originalValue != null) {
                        failures += "${node.logicalId} absent fan must have null value"
                    }
                    val fanValue = node.originalValue
                    if (node.present && fanValue != null && !TuningPlanner.isCapturedVendorFanMode(fanValue)) {
                        failures += "${node.logicalId} captured fan_mode $fanValue outside vendor range"
                    }
                }
                TuningAxis.VENDOR_MODE -> {
                    if (node.logicalId != HardwareSnapshot.VENDOR_MODE_LOGICAL_ID) {
                        failures += "${node.logicalId} noncanonical vendor mode id"
                    }
                    if (node.canonicalPath != HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH) {
                        failures += "${node.logicalId} noncanonical vendor mode path"
                    }
                    if (node.originalMode != null || node.originalUid != null || node.originalGid != null) {
                        failures += "${node.logicalId} vendor mode must have null mode/UID/GID"
                    }
                    // Unlike fan there is no "absent" capture: a plan that drives the mode needs a
                    // readable origin to restore to, so an absent node is an incomplete capture.
                    if (!node.present) {
                        failures += "${node.logicalId} missing/not present"
                    }
                    val vendorValue = node.originalValue
                    if (vendorValue == null) {
                        failures += "${node.logicalId} missing value"
                    } else if (vendorValue !in TuningPlanner.KNOWN_VENDOR_MODES) {
                        failures += "${node.logicalId} captured performance_mode $vendorValue outside known modes"
                    }
                }
                TuningAxis.CPU, TuningAxis.GPU -> {
                    if (!node.present) {
                        failures += "${node.logicalId} missing/not present"
                    }
                    if (node.originalValue == null) {
                        failures += "${node.logicalId} missing value"
                    }
                    if (node.originalMode.isNullOrBlank()) {
                        failures += "${node.logicalId} missing mode"
                    }
                    if (node.originalUid == null) {
                        failures += "${node.logicalId} missing UID"
                    }
                    if (node.originalGid == null) {
                        failures += "${node.logicalId} missing GID"
                    }
                    val canonical = SysfsPath.canonicalize(node.canonicalPath)
                    if (canonical == null || canonical != node.canonicalPath) {
                        failures += "${node.logicalId} noncanonical path ${node.canonicalPath}"
                    }
                }
            }
        }
        return failures
    }
}

/** Exact ordered topology identity used for integrity digests and restore gates. */
internal data class TopologyNodeIdentity(
    val logicalId: String,
    val axis: TuningAxis,
    val canonicalPath: String,
)

/**
 * Integrity binding for planned topology and full snapshot digests.
 * Digests are strict lowercase 64-hex SHA-256.
 */
internal object SnapshotIntegrity {
    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    private val MODE_OCTAL = Regex("^[0-7]{3,4}$")
    private val CPU_LOGICAL_ID = Regex("^cpu:([0-9]+):(scaling_min_freq|scaling_max_freq)$")

    fun isValidSha256Hex(value: String): Boolean = SHA256_HEX.matches(value)

    fun identitiesOf(snapshot: HardwareSnapshot): List<TopologyNodeIdentity> =
        snapshot.nodes.map { TopologyNodeIdentity(it.logicalId, it.axis, it.canonicalPath) }

    /**
     * Independently compute the exact ordered planned topology from [plan] + live [capabilities].
     * Returns null when any planned sysfs path cannot be resolved to a canonical `/sys` target.
     */
    fun expectedTopology(
        plan: TuningPlan,
        capabilities: HardwareCapabilities,
        resolver: SysfsCanonicalResolver = FileSysfsCanonicalResolver,
        cpuMinPolicyIds: Set<Int> = emptySet(),
        cpuCeilingPath: String? = capabilities.cpuCeilingPath,
        includeGpuMin: Boolean = true,
    ): List<TopologyNodeIdentity>? {
        val out = mutableListOf<TopologyNodeIdentity>()
        capabilities.cpuPolicies.forEach { policy ->
            if (policy.id !in plan.cpuMaxKhz) return@forEach
            if (policy.id in cpuMinPolicyIds) {
                val minPath = SysfsPath.canonicalize("${policy.path}/scaling_min_freq", resolver)
                    ?: return null
                out += TopologyNodeIdentity(
                    logicalId = "cpu:${policy.id}:scaling_min_freq",
                    axis = TuningAxis.CPU,
                    canonicalPath = minPath,
                )
            }
            val path = SysfsPath.canonicalize(
                cpuCeilingPath ?: "${policy.path}/scaling_max_freq",
                resolver,
            )
                ?: return null
            out += TopologyNodeIdentity(
                logicalId = "cpu:${policy.id}:scaling_max_freq",
                axis = TuningAxis.CPU,
                canonicalPath = path,
            )
        }
        if (plan.gpuMaxPowerLevel != null) {
            // The SAME transport decision the capture makes, from the same inputs. This function is
            // what the captured snapshot is CHECKED AGAINST, so a disagreement here does not show up
            // as a wrong node -- it shows up as CAPTURE_INCOMPLETE, with the capture blamed for a
            // topology this function expected. Resolving the devfreq path IS its existence test:
            // canonicalize returns null for a node that is not there.
            val ceiling = capabilities.gpuCeilingPath
                ?.takeIf {
                    GpuCeilingTransport.targetHzFor(capabilities.gpuFrequenciesKhz, plan.gpuMaxPowerLevel) != null
                }
            val maxPath = if (ceiling != null) {
                SysfsPath.canonicalize(ceiling, resolver) ?: return null
            } else {
                SysfsPath.canonicalize(HardwareProbe.GPU_MAX_LEVEL, resolver) ?: return null
            }
            val ceilingLogicalId = if (ceiling != null) {
                HardwareSnapshot.GPU_DEVFREQ_MAX_LOGICAL_ID
            } else {
                HardwareSnapshot.GPU_MAX_LOGICAL_ID
            }
            if (includeGpuMin) {
                val minPath = SysfsPath.canonicalize(HardwareProbe.GPU_MIN_LEVEL, resolver) ?: return null
                out += TopologyNodeIdentity(
                    logicalId = HardwareSnapshot.GPU_MIN_LOGICAL_ID,
                    axis = TuningAxis.GPU,
                    canonicalPath = minPath,
                )
            }
            out += TopologyNodeIdentity(
                logicalId = ceilingLogicalId,
                axis = TuningAxis.GPU,
                canonicalPath = maxPath,
            )
        }
        if (plan.fanMode != null) {
            out += TopologyNodeIdentity(
                logicalId = HardwareSnapshot.FAN_LOGICAL_ID,
                axis = TuningAxis.FAN,
                canonicalPath = HardwareSnapshot.FAN_SETTINGS_PATH,
            )
        }
        if (plan.vendorMode != null) {
            out += TopologyNodeIdentity(
                logicalId = HardwareSnapshot.VENDOR_MODE_LOGICAL_ID,
                axis = TuningAxis.VENDOR_MODE,
                canonicalPath = HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH,
            )
        }
        return out
    }

    fun topologySha256(identities: List<TopologyNodeIdentity>): String {
        val canonical = buildString {
            identities.forEach { node ->
                append(node.logicalId).append('|')
                append(node.axis.name).append('|')
                append(node.canonicalPath).append('\n')
            }
        }
        return sha256Hex(canonical)
    }

    fun snapshotSha256(snapshot: HardwareSnapshot): String {
        val canonical = buildString {
            append(snapshot.schemaVersion).append('|')
            append(snapshot.hardwareFingerprint).append('|')
            append(snapshot.bootIdentity).append('\n')
            snapshot.nodes.forEach { node ->
                append(node.logicalId).append('|')
                append(node.axis.name).append('|')
                append(node.canonicalPath).append('|')
                append(node.originalValue ?: "null").append('|')
                append(node.present).append('|')
                append(node.originalMode ?: "null").append('|')
                append(node.originalUid ?: "null").append('|')
                append(node.originalGid ?: "null").append('\n')
            }
        }
        return sha256Hex(canonical)
    }

    fun bindHashes(
        plan: TuningPlan,
        capabilities: HardwareCapabilities,
        snapshot: HardwareSnapshot,
        resolver: SysfsCanonicalResolver = FileSysfsCanonicalResolver,
    ): Pair<String, String>? {
        val expected = expectedTopology(
            plan,
            capabilities,
            resolver,
            cpuMinPolicyIds = snapshot.cpuMinKhz.keys,
            includeGpuMin = snapshot.gpuMinPowerLevel != null,
        ) ?: return null
        if (identitiesOf(snapshot) != expected) return null
        return topologySha256(expected) to snapshotSha256(snapshot)
    }

    /**
     * Decoder semantic + completeness gate. Returns a corrupt reason, never throws.
     */
    fun validateDecodedSnapshot(
        snapshot: HardwareSnapshot,
        expectedTopologyDigest: String,
        expectedSnapshotDigest: String,
        resolver: SysfsCanonicalResolver = FileSysfsCanonicalResolver,
    ): String? {
        if (!isValidSha256Hex(expectedTopologyDigest)) {
            return "Invalid expectedTopologySha256"
        }
        if (!isValidSha256Hex(expectedSnapshotDigest)) {
            return "Invalid snapshotSha256"
        }
        val logicalIds = linkedSetOf<String>()
        val pathOwners = linkedMapOf<String, TopologyNodeState>()
        for (node in snapshot.nodes) {
            if (!logicalIds.add(node.logicalId)) {
                return "Duplicate logical ID: ${node.logicalId}"
            }
            val priorPathOwner = pathOwners.putIfAbsent(node.canonicalPath, node)
            if (
                priorPathOwner != null &&
                !(isSharedCpuQosNode(priorPathOwner) && isSharedCpuQosNode(node))
            ) {
                return "Duplicate canonical path: ${node.canonicalPath}"
            }
            semanticBindFailure(node, resolver)?.let { return it }
        }
        SnapshotCompleteness.validate(snapshot).firstOrNull()?.let { return it }
        val identities = identitiesOf(snapshot)
        if (topologySha256(identities) != expectedTopologyDigest) {
            return "Snapshot topology hash mismatch"
        }
        if (snapshotSha256(snapshot) != expectedSnapshotDigest) {
            return "Snapshot digest mismatch"
        }
        return null
    }

    private fun semanticBindFailure(
        node: TopologyNodeState,
        resolver: SysfsCanonicalResolver,
    ): String? {
        return when (node.axis) {
            TuningAxis.CPU -> {
                val logical = CPU_LOGICAL_ID.matchEntire(node.logicalId)
                if (logical == null) {
                    return "CPU logicalId shape invalid: ${node.logicalId}"
                }
                val policyId = logical.groupValues[1]
                val expectedSuffix = logical.groupValues[2]
                val expectedPath = SysfsPath.canonicalize(
                    "${HardwareProbe.CPU_ROOT}/policy$policyId/$expectedSuffix",
                    resolver,
                )
                    ?: return "CPU live policy path unresolved: ${node.logicalId}"
                val sharedQos = expectedSuffix == "scaling_max_freq" &&
                    node.canonicalPath == HardwareProbe.CPU_QOS_MAX_PATH
                if (node.canonicalPath != expectedPath && !sharedQos) {
                    return "CPU path/logicalId mismatch: ${node.logicalId}"
                }
                sysfsValuePermissionFailure(node, cpuPositive = true)
            }
            TuningAxis.GPU -> {
                val expectedPath = when (node.logicalId) {
                    HardwareSnapshot.GPU_MIN_LOGICAL_ID ->
                        SysfsPath.canonicalize(HardwareProbe.GPU_MIN_LEVEL, resolver)
                    HardwareSnapshot.GPU_MAX_LOGICAL_ID ->
                        SysfsPath.canonicalize(HardwareProbe.GPU_MAX_LEVEL, resolver)
                    // The devfreq ceiling. Its absence here was the LAST link in a four-cycle chain:
                    // the record encoded, but `signJournal` re-decodes what it is about to sign and
                    // requires it to bind, so an id this function does not know made the payload
                    // unsignable -- surfacing as JOURNAL_PERSIST_FAILED, three layers from the cause.
                    HardwareSnapshot.GPU_DEVFREQ_MAX_LOGICAL_ID ->
                        SysfsPath.canonicalize(HardwareProbe.GPU_DEVFREQ_MAX_FREQ, resolver)
                    else -> return "GPU logicalId invalid: ${node.logicalId}"
                } ?: return "GPU live constant path unresolved: ${node.logicalId}"
                if (node.canonicalPath != expectedPath) {
                    return "GPU path mismatch: ${node.logicalId}"
                }
                sysfsValuePermissionFailure(node, cpuPositive = false)
            }
            TuningAxis.FAN -> {
                if (node.logicalId != HardwareSnapshot.FAN_LOGICAL_ID) {
                    return "FAN logicalId invalid: ${node.logicalId}"
                }
                if (node.canonicalPath != HardwareSnapshot.FAN_SETTINGS_PATH) {
                    return "FAN path invalid: ${node.logicalId}"
                }
                if (node.originalMode != null || node.originalUid != null || node.originalGid != null) {
                    return "FAN mode/UID/GID must be null: ${node.logicalId}"
                }
                if (node.present) {
                    val value = node.originalValue
                        ?: return "FAN present without value: ${node.logicalId}"
                    if (!TuningPlanner.isCapturedVendorFanMode(value)) {
                        return "FAN value out of vendor range: ${node.logicalId}"
                    }
                } else if (node.originalValue != null) {
                    return "FAN absent must have null value: ${node.logicalId}"
                }
                null
            }
            TuningAxis.VENDOR_MODE -> {
                if (node.logicalId != HardwareSnapshot.VENDOR_MODE_LOGICAL_ID) {
                    return "VENDOR_MODE logicalId invalid: ${node.logicalId}"
                }
                if (node.canonicalPath != HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH) {
                    return "VENDOR_MODE path invalid: ${node.logicalId}"
                }
                if (node.originalMode != null || node.originalUid != null || node.originalGid != null) {
                    return "VENDOR_MODE mode/UID/GID must be null: ${node.logicalId}"
                }
                if (!node.present) return "VENDOR_MODE must be present: ${node.logicalId}"
                val value = node.originalValue
                    ?: return "VENDOR_MODE present without value: ${node.logicalId}"
                if (value !in TuningPlanner.KNOWN_VENDOR_MODES) {
                    return "VENDOR_MODE value out of known modes: ${node.logicalId}"
                }
                null
            }
        }
    }

    private fun sysfsValuePermissionFailure(node: TopologyNodeState, cpuPositive: Boolean): String? {
        if (!node.present) return "${node.logicalId} must be present"
        val value = node.originalValue ?: return "${node.logicalId} missing value"
        if (cpuPositive) {
            if (value <= 0) return "${node.logicalId} CPU value must be positive"
        } else if (value < 0) {
            return "${node.logicalId} GPU value must be nonnegative"
        }
        val mode = node.originalMode ?: return "${node.logicalId} missing mode"
        if (!MODE_OCTAL.matches(mode)) return "${node.logicalId} invalid octal mode"
        val uid = node.originalUid ?: return "${node.logicalId} missing UID"
        val gid = node.originalGid ?: return "${node.logicalId} missing GID"
        if (uid < 0 || gid < 0) return "${node.logicalId} UID/GID must be nonnegative"
        return null
    }

    private fun isSharedCpuQosNode(node: TopologyNodeState): Boolean =
        node.axis == TuningAxis.CPU &&
            node.canonicalPath == HardwareProbe.CPU_QOS_MAX_PATH &&
            CPU_LOGICAL_ID.matchEntire(node.logicalId)?.groupValues?.get(2) == "scaling_max_freq"

    private fun sha256Hex(canonical: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}

/** Static schema-v4 renderer projection validation; never reconstructs a renderer value. */
internal object AppliedMutationProjectionValidator {
    private val CPU = Regex("^cpu:([0-9]+):(scaling_min_freq|scaling_max_freq)$")
    private val GPU_IDS = setOf(
        HardwareSnapshot.GPU_MIN_LOGICAL_ID,
        HardwareSnapshot.GPU_MAX_LOGICAL_ID,
        HardwareSnapshot.GPU_DEVFREQ_MAX_LOGICAL_ID,
    )

    fun validate(record: TransactionJournalRecord): List<String> {
        val failures = mutableListOf<String>()
        if (record.schemaVersion != TRANSACTION_JOURNAL_SCHEMA && record.schemaVersion != TRANSACTION_JOURNAL_SCHEMA_V5) {
            if (record.appliedMutationProjection.isNotEmpty()) {
                failures += "legacy journal must not carry appliedMutationProjection"
            }
            return failures
        }
        val plan = record.appliedPlan
        if (plan == null) {
            failures += "schema-v4 projection requires appliedPlan"
            return failures
        }
        val projection = record.appliedMutationProjection
        if (projection.isEmpty()) {
            failures += "schema-v4 missing appliedMutationProjection"
            return failures
        }

        val projectedIds = projection.map { it.logicalId }
        if (projectedIds.size != projectedIds.toSet().size) {
            failures += "appliedMutationProjection contains duplicate logical IDs"
        }
        // Apply order and stock topology are deliberately checked as independent facts.
        if (projectedIds != record.applyOrderLogicalIds) {
            failures += "appliedMutationProjection order mismatch vs applyOrderLogicalIds"
        }
        val snapshotIds = record.snapshot.nodes.map { it.logicalId }
        if (projectedIds.toSet() != snapshotIds.toSet()) {
            failures += "appliedMutationProjection must cover snapshot topology exactly"
        }
        if (projectedIds.size != snapshotIds.size) {
            failures += "appliedMutationProjection size mismatch vs snapshot"
        }

        val nodes = record.snapshot.nodes.associateBy { it.logicalId }
        projection.forEach { mutation ->
            val node = nodes[mutation.logicalId]
            if (node == null) {
                failures += "projected logical ID missing from snapshot: ${mutation.logicalId}"
                return@forEach
            }
            if (mutation.axis != node.axis) {
                failures += "projected axis mismatch: ${mutation.logicalId}"
            }
            if (mutation.canonicalPath != node.canonicalPath) {
                failures += "projected canonical path mismatch: ${mutation.logicalId}"
            }
            if (!mutation.expectPresent) {
                failures += "projected apply target must expect presence: ${mutation.logicalId}"
            }

            when (mutation.axis) {
                TuningAxis.CPU -> {
                    val cpuMatch = CPU.matchEntire(mutation.logicalId)
                    val policyId = cpuMatch?.groupValues?.get(1)?.toIntOrNull()
                    val target = policyId?.let(plan.cpuMaxKhz::get)
                    if (policyId == null || target == null) {
                        failures += "projected CPU target absent from appliedPlan: ${mutation.logicalId}"
                    }
                    if (mutation.kind != LogicalMutationKind.SYSFS_WRITE) {
                        failures += "projected CPU mutation kind invalid: ${mutation.logicalId}"
                    }
                    if (mutation.value == null || mutation.value <= 0 || mutation.value != target) {
                        failures += "projected CPU value mismatch: ${mutation.logicalId}"
                    }
                    if (cpuMatch?.groupValues?.get(2) == "scaling_min_freq") {
                        val captured = node.originalValue
                        if (captured == null || target == null || captured <= target) {
                            failures += "projected CPU floor is not required: ${mutation.logicalId}"
                        }
                        val index = projection.indexOf(mutation)
                        val expectedMaxId = "cpu:$policyId:scaling_max_freq"
                        if (index < 0 || projection.getOrNull(index + 1)?.logicalId != expectedMaxId) {
                            failures += "projected CPU floor must immediately precede its ceiling"
                        }
                    }
                }
                TuningAxis.GPU -> {
                    if (mutation.logicalId !in GPU_IDS) {
                        failures += "projected GPU logical ID invalid: ${mutation.logicalId}"
                    }
                    if (mutation.kind != LogicalMutationKind.SYSFS_WRITE) {
                        failures += "projected GPU mutation kind invalid: ${mutation.logicalId}"
                    }
                    if (mutation.value == null || mutation.value < 0) {
                        failures += "projected GPU value must be nonnegative: ${mutation.logicalId}"
                    }
                    // Only the pwrlevel projection can be compared to the plan VERBATIM -- it is the
                    // same quantity in the same units. The devfreq projection is that ceiling
                    // translated into kHz, and its agreement with the plan was established at apply
                    // by the one function that owns the translation. Re-deriving it here would be a
                    // second opinion about the frequency table, which is exactly the divergence the
                    // single-transport choice exists to prevent.
                    if (
                        mutation.logicalId == HardwareSnapshot.GPU_MAX_LOGICAL_ID &&
                        mutation.value != plan.gpuMaxPowerLevel
                    ) {
                        failures += "projected GPU max mismatch vs appliedPlan"
                    }
                }
                TuningAxis.FAN -> {
                    if (
                        !HandheldDeviceProfiles.supportsFanMode(record.deviceProfile) ||
                        mutation.logicalId != HardwareSnapshot.FAN_LOGICAL_ID ||
                        mutation.canonicalPath != HardwareSnapshot.FAN_SETTINGS_PATH
                    ) {
                        failures += "projected fan target outside device-profile allowlist"
                    }
                    if (mutation.kind != LogicalMutationKind.SETTINGS_PUT) {
                        failures += "projected fan mutation kind invalid"
                    }
                    if (
                        mutation.value == null ||
                        mutation.value != plan.fanMode ||
                        mutation.value !in TuningPlanner.KNOWN_FAN_MODES
                    ) {
                        failures += "projected fan value mismatch"
                    }
                }
                TuningAxis.VENDOR_MODE -> {
                    if (
                        mutation.logicalId != HardwareSnapshot.VENDOR_MODE_LOGICAL_ID ||
                        mutation.canonicalPath != HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH
                    ) {
                        failures += "projected vendor mode target outside the canonical key"
                    }
                    if (mutation.kind != LogicalMutationKind.SETTINGS_PUT) {
                        failures += "projected vendor mode mutation kind invalid"
                    }
                    if (
                        mutation.value == null ||
                        mutation.value != plan.vendorMode ||
                        mutation.value !in TuningPlanner.KNOWN_VENDOR_MODES
                    ) {
                        failures += "projected vendor mode value mismatch"
                    }
                }
            }
        }

        val projectedCpuIds = projection
            .filter { it.axis == TuningAxis.CPU }
            .mapNotNull { CPU.matchEntire(it.logicalId)?.groupValues?.get(1)?.toIntOrNull() }
            .toSet()
        if (projectedCpuIds != plan.cpuMaxKhz.keys) {
            failures += "projected CPU set mismatch vs appliedPlan"
        }
        val projectedGpuIds = projection
            .filter { it.axis == TuningAxis.GPU }
            .map { it.logicalId }
            .toSet()
        if (plan.gpuMaxPowerLevel != null) {
            // EXACTLY ONE ceiling node, plus an optional MIN. The two ceilings are alternatives --
            // a projection carrying both would mean capture and emission disagreed about which
            // transport this chassis uses, and neither could then be trusted as the thing to watch.
            val ceilings = projectedGpuIds.count { it in HardwareSnapshot.GPU_CEILING_LOGICAL_IDS }
            if (ceilings != 1 || !GPU_IDS.containsAll(projectedGpuIds)) {
                failures += "schema-v4 GPU projection requires exactly one ceiling and permits only optional MIN"
            }
        } else if (projectedGpuIds.isNotEmpty()) {
            failures += "projected GPU set present without appliedPlan GPU target"
        }
        val projectedFans = projection.filter { it.axis == TuningAxis.FAN }
        if (plan.fanMode != null) {
            if (projectedFans.size != 1) failures += "projected fan set mismatch vs appliedPlan"
        } else if (projectedFans.isNotEmpty()) {
            failures += "projected fan present without appliedPlan fan target"
        }
        val projectedVendorModes = projection.filter { it.axis == TuningAxis.VENDOR_MODE }
        if (plan.vendorMode != null) {
            if (projectedVendorModes.size != 1) failures += "projected vendor mode set mismatch vs appliedPlan"
        } else if (projectedVendorModes.isNotEmpty()) {
            failures += "projected vendor mode present without appliedPlan vendor target"
        }
        return failures
    }
}

/**
 * Pre-command restore gate shared by the real PServer port and stateful JVM rigs.
 * Schema-v2+ validates the persisted historical plan + apply-order against live topology.
 * Schema-v1 never calls current [TuningPlanner] / formula values — it validates the durable
 * envelope, digests, live fingerprint/profile, allowlist, and known historic ordering only.
 */
internal object RestoreContextValidator {
    /** Historic profile ids known to schema-v1 apply-order reconstruction. */
    private val V1_KNOWN_PROFILES = setOf("stock", "quiet", "balanced", "performance")

    fun validate(
        record: TransactionJournalRecord,
        live: HardwareCapabilities,
        resolver: SysfsCanonicalResolver = FileSysfsCanonicalResolver,
    ): PlanVerification {
        val failures = mutableListOf<String>()
        val liveFingerprint = HardwareFingerprint.create(live)
        if (record.hardwareFingerprint != liveFingerprint) {
            failures += "hardwareFingerprint mismatch"
        }
        if (record.deviceProfile != live.deviceProfileId) {
            failures += "deviceProfile mismatch"
        }
        if (!SnapshotIntegrity.isValidSha256Hex(record.expectedTopologySha256)) {
            failures += "invalid expectedTopologySha256"
        }
        if (!SnapshotIntegrity.isValidSha256Hex(record.snapshotSha256)) {
            failures += "invalid snapshotSha256"
        }
        if (!SnapshotIntegrity.isValidSha256Hex(record.planHash)) {
            failures += "invalid planHash shape"
        }
        if (SnapshotIntegrity.snapshotSha256(record.snapshot) != record.snapshotSha256) {
            failures += "snapshotSha256 mismatch"
        }
        val captured = SnapshotIntegrity.identitiesOf(record.snapshot)
        if (SnapshotIntegrity.topologySha256(captured) != record.expectedTopologySha256) {
            failures += "expectedTopologySha256 mismatch"
        }
        validateLiveAllowlist(record, live, resolver, failures)
        if (
            record.schemaVersion <= TRANSACTION_JOURNAL_SCHEMA_V3 &&
            record.snapshot.cpuMinKhz.isNotEmpty()
        ) {
            failures += "legacy schema cannot carry CPU floor topology"
        }

        if (record.schemaVersion >= TRANSACTION_JOURNAL_SCHEMA_V2) {
            validateV2(record, live, resolver, failures)
        } else {
            validateV1(record, failures)
        }
        return PlanVerification(failures.isEmpty(), failures)
    }

    /**
     * Schema-v1: durable envelope + historic order coverage only. Never invoke current
     * TuningPlanner or require current formula/topology target values.
     */
    private fun validateV1(record: TransactionJournalRecord, failures: MutableList<String>) {
        if (record.profileId !in V1_KNOWN_PROFILES) {
            failures += "schema-v1 unknown historic profile: ${record.profileId}"
        }
        if (record.appliedPlan != null) {
            failures += "schema-v1 must not carry appliedPlan"
        }
        if (record.applyOrderLogicalIds.isNotEmpty()) {
            failures += "schema-v1 must not carry applyOrderLogicalIds"
        }
        if (record.consumedMgdeEnvelopeSha256 != null) {
            failures += "schema-v1 must not carry consumed MGDE truth"
        }
        if (record.appliedMutationProjection.isNotEmpty()) {
            failures += "schema-v1 must not carry appliedMutationProjection"
        }
        val order = HistoricApplyOrder.v1LogicalIds(record.profileId, record.snapshot)
        if (order.isEmpty()) {
            failures += "schema-v1 historic order empty"
            return
        }
        if (order.size != order.toSet().size) {
            failures += "schema-v1 historic order contains duplicates"
        }
        val snapshotIds = record.snapshot.nodes.map { it.logicalId }
        if (order.toSet() != snapshotIds.toSet()) {
            failures += "schema-v1 historic order must cover snapshot topology exactly"
        }
        if (order.size != snapshotIds.size) {
            failures += "schema-v1 historic order size mismatch vs snapshot"
        }
    }

    private fun validateV2(
        record: TransactionJournalRecord,
        live: HardwareCapabilities,
        resolver: SysfsCanonicalResolver,
        failures: MutableList<String>,
    ) {
        when (record.schemaVersion) {
            TRANSACTION_JOURNAL_SCHEMA_V2 -> {
                if (record.consumedMgdeEnvelopeSha256 != null) {
                    failures += "schema-v2 must not carry consumed MGDE truth"
                }
                if (record.appliedMutationProjection.isNotEmpty()) {
                    failures += "schema-v2 must not carry appliedMutationProjection"
                }
            }
            TRANSACTION_JOURNAL_SCHEMA_V3 -> {
                val consumed = record.consumedMgdeEnvelopeSha256
                if (consumed != null && !SnapshotIntegrity.isValidSha256Hex(consumed)) {
                    failures += "schema-v3 consumed MGDE digest invalid"
                }
                if (record.appliedMutationProjection.isNotEmpty()) {
                    failures += "schema-v3 must not carry appliedMutationProjection"
                }
            }
            TRANSACTION_JOURNAL_SCHEMA -> {
                val consumed = record.consumedMgdeEnvelopeSha256
                if (consumed != null && !SnapshotIntegrity.isValidSha256Hex(consumed)) {
                    failures += "schema-v4 consumed MGDE digest invalid"
                }
                failures += AppliedMutationProjectionValidator.validate(record)
            }
            TRANSACTION_JOURNAL_SCHEMA_V5 -> {
                val consumed = record.consumedMgdeEnvelopeSha256
                if (consumed != null && !SnapshotIntegrity.isValidSha256Hex(consumed)) {
                    failures += "schema-v5 consumed MGDE digest invalid"
                }
                if (record.guardianBinding == null) failures += "schema-v5 missing immutable Guardian binding"
                failures += AppliedMutationProjectionValidator.validate(record)
            }
            else -> failures += "unsupported plan-bearing journal schema ${record.schemaVersion}"
        }
        val persisted = record.appliedPlan
        if (persisted == null) {
            failures += "schema-v2 missing appliedPlan"
            return
        }
        if (persisted.profileId != record.profileId || persisted.packageName != record.targetPackage) {
            failures += "appliedPlan identity mismatch vs journal envelope"
        }
        val plan = persisted.toTuningPlan()
        if (PlanHash.of(plan) != record.planHash) {
            failures += "planHash mismatch"
        }
        validateV2ApplyOrder(record, failures)
        val plannedCpuIds = plan.cpuMaxKhz.keys
        val capturedCpuMinIds = record.snapshot.cpuMinKhz.keys
        if (!plannedCpuIds.containsAll(capturedCpuMinIds)) {
            failures += "CPU floor topology contains an unplanned policy"
        }
        capturedCpuMinIds.forEach { policyId ->
            val capturedMin = record.snapshot.cpuMinKhz.getValue(policyId)
            val targetMax = plan.cpuMaxKhz[policyId]
            if (targetMax == null || capturedMin <= targetMax) {
                failures += "CPU floor topology is not required for policy $policyId"
            }
        }
        val capturedCpuCeilingPath = capturedCpuCeilingPath(record, live, failures)
        val expected = SnapshotIntegrity.expectedTopology(
            plan,
            live,
            resolver,
            cpuMinPolicyIds = capturedCpuMinIds,
            cpuCeilingPath = capturedCpuCeilingPath,
            includeGpuMin = record.snapshot.gpuMinPowerLevel != null,
        )
        if (expected == null) {
            failures += "live expected topology unresolved"
            return
        }
        if (SnapshotIntegrity.topologySha256(expected) != record.expectedTopologySha256) {
            failures += "expectedTopologySha256 mismatch vs live plan topology"
        }
        val captured = SnapshotIntegrity.identitiesOf(record.snapshot)
        if (captured != expected) {
            failures += "snapshot topology identity mismatch"
        }
    }

    private fun validateV2ApplyOrder(record: TransactionJournalRecord, failures: MutableList<String>) {
        val order = record.applyOrderLogicalIds
        if (order.isEmpty()) {
            failures += "schema-v2 missing applyOrderLogicalIds"
            return
        }
        if (order.size != order.toSet().size) {
            failures += "applyOrderLogicalIds contains duplicates"
        }
        val snapshotIds = record.snapshot.nodes.map { it.logicalId }
        if (order.toSet() != snapshotIds.toSet()) {
            failures += "applyOrderLogicalIds must cover snapshot topology exactly"
        }
        if (order.size != snapshotIds.size) {
            failures += "applyOrderLogicalIds size mismatch vs snapshot"
        }
    }

    private fun validateLiveAllowlist(
        record: TransactionJournalRecord,
        live: HardwareCapabilities,
        resolver: SysfsCanonicalResolver,
        failures: MutableList<String>,
    ) {
        val allowed = SysfsPath.canonicalizeAllowlist(
            buildSet {
                live.cpuPolicies.forEach { policy ->
                    add("${policy.path}/scaling_max_freq")
                    add("${policy.path}/scaling_min_freq")
                }
                live.cpuCeilingPath?.let(::add)
                add(HardwareProbe.GPU_MIN_LEVEL)
                add(HardwareProbe.GPU_MAX_LEVEL)
                // The GPU ceiling transport, exactly as `cpuCeilingPath` is added above. Without it
                // the write is refused as "not in validated capability allowlist" -- correctly, since
                // this list is what bounds every node the daemon may touch.
                live.gpuCeilingPath?.let(::add)
            },
            resolver,
        )
        if (allowed == null) {
            failures += "live capability allowlist is noncanonical"
        }
        record.snapshot.nodes.forEach { node ->
            when (node.axis) {
                TuningAxis.FAN -> {
                    if (node.canonicalPath != HardwareSnapshot.FAN_SETTINGS_PATH) {
                        failures += "fan path not topology-canonical: ${node.canonicalPath}"
                    }
                }
                TuningAxis.VENDOR_MODE -> {
                    if (node.canonicalPath != HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH) {
                        failures += "vendor mode path not topology-canonical: ${node.canonicalPath}"
                    }
                }
                TuningAxis.CPU, TuningAxis.GPU -> {
                    val canonical = SysfsPath.canonicalize(node.canonicalPath, resolver)
                    if (canonical == null || canonical != node.canonicalPath) {
                        failures += "noncanonical journal path ${node.canonicalPath}"
                    } else if (allowed != null && canonical !in allowed) {
                        failures += "journal path outside live topology: $canonical"
                    }
                }
            }
        }
    }

    /**
     * Recovery remains compatible with an already-authenticated legacy per-policy
     * ceiling record while new Odin records bind the shared QoS path. Mixing the two
     * transports in one snapshot is rejected.
     */
    private fun capturedCpuCeilingPath(
        record: TransactionJournalRecord,
        live: HardwareCapabilities,
        failures: MutableList<String>,
    ): String? {
        val nodes = record.snapshot.nodes.filter { node ->
            node.axis == TuningAxis.CPU && node.logicalId.endsWith(":scaling_max_freq")
        }
        if (nodes.isEmpty()) return null
        val policies = live.cpuPolicies.associateBy(CpuPolicy::id)
        val legacy = nodes.all { node ->
            val policyId = CpuCeilingTransport.policyId(node.logicalId)
            val policy = policyId?.let(policies::get)
            policy != null && node.canonicalPath == "${policy.path}/scaling_max_freq"
        }
        if (legacy) return null
        val qosPath = live.cpuCeilingPath
        if (qosPath != null && nodes.all { it.canonicalPath == qosPath }) return qosPath
        failures += "CPU snapshot mixes or uses an unavailable ceiling transport"
        return null
    }
}

/** Resolves an absolute path to its canonical form (production: [java.io.File.canonicalPath]). */
internal fun interface SysfsCanonicalResolver {
    fun resolve(absolutePath: String): String
}

internal object FileSysfsCanonicalResolver : SysfsCanonicalResolver {
    override fun resolve(absolutePath: String): String = java.io.File(absolutePath).canonicalPath
}

/**
 * Canonical /sys path gate: lexical reject of traversal/backslash, then resolve via
 * [SysfsCanonicalResolver], then require the resolved result stays under `/sys`.
 */
internal object SysfsPath {
    // Kernel device names legitimately contain commas (for example
    // `3d00000.qcom,kgsl-3d0`). Traversal/backslash and post-resolution `/sys`
    // confinement remain independently enforced below.
    private val ALLOWED = Regex("^/sys/[A-Za-z0-9_.,/-]+$")

    /** Pure lexical pre-check — no filesystem resolution. */
    fun isLexicallyAcceptable(path: String): Boolean {
        val trimmed = path.trim()
        if (!trimmed.startsWith("/sys/")) return false
        if ("\\" in trimmed) return false
        val parts = trimmed.split('/').filter { it.isNotEmpty() }
        if (parts.firstOrNull() != "sys") return false
        if (parts.any { it == "." || it == ".." || it.isEmpty() }) return false
        val joined = "/" + parts.joinToString("/")
        return ALLOWED.matches(joined)
    }

    fun canonicalize(
        path: String,
        resolver: SysfsCanonicalResolver = FileSysfsCanonicalResolver,
    ): String? {
        val trimmed = path.trim()
        if (!isLexicallyAcceptable(trimmed)) return null
        val resolved = runCatching { resolver.resolve(trimmed) }.getOrNull()?.trim() ?: return null
        if (!resolved.startsWith("/sys/")) return null
        if ("\\" in resolved) return null
        if (!isLexicallyAcceptable(resolved)) return null
        return resolved
    }

    fun canonicalizeAllowlist(
        paths: Set<String>,
        resolver: SysfsCanonicalResolver = FileSysfsCanonicalResolver,
    ): Set<String>? {
        val out = linkedSetOf<String>()
        for (path in paths) {
            val canonical = canonicalize(path, resolver) ?: return null
            out += canonical
        }
        return out
    }
}

/** Apply-time plan target bounds: OPP membership, GPU index range, known fan modes. */
internal object PlanTargetValidator {
    fun validate(plan: TuningPlan, capabilities: HardwareCapabilities): List<String> {
        val failures = mutableListOf<String>()
        plan.cpuMaxKhz.forEach { (policyId, target) ->
            val policy = capabilities.cpuPolicies.firstOrNull { it.id == policyId }
            if (policy == null) {
                failures += "cpu policy $policyId missing from capabilities"
                return@forEach
            }
            if (target !in policy.availableFrequenciesKhz) {
                failures += "cpu policy $policyId target $target is not an OPP member"
            }
            if (target !in policy.minKhz..policy.maxKhz) {
                failures +=
                    "cpu policy $policyId target $target outside immutable range " +
                    "${policy.minKhz}..${policy.maxKhz}"
            }
        }
        plan.gpuMaxPowerLevel?.let { target ->
            val count = capabilities.gpuFrequenciesKhz.size
            if (count < 2) {
                failures += "gpu power-level count unavailable"
            } else if (target !in 0 until count) {
                failures += "gpu max_pwrlevel $target outside discovered count $count"
            }
        }
        plan.fanMode?.let { target ->
            if (target !in TuningPlanner.KNOWN_FAN_MODES) {
                failures += "fan_mode $target is not a known Mimir mode"
            }
            // The exact-Odin restriction on FAN_MANUAL was retired on 2026-08-23 so other AYN/Retroid
            // devices can be admitted by live capabilities. This was
            // the hard floor every other manual-fan gate was documented as being "in lockstep"
            // with -- it refused mode 6 on every apply, on every device but one. The live-axis
            // check immediately below is what still fails a manual target closed, and it is a
            // capability the device either has on this boot or does not.
            if (TuningAxis.FAN !in capabilities.supportedAxes || !capabilities.fanNodeAvailable) {
                failures += "fan target present without an admitted live fan axis"
            }
        }
        plan.vendorMode?.let { target ->
            if (target !in TuningPlanner.KNOWN_VENDOR_MODES) {
                failures += "vendor performance_mode $target is not a known Mimir mode"
            }
            // Capability, never a model: the probe admits the axis from the key itself, and while
            // [VENDOR_MODE_TRANSPORT_ENABLED] is false it never does, so a hand-built plan that
            // names a mode is refused here exactly as a fan target on a fanless chassis is.
            if (TuningAxis.VENDOR_MODE !in capabilities.supportedAxes || !capabilities.pServerAvailable) {
                failures += "vendor mode target present without an admitted live vendor-mode axis"
            }
        }
        return failures
    }
}

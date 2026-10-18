package com.gruning.mimir.tweaker.mgde

import com.gruning.mimir.tweaker.HardwareCapabilities
import com.gruning.mimir.tweaker.HardwareProbe
import com.gruning.mimir.tweaker.HardwareSnapshot
import com.gruning.mimir.tweaker.HandheldDeviceProfiles
import com.gruning.mimir.tweaker.restoreWriteMode
import com.gruning.mimir.tweaker.restoreWriteValue
import com.gruning.mimir.tweaker.LogicalMutationKind
import com.gruning.mimir.tweaker.PlanHash
import com.gruning.mimir.tweaker.RestoreContextValidator
import com.gruning.mimir.tweaker.RestoreGuardianEnvelope
import com.gruning.mimir.tweaker.RestoreGuardianFanDeleteEntry
import com.gruning.mimir.tweaker.RestoreGuardianFanPutEntry
import com.gruning.mimir.tweaker.RestoreGuardianSysfsEntry
import com.gruning.mimir.tweaker.RestoreGuardianVendorModePutEntry
import com.gruning.mimir.tweaker.RestoreMutationOrder
import com.gruning.mimir.tweaker.TuningPlanner
import com.gruning.mimir.tweaker.SnapshotIntegrity
import com.gruning.mimir.tweaker.TopologyNodeState
import com.gruning.mimir.tweaker.TRANSACTION_JOURNAL_SCHEMA
import com.gruning.mimir.tweaker.TransactionJournalRecord
import com.gruning.mimir.tweaker.TuningAxis
import java.security.MessageDigest

/** Complete immutable authority-owner observations. None of these values is read from an MGDE caller. */
internal data class MgdeExpectedIdentity(
    val helperBuildIdentity: String,
    val deviceProfile: String,
    val hardwareFingerprint: String,
    val bootIdentity: String,
    val generation: Long,
    val requestId: String,
    val transactionId: String,
    val taskNonce: String,
    val topologySha256: String,
    val snapshotSha256: String,
    val planSha256: String,
    val signerIdentity: String,
    val expectedDeadlineElapsedRealtimeMs: Long,
    val nowElapsedRealtimeMs: Long,
    val observedNoDirectBootClaim: Boolean,
)

/** Exact identity used by the one serialized MTJA-covered replay transition. */
internal data class MgdeConsumeIdentity(
    val bootIdentity: String,
    val generation: Long,
    val requestId: String,
    val transactionId: String,
    val envelopeSha256: String,
) {
    init {
        require(bootIdentity.isNotBlank()) { "MGDE consume boot identity missing" }
        require(generation > 0) { "MGDE consume generation must be positive" }
        require(requestId.isNotBlank()) { "MGDE consume request identity missing" }
        require(transactionId.isNotBlank()) { "MGDE consume transaction identity missing" }
        require(SnapshotIntegrity.isValidSha256Hex(envelopeSha256)) {
            "MGDE consume envelope digest invalid"
        }
    }
}

internal enum class MgdeConsumeOutcome {
    FIRST_CONSUME,
    ALREADY_CONSUMED,
    CONFLICTING_ENVELOPE,
    IDENTITY_MISMATCH,
    UNVERIFIABLE,
}

/**
 * Admission receives only this exact consume capability, never a general transaction
 * journal. Implementations must serialize read/modify/write and verify durable readback.
 */
internal fun interface MgdeAdmissionConsumePort {
    fun consumeExact(identity: MgdeConsumeIdentity): MgdeConsumeOutcome
}

/** Production default until the later live-wiring slice installs one journal-owned port. */
internal object UnavailableMgdeAdmissionConsumePort : MgdeAdmissionConsumePort {
    override fun consumeExact(identity: MgdeConsumeIdentity): MgdeConsumeOutcome =
        MgdeConsumeOutcome.UNVERIFIABLE
}

/**
 * Exact Stock and renderer projection minted only from an authenticated schema-v4 journal after
 * its complete restore context and immutable expected identity have matched.
 */
internal class MgdeExpectedStockProjection private constructor(
    private val record: TransactionJournalRecord,
    private val live: HardwareCapabilities,
) {
    private val nodes = record.snapshot.nodes.associateBy { it.logicalId }
    private val appliedPlan = checkNotNull(record.appliedPlan)
    private val appliedMutations = record.appliedMutationProjection.associateBy { it.logicalId }

    internal fun matchesExactStockAndPlan(envelope: RestoreGuardianEnvelope): Boolean {
        if (
            envelope.topologySha256 != record.expectedTopologySha256 ||
            envelope.snapshotSha256 != record.snapshotSha256 ||
            envelope.planSha256 != record.planHash
        ) {
            return false
        }
        val reverseOrder = RestoreMutationOrder.indexedPersisted(record.appliedMutationProjection)
            .map { it.value.logicalId to it.index }
        if (envelope.restoreEntries.size != nodes.size || envelope.restoreEntries.size != reverseOrder.size) {
            return false
        }
        return envelope.restoreEntries.zip(reverseOrder).all { (entry, expectedOrder) ->
            if (entry.logicalId != expectedOrder.first || entry.applyOrdinal != expectedOrder.second) return@all false
            val node = nodes[entry.logicalId] ?: return@all false
            val applied = appliedMutations[entry.logicalId] ?: return@all false
            when (entry) {
                is RestoreGuardianSysfsEntry ->
                    node.present &&
                        applied.axis == node.axis &&
                        applied.kind == LogicalMutationKind.SYSFS_WRITE &&
                        applied.expectPresent &&
                        applied.value != null &&
                        applied.canonicalPath == entry.canonicalSysfsPath &&
                        node.canonicalPath == entry.canonicalSysfsPath &&
                        // Still an EXACT check, just against the value a restore writes rather than the
                        // one it captured -- the two differ on the shared QoS node ([restoreWriteValue]).
                        // Nothing is relaxed: the expected value is still fully determined by the
                        // journalled node, so a forged envelope cannot name a value of its choosing.
                        restoreWriteValue(node) == entry.expectedStock.value &&
                        restoreWriteMode(node) == entry.expectedStock.mode &&
                        node.originalUid == entry.expectedStock.uid &&
                        node.originalGid == entry.expectedStock.gid &&
                        entry.rendererAppliedIntermediates == listOf(applied.value)
                is RestoreGuardianFanPutEntry ->
                    node.present &&
                        applied.axis == TuningAxis.FAN &&
                        applied.kind == LogicalMutationKind.SETTINGS_PUT &&
                        applied.expectPresent &&
                        applied.value != null &&
                        node.canonicalPath == HardwareSnapshot.FAN_SETTINGS_PATH &&
                        node.originalValue == entry.expectedStock.value &&
                        entry.rendererAppliedIntermediates == listOf(applied.value)
                is RestoreGuardianFanDeleteEntry ->
                    !node.present &&
                        applied.axis == TuningAxis.FAN &&
                        applied.kind == LogicalMutationKind.SETTINGS_PUT &&
                        applied.expectPresent &&
                        applied.value != null &&
                        node.canonicalPath == HardwareSnapshot.FAN_SETTINGS_PATH &&
                        entry.rendererAppliedIntermediates.isEmpty() &&
                        applied.value == appliedPlan.fanMode
                is RestoreGuardianVendorModePutEntry ->
                    // The honest origin the journalled node carries (HardwareProbe.captureVendorModeNode),
                    // and the exact mode the journalled plan applied -- an envelope cannot name a value
                    // of its own choosing for the player's setting.
                    node.present &&
                        applied.axis == TuningAxis.VENDOR_MODE &&
                        applied.kind == LogicalMutationKind.SETTINGS_PUT &&
                        applied.expectPresent &&
                        applied.value != null &&
                        node.canonicalPath == HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH &&
                        node.originalValue == entry.origin &&
                        applied.value == entry.applied &&
                        appliedPlan.vendorMode == entry.applied &&
                        entry.rendererAppliedIntermediates == listOf(applied.value)
            }
        }
    }

    internal fun compiledPolicyAllows(envelope: RestoreGuardianEnvelope): Boolean {
        val gpuIds = envelope.restoreEntries
            .filterIsInstance<RestoreGuardianSysfsEntry>()
            .map { it.logicalId }
            .filter {
                it == HardwareSnapshot.GPU_MIN_LOGICAL_ID || it in HardwareSnapshot.GPU_CEILING_LOGICAL_IDS
            }
            .toSet()
        // A GPU envelope is a ceiling, optionally preceded by a widened floor. Which ceiling depends
        // on the chassis (`GpuCeilingTransport`), but there is never more than one: they are
        // alternatives, and an envelope naming both would be restoring two nodes for one duty.
        val ceilings = gpuIds.count { it in HardwareSnapshot.GPU_CEILING_LOGICAL_IDS }
        if (gpuIds.isNotEmpty() && (ceilings != 1 || gpuIds.size > 2)) {
            return false
        }
        return envelope.restoreEntries.all { entry ->
            val applied = appliedMutations[entry.logicalId] ?: return@all false
            when (entry) {
                is RestoreGuardianSysfsEntry -> when {
                    CPU.matchEntire(entry.logicalId) != null -> {
                        val cpuMatch = CPU.matchEntire(entry.logicalId)!!
                        val id = cpuMatch.groupValues[1].toIntOrNull()
                            ?: return@all false
                        val nodeName = cpuMatch.groupValues[2]
                        val policyPath =
                            "/sys/devices/system/cpu/cpufreq/policy$id/$nodeName"
                        val sharedQosPath = live.cpuCeilingPath
                            ?.takeIf { nodeName == "scaling_max_freq" }
                        (entry.canonicalSysfsPath == policyPath ||
                            entry.canonicalSysfsPath == sharedQosPath) &&
                            applied.axis == TuningAxis.CPU &&
                            applied.kind == LogicalMutationKind.SYSFS_WRITE &&
                            applied.value == appliedPlan.cpuMaxKhz[id]
                    }
                    entry.logicalId == HardwareSnapshot.GPU_MIN_LOGICAL_ID ->
                        entry.canonicalSysfsPath == HardwareProbe.GPU_MIN_LEVEL &&
                            live.gpuFrequenciesKhz.size >= 2 &&
                            applied.axis == TuningAxis.GPU &&
                            applied.kind == LogicalMutationKind.SYSFS_WRITE &&
                            applied.value != null &&
                            applied.value >= 0
                    entry.logicalId == HardwareSnapshot.GPU_MAX_LOGICAL_ID ->
                        entry.canonicalSysfsPath == HardwareProbe.GPU_MAX_LEVEL &&
                            live.gpuFrequenciesKhz.size >= 2 &&
                            applied.axis == TuningAxis.GPU &&
                            applied.kind == LogicalMutationKind.SYSFS_WRITE &&
                            applied.value != null &&
                            applied.value == appliedPlan.gpuMaxPowerLevel &&
                            applied.value in live.gpuFrequenciesKhz.indices
                    else -> false
                }
                is RestoreGuardianFanPutEntry ->
                    HandheldDeviceProfiles.supportsFanMode(record.deviceProfile) &&
                        entry.logicalId == HardwareSnapshot.FAN_LOGICAL_ID &&
                        applied.axis == TuningAxis.FAN &&
                        applied.kind == LogicalMutationKind.SETTINGS_PUT &&
                        applied.value == appliedPlan.fanMode
                is RestoreGuardianFanDeleteEntry ->
                    HandheldDeviceProfiles.supportsFanMode(record.deviceProfile) &&
                        entry.logicalId == HardwareSnapshot.FAN_LOGICAL_ID &&
                        applied.axis == TuningAxis.FAN &&
                        applied.kind == LogicalMutationKind.SETTINGS_PUT &&
                        applied.value == appliedPlan.fanMode
                // No device-profile conjunct: the axis is admitted by capability (the key reads as a
                // known mode on a pserver device), never by model or family. What is bounded here is
                // the key, the verb and the two modes.
                is RestoreGuardianVendorModePutEntry ->
                    entry.logicalId == HardwareSnapshot.VENDOR_MODE_LOGICAL_ID &&
                        applied.axis == TuningAxis.VENDOR_MODE &&
                        applied.kind == LogicalMutationKind.SETTINGS_PUT &&
                        applied.canonicalPath == HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH &&
                        entry.origin in TuningPlanner.KNOWN_VENDOR_MODES &&
                        entry.applied in TuningPlanner.KNOWN_VENDOR_MODES &&
                        applied.value == appliedPlan.vendorMode
            }
        }
    }

    companion object {
        private val CPU = Regex("cpu:([0-9]+):(scaling_min_freq|scaling_max_freq)")

        internal fun fromAuthenticatedJournal(
            record: TransactionJournalRecord,
            live: HardwareCapabilities,
            expected: MgdeExpectedIdentity,
        ): MgdeExpectedStockProjection? {
            if (record.schemaVersion != TRANSACTION_JOURNAL_SCHEMA) return null
            if (!RestoreContextValidator.validate(record, live).ok) return null
            if (
                record.hardwareFingerprint != expected.hardwareFingerprint ||
                record.deviceProfile != expected.deviceProfile ||
                record.bootIdentity != expected.bootIdentity ||
                record.snapshot.hardwareFingerprint != expected.hardwareFingerprint ||
                record.snapshot.bootIdentity != expected.bootIdentity ||
                record.generation != expected.generation ||
                record.requestId != expected.requestId ||
                record.transactionId != expected.transactionId ||
                record.expectedTopologySha256 != expected.topologySha256 ||
                record.snapshotSha256 != expected.snapshotSha256 ||
                record.planHash != expected.planSha256 ||
                record.leaseDeadline != expected.expectedDeadlineElapsedRealtimeMs ||
                record.appliedPlan == null ||
                PlanHash.ofPersisted(record.appliedPlan) != record.planHash
            ) {
                return null
            }
            if (
                SnapshotIntegrity.snapshotSha256(record.snapshot) != record.snapshotSha256 ||
                SnapshotIntegrity.topologySha256(SnapshotIntegrity.identitiesOf(record.snapshot)) !=
                record.expectedTopologySha256 ||
                record.snapshot.nodes.size != record.snapshot.nodes.map { it.logicalId }.toSet().size ||
                record.applyOrderLogicalIds.size != record.snapshot.nodes.size ||
                record.applyOrderLogicalIds.toSet() != record.snapshot.nodes.map { it.logicalId }.toSet()
            ) {
                return null
            }
            return MgdeExpectedStockProjection(record, live)
        }
    }
}

internal enum class MgdeRejection {
    ACCEPT,
    ABSENT,
    SUBSTITUTED,
    REPLAYED,
    STALE,
    WRONG_BOOT,
    WRONG_SESSION,
    WRONG_SIGNER,
    EXPIRED,
    MALFORMED,
    OFF_ALLOWLIST,
    PRE_UNLOCK,
}

internal sealed interface MgdeAdmissionResult {
    data class Rejected(val reason: MgdeRejection, val diagnostic: String? = null) : MgdeAdmissionResult

    /** Deliberately inert: this package has no runner or controller dependency. */
    class Accepted internal constructor(val envelopeSha256: String) : MgdeAdmissionResult
}

/** Visible, stable P5 decision ordering; cryptographic verification remains authority-private. */
internal object MgdeAdmission {
    fun locatorFor(taskNonce: String): String = MessageDigest.getInstance("SHA-256")
        .digest(taskNonce.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { "%02x".format(it) }

    internal fun precheck(
        source: MgdeObservedSource,
        record: MgdeV2Record,
        identity: MgdeExpectedIdentity,
    ): MgdeAdmissionResult.Rejected? {
        val envelope = record.decodedEnvelope
        val expectedLocator = locatorFor(identity.taskNonce)
        if (
            source.attestedLocator() != expectedLocator ||
            source.attestedLocator() != locatorFor(envelope.taskNonce)
        ) {
            return MgdeAdmissionResult.Rejected(MgdeRejection.SUBSTITUTED)
        }
        if (envelope.bootIdentity != identity.bootIdentity) {
            return MgdeAdmissionResult.Rejected(MgdeRejection.WRONG_BOOT)
        }
        if (
            envelope.helperBuildIdentity != identity.helperBuildIdentity ||
            envelope.deviceProfile != identity.deviceProfile ||
            envelope.hardwareFingerprint != identity.hardwareFingerprint ||
            envelope.generation != identity.generation ||
            envelope.requestId != identity.requestId ||
            envelope.transactionId != identity.transactionId ||
            envelope.taskNonce != identity.taskNonce ||
            envelope.topologySha256 != identity.topologySha256 ||
            envelope.snapshotSha256 != identity.snapshotSha256 ||
            envelope.planSha256 != identity.planSha256
        ) {
            return MgdeAdmissionResult.Rejected(MgdeRejection.WRONG_SESSION)
        }
        if (envelope.tweakerCertificateSha256 != identity.signerIdentity) {
            return MgdeAdmissionResult.Rejected(MgdeRejection.WRONG_SIGNER)
        }
        if (envelope.hardDeadlineElapsedRealtimeMs != identity.expectedDeadlineElapsedRealtimeMs) {
            return MgdeAdmissionResult.Rejected(MgdeRejection.STALE)
        }
        if (envelope.hardDeadlineElapsedRealtimeMs <= identity.nowElapsedRealtimeMs) {
            return MgdeAdmissionResult.Rejected(MgdeRejection.EXPIRED)
        }
        return null
    }

    internal fun finish(
        record: MgdeV2Record,
        consumePort: MgdeAdmissionConsumePort,
        identity: MgdeExpectedIdentity,
        expectedStock: MgdeExpectedStockProjection,
    ): MgdeAdmissionResult {
        val envelope = record.decodedEnvelope
        if (!expectedStock.matchesExactStockAndPlan(envelope)) {
            return MgdeAdmissionResult.Rejected(MgdeRejection.MALFORMED)
        }
        if (!expectedStock.compiledPolicyAllows(envelope)) {
            return MgdeAdmissionResult.Rejected(MgdeRejection.OFF_ALLOWLIST)
        }
        val consume = runCatching {
            consumePort.consumeExact(
                MgdeConsumeIdentity(
                    bootIdentity = identity.bootIdentity,
                    generation = identity.generation,
                    requestId = identity.requestId,
                    transactionId = identity.transactionId,
                    envelopeSha256 = envelope.digestSha256(),
                ),
            )
        }.getOrDefault(MgdeConsumeOutcome.UNVERIFIABLE)
        return when (consume) {
            MgdeConsumeOutcome.FIRST_CONSUME ->
                MgdeAdmissionResult.Accepted(envelope.digestSha256())
            MgdeConsumeOutcome.ALREADY_CONSUMED ->
                MgdeAdmissionResult.Rejected(MgdeRejection.REPLAYED)
            MgdeConsumeOutcome.CONFLICTING_ENVELOPE ->
                MgdeAdmissionResult.Rejected(MgdeRejection.MALFORMED)
            MgdeConsumeOutcome.IDENTITY_MISMATCH ->
                MgdeAdmissionResult.Rejected(MgdeRejection.WRONG_SESSION)
            MgdeConsumeOutcome.UNVERIFIABLE ->
                MgdeAdmissionResult.Rejected(MgdeRejection.MALFORMED)
        }
    }
}

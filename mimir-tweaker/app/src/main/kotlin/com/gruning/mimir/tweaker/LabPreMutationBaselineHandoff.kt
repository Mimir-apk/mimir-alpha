package com.gruning.mimir.tweaker

import android.util.Log
import java.util.UUID

/**
 * LAB-only, process-memory one-shot evidence bridge. It is deliberately neither journal nor
 * Guardian authority: losing process memory destroys the offer and blocks the later observation.
 */
internal data class LabPreMutationBaselineBinding(
    val profileId: String,
    val packageName: String,
    val hardwareFingerprint: String,
    val buildFingerprint: String,
    val bootId: String,
    val generation: Long,
    val transactionId: String,
    val requestId: String,
    val planHash: String,
    val leaseDeadlineElapsedMs: Long,
)

internal data class LabPreMutationBaselineOffer(
    val vectors: List<CalibrationHardwareVector>,
    val binding: LabPreMutationBaselineBinding,
    val observationId: String,
)

/** Keeps the LAB-only pre-apply decision observable without widening any service API. */
internal object LabPreMutationBaselineApplyGate {
    fun permits(
        diagnosticsCompiled: Boolean,
        profileId: String,
        requiresCalibrationBaseline: Boolean = true,
        capture: () -> Boolean,
    ): Boolean =
        !diagnosticsCompiled ||
            profileId != LabPreMutationBaselineHandoff.QUIET_PROFILE ||
            !requiresCalibrationBaseline ||
            capture()
}

internal class LabPreMutationBaselineHandoff(
    private val nowElapsedMs: () -> Long,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    private data class Pending(
        val profileId: String,
        val packageName: String,
        val capabilities: HardwareCapabilities,
        val vectors: List<CalibrationHardwareVector>,
    )

    private var pending: Pending? = null
    private var attached: LabPreMutationBaselineBinding? = null

    @Synchronized
    fun captureBeforeApply(
        profileId: String,
        packageName: String,
        capabilities: HardwareCapabilities,
        fullTelemetry: () -> TelemetrySnapshot,
    ): Boolean {
        clear()
        if (profileId != QUIET_PROFILE) return false
        val stock = TuningPlanner().plan("stock", packageName, capabilities) ?: return false
        // Odin's vendor launcher briefly boosts CPU policy bounds when this LAB activity starts.
        // Let that transient settle before the three evidence reads; Production selection does not
        // use this diagnostic handoff and therefore gains no artificial apply delay.
        sleep(PRE_CAPTURE_SETTLE_MS)
        val vectors = buildList {
            repeat(CalibrationPreProfileBaselinePolicy.REQUIRED_READS) { index ->
                val vector = runCatching {
                    fullTelemetry().toCalibrationHardwareVector(nowElapsedMs(), capabilities.buildFingerprint)
                }.getOrNull() ?: return@buildList
                add(vector)
                if (index < CalibrationPreProfileBaselinePolicy.REQUIRED_READS - 1) sleep(DWELL_MS)
            }
        }
        val violations = CalibrationPreProfileBaselinePolicy.violations(vectors, stock)
        if (violations.isNotEmpty()) {
            Log.w(
                DIAGNOSTIC_TAG,
                "Stock baseline rejected: ${violations.joinToString(separator = " | ")}",
            )
            return false
        }
        pending = Pending(profileId, packageName, capabilities, vectors)
        return true
    }

    /** Called only from TransactionCoordinator's trusted APPLIED adoption callback. */
    @Synchronized
    fun onAppliedAdopt(record: TransactionJournalRecord): Boolean {
        val offer = pending ?: return false
        if (record.phase != TransactionPhase.APPLIED || record.profileId != offer.profileId ||
            record.targetPackage != offer.packageName ||
            record.hardwareFingerprint != HardwareFingerprint.create(offer.capabilities) ||
            record.bootIdentity != offer.vectors.last().bootId
        ) {
            clear()
            return false
        }
        attached = LabPreMutationBaselineBinding(
            profileId = offer.profileId,
            packageName = offer.packageName,
            hardwareFingerprint = record.hardwareFingerprint,
            buildFingerprint = offer.capabilities.buildFingerprint,
            bootId = record.bootIdentity,
            generation = record.generation,
            transactionId = record.transactionId,
            requestId = record.requestId,
            planHash = record.planHash,
            leaseDeadlineElapsedMs = record.leaseDeadline,
        )
        return true
    }

    @Synchronized
    fun consume(
        profileId: String,
        packageName: String,
        capabilities: HardwareCapabilities,
        appliedRecord: TransactionJournalRecord?,
    ): LabPreMutationBaselineOffer? {
        val pendingOffer = pending
        val binding = attached
        clear() // one-shot on every attempt, including mismatch/replay/expired/process-lifecycle caller use.
        if (pendingOffer == null || binding == null || nowElapsedMs() >= binding.leaseDeadlineElapsedMs ||
            profileId != binding.profileId || packageName != binding.packageName ||
            HardwareFingerprint.create(capabilities) != binding.hardwareFingerprint ||
            capabilities.buildFingerprint != binding.buildFingerprint ||
            appliedRecord?.phase != TransactionPhase.APPLIED ||
            appliedRecord.profileId != binding.profileId ||
            appliedRecord.targetPackage != binding.packageName ||
            appliedRecord.hardwareFingerprint != binding.hardwareFingerprint ||
            appliedRecord.generation != binding.generation ||
            appliedRecord.transactionId != binding.transactionId ||
            appliedRecord.requestId != binding.requestId ||
            appliedRecord.planHash != binding.planHash ||
            appliedRecord.bootIdentity != binding.bootId ||
            appliedRecord.leaseDeadline != binding.leaseDeadlineElapsedMs
        ) return null
        return LabPreMutationBaselineOffer(pendingOffer.vectors, binding, UUID.randomUUID().toString())
    }

    @Synchronized
    fun clear() {
        pending = null
        attached = null
    }

    companion object {
        const val QUIET_PROFILE = "quiet"
        const val PRE_CAPTURE_SETTLE_MS = 2_000L
        const val DWELL_MS = 250L
        private const val DIAGNOSTIC_TAG = "MimirLabBaseline"
    }
}

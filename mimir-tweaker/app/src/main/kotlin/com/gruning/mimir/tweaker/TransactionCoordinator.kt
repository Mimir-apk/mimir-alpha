package com.gruning.mimir.tweaker

import android.os.SystemClock
import java.util.UUID

internal data class PlanVerification(
    val ok: Boolean,
    val failures: List<String> = emptyList(),
    /** Optional precise pre-mutation admission code; null retains generic verification truth. */
    val code: String? = null,
)

internal interface HardwareStatePort {
    fun captureForPlan(plan: TuningPlan, capabilities: HardwareCapabilities): CaptureOutcome
    fun verify(plan: TuningPlan): Boolean
    fun verifyPlan(plan: TuningPlan): PlanVerification
    /**
     * Mandatory settled apply verification. The plan and every projected mutation
     * must remain true for the same complete observation window.
     */
    fun verifyAppliedPlan(
        plan: TuningPlan,
        mutations: List<LogicalMutation>,
    ): PlanVerification
    fun verifySnapshot(snapshot: HardwareSnapshot): Boolean
    fun verifySnapshotPlan(snapshot: HardwareSnapshot): PlanVerification
    /** Mandatory post-cooling/pre-compute CPU admission; no permissive default exists. */
    fun admitCpuTargetsAfterCooling(plan: TuningPlan): PlanVerification
    fun readback(mutation: LogicalMutation): Boolean
}

internal interface HardwareCommandPort {
    fun plannedMutations(plan: TuningPlan, snapshot: HardwareSnapshot): List<LogicalMutation>
    /** Literal reverse of the journal's persisted (or v1-historic) apply-order logical IDs. */
    fun restoreMutations(record: TransactionJournalRecord): List<LogicalMutation>
    fun execute(mutation: LogicalMutation): CheckedCommandResult
    /** Pre-command restore gate: fingerprint/profile/topology must match live capabilities. */
    fun validateRestore(record: TransactionJournalRecord): PlanVerification
}

/**
 * Exactly the pre-PREPARED journal identity the coordinator owns. No Guardian-only field
 * (helper build, certificate digest, task nonce, hard deadline) appears here: the coordinator
 * can never invent one, so it only ever asks whether a matching envelope is already durable.
 */
internal data class GuardianJournalIdentity(
    val generation: Long,
    val requestId: String,
    val transactionId: String,
    val hardwareFingerprint: String,
    val deviceProfile: String,
    val bootIdentity: String,
    val expectedTopologySha256: String,
    val snapshotSha256: String,
    val planSha256: String,
) {
    fun matches(envelope: RestoreGuardianEnvelope): Boolean =
        generation == envelope.generation &&
            requestId == envelope.requestId &&
            transactionId == envelope.transactionId &&
            hardwareFingerprint == envelope.hardwareFingerprint &&
            deviceProfile == envelope.deviceProfile &&
            bootIdentity == envelope.bootIdentity &&
            expectedTopologySha256 == envelope.topologySha256 &&
            snapshotSha256 == envelope.snapshotSha256 &&
            planSha256 == envelope.planSha256
}

/**
 * Exact pre-PREPARED material needed to render the Guardian's typed reverse-restore envelope.
 * The coordinator remains the sole capture/planning authority; the envelope port may sign this
 * projection but cannot choose a profile, target, mutation, Stock value, or deadline.
 */
internal data class GuardianPreparedEnvelopeRequest(
    val identity: GuardianJournalIdentity,
    val hardDeadlineElapsedRealtimeMs: Long,
    val snapshot: HardwareSnapshot,
    val plannedMutations: List<LogicalMutation>,
    val fanOwnershipTransitionsAllowed: Boolean = false,
) {
    init {
        require(hardDeadlineElapsedRealtimeMs > 0)
        require(plannedMutations.isNotEmpty())
        val plannedIds = plannedMutations.map { it.logicalId }
        val snapshotIds = snapshot.nodes.map { it.logicalId }
        require(plannedIds.size == plannedIds.toSet().size)
        require(plannedIds.toSet() == snapshotIds.toSet())
    }
}

/**
 * Pre-PREPARED Guardian identity seam for the schema-v5 production writer.
 *
 * The signed envelope is prepared in memory from the exact captured transaction identity. Its
 * immutable binding becomes durable in PREPARED before the separate arm store is touched, so a
 * crash cannot leave Guardian-only truth. Returning null is permitted only when the caller has
 * explicitly declared Guardian authority optional (for example, the LAB rollback harness).
 */
internal fun interface GuardianPreparedEnvelopePort {
    fun preparedEnvelope(identity: GuardianJournalIdentity): SignedRestoreGuardianEnvelope?

    /** Legacy pure fixtures keep receiving identity-only calls; production overrides this form. */
    fun preparedEnvelope(request: GuardianPreparedEnvelopeRequest): SignedRestoreGuardianEnvelope? =
        preparedEnvelope(request.identity)
}

/** Controller-facing arm authority. It has no hardware command surface. */
internal interface GuardianArmAuthorityPort {
    fun armToReady(
        signed: SignedRestoreGuardianEnvelope,
        expected: RestoreGuardianExpectedIdentity,
    ): GuardianArmAdmission
    fun presence(): GuardianArmStorePresence
    fun verifyTerminal(handle: GuardianReadyHandle): GuardianTerminalVerification
    fun disarm(handle: GuardianReadyHandle): Boolean
    fun restoreAndSettle(journal: TransactionJournal): GuardianCrossStoreSettlement =
        GuardianCrossStoreSettlement.Retained("GUARDIAN_RESTORE_AUTHORITY_UNAVAILABLE")
}

internal sealed interface GuardianCrossStoreSettlement {
    data object Settled : GuardianCrossStoreSettlement
    data class Retained(val code: String) : GuardianCrossStoreSettlement
}

internal object NoGuardianArmAuthority : GuardianArmAuthorityPort {
    override fun armToReady(
        signed: SignedRestoreGuardianEnvelope,
        expected: RestoreGuardianExpectedIdentity,
    ) = GuardianArmAdmission.ArmRejected(GuardianArmRejectReason.PREARM_PORT_FAILURE)
    override fun presence() = GuardianArmStorePresence.UNREADABLE
    override fun verifyTerminal(handle: GuardianReadyHandle) =
        GuardianTerminalVerification.Retained(GuardianTerminalRetainedReason.TRANSPORT_UNAVAILABLE)
    override fun disarm(handle: GuardianReadyHandle) = false
}

internal class ControllerGuardianArmAuthority(
    private val controller: RestoreGuardianController,
    private val armStore: RestoreGuardianArmStore,
) : GuardianArmAuthorityPort {
    override fun armToReady(
        signed: SignedRestoreGuardianEnvelope,
        expected: RestoreGuardianExpectedIdentity,
    ) = controller.armToReady(signed, expected)
    override fun presence() = armStore.presence()
    override fun verifyTerminal(handle: GuardianReadyHandle) = controller.verifyTerminal(handle)
    override fun disarm(handle: GuardianReadyHandle) = controller.disarm(handle)
}

/** Exact-or-nothing derivation. A mismatched envelope yields null and must fail the apply closed. */
internal object GuardianJournalBindingDerivation {
    fun derive(
        identity: GuardianJournalIdentity,
        envelope: RestoreGuardianEnvelope,
    ): GuardianJournalBinding? = if (!identity.matches(envelope)) {
        null
    } else {
        // PREPARED carries the immutable base only; READY/terminal digests stay absent until the
        // phase that first requires them, per GuardianJournalBindingValidator.
        GuardianJournalBinding(
            envelopeSha256 = envelope.digestSha256(),
            taskNonce = envelope.taskNonce,
            helperBuildIdentity = envelope.helperBuildIdentity,
            tweakerCertificateSha256 = envelope.tweakerCertificateSha256,
            hardDeadlineElapsedRealtimeMs = envelope.hardDeadlineElapsedRealtimeMs,
        )
    }
}

/** Single writer and crash-restorable transaction boundary for every protected hardware mutation. */
internal class TransactionCoordinator(
    private val planner: TuningPlanner,
    private val state: HardwareStatePort,
    private val commands: HardwareCommandPort,
    private val journal: TransactionJournal,
    private val hardwareWritesCompiled: Boolean,
    private val barrier: TransactionBarrier = NoopTransactionBarrier,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val requestWindowMs: Long = DEFAULT_REQUEST_WINDOW_MS,
    private val leaseWindowMs: Long = DEFAULT_LEASE_WINDOW_MS,
    /**
     * Pure callers are non-product by default. Production construction must set this true; a
     * missing prepared envelope then fails before any journal or hardware mutation rather than
     * falling back to the legacy schema.
     */
    private val requiresGuardianAuthority: Boolean = false,
    private val guardianPreparedEnvelope: GuardianPreparedEnvelopePort = GuardianPreparedEnvelopePort { null },
    private val guardianArmAuthority: GuardianArmAuthorityPort = NoGuardianArmAuthority,
    /** Daemon-local notification, including failed/throwing restores and internal apply rollback. */
    private val onRestoreAttemptFinished: () -> Unit = {},
) {
    /**
     * In-memory observation reference only. It is never journal or restore authority and
     * is invalidated before a later transaction can mutate hardware.
     */
    private var lastRestoredRecord: TransactionJournalRecord? = null
    /** In-process reference only; durable journal + arm records remain the sole recovery truth. */
    private var activeGuardianHandle: GuardianReadyHandle? = null

    /**
     * @param onAppliedAdopt optional service adoption callback. Invoked on the same
     * synchronized apply turn with the exact in-memory APPLIED [TransactionJournalRecord]
     * after durable APPLIED persist and late-abort check, before success return.
     * Returning false or throwing must exact-restore using that trusted record (not a
     * journal reload) and yield a non-APPLIED result. Null preserves default test/non-service
     * behavior with no adoption side effects.
     * @param onAdoptionRollback optional cleanup after a successful post-adopt exact-restore
     * when durable journal truth is Empty/Corrupt/mismatched/non-APPLIED. Clears provisional
     * supervisor/monitor state the coordinator cannot reach. Not invoked on adopt-false
     * (caller already cleared before returning false).
     */
    @Synchronized
    fun apply(
        profileId: String,
        packageName: String,
        capabilities: HardwareCapabilities,
        dryRun: Boolean,
        fanIntent: TuningPlanner.FanIntent = TuningPlanner.FanIntent.PROFILE_AUTO,
        requestId: String = idFactory(),
        requestDeadlineElapsedRealtimeMs: Long? = null,
        hardLeaseDeadlineElapsedRealtimeMs: Long? = null,
        isCancelled: () -> Boolean = { false },
        onAppliedAdopt: ((TransactionJournalRecord) -> Boolean)? = null,
        onAdoptionRollback: (() -> Unit)? = null,
        /**
         * LAB-only thermal-derate rung verification (WAVE2_THERMAL_DERATE_SPEC.md "Rung
         * verification programme"): when non-null, the plan's CPU/GPU targets come from this
         * compiled-in [ThermalDerateRung] (see [TuningPlanner.rungPlan]) instead of [profileId]'s
         * normal tier calculation. Defaults to null so every existing caller is byte-identical.
         * This does NOT touch [applyDerate] or any signed-manifest path -- it is the SAME
         * transaction/journal/Guardian machinery as an ordinary profile apply, just fed a
         * different plan.
         */
        rungOverride: ThermalDerateRung? = null,
    ): TransactionResult {
        // `var`, not `val`: the capture below may hand back CPU targets clamped up to the live
        // floor , and the clamped plan must be the one journalled.
        var plan = if (rungOverride != null) {
            planner.rungPlan(profileId, packageName, capabilities, rungOverride, fanIntent)
        } else {
            planner.plan(profileId, packageName, capabilities, fanIntent)
        } ?: return TransactionResult(false, false, false, "UNKNOWN_PROFILE", "Unknown profile: $profileId")
        // SAFE / write-disabled builds fail closed — never success DRY_RUN on product apply.
        if (!hardwareWritesCompiled) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "WRITE_GATE_DISABLED",
                message = "Hardware mutation is compile-time disabled in this posture.",
                plan = plan,
            )
        }
        if (dryRun) {
            return TransactionResult(
                success = true,
                applied = false,
                restored = false,
                code = "DRY_RUN",
                message = "Plan resolved; hardware was not changed.",
                plan = plan,
            )
        }
        if (capabilities.status == CapabilityStatus.UNSUPPORTED || !plan.calibrated) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "PLAN_NOT_ADMITTED",
                message = "This device/profile has not passed write calibration.",
                plan = plan,
            )
        }

        val admissionNow = clock()
        val effectiveRequestDeadline = requestDeadlineElapsedRealtimeMs
            ?: safeAdd(admissionNow, requestWindowMs)
        if (effectiveRequestDeadline <= admissionNow) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "REQUEST_EXPIRED",
                message = "Caller request deadline elapsed before capture.",
                plan = plan,
                requestId = requestId,
                requestDeadlineElapsedRealtimeMs = effectiveRequestDeadline,
            )
        }
        val effectiveHardLeaseDeadline = hardLeaseDeadlineElapsedRealtimeMs
            ?: safeAdd(admissionNow, leaseWindowMs)
        if (effectiveHardLeaseDeadline <= admissionNow) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "HARD_LEASE_EXPIRED",
                message = "Service hard lease elapsed before capture.",
                plan = plan,
                requestId = requestId,
                requestDeadlineElapsedRealtimeMs = effectiveRequestDeadline,
                hardLeaseDeadlineElapsedRealtimeMs = effectiveHardLeaseDeadline,
            )
        }

        when (val existing = journal.load()) {
            is JournalLoadResult.Corrupt -> {
                return TransactionResult(
                    false,
                    false,
                    false,
                    "JOURNAL_CORRUPT",
                    "Corrupt journal blocks new writes: ${existing.reason}",
                    plan,
                )
            }
            is JournalLoadResult.Loaded -> {
                if (existing.record.requestId == requestId) {
                    return idempotentApplyResult(existing.record, plan, capabilities)
                }
                return TransactionResult(
                    false,
                    false,
                    false,
                    "SESSION_ACTIVE",
                    "Restore or recover the active session first.",
                    plan,
                    existing.record.transactionId,
                ).withRecordIdentity(existing.record)
            }
            JournalLoadResult.Empty -> Unit
        }

        val targetFailures = PlanTargetValidator.validate(plan, capabilities)
        if (targetFailures.isNotEmpty()) {
            return TransactionResult(
                false,
                false,
                false,
                "PLAN_TARGET_INVALID",
                "Plan targets rejected before capture: ${targetFailures.joinToString("; ")}",
                plan,
            )
        }

        val capture = state.captureForPlan(plan, capabilities)
        // a target below its policy's live floor is CLAMPED UP to that
        // floor rather than refused, because refusing leaves the device at Stock and the active profile retains cooling control. Adopted here, before anything is
        // journalled, so the plan of record IS what gets written -- the applied contract and the
        // supervisor's drift check then compare like with like. Doing this any later is what
        // produced a restore/re-apply loop the first time it was attempted.
        if (capture.admittedCpuMaxKhz.isNotEmpty()) {
            plan = plan.copy(cpuMaxKhz = capture.admittedCpuMaxKhz)
        }
        if (!capture.ok || capture.snapshot == null) {
            return TransactionResult(
                false,
                false,
                false,
                capture.code ?: "CAPTURE_INCOMPLETE",
                "Required topology state missing before journal write: ${capture.failures.joinToString("; ")}",
                plan,
            )
        }
        if (capture.snapshot.cpuMinKhz.isNotEmpty()) {
            return TransactionResult(
                false,
                false,
                false,
                "CPU_FLOOR_OWNERSHIP_FORBIDDEN",
                "New transactions may observe CPU floors but must never capture or mutate them.",
                plan,
            )
        }
        val completeness = SnapshotCompleteness.validate(capture.snapshot)
        if (completeness.isNotEmpty()) {
            return TransactionResult(
                false,
                false,
                false,
                "CAPTURE_INCOMPLETE",
                "Capture completeness rejected before PREPARED: ${completeness.joinToString("; ")}",
                plan,
            )
        }
        // New applies are ceiling-only. Legacy floor-bearing journals remain readable
        // exclusively through restore/recovery and never re-enter this apply renderer.
        val expectedTopology = SnapshotIntegrity.expectedTopology(
            plan,
            capabilities,
            cpuMinPolicyIds = emptySet(),
            includeGpuMin = capture.snapshot.gpuMinPowerLevel != null,
        )
        if (expectedTopology == null) {
            return TransactionResult(
                false,
                false,
                false,
                "CAPTURE_INCOMPLETE",
                "Expected topology could not be resolved before PREPARED.",
                plan,
            )
        }
        val capturedIdentities = SnapshotIntegrity.identitiesOf(capture.snapshot)
        if (capturedIdentities != expectedTopology) {
            return TransactionResult(
                false,
                false,
                false,
                "CAPTURE_INCOMPLETE",
                "Captured topology identity mismatch before PREPARED.",
                plan,
            )
        }
        val expectedTopologySha256 = SnapshotIntegrity.topologySha256(expectedTopology)
        val snapshotSha256 = SnapshotIntegrity.snapshotSha256(capture.snapshot)
        barrier.check(TransactionBarrierPoint.AFTER_CAPTURE_BEFORE_JOURNAL, -1)

        when (val generation = journal.nextGeneration()) {
            is GenerationResult.Failed -> {
                return TransactionResult(
                    false,
                    false,
                    false,
                    "JOURNAL_GENERATION_FAILED",
                    "Generation allocation failed closed: ${generation.reason}",
                    plan,
                )
            }
            is GenerationResult.Ok -> {
                val now = clock()
                val transactionId = idFactory()
                val planned = commands.plannedMutations(plan, capture.snapshot)
                val applyOrderLogicalIds = planned.map { it.logicalId }
                val appliedMutationProjection = AppliedMutationProjection.from(planned)
                if (applyOrderLogicalIds.toSet() != capture.snapshot.nodes.map { it.logicalId }.toSet() ||
                    applyOrderLogicalIds.size != capture.snapshot.nodes.size
                ) {
                    return TransactionResult(
                        false,
                        false,
                        false,
                        "CAPTURE_INCOMPLETE",
                        "Apply-order logical IDs incomplete vs captured topology before PREPARED.",
                        plan,
                    )
                }
                val planSha256 = PlanHash.of(plan)
                val guardianIdentity = GuardianJournalIdentity(
                    generation = generation.value,
                    requestId = requestId,
                    transactionId = transactionId,
                    hardwareFingerprint = capture.snapshot.hardwareFingerprint,
                    deviceProfile = capabilities.deviceProfileId,
                    bootIdentity = capture.snapshot.bootIdentity,
                    expectedTopologySha256 = expectedTopologySha256,
                    snapshotSha256 = snapshotSha256,
                    planSha256 = planSha256,
                )
                val prepared = runCatching {
                    guardianPreparedEnvelope.preparedEnvelope(
                        GuardianPreparedEnvelopeRequest(
                            identity = guardianIdentity,
                            hardDeadlineElapsedRealtimeMs = effectiveHardLeaseDeadline,
                            snapshot = capture.snapshot,
                            plannedMutations = planned,
                            fanOwnershipTransitionsAllowed = manualFanPlanEligible(profileId, capabilities) &&
                                capture.snapshot.nodes.singleOrNull { it.axis == TuningAxis.FAN }?.present == true &&
                                plan.fanMode in FAN_OWNERSHIP_TARGETS,
                        ),
                    )
                }
                if (prepared.isFailure) {
                    return TransactionResult(
                        false,
                        false,
                        false,
                        "GUARDIAN_BINDING_UNAVAILABLE",
                        "Guardian envelope could not be prepared before PREPARED; zero commands executed.",
                        plan,
                        transactionId,
                    )
                }
                val preparedEnvelope = prepared.getOrNull()
                if (requiresGuardianAuthority && preparedEnvelope == null) {
                    // The daemon-side name for this refusal. The wrapper in SystemWideProfileTransition
                    // can overwrite the code the client finally sees, so this is the only guaranteed
                    // place the true reason is stated.
                    android.util.Log.w(
                        "MimirTweakerGuardian",
                        "apply refused: prepared envelope required but unavailable " +
                            "(key authority was not Available at service init)",
                    )
                    return TransactionResult(
                        false,
                        false,
                        false,
                        "GUARDIAN_BINDING_UNAVAILABLE",
                        "This posture requires a prepared Guardian envelope; zero commands executed.",
                        plan,
                        transactionId,
                    )
                }
                // Exact-or-nothing: an envelope that does not match this transaction's identity is
                // never downgraded to a legacy write — it fails the apply closed.
                val guardianBinding = preparedEnvelope?.let {
                    GuardianJournalBindingDerivation.derive(guardianIdentity, it.envelope)
                }
                if (preparedEnvelope != null && guardianBinding == null) {
                    return TransactionResult(
                        false,
                        false,
                        false,
                        "GUARDIAN_BINDING_MISMATCH",
                        "Prepared Guardian envelope did not match this transaction identity; zero commands executed.",
                        plan,
                        transactionId,
                    )
                }
                var record = TransactionJournalRecord(
                    schemaVersion = if (guardianBinding != null) {
                        TRANSACTION_JOURNAL_SCHEMA_V5
                    } else {
                        TRANSACTION_JOURNAL_SCHEMA
                    },
                    generation = generation.value,
                    requestId = requestId,
                    transactionId = transactionId,
                    hardwareFingerprint = capture.snapshot.hardwareFingerprint,
                    deviceProfile = capabilities.deviceProfileId,
                    planHash = planSha256,
                    profileId = plan.profileId,
                    targetPackage = plan.packageName,
                    phase = TransactionPhase.PREPARED,
                    creationTime = now,
                    requestDeadline = effectiveRequestDeadline,
                    leaseDeadline = effectiveHardLeaseDeadline,
                    bootIdentity = capture.snapshot.bootIdentity,
                    snapshot = capture.snapshot,
                    expectedTopologySha256 = expectedTopologySha256,
                    snapshotSha256 = snapshotSha256,
                    lastCompletedMutationBoundary = 0,
                    lastCompletedRestoreBoundary = 0,
                    appliedPlan = PersistedTuningPlan.from(plan),
                    applyOrderLogicalIds = applyOrderLogicalIds,
                    appliedMutationProjection = appliedMutationProjection,
                    guardianBinding = guardianBinding,
                )
                val projectionFailures = AppliedMutationProjectionValidator.validate(record)
                if (projectionFailures.isNotEmpty()) {
                    return TransactionResult(
                        false,
                        false,
                        false,
                        "RENDERER_PROJECTION_INVALID",
                        "Exact renderer projection rejected before PREPARED: " +
                            projectionFailures.joinToString("; "),
                        plan,
                        transactionId,
                    ).withRecordIdentity(record)
                }
                if (!journal.persist(record)) {
                    return TransactionResult(
                        false,
                        false,
                        false,
                        "JOURNAL_PERSIST_FAILED",
                        "PREPARED journal was not durable; apply was blocked.",
                        plan,
                        transactionId,
                    ).withRecordIdentity(record)
                }
                lastRestoredRecord = null
                barrier.check(TransactionBarrierPoint.AFTER_PREPARED, -1)

                if (record.schemaVersion == TRANSACTION_JOURNAL_SCHEMA_V5) {
                    val exactPrepared = preparedEnvelope
                        ?: return guardianRetained(record, plan, "Prepared Guardian envelope was lost in memory.")
                    val expected = GuardianArmExpectedIdentityDerivation.from(record)
                        ?: return guardianRetained(record, plan, "Durable Guardian binding could not reconstruct exact identity.")
                    record = record.copy(phase = TransactionPhase.ARMING)
                    if (!journal.persist(record)) {
                        return TransactionResult(
                            success = false,
                            applied = false,
                            restored = false,
                            code = "GUARDIAN_ARMING_PERSIST_FAILED",
                            message = "ARMING was not durable; PREPARED truth was retained and zero commands executed.",
                            plan = plan,
                            transactionId = transactionId,
                        ).withRecordIdentity(record)
                    }
                    barrier.check(TransactionBarrierPoint.AFTER_GUARDIAN_ARMING, -1)

                    val admission = runCatching {
                        guardianArmAuthority.armToReady(exactPrepared, expected)
                    }.getOrElse {
                        return guardianRetained(record, plan, "Guardian arm admission threw; durable truth was retained.")
                    }
                    val handle = when (admission) {
                        is GuardianArmAdmission.ArmRetained ->
                            return guardianRetained(record, plan, "Guardian arm was retained: ${admission.reason}.")
                        is GuardianArmAdmission.ArmRejected -> {
                            val absent = runCatching { guardianArmAuthority.presence() }
                                .getOrDefault(GuardianArmStorePresence.UNREADABLE) == GuardianArmStorePresence.ABSENT
                            val cleared = absent && journal.clear(record.transactionId)
                            return TransactionResult(
                                success = false,
                                applied = false,
                                restored = cleared,
                                code = if (cleared) "GUARDIAN_ARM_REJECTED" else "GUARDIAN_ARM_RETAINED",
                                message = if (cleared) {
                                    "Guardian rejected before READY; proven-empty arm and unmutated journal were cleared."
                                } else {
                                    "Guardian rejected but empty arm truth was not proven; both stores were retained."
                                },
                                plan = plan,
                                transactionId = transactionId,
                            ).withRecordIdentity(record)
                        }
                        is GuardianArmAdmission.Ready -> admission.handle
                    }
                    val exactHandle = handle.expectedIdentity == expected &&
                        handle.signedEnvelope.envelope.canonicalBytes()
                            .contentEquals(exactPrepared.envelope.canonicalBytes()) &&
                        handle.signedEnvelope.signature.contentEquals(exactPrepared.signature)
                    if (!exactHandle) {
                        return guardianRetained(record, plan, "Guardian READY handle did not match the durable transaction.")
                    }
                    val binding = checkNotNull(record.guardianBinding)
                    record = record.copy(
                        phase = TransactionPhase.GUARDIAN_READY,
                        guardianBinding = binding.copy(
                            readySessionSha256 = handle.readySessionSha256,
                        ),
                    )
                    if (!journal.persist(record)) {
                        return guardianRetained(record, plan, "Guardian reached READY but journal READY evidence was not durable.")
                    }
                    activeGuardianHandle = handle
                    barrier.check(TransactionBarrierPoint.AFTER_GUARDIAN_READY, -1)
                }

                record = record.copy(phase = TransactionPhase.APPLYING)
                if (!journal.persist(record)) {
                    val restored = failAndRestore(record, "Failed to persist APPLYING phase")
                    return TransactionResult(
                        false,
                        false,
                        restored.restored,
                        if (restored.restored) "JOURNAL_PERSIST_FAILED" else "RESTORE_FAILED",
                        "APPLYING journal was not durable; recovery attempted.",
                        plan,
                        transactionId,
                    ).withRecordIdentity(record)
                }
                barrier.check(TransactionBarrierPoint.AFTER_APPLYING_PHASE, -1)

                abortBeforeFirstMutation(record, plan, isCancelled)?.let { return it }

                val mutations = planned
                var postCoolingComputeAdmissionChecked = false
                for ((index, mutation) in mutations.withIndex()) {
                    if (index == 0) {
                        abortBeforeFirstMutation(record, plan, isCancelled)?.let { return it }
                    }
                    // The renderer writes the cooling side first (vendor mode, then fan, since
                    // 2026-10-13), but do not rely on CPU being its first compute axis: no GPU/CPU
                    // command may cross this post-cooling live-window admission when CPU targets
                    // exist. The vendor mode is a cooling-side write too, so the admission runs after
                    // both, immediately before the first compute command.
                    if (mutation.axis != TuningAxis.FAN &&
                        mutation.axis != TuningAxis.VENDOR_MODE &&
                        plan.cpuMaxKhz.isNotEmpty() &&
                        !postCoolingComputeAdmissionChecked
                    ) {
                        postCoolingComputeAdmissionChecked = true
                        val cpuAdmission = state.admitCpuTargetsAfterCooling(plan)
                        // The bounded post-cooling dwell may consume the caller's
                        // remaining window. Lifecycle expiry outranks a simultaneous
                        // admission failure, matching the normal late-abort truth.
                        abortAfterCommand(record, plan, isCancelled)?.let { return it }
                        if (!cpuAdmission.ok) {
                            return failApply(
                                record = record,
                                plan = plan,
                                failedIndex = index,
                                detail = "Post-cooling CPU admission failed: " +
                                    cpuAdmission.failures.joinToString("; "),
                                codeOnRestore = cpuAdmission.code ?: "CPU_WINDOW_UNSETTLED",
                            )
                        }
                    }
                    barrier.check(TransactionBarrierPoint.BEFORE_MUTATION, index)
                    val executed = runCatching { commands.execute(mutation) }
                        .getOrElse { error ->
                            CheckedCommandResult(
                                CheckedCommandStatus.AMBIGUOUS,
                                detail = "Mutation exception: ${error.message}",
                            )
                        }
                    // Late cancel/deadline after an uninterruptible command must restore this generation.
                    abortAfterCommand(record, plan, isCancelled)?.let { return it }
                    barrier.check(TransactionBarrierPoint.AFTER_APPLY_COMMAND_BEFORE_READBACK, index)
                    if (executed.status != CheckedCommandStatus.CONFIRMED) {
                        return failApply(
                            record,
                            plan,
                            index,
                            "Mutation ${mutation.logicalId}: ${executed.status} ${executed.detail}",
                        )
                    }
                    if (!state.readback(mutation)) {
                        return failApply(record, plan, index, "Readback mismatch for ${mutation.logicalId}")
                    }
                    record = record.copy(lastCompletedMutationBoundary = index + 1)
                    if (!journal.persist(record)) {
                        return failApply(record, plan, index, "Failed to persist mutation boundary ${index + 1}")
                    }
                    abortAfterCommand(record, plan, isCancelled)?.let { return it }
                    barrier.check(TransactionBarrierPoint.AFTER_MUTATION, index)
                }

                val verification = run {
                    barrier.check(TransactionBarrierPoint.BEFORE_APPLY_VERIFY, -1)
                    state.verifyAppliedPlan(plan, mutations)
                }
                if (!verification.ok) {
                    return failApply(
                        record,
                        plan,
                        (mutations.size - 1).coerceAtLeast(0),
                        "Apply verification failed: ${verification.failures.joinToString("; ")}",
                    )
                }
                barrier.check(TransactionBarrierPoint.AFTER_APPLY_VERIFIED_BEFORE_APPLIED, -1)
                // Late abort immediately after final verify, before APPLIED persist.
                abortAfterCommand(record, plan, isCancelled)?.let { return it }

                record = record.copy(phase = TransactionPhase.APPLIED, lastFailure = null)
                if (!journal.persist(record)) {
                    return failApply(
                        record,
                        plan,
                        (mutations.size - 1).coerceAtLeast(0),
                        "Failed to persist APPLIED phase",
                    )
                }
                barrier.check(TransactionBarrierPoint.AFTER_APPLIED, -1)
                // Late abort after APPLIED persist / before successful return: exact-restore this generation.
                abortAfterCommand(record, plan, isCancelled)?.let { return it }
                if (onAppliedAdopt != null) {
                    val adopted = runCatching { onAppliedAdopt(record) }.getOrDefault(false)
                    if (!adopted) {
                        // Exact-restore from the trusted in-memory APPLIED record — never reload.
                        val restored = failAndRestore(record, "Session adoption/monitor failed after APPLIED")
                        return TransactionResult(
                            success = false,
                            applied = false,
                            restored = restored.restored,
                            code = if (restored.restored) "SESSION_ADOPT_FAILED" else "RESTORE_FAILED",
                            message = if (restored.restored) {
                                "Adoption failed after APPLIED; exact restore completed from trusted record."
                            } else {
                                "Adoption failed after APPLIED; restore failed and journal retained. ${restored.message}"
                            },
                            plan = plan,
                            transactionId = transactionId,
                        ).withRecordIdentity(record)
                    }
                    // Post-adopt: durable journal must still equal the trusted APPLIED record.
                    if (!matchesTrustedApplied(journal.load(), record)) {
                        val restored = failAndRestore(
                            record,
                            "Post-adopt journal truth lost or mismatched after successful adoption",
                        )
                        if (restored.restored) {
                            runCatching { onAdoptionRollback?.invoke() }
                        }
                        return TransactionResult(
                            success = false,
                            applied = false,
                            restored = restored.restored,
                            code = if (restored.restored) "SESSION_ADOPT_FAILED" else "RESTORE_FAILED",
                            message = if (restored.restored) {
                                "Post-adopt journal mismatch; exact restore completed from trusted record."
                            } else {
                                "Post-adopt journal mismatch; restore failed and journal retained. ${restored.message}"
                            },
                            plan = plan,
                            transactionId = transactionId,
                        ).withRecordIdentity(record)
                    }
                    // Owner death/cancel can race the adoption callback itself. Re-check
                    // after the monitor is live and exact-restore this generation before
                    // returning any APPLIED success.
                    abortAfterCommand(record, plan, isCancelled)?.let { aborted ->
                        if (aborted.restored) {
                            runCatching { onAdoptionRollback?.invoke() }
                        }
                        return aborted
                    }
                }
                return TransactionResult(
                    true,
                    true,
                    false,
                    "APPLIED",
                    "Profile applied and verified.",
                    plan,
                    transactionId,
                ).withRecordIdentity(record)
            }
        }
    }

    /**
     * Reassert only the immutable CPU target of one exact active SHARED_QOS (or, since 2026-10-01,
     * POLICY_SCALING_MAX fallback) transport SYSTEM
     * session -- in practice, any 8-Elite-family chassis (family membership, not the
     * literal Odin id; see the `deviceProfile` check dropped below).
     *
     * This is deliberately not a second apply: it captures no state, allocates no generation,
     * changes no journal field, and never consults the current planner. The schema-v5 APPLIED
     * journal plus its Guardian fence remain the sole command authority.
     *
     * Historically the caller (`HardwareSessionSupervisor.execute`) exact-restored this
     * generation whenever this method returned anything except [TARGET_REPAIRED]. That fallback
     * could remove active cooling during CPU heat soak. To preserve cooling, the caller now
     * never falls back to restore on a repair failure of any kind returned from here -- it stays
     * on the active (possibly still CPU-drifted) session and lets its own bounded window/burst
     * budget retry on a later tick. This method's own return codes and their meanings are
     * otherwise unchanged; only what the caller does with a non-[TARGET_REPAIRED] result changed.
     */
    @Synchronized
    fun repairAppliedCpuQosBound(
        expectedGeneration: Long,
        expectedTransactionId: String,
        repairStillAuthorized: () -> Boolean,
    ): TransactionResult {
        if (!hardwareWritesCompiled) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "WRITE_GATE_DISABLED",
                message = "Exact target repair requires the hardware-write posture.",
                transactionId = expectedTransactionId,
                generation = expectedGeneration,
            )
        }
        val record = when (val loaded = journal.load()) {
            JournalLoadResult.Empty -> return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "NO_ACTIVE_SESSION",
                message = "No active transaction exists for target repair.",
                transactionId = expectedTransactionId,
                generation = expectedGeneration,
            )
            is JournalLoadResult.Corrupt -> return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "JOURNAL_CORRUPT",
                message = "Corrupt journal blocks exact target repair: ${loaded.reason}",
                transactionId = expectedTransactionId,
                generation = expectedGeneration,
            )
            is JournalLoadResult.Loaded -> loaded.record
        }
        if (record.generation != expectedGeneration ||
            record.transactionId != expectedTransactionId
        ) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "STALE_GENERATION",
                message = "Target repair refused a mismatched journal generation/transaction.",
                transactionId = record.transactionId,
            ).withRecordIdentity(record)
        }

        val plan = record.appliedPlan?.toTuningPlan()
        val persistedPlanHash = record.appliedPlan?.let { PlanHash.ofPersisted(it) }
        val contract = AppliedTargetContract.fromAuthenticatedAppliedRecord(record)
        val contextFailures = buildList {
            if (record.schemaVersion != TRANSACTION_JOURNAL_SCHEMA_V5) add("schema-v5 required")
            if (record.phase != TransactionPhase.APPLIED) add("APPLIED phase required")
            if (record.targetPackage != SystemWideProfileTransition.TARGET_PACKAGE_ID) {
                add("SYSTEM target sentinel required")
            }
            if (!repairStillAuthorized()) {
                add("active owner/session authorization lost")
            }
            if (clock() >= record.leaseDeadline - TARGET_REPAIR_MIN_REMAINING_LEASE_MS) {
                add("hard lease too close to expiry")
            }
            if (plan == null || persistedPlanHash != record.planHash) {
                add("persisted plan identity invalid")
            }
            if (AppliedMutationProjectionValidator.validate(record).isNotEmpty()) {
                add("applied mutation projection invalid")
            }
            if (GuardianJournalBindingValidator.validate(record).isNotEmpty()) {
                add("Guardian READY identity invalid")
            }
            if (!exactActiveGuardianMatches(record)) {
                add("exact active Guardian authority required")
            }
            // the `record.deviceProfile == ODIN_3_PROFILE` check this
            // context used to require is DROPPED, not widened -- `record` carries only a
            // persisted profile-id string, no socModel to resolve a chip family from, and the
            // check was always redundant with this SHARED_QOS leg: that transport can only be
            // selected for an 8-Elite-family device in the first place
            // (CpuCeilingTransport.selectedPath, now itself family-scoped).
            // 2026-10-01 (sticky apply): the genuine POLICY_SCALING_MAX fallback repairs too -- the
            // vendor perf server reasserting per-policy `scaling_max_freq` is exactly what the 09-30
            // Nova sessions were restored for. UNKNOWN (and a missing contract) still refuse.
            if (contract?.cpuQosTransport != CpuQosTransport.SHARED_QOS &&
                contract?.cpuQosTransport != CpuQosTransport.POLICY_SCALING_MAX
            ) {
                add("CPU ceiling transport must be SHARED_QOS or POLICY_SCALING_MAX")
            }
        }
        if (contextFailures.isNotEmpty() || plan == null || contract == null) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "TARGET_REPAIR_CONTEXT_REJECTED",
                message = "Exact target repair rejected: ${contextFailures.joinToString("; ")}",
                plan = plan,
                transactionId = record.transactionId,
            ).withRecordIdentity(record)
        }
        val restoreContext = commands.validateRestore(record)
        if (!restoreContext.ok) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "TARGET_REPAIR_CONTEXT_REJECTED",
                message = "Live target context rejected: ${restoreContext.failures.joinToString("; ")}",
                plan = plan,
                transactionId = record.transactionId,
            ).withRecordIdentity(record)
        }
        val allMutations = record.appliedMutationProjection.map { projected ->
            val snapshot = record.snapshot.nodes.singleOrNull { it.logicalId == projected.logicalId }
                ?: return TransactionResult(
                    success = false,
                    applied = false,
                    restored = false,
                    code = "TARGET_REPAIR_CONTEXT_REJECTED",
                    message = "Projected target lacks exact snapshot identity: ${projected.logicalId}",
                    plan = plan,
                    transactionId = record.transactionId,
                ).withRecordIdentity(record)
            LogicalMutation(
                logicalId = projected.logicalId,
                axis = projected.axis,
                canonicalPath = projected.canonicalPath,
                kind = projected.kind,
                value = projected.value,
                restoreMode = if (projected.axis == TuningAxis.CPU) {
                    CpuCeilingTransport.appliedMode(
                        path = projected.canonicalPath,
                        capturedMode = snapshot.originalMode,
                    )
                } else {
                    snapshot.originalMode
                },
                restoreUid = snapshot.originalUid,
                restoreGid = snapshot.originalGid,
                expectPresent = projected.expectPresent,
            )
        }
        // Every CPU mutation must ride the contract's own transport: all on the shared node, or all on
        // per-policy scaling_max_freq. A mixed projection is refused below by the size comparison.
        val contractIsSharedQos = contract.cpuQosTransport == CpuQosTransport.SHARED_QOS
        val cpuMutations = allMutations.filter { mutation ->
            mutation.axis == TuningAxis.CPU &&
                CpuCeilingTransport.isSharedQos(mutation.canonicalPath) == contractIsSharedQos
        }
        if (cpuMutations.isEmpty() ||
            cpuMutations.size != allMutations.count { it.axis == TuningAxis.CPU } ||
            cpuMutations.map { it.value }.any { it == null || it <= 0 }
        ) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "TARGET_REPAIR_CONTEXT_REJECTED",
                message = "Exact shared-QoS CPU projection is incomplete.",
                plan = plan,
                transactionId = record.transactionId,
            ).withRecordIdentity(record)
        }
        if (!matchesTrustedApplied(journal.load(), record)) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "STALE_GENERATION",
                message = "Target repair lost its exact APPLIED journal identity before commands.",
                plan = plan,
                transactionId = record.transactionId,
            ).withRecordIdentity(record)
        }

        cpuMutations.forEach { mutation ->
            if (!repairBoundaryOpen(record, repairStillAuthorized)) {
                return targetRepairFenceFailure(record, plan)
            }
            val executed = runCatching { commands.execute(mutation) }.getOrElse { error ->
                CheckedCommandResult(
                    status = CheckedCommandStatus.AMBIGUOUS,
                    detail = "Target repair exception: ${error.message}",
                )
            }
            if (executed.guardianTerminal) return guardianTerminalFenceResult(record, plan, "Target repair")
            if (!repairBoundaryOpen(record, repairStillAuthorized)) {
                return targetRepairFenceFailure(record, plan)
            }
            if (executed.status != CheckedCommandStatus.CONFIRMED || !state.readback(mutation)) {
                return TransactionResult(
                    success = false,
                    applied = false,
                    restored = false,
                    code = "TARGET_REPAIR_FAILED",
                    message = "CPU target repair failed for ${mutation.logicalId}: " +
                        "${executed.status} ${executed.detail}",
                    plan = plan,
                    transactionId = record.transactionId,
                ).withRecordIdentity(record)
            }
            if (!repairBoundaryOpen(record, repairStillAuthorized)) {
                return targetRepairFenceFailure(record, plan)
            }
        }
        val verified = state.verifyAppliedPlan(plan, allMutations)
        if (!repairBoundaryOpen(record, repairStillAuthorized) ||
            !verified.ok ||
            !matchesTrustedApplied(journal.load(), record)
        ) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "TARGET_REPAIR_FAILED",
                message = if (!repairBoundaryOpen(record, repairStillAuthorized)) {
                    "Target repair crossed its exact owner or hard-lease fence."
                } else if (!verified.ok) {
                    "Repaired target did not settle: ${verified.failures.joinToString("; ")}"
                } else {
                    "APPLIED journal identity changed during target repair."
                },
                plan = plan,
                transactionId = record.transactionId,
            ).withRecordIdentity(record)
        }
        return TransactionResult(
            success = true,
            applied = false,
            restored = false,
            code = TARGET_REPAIRED,
            message = "Authenticated system CPU target repaired and verified.",
            plan = plan,
            transactionId = record.transactionId,
        ).withRecordIdentity(record)
    }

    /**
     * S4.3 (OWED-2): reassert only the immutable fan-mode target of one
     * exact active session whose already-signed contract directly duty-drives the fan
     * ([TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES] -- SPORT or MANUAL). Shaped exactly like
     * [repairAppliedCpuQosBound] above -- generation/transaction-bound, no new capture, no new
     * generation, no journal amendment -- because this is the same class of operation: re-write
     * the UNCHANGED value the contract already authorizes, never a newly computed one. The value
     * written is always read back from [TransactionJournalRecord.appliedMutationProjection] /
     * [AppliedTargetContract.fanMode]; this method never hardcodes a fan mode constant.
     *
     * Deliberately does not also write PWM duty: duty is not part of the signed/journalled
     * transaction on this device (only `fan_mode` is -- see [HardwareSnapshot.FAN_LOGICAL_ID]),
     * and remains exclusively owned by the already-existing, already-bounded
     * `PServerFanDutyMutationPort`/`SmartFanThermalControl` tick loop in
     * `MimirTweakerService.fanControlDecision`, unchanged by this operation. That loop is skipped for
     * any tick whose supervisor decision is not [SupervisorDecision.Continue] -- see
     * `MimirTweakerService.startMonitor` -- so it resumes driving duty on the very next tick once
     * this reassert clears the drift and `fan_mode` no longer disagrees with the contract; at most
     * one tick's duty stays stale, still bounded within `0..period` throughout by that loop's own
     * existing invariants (`AndroidFanDutyPort`), never touched here.
     *
     * A caller must NOT exact-restore this generation on a non-[FAN_REASSERTED] result: unlike
     * [repairAppliedCpuQosBound]'s caller (`HardwareSessionSupervisor.execute`'s `is
     * RepairCpuTarget` branch, which does restore on failure), the `is RepairFanTarget` branch
     * deliberately never restores on any outcome here -- a fan-only cause must never cost the
     * compute profile. A MANUAL (mode 6) contract's live vendor mode may have drifted away from
     * mode 6's own one-shot Smart-handoff precondition; [HardwareCommandPort.execute] then fails
     * this closed exactly as it would reject a bare re-entry attempt outside the original handoff
     * transaction, which is the correct, honest outcome, not a bug this method routes around.
     */
    fun reassertAppliedFanBound(
        expectedGeneration: Long,
        expectedTransactionId: String,
        reassertStillAuthorized: () -> Boolean,
    ): TransactionResult = mutateAppliedFanBound(expectedGeneration, expectedTransactionId, null, reassertStillAuthorized)

    fun transitionAppliedFanBound(
        expectedGeneration: Long,
        expectedTransactionId: String,
        targetFanMode: Int,
        reassertStillAuthorized: () -> Boolean,
    ): TransactionResult = mutateAppliedFanBound(expectedGeneration, expectedTransactionId, targetFanMode, reassertStillAuthorized)

    @Synchronized
    fun fanOwnershipTransitionAdmitted(generation: Long, transactionId: String, targetFanMode: Int): Boolean {
        val record = (journal.load() as? JournalLoadResult.Loaded)?.record ?: return false
        val envelope = activeGuardianHandle?.signedEnvelope?.envelope ?: return false
        return record.generation == generation && record.transactionId == transactionId &&
            exactActiveGuardianMatches(record) && FanOwnershipAmendment.candidate(record, targetFanMode) != null &&
            FanOwnershipAmendment.authorized(record, envelope)
    }

    @Synchronized
    private fun mutateAppliedFanBound(
        expectedGeneration: Long,
        expectedTransactionId: String,
        targetFanMode: Int?,
        reassertStillAuthorized: () -> Boolean,
    ): TransactionResult {
        if (!hardwareWritesCompiled) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "WRITE_GATE_DISABLED",
                message = "Exact fan reassert requires the hardware-write posture.",
                transactionId = expectedTransactionId,
                generation = expectedGeneration,
            )
        }
        val record = when (val loaded = journal.load()) {
            JournalLoadResult.Empty -> return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "NO_ACTIVE_SESSION",
                message = "No active transaction exists for fan reassert.",
                transactionId = expectedTransactionId,
                generation = expectedGeneration,
            )
            is JournalLoadResult.Corrupt -> return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "JOURNAL_CORRUPT",
                message = "Corrupt journal blocks exact fan reassert: ${loaded.reason}",
                transactionId = expectedTransactionId,
                generation = expectedGeneration,
            )
            is JournalLoadResult.Loaded -> loaded.record
        }
        if (record.generation != expectedGeneration ||
            record.transactionId != expectedTransactionId
        ) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "STALE_GENERATION",
                message = "Fan reassert refused a mismatched journal generation/transaction.",
                transactionId = record.transactionId,
            ).withRecordIdentity(record)
        }

        val plan = record.appliedPlan?.toTuningPlan()
        val persistedPlanHash = record.appliedPlan?.let { PlanHash.ofPersisted(it) }
        val contract = AppliedTargetContract.fromAuthenticatedAppliedRecord(record)
        val candidate = if (targetFanMode == null) record else FanOwnershipAmendment.candidate(record, targetFanMode)
        val targetPlan = candidate?.appliedPlan?.toTuningPlan()
        val contextFailures = buildList {
            if (candidate == null || targetPlan == null) add("fan-only amendment invalid")
            if (targetFanMode != null && activeGuardianHandle?.signedEnvelope?.envelope?.restoreEntries
                    ?.filterIsInstance<RestoreGuardianFanPutEntry>()?.singleOrNull()
                    ?.rendererAppliedIntermediates != FAN_OWNERSHIP_TARGETS
            ) add("signed fan ownership transition authority required")
            if (record.schemaVersion != TRANSACTION_JOURNAL_SCHEMA_V5) add("schema-v5 required")
            if (record.phase != TransactionPhase.APPLIED) add("APPLIED phase required")
            // 2026-08-23: the `record.deviceProfile == ODIN_3_PROFILE` requirement is DROPPED, not
            // widened, on the same reasoning that retired its sibling in repairAppliedCpuQosBound
            // (see that context's own note above): `record` carries only a persisted profile-id
            // string with no socModel to resolve a chip family from, and the check was redundant
            // with a leg this context already has -- the "Mimir-duty-owned fan mode required" test
            // at the bottom of this list. A contract can only carry SPORT or MANUAL if it passed
            // every capability gate on the way in, and after the 2026-08-23 manual-fan update those
            // gates are family-wide.
            //
            // This gate is on the manual-fan path. `HardwareSessionSupervisor.admitFanReassert` emits `RepairFanTarget`
            // -> `GenerationBoundFanReasserter` -> here, so with manual fan family-wide, a Nova or
            // RP5 session holding mode 6 whose fan drifted would have been refused repair for its
            // device id alone -- every other leg passing -- and fallen through to Restore, losing
            // the session, exactly where the Odin reasserts and keeps it.
            if (!reassertStillAuthorized()) {
                add("active owner/session authorization lost")
            }
            if (clock() >= record.leaseDeadline - TARGET_REPAIR_MIN_REMAINING_LEASE_MS) {
                add("hard lease too close to expiry")
            }
            if (plan == null || persistedPlanHash != record.planHash) {
                add("persisted plan identity invalid")
            }
            if (AppliedMutationProjectionValidator.validate(record).isNotEmpty()) {
                add("applied mutation projection invalid")
            }
            if (GuardianJournalBindingValidator.validate(record).isNotEmpty()) {
                add("Guardian READY identity invalid")
            }
            if (!exactActiveGuardianMatches(record)) {
                add("exact active Guardian authority required")
            }
            if (contract == null || (targetFanMode == null && contract.fanMode !in TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES)) {
                add("Mimir-duty-owned fan mode required")
            }
        }
        if (contextFailures.isNotEmpty() || plan == null || contract == null || candidate == null || targetPlan == null) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "FAN_REASSERT_CONTEXT_REJECTED",
                message = "Exact fan reassert rejected: ${contextFailures.joinToString("; ")}",
                plan = plan,
                transactionId = record.transactionId,
            ).withRecordIdentity(record)
        }
        val restoreContext = commands.validateRestore(record)
        if (!restoreContext.ok) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "FAN_REASSERT_CONTEXT_REJECTED",
                message = "Live target context rejected: ${restoreContext.failures.joinToString("; ")}",
                plan = plan,
                transactionId = record.transactionId,
            ).withRecordIdentity(record)
        }
        val allMutations = candidate.appliedMutationProjection.map { projected ->
            val snapshot = record.snapshot.nodes.singleOrNull { it.logicalId == projected.logicalId }
                ?: return TransactionResult(
                    success = false,
                    applied = false,
                    restored = false,
                    code = "FAN_REASSERT_CONTEXT_REJECTED",
                    message = "Projected target lacks exact snapshot identity: ${projected.logicalId}",
                    plan = plan,
                    transactionId = record.transactionId,
                ).withRecordIdentity(record)
            LogicalMutation(
                logicalId = projected.logicalId,
                axis = projected.axis,
                canonicalPath = projected.canonicalPath,
                kind = projected.kind,
                value = projected.value,
                restoreMode = if (projected.axis == TuningAxis.CPU) {
                    CpuCeilingTransport.appliedMode(
                        path = projected.canonicalPath,
                        capturedMode = snapshot.originalMode,
                    )
                } else {
                    snapshot.originalMode
                },
                restoreUid = snapshot.originalUid,
                restoreGid = snapshot.originalGid,
                expectPresent = projected.expectPresent,
            )
        }
        val fanMutations = allMutations.filter {
            it.axis == TuningAxis.FAN && it.logicalId == HardwareSnapshot.FAN_LOGICAL_ID
        }
        if (fanMutations.size != 1 ||
            fanMutations.single().kind != LogicalMutationKind.SETTINGS_PUT ||
            fanMutations.single().value != (targetFanMode ?: contract.fanMode)
        ) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "FAN_REASSERT_CONTEXT_REJECTED",
                message = "Exact fan projection is incomplete or mismatched.",
                plan = plan,
                transactionId = record.transactionId,
            ).withRecordIdentity(record)
        }
        if (!matchesTrustedApplied(journal.load(), record)) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "STALE_GENERATION",
                message = "Fan reassert lost its exact APPLIED journal identity before commands.",
                plan = plan,
                transactionId = record.transactionId,
            ).withRecordIdentity(record)
        }

        val mutation = fanMutations.single()
        if (!repairBoundaryOpen(record, reassertStillAuthorized)) {
            return fanReassertFenceFailure(record, plan)
        }
        val executed = runCatching { commands.execute(mutation) }.getOrElse { error ->
            CheckedCommandResult(
                status = CheckedCommandStatus.AMBIGUOUS,
                detail = "Fan reassert exception: ${error.message}",
            )
        }
        // F2 (fan fence, 2026-10-14): gen 51 -- this reassert waited 23 s on the fence lock behind
        // the Guardian's own restore and came back `Guardian terminal fence closed`, which was then
        // reported as one more FAN_REASSERT_FAILED into a generation that no longer existed.
        if (executed.guardianTerminal) return guardianTerminalFenceResult(record, plan, "Fan reassert")
        if (!repairBoundaryOpen(record, reassertStillAuthorized)) {
            return fanReassertFenceFailure(record, plan)
        }
        if (executed.status != CheckedCommandStatus.CONFIRMED || !state.readback(mutation)) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "FAN_REASSERT_FAILED",
                message = "Fan mode reassert failed for ${mutation.logicalId}: " +
                    "${executed.status} ${executed.detail}",
                plan = plan,
                transactionId = record.transactionId,
            ).withRecordIdentity(record)
        }
        if (!repairBoundaryOpen(record, reassertStillAuthorized)) {
            return fanReassertFenceFailure(record, plan)
        }
        // A vendor fan entry can replay CPU/GPU policy. Reassert only changed readbacks of the
        // already authenticated compute targets; this never selects Stock or changes the plan.
        if (targetFanMode != null) {
            // The vendor mode is excluded: it is the player's to change, and a reading that no longer
            // matches means they did (a yield), which this repair must never fight by rewriting it.
            for (compute in allMutations.filter {
                it.axis != TuningAxis.FAN && it.axis != TuningAxis.VENDOR_MODE
            }) {
                if (!repairBoundaryOpen(record, reassertStillAuthorized)) return fanReassertFenceFailure(record, plan)
                if (state.readback(compute)) continue
                if (!repairBoundaryOpen(record, reassertStillAuthorized)) return fanReassertFenceFailure(record, plan)
                val repaired = runCatching { commands.execute(compute) }.getOrNull()
                if (!repairBoundaryOpen(record, reassertStillAuthorized)) return fanReassertFenceFailure(record, plan)
                if (repaired?.status != CheckedCommandStatus.CONFIRMED || !state.readback(compute)) {
                    return TransactionResult(false, false, false, "FAN_TRANSITION_COMPUTE_UNCONFIRMED",
                        "Vendor fan entry disturbed an active compute target; the session remains under Guardian protection.",
                        plan, record.transactionId).withRecordIdentity(record)
                }
            }
        }
        val verified = state.verifyAppliedPlan(targetPlan, allMutations)
        if (!repairBoundaryOpen(record, reassertStillAuthorized) ||
            !verified.ok ||
            !matchesTrustedApplied(journal.load(), record)
        ) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "FAN_REASSERT_FAILED",
                message = if (!repairBoundaryOpen(record, reassertStillAuthorized)) {
                    "Fan reassert crossed its exact owner or hard-lease fence."
                } else if (!verified.ok) {
                    "Reasserted fan target did not settle: ${verified.failures.joinToString("; ")}"
                } else {
                    "APPLIED journal identity changed during fan reassert."
                },
                plan = plan,
                transactionId = record.transactionId,
            ).withRecordIdentity(record)
        }
        if (targetFanMode != null && !journal.amendFanOwnership(candidate, activeGuardianHandle!!.signedEnvelope.envelope)) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "FAN_OWNERSHIP_PERSIST_FAILED",
                message = "Fan changed but its target amendment was not proven durable; compute remains active.",
                plan = targetPlan,
                transactionId = record.transactionId,
            ).withRecordIdentity(record)
        }
        return TransactionResult(
            success = true,
            applied = false,
            restored = false,
            code = if (targetFanMode == null) FAN_REASSERTED else "FAN_OWNERSHIP_CHANGED",
            message = "Authenticated system fan mode reasserted and verified.",
            plan = targetPlan,
            transactionId = record.transactionId,
        ).withRecordIdentity(record)
    }

    /**
     * WAVE2 Stage B (spec S2/S5): apply one SIGNED thermal-derate rung to the exact active Odin
     * SYSTEM session, in place.
     *
     * This is "select a different, already qualified plan," not "compute a more conservative
     * write": [rungPlan] must already be [TuningPlan.calibrated] -- sourced only from
     * [ProductQualificationAuthority.qualifiedDerateRungPlan] via a
     * [ProductQualificationResolver], never invented by a caller -- and the same capability/
     * calibration gate `apply()` enforces at its own `!plan.calibrated` check is enforced again
     * below; there is no separate, weaker admission for a derated write. The decode-time
     * dominance/monotonicity admission on [QualifiedDerateRung] (`QualificationManifest.kt`) is
     * what guarantees [rungPlan] can never exceed the profile's signed nominal on any axis --
     * this method trusts that guarantee via [TuningPlan.calibrated], exactly like `apply()`
     * trusts it today; a defensive re-check against the CURRENTLY applied ceiling is included
     * below purely as a second, independent line of defense, never as the primary gate.
     *
     * Unlike [repairAppliedCpuQosBound] (which reasserts an UNCHANGED value after vendor drift),
     * this WRITES NEW CPU+GPU ceilings and durably amends the APPLIED journal record's
     * plan/projection to match, so the next tick's [AppliedTargetContract] drift check compares
     * against the NEW ceiling instead of flagging this call's own write as drift -- which would
     * otherwise force a Restore on the very next tick and reproduce the exact defect WAVE 2 fixed.
     *
     * The fan axis is a hard exclusion, structurally: [rungPlan]'s own `fanMode` is never read by
     * this method, no FAN [LogicalMutation] is ever built or executed here, and the persisted
     * plan's `fanMode` is always carried forward byte-for-byte from the record already durable
     * before this call -- even if a manual fan override happens to be independently active.
     *
     * Zero commands execute if any pre-flight check fails. If a hardware mutation itself fails,
     * or the amended journal cannot be made durable, this method does NOT restore -- mirroring
     * [repairAppliedCpuQosBound]'s own posture of leaving cross-store recovery to the caller.
     * Deliberately unlike repair's caller-side fallback, [HardwareSessionSupervisor.execute] must
     * never follow a failed derate with a restore; the routine per-tick
     * [AppliedTargetContract] drift check remains the sole backstop for a hardware/journal
     * mismatch here, unchanged by this lane.
     */
    @Synchronized
    fun applyDerate(
        expectedGeneration: Long,
        expectedTransactionId: String,
        rungPlan: TuningPlan,
        capabilities: HardwareCapabilities,
        derateStillAuthorized: () -> Boolean,
    ): TransactionResult {
        if (!hardwareWritesCompiled) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "WRITE_GATE_DISABLED",
                message = "Thermal derate requires the hardware-write posture.",
                plan = rungPlan,
                transactionId = expectedTransactionId,
                generation = expectedGeneration,
            )
        }
        // Same gate apply() enforces at its own `!plan.calibrated` check -- no separate, weaker
        // admission exists for a derated write.
        if (capabilities.status == CapabilityStatus.UNSUPPORTED || !rungPlan.calibrated) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "PLAN_NOT_ADMITTED",
                message = "Thermal derate rung has not passed write calibration.",
                plan = rungPlan,
                transactionId = expectedTransactionId,
                generation = expectedGeneration,
            )
        }
        val record = when (val loaded = journal.load()) {
            JournalLoadResult.Empty -> return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "NO_ACTIVE_SESSION",
                message = "No active transaction exists for thermal derate.",
                plan = rungPlan,
                transactionId = expectedTransactionId,
                generation = expectedGeneration,
            )
            is JournalLoadResult.Corrupt -> return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "JOURNAL_CORRUPT",
                message = "Corrupt journal blocks thermal derate: ${loaded.reason}",
                plan = rungPlan,
                transactionId = expectedTransactionId,
                generation = expectedGeneration,
            )
            is JournalLoadResult.Loaded -> loaded.record
        }
        if (record.generation != expectedGeneration || record.transactionId != expectedTransactionId) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "STALE_GENERATION",
                message = "Thermal derate refused a mismatched journal generation/transaction.",
                transactionId = record.transactionId,
            ).withRecordIdentity(record)
        }

        fun rejected(reason: String) = TransactionResult(
            success = false,
            applied = false,
            restored = false,
            code = "THERMAL_DERATE_CONTEXT_REJECTED",
            message = "Thermal derate rejected: $reason",
            plan = rungPlan,
            transactionId = record.transactionId,
        ).withRecordIdentity(record)

        val currentPlan = record.appliedPlan?.toTuningPlan()
        val persistedPlanHash = record.appliedPlan?.let { PlanHash.ofPersisted(it) }
        val contextFailures = buildList {
            if (record.schemaVersion != TRANSACTION_JOURNAL_SCHEMA_V5) add("schema-v5 required")
            if (record.phase != TransactionPhase.APPLIED) add("APPLIED phase required")
            if (record.profileId != rungPlan.profileId) add("profile identity mismatch")
            if (record.targetPackage != rungPlan.packageName) add("package identity mismatch")
            if (record.deviceProfile != capabilities.deviceProfileId) add("device profile mismatch")
            if (record.hardwareFingerprint != HardwareFingerprint.create(capabilities)) {
                add("hardware fingerprint mismatch")
            }
            if (!derateStillAuthorized()) add("active owner/session authorization lost")
            if (clock() >= record.leaseDeadline - TARGET_REPAIR_MIN_REMAINING_LEASE_MS) {
                add("hard lease too close to expiry")
            }
            // Same live-target admission apply() runs before capture (PlanTargetValidator).
            // applyDerate never calls commands.plannedMutations(), so nothing else would catch an
            // out-of-table OPP or GPU index here.
            addAll(PlanTargetValidator.validate(rungPlan, capabilities))
            if (currentPlan == null || persistedPlanHash != record.planHash) {
                add("persisted plan identity invalid")
            }
            if (AppliedMutationProjectionValidator.validate(record).isNotEmpty()) {
                add("applied mutation projection invalid")
            }
            if (GuardianJournalBindingValidator.validate(record).isNotEmpty()) {
                add("Guardian READY identity invalid")
            }
            if (!exactActiveGuardianMatches(record)) add("exact active Guardian authority required")
            // Defensive second line of defense (see kdoc above): the primary guarantee is the
            // manifest's own decode-time dominance admission over the signed NOMINAL, trusted via
            // rungPlan.calibrated. This additionally refuses a write faster than whatever is
            // CURRENTLY applied, so a bug anywhere upstream cannot authorise a step back up.
            if (currentPlan != null) {
                if (rungPlan.cpuMaxKhz.keys != currentPlan.cpuMaxKhz.keys) {
                    add("CPU policy set mismatch")
                } else if (rungPlan.cpuMaxKhz.any { (id, khz) -> khz > currentPlan.cpuMaxKhz.getValue(id) }) {
                    add("derate target faster than the currently applied CPU ceiling")
                }
                if ((currentPlan.gpuMaxPowerLevel == null) != (rungPlan.gpuMaxPowerLevel == null)) {
                    add("GPU axis presence mismatch")
                } else if (
                    currentPlan.gpuMaxPowerLevel != null && rungPlan.gpuMaxPowerLevel != null &&
                    rungPlan.gpuMaxPowerLevel < currentPlan.gpuMaxPowerLevel
                ) {
                    add("derate target faster (lower max_pwrlevel) than the currently applied GPU ceiling")
                }
            }
        }
        if (contextFailures.isNotEmpty() || currentPlan == null) {
            return rejected(contextFailures.joinToString("; "))
        }

        // Build the exact mutation set: CPU + GPU-max targets move to rungPlan's signed values;
        // everything else (GPU-min floor, fan) is carried forward byte-for-byte, never touched.
        val newProjection = record.appliedMutationProjection.map { projected ->
            when {
                projected.axis == TuningAxis.CPU -> {
                    val policyId = CPU_POLICY_LOGICAL_ID.matchEntire(projected.logicalId)
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: return rejected("unrecognized CPU logical ID ${projected.logicalId}")
                    val target = rungPlan.cpuMaxKhz[policyId]
                        ?: return rejected("derate rung missing CPU policy $policyId")
                    projected.copy(value = target)
                }
                projected.logicalId == HardwareSnapshot.GPU_MAX_LOGICAL_ID -> {
                    val target = rungPlan.gpuMaxPowerLevel
                        ?: return rejected("derate rung missing GPU target")
                    projected.copy(value = target)
                }
                else -> projected
            }
        }
        val newAppliedPlan = PersistedTuningPlan(
            profileId = currentPlan.profileId,
            packageName = currentPlan.packageName,
            cpuMaxKhz = rungPlan.cpuMaxKhz.toSortedMap(),
            gpuMaxPowerLevel = rungPlan.gpuMaxPowerLevel,
            fanMode = currentPlan.fanMode,
            calibrated = true,
            // Carried forward unchanged: a derate moves CPU/GPU ceilings only. Dropping it would change
            // the plan hash and fail the projection validator (which holds the vendor-mode entry to
            // this plan) on the very rung that exists to protect the hardware.
            vendorMode = currentPlan.vendorMode,
        )
        val candidateRecord = record.copy(
            planHash = PlanHash.ofPersisted(newAppliedPlan),
            appliedPlan = newAppliedPlan,
            appliedMutationProjection = newProjection,
            lastFailure = null,
        )
        if (AppliedMutationProjectionValidator.validate(candidateRecord).isNotEmpty()) {
            return rejected("candidate derate projection failed validation")
        }

        val nodesByLogicalId = record.snapshot.nodes.associateBy { it.logicalId }
        val mutationsToExecute = newProjection.filter {
            it.axis == TuningAxis.CPU || it.logicalId == HardwareSnapshot.GPU_MAX_LOGICAL_ID
        }
        for (projected in mutationsToExecute) {
            val node = nodesByLogicalId[projected.logicalId]
                ?: return TransactionResult(
                    success = false,
                    applied = false,
                    restored = false,
                    code = "THERMAL_DERATE_FAILED",
                    message = "Derate target lacks exact snapshot identity: ${projected.logicalId}",
                    plan = rungPlan,
                    transactionId = record.transactionId,
                ).withRecordIdentity(record)
            if (!derateBoundaryOpen(record, derateStillAuthorized)) {
                return derateFenceFailure(record, rungPlan)
            }
            val mutation = LogicalMutation(
                logicalId = projected.logicalId,
                axis = projected.axis,
                canonicalPath = projected.canonicalPath,
                kind = projected.kind,
                value = projected.value,
                restoreMode = if (projected.axis == TuningAxis.CPU) {
                    CpuCeilingTransport.appliedMode(path = projected.canonicalPath, capturedMode = node.originalMode)
                } else {
                    node.originalMode
                },
                restoreUid = node.originalUid,
                restoreGid = node.originalGid,
                expectPresent = projected.expectPresent,
            )
            val executed = runCatching { commands.execute(mutation) }.getOrElse { error ->
                CheckedCommandResult(
                    CheckedCommandStatus.AMBIGUOUS,
                    detail = "Thermal derate exception: ${error.message}",
                )
            }
            if (executed.guardianTerminal) return guardianTerminalFenceResult(record, rungPlan, "Thermal derate")
            if (!derateBoundaryOpen(record, derateStillAuthorized)) {
                return derateFenceFailure(record, rungPlan)
            }
            if (executed.status != CheckedCommandStatus.CONFIRMED || !state.readback(mutation)) {
                return TransactionResult(
                    success = false,
                    applied = false,
                    restored = false,
                    code = "THERMAL_DERATE_FAILED",
                    message = "Thermal derate failed for ${mutation.logicalId}: ${executed.status} ${executed.detail}",
                    plan = rungPlan,
                    transactionId = record.transactionId,
                ).withRecordIdentity(record)
            }
        }

        if (!derateBoundaryOpen(record, derateStillAuthorized) || !matchesTrustedApplied(journal.load(), record)) {
            return derateFenceFailure(record, rungPlan)
        }
        // The derate amendment lane, NOT persist: this is the only write in the system that
        // lawfully changes the durable applied projection, and the ports layer admits it only
        // when every value moves slower. Routing it through persist is what made every rung
        // fail with THERMAL_DERATE_JOURNAL_PERSIST_FAILED after the hardware had already moved.
        if (!journal.amendApplied(candidateRecord)) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "THERMAL_DERATE_JOURNAL_PERSIST_FAILED",
                message = "Thermal derate hardware write succeeded but the amended journal was not durable.",
                plan = rungPlan,
                transactionId = record.transactionId,
            ).withRecordIdentity(record)
        }
        return TransactionResult(
            success = true,
            applied = true,
            restored = false,
            code = THERMAL_DERATE_APPLIED,
            message = "Thermal derate rung applied and verified.",
            plan = newAppliedPlan.toTuningPlan(),
            transactionId = record.transactionId,
        ).withRecordIdentity(candidateRecord)
    }

    private fun derateBoundaryOpen(
        record: TransactionJournalRecord,
        derateStillAuthorized: () -> Boolean,
    ): Boolean = derateStillAuthorized() &&
        clock() < record.leaseDeadline &&
        matchesTrustedApplied(journal.load(), record) &&
        exactActiveGuardianMatches(record)

    private fun derateFenceFailure(
        record: TransactionJournalRecord,
        plan: TuningPlan,
    ) = TransactionResult(
        success = false,
        applied = false,
        restored = false,
        code = "THERMAL_DERATE_FENCE_LOST",
        message = "Exact owner, lease, journal, or Guardian authority changed during thermal derate.",
        plan = plan,
        transactionId = record.transactionId,
    ).withRecordIdentity(record)

    private fun repairBoundaryOpen(
        record: TransactionJournalRecord,
        repairStillAuthorized: () -> Boolean,
    ): Boolean = repairStillAuthorized() &&
        clock() < record.leaseDeadline &&
        matchesTrustedApplied(journal.load(), record) &&
        exactActiveGuardianMatches(record)

    private fun targetRepairFenceFailure(
        record: TransactionJournalRecord,
        plan: TuningPlan,
    ) = TransactionResult(
        success = false,
        applied = false,
        restored = false,
        code = "TARGET_REPAIR_FENCE_LOST",
        message = "Exact owner, lease, journal, or Guardian authority changed during target repair.",
        plan = plan,
        transactionId = record.transactionId,
    ).withRecordIdentity(record)

    private fun fanReassertFenceFailure(
        record: TransactionJournalRecord,
        plan: TuningPlan,
    ) = TransactionResult(
        success = false,
        applied = false,
        restored = false,
        code = "FAN_REASSERT_FENCE_LOST",
        message = "Exact owner, lease, journal, or Guardian authority changed during fan reassert.",
        plan = plan,
        transactionId = record.transactionId,
    ).withRecordIdentity(record)

    /**
     * F2 (fan fence, 2026-10-14): the Guardian's terminal fence is closed for this generation, so
     * the Guardian has restored (or is restoring) Stock and the session is dead whatever [what] was
     * trying to do. Distinct from the `*_FENCE_LOST` codes above (authority changed under a live
     * session) and from `*_FAILED` (a real write failure): [HardwareSessionSupervisor.execute]
     * settles the session on this code through the ordinary restore reconciliation, which is the
     * bookkeeping of a restore that has already happened -- not the fan-only fallback to Restore
     * excluded by the thermal policy, because the hardware is already at Stock either way.
     */
    private fun guardianTerminalFenceResult(
        record: TransactionJournalRecord,
        plan: TuningPlan,
        what: String,
    ) = TransactionResult(
        success = false,
        applied = false,
        restored = false,
        code = GUARDIAN_TERMINAL_FENCE,
        message = "$what refused: the Guardian has already terminalized this generation; the session is settled.",
        plan = plan,
        transactionId = record.transactionId,
    ).withRecordIdentity(record)

    private fun exactActiveGuardianMatches(record: TransactionJournalRecord): Boolean {
        if (!requiresGuardianAuthority) return false
        val binding = record.guardianBinding ?: return false
        val expected = GuardianArmExpectedIdentityDerivation.from(record) ?: return false
        val handle = activeGuardianHandle ?: return false
        val envelope = handle.signedEnvelope.envelope
        return handle.expectedIdentity == expected &&
            handle.readySessionSha256 == binding.readySessionSha256 &&
            envelope.digestSha256() == binding.envelopeSha256 &&
            envelope.taskNonce == binding.taskNonce &&
            envelope.helperBuildIdentity == binding.helperBuildIdentity &&
            envelope.tweakerCertificateSha256 == binding.tweakerCertificateSha256 &&
            envelope.hardDeadlineElapsedRealtimeMs == binding.hardDeadlineElapsedRealtimeMs
    }

    @Synchronized
    fun restore(): TransactionResult {
        return when (val loaded = journal.load()) {
            JournalLoadResult.Empty ->
                TransactionResult(true, false, false, "NO_ACTIVE_SESSION", "Nothing to restore.")
            is JournalLoadResult.Corrupt ->
                TransactionResult(
                    false,
                    false,
                    false,
                    "JOURNAL_CORRUPT",
                    "Corrupt journal blocks restore clear: ${loaded.reason}",
                )
            is JournalLoadResult.Loaded -> restoreLoaded(loaded.record)
        }
    }

    /**
     * Generation-bound restore for supervisors/monitors. A mismatched generation or
     * transaction ID returns [STALE_GENERATION] with zero commands and zero journal clear.
     */
    @Synchronized
    fun restoreBound(expectedGeneration: Long, expectedTransactionId: String): TransactionResult {
        return when (val loaded = journal.load()) {
            JournalLoadResult.Empty ->
                TransactionResult(true, false, false, "NO_ACTIVE_SESSION", "Nothing to restore.")
            is JournalLoadResult.Corrupt ->
                TransactionResult(
                    false,
                    false,
                    false,
                    "JOURNAL_CORRUPT",
                    "Corrupt journal blocks restore clear: ${loaded.reason}",
                )
            is JournalLoadResult.Loaded -> {
                if (loaded.record.generation != expectedGeneration ||
                    loaded.record.transactionId != expectedTransactionId
                ) {
                    return TransactionResult(
                        success = false,
                        applied = false,
                        restored = false,
                        code = "STALE_GENERATION",
                        message = "Monitor restore refused; journal generation/transaction mismatch.",
                        transactionId = loaded.record.transactionId,
                    ).withRecordIdentity(loaded.record)
                }
                restoreLoaded(loaded.record)
            }
        }
    }

    /**
     * Observes the exact just-restored Stock record before a repeated lab drill may
     * start another mutation cycle. This method performs no commands and no journal
     * writes. Success means Stock stayed observed and the next CPU plan remained
     * admissible for two complete observation rounds; it does not claim that an
     * opaque vendor writer has ceased issuing Stock-over-Stock writes.
     */
    @Synchronized
    fun observeRestoredStockSettlement(
        expectedGeneration: Long,
        expectedTransactionId: String,
        nextPlan: TuningPlan? = null,
    ): StockSettlement {
        val startedAt = clock()
        fun outcome(
            settled: Boolean,
            code: String,
            message: String,
            cleanRounds: Int = 0,
            cleanWindowMs: Long = 0L,
            record: TransactionJournalRecord? = lastRestoredRecord,
        ) = StockSettlement(
            settled = settled,
            code = code,
            message = message,
            observedWindowMs = (clock() - startedAt).coerceAtLeast(0L),
            consecutiveCleanRounds = cleanRounds,
            consecutiveCleanWindowMs = cleanWindowMs,
            transactionId = record?.transactionId,
            generation = record?.generation,
        )

        if (!hardwareWritesCompiled) {
            return outcome(
                settled = false,
                code = "WRITE_GATE_DISABLED",
                message = "Post-restore settlement observation requires the lab write posture.",
                record = null,
            )
        }
        when (val loaded = journal.load()) {
            is JournalLoadResult.Corrupt -> {
                return outcome(
                    settled = false,
                    code = "JOURNAL_CORRUPT",
                    message = "Corrupt journal blocks Stock settlement observation: ${loaded.reason}",
                )
            }
            is JournalLoadResult.Loaded -> {
                return outcome(
                    settled = false,
                    code = "SESSION_ACTIVE",
                    message = "An active transaction blocks Stock settlement observation.",
                    record = loaded.record,
                )
            }
            JournalLoadResult.Empty -> Unit
        }
        val restored = lastRestoredRecord
        if (restored == null ||
            restored.generation != expectedGeneration ||
            restored.transactionId != expectedTransactionId
        ) {
            return outcome(
                settled = false,
                code = "STALE_GENERATION",
                message = "The requested restore generation is not the coordinator's latest verified restore.",
                record = restored,
            )
        }
        if (nextPlan == null || nextPlan.cpuMaxKhz.isEmpty()) {
            return outcome(
                settled = false,
                code = "CPU_SETTLEMENT_UNAVAILABLE",
                message = "The next drill plan has no CPU window to bind Stock settlement.",
                record = restored,
            )
        }

        var cleanRounds = 0
        var cleanWindowMs = 0L
        var lastCode = "STOCK_UNSETTLED"
        var lastMessage = "Stock did not reach the required consecutive clean observation rounds."
        repeat(SETTLEMENT_MAX_ROUNDS) {
            val roundStartedAt = clock()
            val stock = state.verifySnapshotPlan(restored.snapshot)
            val cpu = state.admitCpuTargetsAfterCooling(nextPlan)
            val roundWindowMs = (clock() - roundStartedAt).coerceAtLeast(0L)

            if (!cpu.ok && cpu.code in SPECIFIC_CPU_SETTLEMENT_CODES) {
                return outcome(
                    settled = false,
                    code = checkNotNull(cpu.code),
                    message = "Next-cycle CPU target is not admissible: ${cpu.failures.joinToString("; ")}",
                    cleanRounds = 0,
                    cleanWindowMs = 0L,
                    record = restored,
                )
            }
            when {
                !stock.ok -> {
                    cleanRounds = 0
                    cleanWindowMs = 0L
                    lastCode = "STOCK_UNSETTLED"
                    lastMessage = "Restored Stock moved during observation: ${stock.failures.joinToString("; ")}"
                }
                !cpu.ok -> {
                    cleanRounds = 0
                    cleanWindowMs = 0L
                    lastCode = cpu.code ?: HardwareProbe.CPU_WINDOW_UNSETTLED
                    lastMessage = "Next-cycle CPU window remained unsettled: ${cpu.failures.joinToString("; ")}"
                }
                else -> {
                    cleanRounds++
                    cleanWindowMs += roundWindowMs
                    if (cleanRounds >= SETTLEMENT_CLEAN_ROUNDS) {
                        return outcome(
                            settled = true,
                            code = "STOCK_SETTLED_OBSERVED",
                            message = "Restored Stock and the next-cycle CPU window remained observed.",
                            cleanRounds = cleanRounds,
                            cleanWindowMs = cleanWindowMs,
                            record = restored,
                        )
                    }
                }
            }
        }
        return outcome(
            settled = false,
            code = lastCode,
            message = lastMessage,
            cleanRounds = cleanRounds,
            cleanWindowMs = cleanWindowMs,
            record = restored,
        )
    }

    private fun restoreLoaded(initial: TransactionJournalRecord): TransactionResult {
        if (initial.schemaVersion == TRANSACTION_JOURNAL_SCHEMA_V5) {
            val restored = guardianRestoreAttempt(initial, "Explicit schema-v5 restore")
            return TransactionResult(
                success = restored.restored,
                applied = false,
                restored = restored.restored,
                code = if (restored.restored) "RESTORED" else "CROSS_STORE_RECONCILIATION_REQUIRED",
                message = restored.message,
                transactionId = initial.transactionId,
            ).withRecordIdentity(initial)
        }
        if (!hardwareWritesCompiled) {
            return TransactionResult(
                false,
                false,
                false,
                "WRITE_GATE_DISABLED",
                "A saved hardware journal exists; this build cannot restore it and will retain it.",
                transactionId = initial.transactionId,
            ).withRecordIdentity(initial)
        }
        // PREPARED with zero mutations: hardware unchanged — clear only.
        // APPLYING with boundary 0 may still have executed an uncommitted command after a crash;
        // that path must run a full snapshot restore rather than a bare clear.
        if (initial.phase == TransactionPhase.PREPARED && initial.lastCompletedMutationBoundary == 0) {
            val cleared = journal.clear(initial.transactionId)
            return TransactionResult(
                success = cleared,
                applied = false,
                restored = cleared,
                code = if (cleared) "RESTORED" else "RESTORE_FAILED",
                message = if (cleared) {
                    "Unused PREPARED journal cleared."
                } else {
                    "PREPARED journal clear failed; record retained."
                },
                transactionId = initial.transactionId,
            ).withRecordIdentity(initial)
        }

        val outcome = failAndRestore(
            initial,
            initial.lastFailure ?: "Recovery restore for phase ${initial.phase}",
            alreadyRestoring = initial.phase == TransactionPhase.RESTORING ||
                initial.phase == TransactionPhase.RESTORE_FAILED,
        )
        return TransactionResult(
            success = outcome.restored,
            applied = false,
            restored = outcome.restored,
            code = if (outcome.restored) "RESTORED" else "RESTORE_FAILED",
            message = outcome.message,
            transactionId = initial.transactionId,
        ).withRecordIdentity(initial)
    }

    private fun guardianRetained(
        record: TransactionJournalRecord,
        plan: TuningPlan,
        detail: String,
    ): TransactionResult = TransactionResult(
        success = false,
        applied = false,
        restored = false,
        code = "GUARDIAN_ARM_RETAINED",
        message = "$detail Zero hardware commands executed; cross-store truth was retained.",
        plan = plan,
        transactionId = record.transactionId,
    ).withRecordIdentity(record)

    private fun abortBeforeFirstMutation(
        record: TransactionJournalRecord,
        plan: TuningPlan,
        isCancelled: () -> Boolean,
    ): TransactionResult? {
        val reason = abortReason(record, isCancelled) ?: return null
        if (record.schemaVersion == TRANSACTION_JOURNAL_SCHEMA_V5) {
            val restored = guardianRestoreAttempt(record, reason.detail)
            return TransactionResult(
                success = restored.restored,
                applied = false,
                restored = restored.restored,
                code = if (restored.restored) reason.code else "GUARDIAN_ARM_RETAINED",
                message = if (restored.restored) {
                    "Request aborted before the first mutation; Guardian terminal settlement completed."
                } else {
                    restored.message
                },
                plan = plan,
                transactionId = record.transactionId,
            ).withRecordIdentity(record)
        }
        val cleared = journal.clear(record.transactionId)
        return TransactionResult(
            success = cleared,
            applied = false,
            restored = cleared,
            code = if (cleared) reason.code else "RESTORE_FAILED",
            message = if (cleared) {
                "Request aborted before first mutation; journal cleared with zero commands."
            } else {
                "Request aborted before first mutation; journal clear failed and record retained."
            },
            plan = plan,
            transactionId = record.transactionId,
        ).withRecordIdentity(record)
    }

    private fun abortAfterCommand(
        record: TransactionJournalRecord,
        plan: TuningPlan,
        isCancelled: () -> Boolean,
    ): TransactionResult? {
        val reason = abortReason(record, isCancelled) ?: return null
        // Late cancel/expiry must restore this exact transaction before the apply result returns.
        val restored = failAndRestore(record, reason.detail)
        return TransactionResult(
            success = restored.restored,
            applied = false,
            restored = restored.restored,
            code = if (restored.restored) reason.code else "RESTORE_FAILED",
            message = if (restored.restored) {
                "Late ${reason.code}: command returned and exact restore completed."
            } else {
                "Late ${reason.code}: restore failed; journal retained. ${restored.message}"
            },
            plan = plan,
            transactionId = record.transactionId,
        ).withRecordIdentity(record)
    }

    private fun abortReason(
        record: TransactionJournalRecord,
        isCancelled: () -> Boolean,
    ): AbortReason? {
        if (isCancelled()) {
            return AbortReason("REQUEST_CANCELLED", "Caller cancelled the in-flight request")
        }
        val now = clock()
        if (now >= record.requestDeadline) {
            return AbortReason("REQUEST_EXPIRED", "Request deadline elapsed")
        }
        if (now >= record.leaseDeadline) {
            return AbortReason("HARD_LEASE_EXPIRED", "Immutable hard lease deadline elapsed")
        }
        return null
    }

    private data class AbortReason(val code: String, val detail: String)

    private fun failApply(
        record: TransactionJournalRecord,
        plan: TuningPlan,
        failedIndex: Int,
        detail: String,
        codeOnRestore: String = "VERIFY_FAILED",
    ): TransactionResult {
        val restored = failAndRestore(record, detail)
        return TransactionResult(
            success = false,
            applied = false,
            restored = restored.restored,
            code = if (restored.restored) codeOnRestore else "RESTORE_FAILED",
            message = if (restored.restored) {
                "Apply failed after mutation boundary $failedIndex and was restored: $detail"
            } else {
                "Apply failed; journal retained in RESTORE_FAILED: $detail"
            },
            plan = plan,
            transactionId = record.transactionId,
        ).withRecordIdentity(record)
    }

    /**
     * Persist RESTORING with the causal failure before the first rollback command.
     * RESTORE_FAILED is only written when rollback/readback/verification/clear fails,
     * or when RESTORING itself cannot be proven durable and restore/clear also cannot.
     */
    private fun failAndRestore(
        record: TransactionJournalRecord,
        detail: String,
        @Suppress("UNUSED_PARAMETER") alreadyRestoring: Boolean = false,
    ): RestoreAttempt {
        if (record.schemaVersion == TRANSACTION_JOURNAL_SCHEMA_V5) {
            return guardianRestoreAttempt(record, detail)
        }
        // Every recovery attempt is a full idempotent snapshot replay. The restore boundary is
        // progress evidence for the current attempt only — never a permanent skip cursor.
        val restoring = record.copy(
            phase = TransactionPhase.RESTORING,
            lastFailure = detail,
            lastCompletedRestoreBoundary = 0,
        )
        val restoringDurable = journal.persist(restoring)
        if (restoringDurable) {
            barrier.check(TransactionBarrierPoint.AFTER_RESTORING_PHASE, -1)
        }
        val attempt = attemptFullRestore(restoring)
        if (attempt.restored) {
            return attempt
        }
        // Persistence of RESTORING failed and restore/clear was not proven — retain/recreate RESTORE_FAILED.
        if (!restoringDurable) {
            enterRestoreFailed(restoring, attempt.message.ifBlank { detail })
        }
        return attempt
    }

    private fun guardianRestoreAttempt(
        record: TransactionJournalRecord,
        detail: String,
    ): RestoreAttempt {
        try {
            // ARMING with no READY digest is the authenticated pre-mutation boundary. Let the
            // authority clear its STAGED arm first; persisting RESTORING here would falsely require
            // READY evidence that never existed and strand an otherwise provably unused transaction.
            if (
                record.phase == TransactionPhase.ARMING &&
                record.lastCompletedMutationBoundary == 0 &&
                record.guardianBinding?.readySessionSha256 == null
            ) {
                return guardianSettlementResult(
                    record,
                    guardianArmAuthority.restoreAndSettle(journal),
                )
            }
            val restoring = record.copy(
                phase = TransactionPhase.RESTORING,
                lastFailure = detail,
                lastCompletedRestoreBoundary = 0,
            )
            if (!journal.persist(restoring)) {
                return RestoreAttempt(
                    restored = false,
                    message = "Guardian RESTORING phase was not durable; cross-store truth retained.",
                    record = record,
                )
            }
            barrier.check(TransactionBarrierPoint.AFTER_RESTORING_PHASE, -1)
            return guardianSettlementResult(
                restoring,
                guardianArmAuthority.restoreAndSettle(journal),
            )
        } finally {
            onRestoreAttemptFinished()
        }
    }

    private fun guardianSettlementResult(
        record: TransactionJournalRecord,
        settlement: GuardianCrossStoreSettlement,
    ): RestoreAttempt {
        return when (settlement) {
            GuardianCrossStoreSettlement.Settled -> {
                val storesSettled = journal.load() is JournalLoadResult.Empty &&
                    runCatching { guardianArmAuthority.presence() }.getOrDefault(
                        GuardianArmStorePresence.UNREADABLE,
                    ) == GuardianArmStorePresence.ABSENT
                if (!storesSettled) {
                    return RestoreAttempt(
                        false,
                        "Guardian reported settlement without proven-empty journal and arm stores; truth retained.",
                        (journal.load() as? JournalLoadResult.Loaded)?.record ?: record,
                    )
                }
                lastRestoredRecord = record
                activeGuardianHandle = null
                RestoreAttempt(true, "Guardian restored exact Stock and settled both durable stores.", record)
            }
            is GuardianCrossStoreSettlement.Retained -> {
                val retained = (journal.load() as? JournalLoadResult.Loaded)?.record ?: record
                RestoreAttempt(
                    false,
                    "Guardian retained cross-store recovery truth: ${settlement.code}.",
                    retained,
                )
            }
        }
    }

    private fun enterRestoreFailed(record: TransactionJournalRecord, detail: String): TransactionJournalRecord {
        val failed = record.copy(phase = TransactionPhase.RESTORE_FAILED, lastFailure = detail)
        journal.persist(failed)
        return failed
    }

    private fun attemptFullRestore(record: TransactionJournalRecord): RestoreAttempt {
        try {
            val validation = commands.validateRestore(record)
            if (!validation.ok) {
                val detail = "Restore context rejected: ${validation.failures.joinToString("; ")}"
                val retained = enterRestoreFailed(record, detail)
                return RestoreAttempt(false, detail, retained)
            }

            // Conservative: always restore the COMPLETE captured snapshot, ignoring apply completion count.
            val mutations = commands.restoreMutations(record)
            var working = record
            for ((index, mutation) in mutations.withIndex()) {
                if (index < working.lastCompletedRestoreBoundary) continue
                barrier.check(TransactionBarrierPoint.BEFORE_RESTORE_MUTATION, index)
                val executed = runCatching { commands.execute(mutation) }
                    .getOrElse { error ->
                        CheckedCommandResult(
                            CheckedCommandStatus.AMBIGUOUS,
                            detail = "Restore exception: ${error.message}",
                        )
                    }
                barrier.check(TransactionBarrierPoint.AFTER_RESTORE_COMMAND_BEFORE_READBACK, index)
                if (executed.status != CheckedCommandStatus.CONFIRMED) {
                    val retained = enterRestoreFailed(
                        working,
                        "Restore mutation ${mutation.logicalId}: ${executed.status} ${executed.detail}",
                    )
                    return RestoreAttempt(false, "Restore command failed for ${mutation.logicalId}; journal retained.", retained)
                }
                if (!state.readback(mutation)) {
                    val retained = enterRestoreFailed(working, "Restore readback mismatch for ${mutation.logicalId}")
                    return RestoreAttempt(false, "Restore readback failed for ${mutation.logicalId}; journal retained.", retained)
                }
                working = working.copy(lastCompletedRestoreBoundary = index + 1)
                if (!journal.persist(working)) {
                    val retained = enterRestoreFailed(working, "Failed to persist restore boundary ${index + 1}")
                    return RestoreAttempt(false, "Restore boundary persist failed; journal retained.", retained)
                }
                barrier.check(TransactionBarrierPoint.AFTER_RESTORE_MUTATION, index)
            }
            barrier.check(TransactionBarrierPoint.BEFORE_RESTORE_VERIFY, -1)
            val restoreVerification = state.verifySnapshotPlan(working.snapshot)
            if (!restoreVerification.ok) {
                val detail = restoreVerification.failures.joinToString("; ")
                val retained = enterRestoreFailed(working, "Restore verification failed: $detail")
                return RestoreAttempt(
                    false,
                    if (detail.isBlank()) "Restore verification failed; journal retained." else "Restore failed: $detail",
                    retained,
                )
            }
            barrier.check(TransactionBarrierPoint.AFTER_RESTORE_VERIFIED_BEFORE_CLEAR, -1)
            if (working.schemaVersion == TRANSACTION_JOURNAL_SCHEMA_V5) {
                // Exact Stock readback is necessary but not sufficient to delete cross-store truth.
                // The authenticated Guardian terminal receipt must become durable in the journal,
                // then the arm store must clear, and only then may the journal clear. Until that live
                // terminal seam is admitted, retain both stores rather than creating an orphan arm.
                val retained = enterRestoreFailed(
                    working,
                    "Guardian terminal settlement is required after verified Stock restore",
                )
                return RestoreAttempt(
                    restored = false,
                    message = "Stock verified; Guardian terminal settlement is still required and journal truth was retained.",
                    record = retained,
                )
            }
            val cleared = journal.clear(working.transactionId)
            if (!cleared) {
                val retained = enterRestoreFailed(working, "Journal clear failed after verified restore")
                return RestoreAttempt(false, "Verified restore could not clear journal; recovery retained.", retained)
            }
            lastRestoredRecord = working
            return RestoreAttempt(true, "Stock state restored.", working)
        } finally {
            onRestoreAttemptFinished()
        }
    }

    /**
     * Exact durable match to the trusted in-memory APPLIED record: full data-class equality
     * (schema/generation/request/transaction, plan/envelope identity, hashes, snapshot,
     * persisted plan/order/boundaries) plus phase APPLIED.
     */
    private fun matchesTrustedApplied(
        loaded: JournalLoadResult,
        trusted: TransactionJournalRecord,
    ): Boolean {
        if (loaded !is JournalLoadResult.Loaded) return false
        val durable = loaded.record
        return durable.phase == TransactionPhase.APPLIED && durable == trusted
    }

    private fun idempotentApplyResult(
        record: TransactionJournalRecord,
        plan: TuningPlan,
        capabilities: HardwareCapabilities,
    ): TransactionResult {
        val planHash = PlanHash.of(plan)
        val fingerprint = HardwareFingerprint.create(capabilities)
        val matches = record.profileId == plan.profileId &&
            record.targetPackage == plan.packageName &&
            record.planHash == planHash &&
            record.hardwareFingerprint == fingerprint &&
            record.deviceProfile == capabilities.deviceProfileId
        if (!matches) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "REQUEST_ID_CONFLICT",
                message = "Duplicate requestId with mismatched session identity; zero commands executed.",
                plan = plan,
                transactionId = record.transactionId,
            ).withRecordIdentity(record)
        }
        return when (record.phase) {
            TransactionPhase.APPLIED -> TransactionResult(
                success = true,
                applied = true,
                restored = false,
                code = "APPLIED",
                message = "Duplicate requestId; session already applied.",
                plan = plan,
                transactionId = record.transactionId,
            ).withRecordIdentity(record)
            TransactionPhase.PREPARED,
            TransactionPhase.ARMING,
            TransactionPhase.GUARDIAN_READY,
            TransactionPhase.GUARDIAN_SETTLED,
            TransactionPhase.APPLYING,
            TransactionPhase.RESTORING,
            TransactionPhase.RESTORE_FAILED,
            -> TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "SESSION_ACTIVE",
                message = "Duplicate requestId is not APPLIED yet; phase=${record.phase}.",
                plan = plan,
                transactionId = record.transactionId,
            ).withRecordIdentity(record)
        }
    }

    private data class RestoreAttempt(
        val restored: Boolean,
        val message: String,
        val record: TransactionJournalRecord,
    )

    private fun TransactionResult.withRecordIdentity(record: TransactionJournalRecord): TransactionResult =
        copy(
            transactionId = record.transactionId,
            requestId = record.requestId,
            generation = record.generation,
            requestDeadlineElapsedRealtimeMs = record.requestDeadline,
            hardLeaseDeadlineElapsedRealtimeMs = record.leaseDeadline,
        )

    companion object {
        const val TARGET_REPAIRED = "TARGET_REPAIRED"
        const val FAN_REASSERTED = "FAN_REASSERTED"
        const val THERMAL_DERATE_APPLIED = "THERMAL_DERATE_APPLIED"
        /** F2: a repair/derate/reassert met the Guardian's closed terminal fence -- see [guardianTerminalFenceResult]. */
        const val GUARDIAN_TERMINAL_FENCE = "GUARDIAN_TERMINAL_FENCE"
        /** Matches applyDerate's own ceiling-only projection entries; scaling_min_freq (legacy
         * floor-bearing journals) deliberately never matches, so such a record is rejected. */
        private val CPU_POLICY_LOGICAL_ID = Regex("^cpu:([0-9]+):scaling_max_freq$")
        private const val TARGET_REPAIR_MIN_REMAINING_LEASE_MS = 2_000L
        private const val SETTLEMENT_CLEAN_ROUNDS = 2
        private const val SETTLEMENT_MAX_ROUNDS = 4
        // `CPU_TARGET_ABOVE_LIVE_CEILING` was the second member before removal: the OPP table now decides what may be admitted, and a target off that table is
        // snapped down rather than refused, so there is no ceiling verdict left for settlement to act on.
        private val SPECIFIC_CPU_SETTLEMENT_CODES = setOf(
            HardwareProbe.CPU_TARGET_BELOW_LIVE_FLOOR,
        )
        /** Elapsed-realtime apply window before the first mutation / late-command abort. */
        // Exact Odin manual-fan plans add a post-mode-transition CPU admission dwell.
        // Forty-five seconds remains bounded by Guardian while admitting the measured
        // checked-command path; callers may still provide a shorter explicit deadline.
        const val DEFAULT_REQUEST_WINDOW_MS = 45_000L
        /**
         * Initial renewable lease captured into the journal; live renewals stay memory-only.
         * It must not expire before the coordinator's own bounded apply request can settle.
         */
        const val DEFAULT_LEASE_WINDOW_MS = 45_000L
    }

    private fun safeAdd(value: Long, delta: Long): Long =
        if (delta > 0L && value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta
}

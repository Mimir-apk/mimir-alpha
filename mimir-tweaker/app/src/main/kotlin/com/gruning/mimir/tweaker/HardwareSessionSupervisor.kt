package com.gruning.mimir.tweaker

import android.os.PowerManager
import java.util.ArrayDeque

/** Default host policy for the service-owned active-session supervisor. */
internal data class SupervisorPolicy(
    val sampleIntervalMs: Long = 1_000L,
    val activationGraceMs: Long = 10_000L,
    val leaseWindowMs: Long = 30_000L,
    /** Intended client renewal cadence for PS-04D; not enforced as a public API here. */
    val clientRenewalCadenceMs: Long = 10_000L,
    val missingTelemetryLimit: Int = 3,
    /**
     * Consecutive TRIP-band samples required before a thermal trip fires (WAVE1A D1 fix).
     * WAVE2: still governs `androidThermalTrip` and `batteryTripDeciCExclusive` below -- spec
     * S3.3 changes CPU/GPU's *response*, not their sensitivity, but leaves android/battery's
     * filter mechanism unchanged too. Battery has no vendor trip points to recalibrate the
     * filter against either. CPU/GPU moved to
     * [thermalRollingWindowMs] below -- the real oscillating trip that motivated WAVE2 needed the
     * *mechanism*, not just the number, to change. See HardwareSessionSupervisor's
     * `presentThermalCondition`.
     */
    val thermalTripDebounceSamples: Int = 3,
    /**
     * Consecutive samples of Android `THERMAL_STATUS_SEVERE` before [THERMAL_ANDROID] derates.
     * At [sampleIntervalMs] = 1 s this is **45 seconds**, against 3 seconds for every other channel.
     *
     * **Keep the channel, raise the debounce.** Performance-clock loss was reported
     * mid-game on 08-24, and this is the only channel that could have fired at the
     * recorded temperatures. The reason it is singled out is that on this chassis SEVERE is a **skin**
     * threshold, and the vendor packs all three severity states into two degrees --
     * `mHotThrottlingThresholds` LIGHT 48.0 / MODERATE 49.0 / SEVERE 50.0. Captured on the Odin 3
     * under real load 2026-08-26: hottest SoC zone 76.9 C, fan at 100% and 9000 rpm, and skin
     * sensor `sys-therm-0` peaking at exactly **49.0 C** -- one degree under the trigger. A single
     * degree of drift on this plastic chassis, whose exhaust air was reported as *ambient* at full load,
     * could determine whether the clocks were reduced.
     *
     * **Why the channel remains armed.** This channel is the one that
     * caught the earlier incident on this exact hardware -- SEVERE with CPU peaking 98.4 C, never
     * crossing the 95 C trip in force that night (see this class's KDoc). It is a real safety net
     * for a case the temperature trips missed. So it stays armed, and only stops reacting to
     * transients.
     *
     * Deliberately NOT shared with the temperature channels: at 105 C the correct response is fast,
     * and slowing those down to match this would trade silicon safety for comfort. Skin heat is a
     * slow-moving quantity about how the case feels to hold; silicon heat is not.
     */
    val androidThermalTripDebounceSamples: Int = 45,
    /**
     * Silicon temperature (hottest classified CPU or GPU zone, whole C) that must ALSO be present
     * before the Android skin status is allowed to derate.
     *
     * **Device evidence 2026-08-26, measured mid-game on the Odin 3.** The skin trip fired at
     * `skin 52.2 C / hottest SoC 80.3 C`, took the prime cluster 3.072 -> 2.8416 GHz and the GPU
     * to 832 MHz, and it was not a transient -- SEVERE held past the 45 s debounce, so the debounce
     * was working and the trip was still wrong. Reported behavior: *Reported game temperatures were around 80 C with Auto fan, compared with roughly 100 C on stock; the raw capture is not distributed here*
     *
     * That is the whole problem in one line: **skin is a comfort signal and it was being used as a
     * silicon-danger signal.** A plastic chassis that conducts badly reaches 50 C skin long before
     * the die is in any trouble, and this device demonstrably runs 100 C on stock. Derating at
     * 80 C throws away performance to solve a problem that does not exist.
     *
     * 95 C is chosen because it is exactly the discriminator between the two real events on this
     * hardware. The incident this channel exists for -- and the reason it remains -- was SEVERE with **CPU peaking 98.4 C**, which the temperature trips of the day missed;
     * that still derates. Tonight's 80.3 C does not. The channel keeps the case it caught and loses
     * the case it should never have taken.
     *
     * Deliberately BELOW [cpuTripCExclusive]/[gpuTripCExclusive] (105 C): this is corroboration for
     * a signal that has already fired, not a second independent trip. Silicon alone still needs
     * 105 C, unchanged.
     */
    val androidThermalCorroborationC: Int = 95,
    /** OS status enum, not a temperature -- untouched by the WAVE1C/WAVE2 sysfs recalibration
     * below. WAVE2 S3.1 changes what a trip DOES (derate, never release) but not this value:
     * the platform signal remains unsuppressed. */
    val androidThermalTrip: Int = PowerManager.THERMAL_STATUS_SEVERE,
    /**
     * WAVE1C vendor calibration. Read from Odin 3 (`reference device`) sysfs, 2026-08-06 -- identical
     * across all eight `gpuss-0..7` zones and the `cpu-0-0-0` zone:
     * ```
     * gpuss-0..7:  passive=95C  passive=95C  hot=115C  passive=135C
     * cpu-0-0-0:   passive=95C  passive=108C  passive=110C  hot=115C  passive=135C
     * ```
     * The platform's own thermal framework begins its FIRST passive mitigation at 95 C on
     * both domains; `hot` is 115 C.
     *
     * WAVE2 policy, 2026-08-06 (WAVE2_THERMAL_DERATE_SPEC.md S3.3), supersedes WAVE1C's
     * 95 C with a 105 C trip that downclocks at the thermal safeguard. 105 sits 10 C below the vendor's `hot`
     * threshold (115 C) rather than exactly on the first passive-mitigation point; the same
     * capture's Performance run peaked at 98.4 C, so 105 does not fire on a healthy run, and its
     * post-rollback Stock regime (no Mimir guard at all) ran CPU to a 104.5 C *mean* -- so even
     * the unguarded baseline sits close to 105, not comfortably under it.
     *
     * Critically, this constant's value is secondary to WHAT a trip now does: WAVE2 S2 shows
     * raising this threshold alone would not have prevented the incident that motivated the
     * change -- that release was `THERMAL_ANDROID` at SEVERE, and CPU never crossed even the old
     * 95 C trip that night (peak 98.4 C, filtered out by the old debounce). The response-type
     * change (`SupervisorDecision.Derate`, never `Restore`, for all four thermal signals) is the
     * fix this number is subordinate to.
     * Pinned by `cpu and gpu trip constants are pinned 10 C below the vendor's hot threshold` in
     * HardwareSessionSupervisorTest.
     */
    val cpuTripCExclusive: Int = 105,
    val gpuTripCExclusive: Int = 105,
    /**
     * WAVE2 S3.4: replaces per-sample consecutive counting for the CPU/GPU channels only (see
     * `thermalTripDebounceSamples` above for why android/battery are unaffected). A 2-second
     * rolling mean per channel: `mean(samples in last thermalRollingWindowMs) > trip` derates one
     * rung. Measured 2026-08-06: CPU oscillated 83-102 C across ~300-350 ms samples, and
     * `thermalTripDebounceSamples = 3` (three *consecutive* samples) almost never survives that
     * oscillation -- the debounce added in WAVE1a to stop false trips became capable of
     * suppressing real ones. The 2 s window is a spec starting value, not a measured optimum.
     */
    val thermalRollingWindowMs: Long = 2_000L,
    /**
     * WAVE2 S3.3: an immediate band, independent of [thermalRollingWindowMs] -- any single valid
     * CPU or GPU sample above this derates two rungs at once, no dwell. 3 C below the vendor's
     * 115 C `hot` trip shared by both the `cpuss-*` and `gpuss-*` zones (see `cpuTripCExclusive`).
     * Not defined for android (a discrete OS enum, not a temperature) or battery (no vendor trip
     * points exist to key a critical band off).
     */
    val thermalCriticalC: Int = 112,
    /**
     * Unchanged from WAVE1A. The battery thermal zone exposes no vendor trip points to
     * recalibrate against, so there is no evidence here to move this value against.
     */
    val batteryTripDeciCExclusive: Int = 450,
    val restoreRetryInitialMs: Long = 1_000L,
    val restoreRetryMaxMs: Long = 30_000L,
    val restoreRetryMultiplier: Int = 2,
    /**
     * Sparse Odin vendor resets remain repairable. Policy 2026-08-13/14 (P0 thermal
     * safety) retired this comment's old second half ("a rapid writer fight restores Stock"): a
     * rapid writer fight now degrades to [SupervisorDecision.Continue] instead -- see
     * [admitTargetRepair]'s kdoc. Reverting to Stock stops the fan at exactly the moment
     * sustained drift (often heat-caused) makes it most dangerous to stop.
     */
    val targetRepairWindowMs: Long = 30_000L,
    val maxTargetRepairAttemptsPerWindow: Int = 4,
    val targetRepairBurstWindowMs: Long = 5_000L,
    val maxTargetRepairAttemptsPerBurst: Int = 2,
    /**
     * S4.3: a fan-ONLY drift (a charger plug/unplug rewriting `fan_mode` at
     * an unpredictable time is normal operation on this device, not tampering) re-asserts within
     * this same bounded-window/burst shape -- but unlike [targetRepairWindowMs]'s CPU repair,
     * exhaustion degrades to [SupervisorDecision.Continue], never [SupervisorDecision.Restore]:
     * a fan-only cause must never cost the compute profile, on the first attempt or the last.
     */
    val fanReassertWindowMs: Long = 30_000L,
    val maxFanReassertAttemptsPerWindow: Int = 4,
    val fanReassertBurstWindowMs: Long = 5_000L,
    val maxFanReassertAttemptsPerBurst: Int = 2,
)

internal enum class ActiveSessionScope {
    LAUNCH,
    SYSTEM,
    RECOVERY,
}

internal object ActiveSessionLeaseDeadline {
    /**
     *  Every SYSTEM session now reaches this same unconditional line, byte
     * for byte, whether or not it was admitted via a signed manifest or
     * `SupportedUncertifiedProfiles.admitsPlan`. Admission itself is unchanged and stays exactly
     * as strict as before this ruling; only session *lifetime* stopped distinguishing the two.
     *
     * The 360 s attended lease this line used to carry for an unqualified chassis is retired
     * along with `ActiveSessionLeaseDeadline.UNCERTIFIED_ATTENDED_LEASE_MS`,
     * `SupervisorPolicy.uncertifiedAttendedLeaseWindowMs`,
     * `HardwareSessionSupervisor.extendAttendedLeaseIfPermitted`, and `ActiveSessionIdentity`'s
     * `uncertified` flag — see `HardwareSessionSupervisorTest`'s "uncertified SYSTEM attended
     * lease -- RETIRED" block for the surviving pin, and the safety grounding this decision
     * actually rests on (device-derived OPP tables, per-apply target re-validation, and the
     * platform thermal HAL / `androidThermalTrip`, none of which this change touches).
     */
    fun initial(
        scope: ActiveSessionScope,
        nowElapsedRealtimeMs: Long,
        renewableWindowMs: Long,
        hardDeadlineElapsedRealtimeMs: Long,
    ): Long {
        require(renewableWindowMs > 0L)
        require(nowElapsedRealtimeMs < hardDeadlineElapsedRealtimeMs)
        if (scope == ActiveSessionScope.SYSTEM) return hardDeadlineElapsedRealtimeMs
        val renewable = if (nowElapsedRealtimeMs > Long.MAX_VALUE - renewableWindowMs) {
            Long.MAX_VALUE
        } else {
            nowElapsedRealtimeMs + renewableWindowMs
        }
        return renewable.coerceAtMost(hardDeadlineElapsedRealtimeMs)
    }
}

/** Memory-only applied-session identity. Lease renewals never fsync the journal. */
internal data class ActiveSessionIdentity(
    val generation: Long,
    val transactionId: String,
    val requestId: String,
    val ownerUid: Int,
    val targetPackage: String,
    val activationDeadlineMs: Long,
    val leaseDeadlineMs: Long,
    val targetEverForeground: Boolean,
    val ownerTokenId: Long = NO_OWNER_TOKEN_ID,
    val requestDeadlineElapsedRealtimeMs: Long? = null,
    val hardLeaseDeadlineMs: Long = leaseDeadlineMs,
    /** Immutable target truth reconstructed only from the authenticated APPLIED journal. */
    val appliedTargetContract: AppliedTargetContract? = null,
    val scope: ActiveSessionScope = ActiveSessionScope.LAUNCH,
)

internal data class AppliedTargetContract(
    val deviceProfile: String,
    val bootId: String,
    val cpuQosCeilingKhz: Map<Int, Int>,
    val cpuQosTransport: CpuQosTransport,
    val gpuMinPowerLevel: Int?,
    val gpuMaxPowerLevel: Int?,
    /**
     * The GPU ceiling as a devfreq POLICY in HERTZ, when that is the transport this apply used.
     *
     * Mutually exclusive with [gpuMaxPowerLevel]: exactly one ceiling node is written per apply
     * (see `GpuCeilingTransport`), and the watch must read back the one that GOVERNS. Watching
     * `max_pwrlevel` on a chassis whose vendor stack reasserts it is what tore down profiles that
     * were holding perfectly well -- the Nova's entire GPU churn.
     */
    val gpuDevfreqMaxHz: Int? = null,
    val fanMode: Int?,
    val profileId: String = "",
    /**
     * The vendor `performance_mode` this apply wrote, straight from the authenticated applied PLAN
     * (never from the mutation projection): null = Mimir wrote no mode, and then nothing about the
     * mode is watched, exactly as before the axis existed. This is a yield contract, not an
     * enforcement one -- a live read that differs means the player chose another mode, and the
     * supervisor steps aside (see `vendorModeYielded`); it is deliberately absent from [violations].
     */
    val vendorMode: Int? = null,
) {
    /** Exact owned-axis comparison used by both the supervisor and calibration admission. */
    fun violations(vector: CalibrationHardwareVector): List<String> = buildList {
        if (vector.bootId != bootId) add("Applied target boot identity drifted.")
        if (vector.cpuQosTransport != cpuQosTransport) {
            add("Applied target CPU QoS transport drifted.")
        }
        if (vector.cpuQosCeilingKhz != cpuQosCeilingKhz) {
            add("Applied target CPU QoS ceilings drifted.")
        }
        if (gpuMinPowerLevel != null && vector.gpuMinPowerLevel != gpuMinPowerLevel) {
            add("Applied target GPU minimum power level drifted.")
        }
        if (gpuMaxPowerLevel != null && vector.gpuMaxPowerLevel != gpuMaxPowerLevel) {
            add("Applied target GPU maximum power level drifted.")
        }
        if (fanMode != null && vector.vendorFanMode != fanMode) {
            add("Applied target fan mode drifted.")
        }
    }

    companion object {
        private val CPU_LOGICAL_ID = Regex("^cpu:([0-9]+):scaling_max_freq$")

        /** Null is fail-closed reconstruction failure; this function never guesses live targets. */
        fun fromAuthenticatedAppliedRecord(record: TransactionJournalRecord): AppliedTargetContract? {
            if (record.schemaVersion !in setOf(TRANSACTION_JOURNAL_SCHEMA_V4, TRANSACTION_JOURNAL_SCHEMA_V5)) {
                return null
            }
            if (record.phase != TransactionPhase.APPLIED) return null
            val plan = record.appliedPlan ?: return null
            if (PlanHash.ofPersisted(plan) != record.planHash) return null
            if (plan.packageName != record.targetPackage || plan.profileId != record.profileId) return null
            val projection = record.appliedMutationProjection
            if (projection.isEmpty()) return null
            if (record.lastCompletedMutationBoundary != projection.size ||
                record.lastCompletedRestoreBoundary != 0 ||
                record.bootIdentity.isBlank() ||
                record.snapshot.bootIdentity != record.bootIdentity ||
                AppliedMutationProjectionValidator.validate(record).isNotEmpty() ||
                record.schemaVersion == TRANSACTION_JOURNAL_SCHEMA_V5 &&
                GuardianJournalBindingValidator.validate(record).isNotEmpty()
            ) return null

            val cpuMutations = projection.filter { it.axis == TuningAxis.CPU }
            if (cpuMutations.isEmpty()) return null
            val cpuTargets = cpuMutations.mapNotNull { mutation ->
                val policyId = CPU_LOGICAL_ID.matchEntire(mutation.logicalId)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
                val value = mutation.value ?: return null
                if (mutation.kind != LogicalMutationKind.SYSFS_WRITE || !mutation.expectPresent) return null
                policyId to value
            }.toMap()
            if (cpuTargets.size != cpuMutations.size || cpuTargets != plan.cpuMaxKhz) return null
            val sharedCpu = cpuMutations.count { CpuCeilingTransport.isSharedQos(it.canonicalPath) }
            val cpuTransport = when (sharedCpu) {
                cpuMutations.size -> CpuQosTransport.SHARED_QOS
                0 -> CpuQosTransport.POLICY_SCALING_MAX
                else -> return null
            }

            fun projectedValue(logicalId: String): Int? {
                val matches = projection.filter { it.logicalId == logicalId }
                if (matches.size > 1) return null
                return matches.singleOrNull()?.takeIf {
                    it.kind == LogicalMutationKind.SYSFS_WRITE && it.expectPresent
                }?.value
            }

            if (projection.count { it.logicalId == HardwareSnapshot.GPU_MIN_LOGICAL_ID } > 1 ||
                projection.count { it.logicalId == HardwareSnapshot.GPU_MAX_LOGICAL_ID } > 1 ||
                projection.count { it.logicalId == HardwareSnapshot.GPU_DEVFREQ_MAX_LOGICAL_ID } > 1
            ) return null
            // Exactly one ceiling node, never both -- they are alternatives. Two would mean capture
            // and emission disagreed about which transport this chassis uses, and a contract built
            // over an ambiguous ceiling is worse than none.
            val ceilingIds = HardwareSnapshot.GPU_CEILING_LOGICAL_IDS
                .filter { id -> projection.any { it.logicalId == id } }
            if (ceilingIds.size > 1) return null
            val gpuDevfreqKhz = projectedValue(HardwareSnapshot.GPU_DEVFREQ_MAX_LOGICAL_ID)
            val gpuMax = projectedValue(HardwareSnapshot.GPU_MAX_LOGICAL_ID)
            // The pwrlevel projection still has to equal the plan's target verbatim. The devfreq
            // projection cannot: it is the same ceiling expressed in kHz, so its agreement with the
            // plan was established at apply, by the translation the transport owns.
            if (gpuDevfreqKhz == null && gpuMax != plan.gpuMaxPowerLevel) return null
            if (gpuDevfreqKhz != null && plan.gpuMaxPowerLevel == null) return null
            val gpuMin = projectedValue(HardwareSnapshot.GPU_MIN_LOGICAL_ID)
            val fanMatches = projection.filter { it.logicalId == HardwareSnapshot.FAN_LOGICAL_ID }
            if (fanMatches.size > 1) return null
            val fan = fanMatches.singleOrNull()?.takeIf {
                it.kind == LogicalMutationKind.SETTINGS_PUT && it.expectPresent
            }?.value
            if (fan != plan.fanMode) return null

            return AppliedTargetContract(
                deviceProfile = record.deviceProfile,
                bootId = record.bootIdentity,
                cpuQosCeilingKhz = cpuTargets.toSortedMap(),
                cpuQosTransport = cpuTransport,
                gpuMinPowerLevel = gpuMin,
                gpuMaxPowerLevel = gpuMax,
                gpuDevfreqMaxHz = gpuDevfreqKhz,
                fanMode = fan,
                profileId = plan.profileId,
                // Plan-derived on purpose, with no projection cross-check like fan's above: the plan
                // identity is covered by the plan hash checked at the top, and a fail-closed mismatch
                // here would null the whole contract -- no supervision at all -- over an axis whose
                // worst failure mode is a harmless yield.
                vendorMode = plan.vendorMode,
            )
        }
    }
}

internal enum class WorkloadTruth {
    TARGET_FOREGROUND,
    TARGET_PAUSED_OR_STOPPED,
    OTHER_FOREGROUND,
    ACCESS_DENIED,
    QUERY_FAILED,
    INDETERMINATE,
}

/**
 * Fold an OBSERVED ceiling that sits above a policy's writable table down onto that table's top, so a
 * boost-bin read and a contract at the cluster's top compare equal.
 *
 * **Observed side only** -- policy 2026-09-21, narrowing the same evening's first cut. That cut
 * normalised both sides, and both sides is one side too many: a contract value comes from
 * `admittedTargetsKhz`, which is already snapped onto this policy's operating-point table, so it has
 * nothing above the top to fold. The one contract that WILL sit above it is an unpinned request -- and
 * folding that turns "we asked for nothing to be held" against a live 2707200 into a match, which is
 * exactly the divergence a drift check exists to catch. Leaving the contract verbatim costs nothing
 * today and keeps that door shut before Stock-unpins walks through it.
 *
 * Only values ABOVE the top move. One inside the table is left exactly as read, however far from the
 * contract it is -- a real ceiling difference between two writable points is drift, not units. And a
 * policy with no published maximum is returned untouched: an unreadable table must not become a silent
 * normalisation.
 */
internal fun normalizeObservedCeilings(
    values: Map<Int, Int>?,
    writableMaxKhz: Map<Int, Int>,
): Map<Int, Int>? = values?.mapValues { (policyId, value) ->
    writableMaxKhz[policyId]?.let { minOf(value, it) } ?: value
}

/**
 * True when EVERY contract policy is observed at or above its contract value -- the drift is toward
 * stock (the vendor lifting our ceiling), never below it. A missing observation is not "toward
 * stock"; it answers false so the caller falls through to the old Restore.
 */
internal fun cpuDriftOnlyTowardStock(
    normalizedObserved: Map<Int, Int>?,
    contract: Map<Int, Int>,
): Boolean = normalizedObserved != null &&
    contract.isNotEmpty() &&
    contract.all { (policyId, target) -> (normalizedObserved[policyId] ?: return false) >= target }

internal data class ThermalTelemetrySample(
    val androidThermalStatus: Int?,
    val cpuTempC: Int?,
    val gpuTempC: Int?,
    val batteryTempDeciC: Int?,
    val cpuQosCeilingKhz: Map<Int, Int>? = null,
    /**
     * The highest WRITABLE ceiling per policy — the top of `scaling_available_frequencies`, not
     * `cpuinfo_max_freq`. Carried on the sample because the supervisor has no capabilities port of its
     * own and the reader that builds this sample does. Empty for a policy whose table could not be read,
     * which means that policy is compared verbatim, exactly as before this field existed.
     *
     * Policy 2026-09-21, extended to the drift check the same evening it was made for admission:
     * an unpinned ceiling reads back as the BOOST maximum (policy3 2803200, policy7 3187200 on the Thor)
     * and no *admitted* contract can contain those numbers, because they are not writable. Comparing
     * them verbatim made "we asked for this cluster's top" and "nothing is pinned" look like drift.
     *
     * Used on the observed side only ([normalizeObservedCeilings]); the contract is compared as written.
     */
    val cpuWritableMaxKhz: Map<Int, Int> = emptyMap(),
    val cpuQosTransport: CpuQosTransport? = null,
    val gpuMinPowerLevel: Int? = null,
    val gpuMaxPowerLevel: Int? = null,
    /** The devfreq policy ceiling in HERTZ, read back when that is the governing transport. */
    val gpuDevfreqMaxHz: Int? = null,
    val vendorFanMode: Int? = null,
    /**
     * Live `settings system performance_mode`; null = unread. Only consulted when the applied
     * contract carries a [AppliedTargetContract.vendorMode]. Defaulted so every existing sample (and
     * every device without the axis) is unchanged.
     */
    val vendorPerformanceMode: Int? = null,
    val bootId: String? = null,
) {
    val isComplete: Boolean
        get() = androidThermalStatus != null &&
            cpuTempC != null &&
            gpuTempC != null &&
            batteryTempDeciC != null
}

/** Inclusive safe ranges for telemetry admission and per-channel validation. */
internal object TelemetryRanges {
    const val ANDROID_MIN = PowerManager.THERMAL_STATUS_NONE
    const val ANDROID_MAX = PowerManager.THERMAL_STATUS_SHUTDOWN
    const val CPU_MIN_C = 0
    const val CPU_MAX_C = 120
    const val GPU_MIN_C = 0
    const val GPU_MAX_C = 120
    const val BATTERY_MIN_DECI_C = 0
    const val BATTERY_MAX_DECI_C = 800

    fun androidValid(value: Int?): Boolean = value != null && value in ANDROID_MIN..ANDROID_MAX
    fun cpuValid(value: Int?): Boolean = value != null && value in CPU_MIN_C..CPU_MAX_C
    fun gpuValid(value: Int?): Boolean = value != null && value in GPU_MIN_C..GPU_MAX_C
    fun batteryValid(value: Int?): Boolean =
        value != null && value in BATTERY_MIN_DECI_C..BATTERY_MAX_DECI_C

    fun allValid(sample: ThermalTelemetrySample): Boolean =
        androidValid(sample.androidThermalStatus) &&
            cpuValid(sample.cpuTempC) &&
            gpuValid(sample.gpuTempC) &&
            batteryValid(sample.batteryTempDeciC)
}

internal sealed class SupervisorDecision {
    data object Continue : SupervisorDecision()

    /**
     * The applied contract's own already-signed CPU-QoS ceiling drifted (typically an Odin vendor
     * reset, often heat-caused) while GPU, boot, and CPU-QoS-transport all still match.
     * [HardwareSessionSupervisor.execute]'s `is RepairCpuTarget` branch re-asserts the SAME
     * already-contracted ceiling via [GenerationBoundTargetRepairer]. Policy 2026-08-13/14
     * (P0 thermal safety) supersedes this decision's original contract: like [Derate] and
     * [RepairFanTarget] below, a failed or exhausted repair now never falls back to [Restore] --
     * see [HardwareSessionSupervisor.admitTargetRepair]'s kdoc.
     */
    data class RepairCpuTarget(
        val generation: Long,
        val transactionId: String,
        val reason: String,
    ) : SupervisorDecision()

    /**
     * S4.3: the fan-only counterpart of [RepairCpuTarget] -- the applied
     * contract's own already-signed `fanMode` drifted (typically a charger plug/unplug rewriting
     * vendor `fan_mode`, normal operation on this device, not tampering) while CPU, GPU, boot, and
     * CPU-QoS-transport all still match. [HardwareSessionSupervisor.execute]'s `is RepairFanTarget`
     * branch re-asserts the SAME already-contracted fan mode via [GenerationBoundFanReasserter];
     * like [SupervisorDecision.Derate] and, since the same policy, [RepairCpuTarget] above,
     * a failed or exhausted reassert never falls back to [Restore] -- see [admitFanReassert].
     */
    data class RepairFanTarget(
        val generation: Long,
        val transactionId: String,
        val reason: String,
    ) : SupervisorDecision()
    data class Restore(
        val generation: Long,
        val transactionId: String,
        val reason: String,
        /**
         * TR-02: additive-only, for the `TARGET_CONTRACT_DRIFT_*` reasons below -- the applied
         * contract's own value that no longer matches live telemetry. Null for every reason that
         * has no single before/after value to name (lease expiry, owner death, workload loss,
         * missing-channel streaks, ...). Naming these two values used to require a hand-run sysfs
         * poll against the device; never a secret, always a bounded id/number/enum name.
         */
        val expected: String? = null,
        /** TR-02: the live telemetry value that diverged from [expected]. See [expected]. */
        val observed: String? = null,
    ) : SupervisorDecision()

    /**
     * WAVE2_THERMAL_DERATE_SPEC.md S3.1: a thermal signal never produces [Restore]. It produces
     * one step down the pre-authorised [ThermalDerateLadder], staying inside the active
     * (already-qualified) profile. [rungs] is the number of rungs this decision actually moves --
     * 1 for a sustained trip, up to 2 for the immediate `thermalCriticalC` band (spec S3.3),
     * clamped so [rung] never exceeds [ThermalDerateLadder.maxRung] (the floor: at the floor and
     * still over trip, the decision degrades to [Continue] -- "remain", never [Restore]).
     *
     * Stage B: [HardwareSessionSupervisor.execute]'s `is Derate` branch actuates this decision --
     * it resolves [rung] to the active profile's SIGNED plan via [GenerationBoundDerater] (never a
     * hardcoded fallback; spec S4) and applies it through the normal Guardian-fenced transaction
     * path. [rung] also advances so later ticks' floor math stays correct.
     */
    data class Derate(
        val generation: Long,
        val transactionId: String,
        val reason: String,
        val rungs: Int,
        val rung: Int,
    ) : SupervisorDecision()
}

internal fun interface SupervisorClock {
    fun elapsedRealtime(): Long
}

internal fun interface WorkloadObservationPort {
    fun observe(targetPackage: String): WorkloadTruth
}

internal fun interface ThermalTelemetryPort {
    /** Null means the sample itself could not be obtained (counts as missing all channels). */
    fun sample(): ThermalTelemetrySample?
}

internal fun interface OwnerLivenessPort {
    /** Exact owner identity only; service bind counts are never liveness authority. */
    fun isOwnerAlive(identity: ActiveSessionIdentity): Boolean
}

internal fun interface GenerationBoundRestorer {
    fun restoreBound(expectedGeneration: Long, expectedTransactionId: String): TransactionResult
}

internal fun interface GenerationBoundTargetRepairer {
    fun repairCpuTargetBound(expectedGeneration: Long, expectedTransactionId: String): TransactionResult
}

private object UnavailableGenerationBoundTargetRepairer : GenerationBoundTargetRepairer {
    override fun repairCpuTargetBound(
        expectedGeneration: Long,
        expectedTransactionId: String,
    ) = TransactionResult(
        success = false,
        applied = false,
        restored = false,
        code = "TARGET_REPAIR_UNAVAILABLE",
        message = "No exact target repair authority is available.",
        transactionId = expectedTransactionId,
        generation = expectedGeneration,
    )
}

/**
 * S4.3: re-asserts the active session's already-signed `fanMode` (mirrors
 * [GenerationBoundTargetRepairer]'s shape -- generation/transaction-bound, no new target, just a
 * re-write of what the contract already authorizes). Left [UnavailableGenerationBoundFanReasserter]
 * by default here, same as [GenerationBoundTargetRepairer]/[GenerationBoundDerater] before their
 * own production wiring landed: the decision layer (this file) is the complete, safe unit of work
 * for this lane -- an unavailable reasserter still never falls back to [SupervisorDecision.Restore]
 * (see [HardwareSessionSupervisor.execute]'s `is RepairFanTarget` branch), so leaving it unwired
 * costs only "the fan axis is not actively re-driven," never the compute profile.
 */
internal fun interface GenerationBoundFanReasserter {
    fun reassertFanBound(expectedGeneration: Long, expectedTransactionId: String): TransactionResult
}

private object UnavailableGenerationBoundFanReasserter : GenerationBoundFanReasserter {
    override fun reassertFanBound(
        expectedGeneration: Long,
        expectedTransactionId: String,
    ) = TransactionResult(
        success = false,
        applied = false,
        restored = false,
        code = "FAN_REASSERT_UNAVAILABLE",
        message = "No exact fan reassert authority is available.",
        transactionId = expectedTransactionId,
        generation = expectedGeneration,
    )
}

/**
 * WAVE2 Stage B: resolves ladder position [rung] to its SIGNED plan for the active profile and
 * applies it via [TransactionCoordinator.applyDerate]. The port signature deliberately carries
 * only [rung] (an index) -- resolution against the qualification manifest is the caller's job
 * (mirrors [GenerationBoundTargetRepairer]'s own shape), keeping [HardwareSessionSupervisor] free
 * of any qualification-manifest dependency so it stays a pure, JVM-testable decision layer.
 */
internal fun interface GenerationBoundDerater {
    fun derateBound(expectedGeneration: Long, expectedTransactionId: String, rung: Int): TransactionResult
}

private object UnavailableGenerationBoundDerater : GenerationBoundDerater {
    override fun derateBound(
        expectedGeneration: Long,
        expectedTransactionId: String,
        rung: Int,
    ) = TransactionResult(
        success = false,
        applied = false,
        restored = false,
        code = "THERMAL_DERATE_UNAVAILABLE",
        message = "No exact thermal derate authority is available.",
        transactionId = expectedTransactionId,
        generation = expectedGeneration,
    )
}

/**
 * Pure deterministic active-session authority. All time, workload, telemetry, and
 * owner-liveness inputs arrive through ports so JVM tests never touch Android main.
 */
internal class HardwareSessionSupervisor(
    private val policy: SupervisorPolicy = SupervisorPolicy(),
    private val clock: SupervisorClock,
    private val workload: WorkloadObservationPort,
    private val telemetry: ThermalTelemetryPort,
    private val ownerLiveness: OwnerLivenessPort,
    private val restorer: GenerationBoundRestorer,
    private val targetRepairer: GenerationBoundTargetRepairer =
        UnavailableGenerationBoundTargetRepairer,
    private val derater: GenerationBoundDerater = UnavailableGenerationBoundDerater,
    private val fanReasserter: GenerationBoundFanReasserter = UnavailableGenerationBoundFanReasserter,
) {
    private var session: ActiveSessionIdentity? = null
        set(value) {
            field = value
            publishLeaseView()
        }
    private var missingAndroidStreak: Int = 0
    private var missingCpuStreak: Int = 0
    private var missingGpuStreak: Int = 0
    private var missingBatteryStreak: Int = 0
    private var androidTripStreak: Int = 0
    private var batteryTripStreak: Int = 0
    private var missingTargetContractStreak: Int = 0
    private var pendingRetryMs: Long? = null
        set(value) {
            field = value
            publishLeaseView()
        }
    private var nextRetryAtMs: Long? = null
        set(value) {
            field = value
            publishLeaseView()
        }
    private var corruptBlocking: Boolean = false
        set(value) {
            field = value
            publishLeaseView()
        }
    private var crossStoreBlocking: Boolean = false
        set(value) {
            field = value
            publishLeaseView()
        }
    private var lastObservedElapsedMs: Long? = null
    /**
     * Set once when a GPU-only drift proves the axis cannot be held on this chassis.
     *
     * Per SESSION rather than per process: a different device, a different boot or a different vendor
     * governor state deserves its own answer, and a process-lifetime flag would carry one chassis's
     * verdict onto the next.
     */
    private var gpuAxisUnholdable = false

    /**
     * Set once when the live vendor performance mode differs from the one this session applied: the
     * player chose another mode and the axis is no longer ours to watch (D3 "drift = yield").
     *
     * Unlike [gpuAxisUnholdable] this IS per session -- reset on adopt and clear -- because it states a
     * fact about THIS session's applied mode, not about the chassis. Volatile so the status path can
     * read it without the supervisor monitor ([vendorModeYieldedNoLock]).
     */
    @Volatile
    private var vendorModeYielded = false

    private val targetRepairAttempts = ArrayDeque<Long>()

    /** Elapsed-realtime of the previous CPU drift SAMPLE this session, for the per-drift log line. */
    private var lastCpuDriftElapsedMs: Long? = null

    /** Last time an exhausted repair budget was said out loud; the note is rate-limited to one per window. */
    private var lastContestedLogElapsedMs: Long? = null
    private val fanReassertAttempts = ArrayDeque<Long>()

    // WAVE2 S3.4: CPU/GPU consecutive-streak counters (cpuTripStreak/gpuTripStreak) are replaced
    // by 2-second rolling windows -- see advanceThermalTelemetryState/presentThermalCondition.
    private val cpuWindow = ArrayDeque<ThermalSample>()
    private val gpuWindow = ArrayDeque<ThermalSample>()

    // WAVE2 S3.1/S3.5: this session's current position on ThermalDerateLadder. 0 == nominal
    // (never derated); ThermalDerateLadder.maxRung == the floor. Monotonically non-decreasing in
    // Stage A -- recovery (stepping back up after sustained cooling, spec S3.4) is not yet
    // implemented.
    private var thermalRung: Int = 0

    // The current tick's thermal classification, computed exactly once by
    // advanceThermalTelemetryState (mirroring how the old consecutive-streak counters were only
    // ever advanced once per tick) and read (never recomputed) by both tick()'s early gate and
    // evaluateTelemetry()'s defensive re-check, so a Derate can never be double-applied to
    // thermalRung within a single tick.
    private var pendingThermalOutcome: ThermalDerateOutcome? = null

    /**
     * An immutable copy of exactly what a status reader needs, republished by every setter that changes any part of it.
     *
     * [execute] holds this monitor across its write-capable repair, derate, fan and restore ports, so one stuck write
     * holds every `@Synchronized` reader with it. The public status path (a SERVICE_BUSY answer built for a caller the
     * worker just timed out) and the main-thread terminal-settlement recheck must never wait on that, so they read this
     * snapshot through [activeSessionNoLock] and [leaseRenewableNoLock] instead. It is the same data the monitor
     * guards, published at each change; a read can be one in-flight change behind, never blocked.
     */
    private class LeaseView(val session: ActiveSessionIdentity?, val blocked: Boolean)

    @Volatile
    private var leaseView = LeaseView(session = null, blocked = false)

    private fun publishLeaseView() {
        leaseView = LeaseView(
            session = session,
            blocked = corruptBlocking || crossStoreBlocking || nextRetryAtMs != null || pendingRetryMs != null,
        )
    }

    /** [activeSession] without the supervisor monitor: never blocks behind a stuck write. */
    fun activeSessionNoLock(): ActiveSessionIdentity? = leaseView.session

    /** [leaseRenewable] without the supervisor monitor: never blocks behind a stuck write. */
    fun leaseRenewableNoLock(): Boolean = leaseRenewableOf(leaseView)

    /**
     * True once this session stopped watching the vendor performance mode because the player changed
     * it. Meaningful only beside an [activeSessionNoLock] whose contract has a
     * [AppliedTargetContract.vendorMode]; reset with the session. Lock-free like its siblings.
     */
    fun vendorModeYieldedNoLock(): Boolean = vendorModeYielded

    @Synchronized
    fun activeSession(): ActiveSessionIdentity? = session

    /** Preserve every lease, thermal state and retry budget across a fan-only amendment. */
    @Synchronized
    fun amendFanContract(generation: Long, transactionId: String, contract: AppliedTargetContract): Boolean {
        val current = session ?: return false
        val prior = current.appliedTargetContract ?: return false
        if (current.generation != generation || current.transactionId != transactionId ||
            contract != prior.copy(fanMode = contract.fanMode)
        ) return false
        session = current.copy(appliedTargetContract = contract)
        return true
    }

    /** Caller supplies the freshly loaded authenticated journal, never an invented target. */
    @Synchronized
    fun reconcileAppliedContract(record: TransactionJournalRecord): Boolean {
        val current = session ?: return false
        val prior = current.appliedTargetContract ?: return false
        if (record.schemaVersion != TRANSACTION_JOURNAL_SCHEMA_V5 ||
            record.generation != current.generation || record.transactionId != current.transactionId ||
            record.requestId != current.requestId || record.targetPackage != current.targetPackage
        ) return false
        val authoritative = AppliedTargetContract.fromAuthenticatedAppliedRecord(record) ?: return false
        if (authoritative.deviceProfile != prior.deviceProfile || authoritative.bootId != prior.bootId ||
            authoritative.profileId != prior.profileId
        ) return false
        session = current.copy(appliedTargetContract = authoritative)
        return true
    }

    @Synchronized
    fun adoptAppliedSession(identity: ActiveSessionIdentity) {
        require(identity.leaseDeadlineMs <= identity.hardLeaseDeadlineMs) {
            "Renewable lease cannot exceed immutable hard lease deadline"
        }
        vendorModeYielded = false
        session = identity
        resetTelemetryState()
        pendingRetryMs = null
        nextRetryAtMs = null
        corruptBlocking = false
        crossStoreBlocking = false
        lastObservedElapsedMs = clock.elapsedRealtime()
        targetRepairAttempts.clear()
        lastCpuDriftElapsedMs = null
        lastContestedLogElapsedMs = null
        fanReassertAttempts.clear()
    }

    /**
     * Re-adopt after a failed restore. Same generation/transaction/request identity
     * preserves bounded retry backoff; a changed identity resets and adopts fresh.
     */
    @Synchronized
    fun retainOrAdoptForRecovery(identity: ActiveSessionIdentity) {
        val current = session
        if (current != null &&
            current.generation == identity.generation &&
            current.transactionId == identity.transactionId &&
            current.requestId == identity.requestId
        ) {
            session = identity.copy(
                // Preserve activation/lease observation bookkeeping from the retrying session.
                activationDeadlineMs = current.activationDeadlineMs,
                leaseDeadlineMs = current.leaseDeadlineMs,
                targetEverForeground = current.targetEverForeground || identity.targetEverForeground,
                ownerTokenId = current.ownerTokenId,
                requestDeadlineElapsedRealtimeMs = current.requestDeadlineElapsedRealtimeMs,
                hardLeaseDeadlineMs = current.hardLeaseDeadlineMs,
            )
            lastObservedElapsedMs = clock.elapsedRealtime()
            return
        }
        adoptAppliedSession(identity)
    }

    @Synchronized
    fun clearSession() {
        session = null
        vendorModeYielded = false
        resetTelemetryState()
        pendingRetryMs = null
        nextRetryAtMs = null
        corruptBlocking = false
        crossStoreBlocking = false
        lastObservedElapsedMs = null
        targetRepairAttempts.clear()
        lastCpuDriftElapsedMs = null
        lastContestedLogElapsedMs = null
        fanReassertAttempts.clear()
    }

    /** Enter corrupt hold: retain session identity blocked, zero restore commands / no tight loop. */
    @Synchronized
    fun enterCorruptHold() {
        corruptBlocking = true
        crossStoreBlocking = false
        pendingRetryMs = null
        nextRetryAtMs = null
    }

    @Synchronized
    fun isCorruptHold(): Boolean = corruptBlocking

    /** Guardian-bound truth is durable but cannot use the legacy restore loop. */
    @Synchronized
    fun enterCrossStoreHold() {
        crossStoreBlocking = true
        corruptBlocking = false
        pendingRetryMs = null
        nextRetryAtMs = null
    }

    @Synchronized
    fun isCrossStoreHold(): Boolean = crossStoreBlocking

    /**
     * Extends only the matching full session identity lease in memory. Wrong owner,
     * generation, transaction, or request ID is rejected. Expired or retrying state
     * cannot be revived. Never mutates durable journal state.
     */
    @Synchronized
    fun renewLease(
        ownerUid: Int,
        generation: Long,
        transactionId: String,
        requestId: String,
        ownerTokenId: Long = NO_OWNER_TOKEN_ID,
    ): Boolean {
        val current = session ?: return false
        if (current.scope == ActiveSessionScope.SYSTEM) return false
        if (current.ownerUid != ownerUid ||
            current.ownerTokenId != ownerTokenId ||
            current.generation != generation ||
            current.transactionId != transactionId ||
            current.requestId != requestId
        ) {
            return false
        }
        if (nextRetryAtMs != null || pendingRetryMs != null) return false
        val now = clock.elapsedRealtime()
        if (now >= current.leaseDeadlineMs || now >= current.hardLeaseDeadlineMs) return false
        val renewedDeadline = safeAdd(now, policy.leaseWindowMs)
            .coerceAtMost(current.hardLeaseDeadlineMs)
        if (renewedDeadline <= now || renewedDeadline <= current.leaseDeadlineMs) return false
        session = current.copy(leaseDeadlineMs = renewedDeadline)
        return true
    }

    @Synchronized
    fun onOwnerDeath(
        ownerUid: Int,
        ownerTokenId: Long,
        requestId: String,
        generation: Long,
        transactionId: String,
    ): SupervisorDecision {
        val current = session ?: return SupervisorDecision.Continue
        if (current.ownerUid != ownerUid ||
            current.ownerTokenId != ownerTokenId ||
            current.requestId != requestId ||
            current.generation != generation ||
            current.transactionId != transactionId
        ) {
            return SupervisorDecision.Continue
        }
        return SupervisorDecision.Restore(
            generation = current.generation,
            transactionId = current.transactionId,
            reason = "OWNER_TOKEN_DIED",
        )
    }

    @Synchronized
    fun tick(): SupervisorDecision {
        if (corruptBlocking || crossStoreBlocking) return SupervisorDecision.Continue
        val now = clock.elapsedRealtime()
        val previous = lastObservedElapsedMs
        if (previous != null && now < previous) {
            val current = session
            lastObservedElapsedMs = now
            if (current != null) {
                return SupervisorDecision.Restore(
                    generation = current.generation,
                    transactionId = current.transactionId,
                    reason = "ELAPSED_CLOCK_REGRESSION",
                )
            }
        } else {
            lastObservedElapsedMs = now
        }

        val retryAt = nextRetryAtMs
        if (retryAt != null) {
            if (now < retryAt) return SupervisorDecision.Continue
            val current = session ?: return SupervisorDecision.Continue
            return SupervisorDecision.Restore(
                generation = current.generation,
                transactionId = current.transactionId,
                reason = "RESTORE_RETRY",
            )
        }
        val current = session ?: return SupervisorDecision.Continue
        if (!ownerLiveness.isOwnerAlive(current)) {
            return SupervisorDecision.Restore(
                generation = current.generation,
                transactionId = current.transactionId,
                reason = "OWNER_TOKEN_DIED",
            )
        }
        if (now >= current.hardLeaseDeadlineMs) {
            return SupervisorDecision.Restore(
                generation = current.generation,
                transactionId = current.transactionId,
                reason = "HARD_LEASE_EXPIRED",
            )
        }
        if (now >= current.leaseDeadlineMs) {
            return SupervisorDecision.Restore(
                generation = current.generation,
                transactionId = current.transactionId,
                reason = "LEASE_EXPIRED",
            )
        }

        // Read once per tick. A present thermal trip outranks simultaneous workload loss;
        // missing channels remain bounded only after workload truth has been classified.
        val telemetrySample = telemetry.sample()
        // Advance every sensor's trip state exactly once for this sample -- here, at the single
        // per-tick sample read -- including committing any resulting ThermalDerateLadder rung
        // change, before pendingThermalOutcome is consulted below and again (defensively, never
        // recomputed) inside evaluateTelemetry() for this same sample. See
        // advanceThermalTelemetryState for why the ladder mutation must never live in a function
        // callable more than once per tick.
        if (telemetrySample != null) {
            advanceThermalTelemetryState(telemetrySample, now)
        } else {
            pendingThermalOutcome = null
        }
        // WAVE2 S3.1: a thermal signal never produces Restore -- it steps the pre-authorised
        // ThermalDerateLadder instead (Derate). At the floor and still over trip,
        // advanceThermalTelemetryState leaves pendingThermalOutcome null (see
        // classifyAndAdvanceLadder), so this simply falls through: "remain", never Restore.
        pendingThermalOutcome?.let { outcome ->
            return SupervisorDecision.Derate(
                generation = current.generation,
                transactionId = current.transactionId,
                reason = outcome.reason,
                rungs = outcome.rungs,
                rung = outcome.rung,
            )
        }

        val workloadTruth = if (current.scope == ActiveSessionScope.SYSTEM) {
            WorkloadTruth.TARGET_FOREGROUND
        } else {
            workload.observe(current.targetPackage)
        }
        when (val truth = workloadTruth) {
            WorkloadTruth.TARGET_FOREGROUND -> {
                if (!current.targetEverForeground) {
                    session = current.copy(targetEverForeground = true)
                }
            }
            WorkloadTruth.TARGET_PAUSED_OR_STOPPED -> {
                // The first UsageEvents read can replay a historical stop from before this exact
                // launch. Before foreground latches, let the existing activation deadline judge it.
                // Once this session observed the target, pause/stop remains an immediate restore.
                if (current.targetEverForeground) {
                    return SupervisorDecision.Restore(
                        generation = current.generation,
                        transactionId = current.transactionId,
                        reason = "TARGET_PAUSED_OR_STOPPED",
                    )
                }
            }
            WorkloadTruth.OTHER_FOREGROUND -> {
                if (current.targetEverForeground) {
                    return SupervisorDecision.Restore(
                        generation = current.generation,
                        transactionId = current.transactionId,
                        reason = "TARGET_LOSS",
                    )
                }
            }
            WorkloadTruth.ACCESS_DENIED,
            WorkloadTruth.QUERY_FAILED,
            -> {
                return SupervisorDecision.Restore(
                    generation = current.generation,
                    transactionId = current.transactionId,
                    reason = "WORKLOAD_${truth.name}",
                )
            }
            WorkloadTruth.INDETERMINATE -> {
                if (current.targetEverForeground) {
                    return SupervisorDecision.Restore(
                        generation = current.generation,
                        transactionId = current.transactionId,
                        reason = "WORKLOAD_INDETERMINATE",
                    )
                }
            }
        }

        val refreshed = session ?: current
        if (!refreshed.targetEverForeground && now >= refreshed.activationDeadlineMs) {
            return SupervisorDecision.Restore(
                generation = refreshed.generation,
                transactionId = refreshed.transactionId,
                reason = "ACTIVATION_TIMEOUT",
            )
        }

        evaluateTelemetry(
            session = refreshed,
            validateTargetContract = workloadTruth == WorkloadTruth.TARGET_FOREGROUND,
            sample = telemetrySample,
            thermalOutcome = pendingThermalOutcome,
            nowElapsedRealtimeMs = now,
        )?.let { return it }
        return SupervisorDecision.Continue
    }

    @Synchronized
    fun execute(
        decision: SupervisorDecision,
        onRepairAttempt: (TransactionResult) -> Unit = {},
        onDerateAttempt: (TransactionResult) -> Unit = {},
        onFanReassertAttempt: (TransactionResult) -> Unit = {},
    ): TransactionResult? {
        if (decision is SupervisorDecision.Continue) return null
        if (decision is SupervisorDecision.Derate) {
            // WAVE2 Stage B: resolve rung N to its SIGNED plan (the derater's job -- see
            // GenerationBoundDerater kdoc) and apply it through the normal Guardian-fenced
            // transaction path. ThermalDerateLadder is the INDEX; the signed manifest is the
            // AUTHORITY -- the derater/TransactionCoordinator.applyDerate refuse a rung that is
            // not itself signed, never a hardcoded fallback.
            val result = runCatching {
                derater.derateBound(decision.generation, decision.transactionId, decision.rung)
            }.getOrElse { error ->
                TransactionResult(
                    success = false,
                    applied = false,
                    restored = false,
                    code = "THERMAL_DERATE_EXCEPTION",
                    message = "Thermal derate threw before verified settlement: ${error.message}",
                    transactionId = decision.transactionId,
                    generation = decision.generation,
                )
            }
            onDerateAttempt(result)
            settleGuardianTerminalFence(decision.generation, decision.transactionId, result)?.let { return it }
            val current = session
            when {
                result.success && result.applied && result.plan != null &&
                    current != null &&
                    current.generation == decision.generation &&
                    current.transactionId == decision.transactionId &&
                    current.appliedTargetContract != null
                -> {
                    // Refresh the in-memory contract to the newly-written ceiling so the NEXT
                    // tick's drift detection compares against reality, not the pre-derate
                    // nominal -- otherwise this call's own successful write would look like
                    // drift and force a restore next tick, reproducing the exact defect WAVE2
                    // fixed. Trusting result.plan here is not a shortcut around authentication:
                    // it is the SAME data TransactionCoordinator.applyDerate just durably
                    // persisted in this same synchronous call, not a separate, unverified claim.
                    session = current.copy(
                        appliedTargetContract = current.appliedTargetContract.copy(
                            cpuQosCeilingKhz = result.plan.cpuMaxKhz.toSortedMap(),
                            gpuMaxPowerLevel = result.plan.gpuMaxPowerLevel,
                            // Track the last mode Mimir wrote: a derated plan that writes a different
                            // one must not read as the player's choice next tick, and one that writes
                            // none leaves the earlier mode (and its watch) exactly as it was.
                            vendorMode = result.plan.vendorMode ?: current.appliedTargetContract.vendorMode,
                        ),
                    )
                }
                result.code == "STALE_GENERATION" -> {
                    if (current?.generation == decision.generation &&
                        current.transactionId == decision.transactionId
                    ) {
                        clearSession()
                    }
                }
                else -> Unit
            }
            // Spec S3.1/S5: a thermal signal must never produce Restore -- and neither may this
            // actuator's own failure modes (unsigned rung, context rejected, command failure,
            // journal persist failure). Unlike RepairCpuTarget below, there is deliberately no
            // restorer fallback here: doing nothing leaves the session at its last
            // Guardian-certified state, which spec S1's own measurement shows is unconditionally
            // cooler than Stock, and the routine per-tick AppliedTargetContract drift check
            // remains the sole backstop for any hardware/journal mismatch here, unchanged by
            // this lane. The platform's own thermal HAL also remains active throughout regardless.
            return result
        }
        if (decision is SupervisorDecision.RepairFanTarget) {
            // S4.3: re-assert the SAME already-signed fanMode via
            // GenerationBoundFanReasserter -- no new target is adopted, unlike Derate's new rung.
            // This mirrors Derate's posture, not RepairCpuTarget's below: a fan-only cause must
            // never fall back to Restore, on this attempt or any other admitFanReassert grants, so
            // there is deliberately no restorer fallback here regardless of outcome. The routine
            // per-tick AppliedTargetContract drift check remains the sole backstop, unchanged by
            // this lane; the platform's own thermal HAL also remains active throughout regardless.
            val result = runCatching {
                fanReasserter.reassertFanBound(decision.generation, decision.transactionId)
            }.getOrElse { error ->
                TransactionResult(
                    success = false,
                    applied = false,
                    restored = false,
                    code = "FAN_REASSERT_EXCEPTION",
                    message = "Fan reassert threw before verified settlement: ${error.message}",
                    transactionId = decision.transactionId,
                    generation = decision.generation,
                )
            }
            onFanReassertAttempt(result)
            settleGuardianTerminalFence(decision.generation, decision.transactionId, result)?.let { return it }
            if (result.code == "STALE_GENERATION" &&
                session?.generation == decision.generation &&
                session?.transactionId == decision.transactionId
            ) {
                clearSession()
            }
            return result
        }
        if (decision is SupervisorDecision.RepairCpuTarget) {
            // Thermal safety supersedes this branch's original
            // "exact-restore this generation when repair returns anything except TARGET_REPAIRED"
            // contract (see repairAppliedCpuQosBound's kdoc in TransactionCoordinator.kt, updated
            // alongside this change). This now mirrors RepairFanTarget below
            // exactly: a CPU-repair cause -- whatever its own root cause (a failed write, a
            // rejected context, a thrown exception) -- must never fall back to Restore, on this
            // attempt or any other admitTargetRepair grants. Staying on the active (possibly still
            // CPU-drifted) Mimir profile is cooling-positive: it keeps the fan under Mimir's own
            // control, and spec S1's own measurement shows Stock is unconditionally hotter. The
            // routine per-tick drift check remains the retry mechanism on later ticks, bounded by
            // admitTargetRepair's own window/burst budget -- no reset() needed. This deliberately
            // trades away the OLD behavior's ability to fall back to a known-good Stock state for
            // a non-thermal repair failure (e.g. JOURNAL_CORRUPT) in exchange for never abandoning
            // fan ownership.
            val repaired = runCatching {
                targetRepairer.repairCpuTargetBound(
                    decision.generation,
                    decision.transactionId,
                )
            }.getOrElse { error ->
                TransactionResult(
                    success = false,
                    applied = false,
                    restored = false,
                    code = "TARGET_REPAIR_EXCEPTION",
                    message = "Exact target repair threw before verified settlement: ${error.message}",
                    transactionId = decision.transactionId,
                    generation = decision.generation,
                )
            }
            onRepairAttempt(repaired)
            settleGuardianTerminalFence(decision.generation, decision.transactionId, repaired)?.let { return it }
            if (repaired.code == "STALE_GENERATION" &&
                session?.generation == decision.generation &&
                session?.transactionId == decision.transactionId
            ) {
                clearSession()
            }
            return repaired
        }
        decision as SupervisorDecision.Restore
        val result = restorer.restoreBound(decision.generation, decision.transactionId)
        settleRestoreResult(decision.generation, decision.transactionId, result)
        return result
    }

    /**
     * F2 (fan fence, 2026-10-14): a repair, derate or reassert that met the Guardian's closed
     * terminal fence (`TransactionCoordinator.GUARDIAN_TERMINAL_FENCE`) is not a failed repair to
     * retry next tick -- the Guardian has already put this generation back to Stock, and the fence
     * will refuse every later write for it. Gen 51 on the Odin sat like this for 24 s with
     * the supervisor still calling the session active. So the session is settled here, through the
     * same [restorer] a Restore decision uses: that reconciles the journal and the arm store against
     * the Guardian's receipt and clears the session. This is the bookkeeping of a restore that has
     * already happened; it is NOT the fan-only fallback to Restore excluded by the thermal recovery policy, because
     * nothing here changes what the hardware runs -- it is at Stock already, by the Guardian's hand.
     *
     * Returns null when [attempt] is anything else, so the caller's own branch continues unchanged.
     */
    private fun settleGuardianTerminalFence(
        generation: Long,
        transactionId: String,
        attempt: TransactionResult,
    ): TransactionResult? {
        if (attempt.code != TransactionCoordinator.GUARDIAN_TERMINAL_FENCE) return null
        val current = session ?: return attempt
        if (current.generation != generation || current.transactionId != transactionId) return attempt
        val settled = runCatching { restorer.restoreBound(generation, transactionId) }.getOrElse { error ->
            TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "RESTORE_FAILED",
                message = "Guardian terminal settlement threw: ${error.message}",
                transactionId = transactionId,
                generation = generation,
            )
        }
        settleRestoreResult(generation, transactionId, settled)
        return settled
    }

    private fun settleRestoreResult(
        decisionGeneration: Long,
        decisionTransactionId: String,
        result: TransactionResult,
    ) {
        when (result.code) {
            "STALE_GENERATION" -> {
                if (session?.generation == decisionGeneration &&
                    session?.transactionId == decisionTransactionId
                ) {
                    clearSession()
                }
            }
            "JOURNAL_CORRUPT" -> {
                corruptBlocking = true
                crossStoreBlocking = false
                pendingRetryMs = null
                nextRetryAtMs = null
            }
            "CROSS_STORE_RECONCILIATION_REQUIRED", "GUARDIAN_ARM_RETAINED" -> {
                enterCrossStoreHold()
            }
            "RESTORED", "NO_ACTIVE_SESSION" -> {
                clearSession()
            }
            "RESTORE_FAILED", "WRITE_GATE_DISABLED" -> {
                scheduleBoundedRetry()
            }
            else -> {
                if (result.restored) {
                    clearSession()
                } else if (!result.success) {
                    scheduleBoundedRetry()
                }
            }
        }
    }

    @Synchronized
    fun tickAndExecute(
        onDecision: (SupervisorDecision) -> Unit = {},
        onRepairAttempt: (TransactionResult) -> Unit = {},
        onDerateAttempt: (TransactionResult) -> Unit = {},
        onFanReassertAttempt: (TransactionResult) -> Unit = {},
    ): TransactionResult? {
        val decision = tick()
        onDecision(decision)
        return execute(decision, onRepairAttempt, onDerateAttempt, onFanReassertAttempt)
    }

    @Synchronized
    fun pendingRetryDelayMs(): Long? = pendingRetryMs

    /**
     * P2-3 (fence review, 2026-10-14): the states in which [tick] answers `Continue` with the session
     * still set -- a corrupt or cross-store hold, or the bounded retry backoff ([scheduleBoundedRetry]
     * arms both retry fields together and every exit nulls both). Exactly the `blocked` half of the
     * lease view. The monitor's hand-back gate skips its receipt override in these states.
     */
    @Synchronized
    fun isHoldOrBackoff(): Boolean =
        corruptBlocking || crossStoreBlocking || nextRetryAtMs != null || pendingRetryMs != null

    /** Arms 1/2/.../30s backoff after an external failed restore without re-entering restore. */
    @Synchronized
    fun armBoundedRetryAfterFailedRestore() {
        scheduleBoundedRetry()
    }

    /**
     * SYSTEM is unrenewable via this client-facing path, unconditionally, whether or not the
     * session was admitted as certified. retired the one exception this
     * used to carve out (an uncertified SYSTEM session's now-deleted attended lease, which was
     * renewed only from inside [tick] by the daemon's own device-interactivity read, never through
     * this method) — SYSTEM sessions hold the twelve-hour hard deadline and nothing renews them.
     */
    @Synchronized
    fun leaseRenewable(): Boolean = leaseRenewableOf(
        LeaseView(
            session = session,
            blocked = corruptBlocking || crossStoreBlocking || nextRetryAtMs != null || pendingRetryMs != null,
        ),
    )

    private fun leaseRenewableOf(view: LeaseView): Boolean {
        val current = view.session ?: return false
        if (current.scope == ActiveSessionScope.SYSTEM) return false
        if (view.blocked) return false
        val now = clock.elapsedRealtime()
        return ownerLiveness.isOwnerAlive(current) &&
            now < current.leaseDeadlineMs &&
            now < current.hardLeaseDeadlineMs &&
            safeAdd(now, policy.leaseWindowMs)
                .coerceAtMost(current.hardLeaseDeadlineMs) > current.leaseDeadlineMs
    }

    private fun evaluateTelemetry(
        session: ActiveSessionIdentity,
        validateTargetContract: Boolean,
        sample: ThermalTelemetrySample?,
        thermalOutcome: ThermalDerateOutcome?,
        nowElapsedRealtimeMs: Long,
    ): SupervisorDecision? {
        if (sample == null) {
            missingAndroidStreak += 1
            missingCpuStreak += 1
            missingGpuStreak += 1
            missingBatteryStreak += 1
            if (validateTargetContract && session.appliedTargetContract != null) {
                missingTargetContractStreak += 1
            }
            return missingChannelRestore(session)
        }

        // Present thermal trips evaluate immediately even when another channel is missing.
        // Second call site for this same sample -- safe because thermalOutcome is a value already
        // computed exactly once in tick(), above, and is only ever read here, never recomputed
        // (WAVE2: this used to re-invoke presentThermalTrip, a pure classifier; now that a
        // thermal trip also commits a stateful ThermalDerateLadder rung mutation, recomputing
        // here would risk double-derating a single sample, so this reads the cached outcome
        // instead).
        thermalOutcome?.let { outcome ->
            return SupervisorDecision.Derate(
                generation = session.generation,
                transactionId = session.transactionId,
                reason = outcome.reason,
                rungs = outcome.rungs,
                rung = outcome.rung,
            )
        }

        if (TelemetryRanges.androidValid(sample.androidThermalStatus)) {
            missingAndroidStreak = 0
        } else {
            missingAndroidStreak += 1
        }
        if (TelemetryRanges.cpuValid(sample.cpuTempC)) {
            missingCpuStreak = 0
        } else {
            missingCpuStreak += 1
        }
        if (TelemetryRanges.gpuValid(sample.gpuTempC)) {
            missingGpuStreak = 0
        } else {
            missingGpuStreak += 1
        }
        if (TelemetryRanges.batteryValid(sample.batteryTempDeciC)) {
            missingBatteryStreak = 0
        } else {
            missingBatteryStreak += 1
        }
        missingChannelRestore(session)?.let { return it }
        if (!validateTargetContract) {
            missingTargetContractStreak = 0
            return null
        }
        val contract = session.appliedTargetContract ?: return null
        val cpuQosSample = sample.cpuQosCeilingKhz
        val exactCpuQosShape = cpuQosSample != null &&
            cpuQosSample.keys == contract.cpuQosCeilingKhz.keys &&
            cpuQosSample.values.all { it > 0 }
        val targetUnavailable = sample.bootId == null || !exactCpuQosShape ||
            sample.cpuQosTransport == null ||
            contract.gpuMinPowerLevel != null && sample.gpuMinPowerLevel == null ||
            contract.gpuMaxPowerLevel != null && sample.gpuMaxPowerLevel == null ||
            contract.fanMode != null && sample.vendorFanMode == null ||
            // An unread mode is an unavailable channel, like fan's, never a drift. Once the axis
            // yielded it is not watched at all, so a later null read must not count toward a Restore.
            contract.vendorMode != null && !vendorModeYielded && sample.vendorPerformanceMode == null
        if (targetUnavailable) {
            missingTargetContractStreak += 1
            if (missingTargetContractStreak < policy.missingTelemetryLimit) return null
            return SupervisorDecision.Restore(
                generation = session.generation,
                transactionId = session.transactionId,
                reason = "MISSING_TARGET_CONTRACT_TELEMETRY",
            )
        }
        missingTargetContractStreak = 0
        // **The player's own vendor performance mode is never contested -- drift is a YIELD.**
        //
        // The Tweaker drives `performance_mode` as the vendor's floor control, but the mode belongs to
        // the player: they can switch it in the vendor UI at any time. A live read that differs from
        // the mode this session applied therefore means "the player chose differently", not "the
        // session is broken". The verdict is reached once, said out loud once, and the axis stops
        // being watched for the rest of the session; the daemon's restore later sees the live mode is
        // not the one Mimir wrote and leaves it alone.
        //
        // **No signal at all, which is how it stays out of every decision below.** The observed value
        // is not a conjunct of cpuDrift, fanDrift, the repair predicates, the GPU verdict or the
        // Restore `when`, so it can neither claim a decision nor veto one: a concurrent fan drift still
        // reaches [admitFanReassert] exactly as with a released GPU. Never a re-assert (that would
        // fight the player) and never a Restore (that would cost the compute profile for their choice).
        // A null read is not drift: the unavailable-channel leg above owns it.
        val appliedVendorMode = contract.vendorMode
        val liveVendorMode = sample.vendorPerformanceMode
        if (appliedVendorMode != null && !vendorModeYielded &&
            liveVendorMode != null && liveVendorMode != appliedVendorMode
        ) {
            vendorModeYielded = true
            android.util.Log.w(
                "MimirTweakerSupervisor",
                "VENDOR_MODE_YIELDED applied=$appliedVendorMode live=$liveVendorMode " +
                    "generation=${session.generation}; the player chose another mode, " +
                    "no longer watching it and keeping the session",
            )
        }
        // The OBSERVED ceiling is folded onto the writable table before comparing; the contract is read
        // verbatim (policy 2026-09-21, narrowed the same evening -- see [normalizeObservedCeilings]).
        // A read at or above a cluster's highest writable operating point says one thing -- "this cluster
        // is not held below its top" -- whether it arrives as that point or as the boost maximum the node
        // reports when nothing is pinned. Measured on the Thor: contract {0=2016000, 3=2707200,
        // 7=1843200} against observed {0=2016000, 3=2803200, 7=3187200}, restored five times in ninety
        // seconds. Policy 0 agreed only by luck -- the little cluster has no boost bin, so its unpinned
        // read already equalled its contract.
        //
        // Folding DOWN and not up: a ceiling genuinely held below its contract still differs and is still
        // drift, which is what policy 7 was in that same reading.
        //
        // What this does NOT explain, and must not be read as explaining: run 14's reverts were the
        // vendor's, not a units mismatch. `pservice` owns `scaling_max_freq` on that chassis and the
        // Tweaker was only writing it because a stranded shared-QoS node had disqualified the transport
        // that exists to avoid it (`scratchpad/run14_policy7_finding.md`, 2026-09-21). This comparison
        // was reporting a real revert on policy 3 as well; folding the boost read is right, and it is
        // not a fix for that device.
        val normalizedObservedCpu = normalizeObservedCeilings(sample.cpuQosCeilingKhz, sample.cpuWritableMaxKhz)
        val cpuDrift = normalizedObservedCpu != contract.cpuQosCeilingKhz
        if (cpuDrift) {
            val sinceLastMs = lastCpuDriftElapsedMs?.let { nowElapsedRealtimeMs - it }
            lastCpuDriftElapsedMs = nowElapsedRealtimeMs
            android.util.Log.i(
                "MimirTweakerSupervisor",
                "cpu drift sample transport=${sample.cpuQosTransport} contractTransport=${contract.cpuQosTransport} " +
                    "expected=${contract.cpuQosCeilingKhz.toSortedMap()} " +
                    "observed=${sample.cpuQosCeilingKhz?.toSortedMap()} " +
                    "sinceLastDriftMs=${sinceLastMs ?: "first"} generation=${session.generation}",
            )
        }
        // **An axis the hardware will not hold stops being watched.**
        //
        // Measured on the AYN Thor: a GPU ceiling written through `max_pwrlevel` lands, governs for
        // about two seconds (`cur_freq` sat at exactly the requested 295 MHz), and is then released by
        // the vendor governor, which also clamps the floor we widened. There is no second door — the
        // kernel's own bound, `devfreq/max_freq`, is `-r--r--r--`, read-only even to root. The CPU had
        // one (`msm_performance`, which composes with the HAL and held 45 s with zero restores); the
        // GPU on this chassis does not.
        //
        // That cost the player their entire profile for an axis the hardware was never going to keep:
        // apply succeeded, the row said active, and three seconds later everything was restored.
        //
        // **Suppressed at the SIGNAL, not at the decision**, which matters: dropping the axis from the
        // watch leaves every other axis's precedence exactly as it was, so a fan drift concurrent with
        // a released GPU still reaches its own repair instead of being swallowed by a GPU branch. The
        // first version of this fix got that wrong and the supervisor's own tests caught it.
        //
        // Admitted rather than repaired: re-writing a node the governor releases every two seconds is a
        // write loop with no terminating condition, which is a perpetual producer wearing a repair's
        // name.
        val gpuMinDriftObserved = contract.gpuMinPowerLevel != null &&
            sample.gpuMinPowerLevel != contract.gpuMinPowerLevel
        // Whichever node GOVERNS is the one that can drift. On a chassis using the devfreq policy
        // the vendor stack is free to keep rewriting `max_pwrlevel` -- it no longer holds the
        // ceiling, so it is no longer evidence of anything.
        val gpuMaxDriftObserved = if (contract.gpuDevfreqMaxHz != null) {
            sample.gpuDevfreqMaxHz != contract.gpuDevfreqMaxHz
        } else {
            contract.gpuMaxPowerLevel != null &&
                sample.gpuMaxPowerLevel != contract.gpuMaxPowerLevel
        }
        // The verdict is reached ONCE, on a drift that is the GPU's alone. A GPU drift arriving
        // alongside a CPU one says nothing about whether the GPU can be held -- the session is going to
        // restore for the CPU regardless -- so it must not be the evidence that condemns the axis.
        if ((gpuMinDriftObserved || gpuMaxDriftObserved) && !gpuAxisUnholdable && !cpuDrift &&
            sample.bootId == contract.bootId && sample.cpuQosTransport == contract.cpuQosTransport
        ) {
            gpuAxisUnholdable = true
            android.util.Log.w(
                "MimirTweakerSupervisor",
                "gpu axis does not hold on this chassis " +
                    "(min expected=${contract.gpuMinPowerLevel} observed=${sample.gpuMinPowerLevel}, " +
                    "max expected=${contract.gpuMaxPowerLevel} observed=${sample.gpuMaxPowerLevel}); " +
                    "dropping it from the watched contract and keeping the session",
            )
        }
        val gpuMinDrift = gpuMinDriftObserved && !gpuAxisUnholdable
        val gpuMaxDrift = gpuMaxDriftObserved && !gpuAxisUnholdable
        val fanDrift = contract.fanMode != null && sample.vendorFanMode != contract.fanMode
        // 2026-08-22 (late) ruling: the `contract.deviceProfile == CpuCeilingTransport.ODIN_3_PROFILE`
        // check this branch used to carry is DROPPED, not widened -- `record`/`contract` here carry
        // only a persisted profile-id string, with no socModel to resolve a chip family from. The
        // check was always redundant with the two SHARED_QOS legs immediately below: that transport
        // can only ever be selected for an 8-Elite-family device in the first place
        // (CpuCeilingTransport.selectedPath, now itself family-scoped), so relying on
        // `cpuQosTransport == SHARED_QOS` alone reaches the identical set of sessions.
        //
        // **Sticky apply (2026-10-01).** Two changes, both from the Nova's 09-30 session set (19 of 20
        // sessions restored 1-9 s after the lease on `TARGET_CONTRACT_DRIFT_CPU`):
        //
        // 1. The genuine POLICY_SCALING_MAX fallback (no usable shared node) is repairable too, but only
        //    when every drifted policy moved TOWARD stock (observed at or above the contract; the
        //    observed side is already folded onto the writable top by [normalizeObservedCeilings]).
        //    That is the vendor perf server reasserting `scaling_max_freq`, which is a writer fight, not
        //    a broken session -- the same budget below bounds it, and exhausting it is a `contested`
        //    Continue, never a Restore. A policy observed BELOW its contract is not the vendor lifting
        //    our cap, it is something clamping harder than we asked, and keeps restoring as before.
        // 2. A fan drift no longer vetoes the CPU repair. It was the only thing in this expression that
        //    was not about the CPU, and when it vetoed, the whole compute profile was restored for a
        //    charger plug. The fan drift is still there next tick and takes its own sole-fan-drift
        //    route ([admitFanReassert]) once the CPU is back on target.
        val cpuTransportRepairable = when {
            sample.cpuQosTransport != contract.cpuQosTransport -> false
            contract.cpuQosTransport == CpuQosTransport.SHARED_QOS -> true
            contract.cpuQosTransport == CpuQosTransport.POLICY_SCALING_MAX ->
                cpuDriftOnlyTowardStock(normalizedObservedCpu, contract.cpuQosCeilingKhz)
            else -> false
        }
        val soleRepairableCpuDrift =
            session.scope == ActiveSessionScope.SYSTEM &&
                sample.bootId == contract.bootId &&
                cpuTransportRepairable &&
                cpuDrift &&
                !gpuMinDrift &&
                !gpuMaxDrift
        if (soleRepairableCpuDrift) {
            return admitTargetRepair(session, nowElapsedRealtimeMs)
        }
        // S4.3: a fan-only drift -- boot, CPU-QoS transport, CPU, and GPU
        // all still match the applied contract -- re-asserts the already-signed fanMode instead
        // of discarding the whole CPU+GPU vector. Unlike soleRepairableCpuDrift above, this is not
        // narrowed to SYSTEM scope / SHARED_QOS transport: those gates exist because THAT repair
        // mechanism is transport-specific, not because sole-axis fan drift is. A charger
        // plug/unplug rewriting vendor fan_mode at an unpredictable time is normal operation on
        // this device, not tampering, and must never cost the player their compute profile.
        val soleRepairableFanDrift = fanDrift &&
            sample.bootId == contract.bootId &&
            sample.cpuQosTransport == contract.cpuQosTransport &&
            !cpuDrift &&
            !gpuMinDrift &&
            !gpuMaxDrift
        if (soleRepairableFanDrift) {
            return admitFanReassert(session, nowElapsedRealtimeMs)
        }
        // **An axis that cannot be HELD is not a broken session.**
        //
        // Measured on the AYN Thor: a GPU ceiling written through `max_pwrlevel` lands, governs for
        // about two seconds (`cur_freq` sat at exactly the requested 295 MHz), and is then released by
        // the vendor governor, which also clamps the floor we widened. There is no second door: the
        // kernel's own bound, `devfreq/max_freq`, is `-r--r--r--`, read-only even to root. The CPU had
        // one — `msm_performance`, which composes with the HAL and held 45 s with zero restores — and
        // the GPU on this chassis does not.
        //
        // So this drift was costing the player their whole profile for an axis the hardware was never
        // going to keep: apply succeeded, the row said active, and three seconds later the supervisor
        // restored everything.
        //
        // **Admitted, not repaired**, which is where this deliberately departs from the fan precedent
        // above. Re-writing a node the governor releases every two seconds would be a write loop with
        // no terminating condition — a perpetual producer wearing a repair's name. The axis is marked
        // unholdable once, said out loud once, and stops being watched; the session survives on the
        // axes that do hold. This is discovered capability: on this chassis the
        // GPU is readable and not writable-and-holdable, and those are separate facts.
        val soleGpuDrift = (gpuMinDrift || gpuMaxDrift) &&
            sample.bootId == contract.bootId &&
            sample.cpuQosTransport == contract.cpuQosTransport &&
            !cpuDrift
        if (soleGpuDrift) {
            if (!gpuAxisUnholdable) {
                gpuAxisUnholdable = true
                android.util.Log.w(
                    "MimirTweakerSupervisor",
                    "gpu axis does not hold on this chassis " +
                        "(min expected=${contract.gpuMinPowerLevel} observed=${sample.gpuMinPowerLevel}, " +
                        "max expected=${contract.gpuMaxPowerLevel} observed=${sample.gpuMaxPowerLevel}); " +
                        "dropping it from the watched contract and keeping the session",
                )
            }
            return SupervisorDecision.Continue
        }
        // TR-02: additive only -- same five branches, same conditions, same order, same reason
        // strings as before. Each branch now also carries the exact expected (applied-contract)
        // and observed (live-telemetry) values that diverged, instead of naming only the axis.
        val drift = when {
            sample.bootId != contract.bootId -> TargetContractDrift(
                reason = "TARGET_CONTRACT_DRIFT_BOOT",
                expected = contract.bootId,
                observed = sample.bootId ?: "missing",
            )
            sample.cpuQosTransport != contract.cpuQosTransport -> TargetContractDrift(
                reason = "TARGET_CONTRACT_DRIFT_CPU_TRANSPORT",
                expected = contract.cpuQosTransport.name,
                observed = sample.cpuQosTransport?.name ?: "missing",
            )
            cpuDrift -> TargetContractDrift(
                reason = "TARGET_CONTRACT_DRIFT_CPU",
                expected = contract.cpuQosCeilingKhz.toString(),
                observed = sample.cpuQosCeilingKhz?.toString() ?: "missing",
            )
            gpuMinDrift -> TargetContractDrift(
                reason = "TARGET_CONTRACT_DRIFT_GPU_MIN",
                expected = contract.gpuMinPowerLevel?.toString() ?: "missing",
                observed = sample.gpuMinPowerLevel?.toString() ?: "missing",
            )
            gpuMaxDrift -> {
                // Report in the units of whichever node governs, so the log names the thing that
                // actually moved rather than an index nobody is enforcing.
                val devfreq = contract.gpuDevfreqMaxHz
                TargetContractDrift(
                    reason = "TARGET_CONTRACT_DRIFT_GPU_MAX",
                    expected = if (devfreq != null) devfreq.toString() + "Hz" else contract.gpuMaxPowerLevel?.toString() ?: "missing",
                    observed = if (devfreq != null) {
                        sample.gpuDevfreqMaxHz?.toString()?.plus("Hz") ?: "missing"
                    } else {
                        sample.gpuMaxPowerLevel?.toString() ?: "missing"
                    },
                )
            }
            // TARGET_CONTRACT_DRIFT_FAN is deliberately not reachable here: every case that would
            // reach it (fanDrift true, nothing else drifted) is exactly soleRepairableFanDrift
            // above, which returns first. A fan drift concurrent with any other axis still
            // reports that OTHER axis's reason, matching soleRepairableCpuDrift's own precedent.
            else -> null
        }
        return drift?.let {
            SupervisorDecision.Restore(
                generation = session.generation,
                transactionId = session.transactionId,
                reason = it.reason,
                expected = it.expected,
                observed = it.observed,
            )
        }
    }

    /** TR-02: pure carrier for one TARGET_CONTRACT_DRIFT_* reason plus its expected/observed pair. */
    private data class TargetContractDrift(val reason: String, val expected: String, val observed: String)

    /**
     * bounded-window/burst admission, shaped
     * exactly like [admitFanReassert], but exhaustion now degrades HONESTLY instead of escalating
     * -- mirroring how [ThermalDerateLadder]/[SupervisorDecision.Derate] degrade to "remain" at
     * the floor rather than restoring (spec S3.1), and how [admitFanReassert]'s own S4.3
     * exhaustion already degrades to [SupervisorDecision.Continue]. Previously this branch
     * escalated exhaustion straight to [SupervisorDecision.Restore] ("a rapid writer fight
     * restores Stock") -- a persistent CPU-QoS drift is exactly the shape a vendor thermal reset
     * produces under sustained heat (see [SupervisorPolicy.targetRepairWindowMs]'s own "Sparse
     * Odin vendor resets remain repairable" kdoc), and reverting to Stock in response abandons fan
     * ownership at the exact moment heat made it matter most. A rate-limited tick does not retry silently
     * forever: it simply stops requesting a repair THIS tick, the window rolls forward on its own,
     * and a later tick tries again -- no reset() needed.
     */
    private fun admitTargetRepair(
        session: ActiveSessionIdentity,
        nowElapsedRealtimeMs: Long,
    ): SupervisorDecision {
        val cutoff = nowElapsedRealtimeMs - policy.targetRepairWindowMs
        while (targetRepairAttempts.isNotEmpty() && targetRepairAttempts.first() <= cutoff) {
            targetRepairAttempts.removeFirst()
        }
        val burstCutoff = nowElapsedRealtimeMs - policy.targetRepairBurstWindowMs
        val burstAttempts = targetRepairAttempts.count { it > burstCutoff }
        if (targetRepairAttempts.size >= policy.maxTargetRepairAttemptsPerWindow ||
            burstAttempts >= policy.maxTargetRepairAttemptsPerBurst
        ) {
            // The budget is spent: another writer is winning this ceiling right now. Say so (once per
            // window, not once per 1 s tick) and keep the session -- never a Restore.
            val lastSaid = lastContestedLogElapsedMs
            if (lastSaid == null || nowElapsedRealtimeMs - lastSaid >= policy.targetRepairWindowMs) {
                lastContestedLogElapsedMs = nowElapsedRealtimeMs
                android.util.Log.i(
                    "MimirTweakerSupervisor",
                    "cpu ceiling contested: repair budget spent " +
                        "(window=${targetRepairAttempts.size}/${policy.maxTargetRepairAttemptsPerWindow} " +
                        "burst=$burstAttempts/${policy.maxTargetRepairAttemptsPerBurst}); " +
                        "continuing without restore generation=${session.generation}",
                )
            }
            return SupervisorDecision.Continue
        }
        targetRepairAttempts.addLast(nowElapsedRealtimeMs)
        return SupervisorDecision.RepairCpuTarget(
            generation = session.generation,
            transactionId = session.transactionId,
            reason = "TARGET_CONTRACT_DRIFT_CPU",
        )
    }

    /**
     * S4.3: bounded-window/burst admission shaped exactly like
     * [admitTargetRepair], but exhaustion degrades honestly instead of escalating -- mirroring how
     * [ThermalDerateLadder]/[SupervisorDecision.Derate] degrade to "remain" at the floor rather
     * than restoring (spec S3.1). A rate-limited tick does not retry silently forever: it simply
     * stops requesting a reassert THIS tick, the same [SupervisorDecision.Continue] a genuinely
     * undrifted fan would produce, and the window rolls forward so a later tick can try again.
     */
    private fun admitFanReassert(
        session: ActiveSessionIdentity,
        nowElapsedRealtimeMs: Long,
    ): SupervisorDecision {
        val cutoff = nowElapsedRealtimeMs - policy.fanReassertWindowMs
        while (fanReassertAttempts.isNotEmpty() && fanReassertAttempts.first() <= cutoff) {
            fanReassertAttempts.removeFirst()
        }
        val burstCutoff = nowElapsedRealtimeMs - policy.fanReassertBurstWindowMs
        val burstAttempts = fanReassertAttempts.count { it > burstCutoff }
        if (fanReassertAttempts.size >= policy.maxFanReassertAttemptsPerWindow ||
            burstAttempts >= policy.maxFanReassertAttemptsPerBurst
        ) {
            return SupervisorDecision.Continue
        }
        fanReassertAttempts.addLast(nowElapsedRealtimeMs)
        return SupervisorDecision.RepairFanTarget(
            generation = session.generation,
            transactionId = session.transactionId,
            reason = "TARGET_CONTRACT_DRIFT_FAN",
        )
    }

    private fun missingChannelRestore(session: ActiveSessionIdentity): SupervisorDecision.Restore? {
        val limit = policy.missingTelemetryLimit
        val reason = when {
            missingAndroidStreak >= limit -> "MISSING_TELEMETRY_ANDROID"
            missingCpuStreak >= limit -> "MISSING_TELEMETRY_CPU"
            missingGpuStreak >= limit -> "MISSING_TELEMETRY_GPU"
            missingBatteryStreak >= limit -> "MISSING_TELEMETRY_BATTERY"
            else -> return null
        }
        return SupervisorDecision.Restore(
            generation = session.generation,
            transactionId = session.transactionId,
            reason = reason,
        )
    }

    /**
     * Pure first-match classification, WAVE2 S3.1/S3.3/S3.4: ANDROID -> CPU -> GPU -> BATTERY,
     * each sensor fully resolved (CRITICAL then TRIP) before the next sensor is examined --
     * unchanged ordering from the pre-WAVE2 `presentThermalTrip`. CPU/GPU CRITICAL (`v >
     * thermalCriticalC`, independent of history, "no dwell") requests 2 rungs; CPU/GPU TRIP (the
     * *sustained* 2-second rolling mean, already advanced into [cpuWindow]/[gpuWindow] by
     * [advanceThermalTelemetryState] for this exact sample, exceeds the trip) requests 1 rung.
     * ANDROID/BATTERY keep the WAVE1A consecutive-streak mechanism unchanged (spec S3.3: "change
     * its response, not its sensitivity") and always request 1 rung -- neither has a defined
     * CRITICAL band (android is a discrete OS enum, not a temperature; battery exposes no vendor
     * trip points to key one off).
     *
     * Reads state, never writes it -- safe to call from both [tick] (indirectly, via
     * [pendingThermalOutcome]) and [evaluateTelemetry] for the same sample without
     * double-counting. Floor clamping and the [thermalRung] mutation itself happen one level up,
     * in [classifyAndAdvanceLadder], which is the only caller.
     */
    /**
     * Is the Android skin status backed by silicon that is actually hot?
     *
     * **Missing telemetry corroborates.** If neither classified zone is readable there is nothing
     * to contradict the platform's own signal, and a supervisor that ignored SEVERE because it
     * could not read a sensor would be choosing a clock over the hardware. Ambiguous branches fail
     * toward cooling -- and a device with no thermal telemetry at all is separately restored by the
     * MISSING_TELEMETRY paths, so this branch is not the only thing standing there.
     */
    private fun androidSkinCorroborated(sample: ThermalTelemetrySample, floorC: Int): Boolean {
        val hottest = listOfNotNull(sample.cpuTempC, sample.gpuTempC).maxOrNull() ?: return true
        return hottest >= floorC
    }

    private fun presentThermalCondition(sample: ThermalTelemetrySample): ThermalTripCondition? {
        val androidStatus = sample.androidThermalStatus
        if (TelemetryRanges.androidValid(androidStatus) &&
            androidStatus!! >= policy.androidThermalTrip &&
            // The skin channel alone: see androidThermalTripDebounceSamples for the device
            // measurement and the ruling. Every other trip below keeps the fast 3-sample path.
            androidTripStreak >= policy.androidThermalTripDebounceSamples &&
            // ...and skin must be CORROBORATED by silicon. See androidThermalCorroborationC:
            // measured firing at 80.3 C on a device that runs 100 C on stock.
            androidSkinCorroborated(sample, policy.androidThermalCorroborationC)
        ) {
            return ThermalTripCondition("THERMAL_ANDROID", rungs = 1)
        }
        val cpu = sample.cpuTempC
        if (cpu != null && cpu > policy.thermalCriticalC) {
            return ThermalTripCondition("THERMAL_CPU", rungs = 2)
        }
        if (windowMeanC(cpuWindow)?.let { it > policy.cpuTripCExclusive } == true) {
            return ThermalTripCondition("THERMAL_CPU", rungs = 1)
        }
        val gpu = sample.gpuTempC
        if (gpu != null && gpu > policy.thermalCriticalC) {
            return ThermalTripCondition("THERMAL_GPU", rungs = 2)
        }
        if (windowMeanC(gpuWindow)?.let { it > policy.gpuTripCExclusive } == true) {
            return ThermalTripCondition("THERMAL_GPU", rungs = 1)
        }
        val battery = sample.batteryTempDeciC
        if (battery != null && battery > TelemetryRanges.BATTERY_MAX_DECI_C) {
            return ThermalTripCondition("THERMAL_BATTERY", rungs = 2)
        }
        if (TelemetryRanges.batteryValid(battery) && battery!! > policy.batteryTripDeciCExclusive &&
            batteryTripStreak >= policy.thermalTripDebounceSamples
        ) {
            return ThermalTripCondition("THERMAL_BATTERY", rungs = 1)
        }
        return null
    }

    /**
     * Advances every sensor's trip-detection state for exactly one telemetry sample, then commits
     * any resulting [ThermalDerateLadder] rung change into [thermalRung] and
     * [pendingThermalOutcome]. Must run exactly once per tick, at the single per-tick sample read
     * in [tick] -- never inside [presentThermalCondition] or [classifyAndAdvanceLadder], which
     * [evaluateTelemetry] also consults (via the cached [pendingThermalOutcome], never
     * recomputed) against this same sample within one tick. Committing the ladder mutation twice
     * per tick would derate two rungs for a single qualifying sample instead of one.
     *
     * ANDROID/BATTERY: unchanged WAVE1A consecutive-streak counters (CRITICAL and TRIP both
     * advance the streak; CLEAR and missing/below-min reset it to 0).
     *
     * CPU/GPU (WAVE2 S3.4): the streak counters are replaced by a 2-second rolling window of
     * valid samples. Pruning (drop anything older than [SupervisorPolicy.thermalRollingWindowMs])
     * always runs; appending this sample only happens when it is itself valid, so one missing or
     * out-of-range reading neither corrupts the mean nor freezes the window the way a fully
     * missing [ThermalTelemetrySample] does (see [tick]'s `telemetrySample == null` branch, which
     * skips this function entirely and leaves the window untouched).
     */
    private fun advanceThermalTelemetryState(sample: ThermalTelemetrySample, nowElapsedRealtimeMs: Long) {
        val androidStatus = sample.androidThermalStatus
        androidTripStreak = if (TelemetryRanges.androidValid(androidStatus) &&
            androidStatus!! >= policy.androidThermalTrip
        ) {
            androidTripStreak + 1
        } else {
            0
        }

        val battery = sample.batteryTempDeciC
        batteryTripStreak = if (battery != null &&
            (battery > TelemetryRanges.BATTERY_MAX_DECI_C ||
                (TelemetryRanges.batteryValid(battery) && battery > policy.batteryTripDeciCExclusive))
        ) {
            batteryTripStreak + 1
        } else {
            0
        }

        pruneWindow(cpuWindow, nowElapsedRealtimeMs)
        sample.cpuTempC?.takeIf(TelemetryRanges::cpuValid)?.let {
            cpuWindow.addLast(ThermalSample(nowElapsedRealtimeMs, it))
        }
        pruneWindow(gpuWindow, nowElapsedRealtimeMs)
        sample.gpuTempC?.takeIf(TelemetryRanges::gpuValid)?.let {
            gpuWindow.addLast(ThermalSample(nowElapsedRealtimeMs, it))
        }

        pendingThermalOutcome = classifyAndAdvanceLadder(sample)
    }

    private fun pruneWindow(window: ArrayDeque<ThermalSample>, nowElapsedRealtimeMs: Long) {
        val cutoff = nowElapsedRealtimeMs - policy.thermalRollingWindowMs
        while (window.isNotEmpty() && window.first().atElapsedRealtimeMs < cutoff) {
            window.removeFirst()
        }
    }

    private fun windowMeanC(window: ArrayDeque<ThermalSample>): Double? =
        if (window.isEmpty()) null else window.sumOf { it.valueC } / window.size.toDouble()

    /**
     * WAVE2 S3.1/S3.5: commits [presentThermalCondition]'s requested rung count onto the shared
     * [thermalRung] ladder position, clamped so it never exceeds [ThermalDerateLadder.maxRung].
     * At the floor and still over trip, this returns null -- the caller falls through to
     * [SupervisorDecision.Continue] ("remain", never [SupervisorDecision.Restore]; spec S3.1).
     * One shared rung across all four channels: the ladder in [ThermalDerateLadder] is a single
     * physical CPU+GPU state, not four independent per-sensor ladders, so whichever sensor trips
     * first steps the same position.
     */
    private fun classifyAndAdvanceLadder(sample: ThermalTelemetrySample): ThermalDerateOutcome? {
        val condition = presentThermalCondition(sample) ?: return null
        val maxRung = ThermalDerateLadder.maxRung
        if (thermalRung >= maxRung) return null
        val appliedRungs = minOf(condition.rungs, maxRung - thermalRung)
        thermalRung += appliedRungs
        return ThermalDerateOutcome(reason = condition.reason, rungs = appliedRungs, rung = thermalRung)
    }

    private fun scheduleBoundedRetry() {
        val next = when (val pending = pendingRetryMs) {
            null -> policy.restoreRetryInitialMs
            else -> (pending * policy.restoreRetryMultiplier).coerceAtMost(policy.restoreRetryMaxMs)
        }
        pendingRetryMs = next
        nextRetryAtMs = clock.elapsedRealtime() + next
    }

    private fun resetTelemetryState() {
        missingAndroidStreak = 0
        missingCpuStreak = 0
        missingGpuStreak = 0
        missingBatteryStreak = 0
        androidTripStreak = 0
        batteryTripStreak = 0
        cpuWindow.clear()
        gpuWindow.clear()
        thermalRung = 0
        pendingThermalOutcome = null
        missingTargetContractStreak = 0
    }

    private fun safeAdd(value: Long, delta: Long): Long =
        if (delta > 0L && value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta
}

/** A single valid channel reading, timestamped for [SupervisorPolicy.thermalRollingWindowMs] pruning. */
private data class ThermalSample(val atElapsedRealtimeMs: Long, val valueC: Int)

/** [presentThermalCondition]'s pure result: which channel tripped and how many rungs it requests. */
private data class ThermalTripCondition(val reason: String, val rungs: Int)

/** [classifyAndAdvanceLadder]'s committed result: the same, plus the resulting ladder position. */
private data class ThermalDerateOutcome(val reason: String, val rungs: Int, val rung: Int)

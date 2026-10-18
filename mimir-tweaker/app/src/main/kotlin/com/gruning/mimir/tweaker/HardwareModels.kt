package com.gruning.mimir.tweaker

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal enum class CapabilityStatus {
    UNSUPPORTED,
    CALIBRATION_REQUIRED,
    SUPPORTED,
}

internal enum class TuningAxis {
    CPU,
    GPU,
    FAN,

    /**
     * The vendor `settings system performance_mode` key (the ODM `pservice` consumes it and moves
     * the CPU/GPU floors). A `settings` key like [FAN], but unlike fan it survives a reboot and is
     * the player's to change at any time, so its restore point is [VendorModeOriginRecord] and a
     * live value that is neither Mimir's nor the origin is left alone (see
     * [VendorModeOriginPolicy.decideRestore]).
     */
    VENDOR_MODE,
}

/**
 * **Safety switch for the vendor-mode transport. Shipped `true` after the Guardian restore entry
 * became real (2026-10-13).**
 *
 * The vendor mode survives a reboot, so a session that wrote it must be restorable after a crash or a
 * reboot. Only the Guardian can promise that, and it now can: [RestoreGuardianVendorModePutEntry]
 * carries the player's origin and the applied mode, is persistent (the cross-boot replay covers it),
 * and is restored by the same real port the daemon uses ([VendorModeOriginPolicy.decideRestore]).
 *
 * Kept as a switch rather than deleted so the axis can be shipped dark again without touching the
 * transport: while this is `false` the probe reports [TuningAxis.VENDOR_MODE] absent
 * (`supportedAxes`, [HardwareCapabilities.vendorPerformanceModePresent]), so the planner plans no
 * vendor mode, [PlanTargetValidator] refuses a hand-built one, and every device behaves exactly as it
 * did before the axis existed. Tests never depend on the shipped value: [HardwareProbe] takes the
 * switch as a constructor argument that defaults to this constant.
 */
internal const val VENDOR_MODE_TRANSPORT_ENABLED = true

internal data class CpuPolicy(
    val id: Int,
    val path: String,
    val cpuIds: List<Int>,
    val availableFrequenciesKhz: List<Int>,
    val minKhz: Int,
    val maxKhz: Int,
    val currentKhz: Int,
)

internal data class HardwareCapabilities(
    val status: CapabilityStatus,
    val manufacturer: String,
    val model: String,
    val socModel: String,
    val buildFingerprint: String,
    val deviceProfileId: String,
    val supportedAxes: Set<TuningAxis>,
    val pServerAvailable: Boolean,
    val cpuPolicies: List<CpuPolicy>,
    val gpuFrequenciesKhz: List<Int>,
    val gpuNodeAvailable: Boolean,
    val fanNodeAvailable: Boolean,
    val refreshRatesHz: List<Int>,
    val notes: List<String>,
    /** Product-qualified profile IDs only; local/lab CERTIFIED reports never populate this. */
    val certifiedProfileIds: Set<String> = emptySet(),
    /**
     * Chipset-support wave stage 1 : quiet/balanced/
     * performance ids whose Odin-anchored transposed CPU vector self-validates against this
     * device's own live OPP tables, computed by [SupportedUncertifiedProfiles] -- populated only
     * for a [hardwareFamilyId] carrying [TweakerHardwareFamilies.MIMIR_CERTIFIED_REFERENCE]
     * treatment. Disjoint from and never merged into [certifiedProfileIds]; never a signed-manifest
     * claim.
     *
     * As of stage 3  this is no longer informational: it participates in
     * `TweakerPostureTruth.mutationAvailable` and gates
     * [SupportedUncertifiedProfiles.admitsPlan], so these ids are genuinely selectable and
     * appliable. Still never a certification claim -- but the
     * surface does not label them: no certified/uncertified vocabulary reaches the player, and an
     * admitted profile is presented and held exactly like a signed one. This set remains internal
     * engineering truth about provenance; it carries no display or lifetime consequence.
     *
     * NOTE: as produced by `AndroidHardwareProbe` this set has NOT yet had the certified set
     * subtracted, because the real one is not resolved until `MimirTweakerService.capabilities()`.
     * Disjointness is established there, and again on the client at parse.
     */
    val supportedUncertifiedProfileIds: Set<String> = emptySet(),
    val posture: String = TweakerPostureTruth.posture,
    val mutationAvailable: Boolean = false,
    val dryRun: Boolean = true,
    val usageAccessGranted: Boolean? = null,
    val qualificationSource: String = TweakerPostureTruth.QUALIFICATION_NONE,
    /** Informational only; never profile authority. */
    val hardwareFamilyId: String? = null,
    /** General live-OPP or Mimir certified-reference treatment; never a signed qualification. */
    val profileTreatment: String? = null,
    /**
     * Optional shared kernel CPU-ceiling control selected by an exact device profile.
     * Null means the ordinary per-policy `scaling_max_freq` transport. This is live
     * transport truth, not qualification authority.
     */
    val cpuCeilingPath: String? = null,
    /**
     * Optional GPU-ceiling transport, the exact counterpart of [cpuCeilingPath]: null means the
     * ordinary `max_pwrlevel` index, non-null means the devfreq policy in kHz.
     *
     * Discovered ONCE by the probe and carried here rather than re-derived per site. Four places
     * need this answer -- capture, emission, apply-time verify and the expected-topology the capture
     * is checked against -- and an earlier draft let two of them test for the node differently
     * (`reader.exists` against resolving the symlink). They disagreed, and a disagreement here does
     * not read as a wrong node: it reads as CAPTURE_INCOMPLETE, with the capture blamed for a
     * topology someone else expected. One discovery, one answer.
     */
    val gpuCeilingPath: String? = null,
    /**
     * Per-policy **settled live `scaling_min_freq`**, the mutable launch/QoS floor -- keyed by
     * policy id, absent for any policy whose floor did not settle or read implausibly.
     *
     * Deliberately here on [HardwareCapabilities] and **not** on [CpuPolicy]. `AndroidHardware`'s
     * `detectCpuPolicies` states the invariant this preserves: *"cpuinfo_min_freq is immutable
     * policy capability. scaling_min_freq is a mutable launch/QoS floor and must never feed
     * planning or topology identity."* [CpuPolicy] IS topology identity -- it is what
     * `HardwareFingerprint.create` hashes -- so the mutable floor stays out of it and lives beside
     * the other live, non-identity capability facts ([cpuCeilingPath], [fanNodeAvailable]).
     *
     * The "never feed planning" half of that invariant is the half this field deliberately
     * revisits, and the seam is worth naming rather than quietly crossing. Its author was guarding
     * against a **transient** boost floor steering a plan. What arrives here is a bounded-settled
     * snapshot of a **persistent** floor, and refusing to look at it had a measured cost: the
     * Retroid Pocket Nova could never apply any profile, because `TuningPlanner` derived a
     * policy-3 ceiling of 1401600 against `cpuinfo_min_freq` while the device held
     * `scaling_min_freq = 1651200`, and apply-time admission correctly refused a ceiling below the
     * floor (`CPU_TARGET_BELOW_LIVE_FLOOR`, measured stable over five hours and identical at
     * `performance_mode` 0 and 1). A plan that cannot be written is not a safer plan.
     */
    val cpuSettledFloorsKhz: Map<Int, Int> = emptyMap(),
    /**
     * Vendor performance-mode axis present on THIS unit: `settings system performance_mode` reads
     * as an integer in `0..2` AND [pServerAvailable] (the attestation already proves the ODM
     * `pservice` that consumes the key). Pure capability, never a manufacturer or family test --
     * the family only selects the mode TABLE ([VendorModeTable]), it never gates the axis.
     *
     * [TuningPlanner.plan] emits [TuningPlan.vendorMode] only when [TuningAxis.VENDOR_MODE] is in
     * [supportedAxes], exactly as fan gates on `FAN in supportedAxes`. This flag and that axis are
     * set together by the probe, and both stay false whenever [VENDOR_MODE_TRANSPORT_ENABLED] is
     * false. It defaults to `false` so every fixture that does not set it is unchanged, with an
     * unchanged [PlanHash].
     */
    val vendorPerformanceModePresent: Boolean = false,
)

internal data class TelemetrySnapshot(
    val cpuCurrentKhz: Map<Int, Int>,
    val cpuMinKhz: Map<Int, Int>,
    val cpuMaxKhz: Map<Int, Int>,
    val gpuClockHz: Long?,
    val gpuBusyPercent: Int?,
    val gpuMinPowerLevel: Int?,
    val gpuMaxPowerLevel: Int?,
    /** devfreq policy ceiling in HERTZ -- the node's own unit, so no conversion can go missing. */
    val gpuDevfreqMaxHz: Int? = null,
    val fanDuty: Int?,
    val fanPeriod: Int?,
    val fanRpm: Int?,
    val cpuTempC: Int?,
    val gpuTempC: Int?,
    val batteryLevelPercent: Int?,
    val batteryCurrentUa: Int?,
    val batteryVoltageMv: Int?,
    val batteryPowerMw: Int?,
    val batteryTempDeciC: Int?,
    val batteryStatus: String?,
    val thermalStatus: Int?,
    /** Vendor read-only Settings.System `performance_mode`, when the handheld exposes it. */
    val vendorPerformanceMode: Int? = null,
    val vendorFanMode: Int? = null,
    /** Exact winning classified thermal zones; raw values are retained for audit. */
    val cpuThermalZone: ThermalZoneWinner? = null,
    val gpuThermalZone: ThermalZoneWinner? = null,
    val thermalInventory: CalibrationThermalInventory? = null,
    /** Effective per-policy ceiling: shared QoS when present, otherwise scaling_max_freq. */
    val cpuQosCeilingKhz: Map<Int, Int> = emptyMap(),
    /** Transport semantics make an OEM's unbounded shared QoS distinct from scaling_max_freq. */
    val cpuQosTransport: CpuQosTransport = CpuQosTransport.UNKNOWN,
    val bootId: String? = null,
    /**
     * User-facing package aggregate: a MEAN of [cpuPackageTempZoneCount] classified CPU zones
     * (a device profile may narrow the family), never a single package sensor. Safety continues
     * to use hottest [cpuTempC].
     */
    val cpuPackageTempC: Int? = null,
    /** How many thermal zones fed the [cpuPackageTempC] mean; the wire contract discloses it. */
    val cpuPackageTempZoneCount: Int? = null,
    /** Independent of battery status: Charge Separation may discharge while USB remains online. */
    val externalPowerState: ExternalPowerState = ExternalPowerState.UNKNOWN,
)

internal enum class ExternalPowerState {
    OFFLINE,
    USB,
    AC,
    WIRELESS,
    UNKNOWN,
}

internal enum class CpuQosTransport {
    UNKNOWN,
    POLICY_SCALING_MAX,
    SHARED_QOS,
}

/** Immutable boot-scoped inventory. Winners may move only within this exact classified family set. */
internal data class ThermalZoneIdentity(
    val family: String,
    val type: String,
    val path: String,
)

internal data class CalibrationThermalInventory(
    val cpu: List<ThermalZoneIdentity>,
    val gpu: List<ThermalZoneIdentity>,
) {
    fun containsCpu(zone: ThermalZoneWinner): Boolean =
        cpu.any { it.type == zone.type && it.path == zone.path && it.family == thermalZoneFamily(zone.type) }

    fun containsGpu(zone: ThermalZoneWinner): Boolean =
        gpu.any { it.type == zone.type && it.path == zone.path && it.family == thermalZoneFamily(zone.type) }
}

/** Kernel zone suffixes identify siblings, not a new sensor family (`gpuss-4`, `gpuss-5`). */
internal fun thermalZoneFamily(type: String): String =
    type.trim().lowercase().replace(Regex("[-_ ]?[0-9]+(?:[-_][0-9]+)*$"), "")

internal data class TuningPlan(
    val profileId: String,
    val packageName: String,
    val cpuMaxKhz: Map<Int, Int>,
    val gpuMaxPowerLevel: Int?,
    val fanMode: Int?,
    val calibrated: Boolean,
    /**
     * The vendor `performance_mode` this plan drives, or null = Mimir never writes the key (Stock,
     * and every device without the axis). Always one of [TuningPlanner.KNOWN_VENDOR_MODES] when
     * non-null. Appended to [PlanHash] only when non-null so every plan that predates the axis
     * keeps its identity. Last and defaulted so no existing construction site changes.
     */
    val vendorMode: Int? = null,
)

internal const val HARDWARE_SNAPSHOT_SCHEMA = 1
/**
 * Current durable journal writes. Schema 4 adds the exact ordered renderer mutation
 * projection. Schema 3 adds the MTJA-covered MGDE consume marker. Schema 2 retains the
 * full historical plan/order; schemas 1-3 remain recovery-readable but are never exact
 * renderer authority for MGDE.
 */
/** Current production writer; schema v5 stays pure/unwired until cross-store admission is live-proven. */
internal const val TRANSACTION_JOURNAL_SCHEMA = 4
internal const val TRANSACTION_JOURNAL_SCHEMA_V5 = 5
internal const val TRANSACTION_JOURNAL_SCHEMA_V4 = 4
internal const val TRANSACTION_JOURNAL_SCHEMA_V3 = 3
internal const val TRANSACTION_JOURNAL_SCHEMA_V2 = 2
internal const val TRANSACTION_JOURNAL_SCHEMA_V1 = 1

/** Canonical SHA-256 of the exact plan identity used for an apply. */
internal object PlanHash {
    fun of(plan: TuningPlan): String = ofPersisted(PersistedTuningPlan.from(plan))

    fun ofPersisted(plan: PersistedTuningPlan): String {
        val canonical = buildString {
            append(plan.profileId.trim())
            append('|').append(plan.packageName.trim())
            plan.cpuMaxKhz.toSortedMap().forEach { (policyId, khz) ->
                append("|cpu").append(policyId).append('=').append(khz)
            }
            append("|gpu=").append(plan.gpuMaxPowerLevel ?: "null")
            append("|fan=").append(plan.fanMode ?: "null")
            append("|calibrated=").append(plan.calibrated)
            // Appended ONLY when non-null: a journal that was active across the upgrade (or any plan
            // on a device without the axis) must reconstruct its original identity byte for byte.
            plan.vendorMode?.let { append("|vmode=").append(it) }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

/**
 * Full historical plan identity persisted before PREPARED so package replacement cannot
 * rebind restore to a later tuning formula.
 */
internal data class PersistedTuningPlan(
    val profileId: String,
    val packageName: String,
    val cpuMaxKhz: Map<Int, Int>,
    val gpuMaxPowerLevel: Int?,
    val fanMode: Int?,
    val calibrated: Boolean,
    /** See [TuningPlan.vendorMode]. Null for every plan journaled before the axis existed. */
    val vendorMode: Int? = null,
) {
    fun toTuningPlan(): TuningPlan = TuningPlan(
        profileId = profileId,
        packageName = packageName,
        cpuMaxKhz = cpuMaxKhz,
        gpuMaxPowerLevel = gpuMaxPowerLevel,
        fanMode = fanMode,
        calibrated = calibrated,
        vendorMode = vendorMode,
    )

    companion object {
        fun from(plan: TuningPlan): PersistedTuningPlan = PersistedTuningPlan(
            profileId = plan.profileId,
            packageName = plan.packageName,
            cpuMaxKhz = plan.cpuMaxKhz.toSortedMap().toMap(),
            gpuMaxPowerLevel = plan.gpuMaxPowerLevel,
            fanMode = plan.fanMode,
            calibrated = plan.calibrated,
            vendorMode = plan.vendorMode,
        )
    }
}

/**
 * Exact renderer output captured before PREPARED. These are applied-state facts, not
 * restore instructions: stock ownership/mode remains authoritative in [HardwareSnapshot].
 */
internal data class PersistedAppliedMutation(
    val logicalId: String,
    val axis: TuningAxis,
    val canonicalPath: String,
    val kind: LogicalMutationKind,
    val value: Int?,
    val expectPresent: Boolean,
) {
    companion object {
        fun from(mutation: LogicalMutation): PersistedAppliedMutation =
            PersistedAppliedMutation(
                logicalId = mutation.logicalId,
                axis = mutation.axis,
                canonicalPath = mutation.canonicalPath,
                kind = mutation.kind,
                value = mutation.value,
                expectPresent = mutation.expectPresent,
            )
    }
}

/**
 * Canonical byte identity for immutable projection preservation across newly signed
 * phase/consume records. List order and every persisted field participate.
 */
internal object AppliedMutationProjection {
    private const val TAG = "MIMIR_APPLIED_MUTATION_PROJECTION"
    private const val VERSION = 1

    fun from(mutations: List<LogicalMutation>): List<PersistedAppliedMutation> =
        mutations.map(PersistedAppliedMutation::from).toList()

    fun canonicalBytes(projection: List<PersistedAppliedMutation>): ByteArray =
        ByteArrayOutputStream().also { sink ->
            DataOutputStream(sink).use { out ->
                out.writeString(TAG)
                out.writeInt(VERSION)
                out.writeInt(projection.size)
                projection.forEach { mutation ->
                    out.writeString(mutation.logicalId)
                    out.writeString(mutation.axis.name)
                    out.writeString(mutation.canonicalPath)
                    out.writeString(mutation.kind.name)
                    out.writeBoolean(mutation.value != null)
                    mutation.value?.let(out::writeInt)
                    out.writeBoolean(mutation.expectPresent)
                }
            }
        }.toByteArray()

    fun sameCanonical(
        first: List<PersistedAppliedMutation>,
        second: List<PersistedAppliedMutation>,
    ): Boolean = canonicalBytes(first).contentEquals(canonicalBytes(second))

    /**
     * The ONE lawful way a durable applied projection may change: a WAVE2 thermal-derate rung
     * amending the record of what is applied, strictly downward.
     *
     * [sameCanonical] freezes the projection because two consumers treat it as executable
     * authority -- the drift watchdog's `AppliedTargetContract`, and
     * `TransactionCoordinator.repairAppliedCpuQosBound`, which re-writes projection values to
     * hardware under Guardian authority. If any write could smuggle in a FASTER projection,
     * overclocked hardware would read as contract-compliant and "repair" would become an
     * overclock actuator. That is why this predicate permits movement in one direction only:
     * even a compromised caller can persist nothing but a slower machine.
     *
     * Everything except [PersistedAppliedMutation.value] must stay byte-identical, in the same
     * list order and length, so this can never be used to re-shape the projection. Direction
     * mirrors `applyDerate`'s own plan-level gate exactly (TransactionCoordinator's
     * "faster than the currently applied ceiling" checks): lower kHz is slower, and a HIGHER
     * `max_pwrlevel` index is slower. Any axis that is not CPU-ceiling or GPU-max -- the fan
     * above all -- must not move at all.
     *
     * `gpu:devfreq_max_freq` joins that list counted the OTHER way: it is a frequency in HERTZ, the
     * node's own unit, so slower is a SMALLER number -- the same sense as the CPU and the opposite
     * of `max_pwrlevel`.
     */
    fun isMonotonicallySlowerAmendment(
        existing: List<PersistedAppliedMutation>,
        candidate: List<PersistedAppliedMutation>,
    ): Boolean {
        if (existing.size != candidate.size) return false
        return existing.zip(candidate).all { (before, after) ->
            before.logicalId == after.logicalId &&
                before.axis == after.axis &&
                before.canonicalPath == after.canonicalPath &&
                before.kind == after.kind &&
                before.expectPresent == after.expectPresent &&
                isSlowerOrUnchanged(before, after)
        }
    }

    private fun isSlowerOrUnchanged(
        before: PersistedAppliedMutation,
        after: PersistedAppliedMutation,
    ): Boolean {
        val previous = before.value
        val next = after.value
        if ((previous == null) != (next == null)) return false
        if (previous == null || next == null) return true
        // ONE law, stated twice because the two GPU ceilings are counted in opposite directions:
        // A DERATE MAY NEVER MOVE TOWARD FASTER.
        //
        //   cpu                 kHz,   smaller is slower  -> next <= previous
        //   gpu:max_pwrlevel    index, LARGER  is slower  -> next >= previous
        //   gpu:devfreq_max_freq Hz,   smaller is slower  -> next <= previous
        //
        // The comparator is the only place that sense is written down, so it is pinned as a table
        // over both ids rather than left to be re-derived by whoever adds the third transport.
        return when {
            before.axis == TuningAxis.CPU -> next <= previous
            before.logicalId == HardwareSnapshot.GPU_MAX_LOGICAL_ID -> next >= previous
            before.logicalId == HardwareSnapshot.GPU_DEVFREQ_MAX_LOGICAL_ID -> next <= previous
            else -> next == previous
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }
}

/**
 * Schema-v1 recovery-only apply-order reconstruction from the known historical
 * profile/snapshot formula. Never used for new durable writes.
 */
internal object HistoricApplyOrder {
    fun v1LogicalIds(profileId: String, snapshot: HardwareSnapshot): List<String> {
        val cpus = snapshot.nodes
            .filter { it.axis == TuningAxis.CPU }
            .sortedBy { node ->
                node.logicalId.removePrefix("cpu:").substringBefore(':').toIntOrNull() ?: Int.MAX_VALUE
            }
            .map { it.logicalId }
        val gpus = listOfNotNull(
            snapshot.nodes.firstOrNull { it.logicalId == HardwareSnapshot.GPU_MIN_LOGICAL_ID }?.logicalId,
            snapshot.nodes.firstOrNull { it.logicalId == HardwareSnapshot.GPU_MAX_LOGICAL_ID }?.logicalId,
        )
        val fan = snapshot.nodes.firstOrNull { it.logicalId == HardwareSnapshot.FAN_LOGICAL_ID }?.logicalId
        val compute = cpus + gpus
        val cooling = listOfNotNull(fan)
        return if (profileId == "performance") cooling + compute else compute + cooling
    }

    fun resolveLogicalIds(record: TransactionJournalRecord): List<String> {
        return if (record.schemaVersion >= TRANSACTION_JOURNAL_SCHEMA_V2) {
            record.applyOrderLogicalIds
        } else {
            v1LogicalIds(record.profileId, record.snapshot)
        }
    }
}

/**
 * The shared PM QoS CPU ceiling node. Declared here, in the pure-data file, because the restore rule
 * below has to be readable from MGDE — which runs on a plain JVM and must not drag Android in.
 * [HardwareProbe.CPU_QOS_MAX_PATH] reads from this rather than the other way round, so there is one
 * spelling of the path in the module.
 */
internal const val SHARED_CPU_QOS_CEILING_PATH = "/sys/kernel/msm_performance/parameters/cpu_max_freq"

/** No request. The value this node reads for a CPU nothing is holding down. */
internal const val UNPINNED_CPU_CEILING = Int.MAX_VALUE

/**
 * The mode Mimir chmods the shared QoS node to while a session holds it, to keep the vendor out.
 * `CpuCeilingTransport.ACTIVE_SHARED_QOS_MODE` reads from this, so there is one spelling in the module.
 */
internal const val HELD_CPU_QOS_MODE = "444"

/** What that node reads when nobody is holding it — its sibling `cpu_min_freq` reads the same. */
internal const val UNHELD_CPU_QOS_MODE = "644"

/**
 * **What a restore WRITES — which is not always what was captured.** Policy: *"Stock unpins the
 * request"* — Stock stops replaying a remembered number and withdraws Mimir's ceiling request entirely,
 * letting the kernel's own maximum stand.
 *
 * ## Why replaying the number is the bug
 *
 * [StockCeilingBaseline] exists because a remembered number can be the wrong one: on the Thor,
 * 2026-09-19, a re-arm captured Quiet's own clamp as stock, and every later "Stock" faithfully wrote the
 * clamp back and confirmed it. That fix makes the FIRST capture of a boot authoritative, which is a real
 * improvement and still cannot help a boot whose first capture already finds the hardware clamped —
 * *"there is no evidence left on the device to recover the true value from"*.
 *
 * Withdrawing needs no evidence. It is the one restore that cannot be wrong about what stock was.
 *
 * ## Why only CPU, and only on the shared node
 *
 * This node is a PM QoS **requester**: its sysfs read returns its own stored request, and
 * [UNPINNED_CPU_CEILING] means "no request". Measured on the Thor 2026-09-21 — a max request below a
 * live vendor min stored, verified and governed, so the node genuinely arbitrates rather than recording.
 * Writing the sentinel therefore withdraws, and reads back as the sentinel, so every exact readback in
 * this module still holds.
 *
 * Per-policy `scaling_max_freq` keeps replaying [TopologyNodeState.originalValue], because it has no
 * withdrawal primitive: the sentinel is clamped to the policy maximum on write and the readback then
 * differs from what was written. Same "no qualified chassis reaches it" reasoning that retired the live
 * floor clamp, applied in the other direction — keep that path working rather than make it refuse.
 *
 * GPU is deliberately out of scope. `devfreq/max_freq` is `-r--r--r--` even to root (2026-08-31), and
 * `max_pwrlevel = 0` is a number like any other, not a withdrawal. There is nothing to withdraw with.
 *
 * ## The property that matters most
 *
 * An unpinned restore leaves the node in exactly the state `CpuCeilingTransport.selectedPath` requires —
 * every cpu of a policy reading one distinct value. A replayed `originalValue` cannot promise that: a
 * capture taken while one cpu was stranded restores that stranding, and the next install is silently
 * demoted to the vendor-owned transport. That is the whole of the 2026-09-21 diagnosis, and this is its
 * structural fix rather than another patch on the remembered number.
 */
internal fun restoreWriteValue(node: TopologyNodeState): Int? =
    if (node.axis == TuningAxis.CPU && node.canonicalPath == SHARED_CPU_QOS_CEILING_PATH) {
        UNPINNED_CPU_CEILING
    } else {
        node.originalValue
    }

/**
 * The axes the vendor governor takes back, which therefore cannot veto a WITHDRAWAL.
 *
 * Measured 2026-08-31 on the reference Nova: `gpu:devfreq_max_freq` is `-r--r--r--` even to root, and a
 * `gpu:max_pwrlevel` write is released by the vendor governor after roughly two seconds, which also
 * clamps `gpu:min_pwrlevel` back off any floor we widened. Not a chassis quirk — it is how this GPU
 * stack arbitrates, and no amount of retrying changes it.
 */
internal val UNHOLDABLE_AXIS_LOGICAL_IDS = setOf(
    HardwareSnapshot.GPU_MIN_LOGICAL_ID,
    HardwareSnapshot.GPU_MAX_LOGICAL_ID,
    HardwareSnapshot.GPU_DEVFREQ_MAX_LOGICAL_ID,
)

/**
 * **An axis the hardware will not hold cannot veto Stock**.
 *
 * Initially applied where the drift SUPERVISOR watches: `HardwareSessionSupervisor` sets
 * `gpuAxisUnholdable` and stops tearing a session down over a GPU it can see the vendor reclaiming. It
 * was never applied to the paths that verify a WITHDRAWAL, and those are the ones where refusing costs
 * the most.
 *
 * Measured on the Thor, 2026-09-21 17:29:30, pressing Stock on a live Quiet session:
 *
 * ```
 * Guardian stock unconfirmed persistentAxes=1 FIRST_READBACK_NOT_STOCK entries=6 mismatched=1
 *                            axes=gpu:min_pwrlevel=4/644/0/0!=0
 * requested=stock prior=quiet result=Rejected(code=SYSTEM_STOCK_NOT_VERIFIED)
 * ```
 *
 * One mismatch in six, and it is the GPU minimum power level rebounding 0 -> 4 while the CPU ceiling
 * released cleanly — value and permissions both, `mode=644`, every policy at `INT_MAX`. Because the
 * SELECTION was rejected the standing profile stayed `quiet`, so the re-arm watcher put Quiet back on
 * fourteen seconds later. The withdrawal worked and the Tweaker turned itself back on.
 *
 * **Refusing Stock is the worst outcome available here.** Every other refusal in this module leaves the
 * device at stock — a less useful fallback for a rejected tuning request. Refusing a RESTORE inverts the safety argument: it leaves the device tuned, and then re-arms.
 *
 * Narrow on purpose, in two directions.
 *
 * **Which axes.** Only [UNHOLDABLE_AXIS_LOGICAL_IDS] — the measured unholdable axes. The CPU
 * ceiling and the fan still veto, because those are the axes a withdrawal can genuinely guarantee: the
 * shared QoS node stores what it is given ([restoreWriteValue]) and `fan_mode` is a Settings key. A
 * Stock that could not confirm the CPU came down would be worth nothing.
 *
 * **Which checks.** This belongs to the checks that ask whether a write STAYED, all of which sample
 * repeatedly over a window: the guardian's double readback with its settle gap,
 * `verifySnapshotOnce`'s eleven-sample window, and `verifyAppliedPlan`'s projected-mutation branch,
 * which runs inside the same eleven-sample loop. The per-mutation readback in the RESTORE loop is
 * deliberately left alone: it runs once, at the instant of the write, so it asks whether the write
 * LANDED, and a mismatch there is a real failed write rather than a governor reclaiming an axis.
 *
 * **The apply path was the third site, and the device found it.** On 2026-09-21, after the first two
 * were fixed, Quiet was still refused:
 *
 * ```
 * 21:52:26.063  verify attempts=11 finalPasses=0 failed=projected mutation gpu:min_pwrlevel mismatch
 * 21:52:34.676  verify attempts=11 finalPasses=0 failed=projected mutation gpu:min_pwrlevel mismatch
 * 21:52:39.483  requested=quiet prior=stock result=Rejected(code=VERIFY_FAILED)
 * ```
 *
 * Twenty-two seconds, the whole apply run twice, then refused outright — with the node watcher
 * showing `gpu=0` at the write and `gpu=4` three seconds later, the reclaim this rule exists for.
 * `HardwareSnapshot.GPU_CEILING_LOGICAL_IDS` covers `max_pwrlevel` and `devfreq_max_freq` but not
 * `min_pwrlevel`, so the min alone fell through to an exact readback that nothing could satisfy.
 *
 * Suppressing the veto must not suppress the observation, so
 * [GuardianDoubleStockReadbackPolicy.describe] still names these axes on the success path as
 * `unheld=`, and the guardian logs that line unconditionally. An axis that stops holding is reported;
 * it just no longer holds the player hostage.
 */
internal fun vetoesWindowedVerification(logicalId: String): Boolean =
    logicalId !in UNHOLDABLE_AXIS_LOGICAL_IDS && logicalId !in PLAYER_OWNED_AXIS_LOGICAL_IDS

/**
 * The axes that belong to the PLAYER, which therefore cannot veto a verification either -- the sibling
 * of [UNHOLDABLE_AXIS_LOGICAL_IDS], kept separate because the reason is different and
 * `StockUnholdableAxisTest` pins that set to the three GPU nodes the hardware itself reclaims.
 *
 * The vendor performance mode is switched by the player in the vendor UI at any time, and the
 * design is that their choice stands: a restore finding a mode that is neither Mimir's nor the
 * origin leaves it alone and reports the axis confirmed. A windowed check that then demanded
 * `live == origin` would hold Stock hostage to that choice forever, and one that demanded
 * `live == applied` would tear a profile down because the player used their own settings screen
 * (which the supervisor deliberately treats as a yield). The write itself is verified once, at the
 * instant of the write, by the per-mutation readback; that is the whole job of the mode check.
 * Like its sibling, the observation is kept: the axis is still named `unheld=`.
 */
internal val PLAYER_OWNED_AXIS_LOGICAL_IDS = setOf(
    HardwareSnapshot.VENDOR_MODE_LOGICAL_ID,
)

/**
 * **The permissions have the same poisoning problem the value did, and the same answer.**
 *
 * Measured on the Thor 2026-09-21, immediately after the value rule shipped: a deliberately
 * poisoned install captured `originalMode = "444"` along with the clamp, and the restore faithfully
 * replayed it. Stock left the node correctly unpinned and still read-only, with `session=Stock` and
 * nothing holding it. The value was repaired and the mode was not.
 *
 * Narrower than the value rule on purpose. [HELD_CPU_QOS_MODE] is **Mimir's own constant** — nothing
 * else on this node sets it — so a capture that reads it back is by construction a capture taken while
 * some session was holding the node, which is exactly a capture that cannot be stock. Every other mode
 * is left alone, including on a chassis whose natural mode is something this code has never seen. A
 * clean capture already reads [UNHELD_CPU_QOS_MODE], so on a healthy device this substitutes nothing.
 */
internal fun restoreWriteMode(node: TopologyNodeState): String? =
    if (node.axis == TuningAxis.CPU &&
        node.canonicalPath == SHARED_CPU_QOS_CEILING_PATH &&
        node.originalMode == HELD_CPU_QOS_MODE
    ) {
        UNHELD_CPU_QOS_MODE
    } else {
        node.originalMode
    }

/** One topology-bound axis node that a plan may mutate or must restore. */
internal data class TopologyNodeState(
    val logicalId: String,
    val axis: TuningAxis,
    val canonicalPath: String,
    val originalValue: Int?,
    val originalMode: String?,
    val originalUid: Int?,
    val originalGid: Int?,
    val present: Boolean,
)

/**
 * Versioned, topology-bound stock snapshot for every axis a plan will mutate.
 * Value-only maps are derived views — never the durability authority.
 */
internal data class HardwareSnapshot(
    val schemaVersion: Int,
    val hardwareFingerprint: String,
    val bootIdentity: String,
    val nodes: List<TopologyNodeState>,
) {
    val cpuMinKhz: Map<Int, Int>
        get() = nodes.mapNotNull { node ->
            val policyId = node.logicalId.removePrefix("cpu:").substringBefore(':').toIntOrNull()
                ?: return@mapNotNull null
            if (!node.logicalId.endsWith(":scaling_min_freq") || !node.present) return@mapNotNull null
            val value = node.originalValue ?: return@mapNotNull null
            policyId to value
        }.toMap()

    val cpuMaxKhz: Map<Int, Int>
        get() = nodes.mapNotNull { node ->
            val policyId = node.logicalId.removePrefix("cpu:").substringBefore(':').toIntOrNull()
                ?: return@mapNotNull null
            if (!node.logicalId.endsWith(":scaling_max_freq") || !node.present) return@mapNotNull null
            val value = node.originalValue ?: return@mapNotNull null
            policyId to value
        }.toMap()

    val gpuMinPowerLevel: Int?
        get() = nodes.firstOrNull { it.logicalId == GPU_MIN_LOGICAL_ID && it.present }?.originalValue

    val gpuMaxPowerLevel: Int?
        get() = nodes.firstOrNull { it.logicalId == GPU_MAX_LOGICAL_ID && it.present }?.originalValue

    val fanMode: Int?
        get() = nodes.firstOrNull { it.logicalId == FAN_LOGICAL_ID && it.present }?.originalValue

    val fanPresent: Boolean
        get() = nodes.firstOrNull { it.logicalId == FAN_LOGICAL_ID }?.present == true

    /** The captured vendor `performance_mode` (the honest origin on a re-arm), if the node was captured. */
    val vendorMode: Int?
        get() = nodes.firstOrNull { it.logicalId == VENDOR_MODE_LOGICAL_ID && it.present }?.originalValue

    val vendorModePresent: Boolean
        get() = nodes.firstOrNull { it.logicalId == VENDOR_MODE_LOGICAL_ID }?.present == true

    val gpuDevfreqMaxHz: Int?
        get() = nodes.firstOrNull { it.logicalId == GPU_DEVFREQ_MAX_LOGICAL_ID && it.present }?.originalValue

    companion object {
        const val GPU_MIN_LOGICAL_ID = "gpu:min_pwrlevel"
        const val GPU_MAX_LOGICAL_ID = "gpu:max_pwrlevel"

        /**
         * The GPU ceiling expressed as a devfreq POLICY, in kHz — the other way this chassis family
         * can be capped, and on some of them the only way that holds.
         *
         * A SEPARATE id rather than a second meaning for [GPU_MAX_LOGICAL_ID], because the two carry
         * different UNITS and opposite senses: `max_pwrlevel` is an index where LARGER is slower,
         * this is a frequency where SMALLER is slower. Overloading one id would put that distinction
         * somewhere implicit, and a sign hidden inside a comparison is precisely the class of bug
         * this tree spent 2026-08-25 removing from the frontend.
         *
         * Exactly one of the two is written per apply — see `GpuCeilingTransport`. They are
         * alternatives, never a pair.
         */
        const val GPU_DEVFREQ_MAX_LOGICAL_ID = "gpu:devfreq_max_freq"

        /** Every id that can carry the GPU CEILING, whichever transport this chassis admits. */
        val GPU_CEILING_LOGICAL_IDS = setOf(GPU_MAX_LOGICAL_ID, GPU_DEVFREQ_MAX_LOGICAL_ID)

        const val FAN_LOGICAL_ID = "fan:fan_mode"
        const val FAN_SETTINGS_PATH = "settings:system:fan_mode"

        /**
         * The vendor performance-mode node (`settings system performance_mode`), consumed by the
         * ODM `pservice` (polls about every 10 s). Declared beside the fan constants because it is
         * the same kind of axis: a `settings` key written with `SETTINGS_PUT`, bounded at execute to
         * [TuningPlanner.KNOWN_VENDOR_MODES], and read back directly. Unlike fan it survives a
         * reboot, which is why its restore point is [VendorModeOriginRecord], not a boot-keyed
         * baseline.
         */
        const val VENDOR_MODE_LOGICAL_ID = "vendor:performance_mode"
        const val VENDOR_MODE_SETTINGS_PATH = "settings:system:performance_mode"
    }
}

internal data class CaptureOutcome(
    val snapshot: HardwareSnapshot? = null,
    val failures: List<String> = emptyList(),
    /**
     * Stable, machine-readable admission outcome for pre-PREPARED observations.
     * Null retains the historical generic capture-incomplete contract for callers that
     * do not have a more precise reason.
     */
    val code: String? = null,
    /**
     * CPU targets as ACTUALLY admitted. Non-empty only when a target sat below its policy's live
     * floor and was raised to it (a clamped underclock beats refusing and
     * leaving the device at Stock). The caller MUST adopt these into the plan it journals: the
     * write, the readback, the applied contract and the supervisor's drift check all compare the
     * same numbers, and a plan that disagrees with what was written is what produced a restore/
     * re-apply loop the first time this was attempted.
     */
    val admittedCpuMaxKhz: Map<Int, Int> = emptyMap(),
) {
    val ok: Boolean get() = snapshot != null && failures.isEmpty()
}

internal enum class CheckedCommandStatus {
    CONFIRMED,
    FAILED,
    AMBIGUOUS,
}

internal data class CheckedCommandResult(
    val status: CheckedCommandStatus,
    val output: String? = null,
    val exitCode: Int? = null,
    val detail: String = "",
    /**
     * F2 (fan fence, 2026-10-14): the command was refused because the Guardian's terminal fence is
     * closed for this generation -- the Guardian has already restored (or is restoring) Stock. Set
     * only by `GuardianFencedHardwareCommandPort`, typed so `TransactionCoordinator` can name the
     * session dead (`GUARDIAN_TERMINAL_FENCE`) instead of reporting one more failed repair into it.
     */
    val guardianTerminal: Boolean = false,
)

internal enum class LogicalMutationKind {
    SYSFS_WRITE,
    SETTINGS_PUT,
    SETTINGS_DELETE,
}

/** One allowlisted, integer-bounded mutation with exact readback identity. */
internal data class LogicalMutation(
    val logicalId: String,
    val axis: TuningAxis,
    val canonicalPath: String,
    val kind: LogicalMutationKind,
    val value: Int?,
    val restoreMode: String?,
    val restoreUid: Int? = null,
    val restoreGid: Int? = null,
    val expectPresent: Boolean,
    /**
     * Restore-only: SETTINGS_PUT may carry the exact captured vendor stock value
     * (including values outside [TuningPlanner.KNOWN_FAN_MODES], e.g. stock `0`).
     * Apply mutations must leave this false and stay limited to known Mimir modes.
     */
    val capturedSnapshotAuthority: Boolean = false,
    /**
     * Restore-only, [TuningAxis.FAN] only: this fan step comes AFTER the vendor-mode restore in the
     * restore order ([RestoreMutationOrder]), so it is the last mutation and the one that must leave
     * the captured value behind SystemUI's coupled rewrite. The port may skip the fan-before-compute
     * settlement barrier on it when nothing needs writing. Like [vendorModeContext] it is execution
     * context, deliberately NOT part of [PersistedAppliedMutation]: apply mutations always leave it
     * false, and the restore derives it from the order every time.
     */
    val fanRestoreAfterVendorMode: Boolean = false,
    /**
     * [TuningAxis.VENDOR_MODE] only; null for every other axis. The execution side of the vendor
     * mode needs facts a bare `value` cannot carry (the honest origin to persist before the write,
     * the value Mimir applied so a restore can tell its own write from the player's), and the
     * mutation is the only thing `execute` is given. Deliberately NOT part of
     * [PersistedAppliedMutation] / [AppliedMutationProjection]: the journal already holds the
     * origin (snapshot node) and the applied value (plan), and a canonical-byte change would
     * orphan every durable projection.
     */
    val vendorModeContext: VendorModeMutationContext? = null,
)

/** See [LogicalMutation.vendorModeContext]. */
internal data class VendorModeMutationContext(
    /** The player's mode before Mimir first changed the key (snapshot node value, never a live read). */
    val origin: Int,
    /** The mode Mimir applies (apply) or applied (restore). */
    val applied: Int,
    /** Audit only: stored in [VendorModeOriginRecord.transactionId]. */
    val transactionId: String,
)

internal data class TransactionResult(
    val success: Boolean,
    val applied: Boolean,
    val restored: Boolean,
    val code: String,
    val message: String,
    val plan: TuningPlan? = null,
    val transactionId: String? = null,
    val requestId: String? = null,
    val generation: Long? = null,
    val requestDeadlineElapsedRealtimeMs: Long? = null,
    val hardLeaseDeadlineElapsedRealtimeMs: Long? = null,
)

/** Read-only verdict for the exact Stock record restored before a repeated lab drill. */
internal data class StockSettlement(
    val settled: Boolean,
    val code: String,
    val message: String,
    val observedWindowMs: Long,
    val consecutiveCleanRounds: Int,
    val consecutiveCleanWindowMs: Long,
    val transactionId: String? = null,
    val generation: Long? = null,
)

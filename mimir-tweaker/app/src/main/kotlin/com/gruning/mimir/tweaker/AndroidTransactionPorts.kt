package com.gruning.mimir.tweaker

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import com.gruning.mimir.tweaker.mgde.MgdeAdmissionConsumePort
import com.gruning.mimir.tweaker.mgde.MgdeConsumeIdentity
import com.gruning.mimir.tweaker.mgde.MgdeConsumeOutcome
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

internal fun interface PosixNodeMetadataReader {
    fun read(path: String): PosixNodeMetadata?
}

internal data class PosixNodeMetadata(
    val mode: String,
    val uid: Int,
    val gid: Int,
)

internal object OsPosixNodeMetadataReader : PosixNodeMetadataReader {
    override fun read(path: String): PosixNodeMetadata? = runCatching {
        val stat = Os.lstat(path)
        PosixNodeMetadata(
            mode = Integer.toOctalString(stat.st_mode and 0x1FF),
            uid = stat.st_uid,
            gid = stat.st_gid,
        )
    }.getOrNull()
}

/**
 * Whether a node EXISTS, as a positive answer. Only `ENOENT`/`ENOTDIR` -- the kernel saying there is no such file --
 * is [NodePresence.ABSENT]; a node that stats is [NodePresence.PRESENT]; any other failure (`EACCES`, `EIO`, an
 * unexpected throw) is [NodePresence.UNKNOWN], never "absent". A failed or timed-out READ of a node says nothing about
 * whether the node is there, so it can never stand in for this.
 */
internal object OsNodePresenceProbe : NodePresenceProbe {
    override fun probe(path: String): NodePresence = try {
        Os.lstat(path)
        NodePresence.PRESENT
    } catch (error: android.system.ErrnoException) {
        if (error.errno == OsConstants.ENOENT || error.errno == OsConstants.ENOTDIR) {
            NodePresence.ABSENT
        } else {
            NodePresence.UNKNOWN
        }
    } catch (_: Throwable) {
        NodePresence.UNKNOWN
    }
}

internal class AndroidHardwareStatePort(
    private val probe: HardwareProbe,
    private val capabilities: () -> HardwareCapabilities,
    private val posix: PosixNodeMetadataReader = OsPosixNodeMetadataReader,
    private val sleeper: (Long) -> Unit = { millis -> Thread.sleep(millis) },
    /**
     * Defaulted seam, byte-identical production behaviour. [FileSysfsCanonicalResolver] resolves
     * `/sys/...` through the host's own filesystem, and on a Windows host that yields `C:\sys\...`,
     * which [SysfsPath.canonicalize] refuses -- so a readback test fed the real resolver answers
     * "mismatch" before it ever reads a node, on Windows only. A test that is about WHAT a verifier
     * does with a rebounding node must be able to take the host out of the question.
     */
    private val sysfsResolver: SysfsCanonicalResolver = FileSysfsCanonicalResolver,
) : HardwareStatePort {
    /** Immutable topology admitted for the current process-local transaction. */
    private var transactionCapabilities: HardwareCapabilities? = null

    override fun captureForPlan(plan: TuningPlan, capabilities: HardwareCapabilities): CaptureOutcome {
        val capture = probe.captureForPlan(plan, capabilities)
        transactionCapabilities = capabilities.takeIf { capture.ok && capture.snapshot != null }
        return capture
    }

    override fun verify(plan: TuningPlan): Boolean = verifyPlan(plan).ok

    override fun verifyPlan(plan: TuningPlan): PlanVerification {
        val admitted = transactionCapabilities ?: capabilities()
        return verifyFullWindow { verifyPlanOnce(plan, admitted) }
    }

    override fun verifyAppliedPlan(
        plan: TuningPlan,
        mutations: List<LogicalMutation>,
    ): PlanVerification {
        val admitted = transactionCapabilities ?: capabilities()
        val planOwnedLogicalIds = buildSet {
            plan.cpuMaxKhz.keys.forEach { add("cpu:$it:scaling_max_freq") }
            // Either ceiling id is plan-owned; verifyPlanOnce above already checked whichever one
            // governs, so neither may be re-checked here as an unowned projected mutation.
            if (plan.gpuMaxPowerLevel != null) addAll(HardwareSnapshot.GPU_CEILING_LOGICAL_IDS)
            if (plan.fanMode != null) add(HardwareSnapshot.FAN_LOGICAL_ID)
        }
        // Axes the vendor governor reclaims are reported here, not enforced -- see
        // [vetoesWindowedVerification]. This branch runs INSIDE [verifyFullWindow], eleven samples
        // across about 1.3 s, so it is asking whether the write stayed; `gpu:min_pwrlevel` cannot
        // answer that on this chassis and was refusing roughly one apply in three outright.
        val unheld = linkedSetOf<String>()
        val verification = verifyFullWindow {
            val failures = verifyPlanOnce(plan, admitted).failures.toMutableList()
            mutations.filterNot { it.logicalId in planOwnedLogicalIds }.forEach { mutation ->
                if (!readback(mutation)) {
                    if (vetoesWindowedVerification(mutation.logicalId)) {
                        failures += "projected mutation ${mutation.logicalId} mismatch"
                    } else {
                        unheld += mutation.logicalId
                    }
                }
            }
            PlanVerification(failures.isEmpty(), failures)
        }
        if (unheld.isNotEmpty()) {
            // Suppressing a veto must not suppress the observation -- the same reason the guardian
            // names its unheld axes on the success path. An axis that stops holding is reported.
            runCatching {
                android.util.Log.i(
                    "MimirVerifyWindow",
                    "applied-plan unheld=" + unheld.joinToString(","),
                )
            }
        }
        return verification
    }

    private fun verifyPlanOnce(
        plan: TuningPlan,
        liveCaps: HardwareCapabilities,
    ): PlanVerification {
        val failures = mutableListOf<String>()
        val sharedQosRaw = if (plan.cpuMaxKhz.keys.any { policyId ->
                liveCaps.cpuPolicies.firstOrNull { it.id == policyId }
                    ?.let { CpuCeilingTransport.pathFor(it, liveCaps) }
                    ?.let(CpuCeilingTransport::isSharedQos) == true
            }
        ) {
            probe.cpuSharedQosRaw()
        } else {
            null
        }
        plan.cpuMaxKhz.forEach { (policyId, target) ->
            val policy = liveCaps.cpuPolicies.firstOrNull { it.id == policyId }
            val actualMin = policy?.let {
                probe.sysfsIntRaw("${it.path}/scaling_min_freq")
            }
            val ceilingPath = policy?.let { CpuCeilingTransport.pathFor(it, liveCaps) }
            val actual = if (ceilingPath != null && CpuCeilingTransport.isSharedQos(ceilingPath)) {
                policy?.let { CpuCeilingTransport.policyValue(sharedQosRaw, it.cpuIds) }
            } else if (ceilingPath != null) {
                probe.sysfsIntRaw(ceilingPath)
            } else {
                null
            }
            if (actualMin == null || actualMin <= 0 || actualMin > target) {
                failures += "cpu policy $policyId floor expected <= $target got $actualMin"
            }
            if (actual != target) failures += "cpu policy $policyId expected $target got $actual"
        }
        plan.gpuMaxPowerLevel?.let { target ->
            // Read back through the node that GOVERNS. Verifying `max_pwrlevel` on a chassis whose
            // vendor stack reasserts it is how a profile that IS holding gets torn down as drifted --
            // the Nova's whole GPU churn.
            val observedDevfreqHz = probe.sysfsIntRaw(HardwareProbe.GPU_DEVFREQ_MAX_FREQ)
            val targetHz = GpuCeilingTransport.targetHzFor(
                gpuFrequenciesKhz = liveCaps.gpuFrequenciesKhz,
                powerLevel = target,
            )?.toInt()
            // Readability IS the existence test on this side: a node this cannot read is one it
            // cannot hold Mimir to, and falling back is the honest answer.
            val governing = liveCaps.gpuCeilingPath?.takeIf { targetHz != null }
            if (governing != null && targetHz != null) {
                if (observedDevfreqHz != targetHz) {
                    failures += "gpu devfreq max_freq expected " + targetHz + " Hz got " + observedDevfreqHz
                }
            } else {
                val actual = probe.sysfsIntRaw(HardwareProbe.GPU_MAX_LEVEL)
                if (actual != target) failures += "gpu max_pwrlevel expected " + target + " got " + actual
            }
        }
        plan.fanMode?.let { target ->
            val actual = probe.fanModeRaw()?.toIntOrNull()
            if (actual != target) failures += "fan_mode expected $target got $actual"
        }
        // `plan.vendorMode` is deliberately NOT checked here. The mode is the player's to change, and
        // the supervisor treats a changed mode as a YIELD, never a fault: a windowed check that
        // demanded it would fail every CPU-target repair and fan reassert (both end in
        // `verifyAppliedPlan`) the moment the player used the vendor settings screen, and a failed
        // repair is answered with a Restore. That the write LANDED is verified once, at the write, by
        // the per-mutation [readback]; the projected mutation is still reported (`unheld=`) below.
        return PlanVerification(failures.isEmpty(), failures)
    }

    override fun verifySnapshot(snapshot: HardwareSnapshot): Boolean = verifySnapshotPlan(snapshot).ok

    override fun verifySnapshotPlan(snapshot: HardwareSnapshot): PlanVerification {
        return verifyFullWindow { verifySnapshotOnce(snapshot) }
    }

    override fun admitCpuTargetsAfterCooling(plan: TuningPlan): PlanVerification {
        val admission = probe.admitCpuTargetsAfterCooling(
            plan,
            transactionCapabilities ?: capabilities(),
        )
        return PlanVerification(
            ok = admission.failures.isEmpty(),
            failures = admission.failures,
            code = admission.code,
        )
    }

    private fun verifySnapshotOnce(snapshot: HardwareSnapshot): PlanVerification {
        val failures = mutableListOf<String>()
        snapshot.nodes.forEach { node ->
            // The WINDOWED check -- eleven samples, admitting only on three consecutive final passes
            // -- so it asks whether the restore STAYED. An axis the vendor governor takes back within
            // two seconds cannot answer that honestly, and answering it wrongly refuses Stock. See
            // [vetoesWindowedVerification]; the per-mutation readback in the restore loop is untouched,
            // because that one asks whether the write LANDED and a mismatch there is a real failure.
            if (!vetoesWindowedVerification(node.logicalId)) return@forEach
            if (!readbackNode(node)) {
                failures += "node ${node.logicalId} restore mismatch"
            }
        }
        return PlanVerification(failures.isEmpty(), failures)
    }

    /**
     * Vendor state can rebound after an apparently good first readback. Consume all
     * eleven samples and ten fixed waits, then admit only when the final three samples
     * pass consecutively; never return on an early pass.
     */
    private fun verifyFullWindow(sample: () -> PlanVerification): PlanVerification {
        // Timed for the same reason as the admission dwell: this loop deliberately never returns
        // early, so it is a fixed cost on every apply and belongs in any account of switch latency.
        // Left at its current cadence, unlike the dwell -- what it observes is still read. It exists
        // to catch a vendor rebound arriving AFTER an apparently good first readback.
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var finalConsecutivePasses = 0
        var last = PlanVerification(ok = false, failures = listOf("verification did not run"))
        repeat(VERIFY_ATTEMPTS) { attempt ->
            last = sample()
            finalConsecutivePasses = if (last.ok) finalConsecutivePasses + 1 else 0
            if (attempt < VERIFY_ATTEMPTS - 1) sleeper(VERIFY_SETTLE_MS)
        }
        runCatching {
            // **The failing branch names WHAT failed.** Measured on the Thor 2026-09-21:
            // two of seven verifications ended `finalPasses=0` -- not one of eleven samples passed
            // -- and each one rolls the whole apply back and runs it again, capture, both dwells and
            // this loop included. One switch took ~13 s instead of ~4.5 s that way. About a third of
            // transitions silently do everything twice, which dwarfs every constant on this path.
            //
            // And it could not be diagnosed, because `failApply` puts its detail in the transaction
            // record rather than logcat and every other log in this class is behind
            // `DIAGNOSTICS_COMPILED`, false in PRODUCTION, which is what the reference devices run. That is the
            // third place in this module to hit the same trap; `logCpuWindowRefusal`'s own KDoc
            // records the first two, including the discarded message that made the Nova's apply
            // refusals undiagnosable for weeks.
            //
            // Disclosure matches that precedent exactly: logical ids and hardware numbers, never a
            // filesystem path. `verifyPlanOnce` renders `gpu max_pwrlevel expected 0 got 4`,
            // `fan_mode expected 2 got 0`, `projected mutation cpu:3:scaling_max_freq mismatch` --
            // ceilings, power levels and fan modes, never private-library content. Bounded so one line
            // cannot become a wall on a plan with many axes.
            val named = if (finalConsecutivePasses >= VERIFY_FINAL_CONSECUTIVE_PASSES) {
                ""
            } else {
                last.failures.take(MAX_NAMED_VERIFY_FAILURES).joinToString("; ")
                    .let { if (it.isEmpty()) "" else " failed=$it" }
            }
            android.util.Log.i(
                "MimirVerifyWindow",
                "verify attempts=" + VERIFY_ATTEMPTS + " finalPasses=" + finalConsecutivePasses +
                    " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - startedAt) + named,
            )
        }
        return if (finalConsecutivePasses >= VERIFY_FINAL_CONSECUTIVE_PASSES) {
            PlanVerification(ok = true)
        } else if (last.ok) {
            PlanVerification(
                ok = false,
                failures = listOf(
                    "verification did not end with $VERIFY_FINAL_CONSECUTIVE_PASSES consecutive passing samples",
                ),
            )
        } else {
            last
        }
    }

    override fun readback(mutation: LogicalMutation): Boolean {
        return when (mutation.kind) {
            LogicalMutationKind.SYSFS_WRITE -> {
                val path = SysfsPath.canonicalize(mutation.canonicalPath, sysfsResolver) ?: return false
                val actual = probe.sysfsIntRaw(
                    mutation.logicalId,
                    path,
                    transactionCapabilities ?: capabilities(),
                )
                if (actual != mutation.value) return false
                val meta = posix.read(path) ?: return false
                meta.mode == mutation.restoreMode &&
                    meta.uid == mutation.restoreUid &&
                    meta.gid == mutation.restoreGid
            }
            LogicalMutationKind.SETTINGS_PUT -> {
                if (mutation.axis == TuningAxis.VENDOR_MODE) {
                    return readbackVendorMode(mutation)
                }
                val observed = probe.fanModeRaw()?.toIntOrNull()
                // A RESTORE of a captured fan is read back against what TR-08 makes it write
                // (FanRestoreTarget, policy 2026-10-13): captured SILENT or SPORT restores to Smart
                // and Smart counts as restored -- strictly, never "captured or Smart". An APPLY stays literal.
                if (mutation.capturedSnapshotAuthority) {
                    mutation.expectPresent && FanRestoreTarget.restored(mutation.value, observed)
                } else {
                    mutation.expectPresent && observed == mutation.value
                }
            }
            LogicalMutationKind.SETTINGS_DELETE -> {
                // The vendor mode is never deleted: its restore is a put of the origin or a skip.
                mutation.axis != TuningAxis.VENDOR_MODE &&
                    !mutation.expectPresent && probe.fanModeRaw() == null
            }
        }
    }

    /**
     * The vendor mode's per-mutation readback, the one place that decides whether a vendor write
     * LANDED.
     *
     * - **Apply:** the direct `Settings.System` read must equal the value written.
     * - **Restore** ([LogicalMutation.capturedSnapshotAuthority]): the write either landed (live is the
     *   origin) or was correctly skipped because the live mode is not Mimir's (the player changed
     *   it; see [VendorModeOriginPolicy.decideRestore]). The one thing that is NOT restored is the
     *   live mode still being the value Mimir applied. So: readable, and not the applied value.
     *   Demanding `live == origin` here would fail every restore after a player's change, and a
     *   failed restore is retried forever against a mode that is not ours to touch.
     *
     *   The origin check comes FIRST ([VendorModeOriginPolicy.decideRestore]'s own order): a plan
     *   that targets the mode the player is already on (Quiet at stock mode 0) has `origin ==
     *   applied`, writes nothing, and its restore is "already at origin". Asking only "live is not
     *   applied" would call that restore failed, forever, because live legitimately IS applied.
     */
    private fun readbackVendorMode(mutation: LogicalMutation): Boolean {
        val live = probe.vendorModeRaw()?.trim()?.toIntOrNull() ?: return false
        if (!mutation.capturedSnapshotAuthority) {
            return mutation.expectPresent && live == mutation.value
        }
        val context = mutation.vendorModeContext ?: return live == mutation.value
        return live == context.origin || live != context.applied
    }

    private fun readbackNode(node: TopologyNodeState): Boolean {
        return when (node.axis) {
            // Strictly "back at the origin". Unreachable from [verifySnapshotOnce] (the vendor mode is
            // player-owned, see [PLAYER_OWNED_AXIS_LOGICAL_IDS], and is filtered before this runs);
            // kept exact so any future caller asks the precise question.
            TuningAxis.VENDOR_MODE -> node.present &&
                probe.vendorModeRaw()?.trim()?.toIntOrNull() == node.originalValue
            TuningAxis.FAN -> {
                val raw = probe.fanModeRaw()
                // The captured value's RESTORE target (FanRestoreTarget): a captured loop-stopper reads
                // back as restored only at Smart, exactly what the restore wrote.
                if (!node.present) raw == null else FanRestoreTarget.restored(node.originalValue, raw?.toIntOrNull())
            }
            TuningAxis.CPU, TuningAxis.GPU -> {
                val path = SysfsPath.canonicalize(node.canonicalPath, sysfsResolver) ?: return false
                val actual = probe.sysfsIntRaw(
                    node.logicalId,
                    path,
                    transactionCapabilities ?: capabilities(),
                )
                // Compared against what the restore WROTE, not what was captured — the two differ on
                // the shared QoS node, and this is the check that decides whether a restore landed.
                if (!node.present || actual != restoreWriteValue(node)) return false
                val meta = posix.read(path) ?: return false
                meta.mode == restoreWriteMode(node) &&
                    meta.uid == node.originalUid &&
                    meta.gid == node.originalGid
            }
        }
    }

    companion object {
        // Eleven samples with ten 75ms intervals match the 750ms cpufreq admission
        // dwell, so apply/restore cannot certify a shorter transient plateau.
        /** Bound on the failing-verification line, the same reason `MAX_NAMED_MISMATCHES` bounds its. */
        private const val MAX_NAMED_VERIFY_FAILURES = 6

        private const val VERIFY_ATTEMPTS = 11
        private const val VERIFY_SETTLE_MS = 75L
        private const val VERIFY_FINAL_CONSECUTIVE_PASSES = 3
    }
}

internal class PServerHardwareCommandPort(
    private val pServer: PServerClient,
    private val reader: SystemReader,
    private val capabilities: () -> HardwareCapabilities,
    /**
     * Production reads the kernel boot identity through the existing [SystemReader].
     * The narrow seam keeps host restore-gate proof deterministic; it is not a
     * persistent-axis recovery policy.
     */
    private val liveBootIdentity: () -> String? = {
        reader.read(LIVE_BOOT_ID_PATH)
    },
    private val fanModeRaw: () -> String? = { null },
    private val sleeper: (Long) -> Unit = Thread::sleep,
    private val manualFanHandoffStore: ManualFanHandoffStore? = null,
    private val manualFanHandoffIdentity: () -> ManualFanHandoffIdentity? = { null },
    private val elapsedRealtimeMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    /**
     * The direct `Settings.System` read of `performance_mode` (the probe's [HardwareProbe.vendorModeRaw]),
     * exactly as [fanModeRaw] is for fan. Defaults to "unreadable", which makes every vendor-mode
     * mutation fail closed: a port built without it can never claim to have written the key.
     */
    private val vendorModeRaw: () -> String? = { null },
    /**
     * The origin record, shared with [HardwareProbe.vendorModeOriginStore] (the probe reads it at
     * capture; this port persists it BEFORE the first write and clears it after a confirmed restore).
     * Null means the transport is not wired: [plannedMutations] refuses a plan that names a mode.
     */
    private val vendorModeOrigin: VendorModeOriginStore? = null,
    /**
     * Defaulted seam, byte-identical production behaviour: the host filesystem resolves `/sys/...`.
     * [FileSysfsCanonicalResolver] yields `C:sys...` on a Windows host, which [SysfsPath.canonicalize]
     * refuses, so a test about WHAT this port plans (the vendor-mode apply order, for one) would
     * otherwise die in the allowlist before it planned anything.
     */
    private val sysfsResolver: SysfsCanonicalResolver = FileSysfsCanonicalResolver,
    /**
     * The direct `Settings.System` read of `is_quick_set_performance_and_fan_enable` (the probe's
     * [HardwareProbe.vendorFanCouplingRaw]): the AYN flag that makes SystemUI rewrite `fan_mode` on
     * every `performance_mode` change. Read only, never written, never through the bridge. Defaults to
     * "unreadable", which [VendorFanCouplingSettle] treats as not coupled: a port built without it
     * never waits.
     */
    private val vendorFanCouplingRaw: () -> String? = { null },
) : HardwareCommandPort {
    private var transactionCapabilities: HardwareCapabilities? = null
    private var transactionProfileId: String? = null

    /** The coupling wait after a vendor-mode write -- see [VendorFanCouplingSettle] for the evidence. */
    private val fanCoupling = VendorFanCouplingSettle(
        couplingRaw = vendorFanCouplingRaw,
        fanModeRaw = fanModeRaw,
        sleeper = sleeper,
        elapsedRealtimeMs = elapsedRealtimeMs,
    )

    /**
     * Whether the LAST vendor-mode write's settle observed a change that then held
     * ([VendorFanCouplingSettle.Outcome.SETTLED]). Reset before every vendor write and consumed by the
     * fan step that follows it: only an observed, settled rewrite lets that step skip its 3 s barrier.
     * After an unobserved outcome (unchanged, timeout) a late rewrite to SILENT/SPORT could still land
     * with compute back at Stock, and the barrier is what catches and corrects it.
     */
    @Volatile
    private var lastVendorFanCouplingSettled: Boolean = false

    /**
     * E4 : timestamp hook the
     * service cannot otherwise observe -- the "fan-first restore" write inside
     * [restoreCapturedFanMode] happens several layers below anything
     * `MimirTweakerService.observeVendorSmartDuty` can see directly (it runs deep inside
     * `transaction.restoreBound(...)` -> `TransactionCoordinator` -> [execute], not at a call site
     * the service itself makes). Set on CONFIRMED, only for a write that actually landed (never
     * the "already restored, nothing duty-owned to defend" skip branch) -- a floor-learning
     * suppression window exists to blind the floor to a Mimir-forced full-PWM instant, not to a
     * report of a no-op. [enterManualFanMode] is deliberately NOT a source of this stamp: mode 6
     * entry only ever runs while live `fan_mode` is already vendor Smart
     * (`fanModeRaw() == FAN_SMART`), which is exactly the condition
     * `MimirTweakerService.observeVendorSmartDuty` already requires before it samples at all --
     * the write and the read can never race there the way they can for a restore or a
     * forced-cooling reassert.
     */
    @Volatile
    private var lastVendorFanModeWriteElapsedRealtimeMs: Long? = null

    /** Read side of [lastVendorFanModeWriteElapsedRealtimeMs] -- see that field's own kdoc. */
    internal fun lastVendorFanModeWriteElapsedRealtimeMs(): Long? = lastVendorFanModeWriteElapsedRealtimeMs

    /**
     * Established, not
     * assumed: [transactionProfileId] is set ONLY by [plannedMutations], and
     * `TransactionCoordinator.reassertAppliedFanBound` (the fan reassert path this method exists
     * for) never calls it -- it replays the journalled
     * `TransactionJournalRecord.appliedMutationProjection` straight through [execute], never
     * through a fresh apply. So by the time a reassert reaches `enterManualFanMode`,
     * [transactionProfileId] carries whatever the MOST RECENT [plannedMutations] call set it to --
     * which is not necessarily this reassert's own contract.
     *
     * One concrete, sourced case where that bites: `MimirTweakerService.observePostTransactionDisposition`'s
     * `PostTransactionDisposition.RetainRecovery` branch. A client applies profile B while a MANUAL
     * session for profile A is active; [plannedMutations] runs for B (setting
     * [transactionProfileId] = B) and then the apply fails to become the exact active APPLIED
     * record (`PostTransactionGate.decide`'s `result.applied` false, or an identity mismatch); the
     * journal still holds A's OLDER record, so `RetainRecovery` re-adopts A
     * (`supervisor.adoptAppliedSession(..., scope = RECOVERY)`) as the active session while
     * [transactionProfileId] is left at B. A later external `fan_mode` write against that
     * RECOVERED A session would then have this reassert capture a handoff identity for
     * `profileId = A` while `enterManualFanMode` compares it against the stale
     * `expectedProfileId = B`, refusing with "Manual fan transition identity is unavailable" --
     * a session that legitimately owns MANUAL, permanently unable to re-enter it after the fact.
     *
     * This setter lets `MimirTweakerService.prepareManualFanReentryForReassert` bind the exact
     * profileId the journalled/authenticated contract [active] session already carries before the
     * reassert can reach `enterManualFanMode` -- idempotent whenever [transactionProfileId] already
     * happened to agree, and the actual fix in the RetainRecovery case above.
     */
    internal fun bindReassertProfile(profileId: String) {
        transactionProfileId = profileId
    }

    override fun plannedMutations(plan: TuningPlan, snapshot: HardwareSnapshot): List<LogicalMutation> {
        check(snapshot.cpuMinKhz.isEmpty()) {
            "New apply snapshots must not own CPU scaling_min_freq"
        }
        val live = capabilities().also { transactionCapabilities = it }
        transactionProfileId = plan.profileId
        val targetFailures = PlanTargetValidator.validate(plan, live)
        check(targetFailures.isEmpty()) { targetFailures.joinToString("; ") }
        val allowed = requireCanonicalAllowlist(live)
        val compute = mutableListOf<LogicalMutation>()
        val cooling = mutableListOf<LogicalMutation>()
        live.cpuPolicies.forEach { policy ->
            val target = plan.cpuMaxKhz[policy.id] ?: return@forEach
            val maxPath = requireCanonical(CpuCeilingTransport.pathFor(policy, live))
            requireAllowed(maxPath, allowed)
            requireInteger(target, "cpu:${policy.id}:scaling_max_freq")
            val maxStock = snapshot.nodes.first { it.logicalId == "cpu:${policy.id}:scaling_max_freq" }
            compute += LogicalMutation(
                logicalId = maxStock.logicalId,
                axis = TuningAxis.CPU,
                canonicalPath = maxPath,
                kind = LogicalMutationKind.SYSFS_WRITE,
                value = target,
                restoreMode = CpuCeilingTransport.appliedMode(
                    path = maxPath,
                    capturedMode = maxStock.originalMode,
                ),
                restoreUid = maxStock.originalUid,
                restoreGid = maxStock.originalGid,
                expectPresent = true,
            )
        }
        plan.gpuMaxPowerLevel?.let { target ->
            val minPath = requireCanonical(HardwareProbe.GPU_MIN_LEVEL)
            val maxPath = requireCanonical(HardwareProbe.GPU_MAX_LEVEL)
            requireAllowed(minPath, allowed)
            requireAllowed(maxPath, allowed)
            requireInteger(target, HardwareSnapshot.GPU_MAX_LOGICAL_ID)
            val minStock = snapshot.nodes.firstOrNull {
                it.logicalId == HardwareSnapshot.GPU_MIN_LOGICAL_ID
            }
            // Resolved but NOT required here. Under the devfreq transport the capture owns a
            // different node and this one is legitimately absent -- `.first` threw, the exception was
            // swallowed into SERVICE_ERROR, and the daemon reported UNAVAILABLE with nothing in the
            // log. Each branch below requires the node it actually writes, and nothing more.
            val maxStock = snapshot.nodes.firstOrNull {
                it.logicalId == HardwareSnapshot.GPU_MAX_LOGICAL_ID
            }
            val slowest = slowestGpuPowerLevel(live)
            check(target in 0..slowest) {
                "GPU max_pwrlevel target $target outside live range 0..$slowest"
            }
            /*
             * Qualcomm power levels are inverted: larger indices are slower. A profile-supplied
             * floor (policy 2026-08-18, Odin balanced only -- see
             * [TuningPlanner.profileSuppliedGpuMinPowerLevel]) wins first; otherwise preserve
             * the vendor's captured lower-performance bound when it already admits the
             * requested max_pwrlevel. Generic devices widen to the slowest live level when
             * required. Odin's firmware replays that bound to max, so its one slower Quiet
             * ceiling records the same admitted level explicitly. RP5 keeps its vendor floor.
             */
            minStock?.let { stock ->
                val capturedMin = stock.originalValue
                    ?: error("GPU min_pwrlevel missing captured value")
                check(capturedMin in 0..slowest) {
                    "Captured GPU min_pwrlevel $capturedMin outside live range 0..$slowest"
                }
                val profileFloor = TuningPlanner.profileSuppliedGpuMinPowerLevel(
                    deviceProfileId = live.deviceProfileId,
                    profileId = plan.profileId,
                    gpuMaxPowerLevel = target,
                    slowestLevel = slowest,
                )
                val admittedMin = when {
                    profileFloor != null -> profileFloor
                    capturedMin >= target -> capturedMin
                    live.deviceProfileId == CpuCeilingTransport.ODIN_3_PROFILE -> target
                    else -> slowest
                }
                check(admittedMin >= target) {
                    "GPU min_pwrlevel $admittedMin cannot admit max_pwrlevel $target"
                }
                requireInteger(admittedMin, HardwareSnapshot.GPU_MIN_LOGICAL_ID)
                compute += LogicalMutation(
                    logicalId = stock.logicalId,
                    axis = TuningAxis.GPU,
                    canonicalPath = minPath,
                    kind = LogicalMutationKind.SYSFS_WRITE,
                    value = admittedMin,
                    restoreMode = stock.originalMode,
                    restoreUid = stock.originalUid,
                    restoreGid = stock.originalGid,
                    expectPresent = true,
                )
            }
            // The SAME decision the capture made, from the same inputs -- see HardwareProbe. The
            // stock node this writes over was captured under whichever id this returns, so the two
            // cannot address different nodes.
            val devfreqPath = live.gpuCeilingPath
                ?.takeIf { GpuCeilingTransport.targetHzFor(live.gpuFrequenciesKhz, target) != null }
            if (devfreqPath != null) {
                val hz = GpuCeilingTransport.targetHzFor(
                    gpuFrequenciesKhz = live.gpuFrequenciesKhz,
                    powerLevel = target,
                ) ?: error("GPU devfreq transport selected without a translatable target")
                val devfreqStock = snapshot.nodes.firstOrNull {
                    it.logicalId == HardwareSnapshot.GPU_DEVFREQ_MAX_LOGICAL_ID
                } ?: error(
                    "GPU devfreq transport selected but no devfreq node was captured -- capture and " +
                        "emission disagreed about the ceiling",
                )
                // HERTZ, because that is what the node itself stores. An earlier draft wrote kHz
                // here on the reasoning that the probe normalises frequencies to kHz -- and the
                // device took it verbatim: `expected 475000 kHz got 475`. The write had landed
                // perfectly; 475000 Hz is simply 475 kHz. A value crossing into hardware wears the
                // hardware's unit, and every reader of this id now does too.
                requireInteger(hz.toInt(), HardwareSnapshot.GPU_DEVFREQ_MAX_LOGICAL_ID)
                compute += LogicalMutation(
                    logicalId = devfreqStock.logicalId,
                    axis = TuningAxis.GPU,
                    canonicalPath = requireCanonical(devfreqPath),
                    kind = LogicalMutationKind.SYSFS_WRITE,
                    value = hz.toInt(),
                    restoreMode = devfreqStock.originalMode,
                    restoreUid = devfreqStock.originalUid,
                    restoreGid = devfreqStock.originalGid,
                    expectPresent = true,
                )
            } else {
                val pwrlevelStock = maxStock ?: error(
                    "GPU pwrlevel transport selected but no max_pwrlevel node was captured -- capture " +
                        "and emission disagreed about the ceiling",
                )
                compute += LogicalMutation(
                    logicalId = pwrlevelStock.logicalId,
                    axis = TuningAxis.GPU,
                    canonicalPath = maxPath,
                    kind = LogicalMutationKind.SYSFS_WRITE,
                    value = target,
                    restoreMode = pwrlevelStock.originalMode,
                    restoreUid = pwrlevelStock.originalUid,
                    restoreGid = pwrlevelStock.originalGid,
                    expectPresent = true,
                )
            }
        }
        plan.fanMode?.let { target ->
            requireInteger(target, HardwareSnapshot.FAN_LOGICAL_ID)
            check(target in TuningPlanner.KNOWN_FAN_MODES) { "fan_mode $target is not a known Mimir mode" }
            cooling += LogicalMutation(
                logicalId = HardwareSnapshot.FAN_LOGICAL_ID,
                axis = TuningAxis.FAN,
                canonicalPath = HardwareSnapshot.FAN_SETTINGS_PATH,
                kind = LogicalMutationKind.SETTINGS_PUT,
                value = target,
                restoreMode = null,
                expectPresent = true,
            )
        }
        val vendor = mutableListOf<LogicalMutation>()
        plan.vendorMode?.let { target ->
            requireInteger(target, HardwareSnapshot.VENDOR_MODE_LOGICAL_ID)
            check(target in TuningPlanner.KNOWN_VENDOR_MODES) {
                "performance_mode $target is not a known Mimir mode"
            }
            check(vendorModeOrigin != null) {
                "Vendor mode transport is not wired: no origin store, so the mode cannot be written"
            }
            // The captured node's value is the HONEST ORIGIN (HardwareProbe.captureVendorModeNode), never
            // a live read: it is what the record persists before the write and what a restore writes back.
            val origin = snapshot.vendorMode
                ?: error("Vendor mode planned but no origin was captured")
            check(origin in TuningPlanner.KNOWN_VENDOR_MODES) {
                "Captured performance_mode $origin outside known modes"
            }
            vendor += LogicalMutation(
                logicalId = HardwareSnapshot.VENDOR_MODE_LOGICAL_ID,
                axis = TuningAxis.VENDOR_MODE,
                canonicalPath = HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH,
                kind = LogicalMutationKind.SETTINGS_PUT,
                value = target,
                restoreMode = null,
                expectPresent = true,
                vendorModeContext = VendorModeMutationContext(
                    origin = origin,
                    applied = target,
                    // Audit only (the coordinator does not hand the transaction id to this port). The
                    // plan hash is the identity of what is being applied.
                    transactionId = "plan:" + PlanHash.of(plan).take(VENDOR_MODE_AUDIT_ID_CHARS),
                ),
            )
        }
        // Apply order: vendor mode, then fan, then compute -- policy 2026-10-13, "mode first,
        // then our fan". On both AYN devices SystemUI rewrites `fan_mode` on every `performance_mode`
        // change (the coupling flag, see VendorFanCouplingSettle), so a fan written BEFORE the mode was
        // overwritten moments later: `MimirVerifyWindow failed=fan_mode expected 6 got 4` on the Thor,
        // and Balanced/Performance never held. The vendor write now waits that rewrite out
        // (applyVendorMode) and the fan runs through its normal path after it.
        //
        // What the old fan-first order protected still holds. Its reason -- "fan changes may reset
        // vendor CPU/GPU policy state, and the vendor mode moves the floors the compute caps sit
        // against" -- was about COMPUTE coming last, and both cooling-side writes still precede every
        // compute cap (the coordinator's post-cooling CPU admission runs after them, unchanged). The
        // relative order of fan and mode has no compute consequence: pservice re-applies the mode's
        // floors from the key on its own poll regardless of a fan write (Thor 18:51:37.348, `cpu_init
        // cpugpumode=0` then scaling_min_freq and min_pwrlevel writes), and the caps Mimir writes are
        // ceilings the vendor never owns. Restore mirrors this (RestoreMutationOrder): the mode and its
        // settle land before the fan's final value.
        return vendor + cooling + compute
    }

    override fun restoreMutations(record: TransactionJournalRecord): List<LogicalMutation> {
        val live = transactionCapabilities ?: capabilities()
        val order = RestoreMutationOrder.logicalIds(record)
        check(order.isNotEmpty()) { "Restore apply-order logical IDs empty" }
        // The fan step that follows the vendor mode is the FINAL one (RestoreMutationOrder): nothing
        // compute-side runs after it, so it may skip the fan-before-compute barrier when the fan is
        // already where the capture left it. A duty-owned session's release step (fan first) keeps it.
        val vendorIndex = order.indexOf(HardwareSnapshot.VENDOR_MODE_LOGICAL_ID)
        return order.mapIndexed { index, logicalId ->
            val node = record.snapshot.nodes.first { it.logicalId == logicalId }
            when (node.axis) {
                TuningAxis.FAN -> {
                    if (node.present) {
                        val captured = node.originalValue
                            ?: error("Restore missing captured fan value for ${node.logicalId}")
                        check(TuningPlanner.isCapturedVendorFanMode(captured)) {
                            "Captured fan_mode $captured outside vendor range"
                        }
                        LogicalMutation(
                            logicalId = node.logicalId,
                            axis = node.axis,
                            canonicalPath = node.canonicalPath,
                            kind = LogicalMutationKind.SETTINGS_PUT,
                            value = captured,
                            restoreMode = null,
                            expectPresent = true,
                            capturedSnapshotAuthority = true,
                            fanRestoreAfterVendorMode = vendorIndex in 0 until index,
                        )
                    } else {
                        LogicalMutation(
                            logicalId = node.logicalId,
                            axis = node.axis,
                            canonicalPath = node.canonicalPath,
                            kind = LogicalMutationKind.SETTINGS_DELETE,
                            value = null,
                            restoreMode = null,
                            expectPresent = false,
                            capturedSnapshotAuthority = true,
                            fanRestoreAfterVendorMode = vendorIndex in 0 until index,
                        )
                    }
                }
                TuningAxis.VENDOR_MODE -> {
                    check(node.present) { "Restore vendor mode node not present: ${node.logicalId}" }
                    val origin = node.originalValue
                        ?: error("Restore missing captured vendor mode for ${node.logicalId}")
                    check(origin in TuningPlanner.KNOWN_VENDOR_MODES) {
                        "Captured performance_mode $origin outside known modes"
                    }
                    // The value Mimir applied is what tells its own write from the player's at restore
                    // time. It lives in the journalled plan (a derate carries it forward unchanged).
                    val applied = record.appliedPlan?.vendorMode
                        ?: error("Restore missing applied vendor mode for ${node.logicalId}")
                    LogicalMutation(
                        logicalId = node.logicalId,
                        axis = node.axis,
                        canonicalPath = node.canonicalPath,
                        kind = LogicalMutationKind.SETTINGS_PUT,
                        value = origin,
                        restoreMode = null,
                        expectPresent = true,
                        capturedSnapshotAuthority = true,
                        vendorModeContext = VendorModeMutationContext(
                            origin = origin,
                            applied = applied,
                            transactionId = record.transactionId,
                        ),
                    )
                }
                TuningAxis.CPU, TuningAxis.GPU -> {
                    val path = requireCanonical(node.canonicalPath)
                    val allowed = requireCanonicalAllowlist(live)
                    requireAllowed(path, allowed)
                    // Not `node.originalValue`: see [restoreWriteValue]. On the shared QoS node a
                    // restore WITHDRAWS the request rather than replaying a remembered number, which is
                    // the one restore that cannot be wrong about what stock was.
                    val value = restoreWriteValue(node)
                        ?: error("Restore missing original value for ${node.logicalId}")
                    requireInteger(value, node.logicalId)
                    LogicalMutation(
                        logicalId = node.logicalId,
                        axis = node.axis,
                        canonicalPath = path,
                        kind = LogicalMutationKind.SYSFS_WRITE,
                        value = value,
                        restoreMode = restoreWriteMode(node),
                        restoreUid = node.originalUid,
                        restoreGid = node.originalGid,
                        expectPresent = true,
                    )
                }
            }
        }
    }

    override fun validateRestore(record: TransactionJournalRecord): PlanVerification {
        // A journal/snapshot captured on another boot must never reach a restore mutation.
        // Different-boot persistent-axis recovery is deliberately not authorized in this seam.
        val journalBootIdentity = record.bootIdentity.trim()
        val snapshotBootIdentity = record.snapshot.bootIdentity.trim()
        val observedBootIdentity = liveBootIdentity()?.trim().orEmpty()
        val failures = buildList {
            if (journalBootIdentity.isBlank()) add("journal bootIdentity missing")
            if (snapshotBootIdentity.isBlank()) add("snapshot bootIdentity missing")
            if (observedBootIdentity.isBlank()) add("live bootIdentity missing")
            if (
                journalBootIdentity.isNotBlank() &&
                snapshotBootIdentity.isNotBlank() &&
                journalBootIdentity != snapshotBootIdentity
            ) {
                add("journal/snapshot bootIdentity mismatch")
            }
            if (
                journalBootIdentity.isNotBlank() &&
                observedBootIdentity.isNotBlank() &&
                journalBootIdentity != observedBootIdentity
            ) {
                add("live bootIdentity mismatch")
            }
        }
        if (failures.isNotEmpty()) return PlanVerification(ok = false, failures = failures)
        return RestoreContextValidator.validate(record, transactionCapabilities ?: capabilities())
    }


    override fun execute(mutation: LogicalMutation): CheckedCommandResult {
        // The vendor mode is a `settings` key of its own, never the fan's: dispatch before anything
        // below can read a `SETTINGS_PUT` as a fan_mode write.
        if (mutation.axis == TuningAxis.VENDOR_MODE || mutation.logicalId == HardwareSnapshot.VENDOR_MODE_LOGICAL_ID) {
            return executeVendorMode(mutation)
        }
        val live = transactionCapabilities ?: capabilities()
        val allowed = requireCanonicalAllowlist(live)
        val command = when (mutation.kind) {
            LogicalMutationKind.SYSFS_WRITE -> {
                val path = requireCanonical(mutation.canonicalPath)
                requireAllowed(path, allowed)
                val value = mutation.value
                    ?: return CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "Sysfs write missing integer value")
                requireInteger(value, mutation.logicalId)
                val mode = mutation.restoreMode
                    ?: return CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "Sysfs write missing restore mode")
                if (!MODE_PATTERN.matches(mode)) {
                    return CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "Illegal mode $mode")
                }
                val uid = mutation.restoreUid
                    ?: return CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "Sysfs write missing restore UID")
                val gid = mutation.restoreGid
                    ?: return CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "Sysfs write missing restore GID")
                if (uid < 0 || gid < 0) {
                    return CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "Illegal ownership")
                }
                val writeSteps = if (
                    mutation.axis == TuningAxis.CPU && CpuCeilingTransport.isSharedQos(path)
                ) {
                    val policyId = CpuCeilingTransport.policyId(mutation.logicalId)
                        ?: return CheckedCommandResult(
                            CheckedCommandStatus.FAILED,
                            detail = "QoS CPU logical ID invalid",
                        )
                    val policy = live.cpuPolicies.firstOrNull { it.id == policyId }
                        ?: return CheckedCommandResult(
                            CheckedCommandStatus.FAILED,
                            detail = "QoS CPU policy $policyId absent",
                        )
                    if (policy.cpuIds.isEmpty()) {
                        return CheckedCommandResult(
                            CheckedCommandStatus.FAILED,
                            detail = "QoS CPU policy $policyId has no CPUs",
                        )
                    }
                    policy.cpuIds.map { cpuId ->
                        "write-cpu-$cpuId" to "echo $cpuId:$value > $path"
                    }
                } else {
                    listOf("write-value" to "echo $value > $path")
                }
                return executeSysfsSteps(
                    listOf("chmod-666" to "chmod 666 $path") +
                        writeSteps +
                        listOf(
                            "restore-owner" to "chown $uid:$gid $path",
                            "restore-mode" to "chmod $mode $path",
                        ),
                )
            }
            LogicalMutationKind.SETTINGS_PUT -> {
                val value = mutation.value
                    ?: return CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "Settings put missing integer")
                requireInteger(value, mutation.logicalId)
                if (mutation.capturedSnapshotAuthority) {
                    if (!TuningPlanner.isCapturedVendorFanMode(value)) {
                        return CheckedCommandResult(
                            CheckedCommandStatus.FAILED,
                            detail = "Captured fan mode $value outside vendor range",
                        )
                    }
                    return restoreCapturedFanMode(value, finalAfterVendorMode = mutation.fanRestoreAfterVendorMode)
                } else if (value !in TuningPlanner.KNOWN_FAN_MODES) {
                    return CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "Illegal fan mode $value")
                }
                if (value == TuningPlanner.FAN_MANUAL) return enterManualFanMode()
                // Fan-mode write safety: re-writing fan_mode to the value it already holds still makes
                // the vendor driver treat it as a fresh mode entry and slam duty to its entry value
                // (measured: period/2) for ~0.2-0.9s before the curve pulls it back -- device-proven by
                // writing fan_mode=5 with nothing else running while it already read 5. Every ordinary
                // apply (plannedMutations' unconditional `plan.fanMode?.let {...}`) and every S4.3
                // reassert (HardwareSessionSupervisor.admitFanReassert -> reassertAppliedFanBound, which
                // re-asserts the SAME already-signed contract value) funnel through this one command, so
                // a fresh read-before-write here is what actually protects every caller. A genuine drift
                // (fanModeRaw() != value) still falls through and writes normally -- this only elides the
                // no-op write, never a corrective one. FAN_MANUAL (mode 6, above) and the
                // capturedSnapshotAuthority restore (above) are deliberately untouched: mode 6 entry
                // itself resets PWM and must always run its combined write/reassert, and restore already
                // has its own current-value check in restoreCapturedFanMode.
                if (fanModeRaw()?.toIntOrNull() == value) {
                    return CheckedCommandResult(
                        CheckedCommandStatus.CONFIRMED,
                        detail = "fan_mode already $value; write skipped to avoid a redundant vendor SPORT-entry duty reset",
                    )
                }
                // E4 (spec §5): an ordinary apply's fan_mode write is Mimir-initiated too -- the
                // vendor loop restarts from whatever duty Mimir last drove, and observeVendorSmartDuty
                // keeps a per-bucket MAX, so the service must not learn a floor from the next 15 s.
                val written = pServer.executeChecked("settings put system fan_mode $value")
                if (written.status == CheckedCommandStatus.CONFIRMED) {
                    lastVendorFanModeWriteElapsedRealtimeMs = elapsedRealtimeMs()
                }
                return written
            }
            LogicalMutationKind.SETTINGS_DELETE -> {
                if (!mutation.capturedSnapshotAuthority) {
                    return CheckedCommandResult(
                        CheckedCommandStatus.FAILED,
                        detail = "SETTINGS_DELETE requires captured-snapshot authority",
                    )
                }
                "settings delete system fan_mode"
            }
        }
        return pServer.executeChecked(command)
    }

    // -- The vendor performance mode ---------------------------------------------------------------
    //
    // `settings put system performance_mode N`, one fixed string bounded to KNOWN_VENDOR_MODES. The
    // ODM `pservice` consumes the key on its own poll (about every 10 s) and moves the CPU/GPU floors;
    // those floors are the vendor's, so nothing here waits for pservice or reads a floor. The apply is
    // verified by the setting alone, through the direct Settings read.

    private fun vendorModeFailure(detail: String) =
        CheckedCommandResult(CheckedCommandStatus.FAILED, detail = detail)

    private fun liveVendorMode(): Int? = vendorModeRaw()?.trim()?.toIntOrNull()

    private fun executeVendorMode(mutation: LogicalMutation): CheckedCommandResult {
        if (
            mutation.axis != TuningAxis.VENDOR_MODE ||
            mutation.logicalId != HardwareSnapshot.VENDOR_MODE_LOGICAL_ID ||
            mutation.canonicalPath != HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH ||
            mutation.kind != LogicalMutationKind.SETTINGS_PUT
        ) {
            // Includes SETTINGS_DELETE: the mode is put back to the origin or left alone, never deleted.
            return vendorModeFailure("Vendor mode mutation identity invalid: ${mutation.kind}")
        }
        val value = mutation.value
            ?: return vendorModeFailure("Settings put missing integer")
        if (value !in TuningPlanner.KNOWN_VENDOR_MODES) {
            return vendorModeFailure("Illegal vendor performance mode $value")
        }
        val context = mutation.vendorModeContext
            ?: return vendorModeFailure("Vendor mode mutation carries no origin context")
        val origins = vendorModeOrigin
            ?: return vendorModeFailure("Vendor mode origin store is not wired")
        if (context.origin !in TuningPlanner.KNOWN_VENDOR_MODES ||
            context.applied !in TuningPlanner.KNOWN_VENDOR_MODES
        ) {
            return vendorModeFailure("Vendor mode origin context outside known modes")
        }
        return if (mutation.capturedSnapshotAuthority) {
            if (context.origin != value) {
                vendorModeFailure("Vendor mode restore value ${value} is not the captured origin ${context.origin}")
            } else {
                restoreVendorMode(context, origins)
            }
        } else {
            if (context.applied != value) {
                vendorModeFailure("Vendor mode apply value ${value} does not match its context ${context.applied}")
            } else {
                applyVendorMode(value, context, origins)
            }
        }
    }

    /**
     * Persist the origin, THEN write -- never the other way round. A crash between the two leaves a
     * record naming a mode that was never written, which the next capture retires harmlessly; the
     * opposite order would leave Mimir's mode on the device with nothing to say whose it was.
     */
    private fun applyVendorMode(
        value: Int,
        context: VendorModeMutationContext,
        origins: VendorModeOriginStore,
    ): CheckedCommandResult {
        val live = liveVendorMode()
            ?: return vendorModeFailure("performance_mode is unreadable; a key that cannot be verified is not written")
        // The snapshot's origin was captured moments ago. If the player moved the key since, that
        // origin is stale and a restore would write it over their newer choice, so this refuses
        // (device untouched, the coordinator restores nothing) rather than record a lie. Three live
        // values are coherent: the origin itself, the value being applied, and -- when an earlier
        // session's record is still held -- the mode that record says Mimir applied.
        val held = origins.load().recordOrNull
        val coherent = live == context.origin || live == value || (held != null && live == held.applied)
        if (!coherent) {
            return vendorModeFailure(
                "performance_mode is $live, not the captured origin ${context.origin}: the player changed it " +
                    "after capture; nothing written",
            )
        }
        val record = VendorModeOriginPolicy.recordAfterApply(context.origin, value, context.transactionId)
        if (record != null) {
            if (!origins.save(record)) {
                return vendorModeFailure(
                    "vendor mode origin record is not durable; performance_mode left untouched",
                )
            }
        } else if (!origins.clear()) {
            // Applying the origin itself leaves Mimir no deviation to guard; a record still on disk
            // would later name a deviation that no longer exists.
            return vendorModeFailure(
                "stale vendor mode origin record could not be cleared; performance_mode left untouched",
            )
        }
        // Redundant-write skip, the fan pattern: re-putting the value the key already holds still
        // wakes the consumer for nothing. Placed AFTER the record so a re-apply of the held mode
        // refreshes the record rather than skipping it.
        if (live == value) {
            return CheckedCommandResult(
                CheckedCommandStatus.CONFIRMED,
                detail = "performance_mode already $value; write skipped",
            )
        }
        // Read the fan BEFORE the write so the coupling settle has an honest baseline (only when the
        // flag is armed: an uncoupled device pays no read and no wait).
        lastVendorFanCouplingSettled = false
        val preFan = if (fanCoupling.armed()) fanCoupling.preFan() else null
        val written = pServer.executeChecked("settings put system performance_mode $value")
        if (written.status == CheckedCommandStatus.CONFIRMED) {
            // E4 (D4 of the vendor-mode design): a mode transition can restart the vendor's fan loop
            // from whatever duty Mimir last drove, so the floor learner must stay blind for the same
            // 15 s it is for a fan_mode write. The mode write itself never touches fan_mode -- but on
            // a coupled AYN device SystemUI does, and the fan mutation that follows this one in the
            // apply order must find that rewrite already landed, not race it.
            lastVendorFanModeWriteElapsedRealtimeMs = elapsedRealtimeMs()
            lastVendorFanCouplingSettled =
                fanCoupling.settle(fromMode = live, toMode = value, preFan = preFan).outcome ==
                VendorFanCouplingSettle.Outcome.SETTLED
        }
        return written
    }

    /**
     * The three-way rule ([VendorModeOriginPolicy.decideRestore]). The player's choice stands:
     * a live mode that is neither Mimir's nor the origin is reported `CONFIRMED` with a named
     * detail and nothing is written.
     */
    private fun restoreVendorMode(
        context: VendorModeMutationContext,
        origins: VendorModeOriginStore,
    ): CheckedCommandResult {
        // Cleared first, on every row: a "settled" left by the apply or an earlier restore must never let the
        // final fan step skip its barrier after a mode step that wrote nothing (re-check P3, 2026-10-13).
        lastVendorFanCouplingSettled = false
        val live = liveVendorMode()
        val held = origins.load().recordOrNull
        return when (VendorModeOriginPolicy.decideRestore(context.origin, context.applied, live)) {
            VendorModeRestoreAction.UNREADABLE ->
                vendorModeFailure("performance_mode is unreadable at restore; not claiming it restored")
            VendorModeRestoreAction.SKIP_PLAYER_CHANGED -> {
                // The player's choice stands, and the record is released with it
                // ([VendorModeOriginPolicy.recordAfterRestore] clears on any readback other than Mimir's
                // own value): a record kept here would let a later session mistake Mimir's old origin for
                // the player's mode (V-P1, 2026-10-13 review).
                releaseVendorModeRecord(held, live)
                CheckedCommandResult(
                    CheckedCommandStatus.CONFIRMED,
                    detail = "performance_mode is $live, neither Mimir's ${context.applied} nor the origin " +
                        "${context.origin}: the player changed it, left as is",
                )
            }
            VendorModeRestoreAction.ALREADY_AT_ORIGIN -> {
                releaseVendorModeRecord(held, live)
                CheckedCommandResult(
                    CheckedCommandStatus.CONFIRMED,
                    detail = "performance_mode already at origin ${context.origin}; write skipped",
                )
            }
            VendorModeRestoreAction.WRITE_ORIGIN -> {
                lastVendorFanCouplingSettled = false
                val preFan = if (fanCoupling.armed()) fanCoupling.preFan() else null
                val written = pServer.executeChecked("settings put system performance_mode ${context.origin}")
                if (written.status == CheckedCommandStatus.CONFIRMED) {
                    lastVendorFanModeWriteElapsedRealtimeMs = elapsedRealtimeMs()
                    // The restore order puts the fan's final write AFTER this step (RestoreMutationOrder)
                    // for exactly this reason: SystemUI's coupled rewrite must have landed before the
                    // captured fan is put back, or the Guardian's stock readback sees the coupled value
                    // (`Guardian stock unconfirmed fan1!=fan4`, Thor 2026-10-13). Same port in both
                    // processes, so the daemon and the :guardian wait identically.
                    lastVendorFanCouplingSettled =
                        fanCoupling.settle(fromMode = live ?: context.applied, toMode = context.origin, preFan = preFan)
                            .outcome == VendorFanCouplingSettle.Outcome.SETTLED
                    releaseVendorModeRecord(held, liveVendorMode())
                }
                written
            }
        }
    }

    /**
     * Clear the origin record unless the restore did not land ([VendorModeOriginPolicy.recordAfterRestore]):
     * the origin confirmed, or a player-chosen value left alone, both release it. A failed clear is not a
     * failed restore: a stale record whose `applied` no longer matches the live key is retired by the next
     * capture.
     */
    private fun releaseVendorModeRecord(held: VendorModeOriginRecord?, readback: Int?) {
        if (VendorModeOriginPolicy.recordAfterRestore(held, readback) != null) return
        if (!(vendorModeOrigin?.clear() ?: true)) {
            runCatching {
                android.util.Log.w("MimirVendorMode", "origin record could not be cleared after a confirmed restore")
            }
        }
    }

    /**
     * Each mutation boundary is independently checked and bounded by PServerClient's 198-byte
     * envelope. Do not add cleanup here: the armed transaction/journal recovery owns restore.
     */
    private fun executeSysfsSteps(steps: List<Pair<String, String>>): CheckedCommandResult {
        val total = steps.size
        steps.forEachIndexed { index, (step, command) ->
            val result = pServer.executeChecked(command)
            if (result.status != CheckedCommandStatus.CONFIRMED) {
                return result.copy(detail = "sysfs ${index + 1}/$total ($step): ${result.detail}")
            }
            if (index == steps.lastIndex) {
                return result.copy(detail = "sysfs $total/$total ($step): ${result.detail}")
            }
        }
        error("SYSFS command steps must not be empty")
    }

    /** `num_pwrlevels` as the driver reports it, or null when unreadable -- see [GpuCeilingTransport]. */
    private fun livePowerLevelCount(): Int? =
        reader.read("${HardwareProbe.GPU_ROOT}/num_pwrlevels")?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    private fun slowestGpuPowerLevel(live: HardwareCapabilities): Int {
        val count = reader.read("${HardwareProbe.GPU_ROOT}/num_pwrlevels")?.toIntOrNull()
        if (count != null && count > 0) return count - 1
        return (live.gpuFrequenciesKhz.size - 1).coerceAtLeast(0)
    }

    /**
     * Entering vendor mode 6 resets PWM near 50%. Reassert the freshly revalidated Smart duty
     * immediately in the same checked shell transaction so that reset never gets an intentional
     * dwell window. The earlier capture still binds signed profile identity, floor, topology,
     * period, and freshness; vendor Smart may legitimately move duty during the mandatory restore.
     * Settlement stays read-only: a delayed vendor reset is terminal and Guardian owns rollback.
     * PREPARED + Guardian READY are durable before this method can run.
     */
    private fun enterManualFanMode(): CheckedCommandResult {
        val live = transactionCapabilities ?: capabilities()
        val nodes = manualFanNodes(live)
            ?: return fanCommandFailure("Manual fan topology is not admitted")
        // Identity and freshness FIRST, before any write: the coupled path below spends up to 3 s on a
        // hand-to-Smart, and a handoff that is already stale must fail closed without paying for it.
        val expectedProfileId = transactionProfileId
            ?: return fanCommandFailure("Manual fan transaction profile is unavailable")
        val expectedIdentity = manualFanHandoffIdentity()
            ?.takeIf { it.profileId == expectedProfileId }
            ?: return fanCommandFailure("Manual fan transition identity is unavailable")
        val handoff = manualFanHandoffStore?.consume(expectedIdentity)
            ?: return fanCommandFailure("Manual fan handoff baseline is unavailable")
        fun handoffAgeMs(): Long = elapsedRealtimeMs() - handoff.capturedElapsedRealtimeMs
        if (handoffAgeMs() !in 0..FAN_HANDOFF_MAX_AGE_MS) {
            return fanCommandFailure("Manual fan handoff baseline expired")
        }
        val liveBeforeEntry = fanModeRaw()?.toIntOrNull()
        if (liveBeforeEntry != TuningPlanner.FAN_SMART) {
            // the vendor-mode write now runs
            // BEFORE this entry, and on a coupled AYN device SystemUI answers it by rewriting
            // `fan_mode` from its own memory -- not always to Smart (Thor 18:51:36, a Tweaker write of
            // mode 0 was followed by fan 1). The service's pre-transaction hand-to-Smart (I3 step 1)
            // therefore no longer guarantees Smart here, so the same renewal write is repeated in the
            // transaction: full PWM and `fan_mode 4` in one checked command, then the bounded settle,
            // cooling-positive by construction exactly as the pre-transaction one. A chassis that
            // reads Smart already (the Thor after a mode 1 or 2 write, every uncoupled device) takes
            // the old path untouched. Every check below still runs on the value it finds.
            //
            // **The duty budget this leans on, stated plainly.** The renewal write parks duty at the
            // full period and the entry below refuses a full-period duty by construction (fan-mode write safety
            // 2026-08-13), so this path passes only if the vendor's Smart loop pulls duty back down
            // inside the renewal's 3 s settle. Device evidence says it does, at mode entry and not on
            // a slow tick: the Thor's `FanBase: mSmartAction` wrote its curve duty 11 ms after each
            // Smart entry (18:37:40.371, 18:51:44.034; the loop's periodic ticks are 3.5-5.6 s apart),
            // and the Nova's restore to mode 4 put duty back to its curve value immediately
            // (2026-08-25, the entry KDoc below). A chassis whose loop waits for its next tick fails
            // CLOSED at the full-period refusal, named on the line logged here, and the apply falls
            // back to the Smart-target plan it already has. Budget: FAN_HANDOFF_MAX_AGE_MS (10 s)
            // from the pre-transaction baseline covers capture (~1.2 s measured 18:37:38.69-39.89),
            // the coupling settle (<= 1.5 s) and this renewal (3 s); the age is checked again after.
            val handed = handFanToVendorSmartForRenewal()
            val liveAfterHand = fanModeRaw()?.toIntOrNull()
            runCatching {
                android.util.Log.w(
                    FAN_ENTRY_TAG,
                    "MANUAL_ENTRY_SMART_REENTRY live=$liveBeforeEntry handed=${handed.status} after=$liveAfterHand " +
                        "duty=${reader.read(nodes.dutyPath)?.toIntOrNull()} period=${reader.read(nodes.periodPath)?.toIntOrNull()} " +
                        "handoffAgeMs=${handoffAgeMs()} profile=$expectedProfileId",
                )
            }
            if (handed.status != CheckedCommandStatus.CONFIRMED) {
                return fanCommandFailure(
                    "Manual fan handoff requires live vendor Smart mode and re-entering it failed: ${handed.detail}",
                )
            }
            if (liveAfterHand != TuningPlanner.FAN_SMART) {
                return fanCommandFailure("Manual fan handoff requires live vendor Smart mode")
            }
            if (handoffAgeMs() !in 0..FAN_HANDOFF_MAX_AGE_MS) {
                return fanCommandFailure("Manual fan handoff baseline expired during the Smart re-entry")
            }
        }
        val period = reader.read(nodes.periodPath)?.toIntOrNull()
            ?.takeIf { it in FanPwmBounds.PERIOD_RANGE }
            ?: return fanCommandFailure("Manual fan period is unavailable")
        val duty = reader.read(nodes.dutyPath)?.toIntOrNull()
            ?.takeIf { it in 0..period }
            ?: return fanCommandFailure("Manual fan duty is unavailable")
        // Fan-duty readback safety: a duty reading that implies 100% of period is never trusted into
        // this handoff, however it was obtained. Device evidence confirms a vendor fan_mode write
        // can itself reset PWM toward a mode-init default -- this got chained once already: a live
        // duty-node reading landed on the period value and this handoff wrote it straight through,
        // commanding full-speed fan. The intended duty this handoff reasserts must be correct BY
        // CONSTRUCTION -- i.e. it must never equal the one value (period, 100% of the intended
        // percent range) a reset artifact can plausibly produce -- so this checks the implied
        // percent (duty/period) against its ceiling directly, as an exact integer comparison with
        // no rounding, rather than trusting any single node read no matter its source. This does
        // not touch [floor] below (a genuine low/zero Smart duty is still admitted unchanged); it
        // only closes the top of the range this handoff will ever chain into a fan_mode write.
        if (duty >= period) {
            // Named in the device log too: on the coupled path this is exactly how a vendor loop that
            // did not pull duty down inside the Smart re-entry's settle shows itself (see above).
            runCatching {
                android.util.Log.w(
                    FAN_ENTRY_TAG,
                    "MANUAL_ENTRY_REFUSED reason=full_period duty=$duty period=$period " +
                        "liveBeforeEntry=$liveBeforeEntry handoffAgeMs=${handoffAgeMs()} profile=$expectedProfileId",
                )
            }
            return fanCommandFailure(
                "Manual fan handoff duty implies the full period (duty=$duty period=$period) -- " +
                    "refusing to chain an untrusted full-duty reading into a fan_mode write",
            )
        }
        if (period != handoff.period) {
            return fanCommandFailure(
                "Manual fan handoff period drifted: period=$period/${handoff.period}",
            )
        }
        val floor = minimumDutyForPercent(period, handoff.minimumDutyPercent)
        if (duty < floor) {
            return fanCommandFailure("Manual fan handoff duty is below the qualified floor")
        }
        if (fanModeRaw()?.toIntOrNull() != TuningPlanner.FAN_SMART) {
            return fanCommandFailure("Manual fan handoff lost vendor Smart mode before mutation")
        }
        // **THE THIRTEENTH MANUAL-FAN BLOCKER, 2026-08-25, and the first one that was never a
        // gate.** These two writes used to be chained -- `settings put system fan_mode 6 && echo
        // $duty > duty` -- on the assumption that Mimir's duty write, being last in the command, is
        // the last writer. On the Nova it is not: the platform performs its OWN one-shot duty write
        // when `fan_mode` becomes MANUAL, it lands AFTER the chained echo, and it sets exactly half
        // the period. The settle check 100 ms later then read Mimir's duty as drifted and the whole
        // mutation was refused and restored -- three times on the reference device, identically:
        //
        //     Manual fan entry duty drift at settle 1: duty=25000 expectedDuty=12000
        //
        // Device-measured 2026-08-25 on the Nova (period 50000, idle, 44 C, on battery), holding
        // mode 6 for ~15 s while writing duty by hand:
        //
        //     set fan_mode=6         -> duty 12000 becomes 25000   (the platform's write)
        //     ~4.5 s, no writes      -> duty STAYS 25000           (one-shot, not enforced)
        //     write 12000            -> duty 12000, holds 6 s      (our writes stick)
        //     write 20000            -> duty 20000, holds 4 s
        //     restore fan_mode=4     -> duty 12000, rpm 0
        //
        // So duty ownership after entry is real and this is purely an ordering defect. The mode
        // write and the duty write are separated, with a settle between them long enough for the
        // platform's reaction to land first: measured at 86 ms, 86 ms and 107 ms after the
        // `settings put` began, over three trials.
        //
        // **Both failure directions stay safe.** If the platform is ever slower than this settle,
        // its write lands on top of ours, the unchanged settle checks below catch it, and the
        // transaction restores -- exactly the behaviour this replaces, never a silent pass. And in
        // the window between the two writes the fan sits at the platform's value, which on this
        // chassis is HIGHER than the duty we are entering at, so an interruption there fails toward
        // cooling.
        val modeResult = pServer.executeChecked(
            "settings put system fan_mode ${TuningPlanner.FAN_MANUAL}",
        )
        if (modeResult.status != CheckedCommandStatus.CONFIRMED) return modeResult
        sleeper(FAN_ENTRY_VENDOR_REACTION_MS)
        val result = pServer.executeChecked("echo $duty>${nodes.dutyPath}")
        if (result.status != CheckedCommandStatus.CONFIRMED) return result

        repeat(FAN_ENTRY_SETTLE_CHECKS) { check ->
            sleeper(FAN_ENTRY_SETTLE_MS)
            val mode = fanModeRaw()?.toIntOrNull()
            val periodReadback = reader.read(nodes.periodPath)?.toIntOrNull()
            if (mode != TuningPlanner.FAN_MANUAL || periodReadback != period) {
                return fanCommandFailure(
                    "Manual fan entry lost authority at settle ${check + 1}: " +
                        "mode=$mode period=$periodReadback expectedPeriod=$period",
                )
            }
            val dutyReadback = reader.read(nodes.dutyPath)?.toIntOrNull()
            if (dutyReadback != duty) {
                return fanCommandFailure(
                    "Manual fan entry duty drift at settle ${check + 1}: " +
                        "duty=$dutyReadback expectedDuty=$duty",
                )
            }
        }
        return result
    }

    /**
     * TR-08: the vendor `fan_mode` [restoreCapturedFanMode] writes for a raw captured [value] -- Smart
     * for a captured loop-stopper, the literal value otherwise. The rule, its evidence and the strict
     * readback it implies live in [FanRestoreTarget] , shared with the
     * daemon's readbacks and the Guardian's restore policy so no two of them can disagree.
     */
    private fun restoreTargetFanMode(value: Int): Int = FanRestoreTarget.of(value)

    /**
     * If Mimir still directly owns duty (SPORT or MANUAL --
     * [TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES]), force full PWM immediately before returning
     * vendor fan authority: device evidence shows both modes' vendor duty loop stops contesting
     * duty during a live session. The SPORT/MANUAL-to-vendor handback transition itself is
     * UNVERIFIED / UNKNOWN -- no device measurement covers duty at that specific boundary -- so
     * this forces full PWM defensively before handback rather than risk handing back a stale low
     * duty that would leave the fan under-spinning until the vendor's next tick. Then hold a
     * bounded mode-stability window before the explicit restore order reasserts captured compute
     * Stock.
     * This also gives Odin's asynchronous vendor observer time to finish its mode replay before
     * Mimir writes the authoritative compute values.
     *
     * This defensive write is keyed on the CURRENT live mode, not on whether the restore target
     * happens to equal it. Device evidence shows a captured-stock restore can legitimately be a duty-owned mode restoring over
     * itself (SPORT over SPORT was the measured baseline) -- that is still Mimir
     * relinquishing whatever regulates duty next, so an equal-value restore gets exactly the same
     * defensive write as a changing one. A truly do-nothing restore (current already equals the
     * target AND current never had Mimir driving duty) is the only case that may skip the write.
     *
     * Mode 6 (MANUAL) is never a legal restore target here. Entering MANUAL resets PWM to ~50%
     * ([enterManualFanMode]'s own kdoc) and that reset is only safe when immediately paired with
     * the signed handoff's identity/freshness check and duty reassert in the same transaction --
     * none of which a captured-snapshot restore carries. [TuningPlanner.isCapturedVendorFanMode]'s
     * 0..7 range does not by itself exclude 6; this method is the enforcement point
     * instead, so a snapshot that captured 6 fails loudly here rather than silently re-entering
     * manual mode with nobody left to reassert duty.
     *
     * TR-08: [value] is the RAW captured value (still what the MANUAL guard above and the redirect
     * note below reason about); every other local computation below targets
     * [restoreTargetFanMode]'s result instead, so a captured mode that stops the vendor's
     * regulation loop restores the vendor default rather than ratcheting itself forward. See
     * [restoreTargetFanMode]'s own KDoc for the full evidence and scope.
     */
    private fun restoreCapturedFanMode(value: Int, finalAfterVendorMode: Boolean = false): CheckedCommandResult {
        if (value == TuningPlanner.FAN_MANUAL) {
            return fanCommandFailure(
                "Captured fan mode $value (MANUAL) cannot be restored directly: manual entry " +
                    "requires the enterManualFanMode handoff (identity, freshness, duty " +
                    "reassert); a plain restore would re-enter mode 6's ~50% PWM reset with no " +
                    "owner",
            )
        }
        val target = restoreTargetFanMode(value)
        val live = transactionCapabilities ?: capabilities()
        val current = fanModeRaw()?.toIntOrNull()
        val currentDutyOwned = current in TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES
        var result = if (current == target && !currentDutyOwned) {
            // The fan step that follows the vendor mode in the restore order (RestoreMutationOrder) is
            // the LAST mutation: no compute axis comes after it. It may skip the barrier below ONLY when
            // the mode write's settle OBSERVED SystemUI's rewrite land and hold ([lastVendorFanCouplingSettled]):
            // then nothing is still in flight. After an unobserved outcome (unchanged, timeout) a late
            // rewrite to SILENT/SPORT could still arrive with compute at Stock, and the barrier is the one
            // thing that would catch and correct it, so it stays. A duty-owned release step always keeps it.
            if (finalAfterVendorMode && lastVendorFanCouplingSettled) {
                lastVendorFanCouplingSettled = false
                return CheckedCommandResult(
                    status = CheckedCommandStatus.CONFIRMED,
                    detail = "Captured fan mode already restored after the vendor mode's settled rewrite; nothing follows",
                )
            }
            CheckedCommandResult(
                status = CheckedCommandStatus.CONFIRMED,
                detail = "Captured fan mode already restored",
            )
        } else if (!currentDutyOwned) {
            pServer.executeChecked("settings put system fan_mode $target")
        } else {
            val nodes = manualFanNodes(live)
                ?: return fanCommandFailure("Manual fan restore topology is unavailable")
            val period = reader.read(nodes.periodPath)?.toIntOrNull()
                ?.takeIf { it in FanPwmBounds.PERIOD_RANGE }
                ?: return fanCommandFailure("Manual fan restore period is unavailable")
            pServer.executeChecked(
                "echo $period > ${nodes.dutyPath} && settings put system fan_mode $target",
            )
        }
        // E4 : a real write just
        // landed -- not the "already restored, nothing duty-owned to defend" skip branch above,
        // which issues no command at all and has nothing to suppress floor-learning against.
        if (result.status == CheckedCommandStatus.CONFIRMED && (current != target || currentDutyOwned)) {
            lastVendorFanModeWriteElapsedRealtimeMs = elapsedRealtimeMs()
        }
        if (result.status != CheckedCommandStatus.CONFIRMED) return result
        if (target != value) {
            // Release gate 7 ("every silent decline names itself") applies in spirit to a silent
            // substitution too: name the redirect in the confirmed detail rather than let a
            // captured-5-becomes-4 write look, from the outside, like an ordinary restore.
            result = result.copy(
                detail = "${result.detail}; captured fan mode $value stops the vendor " +
                    "regulation loop, restored vendor default $target instead (TR-08)",
            )
        }
        // 2026-08-23. This gate was `live.deviceProfileId != CpuCeilingTransport.ODIN_3_PROFILE`,
        // so EVERY other device wrote the restore and returned without ever checking the mode held.
        // It became load-bearing in this commit: manual fan is family-wide now, and a Nova session
        // that held mode 6 would otherwise relinquish it unverified. Widened to the same fan
        // capability the write itself is gated on -- a chassis with no fan axis still returns early
        // because there is genuinely nothing to settle; every fan-bearing device now gets the
        // bounded verification only the Odin had.
        //
        // **This loop is NOT only a verification -- it is also the fan-before-compute settlement
        // barrier**, and that is why it must keep running even on the `alreadyRestored` branch that
        // issues no command at all. Restore order is fan first, then the compute axes; the 6 x 500ms
        // spent here is what gives the fan time to actually take effect before CPU/GPU ceilings go
        // back to Stock and heat rises again. `AndroidFanDutyPortTest`'s
        // "already restored Odin fan mode still holds the compute settlement barrier" pins exactly
        // that, `pServer.commands.isEmpty()` and all six sleeps together. An earlier draft of this
        // commit narrowed the loop to "verify what you actually wrote", which reads as obviously
        // correct and silently deletes the barrier; that pin is what caught it. Do not re-narrow
        // this without moving the barrier somewhere explicit first.
        //
        // UNMEASURED on non-Odin hardware: the 3s cost at every session boundary and the FAILED
        // disposition below are both new for the Nova/RP5. The failure is a report, not a hardware
        // change -- the write already landed CONFIRMED above; this only refuses to call it restored.
        if (TuningAxis.FAN !in live.supportedAxes || !live.fanNodeAvailable) return result

        // Settlement drift is non-fatal after authority returns to the vendor. It used to `return fanCommandFailure(...)` on any drift. `attemptFullRestore`
        // returns on the first non-CONFIRMED restore mutation, and `RestoreMutationOrder` puts the
        // fan FIRST for a duty-owned session -- so a vendor rewrite inside this window aborted the
        // restore before the CPU and GPU mutations ran at all.
        //
        // Two reasons it is wrong to fail here, and the obvious one is NOT among them (Mimir
        // ceilings are underclocks, so aborting actually leaves compute cooler than Stock -- the
        // hazard is elsewhere):
        //  - **Category.** After this write, fan authority belongs to the vendor again. A vendor
        //    rewrite inside the settle window is the new owner exercising ownership, not a failed
        //    restore. Reporting it as one blames Mimir for the handback succeeding.
        //  - **Convergence.** Failure means RESTORE_FAILED, which the supervisor answers with a
        //    bounded retry. Against a device that persistently rewrites, that is a loop -- the same
        //    churn class observed on Nova.
        //
        // What non-fatal DOES open, and this closes it in the same breath: if the vendor lands on a
        // mode that STOPS its own regulation loop (SILENT/SPORT), the restore would complete to
        // Stock clocks with nothing regulating the fan -- the measured 89.9C state -- and
        // `forceCoolingSmartReassert` cannot rescue it, because that method refuses unless the mode
        // is already Smart. So a loop-stopper gets one bounded corrective write back to the vendor
        // default, in TR-08's own spirit: never relinquish the fan leaving a loop-stopper behind.
        var drifted: Int? = null
        repeat(FAN_RESTORE_SETTLE_CHECKS) {
            sleeper(FAN_RESTORE_SETTLE_MS)
            val observed = fanModeRaw()?.toIntOrNull()
            if (observed != target && drifted == null) drifted = observed
        }
        val settledMode = drifted
        if (settledMode == null) {
            return result.copy(
                detail = "${result.detail}; vendor fan mode stable through bounded restore settlement",
            )
        }
        var corrected = false
        if (settledMode in FAN_MODES_STOPPING_VENDOR_REGULATION_LOOP) {
            corrected = pServer.executeChecked(
                "settings put system fan_mode ${TuningPlanner.FAN_SMART}",
            ).status == CheckedCommandStatus.CONFIRMED
            // E4: the loop-stopper correction is its own Mimir-initiated fan_mode write.
            if (corrected) lastVendorFanModeWriteElapsedRealtimeMs = elapsedRealtimeMs()
        }
        android.util.Log.w(
            FAN_RESTORE_TAG,
            "restore settle drift: observed=$settledMode expected=$target " +
                "loopStopper=${settledMode in FAN_MODES_STOPPING_VENDOR_REGULATION_LOOP} " +
                "corrected=$corrected -- restore CONTINUES so the compute axes still return to Stock",
        )
        return result.copy(
            detail = "${result.detail}; vendor reclaimed fan mode $settledMode during restore " +
                "settlement (expected $target)" +
                if (corrected) "; corrected to vendor Smart" else "",
        )
    }

    /**
     * unconditional sibling of [restoreCapturedFanMode] for
     * [PostControlThermalWatch]'s session-independent recovery only -- see that class's own kdoc
     * for the full mechanism this closes. [restoreCapturedFanMode]'s `current == value` skip is
     * CORRECT for its own caller (an ordinary restore has nothing to defend against when nothing
     * changed) but is exactly wrong here: this method exists BECAUSE the device is already
     * sitting at vendor Smart with an unresponsive fan, so re-issuing the write anyway is the
     * whole point. Reuses the EXACT same command shape as [restoreCapturedFanMode]'s duty-owned
     * branch -- force full PWM in the same checked shell transaction as the `fan_mode` write --
     * because device evidence already established that a vendor `fan_mode` write can itself reset
     * PWM toward a mode-init default, and that writing `fan_mode` even to a value it already
     * holds is a live, observable event on this hardware. Never writes bare duty while leaving
     * `fan_mode` untouched -- see [TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES]'s kdoc for why the
     * vendor's own duty loop only fights a direct duty write while `fan_mode == FAN_SMART`, which
     * is exactly the state this method always targets.
     *
     * Fresh-reads live `fan_mode` immediately before writing and refuses if it is no longer
     * vendor Smart: something else -- most plausibly a fresh Mimir session that has already
     * reclaimed the fan -- may have taken over in the interim, and this method must never stomp a
     * legitimate SPORT/MANUAL entry back to Smart. This is this method's OWN authority boundary;
     * callers additionally re-check `HardwareSessionSupervisor.activeSession() == null`
     * immediately before invoking it (see [MimirTweakerService]'s wiring) as defense in depth, not
     * as a substitute for this check.
     *
     * Whether this actually revives a stalled vendor regulation loop is UNPROVEN -- device evidence only confirms the write forces duty to full in the same
     * transaction, which is unconditionally cooling-positive regardless of whether it "unsticks"
     * anything else. A device capture is still needed to prove loop recovery.
     */
    internal fun forceCoolingSmartReassert(): CheckedCommandResult {
        val live = transactionCapabilities ?: capabilities()
        val nodes = manualFanNodes(live)
            ?: return fanCommandFailure("Force-cooling reassert topology is unavailable")
        if (fanModeRaw()?.toIntOrNull() != TuningPlanner.FAN_SMART) {
            return fanCommandFailure(
                "Force-cooling reassert refused: live fan mode is no longer vendor Smart -- " +
                    "something else (e.g. a fresh session) may already own the fan",
            )
        }
        val period = reader.read(nodes.periodPath)?.toIntOrNull()
            ?.takeIf { it in FanPwmBounds.PERIOD_RANGE }
            ?: return fanCommandFailure("Force-cooling reassert period is unavailable")
        val result = pServer.executeChecked(
            "echo $period > ${nodes.dutyPath} && settings put system fan_mode ${TuningPlanner.FAN_SMART}",
        )
        if (result.status != CheckedCommandStatus.CONFIRMED) return result
        // 2026-08-23: the `live.deviceProfileId != ODIN_3_PROFILE` early return that stood here is
        // deleted outright rather than replaced. This method ALWAYS writes (it has no no-op branch
        // -- that is its entire reason for existing beside restoreCapturedFanMode), and
        // manualFanNodes(live) above already returned non-null, which cannot happen unless the fan
        // axis is admitted and the nodes are live. So the capability test its sibling still needs
        // would be unconditionally true here, and a device-id test was never the question.
        repeat(FAN_RESTORE_SETTLE_CHECKS) { check ->
            sleeper(FAN_RESTORE_SETTLE_MS)
            val observed = fanModeRaw()?.toIntOrNull()
            if (observed != TuningPlanner.FAN_SMART) {
                return fanCommandFailure(
                    "Force-cooling reassert lost authority at settle ${check + 1}: mode=$observed",
                )
            }
        }
        return result.copy(
            detail = "${result.detail}; force-cooling reassert stable through bounded settlement",
        )
    }

    /**
     * Fan-control acquisition/renewal from any hardware state:
     * from ANY hardware state, put the fan at vendor Smart (mode 4) with full PWM in the SAME
     * checked write. Every renewal (P2/P3/P4 of that spec) passes through this before
     * `enterManualFanMode`, which still refuses unless live `fan_mode == 4`: whenever the hardware
     * has drifted away from 4 -- an external vendor write, a fresh boot, a recorded neither-owner window -- something has to move
     * it back to 4 before a handoff can even be attempted.
     *
     * [forceCoolingSmartReassert] cannot be reused for this: it exists to REASSERT an
     * already-Smart mode and deliberately REFUSES when live `fan_mode != FAN_SMART` (see its own
     * kdoc -- "something else... may already own the fan"), which is precisely the state this
     * method must act on instead of decline. This is the identical checked write (duty forced full
     * over the same period, `fan_mode` set to [TuningPlanner.FAN_SMART], the same bounded settle
     * loop below) minus that one guard -- cooling-positive by construction, because every path
     * through it ends with the vendor's own regulation loop driving full PWM, never a parked fan.
     * A failed renewal by construction therefore never leaves the fan any worse than it already
     * was: either the write never lands (vendor keeps whatever it was doing) or it lands and the
     * vendor loop takes over at full duty.
     *
     * The only caller is Mimir's own acquisition/renewal path
     * ([MimirTweakerService.transitionFanControlModeOnWorker]'s entry branch, and the P3/P4
     * renewal machinery those pieces build on); this must never be reachable from a public
     * request, unlike the ordinary [enterManualFanMode] handoff it precedes.
     */
    internal fun handFanToVendorSmartForRenewal(): CheckedCommandResult {
        val live = transactionCapabilities ?: capabilities()
        val nodes = manualFanNodes(live)
            ?: return fanCommandFailure("Hand-to-vendor-Smart renewal topology is unavailable")
        val period = reader.read(nodes.periodPath)?.toIntOrNull()
            ?.takeIf { it in FanPwmBounds.PERIOD_RANGE }
            ?: return fanCommandFailure("Hand-to-vendor-Smart renewal period is unavailable")
        val result = pServer.executeChecked(
            "echo $period > ${nodes.dutyPath} && settings put system fan_mode ${TuningPlanner.FAN_SMART}",
        )
        if (result.status != CheckedCommandStatus.CONFIRMED) return result
        repeat(FAN_RESTORE_SETTLE_CHECKS) { check ->
            sleeper(FAN_RESTORE_SETTLE_MS)
            val observed = fanModeRaw()?.toIntOrNull()
            if (observed != TuningPlanner.FAN_SMART) {
                return fanCommandFailure(
                    "Hand-to-vendor-Smart renewal lost authority at settle ${check + 1}: mode=$observed",
                )
            }
        }
        return result.copy(
            detail = "${result.detail}; hand-to-vendor-Smart renewal stable through bounded settlement",
        )
    }

    /**
     * take the fan as Mimir MANUAL at the
     * full period, from vendor Smart only. The write shape is the one [enterManualFanMode] already
     * owns and the Nova measured (2026-08-25, the thirteenth blocker above): `fan_mode 6` first,
     * [FAN_ENTRY_VENDOR_REACTION_MS] for the platform's own one-shot half-period duty write to
     * land, then the duty -- here the full period -- and the bounded settle, which demands mode 6
     * AND duty at the period on every check. The P0 2026-08-13 refusal in [enterManualFanMode]
     * (never chain a duty READ that implies the full period into a mode write) is about trusting
     * an untrusted reading; this writes an intended full computed from a range-checked period, on
     * exactly the footing [forceCoolingSmartReassert] and [handFanToVendorSmartForRenewal] already
     * stand on. Between the two writes the fan sits at the platform's half period, above the
     * vendor curve this is entered from on a hot device, so an interruption there fails toward
     * cooling. Any miss after the mode write hands the fan back to Smart at full PWM
     * ([handFanToVendorSmartForRenewal]) before reporting the failure: this method never leaves
     * MANUAL behind on its own failure. The caller ([PostControlHoldController]) reads the live
     * mode afterwards and keeps its marker whenever that hand-back did not confirm either.
     */
    internal fun enterPostControlHold(): CheckedCommandResult {
        val live = transactionCapabilities ?: capabilities()
        val nodes = manualFanNodes(live)
            ?: return fanCommandFailure("Post-control hold topology is unavailable")
        val before = fanModeRaw()?.toIntOrNull()
        if (before != TuningPlanner.FAN_SMART) {
            return fanCommandFailure("Post-control hold refused: live fan mode is $before, not vendor Smart")
        }
        val period = reader.read(nodes.periodPath)?.toIntOrNull()
            ?.takeIf { it in FanPwmBounds.PERIOD_RANGE }
            ?: return fanCommandFailure("Post-control hold period is unavailable")
        val modeResult = pServer.executeChecked("settings put system fan_mode ${TuningPlanner.FAN_MANUAL}")
        if (modeResult.status != CheckedCommandStatus.CONFIRMED) return modeResult
        lastVendorFanModeWriteElapsedRealtimeMs = elapsedRealtimeMs()
        sleeper(FAN_ENTRY_VENDOR_REACTION_MS)
        val dutyResult = pServer.executeChecked("echo $period>${nodes.dutyPath}")
        if (dutyResult.status != CheckedCommandStatus.CONFIRMED) {
            return postControlHoldHandBack("Post-control hold duty write failed: ${dutyResult.detail}")
        }
        repeat(FAN_RESTORE_SETTLE_CHECKS) { check ->
            sleeper(FAN_RESTORE_SETTLE_MS)
            val mode = fanModeRaw()?.toIntOrNull()
            val duty = reader.read(nodes.dutyPath)?.toIntOrNull()
            if (mode != TuningPlanner.FAN_MANUAL || duty != period) {
                return postControlHoldHandBack(
                    "Post-control hold lost at settle ${check + 1}: mode=$mode duty=$duty period=$period",
                )
            }
        }
        return dutyResult.copy(
            detail = "${dutyResult.detail}; post-control hold at full period stable through bounded settlement",
        )
    }

    /** The fan state the hold acts on, read fresh from this port's own nodes; null where a read failed. */
    internal fun postControlHoldLive(): PostControlHoldLive {
        val nodes = manualFanNodes(transactionCapabilities ?: capabilities())
        return PostControlHoldLive(
            mode = fanModeRaw()?.toIntOrNull(),
            duty = nodes?.let { reader.read(it.dutyPath)?.toIntOrNull() },
            period = nodes?.let { reader.read(it.periodPath)?.toIntOrNull() },
        )
    }

    private fun postControlHoldHandBack(detail: String): CheckedCommandResult {
        val handed = handFanToVendorSmartForRenewal()
        return fanCommandFailure("$detail; handed back to Smart: ${handed.status} ${handed.detail}")
    }

    /**
     * The held duty drifted below the period while `fan_mode` still reads MANUAL: re-write the
     * full period under the mode this port already owns. Refuses unless the live mode is MANUAL --
     * a duty write under any other mode is either the vendor's to fight or someone else's fan.
     */
    internal fun correctPostControlHold(): CheckedCommandResult {
        val live = transactionCapabilities ?: capabilities()
        val nodes = manualFanNodes(live)
            ?: return fanCommandFailure("Post-control hold topology is unavailable")
        val mode = fanModeRaw()?.toIntOrNull()
        if (mode != TuningPlanner.FAN_MANUAL) {
            return fanCommandFailure("Post-control hold correction refused: live fan mode is $mode, not MANUAL")
        }
        val period = reader.read(nodes.periodPath)?.toIntOrNull()
            ?.takeIf { it in FanPwmBounds.PERIOD_RANGE }
            ?: return fanCommandFailure("Post-control hold period is unavailable")
        val result = pServer.executeChecked("echo $period>${nodes.dutyPath}")
        if (result.status != CheckedCommandStatus.CONFIRMED) return result
        repeat(FAN_RESTORE_SETTLE_CHECKS) { check ->
            sleeper(FAN_RESTORE_SETTLE_MS)
            val observedMode = fanModeRaw()?.toIntOrNull()
            val duty = reader.read(nodes.dutyPath)?.toIntOrNull()
            if (observedMode != TuningPlanner.FAN_MANUAL || duty != period) {
                return fanCommandFailure(
                    "Post-control hold correction lost at settle ${check + 1}: mode=$observedMode duty=$duty period=$period",
                )
            }
        }
        return result.copy(detail = "${result.detail}; post-control hold corrected to the full period")
    }

    /**
     * Hand a held fan back to vendor Smart: the same full-PWM + `fan_mode 4` checked write and
     * settle as [handFanToVendorSmartForRenewal] (a captured restore's duty-owned release shape),
     * guarded so it only ever acts on a fan this port holds -- live `fan_mode` must read MANUAL.
     * Anything else is not ours to move, and the caller drops its hold without a write.
     */
    internal fun releasePostControlHold(): CheckedCommandResult {
        val mode = fanModeRaw()?.toIntOrNull()
        if (mode != TuningPlanner.FAN_MANUAL) {
            return fanCommandFailure("Post-control hold release refused: live fan mode is $mode, not MANUAL")
        }
        val handed = handFanToVendorSmartForRenewal()
        if (handed.status == CheckedCommandStatus.CONFIRMED) {
            lastVendorFanModeWriteElapsedRealtimeMs = elapsedRealtimeMs()
        }
        return handed
    }

    /**
     * **Widened off the exact Odin device id, 2026-08-23** under the family-level capability policy. Named `exact*` for the fixed-allowlist topology it
     * resolves, not for a device: `HandheldDeviceProfiles.resolve` returns `fanNodes` only for an
     * RP/AYN id whose `gpio5_pwm2` duty/period/speed nodes are live on THIS unit, so a chassis
     * without the PWM still gets `null` here -- the device-id conjunct removed below was redundant
     * with the two capability conjuncts kept beside it, exactly as it was in
     * `PServerFanDutyMutationPort.write`.
     */
    private fun manualFanNodes(live: HardwareCapabilities): FanNodes? {
        if (TuningAxis.FAN !in live.supportedAxes || !live.fanNodeAvailable) return null
        return HandheldDeviceProfiles.resolve(
            manufacturer = live.manufacturer,
            model = live.model,
            socModel = live.socModel,
            reader = reader,
        ).fanNodes?.takeIf { nodes ->
            nodes.dutyPath == HardwareProbe.FAN_DUTY &&
                nodes.periodPath == HardwareProbe.FAN_PERIOD &&
                nodes.speedPath == HardwareProbe.FAN_SPEED
        }
    }

    private fun fanCommandFailure(detail: String) = CheckedCommandResult(
        status = CheckedCommandStatus.FAILED,
        detail = detail,
    )

    private fun rawAllowedPaths(live: HardwareCapabilities): Set<String> = buildSet {
        live.cpuPolicies.forEach { policy ->
            add("${policy.path}/scaling_max_freq")
            add("${policy.path}/scaling_min_freq")
        }
        live.cpuCeilingPath?.let(::add)
        add(HardwareProbe.GPU_MIN_LEVEL)
        add(HardwareProbe.GPU_MAX_LEVEL)
        // The GPU ceiling transport, exactly as `cpuCeilingPath` is added above. This list bounds
        // every node the daemon may write, so a transport missing from it is refused at execute.
        live.gpuCeilingPath?.let(::add)
    }

    private fun requireCanonicalAllowlist(live: HardwareCapabilities): Set<String> {
        return SysfsPath.canonicalizeAllowlist(rawAllowedPaths(live), sysfsResolver)
            ?: error("Capability allowlist contains noncanonical paths")
    }

    private fun requireCanonical(path: String): String =
        SysfsPath.canonicalize(path, sysfsResolver) ?: error("Path not canonical: $path")

    private fun requireAllowed(path: String, allowed: Set<String>) {
        check(path in allowed) { "Path not in validated capability allowlist: $path" }
    }

    private fun requireInteger(value: Int, logicalId: String) {
        check(value >= 0) { "Non-allowlisted non-integer-or-negative value for $logicalId: $value" }
    }

    companion object {
        private val MODE_PATTERN = Regex("[0-7]{3,4}")
        private const val LIVE_BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id"
        private const val FAN_ENTRY_SETTLE_CHECKS = 3
        private const val FAN_ENTRY_SETTLE_MS = 100L

        /**
         * How long to let the platform's own one-shot duty write land after `fan_mode` becomes
         * MANUAL, before Mimir asserts the duty it is entering at.
         *
         * Device-measured on the Nova 2026-08-25: the platform's write appeared 86 ms, 86 ms and
         * 107 ms after the `settings put` began, over three consecutive trials. 300 ms is ~3x the
         * worst of those. It is NOT a guess dressed as a constant, and it is not load-bearing for
         * safety either -- if a chassis is ever slower, the entry settle checks refuse and restore
         * exactly as they did before this ordering fix existed.
         *
         * A chassis that performs no such write (the Odin has not been observed doing it) simply
         * pays this once per player-initiated switch, which is not a path anything measures.
         */
        private const val FAN_ENTRY_VENDOR_REACTION_MS = 300L
        private const val FAN_RESTORE_SETTLE_CHECKS = 6
        private const val FAN_RESTORE_SETTLE_MS = 500L
        private const val FAN_HANDOFF_MAX_AGE_MS = 10_000L

        /** Hex chars of the plan hash used as the audit-only transaction id on a vendor-mode origin record. */
        private const val VENDOR_MODE_AUDIT_ID_CHARS = 16

        /** See the restore-settlement block; PRODUCTION-visible, like MimirFanControl. */
        private const val FAN_RESTORE_TAG = "MimirFanRestore"

        /** The manual entry's coupled path; PRODUCTION-visible so a device log can say why an entry fell back. */
        private const val FAN_ENTRY_TAG = "MimirFanEntry"

        /** TR-08's loop-stopper set, one definition: [FanRestoreTarget.LOOP_STOPPING_MODES]. */
        private val FAN_MODES_STOPPING_VENDOR_REGULATION_LOOP: Set<Int>
            get() = FanRestoreTarget.LOOP_STOPPING_MODES
    }
}

/**
 * Parent-directory durability after AtomicFile rename. Production uses [OsDirectoryDurability];
 * host/Robolectric tests may inject [NoopDirectoryDurability] while still asserting fail-closed
 * behavior via a throwing fake.
 */
internal fun interface DirectoryDurability {
    fun sync(directory: File)
}

internal object OsDirectoryDurability : DirectoryDurability {
    override fun sync(directory: File) {
        val fd: FileDescriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    }
}

internal object NoopDirectoryDurability : DirectoryDurability {
    override fun sync(directory: File) = Unit
}

/**
 * Device-protected AtomicFile journal. Corrupt bytes are a persistent blocking fault,
 * never treated as empty. Generation is monotonic across cleared sessions and fail-closed
 * on corrupt/unreadable/empty/nonpositive/overflow generation state.
 *
 * Construction never falls back from device-protected to credential-encrypted storage.
 * After every successful [AtomicFile.finishWrite], the containing directory is fsynced
 * before success is reported — an unproven directory sync is a persist failure.
 */
internal class AtomicFileTransactionJournal private constructor(
    private val filesDir: File,
    fileName: String,
    generationFileName: String,
    private val directoryDurability: DirectoryDurability,
    private val authentication: TransactionJournalAuthentication?,
) : TransactionJournal, MgdeAdmissionConsumePort {
    private val atomic = AtomicFile(File(filesDir, fileName))
    private val generationAtomic = AtomicFile(File(filesDir, generationFileName))

    constructor(
        context: Context,
        fileName: String = JOURNAL_FILE,
        generationFileName: String = GENERATION_FILE,
        directoryDurability: DirectoryDurability = OsDirectoryDurability,
        authentication: TransactionJournalAuthentication? = null,
    ) : this(
        filesDir = deviceProtectedFilesDir(context),
        fileName = fileName,
        generationFileName = generationFileName,
        directoryDurability = directoryDurability,
        authentication = authentication,
    )

    /** Test/host seam: operate over an explicit directory without Android DP context lookup. */
    constructor(
        filesDir: File,
        fileName: String = JOURNAL_FILE,
        generationFileName: String = GENERATION_FILE,
        @Suppress("UNUSED_PARAMETER") testSeam: Boolean,
        directoryDurability: DirectoryDurability = OsDirectoryDurability,
        authentication: TransactionJournalAuthentication? = null,
    ) : this(filesDir, fileName, generationFileName, directoryDurability, authentication)

    @Synchronized
    override fun load(): JournalLoadResult = synchronized(PROCESS_JOURNAL_LOCK) {
        loadLocked()
    }

    private fun loadLocked(): JournalLoadResult {
        if (!atomic.baseFile.exists()) return JournalLoadResult.Empty
        val bytes = runCatching { atomic.readFully() }.getOrElse {
            return JournalLoadResult.Corrupt("Unreadable journal: ${it.message}")
        }
        if (bytes.isEmpty()) return JournalLoadResult.Corrupt("Empty journal bytes")
        authentication?.let { authenticated ->
            return when (val decoded = authenticated.verifyAndDecode(bytes)) {
                is JournalLoadResult.Loaded -> validateActiveGeneration(decoded)
                else -> decoded
            }
        }
        val raw = runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrElse {
            return JournalLoadResult.Corrupt("Journal is not UTF-8")
        }
        return when (val decoded = decodeJournal(raw)) {
            is JournalLoadResult.Loaded -> validateActiveGeneration(decoded)
            else -> decoded
        }
    }

    private fun validateActiveGeneration(loaded: JournalLoadResult.Loaded): JournalLoadResult {
        if (!generationAtomic.baseFile.exists()) {
            return JournalLoadResult.Corrupt("Active journal generation file missing")
        }
        val bytes = runCatching { generationAtomic.readFully() }.getOrElse {
            return JournalLoadResult.Corrupt("Unreadable generation: ${it.message}")
        }
        if (bytes.isEmpty()) {
            return JournalLoadResult.Corrupt("Empty generation bytes")
        }
        val text = runCatching { String(bytes, StandardCharsets.UTF_8).trim() }.getOrElse {
            return JournalLoadResult.Corrupt("Generation is not UTF-8")
        }
        if (text.isEmpty()) {
            return JournalLoadResult.Corrupt("Empty generation text")
        }
        val value = text.toLongOrNull()
            ?: return JournalLoadResult.Corrupt("Corrupt generation text")
        if (value != loaded.record.generation) {
            return JournalLoadResult.Corrupt(
                "Generation mismatch: file=$value record=${loaded.record.generation}",
            )
        }
        return loaded
    }

    @Synchronized
    override fun nextGeneration(): GenerationResult = synchronized(PROCESS_JOURNAL_LOCK) {
        nextGenerationLocked()
    }

    private fun nextGenerationLocked(): GenerationResult {
        val current = if (!generationAtomic.baseFile.exists()) {
            0L
        } else {
            val bytes = runCatching { generationAtomic.readFully() }.getOrElse {
                return GenerationResult.Failed("Unreadable generation: ${it.message}")
            }
            if (bytes.isEmpty()) {
                return GenerationResult.Failed("Empty generation bytes")
            }
            val text = runCatching { String(bytes, StandardCharsets.UTF_8).trim() }.getOrElse {
                return GenerationResult.Failed("Generation is not UTF-8")
            }
            if (text.isEmpty()) {
                return GenerationResult.Failed("Empty generation text")
            }
            val value = text.toLongOrNull()
                ?: return GenerationResult.Failed("Corrupt generation text")
            if (value <= 0L) {
                return GenerationResult.Failed("Non-positive generation")
            }
            if (value == Long.MAX_VALUE) {
                return GenerationResult.Failed("Generation overflow")
            }
            value
        }
        val next = current + 1L
        val payload = next.toString().toByteArray(StandardCharsets.UTF_8)
        var stream: FileOutputStream? = null
        return try {
            stream = generationAtomic.startWrite()
            stream.write(payload)
            stream.flush()
            stream.fd.sync()
            generationAtomic.finishWrite(stream)
            stream = null
            directoryDurability.sync(filesDir)
            GenerationResult.Ok(next)
        } catch (error: Throwable) {
            if (stream != null) {
                runCatching { generationAtomic.failWrite(stream) }
            }
            GenerationResult.Failed("Failed to persist generation: ${error.message}")
        }
    }

    @Synchronized
    override fun persist(record: TransactionJournalRecord): Boolean =
        synchronized(PROCESS_JOURNAL_LOCK) {
            persistLocked(record)
        }

    /**
     * WAVE2 thermal-derate rung amendment. Separate entry point from [persist] so the frozen
     * projection guard there is never loosened: this lane is the only one that may change the
     * durable applied values, and only strictly downward.
     */
    @Synchronized
    override fun amendApplied(record: TransactionJournalRecord): Boolean =
        synchronized(PROCESS_JOURNAL_LOCK) {
            amendAppliedLocked(record)
        }

    private fun amendAppliedLocked(record: TransactionJournalRecord): Boolean {
        val effectiveRecord = recordForWrite(record, allowSlowerAppliedAmendment = true) ?: return false
        return writeDurable(effectiveRecord)
    }

    @Synchronized
    override fun amendFanOwnership(record: TransactionJournalRecord, envelope: RestoreGuardianEnvelope): Boolean =
        synchronized(PROCESS_JOURNAL_LOCK) {
            val existing = (load() as? JournalLoadResult.Loaded)?.record ?: return@synchronized false
            if (!FanOwnershipAmendment.admits(existing, record) ||
                !FanOwnershipAmendment.authorized(existing, envelope) ||
                GuardianJournalBindingValidator.validate(record).isNotEmpty() ||
                AppliedMutationProjectionValidator.validate(record).isNotEmpty()
            ) return@synchronized false
            writeDurable(record)
        }

    private fun persistLocked(record: TransactionJournalRecord): Boolean {
        val effectiveRecord = recordForWrite(record, allowSlowerAppliedAmendment = false) ?: return false
        return writeDurable(effectiveRecord)
    }

    private fun writeDurable(effectiveRecord: TransactionJournalRecord): Boolean {
        val encodedPayload = encodeJournal(effectiveRecord).toByteArray(StandardCharsets.UTF_8)
        val payload = authentication?.protect(encodedPayload) ?: run {
            if (authentication != null) return false
            encodedPayload
        }
        var stream: FileOutputStream? = null
        return try {
            stream = atomic.startWrite()
            stream.write(payload)
            stream.flush()
            stream.fd.sync()
            atomic.finishWrite(stream)
            stream = null
            directoryDurability.sync(filesDir)
            true
        } catch (_: Throwable) {
            if (stream != null) {
                runCatching { atomic.failWrite(stream) }
            }
            false
        }
    }

    @Synchronized
    override fun clear(expectedTransactionId: String): Boolean =
        synchronized(PROCESS_JOURNAL_LOCK) {
            clearLocked(expectedTransactionId)
        }

    private fun clearLocked(expectedTransactionId: String): Boolean {
        return when (val loaded = load()) {
            JournalLoadResult.Empty -> true
            is JournalLoadResult.Corrupt -> false
            is JournalLoadResult.Loaded -> {
                if (loaded.record.transactionId != expectedTransactionId) return false
                runCatching {
                    atomic.delete()
                    if (load() !is JournalLoadResult.Empty) return false
                    directoryDurability.sync(filesDir)
                    true
                }.getOrDefault(false)
            }
        }
    }

    /** One exact, serialized replay transition inside a schema-v4 authenticated record. */
    @Synchronized
    override fun consumeExact(identity: MgdeConsumeIdentity): MgdeConsumeOutcome =
        synchronized(PROCESS_JOURNAL_LOCK) {
            consumeExactLocked(identity)
        }

    private fun consumeExactLocked(identity: MgdeConsumeIdentity): MgdeConsumeOutcome {
        val loaded = load() as? JournalLoadResult.Loaded
            ?: return MgdeConsumeOutcome.UNVERIFIABLE
        val record = loaded.record
        if (
            record.bootIdentity != identity.bootIdentity ||
            record.generation != identity.generation ||
            record.requestId != identity.requestId ||
            record.transactionId != identity.transactionId
        ) {
            return MgdeConsumeOutcome.IDENTITY_MISMATCH
        }
        if (
            record.schemaVersion != TRANSACTION_JOURNAL_SCHEMA &&
            record.schemaVersion != TRANSACTION_JOURNAL_SCHEMA_V3
        ) {
            return MgdeConsumeOutcome.UNVERIFIABLE
        }
        val existingConsumed = record.consumedMgdeEnvelopeSha256
        if (existingConsumed != null) {
            return if (existingConsumed == identity.envelopeSha256) {
                MgdeConsumeOutcome.ALREADY_CONSUMED
            } else {
                MgdeConsumeOutcome.CONFLICTING_ENVELOPE
            }
        }
        // Recovery-readable v1-v3 truth is never upgraded into synthetic renderer authority.
        if (record.schemaVersion != TRANSACTION_JOURNAL_SCHEMA) {
            return MgdeConsumeOutcome.UNVERIFIABLE
        }
        val consumed = record.copy(
            consumedMgdeEnvelopeSha256 = identity.envelopeSha256,
        )
        if (!persist(consumed)) return MgdeConsumeOutcome.UNVERIFIABLE
        val confirmed = load() as? JournalLoadResult.Loaded
            ?: return MgdeConsumeOutcome.UNVERIFIABLE
        return if (
            confirmed.record.bootIdentity == identity.bootIdentity &&
            confirmed.record.generation == identity.generation &&
            confirmed.record.requestId == identity.requestId &&
            confirmed.record.transactionId == identity.transactionId &&
            confirmed.record.schemaVersion == TRANSACTION_JOURNAL_SCHEMA &&
            confirmed.record.consumedMgdeEnvelopeSha256 == identity.envelopeSha256
        ) {
            MgdeConsumeOutcome.FIRST_CONSUME
        } else {
            MgdeConsumeOutcome.UNVERIFIABLE
        }
    }

    /**
     * Phase/boundary persistence may hold a pre-consume in-memory record. Preserve the
     * exact canonical schema-v4 renderer projection and merge only the immutable replay
     * marker for the same transaction. Legacy records remain recovery-readable but this
     * port never rewrites or migrates them.
     */
    private fun recordForWrite(
        candidate: TransactionJournalRecord,
        allowSlowerAppliedAmendment: Boolean,
    ): TransactionJournalRecord? {
        if (GuardianJournalBindingValidator.validate(candidate).isNotEmpty()) return null
        return when (val current = load()) {
            // Nothing is applied yet, so there is nothing to amend downward.
            JournalLoadResult.Empty -> if (allowSlowerAppliedAmendment) null else candidate
            is JournalLoadResult.Corrupt -> null
            is JournalLoadResult.Loaded -> {
                val existing = current.record
                val sameIdentity =
                    existing.bootIdentity == candidate.bootIdentity &&
                        existing.generation == candidate.generation &&
                        existing.requestId == candidate.requestId &&
                        existing.transactionId == candidate.transactionId
                if (!sameIdentity) return null
                val v4 = existing.schemaVersion == TRANSACTION_JOURNAL_SCHEMA &&
                    candidate.schemaVersion == TRANSACTION_JOURNAL_SCHEMA
                val v5 = existing.schemaVersion == TRANSACTION_JOURNAL_SCHEMA_V5 &&
                    candidate.schemaVersion == TRANSACTION_JOURNAL_SCHEMA_V5
                if (!v4 && !v5) return null
                val projectionAdmitted = if (allowSlowerAppliedAmendment) {
                    // A derate rung may only ever slow the machine, and only while the
                    // transaction is ALREADY applied -- never as part of a phase transition,
                    // where the projection must stay frozen exactly as it always has.
                    existing.phase == TransactionPhase.APPLIED &&
                        candidate.phase == TransactionPhase.APPLIED &&
                        AppliedMutationProjection.isMonotonicallySlowerAmendment(
                            existing.appliedMutationProjection,
                            candidate.appliedMutationProjection,
                        )
                } else {
                    AppliedMutationProjection.sameCanonical(
                        existing.appliedMutationProjection,
                        candidate.appliedMutationProjection,
                    )
                }
                if (!projectionAdmitted) return null
                val existingConsumed = existing.consumedMgdeEnvelopeSha256
                val candidateConsumed = candidate.consumedMgdeEnvelopeSha256
                if (
                    existingConsumed != null &&
                    candidateConsumed != null &&
                    existingConsumed != candidateConsumed
                ) {
                    return null
                }
                if (v5) {
                    val prior = existing.guardianBinding ?: return null
                    val next = candidate.guardianBinding ?: return null
                    if (!prior.sameImmutableGuardianBase(next)) return null
                    if (!prior.appendOnlyDigestsTo(next)) return null
                    if (!existing.phase.canAdvanceV5To(candidate.phase)) return null
                }
                candidate.copy(
                    consumedMgdeEnvelopeSha256 = existingConsumed ?: candidateConsumed,
                    guardianBinding = if (v5) existing.guardianBinding!!.mergeAppendOnlyDigests(candidate.guardianBinding!!) else null,
                )
            }
        }
    }

    private fun GuardianJournalBinding.sameImmutableGuardianBase(other: GuardianJournalBinding): Boolean =
        envelopeSha256 == other.envelopeSha256 && taskNonce == other.taskNonce &&
            helperBuildIdentity == other.helperBuildIdentity &&
            tweakerCertificateSha256 == other.tweakerCertificateSha256 &&
            hardDeadlineElapsedRealtimeMs == other.hardDeadlineElapsedRealtimeMs

    private fun GuardianJournalBinding.appendOnlyDigestsTo(other: GuardianJournalBinding): Boolean =
        (readySessionSha256 == null || readySessionSha256 == other.readySessionSha256) &&
            (terminalReceiptSha256 == null || terminalReceiptSha256 == other.terminalReceiptSha256)

    private fun GuardianJournalBinding.mergeAppendOnlyDigests(other: GuardianJournalBinding): GuardianJournalBinding =
        copy(
            readySessionSha256 = readySessionSha256 ?: other.readySessionSha256,
            terminalReceiptSha256 = terminalReceiptSha256 ?: other.terminalReceiptSha256,
        )

    private fun TransactionPhase.canAdvanceV5To(next: TransactionPhase): Boolean = when (this) {
        TransactionPhase.PREPARED -> next == TransactionPhase.PREPARED || next == TransactionPhase.ARMING
        TransactionPhase.ARMING -> next == TransactionPhase.ARMING || next == TransactionPhase.GUARDIAN_READY
        TransactionPhase.GUARDIAN_READY ->
            next in setOf(TransactionPhase.GUARDIAN_READY, TransactionPhase.APPLYING, TransactionPhase.RESTORING)
        TransactionPhase.APPLYING -> next in setOf(TransactionPhase.APPLYING, TransactionPhase.APPLIED, TransactionPhase.RESTORING)
        TransactionPhase.APPLIED -> next in setOf(TransactionPhase.APPLIED, TransactionPhase.RESTORING)
        TransactionPhase.RESTORING -> next in setOf(TransactionPhase.RESTORING, TransactionPhase.RESTORE_FAILED, TransactionPhase.GUARDIAN_SETTLED)
        TransactionPhase.RESTORE_FAILED -> next in setOf(TransactionPhase.RESTORE_FAILED, TransactionPhase.RESTORING, TransactionPhase.GUARDIAN_SETTLED)
        TransactionPhase.GUARDIAN_SETTLED -> next == TransactionPhase.GUARDIAN_SETTLED
    }

    companion object {
        const val JOURNAL_FILE = "tweaker_transaction_journal.json"
        const val GENERATION_FILE = "tweaker_transaction_generation"
        // CORRECTED 2026-09-06 (guardian map §4.3): this used to say "All production components
        // share one Android process". That is false -- `RestoreGuardianProcessService` runs in
        // `:guardian` (AndroidManifest.xml), a separate process with its own JVM and its own copy
        // of this companion object, so this lock CANNOT serialize across processes; it serializes
        // only calls made from THIS process (the default/main one, where AtomicFileTransactionJournal
        // is constructed via createAuthenticatedTransactionJournal). The `:guardian` process opens
        // its own journal (RestoreGuardianProcessService.cleanupInternal, .loadDurableArm) but only
        // ever READS it -- no write from `:guardian` was found, so there is no known write/write
        // race today. If a write is ever added on that side, this lock will not protect it.
        private val PROCESS_JOURNAL_LOCK = Any()

        private fun deviceProtectedFilesDir(context: Context): File {
            // Never catch-and-fall-back to credential storage.
            val protectedContext = context.createDeviceProtectedStorageContext()
            return checkNotNull(protectedContext.filesDir) { "Device-protected filesDir unavailable" }
        }

        fun encodeJournal(record: TransactionJournalRecord): String {
            val guardianFailures = GuardianJournalBindingValidator.validate(record)
            require(guardianFailures.isEmpty()) {
                "journal Guardian phase/evidence invalid: ${guardianFailures.joinToString("; ")}"
            }
            val nodes = JSONArray()
            record.snapshot.nodes.forEach { node ->
                val nodeJson = JSONObject()
                nodeJson.put("logicalId", node.logicalId)
                nodeJson.put("axis", node.axis.name)
                nodeJson.put("canonicalPath", node.canonicalPath)
                nodeJson.put("originalValue", node.originalValue ?: JSONObject.NULL)
                nodeJson.put("originalMode", node.originalMode ?: JSONObject.NULL)
                nodeJson.put("originalUid", node.originalUid ?: JSONObject.NULL)
                nodeJson.put("originalGid", node.originalGid ?: JSONObject.NULL)
                nodeJson.put("present", node.present)
                nodes.put(nodeJson)
            }
            val snapshot = JSONObject()
            snapshot.put("schemaVersion", record.snapshot.schemaVersion)
            snapshot.put("hardwareFingerprint", record.snapshot.hardwareFingerprint)
            snapshot.put("bootIdentity", record.snapshot.bootIdentity)
            snapshot.put("nodes", nodes)
            val root = JSONObject()
            root.put("schemaVersion", record.schemaVersion)
            root.put("generation", record.generation)
            root.put("requestId", record.requestId)
            root.put("transactionId", record.transactionId)
            root.put("hardwareFingerprint", record.hardwareFingerprint)
            root.put("deviceProfile", record.deviceProfile)
            root.put("planHash", record.planHash)
            root.put("profileId", record.profileId)
            root.put("targetPackage", record.targetPackage)
            root.put("phase", record.phase.name)
            root.put("creationTime", record.creationTime)
            root.put("requestDeadline", record.requestDeadline)
            root.put("leaseDeadline", record.leaseDeadline)
            root.put("bootIdentity", record.bootIdentity)
            root.put("lastCompletedMutationBoundary", record.lastCompletedMutationBoundary)
            root.put("lastCompletedRestoreBoundary", record.lastCompletedRestoreBoundary)
            root.put("expectedTopologySha256", record.expectedTopologySha256)
            root.put("snapshotSha256", record.snapshotSha256)
            root.put("snapshot", snapshot)
            root.put("lastFailure", record.lastFailure ?: JSONObject.NULL)
            if (record.schemaVersion >= TRANSACTION_JOURNAL_SCHEMA_V2) {
                val plan = checkNotNull(record.appliedPlan) { "schema-v2 encode requires appliedPlan" }
                val planJson = JSONObject()
                planJson.put("profileId", plan.profileId)
                planJson.put("packageName", plan.packageName)
                val cpuJson = JSONObject()
                plan.cpuMaxKhz.toSortedMap().forEach { (id, khz) ->
                    cpuJson.put(id.toString(), khz)
                }
                planJson.put("cpuMaxKhz", cpuJson)
                planJson.put("gpuMaxPowerLevel", plan.gpuMaxPowerLevel ?: JSONObject.NULL)
                planJson.put("fanMode", plan.fanMode ?: JSONObject.NULL)
                planJson.put("calibrated", plan.calibrated)
                // Written ONLY when the plan drives the mode, so every journal for a plan without the
                // axis stays byte-identical to what an older build wrote (and an older build's journal,
                // which has no key, decodes to null below). The plan hash includes it when non-null, so
                // dropping it here would fail the `planHash mismatch` check on the next load.
                plan.vendorMode?.let { planJson.put("vendorMode", it) }
                root.put("appliedPlan", planJson)
                val orderJson = JSONArray()
                record.applyOrderLogicalIds.forEach { orderJson.put(it) }
                root.put("applyOrderLogicalIds", orderJson)
            }
            if (record.schemaVersion >= TRANSACTION_JOURNAL_SCHEMA_V3) {
                val consumed = record.consumedMgdeEnvelopeSha256
                require(consumed == null || SnapshotIntegrity.isValidSha256Hex(consumed)) {
                    "schema-v3+ consumed MGDE digest invalid"
                }
                root.put("consumedMgdeEnvelopeSha256", consumed ?: JSONObject.NULL)
            } else {
                require(record.consumedMgdeEnvelopeSha256 == null) {
                    "legacy journal cannot carry consumed MGDE truth"
                }
            }
            if (record.schemaVersion >= TRANSACTION_JOURNAL_SCHEMA) {
                val projectionFailures = AppliedMutationProjectionValidator.validate(record)
                require(projectionFailures.isEmpty()) {
                    "schema-v4 applied mutation projection invalid: " +
                        projectionFailures.joinToString("; ")
                }
                val projectionJson = JSONArray()
                record.appliedMutationProjection.forEach { mutation ->
                    projectionJson.put(
                        JSONObject().apply {
                            put("logicalId", mutation.logicalId)
                            put("axis", mutation.axis.name)
                            put("canonicalPath", mutation.canonicalPath)
                            put("kind", mutation.kind.name)
                            put("value", mutation.value ?: JSONObject.NULL)
                            put("expectPresent", mutation.expectPresent)
                        },
                    )
                }
                root.put("appliedMutationProjection", projectionJson)
            } else {
                require(record.appliedMutationProjection.isEmpty()) {
                    "legacy journal cannot carry applied mutation projection"
                }
            }
            if (record.schemaVersion == TRANSACTION_JOURNAL_SCHEMA_V5) {
                val binding = checkNotNull(record.guardianBinding) {
                    "schema-v5 encode requires immutable Guardian binding"
                }
                root.put("guardianBinding", JSONObject().apply {
                    put("envelopeSha256", binding.envelopeSha256)
                    put("taskNonce", binding.taskNonce)
                    put("helperBuildIdentity", binding.helperBuildIdentity)
                    put("tweakerCertificateSha256", binding.tweakerCertificateSha256)
                    put("hardDeadlineElapsedRealtimeMs", binding.hardDeadlineElapsedRealtimeMs)
                    put("readySessionSha256", binding.readySessionSha256 ?: JSONObject.NULL)
                    put("terminalReceiptSha256", binding.terminalReceiptSha256 ?: JSONObject.NULL)
                })
            } else {
                require(record.guardianBinding == null) { "legacy journal cannot carry Guardian binding" }
            }
            return root.toString()
        }

        fun decodeJournal(raw: String): JournalLoadResult {
            val json = runCatching { JSONObject(raw) }.getOrElse {
                return JournalLoadResult.Corrupt("Journal JSON parse failed")
            }
            return try {
                val schema = json.getInt("schemaVersion")
                if (
                    schema != TRANSACTION_JOURNAL_SCHEMA &&
                    schema != TRANSACTION_JOURNAL_SCHEMA_V5 &&
                    schema != TRANSACTION_JOURNAL_SCHEMA_V3 &&
                    schema != TRANSACTION_JOURNAL_SCHEMA_V2 &&
                    schema != TRANSACTION_JOURNAL_SCHEMA_V1
                ) {
                    return JournalLoadResult.Corrupt("Unsupported journal schema $schema")
                }
                val phaseName = json.getString("phase")
                if (phaseName == "ACTIVE" || phaseName == "RECOVERY_REQUIRED") {
                    return JournalLoadResult.Corrupt("Superseded phase $phaseName")
                }
                val phase = runCatching { TransactionPhase.valueOf(phaseName) }.getOrElse {
                    return JournalLoadResult.Corrupt("Unknown phase $phaseName")
                }
                if (
                    schema != TRANSACTION_JOURNAL_SCHEMA_V5 &&
                    phase in setOf(
                        TransactionPhase.ARMING,
                        TransactionPhase.GUARDIAN_READY,
                        TransactionPhase.GUARDIAN_SETTLED,
                    )
                ) {
                    return JournalLoadResult.Corrupt("Guardian phase requires schema-v5")
                }
                val generation = json.getLong("generation")
                if (generation <= 0L) {
                    return JournalLoadResult.Corrupt("Non-positive generation")
                }
                val hardwareFingerprint = json.getString("hardwareFingerprint")
                val deviceProfile = json.getString("deviceProfile")
                val planHash = json.getString("planHash")
                val profileId = json.getString("profileId")
                val targetPackage = json.getString("targetPackage")
                val bootIdentity = json.getString("bootIdentity")
                if (listOf(hardwareFingerprint, deviceProfile, planHash, profileId, targetPackage, bootIdentity)
                        .any { it.isBlank() }
                ) {
                    return JournalLoadResult.Corrupt("Blank required journal identity field")
                }
                val creationTime = json.getLong("creationTime")
                val requestDeadline = json.getLong("requestDeadline")
                val leaseDeadline = json.getLong("leaseDeadline")
                val lastCompletedMutationBoundary = json.getInt("lastCompletedMutationBoundary")
                if (lastCompletedMutationBoundary < 0) {
                    return JournalLoadResult.Corrupt("Negative mutation boundary")
                }
                val lastCompletedRestoreBoundary =
                    if (json.has("lastCompletedRestoreBoundary") && !json.isNull("lastCompletedRestoreBoundary")) {
                        json.getInt("lastCompletedRestoreBoundary")
                    } else {
                        0
                    }
                if (lastCompletedRestoreBoundary < 0) {
                    return JournalLoadResult.Corrupt("Negative restore boundary")
                }

                val snapshotJson = json.getJSONObject("snapshot")
                val snapshotSchema = snapshotJson.getInt("schemaVersion")
                if (snapshotSchema != HARDWARE_SNAPSHOT_SCHEMA) {
                    return JournalLoadResult.Corrupt("Unsupported snapshot schema $snapshotSchema")
                }
                val snapshotFingerprint = snapshotJson.getString("hardwareFingerprint")
                val snapshotBoot = snapshotJson.getString("bootIdentity")
                if (snapshotFingerprint.isBlank() || snapshotBoot.isBlank()) {
                    return JournalLoadResult.Corrupt("Snapshot missing fingerprint/bootIdentity")
                }
                if (snapshotFingerprint != hardwareFingerprint || snapshotBoot != bootIdentity) {
                    return JournalLoadResult.Corrupt("Snapshot identity mismatch vs journal envelope")
                }
                val expectedTopologySha256 = json.optString("expectedTopologySha256", "")
                val snapshotSha256 = json.optString("snapshotSha256", "")
                if (!SnapshotIntegrity.isValidSha256Hex(expectedTopologySha256)) {
                    return JournalLoadResult.Corrupt("Invalid or missing expectedTopologySha256")
                }
                if (!SnapshotIntegrity.isValidSha256Hex(snapshotSha256)) {
                    return JournalLoadResult.Corrupt("Invalid or missing snapshotSha256")
                }
                val nodesJson = snapshotJson.getJSONArray("nodes")
                if (nodesJson.length() == 0) {
                    return JournalLoadResult.Corrupt("Snapshot has no topology nodes")
                }
                val nodes = buildList {
                    for (i in 0 until nodesJson.length()) {
                        val node = nodesJson.getJSONObject(i)
                        val axis = runCatching { TuningAxis.valueOf(node.getString("axis")) }.getOrElse {
                            return JournalLoadResult.Corrupt("Unknown axis")
                        }
                        val logicalId = node.getString("logicalId")
                        val path = node.getString("canonicalPath")
                        if (logicalId.isBlank() || path.isBlank()) {
                            return JournalLoadResult.Corrupt("Node missing identity")
                        }
                        if (axis == TuningAxis.VENDOR_MODE) {
                            if (path != HardwareSnapshot.VENDOR_MODE_SETTINGS_PATH) {
                                return JournalLoadResult.Corrupt("Vendor mode node noncanonical path: $logicalId")
                            }
                        } else if (axis != TuningAxis.FAN) {
                            val canonical = SysfsPath.canonicalize(path)
                            if (canonical == null || canonical != path) {
                                return JournalLoadResult.Corrupt("Node noncanonical path: $logicalId")
                            }
                        } else if (path != HardwareSnapshot.FAN_SETTINGS_PATH) {
                            return JournalLoadResult.Corrupt("Fan node noncanonical path: $logicalId")
                        }
                        val present = node.getBoolean("present")
                        val originalValue = node.nullableInt("originalValue")
                        val originalMode = node.nullableString("originalMode")
                        val originalUid = node.nullableInt("originalUid")
                        val originalGid = node.nullableInt("originalGid")
                        add(
                            TopologyNodeState(
                                logicalId = logicalId,
                                axis = axis,
                                canonicalPath = path,
                                originalValue = originalValue,
                                originalMode = originalMode,
                                originalUid = originalUid,
                                originalGid = originalGid,
                                present = present,
                            ),
                        )
                    }
                }
                val snapshot = HardwareSnapshot(
                    schemaVersion = snapshotSchema,
                    hardwareFingerprint = snapshotFingerprint,
                    bootIdentity = snapshotBoot,
                    nodes = nodes,
                )
                if (
                    schema <= TRANSACTION_JOURNAL_SCHEMA_V3 &&
                    snapshot.cpuMinKhz.isNotEmpty()
                ) {
                    return JournalLoadResult.Corrupt("Legacy schema cannot carry CPU floor topology")
                }
                SnapshotIntegrity.validateDecodedSnapshot(
                    snapshot = snapshot,
                    expectedTopologyDigest = expectedTopologySha256,
                    expectedSnapshotDigest = snapshotSha256,
                )?.let { reason ->
                    return JournalLoadResult.Corrupt(reason)
                }
                val transactionId = json.getString("transactionId")
                val requestId = json.getString("requestId")
                if (transactionId.isBlank() || requestId.isBlank()) {
                    return JournalLoadResult.Corrupt("Blank transaction/request id")
                }
                if (lastCompletedMutationBoundary > nodes.size) {
                    return JournalLoadResult.Corrupt(
                        "Mutation boundary overflow: $lastCompletedMutationBoundary > ${nodes.size}",
                    )
                }
                // The restore boundary is bounded AFTER the record is assembled, against the restore
                // list the record actually produces (RestoreMutationOrder.logicalIds): a duty-owned
                // session with a vendor mode restores the fan twice, so its list is one longer than the
                // node set and a fully restored V4 record would otherwise decode as Corrupt here.
                val appliedPlan: PersistedTuningPlan?
                val applyOrderLogicalIds: List<String>
                if (schema >= TRANSACTION_JOURNAL_SCHEMA_V2) {
                    val planJson = json.optJSONObject("appliedPlan")
                        ?: return JournalLoadResult.Corrupt("schema-v2 missing appliedPlan")
                    val cpuJson = planJson.getJSONObject("cpuMaxKhz")
                    val cpu = sortedMapOf<Int, Int>()
                    val keys = cpuJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val policyId = key.toIntOrNull()
                            ?: return JournalLoadResult.Corrupt("appliedPlan cpu key not int: $key")
                        cpu[policyId] = cpuJson.getInt(key)
                    }
                    appliedPlan = PersistedTuningPlan(
                        profileId = planJson.getString("profileId"),
                        packageName = planJson.getString("packageName"),
                        cpuMaxKhz = cpu.toMap(),
                        gpuMaxPowerLevel = planJson.nullableInt("gpuMaxPowerLevel"),
                        fanMode = planJson.nullableInt("fanMode"),
                        calibrated = planJson.getBoolean("calibrated"),
                        vendorMode = planJson.nullableInt("vendorMode"),
                    )
                    val orderJson = json.optJSONArray("applyOrderLogicalIds")
                        ?: return JournalLoadResult.Corrupt("schema-v2 missing applyOrderLogicalIds")
                    if (orderJson.length() == 0) {
                        return JournalLoadResult.Corrupt("schema-v2 empty applyOrderLogicalIds")
                    }
                    applyOrderLogicalIds = buildList {
                        for (i in 0 until orderJson.length()) {
                            val id = orderJson.getString(i)
                            if (id.isBlank()) {
                                return JournalLoadResult.Corrupt("blank applyOrderLogicalId")
                            }
                            add(id)
                        }
                    }
                } else {
                    // Schema-v1: recovery-only compatibility; never rewrite.
                    appliedPlan = null
                    applyOrderLogicalIds = emptyList()
                }
                val consumedMgdeEnvelopeSha256 = when (schema) {
                    TRANSACTION_JOURNAL_SCHEMA,
                    TRANSACTION_JOURNAL_SCHEMA_V5,
                    TRANSACTION_JOURNAL_SCHEMA_V3,
                    -> {
                        if (!json.has("consumedMgdeEnvelopeSha256")) {
                            return JournalLoadResult.Corrupt(
                                "schema-v3+ missing consumedMgdeEnvelopeSha256",
                            )
                        }
                        json.nullableString("consumedMgdeEnvelopeSha256")?.also { digest ->
                            if (!SnapshotIntegrity.isValidSha256Hex(digest)) {
                                return JournalLoadResult.Corrupt(
                                    "schema-v3+ consumed MGDE digest invalid",
                                )
                            }
                        }
                    }
                    TRANSACTION_JOURNAL_SCHEMA_V2,
                    TRANSACTION_JOURNAL_SCHEMA_V1,
                    -> {
                        if (json.has("consumedMgdeEnvelopeSha256")) {
                            return JournalLoadResult.Corrupt(
                                "legacy journal carries consumed MGDE truth",
                            )
                        }
                        null
                    }
                    else -> return JournalLoadResult.Corrupt("Unsupported journal schema $schema")
                }
                val appliedMutationProjection = when (schema) {
                    TRANSACTION_JOURNAL_SCHEMA,
                    TRANSACTION_JOURNAL_SCHEMA_V5,
                    -> {
                        val projectionJson = json.optJSONArray("appliedMutationProjection")
                            ?: return JournalLoadResult.Corrupt(
                                "schema-v4 missing appliedMutationProjection",
                            )
                        if (projectionJson.length() == 0) {
                            return JournalLoadResult.Corrupt(
                                "schema-v4 empty appliedMutationProjection",
                            )
                        }
                        buildList<PersistedAppliedMutation> {
                            for (i in 0 until projectionJson.length()) {
                                val mutation = projectionJson.getJSONObject(i)
                                val expectedKeys = setOf(
                                    "logicalId",
                                    "axis",
                                    "canonicalPath",
                                    "kind",
                                    "value",
                                    "expectPresent",
                                )
                                if (mutation.keyNames() != expectedKeys) {
                                    return JournalLoadResult.Corrupt(
                                        "schema-v4 projected mutation fields invalid",
                                    )
                                }
                                val logicalId = mutation.getString("logicalId")
                                val canonicalPath = mutation.getString("canonicalPath")
                                if (logicalId.isBlank() || canonicalPath.isBlank()) {
                                    return JournalLoadResult.Corrupt(
                                        "schema-v4 projected mutation identity missing",
                                    )
                                }
                                val axis = runCatching {
                                    TuningAxis.valueOf(mutation.getString("axis"))
                                }.getOrElse {
                                    return JournalLoadResult.Corrupt(
                                        "schema-v4 projected mutation axis invalid",
                                    )
                                }
                                val kind = runCatching {
                                    LogicalMutationKind.valueOf(mutation.getString("kind"))
                                }.getOrElse {
                                    return JournalLoadResult.Corrupt(
                                        "schema-v4 projected mutation kind invalid",
                                    )
                                }
                                add(
                                    PersistedAppliedMutation(
                                        logicalId = logicalId,
                                        axis = axis,
                                        canonicalPath = canonicalPath,
                                        kind = kind,
                                        value = mutation.nullableInt("value"),
                                        expectPresent = mutation.getBoolean("expectPresent"),
                                    ),
                                )
                            }
                        }
                    }
                    TRANSACTION_JOURNAL_SCHEMA_V3,
                    TRANSACTION_JOURNAL_SCHEMA_V2,
                    TRANSACTION_JOURNAL_SCHEMA_V1,
                    -> {
                        if (json.has("appliedMutationProjection")) {
                            return JournalLoadResult.Corrupt(
                                "legacy journal carries applied mutation projection",
                            )
                        }
                        emptyList()
                    }
                    else -> return JournalLoadResult.Corrupt("Unsupported journal schema $schema")
                }
                val guardianBinding = when (schema) {
                    TRANSACTION_JOURNAL_SCHEMA_V5 -> {
                        val binding = json.optJSONObject("guardianBinding")
                            ?: return JournalLoadResult.Corrupt("schema-v5 missing guardianBinding")
                        val expected = setOf(
                            "envelopeSha256", "taskNonce", "helperBuildIdentity",
                            "tweakerCertificateSha256", "hardDeadlineElapsedRealtimeMs",
                            "readySessionSha256", "terminalReceiptSha256",
                        )
                        if (binding.keyNames() != expected) {
                            return JournalLoadResult.Corrupt("schema-v5 Guardian binding fields invalid")
                        }
                        runCatching {
                            GuardianJournalBinding(
                                envelopeSha256 = binding.getString("envelopeSha256"),
                                taskNonce = binding.getString("taskNonce"),
                                helperBuildIdentity = binding.getString("helperBuildIdentity"),
                                tweakerCertificateSha256 = binding.getString("tweakerCertificateSha256"),
                                hardDeadlineElapsedRealtimeMs = binding.getLong("hardDeadlineElapsedRealtimeMs"),
                                readySessionSha256 = binding.nullableString("readySessionSha256"),
                                terminalReceiptSha256 = binding.nullableString("terminalReceiptSha256"),
                            )
                        }.getOrElse {
                            return JournalLoadResult.Corrupt("schema-v5 Guardian binding invalid")
                        }
                    }
                    else -> {
                        if (json.has("guardianBinding")) {
                            return JournalLoadResult.Corrupt("legacy journal carries Guardian binding")
                        }
                        null
                    }
                }
                val record = TransactionJournalRecord(
                    schemaVersion = schema,
                    generation = generation,
                    requestId = requestId,
                    transactionId = transactionId,
                    hardwareFingerprint = hardwareFingerprint,
                    deviceProfile = deviceProfile,
                    planHash = planHash,
                    profileId = profileId,
                    targetPackage = targetPackage,
                    phase = phase,
                    creationTime = creationTime,
                    requestDeadline = requestDeadline,
                    leaseDeadline = leaseDeadline,
                    bootIdentity = bootIdentity,
                    snapshot = snapshot,
                    expectedTopologySha256 = expectedTopologySha256,
                    snapshotSha256 = snapshotSha256,
                    lastCompletedMutationBoundary = lastCompletedMutationBoundary,
                    lastCompletedRestoreBoundary = lastCompletedRestoreBoundary,
                    lastFailure = json.nullableString("lastFailure"),
                    appliedPlan = appliedPlan,
                    applyOrderLogicalIds = applyOrderLogicalIds,
                    appliedMutationProjection = appliedMutationProjection,
                    consumedMgdeEnvelopeSha256 = consumedMgdeEnvelopeSha256,
                    guardianBinding = guardianBinding,
                )
                val restoreSteps = runCatching { RestoreMutationOrder.logicalIds(record).size }
                    .getOrDefault(nodes.size)
                if (lastCompletedRestoreBoundary > restoreSteps) {
                    return JournalLoadResult.Corrupt(
                        "Restore boundary overflow: $lastCompletedRestoreBoundary > $restoreSteps",
                    )
                }
                GuardianJournalBindingValidator.validate(record).firstOrNull()?.let { failure ->
                    return JournalLoadResult.Corrupt("Journal Guardian phase/evidence invalid: $failure")
                }
                AppliedMutationProjectionValidator.validate(record).firstOrNull()?.let { failure ->
                    return JournalLoadResult.Corrupt(
                        "Journal applied mutation projection invalid: $failure",
                    )
                }
                JournalLoadResult.Loaded(record)
            } catch (error: Throwable) {
                JournalLoadResult.Corrupt("Journal decode failed: ${error.message}")
            }
        }

        private fun JSONObject.nullableInt(key: String): Int? =
            if (has(key) && !isNull(key)) getInt(key) else null

        private fun JSONObject.nullableString(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null

        private fun JSONObject.keyNames(): Set<String> = buildSet {
            val names = keys()
            while (names.hasNext()) add(names.next())
        }
    }
}

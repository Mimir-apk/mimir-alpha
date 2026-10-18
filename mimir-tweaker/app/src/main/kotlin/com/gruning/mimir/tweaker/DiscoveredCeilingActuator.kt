package com.gruning.mimir.tweaker

import kotlin.math.abs

/**
 * The discovered clock ceiling, WRITTEN by the daemon (API v10).
 *
 * The daemon owns the clock node whenever present; the Tuner routes through it and writes
 * directly only when no daemon exists. This must be deterministic and recoverable from any state.
 * v9 moved the RECORD here and
 * deliberately left the WRITE in Mimir; this file is the other half — the write moves too, and the
 * record it keeps is the only thing that knows the way back.
 *
 * **No sysfs path crosses the Binder boundary and none can.** The caller names an AXIS, a POLICY ID
 * and a kHz. Every path below is a compile-time constant or a directory this process itself
 * enumerated through [HardwareProbe]; every value written is an integer this file derived from a
 * table the kernel published. Nothing a caller supplies is ever interpolated into a command.
 *
 * **This write is deliberately UN-FENCED, and that is the load-bearing decision in the file.**
 * Every profile mutation in this daemon goes through [TransactionCoordinator] → the authenticated
 * journal → [GuardianFencedHardwareCommandPort], which refuses unless
 * `RemoteGuardianProcessAuthority.currentFenceIdentity()` answers — and that identity exists only
 * inside an ARMED transaction. A discovered ceiling is not a transaction: it is player-owned and
 * boot-bound, it holds no lease, it expires with nothing, and it is restored by the player's own
 * withdraw or by the daemon immediately before a named profile goes over it. Its restore memory is
 * the RECORD, not a Guardian envelope, so arming a Guardian for it would buy a rollback path that
 * nothing in its lifecycle ever uses while making a player's cap unavailable on any device where
 * the Guardian process is not up. This class therefore talks to [CeilingCommandPort], which the
 * service binds to `PServerClient.executeChecked` directly. It never touches the Guardian, the
 * transaction journal, a lease, or the supervisor. `DiscoveredCeilingSourcePinTest` pins that.
 *
 * **A ceiling and a named profile are mutually exclusive.** The profile owns the same nodes with a
 * richer (signed, journalled, Guardian-fenced) claim, so a ceiling is refused while any session
 * exists, and a held ceiling is withdrawn before a profile apply rather than being captured by it
 * as stock. That exclusion is what lets the keeper below skip a per-tick session read: the only
 * door into a session runs [withdrawBeforeProfileApply] first, and that stops the keeper.
 *
 * **A held ceiling is WATCHED, because this node has a second writer.** Device evidence (AYN Thor,
 * 2026-09-11, daemon 0.4.1-tw04, Mimir 98d627ef): a confirmed `7:1843200` held for 40+ s, and then
 * — when the system relaunched the stopped HOME app — the row read `7:2147483647` within seconds
 * with NO daemon line of any kind. The daemon wrote nothing; the vendor performance HAL's
 * app-launch boost/release, this node's known second writer, put CPU 7 back to INT_MAX. The
 * daemon's own named profiles survive that same event only because `HardwareSessionSupervisor`
 * re-asserts drifted CPU targets on its 1 s tick. A held ceiling had no such watcher, so it
 * silently evaporated while the record still said HELD and the next cold start adopted a record
 * whose cap was already gone. [CeilingKeeperScheduler] is the missing watcher: a separate,
 * independent 1 s tick that re-asserts ONLY the values this record asked for, on ONLY the CPUs that
 * drifted, inside the same bounded window/burst budget the supervisor's own `admitTargetRepair`
 * uses — and that degrades to doing nothing when the budget is exhausted, never to a restore and
 * never to clearing the record.
 */
internal class DiscoveredCeilingActuator(
    private val discovery: CeilingDiscovery,
    private val nodes: CeilingNodeReader,
    private val commands: CeilingCommandPort,
    private val records: CeilingRecordStore,
    private val clock: CeilingClock,
    private val session: CeilingProfileSessionState,
    private val keeper: CeilingKeeperScheduler,
    private val hardwareWritesCompiled: Boolean,
    private val log: (String) -> Unit = {},
) {

    /**
     * When each admitted repair happened, on the elapsed-realtime clock.
     *
     * Touched from the keeper's tick AND from a Binder [query], so every read-modify-write of it is
     * inside [stateLock]. The same lock serializes the tick against apply/withdraw/query, which is
     * what makes "stop the keeper, THEN read the live row" (see [withdrawInternal]) mean what it
     * says instead of merely asking politely.
     */
    private val repairAttempts = ArrayDeque<Long>()
    private val stateLock = Any()

    /**
     * A withdraw restored the hardware and read it back at stock but could not confirm deleting its record. While true
     * the keeper must not run (it would re-cap hardware that was just given back); apply and query finish the delete first.
     * Guarded by [stateLock].
     */
    private var restoreAwaitingRecordDelete = false

    /** Consecutive keeper ticks that found the record unreadable; log the first and then only now and then. */
    private var unreadableRecordTicks = 0

    /** Consecutive repairs skipped because the live CPU row was not a complete reading. */
    private var unreadableCpuRowTicks = 0

    /**
     * Request a ceiling on one discovered policy. First failure wins, in the order written.
     *
     * The caller's [ceilingKhz] is re-admitted against this process's OWN discovery even though the
     * frontend already snapped it: an admission that trusts the caller's arithmetic is not an
     * admission.
     */
    fun apply(axis: Int, policyId: Int, ceilingKhz: Int): CeilingOutcome = synchronized(stateLock) {
        val outcome = applyInternal(axis, policyId, ceilingKhz)
        log(
            "DISCOVERED_CEILING apply axis=${axisName(axis)} policy=$policyId requested=$ceilingKhz " +
                "admitted=${(outcome as? CeilingOutcome.Applied)?.admittedKhz ?: -1} " +
                "delivered=${outcome is CeilingOutcome.Applied} " +
                "inForce=${(outcome as? CeilingOutcome.Applied)?.inForceKhz ?: -1} " +
                "outcome=${outcome.wireOutcome()}${outcome.refusalSuffix()}",
        )
        // The keeper follows the RECORD, not this outcome: an apply that refused after rolling the
        // store back to a previously held record must leave that record's watcher running, and an
        // apply that refused with nothing held must not start one.
        syncKeeperToRecord()
        outcome
    }

    private fun applyInternal(axis: Int, policyId: Int, ceilingKhz: Int): CeilingOutcome {
        // 2. Posture. A build that cannot write hardware must not pretend it captured stock either.
        if (!hardwareWritesCompiled) {
            return refused(
                TweakerDiscoveredCeilingContract.REFUSAL_POSTURE_NOT_ADMITTED,
                "this build is read-only; HARDWARE_WRITES_COMPILED is false",
            )
        }
        // 3. Profile precedence. "No session / stock" is the only answer that is not an active
        // profile: APPLYING, ACTIVE, RESTORING, RECOVERY_FAILED, CORRUPT and UNKNOWN all mean a
        // named profile may be holding these very nodes.
        session.activeProfileStateOrNull()?.let { state ->
            return refused(
                TweakerDiscoveredCeilingContract.REFUSAL_PROFILE_ACTIVE,
                "a named profile session is $state",
            )
        }
        // A record that exists but cannot be read is not "nothing held": capturing stock from a node that may still
        // carry our own earlier cap would record the cap as stock, and the only record of the way back is the one
        // that could not be read. Nothing is written.
        val view = recordView()
        (view as? RecordView.Unreadable)?.let { unreadable ->
            return refused(
                TweakerDiscoveredCeilingContract.REFUSAL_STORE_UNAVAILABLE,
                "UNCONFIRMED, the ceiling record could not be read (${unreadable.detail}); nothing was written",
            )
        }
        // A previous withdraw restored the hardware but could not delete its record. Whatever that record still says is
        // spent, and capturing stock from it (or from the hardware beside it) would be built on a restore nobody confirmed
        // as finished. The delete is retried first; while it still cannot be confirmed nothing is captured or written.
        // The owed delete is read from the RECORD on disk (its pending-delete marker), not only from this process's
        // memory: after a daemon restart the in-memory flag starts false and the surviving record is all that remembers.
        // That holds whichever boot wrote it: a spent record from an earlier boot still owes its confirmed delete, and is
        // never overwritten by the capture below before it has had one.
        val deleteOwed = restoreAwaitingRecordDelete || view.owesDelete()
        if (deleteOwed && !deleteRecordConfirmed()) {
            return refused(
                TweakerDiscoveredCeilingContract.REFUSAL_STORE_UNAVAILABLE,
                "UNCONFIRMED, a previous ceiling was given back but its record still cannot be deleted; nothing was written",
            )
        }
        restoreAwaitingRecordDelete = false
        return when (axis) {
            TweakerDiscoveredCeilingContract.AXIS_CPU -> applyCpu(policyId, ceilingKhz)
            TweakerDiscoveredCeilingContract.AXIS_GPU -> applyGpu(policyId, ceilingKhz)
            else -> refused(
                TweakerDiscoveredCeilingContract.REFUSAL_UNDISCOVERED_POLICY,
                "axis $axis is not CPU or GPU",
            )
        }
    }

    private fun applyCpu(policyId: Int, ceilingKhz: Int): CeilingOutcome {
        // 4. Discovery, from probe.probe() and never from qualification or capabilities().
        val policy = discovery.cpuPolicies().firstOrNull { it.id == policyId }
            ?: return refused(
                TweakerDiscoveredCeilingContract.REFUSAL_UNDISCOVERED_POLICY,
                "cpu policy $policyId is not one this device published",
            )
        if (policy.cpuIds.isEmpty()) {
            return refused(
                TweakerDiscoveredCeilingContract.REFUSAL_UNDISCOVERED_CPU,
                "cpu policy $policyId published no affected_cpus",
            )
        }
        // 5. Snap onto the policy's own OPP table.
        val snapped = DiscoveredCeilingPlan.nearestSupportedKhz(policy.availableKhz, ceilingKhz)
            ?: return refused(
                TweakerDiscoveredCeilingContract.REFUSAL_UNDISCOVERED_FREQUENCY,
                "cpu policy $policyId cannot serve $ceilingKhz kHz",
            )
        // 6. A ceiling below the live floor is not a writable state (policy ledger
        // 2026-09-01): the kernel raises the ceiling to meet the floor and the readback then
        // disagrees with what was asked for. Clamp UP, exactly as CpuWindowAdmissionPolicy does.
        val admittedKhz = DiscoveredCeilingPlan.clampUpToLiveFloor(snapped, policy.settledFloorKhz)

        // 7. Capture stock BEFORE any write, and persist it before any write.
        val prior = heldRecord()
        val base = prior ?: DiscoveredAxisRecord(bootIdentity = bootIdentity(), cpuStockRow = "", cpuRequestedRow = "")
        val withStock = if (base.cpuStockRow.isBlank()) {
            // Strict: a row with a pair the lenient parser would drop is not a reading of this node, and stock captured
            // from it would silently lack the CPU whose way back is the thing being recorded.
            val liveRow = nodes.read(HardwareProbe.CPU_QOS_MAX_PATH)?.trim()?.takeIf { it.isNotEmpty() }
                ?.takeIf { DiscoveredCeilingPlan.parseCpuQosRowStrict(it) != null }
                ?: return refused(
                    TweakerDiscoveredCeilingContract.REFUSAL_HOST_UNAVAILABLE,
                    "the CPU QoS row is unreadable or malformed, so no stock could be captured",
                )
            val corrected = DiscoveredCeilingPlan.stockCpuRowWithoutHeldCaps(liveRow, publishedCpuCeilings())
            if (corrected != liveRow) {
                log("DISCOVERED_CEILING capture: a cap was already in force live=$liveRow recorded=$corrected")
            }
            base.copy(cpuStockRow = corrected)
        } else {
            base
        }
        // A CPU with no stock entry has no way back: a withdraw could not restore it and would have to skip it, and the
        // record would then clear with that CPU still capped. It is refused HERE, before anything is persisted or written.
        val uncovered = DiscoveredCeilingPlan.cpusWithoutStock(
            requestedCpus = policy.cpuIds,
            stock = DiscoveredCeilingPlan.parseCpuQosRow(withStock.cpuStockRow),
        )
        if (uncovered.isNotEmpty()) {
            return refused(
                TweakerDiscoveredCeilingContract.REFUSAL_HOST_UNAVAILABLE,
                "the stock row names no entry for cpu ${DiscoveredCeilingPlan.formatCpuIds(uncovered)}, " +
                    "so there would be no way back; nothing was written",
            )
        }
        val requestedRow = DiscoveredCeilingPlan.parseCpuQosRow(withStock.cpuRequestedRow) +
            policy.cpuIds.associateWith { admittedKhz }
        val intent = withStock.copy(cpuRequestedRow = DiscoveredCeilingPlan.formatCpuQosRow(requestedRow))
        persist(intent)?.let { return it }

        // 8. The write. One pair per checked command, which is this daemon's own proven spelling
        // for this node (PServerHardwareCommandPort.execute emits exactly `echo $cpuId:$value >
        // $path` per CPU for the shared QoS transport) and the only spelling that fits inside
        // PServerClient's 198-byte checked envelope on an eight-CPU chassis. Every CPU of the
        // policy is named because the node is per-CPU while the player's choice is per-CLUSTER; no
        // CPU outside the policy is named, so another writer's CPUs are untouched.
        val delivered = deliver(DiscoveredCeilingPlan.cpuCeilingCommands(policy.cpuIds, admittedKhz))
        if (delivered != null) return refuseUndelivered(prior, delivered)

        // 9. Verify against the node we wrote, then read what is actually in force.
        val liveAfterRaw = nodes.read(HardwareProbe.CPU_QOS_MAX_PATH)
        // Strict: a row with a pair the parser would drop, or one that does not name every CPU we wrote, is not a
        // reading of this node. It cannot show that a CPU stopped holding our value, so it is unreadable, never "none".
        val liveAfter = DiscoveredCeilingPlan.parseCpuQosRowStrict(liveAfterRaw)
        if (liveAfter == null || policy.cpuIds.any { it !in liveAfter }) {
            return refused(
                TweakerDiscoveredCeilingContract.REFUSAL_WRITE_NOT_HELD,
                "UNCONFIRMED, the intent record is kept: cpu policy $policyId readback " +
                    if (liveAfterRaw.isNullOrBlank()) "was unreadable" else "was incomplete or malformed",
            )
        }
        if (policy.cpuIds.any { liveAfter[it] != admittedKhz }) {
            // The write was confirmed, so the readback is the only evidence left. A row where SOME of our CPUs hold it
            // means a partial ceiling is in force, and the intent record is the only way back: it must stay. Only a
            // complete row that shows none of our value is proof that nothing of ours is held.
            if (policy.cpuIds.any { liveAfter[it] == admittedKhz }) {
                return refused(
                    TweakerDiscoveredCeilingContract.REFUSAL_WRITE_NOT_HELD,
                    "UNCONFIRMED, the intent record is kept: cpu policy $policyId readback shows only part of $admittedKhz kHz",
                )
            }
            rollbackRecord(prior)
            return refused(
                TweakerDiscoveredCeilingContract.REFUSAL_WRITE_NOT_HELD,
                "cpu policy $policyId did not take $admittedKhz kHz",
            )
        }
        val inForceKhz = nodes.read("${policy.path}/scaling_max_freq")?.trim()?.toIntOrNull()
            ?: TweakerDiscoveredCeilingContract.UNREADABLE_KHZ

        // 10. The record is already at the requested row; re-persist so a partially-applied step
        // can never leave the store behind the hardware.
        persist(intent)?.let { return it }
        return CeilingOutcome.Applied(
            axis = TweakerDiscoveredCeilingContract.AXIS_CPU,
            policyId = policyId,
            requestedKhz = ceilingKhz,
            admittedKhz = admittedKhz,
            inForceKhz = inForceKhz,
            record = intent,
        )
    }

    private fun applyGpu(policyId: Int, ceilingKhz: Int): CeilingOutcome {
        if (policyId != TweakerDiscoveredCeilingContract.GPU_POLICY_ID) {
            return refused(
                TweakerDiscoveredCeilingContract.REFUSAL_UNDISCOVERED_POLICY,
                "the GPU axis is policy ${TweakerDiscoveredCeilingContract.GPU_POLICY_ID}, not $policyId",
            )
        }
        val available = discovery.gpuFrequenciesKhz()
        if (!discovery.gpuAxisAvailable() || available.isEmpty()) {
            return refused(
                TweakerDiscoveredCeilingContract.REFUSAL_NO_GPU_AXIS,
                "this device published no GPU power levels",
            )
        }
        val snapped = DiscoveredCeilingPlan.nearestSupportedKhz(available, ceilingKhz)
            ?: return refused(
                TweakerDiscoveredCeilingContract.REFUSAL_UNDISCOVERED_FREQUENCY,
                "the GPU cannot serve $ceilingKhz kHz",
            )
        // The GPU has no live-floor clamp: its bound is a power-level INDEX, and widening
        // min_pwrlevel to the slowest level (below) is what makes every level writable.
        val level = DiscoveredCeilingPlan.gpuPowerLevelForKhz(available, snapped)
        val slowest = DiscoveredCeilingPlan.slowestGpuPowerLevel(available, level)

        val prior = heldRecord()
        val base = prior ?: DiscoveredAxisRecord(bootIdentity = bootIdentity(), cpuStockRow = "", cpuRequestedRow = "")
        val withStock = if (base.gpuStockMaxLevel == DiscoveredAxisRecord.ABSENT_LEVEL) {
            val liveMin = nodes.read(HardwareProbe.GPU_MIN_LEVEL)?.trim()?.toIntOrNull()
            val liveMax = nodes.read(HardwareProbe.GPU_MAX_LEVEL)?.trim()?.toIntOrNull()
            if (liveMin == null || liveMax == null) {
                return refused(
                    TweakerDiscoveredCeilingContract.REFUSAL_HOST_UNAVAILABLE,
                    "the Adreno power-level bounds are unreadable, so no stock could be captured",
                )
            }
            base.copy(gpuStockMinLevel = liveMin, gpuStockMaxLevel = liveMax)
        } else {
            base
        }
        val intent = withStock.copy(gpuRequestedMinLevel = slowest, gpuRequestedMaxLevel = level)
        persist(intent)?.let { return it }

        // min_pwrlevel is widened FIRST. The kernel clamps max_pwrlevel so it can never name a
        // slower level than min_pwrlevel, so a ceiling written while the floor sits above it snaps
        // back silently and the surface reports a change that never happened.
        val delivered = deliver(DiscoveredCeilingPlan.gpuCeilingCommands(slowest, level))
        if (delivered != null) return refuseUndelivered(prior, delivered)
        val liveMaxAfter = nodes.read(HardwareProbe.GPU_MAX_LEVEL)?.trim()?.toIntOrNull()
        if (liveMaxAfter == null) {
            // Confirmed write, unreadable readback: nothing proves the ceiling did not land, so the record stays.
            return refused(
                TweakerDiscoveredCeilingContract.REFUSAL_WRITE_NOT_HELD,
                "UNCONFIRMED, the intent record is kept: max_pwrlevel readback was unreadable",
            )
        }
        if (liveMaxAfter != level) {
            // The min write was confirmed and goes first, so a max that did not take says nothing about min. The
            // record may be rolled back only when min is READ and still where it was before this apply; a min that
            // moved, or cannot be read, may be our write, and the intent record is then the only way back.
            val minBefore = prior?.gpuRequestedMinLevel?.takeIf { it != DiscoveredAxisRecord.ABSENT_LEVEL }
                ?: withStock.gpuStockMinLevel
            val liveMinAfter = nodes.read(HardwareProbe.GPU_MIN_LEVEL)?.trim()?.toIntOrNull()
            if (liveMinAfter == null || liveMinAfter != minBefore) {
                return refused(
                    TweakerDiscoveredCeilingContract.REFUSAL_WRITE_NOT_HELD,
                    "UNCONFIRMED, the intent record is kept: max_pwrlevel is $liveMaxAfter, not $level, " +
                        "and min_pwrlevel is ${liveMinAfter ?: "unreadable"}, not the $minBefore it was",
                )
            }
            rollbackRecord(prior)
            return refused(
                TweakerDiscoveredCeilingContract.REFUSAL_WRITE_NOT_HELD,
                "max_pwrlevel is $liveMaxAfter, not $level",
            )
        }
        val inForceKhz = DiscoveredCeilingPlan.gpuKhzForPowerLevel(available, liveMaxAfter)
            ?: TweakerDiscoveredCeilingContract.UNREADABLE_KHZ
        persist(intent)?.let { return it }
        return CeilingOutcome.Applied(
            axis = TweakerDiscoveredCeilingContract.AXIS_GPU,
            policyId = TweakerDiscoveredCeilingContract.GPU_POLICY_ID,
            requestedKhz = ceilingKhz,
            admittedKhz = snapped,
            inForceKhz = inForceKhz,
            record = intent,
        )
    }

    /** Give back exactly what was captured, on the CPUs and levels still holding this daemon's value. */
    fun withdraw(): CeilingOutcome = synchronized(stateLock) {
        val outcome = withdrawInternal()
        log(
            "DISCOVERED_CEILING withdraw restored=${(outcome as? CeilingOutcome.Withdrawn)?.restoredCpus ?: 0} " +
                "leftAlone=${(outcome as? CeilingOutcome.Withdrawn)?.leftAloneCpus ?: 0} " +
                "gpu=${(outcome as? CeilingOutcome.Withdrawn)?.gpuRestored ?: false} " +
                "outcome=${outcome.wireOutcome()}${outcome.refusalSuffix()}",
        )
        // A withdraw that could not be delivered has given nothing back, so the cap is still held
        // and still needs its watcher: the keeper follows the record here too, and comes back.
        syncKeeperToRecord()
        outcome
    }

    private fun withdrawInternal(): CeilingOutcome {
        // FIRST, ahead of every read below. The keeper's whole job is to put our value back on a
        // CPU that drifted; a withdraw that read the live row while the keeper was still ticking
        // would be comparing against a row being rewritten underneath it, and the ours-check would
        // then decide ownership from a value the keeper had just re-asserted a millisecond ago.
        keeper.stop()
        val record = when (val view = recordView()) {
            RecordView.Absent -> return CeilingOutcome.NothingHeld
            is RecordView.OtherBoot -> {
                // A record from another boot describes a machine that is not this one; the cap it names
                // died with the reboot. Clear it rather than serving it, and say NothingHeld only once the store reads
                // back empty: a failed delete (which may be a spent record still owed one) is never taken for success.
                if (!deleteRecordConfirmed()) {
                    return refused(
                        TweakerDiscoveredCeilingContract.REFUSAL_STORE_UNAVAILABLE,
                        "UNCONFIRMED, a ceiling record from another boot could not be deleted; withdraw again to finish",
                    )
                }
                return CeilingOutcome.NothingHeld
            }
            // A record that exists but cannot be read is NOT "nothing held": the cap may be on the node and this is
            // the only memory of the way back. Nothing is cleared and nothing is claimed.
            // KNOWN LIMIT: a permanently unreadable record is never cleaned up here, so it stays refused.
            is RecordView.Unreadable -> return refused(
                TweakerDiscoveredCeilingContract.REFUSAL_STORE_UNAVAILABLE,
                "UNCONFIRMED, the ceiling record could not be read (${view.detail}); it is left in place",
            )
            is RecordView.Held -> view.record
        }
        // A pending-delete record's hardware was already restored and read back; only its delete is owed. It is never
        // re-planned (that could re-restore a value another writer now owns): the withdraw just retries the delete.
        if (record.isSpent()) {
            if (!deleteRecordConfirmed()) {
                return refused(
                    TweakerDiscoveredCeilingContract.REFUSAL_STORE_UNAVAILABLE,
                    "UNCONFIRMED, the hardware was already given back but the ceiling record still cannot be deleted; " +
                        "the keeper is stopped and will not re-cap; withdraw again to finish",
                )
            }
            restoreAwaitingRecordDelete = false
            return CeilingOutcome.Withdrawn(restoredCpus = 0, leftAloneCpus = 0, gpuRestored = false)
        }

        // The Adreno analog of the stock-coverage rule: a requested bound with no stock beside it would be skipped by the
        // restore and the record cleared with the bound still narrowed.
        if (
            (record.gpuRequestedMaxLevel != DiscoveredAxisRecord.ABSENT_LEVEL &&
                record.gpuStockMaxLevel == DiscoveredAxisRecord.ABSENT_LEVEL) ||
            (record.gpuRequestedMinLevel != DiscoveredAxisRecord.ABSENT_LEVEL &&
                record.gpuStockMinLevel == DiscoveredAxisRecord.ABSENT_LEVEL)
        ) {
            return refused(
                TweakerDiscoveredCeilingContract.REFUSAL_HOST_UNAVAILABLE,
                "UNCONFIRMED, the intent record is kept: it names an Adreno bound with no stock to put back",
            )
        }
        // READ everything the record names before WRITING anything, so a refusal for evidence we cannot read leaves
        // the node exactly as it was found instead of half restored.
        val gpuBounds = readGpuBounds(record)
        if (gpuBounds is GpuBounds.Unreadable) {
            return refused(
                TweakerDiscoveredCeilingContract.REFUSAL_HOST_UNAVAILABLE,
                "UNCONFIRMED, the intent record is kept: the Adreno power-level bounds could not be read",
            )
        }
        val requested = DiscoveredCeilingPlan.parseCpuQosRow(record.cpuRequestedRow)
        val stock = DiscoveredCeilingPlan.parseCpuQosRow(record.cpuStockRow)
        // A requested CPU the record holds no stock for cannot be put back, and skipping it would clear the record with
        // that CPU still capped. Admission refuses such a record now; one written before that is refused here, unchanged.
        val uncovered = DiscoveredCeilingPlan.cpusWithoutStock(requested.keys, stock)
        if (uncovered.isNotEmpty()) {
            return refused(
                TweakerDiscoveredCeilingContract.REFUSAL_HOST_UNAVAILABLE,
                "UNCONFIRMED, the intent record is kept: it holds no stock for cpu " +
                    DiscoveredCeilingPlan.formatCpuIds(uncovered),
            )
        }
        val live: Map<Int, Int> = if (requested.isEmpty()) {
            emptyMap()
        } else {
            // Strict, and it must name every CPU the record asked for. A row the parser would silently trim, or one
            // missing a CPU of ours, does not show that CPU stopped holding the cap: unreadable is not "not ours".
            DiscoveredCeilingPlan.parseCpuQosRowStrict(nodes.read(HardwareProbe.CPU_QOS_MAX_PATH))
                ?.takeIf { row -> requested.keys.all { it in row } }
                ?: return refused(
                    TweakerDiscoveredCeilingContract.REFUSAL_HOST_UNAVAILABLE,
                    "UNCONFIRMED, the intent record is kept: the live CPU QoS row is unreadable, malformed or incomplete",
                )
        }
        val plan = DiscoveredCeilingPlan.cpuRestorePlan(live = live, requested = requested, stock = stock)
        val gpuPlan = (gpuBounds as? GpuBounds.Read)?.let { gpuRestorePlan(record, it) }

        // The GPU is restored FIRST and on its own terms: a different node, a different unit and a
        // different owner, and folding it into the CPU row's early return is how it came to have no
        // restore at all in the frontend.
        if (gpuPlan != null && (gpuPlan.restoreMin || gpuPlan.restoreMax)) {
            deliver(
                DiscoveredCeilingPlan.gpuRestoreCommandsFor(
                    stockMinLevel = record.gpuStockMinLevel,
                    stockMaxLevel = record.gpuStockMaxLevel,
                    restoreMin = gpuPlan.restoreMin,
                    restoreMax = gpuPlan.restoreMax,
                ),
            )?.let { return it.refusal } // the record stays: a withdraw that could not be delivered gave nothing back
        }
        if (plan.restore.isNotEmpty()) {
            deliver(DiscoveredCeilingPlan.cpuRestoreCommands(plan.restore))?.let {
                // Keep the record: the way back is the only thing that survives, and a withdraw
                // that could not be delivered has not given anything back.
                return it.refusal
            }
        }

        // READ BACK before the record goes. A restore command that was accepted is not a restore that held: the kernel
        // may clamp it, and another writer may re-assert the cap in the same instant. Clearing here without looking
        // would report success while the cap stays, and the next profile would capture it as stock.
        verifyGpuRestored(record, gpuPlan)?.let { return it }
        verifyCpuRestored(plan.restore)?.let { return it }

        // Withdrawn is claimed only once the record is READ BACK as gone. A delete that fails (or that nobody can confirm)
        // leaves a record saying "held" beside hardware that is already restored, and the keeper would re-cap it.
        if (!deleteRecordConfirmed()) return restoredButRecordUndeletable(record)
        restoreAwaitingRecordDelete = false
        return CeilingOutcome.Withdrawn(
            restoredCpus = plan.restore.size,
            leftAloneCpus = plan.leftAlone.size,
            gpuRestored = gpuPlan != null && (gpuPlan.restoreMin || gpuPlan.restoreMax),
        )
    }

    private sealed interface GpuBounds {
        /** The record names no Adreno session. */
        data object NotRecorded : GpuBounds

        /** The record names one and a live bound cannot be read. */
        data object Unreadable : GpuBounds
        data class Read(val min: Int, val max: Int) : GpuBounds
    }

    private fun readGpuBounds(record: DiscoveredAxisRecord): GpuBounds {
        if (
            record.gpuRequestedMaxLevel == DiscoveredAxisRecord.ABSENT_LEVEL ||
            record.gpuStockMaxLevel == DiscoveredAxisRecord.ABSENT_LEVEL
        ) {
            return GpuBounds.NotRecorded
        }
        val liveMin = nodes.read(HardwareProbe.GPU_MIN_LEVEL)?.trim()?.toIntOrNull()
        val liveMax = nodes.read(HardwareProbe.GPU_MAX_LEVEL)?.trim()?.toIntOrNull()
        // A bound that cannot be read is not a bound somebody else moved.
        if (liveMin == null || liveMax == null) return GpuBounds.Unreadable
        return GpuBounds.Read(liveMin, liveMax)
    }

    /** Which Adreno bounds still hold the value this daemon wrote, and so are ours to put back. */
    private class GpuRestorePlan(val restoreMin: Boolean, val restoreMax: Boolean)

    /**
     * Each bound is judged on its OWN. The apply writes min first and then max, so a half-delivered apply (min landed,
     * max did not) leaves a pair that matches our request on one bound only; judging the pair as a unit called that
     * "somebody else's" and cleared the record with min still widened. A bound is ours when it still holds what we
     * wrote; one that holds anything else is somebody else's, or already stock, and is left alone.
     */
    private fun gpuRestorePlan(record: DiscoveredAxisRecord, live: GpuBounds.Read): GpuRestorePlan = GpuRestorePlan(
        restoreMin = record.gpuRequestedMinLevel != DiscoveredAxisRecord.ABSENT_LEVEL &&
            record.gpuStockMinLevel != DiscoveredAxisRecord.ABSENT_LEVEL &&
            live.min == record.gpuRequestedMinLevel,
        restoreMax = live.max == record.gpuRequestedMaxLevel,
    )

    /** Null when every bound we put back reads back at stock; an UNCONFIRMED refusal otherwise (the record stays). */
    private fun verifyGpuRestored(record: DiscoveredAxisRecord, plan: GpuRestorePlan?): CeilingOutcome.Refused? {
        if (plan == null || (!plan.restoreMin && !plan.restoreMax)) return null
        val liveMin = nodes.read(HardwareProbe.GPU_MIN_LEVEL)?.trim()?.toIntOrNull()
        val liveMax = nodes.read(HardwareProbe.GPU_MAX_LEVEL)?.trim()?.toIntOrNull()
        val minHeld = plan.restoreMin && liveMin != record.gpuStockMinLevel
        val maxHeld = plan.restoreMax && liveMax != record.gpuStockMaxLevel
        if (!minHeld && !maxHeld) return null
        return refused(
            TweakerDiscoveredCeilingContract.REFUSAL_HOST_UNAVAILABLE,
            "UNCONFIRMED, the intent record is kept: the Adreno bounds read " +
                "min=${liveMin ?: "unreadable"} max=${liveMax ?: "unreadable"} after the restore, " +
                "not stock min=${record.gpuStockMinLevel} max=${record.gpuStockMaxLevel}",
        )
    }

    /** Null when every CPU we put back reads back at its stock value; an UNCONFIRMED refusal otherwise (the record stays). */
    private fun verifyCpuRestored(restored: Map<Int, Int>): CeilingOutcome.Refused? {
        if (restored.isEmpty()) return null
        val after = DiscoveredCeilingPlan.parseCpuQosRowStrict(nodes.read(HardwareProbe.CPU_QOS_MAX_PATH))
        val notBack = restored.keys.sorted().filter { cpuId -> after?.get(cpuId) != restored.getValue(cpuId) }
        if (notBack.isEmpty()) return null
        return refused(
            TweakerDiscoveredCeilingContract.REFUSAL_HOST_UNAVAILABLE,
            "UNCONFIRMED, the intent record is kept: after the restore cpu " +
                "${DiscoveredCeilingPlan.formatCpuIds(notBack)} did not read back at stock",
        )
    }


    /**
     * Delete the record and READ BACK that it is gone. A `clear()` that answers true is not proof: the store is re-read,
     * and only an authoritative "Absent" counts. An exception, a false, or a record still there is not deleted.
     */
    private fun deleteRecordConfirmed(): Boolean {
        val cleared = runCatching { records.clear() }.getOrDefault(false)
        return cleared && recordView() is RecordView.Absent
    }

    /**
     * The hardware has been restored and read back at stock, and the record that remembers the cap could not be
     * deleted. FAIL-CLOSED outcome: the withdraw is REFUSED (never `Withdrawn`), and the keeper is told to stand down,
     * because a surviving record that still says "held" would otherwise restart the keeper on the next sync and re-cap
     * the very hardware that was just given back.
     *
     * The record is KEPT, with its restoration data, and re-written with a durable pending-delete marker ([isSpent]), so a
     * daemon restart that resumes from the file finds a record that is owed a delete and nothing to re-assert, and every
     * path (keeper, resume, query, apply, withdraw) honours the marker from disk. The data is deliberately not emptied: a
     * failed delete must never cost the only memory of the way back. If even the marker write fails, [restoreAwaitingRecordDelete]
     * keeps the keeper off, and makes apply and query retry the delete first, for THIS process only. The marker counts as
     * written only once it is READ BACK through the store (an AtomicFile commit can fail without throwing, see
     * [writeConfirmed]); a marker that reads back absent or different is the same failure as one that threw. That double failure
     * (delete AND marker write) across a daemon restart is the pre-existing residual this cannot close, because nothing
     * durable says the restore finished. The next withdraw (or query) retries the delete on its own.
     */
    private fun restoredButRecordUndeletable(record: DiscoveredAxisRecord): CeilingOutcome.Refused {
        keeper.stop()
        restoreAwaitingRecordDelete = true
        val marked = writeConfirmed(record.copy(pendingDelete = true))
        return refused(
            TweakerDiscoveredCeilingContract.REFUSAL_STORE_UNAVAILABLE,
            "UNCONFIRMED, the hardware read back at stock but the ceiling record could not be deleted; " +
                "the keeper is stopped and will not re-cap" +
                (if (marked) " (the record is marked spent and kept until it can be deleted)" else "") +
                "; withdraw again to finish",
        )
    }

    /**
     * Write [record] and READ IT BACK through the store: true only when the store answers Present with exactly this record
     * (marker and restoration data both). Android's AtomicFile can fail a commit without throwing, so an `Admitted` answer
     * proves nothing about the disk; a write that threw, was refused, or reads back absent, unreadable or different is false.
     */
    private fun writeConfirmed(record: DiscoveredAxisRecord): Boolean {
        if (runCatching { records.write(record) }.getOrNull() != DiscoveredAxisAdmissionResult.Admitted) return false
        val back = runCatching { records.read() }.getOrNull()
        return back is DiscoveredAxisRecordRead.Present && back.record == record
    }

    /**
     * A record that is owed its delete: the durable pending-delete marker, or (older records) one that asks for nothing.
     * It describes hardware that was already given back, so it is never a held ceiling.
     */
    private fun DiscoveredAxisRecord.isSpent(): Boolean =
        pendingDelete ||
            (cpuRequestedRow.isBlank() &&
                gpuRequestedMinLevel == DiscoveredAxisRecord.ABSENT_LEVEL &&
                gpuRequestedMaxLevel == DiscoveredAxisRecord.ABSENT_LEVEL)

    /**
     * What this daemon is holding for THIS boot, and whether it is actually IN FORCE.
     *
     * The record is a claim about hardware, and this node has a second writer, so answering HELD
     * from the record alone is how a cap that evaporated forty seconds ago keeps being reported as
     * held. The row is READ and compared; if no requested value is on the node the answer is still
     * HELD — the record is the only way back and a transient miss must never delete it — but it is
     * HELD with `inForce=false`, so the frontend can say so instead of drawing a cap that is not there.
     *
     * **A query never writes hardware.** It used to repair a drifted cap before answering, which made a read a write
     * on a Binder thread with no transaction around it. Re-asserting is the keeper's job alone ([keeperTick]), on the
     * transaction worker, inside the repair budget. A row that cannot be read as a complete row is not "in force"
     * either: unreadable is never a claim.
     */
    fun query(): CeilingOutcome = synchronized(stateLock) {
        val outcome = queryInternal()
        log(
            "DISCOVERED_CEILING query outcome=${outcome.wireOutcome()} " +
                "inForce=${(outcome as? CeilingOutcome.Held)?.inForce ?: false}",
        )
        outcome
    }

    private fun queryInternal(): CeilingOutcome {
        val stored = when (val view = recordView()) {
            RecordView.Absent -> {
                keeper.stop()
                return CeilingOutcome.NotHeld
            }
            is RecordView.OtherBoot -> {
                keeper.stop()
                // NotHeld only once the store reads back empty; a failed delete is not "nothing held".
                if (!deleteRecordConfirmed()) {
                    return refused(
                        TweakerDiscoveredCeilingContract.REFUSAL_STORE_UNAVAILABLE,
                        "UNCONFIRMED, a ceiling record from another boot could not be deleted",
                    )
                }
                return CeilingOutcome.NotHeld
            }
            // "I cannot read my record" is not "I hold nothing": answering NotHeld would tell the surface no cap is
            // held while one may be. The keeper is left as it is (it cannot be told what to watch without the
            // record, but stopping it on a transient read failure would abandon a cap it is holding).
            is RecordView.Unreadable -> return refused(
                TweakerDiscoveredCeilingContract.REFUSAL_STORE_UNAVAILABLE,
                "UNCONFIRMED, the ceiling record could not be read (${view.detail})",
            )
            is RecordView.Held -> view.record
        }
        // A record that asks for nothing (or one whose delete a previous withdraw could not finish) is not a held
        // ceiling: finish the delete, and say NotHeld only once the store is read back as empty.
        if (stored.isSpent() || restoreAwaitingRecordDelete) {
            keeper.stop()
            if (!deleteRecordConfirmed()) {
                return refused(
                    TweakerDiscoveredCeilingContract.REFUSAL_STORE_UNAVAILABLE,
                    "UNCONFIRMED, the ceiling was given back but its record could not be deleted",
                )
            }
            restoreAwaitingRecordDelete = false
            return CeilingOutcome.NotHeld
        }
        return CeilingOutcome.Held(stored, inForce = anyRequestedValueInForce(stored))
    }

    // -------------------------------------------------------------------------------------------
    // The keeper
    // -------------------------------------------------------------------------------------------

    /**
     * One keeper tick. Called on the daemon's transaction worker, once a second, while held.
     *
     * A record that is gone (withdrawn, cleared, or from another boot) stops the tick rather than
     * repairing anything: the keeper never outlives the record it watches.
     */
    fun keeperTick() {
        synchronized(stateLock) {
            val view = recordView()
            // A record that cannot be read right now is not a record that is gone: this tick has nothing to repair
            // FROM, but stopping the watcher would abandon a cap it may be holding. The next tick tries again.
            if (view is RecordView.Unreadable) {
                // One tick per second for as long as the store stays broken: log the transition, then only now and then.
                unreadableRecordTicks++
                if (unreadableRecordTicks == 1 || unreadableRecordTicks % UNREADABLE_LOG_EVERY == 0) {
                    log(
                        "DISCOVERED_CEILING keeper tick skipped reason=record_unreadable detail=${view.detail} " +
                            "ticks=$unreadableRecordTicks",
                    )
                }
                return
            }
            if (unreadableRecordTicks > 0) {
                log("DISCOVERED_CEILING keeper record readable again after ticks=$unreadableRecordTicks")
                unreadableRecordTicks = 0
            }
            val record = (view as? RecordView.Held)?.record
            // A record that asks for nothing, or one whose delete is still owed, has nothing to keep: its hardware was
            // given back already, and re-asserting from it would re-cap what a withdraw just restored.
            if (record == null || record.isSpent() || restoreAwaitingRecordDelete) {
                keeper.stop()
                return
            }
            repairDrift(record)
        }
    }

    /**
     * Re-assert exactly this record's values on exactly the axes that drifted, or spend nothing.
     *
     * Only OUR CPUs, only our value, one `cpuId:kHz` command each, exactly as apply writes them —
     * a CPU outside `cpuRequestedRow` is another writer's business and is never named. The budget
     * is checked only once drift is real, so an undrifted tick costs nothing and a quiet hour does
     * not use up the window a real vendor reset will need.
     */
    private fun repairDrift(record: DiscoveredAxisRecord) {
        // Posture first, exactly as apply does. The keeper is never started on a read-only build,
        // but [query] reaches this WITHOUT passing through apply, and a build that cannot write
        // hardware must not deliver a repair through the back door of a read.
        if (!hardwareWritesCompiled) return
        val requested = DiscoveredCeilingPlan.parseCpuQosRow(record.cpuRequestedRow)
        // Strict, and it must name every CPU we asked for. A row the lenient parser would trim, or one missing a CPU of
        // ours, is not a reading of this node: it shows no drift, so it triggers no re-assertion. Unreadable is unreadable,
        // never "that CPU drifted".
        val live: Map<Int, Int>? = if (requested.isEmpty()) {
            emptyMap()
        } else {
            DiscoveredCeilingPlan.parseCpuQosRowStrict(nodes.read(HardwareProbe.CPU_QOS_MAX_PATH))
                ?.takeIf { row -> requested.keys.all { it in row } }
        }
        if (live == null) {
            unreadableCpuRowTicks++
            if (unreadableCpuRowTicks == 1 || unreadableCpuRowTicks % UNREADABLE_LOG_EVERY == 0) {
                log(
                    "DISCOVERED_CEILING repair skipped reason=cpu_row_unreadable ticks=$unreadableCpuRowTicks",
                )
            }
        } else {
            unreadableCpuRowTicks = 0
        }
        val driftedCpus = if (live == null) {
            emptyList()
        } else {
            DiscoveredCeilingPlan.driftedCpus(live = live, requested = requested)
        }
        val gpuDrifted = gpuCeilingOpened(record)
        if (driftedCpus.isEmpty() && !gpuDrifted) return
        if (!admitRepair(clock.elapsedRealtimeMs())) {
            // Exhaustion degrades honestly: nothing this tick, no restore, no cleared record. The
            // window rolls forward on its own and a later tick tries again.
            log("DISCOVERED_CEILING repair deferred reason=budget")
            return
        }
        log(
            "DISCOVERED_CEILING repair cpus=${DiscoveredCeilingPlan.formatCpuIds(driftedCpus)} " +
                "gpu=$gpuDrifted reason=drift",
        )
        if (driftedCpus.isNotEmpty()) {
            deliver(
                DiscoveredCeilingPlan.cpuRepairCommands(driftedCpus.associateWith { requested.getValue(it) }),
            )
        }
        if (gpuDrifted) {
            // Same order as apply, for the same kernel clamp: min widened first or the ceiling
            // snaps straight back to whatever the floor allows.
            deliver(
                DiscoveredCeilingPlan.gpuCeilingCommands(
                    record.gpuRequestedMinLevel,
                    record.gpuRequestedMaxLevel,
                ),
            )
        }
    }

    /**
     * Start the keeper for a held record, or stop it when nothing is held.
     *
     * Deliberately does NOT consult [session]: apply refuses while any session exists and the
     * precedence path withdraws ahead of one, so reaching a start here means no session — and
     * `withdrawBeforeProfileApply` must stay session-blind for the reason its own KDoc gives.
     * [resumeIfHeld], which runs at daemon start with no such context, does check.
     */
    private fun syncKeeperToRecord() {
        when (val view = recordView()) {
            // A spent record, or one whose delete is still owed, describes hardware that was already given back: the
            // keeper must not follow it, or it re-caps what the withdraw just restored.
            is RecordView.Held ->
                if (hardwareWritesCompiled && !view.record.isSpent() && !restoreAwaitingRecordDelete) {
                    keeper.start(::keeperTick)
                } else {
                    keeper.stop()
                }
            // Unreadable is neither "held" nor "gone": leave the watcher exactly as it is.
            is RecordView.Unreadable -> Unit
            RecordView.Absent, is RecordView.OtherBoot -> keeper.stop()
        }
    }

    /**
     * A daemon restart must not orphan a held ceiling.
     *
     * The record outlives this process; the keeper does not. Without this, a daemon that restarted
     * while a cap was held would leave the cap to the next vendor reset with nobody watching, and
     * the record would keep saying HELD.
     */
    fun resumeIfHeld() {
        synchronized(stateLock) {
            if (!hardwareWritesCompiled) return
            // At process start there is no apply above us to have refused a session, so this is the
            // one place the mutual exclusion has to be checked rather than inherited.
            if (session.activeProfileStateOrNull() != null) return
            val record = heldRecord() ?: return
            if (record.isSpent() || restoreAwaitingRecordDelete) return
            // Device evidence (Thor, 2026-09-11, 09:02:42.669 / 42.954 / 43.408 on three tids
            // inside one second): this runs more than once per process, and [keeper.start] is
            // idempotent by contract, so an unconditional line reports two starts that never
            // happened. The line belongs to the stopped -> running TRANSITION, not to the call.
            val wasRunning = keeper.isRunning()
            keeper.start(::keeperTick)
            if (!wasRunning) {
                log(
                    "DISCOVERED_CEILING keeper resumed cpus=" +
                        DiscoveredCeilingPlan.formatCpuIds(
                            DiscoveredCeilingPlan.parseCpuQosRow(record.cpuRequestedRow).keys.sorted(),
                        ) +
                        " gpu=${record.gpuRequestedMaxLevel != DiscoveredAxisRecord.ABSENT_LEVEL}",
                )
            }
        }
    }

    /** Is any value this record asked for actually on the node right now? */
    private fun anyRequestedValueInForce(record: DiscoveredAxisRecord): Boolean {
        val requested = DiscoveredCeilingPlan.parseCpuQosRow(record.cpuRequestedRow)
        if (requested.isNotEmpty()) {
            // Strict: a row the lenient parser would trim is not a reading, so it cannot support an "in force" claim.
            val live = DiscoveredCeilingPlan.parseCpuQosRowStrict(nodes.read(HardwareProbe.CPU_QOS_MAX_PATH))
            if (live != null && DiscoveredCeilingPlan.anyCpuRequestedInForce(live = live, requested = requested)) {
                return true
            }
        }
        if (record.gpuRequestedMaxLevel != DiscoveredAxisRecord.ABSENT_LEVEL) {
            if (nodes.read(HardwareProbe.GPU_MAX_LEVEL)?.trim()?.toIntOrNull() == record.gpuRequestedMaxLevel) {
                return true
            }
        }
        return false
    }

    /**
     * Has another writer opened the Adreno bounds back up past ours?
     *
     * Both levels must have moved, and both in the widening direction, before the keeper touches
     * them. One level differing is somebody mid-write or somebody who owns exactly one bound, and
     * stamping our pair over a half-state is the daemon fighting a writer it cannot see.
     */
    private fun gpuCeilingOpened(record: DiscoveredAxisRecord): Boolean {
        if (
            record.gpuRequestedMaxLevel == DiscoveredAxisRecord.ABSENT_LEVEL ||
            record.gpuRequestedMinLevel == DiscoveredAxisRecord.ABSENT_LEVEL
        ) {
            return false
        }
        return DiscoveredCeilingPlan.gpuBoundsOpened(
            liveMinLevel = nodes.read(HardwareProbe.GPU_MIN_LEVEL)?.trim()?.toIntOrNull(),
            liveMaxLevel = nodes.read(HardwareProbe.GPU_MAX_LEVEL)?.trim()?.toIntOrNull(),
            requestedMinLevel = record.gpuRequestedMinLevel,
            requestedMaxLevel = record.gpuRequestedMaxLevel,
        )
    }

    /**
     * The supervisor's `admitTargetRepair` shape, value for value: at most
     * [MAX_REPAIRS_PER_WINDOW] in [REPAIR_WINDOW_MS] and [MAX_REPAIRS_PER_BURST] in
     * [REPAIR_BURST_WINDOW_MS]. Exhaustion returns false and the caller does nothing — it never
     * escalates, because there is nothing above a ceiling to escalate to and abandoning the
     * player's cap is not a safety measure.
     */
    private fun admitRepair(nowElapsedRealtimeMs: Long): Boolean {
        val cutoff = nowElapsedRealtimeMs - REPAIR_WINDOW_MS
        while (repairAttempts.isNotEmpty() && repairAttempts.first() <= cutoff) {
            repairAttempts.removeFirst()
        }
        val burstCutoff = nowElapsedRealtimeMs - REPAIR_BURST_WINDOW_MS
        val burstAttempts = repairAttempts.count { it > burstCutoff }
        if (repairAttempts.size >= MAX_REPAIRS_PER_WINDOW || burstAttempts >= MAX_REPAIRS_PER_BURST) {
            return false
        }
        repairAttempts.addLast(nowElapsedRealtimeMs)
        return true
    }

    /**
     * Withdraw a held ceiling immediately before a named profile is applied over it.
     *
     * Deliberately does NOT consult [session]: this runs at the entry of an apply path, where a
     * session is exactly what is being created. A profile that captured our cap as its stock would
     * restore to the cap forever after, which is the "restore that succeeds at restoring the wrong
     * value" this family exists to prevent.
     */
    fun withdrawBeforeProfileApply(): CeilingPrecedence {
        synchronized(stateLock) {
            when (val view = recordView()) {
                RecordView.Absent -> {
                    keeper.stop()
                    return CeilingPrecedence.NothingHeld
                }
                // An earlier boot's SPENT record still owes its confirmed delete: it goes through the withdraw below, which
                // refuses while the delete cannot be confirmed. Any other earlier-boot record is stale and unchanged here.
                is RecordView.OtherBoot -> if (!view.record.isSpent()) {
                    keeper.stop()
                    return CeilingPrecedence.NothingHeld
                }
                // The profile apply must NOT proceed on "I could not read my record": a held cap would be captured as
                // that profile's stock. The keeper is left running as it is.
                is RecordView.Unreadable -> return CeilingPrecedence.Unreadable(
                    "the ceiling record could not be read (${view.detail})",
                )
                is RecordView.Held -> Unit
            }
            val outcome = withdrawInternal()
            // Blocked keeps the record, so the keeper comes back — and the profile apply this ran
            // ahead of is refused (CEILING_HELD), so no session is ever created next to it.
            syncKeeperToRecord()
            return when (outcome) {
                is CeilingOutcome.Withdrawn -> {
                    log(
                        "DISCOVERED_CEILING withdrawn before profile apply restored=${outcome.restoredCpus} " +
                            "leftAlone=${outcome.leftAloneCpus} gpu=${outcome.gpuRestored}",
                    )
                    CeilingPrecedence.Withdrawn
                }
                CeilingOutcome.NothingHeld -> CeilingPrecedence.NothingHeld
                // The hardware was given back (or the record was spent already) and only the record's DELETE could not be
                // confirmed: no ceiling is held, so this is not CEILING_HELD. It is still a refusal, because nothing
                // proves the store is clean: the profile is not applied, and the answer is "could not confirm".
                is CeilingOutcome.Refused ->
                    if (outcome.code == TweakerDiscoveredCeilingContract.REFUSAL_STORE_UNAVAILABLE) {
                        CeilingPrecedence.Unreadable(outcome.detail)
                    } else {
                        CeilingPrecedence.Blocked(outcome.detail)
                    }
                else -> CeilingPrecedence.Blocked(
                    (outcome as? CeilingOutcome.Refused)?.detail
                        ?: "the discovered ceiling could not be withdrawn",
                )
            }
        }
    }

    /**
     * What a named-profile apply must see, read-only, at the instant it is about to capture stock: is any ceiling
     * recorded? Called on the transaction worker inside the apply's own block, where a ceiling apply cannot interleave,
     * so this is the "use" half of the check/use that [withdrawBeforeProfileApply] opened. It never writes and never
     * touches the keeper: a held ceiling here means one was applied after the withdraw ran, and the profile is refused.
     */
    fun recordedCeilingBeforeCapture(): CeilingPrecedence = synchronized(stateLock) {
        when (val view = recordView()) {
            RecordView.Absent -> CeilingPrecedence.NothingHeld
            is RecordView.OtherBoot ->
                if (view.record.isSpent()) {
                    // A spent record from an earlier boot is still owed its delete: not a held ceiling, still a refusal.
                    CeilingPrecedence.Unreadable("a previous ceiling was given back but its record could not be deleted")
                } else {
                    CeilingPrecedence.NothingHeld
                }
            is RecordView.Unreadable -> CeilingPrecedence.Unreadable(
                "the ceiling record could not be read (${view.detail})",
            )
            is RecordView.Held ->
                if (view.record.isSpent()) {
                    // Nothing is held: only its delete is owed. Not CEILING_HELD, and still a refusal.
                    CeilingPrecedence.Unreadable("a previous ceiling was given back but its record could not be deleted")
                } else {
                    CeilingPrecedence.Blocked("a discovered ceiling was applied after the precedence check and is held")
                }
        }
    }

    /** The held record for THIS boot, or null when there is none OR it cannot be read: callers that must tell those apart use [recordView]. */
    private fun heldRecord(): DiscoveredAxisRecord? = (recordView() as? RecordView.Held)?.record

    /**
     * What the store says, with "no record" and "a record I could not read" kept apart. Collapsing them (the store used
     * to answer null for both) is how an unreadable record became "nothing held" and was cleared.
     */
    private sealed interface RecordView {
        /** Nothing is stored. */
        data object Absent : RecordView

        /**
         * A record from another boot: it describes a machine that is not this one. It carries the record so a spent one
         * (a pending-delete marker survives a reboot) is still seen to owe its delete.
         */
        data class OtherBoot(val record: DiscoveredAxisRecord) : RecordView
        data class Held(val record: DiscoveredAxisRecord) : RecordView

        /** Something is stored (or may be) and could not be read, decoded or re-admitted. */
        data class Unreadable(val detail: String) : RecordView
    }

    private fun recordView(): RecordView {
        val read = try {
            records.read()
        } catch (error: Throwable) {
            return RecordView.Unreadable(error.javaClass.simpleName)
        }
        return when (read) {
            DiscoveredAxisRecordRead.Absent -> {
                // The store reads back empty: whatever delete was owed is done.
                restoreAwaitingRecordDelete = false
                RecordView.Absent
            }
            is DiscoveredAxisRecordRead.Unreadable -> RecordView.Unreadable(read.detail)
            is DiscoveredAxisRecordRead.Present ->
                if (read.record.bootIdentity == bootIdentity()) RecordView.Held(read.record) else RecordView.OtherBoot(read.record)
        }
    }

    /** Does this view's record owe a confirmed delete (spent), whichever boot wrote it? */
    private fun RecordView.owesDelete(): Boolean = when (this) {
        is RecordView.Held -> record.isSpent()
        is RecordView.OtherBoot -> record.isSpent()
        RecordView.Absent, is RecordView.Unreadable -> false
    }

    /** Every CPU this device published, mapped to the ceiling ITS OWN cluster published. */
    private fun publishedCpuCeilings(): Map<Int, Int> = buildMap {
        discovery.cpuPolicies().forEach { policy ->
            val ceiling = policy.availableKhz.maxOrNull() ?: return@forEach
            policy.cpuIds.forEach { cpuId -> put(cpuId, ceiling) }
        }
    }

    /**
     * A delivery that did not complete. [mayHaveLanded] is false ONLY when nothing of it can be on the node: the very
     * first command was never sent or ran and reported failure. A command that threw, or came back AMBIGUOUS, may have
     * run; and any failure after an earlier command was CONFIRMED leaves that earlier write in force. In those cases the
     * ceiling is possibly applied and its record must survive.
     */
    private class Undelivered(val refusal: CeilingOutcome.Refused, val mayHaveLanded: Boolean)

    /** Null when every command was CONFIRMED; the refusal, and whether anything may have landed, otherwise. */
    private fun deliver(commandList: List<String>): Undelivered? {
        commandList.forEachIndexed { index, command ->
            val result = runCatching { commands.execute(command) }.getOrElse {
                return Undelivered(
                    CeilingOutcome.Refused(
                        TweakerDiscoveredCeilingContract.REFUSAL_HOST_UNAVAILABLE,
                        "step ${index + 1}/${commandList.size}: ${it.javaClass.simpleName}",
                    ),
                    mayHaveLanded = true,
                )
            }
            if (result.status != CheckedCommandStatus.CONFIRMED) {
                return Undelivered(
                    CeilingOutcome.Refused(
                        TweakerDiscoveredCeilingContract.REFUSAL_HOST_UNAVAILABLE,
                        "step ${index + 1}/${commandList.size} ${result.status}: ${result.detail}",
                    ),
                    mayHaveLanded = result.status != CheckedCommandStatus.FAILED || index > 0,
                )
            }
        }
        return null
    }

    /**
     * An apply whose write did not complete. Only a delivery that provably put nothing on the node
     * ([Undelivered.mayHaveLanded] false) may undo the persisted intent. Anything else -- an AMBIGUOUS answer, a thrown
     * call, or a failure after an earlier step landed -- keeps it, so a withdraw, a profile apply and the keeper still
     * see a possibly-applied ceiling instead of finding a late write with no record to restore it. The apply is still
     * refused: it did not report success, and the detail says the outcome is unconfirmed.
     */
    private fun refuseUndelivered(prior: DiscoveredAxisRecord?, undelivered: Undelivered): CeilingOutcome.Refused {
        if (!undelivered.mayHaveLanded) {
            rollbackRecord(prior)
            return undelivered.refusal
        }
        return CeilingOutcome.Refused(
            undelivered.refusal.code,
            "UNCONFIRMED, the intent record is kept: ${undelivered.refusal.detail}",
        )
    }

    /** Null on success; a STORE_UNAVAILABLE refusal otherwise. Nothing is written after one. */
    private fun persist(record: DiscoveredAxisRecord): CeilingOutcome? {
        val verdict = runCatching { records.write(record) }.getOrElse {
            return refused(
                TweakerDiscoveredCeilingContract.REFUSAL_STORE_UNAVAILABLE,
                it.javaClass.simpleName,
            )
        }
        if (verdict is DiscoveredAxisAdmissionResult.Refused) {
            return refused(
                TweakerDiscoveredCeilingContract.REFUSAL_STORE_UNAVAILABLE,
                "${verdict.code}: ${verdict.detail}",
            )
        }
        return null
    }

    /** Put the store back exactly as it was before a refused apply. Best effort by construction. */
    private fun rollbackRecord(prior: DiscoveredAxisRecord?) {
        runCatching { if (prior == null) records.clear() else records.write(prior) }
    }

    /**
     * Which boot this is.
     *
     * The fallback mirrors the frontend's byte for byte, deliberately: the two halves must agree
     * about boot identity or a record written by one is invisible to the other. When `boot_id` is
     * unreadable the identity is the wall-clock instant the device booted, bucketed so ordinary
     * clock jitter does not move it. Drift makes a live record stop matching, which widens instead
     * of adopting — the safe direction.
     */
    private fun bootIdentity(): String =
        nodes.read(HardwareProbe.BOOT_ID_PATH)?.trim()?.takeIf { it.isNotEmpty() }
            ?: "elapsed:${(clock.wallClockMs() - clock.elapsedRealtimeMs()) / BOOT_IDENTITY_BUCKET_MS}"

    private fun refused(code: String, detail: String) = CeilingOutcome.Refused(code, detail)

    private fun axisName(axis: Int): String = when (axis) {
        TweakerDiscoveredCeilingContract.AXIS_CPU -> "CPU"
        TweakerDiscoveredCeilingContract.AXIS_GPU -> "GPU"
        else -> "axis$axis"
    }

    internal companion object {
        /** Ten seconds: longer than boot-time clock settling, shorter than any real session. */
        const val BOOT_IDENTITY_BUCKET_MS = 10_000L

        /**
         * `SupervisorPolicy.targetRepairWindowMs` / `maxTargetRepairAttemptsPerWindow` /
         * `targetRepairBurstWindowMs` / `maxTargetRepairAttemptsPerBurst`, value for value.
         *
         * Copied rather than shared because the supervisor's policy is a session's policy and this
         * has no session — but the numbers are the same numbers on purpose: they were tuned against
         * the same node, the same second writer and the same "sparse vendor resets remain
         * repairable, a rapid writer fight must not be fought" reading of it.
         */
        const val REPAIR_WINDOW_MS = 30_000L
        const val MAX_REPAIRS_PER_WINDOW = 4
        const val REPAIR_BURST_WINDOW_MS = 5_000L
        const val MAX_REPAIRS_PER_BURST = 2

        /** One second, matching the session supervisor's own sample interval. */
        const val KEEPER_PERIOD_MS = 1_000L

        /** A keeper that finds the same fault every second logs its first sighting and then every 60th (about a minute). */
        const val UNREADABLE_LOG_EVERY = 60
    }
}

/**
 * Starts and stops the keeper's own 1 s fixed-delay task.
 *
 * A port rather than an executor so the whole keeper is testable on the JVM without a thread, and
 * so the daemon — not this file — decides what the tick runs on. The service binds it to its own
 * single-threaded scheduler, separate from and independent of the session supervisor's monitor,
 * funnelling each tick through the shared transaction worker so a tick can never interleave with an
 * apply, a withdraw or a profile mutation at the hardware level.
 *
 * [start] is idempotent: starting an already-running keeper does nothing, so every apply may call it.
 */
internal interface CeilingKeeperScheduler {
    fun start(tick: () -> Unit)
    fun stop()
    fun isRunning(): Boolean
}

/** What the daemon discovered about its own hardware, as the ceiling needs it. */
internal interface CeilingDiscovery {
    fun cpuPolicies(): List<CeilingCpuPolicy>

    /** Ascending kHz; empty when this device published no GPU axis. */
    fun gpuFrequenciesKhz(): List<Int>

    fun gpuAxisAvailable(): Boolean
}

/**
 * One discovered cpufreq policy.
 *
 * @property settledFloorKhz the policy's settled live `scaling_min_freq`, or null when it did not
 * settle. This is the same live-floor notion `MimirCpuWindowAdmission` clamps against; null means
 * "floor unknown" and no clamp, never a guessed floor.
 */
internal data class CeilingCpuPolicy(
    val id: Int,
    val path: String,
    val cpuIds: List<Int>,
    val availableKhz: List<Int>,
    val settledFloorKhz: Int?,
)

/** One raw sysfs read. */
internal fun interface CeilingNodeReader {
    fun read(path: String): String?
}

/** One checked privileged command. Bound to the UN-FENCED PServer path; see the class KDoc. */
internal fun interface CeilingCommandPort {
    fun execute(command: String): CheckedCommandResult
}

/** The record's durable home, as this actuator needs it. */
internal interface CeilingRecordStore {
    /** Absent, present, or unreadable: a store that cannot tell those apart answers every failure as "nothing held". */
    fun read(): DiscoveredAxisRecordRead
    fun write(record: DiscoveredAxisRecord): DiscoveredAxisAdmissionResult
    fun clear(): Boolean
}

/** [DiscoveredAxisRecordStore] as a [CeilingRecordStore]. */
internal class DiscoveredAxisCeilingRecordStore(
    private val delegate: DiscoveredAxisRecordStore,
) : CeilingRecordStore {
    override fun read(): DiscoveredAxisRecordRead = delegate.readState()
    override fun write(record: DiscoveredAxisRecord): DiscoveredAxisAdmissionResult = delegate.write(record)
    override fun clear(): Boolean = delegate.clear()
}

/** Wall clock plus elapsed realtime, the two halves the boot-identity fallback needs. */
internal interface CeilingClock {
    fun wallClockMs(): Long
    fun elapsedRealtimeMs(): Long
}

/** Named-profile session truth: null when the device is at stock with no session at all. */
internal fun interface CeilingProfileSessionState {
    fun activeProfileStateOrNull(): String?
}

/** What a profile apply must do about a held ceiling before it may proceed. */
internal sealed interface CeilingPrecedence {
    data object NothingHeld : CeilingPrecedence
    data object Withdrawn : CeilingPrecedence
    data class Blocked(val detail: String) : CeilingPrecedence

    /** The record that says whether a cap is held could not be read: neither "nothing held" nor "withdrawn". */
    data class Unreadable(val detail: String) : CeilingPrecedence
}

internal sealed interface CeilingOutcome {
    data class Applied(
        val axis: Int,
        val policyId: Int,
        val requestedKhz: Int,
        val admittedKhz: Int,
        val inForceKhz: Int,
        val record: DiscoveredAxisRecord,
    ) : CeilingOutcome

    data class Withdrawn(
        val restoredCpus: Int,
        val leftAloneCpus: Int,
        val gpuRestored: Boolean,
    ) : CeilingOutcome

    data object NothingHeld : CeilingOutcome

    /**
     * @property inForce whether any value [record] asked for is actually on the node right now.
     * False means the record survives (it is the only way back) but the cap it describes is not
     * currently in effect — a second writer took it and one repair did not get it back this tick.
     */
    data class Held(val record: DiscoveredAxisRecord, val inForce: Boolean) : CeilingOutcome
    data object NotHeld : CeilingOutcome
    data class Refused(val code: String, val detail: String) : CeilingOutcome
}

internal fun CeilingOutcome.wireOutcome(): String = when (this) {
    is CeilingOutcome.Applied -> TweakerDiscoveredCeilingContract.OUTCOME_APPLIED
    is CeilingOutcome.Withdrawn -> TweakerDiscoveredCeilingContract.OUTCOME_WITHDRAWN
    CeilingOutcome.NothingHeld -> TweakerDiscoveredCeilingContract.OUTCOME_NOTHING_HELD
    is CeilingOutcome.Held -> TweakerDiscoveredCeilingContract.OUTCOME_HELD
    CeilingOutcome.NotHeld -> TweakerDiscoveredCeilingContract.OUTCOME_NOT_HELD
    is CeilingOutcome.Refused -> TweakerDiscoveredCeilingContract.OUTCOME_REFUSED
}

private fun CeilingOutcome.refusalSuffix(): String =
    (this as? CeilingOutcome.Refused)?.let { " code=${it.code} detail=${it.detail}" }.orEmpty()

/**
 * Every rule with a number in it, as a pure function over published tables.
 *
 * Separate from [DiscoveredCeilingActuator] so each rule has a test that does not need a store, a
 * clock or a command port to state it.
 */
internal object DiscoveredCeilingPlan {

    /**
     * Snap a request onto a frequency the hardware actually has.
     *
     * This is the only door a number goes through, and it is where safety lives: a value outside the
     * published table is snapped onto it rather than written, so nothing that never appeared in
     * `scaling_available_frequencies` or the Adreno's own table can reach a command.
     */
    fun nearestSupportedKhz(availableKhz: List<Int>, requestedKhz: Int): Int? {
        if (availableKhz.isEmpty()) return null
        if (requestedKhz <= 0) return null
        return availableKhz.minByOrNull { abs(it - requestedKhz) }
    }

    /**
     * A ceiling below the live floor is not a writable state: the kernel raises the ceiling to meet
     * the floor, the readback then disagrees with what was asked for, and the apply dies. Clamping
     * up is still an underclock — `min <= max` holds on any live policy — so the clamped value stays
     * at or below the device's own stock maximum.
     */
    fun clampUpToLiveFloor(snappedKhz: Int, settledFloorKhz: Int?): Int =
        if (settledFloorKhz != null && snappedKhz < settledFloorKhz) settledFloorKhz else snappedKhz

    /**
     * The live QoS row as STOCK, with a cap somebody else is already holding corrected away.
     *
     * A CPU sitting below its own cluster's published ceiling is being held there by somebody; the
     * ceiling the silicon published is the value a restore should aim at, because requesting a
     * ceiling AT the ceiling restricts nothing. An unknown ceiling leaves the value alone: guessing
     * on a CPU whose table could not be read is how a repair becomes a write nobody asked for.
     */
    fun stockCpuRowWithoutHeldCaps(liveRow: String, ceilingForCpu: Map<Int, Int>): String {
        val live = parseCpuQosRow(liveRow)
        if (live.isEmpty()) return liveRow
        return live.keys.sorted().joinToString(" ") { cpuId ->
            val khz = live.getValue(cpuId)
            val ceiling = ceilingForCpu[cpuId]
            val held = ceiling != null && khz in 1 until ceiling
            "$cpuId:${if (held) ceiling else khz}"
        }
    }

    /** `0:2016000 1:2016000 …` → `{0 to 2016000, …}`. Malformed pairs are dropped, never guessed. */
    fun parseCpuQosRow(raw: String): Map<Int, Int> =
        raw.trim().split(ROW_SEPARATOR).mapNotNull { pair ->
            val cpu = pair.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
            val khz = pair.substringAfter(':', "").toIntOrNull() ?: return@mapNotNull null
            cpu to khz
        }.toMap()

    /**
     * The same row, or null when it cannot be trusted as a reading of this node: blank, or any pair the lenient parser
     * would have dropped. A drop is silent in [parseCpuQosRow], so a half-read row there looks like a row that simply
     * does not name a CPU, which is how "unreadable" became "not ours".
     */
    fun parseCpuQosRowStrict(raw: String?): Map<Int, Int>? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val row = mutableMapOf<Int, Int>()
        text.split(ROW_SEPARATOR).forEach { pair ->
            val cpu = pair.substringBefore(':').toIntOrNull() ?: return null
            val khz = pair.substringAfter(':', "").toIntOrNull() ?: return null
            row[cpu] = khz
        }
        return row
    }

    /** The node's own spelling, in one place, so the record and the write cannot disagree. */
    fun formatCpuQosRow(row: Map<Int, Int>): String =
        row.keys.sorted().joinToString(" ") { "$it:${row.getValue(it)}" }

    /**
     * One checked command per CPU.
     *
     * Every CPU of the policy is named because the node is per-CPU while the player's choice is
     * per-CLUSTER — a command naming only the policy id would leave the cluster's other cores at
     * whatever they held and produce a cluster running at two ceilings. No CPU outside the policy is
     * named, so nothing another writer owns is touched.
     */
    fun cpuCeilingCommands(cpuIds: List<Int>, khz: Int): List<String> =
        cpuIds.sorted().map { cpuId -> "echo $cpuId:$khz > ${HardwareProbe.CPU_QOS_MAX_PATH}" }

    fun cpuRestoreCommands(restore: Map<Int, Int>): List<String> = cpuRowCommands(restore)

    /**
     * The keeper's re-assert, named for its own duty.
     *
     * Same spelling as [cpuRestoreCommands] and [cpuCeilingCommands] — the node has exactly one —
     * but a repair is not a restore and not an apply: it puts OUR value back on CPUs that drifted,
     * one per command, and every CPU it names came from the record's own requested row.
     */
    fun cpuRepairCommands(targets: Map<Int, Int>): List<String> = cpuRowCommands(targets)

    private fun cpuRowCommands(row: Map<Int, Int>): List<String> =
        row.keys.sorted().map { cpuId -> "echo $cpuId:${row.getValue(cpuId)} > ${HardwareProbe.CPU_QOS_MAX_PATH}" }

    /**
     * Which of OUR CPUs no longer carry the value we asked for.
     *
     * A CPU missing from a COMPLETE live row counts as drifted. The keeper no longer reaches this with an incomplete row
     * (that is unreadable, see [DiscoveredCeilingActuator.keeperTick]); the row not naming it at all is not the
     * row carrying our kHz. Only CPUs in [requested] are ever considered, so a repair can never
     * name a core this record never wrote.
     */
    fun driftedCpus(live: Map<Int, Int>, requested: Map<Int, Int>): List<Int> =
        requested.keys.sorted().filter { cpuId -> live[cpuId] != requested.getValue(cpuId) }

    /**
     * Which of [requestedCpus] the stock row names no value for. Such a CPU has no way back: a restore would have to
     * skip it, and the record would clear with the CPU still capped.
     */
    fun cpusWithoutStock(requestedCpus: Collection<Int>, stock: Map<Int, Int>): List<Int> =
        requestedCpus.sorted().filter { it !in stock }

    /** Is ANY value we asked for still on the row? The question [DiscoveredCeilingActuator.query] answers. */
    fun anyCpuRequestedInForce(live: Map<Int, Int>, requested: Map<Int, Int>): Boolean =
        requested.any { (cpuId, khz) -> live[cpuId] == khz }

    /**
     * Have the Adreno bounds been opened back up past ours, on BOTH levels?
     *
     * A power level counts DOWN to faster (0 is the fastest), so a value below ours on both bounds
     * is somebody widening what we narrowed — the GPU's spelling of the CPU row going back to
     * INT_MAX. Anything else (one bound moved, a bound moved the other way, a level unreadable) is
     * not ours to correct: it is either a writer who owns exactly one bound or a mid-write we would
     * be racing.
     */
    fun gpuBoundsOpened(
        liveMinLevel: Int?,
        liveMaxLevel: Int?,
        requestedMinLevel: Int,
        requestedMaxLevel: Int,
    ): Boolean {
        if (liveMinLevel == null || liveMaxLevel == null) return false
        return liveMinLevel < requestedMinLevel && liveMaxLevel < requestedMaxLevel
    }

    /** `[0, 1, 2]` → `0,1,2`; empty → `none`, so a log line never ends in a dangling `=`. */
    fun formatCpuIds(cpuIds: List<Int>): String =
        if (cpuIds.isEmpty()) "none" else cpuIds.joinToString(",")

    /**
     * Which CPUs are still ours to give back.
     *
     * A CPU whose live value is no longer the value we requested was moved by the other writer this
     * node is known to have, and stamping our captured value over theirs would be the daemon undoing
     * a change it never made and cannot see — worse than leaving the cap, because it is invisible
     * and looks like a restore succeeding.
     */
    fun cpuRestorePlan(
        live: Map<Int, Int>,
        requested: Map<Int, Int>,
        stock: Map<Int, Int>,
    ): CpuRestorePlan {
        val restore = mutableMapOf<Int, Int>()
        val leftAlone = mutableListOf<Int>()
        requested.keys.sorted().forEach { cpuId ->
            val wrote = requested.getValue(cpuId)
            val stockValue = stock[cpuId] ?: return@forEach
            if (live[cpuId] == wrote) restore[cpuId] = stockValue else leftAlone += cpuId
        }
        return CpuRestorePlan(restore = restore, leftAlone = leftAlone)
    }

    /**
     * Adreno power level (0 = fastest) for a kHz target, against an ASCENDING table.
     *
     * The inversion is the whole function: level 0 is the LAST entry of an ascending table, not the
     * first. Backwards here caps a player's GPU at its floor while the reply claims a raised ceiling.
     */
    fun gpuPowerLevelForKhz(availableKhz: List<Int>, targetKhz: Int): Int {
        if (availableKhz.isEmpty()) return 0
        val nearest = availableKhz.indices.minByOrNull { abs(availableKhz[it] - targetKhz) }
            ?: availableKhz.lastIndex
        return (availableKhz.lastIndex - nearest).coerceIn(0, availableKhz.lastIndex)
    }

    /** The inverse of [gpuPowerLevelForKhz], for rendering a level the kernel reports back. */
    fun gpuKhzForPowerLevel(availableKhz: List<Int>, level: Int): Int? {
        if (availableKhz.isEmpty()) return null
        return availableKhz.getOrNull(availableKhz.lastIndex - level)
    }

    fun slowestGpuPowerLevel(availableKhz: List<Int>, level: Int): Int =
        availableKhz.lastIndex.coerceAtLeast(level)

    /** min_pwrlevel FIRST, then max_pwrlevel: the kernel clamps max against min. */
    fun gpuCeilingCommands(slowestLevel: Int, maxLevel: Int): List<String> = listOf(
        "echo $slowestLevel > ${HardwareProbe.GPU_MIN_LEVEL}",
        "echo $maxLevel > ${HardwareProbe.GPU_MAX_LEVEL}",
    )

    /** max_pwrlevel FIRST, then min_pwrlevel: the reverse of apply, for the same clamp. */
    fun gpuRestoreCommands(stockMinLevel: Int, stockMaxLevel: Int): List<String> = listOf(
        "echo $stockMaxLevel > ${HardwareProbe.GPU_MAX_LEVEL}",
        "echo $stockMinLevel > ${HardwareProbe.GPU_MIN_LEVEL}",
    )

    /**
     * [gpuRestoreCommands] for only the bounds that are ours to put back, in the same order (max first, then min). Both
     * flags set is exactly [gpuRestoreCommands]; a half-delivered apply leaves one bound ours and the other still at
     * stock, and only the one that moved is written.
     */
    fun gpuRestoreCommandsFor(
        stockMinLevel: Int,
        stockMaxLevel: Int,
        restoreMin: Boolean,
        restoreMax: Boolean,
    ): List<String> = buildList {
        if (restoreMax) add("echo $stockMaxLevel > ${HardwareProbe.GPU_MAX_LEVEL}")
        if (restoreMin) add("echo $stockMinLevel > ${HardwareProbe.GPU_MIN_LEVEL}")
    }

    private val ROW_SEPARATOR = Regex("\\s+")
}

/**
 * The verdict a profile apply takes from the ceiling check that runs ahead of it, as a refusal code or null.
 *
 * **Fails closed.** The check used to be `runCatching { withdrawBeforeProfileApply() }.getOrNull()`, and a null
 * (an exception) meant "proceed": a ceiling that could not even be looked at was treated as no ceiling, and the apply
 * went on to capture a held cap as stock. Now an exception refuses, exactly as the CPU transport check does, and so
 * does a record that could not be read. Only "nothing held" and "withdrawn and read back" let an apply through.
 *
 * `CEILING_HELD` says a ceiling is held and could not be given back. `CEILING_UNCONFIRMED` says the daemon could not
 * establish whether one is held at all (the check threw, or its record was unreadable). Both refuse the PROFILE; neither
 * claims the ceiling check changed nothing, because a precedence withdraw may have restored hardware before it threw.
 */
internal object CeilingPrecedenceGate {
    fun refusalCode(evaluate: () -> CeilingPrecedence, log: (String) -> Unit): String? {
        val precedence = try {
            evaluate()
        } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            log(
                "DISCOVERED_CEILING profile apply refused: the ceiling could not be checked " +
                    "(${error.javaClass.simpleName}: ${error.message}); the profile was not applied",
            )
            // The worker did not answer in time: that is the daemon being busy, not a ceiling state of any kind.
            if (error is TransactionWorkerBusyException) return SERVICE_BUSY_CODE
            // The check THREW: nothing says a ceiling is held, only that none could be ruled out. A distinct code, so the
            // caller is never told a ceiling exists when none was established.
            return TweakerDiscoveredCeilingContract.REFUSAL_CEILING_UNCONFIRMED
        }
        return when (precedence) {
            CeilingPrecedence.NothingHeld, CeilingPrecedence.Withdrawn -> null
            is CeilingPrecedence.Blocked -> {
                log("DISCOVERED_CEILING profile apply refused: ${precedence.detail}")
                TweakerDiscoveredCeilingContract.REFUSAL_CEILING_HELD
            }
            is CeilingPrecedence.Unreadable -> {
                log("DISCOVERED_CEILING profile apply refused: ${precedence.detail}")
                TweakerDiscoveredCeilingContract.REFUSAL_CEILING_UNCONFIRMED
            }
        }
    }

    /** The caller-facing sentence for a code [refusalCode] returned: it must not claim more than the code does. */
    fun refusalMessage(code: String): String = when (code) {
        TweakerDiscoveredCeilingContract.REFUSAL_CEILING_UNCONFIRMED ->
            "Couldn't confirm the ceiling state; the profile was not applied."
        SERVICE_BUSY_CODE -> "The Tweaker is busy; the profile was not applied."
        else -> "A discovered ceiling is held and could not be withdrawn first."
    }
}

/**
 * The refusal a v10 ceiling call answers when running it on the transaction worker failed. Never "nothing held": a
 * query that could not be answered is a refusal the client reads as unavailable, and an apply or withdraw whose effect
 * is unknown says so.
 */
internal object CeilingWorkerFailure {
    fun refusal(error: Throwable): CeilingOutcome.Refused {
        val cause = (error as? java.util.concurrent.ExecutionException)?.cause ?: error
        return if (cause is TransactionWorkerBusyException) {
            CeilingOutcome.Refused(
                SERVICE_BUSY_CODE,
                if (cause.started) {
                    "UNCONFIRMED, the Tweaker was still working on this call; its effect is unknown"
                } else {
                    "the Tweaker was busy; nothing was changed"
                },
            )
        } else {
            CeilingOutcome.Refused(
                TweakerDiscoveredCeilingContract.REFUSAL_HOST_UNAVAILABLE,
                "UNCONFIRMED, the ceiling operation failed: ${cause.javaClass.simpleName}",
            )
        }
    }
}

internal data class CpuRestorePlan(
    val restore: Map<Int, Int>,
    val leftAlone: List<Int>,
)

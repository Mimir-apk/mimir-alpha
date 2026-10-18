package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.tweaker.TWEAKER_CEILING_AXIS_CPU
import com.gruning.mimir.rewrite.core.tweaker.TWEAKER_CEILING_AXIS_GPU
import com.gruning.mimir.rewrite.core.tweaker.TWEAKER_CEILING_UNREADABLE_KHZ
import com.gruning.mimir.rewrite.core.tweaker.TWEAKER_DISCOVERED_ABSENT_LEVEL
import com.gruning.mimir.rewrite.core.tweaker.TweakerDiscoveredCeilingRead
import com.gruning.mimir.rewrite.core.tweaker.TweakerDiscoveredCeilingRecord
import com.gruning.mimir.rewrite.core.tweaker.TweakerDiscoveredCeilingWrite

/**
 * The v10 wire, translated into what `MimirTunerWrite` already speaks.
 *
 * **Why the translation lives here and not in `core-tweaker-client`.** That module cannot see
 * `MimirTunerPolicy`, `DaemonCeilingOutcome` or `MimirTunerWrite` and must not learn to: it is the
 * client for a Binder contract, and a client that knows the shape of the app's tuner is a client that
 * has to change whenever the tuner does. So the wire carries primitives — an axis Int, a `cpuId:kHz`
 * row, `-1` for absence — and exactly one file on this side turns them back into the app's types.
 *
 * **Everything in this file is pure**, which is the point of separating it from the actuator in
 * `TweakerBridge`. The reservation-and-bind half cannot be unit-tested without a daemon; the mapping
 * half decides what the surface will show, and it can be pinned on the JVM before any device build.
 */

/** The axis the daemon knows, from the kind the tuner knows. The only place the two vocabularies meet. */
internal fun mimirTunerCeilingAxis(kind: MimirTunerPolicy.Kind): Int = when (kind) {
    MimirTunerPolicy.Kind.CPU -> TWEAKER_CEILING_AXIS_CPU
    MimirTunerPolicy.Kind.GPU -> TWEAKER_CEILING_AXIS_GPU
}

/** And back again. Null for an axis this build does not know, so an unrecognised echo is never guessed at. */
internal fun mimirTunerCeilingKind(axis: Int): MimirTunerPolicy.Kind? = when (axis) {
    TWEAKER_CEILING_AXIS_CPU -> MimirTunerPolicy.Kind.CPU
    TWEAKER_CEILING_AXIS_GPU -> MimirTunerPolicy.Kind.GPU
    else -> null
}

/**
 * The daemon's record, in the tuner's own vocabulary.
 *
 * Mirrors `TweakerDiscoveredSessionRead.toTunerRecordRead` field for field, including how it spells
 * absence: the wire says `""` and `-1`, the tuner says null. Sharing the row parser with the write path
 * (`MimirTunerWrite.parseCpuQosRow`) is deliberate — the record and the write must never disagree about
 * what `7:1843200` means, and the only way to guarantee that is for one function to decide it.
 */
internal fun TweakerDiscoveredCeilingRecord.toDaemonCeilingRecord(): DaemonCeilingRecord = DaemonCeilingRecord(
    bootIdentity = bootIdentity,
    cpuStockRow = cpuStockRow.takeIf { it.isNotBlank() },
    cpuRequested = MimirTunerWrite.parseCpuQosRow(cpuRequestedRow),
    gpuStockMinLevel = gpuStockMinLevel.takeIf { it != TWEAKER_DISCOVERED_ABSENT_LEVEL },
    gpuStockMaxLevel = gpuStockMaxLevel.takeIf { it != TWEAKER_DISCOVERED_ABSENT_LEVEL },
    gpuRequestedMinLevel = gpuRequestedMinLevel.takeIf { it != TWEAKER_DISCOVERED_ABSENT_LEVEL },
    gpuRequestedMaxLevel = gpuRequestedMaxLevel.takeIf { it != TWEAKER_DISCOVERED_ABSENT_LEVEL },
)

/**
 * A write verdict, in the tuner's own vocabulary.
 *
 * **The daemon's echo outranks the request, and that is not pedantry.** The whole reason the write
 * moved into the daemon is that the daemon re-admits every value against its own discovery: it snaps
 * the kHz onto the published table and clamps CPU targets to the live floor. So [requestedKind] and
 * [askedKind], [askedPolicyId] and [askedKhz] are what this side ASKED for and are read only where the
 * reply says nothing — an echo that disagrees is the daemon telling us what it actually did, and
 * reporting our own request back to the surface would hide exactly the correction this route exists to
 * apply. The contract promises only `admittedKhz` on an `APPLIED`, so all three echoes are genuinely
 * optional and the substitution is the normal case rather than a repair.
 *
 * All three carry defaults because they are fallbacks for an `APPLIED` reply alone, and `withdraw`
 * cannot produce one: a withdrawal names no axis and no frequency, by design.
 */
internal fun TweakerDiscoveredCeilingWrite.toDaemonCeilingOutcome(
    askedKind: MimirTunerPolicy.Kind = MimirTunerPolicy.Kind.CPU,
    askedPolicyId: Int = GpuPolicyId,
    askedKhz: Int = TWEAKER_CEILING_UNREADABLE_KHZ,
): DaemonCeilingOutcome = when (this) {
    is TweakerDiscoveredCeilingWrite.Applied -> DaemonCeilingOutcome.Applied(
        kind = axis?.let(::mimirTunerCeilingKind) ?: askedKind,
        policyId = policyId ?: askedPolicyId,
        requestedKhz = requestedKhz?.takeIf { it != TWEAKER_CEILING_UNREADABLE_KHZ } ?: askedKhz,
        admittedKhz = admittedKhz,
        inForceKhz = inForceKhz,
        record = record?.toDaemonCeilingRecord(),
    )

    is TweakerDiscoveredCeilingWrite.Withdrawn -> DaemonCeilingOutcome.Withdrawn(
        restoredCpus = restoredCpus,
        leftAloneCpus = leftAloneCpus,
        gpuRestored = gpuRestored,
    )

    TweakerDiscoveredCeilingWrite.NothingHeld -> DaemonCeilingOutcome.NothingHeld
    is TweakerDiscoveredCeilingWrite.Refused -> DaemonCeilingOutcome.Refused(code, detail)
    is TweakerDiscoveredCeilingWrite.Unavailable -> DaemonCeilingOutcome.Unavailable(reason)
}

/**
 * A read verdict, in the tuner's own vocabulary.
 *
 * `NotHeld` and `Unavailable` stay apart the whole way across, because they demand opposite behaviour
 * at cold start and this is the last place they could have been collapsed.
 */
internal fun TweakerDiscoveredCeilingRead.toDaemonCeilingOutcome(): DaemonCeilingOutcome = when (this) {
    is TweakerDiscoveredCeilingRead.Held -> DaemonCeilingOutcome.Held(record.toDaemonCeilingRecord())
    TweakerDiscoveredCeilingRead.NotHeld -> DaemonCeilingOutcome.NotHeld
    is TweakerDiscoveredCeilingRead.Unavailable -> DaemonCeilingOutcome.Unavailable(reason)
}

/**
 * One log line per actuator call, spelled the same way for all three.
 *
 * The ceiling's life now crosses two processes, and an operator watching `-s MimirTuner` needs the
 * daemon's verdict in the same stream as the tuner's own decisions — otherwise a refusal on the far
 * side of a Binder looks, from here, exactly like nothing having happened.
 */
internal fun daemonCeilingOutcomeLogLine(call: String, outcome: DaemonCeilingOutcome): String = when (outcome) {
    is DaemonCeilingOutcome.Applied ->
        "route $call outcome=APPLIED kind=${outcome.kind} policy=${outcome.policyId} " +
            "requestedKhz=${outcome.requestedKhz} admittedKhz=${outcome.admittedKhz} " +
            "inForceKhz=${outcome.inForceKhz} record=${outcome.record != null}"

    is DaemonCeilingOutcome.Withdrawn ->
        "route $call outcome=WITHDRAWN restoredCpus=${outcome.restoredCpus} " +
            "leftAloneCpus=${outcome.leftAloneCpus} gpuRestored=${outcome.gpuRestored}"

    DaemonCeilingOutcome.NothingHeld -> "route $call outcome=NOTHING_HELD"
    is DaemonCeilingOutcome.Held ->
        "route $call outcome=HELD boot=${outcome.record.bootIdentity} " +
            "cpus=${outcome.record.cpuRequested.size} empty=${outcome.record.isEmpty()}"

    DaemonCeilingOutcome.NotHeld -> "route $call outcome=NOT_HELD"
    is DaemonCeilingOutcome.Refused -> "route $call outcome=REFUSED code=${outcome.code} detail=${outcome.detail}"
    is DaemonCeilingOutcome.Unavailable -> "route $call outcome=UNAVAILABLE reason=${outcome.reason}"
}

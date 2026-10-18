package com.gruning.mimir.rewrite.core.tweaker

import android.os.Bundle
import com.gruning.mimir.tweaker.TweakerDiscoveredCeilingContract

/**
 * API v10 — the discovered clock ceiling, WRITTEN by the daemon .
 *
 * **v9 moved the memory; v10 moves the write.** R2 (2026-09-01) put the *record* in the daemon and
 * deliberately left the write in Mimir, because what a kill destroys is the way back, not the cap. What
 * that left behind is a node with two writers — `MimirTunerWrite`'s `CpuQosNode` and the daemon's
 * shared-QoS transport — and a cold start that cannot tell the daemon's cap from an orphan.
 * The daemon now owns the node whenever present; the Tuner routes through it and writes
 * directly only without a daemon.
 *
 * **Its own minimum API version, for v9's reason and one more.** A missing method surfaces as a failed
 * Binder transaction, and this family's failure mode must never be read as *"the daemon holds no
 * ceiling"* — nor, worse, as permission to write the node directly. Asking an older daemon at all is
 * the mistake, so the app checks the version before it asks, and `mimirTunerRouteDecision` refuses
 * rather than falls back whenever the pair can bind and the version is not in hand.
 *
 * **Nothing here names a sysfs node, and nothing can.** The caller names an AXIS, a POLICY ID and a
 * kHz; the daemon re-admits every value against its own discovery, snaps it onto the published table
 * and clamps CPU targets to the live floor itself. This is the v6 rule ("this API never accepts PWM,
 * sysfs paths, or vendor modes") kept structurally rather than by validation.
 */
const val TWEAKER_V10_DISCOVERED_CEILING_MIN_API_VERSION = 10

/**
 * The two axes the discovered tuner knows, mirrored out of the contract as plain Ints.
 *
 * **Mirrored, not re-exported, because the app cannot see the contract on every variant.** `:app`
 * takes `:mimir-tweaker-contract` on `debugImplementation` / `lab` / `devlab` only, so a release build
 * of `TweakerBridge` has no `TweakerDiscoveredCeilingContract` on its classpath. Carrying the wire's
 * primitives across this module boundary is what keeps the routed ceiling reachable in the release
 * build.
 */
const val TWEAKER_CEILING_AXIS_CPU = TweakerDiscoveredCeilingContract.AXIS_CPU
const val TWEAKER_CEILING_AXIS_GPU = TweakerDiscoveredCeilingContract.AXIS_GPU

/** The GPU is not a cpufreq policy; the frontend already uses this id for it. */
const val TWEAKER_CEILING_GPU_POLICY_ID = TweakerDiscoveredCeilingContract.GPU_POLICY_ID

/** `-1`: the value could not be read back. Absence, never a frequency. The contract's own spelling. */
const val TWEAKER_CEILING_UNREADABLE_KHZ = TweakerDiscoveredCeilingContract.UNREADABLE_KHZ

/**
 * The ceiling the daemon is holding, in the wire's own spelling.
 *
 * Deliberately the same shape as [TweakerDiscoveredSession] rather than a widening of it: v9's record
 * is a memo about a write Mimir made, and this is the daemon's account of a write the daemon made.
 * They agree field for field today, and one type would make a later divergence a silent reinterpretation
 * of the other family's rows. Rows are the QoS node's own `cpuId:kHz` spelling; GPU levels are `-1`
 * when absent.
 */
data class TweakerDiscoveredCeilingRecord(
    val bootIdentity: String,
    val cpuStockRow: String,
    val cpuRequestedRow: String,
    val gpuStockMinLevel: Int = TWEAKER_DISCOVERED_ABSENT_LEVEL,
    val gpuStockMaxLevel: Int = TWEAKER_DISCOVERED_ABSENT_LEVEL,
    val gpuRequestedMinLevel: Int = TWEAKER_DISCOVERED_ABSENT_LEVEL,
    val gpuRequestedMaxLevel: Int = TWEAKER_DISCOVERED_ABSENT_LEVEL,
)

/**
 * What the daemon said when asked whether it holds a ceiling.
 *
 * **Three answers, and the last two must never be collapsed** — the rule [TweakerDiscoveredSessionRead]
 * states, with a sharper consequence here. *"The daemon holds no ceiling"* and *"I could not ask the
 * daemon"* demand opposite behaviour at cold start: the first means there is genuinely nothing to
 * adopt, the second means the evidence is not in yet. Reading the second as the first is what widens a
 * cap the daemon is still holding, which is the collision measured on the Thor on 2026-09-11.
 */
sealed interface TweakerDiscoveredCeilingRead {
    data class Held(val record: TweakerDiscoveredCeilingRecord) : TweakerDiscoveredCeilingRead
    data object NotHeld : TweakerDiscoveredCeilingRead
    data class Unavailable(val reason: String) : TweakerDiscoveredCeilingRead
}

/**
 * What the daemon did with a ceiling it was asked to write or give back.
 *
 * **Refused and Unavailable are separate, for v9's reason.** A refusal is the daemon judging the
 * request — `PROFILE_ACTIVE`, `UNDISCOVERED_POLICY`, `WRITE_NOT_HELD`, … — and is a fact about what
 * was asked; an unavailability is the transport, and is a fact about nothing. Collapsing them sends
 * whoever reads the log into the wrong half of the system, and sends the ROUTE into the wrong branch:
 * a refusal is the daemon owning the node, an unavailability is the daemon not answering, and the
 * safe response to the second is to refuse the write, never to take the node back.
 */
sealed interface TweakerDiscoveredCeilingWrite {
    /**
     * The daemon wrote the ceiling.
     *
     * [admittedKhz] is what it asked the kernel for after its own table snap and live-floor clamp, and
     * [inForceKhz] is what read back afterwards — null when the node could not be read. The three
     * numbers are kept apart because the surface must be able to say *"you asked for A, the daemon
     * admitted B, the silicon is holding C"* rather than assert one of them three times.
     *
     * **[axis], [policyId] and [requestedKhz] are nullable because the contract does not promise them
     * on an `APPLIED` reply** — it names `admittedKhz` and `inForceKhz` as this outcome's payload, and
     * the other three are echoes. A default would have been a fabrication with a plausible shape: a
     * missing `policyId` read as `-1` is a reply claiming the GPU was capped when the caller asked for
     * a CPU cluster. Null means *the daemon did not say*, and the one code that knows what was asked —
     * the caller — substitutes it.
     */
    data class Applied(
        val axis: Int?,
        val policyId: Int?,
        val requestedKhz: Int?,
        val admittedKhz: Int,
        val inForceKhz: Int?,
        val record: TweakerDiscoveredCeilingRecord?,
    ) : TweakerDiscoveredCeilingWrite

    /** The daemon gave back exactly what it captured, on the CPUs and levels still holding its value. */
    data class Withdrawn(
        val restoredCpus: Int,
        val leftAloneCpus: Int,
        val gpuRestored: Boolean,
    ) : TweakerDiscoveredCeilingWrite

    /** The daemon answered, and it was holding nothing to give back. Not an error. */
    data object NothingHeld : TweakerDiscoveredCeilingWrite

    /** The daemon judged the request against its own discovery and posture, and declined. */
    data class Refused(val code: String, val detail: String) : TweakerDiscoveredCeilingWrite

    /** Nobody judged anything: an old daemon, an unbound service, or a thrown transaction. */
    data class Unavailable(val reason: String) : TweakerDiscoveredCeilingWrite
}

/**
 * Is the daemon running a NAMED PROFILE right now?
 *
 * **The ceiling and a named profile are mutually exclusive on the same node**, so the route has to know
 * which one the daemon is running before it decides anything. Derived from the V4 truth the bridge
 * already reads rather than from a new call: `querySessionV4` answers this exactly, and adding a second
 * AIDL method to ask a question an existing one answers would create two versions of one fact.
 *
 * Null is *"not asked / could not be established"* and is never folded into false. False is the daemon
 * saying Stock with no mutation in flight — the one state in which nothing of the daemon's is on the
 * node. Every other state, including `UNKNOWN` and a Stock mid-mutation, reads true, because the safe
 * direction here is to believe the daemon is holding the node rather than to write across it.
 */
fun namedProfileSessionActive(assessment: TweakerV4SessionAssessment?): Boolean? = when (assessment) {
    null -> null
    is TweakerV4SessionAssessment.Uncertain -> null
    is TweakerV4SessionAssessment.Stock -> false
    is TweakerV4SessionAssessment.Blocked -> true
}

/**
 * `-1`: a `WITHDRAWN` reply named no count.
 *
 * **Not `0`, and the difference is the whole reason this constant exists.** The contract's counts
 * distinguish *"the CPUs were still holding my value and I gave them back"* from *"they had moved, so
 * another writer owns them and I left them alone"* — and `restoredCpus = 0` is a real, meaningful
 * outcome the daemon can report. Defaulting a missing key to `0` would manufacture that outcome out of
 * a malformed reply. `-1` is the contract's own spelling of absence (`UNREADABLE_KHZ`, `ABSENT_LEVEL`),
 * so a count nobody sent reads as a count nobody sent.
 */
const val TWEAKER_CEILING_UNCOUNTED = -1

/**
 * The write half of the wire, for both `applyDiscoveredCeilingV10` and `withdrawDiscoveredCeilingV10`.
 *
 * One parser for both calls because one bundle shape serves both: the contract puts the outcome in
 * [TweakerDiscoveredCeilingContract.KEY_OUTCOME] and lets the outcome decide which other keys are
 * present. An outcome this call cannot produce — `HELD` from an apply, say — is reported as
 * [TweakerDiscoveredCeilingWrite.Unavailable] rather than guessed at, because a daemon answering a
 * question nobody asked is a daemon whose answer cannot be trusted to mean what it says.
 */
internal fun parseTweakerDiscoveredCeilingWriteBundle(
    bundle: Bundle,
    expectedApiVersion: Int,
): TweakerDiscoveredCeilingWrite {
    val apiVersion = bundle.getInt(TweakerDiscoveredCeilingContract.KEY_API_VERSION, -1)
    if (apiVersion != expectedApiVersion) {
        return TweakerDiscoveredCeilingWrite.Unavailable("Discovered-ceiling API version mismatch")
    }
    return when (val outcome = bundle.getString(TweakerDiscoveredCeilingContract.KEY_OUTCOME).orEmpty()) {
        TweakerDiscoveredCeilingContract.OUTCOME_APPLIED -> {
            // The one number the contract promises on this outcome. An APPLIED that cannot say what it
            // admitted is a reply nothing can act on — the surface would have to render a frequency it
            // invented — so it is reported as nothing having happened rather than shown.
            val admitted = bundle.optionalInt(TweakerDiscoveredCeilingContract.KEY_ADMITTED_KHZ)
            if (admitted == null || admitted == TWEAKER_CEILING_UNREADABLE_KHZ) {
                return TweakerDiscoveredCeilingWrite.Unavailable("Applied ceiling named no admitted frequency")
            }
            TweakerDiscoveredCeilingWrite.Applied(
                axis = bundle.optionalInt(TweakerDiscoveredCeilingContract.KEY_AXIS),
                policyId = bundle.optionalInt(TweakerDiscoveredCeilingContract.KEY_POLICY_ID),
                requestedKhz = bundle.optionalInt(TweakerDiscoveredCeilingContract.KEY_REQUESTED_KHZ),
                admittedKhz = admitted,
                // The one value that is READ BACK rather than asked for, so `-1` here is a real state —
                // *"the write went out and the node could not be read after it"* — and is spelled null
                // rather than repeated from `admittedKhz`, which nothing confirmed.
                inForceKhz = bundle.optionalInt(TweakerDiscoveredCeilingContract.KEY_IN_FORCE_KHZ)
                    ?.takeIf { it != TWEAKER_CEILING_UNREADABLE_KHZ },
                record = bundle.discoveredCeilingRecordOrNull(),
            )
        }

        TweakerDiscoveredCeilingContract.OUTCOME_WITHDRAWN -> TweakerDiscoveredCeilingWrite.Withdrawn(
            restoredCpus = bundle.getInt(
                TweakerDiscoveredCeilingContract.KEY_RESTORED_CPUS,
                TWEAKER_CEILING_UNCOUNTED,
            ),
            leftAloneCpus = bundle.getInt(
                TweakerDiscoveredCeilingContract.KEY_LEFT_ALONE_CPUS,
                TWEAKER_CEILING_UNCOUNTED,
            ),
            gpuRestored = bundle.getBoolean(TweakerDiscoveredCeilingContract.KEY_GPU_RESTORED, false),
        )

        TweakerDiscoveredCeilingContract.OUTCOME_NOTHING_HELD -> TweakerDiscoveredCeilingWrite.NothingHeld

        TweakerDiscoveredCeilingContract.OUTCOME_REFUSED -> bundle.refusal()

        else -> TweakerDiscoveredCeilingWrite.Unavailable(
            "Discovered-ceiling write reported no usable outcome: ${outcome.ifBlank { "none" }}",
        )
    }
}

/**
 * The read half of the wire, for `queryDiscoveredCeilingV10`.
 *
 * **Every failure is [TweakerDiscoveredCeilingRead.Unavailable], never [TweakerDiscoveredCeilingRead.NotHeld].**
 * A refusal is included in that: the daemon declining to answer a query is not the daemon saying it
 * holds nothing, and the refusal code rides along in the reason so the distinction survives into the log
 * without the read type having to grow a fourth case it has no branch for.
 */
internal fun parseTweakerDiscoveredCeilingReadBundle(
    bundle: Bundle,
    expectedApiVersion: Int,
): TweakerDiscoveredCeilingRead {
    val apiVersion = bundle.getInt(TweakerDiscoveredCeilingContract.KEY_API_VERSION, -1)
    if (apiVersion != expectedApiVersion) {
        return TweakerDiscoveredCeilingRead.Unavailable("Discovered-ceiling API version mismatch")
    }
    return when (val outcome = bundle.getString(TweakerDiscoveredCeilingContract.KEY_OUTCOME).orEmpty()) {
        TweakerDiscoveredCeilingContract.OUTCOME_HELD -> {
            val record = bundle.discoveredCeilingRecordOrNull()
                // Two shapes arrive here and both are the daemon contradicting itself: a ceiling that
                // cannot say which boot it belongs to (it describes a machine state that may no longer
                // exist, and adopting it would stamp a stale row over whatever is on the node now), and
                // an outcome of HELD carrying `held=false`. The reason names neither specifically
                // because the parser genuinely cannot tell the caller which — only that the reply did
                // not carry a record it could use.
                ?: return TweakerDiscoveredCeilingRead.Unavailable("Held ceiling carried no usable record")
            TweakerDiscoveredCeilingRead.Held(record)
        }

        TweakerDiscoveredCeilingContract.OUTCOME_NOT_HELD -> TweakerDiscoveredCeilingRead.NotHeld

        TweakerDiscoveredCeilingContract.OUTCOME_REFUSED -> {
            val refusal = bundle.refusal()
            TweakerDiscoveredCeilingRead.Unavailable(
                "Discovered-ceiling query refused: code=${refusal.code} detail=${refusal.detail}",
            )
        }

        else -> TweakerDiscoveredCeilingRead.Unavailable(
            "Discovered-ceiling query reported no usable outcome: ${outcome.ifBlank { "none" }}",
        )
    }
}

/**
 * The record keys, read the same way wherever they appear.
 *
 * Null when the bundle names no boot: the contract lets an `APPLIED` reply carry the record and lets it
 * omit it, and `MimirTunerSnapshot` has always treated a record with no boot identity as no record at
 * all. [TweakerDiscoveredCeilingContract.KEY_HELD], when present, is honoured the same way — a bundle
 * that says `held=false` carries no record, whatever else is in it.
 */
private fun Bundle.discoveredCeilingRecordOrNull(): TweakerDiscoveredCeilingRecord? {
    if (containsKey(TweakerDiscoveredCeilingContract.KEY_HELD) &&
        !getBoolean(TweakerDiscoveredCeilingContract.KEY_HELD, false)
    ) {
        return null
    }
    val boot = getString(TweakerDiscoveredCeilingContract.KEY_BOOT_IDENTITY).orEmpty()
    if (boot.isBlank()) return null
    return TweakerDiscoveredCeilingRecord(
        bootIdentity = boot,
        cpuStockRow = getString(TweakerDiscoveredCeilingContract.KEY_CPU_STOCK_ROW).orEmpty(),
        cpuRequestedRow = getString(TweakerDiscoveredCeilingContract.KEY_CPU_REQUESTED_ROW).orEmpty(),
        gpuStockMinLevel = getInt(TweakerDiscoveredCeilingContract.KEY_GPU_STOCK_MIN_LEVEL, TWEAKER_DISCOVERED_ABSENT_LEVEL),
        gpuStockMaxLevel = getInt(TweakerDiscoveredCeilingContract.KEY_GPU_STOCK_MAX_LEVEL, TWEAKER_DISCOVERED_ABSENT_LEVEL),
        gpuRequestedMinLevel = getInt(TweakerDiscoveredCeilingContract.KEY_GPU_REQUESTED_MIN_LEVEL, TWEAKER_DISCOVERED_ABSENT_LEVEL),
        gpuRequestedMaxLevel = getInt(TweakerDiscoveredCeilingContract.KEY_GPU_REQUESTED_MAX_LEVEL, TWEAKER_DISCOVERED_ABSENT_LEVEL),
    )
}

/**
 * *"The daemon did not send this key"*, kept distinct from *"it sent a zero"*.
 *
 * `Bundle.getInt(key, default)` cannot tell the two apart, and for an echo — an axis, a policy id, the
 * frequency we asked for — the difference decides whether the caller may substitute what it knows.
 */
private fun Bundle.optionalInt(key: String): Int? = if (containsKey(key)) getInt(key) else null

/** An unnamed refusal is still a refusal: the request genuinely was judged and genuinely was declined. */
private fun Bundle.refusal(): TweakerDiscoveredCeilingWrite.Refused = TweakerDiscoveredCeilingWrite.Refused(
    code = getString(TweakerDiscoveredCeilingContract.KEY_REFUSAL_CODE).orEmpty().ifBlank { "UNNAMED_REFUSAL" },
    detail = getString(TweakerDiscoveredCeilingContract.KEY_REFUSAL_DETAIL).orEmpty(),
)

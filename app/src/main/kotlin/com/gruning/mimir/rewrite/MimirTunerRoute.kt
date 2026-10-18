package com.gruning.mimir.rewrite

/**
 * WHO writes a clock ceiling, decided in exactly one place.
 *
 * The daemon owns the node whenever present. The Tuner routes through it and writes directly
 * only on a device without a daemon. Routing is deterministic and supports recovery from each state.
 * This file joins the three components:
 * the bridge (implements [DaemonTunerActuator] over the Tweaker's v10 Binder API and keeps
 * [MimirTunerDaemonRoute.presence] current), the tuner writer (consumes both in `MimirTunerWrite`) and the
 * daemon (`mimir-tweaker/`, `applyDiscoveredCeilingV10` and friends).
 *
 * **The policy is a pure function of two facts**, so it can be pinned by a table test and is never
 * re-derived at a call site:
 *
 * | pair can bind | daemon API | route | why |
 * |---|---|---|---|
 * | unknown (bridge has not looked yet) | – | REFUSE | nothing is known; never write on a guess |
 * | no | – | DIRECT | no daemon on this device: discovery is the half that works everywhere |
 * | yes | null (not answered / unreachable now) | REFUSE | a daemon exists; writing around it recreates the two-writer state |
 * | yes | < 10 | DIRECT | an old daemon cannot take the write; today's path with the v9 record |
 * | yes | ≥ 10 | DAEMON | daemon-owned write |
 *
 * **Cold start follows the same table** (`MimirTunerWrite.reconcileColdStart` owns the branches):
 * DIRECT → today's orphan reconcile (widen a cap nobody recorded), except that with an old daemon that
 * reports a named-profile session ([MimirTunerDaemonPresence.profileSessionActive] true) nothing is
 * widened, because that cap is the daemon's; DAEMON → ask the daemon (`query`): HELD adopts the daemon's
 * record into the in-process mirror so the surface reads the same number, NOT_HELD does nothing (the
 * node is the daemon's), Unavailable does nothing and clears nothing; REFUSE → nothing, one log line.
 */
sealed interface MimirTunerRouteDecision {
    data object Daemon : MimirTunerRouteDecision
    data object Direct : MimirTunerRouteDecision
    data class Refuse(val reason: String) : MimirTunerRouteDecision
}

/** The daemon API level at which the ceiling write moved into the daemon. Mirrors the contract's MIN_API_VERSION. */
const val MimirTunerDaemonRouteMinApiVersion: Int = 10

/** Reason strings, spelled once so the surface and the log agree. */
const val MimirTunerRefusePairUnresolved: String = "PAIR UNRESOLVED"
const val MimirTunerRefuseDaemonBusy: String = "TWEAKER BUSY"

/** What the bridge knows about the daemon at the moment of a request. Inputs only, so the policy stays pure. */
data class MimirTunerDaemonPresence(
    /** The pair can bind: Tweaker installed, signature matched, service resolvable. False = no daemon here. */
    val pairCanBind: Boolean,
    /** The daemon's API version from its last successful answer; null when it has not answered or could not be reached on the last attempt. */
    val apiVersion: Int?,
    /** True when the daemon reported a named-profile session (V4 per-game or V5 SYSTEM, any state) at its last answer; null = not asked. */
    val profileSessionActive: Boolean?,
)

fun mimirTunerRouteDecision(presence: MimirTunerDaemonPresence?): MimirTunerRouteDecision = when {
    presence == null -> MimirTunerRouteDecision.Refuse(MimirTunerRefusePairUnresolved)
    !presence.pairCanBind -> MimirTunerRouteDecision.Direct
    presence.apiVersion == null -> MimirTunerRouteDecision.Refuse(MimirTunerRefuseDaemonBusy)
    presence.apiVersion < MimirTunerDaemonRouteMinApiVersion -> MimirTunerRouteDecision.Direct
    else -> MimirTunerRouteDecision.Daemon
}

/**
 * The session the daemon holds, as the app sees it. Same shape as `MimirTunerSnapshot` on purpose: the
 * in-process mirror adopts it field for field. Absence is null here; the wire spells it -1.
 */
data class DaemonCeilingRecord(
    val bootIdentity: String,
    val cpuStockRow: String?,
    /** Exactly what the daemon asked each CPU for, so the surface can tell ours from another writer's. */
    val cpuRequested: Map<Int, Int>,
    val gpuStockMinLevel: Int? = null,
    val gpuStockMaxLevel: Int? = null,
    val gpuRequestedMinLevel: Int? = null,
    val gpuRequestedMaxLevel: Int? = null,
) {
    fun isEmpty(): Boolean =
        (cpuStockRow == null || cpuRequested.isEmpty()) && (gpuStockMinLevel == null || gpuRequestedMinLevel == null)
}

/** Every answer the daemon can give, one type per outcome the contract names. Never collapsed into a boolean. */
sealed interface DaemonCeilingOutcome {
    /** The daemon wrote the ceiling. [inForceKhz] is what reads back, which may differ from [admittedKhz]. */
    data class Applied(
        val kind: MimirTunerPolicy.Kind,
        val policyId: Int,
        val requestedKhz: Int,
        val admittedKhz: Int,
        val inForceKhz: Int?,
        val record: DaemonCeilingRecord?,
    ) : DaemonCeilingOutcome

    data class Withdrawn(val restoredCpus: Int, val leftAloneCpus: Int, val gpuRestored: Boolean) : DaemonCeilingOutcome
    data object NothingHeld : DaemonCeilingOutcome
    data class Held(val record: DaemonCeilingRecord) : DaemonCeilingOutcome
    data object NotHeld : DaemonCeilingOutcome

    /** The daemon judged the request and declined; [code] is one of the contract's REFUSAL_* strings. */
    data class Refused(val code: String, val detail: String) : DaemonCeilingOutcome

    /** Nobody judged anything: unbound, timed out, old daemon, thrown transaction. */
    data class Unavailable(val reason: String) : DaemonCeilingOutcome
}

/**
 * The daemon's ceiling API, as the tuner writer calls it.
 *
 * **Threading contract:** every call is synchronous and must be made OFF the main thread. The bridge implements it
 * with the same reservation discipline as the v9 record: `apply`/`withdraw` take the exclusive mutation reservation,
 * `query` the settlement one, and neither unbinds a transport another owner is using.
 *
 * **What is bounded in the client, and what is not (round 5e).** This comment used to say every call was bounded,
 * and the writes were not. The reads are bounded: the V10 ceiling read behind `query` stops waiting after
 * `TweakerSession.CAPABILITY_CALL_DEADLINE_MS` (2,500 ms), as does the V9 discovered-session read the bridge makes at
 * cold start. The contract hands the daemon no deadline for either, so that budget is the client's own. The writes
 * behind `apply` and `withdraw` are not bounded in the client, because the contract hands the daemon no deadline for
 * them, and a client-only bound could report a write unconfirmed that the daemon still lands; the same is true of
 * every other privileged write this client makes. The reservation ask, the bind and the API-version read around
 * every call are bounded. A write the daemon never answers keeps the exclusive mutation reservation for as long as
 * it waits.
 */
interface DaemonTunerActuator {
    fun apply(kind: MimirTunerPolicy.Kind, policyId: Int, ceilingKhz: Int): DaemonCeilingOutcome
    fun withdraw(): DaemonCeilingOutcome
    fun query(): DaemonCeilingOutcome
}

/**
 * The process-scoped binding: the bridge installs, the writer consumes.
 *
 * Process-scoped for the reason `MimirTunerWrite.recordStore` and `MimirTunerBackend` are: it is one live
 * fact per process, and threading it through every signature would break call sites that disagree about
 * positional order. [presence] is written synchronously at cold start (pair identity), completed once the
 * daemon answers (API version, session truth), and refreshed on every later daemon answer, so the decision
 * always reflects the daemon's last word rather than a value cached at process start.
 */
object MimirTunerDaemonRoute {
    @Volatile
    var presence: MimirTunerDaemonPresence? = null

    @Volatile
    var actuator: DaemonTunerActuator? = null

    fun decision(): MimirTunerRouteDecision = mimirTunerRouteDecision(presence)

    /** Test seam. The device path never resets. */
    fun reset() {
        presence = null
        actuator = null
    }
}

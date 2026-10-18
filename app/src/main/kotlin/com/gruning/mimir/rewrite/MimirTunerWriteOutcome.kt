package com.gruning.mimir.rewrite

/**
 * What a ceiling request or a withdrawal actually did, and WHICH WRITER did it.
 *
 * **This type exists because the two old return values could not survive the route.** `requestCeiling`
 * returned `Int?` and `withdraw` returned `Boolean`, and both collapsed answers that the daemon route
 * makes genuinely different: `null` meant *"the frequency is not in the published table"* and *"the
 * write landed but the read-back was unreadable"*; `false` meant *"the host declined"* and, at one call
 * site, was rendered as *"NOTHING TO RELEASE"*. A daemon adds two more — *"I judged your request and
 * declined"* ([Refused], carrying the contract's own code) and *"nobody judged anything"*
 * ([Unavailable]) — and a surface that cannot tell a refusal from an unreachable transport will
 * eventually tell the player the wrong one.
 *
 * **[route] is carried, not re-derived.** `MimirTunerDaemonRoute.decision()` is read ONCE, at the top of
 * the call, and travels out with the answer, so a log line and a toast can never disagree about who
 * wrote — and so a caller can never ask a second time and get a second answer because the bridge
 * refreshed `presence` in between.
 */
sealed interface MimirTunerWriteOutcome {

    /** The decision that was in force when this call started. Never re-read at a call site. */
    val route: MimirTunerRouteDecision

    /**
     * A ceiling is in force.
     *
     * [khz] is the READ-BACK (`scaling_max_freq`, or the GPU's level resolved to kHz) and may be null
     * when the node could not be read; [admittedKhz] is what the writer actually asked the kernel for
     * after the table snap and, on the daemon route, its own clamp to the live floor. They are
     * different questions on this silicon — the file header of [MimirTunerWrite] records the Thor
     * reading 1 555 200 in force against a 2 016 000 request — so both are carried and neither is
     * allowed to stand in for the other.
     */
    data class InForce(
        val khz: Int?,
        val admittedKhz: Int?,
        override val route: MimirTunerRouteDecision,
    ) : MimirTunerWriteOutcome

    /**
     * Somebody judged the request and declined it. Nothing was written.
     *
     * [code] is one of the Tweaker contract's `REFUSAL_*` strings when the daemon answered, or
     * [MimirTunerRefusalRouteRefused] when the route itself declined before any transport was touched.
     */
    data class Refused(
        val code: String,
        val detail: String,
        override val route: MimirTunerRouteDecision,
    ) : MimirTunerWriteOutcome

    /** Nobody judged anything: no actuator, an unbound daemon, a thrown transaction, a declined host. */
    data class Unavailable(
        val reason: String,
        override val route: MimirTunerRouteDecision,
    ) : MimirTunerWriteOutcome

    /**
     * The device got back what it had.
     *
     * [leftAloneCpus] is not a failure count: the QoS node has another writer, so a CPU that has moved
     * since we wrote it is deliberately left where the other writer put it rather than stamped with our
     * stale capture.
     */
    data class Released(
        val restoredCpus: Int,
        val leftAloneCpus: Int,
        val gpuRestored: Boolean,
        override val route: MimirTunerRouteDecision,
    ) : MimirTunerWriteOutcome

    /** Nothing was held, so nothing was given back and nothing was said to the hardware. */
    data class NothingToRelease(
        override val route: MimirTunerRouteDecision,
    ) : MimirTunerWriteOutcome
}

/**
 * The route itself declined, before any transport was touched.
 *
 * Not a contract code: the daemon never saw this request. The reason is already spelled for the surface
 * by the seam ([MimirTunerRefusePairUnresolved] / [MimirTunerRefuseDaemonBusy]) and travels in
 * [MimirTunerWriteOutcome.Refused.detail].
 */
const val MimirTunerRefusalRouteRefused: String = "ROUTE_REFUSED"

/**
 * The daemon is running a named profile and owns the node for its duration.
 *
 * Spelled here rather than imported: `:app` depends on `:core-tweaker-client` with `implementation`,
 * which does not re-export `:mimir-tweaker-contract`, so the contract's `REFUSAL_PROFILE_ACTIVE` is not
 * on this module's compile classpath. The string is the boundary, and it is pinned by a test rather
 * than trusted to stay in step by memory.
 */
const val MimirTunerRefusalProfileActive: String = "PROFILE_ACTIVE"

/**
 * The request named a frequency this device never published.
 *
 * Reached only on the direct route, where `nearestSupportedKhz` is the single door a number can come
 * through; the daemon re-admits every value against its own discovery and answers the same code itself.
 */
const val MimirTunerRefusalUndiscoveredFrequency: String = "UNDISCOVERED_FREQUENCY"

/** `DIRECT` / `DAEMON` / `REFUSE` — the one spelling a device script greps for. */
internal fun mimirTunerRouteLabel(route: MimirTunerRouteDecision): String = when (route) {
    MimirTunerRouteDecision.Daemon -> "DAEMON"
    MimirTunerRouteDecision.Direct -> "DIRECT"
    is MimirTunerRouteDecision.Refuse -> "REFUSE"
}

/** One capped word per outcome, for a log line that has to stay greppable. */
internal fun MimirTunerWriteOutcome.outcomeLabel(): String = when (this) {
    is MimirTunerWriteOutcome.InForce -> "IN_FORCE"
    is MimirTunerWriteOutcome.Refused -> "REFUSED($code)"
    is MimirTunerWriteOutcome.Unavailable -> "UNAVAILABLE($reason)"
    is MimirTunerWriteOutcome.Released -> "RELEASED(cpus=$restoredCpus left=$leftAloneCpus gpu=$gpuRestored)"
    is MimirTunerWriteOutcome.NothingToRelease -> "NOTHING_TO_RELEASE"
}

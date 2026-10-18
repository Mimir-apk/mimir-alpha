package com.gruning.mimir.rewrite.core.tweaker

import android.os.Bundle

/**
 * API v9 — the discovered tuner's session, remembered by the daemon .
 *
 * **The daemon is the half that survives being killed; discovery is the half that works everywhere.**
 * Neither column alone is the product. This family is the join: the discovered tuner keeps its write,
 * and the daemon keeps the memory of what the device was holding before that write, so a cap that
 * outlives Mimir stops being a cap nothing can take back.
 *
 * Its own minimum API version, for the same reason v8 has one — and here the version check earns more
 * than convenience. A missing method surfaces as a failed Binder transaction, and this call's failure
 * mode must never be read as *"the daemon holds nothing"*; asking an older daemon at all is the
 * mistake, so the app checks the version before it asks.
 */
const val TWEAKER_V9_DISCOVERED_RECORD_MIN_API_VERSION = 9

/** `-1`: this record names no GPU session. Absence, never a level. Mirrors the daemon's own spelling. */
const val TWEAKER_DISCOVERED_ABSENT_LEVEL = -1

/** The discovered-axis session as it crosses the Binder, in the QoS node's own `cpuId:kHz` spelling. */
data class TweakerDiscoveredSession(
    val bootIdentity: String,
    val cpuStockRow: String,
    val cpuRequestedRow: String,
    val gpuStockMinLevel: Int = TWEAKER_DISCOVERED_ABSENT_LEVEL,
    val gpuStockMaxLevel: Int = TWEAKER_DISCOVERED_ABSENT_LEVEL,
    val gpuRequestedMinLevel: Int = TWEAKER_DISCOVERED_ABSENT_LEVEL,
    val gpuRequestedMaxLevel: Int = TWEAKER_DISCOVERED_ABSENT_LEVEL,
)

/**
 * What the daemon said when asked whether it holds a session.
 *
 * **Three answers, and the last two must never be collapsed.** *"The daemon holds nothing"* and *"I
 * could not ask the daemon"* look identical to a caller that models them as null, and they demand
 * opposite behaviour at cold start: the first means there is genuinely nothing to adopt, the second
 * means the evidence is not in yet and acting as though it were would destroy it.
 */
sealed interface TweakerDiscoveredSessionRead {
    data class Held(val session: TweakerDiscoveredSession) : TweakerDiscoveredSessionRead
    data object Absent : TweakerDiscoveredSessionRead
    data class Unavailable(val reason: String) : TweakerDiscoveredSessionRead
}

/**
 * What the daemon did with a session it was handed.
 *
 * **The refusal codes exist on the daemon side and were being thrown away on this one.** The service
 * admits every value against its own hardware discovery and puts a named reason in the bundle —
 * `UNDISCOVERED_CPU`, `UNDISCOVERED_FREQUENCY`, `MALFORMED_ROW`, `NO_BOOT_IDENTITY`, `NO_GPU_AXIS`,
 * `UNDISCOVERED_POWER_LEVEL` — and the client collapsed all six, plus "the service is not bound" and
 * "the transaction threw", into a single `false` that its one caller then discarded. The visible
 * consequence was that a refused record made adoption simply not happen, with nothing anywhere saying
 * why: the exact "mysteriously does nothing" failure the discovered axis was built to stop producing.
 *
 * **Refused and Unavailable are separate for the same reason [TweakerDiscoveredSessionRead] keeps them
 * separate.** A refusal is the daemon judging the record and is a defect in what we sent; an
 * unavailability is the transport, and is a defect in nothing. Collapsing them would send whoever reads
 * the log looking in the wrong half of the system.
 */
sealed interface TweakerDiscoveredSessionWrite {
    /** The daemon admitted the record and wrote it down. */
    data object Admitted : TweakerDiscoveredSessionWrite

    /** The daemon judged the record against its own discovery and declined to store it. */
    data class Refused(val code: String, val detail: String) : TweakerDiscoveredSessionWrite

    /** Nobody judged anything: an old daemon, an unbound service, or a thrown transaction. */
    data class Unavailable(val reason: String) : TweakerDiscoveredSessionWrite
}

internal fun parseTweakerDiscoveredSessionWriteBundle(
    bundle: Bundle,
    expectedApiVersion: Int,
): TweakerDiscoveredSessionWrite {
    val apiVersion = bundle.getInt("apiVersion", -1)
    if (apiVersion != expectedApiVersion) {
        return TweakerDiscoveredSessionWrite.Unavailable("Discovered-session API version mismatch")
    }
    if (bundle.getBoolean("success", false)) return TweakerDiscoveredSessionWrite.Admitted
    // The daemon names its reason; an unnamed refusal is still a refusal and must not be reported as a
    // transport problem, because the record genuinely was judged and genuinely was not stored.
    return TweakerDiscoveredSessionWrite.Refused(
        code = bundle.getString("code").orEmpty().ifBlank { "UNNAMED_REFUSAL" },
        detail = bundle.getString("detail").orEmpty(),
    )
}

internal fun parseTweakerDiscoveredSessionBundle(
    bundle: Bundle,
    expectedApiVersion: Int,
): TweakerDiscoveredSessionRead {
    val apiVersion = bundle.getInt("apiVersion", -1)
    if (apiVersion != expectedApiVersion) {
        return TweakerDiscoveredSessionRead.Unavailable("Discovered-session API version mismatch")
    }
    if (!bundle.getBoolean("success", false)) {
        return TweakerDiscoveredSessionRead.Unavailable("Discovered-session query refused")
    }
    if (!bundle.getBoolean("held", false)) return TweakerDiscoveredSessionRead.Absent
    val boot = bundle.getString("bootIdentity").orEmpty()
    if (boot.isBlank()) {
        // A record that cannot say which boot it belongs to cannot be safely adopted, and a daemon
        // that answered "held" without one is not describing a session this app can use.
        return TweakerDiscoveredSessionRead.Unavailable("Discovered session named no boot")
    }
    return TweakerDiscoveredSessionRead.Held(
        TweakerDiscoveredSession(
            bootIdentity = boot,
            cpuStockRow = bundle.getString("cpuStockRow").orEmpty(),
            cpuRequestedRow = bundle.getString("cpuRequestedRow").orEmpty(),
            gpuStockMinLevel = bundle.getInt("gpuStockMinLevel", TWEAKER_DISCOVERED_ABSENT_LEVEL),
            gpuStockMaxLevel = bundle.getInt("gpuStockMaxLevel", TWEAKER_DISCOVERED_ABSENT_LEVEL),
            gpuRequestedMinLevel = bundle.getInt("gpuRequestedMinLevel", TWEAKER_DISCOVERED_ABSENT_LEVEL),
            gpuRequestedMaxLevel = bundle.getInt("gpuRequestedMaxLevel", TWEAKER_DISCOVERED_ABSENT_LEVEL),
        ),
    )
}

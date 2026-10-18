package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.tweaker.TWEAKER_DISCOVERED_ABSENT_LEVEL
import com.gruning.mimir.rewrite.core.tweaker.TweakerDiscoveredSession
import com.gruning.mimir.rewrite.core.tweaker.TweakerDiscoveredSessionRead

/**
 * The daemon-backed record, as `MimirTunerWrite` sees it .
 *
 * **Why the read is answered from a value captured at bind time, rather than asked live.** The bridge
 * binds the daemon for the duration of one operation and unbinds again — that is how every other
 * Tweaker call in this app works, and a permanently bound session for a record that is consulted once
 * per process would be a second binding model for one feature. [read] is called exactly once, by the
 * cold-start reconcile, at the moment the bridge has the daemon bound anyway; so the answer is taken
 * there and handed to this object, and this object never has to bind anything to answer it.
 *
 * **The writes are fire-and-forget, and that is a deliberate trade.** [MimirTunerRecordStore.write] is
 * called from inside `requestCeiling`, on whatever thread moved the stepper, and a Binder round trip
 * there would put the daemon's latency in front of the player's control. So the snapshot is handed to
 * [submit], which the bridge performs on its own IO scope. The cost is stated rather than hidden: if
 * the process dies in the window between the write landing on the node and the record landing in the
 * daemon, the next cold start sees no record and widens to the silicon's ceilings — exactly today's
 * behaviour, which is the floor this change can never fall below.
 */
internal class DaemonTunerRecordStore(
    /** What the daemon answered while the cold-start reconcile had it bound. */
    private val atBind: MimirTunerRecordRead,
    /** Perform the record change off the caller's thread; null means "forget the session". */
    private val submit: (MimirTunerSnapshot?) -> Unit,
) : MimirTunerRecordStore {

    @Volatile
    private var consumed = false

    /**
     * The bind-time answer, once.
     *
     * A second read cannot be served honestly — the session may have moved since, and this object has
     * no transport of its own — so it reports [MimirTunerRecordRead.Unavailable], which every caller
     * already treats as *"could not ask"* rather than *"holds nothing"*.
     */
    override fun read(): MimirTunerRecordRead {
        if (consumed) return MimirTunerRecordRead.Unavailable
        consumed = true
        return atBind
    }

    override fun write(snapshot: MimirTunerSnapshot): Boolean {
        submit(snapshot)
        return true
    }

    override fun clear(): Boolean {
        submit(null)
        return true
    }
}

/** `MimirTunerSnapshot` → the wire shape. The two differ only in how they spell absence. */
internal fun MimirTunerSnapshot.toTweakerDiscoveredSession(): TweakerDiscoveredSession =
    TweakerDiscoveredSession(
        bootIdentity = bootIdentity,
        cpuStockRow = cpuStockRow.orEmpty(),
        cpuRequestedRow = MimirTunerWrite.formatCpuQosRow(cpuRequested),
        gpuStockMinLevel = gpuStockMinLevel ?: TWEAKER_DISCOVERED_ABSENT_LEVEL,
        gpuStockMaxLevel = gpuStockMaxLevel ?: TWEAKER_DISCOVERED_ABSENT_LEVEL,
        gpuRequestedMinLevel = gpuRequestedMinLevel ?: TWEAKER_DISCOVERED_ABSENT_LEVEL,
        gpuRequestedMaxLevel = gpuRequestedMaxLevel ?: TWEAKER_DISCOVERED_ABSENT_LEVEL,
    )

/**
 * The wire shape → what the cold start reconciles against.
 *
 * The three-way distinction is preserved end to end on purpose: the client returns `Unavailable` for
 * an unbound service, an old daemon and a thrown transaction alike, and this keeps that separate from
 * the daemon genuinely answering that it holds nothing.
 */
internal fun TweakerDiscoveredSessionRead.toTunerRecordRead(): MimirTunerRecordRead = when (this) {
    is TweakerDiscoveredSessionRead.Unavailable -> MimirTunerRecordRead.Unavailable
    TweakerDiscoveredSessionRead.Absent -> MimirTunerRecordRead.Absent
    is TweakerDiscoveredSessionRead.Held -> MimirTunerRecordRead.Held(
        MimirTunerSnapshot(
            bootIdentity = session.bootIdentity,
            cpuStockRow = session.cpuStockRow.takeIf { it.isNotBlank() },
            cpuRequested = MimirTunerWrite.parseCpuQosRow(session.cpuRequestedRow),
            gpuStockMinLevel = session.gpuStockMinLevel.takeIf { it != TWEAKER_DISCOVERED_ABSENT_LEVEL },
            gpuStockMaxLevel = session.gpuStockMaxLevel.takeIf { it != TWEAKER_DISCOVERED_ABSENT_LEVEL },
            gpuRequestedMinLevel = session.gpuRequestedMinLevel.takeIf { it != TWEAKER_DISCOVERED_ABSENT_LEVEL },
            gpuRequestedMaxLevel = session.gpuRequestedMaxLevel.takeIf { it != TWEAKER_DISCOVERED_ABSENT_LEVEL },
        ),
    )
}

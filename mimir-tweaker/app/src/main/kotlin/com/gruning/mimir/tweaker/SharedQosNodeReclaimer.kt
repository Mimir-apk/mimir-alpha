package com.gruning.mimir.tweaker

/**
 * What [SharedQosNodeReclaimer.reclaimIfOrphaned] found and did. [wire] is the token the per-session
 * transport log line carries.
 */
internal sealed class QosReclaimOutcome(val wire: String) {
    /**
     * The node genuinely is not there: the existence probe answered "no such file" (or this device has no CPU policies
     * to name). Nothing to reclaim; the transport line names why. This is a POSITIVE answer, never the result of a read
     * that failed or timed out -- that is [NodeUnreadable].
     */
    data object NodeAbsent : QosReclaimOutcome("NODE_ABSENT")

    /**
     * The node is there (or may be) but its row could not be read: the privileged read timed out or failed, came back
     * empty, or the existence probe itself could not answer. Nobody knows whether it is held, so the apply is REFUSED
     * (`CPU_TRANSPORT_UNCONFIRMED`) rather than falling back to the vendor-reasserted `scaling_max_freq`.
     */
    data class NodeUnreadable(val reason: String) : QosReclaimOutcome("NODE_UNREADABLE $reason")

    /** Mode is not Mimir's held mode and every policy CPU reads unpinned. */
    data object NotHeld : QosReclaimOutcome("NOT_HELD")

    /**
     * Some policy CPU reads pinned but the mode is not [HELD_CPU_QOS_MODE]: the vendor HAL's own value
     * (a thermal or power-mode cap) at its own mode. Not Mimir's hold signature, so not ours to overwrite.
     */
    data class ForeignPinned(val detail: String) : QosReclaimOutcome("FOREIGN_PINNED $detail")

    /** Held, but a live session or journal owns it. Not ours to touch. */
    data class Owned(val by: String) : QosReclaimOutcome("OWNED_BY_$by")

    /** Held by nobody; unpinned and 644 again, confirmed by readback. */
    data class Reclaimed(val detail: String) : QosReclaimOutcome("RECLAIMED $detail")

    /** Held by nobody and could not be put back. The apply must not silently demote past this. */
    data class Unreclaimable(val reason: String) : QosReclaimOutcome("HELD_UNRECLAIMABLE $reason")
}

/** What the existence probe says about one node. Only [ABSENT] is a positive "there is no such node". */
internal enum class NodePresence { PRESENT, ABSENT, UNKNOWN }

/** Answers whether a node exists, distinguishing "no such file" from "could not tell". Never reads the node. */
internal fun interface NodePresenceProbe {
    fun probe(path: String): NodePresence
}

/**
 * **Reclaim, do not demote.** A shared QoS node left held by a session that never restored it (process
 * kill, `install -r` mid-session, crash) used to leave the NEXT probe with a map it could not select the
 * transport on, which silently demoted every later apply to per-policy `scaling_max_freq` -- the node the
 * vendor performance server reasserts. That is the Nova's 09-30 `TARGET_CONTRACT_DRIFT_CPU` churn
 * (observed values were vendor `scaling_max_freq` numbers); a reboot clears sysfs, which is why the next
 * morning worked.
 *
 * A node is HELD when its mode is [HELD_CPU_QOS_MODE] (Mimir's own constant -- nothing else sets it). It is
 * only reclaimed when [ownerOrNull] says no session, journal, or recovery owns it: an owned node is
 * somebody's contract. A node pinned at any OTHER mode is the vendor's own cap (thermal, power mode), and
 * overwriting it would defeat that cap: [QosReclaimOutcome.ForeignPinned], nothing touched, and transport
 * selection proceeds as before.
 *
 * **No new surface.** The path is the compile-time [SHARED_CPU_QOS_CEILING_PATH]; the value is the
 * compile-time [UNPINNED_CPU_CEILING] (exactly what [restoreWriteValue] writes for Stock); the mode is
 * [UNHELD_CPU_QOS_MODE] (exactly what [restoreWriteMode] substitutes). The only variable is which CPU
 * ids are named, and those come from this process's own policy discovery -- the same ids
 * `AndroidTransactionPorts.execute` writes per policy. Written through the same un-fenced
 * [CeilingCommandPort] the discovered-ceiling withdraw uses: this is a give-back, not a transaction.
 *
 * Fail closed: the write is only believed once a fresh read shows every policy CPU unpinned AND the mode
 * reads back [UNHELD_CPU_QOS_MODE]. Anything else is [QosReclaimOutcome.Unreclaimable] and the caller
 * refuses the apply with the reason on the log.
 */
internal class SharedQosNodeReclaimer(
    private val nodes: CeilingNodeReader,
    private val modeOf: (String) -> String?,
    private val presenceOf: NodePresenceProbe,
    private val commands: CeilingCommandPort,
    private val ownerOrNull: () -> String?,
    private val log: (String) -> Unit = {},
) {
    fun reclaimIfOrphaned(policies: List<CpuPolicy>): QosReclaimOutcome {
        val path = SHARED_CPU_QOS_CEILING_PATH
        val raw = nodes.read(path)
        if (raw.isNullOrBlank()) {
            // A null here is a read that failed or timed out as often as it is a node that is not there, so it is
            // never read as "absent" on its own. Only the existence probe saying "no such file" is.
            return when (val presence = presenceOf.probe(path)) {
                NodePresence.ABSENT -> QosReclaimOutcome.NodeAbsent
                NodePresence.PRESENT, NodePresence.UNKNOWN -> {
                    val reason = if (presence == NodePresence.PRESENT) {
                        "the node exists but its read returned nothing"
                    } else {
                        "the node's existence could not be determined and its read returned nothing"
                    }
                    log("QOS_RECLAIM unreadable $reason")
                    QosReclaimOutcome.NodeUnreadable(reason)
                }
            }
        }
        val cpuIds = policies.flatMap(CpuPolicy::cpuIds).distinct().sorted()
        if (cpuIds.isEmpty()) return QosReclaimOutcome.NodeAbsent
        val mode = modeOf(path)
        val parsed = CpuCeilingTransport.parse(raw)
        val pinned = cpuIds.filter { id -> (parsed?.get(id) ?: UNPINNED_CPU_CEILING) < UNPINNED_CPU_CEILING }
        val modeHeld = mode == HELD_CPU_QOS_MODE
        val held = "mode=$mode pinned=${pinned.associateWith { parsed?.get(it) }}"
        if (!modeHeld) {
            if (pinned.isEmpty()) return QosReclaimOutcome.NotHeld
            log("QOS_RECLAIM skipped foreign-pinned $held")
            return QosReclaimOutcome.ForeignPinned(held)
        }
        ownerOrNull()?.let { owner ->
            log("QOS_RECLAIM skipped owner=$owner $held")
            return QosReclaimOutcome.Owned(owner)
        }

        val failure = deliver(path, cpuIds)
        val readback = verify(path, cpuIds)
        val reason = failure ?: readback
        if (reason != null) {
            log("QOS_RECLAIM failed was[$held] reason=$reason")
            return QosReclaimOutcome.Unreclaimable(reason)
        }
        log("QOS_RECLAIM reclaimed was[$held] now=unpinned/$UNHELD_CPU_QOS_MODE")
        return QosReclaimOutcome.Reclaimed(held)
    }

    /** Null when every step was CONFIRMED. The closing chmod is attempted even after a failed write. */
    private fun deliver(path: String, cpuIds: List<Int>): String? {
        var failure: String? = null
        fun step(label: String, command: String): Boolean {
            val result = runCatching { commands.execute(command) }.getOrElse {
                if (failure == null) failure = "$label ${it.javaClass.simpleName}"
                return false
            }
            if (result.status != CheckedCommandStatus.CONFIRMED) {
                if (failure == null) failure = "$label ${result.status}: ${result.detail}"
                return false
            }
            return true
        }
        val opened = step("chmod-666", "chmod 666 $path")
        if (opened) {
            for (cpuId in cpuIds) {
                if (!step("write-cpu-$cpuId", "echo $cpuId:$UNPINNED_CPU_CEILING > $path")) break
            }
        }
        step("restore-mode", "chmod $UNHELD_CPU_QOS_MODE $path")
        return failure
    }

    /** Null when a FRESH read shows the policy CPUs unpinned and the mode back to [UNHELD_CPU_QOS_MODE]. */
    private fun verify(path: String, cpuIds: List<Int>): String? {
        val parsed = CpuCeilingTransport.parse(nodes.read(path))
            ?: return "readback unparseable"
        cpuIds.firstOrNull { parsed[it] != UNPINNED_CPU_CEILING }?.let {
            return "readback cpu $it=${parsed[it]}"
        }
        val mode = modeOf(path)
        if (mode != UNHELD_CPU_QOS_MODE) return "readback mode=$mode"
        return null
    }
}

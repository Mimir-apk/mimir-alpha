package com.gruning.mimir.rewrite.core.companionnative

import java.util.UUID

internal data class SecondaryCloseRequest(
    val packageName: String,
    val operationId: UUID,
    /**
     * The secondary-display task id tracked for this package, or null when none is known.
     *
     * Null is the normal case for a tab restored from disk: [SecondaryTabsCodec] persists no task id,
     * and a task id from a previous boot would not identify the same task anyway. A null here means
     * "no task to remove", never "the task is gone".
     */
    val taskId: Int? = null,
)

/** Pure launched-session and close-evidence ledger. */
internal class SecondaryTaskLedger(
    private val operationIdFactory: () -> UUID = UUID::randomUUID,
) {
    private val tracked = linkedMapOf<String, AppEntry>()

    /**
     * Process-local task ids for tracked packages.
     *
     * Deliberately not part of [AppEntry] and deliberately not persisted: a task id is only meaningful
     * for the boot that issued it, so a restored tab must come back with none.
     */
    private val taskIds = mutableMapOf<String, Int>()

    fun values(): List<AppEntry> = tracked.values.toList()

    fun taskId(packageName: String): Int? = taskIds[packageName]

    /** Records the task id for a tracked package. An untracked package is ignored, not invented. */
    fun noteTaskId(packageName: String, taskId: Int): Boolean {
        if (taskId <= 0 || packageName !in tracked) return false
        taskIds[packageName] = taskId
        return true
    }

    /**
     * Oldest to newest: [noteLaunched] re-files an app at the newest end, so this list is the
     * deterministic "opened on the Bottom" order.
     */
    fun ordered(): List<AppEntry> = tracked.values.toList()

    /** Restore in supplied order as OPEN without replacing packages already tracked in this process. */
    fun restore(entries: List<AppEntry>) {
        entries.forEach { entry ->
            if (entry.packageName !in tracked) noteLaunched(entry)
        }
    }

    fun entry(packageName: String): AppEntry? = tracked[packageName]

    fun noteLaunched(entry: AppEntry) {
        val existing = tracked.remove(entry.packageName)
        tracked[entry.packageName] = entry.carryingGameIdentityFrom(existing).copy(
            closeState = SecondaryTaskCloseState.OPEN,
            closeOperationId = null,
            closeRequestedAtElapsedRealtimeMs = null,
        )
    }

    /**
     * Enrich a tracked GAME tab with the library identity the dispatch could not carry.
     *
     * The registry learns the emulator package from the dispatched Intent; title/id/art are resolved by
     * the app against live library truth and arrive after. Only a GAME row accepts them, and only a
     * non-blank value overwrites — a later enrichment that knows less must not erase what is shown.
     */
    fun noteGameIdentity(packageName: String, title: String?, gameId: String?, artRef: String?): Boolean {
        val existing = tracked[packageName]?.takeIf { it.kind == SecondaryTaskKind.GAME } ?: return false
        val next = existing.copy(
            gameTitle = title?.trim()?.takeIf(String::isNotEmpty) ?: existing.gameTitle,
            gameId = gameId?.trim()?.takeIf(String::isNotEmpty) ?: existing.gameId,
            gameArtRef = artRef?.trim()?.takeIf(String::isNotEmpty) ?: existing.gameArtRef,
        )
        if (next == existing) return false
        tracked[packageName] = next
        return true
    }

    /**
     * A reopen from the tab manager is an ordinary secondary-app launch — that is the whole point, since
     * relaunching the emulator package brings its existing task (with the running game on top) forward.
     * It therefore arrives here with no game identity, and must not downgrade the tab it is reopening.
     */
    private fun AppEntry.carryingGameIdentityFrom(existing: AppEntry?): AppEntry {
        if (kind == SecondaryTaskKind.GAME || existing?.kind != SecondaryTaskKind.GAME) return this
        return copy(
            kind = SecondaryTaskKind.GAME,
            gameTitle = existing.gameTitle,
            gameId = existing.gameId,
            gameArtRef = existing.gameArtRef,
        )
    }

    fun requestClose(packageName: String, requestedAtElapsedRealtimeMs: Long): SecondaryCloseRequest? {
        val existing = tracked[packageName] ?: return null
        val operationId = operationIdFactory().also {
            require(it != EMPTY_UUID) { "Close operation id must be non-empty" }
        }
        tracked[packageName] = existing.copy(
            closeState = SecondaryTaskCloseState.CLOSE_REQUESTED_UNVERIFIED,
            closeOperationId = operationId,
            closeRequestedAtElapsedRealtimeMs = requestedAtElapsedRealtimeMs,
        )
        return SecondaryCloseRequest(packageName, operationId, taskIds[packageName])
    }

    fun markCloseUnconfirmed(operationId: UUID): Boolean {
        val match = tracked.values.firstOrNull {
            it.closeOperationId == operationId &&
                it.closeState == SecondaryTaskCloseState.CLOSE_REQUESTED_UNVERIFIED
        } ?: return false
        tracked[match.packageName] = match.copy(closeState = SecondaryTaskCloseState.CLOSE_UNCONFIRMED)
        return true
    }

    /**
     * The one terminal transition a close request may take on its own evidence.
     *
     * [SecondaryTaskCloseState] has no CONFIRMED member, and the advisory observations below stay
     * non-terminal on purpose. This is not one of them: it is only reachable from a privileged
     * readback that proved the package has no live process, or from a privileged task eviction that
     * proved the tab's task is gone -- the "certified terminal evidence" the close contract names. The
     * operation-id guard mirrors [markCloseUnconfirmed], so a late verification for a superseded close
     * cannot remove the row a newer close is still working on.
     */
    fun markCloseVerified(operationId: UUID): Boolean {
        val match = tracked.values.firstOrNull {
            it.closeOperationId == operationId &&
                it.closeState == SecondaryTaskCloseState.CLOSE_REQUESTED_UNVERIFIED
        } ?: return false
        taskIds.remove(match.packageName)
        return tracked.remove(match.packageName) != null
    }

    fun observe(observation: SecondaryCloseObservation): Boolean = when (observation) {
        is SecondaryCloseObservation.PackageRemoved -> {
            taskIds.remove(observation.packageName)
            tracked.remove(observation.packageName) != null
        }
        is SecondaryCloseObservation.ProcessAbsentVerified -> retireVerifiedAbsent(observation.packageName)
        is SecondaryCloseObservation.RequestDispatched,
        is SecondaryCloseObservation.RunningProcessAbsent,
        is SecondaryCloseObservation.UsageStatsBackground,
        -> false
    }

    /**
     * Applies a liveness reconcile's ABSENT answers, on the thread that owns this ledger.
     *
     * [probed] are the rows [secondaryTabsToRetire] found absent, as they were when asked. Each one is retired
     * through [SecondaryCloseObservation.ProcessAbsentVerified] only while it is still the row that was asked
     * about -- a launch re-files a row with a new `lastSeenAtMs`, and an older answer must not retire it -- and,
     * for a GAME row, only while [bottomGameLaunchInFlight] says no Bottom game launch is starting for that
     * package. Returns the rows retired, in [probed] order.
     */
    fun retireProbedAbsent(
        probed: List<AppEntry>,
        bottomGameLaunchInFlight: (packageName: String) -> Boolean,
    ): List<AppEntry> = probed.filter { row ->
        val current = tracked[row.packageName] ?: return@filter false
        current.lastSeenAtMs == row.lastSeenAtMs &&
            !(current.kind == SecondaryTaskKind.GAME && bottomGameLaunchInFlight(row.packageName)) &&
            observe(SecondaryCloseObservation.ProcessAbsentVerified(row.packageName))
    }

    /**
     * The `tabs.v1` document for this ledger: its OPEN tabs, oldest to newest. The registry writes exactly this
     * on every publish, so a test that decodes it reads what the device persists.
     */
    fun persistedTabsDocument(): String =
        SecondaryTabsCodec.encode(ordered().filter { it.closeState == SecondaryTaskCloseState.OPEN })

    /**
     * Retires a tab the privileged liveness readback proved has no process left.
     *
     * Same evidence class as [markCloseVerified], for a row nobody asked to close, so there is no
     * operation id to match. It refuses a row a close request already owns, whose own evidence path (a
     * verified close or the unconfirmed timeout) stays in charge, instead of trusting every caller to have
     * filtered it. A GAME row retires like an APP row (Thor, 2026-09-12): the registry that reads the probe
     * spares a launch still starting ([retireProbedAbsent]) and settles what the row's ✕ settles besides
     * the row, the Bottom game session.
     */
    private fun retireVerifiedAbsent(packageName: String): Boolean {
        val row = tracked[packageName] ?: return false
        if (row.closeState != SecondaryTaskCloseState.OPEN) return false
        taskIds.remove(packageName)
        return tracked.remove(packageName) != null
    }

    fun forget(packageName: String): Boolean {
        taskIds.remove(packageName)
        return tracked.remove(packageName) != null
    }

    private companion object {
        val EMPTY_UUID: UUID = UUID(0L, 0L)
    }
}

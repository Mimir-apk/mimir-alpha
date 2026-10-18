package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.companionnative.AppEntry
import com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.CompanionRunningApp
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID

internal data class CompanionRunningAppTransfer(
    val operationId: UUID,
    val app: CompanionRunningApp,
    val startedAtEpochMs: Long,
    val stagedAtMonotonicMs: Long,
    val hostLaunchClaimed: Boolean = false,
    val confirmationDeadlineMonotonicMs: Long? = null,
)

/** A confirmed transfer session as the ledger holds it: read back from the ledger on every use, never stored here. */
private data class CompanionConfirmedTransfer(
    val operationId: UUID,
    val packageName: String,
    val startedAtEpochMs: Long,
)

/** A destroyed covered host's ticket naming one exact ledger session; it settles nothing until Main resumes. */
private data class CompanionPrimaryHostOrphan(
    val operationId: UUID,
    val packageName: String,
)

private data class CompanionSecondaryReturn(
    val primaryOperationId: UUID,
    val secondaryOperationId: UUID,
    val packageName: String,
)

internal enum class PrimaryHostOrphanSettlement {
    REJECTED,
    MARKED,
    CLEARED,
}

/**
 * The display-0 transfer protocol for an Android app sent from screen 2 to the main display.
 *
 * The session ledger is the sole record: what occupies Main is recorded in the session ledger
 * ([MimirSessionAuthority]) and nowhere else. This object keeps only the protocol around a transfer — the staged
 * transfer and its deadlines, the armed Bottom return, the orphan ticket — and answers every question about the
 * confirmed app on Main by reading the ledger. [running], the Main card, is a read-only view of that ledger; nothing
 * here writes it.
 */
internal object CompanionRunningAppState {
    private var pendingTransfer: CompanionRunningAppTransfer? = null
    private var pendingSecondaryReturn: CompanionSecondaryReturn? = null
    private var orphanedPrimaryHost: CompanionPrimaryHostOrphan? = null
    private var mainResumed: Boolean = false

    /** The Main card: the ledger's proven Main occupant ([provenPrimaryOccupant]). Read-only. */
    val running: StateFlow<CompanionRunningApp?> = LedgerMainCardView

    @Synchronized
    fun set(app: CompanionRunningApp, startedAtEpochMs: Long = System.currentTimeMillis()): Boolean {
        val semanticOperation = primaryAppSessionOperation(UUID.randomUUID())
        if (!MimirSessionAuthority.appPreparing(
                operationId = semanticOperation,
                app = app,
                placement = MimirAppPlacement.PRIMARY,
                returnAnchor = CompanionPrimaryOriginStore.current(),
                startedAtEpochMs = startedAtEpochMs,
            )
        ) {
            return false
        }
        pendingTransfer = null
        pendingSecondaryReturn = null
        orphanedPrimaryHost = null
        return MimirSessionAuthority.appForegroundProven(semanticOperation)
    }

    /** Stage dispatch intent without claiming that the app reached Main. */
    @Synchronized
    fun stageTransfer(
        operationId: UUID,
        app: CompanionRunningApp,
        startedAtEpochMs: Long = System.currentTimeMillis(),
        nowMonotonicMs: Long = monotonicMillis(),
    ): Boolean {
        require(operationId != UUID(0L, 0L)) { "Transfer operation id must be non-empty" }
        val current = pendingTransfer
        if (current != null &&
            (current.confirmationDeadlineMonotonicMs?.let { nowMonotonicMs <= it }
                ?: (nowMonotonicMs <= current.stagedAtMonotonicMs + PRIMARY_TRANSFER_STAGING_WINDOW_MILLIS))
        ) {
            return false
        }
        if (current != null) {
            pendingTransfer = null
            MimirSessionAuthority.appFailed(
                primaryAppSessionOperation(current.operationId),
                "staging_deadline",
            )
        }
        val semanticOperation = primaryAppSessionOperation(operationId)
        if (!MimirSessionAuthority.appPreparing(
                operationId = semanticOperation,
                app = app,
                placement = MimirAppPlacement.PRIMARY,
                returnAnchor = CompanionPrimaryOriginStore.current(),
                startedAtEpochMs = startedAtEpochMs,
            )
        ) {
            return false
        }
        pendingSecondaryReturn = null
        pendingTransfer = CompanionRunningAppTransfer(
            operationId = operationId,
            app = app,
            startedAtEpochMs = startedAtEpochMs,
            stagedAtMonotonicMs = nowMonotonicMs,
        )
        return true
    }

    /** Reserve the exact still-live staged operation before any external Activity can be foregrounded. */
    @Synchronized
    fun claimPrimaryHostLaunch(
        operationId: UUID,
        packageName: String,
        nowMonotonicMs: Long = monotonicMillis(),
    ): Boolean {
        val transfer = pendingTransfer ?: return false
        if (transfer.operationId != operationId || transfer.app.packageName != packageName.trim()) return false
        if (transfer.hostLaunchClaimed || transfer.confirmationDeadlineMonotonicMs != null) return false
        if (nowMonotonicMs > transfer.stagedAtMonotonicMs + PRIMARY_TRANSFER_STAGING_WINDOW_MILLIS) {
            pendingTransfer = null
            MimirSessionAuthority.appFailed(primaryAppSessionOperation(operationId), "staging_deadline")
            return false
        }
        pendingTransfer = transfer.copy(hostLaunchClaimed = true)
        return true
    }

    /** Start the slow-device coverage budget only when the display-0 host is ready to dispatch the target. */
    @Synchronized
    fun beginPrimaryHostDispatch(
        operationId: UUID,
        packageName: String,
        nowMonotonicMs: Long = monotonicMillis(),
    ): Boolean {
        val transfer = pendingTransfer ?: return false
        if (transfer.operationId != operationId || transfer.app.packageName != packageName.trim()) return false
        if (!transfer.hostLaunchClaimed || transfer.confirmationDeadlineMonotonicMs != null) return false
        pendingTransfer = transfer.copy(
            confirmationDeadlineMonotonicMs = nowMonotonicMs + PRIMARY_TRANSFER_COVERAGE_WINDOW_MILLIS,
        )
        MimirSessionAuthority.appDispatched(primaryAppSessionOperation(operationId))
        return true
    }

    @Synchronized
    fun cancelTransfer(operationId: UUID): Boolean {
        if (pendingTransfer?.operationId != operationId) return false
        pendingTransfer = null
        pendingSecondaryReturn = null
        MimirSessionAuthority.appFailed(primaryAppSessionOperation(operationId), "transfer_cancelled")
        return true
    }

    /** Publish only when the operation-owned host on display 0 is covered after dispatching this package. */
    @Synchronized
    fun confirmTransferFromPrimaryHost(
        operationId: UUID,
        packageName: String,
        nowMonotonicMs: Long = monotonicMillis(),
    ): CompanionRunningApp? {
        val transfer = pendingTransfer ?: return null
        if (transfer.operationId != operationId || transfer.app.packageName != packageName.trim()) return null
        val deadline = transfer.confirmationDeadlineMonotonicMs ?: return null
        if (nowMonotonicMs > deadline) {
            pendingTransfer = null
            MimirSessionAuthority.appFailed(primaryAppSessionOperation(operationId), "coverage_deadline")
            return null
        }
        pendingTransfer = null
        // O7 / I3: coverage publishes nothing of its own. The ledger proves this session or the app stays off the Main
        // card; a session the ledger has already ended, failed or superseded is not brought back by a late cover.
        if (!MimirSessionAuthority.appForegroundProven(primaryAppSessionOperation(operationId))) return null
        orphanedPrimaryHost = null
        return transfer.app
    }

    @Synchronized
    fun hasPendingTransfer(operationId: UUID): Boolean = pendingTransfer?.operationId == operationId

    /** Clear only matching, non-stale return evidence; a prior same-package session cannot clear a newer one. */
    @Synchronized
    fun clear(expectedPackageName: String? = null, observedAtEpochMs: Long? = null): Boolean {
        val session = confirmedTransferLocked() ?: return false
        if (expectedPackageName != null && session.packageName != expectedPackageName.trim()) return false
        if (observedAtEpochMs != null && observedAtEpochMs <= session.startedAtEpochMs) return false
        pendingSecondaryReturn = null
        orphanedPrimaryHost = null
        return endTransferSessionLocked(session.operationId)
    }

    /** The transfer host's return can clear only the exact session it previously confirmed. */
    @Synchronized
    fun clearTransferSession(operationId: UUID, packageName: String): Boolean {
        val session = confirmedTransferLocked() ?: return false
        if (session.operationId != operationId || session.packageName != packageName.trim()) return false
        pendingSecondaryReturn = null
        orphanedPrimaryHost = null
        return endTransferSessionLocked(operationId)
    }

    /** Arm an exact secondary-host operation against the primary session current at request time. */
    @Synchronized
    fun armSecondaryReturn(secondaryOperationId: UUID, packageName: String): Boolean {
        if (pendingTransfer != null) return false
        val session = confirmedTransferLocked() ?: return false
        if (session.packageName != packageName.trim()) return false
        pendingSecondaryReturn = CompanionSecondaryReturn(
            primaryOperationId = session.operationId,
            secondaryOperationId = secondaryOperationId,
            packageName = session.packageName,
        )
        return true
    }

    /** Clear only after the exact armed host has actually covered screen 2. */
    @Synchronized
    fun confirmSecondaryReturn(secondaryOperationId: UUID, packageName: String): Boolean {
        val pending = pendingSecondaryReturn ?: return false
        if (pending.secondaryOperationId != secondaryOperationId || pending.packageName != packageName.trim()) {
            return false
        }
        val session = confirmedTransferLocked()
        if (session == null ||
            session.operationId != pending.primaryOperationId ||
            session.packageName != pending.packageName
        ) {
            pendingSecondaryReturn = null
            return false
        }
        pendingSecondaryReturn = null
        orphanedPrimaryHost = null
        return endTransferSessionLocked(session.operationId)
    }

    /** A destroyed covered host is only a ticket to clear later when Main is actually resumed. */
    @Synchronized
    fun markPrimaryHostOrphaned(operationId: UUID, packageName: String): PrimaryHostOrphanSettlement {
        val session = confirmedTransferLocked() ?: return PrimaryHostOrphanSettlement.REJECTED
        if (session.operationId != operationId || session.packageName != packageName.trim()) {
            return PrimaryHostOrphanSettlement.REJECTED
        }
        if (!mainResumed) {
            orphanedPrimaryHost = CompanionPrimaryHostOrphan(session.operationId, session.packageName)
            return PrimaryHostOrphanSettlement.MARKED
        }
        pendingSecondaryReturn = null
        orphanedPrimaryHost = null
        endTransferSessionLocked(session.operationId)
        return PrimaryHostOrphanSettlement.CLEARED
    }

    @Synchronized
    fun noteMainResumed(): Boolean {
        mainResumed = true
        val orphan = orphanedPrimaryHost ?: return false
        val session = confirmedTransferLocked()
        if (session == null || session.operationId != orphan.operationId || session.packageName != orphan.packageName) {
            orphanedPrimaryHost = null
            return false
        }
        pendingSecondaryReturn = null
        orphanedPrimaryHost = null
        return endTransferSessionLocked(session.operationId)
    }

    @Synchronized
    fun noteMainPaused() {
        mainResumed = false
    }

    @Synchronized
    fun cancelSecondaryReturn(secondaryOperationId: UUID, packageName: String): Boolean {
        val pending = pendingSecondaryReturn ?: return false
        if (pending.secondaryOperationId != secondaryOperationId || pending.packageName != packageName.trim()) {
            return false
        }
        pendingSecondaryReturn = null
        return true
    }

    /** Activity-owned reset for a proven return to Mimir; also cancels an unconfirmed dispatch. */
    @Synchronized
    fun reset(): Boolean {
        val pending = pendingTransfer
        val confirmed = confirmedTransferLocked()
        val changed = pending != null || confirmed != null || pendingSecondaryReturn != null
        pendingTransfer = null
        pendingSecondaryReturn = null
        orphanedPrimaryHost = null
        mainResumed = false
        pending?.let {
            MimirSessionAuthority.appFailed(primaryAppSessionOperation(it.operationId), "process_reset")
        }
        confirmed?.let { endTransferSessionLocked(it.operationId) }
        return changed
    }

    /**
     * The ledger's display-0 transfer session whose coverage the ledger has proven, or null. Read from the ledger on
     * every call: a session the ledger ended by any route — a Main app moved to the Bottom, Main's own return, a game
     * that superseded it — is simply not here any more.
     */
    private fun confirmedTransferLocked(): CompanionConfirmedTransfer? {
        val session = MimirSessionAuthority.state.value.activePrimaryAppSession()
            ?.takeIf(MimirAppSession::foregroundProven)
            ?: return null
        val operationId = primaryAppSessionTransferId(session.operationId) ?: return null
        return CompanionConfirmedTransfer(operationId, session.packageName, session.startedAtEpochMs)
    }

    /** End one exact transfer session in the ledger; false when the ledger no longer holds it active. */
    private fun endTransferSessionLocked(operationId: UUID): Boolean {
        val operation = primaryAppSessionOperation(operationId)
        MimirSessionAuthority.appReturning(operation)
        return MimirSessionAuthority.appEnded(operation)
    }
}

/**
 * O7 / I3: the Main card as a read-only view of the session ledger. [value] reads the ledger when it is asked and
 * [collect] follows the ledger, so the card can never hold an app the ledger does not; there is no setter to call.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private object LedgerMainCardView : StateFlow<CompanionRunningApp?> {
    override val value: CompanionRunningApp?
        get() = MimirSessionAuthority.state.value.provenPrimaryOccupant()

    override val replayCache: List<CompanionRunningApp?>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<CompanionRunningApp?>): Nothing {
        MimirSessionAuthority.state
            .map { it.provenPrimaryOccupant() }
            .distinctUntilChanged()
            .collect(collector)
        awaitCancellation()
    }
}

/** Resolve card metadata from the same tracked-task and native-shelf data used by the tab drawer. */
internal fun companionRunningAppMetadata(
    packageName: String,
    trackedApps: List<AppEntry>,
    nativeSnapshot: NativeShelfSnapshot,
    cachedIconPaths: Map<String, String> = emptyMap(),
): CompanionRunningApp {
    val cleanPackage = packageName.trim()
    require(cleanPackage.isNotEmpty()) { "packageName must not be blank" }
    val nativeRow = (nativeSnapshot.games + nativeSnapshot.apps)
        .firstOrNull { it.packageName == cleanPackage }
    val tracked = trackedApps.firstOrNull { it.packageName == cleanPackage }
    return CompanionRunningApp(
        packageName = cleanPackage,
        label = nativeRow?.label?.takeIf(String::isNotBlank)
            ?: tracked?.label?.takeIf(String::isNotBlank)
            ?: cleanPackage,
        iconPath = nativeRow?.iconPath?.takeIf(String::isNotBlank)
            ?: cachedIconPaths[cleanPackage]?.takeIf(String::isNotBlank),
    )
}

internal fun companionRunningAppPayload(
    app: CompanionRunningApp,
    themeId: String,
    themeSigilRef: String? = null,
): CompanionPayload = CompanionPayload(
    mode = CompanionMode.RUNNING_APP,
    themeId = themeId,
    themeSigilRef = themeSigilRef,
    runningApp = app,
)

internal fun CompanionRuntimeSnapshot.isYieldedHost(
    packageName: String,
    operationId: OperationId,
): Boolean {
    val host = (disposition as? SecondaryDisplayDisposition.Yielded)?.lease ?: return false
    return host.operationId == operationId && host.packageName == packageName.trim()
}

/** Clear both process truth and the process-owned display payload without waiting for Compose to wake. */
internal fun clearCompanionRunningApp(
    expectedPackageName: String? = null,
    observedAtEpochMs: Long? = null,
): Boolean {
    if (!CompanionRunningAppState.clear(expectedPackageName, observedAtEpochMs)) return false
    CompanionState.clearRunningApp()
    return true
}

internal fun resetCompanionRunningApp(): Boolean {
    val stateChanged = CompanionRunningAppState.reset()
    val payloadWasRunning = CompanionState.payload.value.mode == CompanionMode.RUNNING_APP
    if (stateChanged || payloadWasRunning) CompanionState.clearRunningApp()
    return stateChanged || payloadWasRunning
}

internal fun clearCompanionRunningAppTransfer(operationId: UUID, packageName: String): Boolean {
    if (!CompanionRunningAppState.clearTransferSession(operationId, packageName)) return false
    CompanionState.clearRunningApp()
    return true
}

internal fun confirmCompanionRunningAppSecondaryReturn(operationId: UUID, packageName: String): Boolean {
    if (!CompanionRunningAppState.confirmSecondaryReturn(operationId, packageName)) return false
    CompanionState.clearRunningApp()
    return true
}

internal fun cancelCompanionRunningAppSecondaryReturn(operationId: UUID, packageName: String): Boolean =
    CompanionRunningAppState.cancelSecondaryReturn(operationId, packageName)

internal fun markCompanionRunningAppPrimaryHostOrphaned(
    operationId: UUID,
    packageName: String,
): PrimaryHostOrphanSettlement {
    val settlement = CompanionRunningAppState.markPrimaryHostOrphaned(operationId, packageName)
    if (settlement == PrimaryHostOrphanSettlement.CLEARED) CompanionState.clearRunningApp()
    return settlement
}

internal fun noteCompanionRunningAppMainResumed(): Boolean {
    if (!CompanionRunningAppState.noteMainResumed()) return false
    CompanionState.clearRunningApp()
    return true
}

internal fun confirmCompanionRunningAppTransfer(
    operationId: UUID,
    packageName: String,
    nowMonotonicMs: Long = monotonicMillis(),
): Boolean {
    val app = CompanionRunningAppState.confirmTransferFromPrimaryHost(
        operationId = operationId,
        packageName = packageName,
        nowMonotonicMs = nowMonotonicMs,
    ) ?: return false
    CompanionState.showRunningApp(app)
    return true
}

internal fun beginCompanionRunningAppPrimaryHostDispatch(
    operationId: UUID,
    packageName: String,
    nowMonotonicMs: Long = monotonicMillis(),
): Boolean = CompanionRunningAppState.beginPrimaryHostDispatch(
    operationId = operationId,
    packageName = packageName,
    nowMonotonicMs = nowMonotonicMs,
)

internal fun claimCompanionRunningAppPrimaryHostLaunch(
    operationId: UUID,
    packageName: String,
    nowMonotonicMs: Long = monotonicMillis(),
): Boolean = CompanionRunningAppState.claimPrimaryHostLaunch(
    operationId = operationId,
    packageName = packageName,
    nowMonotonicMs = nowMonotonicMs,
)

/** O7 / I3: what the exchange's landing settlement did, and who the ledger says occupies Main afterwards. */
internal data class CompanionMainLandingSettlement(
    val settled: Boolean,
    val occupant: CompanionRunningApp?,
)

/**
 * O7 / I3: the Main app of an exchange has landed on the Bottom — [lease] is its `Yielded` host — so its Main session is
 * over on that evidence alone. The ledger is settled here, synchronously, with the same two settlements the process
 * runtime's Yielded collector applies to every Main→Bottom landing: the exact armed transfer return, else the PRIMARY
 * session for the package. Both are idempotent, so whichever caller runs first ends the session and the other changes
 * nothing. The ledger's Main occupant is read back afterwards for the exchange's next leg, which therefore waits on no
 * other collector. A Bottom game lease settles nothing, as in the collector: a game never held a PRIMARY app session.
 */
internal fun settleCompanionMainAppLandedOnBottom(lease: HostLease): CompanionMainLandingSettlement {
    val settled = lease.launchMode != CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME && run {
        val secondaryOperationId = runCatching { UUID.fromString(lease.operationId.toString()) }.getOrNull()
        (secondaryOperationId != null &&
            confirmCompanionRunningAppSecondaryReturn(secondaryOperationId, lease.packageName)) ||
            MimirSessionAuthority.endPrimaryAppForSecondaryMove(lease.packageName)
    }
    return CompanionMainLandingSettlement(
        settled = settled,
        occupant = MimirSessionAuthority.state.value.primaryOccupant(),
    )
}

private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L

internal const val PRIMARY_TRANSFER_COVERAGE_WINDOW_MILLIS = 8_000L
internal const val PRIMARY_TRANSFER_STAGING_WINDOW_MILLIS = 15_000L

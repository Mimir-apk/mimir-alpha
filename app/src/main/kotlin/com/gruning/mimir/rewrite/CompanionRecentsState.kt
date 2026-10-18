package com.gruning.mimir.rewrite

import android.util.Log
import com.gruning.mimir.rewrite.core.ui.BottomUiReceipts
import com.gruning.mimir.rewrite.core.companionnative.PanelLeaseId
import com.gruning.mimir.rewrite.core.companionnative.PanelRejectReason
import com.gruning.mimir.rewrite.core.companionnative.PanelRevocationReason
import com.gruning.mimir.rewrite.core.companionnative.SecondaryDisplayState
import com.gruning.mimir.rewrite.core.companionnative.SecondaryTaskCloseState
import com.gruning.mimir.rewrite.core.companionnative.SecondaryTaskKind
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import com.gruning.mimir.rewrite.core.model.CompanionRunningApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The kind line a GAME tab carries, so the player can tell a running game from a launched app. */
internal const val CompanionGameTabKindLabel = "Game"

/**
 * The tab manager's tile list: the native ledger's Bottom sessions, newest first, then the Main-screen
 * running app when it is not already listed.
 *
 * Pure so the projection can be tested without an Activity. It takes the SESSION snapshot as well as the
 * registry state because a game tab is named by the library, not by PackageManager: the ledger row knows
 * the emulator that was dispatched, and the live session knows which game is inside it.
 *
 * A MAIN-placement game contributes nothing here by construction — it never reaches the secondary registry,
 * so it has no row — and that is asserted rather than assumed in `CompanionGameTabTest`.
 */
internal fun companionDrawerEntries(
    state: SecondaryDisplayState,
    session: MimirSessionSnapshot,
    iconPaths: Map<String, String>,
    running: CompanionRunningApp?,
    selfPackage: String,
): List<CompanionAppEntry> {
    val secondary = state.secondaryDisplayId
    val entries = state.recents
        .asSequence()
        .filter { secondary == null || it.displayId == secondary }
        // Mimir's own frontend is never a closeable tab. Use the same hard-hidden set the shelf excludes.
        .filterNot { nativeShelfIsHardHiddenPackage(it.packageName, selfPackage) }
        .distinctBy { it.packageName }
        .sortedByDescending { it.lastSeenAtMs }
        .take(24)
        .map { entry ->
            val game = entry.takeIf { it.kind == SecondaryTaskKind.GAME }
            // The live session outranks the persisted row: a row restored after a Mimir restart carries the
            // title it was dispatched with, and the session is what the player is actually in right now.
            val live = game?.let { session.liveBottomGameFor(entry.packageName) }
            val title = live?.title?.trim()?.takeIf(String::isNotEmpty)
                ?: game?.gameTitle?.trim()?.takeIf(String::isNotEmpty)
            CompanionAppEntry(
                packageName = entry.packageName,
                label = title ?: entry.label,
                iconPath = iconPaths[entry.packageName],
                artRef = live?.artRef ?: game?.gameArtRef,
                kindLabel = when (entry.closeState) {
                    SecondaryTaskCloseState.OPEN -> if (game != null) CompanionGameTabKindLabel else null
                    SecondaryTaskCloseState.CLOSE_REQUESTED_UNVERIFIED -> "Close requested"
                    SecondaryTaskCloseState.CLOSE_UNCONFIRMED -> "Close unconfirmed"
                },
                closable = entry.closeState == SecondaryTaskCloseState.OPEN,
            )
        }
        .toList()
    return if (running != null &&
        !nativeShelfIsHardHiddenPackage(running.packageName, selfPackage) &&
        entries.none { it.packageName == running.packageName }
    ) {
        entries + CompanionAppEntry(
            packageName = running.packageName,
            label = running.label,
            iconPath = running.iconPath,
            kindLabel = "Main screen",
            closable = true,
        )
    } else {
        entries
    }
}

/** Most recent tracked Bottom APP used by Activity's compact resume affordance. */
internal fun companionMostRecentBottomApp(
    state: SecondaryDisplayState,
    catalog: List<CompanionAppEntry>,
    selfPackage: String,
): CompanionAppEntry? {
    val secondary = state.secondaryDisplayId
    val row = state.recents.asSequence()
        .filter { secondary == null || it.displayId == secondary }
        .filter { it.kind == SecondaryTaskKind.APP }
        .filterNot { nativeShelfIsHardHiddenPackage(it.packageName, selfPackage) }
        .maxByOrNull { it.lastSeenAtMs }
        ?: return null
    val catalogEntry = catalog.firstOrNull { it.packageName == row.packageName }
    return CompanionAppEntry(
        packageName = row.packageName,
        label = catalogEntry?.label ?: row.label,
        iconPath = catalogEntry?.iconPath,
    )
}

sealed interface CompanionDrawerHost {
    data object Hidden : CompanionDrawerHost

    data class Presentation(val renderOperationId: UUID) : CompanionDrawerHost {
        init {
            require(renderOperationId != EMPTY_UUID) { "Presentation drawer operation id must be non-empty" }
        }
    }

    data class Overlay(val panelLeaseId: PanelLeaseId) : CompanionDrawerHost
}

/**
 * The only host decision for the interactive drawer.  This is deliberately a projection of certified
 * runtime truth and the primary Activity lifecycle; it never infers ownership from a window or changes
 * the runtime disposition.
 */
internal enum class CompanionDrawerHostSelection {
    NONE,
    PRESENTATION,
    OVERLAY,
}

internal fun companionDrawerHostSelection(
    snapshot: CompanionRuntimeSnapshot,
    primaryActivityStopped: Boolean,
    /**
     * A dual-screen game is live on Main (R1). Its emulator adds its own window on the Bottom, above Mimir's
     * Presentation, so a drawer drawn in the Presentation is beneath it whether or not Mimir's Main activity is stopped.
     */
    dualScreenGameLive: Boolean = false,
): CompanionDrawerHostSelection = when (snapshot.disposition) {
    is SecondaryDisplayDisposition.Yielded -> CompanionDrawerHostSelection.OVERLAY
    is SecondaryDisplayDisposition.RecoveryNeeded ->
        if (isColdProcessRecoveryAvailable(snapshot)) {
            CompanionDrawerHostSelection.OVERLAY
        } else {
            CompanionDrawerHostSelection.NONE
        }
    is SecondaryDisplayDisposition.MirrorPresented -> if (primaryActivityStopped || dualScreenGameLive) {
        CompanionDrawerHostSelection.OVERLAY
    } else {
        CompanionDrawerHostSelection.PRESENTATION
    }
    else -> CompanionDrawerHostSelection.NONE
}

sealed interface CompanionDrawerLaunchState {
    data object Unlocked : CompanionDrawerLaunchState

    data class LaunchPending(
        val operationId: UUID,
        val packageName: String,
        /**
         * The app's name, or null when nothing in this process knows it yet. **Nullable on purpose:**
         * it used to fall back to the package name, which put `Opening com.foo.bar...` on the launch
         * line whenever the catalog sweep had not landed -- the same cold window that made a pinned
         * seat draw `+`. A package id is not a name, and inventing one from it is a lie with a dot in
         * it. Unknown is representable now, and [launchMessage] renders it as silence rather than as
         * the system's own spelling.
         */
        val label: String?,
        val deadlineElapsedRealtimeMs: Long,
    ) : CompanionDrawerLaunchState

    data class LaunchFailed(val message: String) : CompanionDrawerLaunchState
}

sealed interface CompanionDrawerCloseState {
    data object Idle : CompanionDrawerCloseState

    data class Pending(
        val operationId: UUID,
        val packageName: String,
        val label: String?,
    ) : CompanionDrawerCloseState

    data class Failed(
        val operationId: UUID,
        val packageName: String,
        val message: String,
    ) : CompanionDrawerCloseState
}

enum class CompanionLaunchUnlockReason {
    HOST_COVERED,
    RECLAIM_STARTED,
    RECLAIM_COMPLETED,
    HOST_FAILED,
}

internal data class CompanionPreparedLaunch(
    val operationId: UUID,
    val dispatch: () -> Boolean,
    val dispatchAndAwait: (() -> Boolean)? = null,
) {
    init {
        require(operationId != EMPTY_UUID) { "Prepared launch operation id must be non-empty" }
    }

    fun dispatchAwaitingHost(): Boolean = dispatchAndAwait?.invoke() ?: dispatch()
}

internal sealed interface CompanionLaunchResolution {
    data class Unlock(val reason: CompanionLaunchUnlockReason) : CompanionLaunchResolution
    data class Fail(val message: String) : CompanionLaunchResolution
}

/** Resolve only evidence carrying the exact drawer operation; render-state shape alone is never evidence. */
internal fun CompanionRuntimeSnapshot.launchResolutionFor(operationId: UUID): CompanionLaunchResolution? {
    val expected = OperationId.parseOrNull(operationId.toString()) ?: return null
    launchSettlement?.takeIf { it.operationId == expected }?.let { settlement ->
        return when (settlement.kind) {
            LaunchSettlementKind.HOST_COVERED ->
                CompanionLaunchResolution.Unlock(CompanionLaunchUnlockReason.HOST_COVERED)
            LaunchSettlementKind.FAILED -> CompanionLaunchResolution.Fail("Launch did not reach screen 2.")
        }
    }
    return when (val state = disposition) {
        is SecondaryDisplayDisposition.Yielded -> state.lease
            ?.takeIf { it.operationId == expected }
            ?.let { CompanionLaunchResolution.Unlock(CompanionLaunchUnlockReason.HOST_COVERED) }
        is SecondaryDisplayDisposition.ReclaimPending -> state.lease.parentHostOperationId
            ?.takeIf { it == expected }
            ?.let { CompanionLaunchResolution.Unlock(CompanionLaunchUnlockReason.RECLAIM_STARTED) }
        is SecondaryDisplayDisposition.RecoveryNeeded -> state.operationId
            .takeIf { it == expected }
            ?.let { CompanionLaunchResolution.Fail("Launch needs attention.") }
        else -> null
    }
}

/**
 * Process-scoped drawer state. Window hosts are token-owned and the one launch deadline lives here, so moving
 * between an overlay and a Presentation cannot cancel or duplicate the unlock timer.
 */
object CompanionRecentsState {
    data class Ui(
        val requestedVisible: Boolean = false,
        val entries: List<CompanionAppEntry> = emptyList(),
        val drawerHost: CompanionDrawerHost = CompanionDrawerHost.Hidden,
        val launchState: CompanionDrawerLaunchState = CompanionDrawerLaunchState.Unlocked,
        val closeState: CompanionDrawerCloseState = CompanionDrawerCloseState.Idle,
        val awaitingOverlayPermission: Boolean = false,
    ) {
        /** Legacy projection; Presentation owners should use [isVisibleOnPresentation] with the render id. */
        val visible: Boolean
            get() = requestedVisible && drawerHost is CompanionDrawerHost.Presentation

        val launchingPackageName: String?
            get() = (launchState as? CompanionDrawerLaunchState.LaunchPending)?.packageName

        val launchMessage: String?
            get() = when (val launch = launchState) {
                CompanionDrawerLaunchState.Unlocked -> null
                is CompanionDrawerLaunchState.LaunchFailed -> launch.message
                is CompanionDrawerLaunchState.LaunchPending ->
                    launch.label?.let { "Opening $it..." } ?: "Opening..."
            }

        val launchLocked: Boolean
            get() = launchState is CompanionDrawerLaunchState.LaunchPending

        val closePendingPackageName: String?
            get() = (closeState as? CompanionDrawerCloseState.Pending)?.packageName

        val closePending: Boolean
            get() = closeState is CompanionDrawerCloseState.Pending

        val closeMessage: String?
            get() = when (val close = closeState) {
                CompanionDrawerCloseState.Idle -> null
                is CompanionDrawerCloseState.Pending -> "Returning to shelf…"
                is CompanionDrawerCloseState.Failed -> close.message
            }

        fun isVisibleOnOverlay(leaseId: PanelLeaseId): Boolean =
            requestedVisible && drawerHost == CompanionDrawerHost.Overlay(leaseId)

        fun isVisibleOnPresentation(renderOperationId: UUID): Boolean =
            requestedVisible && drawerHost == CompanionDrawerHost.Presentation(renderOperationId)
    }

    private val coordinator = CompanionRecentsCoordinator()
    val ui: StateFlow<Ui> = coordinator.ui
    internal val livePresentationHosts: StateFlow<Set<UUID>> = coordinator.livePresentationHosts

    private var preparedLaunchHandler: ((String, Boolean) -> CompanionPreparedLaunch?)? = null
    private var closeHandler: ((String, UUID) -> Unit)? = null

    /** The handler takes the package and whether this is a Quick Launch (the launch closes the app's Main copy first). */
    internal fun setPreparedLaunchHandler(handler: (String, Boolean) -> CompanionPreparedLaunch?) {
        preparedLaunchHandler = handler
    }

    internal fun clearPreparedLaunchHandler(handler: (String, Boolean) -> CompanionPreparedLaunch?) {
        if (preparedLaunchHandler === handler) preparedLaunchHandler = null
    }

    fun setCloseHandler(handler: (String, UUID) -> Unit) {
        closeHandler = handler
    }

    fun clearCloseHandler(handler: (String, UUID) -> Unit) {
        if (closeHandler === handler) closeHandler = null
    }

    fun show(entries: List<CompanionAppEntry>) = coordinator.show(entries)

    fun update(entries: List<CompanionAppEntry>) = coordinator.update(entries)

    fun hide(): Boolean = coordinator.hide()

    fun selectPresentation(renderOperationId: UUID): Boolean =
        coordinator.selectPresentation(renderOperationId)

    fun registerPresentationHost(renderOperationId: UUID): Boolean =
        coordinator.registerPresentationHost(renderOperationId)

    internal fun selectPresentation(snapshot: CompanionRuntimeSnapshot): Boolean =
        coordinator.selectPresentation(snapshot)

    fun releasePresentationHost(renderOperationId: UUID): Boolean =
        coordinator.releasePresentationHost(renderOperationId)

    /** Clear only the drawer's Presentation projection while retaining the live render host for migration back. */
    internal fun clearPresentationDrawer(renderOperationId: UUID): Boolean =
        coordinator.clearPresentationDrawer(renderOperationId)

    fun attachOverlayHost(leaseId: PanelLeaseId): Boolean = coordinator.attachOverlayHost(leaseId)

    fun releaseOverlayHost(
        leaseId: PanelLeaseId,
        reason: PanelRevocationReason? = null,
    ): Boolean = coordinator.releaseOverlayHost(leaseId, reason)

    fun noteOverlayUnavailable(reason: PanelRejectReason) = coordinator.noteOverlayUnavailable(reason)

    /** A visible, hostless drawer may be reattached when the runtime confirms a foreign host owns screen 2. */
    fun shouldAttachOverlay(): Boolean = coordinator.shouldAttachOverlay()

    /**
     * Publishes the coordinator's mandatory operation id before dispatching the target. [clearMainCopy] is true only from Quick
     * Launch (the earlier update, policy 2026-10-09): every other caller keeps launching exactly as before.
     */
    fun onLaunch(packageName: String, label: String? = null, clearMainCopy: Boolean = false): UUID? {
        val prepared = preparedLaunchHandler
        return coordinator.requestPreparedLaunch(packageName, label) { cleanPackage ->
            prepared?.invoke(cleanPackage, clearMainCopy)
        }
    }

    @Deprecated("Use onLaunch so every caller receives the exact-token lock and deadline")
    fun launchOnSecondary(packageName: String): Boolean {
        val cleanPackage = packageName.trim()
        if (cleanPackage.isEmpty()) return false
        return onLaunch(cleanPackage) != null
    }

    fun onClose(packageName: String) {
        if (ui.value.launchLocked) return
        val operationId = coordinator.beginClose(packageName) ?: return
        val handler = closeHandler
        if (handler == null) {
            coordinator.failClose(operationId, packageName, "The tab stayed open. Try again.")
            return
        }
        runCatching { handler(packageName, operationId) }
            .onFailure { coordinator.failClose(operationId, packageName, "The tab stayed open. Try again.") }
    }

    fun failClose(operationId: UUID, packageName: String, message: String): Boolean =
        coordinator.failClose(operationId, packageName, message)

    fun completeClose(operationId: UUID, packageName: String): Boolean =
        coordinator.completeClose(operationId, packageName)

    fun clearClose(operationId: UUID): Boolean = coordinator.clearClose(operationId)

    /** Only the matching operation can clear a pending launch across host migration. */
    fun clearLaunchLock(operationId: UUID, reason: CompanionLaunchUnlockReason): Boolean =
        coordinator.clearLaunchLock(operationId, reason)

    fun failLaunch(operationId: UUID, message: String): Boolean = coordinator.failLaunch(operationId, message)

    /**
     * Legacy no-token callers fail closed while a flight is pending. They may clear a non-pending failure
     * message, but can never unlock a newer operation.
     */
    fun clearLaunchLock(): Boolean = coordinator.clearNonPendingLaunchState()
}

internal fun interface LaunchDeadlineHandle {
    fun cancel()
}

internal fun interface LaunchDeadlineScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): LaunchDeadlineHandle
}

private object ProcessLaunchDeadlineScheduler : LaunchDeadlineScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "MimirCompanionDrawerDeadline").apply { isDaemon = true }
    }

    override fun schedule(delayMs: Long, action: () -> Unit): LaunchDeadlineHandle {
        val future = executor.schedule(action, delayMs, TimeUnit.MILLISECONDS)
        return LaunchDeadlineHandle { future.cancel(false) }
    }
}

// P2/P3: message builders pulled out of their call sites so a JVM unit test can pin the exact text
// without touching `android.util.Log` (mirrors `CompanionReturnCoordinator.formatRuntimeLogEvent`).
internal fun tabCloseRequestedLogMessage(packageName: String, operationId: UUID): String =
    "tab close requested package=$packageName op=$operationId"

internal fun tabCloseFailedLogMessage(packageName: String, operationId: UUID, message: String): String =
    "tab close failed package=$packageName op=$operationId message=$message"

internal fun tabCloseCompletedLogMessage(packageName: String, operationId: UUID): String =
    "tab close completed package=$packageName op=$operationId"

internal fun tabCloseClearedLogMessage(packageName: String, operationId: UUID): String =
    "tab close cleared package=$packageName op=$operationId"

internal fun secondaryLaunchTimedOutLogMessage(packageName: String, operationId: UUID): String =
    "secondary launch timed out package=$packageName op=$operationId"

internal fun secondaryLaunchFailedLogMessage(packageName: String, operationId: UUID, message: String): String =
    "secondary launch failed package=$packageName op=$operationId message=$message"

internal fun secondaryLaunchUnlockedLogMessage(operationId: UUID, reason: CompanionLaunchUnlockReason): String =
    "secondary launch unlocked op=$operationId reason=$reason"

internal class CompanionRecentsCoordinator(
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val operationIdFactory: () -> UUID = UUID::randomUUID,
    private val deadlineScheduler: LaunchDeadlineScheduler = ProcessLaunchDeadlineScheduler,
    private val launchTimeoutMs: Long = DEFAULT_LAUNCH_TIMEOUT_MS,
) {
    private val _ui = MutableStateFlow(CompanionRecentsState.Ui())
    val ui: StateFlow<CompanionRecentsState.Ui> = _ui.asStateFlow()
    private val mutableLivePresentationHosts = MutableStateFlow<Set<UUID>>(emptySet())
    val livePresentationHosts: StateFlow<Set<UUID>> = mutableLivePresentationHosts.asStateFlow()

    private var deadlineHandle: LaunchDeadlineHandle? = null

    @Synchronized
    fun show(entries: List<CompanionAppEntry>) {
        _ui.value = _ui.value.copy(requestedVisible = true, entries = entries)
    }

    @Synchronized
    fun update(entries: List<CompanionAppEntry>) {
        val current = _ui.value
        if (!current.requestedVisible || current.launchLocked) return
        _ui.value = current.copy(entries = entries)
    }

    @Synchronized
    fun hide(): Boolean {
        val current = _ui.value
        if (current.launchLocked) return false
        _ui.value = current.copy(
            requestedVisible = false,
            drawerHost = if (current.drawerHost is CompanionDrawerHost.Overlay) {
                current.drawerHost
            } else {
                CompanionDrawerHost.Hidden
            },
            awaitingOverlayPermission = false,
        )
        return true
    }

    @Synchronized
    fun registerPresentationHost(renderOperationId: UUID): Boolean {
        require(renderOperationId != EMPTY_UUID) { "Presentation render operation id must be non-empty" }
        if (renderOperationId in mutableLivePresentationHosts.value) return false
        mutableLivePresentationHosts.value = mutableLivePresentationHosts.value + renderOperationId
        return true
    }

    @Synchronized
    fun selectPresentation(renderOperationId: UUID): Boolean {
        require(renderOperationId != EMPTY_UUID) { "Presentation render operation id must be non-empty" }
        val current = _ui.value
        if (renderOperationId !in mutableLivePresentationHosts.value ||
            !current.requestedVisible ||
            current.drawerHost is CompanionDrawerHost.Overlay
        ) {
            return false
        }
        _ui.value = current.copy(
            drawerHost = CompanionDrawerHost.Presentation(renderOperationId),
            awaitingOverlayPermission = false,
        )
        return true
    }

    fun selectPresentation(snapshot: CompanionRuntimeSnapshot): Boolean {
        if (!snapshot.mirrorActive) return false
        val mirror = snapshot.disposition as? SecondaryDisplayDisposition.MirrorPresented ?: return false
        return selectPresentation(UUID.fromString(mirror.attempt.operationId.toString()))
    }

    @Synchronized
    fun releasePresentationHost(renderOperationId: UUID): Boolean {
        mutableLivePresentationHosts.value = mutableLivePresentationHosts.value - renderOperationId
        val current = _ui.value
        if (current.drawerHost != CompanionDrawerHost.Presentation(renderOperationId)) return false
        _ui.value = current.copy(drawerHost = CompanionDrawerHost.Hidden)
        return true
    }

    @Synchronized
    fun clearPresentationDrawer(renderOperationId: UUID): Boolean {
        val current = _ui.value
        if (current.drawerHost != CompanionDrawerHost.Presentation(renderOperationId)) return false
        _ui.value = current.copy(drawerHost = CompanionDrawerHost.Hidden)
        return true
    }

    @Synchronized
    fun attachOverlayHost(leaseId: PanelLeaseId): Boolean {
        val current = _ui.value
        if (!current.requestedVisible) return false
        _ui.value = current.copy(
            drawerHost = CompanionDrawerHost.Overlay(leaseId),
            awaitingOverlayPermission = false,
        )
        return true
    }

    @Synchronized
    fun releaseOverlayHost(leaseId: PanelLeaseId, reason: PanelRevocationReason?): Boolean {
        val current = _ui.value
        if (current.drawerHost != CompanionDrawerHost.Overlay(leaseId)) return false
        _ui.value = current.copy(
            drawerHost = CompanionDrawerHost.Hidden,
            awaitingOverlayPermission =
                current.requestedVisible && reason == PanelRevocationReason.PERMISSION_LOST,
        )
        return true
    }

    @Synchronized
    fun noteOverlayUnavailable(reason: PanelRejectReason) {
        if (reason != PanelRejectReason.OVERLAY_PERMISSION_MISSING) return
        val current = _ui.value
        _ui.value = current.copy(
            drawerHost = CompanionDrawerHost.Hidden,
            awaitingOverlayPermission = current.requestedVisible,
        )
    }

    @Synchronized
    fun shouldAttachOverlay(): Boolean =
        _ui.value.requestedVisible && _ui.value.drawerHost == CompanionDrawerHost.Hidden

    fun requestLaunch(
        packageName: String,
        label: String? = null,
        dispatch: (UUID, String) -> Boolean,
    ): UUID? {
        val cleanPackage = packageName.trim()
        if (cleanPackage.isEmpty()) return null
        val operationId = operationIdFactory().also {
            require(it != EMPTY_UUID) { "Launch operation id must be non-empty" }
        }
        return beginLaunch(cleanPackage, label, operationId) { dispatch(operationId, cleanPackage) }
    }

    fun requestPreparedLaunch(
        packageName: String,
        label: String? = null,
        prepare: (String) -> CompanionPreparedLaunch?,
    ): UUID? {
        val cleanPackage = packageName.trim()
        if (cleanPackage.isEmpty()) return null
        val prepared = synchronized(this) {
            if (_ui.value.launchLocked) {
                val pending = _ui.value.launchState as CompanionDrawerLaunchState.LaunchPending
                runCatching {
                    Log.w(
                        "MimirCompanionRuntime",
                        "secondary launch refused: locked package=$cleanPackage " +
                            "pending=${pending.packageName}/${pending.operationId} label=${pending.label}",
                    )
                }
                return@synchronized null
            }
            val candidate = runCatching { prepare(cleanPackage) }.getOrNull()
            if (candidate == null) {
                runCatching {
                    Log.w("MimirCompanionRuntime", "secondary launch refused: prepare declined package=$cleanPackage")
                }
                _ui.value = _ui.value.copy(
                    launchState = CompanionDrawerLaunchState.LaunchFailed("Launch unavailable."),
                )
                return@synchronized null
            }
            publishLaunchLocked(cleanPackage, label, candidate.operationId)
            candidate
        } ?: return null
        return dispatchPublishedLaunch(prepared.operationId, prepared.dispatch)
    }

    @Synchronized
    fun beginClose(packageName: String): UUID? {
        val cleanPackage = packageName.trim()
        if (cleanPackage.isEmpty()) return null
        if (_ui.value.closeState is CompanionDrawerCloseState.Pending) return null
        val entry = _ui.value.entries.firstOrNull { it.packageName.equals(cleanPackage, ignoreCase = true) }
            ?: return null
        val operationId = operationIdFactory().also {
            require(it != EMPTY_UUID) { "Close operation id must be non-empty" }
        }
        _ui.value = _ui.value.copy(
            closeState = CompanionDrawerCloseState.Pending(
                operationId = operationId,
                packageName = cleanPackage,
                label = entry.label.trim().takeIf(String::isNotEmpty),
            ),
        )
        // P2: the tap that starts a close has no line of its own anywhere in the drawer half of §1.9.
        runCatching {
            Log.i("MimirCompanionRuntime", tabCloseRequestedLogMessage(cleanPackage, operationId))
        }
        BottomUiReceipts.log.tabClose("requested", companionBottomDisplayId(), cleanPackage, operationId.toString())
        return operationId
    }

    @Synchronized
    fun failClose(operationId: UUID, packageName: String, message: String): Boolean {
        val pending = _ui.value.closeState as? CompanionDrawerCloseState.Pending ?: return false
        if (pending.operationId != operationId || !pending.packageName.equals(packageName.trim(), ignoreCase = true)) {
            return false
        }
        _ui.value = _ui.value.copy(
            closeState = CompanionDrawerCloseState.Failed(
                operationId = operationId,
                packageName = pending.packageName,
                message = message,
            ),
        )
        // P2: previously only the drawer message ("The tab stayed open. Try again.") recorded this outcome.
        runCatching {
            Log.w("MimirCompanionRuntime", tabCloseFailedLogMessage(pending.packageName, operationId, message))
        }
        BottomUiReceipts.log.tabClose("failed", companionBottomDisplayId(), pending.packageName, operationId.toString())
        return true
    }

    @Synchronized
    fun completeClose(operationId: UUID, packageName: String): Boolean {
        val pending = _ui.value.closeState as? CompanionDrawerCloseState.Pending ?: return false
        if (pending.operationId != operationId || !pending.packageName.equals(packageName.trim(), ignoreCase = true)) {
            return false
        }
        _ui.value = _ui.value.copy(
            entries = _ui.value.entries.filterNot { it.packageName.equals(pending.packageName, ignoreCase = true) },
            closeState = CompanionDrawerCloseState.Idle,
        )
        // P2: this drawer-side completion was silent (see §1.9 "Completed").
        runCatching {
            Log.i("MimirCompanionRuntime", tabCloseCompletedLogMessage(pending.packageName, operationId))
        }
        BottomUiReceipts.log.tabClose("completed", companionBottomDisplayId(), pending.packageName, operationId.toString())
        return true
    }

    @Synchronized
    fun clearClose(operationId: UUID): Boolean {
        val pending = _ui.value.closeState as? CompanionDrawerCloseState.Pending ?: return false
        if (pending.operationId != operationId) return false
        _ui.value = _ui.value.copy(closeState = CompanionDrawerCloseState.Idle)
        // P2: a superseded/cleared close previously left no trace at all.
        runCatching {
            Log.i("MimirCompanionRuntime", tabCloseClearedLogMessage(pending.packageName, operationId))
        }
        BottomUiReceipts.log.tabClose("cleared", companionBottomDisplayId(), pending.packageName, operationId.toString())
        return true
    }

    private fun beginLaunch(
        cleanPackage: String,
        labelHint: String?,
        operationId: UUID,
        dispatch: () -> Boolean,
    ): UUID? {
        require(operationId != EMPTY_UUID) { "Launch operation id must be non-empty" }
        synchronized(this) {
            val current = _ui.value
            if (current.launchLocked) return null
            publishLaunchLocked(cleanPackage, labelHint, operationId)
        }

        return dispatchPublishedLaunch(operationId, dispatch)
    }

    /** Caller holds this coordinator's monitor, including across memory-only prepared-plan capture. */
    private fun publishLaunchLocked(cleanPackage: String, labelHint: String?, operationId: UUID) {
        require(operationId != EMPTY_UUID) { "Launch operation id must be non-empty" }
        val current = _ui.value
        check(!current.launchLocked) { "A launch lock cannot be replaced" }
        // **No `?: cleanPackage`.** The chain ends at "we do not know", because the third rung used to
        // be the package id and the line then read `Opening com.foo.bar...` to the player. Both earlier
        // rungs miss together in exactly one situation -- the catalog sweep has not finished -- so the
        // fallback fired precisely when it was least excusable: at the start of a session.
        val label = labelHint?.trim()?.takeIf(String::isNotEmpty)
            ?: current.entries.firstOrNull { it.packageName == cleanPackage }?.label
        val deadline = elapsedRealtimeMs() + launchTimeoutMs
        _ui.value = current.copy(
            launchState = CompanionDrawerLaunchState.LaunchPending(
                operationId = operationId,
                packageName = cleanPackage,
                label = label,
                deadlineElapsedRealtimeMs = deadline,
            ),
        )
        deadlineHandle?.cancel()
        deadlineHandle = deadlineScheduler.schedule(launchTimeoutMs) { expireLaunch(operationId) }
    }

    private fun dispatchPublishedLaunch(operationId: UUID, dispatch: () -> Boolean): UUID {
        val accepted = runCatching(dispatch).getOrDefault(false)
        if (!accepted) failLaunch(operationId, "Launch unavailable.")
        return operationId
    }

    @Synchronized
    fun failLaunch(operationId: UUID, message: String): Boolean {
        val pending = _ui.value.launchState as? CompanionDrawerLaunchState.LaunchPending ?: return false
        if (pending.operationId != operationId) return false
        deadlineHandle?.cancel()
        deadlineHandle = null
        // P3: the drawer's own launch resolution (§1.1 "Drawer resolution") was silent.
        runCatching {
            Log.w(
                "MimirCompanionRuntime",
                secondaryLaunchFailedLogMessage(pending.packageName, operationId, message),
            )
        }
        _ui.value = _ui.value.copy(launchState = CompanionDrawerLaunchState.LaunchFailed(message))
        return true
    }

    @Synchronized
    fun clearLaunchLock(operationId: UUID, reason: CompanionLaunchUnlockReason): Boolean {
        val pending = _ui.value.launchState as? CompanionDrawerLaunchState.LaunchPending ?: return false
        if (pending.operationId != operationId) return false
        deadlineHandle?.cancel()
        deadlineHandle = null
        // P3: the unlock itself was silent, which is half of the I5 orphan (see expireLaunch below).
        runCatching {
            Log.i("MimirCompanionRuntime", secondaryLaunchUnlockedLogMessage(operationId, reason))
        }
        _ui.value = _ui.value.copy(
            requestedVisible = false,
            drawerHost = if (_ui.value.drawerHost is CompanionDrawerHost.Overlay) {
                _ui.value.drawerHost
            } else {
                CompanionDrawerHost.Hidden
            },
            launchState = CompanionDrawerLaunchState.Unlocked,
            awaitingOverlayPermission = false,
        )
        return true
    }

    @Synchronized
    fun clearNonPendingLaunchState(): Boolean {
        if (_ui.value.launchState is CompanionDrawerLaunchState.LaunchPending) return false
        _ui.value = _ui.value.copy(launchState = CompanionDrawerLaunchState.Unlocked)
        return true
    }

    @Synchronized
    private fun expireLaunch(operationId: UUID) {
        val pending = _ui.value.launchState as? CompanionDrawerLaunchState.LaunchPending ?: return
        if (pending.operationId != operationId) return
        deadlineHandle = null
        // P3 / I5: this drawer timeout used to be the silent half of the two-timer race (I5) — a reader
        // could not tell "the drawer gave up" from "the runtime gave up" without this line.
        runCatching {
            Log.w("MimirCompanionRuntime", secondaryLaunchTimedOutLogMessage(pending.packageName, operationId))
        }
        _ui.value = _ui.value.copy(
            requestedVisible = false,
            drawerHost = if (_ui.value.drawerHost is CompanionDrawerHost.Overlay) {
                _ui.value.drawerHost
            } else {
                CompanionDrawerHost.Hidden
            },
            launchState = CompanionDrawerLaunchState.LaunchFailed("Launch timed out."),
            awaitingOverlayPermission = false,
        )
    }
}

/** Re-emits runtime truth when either the runtime or the user's drawer intent changes. */
internal fun companionDrawerHostReconciliation(
    runtimeSnapshots: StateFlow<CompanionRuntimeSnapshot>,
    drawerUi: StateFlow<CompanionRecentsState.Ui>,
    livePresentationHosts: StateFlow<Set<UUID>>,
    primaryActivityStopped: StateFlow<Boolean> = PrimaryActivityObservationState.stopped,
): Flow<CompanionRuntimeSnapshot> = combine(
    runtimeSnapshots,
    drawerUi,
    livePresentationHosts,
    primaryActivityStopped,
) { snapshot, _, _, _ -> snapshot }

/**
 * I5: the drawer's own launch deadline must never fire, and unlock, before the runtime gives up on the
 * *same* launch. The runtime holds `launchLockOperationId` until its own launch deadline (default to
 * dispatch, [CompanionRuntimeTimeouts.launchMillis]) and, once dispatched, its target-coverage deadline
 * ([CompanionRuntimeTimeouts.targetCoverageMillis]) both expire — worst case, dispatch then a full
 * coverage window. An independent literal here (previously a bare `6_000L`, shorter than that worst
 * case) let the drawer unlock while the runtime still owned the operation: a second tile tap then won
 * the lock, minted a new root, and the first launch's late completion landed silently against a root
 * nothing recognised (§2.7 I5). Deriving from the runtime's own constants keeps the two timers on one
 * axis without touching the runtime; [DRAWER_DEADLINE_MARGIN_MS] only absorbs scheduling jitter between
 * the two timers firing, it is not slack for a third, independent timeout.
 */
private val DEFAULT_LAUNCH_TIMEOUT_MS: Long = CompanionRuntimeTimeouts().let { runtimeTimeouts ->
    runtimeTimeouts.launchMillis + runtimeTimeouts.targetCoverageMillis + DRAWER_DEADLINE_MARGIN_MS
}

private const val DRAWER_DEADLINE_MARGIN_MS = 500L
private val EMPTY_UUID = UUID(0L, 0L)

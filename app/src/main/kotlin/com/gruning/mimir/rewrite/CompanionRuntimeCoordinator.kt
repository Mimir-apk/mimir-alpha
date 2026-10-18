package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract
import com.gruning.mimir.rewrite.core.ui.BottomUiReceipts
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val NilUuid = UUID(0L, 0L)

@JvmInline
internal value class OperationId private constructor(private val uuid: UUID) {
    override fun toString(): String = uuid.toString()

    companion object {
        fun create(): OperationId = OperationId(UUID.randomUUID())

        fun parseOrNull(raw: String?): OperationId? {
            val clean = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val parsed = runCatching { UUID.fromString(clean) }.getOrNull() ?: return null
            return parsed.takeUnless { it == NilUuid }?.let(::OperationId)
        }
    }
}

@JvmInline
internal value class RendererOwnerId private constructor(private val uuid: UUID) {
    override fun toString(): String = uuid.toString()

    companion object {
        fun create(): RendererOwnerId = RendererOwnerId(UUID.randomUUID())

        fun parseOrNull(raw: String?): RendererOwnerId? {
            val clean = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val parsed = runCatching { UUID.fromString(clean) }.getOrNull() ?: return null
            return parsed.takeUnless { it == NilUuid }?.let(::RendererOwnerId)
        }
    }
}

internal fun interface OperationIdSource {
    fun next(): OperationId
}

internal data class PrimaryReturnOrigin(
    val destination: String,
    val arguments: Map<String, String> = emptyMap(),
    val revision: Long = 0L,
) {
    init {
        require(destination.isNotBlank()) { "Primary return destination is mandatory" }
        require(revision >= 0L) { "Primary return revision cannot be negative" }
    }

    companion object {
        /** Compatibility value until the app host supplies its exact navigation origin. */
        val LegacyCurrent = PrimaryReturnOrigin("legacy_current_primary")
    }
}

/** Equal or unversioned conflicts retain the recorded root; only a greater revision supersedes it. */
internal fun newestAuthoritativePrimaryOrigin(
    recorded: PrimaryReturnOrigin?,
    current: PrimaryReturnOrigin?,
): PrimaryReturnOrigin? = when {
    recorded == null -> current
    current == null -> recorded
    current.revision > recorded.revision -> current
    else -> recorded
}

/**
 * Task A, floor first (Thor 2026-09-11): whether a settling reclaim brings Mimir's secondary-home floor to the front of its
 * display before the restore guard finishes. After an edge-swipe reclaim the guard finished and YouTube, the next focusable
 * window on display 4, stayed resumed with that display's key focus behind the Presentation. A reason is listed only when
 * nothing queued behind its settle could race a home start on that display; every other reason, including any unknown
 * one, keeps today's behaviour.
 */
internal fun reclaimRequestsFloorFront(reason: String): Boolean = reason in FloorFrontReclaimReasons

private val FloorFrontReclaimReasons = setOf(
    // MainActivity's EdgeBack reclaim, the right-edge swipe: the measured defect.
    "overlay_edge_back",
    // Bottom menu Return to shelf: the same shape, and the app stays in Tabs.
    "secondary_menu_return_to_shelf",
    // Tab close: the reap runs only after the settled MirrorPresented, so this request always precedes it.
    "tab_close_reclaim",
    // Recovery reclaim when Main resumes: a foreign app may still sit on the display.
    "primary_activity_resumed",
    // Mimir takes the Bottom back from a dual-screen game that HOME parked: the edge-back reclaim, over the same live game.
    "home_park",
    // Deliberately absent: secondary_home_resumed (the floor is already the resumed top); overlay_swap_to_primary (the
    // Main transfer is dispatched first, so a home start on the Bottom would take top focus back from the moved app);
    // overlay_swap_exchange (leg 2 starts a Bottom launch host straight after this settle); presentation_window_revealed
    // (an inferred departure, the app may have moved itself to Main); secondary_app_return (not an explicit reclaim).
)

internal data class RenderAttempt(
    val operationId: OperationId,
    val parentDispositionId: OperationId,
    val rendererOwnerId: RendererOwnerId,
    val displayId: Int,
)

internal data class HostLease(
    val operationId: OperationId,
    val packageName: String,
    val displayId: Int,
    val launchMode: String,
    val primaryOrigin: PrimaryReturnOrigin,
)

internal data class ReclaimLease(
    val operationId: OperationId,
    val parentHostOperationId: OperationId?,
    val displayId: Int,
    val primaryOrigin: PrimaryReturnOrigin,
    val reason: String,
    /**
     * D2 (policy 2026-09-06, second-screen ledger): the covered `Yielded` host this reclaim was
     * minted over, kept only when the caller asserted nothing about the app leaving (no
     * `returningFromPackage`). If the reclaim then fails before the mirror settles — root deadline, render
     * deadline, guard dispatch failure, guard destroyed — the root retreats to this host instead of
     * entering `RecoveryNeeded`: the app is still in front on the secondary display, so `Yielded(host)`
     * stays the truth and both return channels (host return, fresh explicit reclaim) keep working.
     * Null for host-return reclaims and for reclaims whose caller watched the app leave; there the cover
     * is gone and a failure honestly needs recovery.
     */
    val coveredHost: HostLease? = null,
    val coveredHostRoot: ReclaimSessionLedger.RootOperation? = null,
)

internal enum class PresentCause {
    DISPLAY_AVAILABLE,
    RENDERER_REBOUND,
    LAUNCH_ROLLED_BACK,
}

internal enum class LaunchPhase {
    HOST_REQUESTED,
    HOST_STARTED,
    TARGET_DISPATCHED,
}

internal enum class YieldEvidence {
    HOST_COVERED,
    USER_REQUESTED_GAME_RETURN,
}

internal enum class ReclaimPhase {
    GUARD_REQUESTED,
    GUARD_DISPATCHED,
    GUARD_FOREGROUND,
    RENDER_REQUESTED,
}

internal enum class FailureCode {
    LAUNCH_FAILED_AFTER_DISPATCH,
    LAUNCH_DEADLINE_AFTER_DISPATCH,
    HOST_DESTROYED_AFTER_DISPATCH,
    HOST_DESTROYED_WHILE_YIELDED,
    RECLAIM_GUARD_DISPATCH_FAILED,
    RECLAIM_HOST_DESTROYED,
    RECLAIM_DEADLINE,
    RENDER_FAILED,
    RENDER_DEADLINE,
    PROCESS_RECOVERED_NON_MIRROR,
}

internal data class FailureReason(
    val code: FailureCode,
    val detail: String,
)

internal sealed interface SecondaryDisplayDisposition {
    data object NoDisplay : SecondaryDisplayDisposition

    data class Presenting(
        val attempt: RenderAttempt,
        val cause: PresentCause,
    ) : SecondaryDisplayDisposition

    data class MirrorPresented(
        val attempt: RenderAttempt,
    ) : SecondaryDisplayDisposition

    data class LaunchPending(
        val lease: HostLease,
        val phase: LaunchPhase,
    ) : SecondaryDisplayDisposition

    data class Yielded(
        val lease: HostLease?,
        val evidence: YieldEvidence,
    ) : SecondaryDisplayDisposition

    data class ReclaimPending(
        val lease: ReclaimLease,
        val phase: ReclaimPhase,
        val expectedRender: RenderAttempt? = null,
    ) : SecondaryDisplayDisposition

    data class RecoveryNeeded(
        val operationId: OperationId,
        val displayId: Int,
        val fallback: PrimaryReturnOrigin?,
        val failure: FailureReason,
    ) : SecondaryDisplayDisposition
}

internal data class CompanionRuntimeSnapshot(
    val stateVersion: Long,
    val displayId: Int?,
    val disposition: SecondaryDisplayDisposition,
    val rendererOwnerId: RendererOwnerId?,
    val mirrorActive: Boolean,
    val launchLockOperationId: OperationId?,
    val launchSettlement: LaunchSettlement?,
    /** The display selector has actually observed this id in the current process. */
    val availableDisplayId: Int? = null,
)

internal enum class LaunchSettlementKind {
    HOST_COVERED,
    FAILED,
}

/** Last terminal result for the launch root, retained across reclaim/render snapshot conflation. */
internal data class LaunchSettlement(
    val operationId: OperationId,
    val kind: LaunchSettlementKind,
)

internal data class PersistedNonMirrorLease(
    val operationId: OperationId,
    val displayId: Int,
    val primaryOrigin: PrimaryReturnOrigin?,
    val description: String,
)

internal fun interface RuntimeCancellation {
    fun cancel()
}

/** Scheduler delays must be monotonic. The operation id is part of every scheduled callback. */
internal fun interface CompanionRuntimeScheduler {
    fun schedule(
        delayMillis: Long,
        operationId: OperationId,
        action: () -> Unit,
    ): RuntimeCancellation
}

internal data class CompanionRuntimeTimeouts(
    val launchMillis: Long = 3_000L,
    val targetCoverageMillis: Long = 8_000L,
    val reclaimMillis: Long = 3_000L,
    val renderMillis: Long = 3_000L,
)

internal data class CompanionRuntimeLogEvent(
    val stateVersion: Long,
    val event: String,
    val rootOperationId: OperationId?,
    val renderOperationId: OperationId?,
    val rendererOwnerId: RendererOwnerId?,
    val from: String,
    val to: String,
    /** [HostLease.packageName] for a `LaunchPending`/`Yielded` destination; null otherwise. P1. */
    val pkg: String? = null,
    /** [HostLease.launchMode] for a `LaunchPending`/`Yielded` destination; null otherwise. P1. */
    val mode: String? = null,
    /** [ReclaimLease.reason] for a `ReclaimPending` destination; null otherwise. P1. */
    val reason: String? = null,
    /**
     * `"${FailureReason.code}:${FailureReason.detail}"` for a `RecoveryNeeded` destination, and for a covered-host retreat
     * to `Yielded` the failure that retreat absorbed (O7 / I1); null otherwise. P1.
     */
    val fail: String? = null,
)

/** P1: helper return type for [CompanionRuntimeCoordinator.logAttributesFor]; all null except the fields the destination disposition actually carries. */
internal data class RuntimeLogAttributes(
    val pkg: String? = null,
    val mode: String? = null,
    val reason: String? = null,
    val fail: String? = null,
)

internal fun interface CompanionRuntimeLogger {
    fun log(event: CompanionRuntimeLogEvent)
}

internal fun interface CompanionRuntimeSessionObserver {
    fun onRuntimeEvent(event: String, snapshot: CompanionRuntimeSnapshot)
}

internal interface CompanionHostPort {
    val rendererOwnerId: RendererOwnerId

    fun render(attempt: RenderAttempt)
    fun dismissPresentation()
    fun dismissPresentationAfterCover(hostOperationId: OperationId, delayMillis: Long)
    fun prepareForReclaim(lease: ReclaimLease)
    fun launchRestoreGuard(lease: ReclaimLease): Boolean
}

/**
 * Process-scoped reducer and effect coordinator for secondary-display disposition.
 *
 * Root operations are linear. Presentation renders are child operations bound to both their parent
 * root and the currently bound renderer owner. Dispatch callbacks can advance a matching flight, but
 * only an exact [presentationShown] callback can establish [CompanionRuntimeSnapshot.mirrorActive].
 */
internal class CompanionRuntimeCoordinator(
    scheduler: CompanionRuntimeScheduler,
    idSource: OperationIdSource = OperationIdSource { OperationId.create() },
    private val timeouts: CompanionRuntimeTimeouts = CompanionRuntimeTimeouts(),
    private val logger: CompanionRuntimeLogger = CompanionRuntimeLogger {},
    private val sessionObserver: CompanionRuntimeSessionObserver = CompanionRuntimeSessionObserver { _, _ -> },
    /** ret2: the operation of the Main dual-screen game that is running right now, or null: what a reclaim is "over". Read under the lock; must not block. */
    private val activeGameOperation: () -> String? = { null },
) {
    private val lock = Any()
    private val ledger = ReclaimSessionLedger(idSource)
    private var disposition: SecondaryDisplayDisposition = SecondaryDisplayDisposition.NoDisplay
    private var stateVersion = 0L
    private var currentDisplayId: Int? = null
    // Updated only by display observation; persisted disposition recovery cannot authorize a launch.
    private var availableDisplayId: Int? = null
    private var renderer: CompanionHostPort? = null
    private var lastRendererOwnerId: RendererOwnerId? = null
    private var currentRootOrigin: PrimaryReturnOrigin? = null
    private var launchLockOperationId: OperationId? = null
    private var launchSettlement: LaunchSettlement? = null

    /**
     *  the operation of the game session MIMIR RECLAIMED display 4 OVER, set when a reclaim is created while that game runs and
     * cleared when Mimir yields (to the game's own window or a host). Lease absence cannot tell "the game removed its own window" (nothing reclaimed:
     * Return needs no yield) from "a reclaim put Mimir over the game's still-existing lower window" (Return must yield so that window shows).
     * Keyed by the session's operation, so a new session never inherits it.
     */
    private var reclaimedOverGameOperation: String? = null
    private var settledReclaimLease: ReclaimLease? = null
    private var rootDeadline: RuntimeCancellation? = null
    private var renderDeadline: RuntimeCancellation? = null
    private val scheduler = scheduler

    private val _snapshot = MutableStateFlow(snapshotLocked())
    val snapshot: StateFlow<CompanionRuntimeSnapshot> = _snapshot.asStateFlow()

    fun bindRenderer(port: CompanionHostPort): Boolean {
        val effects = mutableListOf<Effect>()
        synchronized(lock) {
            val previous = renderer
            if (previous?.rendererOwnerId == port.rendererOwnerId) return true
            if (previous != null) effects += Effect.Dismiss(previous)
            renderer = port
            lastRendererOwnerId = port.rendererOwnerId

            disposition = when (val state = disposition) {
                is SecondaryDisplayDisposition.Presenting -> {
                    mintPresentingLocked(
                        parentId = state.attempt.parentDispositionId,
                        cause = PresentCause.RENDERER_REBOUND,
                        port = port,
                        effects = effects,
                    )
                }
                is SecondaryDisplayDisposition.MirrorPresented -> {
                    mintPresentingLocked(
                        parentId = state.attempt.parentDispositionId,
                        cause = PresentCause.RENDERER_REBOUND,
                        port = port,
                        effects = effects,
                    )
                }
                is SecondaryDisplayDisposition.ReclaimPending -> when (state.phase) {
                    ReclaimPhase.GUARD_REQUESTED -> {
                        effects += Effect.PrepareReclaim(port, state.lease)
                        effects += Effect.LaunchGuard(port, state.lease)
                        state
                    }
                    ReclaimPhase.GUARD_FOREGROUND,
                    ReclaimPhase.RENDER_REQUESTED,
                    -> mintReclaimRenderLocked(state, port, effects)
                    ReclaimPhase.GUARD_DISPATCHED -> state
                }
                else -> state
            }
            publishLocked("RendererBound", disposition)
        }
        execute(effects)
        return true
    }

    fun unbindRenderer(rendererOwnerId: RendererOwnerId): Boolean = synchronized(lock) {
        if (renderer?.rendererOwnerId != rendererOwnerId) return false
        renderer = null
        ledger.invalidateRender(rendererOwnerId)
        renderDeadline?.cancel()
        renderDeadline = null
        disposition = when (val state = disposition) {
            is SecondaryDisplayDisposition.ReclaimPending ->
                if (state.expectedRender?.rendererOwnerId == rendererOwnerId) {
                    state.copy(phase = ReclaimPhase.GUARD_FOREGROUND, expectedRender = null)
                } else {
                    state
                }
            else -> state
        }
        publishLocked("RendererUnbound", disposition)
        true
    }

    fun displayAvailable(displayId: Int): Boolean {
        val effects = mutableListOf<Effect>()
        synchronized(lock) {
            if (displayId < 0) return false
            if (availableDisplayId == displayId && currentDisplayId == displayId &&
                disposition !is SecondaryDisplayDisposition.NoDisplay
            ) {
                return true
            }
            if (currentDisplayId != null && currentDisplayId != displayId) {
                renderer?.let { effects += Effect.Dismiss(it) }
                invalidateOperationsLocked()
                disposition = SecondaryDisplayDisposition.NoDisplay
            }
            val availabilityChanged = availableDisplayId != displayId
            availableDisplayId = displayId
            currentDisplayId = displayId
            if (disposition is SecondaryDisplayDisposition.NoDisplay) {
                disposition = beginPresentRootLocked(PresentCause.DISPLAY_AVAILABLE, effects)
                publishLocked("DisplayAvailable", disposition)
            } else if (availabilityChanged) {
                // Recovery adopts the persisted display id before the selector has observed a live display.
                // Publish that observation without presenting so callers can distinguish a fresh attach from
                // persisted identity alone.
                publishLocked("DisplayAvailableObserved", disposition)
            }
        }
        execute(effects)
        return true
    }

    fun displayRemoved(): Boolean {
        val port: CompanionHostPort?
        synchronized(lock) {
            if (currentDisplayId == null && disposition is SecondaryDisplayDisposition.NoDisplay) return false
            port = renderer
            availableDisplayId = null
            currentDisplayId = null
            invalidateOperationsLocked()
            disposition = SecondaryDisplayDisposition.NoDisplay
            publishLocked("DisplayRemoved", disposition)
        }
        port?.dismissPresentation()
        return true
    }

    fun launchRequested(
        packageName: String,
        displayId: Int?,
        launchMode: String,
        primaryOrigin: PrimaryReturnOrigin,
    ): HostLease? {
        val cleanPackage = packageName.trim()
        val cleanMode = launchMode.trim()
        if (cleanPackage.isEmpty() || cleanMode.isEmpty()) return null
        synchronized(lock) {
            val targetDisplay = displayId ?: return null
            if (targetDisplay < 0 || availableDisplayId != targetDisplay || currentDisplayId != targetDisplay) return null
            cancelDeadlinesLocked()
            val root = ledger.mintRoot(RootOperationKind.LAUNCH)
            val lease = HostLease(root.operationId, cleanPackage, targetDisplay, cleanMode, primaryOrigin)
            currentRootOrigin = primaryOrigin
            launchSettlement = null
            launchLockOperationId = root.operationId
            disposition = SecondaryDisplayDisposition.LaunchPending(lease, LaunchPhase.HOST_REQUESTED)
            scheduleRootDeadlineLocked(root.operationId, timeouts.launchMillis)
            publishLocked("LaunchRequested", disposition)
            return lease
        }
    }

    fun hostStarted(operationId: OperationId): Boolean = synchronized(lock) {
        val state = disposition as? SecondaryDisplayDisposition.LaunchPending ?: return false
        if (!ledger.acceptsRoot(operationId) || state.lease.operationId != operationId) return false
        if (state.phase == LaunchPhase.HOST_STARTED || state.phase == LaunchPhase.TARGET_DISPATCHED) return true
        disposition = state.copy(phase = LaunchPhase.HOST_STARTED)
        publishLocked("HostStarted", disposition)
        true
    }

    fun hostStarted(rawOperationId: String?): Boolean =
        OperationId.parseOrNull(rawOperationId)?.let(::hostStarted) ?: false

    fun targetDispatched(operationId: OperationId): Boolean = synchronized(lock) {
        val state = disposition as? SecondaryDisplayDisposition.LaunchPending ?: return false
        if (!ledger.acceptsRoot(operationId) || state.lease.operationId != operationId) return false
        if (state.phase == LaunchPhase.TARGET_DISPATCHED) return true
        if (state.phase != LaunchPhase.HOST_STARTED) return false
        disposition = state.copy(phase = LaunchPhase.TARGET_DISPATCHED)
        scheduleRootDeadlineLocked(operationId, timeouts.targetCoverageMillis)
        publishLocked("TargetDispatched", disposition)
        true
    }

    fun targetDispatched(rawOperationId: String?): Boolean =
        OperationId.parseOrNull(rawOperationId)?.let(::targetDispatched) ?: false

    fun hostCovered(operationId: OperationId): Boolean {
        val effect: Effect.DismissAfterCover
        synchronized(lock) {
            val state = disposition as? SecondaryDisplayDisposition.LaunchPending ?: return false
            if (!ledger.acceptsRoot(operationId) || state.lease.operationId != operationId) return false
            if (state.phase != LaunchPhase.TARGET_DISPATCHED) return false
            rootDeadline?.cancel()
            rootDeadline = null
            if (launchLockOperationId == operationId) launchLockOperationId = null
            launchSettlement = LaunchSettlement(operationId, LaunchSettlementKind.HOST_COVERED)
            disposition = SecondaryDisplayDisposition.Yielded(state.lease, YieldEvidence.HOST_COVERED)
            reclaimedOverGameOperation = null
            publishLocked("HostCovered", disposition)
            effect = Effect.DismissAfterCover(renderer, operationId, SuppressDismissDelayMillis)
        }
        execute(listOf(effect))
        return true
    }

    fun hostCovered(rawOperationId: String?): Boolean =
        OperationId.parseOrNull(rawOperationId)?.let(::hostCovered) ?: false

    fun hostReturned(
        operationId: OperationId,
        currentPrimaryOrigin: PrimaryReturnOrigin? = null,
        beforeEffects: (ReclaimLease) -> Unit = {},
    ): ReclaimLease? {
        val effects = mutableListOf<Effect>()
        val reclaim: ReclaimLease
        synchronized(lock) {
            val state = disposition as? SecondaryDisplayDisposition.Yielded ?: return null
            val host = state.lease ?: return null
            if (!ledger.acceptsRoot(operationId) || host.operationId != operationId) return null
            cancelDeadlinesLocked()
            val root = ledger.mintRoot(RootOperationKind.RECLAIM)
            val primaryOrigin = newestAuthoritativePrimaryOrigin(host.primaryOrigin, currentPrimaryOrigin)
                ?: host.primaryOrigin
            reclaim = ReclaimLease(
                operationId = root.operationId,
                parentHostOperationId = host.operationId,
                displayId = host.displayId,
                primaryOrigin = primaryOrigin,
                reason = "secondary_app_return",
            )
            currentRootOrigin = reclaim.primaryOrigin
            reclaimedOverGameOperation = runCatching(activeGameOperation).getOrNull()
            disposition = SecondaryDisplayDisposition.ReclaimPending(
                lease = reclaim,
                phase = ReclaimPhase.GUARD_FOREGROUND,
            )
            scheduleRootDeadlineLocked(root.operationId, timeouts.reclaimMillis)
            renderer?.let { port ->
                effects += Effect.PrepareReclaim(port, reclaim)
                disposition = mintReclaimRenderLocked(
                    disposition as SecondaryDisplayDisposition.ReclaimPending,
                    port,
                    effects,
                )
            }
            publishLocked("HostReturned", disposition)
            BottomUiReceipts.log.presentation("reclaim", reclaim.displayId, reclaim.reason, reclaim.operationId.toString())
        }
        beforeEffects(reclaim)
        execute(effects)
        return reclaim
    }

    fun hostReturned(rawOperationId: String?): ReclaimLease? =
        OperationId.parseOrNull(rawOperationId)?.let { hostReturned(it) }

    fun explicitReclaim(
        reason: String,
        primaryOrigin: PrimaryReturnOrigin,
        returningFromPackage: String? = null,
        refreshPresentedMirror: Boolean = false,
    ): ReclaimLease? {
        val effects = mutableListOf<Effect>()
        val reclaim: ReclaimLease
        synchronized(lock) {
            val targetDisplay = currentDisplayId ?: return null
            val recovered = disposition as? SecondaryDisplayDisposition.RecoveryNeeded
            if (recovered?.failure?.code == FailureCode.PROCESS_RECOVERED_NON_MIRROR &&
                (targetDisplay != recovered.displayId || availableDisplayId != recovered.displayId)
            ) {
                return null
            }
            val pending = disposition as? SecondaryDisplayDisposition.ReclaimPending
            if (pending != null && ledger.acceptsRoot(pending.lease.operationId)) {
                return pending.lease
            }
            val refreshSettledMirror = refreshPresentedMirror &&
                reason.trim() in setOf("overlay_edge_back", "main_game_exit", "home_park") &&
                disposition is SecondaryDisplayDisposition.MirrorPresented
            if (!refreshSettledMirror) settledReclaimLeaseLocked()?.let { return it }
            val yieldedHost = (disposition as? SecondaryDisplayDisposition.Yielded)?.lease
            if (yieldedHost != null && !returningFromPackage.isNullOrBlank() &&
                !returningFromPackage.equals(yieldedHost.packageName, ignoreCase = true)
            ) {
                return null
            }
            cancelDeadlinesLocked()
            // Captured before mintRoot supersedes it: the host's own root, and only while the caller made
            // no claim that the app left. See ReclaimLease.coveredHost.
            val coveredHostRoot = yieldedHost
                ?.takeIf { returningFromPackage.isNullOrBlank() }
                ?.let { host -> ledger.currentRoot?.takeIf { it.operationId == host.operationId } }
            val root = ledger.mintRoot(RootOperationKind.RECLAIM)
            val authoritativeOrigin = newestAuthoritativePrimaryOrigin(
                recorded = yieldedHost?.primaryOrigin,
                current = primaryOrigin,
            ) ?: primaryOrigin
            reclaim = ReclaimLease(
                operationId = root.operationId,
                parentHostOperationId = yieldedHost?.operationId,
                displayId = targetDisplay,
                primaryOrigin = authoritativeOrigin,
                reason = reason.trim().ifEmpty { "explicit_reclaim" },
                coveredHost = yieldedHost?.takeIf { coveredHostRoot != null },
                coveredHostRoot = coveredHostRoot,
            )
            currentRootOrigin = reclaim.primaryOrigin
            reclaimedOverGameOperation = runCatching(activeGameOperation).getOrNull()
            launchLockOperationId?.let {
                launchSettlement = LaunchSettlement(it, LaunchSettlementKind.FAILED)
            }
            launchLockOperationId = null
            disposition = SecondaryDisplayDisposition.ReclaimPending(reclaim, ReclaimPhase.GUARD_REQUESTED)
            scheduleRootDeadlineLocked(root.operationId, timeouts.reclaimMillis)
            renderer?.let { port ->
                effects += Effect.PrepareReclaim(port, reclaim)
                effects += Effect.LaunchGuard(port, reclaim)
            }
            publishLocked("ExplicitReclaim", disposition)
            BottomUiReceipts.log.presentation("reclaim", reclaim.displayId, reclaim.reason, reclaim.operationId.toString())
        }
        execute(effects)
        return reclaim
    }

    /** ret2: whether Mimir reclaimed the display over THIS game session (see [reclaimedOverGameOperation]). */
    fun reclaimedOverGame(gameOperation: String): Boolean = synchronized(lock) { reclaimedOverGameOperation == gameOperation }

    /** Yield this exact mirror to an already-running DS/3DS secondary window. */
    fun yieldForExistingSecondaryGame(
        parentOperationId: OperationId,
        packageName: String,
        primaryOrigin: PrimaryReturnOrigin,
    ): Boolean {
        val cleanPackage = packageName.trim()
        if (cleanPackage.isEmpty()) return false
        val effects = mutableListOf<Effect>()
        synchronized(lock) {
            val state = disposition as? SecondaryDisplayDisposition.MirrorPresented ?: return false
            if (state.attempt.parentDispositionId != parentOperationId ||
                ledger.currentRoot?.operationId != parentOperationId ||
                !mirrorActiveLocked()
            ) return false
            val port = renderer ?: return false
            val displayId = currentDisplayId ?: return false
            val origin = currentRootOrigin ?: primaryOrigin
            cancelDeadlinesLocked()
            settledReclaimLease = null
            val root = ledger.mintRoot(RootOperationKind.PRESENT)
            val lease = HostLease(
                operationId = root.operationId,
                packageName = cleanPackage,
                displayId = displayId,
                launchMode = CompanionReturnHostContract.LAUNCH_MODE_EXISTING_MAIN_GAME_RETURN,
                primaryOrigin = origin,
            )
            disposition = SecondaryDisplayDisposition.Yielded(lease, YieldEvidence.USER_REQUESTED_GAME_RETURN)
            reclaimedOverGameOperation = null
            publishLocked("UserRequestedGameReturn", disposition)
            effects += Effect.DismissAfterCover(port, lease.operationId, 0L)
        }
        execute(effects)
        return true
    }

    /**
     * D41 (Thor 2026-10-08): a task Mimir did not launch took display 4 while Mimir was presenting there, so Mimir yields the Bottom to it.
     *
     * Android's Recents, opened from the Bottom's ᛗ, brought a Quick-Launched Chrome back to the front of display 4 under Mimir's still-shown
     * Presentation. No lease existed for that, so nothing could reach `Yielded`: Chrome ran, hidden by a window type that sorts above activities.
     * The floor's own stop is the display-exact proof and a UsageStats resume names the package ([CompanionFloorCoverWatch]); this method is the
     * state change they authorise. It is the same `Yielded(HOST_COVERED)` a Quick Launch reaches, so the park, the ᛗ's stay-visible, the return
     * row and the edge-back reclaim (which finds this lease as its covered host) all work unchanged.
     *
     * Only `Presenting` / `MirrorPresented` yield: any other disposition already has an owner for the display (a launch lease, a reclaim, a
     * recovery) and is none of this method's business. A running Main dual-screen game keeps its own hand-off ([yieldForExistingSecondaryGame]).
     * The Presentation is parked at once rather than after [SuppressDismissDelayMillis]: the floor has already stopped under the task, so the task
     * is on screen and only the Presentation is hiding it.
     */
    fun yieldToForeignTask(
        displayId: Int,
        packageName: String,
        primaryOrigin: PrimaryReturnOrigin,
    ): Boolean {
        val cleanPackage = packageName.trim()
        if (cleanPackage.isEmpty()) return false
        val effects = mutableListOf<Effect>()
        synchronized(lock) {
            val state = disposition
            if (currentDisplayId != displayId ||
                (state !is SecondaryDisplayDisposition.Presenting && state !is SecondaryDisplayDisposition.MirrorPresented)
            ) return false
            if (runCatching(activeGameOperation).getOrNull() != null) return false
            cancelDeadlinesLocked()
            settledReclaimLease = null
            val root = ledger.mintRoot(RootOperationKind.PRESENT)
            val origin = newestAuthoritativePrimaryOrigin(currentRootOrigin, primaryOrigin) ?: primaryOrigin
            val lease = HostLease(root.operationId, cleanPackage, displayId, LaunchModeRecentsPick, origin)
            currentRootOrigin = origin
            reclaimedOverGameOperation = null
            disposition = SecondaryDisplayDisposition.Yielded(lease, YieldEvidence.HOST_COVERED)
            publishLocked("ForeignTaskCovered", disposition, reason = "recents_pick")
            effects += Effect.DismissAfterCover(renderer, lease.operationId, 0L)
        }
        execute(effects)
        return true
    }

    fun restoreGuardDispatched(operationId: OperationId): Boolean = synchronized(lock) {
        val state = disposition as? SecondaryDisplayDisposition.ReclaimPending ?: return false
        if (!ledger.acceptsRoot(operationId) || state.lease.operationId != operationId) return false
        if (state.phase == ReclaimPhase.GUARD_DISPATCHED) return true
        if (state.phase != ReclaimPhase.GUARD_REQUESTED) return false
        disposition = state.copy(phase = ReclaimPhase.GUARD_DISPATCHED)
        publishLocked("RestoreGuardDispatched", disposition)
        true
    }

    fun restoreGuardForeground(operationId: OperationId): Boolean {
        val effects = mutableListOf<Effect>()
        synchronized(lock) {
            val state = disposition as? SecondaryDisplayDisposition.ReclaimPending ?: return false
            if (!ledger.acceptsRoot(operationId) || state.lease.operationId != operationId) return false
            if (state.phase == ReclaimPhase.RENDER_REQUESTED && state.expectedRender != null) return true
            if (state.phase != ReclaimPhase.GUARD_REQUESTED &&
                state.phase != ReclaimPhase.GUARD_DISPATCHED &&
                state.phase != ReclaimPhase.GUARD_FOREGROUND
            ) {
                return false
            }
            disposition = state.copy(phase = ReclaimPhase.GUARD_FOREGROUND, expectedRender = null)
            renderer?.let { port ->
                disposition = mintReclaimRenderLocked(
                    disposition as SecondaryDisplayDisposition.ReclaimPending,
                    port,
                    effects,
                )
            }
            publishLocked("RestoreGuardForeground", disposition)
        }
        execute(effects)
        return true
    }

    fun restoreGuardForeground(rawOperationId: String?): Boolean =
        OperationId.parseOrNull(rawOperationId)?.let(::restoreGuardForeground) ?: false

    fun presentationShown(
        renderOperationId: OperationId,
        parentDispositionId: OperationId,
        rendererOwnerId: RendererOwnerId,
    ): Boolean = synchronized(lock) {
        val expected = settleableRenderLocked(renderOperationId, parentDispositionId, rendererOwnerId) ?: return false
        (disposition as? SecondaryDisplayDisposition.ReclaimPending)?.let {
            settledReclaimLease = it.lease
        }
        cancelDeadlinesLocked()
        disposition = SecondaryDisplayDisposition.MirrorPresented(expected)
        publishLocked("PresentationShown", disposition)
        true
    }

    /** The render [presentationShown] would settle for exactly these ids right now, or null. Caller holds [lock]. */
    private fun settleableRenderLocked(
        renderOperationId: OperationId,
        parentDispositionId: OperationId,
        rendererOwnerId: RendererOwnerId,
    ): RenderAttempt? {
        if (!ledger.acceptsRender(renderOperationId, parentDispositionId, rendererOwnerId)) return null
        val expected = ledger.currentRender ?: return null
        val acceptedState = when (val state = disposition) {
            is SecondaryDisplayDisposition.Presenting ->
                state.attempt == expected
            is SecondaryDisplayDisposition.ReclaimPending ->
                state.expectedRender == expected
            else -> false
        }
        if (!acceptedState || renderer?.rendererOwnerId != rendererOwnerId) return null
        return expected
    }

    fun presentationShown(
        rawRenderOperationId: String?,
        rawParentDispositionId: String?,
        rawRendererOwnerId: String?,
    ): Boolean {
        val renderId = OperationId.parseOrNull(rawRenderOperationId) ?: return false
        val parentId = OperationId.parseOrNull(rawParentDispositionId) ?: return false
        val ownerId = RendererOwnerId.parseOrNull(rawRendererOwnerId) ?: return false
        return presentationShown(renderId, parentId, ownerId)
    }

    fun presentationFailed(
        renderOperationId: OperationId,
        parentDispositionId: OperationId,
        rendererOwnerId: RendererOwnerId,
        detail: String,
    ): Boolean = synchronized(lock) {
        if (!ledger.acceptsRender(renderOperationId, parentDispositionId, rendererOwnerId)) return false
        val failure = FailureReason(FailureCode.RENDER_FAILED, detail)
        // Handle a failed reclaim render like a timeout: a render that fails during a reclaim minted over a
        // covered app hands the root back to that app first, exactly as a render deadline does in [deadline]. The app is
        // still in front on the secondary display; RecoveryNeeded is left for a reclaim with no cover, or a refused retreat.
        val pending = disposition as? SecondaryDisplayDisposition.ReclaimPending
        if (pending != null && pending.lease.operationId == parentDispositionId &&
            retreatToCoveredHostLocked(pending, "RenderFailedRetreat", failure)
        ) {
            return true
        }
        val fallback = originForLocked(parentDispositionId)
        transitionToRecoveryLocked(
            operationId = parentDispositionId,
            failure = failure,
            fallback = fallback,
            event = "PresentationFailed",
        )
        true
    }

    fun presentationFailed(
        rawRenderOperationId: String?,
        rawParentDispositionId: String?,
        rawRendererOwnerId: String?,
        detail: String,
    ): Boolean {
        val renderId = OperationId.parseOrNull(rawRenderOperationId) ?: return false
        val parentId = OperationId.parseOrNull(rawParentDispositionId) ?: return false
        val ownerId = RendererOwnerId.parseOrNull(rawRendererOwnerId) ?: return false
        return presentationFailed(renderId, parentId, ownerId, detail)
    }

    fun launchFailed(operationId: OperationId, detail: String): Boolean {
        val effects = mutableListOf<Effect>()
        synchronized(lock) {
            val state = disposition as? SecondaryDisplayDisposition.LaunchPending ?: return false
            if (!ledger.acceptsRoot(operationId) || state.lease.operationId != operationId) return false
            if (state.phase == LaunchPhase.TARGET_DISPATCHED) {
                transitionToRecoveryLocked(
                    operationId,
                    FailureReason(FailureCode.LAUNCH_FAILED_AFTER_DISPATCH, detail),
                    state.lease.primaryOrigin,
                    "LaunchFailed",
                )
            } else {
                disposition = rollBackLaunchLocked(effects)
                publishLocked("LaunchFailedBeforeDispatch", disposition)
            }
        }
        execute(effects)
        return true
    }

    fun launchFailed(rawOperationId: String?, detail: String): Boolean =
        OperationId.parseOrNull(rawOperationId)?.let { launchFailed(it, detail) } ?: false

    fun hostDestroyed(operationId: OperationId): Boolean {
        val effects = mutableListOf<Effect>()
        synchronized(lock) {
            when (val state = disposition) {
                is SecondaryDisplayDisposition.LaunchPending -> {
                    if (!ledger.acceptsRoot(operationId) || state.lease.operationId != operationId) return false
                    if (state.phase == LaunchPhase.TARGET_DISPATCHED) {
                        transitionToRecoveryLocked(
                            operationId,
                            FailureReason(FailureCode.HOST_DESTROYED_AFTER_DISPATCH, "return host destroyed after dispatch"),
                            state.lease.primaryOrigin,
                            "HostDestroyed",
                        )
                    } else {
                        disposition = rollBackLaunchLocked(effects)
                        publishLocked("HostDestroyedBeforeDispatch", disposition)
                    }
                }
                is SecondaryDisplayDisposition.Yielded -> {
                    val host = state.lease ?: return false
                    if (!ledger.acceptsRoot(operationId) || host.operationId != operationId) return false
                    transitionToRecoveryLocked(
                        operationId,
                        FailureReason(FailureCode.HOST_DESTROYED_WHILE_YIELDED, "return host destroyed while target owned display"),
                        host.primaryOrigin,
                        "HostDestroyed",
                    )
                }
                is SecondaryDisplayDisposition.ReclaimPending -> {
                    if (!ledger.acceptsRoot(operationId) || state.lease.operationId != operationId) return false
                    val failure = FailureReason(
                        FailureCode.RECLAIM_HOST_DESTROYED,
                        "restore guard destroyed before render acknowledgment",
                    )
                    if (!retreatToCoveredHostLocked(state, "HostDestroyedRetreat", failure)) {
                        transitionToRecoveryLocked(
                            operationId,
                            failure,
                            state.lease.primaryOrigin,
                            "HostDestroyed",
                        )
                    }
                }
                else -> return false
            }
        }
        execute(effects)
        return true
    }

    fun hostDestroyed(rawOperationId: String?): Boolean =
        OperationId.parseOrNull(rawOperationId)?.let(::hostDestroyed) ?: false

    fun deadline(operationId: OperationId): Boolean {
        val effects = mutableListOf<Effect>()
        synchronized(lock) {
            val render = ledger.currentRender
            if (render?.operationId == operationId) {
                val failure = FailureReason(FailureCode.RENDER_DEADLINE, "renderer did not acknowledge the expected attempt")
                val pending = disposition as? SecondaryDisplayDisposition.ReclaimPending
                if (pending != null && pending.lease.operationId == render.parentDispositionId &&
                    retreatToCoveredHostLocked(pending, "RenderDeadlineRetreat", failure)
                ) {
                    return true
                }
                val fallback = originForLocked(render.parentDispositionId)
                transitionToRecoveryLocked(
                    render.parentDispositionId,
                    failure,
                    fallback,
                    "RenderDeadline",
                )
                return true
            }
            if (!ledger.acceptsRoot(operationId)) return false
            when (val state = disposition) {
                is SecondaryDisplayDisposition.LaunchPending -> {
                    if (state.phase == LaunchPhase.TARGET_DISPATCHED) {
                        transitionToRecoveryLocked(
                            operationId,
                            FailureReason(FailureCode.LAUNCH_DEADLINE_AFTER_DISPATCH, "target dispatch was not covered"),
                            state.lease.primaryOrigin,
                            "LaunchDeadline",
                        )
                    } else {
                        disposition = rollBackLaunchLocked(effects)
                        publishLocked("LaunchDeadlineBeforeDispatch", disposition)
                    }
                }
                is SecondaryDisplayDisposition.ReclaimPending -> {
                    val failure = FailureReason(
                        FailureCode.RECLAIM_DEADLINE,
                        "reclaim did not receive a matching render acknowledgment",
                    )
                    if (!retreatToCoveredHostLocked(state, "ReclaimDeadlineRetreat", failure)) {
                        transitionToRecoveryLocked(
                            operationId,
                            failure,
                            state.lease.primaryOrigin,
                            "ReclaimDeadline",
                        )
                    }
                }
                else -> return false
            }
        }
        execute(effects)
        return true
    }

    fun deadline(rawOperationId: String?): Boolean =
        OperationId.parseOrNull(rawOperationId)?.let(::deadline) ?: false

    fun recoverPersistedNonMirror(
        record: PersistedNonMirrorLease,
        currentPrimaryOrigin: PrimaryReturnOrigin? = null,
    ): Boolean = synchronized(lock) {
        if (disposition !is SecondaryDisplayDisposition.NoDisplay || ledger.currentRoot != null) return false
        val recovered = ledger.adoptRecoveredRoot(record.operationId) ?: return false
        val authoritativeOrigin = newestAuthoritativePrimaryOrigin(record.primaryOrigin, currentPrimaryOrigin)
        currentDisplayId = record.displayId
        currentRootOrigin = authoritativeOrigin
        disposition = SecondaryDisplayDisposition.RecoveryNeeded(
            operationId = recovered.operationId,
            displayId = record.displayId,
            fallback = authoritativeOrigin,
            failure = FailureReason(FailureCode.PROCESS_RECOVERED_NON_MIRROR, record.description),
        )
        publishLocked("ProcessRecovery", disposition)
        true
    }

    fun recognizes(operationId: OperationId): Boolean = synchronized(lock) {
        ledger.acceptsRoot(operationId)
    }

    fun hostLease(operationId: OperationId): HostLease? = synchronized(lock) {
        when (val state = disposition) {
            is SecondaryDisplayDisposition.LaunchPending -> state.lease.takeIf { it.operationId == operationId }
            is SecondaryDisplayDisposition.Yielded -> state.lease?.takeIf { it.operationId == operationId }
            else -> null
        }
    }

    fun operationOrigin(operationId: OperationId): PrimaryReturnOrigin? = synchronized(lock) {
        originForLocked(operationId)
    }

    fun isMirrorPresentedFor(operationId: OperationId): Boolean = synchronized(lock) {
        val state = disposition as? SecondaryDisplayDisposition.MirrorPresented ?: return false
        state.attempt.parentDispositionId == operationId && mirrorActiveLocked()
    }

    fun isYieldedFor(operationId: OperationId): Boolean = synchronized(lock) {
        val state = disposition as? SecondaryDisplayDisposition.Yielded ?: return false
        state.lease?.operationId == operationId && ledger.acceptsRoot(operationId)
    }

    fun isReclaimRoot(operationId: OperationId): Boolean = synchronized(lock) {
        ledger.kindOf(operationId) == RootOperationKind.RECLAIM
    }

    /**
     * Task A, floor first: the reclaim lease that [presentationShown] would settle for exactly these ids, when its reason
     * asks for the floor ([reclaimRequestsFloorFront]); null otherwise. Read-only, nothing is published. The host brings
     * the floor forward with it and only then calls [presentationShown], whose publish is what the restore guard finishes on.
     */
    fun floorFrontReclaim(
        renderOperationId: OperationId,
        parentDispositionId: OperationId,
        rendererOwnerId: RendererOwnerId,
    ): ReclaimLease? = synchronized(lock) {
        settleableRenderLocked(renderOperationId, parentDispositionId, rendererOwnerId) ?: return null
        (disposition as? SecondaryDisplayDisposition.ReclaimPending)?.lease?.takeIf { reclaimRequestsFloorFront(it.reason) }
    }

    /** Advisory evidence is intentionally non-settling with today's display-less APIs. */
    fun foregroundReturnObserved(packageName: String?): Boolean = false

    /** Close dispatch is not close evidence and cannot mutate display disposition. */
    fun closeDispatchObserved(rawOperationId: String?): Boolean =
        OperationId.parseOrNull(rawOperationId)?.let { false } ?: false

    /** Swap dispatch is not transfer evidence and cannot mutate display disposition. */
    fun swapDispatchObserved(rawOperationId: String?): Boolean =
        OperationId.parseOrNull(rawOperationId)?.let { false } ?: false

    /** Usage/process absence is not certified terminal close evidence. */
    fun processAbsenceObserved(packageName: String): Boolean = false

    private fun beginPresentRootLocked(cause: PresentCause, effects: MutableList<Effect>): SecondaryDisplayDisposition {
        cancelDeadlinesLocked()
        launchLockOperationId = null
        val displayId = currentDisplayId ?: error("Cannot present without a display")
        val root = ledger.mintRoot(RootOperationKind.PRESENT)
        val ownerId = renderer?.rendererOwnerId ?: lastRendererOwnerId
            ?: RendererOwnerId.create().also { lastRendererOwnerId = it }
        val attempt = ledger.mintRender(root.operationId, ownerId, displayId)
            ?: error("Fresh presentation root rejected its child")
        scheduleRenderDeadlineLocked(attempt.operationId)
        renderer?.takeIf { it.rendererOwnerId == ownerId }?.let { effects += Effect.Render(it, attempt) }
        return SecondaryDisplayDisposition.Presenting(attempt, cause)
    }

    private fun mintPresentingLocked(
        parentId: OperationId,
        cause: PresentCause,
        port: CompanionHostPort,
        effects: MutableList<Effect>,
    ): SecondaryDisplayDisposition {
        val displayId = currentDisplayId ?: return disposition
        val attempt = ledger.mintRender(parentId, port.rendererOwnerId, displayId) ?: return disposition
        scheduleRenderDeadlineLocked(attempt.operationId)
        effects += Effect.Render(port, attempt)
        return SecondaryDisplayDisposition.Presenting(attempt, cause)
    }

    private fun mintReclaimRenderLocked(
        state: SecondaryDisplayDisposition.ReclaimPending,
        port: CompanionHostPort,
        effects: MutableList<Effect>,
    ): SecondaryDisplayDisposition.ReclaimPending {
        val attempt = ledger.mintRender(
            state.lease.operationId,
            port.rendererOwnerId,
            state.lease.displayId,
        ) ?: return state
        scheduleRenderDeadlineLocked(attempt.operationId)
        effects += Effect.Render(port, attempt)
        return state.copy(phase = ReclaimPhase.RENDER_REQUESTED, expectedRender = attempt)
    }

    private fun rollBackLaunchLocked(effects: MutableList<Effect>): SecondaryDisplayDisposition {
        val launch = disposition as? SecondaryDisplayDisposition.LaunchPending
        val origin = launch?.lease?.primaryOrigin
        launch?.lease?.operationId?.let {
            launchSettlement = LaunchSettlement(it, LaunchSettlementKind.FAILED)
        }
        launchLockOperationId = null
        val presenting = beginPresentRootLocked(PresentCause.LAUNCH_ROLLED_BACK, effects)
        currentRootOrigin = origin
        return presenting
    }

    /**
     * D2 retreat: a failed reclaim that was minted over a covered host hands the root back to that host
     * (see [ReclaimLease.coveredHost]). The failed reclaim root and its render stop being recognised, which
     * is what makes a standing restore guard fail closed on its next snapshot instead of waiting on a dead
     * lease; the guard's own destruction then reports the dead reclaim id, which a yielded host ignores.
     * The companion hold armed by `PrepareReclaim` is left to expire on its own timer, as recovery leaves it
     * today. Returns false when the lease carries no covered host; callers then fall through to
     * [transitionToRecoveryLocked].
     *
     * [failure] is what the retreat absorbed. `Yielded(host)` stays the whole truth, so the failure is not kept on
     * the disposition; it is printed in the runtime log event's `fail` field (O7 / I1), so every retreat names why
     * the reclaim failed, as recovery does.
     */
    private fun retreatToCoveredHostLocked(
        state: SecondaryDisplayDisposition.ReclaimPending,
        event: String,
        failure: FailureReason,
    ): Boolean {
        val host = state.lease.coveredHost ?: return false
        val hostRoot = state.lease.coveredHostRoot ?: return false
        if (!ledger.retreatToRoot(hostRoot)) return false
        cancelDeadlinesLocked()
        currentRootOrigin = host.primaryOrigin
        disposition = SecondaryDisplayDisposition.Yielded(host, YieldEvidence.HOST_COVERED)
        reclaimedOverGameOperation = null
        publishLocked(event, disposition, absorbedFailure = failure)
        return true
    }

    private fun transitionToRecoveryLocked(
        operationId: OperationId,
        failure: FailureReason,
        fallback: PrimaryReturnOrigin?,
        event: String,
    ) {
        cancelDeadlinesLocked()
        ledger.invalidateRender()
        if (launchLockOperationId == operationId) {
            launchLockOperationId = null
            launchSettlement = LaunchSettlement(operationId, LaunchSettlementKind.FAILED)
        }
        disposition = SecondaryDisplayDisposition.RecoveryNeeded(
            operationId = operationId,
            displayId = currentDisplayId ?: 0,
            fallback = fallback,
            failure = failure,
        )
        publishLocked(event, disposition)
    }

    private fun invalidateOperationsLocked() {
        cancelDeadlinesLocked()
        launchLockOperationId?.let {
            launchSettlement = LaunchSettlement(it, LaunchSettlementKind.FAILED)
        }
        launchLockOperationId = null
        currentRootOrigin = null
        ledger.invalidateAll()
    }

    private fun scheduleRootDeadlineLocked(operationId: OperationId, delayMillis: Long) {
        rootDeadline?.cancel()
        rootDeadline = scheduler.schedule(delayMillis, operationId) { deadline(operationId) }
    }

    private fun scheduleRenderDeadlineLocked(operationId: OperationId) {
        renderDeadline?.cancel()
        renderDeadline = scheduler.schedule(timeouts.renderMillis, operationId) { deadline(operationId) }
    }

    private fun cancelDeadlinesLocked() {
        rootDeadline?.cancel()
        renderDeadline?.cancel()
        rootDeadline = null
        renderDeadline = null
    }

    private fun originForLocked(operationId: OperationId): PrimaryReturnOrigin? {
        if (ledger.acceptsRoot(operationId)) return currentRootOrigin
        return when (val state = disposition) {
            is SecondaryDisplayDisposition.LaunchPending ->
                state.lease.primaryOrigin.takeIf { state.lease.operationId == operationId }
            is SecondaryDisplayDisposition.Yielded ->
                state.lease?.primaryOrigin?.takeIf { state.lease.operationId == operationId }
            is SecondaryDisplayDisposition.ReclaimPending ->
                state.lease.primaryOrigin.takeIf { state.lease.operationId == operationId }
            is SecondaryDisplayDisposition.RecoveryNeeded ->
                state.fallback.takeIf { state.operationId == operationId }
            else -> null
        }
    }

    private fun settledReclaimLeaseLocked(): ReclaimLease? {
        if (disposition is SecondaryDisplayDisposition.RecoveryNeeded) return null
        return settledReclaimLease?.takeIf { ledger.acceptsRoot(it.operationId) }
    }

    /**
     * [absorbedFailure] is a failure the transition absorbed without landing in `RecoveryNeeded`: a covered-host retreat
     * (O7 / I1). It fills the log event's `fail` field only when the destination carries no failure of its own.
     */
    private fun publishLocked(
        event: String,
        next: SecondaryDisplayDisposition,
        absorbedFailure: FailureReason? = null,
        reason: String? = null,
    ) {
        val before = _snapshot.value.disposition
        stateVersion += 1
        disposition = next
        val updated = snapshotLocked()
        _snapshot.value = updated
        // The Bottom takes no key focus (touch-only; FocusAuthority governs Main), so the owner that changes on display 4
        // is who holds its screen and input: Mimir's mirror, a launching app, a covering host, or a reclaim in flight.
        updated.displayId?.let { BottomUiReceipts.log.focusOwner(companionDisplayOwnerLabel(next), it) }
        val attributes = logAttributesFor(next)
        logger.log(
            CompanionRuntimeLogEvent(
                stateVersion = stateVersion,
                event = event,
                rootOperationId = ledger.currentRoot?.operationId,
                renderOperationId = ledger.currentRender?.operationId,
                rendererOwnerId = renderer?.rendererOwnerId,
                from = before::class.java.simpleName,
                to = next::class.java.simpleName,
                pkg = attributes.pkg,
                mode = attributes.mode,
                reason = attributes.reason ?: reason,
                fail = attributes.fail ?: absorbedFailure?.let { "${it.code}:${it.detail}" },
            ),
        )
        sessionObserver.onRuntimeEvent(event, updated)
    }

    /**
     * P1: the destination disposition's identifying attributes, so the runtime log line can carry the
     * package/mode/reason/failure that today only live on the disposition object (never printed).
     */
    private fun logAttributesFor(next: SecondaryDisplayDisposition): RuntimeLogAttributes = when (next) {
        is SecondaryDisplayDisposition.LaunchPending -> RuntimeLogAttributes(
            pkg = next.lease.packageName,
            mode = next.lease.launchMode,
        )
        is SecondaryDisplayDisposition.Yielded -> RuntimeLogAttributes(
            pkg = next.lease?.packageName,
            mode = next.lease?.launchMode,
        )
        is SecondaryDisplayDisposition.ReclaimPending -> RuntimeLogAttributes(
            reason = next.lease.reason,
        )
        is SecondaryDisplayDisposition.RecoveryNeeded -> RuntimeLogAttributes(
            fail = "${next.failure.code}:${next.failure.detail}",
        )
        else -> RuntimeLogAttributes()
    }

    private fun snapshotLocked(): CompanionRuntimeSnapshot = CompanionRuntimeSnapshot(
        stateVersion = stateVersion,
        displayId = currentDisplayId,
        disposition = disposition,
        rendererOwnerId = renderer?.rendererOwnerId,
        mirrorActive = mirrorActiveLocked(),
        launchLockOperationId = launchLockOperationId,
        launchSettlement = launchSettlement,
        availableDisplayId = availableDisplayId,
    )

    private fun mirrorActiveLocked(): Boolean {
        val state = disposition as? SecondaryDisplayDisposition.MirrorPresented ?: return false
        val expected = ledger.currentRender ?: return false
        return state.attempt == expected &&
            ledger.acceptsRoot(expected.parentDispositionId) &&
            renderer?.rendererOwnerId == expected.rendererOwnerId
    }

    private fun execute(effects: List<Effect>) {
        effects.forEach { effect ->
            when (effect) {
                is Effect.Render -> {
                    if (isExpectedRenderEffect(effect.port, effect.attempt)) effect.port.render(effect.attempt)
                }
                is Effect.Dismiss -> effect.port.dismissPresentation()
                is Effect.DismissAfterCover -> effect.port?.dismissPresentationAfterCover(
                    effect.hostOperationId,
                    effect.delayMillis,
                )
                is Effect.PrepareReclaim -> {
                    if (isCurrentReclaimEffect(effect.port, effect.lease)) effect.port.prepareForReclaim(effect.lease)
                }
                is Effect.LaunchGuard -> {
                    if (!isCurrentGuardLaunchEffect(effect.port, effect.lease)) return@forEach
                    if (effect.port.launchRestoreGuard(effect.lease)) {
                        restoreGuardDispatched(effect.lease.operationId)
                    } else {
                        reclaimGuardLaunchFailed(effect.lease.operationId)
                    }
                }
            }
        }
    }

    private fun isExpectedRenderEffect(port: CompanionHostPort, attempt: RenderAttempt): Boolean = synchronized(lock) {
        renderer?.rendererOwnerId == port.rendererOwnerId &&
            ledger.acceptsRender(attempt.operationId, attempt.parentDispositionId, attempt.rendererOwnerId)
    }

    private fun isCurrentReclaimEffect(port: CompanionHostPort, lease: ReclaimLease): Boolean = synchronized(lock) {
        val state = disposition as? SecondaryDisplayDisposition.ReclaimPending ?: return false
        renderer?.rendererOwnerId == port.rendererOwnerId &&
            ledger.acceptsRoot(lease.operationId) && state.lease.operationId == lease.operationId
    }

    private fun isCurrentGuardLaunchEffect(port: CompanionHostPort, lease: ReclaimLease): Boolean = synchronized(lock) {
        val state = disposition as? SecondaryDisplayDisposition.ReclaimPending ?: return false
        renderer?.rendererOwnerId == port.rendererOwnerId &&
            ledger.acceptsRoot(lease.operationId) &&
            state.lease.operationId == lease.operationId &&
            state.phase == ReclaimPhase.GUARD_REQUESTED
    }

    private fun reclaimGuardLaunchFailed(operationId: OperationId): Boolean = synchronized(lock) {
        val state = disposition as? SecondaryDisplayDisposition.ReclaimPending ?: return false
        if (!ledger.acceptsRoot(operationId) || state.lease.operationId != operationId) return false
        val failure = FailureReason(FailureCode.RECLAIM_GUARD_DISPATCH_FAILED, "restore guard dispatch failed")
        if (!retreatToCoveredHostLocked(state, "RestoreGuardDispatchFailedRetreat", failure)) {
            transitionToRecoveryLocked(
                operationId,
                failure,
                state.lease.primaryOrigin,
                "RestoreGuardDispatchFailed",
            )
        }
        true
    }

    private sealed interface Effect {
        data class Render(val port: CompanionHostPort, val attempt: RenderAttempt) : Effect
        data class Dismiss(val port: CompanionHostPort) : Effect
        data class DismissAfterCover(
            val port: CompanionHostPort?,
            val hostOperationId: OperationId,
            val delayMillis: Long,
        ) : Effect
        data class PrepareReclaim(val port: CompanionHostPort, val lease: ReclaimLease) : Effect
        data class LaunchGuard(val port: CompanionHostPort, val lease: ReclaimLease) : Effect
    }

    private companion object {
        const val SuppressDismissDelayMillis = 600L
    }
}

/** The lease mode of a task that took display 4 on its own, with no launch of Mimir's (see [CompanionRuntimeCoordinator.yieldToForeignTask]). */
internal const val LaunchModeRecentsPick = "recents_pick"

/**
 * Whether landing on [lease] means its app moved from Main to the Bottom, which is what ends that app's Main session. A recents-pick task merely
 * covered the floor and moved nothing: the same package may still be running on Main (a second instance), and its Main session must stand.
 */
internal fun yieldedLeaseMovedMainApp(lease: HostLease): Boolean = lease.launchMode != LaunchModeRecentsPick

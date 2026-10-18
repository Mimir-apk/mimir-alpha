package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.tweaker.TweakerV4ActivationRequest
import com.gruning.mimir.rewrite.core.launch.LaunchAttemptId
import com.gruning.mimir.rewrite.core.tweaker.TweakerFpsOwner
import com.gruning.mimir.rewrite.core.tweaker.TweakerV4Owner
import com.gruning.mimir.rewrite.core.tweaker.TweakerV4OwnerOutcome
import com.gruning.mimir.rewrite.core.tweaker.TweakerV4OwnerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface PendingTweakerV4Launch {
    val attemptId: LaunchAttemptId
    val profileId: String
    val packageName: String
    val expiresAtElapsedRealtimeMs: Long

    data class Product(
        override val attemptId: LaunchAttemptId,
        override val profileId: String,
        override val packageName: String,
        override val expiresAtElapsedRealtimeMs: Long,
        val activateProfile: Boolean = true,
        val systemProfileId: String = profileId,
        val frameLimitHz: Int? = null,
        val gameFpsPolicyRevision: Long = 0L,
    ) : PendingTweakerV4Launch {
        init {
            require(activateProfile || frameLimitHz != null)
            require(frameLimitHz == null || frameLimitHz in MIN_FRAME_LIMIT_HZ..MAX_FRAME_LIMIT_HZ)
            require(gameFpsPolicyRevision >= 0L)
        }
    }

    data class DebugLab(
        override val attemptId: LaunchAttemptId,
        override val profileId: String,
        override val packageName: String,
        override val expiresAtElapsedRealtimeMs: Long,
    ) : PendingTweakerV4Launch

}

internal sealed interface TweakerV4LaunchHandoff {
    /** Stock, unavailable, or already-explained preflight: there is intentionally no mutation job. */
    data object NotScheduled : TweakerV4LaunchHandoff

    /** The exact one-shot ticket is now owned by the process job. No Binder call preceded this result. */
    data class Started(val profileId: String) : TweakerV4LaunchHandoff

    data class Rejected(val reason: String) : TweakerV4LaunchHandoff
}

/**
 * What a busy transport means at hand-off time, which depends entirely on whether there was anything
 * to hand off.
 *
 * A busy transport only costs something if a ticket was scheduled. With none — a Stock selection, or a
 * profile already applied system-wide — nothing was going to happen anyway, so the transport's state
 * is irrelevant and the answer is [TweakerV4LaunchHandoff.NotScheduled], which is silent by design.
 * Reporting a rejection instead manufactured a warning about a non-event, which produced the reported launch-time warning: it never involved lost tuning.
 */
internal fun busyTransportHandoff(hasPendingLaunch: Boolean): TweakerV4LaunchHandoff =
    if (hasPendingLaunch) {
        TweakerV4LaunchHandoff.Rejected("Tweaker transport is busy")
    } else {
        TweakerV4LaunchHandoff.NotScheduled
    }

internal sealed interface TweakerV4LaunchRuntimeOutcome {
    val profileId: String

    data class Active(override val profileId: String) : TweakerV4LaunchRuntimeOutcome
    data class DebugLabStarted(override val profileId: String) : TweakerV4LaunchRuntimeOutcome
    data class Failed(
        override val profileId: String,
        val reason: String,
        val phase: TweakerV4LaunchFailurePhase = TweakerV4LaunchFailurePhase.ACTIVATION,
        val noNewSessionConfirmed: Boolean = false,
        val stockConfirmed: Boolean = false,
    ) : TweakerV4LaunchRuntimeOutcome
}

internal enum class TweakerV4LaunchFailurePhase {
    ACTIVATION,
    RENEWAL,
}

/**
 * Result of one explicit live frame-limit change while a game is already on screen. Busy means no
 * lifecycle job ran and the caller still owns any transport claim it made; every other value means
 * this lifecycle owns transport cleanup through its usual job/settlement machinery.
 */
internal sealed interface TweakerLiveFpsOutcome {
    data class Applied(val owner: TweakerFpsOwner) : TweakerLiveFpsOutcome
    data object Stock : TweakerLiveFpsOutcome
    data class Rejected(val reason: String) : TweakerLiveFpsOutcome
    data class Busy(val reason: String) : TweakerLiveFpsOutcome
    /** A terminal settlement pre-empted the change before any mutation; settlement owns the transport. */
    data class Superseded(val reason: String) : TweakerLiveFpsOutcome
    data class Uncertain(val reason: String) : TweakerLiveFpsOutcome
}

/** Small injectable boundary around the already-tested strict V4 owner. */
internal enum class TweakerV4OwnerPortDisposition {
    ACTIVE,
    STOCK,
    PENDING,
    BUSY,
    SUPERSEDED,
    REJECTED,
    BLOCKED,
    UNCERTAIN,
}

internal data class TweakerV4OwnerPortOutcome(
    val disposition: TweakerV4OwnerPortDisposition,
    val reason: String,
) {
    val isActive: Boolean get() = disposition == TweakerV4OwnerPortDisposition.ACTIVE
    val isStockConfirmed: Boolean get() = disposition == TweakerV4OwnerPortDisposition.STOCK
}

internal interface TweakerV4OwnerPort {
    fun mayOwnTransport(): Boolean
    fun currentOutcome(): TweakerV4OwnerPortOutcome
    suspend fun confirmAfterProcessStart(bindTimeoutMs: Long, queryTimeoutMs: Long): TweakerV4OwnerPortOutcome
    suspend fun activate(request: TweakerV4ActivationRequest): TweakerV4OwnerPortOutcome
    suspend fun renew(queryTimeoutMs: Long, operationTimeoutMs: Long): TweakerV4OwnerPortOutcome
    suspend fun settle(queryTimeoutMs: Long, operationTimeoutMs: Long): TweakerV4OwnerPortOutcome
    suspend fun reconcile(queryTimeoutMs: Long, operationTimeoutMs: Long): TweakerV4OwnerPortOutcome
}

internal class AndroidTweakerV4OwnerPort(
    private val owner: TweakerV4Owner,
) : TweakerV4OwnerPort {
    override fun mayOwnTransport(): Boolean = when (owner.state.value) {
        TweakerV4OwnerState.Unconfirmed,
        TweakerV4OwnerState.Stock,
        -> false
        else -> true
    }

    override fun currentOutcome(): TweakerV4OwnerPortOutcome = when (val state = owner.state.value) {
        TweakerV4OwnerState.Stock -> TweakerV4OwnerPortOutcome(
            TweakerV4OwnerPortDisposition.STOCK,
            "Tweaker remained on Stock",
        )
        TweakerV4OwnerState.Unconfirmed -> TweakerV4OwnerPortOutcome(
            TweakerV4OwnerPortDisposition.UNCERTAIN,
            "Tweaker process posture is unconfirmed",
        )
        is TweakerV4OwnerState.Active -> TweakerV4OwnerPortOutcome(
            TweakerV4OwnerPortDisposition.ACTIVE,
            "Tweaker owner is active",
        )
        is TweakerV4OwnerState.Blocked -> TweakerV4OwnerPortOutcome(
            TweakerV4OwnerPortDisposition.BLOCKED,
            state.reason,
        )
        else -> TweakerV4OwnerPortOutcome(
            TweakerV4OwnerPortDisposition.PENDING,
            "Tweaker owner is still settling",
        )
    }

    override suspend fun confirmAfterProcessStart(
        bindTimeoutMs: Long,
        queryTimeoutMs: Long,
    ): TweakerV4OwnerPortOutcome =
        owner.confirmAfterProcessStart(bindTimeoutMs, queryTimeoutMs).toPortOutcome()

    override suspend fun activate(request: TweakerV4ActivationRequest): TweakerV4OwnerPortOutcome =
        owner.activate(request).toPortOutcome()

    override suspend fun renew(
        queryTimeoutMs: Long,
        operationTimeoutMs: Long,
    ): TweakerV4OwnerPortOutcome = owner.renew(queryTimeoutMs, operationTimeoutMs).toPortOutcome()

    override suspend fun settle(
        queryTimeoutMs: Long,
        operationTimeoutMs: Long,
    ): TweakerV4OwnerPortOutcome = owner.settle(queryTimeoutMs, operationTimeoutMs).toPortOutcome()

    override suspend fun reconcile(
        queryTimeoutMs: Long,
        operationTimeoutMs: Long,
    ): TweakerV4OwnerPortOutcome = owner.reconcile(queryTimeoutMs, operationTimeoutMs).toPortOutcome()
}

/**
 * Process-owned launch lifecycle above [TweakerV4Owner]. Scheduling is memory-only and performs zero
 * service work. The exact post-IntentSent callback only moves a one-shot ticket into [scope]; all Binder
 * work happens after that local handoff and therefore cannot be stranded by the launch hook's waiter.
 */
internal class TweakerV4LaunchLifecycle(
    private val owner: TweakerV4OwnerPort,
    private val fps: TweakerFpsLaunchPort = DisabledTweakerFpsLaunchPort,
    private val scope: CoroutineScope,
    private val elapsedRealtimeMs: () -> Long,
    private val delayMs: suspend (Long) -> Unit = { delay(it) },
    private val renewalCadenceMs: Long = DEFAULT_RENEWAL_CADENCE_MS,
    private val bindTimeoutMs: Long = DEFAULT_BIND_TIMEOUT_MS,
    private val queryTimeoutMs: Long = DEFAULT_QUERY_TIMEOUT_MS,
    private val operationTimeoutMs: Long = DEFAULT_OPERATION_TIMEOUT_MS,
    private val settlementJoinTimeoutMs: Long = DEFAULT_SETTLEMENT_JOIN_TIMEOUT_MS,
    private val beforeActivationCommit: suspend () -> Unit = {},
    private val onTransportReleased: () -> Unit = {},
) {
    private val stateLock = Any()
    private var preparationEpoch = 0L
    private var preparationAttemptId: LaunchAttemptId? = null
    private val retiredAttempts = LinkedHashSet<LaunchAttemptId>()
    private var pending: PendingTweakerV4Launch? = null
    private var processConfirmationStarted = false
    private var processConfirmationJob: Job? = null
    private var activationJob: Job? = null
    private var ownerCallCommitted = false
    private var fpsCallCommitted = false
    private var fpsPackageName: String? = null
    private var fpsOwner: TweakerFpsOwner? = null
    private var fpsDebtRetained = false
    private var renewalJob: Job? = null
    private var settlement: CompletableDeferred<TweakerV4OwnerPortOutcome>? = null
    private var terminalRequested = false

    /** Starts a new preflight generation and makes any abandoned generation unable to publish a ticket. */
    fun beginPreparation(attemptId: LaunchAttemptId): Long = synchronized(stateLock) {
        preparationEpoch += 1L
        preparationAttemptId = attemptId
        pending = null
        if (processConfirmationJob?.isActive != true && activationJob?.isActive != true &&
            renewalJob?.isActive != true && settlement?.isActive != true && !fpsMayBeActiveLocked()
        ) {
            terminalRequested = false
        }
        preparationEpoch
    }

    /** Publishes only into the still-current generation. This performs no service or owner operation. */
    fun schedule(epoch: Long, ticket: PendingTweakerV4Launch): Boolean = synchronized(stateLock) {
        if (epoch != preparationEpoch || ticket.attemptId != preparationAttemptId ||
            ticket.attemptId in retiredAttempts || terminalRequested ||
            processConfirmationJob?.isActive == true || activationJob?.isActive == true ||
            renewalJob?.isActive == true || settlement?.isActive == true || fpsMayBeActiveLocked()
        ) {
            false
        } else {
            pending = ticket
            true
        }
    }

    /** Invalidates both a published ticket and any late result from an abandoned detached preflight. */
    fun retireAttempt(attemptId: LaunchAttemptId) {
        synchronized(stateLock) {
            rememberRetiredLocked(attemptId)
            if (preparationAttemptId == attemptId || pending?.attemptId == attemptId) {
                preparationEpoch += 1L
                preparationAttemptId = null
                pending = null
            }
        }
    }

    /** True while the sole process session must not be rebound or unbound by observation work. */
    fun mayOwnTransport(): Boolean = synchronized(stateLock) {
        processConfirmationJob?.isActive == true || activationJob?.isActive == true ||
            ownerCallCommitted || renewalJob?.isActive == true || settlement?.isActive == true ||
            fpsMayBeActiveLocked() || owner.mayOwnTransport()
    }

    /** Keeps read-only observers out of the exact pre-dispatch/post-dispatch ticket window. */
    fun hasPendingLaunch(): Boolean = synchronized(stateLock) { pending != null }

    /** One query-only process-start posture confirmation; Activity recreation cannot schedule another. */
    fun confirmProcessStartOnce(
        validatePair: suspend () -> Boolean,
        onFinished: () -> Unit,
    ): Boolean {
        val job: Job
        synchronized(stateLock) {
            if (processConfirmationStarted) return false
            processConfirmationStarted = true
            job = scope.launch(start = CoroutineStart.LAZY) {
                if (validatePair()) {
                    owner.confirmAfterProcessStart(bindTimeoutMs, queryTimeoutMs)
                }
            }
            processConfirmationJob = job
        }
        job.invokeOnCompletion {
            val transportReleased = synchronized(stateLock) {
                if (processConfirmationJob === job) processConfirmationJob = null
                transportIsIdleLocked()
            }
            if (transportReleased) onTransportReleased()
            onFinished()
        }
        job.start()
        return true
    }

    /**
     * Exact post-IntentSent entry. It performs only local validation plus a process-scope job handoff;
     * [validate], [startDebugLab], and every V4 owner call run inside that job.
     */
    fun handOffAfterIntentSent(
        attemptId: LaunchAttemptId,
        packageName: String,
        validate: suspend (PendingTweakerV4Launch) -> Boolean,
        startDebugLab: suspend (PendingTweakerV4Launch.DebugLab) -> Boolean,
        onRuntimeOutcome: suspend (TweakerV4LaunchRuntimeOutcome) -> Unit,
    ): TweakerV4LaunchHandoff {
        val now = elapsedRealtimeMs()
        val ticket: PendingTweakerV4Launch
        val job: Job
        synchronized(stateLock) {
            if (attemptId in retiredAttempts) {
                return TweakerV4LaunchHandoff.Rejected("Launch attempt was already retired")
            }
            val candidate = pending
            if (candidate == null) {
                rememberRetiredLocked(attemptId)
                return TweakerV4LaunchHandoff.NotScheduled
            }
            if (candidate.attemptId != attemptId) {
                return TweakerV4LaunchHandoff.Rejected("Launch attempt did not match its Tweaker ticket")
            }
            // The handoff itself closes this generation, including the timeout race where no ticket
            // arrived yet but the detached preparation may try to publish one later.
            rememberRetiredLocked(attemptId)
            preparationEpoch += 1L
            preparationAttemptId = null
            pending = null
            if (candidate.packageName != packageName) {
                return TweakerV4LaunchHandoff.Rejected("Launch package did not match its Tweaker ticket")
            }
            if (now >= candidate.expiresAtElapsedRealtimeMs) {
                return TweakerV4LaunchHandoff.Rejected("Tweaker launch ticket expired")
            }
            if (processConfirmationJob?.isActive == true || activationJob?.isActive == true ||
                renewalJob?.isActive == true || settlement?.isActive == true || fpsMayBeActiveLocked()
            ) {
                return TweakerV4LaunchHandoff.Rejected("Another Tweaker owner lifecycle is active")
            }
            terminalRequested = false
            ticket = candidate
            job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    runTicket(ticket, validate, startDebugLab, onRuntimeOutcome)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    emitUnlessTerminal(
                        TweakerV4LaunchRuntimeOutcome.Failed(
                            ticket.profileId,
                            failure.message ?: "Tweaker owner lifecycle failed",
                        ),
                        onRuntimeOutcome,
                    )
                }
            }
            activationJob = job
        }
        job.invokeOnCompletion {
            val transportReleased = synchronized(stateLock) {
                if (activationJob === job) activationJob = null
                transportIsIdleLocked()
            }
            if (transportReleased) onTransportReleased()
        }
        job.start()
        return TweakerV4LaunchHandoff.Started(ticket.profileId)
    }

    /** Return, synchronous dispatch failure, or shutdown settlement. Never holds [stateLock] over IPC. */
    suspend fun settle(): TweakerV4OwnerPortOutcome {
        val claim = synchronized(stateLock) {
            settlement?.takeIf { it.isActive }?.let { return@synchronized SettlementClaim.Join(it) }
            val result = CompletableDeferred<TweakerV4OwnerPortOutcome>()
            settlement = result
            preparationEpoch += 1L
            preparationAttemptId = null
            pending = null
            terminalRequested = true
            activationJob?.cancel()
            renewalJob?.cancel()
            SettlementClaim.Own(
                result = result,
                jobs = listOfNotNull(processConfirmationJob, activationJob, renewalJob),
            )
        }
        if (claim is SettlementClaim.Join) return claim.result.await()
        claim as SettlementClaim.Own
        val outcome = try {
            settleOwned(claim.jobs)
        } catch (failure: Throwable) {
            TweakerV4OwnerPortOutcome(
                TweakerV4OwnerPortDisposition.UNCERTAIN,
                failure.message ?: "Tweaker terminal settlement failed",
            )
        }
        synchronized(stateLock) {
            if (renewalJob?.isCompleted != false) renewalJob = null
            if (settlement === claim.result) settlement = null
        }
        claim.result.complete(outcome)
        return outcome
    }

    private suspend fun settleOwned(jobs: List<Job>): TweakerV4OwnerPortOutcome {
        var waitedForJobs = false
        if (synchronized(stateLock) { fpsCallCommitted }) {
            withTimeoutOrNull(settlementJoinTimeoutMs) { jobs.joinAll() }
            waitedForJobs = true
        }
        val fpsApplyStillCommitted = synchronized(stateLock) { fpsCallCommitted }
        var fpsOutcome = if (fpsApplyStillCommitted) {
            retainFpsDebt("Frame-limit apply is still settling")
        } else {
            settleFpsIfNeeded()
        }
        // V4 settlement is unconditional after the bounded FPS-first attempt. A stuck or throwing FPS
        // transport remains explicit debt, but cannot strand the independently-owned profile lease.
        var outcome = if (jobs.isNotEmpty() || owner.mayOwnTransport()) {
            owner.settle(queryTimeoutMs, operationTimeoutMs)
        } else {
            owner.currentOutcome()
        }
        if (jobs.isNotEmpty() && !waitedForJobs) {
            withTimeoutOrNull(settlementJoinTimeoutMs) { jobs.joinAll() }
        }
        val lateFpsNeedsSettlement = synchronized(stateLock) {
            fpsApplyStillCommitted && !fpsCallCommitted && fpsMayBeActiveLocked()
        }
        if (lateFpsNeedsSettlement) {
            // A non-cancellable apply may have finished during V4 settlement. Preserve the composite
            // ordering by restoring that late FPS debt and then settling V4 once more.
            fpsOutcome = settleFpsIfNeeded()
            outcome = owner.settle(queryTimeoutMs, operationTimeoutMs)
        } else if (owner.mayOwnTransport()) {
            outcome = owner.settle(queryTimeoutMs, operationTimeoutMs)
        }
        outcome = reconcileIfStillOwned(outcome)
        return if (fpsOutcome.mayBeActive) {
            TweakerV4OwnerPortOutcome(
                TweakerV4OwnerPortDisposition.UNCERTAIN,
                fpsOutcome.reason,
            )
        } else {
            if (fpsOutcome.isStockConfirmed && !owner.mayOwnTransport()) {
                fps.releaseTransport()
            }
            outcome
        }
    }

    /**
     * Explicit live frame-limit change for the game already on screen. This is deliberately not a
     * second session type: it arms the exact one-slot FPS obligation launch tickets use, so return
     * settlement ordering, retained-debt truth, next-launch blocking, and the service-side
     * Binder-death/hard-lease recovery are shared with the launch path by construction.
     */
    suspend fun applyLiveFps(
        packageName: String,
        targetFps: Int,
        expectedSystemProfileId: String,
        callerClaimedReservation: Boolean,
    ): TweakerLiveFpsOutcome = runLiveFpsChange(callerClaimedReservation) {
        applyLiveFpsBody(packageName, targetFps, expectedSystemProfileId)
    }

    /** Clears a live or launch-armed frame limit now instead of waiting for return settlement. */
    suspend fun restoreLiveFps(callerClaimedReservation: Boolean): TweakerLiveFpsOutcome =
        runLiveFpsChange(callerClaimedReservation) { restoreLiveFpsBody() }

    /**
     * Shared admission and job handoff for one live FPS change. Admission mirrors
     * [handOffAfterIntentSent] except that an active renewal or armed FPS obligation is the expected
     * coexistence case, not a rejection. [callerClaimedReservation] must be the inverse of this
     * lifecycle's own transport ownership so a racing settlement or launch can never share the
     * transport unnoticed.
     */
    private suspend fun runLiveFpsChange(
        callerClaimedReservation: Boolean,
        body: suspend () -> TweakerLiveFpsOutcome,
    ): TweakerLiveFpsOutcome {
        val result = CompletableDeferred<TweakerLiveFpsOutcome>()
        val job: Job
        synchronized(stateLock) {
            if (terminalRequested || settlement?.isActive == true) {
                return TweakerLiveFpsOutcome.Busy("Tweaker return settlement is in progress")
            }
            if (pending != null) {
                return TweakerLiveFpsOutcome.Busy("A Tweaker launch ticket is pending")
            }
            if (processConfirmationJob?.isActive == true || activationJob?.isActive == true) {
                return TweakerLiveFpsOutcome.Busy("Another Tweaker owner lifecycle is active")
            }
            if (fpsCallCommitted) {
                return TweakerLiveFpsOutcome.Busy("A frame-limit change is already settling")
            }
            val lifecycleOwnsTransport = renewalJob?.isActive == true || fpsMayBeActiveLocked() ||
                owner.mayOwnTransport()
            if (lifecycleOwnsTransport == callerClaimedReservation) {
                return TweakerLiveFpsOutcome.Busy("Tweaker transport ownership changed")
            }
            job = scope.launch(start = CoroutineStart.LAZY) {
                result.complete(body())
            }
            activationJob = job
        }
        job.invokeOnCompletion { completion ->
            val transportReleased = synchronized(stateLock) {
                if (activationJob === job) activationJob = null
                transportIsIdleLocked()
            }
            if (transportReleased) onTransportReleased()
            if (!result.isCompleted) {
                // The body is NonCancellable end to end, so an incomplete result means settlement
                // cancelled the job before any mutation work began.
                result.complete(
                    if (completion is CancellationException) {
                        TweakerLiveFpsOutcome.Superseded(
                            "Tweaker settlement pre-empted the frame-limit change",
                        )
                    } else {
                        TweakerLiveFpsOutcome.Uncertain(
                            completion?.message ?: "Frame-limit change did not settle",
                        )
                    },
                )
            }
        }
        job.start()
        return result.await()
    }

    private suspend fun applyLiveFpsBody(
        packageName: String,
        targetFps: Int,
        expectedSystemProfileId: String,
    ): TweakerLiveFpsOutcome = withContext(kotlinx.coroutines.NonCancellable) {
        // Settle any prior obligation first: a cap change and a cap removal share one restore path,
        // and a new cap is never stacked on unsettled debt.
        val prior = settleFpsIfNeeded()
        if (!prior.isStockConfirmed) {
            return@withContext TweakerLiveFpsOutcome.Uncertain(prior.reason)
        }
        if (synchronized(stateLock) { terminalRequested }) {
            return@withContext TweakerLiveFpsOutcome.Superseded(
                "Tweaker settlement pre-empted the frame-limit change",
            )
        }
        val profileOwnerActive = owner.currentOutcome().isActive
        synchronized(stateLock) {
            fpsPackageName = packageName
            fpsCallCommitted = true
        }
        val applied = try {
            fps.activate(
                packageName = packageName,
                targetFps = targetFps,
                expectedSystemProfileId = expectedSystemProfileId,
                profileOwnerActive = profileOwnerActive,
                bindTimeoutMs = bindTimeoutMs,
                queryTimeoutMs = queryTimeoutMs,
                operationTimeoutMs = operationTimeoutMs,
            )
        } catch (failure: Throwable) {
            TweakerFpsLaunchPortOutcome(
                disposition = TweakerFpsLaunchPortDisposition.UNCERTAIN,
                reason = failure.message ?: "Frame-limit apply failed",
                mutationMayBeActive = true,
            )
        }
        synchronized(stateLock) {
            fpsCallCommitted = false
            fpsOwner = applied.owner
            fpsDebtRetained = applied.mayBeActive
            if (!applied.mayBeActive) fpsPackageName = null
        }
        if (synchronized(stateLock) { terminalRequested }) {
            // A terminal settlement raced this apply and may have already returned with retained
            // debt. Mirror the launch ticket's late re-check: settle the freshly-armed obligation
            // now instead of leaving a cap that nothing restores until the next settlement.
            val late = settleFpsIfNeeded()
            if (!late.mayBeActive && !owner.mayOwnTransport()) fps.releaseTransport()
            return@withContext TweakerLiveFpsOutcome.Superseded(
                if (late.isStockConfirmed) {
                    "Tweaker settlement pre-empted the frame-limit change"
                } else {
                    late.reason
                },
            )
        }
        if (applied.isActive) {
            TweakerLiveFpsOutcome.Applied(checkNotNull(applied.owner))
        } else {
            // Unlike a composite launch ticket, a live change never rolls back the profile session
            // the game is already running with; only the FPS side reports its own truth here.
            if (!applied.mayBeActive && !owner.mayOwnTransport()) fps.releaseTransport()
            when (applied.disposition) {
                TweakerFpsLaunchPortDisposition.STOCK,
                TweakerFpsLaunchPortDisposition.REJECTED,
                -> TweakerLiveFpsOutcome.Rejected(applied.reason)
                else -> TweakerLiveFpsOutcome.Uncertain(applied.reason)
            }
        }
    }

    private suspend fun restoreLiveFpsBody(): TweakerLiveFpsOutcome =
        withContext(kotlinx.coroutines.NonCancellable) {
            val settled = settleFpsIfNeeded()
            if (!settled.isStockConfirmed) {
                return@withContext TweakerLiveFpsOutcome.Uncertain(settled.reason)
            }
            if (!owner.mayOwnTransport()) fps.releaseTransport()
            TweakerLiveFpsOutcome.Stock
        }

    private suspend fun runTicket(
        ticket: PendingTweakerV4Launch,
        validate: suspend (PendingTweakerV4Launch) -> Boolean,
        startDebugLab: suspend (PendingTweakerV4Launch.DebugLab) -> Boolean,
        onRuntimeOutcome: suspend (TweakerV4LaunchRuntimeOutcome) -> Unit,
    ) {
        if (!validate(ticket)) {
            emitUnlessTerminal(
                TweakerV4LaunchRuntimeOutcome.Failed(
                    ticket.profileId,
                    "Tweaker launch admission changed before activation",
                    noNewSessionConfirmed = true,
                ),
                onRuntimeOutcome,
            )
            return
        }
        beforeActivationCommit()
        val committed = synchronized(stateLock) {
            if (terminalRequested) false else {
                ownerCallCommitted = true
                true
            }
        }
        if (!committed) return
        try {
            when (ticket) {
                is PendingTweakerV4Launch.DebugLab -> {
                    val started = startDebugLab(ticket)
                    emitUnlessTerminal(
                        if (started) {
                            TweakerV4LaunchRuntimeOutcome.DebugLabStarted(ticket.profileId)
                        } else {
                            TweakerV4LaunchRuntimeOutcome.Failed(
                                ticket.profileId,
                                "Tweaker LAB validation did not start",
                                noNewSessionConfirmed = true,
                            )
                        },
                        onRuntimeOutcome,
                    )
                }
                is PendingTweakerV4Launch.Product -> {
                    runProductTicket(ticket, onRuntimeOutcome)
                }
            }
        } finally {
            synchronized(stateLock) { ownerCallCommitted = false }
        }
    }

    private suspend fun runProductTicket(
        ticket: PendingTweakerV4Launch.Product,
        onRuntimeOutcome: suspend (TweakerV4LaunchRuntimeOutcome) -> Unit,
    ) {
        var profileActive = false
        if (ticket.activateProfile) {
            val profileResult = owner.activate(
                TweakerV4ActivationRequest(
                    profileId = ticket.profileId,
                    packageName = ticket.packageName,
                    bindTimeoutMs = bindTimeoutMs,
                    queryTimeoutMs = queryTimeoutMs,
                    operationTimeoutMs = operationTimeoutMs,
                ),
            )
            if (!profileResult.isActive) {
                val terminal = settleAfterNonActiveOutcome(profileResult)
                emitUnlessTerminal(
                    TweakerV4LaunchRuntimeOutcome.Failed(
                        ticket.profileId,
                        terminal.reason,
                        stockConfirmed = terminal.isStockConfirmed,
                    ),
                    onRuntimeOutcome,
                )
                return
            }
            profileActive = true
        }

        if (synchronized(stateLock) { terminalRequested }) {
            settleCompositeAfterLateActivation(profileActive)
            return
        }

        val targetFps = ticket.frameLimitHz
        if (targetFps != null) {
            synchronized(stateLock) {
                fpsPackageName = ticket.packageName
                fpsCallCommitted = true
            }
            val fpsResult = try {
                withContext(kotlinx.coroutines.NonCancellable) {
                    fps.activate(
                        packageName = ticket.packageName,
                        targetFps = targetFps,
                        expectedSystemProfileId = ticket.systemProfileId,
                        profileOwnerActive = profileActive,
                        bindTimeoutMs = bindTimeoutMs,
                        queryTimeoutMs = queryTimeoutMs,
                        operationTimeoutMs = operationTimeoutMs,
                    )
                }
            } catch (failure: Throwable) {
                // The transport call was entered, so a thrown result is mutation-uncertain until the
                // exact package is queried/restored by the same lifecycle below.
                TweakerFpsLaunchPortOutcome(
                    disposition = TweakerFpsLaunchPortDisposition.UNCERTAIN,
                    reason = failure.message ?: "Frame-limit apply failed",
                    mutationMayBeActive = true,
                )
            }
            synchronized(stateLock) {
                fpsCallCommitted = false
                fpsOwner = fpsResult.owner
                fpsDebtRetained = fpsResult.mayBeActive
                if (!fpsResult.mayBeActive) fpsPackageName = null
            }
            if (!fpsResult.isActive) {
                val profileTerminal = settleCompositeAfterLateActivation(profileActive)
                emitUnlessTerminal(
                    TweakerV4LaunchRuntimeOutcome.Failed(
                        ticket.profileId,
                        fpsResult.reason,
                        stockConfirmed = fpsResult.isStockConfirmed && profileTerminal.isStockConfirmed,
                    ),
                    onRuntimeOutcome,
                )
                return
            }
        }

        if (synchronized(stateLock) { terminalRequested }) {
            settleCompositeAfterLateActivation(profileActive)
        } else {
            if (profileActive) startRenewal(ticket, onRuntimeOutcome)
            emitUnlessTerminal(
                TweakerV4LaunchRuntimeOutcome.Active(ticket.profileId),
                onRuntimeOutcome,
            )
        }
    }

    /** FPS always restores before profile rollback; both are attempted even if either remains uncertain. */
    private suspend fun settleCompositeAfterLateActivation(
        profileMayBeActive: Boolean,
    ): TweakerV4OwnerPortOutcome = withContext(kotlinx.coroutines.NonCancellable) {
        val fpsOutcome = settleFpsIfNeeded()
        val profileOutcome = if (profileMayBeActive || owner.mayOwnTransport()) {
            owner.settle(queryTimeoutMs, operationTimeoutMs)
        } else {
            owner.currentOutcome()
        }
        if (!fpsOutcome.mayBeActive && !owner.mayOwnTransport()) fps.releaseTransport()
        if (fpsOutcome.mayBeActive) {
            TweakerV4OwnerPortOutcome(TweakerV4OwnerPortDisposition.UNCERTAIN, fpsOutcome.reason)
        } else {
            reconcileIfStillOwned(profileOutcome)
        }
    }

    private suspend fun settleFpsIfNeeded(): TweakerFpsLaunchPortOutcome {
        val snapshot = synchronized(stateLock) {
            // A live change can overlap a running renewal, so a committed in-flight apply must surface
            // as retained debt; claiming Stock would let a renewal failure release the transport under
            // it. Reentrant monitor: retainFpsDebt re-takes stateLock inside this decision atomically.
            if (fpsCallCommitted) {
                return retainFpsDebt("Frame-limit apply is still settling")
            }
            val packageName = fpsPackageName
            if (packageName == null || (!fpsDebtRetained && fpsOwner == null)) null else packageName to fpsOwner
        } ?: return TweakerFpsLaunchPortOutcome(
            TweakerFpsLaunchPortDisposition.STOCK,
            "Frame limit is Stock",
        )
        val outcome = try {
            fps.settle(
                packageName = snapshot.first,
                expectedOwner = snapshot.second,
                bindTimeoutMs = bindTimeoutMs,
                operationTimeoutMs = operationTimeoutMs,
            )
        } catch (failure: Throwable) {
            TweakerFpsLaunchPortOutcome(
                disposition = TweakerFpsLaunchPortDisposition.UNCERTAIN,
                reason = failure.message ?: "Frame-limit restore failed",
                owner = snapshot.second,
                mutationMayBeActive = true,
            )
        }
        synchronized(stateLock) {
            if (fpsPackageName == snapshot.first && fpsOwner == snapshot.second) {
                if (outcome.isStockConfirmed) {
                    fpsPackageName = null
                    fpsOwner = null
                    fpsDebtRetained = false
                } else {
                    fpsOwner = outcome.owner ?: fpsOwner
                    fpsDebtRetained = outcome.mayBeActive
                }
            }
        }
        return outcome
    }

    private fun retainFpsDebt(reason: String): TweakerFpsLaunchPortOutcome = synchronized(stateLock) {
        fpsDebtRetained = true
        TweakerFpsLaunchPortOutcome(
            disposition = TweakerFpsLaunchPortDisposition.UNCERTAIN,
            reason = reason,
            owner = fpsOwner,
            mutationMayBeActive = true,
        )
    }

    private fun startRenewal(
        ticket: PendingTweakerV4Launch.Product,
        onRuntimeOutcome: suspend (TweakerV4LaunchRuntimeOutcome) -> Unit,
    ) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            while (true) {
                delayMs(renewalCadenceMs)
                if (synchronized(stateLock) { terminalRequested }) return@launch
                val result = owner.renew(queryTimeoutMs, operationTimeoutMs)
                if (!result.isActive) {
                    val terminal = settleCompositeAfterLateActivation(profileMayBeActive = true)
                    emitUnlessTerminal(
                        TweakerV4LaunchRuntimeOutcome.Failed(
                            ticket.profileId,
                            "Tweaker lease ended: ${terminal.reason}",
                            phase = TweakerV4LaunchFailurePhase.RENEWAL,
                            stockConfirmed = terminal.isStockConfirmed,
                        ),
                        onRuntimeOutcome,
                    )
                    return@launch
                }
            }
        }
        val shouldStart = synchronized(stateLock) {
            if (terminalRequested) {
                false
            } else {
                renewalJob?.cancel()
                renewalJob = job
                true
            }
        }
        if (shouldStart) {
            job.invokeOnCompletion {
                val transportReleased = synchronized(stateLock) {
                    if (renewalJob === job) renewalJob = null
                    transportIsIdleLocked()
                }
                if (transportReleased) onTransportReleased()
            }
            job.start()
        } else {
            job.cancel()
        }
    }

    private suspend fun emitUnlessTerminal(
        outcome: TweakerV4LaunchRuntimeOutcome,
        emit: suspend (TweakerV4LaunchRuntimeOutcome) -> Unit,
    ) {
        if (!synchronized(stateLock) { terminalRequested }) emit(outcome)
    }

    /** Every non-Active apply/renew result gets one terminal coalescing pass before presentation. */
    private suspend fun settleAfterNonActiveOutcome(
        original: TweakerV4OwnerPortOutcome,
    ): TweakerV4OwnerPortOutcome {
        val settled = owner.settle(queryTimeoutMs, operationTimeoutMs)
        if (settled.isStockConfirmed) return settled
        val reconciled = reconcileIfStillOwned(settled)
        return when {
            reconciled.isStockConfirmed -> reconciled
            reconciled.disposition != TweakerV4OwnerPortDisposition.PENDING -> reconciled
            else -> original
        }
    }

    /** A joined lifecycle may leave the owner Pending; one bounded truth read earns Stock or keeps the block. */
    private suspend fun reconcileIfStillOwned(
        prior: TweakerV4OwnerPortOutcome,
    ): TweakerV4OwnerPortOutcome = if (owner.mayOwnTransport()) {
        owner.reconcile(queryTimeoutMs, operationTimeoutMs)
    } else {
        prior
    }

    private fun transportIsIdleLocked(): Boolean =
        processConfirmationJob?.isActive != true && activationJob?.isActive != true &&
            !ownerCallCommitted && renewalJob?.isActive != true && settlement?.isActive != true &&
            !fpsMayBeActiveLocked() && !owner.mayOwnTransport()

    private fun fpsMayBeActiveLocked(): Boolean =
        fpsCallCommitted || fpsDebtRetained || fpsOwner != null

    private fun rememberRetiredLocked(attemptId: LaunchAttemptId) {
        retiredAttempts += attemptId
        while (retiredAttempts.size > MAX_RETIRED_ATTEMPTS) {
            retiredAttempts.iterator().run {
                next()
                remove()
            }
        }
    }

    private sealed interface SettlementClaim {
        data class Join(val result: CompletableDeferred<TweakerV4OwnerPortOutcome>) : SettlementClaim
        data class Own(
            val result: CompletableDeferred<TweakerV4OwnerPortOutcome>,
            val jobs: List<Job>,
        ) : SettlementClaim
    }

    companion object {
        const val DEFAULT_RENEWAL_CADENCE_MS = 10_000L
        const val DEFAULT_BIND_TIMEOUT_MS = 2_500L
        const val DEFAULT_QUERY_TIMEOUT_MS = 2_500L
        const val DEFAULT_OPERATION_TIMEOUT_MS = 9_000L
        // One activation may consume bind + query + operation budgets before its exact rollback runs.
        const val DEFAULT_SETTLEMENT_JOIN_TIMEOUT_MS = 16_000L
        private const val MAX_RETIRED_ATTEMPTS = 64
    }
}

private fun TweakerV4OwnerOutcome.toPortOutcome(): TweakerV4OwnerPortOutcome = when (this) {
    is TweakerV4OwnerOutcome.Active -> TweakerV4OwnerPortOutcome(
        TweakerV4OwnerPortDisposition.ACTIVE,
        "Tweaker owner is active",
    )
    TweakerV4OwnerOutcome.Stock -> TweakerV4OwnerPortOutcome(
        TweakerV4OwnerPortDisposition.STOCK,
        "Tweaker remained on Stock",
    )
    TweakerV4OwnerOutcome.Pending -> TweakerV4OwnerPortOutcome(
        TweakerV4OwnerPortDisposition.PENDING,
        "Tweaker owner is still settling",
    )
    TweakerV4OwnerOutcome.Busy -> TweakerV4OwnerPortOutcome(
        TweakerV4OwnerPortDisposition.BUSY,
        "Tweaker owner is busy",
    )
    TweakerV4OwnerOutcome.Superseded -> TweakerV4OwnerPortOutcome(
        TweakerV4OwnerPortDisposition.SUPERSEDED,
        "Tweaker owner request was superseded",
    )
    is TweakerV4OwnerOutcome.Rejected -> TweakerV4OwnerPortOutcome(
        TweakerV4OwnerPortDisposition.REJECTED,
        message.ifBlank { code },
    )
    is TweakerV4OwnerOutcome.Blocked -> TweakerV4OwnerPortOutcome(
        TweakerV4OwnerPortDisposition.BLOCKED,
        reason,
    )
    is TweakerV4OwnerOutcome.Uncertain -> TweakerV4OwnerPortOutcome(
        TweakerV4OwnerPortDisposition.UNCERTAIN,
        reason,
    )
}

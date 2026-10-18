package com.gruning.mimir.rewrite.core.tweaker

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class TweakerV4ActivationRequest(
    val profileId: String,
    val packageName: String,
    val bindTimeoutMs: Long,
    val queryTimeoutMs: Long,
    val operationTimeoutMs: Long,
) {
    init {
        require(bindTimeoutMs > 0L) { "Bind timeout must be positive" }
        require(queryTimeoutMs > 0L) { "Query timeout must be positive" }
        require(operationTimeoutMs > 0L) { "Operation timeout must be positive" }
    }
}

sealed interface TweakerV4OwnerState {
    data object Unconfirmed : TweakerV4OwnerState
    data object Stock : TweakerV4OwnerState
    data class StartPending(val epoch: Long, val attempt: TweakerV4ApplyAttempt) : TweakerV4OwnerState
    data class Applying(val epoch: Long, val attempt: TweakerV4ApplyAttempt) : TweakerV4OwnerState
    data class Active(val epoch: Long, val owner: TweakerV4OwnedSession) : TweakerV4OwnerState
    data class SettlingApply(val epoch: Long, val attempt: TweakerV4ApplyAttempt) : TweakerV4OwnerState
    data class Restoring(val epoch: Long, val owner: TweakerV4OwnedSession) : TweakerV4OwnerState
    data class Blocked(
        val epoch: Long,
        val reason: String,
        val truth: TweakerV4SessionTruth?,
    ) : TweakerV4OwnerState
}

sealed interface TweakerV4OwnerOutcome {
    data class Active(val owner: TweakerV4OwnedSession) : TweakerV4OwnerOutcome
    data object Stock : TweakerV4OwnerOutcome
    data object Pending : TweakerV4OwnerOutcome
    data object Busy : TweakerV4OwnerOutcome
    data object Superseded : TweakerV4OwnerOutcome
    data class Rejected(val code: String, val message: String) : TweakerV4OwnerOutcome
    data class Blocked(val reason: String, val truth: TweakerV4SessionTruth?) : TweakerV4OwnerOutcome
    data class Uncertain(val reason: String) : TweakerV4OwnerOutcome
}

/** Exact transport boundary. Production adapts one process-owned [TweakerSession]. */
internal interface TweakerV4OwnerTransport {
    suspend fun bind(timeoutMs: Long): Boolean
    suspend fun unbind()
    suspend fun apiVersion(): Int?
    suspend fun query(apiVersion: Int, timeoutMs: Long): TweakerV4SessionAssessment
    fun createAttempt(profileId: String, packageName: String, timeoutMs: Long): TweakerV4ApplyAttempt?
    suspend fun beginActiveSession(): Boolean
    suspend fun apply(apiVersion: Int, attempt: TweakerV4ApplyAttempt): TweakerV4OperationAssessment
    suspend fun cancelInFlight(
        apiVersion: Int,
        attempt: TweakerV4ApplyAttempt,
        timeoutMs: Long,
    ): TweakerV4OperationAssessment
    suspend fun renew(
        apiVersion: Int,
        owner: TweakerV4OwnedSession,
        timeoutMs: Long,
    ): TweakerV4OperationAssessment
    suspend fun restore(
        apiVersion: Int,
        owner: TweakerV4OwnedSession,
        timeoutMs: Long,
    ): TweakerV4OperationAssessment
}

private class TweakerSessionV4OwnerTransport(
    private val session: TweakerSession,
) : TweakerV4OwnerTransport {
    override suspend fun bind(timeoutMs: Long): Boolean = session.bindV4Owner(timeoutMs)
    override suspend fun unbind() = session.unbindV4Owner()
    override suspend fun apiVersion(): Int? = session.getApiVersionAsync()
    override suspend fun query(apiVersion: Int, timeoutMs: Long): TweakerV4SessionAssessment =
        session.querySessionV4Async(apiVersion, timeoutMs)
    override fun createAttempt(
        profileId: String,
        packageName: String,
        timeoutMs: Long,
    ): TweakerV4ApplyAttempt? = session.createApplyAttemptV4(profileId, packageName, timeoutMs)
    override suspend fun beginActiveSession(): Boolean = session.beginActiveSessionV4Async()
    override suspend fun apply(
        apiVersion: Int,
        attempt: TweakerV4ApplyAttempt,
    ): TweakerV4OperationAssessment = session.applyProfileV4(apiVersion, attempt)
    override suspend fun cancelInFlight(
        apiVersion: Int,
        attempt: TweakerV4ApplyAttempt,
        timeoutMs: Long,
    ): TweakerV4OperationAssessment = session.cancelInFlightV4(apiVersion, attempt, timeoutMs)
    override suspend fun renew(
        apiVersion: Int,
        owner: TweakerV4OwnedSession,
        timeoutMs: Long,
    ): TweakerV4OperationAssessment = session.renewLeaseV4(apiVersion, owner, timeoutMs)
    override suspend fun restore(
        apiVersion: Int,
        owner: TweakerV4OwnedSession,
        timeoutMs: Long,
    ): TweakerV4OperationAssessment = session.restoreSessionV4(apiVersion, owner, timeoutMs)
}

/**
 * One process-local API-v4 owner. State locks protect decisions only; no Binder/start call runs
 * while [lock] is held. A process recreation starts from [TweakerV4OwnerState.Unconfirmed].
 */
class TweakerV4Owner internal constructor(
    private val transport: TweakerV4OwnerTransport,
    private val elapsedRealtimeMs: () -> Long,
    private val beforeAttemptCommit: suspend () -> Unit = {},
    private val beforeApplyResultCommit: suspend () -> Unit = {},
    private val beforeTerminalExecution: suspend () -> Unit = {},
    private val beforeRestoreResultCommit: suspend () -> Unit = {},
    private val beforeRenewResultCommit: suspend () -> Unit = {},
    private val beforeTeardownClaim: suspend () -> Unit = {},
) {
    constructor(session: TweakerSession) : this(
        transport = TweakerSessionV4OwnerTransport(session),
        elapsedRealtimeMs = SystemClock::elapsedRealtime,
        beforeAttemptCommit = {},
        beforeApplyResultCommit = {},
        beforeTerminalExecution = {},
        beforeRestoreResultCommit = {},
        beforeRenewResultCommit = {},
        beforeTeardownClaim = {},
    )

    private val lock = Mutex()
    private val epochs = AtomicLong(0L)
    private val _state = MutableStateFlow<TweakerV4OwnerState>(TweakerV4OwnerState.Unconfirmed)
    val state: StateFlow<TweakerV4OwnerState> = _state.asStateFlow()

    private var currentEpoch = 0L
    private var activationBusy = false
    private var terminalRequested = false
    private var applyCallInFlight = false
    private var renewalBusy = false
    private var reconciliationBusy = false
    private var restoreCallInFlight = false
    private var apiVersion: Int? = null
    private var attempt: TweakerV4ApplyAttempt? = null
    private var owner: TweakerV4OwnedSession? = null

    suspend fun activate(request: TweakerV4ActivationRequest): TweakerV4OwnerOutcome {
        val activationJob = coroutineContext[Job]
        return try {
            activateInternal(request, activationJob)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { settleCancelledActivation(request) }
            throw cancelled
        }
    }

    private suspend fun activateInternal(
        request: TweakerV4ActivationRequest,
        activationJob: Job?,
    ): TweakerV4OwnerOutcome {
        val epoch = lock.withLock {
            if (activationBusy || applyCallInFlight || attempt != null || owner != null ||
                _state.value !is TweakerV4OwnerState.Unconfirmed &&
                _state.value !is TweakerV4OwnerState.Stock
            ) {
                return TweakerV4OwnerOutcome.Busy
            }
            epochs.incrementAndGet().also {
                currentEpoch = it
                activationBusy = true
                terminalRequested = false
                apiVersion = null
            }
        }

        val bound = suspendResult { transport.bind(request.bindTimeoutMs) }.getOrDefault(false)
        if (!bound) return failBeforeApply(epoch, "Tweaker service did not bind")
        val version = suspendResult { transport.apiVersion() }.getOrNull()
        if (version == null || version < TWEAKER_V4_MIN_API_VERSION) {
            return failBeforeApply(epoch, "Tweaker service does not support API v4")
        }
        lock.withLock {
            if (epoch != currentEpoch) return TweakerV4OwnerOutcome.Superseded
            apiVersion = version
        }

        when (val queried = safeQuery(version, request.queryTimeoutMs)) {
            is TweakerV4SessionAssessment.Stock -> Unit
            is TweakerV4SessionAssessment.Blocked -> {
                return blockBeforeApply(epoch, "Tweaker is not authoritatively Stock", queried.truth)
            }
            is TweakerV4SessionAssessment.Uncertain -> return failBeforeApply(epoch, queried.reason)
        }
        beforeAttemptCommit()
        val terminalBeforeAttempt = lock.withLock {
            if (epoch != currentEpoch) return TweakerV4OwnerOutcome.Superseded
            if (terminalRequested) {
                _state.value = TweakerV4OwnerState.Stock
                true
            } else {
                false
            }
        }
        if (terminalBeforeAttempt) return finishStock(epoch)

        val created = transport.createAttempt(
            request.profileId,
            request.packageName,
            request.operationTimeoutMs,
        ) ?: return failKnownStock(epoch, "Apply request could not be created")
        val mayStart = lock.withLock {
            if (epoch != currentEpoch) return TweakerV4OwnerOutcome.Superseded
            if (terminalRequested) {
                _state.value = TweakerV4OwnerState.Stock
                false
            } else {
                attempt = created
                _state.value = TweakerV4OwnerState.StartPending(epoch, created)
                true
            }
        }
        if (!mayStart) return finishStock(epoch)

        val started = suspendResult { transport.beginActiveSession() }.getOrDefault(false)
        if (!started) return failBeforeApply(epoch, "Tweaker active-session start was rejected")
        val mayApply = lock.withLock {
            if (epoch != currentEpoch) return TweakerV4OwnerOutcome.Superseded
            if (terminalRequested) {
                _state.value = TweakerV4OwnerState.SettlingApply(epoch, created)
                false
            } else {
                applyCallInFlight = true
                _state.value = TweakerV4OwnerState.Applying(epoch, created)
                true
            }
        }
        if (!mayApply) {
            return reconcileEpoch(epoch, request.queryTimeoutMs, request.operationTimeoutMs, true)
        }

        return withContext(NonCancellable) {
            val applied =
            runCatching { transport.apply(version, created) }.getOrElse {
                TweakerV4OperationAssessment.Uncertain(
                    TweakerV4Operation.APPLY,
                    "Tweaker apply transport failed",
                )
            }
            runCatching { beforeApplyResultCommit() }
            handleApply(
                epoch,
                created,
                applied,
                request.queryTimeoutMs,
                request.operationTimeoutMs,
                activationJob,
            )
        }
    }

    /** New-process posture check. It binds and queries only; it never replays an old owner identity. */
    suspend fun confirmAfterProcessStart(
        bindTimeoutMs: Long,
        queryTimeoutMs: Long,
    ): TweakerV4OwnerOutcome {
        require(bindTimeoutMs > 0L && queryTimeoutMs > 0L)
        val epoch = lock.withLock {
            if (activationBusy || attempt != null || owner != null ||
                _state.value !is TweakerV4OwnerState.Unconfirmed
            ) {
                return TweakerV4OwnerOutcome.Busy
            }
            epochs.incrementAndGet().also {
                currentEpoch = it
                activationBusy = true
                terminalRequested = false
            }
        }
        val bound = runCatching { transport.bind(bindTimeoutMs) }.getOrDefault(false)
        if (!bound) return failBeforeApply(epoch, "Tweaker service did not bind")
        val version = runCatching { transport.apiVersion() }.getOrNull()
        if (version == null || version < TWEAKER_V4_MIN_API_VERSION) {
            return failBeforeApply(epoch, "Tweaker service does not support API v4")
        }
        lock.withLock { apiVersion = version }
        return when (val queried = safeQuery(version, queryTimeoutMs)) {
            is TweakerV4SessionAssessment.Stock -> finishStock(epoch)
            is TweakerV4SessionAssessment.Blocked ->
                releaseBlockedProcessStart(
                    epoch,
                    "Tweaker is not authoritatively Stock",
                    queried.truth,
                )
            is TweakerV4SessionAssessment.Uncertain -> failBeforeApply(epoch, queried.reason)
        }
    }

    /** Idempotent return/dispatch-failure/shutdown settlement. */
    suspend fun settle(
        queryTimeoutMs: Long,
        operationTimeoutMs: Long,
    ): TweakerV4OwnerOutcome = withContext(NonCancellable) {
        require(queryTimeoutMs > 0L && operationTimeoutMs > 0L)
        val plan = lock.withLock {
            terminalRequested = true
            when (val current = _state.value) {
                TweakerV4OwnerState.Stock -> {
                    if (activationBusy) TerminalPlan.Join else TerminalPlan.AlreadyStock(currentEpoch)
                }
                TweakerV4OwnerState.Unconfirmed -> {
                    TerminalPlan.Join
                }
                is TweakerV4OwnerState.StartPending -> TerminalPlan.Join
                is TweakerV4OwnerState.Applying -> {
                    _state.value = TweakerV4OwnerState.SettlingApply(current.epoch, current.attempt)
                    TerminalPlan.Cancel(current.epoch, current.attempt)
                }
                is TweakerV4OwnerState.Active -> {
                    _state.value = TweakerV4OwnerState.Restoring(current.epoch, current.owner)
                    TerminalPlan.Restore(current.epoch, current.owner)
                }
                is TweakerV4OwnerState.SettlingApply,
                is TweakerV4OwnerState.Restoring,
                -> TerminalPlan.Join
                is TweakerV4OwnerState.Blocked -> TerminalPlan.Reconcile(current.epoch)
            }
        }
        runCatching { beforeTerminalExecution() }
        when (plan) {
            is TerminalPlan.AlreadyStock -> finishStock(plan.epoch)
            TerminalPlan.Join -> TweakerV4OwnerOutcome.Pending
            is TerminalPlan.Cancel -> cancelAndReconcile(
                plan.epoch,
                plan.attempt,
                queryTimeoutMs,
                operationTimeoutMs,
            )
            is TerminalPlan.Restore -> restoreExact(
                plan.epoch,
                plan.owner,
                queryTimeoutMs,
                operationTimeoutMs,
            )
            is TerminalPlan.Reconcile -> reconcileEpoch(
                plan.epoch,
                queryTimeoutMs,
                operationTimeoutMs,
                true,
            )
            is TerminalPlan.FailBeforeApply ->
                failBeforeApply(plan.epoch, "Terminal requested before mutation")
        }
    }

    suspend fun renew(
        queryTimeoutMs: Long,
        operationTimeoutMs: Long,
    ): TweakerV4OwnerOutcome = withContext(NonCancellable) {
        renewInternal(queryTimeoutMs, operationTimeoutMs)
    }

    private suspend fun renewInternal(
        queryTimeoutMs: Long,
        operationTimeoutMs: Long,
    ): TweakerV4OwnerOutcome {
        require(queryTimeoutMs > 0L && operationTimeoutMs > 0L)
        val captured = lock.withLock {
            val active = _state.value as? TweakerV4OwnerState.Active
                ?: return TweakerV4OwnerOutcome.Busy
            if (terminalRequested || renewalBusy) return TweakerV4OwnerOutcome.Busy
            if (elapsedRealtimeMs() >= active.owner.leaseDeadlineElapsedRealtimeMs ||
                elapsedRealtimeMs() >= active.owner.hardLeaseDeadlineElapsedRealtimeMs
            ) {
                _state.value = TweakerV4OwnerState.Restoring(active.epoch, active.owner)
                return@withLock RenewalPlan.Restore(active.epoch, active.owner)
            }
            renewalBusy = true
            RenewalPlan.Renew(active.epoch, active.owner)
        }
        if (captured is RenewalPlan.Restore) {
            return restoreExact(
                captured.epoch,
                captured.owner,
                queryTimeoutMs,
                operationTimeoutMs,
            )
        }
        captured as RenewalPlan.Renew
        val version = lock.withLock { apiVersion }
            ?: return blockEpoch(captured.epoch, "Negotiated API version is missing", null)
        val renewed = withContext(NonCancellable) {
            runCatching { transport.renew(version, captured.owner, operationTimeoutMs) }.getOrElse {
                TweakerV4OperationAssessment.Uncertain(
                    TweakerV4Operation.RENEW,
                    "Tweaker renew transport failed",
                )
            }
        }
        runCatching { beforeRenewResultCommit() }
        val accepted = lock.withLock {
            renewalBusy = false
            if (captured.epoch != currentEpoch) return TweakerV4OwnerOutcome.Superseded
            val terminal = terminalRequested
            val nextOwner = (renewed as? TweakerV4OperationAssessment.Accepted)?.owner
            val now = elapsedRealtimeMs()
            val nextOwnerExpired = nextOwner != null &&
                (now >= nextOwner.leaseDeadlineElapsedRealtimeMs ||
                    now >= nextOwner.hardLeaseDeadlineElapsedRealtimeMs)
            if (!terminal && nextOwner != null && !nextOwnerExpired) {
                owner = nextOwner
                _state.value = TweakerV4OwnerState.Active(captured.epoch, nextOwner)
                return TweakerV4OwnerOutcome.Active(nextOwner)
            }
            val restoreOwner = nextOwner ?: captured.owner
            owner = restoreOwner
            _state.value = TweakerV4OwnerState.Restoring(captured.epoch, restoreOwner)
            restoreOwner
        }
        return restoreExact(
            captured.epoch,
            accepted,
            queryTimeoutMs,
            operationTimeoutMs,
        )
    }

    suspend fun reconcile(
        queryTimeoutMs: Long,
        operationTimeoutMs: Long,
    ): TweakerV4OwnerOutcome {
        require(queryTimeoutMs > 0L && operationTimeoutMs > 0L)
        val epoch = lock.withLock {
            if (reconciliationBusy || activationBusy) return TweakerV4OwnerOutcome.Busy
            reconciliationBusy = true
            currentEpoch
        }
        return try {
            reconcileEpoch(epoch, queryTimeoutMs, operationTimeoutMs, true)
        } finally {
            lock.withLock { reconciliationBusy = false }
        }
    }

    private suspend fun handleApply(
        epoch: Long,
        expectedAttempt: TweakerV4ApplyAttempt,
        result: TweakerV4OperationAssessment,
        queryTimeoutMs: Long,
        operationTimeoutMs: Long,
        activationJob: Job?,
    ): TweakerV4OwnerOutcome {
        return when (result) {
            is TweakerV4OperationAssessment.Accepted -> {
                val acceptedOwner = result.owner
                    ?: return reconcileEpoch(epoch, queryTimeoutMs, operationTimeoutMs, true)
                val terminal = lock.withLock {
                    if (epoch != currentEpoch || attempt !== expectedAttempt) {
                        return TweakerV4OwnerOutcome.Superseded
                    }
                    applyCallInFlight = false
                    activationBusy = false
                    owner = acceptedOwner
                    val now = elapsedRealtimeMs()
                    val acceptedOwnerExpired =
                        now >= acceptedOwner.requestDeadlineElapsedRealtimeMs ||
                            now >= acceptedOwner.leaseDeadlineElapsedRealtimeMs ||
                            now >= acceptedOwner.hardLeaseDeadlineElapsedRealtimeMs
                    val terminalNow = terminalRequested ||
                        activationJob?.isActive == false || acceptedOwnerExpired
                    if (terminalNow) terminalRequested = true
                    _state.value = if (terminalNow) {
                        TweakerV4OwnerState.Restoring(epoch, acceptedOwner)
                    } else {
                        TweakerV4OwnerState.Active(epoch, acceptedOwner)
                    }
                    terminalNow
                }
                if (terminal) {
                    restoreExact(epoch, acceptedOwner, queryTimeoutMs, operationTimeoutMs)
                } else {
                    TweakerV4OwnerOutcome.Active(acceptedOwner)
                }
            }
            is TweakerV4OperationAssessment.Rejected -> {
                lock.withLock {
                    if (epoch != currentEpoch || attempt !== expectedAttempt) {
                        return TweakerV4OwnerOutcome.Superseded
                    }
                    applyCallInFlight = false
                    activationBusy = false
                    if (activationJob?.isActive == false) terminalRequested = true
                }
                when {
                    result.truth.state == TweakerV4SessionState.STOCK -> finishStock(epoch)
                    else -> reconcileTruth(
                        epoch,
                        result.truth,
                        queryTimeoutMs,
                        operationTimeoutMs,
                        true,
                    )
                }
            }
            is TweakerV4OperationAssessment.Uncertain -> {
                lock.withLock {
                    if (epoch != currentEpoch || attempt !== expectedAttempt) {
                        return TweakerV4OwnerOutcome.Superseded
                    }
                    applyCallInFlight = false
                    activationBusy = false
                    if (activationJob?.isActive == false) terminalRequested = true
                }
                reconcileEpoch(epoch, queryTimeoutMs, operationTimeoutMs, true)
            }
        }
    }

    private suspend fun cancelAndReconcile(
        epoch: Long,
        expectedAttempt: TweakerV4ApplyAttempt,
        queryTimeoutMs: Long,
        operationTimeoutMs: Long,
    ): TweakerV4OwnerOutcome {
        val version = lock.withLock { apiVersion }
            ?: return blockEpoch(epoch, "Negotiated API version is missing", null)
        withContext(NonCancellable) {
            runCatching {
                transport.cancelInFlight(version, expectedAttempt, operationTimeoutMs)
            }
        }
        return reconcileEpoch(epoch, queryTimeoutMs, operationTimeoutMs, true)
    }

    private suspend fun restoreExact(
        epoch: Long,
        exactOwner: TweakerV4OwnedSession,
        queryTimeoutMs: Long,
        operationTimeoutMs: Long,
    ): TweakerV4OwnerOutcome {
        val claimed = lock.withLock {
            if (epoch != currentEpoch) return TweakerV4OwnerOutcome.Superseded
            if (restoreCallInFlight) {
                false
            } else {
                restoreCallInFlight = true
                owner = exactOwner
                _state.value = TweakerV4OwnerState.Restoring(epoch, exactOwner)
                true
            }
        }
        if (!claimed) return TweakerV4OwnerOutcome.Pending
        val version = lock.withLock { apiVersion }
            ?: run {
                lock.withLock { restoreCallInFlight = false }
                return blockEpoch(epoch, "Negotiated API version is missing", null)
            }
        val result = withContext(NonCancellable) {
            runCatching { transport.restore(version, exactOwner, operationTimeoutMs) }.getOrElse {
                TweakerV4OperationAssessment.Uncertain(
                    TweakerV4Operation.RESTORE,
                    "Tweaker restore transport failed",
                )
            }
        }
        runCatching { beforeRestoreResultCommit() }
        lock.withLock { restoreCallInFlight = false }
        if (epoch != lock.withLock { currentEpoch }) return TweakerV4OwnerOutcome.Superseded
        val stockTruth = when (result) {
            is TweakerV4OperationAssessment.Accepted -> result.truth
            is TweakerV4OperationAssessment.Rejected -> result.truth
            is TweakerV4OperationAssessment.Uncertain -> null
        }
        if (stockTruth?.state == TweakerV4SessionState.STOCK && !stockTruth.mutationMayBeActive) {
            return finishStock(epoch)
        }
        return reconcileEpoch(epoch, queryTimeoutMs, operationTimeoutMs, false)
    }

    private suspend fun reconcileEpoch(
        epoch: Long,
        queryTimeoutMs: Long,
        operationTimeoutMs: Long,
        allowRestore: Boolean,
    ): TweakerV4OwnerOutcome {
        val version = lock.withLock { apiVersion }
            ?: return blockEpoch(epoch, "Negotiated API version is missing", null)
        return when (val queried = safeQuery(version, queryTimeoutMs)) {
            is TweakerV4SessionAssessment.Stock -> finishStock(epoch)
            is TweakerV4SessionAssessment.Blocked -> reconcileTruth(
                epoch,
                queried.truth,
                queryTimeoutMs,
                operationTimeoutMs,
                allowRestore,
            )
            is TweakerV4SessionAssessment.Uncertain -> {
                lock.withLock {
                    if (epoch != currentEpoch) return TweakerV4OwnerOutcome.Superseded
                    activationBusy = false
                    val expected = attempt
                    _state.value = if (expected != null) {
                        TweakerV4OwnerState.SettlingApply(epoch, expected)
                    } else {
                        TweakerV4OwnerState.Blocked(epoch, queried.reason, null)
                    }
                }
                TweakerV4OwnerOutcome.Uncertain(queried.reason)
            }
        }
    }

    private suspend fun reconcileTruth(
        epoch: Long,
        truth: TweakerV4SessionTruth,
        queryTimeoutMs: Long,
        operationTimeoutMs: Long,
        allowRestore: Boolean,
    ): TweakerV4OwnerOutcome {
        if (truth.state == TweakerV4SessionState.STOCK && !truth.mutationMayBeActive) {
            return finishStock(epoch)
        }
        val expectedAttempt = lock.withLock { attempt }
        val exactOwner = expectedAttempt?.let {
            truth.ownedSessionFor(
                requestId = it.requestId,
                originalRequestDeadlineElapsedRealtimeMs = it.requestDeadlineElapsedRealtimeMs,
                observedAtElapsedRealtimeMs = elapsedRealtimeMs(),
            )
        }
        if (exactOwner != null && allowRestore) {
            lock.withLock {
                if (epoch != currentEpoch) return TweakerV4OwnerOutcome.Superseded
                owner = exactOwner
                _state.value = TweakerV4OwnerState.Restoring(epoch, exactOwner)
            }
            return restoreExact(epoch, exactOwner, queryTimeoutMs, operationTimeoutMs)
        }
        if (truth.state == TweakerV4SessionState.APPLYING &&
            expectedAttempt != null && truth.requestId == expectedAttempt.requestId
        ) {
            lock.withLock {
                activationBusy = false
                _state.value = TweakerV4OwnerState.SettlingApply(epoch, expectedAttempt)
            }
            return TweakerV4OwnerOutcome.Pending
        }
        return blockEpoch(epoch, "Tweaker remains non-Stock", truth)
    }

    /**
     * NonCancellable, so this owner cannot end the wait itself: the bound comes from the transport, which stops
     * waiting at the deadline it hands the daemon and answers Uncertain with a timeout (round 5e). The same holds
     * for apply, cancel, renew and restore, each of which this class also waits on under NonCancellable.
     */
    private suspend fun safeQuery(
        version: Int,
        timeoutMs: Long,
    ): TweakerV4SessionAssessment = withContext(NonCancellable) {
        runCatching { transport.query(version, timeoutMs) }.getOrElse {
            TweakerV4SessionAssessment.Uncertain("Tweaker V4 session query failed")
        }
    }

    private suspend fun finishStock(epoch: Long): TweakerV4OwnerOutcome {
        runCatching { beforeTeardownClaim() }
        val mayFinish = lock.withLock {
            if (epoch != currentEpoch) return TweakerV4OwnerOutcome.Superseded
            if (applyCallInFlight || renewalBusy || restoreCallInFlight) {
                if (applyCallInFlight) {
                    attempt?.let { expected ->
                        _state.value = TweakerV4OwnerState.SettlingApply(epoch, expected)
                    }
                } else if (renewalBusy) {
                    owner?.let { activeOwner ->
                        _state.value = TweakerV4OwnerState.Restoring(epoch, activeOwner)
                    }
                }
                false
            } else {
                activationBusy = true
                terminalRequested = false
                renewalBusy = false
                restoreCallInFlight = false
                apiVersion = null
                attempt = null
                owner = null
                _state.value = TweakerV4OwnerState.Stock
                true
            }
        }
        if (!mayFinish) return TweakerV4OwnerOutcome.Pending
        withContext(NonCancellable) { runCatching { transport.unbind() } }
        val teardownFinished = lock.withLock {
            if (epoch != currentEpoch) {
                false
            } else {
                activationBusy = false
                terminalRequested = false
                true
            }
        }
        if (!teardownFinished) return TweakerV4OwnerOutcome.Superseded
        return TweakerV4OwnerOutcome.Stock
    }

    private suspend fun failBeforeApply(epoch: Long, reason: String): TweakerV4OwnerOutcome {
        runCatching { beforeTeardownClaim() }
        lock.withLock {
            if (epoch != currentEpoch) return TweakerV4OwnerOutcome.Superseded
            activationBusy = true
            apiVersion = null
            attempt = null
            owner = null
            _state.value = TweakerV4OwnerState.Unconfirmed
        }
        withContext(NonCancellable) { runCatching { transport.unbind() } }
        val teardownFinished = lock.withLock {
            if (epoch != currentEpoch) {
                false
            } else {
                activationBusy = false
                terminalRequested = false
                true
            }
        }
        if (!teardownFinished) return TweakerV4OwnerOutcome.Superseded
        return TweakerV4OwnerOutcome.Uncertain(reason)
    }

    private suspend fun failKnownStock(epoch: Long, reason: String): TweakerV4OwnerOutcome {
        finishStock(epoch)
        return TweakerV4OwnerOutcome.Uncertain(reason)
    }

    private suspend fun blockBeforeApply(
        epoch: Long,
        reason: String,
        truth: TweakerV4SessionTruth,
    ): TweakerV4OwnerOutcome {
        lock.withLock {
            if (epoch != currentEpoch) return TweakerV4OwnerOutcome.Superseded
            activationBusy = false
            _state.value = TweakerV4OwnerState.Blocked(epoch, reason, truth)
        }
        return TweakerV4OwnerOutcome.Blocked(reason, truth)
    }

    private suspend fun releaseBlockedProcessStart(
        epoch: Long,
        reason: String,
        truth: TweakerV4SessionTruth,
    ): TweakerV4OwnerOutcome {
        runCatching { beforeTeardownClaim() }
        lock.withLock {
            if (epoch != currentEpoch) return TweakerV4OwnerOutcome.Superseded
            activationBusy = true
            terminalRequested = false
            apiVersion = null
            attempt = null
            owner = null
            _state.value = TweakerV4OwnerState.Unconfirmed
        }
        withContext(NonCancellable) { runCatching { transport.unbind() } }
        val teardownFinished = lock.withLock {
            if (epoch != currentEpoch) {
                false
            } else {
                activationBusy = false
                terminalRequested = false
                true
            }
        }
        if (!teardownFinished) return TweakerV4OwnerOutcome.Superseded
        return TweakerV4OwnerOutcome.Blocked(reason, truth)
    }

    private suspend fun settleCancelledActivation(request: TweakerV4ActivationRequest) {
        val plan = lock.withLock {
            terminalRequested = true
            when (val current = _state.value) {
                TweakerV4OwnerState.Unconfirmed -> {
                    if (apiVersion == null && attempt == null && owner == null) {
                        TerminalPlan.FailBeforeApply(currentEpoch)
                    } else {
                        TerminalPlan.Reconcile(currentEpoch)
                    }
                }
                TweakerV4OwnerState.Stock -> TerminalPlan.AlreadyStock(currentEpoch)
                is TweakerV4OwnerState.StartPending -> {
                    _state.value = TweakerV4OwnerState.SettlingApply(current.epoch, current.attempt)
                    TerminalPlan.Reconcile(current.epoch)
                }
                is TweakerV4OwnerState.Applying -> {
                    _state.value = TweakerV4OwnerState.SettlingApply(current.epoch, current.attempt)
                    TerminalPlan.Cancel(current.epoch, current.attempt)
                }
                is TweakerV4OwnerState.Active -> {
                    _state.value = TweakerV4OwnerState.Restoring(current.epoch, current.owner)
                    TerminalPlan.Restore(current.epoch, current.owner)
                }
                is TweakerV4OwnerState.Blocked -> TerminalPlan.Reconcile(current.epoch)
                is TweakerV4OwnerState.SettlingApply,
                is TweakerV4OwnerState.Restoring,
                -> TerminalPlan.Join
            }
        }
        when (plan) {
            is TerminalPlan.AlreadyStock -> finishStock(plan.epoch)
            is TerminalPlan.Cancel -> cancelAndReconcile(
                plan.epoch,
                plan.attempt,
                request.queryTimeoutMs,
                request.operationTimeoutMs,
            )
            is TerminalPlan.Restore -> restoreExact(
                plan.epoch,
                plan.owner,
                request.queryTimeoutMs,
                request.operationTimeoutMs,
            )
            is TerminalPlan.FailBeforeApply ->
                failBeforeApply(plan.epoch, "Activation cancelled before mutation")
            is TerminalPlan.Reconcile -> reconcileEpoch(
                plan.epoch,
                request.queryTimeoutMs,
                request.operationTimeoutMs,
                true,
            )
            TerminalPlan.Join -> Unit
        }
    }

    private suspend fun <T> suspendResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private suspend fun blockEpoch(
        epoch: Long,
        reason: String,
        truth: TweakerV4SessionTruth?,
    ): TweakerV4OwnerOutcome {
        lock.withLock {
            if (epoch != currentEpoch) return TweakerV4OwnerOutcome.Superseded
            activationBusy = false
            renewalBusy = false
            _state.value = TweakerV4OwnerState.Blocked(epoch, reason, truth)
        }
        return TweakerV4OwnerOutcome.Blocked(reason, truth)
    }

    private sealed interface TerminalPlan {
        data class AlreadyStock(val epoch: Long) : TerminalPlan
        data object Join : TerminalPlan
        data class Cancel(val epoch: Long, val attempt: TweakerV4ApplyAttempt) : TerminalPlan
        data class Restore(val epoch: Long, val owner: TweakerV4OwnedSession) : TerminalPlan
        data class Reconcile(val epoch: Long) : TerminalPlan
        data class FailBeforeApply(val epoch: Long) : TerminalPlan
    }

    private sealed interface RenewalPlan {
        data class Renew(val epoch: Long, val owner: TweakerV4OwnedSession) : RenewalPlan
        data class Restore(val epoch: Long, val owner: TweakerV4OwnedSession) : RenewalPlan
    }
}

package com.gruning.mimir.tweaker

/**
 * Process-local ownership of Android's started-service lifetime.
 *
 * Durable journal truth remains the authority after process death. This controller only prevents
 * an in-process stale timeout/completion from stopping a newer start obligation.
 */
internal class StartedServiceLifetime {
    internal enum class Phase {
        IDLE,
        PENDING,
        APPLYING,
        ACTIVE,
        RECOVERY,
    }

    internal data class Snapshot(
        val phase: Phase,
        val latestStartId: Int?,
        val phaseStartId: Int?,
        val pendingDeadlineElapsedRealtimeMs: Long?,
    ) {
        val isIdle: Boolean get() = phase == Phase.IDLE
    }

    internal sealed interface ApplyAdmission {
        data class Admitted(val startId: Int) : ApplyAdmission
        data object NotStarted : ApplyAdmission
        data object Expired : ApplyAdmission
        data object Busy : ApplyAdmission
    }

    private val lock = Any()
    private var phase = Phase.IDLE
    private var latestStartId: Int? = null
    private var phaseStartId: Int? = null
    private var pendingDeadlineElapsedRealtimeMs: Long? = null

    fun recordStart(startId: Int) {
        require(startId >= 0)
        synchronized(lock) {
            if (latestStartId == null || startId > checkNotNull(latestStartId)) {
                latestStartId = startId
            }
        }
    }

    fun beginPending(
        startId: Int,
        nowElapsedRealtimeMs: Long,
        graceMs: Long,
    ): Snapshot {
        require(startId >= 0)
        require(graceMs > 0L)
        synchronized(lock) {
            recordStart(startId)
            if (phase == Phase.APPLYING || phase == Phase.ACTIVE || phase == Phase.RECOVERY) {
                return snapshotLocked()
            }
            phase = Phase.PENDING
            phaseStartId = startId
            pendingDeadlineElapsedRealtimeMs = saturatingAdd(nowElapsedRealtimeMs, graceMs)
            return snapshotLocked()
        }
    }

    fun admitApply(nowElapsedRealtimeMs: Long): ApplyAdmission = synchronized(lock) {
        when (phase) {
            Phase.IDLE -> ApplyAdmission.NotStarted
            Phase.PENDING -> {
                val deadline = pendingDeadlineElapsedRealtimeMs
                    ?: return@synchronized ApplyAdmission.NotStarted
                if (nowElapsedRealtimeMs >= deadline) {
                    clearPhaseLocked()
                    ApplyAdmission.Expired
                } else {
                    phase = Phase.APPLYING
                    pendingDeadlineElapsedRealtimeMs = null
                    ApplyAdmission.Admitted(checkNotNull(phaseStartId))
                }
            }
            Phase.APPLYING, Phase.ACTIVE, Phase.RECOVERY -> ApplyAdmission.Busy
        }
    }

    fun expirePending(
        expectedStartId: Int,
        expectedDeadlineElapsedRealtimeMs: Long,
        nowElapsedRealtimeMs: Long,
    ): Boolean = synchronized(lock) {
        if (phase != Phase.PENDING ||
            phaseStartId != expectedStartId ||
            pendingDeadlineElapsedRealtimeMs != expectedDeadlineElapsedRealtimeMs ||
            nowElapsedRealtimeMs < expectedDeadlineElapsedRealtimeMs
        ) {
            return@synchronized false
        }
        clearPhaseLocked()
        true
    }

    fun markActive(expectedStartId: Int): Boolean = synchronized(lock) {
        if (phase != Phase.APPLYING || phaseStartId != expectedStartId) return@synchronized false
        phase = Phase.ACTIVE
        true
    }

    fun markRecovery(startId: Int? = null) {
        synchronized(lock) {
            startId?.let(::recordStart)
            phase = Phase.RECOVERY
            phaseStartId = startId ?: phaseStartId ?: latestStartId
            pendingDeadlineElapsedRealtimeMs = null
        }
    }

    fun settleApplying(expectedStartId: Int): Boolean = synchronized(lock) {
        if (phase != Phase.APPLYING || phaseStartId != expectedStartId) return@synchronized false
        clearPhaseLocked()
        true
    }

    fun settleActiveOrRecovery(): Boolean = synchronized(lock) {
        if (phase != Phase.ACTIVE && phase != Phase.RECOVERY) return@synchronized false
        clearPhaseLocked()
        true
    }

    /** A stale, generation-bound recovery start must not retire a newer active system profile. */
    fun retainActiveAfterStaleRecovery(): Boolean = synchronized(lock) {
        if (phase != Phase.RECOVERY) return@synchronized false
        phase = Phase.ACTIVE
        phaseStartId = latestStartId
        pendingDeadlineElapsedRealtimeMs = null
        true
    }

    fun snapshot(): Snapshot = synchronized(lock) { snapshotLocked() }

    private fun snapshotLocked(): Snapshot = Snapshot(
        phase = phase,
        latestStartId = latestStartId,
        phaseStartId = phaseStartId,
        pendingDeadlineElapsedRealtimeMs = pendingDeadlineElapsedRealtimeMs,
    )

    private fun clearPhaseLocked() {
        phase = Phase.IDLE
        phaseStartId = null
        pendingDeadlineElapsedRealtimeMs = null
    }

    private fun saturatingAdd(value: Long, delta: Long): Long =
        if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta
}

/** One fail-closed terminal decision for both foreground and started-service release. */
internal data class StartedServiceTerminalTruth(
    val journalEmpty: Boolean,
    val publicOwnerAbsent: Boolean,
    val supervisorSessionAbsent: Boolean,
    val monitorAbsent: Boolean,
    val recoveryOwnerAbsent: Boolean,
    val restoreRetryAbsent: Boolean,
    val foregroundRetryAbsent: Boolean,
    val lifetimeIdle: Boolean,
    val labHoldInactive: Boolean,
    val calibrationInactive: Boolean,
    val transactionWorkTerminal: Boolean,
    /**
     * true when [MimirTweakerService]'s
     * `postControlThermalWatch` is NOT armed. Defaulted `true` (does not block a stop) so every
     * pre-existing caller/test needs no change; the real production call site is the only one
     * that ever passes `false`. Without this, a genuine Restore leaves every OTHER truth here
     * already satisfied (empty journal, no owner, no session, monitor stopped, nothing else
     * pending) and the service self-stops within the same settlement pass that just armed the
     * watch -- `onDestroy` then tears the watch down moments after `startPostControlWatch()`,
     * making D2 inert in exactly the ownerless-hot-device incident it exists for. See
     * [PostControlThermalWatch]'s own kdoc for the mechanism this field keeps alive long enough
     * to run.
     */
    val postControlWatchInactive: Boolean = true,
    /**
     * True when the FPS-limiter session (its journal, public owner, started lifetime and expiry timer) holds no
     * obligation. Defaulted `true` for the same reason as [postControlWatchInactive].
     */
    val fpsSessionIdle: Boolean = true,
    /**
     * true when no post-control fan hold is
     * live AND no hold marker is on disk. Distinct from [postControlWatchInactive] on purpose: a
     * fresh process armed over a stale marker is not "holding", and its watch can be stopped
     * before the first tick settles -- the marker is the obligation, and the fan it names stays
     * at MANUAL until a tick of this process hands it back. Defaulted `true` for the same reason
     * as the two above.
     */
    val postControlHoldInactive: Boolean = true,
)

internal object StartedServiceTerminalGate {
    fun stopStartIdOrNull(
        truth: StartedServiceTerminalTruth,
        latestStartId: Int?,
    ): Int? {
        if (latestStartId == null || latestStartId < 0) return null
        return latestStartId.takeIf {
            truth.journalEmpty &&
                truth.publicOwnerAbsent &&
                truth.supervisorSessionAbsent &&
                truth.monitorAbsent &&
                truth.recoveryOwnerAbsent &&
                truth.restoreRetryAbsent &&
                truth.foregroundRetryAbsent &&
                truth.lifetimeIdle &&
                truth.labHoldInactive &&
                truth.calibrationInactive &&
                truth.transactionWorkTerminal &&
                truth.postControlWatchInactive &&
                truth.fpsSessionIdle &&
                truth.postControlHoldInactive
        }
    }

    /**
     * The stop is decided twice: once on the settlement thread, where the durable journals can be read, and again on
     * the main thread at the instant it would run. This is the second decision, and it is the one that counts.
     *
     * Between the two, `onStartCommand` (which runs on main) can have recorded a newer start and moved the lifetime
     * from IDLE to PENDING. A snapshot taken after `recordStart(N+1)` but before `beginPending(N+1)` reads IDLE with
     * `latestStartId == N+1`, and `stopSelfResult(N+1)` would be accepted by Android: the apply that follows would run
     * in a service that is neither started nor foreground. [truthNow] and [latestStartIdNow] must therefore be read on
     * the main thread, after that start has finished; the stop proceeds only if the very same start id is still the
     * one that may be stopped.
     */
    fun mayStopNow(
        plannedStartId: Int,
        truthNow: StartedServiceTerminalTruth,
        latestStartIdNow: Int?,
    ): Boolean = stopStartIdOrNull(truthNow, latestStartIdNow) == plannedStartId
}

/** Executes the terminal Android calls in the only safe authority order. */
internal object StartedServiceStopExecutor {
    fun stopLatest(
        latestStartId: Int,
        stopSelfResult: (Int) -> Boolean,
        leaveForeground: () -> Unit,
    ): Boolean {
        val stopped = stopSelfResult(latestStartId)
        if (stopped) leaveForeground()
        return stopped
    }
}

/** Observable one-shot dispatch seam for deterministic service disposition tests. */
internal object PostTransactionDispositionDispatch {
    fun dispatch(
        result: TransactionResult,
        journal: JournalLoadResult,
        onDisposition: (PostTransactionDisposition) -> Unit,
    ): PostTransactionDisposition {
        val disposition = PostTransactionGate.decide(result, journal)
        onDisposition(disposition)
        return disposition
    }
}

internal enum class DurableStartedActionPrecedence {
    CONTINUE_REQUESTED_ACTION,
    START_FOREGROUND_RECOVERY,
    RETAIN_FOREGROUND_HOLD,
}

/**
 * Durable journal truth always outranks a newly requested action. SAFE holds without
 * commands; write-capable posture may run the existing recovery worker.
 */
internal fun durableStartedActionPrecedence(
    load: JournalLoadResult,
    hardwareWritesCompiled: Boolean,
): DurableStartedActionPrecedence = when (load) {
    JournalLoadResult.Empty -> DurableStartedActionPrecedence.CONTINUE_REQUESTED_ACTION
    is JournalLoadResult.Loaded -> if (hardwareWritesCompiled) {
        DurableStartedActionPrecedence.START_FOREGROUND_RECOVERY
    } else {
        DurableStartedActionPrecedence.RETAIN_FOREGROUND_HOLD
    }
    is JournalLoadResult.Corrupt -> DurableStartedActionPrecedence.RETAIN_FOREGROUND_HOLD
}

internal object DurableStartedActionDispatch {
    fun dispatch(
        precedence: DurableStartedActionPrecedence,
        startForegroundRecovery: () -> Unit,
        retainForegroundHold: () -> Unit,
    ): Boolean = when (precedence) {
        DurableStartedActionPrecedence.CONTINUE_REQUESTED_ACTION -> false
        DurableStartedActionPrecedence.START_FOREGROUND_RECOVERY -> {
            startForegroundRecovery()
            true
        }
        DurableStartedActionPrecedence.RETAIN_FOREGROUND_HOLD -> {
            retainForegroundHold()
            true
        }
    }
}

internal object DurableForegroundHoldGate {
    fun establishOrRetry(
        establishForeground: () -> Boolean,
        scheduleForegroundOnlyRetry: () -> Unit,
    ): Boolean {
        if (establishForeground()) return true
        scheduleForegroundOnlyRetry()
        return false
    }
}

/** Durable mutation truth retains a started recovery obligation even in a no-write posture. */
internal fun durableJournalRequiresStartedRecovery(load: JournalLoadResult): Boolean =
    load !is JournalLoadResult.Empty

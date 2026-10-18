package com.gruning.mimir.tweaker

internal data class FpsLimiterTerminalTruth(
    val journalEmpty: Boolean,
    val ownerAbsent: Boolean,
    val lifetimeIdle: Boolean,
    val expiryIdle: Boolean = true,
)

internal fun interface FpsLimiterExpiryHandle {
    fun cancel()
}

internal fun interface FpsLimiterExpiryScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): FpsLimiterExpiryHandle
}

/**
 * Process owner and started-service lifetime around the reversible GameManager controller.
 * All mutating methods run on Mimir Tweaker's single transaction worker.
 */
internal class FpsLimiterServiceSession(
    private val controller: FpsLimiterController,
    private val journal: FpsLimiterJournal,
    private val elapsedRealtimeMs: () -> Long,
    private val executeAsync: (() -> Unit) -> Unit,
    private val expiryScheduler: FpsLimiterExpiryScheduler,
    private val onStateChanged: () -> Unit,
) {
    private data class ExpiryIdentity(
        val generation: Long,
        val transactionId: String,
        val hardDeadlineElapsedRealtimeMs: Long,
    )

    private val lifetime = StartedServiceLifetime()
    private val owners = PublicOwnerRegistry(::onOwnerDeath)
    private val expiryLock = Any()
    private var expiryIdentity: ExpiryIdentity? = null
    private var expiryHandle: FpsLimiterExpiryHandle? = null

    fun begin(startId: Int, graceMs: Long): StartedServiceLifetime.Snapshot =
        lifetime.beginPending(startId, elapsedRealtimeMs(), graceMs).also { onStateChanged() }

    fun markRecovery(startId: Int? = null) {
        lifetime.markRecovery(startId)
        onStateChanged()
    }

    fun expirePending(
        expectedStartId: Int,
        expectedDeadlineElapsedRealtimeMs: Long,
    ): Boolean = lifetime.expirePending(
        expectedStartId = expectedStartId,
        expectedDeadlineElapsedRealtimeMs = expectedDeadlineElapsedRealtimeMs,
        nowElapsedRealtimeMs = elapsedRealtimeMs(),
    ).also { if (it) onStateChanged() }

    fun capability(packageName: String): GameModeFpsCapability? = controller.capability(packageName)

    fun activeRecord(): FpsLimiterJournalRecord? = controller.activeRecord()

    fun apply(
        request: FpsLimiterRequest,
        token: ClientTokenHandle,
    ): FpsLimiterApplyResult {
        val admission = lifetime.admitApply(elapsedRealtimeMs())
        val admittedStartId = (admission as? StartedServiceLifetime.ApplyAdmission.Admitted)?.startId
            ?: return FpsLimiterApplyResult.Rejected(
                when (admission) {
                    StartedServiceLifetime.ApplyAdmission.NotStarted -> "FPS_SERVICE_NOT_STARTED"
                    StartedServiceLifetime.ApplyAdmission.Expired -> "FPS_START_EXPIRED"
                    StartedServiceLifetime.ApplyAdmission.Busy -> "FPS_SESSION_BUSY"
                    is StartedServiceLifetime.ApplyAdmission.Admitted -> error("unreachable")
                },
            )
        val registration = owners.registerApply(request.ownerUid, request.requestId, token)
        val registered = registration as? PublicOwnerRegistration.Registered
        if (registered == null || registered.reused) {
            lifetime.settleApplying(admittedStartId)
            onStateChanged()
            return FpsLimiterApplyResult.Rejected(
                when (registration) {
                    is PublicOwnerRegistration.Busy -> "FPS_OWNER_BUSY"
                    PublicOwnerRegistration.DeadToken -> "FPS_CLIENT_TOKEN_DEAD"
                    is PublicOwnerRegistration.Registered -> "FPS_OWNER_REUSED"
                },
            )
        }

        val result = controller.apply(request)
        return when (result) {
            is FpsLimiterApplyResult.Applied -> settleApplied(registered, admittedStartId, result)
            is FpsLimiterApplyResult.Rejected -> {
                owners.completeApply(registered.owner.tokenId, retainActiveOwner = false)
                lifetime.settleApplying(admittedStartId)
                onStateChanged()
                result
            }
            is FpsLimiterApplyResult.Uncertain -> {
                owners.completeApply(registered.owner.tokenId, retainActiveOwner = false)
                if (!result.restoreDebtRetained) {
                    lifetime.settleApplying(admittedStartId)
                    onStateChanged()
                    result
                } else {
                    lifetime.markRecovery(admittedStartId)
                    val restore = controller.restore()
                    if (restore is FpsLimiterRestoreResult.Restored ||
                        restore is FpsLimiterRestoreResult.NothingToRestore
                    ) {
                        lifetime.settleActiveOrRecovery()
                        onStateChanged()
                        result.copy(restoreDebtRetained = false)
                    } else {
                        onStateChanged()
                        result
                    }
                }
            }
        }
    }

    fun restore(
        ownerUid: Int,
        requestId: String,
        token: ClientTokenHandle,
        generation: Long,
        transactionId: String,
    ): FpsLimiterRestoreResult {
        val owner = owners.matchActive(ownerUid, requestId, token, generation, transactionId)
            ?: return FpsLimiterRestoreResult.Retained("FPS_STALE_OWNER")
        val result = controller.restore(generation, transactionId)
        if (result is FpsLimiterRestoreResult.Restored || result is FpsLimiterRestoreResult.NothingToRestore) {
            owners.clearIfToken(owner.tokenId)
            clearExpiry(generation, transactionId)
            lifetime.settleActiveOrRecovery()
            onStateChanged()
        }
        return result
    }

    fun recoverOrphan(startId: Int? = null): FpsLimiterRestoreResult {
        if (owners.snapshot() != null) return FpsLimiterRestoreResult.Retained("FPS_OWNER_PRESENT")
        clearExpiry()
        if (journal.load() is FpsLimiterJournalLoad.Empty) {
            lifetime.settleActiveOrRecovery()
            onStateChanged()
            return FpsLimiterRestoreResult.NothingToRestore
        }
        lifetime.markRecovery(startId)
        val result = controller.restore()
        if (result is FpsLimiterRestoreResult.Restored || result is FpsLimiterRestoreResult.NothingToRestore) {
            lifetime.settleActiveOrRecovery()
        }
        onStateChanged()
        return result
    }

    fun terminalTruth(): FpsLimiterTerminalTruth = FpsLimiterTerminalTruth(
        journalEmpty = journal.load() is FpsLimiterJournalLoad.Empty,
        ownerAbsent = owners.snapshot() == null,
        lifetimeIdle = lifetime.snapshot().isIdle,
        expiryIdle = synchronized(expiryLock) { expiryIdentity == null },
    )

    /**
     * The in-memory half of [terminalTruth] (no journal read), cheap enough for the main thread: no owner, no started
     * lifetime phase, no armed expiry.
     */
    fun inMemoryIdle(): Boolean =
        owners.snapshot() == null &&
            lifetime.snapshot().isIdle &&
            synchronized(expiryLock) { expiryIdentity == null }

    private fun settleApplied(
        registered: PublicOwnerRegistration.Registered,
        admittedStartId: Int,
        applied: FpsLimiterApplyResult.Applied,
    ): FpsLimiterApplyResult {
        val ownerBound = owners.bindAppliedSession(
            tokenId = registered.owner.tokenId,
            generation = applied.record.generation,
            transactionId = applied.record.transactionId,
        )
        val lifetimeBound = lifetime.markActive(admittedStartId)
        if (ownerBound && lifetimeBound && armExpiry(applied.record)) {
            owners.completeApply(registered.owner.tokenId, retainActiveOwner = true)
            onStateChanged()
            return applied
        }

        owners.completeApply(registered.owner.tokenId, retainActiveOwner = false)
        clearExpiry(applied.record.generation, applied.record.transactionId)
        if (!lifetimeBound) lifetime.markRecovery(admittedStartId)
        val restored = controller.restore(applied.record.generation, applied.record.transactionId)
        val debtRetained = restored !is FpsLimiterRestoreResult.Restored &&
            restored !is FpsLimiterRestoreResult.NothingToRestore
        if (!debtRetained) lifetime.settleActiveOrRecovery()
        onStateChanged()
        return FpsLimiterApplyResult.Uncertain("FPS_OWNER_COMMIT_FAILED", debtRetained)
    }

    private fun onOwnerDeath(owner: PublicOwnerSnapshot) {
        val generation = owner.generation ?: return
        val transactionId = owner.transactionId ?: return
        executeAsync restoreDeadOwner@{
            val active = controller.activeRecord()
            if (active?.generation != generation || active.transactionId != transactionId) {
                owners.clearIfToken(owner.tokenId)
                clearExpiry(generation, transactionId)
                onStateChanged()
                return@restoreDeadOwner
            }
            // Binder death ends public ownership immediately. A failed rollback retains the
            // durable recovery lifetime, but must not leave a dead owner blocking orphan replay.
            owners.clearIfToken(owner.tokenId)
            clearExpiry(generation, transactionId)
            val result = controller.restore(generation, transactionId)
            if (result is FpsLimiterRestoreResult.Restored || result is FpsLimiterRestoreResult.NothingToRestore) {
                lifetime.settleActiveOrRecovery()
            } else {
                lifetime.markRecovery()
            }
            onStateChanged()
        }
    }

    private fun armExpiry(record: FpsLimiterJournalRecord): Boolean {
        val identity = ExpiryIdentity(
            generation = record.generation,
            transactionId = record.transactionId,
            hardDeadlineElapsedRealtimeMs = record.hardDeadlineElapsedRealtimeMs,
        )
        clearExpiry()
        synchronized(expiryLock) {
            expiryIdentity = identity
        }
        val delayMs = (identity.hardDeadlineElapsedRealtimeMs - elapsedRealtimeMs()).coerceAtLeast(0L)
        val handle = runCatching {
            expiryScheduler.schedule(delayMs) {
                executeAsync { expire(identity) }
            }
        }.getOrNull()
        if (handle == null) {
            clearExpiry(identity.generation, identity.transactionId)
            return false
        }
        synchronized(expiryLock) {
            if (expiryIdentity == identity) {
                expiryHandle = handle
                return true
            }
        }
        handle.cancel()
        return false
    }

    private fun expire(expected: ExpiryIdentity) {
        if (!isCurrentExpiry(expected)) return
        val now = elapsedRealtimeMs()
        if (now < expected.hardDeadlineElapsedRealtimeMs) {
            // Scheduled executors may fire a little early. Re-arm the exact same immutable
            // identity rather than restoring before its service-owned deadline.
            val active = controller.activeRecord()
            if (active?.generation == expected.generation &&
                active.transactionId == expected.transactionId
            ) {
                armExpiry(active)
            } else {
                clearExpiry(expected.generation, expected.transactionId)
                onStateChanged()
            }
            return
        }
        val active = controller.activeRecord()
        if (active?.generation != expected.generation ||
            active.transactionId != expected.transactionId
        ) {
            clearExpiry(expected.generation, expected.transactionId)
            onStateChanged()
            return
        }
        owners.snapshot()?.takeIf {
            it.generation == expected.generation && it.transactionId == expected.transactionId
        }?.let { owners.clearIfToken(it.tokenId) }
        clearExpiry(expected.generation, expected.transactionId)
        val result = controller.restore(expected.generation, expected.transactionId)
        if (result is FpsLimiterRestoreResult.Restored || result is FpsLimiterRestoreResult.NothingToRestore) {
            lifetime.settleActiveOrRecovery()
        } else {
            lifetime.markRecovery()
        }
        onStateChanged()
    }

    private fun isCurrentExpiry(expected: ExpiryIdentity): Boolean =
        synchronized(expiryLock) { expiryIdentity == expected }

    private fun clearExpiry(
        expectedGeneration: Long? = null,
        expectedTransactionId: String? = null,
    ): Boolean {
        val handle = synchronized(expiryLock) {
            val current = expiryIdentity ?: return false
            if (expectedGeneration != null && current.generation != expectedGeneration) return false
            if (expectedTransactionId != null && current.transactionId != expectedTransactionId) return false
            expiryIdentity = null
            expiryHandle.also { expiryHandle = null }
        }
        handle?.cancel()
        return true
    }
}

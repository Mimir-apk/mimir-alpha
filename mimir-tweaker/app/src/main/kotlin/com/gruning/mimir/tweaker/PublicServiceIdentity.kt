package com.gruning.mimir.tweaker

import android.os.IBinder
import android.os.RemoteException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal const val NO_OWNER_TOKEN_ID = 0L
internal const val LAB_OWNER_TOKEN_ID = -1L
internal const val RECOVERY_OWNER_TOKEN_ID = -2L

/**
 * Product hard-lease authority. Callers never provide or extend this duration.
 */
internal fun interface OwnerHardLeasePolicy {
    fun hardDeadlineElapsedRealtimeMs(nowElapsedRealtimeMs: Long): Long?
}

internal object DisabledOwnerHardLeasePolicy : OwnerHardLeasePolicy {
    override fun hardDeadlineElapsedRealtimeMs(nowElapsedRealtimeMs: Long): Long? = null
}

/** Twelve-hour immutable ceiling over the separately renewable 30-second owner lease. */
internal object ProductOwnerHardLeasePolicy : OwnerHardLeasePolicy {
    const val HARD_LEASE_MS = 43_200_000L

    override fun hardDeadlineElapsedRealtimeMs(nowElapsedRealtimeMs: Long): Long? =
        if (nowElapsedRealtimeMs < 0L ||
            nowElapsedRealtimeMs > Long.MAX_VALUE - HARD_LEASE_MS
        ) {
            null
        } else {
            nowElapsedRealtimeMs + HARD_LEASE_MS
        }
}

internal data class PublicRequestFailure(
    val code: String,
    val message: String,
)

internal object PublicRequestContract {
    private val REQUEST_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

    fun validate(
        requestId: String?,
        deadlineElapsedRealtimeMs: Long,
        nowElapsedRealtimeMs: Long,
    ): PublicRequestFailure? {
        val exact = requestId.orEmpty()
        if (!REQUEST_ID.matches(exact)) {
            return PublicRequestFailure(
                code = "INVALID_REQUEST_ID",
                message = "requestId must be 1..128 stable ASCII identity characters.",
            )
        }
        if (deadlineElapsedRealtimeMs <= nowElapsedRealtimeMs) {
            return PublicRequestFailure(
                code = "REQUEST_EXPIRED",
                message = "The caller elapsed-realtime deadline has expired.",
            )
        }
        return null
    }

    fun hardLeaseDeadline(
        policy: OwnerHardLeasePolicy,
        nowElapsedRealtimeMs: Long,
    ): Long? = policy.hardDeadlineElapsedRealtimeMs(nowElapsedRealtimeMs)
        ?.takeIf { it > nowElapsedRealtimeMs }
}

/** Binder operations are wrapped so owner/death behavior has deterministic host tests. */
internal interface ClientTokenHandle {
    fun sameToken(other: ClientTokenHandle): Boolean
    fun isAlive(): Boolean
    fun linkToDeath(onDeath: () -> Unit): Boolean
    fun unlinkFromDeath()
}

internal class AndroidClientTokenHandle(
    private val binder: IBinder,
) : ClientTokenHandle {
    private var recipient: IBinder.DeathRecipient? = null

    override fun sameToken(other: ClientTokenHandle): Boolean =
        other is AndroidClientTokenHandle && binder == other.binder

    override fun isAlive(): Boolean = binder.isBinderAlive

    override fun linkToDeath(onDeath: () -> Unit): Boolean {
        val deathRecipient = IBinder.DeathRecipient(onDeath)
        recipient = deathRecipient
        return try {
            binder.linkToDeath(deathRecipient, 0)
            binder.isBinderAlive
        } catch (_: RemoteException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    override fun unlinkFromDeath() {
        val linked = recipient ?: return
        recipient = null
        runCatching { binder.unlinkToDeath(linked, 0) }
    }
}

internal data class PublicOwnerSnapshot(
    val tokenId: Long,
    val requestId: String,
    val ownerUid: Int,
    val inFlight: Boolean,
    val alive: Boolean,
    val cancelled: Boolean,
    val generation: Long?,
    val transactionId: String?,
)

internal sealed class PublicOwnerRegistration {
    data class Registered(
        val owner: PublicOwnerSnapshot,
        val cancelToken: CancelToken,
        val reused: Boolean,
    ) : PublicOwnerRegistration()

    data class Busy(val owner: PublicOwnerSnapshot) : PublicOwnerRegistration()
    data object DeadToken : PublicOwnerRegistration()
}

internal sealed class PublicOwnerMatch {
    data class InFlight(val owner: PublicOwnerSnapshot) : PublicOwnerMatch()
    data class Active(val owner: PublicOwnerSnapshot) : PublicOwnerMatch()
    data object Stale : PublicOwnerMatch()
}

/**
 * Exact Binder-token owner registry. It has no bind count and never treats service
 * binding/unbinding as mutation authority. Death cancels the matching in-flight request
 * immediately and reports the exact owner snapshot for generation-bound recovery.
 */
internal class PublicOwnerRegistry(
    private val onOwnerDeath: (PublicOwnerSnapshot) -> Unit,
) {
    private data class Entry(
        val tokenId: Long,
        val requestId: String,
        val ownerUid: Int,
        val token: ClientTokenHandle,
        var cancelToken: CancelToken,
        var inFlight: Boolean,
        var alive: Boolean,
        var linked: Boolean,
        var generation: Long?,
        var transactionId: String?,
    ) {
        fun snapshot(): PublicOwnerSnapshot = PublicOwnerSnapshot(
            tokenId = tokenId,
            requestId = requestId,
            ownerUid = ownerUid,
            inFlight = inFlight,
            alive = alive,
            cancelled = cancelToken.isCancelled(),
            generation = generation,
            transactionId = transactionId,
        )
    }

    private val lock = Any()
    private val nextTokenId = AtomicLong(1L)
    private var current: Entry? = null

    fun registerApply(
        ownerUid: Int,
        requestId: String,
        token: ClientTokenHandle,
    ): PublicOwnerRegistration {
        val candidate: Entry
        synchronized(lock) {
            val existing = current
            if (existing != null) {
                val exact = existing.ownerUid == ownerUid &&
                    existing.requestId == requestId &&
                    existing.token.sameToken(token)
                if (exact && existing.alive && !existing.inFlight &&
                    existing.generation != null && existing.transactionId != null
                ) {
                    return PublicOwnerRegistration.Registered(
                        owner = existing.snapshot(),
                        cancelToken = existing.cancelToken,
                        reused = true,
                    )
                }
                return PublicOwnerRegistration.Busy(existing.snapshot())
            }
            candidate = Entry(
                tokenId = nextTokenId.getAndIncrement(),
                requestId = requestId,
                ownerUid = ownerUid,
                token = token,
                cancelToken = CancelToken(requestId),
                inFlight = true,
                alive = true,
                linked = false,
                generation = null,
                transactionId = null,
            )
            current = candidate
        }

        val linked = token.linkToDeath { markDead(candidate.tokenId) }
        val alive = linked && token.isAlive()
        synchronized(lock) {
            val installed = current
            if (installed?.tokenId != candidate.tokenId || !alive || !installed.alive) {
                if (installed?.tokenId == candidate.tokenId) {
                    current = null
                }
                token.unlinkFromDeath()
                return PublicOwnerRegistration.DeadToken
            }
            installed.linked = true
            return PublicOwnerRegistration.Registered(
                owner = installed.snapshot(),
                cancelToken = installed.cancelToken,
                reused = false,
            )
        }
    }

    fun bindAppliedSession(
        tokenId: Long,
        generation: Long,
        transactionId: String,
    ): Boolean = synchronized(lock) {
        val entry = current ?: return@synchronized false
        if (entry.tokenId != tokenId || !entry.alive || entry.cancelToken.isCancelled()) {
            return@synchronized false
        }
        entry.generation = generation
        entry.transactionId = transactionId
        true
    }

    /**
     * Converts one exact active owner into the sole in-flight owner for a full
     * restore -> reapply transition. No second Binder death link or parallel writer exists.
     */
    fun beginActiveTransition(
        ownerUid: Int,
        requestId: String,
        token: ClientTokenHandle,
        generation: Long,
        transactionId: String,
    ): PublicOwnerRegistration.Registered? = synchronized(lock) {
        val entry = exactOwner(ownerUid, requestId, token) ?: return@synchronized null
        if (!entry.alive || entry.inFlight || entry.cancelToken.isCancelled() ||
            entry.generation != generation || entry.transactionId != transactionId
        ) return@synchronized null
        entry.cancelToken = CancelToken(requestId)
        entry.inFlight = true
        entry.generation = null
        entry.transactionId = null
        PublicOwnerRegistration.Registered(
            owner = entry.snapshot(),
            cancelToken = entry.cancelToken,
            reused = true,
        )
    }

    fun completeApply(tokenId: Long, retainActiveOwner: Boolean) {
        val unlink = synchronized(lock) {
            val entry = current ?: return@synchronized null
            if (entry.tokenId != tokenId) return@synchronized null
            entry.inFlight = false
            if (retainActiveOwner) {
                null
            } else {
                current = null
                entry.token.takeIf { entry.linked }
            }
        }
        unlink?.unlinkFromDeath()
    }

    fun cancelMatching(
        ownerUid: Int,
        requestId: String,
        token: ClientTokenHandle,
        generation: Long,
        transactionId: String,
    ): PublicOwnerMatch = synchronized(lock) {
        val entry = exactOwner(ownerUid, requestId, token) ?: return@synchronized PublicOwnerMatch.Stale
        if (!entry.alive) return@synchronized PublicOwnerMatch.Stale
        if (entry.inFlight) {
            if (generation != 0L || transactionId.isNotEmpty()) {
                return@synchronized PublicOwnerMatch.Stale
            }
            entry.cancelToken.cancel()
            return@synchronized PublicOwnerMatch.InFlight(entry.snapshot())
        }
        if (entry.generation != generation || entry.transactionId != transactionId) {
            return@synchronized PublicOwnerMatch.Stale
        }
        entry.cancelToken.cancel()
        PublicOwnerMatch.Active(entry.snapshot())
    }

    fun matchActive(
        ownerUid: Int,
        requestId: String,
        token: ClientTokenHandle,
        generation: Long,
        transactionId: String,
    ): PublicOwnerSnapshot? = synchronized(lock) {
        val entry = exactOwner(ownerUid, requestId, token) ?: return@synchronized null
        if (!entry.alive || entry.inFlight ||
            entry.generation != generation || entry.transactionId != transactionId
        ) {
            return@synchronized null
        }
        entry.snapshot()
    }

    fun isOwnerAlive(identity: ActiveSessionIdentity): Boolean = synchronized(lock) {
        val entry = current ?: return@synchronized false
        entry.alive &&
            entry.token.isAlive() &&
            entry.ownerUid == identity.ownerUid &&
            entry.tokenId == identity.ownerTokenId &&
            entry.requestId == identity.requestId &&
            entry.generation == identity.generation &&
            entry.transactionId == identity.transactionId
    }

    fun snapshot(): PublicOwnerSnapshot? = synchronized(lock) { current?.snapshot() }

    /**
     * P3 : the daemon's own tick-driven
     * fan-ownership renewal has no calling client of its own -- there is no `ownerUid`/`requestId`/
     * `token` presented at the door the way every other public entry point has one. It instead acts
     * FOR the live public owner of the active session, so it must satisfy the same fences
     * [matchActive] applies to an exact caller: alive, not mid-transition, and bound to a
     * generation/transactionId. Returns that owner's snapshot paired with its own token handle (the
     * renewal reuses it to drive [transitionFanControlModeOnWorker] exactly as the owner's own
     * request would have) -- or null when there is nobody eligible to renew on behalf of.
     *
     * **Cancellation fencing adds the `!entry.cancelToken.isCancelled()` check.** Before this lane, a renewal could be
     * handed an owner whose in-flight request had already been cancelled (e.g. by a fresh public
     * request racing the renewal tick) and would drive a hardware write on that owner's behalf
     * regardless -- [PublicOwnerSnapshot.cancelled] already existed and was already populated from
     * this exact field, this function simply never read it.
     */
    fun activeOwnerForRenewal(): Pair<PublicOwnerSnapshot, ClientTokenHandle>? = synchronized(lock) {
        val entry = current ?: return@synchronized null
        if (!entry.alive || entry.inFlight || !entry.token.isAlive() ||
            entry.cancelToken.isCancelled() ||
            entry.generation == null || entry.transactionId == null
        ) {
            return@synchronized null
        }
        entry.snapshot() to entry.token
    }

    fun clearIfToken(tokenId: Long) {
        val unlink = synchronized(lock) {
            val entry = current ?: return@synchronized null
            if (entry.tokenId != tokenId) return@synchronized null
            current = null
            entry.token.takeIf { entry.linked }
        }
        unlink?.unlinkFromDeath()
    }

    fun clearMatchingSession(generation: Long, transactionId: String) {
        val unlink = synchronized(lock) {
            val entry = current ?: return@synchronized null
            if (entry.generation != generation || entry.transactionId != transactionId) {
                return@synchronized null
            }
            current = null
            entry.token.takeIf { entry.linked }
        }
        unlink?.unlinkFromDeath()
    }

    private fun exactOwner(
        ownerUid: Int,
        requestId: String,
        token: ClientTokenHandle,
    ): Entry? {
        val entry = current ?: return null
        return entry.takeIf {
            it.ownerUid == ownerUid &&
                it.requestId == requestId &&
                it.token.sameToken(token)
        }
    }

    private fun markDead(tokenId: Long) {
        val snapshot = synchronized(lock) {
            val entry = current ?: return
            if (entry.tokenId != tokenId || !entry.alive) return
            entry.alive = false
            entry.cancelToken.cancel()
            entry.snapshot()
        }
        onOwnerDeath(snapshot)
    }
}

internal enum class PublicSessionState {
    STOCK,
    APPLYING,
    ACTIVE,
    RESTORING,
    RECOVERY_FAILED,
    CORRUPT,
    UNKNOWN,
}

internal data class PublicSessionTruth(
    val state: PublicSessionState,
    val mutationMayBeActive: Boolean,
    val requestId: String? = null,
    val generation: Long? = null,
    val transactionId: String? = null,
    val requestDeadlineElapsedRealtimeMs: Long? = null,
    val leaseDeadlineElapsedRealtimeMs: Long? = null,
    val hardLeaseDeadlineElapsedRealtimeMs: Long? = null,
    val leaseRenewable: Boolean = false,
    val scope: ActiveSessionScope? = null,
    val profileId: String? = null,
)

internal object PublicSessionTruthResolver {
    fun resolve(
        journal: JournalLoadResult,
        active: ActiveSessionIdentity?,
        recoveryOwned: Boolean,
        inFlightOwner: PublicOwnerSnapshot?,
        leaseRenewable: Boolean,
    ): PublicSessionTruth {
        if (journal is JournalLoadResult.Corrupt) {
            return PublicSessionTruth(
                state = PublicSessionState.CORRUPT,
                mutationMayBeActive = true,
            )
        }
        if (journal == JournalLoadResult.Empty) {
            if (inFlightOwner?.inFlight == true) {
                return PublicSessionTruth(
                    state = PublicSessionState.APPLYING,
                    mutationMayBeActive = false,
                    requestId = inFlightOwner.requestId,
                )
            }
            if (active == null && !recoveryOwned) {
                return PublicSessionTruth(
                    state = PublicSessionState.STOCK,
                    mutationMayBeActive = false,
                )
            }
            return truthFromActive(
                state = PublicSessionState.UNKNOWN,
                mutationMayBeActive = true,
                active = active,
                leaseRenewable = false,
            )
        }

        val record = (journal as JournalLoadResult.Loaded).record
        val identityMatches = active == null || (
            active.generation == record.generation &&
                active.transactionId == record.transactionId &&
                active.requestId == record.requestId
            )
        if (!identityMatches) {
            return truthFromRecord(
                state = PublicSessionState.UNKNOWN,
                mutationMayBeActive = true,
                record = record,
                active = active,
                leaseRenewable = false,
            )
        }
        val state = when (record.phase) {
            TransactionPhase.PREPARED,
            TransactionPhase.APPLYING,
            -> PublicSessionState.APPLYING
            TransactionPhase.APPLIED ->
                if (active != null && !recoveryOwned) PublicSessionState.ACTIVE
                else PublicSessionState.RESTORING
            TransactionPhase.RESTORING -> PublicSessionState.RESTORING
            TransactionPhase.RESTORE_FAILED -> PublicSessionState.RECOVERY_FAILED
            TransactionPhase.ARMING,
            TransactionPhase.GUARDIAN_READY,
            TransactionPhase.GUARDIAN_SETTLED,
            -> PublicSessionState.UNKNOWN
        }
        val mutationMayBeActive = when (record.phase) {
            TransactionPhase.PREPARED -> record.lastCompletedMutationBoundary > 0
            TransactionPhase.ARMING,
            TransactionPhase.GUARDIAN_READY,
            -> false
            TransactionPhase.GUARDIAN_SETTLED,
            TransactionPhase.APPLYING,
            TransactionPhase.APPLIED,
            TransactionPhase.RESTORING,
            TransactionPhase.RESTORE_FAILED,
            -> true
        }
        return truthFromRecord(
            state = state,
            mutationMayBeActive = mutationMayBeActive,
            record = record,
            active = active,
            leaseRenewable = state == PublicSessionState.ACTIVE && leaseRenewable,
        )
    }

    private fun truthFromRecord(
        state: PublicSessionState,
        mutationMayBeActive: Boolean,
        record: TransactionJournalRecord,
        active: ActiveSessionIdentity?,
        leaseRenewable: Boolean,
    ): PublicSessionTruth = PublicSessionTruth(
        state = state,
        mutationMayBeActive = mutationMayBeActive,
        requestId = record.requestId,
        generation = record.generation,
        transactionId = record.transactionId,
        requestDeadlineElapsedRealtimeMs = record.requestDeadline,
        leaseDeadlineElapsedRealtimeMs = active?.leaseDeadlineMs,
        hardLeaseDeadlineElapsedRealtimeMs = record.leaseDeadline,
        leaseRenewable = leaseRenewable,
        scope = active?.scope,
        profileId = record.profileId,
    )

    private fun truthFromActive(
        state: PublicSessionState,
        mutationMayBeActive: Boolean,
        active: ActiveSessionIdentity?,
        leaseRenewable: Boolean,
    ): PublicSessionTruth = PublicSessionTruth(
        state = state,
        mutationMayBeActive = mutationMayBeActive,
        requestId = active?.requestId,
        generation = active?.generation,
        transactionId = active?.transactionId,
        requestDeadlineElapsedRealtimeMs = active?.requestDeadlineElapsedRealtimeMs,
        leaseDeadlineElapsedRealtimeMs = active?.leaseDeadlineMs,
        hardLeaseDeadlineElapsedRealtimeMs = active?.hardLeaseDeadlineMs,
        leaseRenewable = leaseRenewable,
        scope = active?.scope,
    )
}

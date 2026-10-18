package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.core.metadata.online.IgdbMetadataSource
import com.gruning.mimir.rewrite.core.metadata.online.ScreenScraperMetadataSource
import com.gruning.mimir.rewrite.core.metadata.online.SignInOutcome
import com.gruning.mimir.rewrite.core.metadata.online.UserAuth
import com.gruning.mimir.rewrite.core.model.posture.MimirConnectedArtGate
import com.gruning.mimir.rewrite.core.model.posture.MimirSteamGridDbGate
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext

/**
 * App-layer glue for the opt-in cover fetch: connect / disconnect a ScreenScraper member account and hold
 * the one gated [ScreenScraperMetadataSource] the fetch pipeline uses. This is the ONLY place that turns a
 * user's typed sign-in into an open gate — always from an explicit user action, never automatically.
 *
 * [connect] validates the sign-in LIVE (`ssuserInfos`) before persisting anything; only a genuine, quota-
 * bearing account opens the gate. [source]'s per-request `userAuth` decrypts only at the explicit client
 * boundary, so plaintext credentials exist transiently for the authenticated request and are never cached.
 */
object ArtworkConnectController {

    @Volatile private var appContext: Context? = null
    @Volatile private var liveAccountMaxThreads: Int? = null
    private val operations = ArtworkConnectionOperationAuthority()

    /** The gated metadata source. Requests are guarded by [MimirConnectedArtGate]; creds come from the store. */
    val source: ScreenScraperMetadataSource by lazy {
        ScreenScraperMetadataSource(
            gateOpen = { MimirConnectedArtGate.isOpen },
            userAuth = { appContext?.let { ArtworkCredentialStore.load(it) } },
        )
    }

    /**
     * The gated IGDB last-resort name-search source (R6-P3-01). `gateOpen` is the OR of both provider
     * opt-ins -- "the player's metadata opt-in" as the product defines it -- and never widens anything:
     * the ROM path only reaches IGDB with [MimirConnectedArtGate] already open (ScreenScraper answered
     * first), and the PC path only with a resolved SteamGridDB key plus [MimirSteamGridDbGate] already
     * open; with both closed, `fetchOneArtwork`/`fetchOnePcArtwork` return `GateClosed` long before
     * either call site below is reached. [com.gruning.mimir.rewrite.ConnectedServicesStore.optedIn] was
     * rejected as the gate: it is set by ScreenScraper/RA connects, never by the SteamGridDB key, so a
     * PC-only player would never get the synopsis the PC ruling exists for.
     */
    val igdbSource: IgdbMetadataSource by lazy {
        IgdbMetadataSource(
            gateOpen = { MimirConnectedArtGate.isOpen || MimirSteamGridDbGate.isOpen },
        )
    }

    sealed interface ConnectResult {
        /** Signed in; the gate is now open. [dailyLimit]/[usedToday] are the live caps for honest copy. */
        data class Success(val account: String, val dailyLimit: Int, val usedToday: Int) : ConnectResult

        /** Sign-in did not open the gate. [reason] lets the UI be honest about WHICH layer failed. */
        data class Failed(val reason: Reason) : ConnectResult

        /** Why a connect failed — drives the connect overlay's copy. */
        enum class Reason {
            /** The member ssid/sspassword was wrong — the user can retry. */
            MEMBER_REJECTED,
            /** This build's ScreenScraper developer key is missing/unapproved — NOT the user's fault. */
            UNAVAILABLE,
            /** ScreenScraper's shared allowance is closed right now (peak / non-contributor). */
            BUSY,
            /** Couldn't reach ScreenScraper (offline/timeout). */
            UNREACHABLE,
            /** Provider accepted the account, but local durable activation could not be proven. */
            LOCAL_STORAGE,
            /** Candidate activation failed and exact local rollback could not be proven. */
            LOCAL_RECOVERY,
        }
    }

    /**
     * Validate [id]/[password] against ScreenScraper and, on success, persist (encrypted) + open the gate.
     * Suspends (a live network call); call off the main thread. Never logs the credentials. Credentials are
     * only saved + the gate only opens on a genuine [SignInOutcome.Success].
     */
    suspend fun connect(context: Context, id: String, password: String): ConnectResult {
        appContext = context.applicationContext
        val operation = operations.begin()
        return when (val outcome = source.signIn(UserAuth(id.trim(), password))) {
            is SignInOutcome.Success -> {
                currentCoroutineContext().ensureActive()
                val commitResult = operations.runIfCurrent(operation) {
                    val result = commitValidatedArtworkConnection(
                        snapshot = {
                            ConnectedArtStore.connectionSnapshot(context)
                        },
                        failClosed = {
                            ConnectedArtStore.failClosedForProcess()
                            ArtworkCredentialStore.failClosedForProcess()
                        },
                        saveCredential = { ArtworkCredentialStore.save(context, id.trim(), password) },
                        markConnectedServicesOptedIn = {
                            ConnectedServicesStore.markOptedIn(context)
                        },
                        activateProvider = { binding ->
                            ConnectedArtStore.markConnected(context, id.trim(), binding)
                        },
                        restorePrevious = { previous ->
                            ConnectedArtStore.failClosedForProcess()
                            ArtworkCredentialStore.failClosedForProcess()
                            ConnectedArtStore.restoreConnectionSnapshot(context, previous)
                        },
                    )
                    liveAccountMaxThreads = if (result === ArtworkConnectionCommitResult.Activated) {
                        outcome.quota.maxThreads.takeIf { it >= 1 }
                            // Remember it immediately: the very next thing a first run does is fetch, and
                            // it must not have to ask the network again for a number we are holding.
                            ?.also { ConnectedArtStore.rememberAccountMaxThreads(context, it) }
                    } else {
                        null
                    }
                    result
                }
                when (commitResult) {
                    ArtworkConnectionCommitResult.Activated -> {
                        ConnectResult.Success(
                            id.trim(),
                            outcome.quota.maxRequestsPerDay,
                            outcome.quota.requestsToday,
                        )
                    }
                    ArtworkConnectionCommitResult.FailedRestored,
                    null,
                    -> {
                        liveAccountMaxThreads = null
                        ConnectResult.Failed(ConnectResult.Reason.LOCAL_STORAGE)
                    }
                    ArtworkConnectionCommitResult.FailedUncertain -> {
                        liveAccountMaxThreads = null
                        ConnectResult.Failed(ConnectResult.Reason.LOCAL_RECOVERY)
                    }
                }
            }
            SignInOutcome.MemberRejected -> ConnectResult.Failed(ConnectResult.Reason.MEMBER_REJECTED)
            SignInOutcome.DeveloperRejected,
            SignInOutcome.NotConfigured -> ConnectResult.Failed(ConnectResult.Reason.UNAVAILABLE)
            SignInOutcome.Busy -> ConnectResult.Failed(ConnectResult.Reason.BUSY)
            SignInOutcome.Unreachable -> ConnectResult.Failed(ConnectResult.Reason.UNREACHABLE)
        }
    }

    /**
     * The account's `ssuser.maxthreads`: live if this process has validated one, otherwise the allowance
     * remembered from the last connect or refresh. Null only when no account has ever answered.
     *
     * [context] is optional so existing callers compile, but passing it is what lets a cold process start
     * at the account's real width instead of one lane — see [ConnectedArtKeyMaxThreads].
     */
    fun accountMaxThreads(context: Context? = null): Int? {
        liveAccountMaxThreads?.let { return it }
        val appContext = context ?: this.appContext ?: return null
        // Seeding from disk is a generation-sensitive write, so it goes through the same authority every
        // other write to this field uses: snapshot, do the slow read, then update only if still current.
        // Without the token a seed racing a disconnect could land AFTER `invalidate` cleared the field —
        // precisely the "an older generation can never activate after disconnect returns" invariant
        // [ArtworkConnectionOperationAuthority] exists to hold. If the generation moved, we do not know
        // this account's width and must say so rather than hand back a width for an account that left.
        val token = operations.snapshot()
        val stored = ConnectedArtStore.storedAccountMaxThreads(appContext) ?: return null
        return if (operations.updateIfCurrent(token) { liveAccountMaxThreads = stored }) stored else null
    }

    /**
     * Re-read this account's live quota/thread caps from `ssuserInfos`, and remember what it says.
     *
     * A failed or unauthenticated read now KEEPS the last known allowance rather than clearing it (reversing this function's original fail-closed-to-one-thread rule). The rule was
     * written to avoid honouring a stale allowance; what it actually did was throw away a good one. The
     * reported initial run connected at six threads and then called this a few seconds later — one failed
     * round trip dropped the whole library to a single lane, 58 games in 14m17s.
     *
     * The trade is bounded on purpose: the retained value is an allowance the provider itself issued, it
     * is clamped to [ArtworkFetchController.ArtworkBatchConcurrency] downstream, ScreenScraper rejects
     * threads beyond an account's real width on its own, and [ArtworkFetchController.WriteFailureBreaker]
     * stops a batch that is being refused. A disconnect clears it in both places.
     */
    suspend fun refreshAccountMaxThreads(context: Context): Int? {
        appContext = context.applicationContext
        val operation = operations.snapshot()
        val quota = ArtworkCredentialStore.load(context)?.let { source.validate(it) }
        // Already in hand from the request above, so keeping it costs nothing: it is the "before" figure of the batch's
        // `MimirEnrichCost` line (the connect card's requests-today count). A failed read clears it rather than leaving a
        // stale number from an earlier batch to be reported as this one's.
        lastRequestsToday = quota?.requestsToday
        quota?.requestsToday?.let { rememberRequestsToday(it) }
        val refreshed = quota
            ?.maxThreads
            ?.takeIf { it >= 1 }
            ?: return accountMaxThreads(context)
        return if (
            operations.updateIfCurrent(operation) {
                liveAccountMaxThreads = refreshed
                ConnectedArtStore.rememberAccountMaxThreads(context, refreshed)
            }
        ) {
            refreshed
        } else {
            accountMaxThreads(context)
        }
    }

    /**
     * The account's requests-today count as [refreshAccountMaxThreads] last read it, or null when it could not be read. Only
     * the batch cost line reads this; nothing gates on it.
     */
    @Volatile
    var lastRequestsToday: Int? = null
        private set

    /**
     * A requests-today count and the wall-clock time it was read. Only ever a count the provider itself reported.
     */
    data class RequestsTodayReading(val count: Int, val readAtMillis: Long)

    /**
     * The last requests-today count any read of the account reported ([refreshAccountMaxThreads], [readRequestsToday]) and
     * WHEN, or null if none has been read this run. It is only as fresh as that read: a fresh value is an authenticated
     * `ssuserInfos` request, which a settings row must not make just by being drawn. A caller that shows it shows its age
     * (or "unknown" when this is null), and a failed read leaves the previous reading standing, with its own time.
     */
    @Volatile
    var lastRequestsTodayReading: RequestsTodayReading? = null
        private set

    private fun rememberRequestsToday(count: Int) {
        lastRequestsTodayReading = RequestsTodayReading(count, System.currentTimeMillis())
    }

    /**
     * One more `ssuserInfos` read of the account's requests-today count, for the "after" figure of a batch's
     * `MimirEnrichCost` line. A single small request; null when there is no account or it could not be read. Never logs.
     */
    suspend fun readRequestsToday(context: Context): Int? {
        appContext = context.applicationContext
        val count = ArtworkCredentialStore.load(context)?.let { source.validate(it) }?.requestsToday
        count?.let { rememberRequestsToday(it) }
        return count
    }

    /** Disconnect: wipe the stored sign-in and shut the gate. Downloaded covers (local siblings) stay. */
    fun disconnect(context: Context): Boolean {
        appContext = context.applicationContext
        return operations.invalidate {
            liveAccountMaxThreads = null
            disconnectArtworkConnection(
                failClosed = {
                    ConnectedArtStore.failClosedForProcess()
                    ArtworkCredentialStore.failClosedForProcess()
                },
                deactivateProvider = { ConnectedArtStore.disconnect(context) },
                clearCredential = { ArtworkCredentialStore.clear(context) },
            )
        }
    }

    /**
     * Cancel a still-visible connect operation. This invalidates its generation and durably removes any
     * revision it may have committed before the UI cancellation reached the controller.
     */
    fun cancelPendingConnect(context: Context): Boolean {
        appContext = context.applicationContext
        return operations.invalidate {
            liveAccountMaxThreads = null
            disconnectArtworkConnection(
                failClosed = {
                    ConnectedArtStore.failClosedForProcess()
                    ArtworkCredentialStore.failClosedForProcess()
                },
                deactivateProvider = { ConnectedArtStore.disconnect(context) },
                clearCredential = { ArtworkCredentialStore.clear(context) },
            )
        }
    }

    /** Remember the app context so [source]'s credential provider can read the encrypted store. */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }
}

/**
 * Close-first, revision-bound activation. A new credential revision cannot reopen the provider until
 * provider-neutral consent is durable and ConnectedArtStore commits that exact revision last.
 */
internal fun commitValidatedArtworkConnection(
    snapshot: () -> ArtworkConnectionDurableSnapshot?,
    failClosed: () -> Unit,
    saveCredential: () -> String?,
    markConnectedServicesOptedIn: () -> Boolean,
    activateProvider: (String) -> Boolean,
    restorePrevious: (ArtworkConnectionDurableSnapshot) -> Boolean,
): ArtworkConnectionCommitResult {
    val previous = runCatching(snapshot).getOrNull()
    if (previous == null) {
        runCatching(failClosed)
        return ArtworkConnectionCommitResult.FailedUncertain
    }
    if (runCatching(failClosed).isFailure) {
        return rollbackArtworkConnection(previous, failClosed, restorePrevious)
    }
    val binding = runCatching(saveCredential).getOrNull()?.takeIf(String::isNotBlank)
        ?: return rollbackArtworkConnection(previous, failClosed, restorePrevious)
    val consentDurable = runCatching(markConnectedServicesOptedIn).getOrDefault(false)
    if (!consentDurable) {
        return rollbackArtworkConnection(previous, failClosed, restorePrevious)
    }
    val activated = runCatching { activateProvider(binding) }.getOrDefault(false)
    return if (activated) {
        ArtworkConnectionCommitResult.Activated
    } else {
        rollbackArtworkConnection(previous, failClosed, restorePrevious)
    }
}

internal fun disconnectArtworkConnection(
    failClosed: () -> Unit,
    deactivateProvider: () -> Boolean,
    clearCredential: () -> Boolean,
): Boolean {
    runCatching(failClosed)
    val providerDeactivated = runCatching(deactivateProvider).getOrDefault(false)
    val credentialPurged = runCatching(clearCredential).getOrDefault(false)
    return providerDeactivated && credentialPurged
}

internal class ArtworkConnectionDurableSnapshot(
    val credential: ArtworkCredentialDurableSnapshot,
    val provider: ConnectedArtDurableSnapshot,
) {
    override fun toString(): String =
        "ArtworkConnectionDurableSnapshot(credential=***, provider=***)"
}

internal enum class ArtworkConnectionCommitResult {
    Activated,
    FailedRestored,
    FailedUncertain,
}

private fun rollbackArtworkConnection(
    previous: ArtworkConnectionDurableSnapshot,
    failClosed: () -> Unit,
    restorePrevious: (ArtworkConnectionDurableSnapshot) -> Boolean,
): ArtworkConnectionCommitResult {
    runCatching(failClosed)
    val restored = runCatching { restorePrevious(previous) }.getOrDefault(false)
    if (!restored) runCatching(failClosed)
    return if (restored) {
        ArtworkConnectionCommitResult.FailedRestored
    } else {
        ArtworkConnectionCommitResult.FailedUncertain
    }
}

/**
 * Serializes generation-sensitive provider activation and cancellation. Invalidation waits for a current
 * activation to finish, then performs its durable close while holding the same lock; an older generation
 * can therefore never activate after cancel/disconnect returns.
 */
internal class ArtworkConnectionOperationAuthority {
    private val lock = Any()
    private var generation = 0L

    fun begin(): Long = synchronized(lock) {
        generation += 1L
        generation
    }

    fun snapshot(): Long = synchronized(lock) { generation }

    fun <T : Any> runIfCurrent(token: Long, operation: () -> T): T? =
        synchronized(lock) {
            if (token != generation) null else operation()
        }

    fun updateIfCurrent(token: Long, update: () -> Unit): Boolean =
        synchronized(lock) {
            if (token != generation) return@synchronized false
            update()
            true
        }

    fun invalidate(operation: () -> Boolean): Boolean =
        synchronized(lock) {
            generation += 1L
            operation()
        }
}

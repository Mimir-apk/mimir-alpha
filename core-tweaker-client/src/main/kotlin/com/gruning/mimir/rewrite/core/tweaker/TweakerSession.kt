package com.gruning.mimir.rewrite.core.tweaker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.gruning.mimir.tweaker.IMimirTweaker
import com.gruning.mimir.tweaker.TweakerServiceActions
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class TweakerSession(
    private val appContext: Context,
    private val bindService: (Intent, ServiceConnection, Int) -> Boolean = { intent, connection, flags ->
        appContext.bindService(intent, connection, flags)
    },
    private val unbindService: (ServiceConnection) -> Unit = { connection ->
        appContext.unbindService(connection)
    },
    private val startForegroundService: (Intent) -> ComponentName? = { intent ->
        appContext.startForegroundService(intent)
    },
    private val asInterface: (IBinder?) -> IMimirTweaker? = IMimirTweaker.Stub::asInterface,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val rpcDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val connectionLock = Any()
    @Volatile private var service: IMimirTweaker? = null
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var bound = false
    /** True only after bindService returned true for the current connection attempt. */
    @Volatile private var serviceBindActive = false
    /** Process/session-stable owner token. Query calls never register mutation ownership. */
    private val ownerToken: IBinder = Binder()
    private val requestNonce = UUID.randomUUID().toString()
    private val requestCounter = AtomicLong(0L)

    /**
     * At most one telemetry transaction in flight. The zero-capacity queue means a call arriving
     * while the daemon has the single thread hung is REJECTED immediately (bounded failure)
     * instead of queueing behind a transaction that may never return.
     */
    private val telemetryCallExecutor = ThreadPoolExecutor(
        0,
        1,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
    ) { runnable -> Thread(runnable, "mimir-tweaker-telemetry").apply { isDaemon = true } }

    /**
     * Same shape as [telemetryCallExecutor], for [getCapabilities] / [getAdvancedCapabilitiesV6] -- kept as a
     * second instance rather than sharing the telemetry one so a hung capability read cannot starve the
     * independent telemetry poll loop, or vice versa. These two are typically called back-to-back from the
     * same refresh cycle (`TweakerBridge.refreshProjectionFromBoundSession`), so a hang on the first
     * correctly makes the second fail fast instead of queueing behind it.
     *
     * [getApiVersion] is not on this one: see [apiVersionCallExecutor].
     */
    private val capabilityCallExecutor = ThreadPoolExecutor(
        0,
        1,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
    ) { runnable -> Thread(runnable, "mimir-tweaker-capability").apply { isDaemon = true } }

    /**
     * Same shape as [telemetryCallExecutor] and [capabilityCallExecutor], for [getApiVersion] alone. It was briefly moved onto the shared [deadlineCallExecutor]; that let a
     * different stuck read (e.g. a frame-limit query, or a V4 session query) fill the deadline pool's
     * abandoned-call budget and start refusing the version read again -- the same class of defect this dedicated executor prevents, just with a different sibling call in the stuck seat. A dedicated one-thread
     * pool means [getApiVersion] can only ever be blocked by ANOTHER [getApiVersion] call, never by
     * [getAdvancedCapabilitiesV6], a frame-limit read, or anything on [deadlineCallExecutor]; and it never
     * itself consumes one of that pool's four abandoned slots, which every other deadline-carrying call
     * still shares once a second from `TweakerBridge.refreshProjectionFromBoundSession`.
     */
    private val apiVersionCallExecutor = ThreadPoolExecutor(
        0,
        1,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
    ) { runnable -> Thread(runnable, "mimir-tweaker-apiversion").apply { isDaemon = true } }

    /**
     * Where every call that hands the daemon a deadline waits for its reply, and stops waiting AT that deadline.
     *
     * **Handing the daemon a deadline never bounded the wait (round 5e).** [querySessionV4],
     * [selectSystemProfileV5], the V4 apply/cancel/renew/restore family, [setFanControlV6] and the two
     * frame-limit calls all send a caller deadline, and each used to block on its Binder transaction until
     * the daemon answered. The daemon checks that deadline when it admits a request and between the commands
     * of a transaction, but its binder thread then waits on a single transaction worker with no bound
     * (`MimirTweakerService.onTransactionWorker` in `mimir-tweaker/`), so a reply can leave well after the
     * deadline it echoes. Every parser here already refuses a reply observed at or after its deadline, so the
     * extra wait bought nothing, and the waiting caller usually held the process's one Tweaker session
     * reservation. Round 5c, O16: *"A daemon that ignores the deadline it is given holds the reservation past
     * 215 s, blocking every other privileged row."*
     *
     * **A Binder transaction cannot be cancelled from the client**, so a call that outlives its deadline keeps
     * its thread until the daemon answers or dies. Its reply is then dropped unread and logged
     * `outcome=LATE_REPLY_IGNORED`. Unlike [capabilityCallExecutor] this pool is not one thread wide: these
     * calls legitimately overlap (a settlement read joining the process-start owner, a frame-limit change
     * beside a lease renewal), and refusing a healthy call because another is in flight would be a new
     * failure. What is capped is the number of calls already abandoned at their deadlines, see
     * [MAX_ABANDONED_DEADLINE_CALLS].
     */
    private val deadlineCallExecutor = ThreadPoolExecutor(
        0,
        Int.MAX_VALUE,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
    ) { runnable -> Thread(runnable, "mimir-tweaker-deadline").apply { isDaemon = true } }

    /** Calls whose caller left at the deadline while the daemon still holds the transaction. */
    private val abandonedDeadlineCalls = AtomicInteger(0)

    /**
     * How many capability / API-version / session queries ended WITHOUT an answer (deadline passed,
     * calls stuck past their deadline, or a capability call already in flight). Monotonic.
     *
     * Not an answer is not a "no": the bridge compares this counter across a refresh to tell a daemon
     * that said nothing (unknown -- keep the last known truth) from one that said no (stock-only, gone).
     * The Nova's 09-30 sessions wedged Binder with restores, `getAdvancedCapabilitiesV6` and
     * `querySessionV4` timed out, and the row read the silence as "went back to Stock, Unavailable".
     */
    private val unansweredQueries = AtomicInteger(0)

    fun unansweredQueryCount(): Int = unansweredQueries.get()

    private fun noteUnansweredQuery() {
        unansweredQueries.incrementAndGet()
    }

    val isBound: Boolean
        get() = synchronized(connectionLock) { bound && service != null }

    /**
     * Dormant API-V4 active-lifetime seam. Starting the service never binds or applies a
     * profile; the later exact V4 apply must independently consume the bounded admission.
     */
    fun beginActiveSessionV4(): Boolean {
        val intent = Intent()
            .setComponent(ComponentName(TWEAKER_PACKAGE, TWEAKER_SERVICE))
            .setAction(TweakerServiceActions.ACTION_BEGIN_ACTIVE_SESSION_V4)
        return try {
            startForegroundService(intent) != null
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    fun beginFpsLimitSessionV7(): Boolean {
        val intent = Intent()
            .setComponent(ComponentName(TWEAKER_PACKAGE, TWEAKER_SERVICE))
            .setAction(TweakerServiceActions.ACTION_BEGIN_FPS_LIMIT_SESSION_V7)
        return try {
            startForegroundService(intent) != null
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    /**
     * Explicit owner-validation entry. The service accepts it only in its diagnostics-compiled LAB
     * posture and applies its fixed six-minute Guardian-backed hold; SAFE and Production ignore it.
     */
    fun beginLabProfileHold(profileId: String, packageName: String): Boolean {
        if (profileId !in TWEAKER_PRODUCT_QUALIFIABLE_PROFILE_IDS) return false
        if (!PACKAGE_NAME.matches(packageName)) return false
        val intent = Intent()
            .setComponent(ComponentName(TWEAKER_PACKAGE, TWEAKER_SERVICE))
            .setAction(TweakerServiceActions.ACTION_BEGIN_LAB_PROFILE_HOLD)
            .putExtra(TweakerServiceActions.EXTRA_LAB_APPLY_HOLD, true)
            .putExtra(TweakerServiceActions.EXTRA_PROFILE_ID, profileId)
            .putExtra(TweakerServiceActions.EXTRA_WORKLOAD_PACKAGE, packageName)
            .putExtra(TweakerServiceActions.EXTRA_LAB_OBSERVE_SECONDS, 0)
            .putExtra(TweakerServiceActions.EXTRA_LAB_OWNER_VALIDATION, true)
        return try {
            startForegroundService(intent) != null
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    suspend fun bind(timeoutMs: Long = 2_500L): Boolean {
        if (isBound) return true
        abandonConnection()

        val deferred = CompletableDeferred<Boolean>()
        val created = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val connected = runCatching { asInterface(binder) }.getOrNull()
                if (connected == null) {
                    abandonConnection(this)
                    if (!deferred.isCompleted) deferred.complete(false)
                    return
                }
                val accepted = synchronized(connectionLock) {
                    if (connection !== this) {
                        false
                    } else {
                        service = connected
                        bound = true
                        true
                    }
                }
                if (accepted && !deferred.isCompleted) deferred.complete(true)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                val accepted = synchronized(connectionLock) {
                    if (connection !== this) {
                        false
                    } else {
                        // Android retains the binding and may reconnect this callback. Clear the live
                        // transport; the request scope owns the eventual unbind.
                        service = null
                        bound = false
                        true
                    }
                }
                if (accepted && !deferred.isCompleted) deferred.complete(false)
            }

            override fun onBindingDied(name: ComponentName?) {
                if (abandonConnection(this) && !deferred.isCompleted) deferred.complete(false)
            }

            override fun onNullBinding(name: ComponentName?) {
                if (abandonConnection(this) && !deferred.isCompleted) deferred.complete(false)
            }
        }
        synchronized(connectionLock) { connection = created }
        val intent = Intent().setComponent(
            ComponentName(TWEAKER_PACKAGE, TWEAKER_SERVICE),
        )
        val started = try {
            bindService(intent, created, Context.BIND_AUTO_CREATE)
        } catch (_: SecurityException) {
            abandonConnection(created)
            return false
        } catch (_: RuntimeException) {
            abandonConnection(created)
            return false
        }
        if (!started) {
            abandonConnection(created)
            return false
        }
        // A synchronous null/dead callback can arrive before bindService returns true.
        val retained = synchronized(connectionLock) {
            if (connection !== created) {
                false
            } else {
                serviceBindActive = true
                true
            }
        }
        if (!retained) {
            runCatching { unbindService(created) }
            return false
        }
        return try {
            val connected: Boolean? = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (connected != true || !isBound) {
                abandonConnection(created)
                return false
            }
            true
        } catch (cancelled: CancellationException) {
            abandonConnection(created)
            throw cancelled
        }
    }

    suspend fun bindV4Owner(timeoutMs: Long): Boolean = withContext(rpcDispatcher) {
        bind(timeoutMs)
    }

    fun unbind() {
        val active = synchronized(connectionLock) { connection } ?: return
        abandonConnection(active)
    }

    suspend fun unbindV4Owner() = withContext(rpcDispatcher) { unbind() }

    /**
     * Bounded like [getTelemetry]: previously a plain synchronous AIDL call with no deadline, so a
     * wedge here silently froze the whole bundled Tweaker rail at last-known values with nothing in
     * logcat to say why. [boundedCapabilityCall] makes the four ways this can
     * end -- never returned, threw, returned null, returned a bundle that failed to parse --
     * distinguishable in logcat instead of collapsing to the same silent `null`.
     */
    fun getCapabilities(deadlineMs: Long = CAPABILITY_CALL_DEADLINE_MS): TweakerCapabilities? {
        val api = service ?: return null
        return when (val outcome = boundedCapabilityCall(deadlineMs) { api.capabilities }) {
            is TweakerBoundedCallOutcome.Completed -> {
                val bundle = outcome.value
                if (bundle == null) {
                    Log.w(TAG, "getCapabilities outcome=RETURNED_NULL")
                    null
                } else {
                    runCatching { parseTweakerCapabilitiesBundle(bundle) }
                        .onFailure { Log.w(TAG, "getCapabilities outcome=PARSE_FAILED", it) }
                        .getOrNull()
                }
            }
            is TweakerBoundedCallOutcome.Threw -> {
                Log.w(TAG, "getCapabilities outcome=THREW", outcome.error)
                null
            }
            TweakerBoundedCallOutcome.TimedOut -> {
                noteUnansweredQuery()
                Log.w(TAG, "getCapabilities outcome=TIMED_OUT deadlineMs=$deadlineMs")
                null
            }
            TweakerBoundedCallOutcome.Rejected -> {
                noteUnansweredQuery()
                Log.w(TAG, "getCapabilities outcome=REJECTED reason=capability_call_in_flight")
                null
            }
        }
    }

    /**
     * Bounded like [bind] (2500 ms default) and like every call that hands the daemon a deadline
     * ([deadlineBoundedCall]): a slow daemon costs at most [deadlineMs] and a hung one costs it once —
     * the single transport thread stays blocked on the hung transaction and later calls fail fast
     * instead of queueing, until the binder dies or the transaction returns. Never blocks the caller
     * indefinitely.
     */
    fun getTelemetry(deadlineMs: Long = TELEMETRY_CALL_DEADLINE_MS): TweakerTelemetry? {
        val api = service ?: return null
        val bundle = runCatching {
            telemetryCallExecutor.submit<Bundle> { api.telemetry }
                .get(deadlineMs, TimeUnit.MILLISECONDS)
        }.getOrNull() ?: return null
        return runCatching { parseTweakerTelemetryBundle(bundle) }.getOrNull()
    }

    /**
     * Bounded like [getTelemetry] and [getCapabilities] (register finding O-2: this was a plain
     * synchronous AIDL call with no deadline, unlike every sibling on this transport). The prior
     * `onFailure { Log.w(...) }` diagnostic could only ever observe a call that threw
     * -- a hang produced no log line at all, which is exactly the negative result that build
     * produced on device. [boundedCapabilityCall] now also bounds and distinguishes a hang, a clean
     * null return, and a bundle that fails to parse.
     */
    fun getAdvancedCapabilitiesV6(
        negotiatedApiVersion: Int,
        deadlineMs: Long = CAPABILITY_CALL_DEADLINE_MS,
    ): TweakerAdvancedCapabilities? {
        if (negotiatedApiVersion < TWEAKER_V6_ADVANCED_CONTROLS_MIN_API_VERSION) return null
        val api = service ?: return null
        return when (
            val outcome = boundedCapabilityCall(deadlineMs) { api.advancedCapabilitiesV6 }
        ) {
            is TweakerBoundedCallOutcome.Completed -> {
                val bundle = outcome.value
                if (bundle == null) {
                    Log.w(TAG, "getAdvancedCapabilitiesV6 outcome=RETURNED_NULL")
                    null
                } else {
                    runCatching {
                        parseTweakerAdvancedCapabilitiesBundle(bundle, negotiatedApiVersion)
                    }.onFailure {
                        Log.w(TAG, "getAdvancedCapabilitiesV6 outcome=PARSE_FAILED", it)
                    }.getOrNull()
                }
            }
            is TweakerBoundedCallOutcome.Threw -> {
                Log.w(TAG, "getAdvancedCapabilitiesV6 outcome=THREW", outcome.error)
                null
            }
            TweakerBoundedCallOutcome.TimedOut -> {
                noteUnansweredQuery()
                Log.w(TAG, "getAdvancedCapabilitiesV6 outcome=TIMED_OUT deadlineMs=$deadlineMs")
                null
            }
            TweakerBoundedCallOutcome.Rejected -> {
                noteUnansweredQuery()
                Log.w(TAG, "getAdvancedCapabilitiesV6 outcome=REJECTED reason=capability_call_in_flight")
                null
            }
        }
    }

    /**
     * Bounded like [queryDiscoveredCeilingV10] (register O-2, round 6 R6-5E-02, policy 2026-09-15
     * "Daemon change, next release"): this call carries no daemon-side deadline, but the daemon queues it
     * behind its one transaction worker -- the same queue an owner-death restore can hold for seconds (see
     * [getApiVersion]). A client-side bound on a READ cannot misreport a write, so it is safe to cap the
     * wait with [deadlineMs] ahead of the V11 daemon contract this ticket otherwise waits on. The write
     * family beside this one ([setFanControlV6] and friends) stays unbounded; see that doc comment.
     */
    fun getFpsLimiterCapabilityV7(
        negotiatedApiVersion: Int,
        packageName: String,
        deadlineMs: Long = CAPABILITY_CALL_DEADLINE_MS,
    ): TweakerFpsCapability? {
        if (negotiatedApiVersion < TWEAKER_V7_FPS_LIMITER_MIN_API_VERSION) return null
        val safePackage = packageName.trim().takeIf(PACKAGE_NAME::matches) ?: return null
        val api = service ?: return null
        val outcome = deadlineBoundedCall(
            "getFpsLimiterCapabilityV7",
            elapsedRealtimeMs().saturatingAdd(deadlineMs),
        ) { api.getFpsLimiterCapabilityV7(safePackage) }
        if (outcome.deadlineEnding("Frame-limit capability") != null) return null
        if (outcome is TweakerBoundedCallOutcome.Threw) {
            Log.w(TAG, "getFpsLimiterCapabilityV7 outcome=THREW", outcome.error)
        }
        val bundle = outcome.replyOrNull() ?: return null
        return runCatching {
            parseTweakerFpsCapabilityBundle(
                bundle = bundle,
                expectedApiVersion = negotiatedApiVersion,
                expectedPackageName = safePackage,
            )
        }.getOrNull()
    }

    /** Bounded like [getFpsLimiterCapabilityV7], for the same reason (register O-2, round 6 R6-5E-02). */
    fun queryFpsLimiterV7(
        negotiatedApiVersion: Int,
        packageName: String,
        deadlineMs: Long = CAPABILITY_CALL_DEADLINE_MS,
    ): TweakerFpsQueryAssessment {
        if (negotiatedApiVersion < TWEAKER_V7_FPS_LIMITER_MIN_API_VERSION) {
            return TweakerFpsQueryAssessment.Uncertain("Tweaker service does not support API-v7 frame limits")
        }
        val safePackage = packageName.trim().takeIf(PACKAGE_NAME::matches)
            ?: return TweakerFpsQueryAssessment.Uncertain("A concrete Android game package is required")
        val api = service
            ?: return TweakerFpsQueryAssessment.Uncertain("Tweaker service is not bound")
        val outcome = deadlineBoundedCall(
            "queryFpsLimiterV7",
            elapsedRealtimeMs().saturatingAdd(deadlineMs),
        ) { api.queryFpsLimiterV7(safePackage) }
        outcome.deadlineEnding("Frame-limit query")?.let { return TweakerFpsQueryAssessment.Uncertain(it) }
        if (outcome is TweakerBoundedCallOutcome.Threw) {
            Log.w(TAG, "queryFpsLimiterV7 outcome=THREW", outcome.error)
        }
        val bundle = outcome.replyOrNull()
            ?: return TweakerFpsQueryAssessment.Uncertain("Frame-limit query failed")
        return parseTweakerFpsQueryBundle(bundle, negotiatedApiVersion, safePackage)
    }

    fun createFpsApplyAttemptV7(
        packageName: String,
        targetFps: Int,
        timeoutMs: Long,
    ): TweakerFpsApplyAttempt? {
        val safePackage = packageName.trim()
        if (!PACKAGE_NAME.matches(safePackage) || targetFps !in FPS_LIMIT_VALUES || timeoutMs <= 0L) return null
        val startedAt = elapsedRealtimeMs()
        val deadline = startedAt.saturatingAdd(timeoutMs)
        if (deadline <= startedAt) return null
        return TweakerFpsApplyAttempt(
            requestId = "mimir.fps.$requestNonce.${requestCounter.incrementAndGet()}",
            requestDeadlineElapsedRealtimeMs = deadline,
            packageName = safePackage,
            targetFps = targetFps,
        )
    }

    suspend fun setFpsLimitV7(
        negotiatedApiVersion: Int,
        attempt: TweakerFpsApplyAttempt,
    ): TweakerFpsApplyAssessment = withContext(rpcDispatcher) {
        if (negotiatedApiVersion < TWEAKER_V7_FPS_LIMITER_MIN_API_VERSION) {
            return@withContext TweakerFpsApplyAssessment.Uncertain(
                "Tweaker service does not support API-v7 frame limits",
                restoreDebtRetained = false,
            )
        }
        val api = service ?: return@withContext TweakerFpsApplyAssessment.Uncertain(
            "Tweaker service is not bound",
            restoreDebtRetained = false,
        )
        if (attempt.requestDeadlineElapsedRealtimeMs <= elapsedRealtimeMs()) {
            return@withContext TweakerFpsApplyAssessment.Uncertain(
                "Frame-limit deadline expired before call",
                restoreDebtRetained = false,
            )
        }
        val outcome = deadlineBoundedCall("setFpsLimitV7", attempt.requestDeadlineElapsedRealtimeMs) {
            api.setFpsLimitV7(
                attempt.requestId,
                attempt.requestDeadlineElapsedRealtimeMs,
                ownerToken,
                attempt.packageName,
                attempt.targetFps,
            )
        }
        outcome.deadlineEnding("Frame-limit apply")?.let { reason ->
            return@withContext TweakerFpsApplyAssessment.Uncertain(
                reason,
                // Timed out: the daemon may still apply the cap after the client stopped waiting, so the debt
                // stays owed. Refused: it was never sent, so nothing can have been applied.
                restoreDebtRetained = outcome == TweakerBoundedCallOutcome.TimedOut,
            )
        }
        val bundle = outcome.replyOrNull() ?: return@withContext TweakerFpsApplyAssessment.Uncertain(
            "Frame-limit apply call failed",
            restoreDebtRetained = true,
        )
        parseTweakerFpsApplyBundle(
            bundle,
            negotiatedApiVersion,
            attempt,
            elapsedRealtimeMs(),
        )
    }

    suspend fun restoreFpsLimitV7(
        negotiatedApiVersion: Int,
        owner: TweakerFpsOwner,
        timeoutMs: Long,
    ): TweakerFpsRestoreAssessment = withContext(rpcDispatcher) {
        if (negotiatedApiVersion < TWEAKER_V7_FPS_LIMITER_MIN_API_VERSION) {
            return@withContext TweakerFpsRestoreAssessment.Uncertain(
                "Tweaker service does not support API-v7 frame limits",
            )
        }
        val deadline = operationDeadline(timeoutMs)
            ?: return@withContext TweakerFpsRestoreAssessment.Uncertain("Frame-limit restore deadline is invalid")
        val api = service
            ?: return@withContext TweakerFpsRestoreAssessment.Uncertain("Tweaker service is not bound")
        val outcome = deadlineBoundedCall("restoreFpsLimitV7", deadline) {
            api.restoreFpsLimitV7(
                owner.requestId,
                deadline,
                ownerToken,
                owner.generation,
                owner.transactionId,
            )
        }
        outcome.deadlineEnding("Frame-limit restore")?.let {
            return@withContext TweakerFpsRestoreAssessment.Uncertain(it)
        }
        val bundle = outcome.replyOrNull() ?: return@withContext TweakerFpsRestoreAssessment.Uncertain(
            "Frame-limit restore call failed",
        )
        parseTweakerFpsRestoreBundle(
            bundle = bundle,
            expectedApiVersion = negotiatedApiVersion,
            owner = owner,
            expectedDeadlineElapsedRealtimeMs = deadline,
            observedAtElapsedRealtimeMs = elapsedRealtimeMs(),
        )
    }

    fun createFanControlAttemptV6(
        capability: TweakerManualFanCapability,
        mode: TweakerFanMode,
        curveBias: Int,
        timeoutMs: Long,
    ): TweakerFanControlAttempt? {
        if (mode !in capability.modes || timeoutMs <= 0L) return null
        if (curveBias !in capability.curveBiasRange || curveBias % capability.curveBiasStep != 0) return null
        val startedAt = elapsedRealtimeMs()
        val deadline = startedAt.saturatingAdd(timeoutMs)
        if (deadline <= startedAt) return null
        return TweakerFanControlAttempt(
            callerRequestId = "mimir.fan.$requestNonce.${requestCounter.incrementAndGet()}",
            requestDeadlineElapsedRealtimeMs = deadline,
            ownerRequestId = capability.ownerRequestId,
            generation = capability.generation,
            transactionId = capability.transactionId,
            priorMode = capability.currentMode,
            profileId = capability.profileId,
            mode = mode,
            curveBias = curveBias,
        )
    }

    suspend fun setFanControlV6(
        negotiatedApiVersion: Int,
        attempt: TweakerFanControlAttempt,
    ): TweakerFanControlAssessment = withContext(rpcDispatcher) {
        if (negotiatedApiVersion < TWEAKER_V6_ADVANCED_CONTROLS_MIN_API_VERSION) {
            return@withContext TweakerFanControlAssessment.Uncertain(
                "Tweaker service does not support API-v6 fan control",
            )
        }
        val api = service ?: return@withContext TweakerFanControlAssessment.Uncertain(
            "Tweaker service is not bound",
        )
        if (attempt.requestDeadlineElapsedRealtimeMs <= elapsedRealtimeMs()) {
            return@withContext TweakerFanControlAssessment.Uncertain("Fan control deadline expired before call")
        }
        val outcome = deadlineBoundedCall("setFanControlV6", attempt.requestDeadlineElapsedRealtimeMs) {
            api.setFanControlV6(
                attempt.callerRequestId,
                attempt.requestDeadlineElapsedRealtimeMs,
                ownerToken,
                attempt.ownerRequestId,
                attempt.generation,
                attempt.transactionId,
                attempt.mode.name,
                attempt.curveBias,
            )
        }
        outcome.deadlineEnding("Tweaker fan control")?.let {
            return@withContext TweakerFanControlAssessment.Uncertain(it)
        }
        if (outcome is TweakerBoundedCallOutcome.Threw) {
            // Diagnostic only (engineering lane, 2026-08-12) -- see getAdvancedCapabilitiesV6.
            Log.w(TAG, "setFanControlV6 call threw", outcome.error)
        }
        val bundle = outcome.replyOrNull() ?: return@withContext TweakerFanControlAssessment.Uncertain(
            "Tweaker fan control call failed",
        )
        parseTweakerFanControlBundle(
            bundle = bundle,
            expectedApiVersion = negotiatedApiVersion,
            attempt = attempt,
            observedAtElapsedRealtimeMs = elapsedRealtimeMs(),
        )
    }

    /**
     * API-v9: hand the daemon the discovered tuner's session so it outlives this process.
     *
     * No lease and no generation ceremony — there is nothing on the daemon side to renew or restore,
     * because this call mutates no hardware. It writes a memo.
     *
     * **Not bounded in the client.** This comment used to call the call
     * "one bounded Binder round trip"; nothing bounded it. The contract hands the daemon no deadline, so
     * there is none to stop at ([deadlineBoundedCall]), and a client-only bound is not a safe fix for a
     * WRITE: the client would report the write unconfirmed while the daemon can still land it. The same
     * holds for every other write here that carries no deadline: [clearDiscoveredSessionV9],
     * [applyDiscoveredCeilingV10], [withdrawDiscoveredCeilingV10], and the legacy [applyProfile] and
     * [restore]. [getFpsLimiterCapabilityV7] and [queryFpsLimiterV7] are reads that the daemon queues the
     * same way, and are bounded with [deadlineBoundedCall] --
     * a client-only bound on a READ cannot misreport a write the way it would here.
     *
     * **It returns the daemon's own verdict rather than a Boolean, and that is a correction.** The
     * service puts a named admission code in the bundle for every refusal, and this method used to
     * reduce all of them — plus an unbound service and a thrown transaction — to `false`, which its one
     * caller then dropped on the floor. A record refused for, say, `UNDISCOVERED_FREQUENCY` therefore
     * produced no record, no adoption at the next cold start, and no evidence anywhere that anything had
     * been declined. See [TweakerDiscoveredSessionWrite].
     */
    fun recordDiscoveredSessionV9(
        negotiatedApiVersion: Int,
        session: TweakerDiscoveredSession,
    ): TweakerDiscoveredSessionWrite {
        if (negotiatedApiVersion < TWEAKER_V9_DISCOVERED_RECORD_MIN_API_VERSION) {
            return TweakerDiscoveredSessionWrite.Unavailable("Tweaker service does not support API-v9")
        }
        val api = service
            ?: return TweakerDiscoveredSessionWrite.Unavailable("Tweaker service is not bound")
        val bundle = runCatching {
            api.recordDiscoveredSessionV9(
                session.bootIdentity,
                session.cpuStockRow,
                session.cpuRequestedRow,
                session.gpuStockMinLevel,
                session.gpuStockMaxLevel,
                session.gpuRequestedMinLevel,
                session.gpuRequestedMaxLevel,
            )
        }.getOrNull()
            ?: return TweakerDiscoveredSessionWrite.Unavailable("Discovered-session record call failed")
        return parseTweakerDiscoveredSessionWriteBundle(bundle, negotiatedApiVersion)
    }

    /**
     * API-v9: what the daemon is holding for the discovered axis.
     *
     * **Every failure returns [TweakerDiscoveredSessionRead.Unavailable], never [Absent].** An older
     * daemon, an unbound service and a thrown transaction all mean *"could not ask"*, and a caller
     * that read them as *"holds nothing"* would widen away a cap it could have restored exactly.
     *
     * Bounded like the capability reads: the call carries no deadline, so
     * [deadlineMs] is the client's own, and a daemon that never answers ends it as Unavailable. It runs
     * under the settlement reservation at cold start, where a hang used to hold that reservation.
     */
    fun queryDiscoveredSessionV9(
        negotiatedApiVersion: Int,
        deadlineMs: Long = CAPABILITY_CALL_DEADLINE_MS,
    ): TweakerDiscoveredSessionRead {
        if (negotiatedApiVersion < TWEAKER_V9_DISCOVERED_RECORD_MIN_API_VERSION) {
            return TweakerDiscoveredSessionRead.Unavailable("Tweaker service does not support API-v9")
        }
        val api = service
            ?: return TweakerDiscoveredSessionRead.Unavailable("Tweaker service is not bound")
        val boundMs = deadlineMs.coerceAtLeast(0L)
        val outcome = deadlineBoundedCall(
            "queryDiscoveredSessionV9",
            elapsedRealtimeMs().saturatingAdd(boundMs),
        ) { api.queryDiscoveredSessionV9() }
        outcome.deadlineEnding("Discovered-session query", timedOut = "timed out after $boundMs ms")?.let {
            return TweakerDiscoveredSessionRead.Unavailable(it)
        }
        val bundle = outcome.replyOrNull()
            ?: return TweakerDiscoveredSessionRead.Unavailable("Discovered-session query call failed")
        return parseTweakerDiscoveredSessionBundle(bundle, negotiatedApiVersion)
    }

    /** API-v9: forget the discovered-axis session, once Mimir has given the device back. */
    fun clearDiscoveredSessionV9(negotiatedApiVersion: Int): Boolean {
        if (negotiatedApiVersion < TWEAKER_V9_DISCOVERED_RECORD_MIN_API_VERSION) return false
        val api = service ?: return false
        val bundle = runCatching { api.clearDiscoveredSessionV9() }.getOrNull() ?: return false
        return bundle.getBoolean("success", false)
    }

    /**
     * API-v10: ask the daemon to hold a ceiling on one discovered policy .
     *
     * The same shape as [recordDiscoveredSessionV9]: no lease, no
     * generation ceremony, one round trip (not bounded in the client, see [recordDiscoveredSessionV9]),
     * a typed verdict. It differs from v9 in the one respect that is the whole point of v10 — this
     * MUTATES HARDWARE, in the daemon's process, so the
     * caller must already hold the exclusive mutation reservation when it arrives here. `TweakerBridge`
     * does; nothing else may call this.
     *
     * **[negotiatedApiVersion] below 10 is [TweakerDiscoveredCeilingWrite.Unavailable], never a
     * fallback.** An old daemon is still a daemon, and writing the node around it is exactly the
     * two-writer state this ownership boundary prevents. The route decides what happens instead (see
     * `mimirTunerRouteDecision`); this call's only job is to refuse to ask a service that cannot answer.
     */
    fun applyDiscoveredCeilingV10(
        negotiatedApiVersion: Int,
        axis: Int,
        policyId: Int,
        ceilingKhz: Int,
    ): TweakerDiscoveredCeilingWrite {
        if (negotiatedApiVersion < TWEAKER_V10_DISCOVERED_CEILING_MIN_API_VERSION) {
            return TweakerDiscoveredCeilingWrite.Unavailable("Tweaker service does not support API-v10")
        }
        val api = service
            ?: return TweakerDiscoveredCeilingWrite.Unavailable("Tweaker service is not bound")
        val bundle = runCatching { api.applyDiscoveredCeilingV10(axis, policyId, ceilingKhz) }
            .onFailure { Log.w(TAG, "applyDiscoveredCeilingV10 call threw", it) }
            .getOrNull()
            ?: return TweakerDiscoveredCeilingWrite.Unavailable("Discovered-ceiling apply call failed")
        return parseTweakerDiscoveredCeilingWriteBundle(bundle, negotiatedApiVersion)
    }

    /**
     * API-v10: give the ceiling back.
     *
     * Takes no arguments on purpose. The daemon restores exactly what IT captured, on the CPUs and
     * levels still holding ITS value — the ours-check `MimirTunerWrite.withdraw` performs in-process,
     * performed where the capture actually lives. A withdraw that named a value would be this side
     * telling a privileged process what to write, which is the shape the contract has refused since v6.
     */
    fun withdrawDiscoveredCeilingV10(negotiatedApiVersion: Int): TweakerDiscoveredCeilingWrite {
        if (negotiatedApiVersion < TWEAKER_V10_DISCOVERED_CEILING_MIN_API_VERSION) {
            return TweakerDiscoveredCeilingWrite.Unavailable("Tweaker service does not support API-v10")
        }
        val api = service
            ?: return TweakerDiscoveredCeilingWrite.Unavailable("Tweaker service is not bound")
        val bundle = runCatching { api.withdrawDiscoveredCeilingV10() }
            .onFailure { Log.w(TAG, "withdrawDiscoveredCeilingV10 call threw", it) }
            .getOrNull()
            ?: return TweakerDiscoveredCeilingWrite.Unavailable("Discovered-ceiling withdraw call failed")
        return parseTweakerDiscoveredCeilingWriteBundle(bundle, negotiatedApiVersion)
    }

    /**
     * API-v10: is the daemon holding a ceiling for THIS boot?
     *
     * **Every failure returns [TweakerDiscoveredCeilingRead.Unavailable], never `NotHeld`** — v9's rule
     * ([queryDiscoveredSessionV9]) with a sharper consequence. A caller that read *"could not ask"* as
     * *"holds nothing"* would widen away a cap the daemon is still holding, which is the cold-start
     * collision measured on the Thor on 2026-09-11.
     *
     * Bounded like the capability reads: the call carries no deadline, so
     * [deadlineMs] is the client's own, and a daemon that never answers ends it as Unavailable. The tuner's
     * `query` makes this call while holding the settlement reservation, where a hang used to hold it.
     */
    fun queryDiscoveredCeilingV10(
        negotiatedApiVersion: Int,
        deadlineMs: Long = CAPABILITY_CALL_DEADLINE_MS,
    ): TweakerDiscoveredCeilingRead {
        if (negotiatedApiVersion < TWEAKER_V10_DISCOVERED_CEILING_MIN_API_VERSION) {
            return TweakerDiscoveredCeilingRead.Unavailable("Tweaker service does not support API-v10")
        }
        val api = service
            ?: return TweakerDiscoveredCeilingRead.Unavailable("Tweaker service is not bound")
        val boundMs = deadlineMs.coerceAtLeast(0L)
        val outcome = deadlineBoundedCall(
            "queryDiscoveredCeilingV10",
            elapsedRealtimeMs().saturatingAdd(boundMs),
        ) { api.queryDiscoveredCeilingV10() }
        outcome.deadlineEnding("Discovered-ceiling query", timedOut = "timed out after $boundMs ms")?.let {
            return TweakerDiscoveredCeilingRead.Unavailable(it)
        }
        if (outcome is TweakerBoundedCallOutcome.Threw) {
            Log.w(TAG, "queryDiscoveredCeilingV10 call threw", outcome.error)
        }
        val bundle = outcome.replyOrNull()
            ?: return TweakerDiscoveredCeilingRead.Unavailable("Discovered-ceiling query call failed")
        return parseTweakerDiscoveredCeilingReadBundle(bundle, negotiatedApiVersion)
    }

    /**
     * API-v11: ask the daemon to make [packageName]'s `requestAudioFocus` fail, so other music keeps playing.
     *
     * Sends this session's process-stable [ownerToken]: the daemon links to its death and puts the package
     * back if this process dies. The hold is NOT tied to the binding, so the caller keeps the session bound
     * for as long as it holds (the daemon is a bound service and is destroyed with its last client).
     *
     * Only ever [TweakerGameAudioFocusResult.Held] after the daemon read the app-op back as ignore; every
     * other ending leaves the game with normal focus behaviour. Bounded at [deadlineMs]: the daemon runs the
     * command on its single transaction worker, so an unbounded wait here could hold a launch.
     */
    fun holdGameAudioFocusV11(
        negotiatedApiVersion: Int,
        packageName: String,
        deadlineMs: Long = GAME_AUDIO_FOCUS_CALL_DEADLINE_MS,
    ): TweakerGameAudioFocusResult {
        if (negotiatedApiVersion < TWEAKER_V11_GAME_AUDIO_FOCUS_MIN_API_VERSION) {
            return TweakerGameAudioFocusResult.Unavailable("Tweaker service does not support API-v11")
        }
        val safePackage = packageName.trim().takeIf(PACKAGE_NAME::matches)
            ?: return TweakerGameAudioFocusResult.Unavailable("A concrete Android package is required")
        val api = service ?: return TweakerGameAudioFocusResult.Unavailable("Tweaker service is not bound")
        return gameAudioFocusCall("holdGameAudioFocusV11", negotiatedApiVersion, deadlineMs) {
            api.holdGameAudioFocusV11(ownerToken, safePackage)
        }
    }

    /** API-v11: put the held package back. Takes no argument on purpose; see the AIDL. */
    fun releaseGameAudioFocusV11(
        negotiatedApiVersion: Int,
        deadlineMs: Long = GAME_AUDIO_FOCUS_CALL_DEADLINE_MS,
    ): TweakerGameAudioFocusResult {
        if (negotiatedApiVersion < TWEAKER_V11_GAME_AUDIO_FOCUS_MIN_API_VERSION) {
            return TweakerGameAudioFocusResult.Unavailable("Tweaker service does not support API-v11")
        }
        val api = service ?: return TweakerGameAudioFocusResult.Unavailable("Tweaker service is not bound")
        return gameAudioFocusCall("releaseGameAudioFocusV11", negotiatedApiVersion, deadlineMs) {
            api.releaseGameAudioFocusV11()
        }
    }

    private fun gameAudioFocusCall(
        operation: String,
        negotiatedApiVersion: Int,
        deadlineMs: Long,
        call: () -> Bundle,
    ): TweakerGameAudioFocusResult {
        val boundMs = deadlineMs.coerceAtLeast(0L)
        val outcome = deadlineBoundedCall(operation, elapsedRealtimeMs().saturatingAdd(boundMs), call)
        outcome.deadlineEnding("Game audio-focus call", timedOut = "timed out after $boundMs ms")?.let {
            return TweakerGameAudioFocusResult.Unavailable(it)
        }
        if (outcome is TweakerBoundedCallOutcome.Threw) {
            Log.w(TAG, "$operation call threw", outcome.error)
        }
        val bundle = outcome.replyOrNull()
            ?: return TweakerGameAudioFocusResult.Unavailable("Game audio-focus call failed")
        return parseTweakerGameAudioFocusBundle(bundle, negotiatedApiVersion)
    }

    /**
     * Is a NAMED PROFILE running on this already-bound session?
     *
     * The ceiling and a profile are mutually exclusive on the same node, so the route has to know which
     * one the daemon is running. This asks [querySessionV4] and maps its verdict through
     * [namedProfileSessionActive] — no new AIDL method, because the existing one answers the question
     * exactly and a second way to ask it would be a second version of one fact.
     *
     * Null is *"could not be established"*: an old daemon, an unbound service, an uncertain truth. It is
     * never folded into false.
     */
    fun namedProfileSessionActiveV4(
        negotiatedApiVersion: Int,
        timeoutMs: Long = DEFAULT_QUERY_TIMEOUT_MS,
    ): Boolean? = namedProfileSessionActive(querySessionV4(negotiatedApiVersion, timeoutMs))

    /**
     * Bounded like [getTelemetry] and [getCapabilities] (register finding O-2). This is the first
     * capability call in every refresh chain (`TweakerBridge.refreshProjectionFromBoundSession`,
     * `selectSystemProfile`, ...), including at least one path that runs while holding the
     * exclusive mutation reservation -- an unbounded hang here previously could not just freeze a
     * read, it could hold that lock indefinitely.
     *
     * Runs on its own [apiVersionCallExecutor], not the shared [capabilityCallExecutor] or
     * [deadlineCallExecutor] because preventing a stuck sibling call from refusing
     * the version read requires a dedicated one-thread pool that only ANOTHER [getApiVersion] call can fill,
     * not a shared pool with a bigger blast radius in either direction.
     */
    fun getApiVersion(deadlineMs: Long = CAPABILITY_CALL_DEADLINE_MS): Int? {
        val api = service ?: return null
        return when (
            val outcome = boundedCapabilityCall(deadlineMs, apiVersionCallExecutor) { api.apiVersion }
        ) {
            is TweakerBoundedCallOutcome.Completed -> {
                val value = outcome.value
                if (value > 0) {
                    value
                } else {
                    Log.w(TAG, "getApiVersion outcome=NON_POSITIVE value=$value")
                    null
                }
            }
            is TweakerBoundedCallOutcome.Threw -> {
                Log.w(TAG, "getApiVersion outcome=THREW", outcome.error)
                null
            }
            TweakerBoundedCallOutcome.TimedOut -> {
                noteUnansweredQuery()
                Log.w(TAG, "getApiVersion outcome=TIMED_OUT deadlineMs=$deadlineMs")
                null
            }
            TweakerBoundedCallOutcome.Rejected -> {
                noteUnansweredQuery()
                Log.w(TAG, "getApiVersion outcome=REJECTED reason=capability_call_in_flight")
                null
            }
        }
    }

    suspend fun getApiVersionAsync(): Int? = withContext(rpcDispatcher) { getApiVersion() }

    suspend fun beginActiveSessionV4Async(): Boolean = withContext(rpcDispatcher) {
        beginActiveSessionV4()
    }

    /**
     * Read-only V4 truth. Request identity is fresh per call while [ownerToken] remains stable for the
     * lifetime of this process-owned session. The wait ends at the deadline handed to the daemon
     * ([deadlineBoundedCall]); a reply after it is dropped and the query reads Uncertain.
     */
    fun querySessionV4(
        negotiatedApiVersion: Int,
        timeoutMs: Long = DEFAULT_QUERY_TIMEOUT_MS,
    ): TweakerV4SessionAssessment {
        val api = service
            ?: return TweakerV4SessionAssessment.Uncertain("Tweaker service is not bound")
        if (negotiatedApiVersion < TWEAKER_V4_MIN_API_VERSION) {
            return TweakerV4SessionAssessment.Uncertain("Tweaker service does not support API v4")
        }
        if (timeoutMs <= 0L) {
            return TweakerV4SessionAssessment.Uncertain("V4 query timeout must be positive")
        }
        val startedAt = elapsedRealtimeMs()
        val deadline = startedAt.saturatingAdd(timeoutMs)
        if (deadline <= startedAt) {
            return TweakerV4SessionAssessment.Uncertain("V4 query deadline could not be created")
        }
        val requestId = "mimir.query.$requestNonce.${requestCounter.incrementAndGet()}"
        val outcome = deadlineBoundedCall("querySessionV4", deadline) {
            api.querySessionV4(requestId, deadline, ownerToken)
        }
        outcome.deadlineEnding("Tweaker V4 session query")?.let {
            return TweakerV4SessionAssessment.Uncertain(it)
        }
        val bundle = outcome.replyOrNull()
            ?: return TweakerV4SessionAssessment.Uncertain("Tweaker V4 session query failed")
        return parseTweakerV4SessionBundle(
            bundle = bundle,
            expectedApiVersion = negotiatedApiVersion,
            expectedCallerRequestId = requestId,
            expectedCallerDeadlineElapsedRealtimeMs = deadline,
            observedAtElapsedRealtimeMs = elapsedRealtimeMs(),
        )
    }

    /** Off-main counterpart for process-owned controllers. */
    suspend fun querySessionV4Async(
        negotiatedApiVersion: Int,
        timeoutMs: Long = DEFAULT_QUERY_TIMEOUT_MS,
    ): TweakerV4SessionAssessment = withContext(rpcDispatcher) {
        querySessionV4(negotiatedApiVersion, timeoutMs)
    }

    /** Allocates the durable owner request identity before apply begins. */
    fun createApplyAttemptV4(
        profileId: String,
        packageName: String,
        timeoutMs: Long,
    ): TweakerV4ApplyAttempt? {
        val safeProfile = profileId.trim().lowercase()
        val safePackage = packageName.trim()
        if (safeProfile !in TWEAKER_PRODUCT_QUALIFIABLE_PROFILE_IDS) return null
        if (!PACKAGE_NAME.matches(safePackage) || timeoutMs <= 0L) return null
        val startedAt = elapsedRealtimeMs()
        val deadline = startedAt.saturatingAdd(timeoutMs)
        if (deadline <= startedAt) return null
        return TweakerV4ApplyAttempt(
            requestId = "mimir.apply.$requestNonce.${requestCounter.incrementAndGet()}",
            profileId = safeProfile,
            packageName = safePackage,
            requestDeadlineElapsedRealtimeMs = deadline,
        )
    }

    /** API-v5 system scope has no fabricated workload package. */
    fun createSystemSelectionAttemptV5(
        profileId: String,
        timeoutMs: Long,
    ): TweakerV5SystemSelectionAttempt? {
        val safeProfile = profileId.trim().lowercase()
        if (safeProfile !in TWEAKER_SYSTEM_SELECTABLE_PROFILE_IDS || timeoutMs <= 0L) return null
        val startedAt = elapsedRealtimeMs()
        val deadline = startedAt.saturatingAdd(timeoutMs)
        if (deadline <= startedAt) return null
        return TweakerV5SystemSelectionAttempt(
            requestId = "mimir.system.$requestNonce.${requestCounter.incrementAndGet()}",
            profileId = safeProfile,
            requestDeadlineElapsedRealtimeMs = deadline,
        )
    }

    suspend fun selectSystemProfileV5(
        negotiatedApiVersion: Int,
        attempt: TweakerV5SystemSelectionAttempt,
    ): TweakerV5SystemSelectionAssessment = withContext(rpcDispatcher) {
        if (negotiatedApiVersion < TWEAKER_V5_SYSTEM_SCOPE_MIN_API_VERSION) {
            return@withContext TweakerV5SystemSelectionAssessment.Uncertain(
                "Tweaker service does not support API-v5 system scope",
            )
        }
        val api = service ?: return@withContext TweakerV5SystemSelectionAssessment.Uncertain(
            "Tweaker service is not bound",
        )
        if (attempt.requestDeadlineElapsedRealtimeMs <= elapsedRealtimeMs()) {
            return@withContext TweakerV5SystemSelectionAssessment.Uncertain(
                "System-wide selection deadline expired before call",
            )
        }
        val outcome = deadlineBoundedCall("selectSystemProfileV5", attempt.requestDeadlineElapsedRealtimeMs) {
            api.selectSystemProfileV5(
                attempt.requestId,
                attempt.requestDeadlineElapsedRealtimeMs,
                ownerToken,
                attempt.profileId,
            )
        }
        outcome.deadlineEnding("Tweaker system-wide selection")?.let {
            return@withContext TweakerV5SystemSelectionAssessment.Uncertain(it)
        }
        val bundle = outcome.replyOrNull() ?: return@withContext TweakerV5SystemSelectionAssessment.Uncertain(
            "Tweaker system-wide selection failed",
        )
        parseTweakerV5SystemSelectionBundle(
            bundle = bundle,
            expectedApiVersion = negotiatedApiVersion,
            attempt = attempt,
            observedAtElapsedRealtimeMs = elapsedRealtimeMs(),
        )
    }

    suspend fun applyProfileV4(
        negotiatedApiVersion: Int,
        attempt: TweakerV4ApplyAttempt,
    ): TweakerV4OperationAssessment = executeV4(
        negotiatedApiVersion = negotiatedApiVersion,
        expectation = TweakerV4OperationExpectation.Apply(attempt),
        callName = "applyProfileV4",
        label = "Tweaker V4 apply",
    ) { api ->
        api.applyProfileV4(
            attempt.requestId,
            attempt.requestDeadlineElapsedRealtimeMs,
            ownerToken,
            attempt.profileId,
            attempt.packageName,
        )
    }

    suspend fun cancelInFlightV4(
        negotiatedApiVersion: Int,
        attempt: TweakerV4ApplyAttempt,
        timeoutMs: Long,
    ): TweakerV4OperationAssessment {
        val deadline = operationDeadline(timeoutMs)
            ?: return uncertain(TweakerV4Operation.CANCEL_IN_FLIGHT, "Cancel deadline is invalid")
        return executeV4(
            negotiatedApiVersion = negotiatedApiVersion,
            expectation = TweakerV4OperationExpectation.CancelInFlight(attempt, deadline),
            callName = "cancelRequestV4",
            label = "Tweaker V4 in-flight cancel",
        ) { api ->
            api.cancelRequestV4(attempt.requestId, deadline, ownerToken, 0L, "")
        }
    }

    suspend fun cancelActiveV4(
        negotiatedApiVersion: Int,
        owner: TweakerV4OwnedSession,
        timeoutMs: Long,
    ): TweakerV4OperationAssessment {
        val deadline = operationDeadline(timeoutMs)
            ?: return uncertain(TweakerV4Operation.CANCEL_ACTIVE, "Cancel deadline is invalid")
        return executeV4(
            negotiatedApiVersion = negotiatedApiVersion,
            expectation = TweakerV4OperationExpectation.CancelActive(owner, deadline),
            callName = "cancelRequestV4",
            label = "Tweaker V4 active cancel",
        ) { api ->
            api.cancelRequestV4(
                owner.requestId,
                deadline,
                ownerToken,
                owner.generation,
                owner.transactionId,
            )
        }
    }

    suspend fun renewLeaseV4(
        negotiatedApiVersion: Int,
        owner: TweakerV4OwnedSession,
        timeoutMs: Long,
    ): TweakerV4OperationAssessment {
        val deadline = operationDeadline(timeoutMs)
            ?: return uncertain(TweakerV4Operation.RENEW, "Renew deadline is invalid")
        return executeV4(
            negotiatedApiVersion = negotiatedApiVersion,
            expectation = TweakerV4OperationExpectation.Renew(owner, deadline),
            callName = "renewLeaseV4",
            label = "Tweaker V4 lease renewal",
        ) { api ->
            api.renewLeaseV4(
                owner.requestId,
                deadline,
                ownerToken,
                owner.generation,
                owner.transactionId,
            )
        }
    }

    suspend fun restoreSessionV4(
        negotiatedApiVersion: Int,
        owner: TweakerV4OwnedSession,
        timeoutMs: Long,
    ): TweakerV4OperationAssessment {
        val deadline = operationDeadline(timeoutMs)
            ?: return uncertain(TweakerV4Operation.RESTORE, "Restore deadline is invalid")
        return executeV4(
            negotiatedApiVersion = negotiatedApiVersion,
            expectation = TweakerV4OperationExpectation.Restore(owner, deadline),
            callName = "restoreSessionV4",
            label = "Tweaker V4 restore",
        ) { api ->
            api.restoreSessionV4(
                owner.requestId,
                deadline,
                ownerToken,
                owner.generation,
                owner.transactionId,
            )
        }
    }

    fun applyProfile(profileId: String, packageName: String): TweakerApplyResult? {
        val api = service ?: return null
        return runCatching {
            val bundle = api.applyProfile(profileId, packageName)
            TweakerApplyResult.fromBundle(bundle)
        }.getOrNull()
    }

    fun restore(): TweakerApplyResult? {
        val api = service ?: return null
        return runCatching {
            val bundle = api.restore()
            TweakerApplyResult.fromBundle(bundle)
        }.getOrNull()
    }

    private suspend fun executeV4(
        negotiatedApiVersion: Int,
        expectation: TweakerV4OperationExpectation,
        callName: String,
        label: String,
        call: (IMimirTweaker) -> android.os.Bundle,
    ): TweakerV4OperationAssessment = withContext(rpcDispatcher) {
        if (negotiatedApiVersion < TWEAKER_V4_MIN_API_VERSION) {
            return@withContext uncertain(
                expectation.operation,
                "Tweaker service does not support API v4",
            )
        }
        val api = service
            ?: return@withContext uncertain(expectation.operation, "Tweaker service is not bound")
        if (expectation.callerDeadlineElapsedRealtimeMs <= elapsedRealtimeMs()) {
            return@withContext uncertain(expectation.operation, "V4 operation deadline expired before call")
        }
        val outcome = deadlineBoundedCall(callName, expectation.callerDeadlineElapsedRealtimeMs) { call(api) }
        outcome.deadlineEnding(label)?.let {
            return@withContext uncertain(expectation.operation, it)
        }
        val bundle = outcome.replyOrNull()
            ?: return@withContext uncertain(expectation.operation, "Tweaker V4 operation failed")
        parseTweakerV4OperationBundle(
            bundle = bundle,
            expectedApiVersion = negotiatedApiVersion,
            expectation = expectation,
            observedAtElapsedRealtimeMs = elapsedRealtimeMs(),
        )
    }

    private fun operationDeadline(timeoutMs: Long): Long? {
        if (timeoutMs <= 0L) return null
        val startedAt = elapsedRealtimeMs()
        return startedAt.saturatingAdd(timeoutMs).takeIf { it > startedAt }
    }

    private fun uncertain(
        operation: TweakerV4Operation,
        reason: String,
    ): TweakerV4OperationAssessment = TweakerV4OperationAssessment.Uncertain(operation, reason)

    /**
     * Runs [call] on [executor] (default [capabilityCallExecutor]), bounded to [deadlineMs]. Distinguishes
     * the four ways an unbounded transport call used to end identically -- as a `null` with nothing in
     * logcat: never returned within the deadline ([TweakerBoundedCallOutcome.TimedOut], the transport thread
     * stays blocked on the hung transaction so a rejection is possible on the next call, mirroring
     * [getTelemetry]'s existing rationale), threw ([TweakerBoundedCallOutcome.Threw]), or returned
     * cleanly ([TweakerBoundedCallOutcome.Completed] -- the caller distinguishes a clean `null` from
     * a value that fails to parse, since only the caller knows how to parse its own bundle shape).
     * [TweakerBoundedCallOutcome.Rejected] is the queue-full case: [executor]'s single thread is still
     * occupied by an earlier hung call on the SAME executor -- see [getApiVersion] and
     * [apiVersionCallExecutor] for why this is parameterized rather than always [capabilityCallExecutor].
     */
    private fun <T> boundedCapabilityCall(
        deadlineMs: Long,
        executor: ThreadPoolExecutor = capabilityCallExecutor,
        call: () -> T,
    ): TweakerBoundedCallOutcome<T> {
        val future = try {
            executor.submit<T> { call() }
        } catch (rejected: RejectedExecutionException) {
            return TweakerBoundedCallOutcome.Rejected
        }
        return try {
            TweakerBoundedCallOutcome.Completed(future.get(deadlineMs, TimeUnit.MILLISECONDS))
        } catch (timeout: TimeoutException) {
            TweakerBoundedCallOutcome.TimedOut
        } catch (executed: ExecutionException) {
            TweakerBoundedCallOutcome.Threw(executed.cause ?: executed)
        }
    }

    /**
     * Runs [call] on [deadlineCallExecutor] and stops waiting at [deadlineElapsedRealtimeMs]: the deadline the
     * daemon was handed, or, for a read that carries none, the bound its caller chose. The endings are
     * [TweakerBoundedCallOutcome]'s, logged under [operation] the way the capability reads log theirs. A reply
     * that arrives after the wait ended is never returned or parsed: its own thread logs
     * `outcome=LATE_REPLY_IGNORED` and drops it.
     */
    private fun <T> deadlineBoundedCall(
        operation: String,
        deadlineElapsedRealtimeMs: Long,
        call: () -> T,
    ): TweakerBoundedCallOutcome<T> {
        val stuck = abandonedDeadlineCalls.get()
        if (stuck >= MAX_ABANDONED_DEADLINE_CALLS) {
            Log.w(TAG, "$operation outcome=REJECTED reason=calls_stuck_past_deadline stuck=$stuck")
            noteUnansweredQuery()
            return TweakerBoundedCallOutcome.Rejected
        }
        val waitBudgetMs = deadlineElapsedRealtimeMs - elapsedRealtimeMs()
        if (waitBudgetMs <= 0L) {
            Log.w(TAG, "$operation outcome=TIMED_OUT reason=deadline_passed_before_call")
            noteUnansweredQuery()
            return TweakerBoundedCallOutcome.TimedOut
        }
        val waiter = AtomicInteger(DEADLINE_CALL_WAITING)
        val submittedAtNs = System.nanoTime()
        val reply = try {
            deadlineCallExecutor.submit<T> {
                try {
                    call()
                } finally {
                    if (!waiter.compareAndSet(DEADLINE_CALL_WAITING, DEADLINE_CALL_ANSWERED)) {
                        abandonedDeadlineCalls.decrementAndGet()
                        Log.w(
                            TAG,
                            "$operation outcome=LATE_REPLY_IGNORED waitBudgetMs=$waitBudgetMs " +
                                "answeredAfterMs=${(System.nanoTime() - submittedAtNs) / 1_000_000L}",
                        )
                    }
                }
            }
        } catch (rejected: RejectedExecutionException) {
            Log.w(TAG, "$operation outcome=REJECTED reason=executor_rejected")
            return TweakerBoundedCallOutcome.Rejected
        }
        return try {
            TweakerBoundedCallOutcome.Completed(reply.get(waitBudgetMs, TimeUnit.MILLISECONDS))
        } catch (timeout: TimeoutException) {
            abandonAtDeadline(waiter)
            Log.w(TAG, "$operation outcome=TIMED_OUT waitBudgetMs=$waitBudgetMs")
            noteUnansweredQuery()
            TweakerBoundedCallOutcome.TimedOut
        } catch (executed: ExecutionException) {
            TweakerBoundedCallOutcome.Threw(executed.cause ?: executed)
        } catch (interrupted: InterruptedException) {
            abandonAtDeadline(waiter)
            Thread.currentThread().interrupt()
            TweakerBoundedCallOutcome.Threw(interrupted)
        }
    }

    /** Counted before the claim, so the late reply's own release can never run ahead of the count. */
    private fun abandonAtDeadline(waiter: AtomicInteger) {
        abandonedDeadlineCalls.incrementAndGet()
        if (!waiter.compareAndSet(DEADLINE_CALL_WAITING, DEADLINE_CALL_ABANDONED)) {
            // The reply landed in the instant the wait ran out, so no thread is left in the transaction.
            abandonedDeadlineCalls.decrementAndGet()
        }
    }

    /** Clears either the current connection or the exact expected callback owner atomically. */
    private fun abandonConnection(expected: ServiceConnection? = null): Boolean {
        val abandoned = synchronized(connectionLock) {
            val active = connection
            if (expected != null && active !== expected) return@synchronized null
            connection = null
            service = null
            bound = false
            val shouldUnbind = serviceBindActive
            serviceBindActive = false
            active to shouldUnbind
        } ?: return false
        val (active, shouldUnbind) = abandoned
        if (shouldUnbind) {
            active?.let { conn -> runCatching { unbindService(conn) } }
        }
        return true
    }

    companion object {
        private const val TAG = "TweakerSession"
        const val TWEAKER_PACKAGE = "com.gruning.mimir.tweaker"
        const val TWEAKER_SERVICE = "com.gruning.mimir.tweaker.MimirTweakerService"
        const val CONTROL_PERMISSION = "com.gruning.mimir.tweaker.permission.CONTROL_TWEAKER"
        const val DEFAULT_QUERY_TIMEOUT_MS = 1_500L
        /** Matches the transport's established bind deadline; well above a legitimately slow sweep. */
        const val TELEMETRY_CALL_DEADLINE_MS = 2_500L
        /** Same rationale and value as [TELEMETRY_CALL_DEADLINE_MS], for the capability-read trio. */
        const val CAPABILITY_CALL_DEADLINE_MS = 2_500L
        /**
         * The game audio-focus hold runs `set` plus a readback through the privileged service on the daemon's
         * one transaction worker, in front of a game launch whose whole pre-dispatch budget is 9.5 s.
         */
        const val GAME_AUDIO_FOCUS_CALL_DEADLINE_MS = 3_000L

        /**
         * How many calls may be stuck past their deadlines before the next one is refused at once. Each keeps
         * one thread in a Binder transaction the client cannot cancel, until the daemon answers or dies. Only
         * abandoned calls count, so no overlap of calls that answer in time is ever refused. Four is a
         * judgment, not a measurement: a timed-out selection and the reconcile reads after it are still
         * attempted, and a daemon that ignores every deadline costs four threads instead of one per refresh.
         */
        private const val MAX_ABANDONED_DEADLINE_CALLS = 4
        private const val DEADLINE_CALL_WAITING = 0
        private const val DEADLINE_CALL_ANSWERED = 1
        private const val DEADLINE_CALL_ABANDONED = 2
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        private val FPS_LIMIT_VALUES = setOf(30, 40, 60, 90, 120)
    }
}

private fun Long.saturatingAdd(delta: Long): Long =
    if (this > Long.MAX_VALUE - delta) Long.MAX_VALUE else this + delta

/**
 * The four distinguishable outcomes of a [TweakerSession.boundedCapabilityCall] and of a
 * [TweakerSession.deadlineBoundedCall]. Before this existed, a hang, a thrown exception, and a clean
 * `null` (see [TweakerSession.getCapabilities], [TweakerSession.getAdvancedCapabilitiesV6]) all collapsed
 * to the same silent `null` at the call site, making the four outcomes indistinguishable.
 */
private sealed class TweakerBoundedCallOutcome<out T> {
    data class Completed<T>(val value: T) : TweakerBoundedCallOutcome<T>()
    data class Threw(val error: Throwable) : TweakerBoundedCallOutcome<Nothing>()
    data object TimedOut : TweakerBoundedCallOutcome<Nothing>()
    data object Rejected : TweakerBoundedCallOutcome<Nothing>()
}

/** The reply a call completed with; null when it threw or the daemon returned null. */
private fun <T> TweakerBoundedCallOutcome<T>.replyOrNull(): T? =
    if (this is TweakerBoundedCallOutcome.Completed) value else null

/**
 * The named ending of a call that produced no reply in time: its timeout, or its refusal before it was
 * sent. Null when the call completed or threw, which each caller reports in its own words, as before.
 */
private fun TweakerBoundedCallOutcome<*>.deadlineEnding(
    label: String,
    timedOut: String = "timed out at its deadline",
): String? = when (this) {
    TweakerBoundedCallOutcome.TimedOut -> "$label $timedOut"
    TweakerBoundedCallOutcome.Rejected ->
        "$label refused: earlier Tweaker calls are still stuck past their deadlines"
    is TweakerBoundedCallOutcome.Completed<*>, is TweakerBoundedCallOutcome.Threw -> null
}

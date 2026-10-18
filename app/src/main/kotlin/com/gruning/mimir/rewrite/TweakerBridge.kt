package com.gruning.mimir.rewrite

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.gruning.mimir.rewrite.core.audio.AudioPreferences
import com.gruning.mimir.rewrite.core.launch.LaunchAttemptId
import com.gruning.mimir.rewrite.core.tweaker.TweakerApplyResult
import com.gruning.mimir.rewrite.core.tweaker.TweakerAdvancedCapabilities
import com.gruning.mimir.rewrite.core.tweaker.TweakerCapabilities
import com.gruning.mimir.rewrite.core.tweaker.TweakerDiscoveredSessionWrite
import com.gruning.mimir.rewrite.core.tweaker.TweakerFanControlAssessment
import com.gruning.mimir.rewrite.core.tweaker.TweakerFanControlReadout
import com.gruning.mimir.rewrite.core.tweaker.TweakerFanMode
import com.gruning.mimir.rewrite.core.tweaker.TweakerManualFanCapability
import com.gruning.mimir.rewrite.core.tweaker.TweakerPreparationResult
import com.gruning.mimir.rewrite.core.tweaker.TweakerSession
import com.gruning.mimir.rewrite.core.tweaker.TweakerSessionScope
import com.gruning.mimir.rewrite.core.tweaker.TweakerTelemetry
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityAvailability
import com.gruning.mimir.rewrite.core.tweaker.TweakerDiscoveredCeiling
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityRowState
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityStepOutcome
import com.gruning.mimir.rewrite.core.tweaker.TweakerV4Owner
import com.gruning.mimir.rewrite.core.tweaker.TweakerV4SessionAssessment
import com.gruning.mimir.rewrite.core.tweaker.TweakerV4SessionState
import com.gruning.mimir.rewrite.core.tweaker.TweakerV5SystemSelectionAssessment
import com.gruning.mimir.rewrite.core.tweaker.TWEAKER_V4_MIN_API_VERSION
import com.gruning.mimir.rewrite.core.tweaker.TWEAKER_V5_SYSTEM_SCOPE_MIN_API_VERSION
import com.gruning.mimir.rewrite.core.tweaker.TWEAKER_V6_ADVANCED_CONTROLS_MIN_API_VERSION
import com.gruning.mimir.rewrite.core.tweaker.TWEAKER_V7_FPS_LIMITER_MIN_API_VERSION
import com.gruning.mimir.rewrite.core.tweaker.allowsLabOwnerValidation
import com.gruning.mimir.rewrite.core.tweaker.isReachable
import com.gruning.mimir.rewrite.core.tweaker.TweakerLaunchSelectionContext
import com.gruning.mimir.rewrite.core.tweaker.launchWarning
import com.gruning.mimir.rewrite.core.tweaker.namedProfileSessionActive
import com.gruning.mimir.rewrite.core.tweaker.selectableTweakerProfileIds
import com.gruning.mimir.rewrite.core.tweaker.stepOutcome
import com.gruning.mimir.rewrite.core.tweaker.tweakerUtilityAvailability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** App-layer bridge to the isolated Mimir Tweaker service. */
object TweakerBridge {
    private lateinit var appContext: Context
    /** Exactly one process-stable session/Binder token serves observation and the strict V4 owner. */
    private val session by lazy { TweakerSession(appContext) }
    private val v4Owner by lazy { TweakerV4Owner(session) }
    /**
     * Background Tweaker work must never take the launcher down with it.
     *
     * `SupervisorJob` stops a sibling failure cancelling the scope; it does NOT stop the exception. With
     * no handler in the context an uncaught throw here reaches the thread's uncaught-exception handler,
     * which on Android kills the process. Everything on this scope talks to a daemon that can be absent,
     * busy, or mid-restore -- `discardLegacyGlobalGameFpsSlot`, `refreshCapabilities`,
     * `scheduleStandingRearm`, `submitDiscoveredSession` -- so a probe that throws would have cost the
     * player their session. log loudly and carry on.
     *
     * It LOGS at error level rather than swallowing: trading a crash for an invisible failure would be
     * the worse bargain. This is also what made the app unit suite fail about one run in five.
     *
     * Thermals are unaffected either way: the daemon's guardian restores on process death, and it has
     * nothing to restore while this process keeps running.
     */
    internal val ownerScopeFailureHandler = CoroutineExceptionHandler { context, throwable ->
        Log.e(TAG_BRIDGE, "background tweaker work failed in $context", throwable)
    }

    private val ownerScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + ownerScopeFailureHandler)
    private val sessionReservation = TweakerSessionReservation()
    /** The discovered-tuner cold start is a PROCESS event; `onCreate` can run many times per process. */
    private val discoveredColdStartDone = AtomicBoolean(false)

    /**
     * True from the moment the cold-start coroutine is launched until its reconcile has returned.
     *
     * **It stops the deferred reconcile racing the ordinary one.** [readDiscoveredSessionAtBind] calls
     * [noteDaemonAnswered] a few lines before [MimirTunerWrite.releaseAnyOrphanedCap] runs, so without
     * this the very answer that ARMS the cold start would also trigger a second, identical query
     * against the same daemon -- two `cold start reconcile:` lines for one event, on the one device
     * class where everything already works.
     */
    private val discoveredColdStartPending = AtomicBoolean(false)
    private val returnSettlementLock = Any()
    private var returnSettlement: CompletableDeferred<TweakerCapabilities?>? = null
    private val v4LaunchLifecycle by lazy {
        TweakerV4LaunchLifecycle(
            owner = AndroidTweakerV4OwnerPort(v4Owner),
            fps = AndroidTweakerFpsLaunchPort(session),
            scope = ownerScope,
            elapsedRealtimeMs = SystemClock::elapsedRealtime,
            // A lambda, not a method reference: `endOwner` now reports whether it freed the last
            // holder, and this release path has no use for that answer.
            onTransportReleased = { sessionReservation.endOwner() },
        )
    }
    // A getter, not lazy: preferences must follow the current appContext after re-initialization.
    private val preferences
        get() = appContext.getSharedPreferences("mimir_tweaker_bridge", Context.MODE_PRIVATE)

    @Volatile
    var capabilities: TweakerCapabilities? = null
        private set(value) {
            field = reconcileCapabilitiesWrite(previous = field, incoming = value)
        }

    /**
     * True while the last capability refresh produced no capabilities because the daemon did not
     * ANSWER (a query timed out), as opposed to answering no. Unknown, not Unavailable: the row keeps
     * the last known profile, the standing choice stays, and the rearm watch keeps watching.
     */
    @Volatile
    var capabilityTruthUnknown: Boolean = false
        private set

    /**
     * True once the daemon has named its API version to this process: it exists, it is bindable, and it ANSWERED, whatever
     * it then declined to turn into capabilities. Cleared by a package event, which may have replaced it. The standing
     * watch reads it to tell a daemon that said "nothing for you" from one it never reached.
     */
    @Volatile
    private var daemonAnsweredInProcess: Boolean = false

    /** [TweakerSession.unansweredQueryCount] as of the previous write to [capabilities]. */
    @Volatile
    private var unansweredQueriesAtLastCapabilitiesWrite: Int = 0

    private fun unansweredQueryCountOrZero(): Int =
        runCatching { session.unansweredQueryCount() }.getOrDefault(0)

    /** The last line logged under each key, so a projection that says the same thing again says nothing. */
    private val lastLoggedByKey = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Logs [message] only when it differs from the last one logged under [key]: a per-second refresh is not a per-second event. */
    private fun logOnChange(key: String, message: String) {
        if (lastLoggedByKey.put(key, message) != message) Log.i(TAG_BRIDGE, message)
    }

    /**
     * A refresh that never reached the daemon learned nothing, and must not clear what was learned before.
     *
     * Three kinds of null looked the same to the row and are not the same: a daemon that SAID no, a query
     * that timed out ([reconcileCapabilitiesWrite] keeps the last answer for that), and a refresh that never
     * ASKED -- the token was not obtainable, a launch owned the transport, or the bind failed on a pair that
     * can bind. The last was written as an authoritative null, so a return-from-game settlement that arrived
     * while the token was busy turned a READY row into DISCOVERED_CEILING with `effective=stock`, and the
     * standing watch read that as a definitive "not offered" (Nova 2026-10-01 12:45:27). Capabilities stay as
     * they were and the truth is marked unanswered; the next answer from the daemon replaces both.
     */
    private fun retainCapabilitiesUnanswered() {
        capabilityTruthUnknown = true
    }

    /**
     * What a failed bind does to the capabilities: a pair that can never bind clears them (the package is
     * gone, or it is the wrong build), a pair that CAN bind keeps them and marks the truth unanswered.
     */
    private fun capabilitiesAfterFailedBind(pairIdentity: TweakerPairIdentity) {
        val outcome = reconcileCapabilitiesAfterFailedBind(capabilities, pairIdentity)
        if (outcome.truthUnknown) retainCapabilitiesUnanswered() else capabilities = outcome.capabilities
    }

    /**
     * A refresh that ended in `null` after a query timed out is not evidence that tuning is gone.
     *
     * The Nova's 09-30 sessions: each Guardian restore stalled Binder, `getAdvancedCapabilitiesV6` and
     * `querySessionV4` timed out, [capabilities] went null, and the row fell to the discovered-ceiling
     * path and appeared as Stock/Unavailable while the standing re-arm watch read that as a
     * definitive NOT_OFFERED and stood down. A null that follows an unanswered query keeps the previous
     * capabilities and marks the truth unknown; any other null (bind refused, package gone, API too
     * old) is still authoritative and clears them, exactly as before.
     */
    private fun reconcileCapabilitiesWrite(
        previous: TweakerCapabilities?,
        incoming: TweakerCapabilities?,
    ): TweakerCapabilities? {
        val unanswered = unansweredQueryCountOrZero()
        val silent = unanswered != unansweredQueriesAtLastCapabilitiesWrite
        unansweredQueriesAtLastCapabilitiesWrite = unanswered
        val outcome = reconcileCapabilitiesAfterRefresh(previous, incoming, silent)
        capabilityTruthUnknown = outcome.truthUnknown
        if (incoming == null && outcome.capabilities != null) {
            val kept = outcome.capabilities
            Log.i(
                TAG_BRIDGE,
                "capabilities unknown: a daemon query went unanswered; keeping last known " +
                    "status=${kept.status} standing=${runCatching { standingSystemProfileId() }.getOrDefault("?")}",
            )
        }
        return outcome.capabilities
    }

    @Volatile
    var advancedCapabilities: TweakerAdvancedCapabilities? = null
        private set

    /**
     * Fired after [refreshProjectionForStandingRearm] has re-read authoritative truth.
     *
     * The standing-rearm watch is the only thing in the frontend that hears about a NEW daemon
     * session without the player being in the Utility panel -- it is what puts the profile back
     * after a restart. It refreshes [advancedCapabilities] here but never re-projected the
     * coordinator, so the fan re-arm, which lives on the projection, was never re-evaluated: on an Odin test device the watch ticked twelve times over two minutes without producing one fan
     * decision, and the fan stayed on vendor Auto. Set by the coordinator; last registration wins.
     */
    var onStandingRearmProjectionRefreshed: (() -> Unit)? = null

    /** Round-5 B6: logs a failing observation bind once per transition; see [TweakerObservationBindFailureLog]. */
    private val observationBindFailureLog = TweakerObservationBindFailureLog()

    @Volatile
    private var telemetry: TweakerTelemetry? = null

    /** Set from the last session assessment; see [tweakerSessionHeldByUnfinishedRestore]. */
    private var heldByUnfinishedRestore = false

    /**
     * Cached pair-admission facts. Package inspection is a synchronous Binder transaction, and the
     * Utility row reads availability during composition and on every resume — including the
     * return-from-game path. Refreshed on the IO side immediately before each bind attempt, and on the
     * package events MainActivity already watches, so the UI path only ever reads a field.
     */
    @Volatile
    private var cachedPairIdentity: TweakerPairIdentity = TweakerPairIdentity(TweakerPairState.UNCHECKED)

    @Volatile
    private var launchSelectionSnapshot: TweakerLaunchSelectionContext? = null
    private val preparedActiveProfileLock = Any()
    private var preparedActiveProfile: Pair<LaunchAttemptId, String>? = null

    /** When [preparedActiveProfile] was set, so a launch that never reaches its confirmation cannot lock observers out. */
    private var preparedActiveProfileAtMs = 0L

    /**
     * Standing re-arm state. One attempt at a time, with backoff, and a watch that exists only while
     * the player's standing choice is actually lost.
     *
     * Device-proven on Odin3 2026-08-10 (Mimir `abede8c4…`, Tweaker `847070d3…`): force-stopping the
     * Tweaker service while Mimir stays alive drops a standing profile to Stock in ~8s and it never
     * comes back, because [rearmStandingSystemProfile] was wired only to `confirmProcessStartOnce` and
     * Mimir's process never restarted. Killing Mimir instead recovers in ~4.4s, twice — the re-arm
     * itself is correct, it was simply unreachable from the failing case.
     */
    private val standingRearmInFlight = AtomicBoolean(false)
    @Volatile
    private var standingRearmFailures = 0
    @Volatile
    private var standingRearmNotBeforeElapsedMs = 0L
    private val standingRearmWatchLock = Any()
    private var standingRearmWatchJob: Job? = null

    /**
     * The re-arm [scheduleStandingRearm] dispatched last, whose end a display-rate APPLY waits for (O16). It is ended in
     * the `finally` of the job that runs it: after `mutateSystemProfile` has released the session reservation, after the
     * `rearm … exit=` line, and after the result is recorded. [inFlightStandingRearm] reads it.
     */
    @Volatile
    private var standingRearmFlight: TweakerStandingRearmFlight? = null

    /**
     * The discovered tuner's cold start, now that the daemon can remember a session (ruling R2).
     *
     * **The ordering is the whole design, so it is stated here rather than inferred.**
     *
     * - **The pair cannot bind** — no Tweaker installed, wrong signature, service missing — and the
     *   reconcile runs SYNCHRONOUSLY, on this thread, with no record store bound. That is
     *   byte-identical to what `MainActivity` did before this function existed, and it is the common
     *   case: discovery is the half that works on every handheld, with or without a daemon.
     * - **The pair can bind** and the widen WAITS for the daemon's answer. Widening first would make
     *   the record permanently moot: adoption asks *"are the CPUs still holding what was recorded?"*,
     *   and widening is precisely what stops them. So the safety action is deferred — never skipped —
     *   until the daemon has answered or failed to.
     * - **The daemon fails to answer** (bind timeout, old API, thrown transaction) and the store stays
     *   [NoTunerRecordStore], so `reconcileColdStart` reads `Unavailable`, widens, and forgets. A
     *   silent transport is never read as *"holds nothing"*.
     *
     * The deferral opens one window: an eligible device where the player moves a stepper before the
     * bind resolves captures the previous session's cap as stock. That is the pre-`releaseAnyOrphanedCap`
     * bug arriving by a different road, and `MimirTunerWrite.adoptRecordedSession` closes it by
     * preferring the daemon's stock, which was taken when nothing of ours was in force.
     */
    fun reconcileDiscoveredTunerColdStart() {
        // **Once per process, because `onCreate` is not once per process.** An Activity recreation --
        // a theme change, a configuration change, the database-recovery branch -- runs this again, and
        // a second pass would reconcile a session this process is already holding against a fresh bind:
        // a transient Unavailable would then be read against a store from the previous pass and could
        // forget a live session. The cold start is a process event, so it is guarded like one; the
        // bridge already treats process start this way in `confirmProcessStartOnce`.
        if (!discoveredColdStartDone.compareAndSet(false, true)) return
        val identity = inspectAndCachePairIdentity()
        // **Pair identity lands SYNCHRONOUSLY, before anything else, and that ordering is the whole
        // guard.** `MimirTunerDaemonRoute.presence` is null until this line, and null is REFUSE — never
        // write on a guess. Every millisecond this stays null is a millisecond in which a stepper press
        // is refused rather than routed, so it is set from the one fact available without a Binder round
        // trip: can this device bind a daemon at all. The API version is deliberately null here, which
        // keeps an eligible device on REFUSE until the daemon has actually answered; a device with NO
        // daemon is DIRECT immediately, which is today's behaviour and the half that works everywhere.
        updateDaemonRoutePresence(
            pairCanBind = identity.mayBind,
            apiVersion = null,
            profileSessionActive = null,
        )
        if (!identity.mayBind) {
            MimirTunerWrite.releaseAnyOrphanedCap()
            return
        }
        discoveredColdStartPending.set(true)
        ownerScope.launch {
            try {
                val atBind = readDiscoveredSessionAtBind()
                // **A v10 daemon owns the record, so it must not also be given the v9 one.** The v9
                // store exists because the write stayed in Mimir and the daemon only remembered what
                // it had written down; with the write itself routed, the daemon's own store IS the
                // record, and installing `DaemonTunerRecordStore` alongside it would leave two
                // accounts of one session that nothing reconciles. The decision, not a bare version
                // compare, so this branch and the writer's cannot drift: DAEMON means "the daemon has
                // the write and the record".
                if (MimirTunerDaemonRoute.decision() != MimirTunerRouteDecision.Daemon &&
                    atBind != MimirTunerRecordRead.Unavailable
                ) {
                    MimirTunerWrite.recordStore = DaemonTunerRecordStore(atBind) { snapshot ->
                        ownerScope.launch { submitDiscoveredSession(snapshot) }
                    }
                }
                MimirTunerWrite.releaseAnyOrphanedCap()
            } finally {
                // **Cleared and re-asked in the same breath, because of the window between them.** The
                // reconcile reads the route ONCE, at its top; if the daemon answers a millisecond
                // later the verdict is already `daemon_unresolved` and no further answer is guaranteed
                // to arrive -- that is the Odin 3 trace of 2026-09-11 exactly. Asking here closes the
                // window from the other side: by now the verdict is written down, so
                // [MimirTunerWrite.deferredDaemonRouteReconcileOwed] can judge it.
                discoveredColdStartPending.set(false)
                reconcileDaemonRouteDeferredIfOwed()
            }
        }
    }

    /** One bound round trip, at the only moment the cold start needs the daemon's answer. */
    private suspend fun readDiscoveredSessionAtBind(): MimirTunerRecordRead = withContext(Dispatchers.IO) {
        // **A READ takes the SETTLEMENT reservation, not the exclusive mutation one**; adoption failed before this change in a deterministic Thor run.
        //
        // This asked for `awaitExclusiveOwnerForMutation`, which is refused in ~1ms at cold start: the
        // transport is already claimed as an OBSERVATION by the bridge's own startup refresh, and the
        // MUTATION kind is requested with `mayJoinGenericOwner = false`, so it fails fast rather than
        // waiting. The read then returned `Unavailable`, no `DaemonTunerRecordStore` was ever installed
        // (`daemonStore=false` in every cold-start log, armed and unarmed), and the reconcile widened —
        // the pre-E2b behaviour. Adoption had therefore never fired on this device and could not have.
        //
        // Reading the daemon's record mutates nothing: it asks whether a session was recorded. It was
        // asking for the one ownership kind that cannot be shared, in order to do something that needs
        // no ownership at all. Settlement is the kind that MAY join a generic owner, which is exactly
        // the shape a read wants, and it is the same reservation the return path already uses to ask
        // the daemon a question while something else holds the transport.
        if (!sessionReservation.awaitOwnerForSettlement()) {
            return@withContext MimirTunerRecordRead.Unavailable
        }
        try {
            val pairIdentity = inspectAndCachePairIdentity()
            if (!executeTweakerBindAttempt(pairIdentity) { session.bind() }) {
                // A bind that failed says nothing about the daemon's API, so the version goes back to
                // null and the route to REFUSE. It does NOT say the pair cannot bind either — this
                // identity was inspected a line ago, so that half is fresh truth and is kept.
                updateDaemonRoutePresence(pairIdentity.mayBind, apiVersion = null, profileSessionActive = null)
                return@withContext MimirTunerRecordRead.Unavailable
            }
            val apiVersion = session.getApiVersion()
            if (apiVersion == null) {
                updateDaemonRoutePresence(pairIdentity.mayBind, apiVersion = null, profileSessionActive = null)
                return@withContext MimirTunerRecordRead.Unavailable
            }
            // **The session question is asked HERE, on this binding, and not from a second round trip.**
            // A ceiling and a named profile are mutually exclusive on the same node, so the route needs
            // both facts to be about the same moment; asking again later would let them describe two
            // different states of the daemon and give the writer a decision that was never true at once.
            //
            // This is also where the actuator is installed — [noteDaemonAnswered] does it, once the
            // version makes the decision DAEMON. Never before: an actuator reachable while the daemon
            // has not answered would be a route nobody established, which is the table's first row.
            noteDaemonAnswered(apiVersion, session.namedProfileSessionActiveV4(apiVersion))
            session.queryDiscoveredSessionV9(apiVersion).toTunerRecordRead()
        } finally {
            // **Release first, and unbind only if that release was the last one.** The reservation
            // depth stopped this read from handing the owner's RESERVATION away; the binding is the
            // other half of the same hazard and it needed the same answer. `session` is one instance
            // shared with `v4Owner` (see the `by lazy` pair at the top of this object), so when this
            // read joins `confirmProcessStartOnce`'s generic owner it is joining a connection that
            // owner is running round trips on — and `TweakerSession.unbind()` is not refcounted, it
            // abandons the connection for everybody. Unconditionally unbinding here tore the transport
            // down under the owner mid-query.
            //
            // The launch path already states this rule in its own words at `liveFpsOnOwnedTransport`:
            // *"a racing settlement may already own the transport; never unbind under it."* That site
            // asks the launch lifecycle, which cannot see a generic owner taken by process-start
            // confirmation; the reservation itself can, so this asks the reservation.
            if (sessionReservation.endOwner()) session.unbind()
        }
    }

    /**
     * Record or forget the discovered session; [snapshot] null means forget. Best effort by design.
     *
     * **The daemon's verdict is reported, not discarded.** The record family's whole safety property is
     * that a privileged process admits every value against its own hardware discovery rather than
     * trusting ours, so a refusal is the system working — but a refusal nobody logs turns into an
     * adoption that silently does not happen at the NEXT cold start, an hour and a reboot away from the
     * call that caused it. This is the one place the code and the reason are both still in hand.
     */
    private suspend fun submitDiscoveredSession(snapshot: MimirTunerSnapshot?) = withContext(Dispatchers.IO) {
        if (!sessionReservation.awaitExclusiveOwnerForMutation()) return@withContext
        try {
            val pairIdentity = inspectAndCachePairIdentity()
            if (!executeTweakerBindAttempt(pairIdentity) { session.bind() }) return@withContext
            try {
                val apiVersion = session.getApiVersion() ?: return@withContext
                if (snapshot == null) {
                    session.clearDiscoveredSessionV9(apiVersion)
                } else {
                    when (
                        val outcome = session.recordDiscoveredSessionV9(
                            apiVersion,
                            snapshot.toTweakerDiscoveredSession(),
                        )
                    ) {
                        TweakerDiscoveredSessionWrite.Admitted ->
                            Log.i(TAG_TUNER, "daemon recorded the discovered session")
                        is TweakerDiscoveredSessionWrite.Refused ->
                            Log.w(
                                TAG_TUNER,
                                "daemon refused the discovered session: " +
                                    "code=${outcome.code} detail=${outcome.detail}",
                            )
                        is TweakerDiscoveredSessionWrite.Unavailable ->
                            Log.w(TAG_TUNER, "daemon could not be asked to record: ${outcome.reason}")
                    }
                }
            } finally {
                session.unbind()
            }
        } finally {
            sessionReservation.endOwner()
        }
    }

    private val daemonRoutePresenceLock = Any()

    /**
     * The route's view of the daemon, moved to a new fact and logged exactly when it actually moves.
     *
     * **This is the only writer of [MimirTunerDaemonRoute.presence], and it is the reason the policy can
     * stay a pure function.** `mimirTunerRouteDecision` is a table over two inputs precisely so that no
     * call site re-derives it; that only holds if the inputs are maintained in one place, by code that
     * knows what it just learned. Four moments feed it: the synchronous cold start (pair identity), the
     * cold-start bind (version and session truth), every later projection refresh — the standing
     * `pair=… availability=…` line and the re-arm watch both come through there — and an actuator call
     * that could not reach the daemon.
     *
     * **Deduplicated, because an undeduplicated presence line would be the noisiest tag in the app.**
     * The projection refresh runs on a 15 s or 60 s cadence for as long as the re-arm watch is up, and
     * a line per tick saying nothing changed would bury the one line that says something did. Under a
     * lock so that two refreshes landing together cannot both decide they were the change.
     */
    private fun updateDaemonRoutePresence(
        pairCanBind: Boolean,
        apiVersion: Int?,
        profileSessionActive: Boolean?,
    ) {
        val next = MimirTunerDaemonPresence(
            pairCanBind = pairCanBind,
            apiVersion = apiVersion,
            profileSessionActive = profileSessionActive,
        )
        synchronized(daemonRoutePresenceLock) {
            if (MimirTunerDaemonRoute.presence == next) return
            MimirTunerDaemonRoute.presence = next
            Log.i(
                TAG_TUNER,
                "route presence pairCanBind=${next.pairCanBind} apiVersion=${next.apiVersion} " +
                    "profileActive=${next.profileSessionActive} decision=${MimirTunerDaemonRoute.decision()}",
            )
        }
    }

    /**
     * A daemon fact learned without a fresh package inspection.
     *
     * **Returns rather than inventing a pair fact when nothing is known yet.** Presence null is REFUSE
     * — *"the bridge has not looked"* — and a path that can say what the daemon answered but not whether
     * the pair is admissible must not be the one that resolves it. Only the cold start, which inspects
     * the package, may make that first statement; everything else refines it.
     */
    private fun updateDaemonRoutePresenceKeepingPair(apiVersion: Int?, profileSessionActive: Boolean?) {
        val current = MimirTunerDaemonRoute.presence ?: return
        updateDaemonRoutePresence(current.pairCanBind, apiVersion, profileSessionActive)
    }

    /**
     * The daemon just answered on a live binding, so the pair demonstrably binds.
     *
     * `pairCanBind = true` here is measured, not cached: a completed round trip is stronger evidence
     * than any package inspection, and it is specifically stronger than a `cachedPairIdentity` that
     * [invalidateCachedPairIdentity] has just reset to UNCHECKED on a package event. Reading the cache
     * here would have let an unrelated Tweaker update flip a live, answering daemon to DIRECT — the
     * two-writer state, arrived at by bookkeeping.
     */
    private fun noteDaemonAnswered(apiVersion: Int, profileSessionActive: Boolean?) {
        daemonAnsweredInProcess = true
        updateDaemonRoutePresence(
            pairCanBind = true,
            apiVersion = apiVersion,
            profileSessionActive = profileSessionActive,
        )
        // **The actuator is installed wherever the version is learned, not only at the cold-start
        // bind.** A device whose daemon was busy, restarting or not yet up at process start would
        // otherwise reach `decision() == Daemon` on a later refresh with `actuator == null`: a route
        // that says "the daemon owns this" and nothing to ask. One installer, keyed to the same
        // decision the writer consults, makes that state unreachable.
        //
        // It is never uninstalled. A daemon that goes away moves the DECISION, which is what gates
        // every use of this; clearing the field as well would add a race against a call in flight and
        // buy nothing.
        if (MimirTunerDaemonRoute.decision() == MimirTunerRouteDecision.Daemon) {
            MimirTunerDaemonRoute.actuator = daemonTunerActuator
            reconcileDaemonRouteDeferredIfOwed()
        }
    }

    /**
     * The recovery for a cold start that ended before the route did (F1, Odin 3, 2026-09-11).
     *
     * ```
     * 12:33:10.801  cold start reconcile: branch=daemon_unresolved daemonStore=false route=REFUSE
     * 12:33:11.068  route presence pairCanBind=true apiVersion=10 profileActive=false decision=Daemon
     * ```
     *
     * The projection refresh answered 267 ms after the reconcile had already decided it could not know
     * who owned the node. Nothing re-asked, so a ceiling the daemon was holding stayed invisible to
     * this process for the life of it. [MimirTunerWrite.reconcileDaemonRouteDeferred] re-runs exactly
     * the cold start's daemon query, once per process; this is its only trigger, and it fires from the
     * two moments that can close that window -- a daemon answer, and the end of the cold start itself.
     *
     * **Launched, never called inline, and that is not tidiness.** Both call sites can be inside a live
     * Binder round trip on the shared session -- [noteDaemonAnswered] is reached from the actuator's
     * own `query`/`mutate` -- and the deferred reconcile takes the settlement reservation for a fresh
     * one. Running it here would be re-entering the transport from inside itself, on the main thread's
     * caller in the `refreshProjectionFromBoundSession` case. `ownerScope` is `Dispatchers.IO` with the
     * supervisor and the logging handler, which is the same contract every other background probe in
     * this file runs under.
     *
     * The owed check is asked twice on purpose -- cheaply here, authoritatively (with the once-flag CAS)
     * inside the writer -- so that the standing 15 s projection refresh does not launch a coroutine per
     * tick for the answer "nothing to do".
     */
    private fun reconcileDaemonRouteDeferredIfOwed() {
        if (discoveredColdStartPending.get()) return
        if (!MimirTunerWrite.deferredDaemonRouteReconcileOwed()) return
        ownerScope.launch { MimirTunerWrite.reconcileDaemonRouteDeferred() }
    }

    /**
     * The ruled shape, implemented: the frontend asks and the daemon writes .
     *
     * **Every call is one bounded attempt and a typed outcome — no timer, no retry loop, no second
     * bind.** A retry here would be a second opinion about hardware that a privileged process has
     * already given its first, and the failure it would paper over (a busy transport, a daemon
     * mid-restore) is exactly the state the route must REFUSE in rather than push through. The caller
     * re-asks by pressing again.
     *
     * **The reservation kinds are not interchangeable and are copied from the two call sites that
     * proved them.** `apply`/`withdraw` mutate, so they take the exclusive mutation reservation and the
     * finally-shape of [submitDiscoveredSession]: release the binding inside, the reservation outside.
     * `query` reads, so it takes the settlement reservation and the finally-shape of
     * [readDiscoveredSessionAtBind] — *release first, and unbind only if that release was the last one*
     * — because a settlement MAY join a generic owner and `TweakerSession.unbind()` is not refcounted.
     * Asking for MUTATION on a read is what made adoption never fire on the Thor; unbinding under a
     * joined owner is what the depth repair exists for. Three reverted attempts on 2026-09-01 bought
     * those two sentences.
     */
    private val daemonTunerActuator: DaemonTunerActuator = object : DaemonTunerActuator {

        override fun apply(
            kind: MimirTunerPolicy.Kind,
            policyId: Int,
            ceilingKhz: Int,
        ): DaemonCeilingOutcome = mutate("apply") { apiVersion ->
            session.applyDiscoveredCeilingV10(
                negotiatedApiVersion = apiVersion,
                axis = mimirTunerCeilingAxis(kind),
                policyId = policyId,
                ceilingKhz = ceilingKhz,
            ).toDaemonCeilingOutcome(askedKind = kind, askedPolicyId = policyId, askedKhz = ceilingKhz)
        }

        override fun withdraw(): DaemonCeilingOutcome = mutate("withdraw") { apiVersion ->
            session.withdrawDiscoveredCeilingV10(apiVersion).toDaemonCeilingOutcome()
        }

        override fun query(): DaemonCeilingOutcome = runBlocking {
            if (!sessionReservation.awaitOwnerForSettlement()) {
                return@runBlocking report("query", DaemonCeilingOutcome.Unavailable(MimirTunerRefuseDaemonBusy))
            }
            try {
                val pairIdentity = inspectAndCachePairIdentity()
                if (!executeTweakerBindAttempt(pairIdentity) { session.bind() }) {
                    updateDaemonRoutePresence(pairIdentity.mayBind, null, null)
                    return@runBlocking report("query", DaemonCeilingOutcome.Unavailable("Tweaker bind attempt failed"))
                }
                val apiVersion = session.getApiVersion()
                if (apiVersion == null) {
                    updateDaemonRoutePresence(pairIdentity.mayBind, null, null)
                    return@runBlocking report("query", DaemonCeilingOutcome.Unavailable("Tweaker named no API version"))
                }
                noteDaemonAnswered(apiVersion, MimirTunerDaemonRoute.presence?.profileSessionActive)
                report("query", session.queryDiscoveredCeilingV10(apiVersion).toDaemonCeilingOutcome())
            } finally {
                // The settlement rule, verbatim from [readDiscoveredSessionAtBind]: release first, and
                // unbind only if that release was the last one. This read can join a generic owner that
                // is running its own round trips on the same `session` instance.
                if (sessionReservation.endOwner()) session.unbind()
            }
        }

        /** The exclusive-mutation shape, from [submitDiscoveredSession]. */
        private fun mutate(
            call: String,
            body: (apiVersion: Int) -> DaemonCeilingOutcome,
        ): DaemonCeilingOutcome = runBlocking {
            if (!sessionReservation.awaitExclusiveOwnerForMutation()) {
                return@runBlocking report(call, DaemonCeilingOutcome.Unavailable(MimirTunerRefuseDaemonBusy))
            }
            try {
                val pairIdentity = inspectAndCachePairIdentity()
                if (!executeTweakerBindAttempt(pairIdentity) { session.bind() }) {
                    updateDaemonRoutePresence(pairIdentity.mayBind, null, null)
                    return@runBlocking report(call, DaemonCeilingOutcome.Unavailable("Tweaker bind attempt failed"))
                }
                try {
                    val apiVersion = session.getApiVersion()
                    if (apiVersion == null) {
                        updateDaemonRoutePresence(pairIdentity.mayBind, null, null)
                        return@runBlocking report(call, DaemonCeilingOutcome.Unavailable("Tweaker named no API version"))
                    }
                    noteDaemonAnswered(apiVersion, MimirTunerDaemonRoute.presence?.profileSessionActive)
                    report(call, body(apiVersion))
                } finally {
                    session.unbind()
                }
            } finally {
                sessionReservation.endOwner()
            }
        }

        /**
         * One exit for every outcome: log it, retire the memo it invalidates, and correct the route.
         *
         * The memoised ceiling is dropped after an APPLIED or a WITHDRAWN because the node has just
         * moved and [DiscoveredCeilingValidityMs] would otherwise let the row keep showing the previous
         * frequency for up to a second after the press that changed it.
         *
         * An UNAVAILABLE sets the API version back to null, which is REFUSE — never DIRECT. That is the
         * ruling's sharpest edge: when a daemon exists but cannot be reached, writing around it
         * recreates the two-writer state the whole route exists to end. The next projection refresh
         * restores the version as soon as the daemon answers again.
         */
        private fun report(call: String, outcome: DaemonCeilingOutcome): DaemonCeilingOutcome {
            Log.i(TAG_TUNER, daemonCeilingOutcomeLogLine(call, outcome))
            when (outcome) {
                is DaemonCeilingOutcome.Applied, is DaemonCeilingOutcome.Withdrawn ->
                    invalidateDiscoveredCeiling()
                is DaemonCeilingOutcome.Unavailable ->
                    updateDaemonRoutePresenceKeepingPair(apiVersion = null, profileSessionActive = null)
                else -> Unit
            }
            return outcome
        }
    }

    /**
     * The discovered tuner's tag, deliberately shared with `MimirTunerWrite`.
     *
     * The record's life crosses two files and one Binder, and splitting it across two logcat tags would
     * mean an operator watching the axis had to know which half of the code emitted which half of the
     * story. One `-s MimirTuner` shows the whole thing in order.
     */
    private const val TAG_TUNER = "MimirTuner"

    /**
     * Lane mem (2026-10-14): the context half of [initialize], on its own so a process start recovering from the
     * lowmemorykiller can serve every reader of [appContext] at once while the bind itself waits
     * (MimirLowMemoryRecovery). Idempotent; [initialize] calls it first.
     */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun initialize(context: Context) {
        attach(context)
        ownerScope.launch { discardLegacyGlobalGameFpsSlot() }
        if (sessionReservation.tryBeginOwner()) {
            val started = v4LaunchLifecycle.confirmProcessStartOnce(
                validatePair = { inspectAndCachePairIdentity().mayBind },
                // Capability truth first, then re-state the player's standing choice. Guardian restores
                // exact Stock when the owning process dies, so without this a swipe-away silently ends
                // a system-wide profile the player set deliberately.
                onFinished = {
                    ownerScope.launch {
                        // refreshCapabilities() runs the projection hook, which already schedules a
                        // re-arm when truth says the choice was lost. Going through the same
                        // single-flight gate rather than calling rearmStandingSystemProfile()
                        // directly means process start cannot collide with its own observation and
                        // charge the loser's Busy to the backoff counter.
                        refreshCapabilities()
                        scheduleStandingRearm()
                    }
                },
            )
            if (!started) sessionReservation.endOwner()
        }
    }

    fun isTweakerPackageEvent(packageName: String?): Boolean =
        packageName == TweakerSession.TWEAKER_PACKAGE

    /** Immediately retires stale presentation truth while the exact-package refresh moves to IO. */
    fun invalidateCachedPairIdentity() {
        cachedPairIdentity = TweakerPairIdentity(TweakerPairState.UNCHECKED)
        daemonAnsweredInProcess = false
        // A package event is authoritative: it retires capabilities whatever timed out before it.
        unansweredQueriesAtLastCapabilitiesWrite = unansweredQueryCountOrZero()
        capabilities = null
        advancedCapabilities = null
        telemetry = null
        // Retiring capability truth retires the profile display with it: with no capabilities the row
        // reads UNAVAILABLE, and a surface must not keep naming a profile nothing can confirm.
        displayedProfileId = STOCK_PROFILE
    }

    fun selectedProfileId(): String =
        preferences.getString(KEY_PROFILE, DEFAULT_PROFILE).orEmpty().ifBlank { DEFAULT_PROFILE }

    /**
     * What the device is actually running, for anything that *displays* profile state.
     *
     * [selectedProfileId] is the stored pick, and it goes stale in one specific, misleading direction:
     * `normalizeStoredSelection` bails whenever `capabilities` is null, so when the Tweaker service dies
     * the pref keeps naming a profile the hardware is no longer running. Device-proven 2026-08-10 — with
     * `balanced` dropped to Stock, `KEY_PROFILE` still read `balanced` while the projection logged
     * `selected=stock`, and the performance overlay went on showing the Balanced glyph over Stock silicon.
     *
     * This publishes [TweakerUtilityRowState.effectiveProfileId] instead — one definition, shared with the
     * Utility row and the re-arm watcher — which collapses to Stock unless truth is READY *and* the pick
     * is still certified. It is refreshed wherever authoritative truth is, and its inputs change only
     * there, so re-deriving it per sample would buy nothing.
     *
     * Defaults to Stock before the first observation: claiming no profile until truth is read is honest,
     * claiming one that may not be applied is the defect.
     */
    fun displayedSystemProfileId(): String = if (capabilityTruthUnknown) STOCK_PROFILE else displayedProfileId

    @Volatile
    private var displayedProfileId: String = STOCK_PROFILE

    /** Durable per-package frame-limit intent. A cap stored for one package never answers for another. */
    internal fun selectedGameFpsPolicy(packageName: String): TweakerGameFpsPolicy {
        val key = gameFpsPolicyPreferenceKey(packageName) ?: return InertGameFpsPolicy
        return preferences.getString(key, null)?.let(::decodeTweakerGameFpsPolicy) ?: InertGameFpsPolicy
    }

    fun selectedGameFpsLimitHz(packageName: String): Int? =
        selectedGameFpsPolicy(packageName).frameLimitHz

    fun gameFpsLimitOptionsHz(): List<Int> = advancedCapabilities?.frameLimitsHz.orEmpty()

    /**
     * The game currently in front, attested rather than remembered.
     *
     * A frame cap is written against one package, so the row that sets it must never act on a stale
     * subject -- capping whatever launched last, or Mimir itself, is exactly the confusion the
     * retired app-wide slot caused. Null whenever that cannot be established honestly.
     */
    fun attestedForegroundGamePackage(): String? {
        return attestedLiveFpsForegroundPackage(
            telemetry = runCatching { session.getTelemetry() }.getOrNull(),
            nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            selfPackageName = appContext.packageName,
        )
    }

    @Synchronized
    internal fun persistGameFpsLimitHz(
        packageName: String,
        frameLimitHz: Int?,
        admittedOptionsHz: List<Int> = gameFpsLimitOptionsHz(),
    ): Boolean {
        val key = gameFpsPolicyPreferenceKey(packageName) ?: return false
        if (frameLimitHz != null && frameLimitHz !in admittedOptionsHz) return false
        val current = selectedGameFpsPolicy(packageName)
        if (current.frameLimitHz == frameLimitHz) return true
        val next = nextTweakerGameFpsPolicy(current, frameLimitHz) ?: return false
        return preferences.edit().putString(key, encodeTweakerGameFpsPolicy(next)).commit()
    }

    /**
     * The retired app-wide slot predates package identity: its value names no game, so promoting it
     * onto whichever package launches next would silently cap an unrelated game. Migration is
     * therefore an explicit discard — remove the slot, attribute the stored value to nothing. Reads
     * are structurally immune either way: per-package keys share no name with the legacy slot.
     */
    internal fun discardLegacyGlobalGameFpsSlot() {
        if (preferences.contains(KEY_LEGACY_GAME_FPS_LIMIT_HZ) ||
            preferences.contains(KEY_LEGACY_GAME_FPS_POLICY_REVISION)
        ) {
            preferences.edit()
                .remove(KEY_LEGACY_GAME_FPS_LIMIT_HZ)
                .remove(KEY_LEGACY_GAME_FPS_POLICY_REVISION)
                .commit()
        }
    }

    fun selectableProfileIds(): List<String> =
        capabilities?.let(::effectiveSelectableProfileIds) ?: listOf(STOCK_PROFILE)

    /** Last known installed truth derived from pair admission; null until the first off-thread read. Never an IPC. */
    fun isTweakerInstalled(): Boolean? = cachedPairIdentity.installedTruth

    /** Last package-contract admission result for actionable, IPC-free release copy. */
    fun pairState(): TweakerPairState = cachedPairIdentity.state

    /**
     * The kernel's own answer, memoised for [DiscoveredCeilingValidityMs].
     *
     * [utilityRowState] is called at discrete refresh points rather than per frame, so a sysfs read here
     * stays on that cadence. The memo exists for the OTHER callers: the launch path asks for this row on
     * every game start, and a burst of asks should cost one read, not four.
     *
     * Invalidated explicitly after a write, because a ceiling that was just changed is exactly when a
     * stale reading would be most visible. `MimirTunerWrite` re-reads the node anyway; this makes the
     * row agree with it.
     */
    @Volatile
    private var discoveredCeilingMemo: TweakerDiscoveredCeiling? = null

    @Volatile
    private var discoveredCeilingAtElapsedRealtimeMs: Long = 0L

    private const val DiscoveredCeilingValidityMs = 1_000L

    /** Drop the memo so the next row read asks the kernel again. Call after any ceiling write. */
    fun invalidateDiscoveredCeiling() {
        discoveredCeilingAtElapsedRealtimeMs = 0L
    }

    private fun discoveredCeiling(): TweakerDiscoveredCeiling? {
        val now = SystemClock.elapsedRealtime()
        val age = now - discoveredCeilingAtElapsedRealtimeMs
        if (discoveredCeilingAtElapsedRealtimeMs != 0L && age in 0 until DiscoveredCeilingValidityMs) {
            return discoveredCeilingMemo
        }
        val fresh = runCatching { discoveredCeilingRow(readTunerAvailability()) }.getOrNull()
        discoveredCeilingMemo = fresh
        discoveredCeilingAtElapsedRealtimeMs = now
        return fresh
    }

    /** The single truthful projection the always-present Utility row renders from. */
    fun utilityRowState(): TweakerUtilityRowState {
        val observedCapabilities = capabilities
        val selectedProfile = selectedProfileId()
        val qualificationTestMode = observedCapabilities?.let {
            includeLabProfilesInUtility(it, BuildConfig.DEBUG)
        } == true
        val discovered = discoveredCeiling()
        return TweakerUtilityRowState(
            availability = tweakerUtilityAvailability(
                installed = cachedPairIdentity.installedTruth,
                capabilitiesUnknown = capabilityTruthUnknown,
                capabilities = observedCapabilities,
                includeDiagnosticProfiles = qualificationTestMode,
                discovered = discovered,
            ),
            discovered = discovered,
            selectedProfileId = selectedProfile,
            selectableProfileIds = selectableProfileIds(),
            qualificationTestMode = qualificationTestMode,
            heldByUnfinishedRestore = heldByUnfinishedRestore,
            // Retained capabilities with an unanswered last ask: the list stays, the claim of a profile does not.
            answerPending = capabilityTruthUnknown && observedCapabilities != null,
            fanControl = freshUtilityFanControl(
                telemetry = telemetry,
                selectedProfileId = selectedProfile,
                nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            ),
        )
    }

    /**
     * Live GPU temperature for the Utility PROFILE row's fan readout — see
     * [freshUtilityFanGpuTempC] for why GPU and not package/CPU temperature. Unlike
     * [utilityRowState]'s `fanControl`, this is not gated on a selected profile: the row shows it
     * only alongside an already-present [TweakerFanControlReadout], so the caller's own null check
     * on that readout is what decides whether the temperature is ever rendered.
     */
    fun utilityFanGpuTempC(): Int? =
        freshUtilityFanGpuTempC(telemetry, SystemClock.elapsedRealtime())

    /**
     * Live CPU temperature for the Utility fan-control section's temperature readout — see
     * [freshUtilityFanCpuTempC] for why hottest-core and not the package mean. Same freshness gate
     * and same "not gated on a selected profile" contract as [utilityFanGpuTempC]; the fan curve
     * readout's own null-check on each field decides whether it renders.
     */
    fun utilityFanCpuTempC(): Int? =
        freshUtilityFanCpuTempC(telemetry, SystemClock.elapsedRealtime())

    /** Pure Utility intent. Persistence follows only authoritative v5 service truth. */
    fun stepProfile(direction: Int): TweakerUtilityStepOutcome =
        utilityRowState().stepOutcome(direction)

    /**
     * Applies one explicit system-wide profile selection through the existing sole Binder owner.
     *
     * This intentionally adds no second lifecycle controller: the service already owns Guardian,
     * qualification, Stock-first switching, readback, owner death, and the non-renewable hard lease.
     */
    internal suspend fun selectSystemProfile(
        profileId: String,
        admittedProfileIds: Set<String>,
        bindAndSelect: suspend (String) -> TweakerSystemProfileSelectionResult =
            ::selectSystemProfileAfterRenderedAdmission,
    ): TweakerSystemProfileSelectionResult = withContext(Dispatchers.IO) {
        val requested = profileId.trim().lowercase()
        if (requested !in admittedProfileIds) {
            TweakerSystemProfileSelectionResult.Unavailable
        } else {
            // The rendered READY row admits exactly this press. A concurrent observation may update the
            // global projection, but it must not revoke an intent the player has already issued. The
            // bound helper is re-read below and remains the authoritative mutation gate.
            bindAndSelect(requested)
        }
    }

    /**
     * The cancellable UI path never interrupts a synchronous Binder transaction. Back atomically wins
     * either before commit or before final settlement; the same bound owner then restores the exact
     * authoritative profile observed before mutation.
     */
    internal suspend fun selectSystemProfileSettled(
        profileId: String,
        admittedProfileIds: Set<String>,
        control: TweakerProfileSelectionControl,
    ): TweakerSystemProfileSelectionResult = withContext(Dispatchers.IO) {
        val requested = profileId.trim().lowercase()
        if (requested !in admittedProfileIds) {
            TweakerSystemProfileSelectionResult.Unavailable
        } else {
            selectSystemProfileAfterRenderedAdmission(requested, control)
        }
    }

    /**
     * The one real mutation path both public entry points share, wrapped so every terminal result --
     * from any of its many returns -- records the player's standing intent exactly once. A test that
     * injects its own `bindAndSelect` replaces this entirely and correctly records nothing.
     */
    private suspend fun selectSystemProfileAfterRenderedAdmission(
        requested: String,
        control: TweakerProfileSelectionControl? = null,
        standingRearm: Boolean = false,
    ): TweakerSystemProfileSelectionResult =
        mutateSystemProfile(requested, control, standingRearm).also(::recordStandingSelection)

    private suspend fun mutateSystemProfile(
        requested: String,
        control: TweakerProfileSelectionControl?,
        standingRearm: Boolean = false,
    ): TweakerSystemProfileSelectionResult {
        if (v4LaunchLifecycle.hasPendingLaunch() || v4LaunchLifecycle.mayOwnTransport()) {
            Log.i(TAG_BRIDGE, "select requested=$requested busy site=launch-before-reservation")
            return TweakerSystemProfileSelectionResult.Busy
        }
        // A long-lived telemetry observer yields its one binding to this explicit mutation.
        val reservationAskedAtMs = SystemClock.elapsedRealtime()
        if (!reserveExclusiveOwnerForSystemProfile(standingRearm)) {
            // Which refusal this was, so a Busy on device is attributable to its holder instead of inferred from timing.
            Log.i(
                TAG_BRIDGE,
                "select requested=$requested busy site=reservation waitedMs=" +
                    "${SystemClock.elapsedRealtime() - reservationAskedAtMs} refusedBy=[${sessionReservation.describe()}]",
            )
            return TweakerSystemProfileSelectionResult.Busy
        }
        try {
            if (v4LaunchLifecycle.hasPendingLaunch() || v4LaunchLifecycle.mayOwnTransport()) {
                Log.i(TAG_BRIDGE, "select requested=$requested busy site=launch-after-reservation")
                return TweakerSystemProfileSelectionResult.Busy
            }
            val pairIdentity = inspectAndCachePairIdentity()
            if (!executeTweakerBindAttempt(pairIdentity) { session.bind() }) {
                capabilitiesAfterFailedBind(pairIdentity)
                return TweakerSystemProfileSelectionResult.Unavailable
            }
            try {
                val apiVersion = session.getApiVersion()
                    ?: return TweakerSystemProfileSelectionResult.Unavailable
                if (apiVersion < TWEAKER_V5_SYSTEM_SCOPE_MIN_API_VERSION) {
                    val assessment = session.querySessionV4(apiVersion)
                    capabilities = failClosedCapabilitiesForSystemSelectionDowngrade(
                        apiVersion = apiVersion,
                        assessment = assessment,
                        readCapabilities = session::getCapabilities,
                    )
                    reconcileStoredSelection(assessment)
                    normalizeStoredSelection()
                    return TweakerSystemProfileSelectionResult.UpgradeRequired
                }

                val before = session.querySessionV4(apiVersion)
                if (before is TweakerV4SessionAssessment.Uncertain) {
                    return TweakerSystemProfileSelectionResult.Uncertain(before.reason)
                }
                val boundCapabilities = authoritativeV4Capabilities(before, session::getCapabilities)
                capabilities = boundCapabilities
                reconcileStoredSelection(before)
                val priorProfileId = authoritativeProfileId(before)
                    ?: return TweakerSystemProfileSelectionResult.Uncertain(
                        "Profile truth was not stable before selection",
                    )
                if (requested !in boundCapabilities?.let(::effectiveSelectableProfileIds).orEmpty()) {
                    normalizeStoredSelection()
                    return TweakerSystemProfileSelectionResult.Unavailable
                }
                if (control?.cancellationRequested == true) {
                    control.markSettled()
                    return TweakerSystemProfileSelectionResult.Cancelled(priorProfileId)
                }
                if (requested != STOCK_PROFILE && before is TweakerV4SessionAssessment.Stock) {
                    if (!session.beginActiveSessionV4Async()) {
                        return TweakerSystemProfileSelectionResult.Unavailable
                    }
                }
                val result = when (
                    val selection = selectSystemProfileWithAdmissionRetry(
                        apiVersion = apiVersion,
                        profileId = requested,
                        control = control,
                    )
                ) {
                    is CancellableProfileSelectionAttempt.Completed -> selection.value
                    CancellableProfileSelectionAttempt.CancelledBeforeMutation -> {
                        control?.markSettled()
                        return TweakerSystemProfileSelectionResult.Cancelled(priorProfileId)
                    }
                    CancellableProfileSelectionAttempt.Unavailable ->
                        return TweakerSystemProfileSelectionResult.Unavailable
                }
                if (control != null && !control.claimUncancelledSettlement()) {
                    val restored = restoreSystemProfileAfterCancellation(
                        apiVersion = apiVersion,
                        priorProfileId = priorProfileId,
                    )
                    control.markSettled()
                    telemetry = session.getTelemetry()
                    return restored
                }
                reconcileAfterSystemSelection(apiVersion, result)
                advancedCapabilities = session.getAdvancedCapabilitiesV6(apiVersion)
                telemetry = session.getTelemetry()
                control?.markSettled()
                return result.toAppSelectionResult()
            } finally {
                session.unbind()
            }
        } finally {
            sessionReservation.endOwner()
        }
    }

    /**
     * The exclusive reservation for one system-profile selection. A player's press asks once, as it always has; the
     * standing re-arm waits out a refusal ([awaitExclusiveOwnerForStandingRearm]) and logs what refused it.
     */
    private suspend fun reserveExclusiveOwnerForSystemProfile(standingRearm: Boolean): Boolean {
        if (!standingRearm) return sessionReservation.awaitExclusiveOwnerForMutation()
        val ask = awaitExclusiveOwnerForStandingRearm(
            reservation = sessionReservation,
            launchOwnsTransport = { v4LaunchLifecycle.hasPendingLaunch() || v4LaunchLifecycle.mayOwnTransport() },
        )
        ask.refusedBy?.let { holder ->
            // The line round 5c lacked: the reservation's state when it refused the re-arm, and what waiting got.
            Log.i(TAG_BRIDGE, "rearm reservation refusedBy=[$holder] acquired=${ask.acquired} waitedMs=${ask.waitedMs}")
        }
        return ask.acquired
    }

    private suspend fun restoreSystemProfileAfterCancellation(
        apiVersion: Int,
        priorProfileId: String,
    ): TweakerSystemProfileSelectionResult {
        val beforeRestore = session.querySessionV4(apiVersion)
        if (priorProfileId != STOCK_PROFILE && beforeRestore is TweakerV4SessionAssessment.Stock) {
            if (!session.beginActiveSessionV4Async()) {
                reconcileStoredSelection(beforeRestore)
                return TweakerSystemProfileSelectionResult.Uncertain(
                    "Prior profile could not be re-admitted after cancellation",
                )
            }
        }
        selectSystemProfileWithAdmissionRetry(apiVersion, priorProfileId)
        val finalAssessment = session.querySessionV4(apiVersion)
        capabilities = authoritativeV4Capabilities(finalAssessment, session::getCapabilities)
        reconcileStoredSelection(finalAssessment)
        val finalProfileId = authoritativeProfileId(finalAssessment)
        val priorProfileStillAdmitted = priorProfileId == STOCK_PROFILE ||
            capabilities?.let(::effectiveSelectableProfileIds)?.contains(priorProfileId) == true
        return cancelledSystemProfileSelectionResult(
            priorProfileId = priorProfileId,
            finalProfileId = finalProfileId,
            priorProfileStillAdmitted = priorProfileStillAdmitted,
        )
    }

    /**
     * Android acknowledges startForegroundService before the helper's main thread necessarily receives
     * onStartCommand. Retry only that one explicit admission race; every other rejection is terminal.
     */
    private suspend fun selectSystemProfileWithAdmissionRetry(
        apiVersion: Int,
        profileId: String,
        control: TweakerProfileSelectionControl? = null,
    ): CancellableProfileSelectionAttempt<TweakerV5SystemSelectionAssessment> =
        cancellableProfileSelectionAttempts(
            attemptCount = SYSTEM_SELECTION_ADMISSION_ATTEMPTS,
            control = control,
            select = { attemptIndex ->
                val attempt = session.createSystemSelectionAttemptV5(
                    profileId = profileId,
                    timeoutMs = SYSTEM_SELECTION_TIMEOUT_MS,
                ) ?: return@cancellableProfileSelectionAttempts null
                session.selectSystemProfileV5(apiVersion, attempt)
            },
            shouldRetry = ::shouldRetrySystemProfileAdmission,
            waitBeforeRetry = { attemptIndex ->
                delay(SYSTEM_SELECTION_ADMISSION_RETRY_MS * (attemptIndex + 1L))
            },
        )

    suspend fun refreshCapabilities(): TweakerCapabilities? = withContext(Dispatchers.IO) {
        if (v4LaunchLifecycle.hasPendingLaunch() || v4LaunchLifecycle.mayOwnTransport() ||
            !sessionReservation.tryBeginObservation()
        ) {
            // The reservation is held elsewhere -- most durably by PerformanceOverlayService's
            // long-lived TweakerTelemetryObservation (see openTelemetryObservation), which binds
            // once and does not release the reservation for as long as the overlay stays enabled.
            // Whoever holds it already keeps the session bound, so a plain telemetry read can
            // piggyback on that binding without touching the reservation: getTelemetry() is
            // independently deadline-bounded on its own executor (TweakerSession.getTelemetry) and
            // this call neither binds/unbinds nor mutates capabilities/selection state, so it
            // cannot race the reservation holder's own work. Without this, the Utility/Tweaker
            // panel's periodic refresh (AppBrightnessCoordinator's UTILITY_TWEAKER_TELEMETRY_REFRESH_MS
            // loop) would silently no-op here every cycle, `telemetry` would never advance past
            // whatever it was before the overlay grabbed the reservation, and the fan readout's
            // retention would then hold that one stale reading forever -- frozen, never blinking,
            // and never live.
            refreshTelemetryIfBound()
            return@withContext adoptCapabilitiesFromHeldBindingIfNeverRead()
        }
        try {
            refreshCapabilitiesWhileObserved()
        } finally {
            sessionReservation.endObservation()
        }
    }

    /**
     * When the last FULL projection (version, session, capabilities, advanced controls) was read off a bound session.
     * Zero until the first.
     */
    @Volatile
    private var lastFullProjectionAtMs = 0L

    /**
     * The Utility panel's once-a-second beat. The live fan and temperature readout wants a fresh telemetry sample each
     * second; the full projection behind [refreshCapabilities] (API version, V4 session, capabilities, advanced controls,
     * the pair inspection, a standing-rearm observation and two log lines) does not change that often, and on the Nova it
     * ran every second while Utility was open (21:49:34-21:49:51, one `rearmwatch observe` a second, a service bind and
     * unbind each time). So this reads telemetry alone while the last projection is fresh and the truth is answered, and
     * does the full projection when it is due, when nothing has been read, or when the last ask went unanswered.
     */
    suspend fun refreshForPanelTick(): TweakerCapabilities? = withContext(Dispatchers.IO) {
        val projectionDue = tweakerPanelProjectionDue(
            capabilitiesPresent = capabilities != null,
            truthUnknown = capabilityTruthUnknown,
            pairMayBind = cachedPairIdentity.mayBind,
            sinceFullProjectionMs = SystemClock.elapsedRealtime() - lastFullProjectionAtMs,
            intervalMs = PANEL_FULL_PROJECTION_INTERVAL_MS,
        )
        if (projectionDue) return@withContext refreshCapabilities()
        if (launchHoldsObservers() || v4LaunchLifecycle.mayOwnTransport() || !sessionReservation.tryBeginObservation()) {
            // Someone holds the token and its binding: read telemetry off that binding, as the full refresh does.
            refreshTelemetryIfBound()
            return@withContext capabilities
        }
        var bound = false
        try {
            bound = executeTweakerBindAttempt(cachedPairIdentity) { session.bind() }
            // An explicit mutation parked behind this beat has priority over its telemetry read: skip the read, release.
            if (bound && !sessionReservation.shouldYieldObservation()) session.getTelemetry()?.let { telemetry = it }
        } finally {
            if (bound) session.unbind()
            sessionReservation.endObservation()
        }
        // A bind that failed is a question for the full refresh, which classifies it.
        if (bound) capabilities else refreshCapabilities()
    }

    /**
     * Best-effort, reservation-free telemetry refresh for a session someone else already bound.
     * Never binds/unbinds and never touches `capabilities`/`advancedCapabilities` -- see the call
     * site in [refreshCapabilities] for why this is safe to run without the reservation.
     */
    private fun refreshTelemetryIfBound() {
        if (!session.isBound) return
        session.getTelemetry()?.let { telemetry = it }
    }

    /**
     * Recovery for the one ordering that leaves the whole Tweaker surface permanently UNAVAILABLE.
     *
     * [refreshCapabilities] gives up and returns the cache whenever the reservation is held
     * elsewhere. That is right for a periodic telemetry poll, and wrong exactly once: if the holder
     * takes the reservation BEFORE capabilities have ever been read, the cache is null, every later
     * refresh short-circuits on the same null, and nothing can ever populate it. The holder named at
     * that call site — PerformanceOverlayService's telemetry observation — "binds once and does not
     * release the reservation for as long as the overlay stays enabled", so on a fresh Mimir process
     * with the overlay on, the Tweaker row reports UNAVAILABLE for the life of the process.
     *
     * Observed on the Odin 3, 2026-08-10: `pair=ELIGIBLE availability=UNAVAILABLE profiles=[stock]`
     * held across relaunches and survived restarting Mimir; only force-stopping the Tweaker service
     * — which dropped the binding and with it the holder's observation — let the next refresh read
     * capabilities and reach `availability=READY profiles=[stock, quiet, balanced, performance]`.
     * Every certified profile had disappeared from the surface, with nothing in the UI to explain it.
     *
     * So: read the projection off the binding the holder already established. This is the same
     * piggyback the telemetry refresh above is justified by, and [refreshProjectionFromBoundSession]
     * is documented for exactly this — it is read-only against the service and, critically, does NOT
     * unbind, so it cannot pull the transport out from under whoever owns it.
     *
     * Deliberately gated on `capabilities == null`. This is a recovery from a state that is already
     * broken, not a general second path into the projection: once anything has been read, the
     * reservation holder's ordering is respected again and this does nothing.
     */
    private fun adoptCapabilitiesFromHeldBindingIfNeverRead(): TweakerCapabilities? {
        val mayAdopt = tweakerBridgeMayAdoptHeldBinding(
            hasCachedCapabilities = capabilities != null,
            hasPendingLaunch = v4LaunchLifecycle.hasPendingLaunch(),
            mayOwnTransport = v4LaunchLifecycle.mayOwnTransport(),
            sessionBound = session.isBound,
        )
        if (!mayAdopt) return capabilities
        return refreshProjectionFromBoundSession()
    }

    /** Applies one explicit fan decision against the exact capability snapshot shown in Tweaker. */
    internal suspend fun setFanControl(
        capability: TweakerManualFanCapability,
        mode: TweakerFanMode,
        curveBias: Int,
    ): TweakerFanControlAssessment = withContext(Dispatchers.IO) {
        if (v4LaunchLifecycle.hasPendingLaunch() || v4LaunchLifecycle.mayOwnTransport()) {
            return@withContext TweakerFanControlAssessment.Uncertain("Tweaker owner is busy")
        }
        if (!sessionReservation.awaitExclusiveOwnerForMutation()) {
            return@withContext TweakerFanControlAssessment.Uncertain("Tweaker observer is busy")
        }
        try {
            val pairIdentity = inspectAndCachePairIdentity()
            if (!executeTweakerBindAttempt(pairIdentity) { session.bind() }) {
                return@withContext TweakerFanControlAssessment.Uncertain("Tweaker service is unavailable")
            }
            try {
                val apiVersion = session.getApiVersion()
                    ?: return@withContext TweakerFanControlAssessment.Uncertain("Tweaker API is unavailable")
                if (apiVersion < TWEAKER_V6_ADVANCED_CONTROLS_MIN_API_VERSION) {
                    return@withContext TweakerFanControlAssessment.Uncertain("Tweaker upgrade required")
                }
                val authoritative = session.getAdvancedCapabilitiesV6(apiVersion)?.manualFan
                // 2026-08-25: was `authoritative != capability`. That is whole-object equality,
                // which also compares currentMode and currentCurveBias -- the two fields this call
                // exists to change -- so every successful fan change poisoned the next press. See
                // TweakerManualFanCapability.isSameSessionAs for the device report it came from.
                if (authoritative == null || !authoritative.isSameSessionAs(capability)) {
                    advancedCapabilities = session.getAdvancedCapabilitiesV6(apiVersion)
                    return@withContext TweakerFanControlAssessment.Uncertain(
                        "Fan control session changed before selection",
                    )
                }
                val attempt = session.createFanControlAttemptV6(
                    capability = authoritative,
                    mode = mode,
                    curveBias = curveBias,
                    timeoutMs = FAN_CONTROL_TIMEOUT_MS,
                ) ?: return@withContext TweakerFanControlAssessment.Uncertain("Fan control selection is invalid")
                val result = session.setFanControlV6(apiVersion, attempt)
                advancedCapabilities = session.getAdvancedCapabilitiesV6(apiVersion)
                telemetry = session.getTelemetry()
                result
            } finally {
                session.unbind()
            }
        } finally {
            sessionReservation.endOwner()
        }
    }

    /**
     * Applies or clears one explicit live frame limit for the game currently on screen.
     *
     * Identity comes from the service's Usage-Access attestation on the same bound transport that
     * performs the mutation, so the attested game and the mutated package cannot diverge between
     * reads. The mutation arms the launch lifecycle's own FPS obligation: return settlement, retained
     * debt, next-launch blocking, and service-side Binder-death/hard-lease recovery are shared with
     * the launch path rather than reimplemented. Persisted per-package intent is deliberately not
     * written here; storing intent stays a separate explicit decision.
     */
    internal suspend fun applyLiveGameFpsLimit(targetFps: Int?): TweakerLiveFpsSelectionResult =
        withContext(Dispatchers.IO) {
            if (v4LaunchLifecycle.hasPendingLaunch()) {
                return@withContext TweakerLiveFpsSelectionResult.Busy
            }
            if (v4LaunchLifecycle.mayOwnTransport()) {
                // The launch lifecycle owns the sole transport; a healthy session keeps it bound, and
                // an unbound one is mid-teardown — no attested truth is available on it. The cleanup
                // tag is irrelevant here: this caller claimed nothing.
                if (!session.isBound) return@withContext TweakerLiveFpsSelectionResult.Unavailable
                return@withContext liveFpsOnOwnedTransport(targetFps, callerClaimedReservation = false)
                    .result
            }
            if (!sessionReservation.awaitGenericOwnerForLiveMutation()) {
                return@withContext TweakerLiveFpsSelectionResult.Busy
            }
            var lifecycleOwnsCleanup = false
            try {
                // Recheck after the reservation; this closes the same handoff race the observation
                // path closes, without holding a monitor over package-manager or Binder work.
                if (v4LaunchLifecycle.hasPendingLaunch() || v4LaunchLifecycle.mayOwnTransport()) {
                    return@withContext TweakerLiveFpsSelectionResult.Busy
                }
                val pairIdentity = inspectAndCachePairIdentity()
                if (!executeTweakerBindAttempt(pairIdentity) { session.bind() }) {
                    return@withContext TweakerLiveFpsSelectionResult.Unavailable
                }
                val tagged = liveFpsOnOwnedTransport(targetFps, callerClaimedReservation = true)
                lifecycleOwnsCleanup = !tagged.callerCleansUp
                tagged.result
            } finally {
                if (!lifecycleOwnsCleanup) {
                    // No lifecycle job ran, so the claim is still this caller's to release. A racing
                    // settlement may already own the transport; never unbind under it.
                    if (!v4LaunchLifecycle.mayOwnTransport()) session.unbind()
                    sessionReservation.endOwner()
                }
            }
        }

    /** Wraps a live result with whether the caller still owns its transport claim. */
    private data class LiveFpsCleanupTaggedResult(
        val result: TweakerLiveFpsSelectionResult,
        val callerCleansUp: Boolean,
    )

    private suspend fun liveFpsOnOwnedTransport(
        targetFps: Int?,
        callerClaimedReservation: Boolean,
    ): LiveFpsCleanupTaggedResult {
        session.getApiVersionAsync()
            ?.takeIf { it >= TWEAKER_V7_FPS_LIMITER_MIN_API_VERSION }
            ?: return LiveFpsCleanupTaggedResult(
                TweakerLiveFpsSelectionResult.Unavailable,
                callerCleansUp = true,
            )
        val outcome = if (targetFps == null) {
            v4LaunchLifecycle.restoreLiveFps(callerClaimedReservation)
        } else {
            val foreground = attestedLiveFpsForegroundPackage(
                telemetry = session.getTelemetry(),
                nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                selfPackageName = appContext.packageName,
            ) ?: return LiveFpsCleanupTaggedResult(
                TweakerLiveFpsSelectionResult.Rejected("No attested foreground game"),
                callerCleansUp = true,
            )
            v4LaunchLifecycle.applyLiveFps(
                packageName = foreground,
                targetFps = targetFps,
                expectedSystemProfileId = selectedProfileId(),
                callerClaimedReservation = callerClaimedReservation,
            )
        }
        return LiveFpsCleanupTaggedResult(
            result = outcome.toLiveSelectionResult(),
            // Busy is the only outcome without a lifecycle job; every other completion path already
            // released or retained the transport through the lifecycle's own machinery.
            callerCleansUp = outcome is TweakerLiveFpsOutcome.Busy,
        )
    }

    private fun TweakerLiveFpsOutcome.toLiveSelectionResult(): TweakerLiveFpsSelectionResult =
        when (this) {
            is TweakerLiveFpsOutcome.Applied ->
                TweakerLiveFpsSelectionResult.Applied(owner.packageName, owner.targetFps)
            TweakerLiveFpsOutcome.Stock -> TweakerLiveFpsSelectionResult.Stock
            is TweakerLiveFpsOutcome.Busy -> TweakerLiveFpsSelectionResult.Busy
            is TweakerLiveFpsOutcome.Superseded -> TweakerLiveFpsSelectionResult.Busy
            is TweakerLiveFpsOutcome.Rejected -> TweakerLiveFpsSelectionResult.Rejected(reason)
            is TweakerLiveFpsOutcome.Uncertain -> TweakerLiveFpsSelectionResult.Uncertain(reason)
        }

    /**
     * Opens read-only telemetry through the process's sole Tweaker session. An active V4 owner shares
     * its already-bound transport without changing its lifetime; Stock observation owns the reservation
     * and binding until [TweakerTelemetryObservation.close].
     */
    internal suspend fun openTelemetryObservation(): TweakerTelemetryObservation? =
        handOffObservation(Dispatchers.IO) {
            // Low-memory recovery: nothing binds the daemon while a low-memory recovery start holds `initialize` back.
            if (MimirLowMemoryRecovery.tweakerBridgeStartDeferred()) return@handOffObservation null
            if (launchHoldsObservers()) return@handOffObservation null
            if (v4LaunchLifecycle.mayOwnTransport()) {
                return@handOffObservation if (session.isBound) {
                    TweakerTelemetryObservation(readTelemetry = session::getTelemetry)
                } else {
                    null
                }
            }
            if (!sessionReservation.tryBeginObservation()) return@handOffObservation null
            var transferred = false
            try {
                // Recheck after the reservation. This closes the only possible handoff race without
                // holding a monitor over package-manager or Binder work.
                if (launchHoldsObservers() || v4LaunchLifecycle.mayOwnTransport()) {
                    return@handOffObservation null
                }
                val pairIdentity = inspectAndCachePairIdentity()
                if (!executeTweakerBindAttempt(pairIdentity) { session.bind() }) {
                    return@handOffObservation null
                }
                // The system-wide overlay intentionally keeps this observation alive. Hydrate the
                // same bound service truth before transferring ownership so a concurrent Tweaker
                // surface refresh cannot remain permanently UNAVAILABLE behind the observer.
                refreshProjectionFromBoundSession()
                val observation = TweakerTelemetryObservation(
                    readTelemetry = session::getTelemetry,
                    shouldYield = sessionReservation::shouldYieldObservation,
                    release = {
                        session.unbind()
                        sessionReservation.endObservation()
                    },
                )
                transferred = true
                observation
            } finally {
                if (!transferred) {
                    session.unbind()
                    sessionReservation.endObservation()
                }
            }
        }

    /**
     * Whether [openTelemetryObservation] would open the Tweaker path right now. Reads cached state and binds
     * nothing, so the overlay's kernel-read fallback can ask it every beat; see [discoveredTelemetryObservation].
     */
    internal fun telemetryObservationMayOpen(): Boolean = tweakerTelemetryObservationMayOpen(
        pairMayBind = cachedPairIdentity.mayBind,
        hasPendingLaunch = launchHoldsObservers(),
        mayOwnTransport = v4LaunchLifecycle.mayOwnTransport(),
        sessionBound = session.isBound,
        reservationIdle = sessionReservation.isIdle(),
        bridgeStartDeferred = MimirLowMemoryRecovery.tweakerBridgeStartDeferred(),
    )

    /**
     * The game audio-focus hold (Tweaker API v11, policy 2026-10-01), created on the first launch.
     *
     * Nullable and created lazily so the release path ([releaseGameAudioFocusAfterReturn]) never builds a
     * Tweaker session just to learn nothing is held, and never reads [appContext] before [initialize].
     */
    @Volatile
    private var gameAudioFocusHolder: GameAudioFocusHolder? = null
    private val gameAudioFocusHolderLock = Any()

    private fun gameAudioFocusHolderForLaunch(): GameAudioFocusHolder = gameAudioFocusHolder
        ?: synchronized(gameAudioFocusHolderLock) {
            gameAudioFocusHolder ?: GameAudioFocusHolder(
                // Its OWN session: the hold must stay bound until the return, long after the launch's
                // bind-observe-unbind window has closed, and a hold is not a profile -- it takes nothing
                // from [sessionReservation]. The Binder token it sends is that session's own.
                transport = SessionGameAudioFocusTransport(
                    session = TweakerSession(appContext),
                    pairMayBind = { inspectAndCachePairIdentity().mayBind },
                ),
                enabled = { AudioPreferences.keepMusicInGames.value },
                log = { line -> runCatching { Log.i(GameAudioFocusHolder.LOG_TAG, line) } },
            ).also { gameAudioFocusHolder = it }
        }

    /**
     * Ask the Tweaker to stop [packageName] taking audio focus, BEFORE the game starts. Never throws and never
     * changes what the launch does: any failure is a game that behaves as it always did.
     */
    private suspend fun holdGameAudioFocusForLaunch(packageName: String) {
        try {
            gameAudioFocusHolderForLaunch().holdForLaunch(packageName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG_BRIDGE, "game audio focus hold failed for $packageName", failure)
        }
    }

    /** Put the held package back: a no-op, and no session built, when nothing is held. */
    private fun releaseGameAudioFocusAfterReturn() {
        val holder = gameAudioFocusHolder ?: return
        if (holder.mayBeHeld == null) return
        ownerScope.launch { holder.releaseAfterReturn("return") }
    }

    internal suspend fun prepareForLaunch(
        attemptId: LaunchAttemptId,
        packageName: String,
    ): TweakerLaunchPreparation {
        // First, ahead of every early-out below: the hold does not depend on the profile path, and a
        // "Tweaker owner is still settling" skip must not cost the player their music.
        holdGameAudioFocusForLaunch(packageName)
        synchronized(preparedActiveProfileLock) {
            preparedActiveProfile = null
        }
        val selection = launchSelectionContext()
        launchSelectionSnapshot = selection
        val preparationEpoch = v4LaunchLifecycle.beginPreparation(attemptId)
        val result = try {
            withContext(Dispatchers.IO) {
                if (v4LaunchLifecycle.mayOwnTransport()) {
                    // The other site that answers "still settling". The 10-01 09:41:35 Skipped logged nothing at
                    // either, so which one fired was inferred from timing; this one now says so.
                    runCatching {
                        Log.i(
                            "MimirTweakerLaunch",
                            "package=$packageName reservation=not-requested reason=owner-active " +
                                "state=${sessionReservation.describe()}",
                        )
                    }
                    return@withContext TweakerPreparationResult.Skipped(
                        "Tweaker owner is still settling",
                    )
                }
                // The launch is the senior caller of the one Binder token. A refresh that is mid-flight (a
                // HOME-press resume refresh held the reservation 0.18 s past the preflight on 10-01) hands it
                // over when it ends, and the overlay's long-lived telemetry observation -- held for as long
                // as the overlay is enabled, and the cause of `Skipped(reason=Tweaker owner is still
                // settling)` with the overlay on ALWAYS (Nova 10-01 09:41:35) -- yields at its next beat,
                // exactly as it does for a settlement. The preflight then owns the token until it has
                // scheduled its ticket, so nothing can retake it between the two.
                val admission = awaitLaunchReservation(reservation = sessionReservation)
                if (admission.waitedMs > 0L || !admission.acquired) {
                    runCatching {
                        Log.i(
                            "MimirTweakerLaunch",
                            "package=$packageName reservation=${if (admission.acquired) "handed-over" else "held"} " +
                                "waitedMs=${admission.waitedMs} boundMs=$LAUNCH_RESERVATION_WAIT_MS " +
                                "state=${sessionReservation.describe()}",
                        )
                    }
                }
                if (!admission.acquired) {
                    return@withContext TweakerPreparationResult.Skipped(
                        "Tweaker owner is still settling",
                    )
                }
                try {
                    var ticket: PendingTweakerV4Launch? = null
                    val preparation = run preparation@{
                        val pairIdentity = inspectAndCachePairIdentity()
                        val connected = executeTweakerBindAttempt(pairIdentity) { session.bind() }
                        if (connected) {
                            capabilities = capabilitiesAfterTweakerBindAttempt(
                                connected = true,
                                cached = capabilities,
                            )
                        } else {
                            capabilitiesAfterFailedBind(pairIdentity)
                        }
                        if (!connected) {
                            normalizeStoredSelection()
                            return@preparation TweakerPreparationResult.Unavailable
                        }
                        try {
                            val apiVersion = session.getApiVersion()
                            val selectedProfileId = selectedProfileId()
                            val outcome = prepareBoundTweaker(
                                apiVersion = apiVersion,
                                selectedProfileId = selectedProfileId,
                                queryV4 = { version -> session.querySessionV4(version) },
                                readCapabilities = session::getCapabilities,
                            )
                            capabilities = outcome.capabilities
                            reconcileStoredSelection(outcome.preparation)
                            val effectiveSelectedProfileId = selectedProfileId()
                            val baseTicket = if (
                                apiVersion != null && apiVersion >= TWEAKER_V4_MIN_API_VERSION
                            ) {
                                pendingV4LaunchFor(
                                    attemptId = attemptId,
                                    preparation = outcome.preparation,
                                    capabilities = outcome.capabilities,
                                    selectedProfileId = effectiveSelectedProfileId,
                                    packageName = packageName,
                                    expiresAtElapsedRealtimeMs =
                                        SystemClock.elapsedRealtime() + V4_LAUNCH_TICKET_MS,
                                    includeDebugLab = BuildConfig.DEBUG,
                                )
                            } else {
                                null
                            }
                            val advanced = if (
                                apiVersion != null &&
                                apiVersion >= TWEAKER_V6_ADVANCED_CONTROLS_MIN_API_VERSION
                            ) {
                                session.getAdvancedCapabilitiesV6(apiVersion)
                            } else {
                                null
                            }
                            advancedCapabilities = advanced
                            val storedGameFpsPolicy = selectedGameFpsPolicy(packageName)
                            val configuredFrameLimit = storedGameFpsPolicy.frameLimitHz?.takeIf { fps ->
                                apiVersion != null &&
                                    apiVersion >= TWEAKER_V7_FPS_LIMITER_MIN_API_VERSION &&
                                    advanced?.perGameOverridesAvailable == true &&
                                    fps in session.getFpsLimiterCapabilityV7(apiVersion, packageName)
                                        ?.frameLimitsHz.orEmpty()
                            }
                            ticket = composePendingTweakerLaunch(
                                baseTicket = baseTicket,
                                attemptId = attemptId,
                                packageName = packageName,
                                expiresAtElapsedRealtimeMs =
                                    SystemClock.elapsedRealtime() + V4_LAUNCH_TICKET_MS,
                                systemProfileId = effectiveSelectedProfileId,
                                preparation = outcome.preparation,
                                productProfileIds = outcome.capabilities
                                    ?.let(::productSelectableProfileIds).orEmpty(),
                                storedFrameLimitHz = configuredFrameLimit,
                                storedGameFpsPolicyRevision = storedGameFpsPolicy.revision,
                            )
                            outcome.preparation
                        } finally {
                            session.unbind()
                        }
                    }
                    val pendingTicket = ticket
                    if (pendingTicket == null) {
                        preparation
                    } else if (v4LaunchLifecycle.schedule(preparationEpoch, pendingTicket)) {
                        TweakerPreparationResult.Scheduled(pendingTicket.profileId)
                    } else {
                        TweakerPreparationResult.Skipped("Tweaker owner is busy")
                    }
                } finally {
                    // Released only after the ticket is published: between the two, an overlay that reopens its
                    // observation would find the token free and no ticket pending, retake it, and the
                    // post-dispatch hand-off would then be refused.
                    sessionReservation.endOwner()
                }
            }
        } catch (failure: Throwable) {
            v4LaunchLifecycle.retireAttempt(attemptId)
            synchronized(preparedActiveProfileLock) {
                if (preparedActiveProfile?.first == attemptId) preparedActiveProfile = null
            }
            throw failure
        }
        synchronized(preparedActiveProfileLock) {
            preparedActiveProfile = (result as? TweakerPreparationResult.Applied)
                ?.profileId
                ?.takeIf { it != STOCK_PROFILE }
                ?.let { attemptId to it }
            preparedActiveProfileAtMs = SystemClock.elapsedRealtime()
        }
        return TweakerLaunchPreparation(attemptId, result, selection)
    }

    /**
     * A launch that has a ticket pending, or one whose preflight found its profile ALREADY active and is waiting for
     * the post-dispatch confirmation. Both need the token to stay free of observers: a ticket for the hand-off, and
     * the confirmation because [confirmPreparedActiveProfileAfterDispatch] takes an observation of its own and a
     * reopened overlay would be holding it. The confirmation window is bounded: a launch that never reaches its
     * confirmation (a dispatch that failed) must not lock the overlay out for the life of the process.
     */
    private fun launchHoldsObservers(): Boolean =
        v4LaunchLifecycle.hasPendingLaunch() || preparedActiveProfileConfirmationOutstanding()

    private fun preparedActiveProfileConfirmationOutstanding(): Boolean =
        synchronized(preparedActiveProfileLock) {
            preparedActiveConfirmationOutstanding(
                hasPreparedProfile = preparedActiveProfile != null,
                ageMs = SystemClock.elapsedRealtime() - preparedActiveProfileAtMs,
                windowMs = PREPARED_ACTIVE_CONFIRMATION_WINDOW_MS,
            )
        }

    private fun takePreparedActiveProfile(attemptId: LaunchAttemptId): String? =
        synchronized(preparedActiveProfileLock) {
            preparedActiveProfile
                ?.takeIf { it.first == attemptId }
                ?.second
                .also {
                    if (preparedActiveProfile?.first == attemptId) preparedActiveProfile = null
                }
        }

    /**
     * Reconfirms an already-active profile after Intent dispatch. The preflight snapshot only names
     * the expected profile; it is never sufficient to publish ACTIVE on its own.
     */
    internal suspend fun confirmPreparedActiveProfileAfterDispatch(
        attemptId: LaunchAttemptId,
    ): String? = withContext(Dispatchers.IO) {
        val expectedProfileId = takePreparedActiveProfile(attemptId) ?: return@withContext null
        if (v4LaunchLifecycle.mayOwnTransport() || !sessionReservation.tryBeginObservation()) {
            return@withContext null
        }
        try {
            val pairIdentity = inspectAndCachePairIdentity()
            if (!executeTweakerBindAttempt(pairIdentity) { session.bind() }) return@withContext null
            try {
                val apiVersion = session.getApiVersion()?.takeIf { it >= TWEAKER_V4_MIN_API_VERSION }
                    ?: return@withContext null
                val assessment = session.querySessionV4(apiVersion)
                val observedCapabilities = authoritativeV4Capabilities(
                    assessment = assessment,
                    readCapabilities = session::getCapabilities,
                )
                capabilities = observedCapabilities
                confirmedPreparedActiveProfile(
                    expectedProfileId = expectedProfileId,
                    assessment = assessment,
                    observedCapabilities = observedCapabilities,
                )
            } finally {
                session.unbind()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        } finally {
            sessionReservation.endObservation()
        }
    }

    /**
     * Restore stock on return, then re-read capabilities — as ONE job on ONE bind.
     *
     * API v4 performs an authoritative read-only query. A daemon older than v4 (or one that does not name its
     * version) is unavailable: nothing is restored, nothing is read, and no stock is assumed.
     */
    suspend fun settleAfterReturn(): TweakerCapabilities? {
        // The one "the game is over, or never started" signal every path shares (primary resume, the dispatch
        // watchdog, a failed dispatch, a bottom-screen game's cleanup), so the audio-focus hold ends here too.
        releaseGameAudioFocusAfterReturn()
        // Activity/lifecycle callers only await a process-owned operation. Their cancellation must not
        // strand the sole Binder reservation halfway through observer yield or exact Stock recovery.
        val (settlement, startsWork) = synchronized(returnSettlementLock) {
            returnSettlement?.takeIf { it.isActive }?.let { it to false }
                ?: CompletableDeferred<TweakerCapabilities?>().let { created ->
                    returnSettlement = created
                    created to true
                }
        }
        if (startsWork) {
            ownerScope.launch {
                try {
                    settlement.complete(settleAfterReturnOwned())
                } catch (cancelled: CancellationException) {
                    settlement.completeExceptionally(cancelled)
                    throw cancelled
                } catch (failure: Throwable) {
                    settlement.completeExceptionally(failure)
                } finally {
                    synchronized(returnSettlementLock) {
                        if (returnSettlement === settlement) returnSettlement = null
                    }
                }
            }
        }
        return settlement.await()
    }

    private suspend fun settleAfterReturnOwned(): TweakerCapabilities? = withContext(Dispatchers.IO) {
        // Reserve the sole token before settlement. A telemetry observer receives a local yield signal
        // and hands the reservation directly to return settlement; no second Binder session is created.
        val settlementReserved = sessionReservation.awaitOwnerForSettlement()
        v4LaunchLifecycle.settle()
        // Both bail-outs below must hand the reservation back. A settlement acquire that turns around
        // without releasing used to cost nothing, because on this path it is almost always a JOIN of the
        // launch's own generic owner and a join mutated no state. Now that joins are counted, an
        // unreleased one strands a holder: the launch's later release takes the depth to 1 instead of 0,
        // `use` stays OWNER for the life of the process, and every later observation and acquire is
        // refused — the bridge answers with nulls until it is restarted.
        if (v4LaunchLifecycle.mayOwnTransport()) {
            if (settlementReserved) sessionReservation.endOwner()
            retainCapabilitiesUnanswered()
            return@withContext null
        }
        if (!settlementReserved || !sessionReservation.transitionOwnerToObservation()) {
            if (settlementReserved) sessionReservation.endOwner()
            retainCapabilitiesUnanswered()
            return@withContext null
        }
        try {
            val pairIdentity = inspectAndCachePairIdentity()
            if (!executeTweakerBindAttempt(pairIdentity) { session.bind() }) {
                capabilitiesAfterFailedBind(pairIdentity)
                normalizeStoredSelection()
                return@withContext null
            }
            try {
                val apiVersion = session.getApiVersion()
                if (apiVersion != null && apiVersion >= TWEAKER_V4_MIN_API_VERSION) {
                    val assessment = session.querySessionV4(apiVersion)
                    val observedCapabilities = session.getCapabilities()
                    if (
                        assessment is TweakerV4SessionAssessment.Blocked &&
                        assessment.truth.state == TweakerV4SessionState.ACTIVE &&
                        assessment.truth.requestId?.startsWith(LAB_REQUEST_PREFIX) == true &&
                        observedCapabilities?.allowsDebugLabOwnerValidation() == true
                    ) {
                        // The LAB hold is service-owned, not public-owner V4 identity. Its existing
                        // exact legacy restore is diagnostics-only and settles through the same journal.
                        session.restore()
                    }
                    val settledAssessment = session.querySessionV4(apiVersion)
                    capabilities = authoritativeV4Capabilities(
                        assessment = settledAssessment,
                        readCapabilities = session::getCapabilities,
                    )
                    reconcileStoredSelection(settledAssessment)
                } else {
                    // No answer, or a daemon older than v4: unavailable. There is no authoritative session
                    // query to settle against, so nothing is restored here and nothing is read, and the
                    // absence of an answer is never taken for Stock.
                    capabilities = null
                }
                normalizeStoredSelection()
                capabilities
            } finally {
                session.unbind()
            }
        } finally {
            sessionReservation.endObservation()
        }
    }

    fun launchSelectionContext(): TweakerLaunchSelectionContext {
        val caps = capabilities
        return TweakerLaunchSelectionContext(
            selectedProfileId = selectedProfileId(),
            certifiedProfileIds = caps?.let(::productSelectableProfileIds)
                .orEmpty().filterNot { it == STOCK_PROFILE }.toSet(),
            tweakerReachable = caps?.status?.isReachable() == true,
        )
    }

    /** Exact user-selection snapshot taken before capability refresh/normalization can change it. */
    fun preparedLaunchSelectionContext(): TweakerLaunchSelectionContext =
        launchSelectionSnapshot ?: launchSelectionContext()

    /**
     * The last curve bias the player actually applied in MANUAL, per profile.
     *
     * Switching from Manual to Auto and back lost the previous bias (for example, cooler 2): the daemon requires
     * `fanCurveBias == 0` while the mode is AUTO (see `parseManualFanCapability`), so the moment
     * the player leaves Manual the authoritative bias IS zero and the number they chose exists
     * nowhere. `stepTweakerFanMode` then re-entered Manual with `fan.currentCurveBias`, faithfully
     * reading back that zero.
     *
     * Kept per profile because the curve it biases is per profile
     * (`FanCurveDefaults.forProfile`), and persisted rather than held in memory because the round
     * trip that loses it is not only Auto-and-back -- an app restart, a daemon restart or a profile
     * change would each have dropped it too.
     */
    fun rememberManualCurveBias(profileId: String, curveBias: Int) {
        if (profileId.isBlank()) return
        preferences.edit().putInt(manualCurveBiasKey(profileId), curveBias).apply()
    }

    /**
     * Returns null unless a remembered bias exists AND is still admissible on the capability the
     * row is showing. A stored value that no longer fits the advertised range/step is dropped here
     * rather than sent: `createFanControlAttemptV6` would refuse it, and the player would get "FAN
     * RESPONSE NOT APPLIED" for a press they never got wrong.
     */
    fun rememberedManualCurveBias(capability: TweakerManualFanCapability): Int? {
        val key = manualCurveBiasKey(capability.profileId)
        if (!preferences.contains(key)) return null
        val stored = preferences.getInt(key, 0)
        if (stored !in capability.curveBiasRange) return null
        if (capability.curveBiasStep <= 0 || stored % capability.curveBiasStep != 0) return null
        return stored
    }

    /**
     * The fan MODE the player last applied, per profile.
     *
     * The fan
     * genuinely does return to vendor Auto when the frontend dies — `OWNER_DEATH_RESTORE`, observed
     * on the Odin at 06:13:26 when an install force-stopped the app — and that is the abandoned-fan
     * guarantee doing its job: Manual stops the vendor's regulation loop, so nothing may hold it
     * while nothing is watching. That guarantee stays in force. The remembered
     * CHOICE outlives the restart, which is a different thing and lives on this side of the
     * wire: the daemon hands the fan back safely, and the frontend re-selects Manual once it is
     * watching again.
     *
     * Stored on every ACCEPTED change, including a change to Auto — picking Auto deliberately must
     * be remembered just as firmly, or the re-arm below would fight the player.
     */
    fun rememberFanMode(profileId: String, mode: TweakerFanMode) {
        if (profileId.isBlank()) return
        preferences.edit().putString(fanModeKey(profileId), mode.name).apply()
    }

    /** Null when nothing has been chosen for this profile yet, or the stored value is unreadable. */
    fun rememberedFanMode(profileId: String): TweakerFanMode? {
        val raw = preferences.getString(fanModeKey(profileId), null) ?: return null
        return runCatching { TweakerFanMode.valueOf(raw) }.getOrNull()
    }

    private fun fanModeKey(profileId: String) = "$KEY_FAN_MODE_PREFIX$profileId"

    private fun manualCurveBiasKey(profileId: String) = "$KEY_MANUAL_CURVE_BIAS_PREFIX$profileId"

    private fun persistProfile(profileId: String) {
        if (selectedProfileId() != profileId) {
            preferences.edit().putString(KEY_PROFILE, profileId).apply()
        }
    }

    private fun reconcileStoredSelection(assessment: TweakerV4SessionAssessment) {
        authoritativeProfileId(assessment)?.let(::persistProfile)
    }

    private fun reconcileStoredSelection(preparation: TweakerPreparationResult) {
        when (preparation) {
            TweakerPreparationResult.StockNoOp -> persistProfile(STOCK_PROFILE)
            is TweakerPreparationResult.Applied -> persistProfile(preparation.profileId)
            else -> normalizeStoredSelection()
        }
    }

    /**
     * The player's standing system-wide choice, kept deliberately apart from [KEY_PROFILE].
     *
     * [KEY_PROFILE] tracks *authoritative* truth and is rewritten from whatever the service reports, so
     * it cannot carry intent across a process restart: Guardian restores exact Stock ~4s after the
     * owning process dies (device-proven on Odin 2026-08-10 — force-stop at 03:05:29, Stock at
     * 03:05:33 with no Mimir process running at all), and the next observation would faithfully persist
     * that Stock over the player's choice. Recording intent separately is what lets startup tell
     * "the player wants Balanced and Guardian cleaned up after a death" apart from "the player chose
     * Stock".
     *
     * Only an explicit selection writes here. Observation, normalization, and reconciliation never do.
     */
    private fun recordStandingSelection(result: TweakerSystemProfileSelectionResult) {
        val intent = when (result) {
            is TweakerSystemProfileSelectionResult.Active -> result.profileId
            is TweakerSystemProfileSelectionResult.Stock -> STOCK_PROFILE
            // Busy/Unavailable/UpgradeRequired/Rejected/Uncertain never reached the hardware, and
            // Cancelled is the player taking the request back. None of them is a new standing choice.
            else -> return
        }
        if (standingSystemProfileId() != intent) {
            preferences.edit().putString(KEY_STANDING_PROFILE, intent).apply()
        }
    }

    /** Blank until the player has ever chosen explicitly; never inferred from observed state. */
    internal fun standingSystemProfileId(): String =
        preferences.getString(KEY_STANDING_PROFILE, "").orEmpty()

    /** Test seam: the real recorder is invoked from inside the one bound mutation path. */
    internal fun recordStandingSelectionForTest(result: TweakerSystemProfileSelectionResult) =
        recordStandingSelection(result)

    /**
     * Re-assert the standing choice once per process start.
     *
     * Guardian tying a profile to the owning process is the safety mechanism working as designed — a
     * dead app must never strand the hardware in a derate — so this does not touch it. Instead the app
     * re-states the player's intent through the ordinary selection path, which means qualification,
     * admission, Stock-first switching, readback, and Guardian arming all apply exactly as they do to a
     * button press. Nothing here can apply a profile the normal path would refuse.
     *
     * Deliberately inert unless every condition holds: the player has a standing non-Stock choice, no
     * launch is in flight, the service is genuinely at Stock (so this never fights a live session), and
     * the profile is still product-selectable on this device.
     */
    internal suspend fun rearmStandingSystemProfile(): TweakerSystemProfileSelectionResult? {
        val standing = standingSystemProfileId()
        if (standing.isBlank() || standing == STOCK_PROFILE) return declineRearm(standing, "no_standing_choice")
        if (v4LaunchLifecycle.hasPendingLaunch() || v4LaunchLifecycle.mayOwnTransport()) {
            return declineRearm(standing, "launch_owns_transport")
        }
        // Capability truth is what says whether this profile is still offered on this hardware.
        // Transient during the service's RESTORING window, which is why the watcher retries rather
        // than treating this exit as terminal the way the once-per-process-start call had to.
        val caps = capabilities ?: refreshCapabilities() ?: return declineRearm(standing, "no_capability_truth")
        // The comment above calls the RESTORING window transient. On 2026-09-04 it was not: both
        // handhelds held one for hours. Capabilities now survive it, so this exit has to be explicit or
        // the watcher would drive a mutation into a daemon that refuses it every fifteen seconds.
        if (heldByUnfinishedRestore) return declineRearm(standing, "held_by_unfinished_restore")
        if (standing !in productSelectableProfileIds(caps)) return declineRearm(standing, "not_selectable_here")
        // Only re-arm from a settled Stock baseline. If a profile is already active -- the service
        // outlived us, or another surface just applied one -- the standing choice is already honoured
        // or deliberately superseded, and re-applying would restart a live session for nothing.
        //
        // Reads the row's effective truth, not `selectedProfileId()`: the raw KEY_PROFILE pref goes
        // stale whenever `normalizeStoredSelection` bails on null capabilities, which is precisely the
        // service-died case this guard has to survive.
        if (utilityRowState().effectiveProfileId != STOCK_PROFILE) {
            // It is holding. That is the success signal for the circuit breaker below.
            rearmAttempts = 0
            rearmAttemptsStanding = standing
            return declineRearm(standing, "already_non_stock")
        }
        // **Circuit breaker, 2026-08-23.** Some chassis accept a CPU ceiling write and then revert
        // it to stock within seconds -- measured on a Nova test device: apply, `TARGET_CONTRACT_DRIFT_
        // _CPU`, restore, re-arm, round again, ~2 drift-restores and 3 Guardian arms per 100s. Each
        // cycle re-arms a foreground service and hammers the same privileged transport that
        // brightness, joystick colour and the fan row ride on, so the visible symptom is not "my
        // profile did not stick" but "everything else stopped working".
        //
        // Re-arming is right when a profile was lost to something transient. It is wrong when the
        // device has demonstrated, repeatedly, that it will not hold this profile -- then it is a
        // loop that costs the player every other control. After [MAX_REARM_ATTEMPTS] consecutive
        // attempts that never reached a non-stock effective state, stop and say so.
        //
        // Deliberately NOT a fix for the revert itself, and not a re-imposition of the CPU floor
        // retired refusal: the profile is still applied, still clamped to what the device
        // will take, and a single apply still happens. Only the endless retry stops. The counter
        // resets the moment a profile holds, when the standing choice changes, or after
        // [REARM_WINDOW_MS], so a device that recovers re-arms normally.
        if (standing != rearmAttemptsStanding ||
            nowMs() - rearmAttemptsStartedMs > REARM_WINDOW_MS
        ) {
            rearmAttemptsStanding = standing
            rearmAttempts = 0
            rearmAttemptsStartedMs = nowMs()
        }
        if (rearmAttempts >= MAX_REARM_ATTEMPTS) {
            return declineRearm(standing, "not_holding_after_$rearmAttempts")
        }
        rearmAttempts += 1
        // Waits out a reservation refusal inside its hand-over budget instead of answering Busy at once; see
        // awaitExclusiveOwnerForStandingRearm for round 5c's Busy 1-2 ms after dispatch.
        val result = selectSystemProfile(standing, setOf(standing)) { requested ->
            selectSystemProfileAfterRenderedAdmission(requested, control = null, standingRearm = true)
        }
        // "attempted", not "applied": the ordinary selection path can still answer Busy or Rejected,
        // and device evidence 2026-08-10 shows the first attempt after a service restart often does.
        Log.i(TAG_BRIDGE, "rearm standing=$standing exit=attempted result=$result")
        return result
    }

    /**
     * Every re-arm exit is named in logcat.
     *
     * All five declines used to return null with no trace, which is why a profile silently failing to
     * come back needed a device reproduction to explain. The guards themselves are unchanged — this
     * only makes which one fired observable.
     */
    /** Consecutive re-arms that never reached non-stock before the breaker opens. */
    private val MAX_REARM_ATTEMPTS = 3

    /** Counter lifetime; a device that recovers later re-arms normally. */
    private val REARM_WINDOW_MS = 10 * 60 * 1000L

    /** See the circuit breaker in the re-arm path. */
    private var rearmAttempts: Int = 0
    private var rearmAttemptsStanding: String = ""
    private var rearmAttemptsStartedMs: Long = 0L

    private fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()

    private fun declineRearm(standing: String, exit: String): TweakerSystemProfileSelectionResult? {
        Log.i(TAG_BRIDGE, "rearm standing=$standing exit=$exit selected=${selectedProfileId()}")
        return null
    }

    /**
     * The watcher's decision for one observation of [row]. A "not serving" row is a definitive negative only
     * when the daemon answered or the pair can never bind ([tweakerNegativeIsDefinitive]), and a READY row whose
     * last ask went unanswered is unknown, not honoured ([TweakerUtilityRowState.answerPending]).
     */
    private fun standingDecisionFor(standing: String, row: TweakerUtilityRowState): StandingRearmDecision =
        standingRearmDecision(
            standingProfileId = standing,
            effectiveProfileId = row.effectiveProfileId,
            availability = row.availability,
            negativeIsDefinitive = tweakerNegativeIsDefinitive(
                pairState = cachedPairIdentity.state,
                // "The daemon answered", not "capabilities exist": a session the daemon answered but declined to
                // turn into capabilities (Uncertain, a non-SYSTEM Blocked) leaves them null on a pair that can
                // bind, and treating that as no answer kept the watch binding every 60 s for the process.
                capabilitiesAnswered = tweakerCapabilitiesAnswered(
                    capabilitiesPresent = capabilities != null,
                    daemonAnsweredInProcess = daemonAnsweredInProcess,
                    truthUnknown = capabilityTruthUnknown,
                ),
            ),
            capabilitiesUnanswered = row.answerPending,
        )

    /**
     * The observation hook. Every authoritative projection refresh asks one question: has the player's
     * standing choice been lost?
     *
     * Guardian restoring Stock when the owning process dies is the safety mechanism working correctly,
     * and the service dying is not the player changing their mind — so the app re-states the intent
     * through the ordinary selection path.  a profile persists until the player explicitly changes it.
     */
    private fun noteAuthoritativeTruthForStandingRearm() {
        val standing = standingSystemProfileId()
        val row = utilityRowState()
        val decision = standingDecisionFor(standing, row)
        if (decision != StandingRearmDecision.NOTHING_TO_ASSERT) {
            logOnChange(
                "observe",
                "rearmwatch observe decision=$decision standing=$standing " +
                    "effective=${row.effectiveProfileId} availability=${row.availability}",
            )
        }
        when (decision) {
            // Nothing to assert, already honoured, or definitively not offered here: clear backoff
            // debt and stand the watch down. NOT_OFFERED keeps the standing choice — intent outlives
            // a missing service — and the hook restarts the watch if the Tweaker ever comes back.
            // Honoured is only terminal when nobody is watching the screen. While Mimir is resumed
            // the watch has to survive it -- cancelling here is precisely how a later,
            // externally-caused loss went unnoticed for minutes, because the ONLY thing that would
            // have restarted the watch is an observation that nothing was left to perform.
            StandingRearmDecision.HONOURED ->
                if (standingWatchConfirms(decision, foregroundConfirmActive)) {
                    standingRearmBackoffCleared()
                    startStandingRearmWatch()
                } else {
                    standingRearmHealed()
                }
            StandingRearmDecision.NOTHING_TO_ASSERT,
            StandingRearmDecision.NOT_OFFERED,
            -> standingRearmHealed()
            // Truth is unreadable. Keep the watch alive so it can be read again, but attempt nothing
            // and never mistake this for honoured -- that mistake is what cancelled the watcher and
            // failed the first two certification runs.
            StandingRearmDecision.TRUTH_UNKNOWN -> startStandingRearmWatch()
            StandingRearmDecision.REARM -> {
                scheduleStandingRearm()
                startStandingRearmWatch()
            }
        }
    }

    /** Resets the singleton's watcher state so one test cannot leak backoff or in-flight into the next. */
    internal fun resetStandingRearmStateForTest() = standingRearmHealed().also {
        standingRearmInFlight.set(false)
        standingRearmFlight?.end()
        standingRearmFlight = null
    }

    /**
     * Test seam: dispatches through the real [scheduleStandingRearm] and returns the flight that dispatch recorded, or
     * null when the scheduler dispatched nothing.
     */
    internal fun scheduleStandingRearmForTest(): StandingRearmFlight? {
        val before = standingRearmFlight
        scheduleStandingRearm()
        return standingRearmFlight?.takeIf { it !== before }
    }

    /** Test seam for the observation hook; production drives it from the projection refresh. */
    internal fun noteAuthoritativeTruthForStandingRearmForTest() =
        noteAuthoritativeTruthForStandingRearm()

    /** True while the standing-profile watch is alive. Test-only observation of watch lifetime. */
    internal fun standingRearmWatchActiveForTest(): Boolean =
        synchronized(standingRearmWatchLock) { standingRearmWatchJob?.isActive == true }

    private fun standingRearmHealed() {
        standingRearmBackoffCleared()
        synchronized(standingRearmWatchLock) {
            standingRearmWatchJob?.cancel()
            standingRearmWatchJob = null
        }
    }

    /**
     * Clear backoff debt WITHOUT standing the watch down.
     *
     * Confirm mode needs exactly this half of [standingRearmHealed]: the incident is over, so the
     * ladder must reset, but the watch has to keep looking because the next loss will arrive with no
     * announcement at all.
     */
    private fun standingRearmBackoffCleared() {
        standingRearmFailures = 0
        standingRearmNotBeforeElapsedMs = 0L
    }

    /**
     * Whether Mimir is the resumed app, which is the only time confirming is worth anything.
     *
     * DEVICE EVIDENCE (Odin, 2026-08-13, manual). Walking Settings -> Library -> Add games and
     * hopping in and out of the system folder picker ended the session from OUTSIDE the app:
     * `SERVICE restore reason=STARTUP_RECOVERY_RESTORE ... restored=true`. The profile fell to Stock
     * and STAYED there -- 5.5 minutes in one run, over 10 in another -- until the app was restarted
     * by hand, at which point the re-arm fired within seconds.
     *
     * The watcher was not broken. It was never started: it is stood down while the choice is honoured
     * (correctly -- see [startStandingRearmWatch]), and the only things that start it run when
     * something refreshes the projection. Nothing refreshes while the player sits on the home screen,
     * so a loss that nobody observed started nothing. **The watch was started from the same
     * observation path it existed to back up.**
     */
    @Volatile
    private var foregroundConfirmActive = false

    /**
     * Mimir is resumed: keep confirming the standing choice is still real.
     *
     * Deliberately inert unless the player actually has a standing non-Stock choice, so a device with
     * nothing to protect still polls nothing.
     */
    internal fun onPrimaryForegroundResumed() {
        val standing = standingSystemProfileId()
        if (standing.isBlank() || standing == STOCK_PROFILE) return
        foregroundConfirmActive = true
        startStandingRearmWatch()
    }

    /** Mimir is no longer resumed: the watch exits on its next tick. */
    internal fun onPrimaryForegroundPaused() {
        foregroundConfirmActive = false
    }

    /** Test seam for the foreground confirm gate. */
    internal fun setForegroundConfirmActiveForTest(active: Boolean) {
        foregroundConfirmActive = active
    }

    /**
     * The standing-profile re-arm in flight now, or null. A display-rate APPLY whose write is refused because the
     * session reservation is busy waits for this re-arm's end instead of guessing how long it holds. The end is signalled from the `finally` of the job [scheduleStandingRearm] launched, so a re-arm
     * that declines or throws ends as well.
     */
    internal fun inFlightStandingRearm(): StandingRearmFlight? = standingRearmFlight?.takeUnless { it.hasEnded }

    /**
     * One attempt at a time. A second observation mid-flight is dropped, never queued.
     *
     * Deliberately does NOT pre-check transport ownership: [rearmStandingSystemProfile] already makes
     * that call and now names it in logcat. Duplicating it here only added a second silent way to do
     * nothing, which is what made the 23:31 certification run unreadable — it produced no `rearm` line
     * at all, so there was no way to tell a declined attempt from an attempt that never happened.
     */
    private fun scheduleStandingRearm() {
        val now = SystemClock.elapsedRealtime()
        when (
            standingRearmAdmission(
                nowElapsedRealtimeMs = now,
                notBeforeElapsedRealtimeMs = standingRearmNotBeforeElapsedMs,
                inFlight = standingRearmInFlight.get(),
            )
        ) {
            StandingRearmAdmission.BACKOFF -> {
                Log.i(
                    TAG_BRIDGE,
                    "rearmwatch schedule=skipped reason=backoff remainingMs=" +
                        "${standingRearmNotBeforeElapsedMs - now} failures=$standingRearmFailures",
                )
                return
            }
            StandingRearmAdmission.IN_FLIGHT -> {
                Log.i(TAG_BRIDGE, "rearmwatch schedule=skipped reason=in_flight")
                return
            }
            StandingRearmAdmission.DISPATCH -> Unit
        }
        // The admission read above is advisory; this CAS is what actually makes it single-flight
        // against a concurrent scheduler.
        if (!standingRearmInFlight.compareAndSet(false, true)) {
            Log.i(TAG_BRIDGE, "rearmwatch schedule=skipped reason=in_flight")
            return
        }
        val flight = TweakerStandingRearmFlight(dispatchedAtElapsedRealtimeMs = now)
        standingRearmFlight = flight
        Log.i(TAG_BRIDGE, "rearmwatch schedule=dispatched")
        ownerScope.launch {
            try {
                val result = rearmStandingSystemProfile()
                if (result is TweakerSystemProfileSelectionResult.Active) {
                    // Mirror the HONOURED branch in [noteAuthoritativeTruthForStandingRearm]: a
                    // successful re-arm puts the choice in exactly the state that branch calls
                    // HONOURED, and the same rule applies -- terminal only when nobody is watching
                    // the screen. Healing unconditionally here is the device-observed defect: it
                    // cancelled the watch this same success just re-armed, so the FIRST loss after
                    // a foreground re-arm went unnoticed until the app was restarted by hand (the
                    // standing profile sat at Stock for 16 minutes on 2026-09-07).
                    if (standingWatchConfirms(StandingRearmDecision.HONOURED, foregroundConfirmActive)) {
                        standingRearmBackoffCleared()
                        startStandingRearmWatch()
                    } else {
                        standingRearmHealed()
                    }
                } else {
                    recordStandingRearmFailure()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.i(TAG_BRIDGE, "rearm exit=threw reason=${failure.message}")
                recordStandingRearmFailure()
            } finally {
                standingRearmInFlight.set(false)
                flight.end()
            }
        }
    }

    /**
     * Exponential backoff so a re-arm that keeps failing cannot hammer the service. Reset by any
     * success, or by any observation that reads non-Stock.
     */
    private fun recordStandingRearmFailure() {
        standingRearmFailures += 1
        standingRearmNotBeforeElapsedMs = SystemClock.elapsedRealtime() + standingRearmBackoffMs(
            consecutiveFailures = standingRearmFailures,
            baseMs = STANDING_REARM_BASE_BACKOFF_MS,
            maxMs = STANDING_REARM_MAX_BACKOFF_MS,
            maxSteps = STANDING_REARM_MAX_BACKOFF_STEPS,
        )
    }

    /**
     * A watch that exists only while the choice is actually lost, and cancels itself the moment it is
     * honoured again.
     *
     * The projection hook alone would be enough whenever something is already refreshing truth — the
     * overlay's sampling loop reopens its observation on a cadence, and that path refreshes the
     * projection. But that only holds while the performance overlay is enabled, and the defect must
     * not depend on an unrelated surface being switched on. This closes that gap without polling in
     * the healthy case: nothing runs unless a standing profile is currently missing.
     */
    private fun startStandingRearmWatch() {
        synchronized(standingRearmWatchLock) {
            if (standingRearmWatchJob?.isActive == true) return
            Log.i(TAG_BRIDGE, "rearmwatch watch=started intervalMs=$STANDING_REARM_WATCH_INTERVAL_MS")
            standingRearmWatchJob = ownerScope.launch {
                var tick = 0
                var unknownTicks = 0
                var delayMs = STANDING_REARM_WATCH_INTERVAL_MS
                while (true) {
                    delay(delayMs)
                    tick += 1
                    val standing = standingSystemProfileId()
                    val row = utilityRowState()
                    val decision = standingDecisionFor(standing, row)
                    val pendingLaunch = v4LaunchLifecycle.hasPendingLaunch()
                    val ownsTransport = v4LaunchLifecycle.mayOwnTransport()
                    unknownTicks = if (decision == StandingRearmDecision.TRUTH_UNKNOWN) unknownTicks + 1 else 0
                    Log.i(
                        TAG_BRIDGE,
                        "rearmwatch tick=$tick decision=$decision standing=$standing " +
                            "effective=${row.effectiveProfileId} availability=${row.availability} " +
                            "bound=${session.isBound} pendingLaunch=$pendingLaunch " +
                            "mayOwnTransport=$ownsTransport pair=${cachedPairIdentity.state} " +
                            "capabilities=${if (capabilities != null) "known" else "none"} " +
                            "unanswered=$capabilityTruthUnknown",
                    )
                    delayMs = standingWatchDelayMs(decision, consecutiveUnknownTicks = unknownTicks)
                    if (decision != StandingRearmDecision.TRUTH_UNKNOWN &&
                        decision != StandingRearmDecision.REARM
                    ) {
                        if (!standingWatchConfirms(decision, foregroundConfirmActive)) {
                            // NOTHING_TO_ASSERT / NOT_OFFERED, or HONOURED with Mimir backgrounded:
                            // terminal. Heal rather than bare-return -- a tick-side exit that skipped
                            // this left backoff debt behind, inflating the first delay of an unrelated
                            // incident days later.
                            Log.i(TAG_BRIDGE, "rearmwatch watch=stopped reason=$decision")
                            standingRearmHealed()
                            return@launch
                        }
                        // Confirm mode. The incident (if there was one) is over, so clear the ladder --
                        // but do NOT return, and do NOT skip the refresh below. A confirm tick that
                        // never re-reads would pin this stale HONOURED forever, which is the exact
                        // stale-truth trap [refreshProjectionForStandingRearm] documents.
                        Log.i(TAG_BRIDGE, "rearmwatch tick=$tick action=confirm intervalMs=$delayMs")
                        standingRearmBackoffCleared()
                    }
                    if (pendingLaunch || ownsTransport) {
                        // Read-only projection refresh still must not cut across a launch that owns
                        // the transport; the next tick re-evaluates.
                        Log.i(TAG_BRIDGE, "rearmwatch tick=$tick action=skipped reason=transport")
                        continue
                    }
                    refreshProjectionForStandingRearm()
                }
            }
        }
    }

    /**
     * Re-read authoritative truth for the watcher, whichever way the transport currently sits.
     *
     * [refreshCapabilities] is the wrong door here. Whenever the reservation is held — and
     * PerformanceOverlayService's telemetry observation holds it for as long as the overlay is
     * enabled — it yields to the holder and falls back to [adoptCapabilitiesFromHeldBindingIfNeverRead],
     * which is deliberately gated on `capabilities == null`. A lost standing profile leaves a stale,
     * *degraded* capability set behind (`availability=UNAVAILABLE profiles=[stock]`), so that gate is
     * closed, nothing re-reads, and the watcher spins forever against truth that can never improve.
     *
     * Device-proven on the 23:26 certification run of `5f3dc124…`: the profile dropped at t+8s and the
     * watch ticked for 80s without producing a single new projection. That build failed its gate.
     *
     * So: piggyback the holder's binding exactly as that path documents — read-only, no bind/unbind,
     * no mutation, therefore no race with the holder's own work — and only take the reservation route
     * when nobody is holding the session.
     */
    private suspend fun refreshProjectionForStandingRearm() {
        // The player's own profile change outranks the watcher, always. Piggybacking a bound session
        // cannot race the holder at the service, but it can in the projection: this refresh rewrites
        // capabilities/telemetry and can persist a transitional Stock via reconcileStoredSelection
        // while a Stock-first switch is mid-flight, and then dispatch a doomed re-arm on top of it.
        // Stand off for a tick instead; the healed state arrives on its own.
        if (sessionReservation.mutationMayBeInFlight()) {
            Log.i(TAG_BRIDGE, "rearmwatch refresh=skipped reason=mutation_in_flight")
            return
        }
        if (session.isBound) {
            Log.i(TAG_BRIDGE, "rearmwatch refresh=piggyback")
            refreshProjectionFromBoundSession()
        } else {
            Log.i(TAG_BRIDGE, "rearmwatch refresh=reservation")
            refreshCapabilities()
        }
        // Truth has just moved; whoever is projecting it needs to look again. See
        // [onStandingRearmProjectionRefreshed] for why this is the fan re-arm's only trigger when
        // the player is not sitting in the panel.
        onStandingRearmProjectionRefreshed?.invoke()
    }

    private fun normalizeStoredSelection() {
        val current = selectedProfileId()
        if (current == STOCK_PROFILE) return
        val selectable = capabilities?.let(::effectiveSelectableProfileIds) ?: return
        if (current !in selectable) {
            persistProfile(STOCK_PROFILE)
        }
    }

    private fun effectiveSelectableProfileIds(caps: TweakerCapabilities): List<String> =
        selectableTweakerProfileIds(
            capabilities = caps,
            includeDiagnosticProfiles = includeLabProfilesInUtility(caps, BuildConfig.DEBUG),
        )

    private fun productSelectableProfileIds(caps: TweakerCapabilities): List<String> =
        selectableTweakerProfileIds(
            capabilities = caps,
            includeDiagnosticProfiles = false,
        )

    private fun reconcileAfterSystemSelection(
        apiVersion: Int,
        result: TweakerV5SystemSelectionAssessment,
    ) {
        val assessment = when (result) {
            is TweakerV5SystemSelectionAssessment.Active ->
                TweakerV4SessionAssessment.Blocked(result.truth)
            is TweakerV5SystemSelectionAssessment.Stock ->
                TweakerV4SessionAssessment.Stock(result.truth)
            is TweakerV5SystemSelectionAssessment.Rejected ->
                session.querySessionV4(apiVersion)
            is TweakerV5SystemSelectionAssessment.Uncertain ->
                session.querySessionV4(apiVersion)
        }
        capabilities = authoritativeV4Capabilities(assessment, session::getCapabilities)
        reconcileStoredSelection(assessment)
        normalizeStoredSelection()
    }

    private fun TweakerCapabilities.allowsDebugLabOwnerValidation(): Boolean =
        includeLabProfilesInUtility(this, BuildConfig.DEBUG)

    /**
     * Consume the exact preflight ticket once, immediately after IntentSent. This method performs no
     * package inspection, Binder transaction, service start, or hardware work before returning.
     */
    internal fun activateScheduledProfileAfterDispatch(
        attemptId: LaunchAttemptId,
        packageName: String,
        onRuntimeOutcome: suspend (TweakerV4LaunchRuntimeOutcome) -> Unit,
    ): TweakerV4LaunchHandoff {
        if (!sessionReservation.tryBeginOwner()) {
            v4LaunchLifecycle.retireAttempt(attemptId)
            // A busy transport only costs something if there was something to hand off. With no ticket
            // scheduled -- a Stock selection, or a profile already applied system-wide -- the honest
            // answer is NotScheduled, which is silent by design. Reporting Rejected here manufactured a
            // warning about a non-event: measured on Odin, a Stock launch logged
            // `prepared=Skipped` and still came back `Rejected(reason=Tweaker transport is busy)`,
            // while the same no-op with a free transport correctly returned NotScheduled. That is the
            // reported launch-time warning, and it never involved lost
            // tuning.
            //
            // Checked HERE rather than ahead of the reservation on purpose: asking first and acting
            // second would let a ticket arrive in between, and the hand-off would then start a job
            // without owning the transport.
            return busyTransportHandoff(v4LaunchLifecycle.hasPendingLaunch())
        }
        return v4LaunchLifecycle.handOffAfterIntentSent(
            attemptId = attemptId,
            packageName = packageName,
            validate = ::validateV4LaunchTicket,
            startDebugLab = { ticket ->
                session.beginLabProfileHold(ticket.profileId, ticket.packageName)
            },
            onRuntimeOutcome = onRuntimeOutcome,
        ).also { handoff ->
            if (handoff !is TweakerV4LaunchHandoff.Started) sessionReservation.endOwner()
        }
    }

    /** Retire a timed-out/failed preflight so its detached late completion cannot arm another launch. */
    fun abandonPendingLaunch(attemptId: LaunchAttemptId) {
        v4LaunchLifecycle.retireAttempt(attemptId)
        synchronized(preparedActiveProfileLock) {
            if (preparedActiveProfile?.first == attemptId) preparedActiveProfile = null
        }
    }

    private suspend fun validateV4LaunchTicket(ticket: PendingTweakerV4Launch): Boolean =
        withContext(Dispatchers.IO) {
            if (!inspectAndCachePairIdentity().mayBind) return@withContext false
            val caps = capabilities ?: return@withContext false
            when (ticket) {
                is PendingTweakerV4Launch.Product -> {
                    pendingProductTicketStillAdmitted(
                        ticket = ticket,
                        selectedSystemProfileId = selectedProfileId(),
                        selectedStoredFpsPolicy = selectedGameFpsPolicy(ticket.packageName),
                        productProfileIds = selectableTweakerProfileIds(
                            capabilities = caps,
                            includeDiagnosticProfiles = false,
                        ),
                        fpsLimiterAvailable =
                            advancedCapabilities?.perGameOverridesAvailable == true,
                    )
                }
                is PendingTweakerV4Launch.DebugLab ->
                    selectedProfileId() == ticket.profileId &&
                        caps.allowsDebugLabOwnerValidation() &&
                        ticket.profileId in caps.diagnosticProfileIds
            }
        }

    private fun inspectAndCachePairIdentity(): TweakerPairIdentity {
        val identity = classifyTweakerPair(inspectTweakerPairFromPackageManager(appContext))
        cachedPairIdentity = identity
        return identity
    }

    private suspend fun refreshCapabilitiesWhileObserved(): TweakerCapabilities? {
        if (v4LaunchLifecycle.hasPendingLaunch() || v4LaunchLifecycle.mayOwnTransport()) {
            return capabilities
        }
        val pairIdentity = inspectAndCachePairIdentity()
        val bound = executeTweakerBindAttempt(pairIdentity) { session.bind() }
        observationBindFailureLog.lineFor(bound, pairIdentity)?.let { line -> Log.w(TAG_BRIDGE, line) }
        if (!bound) {
            capabilitiesAfterFailedBind(pairIdentity)
            advancedCapabilities = null
            if (!pairIdentity.mayBind) displayedProfileId = STOCK_PROFILE
            telemetry = null
            // The route loses the API version with everything else that was read off this daemon: a
            // failed bind means the version is no longer a fact, and an eligible pair with no version
            // is REFUSE. The pair half is fresh — inspected a line ago — so it is stated, not kept.
            updateDaemonRoutePresence(pairIdentity.mayBind, apiVersion = null, profileSessionActive = null)
            normalizeStoredSelection()
            return null
        }
        return try {
            refreshProjectionFromBoundSession()
        } finally {
            session.unbind()
        }
    }

    /** Read-only projection refresh for a session whose Binder ownership is already established. */
    private fun refreshProjectionFromBoundSession(): TweakerCapabilities? {
        val apiVersion = session.getApiVersion()
        val assessment = if (apiVersion != null && apiVersion >= TWEAKER_V4_MIN_API_VERSION) {
            session.querySessionV4(apiVersion)
        } else {
            null
        }
        capabilities = when {
            apiVersion == null -> null
            apiVersion >= TWEAKER_V4_MIN_API_VERSION -> authoritativeV4Capabilities(
                assessment = checkNotNull(assessment),
                readCapabilities = session::getCapabilities,
            )
            else -> session.getCapabilities()?.withoutLaunchMutationAuthority()
        }
        heldByUnfinishedRestore = assessment?.let(::tweakerSessionHeldByUnfinishedRestore) == true
        // Advanced controls are optional here. During helper recovery their worker can remain busy while the
        // discovered-tuner cold-start read still needs the shared capability executor for getApiVersion.
        // Publish the authoritative route before that optional read, and leave the held state fail-closed.
        if (apiVersion != null) {
            noteDaemonAnswered(apiVersion, namedProfileSessionActive(assessment))
        } else {
            // Bound, and the service would not name a version: the transport is up and the daemon is
            // not answering. REFUSE, and keep whatever the pair inspection last established.
            updateDaemonRoutePresenceKeepingPair(apiVersion = null, profileSessionActive = null)
        }
        advancedCapabilities = if (
            apiVersion != null &&
                apiVersion >= TWEAKER_V6_ADVANCED_CONTROLS_MIN_API_VERSION &&
                !heldByUnfinishedRestore
        ) {
            session.getAdvancedCapabilitiesV6(apiVersion)
        } else {
            null
        }
        assessment?.let(::reconcileStoredSelection)
        telemetry = session.getTelemetry()
        lastFullProjectionAtMs = SystemClock.elapsedRealtime()
        normalizeStoredSelection()
        val row = utilityRowState()
        // The same value the log line reports and the Utility row renders, published for the overlay.
        displayedProfileId = row.effectiveProfileId
        logOnChange(
            "pair",
            "pair=${cachedPairIdentity.state} availability=${row.availability} " +
                // The clause that refused, one level further in than this line used to reach.
                //
                // On 2026-09-04 BOTH handhelds reported `posture=null profiles=[stock]` with the
                // Tweaker installed, bound, permission granted and same-signed. The line below could
                // not say whether the service never answered, answered below V4, or answered and was
                // then refused HERE — three very different faults wearing one output. It was the
                // third: `authoritativeProfileId` requires `state == ACTIVE` and `scope == SYSTEM`,
                // and both devices sat in `state=RESTORING scope=RECOVERY leaseRenewable=false` —
                // a recovery session that never finished, on the Thor 17.6 h past its own lease
                // deadline. Capabilities were dropped whole, so the row fell back to the discovered
                // CPU ceiling and every profile disappeared from the surface with nothing on screen to say why.
                "apiVersion=$apiVersion held=$heldByUnfinishedRestore " +
                // The daemon's own account of why it last handed the hardware back. When a session is
                // held, this is the one field that says WHY the restore that stranded it failed — the
                // evidence a 2026-09-04 clear destroyed before it was read.
                "lastRestore=${advancedCapabilities?.lastRestore?.reason} " +
                "session=${(assessment as? TweakerV4SessionAssessment.Blocked)?.truth?.let {
                    "BLOCKED[state=${it.state} scope=${it.scope} active=${it.activeProfileId} " +
                        "renewable=${it.leaseRenewable}]"
                } ?: assessment?.let { it::class.simpleName } ?: "none"} " +
                // Which CLAUSE refused, not merely that something did. The daemon reported
                // uncertified=[quiet, balanced, performance] on the Thor while this line said
                // profiles=[stock], and reading code could not say which of five conjuncts was false.
                "posture=${capabilities?.posture} status=${capabilities?.status} " +
                "mutation=${capabilities?.mutationAvailable} dryRun=${capabilities?.dryRun} " +
                "uncert=${capabilities?.supportedUncertifiedProfileIds} " +
                "selected=${row.effectiveProfileId} profiles=${row.selectableProfileIds}",
        )
        noteAuthoritativeTruthForStandingRearm()
        return capabilities
    }

    const val STOCK_PROFILE = "stock"
    private const val TAG_BRIDGE = "MimirTweakerBridge"

    /** Long enough that a failing re-arm cannot hammer the service; short enough to feel immediate. */
    private const val STANDING_REARM_WATCH_INTERVAL_MS = 15_000L
    /**
     * Confirm cadence while the choice IS honoured and Mimir is resumed. Slower than the recovery
     * interval on purpose: this is the healthy case, and its only job is to bound how long an
     * externally-caused loss can go unnoticed.
     */
    private const val STANDING_CONFIRM_INTERVAL_MS = 60_000L

    /** How often the open Utility panel re-reads the full projection; its per-second beat reads telemetry alone in between. */
    private const val PANEL_FULL_PROJECTION_INTERVAL_MS = 10_000L
    internal const val STANDING_REARM_WATCH_INTERVAL_MS_FOR_TEST = 15_000L
    internal const val STANDING_CONFIRM_INTERVAL_MS_FOR_TEST = 60_000L
    private const val STANDING_REARM_BASE_BACKOFF_MS = 2_000L
    private const val STANDING_REARM_MAX_BACKOFF_MS = 60_000L
    private const val STANDING_REARM_MAX_BACKOFF_STEPS = 6
    private const val KEY_PROFILE = "profile_id"
    private const val KEY_MANUAL_CURVE_BIAS_PREFIX = "manual_curve_bias:"
    private const val KEY_FAN_MODE_PREFIX = "fan_mode:"
    /** The player's explicit standing choice. See [recordStandingSelection] for why it is not KEY_PROFILE. */
    private const val KEY_STANDING_PROFILE = "standing_profile_id"
    /** Retired app-wide FPS slot; see [discardLegacyGlobalGameFpsSlot]. Never read as policy. */
    private const val KEY_LEGACY_GAME_FPS_LIMIT_HZ = "game_fps_limit_hz"
    private const val KEY_LEGACY_GAME_FPS_POLICY_REVISION = "game_fps_policy_revision"
    private const val DEFAULT_PROFILE = STOCK_PROFILE
    private const val LAB_REQUEST_PREFIX = "lab:"
    private const val V4_LAUNCH_TICKET_MS = 5_000L

    /** How long an already-active profile's post-dispatch confirmation may hold observers out; it runs right after the intent. */
    private const val PREPARED_ACTIVE_CONFIRMATION_WINDOW_MS = 10_000L

    /**
     * How long the launch preflight waits for the reservation: the settlement hand-over budget. An observer yields at
     * its next beat (the overlay's is 1 s) after any Binder read already in flight, so the shorter query timeout
     * could expire before an observer that was about to yield had the chance to.
     */
    internal const val LAUNCH_RESERVATION_WAIT_MS = TweakerSessionReservation.SETTLEMENT_OBSERVER_YIELD_TIMEOUT_MS
    // Odin's Guardian-backed prepare/apply/verify path is consistently ~7.6 s. Keep the
    // synchronous owner request bounded while allowing that proven transaction to settle.
    // Odin fan ownership adds a guarded mode transition and a second CPU-window dwell.
    // This is a cancellation deadline, not an artificial wait; typical completion returns
    // immediately while the 60 s bound covers the measured checked vendor backend.
    private const val SYSTEM_SELECTION_TIMEOUT_MS = 60_000L
    private const val SYSTEM_SELECTION_ADMISSION_ATTEMPTS = 3
    // The first Binder attempt remains immediate. Two bounded backoffs cover a cold helper's former
    // ~300 ms admission window without reintroducing a fixed delay on warm profile switches.
    private const val SYSTEM_SELECTION_ADMISSION_RETRY_MS = 100L
    // Fan mode changes use the same Guardian restore/reapply transaction as a system profile
    // selection. The Odin sequence-5 path is physically measured beyond five seconds, so a shorter
    // caller deadline pre-empts native settlement and forces its correct Stock fallback. This bound
    // admits the already-bounded transaction; it adds no delay and preserves native fail-closed truth.
    internal const val FAN_CONTROL_TIMEOUT_MS = SYSTEM_SELECTION_TIMEOUT_MS

    /** [TweakerSession.bind]'s default timeout; every `session.bind()` on a re-arm's path takes the default. */
    private const val TWEAKER_SESSION_BIND_DEFAULT_TIMEOUT_MS = 2_500L

    /**
     * One capability refresh as [refreshCapabilities] runs it on a re-arm's path: bind, API version, V4 query,
     * capabilities, advanced capabilities and telemetry. Its piggyback branch, a telemetry read and then the same
     * projection refresh without a bind, costs the same.
     */
    private const val STANDING_REARM_CAPABILITY_REFRESH_WORST_CASE_MS =
        TWEAKER_SESSION_BIND_DEFAULT_TIMEOUT_MS + TweakerSession.CAPABILITY_CALL_DEADLINE_MS +
            TweakerSession.DEFAULT_QUERY_TIMEOUT_MS + TweakerSession.CAPABILITY_CALL_DEADLINE_MS +
            TweakerSession.CAPABILITY_CALL_DEADLINE_MS + TweakerSession.TELEMETRY_CALL_DEADLINE_MS

    /**
     * The longest one standing-profile re-arm can take by the bounds its own path sets, from `rearmwatch
     * schedule=dispatched` to the end of its job: 215,325 ms. A display-rate APPLY waits for a re-arm no longer than
     * this from the re-arm's dispatch (O16). The terms, in the order a re-arm meets them:
     * - [rearmStandingSystemProfile] refreshes capabilities when none are cached: 14,000 ms.
     * - `mutateSystemProfile` asks for the exclusive reservation. The ask parks up to 3,000 ms for an observer's
     *   hand-over or for a refusal to clear ([awaitExclusiveOwnerForStandingRearm]) and polls every 25 ms: 3,025 ms.
     * - Bind, API version, V4 query and capabilities before the selection: 9,000 ms.
     * - [SYSTEM_SELECTION_ADMISSION_ATTEMPTS] selections of up to [SYSTEM_SELECTION_TIMEOUT_MS] each, with
     *   [SYSTEM_SELECTION_ADMISSION_RETRY_MS] × 1 and × 2 between them: 180,300 ms.
     * - The reconcile's V4 query and capabilities, then advanced capabilities and telemetry: 9,000 ms.
     *
     * Every daemon call on the path is bounded in the client: the bind by its timeout, the capability,
     * advanced-capability and telemetry reads by their own deadlines, and, since round 5e, the V4 query and each
     * selection at the deadline they hand the daemon (`TweakerSession.deadlineBoundedCall`). Before round 5e those
     * two were not, and a daemon that ignored their deadline could hold a re-arm, and the reservation, past this
     * bound. The package-manager reads and the service start in `beginActiveSessionV4Async` are local calls with no
     * bound of their own and are not counted. For scale: 22 re-arms on record took 4.45 to 11.0 s from dispatch to
     * result, build 81429bdf's took 11.6 s, and build 73ea03e3's took 25.9 s (Thor, 2026-09-12).
     */
    internal const val STANDING_REARM_WORST_CASE_MS =
        STANDING_REARM_CAPABILITY_REFRESH_WORST_CASE_MS +
            TweakerSessionReservation.SETTLEMENT_OBSERVER_YIELD_TIMEOUT_MS +
            TweakerSessionReservation.SETTLEMENT_OBSERVER_YIELD_POLL_MS +
            TWEAKER_SESSION_BIND_DEFAULT_TIMEOUT_MS + TweakerSession.CAPABILITY_CALL_DEADLINE_MS +
            TweakerSession.DEFAULT_QUERY_TIMEOUT_MS + TweakerSession.CAPABILITY_CALL_DEADLINE_MS +
            SYSTEM_SELECTION_ADMISSION_ATTEMPTS * SYSTEM_SELECTION_TIMEOUT_MS +
            SYSTEM_SELECTION_ADMISSION_RETRY_MS *
            (SYSTEM_SELECTION_ADMISSION_ATTEMPTS * (SYSTEM_SELECTION_ADMISSION_ATTEMPTS - 1) / 2) +
            TweakerSession.DEFAULT_QUERY_TIMEOUT_MS + TweakerSession.CAPABILITY_CALL_DEADLINE_MS +
            TweakerSession.CAPABILITY_CALL_DEADLINE_MS + TweakerSession.TELEMETRY_CALL_DEADLINE_MS

    private val InertGameFpsPolicy = TweakerGameFpsPolicy(frameLimitHz = null, revision = 0L)
}

/** Durable per-package FPS selection identity used to reject a stale one-shot launch ticket. */
internal data class TweakerGameFpsPolicy(
    val frameLimitHz: Int?,
    val revision: Long,
) {
    init {
        require(frameLimitHz == null || frameLimitHz in 30..120)
        require(revision >= 0L)
    }
}

/**
 * Preference key for one package's frame-limit policy. The prefix shares no name with the retired
 * app-wide slot, so a legacy value can never be read back as any package's cap.
 */
internal fun gameFpsPolicyPreferenceKey(packageName: String): String? = packageName.trim()
    .takeIf(GAME_FPS_POLICY_PACKAGE_NAME::matches)
    ?.let { GAME_FPS_POLICY_KEY_PREFIX + it }

internal fun encodeTweakerGameFpsPolicy(policy: TweakerGameFpsPolicy): String = listOf(
    GAME_FPS_POLICY_SCHEMA_VERSION.toString(),
    policy.frameLimitHz?.toString().orEmpty(),
    policy.revision.toString(),
).joinToString(GAME_FPS_POLICY_SEPARATOR)

internal fun decodeTweakerGameFpsPolicy(raw: String): TweakerGameFpsPolicy? = runCatching {
    val parts = raw.split(GAME_FPS_POLICY_SEPARATOR)
    require(parts.size == 3 && parts[0].toInt() == GAME_FPS_POLICY_SCHEMA_VERSION)
    val frameLimit = parts[1].takeIf(String::isNotEmpty)?.toInt()
    require(frameLimit == null || frameLimit in GAME_FPS_LIMIT_VALUES)
    TweakerGameFpsPolicy(frameLimitHz = frameLimit, revision = parts[2].toLong())
}.getOrNull()

/** Revision stays monotonic per package; a saturated revision refuses further writes. */
internal fun nextTweakerGameFpsPolicy(
    previous: TweakerGameFpsPolicy,
    frameLimitHz: Int?,
): TweakerGameFpsPolicy? = if (previous.revision == Long.MAX_VALUE) {
    null
} else {
    TweakerGameFpsPolicy(frameLimitHz = frameLimitHz, revision = previous.revision + 1L)
}

internal const val GAME_FPS_POLICY_KEY_PREFIX = "game_fps_policy."
private const val GAME_FPS_POLICY_SCHEMA_VERSION = 1
private const val GAME_FPS_POLICY_SEPARATOR = "|"
private val GAME_FPS_LIMIT_VALUES = setOf(30, 40, 60, 90, 120)
private val GAME_FPS_POLICY_PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

internal fun confirmedPreparedActiveProfile(
    expectedProfileId: String,
    assessment: TweakerV4SessionAssessment,
    observedCapabilities: TweakerCapabilities?,
): String? = expectedProfileId.takeIf { expected ->
    expected != TweakerBridge.STOCK_PROFILE &&
        observedCapabilities?.status?.isReachable() == true &&
        authoritativeProfileId(assessment) == expected
}

internal fun freshUtilityFanControl(
    telemetry: TweakerTelemetry?,
    selectedProfileId: String,
    nowElapsedRealtimeMs: Long,
): TweakerFanControlReadout? {
    if (selectedProfileId == TweakerBridge.STOCK_PROFILE) return null
    val sample = telemetry ?: return null
    val sampledAt = sample.sampledAtElapsedRealtimeMs ?: return null
    val age = nowElapsedRealtimeMs - sampledAt
    if (age !in 0..UTILITY_FAN_TELEMETRY_MAX_AGE_MS) return null
    return sample.fanControl?.takeIf { it.profileId == selectedProfileId }
}

/**
 * Live GPU temperature for the Utility PROFILE row's fan readout, gated by the same freshness
 * window as [freshUtilityFanControl] so a stale sample never paints a number nobody can trust.
 *
 * GPU, not package/CPU temperature: under `FanThermalStrategy.AutoCurve` (which both the AUTO and
 * MANUAL/biased fan selections resolve to — see `AdvancedFanControl.kt`'s `strategy()`), the curve's
 * demand temperature is `gpuTemp` (`FanThermalControl.kt:345`). The package-temperature sensor is
 * retained only for the independent 84C full-fan safety intervention, not for driving the curve, so
 * GPU is the number that actually explains what the fan is doing right now.
 */
internal fun freshUtilityFanGpuTempC(
    telemetry: TweakerTelemetry?,
    nowElapsedRealtimeMs: Long,
): Int? {
    val sample = telemetry ?: return null
    val sampledAt = sample.sampledAtElapsedRealtimeMs ?: return null
    val age = nowElapsedRealtimeMs - sampledAt
    if (age !in 0..UTILITY_FAN_TELEMETRY_MAX_AGE_MS) return null
    return sample.gpuTempC
}

/**
 * Live CPU temperature for the fan-control section's temperature readout, gated by the same
 * freshness window as [freshUtilityFanGpuTempC].
 *
 * Hottest core (`cpuTempC`), not the package mean (`cpuPackageTempC`) — policy 2026-08-22,
 * overriding the design spec's original mean-for-parity recommendation: a mean over classified
 * thermal zones can sit ~30 °C below the hottest core (measured on the Odin: package
 * comfortable while cores read 89.9 °C), and a comfort readout that smooths away the hot cluster
 * under-reports exactly the moment it exists for. Accepted cost: the number is jumpier.
 * [performanceOverlayCpuTemperature] moves to the same field in this same change so the fan readout
 * and the performance overlay can never show different CPU temperatures at the same moment.
 */
internal fun freshUtilityFanCpuTempC(
    telemetry: TweakerTelemetry?,
    nowElapsedRealtimeMs: Long,
): Int? {
    val sample = telemetry ?: return null
    val sampledAt = sample.sampledAtElapsedRealtimeMs ?: return null
    val age = nowElapsedRealtimeMs - sampledAt
    if (age !in 0..UTILITY_FAN_TELEMETRY_MAX_AGE_MS) return null
    return sample.cpuTempC
}

private const val UTILITY_FAN_TELEMETRY_MAX_AGE_MS = 3_500L

internal class TweakerTelemetryObservation(
    private val readTelemetry: () -> TweakerTelemetry?,
    private val shouldYield: () -> Boolean = { false },
    private val release: () -> Unit = {},
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun read(): TweakerTelemetry? = if (closed.get() || shouldYield()) null else readTelemetry()

    fun yieldRequested(): Boolean = !closed.get() && shouldYield()

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

/**
 * Runs [open] on [context] and hands the resource it returns to the caller, closing it if the caller was cancelled on the way.
 *
 * `withContext` discards its result and throws when the calling coroutine is cancelled before the result is delivered. A
 * resource [open] had already created, and which only the caller would ever close, was then dropped on the floor: for the
 * Tweaker's telemetry observation that is the sole Binder reservation and its binding, held for ever, with nobody left to
 * yield it. The overlay's mode changes cancel an in-flight open by design (`collectLatest`), so a few quick steps on the
 * overlay row could leave every later profile selection answering Busy after its three-second hand-over wait (Nova
 * 2026-10-01 22:08-22:09: key press to Busy 3.01 s, twice, with the overlay stepped seven times in the six seconds before).
 */
internal suspend fun <T : AutoCloseable> handOffObservation(
    context: kotlin.coroutines.CoroutineContext,
    open: suspend () -> T?,
): T? {
    val opened = java.util.concurrent.atomic.AtomicReference<T?>(null)
    try {
        return withContext(context) { open()?.also { opened.set(it) } }
    } catch (cancelled: CancellationException) {
        opened.getAndSet(null)?.close()
        throw cancelled
    }
}

/**
 * Process-local reservation for the sole Tweaker Binder token. The monitor protects only these three
 * enum transitions; no package-manager or Tweaker Binder call runs while it is held.
 */
internal class TweakerSessionReservation {
    private enum class Use { IDLE, OBSERVATION, OWNER }
    private enum class OwnerKind { NONE, GENERIC, SETTLEMENT, MUTATION, PREPARATION }
    private enum class Admission { ACQUIRED, WAITING, REJECTED }

    private val lock = Any()
    private var use = Use.IDLE
    private var ownerKind = OwnerKind.NONE
    private var transferRequest = OwnerKind.NONE

    /**
     * How many live holders the OWNER reservation has.
     *
     * A settlement read MAY join a generic owner ([requestOwner]'s `mayJoinGenericOwner` arm) — that is
     * the whole point of the settlement kind, and put the discovered-record read
     * on it. But a join used to be invisible: it returned ACQUIRED without recording anything, so the
     * joiner's own `finally { endOwner() }` was indistinguishable from the owner's and reset the
     * reservation to IDLE **while the owner was still using it**. `readDiscoveredSessionAtBind` joins
     * `confirmProcessStartOnce`'s generic owner at cold start and did exactly this, releasing that
     * owner's reservation mid-query.
     *
     * The depth makes acquired-from-joined distinguishable without either caller having to know which it
     * was: the reservation is released when the LAST holder leaves, and only then.
     *
     * Not a semaphore, and deliberately not one — this counts holders of a token that is already
     * serialised by [lock]; it never admits a holder that the state machine would have rejected.
     */
    private var ownerDepth = 0

    /** Nobody holds the token and no hand-over is parked: an observation opened now would be admitted. */
    fun isIdle(): Boolean = synchronized(lock) { use == Use.IDLE && transferRequest == OwnerKind.NONE }

    fun tryBeginObservation(): Boolean = synchronized(lock) {
        if (use != Use.IDLE || transferRequest != OwnerKind.NONE) {
            false
        } else {
            use = Use.OBSERVATION
            true
        }
    }

    fun endObservation() {
        synchronized(lock) {
            if (use == Use.OBSERVATION) {
                if (transferRequest == OwnerKind.NONE) {
                    use = Use.IDLE
                    ownerKind = OwnerKind.NONE
                } else {
                    // The waiter this observation yields to becomes the reservation's first holder.
                    use = Use.OWNER
                    ownerKind = transferRequest
                    ownerDepth = 1
                }
            }
        }
    }

    fun tryBeginOwner(): Boolean = synchronized(lock) {
        if (use != Use.IDLE) {
            false
        } else {
            use = Use.OWNER
            ownerKind = OwnerKind.GENERIC
            ownerDepth = 1
            true
        }
    }

    /**
     * Release one holder of the OWNER reservation; the reservation itself ends when the last one leaves.
     *
     * A caller cannot tell whether its own acquire took the token or joined someone else's, and it does
     * not have to: it pairs its acquire with exactly one call here either way. Unbalanced calls (a call
     * with no live owner) stay the no-op they have always been.
     *
     * @return true when this call released the LAST holder and the transport is now free. Callers that
     * may have joined someone else's session use this to decide whether tearing the Binder connection
     * down is theirs to do — see [TweakerBridge.readDiscoveredSessionAtBind]. Callers that cannot join
     * ignore it.
     */
    fun endOwner(): Boolean = synchronized(lock) {
        if (use == Use.OWNER) {
            ownerDepth -= 1
            if (ownerDepth <= 0) {
                use = Use.IDLE
                ownerKind = OwnerKind.NONE
                transferRequest = OwnerKind.NONE
                ownerDepth = 0
                true
            } else {
                false
            }
        } else {
            false
        }
    }

    fun reserveOwnerForSettlement(): Boolean =
        requestOwner(OwnerKind.SETTLEMENT, mayJoinGenericOwner = true) == Admission.ACQUIRED

    fun shouldYieldObservation(): Boolean = synchronized(lock) {
        use == Use.OBSERVATION && transferRequest != OwnerKind.NONE
    }

    /**
     * True while an explicit mutation owns the transport, or has asked the current observer to yield
     * so it can take over.
     *
     * Read-only observation work may piggyback a binding someone else holds — that cannot race the
     * holder at the *service*. But it can still race them in the *projection*: a refresh rewrites
     * `capabilities`/`telemetry` and can persist a transitional Stock through `reconcileStoredSelection`
     * while a Stock-first profile switch is mid-flight. So the standing re-arm watcher asks this before
     * refreshing, and stands off for a tick rather than reading a profile change halfway through itself.
     */
    fun mutationMayBeInFlight(): Boolean = synchronized(lock) {
        ownerKind == OwnerKind.MUTATION || transferRequest == OwnerKind.MUTATION
    }

    private fun requestOwner(
        requestedKind: OwnerKind,
        mayJoinGenericOwner: Boolean,
    ): Admission = synchronized(lock) {
        when (use) {
            Use.IDLE -> {
                use = Use.OWNER
                ownerKind = requestedKind
                transferRequest = OwnerKind.NONE
                ownerDepth = 1
                Admission.ACQUIRED
            }
            Use.OWNER -> if (
                mayJoinGenericOwner &&
                (ownerKind == OwnerKind.GENERIC || ownerKind == requestedKind)
            ) {
                // A JOIN, and it is counted. `ownerKind` deliberately does NOT move to
                // `requestedKind`: the reservation still describes the owner that took it, which is
                // what `mutationMayBeInFlight` reads to fence the standing re-arm watcher. A join that
                // renamed the owner would let a settlement read clear that fence under a live mutation.
                ownerDepth += 1
                Admission.ACQUIRED
            } else {
                Admission.REJECTED
            }
            Use.OBSERVATION -> if (transferRequest == OwnerKind.NONE) {
                transferRequest = requestedKind
                Admission.WAITING
            } else {
                Admission.REJECTED
            }
        }
    }

    private fun claimTransferredOwner(requestedKind: OwnerKind): Boolean = synchronized(lock) {
        if (use == Use.OWNER && ownerKind == requestedKind && transferRequest == requestedKind) {
            transferRequest = OwnerKind.NONE
            true
        } else {
            false
        }
    }

    private fun cancelOwnerRequest(requestedKind: OwnerKind): Boolean = synchronized(lock) {
        when {
            use == Use.OWNER && ownerKind == requestedKind && transferRequest == requestedKind -> {
                transferRequest = OwnerKind.NONE
                true
            }
            use == Use.OBSERVATION && transferRequest == requestedKind -> {
                transferRequest = OwnerKind.NONE
                false
            }
            else -> false
        }
    }

    private fun abandonOwnerRequest(requestedKind: OwnerKind) {
        synchronized(lock) {
            when {
                use == Use.OWNER && ownerKind == requestedKind && transferRequest == requestedKind -> {
                    // This waiter was handed the token and died before claiming it. Release ITS hold
                    // only. A hard reset here would reopen the very hazard the depth closes, by a
                    // narrower door: `endObservation` transfers without clearing `transferRequest`
                    // (only `claimTransferredOwner` clears it), so a second settlement can join this
                    // owner while the request is still standing, and this branch would then free a
                    // reservation that joiner is mid-query on.
                    transferRequest = OwnerKind.NONE
                    ownerDepth -= 1
                    if (ownerDepth <= 0) {
                        use = Use.IDLE
                        ownerKind = OwnerKind.NONE
                        ownerDepth = 0
                    }
                }
                use == Use.OBSERVATION && transferRequest == requestedKind -> {
                    transferRequest = OwnerKind.NONE
                }
            }
        }
    }

    suspend fun awaitOwnerForSettlement(
        timeoutMs: Long = SETTLEMENT_OBSERVER_YIELD_TIMEOUT_MS,
        elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
        wait: suspend (Long) -> Unit = { delay(it) },
    ): Boolean = awaitOwner(
        requestedKind = OwnerKind.SETTLEMENT,
        mayJoinGenericOwner = true,
        timeoutMs = timeoutMs,
        elapsedRealtimeMs = elapsedRealtimeMs,
        wait = wait,
    )

    suspend fun awaitExclusiveOwnerForMutation(
        timeoutMs: Long = SETTLEMENT_OBSERVER_YIELD_TIMEOUT_MS,
        elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
        wait: suspend (Long) -> Unit = { delay(it) },
    ): Boolean = awaitOwner(
        requestedKind = OwnerKind.MUTATION,
        mayJoinGenericOwner = false,
        timeoutMs = timeoutMs,
        elapsedRealtimeMs = elapsedRealtimeMs,
        wait = wait,
    )

    /**
     * A live in-game mutation claims the token as a generic owner — the kind a later return
     * settlement may join — because a successful live frame limit keeps the transport owned for the
     * rest of the game exactly like a launch-armed one. It still never joins an existing owner.
     */
    suspend fun awaitGenericOwnerForLiveMutation(
        timeoutMs: Long = SETTLEMENT_OBSERVER_YIELD_TIMEOUT_MS,
        elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
        wait: suspend (Long) -> Unit = { delay(it) },
    ): Boolean = awaitOwner(
        requestedKind = OwnerKind.GENERIC,
        mayJoinGenericOwner = false,
        timeoutMs = timeoutMs,
        elapsedRealtimeMs = elapsedRealtimeMs,
        wait = wait,
    )

    /**
     * The launch preflight's claim on the token. It is the senior caller: whoever holds the reservation as an
     * OBSERVATION -- a settling refresh, or the performance overlay's long-lived telemetry -- is asked to hand it
     * over, exactly as a settlement or a mutation asks, and the preflight becomes its sole holder.
     *
     * **Why the launch asks instead of waiting.** The overlay's observation is held for as long as the overlay
     * is enabled, so a wait for it to free could never succeed and the preflight used to refuse it outright
     * (`Skipped(reason=Tweaker owner is still settling)`, Nova 2026-10-01 09:41:35 with the overlay on ALWAYS):
     * the selected profile was never applied. An observer already polls [shouldYieldObservation] each beat and
     * closes when it is true, so asking is enough and nothing is torn out from under it.
     *
     * An OWNER already on the token (a previous game's, a mutation) is not asked to leave. The request is
     * retried each poll until [timeoutMs], because that owner ends by itself and a request that arrived a
     * moment early should not cost the launch its profile. The kind never joins another owner
     * ([OwnerKind.PREPARATION] is not joinable), so a settlement read cannot ride the preflight's binding and
     * lose it when the preflight unbinds.
     *
     * The caller releases with [endOwner], not [endObservation].
     */
    suspend fun awaitOwnerForLaunchPreparation(
        timeoutMs: Long,
        elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
        wait: suspend (Long) -> Unit = { delay(it) },
    ): Boolean {
        val bound = timeoutMs.coerceAtLeast(0L)
        val startedAt = elapsedRealtimeMs()
        while (true) {
            when (requestOwner(OwnerKind.PREPARATION, mayJoinGenericOwner = false)) {
                Admission.ACQUIRED -> return true
                Admission.WAITING -> return awaitTransfer(
                    requestedKind = OwnerKind.PREPARATION,
                    timeoutMs = (bound - (elapsedRealtimeMs() - startedAt)).coerceAtLeast(0L),
                    elapsedRealtimeMs = elapsedRealtimeMs,
                    wait = wait,
                )
                Admission.REJECTED -> Unit
            }
            if (elapsedRealtimeMs() - startedAt >= bound) return false
            wait(SETTLEMENT_OBSERVER_YIELD_POLL_MS)
        }
    }

    private suspend fun awaitOwner(
        requestedKind: OwnerKind,
        mayJoinGenericOwner: Boolean,
        timeoutMs: Long,
        elapsedRealtimeMs: () -> Long,
        wait: suspend (Long) -> Unit,
    ): Boolean {
        when (requestOwner(requestedKind, mayJoinGenericOwner)) {
            Admission.ACQUIRED -> return true
            Admission.REJECTED -> return false
            Admission.WAITING -> Unit
        }
        return awaitTransfer(requestedKind, timeoutMs, elapsedRealtimeMs, wait)
    }

    /** Polls for the hand-over a [requestOwner] WAITING answer asked for; false only if still held at the deadline. */
    private suspend fun awaitTransfer(
        requestedKind: OwnerKind,
        timeoutMs: Long,
        elapsedRealtimeMs: () -> Long,
        wait: suspend (Long) -> Unit,
    ): Boolean {
        try {
            val startedAt = elapsedRealtimeMs()
            while (elapsedRealtimeMs() - startedAt < timeoutMs.coerceAtLeast(0L)) {
                if (claimTransferredOwner(requestedKind)) return true
                wait(SETTLEMENT_OBSERVER_YIELD_POLL_MS)
            }
            return cancelOwnerRequest(requestedKind)
        } catch (failure: Throwable) {
            // Release only this waiter's transfer; never touch another owner/request kind.
            abandonOwnerRequest(requestedKind)
            throw failure
        }
    }

    /** Atomically gives exact-Stock return observation the token formerly reserved by the owner. */
    fun transitionOwnerToObservation(): Boolean = synchronized(lock) {
        when (use) {
            Use.OWNER, Use.IDLE -> {
                // Collapses every owner holder, not just this one: the return path takes the token
                // over wholesale and the single `endObservation` that follows is its only release.
                use = Use.OBSERVATION
                ownerKind = OwnerKind.NONE
                transferRequest = OwnerKind.NONE
                ownerDepth = 0
                true
            }
            Use.OBSERVATION -> false
        }
    }


    /** The reservation's state in one line, for the log a refused standing re-arm writes. */
    fun describe(): String = synchronized(lock) {
        "use=$use owner=$ownerKind handover=$transferRequest depth=$ownerDepth"
    }

    /** [TweakerBridge.STANDING_REARM_WORST_CASE_MS] counts one hand-over wait from these. */
    companion object {
        const val SETTLEMENT_OBSERVER_YIELD_TIMEOUT_MS = 3_000L
        const val SETTLEMENT_OBSERVER_YIELD_POLL_MS = 25L
    }
}

/** What the launch preflight's reservation ask did: whether it now owns the token, and for how long it waited. */
internal data class LaunchReservationAdmission(val acquired: Boolean, val waitedMs: Long)

/**
 * The launch preflight's admission to the Tweaker reservation. Off the main thread only (the caller is on
 * [Dispatchers.IO]). The launch is the senior caller: a free token is taken at once, and a token held as an
 * observation -- a settling refresh or the performance overlay's standing telemetry -- is asked to hand over
 * (see [TweakerSessionReservation.awaitOwnerForLaunchPreparation]), bounded by [timeoutMs]. The caller releases
 * what it acquired with [TweakerSessionReservation.endOwner].
 */
internal suspend fun awaitLaunchReservation(
    reservation: TweakerSessionReservation,
    timeoutMs: Long = TweakerBridge.LAUNCH_RESERVATION_WAIT_MS,
    elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    wait: suspend (Long) -> Unit = { delay(it) },
): LaunchReservationAdmission {
    val startedAt = elapsedRealtimeMs()
    val acquired = reservation.awaitOwnerForLaunchPreparation(timeoutMs, elapsedRealtimeMs, wait)
    return LaunchReservationAdmission(acquired, elapsedRealtimeMs() - startedAt)
}

internal data class TweakerLaunchPreparation(
    val attemptId: LaunchAttemptId,
    val result: TweakerPreparationResult,
    val selection: TweakerLaunchSelectionContext,
)

/** Platform pair-admission states for the installed Mimir/Tweaker contract. */
enum class TweakerPairState {
    UNCHECKED,
    ABSENT,
    PACKAGE_MANAGER_ERROR,
    SIGNATURE_MISMATCH,
    SERVICE_MISSING,
    SERVICE_INCOMPATIBLE,
    COMPONENT_DISABLED,
    CONTROL_PERMISSION_DENIED,
    ELIGIBLE,
}

/** Pure pair facts the Utility row and bind gate read from. */
data class TweakerPairIdentity(
    val state: TweakerPairState,
) {
    val mayBind: Boolean get() = state == TweakerPairState.ELIGIBLE

    /**
     * Installed truth for UI projection: null while unchecked or PackageManager is uncertain, false only
     * when the Tweaker package is genuinely absent, true for every other inspected state.
     */
    val installedTruth: Boolean?
        get() = when (state) {
            TweakerPairState.UNCHECKED,
            TweakerPairState.PACKAGE_MANAGER_ERROR,
            -> null
            TweakerPairState.ABSENT -> false
            else -> true
        }
}

internal sealed interface TweakerPackageLookupResult {
    data object Absent : TweakerPackageLookupResult
    data object PackageManagerError : TweakerPackageLookupResult
    data class Present(
        val applicationEnabled: Boolean,
        val service: TweakerServiceContractFacts?,
        val componentEffectivelyEnabled: Boolean,
    ) : TweakerPackageLookupResult
}

internal data class TweakerServiceContractFacts(
    val exported: Boolean,
    val permission: String?,
)

internal data class TweakerPairInspectionFacts(
    val packageLookup: TweakerPackageLookupResult,
    val signatureResult: Int?,
    val controlPermissionGranted: Boolean?,
)

internal fun classifyTweakerPair(facts: TweakerPairInspectionFacts): TweakerPairIdentity {
    return when (val lookup = facts.packageLookup) {
        TweakerPackageLookupResult.Absent -> TweakerPairIdentity(TweakerPairState.ABSENT)
        TweakerPackageLookupResult.PackageManagerError ->
            TweakerPairIdentity(TweakerPairState.PACKAGE_MANAGER_ERROR)
        is TweakerPackageLookupResult.Present -> classifyPresentTweakerPackage(facts, lookup)
    }
}

private fun classifyPresentTweakerPackage(
    facts: TweakerPairInspectionFacts,
    present: TweakerPackageLookupResult.Present,
): TweakerPairIdentity {
    when (facts.signatureResult) {
        PackageManager.SIGNATURE_MATCH -> Unit
        PackageManager.SIGNATURE_UNKNOWN_PACKAGE, null ->
            return TweakerPairIdentity(TweakerPairState.PACKAGE_MANAGER_ERROR)
        else -> return TweakerPairIdentity(TweakerPairState.SIGNATURE_MISMATCH)
    }

    val service = present.service
        ?: return TweakerPairIdentity(TweakerPairState.SERVICE_MISSING)
    if (!service.exported || service.permission != TweakerSession.CONTROL_PERMISSION) {
        return TweakerPairIdentity(TweakerPairState.SERVICE_INCOMPATIBLE)
    }
    if (!present.componentEffectivelyEnabled) {
        return TweakerPairIdentity(TweakerPairState.COMPONENT_DISABLED)
    }
    if (facts.controlPermissionGranted == null) {
        return TweakerPairIdentity(TweakerPairState.PACKAGE_MANAGER_ERROR)
    }
    if (!facts.controlPermissionGranted) {
        return TweakerPairIdentity(TweakerPairState.CONTROL_PERMISSION_DENIED)
    }
    return TweakerPairIdentity(TweakerPairState.ELIGIBLE)
}

internal fun isTweakerServiceComponentEffectivelyEnabled(
    componentEnabledSetting: Int,
    applicationEnabled: Boolean,
    manifestComponentEnabled: Boolean,
): Boolean {
    if (!applicationEnabled) return false
    return when (componentEnabledSetting) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
        -> false
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> manifestComponentEnabled
        else -> false
    }
}

internal fun <T> inspectTweakerPackageLookup(
    readPackage: () -> T,
    inspectPresent: (T) -> TweakerPackageLookupResult.Present,
): TweakerPackageLookupResult {
    val packageInfo = try {
        readPackage()
    } catch (_: PackageManager.NameNotFoundException) {
        return TweakerPackageLookupResult.Absent
    } catch (_: Exception) {
        return TweakerPackageLookupResult.PackageManagerError
    }
    return try {
        inspectPresent(packageInfo)
    } catch (_: Exception) {
        // A package found above is not reclassified as absent if a later component read races or fails.
        TweakerPackageLookupResult.PackageManagerError
    }
}

internal fun inspectTweakerPairFromPackageManager(context: Context): TweakerPairInspectionFacts {
    val packageManager = context.packageManager
    val tweakerPackage = TweakerSession.TWEAKER_PACKAGE
    val tweakerService = TweakerSession.TWEAKER_SERVICE
    val flags = PackageManager.GET_SERVICES or PackageManager.MATCH_DISABLED_COMPONENTS
    val packageLookup = inspectTweakerPackageLookup(
        readPackage = { packageManager.getPackageInfo(tweakerPackage, flags) },
    ) { packageInfo ->
        val applicationEnabled = packageInfo.applicationInfo?.enabled == true
        val serviceInfo = packageInfo.services?.firstOrNull { it.name == tweakerService }
        val serviceFacts = serviceInfo?.let {
            TweakerServiceContractFacts(
                exported = it.exported,
                permission = it.permission,
            )
        }
        val componentEffectivelyEnabled = serviceInfo?.let {
            isTweakerServiceComponentEffectivelyEnabled(
                componentEnabledSetting = packageManager.getComponentEnabledSetting(
                    ComponentName(tweakerPackage, tweakerService),
                ),
                applicationEnabled = applicationEnabled,
                manifestComponentEnabled = it.enabled,
            )
        } ?: false
        TweakerPackageLookupResult.Present(
            applicationEnabled = applicationEnabled,
            service = serviceFacts,
            componentEffectivelyEnabled = componentEffectivelyEnabled,
        )
    }

    val signatureResult = when (packageLookup) {
        is TweakerPackageLookupResult.Present -> try {
                packageManager.checkSignatures(context.packageName, tweakerPackage)
            } catch (_: Exception) {
                null
            }
        else -> null
    }
    val controlPermissionGranted = when (packageLookup) {
        is TweakerPackageLookupResult.Present -> try {
            packageManager.checkPermission(
                TweakerSession.CONTROL_PERMISSION,
                context.packageName,
            ) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            null
        }
        else -> null
    }

    return TweakerPairInspectionFacts(
        packageLookup = packageLookup,
        signatureResult = signatureResult,
        controlPermissionGranted = controlPermissionGranted,
    )
}

/** Shared bind gate: only [TweakerPairIdentity.mayBind] may invoke [bind] exactly once. */
internal suspend fun executeTweakerBindAttempt(
    pairIdentity: TweakerPairIdentity,
    bind: suspend () -> Boolean,
): Boolean {
    if (!pairIdentity.mayBind) return false
    return bind()
}

/** What one standing re-arm's reservation ask came to. [refusedBy] is the reservation's state at its first refusal. */
internal data class StandingRearmReservation(
    val acquired: Boolean,
    val refusedBy: String?,
    val waitedMs: Long,
)

/**
 * The standing re-arm's exclusive reservation ask. It waits out a refusal instead of answering Busy at once.
 *
 * **The Busy this closes (round 5c, O16: found, not fixed there).** A re-arm is dispatched from inside a projection
 * refresh that still holds the reservation as an observation, and the reservation keeps one hand-over slot per
 * observation. When a settlement read is already parked in that slot, an exclusive request is refused at once
 * ([TweakerSessionReservation.awaitExclusiveOwnerForMutation]), and `mutateSystemProfile` answered Busy. All five
 * round-5c O16 runs show `rearm standing=quiet exit=attempted result=Busy` 1–2 ms after `rearmwatch
 * schedule=dispatched`; those streams did not capture the `MimirTuner` tag, so which read it was on those runs is not
 * in evidence. On the Thor on 2026-09-11 (a recorded log excerpt) the same 1–2 ms Busy was followed 46–62 ms later by the tuner cold-start reconcile's daemon
 * query (`MimirTuner: route query outcome=NOT_HELD`), on the thread that had queued it:
 * `DaemonTunerActuator.query()` asks for the settlement reservation, so that read was the one parked in the slot.
 *
 * A Busy cost the standing profile a step on the backoff ladder and left it at Stock until something refreshed the
 * projection again: on build 81429bdf the watch had just set its 60 s confirm interval, and the next dispatch came only
 * when Utility opened, 24 s later (15:00:14.873 → 15:00:38.866). A player's press is still answered at once, because
 * the player re-asks by pressing again; nobody re-asks for the re-arm.
 *
 * **Bounded by the hand-over budget the re-arm already had.** The ask ends within [budgetMs] plus one
 * [TweakerSessionReservation.SETTLEMENT_OBSERVER_YIELD_POLL_MS] of its first ask: the 3,025 ms term
 * [TweakerBridge.STANDING_REARM_WORST_CASE_MS] already counts. Nothing is held between asks, and a launch that
 * takes the transport ends the wait at once.
 */
internal suspend fun awaitExclusiveOwnerForStandingRearm(
    reservation: TweakerSessionReservation,
    launchOwnsTransport: () -> Boolean,
    budgetMs: Long = TweakerSessionReservation.SETTLEMENT_OBSERVER_YIELD_TIMEOUT_MS,
    elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    wait: suspend (Long) -> Unit = { delay(it) },
): StandingRearmReservation {
    val startedAt = elapsedRealtimeMs()
    var refusedBy: String? = null
    while (true) {
        val remainingMs = budgetMs - (elapsedRealtimeMs() - startedAt)
        if (refusedBy != null && (remainingMs <= 0L || launchOwnsTransport())) {
            return StandingRearmReservation(false, refusedBy, elapsedRealtimeMs() - startedAt)
        }
        val acquired = reservation.awaitExclusiveOwnerForMutation(
            timeoutMs = remainingMs.coerceAtLeast(0L),
            elapsedRealtimeMs = elapsedRealtimeMs,
            wait = wait,
        )
        if (acquired) return StandingRearmReservation(true, refusedBy, elapsedRealtimeMs() - startedAt)
        // Refused at once, or handed nothing in what was left of the budget. The first refusal names the holder.
        if (refusedBy == null) refusedBy = reservation.describe()
        if (budgetMs - (elapsedRealtimeMs() - startedAt) <= 0L) {
            return StandingRearmReservation(false, refusedBy, elapsedRealtimeMs() - startedAt)
        }
        wait(TweakerSessionReservation.SETTLEMENT_OBSERVER_YIELD_POLL_MS)
    }
}

/**
 * Round-5 B6, Thor, 2026-09-11 18:36:30. One failed observation bind cleared cached daemon capabilities
 * and an APPLY refused on that stale cache, and nothing on the bind path logged why.
 * The Utility retries that observation every second while it is open, so this speaks once per transition
 * from binding to failing and stays quiet until a bind succeeds again. There is no throwable to print: the
 * bind reports a Boolean.
 */
internal class TweakerObservationBindFailureLog {
    private var failing = false

    /** The line to log for this observation bind outcome, or null when there is nothing new to say. */
    @Synchronized
    fun lineFor(bound: Boolean, pairIdentity: TweakerPairIdentity): String? {
        if (bound) {
            failing = false
            return null
        }
        if (failing) return null
        failing = true
        // A refused pair never reaches TweakerSession.bind; a bind that ran returned false. Which of bind's own
        // failures that was (timeout, bindService refused, a null or dead binding) is not visible from here.
        val failure = if (pairIdentity.mayBind) "bind_returned_false" else "pair_refused"
        return "observation bind failed mayBind=${pairIdentity.mayBind} failure=$failure pair=${pairIdentity.state}"
    }
}

internal fun capabilitiesAfterTweakerBindAttempt(
    connected: Boolean,
    cached: TweakerCapabilities?,
): TweakerCapabilities? = cached.takeIf { connected }

/**
 * Whether to read the projection off a binding somebody else owns.
 *
 * `TweakerBridge` is an object with no injection seam, which is why the ordering this guards could
 * sit there unnoticed: the decision had no way to be tested. It is lifted out here so it does.
 *
 * True only in the state that is otherwise unrecoverable — capabilities have NEVER been read, the
 * reservation is held elsewhere so the normal refresh has already given up, and a binding
 * nonetheless exists to read from. Once anything is cached this returns false and the reservation
 * holder's ordering is respected again; a launch that owns the transport is never interrupted.
 */
internal fun tweakerBridgeMayAdoptHeldBinding(
    hasCachedCapabilities: Boolean,
    hasPendingLaunch: Boolean,
    mayOwnTransport: Boolean,
    sessionBound: Boolean,
): Boolean = !hasCachedCapabilities && !hasPendingLaunch && !mayOwnTransport && sessionBound

/**
 * The conditions under which [TweakerBridge.openTelemetryObservation] returns an observation, as a pure
 * function of cached facts: no ticket pending, and either an owner already holds a bound transport to share, or
 * the token is idle and the pair can bind.
 */
internal fun tweakerTelemetryObservationMayOpen(
    pairMayBind: Boolean,
    hasPendingLaunch: Boolean,
    mayOwnTransport: Boolean,
    sessionBound: Boolean,
    reservationIdle: Boolean,
    bridgeStartDeferred: Boolean = false,
): Boolean = when {
    // Low-memory recovery: a lean low-memory recovery start holds `initialize` back; an observation opened meanwhile
    // would respawn the daemon into the crunch and take the reservation the deferred start then fails to begin.
    bridgeStartDeferred -> false
    hasPendingLaunch -> false
    mayOwnTransport -> sessionBound
    else -> pairMayBind && reservationIdle
}

internal data class BoundTweakerPreparation(
    val preparation: TweakerPreparationResult,
    val capabilities: TweakerCapabilities?,
)

internal sealed interface TweakerSystemProfileSelectionResult {
    data class Active(val profileId: String, val changed: Boolean) :
        TweakerSystemProfileSelectionResult
    data class Stock(val changed: Boolean) : TweakerSystemProfileSelectionResult
    data object Busy : TweakerSystemProfileSelectionResult
    data object Unavailable : TweakerSystemProfileSelectionResult
    data object UpgradeRequired : TweakerSystemProfileSelectionResult
    data class Cancelled(val restoredProfileId: String) : TweakerSystemProfileSelectionResult
    data class Rejected(val code: String) : TweakerSystemProfileSelectionResult
    data class Uncertain(val reason: String) : TweakerSystemProfileSelectionResult
}

/** Caller-facing truth for one explicit live frame-limit change while a game is on screen. */
internal sealed interface TweakerLiveFpsSelectionResult {
    data class Applied(val packageName: String, val targetFps: Int) : TweakerLiveFpsSelectionResult
    data object Stock : TweakerLiveFpsSelectionResult
    data object Busy : TweakerLiveFpsSelectionResult
    data object Unavailable : TweakerLiveFpsSelectionResult
    data class Rejected(val reason: String) : TweakerLiveFpsSelectionResult
    data class Uncertain(val reason: String) : TweakerLiveFpsSelectionResult
}

/**
 * Foreground identity for a live frame-limit change. Only the service's Usage-Access attestation is
 * trusted, only while fresh, and never for Mimir or the Tweaker themselves: a missing, stale, or
 * self-referential attestation fails closed instead of guessing which game is on screen.
 */
internal fun attestedLiveFpsForegroundPackage(
    telemetry: TweakerTelemetry?,
    nowElapsedRealtimeMs: Long,
    selfPackageName: String,
): String? {
    val sample = telemetry ?: return null
    val sampledAt = sample.sampledAtElapsedRealtimeMs ?: return null
    if (nowElapsedRealtimeMs - sampledAt !in 0..LIVE_FPS_ATTESTATION_MAX_AGE_MS) return null
    val packageName = sample.foregroundPackageName ?: return null
    if (packageName == selfPackageName || packageName == TweakerSession.TWEAKER_PACKAGE) return null
    return packageName
}

private const val LIVE_FPS_ATTESTATION_MAX_AGE_MS = 3_500L

/** Atomic handshake between Back and the exact bound selection owner. */
internal class TweakerProfileSelectionControl {
    private val state = AtomicInteger(OPEN)

    val cancellationRequested: Boolean
        get() = state.get() == CANCEL_REQUESTED

    /** True only while Back can still require a compensating restore. */
    fun requestCancellation(): Boolean = state.compareAndSet(OPEN, CANCEL_REQUESTED) ||
        state.get() == CANCEL_REQUESTED

    /** The Binder owner calls this once before persisting a successful requested profile. */
    fun claimUncancelledSettlement(): Boolean = state.compareAndSet(OPEN, SETTLING)

    fun markSettled() {
        state.set(SETTLED)
    }

    private companion object {
        const val OPEN = 0
        const val CANCEL_REQUESTED = 1
        const val SETTLING = 2
        const val SETTLED = 3
    }
}

internal sealed interface CancellableProfileSelectionAttempt<out T> {
    data class Completed<T>(val value: T) : CancellableProfileSelectionAttempt<T>
    data object CancelledBeforeMutation : CancellableProfileSelectionAttempt<Nothing>
    data object Unavailable : CancellableProfileSelectionAttempt<Nothing>
}

/** Bounded admission retry that lets Back win before every Binder mutation attempt. */
internal suspend fun <T> cancellableProfileSelectionAttempts(
    attemptCount: Int,
    control: TweakerProfileSelectionControl?,
    select: suspend (attemptIndex: Int) -> T?,
    shouldRetry: (T) -> Boolean,
    waitBeforeRetry: suspend (attemptIndex: Int) -> Unit,
): CancellableProfileSelectionAttempt<T> {
    repeat(attemptCount.coerceAtLeast(0)) { attemptIndex ->
        if (control?.cancellationRequested == true) {
            return CancellableProfileSelectionAttempt.CancelledBeforeMutation
        }
        val result = select(attemptIndex) ?: return CancellableProfileSelectionAttempt.Unavailable
        if (!shouldRetry(result) || attemptIndex == attemptCount - 1) {
            return CancellableProfileSelectionAttempt.Completed(result)
        }
        if (control?.cancellationRequested == true) {
            return CancellableProfileSelectionAttempt.CancelledBeforeMutation
        }
        waitBeforeRetry(attemptIndex)
    }
    return CancellableProfileSelectionAttempt.Unavailable
}

internal fun cancelledSystemProfileSelectionResult(
    priorProfileId: String,
    finalProfileId: String?,
    priorProfileStillAdmitted: Boolean,
): TweakerSystemProfileSelectionResult = when {
    finalProfileId != priorProfileId -> TweakerSystemProfileSelectionResult.Uncertain(
        "Profile cancellation restore was not confirmed",
    )
    !priorProfileStillAdmitted -> TweakerSystemProfileSelectionResult.Uncertain(
        "Restored profile is no longer admitted by current capabilities",
    )
    else -> TweakerSystemProfileSelectionResult.Cancelled(priorProfileId)
}

/** LAB candidates may enter Utility only in a debuggable app and with every service-side gate true. */
internal fun includeLabProfilesInUtility(
    capabilities: TweakerCapabilities,
    debugBuild: Boolean,
): Boolean = debugBuild && capabilities.allowsLabOwnerValidation()

internal fun shouldRetrySystemProfileAdmission(
    assessment: TweakerV5SystemSelectionAssessment,
): Boolean = assessment is TweakerV5SystemSelectionAssessment.Rejected &&
    assessment.code == "SERVICE_LIFETIME_NOT_STARTED"

/** What one authoritative observation says about the player's standing choice. */
internal enum class StandingRearmDecision {
    /** The player never chose, or deliberately chose Stock. Nothing to assert, ever. */
    NOTHING_TO_ASSERT,

    /** The service cannot be read right now, so device truth is unknown. Keep watching; do not act. */
    TRUTH_UNKNOWN,

    /**
     * The answer is definitive and negative: the package is gone, or the service replied and
     * classified this hardware as stock-only. Stand down — but keep the standing choice, because the
     * player's intent outlives a missing service. If the Tweaker returns, the projection hook restarts
     * the watch for free.
     */
    NOT_OFFERED,

    /** A non-Stock profile is live. The choice is honoured, or was deliberately superseded. */
    HONOURED,

    /** A standing non-Stock choice exists and the device is at Stock. Re-state the intent. */
    REARM,
}

/**
 * The whole watcher decision, as a pure function of intent plus what the row actually reports.
 *
 * [standingProfileId] is the player's explicit intent (`standing_profile_id`, written only by an
 * explicit selection). [effectiveProfileId] must come from [TweakerUtilityRowState.effectiveProfileId]
 * — the same value the bridge logs and the UI shows — **not** from `selectedProfileId()`.
 *
 * That distinction is the entire reason the first two certification runs failed. `selectedProfileId()`
 * reads `KEY_PROFILE`, which `normalizeStoredSelection` leaves untouched whenever `capabilities` is
 * null. So at 00:51 on 2026-08-11, with the service killed, the projection logged
 * `selected=stock` while the raw pref still said `balanced`; the watcher read the pref, concluded
 * [HONOURED], and cancelled itself while the hardware sat at Stock.
 *
 * [availability] gates the whole thing, and not every non-READY value means the same thing:
 *
 * - `UNAVAILABLE` is genuinely unknown — the bind failed or capabilities have not been read yet, which
 *   is exactly the service-died case. Stock cannot be distinguished from unreadable, so [TRUTH_UNKNOWN]:
 *   keep watching, attempt nothing, and above all do not treat it as honoured.
 * - `NOT_INSTALLED` and `RESPONDING_STOCK_ONLY` are *definitive negatives* — the package is gone, or the
 *   service answered and decertified this hardware. Retrying cannot change either, so they are
 *   [NOT_OFFERED] and the watch stands down. Treating them as unknown made the watch immortal: it ticked
 *   every 15 s forever, and because the tick refresh is not a re-arm it bypassed the backoff entirely,
 *   re-binding (and so restarting) the Tweaker service on that cadence for the life of the process.
 */
internal fun standingRearmDecision(
    standingProfileId: String,
    effectiveProfileId: String,
    availability: TweakerUtilityAvailability,
    negativeIsDefinitive: Boolean,
    capabilitiesUnanswered: Boolean,
    stockProfileId: String = TweakerBridge.STOCK_PROFILE,
): StandingRearmDecision = when {
    standingProfileId.isBlank() || standingProfileId == stockProfileId ->
        StandingRearmDecision.NOTHING_TO_ASSERT
    availability == TweakerUtilityAvailability.NOT_INSTALLED ||
        availability == TweakerUtilityAvailability.RESPONDING_STOCK_ONLY ->
        StandingRearmDecision.NOT_OFFERED
    // **DISCOVERED_CEILING is a negative only when something actually said no.**
    // The discovered path has clock ceilings and no named profiles at all, so on a device whose Tweaker is
    // absent, or answered and offered nothing, a standing profile cannot be honoured and a watch has nothing
    // to confirm: NOT_OFFERED, or the watch is a re-bind cadence on a service that is not there (the row
    // outranked NOT_INSTALLED and the `!= READY` comparison below absorbed it silently).
    //
    // But the row ALSO falls to DISCOVERED_CEILING whenever `capabilities` is null on a Tweaker that is
    // installed and able to bind: nothing was read yet, the last read was cleared, or nobody could ask. That
    // is the ABSENCE of an answer, and reading it as a no stood the watch down for good while the hardware
    // still held the profile (Nova 2026-10-01 12:45:27: `decision=NOT_OFFERED effective=stock
    // availability=DISCOVERED_CEILING bound=false`, the CPU QoS node still held at Quiet). Unknown keeps the
    // watch alive, and its refresh rebinds an unbound bridge. [negativeIsDefinitive] is
    // [tweakerNegativeIsDefinitive].
    availability == TweakerUtilityAvailability.DISCOVERED_CEILING ->
        if (negativeIsDefinitive) {
            StandingRearmDecision.NOT_OFFERED
        } else {
            StandingRearmDecision.TRUTH_UNKNOWN
        }
    // **READY over an unanswered ask is not READY truth.** The row keeps the daemon's last capabilities when a refresh
    // never reached it (see [retainCapabilitiesUnanswered]), and its stored selection is not evidence of what the
    // hardware is running now: a service that restarted onto Stock (startup recovery) looks exactly like this from
    // here. HONOURED would stand the watch down over Stock silicon, and a REARM judged on a stale read could fire on
    // a profile that is in force. Unknown keeps the watch asking until the daemon answers.
    availability == TweakerUtilityAvailability.READY && capabilitiesUnanswered ->
        StandingRearmDecision.TRUTH_UNKNOWN
    availability != TweakerUtilityAvailability.READY -> StandingRearmDecision.TRUTH_UNKNOWN
    effectiveProfileId != stockProfileId -> StandingRearmDecision.HONOURED
    else -> StandingRearmDecision.REARM
}

/**
 * Whether a "this Tweaker is not serving profiles" row is something the package or the daemon SAID, as opposed
 * to the lack of an answer. True once the daemon has answered with capabilities, or the package pair can never
 * bind (absent, wrong signature, service missing or incompatible, component disabled, permission denied). False
 * for a pair that CAN bind ([TweakerPairState.ELIGIBLE]) or has not been checked yet, with no capabilities:
 * nothing has been learned, and the next bind may learn it.
 */
internal fun tweakerNegativeIsDefinitive(
    pairState: TweakerPairState,
    capabilitiesAnswered: Boolean,
): Boolean = capabilitiesAnswered || when (pairState) {
    TweakerPairState.ELIGIBLE,
    TweakerPairState.UNCHECKED,
    TweakerPairState.PACKAGE_MANAGER_ERROR,
    -> false
    TweakerPairState.ABSENT,
    TweakerPairState.SIGNATURE_MISMATCH,
    TweakerPairState.SERVICE_MISSING,
    TweakerPairState.SERVICE_INCOMPATIBLE,
    TweakerPairState.COMPONENT_DISABLED,
    TweakerPairState.CONTROL_PERMISSION_DENIED,
    -> true
}

/**
 * Whether a terminal decision should keep the watch alive to confirm, rather than stand it down.
 *
 * Only HONOURED, and only while Mimir is the resumed app. NOTHING_TO_ASSERT means there is no intent
 * to protect, and NOT_OFFERED means the answer is definitive and negative -- neither is worth a
 * heartbeat. Backgrounded, a confirm would burn a bind per minute for a player who is not looking.
 */
internal fun standingWatchConfirms(
    decision: StandingRearmDecision,
    mimirResumed: Boolean,
): Boolean = mimirResumed && decision == StandingRearmDecision.HONOURED

/**
 * Recovery cadence while the choice is lost or unreadable; the slower confirm cadence once honoured.
 *
 * An unreadable service that stays unreadable is retried at the confirm cadence after
 * [UNKNOWN_TICKS_BEFORE_SLOWING] ticks: the watch never stops while the Tweaker is installed and bindable, but
 * every tick rebinds the service, and a wedged one should not be rebound every 15 s for the life of the process.
 */
internal fun standingWatchDelayMs(
    decision: StandingRearmDecision,
    watchIntervalMs: Long = TweakerBridge.STANDING_REARM_WATCH_INTERVAL_MS_FOR_TEST,
    confirmIntervalMs: Long = TweakerBridge.STANDING_CONFIRM_INTERVAL_MS_FOR_TEST,
    consecutiveUnknownTicks: Int = 0,
): Long = when {
    decision == StandingRearmDecision.HONOURED -> confirmIntervalMs
    decision == StandingRearmDecision.TRUTH_UNKNOWN &&
        consecutiveUnknownTicks >= UNKNOWN_TICKS_BEFORE_SLOWING -> confirmIntervalMs
    else -> watchIntervalMs
}

/** Ticks at the recovery cadence an unreadable service gets before the watch slows to the confirm cadence. */
internal const val UNKNOWN_TICKS_BEFORE_SLOWING = 8

/** Whether one scheduling attempt may dispatch, and if not, why. */
internal enum class StandingRearmAdmission { DISPATCH, IN_FLIGHT, BACKOFF }

/**
 * Single-flight plus backoff admission, extracted so the policy is testable without the singleton.
 * One attempt at a time; a second observation mid-flight is dropped, never queued.
 */
internal fun standingRearmAdmission(
    nowElapsedRealtimeMs: Long,
    notBeforeElapsedRealtimeMs: Long,
    inFlight: Boolean,
): StandingRearmAdmission = when {
    nowElapsedRealtimeMs < notBeforeElapsedRealtimeMs -> StandingRearmAdmission.BACKOFF
    inFlight -> StandingRearmAdmission.IN_FLIGHT
    else -> StandingRearmAdmission.DISPATCH
}

/**
 * Exponential backoff for consecutive failed re-arms, so a re-arm that cannot succeed — a service
 * that keeps refusing, a profile that no longer qualifies — cannot hammer the service. Any success,
 * or any observation reading non-Stock, resets [consecutiveFailures] to zero.
 */
internal fun standingRearmBackoffMs(
    consecutiveFailures: Int,
    baseMs: Long,
    maxMs: Long,
    maxSteps: Int,
): Long {
    if (consecutiveFailures <= 0) return 0L
    val step = consecutiveFailures.coerceAtMost(maxSteps)
    return (baseMs shl (step - 1)).coerceAtMost(maxMs)
}

/**
 * One standing-profile re-arm, from `rearmwatch schedule=dispatched` to the end of the job that runs it. A display-rate
 * APPLY that finds the Tweaker session reservation busy while one is in flight waits for its end (O16).
 */
internal interface StandingRearmFlight {
    /** `SystemClock.elapsedRealtime()` when the re-arm was dispatched. */
    val dispatchedAtElapsedRealtimeMs: Long

    /** Suspends until the re-arm has ended or [timeoutMs] has passed, holding nothing. True when it ended. */
    suspend fun awaitEnd(timeoutMs: Long): Boolean
}

/** The bridge's re-arm flight, ended once, in the `finally` of the job `TweakerBridge.scheduleStandingRearm` launched. */
internal class TweakerStandingRearmFlight(
    override val dispatchedAtElapsedRealtimeMs: Long,
) : StandingRearmFlight {
    private val ended = CompletableDeferred<Unit>()

    val hasEnded: Boolean
        get() = ended.isCompleted

    fun end() {
        ended.complete(Unit)
    }

    override suspend fun awaitEnd(timeoutMs: Long): Boolean =
        ended.isCompleted || (timeoutMs > 0L && withTimeoutOrNull(timeoutMs) { ended.await() } != null)
}

private fun TweakerV5SystemSelectionAssessment.toAppSelectionResult():
    TweakerSystemProfileSelectionResult = when (this) {
    is TweakerV5SystemSelectionAssessment.Active ->
        TweakerSystemProfileSelectionResult.Active(
            profileId = checkNotNull(truth.activeProfileId),
            changed = changed,
        )
    is TweakerV5SystemSelectionAssessment.Stock ->
        TweakerSystemProfileSelectionResult.Stock(changed)
    is TweakerV5SystemSelectionAssessment.Rejected ->
        TweakerSystemProfileSelectionResult.Rejected(code)
    is TweakerV5SystemSelectionAssessment.Uncertain ->
        TweakerSystemProfileSelectionResult.Uncertain(reason)
}

/**
 * Converts authoritative Stock preflight plus the current exact capability projection into a memory-only
 * post-dispatch ticket. Merely calling this function cannot bind, start a service, or mutate hardware.
 */
internal fun pendingV4LaunchFor(
    attemptId: LaunchAttemptId,
    preparation: TweakerPreparationResult,
    capabilities: TweakerCapabilities?,
    selectedProfileId: String,
    packageName: String,
    expiresAtElapsedRealtimeMs: Long,
    includeDebugLab: Boolean,
): PendingTweakerV4Launch? {
    if (preparation != TweakerPreparationResult.StockNoOp || selectedProfileId == TweakerBridge.STOCK_PROFILE) {
        return null
    }
    val caps = capabilities ?: return null
    val productIds = selectableTweakerProfileIds(
        capabilities = caps,
        includeDiagnosticProfiles = false,
    )
    if (selectedProfileId in productIds) {
        return PendingTweakerV4Launch.Product(
            attemptId = attemptId,
            profileId = selectedProfileId,
            packageName = packageName,
            expiresAtElapsedRealtimeMs = expiresAtElapsedRealtimeMs,
        )
    }
    if (includeDebugLab && caps.allowsLabOwnerValidation() && selectedProfileId in caps.diagnosticProfileIds) {
        return PendingTweakerV4Launch.DebugLab(
            attemptId = attemptId,
            profileId = selectedProfileId,
            packageName = packageName,
            expiresAtElapsedRealtimeMs = expiresAtElapsedRealtimeMs,
        )
    }
    return null
}

/** Pure composition of the immutable system-profile/per-package-FPS ticket after bound capability reads. */
internal fun composePendingTweakerLaunch(
    baseTicket: PendingTweakerV4Launch?,
    attemptId: LaunchAttemptId,
    packageName: String,
    expiresAtElapsedRealtimeMs: Long,
    systemProfileId: String,
    preparation: TweakerPreparationResult,
    productProfileIds: List<String>,
    storedFrameLimitHz: Int?,
    storedGameFpsPolicyRevision: Long = 0L,
): PendingTweakerV4Launch? {
    if (baseTicket is PendingTweakerV4Launch.DebugLab) return baseTicket
    if (baseTicket is PendingTweakerV4Launch.Product) {
        return baseTicket.copy(
            frameLimitHz = storedFrameLimitHz,
            gameFpsPolicyRevision = storedGameFpsPolicyRevision,
        )
    }
    val fpsLaunchAdmission = preparation == TweakerPreparationResult.StockNoOp ||
        (preparation as? TweakerPreparationResult.Applied)?.profileId == systemProfileId
    if (storedFrameLimitHz == null ||
        !fpsLaunchAdmission ||
        systemProfileId !in productProfileIds + TweakerBridge.STOCK_PROFILE
    ) return baseTicket
    return PendingTweakerV4Launch.Product(
        attemptId = attemptId,
        profileId = systemProfileId,
        packageName = packageName,
        expiresAtElapsedRealtimeMs = expiresAtElapsedRealtimeMs,
        activateProfile = false,
        systemProfileId = systemProfileId,
        frameLimitHz = storedFrameLimitHz,
        gameFpsPolicyRevision = storedGameFpsPolicyRevision,
    )
}

/** Post-dispatch revalidation; a changed stored FPS policy for the exact launched package makes the one-shot ticket stale. */
internal fun pendingProductTicketStillAdmitted(
    ticket: PendingTweakerV4Launch.Product,
    selectedSystemProfileId: String,
    selectedStoredFpsPolicy: TweakerGameFpsPolicy,
    productProfileIds: List<String>,
    fpsLimiterAvailable: Boolean,
): Boolean {
    if (selectedSystemProfileId != ticket.systemProfileId) return false
    if (ticket.activateProfile && ticket.profileId !in productProfileIds) return false
    if (ticket.gameFpsPolicyRevision != selectedStoredFpsPolicy.revision) return false
    if (ticket.frameLimitHz != null && ticket.frameLimitHz != selectedStoredFpsPolicy.frameLimitHz) return false
    if (ticket.frameLimitHz != null && !fpsLimiterAvailable) return false
    return true
}

/**
 * One already-bound protocol decision. V4 remains read-only here; mutation requires a later ticket.
 *
 * A daemon that names no version, or names one older than v4, is unavailable. It cannot meet the generation-bound
 * owner contract and has no authoritative session query, so nothing is read from it, no capability is kept, and
 * Stock is never assumed: the answer is [TweakerPreparationResult.Unavailable] with no capabilities, whatever was
 * cached before.
 */
internal fun prepareBoundTweaker(
    apiVersion: Int?,
    selectedProfileId: String,
    queryV4: (Int) -> TweakerV4SessionAssessment,
    readCapabilities: () -> TweakerCapabilities?,
): BoundTweakerPreparation {
    if (apiVersion == null || apiVersion < TWEAKER_V4_MIN_API_VERSION) {
        return BoundTweakerPreparation(
            preparation = TweakerPreparationResult.Unavailable,
            capabilities = null,
        )
    }
    val assessment = queryV4(apiVersion)
    val caps = authoritativeV4Capabilities(assessment, readCapabilities)
    val preparation = when (assessment) {
        is TweakerV4SessionAssessment.Stock ->
            if (caps?.status?.isReachable() == true) {
                TweakerPreparationResult.StockNoOp
            } else {
                TweakerPreparationResult.Unavailable
            }
        is TweakerV4SessionAssessment.Blocked ->
            authoritativeProfileId(assessment)
                ?.takeIf { it != TweakerBridge.STOCK_PROFILE && caps?.status?.isReachable() == true }
                ?.let(TweakerPreparationResult::Applied)
                ?: TweakerPreparationResult.Skipped(
                    "Tweaker session ${assessment.truth.state.name.lowercase()} blocks profile changes",
                )
        is TweakerV4SessionAssessment.Uncertain -> TweakerPreparationResult.Skipped(
            "Tweaker session truth unavailable",
        )
    }
    return BoundTweakerPreparation(
        preparation = preparation,
        capabilities = caps,
    )
}

private fun TweakerCapabilities.withoutLaunchMutationAuthority(): TweakerCapabilities = copy(
    certifiedProfileIds = emptySet(),
    diagnosticProfileIds = emptySet(),
    diagnosticMutationAvailable = false,
)

/** Retires a stale v5 mutation projection when the bound helper has downgraded. */
internal fun failClosedCapabilitiesForSystemSelectionDowngrade(
    apiVersion: Int,
    assessment: TweakerV4SessionAssessment,
    readCapabilities: () -> TweakerCapabilities?,
): TweakerCapabilities? {
    require(apiVersion < TWEAKER_V5_SYSTEM_SCOPE_MIN_API_VERSION)
    return if (apiVersion >= TWEAKER_V4_MIN_API_VERSION) {
        authoritativeV4Capabilities(assessment, readCapabilities)
    } else {
        null
    }
}

internal fun authoritativeV4Capabilities(
    assessment: TweakerV4SessionAssessment,
    readCapabilities: () -> TweakerCapabilities?,
): TweakerCapabilities? {
    // Held sessions keep their capability truth: it is the daemon's own, and dropping it is what made
    // the whole Tweaker surface disappear while a stranded restore sat there. The caller marks the row
    // held, which is what stops a press being offered.
    if (tweakerSessionHeldByUnfinishedRestore(assessment)) return readCapabilities()
    val profileId = authoritativeProfileId(assessment) ?: return null
    val truthApiVersion = when (assessment) {
        is TweakerV4SessionAssessment.Stock -> assessment.truth.apiVersion
        is TweakerV4SessionAssessment.Blocked -> assessment.truth.apiVersion
        is TweakerV4SessionAssessment.Uncertain -> return null
    }
    val observed = readCapabilities() ?: return null
    return if (truthApiVersion >= TWEAKER_V5_SYSTEM_SCOPE_MIN_API_VERSION) {
        // The model parser already strips every profile that is not Production, signed-manifest,
        // mutation-enabled, and product-qualifiable. Exact Stock or a canonical active SYSTEM profile
        // permits retaining that same capability truth; neither branch schedules a new mutation here.
        observed
    } else {
        // API v4 remains bind/query compatible, but it cannot truthfully offer the v5 system-wide row.
        check(profileId == TweakerBridge.STOCK_PROFILE)
        observed.withoutLaunchMutationAuthority()
    }
}

/**
 * A `Blocked` session that is mid-restore or in recovery scope.
 *
 * This is the state both handhelds sat in on 2026-09-04, one of them 17.6 hours past its own hard lease
 * deadline: the daemon answers, its capabilities are real (`getCapabilities()` non-null), and it is
 * telling us a restore has not finished. [authoritativeProfileId] returns null for it — correctly, since
 * neither `state == ACTIVE` nor `scope == SYSTEM` holds — and the capability truth used to be discarded
 * whole because of that, taking the profile list with it. The row then showed the discovered CPU ceiling
 * and nothing else, which is the reported unexplained loss.
 *
 * So the capabilities are kept and the row is marked held instead. The refusal is real and stays real —
 * a selection against this session is refused at the daemon with `SESSION_NOT_SETTLED` — but it is now a
 * refusal the player can read rather than a surface that has gone blank.
 */
internal fun tweakerSessionHeldByUnfinishedRestore(
    assessment: TweakerV4SessionAssessment,
): Boolean = assessment is TweakerV4SessionAssessment.Blocked &&
    (assessment.truth.state == TweakerV4SessionState.RESTORING ||
        assessment.truth.scope == TweakerSessionScope.RECOVERY)

internal fun authoritativeProfileId(
    assessment: TweakerV4SessionAssessment,
): String? = when (assessment) {
    is TweakerV4SessionAssessment.Stock -> TweakerBridge.STOCK_PROFILE.takeIf {
        assessment.truth.state == TweakerV4SessionState.STOCK &&
            !assessment.truth.mutationMayBeActive
    }
    is TweakerV4SessionAssessment.Blocked -> assessment.truth.activeProfileId?.takeIf { profileId ->
        assessment.truth.apiVersion >= TWEAKER_V5_SYSTEM_SCOPE_MIN_API_VERSION &&
            assessment.truth.state == TweakerV4SessionState.ACTIVE &&
            assessment.truth.mutationMayBeActive &&
            assessment.truth.scope == TweakerSessionScope.SYSTEM &&
            profileId in com.gruning.mimir.rewrite.core.tweaker.TWEAKER_PRODUCT_QUALIFIABLE_PROFILE_IDS
    }
    is TweakerV4SessionAssessment.Uncertain -> null
}

/**
 * The row's default before anything has been read (previews, and the split second before the first
 * capability refresh lands). UNAVAILABLE, never NOT_INSTALLED: we have not yet earned the right to tell
 * the player to install anything.
 */
internal val DefaultTweakerUtilityRow = TweakerUtilityRowState(TweakerUtilityAvailability.UNAVAILABLE)

/** Convenience for Home launch paths: warn only for intentional non-stock certified failures. */
fun TweakerPreparationResult.toLaunchWarningOrNull(
    context: TweakerLaunchSelectionContext = TweakerBridge.launchSelectionContext(),
): String? = launchWarning(context)

/** What a capability refresh leaves in force: the capabilities to keep, and whether truth is unknown. */
internal data class CapabilitiesReconciliation(
    val capabilities: TweakerCapabilities?,
    val truthUnknown: Boolean,
)

/**
 * A bind that failed. On a pair that can never bind (absent, wrong signature, service missing or incompatible,
 * component disabled, permission denied) that is the answer, and the capabilities clear. On a pair that CAN bind
 * the daemon was never asked, so the last answer stays and the truth is marked unknown.
 */
internal fun reconcileCapabilitiesAfterFailedBind(
    previous: TweakerCapabilities?,
    pairIdentity: TweakerPairIdentity,
): CapabilitiesReconciliation =
    if (pairIdentity.mayBind) {
        CapabilitiesReconciliation(previous, truthUnknown = true)
    } else {
        CapabilitiesReconciliation(null, truthUnknown = false)
    }

/**
 * A null refresh that FOLLOWS an unanswered query ([silent]) is unknown, not "no": the last known
 * capabilities stay and the truth is marked unknown. Any non-null refresh is authoritative, and any null
 * with no unanswered query (bind refused, package gone, API too old) is a definitive negative that
 * clears them -- exactly as before.
 */
internal fun reconcileCapabilitiesAfterRefresh(
    previous: TweakerCapabilities?,
    incoming: TweakerCapabilities?,
    silent: Boolean,
): CapabilitiesReconciliation = when {
    incoming != null -> CapabilitiesReconciliation(incoming, truthUnknown = false)
    silent -> CapabilitiesReconciliation(previous, truthUnknown = true)
    else -> CapabilitiesReconciliation(null, truthUnknown = false)
}

/**
 * Whether the daemon has been HEARD FROM, as opposed to capabilities merely existing. Capabilities present and not
 * under an unanswered ask are an answer; so is a daemon that named its version to this process and has not gone
 * unanswered since, even when it declined to turn what it said into capabilities (an Uncertain session, a non-SYSTEM
 * Blocked one). Without the second clause that case left a bindable pair at "no answer" for the life of the process.
 */
internal fun tweakerCapabilitiesAnswered(
    capabilitiesPresent: Boolean,
    daemonAnsweredInProcess: Boolean,
    truthUnknown: Boolean,
): Boolean = capabilitiesPresent || (daemonAnsweredInProcess && !truthUnknown)

/**
 * A preflight found the selected profile already active and the post-dispatch confirmation has not run yet: observers
 * stay out of the token for [windowMs] so the confirmation's own observation is admitted. Past the window the answer is
 * no, so a dispatch that never confirms cannot lock them out.
 */
internal fun preparedActiveConfirmationOutstanding(
    hasPreparedProfile: Boolean,
    ageMs: Long,
    windowMs: Long,
): Boolean = hasPreparedProfile && ageMs in 0 until windowMs

/**
 * Whether the open Utility panel's once-a-second beat must do the full projection this time, as opposed to reading
 * telemetry alone: when nothing has been read, when the last ask went unanswered, when the pair cannot be bound from
 * cached facts, or when the last full projection is [intervalMs] old.
 */
internal fun tweakerPanelProjectionDue(
    capabilitiesPresent: Boolean,
    truthUnknown: Boolean,
    pairMayBind: Boolean,
    sinceFullProjectionMs: Long,
    intervalMs: Long,
): Boolean = !capabilitiesPresent || truthUnknown || !pairMayBind ||
    sinceFullProjectionMs !in 0 until intervalMs

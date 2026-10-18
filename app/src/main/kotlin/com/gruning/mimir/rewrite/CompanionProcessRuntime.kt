package com.gruning.mimir.rewrite

import android.app.ActivityOptions
import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.util.Log
import android.view.Display
import com.gruning.mimir.rewrite.core.ui.BottomUiReceipts
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import com.gruning.mimir.rewrite.core.launch.LaunchStore
import com.gruning.mimir.rewrite.core.launch.LaunchTargetReapResult
import com.gruning.mimir.rewrite.core.companionnative.AndroidCompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.AndroidExternalForegroundMonitor
import com.gruning.mimir.rewrite.core.companionnative.AndroidSecondaryTaskRegistry
import com.gruning.mimir.rewrite.core.companionnative.ForegroundMonitorDemand
import com.gruning.mimir.rewrite.core.companionnative.ForegroundMonitorDisposition
import com.gruning.mimir.rewrite.core.companionnative.NativeOverlayInteractionObserver
import com.gruning.mimir.rewrite.core.companionnative.NativeOverlayInteractionSource
import com.gruning.mimir.rewrite.core.companionnative.SecondaryDisplaySelector
import com.gruning.mimir.rewrite.core.companionnative.SecondaryDisplayState
import com.gruning.mimir.rewrite.core.companionnative.SecondaryPackageLiveness
import com.gruning.mimir.rewrite.core.companionnative.SecondaryPrivilegedCloseOutcome
import com.gruning.mimir.rewrite.core.companionnative.SecondaryTaskCloseState
import com.gruning.mimir.rewrite.core.audio.MimirAudioFocus
import com.gruning.mimir.rewrite.core.audio.MimirMusicControl
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Process truth that gates continuous UsageStats work without borrowing an Activity-owned coroutine. */
internal object PrimaryActivityObservationState {
    // A process can be started by the user-enabled M-button service before MainActivity exists. Until an
    // Activity explicitly resumes, global panels must treat the primary host as stopped and stay over the
    // current foreground app instead of routing through Home.
    private val mutableStopped = MutableStateFlow(true)
    private val stopResumeLedger = PrimaryStopResumeLedger()
    val stopped = mutableStopped.asStateFlow()

    fun onPrimaryResumed(): PrimaryResumeObservation {
        mutableStopped.value = false
        return stopResumeLedger.observeResume().also { observation ->
            // D3: a skipped return leaves its line on the tag the return's own events use.
            observation.withheldGameOperation?.let { operation ->
                Log.i(
                    "MimirSession",
                    primaryResumeGameReturnWithheldLogMessage(
                        operation = operation,
                        phase = MimirSessionAuthority.state.value.gameSession
                            ?.takeIf { it.operation == operation }
                            ?.phase,
                    ),
                )
            }
        }
    }

    fun onPrimaryStopped(
        changingConfigurations: Boolean,
        ticket: PrimaryStopResumeTicket,
    ) {
        mutableStopped.value = true
        stopResumeLedger.noteStopped(changingConfigurations, ticket)
    }

    /**
     * D3: an M-button command's intent reached MainActivity ([MButtonIntentAuthority.consume]). Delivery comes before
     * the resume the command causes.
     */
    fun noteMButtonCommandDelivered() {
        stopResumeLedger.noteMButtonCommandDelivered()
    }

    /** D3: whether Main's current resume holds a running game's return back because an M-button command caused it. */
    fun gameReturnWithheldByMButtonCommand(): Boolean = stopResumeLedger.gameReturnWithheld()

    fun acknowledgeGameReturn(operation: MimirGameSessionOperation) {
        stopResumeLedger.acknowledgeGame(operation)
    }

    fun acknowledgePrimaryAppReturn(operationId: String) {
        stopResumeLedger.acknowledgePrimaryApp(operationId)
    }
}

internal data class PrimaryResumeObservation(
    val resumedAfterStop: Boolean,
    val returnTicket: PrimaryStopResumeTicket?,
    /**
     * D3: the Main game whose return this resume did not start, because an M-button command brought Main forward and the
     * game did not come back. It stays in the ledger for the next resume that is a return.
     */
    val withheldGameOperation: MimirGameSessionOperation? = null,
)

internal data class PrimaryStopResumeTicket(
    val gameOperation: MimirGameSessionOperation? = null,
    val gameWasPreparing: Boolean = false,
    val primaryAppOperationId: String? = null,
    val primaryAppWasPreparing: Boolean = false,
)

/** D3's skipped-return line. The game has not returned, so no `game_returning` or `game_ended` follows it. */
internal fun primaryResumeGameReturnWithheldLogMessage(
    operation: MimirGameSessionOperation,
    phase: MimirSessionPhase?,
): String = "game return skipped reason=m_button_command op=${operation.value} game=$phase"

internal class PrimaryStopResumeLedger(
    private val isCurrentParkedGame: (MimirGameSessionOperation) -> Boolean =
        MainGameParkingState::isCurrentOperation,
) {
    private var pendingTicket: PrimaryStopResumeTicket? = null
    private var resumeSignalPending = false

    /**
     * D3: Main's current resumed interval was opened by an M-button command (Quick launch, Utility or Tweaker, from the
     * single-screen menu or the Bottom Utility's fallback), not by the app in the ticket coming back. A game in the ticket
     * is still running, so its return is not started: the session ends only when the game actually
     * returns or exits. Set when the command is delivered, which precedes the resume it causes; cleared by the next real
     * stop, so the resume after that is judged on its own.
     */
    private var mButtonCommandInterval = false

    @Synchronized
    fun noteStopped(
        changingConfigurations: Boolean,
        ticket: PrimaryStopResumeTicket,
    ) {
        if (!changingConfigurations) {
            resumeSignalPending = true
            pendingTicket = ticket.takeIf(PrimaryStopResumeTicket::hasReturnOperation)
            mButtonCommandInterval = false
        }
    }

    @Synchronized
    fun noteMButtonCommandDelivered() {
        mButtonCommandInterval = true
    }

    /** Whether a game's return is being held back right now (see [mButtonCommandInterval]). */
    @Synchronized
    fun gameReturnWithheld(): Boolean {
        val gameOperation = pendingTicket?.gameOperation ?: return false
        return mButtonCommandInterval || isCurrentParkedGame(gameOperation)
    }

    @Synchronized
    fun observeResume(): PrimaryResumeObservation {
        val ticket = pendingTicket
        val withheld = ticket?.gameOperation?.takeIf { mButtonCommandInterval }
        val parked = ticket?.gameOperation?.let(isCurrentParkedGame) == true
        val observation = PrimaryResumeObservation(
            resumedAfterStop = resumeSignalPending,
            // A primary app in the same ticket still returns: the ruling is about a game on Main.
            returnTicket = if (ticket == null || (withheld == null && !parked)) {
                ticket
            } else {
                ticket.copy(gameOperation = null, gameWasPreparing = false)
                    .takeIf(PrimaryStopResumeTicket::hasReturnOperation)
            },
            withheldGameOperation = withheld,
        )
        resumeSignalPending = false
        return observation
    }

    @Synchronized
    fun acknowledgeGame(operation: MimirGameSessionOperation) {
        val current = pendingTicket ?: return
        if (current.gameOperation != operation) return
        pendingTicket = current.copy(gameOperation = null, gameWasPreparing = false)
            .takeIf(PrimaryStopResumeTicket::hasReturnOperation)
    }

    @Synchronized
    fun acknowledgePrimaryApp(operationId: String) {
        val current = pendingTicket ?: return
        if (current.primaryAppOperationId != operationId) return
        pendingTicket = current.copy(primaryAppOperationId = null, primaryAppWasPreparing = false)
            .takeIf(PrimaryStopResumeTicket::hasReturnOperation)
    }
}

private fun PrimaryStopResumeTicket.hasReturnOperation(): Boolean =
    gameOperation != null || primaryAppOperationId != null

/** Process-owned companion services. Activity instances bind UI ports; they do not own runtime truth. */
internal object CompanionProcessRuntime {
    val processEpoch: String = UUID.randomUUID().toString()

    data class Services(
        val overlay: AndroidCompanionOverlayController,
        val secondaryDisplaySelector: SecondaryDisplaySelector,
        val taskRegistry: AndroidSecondaryTaskRegistry,
        val foregroundMonitor: AndroidExternalForegroundMonitor,
        val secondaryLaunchCoordinator: CompanionSecondaryLaunchCoordinator,
        val mButtonTopology: MButtonTopologyController,
        /** The single certified-vendor privileged broker. */
        val privilegedUtility: CertifiedPrivilegeGateway,
        val primaryMButtonPanelHost: PrimaryMButtonPanelHost,
        /** O5: the Bottom menu's Quick launch picker, shown on the Bottom. */
        val bottomQuickLaunchPanel: BottomQuickLaunchPanelHost,
        /** the Bottom menu's Utility (and the Tweaker inside it), an overlay window above whatever the Bottom shows. */
        val bottomUtilityPanel: BottomUtilityPanelHost,
        val mButtonActionRouter: MButtonProcessActionRouter,
        /**
         * R6-P1-01: a parked game's package, read through the vendor authority directly (never the
         * composite gateway, which has no liveness route and always answers the interface's UNKNOWN).
         */
        val verifiedPackageLiveness: (String) -> PrivilegedPackageLiveness,
    )

    /** Keep a process-scoped child failure from reaching the launcher thread's uncaught handler. */
    internal val processScopeFailureHandler = CoroutineExceptionHandler { context, throwable ->
        Log.e("MimirCompanionRuntime", "background process work failed in $context", throwable)
    }

    internal val processScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + processScopeFailureHandler)
    val motionGate: InteractionMotionGate = DefaultInteractionMotionGate(
        scope = processScope,
        logger = InteractionMotionLogger { event ->
            Log.i(
                "MimirMotionGate",
                "phase=${event.phase} elapsedMs=${event.atMillis} source=${event.source} root=${event.root}",
            )
        },
    )

    /**
     * Run an admitted primary quick-launch transaction from process lifetime. The resolver may outlive
     * the Activity that accepted the choice; keeping this narrow avoids handing the whole process scope to
     * UI callers while retaining the runtime's SupervisorJob/exception-handler boundary.
     */
    internal fun launchPrimaryQuickLaunch(work: suspend CoroutineScope.() -> Unit): Job =
        processScope.launch(block = work)

    @Volatile
    private var shared: Services? = null
    @Volatile
    private var cleanupContext: Context? = null

    // The process owns the one AppBrightnessCoordinator/model. Activities and the secondary Presentation
    // may render it, but must never construct another coordinator (TweakerBridge is process-global).
    private val sharedBrightnessMutable = MutableStateFlow<AppBrightnessCoordinator?>(null)
    val sharedBrightness: StateFlow<AppBrightnessCoordinator?> = sharedBrightnessMutable.asStateFlow()

    /** One process-retained Utility model; Activity recreation only rebinds its presentation host. */
    private var retainedBrightnessCoordinator: AppBrightnessCoordinator? = null

    /**
     * There are only two primary hosts for the retained Utility model.  The Activity registration is
     * kept while a native panel is on top so Activity recreation cannot steal the model from that panel;
     * the native registration is tokenized so an old overlay cannot tear down its successor.
     */
    internal enum class BrightnessHostKind { ACTIVITY, PRIMARY_OVERLAY }

    internal data class BrightnessHostRegistration(
        val kind: BrightnessHostKind,
        val controller: BrightnessController?,
        val authority: com.gruning.mimir.rewrite.core.input.FocusAuthority,
        val context: Context,
        val beforeExternalActivity: () -> Unit,
        val hostsInAppSurfaces: Boolean,
        val token: Long,
    )

    private var nextBrightnessHostToken = 0L
    private var activityBrightnessHost: BrightnessHostRegistration? = null
    private var overlayBrightnessHost: BrightnessHostRegistration? = null

    internal fun brightnessCoordinator(
        context: Context,
        controller: BrightnessController?,
        authority: com.gruning.mimir.rewrite.core.input.FocusAuthority,
        beforeExternalActivity: () -> Unit = {},
        hostsInAppSurfaces: Boolean = true,
        hostKind: BrightnessHostKind = BrightnessHostKind.ACTIVITY,
    ): AppBrightnessCoordinator = synchronized(this) {
        val coordinator = retainedBrightnessCoordinator ?: AppBrightnessCoordinator(
            context = context.applicationContext,
            controller = controller,
            authority = authority,
            scope = processScope,
            beforeExternalActivity = beforeExternalActivity,
            hostsInAppSurfaces = hostsInAppSurfaces,
        ).also { created ->
            retainedBrightnessCoordinator = created
            sharedBrightnessMutable.value = created
        }
        val registration = BrightnessHostRegistration(
            kind = hostKind,
            controller = controller,
            authority = authority,
            context = context.applicationContext,
            beforeExternalActivity = beforeExternalActivity,
            hostsInAppSurfaces = hostsInAppSurfaces,
            token = ++nextBrightnessHostToken,
        )
        when (hostKind) {
            BrightnessHostKind.ACTIVITY -> {
                activityBrightnessHost = registration
                // A native panel remains the visible primary host until its own token is released.
                if (overlayBrightnessHost == null) {
                    coordinator.rebindHost(registration)
                }
            }
            BrightnessHostKind.PRIMARY_OVERLAY -> {
                overlayBrightnessHost = registration
                coordinator.rebindHost(registration)
            }
        }
        sharedBrightnessMutable.value = coordinator
        coordinator
    }

    /** Returns the current registration for a host kind so its owner can retain an exact token. */
    internal fun currentBrightnessHost(kind: BrightnessHostKind): Long? = synchronized(this) {
        when (kind) {
            BrightnessHostKind.ACTIVITY -> activityBrightnessHost?.token
            BrightnessHostKind.PRIMARY_OVERLAY -> overlayBrightnessHost?.token
        }
    }

    internal fun unregisterActivityBrightnessHost(
        controller: BrightnessController?,
        authority: com.gruning.mimir.rewrite.core.input.FocusAuthority,
    ) = synchronized(this) {
        val registration = activityBrightnessHost ?: return@synchronized
        if (registration.controller !== controller || registration.authority !== authority) return@synchronized
        activityBrightnessHost = null
        val coordinator = retainedBrightnessCoordinator ?: return@synchronized
        if (overlayBrightnessHost == null && coordinator.ownsBrightnessHost(registration)) {
            coordinator.detachHost(registration)
        }
    }

    internal fun unregisterOverlayBrightnessHost(token: Long) = synchronized(this) {
        val registration = overlayBrightnessHost ?: return@synchronized
        if (registration.token != token) return@synchronized
        overlayBrightnessHost = null
        val coordinator = retainedBrightnessCoordinator ?: return@synchronized
        val activity = activityBrightnessHost
        if (activity != null) {
            coordinator.rebindHost(activity)
        } else if (coordinator.ownsBrightnessHost(registration)) {
            coordinator.detachHost(registration)
        }
    }

    fun services(context: Context): Services = shared ?: synchronized(this) {
        shared ?: create(context.applicationContext).also { shared = it }
    }

    private fun create(context: Context): Services {
        cleanupContext = context.applicationContext
        // R01 S4 / PFI-2 ANALYSIS : this synchronous read is LOAD-BEARING — recovery must
        // be adopted before either Activity can consume a return Intent (MimirApplication's contract), and
        // Application.onCreate is the only ordering Android guarantees ahead of BOTH activities
        // (CompanionReturnCoordinator is object-callable without services(), so a background create() could
        // race a return-intent consumer). In-create() parallelization is equally dead: the store's coarse
        // @Synchronized means attach() re-serializes one line later. The cost is mitigated instead by
        // MimirPrefsPrefetch (Application.onCreate) starting this file's disk load before we block on it.
        // Do NOT "optimize" this read onto a background thread.
        val pendingRecovery = CompanionPendingOperationStore.read(context)
        CompanionPrimaryOriginStore.attach(
            context = context,
            dispatcher = CompanionOriginWriteDispatcher { action ->
                processScope.launch(Dispatchers.IO) { action() }
            },
            retryScheduler = CompanionOriginRetryScheduler { delayMillis, action ->
                processScope.launch(Dispatchers.IO) {
                    delay(delayMillis)
                    action()
                }
            },
        )
        CompanionReturnCoordinator.installPrimaryOriginProvider(CompanionPrimaryOriginStore::current)
        val runtime = CompanionReturnCoordinator.runtime()
        // O10 : armed at process start, before an adopted Bottom lease can trigger it.
        // MimirMainHomeRecovery says when Main is brought back to Mimir's Home and what keeps it off a running game.
        MimirMainHomeRecovery.arm(context)
        pendingRecovery?.let { pending ->
            CompanionPrimaryOriginStore.adoptRecovered(pending.primaryOrigin)
            if (CompanionReturnCoordinator.recoverPersistedNonMirror(pending)) {
                CompanionPrimaryOriginStore.offerStartupRecovery(pending.primaryOrigin)
                MimirMainHomeRecovery.trigger(MainHomeRecoveryTrigger.PROCESS_RECOVERY)
            }
        }
        processScope.launch(Dispatchers.IO) {
            runtime.snapshot.collect { snapshot -> CompanionPendingOperationStore.reconcile(context, snapshot) }
        }
        val overlay = startCompanionOverlay(context, processScope)
        val nativeMotionRoot = motionGate.registerRoot(
            InteractionRoot.NATIVE_WINDOW,
            initiallyVisible = true,
        )
        val nativeMotionLeases = NativeMotionLeaseRegistry { source ->
            val interactionSource = when (source) {
                NativeOverlayInteractionSource.M_BUTTON -> InteractionSource.NATIVE_M_BUTTON
                NativeOverlayInteractionSource.MENU -> InteractionSource.NATIVE_MENU
                NativeOverlayInteractionSource.EDGE_BACK -> InteractionSource.NATIVE_EDGE_BACK
            }
            motionGate.hold(nativeMotionRoot, interactionSource)
        }
        overlay.setNativeOverlayInteractionObserver(
            NativeOverlayInteractionObserver { source, active ->
                nativeMotionLeases.onInteractionChanged(source, active)
            },
        )
        val secondaryDisplaySelector = SecondaryDisplaySelector(context)
        // The composite gateway is built further down (it reads this registry's display state), so the
        // close authority is published into this holder once it exists. Until then the registry keeps
        // its best-effort path instead of a half-wired privileged one. Atomic because the ✕ that reads
        // it runs on the UI thread, not on the thread that creates these services.
        val verifiedCloseAuthority = AtomicReference<PrivilegedTaskCloseGateway?>(null)
        // Round-5 B1: the Tabs liveness probe asks the vendor authority itself. The composite routes stop,
        // eviction and task lookup, but it has no liveness route, so through it this read would always be
        // the interface's UNKNOWN. The vendor gateway applies the same stop-policy door and the same live
        // capability probe on its own, and answers UNKNOWN whenever either refuses.
        val verifiedLivenessAuthority = AtomicReference<PrivilegedTaskCloseGateway?>(null)
        val registry = AndroidSecondaryTaskRegistry(
            context = context,
            secondaryDisplaySelector = secondaryDisplaySelector,
            privilegedStop = { pkg ->
                val authority = verifiedCloseAuthority.get()
                if (authority == null) {
                    // Pre-gateway fallback: the legacy reaper hook proves dispatch, never liveness.
                    when (LaunchStore.launchTargetReaper(pkg)) {
                        LaunchTargetReapResult.REQUEST_ACCEPTED ->
                            SecondaryPrivilegedCloseOutcome.REQUEST_UNVERIFIED
                        else -> SecondaryPrivilegedCloseOutcome.UNAVAILABLE
                    }
                } else {
                    when (authority.closeLaunchTarget(pkg)) {
                        PrivilegedCloseOutcome.STOPPED -> SecondaryPrivilegedCloseOutcome.STOPPED
                        PrivilegedCloseOutcome.STILL_ALIVE -> SecondaryPrivilegedCloseOutcome.STILL_ALIVE
                        PrivilegedCloseOutcome.REQUEST_UNVERIFIED ->
                            SecondaryPrivilegedCloseOutcome.REQUEST_UNVERIFIED
                        PrivilegedCloseOutcome.NOT_ALLOWED,
                        PrivilegedCloseOutcome.AUTHORITY_UNAVAILABLE,
                        PrivilegedCloseOutcome.FAILED,
                        -> SecondaryPrivilegedCloseOutcome.UNAVAILABLE
                    }
                }
            },
            privilegedRemoveTask = { taskId ->
                verifiedCloseAuthority.get()?.removeTask(taskId) == PrivilegedTaskRemoval.REMOVED
            },
            privilegedResolveTaskId = { pkg, displayId ->
                verifiedCloseAuthority.get()?.resolveTaskId(pkg, displayId)
            },
            privilegedLiveness = { pkg ->
                when (verifiedLivenessAuthority.get()?.packageLiveness(pkg)) {
                    PrivilegedPackageLiveness.ALIVE -> SecondaryPackageLiveness.ALIVE
                    PrivilegedPackageLiveness.ABSENT -> SecondaryPackageLiveness.ABSENT
                    PrivilegedPackageLiveness.UNKNOWN, null -> SecondaryPackageLiveness.UNKNOWN
                }
            },
            // Round 5b (Thor, 2026-09-12): GAME rows are asked about too. The state read is the session's own: a
            // Bottom game still PREPARING, or DISPATCHED to this emulator, is a launch starting and keeps its row.
            bottomGameLaunchInFlight = { pkg -> MimirSessionAuthority.state.value.bottomGameLaunchInFlightFor(pkg) },
            // What a GAME row's ✕ settles besides the row: the Bottom game session this process holds for it, if any.
            // With none, O14 : a dead process's open Bottom measurement for that game closes at the
            // emulator's last activity.
            onGameTabsRetired = { retired, stillTracked ->
                retired.forEach { row ->
                    val ended = runCatching {
                        MimirCompanionRuntimeSessionBridge.endBottomGameForTabClose(
                            row.packageName,
                            endedBy = "tabs liveness",
                        )
                    }.getOrNull()
                    if (ended == null) {
                        BottomOrphanPlayTime.finishAtLastActivity(context, row, retired, stillTracked)
                    }
                }
            },
            onSecondaryHostLaunchStarting = CompanionReturnCoordinator::noteHostLaunchStarting,
            onSecondaryHostLaunchFailed = { pkg, displayId, operationId, reason ->
                // P15: the registry-side failure reasons (§1.1 "Registry-side failures") reached the
                // runtime as a bare LaunchFailedBeforeDispatch with no reason string of its own.
                runCatching {
                    Log.w(
                        "MimirCompanionRuntime",
                        "secondary launch failed package=$pkg display=$displayId " +
                            "operation=$operationId reason=$reason",
                    )
                }
                CompanionReturnCoordinator.launchFailed(operationId, reason)
            },
            // R6-P0-02 S4: a parked Main game has no Tabs row for onGameTabsRetired to catch, so a Tabs
            // open is the reused signal for its OWN liveness -- same proof bar as every other release site
            // (task gone AND package ABSENT), no new polling.
            onLivenessReconciled = {
                MainGameParkingState.current()?.let { lease ->
                    processScope.launch(Dispatchers.IO) {
                        runCatching { releaseParkedGameIfGone(context, lease, stage = "tabs") }
                    }
                }
            },
        )
        MimirLaunchTargetStopPolicy.sessionAllowedPackages = {
            // the Bottom launch in flight is admitted for its own Main-copy clearance (BottomLaunchAdmission).
            mimirSessionAllowedPackages(
                trackedBottomPackages = registry.state.value.recents.mapTo(HashSet()) { it.packageName },
                runningPackage = CompanionRunningAppState.running.value?.packageName,
            )
        }
        fun logPrepareDeclined(packageName: String, reason: String) {
            runCatching {
                Log.w(
                    "MimirCompanionRuntime",
                    "prepare declined package=$packageName reason=$reason " +
                        "disposition=${runtime.snapshot.value.disposition.javaClass.simpleName}",
                )
            }
        }
        fun declinedPlan(packageName: String, reason: String): CompanionSecondaryLaunchPlan? {
            logPrepareDeclined(packageName, reason)
            return null
        }
        lateinit var launchBottomAppPlan: (CompanionSecondaryLaunchPlan) -> Boolean
        val planIsCurrent: (CompanionSecondaryLaunchPlan) -> Boolean = { plan ->
            val pending = runtime.snapshot.value.disposition as? SecondaryDisplayDisposition.LaunchPending
            pending?.lease?.operationId == plan.operationToken &&
                pending.lease.packageName == plan.packageName &&
                pending.lease.displayId == plan.displayId
        }
        // The Main game a Bottom app launch has to park first (the launch then waits up to 3 s for the park), or null when it launches
        // straight away. ONE answer for both readers: the launch itself (launchBottomAppPlan) and the Main-copy clearance in front of it
        // , which has no business running when the launch is already spending its three seconds on a park.
        fun mainGameToPark(): MimirGameSession? {
            val activeGame = MimirSessionAuthority.state.value.gameSession?.takeIf { game ->
                companionMainDualScreenGameActive(game) && !game.targetPackageName.isNullOrBlank()
            } ?: return null
            val parked = MainGameParkingState.currentFor(activeGame) != null
            return activeGame.takeIf {
                bottomLaunchParksMainGame(
                    dualScreenGameActive = true,
                    parkedLeaseExists = parked,
                    primaryStopped = PrimaryActivityObservationState.stopped.value,
                )
            }
        }
        val secondaryLaunchCoordinator = CompanionSecondaryLaunchCoordinator(
            logPrepareDeclined = ::logPrepareDeclined,
            preparePlan = prepare@{ packageName ->
                val displayId = registry.state.value.secondaryDisplayId
                    ?: return@prepare declinedPlan(packageName, "display_missing")
                val origin = CompanionPrimaryOriginStore.current()
                val rawOperationId = CompanionReturnCoordinator.noteHostLaunchStarting(
                    packageName = packageName,
                    displayId = displayId,
                    launchMode = com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract
                        .LAUNCH_MODE_SECONDARY_APP,
                ) ?: return@prepare declinedPlan(packageName, "host_launch_start_declined")
                val operationToken = OperationId.parseOrNull(rawOperationId)
                    ?: return@prepare declinedPlan(packageName, "operation_token_invalid")
                val operationId = runCatching { UUID.fromString(rawOperationId) }.getOrNull()
                    ?: return@prepare declinedPlan(packageName, "operation_uuid_invalid")
                val snapshot = runtime.snapshot.value
                val pending = snapshot.disposition as? SecondaryDisplayDisposition.LaunchPending
                if (pending?.lease?.operationId != operationToken) {
                    return@prepare declinedPlan(packageName, "pending_operation_mismatch")
                }
                CompanionSecondaryLaunchPlan(
                    operationId = operationId,
                    operationToken = operationToken,
                    packageName = packageName,
                    displayId = displayId,
                    primaryOrigin = origin,
                    stateVersion = snapshot.stateVersion,
                )
            },
            prepareGamePlan = prepareGame@{ targetIntent ->
                val packageName = (targetIntent.component?.packageName ?: targetIntent.`package`)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@prepareGame declinedPlan("", "game_package_missing")
                val displayId = registry.state.value.secondaryDisplayId
                    ?: return@prepareGame declinedPlan(packageName, "display_missing")
                val origin = CompanionPrimaryOriginStore.current()
                val rawOperationId = CompanionReturnCoordinator.noteHostLaunchStarting(
                    packageName = packageName,
                    displayId = displayId,
                    launchMode = com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract
                        .LAUNCH_MODE_SECONDARY_GAME,
                ) ?: return@prepareGame declinedPlan(packageName, "host_launch_start_declined")
                val operationToken = OperationId.parseOrNull(rawOperationId)
                    ?: return@prepareGame declinedPlan(packageName, "operation_token_invalid")
                val operationId = runCatching { UUID.fromString(rawOperationId) }.getOrNull()
                    ?: return@prepareGame declinedPlan(packageName, "operation_uuid_invalid")
                val snapshot = runtime.snapshot.value
                val pending = snapshot.disposition as? SecondaryDisplayDisposition.LaunchPending
                if (pending?.lease?.operationId != operationToken) {
                    return@prepareGame declinedPlan(packageName, "pending_operation_mismatch")
                }
                CompanionSecondaryLaunchPlan(
                    operationId = operationId,
                    operationToken = operationToken,
                    packageName = packageName,
                    displayId = displayId,
                    primaryOrigin = origin,
                    stateVersion = snapshot.stateVersion,
                    launchMode = com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract
                        .LAUNCH_MODE_SECONDARY_GAME,
                    targetIntent = android.content.Intent(targetIntent),
                )
            },
            persistPlan = { plan ->
                CompanionPendingOperationStore.persist(
                    context = context,
                    record = PersistedNonMirrorLease(
                        operationId = plan.operationToken,
                        displayId = plan.displayId,
                        primaryOrigin = plan.primaryOrigin,
                        description = "secondary launch requested for ${plan.packageName}",
                    ),
                    stateVersion = plan.stateVersion,
                )
            },
            isCurrentPlan = planIsCurrent,
            launchPlan = { plan ->
                if (plan.launchMode == com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME) {
                    plan.targetIntent?.let { targetIntent ->
                        registry.launchOnSecondary(
                            targetIntent = targetIntent,
                            operationId = plan.operationId,
                            expectedDisplayId = plan.displayId,
                            primaryOriginDestination = plan.primaryOrigin.destination,
                            primaryOriginArguments = plan.primaryOrigin.arguments,
                        )
                    } ?: false
                } else {
                    launchBottomAppPlan(plan)
                }
            },
            failPlan = { plan, reason -> CompanionReturnCoordinator.launchFailed(plan.operationToken, reason) },
            workScheduler = CompanionLaunchWorkScheduler { work ->
                processScope.launch(Dispatchers.IO) { work() }
                true
            },
            mainDispatcher = CompanionLaunchMainDispatcher { work ->
                processScope.launch(Dispatchers.Main.immediate) { work() }
            },
            // an app opened on Main keeps a window the keyboard refuses once it is moved to the Bottom, so QUICK LAUNCH closes the
            // app's Main copy first (BottomMainCopyClearance.kt). Quick Launch alone (`plan.clearMainCopy`, set by its three callers): an app
            // tile, the recent rail, a tab re-tap, the HOME-park resume and the debug hook launch as before. Never for a game's launch,
            // never while the launch is parking a Main game, and never for an emulator or the running game's package.
            beforeDispatch = { plan ->
                if (plan.clearMainCopy &&
                    plan.launchMode == com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_APP
                ) {
                    val game = MimirSessionAuthority.state.value.gameSession
                    val outcome = if (mainGameToPark() != null) {
                        BottomMainCopyOutcome.Skipped("game_parking")
                    } else {
                        BottomLaunchAdmission.admitting(plan.packageName) {
                            clearMainCopyBeforeBottomLaunch(
                                packageName = plan.packageName,
                                mimirPackage = context.packageName,
                                emulatorTarget = isRegisteredEmulatorPackage(plan.packageName),
                                gamePackages = setOfNotNull(game?.targetPackageName),
                                gateway = verifiedCloseAuthority.get(),
                                deadlineMs = bottomMainCopyDeadlineMs(plan.plannedAtMs),
                                stillWanted = { planIsCurrent(plan) },
                                // O12: when the copy is the app on top of Main, Mimir's Main task comes forward first so the removal does
                                // not slide Main for ~460 ms. Never over a game on Main: that would hide the game the copy was covering.
                                beforeFirstRemoval = if (game?.placement == MimirGamePlacement.MAIN) {
                                    null
                                } else {
                                    { budgetMs ->
                                        runBlocking {
                                            withTimeoutOrNull(budgetMs) {
                                                bringCompanionMainForward(context, CompanionExchangeInFlight.active)
                                            } ?: "timeout"
                                        }
                                    }
                                },
                            )
                        }
                    }
                    Log.i("MimirCompanionRuntime", "bottom launch main copy outcome=${outcome.logLabel()} operation=${plan.operationToken}")
                    if (outcome.needsKeyboardNote()) {
                        processScope.launch {
                            delay(BottomMainCopyNoteDelayMs)
                            showMimirMessage(
                                companionFeedbackContext(context, plan.displayId),
                                BottomMainCopyKeyboardNote,
                                MimirMessageClass.Guidance,
                            )
                        }
                    }
                }
            },
        )
        CompanionRecentsState.setPreparedLaunchHandler { packageName, clearMainCopy ->
            secondaryLaunchCoordinator.prepare(packageName, clearMainCopy)
        }
        processScope.launch {
            runtime.snapshot.collect { snapshot ->
                val operationId = (CompanionRecentsState.ui.value.launchState
                    as? CompanionDrawerLaunchState.LaunchPending)?.operationId ?: return@collect
                when (val resolution = snapshot.launchResolutionFor(operationId)) {
                    is CompanionLaunchResolution.Unlock ->
                        CompanionRecentsState.clearLaunchLock(operationId, resolution.reason)
                    is CompanionLaunchResolution.Fail ->
                        CompanionRecentsState.failLaunch(operationId, resolution.message)
                    null -> Unit
                }
            }
        }
        val monitor = AndroidExternalForegroundMonitor(
            context = context,
            ownHostActivityClassNames = setOf(
                CompanionReturnActivity::class.java.name,
                CompanionSecondaryHomeActivity::class.java.name,
                PrimaryTransferActivity::class.java.name,
            ),
        ).also { it.start() }
        val brightnessIdentity = currentBrightnessDeviceIdentity(context)
        val vendorUtility = AndroidCertifiedVendorPrivilegeGateway(
            context = context,
            identity = brightnessIdentity,
        )
        val privilegedUtility = CertifiedPrivilegeGateway(
            vendor = vendorUtility,
            vendorRecents = vendorUtility,
            vendorClose = vendorUtility,
            vendorKeyboard = vendorUtility,
        )
        launchBottomAppPlan = launchBottomAppPlan@{ plan ->
            // A native Main game owns display 0 while the Bottom app transaction is being admitted. The
            // callback itself runs on Main and only schedules the process-owned handoff; all task-table
            // reads happen off Main, and every hop re-checks the same app plan and game operation.
            val activeGame = mainGameToPark()
            if (activeGame == null) {
                // No native Main game needs covering, or this exact game is already parked while Mimir
                // is in front. Preserve the existing registry route and its operation guards.
                return@launchBottomAppPlan registry.launchOnSecondary(
                    packageName = plan.packageName,
                    operationId = plan.operationId,
                    expectedDisplayId = plan.displayId,
                    primaryOriginDestination = plan.primaryOrigin.destination,
                    primaryOriginArguments = plan.primaryOrigin.arguments,
                )
            } else {
                val expectedGame = requireNotNull(activeGame)
                fun sameParkedGame(current: MimirGameSession?): Boolean {
                    val candidate = current ?: return false
                    return candidate.operation == expectedGame.operation &&
                        candidate.gameId == expectedGame.gameId &&
                        candidate.targetPackageName?.trim() == expectedGame.targetPackageName?.trim() &&
                        candidate.placement == MimirGamePlacement.MAIN &&
                        candidate.dualScreen &&
                        companionMainDualScreenGameActive(candidate)
                }
                processScope.launch(Dispatchers.IO) {
                    val game = MimirSessionAuthority.state.value.gameSession?.takeIf { current ->
                        sameParkedGame(current)
                    } ?: run {
                        withContext(Dispatchers.Main.immediate) {
                            if (planIsCurrent(plan)) {
                                CompanionReturnCoordinator.launchFailed(plan.operationToken, "main_game_replaced_before_parking")
                            }
                        }
                        return@launch
                    }
                    val packageName = game.targetPackageName?.trim()?.takeIf(String::isNotEmpty) ?: run {
                        withContext(Dispatchers.Main.immediate) {
                            if (planIsCurrent(plan)) {
                                CompanionReturnCoordinator.launchFailed(plan.operationToken, "main_game_package_missing")
                            }
                        }
                        return@launch
                    }
                    val taskId = runCatching {
                        privilegedUtility.resolveTaskId(packageName, Display.DEFAULT_DISPLAY)
                    }.getOrNull()
                    if (taskId == null || taskId <= 0) {
                        withContext(Dispatchers.Main.immediate) {
                            if (planIsCurrent(plan)) {
                                CompanionReturnCoordinator.launchFailed(plan.operationToken, "main_game_task_unresolved")
                            }
                        }
                        return@launch
                    }

                    val lease = withContext(Dispatchers.Main.immediate) {
                        if (!planIsCurrent(plan)) return@withContext null
                        val current = MimirSessionAuthority.state.value.gameSession
                            ?.takeIf(::sameParkedGame)
                            ?: return@withContext null
                        when (MainGameParkingState.armForCurrentSession(current, taskId)) {
                            MainGameParkingArmResult.ARMED,
                            MainGameParkingArmResult.ALREADY_ARMED,
                            -> MainGameParkingState.currentFor(current)
                            else -> null
                        }
                    }
                    if (lease == null) {
                        withContext(Dispatchers.Main.immediate) {
                            if (planIsCurrent(plan)) {
                                CompanionReturnCoordinator.launchFailed(plan.operationToken, "main_game_parking_refused")
                            }
                        }
                        return@launch
                    }
                    // Receipts: `park` is written only once Main has actually resumed (below); every way the park can fail
                    // says why, so the log never claims a park while the native game may still be in front.
                    fun parkFailed(reason: String) = BottomUiReceipts.log.session(
                        "park_failed", lease.operation.value, plan.displayId, reason, plan.packageName,
                    )

                    val started = withContext(Dispatchers.Main.immediate) {
                        if (!planIsCurrent(plan) ||
                            !sameParkedGame(MimirSessionAuthority.state.value.gameSession) ||
                            MainGameParkingState.currentFor(
                                lease.operation,
                                lease.gameId,
                                lease.targetPackageName,
                            ) != lease
                        ) {
                            false
                        } else {
                            runCatching {
                                context.startActivity(
                                    Intent(context, MainActivity::class.java).apply {
                                        action = Intent.ACTION_MAIN
                                        addCategory(Intent.CATEGORY_HOME).putExtra(InternalHomeStartExtra, true)
                                        addFlags(
                                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                                Intent.FLAG_ACTIVITY_NO_ANIMATION,
                                        )
                                    },
                                    ActivityOptions.makeBasic()
                                        .setLaunchDisplayId(Display.DEFAULT_DISPLAY)
                                        .toBundle(),
                                )
                                true
                            }.getOrElse {
                                Log.w(
                                    "MimirCompanionRuntime",
                                    "main game parking start failed op=${game.operation.value} pkg=$packageName task=$taskId stage=main_start",
                                )
                                false
                            }
                        }
                    }
                    if (!started) {
                        parkFailed("main_game_front_failed")
                        withContext(Dispatchers.Main.immediate) {
                            if (planIsCurrent(plan)) {
                                CompanionReturnCoordinator.launchFailed(plan.operationToken, "main_game_front_failed")
                            }
                        }
                        return@launch
                    }
                    val resumed = withTimeoutOrNull(MainGameParkingResumeTimeoutMs) {
                        PrimaryActivityObservationState.stopped.first { stopped -> !stopped }
                    } != null
                    withContext(Dispatchers.Main.immediate) {
                        // Timeout intentionally retains the lease so a later explicit retry can still
                        // recover the native task; no stale completion may dispatch to a replacement plan.
                        if (!resumed) {
                            parkFailed("main_game_front_timeout")
                            if (planIsCurrent(plan)) {
                                CompanionReturnCoordinator.launchFailed(plan.operationToken, "main_game_front_timeout")
                            }
                            return@withContext
                        }
                        if (!planIsCurrent(plan) ||
                            !sameParkedGame(MimirSessionAuthority.state.value.gameSession) ||
                            MainGameParkingState.currentFor(
                                lease.operation,
                                lease.gameId,
                                lease.targetPackageName,
                            ) != lease
                        ) {
                            parkFailed("plan_superseded")
                            return@withContext
                        }
                        // Main is in front and resumed: the game is parked. Carries the session id.
                        BottomUiReceipts.log.session("park", lease.operation.value, plan.displayId, "bottom_app_launch", plan.packageName)
                        val launched = runCatching {
                            registry.launchOnSecondary(
                                packageName = plan.packageName,
                                operationId = plan.operationId,
                                expectedDisplayId = plan.displayId,
                                primaryOriginDestination = plan.primaryOrigin.destination,
                                primaryOriginArguments = plan.primaryOrigin.arguments,
                            )
                        }.onFailure {
                            Log.w(
                                "MimirCompanionRuntime",
                                "secondary launch failed op=${plan.operationToken} pkg=${plan.packageName} task=${lease.taskId} stage=bottom_dispatch",
                            )
                        }.getOrDefault(false)
                        if (!launched) {
                            CompanionReturnCoordinator.launchFailed(plan.operationToken, "secondary_launch_dispatch_failed")
                        } else {
                            Log.i(
                                "MimirCompanionRuntime",
                                "parked game bottom app dispatched op=${lease.operation.value} " +
                                    "pkg=${plan.packageName} task=${lease.taskId} stage=bottom_dispatch",
                            )
                        }
                    }
                }
            }
            true
        }
        // The Tabs ✕ now closes through the same composite authority as every other privileged
        // operation, instead of waiting for MainActivity to install the launch reaper hook.
        verifiedCloseAuthority.set(privilegedUtility)
        verifiedLivenessAuthority.set(vendorUtility)
        // Round-5 B1: tabs restored from `tabs.v1` make no claim about a live process. Ask once, now that an
        // authority exists; the Tabs request asks again each time the drawer opens. No polling.
        processScope.launch(Dispatchers.IO) { registry.reconcileLiveness() }
        val mButtonTopology = MButtonTopologyController(
            context = context,
            overlay = overlay,
            secondaryDisplaySelector = secondaryDisplaySelector,
            usageAccessGranted = { monitor.usageAccessState().granted },
            onboardingComplete = { !OnboardingStore.isFirstRun(context) },
            scope = processScope,
        )
        val primaryMButtonPanelHost = PrimaryMButtonPanelHost(
            context = context,
            overlay = overlay,
            topology = mButtonTopology,
            privilegedUtility = privilegedUtility,
            interactionMotionGate = motionGate,
        )
        // O5: the Bottom menu's Quick launch opens the Bottom's own picker on the Bottom and never starts MainActivity.
        val bottomQuickLaunchPanel = BottomQuickLaunchPanelHost(
            context = context,
            overlay = overlay,
            interactionMotionGate = motionGate,
        )
        // the Bottom menu's Utility is an overlay window on the Bottom, above a dual-screen emulator's own window.
        val bottomUtilityPanel = BottomUtilityPanelHost(
            context = context,
            overlay = overlay,
            interactionMotionGate = motionGate,
        )
        val mButtonActionRouter = MButtonProcessActionRouter(
            context = context,
            overlay = overlay,
            systemRecents = privilegedUtility,
            primaryPanel = primaryMButtonPanelHost,
            bottomQuickLaunch = bottomQuickLaunchPanel,
            bottomUtility = bottomUtilityPanel,
            bottomTrackpad = BottomTrackpadMenuGateway(context),
            isPrimaryActivityStopped = { PrimaryActivityObservationState.stopped.value },
            scope = processScope,
            mainRecents = AndroidMButtonMainRecents(context, privilegedUtility) { registry.state.value },
        )
        // a tap on the single-display badge opens the music panel while there is music to control. The overlay
        // reads this at a release; this module (core-companion-native) has no audio dependency, so the app says it.
        processScope.launch { MimirMusicControl.musicPanelAvailable.collect(overlay::setMusicPanelAvailable) }
        // while Utility, Tweaker or Tabs is open on the Bottom the ᛗ coin hides (it covered the Utility's "Bottom screen 60%" value), and comes back where it was.
        observeBottomPanelCoin(processScope, overlay.activeTarget, overlay::setCoinHiddenForPanel)
        processScope.launch {
            combine(runtime.snapshot, registry.state, PrimaryActivityObservationState.stopped) {
                    snapshot, taskState, primaryStopped ->
                foregroundMonitorDemand(snapshot, taskState, primaryStopped)
            }
                .distinctUntilChanged()
                .collect(monitor::updateObservationDemand)
        }
        processScope.launch {
            var pendingSecondaryReturnHost: HostLease? = null
            // One eviction attempt per swap operation. `runtime.snapshot` republishes while a lease
            // stays `Yielded`, so without this the privileged display-0 lookup would run again on every
            // publish and each repeat would log a second outcome line for the same landing.
            var mainCopyEvictionOperation: OperationId? = null
            runtime.snapshot.collect { snapshot ->
                when (val disposition = snapshot.disposition) {
                    is SecondaryDisplayDisposition.LaunchPending -> {
                        pendingSecondaryReturnHost = disposition.lease
                    }
                    is SecondaryDisplayDisposition.Yielded -> {
                        if (disposition.evidence == YieldEvidence.USER_REQUESTED_GAME_RETURN) {
                            pendingSecondaryReturnHost = null
                            return@collect
                        }
                        val lease = disposition.lease ?: return@collect
                        if (lease.launchMode == com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME) {
                            pendingSecondaryReturnHost = null
                            return@collect
                        }
                        val operationId = runCatching { UUID.fromString(lease.operationId.toString()) }.getOrNull()
                            ?: return@collect
                        val confirmed = confirmCompanionRunningAppSecondaryReturn(operationId, lease.packageName)
                        // The confirm above ends a Main session only for a transfer-confirmed app — it needs
                        // a live `CompanionRunningAppState.activeSession`. An app the player launched from the
                        // Bottom dock never crossed the display-0 transfer host, so it has no such session and
                        // the confirm returns false; its PRIMARY session would then sit in DISPATCHED forever
                        // and refuse every later Main launch (`appPreparing(PRIMARY)`). It owns the Bottom now,
                        // so the Main session is over either way (2026-09-11 exchange work).
                        if (!confirmed && yieldedLeaseMovedMainApp(lease) && MimirSessionAuthority.endPrimaryAppForSecondaryMove(lease.packageName)) {
                            Log.i(
                                "MimirCompanionRuntime",
                                "primary session ended: package moved to the Bottom package=${lease.packageName}",
                            )
                        }
                        // The landing hook. This is the one place the process learns that a Bottom app
                        // actually took display 4 -- `noteLaunchDispatched` fires at startActivity, before
                        // the task exists, so resolving there would race the task's creation. A yield
                        // means the host stepped aside for a task that is already up, so the id read here
                        // is the live one, and the ✕ later finds it tracked instead of paying for a
                        // privileged lookup mid-close. Off-main for the Binder call, back on Main to write
                        // the ledger, which is not synchronized.
                        val landed = lease.packageName
                        val landedDisplayId = lease.displayId
                        processScope.launch {
                            val taskId = withContext(Dispatchers.IO) {
                                runCatching {
                                    verifiedCloseAuthority.get()?.resolveTaskId(landed, landedDisplayId)
                                }.getOrNull()
                            } ?: return@launch
                            registry.noteSecondaryTaskId(landed, taskId)
                        }
                        //  A SCREEN_TRANSFER
                        // launches a FRESH instance on display 4, so a standard-launch-mode app's
                        // display-0 task survives the swap. Measured on the Thor (Mimir a8544b36,
                        // 11:00:32 local): after `v=23 event=HostCovered LaunchPending->Yielded` and
                        // `MimirSession: ... primaryApp=ENDED`, `am stack list` showed YouTube on
                        // displayId=4 AND displayId=0, display 0's top activity still
                        // `.app.honeycomb.Shell`. Main was left showing an app Mimir no longer manages.
                        // The reverse direction (LAUNCH_MODE_RESUME) resumes the one existing task, so
                        // only this direction has a copy to evict -- which is why the launch mode, not
                        // this call site, is what decides. The exchange's leg 2 lands here too: it
                        // reaches the Bottom through the same `swapToSecondary` -> SCREEN_TRANSFER.
                        val evictionDecision = companionMainCopyEvictionDecision(
                            launchMode = lease.launchMode,
                            yieldedPackage = landed,
                            secondaryDisplayId = landedDisplayId,
                            mainGamePackage = MimirSessionAuthority.state.value.gameSession
                                ?.takeIf { it.placement == MimirGamePlacement.MAIN }
                                ?.targetPackageName,
                        )
                        if (evictionDecision is CompanionMainCopyEviction.Evict &&
                            mainCopyEvictionOperation != lease.operationId
                        ) {
                            mainCopyEvictionOperation = lease.operationId
                            val evictPackage = evictionDecision.packageName
                            val evictDisplayId = evictionDecision.fromDisplayId
                            // O12: read at the landing. The exchange clears this once leg 3 has dispatched, so a value
                            // read when the removal runs could bring Mimir forward over leg 3's app.
                            val exchangeInFlight = CompanionExchangeInFlight.active
                            processScope.launch {
                                val authority = verifiedCloseAuthority.get()
                                if (authority == null) {
                                    Log.w(
                                        "MimirCompanionRuntime",
                                        "main copy eviction unavailable package=$evictPackage " +
                                            "reason=close_authority_missing",
                                    )
                                    return@launch
                                }
                                // **One eviction is not a recovery.** Device result, Thor, Mimir
                                // 6592f41d 11:13:18: the first pass ran exactly as designed
                                // (`resolveTask ... display=0 taskId=2317` -> `removeTask 2317 REMOVED`),
                                // and display 0 STILL showed YouTube -- the device carried TWO YouTube
                                // tasks there, a stale copy from an earlier glitch plus the one this
                                // swap had just orphaned. `resolveTaskId` answers with the FIRST visible
                                // task, so each pass can only ever see the next copy: sweep until the
                                // display answers empty. Bounded at
                                // [CompanionMainCopyEvictionLimit] so a resolver that keeps answering
                                // cannot turn a swap into an unbounded privileged loop.
                                var evicted = 0
                                var lastRemovedTaskId: Int? = null
                                var removalSettleChecks = 0
                                while (true) {
                                    // Display 0 only, every pass. The freshly landed display-4 task is
                                    // never resolved here, so it can never be the id that gets removed.
                                    val resolved = withContext(Dispatchers.IO) {
                                        runCatching { authority.resolveTaskId(evictPackage, evictDisplayId) }
                                    }
                                    if (resolved.isFailure) {
                                        Log.w(
                                            "MimirCompanionRuntime",
                                            "main copy eviction unavailable package=$evictPackage reason=" +
                                                "resolve_failed_${resolved.exceptionOrNull()?.javaClass?.simpleName}",
                                        )
                                        return@launch
                                    }
                                    val mainTaskId = resolved.getOrNull()
                                    if (mainTaskId == null) {
                                        // Nothing left. Only the very first pass reports it, because a
                                        // sweep that removed copies has already logged one line each.
                                        if (evicted == 0) {
                                            Log.i(
                                                "MimirCompanionRuntime",
                                                "main copy absent package=$evictPackage (no task on display 0)",
                                            )
                                        }
                                        return@launch
                                    }
                                    // O12 round 5b (Thor 8804787e): `am stack remove` answers once the removal has
                                    // begun, and the task stays listed until its activity is gone, so the next pass
                                    // resolved the same id and removed it again. That removal finds only a finishing
                                    // activity and does nothing, but it spent a pass meant for another copy. Wait for
                                    // the list to drop the id instead (CompanionMainCopyEviction.kt).
                                    if (mainTaskId == lastRemovedTaskId) {
                                        if (removalSettleChecks >= CompanionMainCopyRemovalSettleChecks) {
                                            Log.w(
                                                "MimirCompanionRuntime",
                                                "main copy removal still listed package=$evictPackage " +
                                                    "taskId=$mainTaskId checks=$removalSettleChecks",
                                            )
                                            return@launch
                                        }
                                        removalSettleChecks++
                                        delay(CompanionMainCopyRemovalSettlePollMs)
                                        continue
                                    }
                                    if (evicted >= CompanionMainCopyEvictionLimit) {
                                        // A task is still there and the budget is spent -- say so once
                                        // rather than sweeping forever or leaving it silent.
                                        Log.w(
                                            "MimirCompanionRuntime",
                                            "main copy eviction bounded package=$evictPackage remaining=true",
                                        )
                                        return@launch
                                    }
                                    // O12: the first pass brings Mimir's Main task forward with no transition and waits
                                    // for MainActivity, so the copy is removed paused and hidden (CompanionMainCopyEviction.kt).
                                    val front = if (evicted == 0) bringCompanionMainForward(context, exchangeInFlight) else "earlier_pass"
                                    val removal = withContext(Dispatchers.IO) {
                                        runCatching { authority.removeTask(mainTaskId) }
                                    }
                                    val result = removal.getOrNull()
                                    if (result == null) {
                                        Log.w(
                                            "MimirCompanionRuntime",
                                            "main copy eviction unavailable package=$evictPackage reason=" +
                                                "remove_failed_${removal.exceptionOrNull()?.javaClass?.simpleName}",
                                        )
                                        return@launch
                                    }
                                    evicted++
                                    lastRemovedTaskId = mainTaskId
                                    Log.i(
                                        "MimirCompanionRuntime",
                                        "main copy evicted package=$evictPackage " +
                                            "taskId=$mainTaskId result=$result" + " front=$front",
                                    )
                                    // A refused removal would resolve the same id forever; stop on the
                                    // first one the authority did not actually carry out.
                                    if (result != PrivilegedTaskRemoval.REMOVED) return@launch
                                }
                            }
                        }
                        pendingSecondaryReturnHost = null
                    }
                    else -> {
                        val abandoned = pendingSecondaryReturnHost ?: return@collect
                        if (abandoned.launchMode == com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME) {
                            pendingSecondaryReturnHost = null
                            return@collect
                        }
                        val operationId = runCatching { UUID.fromString(abandoned.operationId.toString()) }.getOrNull()
                            ?: return@collect
                        cancelCompanionRunningAppSecondaryReturn(operationId, abandoned.packageName)
                        pendingSecondaryReturnHost = null
                    }
                }
            }
        }
        processScope.launch {
            // this collector's bus
            // (`notifyExplicitReturn`) currently has ZERO producers — the live clear/disarm path is
            // MainActivity's primary-resume backstop, which fires only on a GENUINE Mimir-primary return.
            // CompanionDisplayController.restore() (incl. "tab_close_reclaim") deliberately bypasses this
            // bus and calls runtime().explicitReclaim directly, so a screen-2 tab close can NEVER drop a
            // still-away game's retained hero. IF you ever wire notifyExplicitReturn: route ONLY genuine
            // primary returns through it — this collector treats every event as "the game session is over".
            monitor.returned.collect { event ->
                val currentGameLease = (runtime.snapshot.value.disposition as? SecondaryDisplayDisposition.Yielded)
                    ?.lease
                    ?.takeIf {
                        it.launchMode == com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract
                            .LAUNCH_MODE_SECONDARY_GAME ||
                            it.launchMode == com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract
                                .LAUNCH_MODE_EXISTING_MAIN_GAME_RETURN
                    }
                if (currentGameLease == null) {
                    CompanionState.dualScreenGameActive = false
                    // G-2: Mimir is back in front — the game session is over; the armed re-derive key and the
                    // retained hero card are dead truth from here.
                    CompanionSceneState.disarmGameOwner()
                    CompanionState.clearRetainedNowPlaying()
                    CompanionSceneState.observeMimirForeground()
                    event.packageName?.let { packageName ->
                        clearCompanionRunningApp(packageName, event.observedAtMs)
                    }
                }
                runtime.explicitReclaim(
                    reason = event.reason,
                    primaryOrigin = CompanionPrimaryOriginStore.current(),
                    returningFromPackage = event.packageName,
                )
            }
        }
        processScope.launch {
            monitor.foregroundObserved.collect { observation ->
                Log.i("MimirCompanionNative", "advisory foreground observation: $observation")
                CompanionSceneState.observeMimirForeground(observation.observedAtMs)
                clearCompanionRunningApp(observation.packageName, observation.observedAtMs)
                runtime.foregroundReturnObserved(observation.packageName)
            }
        }
        processScope.launch(Dispatchers.IO) {
            runCatching { CompanionQuickLaunchCatalog.refresh(context) }
                .onFailure { Log.w("MimirCompanionNative", "quick-launch catalog refresh failed", it) }
        }
        processScope.launch {
            monitor.foreignForegroundObserved.collectLatest { observation ->
                if (!shouldRetainForeignForeground(runtime.snapshot.value, observation.packageName)) {
                    return@collectLatest
                }
                val app = withContext(Dispatchers.IO) {
                    val catalog = CompanionQuickLaunchCatalog.entry(observation.packageName)
                    val label = catalog?.label ?: runCatching {
                        val info = context.packageManager.getApplicationInfo(observation.packageName, 0)
                        context.packageManager.getApplicationLabel(info).toString()
                    }.getOrDefault(observation.packageName)
                    com.gruning.mimir.rewrite.core.model.CompanionRunningApp(
                        packageName = observation.packageName,
                        label = label,
                        iconPath = catalog?.iconPath,
                    )
                }
                if (!shouldRetainForeignForeground(runtime.snapshot.value, observation.packageName)) {
                    return@collectLatest
                }
                Log.i(
                    "MimirCompanionNative",
                    "retaining foreign foreground package=${observation.packageName} " +
                        "observedAtMs=${observation.observedAtMs}",
                )
                CompanionSceneState.observeForeignForeground(app, observation.observedAtMs)
                //  the dispatched game's own package back in foreground is the level truth
                // NOW_PLAYING re-derives from — this is what restores the hero card after a quick-launch
                // tab-switch away and back, where the edge-triggered Compose producer stays asleep.
                if (CompanionSceneState.isArmedGameOwner(observation.packageName)) {
                    val republished = CompanionState.republishRetainedNowPlaying()
                    if (republished) {
                        Log.i(
                            "MimirCompanionNative",
                            "re-derived NOW_PLAYING for returning game owner=${observation.packageName}",
                        )
                    }
                }
            }
        }
        return Services(
            overlay = overlay,
            secondaryDisplaySelector = secondaryDisplaySelector,
            taskRegistry = registry,
            foregroundMonitor = monitor,
            secondaryLaunchCoordinator = secondaryLaunchCoordinator,
            mButtonTopology = mButtonTopology,
            privilegedUtility = privilegedUtility,
            primaryMButtonPanelHost = primaryMButtonPanelHost,
            bottomQuickLaunchPanel = bottomQuickLaunchPanel,
            bottomUtilityPanel = bottomUtilityPanel,
            mButtonActionRouter = mButtonActionRouter,
            verifiedPackageLiveness = { pkg ->
                verifiedLivenessAuthority.get()?.packageLiveness(pkg) ?: PrivilegedPackageLiveness.UNKNOWN
            },
        )
    }

    /** Bottom games cannot use MainActivity's stop/resume cleanup; settle their exact return here. */
    internal fun settleBottomGameCleanup(game: MimirGameSession) {
        // The semantic session may already be terminal when this cleanup is queued. Keep the exact
        // operation guard at both entry and the IO hop: a successor can be admitted after FAILED/ENDED,
        // and its audio/Tweaker state must never be restored by this stale game's cleanup.
        if (!bottomGameCleanupStillCurrent(game.operation)) return
        val ownerCleared = game.targetPackageName?.let(CompanionSceneState::disarmGameOwner) == true
        if (ownerCleared) CompanionState.clearRetainedNowPlaying()
        if (game.dualScreen) CompanionState.dualScreenGameActive = false
        processScope.launch(Dispatchers.IO + NonCancellable) {
            if (bottomGameCleanupStillCurrent(game.operation)) {
                MimirAudioFocus.onReturnedToMimir()
                if (bottomGameCleanupStillCurrent(game.operation)) {
                    TweakerBridge.settleAfterReturn()
                }
            }
            // Return-handle persistence is optional for a secondary game, but global audio/Tweaker
            // cleanup above is mandatory for every accepted operation. The play time  is finished
            // for every game, by its handle's token or its own; only a handle has a record to acknowledge.
            val context = cleanupContext
            val handle = game.homeReturnHandle
            if (context != null) {
                HomeExternalSessionElapsedStore.finalize(
                    context = context,
                    returnToken = game.elapsedToken(),
                    nowElapsedMillis = android.os.SystemClock.elapsedRealtime(),
                )
                if (handle != null) acknowledgeHomeLaunchReturnDurably(context, handle)
            }
        }
    }

    /**
     * R6-P1-01: a parking lease lives only while the task it names exists.
     *
     * R6-P0-02 review fix (BLOCKING, orchestrator decision): proven gone is a POSITIVE [VendorTaskOutcome.NoTask]
     * answer alone -- process liveness is informational only, logged but never a second gate (unchanged from the
     * prior follow-up: Lime3DS's own Close Game finishes the activity without killing the process, so gating on
     * ABSENT kept a stale lease -- and every later PLAY -- stuck after a proper close reached through Recents).
     * What changed here: [VendorTaskOutcome.Unavailable] (policy declined, posture not ready, a timed-out or
     * failed transaction, or a reply that did not parse) must retain exactly like a live task does, never read
     * as gone -- a probe that could not answer is not proof of anything. Ending the session on an unanswered
     * probe orphans a live emulator the moment a different game is admitted on top of it (Thor finding: a
     * PServer timeout during a PLAY press ended a live parked Lime3DS session, and Castlevania was admitted over
     * it). Returns true when it released. Runs the probes on IO; the release on Main.immediate.
     */
    internal suspend fun releaseParkedGameIfGone(context: Context, lease: MainGameParkingLease, stage: String): Boolean {
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                services(context).privilegedUtility.resolveTaskOutcome(lease.targetPackageName, lease.displayId)
            }.getOrDefault(VendorTaskOutcome.Unavailable("unavailable"))
        }
        // Liveness is read (and only logged) once the resolver has positively said nothing exists for this
        // package -- a Found or Unavailable answer never needed it, and still doesn't.
        val liveness = if (outcome is VendorTaskOutcome.NoTask) {
            withContext(Dispatchers.IO) {
                runCatching {
                    services(context).verifiedPackageLiveness(lease.targetPackageName)
                }.getOrDefault(PrivilegedPackageLiveness.UNKNOWN)
            }
        } else {
            PrivilegedPackageLiveness.UNKNOWN
        }
        if (!parkedGameProvenGone(outcome, liveness)) {
            val reasonSuffix = (outcome as? VendorTaskOutcome.Unavailable)?.reason?.let { " reason=$it" }.orEmpty()
            Log.w(
                "MimirCompanionRuntime",
                "parked game retained op=${lease.operation.value} pkg=${lease.targetPackageName} " +
                    "task=${lease.taskId} liveness=$liveness stage=$stage$reasonSuffix",
            )
            return false
        }
        return withContext(Dispatchers.Main.immediate) {
            if (MainGameParkingState.currentFor(lease.operation, lease.gameId, lease.targetPackageName) != lease) {
                return@withContext false
            }
            MainGameParkingState.clear(lease)
            MimirCompanionRuntimeSessionBridge.endBottomGameForTabClose(lease.targetPackageName, endedBy = "parked task gone")
            Log.i(
                "MimirCompanionRuntime",
                "parked game gone op=${lease.operation.value} pkg=${lease.targetPackageName} " +
                    "task=${lease.taskId} liveness=$liveness stage=$stage",
            )
            true
        }
    }

    /**
     * R6-P0-02 review fix (BLOCKING): the proof decision alone, isolated so it is pinned without a live
     * Privileged/liveness stack. [liveness] is deliberately not part of the boolean -- it stays a parameter only
     * so the decision's whole input surface is visible in one place, and so `MainGameParkingStateTest` can
     * show a NoTask-but-ALIVE lease releases (the Lime3DS Close-Game symptom, unchanged from the prior
     * follow-up) while a Found OR an Unavailable [outcome] always retains, whatever the process state --
     * an unanswered probe must never be read as proof the game is gone.
     */
    internal fun parkedGameProvenGone(outcome: VendorTaskOutcome, liveness: PrivilegedPackageLiveness): Boolean =
        outcome is VendorTaskOutcome.NoTask

    /**
     * The shared release probe every PLAY
     * admission path calls after a refusal, before it gives up. A no-op (false, no IO) when nothing is
     * parked, so an ordinary "another app is open" refusal never pays this cost.
     *
     * [context] is the caller's own when it has one (Home's Compose `LocalContext`); the Library launch
     * mutex (`LibraryViewModel.launch`, via [MimirSessionAuthority]) has none, so `null` falls back to this
     * process' own cached [cleanupContext] -- the same context [settleBottomGameCleanup] already reads for
     * the same reason. No cached fallback and no context of its own is also a no-op false, never a crash.
     */
    internal suspend fun releaseParkedLeaseForPress(context: Context?, stage: String = "play"): Boolean {
        val lease = MainGameParkingState.current() ?: return false
        val resolvedContext = context ?: cleanupContext ?: return false
        return releaseParkedLease(resolvedContext, lease, stage)
    }

    private fun bottomGameCleanupStillCurrent(operation: MimirGameSessionOperation): Boolean {
        val current = MimirSessionAuthority.state.value.gameSession ?: return false
        return current.operation == operation && current.phase in setOf(
            MimirSessionPhase.RETURNING,
            MimirSessionPhase.ENDED,
            MimirSessionPhase.FAILED,
            MimirSessionPhase.RECOVERING,
        )
    }

}

/**
 * Keep primary task truth flowing while a secondary app covers Mimir, but never mistake that screen-2
 * host's own UsageStats edge for a primary tab switch. No-display processes have no companion to follow.
 */
internal fun shouldRetainForeignForeground(
    snapshot: CompanionRuntimeSnapshot,
    packageName: String,
): Boolean {
    if (snapshot.disposition == SecondaryDisplayDisposition.NoDisplay) return false
    val secondaryHostPackage = when (val disposition = snapshot.disposition) {
        is SecondaryDisplayDisposition.LaunchPending -> disposition.lease.packageName
        is SecondaryDisplayDisposition.Yielded -> disposition.lease?.packageName
        else -> null
    }
    return secondaryHostPackage != packageName
}

/** Keeps stale callback completions from publishing or stranding native interaction leases. */
internal class NativeMotionLeaseRegistry(
    private val acquireLease: (NativeOverlayInteractionSource) -> InteractionLease,
) {
    private val lock = Any()
    private val latestUpdates = mutableMapOf<NativeOverlayInteractionSource, Any>()
    private val leases = mutableMapOf<NativeOverlayInteractionSource, InteractionLease>()

    fun onInteractionChanged(source: NativeOverlayInteractionSource, active: Boolean) {
        val update = Any()
        val previousLease = synchronized(lock) {
            latestUpdates[source] = update
            leases.remove(source)
        }
        previousLease?.close()
        if (!active) return

        val acquiredLease = acquireLease(source)
        val retained = synchronized(lock) {
            if (latestUpdates[source] === update) {
                leases[source] = acquiredLease
                true
            } else {
                false
            }
        }
        if (!retained) acquiredLease.close()
    }
}

internal fun foregroundMonitorDemand(
    snapshot: CompanionRuntimeSnapshot,
    taskState: SecondaryDisplayState,
    primaryStopped: Boolean = true,
): ForegroundMonitorDemand {
    val disposition = when (snapshot.disposition) {
        SecondaryDisplayDisposition.NoDisplay -> ForegroundMonitorDisposition.NO_DISPLAY
        is SecondaryDisplayDisposition.Presenting -> ForegroundMonitorDisposition.PRESENTING
        is SecondaryDisplayDisposition.MirrorPresented -> ForegroundMonitorDisposition.MIRROR_PRESENTED
        is SecondaryDisplayDisposition.LaunchPending -> ForegroundMonitorDisposition.LAUNCH_PENDING
        is SecondaryDisplayDisposition.Yielded -> ForegroundMonitorDisposition.YIELDED
        is SecondaryDisplayDisposition.ReclaimPending -> ForegroundMonitorDisposition.RECLAIM_PENDING
        is SecondaryDisplayDisposition.RecoveryNeeded -> ForegroundMonitorDisposition.RECOVERY_NEEDED
    }
    val taskObservationPending = snapshot.launchLockOperationId != null ||
        taskState.recents.any { it.closeState != SecondaryTaskCloseState.OPEN }
    return ForegroundMonitorDemand(
        disposition = disposition,
        taskObservationPending = taskObservationPending,
        recoveryTaskEvidenceRequired = !isColdProcessRecovery(snapshot),
        // **a live Yielded lease keeps this observer awake.**
        //
        // The gate was `primaryStopped` alone, and that flag is false for the whole app session on a
        // dual-screen handheld -- both displays hold a resumed activity at once, MEASURED on the Thor
        // (`b687f3b5`), which is the same false premise that made RecoveryNeeded absorbing in L-3. So
        // the one advisory observer that could ever notice a leased host had gone away was asleep for
        // exactly the session in which a stale lease is most likely (review finding D9).
        //
        // A `Yielded` lease is the state with no liveness check of any kind (D3): the root deadline is
        // cancelled when the host covers us, nothing is scheduled in its place, and the only accepted
        // close evidence is package uninstall -- so the lease can outlive its subject unboundedly.
        // Keeping the follow active while the lease is live is the bounded backstop that ruling asks
        // for, and it is deliberately the CHEAPEST one: it turns on an observer that already exists
        // rather than adding a new poll.
        //
        // The reveal-gate reclaim in `CompanionDisplayController` stays and is the other half (L-7 was
        // retired once it was clear it closes `Yielded`, which L-3's auto-reclaim never touches). That
        // one fires when the covering host goes away and the window is uncovered; this one covers the
        // case where nothing ever uncovers it.
        externalFollowActive = snapshot.displayId != null &&
            (primaryStopped || snapshot.disposition is SecondaryDisplayDisposition.Yielded),
    )
}

private fun isColdProcessRecovery(snapshot: CompanionRuntimeSnapshot): Boolean =
    snapshot.disposition is SecondaryDisplayDisposition.RecoveryNeeded &&
        (snapshot.disposition as SecondaryDisplayDisposition.RecoveryNeeded).failure.code ==
            FailureCode.PROCESS_RECOVERED_NON_MIRROR

/** Latest exact primary navigation origin, revisioned and committed for process recovery. */
internal object CompanionPrimaryOriginStore {
    @Volatile
    private var value = PrimaryReturnOrigin(destination = MimirDestination.LIBRARY.name)

    @Volatile
    private var writeQueue: CompanionOriginWriteQueue? = null

    @Volatile
    private var startupRecovery: PrimaryReturnOrigin? = null

    @Volatile
    private var recreationOrigin: PrimaryReturnOrigin? = null

    fun current(): PrimaryReturnOrigin = value

    @Synchronized
    fun attach(
        context: Context,
        dispatcher: CompanionOriginWriteDispatcher,
        retryScheduler: CompanionOriginRetryScheduler,
    ) {
        val appContext = context.applicationContext
        val persisted = CompanionPendingOperationStore.currentOrigin(appContext)
        recreationOrigin = persisted
        // Durable current-origin data is a revision floor, not a cold-start route. Only an adopted pending
        // non-mirror recovery may restore its location; an ordinary process start begins at Library.
        if (persisted != null && persisted.revision > value.revision) {
            value = PrimaryReturnOrigin(MimirDestination.LIBRARY.name, revision = persisted.revision)
            CompanionSceneState.updateRoute(value)
        }
        lateinit var queue: CompanionOriginWriteQueue
        queue = CompanionOriginWriteQueue(
            dispatcher = dispatcher,
            initialRevision = persisted?.revision ?: 0L,
            persist = { origin -> CompanionPendingOperationStore.persistCurrentOrigin(appContext, origin) },
            onFailure = { origin ->
                Log.e("MimirCompanionRuntime", "failed to persist primary origin revision=${origin.revision}")
                retryScheduler.schedule(OriginPersistenceRetryDelayMs) { queue.retry(origin) }
            },
        )
        writeQueue = queue
        if (value.revision > (persisted?.revision ?: 0L)) queue.enqueue(value)
    }

    @Synchronized
    fun update(origin: PrimaryReturnOrigin) {
        val currentOrigin = GameIdentityAliasCache.canonicalize(origin)
        val previous = value
        val sameLocation =
            previous.destination == currentOrigin.destination && previous.arguments == currentOrigin.arguments
        if (sameLocation && previous.revision > 0L) return

        val baseRevision = maxOf(previous.revision, currentOrigin.revision)
        if (baseRevision == Long.MAX_VALUE) {
            Log.e("MimirCompanionRuntime", "primary origin revision exhausted")
            return
        }

        val revisioned = currentOrigin.copy(revision = baseRevision + 1L)
        value = revisioned
        CompanionSceneState.updateRoute(revisioned)
        recreationOrigin = revisioned
        writeQueue?.enqueue(revisioned)
    }

    @Synchronized
    fun adoptRecovered(origin: PrimaryReturnOrigin?) {
        val currentOrigin = origin?.let(GameIdentityAliasCache::canonicalize) ?: return
        if (currentOrigin.revision >= value.revision) {
            value = currentOrigin
            CompanionSceneState.adoptRecoveredRoute(currentOrigin)
            recreationOrigin = currentOrigin
        }
    }

    @Synchronized
    fun offerStartupRecovery(origin: PrimaryReturnOrigin?) {
        origin ?: return
        startupRecovery = GameIdentityAliasCache.canonicalize(origin)
    }

    @Synchronized
    fun consumeStartupRecovery(): PrimaryReturnOrigin? = startupRecovery.also { startupRecovery = null }

    fun activityRecreationOrigin(): PrimaryReturnOrigin? = recreationOrigin

    @Synchronized
    fun remapGameId(fromGameId: String, toGameId: String) {
        value = value.remapGameId(fromGameId, toGameId)
        startupRecovery = startupRecovery?.remapGameId(fromGameId, toGameId)
        recreationOrigin = recreationOrigin?.remapGameId(fromGameId, toGameId)
        CompanionSceneState.updateRoute(value)
    }

    @Synchronized
    internal fun resetStartupRecoveryForTest() {
        startupRecovery = null
    }

    private const val OriginPersistenceRetryDelayMs = 1_000L
}

internal fun interface CompanionOriginWriteDispatcher {
    fun dispatch(action: () -> Unit)
}

internal fun interface CompanionOriginRetryScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit)
}

/** One off-main drain owns writes; queued focus revisions coalesce to the newest unseen value. */
internal class CompanionOriginWriteQueue(
    private val dispatcher: CompanionOriginWriteDispatcher,
    initialRevision: Long,
    private val persist: (PrimaryReturnOrigin) -> Boolean,
    private val onFailure: (PrimaryReturnOrigin) -> Unit = {},
    private val maxPersistAttempts: Int = 3,
) {
    init {
        require(maxPersistAttempts > 0) { "Origin persistence attempts must be positive" }
    }
    private val lock = Any()
    private var newestEnqueuedRevision = initialRevision
    private var pending: PrimaryReturnOrigin? = null
    private var drainScheduled = false

    fun enqueue(origin: PrimaryReturnOrigin) {
        if (origin.revision <= 0L) return
        val shouldSchedule = synchronized(lock) {
            if (origin.revision <= newestEnqueuedRevision) return
            newestEnqueuedRevision = origin.revision
            pending = origin
            if (drainScheduled) {
                false
            } else {
                drainScheduled = true
                true
            }
        }
        if (shouldSchedule) dispatcher.dispatch(::drain)
    }

    fun retry(origin: PrimaryReturnOrigin) {
        val accepted = synchronized(lock) {
            if (origin.revision != newestEnqueuedRevision || pending?.revision?.let { it > origin.revision } == true) {
                false
            } else {
                newestEnqueuedRevision = origin.revision - 1L
                true
            }
        }
        if (accepted) enqueue(origin)
    }

    private fun drain() {
        while (true) {
            val origin = synchronized(lock) {
                val next = pending
                if (next == null) {
                    drainScheduled = false
                    return
                }
                pending = null
                next
            }
            var persisted = false
            repeat(maxPersistAttempts) {
                if (!persisted) persisted = runCatching { persist(origin) }.getOrDefault(false)
            }
            if (!persisted) onFailure(origin)
        }
    }
}

internal data class CompanionDurableRecord(
    val processEpoch: String? = null,
    val stateVersion: Long? = null,
    val currentOrigin: PrimaryReturnOrigin? = null,
    val pendingLease: PersistedNonMirrorLease? = null,
)

internal object CompanionDurableRecordCodec {
    const val Schema = "schema_version"
    const val Operation = "operation"
    const val Display = "display"
    const val Destination = "destination"
    const val OriginRevision = "origin_revision"
    const val Description = "description"
    const val ArgPrefix = "arg."
    const val CurrentDestination = "current.destination"
    const val CurrentRevision = "current.revision"
    const val CurrentArgPrefix = "current.arg."
    const val ProcessEpoch = "process_epoch"
    const val StateVersion = "state_version"

    private const val CurrentSchema = 2

    fun decode(values: Map<String, *>): CompanionDurableRecord {
        val schema = (values[Schema] as? Number)?.toInt()
        val schemaKind = when {
            !values.containsKey(Schema) -> SchemaKind.LEGACY
            schema == CurrentSchema -> SchemaKind.CURRENT
            else -> SchemaKind.UNKNOWN
        }
        val pending = decodePending(values, schemaKind)
        val current = if (schemaKind == SchemaKind.CURRENT) {
            decodeOrigin(
                values = values,
                destinationKey = CurrentDestination,
                revisionKey = CurrentRevision,
                argumentPrefix = CurrentArgPrefix,
                revisionRequired = true,
                minimumRevision = 1L,
            )
        } else {
            null
        }
        val epoch = (values[ProcessEpoch] as? String)?.takeIf(String::isNotBlank)
            .takeIf { schemaKind == SchemaKind.CURRENT }
        val stateVersion = longValue(values[StateVersion])
            ?.takeIf { it >= 0L && epoch != null && schemaKind == SchemaKind.CURRENT }

        return CompanionDurableRecord(
            processEpoch = epoch.takeIf { stateVersion != null },
            stateVersion = stateVersion,
            currentOrigin = current,
            pendingLease = pending,
        )
    }

    fun encode(record: CompanionDurableRecord): Map<String, Any> = buildMap {
        put(Schema, CurrentSchema)
        val epoch = record.processEpoch?.takeIf(String::isNotBlank)
        val stateVersion = record.stateVersion?.takeIf { it >= 0L }
        if (epoch != null && stateVersion != null) {
            put(ProcessEpoch, epoch)
            put(StateVersion, stateVersion)
        }
        record.currentOrigin?.takeIf { it.revision > 0L }?.let { origin ->
            put(CurrentDestination, origin.destination)
            put(CurrentRevision, origin.revision)
            origin.arguments.forEach { (key, value) -> put(CurrentArgPrefix + key, value) }
        }
        record.pendingLease?.let { pending ->
            put(Operation, pending.operationId.toString())
            put(Display, pending.displayId)
            put(Description, pending.description)
            pending.primaryOrigin?.let { origin ->
                put(Destination, origin.destination)
                put(OriginRevision, origin.revision)
                origin.arguments.forEach { (key, value) -> put(ArgPrefix + key, value) }
            }
        }
    }

    private fun decodePending(
        values: Map<String, *>,
        schemaKind: SchemaKind,
    ): PersistedNonMirrorLease? {
        val operationId = OperationId.parseOrNull(values[Operation] as? String) ?: return null
        val displayId = (values[Display] as? Number)?.toInt()?.takeIf { it >= 0 } ?: return null
        val origin = when (schemaKind) {
            SchemaKind.LEGACY -> decodeOrigin(
                values = values,
                destinationKey = Destination,
                revisionKey = null,
                argumentPrefix = ArgPrefix,
                revisionRequired = false,
                minimumRevision = 0L,
            )
            SchemaKind.CURRENT -> decodeOrigin(
                values = values,
                destinationKey = Destination,
                revisionKey = OriginRevision,
                argumentPrefix = ArgPrefix,
                revisionRequired = true,
                minimumRevision = 0L,
            )
            SchemaKind.UNKNOWN -> null
        }
        return PersistedNonMirrorLease(
            operationId = operationId,
            displayId = displayId,
            primaryOrigin = origin,
            description = values[Description] as? String ?: "persisted non-mirror operation",
        )
    }

    private fun decodeOrigin(
        values: Map<String, *>,
        destinationKey: String,
        revisionKey: String?,
        argumentPrefix: String,
        revisionRequired: Boolean,
        minimumRevision: Long,
    ): PrimaryReturnOrigin? {
        val destination = (values[destinationKey] as? String)?.takeIf(String::isNotBlank) ?: return null
        val revision = revisionKey?.let { longValue(values[it]) }
        if (revisionRequired && revision == null) return null
        val resolvedRevision = (revision ?: 0L).takeIf { it >= minimumRevision } ?: return null
        val arguments = linkedMapOf<String, String>()
        values.forEach { (key, value) ->
            if (key.startsWith(argumentPrefix)) {
                if (value !is String) return null
                arguments[key.removePrefix(argumentPrefix)] = value
            }
        }
        return runCatching { PrimaryReturnOrigin(destination, arguments, resolvedRevision) }.getOrNull()
    }

    private fun longValue(value: Any?): Long? = when (value) {
        is Long -> value
        is Int -> value.toLong()
        else -> null
    }

    private enum class SchemaKind {
        LEGACY,
        CURRENT,
        UNKNOWN,
    }
}

internal interface CompanionPersistenceBackend {
    fun read(): Map<String, *>
    fun replace(values: Map<String, Any>): Boolean
}

internal enum class CompanionPersistenceWriteResult {
    WRITTEN,
    IGNORED_STALE,
    FAILED,
}

internal class CompanionDurablePersistence(
    private val backend: CompanionPersistenceBackend,
    private val processEpoch: String,
) {
    init {
        require(processEpoch.isNotBlank()) { "Persistence process epoch is mandatory" }
    }

    @Synchronized
    fun currentOrigin(): PrimaryReturnOrigin? = record().currentOrigin

    @Synchronized
    fun recoverPending(): PersistedNonMirrorLease? {
        val durable = record()
        val pending = durable.pendingLease ?: return null
        return pending.copy(
            primaryOrigin = newestAuthoritativePrimaryOrigin(pending.primaryOrigin, durable.currentOrigin),
        )
    }

    @Synchronized
    fun persistCurrentOrigin(origin: PrimaryReturnOrigin): Boolean {
        val canonicalOrigin = GameIdentityAliasCache.canonicalize(origin)
        if (canonicalOrigin.revision <= 0L) return false
        val durable = record()
        val existing = durable.currentOrigin
        if (existing != null) {
            if (canonicalOrigin == existing) return true
            if (canonicalOrigin.revision <= existing.revision) return false
        }
        return backend.replace(
            CompanionDurableRecordCodec.encode(durable.copy(currentOrigin = canonicalOrigin)),
        )
    }

    @Synchronized
    fun remapGameId(fromGameId: String, toGameId: String): Boolean {
        val durable = record()
        val next = durable.copy(
            currentOrigin = durable.currentOrigin?.remapGameId(fromGameId, toGameId),
            pendingLease = durable.pendingLease?.let { lease ->
                lease.copy(primaryOrigin = lease.primaryOrigin?.remapGameId(fromGameId, toGameId))
            },
        )
        return next == durable || backend.replace(CompanionDurableRecordCodec.encode(next))
    }

    @Synchronized
    fun remapGameIds(aliases: Map<String, String>): Boolean {
        val durable = record()
        val next = aliases.entries.fold(durable) { current, (fromGameId, toGameId) ->
            current.copy(
                currentOrigin = current.currentOrigin?.remapGameId(fromGameId, toGameId),
                pendingLease = current.pendingLease?.let { lease ->
                    lease.copy(primaryOrigin = lease.primaryOrigin?.remapGameId(fromGameId, toGameId))
                },
            )
        }
        return next == durable || backend.replace(CompanionDurableRecordCodec.encode(next))
    }

    @Synchronized
    fun writePending(
        pending: PersistedNonMirrorLease?,
        stateVersion: Long,
    ): CompanionPersistenceWriteResult {
        if (stateVersion < 0L) return CompanionPersistenceWriteResult.FAILED
        val canonicalPending = pending?.copy(
            primaryOrigin = pending.primaryOrigin?.let(GameIdentityAliasCache::canonicalize),
        )
        val durable = record()
        val sameEpoch = durable.processEpoch == processEpoch
        val storedVersion = durable.stateVersion.takeIf { sameEpoch }
        if (storedVersion != null && stateVersion < storedVersion) {
            return CompanionPersistenceWriteResult.IGNORED_STALE
        }

        val nextPending = if (storedVersion == stateVersion) {
            mergeSameVersion(durable.pendingLease, canonicalPending)
        } else {
            canonicalPending
        }
        val next = durable.copy(
            processEpoch = processEpoch,
            stateVersion = stateVersion,
            pendingLease = nextPending,
        )
        if (next == durable) return CompanionPersistenceWriteResult.WRITTEN
        return if (backend.replace(CompanionDurableRecordCodec.encode(next))) {
            CompanionPersistenceWriteResult.WRITTEN
        } else {
            CompanionPersistenceWriteResult.FAILED
        }
    }

    private fun record(): CompanionDurableRecord = CompanionDurableRecordCodec.decode(backend.read())

    private fun mergeSameVersion(
        existing: PersistedNonMirrorLease?,
        incoming: PersistedNonMirrorLease?,
    ): PersistedNonMirrorLease? {
        if (existing == null || incoming == null) return existing
        if (existing.operationId != incoming.operationId || existing.displayId != incoming.displayId) return existing
        return existing.copy(
            primaryOrigin = newestAuthoritativePrimaryOrigin(existing.primaryOrigin, incoming.primaryOrigin),
        )
    }
}

private class SharedPreferencesCompanionBackend(
    private val preferences: SharedPreferences,
) : CompanionPersistenceBackend {
    override fun read(): Map<String, *> = preferences.all.toMap()

    override fun replace(values: Map<String, Any>): Boolean {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                else -> error("Unsupported companion persistence value ${value::class.java.name}")
            }
        }
        return editor.commit()
    }
}

/** Durable non-mirror root plus current primary origin, read before recovery can present. */
internal object CompanionPendingOperationStore {
    private const val Prefs = "mimir_companion_pending_operation"
    private val processEpoch = UUID.randomUUID().toString()

    @Volatile
    private var durable: CompanionDurablePersistence? = null

    @Synchronized
    private fun persistence(context: Context): CompanionDurablePersistence = durable ?: CompanionDurablePersistence(
        backend = SharedPreferencesCompanionBackend(
            context.applicationContext.getSharedPreferences(Prefs, Context.MODE_PRIVATE),
        ),
        processEpoch = processEpoch,
    ).also { durable = it }

    @Synchronized
    fun persist(
        context: Context,
        record: PersistedNonMirrorLease,
        stateVersion: Long,
    ): Boolean = persistence(context).writePending(record, stateVersion) ==
        CompanionPersistenceWriteResult.WRITTEN

    @Synchronized
    fun persistCurrentOrigin(context: Context, origin: PrimaryReturnOrigin): Boolean =
        persistence(context).persistCurrentOrigin(origin)

    @Synchronized
    fun currentOrigin(context: Context): PrimaryReturnOrigin? = persistence(context).currentOrigin()

    @Synchronized
    fun reconcile(context: Context, snapshot: CompanionRuntimeSnapshot): Boolean {
        val record = when (val state = snapshot.disposition) {
            is SecondaryDisplayDisposition.LaunchPending -> PersistedNonMirrorLease(
                state.lease.operationId,
                state.lease.displayId,
                state.lease.primaryOrigin,
                "launch:${state.lease.packageName}:${state.phase}",
            )
            is SecondaryDisplayDisposition.Yielded -> state.lease?.let { lease ->
                PersistedNonMirrorLease(
                    lease.operationId,
                    lease.displayId,
                    lease.primaryOrigin,
                    "yielded:${lease.packageName}",
                )
            }
            is SecondaryDisplayDisposition.ReclaimPending -> PersistedNonMirrorLease(
                state.lease.operationId,
                state.lease.displayId,
                state.lease.primaryOrigin,
                "reclaim:${state.phase}",
            )
            is SecondaryDisplayDisposition.RecoveryNeeded -> PersistedNonMirrorLease(
                state.operationId,
                state.displayId,
                state.fallback,
                "recovery:${state.failure.code}",
            )
            else -> null
        }
        return persistence(context).writePending(record, snapshot.stateVersion) !=
            CompanionPersistenceWriteResult.FAILED
    }

    @Synchronized
    fun read(context: Context): PersistedNonMirrorLease? = persistence(context).recoverPending()

    @Synchronized
    fun remapGameId(context: Context, fromGameId: String, toGameId: String): Boolean {
        val written = persistence(context).remapGameId(fromGameId, toGameId)
        if (written) CompanionPrimaryOriginStore.remapGameId(fromGameId, toGameId)
        return written
    }

    @Synchronized
    fun remapGameIds(context: Context, aliases: Map<String, String>): Boolean {
        val written = persistence(context).remapGameIds(aliases)
        if (written) {
            aliases.forEach { (fromGameId, toGameId) ->
                CompanionPrimaryOriginStore.remapGameId(fromGameId, toGameId)
            }
        }
        return written
    }
}

internal fun PrimaryReturnOrigin.remapGameId(
    fromGameId: String,
    toGameId: String,
): PrimaryReturnOrigin {
    if (fromGameId == toGameId) return this
    val oldFocus = "library/gamelist/$fromGameId"
    val newFocus = "library/gamelist/$toGameId"
    val mapped = arguments.mapValues { (key, value) ->
        when {
            key == "detailGameId" && value == fromGameId -> toGameId
            value == oldFocus -> newFocus
            value == fromGameId && key.contains("game", ignoreCase = true) -> toGameId
            else -> value
        }
    }
    return if (mapped == arguments) this else copy(arguments = mapped)
}

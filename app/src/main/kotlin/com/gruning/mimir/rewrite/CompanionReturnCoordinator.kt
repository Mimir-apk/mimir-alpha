package com.gruning.mimir.rewrite

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract

/**
 * Process gateway used by return-host Activities and compatibility call sites in MainActivity.
 *
 * It deliberately holds no Activity/controller reference. Android callbacks land directly in the
 * process [CompanionRuntimeCoordinator], while Activity-bound rendering is supplied by a token-owned
 * [CompanionHostPort].
 */
internal object CompanionReturnCoordinator {
    const val EXTRA_OPERATION_ID = CompanionReturnHostContract.EXTRA_OPERATION_ID

    private const val Tag = "MimirCompanionRuntime"

    /**
     * P1: the runtime disposition line's exact text, pulled out of the logger lambda so a JVM unit test
     * can pin it without touching `android.util.Log`. `from`/`to` keep their existing position so current
     * log readers still parse them; `pkg`/`mode`/`reason`/`fail` are appended.
     */
    internal fun formatRuntimeLogEvent(event: CompanionRuntimeLogEvent): String =
        "v=${event.stateVersion} event=${event.event} root=${event.rootOperationId} " +
            "render=${event.renderOperationId} owner=${event.rendererOwnerId} " +
            "${event.from}->${event.to} pkg=${event.pkg} mode=${event.mode} " +
            "reason=${event.reason} fail=${event.fail}"

    /** P16 (hygiene): a short disposition summary for logging — class name plus the lease package/op, never the full `toString()`. */
    internal fun dispositionLogSummary(disposition: SecondaryDisplayDisposition): String {
        val name = disposition::class.simpleName
        val pkg: String?
        val op: OperationId?
        when (disposition) {
            is SecondaryDisplayDisposition.NoDisplay -> {
                pkg = null
                op = null
            }
            is SecondaryDisplayDisposition.Presenting -> {
                pkg = null
                op = disposition.attempt.operationId
            }
            is SecondaryDisplayDisposition.MirrorPresented -> {
                pkg = null
                op = disposition.attempt.operationId
            }
            is SecondaryDisplayDisposition.LaunchPending -> {
                pkg = disposition.lease.packageName
                op = disposition.lease.operationId
            }
            is SecondaryDisplayDisposition.Yielded -> {
                pkg = disposition.lease?.packageName
                op = disposition.lease?.operationId
            }
            is SecondaryDisplayDisposition.ReclaimPending -> {
                pkg = disposition.lease.coveredHost?.packageName
                op = disposition.lease.operationId
            }
            is SecondaryDisplayDisposition.RecoveryNeeded -> {
                pkg = null
                op = disposition.operationId
            }
        }
        return "$name package=$pkg op=$op"
    }

    private data class HostKey(
        val packageName: String,
        val displayId: Int?,
        val launchMode: String,
    )

    internal data class ReturnOperationContext(
        val operationId: OperationId,
        val primaryOrigin: PrimaryReturnOrigin?,
    )

    @Volatile
    private var runtimeOverride: CompanionRuntimeCoordinator? = null

    @Volatile
    private var primaryOriginProvider: () -> PrimaryReturnOrigin = { PrimaryReturnOrigin.LegacyCurrent }

    private val processRuntime: CompanionRuntimeCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CompanionRuntimeCoordinator(
            scheduler = MainThreadRuntimeScheduler(),
            logger = CompanionRuntimeLogger { event ->
                runCatching { Log.i(Tag, formatRuntimeLogEvent(event)) }
            },
            sessionObserver = MimirCompanionRuntimeSessionBridge,
            activeGameOperation = {
                MimirSessionAuthority.state.value.gameSession
                    ?.takeIf { companionMainDualScreenGameActive(it) }
                    ?.operation?.value
            },
        )
    }

    internal fun runtime(): CompanionRuntimeCoordinator = runtimeOverride ?: processRuntime

    /** Application graph seam for the integration owner. Install before any companion callback. */
    internal fun installRuntime(runtime: CompanionRuntimeCoordinator) {
        runtimeOverride = runtime
    }

    internal fun resetTestOverrides() {
        runtimeOverride = null
        floorCoverWatch = null
        floorResumedDisplay = null
        deferredFloorReclaimDisplay = null
        autoReclaimBudget = FloorReclaimBudget(gameSequence = ::currentGameSequence)
        primaryOriginProvider = { PrimaryReturnOrigin.LegacyCurrent }
    }

    /** Supplies the exact primary navigation origin captured at launch time. */
    internal fun installPrimaryOriginProvider(provider: () -> PrimaryReturnOrigin) {
        primaryOriginProvider = provider
    }

    internal fun currentPrimaryOrigin(): PrimaryReturnOrigin = primaryOriginProvider()

    /**
     * Mimir's OWN secondary-home floor (`CompanionSecondaryHomeActivity`) was resumed on [displayId].
     *
     * Device evidence, 2026-09-11: after `am force-stop` + restart with an app previously yielded on
     * the Bottom, the runtime logs `ProcessRecovery NoDisplay->RecoveryNeeded`, then
     * `RendererBound`/`DisplayAvailableObserved` land while still `RecoveryNeeded` — so display 4
     * shows this floor as a bare black window until the user does the right-edge swipe
     * (`explicitReclaim` -> `PresentationShown`).
     *
     * Mimir's own secondary home being resumed proves no foreign task covers display 4 — Android only
     * resumes a home activity when nothing else owns that display's top task. `RecoveryNeeded` exists
     * to protect against exactly the case where something else might still be covering the screen, so
     * with that possibility ruled out there is nothing left for it to protect, and the shelf can be
     * re-presented without waiting on a gesture the player should not have needed.
     *
     * Every other disposition is left alone: `NoDisplay`, `LaunchPending`, `Presenting`, `MirrorPresented`
     * and `ReclaimPending` all already have their own settled or in-flight owner for the display, and forcing
     * a reclaim underneath one of those would double-drive the same `explicitReclaim` guard this floor's
     * resume is meant to satisfy, not race.
     *
     * `Yielded` is the exception, and since 2026-10-08  every host-covered lease is in it, not only the
     * D41 task Mimir did not launch ([CompanionFloorCoverWatch], lease mode [LaunchModeRecentsPick]). A Quick
     * Launched app's own return host is how Mimir learns the app left, but only while that host is what
     * resumes. A HOME press on the Bottom (`KEYCODE_HOME -d 4`) starts the floor over the app and its host
     * together; both go behind it, the host never resumes, and the lease sat unresolved for good (Thor,
     * `r3-log-excerpt.txt`: `disposition=Yielded package=com.android.chrome autoReclaim=false`, the Presentation
     * parked behind a floor that draws a bare cover). The floor being the resumed activity is the proof no app
     * covers the display, so Mimir comes back. The one `Yielded` left alone is a DS/3DS lower screen
     * ([CompanionReturnHostContract.LAUNCH_MODE_EXISTING_MAIN_GAME_RETURN]): that surface is the emulator's own
     * window above a floor that stays resumed beneath it, so the floor resuming proves nothing there.
     */
    fun noteSecondaryHomeResumed(displayId: Int?) {
        floorResumedDisplay = displayId
        floorCoverWatch?.floorResumed(displayId)
        reclaimOverResumedFloor(displayId, "secondary home resumed")
    }

    /**
     * Which display the floor is the resumed activity on right now (set by [noteSecondaryHomeResumed], cleared by [noteFloorPaused]); null when it is on none.
     * Read when a launch's host settles as covered ([hostCovered]): a HOME press on the Bottom DURING a launch resumed the floor while the launch was still
     * `LaunchPending`, which the resume ignores by design, so the host's cover can arrive with the floor already on top and nothing left to resume it again.
     */
    @Volatile
    private var floorResumedDisplay: Int? = null

    /**
     * How many automatic reclaims in a row may fail before the automatic paths stop issuing them ([FloorReclaimBudget]): ONE budget shared by the
     * floor's resume ([reclaimOverResumedFloor], its replay in [noteDisplayObserved]) and Main's ([reclaimOnPrimaryResume]), so the two cannot
     * reset each other. A new game launch starts the count over; the ledger's sequence is what says a launch happened ([currentGameSequence]).
     */
    private var autoReclaimBudget = FloorReclaimBudget(gameSequence = ::currentGameSequence)

    private fun currentGameSequence(): Long? = MimirSessionAuthority.state.value.gameSession?.sequence

    /**
     * The one body behind the floor's two moments: it resumed ([noteSecondaryHomeResumed]), and a launch's host covered it while it was already the resumed
     * top ([hostCovered]). The disposition decides ([secondaryHomeResumeShouldReclaim]); [FloorReclaimBudget] bounds how often a failing reclaim is retried.
     */
    private fun reclaimOverResumedFloor(displayId: Int?, what: String) {
        val disposition = runtime().snapshot.value.disposition
        val autoReclaim = secondaryHomeResumeShouldReclaim(disposition)
        runCatching {
            Log.i(
                Tag,
                "$what display=$displayId disposition=${dispositionLogSummary(disposition)} " +
                    "autoReclaim=$autoReclaim",
            )
        }
        val verdict = autoReclaimBudget.onFloorResume(disposition)
        if (!autoReclaim) {
            deferredFloorReclaimDisplay = null
            return
        }
        if (verdict != FloorReclaimBudget.Verdict.ALLOW) {
            deferredFloorReclaimDisplay = null
            if (verdict == FloorReclaimBudget.Verdict.GIVE_UP) {
                val failed = (disposition as? SecondaryDisplayDisposition.RecoveryNeeded)?.operationId
                if (failed != null) {
                    runCatching { Log.i(Tag, FloorReclaimBudget.giveUpLogLine(displayId, autoReclaimBudget.failures(), failed)) }
                }
            }
            return
        }
        // Parking both displays: a HOME press over a Bottom GAME parks it. The reclaim then carries its own reason, which keeps the game's session.
        val parksBottomGame = runCatching { MainGameHomePark.onBottomFloorHome(disposition) }.getOrDefault(false)
        val reclaim = runtime().explicitReclaim(
            reason = if (parksBottomGame) BottomHomeParkReclaimReason else "secondary_home_resumed",
            primaryOrigin = currentPrimaryOrigin(),
            returningFromPackage = secondaryHomeResumeReturningPackage(disposition),
        )
        reclaim?.let { autoReclaimBudget.issued(it.operationId) }
        if (reclaim == null && displayId != null && floorReclaimDeferralApplies(runtime().snapshot.value, displayId)) {
            // D2 (QA 2026-09-11): the floor resumed before the display selector reported the recovered display in
            // this process, so the runtime refused (its guard needs availableDisplayId, deliberately -- a stale
            // request must never reclaim on observation alone). Keep the FLOOR's intent here and re-issue it from
            // noteDisplayObserved(); before this the Bottom sat on the recovery cover until a manual swipe.
            deferredFloorReclaimDisplay = displayId
            runCatching {
                Log.i(
                    Tag,
                    "secondary home resume reclaim deferred display=$displayId " +
                        "availableDisplay=${runtime().snapshot.value.availableDisplayId}",
                )
            }
        }
    }

    /**
     * MainActivity resumed with the Bottom in [disposition]. [wanted] is the resume's own answer, [shouldReclaimCompanionAfterPrimaryResume] with
     * a Bottom port to render into; the budget hears every resume regardless, so a presented or yielded Bottom resets it, and a give-up is
     * said once whether or not this resume wanted a reclaim. Returns whether a reclaim was issued, `reason = "primary_activity_resumed"` as
     * before (the floor-front allow-list, [reclaimRequestsFloorFront], keys on it).
     *
     * P1 (combined43 sweep, 2026-10-15): a restore guard that keeps missing its deadline resumed Main, and that resume reclaimed again, for
     * good: streaks of 7, 25 and 21 on the starved emulator, each guard taking top focus from Main. The budget bounds the LOOP: at most
     * [FloorReclaimBudget.DefaultLimit] automatic reclaims per episode, then a `RecoveryNeeded` with no guard in flight, left to the player's
     * own paths (an edge swipe, the menu's Return to shelf, a tab close), to a new launch, or to the next episode. It does not by itself
     * complete the game-return ticket: Main's window focus is global on the Thor and the floor kept it after the give-up (logcat 001905 from
     * 01:26:48, five minutes with no guard up), so the ticket's gate is [primaryHostOwnsDisplay], which takes Mimir's own Bottom holding
     * the focus as Main owning its display.
     *
     * An EPISODE ends with a stop the PLAYER caused. [resumedAfterStop] (a real stop, then this start) begins a new one only when the stop was
     * not the loop's own: the guard cycle STOPS Main (logcat 001905: `Activity reported stop` on 26 of the 25-reclaim streak's cycles, 23 of
     * 21, 8 of 7; Lead's re-check of round 2), so a stop alone would have re-armed every cycle and brought the streaks back whole. The budget
     * decides from the Bottom's state at the stop ([notePrimaryStopped], [FloorReclaimBudget.hostStopped]): a stop with a reclaim of its own
     * in flight, or with that reclaim's recovery not yet counted by any resume, keeps the episode; a stop with none (Recents, another app, a
     * HOME press from another app, the screen off) begins a new one, so a later return still reclaims after a give-up. The reset re-arms the
     * floor's count too, since the budget is shared: a starved device re-arms at most two reclaims per stop of the player's.
     */
    fun reclaimOnPrimaryResume(disposition: SecondaryDisplayDisposition, wanted: Boolean, resumedAfterStop: Boolean = false): Boolean {
        if (resumedAfterStop) autoReclaimBudget.hostResumedAfterStop()
        val verdict = autoReclaimBudget.onResume(disposition)
        if (verdict == FloorReclaimBudget.Verdict.GIVE_UP) {
            val failed = disposition as? SecondaryDisplayDisposition.RecoveryNeeded
            if (failed != null) {
                runCatching {
                    Log.i(Tag, FloorReclaimBudget.primaryGiveUpLogLine(failed.displayId, autoReclaimBudget.failures(), failed.operationId))
                }
            }
        }
        if (!wanted || verdict != FloorReclaimBudget.Verdict.ALLOW) return false
        val reclaim = runtime().explicitReclaim(reason = "primary_activity_resumed", primaryOrigin = currentPrimaryOrigin())
        reclaim?.let { autoReclaimBudget.issued(it.operationId) }
        return reclaim != null
    }

    /**
     * MainActivity stopped, a real stop (not a configuration change). The budget notes whether the stop fell inside a reclaim cycle of its
     * own, so the resume that follows ([reclaimOnPrimaryResume] with `resumedAfterStop`) knows whether the player left or the guard covered
     * Main. Main thread, from `onStop`.
     */
    fun notePrimaryStopped() {
        autoReclaimBudget.hostStopped(runtime().snapshot.value.disposition)
    }

    /**
     * D41: the floor is no longer the resumed activity on [displayId]. Opens the bounded [CompanionFloorCoverWatch] when Mimir is presenting there;
     * the watch yields the Bottom if a foreign task turns out to have covered it. Main thread, like every floor callback.
     */
    fun noteSecondaryHomePaused(context: Context, displayId: Int?) {
        noteFloorPaused(displayId)
        val watch = floorCoverWatch ?: CompanionFloorCoverWatch.forProcess(
            context = context,
            snapshot = { runtime().snapshot.value },
            yieldTo = { display, pkg -> runtime().yieldToForeignTask(display, pkg, currentPrimaryOrigin()) },
        ).also { floorCoverWatch = it }
        watch.floorPaused(displayId)
    }

    /** The floor is no longer the resumed activity on [displayId] (null: wherever it was): see [floorResumedDisplay]. Context-free so the sequence is testable. */
    internal fun noteFloorPaused(displayId: Int?) {
        if (displayId == null || floorResumedDisplay == displayId) floorResumedDisplay = null
    }

    /** D41: the floor stopped on [displayId], so whatever covers it is opaque. A finishing or reconfiguring floor is not a cover. */
    fun noteSecondaryHomeStopped(displayId: Int?, finishing: Boolean, changingConfigurations: Boolean) {
        floorCoverWatch?.floorStopped(displayId, finishing, changingConfigurations)
    }

    /** Built on the first floor pause; touched only on the main thread. */
    private var floorCoverWatch: CompanionFloorCoverWatch? = null

    /**
     * The display selector just reported [displayId] to the runtime. If the floor's own resume asked for a
     * reclaim that the runtime had to refuse for want of exactly this observation, issue it now, once.
     */
    fun noteDisplayObserved(displayId: Int) {
        val deferred = deferredFloorReclaimDisplay ?: return
        if (!floorReclaimReplayDue(deferred, displayId, runtime().snapshot.value.disposition)) return
        deferredFloorReclaimDisplay = null
        val reclaim = runtime().explicitReclaim(reason = "secondary_home_resumed", primaryOrigin = currentPrimaryOrigin())
        reclaim?.let { autoReclaimBudget.issued(it.operationId) }
        runCatching {
            Log.i(Tag, "secondary home resume reclaim replayed display=$displayId issued=${reclaim != null}")
        }
    }

    /** The floor's auto-reclaim that the runtime refused because the selector had not observed the display yet. */
    @Volatile
    private var deferredFloorReclaimDisplay: Int? = null

    fun bind(controller: CompanionDisplayController) {
        controller.attachRuntime(runtime())
    }

    fun unbind(controller: CompanionDisplayController) {
        controller.detachRuntime(runtime())
    }

    /** Mint the mandatory UUID root before the registry constructs its return-host Intent. */
    fun noteHostLaunchStarting(packageName: String, displayId: Int?, launchMode: String): String? {
        val key = hostKey(packageName, displayId, launchMode) ?: return null
        val lease = runtime().launchRequested(
            packageName = key.packageName,
            displayId = key.displayId,
            launchMode = key.launchMode,
            primaryOrigin = primaryOriginProvider(),
        ) ?: return null
        val rawOperationId = lease.operationId.toString()
        if (key.launchMode != CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME) {
            runCatching { java.util.UUID.fromString(rawOperationId) }
                .getOrNull()
                ?.let { CompanionRunningAppState.armSecondaryReturn(it, key.packageName) }
        }
        return rawOperationId
    }

    /** Legacy name retained for callers that only signal the start callback. */
    fun noteHostStarted(packageName: String, displayId: Int?, launchMode: String): Boolean {
        val resolved = resolveReturnOperation(null, packageName, displayId, launchMode) ?: return false
        return runtime().hostStarted(resolved.operationId)
    }

    internal fun resolveReturnOperation(
        rawOperationId: String?,
        packageName: String,
        displayId: Int?,
        launchMode: String,
    ): ReturnOperationContext? {
        val explicit = OperationId.parseOrNull(rawOperationId)
        if (explicit == null) return null
        val runtime = runtime()
        if (!runtime.recognizes(explicit)) return null
        val host = runtime.hostLease(explicit)
        if (host != null) {
            val key = hostKey(packageName, displayId, launchMode) ?: return null
            if (host.packageName != key.packageName || host.displayId != key.displayId || host.launchMode != key.launchMode) {
                return null
            }
        } else {
            val restoreMode = launchMode == CompanionReturnHostContract.LAUNCH_MODE_RESTORE_ONLY ||
                launchMode == CompanionReturnHostContract.LAUNCH_MODE_HOME_RECLAIM
            if (!restoreMode || packageName.isNotBlank() || displayId == null ||
                runtime.snapshot.value.displayId != displayId || !runtime.isReclaimRoot(explicit)
            ) {
                return null
            }
        }
        return ReturnOperationContext(explicit, runtime.operationOrigin(explicit))
    }

    internal fun hostStarted(operationId: OperationId): Boolean = runtime().hostStarted(operationId)

    internal fun targetDispatched(operationId: OperationId): Boolean = runtime().targetDispatched(operationId)

    internal fun yieldForExistingSecondaryGame(
        parentOperationId: OperationId,
        packageName: String,
        primaryOrigin: PrimaryReturnOrigin,
    ): Boolean = runtime().yieldForExistingSecondaryGame(parentOperationId, packageName, primaryOrigin)

    /**
     * The launch's host stopped under its target: `Yielded`. If the floor is the resumed activity on the lease's display at this moment, nothing covers
     * it but the floor (the earlier update, code review: HOME on the Bottom between dispatch and cover, seconds long for a slow emulator), and no later resume will say
     * so, so the same reclaim the floor's own resume would have issued is issued now.
     */
    internal fun hostCovered(operationId: OperationId): Boolean {
        val runtime = runtime()
        val lease = runtime.hostLease(operationId)
        val covered = runtime.hostCovered(operationId)
        if (covered && lease != null && floorResumedDisplay == lease.displayId) {
            reclaimOverResumedFloor(lease.displayId, "secondary host covered under a resumed floor")
        }
        return covered
    }

    internal fun hostReturned(
        operationId: OperationId,
        beforeEffects: (ReclaimLease) -> Unit = {},
    ): ReclaimLease? = runtime().hostReturned(
        operationId = operationId,
        currentPrimaryOrigin = primaryOriginProvider(),
        beforeEffects = beforeEffects,
    )

    internal fun restoreGuardForeground(operationId: OperationId): Boolean =
        runtime().restoreGuardForeground(operationId)

    internal fun launchFailed(operationId: OperationId, reason: String): Boolean {
        val runtime = runtime()
        val host = runtime.hostLease(operationId)
        val failed = runtime.launchFailed(operationId, reason)
        if (failed) revokeRunningAppSecondaryReturn(host)
        return failed
    }

    fun launchFailed(rawOperationId: String?, reason: String): Boolean =
        OperationId.parseOrNull(rawOperationId)?.let { launchFailed(it, reason) } ?: false

    internal fun hostDestroyed(operationId: OperationId): Boolean {
        val runtime = runtime()
        val host = runtime.hostLease(operationId)
        val destroyed = runtime.hostDestroyed(operationId)
        if (destroyed) revokeRunningAppSecondaryReturn(host)
        return destroyed
    }

    internal fun recoverPersistedNonMirror(record: PersistedNonMirrorLease): Boolean =
        runtime().recoverPersistedNonMirror(record, primaryOriginProvider())

    /**
     * Kept source-compatible for the old host-failure callback. Empty or malformed ids now fail closed;
     * the integration owner must thread the UUID into that callback before it can settle a flight.
     */
    fun restoreFromSecondaryHost(reason: String, sessionId: String = ""): Boolean {
        val operationId = OperationId.parseOrNull(sessionId)
        if (operationId == null) {
            Log.w(Tag, "rejected restore callback without UUID reason=$reason")
            return false
        }
        return when (runtime().snapshot.value.disposition) {
            is SecondaryDisplayDisposition.ReclaimPending -> restoreGuardForeground(operationId)
            is SecondaryDisplayDisposition.Yielded -> hostReturned(operationId) != null
            else -> false
        }
    }

    /** Untokened teardown cannot infer return. Retained only as a no-op source adapter. */
    fun notifySecondaryHostGone(alreadyRestored: Boolean): Boolean {
        Log.w(Tag, "rejected untokened host teardown alreadyRestored=$alreadyRestored")
        return false
    }

    private fun hostKey(packageName: String, displayId: Int?, launchMode: String): HostKey? {
        val cleanPackage = packageName.trim()
        val cleanMode = launchMode.trim()
        if (cleanPackage.isEmpty() || cleanMode.isEmpty() || displayId == null || displayId < 0) return null
        return HostKey(cleanPackage, displayId, cleanMode)
    }

    private fun revokeRunningAppSecondaryReturn(host: HostLease?) {
        val lease = host ?: return
        if (lease.launchMode == CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME ||
            lease.launchMode == CompanionReturnHostContract.LAUNCH_MODE_EXISTING_MAIN_GAME_RETURN
        ) return
        val operationId = runCatching { java.util.UUID.fromString(lease.operationId.toString()) }.getOrNull() ?: return
        cancelCompanionRunningAppSecondaryReturn(operationId, lease.packageName)
    }

    private class MainThreadRuntimeScheduler : CompanionRuntimeScheduler {
        private val handler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }

        override fun schedule(
            delayMillis: Long,
            operationId: OperationId,
            action: () -> Unit,
        ): RuntimeCancellation {
            val runnable = Runnable(action)
            handler.postDelayed(runnable, delayMillis)
            return RuntimeCancellation { handler.removeCallbacks(runnable) }
        }
    }
}

/**
 * D2 (QA 2026-09-11): a refused floor auto-reclaim is worth keeping only when the refusal was the runtime's
 * "display not observed yet in this process" guard -- a PROCESS_RECOVERED_NON_MIRROR recovery for this very
 * display whose `availableDisplayId` is still unset or different. Anything else was refused for a reason a later
 * observation cannot change.
 */
internal fun floorReclaimDeferralApplies(snapshot: CompanionRuntimeSnapshot, displayId: Int): Boolean {
    val recovered = snapshot.disposition as? SecondaryDisplayDisposition.RecoveryNeeded ?: return false
    return recovered.displayId == displayId &&
        recovered.failure.code == FailureCode.PROCESS_RECOVERED_NON_MIRROR &&
        snapshot.availableDisplayId != displayId
}

/** The deferred floor reclaim runs exactly when its display is the one observed and recovery is still pending there. */
internal fun floorReclaimReplayDue(
    deferredDisplay: Int?,
    observedDisplay: Int,
    disposition: SecondaryDisplayDisposition,
): Boolean = deferredDisplay == observedDisplay &&
    disposition is SecondaryDisplayDisposition.RecoveryNeeded &&
    disposition.displayId == observedDisplay

/**
 * Pure seam for [CompanionReturnCoordinator.noteSecondaryHomeResumed]: `RecoveryNeeded` and a host-covered `Yielded` lease are the dispositions a bare resume of
 * Mimir's own secondary-home floor may act on (the floor on top proves nothing covers the display) — see that function's KDoc for why the other dispositions each
 * already have a settled or in-flight owner for it.
 */
internal fun secondaryHomeResumeShouldReclaim(disposition: SecondaryDisplayDisposition): Boolean =
    disposition is SecondaryDisplayDisposition.RecoveryNeeded || floorResumeLeaseOf(disposition) != null

/**
 * The package the resumed floor proves is no longer in front, for a host-covered lease. The caller has watched it go (D41, and the earlier update for a HOME press on the
 * Bottom): a failed reclaim is a real failure, and does not retreat to a host that is not covering the display any more. A retreat would be the
 * floor-over-an-app state this resume exists to end, with nothing left to resolve it.
 */
internal fun secondaryHomeResumeReturningPackage(disposition: SecondaryDisplayDisposition): String? =
    floorResumeLeaseOf(disposition)?.packageName

/**
 * The lease a floor resume may reclaim over: a `Yielded` host-covered app of any launch mode but a DS/3DS lower screen (see [CompanionReturnCoordinator.noteSecondaryHomeResumed]).
 * Null for everything else, including a `Yielded` with no lease and the explicit user-requested game return.
 */
private fun floorResumeLeaseOf(disposition: SecondaryDisplayDisposition): HostLease? {
    val yielded = disposition as? SecondaryDisplayDisposition.Yielded ?: return null
    if (yielded.evidence != YieldEvidence.HOST_COVERED) return null
    return yielded.lease?.takeUnless { it.launchMode == CompanionReturnHostContract.LAUNCH_MODE_EXISTING_MAIN_GAME_RETURN }
}

package com.gruning.mimir.rewrite

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.Display
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardResume
import com.gruning.mimir.rewrite.feature.library.ParkedLeaseReleaseOutcome
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pressing HOME while a single-screen game is running used to end
 * Mimir's session within half a second, `game_returning` then `game_ended`, while the emulator was still alive. Now the game
 * is parked behind Mimir and the session lives until the emulator really exits.
 *
 * **Why HOME ended it.** The HOME press reaches `MainActivity.onNewIntent` as `not_in_front` (the game held the screen), a no-op
 * by design (`MimirHomeKeyRoute`). `onResume` then reads the stop ticket, and `PrimaryStopResumeLedger.observeResume` keeps the
 * game in it, because neither of its two withholds applied: `mButtonCommandInterval` is only raised by an M-button command, and
 * `isCurrentParkedGame` needs a [MainGameParkingLease], which `MainGameParkingState.candidate` refused for any game that is not
 * dual-screen. `settleReturningGameSession` then filed the return.
 *
 * **What parks it.** The same two withholds, no new ledger rule:
 *  1. Synchronously, in `onNewIntent` (which precedes the resume it causes): raise D3's withhold
 *     ([PrimaryActivityObservationState.noteMButtonCommandDelivered]) and mark the park pending, so `MainActivity.onResume`
 *     does not arm the dispatch watchdog, start the scene's foreground observer, or drop the Bottom's NOW PLAYING (which carries
 *     Quit) for a game that is still running. The audio gate, the running-app note and the Bottom reclaim run as for any return.
 *  2. Off the main thread: resolve the game's task through the vendor gateway (bounded by [HomeParkResolveTimeoutMs]) and arm
 *     a [MainGameParkingLease.homeParked] lease with it. The lease is what the Bottom's Quit, `releaseParkedGameIfGone` and
 *     [homeParkedPress] act on. The measured elapsed time is finished at the moment of the press, as HOME did before: parked
 *     time is not played time, and Resume starts a new segment.
 *
 * **Who it is for.** A game on Main, while the vendor gateway is READY. A dual-screen game
 * (melonDS, Azahar) parks the same way. It keeps running behind Mimir with its Bottom window under it, so the park also asks the Bottom
 * to come back to Mimir's companion ([HomeParkPorts.reclaimBottom], the edge-back reclaim), and Resume fronts it on both displays: the
 * Bottom hands its screen to the emulator's own window, then Main's task comes forward ([HomeResumePorts.yieldBottom], the sequence
 * the Bottom's "Return to game" has always used). If the Bottom has nothing to hand over, Resume is the plain Main focus.
 *
 * **HOME on the Bottom parks too .** A game placed on the Bottom used to have its session ended when HOME put the secondary-home
 * floor over it (the floor's reclaim returned the game). Now the floor asks first ([onBottomFloorHome]): a settled Bottom game is parked with the same
 * lease, watch and proof-only release, on the Bottom display it was found on ([MainGameParkingLease.bottom], [MainGameParkingLease.displayId]), and
 * the floor reclaims under [BottomHomeParkReclaimReason] so the session bridge keeps the session. Mimir's companion is on the Bottom again and the
 * game keeps running under it. Resume is the game tab's own reopen, which the bridge adopts into the live session; seeing the game in front again
 * ends the park ([bottomGameFronted]). Edge-back is another path and still ends the session.
 *
 * **A gateway that cannot answer never holds a single-screen game.** A DS lease retains on an unanswered probe, because a live
 * Lime3DS was once orphaned by a timeout. A single-screen HOME lease does the opposite: when the gateway is unavailable or does not
 * answer (the task lookup, the exit probe, the Quit, the Resume), the lease is released and the session ended, which is exactly what
 * HOME did before F3. Ending the session does not stop the emulator, so nothing is lost but the Resume, and no launch is ever blocked
 * behind a probe that cannot answer. A dual-screen HOME lease keeps the DS retention policy at those press paths
 * ([releasesOnUnansweredGateway]): ending its session would orphan a live emulator window under Mimir's Bottom. The same goes for the HOME
 * press's own task lookup : a dual-screen game on Main whose lookup the gateway did not answer keeps its session, un-parked, until
 * something proves it gone ([holdsSessionOnUnansweredLookup]); the earlier update gives that session its proof: while Mimir is in front the lookup is asked again every 5 s
 * ([MainGameHomeParker.retryKept]), and a task found parks it in full, a task gone or a gateway not READY ends it. The 5 s watch is
 * the one exception for both: it runs with nobody pressing anything, so it ends a session only on proof (the task is gone, or the
 * gateway is not READY, see [releaseHomeParkedOnProof]) and a transient empty answer keeps the lease for the next tick.
 *
 * **When it ends.** The existing release sites act on any lease (Main resuming after a stop, a Play press, the Tabs liveness
 * read, the Bottom's Quit), through [releaseParkedLease]. On top, [ParkedGameExitWatch] probes every [HomeParkWatchPollMs] while
 * Mimir is in front, so an emulator that exits or crashes behind Home ends the session without waiting for a press.
 *
 * **F1 is not touched by it.** A parked session is YIELDED and the dispatch watchdog arms only on DISPATCHED, so a parked exit
 * can never read as "closed unexpectedly".
 */
internal enum class HomeParkVerdict(val token: String) {
    PARK("park"),
    PENDING("pending"),
    ALREADY_PARKED("already_parked"),
    NOT_COVERED("not_covered"),
    NOT_MAIN_WINDOW("not_main_window"),
    NO_GAME("no_game"),
    NOT_ON_MAIN("not_on_main"),
    NOT_ON_BOTTOM("not_on_bottom"),
    NOT_SETTLED("not_settled"),
    NO_PRIVILEGE("no_privilege"),
}

/** A game that has covered Mimir and holds the screen. DISPATCHED is not settled yet; RETURNING and later are over. */
private val HomeParkablePhases = setOf(
    MimirSessionPhase.FOREGROUND_PROVEN,
    MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
)

/**
 * The one rule. [decision] is the HOME router's: only `not_in_front` means a game (or anything else) held the screen when HOME
 * arrived. [privilegeReady] is read last, only for a press that would otherwise park.
 */
internal fun decideHomePark(
    decision: MainHomeKeyDecision,
    source: MainHomeKeySource,
    game: MimirGameSession?,
    inFlight: Boolean,
    parked: Boolean,
    privilegeReady: () -> Boolean,
): HomeParkVerdict = when {
    decision != MainHomeKeyDecision.NOT_IN_FRONT -> HomeParkVerdict.NOT_COVERED
    source != MainHomeKeySource.MAIN -> HomeParkVerdict.NOT_MAIN_WINDOW
    game == null -> HomeParkVerdict.NO_GAME
    game.placement != MimirGamePlacement.MAIN -> HomeParkVerdict.NOT_ON_MAIN
    game.phase !in HomeParkablePhases -> HomeParkVerdict.NOT_SETTLED
    inFlight -> HomeParkVerdict.PENDING
    parked -> HomeParkVerdict.ALREADY_PARKED
    !privilegeReady() -> HomeParkVerdict.NO_PRIVILEGE
    else -> HomeParkVerdict.PARK
}

/**
 * Parking both displays: the one rule for HOME on the Bottom. [covered] is the floor's finding that a host-covered game lease sat under it; [game] is the
 * session that lease belongs to. Only a Bottom game that is settled (proven in front, or yielded) and not parked or being parked is parked; every other
 * case leaves the floor's reclaim to end the session as it always has. The vendor gateway is not asked here: an unready one fails the park's task
 * lookup, and a park that fails ends the session, which is exactly what the reclaim would have done.
 */
internal fun decideBottomHomePark(
    covered: Boolean,
    game: MimirGameSession?,
    inFlight: Boolean,
    parked: Boolean,
): HomeParkVerdict = when {
    !covered -> HomeParkVerdict.NOT_COVERED
    game == null -> HomeParkVerdict.NO_GAME
    game.placement != MimirGamePlacement.BOTTOM -> HomeParkVerdict.NOT_ON_BOTTOM
    game.phase !in HomeParkablePhases -> HomeParkVerdict.NOT_SETTLED
    inFlight -> HomeParkVerdict.PENDING
    parked -> HomeParkVerdict.ALREADY_PARKED
    else -> HomeParkVerdict.PARK
}

/**
 * What the secondary-home floor found under it when HOME on the Bottom resumed it: the host lease that covered display 4, and the live Bottom game that
 * lease belongs to (null for an app, or a game this process has no live session for). Null when no host-covered lease sat under the floor at all.
 */
internal fun bottomHomeTarget(
    disposition: SecondaryDisplayDisposition,
    session: MimirSessionSnapshot,
): Pair<HostLease, MimirGameSession?>? {
    val yielded = disposition as? SecondaryDisplayDisposition.Yielded ?: return null
    if (yielded.evidence != YieldEvidence.HOST_COVERED) return null
    val host = yielded.lease ?: return null
    return host to session.liveBottomGameFor(host.packageName)
}

/**
 * a dual-screen game on Main is not ended by a task lookup the gateway did not answer. Its emulator window lives on the Bottom too, so
 * ending the session orphans it (the DS ruling [releasesOnUnansweredGateway] keeps at the press paths). A single-screen game, or one placed
 * on the Bottom, orphans nothing and still lets go, exactly as HOME did before F3.
 */
internal fun holdsSessionOnUnansweredLookup(game: MimirGameSession): Boolean =
    game.dualScreen && game.placement == MimirGamePlacement.MAIN

/** The longest the HOME press waits for the vendor gateway to name the game's task. A timeout reads as "unknown". */
internal const val HomeParkResolveTimeoutMs: Long = 2_500L

/** Where the abandoned lookups finish: a scope of its own, so a caller that stops waiting does not wait for them either. */
private val HomeParkLookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * [block]'s answer, or null when it has not answered within [timeoutMs]. [block] is a blocking call that runs on [scope]'s own
 * thread; this function waits for it, not on it, so the caller is released at [timeoutMs] while the call is only abandoned:
 * a blocking call cannot be interrupted, its thread is held until it returns, and its late answer is discarded. In production
 * the vendor gateway's own 2 s per-call limit is the real bound and answers first; [HomeParkResolveTimeoutMs] is the backstop
 * for a call that outlives even that.
 */
internal suspend fun <T : Any> answerWithin(
    timeoutMs: Long,
    scope: CoroutineScope = HomeParkLookupScope,
    block: () -> T?,
): T? {
    val call = scope.async { block() }
    val answer = withTimeoutOrNull(timeoutMs) { call.await() }
    if (answer == null) call.cancel()
    return answer
}

/** What the parker reads and does outside the decision, so it is testable without an Activity or a device. */
internal class HomeParkPorts(
    val currentGame: () -> MimirGameSession?,
    val isParked: (MimirGameSession) -> Boolean,
    val privilegeReady: () -> Boolean,
    /** D3's withhold: the resume this HOME press causes does not start the game's return. */
    val withholdReturn: () -> Unit,
    /**
     * What the gateway says about the game's task on the display it lives on, in ONE call: [VendorTaskOutcome.Found] names the task,
     * [VendorTaskOutcome.NoTask] is proof it is gone, [VendorTaskOutcome.Unavailable] is a lookup that did not answer (a timeout included),
     * which is proof of nothing . Runs off the main thread.
     */
    val resolveTaskOutcome: suspend (packageName: String) -> VendorTaskOutcome,
    /** Main thread. The lease it armed, or null when the session moved on or the arm was refused. */
    val arm: (game: MimirGameSession, taskId: Int) -> MainGameParkingLease?,
    /** Main thread. Ends the session of a game that could not be parked: what HOME did before F3. */
    val endSession: (MimirGameSession) -> Unit,
    val startWatch: (MainGameParkingLease) -> Unit,
    /**
     * Main thread. a dual-screen game was parked, so Mimir takes the Bottom back from the emulator's window (the edge-back
     * reclaim, [reclaimBottomForHomePark]). Never called for a single-screen game; a refused or failed reclaim leaves the park as it is.
     */
    val reclaimBottom: (game: MimirGameSession) -> Unit = {},
    /** Finishes the game's measured elapsed time at [atElapsedMs], the moment of the press, as HOME did before. */
    val finalizeElapsed: suspend (game: MimirGameSession, atElapsedMs: Long) -> Unit,
    val elapsedNow: () -> Long,
    /**
     * The game's total in the elapsed store as it stands now, before [finalizeElapsed] adds the finished segment to it (null when it
     * cannot be read). The Bottom's "before this session" line is this figure, not the live total, which counts the same minutes the clock shows.
     */
    val totalBefore: suspend (MimirGameSession) -> Long? = { null },
    /** What the Bottom's NOW PLAYING reads: the finished segments, and the total before the session. A fresh one per bench; production passes [homeParkSegments]. */
    val segments: HomeParkSegmentLedger = HomeParkSegmentLedger(),
    /** Runs [block] off the caller's stack; the caller is `onNewIntent` and must not wait for the vendor. */
    val schedule: (block: suspend () -> Unit) -> Unit,
    val log: (String) -> Unit,
    /** true while Mimir is in front. A kept session's retry asks the gateway only then, as the 5 s watch does (nothing is probed while the player is in the game). */
    val mimirResumed: () -> Boolean = { true },
    /** waits [HomeParkWatchPollMs] between a kept session's retry asks. A bench passes its own so no test sleeps. */
    val pause: suspend (Long) -> Unit = { delay(it) },
    /** true when the player has launched an app since [game] was kept (a game launched since replaces the session, which [currentGame] shows). */
    val appLaunchedSince: (MimirGameSession) -> Boolean = { false },
    /**
     * runs a kept session's retry loop apart from the park that kept it, on the same scope the park runs on (the process scope, Main): the loop's
     * mutations ([arm], [endSession], [startWatch], [reclaimBottom]) are the Main-thread ones the park itself uses. Nothing is started by default.
     */
    val scheduleRetry: (block: suspend () -> Unit) -> Unit = {},
    /**
     * the gateway's posture status, for the kept session's retry, which must tell a gateway that is down from one that is reconnecting
     * ([gatewayProvenDown]). Blocking (posture() re-attests the transport on every call), so the retry reads it off the main thread. The default
     * reads [privilegeReady]: READY or, when it is not, a failed connection.
     */
    val gatewayStatus: () -> PrivilegedUtilityStatus = {
        if (privilegeReady()) PrivilegedUtilityStatus.READY else PrivilegedUtilityStatus.CONNECTION_FAILED
    },
)

internal class MainGameHomeParker(private val ports: HomeParkPorts) {
    /** The operation whose lease is being resolved. Read through [pending]. */
    @Volatile
    private var inFlightOperation: String? = null

    /** True from the HOME press until the lease is armed or the park has failed. */
    fun pending(): Boolean = inFlightOperation != null

    /** Called from `onNewIntent`, main thread, before the resume the press causes. */
    fun onHomeKey(decision: MainHomeKeyDecision, source: MainHomeKeySource): HomeParkVerdict {
        val game = ports.currentGame()
        val verdict = decideHomePark(
            decision = decision,
            source = source,
            game = game,
            inFlight = game != null && inFlightOperation == game.operation.value,
            parked = game != null && ports.isParked(game),
            privilegeReady = ports.privilegeReady,
        )
        if (verdict == HomeParkVerdict.PARK && game != null) {
            inFlightOperation = game.operation.value
            ports.withholdReturn()
            val pressedAt = ports.elapsedNow()
            ports.schedule { complete(game, pressedAt) }
        }
        if (decision == MainHomeKeyDecision.NOT_IN_FRONT) {
            ports.log("home park verdict=${verdict.token} op=${game?.operation?.value}")
        }
        return verdict
    }

    /**
     * HOME on the Bottom found [game] covering it ([covered]: the floor resumed over a host-covered game lease). Called on the main
     * thread by the floor, BEFORE its reclaim, so the reclaim can be issued under the reason that keeps the session ([BottomHomeParkReclaimReason]).
     * True when this press is parking the game. The lease, the elapsed bookkeeping and the watch are the asynchronous half, [complete], the same as
     * Main's; no return needs withholding here (nothing on Main stopped).
     */
    fun onBottomHome(game: MimirGameSession?, covered: Boolean): HomeParkVerdict {
        val verdict = decideBottomHomePark(
            covered = covered,
            game = game,
            inFlight = game != null && inFlightOperation == game.operation.value,
            parked = game != null && ports.isParked(game),
        )
        if (verdict == HomeParkVerdict.PARK && game != null) {
            inFlightOperation = game.operation.value
            val pressedAt = ports.elapsedNow()
            ports.schedule { complete(game, pressedAt) }
        }
        if (covered) ports.log("bottom home park verdict=${verdict.token} op=${game?.operation?.value}")
        return verdict
    }

    /** The asynchronous half. Module-visible for the tests; production reaches it only through [onHomeKey] and [onBottomHome]. */
    internal suspend fun complete(game: MimirGameSession, pressedAtElapsedMs: Long) {
        try {
            val packageName = game.targetPackageName?.trim()?.takeIf(String::isNotEmpty)
            val outcome = packageName?.let { ports.resolveTaskOutcome(it) }
            val taskId = (outcome as? VendorTaskOutcome.Found)?.taskId?.takeIf { it > 0 }
            val lease = taskId?.let { ports.arm(game, it) }
            if (lease == null) {
                if (packageName != null && outcome is VendorTaskOutcome.Unavailable && holdsSessionOnUnansweredLookup(game)) {
                    // The gateway did not answer, which does not say the game is gone. Ending the session would
                    // orphan its live window on the Bottom, so it stays open and is ended by whatever later proves it (Quit, the Tabs
                    // liveness read, the task being gone). No lease is armed: nothing here knows a task to hold. The measured time stops
                    // where the player left it, as a park's does: it is token-matched, so the finish site that closes the session later
                    // finds it already closed and adds nothing for the minutes the game sat behind Mimir.
                    // the total is taken first, as a park takes it, because the store adds the finished segment on the finish below.
                    // The segment is recorded here too, so NOW PLAYING holds still at the press instead of falling back to the time since
                    // dispatch; a park of the same operation later (the retry's, or a second HOME press) then adds nothing, and its total
                    // before this session is the one taken here.
                    val totalBefore = runCatching { ports.totalBefore(game) }.getOrNull()
                    stopClock(game, pressedAtElapsedMs, totalBefore)
                    ports.log("home park unanswered op=${game.operation.value} pkg=$packageName session kept")
                    startKeptRetry(game, packageName, pressedAtElapsedMs, totalBefore)
                } else {
                    ports.log("home park failed op=${game.operation.value} pkg=$packageName task=$taskId")
                    ports.endSession(game)
                }
            } else {
                // The game's measured time stops where the player left it; the parked hours are not played time. The clock holds still
                // and the total is taken first, so the store adding this segment never shows it twice.
                park(game, lease, pressedAtElapsedMs, runCatching { ports.totalBefore(game) }.getOrNull())
            }
        } finally {
            inFlightOperation = null
        }
    }

    /**
     * The HOME press's own bookkeeping, for a park and for a kept session alike: the running segment ends at the press and the measured clock stops
     * there. Both are token-matched, so doing it a second time for the same operation adds nothing.
     */
    private suspend fun stopClock(game: MimirGameSession, pressedAtElapsedMs: Long, totalBefore: Long?) {
        ports.segments.parked(game.operation, game.dispatchedAtElapsedMs, pressedAtElapsedMs, totalBefore)
        runCatching { ports.finalizeElapsed(game, pressedAtElapsedMs) }
    }

    /** The armed half of a park, shared by the HOME press and the kept session's retry: the segment, the finished clock, the watch and, for a dual-screen game, the Bottom taken back. */
    private suspend fun park(game: MimirGameSession, lease: MainGameParkingLease, pressedAtElapsedMs: Long, totalBefore: Long?) {
        stopClock(game, pressedAtElapsedMs, totalBefore)
        ports.startWatch(lease)
        if (lease.dualScreen && !lease.bottom) runCatching { ports.reclaimBottom(game) }
        ports.log("home park armed op=${lease.operation.value} pkg=${lease.targetPackageName} task=${lease.taskId} dual=${lease.dualScreen}")
    }

    /** Which kept session the running retry belongs to; a newer kept session (a second HOME press) retires the older loop at its next tick. */
    private val keptRetryGeneration = AtomicInteger(0)

    private fun startKeptRetry(game: MimirGameSession, packageName: String, pressedAtElapsedMs: Long, totalBefore: Long?) {
        val generation = keptRetryGeneration.incrementAndGet()
        ports.scheduleRetry {
            try {
                retryKept(game, packageName, pressedAtElapsedMs, totalBefore, generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // A step of the retry that threw is not proof of anything: the retry stops and the session stays kept, as the earlier update left it.
                ports.log("home park retry failed op=${game.operation.value} pkg=$packageName ${failure.javaClass.simpleName}")
            }
        }
    }

    /**
     * The gateway's status for the retry. posture() re-attests the transport on every call: blocking, so it runs off the main thread the loop
     * runs on. A read that throws is a failed connection; being cancelled is not an answer and is passed on, so a stopped loop never ends a session.
     */
    private suspend fun gatewayStatusOffMain(): PrivilegedUtilityStatus = try {
        withContext(Dispatchers.IO) { ports.gatewayStatus() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unreadable: Exception) {
        PrivilegedUtilityStatus.CONNECTION_FAILED
    }

    /** The task lookup for the retry: a lookup that throws did not answer; being cancelled is passed on. */
    private suspend fun askTaskOutcome(packageName: String): VendorTaskOutcome = try {
        ports.resolveTaskOutcome(packageName)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        VendorTaskOutcome.Unavailable("unavailable")
    }

    /** What the retry may still do about [game], read fresh each time it is asked. */
    private enum class KeptRetryGuard {
        /** The kept session is still the player's, nothing was launched, and Mimir is in front: the retry may ask and act. */
        HOLDS,

        /** The player is back in the game: nothing is asked and an answer already in hand is discarded, but the retry goes on. */
        BEHIND_GAME,

        /** A newer kept session, a launch, a lease, or the session's end: the retry is over and leaves the session as it is. */
        OVER,
    }

    /**
     * The one guard for everything the retry does. It runs before each ask and again after each answer, because an answer can arrive seconds after the
     * ask (the gateway's own limit is 2 s, [HomeParkResolveTimeoutMs] 2.5 s) and the player may have gone back into the game, or launched something,
     * in between: an answer that fails the guard is discarded, never acted on.
     */
    private fun keptRetryGuard(game: MimirGameSession, generation: Int): KeptRetryGuard {
        if (generation != keptRetryGeneration.get()) return KeptRetryGuard.OVER
        val current = ports.currentGame()
        if (current == null || current.operation != game.operation || current.phase !in HomeParkablePhases ||
            ports.appLaunchedSince(game) || ports.isParked(game)
        ) {
            return KeptRetryGuard.OVER
        }
        return if (ports.mimirResumed()) KeptRetryGuard.HOLDS else KeptRetryGuard.BEHIND_GAME
    }

    /**
     * **the earlier update.** A kept dual-screen session has no lease, no exit watch, no Bottom reclaim and no Resume, because the park that would give it
     * those waited on a task lookup the gateway never answered. While Mimir is in front, the same single-call lookup is asked again every
     * [HomeParkWatchPollMs]:
     *  - **Found:** the full park, as the press would have done (the lease, the 5 s watch, the Bottom reclaim, Resume), with the press's own time and total.
     *  - **NoTask, or a gateway proven down** ([gatewayProvenDown]: not READY and not BINDING): the session ends (its clock stopped at the press).
     *  - **Anything else** (an answer that did not come, a gateway that is READY or still reconnecting, a refused arm): the session stays kept and the
     *    next tick asks again, until the cap.
     * Every ask and every answer passes [keptRetryGuard]. The retry stops, leaving the session as it was, when the player launched anything (the game
     * was replaced or ended, or an app was launched since), when a lease now holds the game, when a newer kept session replaced it, and after
     * [KeptSessionRetryMaxAsks] asks (the status quo of nothing ends it without proof). Ticks while the player is in the game are not asks and
     * do not count toward the cap.
     */
    internal suspend fun retryKept(game: MimirGameSession, packageName: String, pressedAtElapsedMs: Long, totalBefore: Long?, generation: Int) {
        var asks = 0
        while (asks < KeptSessionRetryMaxAsks) {
            ports.pause(HomeParkWatchPollMs)
            when (keptRetryGuard(game, generation)) {
                KeptRetryGuard.OVER -> return retryOver(game, packageName, asks)
                KeptRetryGuard.BEHIND_GAME -> continue
                KeptRetryGuard.HOLDS -> Unit
            }
            asks++
            val outcome = askTaskOutcome(packageName)
            when (keptRetryGuard(game, generation)) {
                KeptRetryGuard.OVER -> return retryOver(game, packageName, asks)
                KeptRetryGuard.BEHIND_GAME -> continue
                KeptRetryGuard.HOLDS -> Unit
            }
            when (outcome) {
                is VendorTaskOutcome.Found -> {
                    val taskId = outcome.taskId.takeIf { it > 0 } ?: continue
                    val lease = ports.arm(game, taskId)
                    if (lease == null) {
                        // Another lease holds the screen, or the session moved on: not proof of anything. The guard ends the retry at the next
                        // tick if the session really moved on; otherwise the next ask tries again, until the cap.
                        ports.log("home park retry arm refused op=${game.operation.value} pkg=$packageName task=$taskId")
                        continue
                    }
                    park(game, lease, pressedAtElapsedMs, totalBefore)
                    return
                }
                is VendorTaskOutcome.NoTask -> {
                    ports.log("home park retry ended op=${game.operation.value} pkg=$packageName by=task gone")
                    ports.endSession(game)
                    return
                }
                else -> {
                    val status = gatewayStatusOffMain()
                    when (keptRetryGuard(game, generation)) {
                        KeptRetryGuard.OVER -> return retryOver(game, packageName, asks)
                        KeptRetryGuard.BEHIND_GAME -> continue
                        KeptRetryGuard.HOLDS -> Unit
                    }
                    if (gatewayProvenDown(status)) {
                        ports.log("home park retry ended op=${game.operation.value} pkg=$packageName by=gateway down status=${status.name.lowercase()}")
                        ports.endSession(game)
                        return
                    }
                }
            }
        }
        ports.log("home park retry gave up op=${game.operation.value} pkg=$packageName asks=$asks session kept")
    }

    private fun retryOver(game: MimirGameSession, packageName: String, asks: Int) {
        ports.log("home park retry over op=${game.operation.value} pkg=$packageName asks=$asks")
    }
}

/**
 * how many times a kept session's task lookup is asked again before the retry stops and leaves the session kept. 60 asks at the
 * 5 s pace is five minutes of Mimir in front: long enough for a gateway that was busy at the press, short of asking forever about a game
 * the player may have simply left running.
 */
internal const val KeptSessionRetryMaxAsks: Int = 60

/** true when an app was launched after the kept [game] (they share one sequence), which ends the kept session's claim on the screen. */
internal fun appLaunchedAfterKeptGame(snapshot: MimirSessionSnapshot, game: MimirGameSession): Boolean =
    snapshot.appSession?.let { it.sequence > game.sequence } == true

/** How often a parked game's task is looked up while Mimir is in front. Nothing is probed while the player is in the game. */
internal const val HomeParkWatchPollMs: Long = 5_000L

/**
 * Ends a HOME-parked session when its emulator exits behind Home. [releaseIfGone] is [releaseHomeParkedOnProofIfGone] in
 * production: the watch ends a session only on proof, a task the gateway says is gone or a gateway that is not READY. One empty
 * or failed answer keeps the lease and the next tick asks again, because a lost Resume starts a second game over a running one.
 */
internal class ParkedGameExitWatch(
    private val currentLease: () -> MainGameParkingLease?,
    private val mimirResumed: () -> Boolean,
    private val releaseIfGone: suspend (MainGameParkingLease) -> Boolean,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val pollMs: Long = HomeParkWatchPollMs,
) {
    /** Returns when [expected] is released, replaced or cleared (by a Resume, a Quit or a release elsewhere). */
    suspend fun run(expected: MainGameParkingLease) {
        while (true) {
            pause(pollMs)
            val current = currentLease()
            if (current == null || current != expected) return
            if (!mimirResumed()) continue
            if (releaseIfGone(current)) return
        }
    }
}

/**
 * A HOME lease holds a game only while the gateway can say the task still exists. [VendorTaskOutcome.Found] keeps it; "no
 * task" and every kind of "cannot say" release it (see the class note on [MainGameHomePark]).
 */
internal suspend fun releaseHomeParkedWhenNotFound(
    lease: MainGameParkingLease,
    resolve: suspend () -> VendorTaskOutcome,
    release: suspend (lease: MainGameParkingLease, endedBy: String) -> Boolean,
    log: (String) -> Unit,
): Boolean {
    val outcome = runCatching { resolve() }.getOrDefault(VendorTaskOutcome.Unavailable("unavailable"))
    if (outcome is VendorTaskOutcome.Found) {
        log("home park retained op=${lease.operation.value} task=${outcome.taskId}")
        return false
    }
    return release(lease, if (outcome is VendorTaskOutcome.NoTask) "parked task gone" else "parked task unknown")
}

/**
 * The 5 s watch releases a HOME lease when the game's task is definitely gone
 * ([VendorTaskOutcome.NoTask]) or the vendor gateway is proven down ([gatewayProvenDown]: not READY, down or poisoned for the process, but
 * not merely BINDING, which is the bridge reconnecting).
 * A transient empty or failed answer ([VendorTaskOutcome.Unavailable], or a throw) while the gateway is READY or still binding keeps the lease; the
 * next tick checks again. The press paths keep [releaseHomeParkedWhenNotFound], which releases on any answer but a found task.
 */
internal suspend fun releaseHomeParkedOnProof(
    lease: MainGameParkingLease,
    resolve: suspend () -> VendorTaskOutcome,
    gatewayStatus: suspend () -> PrivilegedUtilityStatus,
    release: suspend (lease: MainGameParkingLease, endedBy: String) -> Boolean,
    log: (String) -> Unit,
): Boolean {
    val outcome = runCatching { resolve() }.getOrDefault(VendorTaskOutcome.Unavailable("unavailable"))
    return when (outcome) {
        is VendorTaskOutcome.Found -> {
            log("home park retained op=${lease.operation.value} task=${outcome.taskId}")
            false
        }
        is VendorTaskOutcome.NoTask -> release(lease, "parked task gone")
        else -> {
            val status = runCatching { gatewayStatus() }.getOrDefault(PrivilegedUtilityStatus.CONNECTION_FAILED)
            if (gatewayProvenDown(status)) {
                release(lease, "parked gateway down")
            } else {
                log("home park kept op=${lease.operation.value} probe=unanswered gateway=${status.name.lowercase()}")
                false
            }
        }
    }
}

/**
 * Whether a gateway [status] proves the gateway is down, the only reading of it that may end a session. READY is up. BINDING is the bridge
 * reconnecting: it proves nothing, and the next ask is made once it has bound. Every other state that
 * is not READY, a stopped service, a refused permission, a failed connection, stays the final answer the watch has always taken it for.
 */
internal fun gatewayProvenDown(status: PrivilegedUtilityStatus): Boolean =
    status != PrivilegedUtilityStatus.READY && status != PrivilegedUtilityStatus.BINDING

/** Clears [lease] and ends its session, only if it is still the process's lease. Main thread. True when it released. */
internal suspend fun releaseHomeParkedLease(lease: MainGameParkingLease, endedBy: String): Boolean =
    withContext(Dispatchers.Main.immediate) {
        if (MainGameParkingState.currentFor(lease.operation, lease.gameId, lease.targetPackageName) != lease) {
            return@withContext false
        }
        MainGameParkingState.clear(lease)
        MimirCompanionRuntimeSessionBridge.endBottomGameForTabClose(
            lease.targetPackageName,
            endedBy = endedBy,
            expectedOperation = lease.operation,
        )
        true
    }

private fun homeParkRelease(stage: String): suspend (MainGameParkingLease, String) -> Boolean = { held, endedBy ->
    releaseHomeParkedLease(held, endedBy).also { released ->
        if (released) runCatching { Log.i("MimirHomePark", "home park released op=${held.operation.value} by=$endedBy stage=$stage") }
    }
}

private fun homeParkLog(stage: String): (String) -> Unit = { line -> runCatching { Log.i("MimirHomePark", "$line stage=$stage") } }

private suspend fun homeParkTaskOutcome(context: Context, lease: MainGameParkingLease): VendorTaskOutcome =
    withContext(Dispatchers.IO) {
        CompanionProcessRuntime.services(context).privilegedUtility
            .resolveTaskOutcome(lease.targetPackageName, lease.displayId)
    }

internal suspend fun releaseHomeParkedGameIfGone(context: Context, lease: MainGameParkingLease, stage: String): Boolean =
    releaseHomeParkedWhenNotFound(
        lease = lease,
        resolve = { homeParkTaskOutcome(context, lease) },
        release = homeParkRelease(stage),
        log = homeParkLog(stage),
    )

/** [releaseHomeParkedOnProof] against the live gateway: the watch's release. [stage] is for the log. */
internal suspend fun releaseHomeParkedOnProofIfGone(context: Context, lease: MainGameParkingLease, stage: String): Boolean =
    releaseHomeParkedOnProof(
        lease = lease,
        resolve = { homeParkTaskOutcome(context, lease) },
        gatewayStatus = {
            // posture() re-attests the transport on every call: blocking, so never on the main thread.
            withContext(Dispatchers.IO) {
                CompanionProcessRuntime.services(context).privilegedUtility.posture().status
            }
        },
        release = homeParkRelease(stage),
        log = homeParkLog(stage),
    )

/**
 * The one release every caller of a parked lease uses: a single-screen HOME lease by the rule above, a dual-screen one (HOME-parked
 * or not) by the unchanged `releaseParkedGameIfGone` (an unanswered probe retains, by ruling).
 */
internal suspend fun releaseParkedLease(context: Context, lease: MainGameParkingLease, stage: String): Boolean =
    if (lease.releasesOnUnansweredGateway) {
        releaseHomeParkedGameIfGone(context, lease, stage)
    } else {
        CompanionProcessRuntime.releaseParkedGameIfGone(context, lease, stage)
    }

/** What the Resume press did. */
internal enum class HomeResumeOutcome { FRONTED, FAILED, RELEASED }

internal class HomeResumePorts(
    val beginRestoring: (MainGameParkingLease) -> Boolean,
    val cancelRestoring: (MainGameParkingLease) -> Boolean,
    /** Off the main thread. */
    val focusTask: suspend (taskId: Int) -> PrivilegedTaskFocus,
    /** The task is in front: a new measured segment starts. Best effort. */
    val afterFronted: suspend (MainGameParkingLease) -> Unit,
    /** The gateway cannot front it: clear the lease and end the session, as HOME did before F3. */
    val releaseLease: suspend (MainGameParkingLease) -> Unit,
    /** Ends the parked game for good, through the Bottom's confirmed Quit ([nowPlayingQuitPerform]); true only when it has ended. */
    val endGame: suspend (MainGameParkingLease) -> Boolean,
    /**
     * the earlier update, dual-screen only, before the task is fronted: Mimir's Bottom hands its screen to the emulator's own window ([yieldBottomForHomeResume]).
     * True when it did; false when the Bottom had nothing to hand over (no mirror shown), and Resume is then the plain Main focus.
     */
    val yieldBottom: suspend (MainGameParkingLease) -> Boolean = { false },
    /** the focus failed after [yieldBottom] said true, so Mimir takes the Bottom back ([restoreBottomAfterFailedHomeResume]). */
    val restoreBottom: suspend (MainGameParkingLease) -> Unit = {},
    /**
     * Review P2, dual-screen and Bottom leases, before anything is handed over or fronted: false while the park's own reclaim of the Bottom is still in
     * flight after a bounded wait ([awaitHomeParkReclaimSettled]). A Resume that fronted the game under that reclaim would have Mimir's Presentation
     * land over the live lower screen, so it refuses instead, with its usual "try again" sentence.
     */
    val bottomReady: suspend (MainGameParkingLease) -> Boolean = { true },
    /**
     * For Bottom leases only, in place of [focusTask]: re-fronts the parked game on the Bottom ([reopenBottomForHomeResume], the game tab's
     * own reopen). FOCUSED means the launch was accepted; the lease clears and the next segment starts when the game is seen in front
     * ([MainGameHomePark.bottomGameFronted]), so [afterFronted] is not called for it.
     */
    val reopenBottom: suspend (MainGameParkingLease) -> PrivilegedTaskFocus = { PrivilegedTaskFocus.FAILED },
    /**
     * The hand-back to the game begins: called once everything that can refuse has passed and BEFORE the task is fronted, so the music lets go of audio focus
     * before the game's own onResume asks for it (Main is still resumed until the game's window is up). Best effort.
     */
    val handBackBegin: (MainGameParkingLease) -> Unit = {},
    /** The fronting was refused or the gateway released the lease: the game is not coming up, so the hand-back is over. Best effort. */
    val handBackEnd: (MainGameParkingLease) -> Unit = {},
)

/**
 * Fronts a parked game's task. The lease is marked restoring first, so Main's next stop (the game coming up over it) clears it
 * the same way a dual-screen Return does ([MainGameParkingState.clearRestoringOnPrimaryStop]); a refused focus gives the mark
 * back and keeps the lease for another try, and a gateway that is not there at all releases it. No intent is sent and nothing
 * is reaped: the emulator keeps the game it has.
 *
 * for a dual-screen game the Bottom is handed to the emulator's own window first and taken back if the focus fails, the order the
 * Bottom's "Return to game" uses. A gateway that is not there retains a dual-screen lease ([releasesOnUnansweredGateway]).
 *
 * A Bottom lease is re-fronted on the Bottom by the game tab's own reopen ([HomeResumePorts.reopenBottom]); nothing is marked
 * restoring (the mark is cleared by Main stopping, which a Bottom game never causes).
 */
internal suspend fun resumeHomeParkedGame(lease: MainGameParkingLease, ports: HomeResumePorts): HomeResumeOutcome {
    if (lease.bottom) {
        if (!runCatching { ports.bottomReady(lease) }.getOrDefault(true)) return HomeResumeOutcome.FAILED
        runCatching { ports.handBackBegin(lease) }
        return when (runCatching { ports.reopenBottom(lease) }.getOrDefault(PrivilegedTaskFocus.FAILED)) {
            PrivilegedTaskFocus.FOCUSED -> HomeResumeOutcome.FRONTED
            else -> {
                runCatching { ports.handBackEnd(lease) }
                HomeResumeOutcome.FAILED
            }
        }
    }
    if (!ports.beginRestoring(lease)) return HomeResumeOutcome.FAILED
    if (lease.dualScreen && !runCatching { ports.bottomReady(lease) }.getOrDefault(true)) {
        ports.cancelRestoring(lease)
        return HomeResumeOutcome.FAILED
    }
    runCatching { ports.handBackBegin(lease) }
    val bottomYielded = lease.dualScreen && runCatching { ports.yieldBottom(lease) }.getOrDefault(false)
    suspend fun refused(): HomeResumeOutcome {
        runCatching { ports.handBackEnd(lease) }
        ports.cancelRestoring(lease)
        if (bottomYielded) runCatching { ports.restoreBottom(lease) }
        return HomeResumeOutcome.FAILED
    }
    return when (ports.focusTask(lease.taskId)) {
        PrivilegedTaskFocus.FOCUSED -> {
            runCatching { ports.afterFronted(lease) }
            HomeResumeOutcome.FRONTED
        }
        PrivilegedTaskFocus.AUTHORITY_UNAVAILABLE -> if (lease.releasesOnUnansweredGateway) {
            runCatching { ports.handBackEnd(lease) }
            ports.cancelRestoring(lease)
            ports.releaseLease(lease)
            HomeResumeOutcome.RELEASED
        } else {
            refused()
        }
        else -> refused()
    }
}

/**
 * The confirmed end of a parked game: [quit] is the Bottom's Quit (null when the session is already gone). A gateway that is
 * not there ([PrivilegedCloseOutcome.AUTHORITY_UNAVAILABLE]) cannot close anything, so the lease is released instead and the
 * new launch goes ahead, as it did before F3 (a dual-screen lease is kept instead, [releasesOnUnansweredGateway]: ending its session
 * would orphan the emulator's Bottom window). Any other refusal ends nothing.
 */
internal suspend fun endHomeParkedGame(
    lease: MainGameParkingLease,
    quit: suspend () -> PrivilegedCloseOutcome?,
    release: suspend (MainGameParkingLease) -> Unit,
): Boolean {
    val outcome = quit() ?: return true
    return when (outcome) {
        PrivilegedCloseOutcome.STOPPED -> true
        PrivilegedCloseOutcome.AUTHORITY_UNAVAILABLE -> if (lease.releasesOnUnansweredGateway) {
            release(lease)
            true
        } else {
            false
        }
        else -> false
    }
}

/** How long a "End X and start Y?" question waits for its second Play press. */
internal const val HomeParkedEndConfirmWindowMs: Long = 8_000L

/**
 * The confirmation behind "End <running game> and start <new game>?". The one-game rule is not bypassed and nothing is ended
 * silently: the first Play press on another game only asks (a message on Mimir's rail, the component every refusal here already
 * uses), and a second press on the same game within [windowMs] is the yes. A press on any other game starts a new question.
 */
internal class EndParkedGameConfirm(
    private val windowMs: Long = HomeParkedEndConfirmWindowMs,
    private val now: () -> Long,
) {
    private var askedOperation: String? = null
    private var askedGame: GameId? = null
    private var askedAt: Long = 0L

    /** True when this press answers a standing question; otherwise records the question and returns false. */
    @Synchronized
    fun confirms(parkedOperation: String, pressedGame: GameId): Boolean {
        val t = now()
        val standing = askedOperation == parkedOperation && askedGame == pressedGame && t - askedAt in 0..windowMs
        if (standing) {
            askedOperation = null
            askedGame = null
            return true
        }
        askedOperation = parkedOperation
        askedGame = pressedGame
        askedAt = t
        return false
    }
}

/** What Main's refused Play press turned out to be about a HOME-parked game. */
internal enum class HomeParkedPress {
    /** Nothing HOME-parked held it: today's refusal sentence stands. */
    NOT_PARKED,

    /** The press was answered here (resumed, asked, or told it failed): show nothing more. */
    ANSWERED,

    /** The parked game was ended (or released) on this press: admit the launch again, in this same press. */
    ENDED_PARKED_GAME,
}

/**
 * A Play press that Main's mutex refused, after release-at-the-press has already had its chance (a parked task that is gone was
 * released there, and that press launches as it always did).
 *  - The SAME game's Play resumes it: its task comes forward, with no new intent and no reap.
 *  - ANOTHER game's Play follows the one-game rule: it names the running game and asks "End <it> and start <this>?"; the second
 *    press on the same game ends it and lets the launch through.
 *  - the SAME game's Play that the card's Restart filed ([restart]) ends the parked session instead of fronting it, through the
 *    same confirmed Quit as the question above, with no question: the player already said which game and what to do. The launch is
 *    admitted again in this same press, so it starts the game fresh. A Quit that does not finish says so and starts nothing.
 * Any other refusal (a dual-screen lease, nothing parked) is [HomeParkedPress.NOT_PARKED].
 */
internal suspend fun handleHomeParkedPress(
    lease: MainGameParkingLease?,
    pressedGame: GameId,
    pressedTitle: String,
    ports: HomeResumePorts,
    confirm: EndParkedGameConfirm,
    titleOf: (MainGameParkingLease) -> String?,
    emitMessage: (MimirMessageClass, String) -> Unit,
    restart: Boolean = false,
): HomeParkedPress {
    if (lease == null || !lease.homeParked) return HomeParkedPress.NOT_PARKED
    val title = titleOf(lease)
    if (restart && lease.gameId == pressedGame) {
        if (ports.endGame(lease)) return HomeParkedPress.ENDED_PARKED_GAME
        emitMessage(MimirMessageClass.Warning, homeParkedEndFailedMessage(title))
        return HomeParkedPress.ANSWERED
    }
    if (lease.gameId == pressedGame) {
        return when (resumeHomeParkedGame(lease, ports)) {
            HomeResumeOutcome.FRONTED -> HomeParkedPress.ANSWERED
            HomeResumeOutcome.RELEASED -> HomeParkedPress.ENDED_PARKED_GAME
            HomeResumeOutcome.FAILED -> {
                emitMessage(MimirMessageClass.Warning, homeParkedResumeFailedMessage(title))
                HomeParkedPress.ANSWERED
            }
        }
    }
    if (!confirm.confirms(lease.operation.value, pressedGame)) {
        emitMessage(MimirMessageClass.Warning, homeParkedEndAndStartQuestion(title, pressedTitle))
        return HomeParkedPress.ANSWERED
    }
    if (ports.endGame(lease)) return HomeParkedPress.ENDED_PARKED_GAME
    emitMessage(MimirMessageClass.Warning, homeParkedEndFailedMessage(title))
    return HomeParkedPress.ANSWERED
}

/**
 * The production entry both Home Play sites call. [restart] is the card's Restart request, which the site took from [HomeLaunchCardResume.consumeRestart]
 * at the entry of its Play path (before it admitted, released or probed anything), so a press that never reaches this call cannot leave it standing.
 */
internal suspend fun homeParkedPress(
    context: Context,
    pressedGame: GameId,
    pressedTitle: String,
    emitMessage: (MimirMessageClass, String) -> Unit,
    restart: Boolean = false,
): HomeParkedPress = handleHomeParkedPress(
    lease = MainGameParkingState.current(),
    pressedGame = pressedGame,
    pressedTitle = pressedTitle,
    ports = homeResumePorts(context),
    confirm = MainGameHomePark.endConfirm,
    titleOf = ::parkedGameDisplayTitle,
    emitMessage = emitMessage,
    restart = restart,
)

/**
 * The Library's refused Play, answered the way Home's is: its own game resumes, another game asks to be ended, and only an
 * ended one lets the Library retry its launch. [ParkedLeaseReleaseOutcome.NotParked] stands for "answered, show nothing more":
 * a resumed press has no message, and the Library already holds the refusal it published.
 */
internal suspend fun libraryParkedRefusalOutcome(lease: MainGameParkingLease, game: GameRow): ParkedLeaseReleaseOutcome {
    // The Library card carries Restart too (the one card): its press files the request and presses Play, and this is the Library's refused-Play answer, the
    // one place that press reaches. The request is taken here, at the entry, whether or not a Context is wired, so a request that stood for another game (or went stale)
    // is consumed and ignored, and cannot turn this press, or a later one, into a Restart.
    val restart = HomeLaunchCardResume.consumeRestart(game.gameId)
    val context = MainGameHomePark.appContext
        ?: return ParkedLeaseReleaseOutcome.StillParked(parkedLeaseRefusalMessage(lease))
    return libraryParkedPress(
        lease = MainGameParkingState.current(),
        game = game,
        ports = homeResumePorts(context),
        confirm = MainGameHomePark.endConfirm,
        titleOf = ::parkedGameDisplayTitle,
        otherwise = ::parkedLeaseRefusalMessage,
        restart = restart,
    )
}

/**
 * The Library's refused Play, answered by [handleHomeParkedPress]. [restart] is the card's Restart request, which [libraryParkedRefusalOutcome] took at its entry: the parked
 * game's own press then ends the session (and answers [ParkedLeaseReleaseOutcome.Released], so the launch retries and starts the game fresh) instead of resuming it.
 */
internal suspend fun libraryParkedPress(
    lease: MainGameParkingLease?,
    game: GameRow,
    ports: HomeResumePorts,
    confirm: EndParkedGameConfirm,
    titleOf: (MainGameParkingLease) -> String?,
    otherwise: (MainGameParkingLease) -> String,
    restart: Boolean = false,
): ParkedLeaseReleaseOutcome {
    var message: String? = null
    val press = handleHomeParkedPress(
        lease = lease,
        pressedGame = game.gameId,
        pressedTitle = game.title,
        ports = ports,
        confirm = confirm,
        titleOf = titleOf,
        emitMessage = { _, text -> message = text },
        restart = restart,
    )
    return when (press) {
        HomeParkedPress.NOT_PARKED ->
            lease?.let { ParkedLeaseReleaseOutcome.StillParked(otherwise(it)) } ?: ParkedLeaseReleaseOutcome.NotParked
        HomeParkedPress.ENDED_PARKED_GAME -> ParkedLeaseReleaseOutcome.Released
        HomeParkedPress.ANSWERED -> message?.let { ParkedLeaseReleaseOutcome.StillParked(it) } ?: ParkedLeaseReleaseOutcome.NotParked
    }
}

/**
 * PLAYER-FACING COPY, proposed and pending design review. Names both games and the affirmative action. "Quit"
 * is the word the Bottom's NOW PLAYING already uses; none of the copy below uses O15's busy words.
 */
internal fun homeParkedEndAndStartQuestion(runningTitle: String?, newTitle: String): String {
    val running = runningTitle ?: "the running game"
    return "End $running and start $newTitle? Press Play again to confirm."
}

/** PLAYER-FACING COPY, proposed. The confirmed end did not finish, so nothing was started. O4: "Couldn’t". */
internal fun homeParkedEndFailedMessage(runningTitle: String?): String {
    val running = runningTitle ?: "the running game"
    return "Couldn’t end $running. Quit it first."
}

/** PLAYER-FACING COPY, proposed. A surface that cannot resume tells which game is running. */
internal fun homeParkedStillRunningMessage(title: String?): String =
    if (title != null) {
        "$title is still running. Resume it from Home, or quit it first."
    } else {
        "A game is still running. Resume it from Home, or quit it first."
    }

/** PLAYER-FACING COPY, proposed. The Resume press could not bring the task to the front. O4: "Couldn’t". */
internal fun homeParkedResumeFailedMessage(title: String?): String =
    if (title != null) {
        "Couldn’t bring $title back. Try again, or quit it first."
    } else {
        "Couldn’t bring the game back. Try again, or quit it first."
    }

/** The sentence for a refusal that a parked lease holds, from a surface that cannot resume. */
internal fun parkedLeaseRefusalMessage(lease: MainGameParkingLease): String {
    val title = parkedGameDisplayTitle(lease)
    return if (lease.homeParked) homeParkedStillRunningMessage(title) else parkedGameStillPausedMessage(title)
}

internal fun homeResumePorts(context: Context): HomeResumePorts = HomeResumePorts(
    beginRestoring = MainGameParkingState::beginRestoring,
    cancelRestoring = MainGameParkingState::cancelRestoring,
    focusTask = { taskId ->
        withContext(Dispatchers.IO) {
            runCatching { CompanionProcessRuntime.services(context).privilegedUtility.focusTask(taskId) }
                .getOrElse { PrivilegedTaskFocus.AUTHORITY_UNAVAILABLE }
        }
    },
    afterFronted = { lease -> MainGameHomePark.beginSegment(context, lease) },
    yieldBottom = ::yieldBottomForHomeResume,
    restoreBottom = ::restoreBottomAfterFailedHomeResume,
    bottomReady = ::bottomReadyForHomeResume,
    reopenBottom = ::reopenBottomForHomeResume,
    // The music lets go of audio focus before the game comes up over Mimir (YourMusicEverywhere's hand-back evidence), not at Main's later stop.
    handBackBegin = { MusicGameHandBack.beginAndWake() },
    handBackEnd = { MusicGameHandBack.endAndWake() },
    releaseLease = { lease -> releaseHomeParkedLease(lease, "parked task unknown") },
    endGame = { lease ->
        val game = MimirSessionAuthority.state.value.gameSession?.takeIf { it.operation == lease.operation }
        endHomeParkedGame(
            lease = lease,
            quit = { game?.let { nowPlayingQuitPerform(it, nowPlayingQuitPorts(context)) } },
            release = { held -> releaseHomeParkedLease(held, "parked task unknown") },
        )
    },
)

/** The process's one parker. `MainActivity` reads [parkedByHome]; `ActivityMainHomeKeyPlatform` calls [onHomeKey]. */
internal object MainGameHomePark {
    @Volatile
    private var parker: MainGameHomeParker? = null

    /** The parker for HOME on the Bottom. Its own in-flight mark, so a Main park and a Bottom park are never taken for each other. */
    @Volatile
    private var bottomParker: MainGameHomeParker? = null

    /** The Bottom display the floor found its covered game on, read by the Bottom park's task lookup and lease. */
    @Volatile
    private var bottomDisplay: Int? = null
    private var watchJob: Job? = null

    /** The application context, for the surfaces that have none of their own (the Library's refused press). */
    @Volatile
    var appContext: Context? = null
        private set

    /** The question behind "End X and start Y?" survives the card closing between the two presses. */
    val endConfirm = EndParkedGameConfirm(now = SystemClock::elapsedRealtime)

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /** True between a HOME press over a game and the moment its lease is armed (or the park has failed). */
    fun pending(): Boolean = parker?.pending() == true

    /** The game in front of Home is parked there by a HOME press: pending, or holding a HOME lease. */
    fun parkedByHome(): Boolean = pending() || MainGameParkingState.currentForCurrentSession()?.let { it.homeParked && !it.bottom } == true

    fun onHomeKey(context: Context, decision: MainHomeKeyDecision, source: MainHomeKeySource) {
        install(context)
        val current = parker ?: MainGameHomeParker(productionPorts(context.applicationContext)).also { parker = it }
        current.onHomeKey(decision, source)
    }

    /**
     * Resume fronted the task: a new measured segment starts under the same token as the session's first (parked time was not counted).
     * the token is the session's own when no Home launch armed a handle, so a Library launch's later segments are measured too.
     */
    suspend fun beginSegment(context: Context, lease: MainGameParkingLease) {
        val resumedAt = SystemClock.elapsedRealtime()
        homeParkSegments.resumed(lease.operation, resumedAt)
        val game = MimirSessionAuthority.state.value.gameSession
            ?.takeIf { it.operation == lease.operation }
            ?: return
        HomeExternalSessionElapsedStore.begin(
            context = context,
            itemId = game.elapsedItemId(),
            returnToken = game.elapsedToken(),
            dispatchedAtElapsedMillis = resumedAt,
            placement = game.placement,
        )
    }

    /**
     * the earlier update "park both": HOME on the Bottom (`KEYCODE_HOME -d 4`, or the pad's HOME after a Bottom touch) made the secondary-home floor resume over [disposition].
     * Called by the floor BEFORE it reclaims. True when [disposition] is a host-covered Bottom game that this press is now parking, and the floor then
     * reclaims under [BottomHomeParkReclaimReason], which the session bridge reads as "keep the session"; false for everything else, and the floor's
     * ordinary reclaim ends the game's session as it always has. Edge-back is a different path and never comes through here. A process with no
     * application context yet has nothing to park with and answers false.
     */
    fun onBottomFloorHome(disposition: SecondaryDisplayDisposition): Boolean {
        val context = appContext ?: return false
        val target = bottomHomeTarget(disposition, MimirSessionAuthority.state.value)
        bottomDisplay = target?.first?.displayId
        val current = bottomParker ?: MainGameHomeParker(productionPorts(context, bottom = true)).also { bottomParker = it }
        return current.onBottomHome(target?.second, covered = target != null) == HomeParkVerdict.PARK
    }

    /**
     * The session bridge saw the Bottom game [operation] come to the front of display 4 again (a tap on its tab, or the card's Resume, which is the same
     * reopen): the HOME park is over, so its lease clears (the card says Play again) and the next measured segment starts.
     */
    fun bottomGameFronted(operation: MimirGameSessionOperation) {
        val lease = MainGameParkingState.current()?.takeIf { it.bottom && it.operation == operation } ?: return
        if (!MainGameParkingState.clear(lease)) return
        val context = appContext ?: return
        CompanionProcessRuntime.processScope.launch { runCatching { beginSegment(context, lease) } }
    }

    private fun parkElapsedToken(game: MimirGameSession): String? =
        if (game.placement == MimirGamePlacement.BOTTOM) game.elapsedToken() else primaryGameReturnElapsedToken(game, game.operation)

    private fun productionPorts(context: Context, bottom: Boolean = false): HomeParkPorts {
        fun gateway() = CompanionProcessRuntime.services(context).privilegedUtility
        return HomeParkPorts(
            currentGame = { MimirSessionAuthority.state.value.gameSession },
            isParked = { game -> MainGameParkingState.currentFor(game) != null },
            privilegeReady = {
                bottom || runCatching { gateway().posture().status == PrivilegedUtilityStatus.READY }.getOrDefault(false)
            },
            withholdReturn = { if (!bottom) PrimaryActivityObservationState.noteMButtonCommandDelivered() },
            resolveTaskOutcome = { packageName ->
                val display = if (bottom) bottomDisplay else Display.DEFAULT_DISPLAY
                if (display == null) {
                    VendorTaskOutcome.Unavailable("no_display")
                } else {
                    answerWithin(HomeParkResolveTimeoutMs) {
                        runCatching { gateway().resolveTaskOutcome(packageName, display) }
                            .getOrElse { VendorTaskOutcome.Unavailable("unavailable") }
                    } ?: VendorTaskOutcome.Unavailable("timeout")
                }
            },
            arm = { game, taskId ->
                when (MainGameParkingState.armForHomeCurrentSession(game.operation, taskId, if (bottom) bottomDisplay ?: 0 else 0)) {
                    MainGameParkingArmResult.ARMED,
                    MainGameParkingArmResult.ALREADY_ARMED,
                    -> MainGameParkingState.current()?.takeIf { it.operation == game.operation }
                    else -> null
                }
            },
            endSession = { game ->
                game.targetPackageName?.let { packageName ->
                    MimirCompanionRuntimeSessionBridge.endBottomGameForTabClose(
                        packageName,
                        endedBy = "home press, game not parkable",
                        expectedOperation = game.operation,
                    )
                }
            },
            startWatch = { lease -> startWatch(context, lease) },
            reclaimBottom = { game -> reclaimBottomForHomePark(game) },
            finalizeElapsed = { game, atElapsedMs ->
                parkElapsedToken(game)?.let { token ->
                    HomeExternalSessionElapsedStore.finalize(context, token, atElapsedMs)
                }
            },
            elapsedNow = SystemClock::elapsedRealtime,
            totalBefore = { game ->
                parkElapsedToken(game)?.let { token ->
                    HomeExternalSessionElapsedStore.activeMeasurement(context)
                        ?.takeIf { it.returnToken == token }
                        ?.let { HomeExternalSessionElapsedStore.observe(context).value[it.itemId] ?: 0L }
                }
            },
            segments = homeParkSegments,
            schedule = { block -> CompanionProcessRuntime.processScope.launch { block() } },
            log = { line -> runCatching { Log.i("MimirHomePark", line) } },
            mimirResumed = { !PrimaryActivityObservationState.stopped.value },
            appLaunchedSince = { game -> appLaunchedAfterKeptGame(MimirSessionAuthority.state.value, game) },
            scheduleRetry = { block -> CompanionProcessRuntime.processScope.launch { block() } },
            gatewayStatus = { runCatching { gateway().posture().status }.getOrDefault(PrivilegedUtilityStatus.CONNECTION_FAILED) },
        )
    }

    private fun startWatch(context: Context, lease: MainGameParkingLease) {
        watchJob?.cancel()
        val watch = ParkedGameExitWatch(
            currentLease = MainGameParkingState::current,
            mimirResumed = { !PrimaryActivityObservationState.stopped.value },
            releaseIfGone = { current -> releaseHomeParkedOnProofIfGone(context, current, stage = "home_watch") },
        )
        watchJob = CompanionProcessRuntime.processScope.launch(Dispatchers.IO) { watch.run(lease) }
    }
}

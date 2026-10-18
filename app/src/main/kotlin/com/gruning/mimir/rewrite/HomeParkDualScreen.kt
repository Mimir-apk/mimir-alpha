package com.gruning.mimir.rewrite

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * the earlier update, policy "Yes, extend it": a HOME press over a dual-screen game (melonDS, Azahar) parks it like any other game, and this
 * file is the Bottom's half of it. Main's half is the same lease, the same watch and the same Resume as a single-screen game
 * ([MainGameHomePark]); what a second screen adds is who owns display 4 while the game sits behind Mimir.
 *
 * **While parked, Mimir's companion has the Bottom.** The game keeps running and its own Bottom window is still there under Mimir's. The
 * park takes the Bottom back the way the right-edge swipe does over a live game ([reclaimBottomOverLiveGame], reason `home_park`, a fresh
 * Presentation above the emulator's window), so the Bottom shows NOW PLAYING with Quit and "Return to game" instead of the game's frozen
 * lower screen.
 *
 * **Resume fronts both displays.** The order is the one the Bottom's "Return to game" has used since 2026-09-13
 * (device-proven on melonDS): Mimir's mirror yields to the emulator's window
 * ([yieldBottomIfMirrored], which exposes it on display 4), then the vendor gateway fronts the Main task. The emulator's own window comes
 * back with its activity. If the focus is refused, the Bottom is taken back ([restoreBottomIfYieldedToGame]) and the lease stays for another try.
 *
 * The three functions below take the runtime and the origin, so a test drives them against a real [CompanionRuntimeCoordinator] with a fake
 * Bottom; the suspend wrappers only hop to the main thread, where the runtime's effects are executed.
 */
internal const val HomeParkReclaimReason: String = "home_park"

/**
 * The reason of the reclaim the secondary-home floor issues when HOME on the Bottom found a Bottom game it is parking. The floor's
 * ordinary reason ends the game's session (the player left it); this one tells the session bridge that the session stays, because the park keeps it.
 */
internal const val BottomHomeParkReclaimReason: String = "home_park_bottom"

/** The Bottom comes back to Mimir over the parked game's window. False when the Bottom is not in a state a reclaim may start from. */
internal fun reclaimBottomOverLiveGame(
    runtime: CompanionRuntimeCoordinator,
    packageName: String,
    origin: PrimaryReturnOrigin,
): Boolean {
    val disposition = runtime.snapshot.value.disposition
    if (!companionMainGameExitReclaimAllowed(disposition, packageName)) return false
    return runtime.explicitReclaim(
        reason = HomeParkReclaimReason,
        primaryOrigin = origin,
        returningFromPackage = packageName,
        refreshPresentedMirror = disposition is SecondaryDisplayDisposition.MirrorPresented,
    ) != null
}

/**
 * Mimir's mirror hands display 4 to the emulator's window. False (and nothing changed) when no mirror is shown: there is then nothing on
 * the Bottom to hand over, and Resume is the plain Main focus.
 */
internal fun yieldBottomIfMirrored(
    runtime: CompanionRuntimeCoordinator,
    packageName: String,
    origin: PrimaryReturnOrigin,
): Boolean {
    val snapshot = runtime.snapshot.value
    val mirror = snapshot.disposition as? SecondaryDisplayDisposition.MirrorPresented ?: return false
    if (!snapshot.mirrorActive) return false
    return runtime.yieldForExistingSecondaryGame(
        parentOperationId = mirror.attempt.parentDispositionId,
        packageName = packageName,
        primaryOrigin = origin,
    )
}

/** The focus failed after [yieldBottomIfMirrored] handed the Bottom over: Mimir takes it back, but only from the hand-over it made. */
internal fun restoreBottomIfYieldedToGame(
    runtime: CompanionRuntimeCoordinator,
    packageName: String,
    origin: PrimaryReturnOrigin,
): Boolean {
    val yielded = runtime.snapshot.value.disposition as? SecondaryDisplayDisposition.Yielded ?: return false
    if (yielded.evidence != YieldEvidence.USER_REQUESTED_GAME_RETURN ||
        yielded.lease?.packageName?.equals(packageName, ignoreCase = true) != true
    ) {
        return false
    }
    return runtime.explicitReclaim(
        reason = "main_game_focus_failed",
        primaryOrigin = origin,
        returningFromPackage = packageName,
    ) != null
}

/** The longest a Resume waits for the park's own Bottom reclaim to land before it fronts anything. */
internal const val HomeResumeReclaimWaitMs: Long = 2_000L

/** True while the park's reclaim of the Bottom (about 0.5 to 1.5 s) is still in flight. */
internal fun homeParkReclaimPending(snapshot: CompanionRuntimeSnapshot): Boolean =
    (snapshot.disposition as? SecondaryDisplayDisposition.ReclaimPending)?.lease?.reason.let { it == HomeParkReclaimReason || it == BottomHomeParkReclaimReason }

/**
 * Whether the Bottom is settled enough for a Resume to hand it over (review P2). A Resume that lands inside the park's reclaim finds no mirror to
 * yield, so it would front the game and the reclaim would then land Mimir's Presentation over the live lower screen. So it waits, up to [timeoutMs],
 * for the reclaim to settle (the mirror presented, or the reclaim failed); false when it is still in flight at the deadline, and the Resume
 * then refuses with its usual "try again" sentence instead of fronting under it.
 */
internal suspend fun awaitHomeParkReclaimSettled(runtime: CompanionRuntimeCoordinator, timeoutMs: Long = HomeResumeReclaimWaitMs): Boolean {
    if (!homeParkReclaimPending(runtime.snapshot.value)) return true
    return withTimeoutOrNull(timeoutMs) { runtime.snapshot.first { !homeParkReclaimPending(it) } } != null
}

/** The park's reclaim, for a dual-screen game that is still the session's. Main thread. */
internal fun reclaimBottomForHomePark(game: MimirGameSession): Boolean {
    val packageName = game.targetPackageName?.trim()?.takeIf(String::isNotEmpty) ?: return false
    if (!game.dualScreen || game.placement != MimirGamePlacement.MAIN) return false
    val reclaimed = reclaimBottomOverLiveGame(
        CompanionReturnCoordinator.runtime(),
        packageName,
        CompanionReturnCoordinator.currentPrimaryOrigin(),
    )
    runCatching { Log.i("MimirHomePark", "home park bottom reclaim op=${game.operation.value} reclaimed=$reclaimed") }
    return reclaimed
}

/** Resume's first step for a dual-screen lease. The runtime's effects run on the main thread. */
internal suspend fun yieldBottomForHomeResume(lease: MainGameParkingLease): Boolean = withContext(Dispatchers.Main.immediate) {
    val yielded = yieldBottomIfMirrored(
        CompanionReturnCoordinator.runtime(),
        lease.targetPackageName,
        CompanionReturnCoordinator.currentPrimaryOrigin(),
    )
    runCatching { Log.i("MimirHomePark", "home resume bottom yielded op=${lease.operation.value} yielded=$yielded") }
    yielded
}

/** Resume's undo when the focus was refused. */
internal suspend fun restoreBottomAfterFailedHomeResume(lease: MainGameParkingLease) {
    withContext(Dispatchers.Main.immediate) {
        val restored = restoreBottomIfYieldedToGame(
            CompanionReturnCoordinator.runtime(),
            lease.targetPackageName,
            CompanionReturnCoordinator.currentPrimaryOrigin(),
        )
        runCatching { Log.i("MimirHomePark", "home resume bottom restored op=${lease.operation.value} restored=$restored") }
    }
}

/** Resume's first gate for a dual-screen or Bottom lease: the park's own reclaim of the Bottom must have settled (review P2). */
internal suspend fun bottomReadyForHomeResume(lease: MainGameParkingLease): Boolean {
    val ready = awaitHomeParkReclaimSettled(CompanionReturnCoordinator.runtime())
    if (!ready) runCatching { Log.i("MimirHomePark", "home resume waited out the bottom reclaim op=${lease.operation.value}") }
    return ready
}

/**
 * Resume of a game that HOME parked on the Bottom is the game tab's own reopen ([CompanionRecentsState.onLaunch], the call a tap on
 * the tab makes): relaunching the emulator package brings its running task back to display 4, and the session bridge adopts the live Bottom game
 * (`liveBottomGameFor`) instead of minting a second one. FOCUSED means the launch was accepted; the lease is cleared and the next play segment
 * starts when the bridge sees the game in front ([MainGameHomePark.bottomGameFronted]), not here. A refused launch (the Bottom is busy, or
 * not Mimir's) is FAILED, and Resume says so.
 */
internal suspend fun reopenBottomForHomeResume(lease: MainGameParkingLease): PrivilegedTaskFocus = withContext(Dispatchers.Main.immediate) {
    val accepted = CompanionRecentsState.onLaunch(lease.targetPackageName) != null
    runCatching { Log.i("MimirHomePark", "home resume bottom reopen op=${lease.operation.value} accepted=$accepted") }
    if (accepted) PrivilegedTaskFocus.FOCUSED else PrivilegedTaskFocus.FAILED
}

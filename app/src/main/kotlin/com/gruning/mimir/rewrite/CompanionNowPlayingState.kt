package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirTimeCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/*
 * The Now Playing takeover's rules, decided without a composition (Thor Bottom redesign, slice L3): when the Bottom glides to it, which game
 * session it belongs to, what Quit says and when, and the words of the clock and the total. Pure, so each rule is a test rather than a claim.
 *
 * Wording: the live clock counts from DISPATCH, so it includes launch time ("1:24:07"). The total comes from the elapsed store, whose rule forbids
 * "playtime": it is dispatch-to-matching-return time, and the store holds it WITHOUT the running session (the active measurement is added at return),
 * so "before this session" is literally true. No function here says "playtime".
 */

// ── Entry ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The ruling's phase: the Bottom glides to the takeover when the game is PROVEN in front.
 *
 * Only a Bottom host reaches this phase (HOST_COVERED evidence). A game on MAIN, the one case where Mimir owns display 4 beside a running game, never
 * does: `MainActivity.onStop` moves it from DISPATCHED to YIELDED_TO_SECONDARY_TASK, which proves that Mimir ceded its window and nothing about the game
 * being in front. So for Main the proof is the product's own foreground evidence for the game's package ([nowPlayingForegroundEvidence]).
 */
internal fun nowPlayingEntryPhase(phase: MimirSessionPhase): Boolean = phase == MimirSessionPhase.FOREGROUND_PROVEN

/**
 * Whether the game is out there: dispatched to its emulator and held by the session, with Mimir having ceded its window (YIELDED_TO_SECONDARY_TASK) or
 * the game proven in front. This is what Quit needs (there is something to close); it is NOT proof the game is in front, so it never triggers the glide.
 */
internal fun nowPlayingGameOutPhase(phase: MimirSessionPhase): Boolean =
    phase == MimirSessionPhase.FOREGROUND_PROVEN || phase == MimirSessionPhase.YIELDED_TO_SECONDARY_TASK

/**
 * Positive evidence that [game]'s emulator came to the foreground in THIS launch: the foreground monitor's UsageStats observation
 * (`foreignForegroundObserved`, the same signal that re-derives NOW_PLAYING) names the session's target package, at or after its dispatch. An
 * observation from before the dispatch (the monitor replays its last one) proves nothing about this launch; a session with no dispatch time or no
 * package has nothing to be matched to; and with no Usage Access the monitor emits nothing, so nothing glides.
 */
internal fun nowPlayingForegroundEvidence(game: MimirGameSession?, observedPackage: String, observedAtEpochMs: Long): Boolean {
    val live = companionActivityGame(game) ?: return false
    val target = live.targetPackageName?.takeIf { it.isNotBlank() } ?: return false
    val dispatchedAt = live.dispatchedAtEpochMs ?: return false
    return target.equals(observedPackage.trim(), ignoreCase = true) && observedAtEpochMs >= dispatchedAt
}

/** The session's stable key: one per launch, the same for every phase of it. */
internal fun nowPlayingSessionKey(game: MimirGameSession): String = "${game.operation.value}:${game.sequence}"

/**
 * What the takeover remembers across a Presentation remount (it lives in process state beside the workspace page).
 *
 * [autoEnteredKey] is the session the Bottom has already glided to: it is never glided to again, so a later swipe to the Shelf sticks until the
 * NEXT launch (a new session key). [openKey] is the session whose takeover is showing now: the entry sets it, the "Activity" pill and a swipe to
 * the Shelf clear it, and the Activity page's Now playing tile sets it again. [provenKey] is the session whose game has been seen in front.
 */
internal data class NowPlayingTakeoverState(
    val autoEnteredKey: String? = null,
    val openKey: String? = null,
    val provenKey: String? = null,
)

internal data class NowPlayingEntry(val state: NowPlayingTakeoverState, val glide: Boolean)

/**
 * The state after the session authority reports [game] (null: no game session), and whether the Bottom glides to the takeover now.
 *
 * A glide happens once per session: the first time a live game ([companionActivityGame]: a RUNNING_APP is never a game session, so it gets none) is
 * PROVEN in front, by its phase ([nowPlayingEntryPhase]) or by foreground evidence already recorded for it ([NowPlayingTakeoverState.provenKey]).
 * Being dispatched, or yielded by Main, proves nothing. A session that is gone clears the open takeover and keeps the consumed key, so a late report
 * of the same key cannot re-enter; a different key is a new launch and arms again.
 */
internal fun nowPlayingOnSession(state: NowPlayingTakeoverState, game: MimirGameSession?): NowPlayingEntry {
    val live = companionActivityGame(game)
    if (live == null) {
        return NowPlayingEntry(state.copy(openKey = null), glide = false)
    }
    val key = nowPlayingSessionKey(live)
    val stale = state.openKey != null && state.openKey != key
    val base = if (stale) state.copy(openKey = null) else state
    val proven = nowPlayingEntryPhase(live.phase) || base.provenKey == key
    return if (proven && base.autoEnteredKey != key) {
        NowPlayingEntry(base.copy(autoEnteredKey = key, openKey = key), glide = true)
    } else {
        NowPlayingEntry(base, glide = false)
    }
}

/** The foreground monitor saw [observedPackage] come to the front at [observedAtEpochMs]: if that is this session's game, it is proven, and the glide may happen. */
internal fun nowPlayingOnForeground(
    state: NowPlayingTakeoverState,
    game: MimirGameSession?,
    observedPackage: String,
    observedAtEpochMs: Long,
): NowPlayingEntry {
    if (!nowPlayingForegroundEvidence(game, observedPackage, observedAtEpochMs)) return NowPlayingEntry(state, glide = false)
    val live = requireNotNull(companionActivityGame(game))
    return nowPlayingOnSession(state.copy(provenKey = nowPlayingSessionKey(live)), live)
}

/** The takeover opened by hand (the Now playing tile), for the live session [key] only. */
internal fun nowPlayingOpen(state: NowPlayingTakeoverState, key: String?): NowPlayingTakeoverState =
    if (key == null) state else state.copy(openKey = key)

/** The takeover closed (the "Activity" pill, a swipe to the Shelf, a hub opened from it). The consumed key stays: closing never re-arms the glide. */
internal fun nowPlayingClose(state: NowPlayingTakeoverState): NowPlayingTakeoverState = state.copy(openKey = null)

/** Process state, so a Presentation remount does not forget that the glide has happened (same pattern as [CompanionWorkspaceState]). */
internal object CompanionNowPlayingState {
    private val lock = Any()
    private val mutableState = MutableStateFlow(NowPlayingTakeoverState())
    val state: StateFlow<NowPlayingTakeoverState> = mutableState.asStateFlow()

    /** Reports the session; true when the Bottom should glide to the takeover now. */
    fun onSession(game: MimirGameSession?): Boolean = synchronized(lock) {
        val entry = nowPlayingOnSession(mutableState.value, game)
        mutableState.value = entry.state
        entry.glide
    }

    /** Reports a foreground observation of a package against [game]; true when the Bottom should glide to the takeover now. */
    fun onForeground(game: MimirGameSession?, observedPackage: String, observedAtEpochMs: Long): Boolean = synchronized(lock) {
        val entry = nowPlayingOnForeground(mutableState.value, game, observedPackage, observedAtEpochMs)
        mutableState.value = entry.state
        entry.glide
    }

    fun open(key: String?) = synchronized(lock) { mutableState.value = nowPlayingOpen(mutableState.value, key) }

    fun close() = synchronized(lock) { mutableState.value = nowPlayingClose(mutableState.value) }

    /** Tests only: forgets every consumed key. */
    internal fun resetForTest() = synchronized(lock) { mutableState.value = NowPlayingTakeoverState() }
}

// ── Quit ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

/** Quit's states: "Quit", then "Tap again" for a short window, then the close, then what the readback proved. */
internal enum class NowPlayingQuitPhase { IDLE, CONFIRM, CLOSING, CLOSED, FAILED }

/**
 * How long "Tap again" waits before it goes back to "Quit": the house's 3 s transient hold for a chip's words
 * ([MusicTitleChipHoldMs]), not a new clock. The same window holds "Couldn't close it" before the button offers Quit again.
 */
internal const val NowPlayingQuitConfirmMs: Long = MusicTitleChipHoldMs

internal data class NowPlayingQuitStep(val phase: NowPlayingQuitPhase, val performClose: Boolean)

/**
 * A tap. The first asks to confirm; the second, inside the window, closes; a tap while it is closing or after it closed does nothing; a tap after
 * a failure starts the confirm again.
 */
internal fun nowPlayingQuitOnTap(phase: NowPlayingQuitPhase): NowPlayingQuitStep = when (phase) {
    NowPlayingQuitPhase.IDLE, NowPlayingQuitPhase.FAILED -> NowPlayingQuitStep(NowPlayingQuitPhase.CONFIRM, performClose = false)
    NowPlayingQuitPhase.CONFIRM -> NowPlayingQuitStep(NowPlayingQuitPhase.CLOSING, performClose = true)
    NowPlayingQuitPhase.CLOSING, NowPlayingQuitPhase.CLOSED -> NowPlayingQuitStep(phase, performClose = false)
}

/** The confirm window ran out ([NowPlayingQuitConfirmMs]), or a failure's words have been shown long enough: back to "Quit". Nothing else expires. */
internal fun nowPlayingQuitOnExpiry(phase: NowPlayingQuitPhase): NowPlayingQuitPhase = when (phase) {
    NowPlayingQuitPhase.CONFIRM, NowPlayingQuitPhase.FAILED -> NowPlayingQuitPhase.IDLE
    else -> phase
}

/**
 * What a close attempt proved. ONLY a verified stop (the privileged readback found no process left) is "Closed": the firmware can exit 0 and kill
 * nothing, so every other outcome (still alive, accepted but unverified, not allowed, no authority, failed) is "Couldn't close it".
 */
internal fun nowPlayingQuitPhaseFor(outcome: PrivilegedCloseOutcome): NowPlayingQuitPhase =
    if (outcome == PrivilegedCloseOutcome.STOPPED) NowPlayingQuitPhase.CLOSED else NowPlayingQuitPhase.FAILED

/** What the chip shows. A chip never wraps (update), so the confirm is two words; [nowPlayingQuitSpoken] is the whole sentence for a screen reader. */
internal fun nowPlayingQuitCopy(phase: NowPlayingQuitPhase): String = when (phase) {
    NowPlayingQuitPhase.IDLE -> "Quit"
    NowPlayingQuitPhase.CONFIRM -> "Tap again"
    NowPlayingQuitPhase.CLOSING -> "Closing…"
    NowPlayingQuitPhase.CLOSED -> "Closed"
    NowPlayingQuitPhase.FAILED -> "Couldn't close it"
}

/** What a screen reader says for Quit: the chip's words, except the confirm, which says what the second tap does. */
internal fun nowPlayingQuitSpoken(phase: NowPlayingQuitPhase): String =
    if (phase == NowPlayingQuitPhase.CONFIRM) "Tap again to quit" else nowPlayingQuitCopy(phase)

/**
 * Whether Quit is offered at all: the close authority is ready (read off the main thread, never in composition), the session names the package
 * to close (nothing is guessed), and the game is out there ([nowPlayingGameOutPhase]: not a launch in flight, not one already leaving). With any of
 * these missing the button is hidden, not disabled.
 */
internal fun nowPlayingQuitOffered(game: MimirGameSession, authorityReady: Boolean): Boolean =
    authorityReady && !game.targetPackageName.isNullOrBlank() && nowPlayingGameOutPhase(game.phase)

// ── Words ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The live clock, "1:24:07", counted from dispatch, and holding still while a HOME press has the game parked ([nowPlayingSessionElapsed]).
 * Null when the session recorded no dispatch time (nothing is invented).
 */
internal fun nowPlayingClockText(startedAtElapsedMs: Long?, nowElapsedMs: Long, segments: HomeParkSegments? = null): String? =
    nowPlayingSessionElapsed(startedAtElapsedMs, nowElapsedMs, segments)?.let(MimirTimeCopy::clock)

/**
 * How long until the clock's displayed second changes, so the tick lands on the boundary instead of drifting against it. Always 1..1000 ms.
 */
internal fun nowPlayingClockDelayMs(elapsedMs: Long): Long = 1_000L - (elapsedMs.coerceAtLeast(0L) % 1_000L)

/**
 * The total line: "18 h 40 min before this session". The store's total is the sum of earlier sessions' dispatch-to-return time and excludes the one
 * running, so the words are true. Null for no total or a zero one (Main says "NO MEASURED ELAPSED" there; a quiet line is better dropped than a
 * zero shown on a live screen), and never the word "playtime".
 */
internal fun nowPlayingTotalText(totalBeforeMs: Long?): String? =
    totalBeforeMs?.takeIf { it > 0L }?.let { MimirTimeCopy.total(it) + " before this session" }

/** "Genesis · opens with RetroArch": the platform, then the launcher the retained card names. Either may be absent; neither is invented. */
internal fun nowPlayingSubtitle(platformLabel: String?, launcherLabel: String?): String? {
    val platform = platformLabel?.trim()?.takeIf { it.isNotEmpty() }
    val launcher = launcherLabel?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        platform != null && launcher != null -> "$platform · opens with $launcher"
        platform != null -> platform
        launcher != null -> "Opens with $launcher"
        else -> null
    }
}

/** The title the takeover wears: the library row's, else the payload's, else the session's own. */
internal fun nowPlayingTitle(row: GameRow?, payloadTitle: String?, sessionTitle: String): String =
    row?.title?.takeIf { it.isNotBlank() } ?: payloadTitle?.takeIf { it.isNotBlank() } ?: sessionTitle.takeIf { it.isNotBlank() } ?: "Game"

// ── Quit's close, and what settles after it ──────────────────────────────────────────────────────────────────────────────────────────

/**
 * Everything the confirmed Quit touches, as ports, so the decision is a test rather than a claim. Production wires each to an EXISTING operation; none
 * is new, and no command comes from here.
 */
internal class NowPlayingQuitPorts(
    /** `closeLaunchTarget`, the verified close the Tabs ✕ uses. */
    val close: (packageName: String) -> PrivilegedCloseOutcome,
    /** Whether this exact session (operation and sequence) is still the live one: a replacement launch of the same emulator is not. */
    val sessionLive: (MimirGameSession) -> Boolean,
    /** Whether a DS/3DS parking lease holds this game behind Mimir. */
    val isParked: (MimirGameSession) -> Boolean,
    /** The task the package still holds on Main, as `resolveTaskOutcome` answers it. */
    val resolveTask: (packageName: String) -> VendorTaskOutcome,
    /** `removeTask`, the eviction the Tabs path runs for a survivor of a whitelisted force-stop. */
    val removeTask: (taskId: Int) -> Boolean,
    /**
     * The parked-game release (clears the lease and ends the session); true when it released. Reached only after the close was verified, so it
     * asks the gateway nothing more: the proven close is what counts, whatever a later probe could not say .
     */
    val releaseParked: suspend (MimirGameSession) -> Boolean,
    /** Ends this exact session (pinned to its operation, so a replacement is never ended); true when it is no longer live. */
    val endSession: suspend (MimirGameSession) -> Boolean,
)

/**
 * The confirmed Quit: close the game's package through the EXISTING verified close, and report [PrivilegedCloseOutcome.STOPPED] only when the session
 * has actually ended. Everything runs on the session captured when Quit began ([game]): the identity is checked again before the close and before every
 * settle step, and the two privileged calls (the close, the task removal) check it once more on the IO thread immediately before they run, so an old
 * Quit that completes after a new launch of the same emulator, even one that reuses the same task, can neither close, evict nor end the replacement.
 *
 *  - A game that is not parked is ended the way a Tabs ✕ ends it (`endBottomGameForTabClose`, pinned to this operation).
 *  - A parked DS/3DS game does what the Tabs ✕ does for a survivor of a whitelisted force-stop: find the task the package still holds on Main and
 *    remove it (`removeTask`), and only then release the lease, which ends the session. No task means nothing to remove, and neither does a task
 *    that a refused removal leaves unconfirmed (the close had already been verified). A task that is still there and cannot be removed
 *    leaves the lease and the session untouched and says "Couldn't close it", so the next launch is never blocked by a lie.
 *  - once the close is verified, a probe that cannot answer afterwards does not hold the game. It is released and Quit
 *    reads Closed, dual-screen included. The Lime3DS keep-when-unsure rule is for every path with no verified close, and stays there.
 *  - Anything the readback did not prove (not STOPPED) is returned as it came and settles nothing. The privileged call is blocking and cannot be
 *    interrupted, so the whole thing is not cancellable: a close that finished after the window was dismissed must still settle.
 */
internal suspend fun nowPlayingQuitPerform(game: MimirGameSession, ports: NowPlayingQuitPorts): PrivilegedCloseOutcome = withContext(NonCancellable) {
    val pkg = game.targetPackageName?.takeIf { it.isNotBlank() } ?: return@withContext PrivilegedCloseOutcome.FAILED
    // The session this Quit was started for must still be the live one, or closing the package would close another launch's emulator.
    if (!ports.sessionLive(game)) return@withContext PrivilegedCloseOutcome.FAILED
    val outcome = withContext(Dispatchers.IO) {
        // Checked again on the IO side, right before the call: a relaunch of the same emulator can take the session during the hop above.
        if (!ports.sessionLive(game)) PrivilegedCloseOutcome.FAILED
        else runCatching { ports.close(pkg) }.getOrDefault(PrivilegedCloseOutcome.FAILED)
    }
    if (outcome != PrivilegedCloseOutcome.STOPPED) return@withContext outcome
    val ended = runCatching { settleAfterVerifiedClose(game, pkg, ports) }.getOrDefault(false)
    if (ended) PrivilegedCloseOutcome.STOPPED else PrivilegedCloseOutcome.FAILED
}

private suspend fun settleAfterVerifiedClose(game: MimirGameSession, pkg: String, ports: NowPlayingQuitPorts): Boolean {
    // The session ended by itself (Main's own return settled it) or was replaced by a new launch: nothing of ours to end, and never the replacement.
    if (!ports.sessionLive(game)) return true
    if (!ports.isParked(game)) return ports.endSession(game)
    when (val task = withContext(Dispatchers.IO) { ports.resolveTask(pkg) }) {
        is VendorTaskOutcome.Found -> {
            // The resolve suspended, and so does the hop to IO below: a new launch of the same emulator can take the session in either window, and
            // can REUSE this very task, so the task id cannot tell the old launch from the new one. The session (operation and sequence) can, so it is
            // checked on the IO side, immediately before the removal, and a replaced session evicts nothing. The old session is gone, which is what
            // Quit wanted.
            when (withContext(Dispatchers.IO) { evictParkedTask(game, task.taskId, ports) }) {
                QuitEviction.REPLACED -> return true
                // the close was verified (the privileged readback found no process), so the eviction is the SECOND step of it, and a
                // refusal there can simply mean the system removed the task between the lookup and the removal: it finds nothing to close.
                // Only a task that is positively still there is "Couldn't close it", with the lease and the session left untouched; a
                // task that is gone, or that a second lookup cannot place, is a close that finished .
                QuitEviction.REFUSED -> if (taskStillThere(pkg, ports)) return false
                QuitEviction.REMOVED -> Unit
            }
        }
        // the close is already verified, so a lookup that cannot answer is not a reason to hold a game whose
        // process has been proven gone. It goes to the release below, for a dual-screen lease as for any other.
        VendorTaskOutcome.NoTask, is VendorTaskOutcome.Unavailable -> Unit
    }
    if (!ports.sessionLive(game)) return true
    return ports.releaseParked(game) && !ports.sessionLive(game)
}

/** The task lookup asked again after a refused eviction: true only when it still names a task ([VendorTaskOutcome.Found]). */
private suspend fun taskStillThere(pkg: String, ports: NowPlayingQuitPorts): Boolean =
    withContext(Dispatchers.IO) { runCatching { ports.resolveTask(pkg) }.getOrNull() } is VendorTaskOutcome.Found

private enum class QuitEviction { REPLACED, REFUSED, REMOVED }

/** The removal itself, and the last identity check before it: run on the IO thread so no suspension separates the two. */
private fun evictParkedTask(game: MimirGameSession, taskId: Int, ports: NowPlayingQuitPorts): QuitEviction = when {
    !ports.sessionLive(game) -> QuitEviction.REPLACED
    ports.removeTask(taskId) -> QuitEviction.REMOVED
    else -> QuitEviction.REFUSED
}

/** [NowPlayingQuitPorts] over the process's existing operations. */
internal fun nowPlayingQuitPorts(context: Context): NowPlayingQuitPorts {
    fun gateway() = CompanionProcessRuntime.services(context).privilegedUtility
    fun live(game: MimirGameSession): Boolean = MimirSessionAuthority.state.value.gameSession?.let {
        it.operation == game.operation && it.sequence == game.sequence && it.phase in NowPlayingLivePhases
    } == true
    return NowPlayingQuitPorts(
        close = { pkg -> gateway().closeLaunchTarget(pkg) },
        sessionLive = ::live,
        isParked = { game -> MainGameParkingState.currentFor(game) != null },
        resolveTask = { pkg ->
            // The display the lease holds the game on: a game that HOME parked on the Bottom is not on Main's display .
            val display = MainGameParkingState.current()?.takeIf { it.targetPackageName == pkg }?.displayId ?: android.view.Display.DEFAULT_DISPLAY
            gateway().resolveTaskOutcome(pkg, display)
        },
        removeTask = { taskId -> gateway().removeTask(taskId) == PrivilegedTaskRemoval.REMOVED },
        releaseParked = { game ->
            val lease = MainGameParkingState.currentFor(game)
            if (lease == null) {
                false
            } else {
                // Straight to the lease's own release, not the probing one (`releaseParkedLease`): the close was verified before this is
                // reached, and a second probe that cannot answer would otherwise keep a dual-screen game the Quit just proved gone .
                releaseHomeParkedLease(lease, endedBy = "quit")
            }
        },
        endSession = { game ->
            val pkg = game.targetPackageName
            if (pkg != null) {
                withContext(Dispatchers.Main.immediate) {
                    MimirCompanionRuntimeSessionBridge.endBottomGameForTabClose(pkg, endedBy = "quit", expectedOperation = game.operation)
                }
            }
            !live(game)
        },
    )
}

/** The phases in which a session still owns its game: what "live" means for the identity checks above. */
private val NowPlayingLivePhases = setOf(
    MimirSessionPhase.DISPATCHED,
    MimirSessionPhase.FOREGROUND_PROVEN,
    MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
    MimirSessionPhase.RETURNING,
)


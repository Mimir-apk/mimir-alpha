package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.feature.library.GameLaunchSessionHold
import com.gruning.mimir.rewrite.feature.library.GameLaunchUiState
import com.gruning.mimir.rewrite.feature.library.LibraryGameLaunchOperation

/**
 * O15,
 *
 * The one place a session's phase becomes that split. A session holds the launch mutex in exactly the phases this answers
 * a hold for ([ActivePresentationPhases] is the same set, and `MimirSessionLaunchHoldTest` keeps the two equal).
 *
 * - STARTING while Mimir's own launch is still settling the session, so waiting clears it: PREPARING (admitted, the start
 *   not accepted yet); DISPATCHED when something in Mimir will still move it on ([dispatchSettlesItself]); and RECOVERING,
 *   the dispatch watchdog's cleanup of a launch that never came up, which ends by itself and leaves nothing to close.
 * - RUNNING once the game or app has the screen and only closing it ends the session: FOREGROUND_PROVEN,
 *   YIELDED_TO_SECONDARY_TASK, RETURNING (still open until the return completes), and DISPATCHED when nothing in Mimir
 *   will move it on.
 * - No hold for ENDED, FAILED and STALE, which hold no launch.
 *
 * Exhaustive on purpose: a new phase does not compile until it is placed.
 */
internal fun launchHoldFor(phase: MimirSessionPhase, dispatchSettlesItself: Boolean): GameLaunchSessionHold? =
    when (phase) {
        MimirSessionPhase.PREPARING,
        MimirSessionPhase.RECOVERING,
        -> GameLaunchSessionHold.STARTING
        MimirSessionPhase.DISPATCHED ->
            if (dispatchSettlesItself) GameLaunchSessionHold.STARTING else GameLaunchSessionHold.RUNNING
        MimirSessionPhase.FOREGROUND_PROVEN,
        MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
        MimirSessionPhase.RETURNING,
        -> GameLaunchSessionHold.RUNNING
        MimirSessionPhase.ENDED,
        MimirSessionPhase.FAILED,
        MimirSessionPhase.STALE,
        -> null
    }

/**
 * A game's DISPATCHED settles itself: `MainActivity.onStop` yields a Main game at the hand-off edge, the Bottom host yields a
 * Bottom one, and the dispatch watchdog takes one that never came up to RECOVERING.
 */
internal fun MimirGameSession.launchHold(): GameLaunchSessionHold? = launchHoldFor(phase, dispatchSettlesItself = true)

/**
 * An app's DISPATCHED settles itself on the Bottom, where the runtime host yields it, and for a display-0 transfer, which the
 * transfer host proves or fails at its deadline ([primaryAppSessionTransferId]). An app Mimir started on Main itself
 * (`preparePrimaryApp`, the `primary-native:` ids of Quick launch, the native shelf and the Home row) stays DISPATCHED for
 * as long as it runs: nothing moves it on until the player comes back, as
 * [MimirSessionCoordinator.endPrimaryAppForSecondaryMove] also records.
 */
internal fun MimirAppSession.launchHold(): GameLaunchSessionHold? =
    launchHoldFor(
        phase,
        dispatchSettlesItself = placement == MimirAppPlacement.SECONDARY ||
            primaryAppSessionTransferId(operationId) != null,
    )

/**
 * What held a launch the mutex just refused, read from the sessions that refused it in this snapshot: the game session, and
 * the Main app session when [includePrimaryApp]. A running one wins, because waiting alone never clears the refusal. A
 * refusal with nothing classified, which the admission checks cannot produce, answers STARTING, today's sentence.
 */
internal fun MimirSessionSnapshot.refusedLaunchHold(includePrimaryApp: Boolean): GameLaunchSessionHold {
    val holds = listOfNotNull(
        gameSession?.launchHold(),
        primaryAppSession?.takeIf { includePrimaryApp }?.launchHold(),
    )
    return if (GameLaunchSessionHold.RUNNING in holds) GameLaunchSessionHold.RUNNING else GameLaunchSessionHold.STARTING
}

/**
 * The hold of the Library launch the ledger last refused, kept for [MimirSessionAuthority.holdAfterRefusal]. The Library asks
 * right after its Preparing event was refused, on the same call path, and gets the hold of that exact operation; a
 * question about any other operation answers STARTING.
 */
internal class LibraryLaunchRefusals {
    @Volatile private var last: Pair<LibraryGameLaunchOperation, GameLaunchSessionHold>? = null

    fun record(operation: LibraryGameLaunchOperation, hold: GameLaunchSessionHold) {
        last = operation to hold
    }

    fun holdFor(operation: LibraryGameLaunchOperation): GameLaunchSessionHold =
        last?.takeIf { it.first == operation }?.second ?: GameLaunchSessionHold.STARTING
}

/**
 * O15 follow-up (coordinator review, 2026-09-12): what the Main quick-launch route's guard on the Library's own launch
 * answers, before admission.
 *
 * The Library's launch state reads Launching while its launch is being made and IntentSent from dispatch until the Library
 * restores its return (`LibraryViewModel.restoreLaunchReturnTargetState`) or the dispatch watchdog cancels the launch. That
 * is on purpose: the Bottom's NOW_PLAYING promotion reads it for the game's whole run. So the state alone cannot tell a game
 * still starting from one already running, and it outlives the game whenever a return skips the Library's restore: the
 * M button's Home (`MimirNavigationCoordinator.applyMButtonHomeNavigation`) and its Quick launch in place clear the return
 * target the restore waits for and leave the state as it was. The ledger says what the state means now:
 * - null while the Library's launch is not in flight, and while the ledger holds no game session (ENDED, FAILED, STALE or
 *   none): nothing of the Library's is in the way, so admission decides, the ledger being the one owner of contention;
 * - otherwise the game session's hold: STARTING keeps the guard's own words, RUNNING gets the ruled sentence.
 */
internal fun libraryLaunchGuardHold(
    libraryLaunchState: GameLaunchUiState,
    sessions: MimirSessionSnapshot,
): GameLaunchSessionHold? {
    val libraryLaunchInFlight = libraryLaunchState == GameLaunchUiState.Launching ||
        libraryLaunchState == GameLaunchUiState.IntentSent
    return if (libraryLaunchInFlight) sessions.gameSession?.launchHold() else null
}

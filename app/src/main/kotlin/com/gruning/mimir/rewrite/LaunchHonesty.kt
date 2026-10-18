package com.gruning.mimir.rewrite

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import com.gruning.mimir.rewrite.feature.library.LibraryViewModel
import com.gruning.mimir.rewrite.feature.library.emulatorClosedUnexpectedlyMessage

/**
 * Cert 2026-10-08 F1 and F2: a launch that did not go the way the player pressed it says so.
 *
 * **F1, "closed unexpectedly".** The intent was sent (so the launch stays counted: its proof is written at dispatch and
 * is not touched), and then the emulator never took the window from Mimir.
 *
 * The heuristic is the dispatch watchdog's, not a new timer. [MimirGameSession] stays DISPATCHED until
 * `MainActivity.onStop` yields it, which happens the moment the emulator's window covers Mimir. The watchdog
 * (`runGameDispatchWatchdog`) fires only if, a full grace period (`GameDispatchPrimaryGraceMs`, 3 s) after dispatch, the
 * session is STILL DISPATCHED and Mimir STILL owns a resumed, focused window. That is: the emulator started, or was
 * asked to, and never put a frame in front of the player. A crash in `onCreate` is exactly that (cert E1 5a: the stub threw
 * 170 ms after dispatch, Mimir resumed 0.2 s later and the watchdog expired at +3.0 s with `target_never_left_primary`).
 *
 * What it is NOT, and so what keeps a normal quick exit from showing it:
 *  - A player who quits the emulator after one second has seen it: Mimir was covered, `onStop` yielded the session, and a
 *    YIELDED session can never expire (`gameDispatchWatchdogExpired` requires DISPATCHED). Such a return ends as
 *    `game_ended`, or as `game_failed_on_return` with `launch_foreground_span_under_threshold`: that reason is NOT used
 *    here, because it fires on exactly the quick-quit case (every 1.5 s stub finish in the cert tripped it).
 *  - A slow emulator whose splash covers Mimir for eight seconds yields, so it never expires either.
 *
 * Deliberately not covered, with its reason: an emulator that shows a frame and THEN crashes within a second or two looks,
 * to every signal Mimir has without privileged help, the same as a player quitting after a second (Mimir yielded; Mimir came
 * back; the span is short). Telling them apart needs the emulator's exit reason, which Android keeps for the emulator's own
 * uid only. That case stays as it is: a normal return.
 *
 * **F2, "Starting <Emulator>…".** The Library card says it from the dispatch until the session yields
 * (`MimirSessionCoordinator.onGameYielded`). Its timeout is the same watchdog: if the emulator never appears, the card turns
 * into F1's sentence at +3 s; there is no second timer.
 */
internal object LaunchEarlyClose {
    /** The reason `MimirSessionCoordinator.gameDispatchWatchdogExpired` files. */
    const val NeverLeftPrimary: String = "target_never_left_primary"

    /** True when [game] ended because the emulator never took the window, after its intent was sent. */
    fun isUnexpectedClose(game: MimirGameSession): Boolean =
        game.phase == MimirSessionPhase.FAILED &&
            game.failureReason == NeverLeftPrimary &&
            game.dispatchedAtEpochMs != null

    /** The sentence for a surface that has no launch card (a Home launch). */
    fun message(game: MimirGameSession): String = emulatorClosedUnexpectedlyMessage(game.emulatorLabel)

    /**
     * Review round: a HOME or B press between the dispatch and the emulator's first window (about half a second) is a player
     * who changed their mind, not an emulator that died. The watchdog still fires at +3 s, because Mimir is back in front with
     * the session still DISPATCHED, and it cleans up as it always did; only the sentence is withheld. See [UserLeaveSignal].
     */
    fun userLeftOnPurpose(game: MimirGameSession): Boolean = UserLeaveSignal.leftSince(game.dispatchedAtElapsedMs)
}

/**
 * The last thing the player did with their hands, as far as "did they leave on purpose" needs: a user HOME press (the HOME
 * router, any decision) or the B / Back key, noted at the key lane's front door. Any other key after it takes the note back,
 * so "the last input was HOME or B" is literal, with one exception: a D-pad direction, or L1 / R1 (moving between shelves), is
 * only the player moving about Mimir after they left , it says nothing about wanting the launch, so it neither sets nor
 * takes back the note. Touch is not read: a tap is not a way out of a launch the player started.
 */
internal object UserLeaveSignal {
    @Volatile
    private var leftAtElapsedMs: Long? = null

    /** The clock, replaceable so a test does not need a device. */
    @Volatile
    internal var clock: () -> Long = SystemClock::elapsedRealtime

    fun noteHome() {
        leftAtElapsedMs = clock()
    }

    fun noteKey(keyCode: Int) {
        // B, then a D-pad press, used to leave "B" no longer the last input, so the watchdog's sentence came back. Moving the
        // cursor, or between shelves, after leaving is not a change of mind.
        if (isNavigationKey(keyCode)) return
        leftAtElapsedMs = if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) clock() else null
    }

    private fun isNavigationKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_BUTTON_R1

    /** True when the last input was a leave and it came at or after [sinceElapsedMs] (a launch that never recorded a time counts any). */
    fun leftSince(sinceElapsedMs: Long?): Boolean {
        val at = leftAtElapsedMs ?: return false
        return sinceElapsedMs == null || at >= sinceElapsedMs
    }

    internal fun reset() {
        leftAtElapsedMs = null
    }
}

/**
 * Wires F1 and F2 to the session ledger. The Library card hears about both through its ViewModel; a launch that did not
 * start from the Library (Home's shelf, a Home launch card) has no Library card, so it gets the same sentence on Mimir's
 * message rail, the component every other launch refusal on Home already uses.
 */
internal fun installLaunchHonesty(context: Context, library: LibraryViewModel) {
    MainGameHomePark.install(context)
    MimirSessionAuthority.installLibraryDispatchCleanup { operation ->
        // The watchdog's cleanup is the same either way; only whether the card says "closed unexpectedly" depends on the player.
        val game = MimirSessionAuthority.state.value.gameSession
        if (game != null && LaunchEarlyClose.userLeftOnPurpose(game)) {
            library.cancelPendingLaunch(operation)
        } else {
            library.closePendingLaunchUnexpectedly(operation)
        }
    }
    MimirSessionAuthority.installLibraryYieldObserver(library::noteEmulatorYielded)
    MimirSessionAuthority.installEarlyCloseNotifier { game ->
        if (game.libraryOperation == null && !LaunchEarlyClose.userLeftOnPurpose(game)) {
            showMimirMessage(context, LaunchEarlyClose.message(game), MimirMessageClass.Warning)
        }
    }
}

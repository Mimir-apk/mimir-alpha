package com.gruning.mimir.rewrite

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.feature.library.GamelistScope
import com.gruning.mimir.rewrite.feature.library.LibraryViewModel
import com.gruning.mimir.rewrite.feature.companion.CompanionQuickLaunchDestination

/**
 * Executes [CompanionCommandState] commands on the PRIMARY display (HP-11 v2 S3). Lives in its own file:
 * `MimirApp.kt` sits at an ART bytecode-verifier size edge , so
 * its body must never grow — the call site there is one line.
 *
 * A console tap runs the exact path a controller CONFIRM takes on the shelf (`openPlatform` + push
 * [GamelistScope]) so focus scopes, focus memory, and the gamelist-detail payload behave identically no
 * matter which screen asked. Nothing here is speculative: the companion's own surface only changes when
 * the primary's fresh payload comes back to it.
 *
 * What each command gets is decided in one place, [companionCommandAnswer].
 */
@Composable
internal fun CompanionCommandBridge(
    libraryViewModel: LibraryViewModel,
    focusAuthority: FocusAuthority,
    launchInFlight: Boolean,
    libraryForeground: Boolean,
    /**
     * Launch a game the COMPANION picked .
     *
     * Handed in rather than reached for: everything a correct launch needs — the
     * return-target arming and both Tweaker dispatch hooks — lives in `settleEmulatedHomeLaunch`, and the
     * call site has the repository, the launch controller and Home's own rows in scope. Reaching for
     * those here would grow a second launch path in a file whose whole point is to stay thin.
     *
     * Read through `rememberUpdatedState` below: the lambda is rebuilt on recomposition and this collector
     * is started once, so capturing the first one would launch against a stale composition's state.
     */
    launchGameFromCompanion: (String, CompanionQuickLaunchDestination) -> Unit = { _, _ -> },
) {
    val inFlight by rememberUpdatedState(launchInFlight)
    val onLibrary by rememberUpdatedState(libraryForeground)
    val onLaunchGame by rememberUpdatedState(launchGameFromCompanion)
    LaunchedEffect(Unit) {
        CompanionCommandState.commands.collect { command ->
            val answer = companionCommandAnswer(command, launchInFlight = inFlight, libraryForeground = onLibrary)
            when (command) {
                is CompanionCommandState.Command.OpenGamelistOnMain -> {
                    // Quiet refusal (policy) while a game owns the main screen, and R01's while the Library does
                    // not own it: see companionCommandAnswer. The tile's press feedback already acknowledged the finger.
                    if (answer != CompanionCommandAnswer.OPEN_GAMELIST) return@collect
                    libraryViewModel.openPlatform(PlatformId(command.platformIdValue))
                    // Already inside a gamelist (re-tap / switching platform from screen 2): openPlatform
                    // retargets it. Pushing the scope again would stack a duplicate and strand Back.
                    if (focusAuthority.state.value.scope != GamelistScope) {
                        focusAuthority.pushScope(GamelistScope)
                    }
                }
                is CompanionCommandState.Command.LaunchGameOnMain -> {
                    // O15 follow-up: no refusal of its own. Admission decides, and a refused launch is told why, on the
                    // Bottom, with its opening intro cleared (launchCompanionPickedGame).
                    if (answer != CompanionCommandAnswer.LAUNCH_GAME) return@collect
                    onLaunchGame(command.gameIdValue, command.destination)
                }
            }
        }
    }
}

/** What [CompanionCommandBridge] does with one command from screen 2. */
internal enum class CompanionCommandAnswer {
    /** Open the platform's gamelist on Main and land focus there. */
    OPEN_GAMELIST,

    /** Hand the game to the launch path, whose session admission decides whether it starts. */
    LAUNCH_GAME,

    /** Nothing on Main. The companion tile's press feedback has already answered the finger. */
    QUIET,
}

/**
 * The bridge's one decision about a command from screen 2, pure so a JVM test can hold it.
 *
 * - [CompanionCommandState.Command.OpenGamelistOnMain] is refused quietly while a launch is in flight (policy: while a
 *   game owns the main screen, screen 2 navigates nowhere), and while the Library does not own the main screen (R01: pushing
 *   a gamelist over Settings/Browse/Tools used to trap the user, because LibraryScreen's mount stacks LibraryShelfScope over
 *   the bridge's GamelistScope, leaving an active scope with no focusables and no Back path).
 * - [CompanionCommandState.Command.LaunchGameOnMain] always reaches the launch path (O15 follow-up, coordinator review
 *   2026-09-12). It used to be refused quietly whenever [launchInFlight] was true, which is the Library's Launching or
 *   IntentSent and so lasts for the whole run of a game the Library launched: the Bottom card's PLAY did nothing visible for
 *   that whole game (QA 2026-09-11, P10), while the same tap during a Home-launched game reached admission and was told
 *   why, and after a return that skipped the Library's restore it blocked every card launch with nothing in the way. The
 *   session ledger is the one owner of contention: admission refuses a launch that something holds, with the sentence for
 *   what holds it. Nothing the launch path does before admission outlives a refusal, and the refusal clears the opening
 *   intro the card marked as launching. Deliberately not gated on [libraryForeground] either: screen 2 browses on its own
 *   while screen 1 sits on Home .
 */
internal fun companionCommandAnswer(
    command: CompanionCommandState.Command,
    launchInFlight: Boolean,
    libraryForeground: Boolean,
): CompanionCommandAnswer = when (command) {
    is CompanionCommandState.Command.OpenGamelistOnMain ->
        if (launchInFlight || !libraryForeground) CompanionCommandAnswer.QUIET else CompanionCommandAnswer.OPEN_GAMELIST
    is CompanionCommandState.Command.LaunchGameOnMain -> CompanionCommandAnswer.LAUNCH_GAME
}

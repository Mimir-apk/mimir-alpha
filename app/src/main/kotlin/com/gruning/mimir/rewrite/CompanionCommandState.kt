package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.feature.companion.CompanionQuickLaunchDestination
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The companion → primary COMMAND bus (HP-11 v2 S3). [CompanionState] flows one way (primary → screen 2);
 * this is the deliberately narrow return channel for screen 2 to request primary navigation
 * and placement of Android apps on the companion display. It does not mutate the library.
 *
 * Modeled on [CompanionRecentsState]: a process-scoped object so the Presentation window and the primary
 * Activity never hold a direct reference to one another. The companion emits; the primary decides.
 */
object CompanionCommandState {

    sealed interface Command {
        /** Tap a console tile → open that platform's gamelist on the MAIN display and land focus there. */
        data class OpenGamelistOnMain(val platformIdValue: String) : Command

        /** Second beat on an armed app tile → place that Android app on screen 2. Never a game. */

        /**
         * **Screen 2 launched a game** — policy 2026-09-01, the one that put the library on the
         * companion display. This is the verb the interactivity canon quoted above explicitly forbade
         * (*"it never launches a game"*); that clause is superseded, and `CompanionLibraryRoute` is where
         * the supersession is written down.
         *
         * [gameIdValue] is the opaque id the companion was handed, echoed back exactly. The primary
         * re-reads the row from its own repository rather than trusting anything else on this bus: the
         * bus is process-scoped and an id can outlive the shelf that minted it, so the game must be
         * resolved against live library truth at the moment of launch, not at the moment of the tap.
         *
         * The game still runs on the MAIN display. Screen 2 browses and asks; the primary launches.
         */
        data class LaunchGameOnMain(
            val gameIdValue: String,
            val destination: CompanionQuickLaunchDestination = CompanionQuickLaunchDestination.MAIN,
        ) : Command
    }

    // extraBufferCapacity so a tap never suspends the companion's composition thread.
    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 8)
    val commands: SharedFlow<Command> = _commands.asSharedFlow()

    fun openGamelistOnMain(platformIdValue: String) {
        _commands.tryEmit(Command.OpenGamelistOnMain(platformIdValue))
    }

    fun launchGameOnMain(
        gameIdValue: String,
        destination: CompanionQuickLaunchDestination = CompanionQuickLaunchDestination.MAIN,
    ) {
        if (gameIdValue.isBlank()) return
        _commands.tryEmit(Command.LaunchGameOnMain(gameIdValue, destination))
    }
}

package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.feature.companion.CompanionQuickLaunchDestination
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * O15 follow-up (coordinator review, 2026-09-12): what the companion bridge does with a command from screen 2
 * ([companionCommandAnswer]).
 *
 * The Bottom card's PLAY used to be refused quietly while the Library's launch state read Launching or IntentSent, which
 * lasts for the whole run of a game the Library launched (QA 2026-09-11, P10). It now always reaches the launch path, whose
 * admission decides and says why. The console tap keeps its quiet refusal, an policy: while a game owns the main
 * screen, screen 2 navigates nowhere.
 */
class CompanionCommandAnswerTest {

    @Test
    fun `a Bottom card's PLAY always reaches the launch path, whose admission decides`() {
        CompanionQuickLaunchDestination.entries.forEach { destination ->
            Flags.forEach { (inFlight, onLibrary) ->
                assertEquals(
                    "$destination with a launch in flight=$inFlight and the Library on Main=$onLibrary",
                    CompanionCommandAnswer.LAUNCH_GAME,
                    companionCommandAnswer(
                        CompanionCommandState.Command.LaunchGameOnMain("snes/zelda", destination),
                        launchInFlight = inFlight,
                        libraryForeground = onLibrary,
                    ),
                )
            }
        }
    }

    @Test
    fun `a console tap stays quiet while a launch is in flight`() {
        listOf(true, false).forEach { onLibrary ->
            assertEquals(
                "the Library on Main=$onLibrary",
                CompanionCommandAnswer.QUIET,
                companionCommandAnswer(
                    CompanionCommandState.Command.OpenGamelistOnMain("snes"),
                    launchInFlight = true,
                    libraryForeground = onLibrary,
                ),
            )
        }
    }

    @Test
    fun `a console tap opens the gamelist only when the Library owns the main screen`() {
        assertEquals(
            CompanionCommandAnswer.OPEN_GAMELIST,
            companionCommandAnswer(
                CompanionCommandState.Command.OpenGamelistOnMain("snes"),
                launchInFlight = false,
                libraryForeground = true,
            ),
        )
        assertEquals(
            "R01: never over Settings, Browse or Tools",
            CompanionCommandAnswer.QUIET,
            companionCommandAnswer(
                CompanionCommandState.Command.OpenGamelistOnMain("snes"),
                launchInFlight = false,
                libraryForeground = false,
            ),
        )
    }

    private companion object {
        val Flags = listOf(true to true, true to false, false to true, false to false)
    }
}

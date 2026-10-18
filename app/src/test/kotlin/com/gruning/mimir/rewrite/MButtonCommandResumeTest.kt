package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Intent
import com.gruning.mimir.rewrite.core.launch.LaunchResult
import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.LaunchTarget
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * D3: MainActivity resumed by an M-button command over a game running on Main must not start that game's return or end
 * its session. The session ends only when the game actually returns or exits.
 *
 * The command arrives the way the device delivers it: [MButtonIntentAuthority.dispatch] starts the token intent and the
 * Activity's delivery hands that intent to [MButtonIntentAuthority.consume]. The resume is the real
 * [PrimaryStopResumeLedger], and its ticket drives the real [MimirSessionCoordinator] the way `MainActivity.onResume`
 * does: a game in the ticket begins and completes its return. The Activity wiring is pinned as source in
 * [MButtonCommandResumeWiringTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = Application::class)
class MButtonCommandResumeTest {
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val events = mutableListOf<String>()
    private val sessions = MimirSessionCoordinator(trace = { event, _, _ -> events += event })
    private val ledger = PrimaryStopResumeLedger()

    @After
    fun tearDown() {
        MButtonNavigationCommands.pending.value?.let { MButtonNavigationCommands.acknowledge(it.id) }
        while (shadowOf(application).nextStartedActivity != null) Unit
    }

    @Test
    fun `a single-screen Quick launch over a running game keeps the session`() {
        val zelda = gameRunningOnMain()

        deliverMButtonCommand(MButtonPrimaryCommand.QUICK_LAUNCH)
        val resume = resumeMain()

        assertEquals("the command's resume holds the game's return back", zelda, resume.withheldGameOperation)
        assertNull("so it carries no return to start", resume.returnTicket)
        assertFalse(
            "and settles no Tweaker profile under a game that is still running",
            shouldSettleTweakerAfterPrimaryResume(resume.returnTicket),
        )
        assertEquals(MimirSessionPhase.YIELDED_TO_SECONDARY_TASK, sessions.state.value.gameSession?.phase)
        assertEquals(emptyList<String>(), returnEvents())
        assertTrue("Home's own launch return reads the same hold", ledger.gameReturnWithheld())
    }

    @Test
    fun `control - a resume without an M-button token is the game coming back`() {
        val zelda = gameRunningOnMain()

        // HOME and the launcher carry no token, and a token this process did not issue is refused.
        val foreign = Intent(application, MainActivity::class.java)
            .putExtra(MButtonIntentAuthority.ExtraCommandToken, UUID.randomUUID().toString())
        assertNull(MButtonIntentAuthority.consume(foreign, noteDelivered = ledger::noteMButtonCommandDelivered))
        val resume = resumeMain()

        assertNull(resume.withheldGameOperation)
        assertEquals(zelda, resume.returnTicket?.gameOperation)
        assertEquals(MimirSessionPhase.ENDED, sessions.state.value.gameSession?.phase)
        assertEquals(listOf("game_returning", "game_ended"), returnEvents())
    }

    @Test
    fun `a real game quit still returns and ends the session after a command held it back`() {
        val zelda = gameRunningOnMain()

        // Utility over the game brings Main forward; a pause and resume inside that interval is still not a return.
        deliverMButtonCommand(MButtonPrimaryCommand.UTILITY)
        assertEquals(zelda, resumeMain().withheldGameOperation)
        assertEquals(zelda, resumeMain().withheldGameOperation)
        assertEquals(emptyList<String>(), returnEvents())

        // Back to the game: Main stops again with the game in its ticket. The player quits the game and Main resumes
        // with no command. That resume is the return.
        ledger.noteStopped(changingConfigurations = false, ticket = PrimaryStopResumeTicket(gameOperation = zelda))
        assertFalse(ledger.gameReturnWithheld())
        val quit = resumeMain()

        assertNull(quit.withheldGameOperation)
        assertEquals(zelda, quit.returnTicket?.gameOperation)
        assertTrue(shouldSettleTweakerAfterPrimaryResume(quit.returnTicket))
        assertEquals(MimirSessionPhase.ENDED, sessions.state.value.gameSession?.phase)
        assertEquals(listOf("game_returning", "game_ended"), returnEvents())
        // Rank 7c: Main's return path still gets the returned session's own elapsed token to finish.
        assertEquals("zelda-return", primaryGameReturnElapsedToken(sessions.state.value.gameSession, zelda))
    }

    /** Zelda launched from Home onto Main; MainActivity.onStop yielded it and recorded the return ticket. */
    private fun gameRunningOnMain(): MimirGameSessionOperation {
        val game = GameRow(
            gameId = GameId("zelda"),
            title = "Zelda",
            platformId = PlatformId.SNES,
            memberCount = 1,
            duplicateKind = null,
        )
        val target = HomeLaunchReturnTarget(HomeShelfItemId.Emulated(game.gameId), rank = 1L)
        val operation = requireNotNull(
            sessions.prepareHomeGame(
                game = game,
                returnTarget = target,
                returnHandle = HomeLaunchReturnHandle(target = target, token = "zelda-return"),
                startedAtEpochMs = 1_000L,
            ),
        )
        assertTrue(sessions.gameSettled(operation, game.gameId, launched(game)))
        assertTrue(sessions.gameYielded(operation))
        ledger.observeResume()
        ledger.noteStopped(changingConfigurations = false, ticket = PrimaryStopResumeTicket(gameOperation = operation))
        return operation
    }

    /** The M button's own route to MainActivity: the token intent it starts, delivered to the Activity. */
    private fun deliverMButtonCommand(command: MButtonPrimaryCommand) {
        assertTrue(MButtonIntentAuthority.dispatch(application, command))
        val delivered = requireNotNull(shadowOf(application).nextStartedActivity) {
            "the command must start MainActivity"
        }
        MButtonIntentAuthority.consume(delivered, noteDelivered = ledger::noteMButtonCommandDelivered)
    }

    /** MainActivity.onResume: a game in the observation's ticket begins and completes its return. */
    private fun resumeMain(): PrimaryResumeObservation = ledger.observeResume().also { observation ->
        observation.returnTicket?.gameOperation?.let { operation ->
            assertNotNull(sessions.beginGameReturn(operation))
            assertTrue(sessions.completeGameReturn(operation, foregroundProven = true))
        }
    }

    private fun returnEvents(): List<String> = events.filter { it == "game_returning" || it == "game_ended" }

    private fun launched(game: GameRow) = LaunchResult(
        readiness = LaunchReadiness.Ready(
            LaunchTarget(
                gameId = game.gameId,
                platformId = game.platformId,
                emulator = EmulatorProfile(
                    id = EmulatorId("retroarch-snes"),
                    packageName = "com.retroarch",
                    displayName = "RetroArch",
                    installed = true,
                    capability = EmulatorCapability.DIRECT_PAYLOAD,
                    locatorForm = LaunchLocatorForm.CONTENT_URI,
                    supportedPlatforms = setOf(game.platformId),
                ),
                locator = "content://mimir/${game.gameId.value}",
                locatorForm = LaunchLocatorForm.CONTENT_URI,
                title = game.title,
            ),
        ),
        outcome = LaunchOutcome.IntentSent,
    )
}

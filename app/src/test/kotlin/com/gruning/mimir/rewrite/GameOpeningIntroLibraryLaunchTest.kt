package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * A Nova log had no MimirOpening line for a
 * Flycast launch from the Library. These drive the Library's side of the one intro entry the way LibraryViewModel
 * does: beforeDispatch, then afterDispatch with the verdict.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class GameOpeningIntroLibraryLaunchTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        ShadowLog.clear()
        GameOpeningIntroState.hide()
    }

    @After
    fun tearDown() {
        GameOpeningIntroState.hide()
    }

    @Test
    fun `a Library launch starts the intro and holds for launch`() = runTest {
        val game = game("library-game")

        opening().beforeDispatch(game)

        assertEquals("library-game", GameOpeningIntroState.current.value?.gameIdValue)
        assertTrue(openingLogs().toString(), openingLogs().any { it.startsWith("start game=library-game ") })
        assertTrue(openingLogs().toString(), openingLogs().contains("hold-for-launch game=library-game"))
    }

    /**
     * The game's saved sound reaches the intro through the same Library entry every launch uses: the plan carries it, the
     * player is asked to play it (there is no "missing-uri" line), and the start line says `audio=file`, never the path,
     * which names the game.
     */
    @Test
    fun `a Library launch plays the game's saved sound and the log never names its file`() = runTest {
        val sound = "file:///storage/ROMs/snes/media_art/Sound%20Game-audio.m4a"
        val game = game("sound-game", launchAudioUri = sound)

        opening().beforeDispatch(game)

        assertEquals(sound, GameOpeningIntroState.current.value?.audioUri)
        val logs = openingLogs()
        assertTrue(logs.toString(), logs.any { it.startsWith("start game=sound-game ") && it.endsWith(" audio=file") })
        assertFalse(logs.toString(), logs.contains("audio unavailable reason=missing-uri"))
        assertTrue(
            logs.toString(),
            logs.filter { it.startsWith("start ") }.none { it.contains("Sound%20Game") || it.contains("-audio.m4a") },
        )
    }

    @Test
    fun `a Library launch never plays or logs a provider URL that reached the row`() = runTest {
        val providerUrl = "https://www.screenscraper.fr/medias/video.mp4?devid=D&devpassword=P&ssid=U&sspassword=S"
        val game = game("url-game", launchAudioUri = providerUrl)

        opening().beforeDispatch(game)

        assertNull(GameOpeningIntroState.current.value?.audioUri)
        assertEquals("the intro itself still plays", "url-game", GameOpeningIntroState.current.value?.gameIdValue)
        val logs = openingLogs()
        assertTrue(logs.toString(), logs.any { it.startsWith("start game=url-game ") && it.endsWith(" audio=none") })
        assertTrue(logs.toString(), logs.contains("audio unavailable reason=missing-uri"))
        assertTrue(
            logs.toString(),
            logs.none { it.contains("http") || it.contains("screenscraper") || it.contains("sspassword") },
        )
    }

    @Test
    fun `a Library launch with no hero art logs a skip and shows nothing`() = runTest {
        val game = game("no-hero-game", hero = null)
        val opening = opening()

        opening.beforeDispatch(game)
        opening.afterDispatch(this, game, launched = true)
        runCurrent()

        assertNull(GameOpeningIntroState.current.value)
        assertTrue(openingLogs().toString(), openingLogs().contains("skip game=no-hero-game reason=no-hero"))
        assertFalse(openingLogs().toString(), openingLogs().any { it.startsWith("start ") })
    }

    @Test
    fun `a failed Library launch clears the intro and the next launch plays it again`() = runTest {
        val game = game("failed-game")
        val opening = opening()

        opening.beforeDispatch(game)
        assertEquals("failed-game", GameOpeningIntroState.current.value?.gameIdValue)
        opening.afterDispatch(this, game, launched = false)
        advanceTimeBy(GameOpeningIntroExitMs.toLong() + 1L)
        runCurrent()

        assertNull(GameOpeningIntroState.current.value)
        assertFalse(GameOpeningIntroState.isLaunching())
        assertTrue(openingLogs().toString(), openingLogs().contains("end game=failed-game launched=false"))

        opening.beforeDispatch(game)

        assertEquals("failed-game", GameOpeningIntroState.current.value?.gameIdValue)
        assertEquals(2, openingLogs().count { it.startsWith("start game=failed-game ") })
    }

    @Test
    fun `a dispatched Library launch keeps the intro until the game has the screen and then holds 0_2 s more`() = runTest {
        val game = game("dispatched-game")
        // The game takes the screen one second after dispatch, as the yield edge reports it.
        val opening = opening(awaitHandoff = {
            delay(1_000L)
            GameOpeningIntroHandoff.GAME_HAS_SCREEN
        })
        val cuts = GameOpeningIntroState.cutEpoch.value

        opening.beforeDispatch(game)
        opening.afterDispatch(this, game, launched = true)
        // The old blind timer would have started fading at 2.5 s; this one has not moved, and is still holding just
        // short of the 0.2 s that follows the game coming up.
        advanceTimeBy(1_000L + GameOpeningIntroYieldHoldMs - 1L)
        runCurrent()
        assertEquals("dispatched-game", GameOpeningIntroState.current.value?.gameIdValue)

        advanceTimeBy(1L)
        runCurrent()
        assertNull(GameOpeningIntroState.current.value)
        assertEquals("removed with no exit animation", cuts + 1, GameOpeningIntroState.cutEpoch.value)
        assertTrue(openingLogs().toString(), openingLogs().contains("handoff game=dispatched-game via=yield"))
        assertTrue(
            openingLogs().toString(),
            openingLogs().contains("end game=dispatched-game launched=true via=yield"),
        )
    }

    @Test
    fun `a dispatched Library launch whose game never comes up fades at the cap, as it always has`() = runTest {
        val game = game("hung-game")
        val sessions = dispatchedLedger("hung-game")
        val stopped = MutableStateFlow(false)
        val opening = opening(awaitHandoff = { id -> awaitGameOpeningIntroHandoff(id, sessions, stopped) })
        val cuts = GameOpeningIntroState.cutEpoch.value

        opening.beforeDispatch(game)
        val dispatchedAt = testScheduler.currentTime
        opening.afterDispatch(this, game, launched = true)
        advanceTimeBy(GameOpeningIntroHandoffCapMs - 1L)
        runCurrent()
        assertEquals("hung-game", GameOpeningIntroState.current.value?.gameIdValue)

        advanceTimeBy(1L + GameOpeningIntroExitMs)
        runCurrent()
        assertNull(GameOpeningIntroState.current.value)
        assertEquals("the cap fades, it does not cut", cuts, GameOpeningIntroState.cutEpoch.value)
        assertEquals(GameOpeningIntroHandoffCapMs + GameOpeningIntroExitMs, testScheduler.currentTime - dispatchedAt)
        assertTrue(openingLogs().toString(), openingLogs().contains("end game=hung-game launched=true via=cap"))
    }

    @Test
    fun `a Library launch with a Bottom screen attached skips as Home does`() = runTest {
        val game = game("dual-screen-game")

        GameOpeningIntroLibraryLaunchOpening(
            context = context,
            secondaryDisplayId = { 4 },
            enabled = { true },
            sources = { row -> row },
            introContext = EmptyCoroutineContext,
        ).beforeDispatch(game)

        assertNull(GameOpeningIntroState.current.value)
        assertTrue(openingLogs().toString(), openingLogs().contains("skip game=dual-screen-game reason=secondary-display"))
    }

    /**
     * Mimir is on screen for these (a launch that never left, or has not yet), and [awaitHandoff] says what the game
     * does; the default is a game that says nothing.
     */
    private fun opening(
        awaitHandoff: suspend (String) -> GameOpeningIntroHandoff = { awaitCancellation() },
    ) = GameOpeningIntroLibraryLaunchOpening(
        context = context,
        secondaryDisplayId = { null },
        enabled = { true },
        sources = { row -> row },
        introContext = EmptyCoroutineContext,
        finish = { scope, launched ->
            finishGameOpeningIntro(
                scope,
                launched = launched,
                awaitHandoff = awaitHandoff,
                hostStopped = { false },
                restoreMenuMusic = {},
            )
        },
    )

    /** A ledger where the game is dispatched and nothing has moved since: it has not yielded and nothing has settled. */
    private fun dispatchedLedger(id: String) = MutableStateFlow(
        MimirSessionSnapshot(gameSession = dispatchedSession(id)),
    )

    private fun dispatchedSession(id: String) = MimirGameSession(
        operation = MimirGameSessionOperation("op-$id"),
        sequence = 1L,
        gameId = GameId(id),
        platformId = PlatformId.SNES,
        returnAnchor = MimirGameReturnAnchor.Home(
            HomeLaunchReturnTarget(itemId = HomeShelfItemId.Emulated(GameId(id)), rank = 1L),
        ),
        startedAtEpochMs = 1L,
        dualScreen = false,
        phase = MimirSessionPhase.DISPATCHED,
    )

    private fun openingLogs(): List<String> =
        ShadowLog.getLogsForTag("MimirOpening").map { it.msg }

    private fun game(id: String, hero: String? = "file:///hero.png", launchAudioUri: String? = null) = GameRow(
        gameId = GameId(id),
        title = "Opening Game",
        platformId = PlatformId.SNES,
        memberCount = 1,
        duplicateKind = null,
        heroArtUri = hero,
        flatArtUri = null,
        previewVideoUri = null,
        launchAudioUri = launchAudioUri,
    )
}

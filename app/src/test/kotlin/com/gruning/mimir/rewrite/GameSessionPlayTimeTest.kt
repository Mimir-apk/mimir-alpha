package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the launch card said EXTERNAL ELAPSED / NOT TRACKED after four minutes of play, because only a Home launch opened a measurement
 * in the elapsed store. These pin the repair: every session names a measurement ([elapsedToken], [elapsedItemId]), a session no Home launch
 * measured opens its own at the dispatch edge, every existing finish site reaches it, the segments of a parked-and-resumed session are
 * summed with the parked hours left out, and the total is what the store writes and reads back after a restart. The card's row is
 * `HomeLaunchCardPlayTimeRowTest`'s; the Bottom's "before this session" total reads this same store (`MimirCompanionPresentation`).
 */
class GameSessionPlayTimeTest {
    private val zelda = HomeShelfItemId.Emulated(GameId("zelda"))
    private val bootCount = 7

    private fun session(
        operation: String = "library:run-1",
        handle: HomeLaunchReturnHandle? = null,
        placement: MimirGamePlacement = MimirGamePlacement.MAIN,
        phase: MimirSessionPhase = MimirSessionPhase.DISPATCHED,
        dispatchedAt: Long? = 1_000L,
    ) = MimirGameSession(
        operation = MimirGameSessionOperation(operation),
        sequence = 1L,
        gameId = GameId("zelda"),
        title = "Zelda",
        platformId = PlatformId.PS1,
        returnAnchor = MimirGameReturnAnchor.Home(HomeLaunchReturnTarget(zelda, rank = 0L)),
        homeReturnHandle = handle,
        startedAtEpochMs = 1L,
        dispatchedAtElapsedMs = dispatchedAt,
        targetPackageName = "com.github.stenzek.duckstation",
        placement = placement,
        dualScreen = false,
        phase = phase,
    )

    private fun handle() = HomeLaunchReturnHandle.create(
        HomeLaunchReturnTarget(HomeShelfItemId.Emulated(GameId("zelda")), rank = 0L),
        origin = HomeLaunchOrigin.MAIN_HOME,
    )

    // ---- every session names its measurement -------------------------------------------------------------------------------

    @Test
    fun `a session with a Home handle keeps the handle's token and item, any other names its own`() {
        val home = handle()
        assertEquals(home.token, session(handle = home).elapsedToken())
        assertEquals(home.target.itemId, session(handle = home).elapsedItemId())

        val library = session(operation = "library:run-1")
        assertEquals("session:library:run-1", library.elapsedToken())
        assertEquals(zelda, library.elapsedItemId())
        assertTrue("two sessions never share a token", library.elapsedToken() != session(operation = "library:run-2").elapsedToken())
    }

    @Test
    fun `only a session that just dispatched and has no Home handle needs its measurement opened`() {
        assertTrue(GameSessionPlayTime.needsMeasurement(session()))
        assertTrue("a Bottom game too", GameSessionPlayTime.needsMeasurement(session(placement = MimirGamePlacement.BOTTOM)))

        assertFalse("Home's own launch opened it", GameSessionPlayTime.needsMeasurement(session(handle = handle())))
        assertFalse(GameSessionPlayTime.needsMeasurement(null))
        assertFalse("never dispatched", GameSessionPlayTime.needsMeasurement(session(dispatchedAt = null)))
        MimirSessionPhase.values().filter { it != MimirSessionPhase.DISPATCHED }.forEach { phase ->
            assertFalse(phase.name, GameSessionPlayTime.needsMeasurement(session(phase = phase)))
        }
    }

    // ---- every finish site reaches it ------------------------------------------------------------------------------------

    @Test
    fun `Main's return finishes a Library session by its own token, a Bottom one stays the Bottom cleanup's`() {
        val library = session()
        assertEquals(library.elapsedToken(), primaryGameReturnElapsedToken(library, library.operation))
        assertNull("a Bottom game's measurement belongs to the Bottom cleanup", primaryGameReturnElapsedToken(session(placement = MimirGamePlacement.BOTTOM), library.operation))
        assertNull("a successor is not this session", primaryGameReturnElapsedToken(library, MimirGameSessionOperation("library:successor")))

        val home = session(handle = handle())
        assertEquals("a Home session is unchanged", home.homeReturnHandle!!.token, primaryGameReturnElapsedToken(home, home.operation))
    }

    @Test
    fun `a Library launch no longer reads NOT TRACKED - dispatch opens the entry, the return fills it`() {
        val game = session(dispatchedAt = 10_000L)
        var document = HomeExternalSessionElapsedDocument()
        assertNull("before any launch there is nothing", document.elapsedTotals[game.elapsedItemId()])

        document = beginHomeExternalSession(document, game.elapsedItemId(), game.elapsedToken(), 10_000L, bootCount, game.placement)
        assertEquals("the entry exists the moment the game runs, so the card cannot say NOT TRACKED", 0L, document.elapsedTotals[zelda])

        document = finalizeHomeExternalSession(document, primaryGameReturnElapsedToken(game, game.operation)!!, 10_000L + 4 * 60_000L, bootCount)
        assertEquals("four minutes of play", 4 * 60_000L, document.elapsedTotals[zelda])
        assertEquals(4 * 60_000L, document.lastSessionElapsed[zelda])
        assertNull(document.active)
    }

    // ---- segments summed, parked time left out -------------------------------------------------------------------------------

    @Test
    fun `a parked and resumed session adds each played segment and not the hours it sat parked`() {
        val game = session(dispatchedAt = 1_000_000L)
        val token = game.elapsedToken()
        val minute = 60_000L
        var document = beginHomeExternalSession(HomeExternalSessionElapsedDocument(), zelda, token, 1_000_000L, bootCount, game.placement)

        // Played 10 minutes, HOME: the park finishes the segment at the press.
        val pressedAt = 1_000_000L + 10 * minute
        document = finalizeHomeExternalSession(document, primaryGameReturnElapsedToken(game, game.operation)!!, pressedAt, bootCount)
        assertEquals(10 * minute, document.elapsedTotals[zelda])

        // Parked for three hours: nothing runs, nothing is added.
        val resumedAt = pressedAt + 180 * minute
        document = beginHomeExternalSession(document, game.elapsedItemId(), token, resumedAt, bootCount, MimirGamePlacement.MAIN)
        assertEquals("Resume keeps the total, it does not reset it", 10 * minute, document.elapsedTotals[zelda])

        // Played 5 more minutes, then the game returned.
        document = finalizeHomeExternalSession(document, token, resumedAt + 5 * minute, bootCount)

        assertEquals("10 + 5 minutes, not the 3 h 15 min between dispatch and return", 15 * minute, document.elapsedTotals[zelda])
        assertEquals("the last session is the last segment", 5 * minute, document.lastSessionElapsed[zelda])
    }

    @Test
    fun `a second launch adds to the first's total instead of replacing it, and a quick return adds nothing`() {
        val minute = 60_000L
        var document = beginHomeExternalSession(HomeExternalSessionElapsedDocument(), zelda, "session:a", 0L, bootCount)
        document = finalizeHomeExternalSession(document, "session:a", 30 * minute, bootCount)

        document = beginHomeExternalSession(document, zelda, "session:b", 100 * minute, bootCount)
        assertEquals("the new launch starts from what is already there", 30 * minute, document.elapsedTotals[zelda])
        document = finalizeHomeExternalSession(document, "session:b", 100 * minute + 2_000L, bootCount)

        assertEquals("two seconds is under the quick-return threshold", 30 * minute, document.elapsedTotals[zelda])
    }

    // ---- persisted, and read back after a restart ----------------------------------------------------------------------------

    @Test
    fun `the total is written and read back after a restart, with the measurement still open`() {
        val minute = 60_000L
        var document = beginHomeExternalSession(HomeExternalSessionElapsedDocument(), zelda, "session:a", 0L, bootCount, MimirGamePlacement.MAIN)
        document = finalizeHomeExternalSession(document, "session:a", 84 * minute, bootCount)
        document = beginHomeExternalSession(document, zelda, "session:b", 90 * minute, bootCount, MimirGamePlacement.MAIN)

        val restarted = parseHomeExternalSessionElapsedDocument(encodeHomeExternalSessionElapsedDocument(document))

        assertEquals(document, restarted)
        assertEquals("1 h 24 min of finished play", 84 * minute, restarted.elapsedTotals[zelda])
        assertNotNull("the open measurement survives, so a restart can still finish it", restarted.active)
        assertEquals("session:b", restarted.active!!.returnToken)
    }

    // ---- a launch cancelled while its clock starts leaves no open measurement ------------------------------------------------

    @Test
    fun `#258 a cancel that ran before the begin landed does not leave a measurement open for a game that is gone`() = runBlocking {
        val game = session(dispatchedAt = 10_000L)
        val token = game.elapsedToken()
        var current: MimirGameSession? = game
        var document = HomeExternalSessionElapsedDocument()
        val calls = mutableListOf<String>()

        val opened = GameSessionPlayTime.beginWhileLaunched(
            game = game,
            current = { current },
            open = {
                // The dispatch watchdog's cleanup runs while the store is still busy: the session leaves DISPATCHED, then the cleanup
                // cancels the measurement, which does not exist yet. Only then does the begin land.
                current = game.copy(phase = MimirSessionPhase.RECOVERING)
                document = cancelHomeExternalSession(document, token)
                document = beginHomeExternalSession(document, game.elapsedItemId(), token, 10_000L, bootCount, game.placement)
                calls += "open"
                true
            },
            cancel = {
                document = cancelHomeExternalSession(document, token)
                calls += "cancel"
            },
        )

        assertFalse("the launch was over, so the clock did not start", opened)
        assertEquals(listOf("open", "cancel"), calls)
        assertNull("no open session without a game", document.active)
    }

    @Test
    fun `#258 a launch that is still going keeps its measurement, and one that is already over never opens one`() = runBlocking {
        val game = session(dispatchedAt = 10_000L)
        val token = game.elapsedToken()
        var document = HomeExternalSessionElapsedDocument()
        var cancelled = 0

        // Yielded to the emulator while the clock started: the ordinary course of a launch.
        var current: MimirGameSession? = game.copy(phase = MimirSessionPhase.YIELDED_TO_SECONDARY_TASK)
        val opened = GameSessionPlayTime.beginWhileLaunched(
            game = game,
            current = { current },
            open = {
                document = beginHomeExternalSession(document, game.elapsedItemId(), token, 10_000L, bootCount, game.placement)
                true
            },
            cancel = { cancelled++ },
        )
        assertTrue(opened)
        assertEquals(0, cancelled)
        assertEquals(token, document.active?.returnToken)

        // A cancel that comes after the begin is the ordinary cancel's: it finds the measurement and closes it.
        document = cancelHomeExternalSession(document, token)
        assertNull(document.active)

        // Already over before the begin ran, or replaced by another launch: nothing is opened at all.
        val notOpened = mutableListOf<MimirGameSession?>(null, game.copy(phase = MimirSessionPhase.FAILED), game.copy(operation = MimirGameSessionOperation("library:run-2")))
        notOpened.forEach { gone ->
            current = gone
            var touched = false
            val result = GameSessionPlayTime.beginWhileLaunched(game, { current }, { touched = true; true }, { touched = true })
            assertFalse(result)
            assertFalse("the store is not touched for a launch that is over", touched)
        }
    }

    @Test
    fun `#258 only a cancelled or finished session is over, every phase before and including RETURNING still owns the measurement`() {
        val game = session()
        val over = setOf(MimirSessionPhase.RECOVERING, MimirSessionPhase.FAILED, MimirSessionPhase.ENDED, MimirSessionPhase.STALE)
        MimirSessionPhase.values().forEach { phase ->
            assertEquals(phase.name, phase !in over, GameSessionPlayTime.stillLaunched(game, game.copy(phase = phase)))
        }
    }

    // ---- the wiring the JVM cannot run -------------------------------------------------------------------------------------

    @Test
    fun `the dispatch edge, Main's return, and the Bottom's cleanup are wired to the one measurement`() {
        assertTrue(
            sourceFile("MimirSessionCoordinator.kt").contains("GameSessionPlayTime.onDispatched(state.value.gameSession)"),
        )
        // the real begin goes through the launch-still-current check, never straight to the store.
        val playTime = sourceFile("GameSessionPlayTime.kt")
        assertTrue(playTime.contains("suspend fun begin(context: Context, game: MimirGameSession): Boolean = beginWhileLaunched("))
        assertTrue(playTime.contains("cancel = { HomeExternalSessionElapsedStore.cancel(context, game.elapsedToken()) },"))
        val runtime = sourceFile("CompanionProcessRuntime.kt")
        assertTrue(runtime.contains("returnToken = game.elapsedToken(),"))
        assertTrue(runtime.contains("if (handle != null) acknowledgeHomeLaunchReturnDurably(context, handle)"))
        assertTrue(sourceFile("HomeLaunchReturnOrigin.kt").contains("?.elapsedToken()"))
        // A HOME park finishes the segment with the same token, and Resume opens the next one with it, for a Library session as for a Home one.
        val park = sourceFile("MainGameHomePark.kt")
        assertTrue(park.contains("itemId = game.elapsedItemId(),\n            returnToken = game.elapsedToken(),"))
        assertTrue(park.contains("homeParkSegments.resumed(lease.operation, resumedAt)"))
        assertFalse("no segment is skipped for want of a Home handle", park.contains(".homeReturnHandle\n            ?: return"))
    }

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}

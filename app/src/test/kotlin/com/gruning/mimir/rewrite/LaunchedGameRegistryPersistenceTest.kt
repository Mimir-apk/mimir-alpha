package com.gruning.mimir.rewrite

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Update: the performance card's memory of the games Mimir launched was in memory only, so a process death
 * while a game ran left the game switched back to through Recents a stranger. These drive the real session ledger
 * and the real registry through a simulated process death (a fresh registry reading what the first one wrote) and
 * pin what comes back, what does not, and that nothing blocks the caller.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LaunchedGameRegistryPersistenceTest {
    // --- the process comes back ---------------------------------------------------------------------------

    @Test
    fun `games launched before the process died are remembered by the next one`() = runTest {
        val medium = MemoryMedium()
        val first = process(medium, boot = 7)
        first.start()
        val ledger = Ledger(first.registry)
        val a = ledger.launch("alpha", GameA)
        ledger.returnToMimir(a) // A's session ENDS; A keeps running in Recents
        ledger.launch("beta", GameB)
        advanceUntilIdle()

        first.die()
        val second = process(medium, boot = 7)
        assertTrue("a fresh process knows no game before the restore lands", second.registry.packages().isEmpty())
        second.start()
        advanceUntilIdle()

        assertEquals(
            "both launches, least recently used first; the ENDED one is kept, as it is in memory",
            listOf(GameA, GameB),
            second.registry.packages().toList(),
        )
        val restoredA = second.registry.games().first { it.packageName == GameA }
        assertEquals("the session key comes back", a.value, restoredA.operation)
        assertEquals("alpha", restoredA.gameId)
        assertNotNull("the launch time comes back", restoredA.launchedAtEpochMs)
        assertEquals(first.registry.games(), second.registry.games())
    }

    @Test
    fun `the restored memory makes a previous game look like a Mimir game again`() = runTest {
        val medium = MemoryMedium()
        val first = process(medium, boot = 7)
        first.start()
        val ledger = Ledger(first.registry)
        ledger.returnToMimir(ledger.launch("alpha", GameA))
        advanceUntilIdle()
        first.die()

        val second = process(medium, boot = 7)
        second.start()
        advanceUntilIdle()

        // The process came back with an empty ledger, a non-seed package in front, and GAMES_ONLY.
        fun cardFor(registered: Set<String>) = performanceOverlayCardState(
            visibility = PerformanceOverlayVisibility.GAMES_ONLY,
            dispatchedGamePackageName = null,
            rendererPackage = null,
            gateSignal = PerformanceOverlayForegroundSignal.ForeignForeground(GameA),
            registeredGames = registered,
        )
        assertEquals(
            "without the memory the game in front is a stranger and the card stays hidden",
            PerformanceOverlayCardState.Hidden,
            cardFor(emptySet()),
        )
        assertEquals(
            PerformanceOverlayCardState(wanted = true, target = GameA),
            cardFor(second.registry.packages()),
        )
    }

    // --- what is not restored -----------------------------------------------------------------------------

    @Test
    fun `a game forgotten before the death is not restored`() = runTest {
        val medium = MemoryMedium()
        val first = process(medium, boot = 7)
        first.start()
        val ledger = Ledger(first.registry)
        val a = ledger.launch("alpha", GameA)
        ledger.returnToMimir(a)
        ledger.launch("beta", GameB)
        first.registry.observeSession("op-failed", GameC, MimirSessionPhase.DISPATCHED)
        first.registry.observeSession("op-failed", GameC, MimirSessionPhase.FAILED)
        first.registry.observeSession("op-stale", GameD, MimirSessionPhase.DISPATCHED)
        first.registry.observeSession("op-stale", GameD, MimirSessionPhase.STALE)
        first.registry.observeSession("op-tab", GameE, MimirSessionPhase.DISPATCHED)
        advanceUntilIdle()
        assertEquals(setOf(GameA, GameB, GameE), first.registry.packages())

        // Its Tabs row was closed (or its process proven gone): the same call the live path makes.
        assertTrue(forgetLaunchedGameOnTabClose(GameA, expectedOperation = null, registry = first.registry))
        assertTrue(forgetLaunchedGameOnTabClose(GameE, MimirGameSessionOperation("op-tab"), first.registry))
        advanceUntilIdle()
        first.die()

        val second = process(medium, boot = 7)
        second.start()
        advanceUntilIdle()

        assertEquals(
            "closed, FAILED and STALE games stay forgotten across the death; only B, still launched, is back",
            listOf(GameB),
            second.registry.packages().toList(),
        )
    }

    @Test
    fun `the cap holds across the death and the evicted game is gone`() = runTest {
        val medium = MemoryMedium()
        val first = process(medium, boot = 7)
        first.start()
        (1..PerformanceOverlayGameRegistryCap + 2).forEach { first.registry.note("com.example.game$it", "op$it") }
        advanceUntilIdle()
        first.die()

        val second = process(medium, boot = 7)
        second.start()
        advanceUntilIdle()

        assertEquals(PerformanceOverlayGameRegistryCap, second.registry.size)
        assertFalse(second.registry.contains("com.example.game1"))
        assertFalse(second.registry.contains("com.example.game2"))
        assertTrue(second.registry.contains("com.example.game${PerformanceOverlayGameRegistryCap + 2}"))
    }

    @Test
    fun `a stale entry whose task is gone is dropped and stays dropped`() = runTest {
        val medium = MemoryMedium()
        val first = process(medium, boot = 7)
        first.start()
        listOf(GameA, GameB, GameC, GameD).forEachIndexed { index, pkg -> first.registry.note(pkg, "op$index") }
        advanceUntilIdle()
        first.die()

        val probed = mutableListOf<String>()
        val second = process(
            medium,
            boot = 7,
            liveness = { pkg ->
                probed += pkg
                when (pkg) {
                    GameA -> PrivilegedPackageLiveness.ABSENT // a positive answer: the process is gone
                    GameB -> PrivilegedPackageLiveness.ALIVE
                    GameC -> PrivilegedPackageLiveness.UNKNOWN // an unanswered probe is never proof
                    else -> error("the probe itself failed")
                }
            },
        )
        second.start()
        advanceUntilIdle()

        assertEquals(setOf(GameA, GameB, GameC, GameD), probed.toSet())
        assertEquals(
            "only the proven-gone game is dropped; alive, unknown and a throwing probe all keep it",
            listOf(GameB, GameC, GameD),
            second.registry.packages().toList(),
        )

        // And the drop was written: a third process does not get it back, even if it could not probe.
        second.die()
        val third = process(medium, boot = 7)
        third.start()
        advanceUntilIdle()
        assertEquals(listOf(GameB, GameC, GameD), third.registry.packages().toList())
    }

    @Test
    fun `the probe drops only the launch it restored, never a newer one of the same emulator`() = runTest {
        val medium = MemoryMedium()
        val first = process(medium, boot = 7)
        first.start()
        first.registry.note(GameA, "op-old")
        advanceUntilIdle()
        first.die()

        lateinit var second: FakeProcess
        second = process(
            medium,
            boot = 7,
            liveness = {
                // While the probe is out, the player launches the emulator again.
                second.registry.forgetPackage(GameA)
                second.registry.note(GameA, "op-new")
                PrivilegedPackageLiveness.ABSENT
            },
        )
        second.start()
        advanceUntilIdle()

        assertEquals("op-new", second.registry.games().single().operation)
    }

    @Test
    fun `a document from another boot is dropped whole and replaced`() = runTest {
        val medium = MemoryMedium()
        val first = process(medium, boot = 7)
        first.start()
        first.registry.note(GameA, "op1")
        first.registry.note(GameB, "op2")
        advanceUntilIdle()
        first.die()

        val rebooted = process(medium, boot = 8)
        rebooted.start()
        advanceUntilIdle()

        assertTrue("a reboot ended every task", rebooted.registry.packages().isEmpty())
        val rewritten = parseLaunchedGamesDocument(medium.document)
        assertTrue("the old boot's games are not left in the file", rewritten.games.isEmpty())
        assertEquals(8, rewritten.bootCount)
    }

    @Test
    fun `a boot that cannot be told restores nothing`() = runTest {
        val medium = MemoryMedium()
        val first = process(medium, boot = 7)
        first.start()
        first.registry.note(GameA, "op1")
        advanceUntilIdle()
        first.die()

        val unknown = process(medium, boot = null)
        unknown.start()
        advanceUntilIdle()

        assertTrue(unknown.registry.packages().isEmpty())
    }

    // --- ordering, races, threading -----------------------------------------------------------------------

    @Test
    fun `a launch noted while the restore was pending wins and is not overwritten`() = runTest {
        val medium = MemoryMedium()
        val first = process(medium, boot = 7)
        first.start()
        first.registry.note(GameA, "op-old")
        first.registry.note(GameB, "op-b")
        advanceUntilIdle()
        first.die()

        val second = process(medium, boot = 7)
        // A recovered session publishes before the restore has run.
        second.registry.note(GameA, "op-new")
        second.start()
        advanceUntilIdle()

        assertEquals(
            "the remembered game sits below the live one",
            listOf(GameB, GameA),
            second.registry.packages().toList(),
        )
        assertEquals("op-new", second.registry.games().first { it.packageName == GameA }.operation)
        val onDisk = parseLaunchedGamesDocument(medium.document)
        assertEquals(second.registry.games(), onDisk.games)
    }

    @Test
    fun `a launch this process already removed cannot be restored`() {
        val registry = PerformanceOverlayGameRegistry()
        registry.note(GameA, "op1")
        registry.forgetPackage(GameA)

        val taken = registry.restore(listOf(LaunchedGame(GameA, "op1"), LaunchedGame(GameB, "op2")))

        assertEquals(1, taken)
        assertEquals(setOf(GameB), registry.packages())
    }

    @Test
    fun `noting a game never writes on the caller's thread and a burst is one queued write`() = runTest {
        val medium = MemoryMedium()
        val process = process(medium, boot = 7)
        process.start()
        advanceUntilIdle()
        val writesAtStart = medium.writes

        repeat(20) { process.registry.note("com.example.game${it % 4}", "op$it") }
        assertEquals("the caller has not touched the medium", writesAtStart, medium.writes)

        advanceUntilIdle()
        assertTrue("the queued list is written once the writer runs", medium.writes > writesAtStart)
        assertTrue("a burst collapses: the newest list wins", medium.writes - writesAtStart <= 2)
        assertEquals(process.registry.games(), parseLaunchedGamesDocument(medium.document).games)
    }

    @Test
    fun `nothing is written when nothing changed`() = runTest {
        val medium = MemoryMedium()
        val process = process(medium, boot = 7)
        process.start()
        process.registry.note(GameA, "op1")
        advanceUntilIdle()
        val writes = medium.writes

        process.registry.note(GameA, "op1") // the same launch seen again
        process.registry.touch(GameA) // every beat
        process.registry.forgetPackage(GameB) // a stranger
        process.registry.observeSession("op1", GameA, MimirSessionPhase.ENDED)
        advanceUntilIdle()

        assertEquals(writes, medium.writes)
    }

    @Test
    fun `a medium that fails to read or write costs the process nothing but the memory`() = runTest {
        val failing = object : LaunchedGameDocumentMedium {
            override fun read(): String? = error("the disk is gone")
            override fun write(document: String): Boolean = error("the disk is full")
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val registry = PerformanceOverlayGameRegistry()
        val lines = mutableListOf<String>()
        LaunchedGameRegistryPersistence(registry, failing, { 7 }, { PrivilegedPackageLiveness.UNKNOWN }, scope, lines::add).start()
        registry.note(GameA, "op1")
        advanceUntilIdle()

        assertTrue("the in-memory registry is untouched by a dead disk", registry.contains(GameA))
        assertTrue(lines.any { it.startsWith("launched games not written") })
        scope.cancel()
    }

    // --- the document -------------------------------------------------------------------------------------

    @Test
    fun `the document holds only the package, session key, game id, launch time and the boot`() {
        val encoded = encodeLaunchedGamesDocument(
            bootCount = 7,
            games = listOf(
                LaunchedGame(GameA, "library:3b9f", "game-1", 1_700_000_000_000L),
                LaunchedGame(GameB, "home:77aa"),
            ),
        )
        val root = JSONObject(encoded)
        assertEquals(setOf("schemaVersion", "bootCount", "games"), root.keys().asSequence().toSet())
        val rows = root.getJSONArray("games")
        assertEquals(2, rows.length())
        val allowed = setOf("package", "operation", "gameId", "launchedAtEpochMs")
        for (index in 0 until rows.length()) {
            assertTrue(rows.getJSONObject(index).keys().asSequence().toSet().all { it in allowed })
        }
        assertEquals(
            LaunchedGamesDocument(
                7,
                listOf(
                    LaunchedGame(GameA, "library:3b9f", "game-1", 1_700_000_000_000L),
                    LaunchedGame(GameB, "home:77aa"),
                ),
            ),
            parseLaunchedGamesDocument(encoded),
        )
    }

    @Test
    fun `a missing, foreign or corrupt document is an empty one and bad rows cost only themselves`() {
        val empty = LaunchedGamesDocument(bootCount = null, games = emptyList())
        assertEquals(empty, parseLaunchedGamesDocument(null))
        assertEquals(empty, parseLaunchedGamesDocument(""))
        assertEquals(empty, parseLaunchedGamesDocument("{not json"))
        assertEquals(empty, parseLaunchedGamesDocument("""{"schemaVersion":99,"bootCount":7,"games":[]}"""))

        val mixed = parseLaunchedGamesDocument(
            """
            {"schemaVersion":1,"bootCount":7,"games":[
              {"package":"","operation":"op0"},
              {"package":"com.example.one"},
              {"package":"com.example.two","operation":"op2","gameId":"g2","launchedAtEpochMs":-5},
              {"package":"com.example.three","operation":"op3"},
              {"package":"com.example.two","operation":"op4"},
              "nonsense"
            ]}
            """.trimIndent(),
        )
        assertEquals(7, mixed.bootCount)
        assertEquals(
            "bad rows skipped, a repeated package keeps its newest row, a negative time reads as unknown",
            listOf(LaunchedGame("com.example.three", "op3"), LaunchedGame("com.example.two", "op4")),
            mixed.games,
        )

        val huge = encodeLaunchedGamesDocument(
            7,
            (1..50).map { LaunchedGame("com.example.game$it", "op$it") },
        )
        val parsed = parseLaunchedGamesDocument(huge)
        assertEquals("a file cannot grow the registry past its cap", PerformanceOverlayGameRegistryCap, parsed.games.size)
        assertEquals("the newest rows are the ones kept", "com.example.game50", parsed.games.last().packageName)
        assertNull(parsed.games.first().gameId)
    }

    // --- harness ------------------------------------------------------------------------------------------

    private class MemoryMedium(var document: String? = null) : LaunchedGameDocumentMedium {
        var writes = 0
            private set

        override fun read(): String? = document

        override fun write(document: String): Boolean {
            this.document = document
            writes += 1
            return true
        }
    }

    private fun TestScope.process(
        medium: LaunchedGameDocumentMedium,
        boot: Int?,
        liveness: (String) -> PrivilegedPackageLiveness = { PrivilegedPackageLiveness.UNKNOWN },
    ) = FakeProcess(medium, boot, liveness, testScheduler)

    /** One process's registry and persistence over a shared medium; [die] stops its writer like a kill would. */
    private class FakeProcess(
        medium: LaunchedGameDocumentMedium,
        boot: Int?,
        liveness: (String) -> PrivilegedPackageLiveness,
        scheduler: TestCoroutineScheduler,
    ) {
        val registry = PerformanceOverlayGameRegistry()
        private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        private val persistence = LaunchedGameRegistryPersistence(registry, medium, { boot }, liveness, scope)

        fun start() {
            persistence.start()
        }

        fun die() {
            scope.cancel()
            registry.listener = null
        }
    }

    /** The real one-slot session ledger, feeding [registry] exactly as the authority feeds the process-wide one. */
    private class Ledger(registry: PerformanceOverlayGameRegistry) {
        val coordinator = MimirSessionCoordinator(
            trace = { _, snapshot, _ -> MimirLaunchedGames.observe(snapshot.gameSession, into = registry) },
        )

        fun game(id: String) = GameRow(
            gameId = GameId(id),
            title = id,
            platformId = PlatformId.SNES,
            memberCount = 1,
            duplicateKind = null,
        )

        /** Prepares and dispatches [id] to the emulator [packageName], the way a library launch does. */
        fun launch(id: String, packageName: String): MimirGameSessionOperation {
            val game = game(id)
            val target = HomeLaunchReturnTarget(HomeShelfItemId.Emulated(game.gameId), rank = 0L)
            val operation = requireNotNull(coordinator.prepareHomeGame(game = game, returnTarget = target)) {
                "the one-game guard refused $id"
            }
            assertTrue(coordinator.gameSettled(operation, game.gameId, dispatched(game, packageName)))
            return operation
        }

        /** The player is back in Mimir: the session ENDS, the game keeps running. */
        fun returnToMimir(operation: MimirGameSessionOperation) {
            requireNotNull(coordinator.beginGameReturn(operation))
            assertTrue(coordinator.completeGameReturn(operation, foregroundProven = true))
        }

        private fun dispatched(game: GameRow, packageName: String) = LaunchResult(
            readiness = LaunchReadiness.Ready(
                LaunchTarget(
                    gameId = game.gameId,
                    platformId = game.platformId,
                    emulator = EmulatorProfile(
                        id = EmulatorId("emulator-$packageName"),
                        packageName = packageName,
                        displayName = packageName,
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

    private companion object {
        const val GameA = "io.github.lime3ds.android"
        const val GameB = "org.dolphinemu.dolphinemu"
        const val GameC = "com.example.gamec"
        const val GameD = "com.example.gamed"
        const val GameE = "com.example.gamee"
    }
}

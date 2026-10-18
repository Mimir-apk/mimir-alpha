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
import com.gruning.mimir.rewrite.core.tweaker.TweakerTelemetry
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telemetry disappeared on Thor when switching back to an earlier game. The card was wanted only
 * for the session ledger's ONE game, so a game switched
 * back to through Recents looked like a stranger. The card is now a pure function of the foreground, the
 * ledger's game and the games Mimir launched; these pin that, with the real one-slot ledger underneath.
 */
class PerformanceOverlayReconcileTest {
    private val gamesOnly = PerformanceOverlayVisibility.GAMES_ONLY

    private fun foreign(packageName: String) = PerformanceOverlayForegroundSignal.ForeignForeground(packageName)

    private fun state(
        dispatched: String?,
        foreground: PerformanceOverlayForegroundSignal,
        registered: Set<String>,
        renderer: String? = null,
        visibility: PerformanceOverlayVisibility = gamesOnly,
    ) = performanceOverlayCardState(
        visibility = visibility,
        dispatchedGamePackageName = dispatched,
        rendererPackage = renderer,
        gateSignal = performanceOverlayGateSignal(renderer, foreground),
        registeredGames = registered,
    )

    private fun on(target: String?) = PerformanceOverlayCardState(wanted = true, target = target)

    // --- the reported defect, against the real ledger ------------------------------------------------------

    @Test
    fun `A then B then A - the card targets A again`() {
        val ledger = Ledger()
        val a = ledger.launch("alpha", GameA)
        assertEquals(on(GameA), state(ledger.dispatched(), foreign(GameA), ledger.registry.packages()))

        // Back to Mimir: A's session ENDS and A keeps running in Recents. Then B takes the ledger.
        ledger.returnToMimir(a)
        ledger.launch("beta", GameB)
        assertEquals("the ledger now owns B alone", GameB, ledger.dispatched())
        assertEquals(on(GameB), state(ledger.dispatched(), foreign(GameB), ledger.registry.packages()))

        // Recents back to A: the ledger still says B, the card must follow what is in front.
        val registered = ledger.registry.packages()
        assertEquals(on(GameA), state(ledger.dispatched(), foreign(GameA), registered))
        assertEquals(
            "with the live renderer proof the beat also has",
            on(GameA),
            state(ledger.dispatched(), foreign(GameA), registered, renderer = GameA),
        )
        // and straight back to B, and to A once more: the same pure answer every time.
        assertEquals(on(GameB), state(ledger.dispatched(), foreign(GameB), registered, renderer = GameB))
        assertEquals(on(GameA), state(ledger.dispatched(), foreign(GameA), registered, renderer = GameA))

        assertFalse(
            "the ledger-only gate that shipped before the registry would have hidden A",
            performanceOverlayShouldPresentCard(gamesOnly, ledger.dispatched(), foreign(GameA)),
        )
    }

    @Test
    fun `A then B, B ends, A in front - the card targets A`() {
        val ledger = Ledger()
        val a = ledger.launch("alpha", GameA)
        ledger.returnToMimir(a)
        val b = ledger.launch("beta", GameB)
        ledger.returnToMimir(b)
        assertNull("both sessions are over, the ledger names no game", ledger.dispatched())

        assertEquals(on(GameA), state(null, foreign(GameA), ledger.registry.packages()))

        // B's tab is closed (process proven gone): forgetting it must not touch what is in front.
        assertTrue(ledger.registry.forget(GameB, b.value))
        assertFalse(GameB in ledger.registry.packages())
        assertEquals(on(GameA), state(null, foreign(GameA), ledger.registry.packages()))
    }

    @Test
    fun `the ledger still admits one game at a time, which is why the registry exists`() {
        val ledger = Ledger()
        ledger.launch("alpha", GameA)
        val game = ledger.game("beta")
        val target = HomeLaunchReturnTarget(HomeShelfItemId.Emulated(game.gameId), rank = 0L)

        assertNull(ledger.coordinator.prepareHomeGame(game = game, returnTarget = target))
    }

    // --- what is in front decides -------------------------------------------------------------------------

    @Test
    fun `a non-game in front gets no card`() {
        val registered = setOf(GameA, GameB)

        assertEquals(PerformanceOverlayCardState.Hidden, state(GameB, foreign(Chrome), registered))
        assertEquals(PerformanceOverlayCardState.Hidden, state(null, foreign(Chrome), registered))
        // Mimir itself in front is neutral: no card, even though two registered games are still running.
        assertEquals(
            PerformanceOverlayCardState.Hidden,
            state(GameB, PerformanceOverlayForegroundSignal.NeutralForeground, registered),
        )
    }

    @Test
    fun `without a foreground cross-check the ledger's game is trusted exactly as before`() {
        val registered = setOf(GameA, GameB)

        listOf(
            PerformanceOverlayForegroundSignal.UsageAccessMissing,
            PerformanceOverlayForegroundSignal.AwaitingObservation,
        ).forEach { signal ->
            assertEquals("$signal", on(GameB), state(GameB, signal, registered))
            assertEquals("$signal", PerformanceOverlayCardState.Hidden, state(null, signal, registered))
        }
    }

    @Test
    fun `GameNative proven in front keeps its card whatever the ledger holds`() {
        assertEquals(
            on(GAME_NATIVE_FRAME_RATE_PACKAGE),
            state(GameB, foreign(GAME_NATIVE_FRAME_RATE_PACKAGE), setOf(GameA, GameB), renderer = GAME_NATIVE_FRAME_RATE_PACKAGE),
        )
    }

    @Test
    fun `visibility off draws no card and always draws one whatever is in front`() {
        val registered = setOf(GameA, GameB)

        assertEquals(
            PerformanceOverlayCardState.Hidden,
            state(GameB, foreign(GameA), registered, visibility = PerformanceOverlayVisibility.OFF),
        )
        assertEquals(
            on(GameA),
            state(GameB, foreign(GameA), registered, visibility = PerformanceOverlayVisibility.ALWAYS),
        )
        assertEquals(
            "ALWAYS has no game to bind to when a non-game is in front, but the card stays",
            on(null),
            state(GameB, foreign(Chrome), registered, visibility = PerformanceOverlayVisibility.ALWAYS),
        )
    }

    @Test
    fun `an empty registry answers exactly what the ledger-only gate answered`() {
        val signals = listOf(
            PerformanceOverlayForegroundSignal.UsageAccessMissing,
            PerformanceOverlayForegroundSignal.AwaitingObservation,
            PerformanceOverlayForegroundSignal.NeutralForeground,
            foreign(GameA),
            foreign(GameB),
            foreign(Chrome),
        )
        var compared = 0
        PerformanceOverlayVisibility.entries.forEach { visibility ->
            listOf(null, GameA, GameB).forEach { dispatched ->
                listOf(null, GameA, GameB, GAME_NATIVE_FRAME_RATE_PACKAGE).forEach { renderer ->
                    signals.forEach { signal ->
                        val gate = performanceOverlayGateSignal(renderer, signal)
                        val before = performanceOverlayShouldPresentCard(
                            visibility = visibility,
                            dispatchedGamePackageName = dispatched ?: renderer,
                            foregroundSignal = gate,
                            gameNativeForegroundProven = renderer == GAME_NATIVE_FRAME_RATE_PACKAGE,
                        )
                        val now = performanceOverlayCardState(
                            visibility = visibility,
                            dispatchedGamePackageName = dispatched,
                            rendererPackage = renderer,
                            gateSignal = gate,
                            registeredGames = emptySet(),
                        )
                        assertEquals("$visibility dispatched=$dispatched renderer=$renderer signal=$signal", before, now.wanted)
                        compared += 1
                    }
                }
            }
        }
        assertEquals(3 * 3 * 4 * 6, compared)
    }

    // --- the telemetry source follows the foreground ------------------------------------------------------

    @Test
    fun `the frame-rate source and the card both rebind to whichever game is in front`() {
        val registered = setOf(GameA, GameB)

        listOf(GameA, GameB, GameA).forEach { inFront ->
            val renderer = performanceOverlayForegroundRenderPackage(
                usageAccessGranted = true,
                mimirPrimaryResumed = false,
                interactive = true,
                currentForegroundPackageName = inFront,
            )
            assertEquals("the privileged read is asked about the live foreground, never the ledger", inFront, renderer)
            assertEquals(on(inFront), state(GameB, foreign(inFront), registered, renderer = renderer))
        }
        // Mimir back in front: no renderer, and no card.
        val none = performanceOverlayForegroundRenderPackage(
            usageAccessGranted = true,
            mimirPrimaryResumed = true,
            interactive = true,
            currentForegroundPackageName = GameA,
        )
        assertNull(none)
        assertEquals(
            PerformanceOverlayCardState.Hidden,
            state(GameB, PerformanceOverlayForegroundSignal.NeutralForeground, registered, renderer = none),
        )
    }

    // --- idempotence --------------------------------------------------------------------------------------

    @Test
    fun `the same state re-applied is no change and logs nothing`() {
        val lines = mutableListOf<String>()
        val reconciler = PerformanceOverlayReconciler(lines::add)

        assertNull("hidden to hidden is nothing", reconciler.reconcile(PerformanceOverlayCardState.Hidden, null, 0))
        assertEquals(
            PerformanceOverlayReconcileChange.ATTACH,
            reconciler.reconcile(on(GameA), GameA, 2),
        )
        repeat(60) { assertNull("a beat that finds the same state", reconciler.reconcile(on(GameA), GameA, 2)) }

        assertEquals(listOf("reconcile foreground=$GameA target=$GameA registered=2 change=attach"), lines)
    }

    @Test
    fun `a switch and a detach each log once, with package names only`() {
        val lines = mutableListOf<String>()
        val reconciler = PerformanceOverlayReconciler(lines::add)

        assertEquals(PerformanceOverlayReconcileChange.ATTACH, reconciler.reconcile(on(GameB), GameB, 2))
        assertEquals(PerformanceOverlayReconcileChange.SWITCH, reconciler.reconcile(on(GameA), GameA, 2))
        assertNull(reconciler.reconcile(on(GameA), GameA, 2))
        assertEquals(
            PerformanceOverlayReconcileChange.DETACH,
            reconciler.reconcile(PerformanceOverlayCardState.Hidden, Chrome, 2),
        )
        assertNull(reconciler.reconcile(PerformanceOverlayCardState.Hidden, Chrome, 2))
        assertEquals(PerformanceOverlayReconcileChange.ATTACH, reconciler.reconcile(on(GameA), GameA, 2))

        assertEquals(
            listOf(
                "reconcile foreground=$GameB target=$GameB registered=2 change=attach",
                "reconcile foreground=$GameA target=$GameA registered=2 change=switch",
                "reconcile foreground=$Chrome target=none registered=2 change=detach",
                "reconcile foreground=$GameA target=$GameA registered=2 change=attach",
            ),
            lines,
        )
    }

    @Test
    fun `a target that changes while the card stays up is a switch`() {
        val lines = mutableListOf<String>()
        val reconciler = PerformanceOverlayReconciler(lines::add)

        reconciler.reconcile(on(null), null, 0)
        assertEquals(PerformanceOverlayReconcileChange.SWITCH, reconciler.reconcile(on(GameA), GameA, 1))
        assertNull(reconciler.reconcile(on(GameA), GameA, 1))
        assertEquals(
            listOf(
                "reconcile foreground=none target=none registered=0 change=attach",
                "reconcile foreground=$GameA target=$GameA registered=1 change=switch",
            ),
            lines,
        )
    }

    // --- the registry -------------------------------------------------------------------------------------

    @Test
    fun `the registry holds at most its cap and drops the least recently used`() {
        val registry = PerformanceOverlayGameRegistry()
        (1..PerformanceOverlayGameRegistryCap + 1).forEach { registry.note("com.example.game$it", "op$it") }

        assertEquals(PerformanceOverlayGameRegistryCap, registry.size)
        assertFalse("the first launched game is the one dropped", registry.contains("com.example.game1"))
        assertTrue(registry.contains("com.example.game2"))
        assertTrue(registry.contains("com.example.game${PerformanceOverlayGameRegistryCap + 1}"))
    }

    @Test
    fun `using a game protects it from eviction`() {
        val registry = PerformanceOverlayGameRegistry(cap = 3)
        registry.note("com.example.one", "op1")
        registry.note("com.example.two", "op2")
        registry.note("com.example.three", "op3")

        registry.touch("com.example.one") // in front just now
        registry.note("com.example.four", "op4")

        assertEquals(setOf("com.example.three", "com.example.one", "com.example.four"), registry.packages())
        assertFalse(registry.contains("com.example.two"))
        registry.touch("com.example.unknown")
        assertEquals("touching a stranger registers nothing", 3, registry.size)
    }

    @Test
    fun `noting a game again is idempotent`() {
        val registry = PerformanceOverlayGameRegistry()
        repeat(5) { registry.note(GameA, "op1") }
        registry.note("  ", "op2")

        assertEquals(setOf(GameA), registry.packages())
    }

    @Test
    fun `a session ending keeps its game, a failure or a newer launch's own forget does not`() {
        val registry = PerformanceOverlayGameRegistry()

        registry.observeSession("op1", GameA, MimirSessionPhase.PREPARING)
        assertEquals("a launch that has not dispatched registers nothing", 0, registry.size)
        registry.observeSession("op1", null, MimirSessionPhase.DISPATCHED)
        assertEquals("a session with no emulator yet registers nothing", 0, registry.size)

        registry.observeSession("op0", GameB, MimirSessionPhase.ENDED)
        assertFalse("ENDED alone never registers a launch", registry.contains(GameB))

        registry.observeSession("op1", GameA, MimirSessionPhase.DISPATCHED)
        registry.observeSession("op1", GameA, MimirSessionPhase.RETURNING)
        registry.observeSession("op1", GameA, MimirSessionPhase.ENDED)
        assertTrue("ENDED is the player returning to Mimir; the game is still running", registry.contains(GameA))
        registry.observeSession("op2", GameA, MimirSessionPhase.RECOVERING)
        assertTrue(registry.contains(GameA))

        // A late failure of an OLD launch must not forget a newer launch of the same emulator.
        registry.observeSession("op3", GameA, MimirSessionPhase.DISPATCHED)
        registry.observeSession("op1", GameA, MimirSessionPhase.FAILED)
        assertTrue(registry.contains(GameA))
        assertFalse(registry.forget(GameA, "op1"))

        registry.observeSession("op3", GameA, MimirSessionPhase.FAILED)
        assertFalse("the launch that owns the entry failed", registry.contains(GameA))
        registry.observeSession("op4", GameB, MimirSessionPhase.DISPATCHED)
        registry.observeSession("op4", GameB, MimirSessionPhase.STALE)
        assertFalse(registry.contains(GameB))
    }

    @Test
    fun `a removal persists across every later publish of the same ledger snapshot`() {
        val ledger = Ledger()
        val a = ledger.launch("alpha", GameA)
        ledger.returnToMimir(a)

        // Its tab is closed: A is forgotten, then the ledger republishes the unchanged ENDED snapshot for
        // unrelated reasons (an app session starting, a refused launch) and it is observed again and again.
        assertTrue(forgetLaunchedGameOnTabClose(GameA, expectedOperation = null, registry = ledger.registry))
        val ended = ledger.coordinator.state.value.gameSession
        repeat(5) { MimirLaunchedGames.observe(ended, into = ledger.registry) }
        assertFalse("a republished ENDED snapshot must not bring A back", GameA in ledger.registry.packages())

        // The same operation in any live phase cannot register again either.
        ledger.registry.observeSession(a.value, GameA, MimirSessionPhase.DISPATCHED)
        ledger.registry.observeSession(a.value, GameA, MimirSessionPhase.YIELDED_TO_SECONDARY_TASK)
        ledger.registry.note(GameA, a.value)
        assertFalse(GameA in ledger.registry.packages())

        // Only a NEW launch of the package registers it again.
        ledger.launch("alpha-again", GameA)
        assertTrue(GameA in ledger.registry.packages())
    }

    @Test
    fun `closing A's tab after B replaced A in the ledger still removes A`() {
        val ledger = Ledger()
        val a = ledger.launch("alpha", GameA)
        ledger.returnToMimir(a)
        ledger.launch("beta", GameB)
        assertEquals("the ledger's live game is B, A has no live ledger game", GameB, ledger.dispatched())
        assertEquals(setOf(GameA, GameB), ledger.registry.packages())

        // The ledger-side end finds no live game for A and returns early; the registry must not wait on it.
        assertNull(ledger.coordinator.endGameForTabClose(GameA))
        assertTrue(forgetLaunchedGameOnTabClose(GameA, expectedOperation = null, registry = ledger.registry))

        assertEquals("B, the live game, is untouched", setOf(GameB), ledger.registry.packages())
        assertEquals(PerformanceOverlayCardState.Hidden, state(GameB, foreign(GameA), ledger.registry.packages()))
        assertEquals(on(GameB), state(GameB, foreign(GameB), ledger.registry.packages()))
    }

    @Test
    fun `a close pinned to an earlier launch never removes a newer launch of the same emulator`() {
        val registry = PerformanceOverlayGameRegistry()
        registry.observeSession("op1", GameA, MimirSessionPhase.DISPATCHED)
        registry.observeSession("op2", GameA, MimirSessionPhase.DISPATCHED)

        assertFalse(forgetLaunchedGameOnTabClose(GameA, MimirGameSessionOperation("op1"), registry))
        assertTrue("the newer launch stays", registry.contains(GameA))
        assertTrue(forgetLaunchedGameOnTabClose(GameA, MimirGameSessionOperation("op2"), registry))
        assertFalse(registry.contains(GameA))
        assertFalse("a package nothing is registered for removes nothing", registry.forgetPackage(GameA))
    }

    // --- the sampling floor -------------------------------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `twenty nudges within a second give at most two samples`() = runTest {
        val beat = PerformanceOverlayBeat(nowMs = { testScheduler.currentTime })
        val sampledAt = mutableListOf<Long>()
        backgroundScope.launch {
            while (true) {
                beat.iterationStarted()
                sampledAt += testScheduler.currentTime
                beat.awaitNext()
            }
        }
        testScheduler.runCurrent()
        assertEquals("the loop samples once on entry", listOf(0L), sampledAt)

        repeat(20) {
            beat.nudge()
            testScheduler.advanceTimeBy(45)
            testScheduler.runCurrent()
        }
        assertEquals("900 ms in, the nudges have not bought a second sample", 1, sampledAt.size)

        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        assertTrue("at most two samples inside the first second: $sampledAt", sampledAt.size <= 2)
        assertEquals(listOf(0L, 1_000L), sampledAt)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a foreground that flaps for five seconds never samples faster than once a second`() = runTest {
        val beat = PerformanceOverlayBeat(nowMs = { testScheduler.currentTime })
        val sampledAt = mutableListOf<Long>()
        backgroundScope.launch {
            while (true) {
                beat.iterationStarted()
                sampledAt += testScheduler.currentTime
                beat.awaitNext()
            }
        }
        repeat(500) {
            beat.nudge()
            testScheduler.advanceTimeBy(10)
            testScheduler.runCurrent()
        }

        assertTrue("sampled at $sampledAt", sampledAt.size in 5..6)
        sampledAt.zipWithNext().forEach { (earlier, later) ->
            assertTrue("two samples ${later - earlier} ms apart: $sampledAt", later - earlier >= 1_000L)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a quiet foreground keeps the plain one second beat`() = runTest {
        val beat = PerformanceOverlayBeat(nowMs = { testScheduler.currentTime })
        val sampledAt = mutableListOf<Long>()
        backgroundScope.launch {
            while (true) {
                beat.iterationStarted()
                sampledAt += testScheduler.currentTime
                beat.awaitNext()
            }
        }
        testScheduler.advanceTimeBy(3_500)
        testScheduler.runCurrent()

        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L), sampledAt)
    }

    // --- a switch blanks the number -----------------------------------------------------------------------

    @Test
    fun `a switch blanks the frame rate until the new game's first sample lands`() {
        val gameA = PerformanceOverlayPresentation.Active(
            telemetry = telemetry(),
            environment = PerformanceOverlayEnvironment(foregroundFrameRate = 58, deviceCpuPercent = 31),
        )

        val rebound = performanceOverlayReboundPresentation(gameA) as PerformanceOverlayPresentation.Active

        assertEquals("A's 58 must not read as B's", "—", performanceOverlayFpsLabel(rebound.environment))
        assertEquals("58", performanceOverlayFpsLabel(gameA.environment))
        assertEquals("the whole-device cells are not any game's and stay", 31, rebound.environment.deviceCpuPercent)
        assertEquals(gameA.telemetry, rebound.telemetry)
        // B's first sample replaces the blank outright; there is no merge to get wrong.
        val first = PerformanceOverlayPresentation.Active(telemetry(), PerformanceOverlayEnvironment(foregroundFrameRate = 44))
        assertEquals("44", performanceOverlayFpsLabel(first.environment))
        assertEquals(
            PerformanceOverlayPresentation.Warming,
            performanceOverlayReboundPresentation(PerformanceOverlayPresentation.Warming),
        )
        assertEquals(
            PerformanceOverlayPresentation.Paused,
            performanceOverlayReboundPresentation(PerformanceOverlayPresentation.Paused),
        )
    }

    private fun telemetry() = TweakerTelemetry(
        sampledAtElapsedRealtimeMs = 1L,
        foregroundPackageName = null,
        frameStatus = null,
        frameAverageFpsMilli = null,
        cpuTempC = null,
        gpuTempC = null,
        cpuBusyPercent = null,
        gpuBusyPercent = null,
        batteryPowerMw = null,
        batteryStatus = null,
    )

    // --- wiring (the part JUnit cannot run: the Authority and the service) ------------------------------

    @Test
    fun `the session authority feeds the registry and a closed tab forgets its game`() {
        val session = source("MimirSessionCoordinator.kt")
        assertTrue(
            "every ledger publish must reach the registry, or a game launched while the overlay was off is a stranger",
            session.contains("MimirLaunchedGames.observe(snapshot.gameSession)"),
        )
        val tabClose = session.substringAfter("fun endBottomGameForTabClose(").substringBefore("/** A fast host can reach Yielded")
        val forgetAt = tabClose.indexOf("forgetLaunchedGameOnTabClose(packageName, expectedOperation)")
        val ledgerAt = tabClose.indexOf("MimirSessionAuthority.endGameForTabClose(")
        assertTrue("a tab closed or a process proven gone must forget the game", forgetAt >= 0)
        assertTrue(
            "and must do it BEFORE the ledger lookup, whose early return would skip it once A is ENDED or replaced",
            ledgerAt > forgetAt,
        )
    }

    @Test
    fun `the service wants the card from the reconciled state and logs only changes`() {
        val service = source("PerformanceOverlayService.kt")
        val wanted = service.substringAfter("private fun performanceOverlayCardWanted(): Boolean {")
            .substringBefore("private suspend fun awaitNextBeat()")

        assertTrue(wanted.contains("performanceOverlayCardState("))
        assertTrue(wanted.contains("registeredGames = registry.packages()"))
        assertTrue(wanted.contains("cardReconciler.reconcile("))
        assertTrue(wanted.contains("return desired.wanted"))
        assertFalse(
            "the ledger's one game is no longer the whole question",
            wanted.contains("effectiveDispatchedGame"),
        )
        assertEquals(
            "the beat's cadence is unchanged: one second, cut short only by a foreground change",
            4,
            Regex("awaitNextBeat\\(\\)").findAll(service.substringAfter("private suspend fun sampleSystemTelemetryWithObservation(")).count(),
        )
        assertTrue("the wait goes through the paced beat", service.contains("beat.awaitNext()"))
        assertTrue(
            "every iteration is stamped so the floor can be measured from its start",
            service.substringAfter("private suspend fun sampleSystemTelemetryWithObservation(")
                .contains("beat.iterationStarted()"),
        )
        assertTrue("a switch blanks the number as well as the trace", wanted.contains("presenter.rebind()"))
    }

    private fun source(name: String): String {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/$name")
        assertTrue("source not found at ${file.absolutePath}", file.exists())
        return file.readText()
    }

    /** The real one-slot session ledger, feeding a registry of its own exactly as the authority feeds the process-wide one. */
    private class Ledger {
        val registry = PerformanceOverlayGameRegistry()
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

        fun dispatched(): String? = performanceOverlayDispatchedGamePackageName(coordinator.state.value.gameSession)

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
        const val Chrome = "com.android.chrome"
    }
}

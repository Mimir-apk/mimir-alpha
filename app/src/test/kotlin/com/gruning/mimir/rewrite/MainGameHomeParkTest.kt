package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardResume
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import com.gruning.mimir.rewrite.feature.library.ParkedLeaseReleaseOutcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import android.os.SystemClock
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * HOME parking, on the pieces that run without a device: the HOME decision, the park,
 * the exit watch, the Resume press, and the one-game rule's question. What needs the vendor gateway and a live emulator task
 * (the task lookup, the focus, the Bottom's NOW PLAYING staying up) is the device's to prove.
 */
class MainGameHomeParkTest {
    /** What the lease told the launch card, in order. The real publisher writes Compose state, which a JVM test must not. */
    private val published = mutableListOf<GameId?>()

    @Before
    fun cleanProcess() {
        MainGameParkingState.homeParkedPublisher = { published += it }
        MainGameParkingState.current()?.let(MainGameParkingState::clear)
        UserLeaveSignal.reset()
        published.clear()
    }

    @After
    fun restoreProcess() {
        MainGameParkingState.current()?.let(MainGameParkingState::clear)
        MainGameParkingState.homeParkedPublisher = HomeLaunchCardResume::set
        UserLeaveSignal.reset()
        UserLeaveSignal.clock = SystemClock::elapsedRealtime
    }

    private fun session(
        operation: String = "game:psx",
        gameId: String = "xenogears",
        title: String = "Xenogears",
        phase: MimirSessionPhase = MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
        dualScreen: Boolean = false,
        placement: MimirGamePlacement = MimirGamePlacement.MAIN,
        dispatchedAt: Long? = null,
    ): MimirGameSession = MimirGameSession(
        operation = MimirGameSessionOperation(operation),
        sequence = 1L,
        gameId = GameId(gameId),
        title = title,
        platformId = PlatformId.PS1,
        returnAnchor = MimirGameReturnAnchor.Home(
            HomeLaunchReturnTarget(HomeShelfItemId.Emulated(GameId(gameId)), rank = 0L),
        ),
        startedAtEpochMs = 1L,
        targetPackageName = "com.github.stenzek.duckstation",
        placement = placement,
        dualScreen = dualScreen,
        phase = phase,
        dispatchedAtElapsedMs = dispatchedAt,
    )

    private fun homeLease(
        operation: String = "game:psx",
        gameId: String = "xenogears",
        taskId: Int = 4170,
        homeParked: Boolean = true,
    ) = MainGameParkingLease(
        operation = MimirGameSessionOperation(operation),
        gameId = GameId(gameId),
        targetPackageName = "com.github.stenzek.duckstation",
        taskId = taskId,
        parkedAtElapsedMillis = 1L,
        homeParked = homeParked,
    )

    // ---- the cause: what the ledger does with a HOME resume ---------------------------------------------------------------

    @Test
    fun `without the withhold a HOME resume hands the game to the return, which is what ended the session`() {
        val operation = MimirGameSessionOperation("game:psx")
        val ledger = PrimaryStopResumeLedger(isCurrentParkedGame = { false })
        ledger.noteStopped(false, PrimaryStopResumeTicket(gameOperation = operation))

        val observation = ledger.observeResume()

        assertEquals(operation, observation.returnTicket?.gameOperation)
        assertNull(observation.withheldGameOperation)
    }

    @Test
    fun `with the HOME park's withhold the same resume does not return the game`() {
        val operation = MimirGameSessionOperation("game:psx")
        val ledger = PrimaryStopResumeLedger(isCurrentParkedGame = { false })
        ledger.noteStopped(false, PrimaryStopResumeTicket(gameOperation = operation))
        ledger.noteMButtonCommandDelivered()

        val observation = ledger.observeResume()

        assertNull(observation.returnTicket)
        assertEquals(operation, observation.withheldGameOperation)
    }

    @Test
    fun `once the lease is armed the ledger withholds the return on its own`() {
        val operation = MimirGameSessionOperation("game:psx")
        val ledger = PrimaryStopResumeLedger(isCurrentParkedGame = { it == operation })
        ledger.noteStopped(false, PrimaryStopResumeTicket(gameOperation = operation))

        assertNull(ledger.observeResume().returnTicket)
    }

    // ---- the decision -----------------------------------------------------------------------------------------------------

    @Test
    fun `a HOME press over a running single-screen game parks it`() {
        val verdict = decideHomePark(
            decision = MainHomeKeyDecision.NOT_IN_FRONT,
            source = MainHomeKeySource.MAIN,
            game = session(),
            inFlight = false,
            parked = false,
            privilegeReady = { true },
        )
        assertEquals(HomeParkVerdict.PARK, verdict)
    }

    @Test
    fun `a HOME press over a running dual-screen game parks it too`() {
        listOf(MimirSessionPhase.FOREGROUND_PROVEN, MimirSessionPhase.YIELDED_TO_SECONDARY_TASK).forEach { phase ->
            val verdict = decideHomePark(
                decision = MainHomeKeyDecision.NOT_IN_FRONT,
                source = MainHomeKeySource.MAIN,
                game = session(dualScreen = true, phase = phase),
                inFlight = false,
                parked = false,
                privilegeReady = { true },
            )
            assertEquals(phase.name, HomeParkVerdict.PARK, verdict)
        }
    }

    @Test
    fun `every other press, window, game and state leaves the session to the return as before`() {
        val neverRead: () -> Boolean = { error("privilege must not be read for a press that cannot park") }
        fun verdict(
            decision: MainHomeKeyDecision = MainHomeKeyDecision.NOT_IN_FRONT,
            source: MainHomeKeySource = MainHomeKeySource.MAIN,
            game: MimirGameSession? = session(),
            inFlight: Boolean = false,
            parked: Boolean = false,
        ) = decideHomePark(decision, source, game, inFlight, parked, neverRead)

        MainHomeKeyDecision.values().filter { it != MainHomeKeyDecision.NOT_IN_FRONT }.forEach { decision ->
            assertEquals(decision.token, HomeParkVerdict.NOT_COVERED, verdict(decision = decision))
        }
        assertEquals(HomeParkVerdict.NOT_MAIN_WINDOW, verdict(source = MainHomeKeySource.FLOOR))
        assertEquals(HomeParkVerdict.NO_GAME, verdict(game = null))
        assertEquals(HomeParkVerdict.NOT_ON_MAIN, verdict(game = session(placement = MimirGamePlacement.BOTTOM)))
        assertEquals(
            "#227: a dual-screen game on Bottom is still not Main's",
            HomeParkVerdict.NOT_ON_MAIN,
            verdict(game = session(dualScreen = true, placement = MimirGamePlacement.BOTTOM)),
        )
        listOf(
            MimirSessionPhase.PREPARING,
            MimirSessionPhase.DISPATCHED,
            MimirSessionPhase.RETURNING,
            MimirSessionPhase.RECOVERING,
            MimirSessionPhase.ENDED,
            MimirSessionPhase.FAILED,
            MimirSessionPhase.STALE,
        ).forEach { phase ->
            assertEquals(phase.name, HomeParkVerdict.NOT_SETTLED, verdict(game = session(phase = phase)))
        }
        assertEquals(HomeParkVerdict.PENDING, verdict(inFlight = true))
        assertEquals(HomeParkVerdict.ALREADY_PARKED, verdict(parked = true))
    }

    @Test
    fun `without a ready vendor gateway nothing parks, because no exit could ever be proven`() {
        val verdict = decideHomePark(
            decision = MainHomeKeyDecision.NOT_IN_FRONT,
            source = MainHomeKeySource.MAIN,
            game = session(),
            inFlight = false,
            parked = false,
            privilegeReady = { false },
        )
        assertEquals(HomeParkVerdict.NO_PRIVILEGE, verdict)
    }

    // ---- the park ---------------------------------------------------------------------------------------------------------

    private class ParkBench(var game: MimirGameSession?) {
        var ready = true
        var parked = false
        var taskId: Int? = 4170
        var taskGone = false
        var lookups = 0
        var armRefused = false
        var withheld = 0
        var readinessReads = 0
        var now = 7_000L
        var finalizeThrows = false
        val finalized = mutableListOf<Pair<MimirGameSession, Long>>()
        var totalBefore: Long? = null
        val order = mutableListOf<String>()
        val segments = HomeParkSegmentLedger()
        val scheduled = mutableListOf<suspend () -> Unit>()
        val ended = mutableListOf<MimirGameSession>()
        val watched = mutableListOf<MainGameParkingLease>()
        val armed = mutableListOf<MainGameParkingLease>()
        val logs = mutableListOf<String>()
        val reclaimed = mutableListOf<MimirGameSession>()
        var reclaimThrows = false

        // #261: the kept session's retry. `pauseHook` runs at each 5 s tick with its 1-based number, before the ask it precedes.
        val retries = mutableListOf<suspend () -> Unit>()
        val pauseMs = mutableListOf<Long>()
        var pauseHook: (Int) -> Unit = {}
        var mimirInFront = true
        var appLaunched = false
        var binding = false
        var armThrows = false

        /** Runs inside the task lookup with its 1-based number, before the answer is chosen: flip the world while the ask is in flight, or throw to make it fail. */
        var resolveHook: (Int) -> Unit = {}

        /** Runs inside the gateway status read: flip the world while it is in flight, or throw to make it fail. */
        var statusHook: () -> Unit = {}

        val parker = MainGameHomeParker(
            HomeParkPorts(
                currentGame = { game },
                isParked = { parked },
                privilegeReady = {
                    readinessReads++
                    ready
                },
                withholdReturn = { withheld++ },
                resolveTaskOutcome = {
                    lookups++
                    resolveHook(lookups)
                    yield() // the production lookup suspends: a coroutine cancelled while it is in flight stops here, with whatever answer it holds
                    when {
                        taskId != null -> VendorTaskOutcome.Found(taskId!!)
                        taskGone -> VendorTaskOutcome.NoTask
                        else -> VendorTaskOutcome.Unavailable("unanswered")
                    }
                },
                arm = { g, t ->
                    if (armThrows) error("arm failed")
                    if (armRefused) {
                        null
                    } else {
                        MainGameParkingLease(
                            operation = g.operation,
                            gameId = g.gameId,
                            targetPackageName = g.targetPackageName.orEmpty(),
                            taskId = t,
                            parkedAtElapsedMillis = 1L,
                            homeParked = true,
                            dualScreen = g.dualScreen,
                        ).also { armed += it }
                    }
                },
                endSession = { ended += it },
                startWatch = { watched += it },
                reclaimBottom = { g ->
                    order += "reclaim"
                    reclaimed += g
                    if (reclaimThrows) error("no Bottom")
                },
                finalizeElapsed = { g, at ->
                    order += "finalize"
                    if (finalizeThrows) error("store unavailable")
                    finalized += g to at
                },
                elapsedNow = { now },
                totalBefore = {
                    order += "total"
                    totalBefore
                },
                segments = segments,
                schedule = { scheduled += it },
                log = { logs += it },
                mimirResumed = { mimirInFront },
                pause = { ms ->
                    pauseMs += ms
                    // A retry that ignores its cap fails here, loudly, instead of hanging the run.
                    if (pauseMs.size > 1_000) throw AssertionError("the retry never stopped: ${pauseMs.size} ticks")
                    pauseHook(pauseMs.size)
                    yield()
                },
                appLaunchedSince = { appLaunched },
                scheduleRetry = { retries += it },
                gatewayStatus = {
                    statusHook()
                    when {
                        binding -> PrivilegedUtilityStatus.BINDING
                        ready -> PrivilegedUtilityStatus.READY
                        else -> PrivilegedUtilityStatus.CONNECTION_FAILED
                    }
                },
            ),
        )

        fun runScheduled() = runBlocking { scheduled.single().invoke() }

        /** Presses HOME and runs the park that keeps the session (the lookup unanswered), so the retry is waiting to be run. */
        fun keepSession(): ParkBench {
            parker.onHomeKey(MainHomeKeyDecision.NOT_IN_FRONT, MainHomeKeySource.MAIN)
            runScheduled()
            return this
        }

        fun runRetry(index: Int = 0) = runBlocking { retries[index].invoke() }
    }

    @Test
    fun `HOME parks the game - the return is withheld at once and the lease is armed once the task is resolved`() {
        val bench = ParkBench(session())

        val verdict = bench.parker.onHomeKey(MainHomeKeyDecision.NOT_IN_FRONT, MainHomeKeySource.MAIN)

        assertEquals(HomeParkVerdict.PARK, verdict)
        assertEquals("the withhold is set before the resume this press causes", 1, bench.withheld)
        assertTrue("MainActivity.onResume reads this as parked from its first frame", bench.parker.pending())
        assertTrue("nothing waits for the vendor on the main thread", bench.armed.isEmpty())

        bench.runScheduled()

        val lease = bench.armed.single()
        assertEquals(4170, lease.taskId)
        assertTrue(lease.homeParked)
        assertEquals(listOf(lease), bench.watched)
        assertTrue("the session is kept", bench.ended.isEmpty())
        assertFalse(bench.parker.pending())
    }

    @Test
    fun `a press without a ready gateway does not withhold, schedule or stay pending`() {
        val bench = ParkBench(session()).also { it.ready = false }

        assertEquals(HomeParkVerdict.NO_PRIVILEGE, bench.parker.onHomeKey(MainHomeKeyDecision.NOT_IN_FRONT, MainHomeKeySource.MAIN))

        assertEquals(0, bench.withheld)
        assertTrue(bench.scheduled.isEmpty())
        assertFalse(bench.parker.pending())
    }

    @Test
    fun `a task that cannot be resolved ends the session, which is what HOME did before`() {
        val bench = ParkBench(session()).also { it.taskId = null }
        bench.parker.onHomeKey(MainHomeKeyDecision.NOT_IN_FRONT, MainHomeKeySource.MAIN)

        bench.runScheduled()

        assertEquals(listOf(bench.game), bench.ended)
        assertTrue(bench.watched.isEmpty())
        assertFalse(bench.parker.pending())
    }

    @Test
    fun `a lease the authority refuses ends the session too, and never leaves the park pending`() {
        val bench = ParkBench(session()).also { it.armRefused = true }
        bench.parker.onHomeKey(MainHomeKeyDecision.NOT_IN_FRONT, MainHomeKeySource.MAIN)

        bench.runScheduled()

        assertEquals(1, bench.ended.size)
        assertTrue(bench.watched.isEmpty())
        assertFalse(bench.parker.pending())
    }

    @Test
    fun `a second HOME press while the task is being resolved is one park, not two`() {
        val bench = ParkBench(session())
        bench.parker.onHomeKey(MainHomeKeyDecision.NOT_IN_FRONT, MainHomeKeySource.MAIN)

        val second = bench.parker.onHomeKey(MainHomeKeyDecision.NOT_IN_FRONT, MainHomeKeySource.MAIN)

        assertEquals(HomeParkVerdict.PENDING, second)
        assertEquals(1, bench.scheduled.size)
        assertEquals(1, bench.withheld)
    }

    @Test
    fun `a press that finds the game already parked starts nothing`() {
        val bench = ParkBench(session()).also { it.parked = true }

        assertEquals(HomeParkVerdict.ALREADY_PARKED, bench.parker.onHomeKey(MainHomeKeyDecision.NOT_IN_FRONT, MainHomeKeySource.MAIN))

        assertEquals(0, bench.withheld)
        assertTrue(bench.scheduled.isEmpty())
    }

    @Test
    fun `a press that is not a covered-HOME press is not even logged`() {
        val bench = ParkBench(session())

        bench.parker.onHomeKey(MainHomeKeyDecision.RETURN_HOME, MainHomeKeySource.MAIN)

        assertTrue(bench.logs.isEmpty())
        assertEquals(0, bench.withheld)
    }

    // ---- the lease --------------------------------------------------------------------------------------------------------

    @Test
    fun `the HOME lease matches a single-screen game, and the dual-screen arm still refuses one`() {
        val game = session()

        assertEquals(MainGameParkingArmResult.NOT_CURRENT_DUAL_SCREEN_GAME, MainGameParkingState.arm(game, 4170))
        assertNull(MainGameParkingState.current())

        assertEquals(MainGameParkingArmResult.ARMED, MainGameParkingState.armForHome(game, 4170))
        val lease = requireNotNull(MainGameParkingState.currentFor(game))
        assertTrue(lease.homeParked)
        assertEquals(4170, lease.taskId)
        assertEquals(MainGameParkingArmResult.ALREADY_ARMED, MainGameParkingState.armForHome(game, 4170))
        assertEquals(MainGameParkingArmResult.INVALID_TASK, MainGameParkingState.armForHome(game, 0))
        assertNull(MainGameParkingState.currentFor(game.copy(gameId = GameId("other"))))
    }

    @Test
    fun `a HOME lease is cleared when Main stops for the resumed game, like a dual-screen return`() {
        val game = session()
        MainGameParkingState.armForHome(game, 4170)
        val lease = requireNotNull(MainGameParkingState.current())

        assertTrue(MainGameParkingState.beginRestoring(lease))
        assertTrue(MainGameParkingState.clearRestoringOnPrimaryStop(lease.operation))

        assertNull(MainGameParkingState.current())
    }

    // ---- the exit watch ---------------------------------------------------------------------------------------------------

    @Test
    fun `an emulator that exits behind Home ends the session and clears the entry`() {
        MainGameParkingState.armForHome(session(), 4170)
        val lease = requireNotNull(MainGameParkingState.current())
        val answers = listOf(false, false, true)
        var asked = 0
        var sessionEnded = false
        val pauses = mutableListOf<Long>()
        val watch = ParkedGameExitWatch(
            currentLease = MainGameParkingState::current,
            mimirResumed = { true },
            releaseIfGone = { current ->
                val gone = answers[asked++]
                if (gone) {
                    MainGameParkingState.clear(current)
                    sessionEnded = true
                }
                gone
            },
            pause = { pauses += it },
        )

        runBlocking { watch.run(lease) }

        assertEquals(3, asked)
        assertTrue(sessionEnded)
        assertNull("the entry is gone with the session", MainGameParkingState.current())
        assertEquals(listOf(HomeParkWatchPollMs, HomeParkWatchPollMs, HomeParkWatchPollMs), pauses)
    }

    @Test
    fun `nothing is probed while the player is in the game`() {
        val lease = homeLease()
        var polls = 0
        var probes = 0
        val watch = ParkedGameExitWatch(
            currentLease = { lease },
            mimirResumed = { polls++ >= 2 },
            releaseIfGone = {
                probes++
                true
            },
            pause = {},
        )

        runBlocking { watch.run(lease) }

        assertEquals(3, polls)
        assertEquals(1, probes)
    }

    @Test
    fun `the watch stops when the lease was cleared or replaced elsewhere`() {
        val lease = homeLease()
        var probes = 0
        val probe: suspend (MainGameParkingLease) -> Boolean = {
            probes++
            false
        }

        runBlocking { ParkedGameExitWatch(currentLease = { null }, mimirResumed = { true }, releaseIfGone = probe, pause = {}).run(lease) }
        runBlocking {
            ParkedGameExitWatch(currentLease = { lease.copy(taskId = 4171) }, mimirResumed = { true }, releaseIfGone = probe, pause = {}).run(lease)
        }

        assertEquals(0, probes)
    }

    // ---- the watch ends a session only on proof ----------------------------------------------------------------------------

    /**
     * Runs the watch over [answers] (one per tick) with the production rule, [releaseHomeParkedOnProof]. Returns how many ticks
     * probed and what the lease was released with. The loop ends when a release happens, or once the script is used up (read as the
     * lease being cleared elsewhere, as a Resume does).
     */
    private fun watchOnProof(
        answers: List<suspend () -> VendorTaskOutcome>,
        gatewayStatus: (probe: Int) -> PrivilegedUtilityStatus = { PrivilegedUtilityStatus.READY },
    ): Pair<Int, List<String>> {
        val lease = homeLease()
        var probes = 0
        var cleared = false
        val ended = mutableListOf<String>()
        val watch = ParkedGameExitWatch(
            currentLease = { lease.takeUnless { cleared } },
            mimirResumed = { true },
            releaseIfGone = { current ->
                val probe = probes++
                val gone = releaseHomeParkedOnProof(
                    lease = current,
                    resolve = answers[probe],
                    gatewayStatus = { gatewayStatus(probe) },
                    release = { _, endedBy ->
                        ended += endedBy
                        true
                    },
                    log = {},
                )
                if (probes == answers.size) cleared = true
                gone
            },
            pause = {},
        )
        runBlocking { watch.run(lease) }
        return probes to ended
    }

    @Test
    fun `a transient empty or failed answer keeps the lease, and a later Found keeps it still`() {
        val (probes, ended) = watchOnProof(
            listOf(
                { VendorTaskOutcome.Unavailable("timeout") },
                { VendorTaskOutcome.Unavailable("empty") },
                { error("binder died") },
                { VendorTaskOutcome.Found(4170) },
            ),
        )

        assertEquals("every tick asked again", 4, probes)
        assertTrue("nothing was released on an answer that proved nothing", ended.isEmpty())
    }

    @Test
    fun `a task the gateway says is gone releases the lease on that tick`() {
        val (probes, ended) = watchOnProof(
            listOf({ VendorTaskOutcome.Found(4170) }, { VendorTaskOutcome.Unavailable("empty") }, { VendorTaskOutcome.NoTask }),
        )

        assertEquals(3, probes)
        assertEquals(listOf("parked task gone"), ended)
    }

    @Test
    fun `a gateway that is not READY releases the lease, however the probe failed`() {
        val (probes, ended) = watchOnProof(
            listOf({ VendorTaskOutcome.Found(4170) }, { VendorTaskOutcome.Unavailable("unavailable") }),
            gatewayStatus = { probe -> if (probe < 1) PrivilegedUtilityStatus.READY else PrivilegedUtilityStatus.SERVICE_STOPPED },
        )
        assertEquals(2, probes)
        assertEquals(listOf("parked gateway down"), ended)

        val (thrown, endedOnThrow) = watchOnProof(listOf({ error("binder died") }), gatewayStatus = { PrivilegedUtilityStatus.CONNECTION_FAILED })
        assertEquals(1, thrown)
        assertEquals(listOf("parked gateway down"), endedOnThrow)
    }

    @Test
    fun `#261 a gateway that is still BINDING is reconnecting, which the watch does not take for proof, and every other state that is not READY still is`() {
        val unanswered = listOf<suspend () -> VendorTaskOutcome>(
            { VendorTaskOutcome.Unavailable("unavailable") },
            { VendorTaskOutcome.Unavailable("unavailable") },
            { VendorTaskOutcome.Unavailable("unavailable") },
        )
        val (probes, ended) = watchOnProof(unanswered, gatewayStatus = { PrivilegedUtilityStatus.BINDING })
        assertEquals("every tick asked again behind a binding gateway", 3, probes)
        assertTrue("a gateway that is binding is not down: nothing was released", ended.isEmpty())

        // The same tick that finds the bridge bound keeps the lease too, and a task that is gone still ends it behind a binding gateway.
        val (_, bound) = watchOnProof(unanswered.take(1), gatewayStatus = { PrivilegedUtilityStatus.READY })
        assertTrue(bound.isEmpty())
        val (_, gone) = watchOnProof(listOf({ VendorTaskOutcome.NoTask }), gatewayStatus = { PrivilegedUtilityStatus.BINDING })
        assertEquals(listOf("parked task gone"), gone)

        PrivilegedUtilityStatus.values().filter { it != PrivilegedUtilityStatus.READY && it != PrivilegedUtilityStatus.BINDING }.forEach { status ->
            val (_, down) = watchOnProof(unanswered.take(1), gatewayStatus = { status })
            assertEquals("$status is a gateway that is not coming back by itself", listOf("parked gateway down"), down)
        }
        assertFalse(gatewayProvenDown(PrivilegedUtilityStatus.READY))
        assertFalse(gatewayProvenDown(PrivilegedUtilityStatus.BINDING))
        assertTrue(gatewayProvenDown(PrivilegedUtilityStatus.SERVICE_STOPPED))
    }

    @Test
    fun `a gateway that cannot even say whether it is READY is not proof of a live one`() {
        val ended = mutableListOf<String>()
        val released = runBlocking {
            releaseHomeParkedOnProof(
                lease = homeLease(),
                resolve = { VendorTaskOutcome.Unavailable("timeout") },
                gatewayStatus = { error("posture threw") },
                release = { _, endedBy ->
                    ended += endedBy
                    true
                },
                log = {},
            )
        }
        assertTrue(released)
        assertEquals(listOf("parked gateway down"), ended)
    }

    @Test
    fun `only the watch uses the proof rule - the press paths keep releasing on any answer but a found task`() {
        val source = sourceFile("MainGameHomePark.kt")
        assertTrue(
            source.contains("releaseIfGone = { current -> releaseHomeParkedOnProofIfGone(context, current, stage = " + "\"home_watch\") },"),
        )
        assertTrue(source.contains("releaseHomeParkedWhenNotFound("))
        assertTrue(source.contains("releaseHomeParkedGameIfGone(context, lease, stage)"))
    }

    // ---- Resume -----------------------------------------------------------------------------------------------------------

    private fun resumePorts(
        focus: PrivilegedTaskFocus,
        focused: MutableList<Int> = mutableListOf(),
        fronted: MutableList<MainGameParkingLease> = mutableListOf(),
        released: MutableList<MainGameParkingLease> = mutableListOf(),
        endGame: suspend (MainGameParkingLease) -> Boolean = { error("a resume never ends the game") },
    ) = HomeResumePorts(
        beginRestoring = MainGameParkingState::beginRestoring,
        cancelRestoring = MainGameParkingState::cancelRestoring,
        focusTask = {
            focused += it
            focus
        },
        afterFronted = { fronted += it },
        releaseLease = {
            released += it
            MainGameParkingState.clear(it)
        },
        endGame = endGame,
    )

    @Test
    fun `Resume fronts the parked task and marks the lease so Main's next stop clears it`() {
        MainGameParkingState.armForHome(session(), 4170)
        val lease = requireNotNull(MainGameParkingState.current())
        val focused = mutableListOf<Int>()

        val outcome = runBlocking { resumeHomeParkedGame(lease, resumePorts(PrivilegedTaskFocus.FOCUSED, focused)) }

        assertEquals(HomeResumeOutcome.FRONTED, outcome)
        assertEquals(listOf(4170), focused)
        assertTrue(MainGameParkingState.isRestoring(lease))
        assertTrue(MainGameParkingState.clearRestoringOnPrimaryStop(lease.operation))
        assertNull(MainGameParkingState.current())
    }

    @Test
    fun `a refused focus gives the mark back and keeps the lease for another try`() {
        MainGameParkingState.armForHome(session(), 4170)
        val lease = requireNotNull(MainGameParkingState.current())

        val outcome = runBlocking { resumeHomeParkedGame(lease, resumePorts(PrivilegedTaskFocus.FAILED)) }

        assertEquals(HomeResumeOutcome.FAILED, outcome)
        assertFalse(MainGameParkingState.isRestoring(lease))
        assertEquals(lease, MainGameParkingState.current())
    }

    @Test
    fun `a lease that was replaced meanwhile is not focused`() {
        val focused = mutableListOf<Int>()
        val staleLease = homeLease(taskId = 4170)

        val outcome = runBlocking { resumeHomeParkedGame(staleLease, resumePorts(PrivilegedTaskFocus.FOCUSED, focused)) }

        assertEquals(HomeResumeOutcome.FAILED, outcome)
        assertTrue(focused.isEmpty())
    }

    // ---- Play on a parked game, and on another one ------------------------------------------------------------------------

    private class PressBench(val lease: MainGameParkingLease?) {
        var clock = 0L
        var focusResult = PrivilegedTaskFocus.FOCUSED
        var endResult = true
        var ended = 0
        val focused = mutableListOf<Int>()
        val messages = mutableListOf<Pair<MimirMessageClass, String>>()
        val confirm = EndParkedGameConfirm(windowMs = HomeParkedEndConfirmWindowMs, now = { clock })
        val released = mutableListOf<MainGameParkingLease>()
        val ports = HomeResumePorts(
            beginRestoring = { true },
            cancelRestoring = { true },
            focusTask = {
                focused += it
                focusResult
            },
            afterFronted = {},
            releaseLease = { released += it },
            endGame = {
                ended++
                endResult
            },
        )

        fun press(gameId: String, title: String, restart: Boolean = false): HomeParkedPress = runBlocking {
            handleHomeParkedPress(
                lease = lease,
                pressedGame = GameId(gameId),
                pressedTitle = title,
                ports = ports,
                confirm = confirm,
                titleOf = { "Xenogears" },
                emitMessage = { messageClass, text -> messages += messageClass to text },
                restart = restart,
            )
        }
    }

    // ---- #227: the card's Restart ends the parked session through the Quit path, then the launch goes through ----------------

    @Test
    fun `Restart on the parked game ends it and admits the launch in the same press - no question, no resume`() {
        val bench = PressBench(homeLease())

        val press = bench.press("xenogears", "Xenogears", restart = true)

        assertEquals(HomeParkedPress.ENDED_PARKED_GAME, press)
        assertEquals("the confirmed Quit ran once", 1, bench.ended)
        assertTrue("the task is not fronted first", bench.focused.isEmpty())
        assertTrue("and nothing is asked or said", bench.messages.isEmpty())
    }

    @Test
    fun `a Restart whose Quit does not finish starts nothing and says so`() {
        val bench = PressBench(homeLease()).also { it.endResult = false }

        val press = bench.press("xenogears", "Xenogears", restart = true)

        assertEquals(HomeParkedPress.ANSWERED, press)
        assertEquals(1, bench.ended)
        assertEquals(
            listOf(MimirMessageClass.Warning to "Couldn’t end Xenogears. Quit it first."),
            bench.messages,
        )
    }

    @Test
    fun `a Restart of a dual-screen game ends it through the same Quit`() {
        val bench = PressBench(homeLease().copy(dualScreen = true))

        assertEquals(HomeParkedPress.ENDED_PARKED_GAME, bench.press("xenogears", "Xenogears", restart = true))
        assertEquals(1, bench.ended)
    }

    @Test
    fun `a Restart filed for one game never ends another, and Play without it still resumes`() {
        val other = PressBench(homeLease())
        // Another game's press is the one-game question whatever else was filed; the request is for the parked game only.
        assertEquals(HomeParkedPress.ANSWERED, other.press("crash", "Crash Bandicoot", restart = true))
        assertEquals(0, other.ended)
        assertEquals(1, other.messages.size)

        val plain = PressBench(homeLease())
        assertEquals(HomeParkedPress.ANSWERED, plain.press("xenogears", "Xenogears"))
        assertEquals(listOf(4170), plain.focused)
        assertEquals(0, plain.ended)
    }

    @Test
    fun `every Play path takes the card's request at its entry, and only the Home paths pass it on`() {
        val park = sourceFile("MainGameHomePark.kt")
        // The press itself no longer reads the request: it is handed the verdict its site took.
        assertTrue(park.contains("    restart: Boolean = false,\n): HomeParkedPress = handleHomeParkedPress("))
        assertTrue(park.contains("    restart = restart,\n)"))
        // The Library's Play takes it too, at its refused-Play answer (the one place its press reaches), and hands the verdict to the same press.
        assertEquals(1, park.split("consumeRestart(").size - 1)
        val libraryAnswer = park.substringAfter("internal suspend fun libraryParkedRefusalOutcome(").substringBefore("internal suspend fun libraryParkedPress(")
        assertTrue(libraryAnswer.contains("val restart = HomeLaunchCardResume.consumeRestart(game.gameId)"))
        assertTrue(libraryAnswer.contains("        restart = restart,\n    )"))
        val shelf = sourceFile("HomeShelfCoordinator.kt")
        assertEquals("the shelf row and the launch card, each before it admits anything", 2, shelf.split("HomeLaunchCardResume.consumeRestart(game.gameId)").size - 1)
        assertEquals(2, shelf.split("emitMessage, restart = restartFiled)").size - 1)
        shelf.lines().filter { "consumeRestart(" in it }.forEach { line ->
            val index = shelf.indexOf(line)
            assertTrue("taken before the admission", index < shelf.indexOf("fun admitHomeGame()", index))
        }
    }

    @Test
    fun `Play on the game that is parked brings its task forward - no end, no new launch, no message`() {
        val bench = PressBench(homeLease())

        val press = bench.press("xenogears", "Xenogears")

        assertEquals(HomeParkedPress.ANSWERED, press)
        assertEquals(listOf(4170), bench.focused)
        assertEquals("a resume never reaps", 0, bench.ended)
        assertTrue(bench.messages.isEmpty())
    }

    @Test
    fun `a resume the system refuses says so by name and starts nothing`() {
        val bench = PressBench(homeLease()).also { it.focusResult = PrivilegedTaskFocus.FAILED }

        val press = bench.press("xenogears", "Xenogears")

        assertEquals(HomeParkedPress.ANSWERED, press)
        assertEquals(
            listOf(MimirMessageClass.Warning to "Couldn’t bring Xenogears back. Try again, or quit it first."),
            bench.messages,
        )
        assertEquals(0, bench.ended)
    }

    @Test
    fun `Play on another game names the running one and asks, and ends nothing`() {
        val bench = PressBench(homeLease())

        val press = bench.press("crash", "Crash Bandicoot")

        assertEquals(HomeParkedPress.ANSWERED, press)
        assertEquals(
            listOf(
                MimirMessageClass.Warning to
                    "End Xenogears and start Crash Bandicoot? Press Play again to confirm.",
            ),
            bench.messages,
        )
        assertEquals(0, bench.ended)
        assertTrue("it is not a resume either", bench.focused.isEmpty())
    }

    @Test
    fun `the second press on the same game is the yes - the parked game is ended and the launch goes through`() {
        val bench = PressBench(homeLease())
        bench.press("crash", "Crash Bandicoot")
        bench.clock = 3_000L

        val second = bench.press("crash", "Crash Bandicoot")

        assertEquals(HomeParkedPress.ENDED_PARKED_GAME, second)
        assertEquals(1, bench.ended)
        assertEquals("no second message", 1, bench.messages.size)
    }

    @Test
    fun `the yes expires, and a press on a third game starts its own question`() {
        val bench = PressBench(homeLease())
        bench.press("crash", "Crash Bandicoot")

        bench.clock = HomeParkedEndConfirmWindowMs + 1L
        assertEquals(HomeParkedPress.ANSWERED, bench.press("crash", "Crash Bandicoot"))
        assertEquals("a late second press asks again", 2, bench.messages.size)

        bench.clock += 1_000L
        assertEquals(HomeParkedPress.ANSWERED, bench.press("spyro", "Spyro"))
        assertEquals("another game does not inherit the yes", 3, bench.messages.size)
        assertEquals(0, bench.ended)
    }

    @Test
    fun `an end that does not finish starts nothing and says so`() {
        val bench = PressBench(homeLease()).also { it.endResult = false }
        bench.press("crash", "Crash Bandicoot")
        bench.clock = 1_000L

        val second = bench.press("crash", "Crash Bandicoot")

        assertEquals(HomeParkedPress.ANSWERED, second)
        assertEquals(1, bench.ended)
        assertEquals(
            MimirMessageClass.Warning to "Couldn’t end Xenogears. Quit it first.",
            bench.messages.last(),
        )
    }

    @Test
    fun `a parked task that is gone leaves Play as it always was`() {
        // The press already ran release-at-the-press, which clears the lease of a task that is gone and retries the launch: by
        // the time this handler looks, there is no lease. It must neither speak nor act.
        val gone = PressBench(null)
        assertEquals(HomeParkedPress.NOT_PARKED, gone.press("crash", "Crash Bandicoot"))

        // A dual-screen lease is not this feature's: its refusal sentence stands.
        val dualScreen = PressBench(homeLease(homeParked = false))
        assertEquals(HomeParkedPress.NOT_PARKED, dualScreen.press("xenogears", "Xenogears"))

        listOf(gone, dualScreen).forEach { bench ->
            assertTrue(bench.messages.isEmpty())
            assertTrue(bench.focused.isEmpty())
            assertEquals(0, bench.ended)
        }
    }

    @Test
    fun `the copy names the game, uses the typographic apostrophe, and stays clear of the busy-copy words`() {
        val sentences = listOf(
            homeParkedEndAndStartQuestion("Xenogears", "Crash Bandicoot"),
            homeParkedEndAndStartQuestion(null, "Crash Bandicoot"),
            homeParkedEndFailedMessage("Xenogears"),
            homeParkedEndFailedMessage(null),
            homeParkedStillRunningMessage("Xenogears"),
            homeParkedStillRunningMessage(null),
            homeParkedResumeFailedMessage("Xenogears"),
            homeParkedResumeFailedMessage(null),
        )
        sentences.forEach { sentence ->
            assertFalse(sentence, Regex("\\b(could not|already launching|still open|close it first|launch busy)\\b", RegexOption.IGNORE_CASE).containsMatchIn(sentence))
            assertFalse(sentence, sentence.contains("'"))
        }
        assertEquals("End the running game and start Crash Bandicoot? Press Play again to confirm.", sentences[1])
        assertEquals("Xenogears is still running. Resume it from Home, or quit it first.", sentences[4])
    }

    // ---- a dual-screen game parks, the Bottom comes back to Mimir, and Resume fronts both displays ---------------------

    @Test
    fun `a dual-screen game is parked with a dual-screen lease, and the Bottom is taken back once the lease is armed`() {
        val bench = ParkBench(session(dualScreen = true))

        assertEquals(HomeParkVerdict.PARK, bench.parker.onHomeKey(MainHomeKeyDecision.NOT_IN_FRONT, MainHomeKeySource.MAIN))
        assertTrue("nothing waits for the Bottom on the main thread of the press", bench.reclaimed.isEmpty())

        bench.runScheduled()

        val lease = bench.armed.single()
        assertTrue(lease.homeParked)
        assertTrue(lease.dualScreen)
        assertEquals("the 5 s proof-only watch runs for it too", listOf(lease), bench.watched)
        assertEquals(listOf(bench.game!!), bench.reclaimed)
        assertEquals("the Bottom is taken back after the finished segment is recorded, never before the lease exists", listOf("total", "finalize", "reclaim"), bench.order)
        assertTrue("the session is kept", bench.ended.isEmpty())
    }

    @Test
    fun `a single-screen game never touches the Bottom, and a park that failed takes nothing from it`() {
        val single = ParkBench(session())
        single.parker.onHomeKey(MainHomeKeyDecision.NOT_IN_FRONT, MainHomeKeySource.MAIN)
        single.runScheduled()
        assertTrue(single.reclaimed.isEmpty())

        val failed = ParkBench(session(dualScreen = true)).also { it.taskId = null; it.taskGone = true }
        failed.parker.onHomeKey(MainHomeKeyDecision.NOT_IN_FRONT, MainHomeKeySource.MAIN)
        failed.runScheduled()
        assertTrue(failed.reclaimed.isEmpty())
        assertEquals("a dual-screen game whose task is PROVEN gone cannot be parked and ends its session", 1, failed.ended.size)
    }

    @Test
    fun `#257 a dual-screen game whose task lookup is unanswered keeps its session, and only proof ends it`() {
        val unanswered = ParkBench(session(dualScreen = true)).also { it.taskId = null }
        assertEquals(HomeParkVerdict.PARK, unanswered.parker.onHomeKey(MainHomeKeyDecision.NOT_IN_FRONT, MainHomeKeySource.MAIN))
        unanswered.runScheduled()
        assertTrue("an unanswered lookup is unknown, never proof: the session stays open", unanswered.ended.isEmpty())
        assertTrue("no task was found, so no lease and no watch", unanswered.armed.isEmpty() && unanswered.watched.isEmpty())
        assertTrue(unanswered.reclaimed.isEmpty())
        assertFalse("the park does not stay pending behind it", unanswered.parker.pending())
        assertEquals("one lookup, one answer: the park asks the gateway once", 1, unanswered.lookups)

        // A single-screen game, and one placed on the Bottom, orphan nothing: an unanswered lookup lets go, as HOME did before F3.
        listOf(session(), session(dualScreen = true, placement = MimirGamePlacement.BOTTOM)).forEach { game ->
            val bench = ParkBench(game).also { it.taskId = null }
            runBlocking { bench.parker.complete(game, pressedAtElapsedMs = 7_000L) }
            assertEquals(listOf(game), bench.ended)
        }
    }

    @Test
    fun `#257 a kept session stops its play-time clock at the HOME press, so a long wait before Quit adds nothing`() {
        val minute = 60_000L
        val boot = 7
        val game = session(dualScreen = true)
        val item = game.elapsedItemId()
        val token = game.elapsedToken()
        val bench = ParkBench(game).also { it.taskId = null; it.now = 10 * minute }
        // The clock the store keeps for this session, driven by the same reducers its finish sites use.
        var document = beginHomeExternalSession(HomeExternalSessionElapsedDocument(), item, token, 0L, boot, game.placement)

        bench.parker.onHomeKey(MainHomeKeyDecision.NOT_IN_FRONT, MainHomeKeySource.MAIN)
        bench.runScheduled()
        assertTrue("the session was kept", bench.ended.isEmpty())
        val (finishedGame, finishedAt) = bench.finalized.single()
        assertEquals(game, finishedGame)
        assertEquals("finished at the press, not later", 10 * minute, finishedAt)
        document = finalizeHomeExternalSession(document, token, finishedAt, boot)
        assertEquals(10 * minute, document.elapsedTotals[item])

        // Forty minutes of browsing later the player quits: the finish site the session's end reaches finds the measurement closed.
        document = finalizeHomeExternalSession(document, token, 50 * minute, boot)
        assertEquals("the total stopped growing at the press", 10 * minute, document.elapsedTotals[item])

        // A session that is ended instead (a single-screen game) is not finished here: the end of the session does it.
        val single = ParkBench(session()).also { it.taskId = null }
        single.parker.onHomeKey(MainHomeKeyDecision.NOT_IN_FRONT, MainHomeKeySource.MAIN)
        single.runScheduled()
        assertTrue(single.finalized.isEmpty())
    }

    // ---- the kept session asks again ------------------------------------------------------------------------------------

    private fun keptBench(): ParkBench =
        ParkBench(session(dualScreen = true, dispatchedAt = 1_000L)).also { it.taskId = null; it.totalBefore = 1_234L }

    @Test
    fun `#261 a kept session asks again every 5 s and parks in full the moment its task is found`() {
        val bench = keptBench().keepSession()
        assertEquals("the press kept the session and asked once", 1, bench.lookups)
        assertEquals("one retry is waiting, apart from the park", 1, bench.retries.size)
        assertTrue(bench.armed.isEmpty() && bench.watched.isEmpty() && bench.reclaimed.isEmpty())

        bench.pauseHook = { tick ->
            if (tick == 3) {
                bench.taskId = 4170
                bench.now = 99_000L // the retry runs long after the press: the park must still use the press's own moment
            }
        }
        bench.runRetry()

        assertEquals("every tick is the watch's 5 s", listOf(5_000L, 5_000L, 5_000L), bench.pauseMs)
        assertEquals("the press, then one ask per tick", 4, bench.lookups)
        val lease = bench.armed.single()
        assertEquals(4170, lease.taskId)
        assertTrue(lease.homeParked)
        assertEquals("the exit watch starts", listOf(lease), bench.watched)
        assertEquals("the Bottom is taken back", listOf(bench.game), bench.reclaimed)
        assertTrue("nothing ends the session", bench.ended.isEmpty())
        assertEquals("the clock stopped at the press, never at the retry", listOf(7_000L, 7_000L), bench.finalized.map { it.second })
        assertEquals("the total was read before the first finish, and the park reuses it", listOf("total", "finalize", "finalize", "reclaim"), bench.order)
        val segments = requireNotNull(bench.segments.state.value)
        assertEquals("the segment runs dispatch to the press (1 s to 7 s)", 6_000L, segments.playedMs)
        assertEquals("the total before this session is the one taken at the press", 1_234L, segments.totalBeforeMs)
        assertNull("the clock holds still while parked", segments.segmentStartedAtElapsedMs)
    }

    @Test
    fun `#261 a kept session whose task is gone ends, with its clock already stopped at the press`() {
        val bench = keptBench().keepSession()
        bench.pauseHook = { tick -> if (tick == 2) bench.taskGone = true }

        bench.runRetry()

        assertEquals(listOf(bench.game), bench.ended)
        assertEquals("one ask unanswered, one that proved it", 3, bench.lookups)
        assertEquals(2, bench.pauseMs.size)
        assertTrue(bench.armed.isEmpty() && bench.watched.isEmpty() && bench.reclaimed.isEmpty())
        assertEquals("the only finish was the press's", listOf(7_000L), bench.finalized.map { it.second })
    }

    @Test
    fun `#261 a kept session ends when the gateway is not ready, and keeps waiting while it is`() {
        val bench = keptBench().keepSession()
        bench.pauseHook = { tick -> if (tick == 3) bench.ready = false }

        bench.runRetry()

        assertEquals("two unanswered asks with the gateway ready changed nothing", 3, bench.pauseMs.size)
        assertEquals(listOf(bench.game), bench.ended)
        assertTrue(bench.armed.isEmpty())

        // An answer that did not come while the gateway is READY is "keep waiting", not proof.
        val waiting = keptBench().keepSession()
        waiting.pauseHook = { tick -> if (tick == 4) waiting.taskId = 4170 }
        waiting.runRetry()
        assertTrue(waiting.ended.isEmpty())
        assertEquals(1, waiting.armed.size)
    }

    @Test(timeout = 30_000)
    fun `#261 the retry stops after its cap and leaves the session kept`() {
        val bench = keptBench().keepSession()

        bench.runRetry()

        assertEquals("the press, then one ask per tick up to the cap", 1 + KeptSessionRetryMaxAsks, bench.lookups)
        assertEquals(KeptSessionRetryMaxAsks, bench.pauseMs.size)
        assertTrue("no proof, so nothing ended it", bench.ended.isEmpty())
        assertTrue(bench.armed.isEmpty() && bench.watched.isEmpty())
        assertTrue(bench.logs.any { it.startsWith("home park retry gave up") })
    }

    @Test
    fun `#261 ticks while the player is in the game are not asks and do not spend the cap`() {
        val bench = keptBench().keepSession()
        bench.mimirInFront = false
        bench.pauseHook = { tick ->
            if (tick == KeptSessionRetryMaxAsks + 5) {
                bench.mimirInFront = true
                bench.taskId = 4170
            }
        }

        bench.runRetry()

        assertEquals("one ask only: the press's, then the first with Mimir in front", 2, bench.lookups)
        assertEquals("more ticks than the cap went by, and the first ask still parked it", 1, bench.armed.size)
    }

    @Test
    fun `#261 the retry stops when the player launches anything, the session ends, a lease holds it, or a newer one replaced it`() {
        fun stoppedBy(label: String, change: (ParkBench) -> Unit) {
            val bench = keptBench().keepSession()
            bench.pauseHook = { tick -> if (tick == 2) change(bench) }
            bench.runRetry()
            assertEquals("$label: the press and the first tick asked, the second tick did not", 2, bench.lookups)
            assertEquals("$label: it stopped at the tick that saw it", 2, bench.pauseMs.size)
            assertTrue("$label: the session is left as it was", bench.ended.isEmpty() && bench.armed.isEmpty() && bench.watched.isEmpty())
        }
        stoppedBy("an app was launched") { it.appLaunched = true }
        stoppedBy("another game replaced the session") { it.game = session(operation = "game:other", dualScreen = true) }
        stoppedBy("the session is gone") { it.game = null }
        stoppedBy("the session is returning") { bench -> bench.game = bench.game?.copy(phase = MimirSessionPhase.RETURNING) }
        stoppedBy("a lease already holds it") { it.parked = true }

        // A second HOME press keeps the session again and retires the older loop: only the newest asks.
        val bench = keptBench().keepSession()
        runBlocking { bench.parker.complete(requireNotNull(bench.game), pressedAtElapsedMs = 8_000L) }
        assertEquals(2, bench.retries.size)
        val lookupsBefore = bench.lookups
        bench.runRetry(0)
        assertEquals("the older loop asked nothing", lookupsBefore, bench.lookups)
        bench.pauseHook = { tick -> if (tick == 2) bench.taskId = 4170 }
        bench.runRetry(1)
        assertEquals(1, bench.armed.size)
    }

    @Test
    fun `#261 an answer that arrives after the player went back into the game is discarded, and the next tick asks again`() {
        // A found task arrives while the player is back in the game: it must not re-park the game mid-play.
        val found = keptBench().keepSession()
        found.resolveHook = { lookup ->
            if (lookup == 2) {
                found.taskId = 4170
                found.mimirInFront = false // the first retry ask: the answer is Found, and the player has just pressed back into the game
            }
        }
        found.pauseHook = { tick -> if (tick == 3) found.mimirInFront = true }
        found.runRetry()
        assertEquals("the first answer was discarded and the next tick asked again", 3, found.lookups)
        assertEquals("only the second answer parked it", 1, found.armed.size)
        assertEquals(3, found.pauseMs.size)

        // A not-ready gateway read that completes while the player is back in the game must not end the session mid-play.
        val down = keptBench().keepSession()
        down.ready = false
        down.statusHook = { down.mimirInFront = false }
        down.pauseHook = { tick ->
            if (tick == 2) {
                down.statusHook = {}
                down.mimirInFront = true
                down.ready = true
                down.taskId = 4170
            }
        }
        down.runRetry()
        assertTrue("a status read that finished behind the game ended nothing", down.ended.isEmpty())
        assertEquals("and the next tick, back in front, parked it", 1, down.armed.size)
        assertTrue(down.logs.none { it.contains("by=gateway down") })

        // A NoTask answer that arrives after the player launched something is not acted on either.
        val gone = keptBench().keepSession()
        gone.resolveHook = { lookup ->
            if (lookup == 2) {
                gone.taskGone = true
                gone.appLaunched = true
            }
        }
        gone.runRetry()
        assertTrue("an answer to a question asked before a launch ended nothing", gone.ended.isEmpty())
        assertEquals("and the retry is over", 2, gone.lookups)

        // The same for a found task: nothing is armed over a launch.
        val launched = keptBench().keepSession()
        launched.resolveHook = { lookup ->
            if (lookup == 2) {
                launched.taskId = 4170
                launched.appLaunched = true
            }
        }
        launched.runRetry()
        assertTrue(launched.armed.isEmpty() && launched.watched.isEmpty() && launched.reclaimed.isEmpty())
    }

    @Test
    fun `#261 a gateway that is BINDING is reconnecting, so the retry keeps waiting, and ends only when the gateway is proven down`() {
        val bench = keptBench().keepSession()
        bench.ready = false
        bench.binding = true
        bench.pauseHook = { tick ->
            if (tick == 4) bench.binding = false // the bridge bound, then failed: CONNECTION_FAILED is proof
        }

        bench.runRetry()

        assertEquals("three ticks behind a binding gateway kept the session, the fourth found it down", 4, bench.pauseMs.size)
        assertEquals(listOf(bench.game), bench.ended)
        assertTrue(bench.logs.any { it.contains("by=gateway down status=connection_failed") })

        // BINDING for good never ends it: the retry reaches its cap with the session kept.
        val reconnecting = keptBench().keepSession()
        reconnecting.ready = false
        reconnecting.binding = true
        reconnecting.runRetry()
        assertTrue(reconnecting.ended.isEmpty())
        assertEquals(KeptSessionRetryMaxAsks, reconnecting.pauseMs.size)
    }

    @Test
    fun `#261 a refused arm is not proof and not the end of the retry, which keeps asking until it can park`() {
        val bench = keptBench().keepSession()
        bench.armRefused = true
        bench.taskId = 4170
        bench.pauseHook = { tick -> if (tick == 4) bench.armRefused = false }

        bench.runRetry()

        assertEquals("three refusals, then the fourth ask parked it", 4, bench.pauseMs.size)
        assertEquals(1, bench.armed.size)
        assertTrue(bench.ended.isEmpty())
        assertEquals(3, bench.logs.count { it.startsWith("home park retry arm refused") })

        // A refusal that never ends keeps the session kept to the cap, never ending it.
        val refused = keptBench().keepSession()
        refused.armRefused = true
        refused.taskId = 4170
        refused.runRetry()
        assertEquals(KeptSessionRetryMaxAsks, refused.pauseMs.size)
        assertTrue(refused.ended.isEmpty() && refused.armed.isEmpty())
        assertTrue(refused.logs.any { it.startsWith("home park retry gave up") })
    }

    @Test
    fun `#261 the kept branch records the park segment, so NOW PLAYING holds still, and a later park or second HOME press adds nothing`() {
        val bench = keptBench().keepSession()
        val segments = requireNotNull(bench.segments.state.value)
        assertEquals("the segment ran dispatch to the press (1 s to 7 s)", 6_000L, segments.playedMs)
        assertNull("parked: no running segment", segments.segmentStartedAtElapsedMs)
        assertEquals("the total before this session was taken before the finish", 1_234L, segments.totalBeforeMs)
        assertEquals("the clock on NOW PLAYING holds at the press, not at the time since dispatch", 6_000L, nowPlayingSessionElapsed(1_000L, 90_000L, segments))

        // A second HOME press, long after, on the same kept session.
        bench.now = 50_000L
        bench.totalBefore = 9_999L
        runBlocking { bench.parker.complete(requireNotNull(bench.game), pressedAtElapsedMs = 50_000L) }
        val again = requireNotNull(bench.segments.state.value)
        assertEquals("a second press on a kept session adds no play time", 6_000L, again.playedMs)
        assertEquals("and keeps the first total", 1_234L, again.totalBeforeMs)

        // The retry's park of the same operation adds nothing either.
        bench.pauseHook = { tick -> if (tick == 1) bench.taskId = 4170 }
        bench.runRetry(1)
        assertEquals(1, bench.armed.size)
        assertEquals(6_000L, requireNotNull(bench.segments.state.value).playedMs)
    }

    @Test
    fun `#261 a step of the retry that throws stops it without ending the session, and a lookup that throws is just an unanswered one`() {
        // A lookup that throws did not answer: the next tick asks again and parks.
        val lookup = keptBench().keepSession()
        lookup.resolveHook = { n -> if (n == 2) error("binder died") }
        lookup.pauseHook = { tick -> if (tick == 2) lookup.taskId = 4170 }
        lookup.runRetry()
        assertEquals(1, lookup.armed.size)
        assertTrue(lookup.ended.isEmpty())

        // An arm that throws stops the retry and logs it; nothing is ended and nothing hangs.
        val arming = keptBench().keepSession()
        arming.taskId = 4170
        arming.armThrows = true
        arming.runRetry()
        assertTrue("a retry that failed leaves the session kept", arming.ended.isEmpty() && arming.armed.isEmpty() && arming.watched.isEmpty())
        assertEquals(1, arming.pauseMs.size)
        assertTrue(arming.logs.any { it.startsWith("home park retry failed") })

        // A gateway status read that throws is a failed connection, which is proof the gateway is down.
        val status = keptBench().keepSession()
        status.statusHook = { error("posture threw") }
        status.runRetry()
        assertEquals(listOf(status.game), status.ended)
    }

    @Test
    fun `#261 a retry that is cancelled ends nothing, whether it was waiting or reading the gateway`() {
        // Cancelled while waiting for the next tick.
        val waiting = keptBench().keepSession()
        runBlocking {
            lateinit var job: Job
            waiting.pauseHook = { tick -> if (tick == 2) job.cancel() }
            job = launch { waiting.retries[0].invoke() }
            job.join()
            assertTrue(job.isCancelled)
        }
        assertEquals("the cancelled tick asked nothing", 2, waiting.lookups)
        assertTrue(waiting.ended.isEmpty() && waiting.armed.isEmpty())
        assertTrue(waiting.logs.none { it.startsWith("home park retry failed") })

        // Cancelled while the gateway read is in flight, which reports the gateway down: the answer is not acted on.
        val reading = keptBench().keepSession()
        reading.ready = false
        runBlocking {
            lateinit var job: Job
            reading.statusHook = { job.cancel() }
            job = launch { reading.retries[0].invoke() }
            job.join()
            assertTrue(job.isCancelled)
        }
        assertTrue("a cancelled read is not a gateway that is down", reading.ended.isEmpty())

        // Cancelled while the task lookup is in flight, with a found task in hand: nothing is armed.
        val asking = keptBench().keepSession()
        runBlocking {
            lateinit var job: Job
            asking.resolveHook = { n ->
                if (n == 2) {
                    asking.taskId = 4170
                    job.cancel()
                }
            }
            job = launch { asking.retries[0].invoke() }
            job.join()
            assertTrue(job.isCancelled)
        }
        assertTrue(asking.armed.isEmpty() && asking.watched.isEmpty() && asking.ended.isEmpty())
    }

    @Test
    fun `#261 a game that was never kept starts no retry`() {
        listOf(
            ParkBench(session(dualScreen = true)), // a task found: parked, nothing left to ask
            ParkBench(session()).also { it.taskId = null }, // single-screen: lets go
            ParkBench(session(dualScreen = true)).also { it.taskId = null; it.taskGone = true }, // proof: ends
        ).forEach { bench ->
            bench.keepSession()
            assertTrue(bench.retries.isEmpty())
        }
    }

    @Test
    fun `#261 an app launched after the kept game ends its claim, one launched before does not`() {
        val game = session(dualScreen = true).copy(sequence = 5L)
        fun app(sequence: Long) = MimirAppSession(
            operationId = "app:$sequence",
            sequence = sequence,
            packageName = "com.example.app",
            label = "Example",
            placement = MimirAppPlacement.PRIMARY,
            returnAnchor = PrimaryReturnOrigin(destination = MimirDestination.LIBRARY.name),
            startedAtEpochMs = 1L,
            phase = MimirSessionPhase.FOREGROUND_PROVEN,
        )
        assertFalse(appLaunchedAfterKeptGame(MimirSessionSnapshot(gameSession = game), game))
        assertFalse(appLaunchedAfterKeptGame(MimirSessionSnapshot(gameSession = game, primaryAppSession = app(4L)), game))
        assertTrue(appLaunchedAfterKeptGame(MimirSessionSnapshot(gameSession = game, primaryAppSession = app(6L)), game))
        assertTrue(appLaunchedAfterKeptGame(MimirSessionSnapshot(gameSession = game, secondaryAppSession = app(6L)), game))
    }

    @Test
    fun `a Bottom that cannot be taken back leaves the park standing`() {
        val bench = ParkBench(session(dualScreen = true)).also { it.reclaimThrows = true }
        bench.parker.onHomeKey(MainHomeKeyDecision.NOT_IN_FRONT, MainHomeKeySource.MAIN)

        bench.runScheduled()

        assertEquals(1, bench.watched.size)
        assertTrue(bench.ended.isEmpty())
        assertFalse(bench.parker.pending())
    }

    @Test
    fun `the authority arms a HOME lease for a dual-screen game, carries its screens, and the card is told`() {
        MainGameParkingState.armForHome(session(dualScreen = true), 4170)

        val lease = requireNotNull(MainGameParkingState.current())
        assertTrue(lease.homeParked)
        assertTrue(lease.dualScreen)
        assertEquals(listOf<GameId?>(GameId("xenogears")), published)
        assertFalse("a dual-screen HOME lease releases only after an explicit press", lease.releasesOnUnansweredGateway)

        MainGameParkingState.current()?.let(MainGameParkingState::clear)
        MainGameParkingState.armForHome(session(), 4170)
        assertTrue("a single-screen HOME lease lets go on an unanswered probe", requireNotNull(MainGameParkingState.current()).releasesOnUnansweredGateway)
    }

    /** What a Resume did to the two displays, in order, against a fake gateway. */
    private class DualResumeBench(var focus: PrivilegedTaskFocus, var bottomHandedOver: Boolean = true, var yieldThrows: Boolean = false) {
        val calls = mutableListOf<String>()
        val ports = HomeResumePorts(
            beginRestoring = {
                calls += "restoring"
                true
            },
            cancelRestoring = {
                calls += "unmark"
                true
            },
            focusTask = {
                calls += "focus:$it"
                focus
            },
            afterFronted = { calls += "segment" },
            releaseLease = { calls += "release" },
            endGame = { error("a resume never ends the game") },
            yieldBottom = {
                calls += "yield"
                if (yieldThrows) error("no runtime")
                bottomHandedOver
            },
            restoreBottom = { calls += "reclaim" },
        )

        fun resume(lease: MainGameParkingLease): HomeResumeOutcome = runBlocking { resumeHomeParkedGame(lease, ports) }
    }

    private fun dualLease() = homeLease().copy(dualScreen = true)

    @Test
    fun `Resume of a dual-screen game hands the Bottom to the emulator's window first, then fronts Main's task, then starts the segment`() {
        val bench = DualResumeBench(PrivilegedTaskFocus.FOCUSED)

        assertEquals(HomeResumeOutcome.FRONTED, bench.resume(dualLease()))

        assertEquals(listOf("restoring", "yield", "focus:4170", "segment"), bench.calls)
    }

    @Test
    fun `a refused focus takes the Bottom back and keeps the lease for another try`() {
        val bench = DualResumeBench(PrivilegedTaskFocus.FAILED)

        assertEquals(HomeResumeOutcome.FAILED, bench.resume(dualLease()))

        assertEquals(listOf("restoring", "yield", "focus:4170", "unmark", "reclaim"), bench.calls)
    }

    @Test
    fun `a Bottom with nothing to hand over is not given back, and a yield that throws is nothing to hand over`() {
        val idle = DualResumeBench(PrivilegedTaskFocus.FAILED, bottomHandedOver = false)
        assertEquals(HomeResumeOutcome.FAILED, idle.resume(dualLease()))
        assertEquals(listOf("restoring", "yield", "focus:4170", "unmark"), idle.calls)

        val broken = DualResumeBench(PrivilegedTaskFocus.FOCUSED, yieldThrows = true)
        assertEquals("the Main focus still happens", HomeResumeOutcome.FRONTED, broken.resume(dualLease()))
        assertEquals(listOf("restoring", "yield", "focus:4170", "segment"), broken.calls)
    }

    @Test
    fun `a gateway that is gone retains a dual-screen lease - ending its session would orphan the emulator's Bottom window`() {
        val bench = DualResumeBench(PrivilegedTaskFocus.AUTHORITY_UNAVAILABLE)

        assertEquals(HomeResumeOutcome.FAILED, bench.resume(dualLease()))

        assertEquals(listOf("restoring", "yield", "focus:4170", "unmark", "reclaim"), bench.calls)
        assertFalse("nothing was released", "release" in bench.calls)
    }

    @Test
    fun `a single-screen Resume never touches the Bottom`() {
        val bench = DualResumeBench(PrivilegedTaskFocus.FOCUSED)

        assertEquals(HomeResumeOutcome.FRONTED, bench.resume(homeLease()))

        assertEquals(listOf("restoring", "focus:4170", "segment"), bench.calls)
    }

    @Test
    fun `Play on the dual-screen game that is parked resumes it on both displays`() {
        val bench = DualResumeBench(PrivilegedTaskFocus.FOCUSED)
        val messages = mutableListOf<String>()

        val press = runBlocking {
            handleHomeParkedPress(
                lease = dualLease(),
                pressedGame = GameId("xenogears"),
                pressedTitle = "Xenogears",
                ports = bench.ports,
                confirm = EndParkedGameConfirm(now = { 0L }),
                titleOf = { "Xenogears" },
                emitMessage = { _, text -> messages += text },
            )
        }

        assertEquals(HomeParkedPress.ANSWERED, press)
        assertEquals(listOf("restoring", "yield", "focus:4170", "segment"), bench.calls)
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `ending a parked dual-screen game with no gateway ends nothing, and Quit keeps its lease`() {
        var released = 0
        val ended = runBlocking {
            endHomeParkedGame(
                lease = dualLease(),
                quit = { PrivilegedCloseOutcome.AUTHORITY_UNAVAILABLE },
                release = { released++ },
            )
        }
        assertFalse(ended)
        assertEquals(0, released)
        assertTrue(runBlocking { endHomeParkedGame(dualLease(), { PrivilegedCloseOutcome.STOPPED }, { released++ }) })
    }

    @Test
    fun `the production wiring hands the Bottom over and takes it back`() {
        val park = sourceFile("MainGameHomePark.kt")
        assertTrue(park.contains("yieldBottom = ::yieldBottomForHomeResume,"))
        assertTrue(park.contains("restoreBottom = ::restoreBottomAfterFailedHomeResume,"))
        assertTrue(park.contains("reclaimBottom = { game -> reclaimBottomForHomePark(game) },"))
        assertTrue(park.contains("if (lease.releasesOnUnansweredGateway) {\n        releaseHomeParkedGameIfGone(context, lease, stage)"))
        // The Bottom's own "Return to game" starts the next segment of a HOME-parked game, so the clock and the play time resume there too.
        assertTrue(sourceFile("CompanionDisplayController.kt").contains("if (parkedLease.homeParked) runCatching { MainGameHomePark.beginSegment(activity, parkedLease) }"))
        // Quit's release comes after a verified close, so it is the lease's own release and asks the gateway nothing more.
        assertTrue(sourceFile("CompanionNowPlayingState.kt").contains("releaseHomeParkedLease(lease, endedBy = \"quit\")"))
    }

    // ---- review round, item 1: a gateway that cannot answer never holds a game -------------------------------------------

    private fun releaseDecision(outcome: suspend () -> VendorTaskOutcome): Pair<Boolean, List<String>> {
        val ended = mutableListOf<String>()
        val released = runBlocking {
            releaseHomeParkedWhenNotFound(
                lease = homeLease(),
                resolve = outcome,
                release = { _, endedBy ->
                    ended += endedBy
                    true
                },
                log = {},
            )
        }
        return released to ended
    }

    @Test
    fun `a HOME lease keeps its game only on a task the gateway found`() {
        assertEquals(false to emptyList<String>(), releaseDecision { VendorTaskOutcome.Found(4170) })
    }

    @Test
    fun `a HOME lease is released when the task is gone`() {
        assertEquals(true to listOf("parked task gone"), releaseDecision { VendorTaskOutcome.NoTask })
    }

    @Test
    fun `a HOME lease is released when the gateway is unavailable, times out or throws - no launch is held behind it`() {
        listOf("unavailable", "timeout", "parse", "empty").forEach { reason ->
            assertEquals(reason, true to listOf("parked task unknown"), releaseDecision { VendorTaskOutcome.Unavailable(reason) })
        }
        assertEquals(true to listOf("parked task unknown"), releaseDecision { error("binder died") })
    }

    @Test
    fun `a dual-screen lease still retains on an unanswered probe - only the HOME lease lets go`() {
        assertTrue(CompanionProcessRuntime.parkedGameProvenGone(VendorTaskOutcome.NoTask, PrivilegedPackageLiveness.UNKNOWN))
        assertFalse(
            CompanionProcessRuntime.parkedGameProvenGone(VendorTaskOutcome.Unavailable("timeout"), PrivilegedPackageLiveness.UNKNOWN),
        )
    }

    @Test
    fun `a Resume that finds the gateway gone releases the lease and lets the press launch as before`() {
        MainGameParkingState.armForHome(session(), 4170)
        val lease = requireNotNull(MainGameParkingState.current())
        val released = mutableListOf<MainGameParkingLease>()

        val outcome = runBlocking {
            resumeHomeParkedGame(lease, resumePorts(PrivilegedTaskFocus.AUTHORITY_UNAVAILABLE, released = released))
        }

        assertEquals(HomeResumeOutcome.RELEASED, outcome)
        assertEquals(listOf(lease), released)
        assertNull("the lease is gone, so nothing holds the launch", MainGameParkingState.current())
        assertFalse(MainGameParkingState.isRestoring(lease))
    }

    @Test
    fun `a Resume the system refuses without losing the gateway keeps the lease`() {
        MainGameParkingState.armForHome(session(), 4170)
        val lease = requireNotNull(MainGameParkingState.current())
        val released = mutableListOf<MainGameParkingLease>()

        listOf(PrivilegedTaskFocus.FAILED, PrivilegedTaskFocus.NOT_ALLOWED).forEach { focus ->
            assertEquals(HomeResumeOutcome.FAILED, runBlocking { resumeHomeParkedGame(lease, resumePorts(focus, released = released)) })
        }

        assertTrue(released.isEmpty())
        assertEquals(lease, MainGameParkingState.current())
    }

    @Test
    fun `Play on the parked game after the gateway is gone admits the launch again, in the same press`() {
        val bench = PressBench(homeLease()).also { it.focusResult = PrivilegedTaskFocus.AUTHORITY_UNAVAILABLE }

        val press = bench.press("xenogears", "Xenogears")

        assertEquals(HomeParkedPress.ENDED_PARKED_GAME, press)
        assertEquals(1, bench.released.size)
        assertTrue(bench.messages.isEmpty())
    }

    @Test
    fun `ending a parked game with no gateway releases it, and any other refusal ends nothing`() {
        val lease = homeLease()
        fun end(outcome: PrivilegedCloseOutcome?): Triple<Boolean, Int, Boolean> {
            var released = 0
            var quit = false
            val ended = runBlocking {
                endHomeParkedGame(
                    lease = lease,
                    quit = {
                        quit = true
                        outcome
                    },
                    release = { released++ },
                )
            }
            return Triple(ended, released, quit)
        }

        assertEquals(Triple(true, 0, true), end(PrivilegedCloseOutcome.STOPPED))
        assertEquals(Triple(true, 1, true), end(PrivilegedCloseOutcome.AUTHORITY_UNAVAILABLE))
        assertEquals("the session was already gone", Triple(true, 0, true), end(null))
        listOf(
            PrivilegedCloseOutcome.STILL_ALIVE,
            PrivilegedCloseOutcome.REQUEST_UNVERIFIED,
            PrivilegedCloseOutcome.NOT_ALLOWED,
            PrivilegedCloseOutcome.FAILED,
        ).forEach { refused ->
            assertEquals(refused.name, Triple(false, 0, true), end(refused))
        }
    }

    @Test
    fun `the Bottom Quit lets go of any lease once its close is verified, even when the gateway cannot say whether the task is there`() {
        val sessionGame = session()
        fun quit(): PrivilegedCloseOutcome {
            var ended = 0
            val ports = NowPlayingQuitPorts(
                close = { PrivilegedCloseOutcome.STOPPED },
                sessionLive = { ended == 0 },
                isParked = { true },
                resolveTask = { VendorTaskOutcome.Unavailable("timeout") },
                removeTask = { error("no task is known, so none is removed") },
                releaseParked = {
                    ended++
                    true
                },
                endSession = { false },
            )
            return runBlocking { nowPlayingQuitPerform(sessionGame, ports) }
        }

        // the proven close is what counts, for a dual-screen lease as for a single-screen one. The keep-when-unsure
        // rule is for the paths with no verified close (see the Quit tests in CompanionNowPlayingStateTest).
        assertEquals(PrivilegedCloseOutcome.STOPPED, quit())
    }

    @Test
    fun `the task lookup stops waiting at the timeout - the blocked call is abandoned, not interrupted`() {
        assertEquals(2_500L, HomeParkResolveTimeoutMs)
        val held = CountDownLatch(1)
        val started = System.nanoTime()
        val answer = runBlocking {
            answerWithin<Int>(50L) {
                held.await(30, TimeUnit.SECONDS)
                4170
            }
        }
        val waitedMs = (System.nanoTime() - started) / 1_000_000L
        held.countDown()
        assertNull("no answer at the timeout", answer)
        assertTrue("the caller was released at the timeout, not when the blocked call came back ($waitedMs ms)", waitedMs < 5_000L)
    }

    @Test
    fun `a task lookup that answers in time is returned, and an empty answer stays empty`() {
        assertEquals(4170, runBlocking { answerWithin(1_000L) { 4170 } })
        assertNull(runBlocking { answerWithin<Int>(1_000L) { null } })
    }

    @Test
    fun `a task lookup that times out ends the session, like one that cannot be resolved`() {
        val bench = ParkBench(session()).also { it.taskId = null }
        bench.parker.onHomeKey(MainHomeKeyDecision.NOT_IN_FRONT, MainHomeKeySource.MAIN)

        bench.runScheduled()

        assertEquals(1, bench.ended.size)
        assertTrue("no elapsed time is finished for a park that failed", bench.finalized.isEmpty())
    }

    // ---- review round, item 2: parked time is not played time -------------------------------------------------------------

    @Test
    fun `the measured elapsed time is finished at the moment of the HOME press, once the lease is armed`() {
        val bench = ParkBench(session()).also { it.now = 41_000L }
        bench.parker.onHomeKey(MainHomeKeyDecision.NOT_IN_FRONT, MainHomeKeySource.MAIN)
        bench.now = 99_000L

        bench.runScheduled()

        assertEquals("the press time, not the time the task was found", listOf(bench.game!! to 41_000L), bench.finalized)
    }

    @Test
    fun `a park records the finished segment and the total as it stood, before the store adds the segment`() {
        val bench = ParkBench(session(dispatchedAt = 1_000L)).also {
            it.now = 41_000L
            it.totalBefore = 7_200_000L
        }
        bench.parker.onHomeKey(MainHomeKeyDecision.NOT_IN_FRONT, MainHomeKeySource.MAIN)

        bench.runScheduled()

        assertEquals(listOf("total", "finalize"), bench.order)
        assertEquals(
            HomeParkSegments(
                operation = bench.game!!.operation,
                playedMs = 40_000L,
                segmentStartedAtElapsedMs = null,
                totalBeforeMs = 7_200_000L,
            ),
            bench.segments.state.value,
        )
    }

    @Test
    fun `a park that failed leaves no record for the clock to hold still on`() {
        val bench = ParkBench(session(dispatchedAt = 1_000L)).also { it.taskId = null }
        bench.parker.onHomeKey(MainHomeKeyDecision.NOT_IN_FRONT, MainHomeKeySource.MAIN)

        bench.runScheduled()

        assertNull(bench.segments.state.value)
    }

    @Test
    fun `a measurement that cannot be finished does not undo the park`() {
        val bench = ParkBench(session()).also { it.finalizeThrows = true }
        bench.parker.onHomeKey(MainHomeKeyDecision.NOT_IN_FRONT, MainHomeKeySource.MAIN)

        bench.runScheduled()

        assertEquals(1, bench.watched.size)
        assertTrue(bench.ended.isEmpty())
        assertFalse(bench.parker.pending())
    }

    @Test
    fun `Resume starts a new measured segment only once the task is in front`() {
        MainGameParkingState.armForHome(session(), 4170)
        val lease = requireNotNull(MainGameParkingState.current())
        val fronted = mutableListOf<MainGameParkingLease>()

        runBlocking { resumeHomeParkedGame(lease, resumePorts(PrivilegedTaskFocus.FAILED, fronted = fronted)) }
        assertTrue("a refused focus starts nothing", fronted.isEmpty())

        runBlocking { resumeHomeParkedGame(lease, resumePorts(PrivilegedTaskFocus.FOCUSED, fronted = fronted)) }
        assertEquals(listOf(lease), fronted)
    }

    // ---- review round, item 3: the card says Resume, and the Library's refused Play resumes -------------------------------

    @Test
    fun `the launch card is told which game a HOME lease holds, and told when it lets go`() {
        MainGameParkingState.armForHome(session(), 4170)
        assertEquals(listOf<GameId?>(GameId("xenogears")), published)

        MainGameParkingState.current()?.let(MainGameParkingState::clear)
        assertEquals(listOf<GameId?>(GameId("xenogears"), null), published)
    }

    @Test
    fun `a dual-screen lease never touches the card's label`() {
        MainGameParkingState.arm(session(dualScreen = true), 4170)
        MainGameParkingState.current()?.let(MainGameParkingState::clear)

        assertTrue(published.all { it == null })
    }

    @Test
    fun `a HOME lease dropped when Main stops for the resumed game takes Resume off the card`() {
        MainGameParkingState.armForHome(session(), 4170)
        val lease = requireNotNull(MainGameParkingState.current())
        MainGameParkingState.beginRestoring(lease)
        MainGameParkingState.clearRestoringOnPrimaryStop(lease.operation)

        assertEquals(null, published.last())
    }

    private fun libraryGame(id: String = "xenogears", title: String = "Xenogears") = GameRow(
        gameId = GameId(id),
        title = title,
        platformId = PlatformId.PS1,
        memberCount = 1,
        duplicateKind = null,
    )

    private fun library(
        lease: MainGameParkingLease?,
        game: GameRow,
        bench: PressBench,
        restart: Boolean = false,
    ): ParkedLeaseReleaseOutcome = runBlocking {
        libraryParkedPress(
            lease = lease,
            game = game,
            ports = bench.ports,
            confirm = bench.confirm,
            titleOf = { "Xenogears" },
            otherwise = { "dual-screen sentence" },
            restart = restart,
        )
    }

    // ---- #227 on the Library door: the one card's Restart ends the parked session, and the Library's launch retries ----------

    @Test
    fun `the Library's Restart on the parked game ends it and lets the launch through - no question, no resume`() {
        val bench = PressBench(homeLease())

        val outcome = library(bench.lease, libraryGame(), bench, restart = true)

        assertEquals(ParkedLeaseReleaseOutcome.Released, outcome)
        assertEquals("the confirmed Quit ran once", 1, bench.ended)
        assertTrue("the task is not fronted first", bench.focused.isEmpty())
        assertTrue("and nothing is asked or said", bench.messages.isEmpty())
    }

    @Test
    fun `the Library's Restart whose Quit does not finish starts nothing and says so`() {
        val bench = PressBench(homeLease()).also { it.endResult = false }

        val outcome = library(bench.lease, libraryGame(), bench, restart = true)

        assertEquals(ParkedLeaseReleaseOutcome.StillParked("Couldn’t end Xenogears. Quit it first."), outcome)
        assertEquals(1, bench.ended)
    }

    @Test
    fun `a Restart for one game never ends another through the Library, and Play without it still resumes`() {
        val other = PressBench(homeLease())
        assertEquals(
            ParkedLeaseReleaseOutcome.StillParked("End Xenogears and start Crash Bandicoot? Press Play again to confirm."),
            library(other.lease, libraryGame("crash", "Crash Bandicoot"), other, restart = true),
        )
        assertEquals(0, other.ended)

        val plain = PressBench(homeLease())
        assertEquals(ParkedLeaseReleaseOutcome.NotParked, library(plain.lease, libraryGame(), plain, restart = false))
        assertEquals(listOf(4170), plain.focused)
        assertEquals(0, plain.ended)
    }

    @Test
    fun `the Library refused Play on the parked game resumes it and shows nothing more`() {
        val bench = PressBench(homeLease())

        val outcome = library(bench.lease, libraryGame(), bench)

        assertEquals(ParkedLeaseReleaseOutcome.NotParked, outcome)
        assertEquals(listOf(4170), bench.focused)
        assertEquals(0, bench.ended)
    }

    @Test
    fun `the Library refused Play on another game names the running one, and a second press ends it and retries`() {
        val bench = PressBench(homeLease())
        val crash = libraryGame("crash", "Crash Bandicoot")

        val first = library(bench.lease, crash, bench)
        assertEquals(
            ParkedLeaseReleaseOutcome.StillParked("End Xenogears and start Crash Bandicoot? Press Play again to confirm."),
            first,
        )
        assertEquals(0, bench.ended)

        bench.clock = 2_000L
        assertEquals(ParkedLeaseReleaseOutcome.Released, library(bench.lease, crash, bench))
        assertEquals(1, bench.ended)
    }

    @Test
    fun `the Library refused Play that a resume could not front says so, and a dual-screen lease keeps its own sentence`() {
        val failed = PressBench(homeLease()).also { it.focusResult = PrivilegedTaskFocus.FAILED }
        assertEquals(
            ParkedLeaseReleaseOutcome.StillParked("Couldn’t bring Xenogears back. Try again, or quit it first."),
            library(failed.lease, libraryGame(), failed),
        )

        val dual = PressBench(homeLease(homeParked = false))
        assertEquals(
            ParkedLeaseReleaseOutcome.StillParked("dual-screen sentence"),
            library(dual.lease, libraryGame(), dual),
        )
        assertEquals(ParkedLeaseReleaseOutcome.NotParked, library(null, libraryGame(), dual))
    }

    // ---- review round, item 6: an ended game whose replacement is refused still says why ------------------------------------

    @Test
    fun `both Home Play sites say something when the game they ended did not free the launch`() {
        val source = sourceFile("HomeShelfCoordinator.kt")

        assertTrue(source.contains("if (parkedPress != HomeParkedPress.ANSWERED) emitHomeShelfGameRefusalMessage(heldBy, emitMessage)"))
        assertTrue(source.contains("if (parkedPress == HomeParkedPress.ENDED_PARKED_GAME) emitHomeShelfSessionHeldMessage(heldBy, emitMessage)"))
        val readmissions = "if (parkedPress == HomeParkedPress.ENDED_PARKED_GAME) operation = admitHomeGame()"
        assertEquals("one re-admission after an end at each site", 2, source.split(readmissions).size - 1)
    }

    // ---- review round, item 4: what Main's resume skips for a HOME-parked game ------------------------------------------------

    @Test
    fun `Main's resume leaves a HOME-parked game's NOW PLAYING alone and still does the rest of a return`() {
        val source = sourceFile("MainActivity.kt")
        val resume = source.substringAfter("override fun onResume() {").substringBefore("private fun settleReturningGameSession(")

        assertTrue(resume.contains("val parkedMainGame = MainGameParkingState.currentForCurrentSession()?.takeUnless { it.homeParked } != null"))
        assertTrue(resume.contains("val homeParked = MainGameHomePark.parkedByHome()"))
        // Skipped for a game that is still running behind Mimir: the watchdog, the foreground observer, and the three teardown calls
        // that would drop the Bottom's NOW PLAYING (and its Quit).
        assertTrue(resume.contains("if (!parkedMainGame && !homeParked && !resumedAfterStop) {"))
        assertTrue(resume.contains("if (!homeParked) CompanionSceneState.observeMimirForeground()"))
        assertTrue(resume.contains("if (!homeParked) CompanionState.dualScreenGameActive = false"))
        assertTrue(resume.contains("if (!homeParked) CompanionSceneState.disarmGameOwner()"))
        assertTrue(resume.contains("if (!homeParked) CompanionState.clearRetainedNowPlaying()"))
        // Not skipped, because Mimir really is in front: the audio gate, the running-app note and the Bottom reclaim, which only
        // re-presents the Bottom after a recovery and never touches the lease or the session. The audio gate runs for a switch-button
        // park too (a non-home lease): it sits outside the `if (!parkedMainGame)` block, which keeps the three teardown calls.
        // The call itself, at eight spaces (the top level of the resume), and not the mention of it in the comment above.
        val gate = resume.indexOf("\n        MimirAudioFocus.onReturnedToMimir()\n")
        assertTrue("the audio gate is called at the top level of the resume, not inside a block", gate > 0)
        assertTrue(
            "and the parked-game guard that keeps the three teardown calls comes after it",
            resume.indexOf("if (!parkedMainGame) {", gate) > gate && resume.indexOf("if (!homeParked) CompanionState.dualScreenGameActive = false") > gate,
        )
        assertTrue(resume.contains("            noteCompanionRunningAppMainResumed()"))
        assertTrue(resume.contains("val reclaimOnResume = !parkedMainGame &&"))
        // The resume-time release still looks at a HOME lease.
        assertTrue(resume.contains("if ((parkedMainGame || homeParked) && resumedAfterStop) {"))
        // One reading of the state per resume, reused: no second call to the helper.
        assertEquals(1, resume.split("MainGameHomePark.parkedByHome()").size - 1)
        assertEquals("the observer and the three teardown calls, and nothing else, sit behind it", 4, resume.split("if (!homeParked)").size - 1)
    }

    // ---- review round, item 8: a player who leaves on purpose is not told the emulator closed -------------------------------

    @Test
    fun `a HOME or B press after the dispatch is a deliberate leave, and any other key except D-pad and the shoulder pair takes it back`() {
        var now = 10_000L
        UserLeaveSignal.clock = { now }
        assertFalse(UserLeaveSignal.leftSince(9_000L))

        UserLeaveSignal.noteHome()
        assertTrue(UserLeaveSignal.leftSince(9_000L))
        assertTrue("a launch with no recorded time counts any leave", UserLeaveSignal.leftSince(null))

        UserLeaveSignal.reset()
        now = 10_500L
        UserLeaveSignal.noteKey(android.view.KeyEvent.KEYCODE_BUTTON_B)
        assertTrue(UserLeaveSignal.leftSince(10_000L))

        now = 10_600L
        UserLeaveSignal.noteKey(android.view.KeyEvent.KEYCODE_BUTTON_A)
        assertFalse("the last input was not a leave", UserLeaveSignal.leftSince(10_000L))

        UserLeaveSignal.noteKey(android.view.KeyEvent.KEYCODE_BACK)
        assertTrue(UserLeaveSignal.leftSince(10_000L))
    }

    @Test
    fun `#233 a D-pad or shoulder press after B is still a deliberate leave, so the watchdog does not say the emulator closed`() {
        val dispatched = session(phase = MimirSessionPhase.DISPATCHED).copy(dispatchedAtElapsedMs = 10_000L)
        var now = 10_300L
        UserLeaveSignal.clock = { now }
        UserLeaveSignal.noteKey(android.view.KeyEvent.KEYCODE_BUTTON_B)

        listOf(
            android.view.KeyEvent.KEYCODE_DPAD_UP,
            android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            // L1 and R1 move between shelves: the same player moving about Mimir after they left.
            android.view.KeyEvent.KEYCODE_BUTTON_L1,
            android.view.KeyEvent.KEYCODE_BUTTON_R1,
        ).forEach { navigation ->
            now += 100L
            UserLeaveSignal.noteKey(navigation)
            assertTrue("keycode $navigation", LaunchEarlyClose.userLeftOnPurpose(dispatched))
        }

        // Navigation does not create a leave either: with no B or HOME, the watchdog still speaks.
        UserLeaveSignal.reset()
        UserLeaveSignal.noteKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        UserLeaveSignal.noteKey(android.view.KeyEvent.KEYCODE_BUTTON_R1)
        assertFalse(LaunchEarlyClose.userLeftOnPurpose(dispatched))
    }

    @Test
    fun `a leave from before the dispatch does not suppress the message for this launch`() {
        UserLeaveSignal.clock = { 5_000L }
        UserLeaveSignal.noteKey(android.view.KeyEvent.KEYCODE_BUTTON_B)

        assertFalse(UserLeaveSignal.leftSince(6_000L))
    }

    @Test
    fun `the early-close verdict reads the leave against the launch's own dispatch time`() {
        val dispatched = session(phase = MimirSessionPhase.DISPATCHED).copy(dispatchedAtElapsedMs = 10_000L)
        UserLeaveSignal.clock = { 10_400L }
        assertFalse(LaunchEarlyClose.userLeftOnPurpose(dispatched))

        UserLeaveSignal.noteHome()
        assertTrue(LaunchEarlyClose.userLeftOnPurpose(dispatched))
        assertFalse(LaunchEarlyClose.userLeftOnPurpose(dispatched.copy(dispatchedAtElapsedMs = 20_000L)))
    }

    @Test
    fun `the early-close wiring withholds only the sentence - the card is still reset, and the key lane reports to the signal`() {
        val honesty = sourceFile("LaunchHonesty.kt")
        assertTrue(honesty.contains("library.cancelPendingLaunch(operation)"))
        assertTrue(honesty.contains("library.closePendingLaunchUnexpectedly(operation)"))
        assertTrue(honesty.contains("if (game.libraryOperation == null && !LaunchEarlyClose.userLeftOnPurpose(game)) {"))
        assertTrue(sourceFile("MainActivity.kt").contains("UserLeaveSignal.noteKey(event.keyCode)"))
        assertTrue(sourceFile("MimirHomeKeyRoute.kt").contains("if (kind == MainHomeIntentKind.USER_HOME) UserLeaveSignal.noteHome()"))
    }

    // ---- the wiring the JVM cannot run --------------------------------------------------------------------------------------

    @Test
    fun `both Home Play sites let release-at-the-press clear a stale lease before they look at a parked game`() {
        val source = sourceFile("HomeShelfCoordinator.kt")
        val calls = Regex(Regex.escape("homeParkedPress(")).findAll(source).map { it.range.first }.toList()

        assertEquals("the shelf row and the launch card", 2, calls.size)
        calls.forEach { at ->
            assertTrue(
                source.substring((at - 2_500).coerceAtLeast(0), at).contains("CompanionProcessRuntime.releaseParkedLeaseForPress(context)"),
            )
        }
    }

    @Test
    fun `Main's resume, the HOME router and the Library refusal are wired to the park`() {
        assertTrue(
            sourceFile("MainActivity.kt").contains(
                "val parkedMainGame = MainGameParkingState.currentForCurrentSession()?.takeUnless { it.homeParked } != null",
            ),
        )
        val route = sourceFile("MimirHomeKeyRoute.kt")
        assertTrue(route.contains("if (decision == MainHomeKeyDecision.NOT_IN_FRONT) platform.parkGameForHome(decision)"))
        assertTrue(route.contains("MainGameHomePark.onHomeKey(activity.applicationContext, decision, source)"))
        assertTrue(sourceFile("MimirSessionCoordinator.kt").contains("libraryParkedRefusalOutcome(lease, event.game)"))
        assertNotNull(sourceFile("MainGameHomePark.kt"))
    }

    @Test
    fun `Resume restarts the clock and NOW PLAYING reads the record`() {
        val park = sourceFile("MainGameHomePark.kt")
        assertTrue(park.contains("homeParkSegments.resumed(lease.operation, resumedAt)"))
        assertTrue(park.contains("segments = homeParkSegments,"))
        val detail = sourceFile("CompanionActivityGameDetail.kt")
        assertTrue(detail.contains("nowPlayingTotalText(nowPlayingTotalBeforeMs(totalBeforeMs, segments))"))
        assertTrue(detail.contains("segmentsRecord.forSession(game.operation)"))
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

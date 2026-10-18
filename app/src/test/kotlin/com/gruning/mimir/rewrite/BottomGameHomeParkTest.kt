package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardResume
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HOME on the Bottom over a Bottom-placed game parks it, as HOME on Main parks a Main game. The game keeps running,
 * Mimir's companion comes back on the Bottom, the card offers Resume (and Restart), the 5 s proof-only watch holds the lease, and Resume re-fronts the
 * game on the Bottom through the game tab's own reopen. These are the pieces that run without a device: the decision, the park, the lease, the Resume
 * and the watch against fakes. The session bridge keeping the session through the reclaim is `CompanionRuntimeCoordinatorTest`'s (the real runtime).
 * That the vendor gateway names a task on display 4, and that the reopen brings the running game back, is the device's to prove.
 */
class BottomGameHomeParkTest {
    private val published = mutableListOf<GameId?>()

    @Before
    fun cleanProcess() {
        MainGameParkingState.homeParkedPublisher = { published += it }
        MainGameParkingState.current()?.let(MainGameParkingState::clear)
        published.clear()
    }

    @After
    fun restoreProcess() {
        MainGameParkingState.current()?.let(MainGameParkingState::clear)
        MainGameParkingState.homeParkedPublisher = HomeLaunchCardResume::set
    }

    private fun session(
        placement: MimirGamePlacement = MimirGamePlacement.BOTTOM,
        phase: MimirSessionPhase = MimirSessionPhase.FOREGROUND_PROVEN,
        dualScreen: Boolean = false,
    ): MimirGameSession = MimirGameSession(
        operation = MimirGameSessionOperation("game:chrono"),
        sequence = 1L,
        gameId = GameId("chrono"),
        title = "Chrono Trigger",
        platformId = PlatformId.SNES,
        returnAnchor = MimirGameReturnAnchor.Home(
            HomeLaunchReturnTarget(HomeShelfItemId.Emulated(GameId("chrono")), rank = 0L),
        ),
        startedAtEpochMs = 1L,
        dispatchedAtElapsedMs = 1_000L,
        targetPackageName = "com.retroarch",
        placement = placement,
        dualScreen = dualScreen,
        phase = phase,
    )

    private fun bottomLease(taskId: Int = 71) = MainGameParkingLease(
        operation = MimirGameSessionOperation("game:chrono"),
        gameId = GameId("chrono"),
        targetPackageName = "com.retroarch",
        taskId = taskId,
        parkedAtElapsedMillis = 1L,
        homeParked = true,
        bottom = true,
        displayId = 4,
    )

    private fun hostLease(packageName: String = "com.retroarch") = HostLease(
        operationId = OperationId.create(),
        packageName = packageName,
        displayId = 4,
        launchMode = "secondary_game",
        primaryOrigin = PrimaryReturnOrigin("HOME"),
    )

    // ---- the decision -----------------------------------------------------------------------------------------------------

    @Test
    fun `a settled Bottom game under the floor is parked, and nothing else is`() {
        fun verdict(
            covered: Boolean = true,
            game: MimirGameSession? = session(),
            inFlight: Boolean = false,
            parked: Boolean = false,
        ) = decideBottomHomePark(covered, game, inFlight, parked)

        assertEquals(HomeParkVerdict.PARK, verdict())
        assertEquals("a game yielded by its host is settled too", HomeParkVerdict.PARK, verdict(game = session(phase = MimirSessionPhase.YIELDED_TO_SECONDARY_TASK)))
        assertEquals(HomeParkVerdict.NOT_COVERED, verdict(covered = false))
        assertEquals(HomeParkVerdict.NO_GAME, verdict(game = null))
        assertEquals("a Main game is HOME's other path", HomeParkVerdict.NOT_ON_BOTTOM, verdict(game = session(placement = MimirGamePlacement.MAIN)))
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
    fun `the floor finds a Bottom game only under a host-covered lease whose package a live Bottom session owns`() {
        val host = hostLease()
        val snapshot = MimirSessionSnapshot(revision = 1L, gameSession = session())

        val game = bottomHomeTarget(SecondaryDisplayDisposition.Yielded(host, YieldEvidence.HOST_COVERED), snapshot)
        assertEquals(host, game?.first)
        assertEquals(session(), game?.second)

        val app = bottomHomeTarget(SecondaryDisplayDisposition.Yielded(hostLease("com.android.chrome"), YieldEvidence.HOST_COVERED), snapshot)
        assertTrue("an app under the floor is covered, but no game's", app != null && app.second == null)

        assertNull(
            "the Main game's hand-over is not a host cover",
            bottomHomeTarget(SecondaryDisplayDisposition.Yielded(host, YieldEvidence.USER_REQUESTED_GAME_RETURN), snapshot),
        )
        assertNull(bottomHomeTarget(SecondaryDisplayDisposition.Yielded(null, YieldEvidence.HOST_COVERED), snapshot))
        assertNull(bottomHomeTarget(SecondaryDisplayDisposition.NoDisplay, snapshot))
        val ended = MimirSessionSnapshot(revision = 2L, gameSession = session(phase = MimirSessionPhase.ENDED))
        assertNull(
            "a session that has ended is no live game",
            bottomHomeTarget(SecondaryDisplayDisposition.Yielded(host, YieldEvidence.HOST_COVERED), ended)?.second,
        )
    }

    // ---- the park ---------------------------------------------------------------------------------------------------------

    private class Bench(var game: MimirGameSession?) {
        var taskId: Int? = 71
        var parked = false
        var withheld = 0
        val order = mutableListOf<String>()
        val scheduled = mutableListOf<suspend () -> Unit>()
        val ended = mutableListOf<MimirGameSession>()
        val watched = mutableListOf<MainGameParkingLease>()
        val armed = mutableListOf<MainGameParkingLease>()
        val reclaimed = mutableListOf<MimirGameSession>()
        val logs = mutableListOf<String>()
        val segments = HomeParkSegmentLedger()

        val parker = MainGameHomeParker(
            HomeParkPorts(
                currentGame = { game },
                isParked = { parked },
                privilegeReady = { true },
                withholdReturn = { withheld++ },
                resolveTaskOutcome = { taskId?.let(VendorTaskOutcome::Found) ?: VendorTaskOutcome.Unavailable("unavailable") },
                arm = { g, t ->
                    MainGameParkingLease(
                        operation = g.operation,
                        gameId = g.gameId,
                        targetPackageName = g.targetPackageName.orEmpty(),
                        taskId = t,
                        parkedAtElapsedMillis = 1L,
                        homeParked = true,
                        bottom = true,
                        displayId = 4,
                    ).also { armed += it }
                },
                endSession = { ended += it },
                startWatch = { watched += it },
                reclaimBottom = { reclaimed += it },
                finalizeElapsed = { _, _ -> order += "finalize" },
                elapsedNow = { 9_000L },
                totalBefore = {
                    order += "total"
                    600_000L
                },
                segments = segments,
                schedule = { scheduled += it },
                log = { logs += it },
            ),
        )

        fun run() = runBlocking { scheduled.single().invoke() }
    }

    @Test
    fun `HOME on the Bottom parks the game - the lease names its display, the segment is recorded, the watch runs, the session is kept`() {
        val bench = Bench(session())

        assertEquals(HomeParkVerdict.PARK, bench.parker.onBottomHome(bench.game, covered = true))
        assertTrue("the park is pending from the press, so Main's resume cannot be mistaken for a Bottom park", bench.parker.pending())
        assertTrue("nothing waits for the vendor on the main thread of the press", bench.armed.isEmpty())

        bench.run()

        val lease = bench.armed.single()
        assertTrue(lease.bottom)
        assertTrue(lease.homeParked)
        assertEquals(4, lease.displayId)
        assertEquals(listOf(lease), bench.watched)
        assertEquals("the finished segment is taken after the total, never before", listOf("total", "finalize"), bench.order)
        assertEquals("parked time is not played time: 1.0 s to 9.0 s", 8_000L, bench.segments.state.value?.playedMs)
        assertEquals(600_000L, bench.segments.state.value?.totalBeforeMs)
        assertEquals("no return on Main is withheld and no Bottom is reclaimed again: the floor's reclaim is the park's", 0, bench.withheld)
        assertTrue(bench.reclaimed.isEmpty())
        assertTrue("the session is kept", bench.ended.isEmpty())
        assertFalse(bench.parker.pending())
    }

    @Test
    fun `a task the gateway cannot name ends the session, which is what the floor's reclaim did before`() {
        val bench = Bench(session()).also { it.taskId = null }
        bench.parker.onBottomHome(bench.game, covered = true)

        bench.run()

        assertEquals(listOf(bench.game), bench.ended)
        assertTrue(bench.watched.isEmpty())
        assertTrue("no record for the clock to hold still on", bench.segments.state.value == null)
        assertFalse(bench.parker.pending())
    }

    @Test
    fun `a second HOME press while the task is being named is one park, and the other cases start nothing`() {
        val bench = Bench(session())
        bench.parker.onBottomHome(bench.game, covered = true)

        assertEquals(HomeParkVerdict.PENDING, bench.parker.onBottomHome(bench.game, covered = true))
        assertEquals(1, bench.scheduled.size)

        val idle = Bench(session())
        assertEquals(HomeParkVerdict.NOT_COVERED, idle.parker.onBottomHome(idle.game, covered = false))
        assertEquals(HomeParkVerdict.NOT_ON_BOTTOM, idle.parker.onBottomHome(session(placement = MimirGamePlacement.MAIN), covered = true))
        assertEquals(HomeParkVerdict.NO_GAME, idle.parker.onBottomHome(null, covered = true))
        idle.parked = true
        assertEquals(HomeParkVerdict.ALREADY_PARKED, idle.parker.onBottomHome(idle.game, covered = true))
        assertTrue(idle.scheduled.isEmpty())
        assertFalse(idle.parker.pending())
    }

    // ---- the lease --------------------------------------------------------------------------------------------------------

    @Test
    fun `a HOME lease can be armed for a Bottom game on the display it was found on, and for no other kind of arm`() {
        val game = session()

        assertEquals("the plain arm is for a dual-screen game on Main", MainGameParkingArmResult.NOT_CURRENT_DUAL_SCREEN_GAME, MainGameParkingState.arm(game, 71))
        assertEquals("a Bottom lease names its display", MainGameParkingArmResult.NOT_CURRENT_DUAL_SCREEN_GAME, MainGameParkingState.armForHome(game, 71))
        assertNull(MainGameParkingState.current())

        assertEquals(MainGameParkingArmResult.ARMED, MainGameParkingState.armForHome(game, 71, displayId = 4))
        val lease = requireNotNull(MainGameParkingState.current())
        assertTrue(lease.bottom)
        assertEquals(4, lease.displayId)
        assertTrue(lease.homeParked)
        assertTrue("a Bottom game's tab survives its session, so an unanswered probe lets go like a single-screen one", lease.releasesOnUnansweredGateway)
        assertEquals(lease, MainGameParkingState.currentFor(game))
        assertNull("a Main session never matches a Bottom lease", MainGameParkingState.currentFor(game.copy(placement = MimirGamePlacement.MAIN)))
        assertEquals("the card is told, and offers Resume", listOf<GameId?>(GameId("chrono")), published)
    }

    @Test
    fun `a Main HOME lease is unchanged by the Bottom's`() {
        val main = session(placement = MimirGamePlacement.MAIN, phase = MimirSessionPhase.YIELDED_TO_SECONDARY_TASK)

        assertEquals(MainGameParkingArmResult.ARMED, MainGameParkingState.armForHome(main, 4170))
        val lease = requireNotNull(MainGameParkingState.current())
        assertFalse(lease.bottom)
        assertEquals(0, lease.displayId)
        assertEquals(lease, MainGameParkingState.currentFor(main))
        assertNull("and a Bottom session never matches a Main lease", MainGameParkingState.currentFor(main.copy(placement = MimirGamePlacement.BOTTOM)))
    }

    // ---- Resume -----------------------------------------------------------------------------------------------------------

    private class ResumeBench(var ready: Boolean = true, var reopen: PrivilegedTaskFocus = PrivilegedTaskFocus.FOCUSED) {
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
                PrivilegedTaskFocus.FOCUSED
            },
            afterFronted = { calls += "segment" },
            releaseLease = { calls += "release" },
            endGame = {
                calls += "end"
                true
            },
            bottomReady = {
                calls += "ready"
                ready
            },
            reopenBottom = {
                calls += "reopen"
                reopen
            },
        )

        fun resume(lease: MainGameParkingLease): HomeResumeOutcome = runBlocking { resumeHomeParkedGame(lease, ports) }
    }

    @Test
    fun `Resume of a Bottom game waits for the Bottom, then reopens the game on it - no focus, no mark, and the segment starts when the game is in front`() {
        val bench = ResumeBench()

        assertEquals(HomeResumeOutcome.FRONTED, bench.resume(bottomLease()))

        assertEquals(listOf("ready", "reopen"), bench.calls)
    }

    @Test
    fun `a Bottom reopen the system refuses, or a Bottom still being reclaimed, fronts nothing and keeps the lease for another try`() {
        val refused = ResumeBench(reopen = PrivilegedTaskFocus.FAILED)
        assertEquals(HomeResumeOutcome.FAILED, refused.resume(bottomLease()))
        assertEquals(listOf("ready", "reopen"), refused.calls)

        val busy = ResumeBench(ready = false)
        assertEquals(HomeResumeOutcome.FAILED, busy.resume(bottomLease()))
        assertEquals("the reopen is never tried under an in-flight reclaim", listOf("ready"), busy.calls)
    }

    @Test
    fun `a dual-screen Resume that lands inside the park's reclaim fronts nothing and says to try again - review P2`() {
        val dual = bottomLease().copy(bottom = false, displayId = 0, dualScreen = true, taskId = 4170)

        val busy = ResumeBench(ready = false)
        assertEquals(HomeResumeOutcome.FAILED, busy.resume(dual))
        assertEquals("the mark is given back and the task is never focused under the reclaim", listOf("restoring", "ready", "unmark"), busy.calls)

        val settled = ResumeBench(ready = true)
        assertEquals(HomeResumeOutcome.FRONTED, settled.resume(dual))
        assertEquals(listOf("restoring", "ready", "focus:4170", "segment"), settled.calls)

        val single = ResumeBench(ready = false)
        assertEquals("a single-screen Resume has no Bottom to wait for", HomeResumeOutcome.FRONTED, single.resume(bottomLease().copy(bottom = false, displayId = 0, taskId = 4170)))
        assertEquals(listOf("restoring", "focus:4170", "segment"), single.calls)
    }

    @Test
    fun `Play on the Bottom game that is parked resumes it, a refused one says so by name, and Restart ends it for a fresh launch`() {
        val messages = mutableListOf<Pair<MimirMessageClass, String>>()
        fun press(bench: ResumeBench, restart: Boolean = false) = runBlocking {
            handleHomeParkedPress(
                lease = bottomLease(),
                pressedGame = GameId("chrono"),
                pressedTitle = "Chrono Trigger",
                ports = bench.ports,
                confirm = EndParkedGameConfirm(now = { 0L }),
                titleOf = { "Chrono Trigger" },
                emitMessage = { messageClass, text -> messages += messageClass to text },
                restart = restart,
            )
        }

        val ok = ResumeBench()
        assertEquals(HomeParkedPress.ANSWERED, press(ok))
        assertEquals(listOf("ready", "reopen"), ok.calls)
        assertTrue(messages.isEmpty())

        val refused = ResumeBench(reopen = PrivilegedTaskFocus.FAILED)
        assertEquals(HomeParkedPress.ANSWERED, press(refused))
        assertEquals(
            listOf(MimirMessageClass.Warning to "Couldn’t bring Chrono Trigger back. Try again, or quit it first."),
            messages,
        )

        val restart = ResumeBench()
        assertEquals(HomeParkedPress.ENDED_PARKED_GAME, press(restart, restart = true))
        assertEquals("Restart ends it through the Quit path and never reopens it first", listOf("end"), restart.calls)
    }

    // ---- the watch --------------------------------------------------------------------------------------------------------

    @Test
    fun `the watch holds a Bottom lease on a live task, lets go on proof, and keeps it on an answer it cannot trust`() {
        val lease = bottomLease()
        fun decide(outcome: VendorTaskOutcome, ready: Boolean = true): Pair<Boolean, List<String>> {
            val ended = mutableListOf<String>()
            val released = runBlocking {
                releaseHomeParkedOnProof(
                    lease = lease,
                    resolve = { outcome },
                    gatewayStatus = { if (ready) PrivilegedUtilityStatus.READY else PrivilegedUtilityStatus.SERVICE_STOPPED },
                    release = { _, endedBy ->
                        ended += endedBy
                        true
                    },
                    log = {},
                )
            }
            return released to ended
        }

        assertEquals(false to emptyList<String>(), decide(VendorTaskOutcome.Found(71)))
        assertEquals(true to listOf("parked task gone"), decide(VendorTaskOutcome.NoTask))
        assertEquals("a transient empty answer behind a READY gateway keeps it", false to emptyList<String>(), decide(VendorTaskOutcome.Unavailable("timeout")))
        assertEquals(true to listOf("parked gateway down"), decide(VendorTaskOutcome.Unavailable("timeout"), ready = false))

        // And the watch itself runs for a Bottom lease exactly as for a Main one: it polls while Mimir is up, and returns when the lease is gone.
        var probes = 0
        val watch = ParkedGameExitWatch(
            currentLease = { lease },
            mimirResumed = { true },
            releaseIfGone = {
                probes++
                probes == 3
            },
            pause = {},
        )
        runBlocking { watch.run(lease) }
        assertEquals(3, probes)
    }

    // ---- the wiring the JVM cannot run ------------------------------------------------------------------------------------

    @Test
    fun `the floor asks before it reclaims, the probes ask the lease's display, and Main's resume ignores a Bottom lease`() {
        val floor = sourceFile("CompanionReturnCoordinator.kt")
        assertTrue(floor.contains("val parksBottomGame = runCatching { MainGameHomePark.onBottomFloorHome(disposition) }.getOrDefault(false)"))
        assertTrue(floor.contains("reason = if (parksBottomGame) BottomHomeParkReclaimReason else \"secondary_home_resumed\","))
        assertTrue("edge-back keeps its reason and its ending", floor.indexOf("onBottomFloorHome") < floor.indexOf("explicitReclaim("))

        val park = sourceFile("MainGameHomePark.kt")
        assertTrue(park.contains(".resolveTaskOutcome(lease.targetPackageName, lease.displayId)"))
        assertTrue(park.contains("MainGameParkingState.currentForCurrentSession()?.let { it.homeParked && !it.bottom } == true"))
        assertTrue(park.contains("bottomReady = ::bottomReadyForHomeResume,"))
        assertTrue(park.contains("reopenBottom = ::reopenBottomForHomeResume,"))
        assertTrue(sourceFile("CompanionProcessRuntime.kt").contains("resolveTaskOutcome(lease.targetPackageName, lease.displayId)"))
        assertTrue(sourceFile("CompanionNowPlayingState.kt").contains("MainGameParkingState.current()?.takeIf { it.targetPackageName == pkg }?.displayId"))

        val bridge = sourceFile("MimirSessionCoordinator.kt")
        assertTrue(bridge.contains("if (disposition.lease.reason == BottomHomeParkReclaimReason) {"))
        assertTrue(bridge.contains("if (event == \"HostCovered\") MainGameHomePark.bottomGameFronted(gameOperation)"))
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

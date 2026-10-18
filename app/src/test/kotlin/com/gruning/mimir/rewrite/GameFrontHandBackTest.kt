package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * the way back from a HOME park lets the music go BEFORE the game asks for audio focus, a change of a parking lease
 * (the parked Bottom game's tab tapped) makes the control reconsider, and the hand-back is the app's evidence of "a game is on screen" for the moment between
 * Resume beginning to front the task and Main pausing under it.
 */
class GameFrontHandBackTest {
    private val published = mutableListOf<GameId?>()
    private var leaseChanges = 0

    @Before
    fun cleanProcess() {
        MainGameParkingState.homeParkedPublisher = { published += it }
        MainGameParkingState.current()?.let(MainGameParkingState::clear)
        MainGameParkingState.leaseObserver = { leaseChanges++ }
        MusicGameHandBack.end()
        published.clear()
        leaseChanges = 0
    }

    @After
    fun restoreProcess() {
        MainGameParkingState.current()?.let(MainGameParkingState::clear)
        MainGameParkingState.homeParkedPublisher = com.gruning.mimir.rewrite.feature.library.HomeLaunchCardResume::set
        MainGameParkingState.leaseObserver = {}
        MusicGameHandBack.end()
    }

    private fun session(placement: MimirGamePlacement, phase: MimirSessionPhase = MimirSessionPhase.FOREGROUND_PROVEN) = MimirGameSession(
        operation = MimirGameSessionOperation("game:n3ds"),
        sequence = 1L,
        gameId = GameId("zelda"),
        title = "Zelda",
        platformId = PlatformId.SNES,
        returnAnchor = MimirGameReturnAnchor.Home(HomeLaunchReturnTarget(HomeShelfItemId.Emulated(GameId("zelda")), rank = 0L)),
        startedAtEpochMs = 1L,
        targetPackageName = "io.github.lime3ds.android",
        placement = placement,
        dualScreen = placement == MimirGamePlacement.BOTTOM,
        phase = phase,
    )

    private fun snapshot(game: MimirGameSession) = MimirSessionSnapshot(revision = 1L, gameSession = game)

    // ── The hand-back window ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the hand-back counts for the window and not after it, and Main resuming ends it`() {
        assertFalse("none begun", musicHandBackActive(untilMs = 0L, nowMs = 5L))
        MusicGameHandBack.begin(nowMs = 1_000L)
        assertTrue(MusicGameHandBack.active(nowMs = 1_000L))
        assertTrue(MusicGameHandBack.active(nowMs = 1_000L + MusicHandBackWindowMs - 1L))
        assertFalse("the fronting never happened: the window runs out", MusicGameHandBack.active(nowMs = 1_000L + MusicHandBackWindowMs))
        MusicGameHandBack.begin(nowMs = 1_000L)
        MusicGameHandBack.end()
        assertFalse("ended", MusicGameHandBack.active(nowMs = 1_001L))
    }

    // ── The resume path lets go before the task is fronted ───────────────────────────────────────────────────────────

    private class Bench(
        var restoring: Boolean = true,
        var ready: Boolean = true,
        var focus: PrivilegedTaskFocus = PrivilegedTaskFocus.FOCUSED,
        var reopen: PrivilegedTaskFocus = PrivilegedTaskFocus.FOCUSED,
    ) {
        val calls = mutableListOf<String>()
        val ports = HomeResumePorts(
            beginRestoring = { calls += "restoring"; restoring },
            cancelRestoring = { calls += "unmark"; true },
            focusTask = { calls += "front:$it"; focus },
            afterFronted = { calls += "segment" },
            releaseLease = { calls += "release" },
            endGame = { true },
            bottomReady = { calls += "ready"; ready },
            reopenBottom = { calls += "reopen"; reopen },
            handBackBegin = { calls += "handBackBegin" },
            handBackEnd = { calls += "handBackEnd" },
        )
    }

    private fun mainLease(dual: Boolean = false) = MainGameParkingLease(
        operation = MimirGameSessionOperation("game:n3ds"),
        gameId = GameId("zelda"),
        targetPackageName = "io.github.lime3ds.android",
        taskId = 4170,
        parkedAtElapsedMillis = 1L,
        homeParked = true,
        dualScreen = dual,
    )

    private fun bottomLease() = mainLease(dual = true).copy(bottom = true, displayId = 4)

    @Test
    fun `Resume of a Main game begins the hand-back before the task is fronted, and keeps it once it is`() {
        val bench = Bench()
        val outcome = runBlocking { resumeHomeParkedGame(mainLease(), bench.ports) }
        assertEquals(HomeResumeOutcome.FRONTED, outcome)
        assertEquals(listOf("restoring", "handBackBegin", "front:4170", "segment"), bench.calls)
        assertFalse("a fronted game's hand-back is not ended here: Main pausing takes over", "handBackEnd" in bench.calls)
    }

    @Test
    fun `Resume of a Bottom game begins it before the tab's reopen`() {
        val bench = Bench()
        assertEquals(HomeResumeOutcome.FRONTED, runBlocking { resumeHomeParkedGame(bottomLease(), bench.ports) })
        assertEquals(listOf("ready", "handBackBegin", "reopen"), bench.calls)
    }

    @Test
    fun `a refused fronting ends the hand-back, because the game is not coming up`() {
        val main = Bench(focus = PrivilegedTaskFocus.FAILED)
        assertEquals(HomeResumeOutcome.FAILED, runBlocking { resumeHomeParkedGame(mainLease(), main.ports) })
        assertEquals(listOf("restoring", "handBackBegin", "front:4170", "handBackEnd", "unmark"), main.calls)
        val bottom = Bench(reopen = PrivilegedTaskFocus.FAILED)
        assertEquals(HomeResumeOutcome.FAILED, runBlocking { resumeHomeParkedGame(bottomLease(), bottom.ports) })
        assertEquals(listOf("ready", "handBackBegin", "reopen", "handBackEnd"), bottom.calls)
    }

    @Test
    fun `nothing is handed back while the resume can still refuse before it fronts anything`() {
        val notRestoring = Bench(restoring = false)
        assertEquals(HomeResumeOutcome.FAILED, runBlocking { resumeHomeParkedGame(mainLease(), notRestoring.ports) })
        assertFalse("handBackBegin" in notRestoring.calls)
        val notReady = Bench(ready = false)
        assertEquals(HomeResumeOutcome.FAILED, runBlocking { resumeHomeParkedGame(bottomLease(), notReady.ports) })
        assertFalse("handBackBegin" in notReady.calls)
        val dualNotReady = Bench(ready = false)
        assertEquals(HomeResumeOutcome.FAILED, runBlocking { resumeHomeParkedGame(mainLease(dual = true), dualNotReady.ports) })
        assertFalse("handBackBegin" in dualNotReady.calls)
    }

    @Test
    fun `the production ports begin and end the hand-back and wake the control each time`() {
        val park = source("MainGameHomePark.kt")
        val ports = park.substringAfter("internal fun homeResumePorts(context: Context): HomeResumePorts = HomeResumePorts(").substringBefore("releaseLease = ")
        // The two lambdas are the shared helpers, the same ones the Bottom's Return to a game parked behind the switch button uses.
        assertTrue(ports.contains("handBackBegin = { MusicGameHandBack.beginAndWake() }") && ports.contains("handBackEnd = { MusicGameHandBack.endAndWake() }"))
        val handBack = source("YourMusicEverywhere.kt").substringAfter("internal object MusicGameHandBack {").substringBefore("private val MusicLiveGamePhases")
        val begin = handBack.substringAfter("fun beginAndWake() {").substringBefore("}")
        val end = handBack.substringAfter("fun endAndWake() {").substringBefore("}")
        assertTrue(begin.contains("begin()") && end.contains("end()"))
        assertEquals("woken at both ends", 2, Regex("""MimirMusicControl\.onGameFrontMayHaveChanged\(\)""").findAll(handBack).count())
        // The flag is set before the control is woken, or the pass it posts would read the old answer.
        assertTrue(begin.indexOf("begin()") < begin.indexOf("MimirMusicControl.onGameFrontMayHaveChanged()"))
        assertTrue(end.indexOf("end()") < end.indexOf("MimirMusicControl.onGameFrontMayHaveChanged()"))
        val resume = park.substringAfter("internal suspend fun resumeHomeParkedGame(").substringBefore("The confirmed end of a parked game")
        assertTrue("before anything is fronted", resume.indexOf("ports.handBackBegin(lease)") < resume.indexOf("ports.focusTask(lease.taskId)"))
        assertTrue("and before the tab's reopen", resume.indexOf("ports.handBackBegin(lease)") < resume.indexOf("ports.reopenBottom(lease)"))
        assertEquals("once on each branch", 2, Regex("""ports\.handBackBegin\(lease\)""").findAll(resume).count())
    }

    // ── A change of lease reaches the control ─────────────────────────────────────────────────────────────────────

    @Test
    fun `arming and clearing a lease tell the lease observer, and a clear of nothing does not`() {
        val bottom = session(MimirGamePlacement.BOTTOM)
        assertEquals(MainGameParkingArmResult.ARMED, MainGameParkingState.armForHome(bottom, taskId = 77, displayId = 4))
        assertEquals("a park arms the lease", 1, leaseChanges)
        val lease = requireNotNull(MainGameParkingState.current())
        assertTrue(MainGameParkingState.clear(lease))
        assertEquals("the tab's reopen clears it", 2, leaseChanges)
        assertFalse("a clear of nothing changes nothing", MainGameParkingState.clear(lease))
        assertEquals(2, leaseChanges)
    }

    @Test
    fun `tapping the parked Bottom game's tab turns it from parked to on screen`() {
        val bottom = session(MimirGamePlacement.BOTTOM)
        MainGameParkingState.armForHome(bottom, taskId = 77, displayId = 4)
        val lease = requireNotNull(MainGameParkingState.current())
        val parked = MainGameParkingState.currentFor(bottom) != null
        assertTrue(parked)
        assertFalse("parked by HOME, the Bottom game is not on screen", musicBottomGameOnScreen(snapshot(bottom), bottomParked = parked))
        // The tab is tapped: bottomGameFronted clears the lease. Main never stops, but the observer wakes the control and the evidence flips.
        val before = leaseChanges
        assertTrue(MainGameParkingState.clear(lease))
        assertTrue("the control is told", leaseChanges > before)
        val parkedNow = MainGameParkingState.currentFor(bottom) != null
        assertFalse(parkedNow)
        assertTrue("and now it is on screen", musicBottomGameOnScreen(snapshot(bottom), bottomParked = parkedNow))
        assertNull(MainGameParkingState.current())
    }

    @Test
    fun `the Bottom tab's reopen clears the lease through the observed path`() {
        val park = source("MainGameHomePark.kt")
        val fronted = park.substringAfter("fun bottomGameFronted(operation: MimirGameSessionOperation) {").substringBefore("private fun parkElapsedToken")
        assertTrue(fronted.contains("MainGameParkingState.clear(lease)"))
        val state = source("MainGameParkingState.kt")
        val publish = state.substringAfter("private fun publishHomeParked() {").substringBefore("/**")
        assertTrue(publish.contains("homeParkedPublisher(") && publish.contains("runCatching { leaseObserver() }"))
    }

    private fun source(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText() ?: error("$name not found from ${File(".").absolutePath}")
    }
}

package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RaPointsOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaRecentOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsRecentUnlock
import com.gruning.mimir.rewrite.core.metadata.online.RaUserGamesOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsUserGame
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.RaKeyState
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCardState
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The REAL coordinator ([MimirAchievementsState]) driven end to end: a card opened and dismissed, probes reporting through the real publish gate, the
 * account side changing, the cold-start bulk read and its return refresh. Only the probe's file and network work and the bulk sources are replaced
 * (through [MimirAchievementsSeams]); the fence, the epoch, the slot, the recovery and the loader are the production code. The account, key and
 * opt-in are plain flows and the scope runs unconfined, so everything happens synchronously and no credential or connection is involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class RetroAchievementsCoordinatorEndToEndTest {

    private val account = MutableStateFlow<String?>("e2e-player")
    private val keyState = MutableStateFlow(RaKeyState.ACCEPTED)
    private val optedIn = MutableStateFlow(true)
    private val sessions = MutableStateFlow<String?>(null)
    private var now = 10_000_000L
    private var probeCalls = 0
    private var gameCalls = 0
    private var pointCalls = 0
    private var recentCalls = 0
    private var iconClears = 0
    private var firstBulkGate: CompletableDeferred<Unit>? = null
    private val states = mutableListOf<MimirAchievementsState>()

    private val x = game("x")
    private val y = game("y")

    @Before
    fun reset() = CompanionActivityAchievements.resetForTest()

    @After
    fun closeAll() = states.forEach(MimirAchievementsState::close)

    private fun game(id: String) =
        GameRow(gameId = GameId(id), title = "Game $id", platformId = PlatformId("snes"), memberCount = 1, duplicateKind = null)

    private fun loaded(unlocked: Int) = MimirAchievementLaneState.Loaded(
        RetroAchievementsProgress(
            gameId = 9,
            title = "Game 9",
            achievements = (1..10).map { RetroAchievementsAchievement(it, "A$it", "a", 5, unlocked = it <= unlocked, unlockedHardcore = false) },
        ),
    )

    private fun state(work: MimirAchievementProbeWork? = null): MimirAchievementsState =
        MimirAchievementsState(
            RuntimeEnvironment.getApplication(),
            MimirAchievementsSeams(
                account = account,
                keyState = keyState,
                optedIn = optedIn,
                sessionKeys = sessions,
                scopeContext = Dispatchers.Unconfined,
                probeWork = work,
                bulkGames = {
                    gameCalls += 1
                    if (gameCalls == 1) firstBulkGate?.await() // held only by the test that wants the first load in flight
                    RaUserGamesOutcome.Read(listOf(RetroAchievementsUserGame(1, "Game 9", "Super Nintendo Entertainment System", 4, 10)), 1)
                },
                bulkPoints = {
                    pointCalls += 1
                    RaPointsOutcome.Read(777)
                },
                bulkRecent = {
                    recentCalls += 1
                    RaRecentOutcome.Read(listOf(RetroAchievementsRecentUnlock(7, "First blood", "Win once", 5, "00007", 1, "Game 9", "SNES", 1_700_000_000_000L, false)))
                },
                bulkLibrary = { listOf(LocalGameKey("x", "snes", "Game 9")) },
                iconClear = { iconClears += 1 },
                bulkContext = EmptyCoroutineContext,
                publishContext = EmptyCoroutineContext,
                clock = { now },
            ),
        ).also { states += it }

    /** A probe that answers [unlocked] immediately, counting its call. */
    private fun answering(unlocked: Int): MimirAchievementProbeWork = { _, report ->
        probeCalls += 1
        report(loaded(unlocked), null)
    }

    // Main: no ambient probing -------------------------------------------------------------------------------------------------------

    @Test
    fun `focus changes and every read a recomposition makes ask RetroAchievements for nothing`() {
        val state = state(answering(3))
        // What Main does while the user moves focus: read the hero's label and the card state for whatever game is focused. Nothing probes.
        repeat(50) {
            assertNull(state.progressLabelFor(if (it % 2 == 0) x else y))
            assertNull(state.laneStateFor("x"))
            state.cardState()
        }
        assertEquals(0, probeCalls)
        assertEquals(0, gameCalls)
        assertEquals(0, pointCalls)
    }

    @Test
    fun `Main's sources carry no focus probe and the launch card owns the only one`() {
        val host = source("MimirAppHost.kt")
        assertFalse(host.contains("homeRaProbeGame"))
        assertFalse(host.contains("HomeAchievementProbeSettleMs"))
        assertFalse(host.contains("achievementCount = homeAchievementCount"))
        assertTrue(host.contains("LaunchedEffect(game?.gameId?.value) { game?.let { cardAchievements.probe(it) } }"))
    }

    // The card: exactly one call, fenced, recovering ---------------------------------------------------------------------------------

    @Test
    fun `opening the card makes exactly one probe call and shows its answer`() = runBlocking {
        val state = state(answering(4))
        state.openCardFromHome(x)
        assertEquals(0, probeCalls) // opening only claims the request; the card's effect asks
        state.probeFromHome(state.homeProbeGame!!, state.homeProbeEpoch)

        assertEquals(1, probeCalls)
        assertEquals("4/10", state.progressLabelFor(x))
        assertTrue(state.cardState() is MimirAchievementCardState.Loaded)
    }

    @Test
    fun `back before the answer re-issues the probe so the game resolves without a refocus`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val state = state { _, report ->
            probeCalls += 1
            if (probeCalls == 1) gate.await() // the card's own probe never answers in time
            report(loaded(2), null)
        }
        state.openCardFromHome(x) // Checking, and no settled answer to fall back on
        val cardProbe = launch(start = CoroutineStart.UNDISPATCHED) { state.probeFromHome(x, state.homeProbeEpoch) }
        assertEquals(1, probeCalls)
        assertEquals(MimirAchievementLaneState.Unresolved(MimirAchievementUnresolvedReason.CHECKING), state.laneStateFor("x"))

        state.dismissCard() // Back before the answer
        assertEquals(2, probeCalls) // the ambient probe was asked again, and it superseded the card's
        assertTrue(cardProbe.isCancelled)
        assertEquals("2/10", state.progressLabelFor(x))
    }

    @Test
    fun `back before the answer restores the last settled answer when one exists`() = runBlocking {
        var checking = false
        val hold = CompletableDeferred<Unit>()
        val state = state { _, report ->
            probeCalls += 1
            if (checking) {
                report(retroAchievementsCheckingState(), null)
                hold.await()
            } else {
                report(loaded(6), null)
            }
        }
        state.probe(x) // the launch card's read settled the game at 6/10
        assertEquals("6/10", state.progressLabelFor(x))

        // The launch card is read again and is still Checking when the card opens and then goes away.
        checking = true
        val asking = launch(start = CoroutineStart.UNDISPATCHED) { state.probe(x) }
        state.openCardFromHome(x)
        assertEquals(MimirAchievementLaneState.Unresolved(MimirAchievementUnresolvedReason.CHECKING), state.laneStateFor("x"))
        val callsBefore = probeCalls

        state.dismissCard()

        assertEquals("6/10", state.progressLabelFor(x))
        assertEquals("a restore asks nothing", callsBefore, probeCalls)
        asking.cancel()
    }

    @Test
    fun `a superseded probe's late answer is dropped by the real gate`() = runBlocking {
        val lateGate = CompletableDeferred<Unit>()
        val state = state { game, report ->
            probeCalls += 1
            if (probeCalls == 1) {
                // The first probe ignores cancellation, like a dependency that swallows it.
                withContext(NonCancellable) {
                    lateGate.await()
                    report(loaded(1), null) // reaches the real gate even though its job was cancelled
                }
            } else {
                report(loaded(8), null)
            }
        }
        val first = launch(start = CoroutineStart.UNDISPATCHED) { state.probe(x) }
        state.openCardFromHome(x) // supersedes it
        state.probeFromHome(state.homeProbeGame!!, state.homeProbeEpoch)
        assertEquals("8/10", state.progressLabelFor(x))
        lateGate.complete(Unit)
        first.join()
        assertEquals("the late answer from the superseded probe must not overwrite the newer one", "8/10", state.progressLabelFor(x))
    }

    @Test
    fun `a rejected key reaches the open card without a reopen and a stale success is still dropped`() = runBlocking {
        val state = state { _, report ->
            probeCalls += 1
            // RetroAchievements refuses the current key: the store marks it rejected (which bumps the account epoch and clears the slot) and the
            // probe that detected it then reports the rejection.
            keyState.value = RaKeyState.REJECTED_THIS_SESSION
            report(retroAchievementsRejectedState(), null)
            report(loaded(9), null) // a success from before the change: stale, must never publish
        }
        state.openCardFromHome(x)
        state.probeFromHome(x, state.homeProbeEpoch)

        assertEquals(
            MimirAchievementCardState.Unknown(MimirAchievementCopy.CredentialsRejected),
            state.cardState(),
        )
        assertNull(state.progressLabelFor(x))
    }

    @Test
    fun `an account change clears the slot and the old account's data and loads the new account`() = runBlocking {
        val state = state(answering(5))
        state.probe(x)
        assertEquals("5/10", state.progressLabelFor(x))
        CompanionActivityAchievements.publishBulk("e2e-player", emptyList(), 100)
        assertNotNull(CompanionActivityAchievements.points.value)

        account.value = "someone-else"

        assertNull("the old account's count must not stand under the new account", state.progressLabelFor(x))
        assertNull(CompanionActivityAchievements.points.value?.takeIf { it.account == "e2e-player" })
        assertEquals("the new account loads its own bulk answer", 1, gameCalls)
        assertEquals(1, pointCalls)
    }

    // The Activity bulk ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `cold start makes exactly one bulk load and one points load`() = runBlocking {
        val state = state()
        assertEquals(0, gameCalls) // constructing the lane asks nothing
        state.startActivityBulk()

        assertEquals(1, recentCalls)
        assertEquals(1, gameCalls)
        assertEquals(1, pointCalls)
        val view = companionActivityAchievementHubView(
            CompanionActivityAchievements.snapshots.value, true, "e2e-player", RaKeyState.ACCEPTED, CompanionActivityAchievements.points.value,
        )
        assertEquals(1, view.snapshots.size)
        assertEquals("x", view.snapshots.single().gameId) // matched to the library
        assertEquals(777, view.totalPoints)
        assertEquals("the strip's unlocks came with the same load", listOf(7), CompanionActivityAchievements.recent.value?.unlocks?.map { it.achievementId })
    }

    @Test
    fun `a sign-out or an opt-out empties the icon folder and a sign-in does not`() {
        state()
        account.value = "someone-else" // an account switch while connected keeps the public icons
        assertEquals(0, iconClears)
        optedIn.value = false
        assertEquals(1, iconClears)
        optedIn.value = true
        assertEquals(1, iconClears) // opting back in clears nothing
        account.value = null
        assertEquals(2, iconClears)
    }

    @Test
    fun `cold start asks nothing when RetroAchievements is not opted in`() {
        optedIn.value = false
        state().startActivityBulk()
        assertEquals(0, gameCalls)
        assertEquals(0, pointCalls)
    }

    @Test
    fun `cold start asks nothing when no account is connected`() {
        account.value = null
        state().startActivityBulk()
        assertEquals(0, gameCalls)
        assertEquals(0, pointCalls)
    }

    @Test
    fun `cold start asks nothing when the key was refused`() {
        keyState.value = RaKeyState.REJECTED_THIS_SESSION
        state().startActivityBulk()
        assertEquals(0, gameCalls)
        assertEquals(0, pointCalls)
    }

    @Test
    fun `a returned game session refreshes the bulk read once per five minutes`() {
        val state = state()
        state.startActivityBulk()
        assertEquals(1, gameCalls)

        sessions.value = retroAchievementsSessionKey("op", MimirSessionPhase.DISPATCHED) // a launch refreshes nothing
        sessions.value = retroAchievementsSessionKey("op", MimirSessionPhase.RETURNING)
        assertEquals("inside the throttle", 1, gameCalls)

        now += 5L * 60_000L
        sessions.value = retroAchievementsSessionKey("op2", MimirSessionPhase.DISPATCHED)
        sessions.value = retroAchievementsSessionKey("op2", MimirSessionPhase.RETURNING)
        assertEquals(2, gameCalls)
        assertEquals(2, pointCalls)
        assertEquals("one recent-unlocks read per load, none inside the throttle", 2, recentCalls)
    }

    @Test
    fun `coming back to Mimir after a game played outside it refreshes the bulk read, through the same five-minute window`() {
        val state = state()
        state.startActivityBulk()
        assertEquals(1, gameCalls)

        now += 10L * 60_000L
        state.onHostPaused() // the emulator's own icon was tapped: Mimir is out of front
        now += 3L * 60_000L
        state.onHostResumed()
        assertEquals("the page is refreshed for the play it did not hear", 2, gameCalls)
        assertEquals(2, pointCalls)

        // Away again for three minutes, but the read just made holds the window: the same throttle as a return.
        state.onHostPaused()
        now += 3L * 60_000L
        state.onHostResumed()
        assertEquals("inside the window nothing more is read now", 2, gameCalls)
    }

    @Test
    fun `a pause shorter than two minutes is not a game played elsewhere`() {
        val state = state()
        state.startActivityBulk()
        now += 10L * 60_000L
        state.onHostPaused()
        now += 2L * 60_000L - 1L
        state.onHostResumed()
        assertEquals(1, gameCalls)
    }

    @Test
    fun `coming back to Mimir while a Mimir-launched game is still running refreshes nothing, and once it is over it does`() {
        val state = state()
        state.startActivityBulk()
        now += 10L * 60_000L

        sessions.value = retroAchievementsSessionKey("op", MimirSessionPhase.DISPATCHED)
        state.onHostPaused()
        now += 3L * 60_000L
        state.onHostResumed()
        assertEquals("that game's own return is what refreshes the page", 1, gameCalls)

        sessions.value = retroAchievementsSessionKey("op", MimirSessionPhase.ENDED) // that session's own return reads the page
        val before = gameCalls
        now += 10L * 60_000L
        state.onHostPaused()
        now += 3L * 60_000L
        state.onHostResumed()
        assertTrue("with the session over, an absence is a game played elsewhere", gameCalls > before)
    }

    @Test
    fun `coming back to Mimir asks nothing when RetroAchievements is not connected`() {
        val state = state()
        state.startActivityBulk()
        optedIn.value = false
        val before = gameCalls
        now += 10L * 60_000L
        state.onHostPaused()
        now += 3L * 60_000L
        state.onHostResumed()
        assertEquals(before, gameCalls)
    }

    @Test
    fun `an account change while the first bulk load is in flight cancels it and ends with the new account loaded`() {
        firstBulkGate = CompletableDeferred()
        val state = state()
        state.startActivityBulk() // the old account's load, held in flight
        assertEquals(1, gameCalls)

        account.value = "someone-else"

        assertEquals("the new account's load ran instead of being turned away as busy", 2, gameCalls)
        assertEquals(1, pointCalls)
        val snapshots = CompanionActivityAchievements.snapshots.value
        assertEquals(1, snapshots.size)
        assertEquals("someone-else", snapshots.single().account)
        firstBulkGate?.complete(Unit)
    }

    @Test
    fun `a closed lane no longer reacts to the process flows`() {
        val state = state()
        state.close()
        account.value = "someone-else"
        sessions.value = retroAchievementsSessionKey("op", MimirSessionPhase.RETURNING)
        assertEquals(0, gameCalls)
    }

    // ---------------------------------------------------------------------------------------------------------------------------------

    private fun source(name: String): String {
        val dir = "src/main/kotlin/com/gruning/mimir/rewrite/"
        val candidates = listOf(File("app/$dir$name"), File("$dir$name"))
        return candidates.firstOrNull(File::isFile)?.readText() ?: error("$name not found from ${File(".").absolutePath}")
    }
}

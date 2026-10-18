package com.gruning.mimir.rewrite

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
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
import com.gruning.mimir.rewrite.feature.files.FileBrowserController
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * RC-06 (device P2, Thor and Odin): the dock's Files seat toasted "OPEN MIMIR TO BROWSE FILES" on a long-lived
 * MainActivity and worked again only after the process restarted.
 *
 * Two faults met in `AppBrightnessCoordinator.openFilesManager()`. The route read a MIRROR of "a game is running"
 * (`gameRunning`) that is written only when Utility opens and by a composed Utility host, so a mirror written
 * while a game ran stayed true after the game ended and the dock, which never composes Utility, read it as fresh.
 * And what wrote it was `MimirSessionAuthority.state.value.gameSession != null`, but the ledger never clears a
 * session: `completeGameReturn` leaves it in ENDED (or FAILED) until the next launch replaces it, so after the
 * first game of the process every refresh answered "a game is running" for good.
 *
 * The first test pins the stale mirror through the dock's own entry point, with no Utility open after the game.
 * The rest pin the predicate: a finished session is not a running game, and a live one still withdraws the row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = Config.NONE,
    sdk = [30],
    application = android.app.Application::class,
    shadows = [ShadowAllFilesAccess::class],
)
class FilesDockAfterGameEndsTest {

    @Before
    fun setUp() {
        TweakerBridge.initialize(RuntimeEnvironment.getApplication())
        ShadowAllFilesAccess.granted = true
        FileBrowserStore.close()
        MainGameLedgerFixture.endAnyLive()
    }

    @After
    fun tearDown() {
        MainGameLedgerFixture.endAnyLive()
        FileBrowserStore.close()
        ShadowAllFilesAccess.granted = false
    }

    @Test
    fun aMirrorLeftTrueByAFinishedGameDoesNotRefuseTheDockFilesSeat() {
        val coordinator = coordinator()
        coordinator.refreshOnResume()
        assertTrue("precondition: all-files access is held", coordinator.allFilesAccessGranted)

        // A game ran while Utility was open (or composed): the host's mirror says so.
        val operation = MainGameLedgerFixture.start()
        Snapshot.withMutableSnapshot { coordinator.gameRunning = true }
        assertNotNull(MimirSessionAuthority.state.value.gameSession)

        // The game ends. Utility is never opened again, so nothing refreshes the mirror.
        MainGameLedgerFixture.end(operation)
        assertEquals(
            "premise: the ledger keeps the finished session",
            MimirSessionPhase.ENDED,
            MimirSessionAuthority.state.value.gameSession?.phase,
        )

        // The dock calls exactly this (MimirNavigationCoordinator, MimirQuickRailAction.FILES).
        coordinator.openFilesManager()

        assertNotNull(
            "no game is running: the dock's Files seat opens the manager, not the OPEN MIMIR toast",
            awaitBrowserMount(),
        )
    }

    @Test
    fun aFinishedSessionIsNotARunningGame() {
        val operation = MainGameLedgerFixture.start()
        assertTrue(
            "launched and dispatched: the game holds the screen",
            filesRouteGameRunning(MimirSessionAuthority.state.value),
        )

        MainGameLedgerFixture.end(operation)

        val finished = MimirSessionAuthority.state.value
        assertNotNull("the ledger keeps the finished session", finished.gameSession)
        assertFalse(
            "ENDED is over: the Files row and the dock seat come back",
            filesRouteGameRunning(finished),
        )
        assertFalse("nothing at all: not running", filesRouteGameRunning(MimirSessionSnapshot()))
    }

    @Test
    fun aPressRefreshesTheMirrorFromTheLedgerSoALiveGameStillWithdrawsTheSeat() {
        val coordinator = coordinator()
        coordinator.refreshOnResume()
        val operation = MainGameLedgerFixture.start()
        assertFalse("the mirror has not heard of the game yet", coordinator.gameRunning)

        coordinator.openFilesManager()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull("R2: a game is running, so the press opens no browser", FileBrowserStore.controller.value)
        assertTrue("and the press brought the mirror up to date", coordinator.gameRunning)
        assertFalse(coordinator.filesRouteOffered)

        MainGameLedgerFixture.end(operation)
        coordinator.openFilesManager()

        assertNotNull("the game ended: the same seat opens the manager", awaitBrowserMount())
        assertFalse(coordinator.gameRunning)
        assertTrue(coordinator.filesRouteOffered)
    }

    @Test
    fun theHostReadsTheLedgerInCompositionAndTheCoordinatorUsesTheOnePredicate() {
        val coordinatorSource = appSource("AppBrightnessCoordinator.kt").readText()
        assertFalse(
            "no site may call a session running because the ledger holds one: finished sessions stay in it",
            coordinatorSource.contains("gameSession != null"),
        )
        val host = coordinatorSource.substringAfter("internal fun AppBrightnessCoordinatorHost(")
        val read = host.lineSequence().indexOfFirst { it.contains("filesRouteGameRunning(sessionSnapshot)") }
        val write = host.lineSequence().indexOfFirst { it.contains("SideEffect { coordinator.gameRunning =") }
        assertTrue("the host derives the flag from the collected snapshot", read >= 0)
        assertTrue("and writes the derived value, not a read made inside the effect", write > read)
    }

    private fun coordinator() = AppBrightnessCoordinator(
        context = RuntimeEnvironment.getApplication(),
        controller = null,
        authority = DefaultFocusAuthority(),
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        hostsInAppSurfaces = true,
    )

    /** As `UtilityFilesRouteTest.awaitBrowserMount`: `FileBrowserStore.open()` mounts asynchronously, so drain Main and wait on Room's executor. */
    private fun awaitBrowserMount(timeoutMs: Long = 5_000L): FileBrowserController? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            FileBrowserStore.controller.value?.let { return it }
            Thread.sleep(5L)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return FileBrowserStore.controller.value
    }

    private fun appSource(fileName: String): java.io.File = listOf(
        java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        java.io.File("app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        java.io.File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
    ).firstOrNull { it.isFile }
        ?: throw AssertionError("$fileName not reachable from the test working directory")
}

/**
 * A real Main game in the process-wide session ledger, the way Home launches one (the fixture
 * `CompanionRuntimeCoordinatorTest` uses for the Bottom). The ledger is a singleton shared with every class in the JVM, so
 * a test that starts a game ends it, and [endAnyLive] clears what an earlier class left behind.
 */
internal object MainGameLedgerFixture {
    private val game = GameRow(
        gameId = GameId("chrono"),
        title = "Chrono Trigger",
        platformId = PlatformId.SNES,
        memberCount = 1,
        duplicateKind = null,
    )

    fun start(): MimirGameSessionOperation {
        val operation = requireNotNull(
            MimirSessionAuthority.prepareHomeGame(
                game = game,
                returnTarget = HomeLaunchReturnTarget(HomeShelfItemId.Emulated(game.gameId), rank = 0L),
                placement = MimirGamePlacement.MAIN,
            ),
        )
        val dispatched = LaunchResult(
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
        assertTrue(MimirSessionAuthority.settleHomeGame(operation, game, dispatched))
        return operation
    }

    /** The live game ends through the ledger's own return; the ledger keeps the ENDED session. */
    fun end(operation: MimirGameSessionOperation) {
        assertNotNull(
            "the live game ends through the ledger's own return",
            MimirSessionAuthority.endGameForTabClose("com.retroarch", operation),
        )
    }

    fun endAnyLive() {
        val live = MimirSessionAuthority.state.value.gameSession ?: return
        if (live.phase !in ActivePresentationPhases) return
        MimirSessionAuthority.beginGameReturn(live.operation)
        MimirSessionAuthority.completeGameReturn(live.operation, foregroundProven = live.foregroundProven)
    }
}

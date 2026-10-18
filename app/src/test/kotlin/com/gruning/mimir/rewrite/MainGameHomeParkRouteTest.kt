package com.gruning.mimir.rewrite

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F3: the HOME router offers the park to the platform on `not_in_front` and on nothing else, so a press with Mimir in front
 * still goes Home, an internal start is still ignored, and the Bottom's floor never parks a Main game.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = Application::class)
class MainGameHomeParkRouteTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun readTheLeaveClockFromHere() {
        UserLeaveSignal.reset()
        UserLeaveSignal.clock = { 10_400L }
    }

    @After
    fun putTheLeaveClockBack() {
        UserLeaveSignal.reset()
        UserLeaveSignal.clock = SystemClock::elapsedRealtime
    }

    private class ParkRecordingPlatform(
        var focused: Boolean = true,
        override val source: MainHomeKeySource = MainHomeKeySource.MAIN,
        var sessions: MimirSessionSnapshot = MimirSessionSnapshot(),
    ) : MainHomeKeyPlatform {
        val parked = mutableListOf<MainHomeKeyDecision>()
        var published = 0
        override fun startedBeforeIntent() = true
        override fun hadWindowFocusBeforeIntent() = focused
        override fun onMainDisplay() = true
        override fun firstRun() = false
        override fun sessions() = sessions
        override fun publishHome() {
            published++
        }
        override fun log(line: String) {}
        override fun parkGameForHome(decision: MainHomeKeyDecision) {
            parked += decision
        }
    }

    private fun systemHomePress() = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_HOME)
        .setComponent(ComponentName(context.packageName, "${context.packageName}.MainActivity"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    @Test
    fun `a HOME press that finds something else holding the screen is offered to the park`() {
        val platform = ParkRecordingPlatform(focused = false)

        val decision = routeMainHomeKeyIntent(systemHomePress(), platform)

        assertEquals(MainHomeKeyDecision.NOT_IN_FRONT, decision)
        assertEquals(listOf(MainHomeKeyDecision.NOT_IN_FRONT), platform.parked)
        assertEquals("the router itself still does nothing for this press", 0, platform.published)
    }

    @Test
    fun `a HOME press with Mimir in front goes Home and is not offered to the park`() {
        val platform = ParkRecordingPlatform(focused = true)

        assertEquals(MainHomeKeyDecision.RETURN_HOME, routeMainHomeKeyIntent(systemHomePress(), platform))

        assertTrue(platform.parked.isEmpty())
        assertEquals(1, platform.published)
    }

    @Test
    fun `Mimir's own HOME-shaped start and an intent that is not HOME are never offered`() {
        val platform = ParkRecordingPlatform(focused = false)

        assertEquals(
            MainHomeKeyDecision.INTERNAL_START,
            routeMainHomeKeyIntent(systemHomePress().putExtra(InternalHomeStartExtra, true), platform),
        )
        assertEquals(
            MainHomeKeyDecision.NOT_HOME_INTENT,
            routeMainHomeKeyIntent(Intent(Intent.ACTION_VIEW), platform),
        )

        assertTrue(platform.parked.isEmpty())
    }

    private fun dispatchedGame() = MimirGameSession(
        operation = MimirGameSessionOperation("game:psx"),
        sequence = 1L,
        gameId = GameId("xenogears"),
        platformId = PlatformId.PS1,
        returnAnchor = MimirGameReturnAnchor.Home(
            HomeLaunchReturnTarget(HomeShelfItemId.Emulated(GameId("xenogears")), rank = 0L),
        ),
        startedAtEpochMs = 1L,
        dispatchedAtElapsedMs = 10_000L,
        targetPackageName = "com.github.stenzek.duckstation",
        dualScreen = false,
        phase = MimirSessionPhase.DISPATCHED,
    )

    @Test
    fun `a user HOME press is a deliberate leave whatever the router decides, including during a launch`() {
        val game = dispatchedGame()
        listOf(
            ParkRecordingPlatform(focused = true) to MainHomeKeyDecision.RETURN_HOME,
            ParkRecordingPlatform(focused = false) to MainHomeKeyDecision.NOT_IN_FRONT,
            ParkRecordingPlatform(focused = true, sessions = MimirSessionSnapshot(gameSession = game)) to MainHomeKeyDecision.HAND_OFF,
        ).forEach { (platform, expected) ->
            UserLeaveSignal.reset()
            assertFalse(UserLeaveSignal.leftSince(10_000L))

            assertEquals(expected, routeMainHomeKeyIntent(systemHomePress(), platform))

            assertTrue(expected.token, UserLeaveSignal.leftSince(10_000L))
            assertTrue("the emulator-closed message is withheld for this launch", LaunchEarlyClose.userLeftOnPurpose(game))
        }
    }

    @Test
    fun `Mimir's own HOME-shaped start and an intent that is not HOME are not a leave`() {
        val platform = ParkRecordingPlatform(focused = false)

        routeMainHomeKeyIntent(systemHomePress().putExtra(InternalHomeStartExtra, true), platform)
        routeMainHomeKeyIntent(Intent(Intent.ACTION_VIEW), platform)

        assertFalse(UserLeaveSignal.leftSince(null))
    }
}

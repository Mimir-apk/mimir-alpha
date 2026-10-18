package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.gruning.mimir.rewrite.feature.companion.CompanionQuickLaunchDestination

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class CompanionQuickLaunchChoiceStateTest {
    @After
    fun clearChoice() {
        CompanionQuickLaunchChoiceState.clear()
    }

    @Test
    fun `missing preference opens one chooser and double tap is ignored`() {
        assertTrue(
            routeSecondaryQuickLaunch(
                packageName = "com.example.choice",
                label = "Choice",
                onPrimary = { _, _ -> false },
                onSecondary = { _, _ -> false },
            ),
        )
        assertFalse(
            routeSecondaryQuickLaunch(
                packageName = "com.example.choice",
                label = "Choice",
                onPrimary = { _, _ -> error("stale choice dispatched") },
                onSecondary = { _, _ -> error("stale choice dispatched") },
            ),
        )
        assertTrue(CompanionQuickLaunchChoiceState.pending.value != null)
    }

    @Test
    fun `missing pinned preference retains pinned launch origin in the chooser token`() {
        assertTrue(
            routeSecondaryQuickLaunch(
                packageName = "com.example.pinned-choice",
                label = "Pinned choice",
                fromGeneral = false,
                onPrimary = { _, _ -> false },
                onSecondary = { _, _ -> false },
            ),
        )
        assertFalse(CompanionQuickLaunchChoiceState.pending.value!!.fromGeneral)
    }

    @Test
    fun `dismissing first-use choice launches nothing and saves nothing`() {
        val context = RuntimeEnvironment.getApplication()
        val packageName = "com.example.cancelled-choice"
        QuickLaunchStore.clearDisplayPreference(context, packageName)
        var dispatches = 0
        assertTrue(
            routeSecondaryQuickLaunch(
                packageName = packageName,
                label = "Cancelled",
                onPrimary = { _, _ -> dispatches++; true },
                onSecondary = { _, _ -> dispatches++; true },
            ),
        )
        val choice = CompanionQuickLaunchChoiceState.pending.value!!
        assertTrue(CompanionQuickLaunchChoiceState.dismiss(choice))
        assertNull(QuickLaunchStore.displayPreference(packageName))
        assertEquals(0, dispatches)
    }

    @Test
    fun `stale request token cannot claim a newer choice`() {
        assertTrue(CompanionQuickLaunchChoiceState.request("com.example.one", "One"))
        val first = CompanionQuickLaunchChoiceState.pending.value!!
        assertTrue(CompanionQuickLaunchChoiceState.dismiss(first))
        assertTrue(CompanionQuickLaunchChoiceState.request("com.example.two", "Two"))
        assertFalse(CompanionQuickLaunchChoiceState.claim(first))
        assertTrue(CompanionQuickLaunchChoiceState.pending.value?.packageName == "com.example.two")
    }

    @Test
    fun `editing changes the saved destination without dispatching`() {
        val context = RuntimeEnvironment.getApplication()
        val packageName = "com.example.editing"
        QuickLaunchStore.clearDisplayPreference(context, packageName)
        assertTrue(CompanionQuickLaunchChoiceState.request(packageName, "Editing", editing = true))
        val choice = CompanionQuickLaunchChoiceState.pending.value!!
        assertFalse("editing destination choices do not launch an app", CompanionQuickLaunchChoiceState.requestCoverWarning(choice))
        var dispatches = 0
        assertTrue(
            settleSecondaryQuickLaunchChoice(
                context = context,
                choice = choice,
                destination = CompanionQuickLaunchDestination.MAIN,
                onPrimary = { _, _ -> dispatches++; true },
                onSecondary = { _, _ -> dispatches++; true },
            ),
        )
        assertEquals(0, dispatches)
        assertEquals(QuickLaunchDisplayRole.PRIMARY, QuickLaunchStore.displayPreference(packageName))
        QuickLaunchStore.clearDisplayPreference(context, packageName)
    }

    @Test
    fun `first choice dispatches once and saves the role`() {
        val context = RuntimeEnvironment.getApplication()
        val packageName = "com.example.first-choice"
        QuickLaunchStore.clearDisplayPreference(context, packageName)
        assertTrue(CompanionQuickLaunchChoiceState.request(packageName, "First", editing = false))
        val choice = CompanionQuickLaunchChoiceState.pending.value!!
        var dispatches = 0
        assertTrue(
            settleSecondaryQuickLaunchChoice(
                context = context,
                choice = choice,
                destination = CompanionQuickLaunchDestination.BOTTOM,
                onPrimary = { _, _ -> dispatches++; true },
                onSecondary = { _, _ -> dispatches++; true },
            ),
        )
        assertEquals(1, dispatches)
        assertEquals(QuickLaunchDisplayRole.SECONDARY, QuickLaunchStore.displayPreference(packageName))
        assertFalse(
            settleSecondaryQuickLaunchChoice(
                context = context,
                choice = choice,
                destination = CompanionQuickLaunchDestination.BOTTOM,
                onPrimary = { _, _ -> dispatches++; true },
                onSecondary = { _, _ -> dispatches++; true },
            ),
        )
        assertEquals(1, dispatches)
        QuickLaunchStore.clearDisplayPreference(context, packageName)
    }

    @Test
    fun `saved bottom warning cancel keeps bottom and continue dispatches`() {
        val context = RuntimeEnvironment.getApplication()
        val packageName = "com.example.saved-bottom-warning"
        QuickLaunchStore.setDisplayPreference(context, packageName, QuickLaunchDisplayRole.SECONDARY)
        var dispatches = 0

        assertTrue(CompanionQuickLaunchChoiceState.request(packageName, "Saved Bottom", coversMainGame = true))
        val cancelled = CompanionQuickLaunchChoiceState.pending.value!!
        assertTrue(CompanionQuickLaunchChoiceState.dismiss(cancelled))
        assertEquals(QuickLaunchDisplayRole.SECONDARY, QuickLaunchStore.displayPreference(packageName))
        assertEquals(0, dispatches)

        assertTrue(CompanionQuickLaunchChoiceState.request(packageName, "Saved Bottom", coversMainGame = true))
        val continued = CompanionQuickLaunchChoiceState.pending.value!!
        assertTrue(
            settleSecondaryQuickLaunchChoice(
                context = context,
                choice = continued,
                destination = CompanionQuickLaunchDestination.BOTTOM,
                onPrimary = { _, _ -> error("wrong destination dispatched") },
                onSecondary = { _, _ -> dispatches++; true },
            ),
        )
        assertEquals(QuickLaunchDisplayRole.SECONDARY, QuickLaunchStore.displayPreference(packageName))
        assertEquals(1, dispatches)
        QuickLaunchStore.clearDisplayPreference(context, packageName)
    }

    @Test
    fun `ask bottom warning cancel stays ask and continue saves bottom`() {
        val context = RuntimeEnvironment.getApplication()
        val packageName = "com.example.ask-bottom-warning"
        QuickLaunchStore.clearDisplayPreference(context, packageName)
        var dispatches = 0

        assertTrue(CompanionQuickLaunchChoiceState.request(packageName, "Ask Bottom", coversMainGame = true))
        val cancelled = CompanionQuickLaunchChoiceState.pending.value!!
        assertTrue(CompanionQuickLaunchChoiceState.dismiss(cancelled))
        assertNull(QuickLaunchStore.displayPreference(packageName))
        assertEquals(0, dispatches)

        assertTrue(CompanionQuickLaunchChoiceState.request(packageName, "Ask Bottom", coversMainGame = true))
        val continued = CompanionQuickLaunchChoiceState.pending.value!!
        assertTrue(
            settleSecondaryQuickLaunchChoice(
                context = context,
                choice = continued,
                destination = CompanionQuickLaunchDestination.BOTTOM,
                onPrimary = { _, _ -> error("wrong destination dispatched") },
                onSecondary = { _, _ -> dispatches++; true },
            ),
        )
        assertEquals(QuickLaunchDisplayRole.SECONDARY, QuickLaunchStore.displayPreference(packageName))
        assertEquals(1, dispatches)
        QuickLaunchStore.clearDisplayPreference(context, packageName)
    }

    @Test
    fun `rejected main dispatch keeps the explicit main choice`() {
        val context = RuntimeEnvironment.getApplication()
        val packageName = "com.example.rejected-main-choice"
        QuickLaunchStore.clearDisplayPreference(context, packageName)
        assertTrue(CompanionQuickLaunchChoiceState.request(packageName, "Rejected main"))
        val choice = CompanionQuickLaunchChoiceState.pending.value!!
        var dispatches = 0
        assertFalse(
            settleSecondaryQuickLaunchChoice(
                context = context,
                choice = choice,
                destination = CompanionQuickLaunchDestination.MAIN,
                onPrimary = { _, _ -> dispatches++; false },
                onSecondary = { _, _ -> error("wrong destination dispatched") },
            ),
        )
        assertEquals(1, dispatches)
        assertEquals(QuickLaunchDisplayRole.PRIMARY, QuickLaunchStore.displayPreference(packageName))
        QuickLaunchStore.clearDisplayPreference(context, packageName)
    }

    @Test
    fun `rejected bottom dispatch keeps the explicit bottom choice`() {
        val context = RuntimeEnvironment.getApplication()
        val packageName = "com.example.rejected-bottom-choice"
        QuickLaunchStore.clearDisplayPreference(context, packageName)
        assertTrue(CompanionQuickLaunchChoiceState.request(packageName, "Rejected bottom"))
        val choice = CompanionQuickLaunchChoiceState.pending.value!!
        var dispatches = 0
        assertFalse(
            settleSecondaryQuickLaunchChoice(
                context = context,
                choice = choice,
                destination = CompanionQuickLaunchDestination.BOTTOM,
                onPrimary = { _, _ -> error("wrong destination dispatched") },
                onSecondary = { _, _ -> dispatches++; false },
            ),
        )
        assertEquals(1, dispatches)
        assertEquals(QuickLaunchDisplayRole.SECONDARY, QuickLaunchStore.displayPreference(packageName))
        QuickLaunchStore.clearDisplayPreference(context, packageName)
    }

    // R6-P0-02 S2 (spec §5): the Bottom re-warned about pausing a game that was already parked, because
    // companionQuickLaunchWouldCoverMainGame() read only the session and could not tell "would newly park"
    // from "is already parked with Mimir in front". Reproduces 30-p0-STUCK-pause-game-recurs….png.
    @Test
    fun `a parked game with Mimir in front does not warn again`() {
        val game = parkedMainGame()
        val lease = MainGameParkingLease(game.operation, game.gameId, game.targetPackageName!!, taskId = 4170, parkedAtElapsedMillis = 0L)

        // Already parked (Mimir is in front, mainStopped = false): Continue would change nothing for the
        // game, so no warning.
        assertFalse(companionQuickLaunchWouldCoverMainGame(game = game, parkedLease = lease, mainStopped = false))
        // No parking lease yet, Main stopped: Continue would actually cover/park the game, so warn.
        assertTrue(companionQuickLaunchWouldCoverMainGame(game = game, parkedLease = null, mainStopped = true))
    }

    private fun parkedMainGame(): MimirGameSession = MimirGameSession(
        operation = MimirGameSessionOperation("game:parked-3ds"),
        sequence = 1L,
        gameId = GameId("zelda-3ds"),
        platformId = PlatformId.THREE_DS,
        returnAnchor = MimirGameReturnAnchor.Home(
            HomeLaunchReturnTarget(
                HomeShelfItemId.Emulated(GameId("zelda-3ds")),
                rank = 0L,
            ),
        ),
        startedAtEpochMs = 1L,
        targetPackageName = "com.example.lime3ds",
        placement = MimirGamePlacement.MAIN,
        dualScreen = true,
        phase = MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
    )
}

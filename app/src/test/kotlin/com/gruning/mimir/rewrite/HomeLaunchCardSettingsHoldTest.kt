package com.gruning.mimir.rewrite

import androidx.compose.runtime.BroadcastFrameClock
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.launch.LaunchController
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardOpenWithKey
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardPlayKey
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardRestartKey
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardResume
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardScope
import com.gruning.mimir.rewrite.feature.library.HomeLaunchDescriptionScrollState
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import com.gruning.mimir.rewrite.feature.library.LibraryShelfScope
import com.gruning.mimir.rewrite.feature.library.OpenWithScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * START from Home's launch card opens Settings and B returns to the card : the card is HELD while Settings owns the screen, its session untouched, and comes
 * back on the key it was left on. The coordinator half of that, on a real [DefaultFocusAuthority]; the input and overlay wiring is pinned in [OneGameCardWiringTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeLaunchCardSettingsHoldTest {
    private val settingsScope = FocusScope("test/settings")
    private val settingsKey = FocusKey("test/settings/rail")
    private val clock = BroadcastFrameClock()

    @After
    fun releaseLease() {
        HomeLaunchCardResume.set(null)
    }

    /** The coordinator only holds its launch controller, and these paths never touch it; an instance without a constructor call is enough. */
    private fun untouchedLaunchController(): LaunchController {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        @Suppress("UNCHECKED_CAST")
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, LaunchController::class.java) as LaunchController
    }

    private class Fixture(
        val authority: DefaultFocusAuthority,
        val coordinator: HomeLaunchCardCoordinator,
        val row: HomeShelfRow,
        val game: GameRow?,
    )

    /** The scope the app's coordinator runs on: a frame clock of its own, which the tests send frames to. */
    private fun TestScope.appScope(): CoroutineScope = CoroutineScope(coroutineContext + clock)

    private val xenogears = GameRow(gameId = GameId("ps1/xenogears"), title = "Xenogears", platformId = PlatformId.PS1, memberCount = 1, duplicateKind = null)

    private fun openCard(scope: CoroutineScope, originScope: FocusScope = HomeShelfScope, emulated: Boolean = false): Fixture {
        val authority = DefaultFocusAuthority()
        val row = if (emulated) libraryHeroPickerRow(xenogears) else nativeAppTileMenuState("com.example.game", "Game", null, isGame = true).row
        authority.register(row.focusKey, originScope, FocusBounds(0f, 0f, 10f, 10f))
        authority.pushScope(originScope)
        authority.requestFocus(row.focusKey)
        val coordinator = HomeLaunchCardCoordinator(authority, untouchedLaunchController(), scope)
        val game = if (emulated) xenogears else null
        assertTrue("the card opens from its origin tile", coordinator.open(row, game, originScope, row.focusKey))
        assertEquals(HomeLaunchCardScope, authority.state.value.scope)
        assertEquals(HomeLaunchCardPlayKey, authority.state.value.focused)
        return Fixture(authority, coordinator, row, game)
    }

    /** What Settings does to the scope stack: pushes its own scopes over what is below and pops them as it leaves. */
    private fun visitSettings(authority: DefaultFocusAuthority) {
        authority.register(settingsKey, settingsScope, FocusBounds(0f, 0f, 10f, 10f))
        authority.pushScope(settingsScope)
        authority.requestFocus(settingsKey)
        authority.popScope()
    }

    private var dissolveFrameBase = 0L

    /** The outgoing card is held for its dissolve on the ANIMATION clock (audit RC-49): two frames a dissolve apart release it, where virtual time alone never does. */
    private fun TestScope.runOutDissolve() {
        runCurrent()
        dissolveFrameBase += 1_000_000_000L
        clock.sendFrame(dissolveFrameBase)
        runCurrent()
        clock.sendFrame(dissolveFrameBase + (MimirMotion.OverlayMs + 16) * 1_000_000L)
        runCurrent()
    }

    private fun TestScope.frames(count: Int) {
        runCurrent()
        repeat(count) {
            clock.sendFrame(it.toLong() + 1L)
            runCurrent()
        }
    }

    private val openWithBounds = FocusBounds(100f, 0f, 200f, 44f)

    @Test
    fun aCardOpenedFromTheHomeShelfIsHeldForSettingsAndComesBackOnBWithItsSession() = runTest {
        val fixture = openCard(appScope())
        val session = fixture.coordinator.session

        assertTrue(fixture.coordinator.suspendForSettings())
        assertTrue(fixture.coordinator.suspendedForSettings)
        assertFalse("held once", fixture.coordinator.suspendForSettings())
        assertEquals("the session is untouched", session, fixture.coordinator.session)

        visitSettings(fixture.authority)
        fixture.coordinator.resumeAfterSettings()
        advanceUntilIdle()

        assertFalse(fixture.coordinator.suspendedForSettings)
        assertEquals("the same session is back", session, fixture.coordinator.session)
        assertEquals(HomeLaunchCardScope, fixture.authority.state.value.scope)
        assertEquals(HomeLaunchCardPlayKey, fixture.authority.state.value.focused)
    }

    /** Focus returns to the key the card was left on, not always to Play (Restart on a parked game's card is the case this exists for). */
    @Test
    fun theHeldCardComesBackOnTheKeyItWasLeftOn() = runTest {
        val fixture = openCard(appScope())
        fixture.authority.registerVirtual(HomeLaunchCardOpenWithKey, HomeLaunchCardScope, openWithBounds)
        fixture.authority.requestFocus(HomeLaunchCardOpenWithKey)

        assertTrue(fixture.coordinator.suspendForSettings())
        visitSettings(fixture.authority)
        fixture.coordinator.resumeAfterSettings()
        fixture.authority.registerVirtual(HomeLaunchCardOpenWithKey, HomeLaunchCardScope, openWithBounds)
        frames(2)

        assertEquals(HomeLaunchCardScope, fixture.authority.state.value.scope)
        assertEquals(HomeLaunchCardOpenWithKey, fixture.authority.state.value.focused)
    }

    /** A saved key that never registers leaves the card on Play, after the whole allowance of frames and not before. */
    @Test
    fun aSavedKeyThatNeverRegistersLeavesTheCardOnPlayAfterEightFrames() = runTest {
        val fixture = openCard(appScope())
        fixture.authority.registerVirtual(HomeLaunchCardOpenWithKey, HomeLaunchCardScope, openWithBounds)
        fixture.authority.requestFocus(HomeLaunchCardOpenWithKey)
        assertTrue(fixture.coordinator.suspendForSettings())
        visitSettings(fixture.authority)

        fixture.coordinator.resumeAfterSettings()
        frames(8)

        assertEquals(HomeLaunchCardPlayKey, fixture.authority.state.value.focused)
        // The retry is over: a key that registers now is not chased.
        fixture.authority.registerVirtual(HomeLaunchCardOpenWithKey, HomeLaunchCardScope, openWithBounds)
        frames(2)
        assertEquals(HomeLaunchCardPlayKey, fixture.authority.state.value.focused)
    }

    /** The retry is paced by frames, not a timer: a key that registers on the third frame is taken on it, with no virtual time passing. */
    @Test
    fun theRetryRunsOnFramesAndTakesAKeyThatRegistersOnTheThird() = runTest {
        val fixture = openCard(appScope())
        fixture.authority.registerVirtual(HomeLaunchCardOpenWithKey, HomeLaunchCardScope, openWithBounds)
        fixture.authority.requestFocus(HomeLaunchCardOpenWithKey)
        assertTrue(fixture.coordinator.suspendForSettings())
        visitSettings(fixture.authority)
        fixture.coordinator.resumeAfterSettings()
        runCurrent()

        clock.sendFrame(1L)
        runCurrent()
        clock.sendFrame(2L)
        runCurrent()
        assertEquals("nothing registered yet", HomeLaunchCardPlayKey, fixture.authority.state.value.focused)

        fixture.authority.registerVirtual(HomeLaunchCardOpenWithKey, HomeLaunchCardScope, openWithBounds)
        clock.sendFrame(3L)
        runCurrent()

        assertEquals(HomeLaunchCardOpenWithKey, fixture.authority.state.value.focused)
    }

    /** Restart is asked for only while its game is parked: a game that is not goes to Play at once, with no frame spent. */
    @Test
    fun restartForAGameThatIsNoLongerParkedGoesToPlayAtOnce() = runTest {
        val fixture = openCard(appScope(), emulated = true)
        HomeLaunchCardResume.set(xenogears.gameId)
        fixture.authority.registerVirtual(HomeLaunchCardRestartKey, HomeLaunchCardScope, FocusBounds(60f, 0f, 160f, 44f))
        fixture.authority.requestFocus(HomeLaunchCardRestartKey)
        assertTrue(fixture.coordinator.suspendForSettings())
        visitSettings(fixture.authority)
        HomeLaunchCardResume.set(null) // the game came back while Settings had the screen

        fixture.coordinator.resumeAfterSettings()

        assertEquals("no frame needed", HomeLaunchCardPlayKey, fixture.authority.state.value.focused)
    }

    /**
     * ...and while it IS parked it lands on the resume frame (audit H-04d): Restart is registered with the card's actions and asked for in the same command, so Play is never the
     * key the card is first drawn on. It used to wait a frame or two for the remounted card to register it.
     */
    @Test
    fun restartForAParkedGameLandsOnTheResumeFrame() = runTest {
        val fixture = openCard(appScope(), emulated = true)
        HomeLaunchCardResume.set(xenogears.gameId)
        fixture.authority.registerVirtual(HomeLaunchCardRestartKey, HomeLaunchCardScope, FocusBounds(60f, 0f, 160f, 44f))
        fixture.authority.requestFocus(HomeLaunchCardRestartKey)
        assertTrue(fixture.coordinator.suspendForSettings())
        visitSettings(fixture.authority)

        fixture.coordinator.resumeAfterSettings()

        assertEquals("no frame spent: Restart is the key the card comes back on", HomeLaunchCardRestartKey, fixture.authority.state.value.focused)
        assertEquals(HomeLaunchCardScope, fixture.authority.state.value.scope)
    }

    /** Only a card opened from the Home shelf can be held, and only while it owns the controller: START stays the overlay's elsewhere. */
    @Test
    fun aCardFromAnotherOriginOrWithAChildOpenIsNotHeld() = runTest {
        val elsewhere = openCard(appScope(), originScope = LibraryShelfScope)
        assertFalse(elsewhere.coordinator.suspendForSettings())
        assertFalse(elsewhere.coordinator.suspendedForSettings)

        val withChild = openCard(appScope())
        withChild.authority.pushScope(OpenWithScope)
        assertFalse("a child scope owns the controller", withChild.coordinator.suspendForSettings())
    }

    /** Leaving Settings some way other than B drops the held card exactly as a close would: no session, no card scope, no registered keys. */
    @Test
    fun aHeldCardIsDroppedWhenTheSessionEndsSomeOtherWay() = runTest {
        val fixture = openCard(appScope())
        assertTrue(fixture.coordinator.suspendForSettings())

        fixture.coordinator.dropSuspended()

        assertFalse(fixture.coordinator.suspendedForSettings)
        assertNull(fixture.coordinator.session)
        assertFalse(HomeLaunchCardScope == fixture.authority.state.value.scope)
        assertNotNull(fixture.authority.state.value.scope)
        // Nothing left to resume.
        fixture.coordinator.resumeAfterSettings()
        assertNull(fixture.coordinator.session)
        // A fresh card opens normally afterwards.
        fixture.authority.requestFocus(fixture.row.focusKey)
        assertTrue(fixture.coordinator.open(fixture.row, null, HomeShelfScope, fixture.row.focusKey))
    }

    // ---- the hold does not latch (final round, P2) -------------------------------------------------------------------------------------

    /** The app closes a card whose row left the shelf, held or not: closing a held card ends the hold, so the next card is not born held, and Settings' return finds nothing to resume. */
    @Test
    fun closingAHeldCardEndsTheHold() = runTest {
        val fixture = openCard(appScope())
        assertTrue(fixture.coordinator.suspendForSettings())

        fixture.coordinator.close()

        assertFalse("the hold ended with the card", fixture.coordinator.suspendedForSettings)
        assertNull(fixture.coordinator.session)
        assertFalse(fixture.coordinator.closing)
        assertEquals(HomeCardNavigationOutcome.UNCHANGED, fixture.coordinator.settleForDestination(MimirDestination.HOME, returnRequested = true))

        // The next card opens, and can be held in its turn.
        fixture.authority.requestFocus(fixture.row.focusKey)
        assertTrue(fixture.coordinator.open(fixture.row, null, HomeShelfScope, fixture.row.focusKey))
        assertFalse(fixture.coordinator.suspendedForSettings)
        assertTrue(fixture.coordinator.suspendForSettings())
    }

    /** A card opened over a held one starts clean: [HomeLaunchCardCoordinator.open] resets the hold along with the rest of the session. */
    @Test
    fun openingACardResetsAHoldLeftOver() = runTest {
        val fixture = openCard(appScope())
        assertTrue(fixture.coordinator.suspendForSettings())

        fixture.authority.requestFocus(fixture.row.focusKey)
        assertTrue(fixture.coordinator.open(fixture.row, null, HomeShelfScope, fixture.row.focusKey))

        assertFalse(fixture.coordinator.suspendedForSettings)
        assertEquals(HomeLaunchCardScope, fixture.authority.state.value.scope)
    }

    // ---- one scope, however many round trips (final round, P3) -------------------------------------------------------------------------

    @Test
    fun threeRoundTripsLeaveExactlyOneCardScope() = runTest {
        val fixture = openCard(appScope())
        repeat(3) {
            assertTrue(fixture.coordinator.suspendForSettings())
            assertFalse("the card's scope left the stack when it was held", HomeLaunchCardScope == fixture.authority.state.value.scope)
            visitSettings(fixture.authority)
            fixture.coordinator.resumeAfterSettings()
            advanceUntilIdle()
            assertEquals(HomeLaunchCardScope, fixture.authority.state.value.scope)
        }

        // One copy: taking it off the stack leaves the Home shelf beneath, not a second card.
        fixture.authority.popScope()
        assertEquals(HomeShelfScope, fixture.authority.state.value.scope)
    }

    // ---- the description comes back where it was left (final round, P3) ----------------------------------------------------------------

    @Test
    fun theResumedCardIsToldToRestoreItsDescriptionOffsetInPlaceOfAStalePagingCommand() = runTest {
        val fixture = openCard(appScope())
        fixture.coordinator.updateDescriptionScrollState(
            HomeLaunchDescriptionScrollState(scrollable = true, canScrollUp = true, canScrollDown = true, measured = true, offset = 114, maxOffset = 380, revision = 77L),
        )
        val before = fixture.coordinator.descriptionScrollCommand.sequence
        assertTrue(fixture.coordinator.suspendForSettings())
        visitSettings(fixture.authority)

        fixture.coordinator.resumeAfterSettings()

        val command = fixture.coordinator.descriptionScrollCommand
        assertEquals("one sequence on, so it outranks whatever paged last", before + 1, command.sequence)
        assertEquals(114, command.restoreOffset)
        assertEquals(77L, command.expectedRevision)
        assertEquals("a restore never pages", 0, command.direction)
    }

    // ---- Settings reached from over the card by any path (final round, P1) -------------------------------------------------------------

    private class Stack(val coordinator: HomeLaunchCardCoordinator) {
        var destination = MimirDestination.HOME
        var achievementsOpen = false
        var armed = false
        val dispatched = mutableListOf<MimirOverlayKind>()
        val overlays = MimirOverlayCoordinator(
            snapshot = {
                MimirOverlaySnapshot(
                    gamelistAchievements = achievementsOpen,
                    homeLaunchCard = homeLaunchCardOverlayOpen(
                        hasSession = coordinator.session != null,
                        closing = coordinator.closing,
                        held = coordinator.suspendedForSettings,
                        destination = destination,
                    ),
                )
            },
            dispatchTop = { kind, _ ->
                dispatched += kind
                true
            },
        )
    }

    /**
     * Home card -> SELECT (the achievement card) -> NEEDS SETUP -> Settings answers the pad -> B returns to the card. NEEDS SETUP holds the card as START does; with the card held,
     * and Settings the destination, nothing on the overlay stack takes a press, so the pad reaches Settings (it used to be swallowed by a card that failed its own scope check,
     * B included). B resumes the card on the key it was left on.
     */
    @Test
    fun needsSetupFromTheAchievementCardHoldsTheHomeCardAndSettingsAnswersThePad() = runTest {
        val fixture = openCard(appScope())
        val stack = Stack(fixture.coordinator)
        assertEquals(MimirOverlayKind.HOME_LAUNCH_CARD, stack.overlays.top)

        // SELECT opens the achievement card over the Home card.
        stack.achievementsOpen = true
        assertEquals(MimirOverlayKind.GAMELIST_ACHIEVEMENTS, stack.overlays.top)

        // NEEDS SETUP: the card is dismissed, the Home card is held, Settings opens.
        stack.achievementsOpen = false
        assertTrue(fixture.coordinator.holdForSettingsVisit(stack.destination) { stack.armed = true })
        assertTrue("the return is armed", stack.armed)
        stack.destination = MimirDestination.SETTINGS

        assertNull("nothing on the overlay stack takes a press over Settings", stack.overlays.top)
        assertNull("so Settings answers every one, B included", stack.overlays.dispatch(ControllerAction.NAV_DOWN))
        assertNull(stack.overlays.dispatch(ControllerAction.CANCEL))
        assertTrue(stack.dispatched.isEmpty())
        assertEquals(HomeCardNavigationOutcome.STILL_HELD, fixture.coordinator.settleForDestination(stack.destination, returnRequested = false))

        // B in Settings: back to Home, return requested.
        visitSettings(fixture.authority)
        stack.destination = MimirDestination.HOME
        assertEquals(HomeCardNavigationOutcome.RESUMED, fixture.coordinator.settleForDestination(stack.destination, returnRequested = true))
        advanceUntilIdle()

        assertEquals(MimirOverlayKind.HOME_LAUNCH_CARD, stack.overlays.top)
        assertEquals(HomeLaunchCardScope, fixture.authority.state.value.scope)
        assertEquals(HomeLaunchCardPlayKey, fixture.authority.state.value.focused)
    }

    /**
     * Any other way into Settings (or any other destination) with the card up and NOT held: the card is not on the stack there, so the pad is not swallowed, and the app
     * closes it, scope and keys with it even when Settings has already pushed its own scope over the card's.
     */
    @Test
    fun aCardLeftUpOverAnotherDestinationIsNeitherOnTheStackNorLeftBehind() = runTest {
        for (destination in listOf(MimirDestination.SETTINGS, MimirDestination.LIBRARY, MimirDestination.DIAGNOSTICS)) {
            val fixture = openCard(appScope())
            val stack = Stack(fixture.coordinator)
            stack.destination = destination
            assertNull("$destination: not on the stack", stack.overlays.top)
            // Settings (or the other destination) is already up, with its scope over the card's.
            fixture.authority.register(settingsKey, settingsScope, FocusBounds(0f, 0f, 10f, 10f))
            fixture.authority.pushScope(settingsScope)

            assertEquals(HomeCardNavigationOutcome.CLOSED, fixture.coordinator.settleForDestination(destination, returnRequested = false))
            runOutDissolve()

            assertNull("$destination: the session is gone", fixture.coordinator.session)
            // Settings' own scope is untouched, and the card's is not buried beneath it.
            assertEquals(settingsScope, fixture.authority.state.value.scope)
            fixture.authority.popScope()
            assertEquals("$destination: no card scope left under it", HomeShelfScope, fixture.authority.state.value.scope)
        }
    }

    @Test
    fun theCardIsOnTheStackOnlyOverTheDestinationsItIsDrawnOver() {
        assertTrue(homeLaunchCardOverlayOpen(hasSession = true, closing = false, held = false, destination = MimirDestination.HOME))
        assertTrue("a card stays on the stack over Browse", homeLaunchCardOverlayOpen(true, false, false, MimirDestination.BROWSE))
        assertFalse(homeLaunchCardOverlayOpen(true, false, false, MimirDestination.SETTINGS))
        assertFalse(homeLaunchCardOverlayOpen(true, false, false, MimirDestination.LIBRARY))
        assertFalse("held", homeLaunchCardOverlayOpen(true, false, true, MimirDestination.HOME))
        assertFalse("closing", homeLaunchCardOverlayOpen(true, true, false, MimirDestination.HOME))
        assertFalse("no session", homeLaunchCardOverlayOpen(false, false, false, MimirDestination.HOME))
    }

    @Test
    fun aHeldCardIsDroppedWhenTheDestinationIsAnythingButSettingsOrBRoundTripHome() = runTest {
        for (destination in listOf(MimirDestination.LIBRARY, MimirDestination.BROWSE, MimirDestination.HOME)) {
            val fixture = openCard(appScope())
            assertTrue(fixture.coordinator.suspendForSettings())
            // HOME without a return request is the M-button's Home, not B: the card is not brought back.
            assertEquals(HomeCardNavigationOutcome.DROPPED, fixture.coordinator.settleForDestination(destination, returnRequested = false))
            assertNull(fixture.coordinator.session)
            assertFalse(fixture.coordinator.suspendedForSettings)
        }
    }
}

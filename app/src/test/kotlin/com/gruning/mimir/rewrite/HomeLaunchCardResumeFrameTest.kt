package com.gruning.mimir.rewrite

import androidx.compose.runtime.BroadcastFrameClock
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
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardScope
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch card's resume and close, on the clocks the product runs them on (audit RC-49, H-04d).
 *
 *  - **Resume.** The key a held card was left on is asked for in the command that brings the card back, so the card is never first drawn on Play and then moved: every key the
 *    entry registers is there already ([theKeyTheEntryRegistersIsTheOneTheCardComesBackOn]).
 *  - **Close.** The outgoing card is held for its dissolve on the ANIMATION clock ([mimirHold]), not the wall clock: at a slowed animator the hold is as long as the dissolve it
 *    covers, and a stopped one does not leave a card hanging behind a fade that has ended. A coroutine `delay` finishes the same hold by virtual time with no frame having been
 *    drawn, which is what [theOutgoingCardIsHeldOnTheAnimationClockNotTheWallClock] catches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeLaunchCardResumeFrameTest {
    private val settingsScope = FocusScope("test/settings")
    private val settingsKey = FocusKey("test/settings/rail")
    private val clock = BroadcastFrameClock()

    private fun untouchedLaunchController(): LaunchController {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        @Suppress("UNCHECKED_CAST")
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, LaunchController::class.java) as LaunchController
    }

    private fun TestScope.appScope(): CoroutineScope = CoroutineScope(coroutineContext + clock)

    private val xenogears = GameRow(gameId = GameId("ps1/xenogears"), title = "Xenogears", platformId = PlatformId.PS1, memberCount = 1, duplicateKind = null)

    private class Fixture(val authority: DefaultFocusAuthority, val coordinator: HomeLaunchCardCoordinator, val row: HomeShelfRow)

    private fun openEmulatedCard(scope: CoroutineScope): Fixture {
        val authority = DefaultFocusAuthority()
        val row = libraryHeroPickerRow(xenogears)
        authority.register(row.focusKey, HomeShelfScope, FocusBounds(0f, 0f, 10f, 10f))
        authority.pushScope(HomeShelfScope)
        authority.requestFocus(row.focusKey)
        val coordinator = HomeLaunchCardCoordinator(authority, untouchedLaunchController(), scope)
        assertTrue("the card opens from its origin tile", coordinator.open(row, xenogears, HomeShelfScope, row.focusKey))
        return Fixture(authority, coordinator, row)
    }

    private fun visitSettings(authority: DefaultFocusAuthority) {
        authority.register(settingsKey, settingsScope, FocusBounds(0f, 0f, 10f, 10f))
        authority.pushScope(settingsScope)
        authority.requestFocus(settingsKey)
        authority.popScope()
    }

    @Test
    fun theKeyTheEntryRegistersIsTheOneTheCardComesBackOn() = runTest {
        val fixture = openEmulatedCard(appScope())
        fixture.authority.requestFocus(HomeLaunchCardOpenWithKey)
        assertEquals(HomeLaunchCardOpenWithKey, fixture.authority.state.value.focused)
        assertTrue(fixture.coordinator.suspendForSettings())
        visitSettings(fixture.authority)

        fixture.coordinator.resumeAfterSettings()

        // No frame, no retry: the key is registered by the entry and asked for in the same command.
        assertEquals(HomeLaunchCardScope, fixture.authority.state.value.scope)
        assertEquals("the card is first drawn on the key it was left on, never on Play", HomeLaunchCardOpenWithKey, fixture.authority.state.value.focused)
    }

    @Test
    fun aCardLeftOnPlayStillComesBackOnPlay() = runTest {
        val fixture = openEmulatedCard(appScope())
        assertEquals(HomeLaunchCardPlayKey, fixture.authority.state.value.focused)
        assertTrue(fixture.coordinator.suspendForSettings())
        visitSettings(fixture.authority)

        fixture.coordinator.resumeAfterSettings()

        assertEquals(HomeLaunchCardPlayKey, fixture.authority.state.value.focused)
    }

    @Test
    fun theOutgoingCardIsHeldOnTheAnimationClockNotTheWallClock() = runTest {
        val fixture = openEmulatedCard(appScope())
        assertNotNull(fixture.coordinator.session)

        fixture.coordinator.close()
        runCurrent()
        // Ten seconds of virtual time and not one frame drawn: a wall-clock delay would have released the card by now, and the dissolve it covers would not have started.
        advanceTimeBy(10_000L)
        runCurrent()
        assertTrue("the card is still closing: its dissolve has not had a frame", fixture.coordinator.closing)
        assertNotNull("and its session is still held for the dissolve", fixture.coordinator.session)

        val first = 1_000_000_000L
        clock.sendFrame(first)
        runCurrent()
        assertNotNull("one frame in, the hold has only started", fixture.coordinator.session)
        clock.sendFrame(first + (MimirMotion.OverlayMs + 16) * 1_000_000L)
        runCurrent()
        assertNull("the hold ends once the dissolve's own length of frames has passed", fixture.coordinator.session)
    }
}

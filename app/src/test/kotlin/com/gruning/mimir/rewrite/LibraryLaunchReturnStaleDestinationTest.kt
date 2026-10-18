package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.feature.library.FavoriteSegment
import com.gruning.mimir.rewrite.feature.library.LibraryLaunchReturnTarget
import com.gruning.mimir.rewrite.feature.library.LibraryShelfMode
import com.gruning.mimir.rewrite.feature.library.LibraryViewModel
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ttx re-check P3 (audit 2026-10-14): the launch-return target saved across a process kill is restored only when the destination saved
 * beside it is the Library. A game launched from the Library parks its target there; with the saved destination on Home (a running Bottom
 * game, a Home launch) a stale Library target pulled Home to the game's card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryLaunchReturnStaleDestinationTest {

    @get:Rule
    val compose = createComposeRule()

    private val cardTarget = LibraryLaunchReturnTarget.gamelistRom(
        openPlatformId = PlatformId.PS1,
        romPlatformId = PlatformId.PS1,
        gameId = GameId("ps1/chrono-cross"),
        shelfMode = LibraryShelfMode.EMULATION,
        favoriteSegment = FavoriteSegment.GAMES,
        searchQuery = "chrono",
        favoritesOnly = true,
        restoreLaunchCard = true,
    )

    @Test
    fun `only a saved Library destination may take the saved target back`() {
        assertTrue(libraryLaunchReturnMayRestoreAfterKill(MimirDestination.LIBRARY))
        MimirDestination.entries.filter { it != MimirDestination.LIBRARY }.forEach { destination ->
            assertFalse("$destination", libraryLaunchReturnMayRestoreAfterKill(destination))
        }
    }

    private fun killAndRestore(destination: MimirDestination): Pair<LibraryViewModel, Int> {
        val tester = StateRestorationTester(compose)
        var viewModel = LibraryViewModel(FakeLibraryRepository())
        var armed = 0
        tester.setContent {
            MimirLibraryLaunchReturnSavedStateEffect(viewModel, onRestoredAfterKill = { armed++ }, savedDestination = destination)
        }
        compose.waitForIdle()
        assertTrue("fixture: a launch is pending in the first process", viewModel.restorePendingLaunchReturnTargetAfterKill(cardTarget))
        compose.waitForIdle()
        viewModel = LibraryViewModel(FakeLibraryRepository())
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        return viewModel to armed
    }

    @Test
    fun `a host restored on Home does not pull the Library back to the stale card`() {
        val (viewModel, armed) = killAndRestore(MimirDestination.HOME)
        assertNull("the stale target is not put back", viewModel.pendingLaunchReturnTarget.value)
        assertEquals("and the return is not armed", 0, armed)
    }

    @Test
    fun `a host restored on the Library still lands on the launch card`() {
        val (viewModel, armed) = killAndRestore(MimirDestination.LIBRARY)
        assertEquals(cardTarget, viewModel.pendingLaunchReturnTarget.value)
        assertEquals(1, armed)
    }

    @Test
    fun `the navigation effects hand the effect the destination the host was restored on`() {
        val source = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/MimirNavigationCoordinator.kt"),
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirNavigationCoordinator.kt"),
        ).first(File::isFile).readText()
        assertTrue(source.contains("savedDestination = navigation.destination,"))
    }
}

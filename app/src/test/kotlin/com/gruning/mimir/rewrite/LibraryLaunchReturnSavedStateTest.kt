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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The return must land on its launch card after process recreation. Thor low-memory kills during Eden loads exposed the gap. The target a launch leaves pending lived in the ViewModel only, so a killed process came back to splash, then the
 * shelf. It now rides the host's saved instance state beside the destination; a recreated host with no launch pending puts it back, arms the
 * return, and the restore admits it as an ended session (this process never had its session) and settles it like any other restore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryLaunchReturnSavedStateTest {

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
    fun `a recreated host with the saved target lands on the launch card, settled, with no glide`() {
        val tester = StateRestorationTester(compose)
        var viewModel = LibraryViewModel(FakeLibraryRepository())
        var armed = 0
        tester.setContent { MimirLibraryLaunchReturnSavedStateEffect(viewModel, onRestoredAfterKill = { armed++ }) }
        compose.waitForIdle()
        assertTrue("fixture: a launch is pending in the first process", viewModel.restorePendingLaunchReturnTargetAfterKill(cardTarget))
        compose.waitForIdle() // the saved state follows the pending target

        // The process is killed during the emulator load and comes back: a NEW view model, the saved instance state restored.
        viewModel = LibraryViewModel(FakeLibraryRepository())
        assertNull("fixture: the new process has no target of its own", viewModel.pendingLaunchReturnTarget.value)
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        val restored = requireNotNull(viewModel.pendingLaunchReturnTarget.value) { "the saved target is put back" }
        assertEquals(cardTarget, restored)
        assertEquals("and the return is armed once", 1, armed)
        assertTrue(
            "it passes the session gate with no session at all",
            libraryLaunchReturnMayRestore(restored, null, restoredAfterKill = viewModel.isLaunchReturnRestoredAfterKill(restored)),
        )
        assertFalse("while an ordinary target with no session still does not", libraryLaunchReturnMayRestore(restored, null))

        // The restore itself, as the return effect runs it (that it lands on the card as a settled arrival is LibraryLaunchReturnTargetSavedStateTest's, in the module that owns the motion).
        viewModel.restoreLaunchReturnTargetState(restored)
        assertSame("and it is the restore in flight", restored, viewModel.restoringLaunchReturnTarget())
        assertEquals(PlatformId.PS1, viewModel.openPlatform.value)
    }

    @Test
    fun `a configuration change keeps the view model's own target and restores nothing`() {
        val tester = StateRestorationTester(compose)
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        var armed = 0
        tester.setContent { MimirLibraryLaunchReturnSavedStateEffect(viewModel, onRestoredAfterKill = { armed++ }) }
        compose.waitForIdle()
        viewModel.noteNativePackageLaunch("com.example.game")
        val own = requireNotNull(viewModel.pendingLaunchReturnTarget.value)
        compose.waitForIdle()

        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertEquals("the same target, not a restored copy", own, viewModel.pendingLaunchReturnTarget.value)
        assertEquals(0, armed)
        assertFalse(viewModel.isLaunchReturnRestoredAfterKill(own))
    }

    @Test
    fun `a target that was consumed is not put back by the next restore`() {
        val tester = StateRestorationTester(compose)
        var viewModel = LibraryViewModel(FakeLibraryRepository())
        var armed = 0
        tester.setContent { MimirLibraryLaunchReturnSavedStateEffect(viewModel, onRestoredAfterKill = { armed++ }) }
        compose.waitForIdle()
        viewModel.restorePendingLaunchReturnTargetAfterKill(cardTarget)
        compose.waitForIdle()
        viewModel.clearPendingLaunchReturnTarget() // the cover lifted: the return is done
        compose.waitForIdle()

        viewModel = LibraryViewModel(FakeLibraryRepository())
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertNull(viewModel.pendingLaunchReturnTarget.value)
        assertEquals(0, armed)
    }

    @Test
    fun `a pending launch is never overwritten by a restore`() {
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        viewModel.noteNativePackageLaunch("com.example.game")
        assertFalse(viewModel.restorePendingLaunchReturnTargetAfterKill(cardTarget))
        assertNotNull(viewModel.pendingLaunchReturnTarget.value)
        assertFalse(viewModel.isLaunchReturnRestoredAfterKill(cardTarget))
    }
}

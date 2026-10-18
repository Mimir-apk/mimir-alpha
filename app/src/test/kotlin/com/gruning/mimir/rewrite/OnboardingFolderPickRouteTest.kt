package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Onboarding's Choose folder uses Mimir's picker, with Android's picker as fallback without All-files access. */
class OnboardingFolderPickRouteTest {

    @Test
    fun `Choose folder opens Mimir's browser while All-files access is held, Android's picker only without it`() {
        assertEquals(AddGamesRoute.IN_APP_BROWSER, onboardingFolderPickRoute(allFilesAccessGranted = true))
        assertEquals(AddGamesRoute.SYSTEM_PICKER, onboardingFolderPickRoute(allFilesAccessGranted = false))
        // One rule for both entry points, so they cannot drift.
        listOf(true, false).forEach { granted ->
            assertEquals(addGamesRoute(sourceOperationRunning = false, allFilesAccessGranted = granted), onboardingFolderPickRoute(granted))
        }
    }

    @Test
    fun `a browser path is a root, and it reads only while All-files access is held`() {
        assertTrue(onboardingRootIsPath("/storage/emulated/0/ROMs"))
        assertFalse(onboardingRootIsPath("content://com.android.externalstorage.documents/tree/primary%3AROMs"))
        assertEquals(OnboardingTreeAccess(read = true, write = true), onboardingPathRootAccess(true, readable = true, writable = true))
        assertEquals(OnboardingTreeAccess(read = true, write = false), onboardingPathRootAccess(true, readable = true, writable = false))
        assertEquals(
            "revoking All-files access darkens a path root, so LIBRARY sends the player back to choose again",
            OnboardingTreeAccess(read = false, write = false),
            onboardingPathRootAccess(false, readable = true, writable = true),
        )
        assertEquals(OnboardingTreeAccess(read = false, write = false), onboardingPathRootAccess(true, readable = false, writable = true))
    }

    @Test
    fun `the coordinator routes Choose folder, and keeps Android's picker only for the fallback`() {
        val source = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirOnboardingCoordinator.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/MimirOnboardingCoordinator.kt"),
        ).first(File::isFile).readText()
        assertTrue(source.contains("chooseFolder = chooseLibraryFolder,"))
        val chooser = source.substringAfter("val chooseLibraryFolder: () -> Unit = {").substringBefore("\n    }\n")
        assertTrue(chooser.contains("onboardingFolderPickRoute(allFilesAccess)"))
        assertTrue(chooser.contains("AddGamesRoute.IN_APP_BROWSER -> FileBrowserStore.open("))
        assertTrue("the drive chooser is never scanned", chooser.contains("StorageVolumeListing.isVolumeRoot(chosen)"))
        assertTrue(chooser.contains("else -> folderLauncher.launch(null)"))
        assertEquals("one launch of Android's picker, the fallback", 1, source.split("folderLauncher.launch(").size - 1)
    }
}

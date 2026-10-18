package com.gruning.mimir.rewrite.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.library.LibrarySummary
import com.gruning.mimir.rewrite.core.model.library.SourceRow
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Alpha cert 2026-10-08, lane B, at the surface the player sees (`SettingsDetailPanel`):
 *  - B1: the Emulators pane tells "still checking" (null) from "loaded, nothing to choose from" (empty list).
 *  - B2: the Library pane's storage row follows the All-files grant.
 *  - B3: the "Add Library folder" row's trailing action says what the row does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w640dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsLibraryAndEmulatorsPaneRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val checking = "Checking installed emulators…"
    private val noChoices = "No emulator choices yet"
    private val perFolder = "Allowed per folder when you pick it"
    private val allFiles = "Allowed for all files"

    private val library = LibraryReadModel(
        summary = LibrarySummary(gameCount = 0, platformCount = 0),
        sources = emptyList(),
    )

    /** Chrome text speaks its natural string as the content description (the visual is capitals), so both are matched. */
    private fun exists(text: String): Boolean =
        compose.onAllNodes(hasContentDescription(text) or hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** [rows] is read inside the content, so a test that moves it does so through a snapshot (never a bare global write). */
    private fun showEmulators(rows: () -> List<EmulatorAssignment>?) {
        compose.setContent {
            MimirTheme {
                Box(Modifier.requiredWidth(560.dp).requiredHeight(900.dp)) {
                    SettingsDetailPanel(
                        category = SettingsCategory.EMULATORS,
                        authority = remember { DefaultFocusAuthority() },
                        onRowActivate = {},
                        emulatorAssignments = rows(),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun showLibrary(model: LibraryReadModel) {
        compose.setContent {
            MimirTheme {
                Box(Modifier.requiredWidth(560.dp).requiredHeight(1400.dp)) {
                    SettingsDetailPanel(
                        category = SettingsCategory.LIBRARY,
                        authority = remember { DefaultFocusAuthority() },
                        onRowActivate = {},
                        library = model,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a null assignment list reads Checking and never the empty copy`() {
        showEmulators { null }
        assertTrue(exists(checking))
        assertFalse(exists(noChoices))
    }

    @Test
    fun `an empty assignment list reads No emulator choices yet and Checking is gone`() {
        showEmulators { emptyList() }
        assertTrue(exists(noChoices))
        assertFalse(exists(checking))
    }

    @Test
    fun `the pane resolves from Checking to No emulator choices when the library loads with zero platforms`() {
        val assignments = mutableStateOf<List<EmulatorAssignment>?>(null)
        showEmulators { assignments.value }
        assertTrue(exists(checking))

        Snapshot.withMutableSnapshot { assignments.value = emptyList() }
        compose.waitForIdle()

        assertTrue(exists(noChoices))
        assertFalse(exists(checking))
    }

    @Test
    fun `a populated assignment list shows neither the loading nor the empty copy`() {
        showEmulators { listOf(EmulatorAssignment("Super Nintendo", "Snes9x", SettingsSample.SampleStatus.READY)) }
        assertFalse(exists(checking))
        assertFalse(exists(noChoices))
        assertTrue(exists("Super Nintendo"))
    }

    @Test
    fun `the storage row says per folder until All-files access is held`() {
        showLibrary(library)
        assertTrue(exists(perFolder))
        assertFalse(exists(allFiles))
    }

    @Test
    fun `the storage row says Allowed for all files once All-files access is held`() {
        showLibrary(library.copy(allFilesAccess = true))
        assertTrue(exists(allFiles))
        assertFalse(exists(perFolder))
        // Folder access is untouched by the grant (the duplicate row is an unresolved design question).
        assertTrue(exists("Allowed when you pick a folder"))
    }

    @Test
    fun `Add Library folder offers Add folder, not Add games`() {
        showLibrary(library)
        assertTrue(exists("Add Library folder"))
        assertTrue(exists("Add folder"))
        assertFalse(exists("Add games"))
    }

    // Alpha cert 2026-10-08, E3: a rescan with the grant revoked emptied the index, and the folders still read
    // "Available". A shared-storage folder is now unavailable while the grant is off, and the pane says why.
    private val sharedFolder = SourceRow(
        friendlyPath = "Internal storage › CertRoms › ROMs",
        rootUri = "/storage/emulated/0/CertRoms/ROMs",
        gameCount = 111,
        available = true,
    )
    private val grantedTreeFolder = SourceRow(
        friendlyPath = "SD card › Games",
        rootUri = "content://com.android.externalstorage.documents/tree/6A7F-FCFE%3AGames",
        gameCount = 80,
        available = true,
    )
    private val withFolders = library.copy(
        summary = LibrarySummary(gameCount = 191, platformCount = 21),
        sources = listOf(sharedFolder, grantedTreeFolder),
    )

    private val allFoldersLost = "Mimir can’t read your folders right now. Allow file access to rescan."
    private val someFoldersLost = "Mimir can’t read some of your folders right now. Allow file access to rescan."

    @Test
    fun `with All files access off a shared folder reads Unavailable and the pane says why`() {
        showLibrary(withFolders)

        // One folder is locked and one is not, so the pane does not claim the whole library is out of reach.
        assertTrue(exists(someFoldersLost))
        assertFalse(exists(allFoldersLost))
        assertTrue(exists("Allow file access"))
        assertTrue(exists("111 games · Unavailable"))
        assertFalse(exists("111 games · Available"))
        // A SAF folder is judged by its own grant, not by this one.
        assertTrue(exists("80 games · Available"))
    }

    @Test
    fun `when every folder is locked the pane says your folders, not some`() {
        showLibrary(withFolders.copy(sources = listOf(sharedFolder)))

        assertTrue(exists(allFoldersLost))
        assertFalse(exists(someFoldersLost))
        assertTrue(exists("111 games · Unavailable"))
    }

    @Test
    fun `with All files access held the same folders read Available and no warning shows`() {
        showLibrary(withFolders.copy(allFilesAccess = true))

        assertTrue(exists("111 games · Available"))
        assertFalse(exists("111 games · Unavailable"))
        assertFalse(exists(someFoldersLost))
        assertFalse(exists(allFoldersLost))
        assertFalse(exists("Allow file access"))
    }

    @Test
    fun `another app's folder reads Unavailable without the grant, and Mimir's own folder does not`() {
        val other = sharedFolder.copy(
            friendlyPath = "Internal storage › Android › media › com.other.app",
            rootUri = "/storage/emulated/0/Android/media/com.other.app/ROMs",
            gameCount = 7,
        )
        val own = sharedFolder.copy(
            friendlyPath = "Internal storage › Android › media › Mimir",
            rootUri = "/storage/emulated/0/Android/media/com.gruning.mimir.rewrite/ROMs",
            gameCount = 9,
        )
        showLibrary(library.copy(sources = listOf(other, own), appPackage = "com.gruning.mimir.rewrite"))

        assertTrue(exists("7 games · Unavailable"))
        assertTrue(exists("9 games · Available"))
    }
}

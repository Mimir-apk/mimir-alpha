package com.gruning.mimir.rewrite.feature.files

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Audit 2026-10-14, RC-21 and F-10 (1) / F-15a: the emulator showed the Files root with no ring on any folder tile, and a folder holding only files with nothing focused (`files1`,
 * `files12`, `files13`). **Neither reproduces in the code**, and this pins it, so a later change that really did lose the landing cannot pass for the touch-mode artifact the sweep
 * suspected (SWEEP.md, "the first DPAD key after a tap is eaten leaving touch mode").
 *
 * The whole [FileBrowserSurface], wired as production wires it: a real [DefaultFocusAuthority], the asynchronous listing on the main looper (`FileBrowserStore`'s
 * `listDirectoryAsync` + `loadScope`), and the pad entering a folder through `dispatch(CONFIRM)`. What is read is the tree, not the pixels (a software canvas cannot draw the surface's
 * recorded layers): the authority's focused key, and each tile's `focused` semantic, which is exactly the flag the cover is handed to draw its ring with -- so "the tile is focused"
 * is "the ring is allowed to paint". The ring's own pixels are [FileBrowserTomeRingCaptureTest]'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileBrowserFocusLandingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val root = "/storage/emulated/0"

    private fun dir(name: String, parent: String = root, children: Int = 0) =
        FileItem(name = name, path = "$parent/$name", isDirectory = true, childCount = children)

    private fun file(name: String, parent: String) = FileItem(name = name, path = "$parent/$name", size = 36L)

    private val listing: Map<String, List<FileItem>> = mapOf(
        root to listOf(dir("Alarms"), dir("Android", children = 3), dir("Audiobooks"), dir("DCIM"), dir("Documents"), dir("Download"), dir("FilesTest", children = 7), dir("Movies", children = 2)),
        "$root/Movies" to listOf(file(".database_uuid", "$root/Movies"), file(".nomedia", "$root/Movies")),
        "$root/Alarms" to emptyList(),
    )

    private val authority = DefaultFocusAuthority()

    /** Wired like `FileBrowserStore`: the listing may suspend (here, a short delay) and resumes on the main looper. */
    private fun controller(mode: FileBrowserMode, items: Map<String, List<FileItem>> = listing) = FileBrowserController(
        authority = authority,
        rootPath = root,
        mode = mode,
        listDirectory = { path -> items[path].orEmpty() },
        listDirectoryAsync = { path -> delay(50); items[path].orEmpty() },
        loadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        onChoose = {},
        onDismiss = {},
    )

    private fun mount(controller: FileBrowserController) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
                MimirTheme {
                    Box(Modifier.requiredSize(960.dp, 540.dp)) {
                        FileBrowserSurface(controller = controller, authority = authority, modifier = Modifier)
                    }
                }
            }
        }
        settle()
    }

    private fun settle() {
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS)
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
    }

    private fun press(controller: FileBrowserController, vararg actions: ControllerAction) {
        actions.forEach { action ->
            compose.runOnUiThread { assertTrue("$action is taken", controller.dispatch(action)) }
            settle()
        }
    }

    /** The tiles that carry the ring flag, in reading order, each with the flag's value. */
    private fun ringFlags(): List<Boolean> = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(FileBrowserRingLitSemantics),
        useUnmergedTree = true,
    ).fetchSemanticsNodes()
        .sortedWith(compareBy<SemanticsNode>({ Math.round(it.positionInRoot.y) / 40 }, { it.positionInRoot.x }))
        .map { it.config.getOrNull(FileBrowserRingLitSemantics) == true }

    private fun drawn(text: String): Boolean = compose.onAllNodes(
        SemanticsMatcher("text $text") { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true },
        useUnmergedTree = true,
    ).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the root's first folder is focused and carries the ring the moment it mounts, and the ring follows the pad`() {
        val controller = controller(FileBrowserMode.MANAGE)
        mount(controller)

        assertEquals(FileBrowserFocus.tileKey("$root/Alarms"), authority.state.value.focused)
        assertEquals(
            "eight folder tiles are drawn and the first alone carries the ring",
            listOf(true, false, false, false, false, false, false, false),
            ringFlags(),
        )

        press(controller, ControllerAction.NAV_RIGHT, ControllerAction.NAV_RIGHT)
        assertEquals(FileBrowserFocus.tileKey("$root/Audiobooks"), authority.state.value.focused)
        assertEquals(listOf(false, false, true, false, false, false, false, false), ringFlags())

        // Down from the third tile of four lands on the third of the second row (Download is under Audiobooks).
        press(controller, ControllerAction.NAV_DOWN)
        assertEquals(FileBrowserFocus.tileKey("$root/FilesTest"), authority.state.value.focused)
        assertEquals(listOf(false, false, false, false, false, false, true, false), ringFlags())
    }

    @Test
    fun `a folder that holds only files opens with its first file focused and ringed, entered from the pad`() {
        val controller = controller(FileBrowserMode.MANAGE)
        mount(controller)

        // Movies is the eighth tile: three to the right, one down.
        press(controller, ControllerAction.NAV_RIGHT, ControllerAction.NAV_RIGHT, ControllerAction.NAV_RIGHT, ControllerAction.NAV_DOWN)
        assertEquals(FileBrowserFocus.tileKey("$root/Movies"), authority.state.value.focused)
        press(controller, ControllerAction.CONFIRM)

        assertEquals("$root/Movies", controller.currentPath)
        assertEquals("a files-only folder has no folder tile", 0, controller.partition.tomes.size)
        assertEquals(FileBrowserFocus.tileKey("$root/Movies/.database_uuid"), authority.state.value.focused)
        assertEquals("two file tiles are drawn and the first alone carries the ring", listOf(true, false), ringFlags())

        // And the pad has somewhere to go: RIGHT moves to the second file (the emulator's "RIGHT does nothing").
        press(controller, ControllerAction.NAV_RIGHT)
        assertEquals(FileBrowserFocus.tileKey("$root/Movies/.nomedia"), authority.state.value.focused)
        assertEquals(listOf(false, true), ringFlags())
    }

    @Test
    fun `a pick that opens on an empty folder lands on the pinned action, the only thing there to act on`() {
        val controller = controller(FileBrowserMode.PICK, mapOf(root to emptyList()))
        mount(controller)

        assertTrue("the pinned action exists", controller.commitAvailable)
        assertEquals(FileBrowserFocus.commitKey(root), authority.state.value.focused)
    }

    @Test
    fun `a manage folder that is empty has no action to land on, says so, and leaves the pad free to go back`() {
        val controller = controller(FileBrowserMode.MANAGE)
        mount(controller)
        press(controller, ControllerAction.CONFIRM)

        assertEquals("$root/Alarms", controller.currentPath)
        assertTrue("an empty folder", controller.partition.isEmpty)
        assertNull(
            "the manager's empty state has no action, so nothing in its scope is focused",
            authority.state.value.focused.takeIf { authority.state.value.scope == controller.scope },
        )
        assertTrue("it says so", drawn("This folder is empty."))
        // B goes up from it: the pad is not stranded, and the parent's first tile has its focus back.
        press(controller, ControllerAction.CANCEL)
        assertEquals(root, controller.currentPath)
        assertNotNull(authority.state.value.focused)
        assertFalse(controller.partition.isEmpty)
    }
}

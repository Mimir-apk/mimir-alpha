package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **UI-05 — the phantom "Use this folder" row on the drive chooser.** Regression introduced by
 * `68df882e`, which added `commitAllowed` to [FileBrowserController] and left [FileBrowserSurface]
 * still deciding for itself.
 *
 * Two sources of truth that disagreed:
 * - `FileBrowserController.commitKeyForCurrentMode()` — `mode == PICK && commitAllowed(currentPath)`
 * - `FileBrowserSurface`'s `showCommit` and `commitFocused` — `mode == PICK`, and nothing else
 *
 * On a multi-drive device the browser roots at `/storage`, where `commitAllowed` is false. The row
 * therefore rendered anyway as LazyColumn item 0; its own `mimirFocusable` registered PHYSICAL bounds
 * (the controller never registered it virtually, so it entered the focus graph by a different route);
 * NAV_UP from a volume row landed on it as the only candidate above; `commitFocused` evaluated true so
 * it highlighted and the footer printed "A · CHOOSE"; and CONFIRM reached a controller whose
 * `commitKey` was null, which returns `Consumed`. **On the first screen a multi-drive player ever
 * sees, a control looked focused, promised CHOOSE, and ate the A button.**
 *
 * **Why [FileBrowserContractsTest]'s "contract C - the drive chooser does not offer a commit
 * affordance" does not catch it, stated because it looks like it should:** that test asserts only
 * that focus does not *land* on the commit key at `open()`. It says nothing about whether the row
 * renders, whether it is reachable afterwards, or whether the footer advertises it — all three of
 * which were the defect. It passes on the broken code and on the fixed code alike.
 *
 * **Instrument split, stated rather than implied.** The behavioural half below is a pure controller
 * test and proves the model. The render half is a source-text pin: `FileBrowserItemList` is a private
 * `@Composable` and this module configures no Compose harness (plain JUnit, see `build.gradle.kts`) —
 * the same constraint and the same established fallback [FileBrowserSupportDimRenderTest] documents.
 * So it proves the surface reads the controller's answer instead of re-deriving one; it does not
 * prove a pixel. Only the device closes that.
 */
class FileBrowserCommitAffordanceTest {

    private fun dir(path: String, name: String) = FileItem(
        name = name, path = path, isDirectory = true,
    )

    private fun controller(
        listing: Map<String, List<FileItem>>,
        root: String,
        commitAllowed: (String) -> Boolean = { true },
        authority: DefaultFocusAuthority = DefaultFocusAuthority(),
    ) = FileBrowserController(
        authority = authority,
        rootPath = root,
        listDirectory = { listing[it].orEmpty() },
        onChoose = {},
        onDismiss = {},
        commitAllowed = commitAllowed,
    )

    @Test
    fun `at the drive chooser the commit affordance is unavailable and absent from the focus graph`() {
        val c = controller(
            listing = mapOf("/storage" to listOf(dir("/storage/emulated/0", "Internal Storage"))),
            root = "/storage",
            commitAllowed = { it != "/storage" },
        )
        c.open()

        assertFalse(
            "PICK mode alone must not make the commit affordance available: at /storage the controller " +
                "refuses to build a commitKey (commitAllowed is false), so anything that renders or " +
                "highlights the row is promising an action CONFIRM will silently swallow",
            c.commitAvailable,
        )
        assertFalse(
            "the commit key must not appear in this directory's focus graph at all -- a key the " +
                "controller will not honour is worse than a missing one, because D-pad can still reach it",
            FileBrowserFocus.commitKey("/storage") in fileBrowserKeys(c.partition, FileBrowserGrid.Authored, null),
        )
        c.close()
    }

    @Test
    fun `inside a real drive the commit affordance is available again`() {
        // The cost asymmetry runs the other way here: suppressing a legitimate "Use this folder" would
        // strand a player who has navigated to the exact directory they want to add. Pinned so a future
        // tightening of commitAvailable cannot quietly take the affordance away everywhere.
        val c = controller(
            listing = mapOf("/storage/emulated/0" to emptyList()),
            root = "/storage/emulated/0",
            commitAllowed = { it != "/storage" },
        )
        c.open()

        assertTrue(
            "inside a real drive PICK mode still offers the commit affordance",
            c.commitAvailable,
        )
        c.close()
    }

    @Test
    fun `MANAGE mode never offers the commit affordance whatever commitAllowed says`() {
        val c = FileBrowserController(
            authority = DefaultFocusAuthority(),
            rootPath = "/d",
            mode = FileBrowserMode.MANAGE,
            listDirectory = { emptyList() },
            onChoose = {},
            onDismiss = {},
            commitAllowed = { true },
        )
        c.open()

        assertFalse(
            "commitAvailable must subsume the mode check, not sit beside it -- the surface gates on " +
                "this one value now, so a MANAGE-mode true here would render a commit row on a browser " +
                "that has no commit path at all",
            c.commitAvailable,
        )
        c.close()
    }

    @Test
    fun `the surface renders and highlights the commit row from the controller's own answer`() {
        val source = surfaceSource()

        val itemList = source.sliceBetween(
            after = "val showCommit =",
            before = "if (partition.isEmpty && !showCommit)",
        )
        assertTrue(
            "showCommit must read controller.commitAvailable. Deciding it locally from " +
                "`controller.mode == FileBrowserMode.PICK` is the UI-05 defect itself: it renders a row " +
                "at /storage that the controller's commitKeyForCurrentMode() has already refused to " +
                "build, so found: <${itemList.trim()}>",
            itemList.contains("controller.commitAvailable"),
        )
        assertFalse(
            "showCommit must not re-derive the mode check -- commitAvailable already contains it, and " +
                "two expressions for one decision is exactly how the drive chooser drifted",
            itemList.contains("FileBrowserMode.PICK"),
        )

        val commitFocused = source.sliceBetween(
            after = "val commitFocused =",
            before = "Column(modifier = modifier.fillMaxSize()",
        )
        assertTrue(
            "commitFocused must also gate on controller.commitAvailable, or the row still highlights " +
                "and the footer still prints \"A · CHOOSE\" for an action CONFIRM will swallow",
            commitFocused.contains("controller.commitAvailable"),
        )
        assertFalse(
            "commitFocused must not re-derive the mode check either",
            commitFocused.contains("FileBrowserMode.PICK"),
        )
    }

    /** Presence-checked slice: a missing anchor fails loudly instead of degrading to the whole file. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", contains(before))
        return substringAfter(after).substringBefore(before)
    }

    private fun surfaceSource(): String {
        val name = "FileBrowserSurface.kt"
        val candidates = listOf(
            File("feature-files/src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}

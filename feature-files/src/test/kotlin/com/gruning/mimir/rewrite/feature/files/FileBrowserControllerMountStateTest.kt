package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FileBrowserController.hasOpened]'s lifecycle, and the property it exists to make visible to the app
 * shell: a controller that is MOUNTED but has lost the active [FocusScope] refuses every input path at
 * once — controller AND touch.
 *
 * That simultaneity is the P0 of 2026-08-14. It is
 * not a coincidence of two bugs: [FileBrowserController.dispatch], [FileBrowserController.handleTap],
 * [FileBrowserController.handleLongPress], [FileBrowserController.handleCommitTap] and
 * [FileBrowserController.focusedTile] (which is what [FileBrowserController.verifyFocusedChecksum] reads)
 * are all behind the same one-line scope guard, so losing the scope disables the whole surface in a
 * single step. **[FileBrowserController.handleLongPress] is new as of the 2026-08-14 touch-grammar
 * change and was added to this list at the same time** -- a new touch entry point that skipped the guard
 * would re-open the P0 through a door the original fix never had to cover. The guard itself is correct — a stray
 * action from a foreign scope must not be acted on. What was wrong was the app shell absorbing the
 * refusal forever; see `app/`'s `FileBrowserOverlayAbsorbTest`.
 */
class FileBrowserControllerMountStateTest {

    private val tree: Map<String, List<FileItem>> = mapOf(
        "/root" to listOf(
            FileItem(name = "Games", path = "/root/Games", isDirectory = true),
            FileItem(name = "game.gba", path = "/root/game.gba", extension = "gba"),
            // Added 2026-08-14 with the long-press binding. Without an ARCHIVE in this tree the
            // long-press refusal below would be vacuous -- SECONDARY is inert on a non-extractable tile,
            // so the assertion would hold against a controller whose scope guard had been deleted.
            FileItem(name = "bundle.zip", path = "/root/bundle.zip", extension = "zip"),
        ),
    )

    private fun newController(
        authority: DefaultFocusAuthority,
        mode: FileBrowserMode = FileBrowserMode.MANAGE,
        onChoose: (String) -> Unit = {},
        onDismiss: () -> Unit = {},
        onVerifyChecksum: (String) -> Unit = {},
        onExtract: (String) -> Unit = {},
    ): FileBrowserController = FileBrowserController(
        authority = authority,
        rootPath = "/root",
        mode = mode,
        listDirectory = { path -> tree[path].orEmpty() },
        onChoose = onChoose,
        onDismiss = onDismiss,
        onVerifyChecksum = onVerifyChecksum,
        onExtract = onExtract,
    )

    /**
     * **The regression scenario was reproduced** on a real `Blasphemous 2.steam`,
     * with the installed APK hash verified. The pure dispatch test passes, so if this fails the wiring is at fault and not the rule.
     */
    @Test
    fun `in a source pick, CONFIRM on a file reports THAT file`() {
        val authority = DefaultFocusAuthority()
        var chosen: String? = null
        val controller = newController(authority, mode = FileBrowserMode.PICK, onChoose = { chosen = it })
        controller.open()

        val file = controller.partition.files.single { it.name == "game.gba" }
        authority.requestFocus(FileBrowserFocus.tileKey(file.path))
        controller.dispatch(ControllerAction.CONFIRM)

        assertEquals("the file he stood on, not the folder it sits in", "/root/game.gba", chosen)
    }

    @Test
    fun `in a source pick, a TAP on a file reports that file too`() {
        val authority = DefaultFocusAuthority()
        var chosen: String? = null
        val controller = newController(authority, mode = FileBrowserMode.PICK, onChoose = { chosen = it })
        controller.open()

        controller.handleTap(controller.partition.files.single { it.name == "game.gba" })

        assertEquals("touch reaches the same decision table", "/root/game.gba", chosen)
    }

    @Test
    fun `hasOpened is false before open and latches true from the first open`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority)

        assertFalse("a constructed-but-unmounted controller has never pushed its scope", controller.hasOpened)
        controller.open()
        assertTrue(controller.hasOpened)
    }

    @Test
    fun `close does NOT reset hasOpened, so a closed controller can never claim the pre-mount absorb`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority)
        controller.open()
        controller.close()
        // Idempotent second close, the one MimirAppHost's dispose-time safety net can produce.
        controller.close()

        assertTrue(
            "a CLOSED controller and a NEVER-MOUNTED one want opposite answers from the shell's absorb: " +
                "the second must be swallowed (its scope is one frame away), the first must be refused " +
                "(its scope is never coming back). Resetting this flag would merge them into the " +
                "swallow, which is the permanent-sink polarity",
            controller.hasOpened,
        )
    }

    @Test
    fun `a re-rooted browser starts un-mounted, so the shell keeps covering its own mount gap`() {
        // FileBrowserStore.switchVolume swaps in a NEW controller instance while the surface is composed;
        // that instance has not opened until Compose forgets the old DisposableEffect and remembers the
        // new one. It must present as pre-mount, not as a divorced browser, or the volume switch would
        // leak the presses arriving during its own swap.
        val authority = DefaultFocusAuthority()
        val outgoing = newController(authority)
        outgoing.open()
        val incoming = newController(authority)

        assertTrue(outgoing.hasOpened)
        assertFalse(incoming.hasOpened)
    }

    @Test
    fun `losing the active scope disables the controller AND touch paths in one step`() {
        val authority = DefaultFocusAuthority()
        var chosen: String? = null
        var verified: String? = null
        var extracted: String? = null
        val controller = newController(
            authority,
            mode = FileBrowserMode.PICK,
            onChoose = { chosen = it },
            onVerifyChecksum = { verified = it },
            onExtract = { extracted = it },
        )
        controller.open()

        // The controller's OWN tiles, not hand-built ones, so this exercises what the surface actually
        // hands back to it.
        val tome = controller.partition.tomes.single()
        val file = controller.partition.files.single { it.name == "game.gba" }
        val archive = controller.partition.files.single { it.name == "bundle.zip" }

        // Sanity: while the scope is ours, the touch paths DO work — otherwise the assertions below
        // would pass against a controller that never worked at all. **Every entry point in this block
        // has to be exercised live before it is exercised dead**, which is why the long-press sanity
        // check below is not skipped as obvious: an inert binding refuses a thief for the wrong reason.
        controller.verifyFocusedChecksum()
        assertEquals("the commit row is focused first in PICK mode, so nothing verifies", null, verified)
        // Focused directly rather than by handleTap: since R-21 a tap on a file in a SOURCE pick COMMITS
        // it (and closes the browser), which would end this test three assertions early. The subject here
        // is scope theft, not what a tap means -- so it takes the shortest route to a focused file.
        authority.requestFocus(FileBrowserFocus.tileKey(file.path))
        controller.verifyFocusedChecksum()
        assertEquals("/root/game.gba", verified)
        controller.handleLongPress(archive)
        assertEquals(
            "long-press must reach Extract while the scope is ours -- an extraction writes new files " +
                "and removes none, so PICK offers it too (FileBrowserCommitPurpose's own reasoning)",
            "/root/bundle.zip",
            extracted,
        )

        verified = null
        extracted = null
        authority.pushScope(FocusScope("test/thief"))

        assertTrue("precondition: the browser is still mounted", controller.hasOpened)
        assertFalse("controller path is refused", controller.dispatch(ControllerAction.CONFIRM))
        controller.handleTap(tome)
        assertEquals("touch tap path is refused: no navigation happened", "/root", controller.currentPath)
        controller.handleLongPress(archive)
        assertEquals("touch long-press path is refused: nothing was extracted", null, extracted)
        controller.handleCommitTap()
        assertEquals("touch commit path is refused", null, chosen)
        controller.verifyFocusedChecksum()
        assertEquals("inspector Verify path is refused", null, verified)
    }

    /**
     * The cause half of the P0: the modal must own its own touches so none falls through to the
     * destination underneath.
     *
     * **A source-text pin, and a weak one — said out loud rather than implied.** What actually needs
     * proving is a hit-test outcome (a tap on the inspector's dead space must NOT reach
     * `MimirDestinationHost`), and this module configures no Compose test harness at all — plain JUnit,
     * no Robolectric, no `createComposeRule` (`build.gradle.kts:64`). So this asserts only that the root
     * `Column` still carries the guard: it stops a silent deletion, and it proves nothing about whether
     * the guard works. This tree's own precedent for that situation is
     * [FileBrowserSupportDimRenderTest], which discloses the same limit for the same reason.
     *
     * **The absorber itself is DEVICE-OWED.** Only hardware can show the fall-through is gone.
     */
    @Test
    fun `the surface's root claims its own touches instead of letting them fall through`() {
        val source = surfaceSource()

        val root = source.sliceBetween(
            after = "Column(modifier = modifier.fillMaxSize()",
            before = "FileBrowserHeader(",
        )
        assertTrue(
            "the full-screen modal root must carry fileBrowserModalTouchGuard(). Without a pointer-input " +
                "node over these bounds, Compose hit-testing walks straight past this overlay into the " +
                "destination underneath, and a tap the player cannot see activates a Settings row -- " +
                "which is how the browser lost its FocusScope and both input paths died. Found: <${root.trim()}>",
            root.contains("fileBrowserModalTouchGuard()"),
        )

        val guard = source.sliceBetween(
            after = "private fun Modifier.fileBrowserModalTouchGuard()",
            before = "/** A cheap, cached CRT-scanline wash",
        )
        assertTrue(
            "the guard must register a pointer-input node -- that node's existence is what ends the " +
                "sibling hit-test walk",
            guard.contains("pointerInput("),
        )
        assertFalse(
            "the guard must stay NON-consuming: a consuming absorber on a full-screen root takes the " +
                "DOWN out from under this surface's own LazyColumn scroll",
            guard.contains("detectTapGestures") || guard.contains("clickable"),
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
            java.io.File("feature-files/src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$name"),
            java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$name"),
        )
        return candidates.firstOrNull(java.io.File::isFile)?.readText()
            ?: error("$name not found from ${java.io.File(".").absolutePath}")
    }
}

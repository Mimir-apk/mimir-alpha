package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.DiscSetMoveOption
import com.gruning.mimir.rewrite.core.files.DiscSetWarning
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.files.PlatformDestination
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *
 *
 * The gate between a chosen destination and a move that breaks a multi-disc game. Every test drives the
 * real controller against a real [DefaultFocusAuthority]; only the filesystem, the destination list and
 * the library lookup are faked.
 *
 * **The property that matters most is the one about the OTHER commit path.** There are two ways to
 * finish a move — pick a folder off the destination list, or navigate to one and press the pinned commit
 * row — and a warning wired into only one of them is a warning with an invisible hole: both paths reach
 * the same sink and produce the same card, so nothing about the unwarned path looks different
 * afterwards. That is why the gate is one function and why two tests here press the same button two
 * different ways.
 */
class FileBrowserDiscSetGateTest {

    private val disc2 = "/root/Roms/ps1/FF9/FF9 (Disc 2).chd"

    private val tree: Map<String, List<FileItem>> = mapOf(
        "/root" to listOf(
            FileItem(name = "Roms", path = "/root/Roms", isDirectory = true),
        ),
        "/root/Roms" to listOf(
            FileItem(name = "ps1", path = "/root/Roms/ps1", isDirectory = true),
            FileItem(name = "archive", path = "/root/Roms/archive", isDirectory = true),
        ),
        "/root/Roms/ps1" to listOf(
            FileItem(name = "FF9", path = "/root/Roms/ps1/FF9", isDirectory = true),
        ),
        "/root/Roms/ps1/FF9" to listOf(
            FileItem(name = "FF9.m3u", path = "/root/Roms/ps1/FF9/FF9.m3u", extension = "m3u"),
            FileItem(name = "FF9 (Disc 1).chd", path = "/root/Roms/ps1/FF9/FF9 (Disc 1).chd", extension = "chd"),
            FileItem(name = "FF9 (Disc 2).chd", path = disc2, extension = "chd"),
            FileItem(name = "FF9 (Disc 3).chd", path = "/root/Roms/ps1/FF9/FF9 (Disc 3).chd", extension = "chd"),
            FileItem(name = "extras", path = "/root/Roms/ps1/FF9/extras", isDirectory = true),
        ),
        "/root/Roms/archive" to emptyList(),
        "/root/Roms/ps1/FF9/extras" to emptyList(),
    )

    private fun warning(canTravel: Boolean = true) = DiscSetWarning(
        title = "Final Fantasy IX",
        discNumber = 2,
        discCount = 3,
        subjectPath = disc2,
        setPaths = listOf(
            "/root/Roms/ps1/FF9/FF9.m3u",
            "/root/Roms/ps1/FF9/FF9 (Disc 1).chd",
            disc2,
            "/root/Roms/ps1/FF9/FF9 (Disc 3).chd",
        ),
        wholeSetCanTravel = canTravel,
    )

    private class Sinks {
        val moved = mutableListOf<Pair<String, String>>()
        val movedSets = mutableListOf<Pair<List<String>, String>>()
        val copied = mutableListOf<Pair<String, String>>()
        var lookups = 0
    }

    private val archive = PlatformDestination(
        platformId = "archive",
        path = "/root/Roms/archive",
        fromLibrary = false,
        recognised = false,
        onSubjectVolume = true,
    )

    /** A lookup the test can hold open, so a cancellation can be aimed at a suspended coroutine. */
    private var gate: CompletableDeferred<Unit>? = null

    private fun newController(
        authority: DefaultFocusAuthority,
        sinks: Sinks,
        discSet: DiscSetWarning? = warning(),
        wired: Boolean = true,
    ): FileBrowserController = FileBrowserController(
        authority = authority,
        rootPath = "/root",
        mode = FileBrowserMode.MANAGE,
        listDirectory = { path -> tree[path].orEmpty() },
        onChoose = {},
        onDismiss = {},
        onDelete = {},
        onMoveTo = { path, destination -> sinks.moved += path to destination },
        onCopyTo = { path, destination -> sinks.copied += path to destination },
        onMoveSetTo = { paths, destination -> sinks.movedSets += paths to destination },
        resolvePlatformDestinations = { listOf(archive) },
        resolveDiscSet = if (wired) {
            { path ->
                sinks.lookups++
                gate?.await()
                discSet?.takeIf { it.subjectPath == path }
            }
        } else {
            null
        },
        loadScope = if (wired) CoroutineScope(Dispatchers.Unconfined) else null,
    )

    /**
     * Focus the disc, reach the action overlay, take its *Move into…* row — the browser is now asking
     * "where?".
     *
     * **F-1/R-13, 2026-08-18: via SELECT's action overlay, not a direct X.** Move/Copy now arm
     * [FileBrowserController.beginTransferPick] straight from the overlay's own row, bypassing the old
     * two-row transfer chooser entirely (spec §1c) — see `FileBrowserTransferWiringTest`'s class KDoc.
     */
    private fun beginMoveOf(controller: FileBrowserController, authority: DefaultFocusAuthority) {
        controller.open()
        listOf("/root/Roms", "/root/Roms/ps1", "/root/Roms/ps1/FF9").forEach { path ->
            authority.requestFocus(FileBrowserFocus.tileKey(path))
            controller.dispatch(ControllerAction.CONFIRM)
        }
        authority.requestFocus(FileBrowserFocus.tileKey(disc2))
        controller.handleInspectorSecondaryTap()
        authority.requestFocus(FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.MOVE))
        controller.dispatch(ControllerAction.CONFIRM)
    }

    /**
     * …and take the destination off the list.
     *
     * Located by kind, not by a hardcoded index: F-3 (2026-08-18) moved the browse row to index 0, so
     * this fixture's one platform destination (`archive`) no longer sits at row 0 itself. This helper
     * commits via the platform row regardless of where the reordering puts it.
     */
    private fun commitViaDestinationList(controller: FileBrowserController, authority: DefaultFocusAuthority) {
        val platformIndex = controller.destinationList!!.rows.indexOfFirst {
            it.kind == FileBrowserDestinationRowKind.PLATFORM
        }
        authority.requestFocus(FileBrowserDestinationFocus.optionKey(platformIndex))
        controller.dispatch(ControllerAction.CONFIRM)
    }

    private fun press(controller: FileBrowserController, authority: DefaultFocusAuthority, option: DiscSetMoveOption) {
        authority.requestFocus(FileBrowserDiscSetFocus.optionKey(option))
        controller.dispatch(ControllerAction.CONFIRM)
    }

    @Test
    fun `committing a destination for one disc raises the warning instead of moving it`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        val controller = newController(authority, sinks)

        beginMoveOf(controller, authority)
        commitViaDestinationList(controller, authority)

        assertTrue(controller.discSetPromptOpen)
        assertEquals(
            "**nothing has moved.** Before this fix the same press dispatched the move and the game " +
                "was broken before any warning could appear",
            emptyList<Pair<String, String>>(),
            sinks.moved,
        )
        assertEquals("/root/Roms/archive", controller.discSetPrompt?.destination)
        assertEquals(
            listOf(DiscSetMoveOption.MOVE_WHOLE_SET, DiscSetMoveOption.MOVE_SUBJECT_ONLY, DiscSetMoveOption.CANCEL),
            controller.discSetPrompt?.rows,
        )
    }

    @Test
    fun `the pinned commit row goes through the same gate as the destination list`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        val controller = newController(authority, sinks)

        beginMoveOf(controller, authority)
        // The OTHER path: refuse the list, navigate by hand, press the pinned commit row.
        val browseIndex = controller.destinationList!!.rows.indexOfFirst {
            it.kind == FileBrowserDestinationRowKind.BROWSE
        }
        authority.requestFocus(FileBrowserDestinationFocus.optionKey(browseIndex))
        controller.dispatch(ControllerAction.CONFIRM)
        // Navigate by hand into a folder the list never offered, then press PUT IT HERE.
        assertEquals("/root", controller.currentPath)
        listOf("/root/Roms", "/root/Roms/ps1", "/root/Roms/ps1/FF9", "/root/Roms/ps1/FF9/extras").forEach { path ->
            authority.requestFocus(FileBrowserFocus.tileKey(path))
            controller.dispatch(ControllerAction.CONFIRM)
        }
        assertEquals("/root/Roms/ps1/FF9/extras", controller.currentPath)
        authority.requestFocus(FileBrowserFocus.commitKey(controller.currentPath))
        controller.dispatch(ControllerAction.CONFIRM)

        assertTrue(
            "a warning wired into ONE of the two commit paths is a warning with an invisible hole: both " +
                "reach the same sink and produce the same card, so the unwarned path looks identical " +
                "afterwards",
            controller.discSetPromptOpen,
        )
        assertTrue(sinks.moved.isEmpty())
    }

    @Test
    fun `move all discs takes the playlist and every disc, in one transfer`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        val controller = newController(authority, sinks)
        beginMoveOf(controller, authority)
        commitViaDestinationList(controller, authority)

        press(controller, authority, DiscSetMoveOption.MOVE_WHOLE_SET)

        assertEquals(
            "the PLAYLIST first and all three discs -- moving the discs without the .m3u would leave the " +
                "playlist behind naming files that are no longer beside it, which is the same breakage " +
                "with an extra step",
            listOf(warning().setPaths to "/root/Roms/archive"),
            sinks.movedSets,
        )
        assertTrue("and never as a single-file move as well", sinks.moved.isEmpty())
        assertFalse(controller.discSetPromptOpen)
    }

    @Test
    fun `move just this one moves exactly the file the player picked`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        val controller = newController(authority, sinks)
        beginMoveOf(controller, authority)
        commitViaDestinationList(controller, authority)

        press(controller, authority, DiscSetMoveOption.MOVE_SUBJECT_ONLY)

        assertEquals(listOf(disc2 to "/root/Roms/archive"), sinks.moved)
        assertTrue(sinks.movedSets.isEmpty())
    }

    @Test
    fun `cancel moves nothing, and so does B`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        val controller = newController(authority, sinks)
        beginMoveOf(controller, authority)
        commitViaDestinationList(controller, authority)

        press(controller, authority, DiscSetMoveOption.CANCEL)
        assertFalse(controller.discSetPromptOpen)
        assertTrue(sinks.moved.isEmpty() && sinks.movedSets.isEmpty())

        // …and the same question answered with B rather than with the row.
        val second = newController(authority, Sinks().also { sinks.lookups = it.lookups })
        beginMoveOf(second, authority)
        commitViaDestinationList(second, authority)
        second.dispatch(ControllerAction.CANCEL)

        assertFalse(
            "abandoning the question abandons the move: nothing has been written yet, so fail-closed is " +
                "available here in a way it is not on any other CANCEL in this module",
            second.discSetPromptOpen,
        )
        assertTrue(sinks.moved.isEmpty() && sinks.movedSets.isEmpty())
    }

    @Test
    fun `when the set cannot travel, move-all is not offered at all`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        val controller = newController(authority, sinks, discSet = warning(canTravel = false))
        beginMoveOf(controller, authority)
        commitViaDestinationList(controller, authority)

        assertEquals(
            "the warning still fires -- the game still breaks -- but a row promising to repair it by " +
                "moving them together would be advertising a repair that does not repair",
            listOf(DiscSetMoveOption.MOVE_SUBJECT_ONLY, DiscSetMoveOption.CANCEL),
            controller.discSetPrompt?.rows,
        )
    }

    @Test
    fun `focus lands on the first row of THIS warning, never on one remembered from the last`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()

        // A three-row warning, answered by moving to its LAST row and cancelling from there.
        val first = newController(authority, sinks)
        beginMoveOf(first, authority)
        commitViaDestinationList(first, authority)
        authority.requestFocus(FileBrowserDiscSetFocus.optionKey(DiscSetMoveOption.MOVE_WHOLE_SET))
        first.dispatch(ControllerAction.CANCEL)

        // …then a TWO-row one, which does not offer MOVE_WHOLE_SET at all.
        val second = newController(authority, sinks, discSet = warning(canTravel = false))
        beginMoveOf(second, authority)
        commitViaDestinationList(second, authority)

        assertEquals(
            "the rows are OPTION-derived, so a remembered MOVE_WHOLE_SET would validate perfectly " +
                "against a screen that no longer offers it -- landing the ring on a row that is not " +
                "there, which DefaultFocusAuthority.requestFocus reports as success because the key is " +
                "simply unregistered (BUG#01's silent no-op)",
            FileBrowserDiscSetFocus.optionKey(DiscSetMoveOption.MOVE_SUBJECT_ONLY),
            authority.state.value.focused,
        )

        second.dispatch(ControllerAction.CONFIRM)
        assertTrue(
            "and the press that follows moves ONE file -- never the whole set the previous screen was " +
                "offering when the player left it",
            sinks.movedSets.isEmpty(),
        )
        assertEquals(listOf(disc2 to "/root/Roms/archive"), sinks.moved)
    }

    @Test
    fun `an ordinary file is never delayed by the check`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        val controller = newController(authority, sinks, discSet = null)

        beginMoveOf(controller, authority)
        commitViaDestinationList(controller, authority)

        assertFalse(controller.discSetPromptOpen)
        assertEquals(
            "**the same frame as the press.** The lookup is launched UNDISPATCHED, so one that answers " +
                "without suspending dispatches the move inline -- the property the async listing seam " +
                "was rebuilt to hold after runBlocking measured 33ms for three files.",
            listOf(disc2 to "/root/Roms/archive"),
            sinks.moved,
        )
        assertEquals("and it was asked, exactly once", 1, sinks.lookups)
    }

    @Test
    fun `an unwired lookup preserves the previous behavior`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        val controller = newController(authority, sinks, wired = false)

        beginMoveOf(controller, authority)
        commitViaDestinationList(controller, authority)

        assertEquals(
            "both halves or neither, matching the async listing seam: a controller with no library and no " +
                "scope moves the file, which is what every test in this module that does not name the " +
                "seam is relying on",
            listOf(disc2 to "/root/Roms/archive"),
            sinks.moved,
        )
        assertFalse(controller.discSetPromptOpen)
    }

    @Test
    fun `a COPY is never gated, because a copy breaks no playlist`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        val controller = newController(authority, sinks)

        controller.open()
        listOf("/root/Roms", "/root/Roms/ps1", "/root/Roms/ps1/FF9").forEach { path ->
            authority.requestFocus(FileBrowserFocus.tileKey(path))
            controller.dispatch(ControllerAction.CONFIRM)
        }
        authority.requestFocus(FileBrowserFocus.tileKey(disc2))
        controller.handleInspectorSecondaryTap()
        authority.requestFocus(FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.COPY))
        controller.dispatch(ControllerAction.CONFIRM)
        commitViaDestinationList(controller, authority)

        assertFalse(controller.discSetPromptOpen)
        assertEquals(
            "the original stays where the playlist names it, so the set is untouched -- warning here " +
                "would be crying wolf on the one transfer that is always safe",
            listOf(disc2 to "/root/Roms/archive"),
            sinks.copied,
        )
        assertEquals("and the library is not even asked", 0, sinks.lookups)
    }

    @Test
    fun `the warning is a hard modal - no press reaches the listing underneath`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        val controller = newController(authority, sinks)
        beginMoveOf(controller, authority)
        commitViaDestinationList(controller, authority)
        val focusedBefore = authority.state.value.focused

        listOf(
            ControllerAction.SECONDARY,
            ControllerAction.SYSTEM_SELECT,
            ControllerAction.SYSTEM_START,
            ControllerAction.NAV_LEFT,
            ControllerAction.NAV_RIGHT,
        ).forEach { action ->
            assertTrue("$action must be consumed", controller.dispatch(action))
        }

        assertTrue(
            "SELECT deletes on the listing underneath: answering \"this would break your game\" by " +
                "destroying the file is the worst available reading of the press",
            controller.discSetPromptOpen,
        )
        assertEquals("and nothing moved the ring off the row the player is answering", focusedBefore, authority.state.value.focused)
        assertTrue(sinks.moved.isEmpty() && sinks.movedSets.isEmpty())
    }

    // ── cancellation ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a lookup cancelled by teardown dispatches NOTHING`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        gate = CompletableDeferred()
        val controller = newController(authority, sinks)

        beginMoveOf(controller, authority)
        commitViaDestinationList(controller, authority)
        assertEquals("the lookup is parked, mid-suspend", 1, sinks.lookups)
        assertTrue(sinks.moved.isEmpty())

        controller.close()
        gate!!.complete(Unit)

        assertTrue(
            "**the first version of this dispatched the move.** `runCatching { resolve(path) }` catches " +
                "CancellationException like any other throwable, getOrNull() answers null, and null is " +
                "the arm that MOVES -- so cancelling a lookup performed the press it was supposed to " +
                "drop, into a session that is over, with a destination from the session before it.",
            sinks.moved.isEmpty() && sinks.movedSets.isEmpty(),
        )
        assertFalse(controller.discSetPromptOpen)
    }

    @Test
    fun `a superseded lookup decides nothing, even after it answers`() {
        val authority = DefaultFocusAuthority()
        val sinks = Sinks()
        gate = CompletableDeferred()
        val controller = newController(authority, sinks, discSet = null)

        beginMoveOf(controller, authority)
        commitViaDestinationList(controller, authority)
        // A SECOND full move of the same file, with the first lookup still parked mid-suspend. No
        // `open()` in between -- this must be a supersede, not a teardown, or it re-tests the case above.
        listOf("/root/Roms", "/root/Roms/ps1", "/root/Roms/ps1/FF9").forEach { path ->
            authority.requestFocus(FileBrowserFocus.tileKey(path))
            controller.dispatch(ControllerAction.CONFIRM)
        }
        authority.requestFocus(FileBrowserFocus.tileKey(disc2))
        controller.handleInspectorSecondaryTap()
        authority.requestFocus(FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.MOVE))
        controller.dispatch(ControllerAction.CONFIRM)
        commitViaDestinationList(controller, authority)
        assertEquals("both lookups are parked, neither has decided", 2, sinks.lookups)

        gate!!.complete(Unit)

        assertEquals(
            "ONE move, from the press that is still current. Without the generation guard and the " +
                "cancellation rethrow, the superseded lookup dispatches too and the player's file is " +
                "moved twice off one intention -- [load]'s own generation idiom, for its own reason.",
            1,
            sinks.moved.size,
        )
    }
}

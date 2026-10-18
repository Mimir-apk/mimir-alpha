package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The async listing seam avoids blocking the main thread.
 *
 * The gap has a number. Perfetto on the Odin, backing out of a folder: **~90 ms with the main thread
 * parked in `runBlocking`**, drawing nothing, before the parent listing appeared. That gap produced a second visible folder settlement. `FileBrowserStore.listDirectory` blocked the
 * thread the player presses buttons on, and this class had no way to let it not.
 *
 * **No `kotlinx-coroutines-test` anywhere in this tree**, and this file deliberately does not need it.
 * [Filesystem] gates individual paths with a [CompletableDeferred]: an ungated path answers WITHOUT
 * suspending, a gated one suspends until the test opens it, and [Dispatchers.Unconfined] plus
 * `CoroutineStart.UNDISPATCHED` means both halves run inline on the test thread. Every assertion below is
 * therefore about ordering, not about timing, and there is nothing to flake.
 *
 * The seam is a PAIR (`listDirectoryAsync` + `loadScope`) and optional on purpose: every other controller
 * test in this module supplies neither and keeps the synchronous behaviour it was written against. That
 * is the pin in `the seam is opt-in` below.
 */
class FileBrowserAsyncLoadTest {

    private val tree: Map<String, List<FileItem>> = mapOf(
        "/root" to listOf(
            FileItem(name = "Games", path = "/root/Games", isDirectory = true),
            FileItem(name = "readme.txt", path = "/root/readme.txt", extension = "txt"),
        ),
        "/root/Games" to listOf(
            FileItem(name = "Sub", path = "/root/Games/Sub", isDirectory = true),
            FileItem(name = "game.gba", path = "/root/Games/game.gba", extension = "gba"),
        ),
        "/root/Games/Sub" to listOf(
            FileItem(name = "Deeper", path = "/root/Games/Sub/Deeper", isDirectory = true),
            FileItem(name = "deep.md", path = "/root/Games/Sub/deep.md", extension = "md"),
        ),
        "/root/Games/Sub/Deeper" to listOf(
            FileItem(name = "leaf.md", path = "/root/Games/Sub/Deeper/leaf.md", extension = "md"),
        ),
    )

    /**
     * A filesystem whose reads can be held open one path at a time.
     *
     * [list] is the suspend seam. A path with no gate returns without ever suspending — which is exactly
     * the shape `FileBrowserStore`'s per-path cache has, and the shape the "same frame as the press"
     * pins below depend on.
     */
    private inner class Filesystem {
        private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val syncReads = mutableListOf<String>()
        val asyncReads = mutableListOf<String>()

        fun hold(path: String) {
            gates[path] = CompletableDeferred()
        }

        fun release(path: String) {
            gates.remove(path)?.complete(Unit)
        }

        fun sync(path: String): List<FileItem> {
            syncReads += path
            return tree[path].orEmpty()
        }

        suspend fun list(path: String): List<FileItem> {
            asyncReads += path
            gates[path]?.await()
            return tree[path].orEmpty()
        }
    }

    private fun newController(
        authority: DefaultFocusAuthority,
        fs: Filesystem,
        async: Boolean = true,
        scope: FocusScope = FileBrowserFocus.DefaultScope,
    ): FileBrowserController = FileBrowserController(
        authority = authority,
        rootPath = "/root",
        mode = FileBrowserMode.MANAGE,
        listDirectory = { path -> fs.sync(path) },
        onChoose = {},
        onDismiss = {},
        scope = scope,
        listDirectoryAsync = if (async) ({ path -> fs.list(path) }) else null,
        // Unconfined, not a test dispatcher: combined with the controller's UNDISPATCHED start, the load
        // body runs on whichever thread touched it -- the test thread -- both before and after a gate
        // opens. Production passes a MAIN scope, which is the requirement the seam actually has.
        loadScope = if (async) CoroutineScope(Dispatchers.Unconfined) else null,
    )

    private fun openedAtRoot(authority: DefaultFocusAuthority, fs: Filesystem): FileBrowserController =
        newController(authority, fs).also { it.open() }

    @Test
    fun `the seam is opt-in - with no scope the listing stays synchronous`() {
        val authority = DefaultFocusAuthority()
        val fs = Filesystem()
        val controller = newController(authority, fs, async = false)
        fs.hold("/root/Games") // would block forever if this browser used the async path

        controller.open()
        controller.dispatch(ControllerAction.CONFIRM)

        assertEquals("/root/Games", controller.currentPath)
        assertEquals(
            "a controller given no async pair must never touch it -- this is what keeps the other ~25 " +
                "controller tests in this module deterministic without a test dispatcher",
            emptyList<String>(),
            fs.asyncReads,
        )
    }

    @Test
    fun `the FIRST load is synchronous even with the seam wired`() {
        val authority = DefaultFocusAuthority()
        val fs = Filesystem()
        val controller = newController(authority, fs)
        fs.hold("/root")

        controller.open()

        assertEquals(
            "an async first load would compose an EMPTY browser and pop the rows in afterwards -- a new " +
                "visual and a new directoryArrival over an empty partition, which is a design change",
            listOf("Games"),
            controller.partition.tomes.map { it.name },
        )
        assertEquals(listOf("/root"), fs.syncReads)
    }

    @Test
    fun `a listing answered from memory applies in the SAME call - no dispatch, no extra frame`() {
        val authority = DefaultFocusAuthority()
        val fs = Filesystem()
        val controller = openedAtRoot(authority, fs)

        // No gate on /root/Games: the suspend function returns without suspending, exactly as
        // FileBrowserStore's cache does on a return visit.
        controller.dispatch(ControllerAction.CONFIRM)

        assertEquals(
            "UNDISPATCHED is what buys this: a cache-answered listing must land in the same frame as the " +
                "press, or backing out of a folder costs a frame it did not cost before",
            "/root/Games",
            controller.currentPath,
        )
        assertEquals(listOf("Sub"), controller.partition.tomes.map { it.name })
        assertEquals(listOf("/root/Games"), fs.asyncReads)
    }

    @Test
    fun `a cold directory holds the outgoing listing instead of freezing the thread`() {
        val authority = DefaultFocusAuthority()
        val fs = Filesystem()
        val controller = openedAtRoot(authority, fs)
        fs.hold("/root/Games")

        controller.dispatch(ControllerAction.CONFIRM)

        // The press returned. Before this seam, the thread was parked inside the read and nothing --
        // input, animation, the frame itself -- could run until it finished.
        assertEquals("/root", controller.currentPath)
        assertEquals(
            "the browser must keep drawing the listing it already has, which is the same picture the " +
                "blocking version drew during its own read, minus the freeze",
            listOf("Games"),
            controller.partition.tomes.map { it.name },
        )

        fs.release("/root/Games")

        assertEquals("/root/Games", controller.currentPath)
        assertEquals(listOf("Sub"), controller.partition.tomes.map { it.name })
    }

    @Test
    fun `focus is seated against the scope active when the rows ARRIVE, not the one active at the press`() {
        val authority = DefaultFocusAuthority()
        val fs = Filesystem()
        val controller = openedAtRoot(authority, fs)
        fs.hold("/root/Games")
        controller.dispatch(ControllerAction.CONFIRM)

        // The player reaches another screen while the directory is being read -- the queue, in production,
        // which is one press away and is where they go to watch a delete. the earlier change is the precedent: a load
        // that acts on a stale `browseActive` clears the FOREIGN scope's focus and then silently fails to
        // restore its own, leaving a dead D-pad on a screen that looks ready.
        val foreign = FocusScope("queue-ish")
        authority.pushScope(foreign)
        val foreignKey = FocusKey("queue-row")
        authority.register(foreignKey, foreign, FocusBounds(left = 0f, top = 0f, right = 200f, bottom = 100f))
        authority.requestFocus(foreignKey)

        fs.release("/root/Games")

        assertEquals(foreign, authority.state.value.scope)
        assertEquals(
            "the arriving listing belongs to a scope that is no longer active, so it must seat its focus " +
                "in memory and take nothing from the screen the player is actually on",
            foreignKey,
            authority.state.value.focused,
        )
        // ...and the browse scope still got its landing, waiting for the pop.
        authority.popScope()
        assertEquals(FileBrowserFocus.tileKey("/root/Games/Sub"), authority.state.value.focused)
    }

    @Test
    fun `two descents inside one load window leave ONE stack entry, so one Up returns to the root`() {
        val authority = DefaultFocusAuthority()
        val fs = Filesystem()
        val controller = openedAtRoot(authority, fs)
        fs.hold("/root/Games")

        controller.dispatch(ControllerAction.CONFIRM) // in flight
        controller.dispatch(ControllerAction.CONFIRM) // same visible listing, same tile, supersedes it
        fs.release("/root/Games")

        assertEquals("/root/Games", controller.currentPath)
        controller.dispatch(ControllerAction.CANCEL)

        assertEquals(
            "Enter pushes 'the directory I am leaving'; a superseded Enter has to take its push back, or " +
                "the second Up goes nowhere and the player is stuck one level down",
            "/root",
            controller.currentPath,
        )
        assertTrue(controller.atRoot)
    }

    @Test
    fun `two Ups inside one load window climb TWO levels - the mirror case, and it must not be undone`() {
        val authority = DefaultFocusAuthority()
        val fs = Filesystem()
        val controller = openedAtRoot(authority, fs)
        controller.dispatch(ControllerAction.CONFIRM) // /root/Games
        controller.dispatch(ControllerAction.CONFIRM) // /root/Games/Sub
        assertEquals("/root/Games/Sub", controller.currentPath)

        fs.hold("/root/Games")
        controller.dispatch(ControllerAction.CANCEL) // in flight towards /root/Games
        controller.dispatch(ControllerAction.CANCEL) // supersedes it, towards /root

        assertEquals(
            "Up CONSUMES a stack entry per press. Undoing the superseded one -- the symmetry that looks " +
                "right -- would put the entry back and land the second press where the first already was",
            "/root",
            controller.currentPath,
        )
        assertTrue(controller.atRoot)

        // The superseded read finally lands. It must apply nothing at all.
        fs.release("/root/Games")
        assertEquals("/root", controller.currentPath)
        assertNotEquals("/root/Games", controller.currentPath)
    }

    @Test
    fun `CANCEL during an in-flight descent ascends - it does not empty the stack and close the browser`() {
        val authority = DefaultFocusAuthority()
        val fs = Filesystem()
        val controller = openedAtRoot(authority, fs)
        controller.dispatch(ControllerAction.CONFIRM) // at /root/Games, stack = [/root]
        assertEquals("/root/Games", controller.currentPath)

        fs.hold("/root/Games/Sub")
        controller.dispatch(ControllerAction.CONFIRM) // descent in flight, stack = [/root, /root/Games]
        controller.dispatch(ControllerAction.CANCEL) // the player changes their mind mid-read

        assertEquals("/root", controller.currentPath)
        assertTrue(
            "atRoot IS stack.isEmpty(), so a stack corrupted by a mixed pair does not merely mis-navigate " +
                "-- the browser believes it is at the root while standing in a subdirectory, and the next " +
                "CANCEL closes it instead of going up",
            controller.atRoot,
        )

        fs.release("/root/Games/Sub")
        assertEquals("the abandoned descent must apply nothing", "/root", controller.currentPath)
    }

    @Test
    fun `and the ascent it performs is ONE level, not two - the in-flight push is not consumed`() {
        val authority = DefaultFocusAuthority()
        val fs = Filesystem()
        val controller = openedAtRoot(authority, fs)
        controller.dispatch(ControllerAction.CONFIRM) // /root/Games
        controller.dispatch(ControllerAction.CONFIRM) // /root/Games/Sub, stack = [/root, /root/Games]

        fs.hold("/root/Games/Sub/Deeper") // a descent that will not resolve while the next press lands
        controller.dispatch(ControllerAction.CONFIRM)
        controller.dispatch(ControllerAction.CANCEL)

        assertEquals(
            "the pending Enter's push must come off BEFORE this pop, or the pop eats it and the player " +
                "climbs two levels for one press",
            "/root/Games",
            controller.currentPath,
        )
    }

    @Test
    fun `a descent during an in-flight ascent puts the ascent's step back, so no level is skipped`() {
        val authority = DefaultFocusAuthority()
        val fs = Filesystem()
        val controller = openedAtRoot(authority, fs)
        controller.dispatch(ControllerAction.CONFIRM) // /root/Games
        controller.dispatch(ControllerAction.CONFIRM) // /root/Games/Sub, stack = [/root, /root/Games]

        fs.hold("/root/Games")
        controller.dispatch(ControllerAction.CANCEL) // ascent in flight; it has already popped /root/Games
        controller.dispatch(ControllerAction.CONFIRM) // the player descends from the listing still on screen
        fs.release("/root/Games") // the abandoned read finally answers; it must apply nothing

        assertEquals("/root/Games/Sub/Deeper", controller.currentPath)

        controller.dispatch(ControllerAction.CANCEL)
        assertEquals("/root/Games/Sub", controller.currentPath)
        controller.dispatch(ControllerAction.CANCEL)
        assertEquals(
            "the abandoned Up consumed /root/Games and never navigated there. Leaving it consumed makes " +
                "the next ascent skip a whole level of the player's own hierarchy.",
            "/root/Games",
            controller.currentPath,
        )
        controller.dispatch(ControllerAction.CANCEL)
        assertEquals("/root", controller.currentPath)
        assertTrue(controller.atRoot)
    }

    @Test
    fun `a listing still in flight when the browser closes never lands on the torn-down scope`() {
        val authority = DefaultFocusAuthority()
        val fs = Filesystem()
        val controller = openedAtRoot(authority, fs)
        fs.hold("/root/Games")
        controller.dispatch(ControllerAction.CONFIRM)

        controller.close()
        fs.release("/root/Games")

        assertEquals("/root", controller.currentPath)
        assertFalse(
            "applyListing writes focus into a scope close() has already popped -- a landing here would " +
                "resurrect the browser's focus over whatever surface took the screen",
            authority.state.value.scope == FileBrowserFocus.DefaultScope,
        )
    }
}

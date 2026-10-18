package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **C-3 / C-1 — is the queue screen actually REACHABLE, and is the door to it visible?**
 *
 * This is the file that guards the safety coupling. Delete has no confirmation dialog, so safety depends on reachable Undo; Undo lives only on a queue card. So a delete with an
 * unreachable or invisible queue has **no protection at all**, and every assertion below is about one of
 * the three links in that chain:
 *
 *  1. the surface reports a queue sink, which is what makes `queueAvailable` true (non-null
 *     means wired, so there is no second flag to forget);
 *  2. START therefore opens the screen, and the screen REPLACES the browse content rather than floating
 *     over it;
 *  3. the footer and the header both advertise it, persistently, with the live count.
 *
 * **Two instruments, and the difference matters.** Links 1 and 2 are driven for real — a
 * `DefaultFocusAuthority`, a real [FileBrowserController], real dispatches. Link 3's *rendering* is
 * source-pinned, because this module configures no Compose harness (plain JUnit; no Robolectric, no
 * `createComposeRule`). A source pin proves the call site names the right values; it proves nothing
 * about pixels. Hardware is still the gate for anything about how this looks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserQueueWiringTest {
    private val english = filesEnglish()

    private val tree: Map<String, List<FileItem>> = mapOf(
        "/root" to listOf(
            FileItem(name = "Games", path = "/root/Games", isDirectory = true),
            FileItem(name = "game.gba", path = "/root/game.gba", extension = "gba"),
        ),
    )

    private fun controller(
        authority: DefaultFocusAuthority,
        mode: FileBrowserMode = FileBrowserMode.MANAGE,
    ) = FileBrowserController(
        authority = authority,
        rootPath = "/root",
        mode = mode,
        listDirectory = { path -> tree[path].orEmpty() },
        onChoose = {},
        onDismiss = {},
        onDelete = {},
    )

    // ──────────────────────────────────────────── 1 · the sink is what makes the screen exist ──

    @Test
    fun `without a sink there is no queue at all, and START is inert`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority)
        c.open()

        assertFalse(
            "queueAvailable must be false before FileBrowserSurface reports a sink -- a screen whose " +
                "buttons are focusable, advertised and inert is worse than no screen",
            c.queueAvailable,
        )
        assertTrue("the press is still consumed, never fallen through", c.dispatch(ControllerAction.SYSTEM_START))
        assertFalse("and nothing opened", c.queueOpen)
        c.close()
    }

    @Test
    fun `the sink the surface installs makes START open the screen`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority)
        c.open()
        c.setQueueAction { }

        assertTrue("a wired sink IS the availability flag", c.queueAvailable)
        assertTrue(c.dispatch(ControllerAction.SYSTEM_START))
        assertTrue(
            "START must open the queue once a sink exists. If this fails, the delete binding ships with " +
                "no reachable Undo.",
            c.queueOpen,
        )
        assertEquals(
            "the queue's own focus scope must be the active one, or the D-pad is driving the folio " +
                "underneath a screen the player is looking at",
            c.queueScope,
            authority.state.value.scope,
        )
        c.close()
    }

    /** PICK stays incapable by MODE, not by restriction. Even with a sink. */
    @Test
    fun `PICK cannot open a queue even when a sink is wired`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority, mode = FileBrowserMode.PICK)
        c.open()
        c.setQueueAction { }

        assertFalse("MANAGE-only, by mode", c.queueAvailable)
        c.dispatch(ControllerAction.SYSTEM_START)
        assertFalse("no path exists to the screen in PICK", c.queueOpen)
        c.close()
    }

    // ─────────────────────────────────── 2 · the screen replaces the browse content, not overlays ──

    /**
     * The queue takes the same slot search takes, in the same `if / else if` chain.
     *
     * **An overlay would keep the folio's own scroll effect and its whole focus graph composed
     * underneath a screen that has its own** — two live graphs on one list, which is half of this
     * module's historical D-pad defect. `FileBrowserSurface.kt`'s search branch states the reason and
     * this pin is what stops the queue being added as a floating `Box` beside the keyboard instead.
     */
    @Test
    fun `the queue screen replaces the browse content rather than floating over it`() {
        val source = surfaceSource()
        val contentRow = source.sliceBetween(
            after = "horizontalArrangement = Arrangement.spacedBy(14.dp),",
            before = "FileBrowserFooterLegend(",
        )
        assertTrue(
            "the queue must be composed inside the SAME branch chain as search and the folio",
            contentRow.contains("if (controller.queueOpen) {") &&
                contentRow.contains("FileBrowserQueueScreen("),
        )
        assertTrue(
            "and the folio must be the ELSE of that chain, never a sibling composed alongside it",
            contentRow.contains("} else if (controller.searchOpen && search != null) {"),
        )
        // The floating slot in this file is the keyboard, composed outside the Column. The queue must
        // not have been added there.
        val floating = source.sliceBetween(
            after = "MimirSheetHost(",
            before = "/**",
        )
        assertFalse(
            "the queue must NOT be composed in the floating slot -- that is the overlay shape",
            floating.contains("FileBrowserQueueScreen"),
        )
    }

    @Test
    fun `the surface installs the sink and publishes the store's queue`() {
        val source = surfaceSource()
        assertTrue(
            "FileBrowserQueueSink is what binds Undo/Keep deleted/Dismiss/Resolve and reports " +
                "availability; without this call the screen is unreachable",
            source.contains("FileBrowserQueueSink("),
        )
        listOf("onUndo = onUndo", "onReleaseUndo = onReleaseUndo", "onDismiss = onDismiss", "onResolveConflict = onResolveConflict")
            .forEach { arg ->
                assertTrue("the sink must be handed `$arg` from the host", source.contains(arg))
            }
        val sink = queueSurfaceSource().sliceBetween(
            after = "internal fun FileBrowserQueueSink(",
            before = "internal fun fileBrowserQueueDispatch(",
        )
        assertTrue(
            "publishQueue must run from a LaunchedEffect keyed on the list. A SideEffect would re-run " +
                "the focus repair inside publishQueue on every recomposition.",
            sink.contains("LaunchedEffect(controller, queue) { controller.publishQueue(queue) }"),
        )
        assertTrue(
            "the sink must be released on dispose -- setQueueAction(null) is the documented path that " +
                "closes a screen whose engine has been taken away",
            sink.contains("onDispose { controller.setQueueAction(null) }"),
        )
        assertTrue(
            "the host callbacks must be read through rememberUpdatedState: the lambda outlives the " +
                "composition that built it, so a captured callback would route Undo into a previous " +
                "session's store",
            sink.contains("rememberUpdatedState(onUndo)") && sink.contains("rememberUpdatedState(onReleaseUndo)"),
        )
    }

    // ───────────────────────────────────────────── 3 · the door is advertised, persistently ──

    /**
     * **C-1's footer half.** This pins that the surface actually threads the
     * three values they need. Without the threading, SELECT and START are live presses with no legend
     * entry — which `fileBrowserFooterHints`' own standing rule calls worse than no binding — and the
     * count that makes Undo findable never renders.
     */
    @Test
    fun `the browse footer is threaded with delete, queue and the live count`() {
        val call = surfaceSource().sliceBetween(
            after = "fileBrowserFooterHints(",
            before = ")\n            },",
        )
        listOf(
            // F-1, 2026-08-18: `deleteAvailable = controller.deleteAvailable` renamed to
            // `actionsAvailable = actionsAvailable` -- SELECT no longer deletes directly, it opens the
            // overlay Delete now lives inside, and the legend reads the surface's own local (not a
            // `controller.` property directly) because it must be the collected-snapshot side of
            // fileBrowserActionsAvailable, never the live one -- see that val's own declaration comment.
            "actionsAvailable = actionsAvailable",
            "queueAvailable = controller.queueAvailable",
            "queueCount = controller.queueEntries.size",
        ).forEach { arg ->
            assertTrue(
                "the browse legend must be given `$arg`, or Lane B's binding is invisible. Found: <$call>",
                call.contains(arg),
            )
        }
    }

    @Test
    fun `the queue screen gets its own legend, not the browse one`() {
        val source = surfaceSource()
        assertTrue(
            "reusing the browse legend here would advertise OPEN, EXTRACT and a START hint pointing at " +
                "the screen the player is already standing on",
            source.contains("fileBrowserQueueFooterHints("),
        )
        assertTrue(
            "A's word must come from the FOCUSED button's own label -- one fixed word would be wrong on " +
                "five of the six buttons, and on two of them in the direction that loses data",
            source.contains("focusedAction = FileBrowserQueueFocus.targetOf(queueFocusedKey)?.action"),
        )
    }

    /**
     * **The persistence policy, asserted as behaviour rather than as a render.** UI-03 established that a
     * hint appearing and vanishing during navigation does not register as a control, so the queue
     * affordance is drawn on the same terms empty or not — only the number is live.
     */
    @Test
    fun `the queue is advertised even while it is empty`() {
        val empty = fileBrowserFooterHints(english, focused = null, atRoot = true, queueAvailable = true, queueCount = 0)
        val loaded = fileBrowserFooterHints(english, focused = null, atRoot = true, queueAvailable = true, queueCount = 2)

        assertTrue(
            "an EMPTY queue must still advertise START. Hiding it is how a player learns the button does " +
                "not exist, and then never finds Undo after their first delete.",
            empty.contains("START" to "Queue"),
        )
        assertTrue("a loaded queue names the count", loaded.contains("START" to "Queue · 2"))
        assertFalse(
            "with no queue at all, nothing may be advertised -- PICK is incapable, not restricted",
            fileBrowserFooterHints(english, focused = null, atRoot = true, queueAvailable = false, queueCount = 0)
                .any { it.first == "START" },
        )
    }

    /** The header half of C-1. Same gate as the footer hint, drawn from the same two values. */
    @Test
    fun `the header carries a persistent queue plate on the same terms as the footer hint`() {
        val header = surfaceSource().sliceBetween(
            after = "fun FileBrowserHeader(",
            before = "/**\n * The design's own magnifier",
        )
        assertTrue(
            "the plate must be gated on queueAvailable ALONE -- adding `&& queueCount > 0` would make it " +
                "appear and vanish during navigation, making the control hard to discover",
            header.contains("if (queueAvailable) {"),
        )
        assertFalse(
            "no count gate on the plate's visibility",
            header.contains("if (queueAvailable && queueCount"),
        )
        assertTrue("and the count is what it renders", header.contains("count = queueCount"))
    }

    /**
     * **The header plate opens a NEW path to `openQueue`, and it must not be able to stack two screens.**
     *
     * Through dispatch the two are mutually exclusive for free: the only press that reaches `OpenQueue`
     * is a START in the browse table, and `openSearch` closes the queue on its own side. So
     * `FileBrowserController.openQueue` carries no search guard — correctly, for the paths that existed
     * before this lane. The header stays composed above the search screen, so a tap on the plate is
     * reachable from inside search; without a `closeSearch()` first it would push the queue's scope on
     * top of search's and leave two screens' focus graphs alive at once, which is exactly what the
     * "the queue REPLACES the browse content" contract exists to prevent.
     *
     * Driven for real rather than source-pinned, because the failure is a live scope-stack state.
     */
    @Test
    fun `opening the queue from the header leaves no search screen stacked underneath`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority)
        c.open()
        c.setQueueAction { }
        c.setSearchAvailable(true)
        c.openSearch()
        assertTrue("precondition: search owns the scope", c.searchOpen)

        // Exactly what the header plate's onQueue handler does.
        c.closeSearch()
        c.openQueue()

        assertTrue("the queue opened", c.queueOpen)
        assertFalse(
            "search must have been closed first. Left open, its focus graph stays composed under a " +
                "screen with its own -- two live graphs, which is half of this module's D-pad defect.",
            c.searchOpen,
        )
        c.closeQueue()
        assertEquals(
            "and leaving the queue must land back in the BROWSE scope, not in a stranded search scope",
            c.scope,
            authority.state.value.scope,
        )
        c.close()
    }

    /** The handler in the surface is the one asserted above -- pinned so the `closeSearch()` cannot be
     * tidied away as redundant. */
    @Test
    fun `the header plate's handler closes search before opening the queue`() {
        val handler = surfaceSource().sliceBetween(
            after = "onQueue = {",
            before = "palette = palette,",
        )
        assertTrue(
            "without closeSearch() the plate can stack the queue on top of search",
            handler.contains("controller.closeSearch()"),
        )
        assertTrue(
            "and closing must come BEFORE opening",
            handler.indexOf("controller.closeSearch()") < handler.indexOf("controller.openQueue()"),
        )
    }

    /** The header plate is a TOUCH shortcut, not a focus participant — the same judgement the search
     * icon records. A header focus slot would put a stop between the breadcrumbs and the folio on every
     * NAV_UP. */
    @Test
    fun `the header queue plate takes no focus`() {
        val plate = surfaceSource().sliceBetween(
            after = "private fun FileBrowserQueueHeaderPlate(",
            before = "/** The plate's word.",
        )
        assertFalse(
            "the header plate must not register a focus key -- SYSTEM_START already reaches this screen " +
                "from the D-pad, with a footer hint advertising it",
            plate.contains("mimirFocusable("),
        )
        assertTrue("it is still reachable by touch", plate.contains("detectTapGestures"))
    }

    // ───────────────────────────────────────────────────────────── Helpers ──

    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", contains(before))
        return substringAfter(after).substringBefore(before)
    }

    private fun surfaceSource(): String = moduleSource("FileBrowserSurface.kt")

    private fun queueSurfaceSource(): String = moduleSource("FileBrowserQueueSurface.kt")

    private fun moduleSource(name: String): String {
        val relative = "src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$name"
        val candidates = listOf(File(relative), File("feature-files/$relative"), File("../feature-files/$relative"))
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}

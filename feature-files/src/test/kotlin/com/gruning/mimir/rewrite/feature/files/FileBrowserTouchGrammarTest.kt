package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The touch grammar of the browse folio, pinned.** A tap activates immediately instead of requiring
 * an initial selection tap followed by an activation tap.
 *
 * ## The model this file enforces
 *
 * | Gesture | Meaning |
 * |---|---|
 * | **Tap** | **Activate this element** -- the same action its `CONFIRM` performs. Focus moves to the tapped element as a CONSEQUENCE of activating it, never as a prerequisite. |
 * | **Long-press** | The element's `SECONDARY`, where its surface binds one; nothing where it does not. Never `SYSTEM_SELECT`. |
 * | Drag / fling | Scroll. Never activates. |
 *
 * Touch is *activate-at-a-point*: one event, focus is a consequence. A controller is
 * *focus-then-activate*: two events, by nature. **Two grammars over one set of actions** -- which is the
 * invariant that replaced this module's old "one true path" reasoning:
 *
 * > **PER-ELEMENT ACTION PARITY.** An element's action *set*, and the *meaning* of each action, are
 * > identical regardless of which input triggered them. The *grammar of triggering* may differ. Any
 * > asymmetry must be declared in KDoc with its reason.
 *
 * ## Two defects, and only one of them lived here
 *
 * The product-wide survey  separates them, and this
 * file is deliberately about the second only:
 *
 *  - **Mechanism A** -- the focus-gated idiom `if (focused) onActivate() else requestFocus(key)`,
 *    16 sites across `feature-library`, `core-ui`, `feature-settings` and `app`. Two taps, no timing
 *    window: the second tap acts instantly. **Not in this module.**
 *  - **Mechanism B** -- Compose's double-tap callback on `detectTapGestures`. It existed at exactly two
 *    sites tree-wide, both of them the tile gestures in `FileBrowserSurface`, and it failed differently:
 *    it imposed a **timing window and a full platform double-tap timeout (~300ms) on every single tap,
 *    including the ones that were never going to be doubles.** See [FileBrowserController.handleTap]'s
 *    KDoc for the bytecode offsets that show the deferral.
 *
 * That difference is why the fix here was **removal** rather than conversion, and why an alias binding
 * would have been the worst outcome available: half the fix, all of the latency.
 *
 * ## What this file cannot prove
 *
 * `feature-files` configures no Compose test harness (no Robolectric, no `createComposeRule` --
 * `build.gradle.kts`), so nothing here can dispatch a real pointer event. The behavioural half is
 * asserted at [FileBrowserController]'s touch entry points, which is where the surface's gesture
 * lambdas land; the *wiring* between gesture and entry point is asserted as source text, this repo's
 * established fallback (same disclosure [FileBrowserGhostFreeIdentityTest] and
 * [FileBrowserSupportDimRenderTest] make). **Whether a tap now feels instant on the Odin is device-only,
 * and requires a device measurement.**
 */
class FileBrowserTouchGrammarTest {

    private val tree: Map<String, List<FileItem>> = mapOf(
        "/root" to listOf(
            FileItem(name = "Games", path = "/root/Games", isDirectory = true),
            FileItem(name = "bundle.zip", path = "/root/bundle.zip", extension = "zip"),
            FileItem(name = "readme.txt", path = "/root/readme.txt", extension = "txt"),
        ),
        "/root/Games" to listOf(
            FileItem(name = "Sonic.gba", path = "/root/Games/Sonic.gba", extension = "gba"),
        ),
    )

    private class Spy {
        var extracted: String? = null
        var deleted: String? = null
        var chosen: String? = null
    }

    private fun newController(
        authority: DefaultFocusAuthority,
        spy: Spy,
        mode: FileBrowserMode = FileBrowserMode.MANAGE,
    ): FileBrowserController = FileBrowserController(
        authority = authority,
        rootPath = "/root",
        mode = mode,
        listDirectory = { path -> tree[path].orEmpty() },
        onChoose = { spy.chosen = it },
        onDismiss = {},
        onExtract = { spy.extracted = it },
        // Wired so "long-press never deletes" is a real refusal rather than an unwired no-op. With this
        // null, SYSTEM_SELECT cannot produce a Delete at all and the assertion would be vacuous.
        onDelete = { spy.deleted = it },
    )

    private fun tileNamed(controller: FileBrowserController, name: String): FileBrowserTile =
        (controller.partition.tomes + controller.partition.files).single { it.name == name }

    // ─────────────────────────────────────────────────────────────────────── tap: one tap activates ──

    @Test
    fun `one tap on a tome opens it`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority, Spy())
        controller.open()

        controller.handleTap(tileNamed(controller, "Games"))

        assertEquals(
            "ONE tap must land inside the directory. Before 2026-08-14 this needed a second tap, and " +
                "the first tap was itself withheld for the platform double-tap timeout before it even " +
                "moved the ring -- so the fastest possible open was two gestures and ~300ms of nothing.",
            "/root/Games",
            controller.currentPath,
        )
    }

    @Test
    fun `one tap on a plain file focuses it and does nothing else -- and that is a DECLARED asymmetry`() {
        val authority = DefaultFocusAuthority()
        val spy = Spy()
        val controller = newController(authority, spy)
        controller.open()

        controller.handleTap(tileNamed(controller, "readme.txt"))

        assertEquals(
            "focus follows the finger -- the inspector reads focusedTile(), so this is what fills it",
            FileBrowserFocus.tileKey("/root/readme.txt"),
            authority.state.value.focused,
        )
        assertEquals("a tap on a file must not navigate", "/root", controller.currentPath)
        assertNull("and must not extract", spy.extracted)
        assertNull("and must never delete", spy.deleted)
    }

    /**
     * **The declaration half of PER-ELEMENT ACTION PARITY, asserted rather than asserted-about.** A
     * plain file cannot be OPENED by touch -- and it cannot be opened by a controller either, which is
     * the whole point: this is not touch being poorer, it is an action that does not exist yet for
     * anyone. `CONFIRM` on a FILE is `Consumed` in the one dispatch table.
     *
     * **[U] OPEN NAVIGATION DECISION.** Opening plain files would require a dispatch-table change
     * for BOTH inputs at once; that behavior remains undecided. This test pins today's
     * answer so that changing it is a visible, deliberate edit rather than a silent side effect of the
     * next surface pass.
     */
    @Test
    fun `touch cannot open a file -- and neither can the controller, which is why it is parity not a gap`() {
        val authority = DefaultFocusAuthority()
        val controller = newController(authority, Spy())
        controller.open()
        val file = tileNamed(controller, "readme.txt")

        // The pure table, both inputs' single source of truth.
        assertEquals(
            "CONFIRM on a FILE is Consumed -- the controller has no open either",
            FileBrowserCommand.Consumed,
            fileBrowserCommand(ControllerAction.CONFIRM, file, atRoot = true),
        )

        controller.handleTap(file)
        controller.dispatch(ControllerAction.CONFIRM)

        assertEquals("neither gesture nor press moves anywhere", "/root", controller.currentPath)
    }

    // ────────────────────────────────────────────────────────────── long-press: SECONDARY, or nothing ──

    /**
     * **F-1/R-13, 2026-08-18, supersedes this test's own former claim rather than merely aging past it.**
     * This test used to pin `handleLongPress` reaching `onExtract` directly, on the pre-F-1 SECONDARY
     * binding. `onDelete` being wired here (see [newController]'s own comment) now makes
     * `fileBrowserActionsAvailable` true for EVERY tile kind in this fixture -- Delete alone is enough to
     * give a DIRECTORY, an ARCHIVE and a FILE all at least one overlay row -- so SECONDARY (and therefore
     * long-press, which dispatches it) is quiet everywhere in this file's fixture now, exactly as X is on
     * the controller side (`fileBrowserCommand`'s SECONDARY arm).
     *
     * **This is a real, disclosed gap, not a silently-dropped assertion**: design spec §7/§10.2 flags
     * retargeting long-press to open the overlay instead as pending review, changing the earlier direct-Extract binding.
     * The current quiet behavior is preserved; retargeting remains deferred.
     */
    @Test
    fun `a long-press on an archive is quiet once actionsAvailable is true -- SECONDARY retired with X`() {
        val authority = DefaultFocusAuthority()
        val spy = Spy()
        val controller = newController(authority, spy)
        controller.open()

        controller.handleLongPress(tileNamed(controller, "bundle.zip"))

        assertNull(
            "long-press dispatches SECONDARY, the identical action X sends -- once actionsAvailable is " +
                "true (deleteAvailable alone is enough here) that action is Consumed, so touch's long-press " +
                "goes quiet in lockstep with the controller binding it mirrors, not silently ahead of it",
            spy.extracted,
        )
    }

    /**
     * **The NEW touch path to Extract, replacing the deleted test's claim.** SELECT (now reachable by
     * touch only through the inspector's re-pointed Extract…/Place… buttons, `FileBrowserController
     * .handleInspectorSecondaryTap` -- `FileBrowserController.kt`) opens the overlay; landing on its
     * Extract row and activating it reaches [Spy.extracted] exactly as the retired long-press binding
     * used to, one screen later.
     */
    @Test
    fun `X then the overlay's Extract row reaches onExtract -- the touch path this ticket moved it to`() {
        val authority = DefaultFocusAuthority()
        val spy = Spy()
        val controller = newController(authority, spy)
        controller.open()

        authority.requestFocus(FileBrowserFocus.tileKey("/root/bundle.zip"))
        controller.dispatch(ControllerAction.SECONDARY)
        authority.requestFocus(FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.EXTRACT))
        controller.dispatch(ControllerAction.CONFIRM)

        assertEquals(
            "extractChoiceAvailable is false in this fixture (no resolver/onExtractTo wired), so the " +
                "overlay's Extract row falls back to the legacy sibling-directory extract -- onExtract --" +
                " exactly as SECONDARY's own pre-F-1 fallback arm did",
            "/root/bundle.zip",
            spy.extracted,
        )
    }

    @Test
    fun `a long-press on a tome or a plain file does nothing -- long-press stays narrow`() {
        val authority = DefaultFocusAuthority()
        val spy = Spy()
        val controller = newController(authority, spy)
        controller.open()

        controller.handleLongPress(tileNamed(controller, "Games"))
        controller.handleLongPress(tileNamed(controller, "readme.txt"))

        assertEquals("a hold must not open a tome -- that is the tap's job", "/root", controller.currentPath)
        assertNull("nothing extractable, nothing offered", spy.extracted)
        assertNull("and nothing deleted, ever", spy.deleted)
    }

    /**
     * **The destructive-safety pin.** Deletes omit a confirmation because they use a
     * staged journal + Undo. This change must therefore not put a destructive action one gesture away
     * that was not one gesture away before.
     *
     * `SYSTEM_SELECT` (delete) has **no gesture bound to it at all** and this change did not give it
     * one: long-press maps to `SECONDARY`, tap maps to `CONFIRM`, and nothing maps to `SELECT`. An
     * extraction writes new files and removes none, so nothing reachable by a gesture in this browser is
     * destructive.
     */
    @Test
    fun `no gesture on any tile kind can delete -- SELECT stays gesture-unbound`() {
        val authority = DefaultFocusAuthority()
        val spy = Spy()
        val controller = newController(authority, spy)
        controller.open()

        listOf("Games", "bundle.zip", "readme.txt").forEach { name ->
            controller.handleTap(tileNamed(controller, name))
            // Re-resolve after every gesture: a tap on a tome navigates, so the tile list changes under us.
            controller.open()
            controller.handleLongPress(tileNamed(controller, name))
            controller.open()
        }

        assertNull(
            "not one of the six gestures may reach onDelete. The sink IS wired here (deleteAvailable " +
                "is true in MANAGE), so this refusal is real: only a SYSTEM_SELECT press, followed by " +
                "activating the overlay's own Delete row (F-1/R-3), produces a delete -- and no gesture " +
                "produces either.",
            spy.deleted,
        )

        // The control: the press-then-row that IS bound still deletes, so the assertion above is not
        // passing because the sink is dead. F-1/R-3: SELECT alone no longer deletes -- it opens the
        // overlay -- so the control now needs the second press its own row requires, on
        // FileBrowserActionOverlayChoice.kt's row-activation contract.
        controller.handleTap(tileNamed(controller, "readme.txt"))
        controller.dispatch(ControllerAction.SECONDARY)
        authority.requestFocus(FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.DELETE))
        controller.dispatch(ControllerAction.CONFIRM)
        assertEquals("the controller's own delete still works, one press later", "/root/readme.txt", spy.deleted)
    }

    // ──────────────────────────────────────────────────────────────────── the source-text half ──

    /**
     * **The absence pin.** This defect class is *positional* -- it was re-derived independently across
     * four modules in the Mechanism-A form -- so the fix has to be provable as absent, not merely
     * applied once.
     *
     * Scanned over `src/main` raw text, comments included, on purpose. For an ABSENCE assertion the
     * comment hazard runs the safe way round: a commented-out binding **fails this test loudly** instead
     * of keeping a text pin green, which is the failure mode this programme has been bitten by. The
     * cost is that production prose must not spell the identifier either -- so
     * [FileBrowserController.handleTap]'s KDoc names it in words and points here, and this test is the
     * one place in the module where the token appears.
     */
    @Test
    fun `no double-tap gesture is registered anywhere in this module`() {
        val forbidden = "onDoubleTap"
        val offenders = mainSources()
            .filter { it.readText().contains(forbidden) }
            .map { it.name }

        assertTrue(
            "`$forbidden` must not appear anywhere in feature-files/src/main. Registering it makes " +
                "Compose withhold EVERY single tap for the platform double-tap timeout before running " +
                "onTap -- leaving ~300ms without instant feedback. " +
                "It is not a compatibility alias and it is not free to leave lying around, even " +
                "commented out, because the next reader re-derives the workaround from it. Found in: " +
                "$offenders",
            offenders.isEmpty(),
        )
        assertTrue(
            "the sweep must actually have read files -- a zero-file scan asserts nothing",
            mainSources().size > 10,
        )
    }

    /**
     * **The wiring pin.** The absence test above would still pass if both tile gestures were deleted
     * outright, leaving tiles that cannot be touched at all. These two slices are bounded by the NEXT
     * DECLARATION rather than by a brace pattern, and each anchor is presence-checked, so a rename or a
     * reformat fails loudly instead of silently degrading to an empty or whole-file slice.
     */
    @Test
    fun `both tile gestures route one tap to handleTap and a hold to handleLongPress`() {
        val source = sourceOf("FileBrowserSurface.kt")

        val tome = source.sliceBetween(after = "private fun TomeTile(", before = "private fun TomeGlyph(")
        val file = source.sliceBetween(after = "private fun FileTile(", before = "internal fun fileBrowserDisplayName(")

        listOf("TomeTile" to tome, "FileTile" to file).forEach { (name, slice) ->
            assertTrue(
                "$name must still be a tap target at all -- an untouchable tile is not a fix",
                slice.contains("onTap = { controller.handleTap(tile) }"),
            )
            assertTrue(
                "$name's hold must reach SECONDARY through the controller's guarded entry point, not " +
                    "through a lambda of its own",
                slice.contains("onLongPress = { controller.handleLongPress(tile) }"),
            )
            assertFalse(
                "$name must not reach for Modifier.clickable/combinedClickable. Verified against " +
                    "foundation-android:1.7.3: AbstractClickableNode constructs a FocusableNode, which " +
                    "delegates to FocusTargetModifierNode() -- a SECOND Compose focus target beside " +
                    "this module's single FocusAuthority one, and invisible to " +
                    "tools/mimir_ui_invariants_gate.py, which greps for the literal `.focusable(`.",
                slice.contains("clickable("),
            )
            assertTrue(
                "$name must keep registering with the one FocusAuthority",
                slice.contains("mimirFocusable(authority, key, scope"),
            )
        }
    }

    /**
     * Every touch entry point opens with the same one-line scope guard -- the P0 of 2026-08-14.
     * [FileBrowserControllerMountStateTest] proves the refusal behaves; this proves nobody added a new
     * touch entry point without the guard, which is the shape the next regression takes.
     *
     * **DISCLOSED, because it changes what this test is worth.** A differential deleted the guard from
     * [FileBrowserController.handleLongPress] and **every behavioural test still passed** -- including
     * [FileBrowserControllerMountStateTest]'s own thief-scope refusal. The reason is that the guard is
     * currently **defence in depth rather than the sole mechanism**: `DefaultFocusAuthority.requestFocus`
     * refuses a key whose entry is not in the active scope (`DefaultFocusAuthority.kt:112-116`), and
     * [FileBrowserController.dispatch] carries the identical guard of its own. So an entry point that
     * dropped it would today be caught by two downstream refusals and no assertion.
     *
     * That makes THIS source pin the only thing standing behind the property, which is exactly why it is
     * written as source text rather than left to the behavioural suite -- and it is also a caveat on the
     * P0 test's KDoc, which describes the guard as the single step that disables the surface. It is one
     * of three steps that each independently would.
     */
    @Test
    fun `every touch entry point on the controller carries the scope guard`() {
        val source = sourceOf("FileBrowserController.kt")
        val guard = "if (authority.state.value.scope != scope) return"

        listOf(
            "internal fun handleTap(tile: FileBrowserTile) {",
            "internal fun handleLongPress(tile: FileBrowserTile) {",
            "internal fun handleCommitTap() {",
        ).forEach { signature ->
            assertTrue("entry point missing or renamed: $signature", source.contains(signature))
            val body = source.substringAfter(signature).substringBefore("\n    }")
            assertTrue(
                "$signature must open with the scope guard. Without it a stray gesture from a foreign " +
                    "scope acts on this browser -- and with one-tap activation that stray gesture now " +
                    "NAVIGATES rather than merely moving a ring.",
                body.trimStart().startsWith(guard),
            )
        }
    }

    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", contains(before))
        val slice = substringAfter(after).substringBefore(before)
        assertTrue("slice between <$after> and <$before> is empty -- it asserts nothing", slice.isNotBlank())
        return slice
    }

    private fun mainDir(): File {
        val candidates = listOf(
            File("feature-files/src/main/kotlin/com/gruning/mimir/rewrite/feature/files"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/files"),
        )
        return candidates.firstOrNull(File::isDirectory)
            ?: error("feature-files main sources not found from ${File(".").absolutePath}")
    }

    /**
     * Recursive on purpose. A flat `listFiles` would make the absence pin's own message ("anywhere in
     * feature-files/src/main") false the day this module grows a subpackage, and the new package would
     * drop out of the scan silently -- which is the failure mode the pin exists to prevent.
     */
    private fun mainSources(): List<File> =
        mainDir().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun sourceOf(name: String): String =
        mainSources().firstOrNull { it.name == name }?.readText() ?: error("$name not found in ${mainDir()}")
}

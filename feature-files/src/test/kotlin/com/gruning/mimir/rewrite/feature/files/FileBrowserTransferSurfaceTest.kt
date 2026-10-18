package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState

/**
 * **The transfer chooser screen**, using `FileBrowserExtractSurfaceTest`'s precedent and disclosure.
 * The controller/dispatch/decision layer lives in `FileBrowserController.kt`,
 * `FileBrowserDispatch.kt`, `FileBrowserTransferChoice.kt` and `FileBrowserFooterHints.kt`.
 * This file pins the contracts threaded through `FileBrowserSurface.kt`.
 *
 * ## What a passing run here proves, and what it cannot
 *
 * **Proved.** Every pure rule below is driven directly, and the controller-backed sections drive a real
 * [DefaultFocusAuthority] and a real [FileBrowserController] through real [ControllerAction] dispatches --
 * so scope discipline, the focus landing this surface reads, the MANAGE-only gate and the exact
 * mutual-exclusion with the extract chooser are genuinely exercised.
 *
 * **NOT proved, and this is the same disclosure `FileBrowserExtractSurfaceTest` and
 * `FileBrowserSupportDimRenderTest` make.** `feature-files` configures no Compose harness -- no
 * Robolectric, no `createComposeRule` (`build.gradle.kts`). **Nothing in this file can certify a rendered
 * row, a focus ring, a hit area, an alpha, or that any of this reaches the screen at all.** The call sites
 * are pinned as SOURCE TEXT, this repo's established fallback for exactly that gap. **Hardware is still
 * the gate, and this chooser is unseen on device as of this writing.**
 *
 * ## Said LOUDLY: two tests here pin the CONTRACT, not only this screen
 *
 * `the surface's own focus read resolves the chooser's landing row and its verb` and `tapping a row hands
 * the browser back to answer where` drive [FileBrowserController] directly, on
 * `FileBrowserExtractSurfaceTest`'s own precedent for its analogous pair. They are here because the
 * expressions they evaluate are the ones this surface evaluates, not because this file built the
 * mechanism they exercise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserTransferSurfaceTest {
    private val english = filesEnglish()

    // ─────────────────────────────────────────────── 1 · the commit row's new words (G2) ──

    @Test
    fun `the commit plate says Move into this folder for a move destination`() {
        assertEquals(
            FileBrowserCommitCopy(eyebrow = "Move", label = "Move into this folder"),
            fileBrowserCommitCopy(english, FileBrowserCommitPurpose.MOVE_DESTINATION),
        )
    }

    @Test
    fun `the commit plate says Copy into this folder for a copy destination`() {
        assertEquals(
            FileBrowserCommitCopy(eyebrow = "Copy", label = "Copy into this folder"),
            fileBrowserCommitCopy(english, FileBrowserCommitPurpose.COPY_DESTINATION),
        )
    }

    /**
     * **The three destination purposes must not collide.** A player mid-move must never read a plate that
     * could equally be extract's or copy's -- the referent-shift argument `fileBrowserCommitCopy`'s own
     * KDoc makes for `EXTRACT_DESTINATION` applies to all three pairwise.
     */
    @Test
    fun `the four commit purposes are four distinct plates`() {
        val purposes = listOf(
            FileBrowserCommitPurpose.EXTRACT_DESTINATION,
            FileBrowserCommitPurpose.MOVE_DESTINATION,
            FileBrowserCommitPurpose.COPY_DESTINATION,
            FileBrowserCommitPurpose.CHOOSE_SOURCE,
        )
        val plates = purposes.map { fileBrowserCommitCopy(english, it) }
        assertEquals(
            "four purposes must render four DIFFERENT plates, or a player answering one question reads " +
                "words that mean another",
            purposes.size,
            plates.toSet().size,
        )
    }

    /**
     * **`FileBrowserTransferChoice.kt`'s own rule, checked at the string level.** A completed move or copy
     * has no Undo (`FileQueueState.Done` offers `DISMISS` only). Neither plate may use a word that could
     * read as reversible.
     */
    @Test
    fun `neither transfer plate implies the press can be undone`() {
        listOf(
            fileBrowserCommitCopy(english, FileBrowserCommitPurpose.MOVE_DESTINATION),
            fileBrowserCommitCopy(english, FileBrowserCommitPurpose.COPY_DESTINATION),
        ).forEach { copy ->
            listOf("undo", "revert", "restore").forEach { word ->
                assertFalse(
                    "'${copy.label}' must not suggest reversibility with '$word'",
                    copy.label.lowercase().contains(word) || copy.eyebrow.lowercase().contains(word),
                )
            }
        }
    }

    // ──────────────────────────────────────── 2 · the inspector's Place… button (G2) ──

    /**
     * **`PLACE`'s whole reason to exist**, restated as a string check on the one control that draws it:
     * the button may not say "Move" or "Copy", because it opens a chooser offering both --
     * `fileBrowserFooterHints`' own `X PLACE` branch KDoc argues this at length for the footer entry; this
     * is the same vocabulary rule applied to the inspector.
     */
    @Test
    fun `the inspector's Place button says Place…, never Move or Copy`() {
        val panel = surfaceSource().sliceBetween(
            after = "private fun FileBrowserInspectorPanel(",
            before = "private fun InspectorFieldRow(",
        )
        assertTrue(
            "the inspector must draw a Place… button wired to onPlace",
            panel.contains("FileBrowserButton(res.getString(R.string.files_inspector_place), FileBrowserButtonVariant.PRIMARY, palette, onPlace)"),
        )
        assertEquals(
            "and no verb-specific literal may sit in that button's own line",
            0,
            Regex("FileBrowserButton\\(\"(Move|Copy)[^\"]*\"").findAll(panel.codeOnly()).count(),
        )
    }

    /**
     * **The button's gate, read as source rather than re-derived**: the same tile-kind disjointness
     * `fields.extractable` relies on, the controller's own [FileBrowserController.transferChoiceAvailable]
     * (never re-decided here), and `extractLive` -- SECONDARY has one live/dead state for the whole
     * duration of ANY pick, so the Extract and Place buttons share the identical signal rather than each
     * carrying their own.
     */
    @Test
    fun `the Place button is gated on a plain FILE, the controller's sinks, and the live binding`() {
        val panel = surfaceSource().sliceBetween(
            after = "private fun FileBrowserInspectorPanel(",
            before = "private fun InspectorFieldRow(",
        )
        assertTrue(
            "Found: <$panel>",
            panel.contains(
                "if (tile.kind == FileBrowserTileKind.FILE && transferAvailable && extractLive) {",
            ),
        )
    }

    // ───────────────────────────────────────────────── 3 · MANAGE-only, driven (G2 correction) ──

    /**
     * **The coordinator's own correction, verified against the landed controller rather than trusted from
     * its KDoc.** `transferChoiceAvailable` was first specified "exactly like the extract sinks"
     * (mode-blind) and corrected to MANAGE-only before this lane wrote a line, because a move deletes its
     * source and belongs with [FileBrowserController.deleteAvailable]'s gate shape, not
     * [FileBrowserController.extractChoiceAvailable]'s. This is the one place that correction is checked
     * against real code rather than restated.
     */
    @Test
    fun `both sinks wired is not enough in PICK -- transfer stays MANAGE-only`() {
        val authority = DefaultFocusAuthority()
        val controller = controller(authority, mode = FileBrowserMode.PICK)
        controller.open()

        assertFalse(
            "PICK must stay INCAPABLE of a transfer, not merely unwilling",
            controller.transferChoiceAvailable,
        )
        authority.requestFocus(FileBrowserFocus.tileKey(PLAIN_FILE))
        controller.dispatch(ControllerAction.SECONDARY)
        assertFalse(
            "SECONDARY on a plain file in PICK must open nothing",
            controller.transferChooserOpen,
        )
        controller.close()
    }

    @Test
    fun `MANAGE with both sinks wired offers the action overlay on a plain file, not SECONDARY the old chooser`() {
        val authority = DefaultFocusAuthority()
        val controller = controller(authority, mode = FileBrowserMode.MANAGE)
        controller.open()

        assertTrue(controller.transferChoiceAvailable)
        authority.requestFocus(FileBrowserFocus.tileKey(PLAIN_FILE))
        controller.dispatch(ControllerAction.SECONDARY)
        assertFalse(
            "F-1/R-13: SECONDARY is retired wherever the overlay covers the tile, and a plain FILE with " +
                "both transfer sinks wired always has a MOVE/COPY pair",
            controller.transferChooserOpen,
        )

        controller.dispatch(ControllerAction.SECONDARY)
        assertTrue(
            "SELECT's action overlay is the live door now, with both sinks wired",
            controller.actionOverlayOpen,
        )
        controller.close()
    }

    /** One sink without its partner must draw nothing -- the "both or neither" gate, driven. */
    @Test
    fun `one sink without its partner leaves the chooser unreachable`() {
        val authority = DefaultFocusAuthority()
        val controller = FileBrowserController(
            authority = authority,
            rootPath = "/root",
            mode = FileBrowserMode.MANAGE,
            listDirectory = { path -> tree[path].orEmpty() },
            onChoose = {},
            onDismiss = {},
            onMoveTo = { _, _ -> },
            // onCopyTo left null.
        )
        controller.open()
        assertFalse(
            "a caller that wired Move and not Copy must get the fail-closed answer, not a chooser whose " +
                "second row is focusable, advertised and dead",
            controller.transferChoiceAvailable,
        )
        controller.close()
    }

    // ───────────────────────────────────────────────── 4 · the crumb trail's transfer conjunct ──

    /**
     * **Source pin for the ADDED conjunct**, not a re-test of [fileBrowserCrumbTapsSuppressed] itself --
     * that function's own two-argument behaviour is already pinned by `FileBrowserExtractSurfaceTest` and
     * its signature remains unchanged (a third parameter there would break that file's
     * source-text pin on the call site). The transfer chooser's suppression is a second, separate
     * conjunct at the one call site instead.
     */
    @Test
    fun `crumb taps are additionally suppressed while the transfer chooser is open`() {
        val source = surfaceSource()
        assertTrue(
            "the extract guard must still read exactly as FileBrowserExtractSurfaceTest pins it",
            source.contains("fileBrowserCrumbTapsSuppressed(destinationPick, controller.extractChooserOpen)"),
        )
        val onCrumb = source.sliceBetween(after = "onCrumb = { path ->", before = "searchAvailable")
        assertTrue(
            "and the transfer chooser must additionally suppress it. Found: <$onCrumb>",
            onCrumb.contains("!controller.transferChooserOpen"),
        )
    }

    // ──────────────────────────────────────────────────── 5 · the screen's own copy ──

    @Test
    fun `the eyebrow is the PLACE, not either verb`() {
        assertEquals("Place", english.getString(FileBrowserTransferEyebrow))
    }

    @Test
    fun `the file is named with its extension, matching the extract heading's rule`() {
        assertEquals("Mario.gba", fileBrowserTransferSubjectLabel(PLAIN_FILE))
    }

    @Test
    fun `a path with no name to take still renders something`() {
        assertEquals("/root/", fileBrowserTransferSubjectLabel("/root/"))
        assertEquals("bare.gba", fileBrowserTransferSubjectLabel("bare.gba"))
    }

    // ────────────────────────────────────────────────────────── 6 · the arrival ──

    @Test
    fun `the chooser lands rather than snapping, and lands without moving geometry`() {
        assertTrue(
            "it must animate at all, or the screen snaps",
            fileBrowserArrivalAnimates(FileBrowserTransferArrival),
        )
        assertEquals(FileBrowserArrivalKind.SCREEN, FileBrowserTransferArrival.kind)
        assertEquals(
            "a travelling entrance would clip a ring this lane has no way to gate",
            0f,
            fileBrowserArrivalTravelDp(FileBrowserTransferArrival),
            0f,
        )
        assertEquals(
            "and it DIPS, like every other screen in this browser and like the folio itself. It used to " +
                "fade from nothing, which left the content slot empty for two frames on every swap -- " +
                "the transition snap. The fade-from-nothing value that once served a " +
                "layer with something underneath it (the keyboard) went when the keyboard became a sheet (F4).",
            FileBrowserGlideStartAlpha,
            fileBrowserArrivalStartAlpha(FileBrowserTransferArrival),
            0f,
        )
    }

    // ──────────────────────────────── 7 · the focus contract the footer and the ring both read ──

    /**
     * The exact expression `FileBrowserSurface`'s footer branch evaluates for the transfer chooser, driven
     * end to end -- `FileBrowserExtractSurfaceTest`'s analogous test, one screen along.
     */
    @Test
    fun `the surface's own focus read resolves the chooser's landing row and its verb`() {
        val authority = DefaultFocusAuthority()
        val controller = controller(authority)
        controller.open()

        controller.pressPlaceOn(authority, PLAIN_FILE)
        assertTrue("precondition: SECONDARY on a plain file opens the chooser", controller.transferChooserOpen)

        val state = authority.state.value
        val transferFocusedKey = state.takeIf { it.scope == controller.transferScope }?.focused
        assertEquals(
            "the chooser must own the active scope, or the surface reads null and draws no ring at all",
            controller.transferScope,
            state.scope,
        )
        assertEquals(
            "landing is row 0 -- Move first, the shared chooser order",
            FileBrowserTransferKind.MOVE,
            FileBrowserTransferFocus.optionOf(transferFocusedKey),
        )
        assertEquals(
            listOf("A" to "Choose folder", "B" to "Back"),
            fileBrowserTransferFooterHints(english, FileBrowserTransferFocus.optionOf(transferFocusedKey)),
        )
        controller.close()
    }

    /** A row tap is the controller's one true path -- focus, then its own CONFIRM, for both verbs. */
    @Test
    fun `tapping a row hands the browser back to answer where, for both verbs`() {
        listOf(FileBrowserTransferKind.MOVE to FileBrowserCommitPurpose.MOVE_DESTINATION)
            .plus(listOf(FileBrowserTransferKind.COPY to FileBrowserCommitPurpose.COPY_DESTINATION))
            .forEach { (kind, purpose) ->
                val authority = DefaultFocusAuthority()
                val controller = controller(authority)
                controller.open()

                controller.pressPlaceOn(authority, PLAIN_FILE)
                controller.handleTransferTap(FileBrowserTransferFocus.optionKey(kind))

                assertFalse("the chooser closes behind the pick ($kind)", controller.transferChooserOpen)
                assertNotNull(
                    "and the browser is now asking 'where?' ($kind)",
                    controller.destinationPickPhase(),
                )
                assertEquals(
                    "the commit row now commits the SAME verb the player pressed",
                    purpose,
                    controller.commitPurpose,
                )
                controller.close()
            }
    }

    /**
     * Abandoning a transfer pick must reopen the ACTION OVERLAY it now comes from, not the extract chooser
     * -- and, as of F-1, not the old transfer chooser either.
     *
     * **F-1, 2026-08-18.** [FileBrowserController.abandonDestinationPick]'s `Transfer` arm was repointed
     * from `openTransferChooser` to `openActionOverlay` (that function's own addendum): a player who
     * opened this pick from the overlay never saw the two-row screen on the way in, so reopening it here
     * would be a door back to a room they never walked through. The pick itself is still armed by tapping
     * a row on the (bypassed, directly-opened) chooser -- proving abandon's target is governed by the
     * PICK SESSION's type, not by which door armed it.
     */
    @Test
    fun `abandoning a transfer pick returns to the ACTION OVERLAY, not the old transfer chooser`() {
        val authority = DefaultFocusAuthority()
        val controller = controller(authority)
        controller.open()

        controller.pressPlaceOn(authority, PLAIN_FILE)
        controller.handleTransferTap(FileBrowserTransferFocus.optionKey(FileBrowserTransferKind.COPY))
        assertNotNull("precondition: a pick is open", controller.destinationPickPhase())

        // SETTLED at the origin: the next CANCEL unwinds the pick rather than walking Up.
        assertEquals(FileBrowserDestinationPickPhase.SETTLED, controller.destinationPickPhase())
        controller.dispatch(ControllerAction.CANCEL)

        assertTrue(
            "CANCEL at SETTLED must reopen the ACTION OVERLAY, not close the browser",
            controller.actionOverlayOpen,
        )
        assertFalse("and NOT the old chooser -- repointed, not merely reached differently", controller.transferChooserOpen)
        assertEquals(PLAIN_FILE, controller.actionOverlay?.path)
        assertNull("and the pick itself is gone", controller.destinationPickPhase())
        controller.close()
    }

    // ─────────────────────────────────────────────────── 8 · the call sites, pinned as source ──

    @Test
    fun `the chooser replaces the browse content, tested second in the same chain as extract`() {
        val source = surfaceSource()
        val contentRow = source.sliceBetween(
            after = "horizontalArrangement = Arrangement.spacedBy(14.dp),",
            before = "FileBrowserFooterLegend(",
        )
        assertTrue(
            "the transfer chooser must be composed inside the SAME if/else chain as extract, search, the " +
                "queue and the folio -- a sibling composed alongside them IS the overlay shape",
            contentRow.contains("} else if (transferChooser != null) {") &&
                contentRow.contains("FileBrowserTransferScreen("),
        )
        val extractIndex = contentRow.indexOf("if (extractChooser != null) {")
        val transferIndex = contentRow.indexOf("} else if (transferChooser != null) {")
        val queueIndex = contentRow.indexOf("} else if (controller.queueOpen) {")
        assertTrue(
            "extract, then transfer, then queue -- so a broken mutual-exclusion invariant fails toward " +
                "the innermost pushed scope in the same order the controller checks it",
            extractIndex in 0..<transferIndex && transferIndex in 0..<queueIndex,
        )
    }

    @Test
    fun `the chooser is handed the snapshot, the transfer scope's focus, and its own arrival`() {
        val call = surfaceSource().sliceBetween(
            after = "FileBrowserTransferScreen(",
            before = "} else if (controller.queueOpen) {",
        )
        assertTrue(
            "the branch and the rows must be ONE snapshot",
            call.contains("state = transferChooser,"),
        )
        assertTrue(
            "the ring must come from the TRANSFER scope's focused key, not the browse one",
            call.contains("focusedKey = transferFocusedKey,"),
        )
        assertTrue("and it must land rather than snap", call.contains(".fileBrowserArrivalGlide(FileBrowserTransferArrival)"))
        assertTrue(
            "the transfer focus read must be scope-filtered on the CONTROLLER's derived scope, not the " +
                "bare FileBrowserTransferFocus.Scope constant",
            surfaceSource().contains(
                "val transferFocusedKey = focusState.takeIf { it.scope == controller.transferScope }?.focused",
            ),
        )
    }

    @Test
    fun `the chooser rows go through the single FocusAuthority and the controller's tap path`() {
        val screen = transferSurfaceSource()
        assertTrue(
            "invariant: Modifier.mimirFocusable, on the controller's own transfer scope",
            screen.contains(
                ".mimirFocusable(authority, key, controller.transferScope, autoBringIntoView = false)",
            ),
        )
        listOf("focusable(", "focusTarget(", "FocusRequester", "clickable(", "combinedClickable(").forEach { forbidden ->
            assertFalse(
                "Compose's own focus system, and clickable, are never used in this app -- found `$forbidden`",
                screen.replace(".mimirFocusable(", "").contains(forbidden),
            )
        }
        assertTrue(
            "a tap must reuse the controller's CONFIRM path, or touch and the D-pad can disagree about " +
                "what activating a row means",
            screen.contains("controller.handleTransferTap(key)"),
        )
        assertTrue(
            "and the keys are the decision layer's, never rebuilt here",
            screen.contains("FileBrowserTransferFocus.optionKey(option.kind)"),
        )
    }

    /**
     * **Design standing §3b's third leg** -- found missing from this exact row by review, fixed, and
     * pinned here so it cannot silently regress. `mimirFocusable` + `detectTapGestures` alone restores
     * the focus registration and the gesture `clickable` would give for free, but not the accessibility
     * click action -- a row with only the first two carries a label with no action for TalkBack.
     */
    @Test
    fun `the row carries the accessibility click action, not just focus and a tap gesture`() {
        val screen = transferSurfaceSource()
        assertTrue(
            "Found no semantics(mergeDescendants = true) { onClick(...) } block",
            screen.contains(".semantics(mergeDescendants = true) {") && screen.contains("onClick(label = optionLabel) {"),
        )
        assertTrue(
            "the click action must reuse the tap's own path, so TalkBack and touch cannot disagree",
            screen.sliceBetween(
                after = ".semantics(mergeDescendants = true) {",
                before = ".padding(horizontal = 14.dp, vertical = 9.dp),",
            ).contains("controller.handleTransferTap(key)"),
        )
    }

    @Test
    fun `the chooser gets its own legend, tested in the same order as the content chain`() {
        val source = surfaceSource()
        assertTrue(
            "reusing the browse legend here would advertise OPEN, DELETE and START over a two-row question",
            source.contains("fileBrowserTransferFooterHints(") &&
                source.contains("focusedKind = FileBrowserTransferFocus.optionOf(transferFocusedKey),"),
        )
        val legend = source.sliceBetween(
            after = "FileBrowserFooterLegend(",
            before = "},\n            palette,",
        )
        val extractIndex = legend.indexOf("if (extractChooser != null) {")
        val transferIndex = legend.indexOf("} else if (transferChooser != null) {")
        val queueIndex = legend.indexOf("} else if (controller.queueOpen) {")
        assertTrue(
            "the footer chain must branch in the SAME order as the content-slot chain, or the two could " +
                "disagree about which screen is showing if the controller's invariant ever breaks",
            extractIndex in 0..<transferIndex && transferIndex in 0..<queueIndex,
        )
    }

    @Test
    fun `F-1 R-13 -- the browse legend now reads actionsAvailable, not the retired transferAvailable`() {
        // Was "the browse legend gets the value that makes X PLACE possible at all" -- G2's own X PLACE
        // entry is retired (fileBrowserFooterHints' deleted-arm comment; FileBrowserSurface.kt's own F-1
        // addendum at the call site's closing paren). SELECT's ACTIONS entry is the legend's live claim
        // now, gated on the SAME snapshot-side predicate as the X EXTRACT entry, per that call site's own
        // comment.
        val source = surfaceSource()
        val browse = source.sliceBetween(after = "fileBrowserFooterHints(", before = ")\n            },")
        assertFalse(
            "the retired parameter must not still be threaded as an ARGUMENT -- a caller passing it would " +
                "no longer compile, but a stale one left behind would pass silently. (The bare word still " +
                "appears in this call's own explanatory comment, which is why this checks for the " +
                "'name =' shape rather than the substring alone.) Found: <$browse>",
            browse.contains("transferAvailable ="),
        )
        assertTrue(
            "without this, actionsAvailable being true would leave SELECT live with no legend entry -- a " +
                "lie this function's own KDoc rule refuses everywhere else. Found: <$browse>",
            browse.contains("actionsAvailable = actionsAvailable,"),
        )
    }

    @Test
    fun `row width and height are parity-locked to the extract chooser's, not re-derived`() {
        assertEquals(
            "M6: the two choosers occupy the identical content-column slot, so a second 508f literal " +
                "that happens to agree today is the wrong shape",
            FileBrowserExtractRowWidthDp,
            FileBrowserTransferRowWidthDp,
            0f,
        )
        assertEquals(FileBrowserExtractRowHeightDp, FileBrowserTransferRowHeightDp, 0f)
    }

    @Test
    fun `the chooser screen hardcodes no colour`() {
        val screen = transferSurfaceSource()
        assertEquals(
            "nine themes, all dark, two metallic -- every colour routes through MimirThemePalette",
            0,
            Regex("Color\\(0x").findAll(screen).count(),
        )
        assertTrue(
            "and its fills are palette tokens",
            screen.contains("palette.surfaceHigh") && screen.contains("palette.surfaceRaised") &&
                screen.contains("palette.textPrimary") && screen.contains("palette.textMuted"),
        )
    }

    // ─────────────────────────────────────────────────────────────────── Fixtures ──

    private val tree: Map<String, List<FileItem>> = mapOf(
        "/root" to listOf(
            FileItem(name = "Roms", path = "/root/Roms", isDirectory = true),
            FileItem(name = "Mario.gba", path = PLAIN_FILE, extension = "gba"),
        ),
        "/root/Roms" to listOf(FileItem(name = "GBA", path = "/root/Roms/GBA", isDirectory = true)),
        "/root/Roms/GBA" to emptyList(),
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
        onMoveTo = { _, _ -> },
        onCopyTo = { _, _ -> },
    )

    /**
     * Open the chooser directly, bypassing dispatch.
     *
     * **F-1/R-13, 2026-08-18: X itself no longer reaches this screen.** `openTransferChooser` has zero
     * dispatch-reachable callers as of this ticket -- Move/Copy now arm a pick straight from the action
     * overlay's own rows, bypassing this two-row screen entirely (`FileBrowserTransferWiringTest`'s class
     * KDoc). This file's three remaining chooser-driven tests are legitimate anyway: they pin the SCREEN's
     * own focus/legend/tap contract (`FileBrowserTransferFocus`, `fileBrowserTransferFooterHints`,
     * `handleTransferTap`), code this ticket did not touch and which stays correct if that screen is ever
     * reconnected -- so they reach it the way `FileBrowserTransferWiringTest`'s "openTransferChooser is a
     * door" test does, directly, rather than pretending X still gets there.
     */
    private fun FileBrowserController.pressPlaceOn(authority: DefaultFocusAuthority, path: String) {
        openTransferChooser(path)
    }

    /** Presence-checked slice: a missing anchor fails loudly instead of degrading to the whole file. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", contains(before))
        return substringAfter(after).substringBefore(before)
    }

    /**
     * The file with its comments removed -- line comments, KDoc bodies and their delimiters. A source pin
     * that counts occurrences has to count CODE, on `FileBrowserExtractSurfaceTest.codeOnly`'s own
     * reasoning: a count that included the description a few lines away would fail on a comment edit and
     * pass on a second live read -- wrong in both directions.
     */
    private fun String.codeOnly(): String = lines()
        .filterNot { line ->
            val t = line.trim()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

    private fun surfaceSource(): String = moduleSource("FileBrowserSurface.kt")

    private fun transferSurfaceSource(): String = moduleSource("FileBrowserTransferSurface.kt")

    private fun moduleSource(name: String): String {
        val relative = "src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$name"
        val candidates = listOf(File(relative), File("feature-files/$relative"), File("../feature-files/$relative"))
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    private companion object {
        const val PLAIN_FILE = "/root/Mario.gba"
    }
}

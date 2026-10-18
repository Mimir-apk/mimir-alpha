package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.ExtractDestinationOptions
import com.gruning.mimir.rewrite.core.files.ExtractDestinationPolicy
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

/**
 * **the earlier change lane E — the Extract chooser's screen, and the three drawn things a destination pick used to
 * lie about.**
 *
 * Lane D built the chooser's engine, its decision layer and its whole controller, and could land none of
 * the six contracts that needed `FileBrowserSurface.kt`. This file pins those six.
 *
 * ## What a passing run here proves, and what it cannot
 *
 * **Proved.** Every pure rule below is driven directly, and the two behavioural sections drive a real
 * [DefaultFocusAuthority] and a real [FileBrowserController] through real [ControllerAction] dispatches —
 * so scope discipline, the focus landing the surface draws its ring from, and the exact divergence that
 * decides item 6's gate are genuinely exercised.
 *
 * **NOT proved, and this is the same disclosure `FileBrowserSupportDimRenderTest` and
 * `FileBrowserQueueWiringTest` make.** `feature-files` configures no Compose harness — no Robolectric, no
 * `createComposeRule` (`build.gradle.kts`). **Nothing in this file can certify a rendered row, a focus
 * ring, a hit area, an alpha, or that any of this reaches the screen at all.** The call sites are pinned
 * as SOURCE TEXT, this repo's established fallback for exactly that gap; a source pin proves a line
 * exists and names the right value, never that it is applied in the right place or drawn. **Hardware is
 * still the gate, and the whole chooser is unseen on device as of this writing.**
 *
 * One more limit worth naming: the chooser is inert in production until `app/`'s `FileBrowserStore
 * .mount()` fills `resolveExtractOptions` / `onExtractTo`, which is app integration wiring. Every
 * test below installs its own fakes, so **none of them are evidence that the shipped browser has any.**
 *
 * ## Said LOUDLY: two tests here pin the CONTRACT, not this lane's file
 *
 * `the surface's own focus read resolves the chooser's landing row and its verb` and `tapping the picker
 * row hands the browser back to answer where` drive [FileBrowserController] directly. **Both stay green
 * with the entire chooser screen deleted** — verified, not assumed: the differential that removes the
 * branch from `FileBrowserSurface.kt` fails exactly two tests, and neither of those is one of them. They
 * are here because the expressions they evaluate are the ones the surface evaluates (so a controller
 * change that broke the surface's read would be caught upstream of the screen), and they are **not**
 * evidence that the screen exists. Section 7's source pins are the only thing in this file that is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserExtractSurfaceTest {
    private val english = filesEnglish()

    // ─────────────────────────────────────────────── 1 · item 3 · the commit row's words ──

    /**
     * The former label was "USE / Use this folder" on hardware,
     * but that string is Android's own stock `ACTION_OPEN_DOCUMENT_TREE` picker button text copied
     * verbatim — the review's flagship finding. "ADD / Add this folder" matches this product's own
     * vocabulary for the same action (`SettingsDetail.kt`'s "Add Library folder" / "Add games") and is a
     * character-for-character length match, so no container this row lives in is affected. Byte-for-byte
     * pinning stays the discipline here; only the pinned bytes changed, to match the shared vocabulary rather than a
     * drift.
     */
    @Test
    fun `choosing a ROM source says the Add this folder, not Android's stock picker text`() {
        assertEquals(
            FileBrowserCommitCopy(eyebrow = "Add", label = "Add this folder"),
            fileBrowserCommitCopy(english, FileBrowserCommitPurpose.CHOOSE_SOURCE),
        )
    }

    /**
     * `null` is unreachable at the one call site (the row renders behind `commitAvailable`, which is
     * `commitPurpose != null` from the same predicate), so it is defined as the pre-#141 words rather
     * than left to a `when` branch nobody thought about.
     */
    @Test
    fun `a purpose-less row renders the pre-PR-141 words rather than a blank plate`() {
        assertEquals(fileBrowserCommitCopy(english, FileBrowserCommitPurpose.CHOOSE_SOURCE), fileBrowserCommitCopy(english, null))
    }

    /**
     * **The defect item 3 exists to close.** A MANAGE-mode destination pick rendered PICK's wording, so
     * the player answering *"extract where?"* was reading a plate asking whether to add this folder as a
     * ROM source.
     */
    @Test
    fun `a destination pick asks the question the player is actually answering`() {
        val extract = fileBrowserCommitCopy(english, FileBrowserCommitPurpose.EXTRACT_DESTINATION)
        val source = fileBrowserCommitCopy(english, FileBrowserCommitPurpose.CHOOSE_SOURCE)

        assertFalse(
            "the two purposes must not share a single word of copy -- rendering CHOOSE_SOURCE's plate " +
                "during a pick IS the bug, and 'the strings happen to be equal' is how it comes back",
            extract == source,
        )
        assertTrue(
            "the label must name EXTRACTION, or it is still answering the ROM-source question",
            extract.label.contains("Extract"),
        )
        assertTrue(
            "and it must name THIS FOLDER -- the destination is the directory the player navigated to",
            extract.label.contains("this folder"),
        )
    }

    /**
     * **The one collision worth pinning.** The chooser one press away has a row labelled *"Extract here"*
     * whose destination is a **sibling of the archive**. Reusing those exact words for the directory the
     * player has since walked to would be the worst available wording: the player read them meaning
     * something else thirty seconds ago.
     */
    @Test
    fun `the commit plate never reuses the chooser row's own words for a different destination`() {
        val plate = fileBrowserCommitCopy(english, FileBrowserCommitPurpose.EXTRACT_DESTINATION).label
        val chooserRow = fileBrowserExtractOptions(
            ExtractDestinationPolicy.options(
                archivePath = RECOGNISED,
                platformId = "gba",
                platformFolders = listOf("/root/Roms/GBA"),
            ),
        ).first { it.kind == FileBrowserExtractOptionKind.HERE }.label(english)

        assertEquals("precondition: the chooser's first row still says this", "Extract here", chooserRow)
        assertFalse(
            "the commit plate must not print the chooser's own row label -- two destinations, one " +
                "sentence, and the player has just been taught the other meaning",
            plate == chooserRow || plate.startsWith(chooserRow),
        )
    }

    // ────────── 1 · item 4 · the inspector's Extract button no longer promises a one-press extraction ──

    /**
     * **Source pin added for the button text.** Nothing previously asserted this button's literal text, so a regression here
     * would have shipped silently. the earlier change made `offerExtract` always resolve two rows
     * (`FileBrowserController.kt`'s own KDoc: the single-row arm is "consequently unreachable from this
     * call site"), so a press here opens the two-row chooser rather than extracting on the spot -- the old
     * "Extract here" promised an immediate action it can no longer perform, and the chooser it opens has
     * its own row also called "Extract here" one press later, so the same three words meant two different
     * things one screen apart. "Extract…" reuses this module's own established convention that an ellipsis
     * means "this opens a further step" -- the chooser's own `CHOOSE_FOLDER` row already says "Extract
     * in…".
     */
    @Test
    fun `the inspector's Extract button now says Extract…, not the chooser row's own Extract here`() {
        val panel = surfaceSource().sliceBetween(
            after = "private fun FileBrowserInspectorPanel(",
            before = "private fun InspectorFieldRow(",
        )
        assertTrue(
            "the inspector button must read \"Extract…\" -- a further step follows, it does not extract " +
                "on the spot",
            panel.contains("FileBrowserButton(res.getString(R.string.files_inspector_extract), FileBrowserButtonVariant.PRIMARY, palette, onExtract)"),
        )
        assertEquals(
            "and the old label -- which now collides with the chooser's own unrelated row of the same " +
                "name -- must not survive anywhere in this panel",
            0,
            Regex("\"Extract here\"").findAll(panel.codeOnly()).count(),
        )
    }

    // ────────────────────────────────── 2 · item 6 · the Extract button over a dead binding ──

    @Test
    fun `the inspector's Extract button is live only when SECONDARY is`() {
        assertTrue("no pick: X extracts, so the button is honest", fileBrowserSecondaryBindingLive(null))
        FileBrowserDestinationPickPhase.entries.forEach { phase ->
            assertFalse(
                "SECONDARY is consumed for the WHOLE duration of a pick, $phase included, and the footer " +
                    "already drops its X EXTRACT entry for exactly this interval",
                fileBrowserSecondaryBindingLive(phase),
            )
        }
    }

    /**
     * **Why the gate reads the PICK PHASE and not `commitPurpose` — a reachable divergence, driven
     * rather than argued.**
     *
     * `commitPurposeForCurrentMode` tests `commitAllowed(currentPath)` *first*, so in a directory the
     * host refuses to commit (the drive chooser at `/storage` is the shipped instance) the purpose is
     * **null** while the pick is still open and SECONDARY is still being swallowed. A purpose-gated
     * button would reappear there — live-looking, dead, on exactly the listing UI-05 was about.
     */
    @Test
    fun `a pick in a directory the host refuses to commit still consumes X`() {
        val authority = DefaultFocusAuthority()
        // Stands in for `/storage`: a directory the browser will list and navigate but never commit.
        val controller = controller(authority, commitAllowed = { it != "/root/Deep" })
        controller.open()

        controller.beginPickFrom(authority, UNRECOGNISED)
        // Walk into the refused directory, exactly as a player looking for a destination would.
        authority.requestFocus(FileBrowserFocus.tileKey("/root/Deep"))
        controller.dispatch(ControllerAction.CONFIRM)

        assertNull(
            "precondition: this is the divergence -- commitAllowed refuses, so there is no purpose here",
            controller.commitPurpose,
        )
        assertNotNull("and yet the pick is still open", controller.destinationPickPhase())
        assertFalse(
            "so the Extract button must still be hidden. Gating it on commitPurpose would redraw a dead " +
                "control in the one directory where the commit row is also absent",
            fileBrowserSecondaryBindingLive(controller.destinationPickPhase()),
        )
        controller.close()
    }

    // ──────────────────────────────────────────── 3 · item 5 · the crumb trail goes inert ──

    @Test
    fun `crumb taps are suppressed while either question is open, and only then`() {
        assertFalse(
            "ordinary browsing: a crumb tap is the whole point of the trail",
            fileBrowserCrumbTapsSuppressed(destinationPick = null, extractChooserOpen = false),
        )
        FileBrowserDestinationPickPhase.entries.forEach { phase ->
            assertTrue(
                "mid-pick a crumb tap walks CANCEL, which at $phase unwinds the pick and closes the " +
                    "chooser -- safe, but it reads as the question evaporating",
                fileBrowserCrumbTapsSuppressed(destinationPick = phase, extractChooserOpen = false),
            )
        }
        assertTrue(
            "and with the chooser open the header is STILL COMPOSED above it, so a crumb tap's CANCEL " +
                "loop closes the hard modal and then keeps navigating out from underneath it",
            fileBrowserCrumbTapsSuppressed(destinationPick = null, extractChooserOpen = true),
        )
    }

    // ──────────────────────────────────────────────────── 4 · the screen's own copy ──

    /**
     * **The heading keeps the extension and the folio's own rule is deliberately not reused.**
     * `ExtractDestinationPolicy.extractHereDestination` builds option 1's destination from the archive's
     * **stem**, so a stripped heading would print the identical word directly above the first row's
     * detail line, with nothing saying which was the archive and which was the folder about to appear
     * beside it.
     */
    @Test
    fun `the chooser names the archive in a way its own first row cannot be confused with`() {
        val heading = fileBrowserExtractArchiveLabel(RECOGNISED)
        val hereDetail = File(ExtractDestinationPolicy.extractHereDestination(RECOGNISED)).name

        assertEquals("Golden Sun.zip", heading)
        assertEquals("precondition: option 1 lands in the archive's stem", "Golden Sun", hereDetail)
        assertFalse(
            "the heading and the first row's destination must be distinguishable on screen -- they sit " +
                "a few dp apart and differ only by the extension",
            heading == hereDetail,
        )
    }

    @Test
    fun `a path with no name to take still renders something`() {
        assertEquals("/root/", fileBrowserExtractArchiveLabel("/root/"))
        assertEquals("bare.zip", fileBrowserExtractArchiveLabel("bare.zip"))
    }

    // ────────────────────────────────────────────────────────── 5 · the arrival ──

    /**
     * The chooser fades and does not travel — `FileBrowserSurfaceMotion.kt`'s ring-safety rule, unchanged.
     * The surface cannot gate the ring on the chooser's rows any more than it can on search's or the
     * queue's, and an entrance that never moves geometry is the one that cannot clip a ring.
     */
    @Test
    fun `the chooser lands rather than snapping, and lands without moving geometry`() {
        assertTrue("it must animate at all, or the screen snaps", fileBrowserArrivalAnimates(FileBrowserExtractArrival))
        assertEquals(
            FileBrowserArrivalKind.SCREEN,
            FileBrowserExtractArrival.kind,
        )
        assertEquals(
            "a travelling entrance would clip a ring this lane has no way to gate",
            0f,
            fileBrowserArrivalTravelDp(FileBrowserExtractArrival),
            0f,
        )
        assertEquals(
            "and it DIPS, like every other screen in this browser and like the folio itself. It used to " +
                "fade from nothing, which left the content slot empty for two frames on every swap -- " +
                "the transition snap. The fade-from-nothing value that once served a " +
                "layer with something underneath it (the keyboard) went when the keyboard became a sheet (F4).",
            FileBrowserGlideStartAlpha,
            fileBrowserArrivalStartAlpha(FileBrowserExtractArrival),
            0f,
        )
    }

    // The former characterization pin was removed when `fileBrowserScreenOf` began
    // recognizing the chooser. Gap 1 it named (opening the chooser produces no arrival) is
    // closed: `fileBrowserScreenOf` now sees it via `overlayOpen`. Gap 2 is NOT closed and is not this
    // ticket's scope: `abandonDestinationPick` still spends a DIRECTORY glide under the re-opened
    // chooser (`FileBrowserSurfaceMotion.kt` still marks that sequence played on a frame the chooser,
    // not the folio, owns the slot for) -- the return now dips (this fix) instead of snapping, but the
    // directional glide itself is still lost. Honest scope, not fixed here.

    // ──────────────────────────────── 6 · the focus contract the footer and the ring both read ──

    /**
     * The exact expression `FileBrowserSurface`'s footer branch evaluates, driven end to end: open the
     * chooser, read the focused key out of the authority the way the surface does, and project it through
     * [FileBrowserExtractFocus.optionOf] into the legend.
     */
    @Test
    fun `the surface's own focus read resolves the chooser's landing row and its verb`() {
        val authority = DefaultFocusAuthority()
        val controller = controller(authority)
        controller.open()

        controller.pressExtractOn(authority, RECOGNISED)
        assertTrue("precondition: two rows means a chooser", controller.extractChooserOpen)

        val state = authority.state.value
        val extractFocusedKey = state.takeIf { it.scope == controller.extractScope }?.focused
        assertEquals(
            "the chooser must own the active scope, or the surface reads null and draws no ring at all",
            controller.extractScope,
            state.scope,
        )
        assertEquals(
            "the landing is row 1 -- the chooser seeds it explicitly rather than restoring a remembered " +
                "kind from a DIFFERENT archive",
            FileBrowserExtractOptionKind.HERE,
            FileBrowserExtractFocus.optionOf(extractFocusedKey),
        )
        assertEquals(
            listOf("A" to "Extract", "B" to "Back"),
            fileBrowserExtractFooterHints(english, FileBrowserExtractFocus.optionOf(extractFocusedKey)),
        )
        controller.close()
    }

    /** A row tap is the controller's one true path -- focus, then its own CONFIRM. */
    @Test
    fun `tapping the picker row hands the browser back to answer where`() {
        val authority = DefaultFocusAuthority()
        val controller = controller(authority)
        controller.open()

        controller.pressExtractOn(authority, UNRECOGNISED)
        controller.handleExtractTap(
            FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.CHOOSE_FOLDER),
        )

        assertFalse("the chooser closes behind the pick", controller.extractChooserOpen)
        assertNotNull("and the browser is now asking 'extract where?'", controller.destinationPickPhase())
        assertEquals(
            "the commit row now commits a DESTINATION",
            FileBrowserCommitPurpose.EXTRACT_DESTINATION,
            controller.commitPurpose,
        )
        controller.close()
    }

    // ──────────────────────────────────────────────────── 7 · the call sites, pinned as source ──
    //
    // the earlier change nearly shipped a whole feature dead: its rules were pinned and its wiring was pinned by
    // nothing, so three deletable lines left a green suite over an unreachable screen. Every gate in this
    // module fails CLOSED, which means a dropped line un-arms the feature without failing a thing. These
    // are weak instruments, said out loud: they prove a line exists and names the right value. They prove
    // nothing about pixels.

    @Test
    fun `the chooser replaces the browse content and is not composed as an overlay`() {
        val source = surfaceSource()
        val contentRow = source.sliceBetween(
            after = "horizontalArrangement = Arrangement.spacedBy(14.dp),",
            before = "FileBrowserFooterLegend(",
        )
        assertTrue(
            "the chooser must be composed inside the SAME if/else chain as search, the queue and the " +
                "folio -- a sibling composed alongside them IS the overlay shape",
            contentRow.contains("if (extractChooser != null) {") &&
                contentRow.contains("FileBrowserExtractScreen("),
        )
        assertTrue(
            "and the folio must still be the ELSE of that chain",
            contentRow.contains("} else if (controller.queueOpen) {"),
        )
        val floating = source.sliceBetween(after = "MimirSheetHost(", before = "/**")
        assertFalse(
            "the floating slot in this file is the keyboard's. Composing the chooser there is precisely " +
                "the overlay this screen argued its way out of.",
            floating.contains("FileBrowserExtractScreen"),
        )
    }

    @Test
    fun `the chooser is handed the snapshot, the extract scope's focus, and its own arrival`() {
        val call = surfaceSource().sliceBetween(
            after = "FileBrowserExtractScreen(",
            before = "} else if (controller.queueOpen) {",
        )
        assertTrue(
            "the branch and the rows must be ONE snapshot -- two reads of a mutableStateOf can be split " +
                "by a recomposition, and then the screen renders a chooser the branch no longer means",
            call.contains("state = extractChooser,"),
        )
        assertTrue(
            "the ring must come from the EXTRACT scope's focused key, not the browse one",
            call.contains("focusedKey = extractFocusedKey,"),
        )
        assertTrue(
            "and it must land rather than snap",
            call.contains(".fileBrowserArrivalGlide(FileBrowserExtractArrival)"),
        )
        assertTrue(
            "the extract focus read must be scope-filtered on the CONTROLLER's derived scope. " +
                "FileBrowserExtractFocus.Scope is a static that only equals it for a default-scoped browser.",
            surfaceSource().contains(
                "val extractFocusedKey = focusState.takeIf { it.scope == controller.extractScope }?.focused",
            ),
        )
    }

    @Test
    fun `the chooser rows go through the single FocusAuthority and the controller's tap path`() {
        val screen = extractSurfaceSource()
        assertTrue(
            "invariant: Modifier.mimirFocusable, on the controller's own extract scope",
            screen.contains(
                ".mimirFocusable(authority, key, controller.extractScope, autoBringIntoView = false)",
            ),
        )
        listOf("focusable(", "focusTarget(", "FocusRequester").forEach { forbidden ->
            assertFalse(
                "Compose's own focus system is never used in this app -- found `$forbidden`",
                screen.replace(".mimirFocusable(", "").contains(forbidden),
            )
        }
        assertTrue(
            "a tap must reuse the controller's CONFIRM path, or touch and the D-pad can disagree about " +
                "what activating a row means",
            screen.contains("controller.handleExtractTap(key)"),
        )
        assertTrue(
            "and the keys are the decision layer's, never rebuilt here",
            screen.contains("FileBrowserExtractFocus.optionKey(option.kind)"),
        )
    }

    @Test
    fun `the chooser gets its own legend and the browse legend gets the two values a pick changes`() {
        val source = surfaceSource()
        assertTrue(
            "reusing the browse legend here would advertise OPEN, DELETE and START over a two-row question",
            source.contains("fileBrowserExtractFooterHints(") &&
                source.contains("focusedKind = FileBrowserExtractFocus.optionOf(extractFocusedKey),"),
        )
        val browse = source.sliceBetween(after = "fileBrowserFooterHints(", before = ")\n            },")
        listOf("commitPurpose = commitPurpose", "destinationPick = destinationPick").forEach { arg ->
            assertTrue(
                "without `$arg` the legend says CHOOSE for a press that extracts, keeps X EXTRACT over a " +
                    "consumed binding, and offers CLOSE for a press that cannot close. Found: <$browse>",
                browse.contains(arg),
            )
        }
    }

    @Test
    fun `the three consumers of a pick read one value, not three`() {
        val source = surfaceSource()
        assertTrue(
            "the pick phase is read once at surface level",
            source.contains("val destinationPick = controller.destinationPickPhase()"),
        )
        assertEquals(
            "and exactly once in CODE: three reads of destinationPickPhase() are three chances for the " +
                "legend, the plate's words and the drawn button to describe different states of one press",
            1,
            Regex("controller\\.destinationPickPhase\\(\\)").findAll(source.codeOnly()).count(),
        )
        assertTrue(
            "the same for the purpose the plate renders",
            source.contains("val commitPurpose = controller.commitPurpose"),
        )
    }

    @Test
    fun `the commit plate takes its words from the purpose and the Extract button from the phase`() {
        val source = surfaceSource()
        assertTrue(
            "the plate must render fileBrowserCommitCopy's answer, not a literal",
            source.contains("val copy = fileBrowserCommitCopy(") &&
                source.contains("imagePickHiddenCount = controller.imagePickHiddenCount,") &&
                source.contains("text = copy.eyebrow,") &&
                source.contains("text = copy.label,"),
        )
        val row = source.sliceBetween(
            after = "private fun FileBrowserCommitRow(",
            before = "internal const val FileBrowserCommitRowWidthDp:",
        )
        assertEquals(
            "and no literal may survive inside the row itself -- a leftover \"Use this folder\" there is " +
                "the defect item 3 exists to close, still on screen",
            0,
            Regex("\"Use this folder\"").findAll(row).count(),
        )
        assertTrue(
            "the inspector's Extract button must be gated on the live binding",
            source.contains("extractLive = fileBrowserSecondaryBindingLive(destinationPick),") &&
                source.contains("if (fields.extractable && extractLive) {"),
        )
        assertTrue(
            "and the crumb guard must be at the tap, not merely defined",
            source.contains("fileBrowserCrumbTapsSuppressed(destinationPick, controller.extractChooserOpen)"),
        )
    }

    @Test
    fun `the chooser screen hardcodes no colour`() {
        val screen = extractSurfaceSource()
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
            FileItem(name = "Deep", path = "/root/Deep", isDirectory = true),
            FileItem(name = "Golden Sun.zip", path = RECOGNISED, extension = "zip"),
            FileItem(name = "mystery.zip", path = UNRECOGNISED, extension = "zip"),
        ),
        "/root/Roms" to listOf(FileItem(name = "GBA", path = "/root/Roms/GBA", isDirectory = true)),
        "/root/Roms/GBA" to emptyList(),
        "/root/Deep" to listOf(FileItem(name = "inner", path = "/root/Deep/inner", isDirectory = true)),
    )

    /** Resolved through the REAL policy, so these cannot drift from the four ways it declines. */
    private fun options(archivePath: String): ExtractDestinationOptions = ExtractDestinationPolicy.options(
        archivePath = archivePath,
        platformId = if (archivePath == RECOGNISED) "gba" else null,
        platformFolders = listOf("/root/Roms/GBA"),
    )

    private fun controller(
        authority: DefaultFocusAuthority,
        commitAllowed: (String) -> Boolean = { true },
    ) = FileBrowserController(
        authority = authority,
        rootPath = "/root",
        mode = FileBrowserMode.MANAGE,
        listDirectory = { path -> tree[path].orEmpty() },
        onChoose = {},
        onDismiss = {},
        resolveExtractOptions = ::options,
        onExtractTo = { _, _ -> },
        commitAllowed = commitAllowed,
    )

    /**
     * Put the ring on a tile the way the player would, then reach the chooser.
     *
     * **F-1/R-13, 2026-08-18: via SELECT's action overlay, not a direct X** -- see
     * `FileBrowserExtractWiringTest.pressExtractOn`'s identical pattern and KDoc, duplicated here rather
     * than shared for the same reason `FileBrowserTransferWiringTest.reachExtractChooserOn` duplicates it.
     */
    private fun FileBrowserController.pressExtractOn(authority: DefaultFocusAuthority, path: String) {
        authority.requestFocus(FileBrowserFocus.tileKey(path))
        handleInspectorSecondaryTap()
        if (authority.state.value.scope == actionOverlayScope) {
            authority.requestFocus(
                FileBrowserActionOverlayFocus.optionKey(FileBrowserActionOverlayRowKind.EXTRACT),
            )
            dispatch(ControllerAction.CONFIRM)
        }
    }

    /** X on an archive Mimir cannot place, then the picker row: the browser is now the destination picker. */
    private fun FileBrowserController.beginPickFrom(authority: DefaultFocusAuthority, path: String) {
        pressExtractOn(authority, path)
        handleExtractTap(FileBrowserExtractFocus.optionKey(FileBrowserExtractOptionKind.CHOOSE_FOLDER))
    }

    /** One composed frame that reached the screen: prepare, then commit — `FileBrowserSurface`'s own
     * `remember` + `SideEffect` pairing, restated exactly as `FileBrowserSurfaceMotionTest` restates it. */
    private fun FileBrowserArrivalHistory.frame(
        screen: FileBrowserScreen,
        currentPath: String,
        motion: FileBrowserNavigationMotion?,
    ): FileBrowserArrival {
        val arrival = prepare(screen, currentPath, motion)
        commit(arrival, screen, motion)
        return arrival
    }

    /**
     * The file with its comments removed — line comments, KDoc bodies and their delimiters.
     *
     * A source pin that counts occurrences has to count CODE. Two of the three consumers this file pins
     * are also *described* in prose a few lines away, and a count that included the description would
     * fail on a comment edit and pass on a second live read — wrong in both directions.
     */
    private fun String.codeOnly(): String = lines()
        .filterNot { line ->
            val t = line.trim()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

    /** Presence-checked slice: a missing anchor fails loudly instead of degrading to the whole file. */
    private fun String.sliceBetween(after: String, before: String): String {
        val afterStart = indexOf(after)
        assertTrue("anchor missing: $after", afterStart >= 0)
        val bodyStart = afterStart + after.length
        val beforeStart = indexOf(before, startIndex = bodyStart)
        assertTrue("anchor missing or out of order: $before", beforeStart > bodyStart)
        return substring(bodyStart, beforeStart)
    }

    private fun surfaceSource(): String = moduleSource("FileBrowserSurface.kt")

    private fun extractSurfaceSource(): String = moduleSource("FileBrowserExtractSurface.kt")

    private fun moduleSource(name: String): String {
        val relative = "src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$name"
        val candidates = listOf(File(relative), File("feature-files/$relative"), File("../feature-files/$relative"))
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    private companion object {
        const val RECOGNISED = "/root/Golden Sun.zip"
        const val UNRECOGNISED = "/root/mystery.zip"
    }
}

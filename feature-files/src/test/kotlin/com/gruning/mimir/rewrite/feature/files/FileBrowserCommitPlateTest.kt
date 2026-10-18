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
 * **F-10 / C-2 — the compact, material-treated "Use folder" row.**
 *
 * Two changes, and the risky half is the width. The row is the pinned action at index 0 (policy
 * 6), and two hard-won properties hang off its geometry:
 *  - `fileBrowserCommitVirtualBounds` is **column-0-aligned** so NAV_DOWN out of it is deterministic;
 *  - `FileBrowserDispatch` **consumes NAV_LEFT/NAV_RIGHT** while it holds focus, *because a full-width
 *    row has no horizontal neighbour*.
 *
 * The second is the one the spec asked to be checked before narrowing, and the answer is that it stays
 * correct: the row is the sole occupant of its own `item {}`, so it has no neighbour at any width.
 * **The dispatch table was not changed**; this file characterizes its existing behavior.
 *
 * **Instrument note.** The behavioural half below drives a real controller. The material half is a
 * source pin, because this module configures no Compose harness (plain JUnit, no Robolectric). A source
 * pin shows the leather and the rim are read from `palette.materials.hero`; it shows nothing about how
 * they look. **Whether this reads as leather to the player is a device judgement and hardware validation establishes
 * it**.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserCommitPlateTest {
    private val english = filesEnglish()

    // ─────────────────────────────────────────────── The two properties narrowing must not move ──

    @Test
    fun `the affordance still takes index 0 and initial focus`() {
        val authority = DefaultFocusAuthority()
        val c = pickController(authority)
        c.open()
        assertEquals(
            "the commit affordance is the pinned action at index 0 and takes initial " +
                "focus. Narrowing it must not change its registration.",
            FileBrowserFocus.commitKey("/root"),
            authority.state.value.focused,
        )
        c.close()
    }

    @Test
    fun `NAV_LEFT and NAV_RIGHT are still consumed on the affordance`() {
        val authority = DefaultFocusAuthority()
        val c = pickController(authority)
        c.open()
        val before = authority.state.value.focused

        assertTrue("consumed, never fallen through", c.dispatch(ControllerAction.NAV_LEFT))
        assertEquals(
            "focus must not move sideways off the commit row. The consumption rule exists because the " +
                "row has no horizontal neighbour -- it is the sole occupant of its own LazyColumn item, " +
                "which the width cap does not change.",
            before,
            authority.state.value.focused,
        )
        assertTrue(c.dispatch(ControllerAction.NAV_RIGHT))
        assertEquals(before, authority.state.value.focused)
        c.close()
    }

    @Test
    fun `NAV_DOWN still leaves the affordance for the listing below it`() {
        val authority = DefaultFocusAuthority()
        val c = pickController(authority)
        c.open()
        c.dispatch(ControllerAction.NAV_DOWN)
        assertFalse(
            "the column-0-aligned virtual bounds are what make this deterministic; the cap is applied " +
                "start-aligned so the physical bounds still begin at column 0",
            authority.state.value.focused == FileBrowserFocus.commitKey("/root"),
        )
        c.close()
    }

    /**
     * **F-2, 2026-08-18 UPDATE.** Until this ticket the row WAS the sole occupant of its own item, and
     * this test enforced that by counting composables directly inside it -- deliberately, so a future
     * sibling would fail HERE rather than silently invalidate `FileBrowserDispatch`'s NAV_LEFT/RIGHT
     * consumption rule without the dispatch contract detecting it.
     *
     * The row added beside the commit row on 2026-08-18 is exactly that sibling.
     * **The trip-wire is not deleted, it is re-aimed**: the old assertion (exactly one composable at this
     * item's own indentation) would now pass VACUOUSLY, because the two rows sit inside a wrapping `Row`
     * one level deeper -- a single top-level match that says nothing about what changed. This asserts the
     * two composables the plan actually names, so a THIRD one (not one this ticket's own
     * `FileBrowserDispatch` update accounted for) still fails here rather than silently.
     *
     * `FileBrowserDispatch.kt`'s own NAV_LEFT/RIGHT branch was updated in the same change that added the
     * sibling (`FileBrowserDispatchTest`'s F-2 block pins it) -- this test's job is only to confirm the
     * ITEM still matches what that branch assumes: [FileBrowserCommitRow] unconditional, one optional
     * sibling, nothing else.
     */
    @Test
    fun `the commit row's item holds exactly FileBrowserCommitRow and F-2's optional back-to-origin sibling -- nothing else`() {
        val item = surfaceSource().sliceBetween(
            // V-4 re-anchored: the lazy key gained a `:<path>` suffix ([FileBrowserGhostFreeIdentity] --
            // the row is a different row in every directory and must not inherit the previous one's focus
            // animation). The `commit-affordance:` prefix was kept precisely so anchors like this one stay
            // greppable; the assertion below is unchanged.
            after = "item(key = \"commit-affordance:",
            before = "if (partition.isEmpty)",
        )
        assertTrue("precondition: the row is still composed there", item.contains("FileBrowserCommitRow("))
        assertTrue(
            "F-2's sibling must be present, gated -- not composed unconditionally",
            item.contains("if (backKey != null) {") && item.contains("FileBrowserBackToOriginRow("),
        )
        assertEquals(
            "exactly these two composable call sites may sit in this item, at ANY indentation -- a " +
                "third would give FileBrowserDispatch's NAV_LEFT/RIGHT branches a case they were never " +
                "written to handle. Report it, do not change that file to fit.",
            setOf("FileBrowserCommitRow(", "FileBrowserBackToOriginRow("),
            Regex("""\b(File[A-Z]\w*)\(""").findAll(item).map { it.groupValues[1] + "(" }.toSet(),
        )
    }

    // ─────────────────────────────────────────────────────────────────────── Width ──

    @Test
    fun `the row is narrower than the content column it sits in`() {
        // The design's stated Odin viewport: 833 x 468 dp. FileBrowserSurface's content Row takes
        // 18 dp of padding each side, a 14 dp gap, and gives the inspector FileBrowserInspectorWidthDp.
        val contentColumn = 833f - 18f - 18f - 14f - FileBrowserInspectorWidthDp
        assertTrue(
            "F-10: \"a bit smaller width wise\". At $contentColumn dp of content column the cap must " +
                "actually bite, or nothing changed.",
            FileBrowserCommitRowWidthDp < contentColumn,
        )
        assertEquals(
            "the cap is stated as the standing inspector's own width so the screen's two pinned columns " +
                "measure the same -- a relationship, not a taste value",
            FileBrowserInspectorWidthDp,
            FileBrowserCommitRowWidthDp,
            0f,
        )
    }

    @Test
    fun `the cap constrains before the fill expands`() {
        val row = commitRowSource()
        val cap = row.indexOf(".widthIn(max = FileBrowserCommitRowWidthDp.dp)")
        val fill = row.indexOf(".fillMaxWidth()")
        assertTrue("the cap must be present at all", cap >= 0)
        assertTrue("the fill must be present at all", fill >= 0)
        assertTrue(
            "widthIn must come BEFORE fillMaxWidth. Reversed, the fill sets a fixed width from the " +
                "parent's max and the cap silently does nothing -- a change that reviews as correct and " +
                "renders identically to the observed pre-fix row.",
            cap < fill,
        )
    }

    @Test
    fun `the narrowed row stays start-aligned`() {
        val content = surfaceSource().sliceBetween(
            after = "LazyColumn(\n        state = listState,",
            // V-4 re-anchored -- see the sole-occupant test above.
            before = "item(key = \"commit-affordance:",
        )
        assertFalse(
            "a horizontalAlignment on the folio's LazyColumn would centre the narrowed row, moving its " +
                "physical bounds off column 0 while fileBrowserCommitVirtualBounds stays column-0-aligned " +
                "-- the two would then disagree about which tile is below it",
            content.contains("horizontalAlignment"),
        )
    }

    // ────────────────────────────────────────────────────────────── Leather and metal ──

    @Test
    fun `the plate wears the palette's leather, not a flat surface fill`() {
        val row = commitRowSource()
        assertTrue(
            "the leather is MimirMannazPin's own two-stop cover gradient, read from the same public " +
                "palette tokens",
            row.contains("Brush.verticalGradient(") && row.contains("hero.coverTop"),
        )
        assertTrue(
            "focus lifts the lower stop -- a colour change only",
            row.contains("if (focused) hero.coverMiddle else hero.coverBottom"),
        )
        assertFalse(
            "the flat surfaceRaised/surfaceHigh fill IS the \"stale color\" observed on device",
            row.contains("palette.surfaceRaised") || row.contains("palette.surfaceHigh"),
        )
    }

    @Test
    fun `the plate wears the palette's metal rim`() {
        val row = commitRowSource()
        assertTrue(
            "the rim is `hardware`, the metal channel of the same hero material",
            row.contains("hero.hardware.copy("),
        )
        assertEquals(
            "MimirMannazPin's own resting rim alpha, so the two plates cannot drift apart",
            0.72f,
            FileBrowserPlateRimAlpha,
            0f,
        )
        assertTrue(
            "focused lifts the rim rather than thickening it -- compositor-cheap, invariant #7",
            FileBrowserPlateRimFocusedAlpha > FileBrowserPlateRimAlpha,
        )
    }

    @Test
    fun `focusing the plate changes no geometry`() {
        val row = commitRowSource()
        val focusLines = row.lines().filter { it.contains("focused") }.joinToString("\n")
        listOf("scaleX", "scaleY", "widthIn(max = if", "defaultMinSize(minHeight = if", "padding(horizontal = if")
            .forEach { transform ->
                assertFalse(
                    "invariant #7: focus animates opacity/scale/color/gradient and must never thrash " +
                        "layout. Found `$transform` conditioned on `focused`.",
                    focusLines.contains(transform),
                )
            }
    }

    /**
     * **V-4, 2026-08-14 — and the test above was GREEN while this was false.**
     *
     * The assertions above search this row's own source for a transform conditioned on `focused`, and
     * this row never had one. The size change lived **one layer down**, inside
     * [com.gruning.mimir.rewrite.core.ui.mimirFocusRing]'s `focusedScale` DEFAULT of `1.03f`
     * (`MimirTokens.kt:373`), which the call site simply did not pass — so a `Modifier.scale(1.03f)` grew
     * the whole row on focus and nothing here could see it. A test that cannot see its subject is the
     * shape this module has already paid for twice; this is the missing assertion.
     *
     * **Why it matters on the add-game path specifically.** This row is where PICK mode lands on a first
     * visit to any directory. [fileBrowserRingEligible] holds `focused` false for the whole 160 ms
     * arrival, so the flag flips on an ALREADY-COMPOSED row: `animateFloatAsState` runs for real rather
     * than starting at its target, and the row grew 3% over a further 140 ms *after* the page had landed.
     *
     * **The rule is read off the repo, not invented.** Every ROW is `focusedScale = 1f` — this module's
     * five (`FileBrowserExtractSurface.kt:188`, `FileBrowserQueueSurface.kt:372`,
     * `FileBrowserSearchSurface.kt:261/318/455`) plus `LibrarySearchOverlay.kt:491/534/761`,
     * `LibraryScreen.kt:4011/4063/4120`, `LibraryComponents.kt:632`, `BrowseScreen.kt:276`. The grow is
     * TILE vocabulary (`PlatformTile`'s own *"the 1.03 scale grows the WHOLE card"*,
     * `LibraryComponents.kt:138-139`). `FileTile` keeps it deliberately and is not asserted here.
     */
    @Test
    fun `the plate's focus treatment carries no scale -- the ring's default grow is opted out of`() {
        val row = commitRowSource()
        // Since the ring migration (2026-09-28) the row wears the one selection ring, which has no scale at all;
        // the pin now reads the family's call and refuses any scale beside it.
        val ringCall = row.lines().single { it.contains(".mimirSelectionRing(") }.trim()
        assertTrue(
            "the commit row rings on the rows' shared corner, with no grow. Found: `$ringCall`",
            ringCall.contains("shape = MimirRingShape.Row(FileBrowserRowCorner)") && !ringCall.contains("cale"),
        )
    }

    @Test
    fun `the plate's ink is the cover's own, and no colour is hardcoded`() {
        val row = commitRowSource()
        assertTrue(
            "the label uses hero.onCover -- the token authored to be read against this leather on all " +
                "nine themes. textPrimary is authored against surfaceBase, a different contrast problem.",
            row.contains("color = hero.onCover"),
        )
        assertTrue("the USE eyebrow is stamped in the hardware colour", row.contains("color = hero.hardware,"))
        assertEquals(
            "no Color(0x...) literal may appear in the commit row",
            0,
            Regex("Color\\(0x").findAll(row).count(),
        )
    }

    // ──────────────────────── F-2: the Return row's label and detail ──
    //
    // **Policy 2026-08-18, on the device: the row is "Return" with a return sign.** The label
    // stopped naming the folder, so every degradation rule below moved to the DETAIL line rather than
    // being deleted. That distinction is the point of keeping these tests: the row says less, and knows
    // exactly as much.

    @Test
    fun `the label is the shared constant, and no longer varies with the origin`() {
        assertEquals("Return", fileBrowserBackToOriginLabel(english, "/root/Downloads"))
        assertEquals(
            "one word for every origin -- this is what stopped the row being wider than the geometry it " +
                "aligns to",
            fileBrowserBackToOriginLabel(english, "/root/Downloads"),
            fileBrowserBackToOriginLabel(english, "/sd/Roms/gba/Nintendo/GBA/USA"),
        )
    }

    /**
     * **The one-word commit control uses 50% of the shared row width.**
     *
     * Derived, not typed: half of whatever the shared row width becomes, so a future change to the
     * sibling rows carries this one with it instead of silently un-halving it. What cannot be pinned here
     * is whether it LOOKS right — `feature-files` has no Compose harness, so this asserts geometry only;
     * visual fit still needs device review.
     */
    @Test
    fun `the Return row is half the width its siblings use`() {
        assertEquals(
            FileBrowserActionOverlayRowWidthDp / 2f,
            FileBrowserReturnRowWidthDp,
            0.001f,
        )
    }

    /**
     * **The return and add-folder buttons must share a height.**
     *
     * The original row read `defaultMinSize(minHeight = 46.dp)` with a comment saying it matched the
     * commit row *"so the two rows' plates align on one visual line rather than one being taller than the
     * other."* Replacing that with the ACTION OVERLAY's height matched the style reference
     * instead of the adjacent row, and the two stopped lining up.
     *
     * Pinned as one shared constant rather than two equal numbers, because two literals that must agree
     * is exactly the shape that let them disagree.
     */
    @Test
    fun `the Return row and the commit row share one height constant`() {
        assertEquals(46f, FileBrowserPinnedRowHeightDp, 0.001f)
    }

    @Test
    fun `the return sign is a hooked arrow, not a plain left arrow`() {
        // U+21A9. A plain left arrow beside a horizontal row of siblings would read as "previous item".
        assertEquals("\u21A9", FileBrowserReturnSign)
    }


    @Test
    fun `the detail is the origin's own basename`() {
        assertEquals("Downloads", fileBrowserBackToOriginDetail("/root/Downloads"))
        assertEquals("USA", fileBrowserBackToOriginDetail("/sd/Roms/gba/Nintendo/GBA/USA"))
    }

    @Test
    fun `a blank basename degrades to the full path, never an empty quote -- the ticket's own bar`() {
        // File("/").name == "": a blank basename must fall back to the full path rather than
        // producing an empty quoted name.
        assertEquals("/", fileBrowserBackToOriginDetail("/"))
    }

    @Test
    fun `an ordinary trailing slash is not the blank case -- File already strips it before naming`() {
        // Grounded rather than assumed: java.io.File normalises a trailing separator before computing
        // getName(), so this is "Downloads", not "" -- only the bare root (tested above) actually blanks.
        assertEquals("Downloads", fileBrowserBackToOriginDetail("/root/Downloads/"))
    }

    @Test
    fun `the degradation idiom is fileBrowserBreadcrumbs' own rootFallback, reused rather than reinvented`() {
        // Pins that the two places in this module turning a path into a displayed name cannot disagree
        // about the same edge case -- read the claim directly off fileBrowserBreadcrumbs' own root crumb.
        val breadcrumbRootLabel = fileBrowserBreadcrumbs(rootPath = "/", currentPath = "/").first().label
        assertEquals(breadcrumbRootLabel, fileBrowserBackToOriginDetail("/"))
    }

    // ───────────────────────────────────────────────────────────── Helpers ──

    private fun pickController(authority: DefaultFocusAuthority) = FileBrowserController(
        authority = authority,
        rootPath = "/root",
        mode = FileBrowserMode.PICK,
        listDirectory = { path ->
            if (path == "/root") {
                listOf(
                    FileItem(name = "Games", path = "/root/Games", isDirectory = true),
                    FileItem(name = "game.gba", path = "/root/game.gba", extension = "gba"),
                )
            } else {
                emptyList()
            }
        },
        onChoose = {},
        onDismiss = {},
    )

    private fun commitRowSource(): String = surfaceSource().sliceBetween(
        after = "private fun FileBrowserCommitRow(",
        before = "internal const val FileBrowserCommitRowWidthDp:",
    )

    private fun String.sliceBetween(after: String, before: String): String {
        val afterStart = indexOf(after)
        assertTrue("anchor missing: $after", afterStart >= 0)
        val bodyStart = afterStart + after.length
        val beforeStart = indexOf(before, startIndex = bodyStart)
        assertTrue("anchor missing or out of order: $before", beforeStart > bodyStart)
        return substring(bodyStart, beforeStart)
    }

    private fun surfaceSource(): String {
        val relative = "src/main/kotlin/com/gruning/mimir/rewrite/feature/files/FileBrowserSurface.kt"
        val candidates = listOf(File(relative), File("feature-files/$relative"), File("../feature-files/$relative"))
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("FileBrowserSurface.kt not found from ${File(".").absolutePath}")
    }
}

package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileSearchFilter
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTypeStep
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **Files search clipping regression, pinned.**
 *
 * ## Two instruments, because one of them cannot see the defect
 *
 * The clearance sign-pins below are **pure arithmetic**. Reverting the SURFACE — putting the scope card
 * back in the right column unconditionally — leaves every one of them green, because the functions they
 * call would still compute the same numbers while the screen went back to painting an 8.5dp slit. A
 * suite of those alone would be the shape this session has already been burned by: a test asserting an
 * expression against itself.
 *
 * So there are two:
 *  1. **Arithmetic pins** ([theClearanceSignIsPinnedAtEveryCertifiedViewport] and friends) — every
 *     expected value **hand-transcribed as a literal**, never re-derived through the production chain
 *     the assertion is checking, plus [theClearanceInstrumentCanFail], which corrupts an input and
 *     proves a passing cell flips.
 *  2. **A source-text pin** ([theScopeCardsSeatIsGatedOnTheClearanceFunction]) — this module's
 *     established fallback for wiring it has no Compose harness to exercise (same disclosure
 *     `FileBrowserTouchGrammarTest` and `FileBrowserGhostFreeIdentityTest` make). It is the one that
 *     reddens when the fix is removed, and it was differentially proven by removing it.
 *
 * **What none of this proves:** that a finger can now open the keyboard on a Nova. That is device-only,
 * it is the acceptance test, and it was run on hardware — not asserted here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserSearchGeometryTest {
    private val english = filesEnglish()

    // ─────────────────────────────────────────── F-S1: the clearance ──

    /**
     * Hand-transcribed from the measured geometry, recomputed here ONLY as documentation of where each
     * literal comes from. Chips at Label 11, each carrying the 0.5dp rescale allowance and then the
     * F-S4 floor: 48.000 + 59.804 + 69.370 + 50.239 + 54.065 = 281.478 — which the conformed build then
     * painted at 282.0dp on the Nova. Floor at Body 14: 24 + 16 + 8 + 124.331 = 172.331 (the glyph term was the font magnifier's 8.885 until the drawn 16 dp mark replaced it, 10-09). Fixed
     * chrome: 36 (the content Row's padding) + 270 (the card) + 14 (the Row's gap) + 35 (five 7dp
     * control gaps) = 355. So a SEATED query solves to `W - 636.478`, the clearance is `W - 808.809`.
     */
    private val expectedChipsIntrinsicDp = 281.478f
    private val expectedQueryFloorDp = 172.331f
    private val expectedSeatedQueryConstantDp = 636.478f
    private val expectedYieldWidthDp = 808.809f

    @Test
    fun `the chips and the floor are the measured numbers, transcribed`() {
        assertEquals(
            "five chips at Label 11, each floored at MimirSpacing.minTarget -- if this moved, every " +
                "clearance below moved with it and the sign table needs re-reading, not re-baselining",
            expectedChipsIntrinsicDp,
            fileBrowserSearchChipsIntrinsicDp(),
            0.01f,
        )
        assertEquals(
            "the query field's floor: the glyph, the gap, the whole placeholder and the padding at Body",
            expectedQueryFloorDp,
            fileBrowserSearchQueryFloorDp(),
            0.01f,
        )
        assertEquals(
            "the width at which the card yields, stated so an unseen viewport can be placed by hand",
            expectedYieldWidthDp,
            fileBrowserSearchScopeCardYieldWidthDp(),
            0.01f,
        )
    }

    /**
     * **THE pin.** Odin positive, Nova and RP5-class negative — the spec's own sign table, at literals.
     *
     * A half-fix fails loudly here: widen the query floor until the Nova reads positive and the Odin's
     * cell moves too; narrow it until RP5 reads positive and RP5's cell contradicts its own measured
     * 73.5dp field.
     */
    @Test
    fun `the clearance sign is pinned at every certified viewport`() {
        data class Case(val name: String, val widthDp: Float, val clearanceDp: Float, val seated: Boolean)
        // 833 - 808.809 = +24.19 ; 640 - 808.809 = -168.81 ; 700 - 808.809 = -108.81
        val cases = listOf(
            Case("Odin", 833f, 24.191f, true),
            Case("Retroid Pocket Nova", 640f, -168.809f, false),
            Case("RP5-class", 700f, -108.809f, false),
            Case("538dp companion", 538f, -270.809f, false),
            Case("1080 large-square", 1080f, 271.191f, true),
        )
        cases.forEach { case ->
            assertEquals(
                "${case.name}: clearance at ${case.widthDp}dp",
                case.clearanceDp,
                fileBrowserSearchQueryClearanceDp(case.widthDp),
                0.01f,
            )
            assertEquals(
                "${case.name}: the scope card's seat follows the SIGN of that clearance and nothing else",
                case.seated,
                fileBrowserSearchScopeCardSeated(case.widthDp),
            )
        }
    }

    /**
     * The fixture rows and the hand-written widths above are the same devices — asserted rather than
     * assumed, so a fixture correction cannot leave the pins above pointing at a viewport nobody ships.
     */
    @Test
    fun `the pinned widths are the certified viewports' own`() {
        assertEquals(833f, MimirCertifiedViewports.Odin.solverInputDp.widthDp, 0.01f)
        assertEquals(640f, MimirCertifiedViewports.RetroidPocketNova.solverInputDp.widthDp, 0.01f)
        assertEquals(700f, MimirCertifiedViewports.Rp5Shelf.solverInputDp.widthDp, 0.01f)
    }

    /**
     * **The defect, reproduced in arithmetic.** With the card seated the Nova's query field solves to
     * ~5.5dp of CONTENT — the 8.5dp slit that was measured on hardware, less its own 24dp of padding,
     * i.e. nothing at all. This case is what makes the fix legible: the same function, same viewport,
     * card yielded, returns a field that clears its floor with room to spare.
     */
    @Test
    fun `the Nova's query field is unusable seated and healthy yielded -- the whole of F-S1`() {
        val nova = 640f
        val seated = fileBrowserSearchQueryWidthDp(nova, scopeCardSeated = true)
        val yielded = fileBrowserSearchQueryWidthDp(nova, scopeCardSeated = false)

        assertEquals("640 - 636.478", 3.522f, seated, 0.01f)
        assertEquals("and that is W minus the transcribed seated constant", 640f - expectedSeatedQueryConstantDp, seated, 0.01f)
        assertTrue(
            "seated, the field cannot even hold its own padding -- this is the ring-around-nothing the " +
                "reported in the search row, and a touch player had no other way to open the keyboard",
            seated < FileBrowserSearchQueryHorizontalPaddingDp,
        )
        assertEquals("640 - 36 - 281.478 - 35", 287.522f, yielded, 0.01f)
        assertEquals(
            "and the conformed build PAINTED 287.0dp on the Nova 2026-08-22 (nova_after_02_search.png) " +
                "-- the model is half a dp wide of the hardware, on the safe side",
            287.0f,
            yielded,
            0.6f,
        )
        assertTrue(
            "yielded, the field clears its own floor -- the magnifier and the whole placeholder fit",
            yielded > fileBrowserSearchQueryFloorDp(),
        )
    }

    /**
     * **The Odin must not move**, and "not move" is specifically about the seat: its card keeps its
     * column and its query field keeps a healthy width. (F-S3's +1sp on rows and header and −0.5sp on
     * chips DO move the Odin — deliberately, to apply the 2026-08-11 type-size cap.  Those are type, not geometry, and are not pinned here.)
     */
    @Test
    fun `the Odin keeps its scope card and a healthy query field`() {
        val odin = 833f
        assertTrue("the card stays seated on the Odin", fileBrowserSearchScopeCardSeated(odin))
        val seated = fileBrowserSearchQueryWidthDp(odin, scopeCardSeated = true)
        assertEquals("833 - 636.478", 196.522f, seated, 0.01f)
        assertEquals("same constant, other device", 833f - expectedSeatedQueryConstantDp, seated, 0.01f)
        assertTrue(
            "and it clears the floor by more than 20dp, so the Odin is not near the boundary either (24.19 dp since the drawn 16 dp magnifier replaced the font one, 10-09; it was 31.3)",
            seated - fileBrowserSearchQueryFloorDp() > 20f,
        )
    }

    /**
     * **Why this is a clearance function and not a threshold.** The spec's own warning, kept executable:
     * a `680f`-style width gate would have left RP5-class seated on a 73.5dp field and called it fixed.
     *
     * The 73.5 is the spec's [D] figure at TODAY's chip type, so it is computed at the measured chip
     * step rather than the conformed one — this case is about the gate that was avoided, not about what
     * RP5 renders after this change.
     */
    @Test
    fun `a width bucket would have shipped RP5-class broken`() {
        val rp5 = 700f
        val atShippedChipType = fileBrowserSearchQueryWidthDp(
            surfaceWidthDp = rp5,
            scopeCardSeated = true,
            chipStepSp = FileBrowserSearchInkMeasuredChipStepSp,
            chipHitFloorDp = 0f,
        )
        assertEquals(
            "700 - 36 - 284 - 276.5 - 35 = 68.5dp of field, against the spec's own 73.5 [D] -- both are " +
                "a ring around nothing, and both are above a 680dp gate",
            68.5f,
            atShippedChipType,
            0.01f,
        )
        assertTrue(
            "RP5-class sits ABOVE 680dp, so a 680f threshold would have called it healthy while it " +
                "rendered a field narrower than its own placeholder",
            rp5 > 680f && atShippedChipType < fileBrowserSearchQueryFloorDp(),
        )
    }

    /**
     * The model was calibrated on ONE device's chip run and predicts the other's field width it was
     * never fitted to. Kept as a test so a later edit to the ink table has to stay honest about what the
     * hardware said.
     */
    @Test
    fun `the width model reproduces both devices' measured query fields`() {
        // "As shipped" = the surface the spec measured: chips at 11.5sp with no touch floor.
        val atShippedType = { w: Float ->
            fileBrowserSearchQueryWidthDp(
                surfaceWidthDp = w,
                scopeCardSeated = true,
                chipStepSp = FileBrowserSearchInkMeasuredChipStepSp,
                chipHitFloorDp = 0f,
            )
        }
        assertEquals(
            "Nova, measured 8.5dp on hardware 2026-08-22 (nova_before_02_search.png) -- and the model " +
                "was calibrated on this device's chips, so this one is a closure check, not a prediction",
            8.5f,
            atShippedType(640f),
            0.05f,
        )
        assertEquals(
            "Odin, measured 205.5dp on hardware the same session (odin_before_03_search.png). The model " +
                "predicts 201.5 -- 4dp narrow, because it deliberately carries the WIDER device's chip " +
                "measurements. Erring toward wide chips errs toward yielding the card, which is the safe " +
                "direction: it can never leave the card seated on a field narrower than modelled.",
            201.5f,
            atShippedType(833f),
            0.5f,
        )
    }

    /** Corrupt an input, watch a passing cell flip. Nothing in production is touched. */
    @Test
    fun `the clearance instrument can fail`() {
        val odin = 833f
        assertTrue("sanity: the Odin passes today", fileBrowserSearchScopeCardSeated(odin))
        // One extra chip's worth of intrinsic width is enough to unseat it -- i.e. the pin is reading
        // the chip term, not ignoring it.
        val corrupted = fileBrowserSearchQueryWidthDp(odin, scopeCardSeated = true) -
            fileBrowserSearchChipWidthDp(FileSearchFilter.ARCHIVES) * 2f
        assertTrue(
            "adding two Archives-widths of chip must drag the Odin under its floor: $corrupted",
            corrupted - fileBrowserSearchQueryFloorDp() < 0f,
        )
    }

    // ─────────────────────────────────────────── F-S1: the wiring ──

    /**
     * **The instrument that actually guards the fix.** The arithmetic above stays green if the surface
     * throws the clearance away; this does not.
     *
     * Differentially proven: with the `if (scopeCardSeated)` guard removed and the card composed
     * unconditionally, this case fails and the arithmetic cases all still pass.
     */
    @Test
    fun `the scope card's seat is gated on the clearance function`() {
        val source = searchSurfaceSource()
        assertTrue(
            "the seat must be decided by fileBrowserSearchScopeCardSeated over the SOLVED surface width " +
                "-- a literal width comparison here is the bucket F-S1 exists to refuse",
            source.contains("fileBrowserSearchScopeCardSeated(grid.surfaceWidthDp)"),
        )
        assertTrue(
            "and the card itself must be composed only when that predicate holds",
            source.contains("if (scopeCardSeated) {"),
        )
        assertTrue(
            "with the sentence re-seated in the left column when it does not",
            source.contains("if (!scopeCardSeated) {") &&
                source.contains("FileBrowserSearchScopeSentence("),
        )
        assertTrue(
            "the grid must be a PARAMETER of the search screen, not read off the controller -- the " +
                "controller holds the Odin's pre-measurement default and would flash a seated card",
            source.contains("grid: FileBrowserGrid,"),
        )
    }

    /**
     * The sentence is rendered from its `core-files` const in BOTH seats and restated in neither — the
     * yield moves furniture, never words.
     */
    @Test
    fun `both seats render the pinned sentence verbatim and neither restates it`() {
        val source = searchSurfaceSource()
        assertEquals(
            "the scope sentence must be rendered exactly twice: the seated card and the yielded line",
            2,
            Regex("""text = filesResources\(\)\.getString\(R\.string\.files_search_scope_copy\)""").findAll(source).count(),
        )
        assertTrue(
            "no paraphrase of the promise may appear in this file -- it is a privacy sentence pinned by " +
                "FileSearchPolicyTest, and a second copy here could drift into a promise the engine " +
                "does not keep",
            !source.contains("Names only") && !source.contains("nothing is sent"),
        )
    }

    // ─────────────────────────────────────────── F-S3: the ladder ──

    @Test
    fun `every size this surface speaks is a step on the capped ladder`() {
        assertEquals("chips speak Pill's own step", MimirTypeStep.Label.value, FileBrowserSearchChipStepSp)
        assertEquals("the query and its glyph speak Body", MimirTypeStep.Body.value, FileBrowserSearchQueryStepSp)

        val offLadder = Regex("fontSize = ([0-9.]+)\\.sp").findAll(searchSurfaceSource())
            .map { it.groupValues[1] }
            .toList()
        assertTrue(
            "no raw sp literal may remain on the search screen -- every size must resolve through " +
                "MimirTypeStep or a step-derived symbol. Found: $offLadder",
            offLadder.isEmpty(),
        )
    }

    /**
     * The ink table is calibrated against these five words. If `core-files` re-words a chip, the
     * measurement it was made from is stale and the clearance quietly drifts — so the words are pinned
     * where the measurement lives, not only where they are defined.
     */
    @Test
    fun `the chip labels the ink table was measured against are the labels core-files still ships`() {
        assertEquals(
            listOf("All", "Games", "Archives", "BIOS", "Saves"),
            FileSearchFilter.entries.map { it.label },
        )
        assertEquals(
            "every chip needs an ink measurement, or its width silently solves to bare padding",
            FileSearchFilter.entries.toSet(),
            FileBrowserSearchChipInk.keys,
        )
        assertEquals(
            "the query floor is the measured width of THIS placeholder -- re-word it and the floor is " +
                "stale, and the scope card starts yielding at the wrong viewport",
            "Search this volume",
            english.getString(FileBrowserSearchPlaceholder),
        )
    }

    // ─────────────────────────────────────────── F-S4: the touch floor ──

    /**
     * `MimirSpacing.minTarget` is the minimum touch-target token and the design mock's `min-height:34`/`30` are not
     * authority over it. Every interactive element on this screen clears it on BOTH axes.
     */
    @Test
    fun `every interactive element clears the standing touch floor on both axes`() {
        val floor = MimirSpacing.minTarget.value
        assertTrue("the control row (query field and chip targets)", FileBrowserSearchControlRowHeightDp >= floor)
        assertTrue("a result row", FileBrowserSearchResultRowHeightDp >= floor)
        FileSearchFilter.entries.forEach { filter ->
            assertTrue(
                "the ${filter.label} chip's target width (${fileBrowserSearchChipWidthDp(filter)}dp)",
                fileBrowserSearchChipWidthDp(filter) >= floor,
            )
        }
        assertTrue(
            "'All' is the chip the floor actually bites -- it paints 37.8dp of pill and must still " +
                "answer a finger across 48",
            fileBrowserSearchChipWidthDp(FileSearchFilter.ALL) == floor,
        )
    }

    /**
     * The floor is a HIT floor, not a paint instruction: the pill keeps its own 30dp minimum and its
     * intrinsic width, centred inside a larger target. Asserted as source text because the distinction
     * lives in the composable's structure and this module renders nothing.
     */
    @Test
    fun `the touch floor expands the hit bounds and leaves the ink alone`() {
        val source = searchSurfaceSource()
        assertTrue(
            "the OUTER box carries the floor on both axes, plus the focus registration and the tap -- " +
                "so the bounds the authority is told about are the bounds that respond",
            source.contains(
                ".defaultMinSize(minWidth = MimirSpacing.minTarget, minHeight = MimirSpacing.minTarget)",
            ),
        )
        assertTrue(
            "and the INNER pill keeps the design's own 30dp height floor, unchanged; its width floor is the same 48dp as the hit box's, so a short word " +
                "(All) paints as wide as its target and every gap in the row is the row's own (issue #265's sweep, 10-09: 28 px beside All against 16 elsewhere)",
            source.contains(".defaultMinSize(minWidth = MimirSpacing.minTarget, minHeight = 30.dp)"),
        )
        assertTrue(
            "the ring hugs the ink, not the hit box -- a focus ring drawn on a 48dp target would be a " +
                "visibly bigger pill, which is the visual-weight change this deliberately does NOT make",
            source.contains("identity = key, shape = MimirRingShape.Pill, edgeFlush = true)"),
        )
    }

    // ─────────────────────────────────────────── F-S2: the solved screen ──

    /**
     * All four consumers of the two frozen constants now read the solved grid. RC-12's own lesson,
     * applied: threading one and leaving three is worse than the defect.
     */
    @Test
    fun `the stride, the flush cadence and the reveal estimate all move with the box`() {
        val odin = FileBrowserGrid.Authored
        val nova = fileBrowserSolveGrid(640f, 480f)

        // 293 / 71 = 4.12 -> 4 ; 305 / 71 = 4.29 -> 4. Hand-transcribed.
        assertEquals("the Odin's stride", 4, fileBrowserSearchPageStride(odin))
        assertEquals("the Nova's stride", 4, fileBrowserSearchPageStride(nova))
        assertEquals(
            "the flush cadence is the stride, so a burst never draws a list shorter than the viewport",
            fileBrowserSearchPageStride(nova),
            fileBrowserSearchFlushItems(nova),
        )
        assertEquals("the Nova's solved search viewport: 362 - 48 - 9", 305f, fileBrowserSearchViewportDp(nova), 0.01f)

        // A taller box must page further -- proof the stride is a function of the box at all, which the
        // frozen constant it replaced was not.
        val tall = fileBrowserSolveGrid(640f, 900f)
        assertTrue(
            "a 900dp-tall box holds more rows than a 480dp one, and the stride must know it",
            fileBrowserSearchPageStride(tall) > fileBrowserSearchPageStride(nova),
        )
        assertTrue(
            "the reveal-scroll's item estimate is the same pitch the stride reasons over, minus the gap",
            searchSurfaceSource().contains("(FileBrowserSearchResultRowPitchDp - FileBrowserRowGapDp).dp.roundToPx()"),
        )
    }

    /**
     * **F-S3, the header half — a separate instrument because a separate file draws it.**
     *
     * [everySizeThisSurfaceSpeaksIsAStepOnTheCappedLadder] greps `FileBrowserSearchSurface.kt` only, so
     * without this case all four header sites could go back to 17/13/10sp with the whole suite green.
     * Scoped to `FileBrowserHeader`'s own body rather than the whole 2,900-line file: the folio, the
     * inspector and the footer are not this ticket's scope and their literals must not redden here.
     */
    @Test
    fun `the Files header speaks the ladder too`() {
        val header = fileBrowserHeaderSource()
        // The title moved to FileBrowserHeaderTitle (shrink, wrap, ellipsize); the header names it and it is set from the Heading step.
        assertTrue("\"Files\" is a panel title -> Heading", header.contains("FileBrowserHeaderTitle(") && featureSource("FileBrowserHeaderTitle.kt").contains("MimirTypeStep.Heading"))
        assertEquals(
            "both crumb sites -- the label and the separator between them -- are reading copy -> Body",
            2,
            Regex("fontSize = MimirTypeStep\\.Body").findAll(header).count(),
        )
        assertTrue(
            "the row count is a counter, and conforming DOWN to Micro is as much the type cap as " +
                "conforming up is",
            header.contains("fontSize = MimirTypeStep.Micro"),
        )
        val offLadder = Regex("fontSize = ([0-9.]+)\\.sp").findAll(header).map { it.groupValues[1] }.toList()
        assertTrue("no raw sp literal may remain in the Files header. Found: $offLadder", offLadder.isEmpty())
    }

    // ─────────────────────────────────────────── the source reader ──

    private fun searchSurfaceSource(): String = featureSource("FileBrowserSearchSurface.kt")

    /** Just `FileBrowserHeader`'s own body -- from its declaration to the next top-level composable. */
    private fun fileBrowserHeaderSource(): String {
        val whole = featureSource("FileBrowserSurface.kt")
        val start = whole.indexOf("fun FileBrowserHeader(")
        assertTrue("FileBrowserHeader must still exist in FileBrowserSurface.kt", start > 0)
        val end = whole.indexOf("\n@Composable", start)
        return whole.substring(start, if (end > start) end else whole.length)
    }

    private fun featureSource(name: String): String {
        val relative = "src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$name"
        val candidates = listOf(File(relative), File("feature-files/$relative"), File("../feature-files/$relative"))
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}

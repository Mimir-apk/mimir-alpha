package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.FocusScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RC-12: the solver's own certification -- three focused tests. Each one measures a RELATIONSHIP the ticket depends on, not a restated
 * constant:
 *  1. the grep-pin -- the one whose ABSENCE is what let the pre-ticket code silently reuse a frozen
 *     column constant at four call sites; nothing else in this file's mechanism can catch a sixth.
 *  2. the solver matrix -- the Odin's 832.5/833dp ambiguity collapses to ONE identical grid, and the
 *     Nova reduces to 3 columns as required.
 *  3. reproject safety -- a tile holding focus keeps it, and the tracker's own bookkeeping is unchanged,
 *     across a grid change ([FileBrowserFocusTracker.reproject] never retires).
 *
 * **RP5, grounded correction to the brief.** The brief's expected-results table names "RP5 700 ->
 * 375.0dp" as if 700dp were a confirmed full viewport width. It is not: `MimirCertifiedViewports.kt`
 * (`app`/`core-ui`/`feature-library` test trees, identical content) carries RP5's `fullViewportDp` as
 * `null`, its ONLY fixture is a `postInsetShelfDp` of `700 x 350dp` explicitly labelled "device identity
 * DISPUTED" (`:140`) and "NOT a full-viewport measurement" (`:149-157`), and a conflicting 853x480dp
 * figure exists elsewhere in the tree (`GamelistCarousel.kt:1149`) that was never reconciled with it.
 * `OnboardingScreenFitTest.kt:91`: *"Other handhelds' exact dp (RP5, Thor) are UNKNOWN in this tree and
 * are not invented here."* This test therefore checks ONLY the width-driven claim (column count, cell
 * width) using the 700dp figure that at least exists in the tree's own fixture -- column solving is a
 * pure function of width, never height, so this claim needs no height at all. `contentViewportDp` and
 * page-stride for RP5 stay UNKNOWN and are not asserted anywhere in this module.
 */
class FileBrowserGridTest {

    // ── 1. the grep-pin: no source outside the solver reads the raw column ceiling directly ──

    @Test
    fun `no production file except the solver reads the raw column ceiling constants directly`() {
        val mainDir = mainSourceDir()
        val files = mainDir.listFiles { f -> f.isFile && f.extension == "kt" }
            ?: error("could not list ${mainDir.absolutePath}")
        assertTrue("sanity: this module has more than a handful of files", files.size > 10)

        val offenders = files
            .filterNot { it.name == "FileBrowserGrid.kt" } // the sanctioned solver -- the ceiling is its input
            .mapNotNull { file ->
                val code = stripComments(file.readText())
                    // The declaration site itself is not a "read" -- FileBrowserFocusRegistration.kt is
                    // where FileBrowserTomeColumns/FileBrowserFileColumns are DEFINED, per RC-12's own
                    // constraint that they remain the authored ceiling.
                    .replace("internal const val FileBrowserTomeColumns = 4", "")
                    .replace("internal const val FileBrowserFileColumns = 5", "")
                val hits = Regex("\\bFileBrowserTomeColumns\\b|\\bFileBrowserFileColumns\\b").findAll(code).count()
                if (hits > 0) file.name to hits else null
            }

        assertTrue(
            "these files read the raw authored-ceiling constant OUTSIDE the solver -- this is exactly " +
                "the shape of the pre-RC-12 defect (four call sites silently defaulted to " +
                "FileBrowserTomeColumns/FileBrowserFileColumns instead of a solved grid): $offenders",
            offenders.isEmpty(),
        )
    }

    private fun stripComments(source: String): String {
        val noBlockComments = source.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return noBlockComments.lines().joinToString("\n") { it.substringBefore("//") }
    }

    private fun mainSourceDir(): File {
        val relative = "src/main/kotlin/com/gruning/mimir/rewrite/feature/files"
        val candidates = listOf(File(relative), File("feature-files/$relative"), File("../feature-files/$relative"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("feature/files main source dir not found from ${File(".").absolutePath}")
    }

    // ── 2. the solver matrix ──

    @Test
    fun `the Odin's 832_5dp and 833dp width ambiguity collapses to the identical grid, matching Authored`() {
        val at832_5 = fileBrowserSolveGrid(832.5f, 468f)
        val at833 = fileBrowserSolveGrid(833f, 468f)

        assertEquals(
            "832.5dp and 833dp are two roundings of the SAME physical Odin screen -- a solver that " +
                "answers differently for each is not solving one grid, it is solving two",
            at832_5,
            at833,
        )
        assertEquals(
            "FileBrowserGrid.Authored is fileBrowserSolveGrid(833f, 468f) itself, not a copy -- this is " +
                "what makes the controller's pre-measurement default and the surface's first real solve " +
                "provably the same value, not merely asserted equal by two independent literals",
            FileBrowserGrid.Authored,
            at833,
        )
        assertEquals("the Odin keeps its authored 4 tome columns at both widths", 4, at832_5.tomeColumns)
        assertEquals("the Odin keeps its authored 5 file columns at both widths", 5, at832_5.fileColumns)

        // The stride the two widths would page by must also agree -- not just the pitch used to compute
        // it. Walking the algorithm is necessary: a solver that changed columns
        // but froze the pitch would still often pass a bare equality check by luck; walking the actual
        // page-stride algorithm on both grids is what would catch it.
        val strideAt832_5 = fileBrowserPageStride(
            List(5) { at832_5.tomeRowPitchDp },
            rowIndex = 0,
            forward = true,
            viewportDp = at832_5.contentViewportDp,
        )
        val strideAt833 = fileBrowserPageStride(
            List(5) { at833.tomeRowPitchDp },
            rowIndex = 0,
            forward = true,
            viewportDp = at833.contentViewportDp,
        )
        assertEquals(strideAt832_5, strideAt833)
        assertEquals("2 tome rows (164ish dp each) fit a 350dp viewport, a 3rd would not", 2, strideAt832_5)
    }

    @Test
    fun `the Nova's narrower content column reduces both sections to 3 columns, never below 1, never above authored`() {
        val nova = fileBrowserSolveGrid(640f, 480f)

        assertEquals("clipping labels on the Nova is the whole ticket -- 4 must become 3", 3, nova.tomeColumns)
        assertEquals(3, nova.fileColumns)
        assertTrue("reduced, never exceeding the authored ceiling", nova.tomeColumns <= FileBrowserTomeColumns)
        assertTrue(nova.fileColumns <= FileBrowserFileColumns)
        assertTrue(
            "every solved cell must still clear the floor, or the solver picked a column count it " +
                "should have rejected",
            nova.tomeCellWidthDp >= FileBrowserMinCellDp && nova.fileCellWidthDp >= FileBrowserMinCellDp,
        )
    }

    @Test
    fun `RP5's 700dp shelf-box width alone -- the only figure this tree actually certifies -- solves to 3 columns at 117dp`() {
        // Width-only, deliberately: see this file's own KDoc for why a height cannot be used here
        // without inventing a number nothing in this tree has measured.
        val contentColumnWidthDp = 700f - FileBrowserContentRowHorizontalPaddingDp - FileBrowserContentRowGapDp -
            FileBrowserInspectorWidthDp - FileBrowserContentListEndPaddingDp

        val tomeColumns = fileBrowserSolveColumns(contentColumnWidthDp, FileBrowserTomeColumns, FileBrowserTomeColumnGapDp)
        val fileColumns = fileBrowserSolveColumns(contentColumnWidthDp, FileBrowserFileColumns, FileBrowserFileColumnGapDp)
        val tomeCellWidthDp = fileBrowserCellWidthDp(contentColumnWidthDp, tomeColumns, FileBrowserTomeColumnGapDp)

        assertEquals(3, tomeColumns)
        assertEquals(3, fileColumns)
        assertEquals("essentially the Odin's own authored 117.9dp cell", 117f, tomeCellWidthDp, 0.01f)
    }

    // ── 3. reproject safety: a focused tile survives a grid change, and nothing is retired ──

    @Test
    fun `reproject moves a held-focus tile's neighbours onto the new grid without retiring or reassigning focus`() {
        val authority = DefaultFocusAuthority()
        val scope = FocusScope("files-grid-test")
        authority.pushScope(scope)
        val tracker = FileBrowserFocusTracker()

        val tomes = (1..6).map {
            FileBrowserTile(
                item = FileItem(name = "t$it", path = "/root/t$it", isDirectory = true),
                kind = FileBrowserTileKind.DIRECTORY,
                machinery = false,
                platformIdValue = null,
            )
        }
        val partition = FileBrowserPartition(tomes = tomes, files = emptyList())

        // Authored (4-wide): t5 is index 4 -> row 1, column 0.
        tracker.update(authority, scope, partition, FileBrowserGrid.Authored)
        val t5 = FileBrowserFocus.tileKey("/root/t5")
        authority.requestFocus(t5)
        assertEquals("precondition", t5, authority.state.value.focused)
        val beforeKeys = authority.registeredKeysInScope(scope).toSet()

        // A 3-wide grid (the Nova's own solve): t5 is STILL index 4, but now row 1, column 1.
        val threeWide = fileBrowserSolveGrid(640f, 480f)
        tracker.reproject(authority, scope, threeWide)

        assertEquals(
            "reproject must never move focus off the tile the player is standing on -- it re-registers " +
                "bounds for already-registered keys only and never retires (see its own KDoc)",
            t5,
            authority.state.value.focused,
        )
        assertEquals(
            "no key was added or removed by a reproject -- only bounds moved",
            beforeKeys,
            authority.registeredKeysInScope(scope).toSet(),
        )

        // The behavioural proof the bounds actually moved: under 4 columns NAV_UP from t5 (row1 col0)
        // reaches t1 (row0 col0); under the reprojected 3-column grid (row1 col1) it must reach t2
        // (row0 col1) instead. If reproject were a no-op, this would still resolve to t1.
        assertTrue(authority.moveFocus(ControllerAction.NAV_UP))
        assertEquals(
            "NAV_UP after the reproject must resolve against the NEW 3-column geometry, not the grid " +
                "the tile was originally registered under",
            FileBrowserFocus.tileKey("/root/t2"),
            authority.state.value.focused,
        )
    }

    @Test
    fun `reproject before any update has ever run is a safe no-op`() {
        val authority = DefaultFocusAuthority()
        val scope = FocusScope("files-grid-test-empty")
        authority.pushScope(scope)
        val tracker = FileBrowserFocusTracker()

        // Nothing thrown, nothing registered -- covers FileBrowserController.reprojectGrid firing before
        // the controller's first load() (e.g. two composition effects racing on first frame).
        tracker.reproject(authority, scope, FileBrowserGrid.Authored)

        assertTrue(authority.registeredKeysInScope(scope).isEmpty())
    }
}

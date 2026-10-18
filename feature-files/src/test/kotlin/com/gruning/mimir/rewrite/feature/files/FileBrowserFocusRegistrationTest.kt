package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileBrowserFocusRegistrationTest {

    private fun tile(name: String, kind: FileBrowserTileKind = FileBrowserTileKind.FILE) = FileBrowserTile(
        item = FileItem(name = name, path = "/root/$name", isDirectory = kind == FileBrowserTileKind.DIRECTORY),
        kind = kind,
        machinery = false,
        platformIdValue = null,
    )

    // ── virtual bounds geometry ──

    @Test
    fun `tome bounds within a row are ordered left-to-right by column`() {
        val a = fileBrowserTomeVirtualBounds(0, columns = 4)
        val b = fileBrowserTomeVirtualBounds(1, columns = 4)
        assertTrue(a.centerX < b.centerX)
        assertEquals(a.centerY, b.centerY, 0.001f)
    }

    @Test
    fun `tome bounds wrap to a new row, strictly below the previous one, every columns-th index`() {
        val lastOfFirstRow = fileBrowserTomeVirtualBounds(3, columns = 4)
        val firstOfSecondRow = fileBrowserTomeVirtualBounds(4, columns = 4)
        assertTrue(firstOfSecondRow.centerY > lastOfFirstRow.centerY)
        assertEquals(fileBrowserTomeVirtualBounds(0, columns = 4).centerX, firstOfSecondRow.centerX, 0.001f)
    }

    @Test
    fun `file bounds sit strictly below every tome row`() {
        val tomeCount = 6 // 2 rows at 4 columns
        val lastTomeRow = fileBrowserTomeVirtualBounds(tomeCount - 1, columns = 4)
        val firstFile = fileBrowserFileVirtualBounds(0, tomeCount = tomeCount, tomeColumns = FileBrowserTomeColumns, fileColumns = FileBrowserFileColumns)
        assertTrue(firstFile.top > lastTomeRow.bottom)
    }

    @Test
    fun `file bounds sit directly below the header when there are no tomes at all`() {
        val withTomes = fileBrowserFileVirtualBounds(0, tomeCount = 4, tomeColumns = FileBrowserTomeColumns, fileColumns = FileBrowserFileColumns)
        val withoutTomes = fileBrowserFileVirtualBounds(0, tomeCount = 0, tomeColumns = FileBrowserTomeColumns, fileColumns = FileBrowserFileColumns)
        assertTrue("no tome section means no gap to skip", withoutTomes.top < withTomes.top)
    }

    // ── commit affordance bounds (PICK mode, policy 6) ──

    @Test
    fun `commit bounds sit strictly above tome row 0 -- NAV_UP from row 0 must find it uniquely`() {
        val row0 = fileBrowserTomeVirtualBounds(0, columns = FileBrowserTomeColumns)
        val commit = fileBrowserCommitVirtualBounds(tomeColumns = FileBrowserTomeColumns)
        assertTrue("commit's bottom must not overlap row 0's top", commit.bottom <= row0.top)
        assertTrue("commit's center must read as strictly ABOVE row 0's center for NAV_UP scoring", commit.centerY < row0.centerY)
    }

    @Test
    fun `commit bounds align on column 0 -- NAV_DOWN from commit must land deterministically, not by registration-order tiebreak`() {
        val col0 = fileBrowserTomeVirtualBounds(0, columns = FileBrowserTomeColumns)
        val commit = fileBrowserCommitVirtualBounds(tomeColumns = FileBrowserTomeColumns)
        assertEquals(col0.centerX, commit.centerX, 0.001f)
    }

    // ── F-2: the "Back to origin" sibling's bounds ──

    @Test
    fun `back-to-origin bounds sit in the SAME band as commit bounds -- one pinned row, not a second one`() {
        val commit = fileBrowserCommitVirtualBounds(tomeColumns = FileBrowserTomeColumns)
        val back = fileBrowserBackToOriginVirtualBounds(tomeColumns = FileBrowserTomeColumns)
        assertEquals("same top", commit.top, back.top, 0.001f)
        assertEquals("same bottom", commit.bottom, back.bottom, 0.001f)
    }

    @Test
    fun `back-to-origin bounds align on column 1, immediately right of the commit affordance's column 0`() {
        val col1 = fileBrowserTomeVirtualBounds(1, columns = FileBrowserTomeColumns)
        val back = fileBrowserBackToOriginVirtualBounds(tomeColumns = FileBrowserTomeColumns)
        assertEquals(col1.centerX, back.centerX, 0.001f)
        assertTrue("must sit strictly right of the commit affordance", back.left >= fileBrowserCommitVirtualBounds(tomeColumns = FileBrowserTomeColumns).right)
    }

    @Test
    fun `back-to-origin bounds sit strictly above tome row 0, exactly like the commit affordance`() {
        val row0 = fileBrowserTomeVirtualBounds(0, columns = FileBrowserTomeColumns)
        val back = fileBrowserBackToOriginVirtualBounds(tomeColumns = FileBrowserTomeColumns)
        assertTrue("back's bottom must not overlap row 0's top", back.bottom <= row0.top)
        assertTrue("back's center must read as strictly ABOVE row 0's center for NAV_UP scoring", back.centerY < row0.centerY)
    }

    // ── column normalisation: both sections span ONE shared width ──
    //
    // These are the pins for the 2026-08-14 fix. Before it, both grids used a shared CELL PITCH, so the
    // 4-column tome row only reached 4/5 of the width the 5-column file row covered -- even though both
    // grids are `fillMaxWidth` on screen. Revert the normalisation and every test in this block and the
    // next fails; that is the differential.

    @Test
    fun `the last tome column and the last file column both sit at the right edge of the same width`() {
        val lastTome = fileBrowserTomeVirtualBounds(FileBrowserTomeColumns - 1, columns = FileBrowserTomeColumns)
        val lastFile = fileBrowserFileVirtualBounds(FileBrowserFileColumns - 1, tomeCount = 0, tomeColumns = FileBrowserTomeColumns, fileColumns = FileBrowserFileColumns)

        assertEquals(
            "both grids are fillMaxWidth on screen -- their synthetic right edges must agree, or the " +
                "rightmost tome scores against the wrong file column",
            lastTome.right,
            lastFile.right,
            0.001f,
        )
    }

    @Test
    fun `the first tome column and the first file column both start at the left edge`() {
        assertEquals(0f, fileBrowserTomeVirtualBounds(0, columns = FileBrowserTomeColumns).left, 0.001f)
        assertEquals(0f, fileBrowserFileVirtualBounds(0, tomeCount = 0, tomeColumns = FileBrowserTomeColumns, fileColumns = FileBrowserFileColumns).left, 0.001f)
    }

    // ── directional navigation across the tome/file block boundary, through a REAL authority ──
    //
    // Scored by DefaultFocusAuthority's own geometry, not by hand arithmetic: these assert what the
    // player's thumb actually does, which is the only thing that matters.

    private fun registered(
        tomeCount: Int,
        fileCount: Int,
        commitKey: FocusKey? = null,
        backKey: FocusKey? = null,
    ): Pair<DefaultFocusAuthority, FocusScope> {
        val authority = DefaultFocusAuthority()
        val scope = FocusScope("files-test")
        authority.pushScope(scope)
        val partition = FileBrowserPartition(
            tomes = (1..tomeCount).map { tile("t$it", FileBrowserTileKind.DIRECTORY) },
            files = (1..fileCount).map { tile("f$it") },
        )
        FileBrowserFocusTracker().update(authority, scope, partition, FileBrowserGrid.Authored, commitKey, backKey)
        return authority to scope
    }

    @Test
    fun `NAV_DOWN from the RIGHTMOST tome reaches the RIGHTMOST file -- the column the player sees under it`() {
        val (authority, _) = registered(tomeCount = 4, fileCount = 5)
        authority.requestFocus(FileBrowserFocus.tileKey("/root/t4")) // tome column 3, the right edge

        assertTrue(authority.moveFocus(ControllerAction.NAV_DOWN))

        assertEquals(
            "tome column 3 is at 87.5% of the row on screen; the file under it is column 4 (90%), not " +
                "column 3 (70%) -- and column 4 must not be unreachable from the tome grid at all",
            FileBrowserFocus.tileKey("/root/f5"),
            authority.state.value.focused,
        )
    }

    @Test
    fun `NAV_UP from the RIGHTMOST file returns to the RIGHTMOST tome -- the crossing is symmetric`() {
        val (authority, _) = registered(tomeCount = 4, fileCount = 5)
        authority.requestFocus(FileBrowserFocus.tileKey("/root/f5"))

        assertTrue(authority.moveFocus(ControllerAction.NAV_UP))

        assertEquals(FileBrowserFocus.tileKey("/root/t4"), authority.state.value.focused)
    }

    @Test
    fun `NAV_DOWN and NAV_UP across the boundary are stable on the left edge too`() {
        val (authority, _) = registered(tomeCount = 4, fileCount = 5)
        authority.requestFocus(FileBrowserFocus.tileKey("/root/t1"))

        assertTrue(authority.moveFocus(ControllerAction.NAV_DOWN))
        assertEquals(FileBrowserFocus.tileKey("/root/f1"), authority.state.value.focused)

        assertTrue(authority.moveFocus(ControllerAction.NAV_UP))
        assertEquals("crossing back must return where it came from, not drift a column", FileBrowserFocus.tileKey("/root/t1"), authority.state.value.focused)
    }

    @Test
    fun `NAV_DOWN inside the tome grid prefers the next tome row over jumping the whole section`() {
        val (authority, _) = registered(tomeCount = 5, fileCount = 5) // t5 alone on tome row 1

        authority.requestFocus(FileBrowserFocus.tileKey("/root/t1"))
        assertTrue(authority.moveFocus(ControllerAction.NAV_DOWN))

        assertEquals(
            "a second tome row must not be skipped straight into the files section",
            FileBrowserFocus.tileKey("/root/t5"),
            authority.state.value.focused,
        )
    }

    @Test
    fun `every file column is reachable by NAV_UP -- none of them is a dead end above`() {
        (1..5).forEach { column ->
            val (authority, _) = registered(tomeCount = 4, fileCount = 5)
            authority.requestFocus(FileBrowserFocus.tileKey("/root/f$column"))

            assertTrue("file column ${column - 1} has no upward neighbour", authority.moveFocus(ControllerAction.NAV_UP))
        }
    }

    @Test
    fun `NAV_DOWN from the commit affordance lands on the FIRST tome, never a middle column`() {
        val commitKey = FileBrowserFocus.commitKey("/root")
        val (authority, _) = registered(tomeCount = 4, fileCount = 5, commitKey = commitKey)
        authority.requestFocus(commitKey)

        assertTrue(authority.moveFocus(ControllerAction.NAV_DOWN))

        assertEquals(FileBrowserFocus.tileKey("/root/t1"), authority.state.value.focused)
    }

    @Test
    fun `NAV_UP from anywhere in the top tome row reaches the commit affordance`() {
        (1..4).forEach { column ->
            val commitKey = FileBrowserFocus.commitKey("/root")
            val (authority, _) = registered(tomeCount = 4, fileCount = 5, commitKey = commitKey)
            authority.requestFocus(FileBrowserFocus.tileKey("/root/t$column"))

            assertTrue(authority.moveFocus(ControllerAction.NAV_UP))
            assertEquals("from tome column ${column - 1}", commitKey, authority.state.value.focused)
        }
    }

    // ── F-2: NAV_UP through a REAL authority once the back-to-origin sibling is also registered ──
    //
    // The test above is deliberately left untouched (it never registers a backKey, so it stays a pin of
    // the pre-F-2, single-affordance shape -- see FileBrowserFocusTest's differential test for the same
    // discipline on the pure row model). These are the SAME scan, once a destination pick is open.

    @Test
    fun `NAV_UP from tome COLUMN 0 still reaches the commit affordance, unchanged, once a back-to-origin sibling exists`() {
        val commitKey = FileBrowserFocus.commitKey("/root")
        val backKey = FileBrowserFocus.backToOriginKey("/root/Downloads")
        val (authority, _) = registered(tomeCount = 4, fileCount = 5, commitKey = commitKey, backKey = backKey)
        authority.requestFocus(FileBrowserFocus.tileKey("/root/t1")) // column 0

        assertTrue(authority.moveFocus(ControllerAction.NAV_UP))

        assertEquals(commitKey, authority.state.value.focused)
    }

    @Test
    fun `NAV_UP from tome columns 1 through 3 reaches the back-to-origin sibling, not the commit affordance`() {
        // Grounded against the scorer's own formula (alongAxis + crossAxis * 3), not assumed: the sibling
        // sits directly above these columns (crossAxis 0), which beats the commit affordance's crossAxis
        // by a wide margin regardless of exact row-pitch constants.
        (2..4).forEach { column ->
            val commitKey = FileBrowserFocus.commitKey("/root")
            val backKey = FileBrowserFocus.backToOriginKey("/root/Downloads")
            val (authority, _) = registered(tomeCount = 4, fileCount = 5, commitKey = commitKey, backKey = backKey)
            authority.requestFocus(FileBrowserFocus.tileKey("/root/t$column"))

            assertTrue(authority.moveFocus(ControllerAction.NAV_UP))

            assertEquals("from tome column ${column - 1}", backKey, authority.state.value.focused)
        }
    }

    @Test
    fun `NAV_DOWN from the back-to-origin sibling lands on tome COLUMN 1, mirroring the commit affordance's column 0`() {
        val commitKey = FileBrowserFocus.commitKey("/root")
        val backKey = FileBrowserFocus.backToOriginKey("/root/Downloads")
        val (authority, _) = registered(tomeCount = 4, fileCount = 5, commitKey = commitKey, backKey = backKey)
        authority.requestFocus(backKey)

        assertTrue(authority.moveFocus(ControllerAction.NAV_DOWN))

        assertEquals(FileBrowserFocus.tileKey("/root/t2"), authority.state.value.focused)
    }

    @Test
    fun `NAV_UP from the back-to-origin sibling is a dead end -- nothing is registered above the pinned row`() {
        // Same safe class as the commit affordance's own top edge: the pinned row is the top of the whole
        // scan, so a geometric NAV_UP from either of its keys must find no candidate and leave focus put,
        // not silently jump to some unrelated virtual bounds above y=0.
        val commitKey = FileBrowserFocus.commitKey("/root")
        val backKey = FileBrowserFocus.backToOriginKey("/root/Downloads")
        val (authority, _) = registered(tomeCount = 4, fileCount = 5, commitKey = commitKey, backKey = backKey)
        authority.requestFocus(backKey)

        assertFalse("nothing sits above the pinned row", authority.moveFocus(ControllerAction.NAV_UP))

        assertEquals("a failed move must not disturb focus", backKey, authority.state.value.focused)
    }

    @Test
    fun `NAV_DOWN from the commit affordance still lands on tome COLUMN 0, unaffected by the sibling existing`() {
        val commitKey = FileBrowserFocus.commitKey("/root")
        val backKey = FileBrowserFocus.backToOriginKey("/root/Downloads")
        val (authority, _) = registered(tomeCount = 4, fileCount = 5, commitKey = commitKey, backKey = backKey)
        authority.requestFocus(commitKey)

        assertTrue(authority.moveFocus(ControllerAction.NAV_DOWN))

        assertEquals(FileBrowserFocus.tileKey("/root/t1"), authority.state.value.focused)
    }

    @Test
    fun `raw NAV_RIGHT from the back-to-origin sibling would escape into the tile grid -- exactly why FileBrowserDispatch must never delegate this press to moveFocus`() {
        // This is the differential proof for the dispatch-table hazard comment: with nothing registered
        // to its right, the scorer does NOT dead-end -- it reaches past the pinned row into tome column 2
        // (two rows down), the identical teleport class fileBrowserCommitVirtualBounds' own KDoc already
        // named. FileBrowserDispatchTest pins that the CONTROLLER never lets this run (NAV_RIGHT is
        // Consumed instead); this test is the geometric evidence for why that guard is load-bearing.
        val commitKey = FileBrowserFocus.commitKey("/root")
        val backKey = FileBrowserFocus.backToOriginKey("/root/Downloads")
        val (authority, _) = registered(tomeCount = 4, fileCount = 5, commitKey = commitKey, backKey = backKey)
        authority.requestFocus(backKey)

        assertTrue(
            "moveFocus finds SOME candidate here -- it does not safely dead-end",
            authority.moveFocus(ControllerAction.NAV_RIGHT),
        )
        assertNotEquals(
            "and that candidate is a tile, not a no-op -- the teleport this ticket's hazard note warns about",
            backKey,
            authority.state.value.focused,
        )
    }

    // ── page pitches: grounded in the adoption spec's stated viewport, not invented ──

    @Test
    fun `the content viewport and file row pitch are derived from the design's stated 833x468dp viewport, not chosen`() {
        // Every term is a literal read out of a named source; see FileBrowserContentViewportDp's own
        // KDoc for which construct owns each. This exists so the derivation cannot rot into a magic
        // number.
        val viewportHeightDp = 468f // adoption spec §2 -- the Odin's landscape viewport at 369 dpi
        val headerDp = 52f
        val headerDividerDp = 1f
        val contentTopPaddingDp = 11f
        val listBottomPaddingDp = 10f
        val footerDp = 44f
        val fileTileMinHeightDp = 98f
        val listRowSpacingDp = 9f

        val contentHeightDp = viewportHeightDp - headerDp - headerDividerDp -
            contentTopPaddingDp - listBottomPaddingDp - footerDp

        assertEquals(
            "if this stops matching, FileBrowserSurface's chrome changed and the pitches must be recomputed",
            FileBrowserContentViewportDp,
            contentHeightDp,
            0.001f,
        )
        assertEquals(FileBrowserFileRowPitchDp, fileTileMinHeightDp + listRowSpacingDp, 0.001f)
    }

    @Test
    fun `the tome row pitch is derived from the cover's own aspect ratio at the design's content width`() {
        val viewportWidthDp = 833f // adoption spec §2
        val contentRowHorizontalPaddingDp = 18f + 18f
        val contentRowSpacingDp = 14f
        val inspectorWidthDp = 270f
        val listEndPaddingDp = 5f
        val tomeRowSpacingDp = 12f
        val tomeColumnGapsDp = tomeRowSpacingDp * (FileBrowserTomeColumns - 1)
        val tomeColumnInnerSpacingDp = 4f
        val metaLineDp = 17f // FileBrowserTomeAspectRatio's own KDoc
        val listRowSpacingDp = 9f

        val contentWidthDp = viewportWidthDp - contentRowHorizontalPaddingDp - contentRowSpacingDp -
            inspectorWidthDp - listEndPaddingDp
        val tomeCellWidthDp = (contentWidthDp - tomeColumnGapsDp) / FileBrowserTomeColumns
        val coverHeightDp = tomeCellWidthDp / FileBrowserTomeAspectRatio

        assertEquals(508f, contentWidthDp, 0.001f)
        assertEquals(118f, tomeCellWidthDp, 0.001f)
        assertEquals(
            FileBrowserTomeRowPitchDp,
            coverHeightDp + tomeColumnInnerSpacingDp + metaLineDp + listRowSpacingDp,
            0.5f, // the meta line is a text height at fontScale 1, not a laid-out literal
        )
    }

    @Test
    fun `a taller tome row means FEWER rows per screen, so a file-derived stride OVERSHOOTS the tome grid`() {
        // The corrected argument (mechanism D). The predecessor's KDoc reasoned the other way -- "a tome
        // row is TALLER, so fewer of them fit, meaning three tome rows is at most one screenful there as
        // well" -- and used one constant 3-row stride everywhere. Taller rows mean three of them are MORE
        // than a screen, not less, which is why the stride is now measured in dp rather than counted.
        assertTrue(
            "premise: a tome row really is taller than a file row",
            FileBrowserTomeRowPitchDp > FileBrowserFileRowPitchDp,
        )
        assertTrue(
            "the old constant 3-row stride spans ${3 * FileBrowserTomeRowPitchDp}dp of tomes against a " +
                "${FileBrowserContentViewportDp}dp viewport -- ~1.4 screens, skipping a band of tomes " +
                "the player never saw",
            3 * FileBrowserTomeRowPitchDp > FileBrowserContentViewportDp,
        )
        assertEquals(
            "and the measured stride is what protects against that",
            2,
            fileBrowserPageStride(List(5) { FileBrowserTomeRowPitchDp }, rowIndex = 0, forward = true, viewportDp = FileBrowserContentViewportDp),
        )
    }

    @Test
    fun `the pitch list has exactly one entry per focus row -- the contract nothing else can enforce`() {
        val commitKey = FileBrowserFocus.commitKey("/root")
        val partitions = listOf(
            "empty" to FileBrowserPartition(tomes = emptyList(), files = emptyList()),
            "tomes only, ragged" to FileBrowserPartition(
                tomes = (1..5).map { tile("t$it", FileBrowserTileKind.DIRECTORY) },
                files = emptyList(),
            ),
            "files only, ragged" to FileBrowserPartition(
                tomes = emptyList(),
                files = (1..6).map { tile("f$it") },
            ),
            "both sections, both ragged" to FileBrowserPartition(
                tomes = (1..7).map { tile("t$it", FileBrowserTileKind.DIRECTORY) },
                files = (1..11).map { tile("f$it") },
            ),
            "exact rows, no remainder" to FileBrowserPartition(
                tomes = (1..8).map { tile("t$it", FileBrowserTileKind.DIRECTORY) },
                files = (1..10).map { tile("f$it") },
            ),
        )

        partitions.forEach { (label, partition) ->
            listOf(null, commitKey).forEach { key ->
                val mode = if (key == null) "MANAGE" else "PICK"
                assertEquals(
                    "$label / $mode: a pitch list shorter or longer than the row list pages the listing " +
                        "against the wrong row heights",
                    fileBrowserFocusRows(partition, FileBrowserGrid.Authored, key).size,
                    fileBrowserRowPitchesDp(partition, hasCommitRow = key != null, FileBrowserGrid.Authored).size,
                )
            }
        }
    }

    // ── lazy item index mapping ──

    @Test
    fun `lazy item index is null when nothing is focused`() {
        val partition = FileBrowserPartition(tomes = listOf(tile("A", FileBrowserTileKind.DIRECTORY)), files = emptyList())
        assertNull(fileBrowserLazyItemIndex(partition, null, FileBrowserGrid.Authored))
    }

    @Test
    fun `the first tome row is lazy item 1 -- item 0 is the Tomes heading`() {
        val tomes = (1..4).map { tile("tome$it", FileBrowserTileKind.DIRECTORY) }
        val partition = FileBrowserPartition(tomes = tomes, files = emptyList())

        val index = fileBrowserLazyItemIndex(partition, FileBrowserFocus.tileKey(tomes[0].path), FileBrowserGrid.Authored)

        assertEquals(1, index)
    }

    @Test
    fun `a tome in the second row lands one lazy item further`() {
        val tomes = (1..5).map { tile("tome$it", FileBrowserTileKind.DIRECTORY) }
        val partition = FileBrowserPartition(tomes = tomes, files = emptyList())

        val index = fileBrowserLazyItemIndex(partition, FileBrowserFocus.tileKey(tomes[4].path), FileBrowserGrid.Authored)

        assertEquals(2, index) // heading(0) + row0(1) + row1(2)
    }

    @Test
    fun `the first file row lands after the tome heading, every tome row, and the Files heading`() {
        val tomes = (1..4).map { tile("tome$it", FileBrowserTileKind.DIRECTORY) }
        val files = listOf(tile("file1"))
        val partition = FileBrowserPartition(tomes = tomes, files = files)

        val index = fileBrowserLazyItemIndex(partition, FileBrowserFocus.tileKey(files[0].path), FileBrowserGrid.Authored)

        assertEquals(3, index) // tomesHeading(0) + tomeRow(1) + filesHeading(2) + fileRow(3)
    }

    @Test
    fun `with no tomes at all, the first file row is lazy item 1`() {
        val files = listOf(tile("file1"))
        val partition = FileBrowserPartition(tomes = emptyList(), files = files)

        val index = fileBrowserLazyItemIndex(partition, FileBrowserFocus.tileKey(files[0].path), FileBrowserGrid.Authored)

        assertEquals(1, index) // filesHeading(0) + fileRow(1)
    }

    // ── F-02: the folio's one scroll system, now the gamelist's settlement contract ──
    //
    // The existing settlement model is `feature-library`'s
    // `focusRevealAction` (`LibraryFocusReveal.kt:57-113`), restated as `fileBrowserRevealAction` --
    // that file's own policy (2026-07-28) describes the defect in F-02's exact words: focus
    // "could move several logical rows while the panel stayed visually fixed."
    //
    // These cases include every case the deleted `fileBrowserScrollDelta` carried. Its near-edge rule
    // is deliberately NOT reproduced -- see the first test, which is the F-02 fix itself.
    //
    // These pin the DECISION only. No JVM test can see a viewport move, and `feature-files` has no
    // Compose harness at all: that the three call sites pass `autoBringIntoView = false`, that the
    // effect applies this action, and above all whether the resulting cadence FEELS settled, are
    // read-verified in `FileBrowserSurface.kt` and certifiable only by eye on the device.

    @Test
    fun `a fully visible row is NOT settled -- the near-edge predecessor's zero here IS the F-02 defect`() {
        // The deleted `fileBrowserScrollDelta(120, 245, 0, 800)` returned 0 for exactly this geometry:
        // the row is wholly inside, so nothing moved. Repeat that over several presses and the listing
        // sits still, still, still, then lurches -- "smooth, smooth, teleport". The settlement seat is
        // intentionally non-zero here, which is what makes every press carry the sheet.
        val action = fileBrowserRevealAction(
            targetIndex = 4,
            firstVisibleIndex = 2,
            visibleCount = 5,
            focusedItemOffset = 120,
            focusedItemSize = 245,
            viewportStart = 0,
            viewportEnd = 800,
        )

        // seat = 0 + (800 - 245) / 2 = 277; travel = 120 - 277.
        assertEquals(FileBrowserRevealAction.ScrollBy(-157f), action)
    }

    @Test
    fun `one row of focus movement costs exactly one row pitch of scroll, never a screenful`() {
        // The reference viewport and this module's own derived tome pitch, in dp treated as the
        // function's own unit (it is unit-agnostic): 350dp of content, 164.5dp rows, so the seat sits at
        // (350 - 164) / 2 = 93. A row already seated asks for nothing; the row one pitch below it asks
        // for exactly one pitch. That steady state is what "settlement" means, and it is what makes the
        // measured 359dp / 1.03-screen leap of the earlier change's predecessor structurally impossible.
        val seated = fileBrowserRevealAction(
            targetIndex = 3,
            firstVisibleIndex = 2,
            visibleCount = 3,
            focusedItemOffset = 93,
            focusedItemSize = 164,
            viewportStart = 0,
            viewportEnd = 350,
        )
        assertEquals(FileBrowserRevealAction.None, seated)

        val nextRow = fileBrowserRevealAction(
            targetIndex = 4,
            firstVisibleIndex = 2,
            visibleCount = 3,
            focusedItemOffset = 93 + 164,
            focusedItemSize = 164,
            viewportStart = 0,
            viewportEnd = 350,
        )
        assertEquals(FileBrowserRevealAction.ScrollBy(164f), nextRow)
        assertTrue(
            "one press must never cost a screenful",
            (nextRow as FileBrowserRevealAction.ScrollBy).delta < 350f,
        )
    }

    @Test
    fun `a row hanging off the top settles by a NEGATIVE delta`() {
        val action = fileBrowserRevealAction(
            targetIndex = 1,
            firstVisibleIndex = 1,
            visibleCount = 4,
            focusedItemOffset = -30,
            focusedItemSize = 245,
            viewportStart = 0,
            viewportEnd = 800,
        )

        assertTrue(action is FileBrowserRevealAction.ScrollBy && action.delta < 0f)
        assertEquals(FileBrowserRevealAction.ScrollBy(-307f), action)
    }

    @Test
    fun `a row taller than the viewport seats its TOP, never chases its bottom off-screen`() {
        // Inherited verbatim from the deleted `fileBrowserScrollDelta`, whose above-check-first ordering
        // existed for this one case: both edges overflow at once, and leading with the bottom would
        // scroll the row's top away. Centring is impossible here, so the leading edge wins.
        val action = fileBrowserRevealAction(
            targetIndex = 2,
            firstVisibleIndex = 2,
            visibleCount = 1,
            focusedItemOffset = -40,
            focusedItemSize = 1200,
            viewportStart = 0,
            viewportEnd = 800,
        )

        assertEquals(FileBrowserRevealAction.ScrollBy(-40f), action)
    }

    @Test
    fun `a row exactly the viewport's height takes the leading-edge branch, not the centre`() {
        // The boundary of the branch above: >= , not >. At equal heights the centre and the leading edge
        // coincide, so the two agree -- but only the leading-edge branch keeps agreeing one pixel later.
        val action = fileBrowserRevealAction(
            targetIndex = 2,
            firstVisibleIndex = 2,
            visibleCount = 1,
            focusedItemOffset = 0,
            focusedItemSize = 800,
            viewportStart = 0,
            viewportEnd = 800,
        )

        assertEquals(FileBrowserRevealAction.None, action)
    }

    @Test
    fun `leading content padding is honoured -- the seat is measured from viewportStart, not from zero`() {
        // The folio's LazyColumn carries contentPadding, so viewportStart can be negative. seat =
        // -30 + (830 - 100) / 2 = 335.
        val action = fileBrowserRevealAction(
            targetIndex = 3,
            firstVisibleIndex = 1,
            visibleCount = 6,
            focusedItemOffset = 335,
            focusedItemSize = 100,
            viewportStart = -30,
            viewportEnd = 800,
        )

        assertEquals(FileBrowserRevealAction.None, action)
    }

    @Test
    fun `a directory change SEEKS without motion -- a rebuilt listing has nothing to travel from`() {
        // UI-01's lesson, and F-03's division of labour: the scroll seat is instant and the GLIDE is
        // what acknowledges the state change. Easing a scroll here would animate through positions that
        // describe no real movement, because the listing being left is already gone.
        val action = fileBrowserRevealAction(
            targetIndex = 7,
            firstVisibleIndex = 0,
            visibleCount = 4,
            focusedItemOffset = 600,
            focusedItemSize = 100,
            viewportStart = 0,
            viewportEnd = 800,
            restoring = true,
        )

        assertTrue("a restore must never animate", action is FileBrowserRevealAction.SeekTo)
        assertEquals(FileBrowserRevealAction.SeekTo(7, -350), action)
    }

    @Test
    fun `an uncomposed target animates by index, seated centrally rather than pinned to the top`() {
        // A page jump or a restored focus memory far down the listing. This is a DISCLOSED REVERSAL of a
        // the earlier change decision, which top-pinned this case on the reasoning that "for a jump the player
        // asked for, landing the target at the top IS the useful framing". F-02 names the gamelist as
        // the standard and the gamelist centres, so this centres.
        val action = fileBrowserRevealAction(
            targetIndex = 20,
            firstVisibleIndex = 0,
            visibleCount = 4,
            focusedItemOffset = null,
            focusedItemSize = null,
            viewportStart = 0,
            viewportEnd = 800,
            estimatedItemSize = 200,
        )

        assertEquals(FileBrowserRevealAction.AnimateTo(20, -300), action)
    }

    @Test
    fun `nothing laid out yet seeks rather than animating from a viewport that has no rows in it`() {
        val action = fileBrowserRevealAction(
            targetIndex = 2,
            firstVisibleIndex = null,
            visibleCount = 0,
            focusedItemOffset = null,
            focusedItemSize = null,
            viewportStart = 0,
            viewportEnd = 800,
            estimatedItemSize = 100,
        )

        assertEquals(FileBrowserRevealAction.SeekTo(2, -350), action)
    }

    @Test
    fun `an estimate taller than the viewport cannot push the seek offset positive`() {
        // A positive scrollOffset would seat the target ABOVE the viewport start, i.e. off-screen.
        val action = fileBrowserRevealAction(
            targetIndex = 3,
            firstVisibleIndex = null,
            visibleCount = 0,
            focusedItemOffset = null,
            focusedItemSize = null,
            viewportStart = 0,
            viewportEnd = 300,
            estimatedItemSize = 900,
        )

        assertEquals(FileBrowserRevealAction.SeekTo(3, 0), action)
    }

    @Test
    fun `no target index at all reveals nothing`() {
        assertEquals(
            FileBrowserRevealAction.None,
            fileBrowserRevealAction(
                targetIndex = -1,
                firstVisibleIndex = 0,
                visibleCount = 3,
                focusedItemOffset = 0,
                focusedItemSize = 100,
                viewportStart = 0,
                viewportEnd = 800,
            ),
        )
    }

    // ── F-02: the estimated row height that seats an uncomposed target ──

    @Test
    fun `a tome row's estimated height is its own derived pitch less the inter-row gap`() {
        val partition = FileBrowserPartition(
            tomes = List(2) { tile("dir$it", FileBrowserTileKind.DIRECTORY) },
            files = List(2) { tile("file$it") },
        )

        val height = fileBrowserFocusRowHeightDp(
            partition = partition,
            focusedKey = FileBrowserFocus.tileKey("/root/dir1"),
            commitKey = null,
            grid = FileBrowserGrid.Authored,
        )

        // RC-12: no longer the hand-rounded `FileBrowserTomeRowPitchDp` (164.5f) -- the solved grid
        // recomputes the tome row pitch from the cell width every time (see FileBrowserGrid's own KDoc
        // for why freezing it is the exact bug this ticket exists to close), so the honest expectation is
        // the SAME solve the production code reads, `FileBrowserGrid.Authored.tomeRowPitchDp` (~164.52,
        // the arithmetically correct value `FileBrowserFocusRegistrationTest`'s own derivation test below
        // already computes -- 164.5f was always that value hand-rounded to one decimal place).
        assertEquals(FileBrowserGrid.Authored.tomeRowPitchDp - FileBrowserRowGapDp, height)
    }

    @Test
    fun `a file row's estimated height is the FILE pitch, not whichever section came first`() {
        // The whole reason this exists instead of the reference's own average-of-visible-items: the two
        // sections have different pitches and the folio's visible items include ~22dp section headings,
        // which would drag any mean well below either real row.
        val partition = FileBrowserPartition(
            tomes = List(2) { tile("dir$it", FileBrowserTileKind.DIRECTORY) },
            files = List(3) { tile("file$it") },
        )

        val height = fileBrowserFocusRowHeightDp(
            partition = partition,
            focusedKey = FileBrowserFocus.tileKey("/root/file2"),
            commitKey = null,
            grid = FileBrowserGrid.Authored,
        )

        assertEquals(FileBrowserFileRowPitchDp - FileBrowserRowGapDp, height)
        assertTrue("the two sections must not report the same height", height != FileBrowserTomeRowPitchDp - FileBrowserRowGapDp)
    }

    @Test
    fun `the pinned commit row reports its own height, not the first tile row's`() {
        val commitKey = FileBrowserFocus.commitKey("/root")
        val partition = FileBrowserPartition(
            tomes = List(1) { tile("dir$it", FileBrowserTileKind.DIRECTORY) },
            files = emptyList(),
        )

        val height = fileBrowserFocusRowHeightDp(partition, commitKey, commitKey, FileBrowserGrid.Authored)

        assertEquals(FileBrowserCommitRowPitchDp - FileBrowserRowGapDp, height)
    }

    @Test
    fun `a key from no row in this listing has no height -- the caller then falls back to a top pin`() {
        val partition = FileBrowserPartition(tomes = emptyList(), files = List(2) { tile("file$it") })

        assertNull(fileBrowserFocusRowHeightDp(partition, FocusKey("files/tile/elsewhere/x"), null, FileBrowserGrid.Authored))
        assertNull(fileBrowserFocusRowHeightDp(partition, null, null, FileBrowserGrid.Authored))
    }

    @Test
    fun `the commit row maps to lazy item 0 -- topmost is not the same as visible`() {
        val commitKey = FileBrowserFocus.commitKey("/root")
        val partition = FileBrowserPartition(tomes = listOf(tile("a", FileBrowserTileKind.DIRECTORY)), files = emptyList())

        assertEquals(
            "NAV_UP out of tome row 0 in a scrolled listing lands here, and nothing else would scroll to it",
            0,
            fileBrowserScrollTargetIndex(partition, commitKey, commitKey, FileBrowserGrid.Authored),
        )
    }

    @Test
    fun `a tome row is shifted one item down when the commit row is rendered ahead of it`() {
        val tomes = (1..4).map { tile("t$it", FileBrowserTileKind.DIRECTORY) }
        val partition = FileBrowserPartition(tomes = tomes, files = emptyList())
        val focused = FileBrowserFocus.tileKey(tomes[0].path)

        assertEquals("MANAGE: heading(0) + row(1)", 1, fileBrowserScrollTargetIndex(partition, focused, commitKey = null, grid = FileBrowserGrid.Authored))
        assertEquals(
            "PICK: commit(0) + heading(1) + row(2)",
            2,
            fileBrowserScrollTargetIndex(partition, focused, commitKey = FileBrowserFocus.commitKey("/root"), grid = FileBrowserGrid.Authored),
        )
    }

    @Test
    fun `nothing focused, or focus in another listing entirely, reveals nothing`() {
        val partition = FileBrowserPartition(tomes = listOf(tile("a", FileBrowserTileKind.DIRECTORY)), files = emptyList())

        assertNull(fileBrowserScrollTargetIndex(partition, null, commitKey = null, grid = FileBrowserGrid.Authored))
        assertNull(fileBrowserScrollTargetIndex(partition, FocusKey("files/tile/elsewhere/x"), commitKey = null, grid = FileBrowserGrid.Authored))
    }

    @Test
    fun `a commit key that is not rendered reveals nothing -- MANAGE, and the drive chooser`() {
        val partition = FileBrowserPartition(tomes = listOf(tile("a", FileBrowserTileKind.DIRECTORY)), files = emptyList())

        assertNull(
            "UI-05: where the controller refuses a commit key, no row is rendered and nothing is offset",
            fileBrowserScrollTargetIndex(partition, FileBrowserFocus.commitKey("/root"), commitKey = null, grid = FileBrowserGrid.Authored),
        )
    }

    // ── tracker ──

    @Test
    fun `the tracker registers every tile it is given, virtually, in the given scope`() {
        val authority = DefaultFocusAuthority()
        val scope = FocusScope("files-test")
        authority.pushScope(scope)
        val tracker = FileBrowserFocusTracker()
        val partition = FileBrowserPartition(
            tomes = listOf(tile("a", FileBrowserTileKind.DIRECTORY)),
            files = listOf(tile("b"), tile("c")),
        )

        tracker.update(authority, scope, partition, FileBrowserGrid.Authored)

        val expected = setOf(
            FileBrowserFocus.tileKey("/root/a"),
            FileBrowserFocus.tileKey("/root/b"),
            FileBrowserFocus.tileKey("/root/c"),
        )
        assertEquals(expected, authority.registeredKeysInScope(scope).toSet())
    }

    @Test
    fun `the tracker also registers the commit key, virtually, when one is given -- PICK mode`() {
        val authority = DefaultFocusAuthority()
        val scope = FocusScope("files-test")
        authority.pushScope(scope)
        val tracker = FileBrowserFocusTracker()
        val partition = FileBrowserPartition(tomes = listOf(tile("a", FileBrowserTileKind.DIRECTORY)), files = emptyList())
        val commitKey = FileBrowserFocus.commitKey("/root")

        tracker.update(authority, scope, partition, FileBrowserGrid.Authored, commitKey)

        assertTrue(authority.registeredKeysInScope(scope).contains(commitKey))
    }

    @Test
    fun `a directory change's new commit key replaces the old one -- the parent directory's key is unregistered`() {
        val authority = DefaultFocusAuthority()
        val scope = FocusScope("files-test")
        authority.pushScope(scope)
        val tracker = FileBrowserFocusTracker()
        val partition = FileBrowserPartition(tomes = emptyList(), files = listOf(tile("a")))
        val rootCommit = FileBrowserFocus.commitKey("/root")
        val childCommit = FileBrowserFocus.commitKey("/root/Games")

        tracker.update(authority, scope, partition, FileBrowserGrid.Authored, rootCommit)
        tracker.update(authority, scope, partition, FileBrowserGrid.Authored, childCommit)

        val registered = authority.registeredKeysInScope(scope)
        assertFalse("the parent directory's commit key must not survive navigation", registered.contains(rootCommit))
        assertTrue(registered.contains(childCommit))
    }

    @Test
    fun `a second update unregisters tiles that dropped out of the new listing`() {
        val authority = DefaultFocusAuthority()
        val scope = FocusScope("files-test")
        authority.pushScope(scope)
        val tracker = FileBrowserFocusTracker()

        tracker.update(authority, scope, FileBrowserPartition(tomes = emptyList(), files = listOf(tile("a"), tile("b"))), FileBrowserGrid.Authored)
        tracker.update(authority, scope, FileBrowserPartition(tomes = emptyList(), files = listOf(tile("a"), tile("d"))), FileBrowserGrid.Authored)

        assertEquals(
            setOf(FileBrowserFocus.tileKey("/root/a"), FileBrowserFocus.tileKey("/root/d")),
            authority.registeredKeysInScope(scope).toSet(),
        )
    }

    @Test
    fun `clear unregisters everything this tracker registered`() {
        val authority = DefaultFocusAuthority()
        val scope = FocusScope("files-test")
        authority.pushScope(scope)
        val tracker = FileBrowserFocusTracker()
        tracker.update(authority, scope, FileBrowserPartition(tomes = emptyList(), files = listOf(tile("a"))), FileBrowserGrid.Authored)

        tracker.clear(authority)

        assertTrue(authority.registeredKeysInScope(scope).isEmpty())
    }

    @Test
    fun `clear is idempotent -- a second call has nothing left to remove`() {
        val authority = DefaultFocusAuthority()
        val scope = FocusScope("files-test")
        authority.pushScope(scope)
        val tracker = FileBrowserFocusTracker()
        tracker.update(authority, scope, FileBrowserPartition(tomes = emptyList(), files = listOf(tile("a"))), FileBrowserGrid.Authored)

        tracker.clear(authority)
        tracker.clear(authority) // must not throw, must not resurrect anything

        assertTrue(authority.registeredKeysInScope(scope).isEmpty())
    }

    // ── mechanism B: a departed directory must not survive as PIXEL-bounds ghosts ──
    //
    // `DefaultFocusAuthority.unregisterVirtual` DEMOTES rather than removes when the same key also
    // carries a physical registration (its own `physicalBounds != null` branch). At [FileBrowserController.load]
    // the OUTGOING directory's tiles are still composed, so `Modifier.mimirFocusable` has already
    // registered them with real WINDOW PIXELS, while this tracker's grid is SYNTHETIC (rows at multiples
    // of 7 000). Pixel numbers are numerically tiny next to synthetic ones, so a demoted ghost always
    // scores "just above" a real target and wins NAV_UP -- focus lands on a key from the directory the
    // player just left, `focusedTileAt` resolves it to nothing, and no tile draws a ring at all. On the
    // device that reads as "the D-pad stopped working", and it is asymmetric: DOWN still works.
    //
    // These two are the pins for that fix. Revert [FileBrowserFocusTracker]'s paired `unregister` call
    // and both fail; that is the differential.

    /** Both registrations a browsed-and-composed tile really carries: this tracker's synthetic slot AND
     * the window pixels `Modifier.mimirFocusable`'s `onGloballyPositioned` reports for it. */
    private fun composeTilesPhysically(
        authority: DefaultFocusAuthority,
        scope: FocusScope,
        paths: List<String>,
    ) = paths.forEachIndexed { index, path ->
        authority.register(
            key = FileBrowserFocus.tileKey(path),
            scope = scope,
            // A plausible on-screen row in window pixels -- the magnitude is the whole point.
            bounds = FocusBounds(left = 0f, top = 60f + index * 110f, right = 200f, bottom = 160f + index * 110f),
        )
    }

    @Test
    fun `a departed directory's tiles do not survive as pixel ghosts and steal NAV_UP`() {
        val authority = DefaultFocusAuthority()
        val scope = FocusScope("files-test")
        authority.pushScope(scope)
        val tracker = FileBrowserFocusTracker()

        // Directory A: browsed, so its tiles are composed and carry BOTH kinds of bounds.
        tracker.update(
            authority,
            scope,
            FileBrowserPartition(tomes = emptyList(), files = listOf(tile("a1"), tile("a2"))),
            FileBrowserGrid.Authored,
        )
        composeTilesPhysically(authority, scope, listOf("/root/a1", "/root/a2"))

        // Navigate into directory B, in `load()`'s own order: clearFocus BEFORE the tracker diff.
        authority.clearFocus()
        tracker.update(
            authority,
            scope,
            FileBrowserPartition(tomes = listOf(tile("b1", FileBrowserTileKind.DIRECTORY)), files = emptyList()),
            FileBrowserGrid.Authored,
        )

        val firstOfB = FileBrowserFocus.tileKey("/root/b1")
        authority.requestFocus(firstOfB)
        assertEquals("precondition: focus starts on the new directory's own top row", firstOfB, authority.state.value.focused)

        assertFalse(
            "nothing is registered above the top row of the new directory, so a NAV_UP that SUCCEEDS " +
                "here can only have landed on a ghost from the directory just left",
            authority.moveFocus(ControllerAction.NAV_UP),
        )
        assertEquals(
            "focus must still be on the new directory's own tile -- a key with no tile behind it draws " +
                "no ring and blanks the inspector",
            firstOfB,
            authority.state.value.focused,
        )
        assertTrue(
            "the departed directory's keys must be GONE from the scope, not demoted to their pixels",
            authority.registeredKeysInScope(scope).none { it.value.startsWith("files/tile/root/a") },
        )
    }

    @Test
    fun `clear removes a composed tile too -- closing the browser leaves no demoted ghost behind`() {
        val authority = DefaultFocusAuthority()
        val scope = FocusScope("files-test")
        authority.pushScope(scope)
        val tracker = FileBrowserFocusTracker()
        tracker.update(authority, scope, FileBrowserPartition(tomes = emptyList(), files = listOf(tile("a"))), FileBrowserGrid.Authored)
        composeTilesPhysically(authority, scope, listOf("/root/a"))

        tracker.clear(authority)

        assertTrue(
            "a tile that was on screen when the browser closed must not outlive it in the focus graph",
            authority.registeredKeysInScope(scope).isEmpty(),
        )
    }

    /**
     * **Changing retire ordering produces no differential failure; this test records why.**
     *
     * Reversing `retire`'s ordering produces no failure.
     * `unregisterVirtual`-then-`unregister` and `unregister`-then-`unregisterVirtual` both REMOVE an entry
     * carrying both kinds of bounds — each call demotes while the other half is present, and the second
     * then finds a single-bounds entry and removes it outright. The order is symmetric; only the PAIRING
     * is load-bearing. The real differential for the two tests above is deleting EITHER call from
     * [FileBrowserFocusTracker.retire], which leaves a demoted single-bounds ghost behind.
     *
     * Asserted here rather than only reported, because "the order does not matter" is exactly the kind of
     * claim that gets re-broken by someone tidying a two-line function.
     */
    @Test
    fun `retire needs BOTH unregister calls -- their ORDER is not what removes a both-bounds entry`() {
        val scope = FocusScope("files-test")
        val key = FileBrowserFocus.tileKey("/root/a")

        fun bothBoundsAuthority(): DefaultFocusAuthority = DefaultFocusAuthority().apply {
            pushScope(scope)
            registerVirtual(key, scope, fileBrowserFileVirtualBounds(index = 0, tomeCount = 0, tomeColumns = FileBrowserTomeColumns, fileColumns = FileBrowserFileColumns))
            register(key, scope, FocusBounds(left = 0f, top = 60f, right = 200f, bottom = 160f))
        }

        val virtualFirst = bothBoundsAuthority().apply { unregisterVirtual(key); unregister(key) }
        val physicalFirst = bothBoundsAuthority().apply { unregister(key); unregisterVirtual(key) }
        assertTrue(
            "virtual-then-physical removes the entry",
            virtualFirst.registeredKeysInScope(scope).isEmpty(),
        )
        assertTrue(
            "SO DOES physical-then-virtual -- swapping retire's two lines is not a differential, and any " +
                "report claiming that swap turns the mechanism-B tests red is wrong",
            physicalFirst.registeredKeysInScope(scope).isEmpty(),
        )

        val virtualOnly = bothBoundsAuthority().apply { unregisterVirtual(key) }
        val physicalOnly = bothBoundsAuthority().apply { unregister(key) }
        assertEquals(
            "dropping the paired `unregister` DEMOTES to pixel bounds instead of removing -- this is the " +
                "real differential, and this is the ghost",
            listOf(key),
            virtualOnly.registeredKeysInScope(scope),
        )
        assertEquals(
            "dropping `unregisterVirtual` leaves the synthetic half behind, symmetrically",
            listOf(key),
            physicalOnly.registeredKeysInScope(scope),
        )
    }

    // ──────────────────────────────── F-01 · the Queue screen's focus graph (PR #140) ──

    private fun queueCard(entryId: Long, vararg actions: FileBrowserQueueAction) = FileBrowserQueueCard(
        entryId = entryId,
        title = "Zelda.gba",
        subtitle = "Delete",
        state = FileQueueState.Done,
        tone = FileBrowserQueueTone.DONE,
        progress = null,
        actions = actions.toList(),
    )

    @Test
    fun `the queue tracker registers one virtual slot per button, in render order`() {
        val authority = DefaultFocusAuthority()
        val scope = FocusScope("files-test/queue")
        authority.pushScope(scope)

        FileBrowserQueueFocusTracker().update(
            authority,
            scope,
            listOf(
                queueCard(1, FileBrowserQueueAction.UNDO, FileBrowserQueueAction.KEEP_DELETED),
                queueCard(2, FileBrowserQueueAction.DISMISS),
            ),
        )

        assertEquals(
            listOf(
                FileBrowserQueueFocus.actionKey(1, FileBrowserQueueAction.UNDO),
                FileBrowserQueueFocus.actionKey(1, FileBrowserQueueAction.KEEP_DELETED),
                FileBrowserQueueFocus.actionKey(2, FileBrowserQueueAction.DISMISS),
            ),
            authority.registeredKeysInScope(scope),
        )
    }

    @Test
    fun `a queue of nothing but running transfers registers no slots at all, and that is not a defect`() {
        val authority = DefaultFocusAuthority()
        val scope = FocusScope("files-test/queue")
        authority.pushScope(scope)

        FileBrowserQueueFocusTracker().update(authority, scope, listOf(queueCard(1), queueCard(2)))

        assertTrue(authority.registeredKeysInScope(scope).isEmpty())
        assertNull("with nothing registered there is nothing to focus", authority.state.value.focused)
    }

    @Test
    fun `an entry that leaves the queue takes its buttons out of the focus graph with it`() {
        val authority = DefaultFocusAuthority()
        val scope = FocusScope("files-test/queue")
        authority.pushScope(scope)
        val tracker = FileBrowserQueueFocusTracker()
        tracker.update(
            authority,
            scope,
            listOf(queueCard(1, FileBrowserQueueAction.UNDO), queueCard(2, FileBrowserQueueAction.DISMISS)),
        )

        tracker.update(authority, scope, listOf(queueCard(2, FileBrowserQueueAction.DISMISS)))

        assertEquals(
            "pressing Undo removes the entry -- its button must not linger as a focusable key that " +
                "resolves to an operation that no longer exists",
            listOf(FileBrowserQueueFocus.actionKey(2, FileBrowserQueueAction.DISMISS)),
            authority.registeredKeysInScope(scope),
        )
    }

    @Test
    fun `queue button 0 of every card sits at the same column, so NAV_DOWN never lands on Replace`() {
        val authority = DefaultFocusAuthority()
        val scope = FocusScope("files-test/queue")
        authority.pushScope(scope)
        FileBrowserQueueFocusTracker().update(
            authority,
            scope,
            listOf(
                // A one-button card above a three-button conflict card: the exact shape that a
                // share-of-the-row layout would resolve onto the MIDDLE button.
                queueCard(1, FileBrowserQueueAction.DISMISS),
                queueCard(
                    2,
                    FileBrowserQueueAction.KEEP_BOTH,
                    FileBrowserQueueAction.REPLACE,
                    FileBrowserQueueAction.SKIP,
                ),
            ),
        )
        authority.requestFocus(FileBrowserQueueFocus.actionKey(1, FileBrowserQueueAction.DISMISS))

        assertTrue(authority.moveFocus(ControllerAction.NAV_DOWN))

        assertEquals(
            "NAV_DOWN onto a conflict card must land on its FIRST answer. A width-share layout centres " +
                "the single button between Keep both and Skip, and minByOrNull then picks Replace -- " +
                "seating the ring on the destructive default by accident of arithmetic",
            FileBrowserQueueFocus.actionKey(2, FileBrowserQueueAction.KEEP_BOTH),
            authority.state.value.focused,
        )
    }

    @Test
    fun `a card with no buttons still occupies its row, so it is not compacted out of the geometry`() {
        val running = fileBrowserQueueActionBounds(cardIndex = 0, actionIndex = 0)
        val afterOneSkippedCard = fileBrowserQueueActionBounds(cardIndex = 2, actionIndex = 0)
        assertEquals(
            "card index is the position in the WHOLE list; compacting a button-less running card out " +
                "would score its two neighbours as adjacent when a full card sits between them on screen",
            2f,
            (afterOneSkippedCard.top - running.top) / (running.bottom - running.top),
            0.0001f,
        )
    }

    // ─────────────────── B-4 · where focus goes when the tile under the ring is deleted (the earlier change) ──

    @Test
    fun `the surviving neighbour is the tile that slid into the deleted one's place`() {
        val keys = listOf("a", "b", "c", "d").map { FileBrowserFocus.tileKey("/root/$it") }
        val surviving = keys.filterNot { it == FileBrowserFocus.tileKey("/root/b") }

        assertEquals(
            "forward before backward: the key that slid up into the gap is the one the player's eye is " +
                "already on, and falling backward would move the ring away from the gap they just made",
            FileBrowserFocus.tileKey("/root/c"),
            fileBrowserSurvivingNeighbour(keys, FileBrowserFocus.tileKey("/root/b"), surviving),
        )
    }

    @Test
    fun `deleting the LAST tile falls backward, because there is no forward left`() {
        val keys = listOf("a", "b", "c").map { FileBrowserFocus.tileKey("/root/$it") }
        val surviving = keys.dropLast(1)

        assertEquals(
            FileBrowserFocus.tileKey("/root/b"),
            fileBrowserSurvivingNeighbour(keys, FileBrowserFocus.tileKey("/root/c"), surviving),
        )
    }

    @Test
    fun `a whole BLOCK of departed tiles is walked past rather than giving up at the first gap`() {
        val keys = listOf("a", "b", "c", "d", "e").map { FileBrowserFocus.tileKey("/root/$it") }
        val surviving = listOf(FileBrowserFocus.tileKey("/root/a"), FileBrowserFocus.tileKey("/root/e"))

        assertEquals(
            "a multi-select delete removes a run of keys; stopping at index+1 would return null and dump " +
                "the player at index 0",
            FileBrowserFocus.tileKey("/root/e"),
            fileBrowserSurvivingNeighbour(keys, FileBrowserFocus.tileKey("/root/c"), surviving),
        )
    }

    @Test
    fun `a surviving focused tile is kept, never traded for its neighbour`() {
        val keys = listOf("a", "b", "c").map { FileBrowserFocus.tileKey("/root/$it") }

        assertEquals(
            FileBrowserFocus.tileKey("/root/b"),
            fileBrowserSurvivingNeighbour(keys, FileBrowserFocus.tileKey("/root/b"), keys),
        )
    }

    @Test
    fun `nothing focused, focus outside the listing, or an emptied directory all answer null`() {
        val keys = listOf("a", "b").map { FileBrowserFocus.tileKey("/root/$it") }

        assertNull(fileBrowserSurvivingNeighbour(keys, null, keys))
        assertNull(
            "focus in another scope entirely is not a departed TILE, so there is no neighbour to find",
            fileBrowserSurvivingNeighbour(keys, FocusKey("files/search/query"), keys),
        )
        assertNull(
            "an emptied directory has no honest landing but index 0 -- which is null, and the caller " +
                "then requests nothing at all rather than a dead key",
            fileBrowserSurvivingNeighbour(keys, FileBrowserFocus.tileKey("/root/a"), emptyList()),
        )
    }
}

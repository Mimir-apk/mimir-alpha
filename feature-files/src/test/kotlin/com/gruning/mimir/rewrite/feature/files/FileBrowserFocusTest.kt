package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.FocusKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileBrowserFocusTest {

    private fun tile(name: String, kind: FileBrowserTileKind, isDirectory: Boolean = kind == FileBrowserTileKind.DIRECTORY) =
        FileBrowserTile(
            item = FileItem(name = name, path = "/root/$name", isDirectory = isDirectory),
            kind = kind,
            machinery = false,
            platformIdValue = null,
        )

    private val directory = tile("Roms", FileBrowserTileKind.DIRECTORY)
    private val archive = tile("bundle.zip", FileBrowserTileKind.ARCHIVE)

    @Test
    fun `tileKey is derived from the absolute path`() {
        assertEquals(FocusKey("files/tile/root/Roms"), FileBrowserFocus.tileKey("/root/Roms"))
    }

    @Test
    fun `two tiles with the same path produce the same key regardless of kind`() {
        val a = FileBrowserFocus.tileKey("/root/bundle.zip")
        val b = FileBrowserFocus.tileKey("/root/bundle.zip")
        assertEquals(a, b)
    }

    @Test
    fun `two different paths never produce the same key`() {
        assertNotEquals(FileBrowserFocus.tileKey("/root/a"), FileBrowserFocus.tileKey("/root/b"))
    }

    @Test
    fun `fileBrowserKeys orders tomes before files, matching render order`() {
        val partition = FileBrowserPartition(tomes = listOf(directory), files = listOf(archive))

        val keys = fileBrowserKeys(partition, FileBrowserGrid.Authored)

        assertEquals(listOf(FileBrowserFocus.tileKey(directory.path), FileBrowserFocus.tileKey(archive.path)), keys)
    }

    @Test
    fun `fileBrowserKeys prepends the commit key at index 0 when given one`() {
        val partition = FileBrowserPartition(tomes = listOf(directory), files = listOf(archive))
        val commitKey = FileBrowserFocus.commitKey("/root")

        val keys = fileBrowserKeys(partition, FileBrowserGrid.Authored, commitKey)

        assertEquals(
            listOf(commitKey, FileBrowserFocus.tileKey(directory.path), FileBrowserFocus.tileKey(archive.path)),
            keys,
        )
    }

    @Test
    fun `fileBrowserKeys with a null commit key is unchanged from the no-commit-key call -- MANAGE mode`() {
        val partition = FileBrowserPartition(tomes = listOf(directory), files = listOf(archive))

        assertEquals(
            fileBrowserKeys(partition, FileBrowserGrid.Authored),
            fileBrowserKeys(partition, FileBrowserGrid.Authored, commitKey = null),
        )
    }

    @Test
    fun `commitKey is namespaced distinctly from tileKey -- a real file can never collide with the commit affordance`() {
        assertNotEquals(FileBrowserFocus.commitKey("/root"), FileBrowserFocus.tileKey("/root"))
    }

    @Test
    fun `commitKey is namespaced by the directory it commits -- two directories never share an identity`() {
        assertNotEquals(FileBrowserFocus.commitKey("/root"), FileBrowserFocus.commitKey("/root/Games"))
    }

    // ── F-2: backToOriginKey ──

    @Test
    fun `backToOriginKey is namespaced distinctly from tileKey and commitKey`() {
        assertNotEquals(FileBrowserFocus.backToOriginKey("/root"), FileBrowserFocus.tileKey("/root"))
        assertNotEquals(FileBrowserFocus.backToOriginKey("/root"), FileBrowserFocus.commitKey("/root"))
    }

    @Test
    fun `backToOriginKey is namespaced by the ORIGIN path -- two different origins never share an identity`() {
        assertNotEquals(FileBrowserFocus.backToOriginKey("/root/Downloads"), FileBrowserFocus.backToOriginKey("/root/Roms"))
    }

    @Test
    fun `backToOriginKey does not collide with commitKey even when origin equals current path -- the common SETTLED case`() {
        // The pick's most common phase has
        // originPath == currentPath. If both keys were built the same way off the same string, the
        // distinct "files/backOrigin" vs "files/commit" namespace segments are the only thing keeping the
        // two rows' identities apart.
        assertNotEquals(FileBrowserFocus.backToOriginKey("/root"), FileBrowserFocus.commitKey("/root"))
    }

    @Test
    fun `backToOriginKey is stable -- the same origin always produces the same key`() {
        assertEquals(FileBrowserFocus.backToOriginKey("/root/Downloads"), FileBrowserFocus.backToOriginKey("/root/Downloads"))
    }

    // ── F-2: the pinned row gains a SECOND KEY, not a second row ──

    @Test
    fun `fileBrowserKeys places backKey immediately after commitKey -- one pinned row, two columns`() {
        val partition = FileBrowserPartition(tomes = listOf(directory), files = listOf(archive))
        val commitKey = FileBrowserFocus.commitKey("/root")
        val backKey = FileBrowserFocus.backToOriginKey("/root/Downloads")

        val keys = fileBrowserKeys(partition, FileBrowserGrid.Authored, commitKey, backKey)

        assertEquals(
            listOf(
                commitKey,
                backKey,
                FileBrowserFocus.tileKey(directory.path),
                FileBrowserFocus.tileKey(archive.path),
            ),
            keys,
        )
    }

    @Test
    fun `fileBrowserFocusRows keeps commitKey and backKey in the SAME row -- not two separate rows`() {
        val partition = FileBrowserPartition(tomes = listOf(directory), files = listOf(archive))
        val commitKey = FileBrowserFocus.commitKey("/root")
        val backKey = FileBrowserFocus.backToOriginKey("/root/Downloads")

        val rows = fileBrowserFocusRows(partition, FileBrowserGrid.Authored, commitKey, backKey)

        assertEquals("exactly one pinned row (plus the tome row and the file row) -- not four rows", 3, rows.size)
        assertEquals("both keys share row 0", listOf(commitKey, backKey), rows[0])
    }

    @Test
    fun `fileBrowserKeys with a null backKey is byte-identical to the pre-F-2 call -- differential pin`() {
        // Confirms F-2 is additive: a caller that never passes backKey (every caller written before this
        // ticket) gets the exact list it always did.
        val partition = FileBrowserPartition(tomes = listOf(directory), files = listOf(archive))
        val commitKey = FileBrowserFocus.commitKey("/root")

        assertEquals(
            fileBrowserKeys(partition, FileBrowserGrid.Authored, commitKey),
            fileBrowserKeys(partition, FileBrowserGrid.Authored, commitKey, backKey = null),
        )
    }

    @Test
    fun `fileBrowserKeys never places backKey without commitKey -- the sibling cannot exist alone`() {
        // Documents the invariant FileBrowserController.backToOriginKeyForCurrentMode relies on
        // (commitAvailable is a precondition for the back key) -- if it were ever violated, the pinned
        // row would render a lone back-to-origin cell with nothing to its left, which is not a shape any
        // layout permits. This test pins the MODEL's own behaviour if that precondition is (incorrectly)
        // dropped by a future caller: the key still appears, alone, in row 0 -- i.e. the model does not
        // defend against it, the CONTROLLER's gate is what must (and does -- see
        // FileBrowserControllerTest).
        val partition = FileBrowserPartition(tomes = listOf(directory), files = emptyList())
        val backKey = FileBrowserFocus.backToOriginKey("/root")

        val rows = fileBrowserFocusRows(partition, FileBrowserGrid.Authored, commitKey = null, backKey = backKey)

        assertEquals(listOf(backKey), rows.first())
    }

    @Test
    fun `focusedTileAt resolves the tile whose key equals the focused key`() {
        val partition = FileBrowserPartition(tomes = listOf(directory), files = listOf(archive))

        assertEquals(directory, focusedTileAt(partition, FileBrowserFocus.tileKey(directory.path)))
        assertEquals(archive, focusedTileAt(partition, FileBrowserFocus.tileKey(archive.path)))
    }

    @Test
    fun `focusedTileAt returns null for an unregistered or null key`() {
        val partition = FileBrowserPartition(tomes = listOf(directory), files = emptyList())

        assertNull(focusedTileAt(partition, null))
        assertNull(focusedTileAt(partition, FocusKey("files/tile/somewhere-else-entirely")))
    }

    // ── the row model, and the paging that reads it (added 2026-08-14) ──

    private fun grid(tomeCount: Int, fileCount: Int) = FileBrowserPartition(
        tomes = (1..tomeCount).map { tile("t$it", FileBrowserTileKind.DIRECTORY) },
        files = (1..fileCount).map { tile("f$it", FileBrowserTileKind.FILE) },
    )

    @Test
    fun `rows chunk tomes and files by their OWN column counts, never a shared one`() {
        val rows = fileBrowserFocusRows(grid(tomeCount = 5, fileCount = 6), FileBrowserGrid.Authored)

        assertEquals(
            "2 tome rows at $FileBrowserTomeColumns wide, then 2 file rows at $FileBrowserFileColumns wide",
            listOf(4, 1, 5, 1),
            rows.map { it.size },
        )
    }

    @Test
    fun `the commit affordance is a row of its own at index 0`() {
        val commitKey = FileBrowserFocus.commitKey("/root")

        val rows = fileBrowserFocusRows(grid(tomeCount = 4, fileCount = 5), FileBrowserGrid.Authored, commitKey)

        assertEquals(listOf(commitKey), rows.first())
    }

    @Test
    fun `fileBrowserKeys is exactly the rows flattened -- one ordering, not two`() {
        val partition = grid(tomeCount = 5, fileCount = 6)
        val commitKey = FileBrowserFocus.commitKey("/root")

        assertEquals(
            fileBrowserFocusRows(partition, FileBrowserGrid.Authored, commitKey).flatten(),
            fileBrowserKeys(partition, FileBrowserGrid.Authored, commitKey),
        )
    }

    /**
     * Rows AND pitches from ONE partition, exactly as `FileBrowserController.dispatch` builds them --
     * so no test here can accidentally page a listing against another listing's row heights.
     */
    private fun pageTarget(
        partition: FileBrowserPartition,
        focused: FocusKey?,
        forward: Boolean,
        commitKey: FocusKey? = null,
        backKey: FocusKey? = null,
    ) = fileBrowserPageTarget(
        rows = fileBrowserFocusRows(partition, FileBrowserGrid.Authored, commitKey, backKey),
        focused = focused,
        forward = forward,
        rowPitchDp = fileBrowserRowPitchesDp(
            partition,
            hasCommitRow = commitKey != null || backKey != null,
            FileBrowserGrid.Authored,
        ),
        grid = FileBrowserGrid.Authored,
    )

    // ── mechanism D: a page is one SCREENFUL, measured in dp -- not a fixed row count ──
    //
    // The predecessor jumped a constant 3 rows everywhere, derived from the 107dp FILE pitch and reused
    // for tomes on the argument that a taller row means "three tome rows is at most one screenful there
    // as well". That is backwards: fewer rows per screen means three is MORE than a screen. These pin
    // the corrected behaviour, and the two `-- not three` cases below are that fix's differential.

    @Test
    fun `a page through the TALLER tome grid is TWO rows -- three would be 1_4 screens of skipped tomes`() {
        val stride = fileBrowserPageStride(
            rowPitchDp = List(5) { FileBrowserTomeRowPitchDp },
            rowIndex = 0,
            forward = true,
            viewportDp = FileBrowserContentViewportDp,
        )

        assertEquals(
            "3 tome rows is ${3 * FileBrowserTomeRowPitchDp}dp against a " +
                "${FileBrowserContentViewportDp}dp viewport -- a band of tomes the player never saw",
            2,
            stride,
        )
    }

    @Test
    fun `a page through the file grid is still THREE rows -- the correction must not shrink that`() {
        val stride = fileBrowserPageStride(
            rowPitchDp = List(8) { FileBrowserFileRowPitchDp },
            rowIndex = 0,
            forward = true,
            viewportDp = FileBrowserContentViewportDp,
        )

        assertEquals(3, stride)
    }

    @Test
    fun `paging UP out of the files section measures the TOME rows it crosses, not the file stride`() {
        // The case a per-SECTION stride cannot get right: the press starts in the file section but every
        // row it travels over is a tome row, so the file section's own 3-row stride would overshoot
        // upward by exactly as much as the old constant overshot downward.
        val pitches = listOf(FileBrowserTomeRowPitchDp, FileBrowserTomeRowPitchDp, FileBrowserTomeRowPitchDp, FileBrowserFileRowPitchDp)

        val stride = fileBrowserPageStride(pitches, rowIndex = 3, forward = false, viewportDp = FileBrowserContentViewportDp)

        assertEquals("two tome rows up is 329dp; three is 494dp against a 350dp viewport", 2, stride)
    }

    @Test
    fun `a row taller than the whole viewport is still escapable in one press`() {
        val stride = fileBrowserPageStride(listOf(9_000f, 9_000f), rowIndex = 0, forward = true, viewportDp = FileBrowserContentViewportDp)

        assertEquals("a stride of 0 would make a shoulder button inert on that row", 1, stride)
    }

    // ── paging: target resolution ──

    @Test
    fun `PAGE_RIGHT jumps one screenful of rows, keeping the column`() {
        // 5 tome rows: t1..t20 at 4 columns. From row 0 column 2, a page forward is row 2 column 2.
        val target = pageTarget(grid(tomeCount = 20, fileCount = 0), FileBrowserFocus.tileKey("/root/t3"), forward = true)

        assertEquals(FileBrowserFocus.tileKey("/root/t11"), target) // row 2 (t9..t12), column 2
    }

    @Test
    fun `PAGE_LEFT is the exact inverse of PAGE_RIGHT away from the edges`() {
        val partition = grid(tomeCount = 20, fileCount = 0)
        val start = FileBrowserFocus.tileKey("/root/t3")

        val forward = pageTarget(partition, start, forward = true)
        val backAgain = pageTarget(partition, forward, forward = false)

        assertEquals(start, backAgain)
    }

    @Test
    fun `paging clamps to the last row rather than overshooting the listing`() {
        val target = pageTarget(grid(tomeCount = 8, fileCount = 0), FileBrowserFocus.tileKey("/root/t1"), forward = true)

        assertEquals("a page longer than the listing lands on the last row, not past it", FileBrowserFocus.tileKey("/root/t5"), target)
    }

    @Test
    fun `paging against an edge returns null so a held shoulder does not re-emit focus`() {
        val partition = grid(tomeCount = 4, fileCount = 0) // one row: already both edges

        assertNull(pageTarget(partition, FileBrowserFocus.tileKey("/root/t1"), forward = true))
        assertNull(pageTarget(partition, FileBrowserFocus.tileKey("/root/t1"), forward = false))
    }

    @Test
    fun `paging into a RAGGED final row clamps the column instead of landing on nothing`() {
        // 3 rows of tomes, the last holding a single tile; paging from column 3 must not index past it.
        val target = pageTarget(grid(tomeCount = 9, fileCount = 0), FileBrowserFocus.tileKey("/root/t4"), forward = true)

        assertEquals(FileBrowserFocus.tileKey("/root/t9"), target) // row 2 holds t9 alone
    }

    @Test
    fun `paging crosses the tome-file boundary as one continuous listing`() {
        // 1 tome row (164.5dp) + 3 file rows (107dp each): one screenful from the tome row is the tome
        // row itself plus ONE file row, not three of them.
        val target = pageTarget(grid(tomeCount = 4, fileCount = 15), FileBrowserFocus.tileKey("/root/t1"), forward = true)

        assertEquals("row 0 is the tome row, row 2 is the second file row", FileBrowserFocus.tileKey("/root/f6"), target)
    }

    @Test
    fun `paging back from the top of the listing reaches the commit affordance -- it is a real row`() {
        val commitKey = FileBrowserFocus.commitKey("/root")

        val target = pageTarget(grid(tomeCount = 8, fileCount = 0), FileBrowserFocus.tileKey("/root/t5"), forward = false, commitKey = commitKey)

        assertEquals(commitKey, target)
    }

    // ── paging into the pinned row during a pick: column decides which of the two keys ──
    //
    // fileBrowserPageTarget clamps the landing column to `targetRow.lastIndex`. With both commitKey and
    // backKey pinned, that row is 2 wide (index 0/1), not 1 -- so PAGE_LEFT from tile column 0 still lands
    // on commitKey (unchanged from the commit-only case above), but from any column >= 1 it now lands on
    // backKey instead of re-clamping to commitKey. This is the coercion in fileBrowserPageTarget, not new
    // dispatch logic -- pinned here so a future change to that clamp cannot silently retarget a page press.

    @Test
    fun `paging back from tile column 0 still lands on the commit affordance, even with backKey also pinned`() {
        val commitKey = FileBrowserFocus.commitKey("/root")
        val backKey = FileBrowserFocus.backToOriginKey("/root")

        val target = pageTarget(
            grid(tomeCount = 8, fileCount = 0),
            FileBrowserFocus.tileKey("/root/t5"), // row 1, column 0 (t5..t8 is row 1 at 4-wide)
            forward = false,
            commitKey = commitKey,
            backKey = backKey,
        )

        assertEquals(commitKey, target)
    }

    @Test
    fun `paging back from tile column 1 or later lands on backKey, not a re-clamped commitKey`() {
        val commitKey = FileBrowserFocus.commitKey("/root")
        val backKey = FileBrowserFocus.backToOriginKey("/root")

        val target = pageTarget(
            grid(tomeCount = 8, fileCount = 0),
            FileBrowserFocus.tileKey("/root/t6"), // row 1, column 1
            forward = false,
            commitKey = commitKey,
            backKey = backKey,
        )

        assertEquals("column 1 clamps to targetRow.lastIndex == 1, which is backKey once it exists", backKey, target)
    }

    @Test
    fun `paging is a no-op when nothing is focused, or when focus belongs to another listing entirely`() {
        val partition = grid(tomeCount = 20, fileCount = 0)

        assertNull(pageTarget(partition, null, forward = true))
        assertNull(pageTarget(partition, FocusKey("files/tile/some/other/directory/x"), forward = true))
    }

    @Test
    fun `paging an empty listing is a no-op rather than an index crash`() {
        assertNull(pageTarget(grid(tomeCount = 0, fileCount = 0), FileBrowserFocus.tileKey("/root/t1"), forward = true))
    }
}

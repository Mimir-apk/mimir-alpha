package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RC-48 (audit 2026-10-14): `tiledRows` resolved every Home row's files on the composition thread whenever the rows changed, and a
 * game's return changes them (a reorder). [HomeRowTileMemo] resolves each row once per epoch, so a reorder costs only what changed.
 */
class HomeRowTileMemoTest {

    private fun input(id: String, art: String? = "file:///art/$id.png", cover: Boolean = true) =
        HomeRowTileInputs(HomeShelfItemId.Emulated(GameId(id)), hasAutomaticCover = cover, artworkUri = art)

    private class Counter {
        var calls = 0
        val seen = mutableListOf<String>()
        fun resolve(input: HomeRowTileInputs): String {
            calls++
            seen += (input.itemId as HomeShelfItemId.Emulated).gameId.value
            return "tile:${input.artworkUri}"
        }
    }

    @Test
    fun `a reorder of the same rows resolves nothing`() {
        val memo = HomeRowTileMemo<String>()
        val counter = Counter()
        val rows = listOf(input("a"), input("b"), input("c"))
        memo.resolve(epoch = 1, inputs = rows, compute = counter::resolve)
        assertEquals(3, counter.calls)

        // The game that was just played moves to the front: every row's rank changes, none of its inputs do.
        val out = memo.resolve(epoch = 1, inputs = listOf(rows[2], rows[0], rows[1]), compute = counter::resolve)
        assertEquals("a reorder resolves no row again", 3, counter.calls)
        assertEquals(listOf("tile:file:///art/c.png", "tile:file:///art/a.png", "tile:file:///art/b.png"), out)
    }

    @Test
    fun `a row whose own art changed is resolved again and its neighbours are not`() {
        val memo = HomeRowTileMemo<String>()
        val counter = Counter()
        memo.resolve(1, listOf(input("a"), input("b"), input("c")), counter::resolve)
        counter.seen.clear()

        val out = memo.resolve(1, listOf(input("a"), input("b", art = "file:///art/b2.png"), input("c")), counter::resolve)
        assertEquals("only the row with new art", listOf("b"), counter.seen)
        assertEquals("tile:file:///art/b2.png", out[1])
    }

    @Test
    fun `a new epoch drops everything, so a fresh pick is never cached away`() {
        val memo = HomeRowTileMemo<String>()
        val counter = Counter()
        val rows = listOf(input("a"), input("b"))
        memo.resolve(1, rows, counter::resolve)
        counter.seen.clear()

        memo.resolve(2, rows, counter::resolve)
        assertEquals("every row is resolved again under a new epoch", listOf("a", "b"), counter.seen)

        counter.seen.clear()
        memo.resolve(2, rows, counter::resolve)
        assertTrue("and then they are remembered again", counter.seen.isEmpty())
    }

    @Test
    fun `a row that left the list is forgotten, and returning resolves it afresh`() {
        val memo = HomeRowTileMemo<String>()
        val counter = Counter()
        memo.resolve(1, listOf(input("a"), input("b")), counter::resolve)
        memo.resolve(1, listOf(input("a")), counter::resolve)
        counter.seen.clear()

        memo.resolve(1, listOf(input("a"), input("b")), counter::resolve)
        assertEquals("b had left, so the memo does not outgrow the shelf", listOf("b"), counter.seen)
    }

    @Test
    fun `the coordinator resolves each row through the memo, keyed on inputs and not on the whole row`() {
        val source = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/HomeShelfCoordinator.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/HomeShelfCoordinator.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("HomeShelfCoordinator.kt not found from ${File(".").absolutePath}")
        val block = source.substringAfter("val tiledRows = remember(").substringBefore("val tiledDisplayRows = tiledRows.rows")
        assertTrue("the rows are resolved through the memo, under the epoch", Regex("""tileMemo\.resolve\(\s*sgdbEpoch,""").containsMatchIn(block))
        assertTrue("keyed on the three inputs", block.contains("HomeRowTileInputs(row.itemId, row.hasOwnCoverArt(), row.artworkUri)"))
        assertFalse("the resolver is called inside the memo's compute, never per row directly", block.contains("resolveShelfFaces("))
    }
}

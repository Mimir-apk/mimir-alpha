package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Landing prewarm: the dwell decodes exactly what the settle gate will await,
 * only for a platform the shelf rests on, and the idle pass stays inside its budget. The decode timings and the
 * no-hold result are device-owed (perf build, Nova and RG: the MimirSettle line's waited ms before and after).
 */
class LibraryShelfPrewarmTest {

    private val snes = PlatformId("snes")

    @Test
    fun `the dwell window is the settle gate's window`() {
        val rows = (0 until 12).map { row(it) }
        listOf(null, GameId("g0"), GameId("g1"), GameId("g6"), GameId("g11"), GameId("missing")).forEach { landing ->
            assertEquals(
                "landing $landing",
                libraryGamelistSettleWindow(rows, landing),
                libraryPrewarmWindow(rows, landing, LibraryGamelistSettleReach),
            )
        }
        // the settle window grew to every card the carousel paints (three either side), and the dwell
        // prewarm follows it, so the gate still finds its covers warm.
        assertEquals(7, LibraryDwellPrewarmCovers)
    }

    @Test
    fun `the dwell uris are the gate's uris, in the opened gamelist's order and lane`() {
        val rows = (0 until 12).map { row(it, favorite = it % 2 == 0) }
        val all = libraryPrewarmCoverUris(rows, snes, favoritesOnly = false, landing = GameId("g6"))
        assertEquals(listOf(3, 4, 5, 6, 7, 8, 9).map(::uri), all)
        val favourites = libraryPrewarmCoverUris(rows, snes, favoritesOnly = true, landing = GameId("g6"))
        assertEquals("the favourites lane windows its own rows", listOf(0, 2, 4, 6, 8, 10).map(::uri), favourites)
        val gate = libraryGamelistSettleWindow(rows, GameId("g6")).mapNotNull { libraryListPreviewMedia(it).artUri }
        assertEquals("same mapping as awaitLibraryGamelistSettled", gate, all)
    }

    /**
     * The shelf's face never answers with the provider's support disc; the list's ladder does when a game has no cover. What a view
     * will paint first is what is warmed for it: a disc-only game warms nothing for the shelf and its disc for the list.
     */
    @Test
    fun `the dwell warms the cover of the view the gamelist opens in`() {
        val discOnly = (0 until 6).map { index ->
            row(index, art = false).copy(physicalMediaUri = "file:///art/g$index-support.png")
        }
        assertEquals(
            "the shelf paints the book for a disc-only game, so there is nothing to warm",
            emptyList<String>(),
            libraryPrewarmCoverUris(discOnly, snes, favoritesOnly = false, landing = GameId("g3")),
        )
        assertEquals(
            "the list paints its panel's one cover, the landing game's disc",
            listOf("file:///art/g3-support.png"),
            libraryPrewarmCoverUris(discOnly, snes, favoritesOnly = false, landing = GameId("g3"), listView = true),
        )
        val covered = (0 until 12).map { row(it) }
        assertEquals(listOf(uri(6)), libraryPrewarmCoverUris(covered, snes, favoritesOnly = false, landing = GameId("g6"), listView = true))
    }

    @Test
    fun `the settle gate reads the shelf's face for the carousel and the list's ladder for the list`() {
        val discOnly = row(0, art = false).copy(physicalMediaUri = "file:///art/disc-support.png")
        assertNull(libraryGamelistSettleCoverUri(discOnly, listView = false))
        assertEquals("file:///art/disc-support.png", libraryGamelistSettleCoverUri(discOnly, listView = true))
        val covered = row(2)
        assertEquals(uri(2), libraryGamelistSettleCoverUri(covered, listView = false))
        assertEquals(uri(2), libraryGamelistSettleCoverUri(covered, listView = true))
        assertTrue("Favorites' Games list is always the list", libraryGamelistOpensAsList(favorites = true, mode = GamelistViewMode.CAROUSEL))
        assertTrue(libraryGamelistOpensAsList(favorites = false, mode = GamelistViewMode.LIST))
        assertFalse(libraryGamelistOpensAsList(favorites = false, mode = GamelistViewMode.CAROUSEL))
    }

    @Test
    fun `uncached rows and rows without art prewarm nothing`() {
        assertEquals(emptyList<String>(), libraryPrewarmCoverUris(null, snes, false, null))
        assertEquals(emptyList<String>(), libraryPrewarmCoverUris(emptyList(), snes, false, null))
        val bare = (0 until 3).map { row(it, art = false) }
        assertEquals(emptyList<String>(), libraryPrewarmCoverUris(bare, snes, false, null))
    }

    @Test
    fun `only a platform tile on the shelf, with no gamelist open, is a dwell`() {
        assertEquals(snes, libraryShelfDwellPlatform(true, "library/shelf/snes", null))
        assertNull("the add pill", libraryShelfDwellPlatform(true, "library/shelf/add", null))
        assertNull("a native row", libraryShelfDwellPlatform(true, "library/native/com.example", null))
        assertNull("off the shelf scope", libraryShelfDwellPlatform(false, "library/shelf/snes", null))
        assertNull("a gamelist is open", libraryShelfDwellPlatform(true, "library/shelf/snes", snes))
        assertNull(libraryShelfDwellPlatform(true, null, null))
    }

    @Test
    fun `the idle order is nearest first, wraps like the ring and never repeats the centre`() {
        val p = (0 until 6).map { PlatformId("p$it") }
        assertEquals(listOf("p3", "p1", "p4", "p0"), libraryIdleWarmPlatformOrder(p, p[2]).map { it.value })
        assertEquals("wraps", listOf("p1", "p5", "p2", "p4"), libraryIdleWarmPlatformOrder(p, p[0]).map { it.value })
        assertEquals("no centre: the landing platform's neighbours", libraryIdleWarmPlatformOrder(p, p[0]), libraryIdleWarmPlatformOrder(p, null))
        assertEquals(listOf("p1"), libraryIdleWarmPlatformOrder(p.take(2), p[0]).map { it.value })
        assertEquals(emptyList<PlatformId>(), libraryIdleWarmPlatformOrder(p.take(1), p[0]))
    }

    @Test
    fun `the idle pass stops at its cap and at half the memory cache`() {
        assertTrue(libraryIdleWarmHasBudget(0, 10, 100))
        assertFalse("cap", libraryIdleWarmHasBudget(LibraryIdleWarmCoverCap, 0, 100))
        assertFalse("half full", libraryIdleWarmHasBudget(0, 50, 100))
        assertTrue("no memory cache: the cap alone", libraryIdleWarmHasBudget(3, null, null))
    }

    private fun uri(index: Int) = "file:///art/g$index.png"

    private fun row(index: Int, favorite: Boolean = false, art: Boolean = true) = GameRow(
        gameId = GameId("g$index"),
        title = "Game $index",
        platformId = snes,
        memberCount = 1,
        duplicateKind = null,
        isFavorite = favorite,
        artUri = if (art) uri(index) else null,
    )
}

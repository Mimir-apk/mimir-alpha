package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Walk-32i: the pure half of the LIST view's departure. What the list draws while a game leaves (the new rows and the row that
 * left, at the place it stood), which row sits beside the gap, how present the leaver is, and the wiring that keeps the rest of
 * the list from noticing. The motion itself is [GamelistListDepartureMotionTest].
 */
class GamelistListDepartureTest {

    private val games = List(5) { index ->
        GameRow(
            gameId = GameId("g$index"),
            title = "Game $index",
            platformId = PlatformId.GBA,
            memberCount = 1,
            duplicateKind = null,
        )
    }

    private fun departureOf(index: Int, kind: GamelistDepartureKind = GamelistDepartureKind.Dissolve) =
        GamelistDeparture(games[index], index, kind = kind)

    private fun source(name: String): String {
        val candidates = listOf(
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText() ?: error("$name not found from ${File(".").absolutePath}")
    }

    // --------------------------------------------------------------------------------- what the list draws

    @Test
    fun `with nothing leaving the list draws its own rows, untouched`() {
        val shown = gamelistListShown(games, departure = null)

        assertSame(games, shown.games)
        assertNull(shown.ghostIndex)
    }

    @Test
    fun `a leaver is drawn again at the place it stood, so the list is the list before the removal`() {
        val after = games - games[2]

        val shown = gamelistListShown(after, departureOf(2))

        assertEquals(games.map { it.gameId }, shown.games.map { it.gameId })
        assertEquals(2, shown.ghostIndex)
    }

    @Test
    fun `the last row leaving is drawn after the last that stays`() {
        val after = games - games[4]

        val shown = gamelistListShown(after, departureOf(4))

        assertEquals(games.map { it.gameId }, shown.games.map { it.gameId })
        assertEquals(4, shown.ghostIndex)
    }

    @Test
    fun `a game put back while it dissolves is listed once`() {
        val shown = gamelistListShown(games, departureOf(2))

        assertSame(games, shown.games)
        assertNull(shown.ghostIndex)
    }

    // --------------------------------------------------------------------------------------- the gap's neighbour

    @Test
    fun `the row beside the gap is the one that was after the leaver`() {
        val neighbour = gamelistGapNeighbour(games - games[1], departureOf(1))

        assertEquals(games[2].gameId, neighbour?.gameId)
        assertTrue("it is below the ghost", neighbour!!.below)
    }

    @Test
    fun `when the last row leaves the row beside the gap is the one before it`() {
        val neighbour = gamelistGapNeighbour(games - games[4], departureOf(4))

        assertEquals(games[3].gameId, neighbour?.gameId)
        assertFalse("it is above the ghost", neighbour!!.below)
    }

    @Test
    fun `nothing leaving means no row is beside a gap`() {
        assertNull(gamelistGapNeighbour(games, null))
    }

    // ------------------------------------------------------------------------------------------ how present it is

    @Test
    fun `a dissolving row is as present as the departure has left to run`() {
        val departure = departureOf(1)
        assertEquals(1f, gamelistLeavingPresence(departure), 0f)

        runBlocking { departure.progress.snapTo(0.25f) }
        assertEquals(0.75f, gamelistLeavingPresence(departure), 0.0001f)

        runBlocking { departure.progress.snapTo(1f) }
        assertEquals(0f, gamelistLeavingPresence(departure), 0f)
    }

    @Test
    fun `a shattered row paints nothing at all, from the first frame`() {
        val departure = departureOf(1, GamelistDepartureKind.Shatter)

        assertEquals("the shards are drawn from a picture of it; it must not show through", 0f, gamelistLeavingPresence(departure), 0f)
        runBlocking { departure.progress.snapTo(0.5f) }
        assertEquals(0f, gamelistLeavingPresence(departure), 0f)
    }

    // ------------------------------------------------------------------------------------- the wiring, characterised

    @Test
    fun `the dissolve is a per-draw alpha, never a buffer at the row's bounds, so the ring's bloom is not cut`() {
        val file = source("GamelistListDeparture.kt")

        assertTrue(file.contains("compositingStrategy = CompositingStrategy.ModulateAlpha"))
        assertFalse("a layer alpha of its own would composite the row into its own bounds", file.contains("CompositingStrategy.Offscreen"))
    }

    @Test
    fun `the list is remembered and revealed on the departure's lineage, and the ghost row is not interactive`() {
        val screen = source("LibraryScreen.kt")
        val view = screen.substringAfter("internal fun GamelistView(").substringBefore("internal fun ScrollFocusedLazyItemIntoView(")

        assertTrue(view.contains("val listIdentity: Any = departureFrame?.lineage ?: gameFocusKeys"))
        assertTrue(view.contains("val listState = key(listIdentity) {"))
        assertTrue(view.contains("contentIdentity = listIdentity,"))
        assertTrue("the reveal waits while a row is leaving", view.contains("focusedIndex = if (shown.ghostIndex != null) -1 else scrollTargetIndex,"))
        assertTrue(view.contains("interactive = !leavingRow,"))
        assertTrue(
            "the LazyColumn spaces its rows by the very space the gap gives up",
            view.contains("Arrangement.spacedBy(GamelistListRowSpacing)"),
        )
    }

    @Test
    fun `the screen seats a departure in the lists of games, and not in the native lists`() {
        val screen = source("LibraryScreen.kt")
        val gate = screen.substringAfter("val departureMotionEnabled = ").substringBefore("val nativeFavoritesOpen")

        assertTrue(gate.contains("!mimirReducedMotion()"))
        assertTrue(gate.contains("primary.favoriteSegment == FavoriteSegment.GAMES"))
        assertFalse("the carousel-only gate is gone", gate.contains("GamelistViewMode.CAROUSEL"))
        val listCall = screen.substringAfter("GamelistViewMode.LIST -> GamelistView(").substringBefore("modifier = Modifier.fillMaxSize(),")
        assertTrue("a platform's LIST view takes the frame", listCall.contains("departureFrame = departureFrame,"))
        val favouritesCall = screen.substringAfter("FavoritesSegmentedView(").substringBefore("modifier = Modifier.weight(1f),")
        assertTrue("the Favorites screen hands it on", favouritesCall.contains("departureFrame = departureFrame,"))
        val favouritesGames = screen.substringAfter("FavoriteSegment.GAMES -> GamelistView(").substringBefore("FavoriteSegment.ANDROID_GAMES")
        assertTrue("and its list of games takes it", favouritesGames.contains("departureFrame = departureFrame,"))
    }
}

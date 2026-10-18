package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit 2026-10-14, RC-45: "the Library does O(n) work per focus step over the full game list (5 to 6 passes, about 30,000 allocations a step at 5,500
 * games)". A focus step changes nothing about the list, so the keys of the list, the seat of a key and the game a key names are asked of one index built
 * once per list ([GamelistKeyIndex]) and a step allocates the lookup it asks with and nothing for the list.
 *
 * The step is measured the way it is paid: the bytes this thread allocates across the work the screen and the dispatch do for ONE step over 5,000 games.
 * The LEGACY step below is the work as it was (a `map(::gameKey)` and a walk at every site) and is measured the same way, so the harness is shown to see the
 * cost it is there to catch: it must read hundreds of kilobytes where the product reads a few.
 */
class GamelistKeyIndexStepCostTest {

    private val gameCount = 5_000

    private fun game(n: Int) = GameRow(
        gameId = GameId("game-$n"),
        title = "Game $n",
        platformId = PlatformId.PS1,
        memberCount = 1,
        duplicateKind = null,
        isFavorite = false,
    )

    private val games: List<GameRow> = List(gameCount) { game(it) }

    // ---------------------------------------------------------------------------------------- the answers are the walks'

    /** The centre from the captured cases, unchanged from before the index. */
    private fun legacyCentredIndex(games: List<GameRow>, centeredKey: FocusKey?, getArtFocused: Boolean, detailGame: GameRow?): Int {
        val centred = when {
            getArtFocused -> detailGame?.let { retained -> games.firstOrNull { it.gameId == retained.gameId } }
            else -> centeredKey?.let { key -> games.firstOrNull { gameKey(it) == key } }
        }
        return centred?.let { card -> games.indexOfFirst { it.gameId == card.gameId } }?.takeIf { it >= 0 } ?: 0
    }

    @Test
    fun `the index seats a key where the walk did, for a game, a stranger, nothing and the media pill`() {
        val small = List(40) { game(it) }
        val index = GamelistKeyIndex.of(small)
        val keys: List<FocusKey?> = small.map(::gameKey) + listOf(FocusKey("library/gamelist/gone"), GamelistGetArtKey, null)
        for (key in keys) {
            for (detail in listOf(null, small[7], game(999))) {
                for (getArt in listOf(false, true)) {
                    assertEquals(
                        "key=$key getArt=$getArt detail=${detail?.gameId}",
                        legacyCentredIndex(small, key, getArt, detail),
                        gamelistCentredIndex(index, key, getArt, detail),
                    )
                }
            }
        }
        assertEquals("the list form is the same answer", 12, gamelistCentredIndex(small, gameKey(small[12]), getArtFocused = false, detailGame = null))
    }

    @Test
    fun `the index answers a key's seat, its game and its membership`() {
        val index = GamelistKeyIndex.of(games)
        assertEquals(gameCount, index.keys.size)
        assertEquals(games.map(::gameKey), index.keys)
        assertEquals(1234, index.indexOf(gameKey(games[1234])))
        assertEquals(1234, index.indexOf(games[1234].gameId))
        assertEquals(-1, index.indexOf(FocusKey("library/gamelist/stranger")))
        assertSame(games[77], index.gameFor(gameKey(games[77])))
        assertNull(index.gameFor(GamelistGetArtKey))
        assertTrue(gameKey(games[4999]) in index)
        assertFalse(GamelistGetArtKey in index)
        assertEquals("the key set keeps the list's order", gameKey(games[0]), index.keySet.first())
        assertEquals(gameCount, index.keySet.size)
    }

    @Test
    fun `one list is one index, found again by the very list and not by an equal one`() {
        val index = GamelistKeyIndex.of(games)
        assertSame(index, GamelistKeyIndex.of(games))
        val equalCopy = games.toList()
        assertTrue("the copy is equal", equalCopy == games)
        assertTrue("and is another list, so another index", GamelistKeyIndex.of(equalCopy) !== index)
    }

    // -------------------------------------------------------------------------------------------------------- the cost

    // java.lang.management is not on the Android stub classpath this module compiles against, but it is on the JVM the test runs on: reached by reflection.
    private val threadBean: Any by lazy {
        Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)!!
    }

    private fun beanMethod(name: String, vararg types: Class<*>) = Class.forName("com.sun.management.ThreadMXBean").getMethod(name, *types)

    private fun allocatedBytes(): Long =
        beanMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType!!).invoke(threadBean, Thread.currentThread().id) as Long

    /**
     * What one focus step asks of the list in the product: the centre (the departure tracker's seat and the shelf's selection), the tracker itself, the
     * painted focus, the game under the backdrop and under the persisted focus, and the dispatch's step along the list.
     */
    private fun productStep(index: GamelistKeyIndex, tracker: GamelistDepartureTracker, step: Int): Int {
        val key = gameKey(games[step % gameCount])
        val centred = gamelistCentredIndex(index, key, getArtFocused = false, detailGame = null)
        tracker.observe(
            games = games,
            centredIndex = centred,
            scope = "ps1",
            leaving = { false },
            motionEnabled = true,
            keys = index.keys,
        )
        tracker.commit()
        gamelistRenderedFocus(
            gameKeys = index.keySet,
            focused = key,
            scopeOwnsGamelist = true,
            rememberedFocus = null,
            pendingRememberedFocus = null,
            retainedGameFocus = null,
        )
        val backdrop = index.gameFor(key)
        val remembered = index.gameFor(key)
        val dispatchSeat = GamelistKeyIndex.of(games).indexOf(key)
        return centred + dispatchSeat + (if (backdrop === remembered) 1 else 0)
    }

    /** The same step as the walks did it. */
    private fun legacyStep(tracker: GamelistDepartureTracker, step: Int): Int {
        val key = gameKey(games[step % gameCount])
        val centred = legacyCentredIndex(games, key, false, null)
        // The tracker built its own key list for every seat.
        val shownKeys = games.map(::gameKey)
        // The painted focus: the keys again, and a membership walk per check.
        val paintedKeys = games.map(::gameKey)
        val painted = gamelistRenderedFocus(
            gameKeys = paintedKeys,
            focused = key,
            scopeOwnsGamelist = true,
            rememberedFocus = null,
            pendingRememberedFocus = null,
            retainedGameFocus = null,
        )
        // The game under the backdrop, under the persisted focus, and the dispatch's list and seat.
        val backdrop = games.firstOrNull { gameKey(it) == key }
        val remembered = games.firstOrNull { gameKey(it) == key }
        val dispatchKeys = games.map(::gameKey)
        val dispatchSeat = dispatchKeys.indexOf(key)
        return centred + shownKeys.size + dispatchSeat + (if (backdrop === remembered && painted != null) 1 else 0)
    }

    private fun averageBytes(steps: Int, work: (Int) -> Int): Long {
        var sink = 0
        // The JIT and the class loading are not the step's: warm up first.
        repeat(40) { sink += work(it) }
        val before = allocatedBytes()
        repeat(steps) { sink += work(it + 40) }
        val after = allocatedBytes()
        assertNotNull(sink)
        return (after - before) / steps
    }

    @Test
    fun `a focus step over 5000 games allocates a few kilobytes, where the walks allocated hundreds`() {
        assertTrue("this JVM measures per-thread allocation", beanMethod("isThreadAllocatedMemorySupported").invoke(threadBean) as Boolean)
        if (!(beanMethod("isThreadAllocatedMemoryEnabled").invoke(threadBean) as Boolean)) {
            beanMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType!!).invoke(threadBean, true)
        }

        val index = GamelistKeyIndex.of(games)
        val tracker = GamelistDepartureTracker()
        val productBytes = averageBytes(steps = 100) { productStep(index, tracker, it) }
        val legacyTracker = GamelistDepartureTracker()
        val legacyBytes = averageBytes(steps = 20) { legacyStep(legacyTracker, it) }

        println("GamelistKeyIndexStepCost: product $productBytes B/step, legacy $legacyBytes B/step over $gameCount games")
        assertTrue("the product's step allocates no per-game garbage: $productBytes B", productBytes < 16 * 1024)
        assertTrue("the harness sees the walks' cost (it must read far above the product's): $legacyBytes B", legacyBytes > 400 * 1024)
        assertTrue("and the gap is orders, not noise: $legacyBytes vs $productBytes", legacyBytes > 40 * productBytes)
    }

    @Test
    fun `a held step along the list takes its next seat from the index and builds nothing for the list`() {
        val index = GamelistKeyIndex.of(games)
        val bytes = averageBytes(steps = 200) { step ->
            val current = GamelistKeyIndex.of(games).indexOf(gameKey(games[step % (gameCount - 1)]))
            val next = (current + 1).coerceIn(0, index.keys.lastIndex)
            index.keys[next].value.length
        }
        println("GamelistKeyIndexStepCost: dispatch step $bytes B")
        assertTrue("a dispatch step is a lookup: $bytes B", bytes < 2 * 1024)
    }

    // ------------------------------------------------------------------------------------------------ the sites say it

    private fun codeOf(name: String): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
    ).first(File::isFile).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    @Test
    fun `no focus-step site walks the list to find a key or builds the keys again`() {
        for (name in listOf("LibraryScreen.kt", "LibraryGameShelf.kt", "LibraryDispatch.kt")) {
            val code = codeOf(name)
            assertFalse("$name builds the keys of a list with map(::gameKey)", code.contains("map(::gameKey)"))
            assertFalse("$name walks a list comparing keys", Regex("""firstOrNull\s*\{\s*gameKey\(it\)\s*==""").containsMatchIn(code))
            assertFalse("$name indexes a list by key with associateBy", code.contains("associateBy(::gameKey)"))
        }
        // The tracker may make a list's keys only when two different lists are compared, behind a lazy.
        val tracker = codeOf("GamelistDeparture.kt")
        assertEquals(1, Regex("""map\(::gameKey\)""").findAll(tracker).count())
        assertTrue(tracker.contains("val keys: List<FocusKey> by lazy(LazyThreadSafetyMode.NONE) { given ?: games.map(::gameKey) }"))
        assertTrue("a focus step shows the same list and compares nothing", tracker.contains("before.games === now.games || before.keys == now.keys"))
    }

    @Test
    fun `the screen hands its one key index to every consumer of the list`() {
        val screen = codeOf("LibraryScreen.kt")
        assertTrue(screen.contains("val gameKeys = rememberGamelistKeyIndex(games)"))
        assertTrue(screen.contains("gameKeys = modelState.gameKeys,"))
        assertEquals("the carousel and the list are both given the index", 2, Regex("""gameKeys = primary\.gameKeys,""").findAll(screen).count())
        assertTrue(screen.contains("index = primary.gameKeys,"))
        assertTrue(screen.contains("keys = primary.gameKeys.keys,"))
        assertTrue(screen.contains("backdropFocus?.let(primary.gameKeys::gameFor)"))
        assertTrue("the persisted focus is one lookup", screen.contains("focusState.focused?.let(gameKeys::gameFor)?.let(viewModel::rememberGamelistFocus)"))
    }
}

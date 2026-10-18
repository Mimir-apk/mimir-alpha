package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit 2026-10-14, RC-18 (L-10): "double rings on gamelist entry". A platform's first keys register header first, so for a beat the authority's focus is on the Search
 * pill while the book is already painted as the landing ([gamelistRenderedFocus]): the pill and the book both wore a ring. The pill now wears none while a landing is
 * armed ([gamelistSearchRingLit]), so the book is the ONE ring from the first frame and the pill takes it back when the landing is done.
 *
 * The entry is driven through the screen's own pure functions, frame by frame, the way its landing effect drives them: paint the focus, count the rings, run the effect.
 * The OLD rule (the pill lit by the authority alone) is counted on the same frames, to show this test sees the double ring it is there to catch.
 */
class GamelistEntrySingleRingTest {

    private val platform = PlatformId.PS1
    private val keys = List(6) { gameKey(game(it)) }
    private val book = keys.first()
    private val remembered = keys[3]

    private fun game(n: Int) = com.gruning.mimir.rewrite.core.model.library.GameRow(
        gameId = GameId("game-$n"),
        title = "Game $n",
        platformId = platform,
        memberCount = 1,
        duplicateKind = null,
        isFavorite = false,
    )

    /** One frame of the entry: what the screen paints, then what its landing effect does. */
    private data class Frame(val bookRings: Int, val pillRingsNow: Int, val pillRingsOld: Int, val focused: FocusKey?, val pendingAfter: Boolean)

    private inner class Entry(
        var pending: PendingGamelistLanding?,
        var focused: FocusKey?,
        var rows: List<FocusKey>,
        var loadedFor: PlatformId?,
        private val rememberedFocus: FocusKey?,
    ) {
        fun frame(): Frame {
            val armed = libraryGamelistLandingArmed(
                landingPending = pending != null && pending?.platformId == platform,
                gamesLoaded = loadedFor == platform,
                gamesEmpty = rows.isEmpty(),
            )
            val painted = gamelistRenderedFocus(
                gameKeys = rows,
                focused = focused,
                scopeOwnsGamelist = true,
                rememberedFocus = rememberedFocus,
                pendingRememberedFocus = pending?.rememberedFocus,
                retainedGameFocus = null,
                platformLandingPending = pending != null,
            )
            // The book wears the ring when the painted focus is one of its rows: during an armed landing because it is the painted landing, and after it because it is focused.
            val bookRing = painted != null && painted in rows && (armed || focused in rows)
            val authorityOnPill = focused == LibrarySearchLauncherKey
            val frame = Frame(
                bookRings = if (bookRing) 1 else 0,
                pillRingsNow = if (gamelistSearchRingLit(authorityOnPill, armed)) 1 else 0,
                pillRingsOld = if (authorityOnPill) 1 else 0,
                focused = focused,
                pendingAfter = false,
            )
            val resolution = resolvePendingGamelistLanding(pending, platform, loadedFor, rows, focused)
            pending = resolution.pending
            resolution.target?.let { focused = it }
            return frame.copy(pendingAfter = pending != null)
        }
    }

    private val Frame.ringsNow get() = bookRings + pillRingsNow
    private val Frame.ringsOld get() = bookRings + pillRingsOld

    @Test
    fun `entering a loaded platform rings the book once from the first frame, where the authority-only rule rang the pill too`() {
        val entry = Entry(PendingGamelistLanding(platform, remembered), focused = LibrarySearchLauncherKey, rows = keys, loadedFor = platform, rememberedFocus = remembered)
        val frames = List(4) { entry.frame() }
        println("GamelistEntrySingleRing loaded: now ${frames.map { it.ringsNow }}, old ${frames.map { it.ringsOld }}")
        assertTrue("never more than one ring: ${frames.map { it.ringsNow }}", frames.all { it.ringsNow <= 1 })
        assertEquals("the book is the ring on the very first frame", 1, frames.first().ringsNow)
        assertEquals(1, frames.first().bookRings)
        assertEquals("and the pill is dark on it", 0, frames.first().pillRingsNow)
        assertTrue("the harness sees the old double ring: ${frames.map { it.ringsOld }}", frames.any { it.ringsOld == 2 })
        assertEquals("the landing resolved onto the remembered book", remembered, entry.focused)
        assertTrue("one ring all the way through", frames.all { it.ringsNow == 1 })
    }

    @Test
    fun `entering while the rows are still on their way shows no pill ring and no double either`() {
        val entry = Entry(PendingGamelistLanding(platform, null), focused = LibrarySearchLauncherKey, rows = emptyList(), loadedFor = null, rememberedFocus = null)
        val waiting = List(3) { entry.frame() }
        assertTrue("no ring on the pill while a landing waits for rows: ${waiting.map { it.ringsNow }}", waiting.all { it.pillRingsNow == 0 })
        assertTrue(waiting.all { it.ringsNow <= 1 })
        assertTrue("the old rule rang the pill for these frames", waiting.all { it.pillRingsOld == 1 })
        // The rows arrive: the book is painted as the landing at once and the pill stays dark until the landing is done.
        entry.rows = keys
        entry.loadedFor = platform
        val landing = List(3) { entry.frame() }
        println("GamelistEntrySingleRing arriving: now ${landing.map { it.ringsNow }}, old ${landing.map { it.ringsOld }}")
        assertTrue(landing.all { it.ringsNow == 1 })
        assertTrue("old rule: pill and book together on the frame the rows arrived", landing.first().ringsOld == 2)
        assertEquals(book, entry.focused)
    }

    @Test
    fun `a platform that loaded empty keeps the pill ring, the only thing left to focus`() {
        val entry = Entry(PendingGamelistLanding(platform, null), focused = LibrarySearchLauncherKey, rows = emptyList(), loadedFor = platform, rememberedFocus = null)
        val frames = List(5) { entry.frame() }
        assertTrue("the landing never resolves with no rows: it stays armed in the model", frames.all { it.pendingAfter })
        assertTrue("and the pill wears its ring on every frame: ${frames.map { it.ringsNow }}", frames.all { it.pillRingsNow == 1 && it.bookRings == 0 })
    }

    @Test
    fun `after the landing the pill rings again whenever the authority is on it, and the book does not ring then`() {
        val entry = Entry(PendingGamelistLanding(platform, remembered), focused = LibrarySearchLauncherKey, rows = keys, loadedFor = platform, rememberedFocus = remembered)
        repeat(3) { entry.frame() }
        assertNull("the landing is consumed", entry.pending)
        // The player steps up to the pill.
        entry.focused = LibrarySearchLauncherKey
        val onPill = entry.frame()
        assertEquals("the pill takes its ring back", 1, onPill.pillRingsNow)
        assertEquals("and the book does not ring beside it", 0, onPill.bookRings)
        assertEquals(1, onPill.ringsNow)
    }

    @Test
    fun `the landing is armed only while it has a page to land on`() {
        assertTrue(libraryGamelistLandingArmed(landingPending = true, gamesLoaded = false, gamesEmpty = true))
        assertTrue(libraryGamelistLandingArmed(landingPending = true, gamesLoaded = true, gamesEmpty = false))
        assertFalse("loaded and empty: nothing to land on", libraryGamelistLandingArmed(landingPending = true, gamesLoaded = true, gamesEmpty = true))
        assertFalse(libraryGamelistLandingArmed(landingPending = false, gamesLoaded = true, gamesEmpty = false))
        assertTrue(gamelistSearchRingLit(authorityLit = true, landingArmed = false))
        assertFalse(gamelistSearchRingLit(authorityLit = true, landingArmed = true))
        assertFalse(gamelistSearchRingLit(authorityLit = false, landingArmed = false))
        assertEquals(keys.first(), book)
    }

    @Test
    fun `the header lights the pill through the gate, from the model's armed flag`() {
        val code = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
        ).first(File::isFile).readText()
        assertTrue(code.contains("searchFocused = gamelistSearchRingLit("))
        assertTrue(code.contains("landingArmed = modelState.platformLandingPending,"))
        assertTrue(
            code.contains(
                "platformLandingPending = libraryGamelistLandingArmed(",
            ),
        )
    }
}

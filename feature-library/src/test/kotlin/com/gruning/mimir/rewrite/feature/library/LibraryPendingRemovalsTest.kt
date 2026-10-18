package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Walk 32g: the rules for a game the player has sent out of a list before the database has said so. Pure, so they are
 * checked here without a ViewModel; [LibraryHideDepartureTest] drives the same rules through one.
 */
class LibraryPendingRemovalsTest {
    private val ps1 = PlatformId.PS1
    private val snes = PlatformId.SNES
    private val favorites = LibraryViewModel.FAVORITES_PLATFORM_ID

    private fun row(id: String, favorite: Boolean = false, platform: PlatformId = ps1) = GameRow(
        gameId = GameId(id),
        title = id,
        platformId = platform,
        memberCount = 1,
        duplicateKind = null,
        isFavorite = favorite,
    )

    private fun hidden(scope: PlatformId?) = PendingLibraryRemoval(scope, LibraryRemovalKind.HIDDEN)

    private fun unstarred(scope: PlatformId?) = PendingLibraryRemoval(scope, LibraryRemovalKind.UNFAVORITED)

    // ------------------------------------------------------------------------------------------ what it holds back

    @Test
    fun `nothing pending leaves the very same list`() {
        val rows = listOf(row("a"), row("b"))

        assertSame(rows, PendingLibraryRemovals().applyTo(rows, ps1, favoritesOnly = false))
    }

    @Test
    fun `a pending game that is not in the list leaves the very same list`() {
        val rows = listOf(row("a"), row("b"))
        val pending = PendingLibraryRemovals().with(GameId("z"), hidden(ps1))

        assertSame(rows, pending.applyTo(rows, ps1, favoritesOnly = false))
    }

    @Test
    fun `a hidden game leaves the list it was hidden from`() {
        val pending = PendingLibraryRemovals().with(GameId("b"), hidden(ps1))

        assertEquals(
            listOf("a", "c"),
            pending.applyTo(listOf(row("a"), row("b"), row("c")), ps1, favoritesOnly = false).map { it.gameId.value },
        )
    }

    @Test
    fun `a hidden game leaves every list, not only the one it was hidden from`() {
        // Hidden is global: the database will not carry it anywhere, so a list the player moves to in the meantime must
        // not bring it back for the beat before the write lands.
        val pending = PendingLibraryRemovals().with(GameId("b"), hidden(favorites))

        assertEquals(
            listOf("a"),
            pending.applyTo(listOf(row("a"), row("b")), snes, favoritesOnly = false).map { it.gameId.value },
        )
    }

    @Test
    fun `an un-starred game leaves the Favorites list it was un-starred in`() {
        val pending = PendingLibraryRemovals().with(GameId("b"), unstarred(favorites))

        assertEquals(
            listOf("a"),
            pending.applyTo(listOf(row("a", true), row("b", true)), favorites, favoritesOnly = false)
                .map { it.gameId.value },
        )
    }

    @Test
    fun `an un-starred game leaves a platform's favourites-only view`() {
        val pending = PendingLibraryRemovals().with(GameId("b"), unstarred(ps1))

        assertEquals(
            listOf("a"),
            pending.applyTo(listOf(row("a", true), row("b", true)), ps1, favoritesOnly = true).map { it.gameId.value },
        )
    }

    @Test
    fun `an un-starred game stays in the platform's ordinary list, where it is only a game without a star`() {
        val pending = PendingLibraryRemovals().with(GameId("b"), unstarred(ps1))
        val rows = listOf(row("a", true), row("b", true))

        assertSame(rows, pending.applyTo(rows, ps1, favoritesOnly = false))
    }

    @Test
    fun `an un-starred game is held back from the view it was un-starred in and no other`() {
        val pending = PendingLibraryRemovals().with(GameId("b"), unstarred(favorites))
        val rows = listOf(row("a", true), row("b", true))

        assertSame(rows, pending.applyTo(rows, ps1, favoritesOnly = true))
    }

    // -------------------------------------------------------------------------------- when the database catches up

    @Test
    fun `a hidden game stays pending while the live list still carries it`() {
        val pending = PendingLibraryRemovals().with(GameId("b"), hidden(ps1))

        // An emission computed before the write still holds the game; it says nothing about the write.
        assertSame(pending, pending.reconciledWith(ps1, listOf(row("a"), row("b"))))
    }

    @Test
    fun `a hidden game is done when the live list of its scope no longer carries it`() {
        val pending = PendingLibraryRemovals().with(GameId("b"), hidden(ps1))

        assertTrue(pending.reconciledWith(ps1, listOf(row("a"))).isEmpty)
    }

    @Test
    fun `an emission for another list says nothing about the entry`() {
        val pending = PendingLibraryRemovals().with(GameId("b"), hidden(ps1))

        // SNES's list never held the game, so its emission lacking it is not the database agreeing.
        assertSame(pending, pending.reconciledWith(snes, listOf(row("x", platform = snes))))
    }

    @Test
    fun `an entry made with no list open is settled by any live emission`() {
        val pending = PendingLibraryRemovals().with(GameId("b"), hidden(null))

        assertTrue(pending.reconciledWith(ps1, listOf(row("a"))).isEmpty)
    }

    @Test
    fun `an un-starred game stays pending while the live list still has it starred`() {
        val pending = PendingLibraryRemovals().with(GameId("b"), unstarred(ps1))

        assertSame(pending, pending.reconciledWith(ps1, listOf(row("a", true), row("b", true))))
    }

    @Test
    fun `an un-starred game is done once the live list has it unstarred, or without it`() {
        val pending = PendingLibraryRemovals().with(GameId("b"), unstarred(ps1))

        assertTrue(pending.reconciledWith(ps1, listOf(row("a", true), row("b", false))).isEmpty)
        assertTrue(pending.reconciledWith(ps1, listOf(row("a", true))).isEmpty)
    }

    @Test
    fun `reconciling settles only the entries the emission answers`() {
        val pending = PendingLibraryRemovals()
            .with(GameId("b"), hidden(ps1))
            .with(GameId("c"), hidden(ps1))

        val after = pending.reconciledWith(ps1, listOf(row("a"), row("c")))

        assertEquals(setOf(GameId("c")), after.ids)
    }

    // ------------------------------------------------------------------------------------------------- the record

    @Test
    fun `a restore withdraws the entry, and withdrawing what is not there changes nothing`() {
        val pending = PendingLibraryRemovals().with(GameId("b"), hidden(ps1))

        assertFalse(GameId("b") in pending.without(GameId("b")))
        assertSame(pending, pending.without(GameId("z")))
        assertTrue(GameId("b") in pending)
    }
}

package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.LibraryRepository
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Hides leave the visible list optimistically and stay absent through repository acknowledgement. The real
 * [LibraryViewModel] and dispatch also preserve stars, hand focus to a survivor before writing, and await departure.
 * An unconfined main dispatcher lets these JVM tests run writes without an Android main thread.
 */
class LibraryHideDepartureTest {

    private class RecordingRepository(private val inner: FakeLibraryRepository = FakeLibraryRepository()) :
        LibraryRepository by inner {
        val log = CopyOnWriteArrayList<String>()

        /** While set, a write waits on it: a database that has not answered yet. */
        @Volatile var writeGate: CompletableDeferred<Unit>? = null

        /** Makes the next writes fail the way a cancelled transaction does, without an uncaught exception. */
        @Volatile var failWrites = false

        /** Runs as a write starts, before it waits. */
        @Volatile var onWrite: () -> Unit = {}

        override suspend fun setFavorite(gameId: GameId, favorite: Boolean) {
            log += "favorite:${gameId.value}=$favorite"
            onWrite()
            settle()
            inner.setFavorite(gameId, favorite)
        }

        /** A hide is logged as `hide:` and waits like any write; a restore is logged as it is called and does not wait. */
        override suspend fun setHidden(gameId: GameId, hidden: Boolean) {
            if (!hidden) {
                log += "setHidden:${gameId.value}=false"
                inner.setHidden(gameId, false)
                return
            }
            log += "hide:${gameId.value}"
            onWrite()
            settle()
            inner.setHidden(gameId, true)
        }

        private suspend fun settle() {
            writeGate?.await()
            if (failWrites) throw kotlinx.coroutines.CancellationException("the write did not land")
        }
    }

    private val crash = GameId("ps1/crash-bandicoot")
    private val ff7 = GameId("ps1/final-fantasy-vii")
    private val tekken = GameId("ps1/tekken-3")
    private val skies = GameId("dreamcast/skies-of-arcadia")

    private fun viewModel(repository: LibraryRepository) =
        LibraryViewModel(repository = repository, mainDispatcher = Dispatchers.Unconfined)

    /** Keeps [LibraryViewModel.games] populated (it only shares while someone collects it) and records every emission. */
    private fun kotlinx.coroutines.CoroutineScope.collecting(
        viewModel: LibraryViewModel,
        seen: MutableList<List<GameRow>> = CopyOnWriteArrayList(),
    ): Job = launch(Dispatchers.Unconfined) { viewModel.games.collect { seen += it } }

    private suspend fun waitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val started = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - started > timeoutMs) fail("condition was not met within ${timeoutMs}ms")
            delay(10)
        }
    }

    private fun LibraryViewModel.listed(id: GameId) = games.value.any { it.gameId == id }

    // ============================================================================ the list lets the game go now

    @Test
    fun `hiding a favourite never publishes the game without its star, and a restore brings it back starred`() = runBlocking {
        val repository = RecordingRepository()
        repository.setFavorite(tekken, true)
        val viewModel = viewModel(repository).apply { openPlatform(PlatformId.PS1) }
        val seen = CopyOnWriteArrayList<List<GameRow>>()
        val job = collecting(viewModel, seen)
        waitUntil { viewModel.games.value.any { it.gameId == tekken && it.isFavorite } }
        val target = viewModel.games.value.first { it.gameId == tekken }
        repository.log.clear()

        viewModel.hideGame(target)
        waitUntil { !viewModel.listed(tekken) }
        waitUntil { viewModel.pendingRemovals.value.isEmpty }

        // Every time the game was in the list it still wore its star: the star did not leave first.
        val starStates = seen.mapNotNull { rows -> rows.firstOrNull { it.gameId == tekken }?.isFavorite }
        assertTrue("the game was listed at least once", starStates.isNotEmpty())
        assertTrue("a star left before its game did: $starStates", starStates.all { it })
        assertEquals("one write hides it, and nothing writes the star", listOf("hide:${tekken.value}"), repository.log.toList())

        // Restored from Hidden games, the game comes back as it was.
        viewModel.setHidden(target, hidden = false)
        waitUntil { viewModel.listed(tekken) }
        assertTrue("restored with its star", viewModel.games.value.first { it.gameId == tekken }.isFavorite)
        job.cancel()
    }

    @Test
    fun `a hidden game leaves the list before the database has been asked, and its entry goes when the database agrees`() =
        runBlocking {
            val repository = RecordingRepository()
            val viewModel = viewModel(repository).apply { openPlatform(PlatformId.PS1) }
            val seen = CopyOnWriteArrayList<List<GameRow>>()
            val job = collecting(viewModel, seen)
            waitUntil { viewModel.listed(ff7) }
            val target = viewModel.games.value.first { it.gameId == ff7 }
            repository.writeGate = CompletableDeferred()

            viewModel.hideGame(target)

            waitUntil { !viewModel.listed(ff7) }
            waitUntil { seen.any { rows -> rows.isNotEmpty() && rows.none { it.gameId == ff7 } } }
            val departure = seen.indexOfFirst { rows -> rows.isNotEmpty() && rows.none { it.gameId == ff7 } }
            assertTrue("held back while the write is still out", ff7 in viewModel.pendingRemovals.value)
            assertEquals("the write has been asked and has not answered", listOf("hide:${ff7.value}"), repository.log.toList())

            repository.writeGate!!.complete(Unit)
            waitUntil { viewModel.pendingRemovals.value.isEmpty }
            waitUntil { !viewModel.listed(ff7) }
            viewModel.setSearchQuery("Crash")
            waitUntil { viewModel.games.value.map { it.gameId } == listOf(crash) }
            assertTrue(
                "no publication may resurrect the hidden game after its departure",
                seen.drop(departure).none { rows -> rows.any { it.gameId == ff7 } },
            )
            assertFalse(viewModel.listed(ff7))
            job.cancel()
        }

    @Test
    fun `replaying a live snapshot for search does not acknowledge a newer hide`() = runBlocking {
        val inner = FakeLibraryRepository()
        val repository = RecordingRepository(inner)
        val viewModel = viewModel(repository).apply { openPlatform(PlatformId.PS1) }
        val job = collecting(viewModel)
        waitUntil { viewModel.listed(ff7) }
        val retained = viewModel.games.value.first { it.gameId == ff7 }

        inner.setHidden(ff7, true)
        waitUntil { !viewModel.listed(ff7) }
        repository.writeGate = CompletableDeferred()
        viewModel.hideGame(retained)
        viewModel.setSearchQuery("Crash")
        waitUntil { viewModel.games.value.map { it.gameId } == listOf(crash) }

        assertTrue("search replay cannot acknowledge a later intent", ff7 in viewModel.pendingRemovals.value)

        inner.setFavorite(crash, true)
        waitUntil { viewModel.pendingRemovals.value.isEmpty && viewModel.games.value.single().isFavorite }
        assertTrue("a new repository snapshot reached the visible list", viewModel.games.value.single().isFavorite)
        repository.writeGate!!.complete(Unit)
        job.cancel()
    }

    @Test
    fun `a write that fails lets the game back into the list`() = runBlocking {
        val repository = RecordingRepository()
        val viewModel = viewModel(repository).apply { openPlatform(PlatformId.PS1) }
        val job = collecting(viewModel)
        waitUntil { viewModel.listed(tekken) }
        val target = viewModel.games.value.first { it.gameId == tekken }
        repository.failWrites = true

        viewModel.hideGame(target)

        waitUntil { viewModel.pendingRemovals.value.isEmpty }
        waitUntil { viewModel.listed(tekken) }
        job.cancel()
    }

    @Test
    fun `restoring a game withdraws the entry that was holding it back`() = runBlocking {
        val repository = RecordingRepository()
        val viewModel = viewModel(repository).apply { openPlatform(PlatformId.PS1) }
        val job = collecting(viewModel)
        waitUntil { viewModel.listed(tekken) }
        val target = viewModel.games.value.first { it.gameId == tekken }
        repository.writeGate = CompletableDeferred()
        viewModel.hideGame(target)
        waitUntil { !viewModel.listed(tekken) }

        viewModel.setHidden(target, hidden = false)

        assertFalse(tekken in viewModel.pendingRemovals.value)
        waitUntil { viewModel.listed(tekken) }
        assertTrue(repository.log.contains("setHidden:${tekken.value}=false"))
        job.cancel()
    }

    // ============================================================= the seat a future shatter waits in

    @Test
    fun `the departure slot is awaited before the game leaves the list and before the write`() = runBlocking {
        val repository = RecordingRepository()
        val viewModel = viewModel(repository).apply { openPlatform(PlatformId.PS1) }
        val job = collecting(viewModel)
        waitUntil { viewModel.listed(ff7) }
        val target = viewModel.games.value.first { it.gameId == ff7 }
        val order = CopyOnWriteArrayList<String>()
        val slot = CompletableDeferred<Unit>()
        viewModel.departureSlot = { game ->
            order += "slot listed=${viewModel.listed(game.gameId)} pending=${game.gameId in viewModel.pendingRemovals.value}"
            slot.await()
            order += "slot returned"
        }
        repository.onWrite = { order += "write pending=${ff7 in viewModel.pendingRemovals.value}" }

        viewModel.hideGame(target) { order += "handoff pending=${ff7 in viewModel.pendingRemovals.value}" }

        // The slot is playing: the game is still in the list, nothing is pending, nothing has been written.
        assertEquals(listOf("slot listed=true pending=false"), order.toList())
        assertTrue(viewModel.listed(ff7))
        assertTrue(repository.log.isEmpty())

        slot.complete(Unit)
        waitUntil { repository.log.isNotEmpty() }
        assertEquals(
            listOf(
                "slot listed=true pending=false",
                "slot returned",
                "handoff pending=true",
                "write pending=true",
            ),
            order.toList(),
        )
        viewModel.departureSlot = null
        job.cancel()
    }

    @Test
    fun `with no slot set the game is pending and handed off before the write, with nothing to wait for`() = runBlocking {
        val repository = RecordingRepository()
        val viewModel = viewModel(repository).apply { openPlatform(PlatformId.PS1) }
        val job = collecting(viewModel)
        waitUntil { viewModel.listed(ff7) }
        val target = viewModel.games.value.first { it.gameId == ff7 }
        assertNull("nothing sets a slot today", viewModel.departureSlot)
        repository.writeGate = CompletableDeferred()
        var handedOffWhilePending: Boolean? = null

        viewModel.hideGame(target) { handedOffWhilePending = ff7 in viewModel.pendingRemovals.value }

        // Everything up to the write ran before hideGame returned; only the write is out.
        assertEquals(true, handedOffWhilePending)
        assertEquals(listOf("hide:${ff7.value}"), repository.log.toList())
        job.cancel()
    }

    // =========================================================== un-starring inside a favourites view

    @Test
    fun `un-starring inside Favorites lets the game go now`() = runBlocking {
        val repository = RecordingRepository()
        repository.setFavorite(tekken, true)
        repository.setFavorite(crash, true)
        val viewModel = viewModel(repository).apply { openFavorites() }
        val job = collecting(viewModel)
        waitUntil { viewModel.listed(tekken) && viewModel.listed(crash) }
        val target = viewModel.games.value.first { it.gameId == tekken }
        repository.log.clear()
        repository.writeGate = CompletableDeferred()

        assertFalse(viewModel.toggleFavorite(target))

        waitUntil { !viewModel.listed(tekken) }
        assertTrue(viewModel.listed(crash))
        assertTrue(tekken in viewModel.pendingRemovals.value)

        repository.writeGate!!.complete(Unit)
        waitUntil { viewModel.pendingRemovals.value.isEmpty }
        job.cancel()
    }

    @Test
    fun `un-starring in a platform's ordinary list leaves the game where it is`() = runBlocking {
        val repository = RecordingRepository()
        repository.setFavorite(tekken, true)
        val viewModel = viewModel(repository).apply { openPlatform(PlatformId.PS1) }
        val job = collecting(viewModel)
        waitUntil { viewModel.games.value.any { it.gameId == tekken && it.isFavorite } }
        val target = viewModel.games.value.first { it.gameId == tekken }

        assertFalse(viewModel.toggleFavorite(target))

        assertTrue("an ordinary list keeps the game; it is only without a star", viewModel.pendingRemovals.value.isEmpty)
        waitUntil { viewModel.games.value.any { it.gameId == tekken && !it.isFavorite } }
        job.cancel()
    }

    @Test
    fun `un-starring in a platform's favourites-only view lets the game go now`() = runBlocking {
        val repository = RecordingRepository()
        repository.setFavorite(tekken, true)
        repository.setFavorite(crash, true)
        val viewModel = viewModel(repository).apply { openPlatform(PlatformId.PS1) }
        val job = collecting(viewModel)
        waitUntil { viewModel.openPlatformHasFavorites.value }
        assertTrue(viewModel.toggleFavoritesOnly())
        waitUntil { viewModel.games.value.map { it.gameId }.toSet() == setOf(tekken, crash) }
        val target = viewModel.games.value.first { it.gameId == tekken }
        repository.writeGate = CompletableDeferred()

        assertFalse(viewModel.toggleFavorite(target))

        waitUntil { !viewModel.listed(tekken) }
        assertTrue(viewModel.listed(crash))
        repository.writeGate!!.complete(Unit)
        waitUntil { viewModel.pendingRemovals.value.isEmpty }
        job.cancel()
    }

    @Test
    fun `starring a game again withdraws its un-star entry`() = runBlocking {
        val repository = RecordingRepository()
        repository.setFavorite(tekken, true)
        repository.setFavorite(crash, true)
        val viewModel = viewModel(repository).apply { openFavorites() }
        val job = collecting(viewModel)
        waitUntil { viewModel.listed(tekken) && viewModel.listed(crash) }
        val target = viewModel.games.value.first { it.gameId == tekken }
        repository.writeGate = CompletableDeferred()
        viewModel.toggleFavorite(target)
        waitUntil { !viewModel.listed(tekken) }

        viewModel.toggleFavorite(target.copy(isFavorite = false))

        assertFalse(tekken in viewModel.pendingRemovals.value)
        repository.writeGate!!.complete(Unit)
        job.cancel()
    }

    // ================================================================ the survivor hand-off, through dispatch

    private class Rig(
        val repository: RecordingRepository,
        val viewModel: LibraryViewModel,
        val authority: DefaultFocusAuthority,
        val seen: MutableList<List<GameRow>>,
        val job: Job,
    )

    /** A gamelist of [platform]'s games with focus on [focusedId]; the START press has opened the hide confirmation. */
    private suspend fun kotlinx.coroutines.CoroutineScope.rigWithHideRequested(
        platform: PlatformId,
        focusedId: GameId,
        favorites: Boolean = false,
        favoriteIds: List<GameId> = emptyList(),
        pillFocused: Boolean = false,
    ): Rig {
        val repository = RecordingRepository()
        favoriteIds.forEach { repository.setFavorite(it, true) }
        val viewModel = viewModel(repository)
        if (favorites) viewModel.openFavorites() else viewModel.openPlatform(platform)
        val seen = CopyOnWriteArrayList<List<GameRow>>()
        val job = collecting(viewModel, seen)
        waitUntil { viewModel.listed(focusedId) }
        val games = viewModel.games.value
        val authority = DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            games.forEachIndexed { index, game ->
                registerVirtual(gameKey(game), GamelistScope, FocusBounds(index * 100f, 0f, index * 100f + 80f, 100f))
            }
            requestFocus(gameKey(games.first { it.gameId == focusedId }))
            if (pillFocused) {
                // The artwork action holds focus and keeps [focusedId] as the metadata game it serves.
                registerVirtual(GamelistGetArtKey, GamelistScope, FocusBounds(0f, 200f, 80f, 260f))
                requestFocus(GamelistGetArtKey)
            }
        }
        viewModel.focusMetadata(games.first { it.gameId == focusedId })
        assertTrue(
            dispatchToLibrary(
                authority = authority,
                viewModel = viewModel,
                action = ControllerAction.SYSTEM_START,
                onExit = {},
                onRescanFolder = {},
            ),
        )
        assertEquals(HideConfirmScope, authority.state.value.scope)
        repository.log.clear()
        return Rig(repository, viewModel, authority, seen, job)
    }

    @Test
    fun `hiding a game hands focus to the next one before the write`() = runBlocking {
        val rig = rigWithHideRequested(PlatformId.PS1, ff7)
        val survivorKey = gameKey(rig.viewModel.games.value.first { it.gameId == tekken })
        var focusAtWrite: Any? = "never written"
        rig.repository.onWrite = { focusAtWrite = rig.authority.state.value.focused }
        // The database has not answered: without this the write lands at once, the list catches up on its own thread, and the removal
        // entry that this test reads is withdrawn between the call returning and the assertion (a race that failed under the full suite).
        rig.repository.writeGate = CompletableDeferred()

        confirmLibraryHide(rig.authority, rig.viewModel)

        assertEquals("the write found focus already on the survivor", survivorKey, focusAtWrite)
        assertEquals(GamelistScope, rig.authority.state.value.scope)
        assertEquals(survivorKey, rig.authority.state.value.focused)
        assertEquals(survivorKey, rig.authority.rememberedFocusIn(GamelistScope))
        assertEquals("the metadata seat follows too", tekken, rig.viewModel.detailGame.value?.gameId)
        assertTrue(ff7 in rig.viewModel.pendingRemovals.value)
        assertEquals(PlatformId.PS1, rig.viewModel.openPlatform.value)
        rig.repository.writeGate!!.complete(Unit)
        rig.job.cancel()
    }

    @Test
    fun `hiding the last game of the row hands focus back to the one before it`() = runBlocking {
        val rig = rigWithHideRequested(PlatformId.PS1, tekken)
        val survivorKey = gameKey(rig.viewModel.games.value.first { it.gameId == ff7 })

        confirmLibraryHide(rig.authority, rig.viewModel)

        assertEquals(survivorKey, rig.authority.state.value.focused)
        assertEquals(ff7, rig.viewModel.detailGame.value?.gameId)
        rig.job.cancel()
    }

    @Test
    fun `hiding a game right after another never hands focus to the one already leaving`() = runBlocking {
        val rig = rigWithHideRequested(PlatformId.PS1, ff7)
        rig.repository.writeGate = CompletableDeferred()
        confirmLibraryHide(rig.authority, rig.viewModel)
        // The second press lands before the list has caught up with the first: crash's next neighbour was ff7.
        rig.viewModel.requestHide(rig.viewModel.games.value.first { it.gameId == crash })
        rig.authority.pushScope(HideConfirmScope)

        confirmLibraryHide(rig.authority, rig.viewModel)

        assertEquals(
            "ff7 is on its way out, so the survivor of crash is tekken",
            gameKey(rig.viewModel.games.value.first { it.gameId == tekken }),
            rig.authority.state.value.focused,
        )
        rig.repository.writeGate!!.complete(Unit)
        rig.job.cancel()
    }

    @Test
    fun `hiding the only game a list has closes the gamelist, as un-starring the last favourite does`() = runBlocking {
        val rig = rigWithHideRequested(PlatformId.DREAMCAST, skies)

        confirmLibraryHide(rig.authority, rig.viewModel)

        assertNull("the platform has nothing left to show", rig.viewModel.openPlatform.value)
        assertEquals("the game is still hidden, in one write", listOf("hide:${skies.value}"), rig.repository.log.toList())
        rig.job.cancel()
    }

    @Test
    fun `hiding the last favourite falls back to a native segment that has rows`() = runBlocking {
        val rig = rigWithHideRequested(PlatformId.DREAMCAST, skies, favorites = true, favoriteIds = listOf(skies))
        val row = com.gruning.mimir.rewrite.feature.library.NativeShelfRow(
            packageName = "com.example.game",
            label = "game",
            launchActivity = null,
            isGame = true,
            isFavorite = true,
        )

        confirmLibraryHide(rig.authority, rig.viewModel, nativeFavoriteGameRows = listOf(row))

        assertEquals(FavoriteSegment.ANDROID_GAMES, rig.viewModel.favoriteSegment.value)
        assertEquals("Favorites stays open on its other segment", LibraryViewModel.FAVORITES_PLATFORM_ID, rig.viewModel.openPlatform.value)
        rig.job.cancel()
    }

    @Test
    fun `hiding the last favourite with nothing to fall back to closes Favorites`() = runBlocking {
        val rig = rigWithHideRequested(PlatformId.DREAMCAST, skies, favorites = true, favoriteIds = listOf(skies))

        confirmLibraryHide(rig.authority, rig.viewModel)

        assertNull(rig.viewModel.openPlatform.value)
        rig.job.cancel()
    }

    @Test
    fun `hiding the game the artwork pill serves hands focus to a card that stays`() = runBlocking {
        val rig = rigWithHideRequested(PlatformId.PS1, ff7, pillFocused = true)
        val survivorKey = gameKey(rig.viewModel.games.value.first { it.gameId == tekken })
        assertEquals(
            "the pill held the gamelist's focus when START was pressed",
            GamelistGetArtKey,
            rig.authority.rememberedFocusIn(GamelistScope),
        )

        confirmLibraryHide(rig.authority, rig.viewModel)

        assertEquals("ff7 is hidden, in one write", listOf("hide:${ff7.value}"), rig.repository.log.toList())
        assertEquals(survivorKey, rig.authority.state.value.focused)
        assertEquals(tekken, rig.viewModel.detailGame.value?.gameId)
        rig.job.cancel()
    }

    @Test
    fun `a touch confirm for a game the player is not on hides it and leaves focus where it is`() = runBlocking {
        // A touch confirm can name a game other than the focused one. Focus is on tekken; crash is the one that leaves, and
        // its next neighbour is ff7. Handing focus to ff7 would pull the player off the card they are on.
        val rig = rigWithHideRequested(PlatformId.PS1, tekken)
        val tekkenKey = gameKey(rig.viewModel.games.value.first { it.gameId == tekken })
        rig.viewModel.clearPendingHide()
        rig.viewModel.requestHide(rig.viewModel.games.value.first { it.gameId == crash })

        confirmLibraryHide(rig.authority, rig.viewModel)

        assertEquals("crash is hidden, in one write", listOf("hide:${crash.value}"), rig.repository.log.toList())
        assertEquals("focus stayed on the card the player is on", tekkenKey, rig.authority.state.value.focused)
        assertEquals(GamelistScope, rig.authority.state.value.scope)
        assertEquals(PlatformId.PS1, rig.viewModel.openPlatform.value)
        rig.job.cancel()
    }
}

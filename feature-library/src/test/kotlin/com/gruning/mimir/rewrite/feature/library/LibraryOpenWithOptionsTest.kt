package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.launch.LaunchResult
import com.gruning.mimir.rewrite.core.launch.OpenWithCandidate
import com.gruning.mimir.rewrite.core.launch.OpenWithOptions
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Observed on Thor: the Library launch card's EMULATOR fact fell to "Automatic" after choosing or resetting an emulator,
 * after dismissing the picker, and after every launch return, and stayed there: `openWith` was both "the picker is open" and "the
 * options are loaded", the commit and the dismissal cleared it, and the Library never loaded it again (Home does, after the write).
 *
 * The options are now what the card names its emulator from. The picker is the Open-with focus scope. The EMULATOR fact reads
 * [launchCardEmulatorLabel], which names the emulator the launch would start and falls back to the card's own "Automatic" only when
 * NO options are loaded, so the tests watch that label at every point it can be observed.
 */
class LibraryOpenWithOptionsTest {

    private val duck = candidate("duckstation", "DuckStation")
    private val epsxe = candidate("epsxe", "ePSXe")

    private fun candidate(id: String, label: String) = OpenWithCandidate(
        emulatorId = EmulatorId(id),
        label = label,
        packageName = "emu.$id",
        installed = true,
        isCurrentOverride = false,
        isAutoPick = id == "duckstation",
    )

    /** An override store the port reads back from, as the real controller does: the pick follows the override, else Auto (DuckStation). */
    private inner class StoringPort : LibraryLaunchPort {
        @Volatile var override: EmulatorId? = null
        @Volatile var lastReadFor: GameRow? = null
        @Volatile var gate: CompletableFuture<Unit>? = null

        /** Reads the store BEFORE waiting on [gate], so a load parked on it carries what was true when it began (a stale load). */
        @Volatile var snapshotBeforeGate = false

        override suspend fun openWithOptions(game: GameRow): OpenWithOptions {
            val early = if (snapshotBeforeGate) build() else null
            gate?.let { while (!it.isDone) kotlinx.coroutines.delay(5) }
            lastReadFor = game
            return early ?: build()
        }

        private fun build(): OpenWithOptions {
            val pick = override ?: duck.emulatorId
            return OpenWithOptions(
                hasOverride = override != null,
                candidates = listOf(duck, epsxe).map { it.copy(isLaunchPick = it.emulatorId == pick, isCurrentOverride = it.emulatorId == override) },
            )
        }

        override suspend fun setEmulatorOverride(game: GameRow, candidate: OpenWithCandidate) {
            override = candidate.emulatorId
        }

        override suspend fun clearEmulatorOverride(game: GameRow) {
            override = null
        }

        override suspend fun launch(game: GameRow, preferredDisc: Int, launchMemberEntryKey: EntryKey?): LaunchResult =
            LaunchResult(LaunchReadiness.Unsupported(game.platformId))
    }

    private fun label(viewModel: LibraryViewModel): String? = launchCardEmulatorLabel(viewModel.openWith.value)

    private fun awaitLabel(viewModel: LibraryViewModel, expected: String, seen: MutableList<String?> = mutableListOf()) {
        val started = System.currentTimeMillis()
        while (true) {
            val now = label(viewModel)
            seen += now
            if (now == expected) return
            if (System.currentTimeMillis() - started > 5_000L) fail("the label never became $expected; saw ${seen.distinct()}")
            Thread.sleep(2)
        }
    }

    private suspend fun cardFor(port: StoringPort): Pair<LibraryViewModel, GameRow> {
        val repository = FakeLibraryRepository()
        val viewModel = LibraryViewModel(repository, port)
        val game = repository.observeGames(PlatformId.PS1).first().first()
        viewModel.openPlatform(PlatformId.PS1)
        viewModel.openGameDetail(game)
        awaitLabel(viewModel, "DuckStation")
        return viewModel to game
    }

    @Test
    fun `choosing an emulator never lets the EMULATOR fact fall back, and then names the new pick`() = runBlocking {
        val port = StoringPort()
        val (viewModel, game) = cardFor(port)

        viewModel.chooseEmulator(game, epsxe)
        // Right after the commit, before the write has landed: the options survive, and they already name the PICK, never the old emulator
        // (review 2026-10-14: the row read DuckStation for a beat after choosing ePSXe, then flipped).
        assertNotNull("the options survive the commit", viewModel.openWith.value)
        assertEquals("the pick, at once", "ePSXe", label(viewModel))
        val seen = mutableListOf<String?>(label(viewModel))
        awaitLabel(viewModel, "ePSXe", seen)
        assertTrue("never the card's own Automatic fallback (null) at any point: $seen", seen.none { it == null })
        assertTrue("and never the old emulator once picked: $seen", seen.none { it == "DuckStation" })
    }

    @Test
    fun `a load that began before the pick cannot put the old emulator back`() = runBlocking {
        val port = StoringPort()
        val (viewModel, game) = cardFor(port)
        port.snapshotBeforeGate = true
        port.gate = CompletableFuture()
        assertTrue(viewModel.loadOpenWith(game)) // in flight: it has read DuckStation and waits on the gate
        Thread.sleep(60)

        viewModel.chooseEmulator(game, epsxe)
        port.gate?.complete(Unit) // the stale load lands now
        val seen = mutableListOf<String?>()
        val until = System.currentTimeMillis() + 250L
        while (System.currentTimeMillis() < until) {
            seen += label(viewModel)
            Thread.sleep(3)
        }
        assertTrue("the label is the pick throughout: ${seen.distinct()}", seen.all { it == "ePSXe" })
    }

    @Test
    fun `resetting to Automatic never lets the EMULATOR fact fall back either, and names Auto's pick`() = runBlocking {
        val port = StoringPort()
        val (viewModel, game) = cardFor(port)
        viewModel.chooseEmulator(game, epsxe)
        awaitLabel(viewModel, "ePSXe")

        viewModel.resetEmulator(game)
        val seen = mutableListOf<String?>(label(viewModel))
        assertNotNull(viewModel.openWith.value)
        awaitLabel(viewModel, "DuckStation", seen)
        assertTrue("never null: $seen", seen.none { it == null })
    }

    @Test
    fun `dismissing the picker leaves the options the card names its emulator from`() = runBlocking {
        val port = StoringPort()
        val (viewModel, game) = cardFor(port)
        assertTrue(viewModel.loadOpenWith(game))
        awaitLabel(viewModel, "DuckStation")

        viewModel.closeOpenWith()

        assertEquals("DuckStation", label(viewModel))
    }

    @Test
    fun `a launch return that restores the card keeps the options through the restore`() = runBlocking {
        val port = StoringPort()
        val (viewModel, game) = cardFor(port)
        viewModel.chooseEmulator(game, epsxe)
        awaitLabel(viewModel, "ePSXe")

        viewModel.restoreLaunchReturnTargetState(
            LibraryLaunchReturnTarget.gamelistRom(
                openPlatformId = PlatformId.PS1,
                romPlatformId = PlatformId.PS1,
                gameId = game.gameId,
                shelfMode = LibraryShelfMode.EMULATION,
                favoriteSegment = FavoriteSegment.GAMES,
                searchQuery = "",
                favoritesOnly = false,
                restoreLaunchCard = true,
            ),
        )

        assertEquals("the restored card names the pick at once", "ePSXe", label(viewModel))
    }

    @Test
    fun `the options of another game are never shown for this one`() = runBlocking {
        val port = StoringPort()
        val (viewModel, game) = cardFor(port)
        val other = FakeLibraryRepository().observeGames(PlatformId.PS1).first().first { it.gameId != game.gameId }
        port.gate = CompletableFuture<Unit>()

        viewModel.openGameDetail(other)

        assertNull("the previous game's emulator is not shown for the new card while its own load is out", viewModel.openWith.value)
        port.gate?.complete(Unit)
        awaitLabel(viewModel, "DuckStation")
    }

    @Test
    fun `closing the card still drops the options`() = runBlocking {
        val port = StoringPort()
        val (viewModel, _) = cardFor(port)

        viewModel.closeGameDetail()

        assertNull(viewModel.openWith.value)
    }
}

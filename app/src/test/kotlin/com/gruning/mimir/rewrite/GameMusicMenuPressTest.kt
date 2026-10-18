package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.LibraryViewModel
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * the earlier update, end to end below the Android surface: the Edit menu's CONFIRM on "Download music for this game" through the reducer, the
 * real [MimirArtworkState], to the messages it hands the rail. The batch behind it is injected, so no network, library or sign-in.
 * Every case ends with the process gate free ([processGateIsFree]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class GameMusicMenuPressTest {
    private var connected = true
    private val shown = mutableListOf<GameMusicNotice>()

    private val gameName = "Conker's Bad Fur Day"
    private val row = HomeShelfRow(
        itemId = HomeShelfItemId.Emulated(GameId("n64/conker")),
        rank = 1L,
        addedAtMillis = 10L,
        title = gameName,
    )
    private val menu = HomeTileMenuState(row = row, removable = true)

    @After
    fun processGateIsFree() {
        assertEquals(ArtworkOperation.NONE, ArtworkOperationGate.process.active.value)
    }

    private fun TestScope.state() = MimirArtworkState(
        context = RuntimeEnvironment.getApplication(),
        viewModel = LibraryViewModel(FakeLibraryRepository()),
        scope = backgroundScope,
        gameMusicConnected = { connected },
    )

    /** The coordinator's action, as the menu fires it: the same call with the same arguments, the rail replaced by [shown]. */
    private fun MimirArtworkState.press(
        runForGame: suspend (android.content.Context, String, (GameMusicTally) -> Unit) -> GameMusicTally,
    ) {
        downloadGameMusicFromMenu("n64/conker", gameName, { shown += it }, runForGame)
    }

    /** CONFIRM on the Music row of the real Edit menu, as the pad does it. Returns what the reducer made of the menu. */
    private fun confirmMusicRow(artwork: MimirArtworkState, runForGame: suspend (android.content.Context, String, (GameMusicTally) -> Unit) -> GameMusicTally): HomeTileMenuState? {
        val atMusic = menu.copy(menuFocus = homeTileMenuActions(menu).indexOf(HomeTileMenuAction.DOWNLOAD_GAME_MUSIC))
        return reduceHomeTileMenu(
            state = atMusic,
            action = ControllerAction.CONFIRM,
            onRemove = {},
            onLoadArt = {},
            onApplyPick = { _, _ -> },
            onDownloadGameMusic = { artwork.press(runForGame) },
        )
    }

    private fun texts() = shown.map { it.text }

    @Test
    fun `CONFIRM on the Music row closes the menu and answers on the rail - finding, then saved`() = runTest {
        val artwork = state()
        val hold = CompletableDeferred<Unit>()
        val closed = confirmMusicRow(artwork) { _, id, report ->
            assertEquals("the engine is asked for this exact game", "n64/conker", id)
            report(GameMusicTally(total = 1))
            hold.await()
            GameMusicTally(done = 1, total = 1, written = 1).also(report)
        }
        assertNull("the menu closes, as it always did", closed)
        assertEquals("the start shows at once, before any network work", listOf("Finding music for Conker's Bad Fur Day…"), texts())
        assertEquals(MimirMessageClass.Guidance, shown.single().messageClass)

        runCurrent()
        hold.complete(Unit)
        runCurrent()

        assertEquals(listOf("Finding music for Conker's Bad Fur Day…", "Music saved for Conker's Bad Fur Day."), texts())
        assertEquals(MimirMessageClass.Confirmation, shown.last().messageClass)
        assertEquals(GameMusicDownloadState.Finished(written = 1, skipped = 0, failed = 0), artwork.gameMusicState.value)
    }

    @Test
    fun `a second press while the run is going says it is still finding music and starts nothing`() = runTest {
        val artwork = state()
        val hold = CompletableDeferred<Unit>()
        var starts = 0
        val run: suspend (android.content.Context, String, (GameMusicTally) -> Unit) -> GameMusicTally = { _, _, _ ->
            starts++
            hold.await()
            GameMusicTally(done = 1, total = 1, skipped = 1)
        }
        confirmMusicRow(artwork, run)
        runCurrent()
        confirmMusicRow(artwork, run)
        confirmMusicRow(artwork, run)

        assertEquals(1, starts)
        assertEquals(
            listOf("Finding music for Conker's Bad Fur Day…", "Still finding music for Conker's Bad Fur Day…", "Still finding music for Conker's Bad Fur Day…"),
            texts(),
        )

        hold.complete(Unit)
        runCurrent()
        assertEquals("No music found for Conker's Bad Fur Day.", texts().last())
        assertEquals("one run, one result", 4, shown.size)
    }

    @Test
    fun `a press while the Settings batch is running does not claim to be finding this game's music`() = runTest {
        val artwork = state()
        val hold = CompletableDeferred<Unit>()
        artwork.startGameMusicDownload { _, _ ->
            hold.await()
            GameMusicTally()
        }
        runCurrent()

        confirmMusicRow(artwork) { _, _, _ -> error("the engine must not be asked while the batch runs") }

        assertEquals(listOf("Game music is downloading."), texts())

        hold.complete(Unit)
        runCurrent()
        assertEquals("the batch ending adds no message of its own", 1, shown.size)
    }

    @Test
    fun `the Settings batch's own run and states are untouched by a press having existed`() = runTest {
        val artwork = state()
        artwork.press { _, _, report -> GameMusicTally(done = 1, total = 1, skipped = 1, alreadyHad = 1).also(report) }
        runCurrent()
        assertEquals(
            "already-had is a detail of the press; the card still counts it as a plain skip",
            GameMusicDownloadState.Finished(written = 0, skipped = 1, failed = 0),
            artwork.gameMusicState.value,
        )
        shown.clear()

        artwork.startGameMusicDownload { _, report ->
            report(GameMusicTally(done = 2, total = 2, written = 2))
            GameMusicTally(done = 2, total = 2, written = 2)
        }
        runCurrent()
        assertEquals(GameMusicDownloadState.Finished(written = 2, skipped = 0, failed = 0), artwork.gameMusicState.value)
        assertTrue("the batch speaks only on the Settings card", shown.isEmpty())
    }

    @Test
    fun `not connected is refused at the door with why and where, and nothing runs`() = runTest {
        connected = false
        val artwork = state()

        val closed = confirmMusicRow(artwork) { _, _, _ -> error("the engine must not be asked when not connected") }

        assertNull(closed)
        val refusal = shown.single()
        assertEquals("Connect ScreenScraper to download music.", refusal.text)
        assertEquals("Settings › Appearance › Enrichment & artwork", refusal.supportingText)
        assertEquals(MimirMessageClass.Warning, refusal.messageClass)
        assertEquals(GameMusicBlock.NotConnected, artwork.gameMusicState.value.block)
    }

    @Test
    fun `an artwork download in progress is refused with why and when to retry, and nothing runs`() = runTest {
        val artwork = state()
        val enrich = checkNotNull(ArtworkOperationGate.process.tryClaimEnrich())
        try {
            confirmMusicRow(artwork) { _, _, _ -> error("the engine must not be asked while artwork downloads") }
        } finally {
            enrich.release()
        }

        assertEquals(listOf("Artwork is downloading. Try again when it’s done."), texts())
        assertEquals(MimirMessageClass.Guidance, shown.single().messageClass)
    }

    @Test
    fun `each way a single game's run ends is one honest result`() = runTest {
        val outcomes = listOf(
            GameMusicTally(done = 1, total = 1, written = 1) to "Music saved for Conker's Bad Fur Day.",
            GameMusicTally(done = 1, total = 1, skipped = 1, alreadyHad = 1) to "Conker's Bad Fur Day already has music.",
            GameMusicTally(done = 1, total = 1, skipped = 1) to "No music found for Conker's Bad Fur Day.",
            GameMusicTally(done = 1, total = 1, failed = 1) to "Couldn’t download music for Conker's Bad Fur Day. Try again later.",
        )
        for ((tally, expected) in outcomes) {
            shown.clear()
            val artwork = state()
            artwork.press { _, _, report -> tally.also(report) }
            runCurrent()
            assertEquals(tally.toString(), listOf("Finding music for Conker's Bad Fur Day…", expected), texts())
        }
    }

    @Test
    fun `a run that throws is told as a failure, and the state flow publishes one Finished, not two`() = runTest {
        val artwork = state()
        artwork.press { _, _, _ -> error("provider exploded") }
        runCurrent()

        assertEquals("Couldn’t download music for Conker's Bad Fur Day. Try again later.", texts().last())
        assertEquals(MimirMessageClass.Warning, shown.last().messageClass)
        val finished = artwork.gameMusicState.value as GameMusicDownloadState.Finished
        assertEquals(0, finished.written)
    }

    @Test
    fun `stopping a press from Settings is the player's own act and earns no result message`() = runTest {
        val artwork = state()
        val hold = CompletableDeferred<Unit>()
        artwork.press { _, _, _ ->
            hold.await()
            GameMusicTally(done = 1, total = 1, written = 1)
        }
        runCurrent()

        artwork.cancelGameMusicDownload()
        runCurrent()

        assertEquals(listOf("Finding music for Conker's Bad Fur Day…"), texts())
    }

    @Test
    fun `a message that cannot be shown does not disturb the download or its card`() = runTest {
        val artwork = state()
        artwork.downloadGameMusicFromMenu("n64/conker", gameName, { error("the rail is gone") }) { _, _, report ->
            GameMusicTally(done = 1, total = 1, written = 1).also(report)
        }
        runCurrent()

        assertEquals(GameMusicDownloadState.Finished(written = 1, skipped = 0, failed = 0), artwork.gameMusicState.value)
    }
}

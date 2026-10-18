package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import com.gruning.mimir.rewrite.core.model.jobs.ReversibleMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Queue screen's own words in both languages (localization step 4): its header line (a plural), its empty state and a card's chip,
 * which is chosen from the card's state on display. A card's title and second line are the store's own and are not asserted here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FilesQueueTextTest {
    private val english = filesEnglish()
    private val spanish = filesSpanish()

    @Test
    fun `the header line is a plural that keeps the promise in both languages`() {
        assertEquals("1 operation · nothing leaves this device", fileBrowserQueueSummary(english, 1))
        assertEquals("3 operations · nothing leaves this device", fileBrowserQueueSummary(english, 3))
        assertEquals("1 operación · nada sale de este dispositivo", fileBrowserQueueSummary(spanish, 1))
        assertEquals("0 operaciones · nada sale de este dispositivo", fileBrowserQueueSummary(spanish, 0))
        assertEquals("4 operaciones · nada sale de este dispositivo", fileBrowserQueueSummary(spanish, 4))
    }

    @Test
    fun `the empty state still names Undo and still promises nothing about a move or a copy`() {
        assertEquals("Nothing queued. A delete waits here, with Undo, until you settle it.", english.getString(FileBrowserQueueEmptyMessage))
        val text = spanish.getString(FileBrowserQueueEmptyMessage)
        assertTrue(text, text.contains("Deshacer"))
        listOf("mover", "Mover", "copiar", "Copiar", "transfer").forEach { assertFalse("$it in $text", text.contains(it)) }
    }

    @Test
    fun `a card's chip is chosen from its state and reads in Spanish`() {
        assertEquals("34%", fileBrowserQueueChipLabel(spanish, FileQueueState.Running(0.34f)))
        assertEquals("100%", fileBrowserQueueChipLabel(spanish, FileQueueState.Running(4f)))
        assertEquals("En curso…", fileBrowserQueueChipLabel(spanish, FileQueueState.Running(fraction = null)))
        assertEquals("Cancelando…", fileBrowserQueueChipLabel(spanish, FileQueueState.Running(0.5f, cancelling = true)))
        assertEquals("Colocando archivos 3/8", fileBrowserQueueChipLabel(spanish, FileQueueState.Running(0.9f, placed = 3, placeTotal = 8)))
        assertEquals("Nombre en uso", fileBrowserQueueChipLabel(spanish, FileQueueState.AwaitingConflict))
        assertEquals("Movido", fileBrowserQueueChipLabel(spanish, FileQueueState.ReverseAvailable(ReversibleMove(listOf("/b/a.gba"), "/a"))))
        assertEquals(
            "Nombre cambiado",
            fileBrowserQueueChipLabel(spanish, FileQueueState.ReverseAvailable(ReversibleMove(listOf("/a/new.gba"), "/a", previousName = "old.gba"))),
        )
        assertEquals("Listo", fileBrowserQueueChipLabel(spanish, FileQueueState.Done))
        assertEquals("Nada movido", fileBrowserQueueChipLabel(spanish, FileQueueState.Skipped))
        assertEquals("Cancelado", fileBrowserQueueChipLabel(spanish, FileQueueState.Cancelled("2 of 5 files were kept.")))
        assertEquals("Falló", fileBrowserQueueChipLabel(spanish, FileQueueState.Failed("disk full")))
        assertEquals("No disponible", fileBrowserQueueChipLabel(spanish, FileQueueState.Blocked("rar is not supported")))
    }

    @Test
    fun `the chip words of two different states are never the same word`() {
        val states = listOf(
            FileQueueState.Running(fraction = null),
            FileQueueState.Running(0.5f, cancelling = true),
            FileQueueState.AwaitingConflict,
            FileQueueState.Done,
            FileQueueState.Skipped,
            FileQueueState.Cancelled("kept"),
            FileQueueState.Failed("why"),
            FileQueueState.Blocked("why"),
        )
        listOf(english, spanish).forEach { res ->
            val words = states.map { fileBrowserQueueChipLabel(res, it) }
            assertEquals("a Skipped card must not read like Done: $words", words.size, words.toSet().size)
        }
    }
}

package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.model.jobs.FileQueueEntry
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import com.gruning.mimir.rewrite.core.model.jobs.JobKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E2 alpha-cert O3 (2026-10-08): every failed card read the single word "Failed". The state already
 * carries the reason in plain words; the card now shows it where the success-phrased line used to sit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserQueueSubtitleTest {
    private val english = filesEnglish()

    private fun entry(subtitle: String, state: FileQueueState) =
        FileQueueEntry(id = 1, kind = JobKind.COPY, title = "password-zipcrypto.zip", subtitle = subtitle, state = state)

    @Test
    fun `a failed card shows why it failed, not what it was asked to do`() {
        val card = fileBrowserQueueCards(
            listOf(
                entry(
                    "Extract → password-zipcrypto",
                    FileQueueState.Failed("Password-protected. Mimir can't open archives that need a password."),
                ),
            ),
        ).single()

        assertEquals("Password-protected. Mimir can't open archives that need a password.", card.subtitle)
        assertEquals("the chip still says Failed", "Failed", fileBrowserQueueChipLabel(english, card.state))
        assertEquals(FileBrowserQueueTone.FAILED, card.tone)
    }

    @Test
    fun `a failed MOVE no longer sits under a line that says it moved`() {
        val card = fileBrowserQueueCards(
            listOf(entry("Moved from three to gb", FileQueueState.Failed("The file or folder is no longer there."))),
        ).single()

        assertEquals("The file or folder is no longer there.", card.subtitle)
    }

    @Test
    fun `a refused card shows its reason too`() {
        val card = fileBrowserQueueCards(listOf(entry("Extract", FileQueueState.Blocked("Unsupported archive format.")))).single()

        assertEquals("Unsupported archive format.", card.subtitle)
    }

    @Test
    fun `a blank reason falls back to the line the store wrote`() {
        val card = fileBrowserQueueCards(listOf(entry("Extract → out", FileQueueState.Failed("")))).single()

        assertEquals("Extract → out", card.subtitle)
    }

    @Test
    fun `every other state keeps its own line`() {
        listOf(
            FileQueueState.Done,
            FileQueueState.Running(null),
            FileQueueState.AwaitingConflict,
            FileQueueState.Skipped,
        ).forEach { state ->
            assertEquals(state.toString(), "Extract → out", fileBrowserQueueCards(listOf(entry("Extract → out", state))).single().subtitle)
        }
    }

    @Test
    fun `an interrupted extraction is a failed card that says what was kept`() {
        val sentence = "Interrupted: 626 of 2,000 files were extracted to many-2000-files. Those files were left in place."
        val card = fileBrowserQueueCards(
            listOf(entry(sentence, FileQueueState.Failed(sentence)).copy(title = "many-2000-files.zip")),
        ).single()

        assertEquals("many-2000-files.zip", card.title)
        assertEquals(sentence, card.subtitle)
        assertEquals("Failed", fileBrowserQueueChipLabel(english, card.state))
        assertEquals(listOf(FileBrowserQueueAction.DISMISS), card.actions)
    }
}

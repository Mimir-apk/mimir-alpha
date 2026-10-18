package com.gruning.mimir.rewrite.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueEntry
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import com.gruning.mimir.rewrite.core.model.jobs.ReversibleMove
import com.gruning.mimir.rewrite.core.model.jobs.JobKind

/**
 *
 *
 * The pure half — what the warning screen offers, and what each row moves. The library decides *whether*
 * there is a set (`LibraryDiscSetTest`); this decides what to do about one.
 */
class DiscSetMovePolicyTest {

    private fun warning(canTravel: Boolean = true, discCount: Int = 3) = DiscSetWarning(
        title = "Final Fantasy IX",
        discNumber = 2,
        discCount = discCount,
        subjectPath = "/sd/ps1/FF9/FF9 (Disc 2).chd",
        setPaths = listOf(
            "/sd/ps1/FF9/FF9.m3u",
            "/sd/ps1/FF9/FF9 (Disc 1).chd",
            "/sd/ps1/FF9/FF9 (Disc 2).chd",
            "/sd/ps1/FF9/FF9 (Disc 3).chd",
        ),
        wholeSetCanTravel = canTravel,
    )

    @Test
    fun `a set that can travel is offered the repair, first`() {
        assertEquals(
            listOf(
                DiscSetMoveOption.MOVE_WHOLE_SET,
                DiscSetMoveOption.MOVE_SUBJECT_ONLY,
                DiscSetMoveOption.CANCEL,
            ),
            discSetMoveOptions(warning()),
        )
    }

    @Test
    fun `a set that cannot travel is not offered a repair that does not repair`() {
        assertEquals(
            "the playlist names fixed paths, so the game breaks wherever the discs go -- a row saying " +
                "\"move all 3 discs\" would be taken as a fix, and the player would stop looking",
            listOf(DiscSetMoveOption.MOVE_SUBJECT_ONLY, DiscSetMoveOption.CANCEL),
            discSetMoveOptions(warning(canTravel = false)),
        )
    }

    @Test
    fun `cancel is always present and always last`() {
        listOf(true, false).forEach { canTravel ->
            val options = discSetMoveOptions(warning(canTravel))
            assertEquals(
                "focus lands on the FIRST row, so a screen that opened on Cancel would make the common " +
                    "answer cost an extra press every time while leaving the destructive one exactly as " +
                    "cheap. B still cancels from anywhere, which is what actually protects the press.",
                DiscSetMoveOption.CANCEL,
                options.last(),
            )
        }
    }

    @Test
    fun `move the whole set moves the playlist and every disc`() {
        val subject = warning()
        assertEquals(
            "the .m3u is the file that has to arrive with them -- moving three discs and leaving the " +
                "playlist behind is the same breakage with an extra step",
            subject.setPaths,
            discSetMovePaths(subject, DiscSetMoveOption.MOVE_WHOLE_SET),
        )
    }

    @Test
    fun `move the whole set answers NOTHING when the set cannot travel`() {
        assertTrue(
            "this row was never offered for such a set, and a policy that would still hand back paths " +
                "for it is one mis-wired dispatch away from moving a set it just said it could not carry",
            discSetMovePaths(warning(canTravel = false), DiscSetMoveOption.MOVE_WHOLE_SET).isEmpty(),
        )
    }

    @Test
    fun `move just this one moves exactly the subject`() {
        assertEquals(
            listOf("/sd/ps1/FF9/FF9 (Disc 2).chd"),
            discSetMovePaths(warning(), DiscSetMoveOption.MOVE_SUBJECT_ONLY),
        )
    }

    @Test
    fun `cancel moves nothing`() {
        assertTrue(discSetMovePaths(warning(), DiscSetMoveOption.CANCEL).isEmpty())
    }

    @Test
    fun `every option answers a path list, so a new one cannot inherit a default`() {
        DiscSetMoveOption.entries.forEach { option ->
            // The `when` in discSetMovePaths is exhaustive with no `else`; this asserts the same property
            // from the outside, so that an `else` added later fails here rather than silently moving
            // nothing for a row the screen is drawing.
            val paths = discSetMovePaths(warning(), option)
            val expectEmpty = option == DiscSetMoveOption.CANCEL
            assertEquals("$option", expectEmpty, paths.isEmpty())
        }
    }
}

/**
 * The reverse handle a completed move settles with — undo for a move.
 *
 * The store builds the handle (`FileBrowserStore.reversibleMove`, pinned in `app`); this pins what the
 * queue vocabulary does with one, which is where the delete-Undo's rules must NOT be inherited.
 */
class FileQueueReverseAvailableTest {

    private val reverse = ReversibleMove(
        landedPaths = listOf("/sd/Roms/ps2/Zelda.gba"),
        originFolder = "/sd/Download",
    )

    private fun entry(state: FileQueueState) = FileQueueEntry(
        id = 7L,
        kind = JobKind.MOVE,
        title = "Zelda.gba",
        subtitle = "Moved from Download to ps2",
        state = state,
    )

    @Test
    fun `a reversible move is undoable`() {
        assertTrue(entry(FileQueueState.ReverseAvailable(reverse)).undoable)
        assertFalse(entry(FileQueueState.Done).undoable)
    }

    @Test
    fun `the state cannot exist without the means to reverse itself`() {
        // Structural, and the same promise UndoAvailable makes: ReverseAvailable's only constructor takes
        // a ReversibleMove, so there is no way to draw an Undo button over a move that cannot be walked
        // back. Asserted through the type rather than a value check -- the value check would pass on a
        // nullable field.
        val state: FileQueueState = FileQueueState.ReverseAvailable(reverse)
        assertEquals(reverse, (state as FileQueueState.ReverseAvailable).reverse)
    }

    @Test
    fun `its chip reports the OUTCOME, not the offer`() {
        assertEquals(
            "the delete's chip advertises \"Undo available\" because the delete is not finished until the " +
                "player answers and the chip is the only thing saying so. A move IS finished.",
            "Moved",
            FileOperationQueue.chipLabel(entry(FileQueueState.ReverseAvailable(reverse))),
        )
    }

    @Test
    fun `a departed game is remembered on the handle, not read back out of the subtitle`() {
        val departed = reverse.copy(departedFromLibrary = true)
        assertTrue(departed.departedFromLibrary)
        assertFalse(
            "the default is the safe claim: a handle that has not been told the game left must not " +
                "promise the shelf entry comes back, nor claim it does not",
            reverse.departedFromLibrary,
        )
    }

    @Test
    fun `a held deletion is a DELETE handle, and a reversible move is not one`() {
        assertTrue(
            "teardown walks heldDeletions to finalise staging journals. A move has no journal -- its " +
                "staging is deleted on success -- so leaking into that list would make teardown try to " +
                "finalise something that does not exist.",
            FileOperationQueue.heldDeletions(listOf(entry(FileQueueState.ReverseAvailable(reverse)))).isEmpty(),
        )
    }
}

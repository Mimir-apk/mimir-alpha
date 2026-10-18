package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.files.FileOperation
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueEntry
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import com.gruning.mimir.rewrite.core.model.jobs.ReversibleMove
import com.gruning.mimir.rewrite.core.model.jobs.StagedDeletion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Undo for a move, and visible completion** at the file manager/library seam.
 *
 * Two things are pinned here and they are pinned differently:
 *
 * - **[fileQueueDismissable]** is a pure rule and is driven with real values. It is where the
 *   delete-Undo's refusal must NOT be inherited, and getting that wrong strands every completed move on
 *   the queue for the session.
 * - **Everything in [FileBrowserStore]** is pinned as SOURCE TEXT, carrying `FileBrowserMoveTellsLibrary
 *   Test`'s disclosed gap unchanged: the store is an `object` with process-lifetime state and a `Context`
 *   requirement, so nothing here can put an entry into its real queue without a Robolectric harness this
 *   module does not have. These prove the code is written, not that it executes. The executable halves
 *   live in `core-files` (the queue vocabulary) and `core-resolver` (the relocation).
 */
class FileBrowserMoveUndoTest {

    private val source: String by lazy {
        appSource("FileBrowserStore.kt").readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("(?m)//.*$"), "")
    }

    private val reverse = ReversibleMove(listOf("/sd/Roms/ps2/Zelda.gba"), "/sd/Download")

    // ── the pure rule ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a completed move CAN be dismissed, and a staged delete still cannot`() {
        assertTrue(
            "the move is already final -- the bytes are at the destination and the library has followed " +
                "them -- so Undo is an OFFER. Refusing DISMISS here would strand every completed move on " +
                "the queue for the whole session, with a button that no longer has anything to protect.",
            fileQueueDismissable(FileQueueState.ReverseAvailable(reverse)),
        )
        assertFalse(
            "the delete's refusal is about a decision the player has NOT made yet: dismissing it " +
                "finalises the delete, and clearSession reads held deletions out of the queue itself, so " +
                "a card removed that way is invisible to teardown",
            fileQueueDismissable(FileQueueState.UndoAvailable(StagedDeletion(operationRootPath = "/sd/.mimir-staging/op", entries = emptyList()))),
        )
    }

    @Test
    fun `every queue state answers dismissable, so a new one cannot inherit a default`() {
        val states = listOf(
            FileQueueState.Running(0.5f),
            FileQueueState.AwaitingConflict,
            FileQueueState.UndoAvailable(StagedDeletion(operationRootPath = "/sd/.mimir-staging/op", entries = emptyList())),
            FileQueueState.ReverseAvailable(reverse),
            FileQueueState.Blocked("rar"),
            FileQueueState.Done,
            FileQueueState.Failed("nope"),
        )
        assertEquals(
            "one value per FileQueueState subtype -- if this count and the sealed hierarchy ever " +
                "disagree, a state is being decided by an `else` somewhere",
            listOf(false, false, false, true, true, true, true),
            states.map(::fileQueueDismissable),
        )
    }

    // ── the wiring ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a successful MOVE settles as reversible, and a COPY settles as Done`() {
        val arm = bodyBetween("onSuccess = { count ->", "onFailure = { error ->")

        assertTrue(
            "before this, a finished move settled as Done and the only button was Dismiss",
            arm.contains("FileQueueState.ReverseAvailable(reverse)"),
        )
        // **A completed progress bar must not claim a transfer when nothing moved.** The terminal state must read
        // the same number the announcement below it already reads. It used to be unconditional, so a move
        // that transferred nothing still settled on Done and the card sat at 100% over an untouched
        // filesystem. Asserted as a whole expression, not a fragment: `transferredCount == 0` alone would
        // pass against the announcement guard twenty lines further down, which is a different check.
        assertTrue(
            "a transfer that moved nothing must settle as Skipped -- not Done, which reads as success, " +
                "and not Failed, which reads as an alarm for something that did not go wrong",
            arm.contains("transferredCount == 0 -> FileQueueState.Skipped"),
        )
        assertTrue(
            "a COPY has nothing to undo -- nothing was taken away, and \"undoing\" it would mean deleting " +
                "a file the player asked to create",
            arm.contains("operation == FileOperation.MOVE"),
        )
        assertTrue(
            "SKIP's `count` is the engine's HANDLED count, not its TRANSFERRED count, so a Skip that " +
                "moved nothing must not offer to move nothing back",
            arm.contains("resolution != ConflictResolution.SKIP"),
        )
    }

    @Test
    fun `the handle refuses everything it could not honestly reverse`() {
        val body = bodyBetween("private fun reversibleMove(", "private fun noteDeparture(")

        assertTrue(
            "sources from different folders have no single \"back\", and picking one would return files " +
                "to a place they were never in",
            body.contains("paths.any { File(it).parent != origin }"),
        )
        assertTrue(
            "EVERY source must have a landing the ENGINE reported. Absence means the resolution skipped " +
                "it, and a handle that guessed past a skip would offer to move back a file that never " +
                "moved.",
            body.contains("landings[path] ?: return null"),
        )
        assertTrue(
            "**and the reported landing must be the file's OWN name at the destination.** This is the " +
                "assertion that used to be a lie: the message beside it named the Keep-both case exactly, " +
                "while the code it pinned -- `landed.any { !it.exists() }` -- did not test for it at all. " +
                "Under Keep both the engine renames the ARRIVING file, so the incumbent still occupies " +
                "destination/<name> and `exists()` is TRUE there, through a file this move never touched. " +
                "The handle named the player's own pre-existing file and Undo moved it out of its folder. " +
                "Comparing against the REPORT is what closes it; `exists()` never could.",
            body.contains("!reported.namesSameFileAs(File(destination, File(path).name))"),
        )
        assertTrue(
            "and it still asks the FILESYSTEM at settle time -- the property that lets an Undo pressed " +
                "against a stale card fail visibly instead of corrupting anything",
            body.contains("landed.any { !it.exists() }"),
        )
        assertTrue(
            "**compared CANONICALLY, not as strings.** ManagedFileOperations canonicalises its " +
                "destination, so every landing it reports is a real path while this store's " +
                "`destination` is whatever the browser was showing. On Android `/sdcard` is a symlink " +
                "to `/storage/emulated/0`, so a string compare would have refused every landing reached " +
                "that way and silently stopped offering Undo at all -- a fix that fails closed on the " +
                "ordinary case is still a broken feature.",
            source.contains("private fun File.namesSameFileAs("),
        )
    }

    @Test
    fun `undo routes a move back through the ordinary transfer path`() {
        val body = functionBody("private fun undoMove(")

        assertTrue(
            "a bespoke restore would be a second mover to keep correct, and the first thing it would get " +
                "wrong is the collision. ManagedFileOperations.move deletes its own staging on success, " +
                "so there is no journal to replay -- the only truthful reverse is a new move.",
            body.contains("transfer(") && body.contains("FileOperation.MOVE"),
        )
        assertTrue(
            "from where they landed, to where they came from",
            body.contains("reverse.landedPaths") && body.contains("reverse.originFolder"),
        )
        assertTrue(
            "the original card leaves first: two cards for one round trip, one offering to undo " +
                "something already undone, is a queue that lies",
            body.contains("FileOperationQueue.remove(mutableQueue.value, entryId)"),
        )
    }

    @Test
    fun `undo says when it cannot put the game back on the shelf`() {
        val body = functionBody("private fun undoMove(")

        assertTrue(
            "a departed game's row was DELETED, so the reverse finds nothing to relocate and the shelf " +
                "entry returns only at the next scan. Undo is still worth offering -- the file goes home " +
                "-- but it must not imply a restoration it cannot perform.",
            body.contains("reverse.departedFromLibrary"),
        )
        assertTrue(
            "and it is said on COMPLETION, not at the press: until the files are actually back, it is a " +
                "promise about a transfer that could still collide or fail",
            body.contains("noteOnSuccess"),
        )
    }

    @Test
    fun `the departure marks the HANDLE, not just the card`() {
        val body = bodyBetween("private fun noteDeparture(", "private fun noteLibraryUnreachable(")

        assertTrue(
            "reading UI copy back as state is how a rename becomes a behaviour change -- the fact is " +
                "carried on FileQueueState.ReverseAvailable's own handle",
            body.contains("FileQueueState.ReverseAvailable") && body.contains("departedFromLibrary = true"),
        )
    }

    @Test
    fun `the undo button branches on the state rather than assuming a delete`() {
        val body = bodyBetween("fun undo(entryId: Long) {", "fun releaseUndo(")

        assertTrue(
            "one word, one promise -- \"put it back\" -- and the state decides which shape that takes. " +
                "Reading the deletion handle first would make undo() a silent no-op on every moved file.",
            body.indexOf("ReverseAvailable") in 0 until body.indexOf("UndoAvailable"),
        )
    }

    // ── no silent fail ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every arm that gives up on the library says so on the card`() {
        val body = bodyBetween("private fun tellLibraryTheFilesMoved(", "private fun reversibleMove(")

        // The four arms that used to `return` without a word. Each one leaves the row stale, which the
        // next scan corrects -- being one row behind is survivable; being one row behind SILENTLY is what
        // makes it look like data loss.
        listOf(
            "context == null" to "noteLibraryUnreachable",
            "repository == null" to "noteLibraryUnreachable",
            "!landed.exists()" to "noteRenamedOnArrival",
            "null -> noteLibraryUnreachable" to "noteLibraryUnreachable",
        ).forEach { (arm, note) ->
            assertTrue("the `$arm` arm must reach $note", body.contains(arm) && body.contains(note))
        }
    }

    @Test
    fun `the two outcomes that are NOT failures stay quiet`() {
        val body = bodyBetween("private fun tellLibraryTheFilesMoved(", "private fun reversibleMove(")

        assertTrue(
            "NotInLibrary is the ORDINARY case -- screenshots, saves, BIOS images -- and Relocated is the " +
                "feature working. A card that announced either would be chatter on every move, which is " +
                "how a player learns to stop reading the one that matters.",
            body.contains("is LibraryRelocation.NotInLibrary, is LibraryRelocation.Relocated -> Unit"),
        )
    }

    @Test
    fun `a note is appended once, and never replaces what the card already said`() {
        val body = bodyBetween("private fun noteOnCard(", "private fun Throwable.playerFacingReason")

        assertTrue(
            "the append itself is withNote, run against real cards in FileBrowserCompanionTracksTest -- the " +
                "once-only and never-replace behaviour is pinned there, not by reading its text",
            body.contains("mutableQueue.value = withNote(mutableQueue.value, id, note)"),
        )
    }

    @Test
    fun `the disc-set pre-flight never blocks the press and never invents a warning`() {
        val body = bodyBetween("suspend fun discSetWarningFor(", "private fun transfer(")

        assertTrue("it suspends rather than blocking", source.contains("suspend fun discSetWarningFor("))
        assertTrue(
            "a warning that fired because the library was unreachable would train the player to dismiss " +
                "it -- and an unreachable library is already stated on the move's own card",
            body.contains("?: return null"),
        )
        assertFalse(
            "**never `runCatching` here.** It catches CancellationException, and null is the answer that " +
                "DISPATCHES THE MOVE -- so a cancelled lookup performed the press it existed to " +
                "intercept. FileBrowserController.beginMove rethrows for exactly this reason and its " +
                "KDoc names the trap; this function then swallowed the exception one module down, before " +
                "the controller could ever see one, leaving the generation guard as the only live " +
                "protection and the documented defence-in-depth half illusory.",
            body.contains("runCatching"),
        )
        assertEquals(
            "one rethrow per catch: the repository lookup and the query are two separate suspension " +
                "points and either can be the one that is cancelled",
            2,
            Regex("catch \\(error: CancellationException\\)").findAll(body).count(),
        )
        assertTrue("and the answer comes from the library, not from a guess", body.contains("repository.discSetFor"))
    }

    @Test
    fun `undo is deliberately NOT behind the disc-set warning, and says so`() {
        val raw = appSource("FileBrowserStore.kt").readText()
        val body = raw.substringAfter("private fun undoMove(").substringBefore("\n    }\n")

        assertFalse(
            "a player who moved one disc out and pressed Undo is REPAIRING the set -- warning them that " +
                "the move is about to break their game would be the feature firing backwards, on the one " +
                "press that fixes it",
            body.contains("discSetWarningFor"),
        )
        assertTrue(
            "and the exception is WRITTEN DOWN, not merely true. An audit found this door and it reads " +
                "like a hole; the next reader must find the reason beside the code rather than close it.",
            body.contains("does NOT pass the disc-set warning, and that is correct"),
        )
    }

    private fun bodyBetween(start: String, end: String): String =
        source.substringAfter(start).substringBefore(end)

    /**
     * One function's body, bounded at its own closing brace rather than at the next name a reader
     * assumes follows it. Found the hard way: `undoMove` and `transfer` have two other functions between
     * them, so a window spanning both quietly asserted things about the wrong code.
     */
    private fun functionBody(signature: String): String =
        source.substringAfter(signature).substringBefore("\n    }\n")

    private fun appSource(fileName: String): File = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
    ).firstOrNull { it.isFile }
        ?: throw AssertionError("$fileName not reachable from the test working directory")
}

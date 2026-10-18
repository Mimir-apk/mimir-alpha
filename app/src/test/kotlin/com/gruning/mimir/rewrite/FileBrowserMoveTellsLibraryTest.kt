package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *
 *
 * `LibraryRepository.relocate` is proved in `core-resolver`'s own `LibraryRelocationTest` against the
 * real registry. What that cannot prove is that this store ever CALLS it — and an unwired relocation is
 * exactly the failure the ruling was about, one layer up: the engine finished, tested and unreachable.
 * This programme has now given a door to five such engines (`ExtractDestinationPolicy`, the queue,
 * `extractOptions`, `fileBrowserTransferOptions`, `DuplicateScanner` is still waiting), so the wiring
 * gets a pin rather than a promise.
 *
 * Source-level, carrying `FileBrowserStorePickSafetyTest`'s disclosed gap unchanged: [FileBrowserStore]
 * is an `object` with process-lifetime state and a `Context` requirement, so this proves the call is
 * written, not that it executes. The executable half is the resolver's.
 */
class FileBrowserMoveTellsLibraryTest {

    private val source: String by lazy {
        appSource("FileBrowserStore.kt").readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("(?m)//.*$"), "")
    }

    @Test
    fun `a completed MOVE tells the library, and a COPY does not`() {
        val moveArm = source.substringAfter("if (operation == FileOperation.MOVE) {\n                            ")
            .substringBefore("} else {")

        assertTrue(
            "without this the row goes on naming a path with no file at it: the game is unlaunchable " +
                "until the next scan of that root, which then DELETES it",
            // `paths - leftBehind`: a companion the engine left where it was  did not move, so the library is not
            // told it did.
            moveArm.contains("tellLibraryTheFilesMoved(id, paths - leftBehind.toSet(), destination, landings)"),
        )
        val copyArm = source.substringAfter("session?.onCopied").substringBefore(")")
        assertTrue(
            "a copy leaves the original where the library indexed it -- nothing about the existing row " +
                "became false, and the scanner will find the new file on its own terms",
            !copyArm.contains("tellLibraryTheFilesMoved"),
        )
    }

    @Test
    fun `it is inside the transferred guard, because a fully skipped or accounted-only move moves nothing`() {
        // F-6(a): the guard used to infer "nothing transferred" from the player's conflict CHOICE
        // (`resolution != ConflictResolution.SKIP`) -- correct only because production transfers exactly
        // one path. `transferredCount` (`count - skippedCount`, recovered from the engine's `onSkipped`
        // callback) is the real number, so this is now the guard that matters.
        val guarded = source.substringAfter("if (transferredCount > 0) {")
            .substringBefore("},")

        assertTrue(
            "`count` is the engine's HANDLED count, not its TRANSFERRED count -- relocating a row for a " +
                "file that was actually skipped would be the same lie the announcement guard already " +
                "exists to prevent. Guarded body was:\n$guarded",
            guarded.contains("tellLibraryTheFilesMoved"),
        )
    }

    @Test
    fun `it only relocates a file that landed under its OWN name`() {
        val body = source.substringAfter("private fun tellLibraryTheFilesMoved(")
            .substringBefore("private fun Throwable.playerFacingReason")

        assertTrue("the destination is checked, not assumed", body.contains("landed.exists()"))
        assertTrue(
            "ManagedFileOperations.move answers a COUNT, not the paths it wrote, and Keep both renames " +
                "the destination -- so the final path is not always derivable and must not be guessed",
            body.contains("File(destination, File(from).name)"),
        )
        assertTrue("and it goes through the real repository", body.contains("repository.relocate(from, landed.path)"))
    }

    @Test
    fun `it never blocks the queue and never crashes it`() {
        val body = source.substringAfter("private fun tellLibraryTheFilesMoved(")
            .substringBefore("private fun Throwable.playerFacingReason")

        assertTrue("a Room rewrite must not hold the card's completion", body.contains("scope.launch"))
        assertTrue(
            "a library that cannot be reached is a stale row the next scan corrects, never a file " +
                "operation that reports failure after the file already moved",
            body.contains("runCatching"),
        )
    }

    @Test
    fun `a game that LEFT the library says so on the card`() {
        val body = source.substringAfter("private fun tellLibraryTheFilesMoved(")
            .substringBefore("private fun Throwable.playerFacingReason")

        assertTrue(
            "a player who watches a card say \"Moved Zelda.gba to ps2\" and then cannot find the game " +
                "anywhere has been told the opposite of what happened",
            body.contains("LibraryRelocation.Departed") && body.contains("noteDeparture"),
        )

        val note = source.substringAfter("private fun noteDeparture(").substringBefore("\n    }")
        assertTrue(
            "both reasons are answered -- an exhaustive `when` over the enum, so a third reason cannot " +
                "be added later and silently inherit no message",
            note.contains("OUTSIDE_SOURCES") && note.contains("IDENTITY_CHANGED"),
        )
        assertTrue("and it lands on the card the player is already looking at", note.contains("mutableQueue.value"))
    }

    private fun appSource(fileName: String): File = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
    ).firstOrNull { it.isFile }
        ?: throw AssertionError("$fileName not reachable from the test working directory")
}

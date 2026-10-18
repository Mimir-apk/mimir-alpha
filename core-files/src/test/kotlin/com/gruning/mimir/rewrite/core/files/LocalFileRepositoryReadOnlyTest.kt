package com.gruning.mimir.rewrite.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **SAFETY PROPERTY — the picker is INCAPABLE of destroying anything, not merely discouraged from it.**
 *
 * A player adding ROMs cannot delete anything: destructive operations are unreachable from
 * the picker, rather than merely hidden by its UI.
 *
 * `FileBrowserStore` hands **one** [LocalFileRepository] to both `FileBrowserMode.PICK` and
 * `FileBrowserMode.MANAGE`. So the strongest available form of "incapable" is that this class — the
 * only engine a PICK session ever holds — **has no destructive method on it at all**. Not a private one,
 * not one behind a flag: none. Restored copy/move/delete live on [ManagedFileOperations], which
 * PICK never constructs (pinned separately in `app/`'s `FileBrowserStorePickSafetyTest`).
 *
 * Reflection rather than a behavioural assertion, deliberately: a behavioural test can only prove that
 * the methods it *thought of* are absent. This proves that nothing matching the destructive vocabulary
 * exists, including a method a future edit invents under a new name.
 *
 * **If this test fails, a player adding ROMs can delete their library. Treat it as a P0.**
 */
class LocalFileRepositoryReadOnlyTest {

    /**
     * Verbs that mutate a user file. Deliberately broader than the six names slice 4 removed
     * (`copyFiles`, `moveFiles`, `deleteFiles`, `rename`, `createDirectory`, `createFile`) — matching on
     * the VERB catches `remove`, `trash`, `overwrite`, `purge` and anything else a later edit reaches
     * for, which a name-by-name list would not.
     */
    private val destructiveVerbs = listOf(
        "delete", "remove", "trash", "erase", "purge", "wipe",
        "move", "rename", "copy", "write", "overwrite", "create", "mkdir", "truncate",
    )

    @Test
    fun `the picker's engine declares no destructive member, by any name`() {
        val offenders = LocalFileRepository::class.java.declaredMethods
            .map { it.name }
            .filter { name -> destructiveVerbs.any { verb -> name.lowercase().contains(verb) } }

        assertEquals(
            "SAFETY: LocalFileRepository is the ONLY engine a FileBrowserMode.PICK session holds, and " +
                "the picker must be INCAPABLE of destruction, not merely gated " +
                "away from it. A destructive member here breaks that by construction. Found: $offenders. " +
                "F-01's restored operations belong on ManagedFileOperations, which PICK never constructs.",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `and the class that DOES carry them is a different one, so the split is real`() {
        val managed = ManagedFileOperations::class.java.declaredMethods.map { it.name }

        // The mirror image of the test above: if these were absent too, the first test would be
        // vacuously green because nothing anywhere implements the operations -- a passing safety test
        // over a feature that does not exist. This is what stops that.
        assertTrue(
            "sanity: F-01's operations must actually exist somewhere, or the pin above proves nothing",
            managed.any { it.contains("delete", ignoreCase = true) } &&
                managed.any { it.contains("move", ignoreCase = true) } &&
                managed.any { it.contains("copy", ignoreCase = true) },
        )
        assertTrue(
            "and they must be on a DIFFERENT class from the picker's engine",
            ManagedFileOperations::class.java != LocalFileRepository::class.java,
        )
    }
}

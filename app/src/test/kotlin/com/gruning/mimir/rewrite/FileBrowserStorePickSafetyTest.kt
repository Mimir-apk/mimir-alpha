package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueEntry

/**
 * **SAFETY PROPERTY, app half — a `FileBrowserMode.PICK` session cannot reach a destructive operation.**
 *
 * A player adding ROMs cannot reach destructive operations from the picker.
 *
 * The property is carried by two independent facts, and both are pinned — one here, one in `core-files`:
 *
 *  1. `core-files`' `LocalFileRepositoryReadOnlyTest`: the engine BOTH modes hold (`LocalFileRepository`)
 *     declares no destructive member at all, so there is nothing for a PICK session to call. Destructive
 *     operations live on the separate `ManagedFileOperations`.
 *  2. **This file:** [FileBrowserStore] constructs `ManagedFileOperations` in exactly one place, guarded
 *     by `mode == FileBrowserMode.MANAGE`, and every destructive entry point returns early when that
 *     field is null.
 *
 * Source-level, following `UtilityFilesRouteTest`'s own idiom for the same safety property: [FileBrowserStore] is
 * an `object` with process-lifetime state and a `Context` requirement, so a JVM unit test cannot drive it
 * through a real session without a Robolectric harness this module does not have. **Disclosed gap:** this
 * proves the guard is written, not that it executes — which is why fact 1 exists, since a structural
 * absence needs no execution to hold.
 */
class FileBrowserStorePickSafetyTest {

    private val storeSource: String by lazy { appSource("FileBrowserStore.kt").readText() }

    /** Every store entry point that can destroy or overwrite a player's file. */
    private val destructiveEntryPoints = listOf(
        "fun deleteInManager(",
        "fun moveInManager(",
        "fun copyInManager(",
        "fun renameInManager(",
        "fun cancel(",
        "fun undo(",
        "fun releaseUndo(",
        "fun resolveConflict(",
    )

    @Test
    fun theDestructiveEngineIsConstructedOnlyInTheManageBranch() {
        val constructions = Regex("""ManagedFileOperations\(""").findAll(storeSource).count()

        assertEquals(
            "SAFETY: ManagedFileOperations must be constructed in exactly ONE place. Every extra " +
                "construction site is another chance for a PICK session to get one.",
            1,
            constructions,
        )

        val assignment = storeSource.lineSequence().first { it.contains("ManagedFileOperations(") }
        assertTrue(
            "SAFETY: the single construction must be gated on MANAGE. Without that gate, the add-games " +
                "picker must not hold a delete engine. Found: ${assignment.trim()}",
            assignment.contains("mode == FileBrowserMode.MANAGE"),
        )
        assertTrue(
            "...and PICK must get null, not a fallback instance",
            assignment.substringAfter("mode == FileBrowserMode.MANAGE").contains("else null"),
        )
    }

    @Test
    fun everyDestructiveEntryPointRefusesWhenNoManageSessionIsOpen() {
        destructiveEntryPoints.forEach { signature ->
            assertTrue(
                "sanity: $signature must exist, or this pin is vacuously green over a feature that " +
                    "was never built",
                storeSource.contains(signature),
            )
            // The first ~6 lines of each body: the null guard has to be the first thing that happens,
            // before any path that could touch the filesystem.
            val body = storeSource.substringAfter(signature).lineSequence().take(6).joinToString("\n")
            assertTrue(
                "SAFETY: $signature must return early when `operations` is null -- that field is null " +
                    "for the whole life of a PICK session. Body began:\n$body",
                body.contains("operations ?: return") || body.contains("operations == null) return"),
            )
        }
    }

    @Test
    fun theQueueCannotBeFilledFromAPickSession() {
        // Everything that adds to the queue routes through the two private helpers, and both are only
        // reachable from bodies that already returned early on a null `operations`. Pinned by absence:
        // no direct mutableQueue write may exist outside them and the teardown.
        val directWrites = Regex("""mutableQueue\.value\s*=""").findAll(storeSource).count()
        assertTrue(
            "sanity: the queue must actually be written somewhere",
            directWrites > 0,
        )
        assertFalse(
            "the queue must not be exposed as a mutable handle -- a caller holding one could enqueue " +
                "work in PICK",
            storeSource.contains("val queue: MutableStateFlow"),
        )
        assertTrue(
            "the queue must be published read-only",
            storeSource.contains("val queue: StateFlow<List<FileQueueEntry>>"),
        )
    }

    @Test
    fun theSessionTeardownSettlesEveryHeldDeleteRatherThanStrandingIt() {
        val teardown = storeSource.substringAfter("private fun clearSession()").substringBefore("\n    }")

        assertTrue(
            "a held delete owns a real journal and the player's bytes; leaving one behind strands both",
            teardown.contains("heldDeletions") && teardown.contains("finalizeDelete"),
        )
        assertTrue(
            "the queue must not outlive its session -- an Undo whose engine is gone cannot be honoured",
            teardown.contains("mutableQueue.value = emptyList()"),
        )
        assertTrue(
            "and the engine itself must be dropped, so a later PICK session cannot inherit it",
            teardown.contains("operations = null"),
        )
        assertTrue(
            "A parked extraction's pendingExtractions entry must not " +
                "survive the session either -- left uncleared, a stale map entry lingers for the rest of " +
                "the process's life, the same leak pendingTransfers.clear() already exists to prevent.",
            teardown.contains("pendingExtractions.clear()"),
        )
        assertTrue(
            "A rename parked on a name clash must not outlive its session either",
            teardown.contains("pendingRenames.clear()"),
        )
    }

    private fun appSource(fileName: String): File = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
    ).firstOrNull { it.isFile }
        ?: throw AssertionError("$fileName not reachable from the test working directory")
}

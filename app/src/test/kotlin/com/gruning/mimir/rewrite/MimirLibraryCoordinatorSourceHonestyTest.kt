package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.library.ScanOutcome
import com.gruning.mimir.rewrite.core.model.library.SourceFailureKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Folder-read failures use one
 * plain sentence, no reason code, no jargon: *"Couldn't read this folder."* Before this fix, `rescan`
 * (`:211-213`, pre-fix line numbers) and `addSource` (`:214-219`) each collapsed `ScanResult.outcome`
 * to `outcome == ScanOutcome.Healthy` — a bare `Boolean` with no false branch, so `ScanOutcome.Failed`
 * (a folder that could not be traversed) and a genuinely empty, successfully-scanned folder produced
 * the identical `false`/no-op. `runSourceOperation`'s `operation` parameter is widened from
 * `suspend () -> Boolean` to `suspend () -> SourceOperationOutcome` to carry the outcome instead of
 * discarding it (the two honest siblings at the old `:220-239`, `rescanAll`/`removeAdditionalSource`,
 * already showed this `onResult`-callback shape).
 *
 * **What is genuinely behavioural here, and what is not.** `librarySourceReadFailureMessage` is a pure,
 * directly-callable function — this file's tests against it are real JUnit differentials: each was
 * actually run red before the fix, and the exact failure is captured in each test's doc comment. C1 and
 * C2's *site wiring* (that `rescan =`/`addSource =` inside `rememberMimirLibraryCoordinator` actually
 * route through that function rather than the old bare-Boolean collapse) is pinned by **source
 * characterization** — reading the raw file text — because `rememberMimirLibraryCoordinator` is a
 * `@Composable` closure with no Robolectric harness in this module able to drive it with a real
 * `MimirAppCoreCoordinator` (same accepted gap as this codebase's own precedent:
 * `MediaMenuHonestyCoordinatorWiringTest`, `AppBrightnessCoordinatorDeclineFeedbackTest`,
 * `SessionMutexRefusalWiringTest`). For C1/C2 specifically, a real compile-time differential was also
 * captured by hand (revert the production line, run `:app:compileDebugKotlin --offline`, record the
 * exact error, restore) — not reproduced as a JUnit test, since a Kotlin
 * compile error is not something JUnit can assert on.
 */
class MimirLibraryCoordinatorSourceHonestyTest {

    // ── Acceptance #2: Healthy and Failed produce distinguishable states. Real behavioural
    // differentials against `librarySourceReadFailureMessage` -- the function C1/C2 now route through. ──

    /**
     * Differential: before this fix, no function like this existed at all -- `rescan`/`addSource`
     * discarded [ScanOutcome.Failed] entirely, so there was nothing to assert null against for the
     * healthy arm either (both outcomes reduced to `false`/no-op with no distinguishing return). This
     * test is trivially green either way (`null == null`); the red half of this differential is the
     * "Healthy and Failed produce distinguishable coordinator states" test below and the failure-kind
     * test that follows it -- both actually run red and restored, exact strings in their own doc
     * comments.
     */
    @Test
    fun `a healthy scan produces no read-failure message`() {
        assertNull(librarySourceReadFailureMessage(ScanOutcome.Healthy))
    }

    /**
     * The defect in one line, pinned: a folder that fails to scan must always read the same plain
     * sentence, never a message that varies with [SourceFailureKind] or leaks the diagnostic-only
     * [ScanOutcome.Failed.reason]. Differential, actually run and captured (revert restored after):
     * changing `librarySourceReadFailureMessage`'s body to `null` unconditionally turned this test red
     * on the first [SourceFailureKind] entry (`forEach` stops at the first thrown assertion) with
     * `java.lang.AssertionError: kind=UNAVAILABLE must not change the player sentence
     * expected:<Couldn’t read this folder.> but was:<null>` -- restored to green after capture.
     */
    @Test
    fun `a failed scan produces the shared failure sentence, verbatim, regardless of failure kind or reason`() {
        SourceFailureKind.entries.forEach { kind ->
            assertEquals(
                "kind=$kind must not change the player sentence",
                "Couldn’t read this folder.",
                librarySourceReadFailureMessage(
                    ScanOutcome.Failed(reason = "diagnostic-only detail unique to $kind, never rendered", kind = kind),
                ),
            )
        }
    }

    /**
     * The deliverable: [ScanOutcome.Healthy] and [ScanOutcome.Failed] are distinguishable in one place.
     * Differential, actually run and captured (revert restored after): changing
     * `librarySourceReadFailureMessage`'s body to `null` unconditionally turned this test red at the
     * `assertEquals` below with `java.lang.AssertionError: expected:<Couldn’t read this folder.> but
     * was:<null>` -- restored to green after capture.
     */
    @Test
    fun `Healthy and Failed produce distinguishable coordinator states`() {
        val healthy = librarySourceReadFailureMessage(ScanOutcome.Healthy)
        val failed = librarySourceReadFailureMessage(ScanOutcome.Failed(reason = "unavailable", kind = SourceFailureKind.UNAVAILABLE))
        assertNull("a healthy scan must not carry a read-failure message", healthy)
        assertEquals("Couldn’t read this folder.", failed)
        assertNotEquals("the two outcomes must not collapse to the same coordinator state", healthy, failed)
    }

    // ── C1/C2: source characterization pinning the two wiring sites (no Robolectric harness for this
    // Composable's closures -- see class doc). ──

    private fun coordinatorSource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirLibraryCoordinator.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/MimirLibraryCoordinator.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("MimirLibraryCoordinator.kt not found from ${File(".").absolutePath}")
    }

    /**
     * Real compile-time differential captured by hand for this exact site (not reproduced as a JUnit
     * assertion -- a Kotlin compiler error isn't something JUnit runs against): reverting only this
     * block's production line back to
     * `runSourceOperation { repository.rescan(rootUri).outcome == ScanOutcome.Healthy }` and running
     * `:app:compileDebugKotlin --offline` failed with
     * `Argument type mismatch: actual type is 'kotlin.Boolean', but
     * 'com.gruning.mimir.rewrite.SourceOperationOutcome' was expected.` at the reverted line. Restored,
     * `:app:compileDebugKotlin --offline` green again, file byte-identical to before the revert.
     */
    @Test
    fun `C1 -- rescan carries the ScanOutcome into runSourceOperation instead of collapsing it to a bare Boolean`() {
        val block = coordinatorSource()
            .substringAfter("rescan = { rootUri ->")
            .substringBefore("addSource = { rootUri, role ->")

        assertFalse(
            "the pre-fix collapse must be gone -- rescan must not reduce the outcome to a bare Boolean",
            block.contains("runSourceOperation { repository.rescan(rootUri).outcome == ScanOutcome.Healthy }"),
        )
        assertTrue(
            "rescan must capture the whole ScanResult once and pass its outcome on as scanOutcome",
            block.contains("val result = repository.rescan(rootUri)") &&
                block.contains("val outcome = result.outcome") &&
                block.contains("SourceOperationOutcome(libraryChanged = outcome == ScanOutcome.Healthy, scanOutcome = outcome)"),
        )
        assertTrue(
            "rescan must publish the summary -- capturing the result and then dropping everything but " +
                "the outcome is the defect D1-R20 closed",
            block.contains("LastScanSummaryState.record(result)"),
        )
    }

    /**
     * Real compile-time differential captured by hand for this exact site: reverting only this block's
     * production line back to `repository.addSource(rootUri, role).outcome == ScanOutcome.Healthy` (bare
     * Boolean, no `val outcome =` capture) and running `:app:compileDebugKotlin --offline` failed with
     * `Argument type mismatch: actual type is 'kotlin.Boolean', but
     * 'com.gruning.mimir.rewrite.SourceOperationOutcome' was expected.` at the reverted line. Restored,
     * `:app:compileDebugKotlin --offline` green again, file byte-identical to before the revert.
     */
    /**
     * **Both pins require the whole scan result.** They matched the literal `val outcome = repository.rescan(rootUri).outcome`. The scan-summary change had to capture the whole `ScanResult` rather than only its outcome,
     * because the skip-reason breakdown was being discarded on this exact line, so the shape moved to
     * `val result = …` / `val outcome = result.outcome`. **The guarantee is unchanged and the pin is
     * strictly stronger:** it still forbids the bare-Boolean collapse, it still requires the outcome to
     * be carried through as `scanOutcome`, and it now also requires the summary to be published.
     * Capturing the whole result and then dropping everything but the outcome would have satisfied the
     * old assertion while reintroducing the exact defect the scan-summary change closed. It does not satisfy this one.
     */
    @Test
    fun `C2 -- addSource carries the ScanOutcome into runSourceOperation instead of collapsing it to a bare Boolean`() {
        val block = coordinatorSource()
            .substringAfter("addSource = { rootUri, role ->")
            .substringBefore("rescanAll = { onResult ->")

        assertFalse(
            "the pre-fix collapse must be gone -- addSource must not reduce the outcome to a bare Boolean",
            block.contains("repository.addSource(rootUri, role).outcome == ScanOutcome.Healthy }"),
        )
        assertTrue(
            "addSource must capture the whole ScanResult once and pass its outcome on as scanOutcome",
            block.contains("val result = repository.addSource(rootUri, role)") &&
                block.contains("val outcome = result.outcome") &&
                block.contains("SourceOperationOutcome(libraryChanged = outcome == ScanOutcome.Healthy, scanOutcome = outcome)"),
        )
        assertTrue(
            "addSource must publish the summary -- capturing the result and then dropping everything " +
                "but the outcome is the defect D1-R20 closed",
            block.contains("LastScanSummaryState.record(result)"),
        )
    }

    @Test
    fun `runSourceOperation is widened past a bare Boolean`() {
        val source = coordinatorSource()
        assertTrue(
            "runSourceOperation's operation parameter must carry the outcome, not just a Boolean",
            source.contains("operation: suspend () -> SourceOperationOutcome"),
        )
        assertFalse(
            "the old bare-Boolean signature must be gone",
            source.contains("operation: suspend () -> Boolean"),
        )
    }

    // ── Healthy siblings unchanged: rescanAll/removeAdditionalSource never set scanOutcome, so they
    // cannot touch sourceReadFailureMessage -- this lane is local to C1/C2, not a four-site rewrite. ──

    @Test
    fun `rescanAll and removeAdditionalSource never set scanOutcome -- they cannot touch sourceReadFailureMessage`() {
        val source = coordinatorSource()
        val rescanAllBlock = source
            .substringAfter("rescanAll = { onResult ->")
            .substringBefore("removeAdditionalSource = { rootUri, onResult ->")
        val removeBlock = source
            .substringAfter("removeAdditionalSource = { rootUri, onResult ->")
            .substringBefore("sourceOperationRunning = sourceOperationRunning,")

        assertFalse("rescanAll must not set scanOutcome", rescanAllBlock.contains("scanOutcome ="))
        assertFalse("removeAdditionalSource must not set scanOutcome", removeBlock.contains("scanOutcome ="))

        // Their own libraryChanged wiring is untouched by this lane.
        assertTrue(rescanAllBlock.contains("SourceOperationOutcome(libraryChanged = sourceReconcileChangedLibrary(result))"))
        assertTrue(removeBlock.contains("SourceOperationOutcome(libraryChanged = result is SourceRemovalResult.Removed)"))
    }

    // ── The healthy path is unchanged: libraryChanged is still driven by `outcome == ScanOutcome.Healthy`
    // exactly as before this lane, and a healthy scan carries no failure message. ──

    @Test
    fun `a successful scan still reports libraryChanged exactly as before, and carries no failure message`() {
        val outcome = ScanOutcome.Healthy
        val result = SourceOperationOutcome(libraryChanged = outcome == ScanOutcome.Healthy, scanOutcome = outcome)

        assertTrue("a healthy outcome must still drive libraryChanged=true, unchanged from before this lane", result.libraryChanged)
        assertNull("a healthy outcome must not carry a read-failure message", librarySourceReadFailureMessage(result.scanOutcome!!))
    }
}

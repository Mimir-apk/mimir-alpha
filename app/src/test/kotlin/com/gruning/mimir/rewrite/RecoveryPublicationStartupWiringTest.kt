package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Startup recovery wiring (`app/MimirMigrationCoordinator.kt:94`):
 * the startup recovery sweep discarded `RecoveryArchiveService.recoverPendingPublication(...)`'s
 * `RecoveryPublicationResult?` as a bare statement inside a `LaunchedEffect(Unit)` that runs on every
 * app start. It only produces a non-null result when a prior recovery-archive export was interrupted by
 * a crash/kill (`null` = nothing pending, by far the common case, and stays silent); a non-null result
 * settles whether the player's last backup attempt can be trusted, which the interactive export path
 * already tells them via `recoveryExportToastCopy`. Zero new copy: this reuses that function verbatim.
 *
 * Standing up `RecoveryArchiveService`'s full journal/port/staged-archive machinery to force each
 * verdict through this specific `LaunchedEffect` has no Robolectric harness in this module (the same
 * accepted gap as `SessionMutexRefusalWiringTest`/`MediaMenuHonestyCoordinatorWiringTest`), so this pins
 * the coordinator wiring as source characterization instead.
 */
class RecoveryPublicationStartupWiringTest {

    private fun startupEffectBlock(): String =
        sourceFile("MimirMigrationCoordinator.kt")
            .substringAfter("LaunchedEffect(Unit) {")
            .substringBefore("val close = {")

    @Test
    fun theResultIsBoundNotDiscarded() {
        assertTrue(
            "recoverPendingPublication's result must be bound to a val, not called as a bare statement",
            startupEffectBlock().contains(
                "val recovered = RecoveryArchiveService.recoverPendingPublication(",
            ),
        )
    }

    @Test
    fun aNonNullResultToastsTheSharedExportSentenceLongForUnverified() {
        val block = startupEffectBlock()
        assertTrue(
            "a recovered (non-null) result must toast recoveryExportToastCopy(recovered)",
            block.contains("if (recovered != null) {") &&
                block.contains("recoveryExportToastCopy(recovered)"),
        )
        assertTrue(
            "WRITTEN_UNVERIFIED must get the long toast, matching the interactive export path",
            block.contains("RecoveryPublicationVerdict.WRITTEN_UNVERIFIED") &&
                block.contains("Toast.LENGTH_LONG"),
        )
    }

    @Test
    fun theSweepsStillRunOnEveryStart() {
        // Healthy-path pin: the fix must not disturb the existing stale-staging/import sweeps that
        // already ran unconditionally before this fix.
        val block = startupEffectBlock()
        assertTrue(block.contains("RecoveryArchiveService.sweepStaleStaging("))
        assertTrue(block.contains("RecoveryRestoreService.sweepStaleImports("))
    }

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}

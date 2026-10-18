package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N5 site #5 (`app/MainActivity.kt:1217`):
 * `moveSecondaryAppToPrimary`'s `CompanionRunningAppState.stageTransfer(transferToken, runningApp) ?:
 * return@launch` (an `if (!producer(...)) return` in source) discarded the refusal in total silence,
 * unlike its sibling three lines below (`swap?.dispatchAccepted != true`), which toasts "Couldn’t move
 * ${runningApp.label} to the main screen" on the exact same "the move did not happen" outcome.
 *
 * `stageTransfer` refuses for two structurally distinct reasons (a staging-window collision,
 * `CompanionRunningAppState.kt:82-88`, or the session mutex, `:96-105`) that its plain `Boolean` return
 * cannot distinguish. Differentiating them would need a typed result on `stageTransfer` itself, in
 * `CompanionRunningAppState.kt` -- a second file, outside this fix's scope: the brief for this exact
 * site calls `MainActivity.kt` a shared seam and says to stop and report rather than widen into another
 * file. This fix stays inside `MainActivity.kt` and reuses the adjacent arm's sentence verbatim; true
 * differentiation is reported as owed, not attempted here.
 *
 * `moveSecondaryAppToPrimary` runs inside a `lifecycleScope.launch {}` closure on a real `Activity` this
 * module has no Robolectric harness to construct (the same accepted gap
 * `MainActivityPerformanceOverlayResumeWiringTest` documents), so this pins the wiring as source
 * characterization instead of a behavioural test.
 */
class CompanionRunningAppTransferStageFailureWiringTest {

    /** The whole `moveSecondaryAppToPrimary` function body. */
    private fun moveSecondaryAppToPrimaryBody(): String =
        sourceFile("MainActivity.kt")
            .substringAfter("private fun moveSecondaryAppToPrimary(")
            .substringAfter("runningAppTransferJob = lifecycleScope.launch {")
            .substringBefore("val swap = registry.swapToPrimary(packageName, transferToken)")

    @Test
    fun aRefusedStageTransferNoLongerReturnsInTotalSilence() {
        val body = moveSecondaryAppToPrimaryBody()

        assertTrue(
            "the stageTransfer refusal must toast before returning, reusing the adjacent arm's exact " +
                "sentence -- was `if (!CompanionRunningAppState.stageTransfer(transferToken, " +
                "runningApp)) return@launch` with nothing in the block at all",
            body.contains("if (!CompanionRunningAppState.stageTransfer(transferToken, runningApp)) {") &&
                body.contains("Couldn’t move \${runningApp.label} to the main screen") &&
                body.contains("return@launch"),
        )
    }

    @Test
    fun theDispatchFailureSiblingArmIsUnchangedAndStillReachable() {
        // Healthy/sibling-path pin: this fix must not disturb the existing dispatch-failure toast this
        // site now deliberately mirrors -- it lives just past this function's extraction window, so its
        // continued presence (not its exact body) is what this test can assert without re-including it.
        val fullSource = sourceFile("MainActivity.kt")
        assertTrue(
            fullSource.contains("if (swap?.dispatchAccepted != true) {") &&
                fullSource.contains("CompanionRunningAppState.cancelTransfer(transferToken)"),
        )
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

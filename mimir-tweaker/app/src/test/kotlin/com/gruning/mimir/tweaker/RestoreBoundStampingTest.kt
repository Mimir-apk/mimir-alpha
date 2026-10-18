package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Restore stamping consistency:
 * every `transaction.restoreBound(...)` call in the service must go through the one stamped
 * helper, `restoreBoundStamped` (next to `stampVendorFloorLearningSuppression`), and the legacy
 * `transaction.restore()` call must stamp the same way. Before this, only two of seven
 * `restoreBound` sites stamped (the transition, and the select path's restore lambda -- the
 * earlier select-path fix); five siblings (the supervisor `restorer`,
 * STARTUP_RECOVERY_RESTORE, OWNER_DEATH_RESTORE, `restoreOwnedSession`,
 * `rejectStagedFanOwnership`) and the legacy `restore()` did not, so a Guardian restore from a
 * MANUAL/SPORT session on any of those paths went unsuppressed. Source-text pin --
 * `MimirTweakerService` has no host-test seam for `transaction`/`journal` today (same gap
 * `TransitionManualRollbackTest`, `SelectPathManualFanEntryTest`, and `FanManualReentryTest`
 * document for their own neighbouring functions).
 */
class RestoreBoundStampingTest {

    @Test
    fun `restore helper stamps failed and cross-store outcomes that may have written the fan`() {
        val source = mainSourceText("MimirTweakerService.kt").lineSequence()
            .joinToString("\n") { it.substringBefore("//") }
        val helper = source.substringAfter("private fun restoreBoundStamped(")
            .substringBefore("private fun fanTransitionJournalBound(")
        assertTrue(helper.contains("if (it.restored || it.code == \"RESTORE_FAILED\" ||"))
        assertTrue(helper.contains("it.code == \"CROSS_STORE_RECONCILIATION_REQUIRED\""))
        assertTrue(helper.contains("stampVendorFloorLearningSuppression()"))
    }

    @Test
    fun `internal coordinator restores are wired to the daemon suppression clock`() {
        val source = mainSourceText("MimirTweakerService.kt").lineSequence()
            .joinToString("\n") { it.substringBefore("//") }
        val constructor = source.substringAfter("transaction = TransactionCoordinator(")
            .substringBefore("supervisor = HardwareSessionSupervisor(")
        assertTrue(constructor.contains("onRestoreAttemptFinished = ::stampVendorFloorLearningSuppression"))
    }

    @Test
    fun `transaction restoreBound( appears exactly once in the whole service -- inside restoreBoundStamped`() {
        val text = mainSourceText("MimirTweakerService.kt")
        val occurrences = Regex(Regex.escape("transaction.restoreBound(")).findAll(text).count()
        assertEquals(
            "transaction.restoreBound( must appear exactly once in the whole service -- inside " +
                "restoreBoundStamped's own body; every other call site must go through the helper. " +
                "Found $occurrences occurrence(s).",
            1,
            occurrences,
        )
    }

    @Test
    fun `restoreBoundStamped appears exactly seven times -- one definition plus six restore call sites`() {
        val text = mainSourceText("MimirTweakerService.kt")
        val occurrences = Regex(Regex.escape("restoreBoundStamped(")).findAll(text).count()
        assertEquals(
            "restoreBoundStamped( must appear exactly 7 times: one function definition plus all " +
                "six call sites (the supervisor restorer, STARTUP_RECOVERY_RESTORE, " +
                "OWNER_DEATH_RESTORE, restoreOwnedSession, " +
                "rejectStagedFanOwnership, and the select path's restore lambda). Found " +
                "$occurrences occurrence(s).",
            7,
            occurrences,
        )
    }

    @Test
    fun `the legacy transaction restore() call site is the only one, and it stamps`() {
        val text = mainSourceText("MimirTweakerService.kt")
        val occurrences = Regex(Regex.escape("transaction.restore(")).findAll(text).count()
        assertEquals(
            "transaction.restore( (the legacy, no-args restore) must appear exactly once in the service",
            1,
            occurrences,
        )
        val restoreIndex = text.indexOf("transaction.restore()")
        assertTrue("the legacy transaction.restore() call site must exist", restoreIndex >= 0)
        val tail = text.substring(restoreIndex, minOf(text.length, restoreIndex + 400))
        assertTrue(
            "the legacy transaction.restore() call must stamp floor-learning suppression the " +
                "same way restoreBoundStamped does, guarded on .restored",
            tail.contains("stampVendorFloorLearningSuppression()"),
        )
    }

    private fun mainSourceText(name: String): String {
        val root = sequenceOf(
            File("src/main/kotlin/com/gruning/mimir/tweaker"),
            File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker"),
        ).firstOrNull(File::isDirectory) ?: throw AssertionError("main source root unavailable")
        val file = File(root, name)
        if (!file.isFile) throw AssertionError("$name not found under $root")
        return file.readText()
    }
}

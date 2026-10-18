package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR171 review, DEFECT 2 — `closeTomeSurface` (`MimirAppInputDispatch.kt`) publishes
 * `bindings.navigation.homeTomeLibrary = returnState` BEFORE `closeTomeToLibrary` runs (CP02: publishing before the close removes an intermediate blank/default-index frame while the
 * Folio closes -- see the function's own comment). If `closeTomeToLibrary` fails, the function correctly
 * rolls that publish back (`bindings.navigation.homeTomeLibrary = null; return false`). If it SUCCEEDS
 * but the following `enterHomeTomeScope(...)` returns false, the function did not: it fell straight to
 * `return false` with the Tome-library overlay state still published and the Folio already gone
 * (`closeTomeToLibrary` succeeded) -- a surface composed with no focus scope entered, while the caller
 * reads the `false` return as "nothing happened".
 *
 * `closeTomeSurface` is a plain (non-`@Composable`) private function taking a `MimirAppBindings` built
 * from ~15 Compose-only coordinators (`HomeShelfCoordinator` alone is ~2300 lines of inlined `remember`
 * state) that cannot be constructed off a real composition in a JVM unit test. This file's own house
 * pattern for exactly that situation is a source-contract test on the actual function body rather than a
 * runtime fixture -- see `MButtonQuickLaunchInPlaceContractTest.kt`, same package, same technique.
 */
class HomeTomeSurfaceCloseRollbackTest {

    @Test
    fun `both closeTomeSurface failure branches roll back the published homeTomeLibrary state`() {
        val body = closeTomeSurfaceBody()

        val closeFailureBranch = body.sliceBetween(
            "if (!bindings.homeShelf.closeTomeToLibrary(arrivalFence)) {",
            "if (!enterHomeTomeScope(",
        )
        assertTrue(
            "closeTomeToLibrary's own failure branch (the correct sibling) must still null the publish",
            closeFailureBranch.contains("bindings.navigation.homeTomeLibrary = null"),
        )

        val enterFailureBranch = body.substringAfter("if (!enterHomeTomeScope(")
        assertTrue(
            "enterHomeTomeScope failing must roll back the SAME publish its sibling rolls back -- " +
                "otherwise the Folio is gone, the Tome-library overlay stays composed with no focus " +
                "scope entered, and the caller reads false as \"nothing happened\"",
            enterFailureBranch.contains("bindings.navigation.homeTomeLibrary = null"),
        )
    }

    private fun closeTomeSurfaceBody(): String =
        sourceFile("MimirAppInputDispatch.kt").sliceBetween(
            "internal fun closeTomeSurface(bindings: MimirAppBindings): Boolean {",
            "internal fun mappedGlobalMButtonCommand(",
        )

    /** Presence-checked slice: a missing anchor fails loudly instead of degrading to the whole input. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", contains(before))
        return substringAfter(after).substringBefore(before)
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

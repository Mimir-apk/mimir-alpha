package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-5 B1 wiring, read as source because neither call site can run in a unit test.
 *
 * The liveness fix is only as real as its call sites and its seam, and the seam has a specific trap. The
 * published close authority is the composite [CertifiedPrivilegeGateway], which routes stop, eviction and
 * task lookup but has no liveness route, so it answers the interface's UNKNOWN for every package. A Tabs
 * reconcile wired through it would compile, log `unknown=<n>`, and retire nothing, ever.
 */
class CompanionTabsLivenessWiringTest {
    @Test
    fun `the Tabs request reconciles liveness inside its IO hop before it reads the entries`() {
        val lines = tabsHandler().lines().map(String::trim)
        val hop = lines.indexOf("secondaryTabIconPaths = withContext(Dispatchers.IO) {")
        val reconcile = lines.indexOf("secondaryTaskRegistry?.reconcileLiveness()")
        val icons = lines.indexOf("loadSecondaryTabIconPaths()")
        val entries = lines.indexOfFirst { it.startsWith("val entries = secondaryTaskRegistry?.state?.value") }

        assertTrue("the IO hop must open before the reconcile", hop >= 0 && reconcile > hop)
        assertTrue("the reconcile must run inside the hop, before the icon read", icons > reconcile)
        assertTrue("the entries must be read after the hop returns", entries > icons)
    }

    @Test
    fun `the liveness seam asks the vendor authority, never the composite close authority`() {
        val runtime = appSource("CompanionProcessRuntime.kt")
        val lines = runtime.lines().map(String::trim)

        assertTrue(
            "the registry's liveness seam must read the vendor holder",
            lines.contains("when (verifiedLivenessAuthority.get()?.packageLiveness(pkg)) {"),
        )
        assertTrue(
            "the vendor gateway, not the composite, must be published into that holder",
            lines.contains("verifiedLivenessAuthority.set(vendorUtility)"),
        )
        assertTrue(
            "restored tabs must be reconciled once when the authority is published",
            lines.contains("processScope.launch(Dispatchers.IO) { registry.reconcileLiveness() }"),
        )
        assertFalse(
            "the composite answers UNKNOWN for every package; a seam through it retires nothing",
            runtime.contains("verifiedCloseAuthority.get()?.packageLiveness"),
        )
    }

    @Test
    fun `a game row is spared while its launch starts and retires with what its close settles`() {
        // Round 5b (Thor, 2026-09-12): GAME rows are asked about now. The seam must read the session's own
        // in-flight launch, and a retired GAME row must reach the same session end the row's close button runs.
        val lines = appSource("CompanionProcessRuntime.kt").lines().map(String::trim)

        assertTrue(
            "the registry must read the session's in-flight Bottom game launch",
            lines.contains(
                "bottomGameLaunchInFlight = { pkg -> MimirSessionAuthority.state.value.bottomGameLaunchInFlightFor(pkg) },",
            ),
        )
        val retiredHook = appSource("CompanionProcessRuntime.kt")
            .substringAfter("onGameTabsRetired = { retired, stillTracked ->", "")
            .substringBefore("onSecondaryHostLaunchStarting =")
        val end = retiredHook.indexOf("MimirCompanionRuntimeSessionBridge.endBottomGameForTabClose(")
        val gate = retiredHook.indexOf("if (ended == null) {")
        val orphan = retiredHook.indexOf("BottomOrphanPlayTime.finishAtLastActivity(context, row, retired, stillTracked)")
        assertTrue(
            "a retired GAME row must end its Bottom game session through the close button's own settlement",
            end >= 0 && retiredHook.contains("endedBy = \"tabs liveness\","),
        )
        // O14 : a dead process's measurement is reached only for a row this process ended no live
        // session for; a live session's own cleanup finishes its measurement exactly as the close button's does.
        assertTrue("the orphan finish must be gated on no session ended", gate > end && orphan > gate)
    }

    private fun tabsHandler(): String = appSource("MainActivity.kt")
        .substringAfter("QuickMenuItem.SECONDARY_TABS -> {")
        .substringBefore("QuickMenuItem.SECONDARY_RETURN -> {")

    private fun appSource(name: String): String =
        listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("source file not found from ${File(".").absolutePath}")
}

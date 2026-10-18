package com.gruning.mimir.rewrite

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `entries` starts empty in every process, so "no apps installed" and "nobody has swept yet" were the same
 * observation for every consumer. The picker was the only surface that needed to tell them apart, and it
 * did so by calling `refresh()` itself and treating the return as the signal — which meant asking the
 * question cost a PackageManager sweep even when the dock's own refresh had already completed.
 *
 * WHAT IS PINNED HERE, and what is not:
 *
 * [PINNED, runtime] A completed `refresh` reports `hasLoaded == true`, and `entries` is already readable
 * at that moment — the write ordering the flag's contract depends on. Reverting the `mutableHasLoaded`
 * assignment turns `a completed refresh reports itself as loaded` red immediately.
 *
 * [NOT PINNED, reasoned] "A refresh that THROWS leaves `hasLoaded` false." That is structural — the
 * assignment sits after a suspending call, so a throw cannot reach it — but `refresh`'s failure path runs
 * inside `loadNativeShelfSnapshotCoalesced` and this test has no seam to make it throw. Stated rather than
 * faked.
 *
 * ORDER-INDEPENDENCE: this file deliberately does NOT assert `hasLoaded == false` up front.
 * `CompanionQuickLaunchCatalog` is a process-global object sharing Robolectric's per-worker classloader,
 * and the flag is monotonic — any earlier test in the same worker that swept would make that assertion
 * fail for a reason unrelated to this change. A prior refresh would otherwise make the test depend on execution order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CompanionQuickLaunchCatalogHasLoadedTest {

    @Test
    fun `a completed refresh reports itself as loaded`() = runBlocking {
        CompanionQuickLaunchCatalog.refresh(RuntimeEnvironment.getApplication())

        assertTrue(
            "refresh completed, so the catalog's emptiness is now an ANSWER rather than a starting value",
            CompanionQuickLaunchCatalog.hasLoaded.value,
        )
    }

    @Test
    fun `an empty catalog that has swept is loaded, which is the whole distinction`() = runBlocking {
        // Robolectric's application has no launchable packages, so this sweep legitimately finds nothing.
        // That state — empty AND loaded — is precisely the one `entries` alone could never express, and
        // the one the picker needs in order to say "No apps found." honestly.
        CompanionQuickLaunchCatalog.refresh(RuntimeEnvironment.getApplication())

        assertTrue(CompanionQuickLaunchCatalog.entries.value.isEmpty())
        assertTrue(
            "an empty-but-swept catalog must read as LOADED — implementing this flag as a synonym for " +
                "`entries.isNotEmpty()` would report false here and re-create the defect",
            CompanionQuickLaunchCatalog.hasLoaded.value,
        )
    }
}

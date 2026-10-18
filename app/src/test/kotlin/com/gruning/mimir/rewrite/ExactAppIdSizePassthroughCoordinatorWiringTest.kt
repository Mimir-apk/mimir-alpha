package com.gruning.mimir.rewrite

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-text characterization of Size-filter propagation into the coordinator call.
 *
 * The fetcher half of that change is pinned by compilation: `CustomHeroCandidateTest` calls
 * `steamGridDbHeroesForSteamAppId` with named `size`/`fit`, which cannot resolve against the old
 * two-parameter signature. The coordinator half had **no pin at all** — reverting just the call site back
 * to two positional arguments still compiled, left the whole suite green, and fully restored the bug
 * (Size = HD or 2K+ with an untouched query fetching heroes that were never dimension-pre-filtered).
 * Compilation alone does not cover that call-site regression.
 *
 * This follows the same convention as [LibraryHeroPickerCoordinatorWiringTest] and the other coordinator
 * wiring tests: closures captured inside one large `@Composable` with no Robolectric harness cannot be
 * invoked from a unit test, so the wiring is pinned by reading the source it compiles from.
 *
 * Differential: revert the call at `MimirAppCoordinator.kt` to
 * `steamGridDbHeroesForSteamAppId(steamGridDbKey, exactAppId)` and this test fails.
 */
class ExactAppIdSizePassthroughCoordinatorWiringTest {

    private fun coordinatorSource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppCoordinator.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppCoordinator.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("MimirAppCoordinator.kt not found from ${File(".").absolutePath}")
    }

    /** The argument list of the exact-id call, and only that. */
    private fun exactAppIdCallArguments(): String = coordinatorSource()
        .substringAfter("CustomHeroFetcher.steamGridDbHeroesForSteamAppId(")
        .substringBefore(")")

    @Test
    fun theExactAppIdRouteIsGivenThePickersOwnSizeAndFit() {
        val arguments = exactAppIdCallArguments()

        // Guard the search window itself: a slice that missed the call would make every assertion below
        // pass vacuously. Three vacuous test windows shipped in this project in one week.
        assertTrue(
            "search window did not find the exact-id call -- this test would pass on an empty slice; " +
                "found: <$arguments>",
            arguments.contains("steamGridDbKey") && arguments.contains("exactAppId"),
        )
        assertTrue(
            "the exact-id route must pre-filter on the Size he picked, not the hardcoded default -- " +
                "native PC artwork lookup must preserve size arguments; found: <$arguments>",
            arguments.contains("size = size"),
        )
        assertTrue(
            "Fit rides through with Size so the exact route filters identically to the title route; " +
                "found: <$arguments>",
            arguments.contains("fit = fit"),
        )
    }
}

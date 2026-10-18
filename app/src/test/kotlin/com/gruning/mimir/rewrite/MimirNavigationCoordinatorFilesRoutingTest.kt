package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the dock end-cap's routing (product interaction contract):
 * FILES must route through the SAME function the Utility "Files" row calls
 * (`AppBrightnessCoordinator.openFilesManager()`, wired at `AppBrightnessCoordinator.kt` ~:750/:2077)
 * -- one policy call, one surface construction, never a re-implemented route. `MimirNavigationCoordinator`
 * is not JVM-constructible (house pattern, see `MButtonQuickLaunchInPlaceContractTest`), so this is a
 * bounded source-text pin.
 */
class MimirNavigationCoordinatorFilesRoutingTest {

    @Test
    fun `FILES routes through the same openFilesManager the Utility row calls, not a new code path`() {
        val rail = navigationCoordinatorSource()
            .sliceBetween("fun onShelfQuickAction(", "fun openSettingsFromCurrent(")

        assertTrue(
            "FILES must call the shared policy function, not re-implement the route -- body was:\n$rail",
            rail.contains("MimirQuickRailAction.FILES -> brightness.openFilesManager()"),
        )
    }

    /** Presence-checked slice: a missing anchor fails loudly instead of degrading to the whole input. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", contains(before))
        return substringAfter(after).substringBefore(before)
    }

    private fun navigationCoordinatorSource(): String = sourceFile("MimirNavigationCoordinator.kt")

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}

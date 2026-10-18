package com.gruning.mimir.rewrite.feature.files

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R6-F-09, found by the reviewer of R6-P2-07/R6-P3-03's Files search rework (now merged as 46a4cf2b),
 * 2026-09-15.
 *
 * `FileBrowserController.setSearchFilter`'s own comment already claimed, at d7e306f3, that
 * "re-confirming the active chip re-runs the walk". Nothing kept that promise. A re-press writes the
 * SAME [com.gruning.mimir.rewrite.core.files.FileSearchFilter] value back to `searchFilter`, and
 * Compose's snapshot state (`structuralEqualityPolicy`, the default `mutableStateOf` uses) does not
 * notify a reader of a write whose new value equals the old one. `FileBrowserSearchSurface`'s
 * `FileBrowserSearchCollector` reads `controller.searchFilter` into its `LaunchedEffect` key list, so a
 * re-press left that key list bitwise identical to the previous composition and the collector never
 * reran. `resetSearchResults` had already cleared `searchResults` to empty on the way in; nothing then
 * repopulated it. The player is left looking at an empty list until the query or the chip genuinely
 * changes.
 *
 * **A source-text pin, and why that is the honest instrument here.** The actual defect is a
 * `LaunchedEffect` failing to restart — visible only through Compose recomposition, which this module
 * configures no harness for (no Robolectric, no `createComposeRule`; `FileBrowserSupportDimRenderTest`
 * and `FileBrowserSearchGeometryTest` disclose the identical limit for files in this same package). So
 * this asserts the fix's actual shape instead of the recomposition it produces: [setSearchFilter] must
 * change something OTHER than `searchFilter` on every call, re-press included — a counter in the same
 * idiom this class already uses for `navigationSequence` — and the collector's `LaunchedEffect` must key
 * on that same counter, or bumping it in the controller buys the player nothing.
 *
 * **What this does NOT prove:** that the walk's `Flow` actually restarts and repopulates the screen on
 * hardware. That is the acceptance test, and hardware is still the gate.
 */
class FileBrowserSearchChipRestartTest {

    @Test
    fun `re-pressing the active chip must change something the search collector's restart key reads`() {
        val setSearchFilterBody = featureSource("FileBrowserController.kt").sliceBetween(
            after = "internal fun setSearchFilter(filter: FileSearchFilter) {",
            before = "\n    }",
        )
        assertTrue(
            "setSearchFilter must bump a counter field on EVERY call, re-press of the already-active " +
                "chip included -- writing the same FileSearchFilter value back to `searchFilter` does " +
                "not, by itself, change anything a Compose reader can observe, so something else must. " +
                "Looked for a `<name>Token++` / `<name>Sequence++` / `<name>Generation++` bump (the " +
                "idiom this class already uses for `navigationSequence`) inside setSearchFilter's own " +
                "body:\n$setSearchFilterBody",
            Regex("""\w+(Token|Sequence|Generation)\+\+""").containsMatchIn(setSearchFilterBody),
        )

        val collectorBody = featureSource("FileBrowserSearchSurface.kt").sliceBetween(
            after = "private fun FileBrowserSearchCollector(",
            before = "\n// ",
        )
        assertTrue(
            "the collector's own LaunchedEffect must be keyed on that same counter, or bumping it in " +
                "the controller restarts nothing -- looked for LaunchedEffect(...Token...) inside " +
                "FileBrowserSearchCollector's body:\n$collectorBody",
            Regex("""LaunchedEffect\([^)]*[Tt]oken[^)]*\)""").containsMatchIn(collectorBody),
        )
    }

    /** Presence-checked slice: a missing anchor fails loudly instead of silently degrading to "". */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        val start = indexOf(after) + after.length
        val end = indexOf(before, start)
        assertTrue("anchor missing after the start of '$after': $before", end > start)
        return substring(start, end)
    }

    private fun featureSource(name: String): String {
        val relative = "src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$name"
        val candidates = listOf(File(relative), File("feature-files/$relative"), File("../feature-files/$relative"))
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}

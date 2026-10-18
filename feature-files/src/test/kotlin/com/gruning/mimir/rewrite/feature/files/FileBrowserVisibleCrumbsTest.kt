package com.gruning.mimir.rewrite.feature.files

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The trail lost the current location. The header's crumb Row takes `weight(1f)` and
 * runs underneath a telemetry pill another layer centres on top of it, so the crumb that got covered was
 * the TAIL — the only one saying where you are. The trail therefore elides from the left:
 * `… › Internal Storage › Podcasts`.
 */
class FileBrowserVisibleCrumbsTest {

    private fun crumbs(vararg names: String) =
        names.mapIndexed { i, n -> FileBrowserCrumb(n, "/p/" + names.take(i + 1).joinToString("/")) }

    @Test
    fun `the current folder always survives, and the ancestors become one ellipsis`() {
        val trimmed = fileBrowserVisibleCrumbs(
            crumbs("Drives", "emulated", "Internal Storage", "Podcasts"),
        )

        assertEquals(
            listOf(FileBrowserCrumbEllipsis, "Internal Storage", "Podcasts"),
            trimmed.map { it.label },
        )
    }

    @Test
    fun `a trail that already fits is returned untouched`() {
        val short = crumbs("Drives", "Thor")
        assertEquals(short, fileBrowserVisibleCrumbs(short))
        assertEquals(crumbs("Drives"), fileBrowserVisibleCrumbs(crumbs("Drives")))
        assertEquals(emptyList<FileBrowserCrumb>(), fileBrowserVisibleCrumbs(emptyList()))
    }

    @Test
    fun `the ellipsis carries the deepest hidden crumb's path, not a decorative string`() {
        val full = crumbs("Drives", "emulated", "Internal Storage", "Podcasts")
        val trimmed = fileBrowserVisibleCrumbs(full)

        assertEquals(
            "so anything that later makes the trail navigable has a real target rather than a label",
            full[1].path,
            trimmed.first().path,
        )
    }

    @Test
    fun `a deep trail still shows exactly the tail`() {
        val trimmed = fileBrowserVisibleCrumbs(
            crumbs("Drives", "Thor", "Roms", "GBA", "Pokemon", "Saves"),
        )

        assertEquals(3, trimmed.size)
        assertEquals(listOf(FileBrowserCrumbEllipsis, "Pokemon", "Saves"), trimmed.map { it.label })
    }
}

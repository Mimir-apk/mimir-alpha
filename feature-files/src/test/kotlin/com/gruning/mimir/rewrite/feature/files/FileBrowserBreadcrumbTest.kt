package com.gruning.mimir.rewrite.feature.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FileBrowserBreadcrumbTest {

    @Test
    fun `at the root, the trail is one crumb -- the root's own basename`() {
        val crumbs = fileBrowserBreadcrumbs(rootPath = "/storage/emulated/0/Roms", currentPath = "/storage/emulated/0/Roms")

        assertEquals(listOf(FileBrowserCrumb("Roms", "/storage/emulated/0/Roms")), crumbs)
    }

    @Test
    fun `one level down adds exactly one crumb, navigable to its own full path`() {
        val crumbs = fileBrowserBreadcrumbs(rootPath = "/root", currentPath = "/root/GBA")

        assertEquals(
            listOf(FileBrowserCrumb("root", "/root"), FileBrowserCrumb("GBA", "/root/GBA")),
            crumbs,
        )
    }

    @Test
    fun `multiple levels down produce one crumb per segment, each accumulating the full path so far`() {
        val crumbs = fileBrowserBreadcrumbs(rootPath = "/root", currentPath = "/root/Roms/GBA/Golden Sun")

        assertEquals(
            listOf(
                FileBrowserCrumb("root", "/root"),
                FileBrowserCrumb("Roms", "/root/Roms"),
                FileBrowserCrumb("GBA", "/root/Roms/GBA"),
                FileBrowserCrumb("Golden Sun", "/root/Roms/GBA/Golden Sun"),
            ),
            crumbs,
        )
    }

    @Test
    fun `a root with a trailing slash does not produce a doubled slash in later crumbs`() {
        val crumbs = fileBrowserBreadcrumbs(rootPath = "/root/", currentPath = "/root/GBA")

        assertEquals("/root/GBA", crumbs.last().path)
    }

    // ── crumbLabels: the seam a real volume name lands on ──

    @Test
    fun `a supplied label replaces the root crumb's basename but never its navigable path`() {
        val crumbs = fileBrowserBreadcrumbs(
            rootPath = "/storage/691E-7BEA",
            currentPath = "/storage/691E-7BEA/Roms",
            crumbLabels = mapOf("/storage/691E-7BEA" to "SD card"),
        )

        assertEquals(
            listOf(
                FileBrowserCrumb("SD card", "/storage/691E-7BEA"),
                FileBrowserCrumb("Roms", "/storage/691E-7BEA/Roms"),
            ),
            crumbs,
        )
    }

    @Test
    fun `a DESCENDANT volume crumb can be relabelled too -- the case a root-only override cannot reach`() {
        // core-files' StorageVolumeListing.browserRoot() returns "/storage" whenever the device has more
        // than one volume -- the Odin, which has an SD card. The volume is then a CHILD crumb,
        // and the raw trail reads "storage › 1A2B-3C4D › Roms". This is the whole reason the seam is a
        // path-keyed map instead of the single rootLabel parameter that was written first.
        val crumbs = fileBrowserBreadcrumbs(
            rootPath = "/storage",
            currentPath = "/storage/1A2B-3C4D/Roms",
            crumbLabels = mapOf("/storage" to "Drives", "/storage/1A2B-3C4D" to "SD Card"),
        )

        assertEquals(listOf("Drives", "SD Card", "Roms"), crumbs.map { it.label })
        assertEquals(
            listOf("/storage", "/storage/1A2B-3C4D", "/storage/1A2B-3C4D/Roms"),
            crumbs.map { it.path },
        )
    }

    @Test
    fun `a label is matched when the PATH carries a trailing slash`() {
        val crumbs = fileBrowserBreadcrumbs(
            rootPath = "/storage/emulated/0/",
            currentPath = "/storage/emulated/0/Roms",
            crumbLabels = mapOf("/storage/emulated/0" to "Internal storage"),
        )

        assertEquals(listOf("Internal storage", "Roms"), crumbs.map { it.label })
    }

    @Test
    fun `a label is matched when the KEY carries a trailing slash -- the side that silently missed`() {
        // The lookup originally trimmed only the path, so a key-side slash never matched and the crumb
        // fell back to its basename -- indistinguishable from "no label was supplied". Since the filler
        // of this map is a caller reading mount points out of StorageVolume, either form can arrive.
        val crumbs = fileBrowserBreadcrumbs(
            rootPath = "/storage",
            currentPath = "/storage/1A2B-3C4D/Roms",
            crumbLabels = mapOf("/storage/" to "Drives", "/storage/1A2B-3C4D/" to "SD Card"),
        )

        assertEquals(listOf("Drives", "SD Card", "Roms"), crumbs.map { it.label })
    }

    @Test
    fun `a blank label falls back to the basename rather than rendering an empty crumb`() {
        // A Context-backed volume lookup that finds nothing returns "" at least as readily as null.
        val crumbs = fileBrowserBreadcrumbs(
            rootPath = "/root/Roms",
            currentPath = "/root/Roms",
            crumbLabels = mapOf("/root/Roms" to ""),
        )

        assertEquals(listOf(FileBrowserCrumb("Roms", "/root/Roms")), crumbs)
    }

    @Test
    fun `an unrelated label never leaks onto a crumb it does not key`() {
        val crumbs = fileBrowserBreadcrumbs(
            rootPath = "/root",
            currentPath = "/root/Roms",
            crumbLabels = mapOf("/somewhere/else" to "Should never appear"),
        )

        assertEquals(listOf("root", "Roms"), crumbs.map { it.label })
    }

    @Test
    fun `an empty label map reproduces the pre-existing basename behaviour exactly`() {
        assertEquals(
            fileBrowserBreadcrumbs(rootPath = "/root", currentPath = "/root/Roms/GBA"),
            fileBrowserBreadcrumbs(rootPath = "/root", currentPath = "/root/Roms/GBA", crumbLabels = emptyMap()),
        )
    }

    @Test
    fun `the raw trails StorageVolumeListing produces today are exactly what the seam exists to fix`() {
        // Characterization, not aspiration: these are the trails a player sees RIGHT NOW for each of
        // core-files' three browserRoot() outcomes, with no labels threaded. When app/ starts filling
        // crumbLabels these assertions stay true (they pass no labels); they are here so the defect is
        // recorded in executable form rather than in prose alone.
        assertEquals(
            "storage › 1A2B-3C4D › Roms › GBA",
            fileBrowserFriendlyPath("/storage", "/storage/1A2B-3C4D/Roms/GBA"),
        )
        assertEquals(
            "0 › Roms › GBA",
            fileBrowserFriendlyPath("/storage/emulated/0", "/storage/emulated/0/Roms/GBA"),
        )
    }

    // ── friendly path (the inspector's Where row) ──

    @Test
    fun `fileBrowserFriendlyPath joins the trail with the design's own separator, never the raw path`() {
        val friendly = fileBrowserFriendlyPath(
            rootPath = "/storage/emulated/0/Roms",
            currentPath = "/storage/emulated/0/Roms/GBA",
        )

        assertEquals("Roms › GBA", friendly)
        assertFalse("the raw mount path must not leak into the friendly trail", friendly.contains("/storage"))
    }

    @Test
    fun `fileBrowserFriendlyPath at the root is the single root crumb, with no separator at all`() {
        assertEquals("Roms", fileBrowserFriendlyPath(rootPath = "/root/Roms", currentPath = "/root/Roms"))
    }

    @Test
    fun `fileBrowserFriendlyPath and the header trail cannot disagree -- one is derived from the other`() {
        val rootPath = "/storage/691E-7BEA"
        val currentPath = "/storage/691E-7BEA/Roms/GBA"
        val labels = mapOf("/storage/691E-7BEA" to "SD card")
        val crumbs = fileBrowserBreadcrumbs(rootPath, currentPath, labels)

        assertEquals(
            crumbs.joinToString(" › ") { it.label },
            fileBrowserFriendlyPath(rootPath, currentPath, labels),
        )
    }
}

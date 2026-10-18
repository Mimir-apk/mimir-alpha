package com.gruning.mimir.rewrite.feature.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * File-browser transition regressions reproduced on Nova, 2026-09-29.
 * A same-folder reload dips in place, and a crumb tap counts its Ups up front so an async load cannot run it to the root.
 */
class FileBrowserWalk31ReviewTest {

    @Test
    fun `a reload where the listing stands dips in place - nothing departs, nothing travels`() {
        listOf(FileBrowserGlideEnter, FileBrowserGlideReturn).forEach { direction ->
            val reload = FileBrowserArrival(FileBrowserArrivalKind.DIRECTORY, direction, epoch = 7L, inPlace = true)
            assertFalse("$direction", fileBrowserArrivalDeparts(reload))
            assertEquals("$direction", 0f, fileBrowserArrivalTravelDp(reload), 0f)
            assertEquals("a dip, not a fade from nothing", FileBrowserGlideStartAlpha, fileBrowserArrivalStartAlpha(reload), 0f)
            assertTrue("the clock still runs, so the ring gate releases", fileBrowserArrivalAnimates(reload))
        }
        // A real ascent still departs.
        val up = FileBrowserArrival(FileBrowserArrivalKind.DIRECTORY, FileBrowserGlideReturn, epoch = 8L)
        assertTrue(fileBrowserArrivalDeparts(up))
    }

    @Test
    fun `an in-place motion reaches its arrival`() {
        val history = FileBrowserArrivalHistory()
        val screen = fileBrowserScreenOf(queueOpen = false, searchOpen = false, overlayOpen = false)
        val first = history.prepare(screen, "/v/Roms", null)
        history.commit(first, screen, null)
        val motion = FileBrowserNavigationMotion("/v/Roms", FileBrowserGlideReturn, sequence = 3L, inPlace = true)
        val arrival = history.prepare(screen, "/v/Roms", motion)
        assertEquals(FileBrowserArrivalKind.DIRECTORY, arrival.kind)
        assertTrue(arrival.inPlace)
        assertFalse(fileBrowserArrivalDeparts(arrival))
    }

    @Test
    fun `the description panel rides the listing and describes the open folder when no tile is focused`() {
        val source = listOf(
            java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/feature/files/FileBrowserSurface.kt"),
            java.io.File("feature-files/src/main/kotlin/com/gruning/mimir/rewrite/feature/files/FileBrowserSurface.kt"),
        ).first(java.io.File::isFile).readText()
        val call = source.substringAfter("val panelArrival = arrival.takeIf { it.kind == FileBrowserArrivalKind.DIRECTORY }")
            .substringBefore("FileBrowserFooterLegend(")
        val wrap = call.substringAfter("// inside a folder stays instant: the epoch does not change.").substringBefore("FileBrowserInspectorPanel(")
        assertTrue(wrap, wrap.contains("MimirPageHandoff(") && wrap.contains("departs = fileBrowserArrivalDeparts(arrival),"))
        assertTrue("it dissolves, never travels", wrap.contains("exitTravelPx = 0f,") && wrap.contains("travels = false"))
        assertTrue("walk-32: it clears first, with the listing", wrap.contains("exitMs = FileBrowserChildClearMs,"))
        assertTrue("its own ground, keyed like the listing", wrap.contains("background(palette.surfaceBase)") && wrap.contains("key(controller.currentPath) {"))
        // The glide is inside the key: a layer that survived the flip would re-record the new panel as the departing one.
        assertTrue(wrap.indexOf("key(controller.currentPath) {") < wrap.indexOf("fileBrowserArrivalGlide(panelArrival"))
        assertTrue(call.contains("tile = focusedTile ?: controller.currentDirectoryTile,"))
        assertTrue(call.contains("describesFolder = focusedTile == null,"))
        val panel = source.substringAfter("private fun FileBrowserInspectorPanel(").substringBefore("private fun InspectorFieldRow(")
        assertTrue("never actionable as the open folder", panel.contains("if (!describesFolder) Row("))
    }

    @Test
    fun `a crumb tap counts its Ups from the path, not from a listing that has not landed yet`() {
        assertEquals(2, fileBrowserCrumbUpCount("/storage/emulated/0/Roms/gba", "/storage/emulated/0"))
        assertEquals(1, fileBrowserCrumbUpCount("/storage/emulated/0/Roms/gba/", "/storage/emulated/0/Roms"))
        assertEquals(0, fileBrowserCrumbUpCount("/storage/emulated/0/Roms", "/storage/emulated/0/Roms"))
        assertEquals("not an ancestor: nothing moves", 0, fileBrowserCrumbUpCount("/storage/emulated/0/Roms", "/storage/emulated/0/Ro"))
        assertEquals("not an ancestor: nothing moves", 0, fileBrowserCrumbUpCount("/storage/emulated/0", "/storage/emulated/0/Roms"))
        assertEquals(3, fileBrowserCrumbUpCount("/storage/a/b/c", "/storage"))
    }
}

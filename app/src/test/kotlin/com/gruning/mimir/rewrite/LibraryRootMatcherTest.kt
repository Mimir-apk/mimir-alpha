package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SD03 pins: a scoped rescan is only as good as its root match. The two loops that would make the
 * feature silently do nothing are pinned first — a SAF tree URI root failing to match the plain
 * child path the file manager hands in, and a naive string-prefix match treating `RomsBackup` as
 * inside `Roms`.
 */
class LibraryRootMatcherTest {

    // -- The trap this lane exists to avoid --------------------------------------------------

    @Test
    fun `SAF tree URI root matches a plain child path`() {
        val root = "content://com.android.externalstorage.documents/tree/primary%3ARoms"
        assertEquals(
            listOf(root),
            LibraryRootMatcher.rootsContaining("/storage/emulated/0/Roms/Game.iso", listOf(root)),
        )
    }

    @Test
    fun `SAF tree URI root on an SD card matches a plain child path on that card`() {
        val root = "content://com.android.externalstorage.documents/tree/6E0E-FBB0%3AROMS"
        assertEquals(
            listOf(root),
            LibraryRootMatcher.rootsContaining("/storage/6E0E-FBB0/ROMS/snes/game.zip", listOf(root)),
        )
    }

    @Test
    fun `combined tree plus document form root still matches by its tree segment`() {
        // Precedent shape from VolumeRootMatcher: "persisted grants often carry both segments."
        val root = "content://com.android.externalstorage.documents/tree/6E0E-FBB0%3AROMS/" +
            "document/6E0E-FBB0%3AROMS%2Fsnes"
        assertEquals(
            listOf(root),
            LibraryRootMatcher.rootsContaining("/storage/6E0E-FBB0/ROMS/game.zip", listOf(root)),
        )
    }

    @Test
    fun `plain path root matches a plain child path`() {
        val root = "/storage/emulated/0/Roms"
        assertEquals(
            listOf(root),
            LibraryRootMatcher.rootsContaining("/storage/emulated/0/Roms/Game.iso", listOf(root)),
        )
    }

    // -- Segment-boundary containment, not string prefix --------------------------------------

    @Test
    fun `sibling folder sharing a text prefix is not contained`() {
        val root = "/storage/emulated/0/Roms"
        assertTrue(
            LibraryRootMatcher.rootsContaining("/storage/emulated/0/RomsBackup/Game.iso", listOf(root)).isEmpty(),
        )
    }

    @Test
    fun `path equal to the root itself is contained`() {
        val root = "/storage/emulated/0/Roms"
        assertEquals(listOf(root), LibraryRootMatcher.rootsContaining(root, listOf(root)))
    }

    @Test
    fun `trailing slash on the root does not change the answer`() {
        val root = "/storage/emulated/0/Roms/"
        assertEquals(
            listOf(root),
            LibraryRootMatcher.rootsContaining("/storage/emulated/0/Roms/Game.iso", listOf(root)),
        )
    }

    @Test
    fun `trailing slash on the path does not change the answer`() {
        val root = "/storage/emulated/0/Roms"
        assertEquals(listOf(root), LibraryRootMatcher.rootsContaining("/storage/emulated/0/Roms/", listOf(root)))
    }

    // -- Nested roots: decision is "return all, most-specific first", not implicit -----------

    @Test
    fun `nested roots both registered return both, most specific first`() {
        val parent = "/storage/emulated/0/Roms"
        val child = "/storage/emulated/0/Roms/PS2"
        assertEquals(
            listOf(child, parent),
            LibraryRootMatcher.rootsContaining("/storage/emulated/0/Roms/PS2/mario.iso", listOf(parent, child)),
        )
    }

    @Test
    fun `nested roots -- a path outside the nested child only matches the parent`() {
        val parent = "/storage/emulated/0/Roms"
        val child = "/storage/emulated/0/Roms/PS2"
        assertEquals(
            listOf(parent),
            LibraryRootMatcher.rootsContaining("/storage/emulated/0/Roms/NES/game.nes", listOf(parent, child)),
        )
    }

    // -- Bare-volume tree root (empty relative half) -------------------------------------------

    @Test
    fun `bare volume tree root with empty relative matches every path on that volume`() {
        val root = "content://com.android.externalstorage.documents/tree/primary%3A"
        assertEquals(
            listOf(root),
            LibraryRootMatcher.rootsContaining("/storage/emulated/0/Roms/Game.iso", listOf(root)),
        )
    }

    // -- Unrecognized shapes: no match rather than a guess --------------------------------------

    @Test
    fun `unrecognized content provider never matches`() {
        val root = "content://com.other.provider/tree/x%3ARoms"
        assertTrue(LibraryRootMatcher.rootsContaining("/storage/emulated/0/Roms/Game.iso", listOf(root)).isEmpty())
    }

    @Test
    fun `malformed tree uri never matches`() {
        val noTreeMarker = "content://com.android.externalstorage.documents/root"
        val emptyTreeSegment = "content://com.android.externalstorage.documents/tree/"
        val path = "/storage/emulated/0/Roms/Game.iso"
        assertTrue(LibraryRootMatcher.rootsContaining(path, listOf(noTreeMarker)).isEmpty())
        assertTrue(LibraryRootMatcher.rootsContaining(path, listOf(emptyTreeSegment)).isEmpty())
    }

    @Test
    fun `non-uri non-path root strings never match`() {
        val path = "/storage/emulated/0/Roms/Game.iso"
        assertTrue(LibraryRootMatcher.rootsContaining(path, listOf("Roms")).isEmpty())
        assertTrue(LibraryRootMatcher.rootsContaining(path, listOf("")).isEmpty())
    }

    @Test
    fun `empty registered roots list returns no matches`() {
        assertTrue(LibraryRootMatcher.rootsContaining("/storage/emulated/0/Roms/Game.iso", emptyList()).isEmpty())
    }

    @Test
    fun `non-absolute path never matches anything`() {
        val root = "/storage/emulated/0/Roms"
        assertTrue(LibraryRootMatcher.rootsContaining("storage/emulated/0/Roms/Game.iso", listOf(root)).isEmpty())
    }
}

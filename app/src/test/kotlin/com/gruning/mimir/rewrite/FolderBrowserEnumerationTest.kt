package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real files on a real filesystem — the enumeration's whole job is what the disk actually contains. */
class FolderBrowserEnumerationTest {

    @get:Rule val temp = TemporaryFolder()

    private val classify: (String) -> String? = { name ->
        if (name.endsWith(".gba")) "Game Boy Advance" else null
    }

    private fun list(dir: File) = FolderBrowserEnumeration.listDirectory(dir.absolutePath, classify)

    @Test
    fun `directories come first and content carries its platform`() {
        val root = temp.newFolder("Roms")
        File(root, "zelda.gba").writeText("x")
        File(root, "GBA").mkdirs()

        val rows = list(root)
        assertEquals(listOf("GBA", "zelda.gba"), rows.map { it.name })
        assertTrue(rows.first().isDirectory)
        assertEquals("Game Boy Advance", rows.last().platformLabel)
    }

    @Test
    fun `only zip is offered for extraction`() {
        val root = temp.newFolder("Archives")
        File(root, "pack.zip").writeText("x")
        File(root, "pack.7z").writeText("x")
        File(root, "pack.rar").writeText("x")

        val rows = list(root).associateBy { it.name }
        assertTrue("zip can actually be unpacked today", rows.getValue("pack.zip").isArchive)
        assertFalse(
            "7z has no reader yet — advertising an extraction that then fails is worse than not offering it",
            rows.getValue("pack.7z").isArchive,
        )
        assertFalse("rar is licence-blocked", rows.getValue("pack.rar").isArchive)
    }

    @Test
    fun `support files are marked, never dropped`() {
        val root = temp.newFolder("Mixed")
        File(root, "zelda.gba").writeText("x")
        File(root, "zelda.srm").writeText("x")
        File(root, ".nomedia").writeText("x")

        val rows = list(root)
        assertEquals("every file on disk still appears", 3, rows.size)
        assertTrue(rows.first { it.name == "zelda.srm" }.isSupport)
        assertTrue(rows.first { it.name == ".nomedia" }.isSupport)
    }

    @Test
    fun `a bios payload is named as one, and a game is never mistaken for bios`() {
        val root = temp.newFolder("Bios")
        File(root, "scph1001.bin").writeText("x")
        File(root, "zelda.gba").writeText("x")

        val rows = list(root).associateBy { it.name }
        assertTrue(rows.getValue("scph1001.bin").isBios)
        assertFalse(
            "mislabelling a game as BIOS hides it from the player's own library",
            rows.getValue("zelda.gba").isBios,
        )
    }

    @Test
    fun `an unreadable or missing directory lists empty instead of throwing`() {
        assertTrue(
            "a ROM tree can contain a folder the app cannot enter; crashing on one is worse than showing " +
                "it empty",
            FolderBrowserEnumeration.listDirectory("/does/not/exist", classify).isEmpty(),
        )
    }

    @Test
    fun `an empty directory lists nothing and does not fail`() {
        assertTrue(list(temp.newFolder("Empty")).isEmpty())
    }

    @Test
    fun `storage roots exclude plumbing directories`() {
        val storage = temp.newFolder("storage")
        File(storage, "self").mkdirs()
        File(storage, "emulated").mkdirs()
        File(storage, "691E-7BEA").mkdirs()

        val roots = FolderBrowserEnumeration.storageRoots(storage)
        assertTrue("the SD card is a place a player keeps games", roots.any { it.endsWith("691E-7BEA") })
        assertTrue(
            "self and emulated are plumbing — offering them invites the player somewhere their games " +
                "cannot be",
            roots.none { it.endsWith("/self") || it.endsWith("/emulated") },
        )
    }
}

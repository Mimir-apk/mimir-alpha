package com.gruning.mimir.rewrite.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * which names are refused, and with which sentence. The policy is the
 * strictest of the filesystems a card can carry, so a name accepted here is one every drive keeps intact.
 */
class RenameNamePolicyTest {

    private fun check(name: String, current: String? = "Old.gba") = RenameNamePolicy.check(name, current)

    @Test
    fun `an ordinary name, with spaces, dots, brackets and unicode, is fine`() {
        listOf(
            "Super Mario Advance 4 (USA).gba",
            "save.state.1",
            ".gitignore",
            "ファイナルファンタジー.zip",
            "a",
            "Zelda - A Link to the Past [!].sfc",
        ).forEach { assertNull(it, check(it)) }
    }

    @Test
    fun `empty and blank are refused`() {
        assertEquals(RenameProblem.EMPTY, check(""))
        assertEquals(RenameProblem.EMPTY, check("   "))
    }

    @Test
    fun `a slash or a backslash is refused anywhere in the name`() {
        assertEquals(RenameProblem.SEPARATOR, check("a/b"))
        assertEquals(RenameProblem.SEPARATOR, check("/abs"))
        assertEquals(RenameProblem.SEPARATOR, check("a\\b"))
        assertEquals(RenameProblem.SEPARATOR, check("../escape"))
    }

    @Test
    fun `dot and dot-dot are refused, other dotted names are judged on their own`() {
        assertEquals(RenameProblem.DOTS, check("."))
        assertEquals(RenameProblem.DOTS, check(".."))
        assertEquals("three dots end in a dot", RenameProblem.TRAILING_DOT, check("..."))
        assertNull(check(".hidden"))
    }

    @Test
    fun `a trailing dot is refused because FAT-family cards cannot keep it`() {
        assertEquals(RenameProblem.TRAILING_DOT, check("Mario."))
        assertNull("a trailing SPACE is tidied away, not reported", check("Mario  "))
        assertEquals("Mario", RenameNamePolicy.normalise("Mario  "))
    }

    @Test
    fun `the characters exFAT refuses are refused`() {
        listOf(":", "*", "?", "\"", "<", ">", "|").forEach {
            assertEquals(it, RenameProblem.BAD_CHARACTER, check("Mario${it}Kart"))
        }
    }

    @Test
    fun `control characters are refused`() {
        assertEquals(RenameProblem.CONTROL, check("Mario\u0007"))
        assertEquals(RenameProblem.CONTROL, check("Ma\nrio"))
    }

    @Test
    fun `the DOS device names are reserved, with or without an extension, in any case`() {
        listOf("CON", "con", "Prn", "AUX", "NUL", "COM1", "com9", "LPT1", "lpt9", "NUL.txt", "con.tar.gz").forEach {
            assertEquals(it, RenameProblem.RESERVED, check(it))
        }
        listOf("CONSOLE", "COM10", "COM0", "NULL", "auxiliary.txt").forEach { assertNull(it, check(it)) }
    }

    @Test
    fun `a name longer than 255 bytes is refused, counted in bytes not characters`() {
        assertNull(check("a".repeat(255)))
        assertEquals(RenameProblem.TOO_LONG, check("a".repeat(256)))
        assertEquals("86 three-byte characters are 258 bytes", RenameProblem.TOO_LONG, check("あ".repeat(86)))
        assertNull("85 are 255", check("あ".repeat(85)))
    }

    @Test
    fun `typing the current name back is nothing to rename, but a change of case is a rename`() {
        assertEquals(RenameProblem.UNCHANGED, check("Old.gba", current = "Old.gba"))
        assertEquals(RenameProblem.UNCHANGED, check("  Old.gba ", current = "Old.gba"))
        assertNull(check("old.gba", current = "Old.gba"))
        assertNull("with no current name there is nothing to compare", RenameNamePolicy.check("Old.gba", null))
    }

    @Test
    fun `every problem has a plain sentence ending in a full stop`() {
        RenameProblem.entries.forEach {
            assertTrue(it.name, it.sentence.endsWith("."))
            assertTrue(it.name, it.sentence.length > 8)
        }
    }

    @Test
    fun `Mimir's own working folder names are refused, in any letter case`() {
        listOf(".mimir-staging", ".Mimir-Staging", ".delete-1927038605654158004", ".extract-7", ".copy-12").forEach {
            assertEquals(it, RenameProblem.WORKING_FOLDER, check(it))
        }
    }

    @Test
    fun `a folder that merely looks like a working folder is the player's own and is fine`() {
        listOf(".extract-notes", ".mimir-staging-old", "mimir-staging", ".delete-", ".moved-1").forEach {
            assertNull(it, check(it))
        }
    }
}

package com.gruning.mimir.rewrite.core.resolver.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **a `.cue`'s tracks go with it on delete and move**, so the reader that finds them is
 * pinned hard: it must accept the sheets people really have (quoted or bare names, CRLF, a byte-order mark) and refuse
 * every entry that could point a delete anywhere but the sheet's own folder.
 */
class CueSheetTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun names(vararg lines: String, eol: String = "\n"): List<String> =
        CueSheet.listedNames(lines.joinToString(eol))

    @Test
    fun `a quoted name is read whole, spaces and parentheses included`() {
        assertEquals(
            listOf("Game (Track 1).bin", "Game (Track 2).bin"),
            names(
                "FILE \"Game (Track 1).bin\" BINARY",
                "  TRACK 01 MODE2/2352",
                "    INDEX 01 00:00:00",
                "FILE \"Game (Track 2).bin\" BINARY",
            ),
        )
    }

    @Test
    fun `a bare name is read with or without spaces and with or without a type`() {
        assertEquals(
            listOf("game.bin", "My Game.bin", "plain.bin", "Wave Track.wav"),
            names(
                "FILE game.bin BINARY",
                "FILE My Game.bin BINARY",
                "FILE plain.bin",
                "file Wave Track.wav wave",
            ),
        )
    }

    @Test
    fun `CRLF line ends, lone CR line ends and a byte-order mark change nothing`() {
        val sheet = arrayOf("FILE \"a.bin\" BINARY", "FILE b.bin BINARY")

        assertEquals(listOf("a.bin", "b.bin"), names(*sheet, eol = "\r\n"))
        assertEquals(listOf("a.bin", "b.bin"), names(*sheet, eol = "\r"))
        assertEquals(listOf("a.bin", "b.bin"), CueSheet.listedNames("\uFEFF" + sheet.joinToString("\r\n")))
    }

    @Test
    fun `an entry that is not a bare name in the sheet's own folder is refused`() {
        assertEquals(
            "only the one honest entry survives",
            listOf("honest.bin"),
            names(
                "FILE \"../up.bin\" BINARY",
                "FILE \"..\\up.bin\" BINARY",
                "FILE \"..\" BINARY",
                "FILE \".\" BINARY",
                "FILE \"sub/inside.bin\" BINARY",
                "FILE \"sub\\inside.bin\" BINARY",
                "FILE sub/bare.bin BINARY",
                "FILE \"/etc/passwd\" BINARY",
                "FILE \"\\\\server\\share\\x.bin\" BINARY",
                "FILE \"C:\\x.bin\" BINARY",
                "FILE \"C:x.bin\" BINARY",
                "FILE \"\" BINARY",
                "FILE \"unterminated.bin BINARY",
                "FILENAME \"not-a-file-line.bin\"",
                "FILE \"honest.bin\" BINARY",
            ),
        )
    }

    @Test
    fun `a name is listed once however often and in whatever case the sheet repeats it`() {
        assertEquals(listOf("A.bin", "b.bin"), names("FILE \"A.bin\" BINARY", "FILE \"a.BIN\" BINARY", "FILE \"b.bin\" BINARY"))
    }

    @Test
    fun `only files that exist beside the sheet come back, never a sheet, a subfolder file or a missing one`() {
        val folder = temp.newFolder()
        val cue = File(folder, "Game.cue")
        File(folder, "Game (Track 1).bin").writeText("1")
        File(folder, "Other.cue").writeText("another sheet")
        File(folder, "sub").mkdirs()
        File(folder, "sub/inside.bin").writeText("2")
        cue.writeText(
            listOf(
                "FILE \"Game (Track 1).bin\" BINARY",
                "FILE \"Game (Track 2).bin\" BINARY", // listed, not there: ignored
                "FILE \"Other.cue\" BINARY", // a sheet is never a track
                "FILE \"sub/inside.bin\" BINARY", // outside the folder, though it exists
                "FILE \"../Game.cue\" BINARY",
            ).joinToString("\r\n"),
        )

        assertEquals(listOf("Game (Track 1).bin"), CueSheet.listedFiles(cue).map { it.name })
        assertEquals("a sheet that is not there lists nothing", emptyList<File>(), CueSheet.listedFiles(File(folder, "none.cue")))
    }

    @Test
    fun `a sheet over the size bound is not read at all`() {
        val folder = temp.newFolder()
        File(folder, "a.bin").writeText("1")
        val cue = File(folder, "Huge.cue")
        cue.writeText("FILE \"a.bin\" BINARY\n" + "REM padding\n".repeat((CueSheet.MAX_BYTES / 12).toInt() + 10))

        assertEquals(emptyList<File>(), CueSheet.listedFiles(cue))
    }

    // -- the PROTECTING reader: what a sheet that stays behind still needs. It fails open. ------------------------

    @Test
    fun `a staying sheet is read leniently - dot-slash, case, and a Windows code page all still protect the track`() {
        val folder = temp.newFolder()
        val sheet = File(folder, "Stays.cue")
        sheet.writeText(
            listOf(
                "FILE \"./Shared.bin\" BINARY",
                "FILE \".\\dotted.bin\" BINARY",
                "FILE \"sub/../folded.bin\" BINARY",
                "FILE \"../elsewhere.bin\" BINARY", // leaves the folder: cannot name a track beside the sheet
                "FILE \"sub/inside.bin\" BINARY",
            ).joinToString("\r\n"),
        )

        assertEquals(setOf("shared.bin", "dotted.bin", "folded.bin"), CueSheet.protectedNames(sheet))
        assertEquals(
            "the taking reader stays strict about the same sheet",
            emptyList<String>(),
            CueSheet.listedNames(sheet.readText()),
        )

        val legacy = File(folder, "Legacy.cue")
        legacy.writeBytes("FILE \"Café (Track 1).bin\" BINARY".toByteArray(Charsets.ISO_8859_1))
        File(folder, "Café (Track 1).bin").writeText("1")
        assertTrue(
            "the Latin-1 reading finds the file on the card",
            "café (track 1).bin" in CueSheet.protectedNames(legacy).orEmpty(),
        )
        assertEquals("the taking reader does not guess a code page", emptyList<File>(), CueSheet.listedFiles(legacy))
    }

    @Test
    fun `a sheet that cannot be read with confidence is assumed to need everything - null, never an empty list`() {
        val folder = temp.newFolder()
        val big = File(folder, "Big.cue")
        big.writeText("FILE \"a.bin\" BINARY\n" + "REM padding\n".repeat((CueSheet.MAX_BYTES / 12).toInt() + 10))
        val malformed = File(folder, "Malformed.cue")
        malformed.writeText("FILE \"unterminated.bin BINARY\r\n")

        assertNull("oversized", CueSheet.protectedNames(big))
        assertNull("not a readable file (gone)", CueSheet.protectedNames(File(folder, "gone.cue")))
        assertNull("not a readable file (a folder)", CueSheet.protectedNames(folder))
        assertNull("a FILE line it cannot parse", CueSheet.protectedNames(malformed))
        assertEquals("while a clean sheet that lists nothing really does need nothing", emptySet<String>(), CueSheet.protectedNames(File(folder, "Clean.cue").apply { writeText("REM nothing here\n") }))
    }

    @Test
    fun `a REM line that mentions FILE is a comment to both readers`() {
        val body = listOf(
            "REM FILE \"ghost.bin\" BINARY",
            "REM COMMENT FILE ghost2.bin",
            "  rem file ghost3.bin",
            "FILE \"real.bin\" BINARY",
        ).joinToString("\n")
        val folder = temp.newFolder()
        val sheet = File(folder, "Rem.cue").apply { writeText(body) }

        assertEquals(listOf("real.bin"), CueSheet.listedNames(body))
        assertEquals(setOf("real.bin"), CueSheet.protectedNames(sheet))
    }
}

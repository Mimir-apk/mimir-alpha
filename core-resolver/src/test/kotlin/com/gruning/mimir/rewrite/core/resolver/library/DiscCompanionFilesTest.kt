package com.gruning.mimir.rewrite.core.resolver.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **a disc's libcrypt `.sbi` / `.sub` go wherever the disc goes** -- on a move, a delete and a rename --
 * through the one resolver the disc-set warning already uses, whether or not the library calls the game a set.
 */
class DiscCompanionFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun folder(): File = temp.newFolder()

    private fun File.make(name: String): String = File(this, name).apply { writeText("x") }.path

    // ── a single-disc game's .sbi travels with its disc on a move ──────────────────────────────

    @Test
    fun `a single disc is sent with its own sbi and sub, after it, and nothing else`() {
        val ps1 = folder()
        val disc = ps1.make("Vagrant Story.chd")
        val sbi = ps1.make("Vagrant Story.sbi")
        val sub = ps1.make("Vagrant Story.sub")
        ps1.make("Vagrant Story.txt")
        ps1.make("Vagrant Story (Disc 2).sbi")

        assertEquals(listOf(disc, sbi, sub), DiscCompanionFiles.withCompanions(listOf(disc)))
    }

    @Test
    fun `a whole-set move that already names its companions is not repeated, and a disc without any adds nothing`() {
        val ps1 = folder()
        val one = ps1.make("FF9 (Disc 1).chd")
        val two = ps1.make("FF9 (Disc 2).chd")
        val sbiOne = ps1.make("FF9 (Disc 1).sbi")
        val set = listOf(one, two, sbiOne)

        assertEquals("already carried: unchanged", set, DiscCompanionFiles.withCompanions(set))
        assertEquals("Move just this one: its own sbi, not the set", listOf(one, sbiOne), DiscCompanionFiles.withCompanions(listOf(one)))
        assertEquals("no companion on disk: unchanged", listOf(two), DiscCompanionFiles.withCompanions(listOf(two)))
    }

    // ── delete and rename carry them, and only ever from the disc's own folder ───────────────

    @Test
    fun `only a disc image has companions, so deleting a note or a companion itself never reaches for a sibling`() {
        val ps1 = folder()
        val note = ps1.make("Game.txt")
        val sub = ps1.make("Game.sub")
        ps1.make("Game.sbi")
        val elsewhere = File(ps1, "other").apply { mkdirs() }
        val disc = elsewhere.make("Game.cue")

        assertEquals(listOf(note), DiscCompanionFiles.withCompanions(listOf(note)))
        assertEquals("a .sub does not drag the .sbi", listOf(sub), DiscCompanionFiles.withCompanions(listOf(sub)))
        assertEquals("the same name in another folder is not its companion", listOf(disc), DiscCompanionFiles.withCompanions(listOf(disc)))
        assertEquals(emptyList<String>(), DiscCompanionFiles.companionsOf(note))
        assertEquals(emptyList<String>(), DiscCompanionFiles.companionsOf(File(ps1, "missing.chd").path))
    }

    @Test
    fun `a renamed disc names its companions after itself and keeps each one's own suffix`() {
        val ps1 = folder()
        val disc = ps1.make("Game.cue")
        val sbi = ps1.make("Game.sbi")
        val sub = ps1.make("Game.SUB")

        // Compared without case: a case-insensitive card (and this machine) answers the first spelling it probes.
        assertEquals(
            listOf(sbi, sub).map(String::lowercase),
            DiscCompanionFiles.companionsOf(disc).map(String::lowercase),
        )
        assertEquals("Better Name.sbi", DiscCompanionFiles.renamedTo(sbi, File(ps1, "Better Name.cue").path))
        assertEquals("Better Name.SUB", DiscCompanionFiles.renamedTo(sub, File(ps1, "Better Name.cue").path))
        assertEquals("only the extension changed: the companion keeps its name", "Game.sbi", DiscCompanionFiles.renamedTo(sbi, File(ps1, "Game.iso").path))
    }

    // -- a disc that stays behind keeps the .sbi: the companion belongs to the NAME, not to one file of it --------

    private fun cueBinChdAndSbi(): List<String> {
        val ps1 = folder()
        return listOf("Game.cue", "Game.bin", "Game.chd", "Game.sbi").map { ps1.make(it) }
    }

    @Test
    fun `deleting the cue and bin does not take the sbi a chd that stays still needs`() {
        val (cue, bin, chd, sbi) = cueBinChdAndSbi()

        assertEquals(listOf(cue, bin), DiscCompanionFiles.withCompanions(listOf(cue, bin)))
        assertEquals(
            "the control: delete every disc image of the name and the sbi has nobody left, so it goes",
            listOf(cue, bin, chd, sbi),
            DiscCompanionFiles.withCompanions(listOf(cue, bin, chd)),
        )
    }

    @Test
    fun `moving one disc image of a name leaves the sbi with the others, and moving them all takes it`() {
        val (cue, bin, chd, sbi) = cueBinChdAndSbi()

        assertEquals(listOf(chd), DiscCompanionFiles.withCompanions(listOf(chd)))
        assertEquals(listOf(cue, bin, chd, sbi), DiscCompanionFiles.withCompanions(listOf(cue, bin, chd)))
    }

    @Test
    fun `renaming a cue does not rename the sbi a chd of the same name still needs`() {
        val (cue) = cueBinChdAndSbi()
        assertEquals(emptyList<String>(), DiscCompanionFiles.companionsOf(cue))

        val solo = folder()
        val soloCue = solo.make("Solo.cue")
        val soloSbi = solo.make("Solo.sbi")
        assertEquals(
            "the control: a cue with no other disc image of its name keeps the rename",
            listOf(soloSbi),
            DiscCompanionFiles.companionsOf(soloCue),
        )
    }

    @Test
    fun `a companion is matched to its disc by name alone, case aside, and a stranger has no leader`() {
        val leaders = DiscCompanionFiles.leadersOf(
            listOf("/r/Game.SBI", "/r/Game.chd", "/r/Other.sbi", "/r/Game.sub", "/r/Game.txt"),
        )

        assertEquals(mapOf("/r/Game.SBI" to "/r/Game.chd", "/r/Game.sub" to "/r/Game.chd"), leaders)
    }

    // -- a .cue's tracks  ---------------------------------------------------------------

    private fun File.cue(name: String, vararg listed: String): String = make(name).also {
        File(it).writeText(listed.joinToString("\r\n") { track -> "FILE \"$track\" BINARY\r\n  TRACK 01 MODE2/2352" })
    }

    @Test
    fun `a delete or move of a cue brings the tracks it lists, then the sbi, and nothing it does not list`() {
        val ps1 = folder()
        val cue = ps1.cue("Game.cue", "Game (Track 1).bin", "Game (Track 2).bin", "Game (Track 3).bin")
        val one = ps1.make("Game (Track 1).bin")
        val two = ps1.make("Game (Track 2).bin")
        // Track 3 is listed and missing; the stranger is there and unlisted.
        val sbi = ps1.make("Game.sbi")
        ps1.make("Game (Track 9).bin")
        ps1.make("Notes.txt")

        assertEquals(listOf(cue, one, two, sbi), DiscCompanionFiles.withCompanions(listOf(cue)))
    }

    @Test
    fun `a track another cue that stays behind still lists is not taken`() {
        val ps1 = folder()
        val a = ps1.cue("Disc A.cue", "a.bin", "shared.bin")
        val b = ps1.cue("Disc B.cue", "b.bin", "shared.bin")
        val aBin = ps1.make("a.bin")
        val bBin = ps1.make("b.bin")
        val shared = ps1.make("shared.bin")

        assertEquals("B stays and needs the shared track", listOf(a, aBin), DiscCompanionFiles.withCompanions(listOf(a)))
        assertEquals(
            "take both sheets and nobody is left to need it, so it goes once",
            listOf(a, b, aBin, shared, bBin),
            DiscCompanionFiles.withCompanions(listOf(a, b)),
        )
    }

    @Test
    fun `a track the player picked is never dropped, even when another cue lists it`() {
        val ps1 = folder()
        val a = ps1.cue("Disc A.cue", "shared.bin")
        ps1.cue("Disc B.cue", "shared.bin")
        val shared = ps1.make("shared.bin")

        assertEquals(listOf(a, shared), DiscCompanionFiles.withCompanions(listOf(a, shared)))
    }

    @Test
    fun `a move makes each track follow its cue under its own name and the sbi follow it under the cue's name`() {
        val ps1 = folder()
        val cue = ps1.cue("Game.cue", "Game (Track 1).bin")
        val bin = ps1.make("Game (Track 1).bin")
        val sbi = ps1.make("Game.sbi")

        val set = DiscCompanionFiles.companionSet(listOf(cue))

        assertEquals(listOf(cue, bin, sbi), set.paths)
        assertEquals(setOf(bin, sbi), set.followers.keys)
        assertEquals(cue, set.followers.getValue(bin).leader)
        assertEquals("a track keeps the name the sheet lists", true, set.followers.getValue(bin).keepsOwnName)
        assertEquals(cue, set.followers.getValue(sbi).leader)
        assertEquals("an sbi takes its disc's name", false, set.followers.getValue(sbi).keepsOwnName)
    }

    @Test
    fun `renaming a cue renames only the sheet and its sbi, and its own tracks do not hold the sbi back`() {
        val ps1 = folder()
        val cue = ps1.cue("Game.cue", "Game.bin")
        ps1.make("Game.bin") // same stem as the sheet, but it is the sheet's own track
        val sbi = ps1.make("Game.sbi")

        assertEquals(listOf(sbi), DiscCompanionFiles.companionsOf(cue))
    }

    @Test
    fun `deleting a playlist takes nothing with it, whatever it lists`() {
        val ps1 = folder()
        ps1.make("A (Disc 1).chd")
        ps1.make("A (Disc 1).sbi")
        val m3u = ps1.make("A.m3u")
        File(m3u).writeText("A (Disc 1).chd\n")

        assertEquals(listOf(m3u), DiscCompanionFiles.withCompanions(listOf(m3u)))
    }

    // -- what a sheet that STAYS BEHIND still needs (review of c89025e17, P1 + P3) ----------------------------------

    @Test
    fun `a cue beside a ccd keeps its track, and the sub that track needs, on both a delete and a move`() {
        val ps1 = folder()
        val cue = ps1.cue("Game.cue", "Game.img")
        ps1.make("Game.ccd")
        ps1.make("Game.img")
        ps1.make("Game.sub")

        assertEquals("a delete takes the sheet alone", listOf(cue), DiscCompanionFiles.withCompanions(listOf(cue)))
        val set = DiscCompanionFiles.companionSet(listOf(cue))
        assertEquals("a move carries the sheet alone", listOf(cue), set.paths)
        assertEquals("with nothing following it", 0, set.followers.size)
    }

    @Test
    fun `each of ccd, mds and toc holds back the track of its own name, and one that goes too does not`() {
        for (manifest in listOf("Game.ccd", "Game.mds", "Game.toc", "Game.TOC")) {
            val ps1 = folder()
            val cue = ps1.cue("Game.cue", "Game.img")
            val img = ps1.make("Game.img")
            val staying = ps1.make(manifest)

            assertEquals(manifest, listOf(cue), DiscCompanionFiles.withCompanions(listOf(cue)))
            assertEquals(
                "$manifest is in the operation, so nothing is left to need the track",
                listOf(cue, staying, img),
                DiscCompanionFiles.withCompanions(listOf(cue, staying)),
            )
        }
    }

    @Test
    fun `a chd of the same name that stays is a disc of its own, so it does not hold a track back`() {
        val ps1 = folder()
        val cue = ps1.cue("Game.cue", "Game.bin")
        val bin = ps1.make("Game.bin")
        ps1.make("Game.chd")

        assertEquals(listOf(cue, bin), DiscCompanionFiles.withCompanions(listOf(cue)))
    }

    @Test
    fun `a gdi that stays behind keeps the tracks it names, bare or quoted, and a gdi that goes too does not`() {
        val ps1 = folder()
        val cue = ps1.cue("Game.cue", "track01.bin", "track 02.raw", "other.bin")
        val one = ps1.make("track01.bin")
        val two = ps1.make("track 02.raw")
        val other = ps1.make("other.bin")
        val gdi = ps1.make("Disc.gdi")
        File(gdi).writeText("2\r\n1 0 4 2352 track01.bin 0\r\n2 450 0 2352 \"track 02.raw\" 0\r\n")

        assertEquals(listOf(cue, other), DiscCompanionFiles.withCompanions(listOf(cue)))
        assertEquals(
            listOf(cue, gdi, one, two, other),
            DiscCompanionFiles.withCompanions(listOf(cue, gdi)),
        )
    }

    @Test
    fun `a sheet that stays behind and cannot be read with confidence holds back every track in its folder`() {
        val padding = "x".repeat(CueSheet.MAX_BYTES.toInt() + 10)
        val control = folder()
        val controlCue = control.cue("Game.cue", "a.bin")
        val controlBin = control.make("a.bin")
        assertEquals("with no other sheet the track goes", listOf(controlCue, controlBin), DiscCompanionFiles.withCompanions(listOf(controlCue)))

        for ((name, body) in listOf(
            "Huge.cue" to "FILE \"b.bin\" BINARY\n$padding",
            "Broken.cue" to "FILE \"unterminated.bin BINARY\r\n",
            "Huge.gdi" to "1\n$padding",
        )) {
            val ps1 = folder()
            val cue = ps1.cue("Game.cue", "a.bin")
            ps1.make("a.bin")
            File(ps1, name).writeText(body)

            assertEquals("$name could need anything", listOf(cue), DiscCompanionFiles.withCompanions(listOf(cue)))
        }
    }

    @Test
    fun `a staying sheet that writes dot-slash or a Windows code page still holds the track back`() {
        val dotted = folder()
        val a = dotted.cue("Disc A.cue", "a.bin", "shared.bin")
        dotted.cue("Disc B.cue", "./shared.bin")
        val aBin = dotted.make("a.bin")
        dotted.make("shared.bin")

        assertEquals(listOf(a, aBin), DiscCompanionFiles.withCompanions(listOf(a)))

        val legacy = folder()
        val cue = legacy.cue("Disc A.cue", "Café.bin")
        File(legacy, "Disc B.cue").writeBytes("FILE \"Café.bin\" BINARY\r\n".toByteArray(Charsets.ISO_8859_1))
        legacy.make("Café.bin")

        assertEquals(listOf(cue), DiscCompanionFiles.withCompanions(listOf(cue)))
    }

    @Test
    fun `a REM line that mentions FILE in a staying sheet protects nothing`() {
        val ps1 = folder()
        val a = ps1.cue("Disc A.cue", "a.bin", "shared.bin")
        File(ps1, "Disc B.cue").writeText("REM FILE \"shared.bin\" BINARY\r\nFILE \"b.bin\" BINARY\r\n")
        val aBin = ps1.make("a.bin")
        val shared = ps1.make("shared.bin")

        assertEquals(listOf(a, aBin, shared), DiscCompanionFiles.withCompanions(listOf(a)))
    }
}

package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.files.ConflictResolution
import com.gruning.mimir.rewrite.core.files.FileOperation
import com.gruning.mimir.rewrite.core.files.ManagedFileOperations
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueEntry
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import com.gruning.mimir.rewrite.core.model.jobs.JobKind
import com.gruning.mimir.rewrite.core.resolver.library.DiscCompanionFiles
import com.gruning.mimir.rewrite.core.resolver.library.PlaylistMembers
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **a `.cue`'s tracks go with it on delete and move, under one Undo, and a playlist's
 * discs never go with the playlist.**
 *
 * Run against real files through the same functions the store calls: [DiscCompanionFiles.companionSet] and
 * [moveFollowersOf] for a move, [DiscCompanionFiles.withCompanions] and the engine's staged delete for a delete,
 * [PlaylistMembers.staying] and [playlistStaysNote] for the line a playlist's card carries.
 */
class FileBrowserCompanionTracksTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val ops = ManagedFileOperations()

    private fun File.make(name: String, text: String = name): File = File(this, name).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    private fun File.sheet(name: String, vararg tracks: String): File =
        make(name, tracks.joinToString("\r\n") { "FILE \"$it\" BINARY\r\n  TRACK 01 MODE2/2352\r\n    INDEX 01 00:00:00" })

    // -- delete + Undo -------------------------------------------------------------------------------------------

    @Test
    fun `deleting a cue stages its tracks and its sbi in one delete, and one Undo brings every one back`() {
        val folder = temp.newFolder()
        val cue = folder.sheet("Game.cue", "Game (Track 1).bin", "Game (Track 2).bin")
        val one = folder.make("Game (Track 1).bin")
        val two = folder.make("Game (Track 2).bin")
        val sbi = folder.make("Game.sbi")
        val bystander = folder.make("Game (Track 9).bin")

        val everything = DiscCompanionFiles.withCompanions(listOf(cue.path))
        val deletion = runBlocking { ops.stageDelete(everything) }.getOrThrow()
        try {
            assertEquals("one held delete holds all four", 4, deletion.entries.size)
            assertTrue("the cue and every listed file are staged away", listOf(cue, one, two, sbi).none { it.exists() })
            assertTrue("a file the sheet does not list is left alone", bystander.isFile)

            val restored = runBlocking { ops.undoDelete(deletion) }.getOrThrow()

            assertEquals("a single Undo returns all four", 4, restored)
            assertTrue("and each is a real file again", listOf(cue, one, two, sbi).all { it.isFile })
            assertEquals("with its own contents", "Game (Track 2).bin", two.readText())
        } finally {
            runBlocking { ops.finalizeDelete(deletion) }
        }
    }

    @Test
    fun `deleting a cue never stages a track another cue that stays still lists`() {
        val folder = temp.newFolder()
        val a = folder.sheet("Disc A.cue", "a.bin", "shared.bin")
        folder.sheet("Disc B.cue", "b.bin", "shared.bin")
        val aBin = folder.make("a.bin")
        folder.make("b.bin")
        val shared = folder.make("shared.bin")

        val deletion = runBlocking { ops.stageDelete(DiscCompanionFiles.withCompanions(listOf(a.path))) }.getOrThrow()
        try {
            assertFalse(a.exists() || aBin.exists())
            assertTrue("B stays and still has its shared track", shared.isFile && File(folder, "b.bin").isFile)
        } finally {
            runBlocking { ops.finalizeDelete(deletion) }
        }
    }

    // -- move ----------------------------------------------------------------------------------------------------

    private fun move(picked: File, to: File, resolution: ConflictResolution): Pair<Result<Int>, List<String>> {
        val set = DiscCompanionFiles.companionSet(listOf(picked.path))
        val skipped = mutableListOf<String>()
        val result = runBlocking {
            ops.move(
                sources = set.paths,
                destination = to.path,
                conflictResolution = resolution,
                onSkipped = { skipped += it },
                followers = moveFollowersOf(set),
            )
        }
        return result to skipped
    }

    @Test
    fun `moving a cue takes its tracks and its sbi to the destination under their own names`() {
        val root = temp.newFolder()
        val cue = root.sheet("from/Game.cue", "Game (Track 1).bin")
        root.make("from/Game (Track 1).bin")
        root.make("from/Game.sbi")
        val to = File(root, "to").apply { mkdirs() }

        val (result, skipped) = move(cue, to, ConflictResolution.ASK)

        assertTrue(result.isSuccess)
        assertTrue(skipped.isEmpty())
        assertEquals(setOf("Game.cue", "Game (Track 1).bin", "Game.sbi"), to.list()!!.toSet())
        assertEquals("and nothing of the game is left behind", emptyList<String>(), File(root, "from").list()!!.toList())
    }

    @Test
    fun `on Keep both the sheet is renamed, its track keeps the listed name, and a taken track stays behind`() {
        val root = temp.newFolder()
        val cue = root.sheet("from/Game.cue", "Game (Track 1).bin")
        root.make("from/Game (Track 1).bin")
        val to = File(root, "to").apply { mkdirs() }
        root.make("to/Game.cue", "ANOTHER SHEET")

        val (renamedSheet, _) = move(cue, to, ConflictResolution.RENAME)
        assertTrue(renamedSheet.isSuccess)
        assertTrue("the sheet landed beside the incumbent", File(to, "Game (copy).cue").isFile)
        assertTrue("the track kept the name the sheet lists", File(to, "Game (Track 1).bin").isFile)
        assertFalse(File(to, "Game (Track 1) (copy).bin").exists())

        // Now the track's own name is the one that is taken: it is left where it was.
        val again = temp.newFolder()
        val cue2 = again.sheet("from/Game.cue", "Game (Track 1).bin")
        val track2 = again.make("from/Game (Track 1).bin")
        val to2 = File(again, "to").apply { mkdirs() }
        again.make("to/Game (Track 1).bin", "ANOTHER GAME'S TRACK")

        val (result, skipped) = move(cue2, to2, ConflictResolution.RENAME)

        assertTrue(result.isSuccess)
        assertEquals(listOf(track2.path), skipped)
        assertTrue("it stayed where it was", track2.isFile)
        assertEquals("ANOTHER GAME'S TRACK", File(to2, "Game (Track 1).bin").readText())
    }

    // -- a playlist's discs never go with it ---------------------------------------------------------------------

    @Test
    fun `deleting a playlist deletes only the playlist, and the card says its discs stay`() {
        val folder = temp.newFolder()
        val discs = (1..3).map { folder.make("FF9 (Disc $it).chd") }
        val m3u = folder.make("FF9.m3u", discs.joinToString("\n") { it.name })

        val everything = DiscCompanionFiles.withCompanions(listOf(m3u.path))
        val staying = PlaylistMembers.staying(everything)
        val deletion = runBlocking { ops.stageDelete(everything) }.getOrThrow()
        try {
            assertEquals("the delete is the playlist and nothing else", listOf(m3u.path), everything)
            assertFalse(m3u.exists())
            assertTrue("every disc it listed is still there", discs.all { it.isFile })
            assertNotNull(staying)
            assertEquals("The 3 discs it lists stay.", playlistStaysNote(staying!!.playlists, staying.members))
        } finally {
            runBlocking { ops.finalizeDelete(deletion) }
        }
    }

    @Test
    fun `the playlist note reads right for one disc, several discs and several playlists`() {
        assertEquals("The disc it lists stays.", playlistStaysNote(playlists = 1, members = 1))
        assertEquals("The 3 discs it lists stay.", playlistStaysNote(playlists = 1, members = 3))
        assertEquals("The 5 discs they list stay.", playlistStaysNote(playlists = 2, members = 5))
        assertEquals("The disc they list stays.", playlistStaysNote(playlists = 2, members = 1))
    }

    // -- the cancelled card counts what was picked ---------------------------------------------------------------

    @Test
    fun `a cancelled move counts the files the player picked, not the companions that rode along`() {
        // One disc picked, its sbi and a track along for the ride: all three landed.
        val all = cancelledTransferState(FileOperation.MOVE, sourceCount = 1, landed = 1, skipped = 0, destinationFolder = "PS1")
        assertEquals("Too late to stop: everything was already moved to PS1.", all.kept)

        // Two picked, one landed: "1 of 2", whatever else landed beside it.
        val partial = cancelledTransferState(FileOperation.MOVE, sourceCount = 2, landed = 1, skipped = 0, destinationFolder = "PS1")
        assertEquals("1 of 2 files were moved to PS1 and kept. The rest are still where they were.", partial.kept)

        // The stop landed between the disc and the files that go with it: do not claim a clean finish.
        val caught = cancelledTransferState(
            FileOperation.MOVE, sourceCount = 1, landed = 1, skipped = 0, destinationFolder = "PS1", companionsLeft = 2,
        )
        assertEquals(
            "Too late to stop: everything you picked was already moved to PS1. The 2 files that go with it are still where they were.",
            caught.kept,
        )
        val one = cancelledTransferState(
            FileOperation.MOVE, sourceCount = 1, landed = 1, skipped = 0, destinationFolder = "PS1", companionsLeft = 1,
        )
        assertTrue(one.kept.endsWith("The 1 file that goes with it is still where it was."))
    }

    // -- a manifest that stays still needs its track (review of c89025e17, P1) ------------------------------------

    @Test
    fun `deleting a cue whose img a ccd still needs stages the cue alone and leaves the ccd, img and sub`() {
        val folder = temp.newFolder()
        val cue = folder.sheet("Game.cue", "Game.img")
        val ccd = folder.make("Game.ccd")
        val img = folder.make("Game.img")
        val sub = folder.make("Game.sub")

        val plan = planDelete(listOf(cue.path))
        val deletion = runBlocking { ops.stageDelete(plan.everything) }.getOrThrow()
        try {
            assertEquals("the delete is the sheet and nothing else", listOf(cue.path), plan.everything)
            assertEquals(1, deletion.entries.size)
            assertFalse(cue.exists())
            assertTrue("the disc image, its manifest and its subchannel are untouched", listOf(ccd, img, sub).all { it.isFile })
            assertEquals("and the toast says one file", 1, deletedPickedCount(deletion.entries, listOf(cue.path)))
        } finally {
            runBlocking { ops.finalizeDelete(deletion) }
        }
    }

    @Test
    fun `moving a cue whose img a ccd still needs carries the cue alone`() {
        val root = temp.newFolder()
        val cue = root.sheet("from/Game.cue", "Game.img")
        root.make("from/Game.ccd")
        root.make("from/Game.img")
        root.make("from/Game.sub")
        val to = File(root, "to").apply { mkdirs() }

        val (result, skipped) = move(cue, to, ConflictResolution.ASK)

        assertTrue(result.isSuccess)
        assertTrue(skipped.isEmpty())
        assertEquals(setOf("Game.cue"), to.list()!!.toSet())
        assertEquals(setOf("Game.ccd", "Game.img", "Game.sub"), File(root, "from").list()!!.toSet())
    }

    // -- the delete toast counts what was picked (P2) ------------------------------------------------------------

    @Test
    fun `the delete toast counts the files the player picked, not the tracks and sbi that rode along`() {
        val folder = temp.newFolder()
        val cue = folder.sheet("Game.cue", "Game (Track 1).bin", "Game (Track 2).bin")
        val one = folder.make("Game (Track 1).bin")
        folder.make("Game (Track 2).bin")
        folder.make("Game.sbi")

        val plan = planDelete(listOf(cue.path))
        val deletion = runBlocking { ops.stageDelete(plan.everything) }.getOrThrow()
        try {
            assertEquals("four files are held under one Undo", 4, deletion.entries.size)
            assertEquals("the toast says one, as the player picked one", 1, deletedPickedCount(deletion.entries, listOf(cue.path)))
            assertEquals("pick the sheet and a track and it says two", 2, deletedPickedCount(deletion.entries, listOf(cue.path, one.path)))
            assertEquals(
                "a path spelled with forward slashes is still the same picked file",
                1,
                deletedPickedCount(deletion.entries, listOf(folder.path.replace('\\', '/') + "/Game.cue")),
            )
        } finally {
            runBlocking { ops.finalizeDelete(deletion) }
        }
    }

    // -- the playlist's note lands on the card, once (P4) --------------------------------------------------------

    @Test
    fun `deleting a playlist puts the discs-stay note on its card once, after what the card already says`() {
        val folder = temp.newFolder()
        val discs = (1..3).map { folder.make("FF9 (Disc $it).chd") }
        val m3u = folder.make("FF9.m3u", discs.joinToString("\n") { it.name })

        val plan = planDelete(listOf(m3u.path))

        assertEquals("the delete is the playlist alone", listOf(m3u.path), plan.everything)
        assertEquals(listOf("The 3 discs it lists stay."), plan.notes)

        val card = FileQueueEntry(7, JobKind.DELETE, "FF9.m3u", "Deleted from PS1", FileQueueState.Done)
        val bystander = card.copy(id = 8, subtitle = "Copied to PS2")
        val queue = listOf(card, bystander)
        val noted = plan.notes.fold(queue) { entries, note -> withNote(entries, 7, note) }

        assertEquals("Deleted from PS1 · The 3 discs it lists stay.", noted[0].subtitle)
        assertEquals("another card is not touched", bystander, noted[1])
        assertEquals("the same note twice adds nothing", noted, plan.notes.fold(noted) { entries, note -> withNote(entries, 7, note) })
        assertEquals(
            "a second, different note is appended after the first and never replaces it",
            "Deleted from PS1 · The 3 discs it lists stay. · Game.sbi stayed behind: it only moves under its disc's name",
            withNote(noted, 7, "Game.sbi stayed behind: it only moves under its disc's name")[0].subtitle,
        )
        assertEquals("the card is still a finished delete", FileQueueState.Done, noted[0].state)
    }

    @Test
    fun `a playlist delete that also takes some of its discs says only that the rest stay, and a cue delete says nothing`() {
        val folder = temp.newFolder()
        val discs = (1..3).map { folder.make("FF9 (Disc $it).chd") }
        val m3u = folder.make("FF9.m3u", discs.joinToString("\n") { it.name })
        val cue = folder.sheet("Other.cue", "Other.bin")
        folder.make("Other.bin")

        val some = planDelete(listOf(m3u.path, discs[0].path))

        assertEquals(listOf(m3u.path, discs[0].path), some.everything)
        assertEquals(listOf("The 2 discs it lists stay."), some.notes)
        assertEquals("no playlist, no note", emptyList<String>(), planDelete(listOf(cue.path)).notes)
    }

    // -- the cancelled card counts what was picked (P4) ----------------------------------------------------------

    @Test
    fun `a cancelled move counts the files the player picked even when a cue's tracks and sbi rode along`() {
        val root = temp.newFolder()
        val cue = root.sheet("from/Game.cue", "Game (Track 1).bin", "Game (Track 2).bin").path
        root.make("from/Game (Track 1).bin")
        root.make("from/Game (Track 2).bin")
        root.make("from/Game.sbi")
        val destination = File(root, "PS1").path
        val set = DiscCompanionFiles.companionSet(listOf(cue))
        assertEquals("the sheet, two tracks and the sbi are moving", 4, set.paths.size)

        val early = cancelledTransferCard(FileOperation.MOVE, listOf(cue), set.paths, destination, mapOf(cue to "$destination/Game.cue"), emptyList())
        assertEquals(
            "Too late to stop: everything you picked was already moved to PS1. The 3 files that go with it are still where they were.",
            early.kept,
        )

        val before = cancelledTransferCard(FileOperation.MOVE, listOf(cue), set.paths, destination, emptyMap(), emptyList())
        assertEquals("Nothing was moved. Everything is still where it was.", before.kept)

        val two = listOf("/r/A.chd", "/r/B.chd")
        val moving = listOf("/r/A.chd", "/r/A.sbi", "/r/B.chd", "/r/B.sbi")
        val half = cancelledTransferCard(
            FileOperation.MOVE, two, moving, destination, mapOf("/r/A.chd" to "x/A.chd", "/r/A.sbi" to "x/A.sbi"), emptyList(),
        )
        assertEquals(
            "two files were picked and one landed: the sbi that landed beside it is not a third",
            "1 of 2 files were moved to PS1 and kept. The rest are still where they were.",
            half.kept,
        )
    }

    // -- a follower left behind under Skip is not explained (P4) -------------------------------------------------

    @Test
    fun `an sbi left behind under Skip adds no sentence to the card, and under Keep both it still does`() {
        for (resolution in listOf(ConflictResolution.SKIP, ConflictResolution.RENAME)) {
            val root = temp.newFolder()
            val disc = root.make("from/Game.chd")
            val sbi = root.make("from/Game.sbi")
            val to = File(root, "to").apply { mkdirs() }
            root.make("to/Game.sbi", "ANOTHER GAME'S SBI")
            val set = DiscCompanionFiles.companionSet(listOf(disc.path))
            assertEquals(listOf(disc.path, sbi.path), set.paths)

            val (result, skipped) = move(disc, to, resolution)
            val leftBehind = skipped.filter { it in set.followers } // what the store records for the card

            assertTrue("$resolution: ${result.exceptionOrNull()}", result.isSuccess)
            assertEquals("$resolution: the sbi stayed in its old folder", listOf(sbi.path), leftBehind)
            assertTrue(sbi.isFile)
            val notes = leftBehindNotes(resolution, leftBehind, set.followers)
            val card = FileQueueEntry(3, JobKind.MOVE, "Game.chd", "Moved to to", FileQueueState.Done)
            val noted = notes.fold(listOf(card)) { entries, note -> withNote(entries, 3, note) }
            if (resolution == ConflictResolution.SKIP) {
                assertEquals("Skip was the player's own choice: nothing to explain", emptyList<String>(), notes)
                assertEquals(listOf(card), noted)
            } else {
                assertEquals(listOf("Game.sbi stayed behind: it only moves under its disc's name"), notes)
                assertEquals("Moved to to · Game.sbi stayed behind: it only moves under its disc's name", noted.single().subtitle)
            }
        }
    }

    @Test
    fun `the left-behind sentence says why - the name a cue lists, or the name of the disc`() {
        val followers = linkedMapOf(
            "/r/Game (Track 1).bin" to DiscCompanionFiles.CompanionFollower("/r/Game.cue", keepsOwnName = true),
            "/r/Game.sbi" to DiscCompanionFiles.CompanionFollower("/r/Game.cue", keepsOwnName = false),
        )

        assertEquals(
            listOf(
                "Game (Track 1).bin stayed behind: its cue lists it under that name",
                "Game.sbi stayed behind: it only moves under its disc's name",
            ),
            leftBehindNotes(ConflictResolution.RENAME, followers.keys.toList(), followers),
        )
        assertEquals(emptyList<String>(), leftBehindNotes(ConflictResolution.SKIP, followers.keys.toList(), followers))
    }
}

package com.gruning.mimir.rewrite.core.files

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep-both regression: **a disc's `.sbi` lands under its disc's final name, or stays behind -- it is never
 * renamed on its own.**
 *
 * `Game.sbi` clashed at the destination while `Game.chd` did not, the player chose Keep both, and the engine named the
 * arriving sbi `Game (copy).sbi` beside a `Game.chd`: a file no disc can find. A follower takes the name its leader
 * landed under, and when that name is taken it is left where it was, reported through `onSkipped`.
 */
class ManagedFileOperationsFollowersTest {

    private val roots = mutableListOf<Path>()

    @After
    fun cleanup() {
        roots.forEach { it.toFile().deleteRecursively() }
        roots.clear()
    }

    private fun tempRoot(): File = Files.createTempDirectory("mimir-followers").also { roots.add(it) }.toFile()

    private fun File.file(name: String, text: String) = File(this, name).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    private val operations = ManagedFileOperations()

    private class Outcome(val landed: Map<String, String>, val skipped: List<String>, val result: Result<Int>)

    /** Moves [disc] and [sbi], the sbi following the disc and taking its name with an `.sbi` suffix. */
    private fun moveWithFollower(disc: File, sbi: File, destination: File, resolution: ConflictResolution): Outcome {
        val landed = LinkedHashMap<String, String>()
        val skipped = mutableListOf<String>()
        val result = runBlocking {
            operations.move(
                sources = listOf(disc.path, sbi.path),
                destination = destination.path,
                conflictResolution = resolution,
                onSkipped = { skipped += it },
                followers = mapOf(
                    sbi.path to MoveFollower(disc.path) { leaderLanded -> File(leaderLanded).nameWithoutExtension + ".sbi" },
                ),
                onLanded = { from, to -> landed[from] = to },
            )
        }
        return Outcome(landed, skipped, result)
    }

    @Test
    fun `keep both leaves an sbi behind rather than rename it apart from a disc that landed under its own name`() {
        val root = tempRoot()
        val disc = root.file("from/Game.chd", "DISC")
        val sbi = root.file("from/Game.sbi", "SBI")
        val to = File(root, "to").apply { mkdirs() }
        root.file("to/Game.sbi", "ANOTHER GAME'S SBI")

        val outcome = moveWithFollower(disc, sbi, to, ConflictResolution.RENAME)

        assertTrue("the move itself succeeded: ${outcome.result}", outcome.result.isSuccess)
        assertEquals("the disc landed under its own name", "DISC", File(to, "Game.chd").readText())
        assertEquals("the incumbent sbi is untouched", "ANOTHER GAME'S SBI", File(to, "Game.sbi").readText())
        assertFalse("and no Game (copy).sbi was made for a disc that is not called that", File(to, "Game (copy).sbi").exists())
        assertEquals("the sbi stayed with its old home", "SBI", sbi.readText())
        assertEquals("and it is reported as left behind", listOf(sbi.path), outcome.skipped)
        assertEquals("only the disc landed", setOf(disc.path), outcome.landed.keys)
    }

    @Test
    fun `when the disc is renamed on arrival the sbi follows it to the same new name`() {
        val root = tempRoot()
        val disc = root.file("from/Game.chd", "DISC")
        val sbi = root.file("from/Game.sbi", "SBI")
        val to = File(root, "to").apply { mkdirs() }
        root.file("to/Game.chd", "ANOTHER COPY")

        val outcome = moveWithFollower(disc, sbi, to, ConflictResolution.RENAME)

        assertTrue(outcome.result.isSuccess)
        assertEquals("ANOTHER COPY", File(to, "Game.chd").readText())
        assertEquals("the disc was kept beside the incumbent", "DISC", File(to, "Game (copy).chd").readText())
        assertEquals("and its sbi took the same name", "SBI", File(to, "Game (copy).sbi").readText())
        assertTrue("nothing was left behind", outcome.skipped.isEmpty() && !sbi.exists())
    }

    @Test
    fun `when the disc is skipped its sbi is not moved without it`() {
        val root = tempRoot()
        val disc = root.file("from/Game.chd", "DISC")
        val sbi = root.file("from/Game.sbi", "SBI")
        val to = File(root, "to").apply { mkdirs() }
        root.file("to/Game.chd", "ANOTHER COPY")

        val outcome = moveWithFollower(disc, sbi, to, ConflictResolution.SKIP)

        assertTrue(outcome.result.isSuccess)
        assertTrue("both stayed where they were", disc.exists() && sbi.exists())
        assertFalse("and no stray sbi reached the destination", File(to, "Game.sbi").exists())
        assertEquals(setOf(disc.path, sbi.path), outcome.skipped.toSet())
    }

    // -- a .cue's track keeps the name the sheet lists, whatever the sheet itself lands as  --

    private fun moveCueWithTrack(cue: File, track: File, destination: File, resolution: ConflictResolution): Outcome {
        val landed = LinkedHashMap<String, String>()
        val skipped = mutableListOf<String>()
        val result = runBlocking {
            operations.move(
                sources = listOf(cue.path, track.path),
                destination = destination.path,
                conflictResolution = resolution,
                onSkipped = { skipped += it },
                followers = mapOf(track.path to MoveFollower(cue.path) { _ -> track.name }),
                onLanded = { from, to -> landed[from] = to },
            )
        }
        return Outcome(landed, skipped, result)
    }

    @Test
    fun `a track keeps its listed name when the sheet is renamed on arrival`() {
        val root = tempRoot()
        val cue = root.file("from/Game.cue", "SHEET")
        val track = root.file("from/Game (Track 1).bin", "TRACK")
        val to = File(root, "to").apply { mkdirs() }
        root.file("to/Game.cue", "ANOTHER SHEET")

        val outcome = moveCueWithTrack(cue, track, to, ConflictResolution.RENAME)

        assertTrue(outcome.result.isSuccess)
        assertEquals("the sheet was kept beside the incumbent", "SHEET", File(to, "Game (copy).cue").readText())
        assertEquals("and the track is still called what the sheet lists", "TRACK", File(to, "Game (Track 1).bin").readText())
        assertFalse(File(to, "Game (Track 1) (copy).bin").exists())
        assertTrue(outcome.skipped.isEmpty())
    }

    @Test
    fun `a track whose listed name is taken stays behind rather than land under a name no sheet lists`() {
        val root = tempRoot()
        val cue = root.file("from/Game.cue", "SHEET")
        val track = root.file("from/Game (Track 1).bin", "TRACK")
        val to = File(root, "to").apply { mkdirs() }
        root.file("to/Game (Track 1).bin", "ANOTHER GAME'S TRACK")

        val outcome = moveCueWithTrack(cue, track, to, ConflictResolution.RENAME)

        assertTrue(outcome.result.isSuccess)
        assertEquals("SHEET", File(to, "Game.cue").readText())
        assertEquals("the incumbent track is untouched", "ANOTHER GAME'S TRACK", File(to, "Game (Track 1).bin").readText())
        assertEquals("TRACK", track.readText())
        assertEquals(listOf(track.path), outcome.skipped)
    }
}

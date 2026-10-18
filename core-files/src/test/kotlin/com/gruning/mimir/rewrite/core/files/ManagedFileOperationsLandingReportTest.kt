package com.gruning.mimir.rewrite.core.files

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Where a moved file actually landed — [ManagedFileOperations.move]'s `onLanded` report.**
 *
 * This file exists because the whole *Keep both* path had no engine-level test at all, and two callers
 * one module up were quietly inferring the landing as `destination/<originalName>` and checking
 * `exists()`. Under a Keep-both collision that check passes **through the incumbent**: the engine renames
 * the ARRIVING file (`resolveTransferTarget`'s RENAME branch resolves a *sibling*), so the file that was
 * already there keeps the plain name. Existence at that path is evidence of the incumbent, not of this
 * move — and it is true for exactly the reason it should have been false.
 *
 * What that shipped as, in 49acd461: an Undo handle naming the player's own pre-existing file (press
 * Undo, it leaves its folder while the moved file stays behind), and a library row rewritten onto the
 * incumbent's path.
 *
 * Real temp directories, real collisions, real bytes — the assertions below are about which file holds
 * which content afterwards, never about the naming policy, so a change to the suffix format does not
 * make this file lie.
 */
class ManagedFileOperationsLandingReportTest {

    private val roots = mutableListOf<Path>()

    @After
    fun cleanup() {
        roots.forEach { it.toFile().deleteRecursively() }
        roots.clear()
    }

    private fun tempRoot(): File = Files.createTempDirectory("mimir-landing-report-test")
        .also { roots.add(it) }
        .toFile()

    private fun File.dir(name: String) = File(this, name).apply { mkdirs() }

    private fun File.file(name: String, text: String) = File(this, name).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    /** Every landing the engine reported, source -> where it put it. */
    private fun landingsOf(
        sources: List<String>,
        destination: String,
        resolution: ConflictResolution,
    ): Map<String, String> {
        val landings = linkedMapOf<String, String>()
        runBlocking {
            ManagedFileOperations()
                .move(sources, destination, resolution) { source, landed ->
                    landings[source] = landed
                }
                .getOrThrow()
        }
        return landings
    }

    @Test
    fun `an uncontested move reports the file at its own name`() {
        val root = tempRoot()
        val from = root.dir("Downloads")
        val to = root.dir("ps2")
        val rom = from.file("Zelda.iso", "the moved bytes")

        val landings = landingsOf(listOf(rom.path), to.path, ConflictResolution.OVERWRITE)

        assertEquals(
            "the ordinary case, and the one the assumed-name inference got right -- which is why it " +
                "survived review",
            File(to, "Zelda.iso").canonicalPath,
            landings[rom.path],
        )
    }

    @Test
    fun `Keep both reports the RENAMED arrival, and the incumbent is not it`() {
        val root = tempRoot()
        val from = root.dir("Downloads")
        val to = root.dir("ps2")
        val rom = from.file("Zelda.iso", "the moved bytes")
        val incumbent = to.file("Zelda.iso", "the file that was already there")

        val landings = landingsOf(listOf(rom.path), to.path, ConflictResolution.RENAME)

        val reported = requireNotNull(landings[rom.path]) { "a Keep-both move still lands somewhere" }
        assertNotEquals(
            "**the whole defect in one assertion.** The engine renames the ARRIVING file, so " +
                "destination/<originalName> still belongs to the incumbent -- and a caller that " +
                "inferred the landing from that name got a file this operation never touched, with " +
                "exists() returning true to confirm it.",
            incumbent.path,
            reported,
        )
        assertEquals(
            "the report names where the MOVED bytes went",
            "the moved bytes",
            File(reported).readText(),
        )
        assertEquals(
            "and the incumbent is untouched, still holding its own content under its own name -- this " +
                "is the file Undo used to move out of the player's folder",
            "the file that was already there",
            incumbent.readText(),
        )
        assertTrue("the source really left", !rom.exists())
    }

    @Test
    fun `a skipped source is reported by ABSENCE, never as a landing`() {
        val root = tempRoot()
        val from = root.dir("Downloads")
        val to = root.dir("ps2")
        val rom = from.file("Zelda.iso", "the moved bytes")
        to.file("Zelda.iso", "the file that was already there")

        val landings = landingsOf(listOf(rom.path), to.path, ConflictResolution.SKIP)

        assertTrue(
            "SKIP writes nothing, so there is nothing to report. Absence is the signal -- `count` " +
                "still increments for a skipped source (the conflated-total defect the store documents " +
                "as OWED), so a caller reading the COUNT cannot tell a skip from a move, and a caller " +
                "reading this map can.",
            landings.isEmpty(),
        )
        assertTrue("and the source is still where it was", rom.exists())
    }

    @Test
    fun `a multi-file move reports each source separately, mixed outcomes included`() {
        val root = tempRoot()
        val from = root.dir("Downloads")
        val to = root.dir("ps2")
        val clean = from.file("Clean.iso", "no collision")
        val colliding = from.file("Collides.iso", "the moved bytes")
        to.file("Collides.iso", "the file that was already there")

        val landings = landingsOf(
            listOf(clean.path, colliding.path),
            to.path,
            ConflictResolution.RENAME,
        )

        assertEquals("both landed", 2, landings.size)
        assertEquals(
            "the uncontested one at its own name",
            File(to, "Clean.iso").canonicalPath,
            landings[clean.path],
        )
        assertNotEquals(
            "and the contested one somewhere else -- per source, because one operation can genuinely " +
                "have both outcomes and a single flag for the whole move could not say which file was " +
                "which",
            File(to, "Collides.iso").canonicalPath,
            landings[colliding.path],
        )
        assertEquals("the moved bytes", File(requireNotNull(landings[colliding.path])).readText())
    }
}

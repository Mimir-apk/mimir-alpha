package com.gruning.mimir.rewrite.core.resolver.scan

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Library scans exclude `.mimir-staging`.
 *
 * The folder holds files in transit -- an extraction's unpacked payload before it is placed, and a delete
 * held for Undo. A scan that walked into it would put a game on the shelf at a path that vanishes seconds
 * later, or that the player has just deleted.
 */
class SourceWalkerStagingTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `the scan lists the player's games and never anything staged`() = runBlocking {
        val root = temporaryFolder.newFolder("roms")
        File(root, "Real Game.gba").writeText("rom")
        File(root, "gb").apply { mkdirs() }.resolve("Other.gb").writeText("rom")
        File(root, ".mimir-staging/.extract-1/payload").mkdirs()
        File(root, ".mimir-staging/.extract-1/payload/Half Placed.gba").writeText("rom")
        File(root, "gb/.mimir-staging/.delete-2/payload").mkdirs()
        File(root, "gb/.mimir-staging/.delete-2/payload/Deleted.gb").writeText("rom")

        val walked = SourceWalker(SourceWalkLimits()).walk(root.path) as SourceWalkResult.Complete

        assertEquals(
            listOf("Real Game.gba", "gb/Other.gb"),
            walked.files.map { it.relativePath }.sorted(),
        )
    }

    @Test
    fun `a folder the player made with a similar name is still scanned`() = runBlocking {
        val root = temporaryFolder.newFolder("roms2")
        File(root, ".mimir-staging-backup").apply { mkdirs() }.resolve("Kept.gba").writeText("rom")

        val walked = SourceWalker(SourceWalkLimits()).walk(root.path) as SourceWalkResult.Complete

        assertEquals(listOf(".mimir-staging-backup/Kept.gba"), walked.files.map { it.relativePath })
    }
}

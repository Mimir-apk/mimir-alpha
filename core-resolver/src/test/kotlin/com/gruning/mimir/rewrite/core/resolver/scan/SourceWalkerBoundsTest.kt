package com.gruning.mimir.rewrite.core.resolver.scan

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceWalkerBoundsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * **A single game can be a source.** A real `Blasphemous 2.steam` shortcut was not recognised.
     *
     * This was the **fourth and last** gate between selecting and scanning that file, and the only one outside
     * `feature-files`. The first three were the dispatch consuming a FILE, `CommitSource` reading
     * `currentPath`, and the dispatch not knowing it was a source pick. Opening those let the path reach
     * the walker, which refused it for a reason nobody had looked at yet — *"a source is a folder"* had
     * simply been true since the beginning.
     *
     * The file becomes its own root, so `relativePath` is its own name and every consumer past this point
     * sees a shape it already understands.
     */
    @Test
    fun `a single file is walked as its own one-file source`() = runBlocking {
        val folder = temporaryFolder.newFolder("downloads")
        val shortcut = File(folder, "Blasphemous 2.steam").apply { writeText("2114740") }
        File(folder, "unrelated.iso").writeText("not mine")

        val walked = SourceWalker(SourceWalkLimits(maxDepth = 0, maxEntries = 8)).walk(shortcut.path)

        val files = (walked as SourceWalkResult.Complete).files
        assertEquals(
            "exactly the file he picked -- enrolling its parent would have taken everything else in " +
                "Downloads with it",
            1,
            files.size,
        )
        assertEquals("Blasphemous 2.steam", files.single().displayName)
        assertEquals(
            "its own name, because the file IS the root -- not a path relative to the folder it sits in",
            "Blasphemous 2.steam",
            files.single().relativePath,
        )
        assertEquals(7L, files.single().sizeBytes)
    }

    @Test
    fun `entry limit accepts N and rejects N plus one without a partial result`() = runBlocking {
        val root = temporaryFolder.newFolder("entry-limit")
        File(root, "one.gba").writeText("1")
        File(root, "two.gba").writeText("2")

        val exact = SourceWalker(SourceWalkLimits(maxDepth = 0, maxEntries = 2)).walk(root.path)
        val exceeded = SourceWalker(SourceWalkLimits(maxDepth = 0, maxEntries = 1)).walk(root.path)

        assertEquals(2, (exact as SourceWalkResult.Complete).files.size)
        assertTrue(exceeded is SourceWalkResult.Failed)
    }

    @Test
    fun `directory entries count toward the total entry limit`() = runBlocking {
        val root = temporaryFolder.newFolder("directory-entry-limit")
        File(root, "one/two").mkdirs()

        val exact = SourceWalker(SourceWalkLimits(maxDepth = 2, maxEntries = 2)).walk(root.path)
        val exceeded = SourceWalker(SourceWalkLimits(maxDepth = 2, maxEntries = 1)).walk(root.path)

        assertEquals(emptyList<WalkedFile>(), (exact as SourceWalkResult.Complete).files)
        assertTrue(exceeded is SourceWalkResult.Failed)
    }

    @Test
    fun `directory depth accepts N and rejects N plus one`() = runBlocking {
        val root = temporaryFolder.newFolder("depth-limit")
        val deepest = File(root, "one/two")
        deepest.mkdirs()
        File(deepest, "game.gba").writeText("rom")

        val exact = SourceWalker(SourceWalkLimits(maxDepth = 2, maxEntries = 3)).walk(root.path)
        val exceeded = SourceWalker(SourceWalkLimits(maxDepth = 1, maxEntries = 3)).walk(root.path)

        assertEquals(1, (exact as SourceWalkResult.Complete).files.size)
        assertTrue(exceeded is SourceWalkResult.Failed)
    }

    @Test
    fun `plain path results have stable relative path order`() = runBlocking {
        val root = temporaryFolder.newFolder("stable-order")
        File(root, "zeta.gba").writeText("z")
        File(root, "alpha.gba").writeText("a")

        val result = SourceWalker(SourceWalkLimits(maxDepth = 0, maxEntries = 2)).walk(root.path)

        assertEquals(
            listOf("alpha.gba", "zeta.gba"),
            (result as SourceWalkResult.Complete).files.map(WalkedFile::relativePath),
        )
    }

    @Test
    fun `file self cycle is rejected boundedly`() = runBlocking {
        val root = temporaryFolder.newFolder("self-cycle")
        val link = File(root, "loop").toPath()
        createSymbolicLinkOrSkip(link, root.toPath())
        try {
            assertTrue(
                SourceWalker(SourceWalkLimits(maxDepth = 8, maxEntries = 32)).walk(root.path) is
                    SourceWalkResult.Failed,
            )
        } finally {
            Files.deleteIfExists(link)
        }
    }

    @Test
    fun `two node directory cycle is rejected boundedly`() = runBlocking {
        val root = temporaryFolder.newFolder("two-node-cycle")
        val first = File(root, "first").apply { mkdirs() }
        val second = File(first, "second").apply { mkdirs() }
        val link = File(second, "back").toPath()
        createSymbolicLinkOrSkip(link, first.toPath())
        try {
            assertTrue(
                SourceWalker(SourceWalkLimits(maxDepth = 8, maxEntries = 32)).walk(root.path) is
                    SourceWalkResult.Failed,
            )
        } finally {
            Files.deleteIfExists(link)
        }
    }

    @Test
    fun `file alias to an already visited node is rejected`() = runBlocking {
        val root = temporaryFolder.newFolder("file-alias")
        val target = File(root, "game.gba").apply { writeText("rom") }
        val link = File(root, "alias.gba").toPath()
        createSymbolicLinkOrSkip(link, target.toPath())
        try {
            assertTrue(
                SourceWalker(SourceWalkLimits(maxDepth = 1, maxEntries = 8)).walk(root.path) is
                    SourceWalkResult.Failed,
            )
        } finally {
            Files.deleteIfExists(link)
        }
    }

    @Test
    fun `symbolic link cannot escape the selected root`() = runBlocking {
        val root = temporaryFolder.newFolder("selected-root")
        val outside = temporaryFolder.newFile("outside.gba").apply { writeText("rom") }
        val link = File(root, "outside.gba").toPath()
        createSymbolicLinkOrSkip(link, outside.toPath())
        try {
            assertTrue(
                SourceWalker(SourceWalkLimits(maxDepth = 1, maxEntries = 8)).walk(root.path) is
                    SourceWalkResult.Failed,
            )
        } finally {
            Files.deleteIfExists(link)
        }
    }

    @Test
    fun `guard rejects duplicate document identity and duplicate relative path`() {
        val duplicateIdentity = SourceTraversalGuard(SourceWalkLimits(maxDepth = 4, maxEntries = 8))
        duplicateIdentity.beginRoot("root")
        duplicateIdentity.observeNode("one", "a.gba", depth = 1, isDirectory = false)
        assertRejected {
            duplicateIdentity.observeNode("one", "b.gba", depth = 1, isDirectory = false)
        }

        val duplicatePath = SourceTraversalGuard(SourceWalkLimits(maxDepth = 4, maxEntries = 8))
        duplicatePath.beginRoot("root")
        duplicatePath.observeNode("one", "same.gba", depth = 1, isDirectory = false)
        assertRejected {
            duplicatePath.observeNode("two", "same.gba", depth = 1, isDirectory = false)
        }
    }

    @Test
    fun `selected symlink root preserves relative and selected file paths`() = runBlocking {
        val target = temporaryFolder.newFolder("root-target")
        File(target, "game.gba").writeText("rom")
        val link = File(temporaryFolder.root, "selected-link").toPath()
        createSymbolicLinkOrSkip(link, target.toPath())
        try {
            val result = SourceWalker(SourceWalkLimits()).walk(link.toString()) as SourceWalkResult.Complete
            assertEquals(listOf("game.gba"), result.files.map { it.relativePath })
            assertEquals(listOf(File(link.toFile(), "game.gba").absolutePath), result.files.map { it.filePath })
        } finally {
            Files.deleteIfExists(link)
        }
    }

    @Test
    fun `escape beneath a selected symlink root rejects the whole traversal`() = runBlocking {
        val target = temporaryFolder.newFolder("linked-root-target")
        val outside = temporaryFolder.newFile("outside-linked-root.gba")
        val rootLink = File(temporaryFolder.root, "linked-root").toPath()
        val childLink = File(target, "escape.gba").toPath()
        createSymbolicLinkOrSkip(rootLink, target.toPath())
        try {
            createSymbolicLinkOrSkip(childLink, outside.toPath())
            try {
                assertTrue(SourceWalker(SourceWalkLimits()).walk(rootLink.toString()) is SourceWalkResult.Failed)
            } finally {
                Files.deleteIfExists(childLink)
            }
        } finally {
            Files.deleteIfExists(rootLink)
        }
    }

    @Test
    fun `dangling child link makes the source unavailable without partial results`() = runBlocking {
        val root = temporaryFolder.newFolder("dangling-root")
        File(root, "valid.gba").writeText("rom")
        val link = File(root, "missing.gba").toPath()
        createSymbolicLinkOrSkip(link, File(temporaryFolder.root, "missing-target").toPath())
        try {
            assertTrue(SourceWalker(SourceWalkLimits()).walk(root.path) is SourceWalkResult.Unavailable)
        } finally {
            Files.deleteIfExists(link)
        }
    }

    private fun createSymbolicLinkOrSkip(link: java.nio.file.Path, target: java.nio.file.Path) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (error: Exception) {
            assumeNoException("Symbolic links are unavailable on this host", error)
        }
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
        } catch (_: SourceTraversalRejectedException) {
            return
        }
        throw AssertionError("Expected SourceTraversalRejectedException")
    }
}

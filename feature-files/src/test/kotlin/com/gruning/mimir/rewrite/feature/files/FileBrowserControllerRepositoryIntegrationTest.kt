package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.LocalFileRepository
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Closes the gap between [FileBrowserController]'s KDoc'd `LocalFileRepository.listFiles` bridge and
 * an actually-executed one: every other controller test fakes `listDirectory` with a plain map (by
 * design -- see that class's KDoc), so nothing else in this suite proves that the current-directory rows from
 * `LocalFileRepository.listFiles` reach the controller end to end against a real filesystem.
 *
 * `runBlocking { listFiles(path).first() }` is safe here specifically because
 * `LocalFileRepository.listFiles`'s own doc comment establishes it emits exactly once (a local
 * directory read, not an unbounded/streaming source) -- the same fact `LocalFileRepositoryTest.kt`
 * (`:core-files`) already exercises on a plain JVM with a real temp directory; this test mirrors that
 * pattern one layer up, through the controller.
 */
class FileBrowserControllerRepositoryIntegrationTest {

    @Test
    fun `the controller lists a real directory through LocalFileRepository, split into tomes and files`() {
        val root = Files.createTempDirectory("mimir-file-browser-controller-integration-test")
        try {
            File(root.toFile(), "zelda.gba").writeText("cartridge payload")
            File(root.toFile(), "Roms").mkdirs()
            File(root.toFile(), "bundle.zip").writeText("not a real zip -- only the name/extension matter here")

            val repository = LocalFileRepository()
            val authority = DefaultFocusAuthority()
            val controller = FileBrowserController(
                authority = authority,
                rootPath = root.toString(),
                listDirectory = { path -> runBlocking { repository.listFiles(path).first() } },
                onChoose = {},
                onDismiss = {},
            )

            controller.open()

            assertEquals(listOf("Roms"), controller.partition.tomes.map { it.name })
            val byName = controller.partition.files.associateBy { it.name }
            assertEquals(2, byName.size)
            assertEquals(FileBrowserTileKind.ARCHIVE, byName.getValue("bundle.zip").kind)
            assertEquals(FileBrowserTileKind.FILE, byName.getValue("zelda.gba").kind)
            assertTrue(
                "every real tile's path must be the repository's own absolute path, not a reconstructed one",
                (controller.partition.tomes + controller.partition.files).all { it.path.startsWith(root.toString()) },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

package com.gruning.mimir.rewrite.core.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate for a computation that was REMOVED.
 *
 *  — a `Back` into a large directory sat ~100 ms before anything moved.
 * The main thread was parked inside `FileBrowserStore`'s `runBlocking { listFiles(path).first() }` while
 * the IO dispatcher ran a per-row syscall mix. Measured on 441 entries: **11.4 ms full, 6.8 ms lean, a
 * 41 % cut**, entirely by not computing fields nothing reads.
 *
 * `LocalFileRepository.pathToFileItem(path, detailed = false)` therefore leaves `isWritable` at the data
 * class default and `ownerName` / `groupName` / `permissions` / `symlinkTarget` empty on every LISTING
 * row. That is safe only while nothing reads them, so this test is what makes it safe — the day a consumer needs one of those, it fails here and says "ask for `detailed = true`" instead of silently handing over
 * a default that looks measured.
 *
 * Absence of behaviour needs its own gate: one deleted
 * computation, and the thing that protects it is a test that fails when the assumption stops holding.
 */
class LocalFileRepositoryLeanListingTest {

    private fun productionSources(): List<File> = listOf(
        "core-files/src/main/kotlin/com/gruning/mimir/rewrite/core/files",
        "feature-files/src/main/kotlin/com/gruning/mimir/rewrite/feature/files",
        "app/src/main/kotlin/com/gruning/mimir/rewrite",
    ).map(::File)
        .flatMap { dir ->
            listOf(dir, File("../$dir")).firstOrNull(File::isDirectory)
                ?.walkTopDown()?.filter { it.isFile && it.extension == "kt" }?.toList()
                ?: emptyList()
        }

    @Test
    fun `no production code reads a field the lean listing no longer computes`() {
        val sources = productionSources()
        assertTrue("could not locate the production sources to scan", sources.size > 20)

        // FileItem declares them and LocalFileRepository writes them; everyone else must not READ them.
        val exempt = setOf("FileItem.kt", "LocalFileRepository.kt")
        val offenders = mutableListOf<String>()
        for (f in sources.filter { it.name !in exempt }) {
            val text = f.readText()
            for (field in listOf("ownerName", "groupName", "permissions", "symlinkTarget", "isWritable")) {
                text.lineSequence().forEachIndexed { i, line ->
                    if (Regex("""\.\s*$field\b""").containsMatchIn(line) && !line.trimStart().startsWith("*") &&
                        !line.trimStart().startsWith("//")
                    ) {
                        offenders += "${f.name}:${i + 1} reads .$field"
                    }
                }
            }
        }
        assertEquals(
            "These fields are NOT computed for listing rows (LocalFileRepository.pathToFileItem's " +
                "`detailed` flag). Reading one off a listing row gets the data class default, not a " +
                "measured value. Call getFileInfo(), which passes detailed = true, or make the listing " +
                "compute it again and pay the ~41% it costs.",
            emptyList<String>(),
            offenders,
        )
    }
}

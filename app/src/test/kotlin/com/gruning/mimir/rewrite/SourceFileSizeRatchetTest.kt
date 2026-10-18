package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * File-size ratchet: no main Kotlin file may grow past its recorded budget (source-file-size-budget.txt), and a file not
 * listed may have at most [DEFAULT_MAX_LINES] lines. Large files are known debt; this stops them growing. To raise a
 * budget on purpose, run tools/update-file-size-budget.sh and say why in the commit.
 */
class SourceFileSizeRatchetTest {
    private val repoRoot: File = File("..").canonicalFile

    private fun budget(): Map<String, Int> {
        val text = javaClass.classLoader!!.getResource("source-file-size-budget.txt")!!.readText()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line -> line.substringBefore('\t') to line.substringAfter('\t').trim().toInt() }
    }

    private fun mainSources(): List<File> = repoRoot.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
        .filter { it.isFile && it.extension == "kt" && it.invariantSeparatorsPath.contains("/src/main/kotlin/") }
        .toList()

    @Test
    fun `no main source file grows past its budget`() {
        val budget = budget()
        val sources = mainSources()
        assertTrue("fixture: the walk found the app's sources (${sources.size})", sources.size > 200)
        val over = sources.mapNotNull { file ->
            val path = file.relativeTo(repoRoot).invariantSeparatorsPath
            val lines = file.readLines().size
            val max = budget[path] ?: DEFAULT_MAX_LINES
            if (lines > max) "$path: $lines lines, budget $max" else null
        }
        assertTrue(
            "These files grew past their budget. Split them, or raise the budget with tools/update-file-size-budget.sh and a reason:\n" +
                over.joinToString("\n"),
            over.isEmpty(),
        )
    }

    @Test
    fun `every budget entry still names a file`() {
        val missing = budget().keys.filterNot { File(repoRoot, it).isFile }
        assertTrue("Stale budget entries (the file moved or was deleted); regenerate the budget:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    private companion object {
        const val DEFAULT_MAX_LINES = 2000
    }
}

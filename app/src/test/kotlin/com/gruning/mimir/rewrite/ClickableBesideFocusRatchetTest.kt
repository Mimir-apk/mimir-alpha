package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `.clickable` / `.combinedClickable` in the same modifier chain as `.mimirFocusable(` plants a second Compose focus
 * node beside the FocusAuthority's (see `MimirActivatable.kt`); a tap can then act twice or land focus where the
 * controller never put it. Motion audit batch A (2026-09-28) converted the five sites the audit named (the Library's
 * platform card, game tile, CTA and list row, and the Settings rail) to `mimirActivatable`. The scan found 26 more
 * across the product. They move with the ring migration, which touches those files; until then this ratchet holds
 * each file's count and fails on any new one.
 */
class ClickableBesideFocusRatchetTest {

    // None left: the game page's two pairs were the unused DetailActionButton (deleted) and the "Opens with" row (an etched chip on mimirActivatable now, the earlier update).
    private val remaining = emptyMap<String, Int>()

    @Test
    fun `no file gains a clickable beside the focus authority, and the converted five stay converted`() {
        val found = scan()
        val grew = found.filter { (file, count) -> count > (remaining[file] ?: 0) }
        assertTrue("new clickable beside mimirFocusable (use mimirActivatable): $grew", grew.isEmpty())
        assertEquals(0, found["LibraryComponents.kt"] ?: 0)
        assertEquals(0, found["SettingsHome.kt"] ?: 0)
        // A conversion lowers the count; lower the map with it so the ratchet keeps holding.
        val shrank = remaining.filter { (file, count) -> (found[file] ?: 0) < count }
        assertTrue("converted sites: lower this ratchet to $found", shrank.isEmpty())
    }

    private fun scan(): Map<String, Int> {
        val root = listOf(File("."), File("..")).first { File(it, "settings.gradle.kts").isFile }
        val counts = mutableMapOf<String, Int>()
        root.listFiles().orEmpty()
            .map { File(it, "src/main") }
            .filter(File::isDirectory)
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .forEach { file ->
                val lines = file.readLines()
                lines.forEachIndexed { index, line ->
                    val code = line.trim()
                    if (code.startsWith("//") || code.startsWith("*")) return@forEachIndexed
                    if (!ClickableCall.containsMatchIn(line)) return@forEachIndexed
                    val chain = chainAround(lines, index)
                    if (chain.any { FocusableCall.containsMatchIn(it) && !it.trim().startsWith("//") }) {
                        counts.merge(file.name, 1, Int::plus)
                    }
                }
            }
        return counts
    }

    /** The lines of the modifier chain around [index]: `.call` continuations, their argument lists and comments. */
    private fun chainAround(lines: List<String>, index: Int): List<String> {
        val chain = mutableListOf(lines[index])
        var depth = 0
        var j = index - 1
        while (j >= 0) {
            val (opens, closes) = brackets(lines[j])
            depth += closes - opens
            val code = lines[j].trim()
            chain += lines[j]
            if (depth > 0 || code.startsWith(".") || code.startsWith("//") || code.startsWith("*")) j-- else break
        }
        val (opens, closes) = brackets(lines[index])
        depth = opens - closes
        j = index + 1
        while (j < lines.size) {
            val code = lines[j].trim()
            if (depth > 0 || code.startsWith(".") || code.startsWith("//")) {
                val (o, c) = brackets(lines[j])
                depth += o - c
                chain += lines[j]
                j++
            } else {
                break
            }
        }
        return chain
    }

    private fun brackets(line: String): Pair<Int, Int> {
        val code = line.substringBefore("//").replace(StringLiteral, "\"\"")
        return code.count { it == '(' || it == '{' } to code.count { it == ')' || it == '}' }
    }

    private companion object {
        val ClickableCall = Regex("""\.(clickable|combinedClickable)\s*[({]""")
        val FocusableCall = Regex("""\.mimirFocusable\(""")
        val StringLiteral = Regex(""""(?:[^"\\]|\\.)*"""")
    }
}

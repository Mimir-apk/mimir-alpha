package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Motion audit 2026-09-28, batch F, the last item: the literal-duration pin. Every duration in a `tween(` and every
 * `delayMillis` in production code outside core-ui's token file is a name from
 * [com.gruning.mimir.rewrite.core.ui.MimirMotion] or a constant derived from one, never a raw number. The grammar has
 * one set of clocks; a literal is a second clock nobody ruled on.
 *
 * The allow-list is explicit and dated: literals that predate the audit and belong to a lane or a ruling batch F does
 * not own, each naming who retires it. An entry that no longer matches FAILS this test, so the list can only shrink
 * deliberately -- there is no silent skip. Comments are stripped before the scan (a KDoc that quotes a retired
 * `tween(240)` is prose, not a clock).
 */
class MimirMotionLiteralPinTest {

    private class Allowed(val file: String, val literal: String, val count: Int, val since: String, val retiredBy: String)

    private val tokenFile = "core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirTokens.kt"

    private val allowed = listOf(
        Allowed(
            "core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirAtmosphere.kt",
            "tween(1100, easing = FastOutSlowInEasing)",
            1,
            "2026-09-28",
            "the starfield ambient phase is atmosphere; changing to an ambient token remains a design decision",
        ),
        // The Bottom tile's `tween(170, easing = EaseOutCubic)` (the Bottom's tile lift) was retired by Q6 : FocusMs, MimirMotion.Easing.
    )

    private val literal = Regex("""tween\s*(?:<[^>]*>)?\s*\(\s*(?:durationMillis\s*=\s*)?\d+|delayMillis\s*=\s*\d+""")

    /** Deliberately fails rather than skipping when the tree is unreachable: a gate that assumes itself away reads green. */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "core-ui").isDirectory) return dir
            dir = dir.parentFile
        }
        error("repository root not found from ${File(".").absolutePath}")
    }

    private fun productionSources(root: File): List<File> = root.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
        .filter { it.isFile && it.extension == "kt" }
        .filter { file -> ("/" + file.relativeTo(root).invariantSeparatorsPath).contains("/src/main/") }
        .toList()

    /** Comments name clocks by their numbers constantly; reading them measures prose. Line numbers are kept. */
    private fun stripComments(text: String): String = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)) { m -> m.value.replace(Regex("[^\n]"), " ") }
        .lineSequence()
        .joinToString("\n") { line -> line.substringBefore("//") }

    @Test
    fun `no raw millisecond literal in a tween or delayMillis outside the token file and the dated allow-list`() {
        val root = repoRoot()
        val offenders = mutableListOf<String>()
        val seen = mutableMapOf<Pair<String, String>, Int>()
        productionSources(root).forEach { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            if (path == tokenFile) return@forEach
            stripComments(file.readText()).lines().forEachIndexed { index, line ->
                literal.findAll(line).forEach { match ->
                    val entry = allowed.firstOrNull { it.file == path && line.contains(it.literal) }
                    if (entry == null) {
                        offenders += "$path:${index + 1}: ${match.value}"
                    } else {
                        seen[entry.file to entry.literal] = (seen[entry.file to entry.literal] ?: 0) + 1
                    }
                }
            }
        }
        assertTrue(
            "raw millisecond literals outside MimirTokens.kt -- name a MimirMotion token, or add a DATED allow-list " +
                "entry that says who retires it:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
        allowed.forEach { entry ->
            assertEquals(
                "stale allow-list entry (since ${entry.since}; to be retired by ${entry.retiredBy}): ${entry.file} " +
                    "no longer holds `${entry.literal}` x${entry.count} -- delete the entry rather than leave it",
                entry.count,
                seen[entry.file to entry.literal] ?: 0,
            )
        }
    }
}

package com.gruning.mimir.rewrite.core.launch.remotedefs

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mimir's own matcher for the restricted filename dialect: it must give the answer `kotlin.text.Regex` gives
 * on the whole dialect, cost a bounded number of steps whatever the pattern and the name, and never throw.
 */
class RecognitionMatcherTest {

    /** Patterns the validator accepts, covering every construct of the dialect. */
    private val corpusPatterns = listOf(
        "^\$",
        "^a\$",
        "^eboot\\.bin\$",
        "^.*\\.(?:iso|cso)\$",
        "^.+\\.chd\$",
        "^(?:default\\.xbe|eboot\\.bin)\$",
        "^[a-z0-9_]+\\.chd\$",
        "^a?b?c?d?e\$",
        "^disc[0-9]?\\.(?:bin|cue)\$",
        "^[a-z]-[0-9]\$",
        "^[a-z0-9_-]+\\.(?:iso|bin)?\$",
        "^(?:a|aa)?(?:a|aa)?(?:a|aa)?(?:a|aa)?a*b\$",
        "^(?:a?b|c)(?:d|e?f)?g\$",
        "^(?:|a)b\$",
        "^(?:a|)b\$",
        "^(?:)\$",
        "^x(?:)y\$",
        "^[.]+x\$",
        "^[a-]x\$",
        "^[-a]x\$",
        "^[a-c-e]x\$",
        "^...\$",
        "^.\$",
        "^.*\$",
        "^.+\$",
        "^[0-9a-f]+\\.[0-9a-f]\$",
        "^game(?:-|_)?[0-9]+\\.(?:nsp|xci)\$",
        "^(?:a|b|c|d|e|f|g|h|i|j|k|l|m|n|o|p)x\$",
        "^[a-z0-9]*\\.zip\$",
        "^\\.hidden\$",
        "^a\\.\\.b\$",
    )

    private val corpusNames = listOf(
        "", "a", "b", "x", "ab", "aab", "aaaab", "aaaaaaab", "aaaaaaaaaaaaaaaaaaaaaaaaaaaab", "aaaaaaaaaaaaaaaaaaaaaaaaaaaac",
        "game.iso", "GAME.ISO", "Game.Cso", "game.cso", "game.isox", "xgame.iso", ".iso", "..iso", "a.b.iso", "game.bin",
        "eboot.bin", "EBOOT.BIN", "default.xbe", "x.chd", ".chd", "disc.bin", "disc1.cue", "disc12.cue", "disc9.bin",
        "a-1", "a_1", "z-9", "z-a", "game-12.nsp", "game_7.xci", "game7.xci", "game-.xci", "game.zip", "0a1b2c.zip",
        "abcdefg", "acdg", "abdg", "bg", "cg", "cfg", "cdg", "beg", "befg", "ag", "bx", "ax", "x-y", "xy", "ay",
        "...", "..", ".", "a.x", "..x", "x", "...x", "....x", "a-x", "-x", "bx", "cx", "ex",
        "ff.0", "1f.f", "abc.d", ".hidden", "a..b", "a.b",
        "tab\tname.iso", "new\nline.iso", "cr\rname", "name\u0085x", "name x", "name ", "line\n", "x\n",
        "café.iso", "ゲーム.iso", "emoji😀.iso", "😀", "😀😀", "😀x", "lone\uD83Dx",
        "İ.iso", "A".repeat(100), "a".repeat(255), "a".repeat(254) + "b", "b" + "a".repeat(250) + "b",
        ("ab".repeat(120)) + "c",
    ).filter { it.length <= RecognitionRegex.MAX_NAME_CHARS }

    @Test
    fun every_corpus_pattern_is_inside_the_dialect_and_compiles() {
        for (pattern in corpusPatterns) {
            assertNull("the validator should accept $pattern", RecognitionRegex.problem(pattern))
            assertNotNull("should compile: $pattern", RecognitionRegex.compile(pattern))
        }
    }

    @Test
    fun it_agrees_with_kotlin_regex_on_the_corpus() {
        var compared = 0
        for (pattern in corpusPatterns) {
            val mine = RecognitionRegex.compile(pattern)!!
            val reference = Regex(pattern)
            for (name in corpusNames) {
                val expected = reference.matches(name.lowercase())
                assertEquals("pattern $pattern on ${name.escaped()}", expected, mine.matches(name))
                compared++
            }
        }
        assertTrue("the corpus is not trivially small ($compared)", compared > 2_000)
    }

    @Test
    fun it_agrees_with_kotlin_regex_on_generated_patterns_and_names() {
        val random = Random(242)
        val alphabet = "abcdexyz019_-."
        var patterns = 0
        var compared = 0
        repeat(3_000) {
            val pattern = randomPattern(random)
            if (RecognitionRegex.problem(pattern) != null) return@repeat
            // java.util.regex refuses `[x--]`-style classes that the dialect's own reading accepts; keep to patterns it can build.
            val reference = runCatching { Regex(pattern) }.getOrNull() ?: return@repeat
            val mine = RecognitionRegex.compile(pattern)!!
            patterns++
            repeat(40) {
                val name = buildString { repeat(random.nextInt(12)) { append(alphabet[random.nextInt(alphabet.length)]) } }
                assertEquals("pattern $pattern on ${name.escaped()}", reference.matches(name), mine.matches(name))
                compared++
            }
        }
        assertTrue("enough generated patterns were valid ($patterns)", patterns > 300)
        assertTrue(compared > 10_000)
    }

    /** A pattern built from the dialect's own parts, in an order the validator may or may not accept. */
    private fun randomPattern(random: Random): String {
        val atoms = listOf("a", "b", "x", "0", "_", "-", "\\.", ".", "[a-c]", "[0-9a]", "[.]", "[x-z_]")
        val body = StringBuilder()
        repeat(random.nextInt(6)) {
            when (random.nextInt(6)) {
                0 -> {
                    body.append("(?:")
                    repeat(1 + random.nextInt(3)) { index ->
                        if (index > 0) body.append('|')
                        repeat(random.nextInt(3)) {
                            body.append(atoms[random.nextInt(atoms.size)])
                            if (random.nextInt(4) == 0) body.append('?')
                        }
                    }
                    body.append(')')
                    if (random.nextBoolean()) body.append('?')
                }
                else -> {
                    body.append(atoms[random.nextInt(atoms.size)])
                    when (random.nextInt(5)) {
                        0 -> body.append('?')
                        1 -> body.append('*')
                        2 -> body.append('+')
                    }
                }
            }
        }
        return "^$body\$"
    }

    private fun String.escaped(): String = map { if (it.code in 32..126) it.toString() else "\\u%04x".format(it.code) }.joinToString("")

    // ---- bounded work -------------------------------------------------------------------------------------------

    @Test(timeout = 20_000)
    fun the_worst_shapes_cost_a_bounded_number_of_steps_on_the_longest_name() {
        val shapes = listOf(
            "^(?:a|aa)?(?:a|aa)?(?:a|aa)?(?:a|aa)?a*b\$",
            "^a?a?a?a?a*b\$",
            "^.*\\.(?:iso|cso)\$",
            "^(?:a|a|a|a|a|a|a|a|a|a|a|a|a|a|a|a)?(?:a|a|a|a|a|a|a|a|a|a|a|a|a|a|a|a)?a+c\$",
            "^[a-c]*x\$",
            "^.+\\.chd\$",
        )
        for (pattern in shapes) {
            assertNull("the validator should accept $pattern", RecognitionRegex.problem(pattern))
            val compiled = RecognitionRegex.compile(pattern)!!
            for (name in listOf("a".repeat(255), "a".repeat(254) + "c", "ab".repeat(127), "a".repeat(250) + ".iso")) {
                val outcome = compiled.run(name)
                assertTrue(
                    "$pattern on a ${name.length}-character name cost ${outcome.steps} steps, over the ceiling ${compiled.stepCeiling(name.length)}",
                    outcome.steps <= compiled.stepCeiling(name.length),
                )
            }
        }
    }

    @Test
    fun the_cost_grows_linearly_with_the_name_not_with_its_ambiguity() {
        val compiled = RecognitionRegex.compile("^(?:a|aa)?(?:a|aa)?(?:a|aa)?(?:a|aa)?a*b\$")!!
        val half = compiled.run("a".repeat(120) + "c").steps
        val full = compiled.run("a".repeat(240) + "c").steps
        assertTrue("half=$half full=$full", full <= half * 2 + compiled.instructionCount * 2L)
        // And every name, whatever it holds, stays under the ceiling.
        for (length in listOf(0, 1, 7, 64, 200, 255)) {
            val outcome = compiled.run("a".repeat(length))
            assertTrue(outcome.steps <= compiled.stepCeiling(length))
        }
    }

    // ---- limits and safety --------------------------------------------------------------------------------------

    @Test
    fun a_name_over_255_characters_never_matches() {
        val any = RecognitionRegex.compile("^.*\$")!!
        assertTrue(any.matches("a".repeat(255)))
        assertFalse(any.matches("a".repeat(256)))
        assertFalse(any.matches("a".repeat(1_000)))
        val iso = RecognitionRegex.compile("^.*\\.iso\$")!!
        assertTrue(iso.matches("a".repeat(251) + ".iso"))
        assertFalse(iso.matches("a".repeat(252) + ".iso"))
        // Lowercasing can make a name longer: this one is 200 characters and 400 once lowered, so it is past the limit.
        assertFalse(any.matches("İ".repeat(200)))
        assertEquals(0L, any.run("a".repeat(256)).steps)
    }

    @Test
    fun an_unreadable_pattern_compiles_to_nothing_and_nothing_throws() {
        val garbage = listOf(
            "", "^", "\$", "^(\$", "^(?:a\$", "^[a\$", "^[z-a]\$", "^a{2}\$", "^(a+)+\$", "^(?:(?:a))\$", "^a**\$", "^\\\$",
            "^" + "a".repeat(120) + "\$", "^[\u0000]\$", "^é\$", "^a|b\$", "^(?<n>a)\$", "^\\1\$", "^(?=a)\$",
        )
        for (pattern in garbage) assertNull("should not compile: $pattern", RecognitionRegex.compile(pattern))
        val random = Random(7)
        val soup = "^\$()[]{}|?*+.\\-:abz09 \n"
        repeat(2_000) {
            val pattern = buildString { repeat(random.nextInt(25)) { append(soup[random.nextInt(soup.length)]) } }
            val compiled = RecognitionRegex.compile(pattern)
            if (compiled != null) assertNull("only a valid pattern compiles: $pattern", RecognitionRegex.problem(pattern))
            compiled?.matches("name.iso")
        }
    }

    @Test
    fun matching_is_against_the_lowercase_name_and_a_dot_is_any_non_line_break() {
        val marker = RecognitionRegex.compile("^eboot\\.bin\$")!!
        assertTrue(marker.matches("EBOOT.BIN"))
        assertFalse(marker.matches("eboot_bin"))
        val dots = RecognitionRegex.compile("^...\$")!!
        assertTrue(dots.matches("abc"))
        assertFalse(dots.matches("a\nc"))
        assertTrue("one code point, not two chars", RecognitionRegex.compile("^.\$")!!.matches("😀"))
    }
}

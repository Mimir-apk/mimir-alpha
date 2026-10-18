package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate for the declaration-site class that keeps shipping on this rail.
 *
 * `brightnessControls` and `brightnessRailControls` carry defaults for almost every parameter, so a
 * caller that forgets one compiles clean and silently gets `NOT_INSTALLED` / `NoSubject` instead of
 * device truth. That has now shipped three times against the same rail:
 *
 *  - `BrightnessOverlay`'s reveal list omitted `tweakerFpsCapRow`, so the frame-cap row was drawn and
 *    NAV-reachable while `utilityRailReveal` returned NONE for it and it never settled into view.
 *  - the same `remember` block omitted `tweakerRow.availability` from its KEYS, so an availability
 *    change left a stale list behind.
 *  - `open()`'s `visible` list omitted both, and it decides which row the panel lands on.
 *
 * [UtilityControlOrderTest.railRevealAndFocusOrderNeverDisagreeForTheSameInputs] cannot see any of
 * them: it hands both functions the SAME inputs, and the whole defect is that the call sites did not.
 * The compiler cannot see them either, which is what makes a source assertion the right instrument —
 * the property is about how the call is *written*, not about what either function computes.
 *
 * Scope is production source only. Tests legitimately lean on the defaults to stay readable; a test
 * that names five inputs is not the failure mode this protects against.
 */
class BrightnessControlCallSiteContractTest {

    private val gatedFunctions = listOf("brightnessControls", "brightnessRailControls")

    @Test
    fun `every production call passes every parameter, so no caller can silently default one`() {
        val sources = productionSources()
        val declarations = declarationArity()

        val offenders = mutableListOf<String>()
        sources.forEach { file ->
            val text = stripComments(file.readText())
            gatedFunctions.forEach { function ->
                callSites(text, function).forEach { args ->
                    val expected = declarations.getValue(function)
                    if (args.size != expected) {
                        offenders += "${file.name}: $function(...) passes ${args.size} of $expected " +
                            "-- missing arguments default silently: ${args.joinToString()}"
                    }
                }
            }
        }

        assertEquals(
            "a call site that omits a parameter takes a default instead of device truth:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * An argument that is not also a `remember` key freezes: the list is rebuilt only when one of the
     * keys changes, so a row can appear or vanish in the device state and never reach the rail. Keys
     * and arguments are therefore required to be the same SET (order is irrelevant to both).
     */
    @Test
    fun `the rail reveal list keys on exactly the inputs it passes`() {
        val overlay = productionSources().single { it.name == "BrightnessOverlay.kt" }
        val text = stripComments(overlay.readText())

        val callIndex = text.indexOf("brightnessRailControls(")
        assertTrue("brightnessRailControls call not found in BrightnessOverlay.kt", callIndex > 0)
        val rememberIndex = text.lastIndexOf("remember(", callIndex)
        assertTrue("no remember block wraps the rail reveal list", rememberIndex > 0)

        val keys = argumentsAt(text, text.indexOf('(', rememberIndex)).toSet()
        val passed = argumentsAt(text, text.indexOf('(', callIndex)).toSet()

        assertEquals(
            "arguments that are not remember keys make the list stale; keys that are not arguments " +
                "rebuild it for nothing",
            passed,
            keys,
        )
    }

    /**
     * Both gates read arity from the declaration rather than a hardcoded number, so ADDING a parameter
     * fails them until every call site is updated. That is the point: the next parameter is the next
     * instance of this defect.
     */
    @Test
    fun `both gated functions still take the same input list`() {
        val declarations = declarationArity()
        assertEquals(
            "the two lists must stay parallel; a parameter on one and not the other is the same defect",
            declarations.getValue("brightnessControls"),
            declarations.getValue("brightnessRailControls"),
        )
    }

    private fun declarationArity(): Map<String, Int> {
        val host = productionSources().single { it.name == "AppBrightnessHost.kt" }
        val text = host.readText()
        return gatedFunctions.associateWith { function ->
            val declaration = text.indexOf("fun $function(")
            require(declaration > 0) { "$function declaration not found in AppBrightnessHost.kt" }
            argumentsAt(text, text.indexOf('(', declaration)).size
        }
    }

    /** Call sites of [function], excluding its own declaration. */
    private fun callSites(text: String, function: String): List<List<String>> {
        val hits = Regex("(?<![A-Za-z0-9_])$function\\s*\\(").findAll(text)
        return hits.mapNotNull { hit ->
            val before = text.substring(0, hit.range.first)
            if (before.trimEnd().endsWith("fun")) return@mapNotNull null
            argumentsAt(text, text.indexOf('(', hit.range.first))
        }.toList()
    }

    /**
     * Top-level, comma-separated arguments of the parenthesised list opening at [openIndex]. Depth
     * counting handles nested calls and default values; it would NOT handle a generic type carrying a
     * comma, and neither list has one today.
     */
    private fun argumentsAt(text: String, openIndex: Int): List<String> {
        require(text[openIndex] == '(') { "expected '(' at $openIndex" }
        val arguments = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var index = openIndex
        while (index < text.length) {
            when (val character = text[index]) {
                '(' -> {
                    depth++
                    if (depth > 1) current.append(character)
                }
                ')' -> {
                    depth--
                    if (depth == 0) {
                        current.toString().trim().takeIf(String::isNotEmpty)?.let(arguments::add)
                        return arguments
                    }
                    current.append(character)
                }
                ',' -> if (depth == 1) {
                    current.toString().trim().takeIf(String::isNotEmpty)?.let(arguments::add)
                    current.clear()
                } else {
                    current.append(character)
                }
                else -> if (depth >= 1) current.append(character)
            }
            index++
        }
        error("unbalanced parentheses from $openIndex")
    }

    /**
     * Deliberately fails rather than skipping when the tree is unreachable. A gate that quietly assumes
     * itself away reads exactly like a passing gate, which is the failure this whole file exists to
     * stop.
     */
    private fun productionSources(): List<File> {
        val root = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite"),
            File("app/src/main/kotlin/com/gruning/mimir/rewrite"),
            File("../app/src/main/kotlin/com/gruning/mimir/rewrite"),
        ).firstOrNull { it.isDirectory }
        assertTrue("production sources not reachable from the test working directory", root != null)
        return root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** Comments name these functions and their parameters constantly; reading them measures prose. */
    private fun stripComments(text: String): String = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lineSequence()
        .joinToString("\n") { line -> line.substringBefore("//") }
}

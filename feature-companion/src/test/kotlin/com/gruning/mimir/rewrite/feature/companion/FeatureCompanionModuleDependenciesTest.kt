package com.gruning.mimir.rewrite.feature.companion

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `:feature-companion` builds against `:core-model` and `:core-ui` and no other project.
 *
 * The Bottom screen's module sees the shared read-model and the design system, and nothing from
 * `:feature-library` or `:app`; the app hands it plain values. One added line in `build.gradle.kts` would
 * end that quietly, so the declaration is pinned where it is made. Every project reference counts,
 * whatever configuration declares it, test-only ones included: widening the rule should be a decision
 * someone makes here, not a side effect of a build edit.
 *
 * The pin used to sit inside `CompanionCollectionCommandPathTest`, which was deleted with the collection
 * switch .
 */
class FeatureCompanionModuleDependenciesTest {

    private val buildScript: String = File("build.gradle.kts").readText()

    @Test
    fun `the module references core-model and core-ui and no other project`() {
        assertTrue(
            "this must be :feature-companion's own build script",
            buildScript.contains("namespace = \"com.gruning.mimir.rewrite.feature.companion\""),
        )
        assertOnlyCoreModelAndCoreUi(buildScript)
    }

    /**
     * The red arm, run without touching the real file (no broken code in the tree
     * to prove a pin bites). The same check reads this module's own script with one extra line, in each form
     * a project dependency can take, and must fail naming the extra project every time.
     */
    @Test
    fun `an extra project dependency fails the pin in every declaration form`() {
        mapOf(
            "implementation(project(\":feature-library\"))" to ":feature-library",
            "api(project(\":core-input\"))" to ":core-input",
            "testImplementation(project(path = \":app\"))" to ":app",
            "implementation(projects.coreInput)" to "projects.coreInput",
        ).forEach { (extra, named) ->
            val widened = buildScript.replaceFirst("dependencies {", "dependencies {\n    $extra")
            assertNotEquals("the extra line must land in the script under test", buildScript, widened)
            val failure = runCatching { assertOnlyCoreModelAndCoreUi(widened) }.exceptionOrNull()
            assertTrue("`$extra` must fail the pin, got $failure", failure is AssertionError)
            assertTrue(
                "the failure must name $named: ${failure?.message}",
                failure?.message.orEmpty().contains(named),
            )
        }
    }

    private fun assertOnlyCoreModelAndCoreUi(script: String) {
        assertEquals(
            "`:feature-companion` may reference only :core-model and :core-ui as projects",
            setOf(":core-model", ":core-ui"),
            projectReferences(script),
        )
    }

    /** Every project the script names, by path or by type-safe accessor. Text after `//` is ignored. */
    private fun projectReferences(script: String): Set<String> {
        val code = script.lines().joinToString("\n") { it.substringBefore("//") }
        val byPath = Regex("project\\(\\s*(?:path\\s*=\\s*)?\"([^\"]+)\"").findAll(code).map { it.groupValues[1] }
        val byAccessor = Regex("\\bprojects\\.[A-Za-z0-9_.]+").findAll(code).map { it.value }
        return (byPath + byAccessor).toSet()
    }
}

package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.companionnative.QuickMenuItem
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retired paths: two Bottom-screen paths that nothing could reach are removed.
 *
 * - **Retired gauges panel.** The gauges panel goes: the `SECONDARY_GAUGES` menu item and its handler, the
 *   renderer, the panel composable, the service's state feed and the debug intents that opened it. The
 *   Tweaker surface in Utility stays the place for performance controls, and the performance card on
 *   Main keeps its telemetry.
 * - **Removed collection switch.** The collection switch goes end to end: the payload field, its projection, the
 *   command verb, the surface and drawer parameters and the seat glyph that was never drawn. The rename
 *   change of 2026-09-10 is a separate matter and is untouched.
 *
 * Both paths were unreachable before they were deleted, so no behavioural test can notice one coming
 * back. These pins read the declarations instead. Comments are stripped before any check, so prose that
 * still explains the history cannot trip them; string literals are kept, so a returning debug extra is
 * still seen.
 */
class RetiredBottomScreenPathsTest {

    @Test
    fun `the Bottom menu has no gauges item`() {
        assertFalse(
            "O1 removed the item from the menu contract",
            QuickMenuItem.entries.any { it.name == "SECONDARY_GAUGES" },
        )
        listOf(
            "../core-companion-native/src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/CompanionNativeContracts.kt",
            "src/main/kotlin/com/gruning/mimir/rewrite/MainActivity.kt",
            "src/main/kotlin/com/gruning/mimir/rewrite/MButtonRuntime.kt",
        ).forEach { file -> assertAbsent(file, "SECONDARY_GAUGES") }
    }

    @Test
    fun `nothing renders, feeds or opens the gauges panel`() {
        listOf(
            "../feature-companion/src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionInstrumentsPanel.kt",
            "src/main/kotlin/com/gruning/mimir/rewrite/CompanionGaugesState.kt",
            "src/main/kotlin/com/gruning/mimir/rewrite/CompanionGaugeProjection.kt",
        ).forEach { file -> assertFalse("O1 deleted $file", File(file).exists()) }
        assertAbsent(
            "src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt",
            "CompanionInstrumentsSurface",
            "CompanionGaugesState",
            "GAUGES",
        )
        assertAbsent(
            "src/main/kotlin/com/gruning/mimir/rewrite/PerformanceOverlayService.kt",
            "CompanionGaugesState",
            "companionGaugeProjection",
            "panelWanted",
        )
        assertAbsent(
            "src/main/kotlin/com/gruning/mimir/rewrite/MainActivity.kt",
            "CompanionGaugesState",
            "\"mimir.showInstruments\"",
            "\"mimir.gauges\"",
        )
    }

    @Test
    fun `the companion payload carries no collection switch`() {
        assertFalse(
            "O2 removed the field from the payload",
            CompanionPayload::class.java.declaredFields.any { it.name == "collectionSwitch" },
        )
        assertFalse(
            "and the type it carried",
            runCatching { Class.forName("com.gruning.mimir.rewrite.core.model.CompanionCollectionSwitch") }.isSuccess,
        )
        assertAbsent(
            "../core-model/src/main/kotlin/com/gruning/mimir/rewrite/core/model/CompanionPayload.kt",
            "collectionSwitch",
            "CompanionCollectionSwitch",
        )
    }

    @Test
    fun `no host, surface or command offers a collection selection`() {
        mapOf(
            "src/main/kotlin/com/gruning/mimir/rewrite/CompanionCommandState.kt" to
                listOf("SelectHomeCollection", "selectHomeCollection"),
            "src/main/kotlin/com/gruning/mimir/rewrite/CompanionCommandBridge.kt" to
                listOf("SelectHomeCollection", "switchHomeViewMode"),
            "src/main/kotlin/com/gruning/mimir/rewrite/CompanionPayloadAdapter.kt" to
                listOf("collectionSwitch", "CompanionCollectionSwitch"),
            "src/main/kotlin/com/gruning/mimir/rewrite/CompanionPayloadPush.kt" to
                listOf("collectionSwitch", "companionCollectionSwitch"),
            "src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt" to
                listOf("collectionSwitch", "onSelectCollection"),
            "src/main/kotlin/com/gruning/mimir/rewrite/CompanionRecentsOverlayHost.kt" to
                listOf("collectionSwitch", "onSelectCollection"),
            "../feature-companion/src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt" to
                listOf("collectionSwitch", "CompanionCollectionSwitch", "onSelectCollection", "collectionGlyph"),
            "../feature-companion/src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionRecentsDrawer.kt" to
                listOf("collectionSwitch", "CompanionCollectionSwitch", "onSelectCollection"),
        ).forEach { (file, tokens) -> assertAbsent(file, *tokens.toTypedArray()) }
    }

    /**
     * Policy R1 (build A1, 2026-10-02): the Bottom names a platform in full and the lattice tile is laid out
     * from `solveCompanionLatticeCell`. The acronym table (`platformShortLabel`) that shortened "GameCube" to "GCN" is
     * deleted, and so is the label that hung below the tile at a fixed 0.9 of its height, which the ring could cross.
     */
    @Test
    fun `no acronym table and no label hung at a fixed fraction of the tile`() {
        val main = File("src/main/kotlin")
        assertTrue("${main.path} must exist for this pin to mean anything", main.isDirectory)
        val sources = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("the walk must see the app's sources", sources.size > 50)
        sources.forEach { source ->
            assertFalse(
                "platformShortLabel( is retired (R1: full platform names) but still appears in the code of ${source.path}",
                kotlinCodeOnly(source.readText()).contains("platformShortLabel("),
            )
        }
        assertAbsent(
            "../feature-companion/src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt",
            "maxHeight * 0.9f",
        )
    }

    /** The instrument can see what it claims to: literals survive, comments do not, nothing else is lost. */
    @Test
    fun `the comment stripper keeps code and literals and drops only comments`() {
        val code = kotlinCodeOnly(
            """
            // SECONDARY_GAUGES in a line comment
            /** collectionSwitch in KDoc /* nested */ still comment */
            val a = "mimir.gauges" // trailing
            val b = "image/*" ; val c = SECONDARY_GAUGES
            val d = '"' ; val e = "http://x" ; val f = collectionSwitch
            """.trimIndent(),
        )
        assertTrue("a string literal survives", code.contains("val a = \"mimir.gauges\""))
        assertTrue("a /* inside a literal must not open a comment", code.contains("val c = SECONDARY_GAUGES"))
        assertTrue("a quote char literal must not open a string", code.contains("val f = collectionSwitch"))
        assertFalse(code.contains("in a line comment"))
        assertFalse(code.contains("in KDoc"))
        assertFalse(code.contains("still comment"))
        assertFalse(code.contains("trailing"))
    }

    private fun assertAbsent(file: String, vararg tokens: String) {
        val source = File(file)
        // A moved or renamed file would make every absence trivially true, so it has to fail loudly.
        assertTrue("$file must exist for this pin to mean anything", source.isFile)
        val code = kotlinCodeOnly(source.readText())
        tokens.forEach { token ->
            assertFalse("$token is retired but still appears in the code of $file", code.contains(token))
        }
    }

    /** Kotlin source with line and (nested) block comments removed; string and char literals kept. */
    private fun kotlinCodeOnly(source: String): String {
        val out = StringBuilder(source.length)
        val n = source.length
        var i = 0
        while (i < n) {
            val c = source[i]
            when {
                source.startsWith("\"\"\"", i) -> {
                    val close = source.indexOf("\"\"\"", i + 3)
                    val end = if (close < 0) n else close + 3
                    out.append(source, i, end)
                    i = end
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < n && source[j] != c && source[j] != '\n') {
                        if (source[j] == '\\') j++
                        j++
                    }
                    val end = minOf(j + 1, n)
                    out.append(source, i, end)
                    i = end
                }
                source.startsWith("//", i) -> {
                    val newline = source.indexOf('\n', i)
                    i = if (newline < 0) n else newline
                }
                source.startsWith("/*", i) -> {
                    var depth = 0
                    var j = i
                    while (j < n) {
                        when {
                            source.startsWith("/*", j) -> {
                                depth++
                                j += 2
                            }
                            source.startsWith("*/", j) -> {
                                depth--
                                j += 2
                                if (depth == 0) break
                            }
                            else -> j++
                        }
                    }
                    out.append(' ')
                    i = j
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}

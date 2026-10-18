package com.gruning.mimir.rewrite.feature.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings typography audit: `MimirTypeScale` is a deliberate five-step
 * ramp (Display 22 / Heading 18 / Body 14 / Label 11 / Micro 9), `MimirType` is a documented thin ALIAS
 * over it rather than a second dialect, and Settings roles use the same steps — including removing a bespoke 13sp that
 * `MimirType.SettingsActionTitle`'s own KDoc calls *"a nineteenth size punching through the cap"*.
 *
 * One surface still departs: `ThemeGallery.kt`'s preview cards. Four of its six size overrides already sat
 * on a step and are now bound to the token, so they follow the scale instead of coinciding with it. Two are
 * genuinely off-scale and are EXEMPT by those cards size their content to a
 * fixed book aspect and a measured height, so the text is laid out against artwork rather than read as a
 * row. The exemption is recorded in `MimirTypeStep`'s KDoc beside `PerformanceOverlayPresenter`'s.
 *
 * This test exists so that stays true. A new raw size anywhere in Settings fails it.
 */
class SettingsTypeScaleAuditTest {

    /** Display / Heading / Body / Label / Micro, from `MimirTypeStep`. */
    private val scaleSteps = setOf("22", "18", "14", "11", "9")

    /**
     * The two documented exemptions, with the file that carries them: preview-card sizes
     * laid out against artwork rather than read as rows. The reason lives in `MimirTypeStep`'s KDoc, beside
     * `PerformanceOverlayPresenter`'s.
     *
     * Growing this map needs documented rationale there first, not just a passing build. The exemption is
     * scoped to this FILE: the same 10sp or 13sp appearing anywhere else in Settings still fails.
     */
    private val ruledExceptions = mapOf("ThemeGallery.kt" to listOf("10", "13"))

    private fun settingsSources(): List<File> {
        val roots = listOf(
            File("feature-settings/src/main/kotlin"),
            File("src/main/kotlin"),
        )
        val root = roots.firstOrNull(File::isDirectory)
            ?: error("feature-settings sources not found from ${File(".").absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private val literal = Regex("""fontSize\s*=\s*([0-9]+(?:\.[0-9]+)?)\.sp""")

    @Test
    fun `Settings raw font sizes stay on the five-step scale`() {
        val offenders = mutableListOf<String>()
        settingsSources().forEach { file ->
            literal.findAll(file.readText()).forEach { match ->
                val size = match.groupValues[1].removeSuffix(".0")
                val allowed = ruledExceptions[file.name].orEmpty()
                if (size !in scaleSteps && size !in allowed) {
                    offenders += "${file.name}: ${size}sp"
                }
            }
        }
        assertEquals(
            "these Settings font sizes are on no step of MimirTypeStep. " +
                "Use a token, or get an exemption written into MimirTypeStep's KDoc first and then add it " +
                "to ruledExceptions: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the sizes that already matched a step are bound to the token, not merely equal to it`() {
        val gallery = settingsSources().single { it.name == "ThemeGallery.kt" }.readText()

        assertTrue(
            "search window missed the file -- the assertions below would pass vacuously",
            gallery.contains("MimirType.RowSupport") && gallery.length > 2000,
        )
        assertTrue(
            "the on-scale overrides must reference MimirTypeStep so they FOLLOW the scale if it moves",
            gallery.contains("fontSize = MimirTypeStep.Label") &&
                gallery.contains("fontSize = MimirTypeStep.Micro"),
        )

        // Exactly the two documented exceptions remain as raw literals in this file.
        val raw = literal.findAll(gallery)
            .map { it.groupValues[1].removeSuffix(".0") }
            .toList()
            .sorted()
        assertEquals(
            "ThemeGallery should carry only its two documented off-scale sizes as literals",
            listOf("10", "13"),
            raw,
        )
    }

    @Test
    fun `MimirType stays an alias over the one scale rather than a second ramp`() {
        // The audit's other half: two type namespaces are in use across Settings (MimirType.*, 36 call
        // sites; MimirTypeScale.*, 20). That is only safe while the first is an alias of the second. If a
        // role here ever grows its own TextStyle(...), Settings has two ramps and the cap means nothing.
        val roots = listOf(
            File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/theme/MimirType.kt"),
            File("../core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/theme/MimirType.kt"),
        )
        val source = roots.firstOrNull(File::isFile)?.readText()
            ?: error("MimirType.kt not found from ${File(".").absolutePath}")

        assertTrue(
            "MimirType must not declare its own TextStyle -- every role is an alias onto MimirTypeScale",
            !source.contains("= TextStyle("),
        )
        val roles = Regex("""val (\w+): TextStyle = (\w+)""").findAll(source).toList()
        assertTrue("expected the alias roles to be found", roles.size >= 15)
        roles.forEach { role ->
            val target = role.groupValues[2]
            assertTrue(
                "${role.groupValues[1]} must alias MimirTypeScale or another role in this file, not a " +
                    "locally-defined style",
                target == "MimirTypeScale" || source.contains("val $target: TextStyle = MimirTypeScale"),
            )
        }
    }
}

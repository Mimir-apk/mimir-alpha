package com.gruning.mimir.rewrite.feature.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Attribution regression for bundled checksum/title indexes. No-Intro and GameTDB are credited in
 * Settings; MAME and Redump retain their source and permission distinctions in NOTICE. A tooling
 * license must not be presented as a grant over its input data or game content.
 *
 * feature-settings cannot depend on core-metadata, so source names and NOTICE are checked here;
 * adding a bundled origin still requires updating its attribution.
 */
class CreditsTomeTest {

    @Test
    fun `the in-app credits are exactly the two sources whose own terms require it`() {
        val credited = MimirDataSourceCredits.map { it.source }.toSet()
        assertEquals(
            "the in-app credits page should show No-Intro and GameTDB only — see NOTICE section 2 for why",
            setOf("No-Intro", "GameTDB"),
            credited,
        )
    }

    @Test
    fun `MAME and Redump are still acknowledged, in NOTICE now instead of in-app`() {
        val notice = noticeFile().readText()
        assertTrue(
            "NOTICE must still record the Redump sets and their sources, MAMERedump (MIT) and redump.zip (sections 2.2 and 2.3)",
            notice.contains("MAMERedump") && notice.contains("MIT") && notice.contains("redump.zip"),
        )
        assertTrue(
            "NOTICE must record where the MAME indexes come from: the -listxml release archive and the arcade gist (section 2.6)",
            notice.contains("mame0287lx.zip") && notice.contains("-listxml") && notice.contains("a8bit"),
        )
        val redumpData = notice.substringAfter("2.3 Redump sets through auto-datfile-generator", "")
            .substringBefore("2.4 GameTDB")
        assertTrue(
            "Redump data must retain its metadata policy separately from the generator's license",
            redumpData.contains("license covers the generator, not the Redump") &&
                redumpData.contains("https://wiki.redump.info/Redump") &&
                redumpData.contains("permits reuse of the contributed metadata") &&
                redumpData.contains("does not license game content"),
        )
        val mameData = notice.substringAfter("2.6 MAME (4 files)", "")
            .substringBefore("3. ")
        assertTrue(
            "MAME driver/title indexes must retain the missing redistribution-grant qualification",
            mameData.contains("no redistribution grant is on file for these four driver/title indexes"),
        )
    }

    @Test
    fun `every credit states what is bundled, and none is blank`() {
        assertTrue("credits list must not be empty", MimirDataSourceCredits.isNotEmpty())
        MimirDataSourceCredits.forEach { entry ->
            assertTrue("source name blank", entry.source.isNotBlank())
            assertTrue("${entry.source} does not say what it provides", entry.provides.isNotBlank())
        }
    }

    @Test
    fun `the posture line never claims Mimir supplies game content`() {
        val posture = CreditsPostureLine.lowercase()
        assertTrue("posture line must state checksums/names only", posture.contains("checksums"))
        assertTrue(posture.contains("never game content"))
        // The acquisition-posture invariant: this string ships in the UI and must not read as sourcing.
        listOf("download game", "provide games", "obtain roms", "where to get").forEach { forbidden ->
            assertFalse("posture line reads as acquisition: $forbidden", posture.contains(forbidden))
        }
    }

    @Test
    fun `trademarks are acknowledged, never claimed`() {
        assertTrue(CreditsTrademarkLine.contains("respective owners"))
    }

    @Test
    fun `the No-Intro credit does not claim the tooling licence covers the data`() {
        val noIntro = MimirDataSourceCredits.single { it.source == "No-Intro" }
        // NOTICE section 2.1: auto-datfile-generator's MIT covers the GENERATOR, not No-Intro's DAT
        // content. The credit line may name the tooling path but must never read as licensing the data.
        assertFalse(
            "the credit must not state No-Intro data is MIT-licensed",
            noIntro.terms.contains("No-Intro data is MIT") ||
                noIntro.terms.contains("under the MIT licence"),
        )
        assertTrue("the tooling path should still be named", noIntro.terms.contains("MIT"))
    }

    /** Locates the repo-root `NOTICE` file regardless of which module directory Gradle runs tests from. */
    private fun noticeFile(): File =
        generateSequence(File(System.getProperty("user.dir").orEmpty())) { it.parentFile }
            .map { File(it, "NOTICE") }
            .firstOrNull(File::isFile)
            ?: error("Unable to locate repo-root NOTICE file")
}

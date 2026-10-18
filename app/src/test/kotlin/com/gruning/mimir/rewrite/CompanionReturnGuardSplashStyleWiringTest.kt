package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `windowDisablePreview` on `Mimir.ReturnGuard` did not keep the launcher logo out of a reclaim on the Thor
 * (2026-09-11, SDK 33, Mimir is the HOME app). A recording of the Bottom during an explicit reclaim over YouTube
 * counted 2021 gold samples of 13,200 in the centre box at pts 1.094-1.184 s (the logo on a dark ground), then 0
 * until the shelf at 1.362 s, so the guard's launch also requests `SPLASH_SCREEN_STYLE_SOLID_COLOR` on API 33+ and
 * the theme opt-out stays for devices where Mimir is not HOME. Source pins only: whether the logo is gone is owed
 * to a device re-measure.
 */
class CompanionReturnGuardSplashStyleWiringTest {

    private fun moduleText(relative: String): String {
        val file = File(relative)
        assertTrue("$relative must be readable from the app module dir", file.isFile)
        return file.readText()
    }

    /** The text inside the brace block that opens at the first `{` at or after [from], matched by depth. */
    private fun braceBlock(source: String, from: Int): String {
        val open = source.indexOf('{', from)
        assertTrue("expected a block after index $from", open >= 0)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
        }
        throw AssertionError("unbalanced block after index $from")
    }

    /** Whole-line `//` comments are dropped first, so a brace in prose cannot shift the match. */
    private fun launchRestoreGuardBody(): String {
        val source = moduleText("src/main/kotlin/com/gruning/mimir/rewrite/CompanionDisplayController.kt")
            .replace(Regex("""(?m)^\s*//.*$"""), "")
        val declaration = source.indexOf("fun launchRestoreGuard(")
        assertTrue("CompanionDisplayController must still declare launchRestoreGuard", declaration >= 0)
        return braceBlock(source, declaration)
    }

    @Test
    fun `the guard launch requests a solid-colour splash on the options it hands to startActivity`() {
        val body = launchRestoreGuardBody()
        val start = Regex("""startActivity\(\s*intent\s*,\s*(\w+)\.toBundle\(\)\s*\)""").find(body)
        assertNotNull("the guard must still be started with an ActivityOptions bundle", start)
        val options = start!!.groupValues[1]
        assertTrue(
            "the bundle must come from the ActivityOptions this function builds",
            Regex("""\bval\s+$options\s*=\s*ActivityOptions\.makeBasic\(\)""").containsMatchIn(body),
        )
        val gate = Regex(
            """if\s*\(\s*(?:android\.os\.)?Build\.VERSION\.SDK_INT\s*>=\s*(?:android\.os\.)?Build\.VERSION_CODES\.TIRAMISU\s*\)""",
        ).find(body)
        assertNotNull("setSplashScreenStyle is API 33, so the call needs an inline TIRAMISU gate", gate)
        val afterGate = gate!!.range.last + 1
        assertTrue(
            "the TIRAMISU gate must open its own block",
            body.substring(afterGate).trimStart().startsWith("{"),
        )
        assertTrue(
            "inside the gate, $options.setSplashScreenStyle(SPLASH_SCREEN_STYLE_SOLID_COLOR) must be an explicit call",
            Regex(
                """\b$options\.setSplashScreenStyle\(\s*(?:android\.window\.)?SplashScreen\.SPLASH_SCREEN_STYLE_SOLID_COLOR\s*\)""",
            ).containsMatchIn(braceBlock(body, afterGate)),
        )
        assertTrue(
            "the style must be set before toBundle() copies the options into the start",
            gate.range.first < start.range.first,
        )
        assertFalse(
            "explicit setter call, not Kotlin property syntax",
            Regex("""\bsplashScreenStyle\s*=""").containsMatchIn(body),
        )
        assertFalse("never the icon style", "SPLASH_SCREEN_STYLE_ICON" in body)
    }

    @Test
    fun `Mimir ReturnGuard keeps its windowDisablePreview opt-out`() {
        val themes = moduleText("src/main/res/values/themes.xml")
            .replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
        val guard = Regex("""<style\s+name="Mimir\.ReturnGuard"[^>]*>(.*?)</style>""", RegexOption.DOT_MATCHES_ALL)
            .find(themes)
        assertNotNull("values/themes.xml must still declare Mimir.ReturnGuard", guard)
        assertTrue(
            "the theme opt-out still applies where Mimir is not the HOME app; keep it beside the launch option",
            Regex("""<item\s+name="android:windowDisablePreview"\s*>\s*true\s*</item>""")
                .containsMatchIn(guard!!.groupValues[1]),
        )
    }
}

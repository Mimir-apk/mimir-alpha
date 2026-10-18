package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppBrightnessHost's `controlModifier` is `Modifier.mimirFocusable(...)`, and BrightnessOverlay's rail rows and
 * footer action receive it as their `modifier`. A `.clickable` chained onto that modifier plants a second Compose
 * focus node beside the FocusAuthority's (MimirActivatable.kt). ClickableBesideFocusRatchetTest scans one file at a
 * time and cannot see the pair across the two files, so this pins the four receivers directly.
 */
class BrightnessRailActivationPinTest {

    @Test
    fun `the host still hands the rail a mimirFocusable modifier`() {
        val host = source("AppBrightnessHost.kt")
        val start = host.indexOf("controlModifier = { control ->")
        assertTrue("controlModifier must be found", start >= 0)
        assertTrue(host.substring(start, start + 400).contains("Modifier.mimirFocusable("))
    }

    @Test
    fun `the rail rows and footer action activate through mimirActivatable, not clickable`() {
        val overlay = source("BrightnessOverlay.kt")
        for (name in listOf("UtilityChoiceRow", "TweakerProfileRow", "UtilityToggleRow", "UtilityAction")) {
            val chain = receiverChain(overlay, name)
            assertFalse(
                "$name chains clickable onto the host's mimirFocusable modifier: $chain",
                Regex("""\b(clickable|combinedClickable)\b""").containsMatchIn(chain),
            )
            assertTrue("$name must activate through mimirActivatable", chain.contains(".mimirActivatable("))
        }
    }

    /** The code lines of [name]'s `modifier = modifier` chain, up to its closing `.padding(horizontal` or the box's `contentAlignment`. */
    private fun receiverChain(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        require(start >= 0) { "could not find $name" }
        val chainStart = source.indexOf("modifier = modifier", start)
        // A row chain ends at its `.padding(horizontal`; the footer action's (an etched chip in a 48 dp hit box) ends at the box's `contentAlignment`.
        val chainEnd = listOf(".padding(horizontal", "contentAlignment").map { source.indexOf(it, chainStart) }.filter { it > chainStart }.minOrNull() ?: -1
        val next = source.indexOf("\n@Composable", start)
        require(chainStart > start && chainEnd > chainStart && (next < 0 || chainEnd < next)) {
            "could not slice $name's modifier chain"
        }
        return source.substring(chainStart, chainEnd).lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
    }

    private fun source(file: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$file"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$file"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$file not found from ${File(".").absolutePath}")
    }
}

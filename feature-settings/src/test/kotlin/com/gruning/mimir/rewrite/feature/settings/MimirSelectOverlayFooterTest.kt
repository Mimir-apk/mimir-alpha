package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.ui.select.MimirSelectOverlay
import com.gruning.mimir.rewrite.core.ui.select.MimirSelectOptionsTestTag
import com.gruning.mimir.rewrite.core.ui.select.MimirSelectPanelTestTag
import com.gruning.mimir.rewrite.core.ui.select.MimirSelectFooterTestTag

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The select overlay's footer legend (sweep 10-09): "A Select · B Back" was one run of plain text laid out AFTER a list that took every remaining pixel, so at 1.3x on Main
 * (five rows taller than the panel) the footer was cut in half at the panel's bottom edge; and it drew its keys as plain text, where the policy is that every footer keycap
 * is the etched one. What can be read without drawing is pinned here as source; the footer's bounds inside the panel at 1.0x and 1.3x are measured on the rendered sheet in `SweepBCaptureTest`.
 */
class MimirSelectOverlayFooterTest {

    private val source: String by lazy {
        val file = File("../core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/select/MimirSelectOverlay.kt")
        assertTrue("MimirSelectOverlay.kt must be readable from the module dir", file.isFile)
        file.readText()
    }

    private val code: String by lazy {
        source.lineSequence().joinToString("\n") { line ->
            val marker = line.indexOf("//")
            if (marker >= 0) line.substring(0, marker) else line
        }
    }

    @Test
    fun theFooterKeysAreTheEtchedFooterKeycapNotPlainText() {
        val footer = code.substringAfter("private fun MimirSelectFooter(", "").substringBefore("@Composable\nprivate fun SelectRow(", "")
        assertTrue("the footer composable is found", footer.isNotEmpty())
        assertTrue("each key is the footer's one keycap", "MimirFooterKeycap(key = key" in footer)
        assertTrue("the key is projected to the pad's own label, and an unbound one is not drawn", "labelFor(token)" in footer && "isBlank()" in footer)
        assertTrue("the words stay", "\"A\" to \"Select\"" in code && "\"B\" to \"Back\"" in code)
        assertTrue("and the words are the shell legend's hint style", "MimirTypeScale.ControlHint" in footer)
        assertTrue("the old plain-text legend is gone", "A Select · B Back" !in code && "MimirControlHintText" !in code)
    }

    @Test
    fun theListScrollsAboveAFooterThatKeepsItsFullHeight() {
        val list = code.substringBefore(".testTag(MimirSelectOptionsTestTag)", "").takeLast(300)
        assertTrue("the options column is found", list.isNotEmpty())
        assertTrue("it takes what the head, rule and footer leave, and no more than it needs", ".weight(1f, fill = false)" in list)
        assertTrue("the footer is placed after the list, in the panel's own column", code.indexOf("MimirSelectFooter()") > code.indexOf(".testTag(MimirSelectOptionsTestTag)"))
    }

    @Test
    fun thePanelAndFooterCarryTheTagsTheRenderedCheckReads() {
        assertTrue("the panel's body is tagged after the shadow gutter", ".padding(MimirSpacing.xl)\n                    .testTag(MimirSelectPanelTestTag)" in code)
        assertTrue("the footer row is tagged", ".testTag(MimirSelectFooterTestTag)" in code)
    }
}

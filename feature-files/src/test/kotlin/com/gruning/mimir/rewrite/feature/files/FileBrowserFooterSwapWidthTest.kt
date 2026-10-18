package com.gruning.mimir.rewrite.feature.files

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A regression on return to the root: on the way up to the root the footer read "B CLOSI" for a few frames before "CLOSE" fit.
 * The hint swap's layers are composited at their own bounds while they fade; at the words' width a word that grows is
 * cut to the old words' width. The swap spans the band.
 *
 * the whole-line fade read as no transition; the footer now uses [MimirSegmentedHintSwap], left-aligned.
 */
class FileBrowserFooterSwapWidthTest {

    @Test
    fun `the footer's hint swap spans the band, so a longer word is never cut while the words change`() {
        val source = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/files/FileBrowserSurface.kt"),
            File("feature-files/src/main/kotlin/com/gruning/mimir/rewrite/feature/files/FileBrowserSurface.kt"),
        ).first(File::isFile).readText()
        val legend = source.substringAfter("private fun FileBrowserFooterLegend(")
        val swap = legend.substringAfter("MimirSegmentedHintSwap(").substringBefore("separator =")
        assertTrue("the footer uses the per-hint swap", legend.contains("MimirSegmentedHintSwap("))
        assertTrue("the swap spans the band", swap.contains("modifier = Modifier.fillMaxWidth()"))
        assertTrue("the footer stays left-aligned", swap.contains("centred = false"))
    }
}

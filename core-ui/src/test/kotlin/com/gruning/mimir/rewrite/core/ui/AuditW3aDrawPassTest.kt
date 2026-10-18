package com.gruning.mimir.rewrite.core.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Draw-phase source contract: an animated value is read where it is painted, rather than in
 * composition. These checks pin source placement; they do not measure rendered device frames.
 */
class AuditW3aDrawPassTest {

    private fun source(name: String): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name"),
        File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name"),
    ).first(File::isFile).readText()

    @Test
    fun `a dock seat's drawn mark reads its ink in the draw phase, not in composition`() {
        val text = source("MimirRunicQuickRail.kt")
        assertFalse("the animated colour is not handed to a composable parameter", text.contains("ink = runeColor.value"))
        assertFalse(text.contains("cellInk = runeColor.value"))
        assertTrue(text.contains("drawMimirIcon(action.dockMark, runeColor.value, runeColor.value)"))
    }

    @Test
    fun `a ledger cap fades in the layer, and composition sees only whether it is there at all`() {
        val cap = source("MimirAchievementCard.kt").substringAfter("private fun BoxScope.LedgerCap(").substringBefore("/**")
        assertFalse("no composition-phase read of the fade", cap.contains("if (alpha <=") || cap.contains(".alpha(alpha)"))
        assertTrue(cap.contains("val present by remember { derivedStateOf { capAlpha.value > CapInvisibleThreshold } }"))
        assertTrue(cap.contains(".graphicsLayer { alpha = capAlpha.value }"))
    }
}

package com.gruning.mimir.rewrite.feature.browse

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Browse's hidden rows after the ring migration : the one selection ring on the row's card corner,
 * before its clip, no grow; the list reserves the ring's reach at both clipped ends and the reveal covers it; a tap is
 * one activation beside the focus registration. Source pins, the module's idiom elsewhere.
 */
class BrowseRingTest {

    @Test
    fun `hidden rows wear the one ring and reserve its reach`() {
        val code = codeOf("BrowseScreen.kt")
        assertFalse(code.contains(".mimirFocusRing("))
        assertFalse(code.contains("palette.focusRing"))
        val row = code.substringAfter("private fun HiddenRow(")
        assertTrue(row.contains("shape = MimirRingShape.Row(MimirSpacing.radiusCard),"))
        assertTrue(row.contains("identity = identity,"))
        assertTrue(row.indexOf(".mimirSelectionRing(") in 0 until row.indexOf(".clip(RoundedCornerShape(MimirSpacing.radiusCard))"))
        assertFalse("a tap beside a focus registration is one activation", row.contains(".clickable("))
        assertTrue(row.contains(".mimirActivatable("))
        assertTrue(code.contains("top = MimirFocusTileRingReach,"))
        assertTrue(code.contains("Modifier.mimirFocusable(authority, key, BrowseScope, revealOutset = MimirFocusTileRingReach)"))
    }

    private fun codeOf(name: String): String = listOf(
        File("feature-browse/src/main/kotlin/com/gruning/mimir/rewrite/feature/browse/$name"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/browse/$name"),
    ).first(File::isFile).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")
}

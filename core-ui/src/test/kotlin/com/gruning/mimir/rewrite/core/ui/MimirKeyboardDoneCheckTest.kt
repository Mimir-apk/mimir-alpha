package com.gruning.mimir.rewrite.core.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Done key's check is DRAWN (the font's U+2713 fell back to a symbol face and the key read as a root sign). The mark is one stroked
 * vector path; the key is the warm etched primary (the dark plate, an accent rim and accent ink), and the glyph is in no string the keyboard sets.
 */
class MimirKeyboardDoneCheckTest {

    private val source: String = listOf(
        File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirKeyboard.kt"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirKeyboard.kt"),
    ).first(File::isFile).readText()

    private val code = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    @Test
    fun `no string the keyboard draws carries the font's check glyph`() {
        assertFalse("U+2713 is not set as text", code.contains("✓") || code.contains("\\u2713"))
        assertFalse("nor its heavy form", code.contains("✔") || code.contains("\\u2714"))
    }

    @Test
    fun `the Done key draws its check as a stroked vector path in the done ink, in place of its label`() {
        val check = code.substringAfter("private fun KeyboardDoneCheck(").substringBefore("/** One tappable key")
        assertTrue("a path", check.contains("Path().apply") && check.contains("moveTo(") && check.contains("lineTo("))
        assertTrue("stroked with round caps and joins", check.contains("drawPath(") && check.contains("StrokeCap.Round") && check.contains("StrokeJoin.Round"))
        assertTrue("tagged for the tests that ask where it stands", check.contains("testTag(MimirKeyboardDoneCheckTag)"))
        // The key draws it for the Done action only, in the primary chip's ink (accent), on the warm etched plate (no gold fill).
        assertTrue(code.contains("checkMark = key.action == KeyboardAction.Done,"))
        assertTrue(code.contains("color = if (accent) palette.etchedChipInk(primary = true) else keyInk,"))
        assertTrue(code.contains("accent = key.action == KeyboardAction.Done,"))
    }

    @Test
    fun `the check's points make a check inside its seat`() {
        val points = KeyboardDoneCheckPoints
        assertEquals(3, points.size)
        points.forEach { (x, y) ->
            assertTrue("inside the seat: $x,$y", x in 0f..1f && y in 0f..1f)
        }
        val (x0, y0) = points[0]
        val (x1, y1) = points[1]
        val (x2, y2) = points[2]
        assertTrue("the short stroke goes down and right", x1 > x0 && y1 > y0)
        assertTrue("the long stroke goes up and right, higher than it began", x2 > x1 && y2 < y1 && y2 < y0)
        assertTrue("the long stroke is longer than the short one", (x2 - x1) > (x1 - x0))
    }
}

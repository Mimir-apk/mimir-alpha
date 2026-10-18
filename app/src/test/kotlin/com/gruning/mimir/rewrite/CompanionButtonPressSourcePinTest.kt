package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirPressScale
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Thor Bottom revamp B5 (policy R4): the native ᛗ coin presses on the Bottom's one press response, and `core-companion-native` has no core-ui dependency, so its
 * depth and its beat are local constants. This reads that module's source and holds them equal to the tokens they mirror, so a change to a token is a red test here
 * rather than a coin that quietly keeps an old press:
 *
 *  - the coin draws in to `MimirPressScale`;
 *  - both ways take `MimirMotion.FocusMs`;
 *  - the coin still keeps its pressed alpha, and only the Bottom's coin takes any of this: Main's keeps 0.92 over 90 ms down and 1 over 120 ms up, as before B5.
 */
class CompanionButtonPressSourcePinTest {

    private val source: String = listOf(
        File("../core-companion-native/src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/AndroidCompanionOverlayController.kt"),
        File("core-companion-native/src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/AndroidCompanionOverlayController.kt"),
    ).firstOrNull(File::isFile)?.readText() ?: error("the native overlay controller's source must be readable from the module dir")

    private fun declared(type: String, name: String): String {
        val match = Regex("""const val $name\s*=\s*([0-9.]+)$type""").find(source)
        return checkNotNull(match) { "`$name` is not declared in the native controller" }.groupValues[1]
    }

    /** The body of `handleButtonTouch`, which is where the coin's press lives. */
    private val touch: String = run {
        val start = source.indexOf("private fun handleButtonTouch(")
        check(start >= 0) { "handleButtonTouch moved: move this pin with it" }
        val end = source.indexOf("private fun handleMenuDrag(", start)
        source.substring(start, end)
    }

    @Test
    fun `the coin draws in to the press scale token`() {
        assertEquals(MimirPressScale, declared("f", "CompanionButtonPressScale").toFloat(), 0f)
    }

    @Test
    fun `the coin presses and releases on the focus beat`() {
        assertEquals(MimirMotion.FocusMs.toLong(), declared("L", "CompanionButtonPressMs").toLong())
    }


    @Test
    fun `down, up and cancel choose their press by the coin's role, and no literal press is left in the touch handler`() {
        // Down: the scale and the beat of the role; up and cancel: back to whole on the role's release beat.
        assertTrue(touch.contains(".scaleX(companionButtonPressScale(displayRole)).scaleY(companionButtonPressScale(displayRole))"))
        assertEquals("down sets the role's press beat", 1, Regex("""setDuration\(companionButtonPressMs\(displayRole\)\)""").findAll(touch).count())
        assertEquals("up and cancel set the role's release beat", 2, Regex("""setDuration\(companionButtonReleaseMs\(displayRole\)\)""").findAll(touch).count())
        assertEquals("up and cancel return to whole", 2, Regex("""scaleX\(1f\)\.scaleY\(1f\)""").findAll(touch).count())
        assertFalse("no literal press scale is left", touch.contains("scaleX(0.9"))
        assertFalse("no literal press beat is left", Regex("""setDuration\(\d+\)""").containsMatchIn(touch))
        assertTrue("the coin keeps its pressed alpha", touch.contains("alpha(pressedAlpha())"))
    }

    /** The two branches of a one-line `internal fun companionButton...(role)`: what it picks for the Bottom, and what for anything else. */
    private fun branches(name: String): Pair<String, String> {
        val pattern = """internal fun $name\(role: CompanionOverlayRole\): (?:Float|Long) =\s*if \(role == CompanionOverlayRole\.SECONDARY_COMPANION\) (\w+) else (\w+)"""
        val match = checkNotNull(Regex(pattern).find(source)) { "`$name` is not the two-branch role function this pin reads" }
        return match.groupValues[1] to match.groupValues[2]
    }

    @Test
    fun `the Bottom's coin takes the press response and Main's coin keeps its old press exactly`() {
        // Each role's function picks the Bottom's constant on SECONDARY_COMPANION and Main's on anything else.
        assertEquals("CompanionButtonPressScale" to "CompanionMainButtonPressScale", branches("companionButtonPressScale"))
        assertEquals("CompanionButtonPressMs" to "CompanionMainButtonPressMs", branches("companionButtonPressMs"))
        assertEquals("CompanionButtonPressMs" to "CompanionMainButtonReleaseMs", branches("companionButtonReleaseMs"))
        // Main's three literals are what the coin had before B5: 0.92 over 90 ms down, 1 over 120 ms up.
        assertEquals(0.92f, declared("f", "CompanionMainButtonPressScale").toFloat(), 0f)
        assertEquals(90L, declared("L", "CompanionMainButtonPressMs").toLong())
        assertEquals(120L, declared("L", "CompanionMainButtonReleaseMs").toLong())
    }
}

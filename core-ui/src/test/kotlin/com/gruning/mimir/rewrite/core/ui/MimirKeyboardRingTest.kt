package com.gruning.mimir.rewrite.core.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyboard's keys after the ring migration : the dense key ring, after the press layer so it
 * moves with the cap and before the clip so it can reach past it; the old in-bounds ring is gone.
 */
class MimirKeyboardRingTest {

    @Test
    fun `a key rings densely, after its press layer and before its clip`() {
        val code = listOf(
            File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirKeyboard.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirKeyboard.kt"),
        ).first(File::isFile).readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
        assertFalse(code.contains(".mimirFocusRing("))
        val ring = code.indexOf("shape = MimirRingShape.Key(KeyboardKeyRadius)")
        assertTrue(ring > 0)
        val press = code.lastIndexOf("translationY = if (pressed) PressedKeyLift.toPx() else 0f", ring)
        val clip = code.indexOf(".clip(shape)", ring)
        assertTrue("the ring sits after the press layer", press in 1 until ring)
        assertTrue("and before the clip", clip > ring)
        assertTrue(code.contains("identity = keyId ?: label"))
    }
}

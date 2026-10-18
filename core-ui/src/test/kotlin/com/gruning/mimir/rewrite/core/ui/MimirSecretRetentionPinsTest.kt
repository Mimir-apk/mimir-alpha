package com.gruning.mimir.rewrite.core.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D33 fix round, items 1 and 4: nothing keyboard-owned may hold a String derived from a secret.
 *
 * A closure cannot be walked from a JVM test, so this is a source pin plus the behavioural checks in
 * [MimirSecretLiveEditTest] (the owner's own state is read at call time, so a stale capture would show there):
 *  - the public keyboards have no `secret` mode at all, so a secret can never reach `editingValue` or a closure that
 *    captures the owner's snapshot;
 *  - the secret keyboard composables take no String parameter and no `String?` reveal;
 *  - the Show/Hide text is a call-time provider, not a value;
 *  - the initial landing focus consults a COUNT, never the rendered dots.
 */
class MimirSecretRetentionPinsTest {

    private val dir = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui"),
        File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui"),
    ).first(File::isDirectory)

    private fun code(name: String) = File(dir, name).readText()
        .replace(Regex("/[*].*?[*]/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//.*"), "")

    @Test
    fun thePublicKeyboardsHaveNoSecretMode() {
        val keyboard = code("MimirKeyboard.kt")
        val publicPart = keyboard.substringBefore("internal fun mimirKeyboardStartsShifted")
        assertFalse("a public overload still takes `secret`", publicPart.contains("secret: Boolean"))
        assertFalse(publicPart.contains("MimirOwnedSecretKeyboard"))
    }

    @Test
    fun theSecretKeyboardsTakeNoStringAndTheKeyboardNeverReceivesTheSecretNotEvenToShowIt() {
        val secret = code("MimirSecretKeyboard.kt")
        val composables = secret.substringAfter("@Composable")
        assertFalse("no String parameter on a secret keyboard", Regex("\\bvalue: String\\b").containsMatchIn(composables))
        val keyboard = code("MimirKeyboard.kt")
        assertFalse("no text provider reaches the keyboard", keyboard.contains("secretRevealText"))
        assertFalse("the keyboard never asks the owner for its text", keyboard.contains("revealedText"))
        val wrapper = secret.substringAfter("fun MimirSecretFieldKeyboard(")
        val surfaceCall = wrapper.substringAfter("MimirKeyboardSurface(").substringBefore("\n    )")
        assertFalse("the surface is handed no reveal text", surfaceCall.contains("revealedText"))
        assertTrue("the Show key flips an owner flag", keyboard.contains("secretRevealToggle"))
        assertTrue("the owner draws its own reveal outside the keyboard", wrapper.contains("MimirSecretRevealPlate("))
    }

    @Test
    fun theRevealPlateDrawsTheSecretInTheDrawPhaseOnlyWithNoCachedLayoutAndNoPlaintextSemantics() {
        val secret = code("MimirSecretKeyboard.kt")
        val plate = secret.substringAfter("private fun MimirSecretRevealPlate(").substringBefore("\n}\n")
        assertTrue("the plate was found", plate.contains("drawBehind"))
        assertFalse(
            "the plate is no Text node: a Text would hold the secret in its node, its layout and the semantics tree",
            Regex("(?<![A-Za-z])(BasicText|Text)\\(").containsMatchIn(plate),
        )
        assertTrue("no layout is cached", plate.contains("cacheSize = 0"))
        assertTrue("the secret is painted with drawText", plate.contains("drawText("))
        assertEquals("the secret is read exactly once", 1, Regex("revealedText\\(\\)").findAll(plate).count())
        assertTrue(
            "and that read is inside the draw lambda",
            plate.indexOf("revealedText()") > plate.indexOf("drawBehind"),
        )
        assertTrue("accessibility never receives the secret", plate.contains("clearAndSetSemantics"))
        assertTrue(plate.contains("Password shown"))
        assertFalse(
            "nothing remembers the text",
            Regex("remember(Saveable)?\\s*\\{[^}]*revealedText").containsMatchIn(plate),
        )
    }

    @Test
    fun theLandingFocusReadsTheCountNotTheDots() {
        val keyboard = code("MimirKeyboard.kt")
        val surface = keyboard.substringAfter("internal fun MimirKeyboardSurface(")
        assertTrue(surface.contains("secretCount"))
        assertFalse(
            "the landing must not be computed from the rendered text for a secret",
            Regex("val landing = remember \\{ mimirKeyboardLandingKeyId\\(value.text\\) \\}").containsMatchIn(surface),
        )
    }
}

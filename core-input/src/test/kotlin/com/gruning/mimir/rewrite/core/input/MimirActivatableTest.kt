package com.gruning.mimir.rewrite.core.input

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract pins for [Modifier.mimirActivatable] — the activation primitive that exists so a
 * controller-navigated element never has to reach for `Modifier.clickable`.
 *
 * These are source pins, not behaviour tests: `core-input` has no Compose test harness (JUnit only, see
 * `build.gradle.kts`), so what a tap actually does on a real key is a **device fact** and is not proven
 * here. What is proven here is that the primitive keeps the three properties it was created to carry —
 * including the accessibility half that a hurried migration drops and no lint rule would catch.
 */
class MimirActivatableTest {

    @Test
    fun `the primitive plants no focus node`() {
        val body = code()
        listOf(".clickable(", "combinedClickable", ".focusable(", "focusTarget(").forEach { forbidden ->
            assertFalse(
                "mimirActivatable must not use `$forbidden` -- it exists precisely so a " +
                    "controller-navigated element does not carry a second Compose focus target",
                body.contains(forbidden),
            )
        }
    }

    /**
     * `clickable` published a click ACTION to accessibility services, and
     * `AbstractClickableNode.getShouldMergeDescendantSemantics` is a hardcoded `return true` in the
     * shipped `foundation-android:1.7.3`, so it merged the element's label into one node. A raw pointer
     * gesture publishes neither. Dropping this leaves the action on a text-less parent with the label as
     * a separate child, and TalkBack lands on a label with no action.
     */
    @Test
    fun `the primitive republishes what clickable used to publish to accessibility`() {
        val body = code()
        assertTrue(
            "mimirActivatable must merge descendant semantics, or the click action separates from its label",
            body.contains("semantics(mergeDescendants = true)"),
        )
        assertTrue(
            "mimirActivatable must publish an onClick action for accessibility services",
            body.contains("onClick(label = label)"),
        )
    }

    @Test
    fun `the primitive offers no double-tap`() {
        assertFalse(
            "onDoubleTap puts the platform double-tap timeout (~300ms) in front of EVERY single tap -- " +
                "the exact defect the Files surface removed its double-tap gesture to fix",
            code().contains("onDoubleTap"),
        )
    }

    /**
     * `pointerInput`'s keys are a RESTART contract: a changed key disposes the detector and cancels
     * whatever gesture is in flight, so keying on the caller's lambda would eat a tap whose finger was
     * already down when a recomposition changed it.
     */
    @Test
    fun `the gesture node is keyed on Unit and reads its targets through rememberUpdatedState`() {
        val body = code()
        assertTrue(
            "the gesture node must be keyed on Unit, never on the caller's lambda",
            body.contains("pointerInput(Unit)"),
        )
        assertTrue(
            "targets must be read through rememberUpdatedState so the node survives a changed lambda",
            body.contains("rememberUpdatedState(onActivate)"),
        )
    }

    /**
     * The focus write is opt-in, and when it happens it must happen BEFORE the action. Wherever the
     * action resolves its target from the currently-focused key rather than from the tapped element
     * (Home binds `onPlay` to a CONFIRM dispatch that does exactly this), publishing focus first is what
     * makes the tapped element — not whatever the controller last left focused — the thing that acts.
     */
    @Test
    fun `focus is published before the action, never after`() {
        val activate = code().sliceBetween(after = "val activate: () -> Unit = {", before = "return this")
        val focusWrite = activate.indexOf("requestFocus(")
        val action = activate.indexOf("currentActivate()")
        assertTrue("expected a requestFocus call in the activation path", focusWrite >= 0)
        assertTrue("expected the action call in the activation path", action >= 0)
        assertTrue(
            "requestFocus must precede the action -- flipping these makes the CONFIRM dispatch resolve " +
                "against whatever the controller last focused instead of the element the player tapped",
            focusWrite < action,
        )
    }

    // ── Source-pin plumbing ──────────────────────────────────────────────────────────────────────

    /** Presence-checked slice: a missing anchor fails loudly instead of degrading to the whole file. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        val tail = substringAfter(after)
        assertTrue("closing anchor missing after '$after': $before", tail.contains(before))
        return tail.substringBefore(before)
    }

    /**
     * Comments stripped. This primitive's KDoc names every forbidden idiom while explaining why it is
     * forbidden; without stripping, the pins above would read the explanation as the code.
     */
    private fun code(): String = stripKotlinComments(source())

    private fun source(): String {
        val relative = "src/main/kotlin/com/gruning/mimir/rewrite/core/input/MimirActivatable.kt"
        val candidates = listOf(File("core-input/$relative"), File(relative), File("../core-input/$relative"))
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("MimirActivatable.kt not found from ${File(".").absolutePath}")
    }

    companion object {
        /**
         * Strips `//` line comments and block comments while preserving string literals and every
         * newline. Deliberately small: not a Kotlin lexer, it only has to survive this file's comments.
         */
        internal fun stripKotlinComments(text: String): String {
            val out = StringBuilder(text.length)
            var index = 0
            var inString = false
            var inChar = false
            while (index < text.length) {
                val c = text[index]
                val next = text.getOrNull(index + 1)
                when {
                    inString -> {
                        out.append(c)
                        if (c == '\\') {
                            text.getOrNull(index + 1)?.let(out::append)
                            index++
                        } else if (c == '"') {
                            inString = false
                        }
                        index++
                    }
                    inChar -> {
                        out.append(c)
                        if (c == '\\') {
                            text.getOrNull(index + 1)?.let(out::append)
                            index++
                        } else if (c == '\'') {
                            inChar = false
                        }
                        index++
                    }
                    c == '"' -> { inString = true; out.append(c); index++ }
                    c == '\'' -> { inChar = true; out.append(c); index++ }
                    c == '/' && next == '/' -> {
                        while (index < text.length && text[index] != '\n') index++
                    }
                    c == '/' && next == '*' -> {
                        index += 2
                        while (index < text.length && !(text[index] == '*' && text.getOrNull(index + 1) == '/')) {
                            if (text[index] == '\n') out.append('\n')
                            index++
                        }
                        index += 2
                    }
                    else -> { out.append(c); index++ }
                }
            }
            return out.toString()
        }
    }
}

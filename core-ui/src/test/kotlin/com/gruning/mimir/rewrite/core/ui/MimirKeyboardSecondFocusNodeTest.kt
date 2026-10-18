package com.gruning.mimir.rewrite.core.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `MimirKeyboard`'s keys must not carry a second Compose focus target.
 *
 * ## The defect this pins closed
 *
 * `Modifier.clickable` builds an `AbstractClickableNode`, which constructs a `FocusableNode` and
 * therefore a real `FocusTargetModifierNode`. `MimirKeyboard.kt` applies `decoration?.focusModifier` —
 * which is `Modifier.mimirFocusable`, handed in from the app layer by
 * `MimirKeyboardFocusBinding.decorateKey` (`core-input`) — to the **same `Box`** that receives
 * `inputModifier`. So while `inputModifier`'s `else` branch was a `clickable`, every ordinary letter key
 * carried both focus systems at once, on every host that supplies a key decorator (`MimirAppHost`,
 * `OnboardingFlow`, `HomeCustomEnrichPicker`, `HomeTomeLibraryOverlay`, `ArtworkConnectOverlay`).
 *
 * The `when` branches on key BEHAVIOUR (`repeatOnHold`, `horizontalDragHandler`), never on whether a
 * controller is attached, so this was not reachable-only-in-theory: it was the default path for letters.
 *
 * ## Why the pin lives here rather than in the gate
 *
 * `tools/mimir_ui_invariants_gate.py`'s F1 rules grep for four literals — two `focusable` forms and two
 * `focusTarget` forms — and match **neither** `clickable` nor `combinedClickable`. The hazard is
 * structurally invisible to the gate, so it is pinned at the source instead.
 *
 * ## Scope — deliberately narrow
 *
 * This pins the KEY input path only. The panel `Column`'s no-op `clickable` touch-swallower is out of
 * scope and stays: it is a touch-only element that carries no `mimirFocusable`, and the invariant is that the invariant is one authority for *controller navigation*, not the absence
 * of any `FocusableNode` anywhere. Same-element is the defect; a touch-only element is not.
 */
class MimirKeyboardSecondFocusNodeTest {

    @Test
    fun `the key input path never uses clickable`() {
        val inputModifier = keyInputModifier()
        assertFalse(
            "MimirKeyboard's key inputModifier must not use clickable -- it builds an " +
                "AbstractClickableNode, whose FocusableNode is a second focus target beside the " +
                "mimirFocusable that decorateKey applies to the same Box",
            inputModifier.contains("clickable"),
        )
    }

    @Test
    fun `the key input path never registers a double-tap`() {
        assertFalse(
            "onDoubleTap puts the platform double-tap timeout (~300ms) in front of EVERY single tap",
            keyInputModifier().contains("onDoubleTap"),
        )
    }

    /**
     * The half a migration drops. `clickable` published a click ACTION to accessibility services, and
     * `AbstractClickableNode.getShouldMergeDescendantSemantics` is a hardcoded `return true` in the
     * shipped `foundation-android:1.7.3`, so it merged the key's label into one node too. A raw pointer
     * gesture publishes neither: without this block TalkBack lands on a label with no action, and no F1
     * rule would ever catch it.
     */
    @Test
    fun `every key input branch still publishes a merged click action`() {
        val branches = keyInputModifier().split("-> Modifier").drop(1)
        assertTrue("expected three inputModifier branches, found ${branches.size}", branches.size == 3)
        branches.forEachIndexed { index, branch ->
            assertTrue(
                "inputModifier branch $index must keep semantics(mergeDescendants = true) -- " +
                    "without it the click action lands on a text-less parent",
                branch.contains("semantics(mergeDescendants = true)"),
            )
            assertTrue(
                "inputModifier branch $index must keep an onClick action for accessibility services",
                branch.contains("onClick("),
            )
        }
    }

    /**
     * Guards this test against going vacuous. Every assertion above is about `inputModifier` being
     * applied to the element that also receives the authority's focus modifier; if a refactor ever
     * separated the two, the pins above would still pass while meaning something else entirely.
     */
    @Test
    fun `the focus modifier and the input modifier still land on the same element`() {
        val box = code().sliceBetween(after = "val decoration = if (keyDecorator", before = "contentAlignment")
        assertTrue(
            "expected decoration?.focusModifier and inputModifier on one modifier chain -- if this " +
                "moved, re-read this whole test: its premise changed",
            box.contains("decoration?.focusModifier") && box.contains("inputModifier"),
        )
    }

    // ── Source-pin plumbing ──────────────────────────────────────────────────────────────────────

    private fun keyInputModifier(): String =
        code().sliceBetween(after = "val inputModifier = when {", before = "val decoration = if (keyDecorator")

    /** Presence-checked slice: a missing anchor fails loudly instead of degrading to the whole file. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        val tail = substringAfter(after)
        assertTrue("closing anchor missing after '$after': $before", tail.contains(before))
        return tail.substringBefore(before)
    }

    /**
     * Comments stripped. This file explains the rejected idiom in prose and names `clickable` a dozen
     * times while doing it; without stripping, every assertion here would read the explanation as code
     * and fail forever.
     */
    private fun code(): String = stripKotlinComments(source())

    private fun source(): String {
        val relative = "src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirKeyboard.kt"
        val candidates = listOf(File("core-ui/$relative"), File(relative), File("../core-ui/$relative"))
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("MimirKeyboard.kt not found from ${File(".").absolutePath}")
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

package com.gruning.mimir.rewrite.feature.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings rail's three activatable components — `SettingsRow`, `GradientCta`, `TonalCta` — activate
 * through `Modifier.mimirActivatable`, never `Modifier.clickable`.
 *
 * ## Why this file, of all files
 *
 * `core-input/MimirFocusable.kt` used to end with *"Consumers currently wire tap via their own
 * Modifier.clickable (see the Settings rail)"* — so this rail was the tree's documented example of the
 * pattern, and all three components put `.clickable` on the very same element as their
 * `.mimirFocusable` registration. `clickable` builds an `AbstractClickableNode`, which constructs a
 * `FocusableNode` and therefore a real focus target: a second one, beside the single authority.
 * `SettingsRow`'s own KDoc claimed *"never a second focus engine"* while doing it.
 *
 * `tools/mimir_ui_invariants_gate.py`'s F1 rules grep for `.focusable(` / `focusTarget(` and match
 * neither `clickable` nor `combinedClickable`, so this was invisible to the gate. Hence a source pin.
 *
 * **Not proven here:** that a tap works. `feature-settings` has no Compose unit harness, so what a finger
 * does to a CTA — and where TalkBack lands on it — remain device facts.
 */
class SettingsCtaActivationTest {

    private val activatable = listOf("SettingsRow", "GradientCta", "TonalCta")

    @Test
    fun `no settings component activates through clickable`() {
        val body = code()
        listOf(".clickable(", ".clickable {", "combinedClickable").forEach { forbidden ->
            assertFalse(
                "SettingsComponents.kt must not use `$forbidden` -- every activatable component here " +
                    "also calls mimirFocusable on the same element, so this plants a second focus target. " +
                    "Note the brace form is listed explicitly: a `.clickable {` call is invisible to a " +
                    "pattern that only looks for `.clickable(`, which is how this file's SettingsRow was " +
                    "missed by the first census.",
                body.contains(forbidden),
            )
        }
    }

    @Test
    fun `every activatable settings component pairs mimirFocusable with mimirActivatable`() {
        val body = code()
        activatable.forEach { name ->
            val block = body.sliceBetween(after = "fun $name(", before = "\n}")
            assertTrue(
                "$name must register with the single authority via mimirFocusable",
                block.contains(".mimirFocusable("),
            )
            assertTrue(
                "$name must activate via mimirActivatable -- the primitive carries the merged click " +
                    "action that clickable used to publish to accessibility services",
                block.contains(".mimirActivatable("),
            )
        }
    }

    /**
     * The focus write is what the old `clickable` lambdas did first (`authority.requestFocus(key)` then
     * `onActivate()`), and it has to survive the migration: a CTA's action can resolve against the
     * focused key rather than the tapped element, so the tapped button must become the focused one
     * before it runs. `mimirActivatable` does this only when handed both `authority` and `key`.
     */
    @Test
    fun `each component still publishes focus on tap by passing authority and key`() {
        val body = code()
        activatable.forEach { name ->
            val block = body.sliceBetween(after = "fun $name(", before = "\n}")
            val call = block.substringAfter(".mimirActivatable(").substringBefore(")")
            assertTrue(
                "$name's mimirActivatable call must pass `authority` -- without it the tap activates " +
                    "without publishing focus, silently dropping what the old clickable did first",
                call.contains("authority = authority"),
            )
            assertTrue(
                "$name's mimirActivatable call must pass `key`",
                call.contains("key = key"),
            )
        }
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
     * Comments stripped. The migrated call sites explain the rejected idiom in prose and name `clickable`
     * repeatedly; without stripping, the first assertion would read those explanations as code.
     */
    private fun code(): String = stripKotlinComments(source())

    private fun source(): String {
        val relative = "src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/SettingsComponents.kt"
        val candidates = listOf(File("feature-settings/$relative"), File(relative), File("../feature-settings/$relative"))
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("SettingsComponents.kt not found from ${File(".").absolutePath}")
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

package com.gruning.mimir.rewrite.core.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins two things about `MimirHintToastRail`'s own appear/disappear, which used to be a one-frame hard cut
 * even though message-to-message changes already crossfaded on `MimirMotion.OverlayMs`/`Easing`:
 *
 * 1. The pure retention policy ([retainedHintToastMessageForFrame]) and the fade spec
 *    ([mimirHintToastRailFadeSpec]) — both directly callable with no Compose harness, same as
 *    `StorageNoticeHost.retainedStorageNoticeForFrame` / `storageNoticeFadeSpec`.
 * 2. That the composable actually WIRES those two into `AnimatedVisibility`, source-pinned the way
 *    `MimirGlassTransitionMountTest` pins `MimirGlassTransition.kt` — this module has no Compose test
 *    harness, so a passing pure-function test alone cannot prove the composable reads its result rather
 *    than the raw `activeToast ?: guidance` union straight into the old `if (x != null)` hard cut.
 */
class MimirHintToastRailLifetimeTest {

    private fun source(): String {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirHintToastRail.kt")
        assertTrue(
            "MimirHintToastRail.kt not found at ${file.absolutePath} — if this file moved, move this pin with it.",
            file.exists(),
        )
        // Strip comments before asserting: several claims below are about CODE, and this KDoc itself
        // mentions the old `if (displayMessage != null)` gate to explain what changed.
        return file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
    }

    @Test
    fun `pure retention logic keeps the outgoing message through exit and clears only when settled`() {
        val toast = MimirMessage(1L, "Saved", MimirMessageClass.Confirmation)
        val active = retainedHintToastMessageForFrame(current = toast, retained = null, exitSettled = false)
        val exiting = retainedHintToastMessageForFrame(current = null, retained = active, exitSettled = false)
        val settled = retainedHintToastMessageForFrame(current = null, retained = exiting, exitSettled = true)

        assertEquals(toast, active)
        assertEquals(toast, exiting)
        assertNull(settled)
    }

    @Test
    fun `a replacement message wins immediately even at the exit boundary`() {
        val outgoing = MimirMessage(1L, "Saved", MimirMessageClass.Confirmation)
        val incoming = MimirMessage(2L, "Could not save", MimirMessageClass.Warning)

        assertEquals(
            incoming,
            retainedHintToastMessageForFrame(current = incoming, retained = outgoing, exitSettled = true),
        )
    }

    @Test
    fun `the rail's own fade uses the house duration and easing, never Compose's default`() {
        val active = mimirHintToastRailFadeSpec(reducedMotion = false)
        assertEquals(MimirMotion.OverlayMs, active.durationMillis)
        assertEquals(MimirMotion.Easing, active.easing)

        val reduced = mimirHintToastRailFadeSpec(reducedMotion = true)
        assertEquals(0, reduced.durationMillis)
        assertEquals(MimirMotion.Easing, reduced.easing)
    }

    @Test
    fun `the composable is wired to AnimatedVisibility and the retained value, not the old hard cut`() {
        val code = source()
        assertTrue(
            "MimirHintToastRail must gate its content on AnimatedVisibility(visibleState = visibility, ...) " +
                "so its own first appearance and final disappearance fade rather than cut.",
            code.contains("AnimatedVisibility(") && code.contains("visibleState = visibility"),
        )
        assertTrue(
            "The rail's own enter/exit must use mimirHintToastRailFadeSpec (house duration + easing), " +
                "not a raw tween() that would default to Compose's easing.",
            code.contains("enter = fadeIn(mimirHintToastRailFadeSpec(reducedMotion))") &&
                code.contains("exit = fadeOut(mimirHintToastRailFadeSpec(reducedMotion))"),
        )
        assertTrue(
            "The content inside AnimatedVisibility must read the retained value (shownMessage), not the " +
                "raw activeToast/guidance union — otherwise the retained value is computed but never shown.",
            code.contains("val activeMessage = shownMessage ?: return@AnimatedVisibility"),
        )
        assertFalse(
            "The old unconditional `if (displayMessage != null)` hard-cut gate must be gone — that gate, " +
                "not the inner Crossfade, was the one-frame cut this fix removes.",
            code.contains("if (displayMessage != null)"),
        )
    }
}

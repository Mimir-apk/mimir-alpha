package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.tweaker.TweakerLastRestore
import com.gruning.mimir.rewrite.core.ui.MimirCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 *
 *
 * The daemon computed the reason and shipped it, `MimirCopy` carried the sentences, the wire
 * contract declared both keys consumed -- but the surface did not read either key. These tests pin the surface consumers.
 */
class TweakerRestoreLegibilityTest {

    @Test
    fun `a telemetry-transport restore is announced once, not on every panel tick`() {
        val drop = TweakerLastRestore(reason = "MISSING_TELEMETRY_CPU", isTelemetryTransport = true)
        assertTrue(
            "the first sighting must be announced",
            shouldSurfaceTweakerRestore(drop, lastSurfacedKey = null),
        )
        assertFalse(
            "the projection refresh runs constantly -- the same incident must not re-announce",
            shouldSurfaceTweakerRestore(drop, lastSurfacedKey = drop.incidentKey),
        )
        assertTrue(
            "but a DIFFERENT telemetry-transport reason is a different incident",
            shouldSurfaceTweakerRestore(
                TweakerLastRestore(reason = "MISSING_TELEMETRY_GPU", isTelemetryTransport = true),
                lastSurfacedKey = drop.incidentKey,
            ),
        )
    }

    @Test
    fun `restores the player cannot act on stay silent`() {
        // A message with no action is noise, and noise teaches people to ignore the channel. Only
        // the telemetry-transport class has a recovery the player can actually perform.
        listOf("LEASE_EXPIRED", "OWNER_TOKEN_DIED", "TARGET_LOSS", "ACTIVATION_TIMEOUT").forEach { reason ->
            assertFalse(
                "reason=$reason is not a telemetry-transport loss and must not be announced",
                shouldSurfaceTweakerRestore(
                    TweakerLastRestore(reason = reason, isTelemetryTransport = false),
                    lastSurfacedKey = null,
                ),
            )
        }
        assertFalse(
            "and no recorded restore at all must say nothing",
            shouldSurfaceTweakerRestore(null, lastSurfacedKey = null),
        )
    }

    /**
     * Fan-fence fix F4 (2026-10-14): the Tweaker's guardian handed the Odin back to Stock
     * three times and Mimir said nothing while the device sat at 103 C. A guardian hand-back is
     * the second class with an action the player can take (re-apply), so it is announced -- once
     * per distinct reason, like the telemetry class -- through the same message surface.
     */
    @Test
    fun `a guardian hand-back is announced once, with its own copy, and ordinary restores stay silent`() {
        val handback = TweakerLastRestore(
            reason = "GUARDIAN_RESTORE_REQUESTED/HEALTHY_STALE",
            isTelemetryTransport = false,
            isGuardianHandback = true,
        )
        assertTrue(shouldSurfaceTweakerRestore(handback, lastSurfacedKey = null))
        assertFalse(shouldSurfaceTweakerRestore(handback, lastSurfacedKey = handback.incidentKey))
        assertTrue(
            shouldSurfaceTweakerRestore(
                TweakerLastRestore("GUARDIAN_TERMINAL_RECEIVED", isTelemetryTransport = false, isGuardianHandback = true),
                lastSurfacedKey = handback.incidentKey,
            ),
        )
        assertFalse(
            "a restore the daemon did not classify as either is still not announced",
            shouldSurfaceTweakerRestore(
                TweakerLastRestore("STARTUP_RECOVERY_RESTORE", isTelemetryTransport = false, isGuardianHandback = false),
                lastSurfacedKey = null,
            ),
        )
        // Policy D (2026-10-14): plain words, verbatim.
        assertEquals(
            "Performance profile paused — the device is back on its own settings.",
            MimirCopy.TweakerProfileDroppedGuardianHandback,
        )
        assertEquals("Re-apply your profile when you're ready.", MimirCopy.TweakerProfileDroppedReapplyHint)
        assertFalse(
            "a hand-back needs a re-apply, not a restart -- the hint must not say Restart",
            MimirCopy.TweakerProfileDroppedReapplyHint.contains("Restart"),
        )
    }

    /**
     * P3-6 (fence review, 2026-10-14): the latch is keyed on reason AND generation. The same reason
     * on a later session is a second drop and is announced again; the same pair is the same drop.
     */
    @Test
    fun `a repeat of the same reason on a later generation is a new incident`() {
        val first = TweakerLastRestore("GUARDIAN_TERMINAL_RECEIVED", isTelemetryTransport = false, isGuardianHandback = true, generation = 51L)
        val second = first.copy(generation = 52L)
        assertEquals("GUARDIAN_TERMINAL_RECEIVED#51", first.incidentKey)
        assertFalse("the same pair is the same drop", shouldSurfaceTweakerRestore(first, lastSurfacedKey = first.incidentKey))
        assertTrue("a later generation is a new drop", shouldSurfaceTweakerRestore(second, lastSurfacedKey = first.incidentKey))
    }

    @Test
    fun `the announced copy is the approved content, used verbatim`() {
        // MimirCopy labels these as proposed content for readers. Reuse that shared copy exactly;
        // final wording remains a design-review question.
        assertTrue(MimirCopy.TweakerProfileDroppedTelemetryLost.isNotBlank())
        assertTrue(
            "the headline must name the cause, not just the symptom",
            MimirCopy.TweakerProfileDroppedTelemetryLost.contains("thermal readings"),
        )
        assertTrue(
            "the reported incident needed BOTH halves restarted -- the hint must say so",
            MimirCopy.TweakerProfileDroppedRestartHint.contains("Restart"),
        )
    }
}

/**
 * the fan readout warms as the device approaches the active profile's own
 * target, replacing a constant ink. An *absolute* threshold could paint a healthy measured state
 * as a problem; this ramp
 * is relative to a number the profile's own curve already regulates toward.
 */
class TweakerFanTempWarmthTest {

    @Test
    fun `a device comfortably below its target is not coloured at all`() {
        // The critical case from the spec's objection: balanced's own measured PASS must not be
        // painted as anything. At 10C or more below target the ink is the authored token, untouched.
        assertEquals(0f, tweakerFanTempWarmFraction(gpuTempC = 60, targetTempC = 70), 0f)
        assertEquals(0f, tweakerFanTempWarmFraction(gpuTempC = 45, targetTempC = 70), 0f)
    }

    @Test
    fun `warmth rises with proximity and stops rising at the target`() {
        val halfway = tweakerFanTempWarmFraction(gpuTempC = 65, targetTempC = 70)
        assertTrue("halfway into the band must be partly warm: $halfway", halfway > 0f)
        val nearer = tweakerFanTempWarmFraction(gpuTempC = 68, targetTempC = 70)
        assertTrue("closer must be warmer: $nearer vs $halfway", nearer > halfway)

        // At and beyond the target the ramp is capped -- hotter does not keep escalating, because
        // the readout reports a temperature, it does not issue a verdict.
        val atTarget = tweakerFanTempWarmFraction(gpuTempC = 70, targetTempC = 70)
        assertEquals(atTarget, tweakerFanTempWarmFraction(gpuTempC = 85, targetTempC = 70), 0f)
        assertEquals(atTarget, tweakerFanTempWarmFraction(gpuTempC = 105, targetTempC = 70), 0f)
        assertTrue("and the cap stays short of a full molten blend: $atTarget", atTarget < 0.8f)
    }

    @Test
    fun `nothing established means nothing implied`() {
        // No target, or no reading, must produce no shift. With nothing to be near, a colour would
        // be exactly the unearned claim the design spec was right to refuse.
        assertEquals(0f, tweakerFanTempWarmFraction(gpuTempC = 90, targetTempC = null), 0f)
        assertEquals(0f, tweakerFanTempWarmFraction(gpuTempC = null, targetTempC = 70), 0f)
        assertEquals(0f, tweakerFanTempWarmFraction(gpuTempC = null, targetTempC = null), 0f)
    }

    /**
     * **This test replaces one that could not fail.** The original asserted
     * `tweakerFanTempWarmFraction(gpuTempC = 55, targetTempC = 70) == 0f` and called itself "a hot
     * CPU beside a cool GPU" -- but the function takes no CPU argument, so it was just another
     * below-band GPU case and would have passed against the pre-fix code verbatim. The sensor
     * choice is not made inside that function; it is made at the **call site**, which the old test
     * never touched. Reverting the call site to `max(gpu, cpu)` left the whole suite green.
     *
     * [[verify-the-instrument-can-see-the-defect]] -- a lesson this file cited and then failed to
     * apply to itself. Source-text is the right instrument for a call-site choice, and this file's
     * sibling module already uses it for exactly that.
     */
    @Test
    fun `the warm shift is fed the GPU reading alone, at the call site`() {
        val overlay = sequenceOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"),
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"),
        ).firstOrNull(File::isFile) ?: throw AssertionError("BrightnessOverlay.kt not found")
        val code = overlay.readText()
            .lineSequence()
            .map { it.substringBefore("//") }
            .filterNot { it.trimStart().startsWith("*") }
            .joinToString("\n")

        assertTrue(
            "the readout must warm on the GPU reading alone -- targetTempC is the fan curve's " +
                "target and the curve is GPU-driven; the package/CPU sensor is on its own scale " +
                "for the 84C intervention, and an Odin measured CPU 89.9C against a " +
                "GPU in the 50s",
            code.contains("tweakerFanTempWarmFraction(gpuTempC = gpuTempC, targetTempC = targetTempC)"),
        )
        assertFalse(
            "it must never be fed the hotter of the two readings",
            code.contains("listOfNotNull(gpuTempC, cpuTempC)") || code.contains("maxOf(gpuTempC"),
        )
    }
}

package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.tweaker.TweakerPreparationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch path's diagnostic labels.
 *
 * These exist because the player-facing copy states which tuning is in force and says nothing about
 * the operation — right for the player, useless for a bug report. The panel had
 * `MimirTweakerSelection`; the launch path had nothing, so a reported launch-time warning required a device investigation to attribute.
 *
 * The property worth gating is not the wording, it is **distinguishability**: every branch a launch
 * can take must be tellable apart in the log. A label that collapses two failures into one string
 * reproduces the defect it was written to fix.
 */
class TweakerLaunchDiagnosticTest {

    private val activation = TweakerV4LaunchFailurePhase.ACTIVATION
    private val renewal = TweakerV4LaunchFailurePhase.RENEWAL

    private fun failed(
        phase: TweakerV4LaunchFailurePhase = activation,
        stock: Boolean = false,
        noNewSession: Boolean = false,
        reason: String = "CPU_TARGET_BELOW_LIVE_FLOOR",
    ) = TweakerV4LaunchRuntimeOutcome.Failed(
        profileId = "quiet",
        reason = reason,
        phase = phase,
        noNewSessionConfirmed = noNewSession,
        stockConfirmed = stock,
    )

    @Test
    fun `a failure carries the daemon reason, which is the whole point`() {
        val label = failed().diagnosticLabel()
        assertTrue(
            "the reason is the only field that names WHY the daemon refused: $label",
            label.contains("CPU_TARGET_BELOW_LIVE_FLOOR"),
        )
        assertTrue("the profile must be named: $label", label.contains("quiet"))
        assertTrue("the phase distinguishes activation from renewal: $label", label.contains("ACTIVATION"))
    }

    @Test
    fun `a rejected handoff carries its reason`() {
        val label = TweakerV4LaunchHandoff.Rejected(reason = "SESSION_BUSY").diagnosticLabel()
        assertTrue("a rejection with no reason names no branch: $label", label.contains("SESSION_BUSY"))
    }

    @Test
    fun `every distinct outcome produces a distinct label`() {
        // The four Failed shapes below are exactly the four the player-facing copy collapses into
        // three strings. The log must not collapse them at all.
        val labels = listOf(
            TweakerV4LaunchRuntimeOutcome.Active("balanced"),
            TweakerV4LaunchRuntimeOutcome.DebugLabStarted("balanced"),
            failed(phase = activation, stock = true),
            failed(phase = activation, noNewSession = true),
            failed(phase = renewal, stock = true),
            failed(phase = activation, reason = "SOMETHING_ELSE"),
        ).map { it.diagnosticLabel() }

        assertEquals(
            "two outcomes sharing a label makes the log unable to tell them apart, which is the " +
                "defect this diagnostic exists to fix: $labels",
            labels.size,
            labels.toSet().size,
        )
    }

    @Test
    fun `every distinct handoff produces a distinct label`() {
        val labels = listOf(
            TweakerV4LaunchHandoff.NotScheduled,
            TweakerV4LaunchHandoff.Started("balanced"),
            TweakerV4LaunchHandoff.Started("quiet"),
            TweakerV4LaunchHandoff.Rejected("SESSION_BUSY"),
            TweakerV4LaunchHandoff.Rejected("NO_TICKET"),
        ).map { it.diagnosticLabel() }

        assertEquals(labels.size, labels.toSet().size)
    }

    /**
     * A success must be tellable from a silent no-op. "Nothing happened" and "a profile activated"
     * looking alike in the log is how a launch regression hides.
     */
    @Test
    fun `not-scheduled is distinguishable from a started handoff`() {
        assertTrue(
            TweakerV4LaunchHandoff.NotScheduled.diagnosticLabel() !=
                TweakerV4LaunchHandoff.Started("stock").diagnosticLabel(),
        )
    }

    /**
     * `Skipped` is the branch that decides whether a launch gets tuned, and its reason is the only
     * field saying why it did not. A deliberate Stock no-op and an owner that had not settled yet are
     * very different facts — one is correct, the other is lost tuning — and the log has to tell them
     * apart. Observed on device as a bare `prepared=Skipped`, which could not.
     */
    @Test
    fun `a skipped preflight carries the reason it skipped for`() {
        val label = TweakerPreparationResult.Skipped("Tweaker owner is still settling").diagnosticLabel()
        assertTrue(
            "without the reason, a correct Stock no-op and lost tuning look identical: $label",
            label.contains("Tweaker owner is still settling"),
        )
    }

    @Test
    fun `every distinct preflight result produces a distinct label`() {
        val labels = listOf(
            TweakerPreparationResult.Unavailable,
            TweakerPreparationResult.StockNoOp,
            TweakerPreparationResult.Applied("balanced"),
            TweakerPreparationResult.Scheduled("balanced"),
            TweakerPreparationResult.Skipped("Tweaker owner is still settling"),
            TweakerPreparationResult.Skipped("some other reason"),
        ).map { it.diagnosticLabel() }

        assertEquals(
            "two preflight results sharing a label makes the branch unattributable: $labels",
            labels.size,
            labels.toSet().size,
        )
    }
}

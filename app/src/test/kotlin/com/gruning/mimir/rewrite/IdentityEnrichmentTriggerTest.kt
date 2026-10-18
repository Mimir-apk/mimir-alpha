package com.gruning.mimir.rewrite

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * When a background identity sweep is asked for.
 *
 * ## The bug this file exists for
 *
 * The reported delay made the example library appear to take 24 minutes
 * to identify. It did not. Measured from device logcat, 2026-09-17:
 *
 * ```
 * 17:33:45  start: nothing unresolved          <- the scan has not inserted yet
 * 17:35:34  first-run artwork batch: games=66
 * 17:55:49  start: 66 unresolved               <- identification finally begins
 * 17:57:43  clear after 113591ms (remaining=0)
 * ```
 *
 * **113 seconds of work inside a 24-minute window.** The other twenty-two minutes were dead air: the scan
 * had inserted 66 unresolved games and nothing asked the enricher to run. It started at 17:55:49 only
 * because resuming the app recomposed a step-advance effect in `MimirOnboardingCoordinator`, which
 * enqueues from an entirely different code path.
 *
 * The trigger that should have done it read:
 *
 * ```
 * dao.unresolvedPrimaryCount().distinctUntilChanged().collect { count ->
 *     val libraryChoiceIsFinal = ...read the onboarding step here...
 *     if (count > 0 && libraryChoiceIsFinal) enqueue()
 * }
 * ```
 *
 * Two conditions; one of them observed. The count went 0 -> 66 while onboarding was still short of
 * METADATA, so the collector ran once and declined. When the step later became final the count had not
 * moved, `distinctUntilChanged` dropped the emission, and the decision was never retaken. The
 * coordinator's scope is process-lifetime rather than lifecycle-bound, so nothing restarted the collector
 * either.
 *
 * ## Why these tests are about wiring, not rules
 *
 * [identityEnrichmentAllowed] was never wrong, and `OnboardingCompletionGateTest` already covered it. A
 * correct predicate consulted at a moment that never comes again is still a hang. So these drive both
 * inputs of [identityEnrichmentTrigger] independently, and the load-bearing one is
 * `the step becoming final fires the trigger with the count unchanged` -- the exact sequence above, which
 * the old shape cannot pass.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdentityEnrichmentTriggerTest {

    private fun checkpoint(step: OnboardingStep) = OnboardingCheckpoint(step = step)

    /** **The regression pin.** The regression sequence: the games arrive early, the step catches up later. */
    @Test
    fun `the step becoming final fires the trigger with the count unchanged`() = runTest {
        val counts = MutableStateFlow(0)
        val checkpoints = MutableStateFlow(checkpoint(OnboardingStep.LIBRARY))
        val seen = mutableListOf<Boolean>()

        backgroundScope.launch {
            identityEnrichmentTrigger(counts, checkpoints, firstRun = { true }).collect(seen::add)
        }
        runCurrent()

        counts.value = 66
        runCurrent()
        assertEquals("still short of METADATA, so nothing is asked for yet", listOf(false), seen)

        // The count does NOT move here. Only the step does. This is the emission the old shape dropped,
        // and on the test device it cost twenty-two minutes.
        checkpoints.value = checkpoint(OnboardingStep.METADATA)
        runCurrent()

        assertEquals(listOf(false, true), seen)
    }

    @Test
    fun `games arriving while the step is already final fires immediately`() = runTest {
        val counts = MutableStateFlow(0)
        val checkpoints = MutableStateFlow(checkpoint(OnboardingStep.SETTLING))
        val seen = mutableListOf<Boolean>()

        backgroundScope.launch {
            identityEnrichmentTrigger(counts, checkpoints, firstRun = { true }).collect(seen::add)
        }
        runCurrent()
        counts.value = 66
        runCurrent()

        assertEquals(listOf(false, true), seen)
    }

    /** A decision that stays true must not re-enqueue on every count tick as the sweep drains. */
    @Test
    fun `a draining count does not re-ask while the answer stays true`() = runTest {
        val counts = MutableStateFlow(66)
        val checkpoints = MutableStateFlow(checkpoint(OnboardingStep.SETTLING))
        val seen = mutableListOf<Boolean>()

        backgroundScope.launch {
            identityEnrichmentTrigger(counts, checkpoints, firstRun = { true }).collect(seen::add)
        }
        runCurrent()
        listOf(60, 42, 17, 3).forEach {
            counts.value = it
            runCurrent()
        }

        assertEquals("one ask, not five", listOf(true), seen)

        counts.value = 0
        runCurrent()
        assertEquals(listOf(true, false), seen)
    }

    /**
     * [identityEnrichmentTrigger] reads `firstRun` per emission rather than capturing it. Onboarding
     * completing is precisely when a gate that had been shut must open, and a value captured once at
     * subscription would pin it shut for the life of the process -- the same class of mistake as the one
     * above, one level down.
     */
    @Test
    fun `firstRun is re-read, so finishing onboarding opens the gate`() = runTest {
        val counts = MutableStateFlow(66)
        val checkpoints = MutableStateFlow(checkpoint(OnboardingStep.LIBRARY))
        var firstRun = true
        val seen = mutableListOf<Boolean>()

        backgroundScope.launch {
            identityEnrichmentTrigger(counts, checkpoints, firstRun = { firstRun }).collect(seen::add)
        }
        runCurrent()
        assertEquals(listOf(false), seen)

        firstRun = false
        // Not a first run any more, so LIBRARY gates nothing. Something still has to re-emit for the flow
        // to notice, which is why the coordinator passes a LIVE checkpoint flow rather than a one-shot
        // read -- any checkpoint write at all re-opens the question.
        checkpoints.value = checkpoint(OnboardingStep.LIBRARY).copy(scanFilesSeen = 1)
        runCurrent()

        assertEquals(listOf(false, true), seen)
    }

    @Test
    fun `nothing unresolved is never a reason to run, at any step`() = runTest {
        OnboardingStep.entries.forEach { step ->
            val first = identityEnrichmentTrigger(
                unresolvedPrimaryCount = MutableStateFlow(0),
                checkpoints = MutableStateFlow(checkpoint(step)),
                firstRun = { false },
            ).first()
            assertFalse("step $step", first)
        }
    }
}

package com.gruning.mimir.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingCompletionGateTest {

    @Test
    fun identityWorkWaitsUntilTheFirstRunFolderChoiceIsFinal() {
        assertFalse(identityEnrichmentAllowed(firstRun = true, step = OnboardingStep.LIBRARY))
        assertTrue(identityEnrichmentAllowed(firstRun = true, step = OnboardingStep.METADATA))
        assertTrue(identityEnrichmentAllowed(firstRun = true, step = OnboardingStep.FAVORITES))
        assertTrue(identityEnrichmentAllowed(firstRun = false, step = OnboardingStep.WELCOME))
    }

    @Test
    fun libraryCannotAdvanceUntilScanAndDurableReadAccessAreBothReady() {
        val complete = OnboardingCheckpoint(scanStatus = OnboardingScanStatus.COMPLETE)

        assertFalse(
            onboardingAdvanceAllowed(
                step = OnboardingStep.LIBRARY,
                checkpoint = complete,
                durableReadAccess = false,
            ),
        )
        assertFalse(
            onboardingAdvanceAllowed(
                step = OnboardingStep.LIBRARY,
                checkpoint = complete.copy(scanStatus = OnboardingScanStatus.RUNNING),
                durableReadAccess = true,
            ),
        )
        assertTrue(
            onboardingAdvanceAllowed(
                step = OnboardingStep.LIBRARY,
                checkpoint = complete,
                durableReadAccess = true,
            ),
        )
    }

    @Test
    fun settlingCannotRevealShelfWhilePreparationIsPending() {
        val pending = OnboardingCheckpoint(
            scanStatus = OnboardingScanStatus.COMPLETE,
            settlementStatus = OnboardingSettlementStatus.PENDING,
        )

        assertFalse(
            onboardingAdvanceAllowed(
                step = OnboardingStep.SETTLING,
                checkpoint = pending,
                durableReadAccess = true,
            ),
        )
        assertTrue(
            onboardingAdvanceAllowed(
                step = OnboardingStep.SETTLING,
                checkpoint = pending.copy(settlementStatus = OnboardingSettlementStatus.READY),
                durableReadAccess = true,
            ),
        )
        assertFalse(
            onboardingAdvanceAllowed(
                step = OnboardingStep.SETTLING,
                checkpoint = pending.copy(settlementStatus = OnboardingSettlementStatus.READY),
                durableReadAccess = false,
            ),
        )
        assertTrue(
            onboardingAdvanceAllowed(
                step = OnboardingStep.SETTLING,
                checkpoint = pending.copy(settlementStatus = OnboardingSettlementStatus.LIMITED),
                durableReadAccess = true,
            ),
        )
    }

    @Test
    fun connectedDetailsRequireBothAnAccountAndTheExistingOptInGate() {
        assertFalse(connectedMetadataHydrationAllowed(null, connectedGateOpen = true))
        assertFalse(connectedMetadataHydrationAllowed("player", connectedGateOpen = false))
        assertTrue(connectedMetadataHydrationAllowed("player", connectedGateOpen = true))
    }

    @Test
    fun metadataStarterPassIsHardCappedForHugeLibraries() {
        val candidates = (0 until 10_000).toList()

        assertEquals(
            ConnectedMetadataHydrator.MaxGamesPerRun,
            boundedMetadataHydrationCandidates(candidates).size,
        )
    }

    @Test
    fun identityHashingUsesHalfTheCoresBetweenTwoAndFour() {
        assertEquals(2, identityHashConcurrency(1))
        assertEquals(2, identityHashConcurrency(4))
        assertEquals(3, identityHashConcurrency(6))
        assertEquals(4, identityHashConcurrency(8))
        assertEquals(4, identityHashConcurrency(16))
    }
}

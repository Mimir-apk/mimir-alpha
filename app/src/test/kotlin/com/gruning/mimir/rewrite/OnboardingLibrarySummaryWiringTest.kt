package com.gruning.mimir.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingLibrarySummaryWiringTest {
    private val source: String by lazy { pinSource() }

    @Test
    fun perRunSnapshotIsCollectedAndPassedToSettlement() {
        assertTrue(source.contains("IdentityEnrichmentLiveProgress.progress.collect"))
        assertTrue(source.contains("liveIdentityProgress = liveIdentityProgress"))
        assertFalse(source.contains("totalVisible = librarySummary.gameCount"))
    }

    @Test
    fun librarySummaryIsNotASettlementEffectKey() {
        val settlementEffects = listOf(
            source.sliceBetween(
                "LaunchedEffect(navigation.onboardingStep, checkpoint.scanStatus, resolvingCount)",
                "\n    }\n",
            ),
            source.sliceBetween(
                "LaunchedEffect(\n        navigation.onboardingStep,\n        checkpoint.scanStatus,\n        checkpoint.settlementStatus,",
                "IdentityEnrichmentWorker.enqueue(context)",
            ),
        )
        settlementEffects.forEach { effectHeader ->
            assertFalse(effectHeader.contains("librarySummary") || effectHeader.contains("gameCount"))
        }
    }

    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue(contains(after))
        assertTrue(contains(before))
        return substringAfter(after).substringBefore(before)
    }

    @Test
    fun liveCollectionIsGatedToTheSettlementScreenButNotToItsPendingStatus() {
        // settling READY/LIMITED releases the step but must not stop the hashing probe.
        val slice = source.substringAfter("val liveIdentityProgress by produceState")
            .substringBefore("val identityProgress by produceState")
        assertTrue(slice.contains("navigation.onboardingStep != OnboardingStep.SETTLING"))
        assertFalse(slice.contains("checkpoint.settlementStatus != OnboardingSettlementStatus.PENDING"))
    }

    private fun pinSource(): String {
        val name = "MimirOnboardingCoordinator.kt"
        val candidates = listOf(
            java.io.File("app/src/main/kotlin/com/gruning/mimir/rewrite/" + name),
            java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/" + name),
            java.io.File("../app/src/main/kotlin/com/gruning/mimir/rewrite/" + name),
        )
        return candidates.firstOrNull(java.io.File::isFile)?.readText() ?: error(name + " not found")
    }
}

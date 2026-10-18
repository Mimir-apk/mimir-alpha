package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-A regression. First-run settlement gated on `unresolvedPrimaryCount() == 0`, but
 * `ResolveAttempt.Retryable` is a legitimate, designed, non-terminal state (A1D: a differential CHD whose
 * parent is not attached yet). One such row blocked onboarding permanently — even when the enrichment
 * worker behaved correctly and returned success.
 *
 * Settling does not wait on identification at all, which retires the
 * bounded wait these once pinned. What remains: a LIMITED settle (Continue while identifying) still passes, and
 * the copy never claims identity finished when it did not.
 */
class OnboardingSettlementBudgetTest {

    @Test
    fun `settling marks the library ready without waiting on identification`() {
        val coordinator = listOf(
            java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/MimirOnboardingCoordinator.kt"),
            java.io.File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirOnboardingCoordinator.kt"),
        ).first(java.io.File::isFile).readText()
        val settle = coordinator.substringAfter("if (navigation.onboardingStep != OnboardingStep.SETTLING) return@LaunchedEffect")
            .substringBefore("val settlement = onboardingSettlementUi(")
        assertTrue(settle.contains("OnboardingStore.markSettlement(context, OnboardingSettlementStatus.READY, reason)"))
        assertFalse(
            "settling must not wait on identification again",
            Regex("""awaitSettled\w*\(""").containsMatchIn(settle),
        )
    }

    @Test
    fun `LIMITED is a passable settlement, so an unresolvable row cannot strand the user`() {
        val limited = OnboardingCheckpoint(
            scanStatus = OnboardingScanStatus.COMPLETE,
            settlementStatus = OnboardingSettlementStatus.LIMITED,
        )
        assertTrue(
            onboardingAdvanceAllowed(
                step = OnboardingStep.SETTLING,
                checkpoint = limited,
                durableReadAccess = true,
            ),
        )

        val pending = OnboardingCheckpoint(
            scanStatus = OnboardingScanStatus.COMPLETE,
            settlementStatus = OnboardingSettlementStatus.PENDING,
        )
        assertFalse(
            "PENDING must still hold the step — the bound is what releases it, not a weaker gate",
            onboardingAdvanceAllowed(
                step = OnboardingStep.SETTLING,
                checkpoint = pending,
                durableReadAccess = true,
            ),
        )
    }

    @Test
    fun `settlement copy never claims identity finished when it did not`() {
        val unconverged = settlementReasonForEnrichment(converged = false, connectedDetails = false)
        assertTrue(
            "an unconverged settle must say work continues",
            unconverged.contains("still being identified"),
        )
        assertFalse(
            "an unconverged settle must not imply artwork was fetched",
            unconverged.contains("artwork downloads"),
        )

        val converged = settlementReasonForEnrichment(converged = true, connectedDetails = false)
        assertFalse(converged.contains("still being identified"))
    }

    @Test
    fun `the art promise is only made where it holds`() {
        // Spec B3: ScreenScraper's hydration fetches text only, so without a SteamGridDB key Mimir does ask first.
        assertEquals(
            "Game details fill in after setup. Mimir asks before downloading any artwork.",
            settlementReasonForEnrichment(converged = true, connectedDetails = true),
        )
        // With a key, Home fetches the shelved games' tiles by itself, so the line may not promise to ask.
        val keyed = settlementReasonForEnrichment(converged = true, connectedDetails = false, steamGridDbKey = true)
        assertFalse(keyed.contains("asks before"))
        assertTrue(keyed.contains("SteamGridDB"))
        for (line in listOf(
            settlementReasonForEnrichment(converged = true, connectedDetails = true),
            settlementReasonForEnrichment(converged = false, connectedDetails = false),
        )) {
            assertFalse("the line must not repeat the title", line.contains("library is ready"))
            assertFalse("pass was ordered out in R2", line.contains("pass"))
        }
    }
}

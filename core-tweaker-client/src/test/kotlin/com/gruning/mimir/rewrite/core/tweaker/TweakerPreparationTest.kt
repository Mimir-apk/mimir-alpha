package com.gruning.mimir.rewrite.core.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TweakerPreparationTest {
    @Test
    fun `stock no op requires restored or no active session`() {
        assertEquals(
            TweakerPreparationResult.StockNoOp,
            interpretStockRestore(
                TweakerApplyResult(true, false, true, "RESTORED", "Stock state restored.", null),
            ),
        )
        assertEquals(
            TweakerPreparationResult.StockNoOp,
            interpretStockRestore(
                TweakerApplyResult(true, false, false, "NO_ACTIVE_SESSION", "Nothing to restore.", null),
            ),
        )
        assertTrue(interpretStockRestore(null) is TweakerPreparationResult.Skipped)
        assertTrue(
            interpretStockRestore(
                TweakerApplyResult(false, false, false, "RESTORE_FAILED", "Restore failed.", null),
            ) is TweakerPreparationResult.Skipped,
        )
    }

    @Test
    fun `isConfirmedApply requires success applied and APPLIED code`() {
        assertTrue(
            isConfirmedApply(TweakerApplyResult(true, true, false, "APPLIED", "Profile applied.", "performance")),
        )
        assertFalse(
            isConfirmedApply(TweakerApplyResult(true, false, false, "DRY_RUN", "Dry run.", "performance")),
        )
        assertFalse(isConfirmedApply(null))
    }

    @Test
    fun `confirmed apply returns applied without invoking restore fallback`() {
        var restoreCalls = 0
        val result = interpretTunableApply(
            profileId = "performance",
            apply = TweakerApplyResult(true, true, false, "APPLIED", "Profile applied.", "performance"),
            restoreFallback = {
                restoreCalls++
                TweakerApplyResult(true, false, true, "RESTORED", "Stock state restored.", null)
            },
        )
        assertEquals(TweakerPreparationResult.Applied("performance"), result)
        assertEquals(0, restoreCalls)
    }

    @Test
    fun `non applied tunable invokes restore fallback once`() {
        var restoreCalls = 0
        val skipped = interpretTunableApply(
            profileId = "balanced",
            apply = TweakerApplyResult(false, false, false, "CALIBRATION_REQUIRED", "Uncertified.", "balanced"),
            restoreFallback = {
                restoreCalls++
                TweakerApplyResult(true, false, true, "RESTORED", "Stock state restored.", null)
            },
        ) as TweakerPreparationResult.Skipped
        assertEquals("Performance profile apply did not take — stock settings restored", skipped.reason)
        assertFalse("reason must not name certification status", skipped.reason.contains("certif", ignoreCase = true))
        assertEquals(1, restoreCalls)
    }

    @Test
    fun `plan not admitted refusal maps the same as the older calibration required code`() {
        listOf("PLAN_NOT_ADMITTED", "CALIBRATION_REQUIRED").forEach { code ->
            var restoreCalls = 0
            val skipped = interpretTunableApply(
                profileId = "balanced",
                apply = TweakerApplyResult(false, false, false, code, "Not admitted.", "balanced"),
                restoreFallback = {
                    restoreCalls++
                    TweakerApplyResult(true, false, true, "RESTORED", "Stock state restored.", null)
                },
            ) as TweakerPreparationResult.Skipped
            assertEquals(code, "Performance profile apply did not take — stock settings restored", skipped.reason)
            assertEquals(code, 1, restoreCalls)
        }
    }

    @Test
    fun `dry run does not invoke restore fallback`() {
        var restoreCalls = 0
        val skipped = interpretTunableApply(
            profileId = "performance",
            apply = TweakerApplyResult(true, false, false, "DRY_RUN", "Dry run.", "performance"),
            restoreFallback = {
                restoreCalls++
                null
            },
        ) as TweakerPreparationResult.Skipped
        assertEquals("Tweaker dry-run — hardware unchanged", skipped.reason)
        assertEquals(0, restoreCalls)
    }

    @Test
    fun `write gate disabled does not invoke restore fallback`() {
        var restoreCalls = 0
        val skipped = interpretTunableApply(
            profileId = "performance",
            apply = TweakerApplyResult(
                false,
                false,
                false,
                "WRITE_GATE_DISABLED",
                "Hardware mutation is compile-time disabled.",
                "performance",
            ),
            restoreFallback = {
                restoreCalls++
                null
            },
        ) as TweakerPreparationResult.Skipped
        assertEquals("Tweaker write gate disabled — hardware unchanged", skipped.reason)
        assertEquals(0, restoreCalls)
    }

    @Test
    fun `unavailable warns only for intentional certified selection`() {
        val certified = TweakerLaunchSelectionContext(
            selectedProfileId = "performance",
            certifiedProfileIds = setOf("performance"),
        )
        assertEquals(
            "Tweaker unavailable — launch continues without tuning",
            TweakerPreparationResult.Unavailable.launchWarning(certified),
        )
        assertNull(
            TweakerPreparationResult.Unavailable.launchWarning(
                certified.copy(selectedProfileId = TWEAKER_STOCK_PROFILE_ID),
            ),
        )
        assertNull(TweakerPreparationResult.StockNoOp.launchWarning(certified))
    }

    @Test
    fun `isStockConfirmed accepts restored flag only with success codes`() {
        assertTrue(isStockConfirmed(TweakerApplyResult(true, false, true, "RESTORED", "", null)))
        assertTrue(isStockConfirmed(TweakerApplyResult(true, false, false, "NO_ACTIVE_SESSION", "", null)))
        assertFalse(isStockConfirmed(TweakerApplyResult(false, false, false, "RESTORE_FAILED", "", null)))
    }
}

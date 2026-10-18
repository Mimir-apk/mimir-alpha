package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemWideProfileTransitionTest {
    @Test
    fun `reselecting the active system profile executes zero hardware paths`() {
        val calls = mutableListOf<String>()

        val result = SystemWideProfileTransition.execute(
            requestedProfileId = "balanced",
            activeMutation = true,
            activeSystemProfileId = "balanced",
            restore = { calls += "restore"; restored() },
            verifiedStock = { calls += "verify-stock"; false },
            onVerifiedStock = { calls += "settle-stock" },
            onUnverifiedRestore = { calls += "supervise-recovery" },
            apply = { calls += "apply"; applied() },
        )

        assertEquals("SYSTEM_PROFILE_UNCHANGED", result.code)
        assertTrue(result.success)
        assertFalse(result.applied)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `profile switch verifies Stock before applying the replacement`() {
        val calls = mutableListOf<String>()
        var stock = false

        val result = SystemWideProfileTransition.execute(
            requestedProfileId = "performance",
            activeMutation = true,
            activeSystemProfileId = "quiet",
            restore = { calls += "restore"; stock = true; restored() },
            verifiedStock = { calls += "verify-stock"; stock },
            onVerifiedStock = { calls += "settle-stock" },
            onUnverifiedRestore = { calls += "supervise-recovery" },
            apply = { calls += "apply"; applied("performance") },
        )

        assertTrue(result.applied)
        assertEquals(listOf("restore", "verify-stock", "settle-stock", "apply"), calls)
    }

    @Test
    fun `failed replacement rolls back and returns verified Stock`() {
        val calls = mutableListOf<String>()
        var stock = false
        var restoreCount = 0

        val result = SystemWideProfileTransition.execute(
            requestedProfileId = "quiet",
            activeMutation = false,
            activeSystemProfileId = null,
            restore = {
                calls += "restore"
                restoreCount++
                stock = true
                restored()
            },
            verifiedStock = { calls += "verify-stock"; stock },
            onVerifiedStock = { calls += "settle-stock" },
            onUnverifiedRestore = { calls += "supervise-recovery" },
            apply = {
                calls += "apply"
                stock = false
                TransactionResult(false, false, false, "APPLY_VERIFY_FAILED", "failed")
            },
        )

        assertEquals("APPLY_VERIFY_FAILED", result.code)
        assertTrue(result.restored)
        assertEquals(1, restoreCount)
        assertEquals(listOf("apply", "verify-stock", "restore", "verify-stock", "settle-stock"), calls)
    }

    @Test
    fun `failed switch restore invokes recovery supervision before returning`() {
        val calls = mutableListOf<String>()
        var applyCalls = 0

        val result = SystemWideProfileTransition.execute(
            requestedProfileId = "performance",
            activeMutation = true,
            activeSystemProfileId = "quiet",
            restore = {
                calls += "restore"
                TransactionResult(false, false, false, "RESTORE_FAILED", "failed")
            },
            verifiedStock = { calls += "verify-stock"; false },
            onVerifiedStock = {},
            onUnverifiedRestore = { calls += "supervise-recovery" },
            apply = { applyCalls++; applied() },
        )

        assertEquals("SYSTEM_STOCK_NOT_VERIFIED", result.code)
        assertEquals(0, applyCalls)
        assertEquals(listOf("restore", "verify-stock", "supervise-recovery"), calls)
    }

    private fun applied(profileId: String = "balanced") = TransactionResult(
        success = true,
        applied = true,
        restored = false,
        code = "APPLIED",
        message = "applied",
        plan = TuningPlan(profileId, "", mapOf(0 to 1_000), 0, null, true),
    )

    private fun restored() = TransactionResult(true, false, true, "RESTORED", "restored")
}

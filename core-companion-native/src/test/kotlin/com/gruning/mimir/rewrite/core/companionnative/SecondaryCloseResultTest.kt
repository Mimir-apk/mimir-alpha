package com.gruning.mimir.rewrite.core.companionnative

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondaryCloseResultTest {
    @Test
    fun closeResultNeverClaimsGuaranteedClose() {
        val result = SecondaryCloseResult(
            requestedPackages = listOf("org.example.emulator"),
            operationIdsByPackage = mapOf("org.example.emulator" to java.util.UUID(0L, 1L)),
        )

        assertTrue(result.closeRequested)
        assertFalse(result.clearedTrackedState)
        assertFalse(result.closeGuarantee)
        assertTrue(result.mediaMayContinue)
        assertTrue(result.localOnly)
        assertTrue(result.truth.contains("best-effort"))
        assertTrue(result.truth.contains("Android may keep"))
        assertTrue(result.truth.contains("tracking remains"))
    }

    @Test
    fun emptyCloseResultIsHonestNoRequest() {
        val result = SecondaryCloseResult(
            requestedPackages = emptyList(),
            operationIdsByPackage = emptyMap(),
        )

        assertFalse(result.closeRequested)
        assertFalse(result.closeGuarantee)
        assertTrue(result.mediaMayContinue)
    }
}

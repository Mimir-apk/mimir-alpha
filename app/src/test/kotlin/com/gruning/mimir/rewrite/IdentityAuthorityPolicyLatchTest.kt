package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.AthenaIdentityPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityAuthorityPolicyLatchTest {

    @Test
    fun `matching authority policy is a no-op`() = runBlocking {
        val events = mutableListOf<String>()

        val result = reconcileIdentityAuthorityPolicy(
            storedStamp = AthenaIdentityPolicy.STAMP,
            currentStamp = AthenaIdentityPolicy.STAMP,
            resetLegacyIdentity = { events += "reset"; 3 },
            persistStamp = { events += "persist"; true },
        )

        assertFalse(result.changed)
        assertEquals(0, result.clearedLegacy)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `authority policy reset and durable enqueue finish before stamp persistence`() = runBlocking {
        val events = mutableListOf<String>()

        val result = reconcileIdentityAuthorityPolicy(
            storedStamp = "legacy",
            currentStamp = AthenaIdentityPolicy.STAMP,
            resetLegacyIdentity = {
                resetLegacyIdentityForAuthorityPolicyChange(
                    clearLegacyIdentity = { events += "clear"; 7 },
                    enqueueTrailingPass = { events += "enqueue-await" },
                )
            },
            persistStamp = { stamp ->
                events += "persist:$stamp"
                true
            },
        )

        assertTrue(result.changed)
        assertEquals(7, result.clearedLegacy)
        assertEquals(
            listOf(
                "clear",
                "enqueue-await",
                "persist:${AthenaIdentityPolicy.STAMP}",
            ),
            events,
        )
    }

    @Test
    fun `reset or persistence failure leaves policy retryable`() = runBlocking {
        var persistedAfterResetFailure = false
        val resetFailure = runCatching {
            reconcileIdentityAuthorityPolicy(
                storedStamp = null,
                currentStamp = AthenaIdentityPolicy.STAMP,
                resetLegacyIdentity = { error("database unavailable") },
                persistStamp = { persistedAfterResetFailure = true; true },
            )
        }.exceptionOrNull()

        var resets = 0
        val persistenceFailure = runCatching {
            reconcileIdentityAuthorityPolicy(
                storedStamp = null,
                currentStamp = AthenaIdentityPolicy.STAMP,
                resetLegacyIdentity = { resets++; 2 },
                persistStamp = { false },
            )
        }.exceptionOrNull()

        assertTrue(resetFailure is IllegalStateException)
        assertFalse(persistedAfterResetFailure)
        assertTrue(persistenceFailure is IllegalStateException)
        assertEquals(1, resets)
    }
}

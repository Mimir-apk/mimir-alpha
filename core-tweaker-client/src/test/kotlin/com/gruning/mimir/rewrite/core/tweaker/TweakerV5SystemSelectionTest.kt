package com.gruning.mimir.rewrite.core.tweaker

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class TweakerV5SystemSelectionTest {
    @Test
    fun `api 4 query contract remains readable when service negotiates api 5`() {
        val legacyShape = stockBundle("SESSION_QUERY", restored = false).apply {
            remove("sessionScope")
        }

        val parsed = parseTweakerV4SessionBundle(
            bundle = legacyShape,
            expectedApiVersion = 5,
            expectedCallerRequestId = REQUEST_ID,
            expectedCallerDeadlineElapsedRealtimeMs = DEADLINE_MS,
            observedAtElapsedRealtimeMs = 1_000L,
        )

        assertTrue(parsed is TweakerV4SessionAssessment.Stock)
    }

    @Test
    fun `api 4 client fails closed before invoking system scope parser`() {
        val parsed = parseTweakerV5SystemSelectionBundle(
            bundle = activeBundle("APPLIED", applied = true),
            expectedApiVersion = 4,
            attempt = attempt("quiet"),
            observedAtElapsedRealtimeMs = 1_000L,
        )

        assertTrue(parsed is TweakerV5SystemSelectionAssessment.Uncertain)
    }

    @Test
    fun `applied system profile returns nonrenewable immutable hard lease`() {
        val parsed = parseTweakerV5SystemSelectionBundle(
            bundle = activeBundle("APPLIED", applied = true),
            expectedApiVersion = 5,
            attempt = attempt("quiet"),
            observedAtElapsedRealtimeMs = 1_000L,
        )

        assertTrue(parsed is TweakerV5SystemSelectionAssessment.Active)
        parsed as TweakerV5SystemSelectionAssessment.Active
        assertTrue(parsed.changed)
        assertEquals(TweakerSessionScope.SYSTEM, parsed.truth.scope)
        assertEquals("quiet", parsed.truth.activeProfileId)
        assertEquals(43_200_000L, parsed.owner?.leaseDeadlineElapsedRealtimeMs)
        assertEquals(43_200_000L, parsed.owner?.hardLeaseDeadlineElapsedRealtimeMs)
    }

    @Test
    fun `same profile no-op is accepted without inventing a new owner`() {
        val bundle = activeBundle("SYSTEM_PROFILE_UNCHANGED", applied = false).apply {
            putString("requestId", "prior-owner")
            putLong("requestDeadlineElapsedRealtimeMs", 2_500L)
        }

        val parsed = parseTweakerV5SystemSelectionBundle(
            bundle = bundle,
            expectedApiVersion = 5,
            attempt = attempt("quiet"),
            observedAtElapsedRealtimeMs = 1_000L,
        )

        assertTrue(parsed is TweakerV5SystemSelectionAssessment.Active)
        parsed as TweakerV5SystemSelectionAssessment.Active
        assertEquals(false, parsed.changed)
        assertNull(parsed.owner)
    }

    @Test
    fun `unchanged system truth rejects a renewable lease`() {
        val parsed = parseUnchanged(
            unchangedBundle().apply { putBoolean("leaseRenewable", true) },
        )

        assertTrue(parsed is TweakerV5SystemSelectionAssessment.Uncertain)
    }

    @Test
    fun `unchanged system truth rejects an expired hard lease`() {
        val parsed = parseUnchanged(
            unchangedBundle().apply {
                putLong("leaseDeadlineElapsedRealtimeMs", OBSERVED_AT_MS)
                putLong("hardLeaseDeadlineElapsedRealtimeMs", OBSERVED_AT_MS)
            },
        )

        assertTrue(parsed is TweakerV5SystemSelectionAssessment.Uncertain)
    }

    @Test
    fun `unchanged system truth rejects unequal or missing lease deadlines`() {
        val unequal = unchangedBundle().apply {
            putLong("leaseDeadlineElapsedRealtimeMs", 40_000L)
        }
        val missingLease = unchangedBundle().apply { remove("leaseDeadlineElapsedRealtimeMs") }
        val missingHard = unchangedBundle().apply { remove("hardLeaseDeadlineElapsedRealtimeMs") }

        listOf(unequal, missingLease, missingHard).forEach { bundle ->
            assertTrue(parseUnchanged(bundle) is TweakerV5SystemSelectionAssessment.Uncertain)
        }
    }

    @Test
    fun `unchanged system truth rejects incomplete active identity`() {
        val missingTransaction = unchangedBundle().apply { remove("transactionId") }

        assertTrue(parseUnchanged(missingTransaction) is TweakerV5SystemSelectionAssessment.Uncertain)
    }

    @Test
    fun `Stock off accepts verified restore and exact no-op forms`() {
        val restored = parseTweakerV5SystemSelectionBundle(
            bundle = stockBundle("RESTORED", restored = true),
            expectedApiVersion = 5,
            attempt = attempt("stock"),
            observedAtElapsedRealtimeMs = 1_000L,
        )
        val unchanged = parseTweakerV5SystemSelectionBundle(
            bundle = stockBundle("SYSTEM_STOCK_UNCHANGED", restored = false),
            expectedApiVersion = 5,
            attempt = attempt("stock"),
            observedAtElapsedRealtimeMs = 1_000L,
        )

        assertTrue((restored as TweakerV5SystemSelectionAssessment.Stock).changed)
        assertEquals(false, (unchanged as TweakerV5SystemSelectionAssessment.Stock).changed)
    }

    private fun attempt(profileId: String) = TweakerV5SystemSelectionAttempt(
        requestId = REQUEST_ID,
        profileId = profileId,
        requestDeadlineElapsedRealtimeMs = DEADLINE_MS,
    )

    private fun unchangedBundle(): Bundle = activeBundle(
        code = "SYSTEM_PROFILE_UNCHANGED",
        applied = false,
    ).apply {
        putString("requestId", "prior-owner")
        putLong("requestDeadlineElapsedRealtimeMs", 2_500L)
    }

    private fun parseUnchanged(bundle: Bundle): TweakerV5SystemSelectionAssessment =
        parseTweakerV5SystemSelectionBundle(
            bundle = bundle,
            expectedApiVersion = 5,
            attempt = attempt("quiet"),
            observedAtElapsedRealtimeMs = OBSERVED_AT_MS,
        )

    private fun activeBundle(code: String, applied: Boolean): Bundle = Bundle().apply {
        putInt("apiVersion", 5)
        putBoolean("success", true)
        putBoolean("applied", applied)
        putBoolean("restored", false)
        putString("code", code)
        putString("message", "test result")
        putString("callerRequestId", REQUEST_ID)
        putLong("callerDeadlineElapsedRealtimeMs", DEADLINE_MS)
        putString("sessionState", "ACTIVE")
        putBoolean("mutationMayBeActive", true)
        putString("requestId", REQUEST_ID)
        putLong("generation", 7L)
        putString("transactionId", "tx-7")
        putLong("requestDeadlineElapsedRealtimeMs", DEADLINE_MS)
        putLong("leaseDeadlineElapsedRealtimeMs", 43_200_000L)
        putLong("hardLeaseDeadlineElapsedRealtimeMs", 43_200_000L)
        putBoolean("leaseRenewable", false)
        putString("sessionScope", "SYSTEM")
        putString("activeProfileId", "quiet")
    }

    private fun stockBundle(code: String, restored: Boolean): Bundle = Bundle().apply {
        putInt("apiVersion", 5)
        putBoolean("success", true)
        putBoolean("applied", false)
        putBoolean("restored", restored)
        putString("code", code)
        putString("message", "test result")
        putString("callerRequestId", REQUEST_ID)
        putLong("callerDeadlineElapsedRealtimeMs", DEADLINE_MS)
        putString("sessionState", "STOCK")
        putBoolean("mutationMayBeActive", false)
        putBoolean("leaseRenewable", false)
        putString("sessionScope", "NONE")
    }

    private companion object {
        const val REQUEST_ID = "mimir.system.test.1"
        const val DEADLINE_MS = 3_000L
        const val OBSERVED_AT_MS = 1_000L
    }
}

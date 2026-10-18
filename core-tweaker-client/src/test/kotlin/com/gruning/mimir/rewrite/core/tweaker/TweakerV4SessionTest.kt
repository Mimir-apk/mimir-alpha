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
class TweakerV4SessionTest {
    @Test
    fun `public state model contains exactly the seven service states`() {
        assertEquals(
            listOf(
                "STOCK",
                "APPLYING",
                "ACTIVE",
                "RESTORING",
                "RECOVERY_FAILED",
                "CORRUPT",
                "UNKNOWN",
            ),
            TweakerV4SessionState.entries.map { it.name },
        )
    }

    @Test
    fun `only exact mutation-free stock earns stock assessment`() {
        val parsed = parse(stockBundle())

        assertTrue(parsed is TweakerV4SessionAssessment.Stock)
        val truth = (parsed as TweakerV4SessionAssessment.Stock).truth
        assertEquals(TweakerV4SessionState.STOCK, truth.state)
        assertEquals(false, truth.mutationMayBeActive)
    }

    @Test
    fun `all valid non-stock states remain blocked`() {
        val applying = stockBundle().apply {
            putString("sessionState", "APPLYING")
            putString("requestId", "owner-request")
        }
        val active = recordBundle("ACTIVE").apply {
            putLong("leaseDeadlineElapsedRealtimeMs", 8_000L)
            putBoolean("leaseRenewable", true)
        }
        val restoring = recordBundle("RESTORING")
        val recoveryFailed = recordBundle("RECOVERY_FAILED")
        val corrupt = stockBundle().apply {
            putString("sessionState", "CORRUPT")
            putBoolean("mutationMayBeActive", true)
        }
        val unknown = stockBundle().apply {
            putString("sessionState", "UNKNOWN")
            putBoolean("mutationMayBeActive", true)
        }

        listOf(applying, active, restoring, recoveryFailed, corrupt, unknown).forEach { bundle ->
            assertTrue(
                "${bundle.getString("sessionState")} must not earn Stock",
                parse(bundle) is TweakerV4SessionAssessment.Blocked,
            )
        }
    }

    @Test
    fun `contradictory stock and active mutation truth fail closed`() {
        val stockWithIdentity = stockBundle().apply {
            putString("requestId", "unexpected-owner")
        }
        val activeWithoutMutation = recordBundle("ACTIVE").apply {
            putLong("leaseDeadlineElapsedRealtimeMs", 8_000L)
            putBoolean("mutationMayBeActive", false)
        }

        assertTrue(parse(stockWithIdentity) is TweakerV4SessionAssessment.Uncertain)
        assertTrue(parse(activeWithoutMutation) is TweakerV4SessionAssessment.Uncertain)
    }

    @Test
    fun `version request echo and caller deadline are exact`() {
        assertTrue(
            parse(stockBundle().apply { putInt("apiVersion", 5) }) is
                TweakerV4SessionAssessment.Uncertain,
        )
        assertTrue(
            parse(stockBundle().apply { putString("callerRequestId", "other") }) is
                TweakerV4SessionAssessment.Uncertain,
        )
        assertTrue(
            parse(stockBundle().apply { putLong("callerDeadlineElapsedRealtimeMs", 2_001L) }) is
                TweakerV4SessionAssessment.Uncertain,
        )
        assertTrue(
            parseTweakerV4SessionBundle(
                bundle = stockBundle(),
                expectedApiVersion = 4,
                expectedCallerRequestId = REQUEST_ID,
                expectedCallerDeadlineElapsedRealtimeMs = DEADLINE_MS,
                observedAtElapsedRealtimeMs = DEADLINE_MS,
            ) is TweakerV4SessionAssessment.Uncertain,
        )
    }

    @Test
    fun `partial identity and impossible lease deadline fail closed`() {
        val partial = stockBundle().apply {
            putString("sessionState", "RESTORING")
            putBoolean("mutationMayBeActive", true)
            putString("requestId", "owner-request")
            putLong("generation", 7L)
        }
        val impossibleLease = recordBundle("ACTIVE").apply {
            putLong("leaseDeadlineElapsedRealtimeMs", 10_001L)
            putBoolean("leaseRenewable", true)
        }

        assertTrue(parse(partial) is TweakerV4SessionAssessment.Uncertain)
        assertTrue(parse(impossibleLease) is TweakerV4SessionAssessment.Uncertain)
    }

    @Test
    fun `canonical apply returns exact owned identity`() {
        val attempt = applyAttempt()
        val parsed = parseTweakerV4OperationBundle(
            bundle = activeOperationBundle(
                callerDeadline = attempt.requestDeadlineElapsedRealtimeMs,
                code = "APPLIED",
                applied = true,
            ),
            expectedApiVersion = 4,
            expectation = TweakerV4OperationExpectation.Apply(attempt),
            observedAtElapsedRealtimeMs = 1_500L,
        )

        assertTrue(parsed is TweakerV4OperationAssessment.Accepted)
        val accepted = parsed as TweakerV4OperationAssessment.Accepted
        assertEquals(TweakerV4Operation.APPLY, accepted.operation)
        assertEquals("owner-request", accepted.owner?.requestId)
        assertEquals(7L, accepted.owner?.generation)
        assertEquals("tx-7", accepted.owner?.transactionId)
        assertEquals(8_000L, accepted.owner?.leaseDeadlineElapsedRealtimeMs)
        assertEquals(10_000L, accepted.owner?.hardLeaseDeadlineElapsedRealtimeMs)
    }

    @Test
    fun `valid service failure is rejected with authoritative truth`() {
        val attempt = applyAttempt()
        val parsed = parseTweakerV4OperationBundle(
            bundle = stockOperationBundle(
                callerDeadline = attempt.requestDeadlineElapsedRealtimeMs,
                success = false,
                code = "LEASE_POLICY_UNAVAILABLE",
            ),
            expectedApiVersion = 4,
            expectation = TweakerV4OperationExpectation.Apply(attempt),
            observedAtElapsedRealtimeMs = 1_500L,
        )

        assertTrue(parsed is TweakerV4OperationAssessment.Rejected)
        val rejected = parsed as TweakerV4OperationAssessment.Rejected
        assertEquals("LEASE_POLICY_UNAVAILABLE", rejected.code)
        assertEquals(TweakerV4SessionState.STOCK, rejected.truth.state)
    }

    @Test
    fun `rejection with applied flag success code or non-stock restore is uncertain`() {
        val attempt = applyAttempt()
        val appliedFailure = stockOperationBundle(
            callerDeadline = attempt.requestDeadlineElapsedRealtimeMs,
            success = false,
            code = "CALIBRATION_REQUIRED",
            applied = true,
        )
        val successCodeFailure = stockOperationBundle(
            callerDeadline = attempt.requestDeadlineElapsedRealtimeMs,
            success = false,
            code = "APPLIED",
        )
        val nonStockRestoreFailure = applyingOperationBundle(
            attempt.requestDeadlineElapsedRealtimeMs,
        ).apply {
            putBoolean("success", false)
            putBoolean("restored", true)
            putString("code", "SESSION_ADOPT_FAILED")
        }

        listOf(appliedFailure, successCodeFailure, nonStockRestoreFailure).forEach { bundle ->
            assertTrue(
                parseTweakerV4OperationBundle(
                    bundle = bundle,
                    expectedApiVersion = 4,
                    expectation = TweakerV4OperationExpectation.Apply(attempt),
                    observedAtElapsedRealtimeMs = 1_500L,
                ) is TweakerV4OperationAssessment.Uncertain,
            )
        }
    }

    @Test
    fun `successful apply with changed identity or expired lease is uncertain`() {
        val attempt = applyAttempt()
        val wrongOwner = activeOperationBundle(
            callerDeadline = attempt.requestDeadlineElapsedRealtimeMs,
            code = "APPLIED",
            applied = true,
        ).apply { putString("requestId", "another-owner") }
        val expiredLease = activeOperationBundle(
            callerDeadline = attempt.requestDeadlineElapsedRealtimeMs,
            code = "APPLIED",
            applied = true,
        ).apply { putLong("leaseDeadlineElapsedRealtimeMs", 1_499L) }

        listOf(wrongOwner, expiredLease).forEach { bundle ->
            assertTrue(
                parseTweakerV4OperationBundle(
                    bundle = bundle,
                    expectedApiVersion = 4,
                    expectation = TweakerV4OperationExpectation.Apply(attempt),
                    observedAtElapsedRealtimeMs = 1_500L,
                ) is TweakerV4OperationAssessment.Uncertain,
            )
        }
    }

    @Test
    fun `renew preserves owner and hard deadline while strictly advancing lease`() {
        val owner = ownedSession()
        val expectation = TweakerV4OperationExpectation.Renew(owner, 4_500L)
        val accepted = parseTweakerV4OperationBundle(
            bundle = activeOperationBundle(
                callerDeadline = 4_500L,
                code = "LEASE_RENEWED",
                leaseDeadline = 9_000L,
            ),
            expectedApiVersion = 4,
            expectation = expectation,
            observedAtElapsedRealtimeMs = 2_500L,
        )
        val unchanged = parseTweakerV4OperationBundle(
            bundle = activeOperationBundle(
                callerDeadline = 4_500L,
                code = "LEASE_RENEWED",
                leaseDeadline = owner.leaseDeadlineElapsedRealtimeMs,
            ),
            expectedApiVersion = 4,
            expectation = expectation,
            observedAtElapsedRealtimeMs = 2_500L,
        )

        assertTrue(accepted is TweakerV4OperationAssessment.Accepted)
        assertEquals(9_000L, (accepted as TweakerV4OperationAssessment.Accepted).owner?.leaseDeadlineElapsedRealtimeMs)
        assertTrue(unchanged is TweakerV4OperationAssessment.Uncertain)
    }

    @Test
    fun `cancel and restore accept only their exact terminal states`() {
        val attempt = applyAttempt()
        val cancel = parseTweakerV4OperationBundle(
            bundle = applyingOperationBundle(4_500L),
            expectedApiVersion = 4,
            expectation = TweakerV4OperationExpectation.CancelInFlight(attempt, 4_500L),
            observedAtElapsedRealtimeMs = 2_000L,
        )
        val restore = parseTweakerV4OperationBundle(
            bundle = stockOperationBundle(
                callerDeadline = 4_500L,
                code = "RESTORED",
                restored = true,
            ),
            expectedApiVersion = 4,
            expectation = TweakerV4OperationExpectation.Restore(ownedSession(), 4_500L),
            observedAtElapsedRealtimeMs = 2_000L,
        )

        assertTrue(cancel is TweakerV4OperationAssessment.Accepted)
        assertNull((cancel as TweakerV4OperationAssessment.Accepted).owner)
        assertTrue(restore is TweakerV4OperationAssessment.Accepted)
        assertEquals(
            TweakerV4SessionState.STOCK,
            (restore as TweakerV4OperationAssessment.Accepted).truth.state,
        )
    }

    private fun parse(bundle: Bundle): TweakerV4SessionAssessment =
        parseTweakerV4SessionBundle(
            bundle = bundle,
            expectedApiVersion = 4,
            expectedCallerRequestId = REQUEST_ID,
            expectedCallerDeadlineElapsedRealtimeMs = DEADLINE_MS,
            observedAtElapsedRealtimeMs = 1_000L,
        )

    private fun stockBundle(): Bundle = Bundle().apply {
        putInt("apiVersion", 4)
        putBoolean("success", true)
        putBoolean("applied", false)
        putBoolean("restored", false)
        putString("code", "SESSION_QUERY")
        putString("callerRequestId", REQUEST_ID)
        putLong("callerDeadlineElapsedRealtimeMs", DEADLINE_MS)
        putString("sessionState", "STOCK")
        putBoolean("mutationMayBeActive", false)
        putBoolean("leaseRenewable", false)
    }

    private fun recordBundle(state: String): Bundle = stockBundle().apply {
        putString("sessionState", state)
        putBoolean("mutationMayBeActive", true)
        putString("requestId", "owner-request")
        putLong("generation", 7L)
        putString("transactionId", "tx-7")
        putLong("requestDeadlineElapsedRealtimeMs", 3_000L)
        putLong("hardLeaseDeadlineElapsedRealtimeMs", 10_000L)
    }

    private fun applyAttempt() = TweakerV4ApplyAttempt(
        requestId = "owner-request",
        profileId = "balanced",
        packageName = "dev.legacy.eden_emulator",
        requestDeadlineElapsedRealtimeMs = 3_000L,
    )

    private fun ownedSession() = TweakerV4OwnedSession(
        requestId = "owner-request",
        generation = 7L,
        transactionId = "tx-7",
        requestDeadlineElapsedRealtimeMs = 3_000L,
        leaseDeadlineElapsedRealtimeMs = 8_000L,
        hardLeaseDeadlineElapsedRealtimeMs = 10_000L,
    )

    private fun activeOperationBundle(
        callerDeadline: Long,
        code: String,
        applied: Boolean = false,
        leaseDeadline: Long = 8_000L,
    ): Bundle = stockOperationBundle(
        callerDeadline = callerDeadline,
        code = code,
        applied = applied,
    ).apply {
        putString("sessionState", "ACTIVE")
        putBoolean("mutationMayBeActive", true)
        putString("requestId", "owner-request")
        putLong("generation", 7L)
        putString("transactionId", "tx-7")
        putLong("requestDeadlineElapsedRealtimeMs", 3_000L)
        putLong("leaseDeadlineElapsedRealtimeMs", leaseDeadline)
        putLong("hardLeaseDeadlineElapsedRealtimeMs", 10_000L)
        putBoolean("leaseRenewable", true)
    }

    private fun applyingOperationBundle(callerDeadline: Long): Bundle = stockOperationBundle(
        callerDeadline = callerDeadline,
        code = "CANCEL_REQUESTED",
    ).apply {
        putString("sessionState", "APPLYING")
        putString("requestId", "owner-request")
    }

    private fun stockOperationBundle(
        callerDeadline: Long,
        success: Boolean = true,
        code: String,
        applied: Boolean = false,
        restored: Boolean = false,
    ): Bundle = Bundle().apply {
        putInt("apiVersion", 4)
        putBoolean("success", success)
        putBoolean("applied", applied)
        putBoolean("restored", restored)
        putString("code", code)
        putString("message", "test result")
        putString("callerRequestId", "owner-request")
        putLong("callerDeadlineElapsedRealtimeMs", callerDeadline)
        putString("sessionState", "STOCK")
        putBoolean("mutationMayBeActive", false)
        putBoolean("leaseRenewable", false)
    }

    private companion object {
        const val REQUEST_ID = "mimir.query.test.1"
        const val DEADLINE_MS = 2_000L
    }
}

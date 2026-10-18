package com.gruning.mimir.rewrite.core.tweaker

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Odin 3 calibration driver.
 *
 * The host starts 3DMark only after [ReadyMarker] appears. The profile owner remains alive for the
 * bounded hold, samples public telemetry, and requests authoritative Stock in `finally`.
 */
@RunWith(AndroidJUnit4::class)
class Odin3DMarkProfileCalibrationDeviceTest {
    @Test
    fun holdQualifiedSystemProfileAndRestoreStock() {
        runBlocking {
            assertTrue("This calibration driver is scoped to Odin3", Build.MODEL.equals("Odin3", true))
            val arguments = InstrumentationRegistry.getArguments()
            val profileId = arguments.getString(ProfileArgument)
                ?.trim()
                ?.lowercase()
                .orEmpty()
            val holdSeconds = arguments.getString(HoldSecondsArgument)
                ?.toIntOrNull()
                ?.coerceIn(MinimumHoldSeconds, MaximumHoldSeconds)
                ?: DefaultHoldSeconds
            assertTrue(
                "profile must be one of $TWEAKER_SYSTEM_SELECTABLE_PROFILE_IDS",
                profileId in TWEAKER_SYSTEM_SELECTABLE_PROFILE_IDS,
            )

            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val session = TweakerSession(context)
            var apiVersion: Int? = null
            var workloadSamples = 0
            var eligibleWorkloadSamples = 0
            try {
                if (profileId != TWEAKER_STOCK_PROFILE_ID) {
                    assertTrue(
                        "Tweaker active-session admission was rejected",
                        session.beginActiveSessionV4(),
                    )
                    SystemClock.sleep(AdmissionSettleMs)
                }
                assertTrue("signature-protected Tweaker service did not bind", session.bind())
                val negotiatedApiVersion = requireNotNull(session.getApiVersion())
                apiVersion = negotiatedApiVersion
                assertTrue(
                    "System-wide profile selection requires API v5 or newer",
                    negotiatedApiVersion >= TWEAKER_V5_SYSTEM_SCOPE_MIN_API_VERSION,
                )
                val capabilities = requireNotNull(session.getCapabilities())
                assertEquals(TweakerPosture.PRODUCTION, capabilities.posture)
                assertEquals(TweakerQualificationSource.SIGNED_MANIFEST, capabilities.qualificationSource)
                assertEquals(true, capabilities.mutationAvailable)
                assertTrue(
                    "Requested profile is not published by the signed helper",
                    profileId in selectableTweakerProfileIds(capabilities),
                )

                val selected = selectWithAdmissionRetry(session, negotiatedApiVersion, profileId)
                when (profileId) {
                    TWEAKER_STOCK_PROFILE_ID -> assertTrue(
                        "Stock selection did not settle authoritatively: $selected",
                        selected is TweakerV5SystemSelectionAssessment.Stock,
                    )
                    else -> {
                        assertTrue(
                            "Qualified profile did not become active: $selected",
                            selected is TweakerV5SystemSelectionAssessment.Active,
                        )
                        selected as TweakerV5SystemSelectionAssessment.Active
                        assertEquals(profileId, selected.truth.activeProfileId)
                    }
                }

                Log.i(ProofTag, "$ReadyMarker profile=$profileId holdSeconds=$holdSeconds")
                repeat(holdSeconds) { second ->
                    if (second % TelemetryLogIntervalSeconds == 0) {
                        val truth = session.querySessionV4(negotiatedApiVersion)
                        assertExpectedSessionTruth(profileId, truth)
                        val telemetry = requireNotNull(session.getTelemetry()) {
                            "Public Tweaker telemetry disappeared during calibration"
                        }
                        Log.i(
                            ProofTag,
                            "sample second=$second profile=$profileId truth=$truth telemetry=$telemetry",
                        )
                        if (second >= WorkloadLaunchGraceSeconds) {
                            eligibleWorkloadSamples += 1
                            if (telemetry.foregroundPackageName == WorkloadPackage &&
                                (telemetry.gpuBusyPercent ?: 0) >= WorkloadGpuBusyFloorPercent
                            ) {
                                workloadSamples += 1
                            }
                        }
                    }
                    SystemClock.sleep(1_000L)
                }
                val minimumWorkloadSamples =
                    (eligibleWorkloadSamples * MinimumWorkloadCoveragePercent + 99) / 100
                assertTrue(
                    "WLE workload coverage was insufficient: $workloadSamples/$eligibleWorkloadSamples",
                    eligibleWorkloadSamples > 0 && workloadSamples >= minimumWorkloadSamples,
                )
                Log.i(
                    ProofTag,
                    "$CompletedMarker profile=$profileId holdSeconds=$holdSeconds " +
                        "workloadSamples=$workloadSamples/$eligibleWorkloadSamples",
                )
            } finally {
                val version = apiVersion
                if (version != null && session.isBound) {
                    val restored = runCatching {
                        selectWithAdmissionRetry(session, version, TWEAKER_STOCK_PROFILE_ID)
                    }.getOrNull()
                    Log.i(ProofTag, "$RestoredMarker result=$restored")
                    assertTrue(
                        "Calibration did not return to authoritative Stock: $restored",
                        restored is TweakerV5SystemSelectionAssessment.Stock,
                    )
                }
                session.unbind()
                if (version != null) verifyIndependentStock(context, version)
            }
        }
    }

    private fun assertExpectedSessionTruth(
        profileId: String,
        assessment: TweakerV4SessionAssessment,
    ) {
        if (profileId == TWEAKER_STOCK_PROFILE_ID) {
            assertTrue(
                "Stock drifted during calibration: $assessment",
                assessment is TweakerV4SessionAssessment.Stock,
            )
            return
        }
        assertTrue(
            "Qualified profile stopped being active during calibration: $assessment",
            assessment is TweakerV4SessionAssessment.Blocked,
        )
        val truth = (assessment as TweakerV4SessionAssessment.Blocked).truth
        assertEquals(TweakerV4SessionState.ACTIVE, truth.state)
        assertEquals(TweakerSessionScope.SYSTEM, truth.scope)
        assertEquals(profileId, truth.activeProfileId)
        assertTrue("Active profile lost mutation authority", truth.mutationMayBeActive)
    }

    private suspend fun verifyIndependentStock(context: android.content.Context, apiVersion: Int) {
        var latest: TweakerV4SessionAssessment =
            TweakerV4SessionAssessment.Uncertain("Independent Stock verification did not run")
        repeat(IndependentStockAttempts) {
            val verifier = TweakerSession(context)
            try {
                if (verifier.bind()) {
                    latest = verifier.querySessionV4(apiVersion)
                    if (latest is TweakerV4SessionAssessment.Stock) {
                        Log.i(ProofTag, "$IndependentStockMarker result=$latest")
                        return
                    }
                }
            } finally {
                verifier.unbind()
            }
            SystemClock.sleep(IndependentStockRetryMs)
        }
        throw AssertionError("Independent eventual-Stock verification failed: $latest")
    }

    private suspend fun selectWithAdmissionRetry(
        session: TweakerSession,
        apiVersion: Int,
        profileId: String,
    ): TweakerV5SystemSelectionAssessment {
        var latest: TweakerV5SystemSelectionAssessment =
            TweakerV5SystemSelectionAssessment.Uncertain("selection did not run")
        repeat(AdmissionAttempts) { attemptIndex ->
            val attempt = requireNotNull(
                session.createSystemSelectionAttemptV5(profileId, SelectionTimeoutMs),
            )
            latest = session.selectSystemProfileV5(apiVersion, attempt)
            val retryable = (latest as? TweakerV5SystemSelectionAssessment.Rejected)
                ?.code == ServiceLifetimeNotStarted
            if (!retryable) return latest
            SystemClock.sleep(AdmissionSettleMs * (attemptIndex + 1L))
        }
        return latest
    }

    private companion object {
        const val ProfileArgument = "mimirProfile"
        const val HoldSecondsArgument = "mimirHoldSeconds"
        const val ProofTag = "Mimir3DMarkDriver"
        const val ReadyMarker = "CALIBRATION_READY"
        const val CompletedMarker = "CALIBRATION_HOLD_COMPLETE"
        const val RestoredMarker = "CALIBRATION_STOCK_RESTORED"
        const val IndependentStockMarker = "CALIBRATION_INDEPENDENT_STOCK"
        const val WorkloadPackage = "com.futuremark.dmandroid.application"
        const val ServiceLifetimeNotStarted = "SERVICE_LIFETIME_NOT_STARTED"
        const val DefaultHoldSeconds = 1_320
        const val MinimumHoldSeconds = 60
        const val MaximumHoldSeconds = 1_800
        const val TelemetryLogIntervalSeconds = 5
        const val WorkloadLaunchGraceSeconds = 30
        const val WorkloadGpuBusyFloorPercent = 50
        const val MinimumWorkloadCoveragePercent = 60
        const val AdmissionAttempts = 3
        const val AdmissionSettleMs = 500L
        const val SelectionTimeoutMs = 5_000L
        const val IndependentStockAttempts = 20
        const val IndependentStockRetryMs = 500L
    }
}

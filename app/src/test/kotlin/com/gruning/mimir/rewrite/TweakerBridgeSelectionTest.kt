package com.gruning.mimir.rewrite

import android.content.ContextWrapper
import com.gruning.mimir.rewrite.core.launch.LaunchAttemptId
import com.gruning.mimir.rewrite.core.tweaker.TWEAKER_V4_MIN_API_VERSION
import com.gruning.mimir.rewrite.core.tweaker.TweakerApplyResult
import com.gruning.mimir.rewrite.core.tweaker.TweakerCapabilities
import com.gruning.mimir.rewrite.core.tweaker.TweakerCapabilityStatus
import com.gruning.mimir.rewrite.core.tweaker.TweakerFanMode
import com.gruning.mimir.rewrite.core.tweaker.TweakerPreparationResult
import com.gruning.mimir.rewrite.core.tweaker.TweakerPosture
import com.gruning.mimir.rewrite.core.tweaker.TweakerQualificationSource
import com.gruning.mimir.rewrite.core.tweaker.TweakerSessionScope
import com.gruning.mimir.rewrite.core.tweaker.TweakerTelemetry
import com.gruning.mimir.rewrite.core.tweaker.interpretTunableApply
import com.gruning.mimir.rewrite.core.tweaker.TweakerLaunchOverlayMessage
import com.gruning.mimir.rewrite.core.tweaker.TweakerLaunchSelectionContext
import com.gruning.mimir.rewrite.core.tweaker.TweakerManualFanCapability
import com.gruning.mimir.rewrite.core.tweaker.TweakerSession
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityRowState
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityAvailability
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityStepOutcome
import com.gruning.mimir.rewrite.core.tweaker.TweakerV4SessionAssessment
import com.gruning.mimir.rewrite.core.tweaker.TweakerV4SessionState
import com.gruning.mimir.rewrite.core.tweaker.TweakerV4SessionTruth
import com.gruning.mimir.rewrite.core.tweaker.TweakerV5SystemSelectionAssessment
import com.gruning.mimir.rewrite.core.tweaker.selectableTweakerProfileIds
import com.gruning.mimir.rewrite.core.tweaker.toLaunchOverlayOrNull
import com.gruning.mimir.rewrite.core.tweaker.tweakerUtilityAvailability
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TweakerBridgeSelectionTest {
    @Test
    fun `selectable profiles are stock plus certified only`() {
        assertEquals(
            listOf("stock", "performance"),
            selectableTweakerProfileIds(setOf("performance")),
        )
    }

    @Test
    fun `lab candidates enter utility only in a debug build with every lab gate true`() {
        val lab = TweakerCapabilities(
            status = TweakerCapabilityStatus.SUPPORTED,
            deviceProfileId = "ayn-odin3-cq8725s-pserver",
            hardwareFingerprint = "odin3",
            supportedAxes = setOf("CPU", "GPU"),
            notes = emptyList(),
            posture = TweakerPosture.LAB,
            dryRun = false,
            usageAccessGranted = true,
            diagnosticProfileIds = setOf("quiet", "balanced", "performance"),
            diagnosticMutationAvailable = true,
        )

        assertTrue(includeLabProfilesInUtility(lab, debugBuild = true))
        assertFalse(includeLabProfilesInUtility(lab, debugBuild = false))
        assertFalse(includeLabProfilesInUtility(lab.copy(dryRun = true), debugBuild = true))
        assertFalse(includeLabProfilesInUtility(lab.copy(usageAccessGranted = false), debugBuild = true))
        assertFalse(includeLabProfilesInUtility(lab.copy(diagnosticMutationAvailable = false), debugBuild = true))
    }

    @Test
    fun `system selection retries only the asynchronous started-service admission race`() {
        assertTrue(
            shouldRetrySystemProfileAdmission(
                TweakerV5SystemSelectionAssessment.Rejected(
                    code = "SERVICE_LIFETIME_NOT_STARTED",
                    message = "pending",
                    truth = v4Truth(
                        state = TweakerV4SessionState.STOCK,
                        mutationMayBeActive = false,
                    ),
                ),
            ),
        )
        assertFalse(
            shouldRetrySystemProfileAdmission(
                TweakerV5SystemSelectionAssessment.Rejected(
                    code = "JOURNAL_PERSIST_FAILED",
                    message = "blocked",
                    truth = v4Truth(
                        state = TweakerV4SessionState.STOCK,
                        mutationMayBeActive = false,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `fan control admits the same bounded Guardian transaction window as profile selection`() {
        val startedAt = 41_000L
        val session = TweakerSession(
            appContext = FanAttemptTestContext(),
            elapsedRealtimeMs = { startedAt },
        )
        val capability = TweakerManualFanCapability(
            modes = TweakerFanMode.entries.toSet(),
            curveBiasRange = -100..100,
            curveBiasStep = 10,
            curveSensorType = "GPU",
            currentMode = TweakerFanMode.AUTO,
            currentCurveBias = 0,
            ownerRequestId = "mimir.system.1",
            generation = 7L,
            transactionId = "tx-7",
            profileId = "performance",
        )
        val attempt = requireNotNull(
            session.createFanControlAttemptV6(
                capability = capability,
                mode = TweakerFanMode.MANUAL,
                curveBias = 0,
                timeoutMs = TweakerBridge.FAN_CONTROL_TIMEOUT_MS,
            ),
        )

        assertEquals(60_000L, TweakerBridge.FAN_CONTROL_TIMEOUT_MS)
        assertEquals(startedAt + 60_000L, attempt.requestDeadlineElapsedRealtimeMs)
    }

    private val certifiedPerformance = TweakerLaunchSelectionContext(
        selectedProfileId = "performance",
        certifiedProfileIds = setOf("performance"),
    )

    @Test
    fun `stale selection stays silent`() {
        val stale = certifiedPerformance.copy(selectedProfileId = "balanced", certifiedProfileIds = setOf("performance"))
        val warning = TweakerPreparationResult.Skipped(
            "Performance profile apply did not take — stock settings restored",
        ).toLaunchWarningOrNull(stale)
        assertNull(warning)
    }

    @Test
    fun `fps only stock ticket does not claim stock tuning was armed`() {
        assertNull(
            TweakerPreparationResult.Scheduled("stock").toLaunchOverlayOrNull(
                TweakerLaunchSelectionContext("stock"),
                "Game",
            ),
        )
    }

    @Test
    fun `unavailable certified selection warns without claiming stock`() {
        val warning = TweakerPreparationResult.Unavailable.toLaunchWarningOrNull(certifiedPerformance)
        assertNotNull(warning)
        assertEquals("Tweaker unavailable — launch continues without tuning", warning)
    }

    @Test
    fun `prepare tunable launch defers restore until apply is not confirmed`() {
        var restoreCalls = 0
        val restore = {
            restoreCalls++
            TweakerApplyResult(true, false, true, "RESTORED", "Stock state restored.", null)
        }

        val applied = interpretTunableApply(
            profileId = "performance",
            apply = TweakerApplyResult(true, true, false, "APPLIED", "Profile applied.", "performance"),
            restoreFallback = restore,
        )
        assertEquals(TweakerPreparationResult.Applied("performance"), applied)
        assertEquals(0, restoreCalls)

        val skipped = interpretTunableApply(
            profileId = "balanced",
            apply = TweakerApplyResult(false, false, false, "CALIBRATION_REQUIRED", "Uncertified.", "balanced"),
            restoreFallback = restore,
        ) as TweakerPreparationResult.Skipped
        assertEquals("Performance profile apply did not take — stock settings restored", skipped.reason)
        assertEquals(1, restoreCalls)
    }

    private fun caps(status: TweakerCapabilityStatus) = TweakerCapabilities(
        status = status,
        deviceProfileId = "tw03",
        hardwareFingerprint = "rp5",
        supportedAxes = emptySet(),
        certifiedProfileIds = setOf("performance"),
        notes = emptyList(),
        posture = TweakerPosture.PRODUCTION,
        mutationAvailable = true,
        qualificationSource = TweakerQualificationSource.SIGNED_MANIFEST,
    )

    private fun row(status: TweakerCapabilityStatus, selected: String) = TweakerUtilityRowState(
        availability = tweakerUtilityAvailability(installed = true, capabilities = caps(status)),
        selectedProfileId = selected,
        selectableProfileIds = selectableTweakerProfileIds(setOf("performance")),
    )

    @Test
    fun `stock profile is never labeled not certified`() {
        assertEquals("STOCK", row(TweakerCapabilityStatus.SUPPORTED, "stock").valueLabelCaps)
    }

    @Test
    fun `visible ready row reaches binder once despite concurrent unavailable projection`() = runTest {
        val visible = row(TweakerCapabilityStatus.SUPPORTED, "stock")
        val concurrentRefresh = TweakerUtilityRowState(
            availability = TweakerUtilityAvailability.UNAVAILABLE,
        )
        var binderCalls = 0
        var requestedAtBinder: String? = null

        val outcome = renderedTweakerStepOutcome(visible, direction = 1)
            as TweakerUtilityStepOutcome.Changed
        assertEquals(
            TweakerUtilityStepOutcome.Unavailable,
            renderedTweakerStepOutcome(concurrentRefresh, direction = 1),
        )
        val result = TweakerBridge.selectSystemProfile(
            profileId = outcome.profileId,
            admittedProfileIds = visible.selectableProfileIds.toSet(),
            bindAndSelect = { requested ->
                binderCalls++
                requestedAtBinder = requested
                TweakerSystemProfileSelectionResult.Active(requested, changed = true)
            },
        )

        assertEquals(1, binderCalls)
        assertEquals("performance", requestedAtBinder)
        assertEquals(
            TweakerSystemProfileSelectionResult.Active("performance", changed = true),
            result,
        )
    }

    @Test
    fun `rendered admission rejects a profile the visible row never offered before binder`() = runTest {
        var binderCalls = 0

        val result = TweakerBridge.selectSystemProfile(
            profileId = "performance",
            admittedProfileIds = setOf("stock"),
            bindAndSelect = {
                binderCalls++
                TweakerSystemProfileSelectionResult.Active(it, changed = true)
            },
        )

        assertEquals(0, binderCalls)
        assertEquals(TweakerSystemProfileSelectionResult.Unavailable, result)
    }

    @Test
    fun `stock profile ignores calibration owed on reachable capabilities`() {
        assertEquals("STOCK", row(TweakerCapabilityStatus.CALIBRATION_REQUIRED, "stock").valueLabelCaps)
    }

    @Test
    fun `certified profile ignores global calibration owed`() {
        assertEquals(
            "PERFORMANCE",
            row(TweakerCapabilityStatus.CALIBRATION_REQUIRED, "performance").valueLabelCaps,
        )
    }

    @Test
    fun `the row tells the owner to install only when the package is genuinely absent`() {
        val absent = TweakerUtilityRowState(
            tweakerUtilityAvailability(installed = false, capabilities = null),
        )
        assertEquals("INSTALL TWEAKER", absent.valueLabelCaps)

        // Installed but the service never answered: still one row, and it does not tell the player to
        // install what they already have.
        val refusedBind = TweakerUtilityRowState(
            tweakerUtilityAvailability(installed = true, capabilities = null),
        )
        assertEquals("UNAVAILABLE", refusedBind.valueLabelCaps)
        assertEquals(
            "STOCK ONLY",
            TweakerUtilityRowState(
                tweakerUtilityAvailability(true, caps(TweakerCapabilityStatus.UNSUPPORTED)),
            ).valueLabelCaps,
        )
    }

    @Test
    fun `applied preparation is not mislabeled active before post dispatch readback`() {
        val overlay = TweakerPreparationResult.Applied("balanced").toLaunchOverlayOrNull(
            certifiedPerformance.copy(selectedProfileId = "balanced", certifiedProfileIds = setOf("balanced")),
            "Pokemon Z-A",
        )
        assertNull(overlay)
        assertNull(
            TweakerPreparationResult.Applied("balanced").toLaunchWarningOrNull(
                certifiedPerformance.copy(selectedProfileId = "balanced", certifiedProfileIds = setOf("balanced")),
            ),
        )
    }

    @Test
    fun `post dispatch active readback uses exact profile copy and authored title casing`() {
        assertEquals(
            TweakerLaunchOverlayMessage.Confirmation(
                headline = "TWEAKER · BALANCED · ACTIVE",
                supportingText = "READY FOR Pokemon Z-A.",
            ),
            TweakerV4LaunchRuntimeOutcome.Active("balanced")
                .toPostDispatchOverlayOrNull("Pokemon Z-A"),
        )
        assertNull(
            TweakerV4LaunchRuntimeOutcome.Active("stock")
                .toPostDispatchOverlayOrNull("Pokemon Z-A"),
        )
    }

    @Test
    fun `prepared active profile is confirmed only by matching post dispatch system truth`() {
        val active = TweakerV4SessionAssessment.Blocked(
            v4Truth(
                apiVersion = 5,
                state = TweakerV4SessionState.ACTIVE,
                mutationMayBeActive = true,
                scope = TweakerSessionScope.SYSTEM,
                activeProfileId = "balanced",
            ).copy(
                requestId = "mimir.system.post-dispatch.1",
                generation = 4L,
                transactionId = "txn-post-dispatch",
                requestDeadlineElapsedRealtimeMs = 5_000L,
                leaseDeadlineElapsedRealtimeMs = 50_000L,
                hardLeaseDeadlineElapsedRealtimeMs = 50_000L,
            ),
        )
        val observed = caps(TweakerCapabilityStatus.SUPPORTED)

        assertEquals("balanced", confirmedPreparedActiveProfile("balanced", active, observed))
        assertNull(confirmedPreparedActiveProfile("performance", active, observed))
        assertNull(confirmedPreparedActiveProfile("balanced", v4Stock(apiVersion = 5), observed))
        assertNull(confirmedPreparedActiveProfile("balanced", active, null))
    }

    @Test
    fun `v4 stock query stays compatible but strips system-wide mutation authority`() {
        var queryCalls = 0
        var capabilityCalls = 0

        val outcome = prepareBoundTweaker(
            apiVersion = 4,
            selectedProfileId = "performance",
            queryV4 = {
                queryCalls++
                v4Stock()
            },
            readCapabilities = {
                capabilityCalls++
                caps(TweakerCapabilityStatus.SUPPORTED)
            },
        )

        assertEquals(TweakerPreparationResult.StockNoOp, outcome.preparation)
        assertEquals(emptySet<String>(), outcome.capabilities?.certifiedProfileIds)
        assertEquals(1, queryCalls)
        assertEquals(1, capabilityCalls)
    }

    @Test
    fun `v4 non-stock and malformed truth fail closed without legacy mutation`() {
        var capabilityCalls = 0
        val active = TweakerV4SessionAssessment.Blocked(
            v4Truth(
                state = TweakerV4SessionState.ACTIVE,
                mutationMayBeActive = true,
            ),
        )

        listOf<TweakerV4SessionAssessment>(
            active,
            TweakerV4SessionAssessment.Uncertain("bad echo"),
        ).forEach { assessment ->
            val outcome = prepareBoundTweaker(
                apiVersion = 4,
                selectedProfileId = "performance",
                queryV4 = { assessment },
                readCapabilities = {
                    capabilityCalls++
                    caps(TweakerCapabilityStatus.SUPPORTED)
                },
            )

            assertTrue(outcome.preparation is TweakerPreparationResult.Skipped)
            assertNull(outcome.capabilities)
        }
        assertEquals(0, capabilityCalls)
    }

    @Test
    fun `canonical system-wide truth retains capabilities and confirms active launch tuning`() {
        var capabilityCalls = 0
        val systemActive = TweakerV4SessionAssessment.Blocked(
            v4Truth(
                apiVersion = 5,
                state = TweakerV4SessionState.ACTIVE,
                mutationMayBeActive = true,
                scope = TweakerSessionScope.SYSTEM,
                activeProfileId = "performance",
            ).copy(
                requestId = "mimir.system.test.1",
                generation = 1L,
                transactionId = "txn-system-test",
                requestDeadlineElapsedRealtimeMs = 5_000L,
                leaseDeadlineElapsedRealtimeMs = 50_000L,
                hardLeaseDeadlineElapsedRealtimeMs = 50_000L,
            ),
        )

        val outcome = prepareBoundTweaker(
            apiVersion = 5,
            selectedProfileId = "performance",
            queryV4 = { systemActive },
            readCapabilities = {
                capabilityCalls++
                caps(TweakerCapabilityStatus.SUPPORTED)
            },
        )

        assertEquals(TweakerPreparationResult.Applied("performance"), outcome.preparation)
        assertEquals("performance", authoritativeProfileId(systemActive))
        assertNotNull(outcome.capabilities)
        assertEquals(1, capabilityCalls)
    }

    @Test
    fun `api four truth cannot claim an active system-wide profile`() {
        var capabilityCalls = 0
        val legacySystemFields = TweakerV4SessionAssessment.Blocked(
            v4Truth(
                apiVersion = 4,
                state = TweakerV4SessionState.ACTIVE,
                mutationMayBeActive = true,
                scope = TweakerSessionScope.SYSTEM,
                activeProfileId = "performance",
            ),
        )

        assertNull(authoritativeProfileId(legacySystemFields))
        assertNull(
            authoritativeV4Capabilities(legacySystemFields) {
                capabilityCalls++
                caps(TweakerCapabilityStatus.SUPPORTED)
            },
        )
        assertEquals(0, capabilityCalls)
    }

    @Test
    fun `helper downgrade retires stale system profile capabilities immediately`() {
        val staleV5Capabilities = caps(TweakerCapabilityStatus.SUPPORTED)

        val apiFourCapabilities = failClosedCapabilitiesForSystemSelectionDowngrade(
            apiVersion = 4,
            assessment = v4Stock(apiVersion = 4),
            readCapabilities = { staleV5Capabilities },
        )
        assertNotNull(apiFourCapabilities)
        assertEquals(emptySet<String>(), apiFourCapabilities?.certifiedProfileIds)
        assertEquals(emptySet<String>(), apiFourCapabilities?.diagnosticProfileIds)

        val legacyCapabilities = failClosedCapabilitiesForSystemSelectionDowngrade(
            apiVersion = 3,
            assessment = TweakerV4SessionAssessment.Uncertain("API v4 unavailable"),
            readCapabilities = { staleV5Capabilities },
        )
        assertNull(legacyCapabilities)
    }

    @Test
    fun `utility feedback is concise and truthful`() {
        assertEquals(
            "EFFICIENCY · ACTIVE",
            TweakerSystemProfileSelectionResult.Active("quiet", changed = true)
                .systemProfileToastCaps(),
        )
        assertEquals(
            "STOCK · TUNING OFF",
            TweakerSystemProfileSelectionResult.Stock(changed = true).systemProfileToastCaps(),
        )
        assertEquals(
            "PROFILE STATUS UNCONFIRMED",
            TweakerSystemProfileSelectionResult.Uncertain("transport").systemProfileToastCaps(),
        )
        assertEquals(
            MimirMessageClass.Confirmation,
            TweakerSystemProfileSelectionResult.Active("quiet", changed = true)
                .systemProfileMessageClass(),
        )
        assertEquals(
            MimirMessageClass.Warning,
            TweakerSystemProfileSelectionResult.Rejected("PROFILE_NOT_CERTIFIED")
                .systemProfileMessageClass(),
        )
    }

    @Test
    fun `a daemon older than v4 is unavailable, keeps nothing and is never read or queried`() {
        listOf(Int.MIN_VALUE, -1, 0, 1, 2, 3).forEach { oldApiVersion ->
            var queryCalls = 0
            var capabilityReads = 0

            val outcome = prepareBoundTweaker(
                apiVersion = oldApiVersion,
                selectedProfileId = "stock",
                queryV4 = {
                    queryCalls++
                    v4Stock()
                },
                readCapabilities = {
                    capabilityReads++
                    caps(TweakerCapabilityStatus.SUPPORTED)
                },
            )

            assertEquals("api $oldApiVersion", TweakerPreparationResult.Unavailable, outcome.preparation)
            assertNull("api $oldApiVersion keeps no capability truth, not even a stripped copy", outcome.capabilities)
            assertEquals("api $oldApiVersion is never asked a v4 question", 0, queryCalls)
            assertEquals("api $oldApiVersion is never read", 0, capabilityReads)
        }
    }

    @Test
    fun `v4 is the first version that is asked`() {
        var queryCalls = 0

        val outcome = prepareBoundTweaker(
            apiVersion = TWEAKER_V4_MIN_API_VERSION,
            selectedProfileId = "stock",
            queryV4 = {
                queryCalls++
                v4Stock()
            },
            readCapabilities = { caps(TweakerCapabilityStatus.SUPPORTED) },
        )

        assertEquals(1, queryCalls)
        assertEquals(TweakerPreparationResult.StockNoOp, outcome.preparation)
    }

    @Test
    fun `the bridge no longer carries a restore-on-return path for a daemon older than v4`() {
        val source = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/TweakerBridge.kt").readText()

        listOf("settleLegacyReturn", "legacyTuningMayBeActive", "nextTuningMayBeActive", "LegacyReturnSettlement")
            .forEach { removed -> assertFalse("`$removed` must stay removed", source.contains(removed)) }
    }

    @Test
    fun `unknown api never falls through to legacy mutation`() {
        var queryCalls = 0
        val outcome = prepareBoundTweaker(
            apiVersion = null,
            selectedProfileId = "performance",
            queryV4 = {
                queryCalls++
                v4Stock()
            },
            readCapabilities = { caps(TweakerCapabilityStatus.SUPPORTED) },
        )

        assertEquals(TweakerPreparationResult.Unavailable, outcome.preparation)
        assertNull(outcome.capabilities)
        assertEquals(0, queryCalls)
    }

    @Test
    fun `signed product selection creates only a memory ticket after stock preflight`() {
        val ticket = pendingV4LaunchFor(
            attemptId = TestAttemptId,
            preparation = TweakerPreparationResult.StockNoOp,
            capabilities = caps(TweakerCapabilityStatus.SUPPORTED),
            selectedProfileId = "performance",
            packageName = "dev.legacy.eden_emulator",
            expiresAtElapsedRealtimeMs = 5_000L,
            includeDebugLab = false,
        )

        assertEquals(
            PendingTweakerV4Launch.Product(
                attemptId = TestAttemptId,
                profileId = "performance",
                packageName = "dev.legacy.eden_emulator",
                expiresAtElapsedRealtimeMs = 5_000L,
            ),
            ticket,
        )
        assertNull(
            pendingV4LaunchFor(
                attemptId = TestAttemptId,
                preparation = TweakerPreparationResult.StockNoOp,
                capabilities = caps(TweakerCapabilityStatus.SUPPORTED),
                selectedProfileId = "stock",
                packageName = "dev.legacy.eden_emulator",
                expiresAtElapsedRealtimeMs = 5_000L,
                includeDebugLab = false,
            ),
        )
    }

    /**
     * A profile the daemon admits must be honoured like any
     * other. The Nova's daemon admits chipset-supported-but-unsigned profiles
     * (`supportedUncertifiedProfileIds`), never `certifiedProfileIds` -- the blast-radius case this
     * pins is that the launch TICKET path (`pendingV4LaunchFor`, which the real app calls) must
     * accept that pick exactly like `signed product selection creates only a memory ticket after
     * stock preflight` above accepts a signed one, not silently drop it to Stock.
     */
    @Test
    fun `an admitted chipset-supported profile gets the same launch ticket as a signed one`() {
        val novaLikeCaps = TweakerCapabilities(
            status = TweakerCapabilityStatus.CALIBRATION_REQUIRED,
            deviceProfileId = "retroid-qcs8550-pserver",
            hardwareFingerprint = "nova",
            supportedAxes = setOf("CPU", "GPU"),
            certifiedProfileIds = emptySet(),
            supportedUncertifiedProfileIds = setOf("quiet", "balanced", "performance"),
            notes = emptyList(),
            posture = TweakerPosture.PRODUCTION,
            mutationAvailable = true,
            qualificationSource = TweakerQualificationSource.NONE,
        )

        val ticket = pendingV4LaunchFor(
            attemptId = TestAttemptId,
            preparation = TweakerPreparationResult.StockNoOp,
            capabilities = novaLikeCaps,
            selectedProfileId = "balanced",
            packageName = "dev.legacy.eden_emulator",
            expiresAtElapsedRealtimeMs = 5_000L,
            includeDebugLab = false,
        )

        assertEquals(
            PendingTweakerV4Launch.Product(
                attemptId = TestAttemptId,
                profileId = "balanced",
                packageName = "dev.legacy.eden_emulator",
                expiresAtElapsedRealtimeMs = 5_000L,
            ),
            ticket,
        )
    }

    @Test
    fun `per package fps policy codec round trips and rejects malformed input`() {
        val stored = TweakerGameFpsPolicy(frameLimitHz = 60, revision = 9L)
        val cleared = TweakerGameFpsPolicy(frameLimitHz = null, revision = 10L)

        assertEquals(stored, decodeTweakerGameFpsPolicy(encodeTweakerGameFpsPolicy(stored)))
        assertEquals(cleared, decodeTweakerGameFpsPolicy(encodeTweakerGameFpsPolicy(cleared)))
        assertNull(decodeTweakerGameFpsPolicy("2|60|9"))
        assertNull(decodeTweakerGameFpsPolicy("1|45|9"))
        assertNull(decodeTweakerGameFpsPolicy("1|60|-1"))
        assertNull(decodeTweakerGameFpsPolicy("garbage"))
    }

    @Test
    fun `per package fps policy key requires a concrete package and never names the legacy slot`() {
        assertEquals(
            "game_fps_policy.dev.legacy.eden_emulator",
            gameFpsPolicyPreferenceKey("dev.legacy.eden_emulator"),
        )
        assertNull(gameFpsPolicyPreferenceKey(""))
        assertNull(gameFpsPolicyPreferenceKey("no-dots"))
        assertNull(gameFpsPolicyPreferenceKey("game_fps_limit_hz"))
        // The legacy slot's own key can never be produced, so its value can never read back as a cap.
        assertTrue(GAME_FPS_POLICY_KEY_PREFIX.isNotEmpty())
        assertNotEquals("game_fps_limit_hz", gameFpsPolicyPreferenceKey("any.game"))
    }

    @Test
    fun `per package fps revision is monotonic and refuses a saturated slot`() {
        val next = nextTweakerGameFpsPolicy(
            previous = TweakerGameFpsPolicy(frameLimitHz = 60, revision = 4L),
            frameLimitHz = null,
        )

        assertEquals(TweakerGameFpsPolicy(frameLimitHz = null, revision = 5L), next)
        assertNull(
            nextTweakerGameFpsPolicy(
                previous = TweakerGameFpsPolicy(frameLimitHz = 60, revision = Long.MAX_VALUE),
                frameLimitHz = 40,
            ),
        )
    }

    @Test
    fun `live fps foreground attestation fails closed on stale missing or self identity`() {
        val telemetry = TweakerTelemetry(
            sampledAtElapsedRealtimeMs = 10_000,
            foregroundPackageName = "dev.legacy.eden_emulator",
            frameStatus = null,
            frameAverageFpsMilli = null,
            cpuTempC = null,
            gpuTempC = null,
            cpuBusyPercent = null,
            gpuBusyPercent = null,
            batteryPowerMw = null,
            batteryStatus = null,
        )
        val self = "com.gruning.mimir.rewrite"

        assertEquals(
            "dev.legacy.eden_emulator",
            attestedLiveFpsForegroundPackage(telemetry, 13_500, self),
        )
        assertNull(attestedLiveFpsForegroundPackage(null, 13_500, self))
        assertNull(attestedLiveFpsForegroundPackage(telemetry, 13_501, self))
        assertNull(attestedLiveFpsForegroundPackage(telemetry, 9_999, self))
        assertNull(
            attestedLiveFpsForegroundPackage(
                telemetry.copy(sampledAtElapsedRealtimeMs = null),
                13_500,
                self,
            ),
        )
        assertNull(
            attestedLiveFpsForegroundPackage(
                telemetry.copy(foregroundPackageName = null),
                13_500,
                self,
            ),
        )
        assertNull(
            attestedLiveFpsForegroundPackage(
                telemetry.copy(foregroundPackageName = self),
                13_500,
                self,
            ),
        )
        assertNull(
            attestedLiveFpsForegroundPackage(
                telemetry.copy(foregroundPackageName = TweakerSession.TWEAKER_PACKAGE),
                13_500,
                self,
            ),
        )
    }

    @Test
    fun `missing stored fps policy omits frame limit from launch ticket`() {
        val ticket = composePendingTweakerLaunch(
            baseTicket = null,
            attemptId = TestAttemptId,
            packageName = "dev.legacy.eden_emulator",
            expiresAtElapsedRealtimeMs = 5_000L,
            systemProfileId = "stock",
            preparation = TweakerPreparationResult.StockNoOp,
            productProfileIds = listOf("stock", "balanced"),
            storedFrameLimitHz = null,
            storedGameFpsPolicyRevision = 9L,
        )

        assertNull(ticket)
    }

    @Test
    fun `stored fps policy composes one immutable exact package ticket`() {
        val ticket = composePendingTweakerLaunch(
            baseTicket = null,
            attemptId = TestAttemptId,
            packageName = "dev.legacy.eden_emulator",
            expiresAtElapsedRealtimeMs = 5_000L,
            systemProfileId = "stock",
            preparation = TweakerPreparationResult.StockNoOp,
            productProfileIds = listOf("stock", "balanced"),
            storedFrameLimitHz = 60,
            storedGameFpsPolicyRevision = 3L,
        ) as PendingTweakerV4Launch.Product

        assertFalse(ticket.activateProfile)
        assertEquals("stock", ticket.profileId)
        assertEquals("stock", ticket.systemProfileId)
        assertEquals(60, ticket.frameLimitHz)
        assertEquals(3L, ticket.gameFpsPolicyRevision)
    }

    @Test
    fun `active system intent wins profile while retaining the admitted stored fps cap`() {
        val base = PendingTweakerV4Launch.Product(
            attemptId = TestAttemptId,
            profileId = "performance",
            packageName = "dev.legacy.eden_emulator",
            expiresAtElapsedRealtimeMs = 5_000L,
        )

        val ticket = composePendingTweakerLaunch(
            baseTicket = base,
            attemptId = TestAttemptId,
            packageName = base.packageName,
            expiresAtElapsedRealtimeMs = 5_000L,
            systemProfileId = "performance",
            preparation = TweakerPreparationResult.StockNoOp,
            productProfileIds = listOf("stock", "quiet", "performance"),
            storedFrameLimitHz = 40,
            storedGameFpsPolicyRevision = 4L,
        ) as PendingTweakerV4Launch.Product

        assertEquals("performance", ticket.profileId)
        assertEquals("performance", ticket.systemProfileId)
        assertTrue(ticket.activateProfile)
        assertEquals(40, ticket.frameLimitHz)
        assertEquals(4L, ticket.gameFpsPolicyRevision)
    }

    @Test
    fun `already active system profile produces an fps only ticket without reactivation`() {
        val ticket = composePendingTweakerLaunch(
            baseTicket = null,
            attemptId = TestAttemptId,
            packageName = "dev.legacy.eden_emulator",
            expiresAtElapsedRealtimeMs = 5_000L,
            systemProfileId = "balanced",
            preparation = TweakerPreparationResult.Applied("balanced"),
            productProfileIds = listOf("stock", "quiet", "balanced", "performance"),
            storedFrameLimitHz = 60,
            storedGameFpsPolicyRevision = 7L,
        ) as PendingTweakerV4Launch.Product

        assertFalse(ticket.activateProfile)
        assertEquals("balanced", ticket.profileId)
        assertEquals("balanced", ticket.systemProfileId)
        assertEquals(60, ticket.frameLimitHz)
        assertEquals(7L, ticket.gameFpsPolicyRevision)
    }

    @Test
    fun `changed stored fps revision invalidates the post dispatch ticket`() {
        val ticket = PendingTweakerV4Launch.Product(
            attemptId = TestAttemptId,
            profileId = "stock",
            packageName = "dev.legacy.eden_emulator",
            expiresAtElapsedRealtimeMs = 5_000L,
            activateProfile = false,
            systemProfileId = "stock",
            frameLimitHz = 60,
            gameFpsPolicyRevision = 5L,
        )

        assertTrue(
            pendingProductTicketStillAdmitted(
                ticket,
                "stock",
                TweakerGameFpsPolicy(frameLimitHz = 60, revision = 5L),
                listOf("stock", "balanced"),
                fpsLimiterAvailable = true,
            ),
        )
        assertFalse(
            pendingProductTicketStillAdmitted(
                ticket,
                "stock",
                TweakerGameFpsPolicy(frameLimitHz = 60, revision = 6L),
                listOf("stock", "balanced"),
                fpsLimiterAvailable = true,
            ),
        )
    }

    @Test
    fun `bind failure discards stale reachable stock authority`() {
        val staleReachable = caps(TweakerCapabilityStatus.SUPPORTED)

        assertNull(
            capabilitiesAfterTweakerBindAttempt(
                connected = false,
                cached = staleReachable,
            ),
        )
        assertTrue(
            capabilitiesAfterTweakerBindAttempt(
                connected = true,
                cached = staleReachable,
            ) === staleReachable,
        )
    }

    @Test
    fun `session reservation serializes observation owner and stock transition`() {
        val reservation = TweakerSessionReservation()

        assertTrue(reservation.tryBeginObservation())
        assertFalse(reservation.tryBeginObservation())
        assertFalse(reservation.tryBeginOwner())
        assertFalse(reservation.reserveOwnerForSettlement())
        reservation.endObservation()

        assertTrue(reservation.reserveOwnerForSettlement())
        assertFalse(reservation.tryBeginObservation())
        assertFalse(reservation.tryBeginOwner())
        assertTrue(reservation.reserveOwnerForSettlement())
        assertTrue(reservation.transitionOwnerToObservation())
        assertFalse(reservation.tryBeginOwner())
        reservation.endObservation()

        assertTrue(reservation.tryBeginObservation())
        reservation.endObservation()
    }

    @Test
    fun `return settlement preempts telemetry observation and receives the same token`() = runTest {
        val reservation = TweakerSessionReservation()
        assertTrue(reservation.tryBeginObservation())

        val settlement = async {
            reservation.awaitOwnerForSettlement(
                elapsedRealtimeMs = { testScheduler.currentTime },
            )
        }
        runCurrent()
        assertTrue(reservation.shouldYieldObservation())
        assertFalse(settlement.isCompleted)

        reservation.endObservation()
        runCurrent()

        assertTrue(settlement.await())
        assertFalse(reservation.tryBeginObservation())
        assertTrue(reservation.transitionOwnerToObservation())
        reservation.endObservation()
    }

    @Test
    fun `exclusive mutation preempts telemetry but never joins a settlement owner`() = runTest {
        val reservation = TweakerSessionReservation()
        assertTrue(reservation.tryBeginObservation())

        val mutation = async {
            reservation.awaitExclusiveOwnerForMutation(
                elapsedRealtimeMs = { testScheduler.currentTime },
            )
        }
        runCurrent()
        assertTrue(reservation.shouldYieldObservation())
        assertFalse(mutation.isCompleted)

        // Return settlement cannot steal the transfer that belongs to the mutation.
        assertFalse(reservation.awaitOwnerForSettlement(timeoutMs = 1L))
        reservation.endObservation()
        runCurrent()
        assertTrue(mutation.await())

        // A second mutation and return settlement both reject the live mutation owner.
        assertFalse(reservation.awaitExclusiveOwnerForMutation(timeoutMs = 1L))
        assertFalse(reservation.awaitOwnerForSettlement(timeoutMs = 1L))
        reservation.endOwner()
        assertTrue(reservation.tryBeginObservation())
        reservation.endObservation()
    }

    @Test
    fun `exclusive mutation rejects an existing generic owner`() = runTest {
        val reservation = TweakerSessionReservation()
        assertTrue(reservation.tryBeginOwner())
        assertFalse(reservation.awaitExclusiveOwnerForMutation(timeoutMs = 1L))
        reservation.endOwner()
    }

    @Test
    fun `timed out observer yield cancels its reservation request cleanly`() = runTest {
        val reservation = TweakerSessionReservation()
        assertTrue(reservation.tryBeginObservation())

        val settlement = async {
            reservation.awaitOwnerForSettlement(
                timeoutMs = 50L,
                elapsedRealtimeMs = { testScheduler.currentTime },
            )
        }
        runCurrent()
        advanceTimeBy(50L)
        runCurrent()

        assertFalse(settlement.await())
        assertFalse(reservation.shouldYieldObservation())
        reservation.endObservation()
        assertTrue(reservation.tryBeginObservation())
        reservation.endObservation()
    }

    @Test
    fun `cancelled settlement waiter releases a transferred owner reservation`() = runTest {
        val reservation = TweakerSessionReservation()
        val waitGate = CompletableDeferred<Unit>()
        assertTrue(reservation.tryBeginObservation())

        val settlement = launch {
            reservation.awaitOwnerForSettlement(
                elapsedRealtimeMs = { testScheduler.currentTime },
                wait = { waitGate.await() },
            )
        }
        runCurrent()
        assertTrue(reservation.shouldYieldObservation())

        reservation.endObservation()
        settlement.cancelAndJoin()

        assertFalse(reservation.shouldYieldObservation())
        assertTrue(reservation.tryBeginObservation())
        reservation.endObservation()
    }

    @Test
    fun `profile selection control gives either Back or final settlement one atomic winner`() {
        val cancelled = TweakerProfileSelectionControl()
        assertTrue(cancelled.requestCancellation())
        assertTrue(cancelled.requestCancellation())
        assertFalse(cancelled.claimUncancelledSettlement())
        cancelled.markSettled()
        assertFalse(cancelled.requestCancellation())

        val committed = TweakerProfileSelectionControl()
        assertTrue(committed.claimUncancelledSettlement())
        assertFalse(committed.requestCancellation())
        committed.markSettled()
        assertFalse(committed.requestCancellation())
    }

    @Test
    fun `profile change copy keeps prior truth while applying and restoring`() {
        val applying = TweakerProfileChangeState(
            operationId = 9L,
            priorProfileId = "stock",
            requestedProfileId = "performance",
            phase = TweakerProfileChangePhase.APPLYING,
        )
        assertEquals("APPLYING · PERFORMANCE", applying.supportingTextCaps())
        assertEquals(
            "RESTORING · STOCK",
            applying.copy(phase = TweakerProfileChangePhase.CANCELLING).supportingTextCaps(),
        )
        assertEquals(
            "PROFILE CHANGE CANCELLED",
            TweakerSystemProfileSelectionResult.Cancelled("stock").systemProfileToastCaps(),
        )

        assertEquals(
            "APPLYING · MANUAL FAN",
            TweakerFanControlChangeState(
                operationId = 10L,
                priorMode = TweakerFanMode.AUTO,
                requestedMode = TweakerFanMode.MANUAL,
                priorCurveBias = 0,
                requestedCurveBias = 0,
            ).supportingTextCaps(),
        )
        assertEquals(
            "APPLYING · FAN RESPONSE",
            TweakerFanControlChangeState(
                operationId = 11L,
                priorMode = TweakerFanMode.MANUAL,
                requestedMode = TweakerFanMode.MANUAL,
                priorCurveBias = 0,
                requestedCurveBias = -20,
            ).supportingTextCaps(),
        )
    }

    @Test
    fun `Back before mutation performs zero selection calls`() = runTest {
        val control = TweakerProfileSelectionControl().also { it.requestCancellation() }
        var calls = 0

        val result = cancellableProfileSelectionAttempts(
            attemptCount = 3,
            control = control,
            select = { calls += 1; "applied" },
            shouldRetry = { false },
            waitBeforeRetry = {},
        )

        assertEquals(CancellableProfileSelectionAttempt.CancelledBeforeMutation, result)
        assertEquals(0, calls)
    }

    @Test
    fun `Back during admission retry prevents the next Binder attempt`() = runTest {
        val control = TweakerProfileSelectionControl()
        var calls = 0
        var waits = 0

        val result = cancellableProfileSelectionAttempts(
            attemptCount = 3,
            control = control,
            select = {
                calls += 1
                control.requestCancellation()
                "service-lifetime-not-started"
            },
            shouldRetry = { true },
            waitBeforeRetry = { waits += 1 },
        )

        assertEquals(CancellableProfileSelectionAttempt.CancelledBeforeMutation, result)
        assertEquals(1, calls)
        assertEquals(0, waits)
    }

    @Test
    fun `Back arriving during a completed call requires compensation`() = runTest {
        val control = TweakerProfileSelectionControl()
        val result = cancellableProfileSelectionAttempts(
            attemptCount = 3,
            control = control,
            select = {
                control.requestCancellation()
                "active-max"
            },
            shouldRetry = { false },
            waitBeforeRetry = {},
        )

        assertEquals(CancellableProfileSelectionAttempt.Completed("active-max"), result)
        assertFalse(control.claimUncancelledSettlement())
    }

    @Test
    fun `compensating restore confirms exact Stock or prior active profile only`() {
        assertEquals(
            TweakerSystemProfileSelectionResult.Cancelled("stock"),
            cancelledSystemProfileSelectionResult("stock", "stock", true),
        )
        assertEquals(
            TweakerSystemProfileSelectionResult.Cancelled("balanced"),
            cancelledSystemProfileSelectionResult("balanced", "balanced", true),
        )
        assertTrue(
            cancelledSystemProfileSelectionResult("balanced", "performance", true) is
                TweakerSystemProfileSelectionResult.Uncertain,
        )
        assertTrue(
            cancelledSystemProfileSelectionResult("balanced", "balanced", false) is
                TweakerSystemProfileSelectionResult.Uncertain,
        )
    }

    private fun v4Stock(apiVersion: Int = 4): TweakerV4SessionAssessment.Stock =
        TweakerV4SessionAssessment.Stock(
            v4Truth(
                apiVersion = apiVersion,
                state = TweakerV4SessionState.STOCK,
                mutationMayBeActive = false,
            ),
        )

    private fun v4Truth(
        apiVersion: Int = 4,
        state: TweakerV4SessionState,
        mutationMayBeActive: Boolean,
        scope: TweakerSessionScope = TweakerSessionScope.UNKNOWN,
        activeProfileId: String? = null,
    ): TweakerV4SessionTruth = TweakerV4SessionTruth(
        apiVersion = apiVersion,
        state = state,
        mutationMayBeActive = mutationMayBeActive,
        requestId = null,
        generation = null,
        transactionId = null,
        requestDeadlineElapsedRealtimeMs = null,
        leaseDeadlineElapsedRealtimeMs = null,
        hardLeaseDeadlineElapsedRealtimeMs = null,
        leaseRenewable = false,
        scope = scope,
        activeProfileId = activeProfileId,
    )

    private companion object {
        val TestAttemptId = LaunchAttemptId.fromTestValue("bridge-selection")
    }
}

private class FanAttemptTestContext : ContextWrapper(null)

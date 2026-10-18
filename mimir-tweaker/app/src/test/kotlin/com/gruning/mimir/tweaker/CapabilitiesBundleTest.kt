package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class CapabilitiesBundleTest {
    @Test
    fun `capabilities bundle carries posture mutation and fail-closed profile ids`() {
        val capabilities = HardwareCapabilities(
            status = CapabilityStatus.CALIBRATION_REQUIRED,
            manufacturer = "Moorechip",
            model = "Retroid Pocket 5",
            socModel = "SM8250",
            buildFingerprint = "rp5/test",
            deviceProfileId = "rp5",
            supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU),
            pServerAvailable = true,
            cpuPolicies = emptyList(),
            gpuFrequenciesKhz = listOf(305_000, 905_000),
            gpuNodeAvailable = true,
            fanNodeAvailable = true,
            refreshRatesHz = listOf(60),
            notes = emptyList(),
            certifiedProfileIds = emptySet(),
            posture = TweakerPostureTruth.SAFE,
            mutationAvailable = false,
            dryRun = true,
            usageAccessGranted = false,
            qualificationSource = TweakerPostureTruth.QUALIFICATION_LOCAL_REPORT,
        )

        val bundle = capabilities.toBundle()
        assertEquals(TweakerPostureTruth.SAFE, bundle.getString("posture"))
        assertFalse(bundle.getBoolean("mutationAvailable"))
        assertTrue(bundle.getBoolean("dryRun"))
        assertTrue(bundle.getBoolean("hasUsageAccessGranted"))
        assertFalse(bundle.getBoolean("usageAccessGranted"))
        assertEquals(
            TweakerPostureTruth.QUALIFICATION_LOCAL_REPORT,
            bundle.getString("qualificationSource"),
        )
        assertTrue(bundle.getStringArrayList("certifiedProfileIds")!!.isEmpty())
        assertTrue(bundle.getStringArrayList("qualifiedProfileIds")!!.isEmpty())
    }

    @Test
    fun `supported-uncertified profile ids are carried on the bundle, disjoint from certified`() {
        val capabilities = HardwareCapabilities(
            status = CapabilityStatus.CALIBRATION_REQUIRED,
            manufacturer = "Moorechip",
            model = "Retroid Pocket 5",
            socModel = "SM8250",
            buildFingerprint = "rp5/test",
            deviceProfileId = "rp5",
            supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU),
            pServerAvailable = true,
            cpuPolicies = emptyList(),
            gpuFrequenciesKhz = listOf(305_000, 905_000),
            gpuNodeAvailable = true,
            fanNodeAvailable = true,
            refreshRatesHz = listOf(60),
            notes = emptyList(),
            certifiedProfileIds = emptySet(),
            supportedUncertifiedProfileIds = setOf("quiet", "balanced", "performance"),
            posture = TweakerPostureTruth.PRODUCTION,
            mutationAvailable = false,
            dryRun = true,
            usageAccessGranted = false,
            qualificationSource = TweakerPostureTruth.QUALIFICATION_NONE,
        )

        val bundle = capabilities.toBundle()
        assertTrue(bundle.getStringArrayList("certifiedProfileIds")!!.isEmpty())
        assertEquals(
            setOf("quiet", "balanced", "performance"),
            bundle.getStringArrayList("supportedUncertifiedProfileIds")!!.toSet(),
        )
    }

    @Test
    fun `missing usage access stays non-authoritative in the bundle`() {
        val capabilities = HardwareCapabilities(
            status = CapabilityStatus.UNSUPPORTED,
            manufacturer = "x",
            model = "y",
            socModel = "z",
            buildFingerprint = "fp",
            deviceProfileId = "unknown",
            supportedAxes = emptySet(),
            pServerAvailable = false,
            cpuPolicies = emptyList(),
            gpuFrequenciesKhz = emptyList(),
            gpuNodeAvailable = false,
            fanNodeAvailable = false,
            refreshRatesHz = emptyList(),
            notes = emptyList(),
            usageAccessGranted = null,
        )
        val bundle = capabilities.toBundle()
        assertFalse(bundle.getBoolean("hasUsageAccessGranted"))
    }

    @Test
    fun `public result returns exact session lease truth without profile details`() {
        val result = TransactionResult(
            success = true,
            applied = true,
            restored = false,
            code = "APPLIED",
            message = "ok",
            plan = TuningPlan(
                profileId = "performance",
                packageName = "example.game",
                cpuMaxKhz = mapOf(0 to 1_804_800),
                gpuMaxPowerLevel = 0,
                fanMode = 3,
                calibrated = false,
            ),
            transactionId = "tx-7",
            requestId = "request-7",
            generation = 7L,
            requestDeadlineElapsedRealtimeMs = 9_000L,
            hardLeaseDeadlineElapsedRealtimeMs = 91_000L,
        )
        val bundle = result.toPublicBundle(
            callerRequestId = "request-7",
            callerDeadlineElapsedRealtimeMs = 9_000L,
            truth = PublicSessionTruth(
                state = PublicSessionState.ACTIVE,
                mutationMayBeActive = true,
                requestId = "request-7",
                generation = 7L,
                transactionId = "tx-7",
                requestDeadlineElapsedRealtimeMs = 9_000L,
                leaseDeadlineElapsedRealtimeMs = 31_000L,
                hardLeaseDeadlineElapsedRealtimeMs = 91_000L,
                leaseRenewable = true,
            ),
        )

        assertEquals("request-7", bundle.getString("callerRequestId"))
        assertEquals("request-7", bundle.getString("requestId"))
        assertEquals(7L, bundle.getLong("generation"))
        assertEquals("tx-7", bundle.getString("transactionId"))
        assertEquals(9_000L, bundle.getLong("requestDeadlineElapsedRealtimeMs"))
        assertEquals(31_000L, bundle.getLong("leaseDeadlineElapsedRealtimeMs"))
        assertEquals(91_000L, bundle.getLong("hardLeaseDeadlineElapsedRealtimeMs"))
        assertEquals(PublicSessionState.ACTIVE.name, bundle.getString("sessionState"))
        assertTrue(bundle.getBoolean("mutationMayBeActive"))
        assertTrue(bundle.getBoolean("leaseRenewable"))
        assertFalse(bundle.containsKey("profileId"))
        assertFalse(bundle.containsKey("packageName"))
        assertFalse(bundle.containsKey("cpuMaxKhz"))
        assertFalse(bundle.containsKey("gpuMaxPowerLevel"))
        assertFalse(bundle.containsKey("fanMode"))
        assertFalse(bundle.containsKey("calibrated"))
    }

    @Test
    fun `public Stock truth never inherits the retired transaction identity`() {
        val bundle = TransactionResult(
            success = true,
            applied = false,
            restored = true,
            code = "RESTORED",
            message = "Stock restored",
            transactionId = "retired-tx",
            requestId = "retired-request",
            generation = 9L,
            requestDeadlineElapsedRealtimeMs = 20_000L,
            hardLeaseDeadlineElapsedRealtimeMs = 90_000L,
        ).toPublicBundle(
            callerRequestId = "stock-request",
            callerDeadlineElapsedRealtimeMs = 25_000L,
            truth = PublicSessionTruth(
                state = PublicSessionState.STOCK,
                mutationMayBeActive = false,
                leaseRenewable = false,
            ),
        )

        assertEquals(PublicSessionState.STOCK.name, bundle.getString("sessionState"))
        assertFalse(bundle.getBoolean("mutationMayBeActive"))
        assertTrue(bundle.getBoolean("restored"))
        assertFalse(bundle.containsKey("requestId"))
        assertFalse(bundle.containsKey("generation"))
        assertFalse(bundle.containsKey("transactionId"))
        assertFalse(bundle.containsKey("requestDeadlineElapsedRealtimeMs"))
        assertFalse(bundle.containsKey("leaseDeadlineElapsedRealtimeMs"))
        assertFalse(bundle.containsKey("hardLeaseDeadlineElapsedRealtimeMs"))
    }
}
